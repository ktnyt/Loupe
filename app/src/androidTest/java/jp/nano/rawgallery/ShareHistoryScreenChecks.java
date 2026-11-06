package jp.nano.rawgallery;

import static jp.nano.rawgallery.RankingResultChecks.call;
import static jp.nano.rawgallery.RankingResultChecks.get;
import static jp.nano.rawgallery.RankingResultChecks.set;
import static jp.nano.rawgallery.TagDetailChecks.require;
import static jp.nano.rawgallery.TagDetailChecks.ui;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.net.Uri;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises the actual screen views with generated photos and a process-local history snapshot. */
final class ShareHistoryScreenChecks {
  private static final String LONG_LABEL =
      "削除済みの共有アプリに保存された長い表示名を、文字を大きくした情報画面でも最後まで確認するための合成ラベル";
  private static final long DATE = 1792298040000L;

  static String run(Instrumentation test, int width, float font) throws Exception {
    MainActivity activity = (MainActivity) test.startActivitySync(
        new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    File scratch = Files.createTempDirectory(activity.getCacheDir().toPath(), "share-history-ui-").toFile();
    File captures = new File(activity.getCacheDir(), "consistency-checks");
    require(captures.isDirectory() || captures.mkdirs(), "Capture directory");
    List<Bitmap> bitmaps = new ArrayList<>();
    String prefix = "share-history-" + width + "-" + font;
    try {
      GalleryScreenChecks.portraitFixture(test, activity, width, font);
      GalleryScreenChecks.finishInitialIndex(test, activity);
      freezeHistory(test, activity);
      List<PhotoRepository.Photo> photos = new ArrayList<>();
      List<String> keys = new ArrayList<>();
      for (int i = 0; i < 7; i++) {
        Bitmap bitmap = fixtureBitmap(i);
        bitmaps.add(bitmap);
        File file = new File(scratch, i + ".jpg");
        try (FileOutputStream out = new FileOutputStream(file)) {
          require(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out), "Generated JPEG");
        }
        Uri uri = i == 5 ? Uri.parse("archive://share-history-fixture/" + UUID.randomUUID()) : Uri.fromFile(file);
        PhotoRepository.Photo photo =
            new PhotoRepository.Photo(uri, "合成写真 " + i + ".jpg", scratch.toString(), file.length(), DATE + i, DATE + i);
        photos.add(photo);
        keys.add(ArchiveStore.key(photo));
      }
      List<ShareHistoryUi.Item> items = items(bitmaps);
      ShareHistoryUi.Item other = new ShareHistoryUi.Item(
          new ShareHistoryStore.Entry("fixture.member", "代表以外の共有アプリ", DATE),
          new ShareHistoryUi.App(null, false));
      Map<String, List<ShareHistoryUi.Item>> history = new LinkedHashMap<>();
      history.put(keys.get(1), items.subList(1, 2));
      history.put(keys.get(2), items.subList(1, 3));
      history.put(keys.get(3), items);
      history.put(keys.get(4), List.of(other));
      history.put(keys.get(5), items);
      List<Integer> burst = List.of(3, 4);
      List<Integer> cloudBurst = List.of(5, 6);
      ui(test, () -> {
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        set(activity, "photos", photos);
        set(activity, "bundles", List.of(List.of(0), List.of(1), List.of(2), burst, cloudBurst));
        set(activity, "selecting", false);
        set(activity, "rankFilter", 0);
        set(activity, "burstMembers", null);
        set(activity, "filter", "すべて");
        set(activity, "albumFolder", "/synthetic/共有履歴");
        set(activity, "shareHistory", history);
        set(activity, "historyLoaded", true);
        set(activity, "historyReadFailed", false);
        @SuppressWarnings("unchecked")
        Map<List<Integer>, PhotoRanking> rankings = (Map<List<Integer>, PhotoRanking>) get(activity, "rankCache");
        rankings.clear();
        rankings.put(burst, rank(keys, burst));
        rankings.put(cloudBurst, rank(keys, cloudBurst));
        @SuppressWarnings("unchecked")
        LruCache<String, Bitmap> thumbs = (LruCache<String, Bitmap>) get(activity, "thumbs");
        // Supply the generated cloud photo's preview without opening its archive URI.
        thumbs.put(PreviewPlaceholders.key(photos.get(5)) + ":400", bitmaps.get(5));
        call(activity, "gallery");
      });
      GalleryScreenChecks.syntheticCarousel(test, activity);
      checkGrid(test, activity, false);
      capture(test, activity, captures, prefix + "-grid", null, width, font);

      ui(test, () -> TagDetailChecks.button(root(activity), "選択").performClick());
      GalleryScreenChecks.settle(test, activity);
      tap(test, () -> cell(activity, 3), 0.86f, 0.82f);
      tap(test, () -> cell(activity, 5), 0.83f, 0.81f);
      checkGrid(test, activity, true);
      capture(test, activity, captures, prefix + "-selection", null, width, font);
      ui(test, activity::onBackPressed);
      GalleryScreenChecks.syntheticCarousel(test, activity);

      tap(test, () -> cell(activity, 3), 0.86f, 0.82f);
      GalleryScreenChecks.settle(test, activity);
      ui(test, () -> {
        require(burst.equals(get(activity, "burstMembers")), "Badge-area tap opens original burst");
        View hero = described(root(activity), "合成写真 3.jpg、タップで写真を拡大");
        require(description(hero).contains("共有したアプリ 7件") && !description(hero).contains("代表以外"),
            "Burst hero reads only active photo history");
        for (ShareHistoryUi.Overlay overlay : views(root(activity), ShareHistoryUi.Overlay.class)) decoration(overlay);
        require(views(root(activity), ShareHistoryUi.Overlay.class).size() >= 3, "Hero and two thumbnail badges");
      });
      capture(test, activity, captures, prefix + "-burst", null, width, font);
      tap(test, () -> described(root(activity), "合成写真 3.jpg、タップで写真を拡大"), 0.90f, 0.86f);
      awaitViewer(test, activity);
      ui(test, () -> {
        require((int) get(activity, "index") == 3, "Hero badge-area tap opens the same photo");
        PhotoViewerUi viewer = ViewerScreenChecks.viewer(activity);
        require(description(viewer.image).contains("共有したアプリ 7件")
            && description(viewer.image).contains("ほか5件"), "Viewer accessibility count");
        decoration(viewer.shared);
      });
      capture(test, activity, captures, prefix + "-viewer-many", null, width, font);
      ui(test, () -> TagDetailChecks.button(root(activity), "拡大").performClick());
      ui(test, () -> require(ViewerScreenChecks.viewer(activity).image.isZoomed(), "Viewer zoomed"));
      capture(test, activity, captures, prefix + "-viewer-zoom", null, width, font);
      ui(test, () -> TagDetailChecks.button(root(activity), "全体を表示").performClick());
      information(test, activity, captures, prefix + "-info-many", width, font, true);
      ui(test, () -> { activity.onBackPressed(); activity.onBackPressed(); });
      GalleryScreenChecks.syntheticCarousel(test, activity);

      for (int i = 0; i <= 2; i++) {
        int count = i;
        tap(test, () -> cell(activity, count), 0.84f, 0.82f);
        awaitViewer(test, activity);
        ui(test, () -> {
          require((int) get(activity, "index") == count, "Tap selects photo for history count " + count);
          String description = description(ViewerScreenChecks.viewer(activity).image);
          require(count == 0 ? !description.contains("共有したアプリ")
              : description.contains("共有したアプリ " + count + "件"), "Viewer history count " + count);
        });
        capture(test, activity, captures, prefix + "-viewer-" + count, null, width, font);
        if (i == 0) information(test, activity, captures, prefix + "-info-empty", width, font, false);
        ui(test, activity::onBackPressed);
        GalleryScreenChecks.syntheticCarousel(test, activity);
      }
      return "PASS share-history screens: generated photos, actual gallery/selection/burst/viewer/info, "
          + "0/1/2/7 apps, rank and cached cloud preview, badge-area taps, AX labels and decoration, "
          + "removed long label, zoom, hardware captures with geometry. No handoffs or history writes.\n";
    } finally {
      ui(test, activity::finish);
      test.waitForIdleSync();
      for (File file : scratch.listFiles()) file.delete();
      scratch.delete();
    }
  }

