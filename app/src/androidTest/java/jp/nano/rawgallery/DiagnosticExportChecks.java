package jp.nano.rawgallery;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.SystemClock;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

final class DiagnosticExportChecks {
  private static final String SECRET = "synthetic-token-do-not-export";
  private static final String PATH = "/sdcard/DCIM/private-photo.JPG";
  private static final String SESSION = "abcdef01-23ab-45cd-67ef-0123456789ab";

  static String run(Context context) throws Exception {
    require("ranchu".equals(Build.HARDWARE));
    require((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0);
    require(DiagnosticLog.currentRecorder() == null);

    var intent = DiagnosticDestination.folderPicker();
    require(Intent.ACTION_OPEN_DOCUMENT_TREE.equals(intent.getAction())
        && intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false));

    File folder = new File(context.getFilesDir(), "diagnostics");
    require(folder.isDirectory() || folder.mkdirs());
    File blocked = new File(folder, "events.jsonl");
    File original = new File(folder, "events.fixture-backup");
    require(!original.exists());
    boolean hadFile = blocked.exists();
    if (hadFile) Files.move(blocked.toPath(), original.toPath());
    try {
      require(blocked.mkdir());
      require(!DiagnosticLog.startRecording(context) && DiagnosticLog.currentRecorder() == null);
      require(!context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).getBoolean("record", false));
    } finally {
      Files.deleteIfExists(blocked.toPath());
      if (hadFile) Files.move(original.toPath(), blocked.toPath());
    }
    write(folder, "previous.jsonl", event("input.back", 1).toString());
    write(folder, "events.jsonl", event("image.memory", 2).toString()
        + "\n" + event(SECRET, 3) + "\n" + event("image.request", 4) + "\ninvalid JSON " + SECRET + "\n");
    write(folder, "last-crash.json", new JSONObject()
        .put("version_code", 230).put("exception", "java.io.IOException")
        .put("frame", new JSONObject().put("class", "java.lang.String").put("method", "length").put("line", 10)
            .put("path", PATH))
        .put("message", SECRET).toString());
    for (String name : Set.of("last-crash.json.new", "import.log", "original.jpg", "preferences.xml")) {
      write(folder, name, SECRET);
    }
    Map<String, String> contents = unzip(DiagnosticExport.capture(context));
    require(contents.keySet().equals(Set.of("events.jsonl", "previous.jsonl", "last-crash.json")));
    for (String value : contents.values()) {
      require(!value.contains(SECRET) && !value.contains(PATH) && !value.contains(SESSION));
    }
    JSONObject newer = new JSONObject(contents.get("events.jsonl"));
    JSONObject older = new JSONObject(contents.get("previous.jsonl"));
    require(contents.get("events.jsonl").lines().count() == 1);
    require("image.memory".equals(newer.getString("event")) && newer.getBoolean("hit"));
    require(newer.getInt("session") == older.getInt("session") && newer.getInt("seq") == 2);
    JSONObject crash = new JSONObject(contents.get("last-crash.json"));
    require("java.io.IOException".equals(crash.getString("exception"))
        && "length".equals(crash.getJSONObject("frame").getString("method")));
    require(DiagnosticExport.archive(Map.of(
        "events.jsonl", new byte[DiagnosticExport.EVENT_FILE_LIMIT + 1],
        "last-crash.json", new JSONObject().put("exception", "java.lang." + SECRET)
            .put("version_code", 230).toString().getBytes(StandardCharsets.UTF_8))).length == 0);

    String metadata = event("operation.exception", 5).put("phase", "index.e2_metadata")
        + "\n" + event("operation.exception", 6).put("phase", "index.e2_metadata_row") + "\n";
    String retained = unzip(DiagnosticExport.archive(Map.of(
        "events.jsonl", metadata.getBytes(StandardCharsets.UTF_8)))).get("events.jsonl");
    String[] phases = retained.split("\n");
    require(phases.length == 2);
    require("index.e2_metadata".equals(new JSONObject(phases[0]).getString("phase")));
    require("index.e2_metadata_row".equals(new JSONObject(phases[1]).getString("phase")));

    // Five levels are sufficient to test the policy without stressing the Android parser.
    JSONObject nested = new JSONObject().put("version_code", 230).put("exception", "java.io.IOException")
        .put("unknown", new JSONArray().put(new JSONArray().put(new JSONArray().put(new JSONArray().put(0)))));
    require(DiagnosticExport.archive(Map.of(
        "last-crash.json", nested.toString().getBytes(StandardCharsets.UTF_8))).length == 0);

