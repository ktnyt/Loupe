package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

final class GalleryPositionChecks {
  private static final int GALLERY_ANCHOR = 27; // The real gallery spans three columns.

  private static Object field(Object o, String name) throws Exception {
    Field f = o.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(o);
  }

  private static void waitFor(Instrumentation test, String stage, DiagnosticChecks.Check check)
      throws Exception {
    try {
      DiagnosticChecks.waitFor(test, check);
    } catch (AssertionError error) {
      throw new AssertionError(stage + ": " + error.getMessage(), error);
    }
  }

  static String run(Instrumentation test) throws Exception {
    MainActivity a =
        (MainActivity)
            test.startActivitySync(
                new Intent(test.getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      GalleryScreenChecks.finishInitialIndex(test, a);
      GalleryIndex.Snapshot snapshot;
      try (GalleryIndex index = new GalleryIndex(a)) {
        snapshot = index.load(null, "すべて");
      }
      if (snapshot.bundles.size() < 40)
        throw new AssertionError("Need 40 groups for read-only integration check");
      Method load = MainActivity.class.getDeclaredMethod("loadIndexedPhotos");
      load.setAccessible(true);
      TagDetailChecks.ui(
          test,
          () -> {
            LibraryTabs tabs =
                ExpressiveUiChecks.find(a.getWindow().getDecorView(), LibraryTabs.class);
            TagDetailChecks.require(
                tabs != null && tabs.destinationView(0).performClick(),
                "open native photo destination");
          });
      GalleryScreenChecks.settle(test, a);
      ScrollingGrid real = (ScrollingGrid) field(a, "grid");
      waitFor(
          test, "real grid loaded", () -> real.isAttachedToWindow() && real.getChildCount() > 1);
      test.runOnMainSync(() -> real.restoreCell(GALLERY_ANCHOR, -37, false));
      waitFor(
          test,
          "real grid scrolled",
          () -> real.firstCellPosition() == GALLERY_ANCHOR && real.getChildAt(0).getTop() == -37);
      Object beforeReload = field(a, "photos");
      test.runOnMainSync(
          () -> {
            try {
              load.invoke(a);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      ((ExecutorService) field(a, "scans")).submit(() -> {}).get(8, TimeUnit.SECONDS);
      test.waitForIdleSync();
      waitFor(
          test,
          "index reload applied and preserved live position",
          () ->
              RankingResultChecks.get(a, "photos") != beforeReload
                  && RankingResultChecks.get(a, "grid") == real
                  && real.isAttachedToWindow()
                  && real.getChildCount() > 0
                  && real.firstCellPosition() == GALLERY_ANCHOR
                  && real.getChildAt(0).getTop() == -37);

      List<PhotoRepository.Photo> photos = new ArrayList<>();
      List<List<Integer>> shown = new ArrayList<>();
      for (int i = 0; i < 160; i++) {
        photos.add(
            new PhotoRepository.Photo(
                Uri.parse("file:///position-fixture-" + i), "synthetic", "", 1, 1, 0));
        shown.add(Collections.singletonList(i));
      }
      ScrollingGrid[] grid = {null};
      BaseAdapter[] adapter = {null};
      GalleryPosition[] anchor = {null};
      test.runOnMainSync(
          () -> {
            grid[0] = new ScrollingGrid(a);
            grid[0].setNumColumns(4);
            grid[0].setPadding(0, 0, 0, 0);
            TextView header = new TextView(a);
            header.setHeight(200);
            grid[0].addHeaderView(header);
            adapter[0] =
                new BaseAdapter() {
                  public int getCount() {
                    return shown.size();
                  }

                  public Object getItem(int p) {
                    return p;
                  }

                  public long getItemId(int p) {
                    return p;
                  }

                  public View getView(int p, View old, ViewGroup parent) {
                    TextView v = new TextView(a);
                    v.setHeight(200);
                    v.setText("fixture");
                    return v;
                  }
                };
            grid[0].setCells(adapter[0]);
            a.setContentView(grid[0]);
          });
      waitFor(test, "stage 3", () -> grid[0].getChildCount() > 1);
      test.runOnMainSync(() -> grid[0].restoreCell(28, -37, false));
      try {
        waitFor(
            test,
            "stage 4",
            () -> grid[0].firstCellPosition() == 28 && grid[0].getChildAt(0).getTop() == -37);
      } catch (AssertionError e) {
        throw new AssertionError(
            "synthetic initial: first="
                + grid[0].firstCellPosition()
                + " top="
                + grid[0].getChildAt(0).getTop()
                + " height="
                + grid[0].getChildAt(0).getHeight()
                + " rows="
                + grid[0].getCount(),
            e);
      }
      test.runOnMainSync(
          () -> {
            anchor[0] = GalleryPosition.capture(grid[0], photos, shown);
            for (int i = 0; i < 4; i++) shown.add(0, Collections.singletonList(79 - i));
            adapter[0].notifyDataSetChanged();
            anchor[0].restore(grid[0], photos, shown);
          });
      waitFor(
          test,
          "stage 5",
          () -> grid[0].firstCellPosition() == 32 && grid[0].getChildAt(0).getTop() == -37);
      test.runOnMainSync(
          () -> {
            anchor[0] = GalleryPosition.capture(grid[0], photos, shown);
            shown.remove(32);
            adapter[0].notifyDataSetChanged();
            anchor[0].restore(grid[0], photos, shown);
          });
      waitFor(
          test,
          "stage 6",
          () -> grid[0].firstCellPosition() == 32 && grid[0].getChildAt(0).getTop() == -37);
      test.runOnMainSync(() -> grid[0].setSelectionFromTop(0, -63));
      waitFor(
          test,
          "stage 7",
          () -> grid[0].getFirstVisiblePosition() == 0 && grid[0].getChildAt(0).getTop() == -63);
      test.runOnMainSync(
          () -> {
            anchor[0] = GalleryPosition.capture(grid[0], photos, shown);
            adapter[0].notifyDataSetChanged();
            anchor[0].restore(grid[0], photos, shown);
          });
      waitFor(
          test,
          "stage 8",
          () -> grid[0].getFirstVisiblePosition() == 0 && grid[0].getChildAt(0).getTop() == -63);
      return "PASS real index reload preserves cell/top; insertion follows photo; removed anchor falls back; partial header retained. No originals or index changed.\n";
    } finally {
      test.runOnMainSync(a::finish);
    }
  }
}
