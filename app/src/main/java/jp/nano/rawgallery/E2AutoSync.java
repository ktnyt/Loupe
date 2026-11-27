package jp.nano.rawgallery;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Network;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.json.JSONObject;

/** Persists observations of each sweep and prepares offline images for verified copies. */
final class E2AutoSync {
  private static final int TRANSFERS = 2;
  private static final long ADMISSION_MS = 120_000;
  private static volatile RecentTransfers currentRate;
  private static final String STATE = "e2_auto_sync";
  private static final String SETTLED_MEDIA = "settledMedia";
  private static final String SETTLED_INVENTORY = "settledInventory";
  private static final Pattern ARCHIVE_HTTP_FAILURE =
      Pattern.compile("IDrive e2の転送失敗 \\(HTTP ([0-9]{3})\\)");

  record Candidate(PhotoRepository.Photo photo, String volume, String version, long generation) {}

  private record Publication(E2RecoveryCatalog.Snapshot snapshot, long revision) {}

  record Transfer(boolean ready, String fallbackHash, boolean missingPreview) {
    Transfer(boolean ready, String fallbackHash) {
      this(ready, fallbackHash, false);
    }
  }

  interface OriginalSource {
    InputStream open(Candidate candidate) throws IOException;
  }

  static final class Batch {
    final String identity;
    int ready, failed;
    int reportedReady, reportedFailed;
    String fallbackIdentity = "";
    boolean missingPreviews;

    Batch(SharedPreferences preferences, String identity, int total) {
      this.identity = identity;
      if (!identity.equals(preferences.getString("batchIdentity", ""))) return;
      int savedReady = preferences.getInt("batchReady", 0);
      int savedFailed = preferences.getInt("batchFailed", 0);
      if (savedReady < 0 || savedFailed < 0 || (long) savedReady + savedFailed >= total) return;
      ready = reportedReady = savedReady;
      failed = reportedFailed = savedFailed;
      missingPreviews = preferences.getBoolean("batchMissingPreviews", savedReady > 0);
    }

    int processed() {
      return ready + failed;
    }

    // A candidate without a MediaStore generation can only be skipped after checking its bytes.
    void validateFallback(
        SharedPreferences preferences,
        List<Candidate> photos,
        OriginalSource originals,
        BooleanSupplier stopped)
        throws Exception {
      int prefix = processed();
      for (int i = 0; i < prefix; i++) {
        Candidate photo = photos.get(i);
        if (photo.generation() > 0) continue;
        String hash = null;
        try {
          hash = readFallback(photo, originals, stopped);
        } catch (InterruptedIOException interrupted) {
          throw interrupted;
        } catch (IOException | RuntimeException unavailable) {
          requireRunning(stopped);
        }
        extendFallback(hash);
      }
      if (!fallbackIdentity.equals(preferences.getString("batchFallbackIdentity", ""))) {
        ready = reportedReady = 0;
        failed = reportedFailed = 0;
        fallbackIdentity = "";
        missingPreviews = false;
      }
    }

    void extendFallback(String hash) throws IOException {
      fallbackIdentity =
          PhotoRepository.digest(fallbackIdentity + ":" + (hash == null ? "-" : hash));
    }

    boolean shouldContinue(int total, int readyBefore) {
      return processed() < total && ready > readyBefore;
    }

    void save(SharedPreferences.Editor editor) {
      editor
          .putString("batchIdentity", identity)
          .putInt("batchReady", ready)
          .putInt("batchFailed", failed)
          .putString("batchFallbackIdentity", fallbackIdentity)
          .putBoolean("batchMissingPreviews", missingPreviews);
    }
  }

  static void clearBatch(SharedPreferences.Editor editor) {
    editor
        .remove("batchIdentity")
        .remove("batchReady")
        .remove("batchFailed")
        .remove("batchFallbackIdentity")
        .remove("batchMissingPreviews");
  }

  static void clearSettled(SharedPreferences.Editor editor) {
    editor.remove(SETTLED_MEDIA).remove(SETTLED_INVENTORY);
  }

  static SharedPreferences state(Context context) {
    return context.getSharedPreferences(STATE, Context.MODE_PRIVATE);
  }

  static JobState snapshot(Context context, boolean running) {
    return JobState.read(state(context), "", running, true);
  }

