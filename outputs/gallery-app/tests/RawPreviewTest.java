import java.io.*;
import java.nio.file.*;
import java.util.*;
import jp.nano.rawgallery.RawPreview;

public class RawPreviewTest {
  static int passed;

  public static void main(String[] args) throws Exception {
    File[] samples = new File(args[0]).listFiles();
    if (samples == null || samples.length == 0) throw new AssertionError("Samples missing");
    for (File sample : samples) {
      try (RawPreview.FileSource source = new RawPreview.FileSource(sample)) {
        RawPreview.Result result = RawPreview.inspect(source);
        String stem = sample.getName().replaceFirst("\\.[^.]+$", "");
        byte[] reference = Files.readAllBytes(Path.of(args[1], stem + ".jpg"));
        if (!Arrays.equals(result.jpeg(source), reference))
          throw new AssertionError(sample + " JPEG mismatch");
        System.out.println(
            "PASS "
                + sample.getName()
                + " "
                + result.model
                + " "
                + result.width
                + "x"
                + result.height
                + " orientation="
                + result.orientation);
        passed++;
      }
    }
    reject(new byte[0]);
    reject(new byte[] {'I', 'I', 42, 0, (byte) 255, (byte) 255, (byte) 255, 127});
    reject(new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0, 8, 0, 0, 0});
    reject(new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8, (byte) 255, (byte) 255, 0, 0, 0, 0});
    System.out.println("Passed " + passed + " checks");
  }

  static void reject(byte[] bytes) throws Exception {
    try {
      RawPreview.inspect(
          new RawPreview.Source() {
            public long length() {
              return bytes.length;
            }

            public void read(long p, byte[] dst) throws IOException {
              if (p < 0 || p + dst.length > bytes.length) throw new IOException("bounds");
              System.arraycopy(bytes, (int) p, dst, 0, dst.length);
            }
          });
      throw new AssertionError("malformed file accepted");
    } catch (IOException expected) {
      passed++;
    }
  }
}
