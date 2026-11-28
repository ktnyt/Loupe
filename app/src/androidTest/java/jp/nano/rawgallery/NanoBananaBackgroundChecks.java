package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.google.android.material.snackbar.Snackbar;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import jp.nano.rawgallery.camera.MediaSaver;
import org.json.JSONObject;

/**
 * Synthetic OS-service integration. All network operations fail closed through an in-process seam.
 */
final class NanoBananaBackgroundChecks {
  private static final List<String> created = new ArrayList<>();

  static void activityCreated(Activity activity) {
    synchronized (created) {
      created.add(activity.getClass().getName());
    }
  }

  interface Check {
    boolean ok() throws Exception;
  }

  private static void await(String label, Check check) throws Exception {
    long end = SystemClock.uptimeMillis() + 15000;
    while (!check.ok() && SystemClock.uptimeMillis() < end) Thread.sleep(30);
    TagDetailChecks.require(check.ok(), label);
  }

  private static void idle(Instrumentation test) throws Exception {
    await("service worker released lease", () -> !NanoBananaService.busy());
    test.waitForIdleSync();
  }

  private static final class Fake implements NanoBananaJob.Services {
    final NanoBananaStore store;
    final byte[] jpeg;
    final List<String> saved;
    final CountDownLatch entered = new CountDownLatch(1),
        release = new CountDownLatch(1),
        interrupted = new CountDownLatch(1);
    volatile int submits, statuses, downloads, deletes;
    volatile boolean deleteFailure, ignoreInterrupt, requireVersion;
    volatile boolean statusTimedOut, statusExited;
    volatile IOException statusFailure;
    NanoBananaApi submitApi;
    NanoBananaApi statusApi;
    volatile long statusEnteredAt;
    volatile String prompt;
    volatile NanoBananaOutput output;

    Fake(NanoBananaStore store, byte[] jpeg, List<String> saved) {
      this.store = store;
      this.jpeg = jpeg;
      this.saved = saved;
    }

    public String upload(String object, byte[] bytes, String owner) {
      return "synthetic-version";
    }

    public String generate(String text, String object, String version, NanoBananaOutput requested)
        throws Exception {
      submits++;
      prompt = text;
      output = requested;
      if (submitApi != null) {
        return submitApi.generate(text, "https://synthetic.invalid/input?token=private", requested);
      }
      return "synthetic-task";
    }

    public JSONObject status(String task) throws Exception {
      statuses++;
      if (statusFailure != null) throw statusFailure;
      if (statusApi != null) return statusApi.status(task);
      statusEnteredAt = SystemClock.elapsedRealtime();
      entered.countDown();
      // Finish when the fixture releases the provider. The watchdog covers all UI
      // actions and captures, which can exceed a single assertion's timeout.
      long deadline = statusEnteredAt + TimeUnit.SECONDS.toMillis(120);
      boolean wasInterrupted = false;
      try {
        while (true) {
          try {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining > 0 && release.await(remaining, TimeUnit.MILLISECONDS)) break;
            statusTimedOut = true;
            throw new IOException("synthetic provider release watchdog expired");
          } catch (InterruptedException e) {
            interrupted.countDown();
            if (!ignoreInterrupt) throw e;
            wasInterrupted = true;
          }
        }
        if (wasInterrupted) Thread.currentThread().interrupt();
        return new JSONObject()
            .put("successFlag", 1)
            .put(
                "response",
                new JSONObject().put("resultImageUrl", "https://synthetic.invalid/result.jpg"));
      } finally {
        statusExited = true;
      }
    }

    public byte[] download(String url) {
      downloads++;
      return jpeg;
    }