  record Rate(double bytesPerSecond, long remainingSeconds) {}

  private static final class RecentTransfers {
    private static final long WINDOW_MS = 60_000;

    private record Sample(long time, long bytes) {}

    final String bucket;
    final long started = SystemClock.elapsedRealtime();
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private long remainingBytes;

    RecentTransfers(String bucket, long remainingBytes) {
      this.bucket = bucket;
      this.remainingBytes = remainingBytes;
    }

    synchronized void verified(long bytes, long putMs) {
      if (remainingBytes >= 0) remainingBytes = Math.max(0, remainingBytes - bytes);
      if (putMs <= 0) return;
      long now = SystemClock.elapsedRealtime();
      trim(now - WINDOW_MS);
      samples.addLast(new Sample(now, bytes));
      Log.i("LoupeE2Rate", "verified bytes=" + bytes + " put_ms=" + putMs + " elapsed_ms=" + now);
    }

    synchronized Rate estimate() {
      long now = SystemClock.elapsedRealtime();
      long from = Math.max(started, now - WINDOW_MS);
      trim(from);
      long elapsed = now - from;
      if (samples.size() < 2 || elapsed < 2000 || now - samples.getLast().time() > 15_000)
        return null;
      long bytes = 0;
      for (Sample sample : samples) bytes += sample.bytes();
      double speed = bytes * 1000.0 / elapsed;
      return new Rate(speed, remainingBytes > 0 ? (long) Math.ceil(remainingBytes / speed) : -1);
    }

    private void trim(long from) {
      while (!samples.isEmpty() && samples.getFirst().time() <= from) samples.removeFirst();
    }
  }

  static Rate rate(String bucket) {
    RecentTransfers current = currentRate;
    return current != null && current.bucket.equals(bucket) ? current.estimate() : null;
  }

  private static long remainingBytes(
      ArchiveStore store,
      List<Candidate> candidates,
      int processed,
      String bucket,
      BooleanSupplier stopped) {
    try {
      var verified = store.verifiedKeys();
      long bytes = 0;
      for (int i = processed; i < candidates.size(); i++) {
        Candidate candidate = candidates.get(i);
        // Without a MediaStore generation the key requires hashing the original. Only the
        // transfer worker does that work; an unknown estimate must never delay backup.
        long size = candidate.photo().size;
        if (stopped.getAsBoolean()
            || candidate.generation() <= 0
            || size <= 0
            || size > E2Archive.ORIGINAL_LIMIT) return -1;
        if (!verified.contains(key(candidate, bucket, Long.toString(candidate.generation()))))
          bytes = Math.addExact(bytes, size);
      }
      return bytes;
    } catch (Exception unavailable) {
      return -1;
    }
  }

  record FollowUp(String destination, boolean retryUnnecessary) {
    static final FollowUp NONE = new FollowUp(null, false);
    static final FollowUp CLEAR_RETRY = new FollowUp(null, true);
  }

