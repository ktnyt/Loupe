package jp.nano.rawgallery;

import static jp.nano.rawgallery.AlbumTagEntryChecks.*;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real view timing and accessibility actions; generated state only, no network or originals. */
final class VisualStatusChecks {
  private static long checkShortLoad(Instrumentation t, StatusView status) throws Exception {
    Handler uiHandler = new Handler(Looper.getMainLooper());
    for (int attempt = 0; attempt < 3; attempt++) {
      CountDownLatch observed = new CountDownLatch(1);
      boolean[] hidden = {false, false};
      long[] elapsed = {0};
      long[] priorVisibleAt = {0};
      main(
          t,
          () -> {
            priorVisibleAt[0] = (Long) field(status, "visibleAt");
            status.begin("短い読み込み");
            long started = (Long) field(status, "started");
            TagDetailChecks.require(
                uiHandler.postAtTime(
                    () -> {
                      elapsed[0] = SystemClock.uptimeMillis() - started;
                      hidden[0] = status.getVisibility() != View.VISIBLE;
                      status.finish();
                    },
                    started + 180),
                "schedule short load completion");
            TagDetailChecks.require(
                uiHandler.postAtTime(
                    () -> {
                      hidden[1] = status.getVisibility() != View.VISIBLE;
                      observed.countDown();
                    },
                    started + 500),
                "schedule check after original show delay");
            return true;
          });
      TagDetailChecks.require(observed.await(10, TimeUnit.SECONDS), "short load check ran");
      android.util.Log.i("LoupeStatusTiming", "short finish ms=" + elapsed[0]);
      if (elapsed[0] >= 400) continue;
      main(
          t,
          () -> {
            TagDetailChecks.require(
                hidden[0] && hidden[1] && (Long) field(status, "visibleAt") == priorVisibleAt[0],
                "short load never became visible");
            return true;
          });
      return elapsed[0];
    }
    throw new AssertionError("fixture could not complete a short load within 400ms");
  }

