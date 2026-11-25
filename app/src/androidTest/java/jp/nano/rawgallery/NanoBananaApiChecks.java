package jp.nano.rawgallery;

import static jp.nano.rawgallery.TagDetailChecks.require;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.Arrays;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONException;
import org.json.JSONObject;

/** Exercises the actual HTTP client without DNS, sockets or provider credentials. */
final class NanoBananaApiChecks {
  private static final String KEY = "synthetic-key";
  private static final String DETAIL = "synthetic-private-detail";
  private static final String API_FAILURE = "APIとの通信に失敗しました。APIキー・残高・通信を確認し、状態を再確認してください";
  private static final String DOWNLOAD_FAILURE = "結果画像を取得できませんでした。状態を再確認してください";
  static final String RATE_LIMIT = "APIの利用上限に達しました。時間をおいて状態を確認してください";

  static void run() throws Exception {
    String imageUrl = "https://synthetic.invalid/input.jpg?token=" + DETAIL;
    Response post = new Response(200, "{\"code\":200,\"data\":{\"taskId\":\"fixture-task\"}}");
    require(
        new NanoBananaApi(KEY, post::open)
            .generate("fixture", imageUrl, NanoBananaOutput.DEFAULT)
            .equals("fixture-task"),
        "POST returns the provider task ID");
    require(
        post.url.getPath().endsWith("/generate-2")
            && post.connection.getRequestMethod().equals("POST")
            && post.connection.getRequestProperty("Authorization").equals("Bearer " + KEY)
            && new JSONObject(post.connection.request.toString(StandardCharsets.UTF_8.name()))
                .getJSONArray("imageUrls")
                .getString(0)
                .equals(imageUrl),
        "POST still sends the configured key and request body to the API");
    post.verifyClosed();

    Response get = new Response(200, "{\"code\":200,\"data\":{\"successFlag\":1}}");
    require(
        new NanoBananaApi(KEY, get::open).status("fixture+task&id").getInt("successFlag") == 1
            && get.url.getQuery().equals("taskId=fixture%2Btask%26id")
            && get.connection.getRequestMethod().equals("GET"),
        "status returns data and encodes the task ID");
    get.verifyClosed();

    verifyStatusFailure(401, "APIキーまたは利用権限を確認してください");
    verifyStatusFailure(403, "APIキーまたは利用権限を確認してください");
    verifyStatusFailure(429, RATE_LIMIT);
    verifyStatusFailure(503, "APIとの通信に失敗しました (HTTP 503)");

    Response rejected = new Response(200, "{\"code\":402,\"message\":\"" + DETAIL + "\"}");
    IOException rejection = failure(() -> new NanoBananaApi(KEY, rejected::open).status(DETAIL));
    verifyMessage(rejection, "APIが処理を受け付けませんでした。キーと残高を確認してください");
    require(rejection.getCause() == null, "API rejection keeps its specific error");
    rejected.verifyClosed();

    Response badJson = new Response(200, "{" + DETAIL);
    IOException parseFailure = failure(() -> new NanoBananaApi(KEY, badJson::open).status(DETAIL));
    verifyMessage(parseFailure, API_FAILURE);
    require(parseFailure.getCause() instanceof JSONException, "JSON failure keeps its cause");
    badJson.verifyClosed();

    Response missingTask =
        new Response(200, "{\"code\":200,\"data\":{\"message\":\"" + DETAIL + "\"}}");
    IOException taskFailure =
        failure(
            () ->
                new NanoBananaApi(KEY, missingTask::open)
                    .generate("fixture", imageUrl, NanoBananaOutput.DEFAULT));
    verifyMessage(taskFailure, "生成タスクの応答を確認できませんでした");
    require(taskFailure.getCause() instanceof JSONException, "task parse failure keeps its cause");
    missingTask.verifyClosed();

    IOException transport = new IOException(KEY + " " + imageUrl);
    Response lostPost = new Response(200, "", transport);
    IOException postFailure =
        failure(
            () ->
                new NanoBananaApi(KEY, lostPost::open)
                    .generate("fixture", imageUrl, NanoBananaOutput.DEFAULT));
    verifyMessage(postFailure, API_FAILURE);
    require(
        postFailure.getCause() == transport && lostPost.connection.request.size() > 0,
        "ambiguous POST keeps the original cause after sending its body");
    lostPost.verifyClosed();

    IOException openFailure =
        failure(
            () ->
                new NanoBananaApi(
                        KEY,
                        url -> {
                          throw transport;
                        })
                    .status(DETAIL));
    verifyMessage(openFailure, API_FAILURE);
    require(openFailure.getCause() == transport, "opening the API connection keeps the cause");

    String resultUrl = "https://file.aiquickdraw.com/result.jpg?token=" + DETAIL;
    Response download = new Response(200, "synthetic-image-bytes");
    require(
        Arrays.equals(NanoBananaApi.download(resultUrl, download::open), download.body),
        "download returns original bytes");
    require(
        download.connection.getRequestProperty("Authorization") == null,
        "download omits API credentials");
    download.verifyClosed();

    Response failedDownload = new Response(200, "", transport);
    IOException downloadFailure =
        failure(() -> NanoBananaApi.download(resultUrl, failedDownload::open));
    verifyMessage(downloadFailure, DOWNLOAD_FAILURE);
    require(downloadFailure.getCause() == transport, "download keeps its original I/O cause");
    failedDownload.verifyClosed();

    Response redirect = new Response(302, "");
    verifyMessage(
        failure(() -> NanoBananaApi.download(resultUrl, redirect::open)), DOWNLOAD_FAILURE);
    redirect.verifyClosed();
    verifyMessage(
        failure(
            () ->
                NanoBananaApi.download(
                    "not-a-url?token=" + DETAIL,
                    url -> {
                      throw new AssertionError("malformed result URL must never connect");
                    })),
        DOWNLOAD_FAILURE);
    verifyMessage(
        failure(
            () ->
                NanoBananaApi.download(
                    "https://synthetic.invalid/" + DETAIL,
                    url -> {
                      throw new AssertionError("unapproved CDN host must never connect");
                    })),
        "結果画像の接続先に対応していません");
  }

