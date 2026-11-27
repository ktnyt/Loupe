package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.*;
import java.util.Arrays;
import jp.nano.rawgallery.camera.CameraQrCredentials;

/** Synthetic local images only. Never selects or opens a user photo. */
final class CameraQrImageChecks {
  static String run(Instrumentation instrumentation) throws Exception {
    String vector = "OIS2,3,TVJ1CRDC,-+*%$ZYX,UKR1CRDC,-+*%$Z";
    File image =
        File.createTempFile(
            "camera-qr-fixture-", ".png", instrumentation.getTargetContext().getCacheDir());
    try {
      for (int edge : new int[] {640, 2304}) {
        BitMatrix matrix = new QRCodeWriter().encode(vector, BarcodeFormat.QR_CODE, edge, edge);
        int[] pixels = new int[edge * edge];
        for (int y = 0; y < edge; y++)
          for (int x = 0; x < edge; x++)
            pixels[y * edge + x] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
        Bitmap bitmap = Bitmap.createBitmap(pixels, edge, edge, Bitmap.Config.ARGB_8888);
        Arrays.fill(pixels, 0);
        Matrix rotation = new Matrix();
        rotation.postRotate(90);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, edge, edge, rotation, false);
        try (FileOutputStream output = new FileOutputStream(image)) {
          rotated.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally {
          if (rotated != bitmap) rotated.recycle();
          bitmap.recycle();
        }
        long length = image.length();
        byte[] before = digest(image);
        String decoded =
            CameraQrImageDecoder.decode(
                instrumentation.getTargetContext().getContentResolver(), Uri.fromFile(image));
        try (CameraQrCredentials credentials = CameraQrCredentials.parse(decoded)) {
          if (!credentials.bleName.equals("BLE-TEST"))
            throw new AssertionError("Image QR identity");
        }
        if (image.length() != length || !Arrays.equals(before, digest(image)))
          throw new AssertionError("Source image changed");
      }
      try (FileOutputStream output = new FileOutputStream(image)) {
        output.write(new byte[] {1, 2, 3});
      }
      try {
        CameraQrImageDecoder.decode(
            instrumentation.getTargetContext().getContentResolver(), Uri.fromFile(image));
        throw new AssertionError("Invalid image accepted");
      } catch (IOException expected) {
      }
      return "PASS camera-qr-image localDecode=true rotated=true sampled=true invalidRejected=true sourceUnchanged=true";
    } finally {
      if (image.exists() && !image.delete()) throw new IOException("Synthetic QR cleanup failed");
    }
  }

  private static byte[] digest(File file) throws Exception {
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
    try (FileInputStream input = new FileInputStream(file)) {
      byte[] buffer = new byte[65536];
      int count;
      while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
    }
    return digest.digest();
  }
}
