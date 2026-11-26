package jp.nano.rawgallery;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Releases local JPEGs; the e2 receipt and thumbnail stay available to the gallery. */
final class E2Cleanup {
  private static final String SCHEME = "e2archive";
  private static final Pattern RECORD_KEY = Pattern.compile("[0-9a-f]{32}");
  static final String RELEASING = "releasing", RELEASED = "released";
  private static final int EDGE = 256;
  private static final String LEGACY_CLEANUP_ENABLED = "cleanupEnabled";
  private static final String FRESH_CONSENT = "cleanupConsentAfterAmazonRetirement";
  private static final String PENDING_CONSENT = "e2-cleanup-consent.pending";
  // Presence fences synchronous observers. A true value also marks an uncertain disk state.
  private static final Map<SharedPreferences, Boolean> unconfirmedConsentWrites =
      new IdentityHashMap<>();

  static final class UnknownConsentException extends IllegalStateException {
    UnknownConsentException(Throwable cause) {
      super("容量整理の設定が保存されたか確認できません", cause);
    }
  }

  private record Eligible(E2AutoSync.Candidate candidate, JSONObject receipt) {}

  record Plan(int retention, List<Eligible> eligible) {}

  static boolean archived(Uri uri) {
    return uri != null && SCHEME.equals(uri.getScheme());
  }

  private static SharedPreferences options(Context c) {
    return E2AutoSync.state(c);
  }

  static boolean enabled(Context c, String bucket) {
    if (bucket == null || bucket.isEmpty()) return false;
    SharedPreferences consent = options(c);
    synchronized (unconfirmedConsentWrites) {
      if (unconfirmedConsentWrites.containsKey(consent)) return false;
      try {
        return bucket.equals(consent.getString("cleanupBucket", ""))
            && days(c) == consent.getInt("cleanupDays", 0)
            && (!legacyCleanup(c)
                || (consent.getBoolean(FRESH_CONSENT, false)
                    && pendingConsentAbsent(c.getDataDir())));
      } catch (RuntimeException invalidConsent) {
        return false;
      }
    }
  }

  static boolean consentUnknown(Context c) {
    SharedPreferences consent = options(c);
    synchronized (unconfirmedConsentWrites) {
      return Boolean.TRUE.equals(unconfirmedConsentWrites.get(consent));
    }
  }

  private static boolean legacyCleanup(Context c) {
    return SettingsRepository.open(c).getBoolean(LEGACY_CLEANUP_ENABLED, false);
  }

  private static FileDescriptor openConsentDirectory(File parent)
      throws ErrnoException, IOException {
    FileDescriptor directory =
        Os.open(
            parent.getAbsolutePath(),
            OsConstants.O_RDONLY
                | OsConstants.O_NOFOLLOW
                | OsConstants.O_NONBLOCK
                | OsConstants.O_CLOEXEC,
            0);
    boolean confirmed = false;
    try {
      confirmed = OsConstants.S_ISDIR(Os.fstat(directory).st_mode);
      if (!confirmed) throw new IOException("Invalid cleanup consent directory");
      return directory;
    } finally {
      if (!confirmed) Os.close(directory);
    }
  }

  private static String pendingConsentPath(File parent) {
    return new File(parent, PENDING_CONSENT).getAbsolutePath();
  }

  private static boolean pendingConsentAbsent(File parent) {
    try {
      FileDescriptor directory = openConsentDirectory(parent);
      try {
        Os.lstat(pendingConsentPath(parent));
        return false;
      } catch (ErrnoException status) {
        return status.errno == OsConstants.ENOENT;
      } finally {
        Os.close(directory);
      }
    } catch (ErrnoException | IOException | RuntimeException unavailable) {
      return false;
    }
  }

