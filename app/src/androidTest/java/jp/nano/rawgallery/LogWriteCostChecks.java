package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded manual measurement of the actual DiagnosticLog in an idle instrumentation process. */
final class LogWriteCostChecks {
  private static final String EVENT = "video.prepared";
  private static final String PAYLOAD = "x".repeat(128);
  private static final String SEED = "{\"pad\":\"" + "x".repeat(1013) + "\"}";
  private static final int SEED_LINES = (DiagnosticExport.EVENT_FILE_LIMIT - 2048) / 1024;
  private static final long VISIBILITY_TIMEOUT_MS = 5000;
  private static final int VISIBILITY_POLL_MS = 1;
  private static final List<Scenario> SCENARIOS =
      List.of(
          new Scenario("off", false, 32, 0, false),
          new Scenario("sparse", true, 8, 20, false),
          new Scenario("burst", true, 32, 0, false),
          new Scenario("rotation", true, 32, 0, true));

  private record Scenario(String name, boolean enabled, int count, int gapMs, boolean rotation) {}

  private LogWriteCostChecks() {}

  static String run(Instrumentation test, Bundle args) throws Exception {
    Context context = test.getTargetContext();
    String source = args.getString("source-commit", "");
    require("true".equals(args.getString("disposable-avd")), "dedicated offline AVD required");
    require("ranchu".equals(Build.HARDWARE), "Android emulator required");
    require(
        (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0,
        "non-debuggable target required");
    require(source.matches("[a-f0-9]{40}"), "source-commit must be a full commit ID");
    require(DiagnosticLog.currentRecorder() == null, "existing recorder must be absent");

    var prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE);
    var previousSettings = prefs.getAll();
    var apk = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
    publish(
        test,
        "log_write_environment",
        new JSONObject()
            .put("source_commit_from_host", source)
            .put("version_name", apk.versionName)
            .put("version_code", apk.getLongVersionCode())
            .put("api", Build.VERSION.SDK_INT)
            .put("fingerprint", Build.FINGERPRINT)
            .put("abi", Build.SUPPORTED_ABIS[0])
            .put("payload_ascii_bytes", PAYLOAD.length())
            .put("rotation_seed_bytes", SEED_LINES * 1024)
            .put("poll_interval_ms", VISIBILITY_POLL_MS)
            .put("visibility_timeout_ms", VISIBILITY_TIMEOUT_MS)
            .put("producer_threads", 1));
    boolean recorderAbsent;
    boolean settingsUnchanged;
    try {
      // Round zero is a warmup for every scenario. Rotate order deterministically each round.
      for (int round = 0; round <= 3; round++) {
        for (int position = 0; position < SCENARIOS.size(); position++) {
          Scenario scenario = SCENARIOS.get((position + round) % SCENARIOS.size());
          measure(test, context, scenario, round, position);
        }
      }
    } finally {
      recorderAbsent = DiagnosticLog.currentRecorder() == null;
      settingsUnchanged = previousSettings.equals(prefs.getAll());
      publish(
          test,
          "log_write_restore",
          new JSONObject()
              .put("current_recorder_absent", recorderAbsent)
              .put("global_settings_unchanged", settingsUnchanged)
              .put("process_local_counters_and_ended_token_reset", false));
    }
    require(recorderAbsent && settingsUnchanged, "global diagnostic state changed");
    return "PASS DiagnosticLog measurements (4 warmups, 12 measured trials, synthetic only)";
  }

