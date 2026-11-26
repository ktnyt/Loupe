package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.ImageView;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;

/** Only synthetic cache images; no source photos, tags or rankings are edited. */
final class DiagnosticChecks {
  static String run(Instrumentation test) throws Exception {
    checkRotatedCapture(test.getTargetContext().getCacheDir());
    MainActivity activity =
        (MainActivity)
            test.startActivitySync(
                new Intent(test.getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    Field gridField = MainActivity.class.getDeclaredField("albumGridView");
    gridField.setAccessible(true);
    Method reload = MainActivity.class.getDeclaredMethod("loadAlbums");
    reload.setAccessible(true);
    Thread.sleep(500);
    for (int i = 0; i < 2; i++) {
      test.runOnMainSync(
          () -> {
            try {
              reload.invoke(activity);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      Thread.sleep(300);
    }
    if (gridField.get(activity) == null) throw new AssertionError("album reload missing grid");
    File fixture = new File(test.getTargetContext().getCacheDir(), "diagnostic-fixture.jpg");
    Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(Color.BLUE);
    try (FileOutputStream out = new FileOutputStream(fixture)) {
      bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
    }
    bitmap.recycle();
    PhotoRepository.Photo good =
        new PhotoRepository.Photo(
            Uri.fromFile(fixture),
            "private-sentinel.jpg",
            "",
            fixture.length(),
            System.currentTimeMillis(),
            0);
    PhotoRepository.Photo bad =
        new PhotoRepository.Photo(
            Uri.fromFile(new File(fixture.getParent(), "absent-diagnostic.jpg")),
            "private-sentinel.jpg",
            "",
            1,
            1,
            0);
    Method preview =
        MainActivity.class.getDeclaredMethod(
            "previewInto", ImageView.class, PhotoRepository.Photo.class, int.class);
    preview.setAccessible(true);
    Field imagesField = MainActivity.class.getDeclaredField("images");
    imagesField.setAccessible(true);
    ExecutorService images = (ExecutorService) imagesField.get(activity);
    DiagnosticImageView[] view = new DiagnosticImageView[1];
    long run = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    String begin = marker("test.begin", run);
    File folder = new File(test.getTargetContext().getFilesDir(), "diagnostics");
    try {
      if (DiagnosticLog.currentRecorder() == null) {
        throw new AssertionError("diagnostic recorder is not active");
      }
      DiagnosticLog.event("test.begin", run);
      // Exercise real repository miss and hit paths using only the synthetic file.
      PhotoRepository repository = new PhotoRepository(test.getTargetContext());
      repository.preview(good);
      repository.preview(good);
      try {
        repository.preview(bad);
        throw new AssertionError("missing image succeeded");
      } catch (IOException expected) {
      }
      test.runOnMainSync(
          () -> {
            view[0] = new DiagnosticImageView(activity);
            activity.setContentView(view[0]);
            invoke(preview, activity, view[0], good);
          });
      waitFor(test, () -> view[0].getDrawable() != null);
      // The first frame must commit without requiring a subsequent UI update.
      long commitDeadline = SystemClock.elapsedRealtime() + 2000;
      awaitEvent(
          folder,
          begin,
          eventToken("image.frame_commit"),
          commitDeadline,
          "frame commit needed another UI update");
      // Queue two different image requests against a reused target behind blocked workers.
      CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
      for (int i = 0; i < 2; i++)
        images.execute(
            () -> {
              entered.countDown();
              try {
                release.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
      if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("workers blocked");
      test.runOnMainSync(
          () -> {
            invoke(preview, activity, view[0], bad);
            invoke(preview, activity, view[0], good);
          });
      release.countDown();
      Thread.sleep(500);
      test.runOnMainSync(() -> invoke(preview, activity, view[0], bad));
      waitFor(
          test,
          () ->
              view[0].getContentDescription() != null
                  && view[0].getContentDescription().toString().contains("読み込めません"));
      DiagnosticLog.event("test.end", run);
      String end = marker("test.end", run);
      String text =
          awaitEvent(
              folder,
              begin,
              end,
              SystemClock.elapsedRealtime() + 2000,
              "diagnostic test end was not persisted");
      text = text.substring(0, text.indexOf('\n', text.indexOf(end)) + 1);
      for (String event :
          new String[] {
            "image.bind",
            "image.draw",
            "image.frame_commit",
            "image.fail",
            "image.discard",
            "image.failure_applied",
            "repository.lock_acquired"
          }) if (!text.contains(eventToken(event))) throw new AssertionError("missing " + event);
      if (text.contains("private-sentinel") || text.contains("diagnostic-fixture.jpg"))
        throw new AssertionError("private string leaked");
      return "PASS synthetic cache miss/hit, bind/draw/frame commit, failure, stale discard, no filename leakage\n";
    } finally {
      test.runOnMainSync(activity::finish);
      fixture.delete();
    }
  }

  private static String eventToken(String event) {
    return "\"event\":\"" + event + "\"";
  }

  private static String marker(String event, long run) {
    return eventToken(event) + ",\"request\":" + run + ",";
  }

  private static String awaitEvent(
      File folder, String begin, String expected, long deadline, String missingEvent)
      throws Exception {
    boolean sawBegin = false;
    while (SystemClock.elapsedRealtime() < deadline) {
      String capture = readAfterBegin(folder, begin);
      if (capture != null) {
        sawBegin = true;
        if (capture.contains(expected)) return capture;
      }
      Thread.sleep(20);
    }
    throw new AssertionError(sawBegin ? missingEvent : "diagnostic test begin was not persisted");
  }

  private static String readAfterBegin(File folder, String begin) throws IOException {
    // The writer changes file names and flushes under the same lock as the export path.
    synchronized (DiagnosticLog.FILES) {
      String current = readBounded(new File(folder, "events.jsonl"));
      int index = current.lastIndexOf(begin);
      if (index >= 0) return current.substring(current.lastIndexOf('\n', index) + 1);
      String previous = readBounded(new File(folder, "previous.jsonl"));
      index = previous.lastIndexOf(begin);
      if (index < 0) return null;
      return previous.substring(previous.lastIndexOf('\n', index) + 1) + current;
    }
  }

  private static String readBounded(File file) throws IOException {
    if (!file.exists()) return "";
    if (file.length() > DiagnosticExport.EVENT_FILE_LIMIT) {
      throw new AssertionError("diagnostic log exceeds its retention bound");
    }
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }

  private static void checkRotatedCapture(File cache) throws IOException {
    File folder = Files.createTempDirectory(cache.toPath(), "diagnostic-capture-fixture").toFile();
    String begin = marker("test.begin", 15);
    String stale = "{" + marker("test.begin", 150) + "\"fixture\":true}";
    String current = "{" + begin + "\"fixture\":true}";
    String afterRotation = "{" + eventToken("fixture.after_rotation") + "}";
    try (DiagnosticWriter writer =
        new DiagnosticWriter(folder, stale.length() + current.length() + 2)) {
      writer.write(stale);
      writer.flush();
      writer.write(current);
      if (readAfterBegin(folder, begin) != null) {
        throw new AssertionError("diagnostic capture accepted a stale or unflushed begin");
      }
      writer.flush();
      if (!(current + "\n").equals(readAfterBegin(folder, begin))) {
        throw new AssertionError("diagnostic capture missed the current begin");
      }
      writer.write(afterRotation);
      writer.flush();
      if (!(current + "\n" + afterRotation + "\n").equals(readAfterBegin(folder, begin))) {
        throw new AssertionError("diagnostic capture missed the begin across rotation");
      }
    } finally {
      Files.deleteIfExists(new File(folder, "events.jsonl").toPath());
      Files.deleteIfExists(new File(folder, "previous.jsonl").toPath());
      Files.delete(folder.toPath());
    }
  }

  private static void invoke(Method method, MainActivity a, ImageView v, PhotoRepository.Photo p) {
    try {
      method.invoke(a, v, p, 1600);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  interface Check {
    boolean done();
  }

  static void waitFor(Instrumentation test, Check check) throws Exception {
    long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
    while (android.os.SystemClock.elapsedRealtime() < deadline) {
      boolean[] done = {false};
      test.runOnMainSync(() -> done[0] = check.done());
      if (done[0]) return;
      Thread.sleep(20);
    }
    throw new AssertionError("UI timeout");
  }
}
