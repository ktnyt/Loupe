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
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Bridges user-selected files and the separate SQLite catalog of e2 originals. */
final class E2Photos implements AutoCloseable {
  private static final Object SAVE_LOCK = new Object();
  private static final Set<File> ACTIVE_PICKER_FILES = new HashSet<>();
  private static final long PICKER_LIFETIME_MS = TimeUnit.DAYS.toMillis(1);
  private static boolean checkedInterruptedStaging;
  private static long nextPickerCleanupAt;
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
    File staged = stage(() -> context.getContentResolver().openInputStream(source),
        sizeHint(source));
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

  private long sizeHint(Uri source) {
    if (!"content".equals(source.getScheme())) return -1;
    try (Cursor row = context.getContentResolver()
        .query(source, new String[]{OpenableColumns.SIZE}, null, null, null)) {
      return row != null && row.moveToFirst() && !row.isNull(0) ? Math.max(-1, row.getLong(0)) : -1;
    } catch (RuntimeException unavailable) {
      return -1;
    }
  }

  interface Source {
    InputStream open() throws IOException;
  }

  static synchronized void discardInterruptedStaging(File cache, long now) {
    boolean firstScan = !checkedInterruptedStaging;
    if (!firstScan && now < nextPickerCleanupAt) return;
    File[] files = cache.listFiles();
    if (files == null) return;
    checkedInterruptedStaging = true;
    nextPickerCleanupAt = Long.MAX_VALUE;
    for (File file : files) {
      String name = file.getName();
      if (firstScan && file.isFile() && name.startsWith("e2-save-") && name.endsWith(".part")) {
        file.delete();
      } else if (file.isDirectory() && name.startsWith("e2-export-")) {
        File original = new File(file, "original");
        if (ACTIVE_PICKER_FILES.contains(original)) continue;
        File lease = new File(file, "picker");
        if (lease.isFile()) {
          long lastModified = lease.lastModified();
          long expires = lastModified + PICKER_LIFETIME_MS;
          if (lastModified <= now && now < expires) {
            nextPickerCleanupAt = Math.min(nextPickerCleanupAt, expires);
            continue;
          }
        } else if (!firstScan) {
          // Only the first scan can reclaim unmarked files without racing a live download.
          continue;
        }
        lease.delete();
        original.delete();
        File[] incomplete = file.listFiles((directory, part) ->
            part.startsWith("e2-restore-") && part.endsWith(".part"));
        if (incomplete != null) for (File part : incomplete) part.delete();
        file.delete();
      }
    }
  }

