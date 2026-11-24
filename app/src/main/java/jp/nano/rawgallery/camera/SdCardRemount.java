package jp.nano.rawgallery.camera;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.Settings;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import jp.nano.rawgallery.BuildConfig;
import jp.nano.rawgallery.PhotoStorage;
import rikka.shizuku.Shizuku;

/** Runs the optional SD remount independently of the Activity that requested it. */
public final class SdCardRemount {
    public enum Status { UNAVAILABLE, NEEDS_PERMISSION, READY }
    public enum Result { MOUNTED, SKIPPED, UNAVAILABLE, FAILED, RECONNECTED, SOURCE_MISSING }

    public static final String ENABLED_PREFERENCE = "sd_remount_on_entry";
    private static final int CONNECT_MS = 8_000;
    private static final int PERMISSION_MS = 90_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static PermissionRequest permission;
    private static int permissionCode;

    private SdCardRemount() {}

    /** Called once at process startup, before import or move can enter the gate. */
    public static void restoreReservation(Context app) {
        var preferences = app.getSharedPreferences("sd_remount_reservation", Context.MODE_PRIVATE);
        var saved = new SdRemountMountNotice.Reservation(preferences.getInt("boot", -1),
                preferences.getLong("blocked_until", 0), preferences.getString("source", null),
                preferences.getLong("muted_until", 0), preferences.getString("reconnect_source", null),
                preferences.getBoolean("reconnect_awaiting_mount", false));
        int boot;
        try {
            boot = Settings.Global.getInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException unavailable) {
            boot = -1;
        }
        var registration = app.getSharedPreferences("camera_import", Context.MODE_PRIVATE)
                .getString("sd_tree_uri", null);
        Uri tree = registration == null ? null : Uri.parse(registration);
        SdRemountMountNotice.restore(boot, SystemClock::elapsedRealtime, saved, next -> {
            try {
                var edit = preferences.edit();
                if (next.blockedUntil() == 0 && next.source() == null && next.mutedUntil() == 0 && next.reconnectSource() == null) {
                    edit.clear();
                } else {
                    edit.putInt("boot", next.boot()).putLong("blocked_until", next.blockedUntil())
                            .putString("source", next.source()).putLong("muted_until", next.mutedUntil())
                            .putString("reconnect_source", next.reconnectSource())
                            .putBoolean("reconnect_awaiting_mount", next.awaitingReconnectMount());
                }
                return edit.commit();
            } catch (RuntimeException unavailable) {
                return false;
            }
        }, uuid -> MountedCameraCard.matchesMountNotice(tree, uuid));
    }

