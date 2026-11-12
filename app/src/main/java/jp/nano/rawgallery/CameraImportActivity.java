package jp.nano.rawgallery;

import android.Manifest;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import android.content.Intent;
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
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import jp.nano.rawgallery.camera.Camera;
import jp.nano.rawgallery.camera.CameraImportGate;
import jp.nano.rawgallery.camera.CameraUsb;
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
    private static final int PICK_DB = 401;
    private static final java.util.concurrent.Executor IO = Executors.newSingleThreadExecutor();
    private static final ImportProgress PROGRESS = ImportProgress.INSTANCE;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView connection, result, migrationResult;
    private ProgressBar progress;
    private Button run, stop, migrate, notifyButton;
    private LinearLayout cameras;
    private MaterialSwitch auto;
    private Closeable permission;
    private boolean migrating, starting, wasActive;
    private String connectionFailure;
    private boolean quickImportFlow;
    private String quickRequestId;
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
            quickImportFlow = state.getBoolean("quick_import_flow");
            quickRequestId = state.getString("quick_import_request");
        }
        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(GalleryUi.BG);
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            var bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(GalleryUi.BG);
        root.setPadding(dp(16), dp(20), dp(16), dp(16));
        scroll.addView(root);
        frame.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(frame);
        root.addView(text("設定", 13, GalleryUi.MUTED));
        TextView title = text("カメラから取り込む", 24, GalleryUi.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setAccessibilityHeading(true);
        root.addView(title);
        LinearLayout attached = card(root, "カメラ");
        TextView cameraHeading = (TextView) attached.getChildAt(0);
        cameraHeading.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_camera_usb, 0, 0, 0);
        cameraHeading.setCompoundDrawablePadding(dp(10));
        connection = text("", 14, GalleryUi.TEXT);
        attached.addView(connection);
        LinearLayout importing = card(root, "取り込み");
        result = status(importing);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setContentDescription("取り込みの進捗");
        importing.addView(progress);
        run = button(importing, "今すぐ取り込む", this::startFromScreen);
        stop = button(importing, "中止", () -> {
            PROGRESS.requestStop();
            renderStatus();
        });
        auto = new MaterialSwitch(this);
        auto.setText("つないだら自動で取り込む");
        auto.setMinHeight(dp(48));
        auto.setChecked(settings.getBoolean("auto", true));
        auto.setOnCheckedChangeListener((v, checked) -> settings.edit().putBoolean("auto", checked).apply());
        importing.addView(auto);
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifyButton = button(importing, "取り込みの通知を許可する",
                    () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 402));
        }
        cameras = card(root, "取り込むもの");
        LinearLayout history = card(root, "CameraSyncの記録");
        history.addView(text("取り込み済みの記録を引き継ぐと、同じファイルを再び取り込まずに済みます。CameraSyncと保存済みの写真は残ります。",
                14, GalleryUi.MUTED));
        migrationResult = status(history);
        migrate = button(history, "記録のファイルを選ぶ", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*");
            startActivityForResult(intent, PICK_DB);
        });
        ((MaterialButton) migrate).setStrokeWidth(dp(1));
        ((MaterialButton) migrate).setStrokeColor(android.content.res.ColorStateList.valueOf(GalleryUi.MUTED));
        var migrationButtonLayout = (LinearLayout.LayoutParams) migrate.getLayoutParams();
        migrationButtonLayout.topMargin = dp(8);
        migrate.setLayoutParams(migrationButtonLayout);
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setBackgroundColor(GalleryUi.BG);
        dock.setPadding(dp(16), 0, dp(16), 0);
        var back = button(dock, "‹\n戻る", this::finish);
        back.setContentDescription("戻る");

        back.setTextSize(13);
        back.setMinHeight(dp(64));
        frame.addView(dock);
        renderStatus();
    }

    @Override protected void onStart() {
        super.onStart();
        handler.post(refresh);
        loadCameras();
    }

    @Override protected void onStop() {
        handler.removeCallbacks(refresh);
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
        state.putBoolean("quick_import_flow", quickImportFlow);
        state.putString("quick_import_request", quickRequestId);
    }

    @Override protected void onPostResume() {
        super.onPostResume();
        if (getIntent().getBooleanExtra(START_IMPORT_KEEP_ORIGINALS, false)) {
            getIntent().removeExtra(START_IMPORT_KEEP_ORIGINALS);
            quickImportFlow = true;
            startFromScreen(true);
        }
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

    private void renderStatus() {
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
        if (wasActive && !active) loadCameras();
        wasActive = active;
        if (active) starting = false;
        setText(connection, device == null ? "USBケーブルでカメラをつないでください"
                : (device.getProductName() == null ? "カメラ" : device.getProductName())
                  + (granted ? (manualOnly ? " · 手動取り込み" : " · 接続済み") : " · USBの使用許可が必要です"));
        int total = PROGRESS.getTotal();
        setText(result, active
                ? (total > 0 ? PROGRESS.getDone() + " / " + total + " 件"
                    : java.util.Objects.toString(PROGRESS.getStatus(), "カメラを確認しています"))
                : java.util.Objects.toString(connectionFailure,
                    java.util.Objects.toString(PROGRESS.getLastMessage(), "まだ取り込んでいません")));
        progress.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) {
            progress.setIndeterminate(total <= 0);
            if (total > 0) {
                progress.setMax(total);
                progress.setProgress(PROGRESS.getDone());
            }
        }
        setText(run, device != null && !granted ? "USBの使用を許可する"
                : (settings.getBoolean("configured", false) ? "今すぐ取り込む" : "取り込みの設定をする"));
        run.setEnabled(device != null && !active && !starting && permission == null && !migrating);
        boolean configured = settings.getBoolean("configured", false);
        run.setVisibility(active || (!configured && device == null) ? View.GONE : View.VISIBLE);
        auto.setVisibility(configured ? View.VISIBLE : View.GONE);
        cameras.setVisibility(configured ? View.VISIBLE : View.GONE);
        if (notifyButton != null) notifyButton.setVisibility(configured &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ? View.VISIBLE : View.GONE);
        stop.setVisibility(active ? View.VISIBLE : View.GONE);
        stop.setEnabled(active && !PROGRESS.getStopRequested());
        setText(stop, PROGRESS.getStopRequested() ? "中止しています" : "中止");
        auto.setEnabled(!active && !migrating && !manualOnly);
        migrate.setEnabled(!active && !starting && !migrating);
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
                permission = CameraUsb.request(this, device, granted -> {
                    permission = null;
                    renderStatus();
                    if (granted) prepareImport(device, keepOriginals);
                    else { connectionFailure = "USBの使用が許可されませんでした"; renderStatus(); }
                });
            } catch (RuntimeException failure) {
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

    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
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
        Button button = new MaterialButton(this);
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
