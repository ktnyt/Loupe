package jp.nano.rawgallery;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises catalog generations and SQLite migration without an account or network. */
final class E2CatalogChecks {
  private static final String BUCKET = "synthetic-bucket";
  private static final String LEGACY = E2RecoveryCatalog.LEGACY;
  private static final String INDEX = E2RecoveryCatalog.INDEX;

  private static class Bucket implements E2RecoveryCatalog.Storage {
    final Map<String, LinkedHashMap<String, E2Archive.CatalogObject>> objects = new HashMap<>();
    final List<String> originals = new ArrayList<>();
    int writes;
    String hidden = "", corrupt = "", denied = "";
    boolean failPart;

    E2Archive.CatalogObject seed(String key, byte[] body) {
      LinkedHashMap<String, E2Archive.CatalogObject> history =
          objects.computeIfAbsent(key, unused -> new LinkedHashMap<>());
      String id = "version-" + (history.size() + 1);
      E2Archive.CatalogObject value = new E2Archive.CatalogObject(body, id, "\"" + id + "\"");
      history.put(id, value);
      return value;
    }

    @Override public List<String> previousCatalogVersions(String key) {
      List<String> versions = new ArrayList<>(objects.getOrDefault(key, new LinkedHashMap<>()).keySet());
      java.util.Collections.reverse(versions);
      return versions;
    }

    @Override public E2Archive.CatalogObject readCatalog(String key, String version) throws Exception {
      if (key.equals(denied)) throw new IOException("Synthetic permission failure");
      if (key.equals(hidden) || !objects.containsKey(key)) return null;
      if (version.isEmpty()) version = previousCatalogVersions(key).get(0);
      E2Archive.CatalogObject value = objects.get(key).get(version);
      if (value != null && key.equals(corrupt))
        return new E2Archive.CatalogObject(new byte[] {'?'}, value.version(), value.etag());
      return value;
    }

    @Override public E2Archive.CatalogObject writeCatalog(String key, byte[] body,
        E2Archive.CatalogObject expected) throws Exception {
      if (!key.equals(INDEX) && (failPart || body.length > E2RecoveryCatalog.PART_BYTES))
        throw new IOException("Synthetic rejected part");
      E2Archive.CatalogObject current = readCatalog(key, "");
      if (expected == null ? current != null : current == null || !current.etag().equals(expected.etag()))
        throw new IOException("Synthetic precondition failure");
      writes++;
      return seed(key, body);
    }

    @Override public void verifyVersion(JSONObject row) throws Exception {
      originals.add(row.getString("version"));
    }
  }

  private static JSONObject row(int i, String name, String version) throws Exception {
    return new JSONObject().put("key", String.format(Locale.ROOT, "%032x", i))
        .put("sha", String.format(Locale.ROOT, "%064x", i)).put("name", name).put("size", i + 1)
        .put("bucket", BUCKET).put("provider", "idrive-e2").put("state", "verified")
        .put("version", version);
  }

  private static byte[] legacy(JSONObject... rows) throws Exception {
    String payload = new JSONObject().put("schema", 1).put("created", 1234)
        .put("bucket", BUCKET).put("count", rows.length).put("photos", new JSONArray(Arrays.asList(rows)))
        .toString();
    return new JSONObject().put("payload", payload)
        .put("sha256", E2Archive.sha256(payload.getBytes(StandardCharsets.UTF_8)))
        .toString().getBytes(StandardCharsets.UTF_8);
  }

