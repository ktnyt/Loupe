package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class CameraTabsChecks {
  static String run(Instrumentation t) throws Exception {
    var activity = t.startActivitySync(new Intent(t.getTargetContext(), CameraImportActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    var error = new AtomicReference<Throwable>();
    try {
      File captures=new File(activity.getCacheDir(),"consistency-checks");captures.mkdirs();
      String size=activity.getResources().getConfiguration().screenWidthDp+"-"+activity.getResources().getConfiguration().fontScale;
      ConsistencyLayoutChecks.captureHardware(t,activity,activity.getWindow().getDecorView(),captures,"import-"+size+"-collapsed");
      var gear=CameraImportActivity.class.getDeclaredField("settingsButton");gear.setAccessible(true);
      TagDetailChecks.ui(t,()->{try{((View)gear.get(activity)).performClick();}catch(Exception e){throw new AssertionError(e);}});
      ConsistencyLayoutChecks.captureHardware(t,activity,activity.getWindow().getDecorView(),captures,"import-"+size+"-settings");
      TagDetailChecks.ui(t,()->{try{((View)gear.get(activity)).performClick();}catch(Exception e){throw new AssertionError(e);}});
      t.runOnMainSync(() -> {
        try {
          checkImportPresentation((CameraImportActivity) activity);
          var config = new Configuration(activity.getResources().getConfiguration());
          config.fontScale = 1.3f;
          var context = new android.view.ContextThemeWrapper(activity.createConfigurationContext(config), R.style.AppTheme);
          for (boolean albums : new boolean[]{false, true}) {
            var opens = new AtomicInteger();
            var starts = new AtomicInteger();
            var changes = new AtomicInteger();
            var tabs = new LibraryTabs(context, albums, true, ignored -> changes.incrementAndGet(),
                opens::incrementAndGet, starts::incrementAndGet);
            check(tabs.getMenu().size() == 3, "disconnected import remains reachable");
            tabs.setCameraState(true, "サンプル", false, 0, 0);
            var camera = tabs.destinationView(2);
            check(camera.getVisibility() == View.VISIBLE && tabs.getMenu().size() == 3, "attached");
            check(tabs.selectedDestination() == (albums ? 1 : 0) && !camera.isSelected(), "selected peer");
            camera.performClick();
            check(opens.get() == 1 && starts.get() == 0, "tap opens");
            check(hasLongAction(camera), "long action exposed");
            camera.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null);
            check(opens.get() == 1 && starts.get() == 1, "long press starts only");
            tabs.setCameraState(true, "サンプル", true, 1, 3);
            check(androidx.core.view.ViewCompat.getStateDescription(camera).toString().contains("1 / 3"), "progress state announced");
            check(tabs.getBadge(tabs.getMenu().getItem(2).getItemId()) != null, "connected/progress badge");
            check(!camera.performLongClick() && !hasLongAction(camera), "busy quick import unavailable");
            check(opens.get() == 1 && starts.get() == 1, "no duplicate");
            layout(tabs, 320);
            check(tabs.selectedDestination() == (albums ? 1 : 0), "camera action retains destination");
            check(camera.getHeight() >= GalleryUi.dp(context, 48), "touch target");
            if (!albums) {
              var bitmap = Bitmap.createBitmap(tabs.getWidth(), tabs.getHeight(), Bitmap.Config.ARGB_8888);
              tabs.draw(new Canvas(bitmap));
              try (var out = new FileOutputStream(new File(activity.getCacheDir(), "camera-tabs-check.png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
              } finally { bitmap.recycle(); }
            }
            tabs.setCameraState(false, null, false, 0, 0);
            check(tabs.getMenu().size() == 3 && !hasLongAction(camera), "disconnected destination retained");
            camera.performClick();
            check(opens.get() == 2 && starts.get() == 1, "disconnected tap opens only");
            tabs.setCameraState(true, "再接続", false, 0, 0);
            tabs.destinationView(2).performClick();
            check(opens.get() == 3 && changes.get() == 0, "reattach preserves tap without changing library");
            tabs.destinationView(albums ? 0 : 1).performClick();
            check(changes.get() == 1 && tabs.selectedDestination() == (albums ? 0 : 1), "peer navigation updates selection");
            tabs.setEnabled(false);
            tabs.destinationView(albums ? 1 : 0).performClick();
            camera.performClick();camera.performLongClick();
            check(changes.get() == 1 && opens.get() == 3 && starts.get() == 1, "runtime disable blocks all destinations");
            tabs.setEnabled(true);
            check(hasLongAction(camera), "runtime enable restores valid quick import");
          }
          var locked = new LibraryTabs(context, false, false, ignored -> {},
              () -> { throw new AssertionError("selection tap"); },
              () -> { throw new AssertionError("selection long press"); });
          locked.setCameraState(true, "サンプル", false, 0, 0);
          var camera = locked.destinationView(2);
          camera.performClick();
          camera.performLongClick();
          check(!camera.isEnabled() && !hasLongAction(camera), "selection disabled");
          var request = CameraImportActivity.class.getDeclaredField("quickRequestId");
          request.setAccessible(true);
          var render = CameraImportActivity.class.getDeclaredMethod("renderStatus");
          render.setAccessible(true);
          var progress = jp.nano.rawgallery.camera.ImportProgress.INSTANCE;
          request.set(activity, "return-test");
          progress.completeRequest("unrelated", true);
          render.invoke(activity);
          check(!activity.isFinishing(), "unrelated completion stays");
          progress.completeRequest("return-test", false);
          render.invoke(activity);
          check(!activity.isFinishing(), "failed import stays");
          request.set(activity, "return-test-2");
          progress.completeRequest("return-test-2", true);
          render.invoke(activity);
          check(activity.isFinishing(), "successful quick import returns");

        } catch (Throwable failure) { error.set(failure); }
      });
      if (error.get() != null) throw new AssertionError(error.get());
      checkSettingsRoutes(t);
      return "PASS camera settings and Nano Banana routes coexist; persistent import destination through connection changes, tap/long press, busy/selection guards and 320dp/1.3x layout, successful quick import returns (synthetic device).";
    } finally { t.runOnMainSync(activity::finish); }
  }

  private static void checkImportPresentation(CameraImportActivity activity) throws Exception {
    var panelField = CameraImportActivity.class.getDeclaredField("importSettings");
    var gearField = CameraImportActivity.class.getDeclaredField("settingsButton");
    var runField = CameraImportActivity.class.getDeclaredField("run");
    var autoField = CameraImportActivity.class.getDeclaredField("auto");
    var migrateField = CameraImportActivity.class.getDeclaredField("migrate");
    for (var field : new java.lang.reflect.Field[]{panelField, gearField, runField, autoField, migrateField})
      field.setAccessible(true);
    View panel = (View) panelField.get(activity);
    View gear = (View) gearField.get(activity);
    View run = (View) runField.get(activity);
    check(panel.getVisibility() == View.GONE, "initial import screen focuses on connection and progress");
    check(run.getMinimumHeight() >= GalleryUi.dp(activity, 56), "primary import touch target");
    check("取り込み設定".contentEquals(gear.getContentDescription()), "settings gear accessible name");
    gear.performClick();
    check(panel.getVisibility() == View.VISIBLE, "settings explicitly reachable");
    check(((View) autoField.get(activity)).getVisibility() == View.VISIBLE
        && ((View) migrateField.get(activity)).getVisibility() == View.VISIBLE,
        "automatic import and CameraSync migration remain reachable");
    gear.performClick();
    var render = CameraImportActivity.class.getDeclaredMethod("renderStatus");
    render.setAccessible(true);
    render.invoke(activity);
    check(panel.getVisibility() == View.GONE, "status refresh preserves collapsed settings");
    gear.performClick();
    check(panel.getVisibility() == View.VISIBLE, "settings reopen without reconstruction");
    gear.performClick();
  }

  private static void checkSettingsRoutes(Instrumentation test) throws Exception {
    MainActivity main = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    try {
      GalleryScreenChecks.finishInitialIndex(test, main);
      var menu = MainActivity.class.getDeclaredMethod("galleryMenu");
      menu.setAccessible(true);
      for (Class<?> destination : new Class<?>[]{CameraImportActivity.class, NanoBananaSettingsActivity.class}) {
        var monitor = test.addMonitor(destination.getName(), null, false);
        try {
          android.app.Dialog[] sheet = {null};
          TagDetailChecks.ui(test, () -> {
            try { sheet[0] = (android.app.Dialog) menu.invoke(main); }
            catch (ReflectiveOperationException e) { throw new AssertionError(e); }
          });
          test.waitForIdleSync();
          ConsistencyLayoutChecks.frame(test, sheet[0].getWindow().getDecorView());
          TagDetailChecks.ui(test, () -> {
            var root = sheet[0].getWindow().getDecorView();
            var camera = TagDetailChecks.button(root, "カメラから取り込む");
            var nano = TagDetailChecks.button(root, "Nano Banana");
            check(camera != null && nano != null, "settings routes coexist without USB");
            (destination == CameraImportActivity.class ? camera : nano).performClick();
          });
          var opened = test.waitForMonitorWithTimeout(monitor, 5000);
          check(opened != null && destination.isInstance(opened), "settings activity reached " + destination.getSimpleName());
          TagDetailChecks.ui(test, opened::finish);
          test.waitForIdleSync();
        } finally { test.removeMonitor(monitor); }
      }
    } finally { TagDetailChecks.ui(test, main::finish); }
  }
  private static boolean hasLongAction(View view) {
    return view.createAccessibilityNodeInfo().getActionList().stream()
        .anyMatch(action -> action.getId() == AccessibilityNodeInfo.ACTION_LONG_CLICK);
  }
  private static void layout(View view, int widthDp) {
    int width = GalleryUi.dp(view.getContext(), widthDp);
    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    view.layout(0, 0, width, view.getMeasuredHeight());
  }
  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
