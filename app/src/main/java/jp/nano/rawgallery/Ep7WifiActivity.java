package jp.nano.rawgallery;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import jp.nano.rawgallery.camera.CameraDifferentialService;
import jp.nano.rawgallery.camera.ImportProgress;

/** Explicit Wi-Fi import and opt-in automatic connection handling. */
public final class Ep7WifiActivity extends AppCompatActivity {
    private final ImportProgress progress = ImportProgress.INSTANCE;
    private final Runnable listener = () -> runOnUiThread(this::render);
    private TextView status;
    private MaterialButton start, stop;
    private MaterialSwitch verify;
    private TextView photoPermissionStatus;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(GalleryUi.BG);
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets;
        });
        LinearLayout toolbar = LibraryUi.toolbar(this, "E-P7 Wi-Fi取り込み");
        toolbar.addView(LibraryUi.icon(this, R.drawable.ic_back, "戻る", this::finish), 0);
        frame.addView(toolbar);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(24));
        scroll.addView(body); frame.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        text(body, "カメラのWi-Fiに接続してから取り込みます。カメラの写真は削除しません。", 16);
        button(body, "Wi-Fi設定を開く", () -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        button(body, "Bluetoothでカメラの起動を試す", () -> startActivity(new Intent(this, CameraBleActivity.class)));
        verify = new MaterialSwitch(this);
        verify.setChecked(true); verify.setText("内容も確認する"); verify.setMinHeight(dp(48));
        body.addView(verify);
        text(body, "取り込み済みの写真も内容を読み直して確認します。時間がかかります。", 14);
        start = button(body, "写真を取り込む", () -> {
            if (!ensurePhotoPermission()) return;
            try { CameraDifferentialService.startWifi(this, verify.isChecked()); }
            catch (RuntimeException error) { status.setText("取り込みを開始できませんでした。接続と権限を確認してください。"); return; }
            render();
        });
        MaterialUi.filled(start);
        stop = button(body, "中止", () -> { progress.requestStop(); status.setText("中止しています…"); stop.setEnabled(false); });
        status = text(body, "待機中", 16); status.setTag("ep7-status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        MaterialSwitch auto = new MaterialSwitch(this);
        auto.setText("カメラのWi-Fi接続時に自動で取り込む"); auto.setMinHeight(dp(48));
        var prefs = getSharedPreferences("camera_import", MODE_PRIVATE);
        auto.setChecked(prefs.getBoolean("wifi_auto", false));
        auto.setOnCheckedChangeListener((v, checked) -> {
            if (checked && !ensurePhotoPermission()) { auto.setChecked(false); return; }
            prefs.edit().putBoolean("wifi_auto", checked).apply();
            CameraDifferentialService.updateAuto(this);
            if (checked && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 403);
        });
        body.addView(auto);
        photoPermissionStatus = text(body, "重複を確認するため、端末内の写真すべてへのアクセスが必要です。", 14);
        photoPermissionStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        button(body, "写真のアクセス権限を確認", this::ensurePhotoPermission);
        button(body, "アプリの権限設定を開く", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null))));
        setContentView(frame); render();
    }
    private String photoPermission() { return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE; }
    private boolean ensurePhotoPermission() {
        if (checkSelfPermission(photoPermission()) == PackageManager.PERMISSION_GRANTED) return true;
        photoPermissionStatus.setText("写真すべてへのアクセスを許可してください。選択した写真のみでは取り込めません。許可後にもう一度操作してください。");
        requestPermissions(Build.VERSION.SDK_INT >= 34
                ? new String[]{photoPermission(), Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}
                : new String[]{photoPermission()}, 406);
        return false;
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 406) photoPermissionStatus.setText(checkSelfPermission(photoPermission()) == PackageManager.PERMISSION_GRANTED
                ? "写真へのアクセスを許可しました。もう一度操作してください。"
                : "写真すべてへのアクセスが必要です。再度許可するか、アプリの権限設定を確認してください。");
    }
    @Override protected void onStart() { super.onStart(); progress.addListener(listener); render(); }
    @Override protected void onStop() { progress.removeListener(listener); super.onStop(); }
    private void render() {
        if (status == null) return;
        boolean active = progress.getActive();
        start.setEnabled(!active); verify.setEnabled(!active); stop.setEnabled(active && !progress.getStopRequested());
        String message = active ? progress.getStatus() : progress.getLastMessage();
        if (active && (message == null || message.isEmpty())) message = "取り込み中 " + progress.getDone() + " / " + progress.getTotal();
        status.setText(message == null || message.isEmpty() ? "待機中" : message);
    }
    private int dp(int value) { return GalleryUi.dp(this, value); }
    private TextView text(LinearLayout root, String value, int size) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(GalleryUi.TEXT);
        v.setPadding(0, dp(8), 0, dp(8)); root.addView(v, new LinearLayout.LayoutParams(-1, -2)); return v;
    }
    private MaterialButton button(LinearLayout root, String label, Runnable action) {
        MaterialButton v = new MaterialButton(this); v.setText(label); v.setAllCaps(false); v.setMinHeight(dp(48));
        v.setMinimumHeight(dp(48)); v.setOnClickListener(x -> action.run()); root.addView(v, new LinearLayout.LayoutParams(-1, -2)); return v;
    }
}
