package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
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

  static String seed(Context context) throws Exception {
    var scheduler = context.getSystemService(JobScheduler.class);
    var oldJob = component(context, "SyncJob");
    context.getPackageManager().getServiceInfo(oldJob, 0);
    var e2State = E2AutoSync.state(context);
    require(
        !E2SyncJob.enabled(context), "seed requires a disposable installation with E2 disabled");
    require(!e2State.contains("cleanupBucket"), "seed requires no E2 cleanup");
    String key = PhotoRepository.digest(FIXTURE);
    File preview = preview(context, key);
    require(!preview.exists(), "old receipt already seeded");
    require(
        preview.getParentFile().isDirectory() || preview.getParentFile().mkdirs(),
        "preview folder");
    Bitmap bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(Color.BLUE);
    try (var out = new FileOutputStream(preview)) {
      require(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out), "fixture preview");
    } finally {
      bitmap.recycle();
    }
    var record =
        new JSONObject()
            .put("key", key)
            .put("uri", "content://media/external/file/999999")
            .put("name", FIXTURE + ".jpg")
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
            .put("previewFormat", "webp");
    try (var archive = new ArchiveStore(context)) {
      require(archive.get(key) == null, "old receipt already exists");
      archive.put(record, "verified");
    }
    File cache = original(context, key);
    require(cache.getParentFile().isDirectory() || cache.getParentFile().mkdirs(), "cache folder");
    Files.write(cache.toPath(), ORIGINAL);
    require(e2State.edit().putInt("fullMediaConsent", 2).commit(), "synthetic e2 consent marker");
    require(
        SettingsRepository.open(context)
            .edit()
            .putBoolean("syncEnabled", true)
            .putBoolean("cleanupEnabled", true)
            .putInt("retentionDays", 31)
            .commit(),
        "old configuration");

    defer(scheduler, 71, oldJob, true);
    defer(scheduler, 72, oldJob, false);
    defer(scheduler, 7054, component(context, "E2SyncJob"), true);
    return "PASS old archive receipt, checked-byte cache, deferred old jobs and E2 markers seeded";
  }

  static String verify(Context context) throws Exception {
    SyncControlChecks.checkInstalledComponents(context);
    var scheduler = context.getSystemService(JobScheduler.class);
    require(
        scheduler.getPendingJob(71) == null && scheduler.getPendingJob(72) == null,
        "retired jobs remain");
    require(scheduler.getPendingJob(7054) != null, "E2 control job was cancelled");
    var settings = SettingsRepository.open(context);
    require(
        settings.getBoolean("syncEnabled", false) && settings.getBoolean("cleanupEnabled", false),
        "old settings were erased");
    require(E2Cleanup.days(context) == 31, "shared retention was changed");
    var e2State = E2AutoSync.state(context);
    require(
        E2SyncJob.fullMediaConsent(context)
            && !E2SyncJob.enabled(context)
            && !e2State.contains("cleanupBucket")
            && !e2State.contains("cleanupDays"),
        "E2 settings changed during upgrade");
    String key = PhotoRepository.digest(FIXTURE);
    try (var archive = new ArchiveStore(context)) {
      var record = archive.get(key);
      require(
          record != null && "verified".equals(record.getString("state")),
          "old receipt disappeared");
      var photo = archive.cloudPhoto(record);
      require(key.equals(ArchiveStore.key(photo)), "old identity changed");
      var saved = archive.preview(key);
      require(saved.file.equals(preview(context, key)), "old preview changed");
      Bitmap image = PhotoRepository.bitmap(saved, 64);
      try {
        require(image.getWidth() == 32 && image.getHeight() == 24, "old preview did not decode");
      } finally {
        image.recycle();
      }
      var cached = OriginalCache.obtain(context, photo);
      require(
          Arrays.equals(ORIGINAL, Files.readAllBytes(new File(cached.uri.getPath()).toPath())),
          "checked originals cache changed");
    }
    scheduler.cancel(7054);
    return "PASS old jobs retired; E2 control job and settings preserved; old identity, preview and checked cache retained";
  }

  static String viewer(Instrumentation test) throws Exception {
    Context context = test.getTargetContext();
    String key = PhotoRepository.digest(FIXTURE);
    PhotoRepository.Photo photo;
    try (var archive = new ArchiveStore(context)) {
      JSONObject receipt = archive.get(key);
      require(receipt != null, "seed the old record before running the viewer check");
      photo = archive.cloudPhoto(receipt);
    }
    File cache = original(context, key);
    byte[] saved = Files.readAllBytes(cache.toPath());
    require(Arrays.equals(ORIGINAL, saved), "disposable fixture cache missing");
    MainActivity activity = null;
    try {
      Files.delete(cache.toPath());
      activity =
          (MainActivity)
              test.startActivitySync(
                  new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      MainActivity screen = activity;
      GalleryScreenChecks.finishInitialIndex(test, screen);
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
      SyncControlChecks.capture(test, "amazon-retirement-preview");
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
      SyncControlChecks.capture(test, "amazon-retirement-guidance");
      return "PASS offline preview, missing-original recovery action and guidance captures";
    } finally {
      MainActivity screen = activity;
      if (screen != null) test.runOnMainSync(screen::finish);
      Files.write(cache.toPath(), saved);
    }
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

  private static File preview(Context context, String key) {
    return new File(new File(context.getFilesDir(), "archive"), key + ".webp");
  }

  private static File original(Context context, String key) {
    return new File(new File(context.getCacheDir(), "originals"), key + ".jpg");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
