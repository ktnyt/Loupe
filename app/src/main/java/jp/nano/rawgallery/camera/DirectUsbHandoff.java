package jp.nano.rawgallery.camera;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.provider.Settings;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import rikka.shizuku.Shizuku;

/** Keeps an Android-side recovery record until the privileged service verifies both volumes. */
final class DirectUsbHandoff {
    static final String RECOVERY = "取り込みを止めました。カードリーダーを挿し直し、Androidで両方のカードを確認してください。";
    private static final String PREFERENCES = "direct_usb_recovery";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long WORK_MS = TimeUnit.MINUTES.toMillis(3);
    private static final long EJECT_GRACE_MS = 30_000;
    private static final long CONNECT_MS = 8_000;
    private static final Object JOURNAL_LOCK = new Object();
    private static boolean leaseInFlight;

    interface Work {
        void run(DirectUsbTransport reader, BooleanSupplier cancelled) throws IOException;
    }

    private DirectUsbHandoff() {}

    static boolean supported(UsbDevice device) {
        return device != null && device.getVendorId() == 0x21c4 && device.getProductId() == 0xb060
                && SdCardRemountUserService.supportsUsbReconnect();
    }

    static boolean recoveryPending(Context context) {
        return preferences(context).contains("token");
    }

    static boolean suppressAutomaticMount(Context context) {
        synchronized (JOURNAL_LOCK) {
            return leaseInFlight || recoveryPending(context);
        }
    }

    static boolean blocksImport(Context context) {
        if (!recoveryPending(context)) return false;
        try {
            UsbManager manager = context.getSystemService(UsbManager.class);
            UsbDevice reader = null;
            for (UsbDevice device : manager.getDeviceList().values()) {
                if (!supported(device)) continue;
                if (reader != null) return true;
                reader = device;
            }
            return !settleIfRestored(context, reader);
        } catch (RuntimeException unavailable) {
            return true;
        }
    }

    /** On restart only settle a journal with proof of a fresh connection and both expected mounts. */
    static boolean settleIfRestored(Context context, UsbDevice device) {
        synchronized (JOURNAL_LOCK) {
            SharedPreferences prefs = preferences(context);
            if (!prefs.contains("token")) return true;
            if (leaseInFlight || !supported(device)) return false;
            UsbManager manager = context.getSystemService(UsbManager.class);
            var attached = manager.getDeviceList();
            if (!device.equals(attached.get(device.getDeviceName()))
                    || attached.values().stream().filter(DirectUsbHandoff::supported).count() != 1) return false;
            int previousBoot = prefs.getInt("boot", -1);
            int currentBoot = boot(context);
            boolean freshBoot = previousBoot >= 0 && currentBoot >= 0 && currentBoot != previousBoot;
            String oldAddress = prefs.getString("address", null);
            if (!freshBoot && (oldAddress == null || attached.containsKey(oldAddress))) return false;
            String source = prefs.getString("source", null);
            String target = prefs.getString("target", null);
            if (!mounted(context, source, false) || !mounted(context, target, true)) return false;
            return prefs.edit().clear().commit();
        }
    }

    static void run(Context context, UsbDevice device, String source, String target,
            BooleanSupplier cancelled, Work work) throws IOException {
        Context app = context.getApplicationContext();
        if (Looper.myLooper() == Looper.getMainLooper() || !CameraImportGate.lock.isHeldByCurrentThread())
            throw new IllegalStateException("USB handoff must run within the import gate off the UI thread");
        if (!supported(device) || SdCardRemount.status() != SdCardRemount.Status.READY
                || !settleIfRestored(app, device) || !mounted(app, source, false) || !mounted(app, target, true))
            throw new IOException(recoveryPending(app) ? RECOVERY : "AnkerリーダーとSD、microSDの接続を確認してください。");
        UsbManager manager = app.getSystemService(UsbManager.class);
        if (!manager.hasPermission(device)) throw new IOException("USBリーダーの利用許可が必要です。");
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("取り込みを中止しました。");
        Request request = new Request(app, device, source, target, manager, cancelled, work);
        synchronized (JOURNAL_LOCK) {
            if (leaseInFlight) throw new IOException(RECOVERY);
            leaseInFlight = true;
        }
        try {
            ISdCardRemountService storage = request.bind();
            CameraPhotoPublisher.check(cancelled);
            request.dispatched = true;
            storage.leaseDirect(device.getDeviceName(), source, target,
                    SystemClock.elapsedRealtime() + DirectUsbLeasePlan.EJECT_MS, request.callback);
            int outcome = request.awaitResult();
            if (outcome != ISdCardDirectLeaseCallback.TRANSFERRED || recoveryPending(app)) {
                if (outcome == ISdCardDirectLeaseCallback.RECOVERY_PENDING || recoveryPending(app))
                    throw new IOException(RECOVERY, request.workFailure);
                if (request.workFailure instanceof IOException failure) throw failure;
                throw new IOException("USBリーダーを使った取り込みを完了できませんでした。", request.workFailure);
            }
        } catch (android.os.RemoteException failure) {
            throw new IOException(recoveryPending(app) ? RECOVERY : "USBリーダーとの連携が切れました。", failure);
        } finally {
            request.stopAndUnbind();
            synchronized (JOURNAL_LOCK) {
                leaseInFlight = false;
            }
        }
    }

