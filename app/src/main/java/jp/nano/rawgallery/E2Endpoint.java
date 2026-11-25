package jp.nano.rawgallery;

/** Fixed HTTPS destination and signing region for IDrive e2 requests. */
final class E2Endpoint {
  static final String REGION = "ap-northeast-1";
  static final String HOST = "s3." + REGION + ".idrivee2.com";

  private E2Endpoint() {}
}
