package jp.nano.rawgallery;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Network;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.json.JSONObject;

/** Persists observations of each sweep and prepares offline images for verified copies. */
final class E2AutoSync {
  private static final int TRANSFERS = 1;
  private static final long ADMISSION_MS = 120_000;
  private static final String STATE = "e2_auto_sync";
  record Candidate(PhotoRepository.Photo photo, String volume, String version, long generation) {}
  private record Publication(E2RecoveryCatalog.Snapshot snapshot, long revision) {}

  static final class Batch {
    final String identity;
    int ready, failed;

    Batch(SharedPreferences preferences, String identity, int total) {
      this.identity = identity;
      if (!identity.equals(preferences.getString("batchIdentity", ""))) return;
      int savedReady = preferences.getInt("batchReady", 0);
      int savedFailed = preferences.getInt("batchFailed", 0);
      if (savedReady < 0 || savedFailed < 0 || (long) savedReady + savedFailed >= total) return;
      ready = savedReady;
      failed = savedFailed;
    }

    int processed() { return ready + failed; }

    boolean shouldContinue(int total, int readyBefore, int processedBefore, boolean publishedAtStart) {
      return processed() < total && (ready > readyBefore
          || (processed() == processedBefore && publishedAtStart));
    }

    void save(SharedPreferences.Editor editor) {
      editor.putString("batchIdentity", identity).putInt("batchReady", ready)
          .putInt("batchFailed", failed);
    }
  }

  static void clearBatch(SharedPreferences.Editor editor) {
    editor.remove("batchIdentity").remove("batchReady").remove("batchFailed");
  }

  static SharedPreferences state(Context context) {
    return context.getSharedPreferences(STATE, Context.MODE_PRIVATE);
  }

  static JobState snapshot(Context context, boolean running) {
    return JobState.read(state(context), "", running, true);
  }

  /** Returns the destination if this batch made progress and has another batch to run. */
  static String run(Context context, Network network, BooleanSupplier stopped) {
    long admissionDeadline = SystemClock.elapsedRealtime() + ADMISSION_MS;
    SharedPreferences preferences = state(context);
    preferences.edit().remove("failureReason").apply();
    publish(preferences, JobState.Kind.RUNNING, 0, 0, 0, false);
    Batch batch = null;
    int total = 0;
    try (E2Photos e2 = new E2Photos(context, network)) {
      if (stopped.getAsBoolean()) {
        publish(preferences, JobState.Kind.PAUSED, 0, 0, 0, false);
        return null;
      }
      if (!e2.settings.configured() || !E2Settings.hasMediaAccess(context)) {
        publish(preferences, JobState.Kind.AUTH_REQUIRED, 0, 0, 0, false);
        return null;
      }
      if (!E2SyncJob.fullMediaConsent(context)) {
        preferences.edit().putString("failureReason", "写真・動画のバックアップ範囲の確認が必要です。一時停止してから再開してください").apply();
        publish(preferences, JobState.Kind.AUTH_REQUIRED, 0, 0, 0, false);
        return null;
      }
      String bucket = preferences.getString("destination", "");
      if (bucket.isEmpty() || !bucket.equals(e2.settings.configuration().getString("bucket"))) {
        publish(preferences, JobState.Kind.AUTH_REQUIRED, 0, 0, 0, false);
        return null;
      }
      e2.settings.archive(bucket).checkConnection();
      E2Cleanup.reconcile(context, e2.settings.records, bucket, stopped);
      List<Candidate> photos = candidates(context);
      total = photos.size();
      batch = new Batch(preferences, batchIdentity(bucket, photos), total);
      int readyBefore = batch.ready;
      int processedBefore = batch.processed();
      Publication published = e2.catalogPending(bucket) ? tryPublishCatalog(context, e2, bucket, stopped) : null;
      boolean publishedAtStart = published != null;
      PhotoRepository repository = new PhotoRepository(context, true);
      transferBatch(preferences, photos, batch, stopped,
          () -> SystemClock.elapsedRealtime() < admissionDeadline,
          photo -> transfer(context, repository, e2, photo, bucket, stopped));

      requireDestination(context, bucket, stopped);
      boolean unfinished = batch.processed() < total;
      E2Cleanup.Plan cleanup = unfinished ? null : E2Cleanup.plan(context, e2, photos, bucket, stopped);
      Long snapshotRevision = published != null && published.snapshot() != null
          ? published.revision() : null;
      if (needsCatalog(e2, bucket, cleanup != null, snapshotRevision)) {
        published = tryPublishCatalog(context, e2, bucket, stopped);
        if (published == null) {
          publish(preferences, failureKind(batch), batch.ready, total, batch.failed, true);
          return null;
        }
      }
      requireDestination(context, bucket, stopped);
      if (cleanup != null && published != null && published.snapshot() != null)
        E2Cleanup.sweep(context, e2, cleanup, bucket, stopped, published.snapshot());

      synchronized (E2Settings.DESTINATION_LOCK) {
        requireDestination(context, bucket, stopped);
        SharedPreferences.Editor editor = preferences.edit();
        if (unfinished) batch.save(editor);
        else clearBatch(editor);
        if (!editor.commit()) throw new IOException("同期の進捗を保存できません");
      }
      boolean resume = batch.shouldContinue(total, readyBefore, processedBefore, publishedAtStart);
      JobState.Kind kind = resume ? JobState.Kind.WAITING
          : unfinished || batch.failed != 0 ? failureKind(batch) : JobState.Kind.COMPLETE;
      publish(preferences, kind, batch.ready, total, batch.failed, true);
      return resume ? bucket : null;
    } catch (Exception e) {
      publish(preferences, stopped.getAsBoolean() || Thread.currentThread().isInterrupted()
          ? JobState.Kind.PAUSED : failureKind(batch),
          batch == null ? 0 : batch.ready, total, batch == null ? 0 : batch.failed, batch != null);
      return null;
    }
  }

