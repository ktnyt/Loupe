package jp.nano.rawgallery;

import android.app.job.*;
import android.content.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local-only, resumable preview preparation. Originals are never changed or restored. */
public final class PreviewPreparationJob extends JobService {
  private static final int JOB = 7013;
  private static final java.util.concurrent.ExecutorService WORKER =
      new DiagnosticExecutor("PreviewPreparation.worker", 1);
  private static volatile int visible;
  private AtomicBoolean stopped;
  static void enter() { visible++; DiagnosticLog.event("preparation.pause", 0); }
  static void leave(Context c) { visible = Math.max(0, visible - 1); if (visible == 0) schedule(c); }
  static boolean browsing() { return visible > 0; }
  static void schedule(Context c) {
    JobScheduler scheduler = c.getSystemService(JobScheduler.class);
    if (scheduler.getPendingJob(JOB) == null)
      scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(c, PreviewPreparationJob.class))
          .setMinimumLatency(1500).setRequiresStorageNotLow(true).setPersisted(true).build());
  }
  static boolean local(PhotoRepository.Photo p) {
    String scheme = p.uri.getScheme(), authority = p.uri.getAuthority();
    return "file".equals(scheme) || ("content".equals(scheme)
        && ("media".equals(authority) || "com.android.externalstorage.documents".equals(authority)));
  }
  static String status(Context c) {
    android.content.SharedPreferences p = c.getSharedPreferences("preview-preparation", 0);
    String state = browsing() ? "閲覧中のため一時停止" : p.getString("state", "準備を待っています");
    return p.getInt("ready", 0) + " / " + p.getInt("total", 0) + "枚の準備ができました\n"
        + state + "\n準備できなかった写真：" + p.getInt("failed", 0) + "枚"
        + "\n充電中以外も、Loupeを閉じている間に進めます。写真の原本は変更しません。";
  }
  private void save(int ready, int total, int failed, String state) {
    getSharedPreferences("preview-preparation", 0).edit().putInt("ready", ready)
        .putInt("total", total).putInt("failed", failed).putString("state", state).apply();
    DiagnosticLog.event("preparation.progress", 0, "ready", ready, "total", total, "failed", failed);
  }
  public boolean onStartJob(JobParameters params) {
    AtomicBoolean cancel = new AtomicBoolean(); stopped = cancel;
    WORKER.execute(() -> {
      int ready = 0, failed = 0, total = 0;
      boolean retry = false;
      try (GalleryIndex index = new GalleryIndex(this)) {
        PhotoRepository repository = new PhotoRepository(this);
        List<PhotoRepository.Photo> photos = index.load(null, "すべて").photos;
        Set<String> keys = new HashSet<>();
        for (PhotoRepository.Photo p : photos) if (local(p)) {
          total++; keys.add(PhotoRepository.previewKey(p)); if (repository.prepared(p)) ready++;
        }
        repository.prunePrepared(keys, () -> cancel.get() || browsing());
        save(ready, total, 0, "準備中");
        for (PhotoRepository.Photo p : photos) {
          if (cancel.get() || browsing()) { retry = true; break; }
          if (!local(p) || repository.prepared(p)) continue;
          if (getFilesDir().getUsableSpace() < 1024L * 1024 * 1024) {
            save(ready, total, failed, "空き容量が少ないため一時停止");
            retry = true; break;
          }
          try {
            if (!repository.prepare(p, () -> cancel.get() || browsing())) { retry = true; break; }
            ready++;
          }
          catch (Exception e) { failed++; DiagnosticLog.event("preparation.item_fail", 0, "error", e.getClass().getSimpleName()); }
          save(ready, total, failed, "準備中");
        }
        if (browsing()) save(ready, total, failed, "閲覧中のため一時停止");
        else if (!retry && !cancel.get()) save(ready, total, failed,
            failed == 0 ? "準備できました" : "一部の写真を準備できませんでした");
      } catch (Exception e) {
        save(ready, total, failed, "準備を再開できませんでした"); retry = true;
      }
      if (!cancel.get()) jobFinished(params, retry);
    });
    return true;
  }
  public boolean onStopJob(JobParameters params) { if (stopped != null) stopped.set(true); return true; }
}