  private static String partKey(E2RecoveryCatalog.Snapshot snapshot, int index) throws Exception {
    JSONObject indexDoc = new JSONObject(new String(snapshot.index().body(), StandardCharsets.UTF_8));
    JSONArray parts = new JSONObject(indexDoc.getString("payload")).getJSONArray("parts");
    return "loupe/catalog/parts/" + parts.getJSONObject(index).getString("sha256") + ".json";
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private interface Action { void run() throws Exception; }

  private static void rejected(Action action) throws Exception {
    try {
      action.run();
    } catch (IOException expected) {
      return;
    }
    throw new AssertionError("Expected operation to fail closed");
  }

  static String run() throws Exception { return exercise(null); }

  static String run(Context context) throws Exception { return exercise(context); }

  /** Opt-in device scale check; kept separate from the fast host and regular instrumentation suite. */
  static String runScale(Context context) throws Exception {
    if (context == null) throw new IllegalArgumentException("Scale check requires Android context");
    final int count = 70_001;
    String bucketName = BUCKET;
    Bucket bucket = new Bucket();
    List<JSONObject> rows = new AbstractList<>() {
      @Override public JSONObject get(int index) {
        try {
          return row(index, "scale-" + index + (index % 2 == 0 ? ".ORF" : ".MP4"),
              "scale-version-" + index);
        } catch (Exception failure) {
          throw new IllegalStateException(failure);
        }
      }

      @Override public int size() { return count; }
    };
    Runtime runtime = Runtime.getRuntime();
    long start = SystemClock.elapsedRealtime();
    long beforeMemory = usedMemory(runtime);
    E2RecoveryCatalog.Snapshot published = E2RecoveryCatalog.publish(bucket, bucketName, rows);
    long afterPublish = SystemClock.elapsedRealtime();
    long publishMemory = usedMemory(runtime);
    check(published.records().size() == count, "Scale publish count mismatch");
    check(published.records().containsKey(String.format(Locale.ROOT, "%032x", 0)),
        "Scale publish lost first row");
    check(published.records().containsKey(String.format(Locale.ROOT, "%032x", count - 1)),
        "Scale publish lost last row");

    String database = "e2-catalog-scale-" + UUID.randomUUID() + ".db";
    long afterFreshLoad;
    long loadMemory;
    long afterCorruptTailCheck;
    long afterReopen;
    String tail = partKey(published, published.partVersions().size() - 1);
    try {
      try (E2RecoveryCatalog receiver = new E2RecoveryCatalog(context, database)) {
        E2RecoveryCatalog.Snapshot loaded = receiver.load(bucket, bucketName, List.of());
        check(loaded != null && loaded.records().size() == count, "Scale fresh load count mismatch");
        afterFreshLoad = SystemClock.elapsedRealtime();
        loadMemory = usedMemory(runtime);
        bucket.corrupt = tail;
        rejected(() -> receiver.load(bucket, bucketName, List.of()));
        E2RecoveryCatalog.Snapshot preserved = receiver.cached(bucketName);
        check(preserved != null && preserved.records().size() == count,
            "Corrupt catalog tail replaced the complete cached generation");
        afterCorruptTailCheck = SystemClock.elapsedRealtime();
      }
      long reopenStart = SystemClock.elapsedRealtime();
      try (E2RecoveryCatalog reopened = new E2RecoveryCatalog(context, database)) {
        E2RecoveryCatalog.Snapshot cached = reopened.cached(bucketName);
        check(cached != null && cached.records().size() == count,
            "Scale cache reopen count mismatch");
        JSONObject first = cached.records().get(String.format(Locale.ROOT, "%032x", 0));
        JSONObject last = cached.records().get(String.format(Locale.ROOT, "%032x", count - 1));
        JSONObject lastVideo = cached.records().get(String.format(Locale.ROOT, "%032x", count - 2));
        check(first != null && "scale-0.ORF".equals(first.getString("name"))
            && "image/x-olympus-orf".equals(first.getString("mime")),
            "Scale reopen lost first RAW row");
        check(last != null && "scale-70000.ORF".equals(last.getString("name"))
            && "image/x-olympus-orf".equals(last.getString("mime")),
            "Scale reopen lost last RAW row");
        check(lastVideo != null && "video/mp4".equals(lastVideo.getString("mime")),
            "Scale reopen lost video row");
        afterReopen = SystemClock.elapsedRealtime();
      }
      long finishedMemory = usedMemory(runtime);
      return "PASS e2 catalog scale: " + count + " RAW/video rows, publish="
          + (afterPublish - start) + "ms, freshLoad=" + (afterFreshLoad - afterPublish)
          + "ms, corrupt-tail-check=" + (afterCorruptTailCheck - afterFreshLoad)
          + "ms, reopen=" + (afterReopen - reopenStart)
          + "ms; used-heap samples before/publish/load/finish=" + beforeMemory + "/"
          + publishMemory + "/" + loadMemory + "/" + finishedMemory + " bytes";
    } finally {
      context.deleteDatabase(database);
    }
  }

  private static long usedMemory(Runtime runtime) {
    return runtime.totalMemory() - runtime.freeMemory();
  }

  private static String exercise(Context context) throws Exception {
    E2RecoveryCatalog.Rows wrongBucket = new E2RecoveryCatalog.Rows(BUCKET);
    wrongBucket.add(row(998, "bucket.jpg", "version-a"));
    Bucket isolated = new Bucket();
    rejected(() -> E2RecoveryCatalog.publish(isolated, "different-bucket", wrongBucket));
    check(isolated.writes == 0, "A mismatched bucket was written");
    if (context != null) checkLocalReceipts(context);
    long largeSize = E2Archive.LIMIT + 1024L * 1024;
    JSONObject large = row(1, "large.MP4", "large-version").put("size", largeSize);
    Bucket largeLegacy = new Bucket();
    largeLegacy.seed(LEGACY, legacy(large));
    rejected(() -> E2RecoveryCatalog.remote(largeLegacy, BUCKET));
    Bucket largeModern = new Bucket();
    E2RecoveryCatalog.publish(largeModern, BUCKET, List.of(large));
    JSONObject restoredLarge = E2RecoveryCatalog.remote(largeModern, BUCKET).records()
        .get(large.getString("key"));
    check(largeSize == restoredLarge.getLong("size") && "video/mp4".equals(restoredLarge.getString("mime")),
        "New catalog omitted the large video");
    Bucket bucket = new Bucket();
    JSONObject original = row(0, "original.ORF", "original-v1");
    byte[] v1 = legacy(original);
    bucket.seed(LEGACY, v1);
    check(E2RecoveryCatalog.remote(bucket, BUCKET).records().size() == 1, "Unreadable legacy catalog");
    List<JSONObject> rows = new ArrayList<>();
    rows.add(row(0, "original.ORF", "replayed-v2"));
    for (int i = 1; i <= 2500; i++)
      rows.add(row(i, "sample-" + i + (i % 2 == 0 ? ".MOV" : ".jpg"), "media-version"));
    E2RecoveryCatalog.Snapshot first = E2RecoveryCatalog.publish(bucket, BUCKET, rows);
    check(first.records().size() == 2501 && first.partVersions().size() > 1, "Not all rows migrated");
    check(bucket.originals.contains("original-v1")
        && first.records().get(original.getString("key")).getString("version").equals("original-v1"),
        "Legacy replay lost the published version");
    check("video/quicktime".equals(first.records().get(rows.get(2).getString("key")).getString("mime")),
        "Video MIME lost");
    check(Arrays.equals(v1, bucket.readCatalog(LEGACY, "").body()), "Overwrote v1");
    int writes = bucket.writes;
    E2RecoveryCatalog.publish(bucket, BUCKET, rows);
    check(bucket.writes == writes, "Identical catalog issued PUTs");
    JSONObject late = row(3000, "late.dng", "after-migration");
    bucket.seed(LEGACY, legacy(row(0, "original.ORF", "legacy-replay"), late));
    E2RecoveryCatalog.Snapshot beforePublish;
    if (context == null) {
      beforePublish = E2RecoveryCatalog.remote(bucket, BUCKET);
    } else {
      String database = "e2-readonly-receiver-" + UUID.randomUUID() + ".db";
      try {
        try (E2RecoveryCatalog receiver = new E2RecoveryCatalog(context, database)) {
          receiver.load(bucket, BUCKET, List.of());
        }
        try (E2RecoveryCatalog receiver = new E2RecoveryCatalog(context, database)) {
          beforePublish = receiver.cached(BUCKET);
        }
      } finally {
        context.deleteDatabase(database);
      }
    }
    check(beforePublish.records().size() == 2502
        && "after-migration".equals(beforePublish.records().get(late.getString("key")).getString("version"))
        && "original-v1".equals(beforePublish.records().get(original.getString("key")).getString("version"))
        && !beforePublish.previous() && bucket.writes == writes, "Read-only import lost a late v1 update");
    bucket.seed(LEGACY, legacy(row(0, "other-content.jpg", "legacy-replay"), late));
    rejected(() -> E2RecoveryCatalog.remote(bucket, BUCKET));
    check(bucket.writes == writes, "Conflicting read-only import issued PUTs");
    bucket.seed(LEGACY, legacy(original, late));
    rows.add(row(3100, "v2-only.jpg", "v2-only-version"));
    E2RecoveryCatalog.Snapshot second = E2RecoveryCatalog.publish(bucket, BUCKET, rows);
    check(second.records().size() == 2503 && second.records().containsKey(late.getString("key")),
        "Lost a late v1 update");
    check(bucket.writes == writes + 2, "Unmodified parts were rewritten");
    E2RecoveryCatalog.confirmLatest(bucket, second);
    rejected(() -> E2RecoveryCatalog.confirmLatest(bucket, first));
    writes = bucket.writes;
    check(E2RecoveryCatalog.latest(bucket, BUCKET).records().size() == 2503, "Strict roundtrip failed");
    String tail = partKey(second, second.partVersions().size() - 1);
    bucket.corrupt = tail;
    rejected(() -> E2RecoveryCatalog.confirmLatest(bucket, second));
    check(E2RecoveryCatalog.remote(bucket, BUCKET).previous(), "Corrupt latest part accepted");
    rejected(() -> E2RecoveryCatalog.latest(bucket, BUCKET));
    bucket.corrupt = "";
    bucket.hidden = tail;
    rejected(() -> E2RecoveryCatalog.confirmLatest(bucket, second));
    E2RecoveryCatalog.Snapshot fallback = E2RecoveryCatalog.remote(bucket, BUCKET);
    check(fallback.previous() && fallback.records().size() == 2502, "Wrong previous generation");
    rejected(() -> E2RecoveryCatalog.publish(bucket, BUCKET, rows));
    bucket.hidden = "";
    bucket.denied = tail;
    rejected(() -> E2RecoveryCatalog.remote(bucket, BUCKET));
    bucket.denied = "";
    check(bucket.writes == writes, "Recovery or unverifiable publication issued PUTs");
    rows.add(row(0, "different.jpg", "version-x"));
    rejected(() -> E2RecoveryCatalog.publish(bucket, BUCKET, rows));
    rows.remove(rows.size() - 1);
    rows.add(row(3200, "new.webm", "new-original"));
    bucket.failPart = true;
    rejected(() -> E2RecoveryCatalog.publish(bucket, BUCKET, rows));
    check(bucket.readCatalog(INDEX, "").version().equals(second.index().version()),
        "Published partial generation");
    if (context != null) cache(context, bucket, original, v1, tail);
    String cache = context == null ? "" : ", SQLite v1 migration and atomic preservation";
    return "PASS e2 catalog: v1 replay, 2503 rows, multipart publish/read, prior generation, fail-closed writes" + cache;
  }

  private static void checkLocalReceipts(Context context) throws Exception {
    String database = "e2-receipts-scale-" + UUID.randomUUID() + ".db";
    try (ArchiveStore store = new ArchiveStore(context, database)) {
      SQLiteDatabase db = store.getWritableDatabase();
      // A state-filtered read must never parse the unrelated payloads.
      db.execSQL("WITH RECURSIVE ids(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM ids WHERE n<70000)"
          + " INSERT INTO photos SELECT 'unrelated-' || n, 'verified', 'invalid-json' FROM ids");
      JSONObject old = row(0, "historical.jpg", "version-old").put("taken", -1);
      store.put(old, "released");
      List<JSONObject> selected = store.inStates("released", "releasing");
      check(selected.size() == 1 && "historical.jpg".equals(selected.get(0).getString("name")),
          "State query opened unrelated receipts");
      check(store.inStates("releasing").isEmpty(), "Picked archive rows without a pending deletion");
      db.delete("photos", "state=?", new String[] {"verified"});
      E2RecoveryCatalog.Rows local = E2Photos.localSaved(store, BUCKET);
      check(local.size() == 1 && local.sorted().get(0).getLong("taken") == 0,
          "A verified historical local original was omitted");
    } finally {
      context.deleteDatabase(database);
    }
  }

  private static void cache(Context context, Bucket bucket, JSONObject original, byte[] v1,
      String tail) throws Exception {
    String name = "e2-catalog-test-" + UUID.randomUUID() + ".db";
    try {
      try (SQLiteDatabase db = context.openOrCreateDatabase(name, 0, null)) {
        db.execSQL("CREATE TABLE snapshots (bucket TEXT PRIMARY KEY, body BLOB NOT NULL)");
        ContentValues values = new ContentValues();
        values.put("bucket", BUCKET);
        values.put("body", v1);
        db.insertOrThrow("snapshots", null, values);
        db.setVersion(1);
      }
      try (E2RecoveryCatalog db = new E2RecoveryCatalog(context, name)) {
        check(db.cached(BUCKET).records().containsKey(original.getString("key")),
            "Lost v1 cache during schema migration");
        db.load(bucket, BUCKET, List.of());
      }
      try (E2RecoveryCatalog db = new E2RecoveryCatalog(context, name)) {
        check(db.cached(BUCKET).records().size() == 2503, "Large cache roundtrip failed");
        bucket.hidden = tail;
        rejected(() -> db.load(bucket, BUCKET, List.of()));
        check(db.cached(BUCKET).records().size() == 2503, "Partial import replaced complete cache");
        bucket.hidden = "";
        db.getWritableDatabase().execSQL("CREATE TRIGGER fail_mid_import BEFORE INSERT ON catalog_rows"
            + " WHEN NEW.ordinal=100 BEGIN SELECT RAISE(ABORT, 'synthetic storage failure'); END");
        try {
          db.load(bucket, BUCKET, List.of());
          throw new AssertionError("Expected synthetic SQLite failure");
        } catch (android.database.sqlite.SQLiteException expected) {
          check(db.cached(BUCKET).records().size() == 2503, "SQLite rollback lost the previous cache");
        }
      }
    } finally {
      context.deleteDatabase(name);
    }
  }
}