    private static final class Request implements ServiceConnection {
        final Context app;
        final UsbDevice device;
        final String source;
        final String target;
        final UsbManager manager;
        final BooleanSupplier cancelled;
        final Work work;
        final Shizuku.UserServiceArgs args;
        final CompletableFuture<ISdCardRemountService> connected = new CompletableFuture<>();
        final CompletableFuture<Integer> result = new CompletableFuture<>();
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicBoolean bound = new AtomicBoolean();
        final Object ownerState = new Object();
        boolean entered;
        volatile boolean dispatched;
        volatile Throwable workFailure;
        volatile boolean active;
        volatile long stoppedAt;

        Request(Context app, UsbDevice device, String source, String target, UsbManager manager,
                BooleanSupplier cancelled, Work work) {
            this.app = app;
            this.device = device;
            this.source = source;
            this.target = target;
            this.manager = manager;
            this.cancelled = cancelled;
            this.work = work;
            args = SdCardRemount.serviceArgs(app);
        }

        ISdCardRemountService bind() throws IOException {
            MAIN.post(() -> {
                if (stop.get()) return;
                try {
                    Shizuku.bindUserService(args, this);
                    bound.set(true);
                } catch (RuntimeException missing) {
                    connected.completeExceptionally(missing);
                }
            });
            try {
                return connected.get(CONNECT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Shizukuへの接続を中止しました。");
            } catch (ExecutionException | TimeoutException missing) {
                throw new IOException("Shizukuに接続できませんでした。", missing);
            }
        }

        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            connected.complete(ISdCardRemountService.Stub.asInterface(service));
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            connected.completeExceptionally(new IOException("Shizuku connection lost"));
            if (dispatched) result.complete(ISdCardDirectLeaseCallback.RECOVERY_PENDING);
        }

        boolean stopped() {
            if (stop.get()) return true;
            if (cancelled.getAsBoolean()) {
                stop.set(true);
                return true;
            }
            return false;
        }

