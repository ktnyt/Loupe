package jp.nano.rawgallery;

import android.os.SystemClock;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Xml;
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
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

/** Stores and restores checked originals in the Tokyo archive. The caller owns the archive DB. */
final class E2Archive {
  private static final String HOST = "s3.ap-northeast-1.idrivee2.com";
  private static final String SCOPE_SUFFIX = "/ap-northeast-1/s3/aws4_request";
  private static final String EMPTY_SHA =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  static final long LIMIT = 512L * 1024 * 1024;
  private static final String CATALOG = "loupe/catalog/v1.json";
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
  private final String bucket, keyId, secret;
  private final ArchiveStore store;
  private final String prefix;
  private final Context context;
  private final Network network;

  E2Archive(ArchiveStore store, String bucket, String keyId, String secret) {
    this(store, bucket, keyId, secret, "loupe-transfer-pilot/android", null, null);
  }

  static E2Archive forPhotos(ArchiveStore store, String bucket, String keyId, String secret) {
    return forPhotos(store, bucket, keyId, secret, null, null);
  }

  static E2Archive forPhotos(ArchiveStore store, String bucket, String keyId, String secret,
      Context context, Network network) {
    return new E2Archive(store, bucket, keyId, secret, "loupe/originals", context, network);
  }

  private E2Archive(ArchiveStore store, String bucket, String keyId, String secret, String prefix,
      Context context, Network network) {
    if (!bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")
        || !keyId.matches("[A-Za-z0-9]{20}") || !secret.matches("[A-Za-z0-9+/=_-]{40}"))
      throw new IllegalArgumentException("IDrive e2の設定が不正です");
    this.store = store;
    this.prefix = prefix;
    this.context = context;
    this.network = network;
    this.bucket = bucket;
    this.keyId = keyId;
    this.secret = secret;
  }

  void checkConnection() throws Exception {
    HttpsURLConnection connection = open("GET", "/" + bucket, "versioning=", EMPTY_SHA);
    try {
      requireOk(connection.getResponseCode());
      try (InputStream in = connection.getInputStream()) {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        for (int n; (n = in.read(buffer)) != -1; ) {
          if (response.size() + n > 8192) throw new IOException("保存先の応答が不正です");
          response.write(buffer, 0, n);
        }
        XmlPullParser xml = Xml.newPullParser();
        xml.setInput(new ByteArrayInputStream(response.toByteArray()), "UTF-8");
        for (int event = xml.next(); event != XmlPullParser.END_DOCUMENT; event = xml.next()) {
          if (event == XmlPullParser.START_TAG && xml.getDepth() == 2
              && "Status".equals(xml.getName()) && "Enabled".equals(xml.nextText())) return;
        }
      }
      throw new IOException("バケットのバージョニングを有効にしてください");
    } finally {
      connection.disconnect();
    }
  }

  /** A retry may find an existing object. Only a confirmed absence permits another PUT. */
  synchronized long save(String id, File source) throws Exception {
    return save(id, source, null);
  }

  synchronized long savePhoto(File source, String name, String mime) throws Exception {
    return save(null, source, new JSONObject().put("name", name).put("mime", mime));
  }

  synchronized void saveIndexed(String id, File source, JSONObject metadata) throws Exception {
    save(id, source, metadata);
  }

