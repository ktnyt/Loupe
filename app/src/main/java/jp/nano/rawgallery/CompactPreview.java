package jp.nano.rawgallery;

import android.graphics.Bitmap;
import java.io.*;
import java.nio.file.*;

/** Disposable-looking images whose durable archive copies must survive original cleanup. */
final class CompactPreview {
  static final int EDGE = 1280;

  static PhotoRepository.Preview save(PhotoRepository.Preview source, File destination)
      throws IOException {
    Bitmap decoded = PhotoRepository.bitmap(source, EDGE);
    Bitmap scaled = decoded;
    File temporary = File.createTempFile("proxy-", ".part", destination.getParentFile());
    try {
      int edge = Math.max(decoded.getWidth(), decoded.getHeight());
      if (edge > EDGE) {
        float ratio = (float) EDGE / edge;
        scaled =
            Bitmap.createScaledBitmap(
                decoded,
                Math.max(1, Math.round(decoded.getWidth() * ratio)),
                Math.max(1, Math.round(decoded.getHeight() * ratio)),
                true);
      }
      try (FileOutputStream out = new FileOutputStream(temporary)) {
        if (!scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out))
          throw new IOException("小サイズのプレビューを保存できません");
        out.getFD().sync();
      }
      Files.move(
          temporary.toPath(),
          destination.toPath(),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
      return new PhotoRepository.Preview(
          destination, source.model, scaled.getWidth(), scaled.getHeight(), 1);
    } finally {
      if (scaled != decoded) scaled.recycle();
      decoded.recycle();
      temporary.delete();
    }
  }
}
