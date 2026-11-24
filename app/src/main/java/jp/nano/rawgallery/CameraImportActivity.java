package jp.nano.rawgallery;

import android.Manifest;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
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
import jp.nano.rawgallery.camera.CameraDocumentSource;
import jp.nano.rawgallery.camera.CameraImportGate;
import jp.nano.rawgallery.camera.CameraCardRouting;
import jp.nano.rawgallery.camera.CameraImportLedger;
import jp.nano.rawgallery.camera.CameraUsb;
import jp.nano.rawgallery.camera.UsbMassStorage;
import jp.nano.rawgallery.camera.UsbLaunchSettings;
import jp.nano.rawgallery.camera.HistoryDb;
import jp.nano.rawgallery.camera.ImportProgress;
import jp.nano.rawgallery.camera.ImportService;
import jp.nano.rawgallery.camera.MediaTypes;
import jp.nano.rawgallery.camera.MountedCameraCard;
import jp.nano.rawgallery.camera.SdCardRemount;
import jp.nano.rawgallery.camera.SdSourceRegistrations;

import java.io.Closeable;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class CameraImportActivity extends AppCompatActivity {
    static final String START_IMPORT_KEEP_ORIGINALS = "start_import_keep_originals";
    static final String SD_REMOUNT_ON_ENTRY = SdCardRemount.ENABLED_PREFERENCE;
    private static final String ATTACHED_SD_TARGET = "attached_sd_target";

    public static Intent mountedReaderAttachIntent(Context context, String expectedTarget) {
        return new Intent(context, CameraImportActivity.class).putExtra(ATTACHED_SD_TARGET, expectedTarget);
    }

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
    private LinearProgressIndicator sdChecking;
    private Button sdStart;
    private CameraQuickImportSources.SdState sdSourceState;
    private MaterialButton sdSettingsButton;
    private TextView sdSettingsStatus;
    private androidx.appcompat.app.AlertDialog sdSettingsDialog;
    private MaterialSwitch sdRemount;
    private TextView sdRemountInfo, sdRemountResult;
    private Button sdRemountPermission, sdReconnect;
    private androidx.appcompat.app.AlertDialog sdReconnectConfirmation;
    private SdRemountState sdRemountState;
    private boolean freshManualEntry, sdRefreshAfterRemount, discoverAfterRemount;
    private boolean sdRemountPermissionPending, sdRemountPermissionDenied;
    private android.os.CancellationSignal sdStatusCancellation;
    private Runnable removeMountObserver;
    private androidx.appcompat.app.AlertDialog sdConfirmation;
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
    private record SdPicker(String target, String destination, String kinds, long generation,
            boolean registrationOnly, String previousRegistration) {}
    record SdStart(Uri tree, String target, String destination, String kinds, long generation,
            boolean picked, String previousRegistration) {}
    private SdPicker sdPicker;
    private SdStart pendingSdStart;
    private CameraDifferentialService.SdConfirmation submittedSd;

    interface SdSubmission {
        void submit(SdStart request, boolean registrationOnly, CameraDifferentialService.SdConfirmation confirmation);
    }

    SdSubmission sdSubmission = (request, registrationOnly, confirmation) ->
            CameraDifferentialService.submitConfirmedSd(this, request.tree(), sdVerify.isChecked(), request.target(),
                    request.kinds(), request.generation(), request.destination(), request.picked(), registrationOnly,
                    request.previousRegistration(), confirmation);
    private int pendingUsbDeviceId = -1, pendingUsbVendorId = -1, pendingUsbProductId = -1;
    private String pendingUsbDeviceName, pendingUsbVolume;
    private static final java.util.concurrent.Executor SOURCE_IO = new java.util.concurrent.ThreadPoolExecutor(
            0, 2, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>());
    private static final java.util.concurrent.Executor SOURCE_STATUS_IO = new java.util.concurrent.ThreadPoolExecutor(
            0, 2, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>());

    // Instance-scoped loader lets instrumentation supply metadata without touching registrations.
    interface WifiProfileLoader {
        List<CameraQuickImportSources.Profile> load(android.content.Context context,
                android.os.CancellationSignal cancellation) throws IOException;
    }
    WifiProfileLoader wifiProfileLoader = CameraQuickImportSources::wifiProfiles;

    interface SdRemountAccess {
        SdCardRemount.Status status();
        void requestPermission(Consumer<Boolean> callback);
        boolean start(Consumer<SdCardRemount.Result> callback);
        SdCardRemount.ReconnectTarget reconnectTarget();
        boolean reconnect(SdCardRemount.ReconnectTarget target, Consumer<SdCardRemount.Result> callback);
        boolean isRunning();
    }

    public static final class SdRemountState extends ViewModel {
        private boolean running;
        private boolean reconnecting;
        private final MutableLiveData<SdCardRemount.Result> completion = new MutableLiveData<>();

        private void finish(SdCardRemount.Result outcome) {
            running = false;
            completion.setValue(outcome);
        }
    }

    SdRemountAccess sdRemountAccess = new SdRemountAccess() {
        @Override public SdCardRemount.Status status() { return SdCardRemount.status(); }
        @Override public void requestPermission(Consumer<Boolean> callback) {
            SdCardRemount.requestPermission(callback);
        }
        @Override public boolean start(Consumer<SdCardRemount.Result> callback) {
            return SdCardRemount.start(getApplicationContext(), callback);
        }
        @Override public SdCardRemount.ReconnectTarget reconnectTarget() {
            return SdCardRemount.reconnectTarget(CameraImportActivity.this);
        }
        @Override public boolean reconnect(SdCardRemount.ReconnectTarget target, Consumer<SdCardRemount.Result> callback) {
            return SdCardRemount.reconnect(getApplicationContext(), target, callback);
        }
        @Override public boolean isRunning() { return SdCardRemount.isRunning(); }
    };
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
            resumeAfterSdRemount();
            handler.postDelayed(this, 350);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        sdRemountState = new ViewModelProvider(this).get(SdRemountState.class);
        settings = getSharedPreferences("camera_import", MODE_PRIVATE);
        freshManualEntry = state == null && getIntent().getAction() == null
                && !getIntent().hasExtra(ATTACHED_SD_TARGET);
        if (state != null) {
            discoverAfterRemount = state.getBoolean("sd_remount_discover_pending");
            pendingUsbDeviceId = state.getInt("quick_usb_device_id", -1);
            pendingUsbDeviceName = state.getString("quick_usb_device_name");
            pendingUsbVolume = state.getString("quick_usb_volume");
            pendingUsbVendorId = state.getInt("quick_usb_vendor_id", -1);
            pendingUsbProductId = state.getInt("quick_usb_product_id", -1);
            pendingUsbPermission = state.getBoolean("quick_usb_permission");
            resumeUsb = state.getBoolean("quick_usb_resume");
            quickConsumed = state.getBoolean("quick_consumed");
            if (state.getBoolean("quick_source_pending")) connectionFailure = "取り込み元の確認を中止しました。もう一度長押ししてください。";
            String pendingTree = state.getString("quick_sd_tree");
            if (pendingTree != null) pendingSdStart = new SdStart(Uri.parse(pendingTree),
                    state.getString("quick_sd_target"), state.getString("quick_sd_destination"),
                    state.getString("quick_sd_kinds"), state.getLong("quick_sd_generation"),
                    state.getBoolean("quick_sd_picked"), state.getString("quick_sd_previous_registration"));
            String pickerDestination = state.getString("sd_picker_destination");
            if (pickerDestination != null) sdPicker = new SdPicker(state.getString("sd_picker_target"),
                    pickerDestination, state.getString("sd_picker_kinds"), state.getLong("sd_picker_generation"),
                    state.getBoolean("sd_picker_registration"), state.getString("sd_picker_previous_registration"));
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
        button(preferences, "写真・動画の保存先", () -> startActivity(new Intent(this, PhotoStorageActivity.class)));
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
        addSdImportCard(root);
        renderSdSource(CameraQuickImportSources.SdState.CHECKING);
        removeMountObserver = MountedCameraCard.observeMounts(this, this::onSdMountChange);
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
        if (sdRemountState.running) showSdRemountProgress();
        sdRemountState.completion.observe(this, this::showSdRemountCompletion);
        renderSettingsVisibility();
        renderStatus();
    }

    private void addSdImportCard(LinearLayout root) {
        LinearLayout sd = card(root, "SDカード取り込み");
        sd.setTag("sd-import-card");
        TextView heading = (TextView) sd.getChildAt(0);
        sd.removeView(heading);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        sdSettingsButton = LibraryUi.icon(this, R.drawable.ic_viewer_edit,
                "SDカード取り込み設定", this::openSdSettings);
        sdSettingsButton.setTag("sd-import-settings");
        header.addView(sdSettingsButton);
        sd.addView(header);
        sdStatus = status(sd);
        sdStatus.setTag("sd-import-status");
        sdRemountResult = status(sd);
        sdRemountResult.setTag("sd-remount-result");
        sdRemountResult.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        sdRemountResult.setVisibility(View.GONE);
        sdChecking = new LinearProgressIndicator(this);
        sdChecking.setIndeterminate(true);
        sdChecking.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sdChecking.setVisibility(View.GONE);
        sd.addView(sdChecking);
        sdStart = button(sd, "SDカードから取り込む", this::startSd);
        sdStart.setTag("sd-import-start");
        MaterialUi.filled(sdStart);

        var scroll = new ScrollView(this);
        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(dp(24), dp(8), dp(24), dp(12));
        scroll.addView(options);
        sdSettingsStatus = status(options);
        button(options, "SDカードのフォルダを選ぶ", () -> {
            sdSettingsDialog.dismiss();
            pickSdTree();
        });
        sdReconnect = button(options, "リーダーを再接続", this::confirmSdReconnect);
        sdReconnect.setTag("sd-reader-reconnect");
        sdReconnect.setSingleLine(false);
        sdReconnect.setMinHeight(dp(48));
        MaterialUi.outlined(sdReconnect);
        sdRemount = new MaterialSwitch(this);
        sdRemount.setText("取り込み画面を開くと取り込み元のSDを再マウント");
        sdRemount.setTag("sd-remount-switch");
        sdRemount.setSingleLine(false);
        sdRemount.setMinHeight(dp(48));
        sdRemount.setChecked(settings.getBoolean(SD_REMOUNT_ON_ENTRY, false));
        sdRemount.setOnCheckedChangeListener((v, checked) -> {
            settings.edit().putBoolean(SD_REMOUNT_ON_ENTRY, checked).apply();
            sdRemountPermissionDenied = false;
            if (checked) requestSdRemountPermission();
            else renderSdRemountSettings();
        });
        options.addView(sdRemount);
        sdRemountInfo = status(options);
        sdRemountInfo.setTag("sd-remount-info");
        sdRemountPermission = button(options, "Shizukuの使用を許可する", this::requestSdRemountPermission);
        sdRemountPermission.setTag("sd-remount-permission");
        sdVerify = new MaterialSwitch(this);
        sdVerify.setText("内容も確認する");
        sdVerify.setMinHeight(dp(48));
        sdVerify.setChecked(settings.getBoolean("sd_verify_all", false));
        sdVerify.setOnCheckedChangeListener((v, checked) -> settings.edit().putBoolean("sd_verify_all", checked).apply());
        options.addView(sdVerify);
        options.addView(text("通常は取り込み済みの写真を読み直しません。写真の内容や保存先を確認し直すときはオンにします。", 14, GalleryUi.MUTED));

        sdAuto = new MaterialSwitch(this);
        sdAuto.setText("SDカード接続時に自動で取り込む");
        sdAuto.setMinHeight(dp(48));
        sdAuto.setChecked(settings.getBoolean("sd_auto", false));
        sdAuto.setOnCheckedChangeListener((v, checked) -> {
            if (checked && !ensureSdPermissions()) { sdAuto.setChecked(false); return; }
            if (checked && !SdSourceRegistrations.hasReadableRegistration(this)) {
                sdAuto.setChecked(false);
                showSdPermissionStatus("先に読み取り可能なフォルダを選んでください。");
                return;
            }
            settings.edit().putBoolean("sd_auto", checked).apply();
            CameraDifferentialService.updateAuto(this);
            if (checked && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 403);
        });
        options.addView(sdAuto);
        options.addView(text("原本をそのまま保存して重複を確認するため、写真すべてと写真の位置情報へのアクセスが必要です。",
                14, GalleryUi.MUTED));
        button(options, "写真のアクセス権限を確認", this::ensureSdPermissions);
        button(options, "アプリの権限設定を開く", () ->
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null))));
        sdSettingsDialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("SDカード取り込み設定")
                .setView(scroll)
                .setPositiveButton("閉じる", null)
                .create();
    }

    private void openSdSettings() {
        if (quickAvailable() && sdSettingsButton.isEnabled() && !sdSettingsDialog.isShowing()) {
            renderSdRemountSettings();
            sdSettingsDialog.show();
        }
    }

    private void renderSdRemountSettings() {
        boolean reader = sdRemountAccess.reconnectTarget() != null;
        SdCardRemount.Status status = sdRemount.isChecked() || reader ? sdRemountAccess.status() : null;
        String info = "";
        if (status == SdCardRemount.Status.UNAVAILABLE)
            info = "Shizukuをインストールし、ADBモードで起動してください。";
        else if (status == SdCardRemount.Status.NEEDS_PERMISSION)
            info = sdRemountPermissionDenied ? "使用が許可されませんでした。ShizukuでLoupeの使用を許可してください。"
                    : "Shizukuの使用を許可してください。";
        setText(sdRemountInfo, info);
        sdRemountInfo.setVisibility(info.isEmpty() ? View.GONE : View.VISIBLE);
        sdRemountPermission.setVisibility(status == SdCardRemount.Status.NEEDS_PERMISSION ? View.VISIBLE : View.GONE);
        sdRemountPermission.setEnabled(!sdRemountPermissionPending);
        sdReconnect.setVisibility(reader ? View.VISIBLE : View.GONE);
        sdReconnect.setEnabled(status == SdCardRemount.Status.READY && canStartSourceAction());
    }

    private void requestSdRemountPermission() {
        if (!sdRemountPermissionPending && (sdRemount.isChecked() || sdRemountAccess.reconnectTarget() != null)
                && sdRemountAccess.status() == SdCardRemount.Status.NEEDS_PERMISSION) {
            sdRemountPermissionPending = true;
            sdRemountPermissionDenied = false;
            sdRemountAccess.requestPermission(granted -> {
                if (isFinishing() || isDestroyed()) return;
                sdRemountPermissionPending = false;
                sdRemountPermissionDenied = !granted;
                renderSdRemountSettings();
            });
        }
        renderSdRemountSettings();
    }

    private void confirmSdReconnect() {
        SdCardRemount.ReconnectTarget target = sdRemountAccess.reconnectTarget();
        if (!canStartSourceAction() || sdReconnectConfirmation != null || target == null
                || sdRemountAccess.status() != SdCardRemount.Status.READY) {
            renderSdRemountSettings();
            return;
        }
        sdReconnectConfirmation = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("リーダーを再接続しますか？")
                .setMessage("SDカードとmicroSDが一時的に切断されます。ほかのアプリも含め、カードの読み書きが終わってから再接続してください。")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("再接続", (dialog, which) -> startSdReconnect(target))
                .create();
        sdReconnectConfirmation.setOnDismissListener(dialog -> sdReconnectConfirmation = null);
        sdReconnectConfirmation.show();
    }

    private void startSdReconnect(SdCardRemount.ReconnectTarget target) {
        if (!canStartSourceAction()) return;
        sdSettingsDialog.dismiss();
        if (!target.equals(sdRemountAccess.reconnectTarget())) {
            showSdRemountResult("リーダーかカードの接続が変わりました。接続を確認してからやり直してください。");
            return;
        }
        if (!sdRemountAccess.reconnect(target, sdRemountState::finish)) {
            showSdRemountResult("再接続を開始できませんでした。Shizukuとリーダーの接続を確認してください。");
            return;
        }
        beginSdRemount(true);
    }

    private boolean remountBusy() {
        return sdRemountState.running || sdRemountAccess.isRunning();
    }

    private void startSdRemountOnEntry() {
        if (!settings.getBoolean(SD_REMOUNT_ON_ENTRY, false) || !canStartSourceAction()
                || sdPicker != null || pendingSdStart != null || sdConfirmation != null || sdSettingsDialog.isShowing()
                || sdRemountAccess.status() != SdCardRemount.Status.READY) return;
        if (!sdRemountAccess.start(sdRemountState::finish)) return;
        beginSdRemount(false);
    }

    private void beginSdRemount(boolean reconnecting) {
        sdRemountState.reconnecting = reconnecting;
        sdRemountState.running = true;
        cancelSdStatusLoad();
        showSdRemountProgress();
        renderStatus();
    }

    private void showSdRemountProgress() {
        showSdRemountResult(sdRemountState.reconnecting ? "リーダーを再接続しています…" : "SDカードを再マウントしています…");
    }

    private void showSdRemountCompletion(SdCardRemount.Result outcome) {
        if (isFinishing() || isDestroyed() || sdRemountState.running) return;
        boolean reconnecting = sdRemountState.reconnecting;
        showSdRemountResult(switch (outcome) {
            case MOUNTED -> "SDカードを再マウントしました。";
            case RECONNECTED -> "リーダーを再接続しました。";
            case SOURCE_MISSING -> "保存先は再接続しました。取り込み元のSDカードを認識できませんでした。認識されたら手動で取り込んでください。";
            case SKIPPED -> reconnecting ? "リーダーや保存先の接続を確認してください。" : "";
            case UNAVAILABLE -> reconnecting ? "Shizukuを起動してから、再接続を試してください。"
                    : "Shizukuを起動してから、取り込み画面を開き直してください。";
            case FAILED -> reconnecting ? "リーダーを再接続できませんでした。SDカードと保存先の接続を確認してください。"
                    : "SDカードを再マウントできませんでした。接続を確認してください。";
        });
        sdRefreshAfterRemount = true;
        renderStatus();
        resumeAfterSdRemount();
    }

    private void showSdRemountResult(String message) {
        setText(sdRemountResult, message);
        sdRemountResult.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void resumeAfterSdRemount() {
        if ((!sdRefreshAfterRemount && !discoverAfterRemount) || !canStartSourceAction()) return;
        if (sdPicker != null || sdConfirmation != null || pendingSdStart != null) return;
        if (sdRefreshAfterRemount) refreshSdSource();
        if (discoverAfterRemount) {
            discoverAfterRemount = false;
            discoverQuickSource(false);
        }
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

    @Override public void finish() {
        abandonSdSubmission();
        super.finish();
    }

    @Override protected void onDestroy() {
        if (removeMountObserver != null) removeMountObserver.run();
        abandonSdSubmission();
        cancelSdStatusLoad();
        if (sdReconnectConfirmation != null) sdReconnectConfirmation.dismiss();
        sdSettingsDialog.dismiss();
        if (permission != null) {
            try { permission.close(); } catch (IOException ignored) {}
        }
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("sd_remount_discover_pending", discoverAfterRemount);
        state.putInt("quick_usb_device_id", pendingUsbDeviceId);
        state.putString("quick_usb_device_name", pendingUsbDeviceName);
        state.putString("quick_usb_volume", pendingUsbVolume);
        state.putInt("quick_usb_vendor_id", pendingUsbVendorId);
        state.putInt("quick_usb_product_id", pendingUsbProductId);
        state.putBoolean("quick_usb_permission", pendingUsbPermission);
        state.putBoolean("quick_usb_resume", resumeUsb);
        state.putBoolean("quick_source_pending", sourceCancellation != null);
        state.putBoolean("quick_consumed", quickConsumed);
        if (pendingSdStart != null) {
            state.putString("quick_sd_tree", pendingSdStart.tree().toString());
            state.putString("quick_sd_target", pendingSdStart.target());
            state.putString("quick_sd_destination", pendingSdStart.destination());
            state.putString("quick_sd_kinds", pendingSdStart.kinds());
            state.putLong("quick_sd_generation", pendingSdStart.generation());
            state.putBoolean("quick_sd_picked", pendingSdStart.picked());
            state.putString("quick_sd_previous_registration", pendingSdStart.previousRegistration());
        }
        if (sdPicker != null) {
            state.putString("sd_picker_target", sdPicker.target());
            state.putString("sd_picker_destination", sdPicker.destination());
            state.putString("sd_picker_kinds", sdPicker.kinds());
            state.putLong("sd_picker_generation", sdPicker.generation());
            state.putBoolean("sd_picker_registration", sdPicker.registrationOnly());
            state.putString("sd_picker_previous_registration", sdPicker.previousRegistration());
        }
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
        if (freshManualEntry) {
            freshManualEntry = false;
            startSdRemountOnEntry();
        }
        if (sdSettingsDialog.isShowing()) renderSdRemountSettings();
        if (sdConfirmation == null && !starting && sdPicker == null && pendingSdStart == null) refreshSdSource();
        if (resumeUsb || (pendingUsbPermission && permission == null)) continuePendingUsb();
        else if (resumeSd) resumeSdStart();
        else if (!quickConsumed && getIntent().hasExtra(ATTACHED_SD_TARGET)) {
            quickConsumed = true;
            String target = getIntent().getStringExtra(ATTACHED_SD_TARGET);
            getIntent().removeExtra(ATTACHED_SD_TARGET);
            if (target != null) discoverQuickSource(false, target);
        } else if (!quickConsumed && getIntent().getBooleanExtra(START_IMPORT_KEEP_ORIGINALS, false)) {
            quickConsumed = true;
            getIntent().removeExtra(START_IMPORT_KEEP_ORIGINALS);
            quickImportFlow = true;
            if (remountBusy()) discoverAfterRemount = true;
            else discoverQuickSource(false);
        }
        resumeAfterSdRemount();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getAction() != null) {
            freshManualEntry = false;
            discoverAfterRemount = false;
        }
    }

    @Override protected void onPause() {
        quickResumed = false;
        abandonSdSubmission();
        cancelSdStatusLoad();
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
                && !CameraImportGate.lock.isLocked() && !migrating && submittedSd == null && !remountBusy();
    }

    private void discoverQuickSource(boolean manualSd) {
        discoverQuickSource(manualSd, null);
    }

    private void discoverQuickSource(boolean manualSd, String attachedSdTarget) {
        if (!quickAvailable() || starting || sourceCancellation != null || sdConfirmation != null) return;
        String destination = PhotoStorage.selected(this);
        UsbDevice usb = manualSd || attachedSdTarget != null ? null : connected();
        String expectedTarget = manualSd ? MountedCameraCard.targetWithGenericReader(this, destination)
                : attachedSdTarget != null ? attachedSdTarget : guardedSdTarget(usb);
        if (!manualSd && expectedTarget == null && usb != null) { startFromScreen(true); return; }
        boolean sdOnly = manualSd || expectedTarget != null;
        long detach = MountedCameraCard.detachGeneration();
        int generation = ++sourceGeneration;
        var cancellation = new android.os.CancellationSignal();
        sourceCancellation = cancellation;
        starting = true;
        if (sdOnly) {
            cancelSdStatusLoad();
            renderSdSource(CameraQuickImportSources.SdState.CHECKING);
        }
        connectionFailure = "取り込み元を確認しています…";
        renderStatus();
        Runnable timeout = () -> {
            if (sourceCancellation != cancellation) return;
            sourceGeneration++;
            cancellation.cancel();
            sourceCancellation = null;
            starting = false;
            showSdFailure("取り込み元を確認できませんでした。接続を確認してからやり直してください。");
        };
        handler.postDelayed(timeout, 3000);
        try {
            SOURCE_IO.execute(() -> {
                CameraQuickImportSources.Sources sources = null;
                try {
                    if (sdOnly) {
                        var selected = CameraQuickImportSources.inspectSd(getApplicationContext(), cancellation);
                        sources = CameraQuickImportSources.Sources.fromSd(selected, List.of());
                    } else sources = CameraQuickImportSources.resolve(getApplicationContext(), cancellation);
                } catch (IOException | RuntimeException ignored) { }
                var outcome = sources;
                UsbDevice formatDevice = manualSd && expectedTarget != null ? connected() : usb;
                String kinds = expectedTarget != null
                        ? requestedUsbKinds(guardedSdTarget(formatDevice, destination) != null ? formatDevice : null)
                        : manualSd ? "JPEG,RAW" : null;
                handler.post(() -> {
                    if (generation != sourceGeneration || sourceCancellation != cancellation) return;
                    handler.removeCallbacks(timeout);
                    sourceCancellation = null;
                    starting = false;
                    if (!quickAvailable()) return;
                    if (!destination.equals(PhotoStorage.selected(this))
                            || detach != MountedCameraCard.detachGeneration()
                            || expectedTarget != null && !expectedTarget.equals(destination)) {
                        showSdFailure("カードの接続または保存先が変わりました。取り込み元を確認してください。");
                        return;
                    }
                    if (!sdOnly && connected() != null) { startFromScreen(true); return; }
                    var inspected = outcome == null ? null : outcome.sdSource;
                    var validated = inspected == null ? null : CameraQuickImportSources.recheckSd(this, inspected);
                    Uri readyTree = validated == null ? null : validated.reachableTree();
                    boolean chooseSd = inspected != null && (inspected.reachableTree() != null
                            || inspected.state() == CameraQuickImportSources.SdState.AMBIGUOUS
                            || validated.state() == CameraQuickImportSources.SdState.AMBIGUOUS);
                    if (readyTree != null) {
                        connectionFailure = null;
                        renderSdSource(CameraQuickImportSources.SdState.READY);
                        SdStart request = new SdStart(readyTree, expectedTarget, destination, kinds, detach, false, null);
                        if (expectedTarget == null) requestSdStart(request);
                        else confirmSdSource(request, false);
                    } else if (sdOnly || chooseSd) {
                        var state = validated == null ? CameraQuickImportSources.SdState.FOLDER_UNAVAILABLE : validated.state();
                        renderSdSource(state);
                        connectionFailure = state.message;
                        renderStatus();
                        pickSdTree(expectedTarget, kinds, false);
                    } else if (outcome == null) {
                        connectionFailure = "登録情報を確認できませんでした。QRの登録を確認してください。";
                        renderStatus();
                    } else if (outcome.profiles.size() == 1) openQuickProfile(outcome.profiles.get(0).id);
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

    private boolean canStartSourceAction() {
        return quickAvailable() && !starting && permission == null && !pendingSdPermission
                && !pendingUsbPermission && !resumeSd && !resumeUsb && !wifiLaunching;
    }

    private void openWifiSettings() {
        if (!canStartSourceAction()) return;
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
        if (!canStartSourceAction() || wifiCancellation != null) return;
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
        boolean enabled = canStartSourceAction();
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
            SOURCE_STATUS_IO.execute(() -> {
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

    private void rememberPendingUsb(UsbDevice device, String expectedVolume) {
        pendingUsbVolume = expectedVolume;
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
        String expectedVolume = pendingUsbVolume;
        pendingUsbVolume = null;
        if (expectedVolume == null || !expectedVolume.equals(PhotoStorage.selected(this))) {
            connectionFailure = "保存先が変わりました。保存先を確認して取り込み直してください。";
            return;
        }
        prepareImport(device, true, expectedVolume);
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
        boolean mountedRoute = guardedSdTarget(device) != null;
        boolean granted = device != null && getSystemService(UsbManager.class).hasPermission(device);
        boolean active = PROGRESS.getActive();
        if (wasActive && !active) {
            loadCameras();
            loadLedgerCounts();
            if (quickResumed && !starting && sdPicker == null && sdConfirmation == null && submittedSd == null)
                refreshSdSource();
        }
        wasActive = active;
        if (active) starting = false;
        setText(connection, device == null ? "USBケーブルでカメラをつないでください"
                : (device.getProductName() == null ? "カメラ" : device.getProductName())
                  + (mountedRoute ? " · SDカードのフォルダを使用" :
                      granted ? (manualOnly ? " · 手動取り込み" : " · 接続済み") : " · USBの使用許可が必要です"));
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
                : settings.getBoolean("configured", false) ? "今すぐ取り込む" : "取り込みの設定をする");
        boolean remountBusy = remountBusy();
        run.setEnabled(device != null && !active && !starting && permission == null && !migrating
                && submittedSd == null && !remountBusy);
        run.setVisibility(active || mountedRoute ? View.GONE : View.VISIBLE);
        if (notifyButton != null) notifyButton.setVisibility(
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ? View.VISIBLE : View.GONE);
        stop.setVisibility(active ? View.VISIBLE : View.GONE);
        stop.setEnabled(active && !PROGRESS.getStopRequested());
        setText(stop, PROGRESS.getStopRequested() ? "中止しています" : "中止");
        renderWifiEnabled();
        auto.setEnabled(UsbLaunchSettings.isEnabled(this) && !active && !migrating && !manualOnly);
        migrate.setEnabled(!active && !starting && !migrating && !remountBusy);
        boolean sdAvailable = !active && !starting && !migrating && permission == null
                && sdConfirmation == null && sdPicker == null && submittedSd == null && !remountBusy;
        sdStart.setEnabled(sdAvailable);
        sdSettingsButton.setEnabled(sdAvailable);
        if (active && sdSettingsDialog.isShowing()) sdSettingsDialog.dismiss();
    }

    private void renderSettingsVisibility() {
        importSettings.setVisibility(settingsExpanded ? View.VISIBLE : View.GONE);
        settingsButton.setChecked(settingsExpanded);
        androidx.core.view.ViewCompat.setStateDescription(settingsButton,
                settingsExpanded ? "開いています" : "閉じています");
    }

    private void startFromScreen() { startFromScreen(quickImportFlow); }

    private void startFromScreen(boolean keepOriginals) {
        if (PROGRESS.getActive() || starting || migrating || permission != null || remountBusy()) return;
        UsbDevice device = connected();
        if (device == null) return;
        String expectedVolume = PhotoStorage.selected(this);
        if (guardedSdTarget(device, expectedVolume) != null) { discoverQuickSource(false); return; }
        connectionFailure = null;
        if (getSystemService(UsbManager.class).hasPermission(device)) prepareImport(device, keepOriginals, expectedVolume);
        else {
            try {
                if (quickImportFlow) rememberPendingUsb(device, expectedVolume);
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
                                    && current.getDeviceName().equals(device.getDeviceName())) prepareImport(device, keepOriginals, expectedVolume);
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

    private void prepareImport(UsbDevice device, boolean keepOriginals, String expectedVolume) {
        if (!expectedVolume.equals(PhotoStorage.selected(this))) {
            connectionFailure = "保存先が変わりました。保存先を確認して取り込み直してください。";
            renderStatus();
            return;
        }
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
                launch(device, false, expectedVolume);
            }));
            dialog.show();
            return;
        }
        if (keepOriginals) {
            launch(device, false, expectedVolume);
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
                        .setNegativeButton("削除せず取り込む", (d, w) -> launch(device, false, expectedVolume))
                        .setPositiveButton("取り込んで削除", (d, w) -> launch(device, true, expectedVolume)).show();
                else launch(device, false, expectedVolume);
            });
        });
    }

    private void launch(UsbDevice device, boolean delete, String expectedVolume) {
        if (quickImportFlow && !quickAvailable()) return;
        if (starting || migrating || PROGRESS.getActive()) return;
        if (!expectedVolume.equals(PhotoStorage.selected(this))) {
            connectionFailure = "保存先が変わりました。保存先を確認して取り込み直してください。";
            renderStatus();
            return;
        }
        if (guardedSdTarget(device, expectedVolume) != null) { discoverQuickSource(false); return; }
        if (quickImportFlow) quickRequestId = java.util.UUID.randomUUID().toString();
        starting = true;
        try {
            ImportService.start(this, device, null, delete && !quickImportFlow, quickRequestId, expectedVolume);
        } catch (RuntimeException failure) {
            starting = false;
            quickRequestId = null;
            PROGRESS.finish("取り込みを開始できませんでした");
        }
        handler.postDelayed(() -> { starting = false; renderStatus(); }, 4000);
        renderStatus();
    }

    private String guardedSdTarget(UsbDevice device) {
        return guardedSdTarget(device, PhotoStorage.selected(this));
    }

    private String guardedSdTarget(UsbDevice device, String selected) {
        if (device == null) return null;
        var route = CameraCardRouting.route(UsbMassStorage.Companion.isMassStorage(device),
                CameraUsb.knownCamera(device), !PhotoStorage.PRIMARY.equals(selected));
        return route == CameraCardRouting.Route.MOUNTED_SOURCE ? selected : null;
    }

    private void pickSdTree() {
        pickSdTree(MountedCameraCard.targetWithGenericReader(this, PhotoStorage.selected(this)), null, true);
    }

    private void pickSdTree(String expectedTarget, String kinds, boolean registrationOnly) {
        if (sdPicker != null || sdConfirmation != null || PROGRESS.getActive() || starting || migrating
                || submittedSd != null || remountBusy()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        sdPicker = new SdPicker(expectedTarget, PhotoStorage.selected(this), kinds,
                MountedCameraCard.detachGeneration(), registrationOnly, SdSourceRegistrations.latest(this));
        try { startActivityForResult(intent, PICK_SD_TREE); }
        catch (RuntimeException failure) {
            sdPicker = null;
            showSdPermissionStatus("フォルダ選択を開けませんでした。");
        }
    }

    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request == PICK_SD_TREE) {
            SdPicker picker = sdPicker;
            sdPicker = null;
            if (picker == null || code != RESULT_OK || data == null || data.getData() == null) {
                quickCancelled();
                return;
            }
            if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
                showSdFailure("フォルダの読み取りが許可されませんでした。");
                return;
            }
            confirmSdSource(new SdStart(data.getData(), picker.target(), picker.destination(),
                    picker.kinds(), picker.generation(), true, picker.previousRegistration()), picker.registrationOnly());
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
        if (checkSelfPermission(photoPermission()) != PackageManager.PERMISSION_GRANTED) {
            clearPendingSdStart();
            showSdPermissionStatus("写真すべてへのアクセスが必要です。再度許可するか、アプリの権限設定を確認してください。");
        } else if (checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            clearPendingSdStart();
            showSdPermissionStatus("原本の保存には写真の位置情報へのアクセスが必要です。再度操作するか、アプリの権限設定を確認してください。");
        } else {
            showSdPermissionStatus("写真の原本へのアクセスを許可しました。");
            if (pendingSdPermission) { resumeSd = true; if (quickResumed) resumeSdStart(); }
        }
        pendingSdPermission = false;
    }

    void renderSdSource(CameraQuickImportSources.SdState state) {
        sdSourceState = state;
        setText(sdStatus, state.message);
        setText(sdSettingsStatus, state.message);
        setText(sdStart, state == CameraQuickImportSources.SdState.AMBIGUOUS
                ? "取り込み元を選ぶ" : "SDカードから取り込む");
        sdChecking.setVisibility(state == CameraQuickImportSources.SdState.CHECKING ? View.VISIBLE : View.GONE);
    }

    private void cancelSdStatusLoad() {
        if (sdStatusCancellation != null) sdStatusCancellation.cancel();
        sdStatusCancellation = null;
    }

    private void refreshSdSource() {
        sdRefreshAfterRemount = remountBusy();
        if (sdRefreshAfterRemount) return;
        cancelSdStatusLoad();
        var cancellation = new android.os.CancellationSignal();
        sdStatusCancellation = cancellation;
        renderSdSource(CameraQuickImportSources.SdState.CHECKING);
        Runnable timeout = () -> {
            if (sdStatusCancellation != cancellation) return;
            cancelSdStatusLoad();
            renderSdSource(CameraQuickImportSources.SdState.FOLDER_UNAVAILABLE);
        };
        handler.postDelayed(timeout, 3000);
        try {
            SOURCE_STATUS_IO.execute(() -> {
                CameraQuickImportSources.SdSource source;
                try { source = CameraQuickImportSources.inspectSd(getApplicationContext(), cancellation); }
                catch (android.os.OperationCanceledException stopped) { return; }
                handler.post(() -> {
                    handler.removeCallbacks(timeout);
                    if (sdStatusCancellation != cancellation || isFinishing() || isDestroyed()) return;
                    sdStatusCancellation = null;
                    renderSdSource(CameraQuickImportSources.recheckSd(this, source).state());
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            handler.removeCallbacks(timeout);
            timeout.run();
        }
    }

    private void onSdMountChange(boolean detached) {
        if (detached) {
            boolean pending = sdConfirmation != null || pendingSdStart != null || sdPicker != null
                    || sourceCancellation != null || submittedSd != null;
            abandonSdSubmission();
            if (sdConfirmation != null) sdConfirmation.dismiss();
            if (sourceCancellation != null) {
                ++sourceGeneration;
                sourceCancellation.cancel();
                sourceCancellation = null;
                starting = false;
            }
            clearPendingSdStart();
            if (pending) showSdFailure("カードの接続が変わりました。取り込み元を確認してください。");
        }
        if (quickResumed && !starting && sdPicker == null && sdConfirmation == null) refreshSdSource();
    }

    private void showSdPermissionStatus(String message) {
        cancelSdStatusLoad();
        sdChecking.setVisibility(View.GONE);
        setText(sdStatus, message);
        setText(sdSettingsStatus, message);
        sdStatus.post(() -> {
            if (!isFinishing() && !isDestroyed())
                sdStatus.requestRectangleOnScreen(new android.graphics.Rect(0, 0, sdStatus.getWidth(), sdStatus.getHeight()), true);
        });
    }

    private void clearPendingSdStart() {
        pendingSdStart = null;
        pendingSdPermission = false;
        resumeSd = false;
    }

    private void resumeSdStart() {
        SdStart request = pendingSdStart;
        clearPendingSdStart();
        if (request != null) requestSdStart(request);
    }

    private void startSd() {
        if (!canStartSourceAction()) return;
        if (sdSourceState == CameraQuickImportSources.SdState.AMBIGUOUS) {
            String target = MountedCameraCard.targetWithGenericReader(this, PhotoStorage.selected(this));
            UsbDevice device = connected();
            String kinds = target == null ? "JPEG,RAW" : requestedUsbKinds(guardedSdTarget(device) != null ? device : null);
            pickSdTree(target, kinds, false);
        } else {
            discoverQuickSource(true);
        }
    }

    private String requestedUsbKinds(UsbDevice device) {
        if (device != null) try {
            if (getSystemService(UsbManager.class).hasPermission(device)) {
                String model = device.getProductName();
                String serial = device.getSerialNumber();
                Camera camera = HistoryDb.Companion.get(this).findCamera(
                        model == null ? "USB Camera" : model, serial == null ? "" : serial);
                if (camera != null) return MediaTypes.INSTANCE.kindsToString(camera.getDefaultKinds());
            }
        } catch (RuntimeException unavailable) { }
        return settings.getString("initial_kinds", "JPEG,RAW,VIDEO");
    }

    private boolean stillSelected(SdStart request) {
        return selectionContextMatches(request) && (request.picked()
                || CameraQuickImportSources.matchesRegisteredSelection(this, request.tree()));
    }

    private boolean selectionContextMatches(SdStart request) {
        return matchesSelection(request, PhotoStorage.selected(this), MountedCameraCard.detachGeneration(),
                request.picked() ? SdSourceRegistrations.latest(this) : null);
    }

    static boolean matchesSelection(SdStart request, String destination, long generation, String registration) {
        return request.generation() == generation && request.destination() != null
                && request.destination().equals(destination)
                && (!request.picked() || java.util.Objects.equals(request.previousRegistration(), registration));
    }

    static final String PICKED_FOLDER_UNAVAILABLE =
            "選んだフォルダを確認できません。接続を確認するか、フォルダを選び直してください。";

    record SdLocation(String sourceVolume, String summary) {}
    interface SdSourceProbe {
        SdLocation verify(SdStart request, android.os.CancellationSignal cancellation) throws IOException;
    }
    SdSourceProbe sdSourceProbe = this::verifySdSource;

    private SdLocation verifySdSource(SdStart request, android.os.CancellationSignal cancellation) throws IOException {
        String source;
        if (request.target() != null) {
            source = MountedCameraCard.requireDistinctSource(this, request.tree(), request.target(),
                    PhotoStorage.selected(this), !request.picked(), cancellation::isCanceled);
        } else {
            source = request.picked()
                    ? MountedCameraCard.requireRemovableSource(this, request.tree(), request.destination()) : null;
            if (MountedCameraCard.isSameMountedCard(this, request.tree(), request.destination())
                    || MountedCameraCard.isUnmountedRemovableTree(this, request.tree())
                    || !CameraDocumentSource.isReachableDirectory(getContentResolver(), request.tree(), cancellation::isCanceled)) {
                return null;
            }
        }
        return new SdLocation(source, "取り込み元　" + (source == null ? "選んだSDカード" : exactCardLabel(source))
                + "\n保存先　" + exactCardLabel(request.destination()));
    }

    void confirmSdSource(SdStart request, boolean registrationOnly) {
        if (sdConfirmation != null) return;
        verifySdSourceAsync(request, true, location -> {
            connectionFailure = null;
            renderSdSource(CameraQuickImportSources.SdState.READY);
            renderStatus();
            if (registrationOnly) confirmSdRegistration(request, location.summary());
            else confirmSdKinds(request, location.summary());
        });
    }

    private void verifySdSourceAsync(SdStart request, boolean allowReselect,
            java.util.function.Consumer<SdLocation> onVerified) {
        if (PROGRESS.getActive() || starting || migrating || sourceCancellation != null || submittedSd != null) return;
        if (!stillSelected(request)) {
            showSdFailure("カードの接続または保存先が変わりました。取り込み元を確認してください。");
            return;
        }
        if (!CameraDocumentSource.isImportTree(request.tree(), !request.picked())) {
            showSdFailure("取り込み元のSDカードのDCIMフォルダを選んでください。");
            return;
        }
        int generation = ++sourceGeneration;
        var cancellation = new android.os.CancellationSignal();
        sourceCancellation = cancellation;
        starting = true;
        cancelSdStatusLoad();
        renderSdSource(CameraQuickImportSources.SdState.CHECKING);
        renderStatus();
        Runnable timeout = () -> {
            if (sourceCancellation != cancellation) return;
            sourceGeneration++;
            cancellation.cancel();
            sourceCancellation = null;
            starting = false;
            showSdFailure("取り込み元のフォルダを確認できません。接続とフォルダを確認してください。");
        };
        handler.postDelayed(timeout, 3000);
        try {
            SOURCE_IO.execute(() -> {
                SdLocation location = null;
                String failure = request.picked() ? PICKED_FOLDER_UNAVAILABLE
                        : CameraQuickImportSources.SdState.FOLDER_UNAVAILABLE.message;
                boolean reselect = false;
                try {
                    location = sdSourceProbe.verify(request, cancellation);
                } catch (MountedCameraCard.Unavailable unavailable) {
                    failure = unavailable.getMessage();
                    reselect = allowReselect && unavailable.canReselect() && !request.picked();
                } catch (IOException | RuntimeException unavailable) {
                    // A failed metadata query cannot approve a source for registration or import.
                }
                SdLocation confirmed = location;
                String message = failure;
                boolean chooseAgain = reselect;
                handler.post(() -> {
                    if (generation != sourceGeneration || sourceCancellation != cancellation) return;
                    handler.removeCallbacks(timeout);
                    sourceCancellation = null;
                    starting = false;
                    if (isFinishing() || isDestroyed()) return;
                    if (!stillSelected(request)) {
                        showSdFailure("カードの接続または保存先が変わりました。取り込み元を確認してください。");
                    } else if (confirmed == null) {
                        showSdFailure(message);
                        if (chooseAgain) pickSdTree(request.target(), request.kinds(), false);
                    } else {
                        onVerified.accept(confirmed);
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            handler.removeCallbacks(timeout);
            timeout.run();
        }
    }

    private void showSdConfirmation(androidx.appcompat.app.AlertDialog dialog) {
        sdConfirmation = dialog;
        dialog.setOnCancelListener(ignored -> refreshSdSource());
        dialog.setOnDismissListener(ignored -> {
            if (sdConfirmation == dialog) {
                sdConfirmation = null;
                renderStatus();
            }
        });
        dialog.show();
    }

    private String sdAutoRegistrationNotice(boolean registering) {
        return registering && settings.getBoolean("sd_auto", false)
                ? "\n登録後は、カード接続時の自動取り込みに使います。" : "";
    }

    private void confirmSdRegistration(SdStart request, String locations) {
        showSdConfirmation(new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("取り込み元を登録")
                .setMessage(locations + sdAutoRegistrationNotice(true))
                .setNegativeButton("キャンセル", (dialog, which) -> refreshSdSource())
                .setPositiveButton("登録する", (dialog, which) -> {
                    dialog.dismiss();
                    confirmPickedSubmission(request, true);
                }).create());
    }

    private void confirmPickedSubmission(SdStart request, boolean registrationOnly) {
        if (PROGRESS.getActive()) {
            showSdFailure("別の取り込みが実行中です。処理が終わってからやり直してください。");
            return;
        }
        verifySdSourceAsync(request, false, ignored -> submitSd(request, registrationOnly));
    }

    private void abandonSdSubmission() {
        if (submittedSd != null) submittedSd.cancel();
        submittedSd = null;
    }

    private String exactCardLabel(String volume) {
        String label = PhotoStorage.label(this, volume);
        String id = volume.toUpperCase(Locale.ROOT);
        return label.toUpperCase(Locale.ROOT).contains(id) ? label : label + "（" + id + "）";
    }

    void confirmSdKinds(SdStart request, String locations) {
        var requested = MediaTypes.INSTANCE.kindsFromString(request.kinds() == null ? "JPEG,RAW" : request.kinds());
        boolean[] checked = {requested.contains(MediaTypes.Kind.JPEG), requested.contains(MediaTypes.Kind.RAW)};
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), 0, dp(24), 0);
        content.addView(text(locations + (requested.contains(MediaTypes.Kind.VIDEO)
                ? "\nこの方法では動画を取り込めません。" : "")
                + sdAutoRegistrationNotice(request.picked()), 14, GalleryUi.TEXT));
        var jpeg = new com.google.android.material.checkbox.MaterialCheckBox(this);
        jpeg.setText("JPEG");
        jpeg.setMinHeight(dp(48));
        jpeg.setChecked(checked[0]);
        content.addView(jpeg);
        var raw = new com.google.android.material.checkbox.MaterialCheckBox(this);
        raw.setText("RAW");
        raw.setMinHeight(dp(48));
        raw.setChecked(checked[1]);
        content.addView(raw);
        var dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(request.target() == null ? "SDカードから取り込む" : "別のSDカードへ取り込む").setView(content)
                .setNegativeButton("キャンセル", (ignored, which) -> refreshSdSource())
                .setPositiveButton("取り込む", null).create();
        dialog.setOnShowListener(unused -> {
            Button confirm = dialog.getButton(-1);
            Runnable refresh = () -> confirm.setEnabled(jpeg.isChecked() || raw.isChecked());
            jpeg.setOnCheckedChangeListener((button, enabled) -> refresh.run());
            raw.setOnCheckedChangeListener((button, enabled) -> refresh.run());
            refresh.run();
            confirm.setOnClickListener(button -> {
                var selected = EnumSet.noneOf(MediaTypes.Kind.class);
                if (jpeg.isChecked()) selected.add(MediaTypes.Kind.JPEG);
                if (raw.isChecked()) selected.add(MediaTypes.Kind.RAW);
                if (selected.isEmpty()) return;
                dialog.dismiss();
                requestSdStart(new SdStart(request.tree(), request.target(), request.destination(),
                        MediaTypes.INSTANCE.kindsToString(selected), request.generation(), request.picked(), request.previousRegistration()));
            });
        });
        showSdConfirmation(dialog);
    }

    private void showSdFailure(String message) {
        connectionFailure = message;
        showSdPermissionStatus(message);
        renderStatus();
    }

    private void requestSdStart(SdStart request) {
        if (PROGRESS.getActive() || starting || migrating) return;
        if (!selectionContextMatches(request)) {
            clearPendingSdStart();
            showSdFailure("カードの接続または保存先が変わりました。取り込み元を確認してください。");
            return;
        }
        if (!request.picked() && !CameraQuickImportSources.matchesRegisteredSelection(this, request.tree())) {
            clearPendingSdStart();
            showSdFailure("取り込むSDカードのフォルダを選んでください。");
            pickSdTree(request.target(), request.kinds(), false);
            return;
        }
        pendingSdStart = request;
        pendingSdPermission = true;
        if (!ensureSdPermissions()) return;
        clearPendingSdStart();
        if (request.picked()) confirmPickedSubmission(request, false);
        else submitSd(request, false);
    }

    void submitSd(SdStart request, boolean registrationOnly) {
        if (submittedSd != null || isFinishing() || isDestroyed()) return;
        if (!stillSelected(request)) {
            showSdFailure("カードの接続または保存先が変わりました。取り込み元を確認してください。");
            return;
        }
        var confirmation = new CameraDifferentialService.SdConfirmation((reply, failure) -> {
            if (submittedSd != reply || isFinishing() || isDestroyed()) return;
            submittedSd = null;
            if (failure != null) showSdFailure(failure);
            else {
                connectionFailure = null;
                // Acceptance updates the registered source. The progress panel owns operation status.
                refreshSdSource();
                renderStatus();
            }
        });
        submittedSd = confirmation;
        renderSdSource(CameraQuickImportSources.SdState.CHECKING);
        renderStatus();
        try {
            sdSubmission.submit(request, registrationOnly, confirmation);
        } catch (RuntimeException failure) {
            abandonSdSubmission();
            showSdFailure("取り込み元の確認を開始できませんでした。接続を確認してください。");
        }
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
