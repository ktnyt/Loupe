package jp.nano.rawgallery;

import android.content.Context;
import android.net.Uri;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class TagStoreIncrementalChecks {
  private static final TagJudgments.Value YES = TagJudgments.Value.ASSIGNED;
  private static final TagJudgments.Value NO = TagJudgments.Value.EXCLUDED;
  private static final TagJudgments.Value NONE = TagJudgments.Value.UNDECIDED;

  static void run(Context context) {
    String name = "tag-incremental-test-" + UUID.randomUUID() + ".db";
    try {
      String tag = "legacy-tag", first = "legacy-first", second = "legacy-second", third = "legacy-third";
      TagJudgments expected = new TagJudgments().record("first", tag, first, 0, YES);
      try (var db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)) {
        LegacyTagDatabase.create(db, 3);
        LegacyTagDatabase.insertAssignment(db, tag, "判断", first, photo(1), "first");
        LegacyTagDatabase.insertPhoto(db, second, photo(2));
        LegacyTagDatabase.insertPhoto(db, third, photo(3));
      }
      try (TagStore store = new TagStore(context, name)) {
        same(expected, store.load());
        same(expected, store.record("first", tag, first, 0, YES));
        check(
            store.getReadableDatabase().getVersion() == 4, "version 3 upgraded with same history");
        assertPlan(
            store,
            "EXPLAIN QUERY PLAN SELECT revision FROM history WHERE operation=?",
            new String[] {"first"},
            "sqlite_autoindex_history_1");
        assertPlan(
            store,
            "EXPLAIN QUERY PLAN SELECT value FROM history WHERE tag=? AND photo=? AND revision=?",
            new String[] {tag, first, "1"},
            "history_judgment_revision");
        assertPlan(
            store,
            "EXPLAIN QUERY PLAN SELECT revision FROM judgments WHERE tag=? AND photo=?",
            new String[] {tag, first},
            "sqlite_autoindex_judgments_1");
        expect(IllegalArgumentException.class, () -> store.record("first", tag, first, 0, NO));
        expect(IllegalArgumentException.class, () -> store.record("first", tag, second, 0, YES));

        var batch = new LinkedHashMap<String, Long>();
        batch.put(second, 0L);
        batch.put(first, 0L);
        expect(
            TagJudgments.StaleRevisionException.class,
            () -> store.recordBatch("batch", tag, batch, NO));
        same(expected, store.load());
        eq(1L, store.judgmentRevision(tag), "no partial reevaluation");

        batch.put(first, 1L);
        expected =
            expected
                .record("batch:" + second, tag, second, 0, NO)
                .record("batch:" + first, tag, first, 1, NO);
        same(expected, store.recordBatch("batch", tag, batch, NO));
        same(expected, store.recordBatch("batch", tag, batch, NO));
        expect(IllegalArgumentException.class, () -> store.recordBatch("batch", tag, batch, YES));
        expect(TagJudgments.StaleRevisionException.class, () -> store.undo("stale-undo", "first"));
        expect(IllegalArgumentException.class, () -> store.undo("unknown-undo", "missing"));
        expect(IllegalArgumentException.class, () -> store.undo("first", "batch:" + first));
        expected = expected.undo("undo", "batch:" + first);
        same(expected, store.undo("undo", "batch:" + first));
        expected = expected.undo("redo", "undo");
        same(expected, store.undo("redo", "undo"));
        // An exact retry still returns the complete current state after the target has advanced.
        same(expected, store.undo("undo", "batch:" + first));
        expected = expected.undo("cancel", "batch:" + second);
        same(expected, store.undo("cancel", "batch:" + second));
        eq(NONE, store.load().value(tag, second), "first decision undoes to undecided");
      }
      try (TagStore store = new TagStore(context, name)) {
        same(expected, store.load());
        eq(6L, store.judgmentRevision(tag), "only committed entries advance reevaluation");
      }
      concurrent(context, name, tag, first, third, expected);
    } finally {
      context.deleteDatabase(name);
    }
  }

  private static void concurrent(
      Context context, String name, String tag, String first, String third, TagJudgments initial) {
    var pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
    try {
      var left = pool.submit(() -> competingWrite(context, name, tag, third, "left", ready, start));
      var right =
          pool.submit(() -> competingWrite(context, name, tag, third, "right", ready, start));
      check(ready.await(10, TimeUnit.SECONDS), "both database connections opened");
      start.countDown();
      String leftResult = left.get(10, TimeUnit.SECONDS);
      String rightResult = right.get(10, TimeUnit.SECONDS);
      check((leftResult == null) ^ (rightResult == null), "one stale writer rejected");
      String winner = leftResult == null ? rightResult : leftResult;
      expectedWin(context, name, tag, first, third, initial.record(winner, tag, third, 0, YES));
    } catch (Exception error) {
      throw new AssertionError("concurrent tag save", error);
    } finally {
      start.countDown();
      pool.shutdownNow();
      try {
        check(
            pool.awaitTermination(10, TimeUnit.SECONDS), "workers stopped before database cleanup");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }
  }

  private static String competingWrite(
      Context context,
      String name,
      String tag,
      String photo,
      String operation,
      CountDownLatch ready,
      CountDownLatch start)
      throws Exception {
    try (TagStore store = new TagStore(context, name)) {
      store.getWritableDatabase();
      ready.countDown();
      check(start.await(10, TimeUnit.SECONDS), "release concurrent writers");
      try {
        store.record(operation, tag, photo, 0, YES);
        return operation;
      } catch (TagJudgments.StaleRevisionException expected) {
        return null;
      }
    }
  }

  private static void expectedWin(
      Context context, String name, String tag, String first, String third, TagJudgments expected) {
    try (TagStore independent = new TagStore(context, name)) {
      same(expected, independent.load());
      var next = expected.record("other-photo", tag, first, 4, YES);
      var snapshot = independent.record("other-photo", tag, first, 4, YES);
      same(next, snapshot);
      eq(YES, snapshot.value(tag, third), "snapshot returned another connection's write");
      eq(8L, independent.judgmentRevision(tag), "same photo winner and other photo counted once");
    }
  }

  private static PhotoRepository.Photo photo(int index) {
    return new PhotoRepository.Photo(
        Uri.parse("content://test/tag-photo/" + index),
        "fixture-" + index + ".jpg",
        "test/",
        10,
        2,
        0);
  }

  private static void assertPlan(TagStore store, String sql, String[] args, String index) {
    try (var rows = store.getReadableDatabase().rawQuery(sql, args)) {
      check(rows.moveToNext() && rows.getString(3).contains(index), "indexed lookup " + index);
    }
  }

  private static void same(TagJudgments expected, TagJudgments actual) {
    eq(expected.history.size(), actual.history.size(), "complete sequence");
    for (int i = 0; i < expected.history.size(); i++) {
      var left = expected.history.get(i);
      var right = actual.history.get(i);
      eq(left.operationId, right.operationId, "operation");
      eq(left.tagId, right.tagId, "tag");
      eq(left.photoId, right.photoId, "photo");
      eq(left.value, right.value, "value");
      eq(left.revision, right.revision, "revision");
      eq(left.undoneOperationId, right.undoneOperationId, "undo target");
    }
  }

  private static void expect(Class<? extends RuntimeException> type, Runnable operation) {
    try {
      operation.run();
    } catch (RuntimeException error) {
      if (type.isInstance(error)) return;
      throw error;
    }
    throw new AssertionError("Missing exception " + type.getSimpleName());
  }

  private static void eq(Object expected, Object actual, String message) {
    check(
        java.util.Objects.equals(expected, actual),
        message + " expected=" + expected + " actual=" + actual);
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
