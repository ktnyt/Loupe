package jp.nano.rawgallery.camera;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs both port changes in one process, including when its Shizuku owner exits mid-cycle. */
final class SdRemountUsbRestore implements UsbReaderReconnectPlan.DataCycle {
    static final String SCRIPT = """
            IFS= read -r request || exit 0
            [ "$request" = arm ] || exit 0
            printf '%s\\n' armed || exit 0
            IFS= read -r request || exit 0
            [ "$request" = cycle ] || exit 0
            trap '' PIPE
            printf '%s\\n' started || :
            /system/bin/dumpsys -t 7 usb enable-usb-data port0 false </dev/null >/dev/null 2>&1
            off=$?
            /system/bin/sleep 2 </dev/null >/dev/null 2>&1
            /system/bin/dumpsys -t 7 usb enable-usb-data port0 true </dev/null >/dev/null 2>&1
            on=$?
            printf 'done %s %s\\n' "$off" "$on" || :
            """;

    private final OutputStream control;
    private final CompletableFuture<UsbReaderReconnectPlan.CycleResult> finished = new CompletableFuture<>();

    private SdRemountUsbRestore(OutputStream control) { this.control = control; }

    static SdRemountUsbRestore arm(long timeoutMs) throws IOException, InterruptedException {
        if (timeoutMs <= 0) throw new IOException("USB cycle deadline elapsed");
        Process process = new ProcessBuilder("/system/bin/sh", "-c", SCRIPT).redirectErrorStream(true).start();
        var cycle = new SdRemountUsbRestore(process.getOutputStream());
        var acknowledgement = new CompletableFuture<String>();
        var reader = new Thread(() -> {
            try (var output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.US_ASCII))) {
                acknowledgement.complete(output.readLine());
                cycle.finished.complete(readResult(output));
            } catch (IOException failure) {
                acknowledgement.completeExceptionally(failure);
                cycle.finished.completeExceptionally(failure);
            }
        }, "usb-cycle-output");
        reader.setDaemon(true);
        boolean armed = false;
        try {
            reader.start();
            cycle.signal("arm\n");
            armed = "armed".equals(acknowledgement.get(timeoutMs, TimeUnit.MILLISECONDS)) && process.isAlive();
            if (!armed) throw new IOException("USB cycle did not arm");
            return cycle;
        } catch (ExecutionException | TimeoutException failure) {
            throw new IOException("USB cycle gave no acknowledgement", failure);
        } finally {
            if (!armed) cycle.close();
        }
    }

    @Override public void start() throws IOException { signal("cycle\n"); }

    @Override public UsbReaderReconnectPlan.CycleResult await(long timeoutMs) throws IOException, InterruptedException {
        try {
            return finished.get(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            // The independent cycle process owns both writes and must finish on its own.
            return new UsbReaderReconnectPlan.CycleResult(UsbReaderReconnectPlan.CycleState.UNKNOWN, false, false);
        } catch (ExecutionException failure) {
            throw new IOException("Could not read USB cycle completion", failure);
        }
    }

    static UsbReaderReconnectPlan.CycleResult readResult(BufferedReader output) throws IOException {
        String progress = output.readLine();
        if (progress == null) {
            return new UsbReaderReconnectPlan.CycleResult(UsbReaderReconnectPlan.CycleState.NOT_STARTED, false, false);
        }
        if (!"started".equals(progress)) {
            return new UsbReaderReconnectPlan.CycleResult(UsbReaderReconnectPlan.CycleState.UNKNOWN, false, false);
        }
        String status = output.readLine();
        if (status == null) {
            return new UsbReaderReconnectPlan.CycleResult(UsbReaderReconnectPlan.CycleState.TERMINATED, false, false);
        }
        if (!status.matches("done [0-9]{1,3} [0-9]{1,3}")) {
            return new UsbReaderReconnectPlan.CycleResult(UsbReaderReconnectPlan.CycleState.UNKNOWN, false, false);
        }
        String[] fields = status.split(" ");
        return new UsbReaderReconnectPlan.CycleResult(UsbReaderReconnectPlan.CycleState.COMPLETED,
                "0".equals(fields[1]), "0".equals(fields[2]));
    }

    private void signal(String message) throws IOException {
        control.write(message.getBytes(StandardCharsets.US_ASCII));
        control.flush();
    }

    @Override public void close() {
        try {
            control.close();
        } catch (IOException ignored) {
            // EOF aborts an unstarted cycle. An accepted cycle no longer reads from this pipe.
        }
    }
}