  static String run(Instrumentation t) throws Exception {
    MainActivity a =
        (MainActivity)
            t.startActivitySync(
                new Intent(t.getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    Thread.sleep(700);
    StatusView[] v = {null};
    try {
      main(
          t,
          () -> {
            LinearLayout root = new LinearLayout(a);
            root.setOrientation(1);
            root.setPadding(0, 192, 0, 0);
            root.setBackgroundColor(GalleryUi.BG);
            v[0] = new StatusView(a);
            root.addView(v[0]);
            a.setContentView(root);
            return true;
          });
      long shortMs = checkShortLoad(t, v[0]);
      TagDetailChecks.ui(t, () -> v[0].begin("長い読み込み"));
      GenerationIndicatorChecks.await(
          t,
          "long load becomes visible",
          () ->
              v[0].getVisibility() == View.VISIBLE
                  && "長い読み込み".contentEquals(v[0].getContentDescription()));
      long[] delayMs = {0};
      long fadeMs =
          BurstPreviewChecks.checkMinimumStatusVisibility(
              t,
              v[0],
              () -> {
                delayMs[0] = (Long) field(v[0], "visibleAt") - (Long) field(v[0], "started");
                TagDetailChecks.require(
                    delayMs[0] >= 400, "loading presentation waits at least 400ms");
                v[0].finish();
                return true;
              });
      main(
          t,
          () -> {
            v[0].begin("古い写真");
            v[0].clear();
            return true;
          });
      Thread.sleep(500);
      final int[] clicks = {0};
      main(
          t,
          () -> {
            if (v[0].getVisibility() == View.VISIBLE)
              throw new AssertionError("stale photo status");
            v[0].show(StatusView.Symbol.FAILED, "", "オリジナルを読み込めませんでした", () -> clicks[0]++, "再試行");
            return true;
          });
      Thread.sleep(1300);
      main(
          t,
          () -> {
            if (v[0].getVisibility() != View.VISIBLE)
              throw new AssertionError("failure disappeared");
            AccessibilityNodeInfo node = v[0].createAccessibilityNodeInfo();
            if (!node.isClickable() || !node.getContentDescription().toString().contains("再試行"))
              throw new AssertionError("recovery AX");
            if (!v[0].performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                || clicks[0] != 1) throw new AssertionError("recovery action");
            v[0].result(StatusView.Symbol.COMPLETE, "保存しました");
            return true;
          });
      Thread.sleep(1200);
      main(
          t,
          () -> {
            if (v[0].getVisibility() == View.VISIBLE) throw new AssertionError("success remains");
            return true;
          });
      main(
          t,
          () -> {
            v[0].begin("8秒を超える処理");
            if ((boolean) field(v[0], "transientResult")
                || (boolean) field(v[0], "marker")
                || !"".equals(field(v[0], "recoveryName")))
              throw new AssertionError("previous state leaked into loading");
            return true;
          });
      Thread.sleep(500);
      final int[] initialBounds = {v[0].getWidth(), v[0].getHeight()};
      Thread.sleep(7700);
      main(
          t,
          () -> {
            if (!(boolean) field(v[0], "marker")
                || v[0].getWidth() != initialBounds[0]
                || v[0].getHeight() != initialBounds[1])
              throw new AssertionError("8 second marker shifts layout");
            set(v[0], "started", SystemClock.uptimeMillis() - 61000);
            v[0].invalidate();
            return true;
          });
      // Advance only the elapsed-time origin; no claim of waiting a real minute.
      Thread.sleep(100);
      capture(t, a, v[0], v[0].getHeight(), "status-elapsed-minute");
      main(
          t,
          () -> {
            v[0].counts("3/12");
            v[0].describe("12枚中3枚");
            return true;
          });
      capture(t, a, v[0], v[0].getHeight(), "status-known-count");
      main(
          t,
          () -> {
            v[0].clear();
            return true;
          });
      int cases = 0;
      for (int page = 0; page < 2; page++)
        for (int width : new int[] {320, 400})
          for (float scale : new float[] {1, 1.3f}) {
            final int statePage = page;
            LinearLayout[] board = {null};
            main(
                t,
                () -> {
                  Configuration config = new Configuration(a.getResources().getConfiguration());
                  config.fontScale = scale;
                  Context c =
                      new android.view.ContextThemeWrapper(
                          a.createConfigurationContext(config), R.style.AppTheme);
                  FrameLayout host = new FrameLayout(a);
                  host.setBackgroundColor(GalleryUi.BG);
                  board[0] = new LinearLayout(c);
                  board[0].setOrientation(1);
                  board[0].setBackgroundColor(GalleryUi.BG);
                  FrameLayout.LayoutParams bounds =
                      new FrameLayout.LayoutParams(GalleryUi.dp(c, width), -2);
                  bounds.topMargin = GalleryUi.dp(c, 64);
                  host.addView(board[0], bounds);
                  a.setContentView(host);
                  StatusView.Symbol[] symbols =
                      statePage == 0
                          ? new StatusView.Symbol[] {
                            StatusView.Symbol.UNKNOWN,
                            StatusView.Symbol.WAITING,
                            StatusView.Symbol.PAUSED,
                            StatusView.Symbol.COMPLETE,
                            StatusView.Symbol.PARTIAL,
                            StatusView.Symbol.FAILED,
                            StatusView.Symbol.AUTH,
                            StatusView.Symbol.PREVIEW
                          }
                          : new StatusView.Symbol[] {
                            StatusView.Symbol.RUNNING,
                            StatusView.Symbol.NETWORK,
                            StatusView.Symbol.STORAGE,
                            StatusView.Symbol.PERMISSION,
                            StatusView.Symbol.TLS,
                            StatusView.Symbol.HOST,
                            StatusView.Symbol.NO_APP,
                            StatusView.Symbol.FORMAT,
                            StatusView.Symbol.EMPTY,
                            StatusView.Symbol.CANCELLED,
                            StatusView.Symbol.ALREADY
                          };
                  for (StatusView.Symbol symbol : symbols) {
                    StatusView item = new StatusView(c);
                    board[0].addView(item, new LinearLayout.LayoutParams(-1, GalleryUi.dp(c, 48)));
                    item.show(
                        symbol,
                        symbol == StatusView.Symbol.PARTIAL ? "3" : "",
                        symbol.name(),
                        null,
                        "");
                    if (symbol == StatusView.Symbol.FAILED)
                      item.show(symbol, "", "オリジナル失敗", () -> clicks[0]++, "再試行");
                  }
                  return true;
                });
            capture(
                t,
                a,
                board[0],
                board[0].getHeight(),
                "status-" + width + "-" + scale + (statePage == 0 ? "" : "-causes"));
            cases++;
          }
      String flows = VisualStatusFlowChecks.run(t, a);
      return flows
          + "PASS status delay (short hidden at "
          + shortMs
          + " ms; long shown after "
          + delayMs[0]
          + " ms), minimum 600ms (fade after "
          + fadeMs
          + " ms), stale clear, persistent failure, actual AX retry, transient result, real 8s marker with stable bounds, simulated 61s elapsed origin and known count capture; "
          + cases
          + " real-device synthetic captures. No external transfer; TalkBack audio untested.\n";
    } finally {
      main(
          t,
          () -> {
            a.finish();
            return true;
          });
    }
  }
}
