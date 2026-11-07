package jp.nano.rawgallery;

import android.content.Context;
import android.media.ExifInterface;
import java.io.*;
import java.util.*;

/**
 * Prepares all share copies before any recipient is opened (current-design.md P4).
 * Originals and durable previews are never cleanup targets. Failure removes this
 * attempt's copies, but does not roll back expired-cache cleanup or original retrieval.
 */
final class SharePreparation {
  private static final long LIMIT = ShareCapacity.LIMIT;
  private static final long MAX_AGE = 24L * 60 * 60 * 1000;

  /** Writes one complete copy, or throws; may leave a partial destination on failure. */
  interface CopyWriter {
    void write(PhotoRepository.Photo photo, boolean raw, File destination) throws Exception;
  }

  static final class Result {
    final List<File> files;
    final String mimeType;
    final Map<String,Long> jpegSizes;

    private Result(List<File> files, String mimeType, Map<String,Long> jpegSizes) {
      this.files = List.copyOf(files);
      this.mimeType = mimeType;
      this.jpegSizes = Map.copyOf(jpegSizes);
    }
  }

  private final File directory;
  private final CopyWriter writer;

  SharePreparation(Context context, PhotoRepository repository) {
    this(
        new File(context.getCacheDir(), "shares"),
        (photo, raw, destination) -> writeCopy(context, repository, photo, raw, destination));
  }

  SharePreparation(File directory, CopyWriter writer) {
    this.directory = directory;
    this.writer = writer;
  }

  /**
   * Requires a nonempty, fixed selection. Returns ordered files only after all copies
   * fit the 512 MiB per-share limit. Retained recipient copies use a separate lifetime.
   * Exceptions (including interruption) return
   * no partial result and attempt to delete every copy created by this call.
   */
  Result prepare(List<PhotoRepository.Photo> selection, boolean raw) throws Exception {
    List<File> prepared = new ArrayList<>();
    Map<String,Long> jpegSizes=new HashMap<>();
    try {
      if (selection.isEmpty()) throw new IOException("共有する写真がありません");
      ShareCapacity.Result known=ShareCapacity.assess(selection, raw, Map.of(), false);
      if (known.state == ShareCapacity.State.OVER) throw new CapacityException(known.bytes, Map.of());
      Set<String> types = new HashSet<>();
      if (!directory.isDirectory() && !directory.mkdirs())
        throw new IOException("共有用の空き領域を作れません");
      File[] old = directory.listFiles();
      long total = 0;
      if (old != null)
        for (File file : old) {
          if (file.lastModified() < System.currentTimeMillis() - MAX_AGE) file.delete();
        }
      for (PhotoRepository.Photo photo : selection) {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        String extension = raw ? RawFormats.originalExtension(photo.name) : "jpg";
        File file = new File(directory, UUID.randomUUID() + "." + extension);
        prepared.add(file);
        writer.write(photo, raw, file);
        types.add(RawFormats.mime("file." + extension));
        if (file.length() <= 0) throw new IOException("共有用ファイルを準備できませんでした");
        if (!raw) jpegSizes.put(ShareCapacity.key(photo),file.length());
        if (file.length() > LIMIT - total) throw new CapacityException(
            total>Long.MAX_VALUE-file.length()?Long.MAX_VALUE:total+file.length(),jpegSizes);
        total += file.length();
      }
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
      return new Result(prepared, types.size() == 1 ? types.iterator().next()
          : types.contains("application/octet-stream") ? "*/*" : "image/*", jpegSizes);
    } catch (Exception e) {
      for (File file : prepared) file.delete();
      throw e;
    }
  }

  static final class CapacityException extends IOException {
    final long bytes;
    final Map<String,Long> jpegSizes;
    CapacityException(long bytes,Map<String,Long> jpegSizes) {
      super("1回の共有は512 MiBまでです。写真を減らしてください。");
      this.bytes=bytes;this.jpegSizes=Map.copyOf(jpegSizes);
    }
  }

  /** Exact local RAW export size; never retrieves cloud originals on a selection change. */
  static long measureJpeg(Context context, PhotoRepository repository,
      PhotoRepository.Photo photo) throws Exception {
    if (photo.isJpeg()) return photo.size;
    if ("archive".equals(photo.uri.getScheme())) throw new IOException("共有の準備時に容量を確認します");
    PhotoRepository.Preview preview = repository.sourcePreview(photo);
    File scratch = File.createTempFile("share-measure-", ".jpg", context.getCacheDir());
    try {
      writePreview(preview, scratch);
      if (scratch.length() <= 0) throw new IOException("JPEGのサイズを確認できませんでした");
      return scratch.length();
    } finally { scratch.delete(); }
  }

  private static void writeCopy(
      Context context,
      PhotoRepository repository,
      PhotoRepository.Photo photo,
      boolean raw,
      File destination)
      throws Exception {
    PhotoRepository.Photo original = OriginalCache.obtain(context, photo);
    if (raw || photo.isJpeg()) {
      try (InputStream in = PhotoRepository.original(context, original);
          OutputStream out = new FileOutputStream(destination)) {
        copy(in, out);
      }
    } else {
      PhotoRepository.Preview preview = repository.fullPreview(original);
      writePreview(preview, destination);
    }
  }

  private static void writePreview(PhotoRepository.Preview preview, File destination)
      throws IOException {
    try (InputStream in = new FileInputStream(preview.file);
          OutputStream out = new FileOutputStream(destination)) {
      copy(in, out);
    }
    ExifInterface exif = new ExifInterface(destination);
    exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(preview.orientation));
    exif.saveAttributes();
  }

  private static void copy(InputStream in, OutputStream out) throws IOException {
    byte[] buffer = new byte[65536];
    int n;
    while ((n = in.read(buffer)) != -1) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
      out.write(buffer, 0, n);
    }
  }
}
