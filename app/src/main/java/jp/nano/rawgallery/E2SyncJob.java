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
import android.provider.MediaStore;
import android.util.Log;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Automatic e2 transfers use their own scheduler and never change Amazon's settings. */
public final class E2SyncJob extends JobService {
  private static final int PERIODIC = 77;
  private static final int FIRST = 78;
  private static final int CONTINUATION = 79;
  private static final int MEDIA_CHANGED = 80;
  private static final long RETRY_DELAY_MS = 15 * 60 * 1000L;
  private static final long RECOVERY_INTERVAL_MS = 12 * 60 * 60 * 1000L;
  private static final long CONTINUATION_DELAY_MS = 15_000;
  private static final int MEDIA_CONSENT_VERSION = 2;
  private static final AtomicBoolean RUNNING = new AtomicBoolean();
  private static final AtomicBoolean OPEN_RECOVERY_QUEUED = new AtomicBoolean();
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

  static void setEnabled(Context context, boolean enabled) {
    setEnabled(context, enabled, null);
  }

  static void setEnabled(Context context, boolean enabled, String bucket) {
    if (enabled && !ORIGINALS.tryLock()) throw new IllegalStateException("端末オリジナルの整理が終わるまで待ってください");
    try {
      synchronized (E2Settings.DESTINATION_LOCK) {
        setEnabledLocked(context, enabled, bucket);
      }
    } finally {
      if (enabled) ORIGINALS.unlock();
    }
  }

  private static void setEnabledLocked(Context context, boolean enabled, String bucket) {
    if (enabled && RUNNING.get()) throw new IllegalStateException("既存のオリジナル整理を停止してから開始してください");
    if (enabled && (bucket == null || !bucket.equals(E2Settings.configuredBucket(context))))
      throw new IllegalStateException("保存先が変わりました。設定を開き直してください");
    android.content.SharedPreferences state = E2AutoSync.state(context);
    String observed = enabled ? bucket : state.getString("destination", "");
    android.content.SharedPreferences.Editor setting =
        state.edit().putBoolean("enabled", enabled).putString("destination", enabled ? bucket : "");
    E2AutoSync.clearBatch(setting);
    E2AutoSync.clearSettled(setting);
    if (!observed.isEmpty()) setting.putString("observationBucket", observed);
    if (enabled) {
      setting.putInt("fullMediaConsent", MEDIA_CONSENT_VERSION);
      JobState.write(setting, "", JobState.Kind.WAITING, System.currentTimeMillis())
          .putBoolean("CountsKnown", false);
      if (!setting.commit()) throw new IllegalStateException("自動同期の設定を保存できません");
    } else {
      if (!E2Cleanup.revokeForAutoBackupPause(context, setting))
        throw new IllegalStateException("自動同期の設定を保存できません");
      try {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancel(PERIODIC);
        scheduler.cancel(FIRST);
        scheduler.cancel(CONTINUATION);
        scheduler.cancel(MEDIA_CHANGED);
      } catch (RuntimeException unavailable) {
        Log.w("LoupeE2Sync", "Backup schedule cancellation unavailable", unavailable);
      }
      return;
    }
    JobScheduler scheduler = context.getSystemService(JobScheduler.class);
    ComponentName service = new ComponentName(context, E2SyncJob.class);
    boolean recovery = ensureRecoverySchedule(context);
    boolean observer = scheduleMediaJob(context);
    int first = scheduler.schedule(job(FIRST, service).build());
    if (!recovery || !observer || first != JobScheduler.RESULT_SUCCESS) {
      setEnabledLocked(context, false, null);
      throw new IllegalStateException("自動同期を予約できません");
    }
  }

  private static JobInfo.Builder job(int id, ComponentName service) {
    return new JobInfo.Builder(id, service)
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
        .setRequiresStorageNotLow(true);
  }

