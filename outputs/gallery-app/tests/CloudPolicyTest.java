package jp.nano.rawgallery;

import java.net.URL;

public final class CloudPolicyTest {
  public static void main(String[] args) throws Exception {
    String[] allowed = {
      "https://www.amazon.co.jp/drive/v1/nodes",
      "https://content-fe.drive.amazonaws.com/raw",
      "https://content-na.drive.amazonaws.com/raw",
      "https://bucket.s3.ap-northeast-1.amazonaws.com/raw"
    };
    String[] denied = {
      "http://www.amazon.co.jp/drive/v1/nodes",
      "https://www.amazon.co.jp.attacker.example/raw",
      "https://drive.amazonaws.com.attacker.example/raw",
      "https://content-fe.drive.amazonaws.com:444/raw",
      "https://secret@content-fe.drive.amazonaws.com/raw",
      "https://127.0.0.1/raw",
      "https://example.com/raw"
    };
    for (String s : allowed)
      if (!CloudVerifier.safeDownload(new URL(s))) throw new AssertionError(s);
    for (String s : denied) if (CloudVerifier.safeDownload(new URL(s))) throw new AssertionError(s);
    if (CloudVerifier.isOrigin(new URL(allowed[1])))
      throw new AssertionError("Credentials crossed origin");
    if (!CloudVerifier.isOrigin(new URL(allowed[0]))) throw new AssertionError("Origin rejected");
    for (String path : new String[] {"/", "/cdproxy/", "/drive/v1/"})
      if (!CloudVerifier.uploadEndpointAllowed(
          new URL("https://content-jp.drive.amazonaws.com" + path))) throw new AssertionError(path);
    for (String endpoint :
        new String[] {
          "http://content-jp.drive.amazonaws.com/",
          "https://content-na.drive.amazonaws.com/",
          "https://content-jp.drive.amazonaws.com.attacker.example/",
          "https://user@content-jp.drive.amazonaws.com/",
          "https://content-jp.drive.amazonaws.com:444/",
          "https://content-jp.drive.amazonaws.com/?token=x"
        })
      if (CloudVerifier.uploadEndpointAllowed(new URL(endpoint)))
        throw new AssertionError(endpoint);
    System.out.println("Cloud URL policy: 22 checks passed");
  }
}