    public static Status status() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.getUid() != SdCardRemountUserService.SHELL_UID
                    || Shizuku.getVersion() < 13) return Status.UNAVAILABLE;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    ? Status.READY : Status.NEEDS_PERMISSION;
        } catch (RuntimeException unavailable) {
            return Status.UNAVAILABLE;
        }
    }

    public static void requestPermission(Consumer<Boolean> callback) {
        MAIN.post(() -> {
            Status current = status();
            if (current != Status.NEEDS_PERMISSION || permission != null) {
                callback.accept(current == Status.READY);
                return;
            }
            permission = new PermissionRequest(++permissionCode, callback);
            permission.request();
        });
    }

    private static final class PermissionRequest {
        final int code;
        final Consumer<Boolean> callback;
        final Shizuku.OnRequestPermissionResultListener listener;
        final Shizuku.OnBinderDeadListener binderDead = () -> finish(false);
        final Runnable timeout = () -> finish(false);

        PermissionRequest(int code, Consumer<Boolean> callback) {
            this.code = code;
            this.callback = callback;
            listener = (requestCode, decision) -> {
                if (requestCode == code) finish(decision == PackageManager.PERMISSION_GRANTED && status() == Status.READY);
            };
        }

        void request() {
            try {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    finish(false);
                    return;
                }
                Shizuku.addRequestPermissionResultListener(listener, MAIN);
                Shizuku.addBinderDeadListener(binderDead, MAIN);
                MAIN.postDelayed(timeout, PERMISSION_MS);
                Shizuku.requestPermission(code);
            } catch (RuntimeException unavailable) {
                finish(false);
            }
        }

        void finish(boolean granted) {
            if (permission != this) return;
            permission = null;
            MAIN.removeCallbacks(timeout);
            Shizuku.removeRequestPermissionResultListener(listener);
            Shizuku.removeBinderDeadListener(binderDead);
            callback.accept(granted);
        }
    }

    public static boolean isRunning() { return RUNNING.get() || SdRemountMountNotice.blocksImport(); }

    public static boolean start(Context context, Consumer<Result> callback) {
        Context app = context.getApplicationContext();
        if (RUNNING.get() || CameraImportGate.lock.isLocked() || selection(app) == null || status() != Status.READY
                || !RUNNING.compareAndSet(false, true)) return false;
        MAIN.post(() -> new Operation(app, null, callback).bind());
        return true;
    }

    public static boolean canReconnect(Context context) {
        return reconnectTarget(context) != null;
    }

    public static boolean reconnect(Context context, ReconnectTarget confirmed, Consumer<Result> callback) {
        Context app = context.getApplicationContext();
        if (RUNNING.get() || CameraImportGate.lock.isLocked() || status() != Status.READY
                || confirmed == null || !confirmed.equals(reconnectTarget(app))
                || !RUNNING.compareAndSet(false, true)) return false;
        MAIN.post(() -> new Operation(app, confirmed, callback).bind());
        return true;
    }

    static Shizuku.UserServiceArgs serviceArgs(Context context) {
        return new Shizuku.UserServiceArgs(new ComponentName(context, SdCardRemountUserService.class))
                .processNameSuffix("sd_remount").daemon(false).version(BuildConfig.VERSION_CODE);
    }

    private static final class Operation implements ServiceConnection {
        final Context app;
        final Shizuku.UserServiceArgs args;
        final ReconnectTarget reconnect;
        final Runnable connectionTimeout = () -> finish(Result.UNAVAILABLE);
        final CompletableFuture<Result> remoteResult = new CompletableFuture<>();
        Consumer<Result> completion;
        boolean bindingAttempted;
        boolean dispatched;

        Operation(Context app, ReconnectTarget reconnect, Consumer<Result> completion) {
            this.app = app;
            this.reconnect = reconnect;
            this.completion = completion;
            args = serviceArgs(app);
        }

        void bind() {
            if (status() != Status.READY) {
                finish(Result.UNAVAILABLE);
                return;
            }
            try {
                bindingAttempted = true;
                MAIN.postDelayed(connectionTimeout, CONNECT_MS);
                Shizuku.bindUserService(args, this);
            } catch (RuntimeException unavailable) {
                finish(Result.UNAVAILABLE);
            }
        }

        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            if (completion == null || dispatched) return;
            MAIN.removeCallbacks(connectionTimeout);
            dispatched = true;
            new Thread(() -> {
                Result result = runUnderGate(ISdCardRemountService.Stub.asInterface(service));
                MAIN.post(() -> finish(result));
            }, "sd-remount").start();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            // Once dispatched, hold the gate until the result or the remote deadline.
            if (!dispatched) MAIN.post(() -> finish(Result.UNAVAILABLE));
        }

        private Result runUnderGate(ISdCardRemountService service) {
            if (!CameraImportGate.lock.tryLock()) return Result.SKIPPED;
            boolean reserved = false;
            Result outcome = Result.FAILED;
            try {
                if (status() != Status.READY) return Result.UNAVAILABLE;
                Selection selection = reconnect == null ? selection(app) : null;
                if (reconnect == null ? selection == null : !Objects.equals(reconnect, reconnectTarget(app))) {
                    return Result.SKIPPED;
                }

                ISdCardRemountCallback callback = new ISdCardRemountCallback.Stub() {
                    @Override public void onResult(int value) {
                        if (Binder.getCallingUid() != SdCardRemountUserService.SHELL_UID) return;
                        remoteResult.complete(switch (value) {
                            case ISdCardRemountCallback.MOUNTED -> Result.MOUNTED;
                            case ISdCardRemountCallback.SKIPPED -> Result.SKIPPED;
                            case ISdCardRemountCallback.RECONNECTED -> Result.RECONNECTED;
                            case ISdCardRemountCallback.SOURCE_MISSING -> Result.SOURCE_MISSING;
                            default -> Result.FAILED;
                        });
                    }
                };
                long window = reconnect == null ? SdCardRemountPlan.OPERATION_MS : UsbReaderReconnectPlan.OPERATION_MS;
                long deadline = SystemClock.elapsedRealtime() + window;
                long blockedUntil = deadline + SdRemountMountNotice.RESULT_GRACE_MS;
                if (reconnect == null) {
                    reserved = SdRemountMountNotice.begin(selection.source(), blockedUntil);
                    if (!reserved) return Result.FAILED;
                    service.remount(selection.source(), selection.destination(), deadline, callback);
                } else {
                    reserved = SdRemountMountNotice.beginReconnect(registeredSourceUuid(app), blockedUntil);
                    if (!reserved) return Result.FAILED;
                    service.reconnect(reconnect.address(), reconnect.destination(), deadline, callback);
                }
                long remaining = Math.max(0, blockedUntil - SystemClock.elapsedRealtime());
                outcome = remoteResult.get(remaining, TimeUnit.MILLISECONDS);
                return outcome;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.FAILED;
            } catch (RemoteException | ExecutionException | TimeoutException | RuntimeException e) {
                return Result.FAILED;
            } finally {
                if (reserved && reconnect == null && (outcome == Result.MOUNTED || outcome == Result.SKIPPED)) {
                    SdRemountMountNotice.end(outcome == Result.MOUNTED);
                } else if (reserved && reconnect != null
                        && (outcome == Result.RECONNECTED || outcome == Result.SOURCE_MISSING || outcome == Result.SKIPPED)) {
                    SdRemountMountNotice.endReconnect(outcome != Result.SKIPPED, outcome == Result.SOURCE_MISSING);
                }
                CameraImportGate.lock.unlock();
            }
        }

        private void finish(Result result) {
            Consumer<Result> callback = completion;
            if (callback == null) return;
            completion = null;
            MAIN.removeCallbacks(connectionTimeout);
            if (bindingAttempted) {
                try {
                    Shizuku.unbindUserService(args, this, true);
                } catch (RuntimeException unavailable) {
                    // The Shizuku server may have already stopped.
                }
                try {
                    Shizuku.unbindUserService(args, this, false);
                } catch (RuntimeException unavailable) {
                    // The completed connection no longer retains the caller's callback.
                }
            }
            RUNNING.set(false);
            callback.accept(result);
        }
    }

    private record Selection(String source, String destination) {}
    public record ReconnectTarget(String address, String destination, long detachGeneration) {}

    public static ReconnectTarget reconnectTarget(Context context) {
        if (!SdCardRemountUserService.supportsUsbReconnect()) return null;
        try {
            Context app = context.getApplicationContext();
            String registered = app.getSharedPreferences("camera_import", Context.MODE_PRIVATE).getString("sd_tree_uri", null);
            if (registered != null && !MountedCameraCard.isRemovableTree(Uri.parse(registered))) return null;
            long generation = MountedCameraCard.detachGeneration();
            UsbManager manager = app.getSystemService(UsbManager.class);
            if (manager == null) return null;
            var devices = manager.getDeviceList();
            if (devices.size() != 1) return null;
            UsbDevice device = devices.values().iterator().next();
            if (device.getVendorId() != SdRemountUsbPort.VENDOR || device.getProductId() != SdRemountUsbPort.PRODUCT
                    || !UsbMassStorage.Companion.isMassStorage(device)
                    || !SdRemountUsbPort.validAddress(device.getDeviceName())) return null;
            String selected = PhotoStorage.selected(app);
            if (selected == null || PhotoStorage.PRIMARY.equals(selected)) return null;
            String destination = removableUuid(app, selected, true);
            return destination == null || !selected.equals(PhotoStorage.selected(app))
                    || generation != MountedCameraCard.detachGeneration() ? null
                    : new ReconnectTarget(device.getDeviceName(), destination, generation);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static String registeredSourceUuid(Context app) {
        try {
            String registered = app.getSharedPreferences("camera_import", Context.MODE_PRIVATE)
                    .getString("sd_tree_uri", null);
            if (registered == null) return null;
            Uri tree = Uri.parse(registered);
            String id = DocumentsContract.getTreeDocumentId(tree);
            int separator = id.indexOf(':');
            if (separator < 0) return null;
            String uuid = id.substring(0, separator);
            return MountedCameraCard.matchesMountNotice(tree, uuid) ? uuid : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static Selection selection(Context app) {
        try {
            var settings = app.getSharedPreferences("camera_import", Context.MODE_PRIVATE);
            if (!settings.getBoolean(ENABLED_PREFERENCE, false)) return null;
            String destination = PhotoStorage.selected(app);
            if (destination == null || PhotoStorage.PRIMARY.equals(destination) || !singleGenericReader(app)) return null;
            String registered = settings.getString("sd_tree_uri", null);
            if (registered == null) return null;
            Uri tree = Uri.parse(registered);
            String source = MountedCameraCard.requireRemovableSource(app, tree, destination);
            MountedCameraCard.requireGrant(app, tree);
            String sourceUuid = removableUuid(app, source, false);
            String destinationUuid = removableUuid(app, destination, false);
            if (sourceUuid == null || destinationUuid == null || sourceUuid.equalsIgnoreCase(destinationUuid)
                    || !destination.equals(PhotoStorage.selected(app))) return null;
            return new Selection(sourceUuid, destinationUuid);
        } catch (MountedCameraCard.Unavailable | RuntimeException unavailable) {
            return null;
        }
    }

    private static boolean singleGenericReader(Context app) {
        var manager = app.getSystemService(UsbManager.class);
        if (manager == null) return false;
        boolean found = false;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (!UsbMassStorage.Companion.isMassStorage(device)) continue;
            if (found || CameraUsb.knownCamera(device)) return false;
            found = true;
        }
        return found;
    }

    private static String removableUuid(Context app, String volumeName, boolean allowUnmounted) {
        String uuid = null;
        for (StorageVolume volume : app.getSystemService(StorageManager.class).getStorageVolumes()) {
            String candidate = volume.getUuid();
            String state = volume.getState();
            boolean knownUnmounted = allowUnmounted && Environment.MEDIA_UNMOUNTED.equals(state)
                    && SdCardRemountPlan.isFilesystemUuid(candidate);
            if (!volumeName.equals(volume.getMediaStoreVolumeName())
                    && !(knownUnmounted && volumeName.equalsIgnoreCase(candidate))) continue;
            if (uuid != null || !volume.isRemovable() || volume.isEmulated() || volume.isPrimary()
                    || !(Environment.MEDIA_MOUNTED.equals(state) || knownUnmounted)
                    || !SdCardRemountPlan.isFilesystemUuid(candidate)) return null;
            uuid = candidate;
        }
        return uuid;
    }
}
