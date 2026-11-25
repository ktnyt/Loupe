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
    try (FileOutputStream out = new FileOutputStream(source)) {
      bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
    }
    bitmap.recycle();
    byte[] original = Files.readAllBytes(source.toPath());
    PhotoRepository foreground = new PhotoRepository(context);
    PhotoRepository background = new PhotoRepository(context, true);
    PhotoRepository.Photo first = photo(source, 1),
        concurrent = photo(source, 2),
        cancelled = photo(source, 3);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    try {
      Future<Boolean> preparing =
          workers.submit(
              () ->
                  background.prepare(
                      first,
                      () -> {
                        entered.countDown();
                        try {
                          if (!release.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("release timeout");
                        } catch (InterruptedException e) {
                          throw new RuntimeException(e);
                        }
                        return false;
                      }));
      if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("background did not start");
      try {
        Future<PhotoRepository.Preview> visible = workers.submit(() -> foreground.preview(first));
        // Deadline detects a dependency, not a performance target. Background is still blocked.
        PhotoRepository.Preview result = visible.get(3, TimeUnit.SECONDS);
        if (!result.file.isFile()) throw new AssertionError("foreground result missing");
        if (preparing.isDone()) throw new AssertionError("background was not held open");
      } finally {
        release.countDown();
      }
      if (!preparing.get(5, TimeUnit.SECONDS))
        throw new AssertionError("background did not finish");
      checkPreparedHitDuringForegroundWork(foreground, first, concurrent, workers);
      PhotoRepository.Preview cached = foreground.preview(first);
      File metadata = new File(cached.file.getPath() + ".json");
      Files.write(
          metadata.toPath(),
          "broken metadata".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      Bitmap repaired = PhotoRepository.bitmap(foreground.preview(first), 200);
      repaired.recycle();
      checkLargeJpegPrefix(context, foreground, original);

      CountDownLatch together = new CountDownLatch(1);
      Future<?> a =
          workers.submit(
              () -> {
                together.await();
                return foreground.preview(concurrent);
              });
      Future<?> b =
          workers.submit(
              () -> {
                together.await();
                return background.prepare(concurrent, () -> false);
              });
      together.countDown();
      a.get(5, TimeUnit.SECONDS);
      b.get(5, TimeUnit.SECONDS);
      Bitmap decoded = PhotoRepository.bitmap(foreground.preview(concurrent), 200);
      decoded.recycle();
      AtomicInteger boundaries = new AtomicInteger();
      if (background.prepare(cancelled, () -> boundaries.incrementAndGet() >= 4))
        throw new AssertionError("did not stop after source stage");
      if (foreground.prepared(cancelled)) throw new AssertionError("cancelled result published");
      PhotoRepository.Preview damaged = foreground.preview(first);
      try (FileOutputStream out = new FileOutputStream(damaged.file)) {
        out.write(new byte[] {1, 2, 3});
      }
      boolean rejected = false;
      try {
        PhotoRepository.bitmap(damaged, 200);
      } catch (IOException expected) {
        rejected = true;
      }
      if (!rejected || damaged.file.exists())
        throw new AssertionError("corrupt derived preview retained");
      Bitmap recovered = PhotoRepository.bitmap(foreground.preview(first), 200);
      recovered.recycle();
      // A Preview representing an original must never invalidate its file on decode failure.
      File badOriginal = File.createTempFile("bad-source-check-", ".jpg", context.getCacheDir());
      try {
        try (FileOutputStream out = new FileOutputStream(badOriginal)) {
          out.write(new byte[] {4, 5, 6});
        }
        try {
          PhotoRepository.bitmap(new PhotoRepository.Preview(badOriginal, "", 10, 10, 1), 200);
        } catch (IOException expected) {
        }
        if (!badOriginal.isFile() || badOriginal.length() != 3)
          throw new AssertionError("original invalidated");
      } finally {
        badOriginal.delete();
      }
      if (!Arrays.equals(original, Files.readAllBytes(source.toPath())))
        throw new AssertionError("source changed");
      return "PASS background held open while foreground miss completes, prepared hit while foreground is busy, large JPEG prefix, concurrent publication, stage cancellation, corrupt derived recovery, original unchanged\n";
    } finally {
      release.countDown();
      workers.shutdownNow();
      workers.awaitTermination(5, TimeUnit.SECONDS);
      source.delete();
      for (PhotoRepository.Photo p : new PhotoRepository.Photo[] {first, concurrent, cancelled}) {
        String key = PhotoRepository.previewKey(p);
        new File(context.getFilesDir(), "prepared-previews/" + key + ".webp").delete();
        new File(context.getFilesDir(), "prepared-previews/" + key + ".webp.json").delete();
      }
    }
  }

  private static void checkPreparedHitDuringForegroundWork(
      PhotoRepository repository,
      PhotoRepository.Photo prepared,
      PhotoRepository.Photo waitingPhoto,
      ExecutorService workers)
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var waiting =
        workers.submit(
            () ->
                repository.prepare(
                    waitingPhoto,
                    () -> {
                      entered.countDown();
                      try {
                        if (!release.await(5, TimeUnit.SECONDS))
                          throw new AssertionError("foreground gate timeout");
                      } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                      }
                      return true;
                    }));
    try {
      if (!entered.await(5, TimeUnit.SECONDS))
        throw new AssertionError("foreground gate not entered");
      var hit = workers.submit(() -> repository.preview(prepared));
      if (!hit.get(3, TimeUnit.SECONDS).file.isFile() || waiting.isDone())
        throw new AssertionError("prepared hit depended on foreground generation");
    } finally {
      release.countDown();
    }
    if (waiting.get(5, TimeUnit.SECONDS)) throw new AssertionError("cancelled probe published");
  }

  private static void checkLargeJpegPrefix(Context context, PhotoRepository repository, byte[] jpeg)
      throws Exception {
    File padded = File.createTempFile("jpeg-large-prefix-", ".jpg", context.getCacheDir());
    PhotoRepository.Photo photo = null;
    try {
      try (var out = new DataOutputStream(new FileOutputStream(padded))) {
        out.write(jpeg, 0, 2);
        for (int segment = 0; segment < 2; segment++) {
          out.writeShort(0xffef);
          out.writeShort(40002);
          out.write(new byte[40000]);
        }
        out.write(jpeg, 2, jpeg.length - 2);
      }
      photo = photo(padded, 1);
      byte[] before = Files.readAllBytes(padded.toPath());
      Bitmap decoded = PhotoRepository.bitmap(repository.preview(photo), 200);
      decoded.recycle();
      if (!Arrays.equals(before, Files.readAllBytes(padded.toPath())))
        throw new AssertionError("JPEG reopening modified its source");
    } finally {
      if (photo != null) repository.removePreparedPreview(photo);
      padded.delete();
    }
  }

  private static PhotoRepository.Photo photo(File f, long version) {
    return new PhotoRepository.Photo(Uri.fromFile(f), "synthetic.jpg", "", f.length(), version, 0);
  }
}