  static FollowUp run(Context context, Network network, BooleanSupplier stopped) {
    long admissionDeadline = SystemClock.elapsedRealtime() + ADMISSION_MS;
    SharedPreferences preferences = state(context);
    if (preferences.contains("failureReason") || preferences.getBoolean("publishingCatalog", false))
      preferences.edit().remove("failureReason").putBoolean("publishingCatalog", false).apply();
    Batch batch = null;
    int total = 0;
    try (E2Photos e2 = new E2Photos(context, network)) {
      if (stopped.getAsBoolean()) {
        publish(preferences, JobState.Kind.PAUSED, 0, 0, 0, false);
        return FollowUp.NONE;
      }
      if (!e2.settings.configured() || !E2Settings.hasMediaAccess(context)) {
        publish(preferences, JobState.Kind.AUTH_REQUIRED, 0, 0, 0, false);
        return FollowUp.NONE;
      }
      if (!E2SyncJob.fullMediaConsent(context)) {
        preferences
            .edit()
            .putString("failureReason", "写真・動画のバックアップ範囲の確認が必要です。一時停止してから再開してください")
            .apply();
        publish(preferences, JobState.Kind.AUTH_REQUIRED, 0, 0, 0, false);
        return FollowUp.NONE;
      }
      String bucket = preferences.getString("destination", "");
      if (bucket.isEmpty() || !bucket.equals(E2Settings.configuredBucket(context))) {
        publish(preferences, JobState.Kind.AUTH_REQUIRED, 0, 0, 0, false);
        return FollowUp.NONE;
      }
      // A sweep can settle only the media state observed before opening the cursor.
      String media = mediaStamp(context, bucket);
      String settledMedia = preferences.getString(SETTLED_MEDIA, "");
      String settledInventory = preferences.getString(SETTLED_INVENTORY, "");
      List<Candidate> photos = null;
      String inventory = null;
      boolean reconciled = false;
      if (media != null
          && !settledMedia.isEmpty()
          && !settledInventory.isEmpty()
          && snapshot(context, false).kind == JobState.Kind.COMPLETE
          && !E2Cleanup.enabled(context, bucket)) {
        E2Cleanup.reconcile(context, e2.settings.records, bucket, stopped);
        reconciled = true;
        boolean sameMedia = media.equals(settledMedia);
        boolean sameInventory = false;
        if (!sameMedia) {
          // A volume generation also changes for rows outside the backup's file formats.
          photos = candidates(context);
          requireRunning(stopped);
          inventory = batchIdentity(bucket, photos);
          sameInventory =
              settledInventory.equals(inventory) && media.equals(mediaStamp(context, bucket));
        }
        if ((sameMedia || sameInventory) && !e2.catalogPending(bucket)) {
          synchronized (E2Settings.DESTINATION_LOCK) {
            requireDestination(context, bucket, stopped);
            if (!E2Cleanup.enabled(context, bucket)) {
              if (!sameMedia && !preferences.edit().putString(SETTLED_MEDIA, media).commit())
                throw new IOException("同期の進捗を保存できません");
              return FollowUp.CLEAR_RETRY;
            }
          }
        }
      }

      if (!bucket.equals(e2.settings.configuration().getString("bucket"))) {
        publish(preferences, JobState.Kind.AUTH_REQUIRED, 0, 0, 0, false);
        return FollowUp.NONE;
      }
      SharedPreferences.Editor start = preferences.edit();
      clearSettled(start);
      start.apply();
      publish(preferences, JobState.Kind.RUNNING, 0, 0, 0, false);
      e2.settings.archive(bucket).checkConnection();
      if (!reconciled) E2Cleanup.reconcile(context, e2.settings.records, bucket, stopped);
      if (photos == null) {
        photos = candidates(context);
        requireRunning(stopped);
        inventory = batchIdentity(bucket, photos);
      }
      total = photos.size();
      batch = new Batch(preferences, inventory, total);
      OriginalSource originals = photo -> PhotoRepository.original(context, photo.photo());
      batch.validateFallback(preferences, photos, originals, stopped);
      int readyBefore = batch.ready;
      publish(preferences, JobState.Kind.RUNNING, batch.ready, total, batch.failed, true);
      Publication published =
          e2.catalogPending(bucket) ? tryPublishCatalog(context, e2, bucket, stopped) : null;
      PhotoRepository repository = new PhotoRepository(context, true);
      RecentTransfers recent =
          new RecentTransfers(
              bucket,
              remainingBytes(e2.settings.records, photos, batch.processed(), bucket, stopped));
      currentRate = recent;
      transferBatch(
          preferences,
          photos,
          batch,
          stopped,
          () -> SystemClock.elapsedRealtime() < admissionDeadline,
          context.getCacheDir()::getUsableSpace,
          photo -> transfer(context, repository, e2, photo, bucket, originals, stopped, recent));
      currentRate = null;

      requireDestination(context, bucket, stopped);
      boolean unfinished = batch.processed() < total;
      E2Cleanup.Plan cleanup =
          unfinished ? null : E2Cleanup.plan(context, e2, photos, bucket, stopped);
      Long snapshotRevision =
          published != null && published.snapshot() != null ? published.revision() : null;
      if (needsCatalog(e2, bucket, cleanup != null, snapshotRevision)) {
        published = tryPublishCatalog(context, e2, bucket, stopped);
        if (published == null) {
          publish(
              preferences,
              failureKind(batch),
              batch.reportedReady,
              total,
              batch.reportedFailed,
              true);
          return FollowUp.NONE;
        }
      }
      requireDestination(context, bucket, stopped);
      if (cleanup != null && published != null && published.snapshot() != null)
        E2Cleanup.sweep(context, e2, cleanup, bucket, stopped, published.snapshot());

      // Missing row generations still require the fallback byte check on future sweeps.
      boolean settle =
          !unfinished
              && batch.failed == 0
              && !batch.missingPreviews
              && cleanup == null
              && media != null
              && photos.stream().allMatch(photo -> photo.generation() > 0)
              && media.equals(mediaStamp(context, bucket));
      synchronized (E2Settings.DESTINATION_LOCK) {
        requireDestination(context, bucket, stopped);
        SharedPreferences.Editor editor = preferences.edit();
        if (unfinished) batch.save(editor);
        else clearBatch(editor);
        if (settle && !E2Cleanup.enabled(context, bucket))
          editor.putString(SETTLED_MEDIA, media).putString(SETTLED_INVENTORY, inventory);
        if (!editor.commit()) throw new IOException("同期の進捗を保存できません");
      }
      boolean resume = batch.shouldContinue(total, readyBefore);
      JobState.Kind kind =
          resume
              ? JobState.Kind.WAITING
              : unfinished || batch.failed != 0 ? failureKind(batch) : JobState.Kind.COMPLETE;
      publish(preferences, kind, batch.reportedReady, total, batch.reportedFailed, true);
      if (resume) return new FollowUp(bucket, false);
      return kind == JobState.Kind.COMPLETE
              && !batch.missingPreviews
              && !E2Cleanup.enabled(context, bucket)
          ? FollowUp.CLEAR_RETRY
          : FollowUp.NONE;
    } catch (Exception e) {
      publish(
          preferences,
          stopped.getAsBoolean() || Thread.currentThread().isInterrupted()
              ? JobState.Kind.PAUSED
              : failureKind(batch),
          batch == null ? 0 : batch.reportedReady,
          total,
          batch == null ? 0 : batch.reportedFailed,
          batch != null);
      return FollowUp.NONE;
    } finally {
      currentRate = null;
    }
  }

