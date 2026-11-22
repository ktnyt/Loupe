package jp.nano.rawgallery;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import java.util.List;
import java.io.IOException;
import java.util.UUID;

/** One confirmed batch and stable aliases for photos that have been moved. */
final class PhotoMoveStore extends SQLiteOpenHelper {
  static final int QUEUED = 0, COPYING = 1, VERIFIED = 2, UPDATING_HISTORY = 3, REMAPPED = 4, DONE = 5;
  record Item(String source, String sourceAlias, String version, String expectedSha, String legacyIds, long bytes, String token,
      String target, String sha, int stage, Identity identity) {}
  record Batch(String volume, int total, int skipped, int done) {}
  record Identity(String archiveKey, String tagSource, long sortTime) {
    Identity(String archiveKey, String tagSource) { this(archiveKey, tagSource, 0); }
  }

  PhotoMoveStore(Context context) {
    super(context.getApplicationContext(), "photo-moves.db", null, 2);
    setWriteAheadLoggingEnabled(true);
  }

  @Override public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE batch (id INTEGER PRIMARY KEY CHECK(id=1), volume TEXT NOT NULL, "
        + "total INTEGER NOT NULL, skipped INTEGER NOT NULL)");
    db.execSQL("CREATE TABLE items (source TEXT PRIMARY KEY, source_alias TEXT, version TEXT NOT NULL, expected_sha TEXT, legacy_ids TEXT NOT NULL, "
        + "bytes INTEGER NOT NULL, token TEXT, target TEXT, sha TEXT, stage INTEGER NOT NULL DEFAULT 0, "
        + "archive_key TEXT, tag_source TEXT, sort_time INTEGER)");
    db.execSQL("CREATE INDEX items_stage ON items(stage)");
    PhotoIdentityIndex.create(db);
    db.execSQL("CREATE TABLE revision (id INTEGER PRIMARY KEY CHECK(id=1), value INTEGER NOT NULL)");
    db.execSQL("INSERT INTO revision VALUES(1,0)");
  }

  @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
    if (from != 1 || to != 2) throw new IllegalStateException("Unsupported move journal upgrade");
    PhotoIdentityIndex.create(db);
    // Version 1 used an exact presentation URI and version for each moved destination.
    db.execSQL("INSERT INTO local_photos SELECT location,'',archive_key,tag_source,sort_time,"
        + "substr(location,1,instr(location,char(10))-1) FROM identities");
    try (Cursor rows = db.rawQuery("SELECT legacy_identity,location FROM archive_claims", null)) {
      while (rows.moveToNext()) {
        String source = rows.getString(1);
        db.execSQL("INSERT OR IGNORE INTO local_photos VALUES(?,'',?,?,0,NULL)",
            new Object[]{source, PhotoRepository.digest(rows.getString(0)), TagStore.legacySource(source)});
      }
    } catch (IOException unavailable) {
      throw new IllegalStateException("Could not migrate photo identities", unavailable);
    }
    db.execSQL("DROP TABLE identities");
    db.execSQL("DROP TABLE archive_claims");
    db.execSQL("UPDATE revision SET value=value+1 WHERE id=1");
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
        db.execSQL("INSERT INTO items(source,source_alias,version,expected_sha,legacy_ids,bytes) VALUES(?,?,?,?,?,?)",
            new Object[]{item.source(), item.sourceAlias(), item.version(), item.expectedSha(), item.legacyIds(), item.bytes()});
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  Item next() {
    try (Cursor row = getReadableDatabase().rawQuery(
        "SELECT source,source_alias,version,expected_sha,legacy_ids,bytes,token,target,sha,stage,archive_key,tag_source,sort_time FROM items "
            + "WHERE stage<" + DONE + " ORDER BY stage DESC,rowid LIMIT 1", null)) {
      return row.moveToFirst() ? new Item(row.getString(0), row.getString(1), row.getString(2),
          row.getString(3), row.getString(4), row.getLong(5), row.getString(6), row.getString(7), row.getString(8),
          row.getInt(9), row.isNull(10) ? null : new Identity(row.getString(10), row.getString(11), row.getLong(12))) : null;
    }
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
