package jp.nano.rawgallery;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ErrorHandler;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;

/**
 * Short-lived JPEG transport for Nano Banana. Only the dedicated UUID prefix is reachable.
 * The caller persists the object key before upload and retains it until delete succeeds.
 */
final class NanoBananaE2 {
  private static final String HOST = "s3.ap-northeast-1.idrivee2.com";
  private static final String REGION = "ap-northeast-1";
  private static final String HEADERS = "host;x-amz-content-sha256;x-amz-date";
  private static final String EMPTY_SHA =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
  private static final int JPEG_LIMIT = 5 * 1024 * 1024;
  private final String bucket, keyId, secret;

  NanoBananaE2(String bucket, String keyId, String secret) {
    if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")
        || keyId == null || !keyId.matches("[A-Za-z0-9]{20}")
        || secret == null || !secret.matches("[A-Za-z0-9+/=_-]{40}"))
      throw new IllegalArgumentException("IDrive e2の設定が不正です");
    this.bucket = bucket;
    this.keyId = keyId;
    this.secret = secret;
  }

  /** A confirmed client rejection did not create an object; no cleanup is required. */
  static final class UploadRejected extends IOException {
    final int statusCode;
    UploadRejected(int statusCode) {
      super("一時画像のアップロードが拒否されました (HTTP " + statusCode + ")");
      this.statusCode = statusCode;
    }
  }

  /** One PUT only; returns the accepted version, or null when no version header is supplied. */
  String upload(String objectKey, byte[] jpeg, String owner) throws Exception {
    String path = path(objectKey);
    checkOwner(owner);
    checkJpeg(jpeg);
    HttpsURLConnection connection = null;
    try {
      connection = open("PUT", path, "", hash(jpeg), owner);
      connection.setRequestProperty("Content-Type", "image/jpeg");
      connection.setDoOutput(true);
      connection.setFixedLengthStreamingMode(jpeg.length);
      try (OutputStream out = connection.getOutputStream()) { out.write(jpeg); }
      int code = connection.getResponseCode();
      if (code >= 400 && code < 500 && code != 408) throw new UploadRejected(code);
      if (code != 200) throw new IOException();
      return checkedVersion(connection.getHeaderField("x-amz-version-id"));
    } catch (UploadRejected rejected) {
      throw rejected;
    } catch (Exception unavailable) {
      // URLConnection error messages and causes can include signed URLs or request details.
      throw new IOException("一時画像のアップロード結果を確認できません");
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  /** A 30-minute URL pinned to a successful immutable version; never log or persist it. */
  String signedGet(String objectKey, String version) throws Exception {
    String path = path(objectKey);
    if (version == null || "null".equals(version))
      throw versioningRequired();
    checkedVersion(version);
    try { return presignGet(HOST, REGION, path, keyId, secret, Instant.now(), 1800, version); }
    catch (Exception unavailable) { throw new IOException("一時画像の読み取りURLを作成できません"); }
  }

  /** Deletes only data versions carrying this attempt's independent, persisted owner token. */
  void delete(String objectKey, String knownVersion, String owner) throws Exception {
    String path = path(objectKey);
    checkOwner(owner);
    if (knownVersion != null) {
      deleteOwnedVersion(path, checkedVersion(knownVersion), owner, true);
      return;
    }
    Head current = head(path, null);
    if (!current.missing && current.version == null) {
      if (owner.equals(current.owner)) throw versioningRequired();
    }
    // A marker or a different writer's current version can hide this attempt's older PUT.
    // Restrict listing to the exact UUID key, verify each version's owner, and leave all markers.
    for (String version : versions(objectKey)) deleteOwnedVersion(path, version, owner, false);
  }

  private void deleteOwnedVersion(String path, String version, String owner, boolean known) throws Exception {
    Head value = head(path, version);
    if(!value.missing && (!validOwner(value.owner) || known&&!owner.equals(value.owner)))
      throw new IOException("記録した一時画像の所有情報が一致しないため削除を保留しました");
    if (!value.missing && owner.equals(value.owner)) {
      if (version == null || "null".equals(version)) throw versioningRequired();
      removeVersion(path, version);
    }
  }

  private static final class Head {
    final boolean missing;
    final String version, owner;
    Head(boolean missing, String version, String owner) {
      this.missing = missing; this.version = version; this.owner = owner;
    }
  }

  private Head head(String path, String requestedVersion) throws Exception {
    HttpsURLConnection connection = null;
    try {
      String query = requestedVersion == null ? "" : "versionId=" + encode(requestedVersion);
      connection = open("HEAD", path, query, EMPTY_SHA);
      int code = connection.getResponseCode();
      if (code == 404) return new Head(true, null, null);
      if (code != 200) throw new IOException();
      String version = checkedVersion(connection.getHeaderField("x-amz-version-id"));
      if (requestedVersion != null && !requestedVersion.equals(version)) throw new IOException();
      return new Head(false, version, connection.getHeaderField("x-amz-meta-loupe-nano-owner"));
    } catch (Exception unavailable) {
      throw new IOException("一時画像の所有情報を確認できないため削除を保留しました");
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private static IOException versioningRequired() {
    return new IOException("バケットのバージョニングを確認し、e2上の一時画像を削除してから再確認してください");
  }

  private void removeVersion(String path, String version) throws Exception {
    if (version == null || "null".equals(version)) throw versioningRequired();
    HttpsURLConnection remove = null;
    try {
      String query = "versionId=" + encode(version);
      remove = open("DELETE", path, query, EMPTY_SHA);
      int code = remove.getResponseCode();
      if (code != 200 && code != 204 && code != 404) throw new IOException();
    } catch (Exception unavailable) {
      throw new IOException("一時画像の削除結果を確認できません");
    } finally {
      if (remove != null) remove.disconnect();
    }
  }

  private List<String> versions(String objectKey) throws Exception {
    HttpsURLConnection list = null;
    try {
      String query = "max-keys=1000&prefix=" + encode(objectKey) + "&versions=";
      list = open("GET", "/" + bucket, query, EMPTY_SHA);
      if (list.getResponseCode() != 200) throw new IOException();
      byte[] body;
      try (InputStream in = list.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        for (int count; (count = in.read(buffer)) != -1;) {
          if (out.size() + count > 1024 * 1024) throw new IOException();
          out.write(buffer, 0, count);
        }
        body = out.toByteArray();
      }
      return listedVersions(body, objectKey);
    } catch (Exception unavailable) {
      throw new IOException("一時画像の全ての版を確認できないため削除を保留しました");
    } finally {
      if (list != null) list.disconnect();
    }
  }

  static List<String> listedVersions(byte[] body, String objectKey) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setExpandEntityReferences(false);
    // Android's DOM parser does not support every Xerces feature flag. S3 XML is UTF-8;
    // reject declarations before parsing and disable external resolution without those flags.
    String xml = new String(body, StandardCharsets.UTF_8);
    if (xml.indexOf('\0') >= 0 || xml.contains("<!")) throw new IOException();
    javax.xml.parsers.DocumentBuilder parser = factory.newDocumentBuilder();
    parser.setEntityResolver((publicId, systemId) -> { throw new SAXException("External XML is disabled"); });
    parser.setErrorHandler(new ErrorHandler() {
      public void warning(SAXParseException error) throws SAXException { throw error; }
      public void error(SAXParseException error) throws SAXException { throw error; }
      public void fatalError(SAXParseException error) throws SAXException { throw error; }
    });
    Element root = parser.parse(new InputSource(new StringReader(xml))).getDocumentElement();
    if (!"ListVersionsResult".equals(root.getLocalName())
        || !objectKey.equals(child(root, "Prefix"))
        || !"false".equals(child(root, "IsTruncated"))) throw new IOException();
    List<String> versions = new ArrayList<>();
    for (Node item = root.getFirstChild(); item != null; item = item.getNextSibling()) {
      if (!(item instanceof Element)) continue;
      Element entry = (Element) item;
      String type = entry.getLocalName();
      if (!"Version".equals(type)) continue; // Markers do not carry this attempt's owner metadata.
      if (!objectKey.equals(child(entry, "Key"))) continue;
      String id = checkedVersion(child(entry, "VersionId"));
      if (id == null) throw new IOException();
      versions.add(id);
    }
    return versions;
  }

  private static String child(Element parent, String name) throws IOException {
    String value = null;
    for (Node item = parent.getFirstChild(); item != null; item = item.getNextSibling()) {
      if (item instanceof Element && name.equals(item.getLocalName())) {
        if (value != null) throw new IOException();
        value = item.getTextContent();
      }
    }
    return value;
  }

  private String path(String objectKey) {
    if (objectKey == null || !objectKey.matches(
        "loupe-nano-temp/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg"))
      throw new IllegalArgumentException("一時画像の識別子が不正です");
    return "/" + bucket + "/" + objectKey;
  }

  private static boolean validOwner(String owner) {
    return owner!=null&&owner.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }
  private static void checkOwner(String owner) {
    if (!validOwner(owner))
      throw new IllegalArgumentException("一時画像の所有識別子が不正です");
  }

  private static void checkJpeg(byte[] jpeg) {
    if (jpeg == null || jpeg.length < 4 || jpeg.length > JPEG_LIMIT
        || (jpeg[0] & 255) != 255 || (jpeg[1] & 255) != 216
        || (jpeg[jpeg.length - 2] & 255) != 255 || (jpeg[jpeg.length - 1] & 255) != 217)
      throw new IllegalArgumentException("一時画像は5 MiB以下のJPEGにしてください");
  }

  static String checkedVersion(String version) throws IOException {
    if (version == null) return null; // No version header: unversioned object.
    if (version.isEmpty() || version.length() > 1024) throw new IOException("一時画像の版が不正です");
    for (int i = 0; i < version.length(); i++)
      if (version.charAt(i) < 33 || version.charAt(i) > 126)
        throw new IOException("一時画像の版が不正です");
    return version;
  }

  private HttpsURLConnection open(String method, String path, String query, String sha)
      throws Exception { return open(method, path, query, sha, null); }

  private HttpsURLConnection open(String method, String path, String query, String sha, String owner)
      throws Exception {
    String stamp = TIME.format(Instant.now());
    HttpsURLConnection connection = (HttpsURLConnection) new URL("https://" + HOST + path
        + (query.isEmpty() ? "" : "?" + query)).openConnection();
    connection.setInstanceFollowRedirects(false);
    connection.setUseCaches(false);
    connection.setConnectTimeout(15000);
    connection.setReadTimeout(30000);
    connection.setRequestMethod(method);
    connection.setRequestProperty("Accept-Encoding", "identity");
    connection.setRequestProperty("x-amz-content-sha256", sha);
    connection.setRequestProperty("x-amz-date", stamp);
    if (owner != null) connection.setRequestProperty("x-amz-meta-loupe-nano-owner", owner);
    connection.setRequestProperty("Authorization", authorization(
        method, HOST, REGION, path, query, sha, stamp, keyId, secret, owner));
    return connection;
  }

  // Pure Java signing helpers are also exercised with AWS's published fixed vectors.
  static String authorization(String method, String host, String region, String path, String query,
      String sha, String stamp, String keyId, String secret) throws Exception {
    return authorization(method, host, region, path, query, sha, stamp, keyId, secret, null);
  }

  static String authorization(String method, String host, String region, String path, String query,
      String sha, String stamp, String keyId, String secret, String owner) throws Exception {
    String scope = stamp.substring(0, 8) + "/" + region + "/s3/aws4_request";
    String headers = HEADERS + (owner == null ? "" : ";x-amz-meta-loupe-nano-owner");
    String canonical = method + "\n" + path + "\n" + query + "\nhost:" + host
        + "\nx-amz-content-sha256:" + sha + "\nx-amz-date:" + stamp
        + (owner == null ? "" : "\nx-amz-meta-loupe-nano-owner:" + owner) + "\n\n"
        + headers + "\n" + sha;
    return "AWS4-HMAC-SHA256 Credential=" + keyId + "/" + scope
        + ",SignedHeaders=" + headers + ",Signature="
        + signature(canonical, stamp, region, secret);
  }

  static String presignGet(String host, String region, String path, String keyId, String secret,
      Instant instant, int seconds) throws Exception {
    return presignGet(host, region, path, keyId, secret, instant, seconds, null);
  }

  static String presignGet(String host, String region, String path, String keyId, String secret,
      Instant instant, int seconds, String version) throws Exception {
    String stamp = TIME.format(instant);
    String scope = stamp.substring(0, 8) + "/" + region + "/s3/aws4_request";
    String query = "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=" + encode(keyId + "/" + scope)
        + "&X-Amz-Date=" + stamp + "&X-Amz-Expires=" + seconds + "&X-Amz-SignedHeaders=host"
        + (version == null ? "" : "&versionId=" + encode(version));
    String canonical = "GET\n" + path + "\n" + query + "\nhost:" + host
        + "\n\nhost\nUNSIGNED-PAYLOAD";
    return "https://" + host + path + "?" + query + "&X-Amz-Signature="
        + signature(canonical, stamp, region, secret);
  }

  private static String signature(String canonical, String stamp, String region, String secret)
      throws Exception {
    String day = stamp.substring(0, 8);
    String scope = day + "/" + region + "/s3/aws4_request";
    String toSign = "AWS4-HMAC-SHA256\n" + stamp + "\n" + scope + "\n" + hash(bytes(canonical));
    byte[] dateKey = hmac(bytes("AWS4" + secret), day);
    byte[] regionKey = hmac(dateKey, region);
    byte[] serviceKey = hmac(regionKey, "s3");
    return hex(hmac(hmac(serviceKey, "aws4_request"), toSign));
  }

  /** RFC 3986 byte encoding, including '/' in query values; never form encoding with '+'. */
  static String encode(String value) {
    StringBuilder encoded = new StringBuilder();
    String digits = "0123456789ABCDEF";
    for (byte item : bytes(value)) {
      int b = item & 255;
      if (b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9'
          || b == '-' || b == '_' || b == '.' || b == '~') encoded.append((char) b);
      else encoded.append('%').append(digits.charAt(b >>> 4)).append(digits.charAt(b & 15));
    }
    return encoded.toString();
  }

  private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

  private static String hash(byte[] input) throws Exception {
    return hex(MessageDigest.getInstance("SHA-256").digest(input));
  }

  private static byte[] hmac(byte[] key, String data) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(bytes(data));
  }

  private static String hex(byte[] input) {
    char[] digits = "0123456789abcdef".toCharArray();
    char[] result = new char[input.length * 2];
    for (int i = 0; i < input.length; i++) {
      result[i * 2] = digits[(input[i] & 255) >>> 4];
      result[i * 2 + 1] = digits[input[i] & 15];
    }
    return new String(result);
  }
}
