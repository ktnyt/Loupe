package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import jp.nano.rawgallery.camera.ImportLog;
import jp.nano.rawgallery.camera.MountedCameraCard;
import jp.nano.rawgallery.camera.SdSourceRegistrations;
import org.json.JSONObject;

/** Synthetic crashes, credentials and provider concurrency. Never starts an import or network call. */
final class DiagnosticFailureChecks {
  private static final String SENTINEL = "content://example/synthetic-private-photo.jpg";
  private static final String BUSY = "確認処理が混み合っています。少し待ってからやり直してください。";

  static String run(Instrumentation test) throws Exception {
    Context context = test.getTargetContext();
    crashAndPrivacy(context);
    credentialConcurrency(context);
    sourceConcurrency(test);
    checkPrivacy(context);
    return "PASS crash summary, delegation and storage error, log privacy, generated image without a path, "
        + "Nano Banana preferences and credential messages, SD/Wi-Fi source rejection and retry";
  }

  private static void crashAndPrivacy(Context context) throws Exception {
    require(Thread.getDefaultUncaughtExceptionHandler() instanceof DiagnosticCrashHandler, "crash handler installed");
    File directory = Files.createTempDirectory(context.getCacheDir().toPath(), "crash-summary").toFile();
    File file = new File(directory, "last-crash.json");
    File unavailable = new File(directory, "unavailable");
    require(unavailable.createNewFile(), "storage error fixture");
    Throwable cause = new IOException("private cause " + SENTINEL);
    Throwable failure = new IllegalStateException("private failure " + SENTINEL, cause);
    failure.setStackTrace(new StackTraceElement[] {
        new StackTraceElement("Fixture", "check", SENTINEL, 42),
        new StackTraceElement("Other", "discarded", "Other.java", 15)});
    Thread thread = Thread.currentThread();
    AtomicInteger delegates = new AtomicInteger();
    Thread.UncaughtExceptionHandler previous = (originalThread, error) -> {
      require(originalThread == thread && error == failure, "original arguments delegated");
      delegates.incrementAndGet();
    };
    try {
      new DiagnosticCrashHandler(directory, 777, previous).uncaughtException(thread, failure);
      new DiagnosticCrashHandler(unavailable, 888, previous).uncaughtException(thread, failure);
      require(delegates.get() == 2, "delegate even when note cannot be written");
      String content = read(file);
      JSONObject note = new JSONObject(content);
      JSONObject frame = note.getJSONObject("frame");
      require(note.length() == 3 && note.getInt("version_code") == 777
          && note.getString("exception").equals(IllegalStateException.class.getName()), "summary metadata");
      require(frame.length() == 3 && frame.getString("class").equals("Fixture")
          && frame.getString("method").equals("check") && frame.getInt("line") == 42, "top frame only");
      require(content.length() < 1024 && !content.contains(SENTINEL)
          && !content.contains("discarded") && !content.contains("private failure"), "no private crash detail");
    } finally {
      Files.deleteIfExists(file.toPath());
      Files.deleteIfExists(unavailable.toPath());
      Files.deleteIfExists(directory.toPath());
    }

    DiagnosticLog.exception("fixture.diagnostics", "failed", failure);
    ImportLog.INSTANCE.init(context);
    ImportLog.INSTANCE.w("ImportLogFixture", "fixture.import", failure);
  }

