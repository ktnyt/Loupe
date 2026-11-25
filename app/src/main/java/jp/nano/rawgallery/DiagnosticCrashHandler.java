package jp.nano.rawgallery;

import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Keeps one app-private summary independent of the asynchronous diagnostic queue. */
final class DiagnosticCrashHandler implements Thread.UncaughtExceptionHandler {
  private final File directory;
  private final int versionCode;
  private final Thread.UncaughtExceptionHandler previous;
  private final AtomicBoolean recorded = new AtomicBoolean();

  DiagnosticCrashHandler(File directory, int versionCode, Thread.UncaughtExceptionHandler previous) {
    this.directory = directory;
    this.versionCode = versionCode;
    this.previous = previous;
  }

  @Override public void uncaughtException(Thread thread, Throwable failure) {
    try {
      if (recorded.compareAndSet(false, true)) save(failure);
    } catch (Throwable writeFailure) {
      Log.e("LoupeDiagnostic", "crash_summary_failed:" + writeFailure.getClass().getSimpleName());
    } finally {
      if (previous != null) previous.uncaughtException(thread, failure);
    }
  }

  private void save(Throwable failure) throws Exception {
    if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
      throw new IOException("diagnostics directory");
    JSONObject summary = new JSONObject().put("version_code", versionCode)
        .put("exception", codeLabel(failure.getClass().getName()));
    StackTraceElement[] stack = failure.getStackTrace();
    if (stack.length > 0) {
      StackTraceElement first = stack[0];
      summary.put("frame", new JSONObject()
          .put("class", codeLabel(first.getClassName()))
          .put("method", codeLabel(first.getMethodName()))
          .put("line", first.getLineNumber()));
    }
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

  private static String codeLabel(String value) {
    return value.length() <= 240 && value.matches("[A-Za-z0-9_.$<>-]+") ? value : "unknown";
  }
}
