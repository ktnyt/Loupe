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
        check(root.findViewWithTag("wifi-import-settings") != null, "Wi-Fi pencil settings entry available");
        check(root.findViewWithTag("sd-import-settings") != null, "SD pencil settings entry available");
        require(root, "SDカードから取り込む");
        check(find(root, "SDカードのフォルダを選ぶ") == null, "SD details are outside the main screen");
        require(root, "共通履歴");
      });
      capture(t, camera, "camera-import");
      TagDetailChecks.ui(t, () ->
          camera.getWindow().getDecorView().findViewWithTag("sd-import-settings").performClick());
      t.waitForIdleSync();
      View[] sdDialog = {null};
      TagDetailChecks.ui(t, () -> {
        for (View candidate : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
          if (candidate.hasWindowFocus() && find(candidate, "SDカードのフォルダを選ぶ") != null)
            sdDialog[0] = candidate;
        }
        check(sdDialog[0] != null, "SD pencil opened the settings dialog");
        require(sdDialog[0], "SDカード接続時に自動で取り込む");
        require(sdDialog[0], "写真のアクセス権限を確認");
        TextView sdVerify = require(sdDialog[0], "内容も確認する");
        check(((android.widget.CompoundButton) sdVerify).isChecked() == camera.getSharedPreferences("camera_import", 0)
            .getBoolean("sd_verify_all", false), "SD verify reflects saved preference");
      });
      capture(t, camera, "camera-import-sd", sdDialog[0]);
      TagDetailChecks.ui(t, () -> require(sdDialog[0], "閉じる").performClick());
      if (camera.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
          && camera.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
        // Exercise the app result state; this does not simulate a native permission dialog.
        TagDetailChecks.ui(t, () -> camera.onRequestPermissionsResult(406,
            new String[]{android.Manifest.permission.ACCESS_MEDIA_LOCATION}, new int[]{android.content.pm.PackageManager.PERMISSION_DENIED}));
        t.waitForIdleSync();
        TagDetailChecks.ui(t, () -> {
          TextView denied = require(camera.getWindow().getDecorView(), "原本の保存には写真の位置情報へのアクセスが必要です。再度操作するか、アプリの権限設定を確認してください。");
          Rect visible = new Rect();
          check(denied.getGlobalVisibleRect(visible) && visible.height() == denied.getHeight(), "Denied original access guidance stays fully visible");
        });
        capture(t, camera, "camera-import-sd-denied");
      }
      TagDetailChecks.ui(t,()->findScroll(camera.getWindow().getDecorView()).fullScroll(View.FOCUS_DOWN));
      capture(t,camera,"camera-import-bottom");
      wifi = (Ep7WifiActivity) t.startActivitySync(new Intent(t.getTargetContext(), Ep7WifiActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      t.waitForIdleSync();
      final Ep7WifiActivity screen = wifi;
      TagDetailChecks.ui(t, () -> {
        View root = screen.getWindow().getDecorView();
        for (String label : new String[]{"手動でWi-Fiに接続して取り込む", "Wi-Fi設定を開く", "写真のアクセス権限を確認", "アプリの権限設定を開く", "カメラの登録を編集", "写真を取り込む", "中止", "カメラの原本も確認する", "カメラのWi-Fi接続時に自動で取り込む"}) {
          TextView v = require(root, label);
          check(v.getMinimumHeight() >= GalleryUi.dp(screen, 48), label + " touch minimum");
        }
        check(require(root, "手動でWi-Fiに接続して取り込む").isEnabled(), "connect entry enabled while idle");
        check(((android.widget.CompoundButton) require(root, "カメラの原本も確認する")).isChecked() == screen.getSharedPreferences("camera_import", 0).getBoolean("wifi_verify_all", false), "Wi-Fi verify reflects saved preference");
        View status = root.findViewWithTag("ep7-status");
        check(status != null && status.getAccessibilityLiveRegion() == View.ACCESSIBILITY_LIVE_REGION_POLITE, "status live region");
        String before = ((TextView) status).getText().toString();
        screen.onActivityResult(407, Activity.RESULT_CANCELED, null);
        check(before.contentEquals(((TextView) status).getText()), "unsolicited panel result does not begin connection");
        check(find(root, "選んだ1枚を試験転送") == null && find(root, "試験用コピーを削除") == null, "old experiment controls removed");
        ScrollView scroll = findScroll(root); check(scroll != null, "scrolling container");
        Rect safe = new Rect(); root.getWindowVisibleDisplayFrame(safe); int[] xy = new int[2]; scroll.getLocationOnScreen(xy);
        check(xy[1] >= safe.top && xy[1] + scroll.getHeight() <= safe.bottom, "scroll stays inside safe bounds");
      });
      capture(t, wifi, "wifi-import");
      TagDetailChecks.ui(t,()->findScroll(screen.getWindow().getDecorView()).fullScroll(View.FOCUS_DOWN));
      capture(t,wifi,"wifi-import-bottom");
      if(screen.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED
          && !((android.widget.CompoundButton)require(screen.getWindow().getDecorView(),"カメラの原本も確認する")).isChecked()
          && !screen.getSharedPreferences("camera_import",0).getBoolean("wifi_identity_asked",false)) {
        TagDetailChecks.ui(t,()->{
          try{var method=Ep7WifiActivity.class.getDeclaredMethod("startWifi");method.setAccessible(true);method.invoke(screen);}
          catch(Exception error){throw new AssertionError(error);}
        });
        t.waitForIdleSync();
        View[] dialog={null};
        TagDetailChecks.ui(t,()->{
          for(View candidate:android.view.inspector.WindowInspector.getGlobalWindowViews())if(find(candidate,"カメラのWi-Fiを識別")!=null&&candidate.hasWindowFocus())dialog[0]=candidate;
          check(dialog[0]!=null,"identity rationale is a focused dialog");
          require(dialog[0],"許可して開始");require(dialog[0],"内容を確認して開始");
        });
        capture(t,wifi,"wifi-identity-rationale",dialog[0]);
        check(t.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK),"dismiss rationale without starting import");
        t.waitForIdleSync();
        check(!screen.getSharedPreferences("camera_import",0).getBoolean("wifi_identity_asked",false),"cancel rationale keeps preferences unchanged");
      }
      ble = (CameraBleActivity) t.startActivitySync(new Intent(t.getTargetContext(), CameraBleActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      t.waitForIdleSync();
      final CameraBleActivity bleScreen = ble;
      TagDetailChecks.ui(t, () -> {
        View root = bleScreen.getWindow().getDecorView();
        check((bleScreen.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0, "BLE credential screen protected");
        if (android.os.Build.HARDWARE.equals("ranchu") || android.os.Build.HARDWARE.equals("goldfish")) {
          try { check(new jp.nano.rawgallery.camera.CameraCredentialStore(bleScreen).names().isEmpty(), "Capture requires no registered user camera"); }
          catch(java.io.IOException error) { throw new AssertionError("Capture registration state unavailable"); }
          bleScreen.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
        require(root, "カメラのQRを撮影する"); require(root, "写真のQRを読み取る");
        require(root, "カメラを探す"); require(root, "選んだカメラの起動を試す");
        check(!require(root, "選んだカメラの起動を試す").isEnabled(), "wake disabled before selection");
      });
      capture(t, ble, "ble-wake");
      TagDetailChecks.ui(t,()->findScroll(bleScreen.getWindow().getDecorView()).fullScroll(View.FOCUS_DOWN));
      capture(t,ble,"ble-wake-bottom");
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
  static void capture(Instrumentation t,Activity activity,String name)throws Exception {capture(t,activity,name,activity.getWindow().getDecorView());}
  private static void capture(Instrumentation t, Activity activity, String name,View decor) throws Exception {
    long deadline=android.os.SystemClock.uptimeMillis()+5000; boolean[] focused={false};
    do { TagDetailChecks.ui(t,()->focused[0]=decor.hasWindowFocus()); if(focused[0])break; Thread.sleep(50); } while(android.os.SystemClock.uptimeMillis()<deadline);
    check(focused[0],"target window focus before capture");
    ConsistencyLayoutChecks.frame(t, decor);
    TagDetailChecks.ui(t,()->check((activity.getWindow().getInsetsController().getSystemBarsAppearance() & android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)==0,"light status ink on dark screen"));
    Bitmap image=null;
    for(int attempt=0;attempt<20;attempt++) {
      Bitmap next=t.getUiAutomation().takeScreenshot(); check(next!=null,"screenshot");
      if(image!=null&&image.sameAs(next)){image.recycle();image=next;break;}
      if(image!=null)image.recycle();image=next;
      if(attempt==19){image.recycle();throw new AssertionError("screen compositor did not settle");}
      Thread.sleep(150);
    }
    File dir = new File(activity.getCacheDir(), "consistency-checks"); dir.mkdirs();
    String config = activity.getResources().getConfiguration().screenWidthDp + "-" + activity.getResources().getConfiguration().fontScale;
    try (FileOutputStream out = new FileOutputStream(new File(dir, name + "-" + config + ".png"))) { check(image.compress(Bitmap.CompressFormat.PNG, 100, out), "capture"); } finally { image.recycle(); }
  }
}
