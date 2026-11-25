package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import jp.nano.rawgallery.camera.ImportLog;
import jp.nano.rawgallery.camera.MountedCameraCard;
import jp.nano.rawgallery.camera.SdSourceRegistrations;
import org.json.JSONObject;

/**
 * Synthetic crashes, credentials and provider concurrency. Never starts an import or network call.
 */
final class DiagnosticFailureChecks {
  private static final String SENTINEL = "content://example/synthetic-private-photo.jpg";
  private static final String BUSY = "確認処理が混み合っています。少し待ってからやり直してください。";
  private static final String IMPORT_NOT_STARTED = "確認処理が混み合っています。取り込みは始まっていません。少し待ってからやり直してください。";

  private static final String SD_REGISTRATION_NOT_SAVED =
      "混雑のため登録できませんでした。少し待って、SDカード取り込み設定からフォルダを選び直してください。";

  static String run(Instrumentation test) throws Exception {
    Context context = test.getTargetContext();
    long fixture = SystemClock.elapsedRealtimeNanos();
    try (FixtureRecording recording = new FixtureRecording(context)) {
      require(
          DiagnosticLog.startRecording(context), "start diagnostic recording in the test process");
      DiagnosticLog.event("fixture.diagnostics_begin", 0, "fixture", fixture);
      crashAndPrivacy(context, fixture);
      simultaneousCrashes(context);
      credentialConcurrency(context);
      sourceConcurrency(test);
      recording.drain();
      checkPrivacy(context, fixture);
    }
    return "PASS crash summary, simultaneous delegation and storage error, log privacy, generated image without a path, "
        + "Nano Banana preferences and credential messages, SD registration and SD/Wi-Fi source rejection and retry";
  }

  private static final class FixtureRecording implements AutoCloseable {
    private final Context context;
    private final SharedPreferences settings;
    private final boolean settingExisted;
    private final boolean settingEnabled;
    private final boolean wasRecording;

    FixtureRecording(Context context) {
      this.context = context;
      settings = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE);
      settingExisted = settings.contains("record");
      settingEnabled = settings.getBoolean("record", false);
      wasRecording = DiagnosticLog.currentRecorder() != null;
    }

    void drain() {
      var recorder = DiagnosticLog.currentRecorder();
      require(
          recorder != null && DiagnosticLog.stopRecording(context, recorder),
          "drain the diagnostic recorder before checking its files");
    }

