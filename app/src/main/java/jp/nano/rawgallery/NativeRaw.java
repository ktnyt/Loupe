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
        public final ByteBuffer pixels;

        private Image(int width, int height, ByteBuffer pixels) {
            this.width = width;
            this.height = height;
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

    private static native Image decodeNative(String path, int maxSide) throws IOException;
}
