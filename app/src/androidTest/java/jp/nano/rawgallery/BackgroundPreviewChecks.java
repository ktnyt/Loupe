package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Holds a background operation open: foreground completion must not depend on its release. */
final class BackgroundPreviewChecks {
  static String run(Context context) throws Exception {
    File source = File.createTempFile("background-check-", ".jpg", context.getCacheDir());
    Bitmap bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(Color.BLUE);
    try (FileOutputStream out = new FileOutputStream(source)) { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out); }
    bitmap.recycle();
    byte[] original = Files.readAllBytes(source.toPath());
    PhotoRepository foreground = new PhotoRepository(context);
    PhotoRepository background = new PhotoRepository(context, true);
    PhotoRepository.Photo first = photo(source, 1), concurrent = photo(source, 2), cancelled = photo(source, 3);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    try {
      Future<Boolean> preparing = workers.submit(() -> background.prepare(first, () -> {
        entered.countDown();
        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
        catch (InterruptedException e) { throw new RuntimeException(e); }
        return false;
      }));
      if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("background did not start");
      try {
        Future<PhotoRepository.Preview> visible = workers.submit(() -> foreground.preview(first));
        // Deadline detects a dependency, not a performance target. Background is still blocked.
        PhotoRepository.Preview result = visible.get(3, TimeUnit.SECONDS);
        if (!result.file.isFile()) throw new AssertionError("foreground result missing");
        if (preparing.isDone()) throw new AssertionError("background was not held open");
      } finally { release.countDown(); }
      if (!preparing.get(5, TimeUnit.SECONDS)) throw new AssertionError("background did not finish");
      CountDownLatch together = new CountDownLatch(1);
      Future<?> a = workers.submit(() -> { together.await(); return foreground.preview(concurrent); });
      Future<?> b = workers.submit(() -> { together.await(); return background.prepare(concurrent, () -> false); });
      together.countDown(); a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS);
      Bitmap decoded = PhotoRepository.bitmap(foreground.preview(concurrent), 200); decoded.recycle();
      AtomicInteger boundaries = new AtomicInteger();
      if (background.prepare(cancelled, () -> boundaries.incrementAndGet() >= 4))
        throw new AssertionError("did not stop after source stage");
      if (foreground.prepared(cancelled)) throw new AssertionError("cancelled result published");
      if (!Arrays.equals(original, Files.readAllBytes(source.toPath()))) throw new AssertionError("source changed");
      return "PASS background held open while foreground miss completes, concurrent publication, stage cancellation, original unchanged\n";
    } finally {
      release.countDown(); workers.shutdownNow(); workers.awaitTermination(5, TimeUnit.SECONDS);
      source.delete();
      for (PhotoRepository.Photo p : new PhotoRepository.Photo[]{first, concurrent, cancelled}) {
        String key = PhotoRepository.previewKey(p);
        new File(context.getFilesDir(), "prepared-previews/" + key + ".webp").delete();
        new File(context.getFilesDir(), "prepared-previews/" + key + ".webp.json").delete();
      }
    }
  }
  private static PhotoRepository.Photo photo(File f, long version) {
    return new PhotoRepository.Photo(Uri.fromFile(f), "synthetic.jpg", "", f.length(), version, 0);
  }
}
