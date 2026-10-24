package jp.nano.rawgallery;

import android.app.job.*;
import android.content.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Maintains only the browsing index; never uploads, restores or deletes originals. */
public final class GalleryIndexJob extends JobService {
  private AtomicBoolean stopped;

  static void schedule(Context c) {
    JobScheduler scheduler = c.getSystemService(JobScheduler.class);
    if (scheduler.getPendingJob(7012) == null)
      scheduler.schedule(
          new JobInfo.Builder(7012, new ComponentName(c, GalleryIndexJob.class))
              .setPeriodic(15 * 60 * 1000L)
              .setPersisted(true)
              .build());
  }

  public boolean onStartJob(JobParameters parameters) {
    AtomicBoolean cancel = new AtomicBoolean();
    stopped = cancel;
    GalleryIndex.WORKER.execute(
        () -> {
          boolean retry = false;
          try {
            GalleryIndex.rebuild(getApplicationContext(), false, cancel::get);
          } catch (Exception e) {
            retry = true;
          }
          if (!cancel.get()) jobFinished(parameters, retry);
        });
    return true;
  }

  public boolean onStopJob(JobParameters parameters) {
    if (stopped != null) stopped.set(true);
    return true;
  }
}
