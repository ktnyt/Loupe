import jp.nano.rawgallery.RawPreview;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.*;

/** Reports extraction and byte identity against independently extracted reference JPEGs. */
public final class RawPreviewCorpusTest {
  public static void main(String[] args) throws Exception {
    File root = new File(args[0]);
    File output = new File(args[1]); output.mkdirs();
    File[] files = root.listFiles(f -> f.getName().startsWith("sample."));
    Arrays.sort(files);
    int matched = 0, absent = 0, failures = 0;
    for (File file : files) {
      String extension = file.getName().substring(7);
      long start = System.nanoTime();
      File[] references = new File(root, "references").listFiles(f -> f.getName().startsWith(extension + "-"));
      byte[] best = null, leafReference = null; long area = 0;
      if (references != null) for (File ref : references) {
        byte[] bytes = Files.readAllBytes(ref.toPath());
        if (bytes.length < 2 || (bytes[0] & 255) != 255 || (bytes[1] & 255) != 216) continue;
        if (extension.equals("mos")) leafReference = bytes;
        try {
          BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
          if (image != null && (long) image.getWidth() * image.getHeight() > area) {
            area = (long) image.getWidth() * image.getHeight(); best = bytes;
          }
        } catch (IOException ignored) { }
      }
      try (RawPreview.FileSource source = new RawPreview.FileSource(file)) {
        RawPreview.Result result = RawPreview.inspect(source);
        byte[] jpeg = result.jpeg(source);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
        if (image == null || image.getWidth() != result.width || image.getHeight() != result.height)
          throw new AssertionError("JPEG decode failed");
        Files.write(new File(output, extension + ".jpg").toPath(), jpeg);
        // ExifTool may include padding after EOI. Compare the complete JPEG prefix.
        boolean same = best != null && best.length >= jpeg.length
            && Arrays.equals(Arrays.copyOf(best, jpeg.length), jpeg);
        // ExifTool 13.55 Leaf.pm translates LF to spaces even in the binary preview.
        // Verify its declared length AND every byte against that known transformation.
        boolean leaf = leafReference != null && leafReference.length == jpeg.length;
        if (leaf) for (int i = 0; i < jpeg.length; i++)
          if (leafReference[i] != (jpeg[i] == 10 ? 32 : jpeg[i])) { leaf = false; break; }
        same |= leaf;
        if (!same) failures++; else matched++;
        System.out.printf("%s\t%s\t%dx%d\t%d bytes\t%d ms\t%s\torientation=%d%n",
            extension, leaf ? "MATCH_LEAF_LF_NORMALIZATION" : same ? "MATCH" : "REFERENCE_MISMATCH", result.width, result.height,
            result.length, (System.nanoTime()-start)/1_000_000, result.model, result.orientation);
      } catch (IOException error) {
        if (best != null) failures++; else absent++;
        System.out.println(extension + "\t" + (best == null ? "NO_JPEG" : "MISSED_JPEG") + "\t" + error.getMessage());
      }
    }
    System.out.printf("Matched=%d NoReferenceJPEG=%d Failures=%d%n", matched, absent, failures);
    if (failures != 0) throw new AssertionError("Corpus failures: " + failures);
  }
}
