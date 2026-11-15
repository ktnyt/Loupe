package jp.nano.rawgallery.camera;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.function.BooleanSupplier;

/** Wait for a validated camera session, rather than accepting any still-connected Wi-Fi. */
public final class CameraConnectionWait {
    public interface Probe<T> { T attempt(BooleanSupplier cancelled) throws IOException; }
    interface Time { long millis(); void pause(long millis) throws InterruptedException; }
    private CameraConnectionWait() { }
    public static <T> T await(long timeoutMillis, Probe<T> probe, BooleanSupplier cancelled) throws IOException {
        return await(timeoutMillis, probe, cancelled, new Time() {
            public long millis() { return System.nanoTime() / 1_000_000; }
            public void pause(long millis) throws InterruptedException { Thread.sleep(millis); }
        });
    }
    static <T> T await(long timeoutMillis, Probe<T> probe, BooleanSupplier cancelled, Time time) throws IOException {
        if (timeoutMillis <= 0 || timeoutMillis > 60000) throw new IllegalArgumentException("Invalid connection deadline");
        long deadline = time.millis() + timeoutMillis;
        IOException last = null;
        while (time.millis() < deadline) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Connection cancelled");
            try {
                T session = probe.attempt(() -> cancelled.getAsBoolean() || time.millis() >= deadline);
                if (session != null && time.millis() < deadline && !cancelled.getAsBoolean()) return session;
            } catch (IOException unavailable) { last = unavailable; }
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("Connection cancelled");
            long remaining = deadline - time.millis();
            if (remaining > 0) try { time.pause(Math.min(250, remaining)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Connection cancelled"); }
        }
        throw new IOException("Camera connection timed out", last);
    }
}
