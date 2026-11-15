package jp.nano.rawgallery;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import jp.nano.rawgallery.camera.CameraBleWake;
import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;

/** Explicit, experimental Bluetooth wake; passcode is never saved. */
public final class CameraBleActivity extends AppCompatActivity {
    private CameraBleWake helper;
    private Closeable operation;
    private BluetoothDevice selected;
    private LinearLayout devices;
    private EditText passcode;
    private TextView status;
    private MaterialButton wake;
    private boolean foreground;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        helper = new CameraBleWake(this);
        LinearLayout frame = new LinearLayout(this); frame.setOrientation(LinearLayout.VERTICAL); frame.setBackgroundColor(GalleryUi.BG);
        frame.setOnApplyWindowInsetsListener((v, insets) -> { var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()); v.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets; });
        LinearLayout toolbar = LibraryUi.toolbar(this, "Bluetoothで起動を試す");
        toolbar.addView(LibraryUi.icon(this, R.drawable.ic_back, "戻る", this::finish), 0); frame.addView(toolbar);
        ScrollView scroll = new ScrollView(this); LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(24)); scroll.addView(body); frame.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        text(body, "E-P7 / OM-1 Mark II向けの実験機能です。実機での動作は未確認です。カメラを選び、パスコードを入力してください。");
        button(body, "カメラを探す", this::requestScan);
        devices = new LinearLayout(this); devices.setOrientation(LinearLayout.VERTICAL); body.addView(devices);
        passcode = new EditText(this); passcode.setHint("カメラのパスコード"); passcode.setContentDescription("カメラのパスコード");
        passcode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); passcode.setSaveEnabled(false); passcode.setMinHeight(dp(48)); body.addView(passcode);
        wake = button(body, "選んだカメラの起動を試す", this::wake); wake.setEnabled(false); MaterialUi.filled(wake);
        status = text(body, "カメラを探してください"); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        button(body, "Wi-Fi設定を開く", () -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        text(body, "操作後、カメラの状態を確認し、Wi-Fi設定で接続してください。");
        setContentView(frame);
        frame.post(() -> { var bars=getWindow().getInsetsController(); if(bars!=null)bars.setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS); });
    }
    @Override protected void onStart() { super.onStart(); foreground = true; }
    @Override protected void onStop() { foreground = false; closeOperation(); selected = null; wake.setEnabled(false); passcode.getText().clear(); devices.removeAllViews(); status.setText("再度カメラを探してください"); super.onStop(); }
    private void requestScan() {
        String[] permissions = Build.VERSION.SDK_INT >= 31 ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT} : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for (String permission : permissions) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) { requestPermissions(permissions, 404); return; }
        scan();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != 404) return;
        for (int result : results) if (result != PackageManager.PERMISSION_GRANTED) { status.setText("Bluetoothの権限が必要です。"); return; }
        if (results.length > 0 && foreground) scan();
    }
    private void scan() {
        closeOperation(); selected = null; wake.setEnabled(false); devices.removeAllViews(); status.setText("カメラを探しています…");
        operation = helper.scan(new CameraBleWake.ScanObserver() {
            public void onCamera(BluetoothDevice device, String name) {
                if (!foreground) return;
                button(devices, name, () -> { selected = device; wake.setEnabled(true); status.setText(name + "を選択しました"); });
            }
            public void onFinished() { if (foreground) status.setText(selected == null ? "カメラを選んでください。見つからない場合は再度探してください。" : "カメラを選択しました。パスコードを入力してください。"); }
            public void onError(CameraBleWake.Outcome error) { if (foreground && error != CameraBleWake.Outcome.CANCELLED) status.setText("カメラを探せませんでした。Bluetoothと権限を確認してください。"); }
        });
    }
    private void wake() {
        if (selected == null) return;
        closeOperation(); char[] code = new char[passcode.length()]; passcode.getText().getChars(0, passcode.length(), code, 0); passcode.getText().clear();
        status.setText("カメラに接続しています…"); wake.setEnabled(false);
        try { operation = helper.wake(selected, code, result -> {
            if (!foreground) return;
            wake.setEnabled(selected != null);
            switch (result.outcome) {
                case LOCATION_MODE_UNVERIFIED: status.setText("カメラが位置情報連携モードです。カメラの設定を確認してください。"); break;
                case PROTOCOL_ACCEPTED: status.setText("カメラが操作を受け付けました。カメラの状態を確認し、Wi-Fi設定で接続してください。"); break;
                case AUTH_REJECTED: status.setText("認証できませんでした。パスコードを確認してください。"); break;
                case INVALID_INPUT: status.setText("パスコードを確認してください。"); break;
                case CANCELLED: status.setText("中止しました。"); break;
                default: status.setText("操作できませんでした。カメラとBluetoothの状態を確認して再試行してください。");
            }
        }); } finally { Arrays.fill(code, '\0'); }
    }
    private void closeOperation() { if (operation != null) { try { operation.close(); } catch (IOException ignored) { } operation = null; } }
    private int dp(int n) { return GalleryUi.dp(this, n); }
    private TextView text(LinearLayout root, String value) { TextView v = new TextView(this); v.setText(value); v.setTextSize(16); v.setTextColor(GalleryUi.TEXT); v.setPadding(0, dp(8), 0, dp(8)); root.addView(v); return v; }
    private MaterialButton button(LinearLayout root, String label, Runnable action) { MaterialButton v = MaterialUi.button(this); v.setText(label); v.setAllCaps(false); v.setMinHeight(dp(48)); v.setMinimumHeight(dp(48)); v.setOnClickListener(x -> action.run()); root.addView(v, new LinearLayout.LayoutParams(-1, -2)); return v; }
}
