package jp.nano.rawgallery;

import android.content.Context;
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
  private static final ArrayBlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(2048);
  private static final ThreadLocal<Long> PARENT = ThreadLocal.withInitial(() -> 0L);
  private static final Object CONFIG = new Object();
  static final Object FILES = new Object();
  private static volatile boolean enabled;
  private static boolean writerStarted;
  private static boolean initialized;

  static synchronized void init(Context context) {
    if (initialized) return;
    initialized = true;
    boolean debug = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    enabled = settings(context).getBoolean("record", debug);
    if (enabled) startWriter(context);
  }

  static boolean recording() { return enabled; }

  static boolean setRecording(Context context, boolean record) {
    synchronized (CONFIG) {
      if (!settings(context).edit().putBoolean("record", record).commit()) return false;
      synchronized (DiagnosticLog.class) {
        boolean starting = record && !enabled;
        enabled = record;
        if (starting) startWriter(context);
      }
      return true;
    }
  }

  private static android.content.SharedPreferences settings(Context context) {
    return context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE);
  }

  /** The caller holds the class monitor to serialize starting and stopping capture. */
  private static void startWriter(Context context) {
    if (!writerStarted) {
      writerStarted = true;
      File dir = new File(context.getFilesDir(), "diagnostics");
      Thread writer = new Thread(() -> write(dir), "loupe-diagnostics");
      writer.setDaemon(true);
      writer.start();
    }
    long version = 0;
    try { version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode(); }
    catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
    event("session", 0, "version", version, "wall_ms", System.currentTimeMillis());
  }

  private static void write(File dir) {
    try (DiagnosticWriter sink = new DiagnosticWriter(dir, DiagnosticExport.EVENT_FILE_LIMIT)) {
      while (true) {
        String line = QUEUE.take();
        synchronized (FILES) {
          sink.write(line);
          sink.flush();
        }
      }
    } catch (Exception error) {
      synchronized (DiagnosticLog.class) {
        enabled = false;
        writerStarted = false;
        QUEUE.clear();
      }
      Log.e("LoupeDiagnostic", "writer_failed:" + error.getClass().getSimpleName());
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
    if (!enabled) return;
    try {
      JSONObject j = new JSONObject().put("session", SESSION).put("seq", id())
          .put("ns", SystemClock.elapsedRealtimeNanos()).put("event", event)
          .put("request", request).put("parent", parent())
          .put("thread", Thread.currentThread().getId()).put("dropped", DROPPED.get());
      for (int i = 0; i < fields.length; i += 2) j.put((String) fields[i], fields[i + 1]);
      synchronized (DiagnosticLog.class) {
        if (enabled && !QUEUE.offer(j.toString())) DROPPED.incrementAndGet();
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
