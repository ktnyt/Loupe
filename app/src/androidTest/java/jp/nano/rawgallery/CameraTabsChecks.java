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
      t.runOnMainSync(() -> {
        try {
          var config = new Configuration(activity.getResources().getConfiguration());
          config.fontScale = 1.3f;
          var context = activity.createConfigurationContext(config);
          for (boolean albums : new boolean[]{false, true}) {
            var opens = new AtomicInteger();
            var starts = new AtomicInteger();
            var tabs = new LibraryTabs(context, albums, true, ignored -> {},
                opens::incrementAndGet, starts::incrementAndGet);
            var row = (ViewGroup) tabs.getChildAt(1);
            var camera = (TextView) row.getChildAt(2);
            check(camera.getVisibility() == View.GONE && columns(row) == 2, "disconnected");
            tabs.setCameraState(true, "サンプル", false, 0, 0);
            check(camera.getVisibility() == View.VISIBLE && columns(row) == 3, "attached");
            check(row.getChildAt(albums ? 1 : 0).isSelected() && !camera.isSelected(), "selected peer");
            camera.performClick();
            check(opens.get() == 1 && starts.get() == 0, "tap opens");
            check(hasLongAction(camera), "long action exposed");
            camera.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null);
            check(opens.get() == 1 && starts.get() == 1, "long press starts only");
            tabs.setCameraState(true, "サンプル", true, 1, 3);
            check(camera.performLongClick() && !hasLongAction(camera), "busy gesture swallowed");
            check(opens.get() == 1 && starts.get() == 1, "no duplicate");
            layout(tabs, 320);
            check(camera.getLayout().getLineCount() == 1, "camera label fits at 320dp");
            check(camera.getHeight() >= GalleryUi.dp(context, 48), "touch target");
            if (!albums) {
              var bitmap = Bitmap.createBitmap(tabs.getWidth(), tabs.getHeight(), Bitmap.Config.ARGB_8888);
              tabs.draw(new Canvas(bitmap));
              try (var out = new FileOutputStream(new File(activity.getCacheDir(), "camera-tabs-check.png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
              } finally { bitmap.recycle(); }
            }
            tabs.setCameraState(false, null, false, 0, 0);
            check(camera.getVisibility() == View.GONE && columns(row) == 2, "detached");
          }
          var locked = new LibraryTabs(context, false, false, ignored -> {},
              () -> { throw new AssertionError("selection tap"); },
              () -> { throw new AssertionError("selection long press"); });
          locked.setCameraState(true, "サンプル", false, 0, 0);
          var camera = ((ViewGroup) locked.getChildAt(1)).getChildAt(2);
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
      return "PASS camera tab attach/detach, tap/long press, busy/selection guards and 320dp/1.3x layout, successful quick import returns (synthetic device).";
    } finally { t.runOnMainSync(activity::finish); }
  }

  private static int columns(ViewGroup row) {
    return row.createAccessibilityNodeInfo().getCollectionInfo().getColumnCount();
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
