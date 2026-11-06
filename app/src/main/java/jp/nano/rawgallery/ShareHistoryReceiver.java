package jp.nano.rawgallery;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit, non-exported Sharesheet callback. It receives no photo keys from the sender. */
public final class ShareHistoryReceiver extends BroadcastReceiver {
  static final String ACTION = "jp.nano.rawgallery.SHARE_CHOSEN";
  static final String PREFS = "share-history-status";
  private static final ExecutorService WRITES = Executors.newSingleThreadExecutor();

  static PendingIntent callback(Context context, String session) {
    Intent callback = new Intent(context, ShareHistoryReceiver.class).setAction(ACTION)
        .setData(new Uri.Builder().scheme("loupe-share").authority("chosen").appendPath(session).build());
    // Mutable is necessary for Android to append EXTRA_CHOSEN_COMPONENT. The explicit component,
    // action and session data are already fixed and cannot be replaced by ordinary fill-in extras.
    int flags = PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_CANCEL_CURRENT;
    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
    return PendingIntent.getBroadcast(context, 0, callback, flags);
  }

  @Override public void onReceive(Context context, Intent intent) {
    long handedOffAt = System.currentTimeMillis();
    if (intent == null || !ACTION.equals(intent.getAction())) return;
    Uri data = intent.getData();
    if (data == null || !"loupe-share".equals(data.getScheme()) || !"chosen".equals(data.getAuthority())
        || data.getPathSegments().size() != 1) return;
    ComponentName chosen;
    try {
      if (Build.VERSION.SDK_INT >= 35 && intent.hasExtra(Intent.EXTRA_CHOOSER_RESULT)) {
        android.service.chooser.ChooserResult result = intent.getParcelableExtra(
            Intent.EXTRA_CHOOSER_RESULT, android.service.chooser.ChooserResult.class);
        if (result == null || result.getType() != android.service.chooser.ChooserResult.CHOOSER_RESULT_SELECTED_COMPONENT) return;
        chosen = result.getSelectedComponent();
      } else {
        chosen = Build.VERSION.SDK_INT >= 33
            ? intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName.class)
            : intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT);
      }
    } catch (RuntimeException malformed) { return; }
    if (chosen == null || chosen.getPackageName().equals(context.getPackageName())) return;
    String session = data.getLastPathSegment();
    if (session == null || !session.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) return;
    PendingResult pending = goAsync();
    Context app = context.getApplicationContext();
    ComponentName target = chosen;
    WRITES.execute(() -> {
      try { record(app, session, target, handedOffAt); }
      finally { pending.finish(); }
    });
  }

  static void recordLater(Context context, String session, ComponentName target) {
    long handedOffAt = System.currentTimeMillis();
    Context app = context.getApplicationContext();
    WRITES.execute(() -> {
      if (session == null) failed(app);
      else record(app, session, target, handedOffAt);
    });
  }

  static void cancelLater(Context context, String session) {
    if (session == null) return;
    Context app = context.getApplicationContext();
    WRITES.execute(() -> {
      try (ShareHistoryStore store = new ShareHistoryStore(app)) { store.cancel(session); }
      catch (RuntimeException ignored) { /* Unconsumed sessions expire without becoming history. */ }
    });
  }

  static void failed(Context context) {
    context.getSharedPreferences(PREFS, 0).edit().putBoolean("write_failed", true).apply();
  }

  static void record(Context context, String session, ComponentName target, long handedOffAt) {
    long started = android.os.SystemClock.elapsedRealtime();
    long request = DiagnosticLog.id();
    DiagnosticLog.event("share.history.start", request);
    try (ShareHistoryStore store = new ShareHistoryStore(context)) {
      String packageName = target.getPackageName();
      String label = packageName;
      try {
        label = context.getPackageManager().getApplicationLabel(
            context.getPackageManager().getApplicationInfo(packageName, 0)).toString();
      } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
      if (label.isBlank()) label = packageName;
      if (label.length() > 512) label = label.substring(0, 512);
      boolean changed = store.complete(session, packageName, label, handedOffAt);
      if (changed) {
        context.getSharedPreferences(PREFS, 0).edit().putLong("revision", System.nanoTime()).apply();
        DiagnosticLog.event("share.history.saved", request, "elapsedMs", android.os.SystemClock.elapsedRealtime() - started);
      } else {
        DiagnosticLog.event("share.history.ignored", request, "elapsedMs", android.os.SystemClock.elapsedRealtime() - started);
      }
    } catch (RuntimeException failure) {
      failed(context);
      DiagnosticLog.event("share.history.failed", request, "elapsedMs", android.os.SystemClock.elapsedRealtime() - started);
    }
  }
}
