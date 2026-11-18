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
  private final E2RecoveryCatalog catalog;
  final E2Settings settings;

  E2Photos(Context context) {
    this(context, null);
  }

  E2Photos(Context context, android.net.Network network) {
    this.context = context.getApplicationContext();
    settings = new E2Settings(context, network);
    catalog = new E2RecoveryCatalog(this.context);
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

  void saveIndexed(E2AutoSync.Candidate candidate, String id, String bucket, String fallbackHash)
      throws Exception {
    PhotoRepository.Photo photo = candidate.photo();
    E2AutoSync.requireCurrentRevision(context, candidate);
    File staged = stage(() -> PhotoRepository.original(context, photo), photo.size);
    try {
      E2AutoSync.requireCurrentRevision(context, candidate);
      if (fallbackHash != null) {
        try (InputStream in = Files.newInputStream(staged.toPath())) {
          if (!fallbackHash.equals(E2Archive.readHash(in, null, photo.size)))
            throw new IOException("原本が変わりました。次回の同期で確認します");
        }
      }
      ArchiveStore.RawMetadata inspected;
      if (photo.isJpeg()) {
        inspected = settings.records.metadata(photo);
      } else {
        try (RawPreview.FileSource source = new RawPreview.FileSource(staged)) {
          RawPreview.Result raw = RawPreview.inspect(source);
          if (!SyncPolicy.supported(raw.model)) throw new IOException("対応するRAWを確認できません");
          inspected = new ArchiveStore.RawMetadata(raw.model, raw.taken);
        }
      }
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
    return downloadRecord(settings.records.get(id));
  }

  File downloadRecord(JSONObject record) throws Exception {
    if (record == null) throw new IOException("原本の保管記録が見つかりません");
    File directory = Files.createTempDirectory(context.getCacheDir().toPath(), "e2-export-").toFile();
    File verified = new File(directory, "original");
    try {
      settings.archive(record.getString("bucket")).restoreRecord(record, verified);
      return verified;
    } catch (Exception e) {
      discard(verified);
      throw e;
    }
  }

  void export(File verified, Uri destination) throws Exception {
    if (!DocumentsContract.isDocumentUri(context, destination))
      throw new IOException("新しいファイルを復元先に選んでください");
    long size = verified.length();
    String sha;
    try (InputStream source = Files.newInputStream(verified.toPath())) {
      sha = E2Archive.readHash(source, null, size);
    }
    E2Cleanup.keepRestored(context, sha);
    ParcelFileDescriptor descriptor =
        context.getContentResolver().openFileDescriptor(destination, "rw");
    if (descriptor == null) throw new IOException("復元先を開けません");
    try (FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
      if (descriptor.getStatSize() != 0) throw new IOException("復元先にファイルがあります");
      Files.copy(verified.toPath(), out);
      out.getFD().sync();
    }
    try (InputStream copy = context.getContentResolver().openInputStream(destination)) {
      if (copy == null || !sha.equals(E2Archive.readHash(copy, null, size)))
        throw new IOException("書き出した原本が一致しません");
    }
  }

  static void discard(File verified) {
    verified.delete();
    verified.getParentFile().delete();
  }

  List<JSONObject> saved(String bucket) throws Exception {
    List<JSONObject> results = localSaved(bucket);
    E2RecoveryCatalog.Snapshot imported = catalog.cached(bucket);
    if (imported != null)
      results = new ArrayList<>(E2RecoveryCatalog.merged(bucket, imported, results, null).values());
    results.sort((a, b) -> a.optString("name").compareToIgnoreCase(b.optString("name")));
    return results;
  }

  E2RecoveryCatalog.Snapshot publishCatalog(String bucket) throws Exception {
    return E2RecoveryCatalog.publish(settings.archive(bucket), bucket, localSaved(bucket));
  }

  E2RecoveryCatalog.Snapshot loadCatalog(String bucket) throws Exception {
    return catalog.load(settings.archive(bucket), bucket, localSaved(bucket));
  }

  private List<JSONObject> localSaved(String bucket) throws Exception {
    List<JSONObject> results = new ArrayList<>();
    for (JSONObject record : settings.records.all()) {
      if (E2Cleanup.recoverable(record) && bucket.equals(record.getString("bucket")))
        results.add(record.put("state", "verified"));
    }
    return results;
  }

  @Override public void close() {
    catalog.close();
    settings.records.close();
  }
}