  private static void preparePendingConsent(File parent) throws ErrnoException, IOException {
    FileDescriptor directory = openConsentDirectory(parent);
    try {
      FileDescriptor pending =
          Os.open(
              pendingConsentPath(parent),
              OsConstants.O_WRONLY
                  | OsConstants.O_CREAT
                  | OsConstants.O_NOFOLLOW
                  | OsConstants.O_NONBLOCK
                  | OsConstants.O_CLOEXEC,
              0600);
      try {
        if (!OsConstants.S_ISREG(Os.fstat(pending).st_mode))
          throw new IOException("Invalid pending cleanup consent file");
        Os.fsync(pending);
      } finally {
        Os.close(pending);
      }
      Os.fsync(directory);
    } finally {
      Os.close(directory);
    }
  }

  private static boolean activatePendingConsent(File parent) throws ErrnoException, IOException {
    FileDescriptor directory = openConsentDirectory(parent);
    try {
      String path = pendingConsentPath(parent);
      if (!OsConstants.S_ISREG(Os.lstat(path).st_mode))
        throw new IOException("Invalid pending cleanup consent file");
      try {
        Os.remove(path);
      } catch (ErrnoException unlinkFailure) {
        try {
          if (OsConstants.S_ISREG(Os.lstat(path).st_mode)) return false;
        } catch (ErrnoException statusFailure) {
          unlinkFailure.addSuppressed(statusFailure);
        }
        throw unlinkFailure;
      }
      Os.fsync(directory);
    } finally {
      Os.close(directory);
    }
    if (!pendingConsentAbsent(parent)) throw new IOException("Cannot confirm cleanup consent");
    return true;
  }

  private static SharedPreferences.Editor clearConsent(SharedPreferences.Editor editor) {
    return editor.remove("cleanupBucket").remove("cleanupDays").remove(FRESH_CONSENT);
  }

  private static File beginConsentWrite(Context c, SharedPreferences consent, boolean grant)
      throws IOException {
    // Framework listeners can read the changed in-memory preferences before commit returns.
    unconfirmedConsentWrites.putIfAbsent(consent, false);
    if (!legacyCleanup(c)) return null;
    boolean hasFreshGrant = Boolean.TRUE.equals(consent.getAll().get(FRESH_CONSENT));
    if (!grant && !hasFreshGrant) return null;
    try {
      // Android establishes this app-private directory when it installs the package.
      File parent = c.getDataDir();
      preparePendingConsent(parent);
      unconfirmedConsentWrites.put(consent, false);
      return parent;
    } catch (ErrnoException | IOException | RuntimeException unavailable) {
      if (!grant || hasFreshGrant) {
        unconfirmedConsentWrites.put(consent, true);
        throw new UnknownConsentException(unavailable);
      }
      throw new IOException("容量整理の設定を保存できません", unavailable);
    }
  }

  private static void writeConsent(
      Context c, SharedPreferences consent, String bucket, int retention) throws IOException {
    synchronized (unconfirmedConsentWrites) {
      File pendingParent = beginConsentWrite(c, consent, retention > 0);
      IOException failure = null;
      try {
        SharedPreferences.Editor editor = clearConsent(consent.edit());
        if (retention > 0) {
          editor
              .putString("cleanupBucket", bucket)
              .putInt("cleanupDays", retention)
              .putBoolean(FRESH_CONSENT, true);
        }
        if (!editor.commit()) failure = new IOException("容量整理の設定を保存できません");
      } catch (RuntimeException writeFailure) {
        failure = new IOException("容量整理の設定を保存できません", writeFailure);
      }
      if (failure != null) {
        // A compensation is best-effort. It never removes the durable veto.
        try {
          if (clearConsent(consent.edit()).commit()) unconfirmedConsentWrites.remove(consent);
          else failure.addSuppressed(new IOException("容量整理の停止を保存できません"));
        } catch (RuntimeException revokeFailure) {
          failure.addSuppressed(revokeFailure);
        }
        throw failure;
      }
      boolean active = true;
      if (retention > 0 && pendingParent != null) {
        try {
          active = activatePendingConsent(pendingParent);
        } catch (ErrnoException | IOException | RuntimeException unavailable) {
          unconfirmedConsentWrites.put(consent, true);
          throw new UnknownConsentException(unavailable);
        }
      }
      unconfirmedConsentWrites.remove(consent);
      if (!active) throw new IOException("容量整理を有効にできません");
    }
  }

