package jp.nano.rawgallery;

import static jp.nano.rawgallery.BurstCoWinnerChecks.*;
import static jp.nano.rawgallery.RankingResultChecks.call;
import static jp.nano.rawgallery.RankingResultChecks.get;
import static jp.nano.rawgallery.RankingResultChecks.set;
import static jp.nano.rawgallery.TagDetailChecks.ui;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Shader;
import android.net.Uri;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.CompoundButton;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Runs the user-facing bulk action with synthetic photos and isolated rankings. */
final class BurstBulkWinnerChecks {
  private static final int SCROLL_BURST_SIZE = 24;
  private static final int[] INITIAL = {3, 1, 4, 4, 6, 7, 1, 8, 9, 10, 11, 12};
  private static final int[] PROMOTED = {1, 1, 5, 1, 6, 7, 1, 8, 9, 10, 11, 12};
  private static final String CANCEL = "キャンセル";
  private static final String CONFIRM = "2枚を同率1位にしますか？";
  private static final String APPLY = "2枚を同率1位にする";
  private static final String FAILED = "保存できませんでした。順位と選択は前のままです";
  private static final String SAVED = "2枚を同率1位にしました";
  private static final String SWITCH = "連写は1位だけ共有";

  static String run(Instrumentation t, int width, float font) throws Exception {
    MainActivity a = (MainActivity) t.startActivitySync(new Intent(t.getTargetContext(),
        MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    SharedPreferences prefs = a.getSharedPreferences("burst-bulk-winners-test", 0);
    List<File> files = new ArrayList<>();
    CountDownLatch release = new CountDownLatch(1);
    try {
      ((ExecutorService) get(a, "scans")).submit(() -> {}).get(10, TimeUnit.SECONDS);
      check(prefs.edit().clear().commit(), "Reset fixture rankings");
      List<PhotoRepository.Photo> photos = fixtures(a, files);
      List<String> fixtureKeys = new ArrayList<>();
      for (PhotoRepository.Photo photo : photos) fixtureKeys.add(ArchiveStore.key(photo));
      List<String> keys = fixtureKeys.subList(0, INITIAL.length);
      List<String> otherKeys = fixtureKeys.subList(INITIAL.length, INITIAL.length + 2);
      List<Integer> members = IntStream.range(0, keys.size()).boxed().collect(Collectors.toList());
      List<Integer> other = List.of(keys.size(), keys.size() + 1);
      RankingStore disk = new RankingStore(prefs);
      PhotoRanking original = ranked(disk, keys, INITIAL);
      PhotoRanking otherRanking = ranked(disk, otherKeys, new int[] {1, 2});
      check(disk.save(original) && disk.save(otherRanking), "Seed rankings");

      AtomicInteger commits = new AtomicInteger();
      RankingStore store = new RankingStore(failFirstCommit(prefs, release, commits));
      ui(t, () -> seed(a, photos, members, other, store));
      clickExact(t, a, "選択");
      await(t, "empty bulk action", () -> bulk(a) != null && !bulkEnabled(a));
      String prefix = "burst-bulk-winners-" + width + "-" + font;
      tapPhoto(t, 0);
      tapPhoto(t, 3);
      await(t, "two selected", () -> bulkEnabled(a) && bulk(a).getText().toString().contains("2枚"));
      ui(t, () -> {
        if (width > 0) {
          check(a.getWindow().getDecorView().getWidth() == GalleryUi.dp(a, width), "Activity width");
          check(Math.abs(a.getResources().getConfiguration().fontScale - font) < .001f, "Font scale");
        }
        check(((CompoundButton) windowView(SWITCH)).isChecked(), "Best-only sharing starts enabled");
        check(shared(a).isEmpty(), "Best-only sharing initially excludes lower photos");
      });
      capture(t, a, prefix + "-selection", SWITCH);

      click(t, SWITCH);
      ui(t, () -> check(shared(a).equals(List.of(photos.get(0), photos.get(3))),
          "Sharing switch includes both lower photos when off"));
      open(t, a);
      capture(t, a, prefix + "-confirm", CONFIRM);
      click(t, CANCEL);
      await(t, "cancel closed confirmation", a::hasWindowFocus);
      click(t, SWITCH);

      open(t, a);
      back(t);
      await(t, "Back closed confirmation", a::hasWindowFocus);
      open(t, a);
      tapOutside(t, a, CONFIRM);
      await(t, "outside touch closed confirmation", a::hasWindowFocus);
      check(commits.get() == 0, "Dismissal attempted save");
      assertRanks(disk, keys, INITIAL);

      String outside = photos.get(keys.size() + 1).uri.toString();
      ui(t, () -> selections(a).add(outside));
      int[] before = gridPosition(t, a);
      open(t, a);
      visible(t, "ほかの連写の1枚は含みません");
      // Alter the live selection while the modal is open. The saved operation still uses
      // the two originally displayed targets, even when the current set changes.
      String later = photos.get(2).uri.toString();
      ui(t, () -> selections(a).add(later));
      click(t, APPLY);
      visible(t, "保存しています");
      await(t, "first commit entered", () -> commits.get() == 1);
      ui(t, () -> {
        check(!windowView("保存しています").isEnabled(), "Confirm still enabled during save");
        check(!windowView(CANCEL).isEnabled(), "Cancel still enabled during save");
      });
      back(t);
      visible(t, "保存しています");
      tapOutside(t, a, CONFIRM);
      visible(t, "保存しています");
      assertRanks(disk, keys, INITIAL);
      release.countDown();
      visible(t, FAILED);
      check(commits.get() == 2, "Failure should restore process memory and disk");
      assertRanks(store, keys, INITIAL);
      assertRanks(disk, keys, INITIAL);
      ui(t, () -> selections(a).remove(later));
      capture(t, a, prefix + "-failure", FAILED);
      click(t, "再試行");
      await(t, "retry completed", () -> a.hasWindowFocus() && !bulkEnabled(a));
      check(commits.get() == 3, "Retry should use a single commit");
      assertRanks(new RankingStore(prefs), keys, PROMOTED);
      check(disk.load(otherKeys).journal.equals(otherRanking.journal), "Other burst changed");
      check(Arrays.equals(gridPosition(t, a), before), "Bulk save moved the grid");
      ui(t, () -> {
        check(Boolean.TRUE.equals(get(a, "selecting")) && get(a, "burstMembers").equals(members),
            "Bulk save left selection or changed bursts");
        check(selections(a).equals(Set.of(photos.get(0).uri.toString(),
            photos.get(3).uri.toString(), outside)), "Bulk save changed selected photos");
        check(((CompoundButton) windowView(SWITCH)).isChecked(), "Bulk save changed sharing setting");
        check(shared(a).equals(List.of(photos.get(0), photos.get(3))),
            "Best-only sharing did not use the newly saved winners");
      });
      capture(t, a, prefix + "-success", SAVED);
      ui(t, () -> bulk(a).performClick());
      ui(t, () -> check(a.hasWindowFocus(), "Disabled action opened confirmation"));
      check(commits.get() == 3, "All already winners saved again");

      // Keep selected non-winners when a rank filter hides their tiles.
      ui(t, () -> seed(a, photos, members, other, store));
      clickExact(t, a, "選択");
      tapPhoto(t, 2);
      tapPhoto(t, 4);
      clickExact(t, a, "1位");
      await(t, "rank filter hides selected photos", () ->
          windowView(photoLabel(2)) == null && windowView(photoLabel(4)) == null);
      open(t, a);
      visible(t, "絞り込みで見えない2枚を含みます");
      click(t, APPLY);
      await(t, "hidden selection saved", () -> a.hasWindowFocus() && !bulkEnabled(a));
      check(commits.get() == 4, "Hidden selection needs a single save");
      check(Set.copyOf(disk.load(keys).best()).equals(Set.of(keys.get(0), keys.get(1),
          keys.get(2), keys.get(3), keys.get(4), keys.get(6))), "Hidden selections omitted");

      // An unfinished journal can be replaced after warning, including selecting the entire burst.
      List<Integer> longBurst = IntStream.range(0, fixtureKeys.size()).boxed()
          .collect(Collectors.toList());
      PhotoRanking mid = new PhotoRanking(disk.load(fixtureKeys).ids, "B:").choose(false);
      check(store.save(mid), "Seed an unfinished comparison");
      ui(t, () -> seed(a, photos, longBurst, List.of(), store));
      clickExact(t, a, "選択");
      tapPhoto(t, 0);
      tapPhoto(t, 1);
      tapBulk(t, a);
      visible(t, "途中の比較");
      click(t, CANCEL);
      await(t, "unfinished confirmation closed", a::hasWindowFocus);
      check(disk.load(fixtureKeys).journal.equals(mid.journal), "Cancel replaced unfinished journal");
      click(t, "表示中の写真をすべて選択");
      t.waitForIdleSync();
      int[] unscrolled = gridPosition(t, a);
      ui(t, () -> ((ScrollingGrid) get(a, "grid")).scrollListBy(GalleryUi.dp(a, 80)));
      t.waitForIdleSync();
      int[] scrolled = gridPosition(t, a);
      check(!Arrays.equals(unscrolled, scrolled), "Long burst grid did not scroll");
      tapBulk(t, a);
      visible(t, "途中の比較");
      click(t, fixtureKeys.size() + "枚を1位にする");
      await(t, "all selected saved", () -> a.hasWindowFocus() && !bulkEnabled(a));
      t.waitForIdleSync();
      check(disk.load(fixtureKeys).best().size() == fixtureKeys.size(), "All selected photos must tie first");
      check(Arrays.equals(gridPosition(t, a), scrolled), "Bulk save moved a scrolled grid");
      return "PASS isolated burst bulk UI: multiple selections, empty/already disabled, cancel, Back, "
          + "outside, busy Back, rollback and single retry, frozen targets, selection/grid retention, "
          + "best-only independence, hidden and other burst selections, unfinished and all selected.\n";
    } finally {
      release.countDown();
      ui(t, a::finish);
      prefs.edit().clear().commit();
      for (File file : files) file.delete();
    }
  }

  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  private static Button bulk(MainActivity a) {
    return (Button) get(a, "selectionBulk");
  }

  private static void tapBulk(Instrumentation t, MainActivity a) throws InterruptedException {
    await(t, "enabled bulk action", () -> a.hasWindowFocus() && bulkEnabled(a));
    ui(t, () -> check(bulk(a).performClick(), "Bulk action did not open"));
  }

  private static void open(Instrumentation t, MainActivity a) throws InterruptedException {
    tapBulk(t, a);
    visible(t, CONFIRM);
  }

  private static boolean bulkEnabled(MainActivity a) {
    Button button = bulk(a);
    if (button == null) return false;
    AccessibilityNodeInfo node = button.createAccessibilityNodeInfo();
    try {
      return node.isEnabled();
    } finally {
      node.recycle();
    }
  }

  private static void clickExact(Instrumentation t, MainActivity a, String label)
      throws InterruptedException {
    await(t, "button " + label, () -> a.hasWindowFocus()
        && exactButton(a.getWindow().getDecorView(), label) != null);
    ui(t, () -> check(exactButton(a.getWindow().getDecorView(), label)
        .performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null), "Click " + label));
  }

  private static Button exactButton(View view, String label) {
    if (!view.isShown()) return null;
    if (view instanceof Button && (label.contentEquals(((Button) view).getText())
        || label.equals(view.getContentDescription()))) return (Button) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        Button found = exactButton(group.getChildAt(i), label);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static int[] gridPosition(Instrumentation t, MainActivity a) {
    int[] position = new int[2];
    ui(t, () -> {
      ScrollingGrid grid = (ScrollingGrid) get(a, "grid");
      position[0] = grid.getFirstVisiblePosition();
      position[1] = grid.getChildAt(0).getTop();
    });
    return position;
  }

  private static void tapPhoto(Instrumentation t, int index) throws InterruptedException {
    String label = photoLabel(index);
    int[] point = new int[2];
    await(t, "visible photo tile " + index, () -> {
      View tile = windowView(label);
      Rect rect = new Rect();
      Point origin = new Point();
      if (tile == null || !tile.getGlobalVisibleRect(rect, origin) || rect.height() < 8) return false;
      int[] screen = new int[2];
      tile.getLocationOnScreen(screen);
      point[0] = rect.centerX() + screen[0] - origin.x;
      point[1] = rect.centerY() + screen[1] - origin.y;
      return true;
    });
    long down = SystemClock.uptimeMillis();
    for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
      MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
          point[0], point[1], 0);
      event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
      try {
        check(t.getUiAutomation().injectInputEvent(event, true), "Tap photo tile " + index);
      } finally {
        event.recycle();
      }
    }
    t.waitForIdleSync();
    await(t, "photo selected " + index, () -> {
      View tile = windowView(label);
      return tile != null && tile.getContentDescription().toString().contains("選択済み");
    });
  }