  private static void measure(
      Instrumentation test, Context context, Scenario scenario, int round, int position)
      throws Exception {
    boolean probe = scenario.enabled() && round != 2;
    var receipt =
        new JSONObject()
            .put("scenario", scenario.name())
            .put("round", round)
            .put("position", position)
            .put("warmup", round == 0)
            .put("diagnostics_on", scenario.enabled())
            .put("events_planned", scenario.count())
            .put("requested_gap_ms", scenario.gapMs())
            .put("probe_visibility_before_stop", probe);
    Trial trial = null;
    long[] calls = new long[scenario.count()];
    long[] starts = new long[scenario.count()];
    int submittedCount = 0;
    try {
      try (var owned = new Trial(context)) {
        trial = owned;
        if (scenario.rotation()) owned.seedRotation();
        if (scenario.enabled()) owned.start();
        long start = SystemClock.elapsedRealtimeNanos();
        for (int i = 0; i < scenario.count(); i++) {
          if (i > 0 && scenario.gapMs() > 0) Thread.sleep(scenario.gapMs());
          long before = SystemClock.elapsedRealtimeNanos();
          DiagnosticLog.event(EVENT, 0, "fixture_payload", PAYLOAD, "fixture_index", i);
          calls[i] = SystemClock.elapsedRealtimeNanos() - before;
          starts[i] = before - start;
          submittedCount++;
        }
        int last = scenario.count() - 1;
        long submitted = start + starts[last] + calls[last];

        if (probe) {
          byte[] marker = ("\"fixture_index\":" + last + "}\n").getBytes(StandardCharsets.UTF_8);
          long visible = awaitTail(owned.current, marker);
          receipt.put("last_return_to_tail_observed_ns", visible - submitted);
        }
        if (scenario.enabled()) {
          long before = SystemClock.elapsedRealtimeNanos();
          owned.stop();
          long stopped = SystemClock.elapsedRealtimeNanos();
          receipt
              .put("stop_ns", stopped - before)
              .put("last_return_to_stop_ns", stopped - submitted);
        }
        verifyReadback(owned, scenario, receipt);
      }
      receipt.put("outcome", "pass");
    } catch (Exception | AssertionError failure) {
      receipt.put("outcome", "fail").put("failure_class", failure.getClass().getSimpleName());
      if (failure instanceof AssertionError) receipt.put("failed_check", failure.getMessage());
      throw failure;
    } finally {
      var elapsed = new JSONArray();
      var offsets = new JSONArray();
      for (int i = 0; i < submittedCount; i++) {
        elapsed.put(calls[i]);
        offsets.put(starts[i]);
      }
      receipt
          .put("calls_returned", submittedCount)
          .put("call_elapsed_ns", elapsed)
          .put("call_start_offset_ns", offsets);
      if (trial != null) {
        receipt.put("owned_preferences_removed", trial.prefsRemoved);
        receipt.put("owned_files_removed", trial.filesRemoved);
      }
      publish(test, "log_write_trial", receipt);
    }
  }

