package jp.nano.rawgallery;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A ZIP contains reconstructed diagnostic data, with no copy of the source file bytes. */
final class DiagnosticExport {
  static final int EVENT_FILE_LIMIT = 2 * 1024 * 1024;
  // The legacy frame, eight top frames, the app frame and eight cause names fit with 240-character
  // labels.
  static final int CRASH_FILE_LIMIT = 8 * 1024;
  private static final int CRASH_JSON_DEPTH = 4;
  private static final int EVENT_LINE_LIMIT = 8192;
  private static final Pattern SESSION =
      Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
  private static final String[] EVENT_FILES = {"previous.jsonl", "events.jsonl"};
  private static final String CRASH_FILE = "last-crash.json";
  private static final Set<String> EVENTS =
      Set.of(
          "action.back",
          "action.button.end",
          "action.button.start",
          "action.grid",
          "activity.create",
          "activity.destroy",
          "activity.pause",
          "activity.resume",
          "bitmap.decode.end",
          "bitmap.decode.start",
          "burst.open",
          "burst.select",
          "cache.compact",
          "cache.compact_invalidated",
          "cache.source",
          "cache.trim.end",
          "cache.trim.start",
          "dashboard.read_failed",
          "dashboard.snapshot.end",
          "dashboard.snapshot.start",
          "dialog.first_draw",
          "dialog.frame_commit",
          "dialog.frame_unavailable",
          "dialog.input",
          "dialog.show",
          "operation.exception",
          "image.bind",
          "image.discard",
          "image.draw",
          "image.draw_detached",
          "image.draw_superseded",
          "image.fail",
          "image.failure_applied",
          "image.frame_commit",
          "image.frame_unavailable",
          "image.memory",
          "image.placeholder",
          "image.post",
          "image.provisional",
          "image.request",
          "index.burst_probe",
          "index.end",
          "index.metadata_end",
          "index.metadata_start",
          "index.publish_start",
          "index.published",
          "index.scan_end",
          "index.scan_start",
          "index.start",
          "input.back",
          "input.dispatch_end",
          "input.key",
          "input.touch",
          "jpeg.source.end",
          "jpeg.source.start",
          "library.tab",
          "media.changed",
          "nano.complete",
          "nano.failed",
          "nano.start",
          "photo.edit.chooser.denied",
          "photo.edit.chooser.open",
          "photo.edit.chooser.unavailable",
          "photo.edit.external.open",
          "photo.edit.internal.open",
          "photo.edit.prepare.cancelled",
          "photo.edit.prepare.complete",
          "photo.edit.prepare.failed",
          "photo.edit.prepare.start",
          "photo_move.confirmation_ready",
          "photo_move.preflight",
          "photo_move.run",
          "preparation.item_cancel",
          "preparation.item_end",
          "preparation.item_fail",
          "preparation.item_start",
          "preparation.pause",
          "preparation.progress",
          "preview.compact.end",
          "preview.compact.start",
          "ranking.bulk.failed",
          "ranking.bulk.frame",
          "ranking.bulk.saved",
          "ranking.bulk.start",
          "ranking.direct.failed",
          "ranking.direct.frame",
          "ranking.direct.saved",
          "ranking.direct.start",
          "ranking.single.failed",
          "ranking.single.frame",
          "ranking.single.saved",
          "ranking.single.start",
          "raw.extract.end",
          "raw.extract.start",
          "raw.inspect.end",
          "raw.inspect.start",
          "repository.fail",
          "repository.lock_acquired",
          "repository.lock_wait",
          "repository.preview.end",
          "repository.preview.start",
          "repository.sourcePreview.end",
          "repository.sourcePreview.start",
          "screen.albumHome",
          "screen.burstDetail",
          "screen.editTargets",
          "screen.gallery",
          "screen.galleryMenu",
          "screen.index_applied",
          "screen.loadAlbums",
          "screen.loadIndexedPhotos",
          "screen.openRanking",
          "screen.photo",
          "screen.photoFilter",
          "screen.photoMenu",
          "screen.refresh",
          "screen.selectionControls",
          "screen.shareSelection",
          "screen.showRanking",
          "session",
          "share.history.failed",
          "share.history.ignored",
          "share.history.saved",
          "share.history.start",
          "tag.confidence.end",
          "tag.confidence.insufficient",
          "tag.confidence.result",
          "tag.confidence.start",
          "tag.selection.complete",
          "tag.selection.failed",
          "tag.selection.start",
          "tag.threshold.preview",
          "task.cancel",
          "task.end",
          "task.enqueue",
          "task.fail",
          "task.start",
          "thumbnail.provider.end",
          "thumbnail.provider.start",
          "video.complete",
          "video.error",
          "video.open",
          "video.pause",
          "video.play",
          "video.prepared");
  private static final Set<String> NUMBERS =
      Set.of(
          "parent",
          "thread",
          "dropped",
          "version",
          "wall_ms",
          "elapsed_ms",
          "elapsedMs",
          "input_uptime_ms",
          "action",
          "code",
          "position",
          "count",
          "size",
          "burst",
          "ready",
          "total",
          "failed",
          "queued",
          "groups",
          "numbered",
          "positive",
          "negative",
          "scored",
          "threshold",
          "visible",
          "chosen",
          "together",
          "what",
          "extra",
          "x",
          "y",
          "width",
          "height");
  private static final Set<String> FLAGS =
      Set.of("hardware", "albums", "hit", "force", "resume", "applied");
  private static final Map<String, Set<String>> LABELS =
      Map.of(
          "format", Set.of("JPEG", "RAW", "VIDEO", "jpeg", "raw", "video"),
          "mode", Set.of("default", "long-press"),
          "kind", Set.of("thumbnail", "dispatchTouchEvent", "dispatchKeyEvent"),
          "result", Set.of("success", "complete", "cancelled", "paused", "failed"),
          "phase",
              Set.of(
                  "camera.source_lookup",
                  "camera.wifi_profiles",
                  "camera.sd_status",
                  "camera.sd_verify",
                  "index.e2_metadata",
                  "index.e2_metadata_row",
                  "e2_cleanup.current_revision",
                  "e2_cleanup.plan",
                  "e2_cleanup.remove",
                  "e2_cleanup.preview",
                  "nano.admission",
                  "nano.foreground",
                  "nano.foreground_stop",
                  "nano.resume",
                  "nano.worker_start",
                  "nano.result_recovery",
                  "nano.discard",
                  "nano.execute",
                  "nano.settings_commit",
                  "nano.settings_restore",
                  "nano.credentials_api_save",
                  "nano.credentials_api_read",
                  "nano.credentials_e2_save",
                  "nano.credentials_e2_read",
                  "nano.job_read",
                  "preparation.item",
                  "preparation.run",
                  "amazon.candidate_preview",
                  "amazon.scan",
                  "amazon.sync_item",
                  "amazon.sync",
                  "amazon.local_revision"),
          "outcome",
              Set.of(
                  "cancelled",
                  "failed",
                  "busy",
                  "unavailable",
                  "skipped",
                  "paused",
                  "auth_required"),
          "error",
              Set.of(
                  "IOException",
                  "InterruptedIOException",
                  "SecurityException",
                  "IllegalStateException",
                  "IllegalArgumentException",
                  "SQLiteException",
                  "NullPointerException"));

