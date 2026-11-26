package jp.nano.rawgallery;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
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
import org.json.JSONException;
import org.json.JSONObject;

final class DiagnosticExportChecks {
  private static final String SECRET = "synthetic-token-do-not-export";
  private static final String PATH = "/sdcard/DCIM/private-photo.JPG";
  private static final String SESSION = "abcdef01-23ab-45cd-67ef-0123456789ab";

  static String run(Context context) throws Exception {
    require("ranchu".equals(Build.HARDWARE));
    require((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0);
    require(DiagnosticLog.currentRecorder() == null);

    checkFolderPicker(context);

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
      require(
          !context
              .getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
              .getBoolean("record", false));
    } finally {
      Files.deleteIfExists(blocked.toPath());
      if (hadFile) Files.move(original.toPath(), blocked.toPath());
    }
    write(folder, "previous.jsonl", event("input.back", 1).toString());
    write(
        folder,
        "events.jsonl",
        event("image.memory", 2).toString()
            + "\n"
            + event(SECRET, 3)
            + "\n"
            + event("image.request", 4)
            + "\ninvalid JSON "
            + SECRET
            + "\n");
    write(
        folder,
        "last-crash.json",
        new JSONObject()
            .put("version_code", 230)
            .put("exception", "java.io.IOException")
            .put(
                "frame",
                new JSONObject()
                    .put("class", "java.lang.String")
                    .put("method", "length")
                    .put("line", 10)
                    .put("path", PATH))
            .put("message", SECRET)
            .toString());
    for (String name :
        Set.of("last-crash.json.new", "import.log", "original.jpg", "preferences.xml")) {
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
    require(
        crash.length() == 3
            && "java.io.IOException".equals(crash.getString("exception"))
            && "length".equals(crash.getJSONObject("frame").getString("method")));
    checkExpandedCrash(context, folder);
    checkUnverifiedCrashFrames(context, folder);
    checkUnsupportedPrimaryCrash(context, folder);
    require(
        DiagnosticExport.archive(
                    Map.of(
                        "events.jsonl",
                        new byte[DiagnosticExport.EVENT_FILE_LIMIT + 1],
                        "last-crash.json",
                        new JSONObject()
                            .put("exception", "java.lang." + SECRET)
                            .put("version_code", 230)
                            .toString()
                            .getBytes(StandardCharsets.UTF_8)))
                .length
            == 0);

    String metadata =
        event("operation.exception", 5).put("phase", "index.e2_metadata")
            + "\n"
            + event("operation.exception", 6).put("phase", "index.e2_metadata_row")
            + "\n";
    String retained =
        unzip(
                DiagnosticExport.archive(
                    Map.of("events.jsonl", metadata.getBytes(StandardCharsets.UTF_8))))
            .get("events.jsonl");
    String[] phases = retained.split("\n");
    require(phases.length == 2);
    require("index.e2_metadata".equals(new JSONObject(phases[0]).getString("phase")));
    require("index.e2_metadata_row".equals(new JSONObject(phases[1]).getString("phase")));

    // Five levels are sufficient to test the policy without stressing the Android parser.
    JSONObject nested =
        new JSONObject()
            .put("version_code", 230)
            .put("exception", "java.io.IOException")
            .put(
                "unknown",
                new JSONArray()
                    .put(new JSONArray().put(new JSONArray().put(new JSONArray().put(0)))));
    require(
        DiagnosticExport.archive(
                    Map.of("last-crash.json", nested.toString().getBytes(StandardCharsets.UTF_8)))
                .length
            == 0);

    DiagnosticLog.event("video.prepared", 0, "elapsed_ms", 863241234);
    require(!unzip(DiagnosticExport.capture(context)).get("events.jsonl").contains("863241234"));
    require(DiagnosticLog.startRecording(context));
    DiagnosticLog.Recorder recording = DiagnosticLog.currentRecorder();
    require(recording != null);
    DiagnosticLog.event("video.prepared", 0, "elapsed_ms", 556677889, "path", PATH);
    require(
        DiagnosticLog.stopRecording(context, recording) && DiagnosticLog.currentRecorder() == null);
    DiagnosticLog.event("video.prepared", 0, "elapsed_ms", 999999991);
    String events = unzip(DiagnosticExport.capture(context)).get("events.jsonl");
    require(
        events.contains("556677889") && !events.contains("999999991") && !events.contains(PATH));
    checkFailedRecording(context);
    checkOutput("write", false);
    checkOutput("close", false);
    checkOutput("close", true);
    checkOutput("", false);
    return "PASS non-debuggable diagnostics, failed open, stop tail, E2 phases, legacy and expanded"
        + " crash redaction, ZIP output failures, failed recorder identity, and system-only"
        + " picker";
  }

  private static void checkExpandedCrash(Context context, File folder) throws Exception {
    Throwable cause = new IOException("private cause " + SECRET);
    for (int index = 0; index < 8; index++) {
      cause = new UnsupportedOperationException("private cause " + PATH, cause);
    }
    Throwable failure = new IllegalStateException(SECRET, cause);
    StackTraceElement[] stack = new StackTraceElement[12];
    Arrays.fill(stack, new StackTraceElement("android.app.Activity", "onCreate", PATH, 42));
    stack[10] = new StackTraceElement(DiagnosticCrashHandler.class.getName(), "save", PATH, 17);
    failure.setStackTrace(stack);
    new DiagnosticCrashHandler(folder, 230, null)
        .uncaughtException(Thread.currentThread(), failure);

    String exported = unzip(DiagnosticExport.capture(context)).get("last-crash.json");
    require(exported != null && !exported.contains(PATH) && !exported.contains(SECRET));
    JSONObject crash = new JSONObject(exported);
    require(
        crash.getInt("version_code") == 230
            && "java.lang.IllegalStateException".equals(crash.getString("exception"))
            && "android.app.Activity".equals(crash.getJSONObject("frame").getString("class"))
            && crash.getJSONArray("frames").length() == 8
            && "onCreate".equals(crash.getJSONArray("frames").getJSONObject(7).getString("method"))
            && "save".equals(crash.getJSONObject("app_frame").getString("method"))
            && crash.getJSONArray("causes").length() == 8
            && "java.lang.UnsupportedOperationException"
                .equals(crash.getJSONArray("causes").getString(7)));

    byte[] saved = Files.readAllBytes(new File(folder, "last-crash.json").toPath());
    require(saved.length > 1024);
    JSONObject injected = new JSONObject(new String(saved, StandardCharsets.UTF_8));
    JSONArray frames = injected.getJSONArray("frames");
    JSONArray causes = injected.getJSONArray("causes");
    injected.getJSONObject("frame").put("path", PATH);
    injected.getJSONObject("app_frame").put("method", SECRET);
    frames.getJSONObject(0).put("message", SECRET);
    frames.getJSONObject(1).put("method", SECRET);
    frames.getJSONObject(2).put("class", new JSONArray().put(PATH));
    frames.getJSONObject(3).put("line", "42");
    frames.put(
        new JSONObject()
            .put("class", "java.lang.String")
            .put("method", "length")
            .put("line", 12345));
    causes.put(0, "java.lang." + SECRET);
    causes.put(1, "java.lang.String");
    causes.put(2, new JSONObject().put("path", PATH));
    causes.put("java.lang.ArithmeticException");
    injected.put("message", SECRET).put("extra", PATH);
    write(folder, "last-crash.json", injected.toString());
    exported = unzip(DiagnosticExport.capture(context)).get("last-crash.json");
    require(
        !exported.contains(PATH)
            && !exported.contains(SECRET)
            && !exported.contains("12345")
            && !exported.contains("ArithmeticException"));
    crash = new JSONObject(exported);
    require(
        crash.length() == 5
            && !crash.has("app_frame")
            && crash.getJSONObject("frame").length() == 3);
    require(
        crash.getJSONArray("frames").length() == 5
            && crash.getJSONArray("frames").getJSONObject(0).length() == 3
            && crash.getJSONArray("causes").length() == 5);

    injected
        .put("frames", SECRET)
        .put("causes", PATH)
        .put("app_frame", injected.getJSONObject("frame"));
    exported =
        unzip(
                DiagnosticExport.archive(
                    Map.of(
                        "last-crash.json", injected.toString().getBytes(StandardCharsets.UTF_8))))
            .get("last-crash.json");
    require(
        new JSONObject(exported).length() == 3
            && !exported.contains(PATH)
            && !exported.contains(SECRET));
    require(
        DiagnosticExport.archive(
                    Map.of("last-crash.json", new byte[DiagnosticExport.CRASH_FILE_LIMIT + 1]))
                .length
            == 0);
  }

  private static void checkUnverifiedCrashFrames(Context context, File folder) throws Exception {
    String filename = "private-photo.jpg";
    String token = "syntheticTokenQ7";
    String missingOwner = "jp.nano.rawgallery.SyntheticMissingOwner";
    Throwable failure = new IllegalStateException(SECRET);
    StackTraceElement legitimate =
        new StackTraceElement("android.app.Activity", "onCreate", PATH, 42);
    StackTraceElement[] stack = new StackTraceElement[10];
    Arrays.fill(stack, legitimate);
    stack[0] = new StackTraceElement("android.app.Activity", filename, PATH, 41);
    stack[2] = new StackTraceElement(DiagnosticCrashHandler.class.getName(), token, PATH, 43);
    stack[3] = new StackTraceElement(missingOwner, "save", PATH, 44);
    stack[8] = new StackTraceElement("java.lang.String", "length", PATH, 45);
    stack[9] = new StackTraceElement(DiagnosticCrashHandler.class.getName(), "save", PATH, 17);
    failure.setStackTrace(stack);

    new DiagnosticCrashHandler(folder, 230, null)
        .uncaughtException(Thread.currentThread(), failure);
    String local =
        new String(
            Files.readAllBytes(new File(folder, "last-crash.json").toPath()),
            StandardCharsets.UTF_8);
    String exported = unzip(DiagnosticExport.capture(context)).get("last-crash.json");
    require(exported != null);
    for (String content : List.of(local, exported)) {
      require(
          !content.contains(filename)
              && !content.contains(token)
              && !content.contains(missingOwner)
              && !content.contains(PATH)
              && !content.contains(SECRET));
      JSONObject summary = new JSONObject(content);
      JSONArray frames = summary.getJSONArray("frames");
      JSONObject appFrame = summary.getJSONObject("app_frame");
      require(
          !summary.has("frame")
              && frames.length() == 5
              && DiagnosticCrashHandler.class.getName().equals(appFrame.getString("class"))
              && "save".equals(appFrame.getString("method"))
              && appFrame.getInt("line") == 17);
      for (int index = 0; index < frames.length(); index++) {
        require(
            "android.app.Activity".equals(frames.getJSONObject(index).getString("class"))
                && "onCreate".equals(frames.getJSONObject(index).getString("method")));
      }
    }
  }

  private static void checkUnsupportedPrimaryCrash(Context context, File folder) throws Exception {
    JSONException failure = new JSONException(SECRET);
    failure.initCause(new IOException(SECRET));
    failure.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement(DiagnosticCrashHandler.class.getName(), "save", PATH, -2)
        });
    new DiagnosticCrashHandler(folder, 230, null)
        .uncaughtException(Thread.currentThread(), failure);
    String local =
        new String(
            Files.readAllBytes(new File(folder, "last-crash.json").toPath()),
            StandardCharsets.UTF_8);
    String exported = unzip(DiagnosticExport.capture(context)).get("last-crash.json");
    require(exported != null);
    for (String content : List.of(local, exported)) {
      require(
          !content.contains(PATH)
              && !content.contains(SECRET)
              && !content.contains(JSONException.class.getName()));
      JSONObject crash = new JSONObject(content);
      JSONObject frame = crash.getJSONObject("frame");
      require(
          crash.getInt("version_code") == 230
              && "unknown".equals(crash.getString("exception"))
              && DiagnosticCrashHandler.class.getName().equals(frame.getString("class"))
              && "save".equals(frame.getString("method"))
              && frame.getInt("line") == -2
              && crash.getJSONArray("frames").length() == 1
              && frame.toString().equals(crash.getJSONArray("frames").getJSONObject(0).toString())
              && frame.toString().equals(crash.getJSONObject("app_frame").toString())
              && crash.getJSONArray("causes").length() == 1
              && IOException.class.getName().equals(crash.getJSONArray("causes").getString(0)));
    }

