import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import jp.nano.rawgallery.camera.Ep7WifiClient;

public final class Ep7WifiClientTest {
  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static void fails(Checked operation) throws Exception {
    try {
      operation.run();
      throw new AssertionError("expected failure");
    } catch (IOException expected) {
    }
  }

  interface Checked {
    void run() throws Exception;
  }

  static String file(long size) {
    return "VER_100\n/DCIM/100OLYMP,P1010001.ORF," + size + ",32,20000,10000\n";
  }

  public static void main(String[] args) throws Exception {
    var row = Ep7WifiClient.parseList(file(100), "/DCIM/100OLYMP").get(0);
    require(row.bytes == 100 && row.photo() && !row.directory, "ORF metadata");
    for (String bad :
        List.of(
            "bad",
            "VER_100\n/DCIM,../secret,1,32,0,0\n",
            "VER_100\n/DCIM,a.jpg,-1,32,0,0\n",
            "VER_100\n/OTHER,a.jpg,1,32,0,0\n",
            "VER_100\n/DCIM,a.jpg,1,32,0,0\n/DCIM,a.jpg,1,32,0,0\n",
            "VER_100\n/DCIM,a.jpg,1,32,0\n")) fails(() -> Ep7WifiClient.parseList(bad, "/DCIM"));
    StringBuilder hidden = new StringBuilder("VER_100\n");
    for (int i = 0; i < 20001; i++) hidden.append("/DCIM,H").append(i).append(".JPG,1,2,0,0\n");
    fails(() -> Ep7WifiClient.parseList(hidden.toString(), "/DCIM"));
    AtomicInteger mode = new AtomicInteger();
    AtomicInteger gets = new AtomicInteger();
    AtomicInteger subdirectories = new AtomicInteger();
    byte[] content = new byte[65537];
    new Random(123).nextBytes(content);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try {
            require(exchange.getRequestMethod().equals("GET"), "GET only");
            require(
                "OlympusCameraKit".equals(exchange.getRequestHeaders().getFirst("User-Agent")),
                "OPC user agent");
            String path = exchange.getRequestURI().getPath();
            byte[] response;
            if (path.equals("/get_imglist.cgi")) {
              String dir =
                  URLDecoder.decode(exchange.getRequestURI().getQuery().substring(4), "UTF-8");
              if (!dir.equals("/DCIM")) subdirectories.incrementAndGet();
              response =
                  (dir.equals("/DCIM")
                          ? "VER_100\n/DCIM,100OLYMP,0,16,0,0\n"
                          : file(content.length))
                      .getBytes();
            } else {
              require(path.equals("/DCIM/100OLYMP/P1010001.ORF"), "original path only");
              gets.incrementAndGet();
              response = content;
              if (mode.get() == 1) response = Arrays.copyOf(content, 100);
              if (mode.get() == 2) {
                exchange.getResponseHeaders().add("Location", "http://example.com/");
                exchange.sendResponseHeaders(302, -1);
                return;
              }
              if (mode.get() == 3) response = Arrays.copyOf(content, content.length + 1);
              if (mode.get() == 4) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(content, 0, 100);
                return;
              }
            }
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
          } finally {
            exchange.close();
          }
        });
    server.start();
    File dir = Files.createTempDirectory("ep7-test-").toFile();
    AtomicBoolean cancel = new AtomicBoolean();
    try {
      Ep7WifiClient client =
          new Ep7WifiClient(
              url -> {
                require(
                    url.getHost().equals(Ep7WifiClient.HOST) && url.getProtocol().equals("http"),
                    "fixed camera origin");
                return (HttpURLConnection)
                    new URL("http://127.0.0.1:" + server.getAddress().getPort() + url.getFile())
                        .openConnection(Proxy.NO_PROXY);
              },
              cancel::get);
      client.probe();
      require(subdirectories.get() == 0, "connection probe must not recurse");
      var listing = client.list();
      require(listing.photos.size() == 1 && listing.seconds > 0, "recursive list");
      var entry = listing.photos.get(0);
      ByteArrayOutputStream streamed = new ByteArrayOutputStream();
      client.copyOriginal(entry, streamed);
      require(Arrays.equals(content, streamed.toByteArray()), "common import original-byte stream");
      var result = client.download(entry, dir);
      require(
          Arrays.equals(content, Files.readAllBytes(result.file.toPath())),
          "saved bytes read back");
      require(
          result.sha256.length() == 64
              && result.bytes == content.length
              && result.transferSeconds > 0
              && result.totalSeconds >= result.transferSeconds
              && result.mibPerSecond() > 0,
          "metrics");
      var again = client.download(entry, dir);
      require(!again.file.equals(result.file) && result.file.exists(), "no overwrite");
      for (int m : new int[] {1, 2, 3, 4}) {
        mode.set(m);
        fails(() -> client.download(entry, dir));
        fails(() -> client.copyOriginal(entry, new ByteArrayOutputStream()));
        require(
            Objects.requireNonNull(dir.list()).length == 2,
            "failed copies removed, previous successes retained");
      }
      mode.set(0);
      AtomicInteger reads = new AtomicInteger();
      Ep7WifiClient midCancel =
          new Ep7WifiClient(
              url ->
                  (HttpURLConnection)
                      new URL("http://127.0.0.1:" + server.getAddress().getPort() + url.getFile())
                          .openConnection(),
              () -> reads.incrementAndGet() > 2);
      fails(() -> midCancel.download(entry, dir));
      require(Objects.requireNonNull(dir.list()).length == 2, "mid-transfer cancel cleans partial");
      var retry = client.download(entry, dir);
      require(retry.file.exists(), "success after failed transfer");
      require(retry.file.delete(), "remove successful retry");
      cancel.set(true);
      int before = gets.get();
      fails(() -> client.list());
      fails(() -> client.download(entry, dir));
      require(gets.get() == before, "pre-cancel no HTTP");
      require(Objects.requireNonNull(dir.list()).length == 2, "cancel creates no temp copy");
    } finally {
      server.stop(0);
      for (File f : Objects.requireNonNull(dir.listFiles())) require(f.delete(), "remove fixture");
      require(dir.delete(), "remove directory");
    }
    System.out.println(
        "PASS OPC recursive listing, malformed/traversal/duplicate entries, fixed origin, original-byte download, SHA readback, metrics, redirect/short/oversize rejection, cancellation, cleanup and retry");
  }
}
