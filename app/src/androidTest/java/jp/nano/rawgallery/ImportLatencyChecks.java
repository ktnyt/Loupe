package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Imports only generated images; deletes only URIs returned by this invocation's inserts. */
final class ImportLatencyChecks {
  private static Object field(Object target, String name) throws Exception {
    Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(target);
  }
  private static final class ImportInterrupted extends AssertionError {}

  static String run(Instrumentation test, boolean interrupt) throws Exception {
    Context context = test.getTargetContext();
    ContentResolver resolver = context.getContentResolver();
    String folder = "Pictures/Loupe-import-test-" + UUID.randomUUID() + "/";
    List<Uri> fixtures = new ArrayList<>();
    MainActivity activity = null;
    File container = File.createTempFile("import-test-", ".dng", context.getCacheDir());
    final int count = 6, padding = 24 * 1024 * 1024;
    try {
      Bitmap bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
      bitmap.eraseColor(Color.rgb(44, 92, 136));
      try (FileOutputStream output = new FileOutputStream(container)) {
        byte[] block = new byte[65536];
        for (int i = 0; i < padding / block.length; i++) output.write(block);
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output);
      } finally { bitmap.recycle(); }
      activity = (MainActivity) test.startActivitySync(new Intent(context, MainActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      final MainActivity a = activity;
      // Drain the initial refresh before the measured import. No existing cache is removed.
      GalleryIndex.WORKER.submit(() -> {}).get(90, TimeUnit.SECONDS);
      test.waitForIdleSync();
      int[] generation = {0};
      test.runOnMainSync(() -> {
        try {
          Field restore = MainActivity.class.getDeclaredField("pendingRestore"); restore.setAccessible(true); restore.set(a, null);
          Field f = MainActivity.class.getDeclaredField("albumFolder"); f.setAccessible(true); f.set(a, folder);
          Method gallery = MainActivity.class.getDeclaredMethod("gallery"); gallery.setAccessible(true); gallery.invoke(a);
          Method load = MainActivity.class.getDeclaredMethod("loadIndexedPhotos"); load.setAccessible(true); load.invoke(a);
          generation[0] = (int) field(a, "generation");
        } catch (Exception e) { throw new RuntimeException(e); }
      });
      ((ExecutorService) field(a, "scans")).submit(() -> {}).get(30, TimeUnit.SECONDS);
      test.waitForIdleSync();
      long begin = SystemClock.elapsedRealtimeNanos();
      DiagnosticLog.event("test.import_begin", 0, "count", count, "bytes", container.length());
      for (int i = 0; i < count; i++) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "generated-" + i + ".dng");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new AssertionError("fixture insert failed");
        fixtures.add(uri);
        try (InputStream in = new FileInputStream(container); OutputStream out = resolver.openOutputStream(uri)) {
          if (out == null) throw new AssertionError("fixture output missing");
          in.transferTo(out);
        }
      }
      long publishing = SystemClock.elapsedRealtimeNanos();
      DiagnosticLog.event("test.import_publish", 0);
      for (Uri uri : fixtures) {
        ContentValues values = new ContentValues(); values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
      }
      DiagnosticLog.event("test.import_published", 0);
      if (interrupt) test.runOnMainSync(a::onBackPressed);
      long deadline = SystemClock.elapsedRealtime() + 90000;
      long applied = 0, frame = 0;
      Set<Long> ids = new HashSet<>();
      while (SystemClock.elapsedRealtime() < deadline) {
        boolean[] ready = {false};
        boolean[] changed = {false};
        test.runOnMainSync(() -> {
          try {
            changed[0] = (int) field(a, "generation") != generation[0]
                || !folder.equals(field(a, "albumFolder")) || (boolean) field(a, "albumHome");
            @SuppressWarnings("unchecked") List<PhotoRepository.Photo> photos = (List<PhotoRepository.Photo>) field(a, "photos");
            ready[0] = photos.size() == count;
            for (PhotoRepository.Photo p : photos) ready[0] &= p.folder.equals(folder) && p.size == container.length();
            if (ready[0]) for (PhotoRepository.Photo p : photos) ids.add(DiagnosticLog.photo(p));
          } catch (Exception e) { throw new RuntimeException(e); }
        });
        if (changed[0]) {
          DiagnosticLog.event("test.import_interrupted", 0);
          throw new ImportInterrupted();
        }
        if (ready[0]) {
          if (applied == 0) applied = SystemClock.elapsedRealtimeNanos();
          // Correlate new fixture requests only; an old screen's frame cannot satisfy the check.
          Map<Long, Long> requests = new HashMap<>();
          File log = new File(context.getFilesDir(), "diagnostics/events.jsonl");
          if (log.isFile()) for (String line : Files.readAllLines(log.toPath())) {
            if (!line.endsWith("}")) continue; // writer may be appending the last line
            JSONObject event = new JSONObject(line);
            if (event.getLong("ns") < publishing) continue;
            if ("image.request".equals(event.getString("event")) && ids.contains(event.optLong("photo")))
              requests.put(event.getLong("request"), event.optLong("photo"));
            if ("image.frame_commit".equals(event.getString("event")) && requests.containsKey(event.getLong("request"))) {
              frame = event.getLong("ns"); break;
            }
          }
          if (frame != 0) break;
        }
        Thread.sleep(20);
      }
      if (applied == 0 || frame == 0) throw new AssertionError("new import did not reach list and image frame");
      if (interrupt) throw new AssertionError("screen change was not detected");
      return String.format(Locale.ROOT,
          "PASS new synthetic RAW6, 24MiB padding each, no derived cache; import_to_list_ms=%.3f publish_to_list_ms=%.3f publish_to_frame_ms=%.3f. Existing photos untouched; fixture URIs removed in finally.\n",
          (applied-begin)/1e6, (applied-publishing)/1e6, (frame-publishing)/1e6);
    } catch (ImportInterrupted interrupted) {
      if (!interrupt) throw interrupted;
      return "PASS import test detects changed screen without waiting for timeout; fixture cleanup runs in finally.\n";
    } finally {
      if (activity != null) { MainActivity a = activity; test.runOnMainSync(a::finish); }
      for (Uri uri : fixtures) resolver.delete(uri, null, null);
      container.delete();
      // Restore the rebuildable index after removing only this test's fixtures.
      GalleryIndex.WORKER.submit(() -> {
        try { GalleryIndex.rebuild(context, true, () -> false); }
        catch (Exception e) { throw new RuntimeException(e); }
      }).get(90, TimeUnit.SECONDS);
    }
  }
}
