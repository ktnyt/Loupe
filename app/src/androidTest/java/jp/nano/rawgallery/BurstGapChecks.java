package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListView;
import com.google.android.material.textfield.TextInputLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
      int[] closedBottom = new int[1];
      ui(test, () -> closedBottom[0] = visibleFrame(editor).bottom);
      tap(test, field.getEditText());
      ExpressiveUiChecks.awaitUi(test, 5000,
          () -> closedBottom[0] - visibleFrame(editor).bottom > GalleryUi.dp(activityContext(editor), 48),
          "numeric keyboard shown and dialog resized");
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

      View preset = openPicker(test, activity);
      tap(test, list(preset).getChildAt(0));
      awaitClosed(test, preset);
      require(prefs.getInt(millis, -1) == 0, "off preset saves immediately using canonical key");
      GalleryScreenChecks.finishInitialIndex(test, activity);
      require(signature(context).contains(":burst-gap-ms:0:"), "off preset index rebuilt");

      checkRetry(test, activity);
      return "PASS " + prefix + " numeric keyboard, visible validation, cancel/Back/outside, exact save, "
          + "reopen, preset, index signature and failed-save retry; restored test preferences.\n";
    } finally {
      if (activity != null) ui(test, activity::finish);
      var restore = prefs.edit();
      if (previousMillis == null) restore.remove(millis);
      else restore.putInt(millis, previousMillis);
      if (previousSeconds == null) restore.remove(seconds);
      else restore.putInt(seconds, previousSeconds);
      require(restore.commit(), "restore burst preferences");
      GalleryIndex.WORKER.submit(() -> GalleryIndex.rebuild(context, false, () -> false))
          .get(30, TimeUnit.SECONDS);
    }
  }

  private static void checkRetry(Instrumentation test, MainActivity activity) throws Exception {
    var attempts = new AtomicInteger();
    ui(test, () -> BurstGapDialog.showCustom(activity, 350, value -> {
      require(value == 350, "retry receives retained draft");
      return attempts.incrementAndGet() == 2;
    }));
    View editor = ShareScreenChecks.modal(test, activity);
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
    View menu = ShareScreenChecks.modal(test, activity);
    ui(test, () -> TagDetailChecks.button(menu, "設定とその他").performClick());
    View settings = ShareScreenChecks.modal(test, activity);
    ui(test, () -> TagDetailChecks.button(settings, "連写のまとめ方").performClick());
    return ShareScreenChecks.modal(test, activity);
  }

  private static View openCustom(Instrumentation test, MainActivity activity, int checked, String shown)
      throws Exception {
    return selectCustom(test, activity, openPicker(test, activity), checked, shown);
  }

  private static View selectCustom(Instrumentation test, MainActivity activity, View picker,
      int checked, String shown) throws Exception {
    ui(test, () -> require(list(picker).getCheckedItemPosition() == checked, "correct choice checked"));
    tap(test, list(picker).getChildAt(4));
    View editor = ShareScreenChecks.modal(test, activity);
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
  private static Context activityContext(View view) { return view.getContext(); }

  private static Rect visibleFrame(View root) {
    Rect result = new Rect();
    root.getWindowVisibleDisplayFrame(result);
    return result;
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
