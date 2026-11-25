package jp.nano.rawgallery;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Releases local JPEGs; the e2 receipt and thumbnail stay available to the gallery. */
final class E2Cleanup {
  private static final String SCHEME = "e2archive";
  private static final Pattern RECORD_KEY = Pattern.compile("[0-9a-f]{32}");
  static final String RELEASING = "releasing", RELEASED = "released";
  private static final int EDGE = 256;
  private record Eligible(E2AutoSync.Candidate candidate, JSONObject receipt) {}
  record Plan(int retention, List<Eligible> eligible) {}

  static boolean archived(Uri uri) {
    return uri != null && SCHEME.equals(uri.getScheme());
  }

  private static SharedPreferences options(Context c) { return E2AutoSync.state(c); }

  static boolean enabled(Context c, String bucket) {
    return bucket != null && !bucket.isEmpty()
        && bucket.equals(options(c).getString("cleanupBucket", ""))
        && days(c) == options(c).getInt("cleanupDays", 0);
  }

  static int days(Context c) {
    int value = c.getSharedPreferences("settings", 0).getInt("retentionDays", 7);
    return value >= 1 && value <= 365 ? value : 7;
  }

  static void setEnabled(Context c, String bucket, int retention) throws IOException {
    boolean enabled = retention > 0;
    synchronized (E2Settings.DESTINATION_LOCK) {
      if (enabled && (retention != days(c) || !Environment.isExternalStorageManager() || !E2Settings.hasPhotoAccess(c)
          || !E2SyncJob.enabled(c) || !bucket.equals(E2Settings.configuredBucket(c))
          || !bucket.equals(options(c).getString("destination", ""))
          || c.getSharedPreferences("settings", 0).getBoolean("cleanupEnabled", false)))
        throw new IOException("自動バックアップと写真の整理権限を確認してください");
      if (!options(c).edit().putString("cleanupBucket", enabled ? bucket : "")
          .putInt("cleanupDays", retention).commit())
        throw new IOException("容量整理の設定を保存できません");
      if (!E2SyncJob.ensureRecoverySchedule(c))
        android.util.Log.w("LoupeE2Sync", "Recovery scheduling declined");
    }
  }

  static String status(Context c) {
    return options(c).getString("cleanupStatus", "");
  }

  static Plan plan(Context c, E2Photos photos, List<E2AutoSync.Candidate> candidates,
      String bucket, BooleanSupplier stopped) {
    try {
      if (!canStart(c, bucket, stopped)) return null;
      long now = System.currentTimeMillis();
      int retention = days(c);
      List<Eligible> eligible = new ArrayList<>();
      for (E2AutoSync.Candidate candidate : candidates) {
        if (!allowed(c, bucket, stopped)) return null;
        PhotoRepository.Photo p = candidate.photo();
        if (!p.isJpeg() || candidate.generation() <= 0
            || !MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(candidate.volume())) continue;
        JSONObject record = photos.settings.records.get(E2AutoSync.key(candidate, bucket,
            Long.toString(candidate.generation())));
        if (record == null || !"verified".equals(record.optString("state"))) continue;
        long date = SyncPolicy.retentionDate(p.taken, p.added, p.created, true);
        if (!SyncPolicy.eligible(date, now, retention, true, true, true)
            || !bucket.equals(record.optString("bucket")) || !p.uri.toString().equals(record.optString("uri"))
            || recentlyRestored(c, record.getString("sha"), retention)) continue;
        try {
          E2AutoSync.requireCurrentRevision(c, candidate);
        } catch (IOException changed) {
          DiagnosticLog.exception("e2_cleanup.current_revision", "skipped", changed);
          continue;
        }
        eligible.add(new Eligible(candidate, record));
      }
      if (!canStart(c, bucket, stopped) || days(c) != retention) return null;
      if (eligible.isEmpty()) {
        updateStatus(c, "整理対象のJPEGはありません");
        return null;
      }
      return new Plan(retention, eligible);
    } catch (Exception failure) {
      DiagnosticLog.exception("e2_cleanup.plan", stopped.getAsBoolean() ? "cancelled" : "failed", failure);
      if (!stopped.getAsBoolean()) updateStatus(c, "容量整理を中断しました。保存先や権限を確認してください");
      return null;
    }
  }

  static void sweep(Context c, E2Photos photos, Plan plan, String bucket, BooleanSupplier stopped,
      E2RecoveryCatalog.Snapshot published) {
    try {
      if (!canStart(c, bucket, stopped) || days(c) != plan.retention()) return;
      E2RecoveryCatalog.confirmLatest(photos.settings.archive(bucket), published);
      int removed = 0;
      for (Eligible item : plan.eligible()) {
        if (!allowed(c, bucket, stopped)) break;
        JSONObject entry = published.records().get(item.receipt().getString("key"));
        if (entry == null || !E2RecoveryCatalog.sameContent(entry, item.receipt()))
          throw new IOException("復元一覧と端末の保管記録が一致しません");
        // Release reads back this exact published version before the local deletion.
        item.receipt().put("version", entry.getString("version"));
        if (release(c, photos, item, bucket, plan.retention(), stopped)) removed++;
      }
      updateStatus(c, "端末オリジナルを整理 · " + removed + "枚");
    } catch (Exception failure) {
      DiagnosticLog.exception("e2_cleanup.remove", stopped.getAsBoolean() ? "cancelled" : "failed", failure);
      if (!stopped.getAsBoolean()) updateStatus(c, "容量整理を中断しました。保存先や権限を確認してください");
    }
  }

