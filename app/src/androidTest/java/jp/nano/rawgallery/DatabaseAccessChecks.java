package jp.nano.rawgallery;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import jp.nano.rawgallery.camera.HistoryDb;
import org.json.JSONObject;

final class DatabaseAccessChecks {
  private static final int ROWS = 24_000;
  private static final String SEED = "WITH RECURSIVE rows(n) AS (VALUES(1)"
      + " UNION ALL SELECT n + 1 FROM rows WHERE n < " + ROWS + ") ";
  private static final String SOURCE = "content://media/external/images/media/55000";
  private static final String ALIAS = "content://media/external/file/55000";
  private static final String ARCHIVE_LOOKUP =
      "SELECT state,payload FROM photos WHERE state IN (?,?)";
  private static final String HISTORY_LOOKUP =
      "SELECT id,dest_uri,file_name,file_size,shot_at,imported_at,camera_model,camera_serial"
          + " FROM imported WHERE dest_uri IN (?,?,?,?)";

  static String run(Context context) throws Exception {
    String suffix = UUID.randomUUID().toString();
    String archiveName = "index-photos-" + suffix + ".db";
    String historyName = "index-history-" + suffix + ".db";
    try {
      try (ArchiveStore seed = new ArchiveStore(context, archiveName);
          HistoryDb cameras = new HistoryDb(context, historyName)) {
        seedArchives(seed.getWritableDatabase());
        seedHistory(cameras.getWritableDatabase());
      }
      return checkArchives(context, archiveName) + "\n" + checkHistory(context, historyName);
    } finally {
      context.deleteDatabase(archiveName);
      context.deleteDatabase(historyName);
    }
  }

  private static void seedArchives(SQLiteDatabase db) {
    db.execSQL(SEED + "INSERT INTO photos(key,state,payload) SELECT printf('fixture-%d',n),"
        + " CASE WHEN n % 1000 = 1 THEN 'released' WHEN n % 1000 = 2 THEN 'releasing' ELSE 'verified' END,"
        + " json_object('key',printf('fixture-%d',n),'fixture',n,'state','ignored') FROM rows");
  }

  private static void seedHistory(SQLiteDatabase db) {
    db.execSQL("INSERT INTO camera(model,serial,folder,mass_storage) VALUES('fixture','camera','Photos',1)");
    db.execSQL(SEED + "INSERT INTO imported(camera_model,camera_serial,file_name,file_size,shot_at,imported_at,dest_uri,deleted_at)"
        + " SELECT 'fixture','camera',printf('fixture-%d.jpg',n),n,10,20,"
        + " CASE WHEN n % 1000 = 1 THEN ? WHEN n % 1000 = 2 THEN ? ELSE printf('content://test/%d',n) END,"
        + " CASE WHEN n % 2 = 0 THEN 3 ELSE NULL END FROM rows", new Object[] {SOURCE, ALIAS});
  }

  private static String checkArchives(Context context, String name) throws Exception {
    try (ArchiveStore active = new ArchiveStore(context, name);
        ArchiveStore other = new ArchiveStore(context, name)) {
      SQLiteDatabase db = active.getWritableDatabase();
      other.getReadableDatabase();
      String[] states = {E2Cleanup.RELEASING, E2Cleanup.RELEASED};
      var records = active.inStates(states[0], states[1]);
      check(records.size() == 48 && records.get(0).getInt("fixture") == 1
          && records.get(1).getInt("fixture") == 2, "archive matches and legacy row order");
      check("released".equals(records.get(0).getString("state")), "state column remains authoritative");
      long revision = active.indexRevision();
      check(revision == ROWS, "all receipt revisions preserved");
      String plan = plan(db, ARCHIVE_LOOKUP, states);
      double millis = median(() -> active.inStates(states[0], states[1]).size());
      String overlap = overlap(db,
          () -> active.put(new JSONObject().put("key", "fixture-1").put("fixture", -1), "pending"),
          () -> active.get("fixture-1").getInt("fixture"),
          () -> other.get("fixture-1").getInt("fixture"));
      check(active.indexRevision() == revision && active.get("fixture-1").getInt("fixture") == 1,
          "rollback restores archive payload, state, and revision");
      return String.format(Locale.ROOT, "DB archive schema=%d journal=%s count=%d median_ms=%.3f plan=%s overlap=%s",
          db.getVersion(), journal(db), ROWS, millis, plan, overlap);
    }
  }