    @Override
    public void close() {
      try {
        var recorder = DiagnosticLog.currentRecorder();
        if (wasRecording && recorder == null) {
          require(DiagnosticLog.startRecording(context), "restore the running recorder");
        } else if (!wasRecording && recorder != null) {
          require(DiagnosticLog.stopRecording(context, recorder), "stop the fixture recorder");
        }
      } finally {
        SharedPreferences.Editor restore = settings.edit();
        if (settingExisted) restore.putBoolean("record", settingEnabled);
        else restore.remove("record");
        require(restore.commit(), "restore the original diagnostic setting");
      }
    }
  }

  private static void crashAndPrivacy(Context context, long fixture) throws Exception {
    require(
        Thread.getDefaultUncaughtExceptionHandler() instanceof DiagnosticCrashHandler,
        "crash handler installed");
    File directory =
        Files.createTempDirectory(context.getCacheDir().toPath(), "crash-summary").toFile();
    File file = new File(directory, "last-crash.json");
    File unavailable = new File(directory, "unavailable");
    require(unavailable.createNewFile(), "storage error fixture");
    Throwable cause = new IOException("private cause " + SENTINEL);
    Throwable failure = new IllegalStateException("private failure " + SENTINEL, cause);
    failure.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement("Fixture", "check", SENTINEL, 42),
          new StackTraceElement("Other", "discarded", "Other.java", 15)
        });
    Thread thread = Thread.currentThread();
    AtomicInteger delegates = new AtomicInteger();
    Thread.UncaughtExceptionHandler previous =
        (originalThread, error) -> {
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
      require(
          note.length() == 3
              && note.getInt("version_code") == 777
              && note.getString("exception").equals(IllegalStateException.class.getName()),
          "summary metadata");
      require(
          frame.length() == 3
              && frame.getString("class").equals("Fixture")
              && frame.getString("method").equals("check")
              && frame.getInt("line") == 42,
          "top frame only");
      require(
          content.length() < 1024
              && !content.contains(SENTINEL)
              && !content.contains("discarded")
              && !content.contains("private failure"),
          "no private crash detail");
    } finally {
      Files.deleteIfExists(file.toPath());
      Files.deleteIfExists(unavailable.toPath());
      Files.deleteIfExists(directory.toPath());
    }

    DiagnosticLog.exception("fixture.diagnostics", "failed", failure);
    ImportLog.INSTANCE.init(context);
    ImportLog.INSTANCE.w("ImportLogFixture", "fixture.import " + fixture, failure);
  }

  private static void simultaneousCrashes(Context context) throws Exception {
    File directory =
        Files.createTempDirectory(context.getCacheDir().toPath(), "parallel-crash").toFile();
    File summary = new File(directory, "last-crash.json");
    CountDownLatch saving = new CountDownLatch(1), allowWrite = new CountDownLatch(1);
    CountDownLatch secondEntered = new CountDownLatch(1), secondDelegated = new CountDownLatch(1);
    CountDownLatch firstDelegated = new CountDownLatch(1),
        allowFirstToReturn = new CountDownLatch(1);
    AtomicInteger delegatedWithSummary = new AtomicInteger();
    Throwable firstFailure =
        new IllegalStateException() {
          @Override
          public StackTraceElement[] getStackTrace() {
            saving.countDown();
            waitForLatch(allowWrite);
            return new StackTraceElement[] {new StackTraceElement("First", "crash", null, 4)};
          }
        };
    Throwable secondFailure = new IllegalArgumentException();
    DiagnosticCrashHandler handler =
        new DiagnosticCrashHandler(
            directory,
            123,
            (thread, failure) -> {
              if (summary.isFile()) delegatedWithSummary.incrementAndGet();
              if (failure == firstFailure) {
                firstDelegated.countDown();
                waitForLatch(allowFirstToReturn);
              } else if (failure == secondFailure) {
                secondDelegated.countDown();
              }
            });
    Thread first =
        new Thread(
            () -> handler.uncaughtException(Thread.currentThread(), firstFailure), "first-crash");
    Thread second =
        new Thread(
            () -> {
              secondEntered.countDown();
              handler.uncaughtException(Thread.currentThread(), secondFailure);
            },
            "second-crash");
    try {
      first.start();
      require(saving.await(3, TimeUnit.SECONDS), "first crash is recording");
      second.start();
      require(secondEntered.await(3, TimeUnit.SECONDS), "second crash has entered");
      long waitingDeadline = SystemClock.elapsedRealtime() + 3000;
      while (second.getState() != Thread.State.BLOCKED
          && secondDelegated.getCount() != 0
          && SystemClock.elapsedRealtime() < waitingDeadline) SystemClock.sleep(10);
      require(
          second.getState() == Thread.State.BLOCKED && secondDelegated.getCount() == 1,
          "second crash cannot end the process while saving");
      allowWrite.countDown();
      require(firstDelegated.await(3, TimeUnit.SECONDS), "first crash delegated");
      require(secondDelegated.await(3, TimeUnit.SECONDS), "delegation does not hold save lock");
      require(delegatedWithSummary.get() == 2, "both delegates observe the saved summary");
      require(
          new JSONObject(read(summary)).getJSONObject("frame").getString("class").equals("First"),
          "second crash does not overwrite first summary");
    } finally {
      allowWrite.countDown();
      allowFirstToReturn.countDown();
      first.join(3000);
      second.join(3000);
      Files.deleteIfExists(summary.toPath());
      Files.deleteIfExists(directory.toPath());
    }
  }

  private static void waitForLatch(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static void credentialConcurrency(Context context) throws Exception {
    String namespace = "nano-bananafailure-fixture";
    SharedPreferences prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE);
    NanoBananaStore writer = new NanoBananaStore(context, namespace);
    NanoBananaStore reader = new NanoBananaStore(context, namespace);
    try {
      writer.setKey("synthetic-fixture-api-key");
      require(
          "synthetic-fixture-api-key".equals(reader.apiKey()),
          "encrypted key readable from another store");
      writer.setE2("fixture-bucket", "A".repeat(20), "B".repeat(40));
      writer.job(new JSONObject().put("stage", "cleanup"));
      expectIo(
          () -> reader.setE2("another-bucket", "A".repeat(20), "B".repeat(40)), "バケットは変更できません");
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
      Thread blocked =
          new Thread(
              () -> {
                entering.countDown();
                try {
                  reader.job(new JSONObject().put("stage", "pending"));
                } catch (Exception failure) {
                  error.set(failure);
                } finally {
                  finished.countDown();
                }
              },
              "store-contention-fixture");
      synchronized (NanoBananaStore.LOCK) {
        blocked.start();
        require(entering.await(3, TimeUnit.SECONDS), "job writer has started");
        require(!finished.await(100, TimeUnit.MILLISECONDS), "independent store uses shared lock");
        expectIo(reader::deleteE2, "進行中の処理を終了");
      }
      require(
          finished.await(3, TimeUnit.SECONDS)
              && error.get() == null
              && "pending".equals(writer.job().getString("stage")),
          "serialized write visible to both stores");
      writer.job(null);
      expectIo(() -> failNextCommit(prefs).setKey("different-fixture-key"), "APIキーを保存できません");
      expectIo(failNextCommit(prefs)::deleteKey, "APIキーを削除できません");
      require(
          reader.hasKey() && reader.apiKey().equals("synthetic-fixture-api-key"),
          "failed API edits preserve key");
      String priorE2 = prefs.getString("e2", null);
      expectIo(
          () -> failNextCommit(prefs).setE2("another-bucket", "A".repeat(20), "B".repeat(40)),
          "IDrive e2の設定を保存できません");
      expectIo(failNextCommit(prefs)::deleteE2, "設定を削除できません");
      require(
          reader.hasE2() && priorE2.equals(prefs.getString("e2", null)),
          "failed e2 edits preserve ciphertext");
      reader.e2();
      writer.deleteE2();
      expectIo(reader::e2, "IDrive e2の設定を読み込めません");
      writer.deleteKey();
      expectIo(reader::apiKey, "APIキーを登録し直してください");
    } finally {
      context.deleteSharedPreferences(namespace);
    }
  }

  private static NanoBananaStore failNextCommit(SharedPreferences prefs) {
    AtomicBoolean fail = new AtomicBoolean(true);
    SharedPreferences failing =
        (SharedPreferences)
            Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class},
                (unused, method, args) -> {
                  Object result = method.invoke(prefs, args);
                  if (!(result instanceof SharedPreferences.Editor editor)) return result;
                  return Proxy.newProxyInstance(
                      SharedPreferences.Editor.class.getClassLoader(),
                      new Class<?>[] {SharedPreferences.Editor.class},
                      (proxy, edit, values) -> {
                        Object edited = edit.invoke(editor, values);
                        // The first commit mutates memory, but reports failure. The rollback is
                        // allowed.
                        if (edit.getName().equals("commit") && fail.getAndSet(false)) return false;
                        return edited == editor ? proxy : edited;
                      });
                });
    return new NanoBananaStore(failing);
  }

  private static void sourceConcurrency(Instrumentation test) throws Exception {
    Context context = test.getTargetContext();
    SharedPreferences registration =
        context.getSharedPreferences("camera_import", Context.MODE_PRIVATE);
    String volumeKey = "sd_tree_uri_volume_2301-1220", latestKey = "sd_tree_uri";
    String oldVolume = registration.getString(volumeKey, null);
    String oldLatest = registration.getString(latestKey, null);
    Uri previousTree =
        Uri.parse("content://com.android.externalstorage.documents/tree/2301-1220%3ADCIM");
    require(
        registration
            .edit()
            .putString(latestKey, previousTree.toString())
            .putString(volumeKey, previousTree.toString())
            .commit(),
        "previous registration fixture");
    try {
      verifyBusyOperations(test, previousTree);
    } finally {
      require(
          registration
              .edit()
              .putString(latestKey, oldLatest)
              .putString(volumeKey, oldVolume)
              .commit(),
          "restore previous registration fixture");
    }
  }

  private static void verifyBusyOperations(Instrumentation test, Uri previousTree)
      throws Exception {
    Context context = test.getTargetContext();
    MainActivity gallery =
        (MainActivity)
            test.startActivitySync(
                new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.GENERATED_IMAGE, "content://media"));
    test.runOnMainSync(gallery::finish);
    CameraImportActivity screen =
        (CameraImportActivity)
            test.startActivitySync(
                new Intent(context, CameraImportActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      DiagnosticChecks.waitFor(test, screen::hasWindowFocus);
      DiagnosticChecks.waitFor(
          test,
          () ->
              field(screen, "wifiCancellation") == null
                  && field(screen, "sdStatusCancellation") == null);
      Executor requests = (Executor) field(null, "SOURCE_IO");
      Executor status = (Executor) field(null, "SOURCE_STATUS_IO");
      CountDownLatch entered = new CountDownLatch(4), release = new CountDownLatch(1);
      Runnable hold =
          () -> {
            entered.countDown();
            try {
              release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          };
      try {
        for (int i = 0; i < 2; i++) {
          requests.execute(hold);
          status.execute(hold);
        }
        require(entered.await(3, TimeUnit.SECONDS), "both executors saturated");
        Method discover =
            CameraImportActivity.class.getDeclaredMethod("discoverQuickSource", boolean.class);
        Method statusRefresh = CameraImportActivity.class.getDeclaredMethod("refreshSdSource");
        Method loadWifi = CameraImportActivity.class.getDeclaredMethod("loadWifiProfiles");
        Method verify =
            CameraImportActivity.class.getDeclaredMethod(
                "verifySdSourceAsync",
                CameraImportActivity.SdStart.class,
                CameraImportActivity.SdStartMode.class,
                boolean.class,
                java.util.function.Consumer.class);
        Method confirmPicked =
            CameraImportActivity.class.getDeclaredMethod(
                "confirmPickedSubmission", CameraImportActivity.SdStart.class, boolean.class);
        CameraImportActivity.SdStart picked =
            new CameraImportActivity.SdStart(
                Uri.parse("content://com.android.externalstorage.documents/tree/ABCD-1234%3ADCIM"),
                null,
                PhotoStorage.selected(context),
                "JPEG",
                MountedCameraCard.detachGeneration(),
                true,
                SdSourceRegistrations.latest(context));
        AtomicInteger verified = new AtomicInteger();
        java.util.function.Consumer<CameraImportActivity.SdLocation> callback =
            ignored -> verified.incrementAndGet();
        test.runOnMainSync(
            () -> {
              invoke(discover, screen, true);
              require(
                  IMPORT_NOT_STARTED.equals(field(screen, "connectionFailure"))
                      && field(screen, "sourceCancellation") == null
                      && !((boolean) field(screen, "starting")),
                  "lookup rejected visibly");
              invoke(verify, screen, picked, null, false, callback);
              require(
                  IMPORT_NOT_STARTED.equals(field(screen, "connectionFailure"))
                      && field(screen, "sourceCancellation") == null,
                  "verification rejected visibly");
              screen.confirmSdSource(picked, true);
              require(
                  SD_REGISTRATION_NOT_SAVED.equals(field(screen, "connectionFailure"))
                      && field(screen, "sdConfirmation") == null,
                  "register-only preflight reports unsaved registration");
              invoke(confirmPicked, screen, picked, true);
              require(
                  SD_REGISTRATION_NOT_SAVED.equals(field(screen, "connectionFailure"))
                      && field(screen, "submittedSd") == null,
                  "register-only confirmation reports unsaved registration");
              invoke(verify, screen, picked, null, false, callback);
              invoke(statusRefresh, screen);
              require(
                  BUSY.contentEquals(((TextView) field(screen, "sdStatus")).getText())
                      && field(screen, "sdStatusCancellation") == null,
                  "SD status busy");
              screen.wifiProfileLoader = (owner, cancellation) -> List.of();
              invoke(loadWifi, screen);
              require(
                  BUSY.contentEquals(((TextView) field(screen, "wifiStatus")).getText())
                      && field(screen, "wifiCancellation") == null,
                  "Wi-Fi status busy");
            });
        capture(test, screen, "diagnostics-source-busy.png");
        test.runOnMainSync(
            () -> {
              View retry = (View) field(screen, "sdStart");
              require(
                  retry.isEnabled() && retry.performClick(),
                  "SD retry action handles click while busy");
              require(
                  IMPORT_NOT_STARTED.equals(field(screen, "connectionFailure")),
                  "retry still reports no import");
            });
        // The production failure handler first scrolls the updated status into view.
        test.waitForIdleSync();
        test.runOnMainSync(
            () -> {
              View retry = (View) field(screen, "sdStart");
              retry.requestRectangleOnScreen(
                  new Rect(0, 0, retry.getWidth(), retry.getHeight()), true);
            });
        DiagnosticChecks.waitFor(
            test,
            () -> {
              View retry = (View) field(screen, "sdStart");
              Rect visible = new Rect();
              return retry.getGlobalVisibleRect(visible)
                  && visible.height() == retry.getHeight()
                  && visible.width() == retry.getWidth()
                  && retry.isEnabled();
            });
        capture(test, screen, "diagnostics-sd-retry-busy.png");
        test.runOnMainSync(() -> screen.confirmSdSource(picked, true));
        capture(test, screen, "diagnostics-sd-registration-busy.png");
        verifyRegistrationRetry(test, screen);
        require(
            previousTree.toString().equals(SdSourceRegistrations.latest(context))
                && previousTree.equals(SdSourceRegistrations.forVolume(context, "2301-1220")),
            "rejected registration and retry preserve the old source");

        release.countDown();
        // A new user request is necessary: releasing an executor never replays a rejected import.
        SystemClock.sleep(150);
        require(verified.get() == 0, "no automatic replay");
        test.runOnMainSync(
            () -> {
              screen.sdSourceProbe =
                  (request, cancellation) ->
                      new CameraImportActivity.SdLocation("fixture", "fixture");
              invoke(verify, screen, picked, null, false, callback);
              invoke(loadWifi, screen);
            });
        DiagnosticChecks.waitFor(
            test,
            () ->
                verified.get() == 1
                    && field(screen, "wifiCancellation") == null
                    && ((TextView) field(screen, "wifiStatus"))
                        .getText()
                        .toString()
                        .contains("QR"));
        showWifiCard(test, screen);
        capture(test, screen, "diagnostics-wifi-unregistered.png");
        test.runOnMainSync(
            () -> {
              screen.wifiProfileLoader =
                  (owner, cancellation) -> {
                    throw new IOException(SENTINEL);
                  };
              invoke(loadWifi, screen);
            });
        DiagnosticChecks.waitFor(
            test,
            () ->
                "登録カメラを確認できませんでした。"
                    .contentEquals(((TextView) field(screen, "wifiStatus")).getText()));
        showWifiCard(test, screen);
        capture(test, screen, "diagnostics-wifi-read-error.png");
      } finally {
        release.countDown();
      }
    } finally {
      test.runOnMainSync(screen::finish);
    }
  }

  private static void verifyRegistrationRetry(Instrumentation test, CameraImportActivity screen) {
    AtomicInteger launches = new AtomicInteger();
    var picker =
        new Instrumentation.ActivityMonitor() {
          @Override
          public Instrumentation.ActivityResult onStartActivity(Intent intent) {
            if (!Intent.ACTION_OPEN_DOCUMENT_TREE.equals(intent.getAction())) return null;
            launches.incrementAndGet();
            return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
          }
        };
    test.addMonitor(picker);
    try {
      test.runOnMainSync(
          () -> {
            View edit = (View) field(screen, "sdSettingsButton");
            require(edit.isEnabled() && edit.performClick(), "SD edit button opens settings");
            var settings = (androidx.appcompat.app.AlertDialog) field(screen, "sdSettingsDialog");
            require(settings.isShowing(), "SD settings dialog shown");
            View choose = textView(settings.getWindow().getDecorView(), "SDカードのフォルダを選ぶ");
            require(
                choose != null && choose.isEnabled() && choose.performClick(),
                "settings action reopens folder picker");
          });
      test.waitForIdleSync();
      require(launches.get() == 1, "one folder selection Intent requested");
    } finally {
      test.removeMonitor(picker);
    }
  }

  private static View textView(View root, String text) {
    if (root instanceof TextView view && text.contentEquals(view.getText())) return root;
    if (root instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) {
        View match = textView(group.getChildAt(i), text);
        if (match != null) return match;
      }
    }
    return null;
  }

  private static void capture(Instrumentation test, CameraImportActivity screen, String filename)
      throws IOException {
    test.waitForIdleSync();
    SystemClock.sleep(200); // Allow the compositor to present the laid-out and scrolled frame.
    var screenshot = test.getUiAutomation().takeScreenshot();
    require(screenshot != null, "busy screenshot");
    try {
      View root = screen.getWindow().getDecorView();
      require(root.getWidth() <= screenshot.getWidth(), "capture contains the full window width");
      try (var output =
          new FileOutputStream(new File(test.getTargetContext().getCacheDir(), filename))) {
        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
      }
    } finally {
      screenshot.recycle();
    }
  }

  private static void showWifiCard(Instrumentation test, CameraImportActivity screen) {
    test.runOnMainSync(
        () -> {
          View status = (View) field(screen, "wifiStatus");
          View card = (View) status.getParent();
          card.requestRectangleOnScreen(new Rect(0, 0, card.getWidth(), card.getHeight()), true);
        });
  }

  private static void checkPrivacy(Context context, long fixture) throws Exception {
    File directory = new File(context.getFilesDir(), "diagnostics");
    File previous = new File(directory, "previous.jsonl");
    String log =
        (previous.isFile() ? read(previous) : "") + read(new File(directory, "events.jsonl"));
    int start = log.lastIndexOf("\"fixture\":" + fixture + "}");
    require(start >= 0, "this run produced the diagnostic fixture marker");
    log = log.substring(start);
    String finalPhase = exceptionRecord("camera.wifi_profiles", "failed", IOException.class);
    require(log.contains(finalPhase), "this run produced the regular Wi-Fi read error");
    require(
        log.contains(exceptionRecord("fixture.diagnostics", "failed", IllegalStateException.class)),
        "this run produced the fixture failure");
    for (String phase :
        List.of(
            "camera.source_lookup",
            "camera.sd_verify",
            "camera.sd_status",
            "camera.wifi_profiles")) {
      require(
          log.contains(
              exceptionRecord(
                  phase, "busy", java.util.concurrent.RejectedExecutionException.class)),
          "busy event missing: " + phase);
    }
    String usbLog = read(ImportLog.INSTANCE.currentFile());
    int usb =
        usbLog.lastIndexOf("fixture.import " + fixture + " | java.lang.IllegalStateException");
    require(usb >= 0, "this run produced a USB exception type");
    require(
        !log.contains("synthetic-private-photo.jpg")
            && !usbLog.substring(usb).contains("synthetic-private-photo.jpg"),
        "private exception text omitted");
  }

  private static String exceptionRecord(
      String phase, String outcome, Class<? extends Throwable> type) {
    return "\"phase\":\""
        + phase
        + "\",\"outcome\":\""
        + outcome
        + "\",\"exception\":\""
        + type.getName()
        + "\"";
  }

  private interface IoAction {
    void run() throws IOException;
  }

  private static String read(File file) throws IOException {
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }

  private static void expectIo(IoAction action, String message) throws IOException {
    try {
      action.run();
    } catch (IOException error) {
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
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }

  private static void invoke(Method method, CameraImportActivity activity, Object... args) {
    try {
      method.setAccessible(true);
      method.invoke(activity, args);
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }

  private static void require(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }
}
