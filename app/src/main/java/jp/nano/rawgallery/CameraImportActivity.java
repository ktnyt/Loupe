package jp.nano.rawgallery;

import android.Manifest;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import jp.nano.rawgallery.camera.Camera;
import jp.nano.rawgallery.camera.CameraDifferentialService;
import jp.nano.rawgallery.camera.CameraImportGate;
import jp.nano.rawgallery.camera.CameraImportLedger;
import jp.nano.rawgallery.camera.CameraUsb;
import jp.nano.rawgallery.camera.UsbLaunchSettings;
import jp.nano.rawgallery.camera.HistoryDb;
import jp.nano.rawgallery.camera.ImportProgress;
import jp.nano.rawgallery.camera.ImportService;
import jp.nano.rawgallery.camera.MediaTypes;

import java.io.Closeable;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Executors;

public final class CameraImportActivity extends AppCompatActivity {
    static final String START_IMPORT_KEEP_ORIGINALS = "start_import_keep_originals";

    /** Return to import progress in the current task, or open it if it isn't there yet. */
    public static Intent showProgressIntent(Context context) {
        return new Intent(context, CameraImportActivity.class)
                .setAction("jp.nano.rawgallery.SHOW_IMPORT_PROGRESS")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private static final int PICK_DB = 401;
    private static final int PICK_SD_TREE = 405;
    private static final java.util.concurrent.Executor IO = Executors.newSingleThreadExecutor();
    private static final ImportProgress PROGRESS = ImportProgress.INSTANCE;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView connection, result, migrationResult, sdStatus, ledgerStatus;
    private MaterialSwitch sdVerify, sdAuto;
    private ProgressBar progress;
    private TextView progressDetail;
    private Button run, stop, migrate, notifyButton;
    private LinearLayout cameras, importSettings;
    private MaterialButton settingsButton;
    private boolean settingsExpanded;
    private MaterialSwitch auto;
    private MaterialSwitch usbAutoOpen;
    private boolean updatingUsbAutoOpen;
    private Closeable permission;
    private boolean migrating, starting, wasActive;
    private String connectionFailure;
    private boolean quickImportFlow;
    private String quickRequestId;
    private boolean quickConsumed, quickResumed, pendingSdPermission, resumeSd, pendingUsbPermission, resumeUsb;
    private android.os.CancellationSignal sourceCancellation;
    private int sourceGeneration;
    private String pendingSdTree;
    private int pendingUsbDeviceId = -1, pendingUsbVendorId = -1, pendingUsbProductId = -1;
    private String pendingUsbDeviceName;
    private static final java.util.concurrent.Executor SOURCE_IO = new java.util.concurrent.ThreadPoolExecutor(
            0, 2, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>());

    // Instance-scoped loader lets instrumentation supply metadata without touching registrations.
    interface WifiProfileLoader {
        List<CameraQuickImportSources.Profile> load(android.content.Context context,
                android.os.CancellationSignal cancellation) throws IOException;
    }
    WifiProfileLoader wifiProfileLoader = CameraQuickImportSources::wifiProfiles;
    private LinearLayout wifiActions;
    private TextView wifiStatus;
    private MaterialButton wifiSettings;
    private android.os.CancellationSignal wifiCancellation;
    private int wifiGeneration;
    private boolean wifiLaunching;
    private Runnable wifiTimeout;

    private SharedPreferences settings;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            renderStatus();
            handler.postDelayed(this, 350);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = getSharedPreferences("camera_import", MODE_PRIVATE);
        if (state != null) {
            pendingUsbDeviceId = state.getInt("quick_usb_device_id", -1);
            pendingUsbDeviceName = state.getString("quick_usb_device_name");
            pendingUsbVendorId = state.getInt("quick_usb_vendor_id", -1);
            pendingUsbProductId = state.getInt("quick_usb_product_id", -1);
            pendingUsbPermission = state.getBoolean("quick_usb_permission");
            resumeUsb = state.getBoolean("quick_usb_resume");
            quickConsumed = state.getBoolean("quick_consumed");
            if (state.getBoolean("quick_source_pending")) connectionFailure = "取り込み元の確認を中止しました。もう一度長押ししてください。";
            pendingSdTree = state.getString("quick_sd_tree");
            pendingSdPermission = state.getBoolean("quick_sd_permission");
            resumeSd = state.getBoolean("quick_sd_resume");
            quickImportFlow = state.getBoolean("quick_import_flow");
            quickRequestId = state.getString("quick_import_request");
            settingsExpanded = state.getBoolean("import_settings_expanded");
        }
        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(GalleryUi.BG);
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            var bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        settingsButton = LibraryUi.icon(this, R.drawable.ic_settings, "取り込み設定", () -> {
            settingsExpanded = !settingsExpanded;
            renderSettingsVisibility();
        });
        settingsButton.setCheckable(true);
        settingsButton.setToggleCheckedStateOnClick(false);
        LinearLayout toolbar = LibraryUi.toolbar(this, "取り込み", settingsButton);
        toolbar.addView(LibraryUi.icon(this, R.drawable.ic_back, "戻る", this::finish),
                0, new LinearLayout.LayoutParams(-2, -2));
        frame.addView(toolbar);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(GalleryUi.BG);
        root.setPadding(dp(16), 0, dp(16), dp(16));
        scroll.addView(root);
        frame.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(frame);
        frame.post(() -> { var bars=getWindow().getInsetsController(); if(bars!=null)bars.setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS); });
        LinearLayout attached = card(root, "カメラ");
        TextView cameraHeading = (TextView) attached.getChildAt(0);
        cameraHeading.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_camera_usb, 0, 0, 0);
        cameraHeading.setCompoundDrawablePadding(dp(10));
        connection = text("", 14, GalleryUi.TEXT);
        attached.addView(connection);
        LinearLayout importing = card(root, "取り込み状況");
        result = status(importing);
        result.setTag("camera-import-stage");
        result.setTextColor(GalleryUi.TEXT);
        result.setTextSize(16);
        progressDetail = text("", 14, GalleryUi.MUTED);
        progressDetail.setTag("camera-import-counts");
        importing.addView(progressDetail);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setContentDescription("取り込みの進捗");
        importing.addView(progress);
        run = button(importing, "今すぐ取り込む", this::startFromScreen);
        MaterialUi.filled(run);
        run.setMinHeight(dp(56));
        run.setMinimumHeight(dp(56));
        stop = button(importing, "中止", () -> {
            PROGRESS.requestStop();
            renderStatus();
        });
        importSettings = new LinearLayout(this);
        importSettings.setOrientation(LinearLayout.VERTICAL);
        importSettings.setTag("import-settings");
        root.addView(importSettings, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout preferences = card(importSettings, "取り込み設定");
        button(preferences, "写真の保存先", () -> startActivity(new Intent(this, PhotoStorageActivity.class)));
        usbAutoOpen = new MaterialSwitch(this);
        usbAutoOpen.setText("USB接続時にLoupeを開く");
        usbAutoOpen.setTag("usb-auto-open");
        usbAutoOpen.setMinHeight(dp(48));
        usbAutoOpen.setChecked(UsbLaunchSettings.isEnabled(this));
        usbAutoOpen.setOnCheckedChangeListener((v, checked) -> {
            if (updatingUsbAutoOpen) return;
            try {
                UsbLaunchSettings.setEnabled(this, checked);
            } catch (RuntimeException error) {
                updatingUsbAutoOpen = true;
                try { usbAutoOpen.setChecked(UsbLaunchSettings.isEnabled(this)); }
                finally { updatingUsbAutoOpen = false; }
                Toast.makeText(this, "USB接続時の起動設定を変更できませんでした", Toast.LENGTH_LONG).show();
            }
            renderStatus();
        });
        preferences.addView(usbAutoOpen);
        preferences.addView(text("オンの場合、接続時にAndroidの起動確認が表示されることがあります。", 14, GalleryUi.MUTED));
        auto = new MaterialSwitch(this);
        auto.setText("開いたら自動で取り込む");
        auto.setMinHeight(dp(48));
        auto.setChecked(settings.getBoolean("auto", true));
        auto.setOnCheckedChangeListener((v, checked) -> settings.edit().putBoolean("auto", checked).apply());
        preferences.addView(auto);
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifyButton = button(preferences, "取り込みの通知を許可する",
                    () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 402));
        }
        LinearLayout wifiTrial = card(root, "Wi-Fi取り込み");
        wifiTrial.setTag("wifi-import-card");
        TextView wifiHeading = (TextView) wifiTrial.getChildAt(0);
        wifiTrial.removeView(wifiHeading);
        LinearLayout wifiHeader = new LinearLayout(this);
        wifiHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
        wifiHeader.addView(wifiHeading, new LinearLayout.LayoutParams(0, -2, 1));
        wifiSettings = LibraryUi.icon(this, R.drawable.ic_viewer_edit, "Wi-Fi取り込み設定", this::openWifiSettings);
        wifiSettings.setTag("wifi-import-settings");
        wifiHeader.addView(wifiSettings);
        wifiTrial.addView(wifiHeader);
        wifiStatus = status(wifiTrial);
        wifiStatus.setTag("wifi-import-status");
        wifiActions = new LinearLayout(this);
        wifiActions.setOrientation(LinearLayout.VERTICAL);
        wifiTrial.addView(wifiActions, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout sd = card(root, "SDカードから取り込む");
        sd.addView(text("写真のあるフォルダを選びます。SDカードの写真は削除しません。", 14, GalleryUi.MUTED));
        sdStatus = status(sd);
        button(sd, "SDカードのフォルダを選ぶ", this::pickSdTree);
        sdVerify = new MaterialSwitch(this); sdVerify.setChecked(settings.getBoolean("sd_verify_all", false)); sdVerify.setOnCheckedChangeListener((v, checked) -> settings.edit().putBoolean("sd_verify_all", checked).apply()); sdVerify.setText("内容も確認する"); sdVerify.setMinHeight(dp(48)); sd.addView(sdVerify);
        sd.addView(text("通常は履歴を優先します。同じ名前・サイズ・日時のまま内容だけ変わった写真は見逃す場合があります。内容も確認すると読み直すため時間がかかります。", 14, GalleryUi.MUTED));
        button(sd, "SDカードの写真を取り込む", this::startSd);
        sdAuto = new MaterialSwitch(this); sdAuto.setText("SDカード接続時に自動で取り込む"); sdAuto.setMinHeight(dp(48));
        sdAuto.setChecked(settings.getBoolean("sd_auto", false));
        sdAuto.setOnCheckedChangeListener((v, checked) -> {
            if (checked && !ensureSdPermissions()) { sdAuto.setChecked(false); return; }
            if (checked && readableSdTree() == null) { sdAuto.setChecked(false); sdStatus.setText("先に読み取り可能なフォルダを選んでください。"); return; }
            settings.edit().putBoolean("sd_auto", checked).apply(); CameraDifferentialService.updateAuto(this);
            if (checked && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 403);
        });
        sd.addView(sdAuto);
        sd.addView(text("原本をそのまま保存して重複を確認するため、写真すべてと写真の位置情報へのアクセスが必要です。", 14, GalleryUi.MUTED));
        button(sd, "写真のアクセス権限を確認", this::ensureSdPermissions);
        button(sd, "アプリの権限設定を開く", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null))));
        sdStatus.setText(readableSdTree() == null ? "フォルダを選んでください" : "フォルダを選択済み");
        LinearLayout ledger = card(root, "共通履歴");
        ledgerStatus = status(ledger);
        ledgerStatus.setText("件数を確認しています");
        cameras = card(importSettings, "カメラごとの設定");
        LinearLayout history = card(importSettings, "CameraSyncの記録");
        history.addView(text("取り込み済みの記録を引き継ぐと、同じファイルを再び取り込まずに済みます。CameraSyncと保存済みの写真は残ります。",
                14, GalleryUi.MUTED));
        migrationResult = status(history);
        migrate = button(history, "記録のファイルを選ぶ", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*");
            startActivityForResult(intent, PICK_DB);
        });
        MaterialUi.outlined(migrate);
        var migrationButtonLayout = (LinearLayout.LayoutParams) migrate.getLayoutParams();
        migrationButtonLayout.topMargin = dp(8);
        migrate.setLayoutParams(migrationButtonLayout);
        renderSettingsVisibility();
        renderStatus();
    }

    @Override protected void onStart() {
        super.onStart();
        handler.post(refresh);
        loadCameras();
        loadLedgerCounts();
        loadWifiProfiles();
    }

    @Override protected void onStop() {
        handler.removeCallbacks(refresh);
        cancelWifiLoad();
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (permission != null) {
            try { permission.close(); } catch (IOException ignored) {}
        }
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("quick_usb_device_id", pendingUsbDeviceId);
        state.putString("quick_usb_device_name", pendingUsbDeviceName);
        state.putInt("quick_usb_vendor_id", pendingUsbVendorId);
        state.putInt("quick_usb_product_id", pendingUsbProductId);
        state.putBoolean("quick_usb_permission", pendingUsbPermission);
        state.putBoolean("quick_usb_resume", resumeUsb);
        state.putBoolean("quick_source_pending", sourceCancellation != null);
        state.putBoolean("quick_consumed", quickConsumed);
        state.putString("quick_sd_tree", pendingSdTree);
        state.putBoolean("quick_sd_permission", pendingSdPermission);
        state.putBoolean("quick_sd_resume", resumeSd);
        state.putBoolean("quick_import_flow", quickImportFlow);
        state.putString("quick_import_request", quickRequestId);
        state.putBoolean("import_settings_expanded", settingsExpanded);
    }

    @Override protected void onPostResume() {
        super.onPostResume();
        quickResumed = true;
        wifiLaunching = false;
        renderStatus();
        if (resumeUsb || (pendingUsbPermission && permission == null)) continuePendingUsb();
        else if (resumeSd) { resumeSd = false; discoverQuickSource(true); }
        else if (!quickConsumed && getIntent().getBooleanExtra(START_IMPORT_KEEP_ORIGINALS, false)) {
            quickConsumed = true;
            getIntent().removeExtra(START_IMPORT_KEEP_ORIGINALS);
            quickImportFlow = true;
            discoverQuickSource(false);
        }
    }

    @Override protected void onPause() {
        quickResumed = false;
        if (sourceCancellation != null) {
            sourceGeneration++;
            sourceCancellation.cancel();
            sourceCancellation = null;
            starting = false;
            connectionFailure = "取り込み元の確認を中止しました。もう一度長押ししてください。";
        }
        super.onPause();
    }

    private boolean quickAvailable() {
        return quickResumed && !isFinishing() && !isDestroyed() && !PROGRESS.getActive()
                && !CameraImportGate.lock.isLocked() && !migrating;
    }

    private void discoverQuickSource(boolean sdOnly) {
        if (!quickAvailable() || starting || sourceCancellation != null) return;
        if (!sdOnly && connected() != null) { startFromScreen(true); return; }
        String expectedSd = pendingSdTree;
        int generation = ++sourceGeneration;
        var cancellation = new android.os.CancellationSignal();
        sourceCancellation = cancellation;
        starting = true;
        connectionFailure = "取り込み元を確認しています…";
        renderStatus();
        Runnable timeout = () -> {
            if (sourceCancellation != cancellation) return;
            sourceGeneration++;
            cancellation.cancel(); sourceCancellation = null; starting = false;
            connectionFailure = "取り込み元を確認できませんでした。接続を確認してもう一度長押ししてください。";
            renderStatus();
        };
        handler.postDelayed(timeout, 3000);
        try {
            SOURCE_IO.execute(() -> {
                CameraQuickImportSources.Sources sources = null;
                try {
                    if (sdOnly) {
                        Uri reachable = CameraQuickImportSources.reachableSdTree(getApplicationContext(), cancellation);
                        sources = new CameraQuickImportSources.Sources(reachable != null
                                && reachable.toString().equals(expectedSd) ? reachable : null, List.of());
                    } else sources = CameraQuickImportSources.resolve(getApplicationContext(), cancellation);
                } catch (IOException | RuntimeException ignored) { }
                var outcome = sources;
                handler.post(() -> {
                    if (generation != sourceGeneration || sourceCancellation != cancellation) return;
                    handler.removeCallbacks(timeout); sourceCancellation = null; starting = false;
                    if (!quickAvailable()) return;
                    if (!sdOnly && connected() != null) { startFromScreen(true); return; }
                    if (outcome == null) { connectionFailure = "登録情報を確認できませんでした。QRの登録を確認してください。"; renderStatus(); return; }
                    connectionFailure = "取り込み元を選んでください。";
                    renderStatus();
                    if (outcome.sd != null) {
                        pendingSdTree = outcome.sd.toString();
                        pendingSdPermission = true;
                        if (ensureSdPermissions()) {
                            pendingSdPermission = false;
                            startSd();
                        }
                    } else if (sdOnly) { connectionFailure = "SDカードに接続できません。接続とフォルダを確認してください。"; renderStatus(); }
                    else if (outcome.profiles.size() == 1) openQuickProfile(outcome.profiles.get(0).id);
                    else if (outcome.profiles.size() > 1) {
                        String[] names = outcome.profiles.stream().map(p -> p.name).toArray(String[]::new);
                        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                                .setTitle("取り込むカメラを選択")
                                .setItems(names, (dialog, index) -> openQuickProfile(outcome.profiles.get(index).id))
                                .setNegativeButton("キャンセル", (d, w) -> quickCancelled()).setOnCancelListener(d -> quickCancelled()).show();
                    } else {
                        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                                .setTitle("取り込み元を登録")
                                .setMessage("カメラのQRを登録するか、SDカードのフォルダを選んでください。Wi-Fi情報のない登録はQRの再登録が必要です。")
                                .setPositiveButton("カメラを登録", (d, w) -> { if (quickAvailable()) startActivity(new Intent(this, CameraBleActivity.class)); })
                                .setNeutralButton("SDを選ぶ", (d, w) -> { if (quickAvailable()) pickSdTree(); })
                                .setNegativeButton("キャンセル", (d, w) -> quickCancelled()).setOnCancelListener(d -> quickCancelled()).show();
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) { timeout.run(); }
    }

    private boolean wifiAvailable() {
        return quickAvailable() && !starting && permission == null && !pendingSdPermission
                && !pendingUsbPermission && !resumeSd && !resumeUsb && !wifiLaunching;
    }

    private void openWifiSettings() {
        if (!wifiAvailable()) return;
        wifiLaunching = true;
        renderWifiEnabled();
        try { startActivity(new Intent(this, Ep7WifiActivity.class)); }
        catch (RuntimeException unavailable) {
            wifiLaunching = false;
            wifiStatus.setText("設定を開けませんでした。もう一度お試しください。");
            renderWifiEnabled();
        }
    }

    private void startWifiProfile(String id) {
        if (!wifiAvailable() || wifiCancellation != null) return;
        wifiLaunching = true; // Consume before Activity launch, including two clicks in one UI turn.
        renderWifiEnabled();
        try {
            startActivity(new Intent(this, CameraBleActivity.class)
                    .putExtra(CameraBleActivity.EXTRA_QUICK_PROFILE_ID, id));
        } catch (RuntimeException unavailable) {
            wifiLaunching = false;
            wifiStatus.setText("接続を開始できませんでした。設定を確認してもう一度お試しください。");
            renderWifiEnabled();
        }
    }

    private void renderWifiEnabled() {
        boolean enabled = wifiAvailable();
        if (wifiSettings != null) wifiSettings.setEnabled(enabled);
        if (wifiActions != null) for (int i = 0; i < wifiActions.getChildCount(); i++)
            wifiActions.getChildAt(i).setEnabled(enabled && wifiCancellation == null);
    }

    private void cancelWifiLoad() {
        wifiGeneration++;
        if (wifiTimeout != null) handler.removeCallbacks(wifiTimeout);
        wifiTimeout = null;
        if (wifiCancellation != null) wifiCancellation.cancel();
        wifiCancellation = null;
    }

    private void loadWifiProfiles() {
        cancelWifiLoad();
        int generation = wifiGeneration;
        var cancellation = new android.os.CancellationSignal();
        wifiCancellation = cancellation;
        wifiActions.removeAllViews();
        wifiStatus.setText("登録カメラを確認しています…");
        renderWifiEnabled();
        wifiTimeout = () -> {
            if (wifiCancellation != cancellation || generation != wifiGeneration) return;
            cancelWifiLoad();
            showWifiProfiles(null);
        };
        handler.postDelayed(wifiTimeout, 3000);
        try {
            SOURCE_IO.execute(() -> {
                List<CameraQuickImportSources.Profile> profiles = null;
                try { profiles = wifiProfileLoader.load(getApplicationContext(), cancellation); }
                catch (IOException | RuntimeException unavailable) { }
                var outcome = profiles;
                handler.post(() -> {
                    if (wifiCancellation != cancellation || generation != wifiGeneration
                            || isFinishing() || isDestroyed()) return;
                    handler.removeCallbacks(wifiTimeout);
                    wifiTimeout = null;
                    wifiCancellation = null;
                    showWifiProfiles(outcome);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) { wifiTimeout.run(); }
    }

    private void showWifiProfiles(List<CameraQuickImportSources.Profile> profiles) {
        wifiActions.removeAllViews();
        if (profiles == null) {
            wifiStatus.setText("登録カメラを確認できませんでした。");
            button(wifiActions, "もう一度確認する", this::loadWifiProfiles).setTag("wifi-import-retry");
        } else if (profiles.isEmpty()) {
            wifiStatus.setText("QRでカメラを登録すると、ここから取り込めます。");
            button(wifiActions, "カメラを登録・接続する", this::openWifiSettings).setTag("wifi-import-setup");
        } else {
            wifiStatus.setText("カメラをタップすると接続して取り込みます。");
            for (var profile : profiles) {
                Button action = button(wifiActions, profile.name + "から取り込む", () -> startWifiProfile(profile.id));
                action.setTag("wifi-import-profile:" + profile.id);
                MaterialUi.filled(action);
                action.setMinHeight(dp(56));
                if (wifiActions.getChildCount() > 1) {
                    LinearLayout.LayoutParams spacing = (LinearLayout.LayoutParams) action.getLayoutParams();
                    spacing.topMargin = dp(8);
                    action.setLayoutParams(spacing);
                }
            }
        }
        renderWifiEnabled();
    }

    private void quickCancelled() { connectionFailure = "取り込みをキャンセルしました。"; renderStatus(); }
    private void openQuickProfile(String id) {
        if (!quickAvailable()) return;
        startActivity(new Intent(this, CameraBleActivity.class).putExtra(CameraBleActivity.EXTRA_QUICK_PROFILE_ID, id));
    }

    private UsbDevice connected() { return connected(this); }

    static UsbDevice connected(android.content.Context context) {
        UsbManager manager = context.getSystemService(UsbManager.class);
        if (manager == null) return null;
        return manager.getDeviceList().values().stream()
                .filter(CameraUsb::supported)
                .sorted(java.util.Comparator.<UsbDevice, Boolean>comparing(d -> !CameraUsb.knownCamera(d))
                        .thenComparing(UsbDevice::getDeviceName))
                .findFirst().orElse(null);
    }

    private void rememberPendingUsb(UsbDevice device) {
        pendingUsbDeviceId = device.getDeviceId();
        pendingUsbDeviceName = device.getDeviceName();
        pendingUsbVendorId = device.getVendorId();
        pendingUsbProductId = device.getProductId();
        pendingUsbPermission = true;
    }

    private boolean matchesPendingUsb(UsbDevice device) {
        return device != null && pendingUsbDeviceName != null
                && device.getDeviceId() == pendingUsbDeviceId
                && pendingUsbDeviceName.equals(device.getDeviceName())
                && device.getVendorId() == pendingUsbVendorId && device.getProductId() == pendingUsbProductId;
    }

    private void continuePendingUsb() {
        if (!quickAvailable() || permission != null || (!pendingUsbPermission && !resumeUsb)) return;
        UsbDevice device = connected();
        if (!matchesPendingUsb(device)) {
            pendingUsbPermission = false; resumeUsb = false;
            connectionFailure = "許可を求めたカメラの接続が変わりました。もう一度操作してください。";
            return;
        }
        if (!getSystemService(UsbManager.class).hasPermission(device)) {
            connectionFailure = "USBの接続と使用許可を確認してください。許可を取り消した場合は、取り込みボタンで再試行できます。";
            return;
        }
        pendingUsbPermission = false; resumeUsb = false;
        prepareImport(device, true);
    }

    private void renderStatus() {
        // A USB permission broadcast can target the pre-recreation activity.
        // Recover only the original request, consuming it before any UI/import side effect.
        if ((pendingUsbPermission || resumeUsb) && permission == null && quickAvailable()) continuePendingUsb();
        var completion = PROGRESS.getCompletion();
        if (quickRequestId != null && completion != null && quickRequestId.equals(completion.getRequestId())) {
            quickRequestId = null;
            starting = false;
            if (completion.getSucceeded()) {
                finish();
                return;
            }
        }
        UsbDevice device = connected();
        boolean manualOnly = device != null && !CameraUsb.knownCamera(device);
        boolean granted = device != null && getSystemService(UsbManager.class).hasPermission(device);
        boolean active = PROGRESS.getActive();
        if (wasActive && !active) { loadCameras(); loadLedgerCounts(); }
        wasActive = active;
        if (active) starting = false;
        setText(connection, device == null ? "USBケーブルでカメラをつないでください"
                : (device.getProductName() == null ? "カメラ" : device.getProductName())
                  + (granted ? (manualOnly ? " · 手動取り込み" : " · 接続済み") : " · USBの使用許可が必要です"));
        ImportProgressPresentation presentation = ImportProgressPresentation.current();
        setText(result, !active && connectionFailure != null ? connectionFailure : presentation.primary);
        setText(progressDetail, presentation.secondary);
        progressDetail.setVisibility(presentation.secondary.isEmpty() ? View.GONE : View.VISIBLE);
        progress.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) {
            progress.setContentDescription(presentation.accessibility());
            progress.setIndeterminate(presentation.total <= 0);
            if (presentation.total > 0) {
                progress.setMax(presentation.total);
                progress.setProgress(presentation.done);
            }
        }
        setText(run, device != null && !granted ? "USBの使用を許可する"
                : (settings.getBoolean("configured", false) ? "今すぐ取り込む" : "取り込みの設定をする"));
        run.setEnabled(device != null && !active && !starting && permission == null && !migrating);
        run.setVisibility(active ? View.GONE : View.VISIBLE);
        if (notifyButton != null) notifyButton.setVisibility(
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ? View.VISIBLE : View.GONE);
        stop.setVisibility(active ? View.VISIBLE : View.GONE);
        stop.setEnabled(active && !PROGRESS.getStopRequested());
        setText(stop, PROGRESS.getStopRequested() ? "中止しています" : "中止");
        renderWifiEnabled();
        auto.setEnabled(UsbLaunchSettings.isEnabled(this) && !active && !migrating && !manualOnly);
        migrate.setEnabled(!active && !starting && !migrating);
    }

    private void renderSettingsVisibility() {
        importSettings.setVisibility(settingsExpanded ? View.VISIBLE : View.GONE);
        settingsButton.setChecked(settingsExpanded);
        androidx.core.view.ViewCompat.setStateDescription(settingsButton,
                settingsExpanded ? "開いています" : "閉じています");
    }

    private void startFromScreen() { startFromScreen(quickImportFlow); }

    private void startFromScreen(boolean keepOriginals) {
        if (PROGRESS.getActive() || starting || migrating || permission != null) return;
        UsbDevice device = connected();
        if (device == null) return;
        connectionFailure = null;
        if (getSystemService(UsbManager.class).hasPermission(device)) prepareImport(device, keepOriginals);
        else {
            try {
                if (quickImportFlow) rememberPendingUsb(device);
                permission = CameraUsb.request(this, device, granted -> {
                    // Consume before renderStatus: rendering also recovers recreated USB requests.
                    pendingUsbPermission = false;
                    permission = null;
                    if (granted && !isFinishing() && !isDestroyed()) {
                        if (quickImportFlow) {
                            resumeUsb = true;
                            continuePendingUsb();
                        } else {
                            UsbDevice current = connected();
                            if (current != null && current.getDeviceId() == device.getDeviceId()
                                    && current.getDeviceName().equals(device.getDeviceName())) prepareImport(device, keepOriginals);
                            else connectionFailure = "カメラの接続が変わりました。もう一度操作してください。";
                        }
                    } else { resumeUsb = false; connectionFailure = "USBの使用が許可されませんでした"; }
                    renderStatus();
                });
            } catch (RuntimeException failure) {
                pendingUsbPermission = false; resumeUsb = false;
                connectionFailure = "USBの使用許可を確認できませんでした";
                renderStatus();
            }
        }
    }

    private void prepareImport(UsbDevice device, boolean keepOriginals) {
        if (!settings.getBoolean("configured", false)) {
            String[] types = {"JPEG", "RAW", "動画"};
            boolean[] checked = {true, true, true};
            var dialog = new GalleryUi.Sheet(this)
                    .setTitle("はじめての取り込み")
                    .setMultiChoiceItems(types, checked, (d, i, on) -> checked[i] = on)
                    .setNegativeButton("あとで", null)
                    .setPositiveButton("取り込む", null).create();
            dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
                var kinds = EnumSet.noneOf(MediaTypes.Kind.class);
                for (int i = 0; i < checked.length; i++) if (checked[i]) kinds.add(MediaTypes.Kind.values()[i]);
                if (kinds.isEmpty()) return;
                settings.edit().putBoolean("configured", true)
                        .putString("initial_kinds", MediaTypes.INSTANCE.kindsToString(kinds)).apply();
                dialog.dismiss();
                launch(device, false);
            }));
            dialog.show();
            return;
        }
        if (keepOriginals) {
            launch(device, false);
            return;
        }
        IO.execute(() -> {
            Camera camera = null;
            try {
                String model = device.getProductName();
                String serial = device.getSerialNumber();
                if (model != null && serial != null) camera = HistoryDb.Companion.get(this).findCamera(model, serial);
            } catch (RuntimeException ignored) {}
            boolean deletion = camera != null && camera.getDeleteAfterImport() && !camera.getMassStorage();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (deletion) new GalleryUi.Sheet(this)
                        .setTitle("取り込み後にカメラから削除しますか？")
                        .setMessage("今回保存できたファイルをカメラから削除します。カメラ側では元に戻せません。")
                        .setNegativeButton("削除せず取り込む", (d, w) -> launch(device, false))
                        .setPositiveButton("取り込んで削除", (d, w) -> launch(device, true)).show();
                else launch(device, false);
            });
        });
    }

    private void launch(UsbDevice device, boolean delete) {
        if (quickImportFlow && !quickAvailable()) return;
        if (starting || migrating || PROGRESS.getActive()) return;
        if (quickImportFlow) quickRequestId = java.util.UUID.randomUUID().toString();
        starting = true;
        try {
            ImportService.start(this, device, null, delete && !quickImportFlow, quickRequestId);
        } catch (RuntimeException failure) {
            starting = false;
            quickRequestId = null;
            PROGRESS.finish("取り込みを開始できませんでした");
        }
        handler.postDelayed(() -> { starting = false; renderStatus(); }, 4000);
        renderStatus();
    }

    private void pickSdTree() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, PICK_SD_TREE); }
        catch (RuntimeException failure) { sdStatus.setText("フォルダ選択を開けませんでした。"); }
    }

    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request == PICK_SD_TREE) {
            if (code != RESULT_OK || data == null || data.getData() == null) return;
            Uri tree = data.getData();
            try {
                if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) throw new SecurityException();
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                settings.edit().putString("sd_tree_uri", tree.toString()).apply();
                sdStatus.setText("フォルダを選択しました"); CameraDifferentialService.updateAuto(this);
            } catch (SecurityException | IllegalArgumentException failure) { sdStatus.setText("フォルダの読み取り権限を保存できませんでした。もう一度選んでください。"); }
            return;
        }
        if (request != PICK_DB || code != RESULT_OK || data == null || data.getData() == null) return;
        var document = data.getData();
        migrating = true;
        setText(migrationResult, "記録を読み込んでいます");
        renderStatus();
        IO.execute(() -> {
            String message;
            var lock = CameraImportGate.lock;
            if (!lock.tryLock()) {
                message = "取り込みが終わってから、もう一度ファイルを選んでください";
            } else {
                try {
                    var migration = CameraSyncHistoryMigration.mergeDocument(this, document,
                            HistoryDb.Companion.get(this).getWritableDatabase());
                    if (migration.camerasAdded() > 0) settings.edit().putBoolean("configured", true).apply();
                    message = migration.importsAdded() > 0 || migration.camerasAdded() > 0
                            ? migration.camerasAdded() + "台の設定と" + migration.importsAdded() + "件の記録を引き継ぎました"
                            : "新しい設定や記録はありませんでした";
                } catch (IOException | RuntimeException failure) {
                    message = "記録を読み込めませんでした。設定と履歴は変わっていません";
                } finally {
                    lock.unlock();
                }
            }
            String outcome = message;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                migrating = false;
                setText(migrationResult, outcome);
                loadCameras();
                renderStatus();
            });
        });
    }

    private String photoPermission() { return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE; }
    private boolean ensureSdPermissions() {
        boolean photos = checkSelfPermission(photoPermission()) == PackageManager.PERMISSION_GRANTED;
        boolean originals = checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (photos && originals) return true;
        showSdPermissionStatus(photos
                ? "原本をそのまま保存するため、写真の位置情報へのアクセスを許可してください。許可後にもう一度操作してください。"
                : "写真すべてと写真の位置情報へのアクセスを許可してください。選択した写真のみでは取り込めません。許可後にもう一度操作してください。");
        java.util.ArrayList<String> permissions = new java.util.ArrayList<>();
        if (!photos) {
            permissions.add(photoPermission());
            if (Build.VERSION.SDK_INT >= 34) permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        }
        if (!originals) permissions.add(Manifest.permission.ACCESS_MEDIA_LOCATION);
        requestPermissions(permissions.toArray(new String[0]), 406);
        return false;
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != 406) return;
        if (checkSelfPermission(photoPermission()) != PackageManager.PERMISSION_GRANTED)
            showSdPermissionStatus("写真すべてへのアクセスが必要です。再度許可するか、アプリの権限設定を確認してください。");
        else if (checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED)
            showSdPermissionStatus("原本の保存には写真の位置情報へのアクセスが必要です。再度操作するか、アプリの権限設定を確認してください。");
        else {
            showSdPermissionStatus("写真の原本へのアクセスを許可しました。");
            if (pendingSdPermission) { resumeSd = true; if (quickResumed) { resumeSd = false; discoverQuickSource(true); } }
        }
        pendingSdPermission = false;
    }

    private void showSdPermissionStatus(String message) {
        sdStatus.setText(message);
        sdStatus.post(() -> {
            if (!isFinishing() && !isDestroyed())
                sdStatus.requestRectangleOnScreen(new android.graphics.Rect(0, 0, sdStatus.getWidth(), sdStatus.getHeight()), true);
        });
    }

    private Uri readableSdTree() {
        String value = settings.getString("sd_tree_uri", null);
        if (value == null) return null;
        try {
            Uri uri = Uri.parse(value);
            for (android.content.UriPermission grant : getContentResolver().getPersistedUriPermissions())
                if (grant.isReadPermission() && grant.getUri().equals(uri)) return uri;
        } catch (RuntimeException ignored) { }
        return null;
    }

    private void startSd() {
        if (PROGRESS.getActive() || starting || migrating) return;
        if (!ensureSdPermissions()) return;
        Uri tree = readableSdTree();
        if (tree == null) { sdStatus.setText("フォルダの読み取り権限がありません。もう一度選んでください。"); return; }
        if (PROGRESS.getActive()) { sdStatus.setText("取り込みが終わってから再試行してください。"); return; }
        try { CameraDifferentialService.startDocumentTree(this, tree, sdVerify.isChecked()); sdStatus.setText("SDカードの取り込みを開始します。"); }
        catch (RuntimeException failure) { sdStatus.setText("取り込みを開始できませんでした。フォルダと権限を確認してください。"); }
    }

    private void loadLedgerCounts() {
        IO.execute(() -> {
            String counts;
            try {
                CameraImportLedger ledger = CameraImportLedger.get(this);
                counts = "原本 " + ledger.contentCount() + "件 · 受領記録 " + ledger.receiptCount() + "件";
            } catch (RuntimeException failure) { counts = "共通履歴の件数を確認できませんでした"; }
            String result = counts;
            runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) ledgerStatus.setText(result); });
        });
    }

    private void loadCameras() {
        IO.execute(() -> {
            List<Camera> rows;
            try { rows = HistoryDb.Companion.get(this).listCameras(); }
            catch (RuntimeException failure) { rows = List.of(); }
            List<Camera> snapshot = rows;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                cameras.removeViews(1, cameras.getChildCount() - 1);
                if (snapshot.isEmpty()) cameras.addView(text("カメラごとの設定は、最初の取り込み後に変更できます", 14, GalleryUi.MUTED));
                for (Camera camera : snapshot) {
                    button(cameras, camera.getModel() + " · " + camera.getFolder(), () -> editCamera(camera));
                }
            });
        });
    }

    private void editCamera(Camera camera) {
        if (PROGRESS.getActive() || migrating) return;
        var body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), 0, dp(16), 0);
        var folder = new EditText(this);
        folder.setSingleLine(true);
        folder.setHint("端末の保存先フォルダ名");
        folder.setContentDescription("端末の保存先フォルダ名");
        folder.setText(camera.getFolder());
        body.addView(folder);
        var kinds = EnumSet.copyOf(camera.getDefaultKinds());
        for (var kind : MediaTypes.Kind.values()) {
            var item = new android.widget.CheckBox(this);
            item.setText(kind == MediaTypes.Kind.VIDEO ? "動画" : kind.name());
            item.setChecked(kinds.contains(kind));
            item.setMinHeight(dp(48));
            item.setOnCheckedChangeListener((v, checked) -> {
                if (checked) kinds.add(kind); else kinds.remove(kind);
            });
            body.addView(item);
        }
        var deletion = new MaterialSwitch(this);
        deletion.setText("手動取り込み後にカメラから削除する");
        deletion.setMinHeight(dp(48));
        deletion.setChecked(!camera.getMassStorage() && camera.getDeleteAfterImport());
        deletion.setEnabled(!camera.getMassStorage());
        body.addView(deletion);
        deletion.setOnCheckedChangeListener((v, enabled) -> {
            if (!enabled || camera.getDeleteAfterImport()) return;
            var confirm = new GalleryUi.Sheet(this).setTitle("カメラ側の削除を有効にしますか？")
                    .setMessage("自動取り込みでは削除しません。手動取り込みの前に、もう一度確認します。")
                    .setNegativeButton("キャンセル", (d, w) -> deletion.setChecked(false))
                    .setPositiveButton("有効にする", null).create();
            confirm.setOnCancelListener(d -> deletion.setChecked(false));
            confirm.show();
        });
        var dialog = new GalleryUi.Sheet(this).setTitle(camera.getModel())
                .setView(body).setNegativeButton("キャンセル", null)
                .setNeutralButton("履歴を消去", (d, w) -> confirmReset(camera))
                .setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            if (kinds.isEmpty()) return;
            Camera updated = new Camera(camera.getModel(), camera.getSerial(),
                    ImportService.Companion.sanitizeFolder(folder.getText().toString()),
                    deletion.isChecked(), kinds, camera.getMassStorage());
            dialog.dismiss();
            IO.execute(() -> {
                var lock = CameraImportGate.lock;
                if (!lock.tryLock()) return;
                try { HistoryDb.Companion.get(this).saveCamera(updated); }
                catch (RuntimeException failure) { runOnUiThread(() -> setText(migrationResult, "設定を保存できませんでした")); }
                finally { lock.unlock(); }
                loadCameras();
            });
        }));
        dialog.show();
    }

    private void confirmReset(Camera camera) {
        new GalleryUi.Sheet(this).setTitle("このカメラの取り込み履歴を消去しますか？")
                .setMessage("端末にある写真は残ります。次回は保存済みファイルを確認しながら取り込みます。")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("履歴を消去", (d, w) -> IO.execute(() -> {
                    var lock = CameraImportGate.lock;
                    if (!lock.tryLock()) {
                        runOnUiThread(() -> setText(migrationResult, "取り込みの完了後にもう一度お試しください"));
                        return;
                    }
                    try {
                        HistoryDb.Companion.get(this).resetHistory(camera.getModel(), camera.getSerial());
                        runOnUiThread(() -> setText(migrationResult, "取り込み履歴を消去しました"));
                    } catch (RuntimeException failure) {
                        runOnUiThread(() -> setText(migrationResult, "取り込み履歴を消去できませんでした"));
                    } finally { lock.unlock(); }
                })).show();
    }

    private LinearLayout card(LinearLayout parent, String name) {
        var card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        var bg = new GradientDrawable();
        bg.setColor(GalleryUi.PANEL);
        bg.setCornerRadius(dp(20));
        card.setBackground(bg);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        var lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        parent.addView(card, lp);
        var header = text(name, 16, GalleryUi.TEXT);
        header.setTypeface(null, Typeface.BOLD);
        header.setAccessibilityHeading(true);
        card.addView(header);
        return card;
    }

    private TextView status(LinearLayout parent) {
        TextView status = text("", 14, GalleryUi.MUTED);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        parent.addView(status);
        return status;
    }

    private Button button(LinearLayout parent, String label, Runnable click) {
        Button button = MaterialUi.button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setMinHeight(dp(48));
        button.setOnClickListener(v -> click.run());
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    private TextView text(String label, int size, int color) {
        var view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private static void setText(TextView view, String label) {
        if (!android.text.TextUtils.equals(view.getText(), label)) view.setText(label);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
