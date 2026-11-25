package jp.nano.rawgallery;

import android.app.job.*;
import android.content.*;
import android.webkit.*;
import java.util.concurrent.*;

public final class SyncJob extends JobService {
  private final ExecutorService worker = new DiagnosticExecutor("SyncJob.worker", 1);
  private Future<?> running;

  static void schedule(Context c) {
    JobScheduler scheduler = c.getSystemService(JobScheduler.class);
    if (!SettingsRepository.syncEnabled(SettingsRepository.open(c))) {
      scheduler.cancel(71);
      scheduler.cancel(72);
      return;
    }
    JobInfo periodic =
        new JobInfo.Builder(71, new ComponentName(c, SyncJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresStorageNotLow(true)
            .setPersisted(true)
            .setPeriodic(15 * 60 * 1000L)
            .build();
    if (scheduler.schedule(periodic) != JobScheduler.RESULT_SUCCESS)
      throw new IllegalStateException("同期ジョブを登録できません");
  }

  static void now(Context c) {
    if (!SettingsRepository.syncEnabled(SettingsRepository.open(c))) return;
    c.getSystemService(JobScheduler.class)
        .schedule(
            new JobInfo.Builder(72, new ComponentName(c, SyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresStorageNotLow(true)
                .build());
  }

  public boolean onStartJob(JobParameters params) {
    if (!SettingsRepository.syncEnabled(SettingsRepository.open(this))) return false;
    String cookie = CookieManager.getInstance().getCookie(CloudVerifier.ORIGIN + "/drive/v1/nodes");
    String agent =
        SettingsRepository.cloudUserAgent(SettingsRepository.open(this), WebSettings.getDefaultUserAgent(this));
    running =
        worker.submit(
            () -> {
              SyncEngine.run(getApplicationContext(), cookie, agent);
              jobFinished(params, false);
            });
    return true;
  }

  public boolean onStopJob(JobParameters params) {
    if (running != null) running.cancel(true);
    return false;
  }

  public void onDestroy() {
    worker.shutdownNow();
    super.onDestroy();
  }
}
