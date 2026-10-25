package jp.nano.rawgallery;

import android.content.Context;
import java.util.*;

final class BatchTagChecks {
  static String run(Context context) throws Exception {
    String db = "batch-tags-" + UUID.randomUUID() + ".db";
    try (TagStore store = new TagStore(context, db)) {
      String tag = store.create("batch").id, otherTag = store.create("untouched").id;
      String a = store.photoId(AutomaticTagChecks.photo(71), true);
      String b = store.photoId(AutomaticTagChecks.photo(72), true);
      String c = store.photoId(AutomaticTagChecks.photo(73), true);
      store.record("seed-a", tag, a, 0, TagJudgments.Value.ASSIGNED);
      store.record("seed-b", tag, b, 0, TagJudgments.Value.ASSIGNED);
      store.record("other", otherTag, a, 0, TagJudgments.Value.ASSIGNED);
      Map<String, Long> selected = new LinkedHashMap<>(); selected.put(a, 1L); selected.put(b, 1L);
      // Fail after the first item has been written: the entire batch must roll back.
      store.getWritableDatabase().execSQL("CREATE TRIGGER fail_batch BEFORE INSERT ON history WHEN NEW.photo='" + b + "' AND NEW.value='EXCLUDED' BEGIN SELECT RAISE(ABORT, 'test'); END");
      boolean failed = false;
      try { store.recordBatch("remove", tag, selected, TagJudgments.Value.EXCLUDED); }
      catch (android.database.sqlite.SQLiteException expected) { failed = true; }
      AutomaticTagChecks.check(failed && store.load().history.size() == 3, "Batch partially committed");
      AutomaticTagChecks.check(store.load().value(tag, a) == TagJudgments.Value.ASSIGNED, "First removal survived failure");
      store.getWritableDatabase().execSQL("DROP TRIGGER fail_batch");
      store.recordBatch("remove", tag, selected, TagJudgments.Value.EXCLUDED);
      store.recordBatch("remove", tag, selected, TagJudgments.Value.EXCLUDED);
      try (TagStore reopened = new TagStore(context, db)) {
        TagJudgments state = reopened.load();
        AutomaticTagChecks.check(state.history.size() == 5, "Retry duplicated history");
        AutomaticTagChecks.check(state.value(tag, a) == TagJudgments.Value.EXCLUDED && state.value(tag, b) == TagJudgments.Value.EXCLUDED, "Batch not durable");
        AutomaticTagChecks.check(state.value(otherTag, a) == TagJudgments.Value.ASSIGNED && state.value(tag, c) == TagJudgments.Value.UNDECIDED, "Unselected data changed");
      }
      Map<String, Long> stale = new LinkedHashMap<>(); stale.put(c, 0L); stale.put(a, 1L);
      failed = false;
      try { store.recordBatch("stale", tag, stale, TagJudgments.Value.ASSIGNED); }
      catch (IllegalStateException expected) { failed = true; }
      AutomaticTagChecks.check(failed && store.load().value(tag, c) == TagJudgments.Value.UNDECIDED, "Stale batch partially applied");
      selected.put(a, 2L); selected.put(b, 2L);
      store.recordBatch("restore", tag, selected, TagJudgments.Value.ASSIGNED);
      AutomaticTagChecks.check(store.load().value(tag, a) == TagJudgments.Value.ASSIGNED && store.load().value(tag, b) == TagJudgments.Value.ASSIGNED, "Batch re-add failed");
      return "Batch tags passed: multi-remove/add, atomic rollback, stale rejection, idempotent retry, reopen, untouched photos/tags\n";
    } finally { context.deleteDatabase(db); }
  }
}