  private static void credentialConcurrency(Context context) throws Exception {
    String namespace = "nano-bananafailure-fixture";
    NanoBananaStore writer = new NanoBananaStore(context, namespace);
    NanoBananaStore reader = new NanoBananaStore(context, namespace);
    try {
      writer.setKey("synthetic-fixture-api-key");
      require("synthetic-fixture-api-key".equals(reader.apiKey()), "encrypted key readable from another store");
      writer.setE2("fixture-bucket", "A".repeat(20), "B".repeat(40));
      writer.job(new JSONObject().put("stage", "cleanup"));
      expectIo(() -> reader.setE2("another-bucket", "A".repeat(20), "B".repeat(40)), "バケットは変更できません");
      reader.setE2("fixture-bucket", "C".repeat(20), "D".repeat(40));
      expectIo(() -> reader.setE2("", "", ""), "バケット名、キー、シークレット");
      NanoBananaJob.BUSY.set(true);
      try {
        expectIo(() -> reader.setE2("fixture-bucket", "E".repeat(20), "F".repeat(40)), "実行が終わってから");
        expectIo(reader::deleteKey, "進行中の処理を終了");
      } finally {
        NanoBananaJob.BUSY.set(false);
      }

      CountDownLatch entering = new CountDownLatch(1), finished = new CountDownLatch(1);
      AtomicReference<Throwable> error = new AtomicReference<>();
      Thread blocked = new Thread(() -> {
        entering.countDown();
        try { reader.job(new JSONObject().put("stage", "pending")); }
        catch (Exception failure) { error.set(failure); }
        finally { finished.countDown(); }
      }, "store-contention-fixture");
      synchronized (NanoBananaStore.LOCK) {
        blocked.start();
        require(entering.await(3, TimeUnit.SECONDS), "job writer has started");
        require(!finished.await(100, TimeUnit.MILLISECONDS), "independent store uses shared lock");
        expectIo(reader::deleteE2, "進行中の処理を終了");
      }
      require(finished.await(3, TimeUnit.SECONDS) && error.get() == null
          && "pending".equals(writer.job().getString("stage")), "serialized write visible to both stores");
      writer.job(null);
      writer.deleteE2();
      expectIo(reader::e2, "IDrive e2の設定を読み込めません");
      writer.deleteKey();
      expectIo(reader::apiKey, "APIキーを登録し直してください");
    } finally {
      context.deleteSharedPreferences(namespace);
    }
  }

