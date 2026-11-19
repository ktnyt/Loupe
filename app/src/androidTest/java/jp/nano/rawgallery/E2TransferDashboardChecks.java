package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises the normal e2 screen against synthetic state in an empty test profile. */
final class E2TransferDashboardChecks {
  static String run(Instrumentation test) throws Exception {
    Context context = test.getTargetContext();
    SharedPreferences connection = context.getSharedPreferences("e2_connection", 0);
    SharedPreferences upload = E2AutoSync.state(context);
    if (!connection.getAll().isEmpty() || !upload.getAll().isEmpty())
      throw new AssertionError("Use a fresh Android test profile without a cloud configuration");
    String bucket = "loupe-test";
    connection.edit().putString("bucket", bucket).commit();
    upload.edit().putString("observationBucket", bucket).commit();
    record(upload, JobState.Kind.WAITING, false);
    var field = E2SyncJob.class.getDeclaredField("RUNNING");
    field.setAccessible(true);
    AtomicBoolean worker = (AtomicBoolean) field.get(null);
    E2SyncActivity activity = null;
    try {
      activity = (E2SyncActivity) test.startActivitySync(new Intent(context, E2SyncActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      requireText(test, activity, "クラウド同期の状況", true);
      requireText(test, activity, "アップロード", true);
      requireText(test, activity, "ダウンロード", true);
      requireText(test, activity, "自動バックアップ · 一時停止中", false);
      var consent = E2SyncJob.class.getDeclaredField("MEDIA_CONSENT_VERSION");
      consent.setAccessible(true);
      upload.edit().putBoolean("enabled", true).putString("destination", bucket)
          .putInt("fullMediaConsent", consent.getInt(null)).commit();
      requireText(test, activity, "自動バックアップ · 待機中", false);
      requireText(test, activity, "復元一覧 · 未実行", false);
      requireText(test, activity, "原本 · 未実行", false);

      worker.set(true);
      record(upload, JobState.Kind.RUNNING, true);
      try (E2TransferStatus catalog = E2TransferStatus.begin(
          E2TransferStatus.Kind.CATALOG_DOWNLOAD, bucket, -1);
          E2TransferStatus original = E2TransferStatus.begin(
              E2TransferStatus.Kind.ORIGINAL_DOWNLOAD, bucket, 2_000_000)) {
        original.bytesRead(1_000_000);
        requireText(test, activity, "自動バックアップ · アップロード中", false);
        requireText(test, activity, "確認済み 1,124 / 7,021件\n残り 5,895件 · 失敗 2件", false);
        requireText(test, activity, "復元一覧 · 取得中", false);
        requireText(test, activity, "原本 · 取得中 · ", false);
        capture(context, test);

        original.bytesRead(2_000_000);
        requireText(test, activity, "原本 · 照合中 · ", false);
        original.complete(-1);
        catalog.complete(50_001);
      }
      upload.edit().putBoolean("publishingCatalog", true).commit();
      requireText(test, activity, "自動バックアップ · 復元一覧に反映中", false);
      requireText(test, activity, "復元一覧 · 取得済み · 50,001件", false);
      requireText(test, activity, "原本 · 取得済み", false);
      try (E2TransferStatus original = E2TransferStatus.begin(
          E2TransferStatus.Kind.ORIGINAL_DOWNLOAD, bucket, 2_000_000)) {
        original.cancel();
      }
      requireText(test, activity, "原本 · 中止", false);
      E2TransferStatus.begin(E2TransferStatus.Kind.CATALOG_DOWNLOAD, bucket, -1).close();
      requireText(test, activity, "復元一覧 · 失敗", false);
      upload.edit().putBoolean("publishingCatalog", false).putString("lastCountBucket", bucket)
          .putInt("lastReady", 1124).putInt("lastTotal", 7021).putInt("lastFailed", 2).commit();
      record(upload, JobState.Kind.RUNNING, false);
      requireText(test, activity, "自動バックアップ · 対象を確認中", false);
      requireText(test, activity, "前回の計測 · 確認済み 1,124 / 7,021件", false);
      worker.set(false);
      record(upload, JobState.Kind.PAUSED, false);
      requireText(test, activity, "自動バックアップ · 中断 · 再開待ち", false);
      record(upload, JobState.Kind.COMPLETE, true);
      requireText(test, activity, "自動バックアップ · 完了", false);
      requireText(test, activity, "確認済み 7,021 / 7,021件", false);
      try (E2TransferStatus manual = E2TransferStatus.begin(
          E2TransferStatus.Kind.MANUAL_UPLOAD, bucket, -1)) {
        manual.publishing(true);
        requireText(test, activity, "手動保存 · 原本保存済み · 復元一覧に反映中", false);
        manual.fail();
      }
      requireText(test, activity, "手動保存 · 原本保存済み · 復元一覧の反映に失敗", false);

      connection.edit().putString("bucket", "another-test-bucket").commit();
      requireText(test, activity, "IDrive e2 · another-test-bucket", false);
      requireText(test, activity, "復元一覧 · 未実行", false);
      requireText(test, activity, "原本 · 未実行", false);
      if (hasText(test, activity, "確認済み", false))
        throw new AssertionError("A different bucket must not display the earlier upload counts");
      test.runOnMainSync(activity::finish);
      activity = openFromProcessingDashboard(test, context);

      return "PASS e2 transfer dashboard: independent live directions, counters, bytes, checking, "
          + "publication, completion, cancellation, failure, previous counts, bucket isolation "
          + "and navigation from processing dashboard.\n";
    } finally {
      worker.set(false);
      if (activity != null) test.runOnMainSync(activity::finish);
      connection.edit().clear().commit();
      upload.edit().clear().commit();
    }
  }

  private static E2SyncActivity openFromProcessingDashboard(Instrumentation test, Context context) {
    var dashboard = test.startActivitySync(new Intent(context, DashboardActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    var monitor = test.addMonitor(E2SyncActivity.class.getName(), null, false);
    try {
      test.runOnMainSync(() -> {
        if (!openDashboardLink(dashboard.getWindow().getDecorView()))
          throw new AssertionError("Missing cloud dashboard link");
      });
      var destination = test.waitForMonitorWithTimeout(monitor, 5000);
      if (!(destination instanceof E2SyncActivity e2))
        throw new AssertionError("Link must open the dashboard");
      requireText(test, e2, E2Panel.DASHBOARD_TITLE, true);
      return e2;
    } finally {
      test.removeMonitor(monitor);
      test.runOnMainSync(dashboard::finish);
    }
  }

  private static boolean openDashboardLink(View view) {
    if (view instanceof android.widget.Button button
        && E2Panel.DASHBOARD_TITLE.contentEquals(button.getText())) return button.performClick();
    if (view instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++)
        if (openDashboardLink(group.getChildAt(i))) return true;
    }
    return false;
  }

  private static void record(SharedPreferences upload, JobState.Kind kind, boolean known) {
    JobState.write(upload.edit(), "", kind, System.currentTimeMillis())
        .putBoolean("CountsKnown", known).putInt("ready", kind == JobState.Kind.COMPLETE ? 7021 : 1124)
        .putInt("total", 7021).putInt("failed", kind == JobState.Kind.COMPLETE ? 0 : 2).commit();
  }

  private static void capture(Context context, Instrumentation test) throws Exception {
    Bitmap image = test.getUiAutomation().takeScreenshot();
    if (image == null) throw new AssertionError("Android screen capture failed");
    try (FileOutputStream out = new FileOutputStream(
        new File(context.getCacheDir(), "e2-transfer-dashboard.png"))) {
      if (!image.compress(Bitmap.CompressFormat.PNG, 100, out))
        throw new AssertionError("PNG encoding failed");
    } finally {
      image.recycle();
    }
  }

  private static void requireText(Instrumentation test, E2SyncActivity activity, String text,
      boolean heading) {
    long until = SystemClock.elapsedRealtime() + 3500;
    do {
      if (hasText(test, activity, text, heading)) return;
      SystemClock.sleep(100);
    } while (SystemClock.elapsedRealtime() < until);
    throw new AssertionError("Missing visible text: " + text);
  }

  private static boolean hasText(Instrumentation test, E2SyncActivity activity, String text,
      boolean heading) {
    boolean[] result = new boolean[1];
    test.runOnMainSync(() -> result[0] = hasText(activity.getWindow().getDecorView(), text, heading));
    return result[0];
  }

  private static boolean hasText(View view, String text, boolean heading) {
    if (view instanceof TextView label && view.isShown()
        && label.getText().toString().contains(text) && (!heading || view.isAccessibilityHeading()))
      return true;
    if (view instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++)
        if (hasText(group.getChildAt(i), text, heading)) return true;
    }
    return false;
  }
}
