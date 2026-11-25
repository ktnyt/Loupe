package jp.nano.rawgallery;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Run seed on the old APK and verify on its replacement using a disposable test installation. */
final class AmazonRetirementUpgradeChecks {
  private static final String FIXTURE = "amazon-retirement";
  private static final byte[] ORIGINAL = {9, 4, 2, 1};

  private static final String BUCKET = "loupe-amazon-retirement-fixture";

  static void requireDisposable(Context context, boolean acknowledged) {
    require(
        acknowledged
            && "ranchu".equals(Build.HARDWARE)
            && "jp.nano.rawgallery".equals(context.getPackageName())
            && (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        "requires a dedicated debug emulator and -e disposable-avd true");
  }

  static String seed(Instrumentation test, boolean existingConsent) throws Exception {
    Context context = test.getTargetContext();
    var scheduler = context.getSystemService(JobScheduler.class);
    var oldJob = component(context, "SyncJob");
    context.getPackageManager().getServiceInfo(oldJob, 0);
    require(
        context.getSystemService(ConnectivityManager.class).getActiveNetwork() == null,
        "fixture requires an offline AVD");
    require(E2Settings.hasMediaAccess(context), "grant the fixture media permissions first");
    for (int id : new int[] {71, 72, 77, 78, 79, 80}) {
      require(scheduler.getPendingJob(id) == null, "existing job " + id);
    }
    var e2State = E2AutoSync.state(context);
    var connection = context.getSharedPreferences("e2_connection", Context.MODE_PRIVATE);
    var settings = SettingsRepository.open(context);
    var witness = context.getSharedPreferences(FIXTURE, Context.MODE_PRIVATE);
    require(
        witness.getAll().isEmpty() && e2State.getAll().isEmpty() && connection.getAll().isEmpty(),
        "existing upgrade, e2 options or connection");
    require(
        !settings.contains("syncEnabled")
            && !settings.contains("cleanupEnabled")
            && !settings.contains("retentionDays")
            && !settings.contains("cloudUserAgent"),
        "existing Amazon settings");
    require(emptyMedia(context), "existing MediaStore photos or videos");
    require(
        emptyReceipts(context, "archive.db") && emptyReceipts(context, E2Settings.DATABASE),
        "existing archive records");
    require(
        emptyDirectory(new File(context.getFilesDir(), "archive"))
            && emptyDirectory(new File(context.getFilesDir(), "e2-previews"))
            && emptyDirectory(new File(context.getCacheDir(), "originals")),
        "existing archive previews or cache files");
    require(witness.edit().putString("phase", "seeding").commit(), "fixture marker");

    int oldTask = -1;
    if (existingConsent) {
      var old =
          test.startActivitySync(
              new Intent()
                  .setComponent(component(context, "CloudActivity"))
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      oldTask = old.getTaskId();
      test.runOnMainSync(
          () -> require(old.moveTaskToBack(true), "old activity task was not backgrounded"));
      var recovering = E2SyncJob.class.getDeclaredField("OPEN_RECOVERY_QUEUED");
      recovering.setAccessible(true);
      var queued = (java.util.concurrent.atomic.AtomicBoolean) recovering.get(null);
      long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
      while (queued.get() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
      require(!queued.get(), "old recover-on-open did not settle");
    }
    storeFixture(context, false);
    storeFixture(context, true);
    storeE2Fixture(context, false);
    storeE2Fixture(context, true);
    String key = key(false);
    File cache = original(context, key);
    require(cache.getParentFile().isDirectory() || cache.getParentFile().mkdirs(), "cache folder");
    Files.write(cache.toPath(), ORIGINAL);
    if (existingConsent) {
      require(connection.edit().putString("bucket", BUCKET).commit(), "synthetic destination");
      require(
          e2State
              .edit()
              .putBoolean("enabled", true)
              .putString("destination", BUCKET)
              .putInt("fullMediaConsent", 2)
              .putString("cleanupBucket", BUCKET)
              .putInt("cleanupDays", 31)
              .commit(),
          "synthetic existing e2 consent");
    }
    require(
        settings
            .edit()
            .putBoolean("syncEnabled", true)
            .putBoolean("cleanupEnabled", true)
            .putInt("retentionDays", 31)
            .commit(),
        "old configuration");
    defer(scheduler, 71, oldJob, true);
    defer(scheduler, 72, oldJob, false);
    require(
        witness
            .edit()
            .putString("phase", "seeded")
            .putBoolean("consent", existingConsent)
            .putInt("oldTask", oldTask)
            .commit(),
        "fixture seeded marker");
    return "PASS old JPG/WebP receipts, cache, deferred Amazon jobs and consent scenario seeded";
  }

  private static String key(boolean jpeg) throws Exception {
    return PhotoRepository.digest(jpeg ? FIXTURE + "-jpeg" : FIXTURE);
  }

  private static boolean emptyDirectory(File directory) {
    String[] entries = directory.list();
    return !directory.exists() || (entries != null && entries.length == 0);
  }

  private static boolean emptyMedia(Context context) {
    var resolver = context.getContentResolver();
    for (var uri :
        List.of(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
      try (var rows = resolver.query(uri, new String[] {"_id"}, null, null, null)) {
        if (rows == null || rows.moveToFirst()) return false;
      }
    }
    return true;
  }

  private static boolean emptyReceipts(Context context, String name) {
    File file = context.getDatabasePath(name);
    if (!file.exists()) return true;
    try (SQLiteDatabase db =
            SQLiteDatabase.openDatabase(
                file.getPath(), null, SQLiteDatabase.OPEN_READONLY, ignored -> {});
        var rows = db.rawQuery("SELECT 1 FROM photos LIMIT 1", null)) {
      return !rows.moveToFirst();
    }
  }

  private static void storeFixture(Context context, boolean jpeg) throws Exception {
    String key = key(jpeg);
    File preview = preview(context, key, jpeg);
    require(!preview.exists(), "old receipt already seeded");
    require(
        preview.getParentFile().isDirectory() || preview.getParentFile().mkdirs(),
        "preview folder");
    Bitmap bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(Color.BLUE);
    try (var out = new FileOutputStream(preview)) {
      require(
          bitmap.compress(
              jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out),
          "fixture preview");
    } finally {
      bitmap.recycle();
    }
    var record =
        new JSONObject()
            .put("key", key)
            .put("uri", "content://media/external/file/999999")
            .put("name", FIXTURE + (jpeg ? "-old.jpg" : ".jpg"))
            .put("folder", "Pictures/LoupeRegression/")
            .put("size", ORIGINAL.length)
            .put("modified", 1)
            .put("taken", 1)
            .put(
                "sha",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ORIGINAL)))
            .put("model", "Synthetic")
            .put("width", 32)
            .put("height", 24)
            .put("orientation", 1)
            .put("previewFormat", jpeg ? "jpeg" : "webp");
    try (var archive = new ArchiveStore(context)) {
      require(archive.get(key) == null, "old receipt already exists");
      archive.put(record, "verified");
    }
  }

  private static String e2Key(boolean cached) throws Exception {
    return PhotoRepository.digest(FIXTURE + "-e2-" + cached).substring(0, 32);
  }

  private static void storeE2Fixture(Context context, boolean cached) throws Exception {
    String key = e2Key(cached);
    var record =
        new JSONObject()
            .put("key", key)
            .put("uri", "content://media/external/file/999998")
            .put("name", FIXTURE + "-e2-" + cached + ".jpg")
            .put("folder", "Pictures/LoupeFixture/")
            .put("size", ORIGINAL.length)
            .put("modified", 1)
            .put("taken", 1)
            .put(
                "sha",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ORIGINAL)))
            .put("bucket", BUCKET)
            .put("model", "Synthetic")
            .put("thumbWidth", 32)
            .put("thumbHeight", 24);
    try (var archive = E2Settings.openRecords(context)) {
      archive.put(record, E2Cleanup.RELEASED);
    }
    File thumbnail = new File(new File(context.getFilesDir(), "e2-previews"), key + "-thumb.webp");
    require(
        thumbnail.getParentFile().isDirectory() || thumbnail.getParentFile().mkdirs(),
        "e2 thumbs folder");
    Files.copy(preview(context, key(false), false).toPath(), thumbnail.toPath());
    if (cached) {
      File file = original(context, ArchiveStore.key(E2Cleanup.galleryPhoto(record)));
      require(file.getParentFile().isDirectory() || file.getParentFile().mkdirs(), "cache folder");
      Files.write(file.toPath(), ORIGINAL);
    }
  }

