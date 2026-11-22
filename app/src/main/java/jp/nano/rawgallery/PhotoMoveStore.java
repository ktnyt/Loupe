package jp.nano.rawgallery;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.io.IOException;
import java.util.UUID;

/** One confirmed batch and stable aliases for photos that have been moved. */
final class PhotoMoveStore extends SQLiteOpenHelper {
  static final int QUEUED = 0, COPYING = 1, VERIFIED = 2, UPDATING_HISTORY = 3, REMAPPED = 4, DONE = 5;
  record Item(String source, String sourceAlias, String version, String expectedSha, String legacyIds, long bytes, String token,
      String target, String sha, int stage, Identity identity, ArchiveProof archiveProof) {
    Item(String source, String sourceAlias, String version, String expectedSha, String legacyIds, long bytes,
        String token, String target, String sha, int stage, Identity identity) {
      this(source, sourceAlias, version, expectedSha, legacyIds, bytes, token, target, sha, stage, identity, null);
    }
  }

  record ArchiveProof(String key, String sha, long historicalGeneration, long historyId, String epoch,
      String historyFingerprint) {
    ArchiveProof {
      if (key == null || !key.matches("[0-9a-f]{32}") || sha == null || !sha.matches("[0-9a-f]{64}")
          || historicalGeneration <= 0 || historyId <= 0 || epoch == null || epoch.isEmpty()
          || historyFingerprint == null || !historyFingerprint.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("Invalid archive move proof");
    }
  }

  private static final String[] ARCHIVE_COLUMNS = {"proof_key TEXT", "proof_sha TEXT", "proof_generation INTEGER",
      "proof_history_id INTEGER", "proof_epoch TEXT", "proof_history_fingerprint TEXT"};
  private static final String ARCHIVE_FIELDS =
      "proof_key,proof_sha,proof_generation,proof_history_id,proof_epoch,proof_history_fingerprint";
  record Batch(String volume, int total, int skipped, int done) {}
  record Identity(String archiveKey, String tagSource, long sortTime) {
    Identity(String archiveKey, String tagSource) { this(archiveKey, tagSource, 0); }
  }

  PhotoMoveStore(Context context) {
    super(context.getApplicationContext(), "photo-moves.db", null, 3);
    setWriteAheadLoggingEnabled(true);
  }

  @Override public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE batch (id INTEGER PRIMARY KEY CHECK(id=1), volume TEXT NOT NULL, "
        + "total INTEGER NOT NULL, skipped INTEGER NOT NULL)");
    db.execSQL("CREATE TABLE items (source TEXT PRIMARY KEY, source_alias TEXT, version TEXT NOT NULL, expected_sha TEXT, legacy_ids TEXT NOT NULL, "
        + "bytes INTEGER NOT NULL, token TEXT, target TEXT, sha TEXT, stage INTEGER NOT NULL DEFAULT 0, "
        + "archive_key TEXT, tag_source TEXT, sort_time INTEGER, " + String.join(",", ARCHIVE_COLUMNS) + ")");
    db.execSQL("CREATE INDEX items_stage ON items(stage)");
    PhotoIdentityIndex.create(db);
    db.execSQL("CREATE TABLE revision (id INTEGER PRIMARY KEY CHECK(id=1), value INTEGER NOT NULL)");
    db.execSQL("INSERT INTO revision VALUES(1,0)");
  }