    public void delete(String object, String version, String owner) throws Exception {
      deletes++;
      JSONObject record = store.job();
      if (record != null && record.has("saved")) {
        synchronized (saved) {
          if (!saved.contains(record.getString("saved"))) saved.add(record.getString("saved"));
        }
      }
      if (requireVersion && version == null)
        throw new java.io.IOException("synthetic object version missing");
      if (deleteFailure) {
        deleteFailure = false;
        throw new java.io.IOException("synthetic cleanup interruption");
      }
    }
  }

  private static final class FixtureFactory implements NanoBananaService.Factory {
    final NanoBananaStore store;
    volatile Fake fake;
    volatile String lastResult;
    volatile int jobs;
    volatile long lastMessageAt;
    private volatile SubmitFailureNotice submitNotice;
    final List<String> messages = new java.util.concurrent.CopyOnWriteArrayList<>();

    FixtureFactory(NanoBananaStore store) {
      this.store = store;
    }

    public NanoBananaStore store(Context context) {
      return store;
    }

    public void result(String message) {
      lastResult = message;
      lastMessageAt = SystemClock.uptimeMillis();
      messages.add(message);
      SubmitFailureNotice notice = submitNotice;
      if (notice != null && notice.expected.equals(message)) notice.onResult();
    }

    public NanoBananaJob job(Context context, NanoBananaStore received) {
      jobs++;
      TagDetailChecks.require(
          received == store && fake != null, "isolated service dependencies required");
      return new NanoBananaJob(context, store, fake);
    }
  }

  static String run(Instrumentation test, int width, float font) throws Exception {
    Context context = test.getTargetContext();
    String namespace = "nano-background-fixture";
    TagDetailChecks.require(!NanoBananaService.busy(), "no existing service lease before fixture");
    context.stopService(new Intent(context, NanoBananaService.class));
    test.waitForIdleSync();
    context.getSharedPreferences(namespace, 0).edit().clear().commit();
    NanoBananaStore store = new NanoBananaStore(context, namespace);
    FixtureFactory factory = new FixtureFactory(store);
    List<String> saved = new ArrayList<>();
    MainActivity activity = null;
    Bitmap bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(0xff286ca0);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
    bitmap.recycle();
    byte[] jpeg = out.toByteArray();
    NanoBananaStore.Prompt prompt =
        new NanoBananaStore.Prompt("fixture", "色を整える", "captured synthetic prompt");
    android.content.ContentValues values = new android.content.ContentValues();
    values.put(
        android.provider.MediaStore.Images.Media.DISPLAY_NAME, "nano-background-owned-fixture.jpg");
    values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
    values.put(
        android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LoupeSyntheticChecks");
    Uri fixture =
        context
            .getContentResolver()
            .insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
    TagDetailChecks.require(fixture != null, "owned fixture row created");
    try (java.io.OutputStream fixtureOut = context.getContentResolver().openOutputStream(fixture)) {
      fixtureOut.write(jpeg);
    }
    PhotoRepository.Photo photo =
        new PhotoRepository.Photo(
            fixture, "nano-background-owned-fixture.jpg", "synthetic", jpeg.length, 1, 1);
    PhotoRepository.Photo second =
        new PhotoRepository.Photo(
            fixture, "nano-background-second.jpg", "synthetic", jpeg.length, 2, 2);
    final PhotoRepository.Photo input = photo, next = second;
    NanoBananaService.dependencies = factory;
    try {
      verifyMtpDeletionProof();
      verifyDiscardWithDetachedResult(context, store, new Fake(store, jpeg, saved));
      store.setKey("synthetic-only");
      store.setE2("synthetic-bucket", "ABCDEFGHIJKLMNOPQRST", "a".repeat(40));
      store.savePrompt(null, prompt.name, prompt.text);
      store.setOutput("4:3", "1K");
      activity =
          (MainActivity)
              test.startActivitySync(
                  new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      final MainActivity screen = activity;
      GalleryScreenChecks.finishInitialIndex(test, screen);
      GalleryScreenChecks.portraitFixture(test, screen, width, font);
      TagDetailChecks.ui(
          test,
          () -> {
            RankingResultChecks.set(screen, "photos", new ArrayList<>(List.of(input, next)));
            RankingResultChecks.set(screen, "burstMembers", null);
            showPhoto(screen, 0);
          });
      await(
          "synthetic viewer loaded",
          () -> RankingResultChecks.get(screen, "activePreview") != null);
      // Exercise the actual viewer tag action while the slow service is running below.
      Fake first = new Fake(store, jpeg, saved);
      factory.fake = first;
      synchronized (created) {
        created.clear();
      }
      Dialog[] selected = {null};
      TagDetailChecks.ui(
          test, () -> selected[0] = NanoBananaUi.show(screen, input, store, NanoBananaUi.ACTUAL));
      capture(test, screen, "nano-background-ready-" + width + "-" + font);
      TagDetailChecks.ui(
          test,
          () -> {
            View row = findLabel(selected[0].getWindow().getDecorView(), prompt.name);
            verifyMenuTargets(selected[0].getWindow().getDecorView());
            TagDetailChecks.require(
                row != null && row.performClick(), "prompt selection starts service directly");
          });
      TagDetailChecks.require(
          NanoBananaService.busy() && !selected[0].isShowing(),
          "prompt selection dismisses menu and admits foreground service");
      TagDetailChecks.require(
          !NanoBananaService.start(screen, photo, prompt), "duplicate admission rejected");
      store.setOutput("16:9", "4K");
      store.savePrompt(store.prompts().get(0).id, "変更した設定", "changed after admission");
      TagDetailChecks.require(
          first.entered.await(15, TimeUnit.SECONDS), "real OS service reached fake provider");
      TagDetailChecks.require(
          first.submits == 1
              && first.prompt.equals(prompt.text)
              && first.output.aspectRatio.equals("4:3")
              && first.output.resolution.equals("1K"),
          "admission captures prompt and output before settings edits");
      TagDetailChecks.ui(
          test,
          () -> {
            View more = findLabel(screen.getWindow().getDecorView(), "写真のその他の操作");
            TagDetailChecks.require(
                more != null && more.performClick(), "open current viewer action sheet");
            View nextControl = findWindowLabel("次の写真");
            TagDetailChecks.require(
                nextControl != null && nextControl.isEnabled() && nextControl.performClick(),
                "normal next-photo control responds during generation");
          });
      View viewerWindow = screen.getWindow().getDecorView();
      GenerationIndicatorChecks.await(
          test,
          "next photo ready while service waits",
          () ->
              ((Integer) RankingResultChecks.get(screen, "index")) == 1
                  && RankingResultChecks.get(screen, "activePreview") != null
                  && viewerWindow.hasWindowFocus());
      Instrumentation.ActivityMonitor tags =
          test.addMonitor(TagActivity.class.getName(), null, false);
      View[] tagActionSheet = {null};
      TagDetailChecks.ui(
          test,
          () -> {
            View more = findLabel(screen.getWindow().getDecorView(), "写真のその他の操作");
            TagDetailChecks.require(
                more != null && more.performClick(), "open current viewer tag sheet");
            View tag = findWindowLabel("タグ");
            if (tag != null) tagActionSheet[0] = tag.getRootView();
            TagDetailChecks.require(
                tag != null && tag.performClick(),
                "normal viewer tag control responds during generation");
          });
      Activity tagScreen = tags.waitForActivityWithTimeout(10000);
      test.removeMonitor(tags);
      TagDetailChecks.require(
          tagScreen instanceof TagActivity, "normal tag screen opens during generation");
      TagDetailChecks.ui(test, tagScreen::finish);
      GenerationIndicatorChecks.await(
          test,
          "viewer action sheet regains focus after closing tags",
          () -> tagScreen.isDestroyed() && tagActionSheet[0].hasWindowFocus());
      synchronized (created) {
        TagDetailChecks.require(
            created.equals(List.of(TagActivity.class.getName())),
            "only requested tag navigation occurred while generating");
        created.clear();
      }
      captureWindow(test, tagActionSheet[0], "nano-background-viewer-" + width + "-" + font);
      TagDetailChecks.ui(
          test,
          () -> {
            View close = findLabel(tagActionSheet[0], "閉じる");
            TagDetailChecks.require(
                close != null && close.performClick(), "close restored action sheet");
          });
      GenerationIndicatorChecks.await(
          test, "viewer regains focus after closing actions", viewerWindow::hasWindowFocus);
      Dialog[] menu = {null};
      TagDetailChecks.ui(
          test,
          () -> {
            menu[0] = NanoBananaUi.show(screen, next, store, NanoBananaUi.ACTUAL);
          });
      captureWindow(
          test, menu[0].getWindow().getDecorView(), "nano-background-busy-" + width + "-" + font);
      TagDetailChecks.ui(
          test,
          () -> {
            verifyMenuTargets(menu[0].getWindow().getDecorView());
            menu[0].dismiss();
          });
      // Verify both sides of OS navigation while the provider expressly remains blocked.
      requireGenerationBlocked(store, first, "before Home");
      TagDetailChecks.require(
          test.getUiAutomation()
              .performGlobalAction(
                  android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME),
          "Android accepted Home");
      GenerationIndicatorChecks.await(
          test,
          "Home backgrounds the Loupe viewer",
          () -> !GenerationIndicator.foreground() && !viewerWindow.hasWindowFocus());
      requireGenerationBlocked(store, first, "after Home");
      long homeWaitMs = SystemClock.elapsedRealtime() - first.statusEnteredAt;
      first.release.countDown();
      idle(test);
      TagDetailChecks.require(
          first.downloads == 1 && first.deletes == 1 && store.job() == null,
          "background completion saves, cleans and clears durable record");
      synchronized (created) {
        TagDetailChecks.require(
            created.isEmpty(), "generation and completion do not launch another Activity");
      }
      TagDetailChecks.require(
          factory.messages.equals(List.of("画像の生成を開始しました", "画像を生成しています", "生成画像を保存しました")),
          "initial prompt/busy/completion Toast order exactly once: " + factory.messages);
      TagDetailChecks.require(saved.size() == 1, "one automatically saved JPEG");
      verifySaved(context, saved.get(0), 1200, 800);
      context.startActivity(
          new Intent(context, MainActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
      test.waitForIdleSync();
      TagDetailChecks.ui(test, screen::onBackPressed);
      test.waitForIdleSync();
      // A second request is accepted after automatic completion and exercises saved cleanup
      // recovery.
      int cleanupToastStart = factory.messages.size();
      Fake cleanup = new Fake(store, jpeg, saved);
      cleanup.deleteFailure = true;
      cleanup.release.countDown();
      factory.fake = cleanup;
      TagDetailChecks.require(
          NanoBananaService.start(screen, photo, prompt), "next request accepted after completion");
      idle(test);
      JSONObject pending = store.job();
      TagDetailChecks.require(
          pending != null && pending.optString("stage").equals("cleanup") && pending.has("saved"),
          "cleanup failure retains saved URI");
      String retained = pending.getString("saved");
      TagDetailChecks.require(
          factory
              .messages
              .subList(cleanupToastStart, factory.messages.size())
              .equals(List.of("画像の生成を開始しました", "画像は保存済みです。後処理を再試行してください")),
          "cleanup failure start then saved-image notice exactly once: " + factory.messages);
      TagDetailChecks.ui(
          test,
          () -> {
            showPhoto(screen, 0);
            menu[0] = NanoBananaUi.show(screen, input, store, NanoBananaUi.ACTUAL);
          });
      capture(test, screen, "nano-background-cleanup-" + width + "-" + font);
      TagDetailChecks.ui(test, () -> menu[0].dismiss());
      TagDetailChecks.ui(
          test,
          () -> {
            menu[0] = NanoBananaUi.show(screen, input, store, NanoBananaUi.ACTUAL);
            View row = findLabel(menu[0].getWindow().getDecorView(), "後処理を再試行");
            TagDetailChecks.require(
                row != null && row.performClick(), "cleanup retry control responds");
          });
      idle(test);
      TagDetailChecks.require(
          store.job() == null
              && cleanup.submits == 1
              && cleanup.downloads == 1
              && cleanup.deletes == 2,
          "cleanup retry never POSTs or downloads again");
      verifySaved(context, retained, 1200, 800);
      // Stop while a provider ignores interruption: the lease must remain held until it returns.
      Fake stopped = new Fake(store, jpeg, saved);
      stopped.ignoreInterrupt = true;
      factory.fake = stopped;
      TagDetailChecks.require(
          NanoBananaService.start(screen, photo, prompt), "interruption request admitted");
      TagDetailChecks.require(
          stopped.entered.await(15, TimeUnit.SECONDS), "worker blocks in provider");
      context.stopService(new Intent(context, NanoBananaService.class));
      TagDetailChecks.require(
          stopped.interrupted.await(15, TimeUnit.SECONDS),
          "OS Service destruction interrupted blocked worker before fixture release");
      test.waitForIdleSync();
      TagDetailChecks.require(
          NanoBananaService.busy() && !NanoBananaService.start(screen, photo, prompt),
          "destroy cannot release a live worker lease");
      stopped.release.countDown();
      idle(test);
      JSONObject interruptedRecord = store.job();
      TagDetailChecks.require(
          stopped.downloads == 0
              && interruptedRecord != null
              && interruptedRecord.optString("stage").equals("generating"),
          "interrupted worker preserves resumable task: downloads="
              + stopped.downloads
              + ", statuses="
              + stopped.statuses
              + ", stage="
              + (interruptedRecord == null ? "none" : interruptedRecord.optString("stage")));
      // Mark discard before launch; stale/redelivered intents may only execute this cleanup.
      NanoBananaJob.markDiscarding(store);
      stopped.deleteFailure = true;
      ContextCompat.startForegroundService(
          context, new Intent(context, NanoBananaService.class).putExtra("request_nonce", "stale"));
      await("stale intent started durable discard", () -> stopped.deletes == 1);
      idle(test);
      TagDetailChecks.require(
          store.job().optString("stage").equals("discarding")
              && stopped.submits == 1
              && stopped.downloads == 0,
          "failed discard remains durable without resurrection");
      int savedBeforeDiscard = saved.size(),
          statusesBeforeDiscard = stopped.statuses,
          discardToastStart = factory.messages.size();
      factory.lastResult = null;
      TagDetailChecks.require(NanoBananaService.resume(screen), "discard retry admitted");
      idle(test);
      TagDetailChecks.require(
          store.job() == null && stopped.deletes == 2 && stopped.downloads == 0,
          "discard retry only removes temporary object");
      TagDetailChecks.require(
          "後処理が完了しました".equals(factory.lastResult)
              && saved.size() == savedBeforeDiscard
              && stopped.statuses == statusesBeforeDiscard,
          "manual discarding resume reports cleanup complete without saving or polling");
      TagDetailChecks.require(
          factory
              .messages
              .subList(discardToastStart, factory.messages.size())
              .equals(List.of("後処理を開始しました", "後処理が完了しました")),
          "cleanup-only retry Toast start/completion order exactly once: " + factory.messages);
      int before = stopped.submits;
      ContextCompat.startForegroundService(
          context, new Intent(context, NanoBananaService.class).putExtra("request_nonce", "stale"));
      Thread.sleep(300);
      test.waitForIdleSync();
      TagDetailChecks.require(
          !NanoBananaService.busy() && stopped.submits == before && store.job() == null,
          "stale request without durable work never submits");
      // A missing upload version is recoverable only by explicit manual e2 cleanup.
      Fake missing = new Fake(store, jpeg, saved);
      missing.requireVersion = true;
      factory.fake = missing;
      String object = "loupe-nano-temp/00000000-0000-0000-0000-000000000107.jpg";
      store.job(
          new JSONObject()
              .put("stage", "uploading")
              .put("object", object)
              .put("owner", "synthetic-owner"));
      factory.lastResult = null;
      int savedBeforeMissing = saved.size();
      TagDetailChecks.require(
          NanoBananaService.discard(screen), "missing-version cleanup admitted");
      idle(test);
      TagDetailChecks.require(
          store.job() != null
              && store.job().optString("stage").equals("discarding")
              && !store.job().has("version")
              && missing.deletes == 1
              && missing.submits == 0
              && missing.statuses == 0
              && missing.downloads == 0
              && saved.size() == savedBeforeMissing,
          "missing-version cleanup failure retains recovery location without generation or save");
      TagDetailChecks.ui(
          test,
          () -> {
            menu[0] = NanoBananaUi.show(screen, input, store, NanoBananaUi.ACTUAL);
            View location = findLabel(menu[0].getWindow().getDecorView(), "一時画像の場所");
            TagDetailChecks.require(
                location != null && location.performClick(),
                "missing-version recovery exposes temporary-object location");
          });
      test.waitForIdleSync();
      android.view.accessibility.AccessibilityNodeInfo location =
          findAccessibleText(test.getUiAutomation().getRootInActiveWindow(), object);
      TagDetailChecks.require(
          location != null
              && location.getText() != null
              && location.getText().toString().contains("自動削除できません")
              && location.getText().toString().contains("手動削除")
              && location.getText().toString().contains("バージョニング")
              && location.getText().toString().contains("処理を終了"),
          "recovery dialog names the object and explains manual cleanup, versioning and retry");
      TagDetailChecks.require(
          location
              .getActionList()
              .contains(
                  android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                      .ACTION_SET_SELECTION),
          "temporary-object location text supports selection");
      capture(test, screen, "nano-background-missing-version-" + width + "-" + font);
      test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
      test.waitForIdleSync();
      store.job(
          new JSONObject()
              .put("stage", "submitting")
              .put("object", object)
              .put("owner", "synthetic-owner")
              .put("version", "synthetic-version"));
      TagDetailChecks.ui(
          test,
          () -> {
            menu[0] = NanoBananaUi.show(screen, input, store, NanoBananaUi.ACTUAL);
            View decor = menu[0].getWindow().getDecorView();
            TagDetailChecks.require(
                findLabel(decor, "依頼の結果が不明です。自動では再送しません") != null
                    && findLabel(decor, "状態を再確認") == null
                    && findLabel(decor, "後処理を再試行") == null,
                "ambiguous submission explicitly says unknown and never offers automatic resend");
          });
      capture(test, screen, "nano-background-submitting-" + width + "-" + font);
      TagDetailChecks.ui(test, () -> menu[0].dismiss());
      TagDetailChecks.require(
          missing.submits == 0 && missing.statuses == 0 && missing.downloads == 0,
          "opening recovery menus performs no provider request");
      // A dialog describes one durable request. Its confirmation must not target a replacement.
      Fake guarded = new Fake(store, jpeg, saved);
      factory.fake = guarded;
      String objectA = "loupe-nano-temp/00000000-0000-0000-0000-00000000010a.jpg",
          ownerA = "synthetic-owner-a";
      String objectB = "loupe-nano-temp/00000000-0000-0000-0000-00000000010b.jpg",
          ownerB = "synthetic-owner-b";
      store.job(
          new JSONObject()
              .put("stage", "submitting")
              .put("object", objectA)
              .put("owner", ownerA)
              .put("version", "synthetic-version-a"));
      TagDetailChecks.ui(
          test,
          () -> {
            menu[0] = NanoBananaUi.show(screen, input, store, NanoBananaUi.ACTUAL);
            View end = findLabel(menu[0].getWindow().getDecorView(), "処理を終了");
            TagDetailChecks.require(
                end != null && end.performClick(), "request A opens explicit end confirmation");
          });
      test.waitForIdleSync();
      android.view.accessibility.AccessibilityNodeInfo confirmation =
          findAccessibleExact(test.getUiAutomation().getRootInActiveWindow(), "終了");
      TagDetailChecks.require(
          confirmation != null && confirmation.isClickable(),
          "request A confirmation is visible before request replacement");
      JSONObject replacement =
          new JSONObject()
              .put("stage", "generating")
              .put("task", "synthetic-task-b")
              .put("object", objectB)
              .put("owner", ownerB)
              .put("version", "synthetic-version-b");
      store.job(replacement);
      String replacementText = store.job().toString();
      int jobsBeforeStale = factory.jobs;
      TagDetailChecks.require(
          confirmation.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),
          "stale request A confirmation clicked");
      test.waitForIdleSync();
      TagDetailChecks.ui(test, () -> {});
      test.waitForIdleSync();
      TagDetailChecks.require(
          !NanoBananaService.busy()
              && store.job() != null
              && store.job().toString().equals(replacementText)
              && factory.jobs == jobsBeforeStale
              && guarded.deletes == 0
              && guarded.submits == 0
              && guarded.statuses == 0
              && guarded.downloads == 0,
          "stale confirmation for A leaves request B unchanged without starting service or provider operations");
      TagDetailChecks.require(
          NanoBananaService.discard(screen, objectB, ownerB),
          "current request B identity admits cleanup");
      idle(test);
      TagDetailChecks.require(
          store.job() == null
              && guarded.deletes == 1
              && guarded.submits == 0
              && guarded.statuses == 0
              && guarded.downloads == 0
              && factory.jobs == jobsBeforeStale + 1,
          "current request cleanup removes B once without generation or polling");
      verifyDiscardWithRenamedPublishedResult(
          test, screen, store, factory, saved, input, prompt, jpeg);
      verifySubmitNoticeFailureReporting(test, screen, factory);
      Fake rateLimitedSubmit = new Fake(store, jpeg, saved);
      rateLimitedSubmit.submitApi = NanoBananaApiChecks.rateLimitedApi();
      Snackbar rateLimitNotice =
          verifySubmitFailureMessage(
              test,
              screen,
              factory,
              rateLimitedSubmit,
              input,
              prompt,
              NanoBananaApiChecks.SUBMIT_RATE_LIMIT,
              "429",
              width,
              font,
              null);
      Fake rateLimitedStatus = new Fake(store, jpeg, saved);
      rateLimitedStatus.statusApi = NanoBananaApiChecks.rateLimitedApi();
      verifyFailureMessage(
          test, screen, factory, rateLimitedStatus, NanoBananaApiChecks.RATE_LIMIT);
      for (String malformed : List.of("{}", "{\"successFlag\":1,\"response\":{}}")) {
        Fake invalidStatus = new Fake(store, jpeg, saved);
        invalidStatus.statusApi = NanoBananaApiChecks.statusApi(malformed);
        verifyFailureMessage(test, screen, factory, invalidStatus, NanoBananaApiChecks.API_FAILURE);
      }
      Fake arbitraryFailure = new Fake(store, jpeg, saved);
      arbitraryFailure.statusFailure =
          new IOException("synthetic-key https://synthetic.invalid/result?token=private");
      verifyFailureMessage(
          test, screen, factory, arbitraryFailure, "処理を続けられませんでした。AI編集メニューから確認してください");
      Fake rejectedSubmit = new Fake(store, jpeg, saved);
      rejectedSubmit.submitApi = NanoBananaApiChecks.rejectedApi();
      verifySubmitFailureMessage(
          test,
          screen,
          factory,
          rejectedSubmit,
          input,
          prompt,
          NanoBananaApiChecks.SUBMIT_API_REJECTED,
          "api-rejection",
          width,
          font,
          rateLimitNotice);
      String startupTrace = verifyStartupRejection(context, store, photo, prompt);
      return "PASS nano-background: actual production Android Service with isolated fake provider; next-photo operation, busy/error menu captures, Home continuation, no generation/result Activity launch, automatic JPEG save/readback 1200x800, temporary-object cleanup, duplicate admission, immutable prompt/output snapshot, next admission, saved cleanup retry without POST/download, interrupted-worker lease, durable discard retry and cleanup-only completion text, missing-version selectable manual recovery guidance, explicit ambiguous-submission messaging, stale end-confirmation A/B identity guard and current identity cleanup, stale Intent suppression and startup rejection, remote-first discard with a detached result volume, renamed published result retained on discard, MTP same-size corruption and read error checks, fixed API error delivery and suppression of arbitrary exception text. Provider remained blocked through Home after "
          + homeWaitMs
          + " ms. Invalid polling payload recovery and both POST failure Snackbar messages, confirmation, pending menu and explicit cleanup verified. Captures are synthetic layouts; OS-service runtime assertions are separate. No real API, credentials, photos, physical touch or TalkBack speech tested.\nStatus trace (delivery hook; fixed UI text only): "
          + String.join(" → ", factory.messages)
          + "\n"
          + startupTrace
          + "\n";
    } finally {
      if (factory.fake != null) factory.fake.release.countDown();
      context.stopService(new Intent(context, NanoBananaService.class));
      // Never restore production dependencies while a fixture worker could still run.
      idle(test);
      context.stopService(new Intent(context, NanoBananaService.class));
      test.waitForIdleSync();
      if (activity != null) {
        MainActivity screen = activity;
        TagDetailChecks.ui(test, screen::finish);
      }
      JSONObject remaining = store.job();
      if (remaining != null && remaining.has("saved")) saved.add(remaining.getString("saved"));
      for (String uri : saved) context.getContentResolver().delete(Uri.parse(uri), null, null);
      store.job(null);
      context.getSharedPreferences(namespace, 0).edit().clear().commit();
      context.getContentResolver().delete(fixture, null, null);
      NanoBananaService.dependencies = NanoBananaService.PRODUCTION;
    }
  }

  private static String verifyStartupRejection(
      Context context,
      NanoBananaStore store,
      PhotoRepository.Photo photo,
      NanoBananaStore.Prompt prompt)
      throws Exception {
    boolean busyBefore = NanoBananaService.busy();
    boolean jobBefore = store.job() != null;
    String before = "startup rejection: busyBefore=" + busyBefore + ", jobBefore=" + jobBefore;
    if (busyBefore || jobBefore) android.util.Log.e("LoupeNanoFixture", before);
    TagDetailChecks.require(!busyBefore && !jobBefore, "startup rejection requires idle service");

    class FailingStartContext extends ContextWrapper {
      int calls;
      boolean busyAtCallback;
      boolean jobAtCallback;
      boolean jobReadAtCallback;

      FailingStartContext() {
        super(context);
      }

      @Override
      public Context getApplicationContext() {
        return this;
      }

      @Override
      public ComponentName startForegroundService(Intent intent) {
        calls++;
        busyAtCallback = NanoBananaService.busy();
        try {
          jobAtCallback = store.job() != null;
          jobReadAtCallback = true;
        } catch (IOException error) {
          android.util.Log.e(
              "LoupeNanoFixture", "startup rejection callback cannot read fixture job");
        }
        throw new IllegalStateException("synthetic startup rejection");
      }
    }
    FailingStartContext failing = new FailingStartContext();
    boolean started = NanoBananaService.start(failing, photo, prompt);
    boolean busyAfter = NanoBananaService.busy();
    boolean jobAfter = store.job() != null;
    boolean rejectedAtStart =
        failing.calls == 1
            && failing.busyAtCallback
            && failing.jobReadAtCallback
            && !failing.jobAtCallback;
    boolean rolledBack = !started && !busyAfter && !jobAfter;
    String trace =
        before
            + ", callbacks="
            + failing.calls
            + ", busyAtCallback="
            + failing.busyAtCallback
            + ", jobReadAtCallback="
            + failing.jobReadAtCallback
            + ", jobAtCallback="
            + failing.jobAtCallback
            + ", started="
            + started
            + ", busyAfter="
            + busyAfter
            + ", jobAfter="
            + jobAfter;
    if (rejectedAtStart && rolledBack) android.util.Log.i("LoupeNanoFixture", trace);
    else android.util.Log.e("LoupeNanoFixture", trace);
    TagDetailChecks.require(
        rejectedAtStart, "startup rejection injected once with reserved service");
    TagDetailChecks.require(rolledBack, "startup rejection rolls back reservation");
    return trace;
  }

  private static void requireGenerationBlocked(NanoBananaStore store, Fake first, String checkpoint)
      throws Exception {
    JSONObject record = store.job();
    boolean generating = record != null && "generating".equals(record.optString("stage"));
    boolean leased = NanoBananaService.busy();
    boolean providerWaiting =
        first.entered.getCount() == 0
            && first.release.getCount() == 1
            && first.interrupted.getCount() == 1
            && !first.statusExited
            && first.downloads == 0;
    if (!leased || !generating || !providerWaiting) {
      // Emit only fixture booleans and durations. Instrumentation redacts arbitrary error text.
      android.util.Log.e(
          "LoupeNanoFixture",
          checkpoint
              + " lease="
              + leased
              + " generating="
              + generating
              + " timedOut="
              + first.statusTimedOut
              + " statusExited="
              + first.statusExited
              + " interrupted="
              + (first.interrupted.getCount() == 0)
              + " elapsedMs="
              + (SystemClock.elapsedRealtime() - first.statusEnteredAt));
      throw new AssertionError("Service and fake provider must still be running " + checkpoint);
    }
  }

  private static Snackbar verifySubmitFailureMessage(
      Instrumentation test,
      MainActivity screen,
      FixtureFactory factory,
      Fake network,
      PhotoRepository.Photo input,
      NanoBananaStore.Prompt prompt,
      String expected,
      String scenario,
      int width,
      float font,
      Snackbar priorShown)
      throws Exception {
    factory.fake = network;
    factory.lastResult = null;
    String capturePrefix = "nano-background-submit-" + scenario;
    String profile = "-" + width + "-" + font;
    SubmitFailureNotice notice = new SubmitFailureNotice(screen, expected, priorShown);
    String noticeName = capturePrefix + "-notice" + profile;
    try (AutoCloseable observer = () -> TagDetailChecks.ui(test, () -> notice.disarm(factory))) {
      TagDetailChecks.ui(test, () -> notice.arm(factory));
      TagDetailChecks.require(
          NanoBananaService.start(screen, input, prompt), "start provider POST");
      captureAndOpenSubmitFailureNotice(
          test,
          notice,
          noticeName,
          width,
          font,
          new NoticeEvidence(
              () -> saveCapture(test, noticeName),
              () -> saveDisplayCapture(test, capturePrefix + "-notice-display" + profile),
              (observations, verdict) -> saveNoticeTrace(test, noticeName, observations, verdict)));
    }
    idle(test);
    JSONObject pending = factory.store.job();
    TagDetailChecks.require(
        expected.equals(factory.lastResult),
        "service offers the pending submission's real recovery actions");
    TagDetailChecks.require(
        network.submits == 1
            && network.statuses == 0
            && pending != null
            && pending.optString("stage").equals("submitting")
            && !pending.has("task"),
        "failed POST retains ambiguous state and does not poll or resend");
    View[] pendingWindow = {null};
    GenerationIndicatorChecks.await(
        test,
        "Snackbar confirmation opens the ambiguous submission menu",
        () -> {
          View heading = findWindowLabel("依頼の結果が不明です。自動では再送しません");
          if (heading == null || !heading.isShown() || !heading.hasWindowFocus()) return false;
          pendingWindow[0] = heading.getRootView();
          return true;
        });
    View menuWindow = pendingWindow[0];
    TagDetailChecks.ui(test, () -> verifyMenuTargets(menuWindow));
    captureWindow(test, menuWindow, capturePrefix + "-menu" + profile);
    saveDisplayCapture(test, capturePrefix + "-menu-display" + profile);
    TagDetailChecks.ui(
        test,
        () -> {
          View end = findLabel(menuWindow, "処理を終了");
          TagDetailChecks.require(
              findLabel(menuWindow, "設定") != null
                  && findLabel(menuWindow, "状態を再確認") == null
                  && findLabel(menuWindow, "後処理を再試行") == null
                  && end != null
                  && end.performClick(),
              "the ambiguous submission menu offers end and settings without retry");
        });
    test.waitForIdleSync();
    android.view.accessibility.AccessibilityNodeInfo confirmation =
        findAccessibleExact(test.getUiAutomation().getRootInActiveWindow(), "終了");
    TagDetailChecks.require(
        confirmation != null
            && confirmation.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),
        "explicit end confirmation starts cleanup");
    await("end confirmation reaches cleanup worker", () -> network.deletes > 0);
    idle(test);
    TagDetailChecks.require(
        factory.store.job() == null
            && network.deletes == 1
            && network.submits == 1
            && network.statuses == 0
            && network.downloads == 0,
        "explicit end cleans the temporary input without resubmitting, polling or downloading");
    return notice.bar;
  }

  private static final class SubmitFailureNotice extends Snackbar.Callback
      implements View.OnAttachStateChangeListener {
    final MainActivity screen;
    final String expected;
    final Snackbar priorShown;
    final Field instance = indicatorField("instance");
    final Field snackbarField = indicatorField("snackbar");
    final Field resumedField = indicatorField("resumed");
    final Field hostField = indicatorField("host");
    final Field messageField = indicatorField("message");
    final Field stateField = indicatorField("state");
    final Handler main = new Handler(Looper.getMainLooper());
    final Runnable registration = this::register;
    final CountDownLatch terminal = new CountDownLatch(1);
    final LinkedHashSet<String> observations = new LinkedHashSet<>();
    View decor;
    Snackbar beforeStart;
    Snackbar bar;
    TextView text;
    Button action;
    Throwable failure;
    boolean active;
    boolean registrationPosted;

    SubmitFailureNotice(MainActivity screen, String expected, Snackbar priorShown)
        throws ReflectiveOperationException {
      this.screen = screen;
      this.expected = expected;
      this.priorShown = priorShown;
    }

    private Snackbar currentBar(Object indicator) throws IllegalAccessException {
      return indicator == null ? null : (Snackbar) snackbarField.get(indicator);
    }

    void arm(FixtureFactory factory) {
      TagDetailChecks.require(
          factory.submitNotice == null, "no other submit notice observer armed");
      try {
        beforeStart = currentBar(instance.get(null));
      } catch (IllegalAccessException error) {
        throw new AssertionError(error);
      }
      decor = screen.getWindow().getDecorView();
      active = true;
      factory.submitNotice = this;
      observations.add("armed before provider POST");
    }

    void onResult() {
      if (Looper.myLooper() != main.getLooper()) {
        main.post(
            () -> {
              if (active) fail(new AssertionError("expected submit result on the main looper"));
            });
        return;
      }
      if (!active || failure != null) return;
      if (registrationPosted) {
        fail(new AssertionError("duplicate matching submit result"));
        return;
      }
      registrationPosted = true;
      observations.add("matched fixture result");
      // The service has posted the indicator update. That update creates the notice and queues
      // Material's SHOW behind this registration, even if the notice will show without animation.
      if (!main.post(registration))
        fail(new AssertionError("could not queue Snackbar registration"));
    }

    private void register() {
      if (!active || failure != null) return;
      try {
        Object indicator = instance.get(null);
        if (indicator == null)
          throw new AssertionError("generation indicator absent at registration");
        Snackbar candidate = currentBar(indicator);
        Activity resumed = (Activity) resumedField.get(indicator);
        View host = (View) hostField.get(indicator);
        boolean messageMatches = expected.equals(messageField.get(indicator));
        observations.add(
            "registration state="
                + ((Enum<?>) stateField.get(indicator)).name()
                + ", resumed="
                + (resumed == null ? "none" : resumed == screen ? "fixture" : "other")
                + ", host="
                + (host == null ? "none" : host == decor ? "activity" : "other-window")
                + ", hostFocused="
                + (host != null && host.hasWindowFocus())
                + ", messageMatches="
                + messageMatches
                + ", snackbar="
                + (candidate != null)
                + ", queuedOrShowing="
                + (candidate != null && candidate.isShownOrQueued()));
        TagDetailChecks.require(
            resumed == screen
                && host != null
                && messageMatches
                && candidate != null
                && candidate != beforeStart
                && candidate.isShownOrQueued(),
            "matching new Snackbar unavailable at registration");
        bar = candidate;
        text = bar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        action = bar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
        requireIdentity();
        TagDetailChecks.require(
            !bar.getView().isAttachedToWindow(),
            "registration reached the Snackbar after attachment");
        if (priorShown != null) {
          TagDetailChecks.require(
              priorShown != bar && !priorShown.getView().isAttachedToWindow(),
              "previous scenario's Snackbar must already be detached");
          // Replay delayed events from the previous real notice against the active observer.
          onDismissed(priorShown, DISMISS_EVENT_ACTION);
          onShown(priorShown);
          TagDetailChecks.require(
              terminal.getCount() == 1,
              "previous scenario's dismissed Snackbar released this scenario's wait");
        }
        bar.getView().addOnAttachStateChangeListener(this);
        bar.addCallback(this);
        observations.add("callback registered before attachment");
      } catch (ReflectiveOperationException | RuntimeException | AssertionError error) {
        fail(error);
      }
    }

    private void requireIdentity() throws IllegalAccessException {
      Object indicator = instance.get(null);
      TagDetailChecks.require(
          bar != null
              && currentBar(indicator) == bar
              && resumedField.get(indicator) == screen
              && expected.equals(messageField.get(indicator))
              && text != null
              && expected.contentEquals(text.getText())
              && text == bar.getView().findViewById(com.google.android.material.R.id.snackbar_text)
              && action != null
              && "確認".contentEquals(action.getText())
              && action
                  == bar.getView().findViewById(com.google.android.material.R.id.snackbar_action),
          "Snackbar, expected message, text or confirmation action is no longer current");
    }

    void requireVisible() throws IllegalAccessException {
      requireIdentity();
      TagDetailChecks.require(
          bar.isShown()
              && text.isAttachedToWindow()
              && text.isShown()
              && text.hasWindowFocus()
              && text.getLayout() != null
              && action.isAttachedToWindow()
              && action.isShown()
              && action.hasWindowFocus(),
          "matching Snackbar is detached, hidden, unlaid-out or unfocused");
    }

    @Override
    public void onViewAttachedToWindow(View view) {
      if (!active || failure != null) return;
      try {
        requireIdentity();
        // Material already reports isShown() at attachment, before its shown callback.
        TagDetailChecks.require(
            bar.isShown() && terminal.getCount() == 1,
            "attaching the current Snackbar released the wait before onShown");
        observations.add(
            "current Snackbar attached and manager isShown=true while wait is pending");
      } catch (IllegalAccessException | RuntimeException | AssertionError error) {
        fail(error);
      }
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
      if (active) fail(new AssertionError("matching Snackbar detached during observation"));
    }

    @Override
    public void onShown(Snackbar shown) {
      if (!active || failure != null) return;
      if (shown != bar) {
        observations.add("ignored onShown from another Snackbar");
        return;
      }
      try {
        TagDetailChecks.require(terminal.getCount() == 1, "duplicate matching Snackbar onShown");
        requireVisible();
        observations.add("onShown for current Snackbar with matching text and action");
        terminal.countDown();
      } catch (IllegalAccessException | RuntimeException | AssertionError error) {
        fail(error);
      }
    }

    @Override
    public void onDismissed(Snackbar dismissed, int event) {
      if (!active) return;
      if (dismissed == bar)
        fail(new AssertionError("matching Snackbar dismissed during observation, event=" + event));
      else observations.add("ignored onDismissed from another Snackbar, event=" + event);
    }

    private void fail(Throwable error) {
      if (failure == null) failure = error;
      observations.add("failed: " + error);
      terminal.countDown();
    }

    void disarm(FixtureFactory factory) {
      active = false;
      if (factory.submitNotice == this) factory.submitNotice = null;
      main.removeCallbacksAndMessages(null);
      if (bar != null) {
        bar.removeCallback(this);
        bar.getView().removeOnAttachStateChangeListener(this);
      }
    }
  }

  @FunctionalInterface
  private interface NoticeCheck {
    void run() throws Exception;
  }

  @FunctionalInterface
  private interface NoticeTrace {
    void save(LinkedHashSet<String> observations, String verdict) throws IOException;
  }

  private record NoticeEvidence(NoticeCheck screenshot, NoticeCheck display, NoticeTrace trace) {}

  private static void captureAndOpenSubmitFailureNotice(
      Instrumentation test,
      SubmitFailureNotice notice,
      String name,
      int width,
      float font,
      NoticeEvidence captures) {
    Throwable failure;
    try {
      failure = awaitShownNotice(test, notice);
    } catch (Throwable error) {
      failure = error;
    }
    List<Throwable> evidenceErrors = new ArrayList<>();
    boolean frameCommitted = false;
    if (failure == null) {
      frameCommitted =
          collectNoticeEvidence(
              evidenceErrors, "settled Snackbar frame", () -> commitNoticeFrame(test, notice));
      try {
        failure = verifyNoticeForCapture(test, notice, width, font, frameCommitted);
      } catch (Throwable error) {
        failure = error;
      }
    }
    // Failure screenshots are best effort, including when there is no attached notice.
    collectNoticeEvidence(evidenceErrors, "notice screenshot", captures.screenshot());
    collectNoticeEvidence(evidenceErrors, "notice display screenshot", captures.display());
    if (failure == null && frameCommitted) {
      try {
        failure = verifyNoticeConfirmation(test, notice);
      } catch (Throwable error) {
        failure = error;
      }
    }
    String verdict;
    if (failure != null) verdict = failure.toString();
    else if (frameCommitted) verdict = "layout fits\nSnackbar confirmation clicked";
    else verdict = "text fit and confirmation skipped because no settled frame was confirmed";
    collectNoticeEvidence(
        evidenceErrors,
        "notice trace",
        () -> {
          LinkedHashSet<String> trace = new LinkedHashSet<>();
          TagDetailChecks.ui(test, () -> trace.addAll(notice.observations));
          for (Throwable error : evidenceErrors)
            trace.add("capture evidence failed: " + error.getMessage());
          captures.trace().save(trace, verdict);
        });
    if (failure == null && evidenceErrors.isEmpty()) return;
    AssertionError report =
        new AssertionError(
            "POST failure Snackbar check failed: " + name,
            failure != null ? failure : evidenceErrors.remove(0));
    for (Throwable error : evidenceErrors) report.addSuppressed(error);
    throw report;
  }

  private static boolean collectNoticeEvidence(
      List<Throwable> errors, String label, NoticeCheck check) {
    try {
      check.run();
      return true;
    } catch (Throwable error) {
      errors.add(new AssertionError("Could not collect " + label, error));
      return false;
    }
  }

  private static Throwable awaitShownNotice(Instrumentation test, SubmitFailureNotice notice)
      throws InterruptedException {
    boolean signaled = notice.terminal.await(10, TimeUnit.SECONDS);
    Throwable[] issue = {null};
    TagDetailChecks.ui(
        test,
        () -> {
          if (!signaled)
            notice.fail(new AssertionError("timed out waiting for matching Snackbar onShown"));
          issue[0] = notice.failure;
        });
    return issue[0];
  }

  private static Throwable verifyNoticeForCapture(
      Instrumentation test,
      SubmitFailureNotice notice,
      int width,
      float font,
      boolean frameCommitted) {
    Throwable[] issue = {null};
    TagDetailChecks.ui(
        test,
        () -> {
          if (notice.failure != null) {
            issue[0] = notice.failure;
            return;
          }
          TextView text = notice.text;
          Button action = notice.action;
          try {
            notice.requireVisible();
          } catch (IllegalAccessException | RuntimeException | AssertionError error) {
            logSubmitNoticeGeometry("lifecycle", notice.expected, width, font, true, text, action);
            logSubmitNoticeGate(
                "lifecycle",
                notice.expected,
                width,
                font,
                notice.decor,
                text,
                action,
                text.getLayout(),
                null);
            issue[0] = error;
            return;
          }
          // A frame failure can still coincide with an observer or lifecycle failure above.
          if (!frameCommitted) return;
          NoticeTextFit fit = readNoticeTextFit(text);
          boolean rejected = fit.rejected(text);
          logSubmitNoticeGeometry("text_fit", notice.expected, width, font, rejected, text, action);
          notice.observations.add(
              "text fit after onShown: rejected="
                  + rejected
                  + ", visible="
                  + fit.visible().width()
                  + "x"
                  + fit.visible().height()
                  + ", local="
                  + text.getWidth()
                  + "x"
                  + text.getHeight());
          if (rejected) {
            issue[0] =
                new AssertionError(
                    "lines="
                        + fit.lines()
                        + ", ellipsized="
                        + fit.ellipses()
                        + ", lineEnd="
                        + fit.end()
                        + "/"
                        + text.length()
                        + ", lineFits="
                        + fit.lineFits()
                        + ", boundsFit="
                        + fit.boundsFit());
            logSubmitNoticeGate(
                "text_fit",
                notice.expected,
                width,
                font,
                notice.decor,
                text,
                action,
                text.getLayout(),
                fit);
          } else if (notice.priorShown != null) {
            // Exercise the negative fit control once, on the second settled notice.
            try {
              verifyClippedNoticeRejected(notice, fit);
            } catch (RuntimeException | AssertionError error) {
              issue[0] = error;
            }
          }
        });
    return issue[0];
  }

  private static void commitNoticeFrame(Instrumentation test, SubmitFailureNotice notice)
      throws Exception {
    CountDownLatch committed = new CountDownLatch(1);
    Runnable callback = committed::countDown;
    ViewTreeObserver[] tree = {null};
    try (AutoCloseable unregister =
        () ->
            TagDetailChecks.ui(
                test,
                () -> {
                  if (tree[0] != null && tree[0].isAlive())
                    tree[0].unregisterFrameCommitCallback(callback);
                })) {
      TagDetailChecks.ui(
          test,
          () -> {
            requireCurrentNoticeForCapture(notice);
            View root = notice.text.getRootView();
            TagDetailChecks.require(
                root.isHardwareAccelerated(), "notice capture requires an attached hardware root");
            tree[0] = root.getViewTreeObserver();
            tree[0].registerFrameCommitCallback(callback);
            root.requestLayout();
            root.postInvalidateOnAnimation();
          });
      boolean rendered = committed.await(5, TimeUnit.SECONDS);
      TagDetailChecks.ui(
          test,
          () -> {
            requireCurrentNoticeForCapture(notice);
            TagDetailChecks.require(rendered, "settled Snackbar frame commit timed out");
            notice.observations.add(
                "settled frame committed for current Snackbar before notice captures");
          });
    }
  }

  private static void requireCurrentNoticeForCapture(SubmitFailureNotice notice) {
    try {
      if (notice.failure != null) throw new AssertionError(notice.failure);
      notice.requireVisible();
    } catch (IllegalAccessException error) {
      throw new AssertionError(error);
    }
  }

  private static Throwable verifyNoticeConfirmation(
      Instrumentation test, SubmitFailureNotice notice) {
    Throwable[] issue = {null};
    TagDetailChecks.ui(
        test,
        () -> {
          if (notice.failure != null) {
            issue[0] = notice.failure;
            return;
          }
          try {
            notice.requireVisible();
            Button confirm = notice.action;
            Rect visible = new Rect();
            TagDetailChecks.require(
                confirm.isEnabled()
                    && confirm.isClickable()
                    && confirm.getWidth() >= GalleryUi.dp(confirm.getContext(), 48)
                    && confirm.getHeight() >= GalleryUi.dp(confirm.getContext(), 48)
                    && confirm.getGlobalVisibleRect(visible)
                    && visible.width() == confirm.getWidth()
                    && visible.height() == confirm.getHeight()
                    && confirm.performClick(),
                "Snackbar confirmation is not a fully visible action");
            // The action itself dismisses this bar. Observation ends once the click succeeds.
            notice.active = false;
          } catch (IllegalAccessException | RuntimeException | AssertionError error) {
            issue[0] = error;
          }
        });
    return issue[0];
  }

  private static void verifySubmitNoticeFailureReporting(
      Instrumentation test, MainActivity screen, FixtureFactory factory) throws Exception {
    SubmitFailureNotice observer =
        new SubmitFailureNotice(screen, NanoBananaApiChecks.SUBMIT_RATE_LIMIT, null);
    AssertionError observerError = new AssertionError("synthetic submit observer failure");
    IOException screenshotError = new IOException("synthetic notice screenshot write failure");
    IOException traceError = new IOException("synthetic notice trace write failure");
    boolean[] displayAttempted = {false};
    try (AutoCloseable disarm = () -> TagDetailChecks.ui(test, () -> observer.disarm(factory))) {
      TagDetailChecks.ui(
          test,
          () -> {
            observer.arm(factory);
            observer.fail(observerError);
          });
      captureAndOpenSubmitFailureNotice(
          test,
          observer,
          "injected evidence failures",
          0,
          0f,
          new NoticeEvidence(
              () -> {
                throw screenshotError;
              },
              () -> displayAttempted[0] = true,
              (trace, verdict) -> {
                TagDetailChecks.require(
                    trace.contains("failed: " + observerError)
                        && verdict.equals(observerError.toString()),
                    "trace receives the observer failure even when screenshot writing fails");
                throw traceError;
              }));
      throw new AssertionError("injected submit observer failure did not propagate");
    } catch (AssertionError reported) {
      Throwable[] suppressed = reported.getSuppressed();
      if (reported.getCause() != observerError
          || suppressed.length != 2
          || suppressed[0].getCause() != screenshotError
          || suppressed[1].getCause() != traceError
          || !displayAttempted[0]) {
        throw new AssertionError(
            "submit notice failure reporting lost the original or evidence failures", reported);
      }
    }
    TagDetailChecks.ui(
        test,
        () ->
            TagDetailChecks.require(
                !observer.active && factory.submitNotice == null,
                "submit observer is disarmed after combined observer and evidence failures"));
    android.util.Log.i(
        "LoupeNanoFixture",
        "submit_notice_failure_control preserved the observer error, attempted both captures and trace, and disarmed");
  }

  private static NoticeTextFit readNoticeTextFit(TextView text) {
    Layout layout = text.getLayout();
    int lines = layout.getLineCount();
    int end = lines == 0 ? 0 : layout.getLineEnd(lines - 1);
    int ellipses = 0;
    boolean lineFits = true;
    for (int line = 0; line < lines; line++) {
      ellipses += layout.getEllipsisCount(line);
      lineFits &=
          layout.getLineLeft(line) >= -1 && layout.getLineRight(line) <= layout.getWidth() + 1;
    }
    Rect visible = new Rect();
    boolean globalVisible = text.getGlobalVisibleRect(visible);
    boolean boundsFit =
        globalVisible
            && visible.width() == text.getWidth()
            && visible.height() == text.getHeight()
            && layout.getHeight() + text.getTotalPaddingTop() + text.getTotalPaddingBottom()
                <= text.getHeight();
    return new NoticeTextFit(lines, end, ellipses, lineFits, visible, globalVisible, boundsFit);
  }

  private static void verifyClippedNoticeRejected(
      SubmitFailureNotice notice, NoticeTextFit normal) {
    TextView text = notice.text;
    Rect root = new Rect();
    TagDetailChecks.require(
        text.getRootView().getGlobalVisibleRect(root), "Snackbar root is visible");
    float translation = text.getTranslationX();
    NoticeTextFit clipped;
    try {
      text.setTranslationX(translation - (normal.visible().left - root.left) - 2);
      clipped = readNoticeTextFit(text);
      TagDetailChecks.require(
          clipped.globalVisible()
              && normal.visible().width() - clipped.visible().width() >= 2
              && clipped.rejected(text),
          "strict text fit should reject a shown Snackbar clipped by at least two pixels");
    } finally {
      text.setTranslationX(translation);
    }
    NoticeTextFit restored = readNoticeTextFit(text);
    TagDetailChecks.require(!restored.rejected(text), "restored shown Snackbar must fit exactly");
    notice.observations.add(
        "post-show clipping rejected by the exact fit gate: visible width "
            + normal.visible().width()
            + " -> "
            + clipped.visible().width()
            + " -> "
            + restored.visible().width());
  }

  private record NoticeTextFit(
      int lines,
      int end,
      int ellipses,
      boolean lineFits,
      Rect visible,
      boolean globalVisible,
      boolean boundsFit) {
    boolean rejected(TextView text) {
      return ellipses != 0 || end != text.length() || !lineFits || !boundsFit;
    }
  }

  private static void logSubmitNoticeGate(
      String phase,
      String expected,
      int requestedWidth,
      float requestedFont,
      View decor,
      TextView text,
      Button action,
      Layout layout,
      NoticeTextFit fit) {
    runNoticeDiagnostic(
        () ->
            readSubmitNoticeGate(
                phase, expected, requestedWidth, requestedFont, decor, text, action, layout, fit));
  }

  private static void runNoticeDiagnostic(Runnable diagnostic) {
    try {
      diagnostic.run();
    } catch (RuntimeException | AssertionError unavailable) {
      // Optional diagnostics never determine the notice verdict.
    }
  }

  private static void logSubmitNoticeGeometry(
      String phase,
      String expected,
      int width,
      float font,
      boolean rejected,
      View text,
      View action) {
    runNoticeDiagnostic(
        () -> {
          String scenario =
              expected.equals(NanoBananaApiChecks.SUBMIT_RATE_LIMIT)
                  ? "rate_limit"
                  : "api_rejection";
          String sample =
              " scenario="
                  + scenario
                  + " phase="
                  + phase
                  + " rejected="
                  + rejected
                  + " reqWidthDp="
                  + width
                  + " reqFont="
                  + font;
          logNoticeViewChain(sample + " chain=text", text);
          logNoticeViewChain(sample + " chain=action", action);
        });
  }

  private static void logNoticeViewChain(String sample, View leaf) {
    final int maxNodes = 32;
    View node = leaf;
    int depth = 0;
    boolean reachedRoot = false;
    while (node != null && depth < maxNodes) {
      ViewParent parent = node.getParent();
      boolean root = !(parent instanceof View);
      boolean attached = node.isAttachedToWindow();
      ViewGroup group = node instanceof ViewGroup vg ? vg : null;
      float[] matrix = new float[9];
      node.getMatrix().getValues(matrix);
      Rect clip = node.getClipBounds();
      Rect visible = new Rect();
      Point offset = new Point();
      boolean globalVisible = node.getGlobalVisibleRect(visible, offset);
      String rootGeometry = "";
      if (root) {
        int[] screen = new int[2];
        int[] window = new int[2];
        node.getLocationOnScreen(screen);
        node.getLocationInWindow(window);
        // Query the terminal ViewParent's virtual canvas through its public visibility API.
        Rect canvas = new Rect(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE);
        boolean canvasVisible =
            attached && parent != null && parent.getChildVisibleRect(node, canvas, null);
        rootGeometry =
            " rootScreen="
                + Arrays.toString(screen)
                + " rootWindow="
                + Arrays.toString(window)
                + " canvasVisible="
                + canvasVisible
                + " canvasClip="
                + (canvasVisible ? canvas.toShortString() : "none");
      }
      android.util.Log.e(
          "LoupeNanoFixture",
          "submit_notice_geometry_node"
              + sample
              + " depth="
              + depth
              + " root="
              + root
              + " attached="
              + attached
              + " hasParent="
              + (parent != null)
              + " ltwh=["
              + node.getLeft()
              + ","
              + node.getTop()
              + ","
              + node.getWidth()
              + ","
              + node.getHeight()
              + "]"
              + " scroll=["
              + node.getScrollX()
              + ","
              + node.getScrollY()
              + "]"
              + " padding=["
              + node.getPaddingLeft()
              + ","
              + node.getPaddingTop()
              + ","
              + node.getPaddingRight()
              + ","
              + node.getPaddingBottom()
              + "]"
              + " matrix="
              + Arrays.toString(matrix)
              + " group="
              + (group != null)
              + " clipChildren="
              + (group != null && group.getClipChildren())
              + " clipToPadding="
              + (group != null && group.getClipToPadding())
              + " clipToOutline="
              + node.getClipToOutline()
              + " clipBounds="
              + (clip == null ? "none" : clip.toShortString())
              + " globalVisible="
              + globalVisible
              + " visibleRoot="
              + visible.toShortString()
              + " offset=["
              + offset.x
              + ","
              + offset.y
              + "]"
              + rootGeometry);
      depth++;
      reachedRoot = root;
      node = parent instanceof View parentView ? parentView : null;
    }
    android.util.Log.e(
        "LoupeNanoFixture",
        "submit_notice_geometry_end"
            + sample
            + " present="
            + (leaf != null)
            + " nodes="
            + depth
            + " complete="
            + reachedRoot
            + " truncated="
            + (node != null));
  }

  private static void readSubmitNoticeGate(
      String phase,
      String expected,
      int requestedWidth,
      float requestedFont,
      View decor,
      TextView text,
      Button action,
      Layout layout,
      NoticeTextFit fit) {
    View currentHost = null;
    View currentText = null;
    View currentAction = null;
    View currentAnchor = null;
    boolean currentRead = false;
    try {
      Object indicator = indicatorField("instance").get(null);
      if (indicator != null) {
        currentHost = (View) indicatorField("host").get(indicator);
        var snackbar = (Snackbar) indicatorField("snackbar").get(indicator);
        if (snackbar != null) {
          currentText =
              snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
          currentAction =
              snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
          currentAnchor = snackbar.getAnchorView();
        }
      }
      currentRead = true;
    } catch (ReflectiveOperationException unavailable) {
      // Geometry for the acquired view still identifies the failing gate.
    }
    var resources = text.getResources();
    var config = resources.getConfiguration();
    var metrics = resources.getDisplayMetrics();
    Rect visible = fit == null ? new Rect() : fit.visible();
    boolean globalVisible = fit == null ? text.getGlobalVisibleRect(visible) : fit.globalVisible();
    int lines = fit != null ? fit.lines() : layout == null ? 0 : layout.getLineCount();
    int end = fit != null ? fit.end() : lines == 0 ? 0 : layout.getLineEnd(lines - 1);
    int ellipses = fit == null ? 0 : fit.ellipses();
    float minLeft = 0;
    float maxRight = 0;
    for (int line = 0; line < lines; line++) {
      float left = layout.getLineLeft(line);
      float right = layout.getLineRight(line);
      minLeft = line == 0 ? left : Math.min(minLeft, left);
      maxRight = line == 0 ? right : Math.max(maxRight, right);
      if (fit == null) ellipses += layout.getEllipsisCount(line);
    }
    int layoutWidth = layout == null ? -1 : layout.getWidth();
    int layoutHeight = layout == null ? -1 : layout.getHeight();
    int padTop = layout == null ? -1 : text.getTotalPaddingTop();
    int padBottom = layout == null ? -1 : text.getTotalPaddingBottom();
    String scenario =
        expected.equals(NanoBananaApiChecks.SUBMIT_RATE_LIMIT) ? "rate_limit" : "api_rejection";
    android.util.Log.e(
        "LoupeNanoFixture",
        "submit_notice_gate scenario="
            + scenario
            + " phase="
            + phase
            + " reqWidthDp="
            + requestedWidth
            + " reqFont="
            + requestedFont
            + " configWidthDp="
            + config.screenWidthDp
            + " configHeightDp="
            + config.screenHeightDp
            + " decorWidthDp="
            + Math.round(decor.getWidth() / metrics.density)
            + " font="
            + config.fontScale
            + " density="
            + metrics.density
            + " attached="
            + text.isAttachedToWindow()
            + " shown="
            + text.isShown()
            + " focus="
            + text.hasWindowFocus()
            + " hasLayout="
            + (layout != null)
            + " currentRead="
            + currentRead
            + " sameTextView="
            + (text == currentText)
            + " sameAction="
            + (action != null && action == currentAction)
            + " lines="
            + lines
            + " ellipses="
            + ellipses
            + " end="
            + end
            + " currentLength="
            + text.length()
            + " expectedLength="
            + expected.length()
            + " textMatches="
            + expected.contentEquals(text.getText())
            + " minLeft="
            + minLeft
            + " maxRight="
            + maxRight
            + " layoutWidth="
            + layoutWidth
            + " fitSampled="
            + (fit != null)
            + " lineFits="
            + (fit != null && fit.lineFits())
            + " globalVisible="
            + globalVisible
            + " visibleRoot="
            + visible.toShortString()
            + " fullWidth="
            + (globalVisible && visible.width() == text.getWidth())
            + " fullHeight="
            + (globalVisible && visible.height() == text.getHeight())
            + " layoutHeight="
            + layoutHeight
            + " padTop="
            + padTop
            + " padBottom="
            + padBottom
            + " paddedHeightFit="
            + (layout != null && layoutHeight + padTop + padBottom <= text.getHeight())
            + " decorScreen="
            + noticeScreenBounds(decor)
            + " hostScreen="
            + noticeScreenBounds(currentHost)
            + " anchorScreen="
            + noticeScreenBounds(currentAnchor)
            + " textScreen="
            + noticeScreenBounds(text)
            + " actionScreen="
            + noticeScreenBounds(action));
  }

  private static String noticeScreenBounds(View view) {
    if (view == null) return "none";
    int[] location = new int[2];
    view.getLocationOnScreen(location);
    return new Rect(
            location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight())
        .toShortString();
  }

  private static void saveNoticeTrace(
      Instrumentation test, String name, LinkedHashSet<String> observations, String layout)
      throws IOException {
    String trace = String.join("\n", observations) + "\n" + layout + "\n";
    android.util.Log.i("LoupeNanoFixture", name + " Snackbar states\n" + trace);
    try (FileOutputStream output =
        new FileOutputStream(new File(captureDirectory(test), name + ".txt"))) {
      output.write(trace.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static Field indicatorField(String name) throws NoSuchFieldException {
    Field field = GenerationIndicator.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static void verifyFailureMessage(
      Instrumentation test,
      MainActivity screen,
      FixtureFactory factory,
      Fake network,
      String message)
      throws Exception {
    factory.fake = network;
    factory.lastResult = null;
    factory.store.job(
        new JSONObject()
            .put("stage", "generating")
            .put("task", "synthetic-status-failure")
            .put("object", "loupe-nano-temp/00000000-0000-0000-0000-000000000109.jpg")
            .put("owner", "synthetic-status-owner")
            .put("version", "synthetic-version"));
    try {
      TagDetailChecks.require(NanoBananaService.resume(screen), "resume a failing status request");
      idle(test);
      TagDetailChecks.require(
          message.equals(factory.lastResult),
          "service delivers only the expected safe status failure");
      JSONObject pending = factory.store.job();
      TagDetailChecks.require(
          network.statuses == 1
              && network.submits == 0
              && network.downloads == 0
              && network.deletes == 0
              && pending != null
              && pending.optString("stage").equals("generating")
              && pending.optString("task").equals("synthetic-status-failure"),
          "status failure preserves the job and does not generate or download");
    } finally {
      factory.store.job(null);
    }
  }

  private static void verifyDiscardWithRenamedPublishedResult(
      Instrumentation test,
      MainActivity activity,
      NanoBananaStore store,
      FixtureFactory factory,
      List<String> saved,
      PhotoRepository.Photo input,
      NanoBananaStore.Prompt prompt,
      byte[] jpeg)
      throws Exception {
    ContentResolver resolver = activity.getContentResolver();
    Uri collection = MediaStore.Images.Media.getContentUri(PhotoStorage.PRIMARY);
    ContentValues row = new ContentValues();
    row.put(MediaStore.MediaColumns.DISPLAY_NAME, "NanoBanana-" + UUID.randomUUID() + ".jpg");
    row.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LoupeNanoBanana/");
    row.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
    row.put(MediaStore.MediaColumns.IS_PENDING, 1);
    Uri published = resolver.insert(collection, row);
    TagDetailChecks.require(published != null, "Recovered result fixture inserted");
    try {
      try (java.io.OutputStream output = resolver.openOutputStream(published)) {
        TagDetailChecks.require(output != null, "Recovered result fixture opened");
        output.write(jpeg);
      }
      ContentValues publish = new ContentValues();
      publish.put(MediaStore.MediaColumns.IS_PENDING, 0);
      TagDetailChecks.require(
          resolver.update(published, publish, null, null) == 1,
          "Recovered result fixture published");
      String renamed = "nano-discard-relocated-" + UUID.randomUUID() + ".jpg";
      String relocated = "Pictures/LoupeSyntheticChecks/";
      ContentValues move = new ContentValues();
      move.put(MediaStore.MediaColumns.DISPLAY_NAME, renamed);
      move.put(MediaStore.MediaColumns.RELATIVE_PATH, relocated);
      TagDetailChecks.require(
          resolver.update(published, move, null, null) == 1, "Published result renamed and moved");
      Fake discard = new Fake(store, jpeg, saved);
      factory.fake = discard;
      store.job(
          new JSONObject()
              .put("stage", "generating")
              .put("task", "synthetic-published-result")
              .put("object", "loupe-nano-temp/00000000-0000-0000-0000-000000000108.jpg")
              .put("owner", "synthetic-published-owner")
              .put("version", "synthetic-version")
              .put("photo_volume", PhotoStorage.PRIMARY)
              .put("pending", published.toString()));
      TagDetailChecks.require(
          NanoBananaService.discard(activity), "Discard renamed published result admitted");
      idle(test);
      TagDetailChecks.require(
          store.job() == null
              && discard.deletes == 1
              && discard.submits == 0
              && discard.statuses == 0
              && discard.downloads == 0,
          "Discard closes job and removes only the remote input");
      try (android.database.Cursor current =
          resolver.query(
              published,
              new String[] {
                MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH
              },
              null,
              null,
              null)) {
        TagDetailChecks.require(
            current != null
                && current.moveToFirst()
                && current.getInt(0) == 0
                && renamed.equals(current.getString(1))
                && relocated.equals(current.getString(2)),
            "The relocated result remains published with its current name and folder");
      }
      try (InputStream bytes = resolver.openInputStream(published)) {
        TagDetailChecks.require(
            bytes != null && Arrays.equals(jpeg, bytes.readAllBytes()),
            "Discard leaves the published JPEG bytes intact");
      }
      Fake next = new Fake(store, jpeg, saved);
      next.release.countDown();
      factory.fake = next;
      TagDetailChecks.require(
          NanoBananaService.start(activity, input, prompt), "New job admitted after discard");
      idle(test);
      TagDetailChecks.require(
          next.submits == 1 && next.downloads == 1 && next.deletes == 1 && store.job() == null,
          "New job completes after renamed published-result discard");
    } finally {
      resolver.delete(published, null, null);
    }
  }

  private static void verifyMtpDeletionProof() throws Exception {
    byte[] staged = new byte[128 * 1024 + 17];
    StringBuilder expected = new StringBuilder(64);
    for (byte value : MessageDigest.getInstance("SHA-256").digest(staged))
      expected.append(String.format(Locale.ROOT, "%02x", value & 255));
    MediaSaver media = MediaSaver.INSTANCE;
    TagDetailChecks.require(
        media.matchesSavedBytes(
            new ByteArrayInputStream(staged), staged.length, expected.toString(), () -> false),
        "MTP deletion allows the independently fingerprinted bytes");
    byte[] corrupted = staged.clone();
    corrupted[corrupted.length - 1] = 1;
    TagDetailChecks.require(
        !media.matchesSavedBytes(
            new ByteArrayInputStream(corrupted), staged.length, expected.toString(), () -> false),
        "MTP deletion rejects a same-size mutation beyond the first 128 KiB");
    InputStream unreadable =
        new InputStream() {
          private boolean deliveredPrefix;

          @Override
          public int read() throws IOException {
            throw new IOException("synthetic SD read failure");
          }

          @Override
          public int read(byte[] bytes, int offset, int length) throws IOException {
            if (deliveredPrefix) throw new IOException("synthetic SD read failure");
            deliveredPrefix = true;
            int count = Math.min(1024, length);
            System.arraycopy(staged, 0, bytes, offset, count);
            return count;
          }
        };
    TagDetailChecks.require(
        !media.matchesSavedBytes(unreadable, staged.length, expected.toString(), () -> false),
        "MTP deletion rejects a read failure after a matching prefix");
    TagDetailChecks.require(
        !media.matchesSavedBytes(
            new ByteArrayInputStream(staged), staged.length, expected.toString(), () -> true),
        "MTP deletion does not proceed after cancellation");
  }

  private static void verifyDiscardWithDetachedResult(
      Context context, NanoBananaStore store, Fake network) throws Exception {
    String volume = "dead-beef";
    TagDetailChecks.require(
        !android.provider.MediaStore.getExternalVolumeNames(context).contains(volume),
        "fixture refers to an unavailable volume");
    String pending = "content://media/" + volume + "/images/media/1";
    store.job(
        new JSONObject()
            .put("stage", "generating")
            .put("task", "synthetic-detached-result")
            .put("object", "loupe-nano-temp/00000000-0000-0000-0000-000000000106.jpg")
            .put("owner", "synthetic-detached-owner")
            .put("version", "synthetic-version")
            .put("photo_volume", volume)
            .put("pending", pending));
    try {
      NanoBananaJob first = new NanoBananaJob(context, store, network);
      try {
        first.discard();
        throw new AssertionError("Discard silently forgot the detached result");
      } catch (PhotoStorage.Unavailable expected) {
        /* Remote cleanup has a separate durable boundary. */
      }
      JSONObject record = store.job();
      TagDetailChecks.require(
          network.deletes == 1
              && record != null
              && record.getString("stage").equals("discarding")
              && record.getString("pending").equals(pending)
              && !record.getBoolean("discardDelete"),
          "Remote deletion completes once and detached local cleanup remains journaled");
      NanoBananaJob reopened = new NanoBananaJob(context, store, network);
      try {
        reopened.run(null, null, message -> {});
        throw new AssertionError("Retry forgot the detached result");
      } catch (PhotoStorage.Unavailable expected) {
        /* A retry still awaits the original card. */
      }
      TagDetailChecks.require(
          network.deletes == 1
              && network.submits == 0
              && network.statuses == 0
              && network.downloads == 0
              && store.job().getString("pending").equals(pending),
          "Resuming a canceled job never regenerates or repeats its finished remote deletion");
    } finally {
      store.job(null); // The fixture used a non-existent volume and no live provider objects.
    }
  }

  private static android.view.accessibility.AccessibilityNodeInfo findAccessibleExact(
      android.view.accessibility.AccessibilityNodeInfo node, String text) {
    if (node == null) return null;
    if (node.getText() != null && text.contentEquals(node.getText())) return node;
    for (int i = 0; i < node.getChildCount(); i++) {
      android.view.accessibility.AccessibilityNodeInfo found =
          findAccessibleExact(node.getChild(i), text);
      if (found != null) return found;
    }
    return null;
  }

  private static android.view.accessibility.AccessibilityNodeInfo findAccessibleText(
      android.view.accessibility.AccessibilityNodeInfo node, String part) {
    if (node == null) return null;
    if (node.getText() != null && node.getText().toString().contains(part)) return node;
    for (int i = 0; i < node.getChildCount(); i++) {
      android.view.accessibility.AccessibilityNodeInfo found =
          findAccessibleText(node.getChild(i), part);
      if (found != null) return found;
    }
    return null;
  }

  private static void verifyMenuTargets(View view) {
    if (view.getVisibility() != View.VISIBLE) return;
    if (view instanceof Button)
      TagDetailChecks.require(
          view.getHeight() >= GalleryUi.dp(view.getContext(), 48),
          "visible menu row has 48dp touch target");
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) verifyMenuTargets(group.getChildAt(i));
    }
  }

  private static View findWindowLabel(String text) {
    for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
      View found = findLabel(root, text);
      if (found != null) return found;
    }
    return null;
  }

  private static View findLabel(View view, String label) {
    if (view.getVisibility() != View.VISIBLE) return null;
    if (label.equals(
            view.getContentDescription() == null ? null : view.getContentDescription().toString())
        || view instanceof android.widget.TextView
            && label.contentEquals(((android.widget.TextView) view).getText())) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findLabel(group.getChildAt(i), label);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static void showPhoto(MainActivity screen, int index) {
    try {
      Method m = MainActivity.class.getDeclaredMethod("showPhoto", int.class);
      m.setAccessible(true);
      m.invoke(screen, index);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static void verifySaved(Context context, String uri, int width, int height)
      throws Exception {
    BitmapFactory.Options size = new BitmapFactory.Options();
    size.inJustDecodeBounds = true;
    try (java.io.InputStream input = context.getContentResolver().openInputStream(Uri.parse(uri))) {
      BitmapFactory.decodeStream(input, null, size);
    }
    TagDetailChecks.require(
        size.outWidth == width && size.outHeight == height && "image/jpeg".equals(size.outMimeType),
        "saved JPEG dimensions and format read back from MediaStore");
  }

  private static void capture(Instrumentation test, Activity activity, String name)
      throws Exception {
    test.waitForIdleSync();
    captureImage(test, name);
  }

  private static void captureWindow(Instrumentation test, View window, String name)
      throws Exception {
    // The indeterminate generation indicator can keep the main queue busy.
    GenerationIndicatorChecks.await(test, "capture window has focus", window::hasWindowFocus);
    ConsistencyLayoutChecks.frame(test, window);
    captureImage(test, name);
  }

  private static void captureImage(Instrumentation test, String name) throws Exception {
    // Service Toasts cancel their predecessor; wait for the latest short Toast to leave the screen.
    FixtureFactory factory = (FixtureFactory) NanoBananaService.dependencies;
    while (factory.lastMessageAt > 0 && SystemClock.uptimeMillis() - factory.lastMessageAt < 2500)
      Thread.sleep(50);
    Thread.sleep(250);
    saveCapture(test, name);
  }

  private static void saveCapture(Instrumentation test, String name) throws Exception {
    Bitmap image = test.getUiAutomation().takeScreenshot();
    if (image == null) throw new IOException("UiAutomation returned no screenshot");
    try (FileOutputStream output =
        new FileOutputStream(new File(captureDirectory(test), name + ".png"))) {
      if (!image.compress(Bitmap.CompressFormat.PNG, 100, output))
        throw new IOException("UiAutomation screenshot PNG encoding failed");
    } finally {
      image.recycle();
    }
  }

  private static void saveDisplayCapture(Instrumentation test, String name) throws IOException {
    try (ParcelFileDescriptor descriptor =
            test.getUiAutomation().executeShellCommand("screencap -p");
        FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
        FileOutputStream output =
            new FileOutputStream(new File(captureDirectory(test), name + ".png"))) {
      TagDetailChecks.require(input.transferTo(output) > 0, "display screencap returned PNG bytes");
    }
  }

  private static File captureDirectory(Instrumentation test) {
    File dir = new File(test.getTargetContext().getCacheDir(), "consistency-checks");
    dir.mkdirs();
    return dir;
  }
}
