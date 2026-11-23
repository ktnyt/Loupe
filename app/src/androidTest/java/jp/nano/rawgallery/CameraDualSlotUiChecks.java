package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import jp.nano.rawgallery.camera.ImportProgress;

/** Captures the actual confirmation for already validated synthetic cards. No source or service is opened. */
final class CameraDualSlotUiChecks {
  static final String MODE = "camera-pilot-dual-slot-ui";
  private static final List<String> ISOLATED_PACKAGES = List.of("jp.nano.rawgallery.sd162", "jp.nano.rawgallery.sd166");
  private static final String SOURCE_ID = "166A-0001";
  private static final String TARGET_ID = "166B-0002";
  private static final String TITLE = "別のSDカードへ取り込む";
  private static final String WARNING = "この方法では動画を取り込めません。";
  private static final String CONFIRM = "取り込む";
  private static final String CANCEL = "キャンセル";
  private static final String SOURCE_LABEL = "取り込み元　USBストレージ（" + SOURCE_ID + "）";
  private static final String TARGET_LABEL = "保存先　USBストレージ（" + TARGET_ID + "）";
  private static final Uri TREE = DocumentsContract.buildTreeDocumentUri(
      "com.android.externalstorage.documents", SOURCE_ID + ":DCIM");

  private record Scenario(String kind, String capture, boolean jpeg, boolean raw, boolean videoWarning) {}

