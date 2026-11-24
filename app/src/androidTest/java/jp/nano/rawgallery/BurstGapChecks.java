package jp.nano.rawgallery;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ListView;
import com.google.android.material.textfield.TextInputLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static jp.nano.rawgallery.TagDetailChecks.require;
import static jp.nano.rawgallery.TagDetailChecks.ui;

/** Emulator checks for the real settings route, using an empty photo UI for evidence. */
final class BurstGapChecks {
  static String run(Instrumentation test) throws Exception {
    Context context = test.getTargetContext();
    SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    var millis = BurstGapSettings.MILLIS;
    var seconds = BurstGapSettings.LEGACY_SECONDS;
    Integer previousMillis = prefs.contains(millis) ? prefs.getInt(millis, 0) : null;
    Integer previousSeconds = prefs.contains(seconds) ? prefs.getInt(seconds, 0) : null;
    MainActivity activity = null;
    List<Uri> fixtures = new ArrayList<>();
    Throwable failure = null;
    try {
      require(prefs.edit().remove(millis).putInt(seconds, 1).commit(), "seed legacy value");
      activity = (MainActivity) test.startActivitySync(new Intent(context, MainActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      GalleryScreenChecks.finishInitialIndex(test, activity);
      clearPhotoUi(test, activity);
      String before = signature(context);
      require(before != null && before.contains(":burst-gap-ms:1000:"), "legacy grouping indexed");
      var config = activity.getResources().getConfiguration();
      File captures = new File(context.getCacheDir(), "burst-gap-checks");
      require(captures.isDirectory() || captures.mkdir(), "capture directory");
      String prefix = config.screenWidthDp + "dp-font" + config.fontScale;

      View editor = openCustom(test, activity, 1, "1000");
      TextInputLayout field = field(editor);
      var automation = test.getUiAutomation();
      var service = automation.getServiceInfo();
      android.util.Log.i("LoupeBurstGapCheck", "interactive window flag before request: "
          + ((service.flags & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0));
      service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
      automation.setServiceInfo(service);
      tap(test, field.getEditText());
      Rect keyboard;
      try {
        keyboard = awaitKeyboard(test);
      } catch (AssertionError absent) {
        capture(test, editor, captures, prefix + "-keyboard-probe");
        throw absent;
      }
      ui(test, () -> {
        require(field.getEditText().getInputType() == android.text.InputType.TYPE_CLASS_NUMBER,
            "number input requested");
        require(bottom(TagDetailChecks.button(editor, "保存")) <= keyboard.top
            && bottom(TagDetailChecks.button(editor, "キャンセル")) <= keyboard.top,
            "dialog actions stay above the keyboard");
      });
      ui(test, () -> field.getEditText().setText("350"));
      capture(test, editor, captures, prefix + "-custom-keyboard");
      ui(test, () -> field.getEditText().setText("2147483648"));
      tap(test, TagDetailChecks.button(editor, "保存"));
      ui(test, () -> {
        require(field.getError() != null && field.getError().toString().contains("2147483647"), "range error shown");
        require(field.getEditText().getText().toString().equals("2147483648"), "invalid draft retained");
        require(editor.hasWindowFocus(), "invalid input keeps dialog open");
      });
      capture(test, editor, captures, prefix + "-invalid");
      tap(test, TagDetailChecks.button(editor, "キャンセル"));
      awaitClosed(test, editor);
      awaitKeyboardClosed(test);
      requireUnchanged(prefs, context, before);

      View backEditor = openCustom(test, activity, 1, "1000");
      ui(test, () -> field(backEditor).getEditText().setText("19"));
      test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
      awaitClosed(test, backEditor);
      requireUnchanged(prefs, context, before);

      View outsideEditor = openCustom(test, activity, 1, "1000");
      ui(test, () -> field(outsideEditor).getEditText().setText("21"));
      int[] outside = new int[2];
      ui(test, () -> {
        View panel = outsideEditor.findViewById(androidx.appcompat.R.id.parentPanel);
        panel.getLocationOnScreen(outside);
        outside[0] += panel.getWidth() / 2;
        outside[1] -= GalleryUi.dp(panel.getContext(), 40);
        require(outside[1] > visibleFrame(outsideEditor).top, "outside tap inside safe screen area");
      });
      tap(test, outside[0], outside[1]);
      awaitClosed(test, outsideEditor);
      requireUnchanged(prefs, context, before);

      View saveEditor = openCustom(test, activity, 1, "1000");
      ui(test, () -> field(saveEditor).getEditText().setText("350"));
      tap(test, TagDetailChecks.button(saveEditor, "保存"));
      awaitClosed(test, saveEditor);
      require(prefs.getInt(millis, -1) == 350 && prefs.getInt(seconds, -1) == 1, "exact canonical persisted");
      GalleryScreenChecks.finishInitialIndex(test, activity);
      require(signature(context).contains(":burst-gap-ms:350:"), "rebuilt index signature uses exact milliseconds");
      clearPhotoUi(test, activity);
      View picker = openPicker(test, activity);
      capture(test, picker, captures, prefix + "-custom-selected");
      View savedEditor = selectCustom(test, activity, picker, 4, "350");
      tap(test, TagDetailChecks.button(savedEditor, "キャンセル"));
      awaitClosed(test, savedEditor);

      checkStaleBurst(test, activity, prefs, fixtures);
      checkRetry(test, activity);
      return "PASS " + prefix + " numeric keyboard, visible validation, cancel/Back/outside, exact save, "
          + "reopen, preset, stale burst/selection guard, individual photos after rebuild, index signature, "
          + "and failed-save retry; restored test preferences and removed fixture photos.\n";
    } catch (Exception | AssertionError error) {
      failure = error;
      throw error;
    } finally {
      var cleanup = new ArrayList<Throwable>();
      MainActivity launched = activity;
      if (launched != null) cleanup(cleanup, () -> ui(test, launched::finish));
      cleanup(cleanup, () -> {
        var restore = prefs.edit();
        if (previousMillis == null) restore.remove(millis);
        else restore.putInt(millis, previousMillis);
        if (previousSeconds == null) restore.remove(seconds);
        else restore.putInt(seconds, previousSeconds);
        require(restore.commit(), "restore burst preferences");
      });
      for (Uri fixture : fixtures) {
        cleanup(cleanup, () -> require(context.getContentResolver().delete(fixture, null, null) == 1,
            "remove owned fixture"));
      }
      cleanup(cleanup, () -> GalleryIndex.WORKER.submit(() -> GalleryIndex.rebuild(context, false, () -> false))
          .get(30, TimeUnit.SECONDS));
      if (!cleanup.isEmpty()) {
        Throwable reported = failure == null ? new AssertionError("burst test cleanup failed") : failure;
        cleanup.forEach(reported::addSuppressed);
        if (failure == null) throw (AssertionError) reported;
      }
    }
  }

  @FunctionalInterface
  private interface CleanupAction { void run() throws Exception; }

  private static void cleanup(List<Throwable> failures, CleanupAction action) {
    try {
      action.run();
    } catch (Exception | AssertionError error) {
      failures.add(error);
    }
  }

  private static void checkStaleBurst(Instrumentation test, MainActivity activity,
      SharedPreferences prefs, List<Uri> fixtures) throws Exception {
    String folder = "Pictures/LoupeBurstGap-" + UUID.randomUUID() + "/";
    seedBurst(activity, folder, fixtures);
    GalleryIndex.WORKER.submit(() -> GalleryIndex.rebuild(activity, true, () -> false))
        .get(30, TimeUnit.SECONDS);
    ui(test, () -> {
      RankingResultChecks.set(activity, "albumFolder", folder);
      RankingResultChecks.call(activity, "gallery");
      RankingResultChecks.call(activity, "loadIndexedPhotos");
    });
    GalleryScreenChecks.settle(test, activity);
    ui(test, () -> require(RankingResultChecks.get(activity, "bundles").equals(List.of(List.of(0, 1))),
        "fixture JPEGs start in one indexed burst"));
    tap(test, GalleryScreenChecks.firstPhoto(activity.getWindow().getDecorView()));
    Bundle pendingRestore = new Bundle();
    ui(test, () -> {
      require(RankingResultChecks.get(activity, "burstMembers") != null, "open fixture burst");
      pendingRestore.putStringArrayList("burst", fixtures.stream()
          .map(Uri::toString).collect(Collectors.toCollection(ArrayList::new)));
      RankingResultChecks.set(activity, "pendingRestore", pendingRestore);
      TagDetailChecks.button(activity.getWindow().getDecorView(), "その他").performClick();
    });
    View preset = openPickerFromMenu(test, activity);
    var workerEntered = new CountDownLatch(1);
    var releaseWorker = new CountDownLatch(1);
    GalleryIndex.WORKER.submit(() -> {
      workerEntered.countDown();
      require(releaseWorker.await(20, TimeUnit.SECONDS), "release paused index worker");
      return null;
    });
    try {
      require(workerEntered.await(10, TimeUnit.SECONDS), "pause index worker before save");
      ListView choices = list(preset);
      ui(test, () -> choices.setSelectionFromTop(0, 0));
      ConsistencyLayoutChecks.frame(test, preset);
      View off = choices.getChildAt(0);
      ui(test, () -> {
        Rect visible = new Rect();
        require(choices.getFirstVisiblePosition() == 0 && off.getGlobalVisibleRect(visible)
            && visible.height() == off.getHeight(), "off preset is fully visible after scrolling");
      });
      tap(test, off);
      awaitClosed(test, preset);
      require(prefs.getInt(BurstGapSettings.MILLIS, -1) == 0, "off preset saves immediately");
      GalleryScreenChecks.settle(test, activity);
      tap(test, GalleryScreenChecks.firstPhoto(activity.getWindow().getDecorView()));
      ui(test, () -> {
        require(RankingResultChecks.get(activity, "burstMembers") == null
            && RankingResultChecks.get(activity, "grid") != null, "old group does not reopen");
        require(!pendingRestore.containsKey("burst"), "saved scope cannot restore the old burst");
        TagDetailChecks.button(activity.getWindow().getDecorView(), "選択").performClick();
        require(!(boolean) RankingResultChecks.get(activity, "selecting"), "old selection route is blocked");
      });
      require(signature(activity).contains(":burst-gap-ms:350:"), "worker still paused at old index");
    } finally {
      releaseWorker.countDown();
    }
    GalleryScreenChecks.finishInitialIndex(test, activity);
    require(signature(activity).contains(":burst-gap-ms:0:"), "off preset index rebuilt");
    ui(test, () -> require(RankingResultChecks.get(activity, "bundles").equals(List.of(List.of(0), List.of(1))),
        "new index splits the same fixture images into individual photos"));
  }

  private static void seedBurst(Context context, String folder, List<Uri> fixtures) throws Exception {
    File jpeg = File.createTempFile("burst-gap-", ".jpg", context.getCacheDir());
    try {
      Bitmap bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
      bitmap.eraseColor(0xff486381);
      try (var out = new FileOutputStream(jpeg)) {
        require(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out), "encode fixture JPEG");
      } finally {
        bitmap.recycle();
      }
      var exif = new android.media.ExifInterface(jpeg.getPath());
      exif.setAttribute(android.media.ExifInterface.TAG_MODEL, "Loupe Fixture");
      exif.setAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:24 12:34:56");
      exif.setAttribute(android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00");
      exif.saveAttributes();
      var resolver = context.getContentResolver();
      for (int i = 0; i < 2; i++) {
        var values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "fixture-" + i + ".jpg");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri fixture = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        require(fixture != null, "insert fixture");
        fixtures.add(fixture);
        try (var out = resolver.openOutputStream(fixture)) {
          Files.copy(jpeg.toPath(), out);
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        require(resolver.update(fixture, values, null, null) == 1, "publish fixture");
      }
    } finally {
      Files.deleteIfExists(jpeg.toPath());
    }
  }

  private static void checkRetry(Instrumentation test, MainActivity activity) throws Exception {
    var attempts = new AtomicInteger();
    ui(test, () -> BurstGapDialog.showCustom(activity, 350, value -> {
      require(value == 350, "retry receives retained draft");
      return attempts.incrementAndGet() == 2;
    }));
    View editor = modal(test, activity, view -> field(view) != null);
    tap(test, TagDetailChecks.button(editor, "保存"));
    ui(test, () -> {
      require(editor.hasWindowFocus() && field(editor).getError() != null, "failed save keeps dialog and reports error");
      require(field(editor).getEditText().getText().toString().equals("350"), "failed save keeps draft");
    });
    tap(test, TagDetailChecks.button(editor, "保存"));
    awaitClosed(test, editor);
    require(attempts.get() == 2, "successful retry closes dialog");
  }

  private static void clearPhotoUi(Instrumentation test, MainActivity activity) throws Exception {
    ui(test, () -> {
      RankingResultChecks.set(activity, "photos", List.of());
      RankingResultChecks.set(activity, "bundles", List.of());
      RankingResultChecks.set(activity, "albumFolder", null);
      RankingResultChecks.call(activity, "gallery");
    });
    GalleryScreenChecks.settle(test, activity);
  }

  private static String signature(Context context) {
    try (GalleryIndex index = new GalleryIndex(context);
        Cursor cursor = index.getReadableDatabase().rawQuery("SELECT signature FROM state WHERE id=1", null)) {
      return cursor.moveToFirst() ? cursor.getString(0) : null;
    }
  }

  private static void requireUnchanged(SharedPreferences prefs, Context context, String before) {
    require(!prefs.contains(BurstGapSettings.MILLIS), "dismissal does not save canonical value");
    require(before.equals(signature(context)), "dismissal preserves published index signature");
  }

  private static View openPicker(Instrumentation test, MainActivity activity) throws Exception {
    ui(test, () -> ExpressiveUiChecks.description(activity.getWindow().getDecorView(), "設定とその他").performClick());
    return openPickerFromMenu(test, activity);
  }

  private static View openPickerFromMenu(Instrumentation test, MainActivity activity) throws Exception {
    View menu = modal(test, activity, view -> TagDetailChecks.button(view, "設定とその他") != null);
    ui(test, () -> TagDetailChecks.button(menu, "設定とその他").performClick());
    View settings = modal(test, activity, view -> TagDetailChecks.button(view, "連写のまとめ方") != null);
    ui(test, () -> TagDetailChecks.button(settings, "連写のまとめ方").performClick());
    return modal(test, activity, view -> list(view) != null);
  }

  private static View openCustom(Instrumentation test, MainActivity activity, int checked, String shown)
      throws Exception {
    return selectCustom(test, activity, openPicker(test, activity), checked, shown);
  }

  private static View selectCustom(Instrumentation test, MainActivity activity, View picker,
      int checked, String shown) throws Exception {
    ui(test, () -> require(list(picker).getCheckedItemPosition() == checked, "correct choice checked"));
    tap(test, list(picker).getChildAt(4));
    awaitClosed(test, picker);
    View editor = modal(test, activity, view -> field(view) != null);
    ui(test, () -> {
      require(field(editor).getEditText().getText().toString().equals(shown), "custom input prefilled");
      var node = field(editor).getEditText().createAccessibilityNodeInfo();
      require(node.getHintText() != null && node.getHintText().toString().contains("撮影間隔（ミリ秒）"),
          "custom input exposes accessible label");
    });
    return editor;
  }

  private static ListView list(View picker) { return ExpressiveUiChecks.find(picker, ListView.class); }
  private static TextInputLayout field(View editor) { return ExpressiveUiChecks.find(editor, TextInputLayout.class); }

  private static View modal(Instrumentation test, MainActivity activity, Predicate<View> matches)
      throws Exception {
    View[] target = new View[1];
    ExpressiveUiChecks.awaitUi(test, 5000, () -> {
      for (View window : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
        if (window != activity.getWindow().getDecorView() && window.hasWindowFocus() && matches.test(window)) {
          target[0] = window;
          return true;
        }
      }
      return false;
    }, "expected modal gains focus");
    ConsistencyLayoutChecks.frame(test, target[0]);
    return target[0];
  }

  private static Rect visibleFrame(View root) {
    Rect result = new Rect();
    root.getWindowVisibleDisplayFrame(result);
    return result;
  }

  private static Rect awaitKeyboard(Instrumentation test) throws Exception {
    long deadline = SystemClock.uptimeMillis() + 5000;
    do {
      Rect bounds = keyboardBounds(test);
      if (bounds != null) return bounds;
      Thread.sleep(50);
    } while (SystemClock.uptimeMillis() < deadline);
    throw new AssertionError("numeric keyboard is not visible");
  }

  private static void awaitKeyboardClosed(Instrumentation test) throws Exception {
    long deadline = SystemClock.uptimeMillis() + 5000;
    do {
      if (keyboardBounds(test) == null) return;
      Thread.sleep(50);
    } while (SystemClock.uptimeMillis() < deadline);
    throw new AssertionError("numeric keyboard did not dismiss");
  }

  private static Rect keyboardBounds(Instrumentation test) {
    for (var window : test.getUiAutomation().getWindows()) {
      if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
      Rect bounds = new Rect();
      window.getBoundsInScreen(bounds);
      if (!bounds.isEmpty()) return bounds;
    }
    return null;
  }

  private static int bottom(View view) {
    int[] point = new int[2];
    view.getLocationOnScreen(point);
    return point[1] + view.getHeight();
  }

  private static void awaitClosed(Instrumentation test, View dialog) throws Exception {
    ExpressiveUiChecks.awaitUi(test, 5000, () -> !dialog.isAttachedToWindow(), "dialog dismisses");
  }

  private static void tap(Instrumentation test, View view) throws Exception {
    int[] center = new int[2];
    ui(test, () -> {
      require(view != null && view.isShown(), "tap target is shown");
      require(view.getHeight() >= GalleryUi.dp(view.getContext(), 48), "48dp tap target");
      view.getLocationOnScreen(center);
      center[0] += view.getWidth() / 2;
      center[1] += view.getHeight() / 2;
      require(visibleFrame(view).contains(center[0], center[1]), "tap target inside visible display");
    });
    tap(test, center[0], center[1]);
  }

  private static void tap(Instrumentation test, int x, int y) throws Exception {
    long now = SystemClock.uptimeMillis();
    MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
    MotionEvent up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0);
    try {
      test.sendPointerSync(down);
      test.sendPointerSync(up);
      test.waitForIdleSync();
    } finally {
      down.recycle();
      up.recycle();
    }
  }

  private static void capture(Instrumentation test, View root, File directory, String name)
      throws Exception {
    ConsistencyLayoutChecks.frame(test, root);
    Bitmap image = test.getUiAutomation().takeScreenshot();
    require(image != null, "composed display capture");
    try (var output = new FileOutputStream(new File(directory, name + ".png"))) {
      require(image.compress(Bitmap.CompressFormat.PNG, 100, output), "capture saved");
    } finally {
      image.recycle();
    }
  }
}
