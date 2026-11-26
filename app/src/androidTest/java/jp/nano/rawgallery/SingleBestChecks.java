package jp.nano.rawgallery;

import static jp.nano.rawgallery.RankingResultChecks.*;
import static jp.nano.rawgallery.TagDetailChecks.require;
import static jp.nano.rawgallery.TagDetailChecks.ui;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Synthetic photos and isolated preferences; never modifies the user's ranking journal. */
final class SingleBestChecks {
  private static Button toggle(MainActivity a) {
    PhotoViewerUi v = ViewerScreenChecks.viewer(a);
    return (Button) v.findViewWithTag("viewer-action-1位");
  }

  private static void idle(Instrumentation t, MainActivity a) throws Exception {
    ((ExecutorService) get(a, "actions")).submit(() -> {}).get(10, TimeUnit.SECONDS);
    await(
        t,
        () -> {
          Button star = ((View) get(a, "root")).findViewWithTag("viewer-action-1位");
          return star == null || !"保存中".contentEquals(star.getStateDescription());
        },
        "singleton save completed");
    t.waitForIdleSync();
    ConsistencyLayoutChecks.frame(t, (View) get(a, "root"));
  }

  private static void await(
      Instrumentation t, java.util.function.BooleanSupplier check, String message)
      throws Exception {
    long until = android.os.SystemClock.uptimeMillis() + 5000;
    do {
      boolean[] ready = {false};
      ui(t, () -> ready[0] = check.getAsBoolean());
      if (ready[0]) return;
      Thread.sleep(20);
    } while (android.os.SystemClock.uptimeMillis() < until);
    throw new AssertionError(message);
  }

  private static void gallery(Instrumentation t, MainActivity a, int filter) throws Exception {
    ui(
        t,
        () -> {
          set(a, "rankFilter", filter);
          call(a, "gallery");
        });
    GalleryScreenChecks.settle(t, a);
  }

  @SuppressWarnings("unchecked")
  private static List<List<Integer>> shown(MainActivity a) {
    return (List<List<Integer>>) get(a, "displayed");
  }

  private static void capture(Instrumentation t, MainActivity a, File dir, String name)
      throws Exception {
    ConsistencyLayoutChecks.captureHardware(t, a, (View) get(a, "root"), dir, name);
  }

