package jp.nano.rawgallery;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import jp.nano.rawgallery.camera.Ep7WifiClient;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground-only, explicit single-file experiment; does not alter the production import history. */
public final class Ep7WifiActivity extends AppCompatActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AtomicBoolean cancelled = new AtomicBoolean();
    private TextView status;
    private MaterialButton list, download, stop, wifi, clear;
    private Spinner files;
    private LinearProgressIndicator progress;
    private List<Ep7WifiClient.Entry> entries = Collections.emptyList();
    private Network listedNetwork;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(GalleryUi.BG);
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets;
        });
        LinearLayout toolbar = LibraryUi.toolbar(this, "E-P7 Wi-Fi試験");
        toolbar.addView(LibraryUi.icon(this, R.drawable.ic_back, "戻る", this::finish), 0);
        frame.addView(toolbar);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(24));
        scroll.addView(body); frame.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        text(body, "カメラのWi-Fiに接続してから一覧を取得してください。実験用の機能です。E-P7での動作と速度は未確認です。", 16);
        text(body, "JPEG・ORFを1枚ずつ試験転送します。アプリ専用領域に保存し、ライブラリと取り込み履歴には追加しません。カメラの写真は削除しません。", 14);
        wifi = button(body, "Wi-Fi設定を開く", () -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        list = button(body, "画像一覧を取得", this::load);
        MaterialUi.filled(list);
        progress = new LinearProgressIndicator(this);
        progress.setIndeterminate(true);
        progress.setContentDescription("Wi-Fi転送試験の処理中");
        body.addView(progress, new LinearLayout.LayoutParams(-1, dp(8)));
        status = text(body, "待機中", 16);
        status.setTag("ep7-status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        files = new Spinner(this);
        files.setTag("ep7-files");
        files.setContentDescription("試験転送する写真");
        files.setMinimumHeight(dp(48));
        body.addView(files, new LinearLayout.LayoutParams(-1, -2));
        download = button(body, "選んだ1枚を試験転送", this::transfer);
        stop = button(body, "中止", () -> { cancelled.set(true); status.setText("中止しています…"); stop.setEnabled(false); });
        clear = button(body, "試験用コピーを削除", this::clearCopies);
        text(body, "保存上限は合計512 MiBです。速度はHTTP転送時間から計算し、保存検証を含む全体時間と分けて表示します。", 14);
        setContentView(frame); render();
    }
    private File storage() { return new File(getFilesDir(), "ep7-wifi-poc"); }
    private void render() {
        list.setEnabled(!busy); wifi.setEnabled(!busy); clear.setEnabled(!busy);
        files.setEnabled(!busy && !entries.isEmpty());
        download.setEnabled(!busy && !entries.isEmpty());
        stop.setEnabled(busy && !cancelled.get());
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }
    private Network wifiNetwork() throws IOException {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        List<Network> candidates = new ArrayList<>();
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities cap = cm.getNetworkCapabilities(network);
            if (cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) candidates.add(network);
        }
        if (candidates.size() != 1) throw new IOException("A single Wi-Fi network is required");
        return candidates.get(0);
    }
    private Ep7WifiClient client(Network network, AtomicBoolean cancel) {
        return new Ep7WifiClient(url -> {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            NetworkCapabilities cap = cm.getNetworkCapabilities(network);
            if (cap == null || !cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) throw new IOException("Wi-Fi disconnected");
            return (HttpURLConnection) network.openConnection(url, Proxy.NO_PROXY);
        }, cancel::get);
    }
    private interface Operation { String run(AtomicBoolean cancel) throws Exception; }
    private void execute(String message, Operation operation) {
        if (busy) return;
        busy = true; cancelled = new AtomicBoolean();
        AtomicBoolean cancel = cancelled;
        status.setText(message); render();
        io.execute(() -> {
            String result;
            try { result = operation.run(cancel); }
            catch (Exception error) {
                result = cancel.get() ? "中止しました。未完了の転送は登録していません。" :
                        "試験に失敗しました。カメラのWi-Fi接続と空き容量を確認して再試行してください。";
            }
            final String displayed = result;
            runOnUiThread(() -> { if (isDestroyed()) return; busy = false; status.setText(displayed); render(); });
        });
    }
    private void load() {
        entries = Collections.emptyList(); listedNetwork = null;
        files.setAdapter(null);
        execute("画像一覧を取得しています…", cancel -> {
            Network network = wifiNetwork();
            var result = client(network, cancel).list();
            if (cancel.get()) throw new IOException("Cancelled");
            runOnUiThread(() -> {
                if (isDestroyed() || cancel.get()) return;
                entries = result.photos; listedNetwork = network;
                List<String> labels = new ArrayList<>();
                for (var e : entries) labels.add(e.name() + " · " + String.format(Locale.JAPAN, "%.1f MiB", e.bytes / 1048576.0));
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
                files.setAdapter(adapter);
            });
            return String.format(Locale.JAPAN, "%d枚のJPEG・ORF · 一覧取得 %.2f秒", result.photos.size(), result.seconds);
        });
    }
    private void transfer() {
        int selected = files.getSelectedItemPosition();
        if (selected < 0 || selected >= entries.size() || listedNetwork == null || busy) return;
        var entry = entries.get(selected); Network network = listedNetwork;
        execute("選んだ写真を転送しています…", cancel -> {
            long used = 0; File[] copies = storage().listFiles();
            if (copies != null) for (File copy : copies) used += copy.length();
            if (used + entry.bytes > Ep7WifiClient.MAX_FILE_BYTES) return "保存上限です。試験用コピーを削除してから再試行してください。";
            var result = client(network, cancel).download(entry, storage());
            return String.format(Locale.JAPAN,
                    "保存と読戻し照合が完了しました\n%.2f MiB · 転送 %.2f秒 · %.2f MiB/s\n保存検証を含む全体 %.2f秒\n受信・保存SHA-256\n%s",
                    result.bytes / 1048576.0, result.transferSeconds, result.mibPerSecond(), result.totalSeconds, result.sha256);
        });
    }
    private void clearCopies() {
        execute("試験用コピーを削除しています…", cancel -> {
            File[] copies = storage().listFiles(); int count = 0;
            if (copies != null) for (File copy : copies) {
                if (cancel.get()) throw new IOException("Cancelled");
                if (!copy.delete()) throw new IOException("Cannot remove test copy");
                count++;
            }
            return count + "件の試験用コピーを削除しました。カメラとライブラリの写真は変更していません。";
        });
    }
    @Override protected void onStop() { cancelled.set(true); super.onStop(); }
    @Override protected void onDestroy() { cancelled.set(true); io.shutdownNow(); super.onDestroy(); }
    private int dp(int value) { return GalleryUi.dp(this, value); }
    private TextView text(LinearLayout root, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(GalleryUi.TEXT);
        view.setPadding(0, dp(8), 0, dp(8)); root.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private MaterialButton button(LinearLayout root, String label, Runnable action) {
        MaterialButton button = new MaterialButton(this); button.setText(label); button.setAllCaps(false);
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setOnClickListener(v -> action.run());
        root.addView(button, new LinearLayout.LayoutParams(-1, -2)); return button;
    }
}
