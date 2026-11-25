package jp.nano.rawgallery;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import android.util.Log;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Bounded, local diagnostics. Callers pass fixed labels/numbers, never user strings or paths. */
final class DiagnosticLog {
  private static final String SESSION = UUID.randomUUID().toString();
  private static final AtomicLong IDS = new AtomicLong(), DROPPED = new AtomicLong();
  private static final ThreadLocal<Long> PARENT = ThreadLocal.withInitial(() -> 0L);
  private static final Object CONFIG = new Object();
  static final Object FILES = new Object();
  private static volatile Recorder recorder;
  private static Object latestRecorderToken;
  private static boolean initialized;

  static void init(Context context) {
    synchronized (CONFIG) {
      if (initialized) return;
      initialized = true;
      boolean debug = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
      SharedPreferences prefs = settings(context);
      if (prefs.getBoolean("record", debug) && !start(context, prefs, false) && prefs.contains("record")) {
        remember(prefs, false);
      }
    }
  }

  static Recorder currentRecorder() { return recorder; }

  static boolean startRecording(Context context) {
    synchronized (CONFIG) {
      SharedPreferences prefs = settings(context);
      if (recorder != null || start(context, prefs, true)) return true;
      if (prefs.getBoolean("record", false)) remember(prefs, false);
      return false;
    }
  }

  static boolean stopRecording(Context context, Recorder requested) {
    synchronized (CONFIG) {
      // A dialog can outlive its recorder, but it cannot stop a newer one.
      if (latestRecorderToken != requested.token) return false;
      if (!remember(settings(context), false)) return false;
      synchronized (DiagnosticLog.class) {
        if (recorder == requested) {
          recorder = null;
          requested.queue.add(Recorder.STOP);
        }
      }
      // A failed recorder detaches itself. Its final result still belongs to this request.
      return requested.awaitStop();
    }
  }

  private static SharedPreferences settings(Context context) {
    return context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE);
  }

  private static boolean remember(SharedPreferences prefs, boolean record) {
    boolean hadSetting = prefs.contains("record");
    boolean previous = prefs.getBoolean("record", false);
    if (prefs.edit().putBoolean("record", record).commit()) return true;
    // commit() updates the in-memory value even when writing to disk fails.
    SharedPreferences.Editor restore = prefs.edit();
    if (hadSetting) restore.putBoolean("record", previous);
    else restore.remove("record");
    restore.apply();
    return false;
  }

  // The configuration lock serializes open, preference updates and draining the previous recorder.
  private static boolean start(Context context, SharedPreferences prefs, boolean persist) {
    DiagnosticWriter sink;
    try {
      synchronized (FILES) {
        sink = new DiagnosticWriter(new File(context.getFilesDir(), "diagnostics"),
            DiagnosticExport.EVENT_FILE_LIMIT);
      }
    } catch (IOException | SecurityException error) {
      logFailure(error);
      return false;
    }
    if (persist && !remember(prefs, true)) {
      try { sink.close(); } catch (IOException error) { logFailure(error); }
      return false;
    }

    Recorder started = new Recorder(sink);
    started.thread.start();
    latestRecorderToken = started.token;
    recorder = started;
    long version = 0;
    try { version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode(); }
    catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
    event("session", 0, "version", version, "wall_ms", System.currentTimeMillis());
    return true;
  }

  private static void logFailure(Exception error) {
    Log.e("LoupeDiagnostic", "writer_failed:" + error.getClass().getSimpleName());
  }

  static final class Recorder {
    private static final String STOP = new String();
    // The last slot is reserved for STOP, even when the event queue is full.
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(2049);
    private final Thread thread;
    private final Object token = new Object();
    private boolean failed;

    Recorder(DiagnosticWriter sink) {
      thread = new Thread(() -> write(sink), "loupe-diagnostics");
      thread.setDaemon(true);
    }

    private void write(DiagnosticWriter sink) {
      try (sink) {
        while (true) {
          String line = queue.take();
          if (line == STOP) return;
          synchronized (FILES) {
            sink.write(line);
            sink.flush();
          }
        }
      } catch (Exception error) {
        failed = true;
        synchronized (DiagnosticLog.class) {
          if (recorder == this) recorder = null;
          queue.clear();
        }
        logFailure(error);
      }
    }

    boolean awaitStop() {
      boolean interrupted = false;
      try {
        while (true) {
          try { thread.join(); return !failed; }
          catch (InterruptedException ignored) { interrupted = true; }
        }
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }
  }

  static long parent() { return PARENT.get(); }
  static long use(long id) { long old = parent(); PARENT.set(id); return old; }
  static long id() { return IDS.incrementAndGet(); }
  static long photo(PhotoRepository.Photo p) {
    // Session-local pseudonym, never persist the URI or a stable cross-session identifier.
    try {
      byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
          .digest((SESSION + p.uri.toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.nio.ByteBuffer.wrap(bytes).getLong() & Long.MAX_VALUE;
    } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }
  static void event(String event, long request, Object... fields) {
    Recorder target = recorder;
    if (target == null) return;
    try {
      JSONObject j = new JSONObject().put("session", SESSION).put("seq", id())
          .put("ns", SystemClock.elapsedRealtimeNanos()).put("event", event)
          .put("request", request).put("parent", parent())
          .put("thread", Thread.currentThread().getId()).put("dropped", DROPPED.get());
      for (int i = 0; i < fields.length; i += 2) j.put((String) fields[i], fields[i + 1]);
      String line = j.toString();
      synchronized (DiagnosticLog.class) {
        if (recorder == target && (target.queue.remainingCapacity() <= 1 || !target.queue.offer(line))) {
          DROPPED.incrementAndGet();
        }
      }
    } catch (Exception e) { DROPPED.incrementAndGet(); }
  }
  /** Pass only fixed phase/outcome labels. Never record exception text or stack traces here. */
  static void exception(String phase, String outcome, Throwable error) {
    event("operation.exception", 0, "phase", phase, "outcome", outcome,
        "exception", error.getClass().getName());
  }
  static Scope span(String name) { return new Scope(name); }
  static final class Scope implements AutoCloseable {
    final String name;
    final long id = id(), previous;
    Scope(String name) {
      this.name = name;
      event(name + ".start", id);
      previous = use(id);
    }
    public void close() {
      event(name + ".end", id);
      use(previous);
    }
  }
}