  @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
    if ((from != 1 && from != 2) || to != 3) throw new IllegalStateException("Unsupported move journal upgrade");
    if (from == 1) upgradeIdentities(db);
    for (String column : ARCHIVE_COLUMNS) db.execSQL("ALTER TABLE items ADD COLUMN " + column);
  }

  private static void upgradeIdentities(SQLiteDatabase db) {
    Set<String> itemColumns = columns(db, "items");
    if (!itemColumns.containsAll(List.of("source", "source_alias", "version", "expected_sha", "bytes",
        "token", "target", "sha", "stage"))) throw new IllegalStateException("Unrecognized move journal items");
    if (!itemColumns.contains("legacy_ids")) {
      if (DatabaseUtils.queryNumEntries(db, "items", "stage<>" + DONE, null) != 0)
        throw new IllegalStateException("Move journal items lack their original import evidence");
      db.execSQL("ALTER TABLE items ADD COLUMN legacy_ids TEXT NOT NULL DEFAULT ''");
    }
    if (!itemColumns.contains("archive_key")) db.execSQL("ALTER TABLE items ADD COLUMN archive_key TEXT");
    if (!itemColumns.contains("tag_source")) db.execSQL("ALTER TABLE items ADD COLUMN tag_source TEXT");
    if (!itemColumns.contains("sort_time")) db.execSQL("ALTER TABLE items ADD COLUMN sort_time INTEGER");
    if (DatabaseUtils.queryNumEntries(db, "items", "stage NOT BETWEEN 0 AND 5 OR "
        + "(stage IN (3,4) AND (archive_key IS NULL OR tag_source IS NULL OR sort_time IS NULL))", null) != 0)
      throw new IllegalStateException("Move journal cannot recover the recorded identity for this stage");

    PhotoIdentityIndex.create(db);
    // Early v1 checkouts recorded moved destinations before sort times and archive claims existed.
    String sortTime = columns(db, "identities").contains("sort_time") ? "sort_time" : "0";
    db.execSQL("INSERT INTO local_photos SELECT location,'',archive_key,tag_source," + sortTime + ","
        + "substr(location,1,instr(location,char(10))-1) FROM identities");
    long expected = DatabaseUtils.queryNumEntries(db, "identities");
    boolean hasClaims = !columns(db, "archive_claims").isEmpty();
    if (hasClaims) {
      // At a moved destination its explicit identity already takes precedence over the original claim.
      try (Cursor rows = db.rawQuery("SELECT legacy_identity,location FROM archive_claims c WHERE NOT EXISTS "
          + "(SELECT 1 FROM identities i WHERE i.location=c.location)", null)) {
        while (rows.moveToNext()) {
          String source = rows.getString(1);
          db.execSQL("INSERT INTO local_photos VALUES(?,'',?,?,0,NULL)",
              new Object[]{source, PhotoRepository.digest(rows.getString(0)), TagStore.legacySource(source)});
          expected++;
        }
      } catch (IOException unavailable) {
        throw new IllegalStateException("Could not migrate photo identities", unavailable);
      }
    }
    if (DatabaseUtils.queryNumEntries(db, "local_photos") != expected)
      throw new IllegalStateException("Move journal identity migration was incomplete");
    db.execSQL("DROP TABLE identities");
    if (hasClaims) db.execSQL("DROP TABLE archive_claims");
    db.execSQL("UPDATE revision SET value=value+1 WHERE id=1");
  }

  private static Set<String> columns(SQLiteDatabase db, String table) {
    Set<String> names = new HashSet<>();
    try (Cursor rows = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
      int name = rows.getColumnIndexOrThrow("name");
      while (rows.moveToNext()) names.add(rows.getString(name));
    }
    return names;
  }

  Batch batch() {
    try (Cursor row = getReadableDatabase().rawQuery(
        "SELECT volume,total,skipped,(SELECT COUNT(*) FROM items WHERE stage=" + DONE + ") FROM batch", null)) {
      return row.moveToFirst() ? new Batch(row.getString(0), row.getInt(1), row.getInt(2), row.getInt(3)) : null;
    }
  }

  void begin(String volume, List<Item> items, int skipped) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      if (batch() != null) throw new IllegalStateException("A move is already waiting");
      db.execSQL("INSERT INTO batch VALUES(1,?,?,?)", new Object[]{volume, items.size(), skipped});
      for (Item item : items) {
        ArchiveProof proof = item.archiveProof();
        db.execSQL("INSERT INTO items(source,source_alias,version,expected_sha,legacy_ids,bytes," + ARCHIVE_FIELDS
                + ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            new Object[]{item.source(), item.sourceAlias(), item.version(), item.expectedSha(), item.legacyIds(), item.bytes(),
                proof == null ? null : proof.key(), proof == null ? null : proof.sha(),
                proof == null ? null : proof.historicalGeneration(), proof == null ? null : proof.historyId(),
                proof == null ? null : proof.epoch(), proof == null ? null : proof.historyFingerprint()});
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  Item next() {
    try (Cursor row = getReadableDatabase().rawQuery(
        "SELECT source,source_alias,version,expected_sha,legacy_ids,bytes,token,target,sha,stage,archive_key,tag_source,sort_time,"
            + ARCHIVE_FIELDS + " FROM items "
            + "WHERE stage<" + DONE + " ORDER BY stage DESC,rowid LIMIT 1", null)) {
      return row.moveToFirst() ? new Item(row.getString(0), row.getString(1), row.getString(2),
          row.getString(3), row.getString(4), row.getLong(5), row.getString(6), row.getString(7), row.getString(8),
          row.getInt(9), row.isNull(10) ? null : new Identity(row.getString(10), row.getString(11), row.getLong(12)),
          archiveProof(row)) : null;
    }
  }

  private static ArchiveProof archiveProof(Cursor row) {
    int empty = 0;
    for (int i = 13; i < 19; i++) if (row.isNull(i)) empty++;
    if (empty == 6) return null;
    if (empty != 0) throw new IllegalStateException("Incomplete archive move proof");
    return new ArchiveProof(row.getString(13), row.getString(14), row.getLong(15), row.getLong(16),
        row.getString(17), row.getString(18));
  }

  boolean historyUpdatePending() {
    Item item = next();
    return item != null && item.stage() == UPDATING_HISTORY;
  }

  long remainingBytes() {
    try (Cursor row = getReadableDatabase().rawQuery(
        "SELECT COALESCE(SUM(bytes),0) FROM items WHERE stage=0", null)) {
      row.moveToFirst();
      return row.getLong(0);
    }
  }

  void writing(Item item, String token) {
    ContentValues values = new ContentValues();
    values.put("token", token);
    values.put("stage", COPYING);
    update(item, values);
  }

  void attach(Item item, String target) {
    ContentValues values = new ContentValues();
    values.put("target", target);
    update(item, values);
  }

  void verified(Item item, String sha) {
    ContentValues values = new ContentValues();
    values.put("sha", sha);
    values.put("stage", VERIFIED);
    update(item, values);
  }

  void updatingHistory(Item item, Identity identity) {
    ContentValues values = new ContentValues();
    values.put("archive_key", identity.archiveKey());
    values.put("tag_source", identity.tagSource());
    values.put("sort_time", identity.sortTime());
    values.put("stage", UPDATING_HISTORY);
    update(item, values);
  }

  void remapped(Item item, PhotoRepository.Photo target, Identity identity) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      PhotoIdentityIndex.save(db, target, identity, target.uri.toString());
      db.execSQL("UPDATE revision SET value=value+1 WHERE id=1");
      setStage(item, REMAPPED);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  void done(Item item) {
    setStage(item, DONE);
  }

  void finish() {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      if (next() != null) throw new IllegalStateException("Move is incomplete");
      clearBatch(db);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  void abandon(String rollbackTarget) throws IOException {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      Item item = next();
      if (rollbackTarget != null) {
        db.delete("local_photos", "move_uri=?", new String[]{rollbackTarget});
      } else if (item != null && item.stage() == REMAPPED) {
        // The verified target owns the history even if the original card is detached during abandon.
        String sourceUri = PhotoMove.galleryUri(Uri.parse(item.source())).toString();
        String duplicate = "retained-copy:" + UUID.randomUUID();
        ContentValues replacement = new ContentValues();
        replacement.put("archive_key", PhotoRepository.digest(duplicate));
        replacement.put("tag_source", duplicate);
        replacement.put("sort_time", 0);
        replacement.putNull("move_uri");
        db.update("local_photos", replacement, "archive_key=? AND tag_source=? AND "
            + "(substr(media_version,1,instr(media_version,char(10))-1)=? OR "
            + "(media_version='' AND substr(location,1,instr(location,char(10))-1)=?))",
            new String[]{item.identity().archiveKey(), item.identity().tagSource(), sourceUri, sourceUri});
      }
      clearBatch(db);
      db.execSQL("UPDATE revision SET value=value+1 WHERE id=1");
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  private static void clearBatch(SQLiteDatabase db) {
    db.delete("items", null, null);
    db.delete("batch", null, null);
  }

  long revision() {
    try (Cursor row = getReadableDatabase().rawQuery("SELECT value FROM revision", null)) {
      row.moveToFirst();
      return row.getLong(0);
    }
  }

  private void setStage(Item item, int stage) {
    ContentValues values = new ContentValues();
    values.put("stage", stage);
    update(item, values);
  }

  private void update(Item item, ContentValues values) {
    if (getWritableDatabase().update("items", values, "source=?", new String[]{item.source()}) != 1)
      throw new IllegalStateException("Move journal entry is missing");
  }
}
