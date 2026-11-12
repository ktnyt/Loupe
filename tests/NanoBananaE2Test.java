package jp.nano.rawgallery;

import java.time.Instant;

/** All credentials below are AWS's published dummy credentials. No network calls are made. */
public final class NanoBananaE2Test {
  private static final String ID = "AKIAIOSFODNN7EXAMPLE";
  private static final String SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
  private static final String OWNER = "11111111-2222-4333-8444-555555555555";
  private static final String OTHER = "66666666-7777-4888-9999-aaaaaaaaaaaa";
  private static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  private interface Throwing { void run() throws Exception; }

  private static void require(boolean good, String failure) {
    if (!good) throw new AssertionError(failure);
  }

  private static void rejected(Throwing action) throws Exception {
    try { action.run(); }
    catch (IllegalArgumentException expected) {
      require(!expected.toString().contains(ID) && !expected.toString().contains(SECRET), "secret-free validation");
      return;
    }
    throw new AssertionError("Unsafe input accepted");
  }

  private static final java.util.ArrayDeque<Reply> replies = new java.util.ArrayDeque<>();
  private static final class Reply {
    final String method, path, query, version, body, owner;
    final int status;
    Reply(String method, String path, String query, int status, String version, String body, String owner) {
      this.method = method; this.path = path; this.query = query; this.status = status;
      this.version = version; this.body = body; this.owner = owner;
    }
  }
  private static void expect(String method, String path, String query, int status, String version, String body) {
    expectOwner(method, path, query, status, version, body, OWNER);
  }
  private static void expectOwner(String method, String path, String query, int status, String version, String body, String owner) {
    replies.add(new Reply(method, path, query, status, version, body, owner));
  }
  private static final class Mock extends javax.net.ssl.HttpsURLConnection {
    final Reply reply;
    Mock(java.net.URL url, Reply reply) { super(url); this.reply = reply; }
    public String getCipherSuite() { return "mock"; }
    public java.security.cert.Certificate[] getLocalCertificates() { return null; }
    public java.security.cert.Certificate[] getServerCertificates() { return null; }
    public void connect() {}
    public void disconnect() {}
    public boolean usingProxy() { return false; }
    @Override public java.io.OutputStream getOutputStream() { return new java.io.ByteArrayOutputStream(); }
    @Override public java.io.InputStream getInputStream() {
      return new java.io.ByteArrayInputStream(reply.body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Override public String getHeaderField(String name) {
      if ("x-amz-version-id".equalsIgnoreCase(name)) return reply.version;
      if ("x-amz-meta-loupe-nano-owner".equalsIgnoreCase(name)) return reply.owner;
      return null;
    }
    @Override public int getResponseCode() throws java.io.IOException {
      require(getURL().getProtocol().equals("https") && getURL().getHost().equals("s3.ap-northeast-1.idrivee2.com"),
          "fixed HTTPS host");
      require(reply.method.equals(getRequestMethod()) && reply.path.equals(getURL().getPath())
          && java.util.Objects.equals(reply.query, getURL().getQuery()), "exact request method/path/query");
      require(!getInstanceFollowRedirects() && getConnectTimeout() == 15000 && getReadTimeout() == 30000,
          "redirect and timeout policy");
      require(getRequestProperty("Authorization").startsWith("AWS4-HMAC-SHA256 "), "request signed");
      if (getRequestMethod().equals("PUT")) {
        require(OWNER.equals(getRequestProperty("x-amz-meta-loupe-nano-owner")), "PUT carries owner metadata");
        require(getRequestProperty("Authorization").contains(";x-amz-meta-loupe-nano-owner,Signature="),
            "PUT owner is a signed header");
      }
      if (reply.status < 0) throw new java.io.IOException("RAW PRIVATE URL " + getURL());
      return reply.status;
    }
  }
  private static void failsIo(Throwing operation, String failure) throws Exception {
    try { operation.run(); throw new AssertionError(failure); }
    catch (java.io.IOException expected) {
      require(expected.getCause() == null && !expected.toString().contains("https")
          && !expected.toString().contains(ID), "exception strips private URL/cause");
      require(replies.isEmpty(), "no unexpected cleanup requests");
    }
  }
  private static void transportChecks(NanoBananaE2 e2, String key) throws Exception {
    java.net.URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new java.net.URLStreamHandler() {
      @Override protected java.net.URLConnection openConnection(java.net.URL url) {
        require(!replies.isEmpty(), "unexpected network request");
        return new Mock(url, replies.remove());
      }
    } : null);
    String path = "/test-bucket/" + key;
    byte[] jpeg = {(byte)255, (byte)216, 0, (byte)255, (byte)217};
    expect("PUT", path, null, 200, "put-v1", "");
    require(e2.upload(key, jpeg, OWNER).equals("put-v1"), "persist successful PUT version");
    expect("PUT", path, null, 200, null, "");
    require(e2.upload(key, jpeg, OWNER) == null, "unversioned PUT result can be retained for manual cleanup");
    for (int code : new int[] {400, 401, 403, 404, 409, 413, 429}) {
      expect("PUT", path, null, code, null, "");
      try { e2.upload(key, jpeg, OWNER); throw new AssertionError("PUT refusal accepted"); }
      catch (NanoBananaE2.UploadRejected refused) {
        require(refused.statusCode == code && refused.getCause() == null, "definitive rejection preserved");
      }
    }
    for (int code : new int[] {408, 500, 503, 307, -1}) {
      expect("PUT", path, null, code, null, "");
      try { e2.upload(key, jpeg, OWNER); throw new AssertionError("unknown outcome accepted"); }
      catch (java.io.IOException uncertain) {
        require(!(uncertain instanceof NanoBananaE2.UploadRejected), "uncertain PUT remains uncertain");
        require(uncertain.getCause() == null && !uncertain.toString().contains("https")
            && !uncertain.toString().contains(ID), "exception strips private URL/cause");
      }
    }
    expect("HEAD", path, "versionId=v%2B1%2F%3D", 200, "v+1/=", "");
    expect("DELETE", path, "versionId=v%2B1%2F%3D", 204, null, "");
    e2.delete(key, "v+1/=", OWNER);
    expectOwner("HEAD", path, "versionId=other-v1", 200, "other-v1", "", OTHER);
    failsIo(() -> e2.delete(key, "other-v1", OWNER), "known owner mismatch reported success");
    expectOwner("HEAD", path, "versionId=no-owner", 200, "no-owner", "", null);
    failsIo(() -> e2.delete(key, "no-owner", OWNER), "missing known owner reported success");
    expect("HEAD", path, "versionId=gone-v1", 404, null, "");
    e2.delete(key, "gone-v1", OWNER);
    expect("HEAD", path, "versionId=expected-v1", 200, "different-v1", "");
    failsIo(() -> e2.delete(key, "expected-v1", OWNER), "mismatched returned version allowed cleanup");
    expect("HEAD", path, null, 200, null, "");
    failsIo(() -> e2.delete(key, null, OWNER), "unversioned owned object was auto-deleted");
    expect("HEAD", path, "versionId=null", 200, "null", "");
    failsIo(() -> e2.delete(key, "null", OWNER), "owned null-version was auto-deleted");
    expect("HEAD", path, null, 403, null, "");
    failsIo(() -> e2.delete(key, null, OWNER), "failed HEAD allowed cleanup");

    String listQuery = "max-keys=1000&prefix=" + NanoBananaE2.encode(key) + "&versions=";
    String entries = "<DeleteMarker><Key>" + key + "</Key><VersionId>marker-v1</VersionId></DeleteMarker>"
        + "<Version><Key>" + key + ".neighbor</Key><VersionId>leave-neighbor</VersionId></Version>"
        + "<Version><Key>originals/photo.jpg</Key><VersionId>leave-original</VersionId></Version>"
        + "<Version><Key>" + key + "</Key><VersionId>hidden-v1</VersionId></Version>"
        + "<Version><Key>" + key + "</Key><VersionId>other-writer-v1</VersionId></Version>";
    String listing = "<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Prefix>" + key
        + "</Prefix><IsTruncated>false</IsTruncated>" + entries + "</ListVersionsResult>";
    for (int current : new int[] {404, 200, 201}) {
      expectOwner("HEAD", path, null, current==404?404:200, current==201?null:current == 200 ? "other-writer-v1" : "marker-v1", "", OTHER);
      expect("GET", "/test-bucket", listQuery, 200, null, listing);
      expect("HEAD", path, "versionId=hidden-v1", 200, "hidden-v1", "");
      expect("DELETE", path, "versionId=hidden-v1", 204, null, "");
      expectOwner("HEAD", path, "versionId=other-writer-v1", 200, "other-writer-v1", "", OTHER);
      e2.delete(key, null, OWNER); // Only hidden-v1 is deleted; marker and other writer remain.
    }
    expect("HEAD", path, null, 404, null, "");
    expect("GET", "/test-bucket", listQuery, 403, null, "");
    failsIo(() -> e2.delete(key, null, OWNER), "list refusal allowed cleanup");
    expect("HEAD", path, null, 404, null, "");
    expect("GET", "/test-bucket", listQuery, 200, null, listing.replace("<IsTruncated>false", "<IsTruncated>true"));
    failsIo(() -> e2.delete(key, null, OWNER), "truncated list allowed cleanup");
    expect("HEAD", path, null, 404, null, "");
    expect("GET", "/test-bucket", listQuery, 200, null, listing);
    expect("HEAD", path, "versionId=hidden-v1", 403, null, "");
    failsIo(() -> e2.delete(key, null, OWNER), "unconfirmed version owner allowed cleanup");
    expect("HEAD", path, null, 404, null, "");
    expect("GET", "/test-bucket", listQuery, 200, null, listing);
    expectOwner("HEAD", path, "versionId=hidden-v1", 200, "hidden-v1", "", null);
    failsIo(() -> e2.delete(key, null, OWNER), "missing candidate owner reported success");
    for(String invalidOwner : new String[]{"", "invalid-owner"}) {
      expect("HEAD", path, null, 404, null, "");
      expect("GET", "/test-bucket", listQuery, 200, null, listing);
      expectOwner("HEAD", path, "versionId=hidden-v1", 200, "hidden-v1", "", invalidOwner);
      failsIo(() -> e2.delete(key, null, OWNER), "invalid candidate owner reported success");
    }
    String empty = "<ListVersionsResult><Prefix>" + key
        + "</Prefix><IsTruncated>false</IsTruncated></ListVersionsResult>";
    expect("HEAD", path, null, 404, null, "");
    expect("GET", "/test-bucket", listQuery, 200, null, empty);
    e2.delete(key, null, OWNER); // Manual removal can complete after confirmed exact-key absence.
    String onlyOther = listing.replace("<Version><Key>" + key + "</Key><VersionId>hidden-v1</VersionId></Version>", "");
    expect("HEAD", path, null, 404, null, "");
    expect("GET", "/test-bucket", listQuery, 200, null, onlyOther);
    expectOwner("HEAD", path, "versionId=other-writer-v1", 200, "other-writer-v1", "", OTHER);
    e2.delete(key, null, OWNER); // No owned versions: another writer's data is retained.
    for (String unsafe : new String[] {listing.replace(key + "</Prefix>", "other</Prefix>"),
        listing.replace("<IsTruncated>false</IsTruncated>", ""),
        "<!DOCTYPE x [<!ENTITY xxe SYSTEM 'file:///private'>]>" + listing,
        listing.replace("<VersionId>hidden-v1</VersionId>", "")}) {
      boolean failed = false;
      try { NanoBananaE2.listedVersions(unsafe.getBytes(java.nio.charset.StandardCharsets.UTF_8), key); }
      catch (Exception expected) { failed = true; }
      require(failed, "unsafe XML listing rejected");
    }
    require(replies.isEmpty(), "all expected requests consumed");
  }

