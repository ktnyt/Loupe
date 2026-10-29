package jp.nano.rawgallery;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** On-device sensor RAW decoding. Input must be the caller's private temporary copy. */
public final class NativeRaw {
    static { System.loadLibrary("loupe_raw"); }
    private NativeRaw() {}

    /** Linear sRGB RGBA float32, row-major, alpha=1, with orientation already applied. */
    public static final class Image {
        public final int width;
        public final int height;
        /** Metadata estimate for initial 1:1 zoom; the prepared cache is authoritative. */
        public final int originalWidth;
        public final int originalHeight;
        public final ByteBuffer pixels;

        private Image(int width, int height, int originalWidth, int originalHeight, ByteBuffer pixels) {
            this.width = width;
            this.height = height;
            this.originalWidth = originalWidth;
            this.originalHeight = originalHeight;
            this.pixels = pixels.order(ByteOrder.nativeOrder());
        }
    }

    /** Accepts maxSide 256..2048 and throws without exposing the input path on failure. */
    public static Image decode(String path, int maxSide) throws IOException {
        if (path == null || maxSide < 256 || maxSide > 2048) {
            throw new IllegalArgumentException("Invalid RAW decode request");
        }
        return decodeNative(path, maxSide);
    }

    /** Cooperative cancellation; the worker may also interrupt its owning thread. */
    public static final class Cancellation {
        private volatile boolean cancelled;
        private Thread worker;
        public synchronized void cancel() {
            cancelled = true;
            if (worker != null) worker.interrupt();
        }
        private synchronized void attach() {
            if (worker != null) throw new IllegalStateException("RAW cancellation is already in use");
            worker = Thread.currentThread();
            if (cancelled) worker.interrupt();
        }
        private synchronized void detach() { worker = null; }
        public boolean isCancelled() { return cancelled || Thread.currentThread().isInterrupted(); }
    }

    /** Writes a new private linear RGB/gray16 cache. Existing outputs are never replaced. */
    public static void prepareFull(String input, String cachePath, Cancellation cancellation) throws IOException {
        if (input == null || cachePath == null || cancellation == null)
            throw new IllegalArgumentException("Invalid RAW preparation request");
        cancellation.attach();
        try { prepareFullNative(input, cachePath, cancellation); }
        finally { cancellation.detach(); }
    }
    private static native void prepareFullNative(String input, String cachePath, Cancellation cancellation) throws IOException;

    private static native Image decodeNative(String path, int maxSide) throws IOException;
}
