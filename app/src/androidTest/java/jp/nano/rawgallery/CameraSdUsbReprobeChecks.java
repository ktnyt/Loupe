package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
    test.runOnMainSync(
        () -> {
          try {
            checkSettingsRecipients(type, constructor);
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
        });
    var cancelCount = new AtomicInteger();
    var positiveCount = new AtomicInteger();
    for (String name : List.of("prepare", "confirm")) {
      boolean preparing = name.equals("prepare");
      var show = type.getDeclaredMethod(name, Runnable.class, Runnable.class);
      show.setAccessible(true);
      showFakeDialog(test, show, dialogs, positiveCount, cancelCount);
      capture(test, directory, width, font, "dialog-" + name);
      var automation = test.getUiAutomation();
      var root = automation.getRootInActiveWindow();
      require(root != null, "Dialog has an accessibility window " + name);
      var positiveLabel = preparing ? "設定を開く" : "取り出し済み";
      var safetyText = preparing ? "各カードにパーティションが1つ" : "フォーマットやマウントが進行中";
      require(
          !root.findAccessibilityNodeInfosByText(safetyText).isEmpty(),
          "Safety instructions are exposed to accessibility " + name);
      if (preparing) {
        require(
            !root.findAccessibilityNodeInfosByText("カードをすべて取り出してください").isEmpty()
                && !root.findAccessibilityNodeInfosByText("フォーマットは選ばないでください").isEmpty(),
            "Show all-cards and no-format instructions before opening Settings");
      }
      dialogButton(root, positiveLabel);
      dialogButton(root, "キャンセル");
      var needsScroll = new AtomicBoolean();
      test.runOnMainSync(
          () -> needsScroll.set(checkDialogBounds(activity, positiveLabel).canScrollVertically(1)));
      if (needsScroll.get()) {
        int previousY = 0;
        for (int page = 0; page < 5 && needsScroll.get(); page++) {
          var scroll = scrollable(root);
          require(
              scroll != null && scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD),
              "Overflowing dialog instructions can scroll through accessibility");
          int beforeScroll = previousY;
          var position = new AtomicInteger();
          long deadline = SystemClock.uptimeMillis() + 1500;
          do {
            SystemClock.sleep(80);
            test.runOnMainSync(
                () -> {
                  var body = checkDialogBounds(activity, positiveLabel);
                  position.set(body.getScrollY());
                  needsScroll.set(body.canScrollVertically(1));
                });
          } while (position.get() == beforeScroll && SystemClock.uptimeMillis() < deadline);
          require(
              position.get() > beforeScroll,
              "The instruction body scrolled while actions stayed fully visible");
          previousY = position.get();
          root = automation.getRootInActiveWindow();
          require(root != null, "Scrolled dialog retains its window " + name);
        }
        require(!needsScroll.get(), "Entire dialog instructions are reachable " + name);
        capture(test, directory, width, font, "dialog-" + name + "-scrolled");
      }
      tapDialogButton(test, dialogButton(root, "キャンセル"));
      require(cancelCount.get() == (preparing ? 1 : 2), "The visible cancel action works " + name);
      showFakeDialog(test, show, dialogs, positiveCount, cancelCount);
      root = automation.getRootInActiveWindow();
      require(root != null, "Reopened dialog retains its window " + name);
      tapDialogButton(test, dialogButton(root, positiveLabel));
      require(
          positiveCount.get() == (preparing ? 1 : 2), "The visible positive action works " + name);
    }
  }

  private static void showFakeDialog(
      Instrumentation test,
      Method show,
      Object dialogs,
      AtomicInteger positive,
      AtomicInteger cancel) {
    test.runOnMainSync(
        () -> {
          try {
            show.invoke(
                dialogs, (Runnable) positive::incrementAndGet, (Runnable) cancel::incrementAndGet);
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
        });
    test.waitForIdleSync();
  }

  private static void tapDialogButton(Instrumentation test, AccessibilityNodeInfo button) {
    var bounds = new Rect();
    button.getBoundsInScreen(bounds);
    long start = SystemClock.uptimeMillis();
    for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
      var event =
          MotionEvent.obtain(
              start,
              SystemClock.uptimeMillis(),
              action,
              bounds.exactCenterX(),
              bounds.exactCenterY(),
              0);
      try {
        test.sendPointerSync(event);
      } finally {
        event.recycle();
      }
    }
    test.waitForIdleSync();
  }

  private static ScrollView checkDialogBounds(Activity activity, String positiveLabel) {
    for (var window : WindowInspector.getGlobalWindowViews()) {
      ScrollView body = window.findViewWithTag("sd-reprobe-dialog-body");
      if (body == null || !body.isShown()) continue;
      var positive = (Button) window.findViewById(android.R.id.button1);
      var negative = (Button) window.findViewById(android.R.id.button2);
      require(positiveLabel.contentEquals(positive.getText()), "Actual positive button");
      require("キャンセル".contentEquals(negative.getText()), "Actual cancel button");
      var visibleBody = new Rect();
      require(body.getGlobalVisibleRect(visibleBody), "Visible instructions");
      for (Button button : List.of(positive, negative)) {
        var visible = new Rect();
        var local = new Rect();
        require(
            button.getLocalVisibleRect(local)
                && local.equals(new Rect(0, 0, button.getWidth(), button.getHeight()))
                && button.getHeight() >= GalleryUi.dp(activity, 48)
                && button.getGlobalVisibleRect(visible)
                && !Rect.intersects(visibleBody, visible),
            "Full 48dp action visible outside the scroll body: " + button.getText() + " " + local);
      }
      return body;
    }
    throw new AssertionError("Expected a visible re-detection dialog");
  }

  private static void checkSettingsRecipients(Class<?> type, Constructor<?> ctor)
      throws ReflectiveOperationException {
    var open = type.getDeclaredMethod("openSettings");
    open.setAccessible(true);
    for (int unavailableCount = 0; unavailableCount <= 2; unavailableCount++) {
      var activity = new SettingsLauncher(unavailableCount);
      boolean exhausted = false;
      try {
        open.invoke(ctor.newInstance(activity));
      } catch (InvocationTargetException failed) {
        if (!(failed.getCause() instanceof ActivityNotFoundException)) throw failed;
        exhausted = true;
      }
      require(exhausted == (unavailableCount == 2), "Propagate unavailable pinned routes");
      var actions =
          unavailableCount == 0
              ? List.of(Settings.ACTION_MEMORY_CARD_SETTINGS)
              : List.of(
                  Settings.ACTION_MEMORY_CARD_SETTINGS, Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
      require(
          actions.equals(activity.attempts.stream().map(Intent::getAction).toList())
              && activity.attempts.stream()
                  .allMatch(intent -> "com.android.settings".equals(intent.getPackage())),
          "Both Settings actions can reach only the Android Settings package");
    }
  }

  private static final class SettingsLauncher extends Activity {
    final List<Intent> attempts = new ArrayList<>();
    final int unavailableCount;

    SettingsLauncher(int unavailableCount) {
      this.unavailableCount = unavailableCount;
    }

    @Override
    public void startActivity(Intent intent) {
      attempts.add(intent);
      if (attempts.size() <= unavailableCount) throw new ActivityNotFoundException();
    }
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