  private static JobState.Kind failureKind(Batch batch) {
    return batch != null && batch.reportedReady > 0 ? JobState.Kind.PARTIAL : JobState.Kind.FAILED;
  }

  static void transferBatch(
      SharedPreferences preferences,
      List<Candidate> photos,
      Batch batch,
      BooleanSupplier stopped,
      BooleanSupplier mayAdmit,
      LongSupplier usableSpace,
      Function<Candidate, Transfer> transfer)
      throws Exception {
    if (batch.processed() >= photos.size() || stopped.getAsBoolean()) return;
    DiagnosticExecutor transfers = new DiagnosticExecutor("E2AutoSync.transfer", TRANSFERS);
    ExecutorCompletionService<Transfer> completions = new ExecutorCompletionService<>(transfers);
    Map<Future<Transfer>, Integer> inFlight = new HashMap<>();
    Map<Integer, Transfer> completed = new HashMap<>();
    int start = batch.processed();
    int next = start;
    try {
      while (!stopped.getAsBoolean()) {
        // An expired startup budget still allows the first candidate. When refilling the second
        // slot, size the staging check against the one transfer still in flight.
        while (next < photos.size()
            && inFlight.size() < TRANSFERS
            && !stopped.getAsBoolean()
            && (next == start || mayAdmit.getAsBoolean())
            && (inFlight.isEmpty()
                || canStageTogether(
                    usableSpace.getAsLong(),
                    photos.get(inFlight.values().iterator().next()).photo().size,
                    photos.get(next).photo().size))) {
          Candidate photo = photos.get(next);
          inFlight.put(completions.submit(() -> transfer.apply(photo)), next++);
        }
        if (inFlight.isEmpty()) break;
        Future<Transfer> finished = completions.poll(250, TimeUnit.MILLISECONDS);
        if (finished == null) continue;
        if (stopped.getAsBoolean()) break;
        int index = inFlight.remove(finished);
        Transfer outcome = finished.get();
        if (outcome.ready()
            && photos.get(index).generation() <= 0
            && outcome.fallbackHash() == null) outcome = new Transfer(false, null);
        if (outcome.ready()) batch.reportedReady++;
        else batch.reportedFailed++;
        completed.put(index, outcome);
        // Actual completions free slots immediately. Only the uninterrupted prefix can advance the
        // durable checkpoint, which also hashes fallback identities in candidate order.
        for (Transfer prefix; (prefix = completed.remove(batch.processed())) != null; ) {
          if (photos.get(batch.processed()).generation() <= 0)
            batch.extendFallback(prefix.fallbackHash());
          batch.missingPreviews |= prefix.missingPreview();
          if (prefix.ready()) batch.ready++;
          else batch.failed++;
        }
        publish(
            preferences,
            JobState.Kind.RUNNING,
            batch.reportedReady,
            photos.size(),
            batch.reportedFailed,
            true);
      }
    } finally {
      stopTransfers(transfers);
    }
  }

