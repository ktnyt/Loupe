package jp.nano.rawgallery;

import android.content.Context;
import android.net.Uri;
import java.util.*;

/** Isolated database; no changes to user judgments or original photos. */
final class AutomaticTagChecks {
  static String run(Context context) throws Exception {
    String database = "auto-tag-test-" + UUID.randomUUID() + ".db";
    PhotoRepository.Photo seed = photo(1), other = photo(2), third = photo(3);
    List<PhotoRepository.Photo> scope = List.of(seed, other, third);
    String tagId, seedId;
    try {
      try (TagStore store = new TagStore(context, database)) {
        tagId = store.create("test").id;
        seedId = store.photoId(seed, true);
        store.record("seed", tagId, seedId, 0, TagJudgments.Value.ASSIGNED);
        // Recreate a genuine v1 schema before opening the v2 helper again.
        store.getWritableDatabase().execSQL("DROP TABLE automatic_tags");
        store.getWritableDatabase().execSQL("DROP TABLE automation");
        store.getWritableDatabase().setVersion(1);
      }
      try (TagStore store = new TagStore(context, database)) {
        check(store.load().value(tagId, seedId) == TagJudgments.Value.ASSIGNED, "Migration lost human data");
        long epoch = store.automation(tagId, true, false), revision = store.judgmentRevision(tagId);
        Map<Integer, RelatedPhotos.Vector> features = Map.of(0, v(1,0), 1, v(.9f,.1f), 2, v(.8f,.2f));
        List<TagJudgments.Value> human = List.of(TagJudgments.Value.ASSIGNED, TagJudgments.Value.UNDECIDED, TagJudgments.Value.UNDECIDED);
        Map<Integer, AutomaticTags.Decision> plan = AutomaticTags.predict(features, human, Set.of());
        check(store.applyAutomatic(tagId, epoch, revision, scope, features.keySet(), plan, () -> false), "Assignment failed");
        check(store.automatic(tagId).size() == 2, "Not included in automatic browse set");
        check(store.load().history.size() == 1, "Automatic output contaminated human history");
        try (TagStore reopened = new TagStore(context, database)) {
          check(reopened.automatic(tagId).size() == 2, "Automatic results not durable");
        }
        store.automation(tagId, false, false);
        check(store.automatic(tagId).size() == 2, "Stopping erased results");
        check(!store.applyAutomatic(tagId, epoch, revision, scope, features.keySet(), plan, () -> false), "Stopped job wrote");
        epoch = store.automation(tagId, true, false);
        String otherId = store.photoId(other, false);
        store.record("exclude", tagId, otherId, 0, TagJudgments.Value.EXCLUDED);
        check(store.automatic(tagId).size() == 1, "Correction erased unrelated automatic results");
        check(!store.applyAutomatic(tagId, epoch, revision, scope, features.keySet(), plan, () -> false), "Stale judgment version wrote");
        revision = store.judgmentRevision(tagId);
        check(store.applyAutomatic(tagId, epoch, revision, scope, features.keySet(), plan, () -> false), "New version rejected");
        check(store.automatic(tagId).size() == 1, "Manual exclusion overwritten");
        store.undo("undo-exclude", "exclude");
        revision = store.judgmentRevision(tagId);
        store.applyAutomatic(tagId, epoch, revision, scope, features.keySet(), plan, () -> false);
        check(!store.automatic(tagId).containsKey(TagStore.source(other)), "Explicit undecided immediately reattached");
        check(!store.applyAutomatic(tagId, epoch, revision, scope, features.keySet(), Map.of(), () -> true), "Cancellation committed");
        check(store.automatic(tagId).size() == 1, "Cancellation lost previous results");
        store.getWritableDatabase().execSQL("CREATE TRIGGER fail_auto BEFORE INSERT ON automatic_tags BEGIN SELECT RAISE(ABORT, 'test'); END");
        boolean failed = false;
        try { store.applyAutomatic(tagId, epoch, revision, scope, features.keySet(), plan, () -> false); }
        catch (android.database.sqlite.SQLiteException expected) { failed = true; }
        check(failed && store.automatic(tagId).size() == 1, "Partial automatic transaction committed");
        store.getWritableDatabase().execSQL("DROP TRIGGER fail_auto");
        store.automation(tagId, false, true);
        check(store.automatic(tagId).isEmpty(), "Clear failed");
        check(store.load().value(tagId, seedId) == TagJudgments.Value.ASSIGNED, "Clear erased manual tag");
        check(!store.applyAutomatic(tagId, epoch, revision, scope, features.keySet(), plan, () -> false), "Cleared results resurrected");
      }
      return "Automatic tags passed: v1 migration, assignment, durable browse set, human priority, no self-training, stale revision/stop/cancel guards, rollback, clear\n";
    } finally { context.deleteDatabase(database); }
  }
  static PhotoRepository.Photo photo(int id) { return new PhotoRepository.Photo(Uri.parse("content://auto-test/" + id), "test.jpg", "test", 100, 1, 1); }
  static RelatedPhotos.Vector v(float... values) { return RelatedPhotos.Vector.from(values); }
  static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
