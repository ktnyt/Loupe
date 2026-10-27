package jp.nano.rawgallery;

import android.content.*;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.lang.reflect.Method;
import java.util.UUID;
import org.json.JSONObject;

/** Uses isolated databases and synthetic records only; never opens the user's archive database. */
final class IndexSignatureChecks {
  private static Context isolated(Context context, File root) {
    return new ContextWrapper(context) {
      @Override public Context getApplicationContext() { return this; }
      @Override public File getDatabasePath(String name) { return new File(root, name); }
      @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
        return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
      }
      @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
        return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(), factory, handler);
      }
    };
  }
  private static String signature(Context context) throws Exception {
    Method method = GalleryIndex.class.getDeclaredMethod("signature", Context.class);
    method.setAccessible(true);
    return (String) method.invoke(null, context);
  }
  private static void check(boolean valid, String label) { if (!valid) throw new AssertionError(label); }
  private static void runVersion(Context context, int oldVersion) throws Exception {
    File root = new File(context.getCacheDir(), "index-signature-" + UUID.randomUUID());
    check(root.mkdir(), "isolated directory");
    Context isolated = isolated(context, root);
    File file = isolated.getDatabasePath("archive.db");
    try {
      if (oldVersion > 0) try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
        db.execSQL("CREATE TABLE photos (key TEXT PRIMARY KEY, state TEXT NOT NULL, payload TEXT NOT NULL)");
        db.execSQL("INSERT INTO photos VALUES ('seed','verified','{\"key\":\"seed\",\"name\":\"synthetic\"}')");
        if (oldVersion >= 2) {
          db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, model TEXT NOT NULL, taken INTEGER NOT NULL)");
          db.execSQL("INSERT INTO metadata VALUES ('existing','synthetic',42)");
        }
        db.setVersion(oldVersion);
      }
      try (ArchiveStore store = new ArchiveStore(isolated)) {
        SQLiteDatabase db = store.getWritableDatabase();
        check(db.getVersion() == 3, "schema upgraded");
        check(oldVersion == 0 || "synthetic".equals(store.get("seed").getString("name")), "photo payload preserved");
        check(oldVersion < 2 || store.metadataSnapshot().get("existing").taken == 42, "metadata preserved");
        long revision = store.indexRevision();
        String before = signature(isolated);
        ContentValues metadata = new ContentValues();
        metadata.put("key", "new-derived"); metadata.put("model", "synthetic"); metadata.put("taken", 123);
        check(db.insertOrThrow("metadata", null, metadata) >= 0, "metadata saved");
        check(store.indexRevision() == revision, "metadata does not invalidate source");
        check(before.equals(signature(isolated)), "metadata signature stable");
        JSONObject photo = new JSONObject().put("key", "new-photo").put("name", "synthetic");
        store.put(photo, "pending");
        check(store.indexRevision() > revision, "insert invalidates");
        check(!before.equals(signature(isolated)), "photo signature changes");
        revision = store.indexRevision();
        photo.put("name", "replacement"); store.put(photo, "verified");
        check(store.indexRevision() > revision, "replace invalidates");
        revision = store.indexRevision();
        db.execSQL("UPDATE photos SET state='updated' WHERE key='new-photo'");
        check(store.indexRevision() > revision, "update invalidates");
        revision = store.indexRevision();
        before = signature(isolated);
        db.beginTransaction();
        try { store.put(new JSONObject().put("key", "rolled-back"), "pending"); }
        finally { db.endTransaction(); }
        check(store.get("rolled-back") == null, "photo rolled back");
        check(store.indexRevision() == revision && before.equals(signature(isolated)), "revision rolled back");
        db.delete("photos", "key=?", new String[] {"new-photo"});
        check(store.indexRevision() > revision, "delete invalidates");
        before = signature(isolated);
        store.setWriteAheadLoggingEnabled(true);
        metadata.put("key", "wal-derived"); db.insertOrThrow("metadata", null, metadata);
        try (Cursor checkpoint = db.rawQuery("PRAGMA wal_checkpoint(FULL)", null)) {
          check(checkpoint.moveToFirst() && checkpoint.getInt(0) == 0, "checkpoint completed");
        }
        check(before.equals(signature(isolated)), "checkpoint signature stable");
      }
    } finally {
      File[] files = root.listFiles();
      if (files != null) for (File child : files) check(child.delete(), "isolated DB cleanup");
      check(root.delete(), "isolated directory cleanup");
    }
  }
  static String run(Context context) throws Exception {
    for (int version : new int[] {0, 1, 2}) runVersion(context, version);
    return "PASS archive schema 1/2 migration and fresh create preserve records; metadata/checkpoint signatures stable; photo insert/replace/update/delete invalidate; rollback atomic; isolated DBs removed.\n";
  }
}