  private static void freezeHistory(Instrumentation test, MainActivity activity) throws Exception {
    ui(test, () -> activity.getSharedPreferences(ShareHistoryReceiver.PREFS, 0)
        .unregisterOnSharedPreferenceChangeListener(
            (SharedPreferences.OnSharedPreferenceChangeListener) get(activity, "historyChanges")));
    for (int i = 0; i < 8; i++) {
      ((ExecutorService) get(activity, "historyReads")).submit(() -> {}).get(15, TimeUnit.SECONDS);
      test.waitForIdleSync();
      boolean[] reading = {true};
      ui(test, () -> reading[0] = (boolean) get(activity, "historyReading"));
      if (!reading[0]) return;
    }
    throw new AssertionError("Startup history did not settle before fixture");
  }

  private static Bitmap fixtureBitmap(int i) {
    int[] sky = {0xff496a88, 0xff65547e, 0xff856c59, 0xff5e7c8b, 0xff7b5e77, 0xff656c90, 0xff627556};
    Bitmap bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setShader(new LinearGradient(0, 0, 540, 480, sky[i], 0xff161f28, Shader.TileMode.CLAMP));
    canvas.drawRect(0, 0, 640, 480, paint);
    paint.setShader(null);
    paint.setColor(0xffdac9a2);
    canvas.drawCircle(150 + 60 * i, 155 + 16 * i, 54, paint);
    return bitmap;
  }

