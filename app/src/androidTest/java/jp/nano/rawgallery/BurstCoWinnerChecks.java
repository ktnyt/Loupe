package jp.nano.rawgallery;

import static jp.nano.rawgallery.RankingResultChecks.call;
import static jp.nano.rawgallery.RankingResultChecks.get;
import static jp.nano.rawgallery.RankingResultChecks.set;
import static jp.nano.rawgallery.TagDetailChecks.ui;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the burst detail and confirmation sheet using generated photos and isolated rankings. */
final class BurstCoWinnerChecks {
  private static final String ENTRY = "この写真を1位に";
  private static final String ADD = "同率1位に追加。いまの1位はそのまま";
  private static final String SOLE = "この写真だけを1位にする。ほかは同率2位";
  private static final String CANCEL = "キャンセル";
  private static final String RETRY = "再試行、同率1位に追加";
  private static final String ERROR = "保存できませんでした。順位は前のままです";
  private static final String SAVED = "同率1位に追加しました";

  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  private static View find(View view, String label) {
    if (view.isShown() && ((view.getContentDescription() != null
            && view.getContentDescription().toString().contains(label))
        || (view instanceof TextView && ((TextView) view).getText().toString().contains(label)))) {
      return view;
    }
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View match = find(group.getChildAt(i), label);
        if (match != null) return match;
      }
    }
    return null;
  }

  private static View windowView(String label) {
    for (View root : WindowInspector.getGlobalWindowViews()) {
      if (!root.hasWindowFocus()) continue;
      View match = find(root, label);
      if (match != null) return match;
    }
    return null;
  }

  private static void await(Instrumentation t, String message, DiagnosticChecks.Check ready)
      throws InterruptedException {
    long deadline = SystemClock.uptimeMillis() + 6000;
    boolean[] found = {false};
    do {
      ui(t, () -> found[0] = ready.done());
      if (found[0]) return;
      Thread.sleep(25);
    } while (SystemClock.uptimeMillis() < deadline);
    throw new AssertionError("Timed out: " + message);
  }

  private static void visible(Instrumentation t, String label) throws InterruptedException {
    await(t, label, () -> windowView(label) != null);
  }

  private static void click(Instrumentation t, String label) throws InterruptedException {
    await(t, label, () -> {
      View view = windowView(label);
      return view != null && view.isClickable() && view.isEnabled();
    });
    ui(t, () -> check(windowView(label).performAccessibilityAction(
        AccessibilityNodeInfo.ACTION_CLICK, null), "AX click " + label));
  }

  private static void back(Instrumentation t) {
    t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
    t.waitForIdleSync();
  }

  private static void tapOutside(Instrumentation t, MainActivity a) {
    int[] point = new int[2];
    ui(t, () -> {
      View activity = a.getWindow().getDecorView();
      View sheet = windowView(ADD).getRootView();
      int[] activityOrigin = new int[2];
      int[] sheetOrigin = new int[2];
      activity.getLocationOnScreen(activityOrigin);
      sheet.getLocationOnScreen(sheetOrigin);
      int left = Math.max(activityOrigin[0], sheetOrigin[0]);
      int right = Math.min(activityOrigin[0] + activity.getWidth(), sheetOrigin[0] + sheet.getWidth());
      int bottom = Math.min(sheetOrigin[1], activityOrigin[1] + activity.getHeight());
      check(right > left && bottom > activityOrigin[1], "No exposed activity above sheet");
      point[0] = (left + right) / 2;
      point[1] = (activityOrigin[1] + bottom) / 2;
    });
    long down = SystemClock.uptimeMillis();
    for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
      MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(),
          action, point[0], point[1], 0);
      event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
      try {
        check(t.getUiAutomation().injectInputEvent(event, true), "Outside touch " + action);
      } finally {
        event.recycle();
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static void assertBestShotSelection(MainActivity a, List<PhotoRepository.Photo> photos) {
    Set<String> selected = (Set<String>) get(a, "selectedPhotos");
    Set<String> previous = Set.copyOf(selected);
    Object previousMode = get(a, "bestOnly");
    try {
      selected.clear();
      for (PhotoRepository.Photo photo : photos) selected.add(photo.uri.toString());
      set(a, "bestOnly", true);
      Method resolve = MainActivity.class.getDeclaredMethod("resolveSelection");
      resolve.setAccessible(true);
      Object result = resolve.invoke(a);
      Field matches = result.getClass().getDeclaredField("photos");
      matches.setAccessible(true);
      check(matches.get(result).equals(List.of(photos.get(0), photos.get(2))),
          "Best-shot selection did not resolve both co-winners");
    } catch (ReflectiveOperationException error) {
      throw new AssertionError("Best-shot selection unavailable", error);
    } finally {
      selected.clear();
      selected.addAll(previous);
      set(a, "bestOnly", previousMode);
    }
  }

  private static void inspectButton(MainActivity a, String label, int height) {
    View view = windowView(label);
    check(view instanceof Button, "Missing button " + label);
    Button button = (Button) view;
    check(button.getHeight() >= GalleryUi.dp(a, height), "Small button " + label);
    check(button.getLayout() != null, "Missing button layout " + label);
    check(button.getLayout().getHeight() + button.getCompoundPaddingTop()
        + button.getCompoundPaddingBottom() <= button.getHeight(), "Clipped height " + label);
    for (int line = 0; line < button.getLayout().getLineCount(); line++) {
      check(button.getLayout().getEllipsisCount(line) == 0, "Clipped line " + label);
    }
  }

  private static void capture(Instrumentation t, MainActivity a, String name, String label)
      throws Exception {
    View[] focused = new View[1];
    int[] bounds = new int[4];
    ui(t, () -> {
      focused[0] = windowView(label);
      View decor = a.getWindow().getDecorView();
      decor.getLocationOnScreen(bounds);
      bounds[2] = decor.getWidth();
      bounds[3] = decor.getHeight();
    });
    check(focused[0] != null, "Capture screen " + label);
    ConsistencyLayoutChecks.frame(t, focused[0]);
    File dir = new File(a.getCacheDir(), "consistency-checks");
    check(dir.isDirectory() || dir.mkdirs(), "Screenshot directory");
    Bitmap screen = t.getUiAutomation().takeScreenshot();
    check(screen != null, "Screenshot unavailable");
    Bitmap crop = null;
    try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
      int left = Math.max(0, bounds[0]);
      int top = Math.max(0, bounds[1]);
      int right = Math.min(screen.getWidth(), bounds[0] + bounds[2]);
      int bottom = Math.min(screen.getHeight(), bounds[1] + bounds[3]);
      crop = Bitmap.createBitmap(screen, left, top, right - left, bottom - top);
      check(crop.compress(Bitmap.CompressFormat.PNG, 100, out), "Screenshot write");
    } finally {
      if (crop != null && crop != screen) crop.recycle();
      screen.recycle();
    }
  }

  private static void seed(MainActivity a, List<PhotoRepository.Photo> photos, RankingStore store) {
    set(a, "rankingStore", store);
    set(a, "photos", photos);
    List<Integer> members = List.of(0, 1, 2, 3, 4);
    set(a, "bundles", List.of(members));
    set(a, "burstMembers", members);
    set(a, "burstPosition", 0);
    ((Map<?, ?>) get(a, "rankCache")).clear();
    call(a, "gallery");
  }

  private static PhotoRanking sole(List<String> keys, int index) {
    List<String> sorted = new ArrayList<>(keys);
    Collections.sort(sorted);
    PhotoRanking ranking = new PhotoRanking(sorted, "B:");
    while (!ranking.done()) ranking = ranking.choose(ranking.upper().equals(keys.get(index)));
    return ranking;
  }

  private static void assertWinners(RankingStore store, List<String> keys, int... winners)
      throws Exception {
    PhotoRanking result = store.load(keys);
    check(result.done(), "Saved ranking incomplete");
    List<String> expected = new ArrayList<>();
    for (int winner : winners) expected.add(keys.get(winner));
    check(Set.copyOf(result.best()).equals(Set.copyOf(expected)), "Saved winners");
    for (String key : keys) {
      check(result.rankOf(key) == (expected.contains(key) ? 1 : winners.length + 1), "Saved rank");
    }
  }

  private static SharedPreferences failFirstCommit(SharedPreferences prefs,
      CountDownLatch release, AtomicInteger commits) {
    // SharedPreferences can publish a commit to process memory before its disk write fails.
    Map<String, String> memory = new ConcurrentHashMap<>();
    return (SharedPreferences) Proxy.newProxyInstance(
        BurstCoWinnerChecks.class.getClassLoader(), new Class<?>[] {SharedPreferences.class},
        (proxy, method, args) -> {
          if (method.getName().equals("getString") && memory.containsKey(args[0])) {
            return memory.get(args[0]);
          }
          if (!method.getName().equals("edit")) return method.invoke(prefs, args);
          SharedPreferences.Editor editor = prefs.edit();
          Map<String, String> pending = new HashMap<>();
          return Proxy.newProxyInstance(BurstCoWinnerChecks.class.getClassLoader(),
              new Class<?>[] {SharedPreferences.Editor.class}, (wrapper, edit, values) -> {
                if (edit.getName().equals("putString")) {
                  pending.put((String) values[0], (String) values[1]);
                }
                if (edit.getName().equals("commit")) {
                  memory.putAll(pending);
                  if (commits.incrementAndGet() == 1) {
                    check(release.await(10, TimeUnit.SECONDS), "Release first commit");
                    return false;
                  }
                }
                Object result = edit.invoke(editor, values);
                return result == editor ? wrapper : result;
              });
        });
  }

  static String run(Instrumentation t, int width, float font) throws Exception {
    MainActivity a = (MainActivity) t.startActivitySync(
        new Intent(t.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    SharedPreferences prefs = a.getSharedPreferences("burst-co-winners-test", 0);
    List<File> files = new ArrayList<>();
    CountDownLatch release = new CountDownLatch(1);
    try {
      ((ExecutorService) get(a, "scans")).submit(() -> {}).get(10, TimeUnit.SECONDS);
      check(prefs.edit().clear().commit(), "Reset fixture rankings");
      RankingStore disk = new RankingStore(prefs);
      List<PhotoRepository.Photo> photos = new ArrayList<>();
      List<String> keys = new ArrayList<>();
      int[] colors = {0xff587c8c, 0xffa17862, 0xff688664, 0xff8c647f, 0xff986e52};
      for (int i = 0; i < colors.length; i++) {
        File file = new File(a.getCacheDir(), "co-winner-fixture-" + i + ".jpg");
        files.add(file);
        Bitmap bitmap = Bitmap.createBitmap(720, 960, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(colors[i]);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xffededea);
        paint.setTextSize(180);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(Integer.toString(i + 1), 360, 530, paint);
        try (FileOutputStream out = new FileOutputStream(file)) {
          check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out), "Fixture JPEG");
        } finally {
          bitmap.recycle();
        }
        PhotoRepository.Photo photo = new PhotoRepository.Photo(Uri.fromFile(file),
            "co-winner-" + (i + 1) + ".jpg", "", file.length(), i, 0);
        photos.add(photo);
        keys.add(ArchiveStore.key(photo));
      }

      // The normal unfinished-burst action can still designate a single first photo.
      ui(t, () -> seed(a, photos, disk));
      click(t, ENTRY);
      visible(t, "この写真を1位にしますか？");
      ui(t, () -> check(windowView(ADD) == null, "Unfinished burst offers co-add"));
      click(t, CANCEL);
      visible(t, ENTRY);
      check(!disk.load(keys).done(), "Cancel completed an unfinished burst");

      check(disk.save(sole(keys, 2)), "Seed sole winner");
      AtomicInteger commits = new AtomicInteger();
      SharedPreferences failurePrefs = failFirstCommit(prefs, release, commits);
      RankingStore store = new RankingStore(failurePrefs);
      ui(t, () -> seed(a, photos, store));
      await(t, "fixture photo painted", () -> {
        View hero = windowView("co-winner-1.jpg、タップで写真を拡大");
        return hero instanceof ImageView && ((ImageView) hero).getDrawable() != null;
      });
      visible(t, "5枚目、2位、表示済み");
      String prefix = "burst-co-winners-" + width + "-" + font;
      click(t, ENTRY);
      visible(t, ADD);
      ui(t, () -> {
        if (width > 0) {
          check(a.getWindow().getDecorView().getWidth() == GalleryUi.dp(a, width), "Activity width");
          check(Math.abs(a.getResources().getConfiguration().fontScale - font) < .001f, "Font scale");
          check(windowView(ADD).getRootView().getWidth() == GalleryUi.dp(a, width), "Sheet width");
        }
        inspectButton(a, ADD, 56);
        inspectButton(a, SOLE, 56);
        inspectButton(a, CANCEL, 48);
      });
      capture(t, a, prefix + "-sheet", ADD);
      click(t, CANCEL);
      visible(t, ENTRY);
      assertWinners(disk, keys, 2);

      click(t, ENTRY);
      visible(t, ADD);
      back(t);
      visible(t, ENTRY);
      assertWinners(disk, keys, 2);

      click(t, ENTRY);
      visible(t, ADD);
      tapOutside(t, a);
      visible(t, ENTRY);
      assertWinners(disk, keys, 2);
      check(commits.get() == 0, "Dismissal attempted save");

      click(t, ENTRY);
      click(t, ADD);
      visible(t, "保存しています");
      ui(t, () -> {
        check(!windowView("保存しています").isEnabled(), "Saving action still enabled");
        check(!windowView(SOLE).isEnabled(), "Replacement still enabled while saving");
        check(!windowView(CANCEL).isEnabled(), "Cancel still enabled while saving");
      });
      back(t);
      visible(t, "保存しています");
      await(t, "first commit entered", () -> commits.get() == 1);
      assertWinners(store, keys, 0, 2);
      assertWinners(disk, keys, 2);
      release.countDown();
      visible(t, ERROR);
      check(commits.get() == 2, "Missing restore commit");
      assertWinners(new RankingStore(failurePrefs), keys, 2);
      assertWinners(disk, keys, 2);
      click(t, RETRY);
      visible(t, SAVED);
      check(commits.get() == 3, "Unexpected save count");
      assertWinners(new RankingStore(prefs), keys, 0, 2);
      ui(t, () -> assertBestShotSelection(a, photos));
      check((int) get(a, "burstPosition") == 0, "Co-add moved the photo");
      visible(t, "1・3枚目");
      visible(t, "同率1位、順位決定済み");
      visible(t, "1枚目、同率1位、表示済み");
      visible(t, "3枚目、同率1位、表示済み");
      visible(t, "5枚目、3位、表示済み");
      capture(t, a, prefix + "-success", SAVED);

      ui(t, () -> seed(a, photos, new RankingStore(prefs)));
      visible(t, "1・3枚目");
      click(t, ENTRY);
      visible(t, SOLE);
      ui(t, () -> check(windowView(ADD) == null, "Already first offers co-add"));
      click(t, CANCEL);

      click(t, "次の写真");
      click(t, ENTRY);
      click(t, ADD);
      visible(t, "1・2・3枚目");
      assertWinners(disk, keys, 0, 1, 2);
      check((int) get(a, "burstPosition") == 1, "Second co-add moved the photo");
      click(t, ENTRY);
      click(t, SOLE);
      visible(t, "1位にしました");
      assertWinners(disk, keys, 1);
      check((int) get(a, "burstPosition") == 1, "Replacement moved the photo");
      return "PASS isolated burst UI: unfinished, cancel, Back, outside tap, busy and Back, failed save, "
          + "restore of cached and stored ranks, retry, two co-adds, reopen, all winner labels, "
          + "best-shot selection, single replacement, and photo position. "
          + "AX ACTION_CLICK and button bounds checked; spoken TalkBack and finger taps untested.\n";
    } finally {
      release.countDown();
      ui(t, a::finish);
      prefs.edit().clear().commit();
      for (File file : files) file.delete();
    }
  }
}
