package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Actual chooser/direct handoff to the local test APK, with generated JPEGs only. */
final class ShareHistoryHandoffChecks {
  private static final String LABEL = "Loupe 検証用共有先";

  static String run(Instrumentation t) throws Exception {
    Context context = t.getTargetContext();
    MainActivity a =
        (MainActivity)
            t.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    List<File> copies = Collections.synchronizedList(new ArrayList<>());
    List<PhotoRepository.Photo> photos = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    List<File> fixtures = new ArrayList<>();
    Map<PhotoRepository.Photo, File> originals = new IdentityHashMap<>();
    String folder = "/synthetic/" + UUID.randomUUID();
    try {
      for (int i = 0; i < 5; i++) {
        File fixture = File.createTempFile("handoff-", ".jpg", a.getCacheDir());
        fixtures.add(fixture);
        Bitmap bitmap =
            Bitmap.createBitmap(
                ShareHistoryTargetActivity.FIRST_WIDTH + i,
                ShareHistoryTargetActivity.HEIGHT,
                Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(fixture)) {
          bitmap.eraseColor(0xff718774);
          check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out), "synthetic JPEG created");
        } finally {
          bitmap.recycle();
        }
        PhotoRepository.Photo photo =
            new PhotoRepository.Photo(
                Uri.fromFile(fixture), "synthetic-" + i + ".jpg", folder, fixture.length(), i, 0);
        photos.add(photo);
        originals.put(photo, fixture);
        keys.add(ArchiveStore.key(photo));
      }
      GalleryScreenChecks.finishInitialIndex(t, a);
      TagDetailChecks.ui(
          t,
          () -> {
            a.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            RankingResultChecks.set(
                a,
                "sharePreparation",
                new SharePreparation(
                    new File(a.getCacheDir(), "shares"),
                    (photo, raw, destination) -> {
                      File fixture = originals.get(photo);
                      check(fixture != null && !raw, "only generated JPEGs enter handoff fixture");
                      Files.copy(fixture.toPath(), destination.toPath());
                      copies.add(destination);
                    }));
          });
      String targetPackage = t.getContext().getPackageName();
      ComponentName component =
          new ComponentName(targetPackage, ShareHistoryTargetActivity.class.getName());
      try (ShareHistoryStore db = new ShareHistoryStore(context)) {
        for (String key : keys) check(db.history(key).isEmpty(), "fixture keys unique");
      }
      export(
          t,
          a,
          photos.subList(0, 2),
          new ShareTargets.Target(component.flattenToString(), LABEL, false));
      awaitText(t, "Loupe test receipt 2 OK", 15000);
      awaitHistory(context, keys.subList(0, 2), targetPackage, 1, 0);
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
      t.waitForIdleSync();
      // A cancelled system chooser must not create history for its distinct third photo.
      export(t, a, List.of(photos.get(2)), null);
      awaitChooser(t, 15000);
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
      t.waitForIdleSync();
      Thread.sleep(250);
      try (ShareHistoryStore db = new ShareHistoryStore(context)) {
        check(db.history(keys.get(2)).isEmpty(), "cancel adds no history");
      }
      // Repeat the same two photos through the actual OS picker; package entries remain unique.
      long chooserStarted = System.currentTimeMillis();
      export(t, a, photos.subList(0, 2), null);
      awaitChooser(t, 15000);
      captureChooser(t, a);
      choose(t, LABEL);
      awaitText(t, "Loupe test receipt 2 OK", 15000);
      awaitHistory(context, keys.subList(0, 2), targetPackage, 1, chooserStarted);
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
      t.waitForIdleSync();
      try (ShareHistoryStore reopened = new ShareHistoryStore(context)) {
        for (String key : keys.subList(0, 2))
          check(reopened.history(key).size() == 1, "reopen retains history");
        check(reopened.history(keys.get(2)).isEmpty(), "unshared photo isolated");
      }
      export(
          t,
          a,
          List.of(photos.get(3)),
          new ShareTargets.Target(component.flattenToString(), LABEL, false));
      awaitText(t, "Loupe test receipt 1 OK", 15000);
      awaitHistory(context, List.of(keys.get(3)), targetPackage, 1, 0);
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
      t.waitForIdleSync();
      checkMissingTarget(t, a, component, photos.get(4), keys.get(4), fixtures.get(4), copies);
      return "PASS share-history-handoff: actual single and multiple direct launch and Android chooser to local test APK; ordered generated JPEG stream and clip, effective read grants and no write grant; cancelled chooser no history; package dedup and DB reopen; missing target blocked with no session or callback residue and settings recovery. No external posting or real photographs.\n";
    } finally {
      TagDetailChecks.ui(t, () -> a.finish());
      try (ShareHistoryStore db = new ShareHistoryStore(context)) {
        android.database.sqlite.SQLiteDatabase sql = db.getWritableDatabase();
        for (String key : keys) {
          sql.delete("history", "photo=?", new String[] {key});
          sql.delete(
              "sessions",
              "id IN (SELECT session FROM session_photos WHERE photo=?)",
              new String[] {key});
          sql.delete("session_photos", "photo=?", new String[] {key});
        }
      }
      for (File fixture : fixtures) fixture.delete();
      for (File copy : copies) copy.delete();
    }
  }

  private static void export(
      Instrumentation t,
      MainActivity a,
      List<PhotoRepository.Photo> photos,
      ShareTargets.Target target)
      throws Exception {
    Method method =
        MainActivity.class.getDeclaredMethod(
            "export", List.class, boolean.class, ShareTargets.Target.class);
    invokeExport(t, a, method, List.copyOf(photos), false, target);
  }

  private static void invokeExport(
      Instrumentation t, MainActivity a, Method method, Object... args) {
    method.setAccessible(true);
    TagDetailChecks.ui(
        t,
        () -> {
          try {
            method.invoke(a, args);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  private static void checkMissingTarget(
      Instrumentation test,
      MainActivity activity,
      ComponentName registered,
      PhotoRepository.Photo photo,
      String key,
      File fixture,
      List<File> copies)
      throws Exception {
    ComponentName missing =
        new ComponentName(registered.getPackageName(), registered.getClassName() + "Missing");
    ShareTargets.Target stored = new ShareTargets.Target(missing.flattenToString(), LABEL, false);
    AtomicReference<String> session = new AtomicReference<>();
    AtomicInteger confirmations = new AtomicInteger(), launches = new AtomicInteger();
    BooleanSupplier confirm =
        () -> {
          if (confirmations.incrementAndGet() == 2) session.set(stagedShareSession(activity, key));
          return true;
        };
    Instrumentation.ActivityMonitor monitor =
        new Instrumentation.ActivityMonitor() {
          @Override
          public Instrumentation.ActivityResult onStartActivity(Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CHOOSER.equals(action)
                || Intent.ACTION_SEND.equals(action)
                || Intent.ACTION_SEND_MULTIPLE.equals(action)
                || registered.equals(intent.getComponent())
                || missing.equals(intent.getComponent())) {
              launches.incrementAndGet();
              return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
            }
            return null;
          }
        };
    test.addMonitor(monitor);
    try (AutoCloseable remove = () -> test.removeMonitor(monitor);
        AutoCloseable cleanup =
            () -> {
              String id = session.get();
              if (id == null) return;
              PendingIntent pending = existingShareCallback(activity, id);
              if (pending != null) pending.cancel();
              // Only the freshly captured session is eligible for recovery cleanup.
              if (pending != null || stagedShareSession(activity, key) != null) {
                ShareHistoryReceiver.cancelLater(activity, id);
                drainShareHistory();
              }
            }) {
      check(
          stagedShareSession(activity, key) == null, "no prior session for missing target fixture");
      int before = copies.size();
      Method method =
          MainActivity.class.getDeclaredMethod(
              "export",
              List.class,
              boolean.class,
              ShareTargets.Target.class,
              BooleanSupplier.class);
      invokeExport(test, activity, method, List.of(photo), false, stored, confirm);
      ((ExecutorService) RankingResultChecks.get(activity, "actions"))
          .submit(() -> {})
          .get(5, TimeUnit.SECONDS);
      test.waitForIdleSync();
      check(copies.size() == before + 1, "missing target passed real JPEG preparation");
      File copy = copies.get(before);
      check(
          copy.isFile()
              && copy.length() == fixture.length()
              && Arrays.equals(
                  Files.readAllBytes(copy.toPath()), Files.readAllBytes(fixture.toPath())),
          "missing target prepared the exact generated JPEG");
      String staged = session.get();
      check(
          staged != null && confirmations.get() == 2, "missing target staged its history session");
      drainShareHistory();
      check(launches.get() == 0, "missing target launched no recipient or chooser");
      try (ShareHistoryStore history = new ShareHistoryStore(activity);
          Cursor rows =
              history
                  .getReadableDatabase()
                  .query(
                      "sessions",
                      new String[] {"id"},
                      "id=?",
                      new String[] {staged},
                      null,
                      null,
                      null)) {
        check(
            !rows.moveToFirst()
                && stagedShareSession(activity, key) == null
                && history.history(key).isEmpty(),
            "missing target left no durable session or history");
      }
      check(
          existingShareCallback(activity, staged) == null,
          "missing target left no chooser callback");
      checkNoPendingSession(staged);

      Field symbol = StatusView.class.getDeclaredField("symbol");
      symbol.setAccessible(true);
      TagDetailChecks.ui(
          test,
          () -> {
            StatusView status =
                activity.findViewById(android.R.id.content).findViewWithTag("status-feedback");
            try {
              check(
                  status != null
                      && status.isShown()
                      && status.isClickable()
                      && symbol.get(status) == StatusView.Symbol.FORMAT
                      && status.getContentDescription() != null
                      && status.getContentDescription().toString().endsWith("共有先の設定"),
                  "missing target shows FORMAT recovery");
            } catch (IllegalAccessException error) {
              throw new AssertionError("FORMAT witness unavailable");
            }
            check(status.performClick(), "missing target recovery opens settings");
          });
      awaitText(test, "共有先と並び順", 15000);
      test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
      test.waitForIdleSync();
      check(launches.get() == 0, "recovery did not dispatch a share");
    }
  }

  private static String stagedShareSession(Context context, String key) {
    try (ShareHistoryStore store = new ShareHistoryStore(context);
        Cursor rows =
            store
                .getReadableDatabase()
                .query(
                    "session_photos",
                    new String[] {"session"},
                    "photo=?",
                    new String[] {key},
                    null,
                    null,
                    null)) {
      if (!rows.moveToFirst()) return null;
      String id = rows.getString(0);
      check(!rows.moveToNext(), "only one session for the missing target fixture");
      return id;
    }
  }

  private static PendingIntent existingShareCallback(Context context, String session) {
    Intent callback =
        new Intent(context, ShareHistoryReceiver.class)
            .setAction(ShareHistoryReceiver.ACTION)
            .setData(
                new Uri.Builder()
                    .scheme("loupe-share")
                    .authority("chosen")
                    .appendPath(session)
                    .build());
    int flags = PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_ONE_SHOT;
    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
    return PendingIntent.getBroadcast(context, 0, callback, flags);
  }

  private static void drainShareHistory() throws Exception {
    Field writes = ShareHistoryReceiver.class.getDeclaredField("WRITES");
    writes.setAccessible(true);
    ((ExecutorService) writes.get(null)).submit(() -> {}).get(5, TimeUnit.SECONDS);
  }

  private static void checkNoPendingSession(String session) throws Exception {
    Field lock = ShareHistoryReceiver.class.getDeclaredField("SESSION_LOCK");
    lock.setAccessible(true);
    synchronized (lock.get(null)) {
      for (String name : List.of("RECENT", "RETRIES")) {
        Field sessions = ShareHistoryReceiver.class.getDeclaredField(name);
        sessions.setAccessible(true);
        check(
            !((Map<?, ?>) sessions.get(null)).containsKey(session),
            "missing target left no in-memory session");
      }
    }
  }

  private static void captureChooser(Instrumentation test, MainActivity activity) throws Exception {
    File dir = new File(activity.getCacheDir(), "consistency-checks");
    check(dir.isDirectory() || dir.mkdirs(), "synthetic chooser capture directory available");
    Bitmap screenshot = test.getUiAutomation().takeScreenshot();
    check(screenshot != null, "focused synthetic chooser screenshot available");
    try (FileOutputStream out = new FileOutputStream(new File(dir, "share-handoff-chooser.png"))) {
      check(
          screenshot.compress(Bitmap.CompressFormat.PNG, 100, out),
          "synthetic chooser screenshot saved");
    } finally {
      screenshot.recycle();
    }
  }

  private static void awaitHistory(
      Context c, List<String> keys, String pkg, int expected, long minimumTime) throws Exception {
    long end = SystemClock.uptimeMillis() + 8000;
    do {
      boolean good = true;
      try (ShareHistoryStore db = new ShareHistoryStore(c)) {
        for (String key : keys) {
          List<ShareHistoryStore.Entry> entries = db.history(key);
          good &=
              entries.size() == expected
                  && entries.get(0).packageName.equals(pkg)
                  && entries.get(0).handedOffAt >= minimumTime;
        }
      }
      if (good) return;
      Thread.sleep(40);
    } while (SystemClock.uptimeMillis() < end);
    throw new AssertionError("handoff history not saved");
  }

  private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, String text) {
    if (node == null) return null;
    if (android.text.TextUtils.equals(text, node.getText())
        || android.text.TextUtils.equals(text, node.getContentDescription())) return node;
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfo found = find(node.getChild(i), text);
      if (found != null) return found;
    }
    return null;
  }

  private static void awaitText(Instrumentation t, String text, long timeout) throws Exception {
    long end = SystemClock.uptimeMillis() + timeout;
    do {
      if (find(t.getUiAutomation().getRootInActiveWindow(), text) != null) return;
      Thread.sleep(60);
    } while (SystemClock.uptimeMillis() < end);
    throw new AssertionError("test recipient receipt missing");
  }

  private static void awaitChooser(Instrumentation t, long timeout) throws Exception {
    long end = SystemClock.uptimeMillis() + timeout;
    do {
      AccessibilityNodeInfo root = t.getUiAutomation().getRootInActiveWindow();
      if (root != null
          && root.getPackageName() != null
          && !root.getPackageName().toString().startsWith("jp.nano.rawgallery")) return;
      Thread.sleep(60);
    } while (SystemClock.uptimeMillis() < end);
    throw new AssertionError("OS chooser not visible");
  }

  private static boolean scroll(AccessibilityNodeInfo node) {
    if (node == null) return false;
    for (int i = 0; i < node.getChildCount(); i++) if (scroll(node.getChild(i))) return true;
    return node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
  }

  private static void choose(Instrumentation t, String label) throws Exception {
    long deadline = SystemClock.uptimeMillis() + 15000;
    do {
      AccessibilityNodeInfo root = t.getUiAutomation().getRootInActiveWindow();
      AccessibilityNodeInfo target = find(root, label);
      if (target == null) {
        // The chooser window can appear before its targets or scroll controls are ready.
        scroll(root);
      } else {
        while (target != null && !target.isClickable()) target = target.getParent();
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
      }
      Thread.sleep(150);
    } while (SystemClock.uptimeMillis() < deadline);
    throw new AssertionError(
        "test-only share target not found or clickable; no other target selected");
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
