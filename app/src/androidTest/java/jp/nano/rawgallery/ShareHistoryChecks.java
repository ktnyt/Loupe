package jp.nano.rawgallery;

import android.content.Context;
import java.util.List;
import java.util.UUID;

/** Synthetic database only; no external app launch, photographs, or production history. */
final class ShareHistoryChecks {
  static String run(Context context) {
    String database = "test-share-history-" + UUID.randomUUID() + ".db";
    try {
      String pending;
      try (ShareHistoryStore store = new ShareHistoryStore(context, database)) {
        check(store.history("a").isEmpty(), "initial history");
        String id = store.begin(List.of("a", "b", "a"), 100);
        check(store.history("a").isEmpty(), "preparation creates no history");
        check(store.complete(id, "example.first", "First", 200), "handoff");
        check(!store.complete(id, "example.second", "Second", 201), "duplicate callback");
        check(store.history("a").size() == 1 && store.history("b").size() == 1, "batch");
        check(store.history("other").isEmpty(), "unrelated photo");
        check(store.complete(store.begin(List.of("a"), 300), "example.first", "Renamed", 400), "reshare");
        check(store.history("a").size() == 1, "dedup app");
        check(store.history("a").get(0).label.equals("Renamed"), "updated label");
        check(store.history("a").get(0).handedOffAt == 400, "event timestamp retained");
        check(store.complete(store.begin(List.of("a"), 500), "example.second", "Second", 600), "second app");
        check(store.history("a").size() == 2, "multiple apps");
        check(store.history("a").get(0).packageName.equals("example.second"), "recent first");
        String cancelled = store.begin(List.of("c"), 700);
        store.cancel(cancelled);
        check(!store.complete(cancelled, "example.first", "First", 800), "cancel");
        String expired = store.begin(List.of("c"), 900);
        check(!store.complete(expired, "example.first", "First", 901 + ShareHistoryStore.SESSION_LIFETIME_MS), "expiry");
        check(!store.complete("unknown", "example.first", "First", 1000), "unknown");
        check(store.history("c").isEmpty(), "no false history");
        pending = store.begin(List.of("d"), 1100);
      }
      try (ShareHistoryStore reopened = new ShareHistoryStore(context, database)) {
        check(reopened.history("a").size() == 2, "reopen history");
        check(reopened.complete(pending, "example.first", "First", 1200), "reopen pending");
        String failure = reopened.begin(List.of("x", "y"), 1300);
        reopened.getWritableDatabase().execSQL("CREATE TRIGGER fail_history BEFORE INSERT ON history WHEN NEW.photo='y' BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        boolean failed = false;
        try { reopened.complete(failure, "example.first", "First", 1400); }
        catch (android.database.SQLException expected) { failed = true; }
        check(failed, "failure injected");
        check(reopened.history("x").isEmpty() && reopened.history("y").isEmpty(), "atomic rollback");
        reopened.getWritableDatabase().execSQL("DROP TRIGGER fail_history");
        check(reopened.complete(failure, "example.first", "First", 1500), "retry pending transaction");
        check(reopened.history("x").size() == 1 && reopened.history("y").size() == 1, "retry batch");
        String newer = reopened.begin(List.of("newer"), 1800);
        String recovered = reopened.begin(List.of("recovered"), 1600, 1900);
        check(reopened.complete(recovered, "example.first", "First", 1700), "recover older session");
        check(reopened.history("recovered").get(0).handedOffAt == 1700, "original recovery timestamp");
        check(reopened.complete(newer, "example.first", "First", 2000), "recovery preserves newer session");
      }
      return "PASS share-history: frozen batch, dedup, cancel, expiry, reopen, rollback, retry\n";
    } finally { context.deleteDatabase(database); }
  }

  private static void check(boolean value, String label) {
    if (!value) throw new AssertionError(label);
  }
}
