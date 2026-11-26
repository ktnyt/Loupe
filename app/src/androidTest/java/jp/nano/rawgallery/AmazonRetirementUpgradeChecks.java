package jp.nano.rawgallery;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.Instrumentation;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import jp.nano.rawgallery.security.EncryptedSecretStore;
import org.json.JSONObject;

/** Run seed on the old APK and verify on its replacement using a disposable test installation. */
final class AmazonRetirementUpgradeChecks {
  private static final String FIXTURE = "amazon-retirement";
  private static final byte[] ORIGINAL = {9, 4, 2, 1};

  private static final String BUCKET = "loupe-amazon-retirement-fixture";
  private static final String NANO_PREFS = "nano-banana";
  private static final String NANO_ALIAS = "loupe.nano-banana.v1";
  private static final String NANO_KEY = "synthetic-upgrade-noncredential";
  private static final String E2_PREFS = "e2_connection";
  private static final String E2_ALIAS = "loupe-e2-connection";
  private static final String E2_ACCESS = "synthetic-offline-access";
  private static final String E2_SECRET = "synthetic-offline-secret";
  private static final String FRESH_CONSENT = "cleanupConsentAfterAmazonRetirement";
  private static final String PENDING_CONSENT = "e2-cleanup-consent.pending";

  private enum Profile {
    ABSENT(true, false),
    ACTIVE(false, true),
    CONTRADICTORY(true, true);

    final boolean legacyCleanup;
    final boolean e2Cleanup;

    Profile(boolean legacyCleanup, boolean e2Cleanup) {
      this.legacyCleanup = legacyCleanup;
      this.e2Cleanup = e2Cleanup;
    }

    static Profile parse(String name) {
      return switch (name) {
        case "absent" -> ABSENT;
        case "active" -> ACTIVE;
        case "contradictory" -> CONTRADICTORY;
        default ->
            throw new IllegalArgumentException("consent must be absent, active or contradictory");
      };
    }
  }

