package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;

public final class SyncActivity extends DiagnosticActivity {
  private final ExecutorService worker = new DiagnosticExecutor("SyncActivity.worker", 1);
  private final Handler main = new Handler(Looper.getMainLooper());
  private TextView info, retention, progressTime;
  private StatusView progress, collection, permissionState;
  private Switch syncState, cleanupState;
  private boolean refreshing;
  private final java.util.List<Button> settingsButtons = new java.util.ArrayList<>();
  private final java.util.List<Button> footerButtons = new java.util.ArrayList<>();
  private final Runnable refreshProgress =
      new Runnable() {
        public void run() {
          if(progress!=null){JobState state=SyncEngine.snapshot(SyncActivity.this);
            String description=getSharedPreferences("settings",0).getString("syncStatus","まだ同期していません");
            if(state.kind==JobState.Kind.UNKNOWN)description="現在の処理状態を確認できません。"+description;
            progress.show(StatusView.symbol(state.kind),"",description,null,"");
            progressTime.setText(state.updatedAt>0?android.text.format.DateFormat.format("MM/dd HH:mm",state.updatedAt).toString():"—");
          }
          main.postDelayed(this, 3000);
        }
      };

  public void onCreate(Bundle state) {
    super.onCreate(state);
    screen();
  }

  private void button(LinearLayout root, String label, Runnable action) {
    Button b = new SyncSettingsUi.ActionButton(this, label, action);
    if (label.equals("同期・端末からの削除の設定") || label.equals("その他の操作")) {
      footerButtons.add(b);
    } else settingsButtons.add(b);
  }