  private static String photoLabel(int index) {
    return "bulk-winner-" + (index + 1) + ".jpg";
  }

  @SuppressWarnings("unchecked")
  private static Set<String> selections(MainActivity a) {
    return (Set<String>) get(a, "selectedPhotos");
  }

  private static List<?> shared(MainActivity a) {
    try {
      Method resolve = MainActivity.class.getDeclaredMethod("resolveSelection");
      resolve.setAccessible(true);
      Object result = resolve.invoke(a);
      Field selected = result.getClass().getDeclaredField("photos");
      selected.setAccessible(true);
      return (List<?>) selected.get(result);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static void seed(MainActivity a, List<PhotoRepository.Photo> photos,
      List<Integer> members, List<Integer> other, RankingStore store) {
    set(a, "rankingStore", store);
    set(a, "photos", photos);
    set(a, "bundles", other.isEmpty() ? List.of(members) : List.of(members, other));
    set(a, "burstMembers", members);
    set(a, "burstPosition", 0);
    set(a, "rankFilter", 0);
    set(a, "selecting", false);
    selections(a).clear();
    ((Map<?, ?>) get(a, "rankCache")).clear();
    call(a, "gallery");
  }

  private static PhotoRanking ranked(RankingStore store, List<String> keys, int[] ranks)
      throws Exception {
    PhotoRanking result = store.load(keys);
    while (!result.done()) {
      int upper = ranks[keys.indexOf(result.upper())], lower = ranks[keys.indexOf(result.lower())];
      result = upper == lower ? result.tie() : result.choose(upper < lower);
    }
    return result;
  }

  private static void assertRanks(RankingStore store, List<String> keys, int[] ranks)
      throws Exception {
    PhotoRanking result = store.load(keys);
    check(result.done(), "Ranking incomplete");
    for (int i = 0; i < keys.size(); i++) {
      check(result.rankOf(keys.get(i)) == ranks[i], "Unexpected rank at photo " + (i + 1));
    }
  }

  private static List<PhotoRepository.Photo> fixtures(MainActivity a, List<File> files)
      throws Exception {
    String[][] colors = {{"#8D979E", "#586065"}, {"#34443A", "#1C2419"}, {"#A9C3D8", "#69736A"},
        {"#7B5B45", "#2A201B"}, {"#C8B08A", "#6E5A44"}, {"#3A3F45", "#8AB4F8"},
        {"#B6463C", "#E9C46A"}, {"#5D6B58", "#C9A27E"}};
    List<PhotoRepository.Photo> photos = new ArrayList<>();
    for (int i = 0; i < SCROLL_BURST_SIZE; i++) {
      File file = new File(a.getCacheDir(), photoLabel(i));
      files.add(file);
      Bitmap bitmap = Bitmap.createBitmap(360, 480, Bitmap.Config.ARGB_8888);
      try (FileOutputStream out = new FileOutputStream(file)) {
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        String[] color = colors[i % colors.length];
        paint.setShader(new LinearGradient(0, 0, 360, 480, Color.parseColor(color[0]),
            Color.parseColor(color[1]), Shader.TileMode.CLAMP));
        canvas.drawPaint(paint);
        paint.setShader(null);
        paint.setColor(0xd9ede2cd);
        canvas.drawOval(82 + (i * 9) % 100, 205, 185 + (i * 9) % 100, 375, paint);
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out), "Synthetic JPEG");
      } finally {
        bitmap.recycle();
      }
      photos.add(new PhotoRepository.Photo(Uri.fromFile(file), photoLabel(i), "", file.length(), i, 0));
    }
    return photos;
  }
}