  private static JobState.Kind failureKind(Batch batch) {
    return batch != null && batch.ready > 0 ? JobState.Kind.PARTIAL : JobState.Kind.FAILED;
  }

  static void transferBatch(SharedPreferences preferences, List<Candidate> photos, Batch batch,
      BooleanSupplier stopped, BooleanSupplier mayAdmit, Predicate<Candidate> transfer) throws Exception {
    if (batch.processed() >= photos.size() || stopped.getAsBoolean()) return;
    DiagnosticExecutor transfers = new DiagnosticExecutor("E2AutoSync.transfer", TRANSFERS);
    ExecutorCompletionService<Boolean> completed = new ExecutorCompletionService<>(transfers);
    int next = batch.processed();
    try {
      while (!stopped.getAsBoolean()) {
        while (next < photos.size() && next - batch.processed() < TRANSFERS
            && !stopped.getAsBoolean() && mayAdmit.getAsBoolean()) {
          Candidate photo = photos.get(next++);
          completed.submit(() -> transfer.test(photo));
        }
        if (next == batch.processed()) break;
        Future<Boolean> result = completed.poll(250, TimeUnit.MILLISECONDS);
        if (result == null) continue;
        if (stopped.getAsBoolean()) break;
        if (result.get()) batch.ready++;
        else batch.failed++;
        publish(preferences, JobState.Kind.RUNNING, batch.ready, photos.size(), batch.failed, true);
      }
    } finally {
      stopTransfers(transfers);
    }
  }

  static String batchIdentity(String bucket, List<Candidate> photos) throws IOException {
    StringBuilder snapshot = new StringBuilder(bucket);
    for (Candidate candidate : photos) {
      PhotoRepository.Photo photo = candidate.photo();
      snapshot.append('\0').append(photo.uri).append('\0').append(candidate.volume())
          .append('\0').append(candidate.version()).append('\0').append(candidate.generation())
          .append('\0').append(photo.name).append('\0').append(photo.folder)
          .append('\0').append(photo.size).append('\0').append(photo.modified)
          .append('\0').append(photo.taken).append('\0').append(photo.added);
    }
    return PhotoRepository.digest(snapshot.toString());
  }

