package jp.nano.rawgallery;

import java.io.IOException;
import java.nio.ByteBuffer;

/** One encoder for consecutive RGB8 strips. The caller owns the original pending-output fd. */
public final class NativeJpeg implements AutoCloseable {
    static { System.loadLibrary("loupe_raw"); }
    private long handle;
    private final int width;
    private NativeJpeg(long handle, int width) { this.handle = handle; this.width = width; }

    public static NativeJpeg open(int fd, int width, int height, int quality) throws IOException {
        if (fd < 0 || width < 1 || height < 1 || width > 16384 || height > 16384
                || (long) width * height > 80000000L || quality < 1 || quality > 100)
            throw new IllegalArgumentException("Invalid JPEG request");
        return new NativeJpeg(openNative(fd, width, height, quality), width);
    }

    /** Consumes exactly rows tightly packed RGB8 rows from the current buffer position. */
    public synchronized void write(ByteBuffer rgb, int rows) throws IOException {
        requireOpen();
        long bytes = (long) rows * width * 3;
        if (rgb == null || !rgb.isDirect() || rows <= 0 || bytes != rgb.remaining())
            throw new IllegalArgumentException("Invalid JPEG strip");
        writeNative(handle, rgb, rgb.position(), (int) bytes, rows);
        rgb.position(rgb.position() + (int) bytes);
    }

    public synchronized void finish() throws IOException {
        requireOpen();
        try { finishNative(handle); } finally { close(); }
    }

    @Override public synchronized void close() {
        if (handle != 0) { closeNative(handle); handle = 0; }
    }
    private void requireOpen() throws IOException {
        if (handle == 0) throw new IOException("JPEG encoder is closed");
    }
    private static native long openNative(int fd, int width, int height, int quality) throws IOException;
    private static native void writeNative(long handle, ByteBuffer rgb, int offset, int bytes, int rows) throws IOException;
    private static native void finishNative(long handle) throws IOException;
    private static native void closeNative(long handle);
}