  private static boolean canStageTogether(long usable, long first, long second) {
    // Counting the whole first file even if already staged is conservative. Wait for its cleanup
    // when the available space cannot cover both, so each can still run by itself.
    return first > 0
        && second > 0
        && first <= E2Archive.ORIGINAL_LIMIT
        && second <= E2Archive.ORIGINAL_LIMIT
        && E2Archive.hasTemporarySpace(usable, first + second);
  }

  static String batchIdentity(String bucket, List<Candidate> photos) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    identityField(digest, bucket);
    for (Candidate candidate : photos) {
      PhotoRepository.Photo photo = candidate.photo();
      identityField(digest, photo.uri.toString());
      identityField(digest, candidate.volume());
      identityField(digest, candidate.version());
      identityField(digest, candidate.generation());
      identityField(digest, photo.name);
      identityField(digest, photo.folder);
      identityField(digest, photo.size);
      identityField(digest, photo.modified);
      identityField(digest, photo.taken);
      identityField(digest, photo.added);
    }
    return Base64.getEncoder().encodeToString(digest.digest());
  }

  private static void identityField(MessageDigest digest, String value) {
    byte[] field = value.getBytes(StandardCharsets.UTF_8);
    identityField(digest, field.length);
    digest.update(field);
  }

  private static void identityField(MessageDigest digest, long value) {
    for (int shift = 56; shift >= 0; shift -= 8) digest.update((byte) (value >>> shift));
  }

  static boolean needsCatalog(
      E2Photos photos, String bucket, boolean hasCleanup, Long snapshotRevision) throws Exception {
    return photos.catalogPending(bucket)
        || (hasCleanup
            && (snapshotRevision == null
                || snapshotRevision != photos.settings.records.indexRevision()));
  }

  private static String mediaStamp(Context context, String bucket) {
    try {
      List<String> volumes = new ArrayList<>(MediaStore.getExternalVolumeNames(context));
      if (volumes.isEmpty()) return null;
      Collections.sort(volumes);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      identityField(digest, bucket);
      identityField(digest, android.os.Environment.isExternalStorageManager() ? 1 : 0);
      String[] patterns = mediaPatterns();
      identityField(digest, patterns.length);
      for (String pattern : patterns) identityField(digest, pattern);
      for (String volume : volumes) {
        String version = MediaStore.getVersion(context, volume);
        long generation = MediaStore.getGeneration(context, volume);
        if (version == null || version.isEmpty() || generation < 0) return null;
        identityField(digest, volume);
        identityField(digest, version);
        identityField(digest, generation);
      }
      return Base64.getEncoder().encodeToString(digest.digest());
    } catch (NoSuchAlgorithmException | RuntimeException unavailable) {
      return null;
    }
  }

  private static String[] mediaPatterns() {
    return Stream.concat(
            Arrays.stream(RawFormats.patterns()), Arrays.stream(VideoFormats.patterns()))
        .toArray(String[]::new);
  }

  /** Keeps MediaStore identities separate from unrelated document-tree and Amazon rows. */
  static List<Candidate> candidates(Context context) throws Exception {
    if (!E2Settings.hasPhotoAccess(context)) throw new SecurityException("オリジナルへのアクセスが必要です");
    Uri files = MediaStore.Files.getContentUri("external");
    String[] columns = {
      "_id",
      "_display_name",
      "relative_path",
      "_size",
      "date_modified",
      "datetaken",
      "date_added",
      MediaStore.MediaColumns.GENERATION_MODIFIED,
      MediaStore.MediaColumns.VOLUME_NAME
    };
    String[] patterns = mediaPatterns();
    String where =
        "("
            + String.join(
                " OR ",
                Collections.nCopies(
                    patterns.length, MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?"))
            + ")";
    List<Candidate> photos = new ArrayList<>();
    java.util.Map<String, String> versions = new java.util.HashMap<>();
    try (Cursor rows =
        context
            .getContentResolver()
            .query(
                files,
                columns,
                where,
                patterns,
                MediaStore.MediaColumns._ID
                    + " ASC, "
                    + MediaStore.MediaColumns.VOLUME_NAME
                    + " ASC")) {
      if (rows == null) throw new IOException("写真を取得できません");
      while (rows.moveToNext() && !Thread.currentThread().isInterrupted()) {
        if (rows.isNull(1) || rows.isNull(2)) throw new IOException("写真の情報を取得できません");
        PhotoRepository.Photo photo =
            new PhotoRepository.Photo(
                ContentUris.withAppendedId(files, rows.getLong(0)),
                rows.getString(1),
                rows.getString(2),
                rows.getLong(3),
                rows.getLong(4),
                rows.getLong(5),
                SyncPolicy.secondsToMillis(rows.getLong(6)),
                0);
        String volume = rows.getString(8);
        if (volume == null || volume.isEmpty()) throw new IOException("写真の保存先を取得できません");
        String version = versions.computeIfAbsent(volume, v -> MediaStore.getVersion(context, v));
        if (version == null || version.isEmpty()) throw new IOException("写真の変更履歴を取得できません");
        photos.add(new Candidate(photo, volume, version, rows.getLong(7)));
      }
    }
    return photos;
  }

  private static Transfer transfer(
      Context context,
      PhotoRepository repository,
      E2Photos e2,
      Candidate candidate,
      String bucket,
      OriginalSource originals,
      BooleanSupplier stopped,
      RecentTransfers recent) {
    BooleanSupplier cancelled =
        () -> stopped.getAsBoolean() || Thread.currentThread().isInterrupted();
    String fallbackHash = null;
    try {
      requireRunning(cancelled);
      requireCurrentRevision(context, candidate);
      if (candidate.generation() <= 0) fallbackHash = readFallback(candidate, originals, cancelled);
      boolean previewReady =
          prepare(context, repository, e2, candidate, bucket, fallbackHash, cancelled, recent);
      return new Transfer(true, fallbackHash, !previewReady);
    } catch (Exception e) {
      return new Transfer(false, fallbackHash);
    }
  }

  private static void requireRunning(BooleanSupplier stopped) throws IOException {
    if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new IOException("同期を中断しました");
  }

  private static String readFallback(
      Candidate candidate, OriginalSource originals, BooleanSupplier stopped) throws Exception {
    requireRunning(stopped);
    long size = candidate.photo().size;
    if (size <= 0 || size > E2Archive.ORIGINAL_LIMIT) throw new IOException("オリジナルのサイズが対応範囲外です");
    try (InputStream input = originals.open(candidate)) {
      String hash = E2Archive.readHash(input, null, size);
      requireRunning(stopped);
      return hash;
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

  private static boolean prepare(
      Context context,
      PhotoRepository repository,
      E2Photos e2,
      Candidate candidate,
      String bucket,
      String fallbackHash,
      BooleanSupplier stopped,
      RecentTransfers recent)
      throws Exception {
    PhotoRepository.Photo photo = candidate.photo();
    if (photo.size > E2Archive.ORIGINAL_LIMIT) throw new IOException("オリジナルのサイズが対応範囲外です");
    String revision = fallbackHash == null ? Long.toString(candidate.generation()) : fallbackHash;
    String key = key(candidate, bucket, revision);
    File directory = new File(context.getFilesDir(), "e2-previews");
    File preview = new File(directory, key + ".webp");
    File thumbnail = new File(directory, key + "-thumb.webp");
    JSONObject record = e2.settings.records.get(key);
    boolean verified = record != null && "verified".equals(record.optString("state"));
    if (!verified && !stopped.getAsBoolean()) {
      long putMs = e2.saveIndexed(candidate, key, bucket, fallbackHash);
      recent.verified(photo.size, putMs);
    }
    if (photo.isJpeg() && (!thumbnail.isFile() || !preview.isFile()) && !stopped.getAsBoolean()) {
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
        return false;
      }
    }
    if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new IOException("同期を中断しました");
    return true;
  }

  private static Publication tryPublishCatalog(
      Context context, E2Photos photos, String bucket, BooleanSupplier stopped) throws Exception {
    requireDestination(context, bucket, stopped);
    long revision = photos.settings.records.indexRevision();
    long start = SystemClock.elapsedRealtime();
    try {
      state(context).edit().putBoolean("publishingCatalog", true).apply();
      E2RecoveryCatalog.Snapshot snapshot = photos.publishCatalogIfAny(bucket);
      requireDestination(context, bucket, stopped);
      state(context).edit().remove("failureReason").apply();
      return new Publication(snapshot, revision);
    } catch (Exception failure) {
      requireDestination(context, bucket, stopped);
      String message = failure.getMessage();
      Matcher http = ARCHIVE_HTTP_FAILURE.matcher(message == null ? "" : message);
      Log.w(
          "LoupeE2Sync",
          "Catalog publication failed: "
              + failure.getClass().getSimpleName()
              + " elapsedMs="
              + (SystemClock.elapsedRealtime() - start)
              + (http.matches() ? " http=" + http.group(1) : ""));
      state(context)
          .edit()
          .putString("failureReason", "復元一覧をクラウドに保存できませんでした。端末オリジナルは整理せず、次回の同期で再試行します")
          .apply();
      return null;
    } finally {
      state(context).edit().putBoolean("publishingCatalog", false).apply();
    }
  }

  private static void requireDestination(Context context, String bucket, BooleanSupplier stopped)
      throws IOException {
    if (stopped.getAsBoolean()
        || Thread.currentThread().isInterrupted()
        || !E2SyncJob.enabled(context)
        || !E2SyncJob.fullMediaConsent(context)
        || !E2Settings.hasMediaAccess(context)
        || !bucket.equals(state(context).getString("destination", ""))
        || !bucket.equals(E2Settings.configuredBucket(context)))
      throw new IOException("同期を中断したか、保存先が変わりました");
  }

  static String key(Candidate candidate, String bucket, String revision) throws IOException {
    PhotoRepository.Photo photo = candidate.photo();
    return PhotoRepository.digest(
            "e2-auto\n"
                + bucket
                + "\n"
                + photo.uri
                + "\n"
                + ArchiveStore.key(photo)
                + "\n"
                + candidate.volume()
                + "\n"
                + candidate.version()
                + "\n"
                + revision)
        .substring(0, 32);
  }

  static void requireCurrentRevision(Context context, Candidate candidate) throws IOException {
    PhotoRepository.Photo photo = candidate.photo();
    String[] fields = {
      MediaStore.MediaColumns.DISPLAY_NAME,
      MediaStore.MediaColumns.RELATIVE_PATH,
      MediaStore.MediaColumns.SIZE,
      MediaStore.MediaColumns.DATE_MODIFIED,
      MediaStore.MediaColumns.GENERATION_MODIFIED,
      MediaStore.MediaColumns.VOLUME_NAME
    };
    try (Cursor row = context.getContentResolver().query(photo.uri, fields, null, null, null)) {
      if (row == null
          || !row.moveToFirst()
          || !photo.name.equals(row.getString(0))
          || !photo.folder.equals(row.getString(1))
          || photo.size != row.getLong(2)
          || photo.modified != row.getLong(3)
          || candidate.generation() != row.getLong(4)
          || !candidate.volume().equals(row.getString(5))
          || !candidate.version().equals(MediaStore.getVersion(context, candidate.volume())))
        throw new IOException("オリジナルが変わりました。次回の同期で確認します");
    }
  }

  private static void publish(
      SharedPreferences prefs,
      JobState.Kind kind,
      int ready,
      int total,
      int failed,
      boolean known) {
    SharedPreferences.Editor edit =
        JobState.write(prefs.edit(), "", kind, System.currentTimeMillis())
            .putBoolean("CountsKnown", known)
            .putInt("ready", ready)
            .putInt("total", total)
            .putInt("failed", failed);
    String destination = prefs.getString("destination", "");
    if (known && !destination.isEmpty())
      edit.putString("lastCountBucket", destination)
          .putInt("lastReady", ready)
          .putInt("lastTotal", total)
          .putInt("lastFailed", failed);
    edit.apply();
  }
}
