package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.security.NetworkSecurityPolicy;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class Ep7WifiChecks {
  static String run(Instrumentation t) throws Exception {
    Ep7WifiActivity activity = (Ep7WifiActivity) t.startActivitySync(
        new Intent(t.getTargetContext(), Ep7WifiActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    File captures = new File(activity.getCacheDir(), "consistency-checks");
    captures.mkdirs();
    int width = activity.getResources().getConfiguration().screenWidthDp;
    String font = Float.toString(activity.getResources().getConfiguration().fontScale);
    String prefix = "ep7-wifi-";
    try {
      t.waitForIdleSync();
      Fields f = new Fields(activity);
      t.getUiAutomation().waitForIdle(300, 5000);
      TagDetailChecks.ui(t, () -> {
        check(!f.busy(), "launch remains idle; no automatic fetch");
        check("待機中".contentEquals(f.status().getText()), "initial status");
        check(f.status().getAccessibilityLiveRegion() == View.ACCESSIBILITY_LIVE_REGION_POLITE,
            "status announces changes politely");
        check(!f.download().isEnabled() && !f.stop().isEnabled(), "single-file transfer and cancel disabled initially");
        check(f.files().getAdapter() == null || f.files().getAdapter().getCount() == 0, "no listed files initially");
        checkSafeParents(activity, f);
      });
      capture(t, activity, captures, prefix + "initial-" + width + "-" + font + ".png");
      NetworkSecurityPolicy policy = NetworkSecurityPolicy.getInstance();
      check(policy.isCleartextTrafficPermitted("192.168.0.10"), "camera address cleartext exception");
      check(!policy.isCleartextTrafficPermitted("example.com"), "cleartext denied for public host");
      check(!policy.isCleartextTrafficPermitted("abc.192.168.0.10"), "cleartext denied for camera-like subdomain");
      check(!policy.isCleartextTrafficPermitted(), "global cleartext default denied");

      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      invokeOperation(activity, "合成処理中…", cancel -> {
        entered.countDown();
        if (!release.await(30, TimeUnit.SECONDS)) throw new IOException("fixture timeout");
        throw new IOException("synthetic list failure");
      });
      try {
        check(entered.await(5, TimeUnit.SECONDS), "synthetic operation entered");
        TagDetailChecks.ui(t, () -> {
          check(f.busy(), "busy during synthetic operation");
          check(f.progress().getVisibility() == View.VISIBLE, "busy progress visible");
          check(!f.download().isEnabled() && f.stop().isEnabled(), "transfer remains disabled and cancel is available while busy");
        });
        capture(t, activity, captures, prefix + "busy-" + width + "-" + font + ".png");
      } finally { release.countDown(); }
      waitFor(t, activity, () -> !f.busy() && f.status().getText().toString().startsWith("試験に失敗しました。"));
      TagDetailChecks.ui(t, () -> {
        check(f.progress().getVisibility() == View.GONE, "progress hidden after failure");
      });
      capture(t, activity, captures, prefix + "failure-" + width + "-" + font + ".png");

      CountDownLatch cancelEntered = new CountDownLatch(1);
      CountDownLatch cancelRelease = new CountDownLatch(1);
      invokeOperation(activity, "合成中止試験…", cancel -> {
        cancelEntered.countDown();
        try { cancelRelease.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (cancel.get()) throw new IOException("cancelled fixture");
        return "合成結果 fixture";
      });
      check(cancelEntered.await(5, TimeUnit.SECONDS), "cancel fixture entered");
      TagDetailChecks.ui(t, () -> {
        check(f.busy() && f.stop().isEnabled(), "cancel control available while operation is busy");
        try {
          var onStop = Ep7WifiActivity.class.getDeclaredMethod("onStop");
          onStop.setAccessible(true);
          onStop.invoke(activity);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        check(f.cancelled().get(), "stop requests cancellation");
      });
      cancelRelease.countDown();
      waitFor(t, activity, () -> !f.busy() && f.status().getText().toString().startsWith("中止しました。"));

      var listedPhotos = jp.nano.rawgallery.camera.Ep7WifiClient.parseList(
          "VER_100\n/DCIM/100OLYMP,P1010001.JPG,2097152,0,0,0\n/DCIM/100OLYMP,P1010001.ORF,4194304,0,0,0\n",
          "/DCIM/100OLYMP");
      TagDetailChecks.ui(t, () -> {
        try {
          var entries = Ep7WifiActivity.class.getDeclaredField("entries");
          entries.setAccessible(true);
          entries.set(activity, new ArrayList<>(listedPhotos));
          var render = Ep7WifiActivity.class.getDeclaredMethod("render");
          render.setAccessible(true);
          render.invoke(activity);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        f.files().setAdapter(activity.photoAdapter(listedPhotos));
        f.status().setText("合成一覧 fixture（実際のカメラ一覧ではありません）\n2枚のJPEG・ORF · 一覧取得 0.02秒");
        check(!f.busy() && f.list().isEnabled() && f.download().isEnabled(), "synthetic list enables selection and transfer controls");
        check(f.files().getAdapter().getCount() == 2, "synthetic fixture uses core parser entries");
      });
      ConsistencyLayoutChecks.frame(t, activity.getWindow().getDecorView());
      TagDetailChecks.ui(t, () -> {
        f.files().requestRectangleOnScreen(new Rect(0, 0, f.files().getWidth(), f.files().getHeight()), true);
      });
      ConsistencyLayoutChecks.frame(t, activity.getWindow().getDecorView());
      TagDetailChecks.ui(t, () -> {
        TextView selected = (TextView) f.files().getSelectedView();
        check(selected != null && selected.getText().toString().startsWith("JPG · "), "selected JPEG format visible");
        check(selected.getLayout().getEllipsisCount(0) == 0 && selected.getLineCount() >= 2, "selected photo wraps without losing its format");
        f.files().performClick();
      });
      t.getUiAutomation().waitForIdle(200, 5000);
      var tree = t.getUiAutomation().getRootInActiveWindow();
      check(tree != null && !tree.findAccessibilityNodeInfosByText("ORF · 4.0 MiB").isEmpty()
          && !tree.findAccessibilityNodeInfosByText("JPG · 2.0 MiB").isEmpty(), "dropdown exposes both formats for matching basenames");
      capture(t, activity, captures, prefix + "dropdown-" + width + "-" + font + ".png");
      t.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
      t.getUiAutomation().waitForIdle(200, 5000);
      capture(t, activity, captures, prefix + "list-" + width + "-" + font + ".png");

      String syntheticSuccess = "合成表示 fixture（実際の転送ではありません）\n保存と読戻し照合が完了しました\n"
          + "2.00 MiB · 転送 0.50秒 · 4.00 MiB/s\n保存検証を含む全体 0.75秒\n受信・保存SHA-256\n"
          + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
      invokeOperation(activity, "合成成功表示を準備しています…", cancel -> syntheticSuccess);
      waitFor(t, activity, () -> !f.busy() && syntheticSuccess.contentEquals(f.status().getText()));
      TagDetailChecks.ui(t, () -> {
        check(!f.busy() && f.list().isEnabled() && f.download().isEnabled(), "synthetic success returns to idle with list and transfer enabled");
      });
      ConsistencyLayoutChecks.frame(t, activity.getWindow().getDecorView());
      TagDetailChecks.ui(t, () -> f.status().requestRectangleOnScreen(new Rect(0, 0, f.status().getWidth(), f.status().getHeight()), true));
      capture(t, activity, captures, prefix + "success-fixture-" + width + "-" + font + ".png");

      ConsistencyLayoutChecks.frame(t, activity.getWindow().getDecorView());
      TagDetailChecks.ui(t, () -> {
        ScrollView scroll = f.scroll();
        scroll.fullScroll(View.FOCUS_DOWN);
      });
      ConsistencyLayoutChecks.frame(t, activity.getWindow().getDecorView());
      TagDetailChecks.ui(t, () -> {
        checkVisibleInParent(f.stop(), f.scroll(), "cancel action reachable in scroll viewport");
        checkVisibleInParent(f.clear(), f.scroll(), "cleanup action reachable in scroll viewport");
        check(f.wifi().getMinimumHeight() >= GalleryUi.dp(activity, 48), "Wi-Fi settings touch target");
        check(f.list().getMinimumHeight() >= GalleryUi.dp(activity, 48), "list touch target");
        check(f.download().getMinimumHeight() >= GalleryUi.dp(activity, 48), "transfer touch target");
        check(f.stop().getMinimumHeight() >= GalleryUi.dp(activity, 48), "cancel touch target");
        check(f.clear().getMinimumHeight() >= GalleryUi.dp(activity, 48), "cleanup touch target");
      });

      checkSettingsRoute(t, captures, width, font);
      return "PASS E-P7 Wi-Fi idle/no-autofetch state, disabled transfer/cancel, status live region, safe toolbar/scroll bounds, 48dp actions, synthetic list/busy/failure/cancel/success states, CameraImport settings route, and scoped cleartext policy; no network request issued.";
    } finally {
      TagDetailChecks.ui(t, activity::finish);
    }
  }

  private static void checkSettingsRoute(Instrumentation t, File parentCaptures, int width, String font) throws Exception {
    CameraImportActivity camera = (CameraImportActivity) t.startActivitySync(
        new Intent(t.getTargetContext(), CameraImportActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      t.waitForIdleSync();
      var monitor = t.addMonitor(Ep7WifiActivity.class.getName(), null, false);
      try {
        TagDetailChecks.ui(t, () -> {
          try {
            var settings = CameraImportActivity.class.getDeclaredField("settingsButton");
            settings.setAccessible(true);
            ((View) settings.get(camera)).performClick();
            var field = CameraImportActivity.class.getDeclaredField("importSettings");
            field.setAccessible(true);
            View panel = (View) field.get(camera);
            check(panel.getVisibility() == View.VISIBLE, "camera settings expanded");
            var route = findText(panel, "E-P7のWi-Fi転送を試す");
            check(route != null, "Wi-Fi trial route is present in camera settings");
            ScrollView scroll = findScroll(camera.getWindow().getDecorView());
            check(scroll != null, "camera import settings scroll parent exists");
            scroll.fullScroll(View.FOCUS_DOWN);
          } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        t.waitForIdleSync();
        capture(t, camera, parentCaptures, "ep7-wifi-settings-" + width + "-" + font + ".png");
        TagDetailChecks.ui(t, () -> {
          try {
            var field = CameraImportActivity.class.getDeclaredField("importSettings");
            field.setAccessible(true);
            var route = findText((View) field.get(camera), "E-P7のWi-Fi転送を試す");
            check(route != null, "Wi-Fi trial route remains present after capture");
            route.performClick();
          } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        var opened = t.waitForMonitorWithTimeout(monitor, 5000);
        check(opened instanceof Ep7WifiActivity, "CameraImport opens E-P7 Wi-Fi trial");
        if (opened != null) TagDetailChecks.ui(t, opened::finish);
      } finally { t.removeMonitor(monitor); }
    } finally { TagDetailChecks.ui(t, camera::finish); }
  }

  private static void checkSafeParents(Ep7WifiActivity activity, Fields f) {
    View decor = activity.getWindow().getDecorView();
    Rect safe = new Rect();
    decor.getWindowVisibleDisplayFrame(safe);
    Rect toolbar = bounds(f.toolbar());
    Rect scroll = bounds(f.scroll());
    check(toolbar.top >= safe.top && toolbar.bottom <= safe.bottom, "toolbar inside safe display bounds");
    check(scroll.top >= safe.top && scroll.bottom <= safe.bottom, "scroll parent inside safe display bounds");
    for (View action : new View[]{f.wifi(), f.list(), f.files(), f.download(), f.stop(), f.clear()}) {
      check(descendantOf(action, f.scroll()), "action belongs to scroll parent");
      check(action.getMinimumHeight() >= GalleryUi.dp(activity, 48), "action minimum touch target");
    }
  }

  private static void checkVisibleInParent(View view, ViewGroup parent, String message) {
    Rect r = bounds(view), p = bounds(parent);
    check(Rect.intersects(r, p) && p.contains(r), message);
  }

  private static Rect bounds(View view) {
    int[] xy = new int[2]; view.getLocationOnScreen(xy);
    return new Rect(xy[0], xy[1], xy[0] + view.getWidth(), xy[1] + view.getHeight());
  }

  private static boolean descendantOf(View view, ViewGroup parent) {
    View current = view;
    while (current != null) {
      if (current == parent) return true;
      if (!(current.getParent() instanceof View)) return false;
      current = (View) current.getParent();
    }
    return false;
  }

  private static TextView findText(View root, String label) {
    if (root instanceof TextView && label.contentEquals(((TextView) root).getText())) return (TextView) root;
    if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
      TextView found = findText(((ViewGroup) root).getChildAt(i), label);
      if (found != null) return found;
    }
    return null;
  }

  private static ScrollView findScroll(View view) {
    if (view instanceof ScrollView) return (ScrollView) view;
    if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
      ScrollView found = findScroll(((ViewGroup) view).getChildAt(i));
      if (found != null) return found;
    }
    return null;
  }

  private static void capture(Instrumentation t, android.app.Activity activity, File dir, String name) throws Exception {
    ConsistencyLayoutChecks.frame(t, activity.getWindow().getDecorView());
    Bitmap image = t.getUiAutomation().takeScreenshot();
    if (image == null) throw new AssertionError("screenshot unavailable: " + name);
    try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
      if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new AssertionError("screenshot write failed: " + name);
    } catch (IOException error) { throw new AssertionError(error); }
    finally { image.recycle(); }
  }

  private static void invokeOperation(Ep7WifiActivity activity, String message,
      Ep7WifiActivityOperation operation) throws Exception {
    Class<?> operationType = Class.forName("jp.nano.rawgallery.Ep7WifiActivity$Operation");
    Object proxy = Proxy.newProxyInstance(operationType.getClassLoader(), new Class<?>[]{operationType},
        (instance, method, args) -> method.getName().equals("run")
            ? operation.run((AtomicBoolean) args[0]) : null);
    var execute = Ep7WifiActivity.class.getDeclaredMethod("execute", String.class, operationType);
    execute.setAccessible(true);
    activity.runOnUiThread(() -> {
      try { execute.invoke(activity, message, proxy); }
      catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    });
  }

  @FunctionalInterface private interface Ep7WifiActivityOperation {
    String run(AtomicBoolean cancelled) throws Exception;
  }

  private static void waitFor(Instrumentation t, Ep7WifiActivity activity, Check condition) throws Exception {
    long deadline = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < deadline) {
      final boolean[] done = {false};
      TagDetailChecks.ui(t, () -> done[0] = condition.ok());
      if (done[0]) return;
      Thread.sleep(25);
    }
    throw new AssertionError("timed out waiting for synthetic operation result");
  }

  @FunctionalInterface private interface Check { boolean ok(); }

  private static final class Fields {
    private final Ep7WifiActivity a;
    private final java.lang.reflect.Field busy, cancelled, status, progress, files, download, stop, wifi, list, clear;
    private final View toolbarView;
    private final ScrollView scrollView;
    Fields(Ep7WifiActivity activity) throws Exception {
      a = activity;
      busy = field("busy"); cancelled = field("cancelled"); status = field("status"); progress = field("progress");
      files = field("files"); download = field("download"); stop = field("stop"); wifi = field("wifi"); list = field("list"); clear = field("clear");
      View decor = activity.getWindow().getDecorView();
      TextView title = findText(decor, "E-P7 Wi-Fi試験");
      toolbarView = title != null && title.getParent() instanceof View ? (View) title.getParent() : null;
      scrollView = findScroll(decor);
      if (toolbarView == null || scrollView == null) throw new AssertionError("toolbar/scroll parent missing");
    }
    private java.lang.reflect.Field field(String name) throws Exception { var f = Ep7WifiActivity.class.getDeclaredField(name); f.setAccessible(true); return f; }
    boolean busy() { return get(busy, Boolean.class); }
    AtomicBoolean cancelled() { return get(cancelled, AtomicBoolean.class); }
    TextView status() { return get(status, TextView.class); }
    View progress() { return get(progress, View.class); }
    android.widget.Spinner files() { return get(files, android.widget.Spinner.class); }
    View download() { return get(download, View.class); }
    View stop() { return get(stop, View.class); }
    View wifi() { return get(wifi, View.class); }
    View list() { return get(list, View.class); }
    View clear() { return get(clear, View.class); }
    ScrollView scroll() { return scrollView; }
    View toolbar() { return toolbarView; }
    private <T> T get(java.lang.reflect.Field f, Class<T> type) { try { return type.cast(f.get(a)); } catch (IllegalAccessException e) { throw new AssertionError(e); } }
  }

  private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