  static String verify(Context context) throws Exception {
    SyncControlChecks.checkInstalledComponents(context);
    var witness = context.getSharedPreferences(FIXTURE, Context.MODE_PRIVATE);
    require("seeded".equals(witness.getString("phase", "")), "fixture was not seeded");
    require(
        context.getSystemService(ConnectivityManager.class).getActiveNetwork() == null,
        "verification requires an offline AVD");
    var scheduler = context.getSystemService(JobScheduler.class);
    require(
        scheduler.getPendingJob(71) == null && scheduler.getPendingJob(72) == null,
        "retired jobs remain");
    var settings = SettingsRepository.open(context);
    require(
        settings.getBoolean("syncEnabled", false) && settings.getBoolean("cleanupEnabled", false),
        "old settings were erased");
    require(E2Cleanup.days(context) == 31, "shared retention was changed");
    var e2State = E2AutoSync.state(context);
    if (witness.getBoolean("consent", false)) {
      require(
          E2SyncJob.fullMediaConsent(context)
              && E2SyncJob.enabled(context)
              && E2Cleanup.enabled(context, BUCKET)
              && BUCKET.equals(e2State.getString("destination", "")),
          "existing E2 consent was changed");
      long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
      while (scheduler.getPendingJob(80) == null
          && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
      JobInfo periodic = scheduler.getPendingJob(77);
      JobInfo continuation = scheduler.getPendingJob(79);
      JobInfo observer = scheduler.getPendingJob(80);
      require(
          periodic != null
              && periodic.isPersisted()
              && periodic.getIntervalMillis() == TimeUnit.MINUTES.toMillis(15)
              && continuation != null
              && scheduler.getPendingJob(78) == null
              && observer != null
              && observer.getTriggerContentUris().length == 3,
          "E2 periodic, catch-up and observer schedules differ");
      int oldTask = witness.getInt("oldTask", -1);
      require(oldTask >= 0, "old task was not seeded");
      for (ActivityManager.AppTask task :
          context.getSystemService(ActivityManager.class).getAppTasks()) {
        var info = task.getTaskInfo();
        if (info.taskId == oldTask) {
          require(
              !component(context, "CloudActivity").equals(info.topActivity)
                  && !component(context, "SyncActivity").equals(info.topActivity),
              "retired activity still at top of existing task");
        }
      }
    } else {
      require(e2State.getAll().isEmpty(), "upgrade introduced E2 consent");
      for (int id = 77; id <= 80; id++) {
        require(scheduler.getPendingJob(id) == null, "upgrade started unsanctioned E2 job " + id);
      }
    }
    for (boolean jpeg : new boolean[] {false, true}) {
      String key = key(jpeg);
      try (var archive = new ArchiveStore(context)) {
        var record = archive.get(key);
        require(
            record != null && "verified".equals(record.getString("state")),
            "old receipt disappeared");
        var photo = archive.cloudPhoto(record);
        require(key.equals(ArchiveStore.key(photo)), "old identity changed");
        var saved = archive.preview(key);
        require(saved.file.equals(preview(context, key, false)), "old preview not readable");
        Bitmap image = PhotoRepository.bitmap(saved, 64);
        try {
          require(image.getWidth() == 32 && image.getHeight() == 24, "old preview did not decode");
        } finally {
          image.recycle();
        }
        if (!jpeg) {
          var cached = OriginalCache.obtain(context, photo);
          require(
              Arrays.equals(ORIGINAL, Files.readAllBytes(new File(cached.uri.getPath()).toPath())),
              "checked originals cache changed");
        }
      }
    }
    return "PASS old jobs retired; consent and actual E2 schedules checked; old JPG/WebP previews, history and cache retained";
  }

  static String viewer(Instrumentation test, int width, float font) throws Exception {
    Context context = test.getTargetContext();
    String key = key(true);
    PhotoRepository.Photo photo;
    try (var archive = new ArchiveStore(context)) {
      JSONObject receipt = archive.get(key);
      require(receipt != null, "seed the old record before running the viewer check");
      photo = archive.cloudPhoto(receipt);
    }
    require(!original(context, key).exists(), "missing original fixture has a cache");
    MainActivity activity = null;
    try {
      activity =
          (MainActivity)
              test.startActivitySync(
                  new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      MainActivity screen = activity;
      GalleryScreenChecks.finishInitialIndex(test, screen);
      GalleryScreenChecks.portraitFixture(test, screen, width, font);
      AlbumTagEntryChecks.main(
          test,
          () -> {
            AlbumTagEntryChecks.set(screen, "photos", List.of(photo));
            AlbumTagEntryChecks.set(screen, "bundles", List.of(List.of(0)));
            var show = MainActivity.class.getDeclaredMethod("showPhoto", int.class);
            show.setAccessible(true);
            show.invoke(screen, 0);
            return true;
          });
      View decor = screen.getWindow().getDecorView();
      AlbumTagEntryChecks.waitFor(
          test,
          "legacy preview and recovery action",
          () -> {
            var viewer =
                (PhotoViewerUi)
                    ((ViewGroup) AlbumTagEntryChecks.field(screen, "root")).getChildAt(0);
            return viewer.stage.findViewWithTag("viewer-original-recovery") == null
                && String.valueOf(viewer.image.getStateDescription()).contains("プレビューを表示")
                && recovery(decor) != null;
          });
      SyncControlChecks.capture(test, "amazon-retirement-preview-" + width + "-" + font);
      AlbumTagEntryChecks.main(test, () -> recovery(decor).performClick());
      AlbumTagEntryChecks.waitFor(
          test,
          "legacy restore guidance",
          () -> {
            for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
              if (root.hasWindowFocus() && SyncControlChecks.find(root, "IDrive e2を開く") != null)
                return true;
            }
            return false;
          });
      SyncControlChecks.capture(test, "amazon-retirement-guidance-" + width + "-" + font);
      test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
      AlbumTagEntryChecks.waitFor(test, "back to legacy preview", screen::hasWindowFocus);
      return "PASS offline preview, missing-original recovery action and guidance captures";
    } finally {
      MainActivity screen = activity;
      if (screen != null) test.runOnMainSync(screen::finish);
    }
  }

  static String controls(Instrumentation test, int width, float font) throws Exception {
    Context context = test.getTargetContext();
    var witness = context.getSharedPreferences(FIXTURE, Context.MODE_PRIVATE);
    require(
        "seeded".equals(witness.getString("phase", "")) && E2Cleanup.days(context) == 31,
        "upgrade fixture retention was not seeded");
    checkRetentionWrites(context);
    var sync =
        (E2SyncActivity)
            test.startActivitySync(
                new Intent(context, E2SyncActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    String suffix = "-" + width + "-" + font;
    var recreated = new java.util.concurrent.atomic.AtomicReference<E2SyncActivity>();
    var syncMonitor = test.addMonitor(E2SyncActivity.class.getName(), null, false);
    try {
      checkRetentionUi(test, sync, suffix);
      test.runOnMainSync(sync::recreate);
      var replacement = (E2SyncActivity) test.waitForMonitorWithTimeout(syncMonitor, 6500);
      require(replacement != null && replacement != sync, "E2 recreation not observed");
      recreated.set(replacement);
      checkRetentionUi(test, replacement, null);
      require(E2Cleanup.days(context) == 31, "cancel or recreation changed the retention period");
    } finally {
      test.removeMonitor(syncMonitor);
      test.runOnMainSync(
          () -> {
            sync.finish();
            if (recreated.get() != null) recreated.get().finish();
          });
    }

    List<PhotoRepository.Photo> mixed;
    try (var archive = new ArchiveStore(context);
        var e2 = E2Settings.openRecords(context)) {
      File localSource = preview(context, key(false), false);
      var local =
          new PhotoRepository.Photo(
              android.net.Uri.fromFile(localSource),
              "local.jpg",
              "fixture",
              localSource.length(),
              0,
              0);
      mixed =
          List.of(
              local,
              archive.cloudPhoto(archive.get(key(false))),
              E2Cleanup.galleryPhoto(e2.get(e2Key(true))),
              archive.cloudPhoto(archive.get(key(true))),
              E2Cleanup.galleryPhoto(e2.get(e2Key(false))));
    }
    var selected = mixed.stream().map(photo -> photo.uri.toString()).toList();
    var main =
        (MainActivity)
            test.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      GalleryScreenChecks.finishInitialIndex(test, main);
      AlbumTagEntryChecks.main(
          test,
          () -> {
            AlbumTagEntryChecks.set(main, "selecting", true);
            @SuppressWarnings("unchecked")
            var selection =
                (java.util.Set<String>) AlbumTagEntryChecks.field(main, "selectedPhotos");
            selection.addAll(selected);
            var method = MainActivity.class.getDeclaredMethod("shareMenu", List.class);
            method.setAccessible(true);
            method.invoke(main, mixed);
            return true;
          });
      AlbumTagEntryChecks.waitFor(
          test,
          "checked mixed share notice",
          () -> {
            var menu = (ShareUi.Menu) AlbumTagEntryChecks.field(main, "shareDialog");
            return menu.archiveNotice.isShown()
                && menu.archiveNotice
                    .getText()
                    .toString()
                    .contains("IDrive e2の写真2枚は、原本が端末になければ取得します")
                && menu.archiveNotice
                    .getText()
                    .toString()
                    .contains("旧保管の写真2枚は、端末に保存済みの原本を使います。なければ復元が必要です")
                && menu.original.isEnabled();
          });
      SyncControlChecks.capture(test, "amazon-retirement-share" + suffix);
      AlbumTagEntryChecks.main(
          test,
          () -> {
            var menu = (ShareUi.Menu) AlbumTagEntryChecks.field(main, "shareDialog");
            require(
                menu.original.performClick()
                    && menu.continueButton.isEnabled()
                    && menu.continueButton.performClick(),
                "could not prepare selected originals");
            return true;
          });
      AlbumTagEntryChecks.waitFor(
          test,
          "restore guidance after mixed share failure",
          () -> {
            for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
              if (root.hasWindowFocus() && SyncControlChecks.find(root, "IDrive e2を開く") != null) {
                return true;
              }
            }
            return false;
          });
      SyncControlChecks.capture(test, "amazon-retirement-share-failure" + suffix);
      test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
      AlbumTagEntryChecks.waitFor(test, "back from share failure", main::hasWindowFocus);
      AlbumTagEntryChecks.main(
          test,
          () -> {
            require(
                Boolean.TRUE.equals(AlbumTagEntryChecks.field(main, "selecting"))
                    && java.util.Set.copyOf(selected)
                        .equals(AlbumTagEntryChecks.field(main, "selectedPhotos")),
                "share failure changed the selection");
            return true;
          });
      File[] shares = new File(context.getCacheDir(), "shares").listFiles();
      require(shares != null && shares.length == 0, "share failure left prepared copies");
    } finally {
      test.runOnMainSync(main::finish);
    }
    return "PASS retention chooser and recreation; conditional archive share notice, missing-original recovery, copy rollback and preserved selection";
  }

  private static void checkRetentionWrites(Context target) throws Exception {
    var settings =
        target.getSharedPreferences(FIXTURE + "-retention-settings", Context.MODE_PRIVATE);
    var e2 = target.getSharedPreferences(FIXTURE + "-retention-consent", Context.MODE_PRIVATE);
    require(
        settings.edit().clear().commit() && e2.edit().clear().commit(),
        "isolated test reset failed");
    var failSettings = new java.util.concurrent.atomic.AtomicBoolean();
    var failConsent = new java.util.concurrent.atomic.AtomicBoolean();
    Context context =
        new android.content.ContextWrapper(target) {
          @Override
          public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
            return switch (name) {
              case "settings" -> failNextCommit(settings, failSettings);
              case "e2_auto_sync" -> failNextCommit(e2, failConsent);
              default -> throw new AssertionError("unexpected preferences: " + name);
            };
          }
        };
    try {
      require(E2Cleanup.days(context) == 7, "absent retention should display seven days");
      require(
          e2.edit().putString("cleanupBucket", BUCKET).putInt("cleanupDays", 7).commit(),
          "seven day witness");
      require(
          !E2Cleanup.setRetentionDays(context, 7) && E2Cleanup.enabled(context, BUCKET),
          "same days revoked consent");
      failConsent.set(true);
      try {
        E2Cleanup.setRetentionDays(context, 90);
        throw new AssertionError("failed consent revocation reported success");
      } catch (java.io.IOException expected) {
        require(
            E2Cleanup.days(context) == 7 && !settings.contains("retentionDays"),
            "failed revocation changed retention");
      }
      require(
          e2.edit().putString("cleanupBucket", BUCKET).putInt("cleanupDays", 7).commit(),
          "restore fixture");
      failSettings.set(true);
      try {
        E2Cleanup.setRetentionDays(context, 30);
        throw new AssertionError("failed retention write reported success");
      } catch (java.io.IOException expected) {
        require(
            E2Cleanup.days(context) == 7
                && !settings.contains("retentionDays")
                && !E2Cleanup.enabled(context, BUCKET),
            "failed retention write did not restore old setting with consent off");
      }
      require(
          settings.edit().putInt("retentionDays", 31).commit()
              && e2.edit().putString("cleanupBucket", BUCKET).putInt("cleanupDays", 31).commit(),
          "seed old duration");
      require(
          E2Cleanup.setRetentionDays(context, 90)
              && E2Cleanup.days(context) == 90
              && !E2Cleanup.enabled(context, BUCKET),
          "duration change did not revoke consent");
      require(
          E2Cleanup.setRetentionDays(context, 30)
              && E2Cleanup.days(context) == 30
              && !E2Cleanup.enabled(context, BUCKET),
          "second duration change granted consent");
    } finally {
      require(
          settings.edit().clear().commit() && e2.edit().clear().commit(),
          "isolated test cleanup failed");
    }
  }