  File stage(Source source, long expected) throws IOException {
    if (expected > E2Archive.ORIGINAL_LIMIT) throw new IOException("原本のサイズが対応範囲外です");
    File cache = context.getCacheDir();
    discardInterruptedStaging(cache, System.currentTimeMillis());
    E2Archive.requireTemporarySpace(cache, expected);
    File staged = File.createTempFile("e2-save-", ".part", cache);
    boolean complete = false;
    try {
      long size = 0;
      try (InputStream in = source.open();
          FileOutputStream out = new E2Archive.TemporaryOutput(staged)) {
        if (in == null) throw new IOException("原本を読み込めません");
        byte[] buffer = new byte[65536];
        for (int read; (read = in.read(buffer)) != -1; ) {
          size += read;
          if (expected >= 0 && size > expected) throw new IOException("原本のサイズが変わりました");
          if (size > E2Archive.ORIGINAL_LIMIT || Thread.currentThread().isInterrupted())
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
    File cache = context.getCacheDir();
    discardInterruptedStaging(cache, System.currentTimeMillis());
    File directory = Files.createTempDirectory(cache.toPath(), "e2-export-").toFile();
    File verified = new File(directory, "original");
    try {
      settings.archive(record.getString("bucket")).restoreRecord(record, verified);
      return verified;
    } catch (Exception e) {
      discard(verified);
      throw e;
    }
  }

  record PreparedRestore(File file, long size, String sha) {
    PreparedRestore {
      if (size <= 0 || size > E2Archive.ORIGINAL_LIMIT || sha == null || !sha.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("復元の照合値が不正です");
    }

    void verifyStagedFile() throws Exception {
      try (InputStream source = Files.newInputStream(file.toPath())) {
        if (!sha.equals(E2Archive.readHash(source, null, size)))
          throw new IOException("復元した一時ファイルが原本と一致しません");
      }
    }
  }

  void export(PreparedRestore verified, Uri destination) throws Exception {
    if (!DocumentsContract.isDocumentUri(context, destination))
      throw new IOException("新しいファイルを復元先に選んでください");
    verified.verifyStagedFile();
    E2Cleanup.keepRestored(context, verified.sha());
    ParcelFileDescriptor descriptor =
        context.getContentResolver().openFileDescriptor(destination, "rw");
    if (descriptor == null) throw new IOException("復元先を開けません");
    try (FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
      if (descriptor.getStatSize() != 0) throw new IOException("復元先にファイルがあります");
      try (InputStream source = Files.newInputStream(verified.file().toPath())) {
        if (!verified.sha().equals(E2Archive.readHash(source, out, verified.size())))
          throw new IOException("書き出し中に原本が変わりました");
      }
      out.getFD().sync();
    }
    try (InputStream copy = context.getContentResolver().openInputStream(destination)) {
      if (copy == null || !verified.sha().equals(E2Archive.readHash(copy, null, verified.size())))
        throw new IOException("書き出した原本が一致しません");
    }
  }

  static synchronized void holdForPicker(File verified) throws IOException {
    File marker = new File(verified.getParentFile(), "picker");
    if (!verified.isFile()) throw new IOException("復元した一時ファイルが見つかりません");
    if (!marker.createNewFile())
      Files.setLastModifiedTime(marker.toPath(), FileTime.fromMillis(System.currentTimeMillis()));
    ACTIVE_PICKER_FILES.add(verified);
  }

  static synchronized void releasePickerOwnership(File verified) {
    ACTIVE_PICKER_FILES.remove(verified);
    File marker = new File(verified.getParentFile(), "picker");
    if (marker.isFile())
      nextPickerCleanupAt = Math.min(nextPickerCleanupAt, marker.lastModified() + PICKER_LIFETIME_MS);
  }

  static synchronized void discard(File verified) {
    ACTIVE_PICKER_FILES.remove(verified);
    new File(verified.getParentFile(), "picker").delete();
    verified.delete();
    verified.getParentFile().delete();
  }

  E2RecoveryCatalog.SavedRows saved(String bucket) throws Exception {
    E2RecoveryCatalog.Rows local = localSaved(settings.records, bucket);
    E2RecoveryCatalog.Snapshot imported = catalog.cached(bucket);
    return (imported == null ? local : E2RecoveryCatalog.merged(bucket, imported, local, null)).sorted();
  }

  E2RecoveryCatalog.Snapshot publishCatalog(String bucket) throws Exception {
    return publishCatalog(bucket, false);
  }

  E2RecoveryCatalog.Snapshot publishCatalogIfAny(String bucket) throws Exception {
    return publishCatalog(bucket, true);
  }

  boolean catalogPending(String bucket) throws Exception {
    return settings.records.indexRevision() != publishedRevision(bucket)
        && !settings.records.visitReceipts(record -> !isRecoverable(bucket, record));
  }

  private long publishedRevision(String bucket) {
    return context.getSharedPreferences("e2_catalog_publication", Context.MODE_PRIVATE)
        .getLong(bucket, -1);
  }

  private E2RecoveryCatalog.Snapshot publishCatalog(String bucket, boolean allowEmpty) throws Exception {
    long revision = settings.records.indexRevision();
    E2RecoveryCatalog.Rows local = localSaved(settings.records, bucket);
    if (allowEmpty && local.isEmpty()) return null;
    E2RecoveryCatalog.Snapshot result = E2RecoveryCatalog.publish(settings.archive(bucket), bucket, local);
    if (!context.getSharedPreferences("e2_catalog_publication", Context.MODE_PRIVATE).edit()
        .putLong(bucket, revision).commit())
      throw new IOException("復元一覧の公開結果を端末に保存できません");
    return result;
  }

  E2RecoveryCatalog.Snapshot loadCatalog(String bucket) throws Exception {
    return catalog.load(settings.archive(bucket), bucket, localSaved(settings.records, bucket));
  }

  private static boolean isRecoverable(String bucket, JSONObject record) throws Exception {
    return E2Cleanup.recoverable(record) && bucket.equals(record.getString("bucket"));
  }

  static E2RecoveryCatalog.Rows localSaved(ArchiveStore store, String bucket) throws Exception {
    E2RecoveryCatalog.Rows results = new E2RecoveryCatalog.Rows(bucket);
    store.visitReceipts(record -> {
      if (isRecoverable(bucket, record)) {
        if (record.optLong("taken") < 0) record.put("taken", 0);
        results.add(record.put("state", "verified"));
      }
      return true;
    });
    return results;
  }

  @Override public void close() {
    catalog.close();
    settings.records.close();
  }
}
