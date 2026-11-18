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
    long oldGeneration = fixtureGeneration(resolver, changed, changedName);
    long unchangedGeneration = fixtureGeneration(resolver, eligible, eligibleName);
    File folder = new File(android.os.Environment.getExternalStoragePublicDirectory(
        android.os.Environment.DIRECTORY_PICTURES), "LoupeE2Verify");
    File eligibleFile = new File(folder, eligibleName);
    if (!eligibleFile.isFile() || !Arrays.equals(expected, Files.readAllBytes(eligibleFile.toPath())))
      throw new AssertionError("The exact synthetic JPEG must be readable as a file before removal");
    String changedAfterRename = "renamed-" + changedName;
    ContentValues metadata = new ContentValues();
    metadata.put(MediaStore.MediaColumns.DISPLAY_NAME, changedAfterRename);
    if (resolver.update(changedImage, metadata, null, null) != 1
        || fixtureGeneration(resolver, changed, changedAfterRename) == oldGeneration)
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

  private static long fixtureGeneration(android.content.ContentResolver resolver, Uri uri, String name) {
    try (android.database.Cursor row = resolver.query(uri,
        new String[]{MediaStore.MediaColumns.GENERATION_MODIFIED, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.VOLUME_NAME}, null, null, null)) {
      if (row == null || !row.moveToFirst() || row.getLong(0) <= 0
          || !name.equals(row.getString(1)) || !"Pictures/LoupeE2Verify/".equals(row.getString(2))
          || !APP.equals(row.getString(3)) || !MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(row.getString(4)))
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
    Bitmap bitmap = Bitmap.createBitmap(160, 96, Bitmap.Config.ARGB_8888);
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
