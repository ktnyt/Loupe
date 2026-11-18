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
        throw new IOException("RAW・JPEG・動画を選んでください");
      return name;
    }
  }

  static String mime(String name) {
    if (name == null || name.lastIndexOf('.') <= 0 || name.length() > 255
        || name.matches("(?s).*[\\\\/\\p{Cntrl}].*")) return null;
    if (RawFormats.accepts(name)) return RawFormats.mime(name);
    return VideoFormats.accepts(name) ? VideoFormats.mime(name) : null;
  }

  long save(Uri source, String name, String bucket) throws Exception {
    if (mime(name) == null) throw new IOException("RAW・JPEG・動画を選んでください");
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
    if (mime(photo.name) == null) throw new IOException("原本のファイル名を確認できません");
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
      // Original backup does not depend on a RAW decoder or video preview.
      ArchiveStore.RawMetadata inspected = new ArchiveStore.RawMetadata(
          photo.isVideo() ? "動画" : photo.isJpeg() ? "JPEG" : "RAW", photo.taken);
      if (photo.isJpeg()) {
        try {
          inspected = settings.records.metadata(photo);
        } catch (Exception unavailableMetadata) {
          if (Thread.currentThread().isInterrupted())
            throw new IOException("同期を中断しました", unavailableMetadata);
        }
      }
      JSONObject metadata = ArchiveStore.base(photo).put("mime", mime(photo.name))
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
    return publishCatalog(bucket, false);
  }

  E2RecoveryCatalog.Snapshot publishCatalogIfAny(String bucket) throws Exception {
    return publishCatalog(bucket, true);
  }

  boolean catalogPending(String bucket) throws Exception {
    return settings.records.indexRevision() != publishedRevision(bucket)
        && !localSaved(bucket).isEmpty();
  }

  private long publishedRevision(String bucket) {
    return context.getSharedPreferences("e2_catalog_publication", Context.MODE_PRIVATE)
        .getLong(bucket, -1);
  }

  private E2RecoveryCatalog.Snapshot publishCatalog(String bucket, boolean allowEmpty) throws Exception {
    long revision = settings.records.indexRevision();
    List<JSONObject> local = localSaved(bucket);
    if (allowEmpty && local.isEmpty()) return null;
    E2RecoveryCatalog.Snapshot result = E2RecoveryCatalog.publish(settings.archive(bucket), bucket, local);
    if (!context.getSharedPreferences("e2_catalog_publication", Context.MODE_PRIVATE).edit()
        .putLong(bucket, revision).commit())
      throw new IOException("復元一覧の公開結果を端末に保存できません");
    return result;
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