    DiagnosticLog.event("video.prepared", 0, "elapsed_ms", 863241234);
    require(!unzip(DiagnosticExport.capture(context)).get("events.jsonl").contains("863241234"));
    require(DiagnosticLog.startRecording(context));
    DiagnosticLog.Recorder recording = DiagnosticLog.currentRecorder();
    require(recording != null);
    DiagnosticLog.event("video.prepared", 0, "elapsed_ms", 556677889, "path", PATH);
    require(DiagnosticLog.stopRecording(context, recording) && DiagnosticLog.currentRecorder() == null);
    DiagnosticLog.event("video.prepared", 0, "elapsed_ms", 999999991);
    String events = unzip(DiagnosticExport.capture(context)).get("events.jsonl");
    require(events.contains("556677889") && !events.contains("999999991") && !events.contains(PATH));
    checkFailedRecording(context);
    checkOutput("write", false);
    checkOutput("close", false);
    checkOutput("close", true);
    checkOutput("", false);
    return "PASS non-debuggable diagnostics, failed open, stop tail, E2 phases, redaction, ZIP output failures, and failed recorder identity";
  }

  private static void checkFailedRecording(Context context) throws Exception {
    File root = Files.createTempDirectory(context.getCacheDir().toPath(), "diagnostic-writer-fixture").toFile();
    File folder = new File(root, "diagnostics");
    File current = new File(folder, "events.jsonl");
    File previous = new File(folder, "previous.jsonl");
    File child = new File(previous, "fixture");
    try {
      require(folder.mkdir() && previous.mkdir() && child.createNewFile());
      try (RandomAccessFile file = new RandomAccessFile(current, "rw")) {
        file.setLength(DiagnosticExport.EVENT_FILE_LIMIT - 16 * 1024);
      }
      Context isolated = new ContextWrapper(context) {
        @Override public File getFilesDir() { return root; }
      };
      require(DiagnosticLog.startRecording(isolated));
      DiagnosticLog.Recorder displayed = DiagnosticLog.currentRecorder();
      require(displayed != null);

      // Trigger a real rotation while the writer is active. The non-empty previous directory
      // cannot be deleted, so the writer detaches before the stale dialog requests a stop.
      char[] padding = new char[24 * 1024];
      Arrays.fill(padding, 'x');
      DiagnosticLog.event("video.prepared", 0, "fixture", new String(padding));
      long deadline = SystemClock.elapsedRealtime() + 5000;
      while (DiagnosticLog.currentRecorder() == displayed) {
        require(SystemClock.elapsedRealtime() < deadline);
        SystemClock.sleep(10);
      }
      require(DiagnosticLog.currentRecorder() == null);
      require(!DiagnosticLog.stopRecording(context, displayed));
      var prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE);
      require(!prefs.getBoolean("record", true));

      require(DiagnosticLog.startRecording(context));
      DiagnosticLog.Recorder restarted = DiagnosticLog.currentRecorder();
      require(restarted != null && restarted != displayed);
      require(!DiagnosticLog.stopRecording(context, displayed));
      require(DiagnosticLog.currentRecorder() == restarted && prefs.getBoolean("record", false));
      require(DiagnosticLog.stopRecording(context, restarted));
      require(DiagnosticLog.stopRecording(context, restarted));
      require(!DiagnosticLog.stopRecording(context, displayed));
      require(DiagnosticLog.currentRecorder() == null && !prefs.getBoolean("record", true));
    } finally {
      DiagnosticLog.Recorder remaining = DiagnosticLog.currentRecorder();
      if (remaining != null) DiagnosticLog.stopRecording(context, remaining);
      for (File fixture : List.of(child, previous, current, folder, root)) {
        Files.deleteIfExists(fixture.toPath());
      }
    }
  }

  private static void checkOutput(String failedStep, boolean cleanupFails) throws Exception {
    List<String> lifecycle = new ArrayList<>();
    var output = new DiagnosticDestination.NewOutput<String>() {
      @Override public OutputStream open() {
        lifecycle.add("open");
        return new OutputStream() {
          @Override public void write(int value) throws IOException {
            lifecycle.add("write");
            if ("write".equals(failedStep)) throw new IOException("fixture");
          }

          @Override public void close() throws IOException {
            lifecycle.add("close");
            if ("close".equals(failedStep)) throw new IOException("fixture");
          }
        };
      }

      @Override public String publish() {
        lifecycle.add("publish");
        return "owned";
      }

      @Override public boolean delete() {
        lifecycle.add("delete");
        return !cleanupFails;
      }
    };
    try {
      require("owned".equals(DiagnosticDestination.writeNew(new byte[] {1}, output)));
      require(failedStep.isEmpty());
    } catch (IOException error) {
      require(!failedStep.isEmpty() && (error instanceof DiagnosticDestination.CleanupFailed) == cleanupFails);
    }
    require(lifecycle.equals(List.of("open", "write", "close", failedStep.isEmpty() ? "publish" : "delete")));
  }

  private static void write(File folder, String name, String value) throws Exception {
    Files.write(new File(folder, name).toPath(), value.getBytes(StandardCharsets.UTF_8));
  }

  private static JSONObject event(String name, int sequence) throws Exception {
    return new JSONObject().put("event", name).put("session", SESSION).put("seq", sequence)
        .put("ns", sequence * 100).put("request", 0).put("hit", true).put("file", PATH)
        .put("error", SECRET).put("unknown", SECRET);
  }

  private static Map<String, String> unzip(byte[] bytes) throws Exception {
    Map<String, String> contents = new HashMap<>();
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        require(contents.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8)) == null);
      }
    }
    return contents;
  }

  private static void require(boolean condition) {
    if (!condition) throw new AssertionError("Diagnostic export fixture check failed");
  }
}