        int awaitResult() {
            boolean interrupted = false;
            long deadline = SystemClock.elapsedRealtime() + WORK_MS;
            try {
                for (;;) {
                    long now = SystemClock.elapsedRealtime();
                    if (now >= deadline) stop.set(true);
                    if (stopped() && stoppedAt == 0) stoppedAt = now;
                    // Never use elapsed time to claim that an active USB callback has closed.
                    synchronized (ownerState) {
                        if (!active && stoppedAt > 0 && now - stoppedAt >= EJECT_GRACE_MS && !result.isDone()) {
                            stop.set(true);
                            return ISdCardDirectLeaseCallback.RECOVERY_PENDING;
                        }
                    }
                    try {
                        int outcome = result.get(500, TimeUnit.MILLISECONDS);
                        synchronized (ownerState) {
                            stop.set(true);
                            if (!active) return outcome;
                        }
                        Thread.sleep(100);
                    } catch (TimeoutException retry) {
                        // Continue holding the import gate while the callback or recovery runs.
                    } catch (InterruptedException requested) {
                        interrupted = true;
                        stop.set(true);
                    } catch (ExecutionException unexpected) {
                        stop.set(true);
                        synchronized (ownerState) {
                            if (!active) return ISdCardDirectLeaseCallback.RECOVERY_PENDING;
                        }
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        void stopAndUnbind() {
            stop.set(true);
            MAIN.post(() -> {
                if (!bound.getAndSet(false)) return;
                try {
                    // A lost/overdue reply does not authorize destroying a live storage owner.
                    Shizuku.unbindUserService(args, this, !dispatched || (!active && result.isDone()
                            && !recoveryPending(app)));
                } catch (RuntimeException unavailable) {
                    // The persisted marker still prevents further direct ownership.
                }
            });
        }

        final ISdCardDirectLeaseCallback callback = new ISdCardDirectLeaseCallback.Stub() {
            @Override public boolean persist(String token, String sd, String microSd) {
                if (Binder.getCallingUid() != SdCardRemountUserService.SHELL_UID || stopped()
                        || !Objects.equals(uuid(source), sd) || !Objects.equals(uuid(target), microSd)
                        || token == null || token.isEmpty()) return false;
                long clear = Binder.clearCallingIdentity();
                try {
                    synchronized (JOURNAL_LOCK) {
                        SharedPreferences prefs = preferences(app);
                        return !prefs.contains("token") && prefs.edit().putString("token", token)
                                .putString("source", sd).putString("target", microSd)
                                .putString("address", device.getDeviceName()).putInt("boot", boot(app)).commit();
                    }
                } finally {
                    Binder.restoreCallingIdentity(clear);
                }
            }

            @Override public int transfer(String token) {
                if (Binder.getCallingUid() != SdCardRemountUserService.SHELL_UID || token == null
                        || !token.equals(preferences(app).getString("token", null))) return OWNERSHIP_UNKNOWN;
                synchronized (ownerState) {
                    if (entered) return OWNERSHIP_UNKNOWN;
                    if (stopped()) return FAILED_CLOSED;
                    entered = true;
                    active = true;
                }
                long clear = Binder.clearCallingIdentity();
                try {
                    return readWrite();
                } finally {
                    synchronized (ownerState) {
                        active = false;
                    }
                    Binder.restoreCallingIdentity(clear);
                }
            }

            @Override public boolean clear(String token) {
                if (Binder.getCallingUid() != SdCardRemountUserService.SHELL_UID || active || token == null) return false;
                synchronized (JOURNAL_LOCK) {
                    SharedPreferences prefs = preferences(app);
                    return token.equals(prefs.getString("token", null)) && prefs.edit().clear().commit();
                }
            }

            @Override public void onFinished(int outcome) {
                if (Binder.getCallingUid() == SdCardRemountUserService.SHELL_UID) result.complete(outcome);
            }
        };

        private int readWrite() {
            if (stopped()) return ISdCardDirectLeaseCallback.FAILED_CLOSED;
            DirectUsbTransport reader;
            try {
                UsbDevice actual = manager.getDeviceList().get(device.getDeviceName());
                if (actual == null || !device.equals(actual) || !manager.hasPermission(actual)
                        || mounted(app, source, false) || mounted(app, target, false)) {
                    throw new IOException("USBリーダーかカードの状態が変わりました。");
                }
                reader = DirectUsbTransport.open(manager, actual);
            } catch (IOException | RuntimeException failure) {
                workFailure = failure;
                if (failure.getSuppressed().length != 0)
                    return ISdCardDirectLeaseCallback.OWNERSHIP_UNKNOWN;
                return ISdCardDirectLeaseCallback.FAILED_CLOSED;
            }
            try {
                try {
                    if (mounted(app, source, false) || mounted(app, target, false))
                        throw new IOException("USBアクセスの開始時にAndroidがカードを認識し直しました。");
                    work.run(reader, this::stopped);
                } catch (IOException | RuntimeException failure) {
                    workFailure = failure;
                    return ISdCardDirectLeaseCallback.FAILED_CLOSED;
                } finally {
                    reader.close();
                }
            } catch (IOException | RuntimeException unknown) {
                if (workFailure != null) unknown.addSuppressed(workFailure);
                workFailure = unknown;
                return ISdCardDirectLeaseCallback.OWNERSHIP_UNKNOWN;
            }
            return ISdCardDirectLeaseCallback.TRANSFER_OK;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static int boot(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    private static String uuid(String value) {
        if (value == null) return null;
        String compact = value.replace("-", "").toLowerCase(Locale.ROOT);
        return compact.matches("[0-9a-f]{8}") ? compact : null;
    }

    private static boolean mounted(Context app, String identity, boolean writable) {
        String expected = uuid(identity);
        if (expected == null) return false;
        return app.getSystemService(StorageManager.class).getStorageVolumes().stream()
                .anyMatch(volume -> volume.isRemovable() && expected.equals(uuid(volume.getUuid()))
                        && (Environment.MEDIA_MOUNTED.equals(volume.getState())
                        || (!writable && Environment.MEDIA_MOUNTED_READ_ONLY.equals(volume.getState()))));
    }
}