  private long save(String id, File source, JSONObject metadata) throws Exception {
    long size = source.length();
    checkSize(size);
    String sha;
    try (InputStream in = new FileInputStream(source)) {
      sha = readHash(in, null, size);
    }
    if (id == null) {
      id = hex(MessageDigest.getInstance("SHA-256")
          .digest(bytes(bucket + "\n" + sha + "\n" + metadata.getString("name")))).substring(0, 32);
    }
    String path = path(id, sha);
    JSONObject record = store.get(id);
    boolean fresh = record == null;
    if (fresh) {
      record = (metadata == null ? new JSONObject() : metadata)
          .put("key", id).put("provider", "idrive-e2")
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
        store.put(record.put("version", version).put("verifiedAt", System.currentTimeMillis()), "verified");
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
    store.put(record.put("version", version).put("verifiedAt", System.currentTimeMillis()), "verified");
    return putMillis;
  }

  /** Reads the confirmed version into a temporary file and only publishes checked bytes. */
  long restore(String id, File destination) throws Exception {
    return restoreRecord(store.get(id), destination);
  }

  long restoreRecord(JSONObject record, File destination) throws Exception {
    requireRecord(record);
    if (!"verified".equals(record.getString("state")) || destination.exists())
      throw new IOException("復元の前提を満たしていません");
    String sha = record.getString("sha");
    long size = record.getLong("size");
    String version = checkedVersion(record.getString("version"));
    checkSize(size);
    String path = path(record.getString("key"), sha);
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

  record CatalogObject(byte[] body, String version) {}

  CatalogObject readCatalog(String requested) throws Exception {
    String query = requested.isEmpty() ? "" : "versionId=" + checkedVersion(requested);
    HttpsURLConnection connection = open("GET", "/" + bucket + "/" + CATALOG, query, EMPTY_SHA);
    try {
      int code = connection.getResponseCode();
      if (code == 404) return null;
      requireOk(code);
      String actual = version(connection);
      if (!requested.isEmpty() && !requested.equals(actual))
        throw new IOException("取得したカタログの版が一致しません");
      byte[] body;
      try (InputStream in = connection.getInputStream()) {
        body = readBounded(in, E2RecoveryCatalog.MAX_BYTES + 1);
      }
      return new CatalogObject(body, actual);
    } finally {
      connection.disconnect();
    }
  }

  List<String> previousCatalogVersions() throws Exception {
    HttpsURLConnection connection = open("GET", "/" + bucket,
        "max-keys=16&prefix=loupe%2Fcatalog%2Fv1.json&versions=", EMPTY_SHA);
    try {
      requireOk(connection.getResponseCode());
      byte[] response;
      try (InputStream in = connection.getInputStream()) {
        response = readBounded(in, 65537);
      }
      if (response.length > 65536) throw new IOException("カタログの世代一覧が大きすぎます");
      XmlPullParser xml = Xml.newPullParser();
      xml.setInput(new ByteArrayInputStream(response), "UTF-8");
      List<String> result = new ArrayList<>();
      boolean insideVersion = false;
      boolean truncated = false;
      String key = null, versionId = null;
      for (int event = xml.next(); event != XmlPullParser.END_DOCUMENT; event = xml.next()) {
        if (event == XmlPullParser.START_TAG && xml.getDepth() == 2) {
          insideVersion = "Version".equals(xml.getName());
          if ("IsTruncated".equals(xml.getName())) truncated = "true".equals(xml.nextText());
          key = null;
          versionId = null;
        } else if (insideVersion && event == XmlPullParser.START_TAG && xml.getDepth() == 3) {
          if ("Key".equals(xml.getName())) key = xml.nextText();
          else if ("VersionId".equals(xml.getName())) versionId = xml.nextText();
        } else if (insideVersion && event == XmlPullParser.END_TAG && xml.getDepth() == 2) {
          if (CATALOG.equals(key)) result.add(checkedVersion(versionId));
          insideVersion = false;
        }
      }
      if (result.size() > 16) throw new IOException("カタログの世代一覧が不正です");
      if (truncated && result.isEmpty()) throw new IOException("カタログの前世代を確認できません");
      return result;
    } finally {
      connection.disconnect();
    }
  }

  void writeCatalog(byte[] body) throws Exception {
    HttpsURLConnection connection = open("PUT", "/" + bucket + "/" + CATALOG, "", sha256(body));
    String saved;
    try {
      connection.setDoOutput(true);
      connection.setFixedLengthStreamingMode(body.length);
      try (OutputStream out = connection.getOutputStream()) {
        out.write(body);
      }
      requireOk(connection.getResponseCode());
      saved = version(connection);
    } finally {
      connection.disconnect();
    }
    CatalogObject verified = readCatalog(saved);
    if (verified == null || !java.util.Arrays.equals(body, verified.body()))
      throw new IOException("保存したカタログの読み戻しが一致しません");
  }

  private static byte[] readBounded(InputStream input, int limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    long deadline = SystemClock.elapsedRealtime() + 60000;
    while (out.size() < limit) {
      if (Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() > deadline)
        throw new IOException("カタログの受信を中止しました");
      int count = input.read(buffer, 0, Math.min(buffer.length, limit - out.size()));
      if (count < 0) break;
      out.write(buffer, 0, count);
    }
    return out.toByteArray();
  }

  private void requireRecord(JSONObject record) throws Exception {
    if (record == null || !"idrive-e2".equals(record.optString("provider"))
        || !bucket.equals(record.optString("bucket")))
      throw new IOException("保存先と保管記録が一致しません");
  }

  private String path(String id, String sha) {
    if (!id.matches("[0-9a-f]{32}") || !sha.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("原本の識別子が不正です");
    return "/" + bucket + "/" + prefix + "/" + id + "/" + sha;
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
    if (Thread.currentThread().isInterrupted()) throw new IOException("通信を中断しました");
    URL url = new URL("https://" + HOST + path + (query.isEmpty() ? "" : "?" + query));
    HttpsURLConnection conn;
    if (context == null) {
      conn = (HttpsURLConnection) url.openConnection();
    } else {
      if (!unmetered(context, network)) throw new IOException("従量制でない回線を待っています");
      conn = (HttpsURLConnection) network.openConnection(url);
    }
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

  static boolean unmetered(Context context, Network network) {
    if (network == null) return false;
    NetworkCapabilities capabilities =
        context.getSystemService(ConnectivityManager.class).getNetworkCapabilities(network);
    return capabilities != null
        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
  }

  static String readHash(InputStream in, OutputStream out, long size) throws Exception {
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

  static String checkedVersion(String v) throws IOException {
    if (v == null || "null".equals(v) || !v.matches("[A-Za-z0-9_-]{1,512}"))
      throw new IOException("IDrive e2の版を確認できません");
    return v;
  }

  static String sha256(byte[] value) throws java.security.NoSuchAlgorithmException {
    return hex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static void requireOk(int code) throws IOException {
    if (code != 200) throw new IOException("IDrive e2の転送失敗 (HTTP " + code + ")");
  }

  private static void checkSize(long size) throws IOException {
    if (size <= 0 || size > LIMIT) throw new IOException("原本のサイズが試行範囲外です");
  }

  private static long millis(long start) { return (SystemClock.elapsedRealtimeNanos() - start) / 1000000; }
}
