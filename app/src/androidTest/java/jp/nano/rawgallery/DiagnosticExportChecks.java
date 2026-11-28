package jp.nano.rawgallery;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
    checkRankingCleanupFailures();
    checkRankingPersistence(context);
    checkFailedRecording(context);
    checkOutput("write", false);
    checkOutput("close", false);
    checkOutput("close", true);
    checkOutput("", false);
    return "PASS non-debuggable diagnostics, failed open, stop tail, E2 phases, legacy and expanded"
        + " crash redaction, ZIP output failures, failed recorder identity, and system-only"
        + " picker, ranking persistence failures";
  }

  private static void checkRankingPersistence(Context context) throws Exception {
    File root =
        Files.createTempDirectory(context.getCacheDir().toPath(), "ranking-diagnostic-fixture")
            .toFile();
    String prefsName = root.getName();
    Context isolated =
        new ContextWrapper(context) {
          @Override
          public File getFilesDir() {
            return root;
          }

          @Override
          public SharedPreferences getSharedPreferences(String name, int mode) {
            require("diagnostics".equals(name));
            return context.getSharedPreferences(prefsName, mode);
          }
        };
    var recording = new RankingFixtureRecording(isolated);
    File folder = new File(root, "diagnostics");
    File current = new File(folder, "events.jsonl");
    runRankingFixture(
        () -> assertRankingPersistence(isolated, current, recording),
        () -> {
          recording.finish();
          runRankingFixture(
              () -> deleteRankingFixturePreferences(context, prefsName),
              () -> {
                for (File file :
                    List.of(current, new File(folder, "previous.jsonl"), folder, root)) {
                  Files.deleteIfExists(file.toPath());
                }
              });
        });
  }

  private static void assertRankingPersistence(
      Context isolated, File current, RankingFixtureRecording recording) throws Exception {
    String key = "synthetic-ranking-key-do-not-export";
    var commits = new ArrayDeque<Object>();
    RankingStore store = new RankingStore(rankingPreferences(commits));
    require(DiagnosticLog.currentRecorder() == null);
    require(store.awaitReady().phase() == RankingStore.Phase.READY);
    commits.add(false);
    require(rankingOutcome(store, key, false, true) == RankingStore.Outcome.WRITE_FAILED);
    require(DiagnosticExport.capture(isolated).length == 0);

    recording.start();
    require(rankingOutcome(store, "success", false, true) == RankingStore.Outcome.SAVED);
    require(rankingOutcome(store, "success", true, true) == RankingStore.Outcome.UNCHANGED);
    require(rankingOutcome(store, "success", false, true) == RankingStore.Outcome.CONFLICT);
    commits.addAll(List.of(false, false));
    require(rankingOutcome(store, key, false, true) == RankingStore.Outcome.REPAIR_REQUIRED);
    commits.add(new SecurityException(SECRET + PATH));
    require(rankingOutcome(store, "blocked", false, true) == RankingStore.Outcome.REPAIR_REQUIRED);
    require(rankingOutcome(store, "blocked", false, true) == RankingStore.Outcome.SAVED);
    commits.add(new IllegalStateException(SECRET + PATH, new IOException(PATH)));
    require(rankingOutcome(store, key, false, true) == RankingStore.Outcome.WRITE_FAILED);
    require(!store.snapshot().singleBest(key) && store.snapshot().revision() == 2);
    require(commits.isEmpty());
    recording.finish();

    String raw = new String(Files.readAllBytes(current.toPath()), StandardCharsets.UTF_8);
    commits.add(false);
    require(rankingOutcome(store, key, false, true) == RankingStore.Outcome.WRITE_FAILED);
    require(raw.equals(new String(Files.readAllBytes(current.toPath()), StandardCharsets.UTF_8)));
    String safe = unzip(DiagnosticExport.capture(isolated)).get("events.jsonl");
    List<List<String>> expected =
        List.of(
            List.of("ranking.persistence.failed", "ranking.commit", ""),
            List.of("ranking.persistence.failed", "ranking.restore", ""),
            List.of("operation.exception", "ranking.restore", "java.lang.SecurityException"),
            List.of("operation.exception", "ranking.commit", "java.lang.IllegalStateException"));
    require(rankingEvents(raw, key).equals(expected));
    require(rankingEvents(safe, key).equals(expected));
    String malformed =
        new JSONObject()
            .put("event", "operation.exception")
            .put("session", SESSION)
            .put("seq", 1)
            .put("ns", 1)
            .put("request", 0)
            .put("phase", "ranking.restore")
            .put("outcome", "failed")
            .put("exception", "java.lang.String")
            .put("path", PATH)
            .put("message", SECRET)
            .toString();
    String sanitized =
        unzip(
                DiagnosticExport.archive(
                    Map.of("events.jsonl", malformed.getBytes(StandardCharsets.UTF_8))))
            .get("events.jsonl");
    require(
        rankingEvents(sanitized, key)
            .equals(List.of(List.of("operation.exception", "ranking.restore", ""))));
  }

  private static final class RankingFixtureRecording {
    private final Context context;
    private DiagnosticLog.Recorder recorder;
    private boolean mayHaveStarted;

    RankingFixtureRecording(Context context) {
      this.context = context;
    }

    void start() {
      require(DiagnosticLog.currentRecorder() == null);
      mayHaveStarted = true;
      boolean started;
      try {
        started = DiagnosticLog.startRecording(context);
      } finally {
        recorder = DiagnosticLog.currentRecorder();
      }
      if (!started && recorder == null) mayHaveStarted = false;
      require(started && recorder != null);
    }

    void finish() throws IOException {
      if (!mayHaveStarted) return;
      if (recorder == null
          || !DiagnosticLog.stopRecording(context, recorder)
          || DiagnosticLog.currentRecorder() != null) {
        throw new IOException("Could not confirm ranking fixture recorder stopped");
      }
      mayHaveStarted = false;
      recorder = null;
    }
  }

  private static void deleteRankingFixturePreferences(Context context, String name)
      throws IOException {
    File directory = new File(context.getDataDir(), "shared_prefs");
    File file = new File(directory, name + ".xml");
    File backup = new File(directory, name + ".xml.bak");
    if (rankingPreferencesAbsent(file, backup)) return;
    if (!context.deleteSharedPreferences(name) || !rankingPreferencesAbsent(file, backup)) {
      throw new IOException("Could not delete owned ranking fixture preferences");
    }
  }

  private static boolean rankingPreferencesAbsent(File file, File backup) {
    return Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        && Files.notExists(backup.toPath(), LinkOption.NOFOLLOW_LINKS);
  }

  @FunctionalInterface
  private interface RankingFixtureStep {
    void run() throws Exception;
  }

  private static void runRankingFixture(RankingFixtureStep body, RankingFixtureStep cleanup)
      throws Exception {
    try {
      body.run();
    } catch (Exception | Error primary) {
      try {
        cleanup.run();
      } catch (Exception | Error secondary) {
        if (secondary != primary) primary.addSuppressed(secondary);
      }
      throw primary;
    }
    cleanup.run();
  }

  private static void checkRankingCleanupFailures() throws Exception {
    var bodyFailure = new AssertionError("early ranking fixture failure");
    var cleanupFailure = new IOException("ranking fixture cleanup failure");
    try {
      runRankingFixture(
          () -> {
            throw bodyFailure;
          },
          () -> {
            throw cleanupFailure;
          });
      throw new AssertionError("body failure was lost");
    } catch (AssertionError failure) {
      require(failure == bodyFailure);
      require(Arrays.equals(failure.getSuppressed(), new Throwable[] {cleanupFailure}));
    }
    try {
      runRankingFixture(
          () -> {},
          () -> {
            throw cleanupFailure;
          });
      throw new AssertionError("cleanup failure was lost");
    } catch (IOException failure) {
      require(failure == cleanupFailure);
    }
  }

  private static List<List<String>> rankingEvents(String events, String key) throws Exception {
    require(
        events != null
            && !events.contains(SECRET)
            && !events.contains(PATH)
            && !events.contains(key)
            && !events.contains(PhotoRepository.digest(key)));
    var actual = new ArrayList<List<String>>();
    for (String line : events.split("\n")) {
      var item = new JSONObject(line);
      String name = item.getString("event"), phase = item.optString("phase");
      if (!name.equals("ranking.persistence.failed") && !phase.startsWith("ranking.")) continue;
      require(item.getLong("request") == 0 && item.getString("outcome").equals("failed"));
      for (var fields = item.keys(); fields.hasNext(); ) {
        require(
            Set.of(
                    "session",
                    "seq",
                    "ns",
                    "event",
                    "request",
                    "parent",
                    "thread",
                    "dropped",
                    "phase",
                    "outcome",
                    "exception")
                .contains(fields.next()));
      }
      if (name.equals("ranking.persistence.failed")) require(!item.has("exception"));
      actual.add(List.of(name, phase, item.optString("exception")));
    }
    return actual;
  }

  private static RankingStore.Outcome rankingOutcome(
      RankingStore store, String key, boolean previous, boolean next) throws Exception {
    return store
        .submitSingleBest(key, previous, next)
        .toCompletableFuture()
        .get(5, TimeUnit.SECONDS)
        .outcome();
  }

  private static SharedPreferences rankingPreferences(ArrayDeque<Object> commits) {
    Map<String, String> memory = new HashMap<>();
    return (SharedPreferences)
        Proxy.newProxyInstance(
            DiagnosticExportChecks.class.getClassLoader(),
            new Class<?>[] {SharedPreferences.class},
            (prefs, method, args) ->
                switch (method.getName()) {
                  case "getAll" -> new HashMap<>(memory);
                  case "edit" -> rankingEditor(memory, commits);
                  default -> throw new AssertionError("Unexpected ranking preferences call");
                });
  }

  private static SharedPreferences.Editor rankingEditor(
      Map<String, String> memory, ArrayDeque<Object> commits) {
    Map<String, String> changes = new HashMap<>();
    return (SharedPreferences.Editor)
        Proxy.newProxyInstance(
            DiagnosticExportChecks.class.getClassLoader(),
            new Class<?>[] {SharedPreferences.Editor.class},
            (editor, method, args) -> {
              switch (method.getName()) {
                case "putString":
                  changes.put((String) args[0], (String) args[1]);
                  return editor;
                case "remove":
                  changes.put((String) args[0], null);
                  return editor;
                case "commit":
                  changes.forEach(
                      (key, value) -> {
                        if (value == null) memory.remove(key);
                        else memory.put(key, value);
                      });
                  Object result = commits.poll();
                  if (result instanceof RuntimeException failure) throw failure;
                  return result == null || (boolean) result;
                default:
                  throw new AssertionError("Unexpected ranking editor call");
              }
            });
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
