package jp.nano.rawgallery;

import android.os.SystemClock;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Stores and restores checked originals in the Tokyo archive. The caller owns the archive DB. */
final class E2Archive {
  private static final String HOST = "s3.ap-northeast-1.idrivee2.com";
  private static final String SCOPE_SUFFIX = "/ap-northeast-1/s3/aws4_request";
  private static final String EMPTY_SHA =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  private static final long LIMIT = 512L * 1024 * 1024;
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
  private final String bucket, keyId, secret;
  private final ArchiveStore store;

  E2Archive(ArchiveStore store, String bucket, String keyId, String secret) {
    if (!bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")
        || !keyId.matches("[A-Za-z0-9]{20}") || !secret.matches("[A-Za-z0-9+/=_-]{40}"))
      throw new IllegalArgumentException("IDrive e2の設定が不正です");
    this.store = store;
    this.bucket = bucket;
    this.keyId = keyId;
    this.secret = secret;
  }

  /** A retry may find an existing object. Only a confirmed absence permits another PUT. */
  synchronized long save(String id, File source) throws Exception {
    long size = source.length();
    checkSize(size);
    String sha;
    try (InputStream in = new FileInputStream(source)) {
      sha = readHash(in, null, size);
    }
    String path = path(id, sha);
    JSONObject record = store.get(id);
    boolean fresh = record == null;
    if (fresh) {
      record = new JSONObject().put("key", id).put("provider", "idrive-e2")
          .put("bucket", bucket).put("size", size).put("sha", sha);
      store.put(record, "pending");
    } else {
      requireRecord(record);
      if (record.getLong("size") != size || !sha.equals(record.getString("sha")))
        throw new IOException("同じ写真IDの内容が変わりました");
      if ("verified".equals(record.getString("state"))) return 0;
      if (!"pending".equals(record.getString("state"))) throw new IOException("保管状態が不正です");
      String version = get(path, "", size, sha, null, true);
      if (version != null) {
        store.put(record.put("version", version), "verified");
        return 0;
      }
    }

    long start = SystemClock.elapsedRealtimeNanos();
    HttpsURLConnection put = open("PUT", path, "", sha);
    String version;
    try {
      put.setDoOutput(true);
      put.setFixedLengthStreamingMode(size);
      try (InputStream in = new FileInputStream(source); OutputStream out = put.getOutputStream()) {
        if (!sha.equals(readHash(in, out, size))) throw new IOException("送信中に原本が変わりました");
      }
      requireOk(put.getResponseCode());
      version = version(put);
    } finally {
      put.disconnect();
    }
    long putMillis = millis(start);
    get(path, version, size, sha, null, false);
    store.put(record.put("version", version), "verified");
    return putMillis;
  }

  /** Reads the confirmed version into a temporary file and only publishes checked bytes. */
  long restore(String id, File destination) throws Exception {
    JSONObject record = store.get(id);
    requireRecord(record);
    if (!"verified".equals(record.getString("state")) || destination.exists())
      throw new IOException("復元の前提を満たしていません");
    String sha = record.getString("sha");
    long size = record.getLong("size");
    String version = checkedVersion(record.getString("version"));
    checkSize(size);
    String path = path(id, sha);
    File temp = File.createTempFile("e2-restore-", ".part", destination.getParentFile());
    long start = SystemClock.elapsedRealtimeNanos();
    try {
      try (FileOutputStream out = new FileOutputStream(temp)) {
        get(path, version, size, sha, out, false);
        out.getFD().sync();
      }
      Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
      return millis(start);
    } finally {
      temp.delete();
    }
  }

  private void requireRecord(JSONObject record) throws Exception {
    if (record == null || !"idrive-e2".equals(record.optString("provider"))
        || !bucket.equals(record.optString("bucket")))
      throw new IOException("保存先と保管記録が一致しません");
  }