    JSONObject raw = new JSONObject(local);
    for (String label : List.of(JSONException.class.getName(), "java.lang." + SECRET)) {
      raw.put("exception", label);
      require(archiveCrash(raw).length == 0);
    }
    raw.put("exception", "unknown");
    for (long overflow : new long[] {Integer.MIN_VALUE - 1L, Integer.MAX_VALUE + 1L}) {
      raw.put("version_code", overflow);
      require(archiveCrash(raw).length == 0);
      raw.put("version_code", 230);
      raw.getJSONObject("frame").put("line", overflow);
      raw.getJSONArray("frames").getJSONObject(0).put("line", overflow);
      raw.getJSONObject("app_frame").put("line", overflow);
      JSONObject filtered = new JSONObject(unzip(archiveCrash(raw)).get("last-crash.json"));
      require(
          !filtered.has("frame")
              && !filtered.has("app_frame")
              && filtered.getJSONArray("frames").length() == 0
              && filtered.getJSONArray("causes").length() == 1);
    }
  }

  private static byte[] archiveCrash(JSONObject crash) throws IOException {
    return DiagnosticExport.archive(
        Map.of("last-crash.json", crash.toString().getBytes(StandardCharsets.UTF_8)));
  }

  private static void checkFolderPicker(Context context) throws Exception {
    ResolveInfo ordinary = pickerCandidate("sample.ordinary", 0, true, true);
    ResolveInfo system =
        pickerCandidate("sample.oem.documents", ApplicationInfo.FLAG_SYSTEM, true, true);
    ResolveInfo disabled =
        pickerCandidate("sample.disabled", ApplicationInfo.FLAG_SYSTEM, false, true);
    ResolveInfo privateSystem =
        pickerCandidate("sample.private", ApplicationInfo.FLAG_SYSTEM, true, false);
    ResolveInfo updatedSystem =
        pickerCandidate(
            "sample.updated.documents", ApplicationInfo.FLAG_UPDATED_SYSTEM_APP, true, true);
    ResolveInfo forwarder =
        new ResolveInfo(system) {
          @Override
          public boolean isCrossProfileIntentForwarderActivity() {
            return true;
          }
        };
    require(DiagnosticDestination.systemFolderPicker(List.of()) == null);
    require(DiagnosticDestination.systemFolderPicker(List.of(forwarder)) == null);
    require(
        DiagnosticDestination.systemFolderPicker(List.of(ordinary, disabled, privateSystem))
            == null);
    require(DiagnosticDestination.systemFolderPicker(List.of(system, updatedSystem)) == null);
    require(
        new ComponentName("sample.oem.documents", "sample.Picker")
            .equals(
                DiagnosticDestination.systemFolderPicker(
                    List.of(ordinary, disabled, system, privateSystem, forwarder))));
    require(
        new ComponentName("sample.updated.documents", "sample.Picker")
            .equals(DiagnosticDestination.systemFolderPicker(List.of(updatedSystem))));

    Intent intent = DiagnosticDestination.folderPicker(context);
    require(
        intent != null
            && Intent.ACTION_OPEN_DOCUMENT_TREE.equals(intent.getAction())
            && intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false)
            && intent.getComponent() != null
            && intent.getData() == null
            && intent.getClipData() == null);
    var resolved = context.getPackageManager().getActivityInfo(intent.getComponent(), 0);
    require(
        resolved.exported && (resolved.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
  }

  private static ResolveInfo pickerCandidate(
      String name, int flags, boolean enabled, boolean exported) {
    ResolveInfo candidate = new ResolveInfo();
    candidate.activityInfo = new ActivityInfo();
    candidate.activityInfo.packageName = name;
    candidate.activityInfo.name = "sample.Picker";
    candidate.activityInfo.enabled = enabled;
    candidate.activityInfo.exported = exported;
    candidate.activityInfo.applicationInfo = new ApplicationInfo();
    candidate.activityInfo.applicationInfo.enabled = true;
    candidate.activityInfo.applicationInfo.flags = flags;
    return candidate;
  }

  private static void checkFailedRecording(Context context) throws Exception {
    File root =
        Files.createTempDirectory(context.getCacheDir().toPath(), "diagnostic-writer-fixture")
            .toFile();
    File folder = new File(root, "diagnostics");
    File current = new File(folder, "events.jsonl");
    File previous = new File(folder, "previous.jsonl");
    File child = new File(previous, "fixture");
    try {
      require(folder.mkdir() && previous.mkdir() && child.createNewFile());
      try (RandomAccessFile file = new RandomAccessFile(current, "rw")) {
        file.setLength(DiagnosticExport.EVENT_FILE_LIMIT - 16 * 1024);
      }
      Context isolated =
          new ContextWrapper(context) {
            @Override
            public File getFilesDir() {
              return root;
            }
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
    var output =
        new DiagnosticDestination.NewOutput<String>() {
          @Override
          public OutputStream open() {
            lifecycle.add("open");
            return new OutputStream() {
              @Override
              public void write(int value) throws IOException {
                lifecycle.add("write");
                if ("write".equals(failedStep)) throw new IOException("fixture");
              }

              @Override
              public void close() throws IOException {
                lifecycle.add("close");
                if ("close".equals(failedStep)) throw new IOException("fixture");
              }
            };
          }

          @Override
          public String publish() {
            lifecycle.add("publish");
            return "owned";
          }

          @Override
          public boolean delete() {
            lifecycle.add("delete");
            return !cleanupFails;
          }
        };
    try {
      require("owned".equals(DiagnosticDestination.writeNew(new byte[] {1}, output)));
      require(failedStep.isEmpty());
    } catch (IOException error) {
      require(
          !failedStep.isEmpty()
              && (error instanceof DiagnosticDestination.CleanupFailed) == cleanupFails);
    }
    require(
        lifecycle.equals(
            List.of("open", "write", "close", failedStep.isEmpty() ? "publish" : "delete")));
  }

  private static void write(File folder, String name, String value) throws Exception {
    Files.write(new File(folder, name).toPath(), value.getBytes(StandardCharsets.UTF_8));
  }

  private static JSONObject event(String name, int sequence) throws Exception {
    return new JSONObject()
        .put("event", name)
        .put("session", SESSION)
        .put("seq", sequence)
        .put("ns", sequence * 100)
        .put("request", 0)
        .put("hit", true)
        .put("file", PATH)
        .put("error", SECRET)
        .put("unknown", SECRET);
  }

  private static Map<String, String> unzip(byte[] bytes) throws Exception {
    Map<String, String> contents = new HashMap<>();
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        require(
            contents.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8))
                == null);
      }
    }
    return contents;
  }

  private static void require(boolean condition) {
    if (!condition) throw new AssertionError("Diagnostic export fixture check failed");
  }
}