  static String run(Instrumentation t, int width, float font) throws Exception {
    MainActivity a =
        (MainActivity)
            t.startActivitySync(
                new Intent(t.getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    List<File> files = new ArrayList<>();
    String namespace = "single-best-checks-" + UUID.randomUUID();
    SharedPreferences prefs = a.getSharedPreferences(namespace, 0);
    RankingStore ordinaryOwner = null;
    RankingStore flakyOwner = null;
    try {
      ((ExecutorService) get(a, "scans")).submit(() -> {}).get(20, TimeUnit.SECONDS);
      GalleryScreenChecks.portraitFixture(t, a, width, font);
      require(prefs.edit().clear().commit(), "reset isolated rankings");
      var next =
          new java.util.concurrent.atomic.AtomicReference<RankingPersistenceWaitChecks.Commit>();
      RankingStore store = new RankingStore(RankingPersistenceWaitChecks.observe(prefs, next));
      ordinaryOwner = store;
      List<PhotoRepository.Photo> photos = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        File f = new File(a.getCacheDir(), "single-best-fixture-" + i + ".jpg");
        files.add(f);
        Bitmap bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        Paint p = new Paint(3);
        p.setShader(
            new LinearGradient(0, 0, 600, 400, 0xff6486a5, 0xff28353e, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 600, 400, p);
        p.setShader(null);
        p.setColor(0xffe5c591);
        c.drawCircle(380, 150, 65, p);
        try (FileOutputStream out = new FileOutputStream(f)) {
          bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        bitmap.recycle();
        photos.add(
            new PhotoRepository.Photo(
                Uri.fromFile(f),
                "検査写真" + i + ".jpg",
                "/single-best",
                f.length(),
                1792298040L + i,
                1792298040000L + i * 1000));
      }
      List<String> burst =
          List.of(ArchiveStore.key(photos.get(2)), ArchiveStore.key(photos.get(3)));
      PhotoRanking ranked = store.load(burst).choose(true);
      require(store.save(ranked), "seed existing burst");
      ui(
          t,
          () -> {
            a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            set(a, "photos", photos);
            set(a, "bundles", List.of(List.of(0), List.of(1), List.of(2, 3)));
            set(a, "rankingStore", store);
            ((Map<?, ?>) get(a, "rankCache")).clear();
            set(a, "burstMembers", null);
            set(a, "selecting", false);
            set(a, "albumFolder", "/single-best");
          });
      gallery(t, a, 1);
      ui(
          t,
          () ->
              require(
                  shown(a).size() == 1 && !shown(a).contains(List.of(0)),
                  "unmarked singleton excluded from best"));
      gallery(t, a, 0);
      ViewerScreenChecks.open(t, a, 0);
      File dir = new File(a.getCacheDir(), "consistency-checks");
      dir.mkdirs();
      String prefix = "single-best-" + width + "-" + font;
      ui(t, () -> require(toggle(a) != null, "initial toggle exists"));
      ui(t, () -> require(!toggle(a).isSelected(), "initial toggle unmarked"));
      ui(t, () -> require(toggle(a).createAccessibilityNodeInfo().isCheckable(), "AX checkable"));
      ui(t, () -> require(!toggle(a).createAccessibilityNodeInfo().isChecked(), "AX unchecked"));
      ui(t, () -> ViewerScreenChecks.toolbar(ViewerScreenChecks.viewer(a), 8, width <= 320));
      capture(t, a, dir, prefix + "-off");
      int[] before = new int[4];
      ui(
          t,
          () -> {
            Button b = toggle(a);
            before[0] = b.getLeft();
            before[1] = b.getTop();
            before[2] = b.getWidth();
            before[3] = b.getHeight();
            b.performClick();
            require(
                !b.isEnabled() && "保存中".contentEquals(b.getStateDescription()),
                "disable and announce saving during commit");
          });
      idle(t, a);
      ui(
          t,
          () -> {
            Button b = toggle(a);
            require(
                b.isSelected() && b.isEnabled() && b.createAccessibilityNodeInfo().isChecked(),
                "marked state and AX");
            require(
                b.getLeft() == before[0]
                    && b.getTop() == before[1]
                    && b.getWidth() == before[2]
                    && b.getHeight() == before[3],
                "stable toggle geometry");
          });
      require(
          RankingPersistenceWaitChecks.readSavedSingle(prefs, ArchiveStore.key(photos.get(0))),
          "new store reads saved mark");
      require(
          !store.singleBest(ArchiveStore.key(photos.get(1)))
              && store.load(burst).journal.equals(ranked.journal),
          "other photo and burst unchanged");
      capture(t, a, dir, prefix + "-on");
      ui(t, () -> a.onBackPressed());
      gallery(t, a, 1);
      ui(
          t,
          () ->
              require(
                  shown(a).contains(List.of(0))
                      && !shown(a).contains(List.of(1))
                      && shown(a).size() == 2,
                  "best filter includes only marked single and burst winner"));
      capture(t, a, dir, prefix + "-filter");
      gallery(t, a, 2);
      ui(t, () -> require(shown(a).equals(List.of(List.of(1))), "unranked filter"));
      gallery(t, a, 3);
      ui(
          t,
          () ->
              require(
                  shown(a).contains(List.of(0)) && shown(a).contains(List.of(2, 3)),
                  "ranked filter"));
      gallery(t, a, 0);
      ViewerScreenChecks.open(t, a, 0);
      ui(
          t,
          () -> {
            require(toggle(a).isSelected(), "reopen keeps mark");
            toggle(a).performClick();
          });
      idle(t, a);
      require(
          !RankingPersistenceWaitChecks.readSavedSingle(prefs, ArchiveStore.key(photos.get(0))),
          "unmark persisted");
      ui(t, () -> a.onBackPressed());
      gallery(t, a, 1);
      ui(t, () -> require(!shown(a).contains(List.of(0)), "unmarked disappears from best"));
      String key = ArchiveStore.key(photos.get(0));
      gallery(t, a, 0);
      ViewerScreenChecks.open(t, a, 0);
      var viewerWrite = new RankingPersistenceWaitChecks.Commit(true);
      next.set(viewerWrite);
      try {
        ui(t, () -> toggle(a).performClick());
        require(viewerWrite.entered.await(2, TimeUnit.SECONDS), "viewer writer entered");
        ui(t, a::onBackPressed);
        ViewerScreenChecks.open(t, a, 0);
        ui(t, () -> require(!toggle(a).isSelected(), "reopened viewer reads committed old value"));
      } finally {
        viewerWrite.release();
      }
      await(t, () -> toggle(a).isSelected(), "held write refreshes reopened viewer");
      ui(t, () -> toggle(a).performClick());
      idle(t, a);
      require(
          !RankingPersistenceWaitChecks.readSavedSingle(prefs, key), "reopened viewer can unmark");

      gallery(t, a, 0);
      ViewerScreenChecks.open(t, a, 0);
      var filterWrite = new RankingPersistenceWaitChecks.Commit(true);
      next.set(filterWrite);
      try {
        ui(t, () -> toggle(a).performClick());
        require(filterWrite.entered.await(2, TimeUnit.SECONDS), "filter writer entered");
        ui(t, a::onBackPressed);
        gallery(t, a, 1);
        ui(t, () -> require(!shown(a).contains(List.of(0)), "filter sees old value before commit"));
      } finally {
        filterWrite.release();
      }
      await(t, () -> shown(a).contains(List.of(0)), "held write refreshes current rank filter");

      // The other handle's write is queued first while the visible control still has the old value.
      gallery(t, a, 0);
      ViewerScreenChecks.open(t, a, 0);
      RankingStore other = new RankingStore(store);
      ui(
          t,
          () -> {
            other.submitSingleBest(key, true, false);
            toggle(a).performClick();
          });
      idle(t, a);
      ui(
          t,
          () -> {
            require(!toggle(a).isSelected() && toggle(a).isEnabled(), "stale save refreshes value");
            toggle(a).performClick();
          });
      idle(t, a);
      require(
          RankingPersistenceWaitChecks.readSavedSingle(prefs, key),
          "fresh explicit input after stale rejection");

      next.set(new RankingPersistenceWaitChecks.Commit(false, true));
      ui(t, () -> toggle(a).performClick());
      idle(t, a);
      require(
          RankingPersistenceWaitChecks.readSavedSingle(prefs, key),
          "failed commit restored saved mark");
      ui(
          t,
          () -> {
            require(
                toggle(a).isSelected()
                    && toggle(a).getContentDescription().toString().contains("保存できません"),
                "failed save keeps old state and exposes retry");
            toggle(a).performClick();
          });
      idle(t, a);
      require(
          !RankingPersistenceWaitChecks.readSavedSingle(prefs, key), "failed save retry unmarks");

      var readsToFail = new java.util.concurrent.atomic.AtomicInteger(2);
      SharedPreferences flaky =
          (SharedPreferences)
              java.lang.reflect.Proxy.newProxyInstance(
                  SharedPreferences.class.getClassLoader(),
                  new Class<?>[] {SharedPreferences.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("getAll") && readsToFail.getAndDecrement() > 0)
                      throw new IllegalStateException("Synthetic initial load failure");
                    return method.invoke(prefs, args);
                  });
      RankingStore flakyStore = new RankingStore(flaky);
      flakyOwner = flakyStore;
      ui(t, () -> set(a, "rankingStore", flakyStore));
      ViewerScreenChecks.open(t, a, 0);
      await(
          t,
          () -> toggle(a).getContentDescription().toString().contains("読み込めません"),
          "initial rank read failure displayed");
      ui(t, () -> toggle(a).performClick());
      await(
          t,
          () ->
              readsToFail.get() == 0
                  && toggle(a).getContentDescription().toString().contains("読み込めません"),
          "failed read retries without mutation");
      ui(t, () -> toggle(a).performClick());
      await(
          t,
          () -> toggle(a).isEnabled() && toggle(a).createAccessibilityNodeInfo().isCheckable(),
          "read retry returned to native checked control");
      require(!RankingPersistenceWaitChecks.readSavedSingle(prefs, key), "read retry never writes");
      ui(t, () -> toggle(a).performClick());
      idle(t, a);
      require(
          RankingPersistenceWaitChecks.readSavedSingle(prefs, key),
          "explicit click after read retry writes");
      gallery(t, a, 0);
      ViewerScreenChecks.open(t, a, 2);
      ui(t, () -> require(toggle(a) == null, "burst member has no singleton toggle"));
      return "PASS single-best "
          + width
          + "dp/font"
          + font
          + ": mark/unmark, reload, AX, stable geometry, filters, queued navigation/filter and"
          + " stale retry, failed commit/read recovery, burst isolation; screenshots in"
          + " consistency-checks\n";
    } finally {
      try {
        ui(t, () -> a.finish());
      } finally {
        try {
          RankingPersistenceWaitChecks.drainRankingWriter(ordinaryOwner);
          RankingPersistenceWaitChecks.drainRankingWriter(flakyOwner);
          if (!a.deleteSharedPreferences(namespace))
            throw new AssertionError("Delete drained isolated rankings");
        } finally {
          for (File f : files) f.delete();
        }
      }
    }
  }
}
