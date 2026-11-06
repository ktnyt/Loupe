package jp.nano.rawgallery;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit, non-exported Sharesheet callback. It receives no photo keys from the sender. */
public final class ShareHistoryReceiver extends BroadcastReceiver {
  static final String ACTION = "jp.nano.rawgallery.SHARE_CHOSEN";
  static final String PREFS = "share-history-status";
  private static final ExecutorService WRITES = Executors.newSingleThreadExecutor();

  private static final int MAX_SESSIONS = 64;
  private static final int MAX_FALLBACK_KEY_CHARS = 256 * 1024;
  private static final Object SESSION_LOCK = new Object();
  private static final LinkedHashMap<String, Session> RECENT = new LinkedHashMap<>();
  private static final LinkedHashMap<String, Handoff> RETRIES = new LinkedHashMap<>();

  private static final class Session {
    final long created;
    final List<String> fallbackKeys;

    Session(long created, List<String> fallbackKeys) {
      this.created = created;
      this.fallbackKeys = fallbackKeys;
    }
  }

  private static final class Handoff {
    final String sessionId;
    final Session session;
    final String packageName;
    final long handedOffAt;
    String storeId;

    Handoff(String sessionId, Session session, String packageName, long handedOffAt) {
      this.sessionId = sessionId;
      this.session = session;
      this.packageName = packageName;
      this.handedOffAt = handedOffAt;
      if (session == null || session.fallbackKeys.isEmpty()) storeId = sessionId;
    }
  }

  /** Freeze actual outgoing keys on the share preparation thread. */
  static String begin(Context context, Collection<String> keys, long now) {
    List<String> frozen = ShareHistoryStore.validatedKeys(keys);
    if (now < 0) throw new IllegalArgumentException("Invalid time");
    String id;
    Session session;
    try (ShareHistoryStore store = new ShareHistoryStore(context)) {
      id = store.begin(frozen, now);
      session = new Session(now, List.of());
    } catch (RuntimeException unavailable) {
      long keyChars = 0;
      for (String key : frozen) keyChars += key.length();
      if (keyChars > MAX_FALLBACK_KEY_CHARS) return null;
      id = UUID.randomUUID().toString();
      session = new Session(now, frozen);
    }
    synchronized (SESSION_LOCK) {
      RECENT.values().removeIf(item -> now - item.created > ShareHistoryStore.SESSION_LIFETIME_MS);
      putBounded(RECENT, id, session);
    }
    return id;
  }

  static boolean canRetry() {
    long now = System.currentTimeMillis();
    synchronized (SESSION_LOCK) {
      for (Handoff handoff : RETRIES.values()) {
        if (!expired(handoff.session, now)) return true;
      }
      return false;
    }
  }

  static void retryLater(Context context) {
    Context app = context.getApplicationContext();
    WRITES.execute(() -> {
      List<Handoff> pending;
      synchronized (SESSION_LOCK) {
        pending = new ArrayList<>(RETRIES.values());
      }
      for (Handoff handoff : pending) write(app, handoff);
    });
  }

  private static boolean expired(Session session, long now) {
    return now < session.created || now - session.created > ShareHistoryStore.SESSION_LIFETIME_MS;
  }

  private static <T> void putBounded(LinkedHashMap<String, T> map, String id, T item) {
    map.put(id, item);
    if (map.size() > MAX_SESSIONS) map.remove(map.keySet().iterator().next());
  }

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
      Session cancelled;
      synchronized (SESSION_LOCK) {
        cancelled = RECENT.remove(session);
      }
      if (cancelled != null && !cancelled.fallbackKeys.isEmpty()) return;
      try (ShareHistoryStore store = new ShareHistoryStore(app)) { store.cancel(session); }
      catch (RuntimeException ignored) { /* Unconsumed sessions expire without becoming history. */ }
    });
  }

  static void failed(Context context) {
    context.getSharedPreferences(PREFS, 0).edit()
        .putBoolean("write_failed", true).putLong("failure_revision", System.nanoTime()).apply();
  }

  private static void record(Context context, String sessionId, ComponentName target, long handedOffAt) {
    String packageName = target.getPackageName();
    try {
      ShareHistoryStore.validateApp(packageName, packageName, handedOffAt);
    } catch (IllegalArgumentException invalid) { return; }
    Session session;
    synchronized (SESSION_LOCK) {
      if (RETRIES.containsKey(sessionId)) return;
      session = RECENT.remove(sessionId);
    }
    write(context, new Handoff(sessionId, session, packageName, handedOffAt));
  }

  private static void write(Context context, Handoff handoff) {
    long started = android.os.SystemClock.elapsedRealtime();
    long request = DiagnosticLog.id();
    DiagnosticLog.event("share.history.start", request);
    boolean saved;
    try {
      saved = save(context, handoff);
    } catch (RuntimeException failure) {
      if (handoff.session != null) {
        synchronized (SESSION_LOCK) {
          putBounded(RETRIES, handoff.sessionId, handoff);
        }
      }
      // A chooser callback can recreate the process, leaving only the durable session.
      // Report storage failure even when no in-memory retry can be recovered.
      failed(context);
      DiagnosticLog.event("share.history.failed", request, "elapsedMs", android.os.SystemClock.elapsedRealtime() - started);
      return;
    }

    boolean hasPending;
    long now = System.currentTimeMillis();
    synchronized (SESSION_LOCK) {
      RETRIES.remove(handoff.sessionId);
      RETRIES.values().removeIf(item -> expired(item.session, now));
      hasPending = !RETRIES.isEmpty();
    }
    if (saved) {
      SharedPreferences.Editor status = context.getSharedPreferences(PREFS, 0)
          .edit().putLong("revision", System.nanoTime());
      if (!hasPending) status.putBoolean("write_failed", false);
      status.apply();
      DiagnosticLog.event("share.history.saved", request, "elapsedMs", android.os.SystemClock.elapsedRealtime() - started);
    } else {
      DiagnosticLog.event("share.history.ignored", request, "elapsedMs", android.os.SystemClock.elapsedRealtime() - started);
    }
  }

  private static boolean save(Context context, Handoff handoff) {
    long now = System.currentTimeMillis();
    if (handoff.session != null && (expired(handoff.session, handoff.handedOffAt)
        || expired(handoff.session, now))) return false;
    String label = handoff.packageName;
    try {
      label = context.getPackageManager().getApplicationLabel(
          context.getPackageManager().getApplicationInfo(handoff.packageName, 0)).toString();
    } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
    if (label.isBlank()) label = handoff.packageName;
    if (label.length() > 512) label = label.substring(0, 512);
    try (ShareHistoryStore store = new ShareHistoryStore(context)) {
      if (handoff.storeId == null) {
        handoff.storeId = store.begin(handoff.session.fallbackKeys, handoff.session.created, now);
      }
      return store.complete(handoff.storeId, handoff.packageName, label, handoff.handedOffAt);
    }
  }
}
