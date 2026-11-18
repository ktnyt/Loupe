package jp.nano.rawgallery;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
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

  static SharedPreferences state(Context context) {
    return context.getSharedPreferences(STATE, Context.MODE_PRIVATE);
  }

  static JobState snapshot(Context context, boolean running) {
    return JobState.read(state(context), "", running, true);
  }

  static void run(Context context, BooleanSupplier stopped) {
    SharedPreferences preferences = state(context);
    publish(preferences, JobState.Kind.RUNNING, 0, 0, 0, false);
    int ready = 0, failed = 0, total = 0;
    boolean counted = false;
    try (E2Photos e2 = new E2Photos(context)) {
      if (stopped.getAsBoolean()) {
        publish(preferences, JobState.Kind.PAUSED, 0, 0, 0, false);
        return;
      }
      if (!e2.settings.configured()) {
        publish(preferences, JobState.Kind.AUTH_REQUIRED, 0, 0, 0, false);
        return;
      }
      String bucket = e2.settings.configuration().getString("bucket");
      e2.settings.archive(bucket).checkConnection();
      PhotoRepository repository = new PhotoRepository(context, true);
      List<PhotoRepository.Photo> photos = SyncEngine.candidates(context);
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
              PhotoRepository.Photo photo = photos.get(next++);
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
      publish(preferences, stopped.getAsBoolean() ? JobState.Kind.PAUSED
          : failed == 0 ? JobState.Kind.COMPLETE
          : ready == 0 ? JobState.Kind.FAILED : JobState.Kind.PARTIAL, ready, total, failed, true);
    } catch (Exception e) {
      publish(preferences, stopped.getAsBoolean() ? JobState.Kind.PAUSED : JobState.Kind.FAILED,
          ready, total, failed, counted);
    }
  }

  private static boolean transfer(Context context, PhotoRepository repository, E2Photos e2,
      PhotoRepository.Photo photo, String bucket, BooleanSupplier stopped) {
    BooleanSupplier cancelled = () -> stopped.getAsBoolean() || Thread.currentThread().isInterrupted();
    if (cancelled.getAsBoolean()) return false;
    try {
      prepare(context, repository, e2, photo, bucket, cancelled);
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
      PhotoRepository.Photo photo, String bucket, BooleanSupplier stopped) throws Exception {
    String key = PhotoRepository.digest("e2-auto\n" + bucket + "\n"
        + photo.uri + "\n" + ArchiveStore.key(photo)).substring(0, 32);
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
      e2.saveIndexed(photo, key, bucket);
    }
    if (stopped.getAsBoolean()) throw new IOException("同期を中断しました");
  }

  private static void publish(SharedPreferences prefs, JobState.Kind kind, int ready,
      int total, int failed, boolean known) {
    JobState.write(prefs.edit(), "", kind, System.currentTimeMillis())
        .putBoolean("CountsKnown", known).putInt("ready", ready)
        .putInt("total", total).putInt("failed", failed).apply();
  }
}