  static IOException verifyStatusFailure(int code, String message) throws Exception {
    Response response = new Response(code, DETAIL);
    IOException error = failure(() -> new NanoBananaApi(KEY, response::open).status(DETAIL));
    verifyMessage(error, message);
    require(error.getCause() == null, "HTTP failure keeps its specific error");
    response.verifyClosed();
    return error;
  }

  private static void verifyMessage(IOException error, String message) {
    require(
        error instanceof NanoBananaApi.SafeFailure && message.equals(error.getMessage()),
        "public API failure is the expected fixed text");
  }

  private interface Request {
    void run() throws Exception;
  }

  private static IOException failure(Request request) throws Exception {
    try {
      request.run();
    } catch (IOException expected) {
      return expected;
    }
    throw new AssertionError("expected request failure");
  }

  private static final class Response {
    private final int code;
    final byte[] body;
    private final IOException transport;
    URL url;
    private FakeConnection connection;

    Response(int code, String body) {
      this(code, body, null);
    }

    Response(int code, String body, IOException transport) {
      this.code = code;
      this.body = body.getBytes(StandardCharsets.UTF_8);
      this.transport = transport;
    }

    HttpsURLConnection open(URL url) {
      require(connection == null, "a provider request opens only one HTTPS connection");
      this.url = url;
      return connection = new FakeConnection(url);
    }

    void verifyClosed() {
      require(
          connection != null && connection.disconnects == 1,
          "HTTPS connection is disconnected exactly once");
      require(
          !connection.getInstanceFollowRedirects()
              && !connection.getUseCaches()
              && connection.getConnectTimeout() == 15000
              && connection.getReadTimeout() == 45000,
          "HTTPS request keeps its redirect, cache and timeout restrictions");
      require(connection.responses == 1, "HTTP response is requested exactly once");
    }

    private final class FakeConnection extends HttpsURLConnection {
      final ByteArrayOutputStream request = new ByteArrayOutputStream();
      int disconnects;
      int responses;

      FakeConnection(URL url) {
        super(url);
      }

      @Override
      public void connect() {
        throw new AssertionError("unexpected real connection");
      }

      @Override
      public void disconnect() {
        disconnects++;
      }

      @Override
      public boolean usingProxy() {
        return false;
      }

      @Override
      public String getCipherSuite() {
        return "synthetic";
      }

      @Override
      public Certificate[] getLocalCertificates() {
        return null;
      }

      @Override
      public Certificate[] getServerCertificates() {
        return null;
      }

      @Override
      public OutputStream getOutputStream() {
        return request;
      }

      @Override
      public int getResponseCode() {
        responses++;
        return code;
      }

      @Override
      public InputStream getInputStream() throws IOException {
        if (transport != null) throw transport;
        return new ByteArrayInputStream(body);
      }
    }
  }
}
