package jp.nano.rawgallery;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Real temporary files with an injected original-copy boundary; no Android or network I/O. */
public final class SharePreparationTest {
  private static final long LIMIT = 512L * 1024 * 1024;

  public static void main(String[] args) throws Exception {
    File root = Files.createTempDirectory("loupe-share-test-").toFile();
    try {
      List<PhotoRepository.Photo> photos = List.of(photo("a.ORF"), photo("b.dng"), photo("c.jpg"));
      File success = new File(root, "success");
      List<String> copied = new ArrayList<>();
      SharePreparation preparation =
          new SharePreparation(
              success,
              (photo, raw, destination) -> {
                check(raw, "original format passed to copy boundary");
                copied.add(photo.name);
                Files.writeString(destination.toPath(), photo.name);
              });
      SharePreparation.Result result = preparation.prepare(photos, true);
      check(copied.equals(List.of("a.ORF", "b.dng", "c.jpg")), "selection order");
      check(result.mimeType.equals("image/*"), "mixed raw MIME");
      for (int i = 0; i < result.files.size(); i++) {
        File file = result.files.get(i);
        check(Files.readString(file.toPath()).equals(photos.get(i).name), "ordered complete copies");
        check(file.getName().matches("[a-f0-9-]{36}\\.(orf|dng|jpg)"), "provider-compatible name");
      }
      check(result.files.get(0).getName().endsWith(".orf"), "ORF extension");
      check(result.files.get(1).getName().endsWith(".dng"), "DNG extension");
      check(result.files.get(2).getName().endsWith(".jpg"), "JPEG extension");
      try {
        result.files.clear();
        throw new AssertionError("mutable result");
      } catch (UnsupportedOperationException expected) {
      }
      for (String name : List.of("one.ORF", "one.dng", "one.jpg")) {
        SharePreparation.Result single = preparation.prepare(List.of(photo(name)), true);
        String expected = name.endsWith("ORF") ? "image/x-olympus-orf"
            : name.endsWith("dng") ? "image/x-adobe-dng" : "image/jpeg";
        check(single.mimeType.equals(expected), "single format MIME");
      }
      for (String extension : RawFormats.RAW_EXTENSIONS.split("\\|")) {
        SharePreparation.Result single = preparation.prepare(List.of(photo("one." + extension)), true);
        check(single.files.get(0).getName().endsWith("." + extension), "original extension " + extension);
        check(single.mimeType.equals(RawFormats.mime("one." + extension)), "original MIME " + extension);
      }
      check(preparation.prepare(List.of(photo("one.iiq"), photo("two.cr3")), true)
          .mimeType.equals("*/*"), "mixed binary and image MIME");
      SharePreparation.Result jpeg =
          new SharePreparation(new File(root, "jpeg"), (photo, raw, destination) -> {
            check(!raw, "JPEG format passed to copy boundary");
            Files.writeString(destination.toPath(), "preview");
          }).prepare(photos, false);
      check(jpeg.mimeType.equals("image/jpeg"), "JPEG MIME");
      check(jpeg.files.stream().allMatch(f -> f.getName().endsWith(".jpg")), "JPEG extensions");

      File failure = new File(root, "failure");
      failure.mkdirs();
      File previous = new File(failure, "previous.jpg");
      Files.writeString(previous.toPath(), "previous share");
      int[] attempts = {0};
      expectFailure(
          new SharePreparation(failure, (photo, raw, destination) -> {
            Files.writeString(destination.toPath(), "partial");
            if (++attempts[0] == 2) throw new IOException("source unavailable");
          }), photos);
      check(attempts[0] == 2, "no remaining source fetched after failure");
      check(Arrays.equals(failure.list(), new String[] {"previous.jpg"}), "only this attempt removed");
      check(Files.readString(previous.toPath()).equals("previous share"), "prior copy unchanged");

      File capacity = new File(root, "capacity");
      expectFailure(new SharePreparation(capacity, (photo, raw, destination) -> {
        try (RandomAccessFile file = new RandomAccessFile(destination, "rw")) {
          file.setLength(LIMIT + 1); // Sparse file: no 512 MiB buffer allocation.
        }
      }), List.of(photo("large.dng")));
      check(capacity.list().length == 0, "oversized result removed");
      File existing = new File(capacity, "existing.jpg");
      try (RandomAccessFile file = new RandomAccessFile(existing, "rw")) {
        file.setLength(LIMIT);
      }
      expectFailure(new SharePreparation(capacity, (photo, raw, destination) -> {
        Files.write(destination.toPath(), new byte[] {1});
      }), List.of(photo("one.jpg")));
      check(capacity.list().length == 1 && existing.length() == LIMIT, "existing bytes count toward limit");
      check(existing.setLastModified(System.currentTimeMillis() - 25L * 60 * 60 * 1000), "age fixture");
      new SharePreparation(capacity, (photo, raw, destination) -> {
        Files.write(destination.toPath(), new byte[] {1});
      }).prepare(List.of(photo("one.jpg")), true);
      check(!existing.exists(), "expired cache removed before capacity check");

      File interrupted = new File(root, "interrupted");
      try {
        new SharePreparation(interrupted, (photo, raw, destination) -> {
          Files.writeString(destination.toPath(), "first complete");
          Thread.currentThread().interrupt();
        }).prepare(photos, true);
        throw new AssertionError("interrupt ignored");
      } catch (InterruptedException expected) {
        check(interrupted.list().length == 0, "completed copy removed on interruption");
      } finally {
        Thread.interrupted();
      }
      System.out.println("Share preparation: ordering, formats, partial failure, capacity, expiry and interruption passed");
    } finally {
      try (var paths = Files.walk(root.toPath())) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  private static PhotoRepository.Photo photo(String name) {
    return new PhotoRepository.Photo(null, name, "test/", 1, 0, 0);
  }

  private static void expectFailure(SharePreparation preparation, List<PhotoRepository.Photo> photos)
      throws Exception {
    try {
      preparation.prepare(photos, true);
      throw new AssertionError("partial result returned");
    } catch (IOException expected) {
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
