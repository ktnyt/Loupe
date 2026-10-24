package jp.nano.rawgallery;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** RAW storage and byte verification. Authentication is never forwarded to a download redirect. */
final class CloudVerifier {
  static class VerificationException extends IOException {
    VerificationException(String message) {
      super(message);
    }
  }

  static final class AuthException extends VerificationException {
    AuthException() {
      super("Amazonへの再ログインが必要です");
    }
  }

  static final class Verified {
    final String id, owner, sha, md5;

    Verified(String id, String owner, String sha, String md5) {
      this.id = id;
      this.owner = owner;
      this.sha = sha;
      this.md5 = md5;
    }
  }

  Verified lastVerified;
  static final String ORIGIN = "https://www.amazon.co.jp";
  private final String cookies, agent;

  interface Progress {
    void show(String message);
  }

  CloudVerifier(String cookies, String agent) {
    this.cookies = cookies;
    this.agent = agent;
  }

  private HttpsURLConnection open(URL url, boolean authenticate) throws IOException {
    if (!safeDownload(url)) throw new VerificationException("許可していない配信先です");
    if (authenticate && !isOrigin(url)) throw new VerificationException("認証情報の転送を停止しました");
    HttpsURLConnection c = (HttpsURLConnection) url.openConnection();
    c.setInstanceFollowRedirects(false);
    c.setConnectTimeout(15000);
    c.setReadTimeout(30000);
    c.setRequestProperty("User-Agent", agent);
    c.setRequestProperty("Accept-Encoding", "identity");
    if (authenticate) {
      c.setRequestProperty("Cookie", cookies);
      c.setRequestProperty("Accept", "application/json");
      for (String part : cookies.split(";")) {
        String[] kv = part.trim().split("=", 2);
        if (kv.length == 2 && kv[0].equals("session-id"))
          c.setRequestProperty("x-amzn-sessionid", kv[1]);
      }
    }
    return c;
  }

  static boolean isOrigin(URL u) {
    return "https".equals(u.getProtocol())
        && "www.amazon.co.jp".equals(u.getHost())
        && (u.getPort() == -1 || u.getPort() == 443)
        && u.getUserInfo() == null;
  }

  static boolean safeDownload(URL u) {
    String h = u.getHost().toLowerCase(Locale.ROOT);
    return "https".equals(u.getProtocol())
        && (u.getPort() == -1 || u.getPort() == 443)
        && u.getUserInfo() == null
        && (isOrigin(u)
            || h.endsWith(".drive.amazonaws.com")
            || h.endsWith(".s3.amazonaws.com")
            || h.matches("[a-z0-9.-]+\\.s3[.-][a-z0-9-]+\\.amazonaws\\.com"));
  }