  private static long awaitTail(File current, byte[] marker) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + VISIBILITY_TIMEOUT_MS;
    byte[] tail = new byte[marker.length];
    while (SystemClock.elapsedRealtime() < deadline) {
      // Match the export reader's lock so rotation and flush are complete within a snapshot.
      synchronized (DiagnosticLog.FILES) {
        if (current.isFile()) {
          try (var file = new RandomAccessFile(current, "r")) {
            if (file.length() >= marker.length) {
              file.seek(file.length() - marker.length);
              file.readFully(tail);
              if (java.util.Arrays.equals(tail, marker)) {
                long observed = SystemClock.elapsedRealtimeNanos();
                if (observed / 1_000_000L < deadline) return observed;
              }
            }
          }
        }
      }
      Thread.sleep(VISIBILITY_POLL_MS);
    }
    throw new AssertionError("last accepted row was not readable before the observation deadline");
  }

  private static void verifyReadback(Trial trial, Scenario scenario, JSONObject receipt)
      throws Exception {
    int files = 0;
    int seeds = 0;
    int seen = 0;
    int sessions = 0;
    long startDropped = -1;
    long lastDropped = -1;
    long maxDropped = -1;
    long previousSeq = -1;
    for (File file : List.of(trial.previous, trial.current)) {
      if (!file.exists()) continue;
      files++;
      long bytes = file.length();
      receipt.put(file == trial.current ? "current_bytes" : "previous_bytes", bytes);
      require(bytes > 0 && bytes <= DiagnosticExport.EVENT_FILE_LIMIT, "log file size exceeded");
      try (var input = new RandomAccessFile(file, "r")) {
        input.seek(bytes - 1);
        require(input.read() == '\n', "incomplete final JSONL line");
      }
      try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (scenario.rotation() && file == trial.previous && seeds < SEED_LINES) {
            require(SEED.equals(line), "rotation seed changed");
            seeds++;
            continue;
          }
          JSONObject event = new JSONObject(line);
          long seq = event.getLong("seq");
          require(seq > previousSeq, "recorder sequence out of order");
          previousSeq = seq;
          lastDropped = event.getLong("dropped");
          maxDropped = Math.max(maxDropped, lastDropped);
          if ("session".equals(event.getString("event"))) {
            require(sessions == 0 && seen == 0, "unexpected session row");
            sessions++;
            startDropped = lastDropped;
          } else {
            require(sessions == 1 && EVENT.equals(event.getString("event")), "unexpected event");
            require(event.getInt("fixture_index") == seen, "fixture event missing or out of order");
            require(PAYLOAD.equals(event.getString("fixture_payload")), "fixture payload changed");
            seen++;
          }
        }
      }
    }
    receipt
        .put("files", files)
        .put("rotated", trial.previous.isFile())
        .put("seed_rows_read", seeds)
        .put("session_rows_read", sessions)
        .put("fixture_rows_read", seen);
    if (scenario.enabled()) {
      receipt
          .put("dropped_counter_start", startDropped)
          .put("dropped_counter_last", lastDropped)
          .put("observed_dropped_delta", maxDropped - startDropped);
      require(sessions == 1 && seen == scenario.count(), "accepted rows missing after stop");
      require(
          startDropped >= 0 && startDropped == maxDropped, "drop counter changed within the trial");
      require(
          files == (scenario.rotation() ? 2 : 1) && trial.previous.isFile() == scenario.rotation(),
          "unexpected rotation outcome");
      require(seeds == (scenario.rotation() ? SEED_LINES : 0), "rotated seed rows missing");
    } else {
      require(files == 0 && sessions == 0 && seen == 0, "diagnostics off wrote a file");
    }
  }

  private static final class Trial extends ContextWrapper implements AutoCloseable {
    private final File root;
    private final File folder;
    final File current;
    final File previous;
    private final String prefsName;
    private DiagnosticLog.Recorder recorder;
    boolean prefsRemoved;
    boolean filesRemoved;

    Trial(Context context) throws IOException {
      super(context);
      root = Files.createTempDirectory(context.getCacheDir().toPath(), "log-write-trial-").toFile();
      folder = new File(root, "diagnostics");
      current = new File(folder, "events.jsonl");
      previous = new File(folder, "previous.jsonl");
      prefsName = root.getName();
    }

    @Override
    public File getFilesDir() {
      return root;
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
      require("diagnostics".equals(name), "unexpected fixture preferences");
      return super.getSharedPreferences(prefsName, mode);
    }

    void seedRotation() throws IOException {
      Files.createDirectory(folder.toPath());
      byte[] line = (SEED + "\n").getBytes(StandardCharsets.UTF_8);
      try (var output = Files.newOutputStream(current.toPath())) {
        for (int i = 0; i < SEED_LINES; i++) output.write(line);
      }
    }

    void start() {
      require(DiagnosticLog.currentRecorder() == null, "recorder appeared before fixture start");
      boolean started = DiagnosticLog.startRecording(this);
      recorder = DiagnosticLog.currentRecorder();
      require(started && recorder != null, "fixture recorder did not start");
    }

    void stop() {
      require(
          recorder != null && DiagnosticLog.stopRecording(this, recorder), "fixture stop failed");
      require(DiagnosticLog.currentRecorder() == null, "fixture recorder still active after stop");
    }

    @Override
    public void close() throws IOException {
      if (recorder != null && DiagnosticLog.currentRecorder() == recorder) stop();
      require(
          DiagnosticLog.currentRecorder() == null, "fixture recorder still active during cleanup");
      try {
        for (File file : List.of(current, previous, folder, root)) {
          Files.deleteIfExists(file.toPath());
        }
        filesRemoved = true;
      } finally {
        prefsRemoved = super.deleteSharedPreferences(prefsName);
      }
      require(prefsRemoved, "could not remove fixture preferences");
    }
  }

  private static void publish(Instrumentation test, String key, JSONObject receipt) {
    Bundle status = new Bundle();
    status.putString(key, receipt.toString());
    test.sendStatus(0, status);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