  static boolean revokeForAutoBackupPause(Context c, SharedPreferences.Editor settings) {
    SharedPreferences consent = options(c);
    synchronized (unconfirmedConsentWrites) {
      try {
        beginConsentWrite(c, consent, false);
      } catch (IOException unavailable) {
        throw new IllegalStateException("容量整理を停止できません", unavailable);
      }
      boolean saved = clearConsent(settings).remove("fullMediaConsent").commit();
      if (saved) unconfirmedConsentWrites.remove(consent);
      return saved;
    }
  }

  static int days(Context c) {
    int value = SettingsRepository.retentionDays(SettingsRepository.open(c));
    return value >= 1 && value <= 365 ? value : SettingsRepository.DEFAULT_RETENTION_DAYS;
  }

  static boolean setRetentionDays(Context context, int next) throws IOException {
    if (next != 7 && next != 30 && next != 90) throw new IllegalArgumentException("retention");
    synchronized (E2Settings.DESTINATION_LOCK) {
      if (next == days(context)) return false;
      SharedPreferences settings = SettingsRepository.open(context);
      boolean hadValue = settings.contains(SettingsRepository.RETENTION_DAYS);
      int previous = SettingsRepository.retentionDays(settings);
      // Revoke the old consent durably before changing its associated policy.
      writeConsent(context, options(context), null, 0);
      try {
        if (!settings.edit().putInt(SettingsRepository.RETENTION_DAYS, next).commit()) {
          throw new IOException("写真を残す期間を保存できません");
        }
      } catch (IOException | RuntimeException writeFailure) {
        IOException failure =
            writeFailure instanceof IOException io
                ? io
                : new IOException("写真を残す期間を保存できません", writeFailure);
        try {
          var rollback = settings.edit();
          if (hadValue) rollback.putInt(SettingsRepository.RETENTION_DAYS, previous);
          else rollback.remove(SettingsRepository.RETENTION_DAYS);
          if (!rollback.commit()) {
            failure.addSuppressed(new IOException("以前の保存期間を復元できません"));
          }
        } catch (RuntimeException rollbackFailure) {
          if (rollbackFailure != writeFailure) failure.addSuppressed(rollbackFailure);
        }
        throw failure;
      }
      refreshRecoverySchedule(context);
      return true;
    }
  }

  static void setEnabled(Context c, String bucket, int retention) throws IOException {
    boolean enabled = retention > 0;
    synchronized (E2Settings.DESTINATION_LOCK) {
      if (enabled
          && (retention != days(c)
              || !Environment.isExternalStorageManager()
              || !E2Settings.hasPhotoAccess(c)
              || !E2SyncJob.enabled(c)
              || !bucket.equals(E2Settings.configuredBucket(c))
              || !bucket.equals(options(c).getString("destination", ""))))
        throw new IOException("自動バックアップと写真の整理権限を確認してください");
      writeConsent(c, options(c), bucket, retention);
      refreshRecoverySchedule(c);
    }
  }

  private static void refreshRecoverySchedule(Context c) {
    try {
      if (!E2SyncJob.ensureRecoverySchedule(c))
        android.util.Log.w("LoupeE2Sync", "Recovery scheduling declined");
    } catch (RuntimeException unavailable) {
      android.util.Log.w("LoupeE2Sync", "Recovery scheduler unavailable", unavailable);
    }
  }

  static String status(Context c) {
    return options(c).getString("cleanupStatus", "");
  }

