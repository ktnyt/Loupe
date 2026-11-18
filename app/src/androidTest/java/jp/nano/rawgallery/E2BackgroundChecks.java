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
        || !SyncEngine.candidates(context).isEmpty())
      throw new AssertionError("Requires an empty photo collection and loupe-test");
    String name = "loupe-e2-" + UUID.randomUUID() + ".jpg";
    byte[] expected = jpeg();
    Uri original = original(context, name, expected);
    if (SyncEngine.candidates(context).size() != 1)
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
      Activity restoredActivity = test.startActivitySync(new Intent(context, E2SyncActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      View restoreRoot = restoredActivity.getWindow().getDecorView();
      waitFor("restore button", 12000, () -> onUi(test, () -> {
        View button = findDescription(restoreRoot, name + "を復元");
        if (button == null || !button.isEnabled()) return false;
        button.requestRectangleOnScreen(new android.graphics.Rect(0, 0,
            button.getWidth(), button.getHeight()), true);
        return button.performClick();
      }));
      waitFor("document picker", 20000, () -> activePackage(test, "com.google.android.documentsui")
          || activePackage(test, "com.android.documentsui"));
      waitFor("save document", 15000, () -> clickActive(test, "com.google.android.documentsui", "Save")
          || clickActive(test, "com.android.documentsui", "Save")
          || clickActive(test, "com.google.android.documentsui", "保存")
          || clickActive(test, "com.android.documentsui", "保存"));
      waitFor("restore success", 20000,
          () -> activeContains(test, APP, "原本を復元しました"));
      capture(test, context, "e2-restored.png");
      if (!Arrays.equals(expected, shell(test, "cat /sdcard/Download/" + name)))
        throw new AssertionError("Exported JPEG bytes differ");
      try (InputStream in = context.getContentResolver().openInputStream(original)) {
        if (in == null || !Arrays.equals(expected, in.readAllBytes()))
          throw new AssertionError("Original JPEG bytes changed");
      }
      test.runOnMainSync(() -> { restoredActivity.finish(); owner.finish(); });
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
