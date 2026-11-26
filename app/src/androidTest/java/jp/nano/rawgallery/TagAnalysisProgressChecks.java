package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import java.io.File;
import java.util.Collection;

/** Holds only the disposable fixture's worker; separates visibility from scroll reachability. */
final class TagAnalysisProgressChecks {
  private static StatusView status(TagActivity a) {
    return (StatusView) TagDetailChecks.get(a, "analysisStatus");
  }

  private static void awaitVisible(Instrumentation t, TagActivity a) throws Exception {
    long deadline = SystemClock.uptimeMillis() + 2500;
    boolean[] visible = {false};
    do {
      TagDetailChecks.ui(
          t,
          () -> {
            StatusView v = status(a);
            visible[0] = v != null && v.isShown() && v.getAlpha() > 0;
          });
      if (visible[0]) return;
      SystemClock.sleep(50);
    } while (SystemClock.uptimeMillis() < deadline);
    throw new AssertionError("analysis progress is invisible after the loading delay");
  }

  private static void fullyVisible(View v) {
    Rect rect = new Rect();
    TagDetailChecks.require(
        v.isShown()
            && v.getAlpha() > 0
            && v.getGlobalVisibleRect(rect)
            && rect.width() == v.getWidth()
            && rect.height() == v.getHeight(),
        "progress and stop are reachable without clipping");
  }

  private static void reveal(Instrumentation t, TagActivity a) throws Exception {
    TagDetailChecks.ui(
        t,
        () -> {
          View stop = (View) TagDetailChecks.get(a, "resultButton");
          stop.requestRectangleOnScreen(new Rect(0, 0, stop.getWidth(), stop.getHeight()), true);
        });
    ConsistencyLayoutChecks.frame(t, (View) TagDetailChecks.get(a, "root"));
    TagDetailChecks.ui(
        t,
        () -> {
          fullyVisible(status(a));
          fullyVisible((View) TagDetailChecks.get(a, "resultButton"));
        });
  }

  private static void observe(
      TagActivity activity,
      ConfidenceAsyncChecks.AnalysisGateProbe gate,
      String stage,
      int expected) {
    StatusView view = status(activity);
    CharSequence description = view == null ? null : view.getContentDescription();
    int analyzed = (int) TagDetailChecks.get(activity, "analyzed");
    int total = ((Collection<?>) TagDetailChecks.get(activity, "photos")).size();
    int processed = ((Collection<?>) TagDetailChecks.get(activity, "processed")).size();
    String error = (String) TagDetailChecks.get(activity, "automaticError");
    boolean matches =
        description != null && description.toString().contains("解析 " + expected + " / 12枚");
    boolean matchesModel =
        description != null
            && description.toString().contains("解析 " + analyzed + " / " + total + "枚");
    Log.i(
        ConfidenceAsyncChecks.AnalysisGateProbe.LOG_TAG,
        "event=progress stage="
            + stage
            + " gate="
            + gate.state()
            + " elapsedMs="
            + gate.elapsedMillis()
            + " analyzed="
            + analyzed
            + " processed="
            + processed
            + " total="
            + total
            + " generation="
            + TagDetailChecks.get(activity, "analysisGeneration")
            + " running="
            + TagDetailChecks.get(activity, "autoRunning")
            + " active="
            + TagDetailChecks.get(activity, "analysisActive")
            + " finished="
            + TagDetailChecks.get(activity, "analysisFinished")
            + " pending="
            + TagDetailChecks.get(activity, "autoPending")
            + " errorPresent="
            + (error != null && !error.isEmpty())
            + " statusShown="
            + (view != null && view.isShown() && view.getAlpha() > 0)
            + " expected="
            + expected
            + " statusMatchesExpected="
            + matches
            + " statusMatchesModel="
            + matchesModel);
  }

  static void verify(
      Instrumentation t,
      TagActivity activity,
      ConfidenceAsyncChecks.AnalysisGateProbe gate,
      int rebuildDelayMillis)
      throws Exception {
    awaitVisible(t, activity);
    reveal(t, activity);
    File dir = new File(activity.getCacheDir(), "consistency-checks");
    dir.mkdirs();
    String prefix =
        "tag-analysis-progress-"
            + TagDetailChecks.width
            + "-"
            + activity.getResources().getConfiguration().fontScale;
    TagDetailChecks.ui(
        t,
        () ->
            TagDetailChecks.require(
                status(activity).getContentDescription().toString().contains("解析 0 / 12枚"),
                "initial count is exposed"));
    ConsistencyLayoutChecks.captureHardware(
        t, activity, (View) TagDetailChecks.get(activity, "root"), dir, prefix + "-running");
    TagDetailChecks.ui(
        t,
        () -> {
          observe(activity, gate, "beforeInjection", 0);
          TagDetailChecks.set(activity, "analyzed", 6);
          TagDetailChecks.call(activity, "updateProgress");
        });
    reveal(t, activity);
    TagDetailChecks.ui(
        t,
        () ->
            TagDetailChecks.require(
                status(activity).getContentDescription().toString().contains("解析 6 / 12枚"),
                "running count updates"));
    ConsistencyLayoutChecks.captureHardware(
        t, activity, (View) TagDetailChecks.get(activity, "root"), dir, prefix + "-updated");
    if (rebuildDelayMillis > 0) {
      Log.i(
          ConfidenceAsyncChecks.AnalysisGateProbe.LOG_TAG,
          "event=beforeRebuildDelay delayMs=" + rebuildDelayMillis);
      SystemClock.sleep(rebuildDelayMillis);
    }
    TagDetailChecks.ui(
        t,
        () -> {
          observe(activity, gate, "beforeRebuild", 6);
          TagDetailChecks.call(activity, "renderTag");
          observe(activity, gate, "afterRebuild", 6);
        });
    awaitVisible(t, activity);
    reveal(t, activity);
    TagDetailChecks.ui(
        t,
        () -> {
          observe(activity, gate, "visibleAfterRebuild", 6);
          TagDetailChecks.require(
              status(activity).getContentDescription().toString().contains("解析 6 / 12枚"),
              "progress survives panel rebuild");
        });
    // Layout-only large-number fixture; no photos or scores are processed for this number.
    TagDetailChecks.ui(
        t,
        () -> {
          status(activity).observe(StatusView.Symbol.RUNNING, "20354/32541", "解析 20354 / 32541枚");
          ((StatusView) TagDetailChecks.get(activity, "assignmentStatus"))
              .show(StatusView.Symbol.AUTO_TAG, "365", "推定365枚", null, "");
        });
    ConsistencyLayoutChecks.frame(t, (View) TagDetailChecks.get(activity, "root"));
    reveal(t, activity);
    TagDetailChecks.ui(
        t, () -> TagDetailChecks.inspect((View) TagDetailChecks.get(activity, "root")));
    ConsistencyLayoutChecks.captureHardware(
        t, activity, (View) TagDetailChecks.get(activity, "root"), dir, prefix + "-large");
    TagDetailChecks.ui(
        t,
        () -> {
          TagDetailChecks.set(activity, "analyzed", 0);
          TagDetailChecks.call(activity, "updateProgress");
        });
  }
}
