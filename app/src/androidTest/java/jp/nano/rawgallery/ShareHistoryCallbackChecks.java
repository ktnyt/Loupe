package jp.nano.rawgallery;

import static jp.nano.rawgallery.TagDetailChecks.require;

import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Framework delivery to the actual private receiver, using only UUID-namespaced synthetic rows. */
final class ShareHistoryCallbackChecks {
  static String run(Instrumentation test) throws Exception {
    Context context = test.getTargetContext();
    require(!context.getSharedPreferences(ShareHistoryReceiver.PREFS, 0).getBoolean("write_failed", false),
        "Existing unresolved write failure; preserve status");
    ComponentName chosen = new ComponentName(test.getContext(), ShareHistoryTargetActivity.class);
    String namespace = "fixture-share-callback-" + UUID.randomUUID();
    String first = namespace + "-1", second = namespace + "-2", cancelled = namespace + "-cancel";
    List<String> sessions = new ArrayList<>();
    Field field = ShareHistoryReceiver.class.getDeclaredField("WRITES");
    field.setAccessible(true);
    ExecutorService writes = (ExecutorService) field.get(null);
    try (ShareHistoryStore store = new ShareHistoryStore(context)) {
      try {
        require(store.history(first).isEmpty() && store.history(second).isEmpty()
            && store.history(cancelled).isEmpty(), "Synthetic rows start absent");
        PendingIntent single = callback(context, store, sessions, List.of(first));
        deliver(single, context, chosen, writes);
        ShareHistoryStore.Entry initial = assertHistory(store, first, chosen);
        require(store.history(second).isEmpty(), "Callback affects only chosen photo");
        try {
          single.send(context, 0, new Intent().putExtra(Intent.EXTRA_CHOSEN_COMPONENT, chosen));
          throw new AssertionError("System accepted replay of one-shot callback");
        } catch (PendingIntent.CanceledException expected) {
          // The one-shot token was consumed by the framework.
        }
        require(assertHistory(store, first, chosen).handedOffAt == initial.handedOffAt,
            "Replay leaves history unchanged");

        PendingIntent batch = callback(context, store, sessions, List.of(first, second));
        deliver(batch, context, chosen, writes);
        ShareHistoryStore.Entry repeated = assertHistory(store, first, chosen);
        ShareHistoryStore.Entry other = assertHistory(store, second, chosen);
        require(repeated.handedOffAt >= initial.handedOffAt && repeated.handedOffAt == other.handedOffAt,
            "Batch records one handoff and deduplicates prior destination");

        PendingIntent abandoned = callback(context, store, sessions, List.of(cancelled));
        ShareHistoryReceiver.cancelLater(context, sessions.get(sessions.size() - 1));
        writes.submit(() -> {}).get(10, TimeUnit.SECONDS);
        deliver(abandoned, context, chosen, writes);
        require(store.history(cancelled).isEmpty(), "Late delivery after cancellation leaves no history");
      } finally {
        writes.submit(() -> {}).get(10, TimeUnit.SECONDS);
        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
          for (String id : sessions) {
            db.delete("session_photos", "session=?", new String[] {id});
            db.delete("sessions", "id=?", new String[] {id});
          }
          for (String key : List.of(first, second, cancelled))
            db.delete("history", "photo=?", new String[] {key});
          db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
      }
      for (String key : List.of(first, second, cancelled))
        require(store.history(key).isEmpty(), "Synthetic history cleanup");
    }
    return "PASS explicit PendingIntent callback: framework delivery, single/batch, duplicate, "
        + "one-shot replay rejection, cancellation, UUID row cleanup. Injected chooser result; no external launch.\n";
  }

  private static PendingIntent callback(Context context, ShareHistoryStore store, List<String> sessions,
      List<String> keys) {
    String id = UUID.randomUUID().toString();
    sessions.add(id);
    // Seed precisely this session. The production begin() also prunes other expired sessions.
    SQLiteDatabase db = store.getWritableDatabase();
    db.beginTransaction();
    try {
      db.execSQL("INSERT INTO sessions(id,created) VALUES(?,?)", new Object[] {id, System.currentTimeMillis()});
      for (String key : keys)
        db.execSQL("INSERT INTO session_photos(session,photo) VALUES(?,?)", new Object[] {id, key});
      db.setTransactionSuccessful();
    } finally { db.endTransaction(); }
    return ShareHistoryReceiver.callback(context, id);
  }

  private static void deliver(PendingIntent callback, Context context, ComponentName chosen,
      ExecutorService writes) throws Exception {
    Intent result = new Intent().putExtra(Intent.EXTRA_CHOSEN_COMPONENT, chosen);
    CountDownLatch delivered = new CountDownLatch(1);
    callback.send(context, 0, result, (pending, intent, code, data, extras) -> delivered.countDown(), null);
    require(delivered.await(10, TimeUnit.SECONDS), "Framework callback completion");
    writes.submit(() -> {}).get(10, TimeUnit.SECONDS);
  }

  private static ShareHistoryStore.Entry assertHistory(ShareHistoryStore store, String key, ComponentName chosen) {
    List<ShareHistoryStore.Entry> rows = store.history(key);
    require(rows.size() == 1 && chosen.getPackageName().equals(rows.get(0).packageName)
        && !rows.get(0).label.isBlank(), "Expected exactly one destination for synthetic photo");
    return rows.get(0);
  }
}
