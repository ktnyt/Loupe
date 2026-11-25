package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Context;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.BaseAdapter;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

/** Temporary independent measurements for the unchanged import-latency verdict. */
final class ImportLatencyProbe {
  private static final String TAG = "LoupeImportFixture";
  private final Instrumentation test;
  private final MainActivity activity;
  private final String folder;
  private final Field request = declare(DiagnosticImageView.class, "request");
  private final Field drawn = declare(DiagnosticImageView.class, "drawn");
  private final boolean recordingAtStart = DiagnosticLog.currentRecorder() != null;
  private final DataSetObserver notifications = new DataSetObserver() {
    @Override public void onChanged() { changes++; }
    @Override public void onInvalidated() { invalidations++; }
  };
  private final ViewTreeObserver.OnPreDrawListener drawing = this::onPreDraw;
  private BaseAdapter observed;
  private int changes, invalidations;
  private long independentFrame;
  private final long currentAtStart, previousAtStart;

  ImportLatencyProbe(Instrumentation t, MainActivity a, String folder) {
    test = t;
    activity = a;
    this.folder = folder;
    currentAtStart = log("events.jsonl").length();
    previousAtStart = log("previous.jsonl").length();
    test.runOnMainSync(() -> {
      observed = (BaseAdapter) field(activity, "adapter");
      if (observed != null) observed.registerDataSetObserver(notifications);
      activity.getWindow().getDecorView().getViewTreeObserver().addOnPreDrawListener(drawing);
    });
  }

  private boolean onPreDraw() {
    if (independentFrame != 0) return true;
    ThumbnailCell tile = tile();
    if (tile == null || !(tile.image instanceof DiagnosticImageView image)
        || !image.isShown() || !image.isHardwareAccelerated() || image.getDrawable() == null) return true;
    long id = trace(request, image);
    if (id == 0) return true;
    var drawable = image.getDrawable();
    image.getViewTreeObserver().registerFrameCommitCallback(() -> {
      if (independentFrame == 0 && image.isAttachedToWindow() && image.getDrawable() == drawable
          && trace(drawn, image) == id) independentFrame = SystemClock.elapsedRealtimeNanos();
    });
    return true;
  }

  private ThumbnailCell tile() {
    var grid = (View) field(activity, "grid");
    if (grid == null) return null;
    var candidate = GalleryScreenChecks.firstPhoto(grid);
    return candidate != null && candidate.getContentDescription() != null
        && candidate.getContentDescription().toString().startsWith("generated-") ? candidate : null;
  }

  void record(Context context, List<Uri> owned, long expectedBytes, long publishing, long applied, long frame,
      Set<Long> ids) {
    try {
      int published = 0, rightSize = 0;
      for (Uri uri : owned) {
        try (var row = context.getContentResolver().query(uri,
            new String[]{MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE}, null, null, null)) {
          if (row != null && row.moveToFirst() && row.getInt(0) == 0) {
            published++;
            if (row.getLong(1) == expectedBytes) rightSize++;
          }
        }
      }
      int indexed = 0;
      try (GalleryIndex index = new GalleryIndex(context)) {
        for (PhotoRepository.Photo photo : index.load(folder, "すべて").photos) {
          ids.add(DiagnosticLog.photo(photo));
          if (photo.size == expectedBytes) indexed++;
        }
      }
      String[] screen = {""};
      test.runOnMainSync(() -> {
        var grid = (android.widget.ListView) field(activity, "grid");
        var adapter = (BaseAdapter) field(activity, "adapter");
        var candidate = tile();
        var image = candidate == null ? null : (DiagnosticImageView) candidate.image;
        screen[0] = " photos=" + ((List<?>) field(activity, "photos")).size()
            + " displayed=" + ((List<?>) field(activity, "displayed")).size()
            + " adapter=" + (adapter == null ? -1 : adapter.getCount())
            + " children=" + (grid == null ? -1 : grid.getChildCount())
            + " notify=" + changes + "/" + invalidations
            + " focus=" + activity.hasWindowFocus()
            + " pending=" + field(activity, "pendingIndexUpdate")
            + " indexVersion=" + field(activity, "indexUpdateVersion")
            + " bound=" + (image != null && image.getDrawable() != null)
            + " hardware=" + (image != null && image.isHardwareAccelerated())
            + " drawn=" + (image != null && trace(drawn, image) != 0)
            + " independent_ms=" + elapsed(publishing, independentFrame);
      });
      FilesProbe snapshot;
      synchronized (DiagnosticLog.FILES) {
        snapshot = new FilesProbe(read(log("previous.jsonl"), publishing), read(log("events.jsonl"), publishing));
      }
      Log.i(TAG, "published=" + published + "/" + owned.size() + " right_size=" + rightSize
          + " indexed=" + indexed + " applied_ms=" + elapsed(publishing, applied)
          + " legacy_frame_ms=" + elapsed(publishing, frame)
          + " recording=" + recordingAtStart + "/" + (DiagnosticLog.currentRecorder() != null)
          + " bytes_before=" + previousAtStart + "/" + currentAtStart
          + " bytes_after=" + log("previous.jsonl").length() + "/" + log("events.jsonl").length()
          + screen[0] + snapshot.summarize(ids, publishing));
    } catch (Exception failure) {
      Log.e(TAG, "probe_failure=" + failure.getClass().getSimpleName());
    }
  }

