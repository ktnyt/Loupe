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
import android.view.Window;
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
    CameraUsbBoundaryChecks.checkColdPrimaryRegistration(test, directory, width, font);
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
      capture(test, activity.getWindow(), directory, width, font, "unsupported");
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
        capture(test, activity.getWindow(), directory, width, font, state.name);
        if (width == 320 && state.name.equals("timeout")) {
          assertResultReachable(test, activity, state.message);
          capture(test, activity.getWindow(), directory, width, font, "timeout-scrolled");
        }
      }
      SdUsbReprobeFlowChecks.run(test, activity);
      CameraUsbBoundaryChecks.checkPermissionResults(test, activity);
      checkCardNotificationOrdering(test, activity);
      checkManualEntry(test, activity, directory, width, font);
    } finally {
      test.runOnMainSync(activity::finish);
      test.waitForIdleSync();
    }
    CameraUsbBoundaryChecks.checkUsbQueries(test, directory, width, font);
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
            activity.renderSdSource(CameraQuickImportSources.SdState.READY);
            View root = activity.getWindow().getDecorView();
            require(
                root.findViewWithTag("sd-reprobe-start").performClick(), "Start an explicit retry");
            harness.denyPermission();
          });
      test.waitForIdleSync();
      checkSettingsAfterFailedRetry(test, activity, directory, width, font, harness);
      checkInitialObservationFailure(
          test, activity, directory, width, font, harness, consume, discard);
      test.runOnMainSync(
          () -> {
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
      Window window = activity.getWindow();
      View decor = window.getDecorView();
      ConsistencyLayoutChecks.frame(test, decor);
      test.runOnMainSync(
          () -> {
            TextView status = decor.findViewWithTag("sd-reprobe-status");
            require(
                harness.queued() == 1 && harness.claims() == 0,
                "The worker is still held after the actual refresh frame");
            require(
                status.isShown() && "SDカードを再認識しています…".contentEquals(status.getText()),
                "The committed frame shows the current refresh text");
            require(
                !decor.findViewWithTag("sd-reprobe-start").isEnabled()
                    && !decor.findViewWithTag("sd-import-start").isEnabled()
                    && decor.findViewWithTag("sd-import-status").getVisibility() == View.GONE
                    && decor.findViewWithTag("sd-reprobe-progress").isShown(),
                "The committed refresh frame disables both actions and hides the old source");
            bringIntoView(status);
          });
      ConsistencyLayoutChecks.captureHardware(
          test, window, decor, directory, width + "-" + font + "-redetecting-window");
      capture(test, window, directory, width, font, "redetecting");
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
      capture(test, window, directory, width, font, "choose-source");
      if (width == 320) {
        assertResultReachable(test, activity, "取り込み元のフォルダを選んでください。");
        capture(test, window, directory, width, font, "choose-source-scrolled");
      }
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

  private static void checkInitialObservationFailure(
      Instrumentation test,
      CameraImportActivity activity,
      File directory,
      int width,
      float font,
      SdUsbReprobeFlowChecks.Harness harness,
      java.lang.reflect.Method consume,
      java.lang.reflect.Method discard)
      throws Exception {
    var observer = SdUsbReprobeController.class.getDeclaredField("removeMediaObserver");
    observer.setAccessible(true);
    var foreground = SdUsbReprobeController.class.getDeclaredField("foreground");
    foreground.setAccessible(true);
    String retry = "「SDカードを再認識」でやり直してください";
    String reopen = "取り込み画面を開き直してください";
    var sourceCheck = field("sourceCheck");
    var statusLoad = field("sdStatusCancellation");
    var settingsStatus = field("sdSettingsStatus");
    var connectionFailure = field("connectionFailure");
    View root = activity.getWindow().getDecorView();
    test.runOnMainSync(
        () -> {
          try {
            var lookup = (CameraSourceCheck) sourceCheck.get(activity);
            var storedSettingsStatus = (TextView) settingsStatus.get(activity);
            harness.setUnavailableObservation();
            assertUnavailableEntry(
                activity, consume, false, lookup, statusLoad, storedSettingsStatus, retry);
            assertUnavailableEntry(
                activity, consume, true, lookup, statusLoad, storedSettingsStatus, retry);
            require(
                harness.queued() == 0 && harness.claims() == 0,
                "Neither manual entry can queue a claim with an incomplete observation");
            require(
                !((Boolean) invoke(consume, activity)), "An unavailable entry is consumed once");
            bringIntoView(root.findViewWithTag("sd-reprobe-status"));
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
        });
    capture(test, activity.getWindow(), directory, width, font, "observation-unavailable");
    test.runOnMainSync(
        () -> {
          try {
            var lookup = (CameraSourceCheck) sourceCheck.get(activity);
            var storedSettingsStatus = (TextView) settingsStatus.get(activity);
            harness.setPermitted();
            harness.controller().stop();
            // Model the resumed foreground with a failed subscription and no registered observer.
            foreground.setBoolean(harness.controller(), true);
            require(observer.get(harness.controller()) == null, "No media observer is registered");
            assertUnavailableEntry(
                activity, consume, false, lookup, statusLoad, storedSettingsStatus, reopen);
            assertUnavailableEntry(
                activity, consume, true, lookup, statusLoad, storedSettingsStatus, reopen);
            View action = root.findViewWithTag("sd-reprobe-start");
            require(action.isEnabled() && action.performClick(), "Tap retry without observers");
            TextView status = root.findViewWithTag("sd-reprobe-status");
            require(
                status.getText().toString().contains(reopen)
                    && !status.getText().toString().contains(retry)
                    && observer.get(harness.controller()) == null
                    && harness.queued() == 0
                    && harness.claims() == 0
                    && !lookup.isPending()
                    && !ImportProgress.INSTANCE.getActive(),
                "Retry requires reopening when the observer is still absent and starts no import");
            bringIntoView(status);
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
        });
    capture(test, activity.getWindow(), directory, width, font, "observer-unavailable");
    test.runOnMainSync(
        () -> {
          try {
            harness.controller().pause();
            harness.start();
            require(
                observer.get(harness.controller()) != null && harness.queued() == 0,
                "Reentry registers an observer without starting a refresh by itself");
            var lookup = (CameraSourceCheck) sourceCheck.get(activity);
            var app = test.getTargetContext();
            harness.setUnsupportedLayout();
            activity.onNewIntent(CameraImportActivity.manualImportIntent(app, false));
            require(
                !((Boolean) invoke(consume, activity)),
                "A known unsupported layout still uses the ordinary open path");
            Object priorFailure = connectionFailure.get(activity);
            try {
              activity.onNewIntent(CameraImportActivity.manualImportIntent(app, true));
              require(
                  (Boolean) invoke(consume, activity) && lookup.isPending(),
                  "A known unsupported layout still starts ordinary quick discovery");
            } finally {
              invoke(discard, activity);
              connectionFailure.set(activity, priorFailure);
            }
            require(
                harness.queued() == 0 && harness.claims() == 0,
                "Ordinary unsupported fallback cannot queue a claim");
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
        });
  }

  private static void assertUnavailableEntry(
      CameraImportActivity activity,
      java.lang.reflect.Method consume,
      boolean keepOriginals,
      CameraSourceCheck lookup,
      Field statusLoad,
      TextView settingsStatus,
      String instruction)
      throws IllegalAccessException {
    activity.renderSdSource(CameraQuickImportSources.SdState.READY);
    activity.onNewIntent(CameraImportActivity.manualImportIntent(activity, keepOriginals));
    require(
        (Boolean) invoke(consume, activity), "An unavailable manual entry does not fall through");
    require(
        !lookup.isPending()
            && statusLoad.get(activity) == null
            && !ImportProgress.INSTANCE.getActive(),
        "An unavailable manual entry cannot scan the stale source or import it");
    View root = activity.getWindow().getDecorView();
    TextView status = root.findViewWithTag("sd-reprobe-status");
    require(
        status.getText().toString().contains(instruction),
        "Show the recovery action for this unavailable state");
    require(
        root.findViewWithTag("sd-import-status").getVisibility() == View.GONE
            && settingsStatus.getVisibility() == View.GONE
            && settingsStatus.getText().length() == 0,
        "An unavailable entry discards both copies of the previous source status");
  }

  private static void checkSettingsAfterFailedRetry(
      Instrumentation test,
      CameraImportActivity activity,
      File directory,
      int width,
      float font,
      SdUsbReprobeFlowChecks.Harness harness)
      throws Exception {
    var dialogField = field("sdSettingsDialog");
    var cachedStatus = field("sdSettingsStatus");
    var dialog =
        new java.util.concurrent.atomic.AtomicReference<androidx.appcompat.app.AlertDialog>();
    test.runOnMainSync(
        () -> {
          try {
            View root = activity.getWindow().getDecorView();
            TextView failure = root.findViewWithTag("sd-reprobe-status");
            require(
                failure.getText().toString().contains("使用許可を確認できません")
                    && harness.queued() == 0
                    && harness.claims() == 0,
                "The denied explicit retry has finished without touching the reader");
            View settings = root.findViewWithTag("sd-import-settings");
            require(
                settings.isEnabled() && settings.performClick(), "Open SD settings after retry");
            dialog.set((androidx.appcompat.app.AlertDialog) dialogField.get(activity));
            TextView settingsStatus = (TextView) cachedStatus.get(activity);
            require(
                dialog.get().isShowing()
                    && settingsStatus.getText().length() == 0
                    && settingsStatus.getVisibility() == View.GONE,
                "The opened settings dialog cannot show cached success from the previous card");
            TextView sourceStatus = root.findViewWithTag("sd-import-status");
            require(
                sourceStatus.getText().length() == 0 && sourceStatus.getVisibility() == View.GONE,
                "The main card also discards its cached source status");
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
        });
    try {
      capture(
          test, dialog.get().getWindow(), directory, width, font, "settings-after-failed-retry");
    } finally {
      test.runOnMainSync(dialog.get()::dismiss);
      test.waitForIdleSync();
    }
  }

  private static void assertResultReachable(
      Instrumentation test, CameraImportActivity activity, String expected) throws Exception {
    View root = activity.getWindow().getDecorView();
    for (String tag : List.of("sd-import-start", "sd-reprobe-start", "sd-reprobe-status")) {
      View view = root.findViewWithTag(tag);
      test.runOnMainSync(() -> bringIntoView(view));
      ConsistencyLayoutChecks.frame(test, root);
      test.runOnMainSync(
          () -> {
            Rect visible = new Rect();
            require(
                view.getGlobalVisibleRect(visible)
                    && visible.height() == view.getHeight()
                    && visible.width() == view.getWidth(),
                "The full "
                    + tag
                    + " is visible after scrolling. visible="
                    + visible
                    + " size="
                    + view.getWidth()
                    + "x"
                    + view.getHeight());
            if (view instanceof Button button) {
              require(
                  button.isEnabled() && button.getHeight() >= GalleryUi.dp(activity, 48),
                  "The scrolled action can be operated " + tag);
            } else {
              TextView status = (TextView) view;
              var layout = status.getLayout();
              require(
                  expected.contentEquals(status.getText())
                      && layout != null
                      && layout.getHeight()
                          <= status.getHeight()
                              - status.getCompoundPaddingTop()
                              - status.getCompoundPaddingBottom(),
                  "The complete result text fits its layout");
              for (int line = 0; line < layout.getLineCount(); line++) {
                require(layout.getEllipsisCount(line) == 0, "Result text does not use ellipsis");
              }
            }
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

  static void capture(
      Instrumentation test, Window window, File directory, int width, float font, String state)
      throws Exception {
    ConsistencyLayoutChecks.frame(test, window.getDecorView());
    Bitmap screenshot = test.getUiAutomation().takeScreenshot();
    require(screenshot != null, "Composed Android screenshot " + state);
    try (FileOutputStream output =
        new FileOutputStream(new File(directory, width + "-" + font + "-" + state + ".png"))) {
      require(
          screenshot.compress(Bitmap.CompressFormat.PNG, 100, output), "Write screenshot " + state);
    } finally {
      screenshot.recycle();
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
