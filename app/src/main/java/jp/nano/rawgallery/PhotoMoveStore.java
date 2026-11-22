package jp.nano.rawgallery;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    super(context.getApplicationContext(), "photo-moves.db", null, 1);
    setWriteAheadLoggingEnabled(true);
  }

  @Override public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE batch (id INTEGER PRIMARY KEY CHECK(id=1), volume TEXT NOT NULL, "
        + "total INTEGER NOT NULL, skipped INTEGER NOT NULL)");
    db.execSQL("CREATE TABLE items (source TEXT PRIMARY KEY, source_alias TEXT, version TEXT NOT NULL, expected_sha TEXT, legacy_ids TEXT NOT NULL, "
        + "bytes INTEGER NOT NULL, token TEXT, target TEXT, sha TEXT, stage INTEGER NOT NULL DEFAULT 0, "
        + "archive_key TEXT, tag_source TEXT, sort_time INTEGER)");
    db.execSQL("CREATE INDEX items_stage ON items(stage)");
    db.execSQL("CREATE TABLE identities (location TEXT PRIMARY KEY, archive_key TEXT NOT NULL, tag_source TEXT NOT NULL, sort_time INTEGER NOT NULL)");
    db.execSQL("CREATE TABLE archive_claims (legacy_identity TEXT PRIMARY KEY, location TEXT NOT NULL)");
    db.execSQL("CREATE TABLE revision (id INTEGER PRIMARY KEY CHECK(id=1), value INTEGER NOT NULL)");
    db.execSQL("INSERT INTO revision VALUES(1,0)");
  }

  @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
    throw new IllegalStateException("Unsupported move journal upgrade");
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

  void remapped(Item item, String location, Identity identity) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      db.execSQL("INSERT OR REPLACE INTO identities VALUES(?,?,?,?)",
          new Object[]{location, identity.archiveKey(), identity.tagSource(), identity.sortTime()});
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

  void abandon(String identityLocation) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      if (identityLocation != null)
        db.delete("identities", "substr(location,1,length(?)+1)=? || char(10)", new String[]{identityLocation, identityLocation});
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

  Identity identity(String location) {
    try (Cursor row = getReadableDatabase().rawQuery(
        "SELECT archive_key,tag_source,sort_time FROM identities WHERE location=?", new String[]{location})) {
      return row.moveToFirst() ? new Identity(row.getString(0), row.getString(1), row.getLong(2)) : null;
    }
  }

  Map<String, Identity> identities() {
    Map<String, Identity> out = new HashMap<>();
    try (Cursor rows = getReadableDatabase().rawQuery(
        "SELECT location,archive_key,tag_source,sort_time FROM identities", null)) {
      while (rows.moveToNext()) out.put(rows.getString(0), new Identity(rows.getString(1), rows.getString(2), rows.getLong(3)));
    }
    return out;
  }

  Map<String, String> archiveClaims(Map<String, String> missing) {
    SQLiteDatabase db = getWritableDatabase();
    if (!missing.isEmpty()) {
      db.beginTransaction();
      try {
        for (var entry : missing.entrySet()) {
          db.execSQL("INSERT OR IGNORE INTO archive_claims VALUES(?,?)", new String[]{entry.getKey(), entry.getValue()});
        }
        db.setTransactionSuccessful();
      } finally {
        db.endTransaction();
      }
    }
    Map<String, String> out = new HashMap<>();
    try (Cursor rows = db.rawQuery("SELECT legacy_identity,location FROM archive_claims", null)) {
      while (rows.moveToNext()) out.put(rows.getString(0), rows.getString(1));
    }
    return out;
  }

  String claimArchiveKey(String legacy, String location) {
    SQLiteDatabase db = getWritableDatabase();
    db.execSQL("INSERT OR IGNORE INTO archive_claims VALUES(?,?)", new String[]{legacy, location});
    try (Cursor row = db.rawQuery("SELECT location FROM archive_claims WHERE legacy_identity=?", new String[]{legacy})) {
      if (!row.moveToFirst()) throw new IllegalStateException("Archive key claim is missing");
      return row.getString(0);
    }
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
