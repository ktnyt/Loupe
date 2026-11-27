package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

final class MediaObserverLifecycleChecks {
  static String run(Instrumentation test) throws Exception {
    MainActivity activity =
        (MainActivity)
            test.startActivitySync(
                new Intent(test.getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    Handler handler = (Handler) RankingResultChecks.get(activity, "main");
    ContentObserver observer = (ContentObserver) RankingResultChecks.get(activity, "mediaObserver");
    Runnable refresh = (Runnable) RankingResultChecks.get(activity, "mediaChanged");
    ThreadPoolExecutor scans = (ThreadPoolExecutor) RankingResultChecks.get(activity, "scans");
    try {
      GalleryScreenChecks.settle(test, activity);
      TagDetailChecks.ui(
          test,
          () -> {
            handler.removeCallbacks(refresh);
            observer.onChange(false);
            TagDetailChecks.require(
                handler.hasCallbacks(refresh), "live media notification schedules refresh");
            handler.removeCallbacks(refresh);
            long previousTasks = scans.getTaskCount();
            refresh.run();
            TagDetailChecks.require(
                scans.getTaskCount() > previousTasks, "live media refresh scans library");
          });
      GalleryScreenChecks.settle(test, activity);
      TagDetailChecks.ui(
          test,
          () -> {
            handler.removeCallbacks(refresh);
            handler.postDelayed(refresh, 60_000);
            TagDetailChecks.require(
                handler.hasCallbacks(refresh), "refresh queued before destruction");
            activity.finish();
          });
      awaitDestruction(test, activity);

      TagDetailChecks.ui(
          test,
          () -> {
            TagDetailChecks.require(scans.isShutdown(), "activity shuts down scans");
            TagDetailChecks.require(
                !handler.hasCallbacks(refresh), "destruction removes queued refresh");
            observer.onChange(false);
            boolean requeued = handler.hasCallbacks(refresh);
            handler.removeCallbacks(refresh);
            boolean rejected = false;
            try {
              refresh.run();
            } catch (RejectedExecutionException expectedOnUnfixedBuilds) {
              rejected = true;
            }
            if (requeued || rejected) {
              Log.e(
                  "LoupeMediaFixture",
                  "destroyed media refresh: requeued=" + requeued + ", rejected=" + rejected);
              throw new AssertionError("destroyed activity accepts late media refresh");
            }
          });
      return "PASS media-observer-lifecycle: live refresh scans, destroyed activity ignores late callbacks\n";
    } finally {
      TagDetailChecks.ui(
          test,
          () -> {
            handler.removeCallbacks(refresh);
            if (!activity.isFinishing()) activity.finish();
          });
    }
  }

  private static void awaitDestruction(Instrumentation test, MainActivity activity) {
    long deadline = SystemClock.uptimeMillis() + 5000;
    boolean[] destroyed = {false};
    do {
      TagDetailChecks.ui(test, () -> destroyed[0] = activity.isDestroyed());
      if (destroyed[0]) return;
      SystemClock.sleep(20);
    } while (SystemClock.uptimeMillis() < deadline);
    throw new AssertionError("real Activity destruction did not finish");
  }
}
