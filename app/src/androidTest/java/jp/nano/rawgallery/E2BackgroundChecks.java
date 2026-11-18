package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Opt-in live e2 check, restricted to the dedicated verification APK and emulator. */
final class E2BackgroundChecks {
  private static final String APP = "jp.nano.rawgallery.e2verify";
  private static final String BUCKET = "loupe-test";
  private static final String CATALOG_JPEG = "loupe-e2-catalog-stage3.jpg";
  private static final String FIXTURE_APP = "jp.nano.rawgallery.e2fixture";
  private static final String SHARED_STALE = "loupe-shared-changed.jpg";
  private static final String SHARED_MATCH = CATALOG_JPEG;

  static String product(Instrumentation test, String mode, String token) throws Exception {
    Context c = test.getTargetContext();
    String user = new String(shell(test, "am get-current-user")).trim();
    if (!user.matches("[1-9][0-9]{0,3}") || android.os.Process.myUid() / 100000 != Integer.parseInt(user)
        || token == null || !token.matches("[0-9a-f]{12}")
        || !("seed".equals(mode) ? FIXTURE_APP : APP).equals(c.getPackageName()))
      throw new AssertionError("Requires a separate foreground Android user and verification APK");
    String old = "loupe-cleanup-" + token + ".jpg";
    String recent = "loupe-recent-" + token + ".jpg";
    List<E2AutoSync.Candidate> rows = E2AutoSync.candidates(c);
    if ("seed".equals(mode)) {
      if (!rows.isEmpty()) throw new AssertionError("Expected an empty photo collection");
      original(c, old, productJpeg(c, true));
      original(c, recent, productJpeg(c, false));
      return "PASS e2-cleanup-product-seed: two synthetic JPEGs";
    }
    if ("stop".equals(mode)) {
      if (BUCKET.equals(E2Settings.configuredBucket(c))) {
        E2SyncJob.setEnabled(c, false);
        if (!c.getSharedPreferences("e2_connection", Context.MODE_PRIVATE).edit().clear().commit())
          throw new AssertionError("Temporary e2 settings could not be removed");
      }
      return "PASS e2-cleanup-product-stop: trial connection stopped";
    }
    if ("sync".equals(mode)) {
      if (rows.size() != 2 || !E2Settings.configuredBucket(c).isEmpty())
        throw new AssertionError("Expected two fixtures and an unset e2 connection");
      for (E2AutoSync.Candidate row : rows) {
        if (!(old.equals(row.photo().name) || recent.equals(row.photo().name)))
          throw new AssertionError("Unexpected photo in test user");
        if (row.generation() <= 0 || !"Pictures/LoupeE2Verify/".equals(row.photo().folder)
            || !MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(row.volume()))
          throw new AssertionError("Fixture must have a current generation on primary storage");
        verifyFixtureBytes(c, row.photo().uri, productJpeg(c, old.equals(row.photo().name)));
      }
      if (rows.stream().noneMatch(row -> old.equals(row.photo().name) && row.photo().taken > 0
          && row.photo().taken < 1600000000000L))
        throw new AssertionError("Old synthetic date was not indexed");
      List<String> config = sharedCloudCredentials(c);
      Activity activity = test.startActivitySync(new Intent(c, E2SyncActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      View root = activity.getWindow().getDecorView();
      test.runOnMainSync(() -> {
        ((EditText) find(root, "バケット", true)).setText(BUCKET);
        ((EditText) find(root, "アクセスキー", true)).setText(config.get(1));
        ((EditText) find(root, "シークレットキー", true)).setText(config.get(2));
        find(root, "接続して保存", false).performClick();
      });
      waitFor("e2 connect", 30000, () -> onUi(test, () -> find(root, "接続しました", false) != null));
      shell(test, "svc data disable");
      shell(test, "svc wifi disable");
      waitFor("network disabled", 20000, () -> !hasUnmeteredNetwork(c));
      clickView(test, root, "開始", false);
      SystemClock.sleep(500);
      capture(test, c, "e2-product-backup.png");
      waitFor("backup consent", 12000,
          () -> clickPositive(test, "自動バックアップを開始しますか？"));
      waitFor("backup enabled", 10000, () -> E2SyncJob.enabled(c));
      clickView(test, root, "端末原本の整理を有効にする", false);
      waitFor("cleanup consent visible", 10000, () -> activeContains(test, APP, "端末原本の整理を有効にしますか？"));
      SystemClock.sleep(350);
      capture(test, c, "e2-product-consent.png");
      waitFor("cleanup consent", 10000, () -> clickPositive(test, "端末原本の整理を有効にしますか？"));
      waitFor("cleanup enabled", 10000, () -> E2Cleanup.enabled(c, BUCKET));
      SystemClock.sleep(350);
      capture(test, c, "e2-product-enabled.png");
      shell(test, "am start --user " + user + " -a android.settings.SETTINGS");
      waitFor("background", 12000, () -> !activePackage(test, APP));
      shell(test, "svc wifi enable");
      waitFor("native background cleanup", 100000, () -> {
        try (ArchiveStore store = E2Settings.openRecords(c)) {
          return store.all().stream().anyMatch(r -> old.equals(r.optString("name")) && E2Cleanup.released(r));
        } catch (Exception ignored) { return false; }
      });
      List<E2AutoSync.Candidate> left = E2AutoSync.candidates(c);
      if (left.size() != 1 || !recent.equals(left.get(0).photo().name)
          || new File(android.os.Environment.getExternalStoragePublicDirectory(
              android.os.Environment.DIRECTORY_PICTURES), "LoupeE2Verify/" + old).exists())
        throw new AssertionError("Expected only the recent synthetic original to remain");
      verifyFixtureBytes(c, left.get(0).photo().uri, productJpeg(c, false));
      if (activePackage(test, APP)) throw new AssertionError("The cleanup did not stay in the background");
      return "PASS e2-cleanup-product-sync: scheduled e2 cleanup removed only the old synthetic JPEG";
    }
    if ("restore".equals(mode) && E2Settings.configuredBucket(c).isEmpty()) {
      List<String> config = sharedCloudCredentials(c);
      try (E2Settings settings = new E2Settings(c)) {
        settings.connect(BUCKET, config.get(1), config.get(2));
      }
    }
    if (!BUCKET.equals(E2Settings.configuredBucket(c))) throw new AssertionError("Requires loupe-test");
    if ("offline".equals(mode)) {
      ConnectivityManager manager = c.getSystemService(ConnectivityManager.class);
      if (manager.getActiveNetwork() != null || rows.size() != 1 || !recent.equals(rows.get(0).photo().name))
        throw new AssertionError("Expected offline and only the preserved synthetic original");
      PhotoRepository repository = new PhotoRepository(c);
      PhotoRepository.Photo released = repository.scan("すべて").stream()
          .filter(p -> old.equals(p.name) && E2Cleanup.archived(p.uri)).findFirst().orElseThrow();
      PhotoRepository.Preview preview = E2Cleanup.preview(c, released.uri);
      int edge = Math.max(preview.width, preview.height);
      if (edge != 256) throw new AssertionError("Unexpected persisted thumbnail dimensions");
      Activity gallery = test.startActivitySync(new Intent(c, MainActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      View root = gallery.getWindow().getDecorView();
      waitFor("photo tab", 15000, () -> onUi(test, () -> {
        View navigation = root.findViewWithTag("library-navigation");
        return navigation instanceof LibraryTabs tabs && tabs.isEnabled()
            && (tabs.selectedDestination() == 0 || tabs.destinationView(0).performClick());
      }));
      waitFor("archived thumbnail bound to the gallery", 22000, () -> onUi(test, () -> {
        View cell = findDescriptionPrefix(root, old);
        return cell != null && (cell instanceof android.widget.ImageView image
            ? image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable
                && "表示済み".contentEquals(String.valueOf(image.getStateDescription()))
            : hasBoundImage(cell));
      }));
      SystemClock.sleep(400);
      capture(test, c, "e2-product-offline.png");
      return "PASS e2-cleanup-product-offline: persisted thumbnail bound to the gallery; edge=" + edge;
    }
    if (!"restore".equals(mode)) throw new AssertionError("Unknown product verification mode");
    Activity activity = test.startActivitySync(new Intent(c, E2SyncActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    View root = activity.getWindow().getDecorView();
    clickView(test, root, "保存した原本を更新", false);
    clickView(test, root, old + "を復元", true);
    waitFor("Android save dialog", 25000, () -> {
      AccessibilityNodeInfo picker = test.getUiAutomation().getRootInActiveWindow();
      if (picker == null || !List.of("com.google.android.documentsui", "com.android.documentsui")
          .contains(String.valueOf(picker.getPackageName()))) return false;
      for (AccessibilityNodeInfo button : picker.findAccessibilityNodeInfosByViewId("android:id/button1")) {
        if (!button.isEnabled()) continue;
        android.graphics.Rect bounds = new android.graphics.Rect();
        button.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) continue;
        try {
          shell(test, "input tap " + bounds.centerX() + " " + (bounds.top + bounds.height() / 4));
          return true;
        } catch (Exception ignored) { return false; }
      }
      return false;
    });
    waitFor("restore success", 25000, () -> activeContains(test, APP, "原本を復元しました"));
    String downloads = new String(shell(test, "content query --user " + user
        + " --uri content://media/external_primary/downloads --projection _id:_display_name"));
    java.util.regex.Matcher row = java.util.regex.Pattern.compile(
        "_id=(\\d+), _display_name=" + java.util.regex.Pattern.quote(old) + "(?=\\s|$)").matcher(downloads);
    if (!row.find()) throw new AssertionError("Restored fixture is not in Downloads");
    byte[] restored = shell(test, "content read --user " + user
        + " --uri content://media/external_primary/downloads/" + row.group(1));
    if (!Arrays.equals(productJpeg(c, true), restored)) throw new AssertionError("Restored JPEG differs from the original");
    SystemClock.sleep(350);
    capture(test, c, "e2-product-restored.png");
    String sha = E2Archive.readHash(new java.io.ByteArrayInputStream(restored), null, restored.length);
    return "PASS e2-cleanup-product-restore: bytes=" + restored.length + " sha256=" + sha;
  }

  private static byte[] productJpeg(Context c, boolean old) throws Exception {
    byte[] bytes = jpeg(1536, 960);
    if (!old) return bytes;
    File file = File.createTempFile("cleanup-fixture", ".jpg", c.getCacheDir());
    try {
      Files.write(file.toPath(), bytes);
      android.media.ExifInterface exif = new android.media.ExifInterface(file.getPath());
      exif.setAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:01 12:00:00");
      exif.setAttribute(android.media.ExifInterface.TAG_DATETIME, "2020:01:01 12:00:00");
      exif.setAttribute(android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00");
      exif.saveAttributes();
      return Files.readAllBytes(file.toPath());
    } finally {
      Files.deleteIfExists(file.toPath());
    }
  }

  private static boolean clickPositive(Instrumentation test, String title) {
    AccessibilityNodeInfo root = test.getUiAutomation().getRootInActiveWindow();
    if (root == null || !APP.contentEquals(root.getPackageName()) || node(root, title) == null) return false;
    for (AccessibilityNodeInfo button : root.findAccessibilityNodeInfosByViewId("android:id/button1"))
      if (button.isEnabled() && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
    return false;
  }

  private static View findDescriptionPrefix(View view, String label) {
    if (view.getContentDescription() != null && view.getContentDescription().toString().startsWith(label))
      return view;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findDescriptionPrefix(group.getChildAt(i), label);
        if (found != null) return found;
      }
    return null;
  }

  private static boolean hasBoundImage(View view) {
    if (view instanceof android.widget.ImageView image)
      return image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable
          && "表示済み".contentEquals(String.valueOf(image.getStateDescription()));
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        if (hasBoundImage(group.getChildAt(i))) return true;
    return false;
  }

  static String sharedCleanup(Instrumentation test, String mode, long oldGeneration,
      long staleId, long matchId)
      throws Exception {
    Context context = test.getTargetContext();
    if (!"ranchu".equals(Build.HARDWARE)) throw new AssertionError("Requires an isolated emulator");
    if ("e2-cleanup-shared-seed".equals(mode)) return sharedSeed(context);
    if (!APP.equals(context.getPackageName())) throw new AssertionError("Requires the verification APK");
    boolean live = "e2-cleanup-shared-live".equals(mode);
    List<String> cloudConfig = live ? sharedCloudCredentials(context) : List.of();
    if (staleId <= 0 || matchId <= 0 || staleId == matchId)
      throw new AssertionError("Requires the verification APK and both synthetic IDs");
    List<E2AutoSync.Candidate> rows = E2AutoSync.candidates(context);
    E2AutoSync.Candidate changed = exactFixture(rows, SHARED_STALE, staleId);
    if ("e2-cleanup-shared-preview".equals(mode)) {
      if (rows.size() != 1) throw new AssertionError("Expected only the preserved synthetic original");
      verifyFixtureBytes(context, changed.photo().uri);
      Bitmap preview = android.graphics.BitmapFactory.decodeFile(sharedPreview(context).getPath());
      if (preview == null) throw new AssertionError("Retained thumbnail is unreadable");
      try {
        if (Math.max(preview.getWidth(), preview.getHeight()) != 64)
          throw new AssertionError("The durable thumbnail has unexpected dimensions");
      } finally {
        preview.recycle();
      }
      return "PASS e2-cleanup-shared-preview: durable thumbnail survived process restart";
    }

    if (rows.size() != 2 || oldGeneration <= 0 || changed.generation() <= oldGeneration)
      throw new AssertionError("Expected only both seeded photos and a genuinely stale generation");
    E2AutoSync.Candidate exact = exactFixture(rows, SHARED_MATCH, matchId);
    Uri changedUri = changed.photo().uri, exactUri = exact.photo().uri;
    verifyFixtureBytes(context, changedUri);
    verifyFixtureBytes(context, exactUri);
    android.content.ContentResolver resolver = context.getContentResolver();
    String condition = MediaStore.MediaColumns.GENERATION_MODIFIED + "=?";
    String[] current = {Long.toString(exact.generation())};
    if ("e2-cleanup-shared-denied".equals(mode)) {
      if (android.os.Environment.isExternalStorageManager())
        throw new AssertionError("The all-files permission must be off");
      int removed;
      try {
        removed = resolver.delete(exactUri, condition, current);
      } catch (SecurityException denied) {
        removed = 0;
      }
      if (removed != 0) throw new AssertionError("Delete unexpectedly succeeded with read-only access");
      verifyFixtureBytes(context, exactUri);
      return "PASS e2-cleanup-shared-denied: read access alone preserved another app's JPEG";
    }
    if ((!live && !"e2-cleanup-shared-allowed".equals(mode))
        || !android.os.Environment.isExternalStorageManager())
      throw new AssertionError("The test mode and all-files permission are required");

    android.content.SharedPreferences connection =
        context.getSharedPreferences("e2_connection", Context.MODE_PRIVATE);
    boolean probeConnection = false;
    try (E2Photos photos = new E2Photos(context)) {
      JSONObject remote = null;
      if (live) {
        if (!connection.getAll().isEmpty() || !photos.settings.records.all().isEmpty())
          throw new AssertionError("The probe requires an empty local connection and archive");
        probeConnection = true;
        remote = sharedCloudReference(photos, cloudConfig);
        verifyCloudReference(photos, remote);
      }
      int stale = resolver.delete(changedUri, condition, new String[]{Long.toString(oldGeneration)});
      if (stale != 0) throw new AssertionError("A stale revision was deleted");
      verifyFixtureBytes(context, changedUri);
      removeSharedFixture(context, exactUri, condition, current);
      if (remote != null) verifyCloudReference(photos, remote);
    } finally {
      if (probeConnection && !connection.edit().clear().commit())
        throw new AssertionError("Could not clear the temporary e2 connection");
    }
    verifyFixtureBytes(context, changedUri);
    if (live) return "PASS e2-cleanup-shared-live: same loupe-test version read and matched "
        + "before and after removing only the verified synthetic JPEG";
    return "PASS e2-cleanup-shared-allowed: stale photo retained; matching photo removed after thumbnail";
  }

  private static void removeSharedFixture(Context context, Uri exactUri, String condition,
      String[] current) throws Exception {
    File folder = new File(android.os.Environment.getExternalStoragePublicDirectory(
        android.os.Environment.DIRECTORY_PICTURES), "LoupeE2Verify");
    File exactFile = new File(folder, SHARED_MATCH);
    if (!Arrays.equals(jpeg(), Files.readAllBytes(exactFile.toPath())))
      throw new AssertionError("Unexpected backing file");
    File thumbnail = sharedPreview(context);
    CompactPreview.save(new PhotoRepository.Preview(exactFile, "JPEG", 160, 96, 1), thumbnail, 64);
    if (!thumbnail.isFile() || thumbnail.length() <= 0)
      throw new AssertionError("Durable thumbnail was not written before deletion");
    if (context.getContentResolver().delete(exactUri, condition, current) != 1 || exactFile.exists())
      throw new AssertionError("A matching synthetic original did not disappear");
  }

  private static List<String> sharedCloudCredentials(Context context) throws Exception {
    File input = new File(context.getCacheDir(), "e2-background-credentials");
    List<String> config;
    try {
      if (input.length() > 512) throw new AssertionError("Invalid credential input");
      config = Files.readAllLines(input.toPath());
    } finally {
      Files.deleteIfExists(input.toPath());
    }
    if (config.size() != 3 || !BUCKET.equals(config.get(0)))
      throw new AssertionError("The probe requires a fresh loupe-test connection");
    return config;
  }

  private static JSONObject sharedCloudReference(E2Photos photos, List<String> config)
      throws Exception {
    photos.settings.connect(BUCKET, config.get(1), config.get(2));
    E2RecoveryCatalog.Snapshot snapshot = photos.loadCatalog(BUCKET);
    List<JSONObject> rows = photos.saved(BUCKET);
    if (snapshot == null || rows.size() != 1 || !CATALOG_JPEG.equals(rows.get(0).getString("name")))
      throw new AssertionError("Expected the existing one-photo test catalog");
    return rows.get(0);
  }

  private static void verifyCloudReference(E2Photos photos, JSONObject record) throws Exception {
    byte[] expected = jpeg();
    if (record.getLong("size") != expected.length
        || !E2Archive.sha256(expected).equals(record.getString("sha")))
      throw new AssertionError("Cloud catalog differs from synthetic original");
    File downloaded = photos.downloadRecord(record);
    try {
      if (!Arrays.equals(expected, Files.readAllBytes(downloaded.toPath())))
        throw new AssertionError("Versioned restoration differs from synthetic original");
    } finally {
      E2Photos.discard(downloaded);
    }
  }

  private static String sharedSeed(Context context) throws Exception {
    if (!FIXTURE_APP.equals(context.getPackageName()) || !E2AutoSync.candidates(context).isEmpty())
      throw new AssertionError("Requires the empty, separate fixture APK");
    byte[] expected = jpeg();
    Uri source = original(context, "loupe-shared-before.jpg", expected);
    Uri exact = original(context, SHARED_MATCH, expected);
    fixtureGeneration(context.getContentResolver(), exact, SHARED_MATCH, FIXTURE_APP);
    long generation = fixtureGeneration(context.getContentResolver(), source, "loupe-shared-before.jpg",
        FIXTURE_APP);
    ContentValues rename = new ContentValues();
    rename.put(MediaStore.MediaColumns.DISPLAY_NAME, SHARED_STALE);
    if (context.getContentResolver().update(source, rename, null, null) != 1
        || fixtureGeneration(context.getContentResolver(), source, SHARED_STALE, FIXTURE_APP) <= generation)
      throw new AssertionError("Expected a new generation after the fixture rename");
    return "PASS e2-cleanup-shared-seed: stale_generation=" + generation + " stale_id="
        + android.content.ContentUris.parseId(source) + " match_id="
        + android.content.ContentUris.parseId(exact);
  }

  private static E2AutoSync.Candidate exactFixture(List<E2AutoSync.Candidate> rows, String name,
      long seededId) {
    E2AutoSync.Candidate found = null;
    for (E2AutoSync.Candidate row : rows) {
      if (!name.equals(row.photo().name)) continue;
      if (found != null || seededId != android.content.ContentUris.parseId(row.photo().uri)
          || !"Pictures/LoupeE2Verify/".equals(row.photo().folder)
          || !MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(row.volume()))
        throw new AssertionError("Unexpected synthetic fixture identity");
      found = row;
    }
    if (found == null) throw new AssertionError("Synthetic fixture is missing");
    return found;
  }

  private static void verifyFixtureBytes(Context context, Uri uri) throws Exception {
    verifyFixtureBytes(context, uri, jpeg());
  }

  private static void verifyFixtureBytes(Context context, Uri uri, byte[] expected) throws Exception {
    try (InputStream input = context.getContentResolver().openInputStream(uri)) {
      if (input == null) throw new AssertionError("Synthetic original is missing");
      byte[] actual = new byte[expected.length];
      new java.io.DataInputStream(input).readFully(actual);
      if (input.read() != -1 || !Arrays.equals(expected, actual))
        throw new AssertionError("Synthetic original bytes did not match");
    }
  }

  private static File sharedPreview(Context context) {
    return new File(context.getFilesDir(), "loupe-shared-cleanup-test.webp");
  }

  static String cleanupBoundary(Instrumentation test) throws Exception {
    Context context = test.getTargetContext();
    if (!APP.equals(context.getPackageName()) || !"ranchu".equals(Build.HARDWARE)
        || !E2AutoSync.candidates(context).isEmpty())
      throw new AssertionError("Requires an empty isolated emulator APK");
    byte[] expected = jpeg();
    Uri files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
    String changedName = "loupe-cleanup-retain-" + UUID.randomUUID() + ".jpg";
    String eligibleName = "loupe-cleanup-remove-" + UUID.randomUUID() + ".jpg";
    Uri changedImage = original(context, changedName, expected);
    Uri eligibleImage = original(context, eligibleName, expected);
    Uri changed = android.content.ContentUris.withAppendedId(files,
        android.content.ContentUris.parseId(changedImage));
    Uri eligible = android.content.ContentUris.withAppendedId(files,
        android.content.ContentUris.parseId(eligibleImage));
    android.content.ContentResolver resolver = context.getContentResolver();
    long oldGeneration = fixtureGeneration(resolver, changed, changedName, APP);
    long unchangedGeneration = fixtureGeneration(resolver, eligible, eligibleName, APP);
    File folder = new File(android.os.Environment.getExternalStoragePublicDirectory(
        android.os.Environment.DIRECTORY_PICTURES), "LoupeE2Verify");
    File eligibleFile = new File(folder, eligibleName);
    if (!eligibleFile.isFile() || !Arrays.equals(expected, Files.readAllBytes(eligibleFile.toPath())))
      throw new AssertionError("The exact synthetic JPEG must be readable as a file before removal");
    String changedAfterRename = "renamed-" + changedName;
    ContentValues metadata = new ContentValues();
    metadata.put(MediaStore.MediaColumns.DISPLAY_NAME, changedAfterRename);
    if (resolver.update(changedImage, metadata, null, null) != 1
        || fixtureGeneration(resolver, changed, changedAfterRename, APP) == oldGeneration)
      throw new AssertionError("The synthetic MediaStore revision did not change");

    String generationMatches = MediaStore.MediaColumns.GENERATION_MODIFIED + "=?";
    int stale = resolver.delete(changed, generationMatches, new String[]{Long.toString(oldGeneration)});
    if (stale != 0) throw new AssertionError("MediaStore accepted a stale generation condition");
    try (InputStream source = resolver.openInputStream(changed)) {
      if (source == null || !Arrays.equals(expected, source.readAllBytes()))
        throw new AssertionError("The stale original bytes did not survive");
    }

    int removed = resolver.delete(eligible, generationMatches,
        new String[]{Long.toString(unchangedGeneration)});
    if (removed != 1) throw new AssertionError("An exact generation did not delete one owned fixture");
    try (android.database.Cursor found = resolver.query(eligible,
        new String[]{MediaStore.MediaColumns._ID}, null, null, null)) {
      if (found == null || found.moveToFirst()) throw new AssertionError("Deleted fixture is still indexed");
    }
    try (InputStream result = resolver.openInputStream(eligible)) {
      if (result != null) throw new AssertionError("Deleted fixture is still readable");
    } catch (java.io.FileNotFoundException expectedMissing) {
      // Confirm disappearance of the actual file below, independent of MediaStore lookup.
    }
    if (eligibleFile.exists()) throw new AssertionError("The original backing file still exists");
    return "PASS e2-cleanup-boundary: stale MediaStore generation preserved the original; "
        + "matching generation deleted one owned synthetic JPEG";
  }

  private static long fixtureGeneration(android.content.ContentResolver resolver, Uri uri,
      String name, String owner) {
    try (android.database.Cursor row = resolver.query(uri,
        new String[]{MediaStore.MediaColumns.GENERATION_MODIFIED, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.VOLUME_NAME}, null, null, null)) {
      if (row == null || !row.moveToFirst() || row.getLong(0) <= 0
          || !name.equals(row.getString(1)) || !"Pictures/LoupeE2Verify/".equals(row.getString(2))
          || !owner.equals(row.getString(3)) || !MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(row.getString(4)))
        throw new AssertionError("An exact owned synthetic photo and its generation are required");
      return row.getLong(0);
    }
  }

  static String catalog(Instrumentation test, String mode) throws Exception {
    Context context = test.getTargetContext();
    if (!APP.equals(context.getPackageName()) || !"ranchu".equals(Build.HARDWARE))
      throw new AssertionError("Requires the isolated emulator APK");
    boolean republish = "e2-catalog-republish".equals(mode);
    boolean publish = republish || "e2-catalog-publish".equals(mode);
    boolean previous = "e2-catalog-previous".equals(mode);
    String user = new String(shell(test, "am get-current-user")).trim();
    List<E2AutoSync.Candidate> initial = E2AutoSync.candidates(context);
    if (!(publish ? "0".equals(user) : user.matches("[1-9][0-9]{0,3}"))
        || initial.size() != (republish ? 1 : 0)
        || (republish && !CATALOG_JPEG.equals(initial.get(0).photo().name)))
      throw new AssertionError("Requires the isolated publisher or a separate empty Android user");
    File input = new File(context.getCacheDir(), "e2-background-credentials");
    List<String> config;
    try {
      if (input.length() > 512) throw new AssertionError("Invalid credential input");
      config = Files.readAllLines(input.toPath());
    } finally {
      Files.deleteIfExists(input.toPath());
    }
    if (config.size() != 3 || !BUCKET.equals(config.get(0)))
      throw new AssertionError("Requires loupe-test");
    try (E2Photos photos = new E2Photos(context)) {
      List<JSONObject> rows = photos.saved(BUCKET);
      if (photos.settings.configured() || photos.settings.records.all().size() != (republish ? 1 : 0)
          || rows.size() != (republish ? 1 : 0)
          || (republish && !CATALOG_JPEG.equals(rows.get(0).getString("name"))))
        throw new AssertionError("Unexpected starting catalog or connection state");
    }
    byte[] expected = jpeg();
    Activity activity = test.startActivitySync(new Intent(context, E2SyncActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      View root = activity.getWindow().getDecorView();
      test.runOnMainSync(() -> {
        ((EditText) find(root, "バケット", true)).setText(BUCKET);
        ((EditText) find(root, "アクセスキー", true)).setText(config.get(1));
        ((EditText) find(root, "シークレットキー", true)).setText(config.get(2));
        find(root, "接続して保存", false).performClick();
      });
      waitFor("connect", 30000, () -> onUi(test, () -> find(root, "接続しました", false) != null));
      if (publish) {
        Uri fixture = republish ? initial.get(0).photo().uri : original(context, CATALOG_JPEG, expected);
        if (E2AutoSync.candidates(context).size() != 1) throw new AssertionError("Expected one JPEG");
        try (E2Photos photos = new E2Photos(context)) {
          if (!republish) photos.save(fixture, CATALOG_JPEG, BUCKET);
        }
        clickView(test, root, "復元一覧をクラウドに保存", false);
        waitFor("catalog publication consent", 10000, () -> clickActive(test, APP, "保存"));
        waitFor("catalog publication readback", 30000,
            () -> onUi(test, () -> hasTextPrefix(root, "復元一覧を保存しました")));
        try (InputStream source = context.getContentResolver().openInputStream(fixture)) {
          if (source == null || !Arrays.equals(expected, source.readAllBytes()))
            throw new AssertionError("Publisher original changed");
        }
        capture(test, context, "e2-catalog-publish.png");
        return "PASS e2-catalog: published and read back one JPEG; publisher source retained";
      }
      clickView(test, root, "クラウドから復元一覧を取得", false);
      String result = previous ? "前の復元一覧を読み込みました" : "復元一覧を読み込みました";
      waitFor("catalog import", 30000, () -> onUi(test, () -> hasTextPrefix(root, result)));
      try (E2Photos photos = new E2Photos(context)) {
        List<JSONObject> rows = photos.saved(BUCKET);
        if (!photos.settings.records.all().isEmpty() || rows.size() != 1
            || !CATALOG_JPEG.equals(rows.get(0).getString("name")))
          throw new AssertionError("Expected one recoverable row in the separate cache");
      }
      try {
        waitFor("restoration action is visible", 10000, () -> onUi(test, () -> {
          View restore = findDescription(root, CATALOG_JPEG + "を復元");
          android.graphics.Rect visible = new android.graphics.Rect();
          return restore != null && restore.getGlobalVisibleRect(visible)
              && visible.height() >= restore.getHeight();
        }));
      } catch (AssertionError failure) {
        capture(test, context, "e2-catalog-visibility-failed.png");
        throw failure;
      }
      capture(test, context, "e2-catalog-loaded.png");
      clickView(test, root, CATALOG_JPEG + "を復元", true);
      waitFor("save document", 25000, () -> {
        AccessibilityNodeInfo picker = test.getUiAutomation().getRootInActiveWindow();
        if (picker == null || !List.of("com.google.android.documentsui", "com.android.documentsui")
            .contains(String.valueOf(picker.getPackageName()))) return false;
        for (AccessibilityNodeInfo button : picker.findAccessibilityNodeInfosByViewId("android:id/button1"))
          if (button.isEnabled() && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        return false;
      });
      waitFor("restore success", 20000, () -> activeContains(test, APP, "原本を復元しました"));
      byte[] resultRows = shell(test, "content query --user " + user
          + " --uri content://media/external_primary/downloads --projection _id:_display_name");
      java.util.regex.Matcher row = java.util.regex.Pattern.compile(
          "_id=(\\d+), _display_name=" + java.util.regex.Pattern.quote(CATALOG_JPEG))
          .matcher(new String(resultRows));
      if (!row.find()) throw new AssertionError("No fresh JPEG in Downloads");
      byte[] restored = shell(test, "content read --user " + user
          + " --uri content://media/external_primary/downloads/" + row.group(1));
      if (!Arrays.equals(expected, restored)) throw new AssertionError("Restored JPEG bytes differ");
      return "PASS e2-catalog: empty Android user read " + (previous ? "previous" : "latest")
          + " generation and restored matching JPEG; bytes=" + restored.length;
    } finally {
      test.runOnMainSync(activity::finish);
      context.getSharedPreferences("e2_connection", Context.MODE_PRIVATE).edit().clear().commit();
    }
  }

  private static void clickView(Instrumentation test, View root, String label, boolean description) {
    waitFor(label, 12000, () -> onUi(test, () -> {
      View view = description ? findDescription(root, label) : find(root, label, false);
      if (view == null || !view.isEnabled()) return false;
      view.requestRectangleOnScreen(new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight()), true);
      return view.performClick();
    }));
  }

  private static boolean hasTextPrefix(View view, String prefix) {
    if (view instanceof TextView text && text.getText().toString().startsWith(prefix)) return true;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        if (hasTextPrefix(group.getChildAt(i), prefix)) return true;
    return false;
  }

  static String run(Instrumentation test) throws Exception {
    Context context = test.getTargetContext();
    if (!APP.equals(context.getPackageName()) || !"ranchu".equals(Build.HARDWARE))
      throw new AssertionError("Requires the isolated emulator APK");
    File input = new File(context.getCacheDir(), "e2-background-credentials");
    List<String> config;
    try {
      if (input.length() > 512) throw new AssertionError("Invalid credential input");
      config = Files.readAllLines(input.toPath());
    } finally {
      Files.deleteIfExists(input.toPath());
    }
    if (config.size() != 3 || !BUCKET.equals(config.get(0))
        || !E2AutoSync.candidates(context).isEmpty())
      throw new AssertionError("Requires an empty photo collection and loupe-test");
    String name = "loupe-e2-" + UUID.randomUUID() + ".jpg";
    byte[] expected = jpeg();
    Uri original = original(context, name, expected);
    if (E2AutoSync.candidates(context).size() != 1)
      throw new AssertionError("Expected only one synthetic JPEG");
    Activity activity = test.startActivitySync(new Intent(context, E2SyncActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      View root = activity.getWindow().getDecorView();
      test.runOnMainSync(() -> {
        ((EditText) find(root, "バケット", true)).setText(BUCKET);
        ((EditText) find(root, "アクセスキー", true)).setText(config.get(1));
        ((EditText) find(root, "シークレットキー", true)).setText(config.get(2));
        find(root, "接続して保存", false).performClick();
      });
      waitFor("connect", 30000, () -> onUi(test, () -> find(root, "接続しました", false) != null));
      capture(test, context, "e2-connected.png");
      shell(test, "svc data disable");
      shell(test, "svc wifi disable");
      waitFor("network disabled", 20000, () -> !hasUnmeteredNetwork(context));
      Activity owner = activity;
      test.runOnMainSync(() -> find(root, "開始", false).performClick());
      waitFor("start confirmation", 8000, () -> clickActive(test, APP, "開始"));
      waitFor("enqueue", 8000, () -> E2SyncJob.enabled(context));
      shell(test, "input keyevent KEYCODE_HOME");
      waitFor("launcher", 10000, () -> !activePackage(test, APP));
      if (E2SyncJob.snapshot(context).kind != JobState.Kind.WAITING)
        throw new AssertionError("Expected queued sync before restoring Wi-Fi");
      long start = SystemClock.elapsedRealtime();
      shell(test, "svc wifi enable");
      waitFor("background sync", 65000,
          () -> E2SyncJob.snapshot(context).kind == JobState.Kind.COMPLETE);
      long elapsed = SystemClock.elapsedRealtime() - start;
      JobState status = E2SyncJob.snapshot(context);
      if (activePackage(test, APP) || status.ready != 1 || status.total != 1 || status.failed != 0)
        throw new AssertionError("Expected one photo synced while launcher was foreground");
      JSONObject row;
      try (E2Photos photos = new E2Photos(context)) {
        List<JSONObject> saved = photos.saved(BUCKET);
        if (saved.size() != 1 || !name.equals(saved.get(0).getString("name")))
          throw new AssertionError("Unexpected verified photo");
        row = saved.get(0);
      }
      context.startActivity(new Intent(context, E2SyncActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
              | Intent.FLAG_ACTIVITY_SINGLE_TOP));
      waitFor("return to app", 12000, () -> activePackage(test, APP));
      View restoreRoot = owner.getWindow().getDecorView();
      test.runOnMainSync(() -> find(restoreRoot, "保存した原本を更新", false).performClick());
      waitFor("restore button", 12000, () -> onUi(test, () -> {
        View button = findDescription(restoreRoot, name + "を復元");
        if (button == null || !button.isEnabled()) return false;
        button.requestRectangleOnScreen(new android.graphics.Rect(0, 0,
            button.getWidth(), button.getHeight()), true);
        return button.performClick();
      }));
      waitFor("document picker", 20000, () -> activePackage(test, "com.google.android.documentsui")
          || activePackage(test, "com.android.documentsui"));
      waitFor("save document", 15000, () -> {
        AccessibilityNodeInfo picker = test.getUiAutomation().getRootInActiveWindow();
        if (picker == null || !List.of("com.google.android.documentsui", "com.android.documentsui")
            .contains(String.valueOf(picker.getPackageName()))) return false;
        for (AccessibilityNodeInfo button : picker.findAccessibilityNodeInfosByViewId("android:id/button1"))
          if (button.isEnabled() && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        AccessibilityNodeInfo save = node(picker, "SAVE");
        return save != null && save.performAction(AccessibilityNodeInfo.ACTION_CLICK);
      });
      waitFor("restore success", 20000,
          () -> activeContains(test, APP, "原本を復元しました"));
      capture(test, context, "e2-restored.png");
      if (!Arrays.equals(expected, shell(test, "cat /sdcard/Download/" + name)))
        throw new AssertionError("Exported JPEG bytes differ");
      try (InputStream in = context.getContentResolver().openInputStream(original)) {
        if (in == null || !Arrays.equals(expected, in.readAllBytes()))
          throw new AssertionError("Original JPEG bytes changed");
      }
      test.runOnMainSync(owner::finish);
      return "PASS e2-background: native scheduler after Home, 1/1, versioned restore, "
          + "SAF output and source match; bytes=" + expected.length + " elapsed_ms=" + elapsed
          + " object_path=loupe/originals/" + row.getString("key") + "/" + row.getString("sha");
    } finally {
      E2SyncJob.setEnabled(context, false);
      context.getSharedPreferences("e2_connection", Context.MODE_PRIVATE).edit().clear().commit();
      shell(test, "svc wifi enable");
      shell(test, "svc data enable");
    }
  }

  private static void capture(Instrumentation test, Context context, String path) throws Exception {
    Bitmap bitmap = test.getUiAutomation().takeScreenshot();
    if (bitmap == null) throw new AssertionError("Screenshot unavailable");
    try (FileOutputStream output = new FileOutputStream(new File(context.getCacheDir(), path))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
    } finally {
      bitmap.recycle();
    }
  }

  private static boolean hasUnmeteredNetwork(Context context) {
    ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
    for (Network network : manager.getAllNetworks()) {
      NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
      if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
          && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return true;
    }
    return false;
  }

  private static byte[] jpeg() {
    return jpeg(160, 96);
  }

  private static byte[] jpeg(int width, int height) {
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(Color.rgb(77, 123, 191));
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    boolean ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 95, encoded);
    bitmap.recycle();
    if (!ok) throw new AssertionError("Synthetic JPEG could not be encoded");
    return encoded.toByteArray();
  }

  private static Uri original(Context context, String name, byte[] data) throws Exception {
    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
    values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
    values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LoupeE2Verify/");
    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
    Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
    if (uri == null) throw new AssertionError("Could not stage synthetic JPEG");
    try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
      out.write(data);
    }
    values.clear();
    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
    if (context.getContentResolver().update(uri, values, null, null) != 1)
      throw new AssertionError("Could not publish synthetic JPEG");
    return uri;
  }

  private static byte[] shell(Instrumentation test, String command) throws Exception {
    try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
        test.getUiAutomation().executeShellCommand(command))) {
      return input.readAllBytes();
    }
  }

  private static void waitFor(String phase, long timeout, BooleanSupplier predicate) {
    long limit = SystemClock.elapsedRealtime() + timeout;
    do {
      if (predicate.getAsBoolean()) return;
      SystemClock.sleep(150);
    } while (SystemClock.elapsedRealtime() < limit);
    throw new AssertionError("Timed out while waiting for " + phase);
  }

  private static boolean onUi(Instrumentation test, BooleanSupplier action) {
    AtomicBoolean result = new AtomicBoolean();
    test.runOnMainSync(() -> result.set(action.getAsBoolean()));
    return result.get();
  }

  private static View find(View view, String name, boolean hint) {
    if (view instanceof TextView text && name.contentEquals(
        hint && text.getHint() != null ? text.getHint() : text.getText())) return view;
    if (view instanceof ViewGroup group) {
      for (int index = 0; index < group.getChildCount(); index++) {
        View child = find(group.getChildAt(index), name, hint);
        if (child != null) return child;
      }
    }
    return null;
  }

  private static View findDescription(View view, String label) {
    if (label.equals(view.getContentDescription())) return view;
    if (view instanceof ViewGroup group)
      for (int index = 0; index < group.getChildCount(); index++) {
        View child = findDescription(group.getChildAt(index), label);
        if (child != null) return child;
      }
    return null;
  }

  private static boolean activePackage(Instrumentation test, String name) {
    AccessibilityNodeInfo root = test.getUiAutomation().getRootInActiveWindow();
    return root != null && name.contentEquals(root.getPackageName());
  }

  private static boolean activeContains(Instrumentation test, String app, String label) {
    AccessibilityNodeInfo root = test.getUiAutomation().getRootInActiveWindow();
    return root != null && app.contentEquals(root.getPackageName()) && node(root, label) != null;
  }

  private static boolean clickActive(Instrumentation test, String app, String label) {
    AccessibilityNodeInfo root = test.getUiAutomation().getRootInActiveWindow();
    if (root == null || !app.contentEquals(root.getPackageName())) return false;
    AccessibilityNodeInfo control = node(root, label);
    return control != null && control.performAction(AccessibilityNodeInfo.ACTION_CLICK);
  }

  private static AccessibilityNodeInfo node(AccessibilityNodeInfo root, String label) {
    if (label.contentEquals(root.getText() == null ? "" : root.getText())
        || label.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
    for (int i = 0; i < root.getChildCount(); i++) {
      AccessibilityNodeInfo child = root.getChild(i);
      if (child == null) continue;
      AccessibilityNodeInfo found = node(child, label);
      if (found != null) return found;
    }
    return null;
  }
}