  private static String checkHistory(Context context, String name) throws Exception {
    try (HistoryDb active = new HistoryDb(context, name);
        HistoryDb other = new HistoryDb(context, name)) {
      SQLiteDatabase db = active.getWritableDatabase();
      other.getReadableDatabase();
      var matches = active.moveEvidenceFor(SOURCE, ALIAS, null, null);
      check(matches.size() == 48 && matches.get(0).getId() == 1 && matches.get(1).getId() == 2,
          "history matches and legacy row order");
      check(active.findCamera("fixture", "camera").getMassStorage(), "camera settings preserved");
      check(active.photoImportRecords("fixture", "camera").stream()
          .anyMatch(row -> row.key.equals("fixture-2.jpg|2|10") && row.deleted), "deletion evidence preserved");
      String plan = plan(db, HISTORY_LOOKUP, new String[] {SOURCE, ALIAS, SOURCE, ALIAS});
      double millis = median(() -> active.moveEvidenceFor(SOURCE, ALIAS, null, null).size());
      String overlap = overlap(db,
          () -> check(active.relocate(SOURCE, ALIAS, "content://test/new", "1"), "tentative relocation"),
          () -> active.moveEvidenceFor(SOURCE, ALIAS, null, null).get(0).getId(),
          () -> other.moveEvidenceFor(SOURCE, ALIAS, null, null).get(0).getId());
      check(active.hasMoveEvidence(SOURCE, ALIAS, "1") && active.containsMoveEvidence(1),
          "rollback restores import history identity and destination");
      return String.format(Locale.ROOT, "DB history schema=%d journal=%s count=%d median_ms=%.3f plan=%s overlap=%s",
          db.getVersion(), journal(db), ROWS, millis, plan, overlap);
    }
  }

  private static String overlap(SQLiteDatabase writer, CheckedRunnable update,
      Callable<? extends Number> same, Callable<? extends Number> other) throws Exception {
    var threads = Executors.newFixedThreadPool(2);
    CountDownLatch started = new CountDownLatch(2);
    try {
      Future<? extends Number> sameRead, otherRead;
      boolean sameDuring, otherDuring;
      writer.beginTransactionNonExclusive();
      try {
        update.run();
        sameRead = threads.submit(() -> { started.countDown(); return same.call(); });
        otherRead = threads.submit(() -> { started.countDown(); return other.call(); });
        check(started.await(5, TimeUnit.SECONDS), "both readers ready");
        sameDuring = completedWhileOpen(sameRead);
        otherDuring = completedWhileOpen(otherRead);
      } finally {
        writer.endTransaction();
      }
      check(sameRead.get(5, TimeUnit.SECONDS).longValue() == 1
          && otherRead.get(5, TimeUnit.SECONDS).longValue() == 1,
          "readers only see committed data");
      return "same:" + sameDuring + ",separate:" + otherDuring;
    } finally {
      threads.shutdownNow();
      check(threads.awaitTermination(5, TimeUnit.SECONDS), "readers finish before cleanup");
    }
  }

  private static boolean completedWhileOpen(Future<?> read) throws Exception {
    try {
      read.get(750, TimeUnit.MILLISECONDS);
      return true;
    } catch (java.util.concurrent.TimeoutException blocked) {
      return false;
    }
  }

  private static double median(Callable<Integer> query) throws Exception {
    check(query.call() == 48, "warmup count");
    var measurements = new long[31];
    for (int i = 0; i < measurements.length; i++) {
      long start = SystemClock.elapsedRealtimeNanos();
      int count = query.call();
      measurements[i] = SystemClock.elapsedRealtimeNanos() - start;
      check(count == 48, "same query results");
    }
    java.util.Arrays.sort(measurements);
    return measurements[measurements.length / 2] / 1_000_000.0;
  }

  private static String plan(SQLiteDatabase db, String sql, String[] args) {
    var details = new ArrayList<String>();
    try (var rows = db.rawQuery("EXPLAIN QUERY PLAN " + sql, args)) {
      while (rows.moveToNext()) details.add(rows.getString(3));
    }
    return String.join(";", details);
  }

  private static String journal(SQLiteDatabase db) {
    try (var row = db.rawQuery("PRAGMA journal_mode", null)) {
      check(row.moveToFirst(), "journal mode present");
      return row.getString(0);
    }
  }

  @FunctionalInterface
  private interface CheckedRunnable {
    void run() throws Exception;
  }

  private static void check(boolean valid, String label) {
    if (!valid) throw new AssertionError(label);
  }
}
