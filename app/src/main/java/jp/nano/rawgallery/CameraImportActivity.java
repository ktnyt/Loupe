package jp.nano.rawgallery;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import android.widget.Switch;
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

public final class CameraImportActivity extends Activity {
    private static final int PICK_DB = 401;
    private static final java.util.concurrent.Executor IO = Executors.newSingleThreadExecutor();
    private static final ImportProgress PROGRESS = ImportProgress.INSTANCE;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView connection, result, migrationResult;
    private ProgressBar progress;
    private Button run, stop, migrate;
    private LinearLayout cameras;
    private Switch auto;
    private Closeable permission;
    private boolean migrating, starting, wasActive;
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
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff111113);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(root);
        setContentView(scroll);
        root.addView(text("設定", 13, 0xffb5b5be));
        TextView title = text("カメラから取り込む", 24, Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setAccessibilityHeading(true);
        root.addView(title);
        LinearLayout attached = card(root, "接続");
        connection = text("", 14, Color.WHITE);
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
        auto = new Switch(this);
        auto.setText("つないだら自動で取り込む");
        auto.setMinHeight(dp(48));
        auto.setChecked(settings.getBoolean("auto", true));
        auto.setOnCheckedChangeListener((v, checked) -> settings.edit().putBoolean("auto", checked).apply());
        importing.addView(auto);
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Button notify = button(importing, "取り込みの通知を許可する",
                    () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 402));
            notify.setVisibility(View.VISIBLE);
        }
        cameras = card(root, "取り込むもの");
        LinearLayout history = card(root, "CameraSyncの記録");
        history.addView(text("取り込み済みの記録を引き継ぎます。CameraSyncと保存済みの写真はそのまま残ります。",
                14, 0xffb5b5be));
        migrationResult = status(history);
        migrate = button(history, "記録のファイルを選ぶ", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*");
            startActivityForResult(intent, PICK_DB);
        });
        button(root, "戻る", this::finish);
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

    private UsbDevice connected() {
        return getSystemService(UsbManager.class).getDeviceList().values().stream()
                .filter(CameraUsb::supported)
                .sorted(java.util.Comparator.comparing(UsbDevice::getDeviceName))
                .findFirst().orElse(null);
    }

    private void renderStatus() {
        UsbDevice device = connected();
        boolean granted = device != null && getSystemService(UsbManager.class).hasPermission(device);
        boolean active = PROGRESS.getActive();
        if (wasActive && !active) loadCameras();
        wasActive = active;
        if (active) starting = false;
        setText(connection, device == null ? "USBケーブルでカメラをつないでください"
                : (device.getProductName() == null ? "カメラ" : device.getProductName())
                  + (granted ? " · 接続済み" : " · USBの使用許可が必要です"));
        int total = PROGRESS.getTotal();
        setText(result, active
                ? (total > 0 ? PROGRESS.getDone() + " / " + total + " 件"
                    : java.util.Objects.toString(PROGRESS.getStatus(), "カメラを確認しています"))
                : java.util.Objects.toString(PROGRESS.getLastMessage(), "まだ取り込んでいません"));
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
        run.setVisibility(active ? View.GONE : View.VISIBLE);
        stop.setVisibility(active ? View.VISIBLE : View.GONE);
        stop.setEnabled(active && !PROGRESS.getStopRequested());
        setText(stop, PROGRESS.getStopRequested() ? "中止しています" : "中止");
        auto.setEnabled(!active && !migrating);
        migrate.setEnabled(!active && !starting && !migrating);
    }

    private void startFromScreen() {
        UsbDevice device = connected();
        if (device == null) return;
        if (getSystemService(UsbManager.class).hasPermission(device)) prepareImport(device);
        else {
            try {
                permission = CameraUsb.request(this, device, granted -> {
                    permission = null;
                    renderStatus();
                    if (granted) prepareImport(device);
                    else setText(result, "USBの使用が許可されませんでした");
                });
            } catch (RuntimeException failure) {
                setText(result, "USBの使用許可を確認できませんでした");
            }
        }
    }

    private void prepareImport(UsbDevice device) {
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
        starting = true;
        try {
            ImportService.start(this, device, null, delete);
        } catch (RuntimeException failure) {
            starting = false;
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
                if (snapshot.isEmpty()) cameras.addView(text("カメラごとの設定は、最初の取り込み後に変更できます", 14, 0xffb5b5be));
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
        var deletion = new Switch(this);
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
        bg.setColor(0xff1e1e23);
        bg.setCornerRadius(dp(20));
        card.setBackground(bg);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        var lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        parent.addView(card, lp);
        var header = text(name, 16, Color.WHITE);
        header.setTypeface(null, Typeface.BOLD);
        header.setAccessibilityHeading(true);
        card.addView(header);
        return card;
    }

    private TextView status(LinearLayout parent) {
        TextView status = text("", 14, 0xffb5b5be);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        parent.addView(status);
        return status;
    }

    private Button button(LinearLayout parent, String label, Runnable click) {
        Button button = new Button(this);
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
