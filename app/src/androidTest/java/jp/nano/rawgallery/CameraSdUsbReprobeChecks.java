package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import jp.nano.rawgallery.camera.ImportProgress;
import jp.nano.rawgallery.camera.MountedCameraCard;
import jp.nano.rawgallery.camera.SdUsbReprobeController;
import jp.nano.rawgallery.camera.SdUsbReprobeFlowChecks;

/** Exercises the on-screen controls without USB/media fixtures or a physical device. */
final class CameraSdUsbReprobeChecks {
  static final String MODE = "camera-pilot-sd-usb-reprobe";

  private record Status(String name, String message, boolean busy) {}

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
              new Status("busy", "処理中は再認識できません。完了してからもう一度お試しください。", false),
              new Status("eject-needed", "Androidでカードの取り出しが完了していることを確認してください。", false),
              new Status("permit", "USBの使用許可を確認しています。", true),
              new Status("permission-denied", "USBの使用許可を確認できません。もう一度お試しください。", false),
              new Status("redetecting", "SDカードを再認識しています…", true),
              new Status("waiting", "Androidがカードを認識するのを待っています…", true),
              new Status("timeout", "カードを再認識できませんでした。Androidで安全に取り出してから、リーダーを挿し直してください。", false),
              new Status("choose-source", "カードを認識しました。取り込み元のフォルダを選んでください。", false),
              new Status("ready", "元カードと保存先を確認しました。SDカードから取り込めます。", false))) {
        test.runOnMainSync(
            () -> {
              activity.renderSdReprobe(
                  new SdUsbReprobeController.Display(state.message, state.busy));
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
              bringIntoView(status);
              require(!progress.getActive(), "Rendering did not start an import");
            });
        capture(test, directory, width, font, state.name);
      }
      checkDialogs(test, activity, directory, width, font);
      SdUsbReprobeFlowChecks.run(test, activity);
      checkCardNotificationOrdering(test, activity);
    } finally {
      test.runOnMainSync(activity::finish);
      test.waitForIdleSync();
    }
    return "PASS SD USB re-detection UI "
        + width
        + "dp/"
        + font
        + ": actual unsupported action and dialogs, synthetic status rendering and controller lifecycle. "
        + directory.getAbsolutePath();
  }

  private static void checkDialogs(
      Instrumentation test, CameraImportActivity activity, File directory, int width, float font)
      throws Exception {
    Class<?> type = Class.forName(SdUsbReprobeController.class.getName() + "$AndroidDialogs");
    var constructor = type.getDeclaredConstructor(android.app.Activity.class);
    constructor.setAccessible(true);
    var dialogs = constructor.newInstance(activity);
    var cancelCount = new AtomicInteger();
    var positiveCount = new AtomicInteger();
    for (String name : List.of("prepare", "confirm")) {
      boolean preparing = name.equals("prepare");
      var show = type.getDeclaredMethod(name, Runnable.class, Runnable.class);
      show.setAccessible(true);
      test.runOnMainSync(
          () -> {
            try {
              show.invoke(
                  dialogs,
                  (Runnable) positiveCount::incrementAndGet,
                  (Runnable) cancelCount::incrementAndGet);
            } catch (ReflectiveOperationException failure) {
              throw new AssertionError(failure);
            }
          });
      capture(test, directory, width, font, "dialog-" + name);
      var automation = test.getUiAutomation();
      var root = automation.getRootInActiveWindow();
      require(root != null, "Dialog has an accessibility window " + name);
      var positiveLabel = preparing ? "ストレージ設定を開く" : "取り出し済み";
      var safetyText = preparing ? "各カードにパーティションが1つ" : "フォーマットやマウントが進行中";
      require(
          !root.findAccessibilityNodeInfosByText(safetyText).isEmpty(),
          "Safety instructions are exposed to accessibility " + name);
      dialogButton(root, positiveLabel);
      dialogButton(root, "キャンセル");
      var scroll = scrollable(root);
      if (scroll != null && scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
        capture(test, directory, width, font, "dialog-" + name + "-scrolled");
        root = automation.getRootInActiveWindow();
        require(root != null, "Scrolled dialog retains its window " + name);
        dialogButton(root, positiveLabel);
      }
      require(
          dialogButton(root, "キャンセル").performAction(AccessibilityNodeInfo.ACTION_CLICK),
          "Cancel closes the dialog without invoking its action " + name);
      test.waitForIdleSync();
    }
    require(
        cancelCount.get() == 2 && positiveCount.get() == 0,
        "The actual dialogs were canceled without opening Settings or re-detecting");
  }

  private static AccessibilityNodeInfo dialogButton(AccessibilityNodeInfo root, String label) {
    var button =
        root.findAccessibilityNodeInfosByText(label).stream()
            .filter(
                node ->
                    label.contentEquals(node.getText())
                        && node.isClickable()
                        && node.isEnabled()
                        && node.isVisibleToUser())
            .findFirst()
            .orElseThrow(() -> new AssertionError("Visible dialog button " + label));
    var bounds = new Rect();
    button.getBoundsInScreen(bounds);
    require(!bounds.isEmpty(), "Reachable dialog button " + label);
    return button;
  }

  private static AccessibilityNodeInfo scrollable(AccessibilityNodeInfo node) {
    if (node.isScrollable()) return node;
    for (int i = 0; i < node.getChildCount(); i++) {
      var child = node.getChild(i);
      if (child == null) continue;
      var scroll = scrollable(child);
      if (scroll != null) return scroll;
    }
    return null;
  }

  private static void checkCardNotificationOrdering(
      Instrumentation test, CameraImportActivity activity) throws Exception {
    var picker = field("sdPicker");
    var confirmation = field("sdConfirmation");
    var pickerType = picker.getType();
    var constructor =
        pickerType.getDeclaredConstructor(
            String.class,
            String.class,
            String.class,
            long.class,
            boolean.class,
            String.class,
            CameraImportActivity.SdStartMode.class);
    constructor.setAccessible(true);
    var requestCode = field("PICK_SD_TREE");
    String uuid = "30fa-0021";
    Uri source =
        DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents", uuid + ":DCIM");
    test.runOnMainSync(
        () -> {
          try {
            SdUsbReprobeFlowChecks.volumeEvent(uuid, false);
            long beforePicker = MountedCameraCard.detachGeneration();
            picker.set(
                activity,
                constructor.newInstance(
                    null,
                    PhotoStorage.PRIMARY,
                    "RAW",
                    beforePicker,
                    false,
                    null,
                    CameraImportActivity.SdStartMode.MANUAL));
            SdUsbReprobeFlowChecks.volumeEvent(uuid, true);
            require(
                picker.get(activity) == null,
                "The pending folder picker is invalidated by callback-only removal");
            long afterPicker = MountedCameraCard.detachGeneration();
            require(afterPicker == beforePicker + 1, "Storage callback advances the generation");
            SdUsbReprobeFlowChecks.volumeEvent(uuid, false);
            activity.onActivityResult(
                requestCode.getInt(null),
                android.app.Activity.RESULT_OK,
                new Intent().setData(source).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            require(
                confirmation.get(activity) == null,
                "A result from the canceled picker is ignored even after the same card returns");

            SdUsbReprobeFlowChecks.legacyVolumeEvent(uuid, Intent.ACTION_MEDIA_UNMOUNTED);
            long beforeFreshPicker = MountedCameraCard.detachGeneration();
            picker.set(
                activity,
                constructor.newInstance(
                    null,
                    PhotoStorage.PRIMARY,
                    "RAW",
                    beforeFreshPicker,
                    false,
                    null,
                    CameraImportActivity.SdStartMode.MANUAL));
            SdUsbReprobeFlowChecks.volumeEvent(uuid, true);
            require(
                picker.get(activity) == null
                    && MountedCameraCard.detachGeneration() == beforeFreshPicker + 1,
                "A delayed legacy unmount cannot hide a later genuine callback removal");
            SdUsbReprobeFlowChecks.volumeEvent(uuid, false);
            long beforeConfirmation = MountedCameraCard.detachGeneration();

            activity.confirmSdKinds(
                new CameraImportActivity.SdStart(
                    source, null, PhotoStorage.PRIMARY, "RAW", beforeConfirmation, false, null),
                "取り込み元と保存先");
            var dialog = (androidx.appcompat.app.AlertDialog) confirmation.get(activity);
            require(dialog != null && dialog.isShowing(), "Format confirmation is displayed");
            SdUsbReprobeFlowChecks.volumeEvent(uuid, true);
            SdUsbReprobeFlowChecks.volumeEvent(uuid, false);
            require(
                !dialog.isShowing()
                    && MountedCameraCard.detachGeneration() == beforeConfirmation + 1,
                "The independently observed second removal cancels the open confirmation");
          } catch (ReflectiveOperationException unavailable) {
            throw new AssertionError(unavailable);
          }
        });
    test.waitForIdleSync();
    require(
        !ImportProgress.INSTANCE.getActive(),
        "The stale picker or confirmation never starts an import");
  }

  private static Field field(String name) throws NoSuchFieldException {
    var field = CameraImportActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
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
