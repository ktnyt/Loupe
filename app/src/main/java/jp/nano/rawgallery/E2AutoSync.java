package jp.nano.rawgallery;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Network;
import android.net.Uri;
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
import org.json.JSONObject;

/** Persists observations of each sweep and prepares offline images for verified copies. */
final class E2AutoSync {
  private static final int TRANSFERS = 1;
  private static final String STATE = "e2_auto_sync";
  record Candidate(PhotoRepository.Photo photo, String volume, String version, long generation) {}

  static SharedPreferences state(Context context) {
    return context.getSharedPreferences(STATE, Context.MODE_PRIVATE);
  }

  static JobState snapshot(Context context, boolean running) {
    return JobState.read(state(context), "", running, true);
  }

  static void run(Context context, Network network, BooleanSupplier stopped) {
    SharedPreferences preferences = state(context);
    publish(preferences, JobState.Kind.RUNNING, 0, 0, 0, false);
    int ready = 0, failed = 0, total = 0;
    boolean counted = false;
    try (E2Photos e2 = new E2Photos(context, network)) {
      if (stopped.getAsBoolean()) {
        publish(preferences, JobState.Kind.PAUSED, 0, 0, 0, false);
        return;
      }
      if (!e2.settings.configured() || !E2Settings.hasPhotoAccess(context)) {
        publish(preferences, JobState.Kind.AUTH_REQUIRED, 0, 0, 0, false);
        return;
      }
      String bucket = preferences.getString("destination", "");
      if (bucket.isEmpty() || !bucket.equals(e2.settings.configuration().getString("bucket"))) {
        publish(preferences, JobState.Kind.AUTH_REQUIRED, 0, 0, 0, false);
        return;
      }
      e2.settings.archive(bucket).checkConnection();
      E2Cleanup.reconcile(context, e2.settings.records, bucket, stopped);
      PhotoRepository repository = new PhotoRepository(context, true);
      List<Candidate> photos = candidates(context);
      total = photos.size();
      counted = true;
      if (!photos.isEmpty()) {
        DiagnosticExecutor transfers = new DiagnosticExecutor("E2AutoSync.transfer",
            Math.min(TRANSFERS, total));
        ExecutorCompletionService<Boolean> completed = new ExecutorCompletionService<>(transfers);
        int next = 0;
        try {
          while (ready + failed < total && !stopped.getAsBoolean()) {
            while (next < total && next - ready - failed < TRANSFERS && !stopped.getAsBoolean()) {
              Candidate photo = photos.get(next++);
              completed.submit(() -> transfer(context, repository, e2, photo, bucket, stopped));
            }
            Future<Boolean> result = completed.poll(250, TimeUnit.MILLISECONDS);
            if (result == null) continue;
            if (result.get()) ready++;
            else if (!stopped.getAsBoolean()) failed++;
            publish(preferences, JobState.Kind.RUNNING, ready, total, failed, true);
          }
        } finally {
          stopTransfers(transfers);
        }
      }
      if (!stopped.getAsBoolean()) E2Cleanup.sweep(context, e2, photos, bucket, stopped);
      publish(preferences, stopped.getAsBoolean() ? JobState.Kind.PAUSED
          : failed == 0 ? JobState.Kind.COMPLETE
          : ready == 0 ? JobState.Kind.FAILED : JobState.Kind.PARTIAL, ready, total, failed, true);
    } catch (Exception e) {
      publish(preferences, stopped.getAsBoolean() ? JobState.Kind.PAUSED : JobState.Kind.FAILED,
          ready, total, failed, counted);
    }
  }

  /** Keeps MediaStore identities separate from unrelated document-tree and Amazon rows. */
  static List<Candidate> candidates(Context context) throws Exception {
    if (!E2Settings.hasPhotoAccess(context)) throw new SecurityException("原本へのアクセスが必要です");
    Uri files = MediaStore.Files.getContentUri("external");
    String[] columns = {"_id", "_display_name", "relative_path", "_size", "date_modified",
        "datetaken", "date_added", MediaStore.MediaColumns.GENERATION_MODIFIED,
        MediaStore.MediaColumns.VOLUME_NAME};
    String[] patterns = RawFormats.patterns();
    String where = "(" + String.join(" OR ", Collections.nCopies(patterns.length,
        MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?")) + ")";
    List<Candidate> photos = new ArrayList<>();
    java.util.Map<String, String> versions = new java.util.HashMap<>();
    try (Cursor rows = context.getContentResolver().query(files, columns, where, patterns, null)) {
      if (rows == null) throw new IOException("写真を取得できません");
      while (rows.moveToNext() && !Thread.currentThread().isInterrupted()) {
        if (rows.isNull(1) || rows.isNull(2)) throw new IOException("写真の情報を取得できません");
        PhotoRepository.Photo photo = new PhotoRepository.Photo(
            ContentUris.withAppendedId(files, rows.getLong(0)), rows.getString(1),
            rows.getString(2), rows.getLong(3), rows.getLong(4), rows.getLong(5),
            SyncPolicy.secondsToMillis(rows.getLong(6)), 0);
        if (!photo.isJpeg()) {
          try (PhotoRepository.DescriptorSource raw =
              new PhotoRepository.DescriptorSource(context.getContentResolver(), photo.uri, photo.size)) {
            if (!SyncPolicy.supported(RawPreview.inspect(raw).model)) continue;
          } catch (Exception unreadable) {
            // Keep unreadable candidates in the totals; the transfer reports them as failed.
          }
        }
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
    if (!thumbnail.isFile() || !preview.isFile()) {
      if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
        throw new IOException("プレビューを保存できません");
      PhotoRepository.Preview original = repository.preview(photo, stopped);
      CompactPreview.save(original, preview);
      CompactPreview.save(original, thumbnail, 256);
    }
    if (!verified && !stopped.getAsBoolean()) {
      e2.saveIndexed(candidate, key, bucket, fallbackHash);
    }
    if (stopped.getAsBoolean()) throw new IOException("同期を中断しました");
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