  private static void sourceConcurrency(Instrumentation test) throws Exception {
    Context context = test.getTargetContext();
    MainActivity gallery = (MainActivity) test.startActivitySync(new Intent(context, MainActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.GENERATED_IMAGE, "content://media"));
    test.runOnMainSync(gallery::finish);
    CameraImportActivity screen = (CameraImportActivity) test.startActivitySync(new Intent(context, CameraImportActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      DiagnosticChecks.waitFor(test, screen::hasWindowFocus);
      DiagnosticChecks.waitFor(test, () -> field(screen, "wifiCancellation") == null
          && field(screen, "sdStatusCancellation") == null);
      Executor requests = (Executor) field(null, "SOURCE_IO");
      Executor status = (Executor) field(null, "SOURCE_STATUS_IO");
      CountDownLatch entered = new CountDownLatch(4), release = new CountDownLatch(1);
      Runnable hold = () -> {
        entered.countDown();
        try { release.await(10, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      };
      try {
        for (int i = 0; i < 2; i++) { requests.execute(hold); status.execute(hold); }
        require(entered.await(3, TimeUnit.SECONDS), "both executors saturated");
        Method discover = CameraImportActivity.class.getDeclaredMethod("discoverQuickSource", boolean.class);
        Method statusRefresh = CameraImportActivity.class.getDeclaredMethod("refreshSdSource");
        Method loadWifi = CameraImportActivity.class.getDeclaredMethod("loadWifiProfiles");
        Method verify = CameraImportActivity.class.getDeclaredMethod("verifySdSourceAsync",
            CameraImportActivity.SdStart.class, CameraImportActivity.SdStartMode.class, java.util.function.Consumer.class);
        CameraImportActivity.SdStart picked = new CameraImportActivity.SdStart(
            Uri.parse("content://com.android.externalstorage.documents/tree/ABCD-1234%3ADCIM"),
            null, PhotoStorage.selected(context), "JPEG", MountedCameraCard.detachGeneration(), true,
            SdSourceRegistrations.latest(context));
        AtomicInteger verified = new AtomicInteger();
        java.util.function.Consumer<CameraImportActivity.SdLocation> callback = ignored -> verified.incrementAndGet();
        test.runOnMainSync(() -> {
          invoke(discover, screen, true);
          require(BUSY.equals(field(screen, "connectionFailure"))
              && field(screen, "sourceCancellation") == null && !((boolean) field(screen, "starting")), "lookup rejected visibly");
          invoke(verify, screen, picked, null, callback);
          require(BUSY.equals(field(screen, "connectionFailure")) && field(screen, "sourceCancellation") == null,
              "verification rejected visibly");
          invoke(statusRefresh, screen);
          require(BUSY.contentEquals(((TextView) field(screen, "sdStatus")).getText())
              && field(screen, "sdStatusCancellation") == null, "SD status busy");
          screen.wifiProfileLoader = (owner, cancellation) -> List.of();
          invoke(loadWifi, screen);
          require(BUSY.contentEquals(((TextView) field(screen, "wifiStatus")).getText())
              && field(screen, "wifiCancellation") == null, "Wi-Fi status busy");
        });
        test.waitForIdleSync();
        var screenshot = test.getUiAutomation().takeScreenshot();
        require(screenshot != null, "busy screenshot");
        try (var output = new FileOutputStream(new File(context.getCacheDir(), "diagnostics-source-busy.png"))) {
          screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } finally { screenshot.recycle(); }

        release.countDown();
        // A new user request is necessary: releasing an executor never replays a rejected import.
        SystemClock.sleep(150);
        require(verified.get() == 0, "no automatic replay");
        test.runOnMainSync(() -> {
          screen.sdSourceProbe = (request, cancellation) -> new CameraImportActivity.SdLocation("fixture", "fixture");
          invoke(verify, screen, picked, null, callback);
          invoke(loadWifi, screen);
        });
        DiagnosticChecks.waitFor(test, () -> verified.get() == 1 && field(screen, "wifiCancellation") == null
            && ((TextView) field(screen, "wifiStatus")).getText().toString().contains("QR"));
        test.runOnMainSync(() -> {
          screen.wifiProfileLoader = (owner, cancellation) -> { throw new IOException(SENTINEL); };
          invoke(loadWifi, screen);
        });
        DiagnosticChecks.waitFor(test, () -> "登録カメラを確認できませんでした。"
            .contentEquals(((TextView) field(screen, "wifiStatus")).getText()));
      } finally {
        release.countDown();
      }
    } finally {
      test.runOnMainSync(screen::finish);
    }
  }

  private static void checkPrivacy(Context context) throws Exception {
    File eventFile = new File(context.getFilesDir(), "diagnostics/events.jsonl");
    long deadline = SystemClock.elapsedRealtime() + 2500;
    String log;
    do {
      log = read(eventFile);
      if (log.contains("\"phase\":\"camera.wifi_profiles\",\"outcome\":\"failed\"")) break;
      SystemClock.sleep(50);
    } while (SystemClock.elapsedRealtime() < deadline);
    for (String phase : List.of("fixture.diagnostics", "camera.source_lookup", "camera.sd_verify", "camera.sd_status",
        "camera.wifi_profiles")) {
      require(log.contains("\"phase\":\"" + phase + "\""), "exception event missing: " + phase);
    }
    String usbLog = read(ImportLog.INSTANCE.currentFile());
    require(usbLog.contains("fixture.import | java.lang.IllegalStateException"), "USB exception type");
    require(!log.contains(SENTINEL) && !usbLog.contains(SENTINEL), "private exception text omitted");
  }

  private interface IoAction { void run() throws IOException; }

  private static String read(File file) throws IOException {
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }

  private static void expectIo(IoAction action, String message) throws IOException {
    try { action.run(); }
    catch (IOException error) {
      require(error.getMessage().contains(message), "credential error message");
      return;
    }
    throw new AssertionError("expected credential error");
  }

  private static Object field(CameraImportActivity activity, String name) {
    try {
      Field field = CameraImportActivity.class.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(activity);
    } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
  }

  private static void invoke(Method method, CameraImportActivity activity, Object... args) {
    try { method.setAccessible(true); method.invoke(activity, args); }
    catch (ReflectiveOperationException error) { throw new AssertionError(error); }
  }

  private static void require(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }
}
