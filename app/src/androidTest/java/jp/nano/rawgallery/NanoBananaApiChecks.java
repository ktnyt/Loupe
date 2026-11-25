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
  private static final String SUBMIT_RECOVERY = "AI編集メニューから「処理を終了」し、必要に応じて「設定」を確認してください";
  private static final String SUBMIT_FAILURE = "生成依頼の結果を確認できませんでした。" + SUBMIT_RECOVERY;
  private static final String DOWNLOAD_FAILURE = "結果画像を取得できませんでした。状態を再確認してください";
  static final String RATE_LIMIT = "APIの利用上限に達しました。時間をおいて状態を確認してください";
  static final String SUBMIT_RATE_LIMIT = "APIの利用上限に達しました。" + SUBMIT_RECOVERY;

  static NanoBananaApi rateLimitedApi() {
    return new NanoBananaApi(KEY, new Response(429, DETAIL)::open);
  }

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
    verifyMessage(
        failure(() -> rateLimitedApi().generate("fixture", imageUrl, NanoBananaOutput.DEFAULT)),
        SUBMIT_RATE_LIMIT);

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

    for (String body :
        Arrays.asList(
            "{\"data\":{}}",
            "{\"code\":null}",
            "{\"code\":true}",
            "{\"code\":\"" + DETAIL + "\"}",
            "{\"code\":\"200\"}")) {
      Response badCode = new Response(200, body);
      IOException error = failure(() -> new NanoBananaApi(KEY, badCode::open).status(DETAIL));
      verifyMessage(error, API_FAILURE);
      require(error.getCause() instanceof JSONException, "invalid API code keeps its JSON cause");
      badCode.verifyClosed();
    }

    Response badSubmitJson = new Response(200, "{" + DETAIL);
    IOException submitParseFailure =
        failure(
            () ->
                new NanoBananaApi(KEY, badSubmitJson::open)
                    .generate("fixture", imageUrl, NanoBananaOutput.DEFAULT));
    verifyMessage(submitParseFailure, SUBMIT_FAILURE);
    require(
        submitParseFailure.getCause() instanceof JSONException,
        "POST parsing keeps its JSON cause");
    badSubmitJson.verifyClosed();

    for (String data :
        Arrays.asList(
            "{}", "{\"taskId\":null}", "{\"taskId\":123}", "{\"taskId\":\"?" + DETAIL + "\"}")) {
      Response badTask = new Response(200, "{\"code\":200,\"data\":" + data + "}");
      IOException taskFailure =
          failure(
              () ->
                  new NanoBananaApi(KEY, badTask::open)
                      .generate("fixture", imageUrl, NanoBananaOutput.DEFAULT));
      verifyMessage(taskFailure, "生成タスクの応答を確認できませんでした。" + SUBMIT_RECOVERY);
      require(
          taskFailure.getCause() instanceof JSONException, "invalid task ID keeps its JSON cause");
      badTask.verifyClosed();
    }

    IOException transport = new IOException(KEY + " " + imageUrl);
    Response lostPost = new Response(200, "{\"code\":", transport);
    IOException postFailure =
        failure(
            () ->
                new NanoBananaApi(KEY, lostPost::open)
                    .generate("fixture", imageUrl, NanoBananaOutput.DEFAULT));
    verifyMessage(postFailure, SUBMIT_FAILURE);
    require(
        postFailure.getCause() == transport && lostPost.connection.request.size() > 0,
        "ambiguous POST keeps the original cause after sending its body");
    lostPost.verifyReadFailureClosed();

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

    Response failedDownload = new Response(200, "partial-image-bytes", transport);
    IOException downloadFailure =
        failure(() -> NanoBananaApi.download(resultUrl, failedDownload::open));
    verifyMessage(downloadFailure, DOWNLOAD_FAILURE);
    require(downloadFailure.getCause() == transport, "download keeps its original I/O cause");
    failedDownload.verifyReadFailureClosed();

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

    void verifyReadFailureClosed() {
      verifyClosed();
      require(
          connection.reads == 2 && connection.streamCloses == 1,
          "response read succeeds once before failing and the stream closes once");
    }

    private final class FakeConnection extends HttpsURLConnection {
      final ByteArrayOutputStream request = new ByteArrayOutputStream();
      int disconnects;
      int responses;
      int reads;
      int streamCloses;

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
      public InputStream getInputStream() {
        ByteArrayInputStream stream = new ByteArrayInputStream(body);
        if (transport == null) return stream;
        return new InputStream() {
          @Override
          public int read() {
            throw new AssertionError("expected a bulk read");
          }

          @Override
          public int read(byte[] bytes, int offset, int length) throws IOException {
            if (++reads > 1) throw transport;
            return stream.read(bytes, offset, length);
          }

          @Override
          public void close() throws IOException {
            streamCloses++;
            stream.close();
          }
        };
      }
    }
  }
}
