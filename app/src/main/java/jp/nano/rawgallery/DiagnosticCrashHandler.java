package jp.nano.rawgallery;

import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Keeps one app-private summary independent of the asynchronous diagnostic queue. */
final class DiagnosticCrashHandler implements Thread.UncaughtExceptionHandler {
  static final int MAX_FRAMES = 8;
  static final int MAX_CAUSES = 8;
  static final int MAX_CODE_LABEL_LENGTH = 240;
  static final String APP_CLASS_PREFIX = "jp.nano.rawgallery.";
  // A malformed or generated stack can contain thousands of frames.
  static final int MAX_SCANNED_FRAMES = 1024;

  private final File directory;
  private final int versionCode;
  private final Thread.UncaughtExceptionHandler previous;
  private final Object saveLock = new Object();
  private boolean recorded;

  DiagnosticCrashHandler(
      File directory, int versionCode, Thread.UncaughtExceptionHandler previous) {
    this.directory = directory;
    this.versionCode = versionCode;
    this.previous = previous;
  }

  @Override
  public void uncaughtException(Thread thread, Throwable failure) {
    try {
      // The previous handler may end the process, so every crashing thread waits for this write.
      synchronized (saveLock) {
        if (!recorded) {
          recorded = true;
          save(failure);
        }
      }
    } catch (Throwable writeFailure) {
      Log.e("LoupeDiagnostic", "crash_summary_failed:" + writeFailure.getClass().getSimpleName());
    } finally {
      if (previous != null) previous.uncaughtException(thread, failure);
    }
  }

  private void save(Throwable failure) throws Exception {
    if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
      throw new IOException("diagnostics directory");
    JSONObject summary =
        new JSONObject()
            .put("version_code", versionCode)
            .put("exception", codeLabel(failure.getClass().getName()));
    StackTraceElement[] stack = failure.getStackTrace();
    JSONArray frames = new JSONArray();
    for (int index = 0; index < Math.min(stack.length, MAX_FRAMES); index++) {
      StackTraceElement candidate = stack[index];
      if (candidate == null) continue;
      JSONObject entry = frame(candidate);
      if (index == 0) summary.put("frame", entry);
      frames.put(entry);
    }
    summary.put("frames", frames);
    // An absent app_frame means none was found within the scan limit.
    for (int index = 0; index < Math.min(stack.length, MAX_SCANNED_FRAMES); index++) {
      StackTraceElement candidate = stack[index];
      if (candidate != null && candidate.getClassName().startsWith(APP_CLASS_PREFIX)) {
        summary.put("app_frame", frame(candidate));
        break;
      }
    }
    JSONArray causes = new JSONArray();
    IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
    seen.put(failure, Boolean.TRUE);
    Throwable cause = failure;
    while (causes.length() < MAX_CAUSES) {
      try {
        cause = cause.getCause();
      } catch (RuntimeException ignored) {
        // A custom cause accessor can fail. Keep the crash details already collected.
        break;
      }
      if (cause == null || seen.put(cause, Boolean.TRUE) != null) break;
      causes.put(codeLabel(cause.getClass().getName()));
    }
    summary.put("causes", causes);
    AtomicFile lastCrash = new AtomicFile(new File(directory, "last-crash.json"));
    FileOutputStream output = lastCrash.startWrite();
    try {
      output.write(summary.toString().getBytes(StandardCharsets.UTF_8));
      lastCrash.finishWrite(output);
    } catch (Throwable writeFailure) {
      lastCrash.failWrite(output);
      throw writeFailure;
    }
  }

  private static JSONObject frame(StackTraceElement value) throws JSONException {
    return new JSONObject()
        .put("class", codeLabel(value.getClassName()))
        .put("method", codeLabel(value.getMethodName()))
        .put("line", value.getLineNumber());
  }

  private static String codeLabel(String value) {
    return value.length() <= MAX_CODE_LABEL_LENGTH && value.matches("[A-Za-z0-9_.$<>-]+")
        ? value
        : "unknown";
  }
}
