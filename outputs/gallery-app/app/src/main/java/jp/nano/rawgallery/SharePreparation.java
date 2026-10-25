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
  private static final long LIMIT = 512L * 1024 * 1024;
  private static final long MAX_AGE = 24L * 60 * 60 * 1000;

  /** Writes one complete copy, or throws; may leave a partial destination on failure. */
  interface CopyWriter {
    void write(PhotoRepository.Photo photo, boolean raw, File destination) throws Exception;
  }

  static final class Result {
    final List<File> files;
    final String mimeType;

    private Result(List<File> files, String mimeType) {
      this.files = List.copyOf(files);
      this.mimeType = mimeType;
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
   * fit the existing 512 MiB cache limit. Exceptions (including interruption) return
   * no partial result and attempt to delete every copy created by this call.
   */
  Result prepare(List<PhotoRepository.Photo> selection, boolean raw) throws Exception {
    List<File> prepared = new ArrayList<>();
    try {
      Set<String> types = new HashSet<>();
      directory.mkdirs();
      File[] old = directory.listFiles();
      long total = 0;
      if (old != null)
        for (File file : old) {
          if (file.lastModified() < System.currentTimeMillis() - MAX_AGE) file.delete();
          else total += file.length();
        }
      if (total > LIMIT)
        throw new IOException("共有用キャッシュの上限です。しばらく待って再試行してください。");
      for (PhotoRepository.Photo photo : selection) {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        String extension = raw ? RawFormats.originalExtension(photo.name) : "jpg";
        File file = new File(directory, UUID.randomUUID() + "." + extension);
        prepared.add(file);
        writer.write(photo, raw, file);
        types.add(RawFormats.mime("file." + extension));
        total += file.length();
        if (total > LIMIT) throw new IOException("共有用キャッシュの上限です");
      }
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
      return new Result(prepared, types.size() == 1 ? types.iterator().next()
          : types.contains("application/octet-stream") ? "*/*" : "image/*");
    } catch (Exception e) {
      for (File file : prepared) file.delete();
      throw e;
    }
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
      try (InputStream in = new FileInputStream(preview.file);
          OutputStream out = new FileOutputStream(destination)) {
        copy(in, out);
      }
      ExifInterface exif = new ExifInterface(destination);
      exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(preview.orientation));
      exif.saveAttributes();
    }
  }

  private static void copy(InputStream in, OutputStream out) throws IOException {
    byte[] buffer = new byte[65536];
    int n;
    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
  }
}