  public static void main(String[] args) throws Exception {
    // https://docs.aws.amazon.com/AmazonS3/latest/developerguide/sigv4-query-string-auth.html
    String presigned = NanoBananaE2.presignGet("examplebucket.s3.amazonaws.com", "us-east-1", "/test.txt",
        ID, SECRET, Instant.parse("2013-05-24T00:00:00Z"), 86400);
    require(presigned.equals("https://examplebucket.s3.amazonaws.com/test.txt?X-Amz-Algorithm=AWS4-HMAC-SHA256"
        + "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"
        + "&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host"
        + "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404"),
        "AWS presigned GET fixed vector");

    // https://docs.aws.amazon.com/AmazonS3/latest/developerguide/sig-v4-header-based-auth.html
    String auth = NanoBananaE2.authorization("GET", "examplebucket.s3.amazonaws.com", "us-east-1", "/",
        "lifecycle=", EMPTY, "20130524T000000Z", ID, SECRET);
    require(auth.equals("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
        + "SignedHeaders=host;x-amz-content-sha256;x-amz-date,"
        + "Signature=fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543"),
        "AWS header signing fixed vector");
    require(NanoBananaE2.encode("a+/= z~").equals("a%2B%2F%3D%20z~"), "query escaping");
    require(NanoBananaE2.checkedVersion(null) == null, "unversioned header");
    require(NanoBananaE2.checkedVersion("null").equals("null"), "explicit null-version");
    require(NanoBananaE2.checkedVersion("a+/=").equals("a+/="), "opaque version preserved");
    for (String invalid : new String[] {"", "bad\nversion", " ", "x".repeat(1025)}) {
      boolean failed = false;
      try { NanoBananaE2.checkedVersion(invalid); }
      catch (java.io.IOException expected) { failed = true; }
      require(failed, "invalid version header");
    }

    NanoBananaE2 e2 = new NanoBananaE2("test-bucket", ID, SECRET);
    String safe = "loupe-nano-temp/12345678-1234-4234-9234-123456789abc.jpg";
    String url = e2.signedGet(safe, "accepted+version/=");
    require(url.startsWith("https://s3.ap-northeast-1.idrivee2.com/test-bucket/" + safe + "?"),
        "fixed Tokyo HTTPS destination");
    require(url.contains("X-Amz-Expires=1800&") && url.contains("%2Fap-northeast-1%2Fs3%2F"),
        "30-minute Tokyo scope");
    require(url.contains("&versionId=accepted%2Bversion%2F%3D&X-Amz-Signature="), "GET pinned to accepted version");
    failsIo(() -> e2.signedGet(safe, null), "versionless GET accepted");
    failsIo(() -> e2.signedGet(safe, "null"), "mutable null-version GET accepted");
    for (String key : new String[] {null, "", "originals/x.jpg", "loupe-nano-temp/../x.jpg", safe + "?x=y",
        safe.replace(".jpg", ".png"), safe.replace("loupe-nano-temp/", "loupe-nano-temp//"), safe.toUpperCase()}) {
      rejected(() -> e2.signedGet(key, "version"));
      rejected(() -> e2.upload(key, new byte[] {(byte)255, (byte)216, (byte)255, (byte)217}, OWNER));
      rejected(() -> e2.delete(key, null, OWNER));
    }
    rejected(() -> new NanoBananaE2("bad/host", ID, SECRET));
    rejected(() -> new NanoBananaE2("test-bucket", null, SECRET));
    rejected(() -> new NanoBananaE2("test-bucket", ID, null));
    rejected(() -> e2.upload(safe, null, OWNER));
    rejected(() -> e2.upload(safe, new byte[0], OWNER));
    rejected(() -> e2.upload(safe, new byte[5 * 1024 * 1024 + 1], OWNER));
    rejected(() -> e2.upload(safe, new byte[] {1,2,3,4}, OWNER));
    for (String invalidOwner : new String[] {null, "", "not-uuid", OWNER + "\n"}) {
      rejected(() -> e2.upload(safe, new byte[] {(byte)255, (byte)216, (byte)255, (byte)217}, invalidOwner));
      rejected(() -> e2.delete(safe, "version", invalidOwner));
    }
    String ownedAuth = NanoBananaE2.authorization("PUT", "examplebucket.s3.amazonaws.com", "us-east-1", "/",
        "", EMPTY, "20130524T000000Z", ID, SECRET, OWNER);
    require(ownedAuth.contains(";x-amz-meta-loupe-nano-owner,Signature=")
        && !ownedAuth.equals(NanoBananaE2.authorization("PUT", "examplebucket.s3.amazonaws.com", "us-east-1", "/",
        "", EMPTY, "20130524T000000Z", ID, SECRET, OTHER)), "owner participates in signature");
    transportChecks(e2, safe);
    System.out.println("NanoBananaE2Test PASS AWS vectors, owner-signed PUT, version-pinned GET, owner/version cleanup, preserved other writers/markers and mutable-version refusal; mock HTTPS only");
  }
}