  static void requireDisposable(Context context, boolean acknowledged) {
    require(
        acknowledged
            && "ranchu".equals(Build.HARDWARE)
            && "jp.nano.rawgallery".equals(context.getPackageName())
            && (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        "requires a dedicated debug emulator and -e disposable-avd true");
  }

  static String seed(Instrumentation test, String consentProfile) throws Exception {
    Profile profile = Profile.parse(consentProfile);
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
    requireUnusedCredentials(context);
    var e2State = E2AutoSync.state(context);
    var connection = context.getSharedPreferences(E2_PREFS, Context.MODE_PRIVATE);
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
    PackageInfo installed = installedPackage(context);
    require(
        witness
            .edit()
            .putString("phase", "seeding")
            .putLong("oldVersion", installed.getLongVersionCode())
            .putLong("firstInstallTime", installed.firstInstallTime)
            .putLong("oldLastUpdateTime", installed.lastUpdateTime)
            .putString("oldApkDigest", installedApkDigest(context))
            .putString("signer", signerDigest(installed))
            .commit(),
        "fixture marker");

    int oldTask = seedOldTask(test, context);
    requireUnusedCredentials(context);
    seedCredentials(context, connection, profile);
    require(
        witness
            .edit()
            .putString("nanoCipher", storedCipherDigest(context, NANO_PREFS, "credential"))
            .putString("e2Cipher", storedCipherDigest(context, E2_PREFS, "connection"))
            .commit(),
        "fixture ciphertext witness");
    storeFixture(context, false);
    storeFixture(context, true);
    storeE2Fixture(context, false);
    storeE2Fixture(context, true);
    String key = key(false);
    File cache = original(context, key);
    require(cache.getParentFile().isDirectory() || cache.getParentFile().mkdirs(), "cache folder");
    Files.write(cache.toPath(), ORIGINAL);
    if (profile.e2Cleanup) {
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
            .putBoolean("cleanupEnabled", profile.legacyCleanup)
            .putInt("retentionDays", 31)
            .commit(),
        "old configuration");
    if (profile.e2Cleanup) {
      E2SyncJob.recoverOnOpen(context);
      awaitE2Schedule(scheduler, 15);
    }
    defer(scheduler, 71, oldJob, true);
    defer(scheduler, 72, oldJob, false);
    require(
        witness
            .edit()
            .putString("phase", "seeded")
            .putString("consent", consentProfile)
            .putInt("oldTask", oldTask)
            .commit(),
        "fixture seeded marker");
    return "PASS old JPG/WebP receipts, cache, deferred Amazon jobs, encrypted settings and "
        + consentProfile;
  }

  private static void requireUnusedCredentials(Context context) throws Exception {
    var directory = context.getDataDir().toPath().resolve("shared_prefs");
    for (String name : List.of(NANO_PREFS, E2_PREFS)) {
      // Android can report an empty preference map when existing XML is unreadable.
      for (String suffix : List.of(".xml", ".xml.bak")) {
        require(
            Files.notExists(directory.resolve(name + suffix), LinkOption.NOFOLLOW_LINKS),
            "credential preference file already exists or its absence cannot be confirmed");
      }
      require(
          context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().isEmpty(),
          "credential preference state is not empty");
    }
    KeyStore keystore = KeyStore.getInstance("AndroidKeyStore");
    keystore.load(null);
    require(
        !keystore.containsAlias(NANO_ALIAS) && !keystore.containsAlias(E2_ALIAS),
        "a credential key already exists");
  }

  private static void seedCredentials(
      Context context, SharedPreferences connection, Profile profile) throws Exception {
    NanoBananaStore nano = new NanoBananaStore(context);
    nano.setOutput("16:9", "2K");
    nano.setKey(NANO_KEY);
    if (!profile.e2Cleanup) return;

    // Same on-disk representation as E2Settings.connect, without its network probe.
    var configuration =
        new JSONObject().put("bucket", BUCKET).put("access", E2_ACCESS).put("secret", E2_SECRET);
    var cipher = EncryptedSecretStore.encryptionCipher(E2_ALIAS);
    byte[] encrypted = cipher.doFinal(configuration.toString().getBytes(StandardCharsets.UTF_8));
    String encoded =
        Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
            + "."
            + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    require(
        connection.edit().putString("connection", encoded).putString("bucket", BUCKET).commit(),
        "synthetic encrypted e2 settings");
  }

  private static void verifyCredentials(Context context, SharedPreferences witness, Profile profile)
      throws Exception {
    require(
        witness
                .getString("nanoCipher", "")
                .equals(storedCipherDigest(context, NANO_PREFS, "credential"))
            && !witness.getString("nanoCipher", "").isEmpty(),
        "Nano ciphertext changed");
    NanoBananaStore nano = new NanoBananaStore(context);
    require(NANO_KEY.equals(nano.apiKey()), "Nano key did not decrypt to the seeded value");
    var output = nano.output();
    require(
        "16:9".equals(output.aspectRatio) && "2K".equals(output.resolution),
        "Nano output settings changed");
    if (!profile.e2Cleanup) {
      require(
          context.getSharedPreferences(E2_PREFS, Context.MODE_PRIVATE).getAll().isEmpty(),
          "upgrade introduced a connection");
      return;
    }
    require(
        witness
                .getString("e2Cipher", "")
                .equals(storedCipherDigest(context, E2_PREFS, "connection"))
            && !witness.getString("e2Cipher", "").isEmpty(),
        "e2 ciphertext changed");
    E2Settings e2 = new E2Settings(context);
    try (var records = e2.records) {
      var configuration = e2.configuration();
      require(
          BUCKET.equals(E2Settings.configuredBucket(context))
              && BUCKET.equals(configuration.getString("bucket"))
              && E2_ACCESS.equals(configuration.getString("access"))
              && E2_SECRET.equals(configuration.getString("secret")),
          "e2 connection did not decrypt to the seeded values");
    }
  }

  private static String storedCipherDigest(Context context, String prefs, String key)
      throws Exception {
    String encoded = context.getSharedPreferences(prefs, Context.MODE_PRIVATE).getString(key, "");
    return encoded.isEmpty() ? "" : digest(encoded.getBytes(StandardCharsets.UTF_8));
  }

  private static PackageInfo installedPackage(Context context)
      throws PackageManager.NameNotFoundException {
    return context
        .getPackageManager()
        .getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
  }

  private static String signerDigest(PackageInfo installed) throws Exception {
    var signers = installed.signingInfo.getApkContentsSigners();
    require(signers.length == 1, "fixture APK must have one signer");
    return digest(signers[0].toByteArray());
  }

  private static String installedApkDigest(Context context) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] buffer = new byte[32 * 1024];
    try (var input =
        Files.newInputStream(new File(context.getApplicationInfo().sourceDir).toPath())) {
      for (int length; (length = input.read(buffer)) != -1; ) digest.update(buffer, 0, length);
    } catch (IOException unreadable) {
      throw new IOException("installed fixture APK could not be read");
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String digest(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static int seedOldTask(Instrumentation test, Context context) throws Exception {
    Activity old =
        test.startActivitySync(
            new Intent()
                .setComponent(component(context, "CloudActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    int taskId = old.getTaskId();
    var manager = context.getSystemService(ActivityManager.class);
    var appTask = findTask(manager, taskId);
    var info = appTask == null ? null : taskInfo(appTask);
    require(
        info != null && component(context, "CloudActivity").equals(info.topActivity),
        "old activity was not the top of its recorded task");
    test.runOnMainSync(
        () -> require(old.moveTaskToBack(true), "old activity task was not backgrounded"));
    var recovering = E2SyncJob.class.getDeclaredField("OPEN_RECOVERY_QUEUED");
    recovering.setAccessible(true);
    var queued = (AtomicBoolean) recovering.get(null);
    long deadline = SystemClock.elapsedRealtime() + 5000;
    while (queued.get() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
    require(!queued.get(), "old recover-on-open did not settle");
    return taskId;
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

  static String verify(Instrumentation test) throws Exception {
    Context context = test.getTargetContext();
    SyncControlChecks.checkInstalledComponents(context);
    var witness = context.getSharedPreferences(FIXTURE, Context.MODE_PRIVATE);
    require("seeded".equals(witness.getString("phase", "")), "fixture was not seeded");
    Profile profile = Profile.parse(witness.getString("consent", ""));
    require(
        context.getSystemService(ConnectivityManager.class).getActiveNetwork() == null,
        "verification requires an offline AVD");
    PackageInfo installed = installedPackage(context);
    long oldVersion = witness.getLong("oldVersion", -1);
    long oldLastUpdateTime = witness.getLong("oldLastUpdateTime", 0);
    String oldApkDigest = witness.getString("oldApkDigest", "");
    // Feature builds may share the main version until merge. Verify actual APK replacement.
    require(
        oldVersion >= 0
            && installed.getLongVersionCode() >= oldVersion
            && oldLastUpdateTime > 0
            && installed.lastUpdateTime > oldLastUpdateTime
            && installed.firstInstallTime == witness.getLong("firstInstallTime", 0)
            && signerDigest(installed).equals(witness.getString("signer", ""))
            && oldApkDigest.matches("[0-9a-f]{64}")
            && !oldApkDigest.equals(installedApkDigest(context)),
        "expected a different in-place fixture update without downgrade and under the same signer");
    verifyCredentials(context, witness, profile);
    var scheduler = context.getSystemService(JobScheduler.class);
    require(
        scheduler.getPendingJob(71) == null && scheduler.getPendingJob(72) == null,
        "retired jobs remain");
    var settings = SettingsRepository.open(context);
    require(
        settings.getBoolean("syncEnabled", false)
            && settings.contains("cleanupEnabled")
            && settings.getBoolean("cleanupEnabled", !profile.legacyCleanup)
                == profile.legacyCleanup,
        "old settings changed");
    require(E2Cleanup.days(context) == 31, "shared retention was changed");
    var e2State = E2AutoSync.state(context);
    require(!e2State.contains(FRESH_CONSENT), "upgrade granted a new cleanup marker");
    require(
        E2Cleanup.enabled(context, BUCKET) == (profile == Profile.ACTIVE),
        "cleanup eligibility changed independently of network or storage permission");
    if (profile.e2Cleanup) {
      require(
          E2SyncJob.fullMediaConsent(context)
              && E2SyncJob.enabled(context)
              && BUCKET.equals(e2State.getString("destination", ""))
              && BUCKET.equals(e2State.getString("cleanupBucket", ""))
              && e2State.getInt("cleanupDays", 0) == 31,
          "existing E2 state or raw consent was changed");
      awaitE2Schedule(scheduler, profile == Profile.ACTIVE ? 15 : 12 * 60);
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
    try (var e2 = E2Settings.openRecords(context)) {
      for (boolean cached : new boolean[] {false, true}) {
        String key = e2Key(cached);
        JSONObject record = e2.get(key);
        require(
            record != null
                && E2Cleanup.RELEASED.equals(record.getString("state"))
                && BUCKET.equals(record.getString("bucket")),
            "saved e2 receipt changed");
        var photo = E2Cleanup.galleryPhoto(record);
        require(key.equals(photo.uri.getHost()), "saved e2 identity changed");
        if (cached) {
          File file = original(context, ArchiveStore.key(photo));
          require(
              file.isFile() && Arrays.equals(ORIGINAL, Files.readAllBytes(file.toPath())),
              "saved e2 cache or archive key changed");
        }
      }
    }
    String task = verifyOldTask(test, context, witness.getInt("oldTask", -1));
    return "PASS old jobs retired; consent and actual E2 schedules checked; old JPG/WebP previews,"
        + " history, cache and encrypted settings retained; "
        + task;
  }

  private static ActivityManager.AppTask findTask(ActivityManager manager, int id) {
    for (ActivityManager.AppTask task : manager.getAppTasks()) {
      var info = taskInfo(task);
      if (info != null && info.taskId == id) return task;
    }
    return null;
  }

  private static ActivityManager.RecentTaskInfo taskInfo(ActivityManager.AppTask task) {
    try {
      return task.getTaskInfo();
    } catch (IllegalArgumentException removed) {
      return null;
    }
  }

  private static String verifyOldTask(Instrumentation test, Context context, int taskId)
      throws Exception {
    require(taskId >= 0, "old activity task was not recorded");
    var manager = context.getSystemService(ActivityManager.class);
    ActivityManager.AppTask task = findTask(manager, taskId);
    String outcome =
        task == null
            ? "old task absent from the OS app-task list before verification"
            : resumeOldTask(context, manager, task, taskId);
    Activity main =
        test.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      require(main instanceof MainActivity, "normal launch did not open MainActivity");
      AlbumTagEntryChecks.waitFor(
          test, "normal MainActivity launch after update", main::hasWindowFocus);
    } finally {
      test.runOnMainSync(main::finish);
    }
    return outcome + "; normal MainActivity launch observed";
  }

  private static String resumeOldTask(
      Context context, ActivityManager manager, ActivityManager.AppTask task, int id)
      throws InterruptedException {
    Application application = (Application) context.getApplicationContext();
    var resumed = new AtomicReference<Activity>();
    Application.ActivityLifecycleCallbacks monitor =
        new Application.ActivityLifecycleCallbacks() {
          @Override
          public void onActivityResumed(Activity activity) {
            resumed.set(activity);
          }

          @Override
          public void onActivityCreated(Activity activity, Bundle state) {}

          @Override
          public void onActivityStarted(Activity activity) {}

          @Override
          public void onActivityPaused(Activity activity) {}

          @Override
          public void onActivityStopped(Activity activity) {}

          @Override
          public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

          @Override
          public void onActivityDestroyed(Activity activity) {}
        };
    application.registerActivityLifecycleCallbacks(monitor);
    try {
      try {
        task.moveToFront();
      } catch (IllegalArgumentException removed) {
        if (findTask(manager, id) == null) return "old task absent when moveToFront was attempted";
        throw removed;
      }
      long deadline = SystemClock.elapsedRealtime() + 5000;
      do {
        var old = findTask(manager, id);
        var info = old == null ? null : taskInfo(old);
        if (info == null) return "OS removed the old task after a moveToFront attempt";
        var activity = resumed.get();
        // Task metadata can show the old alias name after MainActivity has resumed.
        if (activity instanceof MainActivity && activity.getTaskId() == id) {
          return "retained old task resumed MainActivity";
        }
        Thread.sleep(50);
      } while (SystemClock.elapsedRealtime() < deadline);
      throw new AssertionError("retained old task move had no observable safe outcome");
    } finally {
      application.unregisterActivityLifecycleCallbacks(monitor);
    }
  }

  private static void awaitE2Schedule(JobScheduler scheduler, int intervalMinutes)
      throws InterruptedException {
    long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
    do {
      JobInfo periodic = scheduler.getPendingJob(77);
      JobInfo continuation = scheduler.getPendingJob(79);
      JobInfo observer = scheduler.getPendingJob(80);
      if (periodic != null
          && periodic.isPersisted()
          && periodic.getIntervalMillis() == TimeUnit.MINUTES.toMillis(intervalMinutes)
          && continuation != null
          && scheduler.getPendingJob(78) == null
          && observer != null
          && observer.getTriggerContentUris().length == 3) return;
      Thread.sleep(50);
    } while (android.os.SystemClock.elapsedRealtime() < deadline);
    throw new AssertionError("E2 periodic, catch-up and observer schedules differ");
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
    checkCleanupConsentWrites(context);
    var sync =
        (E2SyncActivity)
            test.startActivitySync(
                new Intent(context, E2SyncActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    String suffix = "-" + width + "-" + font;
    boolean absent = "absent".equals(witness.getString("consent", ""));
    var recreated = new java.util.concurrent.atomic.AtomicReference<E2SyncActivity>();
    var syncMonitor = test.addMonitor(E2SyncActivity.class.getName(), null, false);
    try {
      checkRetentionUi(test, sync, suffix);
      if (absent) checkCleanupUnknownUi(test, sync, suffix);
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
    return "PASS explicit E2 consent and fail-closed writes; retention chooser and recreation;"
        + (absent ? " synthetic E2 unknown state, native stop exposure and confirmed stop;" : "")
        + " conditional archive share notice, missing-original recovery, copy rollback and"
        + " preserved selection";
  }

  private static void checkCleanupUnknownUi(
      Instrumentation test, E2SyncActivity screen, String suffix) throws Exception {
    Context context = test.getTargetContext();
    SharedPreferences options = E2AutoSync.state(context);
    var previousOptions = options.getAll();
    var connection = context.getSharedPreferences(E2_PREFS, Context.MODE_PRIVATE);
    var previousConnection = connection.getAll();
    var nano = context.getSharedPreferences(NANO_PREFS, Context.MODE_PRIVATE);
    var previousNano = nano.getAll();
    var pending = pendingConsent(context.getDataDir()).toPath();
    var optionsFile = new File(context.getDataDir(), "shared_prefs/e2_auto_sync.xml").toPath();
    boolean optionsFileAbsent = Files.notExists(optionsFile, LinkOption.NOFOLLOW_LINKS);
    var optionsBackup = optionsFile.resolveSibling(optionsFile.getFileName() + ".bak");
    require(
        context.getSystemService(ConnectivityManager.class).getActiveNetwork() == null
            && previousOptions.isEmpty()
            && previousConnection.isEmpty()
            && !E2SyncJob.enabled(context)
            && !options.contains(FRESH_CONSENT)
            && Files.notExists(pending, LinkOption.NOFOLLOW_LINKS)
            && Files.notExists(optionsBackup, LinkOption.NOFOLLOW_LINKS)
            && (optionsFileAbsent || Files.isRegularFile(optionsFile, LinkOption.NOFOLLOW_LINKS)),
        "unknown UI test requires the offline, absent upgrade cohort");
    byte[] previousOptionsBytes = optionsFileAbsent ? null : Files.readAllBytes(optionsFile);
    var previousHistory = consentUiHistory(context);

    var field = E2Cleanup.class.getDeclaredField("unconfirmedConsentWrites");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    var pendingWrites = (Map<SharedPreferences, Boolean>) field.get(null);
    var panel = (E2Panel) AlbumTagEntryChecks.field(screen, "panel");
    var stop = (Button) AlbumTagEntryChecks.field(panel, "cleanupToggle");
    var ancestor = stop.getParent();
    while (ancestor != null && !(ancestor instanceof ScrollView)) ancestor = ancestor.getParent();
    require(ancestor instanceof ScrollView, "unknown E2 stop has no settings scroll view");
    var scroll = (ScrollView) ancestor;
    var status = (TextView) AlbumTagEntryChecks.field(panel, "cleanupStatus");
    var message = (TextView) AlbumTagEntryChecks.field(panel, "status");
    String stopLabel = "端末オリジナルの整理を停止";
    String unknownLabel = "容量整理の設定を確認できません";
    String startLabel = "端末オリジナルの整理を有効にする";
    var originalBucket = new AtomicReference<String>();
    var originalWrite = new AtomicReference<Boolean>();
    var staged = new AtomicBoolean();
    try {
      AlbumTagEntryChecks.main(
          test,
          () -> {
            originalBucket.set((String) AlbumTagEntryChecks.field(panel, "activeBucket"));
            require(originalBucket.get() == null, "absent cohort has an active panel bucket");
            synchronized (pendingWrites) {
              originalWrite.set(pendingWrites.put(options, true));
            }
            staged.set(true);
            // The absent cohort has no connection. Stage a UI-only bucket without credentials.
            AlbumTagEntryChecks.set(panel, "activeBucket", BUCKET);
            panel.refreshAuto();
            return true;
          });
      try {
        int captureScroll =
            awaitCleanupUnknownFrame(test, scroll, status, stop, unknownLabel, stopLabel);
        SyncControlChecks.capture(test, "amazon-retirement-consent-unknown" + suffix);
        AlbumTagEntryChecks.main(
            test,
            () -> {
              require(
                  scroll.getScrollY() == captureScroll
                      && cleanupUnknownVisible(status, stop, unknownLabel, stopLabel),
                  "unknown E2 status or stop geometry changed during capture");
              require(
                  E2Cleanup.consentUnknown(context)
                      && stop.isEnabled()
                      && stopLabel.contentEquals(stop.getText())
                      && stop.performClick(),
                  "unknown E2 state did not offer the real stop action");
              return true;
            });
      } catch (AssertionError failure) {
        captureCleanupUnknownFailure(
            test, screen, panel, status, stop, unknownLabel, stopLabel, suffix, failure);
        throw failure;
      }
      AlbumTagEntryChecks.waitFor(
          test,
          "confirmed E2 stop returned to the off state",
          () ->
              !E2Cleanup.consentUnknown(context)
                  && !E2Cleanup.enabled(context, BUCKET)
                  && !status.isShown()
                  && startLabel.contentEquals(stop.getText())
                  && !stop.isEnabled()
                  && "端末オリジナルの整理を停止しました".contentEquals(message.getText()));
      require(previousOptions.equals(options.getAll()), "native stop changed raw E2 options");
    } finally {
      try {
        if (!previousOptions.equals(options.getAll()))
          require(options.edit().clear().commit(), "restore empty upgrade E2 options");
        // A no-op commit against absent preferences can create an empty XML on some Android builds.
        if (optionsFileAbsent) Files.deleteIfExists(optionsFile);
        else if (Files.notExists(optionsFile, LinkOption.NOFOLLOW_LINKS)
            || !Arrays.equals(previousOptionsBytes, Files.readAllBytes(optionsFile)))
          Files.write(optionsFile, previousOptionsBytes);
        Files.deleteIfExists(optionsBackup);
      } finally {
        if (staged.get()) {
          AlbumTagEntryChecks.main(
              test,
              () -> {
                synchronized (pendingWrites) {
                  if (originalWrite.get() == null) pendingWrites.remove(options);
                  else pendingWrites.put(options, originalWrite.get());
                }
                AlbumTagEntryChecks.set(panel, "activeBucket", originalBucket.get());
                panel.refreshAuto();
                return true;
              });
        }
        require(
            previousOptions.equals(options.getAll())
                && !E2SyncJob.enabled(context)
                && Files.notExists(pending, LinkOption.NOFOLLOW_LINKS)
                && Files.notExists(optionsBackup, LinkOption.NOFOLLOW_LINKS)
                && (optionsFileAbsent
                    ? Files.notExists(optionsFile, LinkOption.NOFOLLOW_LINKS)
                    : Arrays.equals(previousOptionsBytes, Files.readAllBytes(optionsFile)))
                && previousConnection.equals(connection.getAll())
                && previousNano.equals(nano.getAll())
                && previousHistory.equals(consentUiHistory(context)),
            "unknown UI proof changed preferences, credentials, history or production journal");
      }
    }
  }

  private static int awaitCleanupUnknownFrame(
      Instrumentation test,
      ScrollView scroll,
      TextView status,
      Button stop,
      String unknownLabel,
      String stopLabel)
      throws Exception {
    View decor = scroll.getRootView();
    // Lay out the newly visible status before calculating how far to scroll.
    ConsistencyLayoutChecks.frame(test, decor);
    var intendedScroll = new java.util.concurrent.atomic.AtomicInteger();
    var stable = new AtomicBoolean();
    for (int attempt = 0; attempt < 3; attempt++) {
      AlbumTagEntryChecks.waitFor(
          test,
          "visible unknown E2 state and accessible stop action",
          () -> {
            if (!status.isShown() || status.getHeight() <= 0) return false;
            revealCleanupUnknown(scroll, status, stop);
            if (!cleanupUnknownVisible(status, stop, unknownLabel, stopLabel)) return false;
            intendedScroll.set(scroll.getScrollY());
            return true;
          });
      ConsistencyLayoutChecks.frame(test, decor);
      AlbumTagEntryChecks.main(
          test,
          () -> {
            stable.set(
                decor.hasWindowFocus()
                    && scroll.getScrollY() == intendedScroll.get()
                    && cleanupUnknownVisible(status, stop, unknownLabel, stopLabel));
            return true;
          });
      if (stable.get()) return intendedScroll.get();
      // Layout can restore focus and scroll. Reveal again instead of capturing that frame.
    }
    throw new AssertionError("unknown E2 status and stop did not stabilize after layout");
  }

  private static boolean cleanupUnknownVisible(
      TextView status, Button stop, String unknownLabel, String stopLabel) {
    if (!stop.hasWindowFocus() || !status.isShown() || status.getHeight() <= 0) return false;
    var node = stop.createAccessibilityNodeInfo();
    var bounds = new Rect();
    node.getBoundsInScreen(bounds);
    var visibleStatus = new Rect();
    var visibleStop = new Rect();
    int touchSize = GalleryUi.dp(stop.getContext(), 48);
    return unknownLabel.contentEquals(status.getText())
        && status.getGlobalVisibleRect(visibleStatus)
        && visibleStatus.height() >= status.getHeight()
        && status.getAccessibilityLiveRegion() == View.ACCESSIBILITY_LIVE_REGION_POLITE
        && stopLabel.contentEquals(node.getText())
        && node.isVisibleToUser()
        && node.isEnabled()
        && node.isClickable()
        && stop.getGlobalVisibleRect(visibleStop)
        && visibleStop.width() >= touchSize
        && visibleStop.height() >= touchSize
        && bounds.width() >= touchSize
        && bounds.height() >= touchSize;
  }

  private static void revealCleanupUnknown(ScrollView scroll, View status, View stop) {
    int[] location = new int[2];
    scroll.getLocationOnScreen(location);
    int viewportTop = location[1] + scroll.getPaddingTop();
    int viewportBottom = location[1] + scroll.getHeight() - scroll.getPaddingBottom();
    status.getLocationOnScreen(location);
    int maxScroll = location[1] - viewportTop;
    stop.getLocationOnScreen(location);
    int minScroll = location[1] + stop.getHeight() - viewportBottom;
    if (minScroll > maxScroll) return;
    // Keep the status and stop action inside the viewport padded for the system bars.
    int margin = GalleryUi.dp(scroll.getContext(), 16);
    if (minScroll > 0) scroll.scrollBy(0, Math.min(minScroll + margin, maxScroll));
    else if (maxScroll < 0) scroll.scrollBy(0, Math.max(maxScroll - margin, minScroll));
  }

  private static void captureCleanupUnknownFailure(
      Instrumentation test,
      E2SyncActivity screen,
      E2Panel panel,
      TextView status,
      Button stop,
      String unknownLabel,
      String stopLabel,
      String suffix,
      AssertionError failure) {
    String name = "amazon-retirement-consent-unknown-failure" + suffix;
    try {
      var diagnostics = new JSONObject();
      AlbumTagEntryChecks.main(
          test,
          () -> {
            var node = stop.createAccessibilityNodeInfo();
            var bounds = new Rect();
            node.getBoundsInScreen(bounds);
            var visibleStatus = new Rect();
            boolean statusOnScreen = status.getGlobalVisibleRect(visibleStatus);
            var visibleStop = new Rect();
            boolean stopOnScreen = stop.getGlobalVisibleRect(visibleStop);
            diagnostics
                .put("windowFocused", screen.hasWindowFocus())
                .put("statusShown", status.isShown())
                .put("statusHeightPx", status.getHeight())
                .put(
                    "statusTextMatches",
                    android.text.TextUtils.equals(unknownLabel, status.getText()))
                .put("statusOnScreen", statusOnScreen)
                .put("statusGlobalVisibleRect", rectCoordinates(visibleStatus))
                .put("statusLiveRegion", status.getAccessibilityLiveRegion())
                .put(
                    "statusLiveRegionPolite",
                    status.getAccessibilityLiveRegion() == View.ACCESSIBILITY_LIVE_REGION_POLITE)
                .put("stopAxVisible", node.isVisibleToUser())
                .put("stopAxEnabled", node.isEnabled())
                .put("stopAxClickable", node.isClickable())
                .put("stopAxLabelMatches", android.text.TextUtils.equals(stopLabel, node.getText()))
                .put("stopAxBoundsInScreen", rectCoordinates(bounds))
                .put("stopOnScreen", stopOnScreen)
                .put("stopGlobalVisibleRect", rectCoordinates(visibleStop))
                .put("touchThresholdPx", GalleryUi.dp(screen, 48))
                .put(
                    "syntheticBucketMatches",
                    BUCKET.equals(AlbumTagEntryChecks.field(panel, "activeBucket")))
                .put("consentUnknown", E2Cleanup.consentUnknown(screen));
            return true;
          });
      Files.write(
          new File(test.getTargetContext().getCacheDir(), name + ".json").toPath(),
          diagnostics.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (Exception | AssertionError diagnosticFailure) {
      failure.addSuppressed(diagnosticFailure);
    }
    try {
      SyncControlChecks.capture(test, name);
    } catch (Exception | AssertionError diagnosticFailure) {
      failure.addSuppressed(diagnosticFailure);
    }
  }

  private static org.json.JSONArray rectCoordinates(Rect bounds) {
    return new org.json.JSONArray()
        .put(bounds.left)
        .put(bounds.top)
        .put(bounds.right)
        .put(bounds.bottom);
  }

  private static List<String> consentUiHistory(Context context) throws Exception {
    try (var archive = new ArchiveStore(context);
        var e2 = E2Settings.openRecords(context)) {
      return List.of(
          archive.get(key(false)).toString(),
          archive.get(key(true)).toString(),
          e2.get(e2Key(false)).toString(),
          e2.get(e2Key(true)).toString());
    }
  }

  private enum RestartConsent {
    REJECTED(false, true, true),
    ACCEPTED(true, true, false),
    REVOKED(false, true, true),
    LEGACY_E2(true, false, false),
    ABSENT(false, false, false),
    FINAL_ABSENT(true, true, false),
    FINAL_RETAINED(false, true, true);

    final boolean eligible;
    final boolean marker;
    final boolean pending;

    RestartConsent(boolean eligible, boolean marker, boolean pending) {
      this.eligible = eligible;
      this.marker = marker;
      this.pending = pending;
    }
  }

  static String writeConsentRestart(Instrumentation test, String run) throws Exception {
    Context target = test.getTargetContext();
    File root = consentRestartRoot(target, run);
    var witness = target.getSharedPreferences(root.getName() + "-witness", Context.MODE_PRIVATE);
    require(!root.exists() && witness.getAll().isEmpty(), "restart fixture already exists");
    Files.createDirectory(root.toPath());
    for (RestartConsent scenario : RestartConsent.values()) {
      File parent = new File(root, scenario.name());
      Files.createDirectory(parent.toPath());
      var settings = consentRestartPrefs(target, root, scenario, "settings");
      var options = consentRestartPrefs(target, root, scenario, "options");
      require(
          settings.getAll().isEmpty()
              && options.getAll().isEmpty()
              && settings
                  .edit()
                  .putBoolean("cleanupEnabled", scenario != RestartConsent.LEGACY_E2)
                  .putInt("retentionDays", 31)
                  .commit(),
          "isolated settings seed " + scenario);
      if (scenario != RestartConsent.ABSENT) {
        require(
            options.edit().putString("cleanupBucket", BUCKET).putInt("cleanupDays", 31).commit(),
            "old cleanup tuple seed " + scenario);
      }
      Context context = isolatedConsentContext(target, settings, options, parent);
      switch (scenario) {
        case REJECTED -> rejectRestartGrant(target, settings, options, parent);
        case ACCEPTED -> {
          rejectRestartGrant(target, settings, options, parent);
          applyConsentAfterPreconditions(context, 31);
        }
        case REVOKED -> {
          applyConsentAfterPreconditions(context, 31);
          require(
              options.edit().putBoolean("enabled", true).putString("destination", BUCKET).commit(),
              "auto-backup revoke seed");
          Context failing =
              isolatedConsentContext(
                  target, settings, withCommitHook(options, editor -> false), parent);
          try {
            E2SyncJob.setEnabled(failing, false);
            throw new AssertionError("failed auto-backup pause reported success");
          } catch (IllegalStateException expected) {
            require(
                !(expected instanceof E2Cleanup.UnknownConsentException)
                    && !E2Cleanup.consentUnknown(failing)
                    && !E2Cleanup.enabled(failing, BUCKET)
                    && E2SyncJob.enabled(context),
                "fenced failed auto-backup pause changed backup or reopened cleanup");
          }
        }
        case LEGACY_E2 -> {
          Context noJournal =
              isolatedConsentContext(target, settings, options, new File(parent, "not-created"));
          require(E2Cleanup.enabled(noJournal, BUCKET), "old e2 consent required a journal");
        }
        case ABSENT ->
            require(
                !E2SyncJob.enabled(context) && !E2Cleanup.enabled(context, BUCKET),
                "absent e2 settings granted consent or backup");
        case FINAL_ABSENT, FINAL_RETAINED ->
            checkFinalActivation(target, settings, options, parent, scenario);
      }
      require(
          options.getBoolean(FRESH_CONSENT, false) == scenario.marker
              && pendingConsent(parent).isFile() == scenario.pending,
          "unexpected persisted writer state " + scenario);
    }
    checkConsentJournalFaults(test, root);
    require(
        witness
            .edit()
            .putString("run", run)
            .putInt("pid", android.os.Process.myPid())
            .putLong("start", android.os.Process.getStartElapsedRealtime())
            .commit(),
        "cannot persist writer identity");
    return "PASS consent writer "
        + run
        + " pid="
        + android.os.Process.myPid()
        + " start="
        + android.os.Process.getStartElapsedRealtime()
        + " left all fixtures intact";
  }

  private static void rejectRestartGrant(
      Context target, SharedPreferences settings, SharedPreferences options, File parent)
      throws Exception {
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    var consent =
        withCommitHook(
            options,
            editor -> {
              if (attempts.incrementAndGet() == 1)
                require(editor.commit(), "real backing grant commit failed");
              return false;
            });
    Context context = isolatedConsentContext(target, settings, consent, parent);
    expectConsentWriteFailure(context, 31);
    require(
        attempts.get() == 2
            && options.getBoolean(FRESH_CONSENT, false)
            && options.edit().putBoolean("fixtureUnrelated", true).commit()
            && !E2Cleanup.consentUnknown(context)
            && !E2Cleanup.enabled(context, BUCKET)
            && pendingConsent(parent).isFile(),
        "rejected grant did not preserve the contradictory disk witness");
  }

  private static void checkFinalActivation(
      Context target,
      SharedPreferences settings,
      SharedPreferences options,
      File parent,
      RestartConsent scenario)
      throws Exception {
    var consent =
        withCommitHook(
            options,
            editor -> {
              require(editor.commit(), "real backing grant commit failed before final activation");
              try {
                if (scenario == RestartConsent.FINAL_ABSENT)
                  Os.remove(pendingConsent(parent).getAbsolutePath());
                else Os.chmod(parent.getAbsolutePath(), 0500);
              } catch (android.system.ErrnoException failure) {
                throw new AssertionError("cannot induce final activation fault", failure);
              }
              return true;
            });
    Context context = isolatedConsentContext(target, settings, consent, parent);
    try {
      if (scenario == RestartConsent.FINAL_RETAINED) {
        expectConsentWriteFailure(context, 31);
        require(!E2Cleanup.consentUnknown(context), "retained veto reported uncertain");
      } else {
        try {
          applyConsentAfterPreconditions(context, 31);
          throw new AssertionError("ambiguous unlink reported success");
        } catch (E2Cleanup.UnknownConsentException expected) {
          require(E2Cleanup.consentUnknown(context), "uncertain activation not exposed");
        }
      }
      require(!E2Cleanup.enabled(context, BUCKET), "final fault activated same process");
    } finally {
      Os.chmod(parent.getAbsolutePath(), 0700);
    }
  }

  private static void checkConsentJournalFaults(Instrumentation test, File root) throws Exception {
    Context target = test.getTargetContext();
    var settings =
        target.getSharedPreferences(root.getName() + "-fault-settings", Context.MODE_PRIVATE);
    var options =
        target.getSharedPreferences(root.getName() + "-fault-options", Context.MODE_PRIVATE);
    File parent = new File(root, "journal-faults");
    File pending = pendingConsent(parent);
    Context context = isolatedConsentContext(target, settings, options, parent);
    try {
      require(
          settings.edit().putBoolean("cleanupEnabled", true).putInt("retentionDays", 31).commit()
              && options
                  .edit()
                  .putString("cleanupBucket", BUCKET)
                  .putInt("cleanupDays", 31)
                  .commit(),
          "journal fault seed");
      var prior = options.getAll();
      expectConsentWriteFailure(context, 31);
      require(options.getAll().equals(prior), "missing parent mutated grant preferences");
      Files.createDirectory(parent.toPath());
      Files.createDirectory(pending.toPath());
      expectConsentWriteFailure(context, 31);
      require(
          options.getAll().equals(prior), "non-regular pending entry mutated grant preferences");
      Files.delete(pending.toPath());
      applyConsentAfterPreconditions(context, 31);
      require(E2Cleanup.enabled(context, BUCKET), "explicit retry failed to activate");

      Os.chmod(parent.getAbsolutePath(), 0400);
      require(!E2Cleanup.enabled(context, BUCKET), "inaccessible journal qualified as absent");
      prior = options.getAll();
      try {
        E2Cleanup.setRetentionDays(context, 90);
        throw new AssertionError("unfenced revocation reported persistent stop");
      } catch (E2Cleanup.UnknownConsentException expected) {
        require(
            E2Cleanup.consentUnknown(context)
                && E2Cleanup.days(context) == 31
                && options.getAll().equals(prior),
            "unknown revocation changed retention or the existing grant");
      }
      Os.chmod(parent.getAbsolutePath(), 0700);
      require(!E2Cleanup.enabled(context, BUCKET), "unfenced revocation lost the process fence");
      E2Cleanup.setEnabled(context, BUCKET, 0);
      require(
          !E2Cleanup.consentUnknown(context)
              && !E2Cleanup.enabled(context, BUCKET)
              && pending.isFile(),
          "explicit persistent stop failed after restoring journal access");
      require(settings.edit().putBoolean("cleanupEnabled", false).commit(), "observer fixture");
      checkFrameworkConsentObserver(test, context, options);
    } finally {
      if (parent.isDirectory()) {
        Os.chmod(parent.getAbsolutePath(), 0700);
        Files.deleteIfExists(pending.toPath());
        Files.delete(parent.toPath());
      }
      target.deleteSharedPreferences(root.getName() + "-fault-settings");
      target.deleteSharedPreferences(root.getName() + "-fault-options");
    }
  }

  private static void checkFrameworkConsentObserver(
      Instrumentation test, Context context, SharedPreferences options) {
    var observed = new AtomicBoolean();
    var failure = new AtomicReference<Throwable>();
    SharedPreferences.OnSharedPreferenceChangeListener listener =
        (changed, key) -> {
          if (FRESH_CONSENT.equals(key)) {
            require(!E2Cleanup.enabled(context, BUCKET), "framework listener saw premature grant");
            observed.set(true);
          }
        };
    options.registerOnSharedPreferenceChangeListener(listener);
    try {
      test.runOnMainSync(
          () -> {
            try {
              applyConsentAfterPreconditions(context, 31);
            } catch (Throwable thrown) {
              failure.set(thrown);
            }
          });
    } finally {
      options.unregisterOnSharedPreferenceChangeListener(listener);
    }
    if (failure.get() != null) throw new AssertionError("observer grant failed", failure.get());
    require(
        observed.get() && E2Cleanup.enabled(context, BUCKET), "framework listener not exercised");
  }

  static String readConsentRestart(Instrumentation test, String run) throws Exception {
    Context target = test.getTargetContext();
    File root = consentRestartRoot(target, run);
    var witness = target.getSharedPreferences(root.getName() + "-witness", Context.MODE_PRIVATE);
    int writerPid = witness.getInt("pid", 0);
    long writerStart = witness.getLong("start", 0);
    int readerPid = android.os.Process.myPid();
    long readerStart = android.os.Process.getStartElapsedRealtime();
    require(
        run.equals(witness.getString("run", ""))
            && writerPid > 0
            && writerStart > 0
            && (writerPid != readerPid || writerStart != readerStart),
        "reader did not start in an independent process for " + run);
    for (RestartConsent scenario : RestartConsent.values()) {
      File parent = new File(root, scenario.name());
      require(parent.isDirectory(), "writer journal is missing " + scenario);
      var settings = consentRestartPrefs(target, root, scenario, "settings");
      var options = consentRestartPrefs(target, root, scenario, "options");
      Context context = isolatedConsentContext(target, settings, options, parent);
      require(
          E2Cleanup.enabled(context, BUCKET) == scenario.eligible
              && !E2Cleanup.consentUnknown(context)
              && options.getBoolean(FRESH_CONSENT, false) == scenario.marker
              && pendingConsent(parent).isFile() == scenario.pending
              && E2Cleanup.days(context) == 31
              && settings.getBoolean("cleanupEnabled", false)
                  == (scenario != RestartConsent.LEGACY_E2),
          "unexpected fresh-process consent " + scenario);
      if (scenario == RestartConsent.REJECTED) {
        require(
            BUCKET.equals(options.getString("cleanupBucket", ""))
                && options.getInt("cleanupDays", 0) == 31
                && options.getBoolean("fixtureUnrelated", false),
            "the old tuple and contradictory rejected marker did not survive");
      }
      if (scenario == RestartConsent.REVOKED) {
        require(
            E2SyncJob.enabled(context)
                && BUCKET.equals(options.getString("cleanupBucket", ""))
                && options.getInt("cleanupDays", 0) == 31,
            "failed auto-backup pause unexpectedly changed the persisted tuple");
      }
      if (scenario == RestartConsent.ABSENT) {
        require(
            options.getAll().isEmpty()
                && !E2SyncJob.enabled(context)
                && E2SyncJob.ensureRecoverySchedule(context),
            "absent cohort changed or requested recovery scheduling");
      }
    }
    for (RestartConsent scenario : RestartConsent.values()) {
      File parent = new File(root, scenario.name());
      Files.deleteIfExists(pendingConsent(parent).toPath());
      Files.delete(parent.toPath());
      target.deleteSharedPreferences(consentRestartPrefsName(root, scenario, "settings"));
      target.deleteSharedPreferences(consentRestartPrefsName(root, scenario, "options"));
    }
    target.deleteSharedPreferences(root.getName() + "-witness");
    Files.delete(root.toPath());
    return "PASS consent reader "
        + run
        + " writer pid="
        + writerPid
        + " start="
        + writerStart
        + " reader pid="
        + readerPid
        + " start="
        + readerStart
        + " verified rejected, accepted, revoked, legacy-e2, absent and both final outcomes";
  }

  private static File consentRestartRoot(Context target, String run) {
    require(run.matches("[a-z0-9][a-z0-9-]{0,31}"), "requires -e consent-run with a unique ID");
    require(
        target.getSystemService(ConnectivityManager.class).getActiveNetwork() == null,
        "restart proof requires an offline AVD");
    return new File(target.getFilesDir(), FIXTURE + "-consent-restart-" + run);
  }

  private static SharedPreferences consentRestartPrefs(
      Context target, File root, RestartConsent scenario, String kind) {
    return target.getSharedPreferences(
        consentRestartPrefsName(root, scenario, kind), Context.MODE_PRIVATE);
  }

  private static String consentRestartPrefsName(File root, RestartConsent scenario, String kind) {
    return root.getName() + "-" + scenario.name() + "-" + kind;
  }

  private static void checkRetentionWrites(Context target) throws Exception {
    var settings =
        target.getSharedPreferences(FIXTURE + "-retention-settings", Context.MODE_PRIVATE);
    var e2 = target.getSharedPreferences(FIXTURE + "-retention-consent", Context.MODE_PRIVATE);
    require(
        settings.edit().clear().commit() && e2.edit().clear().commit(),
        "isolated test reset failed");
    var failSettings = new AtomicBoolean();
    var failConsent = new AtomicBoolean();
    File parent = new File(target.getFilesDir(), FIXTURE + "-unused-retention-journal");
    Context context =
        isolatedConsentContext(
            target,
            failNextCommit(settings, failSettings),
            failNextCommit(e2, failConsent),
            parent);
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

  private enum CommitFault {
    FALSE_AFTER_WRITE,
    THROW_AFTER_WRITE,
    FALSE_BEFORE_WRITE
  }

  private static void checkCleanupConsentWrites(Context target) throws Exception {
    var settings =
        target.getSharedPreferences(FIXTURE + "-new-consent-settings", Context.MODE_PRIVATE);
    var options =
        target.getSharedPreferences(FIXTURE + "-new-consent-options", Context.MODE_PRIVATE);
    require(
        settings.edit().clear().commit() && options.edit().clear().commit(),
        "reset consent fixture");
    require(
        settings.edit().putBoolean("cleanupEnabled", true).putInt("retentionDays", 31).commit()
            && options.edit().putString("cleanupBucket", BUCKET).putInt("cleanupDays", 31).commit(),
        "seed contradictory consent");
    var faults = new ArrayDeque<CommitFault>();
    var beforeAcknowledgement = new AtomicReference<Runnable>();
    SharedPreferences consent =
        withCommitHook(
            options,
            editor -> {
              CommitFault fault = faults.poll();
              if (fault == CommitFault.FALSE_BEFORE_WRITE) return false;
              boolean saved = editor.commit();
              Runnable observer = beforeAcknowledgement.getAndSet(null);
              if (observer != null) observer.run();
              if (fault == CommitFault.FALSE_AFTER_WRITE) return false;
              if (fault == CommitFault.THROW_AFTER_WRITE)
                throw new IllegalStateException("synthetic consent acknowledgement failure");
              return saved;
            });
    File parent =
        Files.createTempDirectory(target.getFilesDir().toPath(), FIXTURE + "-consent").toFile();
    Context context = isolatedConsentContext(target, settings, consent, parent);
    try {
      require(!E2Cleanup.enabled(context, BUCKET), "old contradictory consent must be blocked");
      faults.add(CommitFault.FALSE_AFTER_WRITE);
      faults.add(CommitFault.FALSE_BEFORE_WRITE);
      expectConsentWriteFailure(context, 31);
      require(
          options.getBoolean(FRESH_CONSENT, false), "failed confirmation left changed preferences");
      require(options.edit().putBoolean("fixtureUnrelated", true).commit(), "unrelated preference");
      require(
          !E2Cleanup.enabled(context, BUCKET) && pendingConsent(parent).isFile(),
          "unacknowledged grant must retain its veto");
      applyConsentAfterPreconditions(context, 0);
      require(!options.contains(FRESH_CONSENT), "durable revoke left the marker behind");

      faults.add(CommitFault.THROW_AFTER_WRITE);
      expectConsentWriteFailure(context, 31);
      require(
          !options.contains(FRESH_CONSENT) && !E2Cleanup.enabled(context, BUCKET),
          "throwing grant wasn't revoked");

      beforeAcknowledgement.set(
          () ->
              require(!E2Cleanup.enabled(context, BUCKET), "grant visible before acknowledgement"));
      applyConsentAfterPreconditions(context, 31);
      require(
          E2Cleanup.enabled(context, BUCKET) && options.getBoolean(FRESH_CONSENT, false),
          "explicit acknowledged consent wasn't accepted");
      require(
          E2Cleanup.setRetentionDays(context, 90)
              && !options.contains(FRESH_CONSENT)
              && !E2Cleanup.enabled(context, BUCKET),
          "new retention failed to revoke the fresh consent");
      applyConsentAfterPreconditions(context, 90);
      E2Cleanup.setEnabled(context, BUCKET, 0);
      require(
          !options.contains(FRESH_CONSENT) && !E2Cleanup.enabled(context, BUCKET),
          "disabling cleanup failed to revoke the fresh consent");

      applyConsentAfterPreconditions(context, 90);
      faults.add(CommitFault.THROW_AFTER_WRITE);
      expectConsentWriteFailure(context, 0);
      require(
          !options.contains(FRESH_CONSENT)
              && !E2Cleanup.enabled(context, BUCKET)
              && settings.getBoolean("cleanupEnabled", false)
              && options.getBoolean("fixtureUnrelated", false),
          "revoke failure granted cleanup or touched unrelated settings");
    } finally {
      faults.clear();
      beforeAcknowledgement.set(null);
      try {
        applyConsentAfterPreconditions(context, 0);
      } finally {
        require(
            settings.edit().clear().commit() && options.edit().clear().commit(),
            "clear consent fixture");
        Files.deleteIfExists(pendingConsent(parent).toPath());
        Files.delete(parent.toPath());
      }
    }
  }

  private static void expectConsentWriteFailure(Context context, int retention) throws Exception {
    try {
      applyConsentAfterPreconditions(context, retention);
      throw new AssertionError("consent write was not acknowledged");
    } catch (IOException expected) {
      // Failure tests exercise the storage step without granting deletion permission to the AVD.
    }
  }

  private static void applyConsentAfterPreconditions(Context context, int retention)
      throws Exception {
    var write =
        E2Cleanup.class.getDeclaredMethod(
            "writeConsent", Context.class, SharedPreferences.class, String.class, int.class);
    write.setAccessible(true);
    try {
      synchronized (E2Settings.DESTINATION_LOCK) {
        write.invoke(null, context, E2AutoSync.state(context), BUCKET, retention);
      }
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof IOException io) throw io;
      if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
      throw failure;
    }
  }

  private static File pendingConsent(File directory) {
    return new File(directory, PENDING_CONSENT);
  }

  private static Context isolatedConsentContext(
      Context target, SharedPreferences settings, SharedPreferences consent, File parent) {
    return new android.content.ContextWrapper(target) {
      @Override
      public File getDataDir() {
        return parent;
      }

      @Override
      public File getFilesDir() {
        return parent;
      }

      @Override
      public File getNoBackupFilesDir() {
        return parent;
      }

      @Override
      public Object getSystemService(String name) {
        if (JOB_SCHEDULER_SERVICE.equals(name)) throw new AssertionError("unexpected scheduling");
        return super.getSystemService(name);
      }

      @Override
      public SharedPreferences getSharedPreferences(String name, int mode) {
        return switch (name) {
          case "settings" -> settings;
          case "e2_auto_sync" -> consent;
          default -> throw new AssertionError("unexpected preferences: " + name);
        };
      }
    };
  }

  private static SharedPreferences failNextCommit(
      SharedPreferences delegate, AtomicBoolean failOnce) {
    return withCommitHook(
        delegate,
        editor -> {
          boolean saved = editor.commit();
          return !failOnce.getAndSet(false) && saved;
        });
  }

  private static SharedPreferences withCommitHook(
      SharedPreferences delegate, java.util.function.Predicate<SharedPreferences.Editor> commit) {
    return (SharedPreferences)
        java.lang.reflect.Proxy.newProxyInstance(
            AmazonRetirementUpgradeChecks.class.getClassLoader(),
            new Class<?>[] {SharedPreferences.class},
            (ignored, method, args) -> {
              Object result = method.invoke(delegate, args);
              if (!"edit".equals(method.getName())) return result;
              return java.lang.reflect.Proxy.newProxyInstance(
                  AmazonRetirementUpgradeChecks.class.getClassLoader(),
                  new Class<?>[] {SharedPreferences.Editor.class},
                  (editor, editMethod, values) -> {
                    if ("commit".equals(editMethod.getName()))
                      return commit.test((SharedPreferences.Editor) result);
                    Object value = editMethod.invoke(result, values);
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
