package jp.nano.rawgallery;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/** Primary human data, independent of GalleryIndex. See docs/tag-judgment-contract.md. */
final class TagStore extends SQLiteOpenHelper {
  static final class Tag {
    final String id, name;
    Tag(String id, String name) { this.id = id; this.name = name; }
  }

  TagStore(Context context) { this(context, "tags.db"); }
  TagStore(Context context, String databaseName) { super(context, databaseName, null, 2); }

  public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE tags (id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE)");
    db.execSQL("CREATE TABLE photos (id TEXT PRIMARY KEY, source TEXT NOT NULL UNIQUE)");
    db.execSQL("CREATE TABLE history (seq INTEGER PRIMARY KEY AUTOINCREMENT, operation TEXT NOT NULL UNIQUE, tag TEXT NOT NULL, photo TEXT NOT NULL, revision INTEGER NOT NULL, value TEXT NOT NULL, undone TEXT)");
    db.execSQL("CREATE TABLE judgments (tag TEXT NOT NULL, photo TEXT NOT NULL, revision INTEGER NOT NULL, value TEXT NOT NULL, PRIMARY KEY(tag,photo))");
    db.execSQL("CREATE TABLE reevaluation (tag TEXT PRIMARY KEY, revision INTEGER NOT NULL)");
    createAutomaticTables(db);
  }

  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (oldVersion == 1 && newVersion == 2) createAutomaticTables(db);
    else throw new IllegalStateException("Unsupported tag database version");
  }

  private static void createAutomaticTables(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE automation (tag TEXT PRIMARY KEY, enabled INTEGER NOT NULL, epoch INTEGER NOT NULL)");
    db.execSQL("CREATE TABLE automatic_tags (tag TEXT NOT NULL, photo TEXT NOT NULL, judgment_revision INTEGER NOT NULL, pipeline TEXT NOT NULL, epoch INTEGER NOT NULL, positive REAL NOT NULL, negative REAL NOT NULL, threshold REAL NOT NULL, PRIMARY KEY(tag,photo))");
  }

  static String automaticVersion() { return PhotoFeatures.PIPELINE + ":" + AutomaticTags.VERSION; }

  long judgmentRevision(String tag) {
    try (Cursor c = getReadableDatabase().rawQuery("SELECT revision FROM reevaluation WHERE tag=?", new String[] {tag})) {
      return c.moveToFirst() ? c.getLong(0) : 0;
    }
  }

  /** Starting/stopping invalidates any job which holds an earlier epoch. Stop retains results. */
  long automation(String tag, boolean enabled, boolean clear) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      requireReference(db, "tags", tag);
      db.execSQL("INSERT OR IGNORE INTO automation(tag,enabled,epoch) VALUES(?,0,0)", new Object[] {tag});
      db.execSQL("UPDATE automation SET enabled=?,epoch=epoch+1 WHERE tag=?", new Object[] {enabled ? 1 : 0, tag});
      if (clear) db.delete("automatic_tags", "tag=?", new String[] {tag});
      long epoch;
      try (Cursor c = db.rawQuery("SELECT epoch FROM automation WHERE tag=?", new String[] {tag})) {
        c.moveToFirst(); epoch = c.getLong(0);
      }
      db.setTransactionSuccessful();
      return epoch;
    } finally { db.endTransaction(); }
  }

  /** Effective automatic assignments, keyed by versioned source. Human decisions always win. */
  Map<String, AutomaticTags.Decision> automatic(String tag) {
    Map<String, AutomaticTags.Decision> result = new HashMap<>();
    try (Cursor c = getReadableDatabase().rawQuery(
        "SELECT p.source,a.positive,a.negative,a.threshold FROM automatic_tags a JOIN photos p ON p.id=a.photo "
        + "LEFT JOIN judgments j ON j.tag=a.tag AND j.photo=a.photo "
        + "WHERE a.tag=? AND a.pipeline=? AND a.judgment_revision=? AND j.photo IS NULL",
        new String[] {tag, automaticVersion(), Long.toString(judgmentRevision(tag))})) {
      while (c.moveToNext()) result.put(c.getString(0), new AutomaticTags.Decision(c.getDouble(1), c.getDouble(2), c.getDouble(3)));
    }
    return result;
  }

  /** Atomically publishes only if the human revision, enabled epoch and job are still current. */
  boolean applyAutomatic(String tag, long epoch, long revision, List<PhotoRepository.Photo> scope,
      java.util.Set<Integer> analyzed, Map<Integer, AutomaticTags.Decision> predictions,
      java.util.function.BooleanSupplier cancelled) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      if (cancelled.getAsBoolean() || revision != judgmentRevision(tag)) return false;
      try (Cursor c = db.rawQuery("SELECT enabled,epoch FROM automation WHERE tag=?", new String[] {tag})) {
        if (!c.moveToFirst() || c.getInt(0) != 1 || c.getLong(1) != epoch) return false;
      }
      Map<String, String> ids = photoIds();
      for (int i : analyzed) {
        if (i < 0 || i >= scope.size()) throw new IllegalArgumentException("Invalid photo index");
        String id = ids.get(source(scope.get(i)));
        if (id != null) db.delete("automatic_tags", "tag=? AND photo=?", new String[] {tag, id});
        AutomaticTags.Decision decision = predictions.get(i);
        if (decision == null) continue;
        if (!Double.isFinite(decision.positive) || !Double.isFinite(decision.negative)
            || !Double.isFinite(decision.threshold)) throw new IllegalArgumentException("Invalid decision");
        if (id == null) id = photoId(scope.get(i), true);
        // Any explicit human state, including a cancellation, blocks automatic assignment.
        try (Cursor c = db.rawQuery("SELECT value FROM judgments WHERE tag=? AND photo=?", new String[] {tag, id})) {
          if (c.moveToFirst()) continue;
        }
        ContentValues values = new ContentValues();
        values.put("tag", tag); values.put("photo", id); values.put("judgment_revision", revision);
        values.put("pipeline", automaticVersion()); values.put("epoch", epoch);
        values.put("positive", decision.positive); values.put("negative", decision.negative);
        values.put("threshold", decision.threshold);
        db.insertOrThrow("automatic_tags", null, values);
      }
      if (cancelled.getAsBoolean()) return false;
      db.setTransactionSuccessful();
      return true;
    } finally { db.endTransaction(); }
  }

  List<Tag> tags() {
    List<Tag> result = new ArrayList<>();
    try (Cursor c = getReadableDatabase().rawQuery("SELECT id,name FROM tags ORDER BY name", null)) {
      while (c.moveToNext()) result.add(new Tag(c.getString(0), c.getString(1)));
    }
    return result;
  }

  Tag create(String name) {
    String trimmed = name.trim();
    if (trimmed.isEmpty() || trimmed.length() > 80) throw new IllegalArgumentException("タグ名は1〜80文字で入力してください");
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      Tag result = null;
      try (Cursor c = db.rawQuery("SELECT id,name FROM tags WHERE name=?", new String[] {trimmed})) {
        if (c.moveToFirst()) result = new Tag(c.getString(0), c.getString(1));
      }
      if (result == null) {
        result = new Tag(UUID.randomUUID().toString(), trimmed);
        ContentValues values = new ContentValues();
        values.put("id", result.id); values.put("name", result.name);
        db.insertOrThrow("tags", null, values);
      }
      db.setTransactionSuccessful();
      return result;
    } finally { db.endTransaction(); }
  }

  /** Location and observed version, never a claim of byte identity. */
  static String source(PhotoRepository.Photo photo) {
    return photo.uri + "\n" + photo.size + ":" + photo.modified + ":" + photo.added + ":" + photo.created;
  }

  String photoId(PhotoRepository.Photo photo, boolean create) {
    SQLiteDatabase db = create ? getWritableDatabase() : getReadableDatabase();
    String source = source(photo);
    if (create) {
      ContentValues values = new ContentValues();
      values.put("id", UUID.randomUUID().toString()); values.put("source", source);
      db.insertWithOnConflict("photos", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }
    try (Cursor c = db.rawQuery("SELECT id FROM photos WHERE source=?", new String[] {source})) {
      if (c.moveToFirst()) return c.getString(0);
    }
    if (create) throw new IllegalStateException("写真の対応を保存できませんでした");
    return null;
  }

  TagJudgments load() {
    TagJudgments result = new TagJudgments();
    try (Cursor c = getReadableDatabase().rawQuery("SELECT operation,tag,photo,revision,value,undone FROM history ORDER BY seq", null)) {
      while (c.moveToNext()) {
        if (c.isNull(5)) result = result.record(c.getString(0), c.getString(1), c.getString(2), c.getLong(3) - 1, TagJudgments.Value.valueOf(c.getString(4)));
        else result = result.undo(c.getString(0), c.getString(5));
      }
    }
    return result;
  }

  /** Bulk lookup for gallery-scale browsing; never creates photo identities. */
  Map<String, String> photoIds() {
    Map<String, String> result = new HashMap<>();
    try (Cursor c = getReadableDatabase().rawQuery("SELECT source,id FROM photos", null)) {
      while (c.moveToNext()) result.put(c.getString(0), c.getString(1));
    }
    return result;
  }

  TagJudgments record(String operation, String tag, String photo, long expected, TagJudgments.Value value) {
    return change(operation, tag, photo, expected, value, null);
  }

  TagJudgments undo(String operation, String target) {
    return change(operation, null, null, 0, null, target);
  }

  private TagJudgments change(String operation, String tag, String photo, long expected,
      TagJudgments.Value value, String undo) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      TagJudgments before = load();
      TagJudgments next = undo == null ? before.record(operation, tag, photo, expected, value) : before.undo(operation, undo);
      if (next != before) {
        TagJudgments.Entry entry = next.history.get(next.history.size() - 1);
        requireReference(db, "tags", entry.tagId);
        requireReference(db, "photos", entry.photoId);
        ContentValues history = new ContentValues();
        history.put("operation", entry.operationId); history.put("tag", entry.tagId);
        history.put("photo", entry.photoId); history.put("revision", entry.revision);
        history.put("value", entry.value.name()); history.put("undone", entry.undoneOperationId);
        db.insertOrThrow("history", null, history);
        ContentValues current = new ContentValues();
        current.put("tag", entry.tagId); current.put("photo", entry.photoId);
        current.put("revision", entry.revision); current.put("value", entry.value.name());
        if (db.insertWithOnConflict("judgments", null, current, SQLiteDatabase.CONFLICT_REPLACE) < 0)
          throw new IllegalStateException("判断の現在値を保存できませんでした");
        db.execSQL("INSERT OR IGNORE INTO reevaluation(tag,revision) VALUES(?,0)", new Object[] {entry.tagId});
        db.execSQL("UPDATE reevaluation SET revision=revision+1 WHERE tag=?", new Object[] {entry.tagId});
      }
      db.setTransactionSuccessful();
      return next;
    } finally { db.endTransaction(); }
  }

  private static void requireReference(SQLiteDatabase db, String table, String id) {
    try (Cursor c = db.rawQuery("SELECT id FROM " + table + " WHERE id=?", new String[] {id})) {
      if (!c.moveToFirst()) throw new IllegalArgumentException("Unknown " + table + " reference");
    }
  }
}
