package jp.nano.rawgallery;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.TextView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Synthetic menus only: no user photos, tags, ranking or selection are changed. */
final class SheetChecks {
  static String run(Instrumentation test) throws Exception {
    MainActivity activity = (MainActivity) test.startActivitySync(
        new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    StringBuilder result = new StringBuilder();
    try {
      test.runOnMainSync(() -> {
        TextView backdrop = new TextView(activity);
        backdrop.setText("メニュー表示テスト"); backdrop.setTextColor(GalleryUi.TEXT);
        backdrop.setBackgroundColor(GalleryUi.BG); activity.setContentView(backdrop);
      });
      for (int kind = 0; kind < 3; kind++) {
        int mode = kind;
        AlertDialog[] dialog = new AlertDialog[1];
        boolean[] clicked = {false}, shown = {false};
        test.runOnMainSync(() -> {
          GalleryUi.Sheet builder = new GalleryUi.Sheet(activity);
          if (mode == 2) {
            EditText input = new EditText(activity); input.setHint("タグ名");
            builder.setTitle("新しいタグ").setView(input).setPositiveButton("作成", null);
          } else {
            String[] items = mode == 0 ? new String[]{"カメラで絞り込み", "タグ", "設定とその他"} : new String[40];
            if (mode == 1) for (int i = 0; i < items.length; i++) items[i] = "項目 " + i;
            builder.setItems(items, (d, which) -> clicked[0] = true);
          }
          builder.setNegativeButton("閉じる", null);
          dialog[0] = builder.create();
          // This is the same override pattern used by the tag creation screen.
          dialog[0].setOnShowListener(d -> shown[0] = true);
        });
        for (int repeat = 0; repeat < 2; repeat++) {
          Rect[] first = new Rect[1]; Rect[] visible = new Rect[1];
          CountDownLatch drawn = new CountDownLatch(1);
          View decor = dialog[0].getWindow().getDecorView();
          ViewTreeObserver.OnDrawListener observer = () -> {
            if (first[0] == null) {
              first[0] = bounds(decor);
              visible[0] = new Rect(); decor.getWindowVisibleDisplayFrame(visible[0]);
              drawn.countDown();
            }
          };
          long start = android.os.SystemClock.elapsedRealtime();
          test.runOnMainSync(() -> { decor.getViewTreeObserver().addOnDrawListener(observer); dialog[0].show(); });
          if (!drawn.await(4, TimeUnit.SECONDS)) throw new AssertionError("no first draw " + mode);
          Thread.sleep(300);
          Rect[] stable = new Rect[1];
          test.runOnMainSync(() -> {
            stable[0] = bounds(decor);
            decor.getViewTreeObserver().removeOnDrawListener(observer);
          });
          int expectedWidth = Math.min(activity.getResources().getDisplayMetrics().widthPixels
              - GalleryUi.dp(activity, 24), GalleryUi.dp(activity, 560));
          if (!first[0].equals(stable[0])) throw new AssertionError("moving frame " + first[0] + " -> " + stable[0]);
          if (first[0].width() != expectedWidth) throw new AssertionError("wrong width " + first[0]);
          if (Math.abs(first[0].bottom - visible[0].bottom) > 1) throw new AssertionError("not bottom " + first[0] + " visible=" + visible[0]);
          if (first[0].height() > (int)(visible[0].height() * .48f)) throw new AssertionError("height cap " + first[0]);
          if (!shown[0]) throw new AssertionError("onShow lost");
          result.append("PASS mode=").append(mode).append(" repeat=").append(repeat)
              .append(" first=").append(first[0]).append(" unchanged after 300ms; observed_ms=")
              .append(android.os.SystemClock.elapsedRealtime() - start).append('\n');
          test.runOnMainSync(() -> {
            if (mode == 0 && dialog[0].getListView() != null)
              dialog[0].getListView().performItemClick(dialog[0].getListView().getChildAt(0), 0, 0);
            dialog[0].dismiss();
          });
        }
        if (mode == 0 && !clicked[0]) throw new AssertionError("item action lost");
      }
      return result.toString();
    } finally { test.runOnMainSync(activity::finish); }
  }
  private static Rect bounds(View view) {
    int[] p = new int[2]; view.getLocationOnScreen(p);
    return new Rect(p[0], p[1], p[0] + view.getWidth(), p[1] + view.getHeight());
  }
}
