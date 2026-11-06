package jp.nano.rawgallery;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Local handoff history, not proof that the receiving app published a photo. */
final class ShareHistoryStore extends SQLiteOpenHelper {
  static final long SESSION_LIFETIME_MS = 24L * 60 * 60 * 1000;

  static final class Entry {
    final String packageName;
    final String label;
    final long handedOffAt;

    Entry(String packageName, String label, long handedOffAt) {
      this.packageName = packageName;
      this.label = label;
      this.handedOffAt = handedOffAt;
    }
  }

  ShareHistoryStore(Context context) { this(context, "share-history.db"); }

  ShareHistoryStore(Context context, String name) { super(context, name, null, 1); }

  @Override public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE history (photo TEXT NOT NULL, package TEXT NOT NULL, label TEXT NOT NULL, handed_off INTEGER NOT NULL, PRIMARY KEY(photo,package))");
    db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, created INTEGER NOT NULL)");
    db.execSQL("CREATE TABLE session_photos (session TEXT NOT NULL, photo TEXT NOT NULL, PRIMARY KEY(session,photo))");
  }

  @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    throw new IllegalStateException("Unsupported share history schema");
  }

  /** Freeze actual outgoing photo keys before starting the chooser; no history is created yet. */
  synchronized String begin(Collection<String> photoKeys, long now) {
    List<String> keys = validatedKeys(photoKeys);
    if (now < 0) throw new IllegalArgumentException("Invalid time");
    String id = UUID.randomUUID().toString();
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      db.delete("session_photos", "session IN (SELECT id FROM sessions WHERE created<? OR created>?)",
          new String[] {Long.toString(now - SESSION_LIFETIME_MS), Long.toString(now)});
      db.delete("sessions", "created<? OR created>?",
          new String[] {Long.toString(now - SESSION_LIFETIME_MS), Long.toString(now)});
      ContentValues session = new ContentValues();
      session.put("id", id); session.put("created", now);
      db.insertOrThrow("sessions", null, session);
      for (String key : keys) {
        ContentValues photo = new ContentValues();
        photo.put("session", id); photo.put("photo", key);
        db.insertOrThrow("session_photos", null, photo);
      }
      db.setTransactionSuccessful();
    } finally { db.endTransaction(); }
    return id;
  }

  /** Consume a known, unexpired session once, atomically recording every outgoing photo. */
  synchronized boolean complete(String sessionId, String packageName, String label, long now) {
    if (sessionId == null || sessionId.isEmpty()) return false;
    validateApp(packageName, label, now);
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      long created;
      try (Cursor cursor = db.query("sessions", new String[] {"created"}, "id=?",
          new String[] {sessionId}, null, null, null)) {
        if (!cursor.moveToFirst()) return false;
        created = cursor.getLong(0);
      }
      if (now < created || now - created > SESSION_LIFETIME_MS) {
        deleteSession(db, sessionId);
        db.setTransactionSuccessful();
        return false;
      }
      List<String> keys = new ArrayList<>();
      try (Cursor cursor = db.query("session_photos", new String[] {"photo"}, "session=?",
          new String[] {sessionId}, null, null, null)) {
        while (cursor.moveToNext()) keys.add(cursor.getString(0));
      }
      if (keys.isEmpty()) return false;
      for (String key : keys) {
        // Delayed callbacks must not replace a more recent handoff's label or timestamp.
        long previous = -1;
        try (Cursor cursor = db.query("history", new String[] {"handed_off"}, "photo=? AND package=?",
            new String[] {key, packageName}, null, null, null)) {
          if (cursor.moveToFirst()) previous = cursor.getLong(0);
        }
        if (previous > now) continue;
        ContentValues entry = new ContentValues();
        entry.put("photo", key); entry.put("package", packageName);
        entry.put("label", label); entry.put("handed_off", now);
        if (db.insertWithOnConflict("history", null, entry, SQLiteDatabase.CONFLICT_REPLACE) == -1)
          throw new android.database.SQLException("Could not save share history");
      }
      deleteSession(db, sessionId);
      db.setTransactionSuccessful();
      return true;
    } finally { db.endTransaction(); }
  }

  synchronized void cancel(String sessionId) {
    if (sessionId == null) return;
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      deleteSession(db, sessionId);
      db.setTransactionSuccessful();
    } finally { db.endTransaction(); }
  }

  synchronized List<Entry> history(String photoKey) {
    List<Entry> result = new ArrayList<>();
    try (Cursor cursor = getReadableDatabase().query("history",
        new String[] {"package", "label", "handed_off"}, "photo=?", new String[] {photoKey},
        null, null, "handed_off DESC, package ASC")) {
      while (cursor.moveToNext())
        result.add(new Entry(cursor.getString(0), cursor.getString(1), cursor.getLong(2)));
    }
    return List.copyOf(result);
  }

  /** One snapshot for rebinding visible photos; callers perform disk I/O off the UI thread. */
  synchronized java.util.Map<String, List<Entry>> snapshot() {
    java.util.Map<String, List<Entry>> mutable = new java.util.HashMap<>();
    try (Cursor cursor = getReadableDatabase().query("history",
        new String[] {"photo", "package", "label", "handed_off"}, null, null,
        null, null, "handed_off DESC, package ASC")) {
      while (cursor.moveToNext()) {
        mutable.computeIfAbsent(cursor.getString(0), key -> new ArrayList<>())
            .add(new Entry(cursor.getString(1), cursor.getString(2), cursor.getLong(3)));
      }
    }
    java.util.Map<String, List<Entry>> result = new java.util.HashMap<>();
    for (java.util.Map.Entry<String, List<Entry>> entry : mutable.entrySet())
      result.put(entry.getKey(), List.copyOf(entry.getValue()));
    return java.util.Map.copyOf(result);
  }

  private static void deleteSession(SQLiteDatabase db, String id) {
    db.delete("session_photos", "session=?", new String[] {id});
    db.delete("sessions", "id=?", new String[] {id});
  }

  static List<String> validatedKeys(Collection<String> keys) {
    if (keys == null || keys.isEmpty()) throw new IllegalArgumentException("No photos");
    LinkedHashSet<String> unique = new LinkedHashSet<>();
    for (String key : keys) {
      if (key == null || key.isEmpty() || key.length() > 4096)
        throw new IllegalArgumentException("Invalid photo key");
      unique.add(key);
    }
    return List.copyOf(unique);
  }

  static void validateApp(String packageName, String label, long now) {
    if (packageName == null || !packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")
        || packageName.length() > 255 || label == null || label.isBlank() || label.length() > 512 || now < 0)
      throw new IllegalArgumentException("Invalid share destination");
  }
}
