package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.List;
import jp.nano.rawgallery.camera.CameraAttachActivity;
import jp.nano.rawgallery.camera.ImportProgress;
import jp.nano.rawgallery.camera.MountedCameraCard;
import jp.nano.rawgallery.camera.SdUsbReprobeController;
import jp.nano.rawgallery.camera.SdUsbReprobeFlowChecks;

/** Exercises explicit entry, retry and accessible import status on an emulator without USB. */
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
                CameraImportActivity.manualImportIntent(app, false)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      test.waitForIdleSync();
      test.runOnMainSync(
          () -> {
            var configuration = activity.getResources().getConfiguration();
            require(
                configuration.screenWidthDp == width && configuration.fontScale == font,
                "Injected display profile");
            View root = activity.getWindow().getDecorView();
            Button action = root.findViewWithTag("sd-reprobe-start");
            TextView status = root.findViewWithTag("sd-reprobe-status");
            require(status.getVisibility() == View.GONE, "Unsupported manual entry has no error");
            require(action != null && action.isEnabled(), "Re-detection is available when idle");
            require("SDカードを再認識".contentEquals(action.getText()), "Accessible action label");
            require(
                action.getMinHeight() >= GalleryUi.dp(activity, 48), "At least 48dp touch target");
            action.performClick();
            require(
                status.getText().toString().contains("リーダーを挿し直してください"),
                "An explicit retry on unsupported hardware has manual guidance");
            require(
                status.getAccessibilityLiveRegion() == View.ACCESSIBILITY_LIVE_REGION_POLITE,
                "Announce status changes");
            bringIntoView(status);
          });
      capture(test, directory, width, font, "unsupported");
      for (Status state :
          List.of(
              new Status("busy", "処理が終わってから、もう一度お試しください。", false),
              new Status("permit", "USBの使用許可を確認しています。", true),
              new Status("permission-denied", "USBの使用許可を確認できません。もう一度お試しください。", false),
              new Status("waiting", "Androidがカードを認識するのを待っています…", true),
              new Status("timeout", "カードを再認識できませんでした。Androidで安全に取り出してから、リーダーを挿し直してください。", false),
              new Status("ready", "取り込み元と保存先を確認しました。", false))) {
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
                  root.findViewWithTag("sd-reprobe-start").isEnabled() != state.busy
                      && root.findViewWithTag("sd-import-start").isEnabled() != state.busy,
                  "Refresh and transfer controls follow active status");
              bringIntoView(status);
              require(!progress.getActive(), "Rendering did not start an import");
            });
        capture(test, directory, width, font, state.name);
      }
      SdUsbReprobeFlowChecks.run(test, activity);
      checkCardNotificationOrdering(test, activity);
      checkManualEntry(test, activity, directory, width, font);
    } finally {
      test.runOnMainSync(activity::finish);
      test.waitForIdleSync();
    }
    return "PASS SD USB re-detection UI "
        + width
        + "dp/"
        + font
        + ": manual open and keep-originals, source picker, permission and cancellation."
        + directory.getAbsolutePath();
  }

  private static void checkManualEntry(
      Instrumentation test, CameraImportActivity activity, File directory, int width, float font)
      throws Exception {
    var app = test.getTargetContext();
    String previous = PhotoStorage.selected(app);
    var controllerField = field("sdReprobe");
    var discard = CameraImportActivity.class.getDeclaredMethod("discardSdStatusForReprobe");
    var consume = CameraImportActivity.class.getDeclaredMethod("handleManualEntry");
    var read =
        CameraImportActivity.class.getDeclaredMethod(
            "readManualEntry", Intent.class, boolean.class);
    for (var method : List.of(discard, consume, read)) method.setAccessible(true);
    var picker =
        test.addMonitor(
            new IntentFilter(Intent.ACTION_OPEN_DOCUMENT_TREE),
            new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null),
            true);
    var harness =
        new SdUsbReprobeFlowChecks.Harness(
            activity, () -> true, activity::renderSdReprobe, () -> invoke(discard, activity));
    try {
      test.runOnMainSync(
          () -> {
            try {
              ((SdUsbReprobeController) controllerField.get(activity)).destroy();
              require(
                  PhotoStorage.select(app, SdUsbReprobeFlowChecks.TARGET),
                  "Set synthetic destination");
              controllerField.set(activity, harness.controller());
            } catch (ReflectiveOperationException failure) {
              throw new AssertionError(failure);
            }
            harness.start();
            harness.setPermitted();
            for (Intent ordinary :
                List.of(
                    CameraImportActivity.showProgressIntent(app),
                    CameraImportActivity.mountedReaderAttachIntent(
                        app, SdUsbReprobeFlowChecks.TARGET),
                    new Intent(app, CameraImportActivity.class)
                        .setAction(CameraAttachActivity.ACTION_OPEN_FROM_USB_ATTACH))) {
              activity.onNewIntent(ordinary);
              require(
                  !((Boolean) invoke(consume, activity)), "Background entry has no manual refresh");
            }
            require(harness.queued() == 0, "Progress and attach do not queue a claim");
            invoke(read, activity, CameraImportActivity.manualImportIntent(app, true), false);
            require(
                !((Boolean) invoke(consume, activity)), "Restoring Activity state does not claim");
            var manual = CameraImportActivity.manualImportIntent(app, false);
            var duplicate = new Intent(manual);
            activity.onNewIntent(manual);
            require(
                (Boolean) invoke(consume, activity), "Fresh explicit open takes the refresh path");
            activity.onNewIntent(duplicate);
            require(
                !((Boolean) invoke(consume, activity)),
                "A duplicate token cannot restart an explicit refresh");
            require(harness.queued() == 1, "Open queues one refresh");
            View root = activity.getWindow().getDecorView();
            require(
                root.findViewWithTag("sd-import-status").getVisibility() == View.GONE,
                "Refresh discards the old source state");
            require(
                !root.findViewWithTag("sd-import-start").isEnabled(), "Refresh disables transfer");
            bringIntoView(root.findViewWithTag("sd-reprobe-status"));
          });
      capture(test, directory, width, font, "redetecting");
      test.runOnMainSync(harness::runWorker);
      test.waitForIdleSync();
      test.runOnMainSync(
          () -> {
            require(
                picker.getHits() == 0 && !ImportProgress.INSTANCE.getActive(),
                "Ordinary open updates the view without starting an import or picker");
            require(harness.claims() == 1, "Manual open claimed exactly once");
            View root = activity.getWindow().getDecorView();
            TextView status = root.findViewWithTag("sd-reprobe-status");
            require(
                status.getText().toString().contains("フォルダを選んで"),
                "The current new card requires folder selection");
            bringIntoView(status);
          });
      capture(test, directory, width, font, "choose-source");
      test.runOnMainSync(
          () -> {
            harness.setPermitted();
            activity.onNewIntent(CameraImportActivity.manualImportIntent(app, true));
            require(
                (Boolean) invoke(consume, activity), "Long-press entry refreshes before discovery");
            require(picker.getHits() == 0 && harness.queued() == 1, "Picker waits for remount");
            harness.runWorker();
          });
      test.waitForIdleSync();
      require(
          harness.claims() == 2 && picker.getHits() == 1 && !ImportProgress.INSTANCE.getActive(),
          "Long press prompts for the remounted source instead of copying the old card");
    } finally {
      test.removeMonitor(picker);
      test.runOnMainSync(
          () -> {
            harness.close();
            require(PhotoStorage.select(app, previous), "Restore destination");
          });
    }
  }

  private static Object invoke(
      java.lang.reflect.Method method, CameraImportActivity activity, Object... args) {
    try {
      return method.invoke(activity, args);
    } catch (ReflectiveOperationException unavailable) {
      throw new AssertionError(unavailable);
    }
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
