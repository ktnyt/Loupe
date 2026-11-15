package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;

/** Offline presentation checks: no camera, import, preference mutation, or BLE operation. */
final class Ep7WifiChecks {
  static String run(Instrumentation t) throws Exception {
    CameraImportActivity camera = (CameraImportActivity) t.startActivitySync(new Intent(t.getTargetContext(), CameraImportActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    Ep7WifiActivity wifi = null;
    CameraBleActivity ble = null;
    try {
      t.waitForIdleSync();
      TagDetailChecks.ui(t, () -> {
        View root = camera.getWindow().getDecorView();
        require(root, "E-P7からWi-Fiで取り込む");
        require(root, "SDカードのフォルダを選ぶ");
        require(root, "SDカードの写真を取り込む");
        require(root, "SDカード接続時に自動で取り込む");
        require(root, "内容も確認する");
      });
      capture(t, camera, "camera-import");
      wifi = (Ep7WifiActivity) t.startActivitySync(new Intent(t.getTargetContext(), Ep7WifiActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      t.waitForIdleSync();
      final Ep7WifiActivity screen = wifi;
      TagDetailChecks.ui(t, () -> {
        View root = screen.getWindow().getDecorView();
        for (String label : new String[]{"Wi-Fi設定を開く", "Bluetoothでカメラの起動を試す", "写真を取り込む", "中止", "内容も確認する", "カメラのWi-Fi接続時に自動で取り込む"}) {
          TextView v = require(root, label);
          check(v.getMinimumHeight() >= GalleryUi.dp(screen, 48), label + " touch minimum");
        }
        View status = root.findViewWithTag("ep7-status");
        check(status != null && status.getAccessibilityLiveRegion() == View.ACCESSIBILITY_LIVE_REGION_POLITE, "status live region");
        check(find(root, "選んだ1枚を試験転送") == null && find(root, "試験用コピーを削除") == null, "old experiment controls removed");
        ScrollView scroll = findScroll(root); check(scroll != null, "scrolling container");
        Rect safe = new Rect(); root.getWindowVisibleDisplayFrame(safe); int[] xy = new int[2]; scroll.getLocationOnScreen(xy);
        check(xy[1] >= safe.top && xy[1] + scroll.getHeight() <= safe.bottom, "scroll stays inside safe bounds");
      });
      capture(t, wifi, "wifi-import");
      ble = (CameraBleActivity) t.startActivitySync(new Intent(t.getTargetContext(), CameraBleActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      t.waitForIdleSync();
      final CameraBleActivity bleScreen = ble;
      TagDetailChecks.ui(t, () -> {
        View root = bleScreen.getWindow().getDecorView();
        require(root, "カメラを探す"); require(root, "選んだカメラの起動を試す");
        check(!require(root, "選んだカメラの起動を試す").isEnabled(), "wake disabled before selection");
      });
      capture(t, ble, "ble-wake");
      return "PASS offline Wi-Fi, SD and BLE entry, controls, safe scroll bounds, and live-region checks; no camera or import exercised.";
    } finally {
      if (ble != null) { CameraBleActivity a = ble; TagDetailChecks.ui(t, a::finish); }
      if (wifi != null) { Ep7WifiActivity a = wifi; TagDetailChecks.ui(t, a::finish); }
      TagDetailChecks.ui(t, camera::finish);
    }
  }
  private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
  private static TextView require(View root, String label) { TextView v = find(root, label); check(v != null, "missing " + label); return v; }
  private static TextView find(View root, String label) {
    if (root instanceof TextView && label.contentEquals(((TextView) root).getText())) return (TextView) root;
    if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { TextView v = find(((ViewGroup) root).getChildAt(i), label); if (v != null) return v; }
    return null;
  }
  private static ScrollView findScroll(View root) {
    if (root instanceof ScrollView) return (ScrollView) root;
    if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { ScrollView v = findScroll(((ViewGroup) root).getChildAt(i)); if (v != null) return v; }
    return null;
  }
  private static void capture(Instrumentation t, Activity activity, String name) throws Exception {
    ConsistencyLayoutChecks.frame(t, activity.getWindow().getDecorView());
    Bitmap image = t.getUiAutomation().takeScreenshot(); check(image != null, "screenshot");
    File dir = new File(activity.getCacheDir(), "consistency-checks"); dir.mkdirs();
    String config = activity.getResources().getConfiguration().screenWidthDp + "-" + activity.getResources().getConfiguration().fontScale;
    try (FileOutputStream out = new FileOutputStream(new File(dir, name + "-" + config + ".png"))) { check(image.compress(Bitmap.CompressFormat.PNG, 100, out), "capture"); } finally { image.recycle(); }
  }
}
