package jp.nano.rawgallery;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Local bounded decoding; does not write the selected image. */
final class CameraQrImageDecoder {
  private static final int MAX_BYTES = 32 * 1024 * 1024;
  private static final int MAX_EDGE = 2048;

  static String decode(ContentResolver resolver, Uri uri) throws IOException {
    byte[] data;
    byte[] chunk = new byte[32768];
    try (InputStream in = resolver.openInputStream(uri);
        WipingBuffer out = new WipingBuffer()) {
      if (in == null) throw new IOException("image unavailable");
      int read, total = 0;
      while ((read = in.read(chunk)) != -1) {
        total += read;
        if (total > MAX_BYTES) throw new IOException("image too large");
        out.write(chunk, 0, read);
      }
      data = out.toByteArray();
    } finally {
      java.util.Arrays.fill(chunk, (byte) 0);
    }
    try {
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("invalid image");
      int sample = 1;
      while ((bounds.outWidth + (long) sample - 1) / sample > MAX_EDGE
          || (bounds.outHeight + (long) sample - 1) / sample > MAX_EDGE) sample *= 2;
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inSampleSize = sample;
      options.inPreferredConfig = Bitmap.Config.ARGB_8888;
      Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
      if (bitmap == null) throw new IOException("invalid image");
      try {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        if (width > MAX_EDGE || height > MAX_EDGE) throw new IOException("image too large");
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        try {
          return new QRCodeReader()
              .decode(
                  new BinaryBitmap(
                      new HybridBinarizer(new RGBLuminanceSource(width, height, pixels))),
                  java.util.Collections.singletonMap(
                      com.google.zxing.DecodeHintType.TRY_HARDER, Boolean.TRUE))
              .getText();
        } catch (com.google.zxing.ReaderException failure) {
          throw new IOException("no QR code");
        } finally {
          java.util.Arrays.fill(pixels, 0);
        }
      } finally {
        bitmap.recycle();
      }
    } finally {
      java.util.Arrays.fill(data, (byte) 0);
    }
  }

  private static final class WipingBuffer extends ByteArrayOutputStream {
    @Override
    public void close() {
      java.util.Arrays.fill(buf, (byte) 0);
      reset();
    }
  }
}
