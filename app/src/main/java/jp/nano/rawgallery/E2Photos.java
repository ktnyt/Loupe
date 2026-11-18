package jp.nano.rawgallery;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/** Bridges user-selected files and the separate SQLite catalog of e2 originals. */
final class E2Photos implements AutoCloseable {
  private static final Object SAVE_LOCK = new Object();
  private final Context context;
  final E2Settings settings;

  E2Photos(Context context) {
    this.context = context.getApplicationContext();
    settings = new E2Settings(context);
  }

  String name(Uri uri) throws IOException {
    try (Cursor cursor = context.getContentResolver()
        .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cursor == null || !cursor.moveToFirst()) throw new IOException("ファイル名を取得できません");
      String name = cursor.getString(0);
      if (name == null || name.isEmpty() || name.length() > 255
          || name.matches("(?s).*[\\\\/\\p{Cntrl}].*") || mime(name) == null)
        throw new IOException("RAWまたはJPEGを選んでください");
      return name;
    }
  }

  private static String mime(String name) {
    int dot = name.lastIndexOf('.');
    if (dot <= 0) return null;
    switch (name.substring(dot + 1).toLowerCase(Locale.ROOT)) {
      case "jpg": case "jpeg": return "image/jpeg";
      case "arw": case "cr2": case "cr3": case "dng": case "nef": case "nrw":
      case "orf": case "raf": case "rw2": case "pef": case "srw":
        return "application/octet-stream";
      default: return null;
    }
  }

  long save(Uri source, String name, String bucket) throws Exception {
    File staged = stage(() -> context.getContentResolver().openInputStream(source), -1);
    try {
      synchronized (SAVE_LOCK) {
        return settings.archive(bucket).savePhoto(staged, name, mime(name));
      }
    } finally {
      staged.delete();
    }
  }

  void saveIndexed(PhotoRepository.Photo photo, String id, String bucket) throws Exception {
    File staged = stage(() -> PhotoRepository.original(context, photo), photo.size);
    try {
      ArchiveStore.RawMetadata inspected = settings.records.metadata(photo);
      JSONObject metadata = ArchiveStore.base(photo).put("mime", RawFormats.mime(photo.name))
          .put("model", inspected.model)
          .put("taken", inspected.taken > 0 ? inspected.taken : photo.taken);
      // The automatic sweep submits each indexed ID only once. Manual saves have separate IDs.
      settings.archive(bucket).saveIndexed(id, staged, metadata);
    } finally {
      staged.delete();
    }
  }

  private interface Source {
    InputStream open() throws IOException;
  }

  private File stage(Source source, long expected) throws IOException {
    File staged = File.createTempFile("e2-save-", ".part", context.getCacheDir());
    boolean complete = false;
    try {
      long size = 0;
      try (InputStream in = source.open();
          FileOutputStream out = new FileOutputStream(staged)) {
        if (in == null) throw new IOException("原本を読み込めません");
        byte[] buffer = new byte[65536];
        for (int read; (read = in.read(buffer)) != -1; ) {
          size += read;
          if (size > E2Archive.LIMIT || Thread.currentThread().isInterrupted())
            throw new IOException("原本を読み込めません");
          out.write(buffer, 0, read);
        }
      }
      if (expected >= 0 && size != expected) throw new IOException("原本のサイズが変わりました");
      complete = true;
      return staged;
    } finally {
      if (!complete) staged.delete();
    }
  }

  File download(String id) throws Exception {
    File directory = Files.createTempDirectory(context.getCacheDir().toPath(), "e2-export-").toFile();
    File verified = new File(directory, "original");
    try {
      settings.archive().restore(id, verified);
      return verified;
    } catch (Exception e) {
      discard(verified);
      throw e;
    }
  }

  void export(File verified, Uri destination) throws Exception {
    if (!DocumentsContract.isDocumentUri(context, destination))
      throw new IOException("新しいファイルを復元先に選んでください");
    ParcelFileDescriptor descriptor =
        context.getContentResolver().openFileDescriptor(destination, "rw");
    if (descriptor == null) throw new IOException("復元先を開けません");
    try (FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
      if (descriptor.getStatSize() != 0) throw new IOException("復元先にファイルがあります");
      Files.copy(verified.toPath(), out);
      out.getFD().sync();
    }
    long size = verified.length();
    try (InputStream source = Files.newInputStream(verified.toPath());
         InputStream copy = context.getContentResolver().openInputStream(destination)) {
      if (copy == null || !E2Archive.readHash(source, null, size)
          .equals(E2Archive.readHash(copy, null, size)))
        throw new IOException("書き出した原本が一致しません");
    }
  }

  static void discard(File verified) {
    verified.delete();
    verified.getParentFile().delete();
  }

  List<JSONObject> saved(String bucket) throws Exception {
    List<JSONObject> results = new ArrayList<>();
    for (JSONObject record : settings.records.all()) {
      if ("verified".equals(record.getString("state"))
          && bucket.equals(record.getString("bucket"))) results.add(record);
    }
    results.sort((a, b) -> a.optString("name").compareToIgnoreCase(b.optString("name")));
    return results;
  }

  @Override public void close() {
    settings.records.close();
  }
}
