package jp.nano.rawgallery;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import jp.nano.rawgallery.camera.CameraImportGate;
import jp.nano.rawgallery.camera.CameraOperationDelivery;
import jp.nano.rawgallery.camera.CameraOperationTask;

/** A confirmed move can keep running when the settings screen is closed. */
public final class PhotoMoveService extends Service {
  private static final String CHANNEL = "photo_storage_move";
  private static final int NOTIFICATION = 2040;

  record State(boolean running, PhotoMoveStore.Batch batch, String message) {}

  private static volatile State state = new State(false, null, null);
  private static final String UNAVAILABLE = "移動を開始できません。もう一度お試しください。";
  private static final String CANCELLED = "移動を中断しました。";

  private record MoveRequest(PhotoMove.Plan plan, boolean abandon, AtomicBoolean stopped) {}

  private static volatile AtomicBoolean stopRequested = new AtomicBoolean();
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private MoveRequest active;
  private int lastStartId;
  private boolean destroyed;

  static State state() {
    return state;
  }

  static boolean launch(Context context, PhotoMove.Plan plan) {
    return launch(context, plan, false);
  }

  static boolean abandon(Context context) {
    return launch(context, null, true);
  }

  private static synchronized boolean launch(
      Context context, PhotoMove.Plan plan, boolean abandon) {
    if (state.running()) return false;
    var admission = CameraImportGate.tryAdmit();
    if (admission == null) {
      state = new State(false, null, UNAVAILABLE);
      return false;
    }
    var request = new MoveRequest(plan, abandon, new AtomicBoolean());
    stopRequested = request.stopped();
    state = new State(true, null, "移動の準備をしています。");
    try {
      if (CameraOperationDelivery.submit(
          context, new Intent(context, PhotoMoveService.class), admission, request)) return true;
    } catch (RuntimeException unavailable) {
      android.util.Log.w("PhotoMoveService", "Could not start the move service", unavailable);
    }
    state = new State(false, null, UNAVAILABLE);
    return false;
  }

  static void stopMove() {
    stopRequested.set(true);
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    lastStartId = startId;
    var delivery =
        CameraOperationDelivery.claim(intent, new ComponentName(this, PhotoMoveService.class));
    if (delivery == null) {
      if (active == null) stopIdleStart(startId);
      return START_NOT_STICKY;
    }
    var admission = delivery.admission();
    if (!(delivery.payload() instanceof MoveRequest request) || active != null) {
      admission.close();
      if (active == null) stopIdleStart(startId);
      return START_NOT_STICKY;
    }
    active = request;
    boolean handedOff = false;
    try {
      promote();
      var task =
          new CameraOperationTask(
              () -> {
                State result;
                try (admission) {
                  result =
                      request.stopped().get() ? new State(false, null, CANCELLED) : move(request);
                }
                finish(result);
              },
              () -> {
                admission.close();
                finish(new State(false, null, request.stopped().get() ? CANCELLED : UNAVAILABLE));
              });
      handedOff = true;
      try {
        worker.execute(task);
      } catch (RejectedExecutionException unavailable) {
        task.discardBeforeStart();
      }
      return START_NOT_STICKY;
    } finally {
      if (!handedOff) {
        admission.close();
        finish(new State(false, null, UNAVAILABLE));
      }
    }
  }

  private void promote() {
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel(CHANNEL, "写真の移動", NotificationManager.IMPORTANCE_LOW));
    startForeground(NOTIFICATION, notification(null));
  }

  private void stopIdleStart(int startId) {
    // Meet the foreground-start contract even when a stale Intent has no claimable request.
    promote();
    stopSelf(startId);
  }

  private State move(MoveRequest request) {
    var gate = CameraImportGate.lock;
    PhotoMoveStore.Batch batch = null;
    String message = "取り込みが終わってからもう一度お試しください。";
    if (gate.tryLock()) {
      try (PhotoMove mover = new PhotoMove(this)) {
        try {
          if (request.abandon()) {
            batch = mover.abandon();
            message =
                "移動を終了しました。"
                    + batch.done()
                    + "枚の移動が完了し、"
                    + (batch.total() - batch.done())
                    + "枚は未完了です。"
                    + skipped(batch);
          } else {
            if (request.plan() != null) mover.begin(request.plan());
            batch =
                mover.run(
                    request.stopped()::get, batchProgress -> progress(request, batchProgress));
            message =
                "写真を"
                    + batch.done()
                    + "枚移動しました。 未処理"
                    + (batch.total() - batch.done())
                    + "枚。"
                    + skipped(batch);
          }
        } catch (InterruptedIOException cancelled) {
          batch = mover.batch();
          message = CANCELLED;
        } catch (IOException unavailable) {
          batch = mover.batch();
          message =
              unavailable instanceof PhotoMove.Failure
                      || unavailable instanceof PhotoStorage.Unavailable
                  ? unavailable.getMessage()
                  : "移動を続けられません。保存先と空き容量を確認してください。";
        } catch (RuntimeException error) {
          batch = mover.batch();
          message = "移動を続けられません。保存先と空き容量を確認してください。";
        }
      } catch (RuntimeException unavailable) {
        message = "移動の記録を読み取れません。もう一度お試しください。";
      } finally {
        gate.unlock();
      }
    }
    return new State(false, batch, message);
  }

  private void finish(State result) {
    getMainExecutor()
        .execute(
            () -> {
              active = null;
              state = result;
              GalleryIndexJob.schedule(this);
              if (!destroyed) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(lastStartId);
              }
            });
  }

  static String skipped(PhotoMoveStore.Batch batch) {
    return batch.skipped() == 0 ? "" : " 移動対象外の項目があります。";
  }

  private void progress(MoveRequest request, PhotoMoveStore.Batch batch) {
    state = new State(true, batch, request.stopped().get() ? "移動を中断しています。" : "写真を移動しています。");
    getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(batch));
  }

  private Notification notification(PhotoMoveStore.Batch batch) {
    PendingIntent settings =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, PhotoStorageActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification.Builder builder =
        new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("写真を移動しています")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(settings);
    if (batch == null) return builder.setProgress(0, 0, true).build();
    return builder.setProgress(batch.total(), batch.done(), false).build();
  }

  @Override
  public void onTimeout(int startId, int foregroundServiceType) {
    if (active != null) active.stopped().set(true);
    stopSelf(startId);
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    if (active != null) active.stopped().set(true);
    for (Runnable task : worker.shutdownNow()) ((CameraOperationTask) task).discardBeforeStart();
    super.onDestroy();
  }
}