  static Plan plan(
      Context c,
      E2Photos photos,
      List<E2AutoSync.Candidate> candidates,
      String bucket,
      BooleanSupplier stopped) {
    try {
      if (!canStart(c, bucket, stopped)) return null;
      long now = System.currentTimeMillis();
      int retention = days(c);
      List<Eligible> eligible = new ArrayList<>();
      for (E2AutoSync.Candidate candidate : candidates) {
        if (!allowed(c, bucket, stopped)) return null;
        PhotoRepository.Photo p = candidate.photo();
        if (!p.isJpeg()
            || candidate.generation() <= 0
            || !MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(candidate.volume())) continue;
        JSONObject record =
            photos.settings.records.get(
                E2AutoSync.key(candidate, bucket, Long.toString(candidate.generation())));
        if (record == null || !"verified".equals(record.optString("state"))) continue;
        long date = SyncPolicy.retentionDate(p.taken, p.added, p.created, true);
        if (!SyncPolicy.eligible(date, now, retention, true, true, true)
            || !bucket.equals(record.optString("bucket"))
            || !p.uri.toString().equals(record.optString("uri"))
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
      DiagnosticLog.exception(
          "e2_cleanup.plan", stopped.getAsBoolean() ? "cancelled" : "failed", failure);
      if (!stopped.getAsBoolean()) updateStatus(c, "容量整理を中断しました。保存先や権限を確認してください");
      return null;
    }
  }

  static void sweep(
      Context c,
      E2Photos photos,
      Plan plan,
      String bucket,
      BooleanSupplier stopped,
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
      DiagnosticLog.exception(
          "e2_cleanup.remove", stopped.getAsBoolean() ? "cancelled" : "failed", failure);
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
    return !stopped.getAsBoolean()
        && !Thread.currentThread().isInterrupted()
        && enabled(c, bucket)
        && E2SyncJob.enabled(c)
        && E2Settings.hasPhotoAccess(c)
        && Environment.isExternalStorageManager()
        && bucket.equals(E2Settings.configuredBucket(c))
        && bucket.equals(options(c).getString("destination", ""));
  }

  private static boolean release(
      Context c,
      E2Photos photos,
      Eligible item,
      String bucket,
      int retention,
      BooleanSupplier stopped)
      throws Exception {
    E2AutoSync.Candidate candidate = item.candidate();
    PhotoRepository.Photo photo = candidate.photo();
    JSONObject record = item.receipt();
    String key = record.getString("key");
    File verified = photos.downloadRecord(record);
    try {
      if (!allowed(c, bucket, stopped)) return false;
      PhotoRepository.Photo copy =
          new PhotoRepository.Photo(
              Uri.fromFile(verified),
              photo.name,
              photo.folder,
              photo.size,
              photo.modified,
              photo.taken);
      File thumb = thumbnail(c, key);
      if (!thumb.getParentFile().isDirectory()
          && !thumb.getParentFile().mkdirs()
          && !thumb.getParentFile().isDirectory()) throw new IOException("サムネイルを保存できません");
      PhotoRepository.Preview saved =
          CompactPreview.save(new PhotoRepository(c).fullPreview(copy), thumb, EDGE);
      Bitmap test = PhotoRepository.bitmap(saved, EDGE);
      test.recycle();
      Files.deleteIfExists(new File(thumb.getParentFile(), key + ".webp").toPath());

      if (!E2SyncJob.ORIGINALS.tryLock()) return false;
      try {
        if (!allowed(c, bucket, stopped)) return false;
        E2AutoSync.requireCurrentRevision(c, candidate);
        try (InputStream source = PhotoRepository.original(c, photo)) {
          if (!record.getString("sha").equals(E2Archive.readHash(source, null, photo.size)))
            return false;
        }
        E2AutoSync.requireCurrentRevision(c, candidate);
        synchronized (E2Settings.DESTINATION_LOCK) {
          if (!allowed(c, bucket, stopped)
              || days(c) != retention
              || recentlyRestored(c, record.getString("sha"), retention)) return false;
          E2AutoSync.requireCurrentRevision(c, candidate);
          record
              .put("cleanupVolume", candidate.volume())
              .put("cleanupDb", candidate.version())
              .put("cleanupGeneration", candidate.generation())
              .put("thumbWidth", saved.width)
              .put("thumbHeight", saved.height);
          photos.settings.records.put(record, RELEASING);
          int changed =
              c.getContentResolver()
                  .delete(
                      photo.uri,
                      MediaStore.MediaColumns.GENERATION_MODIFIED
                          + "=? AND "
                          + MediaStore.MediaColumns.SIZE
                          + "=?",
                      new String[] {
                        Long.toString(candidate.generation()), Long.toString(photo.size)
                      });
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
      try (Cursor item =
          c.getContentResolver()
              .query(
                  uri,
                  new String[] {
                    MediaStore.MediaColumns.GENERATION_MODIFIED, MediaStore.MediaColumns.SIZE
                  },
                  null,
                  null,
                  null)) {
        if (item == null) continue;
        boolean same =
            item.moveToFirst()
                && record.optLong("cleanupGeneration") == item.getLong(0)
                && record.getLong("size") == item.getLong(1);
        store.put(record, same ? "verified" : RELEASED);
        if (!same)
          new PhotoRepository(c)
              .removePreparedPreview(
                  new PhotoRepository.Photo(
                      uri,
                      record.getString("name"),
                      record.getString("folder"),
                      record.getLong("size"),
                      record.getLong("modified"),
                      record.optLong("taken")));
      }
    }
  }

  static void keepRestored(Context c, String sha) throws IOException {
    synchronized (E2Settings.DESTINATION_LOCK) {
      SharedPreferences restored = c.getSharedPreferences("e2_restored", Context.MODE_PRIVATE);
      long now = System.currentTimeMillis();
      SharedPreferences.Editor edit = restored.edit().putLong(sha, now);
      for (var entry : restored.getAll().entrySet()) {
        if (!sha.equals(entry.getKey())
            && entry.getValue() instanceof Long time
            && SyncPolicy.eligible(time, now, 365, true, true, true)) edit.remove(entry.getKey());
      }
      if (!edit.commit()) throw new IOException("復元するオリジナルの保存期間を設定できません");
    }
  }

  private static boolean recentlyRestored(Context c, String sha, int retention) {
    long restored = c.getSharedPreferences("e2_restored", Context.MODE_PRIVATE).getLong(sha, 0);
    return restored > 0
        && !SyncPolicy.eligible(restored, System.currentTimeMillis(), retention, true, true, true);
  }

  static boolean recoverable(JSONObject record) {
    String state = record.optString("state");
    return "verified".equals(state) || RELEASING.equals(state) || RELEASED.equals(state);
  }

  static boolean released(JSONObject record) {
    return RELEASED.equals(record.optString("state"));
  }

  static boolean releasing(JSONObject record) {
    return RELEASING.equals(record.optString("state"));
  }

  static PhotoRepository.Photo galleryPhoto(JSONObject record) throws Exception {
    return new PhotoRepository.Photo(
        Uri.parse(SCHEME + "://" + record.getString("key")),
        record.getString("name"),
        record.getString("folder"),
        record.getLong("size"),
        record.getLong("modified"),
        record.optLong("taken"),
        record.optLong("added"),
        record.optLong("created"));
  }

  private static String recordKey(Uri uri) {
    if (!archived(uri)) return null;
    String key = uri.getHost();
    return key != null && RECORD_KEY.matcher(key).matches() ? key : null;
  }

  /** Resolves E2 cache misses in the scanned photo list through one database helper. */
  static Map<String, ArchiveStore.RawMetadata> localIndexMetadata(
      Context c,
      List<PhotoRepository.Photo> photos,
      Map<String, ArchiveStore.RawMetadata> cachedMetadata,
      BooleanSupplier cancelled)
      throws IOException {
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
      if (!file.isFile()
          || file.length() == 0
          || width < 1
          || height < 1
          || width > EDGE
          || height > EDGE) throw new IOException("保存済みサムネイルがありません");
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