  private String path(String id, String sha) {
    if (!id.matches("[0-9a-f]{32}") || !sha.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("原本の識別子が不正です");
    return "/" + bucket + "/loupe-transfer-pilot/android/" + id + "/" + sha;
  }

  private String get(String path, String version, long size, String sha, OutputStream out,
      boolean allowMissing) throws Exception {
    String query = version.isEmpty() ? "" : "versionId=" + checkedVersion(version);
    HttpsURLConnection conn = open("GET", path, query, EMPTY_SHA);
    try {
      int code = conn.getResponseCode();
      if (allowMissing && code == 404) return null;
      requireOk(code);
      String actualVersion = version(conn);
      if (!version.isEmpty() && !version.equals(actualVersion))
        throw new IOException("取得した版が一致しません");
      long length = conn.getContentLengthLong();
      if (length >= 0 && length != size) throw new IOException("取得したサイズが一致しません");
      try (InputStream in = conn.getInputStream()) {
        if (!sha.equals(readHash(in, out, size))) throw new IOException("取得したSHA-256が一致しません");
      }
      return actualVersion;
    } finally {
      conn.disconnect();
    }
  }

  private HttpsURLConnection open(String method, String path, String query, String sha)
      throws Exception {
    String stamp = TIME.format(Instant.now());
    String day = stamp.substring(0, 8);
    String scope = day + SCOPE_SUFFIX;
    String headers = "host;x-amz-content-sha256;x-amz-date";
    String canonical = method + "\n" + path + "\n" + query + "\nhost:" + HOST
        + "\nx-amz-content-sha256:" + sha + "\nx-amz-date:" + stamp + "\n\n"
        + headers + "\n" + sha;
    String toSign = "AWS4-HMAC-SHA256\n" + stamp + "\n" + scope + "\n"
        + hex(MessageDigest.getInstance("SHA-256").digest(bytes(canonical)));
    byte[] dateKey = hmac(bytes("AWS4" + secret), day);
    byte[] regionKey = hmac(dateKey, "ap-northeast-1");
    byte[] serviceKey = hmac(regionKey, "s3");
    String signature = hex(hmac(hmac(serviceKey, "aws4_request"), toSign));
    HttpsURLConnection conn = (HttpsURLConnection) new URL("https://" + HOST + path
        + (query.isEmpty() ? "" : "?" + query)).openConnection();
    conn.setInstanceFollowRedirects(false);
    conn.setUseCaches(false);
    conn.setConnectTimeout(15000);
    conn.setReadTimeout(30000);
    conn.setRequestMethod(method);
    conn.setRequestProperty("Accept-Encoding", "identity");
    conn.setRequestProperty("x-amz-content-sha256", sha);
    conn.setRequestProperty("x-amz-date", stamp);
    conn.setRequestProperty("Authorization", "AWS4-HMAC-SHA256 Credential=" + keyId + "/" + scope
        + ", SignedHeaders=" + headers + ", Signature=" + signature);
    return conn;
  }

  private static String readHash(InputStream in, OutputStream out, long size) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] buffer = new byte[65536];
    long count = 0;
    long deadline = SystemClock.elapsedRealtime() + 300000;
    for (int n; (n = in.read(buffer)) != -1; ) {
      count += n;
      if (count > size || Thread.currentThread().isInterrupted()
          || SystemClock.elapsedRealtime() > deadline) throw new IOException("原本の転送を中止しました");
      digest.update(buffer, 0, n);
      if (out != null) out.write(buffer, 0, n);
    }
    if (count != size) throw new IOException("原本のサイズが一致しません");
    return hex(digest.digest());
  }

  private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

  private static byte[] hmac(byte[] key, String data) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(bytes(data));
  }

  private static String hex(byte[] bytes) {
    char[] digits = "0123456789abcdef".toCharArray();
    char[] result = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      result[i * 2] = digits[(bytes[i] & 255) >>> 4];
      result[i * 2 + 1] = digits[bytes[i] & 15];
    }
    return new String(result);
  }

  private static String version(HttpsURLConnection conn) throws IOException {
    return checkedVersion(conn.getHeaderField("x-amz-version-id"));
  }

  private static String checkedVersion(String v) throws IOException {
    if (v == null || "null".equals(v) || !v.matches("[A-Za-z0-9_-]{1,512}"))
      throw new IOException("IDrive e2の版を確認できません");
    return v;
  }

  private static void requireOk(int code) throws IOException {
    if (code != 200) throw new IOException("IDrive e2の転送失敗 (HTTP " + code + ")");
  }

  private static void checkSize(long size) throws IOException {
    if (size <= 0 || size > LIMIT) throw new IOException("原本のサイズが試行範囲外です");
  }

  private static long millis(long start) { return (SystemClock.elapsedRealtimeNanos() - start) / 1000000; }
}
