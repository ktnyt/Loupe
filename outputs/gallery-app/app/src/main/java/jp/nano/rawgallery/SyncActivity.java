package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;

public final class SyncActivity extends Activity {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private TextView info, progress;
  private boolean refreshing;
  private final java.util.List<Button> footerButtons = new java.util.ArrayList<>();
  private final Runnable refreshProgress =
      new Runnable() {
        public void run() {
          if (progress != null)
            progress.setText(
                getSharedPreferences("settings", 0).getString("syncStatus", "まだ同期していません。"));
          main.postDelayed(this, 3000);
        }
      };

  public void onCreate(Bundle state) {
    super.onCreate(state);
    screen();
  }

  private void button(LinearLayout root, String label, Runnable action) {
    Button b = GalleryUi.button(this, label, action);
    if (label.equals("同期・整理の設定") || label.equals("その他の操作") || label.equals("戻る")) {
      footerButtons.add(b);
    } else root.addView(b);
  }

  private TextView text(LinearLayout root, String value, int size) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(0xfff0ede7);
    t.setPadding(16, 16, 16, 16);
    root.addView(t);
    return t;
  }

  private void screen() {
    ScrollView scroll = new ScrollView(this);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(0xff141719);
    scroll.addView(root);
    LinearLayout screen = new LinearLayout(this);
    screen.setOrientation(LinearLayout.VERTICAL);
    screen.setBackgroundColor(GalleryUi.BG);
    screen.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars());
          v.setPadding(i.left + 12, i.top + 12, i.right + 12, i.bottom);
          return insets;
        });
    screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(screen);
    text(root, "同期と端末容量", 24);
    text(
        root,
        "フォルダ選択は不要です。すべてのJPEGと、RAW内の機種情報からGR IV（Monochrome含む）、OM-1 Mark"
            + " II、E-P7、LeitzphoneのRAWを自動で見つけます。",
        14);
    info = text(root, "写真の機種と撮影日を確認しています…", 14);
    progress = text(root, "", 14);
    text(
        root,
        "モバイル通信でも同期します。実行時刻はAndroidの省電力制御などにより遅れる場合があります。\n"
            + "自動整理には「すべてのファイルへのアクセス」が必要です。アプリが整理するのは対象機種のRAWとすべてのJPEGで、復元照合とプレビュー保存を終えた原本に限ります。",
        13);
    if (!android.os.Environment.isExternalStorageManager())
      button(
          root,
          "自動管理に必要なアクセスを許可",
          () -> {
            try {
              startActivity(
                  new Intent(
                      Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                      Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
              startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
          });
    if (checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
        != android.content.pm.PackageManager.PERMISSION_GRANTED)
      button(
          root,
          "原本の位置情報を含めた読み取りを許可",
          () ->
              requestPermissions(
                  new String[] {android.Manifest.permission.ACCESS_MEDIA_LOCATION}, 41));
    text(root, "撮影時の位置情報も含め、原本を変更せず保存するための許可です。端末の現在位置は取得しません。", 13);
    button(
        root,
        "原本の保持期間を変更",
        () ->
            new GalleryUi.Sheet(this)
                .setTitle("基準日から残す期間")
                .setItems(
                    new String[] {"7日間", "30日間", "90日間"},
                    (d, w) -> {
                      getSharedPreferences("settings", 0)
                          .edit()
                          .putInt("retentionDays", new int[] {7, 30, 90}[w])
                          .putBoolean("cleanupEnabled", false)
                          .apply();
                      refresh();
                    })
                .setNegativeButton("閉じる", null)
                .show());
    button(
        root,
        "同期・整理の設定",
        () ->
            new GalleryUi.Sheet(this)
                .setTitle("同期・整理の設定")
                .setItems(
                    new String[] {"自動同期を開始", "自動整理も有効にする", "自動整理だけ停止", "同期と整理を停止"},
                    (d, which) -> {
                      if (which == 0) confirm(false);
                      else if (which == 1) confirm(true);
                      else {
                        if (which == 2)
                          getSharedPreferences("settings", 0)
                              .edit()
                              .putBoolean("cleanupEnabled", false)
                              .apply();
                        else stop();
                        refresh();
                      }
                    })
                .setNegativeButton("閉じる", null)
                .show());
    button(
        root,
        "その他の操作",
        () ->
            new GalleryUi.Sheet(this)
                .setItems(
                    new String[] {"今すぐ同期を要求", "状態を更新"},
                    (d, which) -> {
                      if (which == 0) SyncJob.now(this);
                      refresh();
                    })
                .setNegativeButton("閉じる", null)
                .show());
    text(root, "アプリを削除すると端末のプレビューと管理記録も消えます。クラウドの原本はAmazon Photosに残ります。", 13);
    button(root, "戻る", this::finish);
    Button settings = footerButtons.get(0),
        other = footerButtons.get(1),
        back = footerButtons.get(2);
    settings.setText("同期・整理");
    other.setText("その他");
    screen.addView(GalleryUi.dock(this, back, GalleryUi.primary(settings), other));
  }

  private void stop() {
    getSharedPreferences("settings", 0)
        .edit()
        .putBoolean("syncEnabled", false)
        .putBoolean("cleanupEnabled", false)
        .apply();
    SyncJob.schedule(this);
  }

  private void refresh() {
    if (refreshing) return;
    refreshing = true;
    worker.execute(
        () -> {
          String result;
          try {
            result = SyncEngine.summary(this);
          } catch (Exception e) {
            result = "写真へのアクセス許可を確認してください。";
          }
          final String message = result;
          runOnUiThread(
              () -> {
                refreshing = false;
                if (isDestroyed()) return;
                SharedPreferences s = getSharedPreferences("settings", 0);
                info.setText(
                    "自動同期："
                        + (s.getBoolean("syncEnabled", false) ? "有効" : "停止")
                        + " ／ 自動整理："
                        + (s.getBoolean("cleanupEnabled", false) ? "有効" : "停止")
                        + "\n自動管理の権限："
                        + (Environment.isExternalStorageManager() ? "許可済み" : "未許可")
                        + "\n"
                        + message);
              });
        });
  }

  private void confirm(boolean cleanup) {
    if (!Environment.isExternalStorageManager()
        && checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      new GalleryUi.Sheet(this)
          .setMessage("先に原本の位置情報を含めた読み取りを許可してください。")
          .setPositiveButton("閉じる", null)
          .show();
      return;
    }
    if (cleanup && !Environment.isExternalStorageManager()) {
      new GalleryUi.Sheet(this)
          .setMessage("先に自動管理に必要なアクセスを許可してください。")
          .setPositiveButton("閉じる", null)
          .show();
      return;
    }
    worker.execute(
        () -> {
          try {
            String summary = SyncEngine.summary(this);
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  if (cleanup
                      && !getSharedPreferences("settings", 0).getBoolean("syncEnabled", false)) {
                    new GalleryUi.Sheet(this)
                        .setMessage("先に自動同期を開始してください。")
                        .setPositiveButton("閉じる", null)
                        .show();
                    return;
                  }
                  new GalleryUi.Sheet(this)
                      .setTitle(cleanup ? "期間を過ぎた原本を自動で整理しますか？" : "対象機種のRAWとすべてのJPEGを自動同期しますか？")
                      .setMessage(
                          summary
                              + "\n"
                              + (cleanup
                                  ? "今後追加される対象機種のRAWとすべてのJPEGも含め、復元照合済みの原本を端末から削除し、プレビューを残します。クラウドの原本は削除しません。"
                                  : "現在と今後追加される対象機種のRAWとすべてのJPEGを、モバイル通信を含む接続で保存・再取得して検証します。"))
                      .setPositiveButton(
                          cleanup ? "自動整理を有効にする" : "自動同期を開始",
                          (d, w) -> {
                            getSharedPreferences("settings", 0)
                                .edit()
                                .putBoolean(cleanup ? "cleanupEnabled" : "syncEnabled", true)
                                .apply();
                            try {
                              SyncJob.schedule(this);
                              SyncJob.now(this);
                            } catch (Exception e) {
                              stop();
                              SyncEngine.status(this, "定期処理を登録できませんでした。");
                            }
                            refresh();
                          })
                      .setNegativeButton("キャンセル", null)
                      .show();
                });
          } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "対象を確認できませんでした", Toast.LENGTH_LONG).show());
          }
        });
  }

  protected void onResume() {
    super.onResume();
    main.post(refreshProgress);
    refresh();
  }

  protected void onPause() {
    main.removeCallbacks(refreshProgress);
    super.onPause();
  }

  protected void onDestroy() {
    worker.shutdownNow();
    super.onDestroy();
  }
}