  // Content jobs cannot be persisted. Android owns these observers, including when Loupe exits.
  private static boolean scheduleMediaJob(Context context) {
    ComponentName service = new ComponentName(context, E2SyncJob.class);
    JobInfo.Builder builder =
        job(MEDIA_CHANGED, service)
            .setTriggerContentUpdateDelay(500)
            .setTriggerContentMaxDelay(2_000);
    for (android.net.Uri uri :
        new android.net.Uri[] {
          MediaStore.Files.getContentUri("external"),
          MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
          MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        })
      builder.addTriggerContentUri(
          new JobInfo.TriggerContentUri(
              uri, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS));
    return context.getSystemService(JobScheduler.class).schedule(builder.build())
        == JobScheduler.RESULT_SUCCESS;
  }

  static boolean ensureRecoverySchedule(Context context) {
    synchronized (E2Settings.DESTINATION_LOCK) {
      if (!enabled(context)) return true;
      String bucket = E2AutoSync.state(context).getString("destination", "");
      long interval = E2Cleanup.enabled(context, bucket) ? RETRY_DELAY_MS : RECOVERY_INTERVAL_MS;
      JobScheduler scheduler = context.getSystemService(JobScheduler.class);
      JobInfo pending = scheduler.getPendingJob(PERIODIC);
      if (pending != null && pending.isPeriodic() && pending.getIntervalMillis() == interval)
        return true;
      ComponentName service = new ComponentName(context, E2SyncJob.class);
      return scheduler.schedule(
              job(PERIODIC, service).setPersisted(true).setPeriodic(interval).build())
          == JobScheduler.RESULT_SUCCESS;
    }
  }

  private static void ensureMediaJob(Context context) {
    synchronized (E2Settings.DESTINATION_LOCK) {
      if (enabled(context)
          && fullMediaConsent(context)
          && E2Settings.hasMediaAccess(context)
          && context.getSystemService(JobScheduler.class).getPendingJob(MEDIA_CHANGED) == null
          && !scheduleMediaJob(context))
        Log.w("LoupeE2Sync", "Media observation scheduling declined");
    }
  }

  static void recoverOnOpen(Context context) {
    if (!OPEN_RECOVERY_QUEUED.compareAndSet(false, true)) return;
    CompletableFuture.runAsync(
        () -> {
          try {
            recoverSchedules(context, false);
          } finally {
            OPEN_RECOVERY_QUEUED.set(false);
          }
        });
  }

  private static void recoverSchedules(Context context, boolean systemRestart) {
    synchronized (E2Settings.DESTINATION_LOCK) {
      if (!enabled(context)) return;
      try {
        if (!ensureRecoverySchedule(context)) Log.w("LoupeE2Sync", "Recovery scheduling declined");
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        boolean missingObserver = scheduler.getPendingJob(MEDIA_CHANGED) == null;
        // Schedule the catch-up first so a refused request leaves the observer eligible
        // for on-open repair. Existing active or pending work can perform the catch-up.
        if ((missingObserver || systemRestart)
            && !RUNNING.get()
            && scheduler.getPendingJob(FIRST) == null
            && scheduler.getPendingJob(CONTINUATION) == null
            && !scheduleContinuation(
                context,
                E2AutoSync.state(context).getString("destination", ""),
                0,
                systemRestart ? CONTINUATION_DELAY_MS : 0)) return;
        if (missingObserver) ensureMediaJob(context);
      } catch (IllegalStateException unavailable) {
        Log.w("LoupeE2Sync", "Recovery scheduler unavailable");
      }
    }
  }

  private static boolean scheduleContinuation(
      Context context, String bucket, int finishedJobId, long delay) {
    synchronized (E2Settings.DESTINATION_LOCK) {
      if (!enabled(context)
          || !fullMediaConsent(context)
          || !E2Settings.hasMediaAccess(context)
          || bucket == null
          || bucket.isEmpty()
          || !bucket.equals(E2AutoSync.state(context).getString("destination", ""))
          || !bucket.equals(E2Settings.configuredBucket(context))) return false;
      int jobId = finishedJobId == CONTINUATION ? FIRST : CONTINUATION;
      JobScheduler scheduler = context.getSystemService(JobScheduler.class);
      ComponentName service = new ComponentName(context, E2SyncJob.class);
      try {
        if (scheduler.schedule(job(jobId, service).setMinimumLatency(delay).build())
            == JobScheduler.RESULT_SUCCESS) return true;
        Log.w("LoupeE2Sync", "Continuation scheduling declined");
      } catch (IllegalStateException unavailable) {
        Log.w("LoupeE2Sync", "Continuation scheduler unavailable");
      }
      return false;
    }
  }

