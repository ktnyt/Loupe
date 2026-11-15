package jp.nano.rawgallery;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.view.WindowManager;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import jp.nano.rawgallery.camera.CameraBleWake;
import jp.nano.rawgallery.camera.CameraQrCredentials;
import jp.nano.rawgallery.camera.CameraCredentialStore;
import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit experimental Bluetooth wake with encrypted, camera-specific credentials. */
public final class CameraBleActivity extends AppCompatActivity {
    private CameraBleWake helper;
    private Closeable operation;
    private BluetoothDevice selected;
    private LinearLayout devices;
    private EditText passcode;
    private TextView status;
    private MaterialButton wake;
    private volatile boolean foreground;
    private String selectedName;
    private LinearLayout registrations;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile int session;
    private String pendingMessage;
    private char[] pendingManual;
    private char[] pendingStored;
    private final Object credentialLock = new Object();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        helper = new CameraBleWake(this);
        LinearLayout frame = new LinearLayout(this); frame.setOrientation(LinearLayout.VERTICAL); frame.setBackgroundColor(GalleryUi.BG);
        frame.setOnApplyWindowInsetsListener((v, insets) -> { var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()); v.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets; });
        LinearLayout toolbar = LibraryUi.toolbar(this, "Bluetoothで起動を試す");
        toolbar.addView(LibraryUi.icon(this, R.drawable.ic_back, "戻る", this::finish), 0); frame.addView(toolbar);
        ScrollView scroll = new ScrollView(this); LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(24)); scroll.addView(body); frame.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        text(body, "カメラの接続画面のQRを一度登録すると、次回からパスコード入力を省けます。転送用Wi-Fiの起動は実験機能です。OM-1 Mark IIは未確認です。");
        button(body, "カメラのQRを撮影する", () -> startActivityForResult(new Intent(this, CameraQrScanActivity.class), 410));
        button(body, "写真のQRを読み取る", this::pickQrImage);
        text(body, "登録したカメラ");
        registrations = new LinearLayout(this); registrations.setOrientation(LinearLayout.VERTICAL); registrations.setTag("ble-registrations"); body.addView(registrations);
        button(body, "カメラを探す", this::requestScan);
        devices = new LinearLayout(this); devices.setOrientation(LinearLayout.VERTICAL); body.addView(devices);
        passcode = new EditText(this); passcode.setHint("未登録時のパスコード"); passcode.setContentDescription("カメラのパスコード");
        passcode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); passcode.setSaveEnabled(false); passcode.setMinHeight(dp(48)); body.addView(passcode);
        wake = button(body, "選んだカメラの起動を試す", this::wake); wake.setEnabled(false); MaterialUi.filled(wake);
        status = text(body, "カメラを探してください"); status.setTag("ble-status"); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        button(body, "Wi-Fi設定を開く", () -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        text(body, "操作後、カメラの状態を確認し、Wi-Fi設定で接続してください。");
        setContentView(frame);
        frame.post(() -> { var bars=getWindow().getInsetsController(); if(bars!=null)bars.setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS); });
    }
    @Override protected void onStart() { super.onStart(); foreground = true; loadRegistrations(); if (pendingMessage != null) { status.setText(pendingMessage); pendingMessage = null; } }
    @Override protected void onStop() { foreground = false; cancelPending(); selected = null; selectedName = null; wake.setEnabled(false); passcode.getText().clear(); devices.removeAllViews(); status.setText("再度カメラを探してください"); super.onStop(); }
    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
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
        cancelPending(); selected = null; selectedName = null; wake.setEnabled(false); devices.removeAllViews(); status.setText("カメラを探しています…");
        operation = helper.scan(new CameraBleWake.ScanObserver() {
            public void onCamera(BluetoothDevice device, String name) {
                if (!foreground) return;
                button(devices, name, () -> { cancelPending(); selected = device; selectedName = name; wake.setEnabled(true); status.setText(name + "を選択しました。登録済みなら保存したパスコードを使います。"); });
            }
            public void onFinished() { if (foreground) status.setText(selected == null ? "カメラを選んでください。見つからない場合は再度探してください。" : "カメラを選択しました。起動を試してください。"); }
            public void onError(CameraBleWake.Outcome error) { if (foreground && error != CameraBleWake.Outcome.CANCELLED) status.setText("カメラを探せませんでした。Bluetoothと権限を確認してください。"); }
        });
    }
    private void wake() {
        if (selected == null || selectedName == null) return;
        closeOperation();
        BluetoothDevice device = selected; String name = selectedName; int current = session;
        char[] manual = new char[passcode.length()]; passcode.getText().getChars(0, passcode.length(), manual, 0); passcode.getText().clear();
        pendingManual = manual;
        wake.setEnabled(false); status.setText("カメラの登録を確認しています…");
        io.execute(() -> {
            char[] stored = null; boolean failed = false;
            try { stored = new CameraCredentialStore(getApplicationContext()).passcode(name); }
            catch (IOException | RuntimeException error) { failed = true; }
            char[] saved = stored; boolean error = failed;
            synchronized (credentialLock) {
                if (!foreground || current != session) { if (saved != null) Arrays.fill(saved, '\0'); }
                else pendingStored = saved;
            }
            runOnUiThread(() -> {
                try {
                    if (!foreground || current != session || selected != device || !name.equals(selectedName)) return;
                    if (error) { status.setText("登録情報を読み取れません。QRを再登録してください。"); wake.setEnabled(true); return; }
                    char[] code = saved != null ? saved : manual;
                    if (code.length == 0) { status.setText("QRを登録するか、パスコードを入力してください。"); wake.setEnabled(true); return; }
                    status.setText("カメラに接続しています…");
                    operation = helper.wake(device, code, result -> {
                        if (!foreground || current != session) return;
                        wake.setEnabled(selected != null);
                        switch (result.outcome) {
                            case LOCATION_MODE_UNVERIFIED: status.setText("カメラが位置情報連携モードです。カメラの設定を確認してください。"); break;
                            case PROTOCOL_ACCEPTED: status.setText("カメラが操作を受け付けました。カメラの状態を確認し、Wi-Fi設定で接続してください。"); break;
                            case AUTH_REJECTED: status.setText("認証できませんでした。QRを再登録するか、パスコードを確認してください。"); break;
                            case UNSUPPORTED_CAMERA: status.setText("カメラを再度探して選んでください。"); break;
                            case INVALID_INPUT: status.setText("パスコードを確認してください。"); break;
                            case CANCELLED: status.setText("中止しました。"); break;
                            default: status.setText("操作できませんでした。カメラとBluetoothの状態を確認して再試行してください。");
                        }
                    });
                } finally { Arrays.fill(manual, '\0'); if (pendingManual == manual) pendingManual = null; synchronized (credentialLock) { if (saved != null) Arrays.fill(saved, '\0'); if (pendingStored == saved) pendingStored = null; } }
            });
        });
    }
    private void pickQrImage() {
        Intent intent = Build.VERSION.SDK_INT >= 33 ? new Intent("android.provider.action.PICK_IMAGES") : new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        intent.setType("image/*");
        try { startActivityForResult(intent, 411); } catch (RuntimeException error) { status.setText("写真選択を開けませんでした。"); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 410) {
            String message = result == CameraQrScanActivity.SAVED ? "QRを登録しました。カメラを再度探してください。"
                    : result == CameraQrScanActivity.INVALID ? "対応するカメラのQRではありません。"
                    : result == CameraQrScanActivity.DENIED ? "カメラの使用が許可されませんでした。"
                    : result == CameraQrScanActivity.CAMERA_FAILED ? "カメラを開けませんでした。写真のQRも読み取れます。"
                    : result == CameraQrScanActivity.SAVE_FAILED ? "登録できませんでした。保存済みの登録を確認してください。" : "QR撮影をキャンセルしました。";
            showResult(message); return;
        }
        if (request != 411) return;
        if (result != RESULT_OK || data == null || data.getData() == null) { showResult("写真の選択をキャンセルしました。"); return; }
        Uri uri = data.getData(); showResult("QRを読み取っています…");
        io.execute(() -> {
            String message;
            String raw = null;
            try { raw = CameraQrImageDecoder.decode(getContentResolver(), uri); }
            catch (IOException | RuntimeException ignored) { }
            if (raw == null) message = "写真からQRを読み取れませんでした。別の写真を選んでください。";
            else {
                try (CameraQrCredentials credentials = CameraQrCredentials.parse(raw)) {
                    try {
                        new CameraCredentialStore(getApplicationContext()).save(credentials);
                        message = "QRを登録しました。カメラを再度探してください。";
                    } catch (IOException | RuntimeException failure) { message = "登録できませんでした。保存済みの登録を確認してください。"; }
                } catch (IllegalArgumentException failure) { message = "対応するカメラのQRではありません。"; }
            }
            String outcome = message;
            runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) { showResult(outcome); if (foreground) loadRegistrations(); } });
        });
    }
    private void showResult(String message) { if (foreground) status.setText(message); else pendingMessage = message; }
    private void loadRegistrations() {
        io.execute(() -> {
            List<String> names = null;
            try { names = new CameraCredentialStore(getApplicationContext()).names(); } catch (IOException | RuntimeException ignored) { }
            List<String> entries = names;
            runOnUiThread(() -> {
                if (!foreground || isDestroyed()) return;
                registrations.removeAllViews();
                if (entries == null) { text(registrations, "登録情報を読み取れません。QRを再登録してください。"); return; }
                if (entries.isEmpty()) { text(registrations, "登録はありません"); return; }
                for (String name : entries) {
                    text(registrations, name);
                    button(registrations, name + "の登録を忘れる", () -> new MaterialAlertDialogBuilder(this)
                            .setMessage(name + "の登録をこの端末から削除します。写真やカメラの設定は変わりません。")
                            .setNegativeButton("キャンセル", null).setPositiveButton("削除", (d, w) -> removeRegistration(name)).show());
                }
            });
        });
    }
    private void removeRegistration(String name) {
        cancelPending(); wake.setEnabled(selected != null);
        io.execute(() -> {
            boolean success;
            try { new CameraCredentialStore(getApplicationContext()).remove(name); success = true; }
            catch (IOException | RuntimeException error) { success = false; }
            boolean removed = success;
            runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) { showResult(removed ? "登録を削除しました。" : "登録を削除できませんでした。"); if (foreground) loadRegistrations(); } });
        });
    }
    private void cancelPending() {
        session++;
        synchronized (credentialLock) {
            if (pendingStored != null) { Arrays.fill(pendingStored, '\0'); pendingStored = null; }
        }
        if (pendingManual != null) { Arrays.fill(pendingManual, '\0'); pendingManual = null; }
        closeOperation();
    }
    private void closeOperation() { if (operation != null) { try { operation.close(); } catch (IOException ignored) { } operation = null; } }
    private int dp(int n) { return GalleryUi.dp(this, n); }
    private TextView text(LinearLayout root, String value) { TextView v = new TextView(this); v.setText(value); v.setTextSize(16); v.setTextColor(GalleryUi.TEXT); v.setPadding(0, dp(8), 0, dp(8)); root.addView(v); return v; }
    private MaterialButton button(LinearLayout root, String label, Runnable action) { MaterialButton v = MaterialUi.button(this); v.setText(label); v.setAllCaps(false); v.setMinHeight(dp(48)); v.setMinimumHeight(dp(48)); v.setOnClickListener(x -> action.run()); root.addView(v, new LinearLayout.LayoutParams(-1, -2)); return v; }
}