  private void screen() {
    ScrollView scroll = new ScrollView(this);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(GalleryUi.BG);
    scroll.addView(root);
    LinearLayout screen = new LinearLayout(this);
    screen.setOrientation(LinearLayout.VERTICAL);
    screen.setBackgroundColor(GalleryUi.BG);
    screen.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars());
          v.setPadding(i.left, i.top, i.right, i.bottom);
          return insets;
        });
    screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(screen);
    info = new TextView(this);progressTime = new TextView(this);
    collection=new StatusView(this);permissionState=new StatusView(this);progress=new StatusView(this);
    syncState=new SyncSettingsUi.ReadOnlySwitch(this);cleanupState=new SyncSettingsUi.ReadOnlySwitch(this);
    if (!android.os.Environment.isExternalStorageManager())
      button(
          root,
          "写真の自動削除に必要なアクセスを許可",
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
          "元のファイルの位置情報を含めた読み取りを許可",
          () ->
              requestPermissions(
                  new String[] {android.Manifest.permission.ACCESS_MEDIA_LOCATION}, 41));
    button(
        root,
        "端末に写真を残す期間",
        () ->
            new GalleryUi.Sheet(this)
                .setTitle("端末に写真を残す期間")
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
        "同期・端末からの削除の設定",
        () ->
            new GalleryUi.Sheet(this)
                .setTitle("同期・端末からの削除の設定")
                .setItems(
                    new String[] {"自動同期を開始", "端末の写真の自動削除も有効にする", "端末の写真の自動削除だけ停止", "同期と端末からの削除を停止"},
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
                    new String[] {"同期を予約する", "状態を更新"},
                    (d, which) -> {
                      if (which == 0) SyncJob.now(this);
                      refresh();
                    })
                .setNegativeButton("閉じる", null)
                .show());
    // Keep all existing action callbacks; only their presentation and placement changes.
    Button settings = footerButtons.get(0), other = footerButtons.get(1);
    Button keep = settingsButtons.get(settingsButtons.size()-1);
    int permissionIndex=0;
    Button allFiles = !Environment.isExternalStorageManager()?settingsButtons.get(permissionIndex++):null;
    Button location = checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
        != android.content.pm.PackageManager.PERMISSION_GRANTED?settingsButtons.get(permissionIndex):null;
    retention=SyncSettingsUi.populate(this,root,screen,info,collection,permissionState,progress,progressTime,
        syncState,cleanupState,allFiles,location,keep,settings,other);

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
    refreshing = true;collection.begin("写真の機種と撮影日を確認しています");
    worker.execute(
        () -> {
          String result;boolean success=true;
          try {
            result = SyncEngine.summary(this);
          } catch (Exception e) {
            success=false;result="";
          }
          final String message = result;final boolean collected=success;
          runOnUiThread(
              () -> {
                refreshing = false;
                if (isDestroyed()) return;
                SharedPreferences s = getSharedPreferences("settings", 0);
                syncState.setChecked(s.getBoolean("syncEnabled",false));cleanupState.setChecked(s.getBoolean("cleanupEnabled",false));
                retention.setText(s.getInt("retentionDays",30)+"日");
                permissionState.show(Environment.isExternalStorageManager()?StatusView.Symbol.COMPLETE:StatusView.Symbol.PERMISSION,Environment.isExternalStorageManager()?"自動管理の権限は許可済み":"自動管理の権限は未許可。必要なアクセスを許可する操作を選んでください");
                info.setText(collected?message:"—");
                if(collected)collection.finish();else collection.show(StatusView.Symbol.FAILED,"","写真の情報を取得できませんでした。アクセス許可を確認してください",this::refresh,"再試行");
              });
        });
  }

  private StatusView requirement(StatusView.Symbol kind,String message){StatusView view=new StatusView(this);view.show(kind,message);return view;}
  private void confirm(boolean cleanup) {
    if (!Environment.isExternalStorageManager()
        && checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      new GalleryUi.Sheet(this)
          .setTitle("元のファイルの位置情報を含めた読み取り")
          .setView(requirement(StatusView.Symbol.PERMISSION,"先に元のファイルの位置情報を含めた読み取りを許可してください"))
          .setPositiveButton("許可",(d,w)->requestPermissions(new String[]{android.Manifest.permission.ACCESS_MEDIA_LOCATION},41))
          .setNegativeButton("キャンセル",null)
          .show();
      return;
    }
    if (cleanup && !Environment.isExternalStorageManager()) {
      new GalleryUi.Sheet(this)
          .setTitle("写真の自動削除に必要なアクセス")
          .setView(requirement(StatusView.Symbol.PERMISSION,"先に写真の自動削除に必要なアクセスを許可してください"))
          .setPositiveButton("許可",(d,w)->{try{startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName())));}catch(ActivityNotFoundException e){startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));}})
          .setNegativeButton("キャンセル",null)
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
                        .setTitle("自動同期")
                        .setView(requirement(StatusView.Symbol.PAUSED,"先に自動同期を開始してください"))
                        .setPositiveButton("自動同期を開始",(d,w)->confirm(false))
                        .setNegativeButton("キャンセル",null)
                        .show();
                    return;
                  }
                  new GalleryUi.Sheet(this)
                      .setTitle(cleanup ? "保存期間を過ぎた写真を端末から自動で削除しますか？" : "対象機種のRAWとすべてのJPEGを自動同期しますか？")
                      .setMessage(
                          summary
                              + "\n"
                              + (cleanup
                                  ? "今後追加される対象機種のRAWとすべてのJPEGも含め、クラウドから同じ内容で戻せることを確認した元のファイルを端末から削除し、プレビューを残します。クラウドの元のファイルは削除しません。"
                                  : "現在と今後追加される対象機種のRAWとすべてのJPEGを、モバイル通信を含む接続で保存・再取得して検証します。"))
                      .setPositiveButton(
                          cleanup ? "端末の写真の自動削除を有効にする" : "自動同期を開始",
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
                              SyncEngine.status(this, JobState.Kind.FAILED, "定期処理を登録できませんでした。");
                            }
                            refresh();
                          })
                      .setNegativeButton("キャンセル", null)
                      .show();
                });
          } catch (Exception e) {
            runOnUiThread(() -> {if(!isDestroyed())StatusFeedback.show(this,StatusView.Symbol.FAILED,"対象を確認できませんでした",()->confirm(cleanup),"再試行");});
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