  static boolean needsCatalog(E2Photos photos, String bucket, boolean hasCleanup,
      Long snapshotRevision) throws Exception {
    return photos.catalogPending(bucket) || (hasCleanup && (snapshotRevision == null
        || snapshotRevision != photos.settings.records.indexRevision()));
  }

  /** Keeps MediaStore identities separate from unrelated document-tree and Amazon rows. */
  static List<Candidate> candidates(Context context) throws Exception {
    if (!E2Settings.hasPhotoAccess(context)) throw new SecurityException("原本へのアクセスが必要です");
    Uri files = MediaStore.Files.getContentUri("external");
    String[] columns = {"_id", "_display_name", "relative_path", "_size", "date_modified",
        "datetaken", "date_added", MediaStore.MediaColumns.GENERATION_MODIFIED,
        MediaStore.MediaColumns.VOLUME_NAME};
    String[] patterns = java.util.stream.Stream.concat(
        java.util.Arrays.stream(RawFormats.patterns()),
        java.util.Arrays.stream(VideoFormats.patterns())).toArray(String[]::new);
    String where = "(" + String.join(" OR ", Collections.nCopies(patterns.length,
        MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?")) + ")";
    List<Candidate> photos = new ArrayList<>();
    java.util.Map<String, String> versions = new java.util.HashMap<>();
    try (Cursor rows = context.getContentResolver().query(files, columns, where, patterns,
        MediaStore.MediaColumns._ID + " ASC, " + MediaStore.MediaColumns.VOLUME_NAME + " ASC")) {
      if (rows == null) throw new IOException("写真を取得できません");
      while (rows.moveToNext() && !Thread.currentThread().isInterrupted()) {
        if (rows.isNull(1) || rows.isNull(2)) throw new IOException("写真の情報を取得できません");
        PhotoRepository.Photo photo = new PhotoRepository.Photo(
            ContentUris.withAppendedId(files, rows.getLong(0)), rows.getString(1),
            rows.getString(2), rows.getLong(3), rows.getLong(4), rows.getLong(5),
            SyncPolicy.secondsToMillis(rows.getLong(6)), 0);
        String volume = rows.getString(8);
        if (volume == null || volume.isEmpty()) throw new IOException("写真の保存先を取得できません");
        String version = versions.computeIfAbsent(volume, v -> MediaStore.getVersion(context, v));
        if (version == null || version.isEmpty()) throw new IOException("写真の変更履歴を取得できません");
        photos.add(new Candidate(photo, volume, version, rows.getLong(7)));
      }
    }
    return photos;
  }

  private static boolean transfer(Context context, PhotoRepository repository, E2Photos e2,
      Candidate candidate, String bucket, BooleanSupplier stopped) {
    BooleanSupplier cancelled = () -> stopped.getAsBoolean() || Thread.currentThread().isInterrupted();
    if (cancelled.getAsBoolean()) return false;
    try {
      prepare(context, repository, e2, candidate, bucket, cancelled);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static void stopTransfers(DiagnosticExecutor transfers) {
    transfers.shutdownNow();
    // Keep the sweep active until workers release the current staged file.
    boolean interrupted = Thread.interrupted();
    try {
      while (!transfers.isTerminated()) {
        try {
          transfers.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private static void prepare(Context context, PhotoRepository repository, E2Photos e2,
      Candidate candidate, String bucket, BooleanSupplier stopped) throws Exception {
    PhotoRepository.Photo photo = candidate.photo();
    if (photo.size > E2Archive.ORIGINAL_LIMIT)
      throw new IOException("原本のサイズが対応範囲外です");
    requireCurrentRevision(context, candidate);
    String revision = Long.toString(candidate.generation());
    String fallbackHash = null;
    if (candidate.generation() <= 0) {
      try (java.io.InputStream input = PhotoRepository.original(context, photo)) {
        fallbackHash = E2Archive.readHash(input, null, photo.size);
        revision = fallbackHash;
      }
    }
    String key = key(candidate, bucket, revision);
    File directory = new File(context.getFilesDir(), "e2-previews");
    File preview = new File(directory, key + ".webp");
    File thumbnail = new File(directory, key + "-thumb.webp");
    JSONObject record = e2.settings.records.get(key);
    boolean verified = record != null && "verified".equals(record.optString("state"));
    if (!verified && !stopped.getAsBoolean()) {
      e2.saveIndexed(candidate, key, bucket, fallbackHash);
    }
    if (photo.isJpeg() && (!thumbnail.isFile() || !preview.isFile())
        && !stopped.getAsBoolean()) {
      try {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
          throw new IOException("プレビューを保存できません");
        PhotoRepository.Preview original = repository.preview(photo, stopped);
        CompactPreview.save(original, preview);
        CompactPreview.save(original, thumbnail, 256);
      } catch (IOException | RuntimeException unavailablePreview) {
        // The original remains protected. Cleanup independently requires both offline images.
        if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted())
          throw new IOException("同期を中断しました", unavailablePreview);
      }
    }
    if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new IOException("同期を中断しました");
  }

  private static Publication tryPublishCatalog(Context context, E2Photos photos, String bucket,
      BooleanSupplier stopped) throws Exception {
    requireDestination(context, bucket, stopped);
    long revision = photos.settings.records.indexRevision();
    try {
      E2RecoveryCatalog.Snapshot snapshot = photos.publishCatalogIfAny(bucket);
      requireDestination(context, bucket, stopped);
      state(context).edit().remove("failureReason").apply();
      return new Publication(snapshot, revision);
    } catch (Exception failure) {
      requireDestination(context, bucket, stopped);
      state(context).edit().putString("failureReason",
          "復元一覧をクラウドに保存できませんでした。端末原本は整理せず、次回の同期で再試行します").apply();
      return null;
    }
  }

  private static void requireDestination(Context context, String bucket, BooleanSupplier stopped)
      throws IOException {
    if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()
        || !E2SyncJob.enabled(context) || !E2SyncJob.fullMediaConsent(context)
        || !E2Settings.hasMediaAccess(context)
        || !bucket.equals(state(context).getString("destination", ""))
        || !bucket.equals(E2Settings.configuredBucket(context)))
      throw new IOException("同期を中断したか、保存先が変わりました");
  }

  static String key(Candidate candidate, String bucket, String revision) throws IOException {
    PhotoRepository.Photo photo = candidate.photo();
    return PhotoRepository.digest("e2-auto\n" + bucket + "\n"
        + photo.uri + "\n" + ArchiveStore.key(photo) + "\n" + candidate.volume() + "\n"
        + candidate.version() + "\n" + revision).substring(0, 32);
  }

  static void requireCurrentRevision(Context context, Candidate candidate) throws IOException {
    PhotoRepository.Photo photo = candidate.photo();
    String[] fields = {MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.GENERATION_MODIFIED, MediaStore.MediaColumns.VOLUME_NAME};
    try (Cursor row = context.getContentResolver().query(photo.uri, fields, null, null, null)) {
      if (row == null || !row.moveToFirst() || !photo.name.equals(row.getString(0))
          || !photo.folder.equals(row.getString(1)) || photo.size != row.getLong(2)
          || photo.modified != row.getLong(3) || candidate.generation() != row.getLong(4)
          || !candidate.volume().equals(row.getString(5))
          || !candidate.version().equals(MediaStore.getVersion(context, candidate.volume())))
        throw new IOException("原本が変わりました。次回の同期で確認します");
    }
  }

  private static void publish(SharedPreferences prefs, JobState.Kind kind, int ready,
      int total, int failed, boolean known) {
    JobState.write(prefs.edit(), "", kind, System.currentTimeMillis())
        .putBoolean("CountsKnown", known).putInt("ready", ready)
        .putInt("total", total).putInt("failed", failed).apply();
  }
}