  private static android.content.SharedPreferences failNextCommit(
      android.content.SharedPreferences delegate,
      java.util.concurrent.atomic.AtomicBoolean failOnce) {
    return (android.content.SharedPreferences)
        java.lang.reflect.Proxy.newProxyInstance(
            AmazonRetirementUpgradeChecks.class.getClassLoader(),
            new Class<?>[] {android.content.SharedPreferences.class},
            (ignored, method, args) -> {
              Object result = method.invoke(delegate, args);
              if (!"edit".equals(method.getName())) return result;
              return java.lang.reflect.Proxy.newProxyInstance(
                  AmazonRetirementUpgradeChecks.class.getClassLoader(),
                  new Class<?>[] {android.content.SharedPreferences.Editor.class},
                  (editor, editMethod, values) -> {
                    Object value = editMethod.invoke(result, values);
                    if ("commit".equals(editMethod.getName()) && failOnce.getAndSet(false))
                      return false;
                    return value == result ? editor : value;
                  });
            });
  }

  private static void checkRetentionUi(Instrumentation test, E2SyncActivity screen, String suffix)
      throws Exception {
    View decor = screen.getWindow().getDecorView();
    String label = "端末に写真を残す期間 · 31日";
    AlbumTagEntryChecks.waitFor(
        test,
        "E2 retention action after recreation",
        () -> screen.hasWindowFocus() && SyncControlChecks.find(decor, label) != null);
    AlbumTagEntryChecks.main(
        test,
        () -> {
          View button = SyncControlChecks.find(decor, label);
          button.requestRectangleOnScreen(
              new android.graphics.Rect(0, 0, button.getWidth(), button.getHeight()), true);
          require(
              label.contentEquals(button.getContentDescription()) && button.isClickable(),
              "retention AX");
          return true;
        });
    if (suffix != null) SyncControlChecks.capture(test, "amazon-retirement-retention" + suffix);
    AlbumTagEntryChecks.main(test, () -> SyncControlChecks.find(decor, label).performClick());
    AlbumTagEntryChecks.waitFor(
        test,
        "three retention choices",
        () -> {
          for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
            if (root.hasWindowFocus()
                && SyncControlChecks.find(root, "7日間") != null
                && SyncControlChecks.find(root, "30日間") != null
                && SyncControlChecks.find(root, "90日間") != null) return true;
          }
          return false;
        });
    if (suffix != null)
      SyncControlChecks.capture(test, "amazon-retirement-retention-choices" + suffix);
    test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
    AlbumTagEntryChecks.waitFor(test, "back from retention choices", screen::hasWindowFocus);
  }

  private static View recovery(View view) {
    CharSequence description = view.getContentDescription();
    if (view.isClickable() && description != null && description.toString().endsWith("。復元方法")) {
      return view;
    }
    if (view instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) {
        View child = recovery(group.getChildAt(i));
        if (child != null) return child;
      }
    }
    return null;
  }

  private static void defer(JobScheduler scheduler, int id, ComponentName name, boolean persisted) {
    var job =
        new JobInfo.Builder(id, name)
            .setPersisted(persisted)
            .setMinimumLatency(TimeUnit.DAYS.toMillis(1))
            .setRequiresDeviceIdle(true)
            .build();
    require(
        scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
            && scheduler.getPendingJob(id) != null,
        "could not seed job " + id);
  }

  private static ComponentName component(Context context, String name) {
    return new ComponentName(context, "jp.nano.rawgallery." + name);
  }

  private static File preview(Context context, String key, boolean jpeg) {
    return new File(new File(context.getFilesDir(), "archive"), key + (jpeg ? ".jpg" : ".webp"));
  }

  private static File original(Context context, String key) {
    return new File(new File(context.getCacheDir(), "originals"), key + ".jpg");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
