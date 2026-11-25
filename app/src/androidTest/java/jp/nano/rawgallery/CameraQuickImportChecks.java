package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import jp.nano.rawgallery.camera.CameraCredentialStore;
import jp.nano.rawgallery.camera.CameraQrCredentials;
import jp.nano.rawgallery.camera.SdSourceRegistrationChecks;

/**
 * Dedicated-emulator entry-point checks; blocks outgoing BLE Activity before any radio operation.
 */
final class CameraQuickImportChecks {
  private static final String QR = "OIS2,3,TVJ1CRDC,-+*%$ZYX,UKR1CRDC,-+*%$Z";

  static String run(Instrumentation t) throws Exception {
    require(Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish"), "emulator only");
    var context = t.getTargetContext();
    var store = new CameraCredentialStore(context);
    require(store.names().isEmpty(), "requires empty dedicated-emulator registration store");
    var prefs = context.getSharedPreferences("camera_import", 0);
    String priorTree = prefs.getString("sd_tree_uri", null);
    prefs.edit().remove("sd_tree_uri").commit();
    AtomicInteger launches = new AtomicInteger(), sdPickers = new AtomicInteger();
    AtomicReference<Intent> captured = new AtomicReference<>();
    Instrumentation.ActivityMonitor monitor =
        new Instrumentation.ActivityMonitor() {
          @Override
          public Instrumentation.ActivityResult onStartActivity(Intent intent) {
            if (Intent.ACTION_OPEN_DOCUMENT_TREE.equals(intent.getAction())) {
              sdPickers.incrementAndGet();
              return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
            }
            if (intent.getComponent() != null
                && CameraBleActivity.class.getName().equals(intent.getComponent().getClassName())) {
              captured.set(new Intent(intent));
              launches.incrementAndGet();
              return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
            }
            return null;
          }
        };
    CameraImportActivity screen = null;
    boolean first = false, second = false;
    try {
      sourceCheckLifetime(t);
      SdSourceRegistrationChecks.run(context);
      directoryBoundary(context);
      require(
          CameraQuickImportSources.resolve(context, new CancellationSignal()).profiles.isEmpty(),
          "empty source list");
      t.addMonitor(monitor);
      screen = open(t);
      awaitText(t, "取り込み元を登録");
      capture(t, "unregistered");
      clickText(t, "SDを選ぶ");
      awaitCount(sdPickers, 1);
      require(launches.get() == 0, "setup SD action opens only picker");
      finish(t, screen);
      screen = null;
      first = true;
      try (var c = CameraQrCredentials.parse(QR)) {
        store.save(c);
      }
      var one = CameraQuickImportSources.resolve(context, new CancellationSignal());
      require(one.sd == null && one.profiles.size() == 1, "unique complete registration");
      screen = open(t);
      awaitCount(launches, 1);
      Intent outgoing = captured.get();
      require(
          outgoing.getExtras() != null && outgoing.getExtras().keySet().size() == 1,
          "only opaque reference sent");
      require(
          CameraCredentialStore.profileId("BLE-TEST")
              .equals(outgoing.getStringExtra(CameraBleActivity.EXTRA_QUICK_PROFILE_ID)),
          "correct profile selected");
      var recreated = t.addMonitor(CameraImportActivity.class.getName(), null, false);
      CameraImportActivity old = screen;
      t.runOnMainSync(old::recreate);
      screen = (CameraImportActivity) recreated.waitForActivityWithTimeout(5000);
      t.removeMonitor(recreated);
      require(screen != null, "quick screen recreated");
      t.waitForIdleSync();
      SystemClock.sleep(350);
      require(launches.get() == 1, "recreation does not reconnect");
      finish(t, screen);
      screen = null;
      second = true;
      try (var c = CameraQrCredentials.parse(QR.replace("UKR1CRDC", "UKR1CRDD"))) {
        store.save(c);
      }
      var multiple = CameraQuickImportSources.resolve(context, new CancellationSignal());
      require(multiple.profiles.size() == 2, "multiple complete registrations");
      screen = open(t);
      awaitText(t, "取り込むカメラを選択");
      capture(t, "choose-camera");
      require(launches.get() == 1, "multiple sources require explicit choice");
      clickText(t, "BLE-TESS");
      awaitCount(launches, 2);
      require(
          CameraCredentialStore.profileId("BLE-TESS")
              .equals(captured.get().getStringExtra(CameraBleActivity.EXTRA_QUICK_PROFILE_ID)),
          "explicit camera selected");
      return "PASS source check cancellation/timeout/stale results, SD registration migration/rollback and mounted-source filtering, quick-import local directory presence/missing/file/error/cancel, empty setup direct SD picker/unique/multiple QR routing, opaque-only Intent, recreation no replay; radio Activity blocked (dedicated emulator).";
    } finally {
      if (screen != null) finish(t, screen);
      t.removeMonitor(monitor);
      if (second) store.remove("BLE-TESS");
      if (first) store.remove("BLE-TEST");
      if (priorTree == null) prefs.edit().remove("sd_tree_uri").commit();
      else prefs.edit().putString("sd_tree_uri", priorTree).commit();
    }
  }

  private static void sourceCheckLifetime(Instrumentation test) throws InterruptedException {
    var workers = new ArrayDeque<Runnable>();
    var results = new ArrayList<String>();
    var cancelled = new AtomicReference<CancellationSignal>();
    var check = new CameraSourceCheck(new Handler(Looper.getMainLooper()), workers::addLast);
    test.runOnMainSync(
        () -> {
          check.start(
              signal -> {
                cancelled.set(signal);
                return "cancelled result";
              },
              results::add,
              () -> results.add("cancelled timed out"),
              busy -> results.add("busy"));
          require(check.cancel(), "pending check cancels");
          require(!check.cancel(), "cancel is idle after clearing");
          check.start(
              signal -> "current",
              results::add,
              () -> results.add("current timed out"),
              busy -> results.add("busy"));
        });
    workers.removeFirst().run();
    require(cancelled.get().isCanceled(), "cancel sets the old signal");
    test.waitForIdleSync();
    test.runOnMainSync(
        () -> {
          require(results.isEmpty(), "cancelled worker result discarded");
          require(check.isPending(), "cancelled result preserves the current request");
        });
    workers.removeFirst().run();
    test.waitForIdleSync();
    test.runOnMainSync(
        () -> {
          require(results.equals(List.of("current")), "the current result is delivered once");
          require(!check.isPending(), "result clears the current request");
        });

    var deadline = new CountDownLatch(1);
    test.runOnMainSync(
        () ->
            check.start(
                signal -> {
                  cancelled.set(signal);
                  return "late result";
                },
                results::add,
                () -> {
                  results.add("timed out");
                  deadline.countDown();
                },
                busy -> results.add("busy")));
    require(deadline.await(6, TimeUnit.SECONDS), "deadline releases the stalled request");
    workers.removeFirst().run();
    require(cancelled.get().isCanceled(), "deadline sets the old signal");
    test.waitForIdleSync();
    test.runOnMainSync(
        () -> {
          require(
              results.equals(List.of("current", "timed out")),
              "late result discarded after deadline");
          require(!check.isPending(), "deadline clears the stalled request");
        });
  }

  private static void directoryBoundary(android.content.Context context) {
    Uri tree = Uri.parse("content://quick.fixture/tree/card");
    var provider =
        new ContentProvider() {
          int state;

          @Override
          public boolean onCreate() {
            return true;
          }

          @Override
          public Cursor query(
              Uri uri, String[] projection, String selection, String[] args, String sort) {
            if (state == 3) throw new SecurityException("synthetic denied");
            var cursor =
                new MatrixCursor(new String[] {DocumentsContract.Document.COLUMN_MIME_TYPE});
            if (state != 1)
              cursor.addRow(
                  new Object[] {
                    state == 2 ? "image/jpeg" : DocumentsContract.Document.MIME_TYPE_DIR
                  });
            return cursor;
          }

          @Override
          public String getType(Uri uri) {
            return null;
          }

          @Override
          public Uri insert(Uri uri, ContentValues v) {
            throw new AssertionError("no writes");
          }

          @Override
          public int delete(Uri uri, String s, String[] a) {
            throw new AssertionError("no deletes");
          }

          @Override
          public int update(Uri uri, ContentValues v, String s, String[] a) {
            throw new AssertionError("no updates");
          }
        };
    var info = new android.content.pm.ProviderInfo();
    info.authority = "quick.fixture";
    info.exported = false;
    provider.attachInfo(context, info);
    ContentResolver resolver = ContentResolver.wrap(provider);
    require(
        CameraQuickImportSources.isReachableDirectory(resolver, tree, new CancellationSignal()),
        "mounted directory accepted");
    provider.state = 1;
    require(
        !CameraQuickImportSources.isReachableDirectory(resolver, tree, new CancellationSignal()),
        "removed directory rejected");
    provider.state = 2;
    require(
        !CameraQuickImportSources.isReachableDirectory(resolver, tree, new CancellationSignal()),
        "file is not tree root");
    provider.state = 3;
    require(
        !CameraQuickImportSources.isReachableDirectory(resolver, tree, new CancellationSignal()),
        "provider denial rejected");
    CancellationSignal cancelled = new CancellationSignal();
    cancelled.cancel();
    try {
      CameraQuickImportSources.isReachableDirectory(resolver, tree, cancelled);
      throw new AssertionError("cancel accepted");
    } catch (OperationCanceledException expected) {
    }
  }

  private static CameraImportActivity open(Instrumentation t) {
    return (CameraImportActivity)
        t.startActivitySync(
            new Intent(t.getTargetContext(), CameraImportActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(CameraImportActivity.START_IMPORT_KEEP_ORIGINALS, true));
  }

  private static void finish(Instrumentation t, Activity screen) {
    t.runOnMainSync(screen::finish);
    t.waitForIdleSync();
  }

  private static void awaitCount(AtomicInteger count, int expected) {
    long end = SystemClock.uptimeMillis() + 6000;
    while (count.get() < expected && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
    require(count.get() == expected, "single dispatch");
  }

  private static void awaitText(Instrumentation t, String text) {
    long end = SystemClock.uptimeMillis() + 6000;
    while (SystemClock.uptimeMillis() < end) {
      var root = t.getUiAutomation().getRootInActiveWindow();
      if (root != null && !root.findAccessibilityNodeInfosByText(text).isEmpty()) return;
      SystemClock.sleep(50);
    }
    throw new AssertionError("expected local source UI");
  }

  private static void clickText(Instrumentation t, String text) {
    var root = t.getUiAutomation().getRootInActiveWindow();
    for (var node : root.findAccessibilityNodeInfosByText(text)) {
      AccessibilityNodeInfo target = node;
      while (target != null && !target.isClickable()) target = target.getParent();
      if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
    }
    throw new AssertionError("choice unavailable");
  }

  private static void capture(Instrumentation t, String name) throws Exception {
    var bitmap = t.getUiAutomation().takeScreenshot();
    require(bitmap != null, "display capture");
    File dir = new File(t.getTargetContext().getCacheDir(), "quick-import-checks");
    dir.mkdirs();
    try (var out = new FileOutputStream(new File(dir, name + ".png"))) {
      bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
    } finally {
      bitmap.recycle();
    }
  }

  private static void require(boolean value, String label) {
    if (!value) throw new AssertionError(label);
  }
}
