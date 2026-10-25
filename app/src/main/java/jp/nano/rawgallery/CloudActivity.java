package jp.nano.rawgallery;

import android.app.*;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.net.URL;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** Login stays on Amazon's page; diagnostics only GET the first page of the user's photo index. */
public final class CloudActivity extends DiagnosticActivity {
  private static final String ORIGIN = "https://www.amazon.co.jp";
  private final ExecutorService worker = new DiagnosticExecutor("CloudActivity.worker", 1);
  private WebView web;
  private StatusView status;
  private boolean showingRunning;
  private Button check, verify, upload, loginButton, logoutButton;

  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(20, 12, 20, 12);
    root.setBackgroundColor(Color.rgb(20, 23, 25));
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars());
          v.setPadding(i.left + 12, i.top + 12, i.right + 12, i.bottom);
          return insets;
        });
    TextView title = new TextView(this);
    title.setText("Amazon Photos");
    title.setTextSize(24);
    title.setTextColor(Color.WHITE);
    root.addView(title);
    status=new StatusView(this);status.setVisibility(View.GONE);
    root.addView(status);
    LinearLayout row = new LinearLayout(this);

    Button login = new Button(this);
    loginButton = login;
    login.setText("ログイン");
    login.setOnClickListener(
        v -> {
          web.setVisibility(View.VISIBLE);
          web.loadUrl(ORIGIN + "/photos");
        });
    row.addView(login);
    check = new Button(this);
    check.setText("接続を確認");
    check.setOnClickListener(v -> diagnose());
    Button more = new Button(this);
    more.setText("⋯");
    more.setContentDescription("接続確認とその他の操作");
    more.setOnClickListener(
        v ->
            new GalleryUi.Sheet(this)
                .setItems(
                    new String[] {"接続を確認", "保存した写真をダウンロードして確認", "写真1枚を保存して内容を確認", "ログアウト"},
                    (d, which) -> {
                      Button target = new Button[] {check, verify, upload, logoutButton}[which];
                      if (!target.isEnabled()) {
                        StatusFeedback.show(this,StatusView.Symbol.WAITING,"実行中の処理が終わるまでお待ちください",null,"");
                        return;
                      }
                      if (which == 3)
                        new GalleryUi.Sheet(this)
                            .setTitle("ログアウトしますか？")
                            .setMessage("自動同期と端末からの自動削除を停止し、このアプリのログイン情報を消します。")
                            .setPositiveButton(
                                "ログアウト",
                                (dialog, w) -> {
                                  if (target.isEnabled()) target.performClick();
                                })
                            .setNegativeButton("キャンセル", null)
                            .show();
                      else target.performClick();
                    })
                .setNegativeButton("閉じる", null)
                .show());
    row.addView(more);
    verify = new Button(this);
    verify.setText("保存した写真をダウンロードして確認（読み取りのみ）");
    verify.setOnClickListener(v -> chooseVerification(false));
    upload = new Button(this);
    upload.setText("写真1枚を保存して内容を確認");
    upload.setOnClickListener(v -> chooseVerification(true));
    Button sync = new Button(this);
    sync.setText("自動同期・端末容量の設定");
    sync.setOnClickListener(
        v -> startActivity(new android.content.Intent(this, SyncActivity.class)));

    web = new WebView(this);
    web.setVisibility(View.GONE);
    web.setBackgroundColor(Color.WHITE);
    WebView.setWebContentsDebuggingEnabled(false);
    WebSettings s = web.getSettings();
    getSharedPreferences("settings", 0)
        .edit()
        .putString("cloudUserAgent", s.getUserAgentString())
        .apply();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setAllowFileAccess(false);
    s.setAllowContentAccess(false);
    s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    s.setSupportMultipleWindows(false);
    CookieManager.getInstance().setAcceptCookie(true);
    CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
    web.setWebViewClient(
        new WebViewClient() {
          public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            String host = u.getHost();
            boolean allowed =
                "https".equals(u.getScheme())
                    && host != null
                    && (host.equals("amazon.co.jp") || host.endsWith(".amazon.co.jp"));
            if (!allowed) setStatus(StatusView.Symbol.HOST,"日本向けAmazon以外への移動を停止しました。接続方式の確認が必要です。",()->loginButton.performClick());
            return !allowed;
          }

          public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
            handler.cancel();
            setStatus(StatusView.Symbol.TLS,"通信の安全性を確認できないため停止しました。",()->loginButton.performClick());
          }

          public void onPageFinished(WebView v, String url) {
            CookieManager.getInstance().flush();
          }
        });
    android.widget.FrameLayout browserSpace = new android.widget.FrameLayout(this);
    browserSpace.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
    root.addView(browserSpace, new LinearLayout.LayoutParams(-1, 0, 1));
    Button logout = new Button(this);
    logoutButton = logout;
    logout.setText("このアプリのログイン情報を消す");
    logout.setOnClickListener(
        v -> {
          getSharedPreferences("settings", 0)
              .edit()
              .putBoolean("syncEnabled", false)
              .putBoolean("cleanupEnabled", false)
              .apply();
          SyncJob.schedule(this);
          web.stopLoading();
          web.loadUrl("about:blank");
          CookieManager.getInstance()
              .removeAllCookies(
                  done -> {
                    CookieManager.getInstance().flush();
                    WebStorage.getInstance().deleteAllData();
                    web.clearCache(true);
                    getSharedPreferences("settings", 0)
                        .edit()
                        .remove("cloudCheckedAt")
                        .remove("cloudCount")
                        .apply();
                    setStatus(StatusView.Symbol.COMPLETE,"このアプリのログイン情報を消しました。",null);
                  });
        });
    View actions = actions(this, () -> login.performClick(), () -> sync.performClick(), () -> more.performClick());
    loginButton = (Button)((LinearLayout)actions).getChildAt(0);
    root.addView(actions);
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    setContentView(root);
  }

  private void setStatus(StatusView.Symbol kind,String message,Runnable retry) {
    if(kind==StatusView.Symbol.RUNNING){if(!showingRunning)status.begin(message);else status.describe(message);showingRunning=true;}
    else {showingRunning=false;status.show(kind,"",message,retry,kind==StatusView.Symbol.AUTH?"ログイン":"再試行");}
  }

  static View actions(android.content.Context c, Runnable login, Runnable sync, Runnable more) {
    Button[] buttons = { GalleryUi.button(c, "ログイン", login),
        GalleryUi.button(c, "同期の設定", sync), GalleryUi.button(c, "接続と確認", more) };
    View dock = GalleryUi.dock(c, buttons);
    for (int i = 0; i < buttons.length; i++) {
      AffordanceIcon icon = new AffordanceIcon(i + 2, GalleryUi.TEXT);
      int size = GalleryUi.dp(c, 24); icon.setBounds(0, 0, size, size);
      buttons[i].setCompoundDrawables(null, icon, null, null);
      buttons[i].setCompoundDrawablePadding(GalleryUi.dp(c, 4));
      buttons[i].setGravity(android.view.Gravity.CENTER);
      buttons[i].setPadding(GalleryUi.dp(c,6), GalleryUi.dp(c,6), GalleryUi.dp(c,6), GalleryUi.dp(c,6));
      buttons[i].setMinHeight(GalleryUi.dp(c,64));
      buttons[i].setMinimumHeight(GalleryUi.dp(c,64));
    }
    dock.setContentDescription("クラウドの操作");
    return dock;
  }

  private void diagnose() {
    String url =
        ORIGIN
            + "/drive/v1/search?asset=ALL&tempLink=false&resourceVersion=V2&ContentType=JSON&limit=1&offset=0&filters=type%3A%28PHOTOS%29&lowResThumbnail=true&searchContext=customer";
    final String cookies = CookieManager.getInstance().getCookie(url),
        agent = web.getSettings().getUserAgentString();
    if (cookies == null || cookies.isBlank()) {
      setStatus(StatusView.Symbol.AUTH,"まずAmazonの画面でログインしてください。",()->loginButton.performClick());
      return;
    }
    check.setEnabled(false);
    setStatus(StatusView.Symbol.RUNNING,"写真一覧への読み取り接続を確認しています",null);
    worker.execute(
        () -> {
          String message;
          int count = -1;
          StatusView.Symbol outcome=StatusView.Symbol.FAILED;
          HttpsURLConnection connection = null;
          try {
            connection = (HttpsURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("Cookie", cookies);
            connection.setRequestProperty("User-Agent", agent);
            connection.setRequestProperty("Accept", "application/json");
            for (String part : cookies.split(";")) {
              String[] kv = part.trim().split("=", 2);
              if (kv.length == 2 && kv[0].equals("session-id"))
                connection.setRequestProperty("x-amzn-sessionid", kv[1]);
            }
            int code = connection.getResponseCode();
            if(code==401||code==403||code>=300&&code<400)outcome=StatusView.Symbol.AUTH;
            if (code == 401 || code == 403) message = "認証が必要です（" + code + "）。ログインを完了してから再確認してください。";
            else if (code >= 300 && code < 400) message = "ログイン画面などへの転送が返りました。ログイン後に再確認してください。";
            else if (code != 200) message = "接続を確認できませんでした（" + code + "）。写真は変更していません。";
            else {
              ByteArrayOutputStream out = new ByteArrayOutputStream();
              try (InputStream in = connection.getInputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) != -1) {
                  if (out.size() + n > 1024 * 1024) throw new IOException("response limit");
                  out.write(b, 0, n);
                }
              }
              JSONObject data = new JSONObject(out.toString("UTF-8"));
              if (!data.has("count") || !(data.opt("data") instanceof JSONArray))
                throw new IOException("Unexpected response");
              count = data.getInt("count");
              if (count < 0) throw new IOException("Invalid count");
              outcome=StatusView.Symbol.COMPLETE;
              message = "写真一覧への接続を確認できました。\nクラウドの写真：" + count + "件\nこの接続確認だけでは元のファイルの保存と復元を保証しません。";
            }
          } catch (Exception e) {
            message = "接続を確認できませんでした。通信状態、ログイン状態、接続先の仕様が変わった可能性があります。";
          } finally {
            if (connection != null) connection.disconnect();
          }
          final String result =
              message + (count >= 0 ? "\n" + new CloudVerifier(cookies, agent).endpoint() : "");
          final int found = count;
          final StatusView.Symbol resultKind=outcome;
          runOnUiThread(
              () -> {
                if (isDestroyed()) return;
                check.setEnabled(true);
                setStatus(resultKind,result,resultKind==StatusView.Symbol.AUTH?()->loginButton.performClick():found>=0?null:this::diagnose);
                if(found>=0)status.counts(found+"件");
                if (found >= 0)
                  getSharedPreferences("settings", 0)
                      .edit()
                      .putLong("cloudCheckedAt", System.currentTimeMillis())
                      .putInt("cloudCount", found)
                      .apply();
                else
                  getSharedPreferences("settings", 0)
                      .edit()
                      .remove("cloudCheckedAt")
                      .remove("cloudCount")
                      .apply();
              });
        });
  }

  private void chooseVerification(boolean uploading) {
    final String cookies = CookieManager.getInstance().getCookie(ORIGIN + "/drive/v1/nodes");
    final String agent = web.getSettings().getUserAgentString();
    if (cookies == null || cookies.isBlank()) {
      setStatus(StatusView.Symbol.AUTH,"先にAmazonへログインしてください。",()->loginButton.performClick());
      return;
    }
    verify.setEnabled(false);
    worker.execute(
        () -> {
          try {
            java.util.LinkedHashMap<String, PhotoRepository.Photo> candidates =
                new java.util.LinkedHashMap<>();
            for (PhotoRepository.Photo p : new PhotoRepository(this).scan("すべて"))
              if (!p.cameraGroup().equals("その他") && "content".equals(p.uri.getScheme()))
                candidates.putIfAbsent(p.cameraGroup(), p);
            java.util.ArrayList<PhotoRepository.Photo> photos =
                new java.util.ArrayList<>(candidates.values());
            String[] labels = new String[photos.size()];
            for (int i = 0; i < photos.size(); i++)
              labels[i] = photos.get(i).cameraGroup() + " · " + photos.get(i).name;
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  verify.setEnabled(true);
                  if (photos.isEmpty()) {
                    setStatus(StatusView.Symbol.EMPTY,"検証するRAWがありません。",null);
                    return;
                  }
                  new GalleryUi.Sheet(this)
                      .setTitle("検証するRAW")
                      .setItems(
                          labels,
                          (dialog, which) ->
                              confirmPhoto(photos.get(which), cookies, agent, uploading))
                      .setNegativeButton("閉じる", null)
                      .show();
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (!isDestroyed()) {
                    verify.setEnabled(true);
                    setStatus(StatusView.Symbol.FAILED,"端末のRAWを読み取れませんでした。",()->chooseVerification(uploading));
                  }
                });
          }
        });
  }

  private void confirmPhoto(
      PhotoRepository.Photo photo, String cookies, String agent, boolean uploading) {
    if (!uploading) {
      verifyPhoto(photo, cookies, agent, false);
      return;
    }
    new GalleryUi.Sheet(this)
        .setTitle("この元のファイルをAmazon Photosに保存しますか？")
        .setMessage(
            photo.name
                + "\n"
                + String.format(java.util.Locale.JAPAN, "%.1f MiB", photo.size / 1048576.0)
                + "\n元のファイル１枚を保存し、再取得して内容を照合します。同じ元のファイルがあれば再送しません。端末の写真は削除しません。")
        .setPositiveButton("保存して確認", (dialog, which) -> verifyPhoto(photo, cookies, agent, true))
        .setNegativeButton("キャンセル", null)
        .show();
  }

  private void verifyPhoto(
      PhotoRepository.Photo photo, String cookies, String agent, boolean uploading) {
    if (!SyncEngine.RUNNING.compareAndSet(false, true)) {
      setStatus(StatusView.Symbol.WAITING,"自動同期が実行中です。完了後に検証してください。",()->chooseVerification(uploading));
      return;
    }
    verify.setEnabled(false);
    upload.setEnabled(false);
    loginButton.setEnabled(false);
    logoutButton.setEnabled(false);
    check.setEnabled(false);
    web.setVisibility(View.GONE);
    worker.execute(
        () -> {
          String result;
          StatusView.Symbol outcome=StatusView.Symbol.FAILED;
          try {
            CloudVerifier client = new CloudVerifier(cookies, agent);
            CloudVerifier.Progress progress =
                text ->
                    runOnUiThread(
                        () -> {
                          if (!isDestroyed()) setStatus(StatusView.Symbol.RUNNING,text,null);
                        });
            result =
                uploading
                    ? client.uploadAndVerify(getApplicationContext(), photo, progress)
                    : client.verify(getApplicationContext(), photo, progress);
            if (client.lastVerified != null)
              try (ArchiveStore store = new ArchiveStore(this)) {
                store.verified(photo, client.lastVerified);
                outcome=StatusView.Symbol.COMPLETE;
              }
          } catch (CloudVerifier.VerificationException e) {
            outcome=StatusView.Symbol.FAILED;
            result = "元のファイルの検証を完了できませんでした。\n" + e.getMessage();
          } catch (Exception e) {
            outcome=uploading?StatusView.Symbol.UNKNOWN:StatusView.Symbol.FAILED;
            result =
                uploading
                    ? "通信または保存結果を確認できませんでした。自動再送はせず、復元検証で保存状態を確認してください。端末の元のファイルは残しています。"
                    : "通信または復元の応答形式を確認できませんでした。写真は変更していません。";
          }
          SyncEngine.RUNNING.set(false);
          final String message = result;
          final StatusView.Symbol resultKind=outcome;
          runOnUiThread(
              () -> {
                if (isDestroyed()) return;
                verify.setEnabled(true);
                upload.setEnabled(true);
                loginButton.setEnabled(true);
                logoutButton.setEnabled(true);
                check.setEnabled(true);
                setStatus(resultKind,message,resultKind==StatusView.Symbol.COMPLETE?null:()->chooseVerification(false));
              });
        });
  }

  protected void onDestroy() {
    web.stopLoading();
    web.destroy();
    worker.shutdownNow();
    super.onDestroy();
  }
}
