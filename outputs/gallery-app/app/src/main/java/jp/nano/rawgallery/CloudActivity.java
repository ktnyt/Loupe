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
  private TextView status;
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
    status = new TextView(this);
    status.setTextColor(0xffd4c7b4);
    status.setTextSize(14);
    status.setPadding(0, 20, 0, 20);
    status.setText("同期や端末容量の管理は設定から変更できます。接続確認や１枚だけの検証は「その他」から開けます。");
    root.addView(status);
    LinearLayout row = new LinearLayout(this);

    Button back = new Button(this);
    back.setText("戻る");
    back.setOnClickListener(v -> finish());
    row.addView(back);
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
                        android.widget.Toast.makeText(
                                this, "実行中の処理が終わるまでお待ちください", android.widget.Toast.LENGTH_SHORT)
                            .show();
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
                            .setNegativeButton("戻る", null)
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
            if (!allowed) status.setText("日本向けAmazon以外への移動を停止しました。接続方式の確認が必要です。");
            return !allowed;
          }

          public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
            handler.cancel();
            status.setText("通信の安全性を確認できないため停止しました。");
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
                    status.setText("このアプリのログイン情報を消しました。");
                  });
        });
    Button dockBack = GalleryUi.button(this, "戻る", () -> back.performClick());
    Button dockLogin = GalleryUi.button(this, "ログイン", () -> login.performClick());
    loginButton = dockLogin;
    Button dockSync = GalleryUi.primary(GalleryUi.button(this, "同期設定", () -> sync.performClick()));
    Button dockMore = GalleryUi.button(this, "その他", () -> more.performClick());
    root.addView(GalleryUi.dock(this, dockBack, dockLogin, dockSync, dockMore));
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    setContentView(root);
  }

  private void diagnose() {
    String url =
        ORIGIN
            + "/drive/v1/search?asset=ALL&tempLink=false&resourceVersion=V2&ContentType=JSON&limit=1&offset=0&filters=type%3A%28PHOTOS%29&lowResThumbnail=true&searchContext=customer";
    final String cookies = CookieManager.getInstance().getCookie(url),
        agent = web.getSettings().getUserAgentString();
    if (cookies == null || cookies.isBlank()) {
      status.setText("まずAmazonの画面でログインしてください。");
      return;
    }
    check.setEnabled(false);
    status.setText("写真一覧への読み取り接続を確認しています…");
    worker.execute(
        () -> {
          String message;
          int count = -1;
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
          runOnUiThread(
              () -> {
                if (isDestroyed()) return;
                check.setEnabled(true);
                status.setText(result);
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
      status.setText("先にAmazonへログインしてください。");
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
                    status.setText("検証するRAWがありません。");
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
                    status.setText("端末のRAWを読み取れませんでした。");
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
      status.setText("自動同期が実行中です。完了後に検証してください。");
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
          try {
            CloudVerifier client = new CloudVerifier(cookies, agent);
            CloudVerifier.Progress progress =
                text ->
                    runOnUiThread(
                        () -> {
                          if (!isDestroyed()) status.setText(text);
                        });
            result =
                uploading
                    ? client.uploadAndVerify(getApplicationContext(), photo, progress)
                    : client.verify(getApplicationContext(), photo, progress);
            if (client.lastVerified != null)
              try (ArchiveStore store = new ArchiveStore(this)) {
                store.verified(photo, client.lastVerified);
              }
          } catch (CloudVerifier.VerificationException e) {
            result = "元のファイルの検証を完了できませんでした。\n" + e.getMessage();
          } catch (Exception e) {
            result =
                uploading
                    ? "通信または保存結果を確認できませんでした。自動再送はせず、復元検証で保存状態を確認してください。端末の元のファイルは残しています。"
                    : "通信または復元の応答形式を確認できませんでした。写真は変更していません。";
          }
          SyncEngine.RUNNING.set(false);
          final String message = result;
          runOnUiThread(
              () -> {
                if (isDestroyed()) return;
                verify.setEnabled(true);
                upload.setEnabled(true);
                loginButton.setEnabled(true);
                logoutButton.setEnabled(true);
                check.setEnabled(true);
                status.setText(message);
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