  private static boolean canStart(Context c, String bucket, BooleanSupplier stopped) {
    if (!E2Settings.hasPhotoAccess(c) || !Environment.isExternalStorageManager()) {
      if (enabled(c, bucket)) updateStatus(c, "容量整理には全ファイルへのアクセスが必要です");
      return false;
    }
    return allowed(c, bucket, stopped);
  }

  private static boolean allowed(Context c, String bucket, BooleanSupplier stopped) {
    return !stopped.getAsBoolean() && !Thread.currentThread().isInterrupted()
        && enabled(c, bucket) && E2SyncJob.enabled(c) && E2Settings.hasPhotoAccess(c)
        && Environment.isExternalStorageManager()
        && bucket.equals(E2Settings.configuredBucket(c))
        && bucket.equals(options(c).getString("destination", ""))
        && !c.getSharedPreferences("settings", 0).getBoolean("cleanupEnabled", false);
  }

  private static boolean release(Context c, E2Photos photos, Eligible item, String bucket,
      int retention, BooleanSupplier stopped) throws Exception {
    E2AutoSync.Candidate candidate = item.candidate();
    PhotoRepository.Photo photo = candidate.photo();
    JSONObject record = item.receipt();
    String key = record.getString("key");
    File verified = photos.downloadRecord(record);
    try {
      if (!allowed(c, bucket, stopped)) return false;
      PhotoRepository.Photo copy = new PhotoRepository.Photo(Uri.fromFile(verified), photo.name,
          photo.folder, photo.size, photo.modified, photo.taken);
      File thumb = thumbnail(c, key);
      if (!thumb.getParentFile().isDirectory() && !thumb.getParentFile().mkdirs()
          && !thumb.getParentFile().isDirectory()) throw new IOException("サムネイルを保存できません");
      PhotoRepository.Preview saved = CompactPreview.save(
          new PhotoRepository(c).fullPreview(copy), thumb, EDGE);
      Bitmap test = PhotoRepository.bitmap(saved, EDGE);
      test.recycle();
      Files.deleteIfExists(new File(thumb.getParentFile(), key + ".webp").toPath());

      if (!E2SyncJob.ORIGINALS.tryLock()) return false;
      try {
        if (!allowed(c, bucket, stopped)) return false;
        E2AutoSync.requireCurrentRevision(c, candidate);
        try (InputStream source = PhotoRepository.original(c, photo)) {
          if (!record.getString("sha").equals(E2Archive.readHash(source, null, photo.size))) return false;
        }
        E2AutoSync.requireCurrentRevision(c, candidate);
        synchronized (E2Settings.DESTINATION_LOCK) {
          if (!allowed(c, bucket, stopped) || days(c) != retention
              || recentlyRestored(c, record.getString("sha"), retention)) return false;
          E2AutoSync.requireCurrentRevision(c, candidate);
          record.put("cleanupVolume", candidate.volume()).put("cleanupDb", candidate.version())
              .put("cleanupGeneration", candidate.generation()).put("thumbWidth", saved.width).put("thumbHeight", saved.height);
          photos.settings.records.put(record, RELEASING);
          int changed = c.getContentResolver().delete(photo.uri,
              MediaStore.MediaColumns.GENERATION_MODIFIED + "=? AND "
                  + MediaStore.MediaColumns.SIZE + "=?",
              new String[]{Long.toString(candidate.generation()), Long.toString(photo.size)});
          if (changed != 1) {
            if (changed == 0) photos.settings.records.put(record, "verified");
            throw new IOException("Androidが端末オリジナルの整理を確定しませんでした");
          }
          photos.settings.records.put(record, RELEASED);
          new PhotoRepository(c).removePreparedPreview(photo);
          return true;
        }
      } finally {
        E2SyncJob.ORIGINALS.unlock();
      }
    } finally {
      E2Photos.discard(verified);
    }
  }

  static void reconcile(Context c, ArchiveStore store, String bucket, BooleanSupplier stopped)
      throws Exception {
    if (!Environment.isExternalStorageManager() || !E2Settings.hasPhotoAccess(c)) return;
    for (JSONObject record : store.inStates(RELEASING)) {
      if (stopped.getAsBoolean()) return;
      if (!bucket.equals(record.optString("bucket"))) continue;
      String volume = record.getString("cleanupVolume");
      if (!MediaStore.getExternalVolumeNames(c).contains(volume)
          || !record.getString("cleanupDb").equals(MediaStore.getVersion(c, volume))) continue;
      Uri uri = Uri.parse(record.getString("uri"));
      if (!uri.toString().matches("content://media/external/file/[0-9]+")) continue;
      try (Cursor item = c.getContentResolver().query(uri,
          new String[]{MediaStore.MediaColumns.GENERATION_MODIFIED, MediaStore.MediaColumns.SIZE},
          null, null, null)) {
        if (item == null) continue;
        boolean same = item.moveToFirst() && record.optLong("cleanupGeneration") == item.getLong(0)
            && record.getLong("size") == item.getLong(1);
        store.put(record, same ? "verified" : RELEASED);
        if (!same) new PhotoRepository(c).removePreparedPreview(new PhotoRepository.Photo(
            uri, record.getString("name"), record.getString("folder"), record.getLong("size"),
            record.getLong("modified"), record.optLong("taken")));
      }
    }
  }