  private DiagnosticExport() {}

  static byte[] capture(Context context) throws IOException {
    File folder = new File(context.getFilesDir(), "diagnostics");
    if (!Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) return new byte[0];
    Map<String, byte[]> snapshots = new HashMap<>();
    synchronized (DiagnosticLog.FILES) {
      for (String name : EVENT_FILES) snapshots.put(name, read(folder, name, EVENT_FILE_LIMIT));
      snapshots.put(CRASH_FILE, read(folder, CRASH_FILE, CRASH_FILE_LIMIT));
    }
    return archive(snapshots);
  }

  private static byte[] read(File folder, String name, int limit) throws IOException {
    var path = new File(folder, name).toPath();
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return new byte[0];
    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      int length;
      byte[] chunk = new byte[8192];
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      while ((length = input.read(chunk)) != -1) {
        if (length > limit - bytes.size()) return new byte[0];
        bytes.write(chunk, 0, length);
      }
      return bytes.toByteArray();
    }
  }

  static byte[] archive(Map<String, byte[]> snapshots) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Map<String, Integer> sessions = new HashMap<>();
    int entries = 0;
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (String name : EVENT_FILES) {
        byte[] source = snapshots.get(name);
        if (source == null || source.length > EVENT_FILE_LIMIT) continue;
        String events = events(new String(source, StandardCharsets.UTF_8), sessions);
        if (!events.isEmpty()) {
          add(zip, name, events);
          entries++;
        }
      }
      byte[] crash = snapshots.get(CRASH_FILE);
      if (crash != null && crash.length <= CRASH_FILE_LIMIT) {
        JSONObject sanitized = crash(new String(crash, StandardCharsets.UTF_8));
        if (sanitized != null) {
          add(zip, CRASH_FILE, sanitized.toString() + "\n");
          entries++;
        }
      }
    }
    return entries != 0 ? bytes.toByteArray() : new byte[0];
  }

  private static void add(ZipOutputStream zip, String name, String value) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(value.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static String events(String source, Map<String, Integer> sessions) throws IOException {
    StringBuilder safe = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new StringReader(source))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.length() > EVENT_LINE_LIMIT) continue;
        try {
          JSONObject data = new JSONObject(line);
          String event = data.optString("event");
          String session = data.optString("session");
          if (!EVENTS.contains(event)
              || !SESSION.matcher(session).matches()
              || number(data.opt("seq")) == null
              || number(data.opt("ns")) == null
              || number(data.opt("request")) == null) continue;
          JSONObject output =
              new JSONObject()
                  .put("event", event)
                  .put("seq", data.get("seq"))
                  .put("ns", data.get("ns"))
                  .put("request", data.get("request"));
          for (String name : NUMBERS) {
            Number value = number(data.opt(name));
            if (value != null) output.put(name, value);
          }
          for (String name : FLAGS) {
            if (data.opt(name) instanceof Boolean value) output.put(name, value);
          }
          for (var entry : LABELS.entrySet()) {
            if (data.opt(entry.getKey()) instanceof String value
                && entry.getValue().contains(value)) {
              output.put(entry.getKey(), value);
            }
          }
          if ("operation.exception".equals(event)) {
            Class<?> exception = exceptionClass(data.optString("exception"));
            if (exception != null) output.put("exception", exception.getName());
          }
          // Keep the fields required by the existing timeline analyzer.
          if ("image.request".equals(event)
              && (!output.has("size") || !output.has("burst") || !output.has("format"))) continue;
          output.put("session", sessions.computeIfAbsent(session, ignored -> sessions.size() + 1));
          safe.append(output).append('\n');
        } catch (JSONException | StackOverflowError ignored) {
          // Files from older versions can contain malformed lines.
        }
      }
    }
    return safe.toString();
  }

  // JSONTokener recursively parses nested JSON. Bound the retained crash file's depth first.
  private static boolean crashDepthAllowed(String source) {
    try (JsonReader reader = new JsonReader(new StringReader(source))) {
      int depth = 0;
      while (reader.peek() != JsonToken.END_DOCUMENT) {
        switch (reader.peek()) {
          case BEGIN_OBJECT -> {
            if (++depth > CRASH_JSON_DEPTH) return false;
            reader.beginObject();
          }
          case BEGIN_ARRAY -> {
            if (++depth > CRASH_JSON_DEPTH) return false;
            reader.beginArray();
          }
          case END_OBJECT -> {
            reader.endObject();
            depth--;
          }
          case END_ARRAY -> {
            reader.endArray();
            depth--;
          }
          case NAME -> reader.nextName();
          default -> reader.skipValue();
        }
      }
      return depth == 0;
    } catch (IOException | IllegalStateException ignored) {
      return false;
    }
  }

  private static JSONObject crash(String source) {
    if (!crashDepthAllowed(source)) return null;
    try {
      JSONObject input = new JSONObject(source);
      Number version = number(input.opt("version_code"));
      Class<?> exception = exceptionClass(input.opt("exception"));
      if (version == null || exception == null) return null;
      JSONObject output =
          new JSONObject().put("version_code", version).put("exception", exception.getName());
      JSONObject frame = knownFrame(input.optJSONObject("frame"));
      if (frame != null) output.put("frame", frame);

      JSONArray frames = input.optJSONArray("frames");
      if (frames != null) {
        JSONArray safeFrames = new JSONArray();
        for (int index = 0;
            index < Math.min(frames.length(), DiagnosticCrashHandler.MAX_FRAMES);
            index++) {
          JSONObject safeFrame = knownFrame(frames.optJSONObject(index));
          if (safeFrame != null) safeFrames.put(safeFrame);
        }
        output.put("frames", safeFrames);
      }
      JSONObject appFrame = input.optJSONObject("app_frame");
      if (appFrame != null
          && appFrame.opt("class") instanceof String owner
          && owner.startsWith(DiagnosticCrashHandler.APP_CLASS_PREFIX)) {
        JSONObject safeAppFrame = knownFrame(appFrame);
        if (safeAppFrame != null) output.put("app_frame", safeAppFrame);
      }
      JSONArray causes = input.optJSONArray("causes");
      if (causes != null) {
        JSONArray safeCauses = new JSONArray();
        for (int index = 0;
            index < Math.min(causes.length(), DiagnosticCrashHandler.MAX_CAUSES);
            index++) {
          Class<?> cause = exceptionClass(causes.opt(index));
          if (cause != null) safeCauses.put(cause.getName());
        }
        output.put("causes", safeCauses);
      }
      return output;
    } catch (JSONException | LinkageError | SecurityException ignored) {
      return null;
    }
  }

  static JSONObject knownFrame(JSONObject frame) throws JSONException {
    if (frame == null
        || !(frame.opt("method") instanceof String method)
        || method.length() > DiagnosticCrashHandler.MAX_CODE_LABEL_LENGTH) return null;
    Class<?> owner = codeClass(frame.opt("class"));
    Number line = number(frame.opt("line"));
    if (owner == null || line == null || !knownMethod(owner, method)) return null;
    return new JSONObject().put("class", owner.getName()).put("method", method).put("line", line);
  }

  private static Number number(Object value) {
    return value instanceof Integer || value instanceof Long ? (Number) value : null;
  }

  private static Class<?> exceptionClass(Object value) {
    Class<?> type = codeClass(value);
    return type != null && Throwable.class.isAssignableFrom(type) ? type : null;
  }

  private static Class<?> codeClass(Object value) {
    if (!(value instanceof String name)
        || name.length() > DiagnosticCrashHandler.MAX_CODE_LABEL_LENGTH
        || (!name.startsWith(DiagnosticCrashHandler.APP_CLASS_PREFIX)
            && !name.startsWith("java.")
            && !name.startsWith("android.")
            && !name.startsWith("androidx."))) return null;
    try {
      return Class.forName(name, false, DiagnosticExport.class.getClassLoader());
    } catch (ClassNotFoundException | LinkageError | SecurityException ignored) {
      return null;
    }
  }

  private static boolean knownMethod(Class<?> owner, String name) {
    try {
      return "<init>".equals(name)
          || "<clinit>".equals(name)
          || Arrays.stream(owner.getDeclaredMethods())
              .anyMatch(method -> method.getName().equals(name));
    } catch (LinkageError | SecurityException ignored) {
      return false;
    }
  }
}