  public static final class AfterUpdate extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        // Retire only the old Amazon periodic and manual jobs.
        scheduler.cancel(71);
        scheduler.cancel(72);
      }
      if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())
          || Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
        recoverSchedules(context, true);
      }
    }
  }

  @Override
  public boolean onStartJob(JobParameters params) {
    if (!enabled(this)) return false;
    boolean media = params.getJobId() == MEDIA_CHANGED;
    if (!media) ensureMediaJob(this);
    android.net.Network network = params.getNetwork();
    if (!E2Archive.unmetered(this, network) || !RUNNING.compareAndSet(false, true)) {
      if (!media) return false;
      // Preserve the triggered content on a retry instead of consuming it when a batch is busy.
      new Handler(Looper.getMainLooper()).post(() -> jobFinished(params, enabled(this)));
      return true;
    }
    current = params;
    AtomicBoolean cancel = new AtomicBoolean();
    stopped = cancel;
    worker.execute(
        () -> {
          runningThread = Thread.currentThread();
          E2AutoSync.FollowUp followUp = E2AutoSync.FollowUp.NONE;
          try {
            followUp =
                E2AutoSync.run(
                    getApplicationContext(),
                    network,
                    () -> cancel.get() || !enabled(this) || !E2Archive.unmetered(this, network));
          } finally {
            runningThread = null;
            E2AutoSync.FollowUp result = followUp;
            String destination = result.destination();
            new Handler(Looper.getMainLooper())
                .post(
                    () -> {
                      RUNNING.set(false);
                      if (current != params) return;
                      current = null;
                      JobState.Kind kind;
                      synchronized (E2Settings.DESTINATION_LOCK) {
                        kind = E2AutoSync.snapshot(this, false).kind;
                        boolean rearmed = media && enabled(this) && scheduleMediaJob(this);
                        if (!rearmed) jobFinished(params, media && enabled(this));
                        if (!cancel.get()
                            && result.retryUnnecessary()
                            && kind == JobState.Kind.COMPLETE
                            && enabled(this)) {
                          JobScheduler scheduler = getSystemService(JobScheduler.class);
                          if (rearmed || (!media && scheduler.getPendingJob(MEDIA_CHANGED) != null))
                            cancelDeferredRetries(scheduler, params.getJobId());
                        }
                      }
                      if (!ensureRecoverySchedule(this))
                        Log.w("LoupeE2Sync", "Recovery scheduling declined");
                      if (cancel.get()) return;
                      if (destination != null) {
                        scheduleContinuation(
                            this, destination, params.getJobId(), CONTINUATION_DELAY_MS);
                      } else if (kind == JobState.Kind.FAILED
                          || kind == JobState.Kind.PARTIAL
                          || kind == JobState.Kind.PAUSED) {
                        scheduleContinuation(
                            this,
                            E2AutoSync.state(this).getString("destination", ""),
                            params.getJobId(),
                            RETRY_DELAY_MS);
                      }
                    });
          }
        });
    return true;
  }

  private static void cancelDeferredRetries(JobScheduler scheduler, int finishedJobId) {
    for (int id : new int[] {FIRST, CONTINUATION}) {
      if (id == finishedJobId) continue;
      JobInfo pending = scheduler.getPendingJob(id);
      // The original delay distinguishes both new and already scheduled retries from catch-up jobs.
      if (pending != null && pending.getMinLatencyMillis() == RETRY_DELAY_MS) scheduler.cancel(id);
    }
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    if (current != null && current.getJobId() == params.getJobId()) {
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

  @Override
  public void onDestroy() {
    interrupt();
    worker.shutdown();
    super.onDestroy();
  }
}
