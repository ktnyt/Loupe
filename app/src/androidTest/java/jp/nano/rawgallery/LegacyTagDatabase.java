package jp.nano.rawgallery;

import android.database.sqlite.SQLiteDatabase;

/** Historical tag schemas for migration tests, independent of the current production helper. */
final class LegacyTagDatabase {
  private LegacyTagDatabase() {}

  static void create(SQLiteDatabase db, int version) {
    if (version < 1 || version > 3) throw new IllegalArgumentException("Legacy tag version");
    // v2 (1256ce4) adds the two automation tables to the v1 judgment schema.
    db.execSQL("CREATE TABLE tags (id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE)");
    db.execSQL("CREATE TABLE photos (id TEXT PRIMARY KEY, source TEXT NOT NULL UNIQUE)");
    db.execSQL(
        "CREATE TABLE history (seq INTEGER PRIMARY KEY AUTOINCREMENT, operation TEXT NOT NULL UNIQUE, tag TEXT NOT NULL, photo TEXT NOT NULL, revision INTEGER NOT NULL, value TEXT NOT NULL, undone TEXT)");
    db.execSQL(
        "CREATE TABLE judgments (tag TEXT NOT NULL, photo TEXT NOT NULL, revision INTEGER NOT NULL, value TEXT NOT NULL, PRIMARY KEY(tag,photo))");
    db.execSQL("CREATE TABLE reevaluation (tag TEXT PRIMARY KEY, revision INTEGER NOT NULL)");
    if (version >= 2) {
      db.execSQL(
          "CREATE TABLE automation (tag TEXT PRIMARY KEY, enabled INTEGER NOT NULL, epoch INTEGER NOT NULL)");
      db.execSQL(
          "CREATE TABLE automatic_tags (tag TEXT NOT NULL, photo TEXT NOT NULL, judgment_revision INTEGER NOT NULL, pipeline TEXT NOT NULL, epoch INTEGER NOT NULL, positive REAL NOT NULL, negative REAL NOT NULL, threshold REAL NOT NULL, PRIMARY KEY(tag,photo))");
    }
    // v3 (the main parent of 76b330f) adds learning tables and has no judgment-history index.
    if (version == 3) {
      db.execSQL(
          "CREATE TABLE confidence_preferences(tag TEXT PRIMARY KEY, threshold REAL NOT NULL DEFAULT 0.60 CHECK(threshold>=0 AND threshold<=1), enabled INTEGER NOT NULL DEFAULT 0, epoch INTEGER NOT NULL DEFAULT 0)");
      db.execSQL(
          "CREATE TABLE confidence_models(tag TEXT PRIMARY KEY, revision INTEGER NOT NULL, pipeline TEXT NOT NULL, parameters BLOB NOT NULL)");
      db.execSQL(
          "CREATE TABLE confidence_scores(tag TEXT NOT NULL, photo TEXT NOT NULL, score REAL NOT NULL CHECK(score>=0 AND score<=1), PRIMARY KEY(tag,photo))");
    }
    db.setVersion(version);
  }

  static void insertPhoto(SQLiteDatabase db, String id, PhotoRepository.Photo photo) {
    db.execSQL(
        "INSERT INTO photos(id,source) VALUES(?,?)", new Object[] {id, TagStore.source(photo)});
  }

  static void insertAssignment(
      SQLiteDatabase db,
      String tag,
      String name,
      String photoId,
      PhotoRepository.Photo photo,
      String operation) {
    db.execSQL("INSERT INTO tags(id,name) VALUES(?,?)", new Object[] {tag, name});
    insertPhoto(db, photoId, photo);
    db.execSQL(
        "INSERT INTO history(operation,tag,photo,revision,value) VALUES(?,?,?,1,'ASSIGNED')",
        new Object[] {operation, tag, photoId});
    db.execSQL(
        "INSERT INTO judgments(tag,photo,revision,value) VALUES(?,?,1,'ASSIGNED')",
        new Object[] {tag, photoId});
    db.execSQL("INSERT INTO reevaluation(tag,revision) VALUES(?,1)", new Object[] {tag});
  }
}
