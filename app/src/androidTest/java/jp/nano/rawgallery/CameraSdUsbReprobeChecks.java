package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import jp.nano.rawgallery.camera.ImportProgress;
import jp.nano.rawgallery.camera.SdUsbReprobeController;

/** Exercises the on-screen controls without USB/media fixtures or a physical device. */
final class CameraSdUsbReprobeChecks {
  static final String MODE = "camera-pilot-sd-usb-reprobe";

  private record Status(String name, String message, boolean busy, boolean chooseSource) {}

  static String run(Instrumentation test, int width, float font) throws Exception {
    var app = test.getTargetContext();
    require(
        "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE),
        "Requires an emulator");
    require(
        app.getSystemService(UsbManager.class).getDeviceList().isEmpty(),
        "Requires no attached USB devices");
    require(
        (width == 400 && font == 1f) || (width == 320 && font == 1.3f), "Unsupported UI profile");
    var directory = new File(app.getCacheDir(), "camera-sd-usb-reprobe");
    require(directory.isDirectory() || directory.mkdirs(), "Could not create capture directory");
    var progress = ImportProgress.INSTANCE;
    require(!progress.getActive(), "Import must be idle before rendering");
    var activity =
        (CameraImportActivity)
            test.startActivitySync(
                new Intent(app, CameraImportActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      test.waitForIdleSync();
      test.runOnMainSync(
          () -> {
            var configuration = activity.getResources().getConfiguration();
            require(
                configuration.screenWidthDp == width && configuration.fontScale == font,
                "Injected display profile");
            Button action = activity.getWindow().getDecorView().findViewWithTag("sd-reprobe-start");
            require(
                action != null && action.isEnabled(),
                "Re-detection action is enabled when no work is active");
            require("SDカードを再認識".contentEquals(action.getText()), "Accessible action label");
            require(
                action.getMinHeight() >= GalleryUi.dp(activity, 48), "At least 48dp touch target");
            action.performClick();
            TextView status =
                activity.getWindow().getDecorView().findViewWithTag("sd-reprobe-status");
            require(
                status.getText().toString().contains("リーダーを挿し直してください"),
                "Unsupported hardware has manual guidance");
            require(
                status.getAccessibilityLiveRegion() == View.ACCESSIBILITY_LIVE_REGION_POLITE,
                "Announce status changes");
            bringIntoView(status);
          });
      capture(test, directory, width, font, "unsupported");
      for (Status state :
          List.of(
              new Status("busy", "処理中は再認識できません。完了してからもう一度お試しください。", false, false),
              new Status("eject-needed", "Androidでカードの取り出しが完了していることを確認してください。", false, false),
              new Status("permit", "USBの使用許可を確認しています。", true, false),
              new Status("permission-denied", "USBの使用許可を確認できません。もう一度お試しください。", false, false),
              new Status("redetecting", "SDカードを再認識しています…", true, false),
              new Status("waiting", "Androidがカードを認識するのを待っています…", true, false),
              new Status(
                  "timeout", "カードを再認識できませんでした。Androidで安全に取り出してから、リーダーを挿し直してください。", false, false),
              new Status("choose-source", "カードを認識しました。取り込み元のフォルダを選んでください。", false, true),
              new Status("ready", "元カードと保存先を確認しました。SDカードから取り込めます。", false, false))) {
        test.runOnMainSync(
            () -> {
              activity.renderSdReprobe(
                  new SdUsbReprobeController.Display(
                      state.message, state.busy, state.chooseSource));
              View root = activity.getWindow().getDecorView();
              TextView status = root.findViewWithTag("sd-reprobe-status");
              require(state.message.contentEquals(status.getText()), "State message " + state.name);
              require(
                  (root.findViewWithTag("sd-reprobe-progress").getVisibility() == View.VISIBLE)
                      == state.busy,
                  "Progress indicator " + state.name);
              require(
                  root.findViewWithTag("sd-reprobe-start").isEnabled() != state.busy,
                  "Button admission follows active status");
              Button start = root.findViewWithTag("sd-import-start");
              require(
                  !state.chooseSource || "取り込み元を選ぶ".contentEquals(start.getText()),
                  "Fresh source selection is offered");
              bringIntoView(status);
              require(!progress.getActive(), "Rendering did not start an import");
            });
        capture(test, directory, width, font, state.name);
      }
    } finally {
      test.runOnMainSync(activity::finish);
      test.waitForIdleSync();
    }
    return "PASS SD USB re-detection UI "
        + width
        + "dp/"
        + font
        + ": actual unsupported action and synthetic status rendering. "
        + directory.getAbsolutePath();
  }

  private static void bringIntoView(View view) {
    view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true);
  }

  private static void capture(
      Instrumentation test, File directory, int width, float font, String state) throws Exception {
    test.waitForIdleSync();
    Bitmap bitmap = test.getUiAutomation().takeScreenshot();
    require(bitmap != null, "Screenshot " + state);
    try (FileOutputStream out =
        new FileOutputStream(new File(directory, width + "-" + font + "-" + state + ".png"))) {
      require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out), "Write screenshot " + state);
    } finally {
      bitmap.recycle();
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
