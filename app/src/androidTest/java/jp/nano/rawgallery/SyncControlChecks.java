package jp.nano.rawgallery;

import android.app.Dialog;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;

/** Verifies the real library settings entry and the installed component boundary. */
final class SyncControlChecks {
  static String run(Instrumentation test) throws Exception {
    checkInstalledComponents(test.getTargetContext());
    var main =
        (MainActivity)
            test.startActivitySync(
                new Intent(test.getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    var monitor = test.addMonitor(E2SyncActivity.class.getName(), null, false);
    var sheet = new AtomicReference<Dialog>();
    E2SyncActivity sync = null;
    try {
      var menu = MainActivity.class.getDeclaredMethod("galleryMenu");
      menu.setAccessible(true);
      AlbumTagEntryChecks.main(
          test,
          () -> {
            sheet.set((Dialog) menu.invoke(main));
            return true;
          });
      AlbumTagEntryChecks.waitFor(
          test,
          "IDrive e2 settings entry",
          () -> find(sheet.get().getWindow().getDecorView(), "IDrive e2") != null);
      capture(test, "amazon-retirement-settings");
      AlbumTagEntryChecks.main(
          test,
          () -> {
            View entry = find(sheet.get().getWindow().getDecorView(), "IDrive e2");
            for (View row = entry; ; row = (View) row.getParent()) {
              if (row.isClickable()) {
                if (!row.performClick()) throw new AssertionError("IDrive e2 entry did not open");
                break;
              }
              if (!(row.getParent() instanceof View))
                throw new AssertionError("No action for e2 entry");
            }
            return true;
          });
      sync = (E2SyncActivity) test.waitForMonitorWithTimeout(monitor, 6000);
      if (sync == null) throw new AssertionError("Settings did not open IDrive e2");
      E2SyncActivity screen = sync;
      AlbumTagEntryChecks.waitFor(
          test,
          "IDrive e2 window focus",
          () ->
              screen.hasWindowFocus()
                  && find(screen.getWindow().getDecorView(), E2Panel.DASHBOARD_TITLE) != null);
      capture(test, "amazon-retirement-e2");
      test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
      AlbumTagEntryChecks.waitFor(test, "Back to library", main::hasWindowFocus);
      return "PASS existing IDrive e2 entry and Back, private Amazon task aliases and removed"
          + " operation classes. Settings and e2 captures saved.\n";
    } finally {
      test.removeMonitor(monitor);
      E2SyncActivity screen = sync;
      test.runOnMainSync(
          () -> {
            if (sheet.get() != null) sheet.get().dismiss();
            if (screen != null) screen.finish();
            main.finish();
          });
    }
  }

  static void checkInstalledComponents(android.content.Context context) throws Exception {
    PackageManager manager = context.getPackageManager();
    var launcher =
        new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.getPackageName());
    var launcherEntries = manager.queryIntentActivities(launcher, 0);
    for (String name : new String[] {"CloudActivity", "SyncActivity"}) {
      ComponentName alias = component(context, name);
      var info = manager.getActivityInfo(alias, 0);
      if (!info.enabled
          || info.exported
          || !MainActivity.class.getName().equals(info.targetActivity)) {
        throw new AssertionError(name + " must be a private MainActivity alias");
      }
      if (launcherEntries.stream()
          .anyMatch(entry -> alias.getClassName().equals(entry.activityInfo.name))) {
        throw new AssertionError(name + " is present in launcher results");
      }
    }
    for (String name :
        new String[] {"CloudActivity", "CloudVerifier", "SyncActivity", "SyncEngine", "SyncJob"}) {
      try {
        Class.forName(component(context, name).getClassName(), false, context.getClassLoader());
        throw new AssertionError(name + " class is still installed");
      } catch (ClassNotFoundException expected) {
        // Retired operational code must not be present behind the aliases.
      }
    }
    try {
      manager.getServiceInfo(
          component(context, "SyncJob"), PackageManager.MATCH_DISABLED_COMPONENTS);
      throw new AssertionError("SyncJob is still installed");
    } catch (PackageManager.NameNotFoundException expected) {
      // Removed component.
    }
    manager.getActivityInfo(component(context, "E2SyncActivity"), 0);
    manager.getServiceInfo(component(context, "E2SyncJob"), 0);
  }

  private static ComponentName component(android.content.Context context, String name) {
    return new ComponentName(context, "jp.nano.rawgallery." + name);
  }

  static View find(View view, String text) {
    if (view instanceof TextView label && text.contentEquals(label.getText())) return view;
    if (view instanceof android.view.ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View match = find(group.getChildAt(i), text);
        if (match != null) return match;
      }
    return null;
  }

  static void capture(Instrumentation test, String name) throws Exception {
    Bitmap shot = test.getUiAutomation().takeScreenshot();
    if (shot == null) throw new AssertionError("Screenshot unavailable");
    try (var out =
        new FileOutputStream(new File(test.getTargetContext().getCacheDir(), name + ".png"))) {
      if (!shot.compress(Bitmap.CompressFormat.PNG, 100, out)) {
        throw new AssertionError("Screenshot write failed");
      }
    } finally {
      shot.recycle();
    }
  }
}