  JSONObject get(String path) throws Exception {
    HttpsURLConnection c = open(new URL(ORIGIN + path), true);
    try {
      int code = c.getResponseCode();
      if (code == 401 || code == 403) throw new AuthException();
      if (code != 200) throw new VerificationException("読み取りに失敗しました（" + code + "）");
      try (InputStream in = c.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) {
          if (out.size() + n > 2 * 1024 * 1024) throw new VerificationException("応答が上限を超えました");
          out.write(b, 0, n);
        }
        return new JSONObject(out.toString("UTF-8"));
      }
    } finally {
      c.disconnect();
    }
  }

  String endpoint() {
    try {
      JSONObject j = get("/drive/v1/account/endpoint");
      String value = j.optString("contentUrl", "");
      URL u = new URL(value);
      String host = u.getHost();
      if (!"https".equals(u.getProtocol())
          || u.getUserInfo() != null
          || !host.matches("[a-z0-9.-]{1,200}")
          || !(host.endsWith(".amazonaws.com") || host.endsWith(".amazon.co.jp")))
        return "保存先：応答形式の確認が必要です";
      return "保存先ホスト：" + host;
    } catch (Exception e) {
      return "保存先ホスト：自動取得できませんでした";
    }
  }

  String verify(Context context, PhotoRepository.Photo p, Progress progress) throws Exception {
    lastVerified = null;
    if (p.size <= 0 || p.size > 512L * 1024 * 1024)
      throw new VerificationException("検証対象は512 MiB以下の画像です");
    progress.show(p.name + "\n端末の原本を照合しています…");
    String md5, sha;
    try (InputStream in = PhotoRepository.original(context, p)) {
      if (in == null) throw new VerificationException("原本を開けません");
      String[] hashes = hashes(in, p.size);
      md5 = hashes[0];
      sha = hashes[1];
    }
    progress.show(p.name + "\n同じ原本をクラウドで探しています…");
    JSONObject results =
        get(
            "/drive/v1/nodes?filters="
                + URLEncoder.encode(
                    "contentProperties.md5:" + md5 + " AND status:AVAILABLE", "UTF-8")
                + "&limit=20&resourceVersion=V2&ContentType=JSON");
    JSONArray data = results.getJSONArray("data");
    JSONObject node = null;
    for (int i = 0; i < data.length(); i++) {
      JSONObject candidate = data.getJSONObject(i),
          cp = candidate.optJSONObject("contentProperties");
      if (cp != null
          && cp.optLong("size", -1) == p.size
          && md5.equalsIgnoreCase(cp.optString("md5"))
          && "AVAILABLE".equals(candidate.optString("status"))
          && "FILE".equals(candidate.optString("kind"))) {
        node = candidate;
        break;
      }
    }
    if (node == null) return p.name + "\n同じ原本は見つかりませんでした。アップロードも削除もしていません。";
    String id = node.getString("id"), owner = node.getString("ownerId");
    if (!id.matches("[A-Za-z0-9_-]{1,128}") || !owner.matches("[A-Za-z0-9_-]{1,128}"))
      throw new VerificationException("原本の識別情報を確認できません");
    progress.show(p.name + "\n原本をダウンロードして内容を照合しています…");
    URL url =
        new URL(
            ORIGIN
                + "/drive/v1/nodes/"
                + id
                + "/contentRedirection?querySuffix=%3Fdownload%3Dtrue&ownerId="
                + owner);
    for (int redirects = 0; redirects < 5; redirects++) {
      HttpsURLConnection c = open(url, redirects == 0);
      try {
        int code = c.getResponseCode();
        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
          String location = c.getHeaderField("Location");
          if (location == null) throw new VerificationException("復元先が不明です");
          url = new URL(url, location);
          continue;
        }
        if (code != 200) throw new VerificationException("原本の取得に失敗しました（" + code + "）");
        long length = c.getContentLengthLong();
        if (length >= 0 && length != p.size) throw new VerificationException("復元した原本のサイズが違います");
        try (InputStream in = c.getInputStream()) {
          String[] restored = hashes(in, p.size);
          if (!sha.equals(restored[1])) throw new VerificationException("原本の内容が一致しません。削除できません");
        }
        lastVerified = new Verified(id, owner, sha, md5);
        return p.name + "\n復元した原本のサイズとSHA-256が一致しました。\n端末とクラウドの写真は変更していません。";
      } finally {
        c.disconnect();
      }
    }
    throw new VerificationException("転送回数が上限を超えました");
  }

  static boolean uploadEndpointAllowed(URL endpoint) {
    // Discovery selects a region only. The upload path below is fixed, never supplied by the
    // response.
    return "https".equals(endpoint.getProtocol())
        && "content-jp.drive.amazonaws.com".equals(endpoint.getHost())
        && endpoint.getUserInfo() == null
        && (endpoint.getPort() == -1 || endpoint.getPort() == 443)
        && endpoint.getQuery() == null
        && endpoint.getRef() == null;
  }

  String uploadAndVerify(Context context, PhotoRepository.Photo p, Progress progress)
      throws Exception {
    if (p.size <= 0 || p.size > 512L * 1024 * 1024)
      throw new VerificationException("対象は512 MiB以下の画像です");
    progress.show(p.name + "\n送信前に原本を照合しています…");
    String md5;
    try (InputStream in = PhotoRepository.original(context, p)) {
      if (in == null) throw new VerificationException("原本を開けません");
      md5 = hashes(in, p.size)[0];
    }
    JSONObject existing =
        get(
            "/drive/v1/nodes?filters="
                + URLEncoder.encode(
                    "contentProperties.md5:" + md5 + " AND status:AVAILABLE", "UTF-8")
                + "&limit=20&resourceVersion=V2&ContentType=JSON");
    if (existing.getJSONArray("data").length() > 0) return verify(context, p, progress);
    JSONObject endpoint = get("/drive/v1/account/endpoint");
    URL base = new URL(endpoint.getString("contentUrl"));
    if (!uploadEndpointAllowed(base)) throw new VerificationException("保存先の方式が想定と違うため送信を停止しました");
    JSONArray roots =
        get("/drive/v1/nodes?filters=isRoot%3Atrue&resourceVersion=V2&ContentType=JSON")
            .getJSONArray("data");
    if (roots.length() != 1) throw new VerificationException("保存先フォルダを特定できません");
    String parent = roots.getJSONObject(0).getString("id");
    if (!parent.matches("[A-Za-z0-9_-]{1,128}")) throw new VerificationException("保存先の識別情報が不正です");
    URL url =
        new URL(
            "https://content-jp.drive.amazonaws.com/cdproxy/nodes?kind=FILE&name="
                + URLEncoder.encode(p.name, "UTF-8")
                + "&parentNodeId="
                + parent);
    HttpsURLConnection c = (HttpsURLConnection) url.openConnection();
    c.setInstanceFollowRedirects(false);
    c.setConnectTimeout(15000);
    c.setReadTimeout(60000);
    c.setRequestMethod("POST");
    c.setDoOutput(true);
    c.setFixedLengthStreamingMode(p.size);
    c.setRequestProperty("Cookie", cookies);
    c.setRequestProperty("User-Agent", agent);
    c.setRequestProperty("Content-Type", "application/octet-stream");
    c.setRequestProperty("Accept", "application/json");
    c.setRequestProperty("x-amzn-file-md5", md5);
    for (String part : cookies.split(";")) {
      String[] kv = part.trim().split("=", 2);
      if (kv.length == 2 && kv[0].equals("session-id"))
        c.setRequestProperty("x-amzn-sessionid", kv[1]);
    }
    try (ArchiveStore store = new ArchiveStore(context)) {
      store.pending(p);
    }
    progress.show(p.name + "\nAmazon Photosに原本を保存しています…");
    // A deadline disconnects blocked writes as well as reads. Never retry an uncertain POST.
    java.util.concurrent.ScheduledExecutorService timer =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    java.util.concurrent.ScheduledFuture<?> timeout =
        timer.schedule(c::disconnect, 180, java.util.concurrent.TimeUnit.SECONDS);
    try {
      try (InputStream in = PhotoRepository.original(context, p);
          OutputStream out = c.getOutputStream()) {
        if (in == null) throw new VerificationException("原本を開けません");
        byte[] b = new byte[65536];
        int n;
        long sent = 0;
        while ((n = in.read(b)) != -1) {
          if (Thread.currentThread().isInterrupted()) throw new VerificationException("送信を中断しました");
          sent += n;
          if (sent > p.size) throw new VerificationException("原本のサイズが変わりました");
          out.write(b, 0, n);
        }
        if (sent != p.size) throw new VerificationException("原本を最後まで読めませんでした");
      }
      int code = c.getResponseCode();
      if (code != 200 && code != 201 && code != 409)
        throw new VerificationException("保存要求の結果（" + code + "）。自動再送はしません");
      try (InputStream in = code == 409 ? c.getErrorStream() : c.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        if (in == null) throw new VerificationException("保存結果を確認できません。自動再送はしません");
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) {
          if (out.size() + n > 1024 * 1024) throw new VerificationException("保存結果の上限を超えました");
          out.write(b, 0, n);
        }
        JSONObject response = new JSONObject(out.toString("UTF-8"));
        String id =
            code == 409
                ? response
                    .getJSONObject("errorDetails")
                    .getJSONArray("conflictNodeIds")
                    .getString(0)
                : response.getString("id");
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new VerificationException("保存結果を確認できません");
      }
    } finally {
      timeout.cancel(false);
      timer.shutdownNow();
      c.disconnect();
    }
    String result = verify(context, p, progress);
    if (result.contains("見つかりません"))
      return p.name + "\n保存要求は受け付けられましたが、検索への反映を確認できていません。後で復元検証を行ってください。";
    return result.replace("端末とクラウドの写真は変更していません。", "クラウドへの保存と復元を確認しました。端末の原本は残しています。");
  }

  void download(JSONObject record, File destination) throws Exception {
    String id = record.getString("id"),
        owner = record.getString("owner"),
        sha = record.getString("sha");
    if (!id.matches("[A-Za-z0-9_-]{1,128}") || !owner.matches("[A-Za-z0-9_-]{1,128}"))
      throw new VerificationException("原本の識別情報が不正です");
    URL url =
        new URL(
            ORIGIN
                + "/drive/v1/nodes/"
                + id
                + "/contentRedirection?querySuffix=%3Fdownload%3Dtrue&ownerId="
                + owner);
    boolean completed = false;
    try {
      for (int redirects = 0; redirects < 5; redirects++) {
        HttpsURLConnection c = open(url, redirects == 0);
        try {
          int code = c.getResponseCode();
          if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
            String location = c.getHeaderField("Location");
            if (location == null) throw new VerificationException("復元先が不明です");
            url = new URL(url, location);
            continue;
          }
          if (code == 401 || code == 403) throw new AuthException();
          if (code != 200) throw new VerificationException("原本を取得できません（" + code + "）");
          long expected = record.getLong("size");
          if (expected <= 0 || expected > 512L * 1024 * 1024)
            throw new VerificationException("原本のサイズが不正です");
          try (InputStream in = c.getInputStream();
              FileOutputStream out = new FileOutputStream(destination)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[65536];
            int n;
            long total = 0, deadline = System.nanoTime() + 180_000_000_000L;
            while ((n = in.read(b)) != -1) {
              if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
                throw new VerificationException("復元を中断しました");
              total += n;
              if (total > expected) throw new VerificationException("原本のサイズが違います");
              digest.update(b, 0, n);
              out.write(b, 0, n);
            }
            if (total != expected || !sha.equals(hex(digest.digest())))
              throw new VerificationException("原本の内容が一致しません");
            out.getFD().sync();
            completed = true;
            return;
          }
        } finally {
          c.disconnect();
        }
      }
      throw new VerificationException("転送回数が上限を超えました");
    } finally {
      if (!completed) destination.delete();
    }
  }

  static String localSha(Context context, PhotoRepository.Photo p) throws Exception {
    try (InputStream in = PhotoRepository.original(context, p)) {
      if (in == null) throw new VerificationException("原本を開けません");
      return hashes(in, p.size)[1];
    }
  }

  private static String[] hashes(InputStream in, long expected) throws Exception {
    MessageDigest md5 = MessageDigest.getInstance("MD5"),
        sha = MessageDigest.getInstance("SHA-256");
    byte[] b = new byte[65536];
    int n;
    long count = 0, deadline = System.nanoTime() + 180_000_000_000L;
    while ((n = in.read(b)) != -1) {
      if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
        throw new VerificationException("検証を中断しました");
      count += n;
      if (count > expected) throw new VerificationException("原本のサイズが変わりました");
      md5.update(b, 0, n);
      sha.update(b, 0, n);
    }
    if (count != expected) throw new VerificationException("原本を最後まで取得できませんでした");
    return new String[] {hex(md5.digest()), hex(sha.digest())};
  }

  private static String hex(byte[] b) {
    StringBuilder s = new StringBuilder();
    for (byte v : b) s.append(String.format(Locale.ROOT, "%02x", v));
    return s.toString();
  }
}
