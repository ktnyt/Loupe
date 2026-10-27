package jp.nano.rawgallery;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Real JPEG encoder output in synthetic containers: independent of TIFF layout and camera names. */
public final class RawPreviewStructureTest {
  static int checks;
  static byte[] jpeg(int width, int height) throws Exception {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "jpeg", output);
    return output.toByteArray();
  }
  static byte[] join(byte[]... parts) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (byte[] part : parts) output.write(part);
    return output.toByteArray();
  }
  static RawPreview.Source source(byte[] data) {
    return new RawPreview.Source() {
      public long length() { return data.length; }
      public void read(long offset, byte[] buffer) throws IOException {
        if (offset < 0 || offset > data.length - buffer.length) throw new IOException("bounds");
        System.arraycopy(data, (int) offset, buffer, 0, buffer.length);
      }
    };
  }
  static void check(boolean success, String message) {
    if (!success) throw new AssertionError(message);
    checks++;
  }
  static void reject(byte[] data) throws Exception {
    try { RawPreview.inspect(source(data)); throw new AssertionError("accepted invalid JPEG"); }
    catch (IOException expected) { checks++; }
  }
  static void extracts(byte[] data, byte[] expected) throws Exception {
    RawPreview.Source source = source(data);
    RawPreview.Result result = RawPreview.inspect(source);
    check(Arrays.equals(result.jpeg(source), expected), "JPEG bytes changed");
    check(ImageIO.read(new ByteArrayInputStream(result.jpeg(source))) != null, "JPEG not decodable");
  }
  public static void main(String[] args) throws Exception {
    byte[] small = jpeg(16, 8), large = jpeg(128, 96);
    for (String extension : RawFormats.RAW_EXTENSIONS.split("\\|")) {
      check(RawFormats.accepts("camera." + extension.toUpperCase(Locale.ROOT)), extension);
      check(RawFormats.originalExtension("camera." + extension).equals(extension), "extension preserved");
    }
    check(!RawFormats.accepts("image.jpg.exe"), "allowlist");
    check(!RawFormats.accepts(null), "null name");
    extracts(join(new byte[37], small, new byte[17], large, new byte[101]), large);
    extracts(join(new byte[65535], large), large); // SOI straddles a read boundary
    extracts(join(new byte[65534], large), large);
    extracts(join(new byte[65536], large, new byte[65535], small), large);
    extracts(join(new byte[65535], small, new byte[65535], large), large);
    extracts(join(large, small), large); // Resume directly at the next complete stream.
    extracts(join(new byte[2 * 1024 * 1024], large), large); // no prefix-only search
    // A corrupt TIFF directory does not hide a valid preview.
    extracts(join(new byte[] {'I','I',42,0,-1,-1,-1,127}, large), large);
    // APP payload contains JPEG-looking bytes and EOI; segment lengths must be respected.
    byte[] app = join(new byte[] {-1,-40,-1,-31,0,(byte)(small.length + 2 >> 0)}, small);
    app[4] = (byte)((small.length + 2) >> 8);
    extracts(join(app, Arrays.copyOfRange(large, 2, large.length)),
        join(app, Arrays.copyOfRange(large, 2, large.length)));
    // A progressive JPEG has multiple SOS sections; the scanner must reach its final EOI.
    var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
    var progressiveBytes = new ByteArrayOutputStream();
    try (var output = ImageIO.createImageOutputStream(progressiveBytes)) {
      writer.setOutput(output);
      var options = writer.getDefaultWriteParam();
      options.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
      writer.write(null, new javax.imageio.IIOImage(
          new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), null, null), options);
    } finally { writer.dispose(); }
    extracts(join(new byte[13], progressiveBytes.toByteArray()), progressiveBytes.toByteArray());
    // Embedded EXIF orientation, both byte orders, in a non-TIFF container (e.g. RAF).
    for (boolean little : new boolean[] {true, false}) {
      var tiff = java.nio.ByteBuffer.allocate(26).order(little
          ? java.nio.ByteOrder.LITTLE_ENDIAN : java.nio.ByteOrder.BIG_ENDIAN);
      tiff.put((byte)(little ? 'I' : 'M')).put((byte)(little ? 'I' : 'M'));
      tiff.putShort((short)42).putInt(8).putShort((short)1);
      tiff.putShort((short)274).putShort((short)3).putInt(1).putShort((short)6).putShort((short)0).putInt(0);
      byte[] withExif = join(new byte[] {-1,-40,-1,-31,0,34,'E','x','i','f',0,0},
          tiff.array(), Arrays.copyOfRange(large, 2, large.length));
      check(RawPreview.inspect(source(join(new byte[84], withExif))).orientation == 6, "embedded orientation");
    }
    // Known Minolta signature permits the historical first-byte correction; unknown data does not.
    byte[] quantizationFirst = Arrays.copyOfRange(large, 20, large.length);
    byte[] damagedHeader = join(new byte[] {(byte)238, (byte)216}, quantizationFirst);
    byte[] repaired = damagedHeader.clone(); repaired[0] = (byte)255;
    extracts(join(new byte[] {0,'M','R','M',0,0,0,0}, damagedHeader), repaired);
    check(RawPreview.inspect(source(join(new byte[] {0,'M','R','M',0,0,0,0}, damagedHeader)))
        .repairedHeader, "repair reported");
    reject(damagedHeader);
    extracts(join(new byte[] {0,'M','R','M',0,0,0,0}, new byte[65527], damagedHeader), repaired);
    extracts(join(new byte[] {0,'M','R','M',0,0,0,0}, new byte[65528], damagedHeader), repaired);
    reject(new byte[0]);
    reject(Arrays.copyOf(large, large.length - 2));
    reject(new byte[] {-1,-40,-1,-39});
    byte[] lossless = large.clone();
    for (int i = 0; i < lossless.length - 1; i++)
      if ((lossless[i] & 255) == 255 && (lossless[i+1] & 255) == 192) { lossless[i+1] = (byte)195; break; }
    reject(lossless);
    extracts(join(lossless, small), small);
    byte[] invalidLength = large.clone(); invalidLength[4] = 0; invalidLength[5] = 1;
    reject(invalidLength);
    Thread.currentThread().interrupt();
    try { RawPreview.inspect(source(large)); throw new AssertionError("cancellation ignored"); }
    catch (InterruptedIOException expected) { checks++; }
    finally { Thread.interrupted(); }
    Thread.currentThread().interrupt();
    try { RawPreview.inspect(source(new byte[65537])); throw new AssertionError("empty scan ignored cancellation"); }
    catch (InterruptedIOException expected) { checks++; }
    finally { Thread.interrupted(); }
    // Source failures must never be converted into a successful extraction.
    try {
      RawPreview.inspect(new RawPreview.Source() {
        public long length() { return 100; }
        public void read(long offset, byte[] buffer) throws IOException { throw new IOException("source failure"); }
      });
      throw new AssertionError("source error swallowed");
    } catch (IOException expected) { check(expected.getMessage().equals("source failure"), "source error"); }
    System.out.println("Passed " + checks + " checks");
  }
}
