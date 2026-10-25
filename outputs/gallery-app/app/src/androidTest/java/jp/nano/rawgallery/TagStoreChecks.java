package jp.nano.rawgallery;

import android.content.Context;
import android.net.Uri;
import java.util.UUID;

/** Uses an isolated database and synthetic photo references; never reads real photos. */
final class TagStoreChecks {
  static String run(Context context) {
    String database = "tag-test-" + UUID.randomUUID() + ".db";
    PhotoRepository.Photo photo = new PhotoRepository.Photo(Uri.parse("content://test/photo/1"), "one.dng", "test", 100, 10, 0);
    String tagId, photoId;
    try {
      try (TagStore store = new TagStore(context, database)) {
        TagStore.Tag tag = store.create(" 猫 "); tagId = tag.id;
        check(store.create("猫").id.equals(tagId), "Duplicate tag");
        photoId = store.photoId(photo, true);
        check(photoId.equals(store.photoId(photo, false)), "Unstable identity");
        PhotoRepository.Photo changed = new PhotoRepository.Photo(photo.uri, photo.name, photo.folder, 101, 10, 0);
        check(store.photoId(changed, false) == null, "Changed version inherited identity");
        PhotoRepository.Photo moved = new PhotoRepository.Photo(Uri.parse("content://test/photo/2"), photo.name, photo.folder, 100, 10, 0);
        check(store.photoId(moved, false) == null, "Moved photo inherited identity");
        store.record("1", tagId, photoId, 0, TagJudgments.Value.ASSIGNED);
        store.record("1", tagId, photoId, 0, TagJudgments.Value.ASSIGNED);
        check(store.load().history.size() == 1, "Duplicate operation");
        store.getWritableDatabase().execSQL("CREATE TRIGGER fail_test BEFORE UPDATE ON reevaluation BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
        boolean failed = false;
        try { store.record("2", tagId, photoId, 1, TagJudgments.Value.EXCLUDED); }
        catch (android.database.sqlite.SQLiteException expected) { failed = true; }
        check(failed, "Failure injection did not run");
        check(store.load().history.size() == 1, "Partial history committed");
        try (android.database.Cursor c = store.getReadableDatabase().rawQuery("SELECT value,revision FROM judgments", null)) {
          check(c.moveToFirst() && c.getString(0).equals("ASSIGNED") && c.getLong(1) == 1, "Partial current state committed");
        }
        store.getWritableDatabase().execSQL("DROP TRIGGER fail_test");
        store.record("2", tagId, photoId, 1, TagJudgments.Value.EXCLUDED);
      }
      try (TagStore reopened = new TagStore(context, database)) {
        check(reopened.load().value(tagId, photoId) == TagJudgments.Value.EXCLUDED, "Restart lost judgment");
        reopened.undo("3", "2");
        reopened.undo("3", "2");
        check(reopened.load().history.size() == 3, "Undo replay duplicated history");
        check(reopened.load().value(tagId, photoId) == TagJudgments.Value.ASSIGNED, "Undo failed");
        try (android.database.Cursor c = reopened.getReadableDatabase().rawQuery("SELECT revision FROM reevaluation WHERE tag=?", new String[] {tagId})) {
          check(c.moveToFirst() && c.getLong(0) == 3, "Reevaluation not atomic");
        }
      }
      return "Tag storage passed: identity separation, idempotency, transaction rollback, reopen, undo, reevaluation\n";
    } finally { context.deleteDatabase(database); }
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