  static void keepRestored(Context c, String sha) throws IOException {
    synchronized (E2Settings.DESTINATION_LOCK) {
      SharedPreferences restored = c.getSharedPreferences("e2_restored", Context.MODE_PRIVATE);
      long now = System.currentTimeMillis();
      SharedPreferences.Editor edit = restored.edit().putLong(sha, now);
      for (var entry : restored.getAll().entrySet()) {
        if (!sha.equals(entry.getKey()) && entry.getValue() instanceof Long time
            && SyncPolicy.eligible(time, now, 365, true, true, true)) edit.remove(entry.getKey());
      }
      if (!edit.commit()) throw new IOException("復元するオリジナルの保存期間を設定できません");
    }
  }

  private static boolean recentlyRestored(Context c, String sha, int retention) {
    long restored = c.getSharedPreferences("e2_restored", Context.MODE_PRIVATE).getLong(sha, 0);
    return restored > 0 && !SyncPolicy.eligible(restored, System.currentTimeMillis(), retention,
        true, true, true);
  }

  static boolean recoverable(JSONObject record) {
    String state = record.optString("state");
    return "verified".equals(state) || RELEASING.equals(state) || RELEASED.equals(state);
  }

  static boolean released(JSONObject record) { return RELEASED.equals(record.optString("state")); }

  static boolean releasing(JSONObject record) { return RELEASING.equals(record.optString("state")); }

  static PhotoRepository.Photo galleryPhoto(JSONObject record) throws Exception {
    return new PhotoRepository.Photo(Uri.parse(SCHEME + "://" + record.getString("key")),
        record.getString("name"), record.getString("folder"), record.getLong("size"),
        record.getLong("modified"), record.optLong("taken"), record.optLong("added"),
        record.optLong("created"));
  }

  private static String recordKey(Uri uri) {
    if (!archived(uri)) return null;
    String key = uri.getHost();
    return key != null && RECORD_KEY.matcher(key).matches() ? key : null;
  }

  /** Resolves E2 cache misses in the scanned photo list through one database helper. */
  static Map<String, ArchiveStore.RawMetadata> localIndexMetadata(Context c, List<PhotoRepository.Photo> photos,
      Map<String, ArchiveStore.RawMetadata> cachedMetadata, BooleanSupplier cancelled) throws IOException {
    Set<String> keys = new HashSet<>();
    for (PhotoRepository.Photo photo : photos) {
      if (cancelled.getAsBoolean()) return null;
      String key = recordKey(photo.uri);
      if (key != null && !cachedMetadata.containsKey(ArchiveStore.key(photo))) keys.add(key);
    }
    if (keys.isEmpty()) return Map.of();
    try (ArchiveStore store = E2Settings.openRecords(c)) {
      return store.releasedIndexMetadata(keys, cancelled);
    } catch (RuntimeException unavailable) {
      DiagnosticLog.exception("index.e2_metadata", "failed", unavailable);
      return Map.of();
    }
  }

  static JSONObject localRecord(Context c, Uri uri) throws Exception {
    String key = recordKey(uri);
    if (key == null) throw new IOException("オリジナルの識別子が不正です");
    try (ArchiveStore store = E2Settings.openRecords(c)) {
      JSONObject record = store.get(key);
      if (record == null || !(released(record) || RELEASING.equals(record.optString("state"))))
        throw new IOException("オリジナルの保管記録がありません");
      record.put("state", "verified");
      return record;
    }
  }

  static PhotoRepository.Preview preview(Context c, Uri uri) throws IOException {
    try {
      JSONObject record = localRecord(c, uri);
      File file = thumbnail(c, record.getString("key"));
      int width = record.getInt("thumbWidth"), height = record.getInt("thumbHeight");
      if (!file.isFile() || file.length() == 0 || width < 1 || height < 1 || width > EDGE || height > EDGE)
        throw new IOException("保存済みサムネイルがありません");
      return new PhotoRepository.Preview(file, record.optString("model"), width, height, 1);
    } catch (Exception e) {
      DiagnosticLog.exception("e2_cleanup.preview", "failed", e);
      throw new IOException("保存済みサムネイルを読めません", e);
    }
  }

  private static File thumbnail(Context c, String key) {
    if (!key.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid e2 receipt key");
    return new File(new File(c.getFilesDir(), "e2-previews"), key + "-thumb.webp");
  }

  private static void updateStatus(Context c, String status) {
    options(c).edit().putString("cleanupStatus", status).apply();
  }
}