  void close() {
    test.runOnMainSync(() -> {
      var observer = activity.getWindow().getDecorView().getViewTreeObserver();
      if (observer.isAlive()) observer.removeOnPreDrawListener(drawing);
      if (observed != null) observed.unregisterDataSetObserver(notifications);
    });
  }

  private File log(String name) {
    return new File(new File(activity.getFilesDir(), "diagnostics"), name);
  }

  private static List<JSONObject> read(File log, long publishing) throws Exception {
    var result = new ArrayList<JSONObject>();
    if (log.isFile()) {
      for (String line : Files.readAllLines(log.toPath())) {
        if (line.isEmpty() || !line.endsWith("}")) continue;
        try {
          JSONObject value = new JSONObject(line);
          if (value.optLong("ns") >= publishing) result.add(value);
        } catch (org.json.JSONException malformed) {
          // The measurement does not report any source diagnostic text.
        }
      }
    }
    return result;
  }

  private record FilesProbe(List<JSONObject> previous, List<JSONObject> current) {
    String summarize(Set<Long> ids, long publishing) {
      Set<Long> old = requests(previous, ids), now = requests(current, ids);
      Set<Long> both = new HashSet<>(old);
      both.addAll(now);
      long frame = java.util.stream.Stream.concat(previous.stream(), current.stream())
          .filter(e -> e.optString("event").equals("image.frame_commit") && both.contains(e.optLong("request")))
          .mapToLong(e -> e.optLong("ns")).min().orElse(0);
      long cross = current.stream()
          .filter(e -> e.optString("event").equals("image.frame_commit")
              && old.contains(e.optLong("request")) && !now.contains(e.optLong("request"))).count();
      return " req_prev=" + old.size() + " req_cur=" + now.size()
          + " bind=" + count(both, "image.bind") + " draw=" + count(both, "image.draw")
          + " unavailable=" + count(both, "image.frame_unavailable")
          + " log_frame_ms=" + elapsed(publishing, frame) + " cross_rotation_frames=" + cross
          + " media_events=" + count(null, "media.changed")
          + " index_apply_events=" + count(null, "screen.index_applied");
    }

    private long count(Set<Long> requests, String event) {
      return java.util.stream.Stream.concat(previous.stream(), current.stream())
          .filter(e -> e.optString("event").equals(event)
              && (requests == null || requests.contains(e.optLong("request")))).count();
    }

    private static Set<Long> requests(List<JSONObject> events, Set<Long> ids) {
      Set<Long> result = new HashSet<>();
      for (JSONObject e : events)
        if (e.optString("event").equals("image.request") && ids.contains(e.optLong("photo")))
          result.add(e.optLong("request"));
      return result;
    }
  }

  private static long elapsed(long start, long value) {
    return value == 0 ? -1 : (value - start) / 1_000_000;
  }

  private static Field declare(Class<?> owner, String name) {
    try {
      Field field = owner.getDeclaredField(name);
      field.setAccessible(true);
      return field;
    } catch (ReflectiveOperationException error) {
      throw new IllegalStateException(error);
    }
  }

  private static Object field(MainActivity owner, String name) {
    try {
      return declare(MainActivity.class, name).get(owner);
    } catch (IllegalAccessException error) {
      throw new IllegalStateException(error);
    }
  }

  private static long trace(Field field, DiagnosticImageView view) {
    try {
      return field.getLong(view);
    } catch (IllegalAccessException error) {
      throw new IllegalStateException(error);
    }
  }
}