  static String run(Instrumentation test, int width, float font) throws Exception {
    var context = test.getTargetContext();
    require(ISOLATED_PACKAGES.contains(context.getPackageName()), "Dual-slot UI capture requires the isolated APK");
    require("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE),
        "Dual-slot UI capture requires an Android emulator");
    require((width == 400 && font == 1f) || (width == 320 && font == 1.3f),
        "Use the 400dp/1.0 or 320dp/1.3 capture profile");

    var importSettings = context.getSharedPreferences("camera_import", 0);
    var settings = context.getSharedPreferences("settings", 0);
    var originalImportSettings = new HashMap<>(importSettings.getAll());
    var originalSettings = new HashMap<>(settings.getAll());
    File directory = new File(context.getCacheDir(), "camera-dual-slot-checks");
    require(directory.isDirectory() || directory.mkdirs(), "Create dual-slot capture cache directory");

    var progress = ImportProgress.INSTANCE;
    require(!progress.getActive(), "Import is idle before opening the fixture");
    AtomicBoolean started = new AtomicBoolean();
    Runnable observeProgress = () -> {
      if (progress.getActive()) started.set(true);
    };
    progress.addListener(observeProgress);
    CameraImportActivity activity = null;
    try {
      activity = (CameraImportActivity) test.startActivitySync(new Intent(context, CameraImportActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      assertProfile(test, activity, width, font);
      UiAutomation automation = test.getUiAutomation();
      for (var scenario : List.of(new Scenario("JPEG", "jpeg-only", true, false, false),
          new Scenario("RAW", "raw-only", false, true, false),
          new Scenario("VIDEO", "video-only", false, false, true))) {
        capture(test, automation, activity, directory, width, font, scenario);
        require(!started.get() && !progress.getActive(), "Cancel never starts import progress");
      }
      require(originalImportSettings.equals(importSettings.getAll()) && originalSettings.equals(settings.getAll()),
          "Showing, changing format selection and cancelling leave preferences unchanged");
    } finally {
      if (activity != null) {
        CameraImportActivity close = activity;
        test.runOnMainSync(close::finish);
        test.waitForIdleSync();
      }
      progress.removeListener(observeProgress);
    }
    require(!started.get() && !progress.getActive(), "Closing the capture screen leaves import idle");
    return "PASS dual-slot import UI at " + width + "dp/" + font + ": same-name cards with distinct IDs,"
        + " JPEG-only and RAW-only choices, VIDEO-only warning and disabled confirm, 48dp targets,"
        + " and cancel keeps import idle; synthetic dialog only. " + directory.getAbsolutePath();
  }

  private static void assertProfile(Instrumentation test, CameraImportActivity activity, int width, float font)
      throws Exception {
    var decor = activity.getWindow().getDecorView();
    ConsistencyLayoutChecks.frame(test, decor);
    TagDetailChecks.ui(test, () -> {
      var configuration = activity.getResources().getConfiguration();
      require(configuration.screenWidthDp == width && configuration.fontScale == font,
          "The activity uses the requested capture configuration");
      require(Math.abs(decor.getWidth() - GalleryUi.dp(activity, width)) <= 1,
          "The composed activity window has the requested width");
    });
  }

  private static void capture(Instrumentation test, UiAutomation automation, CameraImportActivity activity,
      File directory, int width, float font, Scenario scenario) throws Exception {
    TagDetailChecks.ui(test, () -> {
      String target = TARGET_ID.toLowerCase(Locale.ROOT);
      var request = new CameraImportActivity.SdStart(TREE, target, target, scenario.kind(),
          jp.nano.rawgallery.camera.MountedCameraCard.detachGeneration(), true,
          activity.getSharedPreferences("camera_import", 0).getString("sd_tree_uri", null));
      activity.confirmSdKinds(request, SOURCE_LABEL + "\n" + TARGET_LABEL);
    });
    AccessibilityNodeInfo root = awaitWindow(automation, window -> hasText(window, TITLE) != null
        && hasText(window, CANCEL) != null && hasText(window, CONFIRM) != null);
    Rect safe = PhotoStorageChecks.availableDisplay(activity);
    int minimum = GalleryUi.dp(activity, 48);
    var jpeg = assertTarget(root, "JPEG", minimum, safe);
    var raw = assertTarget(root, "RAW", minimum, safe);
    var confirm = assertTarget(root, CONFIRM, minimum, safe);
    assertTarget(root, CANCEL, minimum, safe);
    require(jpeg.isCheckable() && jpeg.isEnabled() && jpeg.isChecked() == scenario.jpeg(), "Default JPEG selection");
    require(raw.isCheckable() && raw.isEnabled() && raw.isChecked() == scenario.raw(), "Default RAW selection");
    require(confirm.isEnabled() == (scenario.jpeg() || scenario.raw()), "Confirm enabled only when an image format is selected");
    var labels = find(root, node -> node.getText() != null && node.getText().toString().contains(SOURCE_LABEL)
        && node.getText().toString().contains(TARGET_LABEL));
    require(labels != null, "Source and destination name the correct distinct card IDs");
    Rect labelBounds = new Rect();
    labels.getBoundsInScreen(labelBounds);
    require(safe.contains(labelBounds), "Source and destination labels fit inside system insets");
    require(labels.getText().toString().contains(WARNING) == scenario.videoWarning(), "VIDEO warning appears on the requested path");
    saveCapture(automation, directory, "camera-dual-slot-" + width + "-" + font + "-" + scenario.capture());

    // Exercise a safe edit of the native selection, then cancel without invoking the import button.
    String toggle = scenario.raw() ? "RAW" : "JPEG";
    boolean selectedAfterClick = !scenario.jpeg() && !scenario.raw();
    click(hasText(root, toggle));
    awaitWindow(automation, window -> {
      var box = hasText(window, toggle);
      var action = hasText(window, CONFIRM);
      return box != null && action != null && box.isChecked() == selectedAfterClick
          && action.isEnabled() == selectedAfterClick;
    });
    click(hasText(automation.getRootInActiveWindow(), CANCEL));
    awaitWindow(automation, window -> hasText(window, TITLE) == null && hasText(window, "取り込み") != null);
    test.waitForIdleSync();
  }

  private static AccessibilityNodeInfo assertTarget(AccessibilityNodeInfo root, String label, int minimum, Rect safe) {
    var node = hasText(root, label);
    require(node != null, "Visible accessibility control: " + label);
    var bounds = new Rect();
    node.getBoundsInScreen(bounds);
    require(bounds.width() >= minimum && bounds.height() >= minimum && safe.contains(bounds),
        "Unclipped 48dp target for " + label + ": " + bounds);
    require(!node.isEnabled() || node.isClickable(), "Enabled accessibility control can be activated: " + label);
    return node;
  }

  private static AccessibilityNodeInfo awaitWindow(UiAutomation automation, Predicate<AccessibilityNodeInfo> ready) {
    long deadline = SystemClock.uptimeMillis() + 6000;
    do {
      var root = automation.getRootInActiveWindow();
      if (root != null && ready.test(root)) return root;
      SystemClock.sleep(60);
    } while (SystemClock.uptimeMillis() < deadline);
    throw new AssertionError("Expected dual-slot dialog or return to import screen");
  }

  private static AccessibilityNodeInfo hasText(AccessibilityNodeInfo root, String value) {
    return find(root, node -> node.getText() != null && value.contentEquals(node.getText()));
  }

  private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, Predicate<AccessibilityNodeInfo> matches) {
    if (root == null) return null;
    if (root.isVisibleToUser() && matches.test(root)) return root;
    for (int i = 0; i < root.getChildCount(); i++) {
      var result = find(root.getChild(i), matches);
      if (result != null) return result;
    }
    return null;
  }

  private static void click(AccessibilityNodeInfo node) {
    require(node != null && node.isEnabled() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK),
        "Click native format or cancel control");
  }

  private static void saveCapture(UiAutomation automation, File directory, String name) throws Exception {
    try {
      automation.waitForIdle(250, 2000);
    } catch (TimeoutException busyScreen) {
      // The import screen underneath the dialog may still report its background status reads.
    }
    require(hasText(automation.getRootInActiveWindow(), TITLE) != null, "Confirmation still owns the visible window");
    Bitmap bitmap = automation.takeScreenshot();
    require(bitmap != null, "Capture the full Android display");
    try (var output = new FileOutputStream(new File(directory, name + ".png"))) {
      require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Write Android UI capture");
    } finally {
      bitmap.recycle();
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
