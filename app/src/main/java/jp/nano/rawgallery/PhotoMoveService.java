package jp.nano.rawgallery;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import jp.nano.rawgallery.camera.CameraImportGate;

/** A confirmed move can keep running when the settings screen is closed. */
public final class PhotoMoveService extends Service {
  private static final String CHANNEL = "photo_storage_move";
  private static final int NOTIFICATION = 2040;
  record State(boolean running, PhotoMoveStore.Batch batch, String message) {}
  private static volatile State state = new State(false, null, null);
  private static PhotoMove.Plan request;
  private static final AtomicBoolean stopped = new AtomicBoolean();
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private boolean started;

  static State state() { return state; }

  static synchronized boolean launch(Context context, PhotoMove.Plan plan) {
    if (state.running()) return false;
    request = plan;
    stopped.set(false);
    state = new State(true, null, "移動の準備をしています。");
    try {
      context.startForegroundService(new Intent(context, PhotoMoveService.class));
      return true;
    } catch (RuntimeException unavailable) {
      request = null;
      state = new State(false, null, "移動を開始できません。もう一度お試しください。");
      return false;
    }
  }

  static void stopMove() {
    stopped.set(true);
  }

  @Override public IBinder onBind(Intent intent) { return null; }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    if (started) return START_NOT_STICKY;
    started = true;
    getSystemService(NotificationManager.class).createNotificationChannel(
        new NotificationChannel(CHANNEL, "写真の移動", NotificationManager.IMPORTANCE_LOW));
    startForeground(NOTIFICATION, notification(null));
    PhotoMove.Plan plan;
    synchronized (PhotoMoveService.class) {
      plan = request;
      request = null;
    }
    worker.execute(() -> move(plan, startId));
    return START_NOT_STICKY;
  }

  private void move(PhotoMove.Plan plan, int startId) {
    var gate = CameraImportGate.lock;
    PhotoMoveStore.Batch batch = null;
    String message = "取り込みが終わってからもう一度お試しください。";
    if (gate.tryLock()) {
      try (PhotoMove mover = new PhotoMove(this)) {
        try {
          if (plan != null) mover.begin(plan);
          batch = mover.run(stopped::get, this::progress);
          message = "写真を" + batch.done() + "枚移動しました。" + skipped(batch);
        } catch (InterruptedIOException cancelled) {
          batch = mover.batch();
          message = "移動を中断しました。";
        } catch (IOException unavailable) {
          batch = mover.batch();
          message = unavailable instanceof PhotoMove.Failure || unavailable instanceof PhotoStorage.Unavailable
              ? unavailable.getMessage() : "移動を続けられません。保存先と空き容量を確認してください。";
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
    PhotoMoveStore.Batch result = batch;
    String text = message;
    getMainExecutor().execute(() -> {
      state = new State(false, result, text);
      GalleryIndexJob.schedule(this);
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf(startId);
    });
  }

  static String skipped(PhotoMoveStore.Batch batch) {
    return batch.skipped() == 0 ? "" : " 移動できない写真が" + batch.skipped() + "件あります。";
  }

  private void progress(PhotoMoveStore.Batch batch) {
    state = new State(true, batch, stopped.get() ? "移動を中断しています。" : "写真を移動しています。");
    getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(batch));
  }

  private Notification notification(PhotoMoveStore.Batch batch) {
    PendingIntent settings = PendingIntent.getActivity(this, 0, new Intent(this, PhotoStorageActivity.class),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification.Builder builder = new Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_gallery).setContentTitle("写真を移動しています")
        .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(settings);
    if (batch == null) return builder.setProgress(0, 0, true).build();
    return builder.setProgress(batch.total(), batch.done(), false).build();
  }

  @Override public void onTimeout(int startId, int foregroundServiceType) {
    stopMove();
    stopSelf(startId);
  }

  @Override public void onDestroy() {
    stopped.set(true);
    worker.shutdownNow();
    super.onDestroy();
  }
}