  private static List<ShareHistoryUi.Item> items(List<Bitmap> bitmaps) {
    String[] labels = {LONG_LABEL, "合成メッセージ", "合成ノート", "合成アルバム",
        "合成ドライブ", "合成メール", "合成ファイル"};
    int[] colors = {0, 0xff2767bd, 0xff1e9f84, 0xffcc775a, 0xffaa73c9, 0xffb94e68, 0xff4a809d};
    List<ShareHistoryUi.Item> result = new ArrayList<>();
    for (int i = 0; i < labels.length; i++) {
      Bitmap icon = null;
      if (i > 0) {
        icon = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        icon.eraseColor(colors[i]);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xfff4f1ea);
        if (i % 2 == 0) new Canvas(icon).drawCircle(16, 16, 8, paint);
        else new Canvas(icon).drawRoundRect(8, 10, 24, 22, 4, 4, paint);
        bitmaps.add(icon);
      }
      result.add(new ShareHistoryUi.Item(new ShareHistoryStore.Entry("fixture.app" + i, labels[i], DATE - i * 60000L),
          new ShareHistoryUi.App(icon, i > 0)));
    }
    return result;
  }

  private static PhotoRanking rank(List<String> keys, List<Integer> members) {
    String winner = keys.get(members.get(0));
    PhotoRanking ranking = new PhotoRanking(List.of(winner, keys.get(members.get(1))), "A:");
    return ranking.choose(ranking.upper().equals(winner));
  }

  private static void checkGrid(Instrumentation test, MainActivity activity, boolean selected) throws Exception {
    ui(test, () -> {
      int[] counts = {0, 1, 2, 7, 7};
      int[] photoIndices = {0, 1, 2, 3, 5};
      for (int i = 0; i < counts.length; i++) {
        ThumbnailCell cell = cell(activity, photoIndices[i]);
        require(cell != null && cell.badges.shared.size() == counts[i], "Bound grid history count " + counts[i]);
        String description = description(cell);
        require(counts[i] == 0 ? !description.contains("共有したアプリ")
            : description.contains("共有したアプリ " + counts[i] + "件"), "Grid history AX count");
        require(!description.contains("代表以外の共有アプリ"), "Representative does not inherit other member history");
        decoration(cell.badges);
        if (i >= 3) require(cell.badges.selected == selected && cell.badges.rank.equals("1位"),
            "Rank preserved alongside share history");
        if (i == 4) require(cell.badges.cloud, "Cloud mark preserved alongside rank and share history");
      }
    });
  }

  private static void information(Instrumentation test, MainActivity activity, File directory,
      String name, int width, float font, boolean many) throws Exception {
    ui(test, () -> TagDetailChecks.button(root(activity), "情報").performClick());
    View[] dialog = new View[1];
    TextView[] expected = new TextView[1];
    for (int i = 0; i < 100 && expected[0] == null; i++) {
      ui(test, () -> {
        for (View window : WindowInspector.getGlobalWindowViews()) {
          for (TextView label : views(window, TextView.class)) {
            if ((many ? LONG_LABEL : "まだありません").contentEquals(label.getText()) && window.hasWindowFocus()) {
              expected[0] = label;
              dialog[0] = window;
              break;
            }
          }
        }
      });
      if (expected[0] == null) Thread.sleep(30);
    }
    require(expected[0] != null, "Information sheet shows expected history");
    ConsistencyLayoutChecks.frame(test, dialog[0]);
    if (many) ui(test, () -> {
      require(expected[0].getLineCount() > 1, "Long removed-app label wraps");
      List<TextView> labels = views(dialog[0], TextView.class);
      require(labels.stream().anyMatch(view -> view.getText().toString().contains("・使えないアプリ")),
          "Removed app explanation retained");
      require(labels.stream().filter(view -> view.getText().toString().startsWith("2026/")).count() >= 7,
          "Information sheet contains all seven dated destinations");
    });
    capture(test, activity, directory, name, dialog[0], width, font);
    test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
    test.waitForIdleSync();
  }

  private static void awaitViewer(Instrumentation test, MainActivity activity) throws Exception {
    test.waitForIdleSync();
    Future<?> viewing = (Future<?>) get(activity, "viewing");
    require(viewing != null, "Photo tap launched viewer");
    viewing.get(30, TimeUnit.SECONDS);
    test.waitForIdleSync();
    ConsistencyLayoutChecks.frame(test, root(activity));
    ui(test, () -> require((boolean) get(activity, "detail") && get(activity, "activePreview") != null,
        "Generated photo loaded"));
  }

  private static View root(MainActivity activity) { return (View) get(activity, "root"); }

  private static ThumbnailCell cell(MainActivity activity, int index) {
    return (ThumbnailCell) described(root(activity), "合成写真 " + index + ".jpg、", ThumbnailCell.class);
  }

  private static View described(View root, String prefix) { return described(root, prefix, View.class); }

  private static View described(View root, String prefix, Class<? extends View> type) {
    for (View view : views(root, type)) if (description(view).startsWith(prefix)) return view;
    return null;
  }

  private static String description(View view) {
    require(view != null, "View with photo description");
    return String.valueOf(view.getContentDescription());
  }

  private static <T extends View> List<T> views(View view, Class<T> type) {
    List<T> result = new ArrayList<>();
    append(view, type, result);
    return result;
  }

  private static <T extends View> void append(View view, Class<T> type, List<T> result) {
    if (type.isInstance(view)) result.add(type.cast(view));
    if (view instanceof ViewGroup) {
      ViewGroup parent = (ViewGroup) view;
      for (int i = 0; i < parent.getChildCount(); i++) append(parent.getChildAt(i), type, result);
    }
  }

  private static void decoration(View view) {
    require(!view.isClickable() && !view.isFocusable()
        && view.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_NO,
        "Badge adds no accessibility or touch target");
  }

  private static void tap(Instrumentation test, java.util.function.Supplier<View> find, float x, float y)
      throws Exception {
    int[] point = new int[2];
    ui(test, () -> {
      View view = find.get();
      require(view != null, "Photo pointer target");
      view.getLocationOnScreen(point);
      int localX = Math.round(view.getWidth() * x), localY = Math.round(view.getHeight() * y);
      Rect visible = new Rect();
      require(view.getLocalVisibleRect(visible) && visible.contains(localX, localY), "Tap inside visible photo");
      point[0] += localX;
      point[1] += localY;
    });
    long down = SystemClock.uptimeMillis();
    for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
      MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point[0], point[1], 0);
      try { test.sendPointerSync(event); }
      finally { event.recycle(); }
    }
    test.waitForIdleSync();
  }

  private static void capture(Instrumentation test, MainActivity activity, File directory,
      String name, View focusedWindow, int width, float font) throws Exception {
    View root = root(activity);
    ConsistencyLayoutChecks.frame(test, focusedWindow == null ? root : focusedWindow);
    int[][] bounds = new int[4][];
    Insets[] systemBars = new Insets[1];
    ui(test, () -> {
      View decor = activity.getWindow().getDecorView();
      bounds[0] = bounds(decor);
      bounds[1] = bounds(decor.findViewById(android.R.id.content));
      bounds[2] = bounds(root);
      bounds[3] = bounds(focusedWindow == null ? decor : focusedWindow);
      WindowInsets insets = decor.getRootWindowInsets();
      if (insets != null) systemBars[0] = insets.getInsets(WindowInsets.Type.systemBars());
      require(root.getWidth() == GalleryUi.dp(activity, width), "Rendered width equals requested fixture width");
      require(Math.abs(activity.getResources().getConfiguration().fontScale - font) < .001f, "Fixture font scale");
    });
    Bitmap screen = test.getUiAutomation().takeScreenshot();
    require(screen != null, "Hardware screenshot");
    Bitmap cropped = null;
    try {
      int[] decor = bounds[0];
      int left = Math.max(0, decor[0]), top = Math.max(0, decor[1]);
      int right = Math.min(screen.getWidth(), decor[0] + decor[2]);
      int bottom = Math.min(screen.getHeight(), decor[1] + decor[3]);
      cropped = Bitmap.createBitmap(screen, left, top, right - left, bottom - top);
      try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
        require(cropped.compress(Bitmap.CompressFormat.PNG, 100, out), "Screenshot output");
      }
      Insets bars = systemBars[0];
      JSONObject metadata = new JSONObject().put("decor", new JSONArray(bounds[0]))
          .put("content", new JSONArray(bounds[1])).put("root", new JSONArray(bounds[2]))
          .put("focusedWindow", new JSONArray(bounds[3]))
          .put("crop", new JSONArray(new int[] {left, top, right - left, bottom - top}))
          .put("display", new JSONArray(new int[] {0, 0, screen.getWidth(), screen.getHeight()}))
          .put("density", activity.getResources().getDisplayMetrics().density)
          .put("densityDpi", activity.getResources().getDisplayMetrics().densityDpi)
          .put("widthDp", width).put("fontScale", font)
          .put("systemBars", bars == null ? JSONObject.NULL : new JSONObject()
              .put("left", bars.left).put("top", bars.top).put("right", bars.right).put("bottom", bars.bottom));
      Files.write(new File(directory, name + ".json").toPath(), metadata.toString(2).getBytes(StandardCharsets.UTF_8));
    } finally {
      if (cropped != null && cropped != screen) cropped.recycle();
      screen.recycle();
    }
  }

  private static int[] bounds(View view) {
    int[] at = new int[2];
    view.getLocationOnScreen(at);
    return new int[] {at[0], at[1], view.getWidth(), view.getHeight()};
  }
}
