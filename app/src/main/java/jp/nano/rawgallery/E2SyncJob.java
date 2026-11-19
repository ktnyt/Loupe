package jp.nano.rawgallery;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Automatic e2 transfers use their own scheduler and never change Amazon's settings. */
public final class E2SyncJob extends JobService {
  private static final int PERIODIC = 77;
  private static final int FIRST = 78;
  private static final int CONTINUATION = 79;
  private static final long CONTINUATION_DELAY_MS = 15_000;
  private static final int MEDIA_CONSENT_VERSION = 2;
  private static final AtomicBoolean RUNNING = new AtomicBoolean();
  static final java.util.concurrent.locks.ReentrantLock ORIGINALS =
      new java.util.concurrent.locks.ReentrantLock();
  private final ExecutorService worker = new DiagnosticExecutor("E2SyncJob.worker", 1);
  private JobParameters current;
  private AtomicBoolean stopped;
  private volatile Thread runningThread;

  static boolean enabled(Context context) {
    return E2AutoSync.state(context).getBoolean("enabled", false);
  }

  static boolean fullMediaConsent(Context context) {
    return E2AutoSync.state(context).getInt("fullMediaConsent", 0) == MEDIA_CONSENT_VERSION;
  }

  static boolean running() {
    return RUNNING.get();
  }

  static JobState snapshot(Context context) {
    return E2AutoSync.snapshot(context, RUNNING.get());
  }

  static String describe(boolean enabled, JobState state) {
    String label;
    if (!enabled) label = state.updatedAt == 0 ? "未開始" : "一時停止中";
    else label = switch (state.kind) {
      case RUNNING -> "処理中";
      case COMPLETE -> "完了";
      case PARTIAL -> "一部失敗";
      case FAILED -> "失敗";
      case AUTH_REQUIRED -> "接続の確認が必要";
      default -> "待機中";
    };
    if (!state.countsKnown) return label;
    int waiting = Math.max(0, state.total - state.ready - state.failed);
    return label + " · 確認済み " + state.ready + " / " + state.total
        + "枚 · 待ち " + waiting + "枚 · 失敗 " + state.failed + "枚";
  }

  static void setEnabled(Context context, boolean enabled) {
    setEnabled(context, enabled, null);
  }

  static void setEnabled(Context context, boolean enabled, String bucket) {
    if (enabled && !ORIGINALS.tryLock())
      throw new IllegalStateException("端末原本の整理が終わるまで待ってください");
    try {
      synchronized (E2Settings.DESTINATION_LOCK) {
        setEnabledLocked(context, enabled, bucket);
      }
    } finally {
      if (enabled) ORIGINALS.unlock();
    }
  }

  private static void setEnabledLocked(Context context, boolean enabled, String bucket) {
    if (enabled && (RUNNING.get()
        || context.getSharedPreferences("settings", 0).getBoolean("cleanupEnabled", false)))
      throw new IllegalStateException("既存の原本整理を停止してから開始してください");
    if (enabled && (bucket == null || !bucket.equals(E2Settings.configuredBucket(context))))
      throw new IllegalStateException("保存先が変わりました。設定を開き直してください");
    JobScheduler scheduler = context.getSystemService(JobScheduler.class);
    android.content.SharedPreferences.Editor setting =
        E2AutoSync.state(context).edit().putBoolean("enabled", enabled)
            .putString("destination", enabled ? bucket : "");
    E2AutoSync.clearBatch(setting);
    if (enabled) setting.putInt("fullMediaConsent", MEDIA_CONSENT_VERSION);
    else setting.remove("cleanupBucket").remove("cleanupDays").remove("fullMediaConsent");
    if (!setting.commit())
      throw new IllegalStateException("自動同期の設定を保存できません");
    if (!enabled) {
      scheduler.cancel(PERIODIC);
      scheduler.cancel(FIRST);
      scheduler.cancel(CONTINUATION);
      return;
    }
    JobState.write(E2AutoSync.state(context).edit(), "", JobState.Kind.WAITING,
        System.currentTimeMillis()).putBoolean("CountsKnown", false).apply();
    ComponentName service = new ComponentName(context, E2SyncJob.class);
    int periodic = scheduler.schedule(job(PERIODIC, service).setPersisted(true)
        .setPeriodic(15 * 60 * 1000L).build());
    int first = scheduler.schedule(job(FIRST, service).build());
    if (periodic != JobScheduler.RESULT_SUCCESS || first != JobScheduler.RESULT_SUCCESS) {
      setEnabledLocked(context, false, null);
      throw new IllegalStateException("自動同期を予約できません");
    }
  }

  private static JobInfo.Builder job(int id, ComponentName service) {
    return new JobInfo.Builder(id, service)
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
        .setRequiresStorageNotLow(true);
  }

  private static void scheduleContinuation(Context context, String bucket, int finishedJobId) {
    synchronized (E2Settings.DESTINATION_LOCK) {
      if (!enabled(context) || !fullMediaConsent(context) || !E2Settings.hasMediaAccess(context)
          || bucket == null || bucket.isEmpty()
          || !bucket.equals(E2AutoSync.state(context).getString("destination", ""))
          || !bucket.equals(E2Settings.configuredBucket(context))) return;
      int jobId = finishedJobId == CONTINUATION ? FIRST : CONTINUATION;
      JobScheduler scheduler = context.getSystemService(JobScheduler.class);
      ComponentName service = new ComponentName(context, E2SyncJob.class);
      try {
        if (scheduler.schedule(job(jobId, service).setMinimumLatency(CONTINUATION_DELAY_MS).build())
            != JobScheduler.RESULT_SUCCESS) Log.w("LoupeE2Sync", "Continuation scheduling declined");
      } catch (IllegalStateException unavailable) {
        Log.w("LoupeE2Sync", "Continuation scheduler unavailable");
      }
    }
  }

  public static final class AfterUpdate extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
      if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
        scheduleContinuation(context, E2AutoSync.state(context).getString("destination", ""), 0);
      }
    }
  }

  @Override public boolean onStartJob(JobParameters params) {
    android.net.Network network = params.getNetwork();
    if (!enabled(this) || !E2Archive.unmetered(this, network)
        || !RUNNING.compareAndSet(false, true)) return false;
    current = params;
    AtomicBoolean cancel = new AtomicBoolean();
    stopped = cancel;
    worker.execute(() -> {
      runningThread = Thread.currentThread();
      String continuation = null;
      try {
        continuation = E2AutoSync.run(getApplicationContext(), network,
            () -> cancel.get() || !enabled(this) || !E2Archive.unmetered(this, network));
      } finally {
        runningThread = null;
        String destination = continuation;
        new Handler(Looper.getMainLooper()).post(() -> {
          RUNNING.set(false);
          if (current != params) return;
          current = null;
          jobFinished(params, false);
          if (destination != null && !cancel.get())
            scheduleContinuation(this, destination, params.getJobId());
        });
      }
    });
    return true;
  }

  @Override public boolean onStopJob(JobParameters params) {
    if (current == params) {
      current = null;
      interrupt();
    }
    return enabled(this);
  }

  private void interrupt() {
    if (stopped != null) stopped.set(true);
    Thread thread = runningThread;
    if (thread != null) thread.interrupt();
  }

  @Override public void onDestroy() {
    interrupt();
    worker.shutdown();
    super.onDestroy();
  }
}
