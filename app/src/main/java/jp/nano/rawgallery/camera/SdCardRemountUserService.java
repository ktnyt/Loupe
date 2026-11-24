package jp.nano.rawgallery.camera;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jp.nano.rawgallery.BuildConfig;

/** Shizuku v13 supplies the package context for the user whose app requested this service. */
public final class SdCardRemountUserService extends ISdCardRemountService.Stub {
    static final int SHELL_UID = 2000;
    private static final int FIRST_APP_UID = 10_000;
    private final int ownerUid;

    public SdCardRemountUserService(Context context) {
        var app = context.getApplicationInfo();
        if (!BuildConfig.APPLICATION_ID.equals(context.getPackageName())
                || !BuildConfig.APPLICATION_ID.equals(app.packageName)
                || app.uid < FIRST_APP_UID || Process.myUid() != SHELL_UID) {
            throw new SecurityException("Unexpected remount service identity");
        }
        ownerUid = app.uid;
    }

    @Override public void remount(String sourceUuid, String destinationUuid, long deadlineMs,
            ISdCardRemountCallback callback) {
        if (Binder.getCallingUid() != ownerUid || Process.myUid() != SHELL_UID || callback == null) return;
        long now = SystemClock.elapsedRealtime();
        int result = ISdCardRemountCallback.SKIPPED;
        if (deadlineMs > now && deadlineMs <= now + SdCardRemountPlan.OPERATION_MS) {
            // AIDL serializes one-way requests on this Binder, including Shizuku's destroy transaction.
            result = switch (SdCardRemountPlan.execute(new Sm(null), sourceUuid, destinationUuid, deadlineMs)) {
                case MOUNTED -> ISdCardRemountCallback.MOUNTED;
                case SKIPPED -> ISdCardRemountCallback.SKIPPED;
                case FAILED -> ISdCardRemountCallback.FAILED;
            };
        }
        send(callback, result);
    }

    @Override public void reconnect(String address, String destinationUuid, long deadlineMs,
            ISdCardRemountCallback callback) {
        if (Binder.getCallingUid() != ownerUid || Process.myUid() != SHELL_UID || callback == null) return;
        long now = SystemClock.elapsedRealtime();
        int result = ISdCardRemountCallback.SKIPPED;
        if (deadlineMs > now && deadlineMs <= now + UsbReaderReconnectPlan.OPERATION_MS) {
            // Keep the one-way request alive until the data port and cards have been checked for recovery.
            result = switch (UsbReaderReconnectPlan.execute(new Sm(callback.asBinder()),
                    address, destinationUuid, deadlineMs)) {
                case RECONNECTED -> ISdCardRemountCallback.RECONNECTED;
                case SOURCE_MISSING -> ISdCardRemountCallback.SOURCE_MISSING;
                case SKIPPED -> ISdCardRemountCallback.SKIPPED;
                case FAILED -> ISdCardRemountCallback.FAILED;
            };
        }
        send(callback, result);
    }

    private static void send(ISdCardRemountCallback callback, int result) {
        try {
            callback.onResult(result);
        } catch (RemoteException ignored) {
            // Storage work is finished even if the app process went away.
        }
    }

    @Override public void destroy() {
        // Only the shell Shizuku server uses this reserved lifecycle transaction.
        if (Binder.getCallingUid() == SHELL_UID && Process.myUid() == SHELL_UID) {
            System.exit(0);
        }
    }

    private final class Sm implements UsbReaderReconnectPlan.Transport {
        private static final String SM = "/system/bin/sm";
        private static final String DUMPSYS = "/system/bin/dumpsys";
        private static final long COMMAND_MS = 4_000;
        private static final int MAX_SM_OUTPUT = 8_192;
        private static final int MAX_DUMP = 1_048_576;
        private final IBinder caller;

        Sm(IBinder caller) { this.caller = caller; }

        @Override public boolean authorized() {
            return Process.myUid() == SHELL_UID && Binder.getCallingUid() == ownerUid
                    && caller != null && caller.isBinderAlive();
        }

        @Override public UsbReaderReconnectPlan.DataCycle armCycle(long deadline)
                throws IOException, InterruptedException {
            if (!authorized()) throw new IOException("Invalid USB cycle identity");
            return SdRemountUsbRestore.arm(Math.min(COMMAND_MS, remaining(deadline)));
        }

        @Override public String usbDump(long deadline) throws IOException, InterruptedException {
            return run(deadline, MAX_DUMP, DUMPSYS, "-t", "4", "usb");
        }

        @Override public void restoreUsbData(long deadline) throws IOException, InterruptedException {
            if (Process.myUid() != SHELL_UID || Binder.getCallingUid() != ownerUid) {
                throw new IOException("Invalid USB data command identity");
            }
            run(deadline, MAX_SM_OUTPUT, DUMPSYS, "-t", "4", "usb", "enable-usb-data", "port0", "true");
        }

        @Override public String listPublicVolumes(long deadline) throws IOException, InterruptedException {
            return run(deadline, MAX_SM_OUTPUT, SM, "list-volumes", "public");
        }

        @Override public String mountDump(long deadline) throws IOException, InterruptedException {
            return run(deadline, MAX_DUMP, DUMPSYS, "-t", "4", "mount");
        }

        @Override public void change(SdCardRemountPlan.Action action, String id, long deadline)
                throws IOException, InterruptedException {
            if (!SdCardRemountPlan.isPublicId(id) || Process.myUid() != SHELL_UID || Binder.getCallingUid() != ownerUid
                    || (caller != null && action == SdCardRemountPlan.Action.UNMOUNT && !authorized())) {
                throw new IOException("Invalid public volume ID or command identity");
            }
            run(deadline, MAX_SM_OUTPUT, SM, action == SdCardRemountPlan.Action.MOUNT ? "mount" : "unmount", id);
        }

        @Override public long now() { return SystemClock.elapsedRealtime(); }
        @Override public void waitMs(long duration) throws InterruptedException { Thread.sleep(duration); }

        private String run(long deadline, int outputLimit, String... argv) throws IOException, InterruptedException {
            long stop = Math.min(deadline, now() + COMMAND_MS);
            if (now() >= stop) throw new IOException("Storage command deadline elapsed");
            java.lang.Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            FutureTask<String> output = new FutureTask<>(() -> readOutput(process.getInputStream(), outputLimit));
            Thread reader = new Thread(output, "remount-command-output");
            reader.setDaemon(true);
            try {
                reader.start();
                if (!process.waitFor(remaining(stop), TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                    throw new IOException("Storage command failed or timed out");
                }
                return output.get(remaining(stop), TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException e) {
                throw new IOException("Could not read the storage command result", e);
            } finally {
                if (process.isAlive()) process.destroyForcibly();
                output.cancel(true);
            }
        }

        private long remaining(long deadline) { return Math.max(0, deadline - now()); }

        private static String readOutput(InputStream stream, int limit) throws IOException {
            try (stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                for (int count; (count = stream.read(buffer)) != -1;) {
                    if (output.size() + count > limit) throw new IOException("Unexpected storage command output");
                    output.write(buffer, 0, count);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }
}
