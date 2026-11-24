package jp.nano.rawgallery;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.net.Uri;
import java.io.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import org.json.*;

/** Durable verification metadata and previews, separate from expendable caches. */
final class ArchiveStore extends SQLiteOpenHelper {
  // Only a single E2 photos write and the last legacy proof scan use this lock.
  static final ReentrantLock E2_PHOTO_UPSERT = new ReentrantLock(true);
  private static final String[] METADATA_COLUMNS = {"key", "model", "taken", "burst_mode", "burst_frame"};
  private final Context context;
  private final boolean e2Photos;

  ArchiveStore(Context c) {
    this(c, "archive.db");
  }

  ArchiveStore(Context c, String databaseName) {
    super(c, databaseName, null, 4);
    context = c.getApplicationContext();
    e2Photos = E2Settings.DATABASE.equals(databaseName);
  }

  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE metadata (key TEXT PRIMARY KEY, model TEXT NOT NULL, taken INTEGER NOT"
            + " NULL, burst_mode INTEGER, burst_frame INTEGER)");
    db.execSQL(
        "CREATE TABLE photos (key TEXT PRIMARY KEY, state TEXT NOT NULL, payload TEXT NOT NULL)");
    createIndexRevision(db);
  }

  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (oldVersion < 2)
      db.execSQL(
          "CREATE TABLE metadata (key TEXT PRIMARY KEY, model TEXT NOT NULL, taken INTEGER NOT"
              + " NULL)");
    if (oldVersion < 3) createIndexRevision(db);
    if (oldVersion < 4) {
      db.execSQL("ALTER TABLE metadata ADD COLUMN burst_mode INTEGER");
      db.execSQL("ALTER TABLE metadata ADD COLUMN burst_frame INTEGER");
    }
  }

  /** Only source photo records invalidate browsing; derived metadata and WAL checkpoints do not. */
  private static void createIndexRevision(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE index_revision (id INTEGER PRIMARY KEY CHECK(id=1), value INTEGER NOT NULL)");
    db.execSQL("INSERT INTO index_revision VALUES (1,0)");
    for (String operation : new String[] {"INSERT", "UPDATE", "DELETE"})
      db.execSQL("CREATE TRIGGER index_revision_" + operation + " AFTER " + operation
          + " ON photos BEGIN UPDATE index_revision SET value=value+1 WHERE id=1; END");
  }

  java.util.Set<String> verifiedKeys() {
    java.util.Set<String> keys = new java.util.HashSet<>();
    try (Cursor c = getReadableDatabase().rawQuery("SELECT key FROM photos WHERE state='verified'", null)) {
      while (c.moveToNext()) keys.add(c.getString(0));
    }
    return keys;
  }

  long indexRevision() {
    try (Cursor c = getReadableDatabase().rawQuery("SELECT value FROM index_revision WHERE id=1", null)) {
      if (!c.moveToFirst()) throw new IllegalStateException("Archive index revision missing");
      return c.getLong(0);
    }
  }

  RawMetadata metadata(PhotoRepository.Photo p) throws Exception {
    if (p.isVideo()) return new RawMetadata("動画", p.taken);
    String key = key(p);
    RawMetadata cached = readMetadata(key);
    if (cached != null) return cached;

    RawMetadata inspected;
    if (p.isJpeg()) {
      try (InputStream in = context.getContentResolver().openInputStream(p.uri)) {
        if (in == null) throw new IOException("JPEGを開けません");
        android.media.ExifInterface exif = new android.media.ExifInterface(in);
        String model = exif.getAttribute(android.media.ExifInterface.TAG_MODEL);
        long taken = RawPreview.captureTime(
            exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL),
            exif.getAttribute(android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL));
        inspected = new RawMetadata(model == null ? "JPEG" : model, taken);
      }
    } else {
      try (PhotoRepository.DescriptorSource source =
          new PhotoRepository.DescriptorSource(context.getContentResolver(), p.uri, p.size)) {
        RawPreview.Result result = RawPreview.inspect(source);
        inspected = new RawMetadata(result.model, result.taken);
      }
    }
    ContentValues values = new ContentValues();
    values.put("key", key);
    values.put("model", inspected.model);
    values.put("taken", inspected.taken);
    if (getWritableDatabase().insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_IGNORE) >= 0)
      return inspected;
    // Another caller may have completed a burst probe since this caller read the source.
    cached = readMetadata(key);
    if (cached == null) throw new IOException("写真のメタデータを保存できません");
    return cached;
  }

  private RawMetadata readMetadata(String key) {
    try (Cursor c = getReadableDatabase().query("metadata", METADATA_COLUMNS,
        "key=?", new String[] {key}, null, null, null)) {
      return c.moveToFirst() ? rawMetadata(c) : null;
    }
  }

  static boolean canProbeBurstFrame(PhotoRepository.Photo p) {
    if (p.isArchived() || (!p.isJpeg() && !p.name.toLowerCase(Locale.ROOT).endsWith(".orf")))
      return false;
    // SAF cloud providers may download on open; only read sources known to be local.
    if (ContentResolver.SCHEME_FILE.equals(p.uri.getScheme())) return true;
    if (!ContentResolver.SCHEME_CONTENT.equals(p.uri.getScheme())) return false;
    String authority = p.uri.getAuthority();
    return "media".equals(authority) || "com.android.externalstorage.documents".equals(authority)
        || "com.android.providers.media.documents".equals(authority)
        || "com.android.providers.downloads.documents".equals(authority);
  }

  /** Gallery-only: I/O failures leave the existing metadata and its NULL probe status intact. */
  RawMetadata probeBurstFrame(PhotoRepository.Photo p, String key, RawMetadata metadata) throws IOException {
    if (metadata.burstFrame != null || !canProbeBurstFrame(p)) return metadata;
    RawPreview.BurstFrame frame;
    if (p.isJpeg()) {
      try (InputStream in = context.getContentResolver().openInputStream(p.uri)) {
        if (in == null) throw new IOException("JPEGを開けません");
        android.media.ExifInterface exif = new android.media.ExifInterface(in);
        frame = RawPreview.readBurstFrame(exif.getAttributeBytes(android.media.ExifInterface.TAG_MAKER_NOTE));
      }
    } else {
      try (PhotoRepository.DescriptorSource source =
          new PhotoRepository.DescriptorSource(context.getContentResolver(), p.uri, p.size)) {
        frame = RawPreview.readBurstFrame(source);
      }
    }
    ContentValues values = new ContentValues();
    values.put("burst_mode", frame.mode());
    values.put("burst_frame", frame.number());
    if (getWritableDatabase().update("metadata", values,
        "key=? AND burst_mode IS NULL AND burst_frame IS NULL", new String[] {key}) == 1)
      return new RawMetadata(metadata.model, metadata.taken, frame);
    RawMetadata cached = readMetadata(key);
    return cached == null ? metadata : cached;
  }

  Map<String, RawMetadata> metadataSnapshot() {
    Map<String, RawMetadata> result = new HashMap<>();
    try (Cursor c = getReadableDatabase().query("metadata", METADATA_COLUMNS,
        null, null, null, null, null)) {
      while (c.moveToNext()) result.put(c.getString(0), rawMetadata(c));
    }
    return result;
  }

  private static RawMetadata rawMetadata(Cursor c) {
    RawPreview.BurstFrame frame = c.isNull(3) || c.isNull(4) ? null
        : new RawPreview.BurstFrame(c.getInt(3), c.getInt(4));
    return new RawMetadata(c.getString(1), c.getLong(2), frame);
  }

  static final class RawMetadata {
    final String model;
    final long taken;
    final RawPreview.BurstFrame burstFrame;

    RawMetadata(String model, long taken) {
      this(model, taken, null);
    }

    RawMetadata(String model, long taken, RawPreview.BurstFrame burstFrame) {
      this.model = model;
      this.taken = taken;
      this.burstFrame = burstFrame;
    }
  }

  static String key(PhotoRepository.Photo p) throws IOException {
    if (p.movedIdentity != null) return p.movedIdentity.archiveKey();
    return PhotoRepository.digest(legacyIdentity(p));
  }

  static String legacyIdentity(PhotoRepository.Photo p) {
    return p.folder + p.name + ":" + p.size + ":" + p.modified;
  }

  static JSONObject base(PhotoRepository.Photo p) throws Exception {
    return new JSONObject()
        .put("key", key(p))
        .put("uri", p.uri.toString())
        .put("name", p.name)
        .put("folder", p.folder)
        .put("size", p.size)
        .put("modified", p.modified)
        .put("taken", p.taken)
        .put("added", p.added)
        .put("created", p.created);
  }

  JSONObject get(String key) throws Exception {
    try (Cursor c =
        getReadableDatabase()
            .query(
                "photos",
                new String[] {"state", "payload"},
                "key=?",
                new String[] {key},
                null,
                null,
                null)) {
      return c.moveToFirst() ? new JSONObject(c.getString(1)).put("state", c.getString(0)) : null;
    }
  }

  void put(JSONObject value, String state) throws Exception {
    ContentValues v = new ContentValues();
    v.put("key", value.getString("key"));
    v.put("state", state);
    v.put("payload", value.toString());
    SQLiteDatabase db = getWritableDatabase();
    long row;
    if (e2Photos) E2_PHOTO_UPSERT.lock();
    try {
      row = db.insertWithOnConflict("photos", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    } finally {
      if (e2Photos) E2_PHOTO_UPSERT.unlock();
    }
    if (row < 0) throw new IOException("同期記録を永続保存できません");
  }

  void pending(PhotoRepository.Photo p) throws Exception {
    put(base(p), "pending");
  }

  void verified(PhotoRepository.Photo p, CloudVerifier.Verified verified) throws Exception {
    if (verified == null) throw new IOException("復元が未検証です");
    String key = key(p);
    PhotoRepository.Preview preview = new PhotoRepository(context).sourcePreview(p);
    File dir = new File(context.getFilesDir(), "archive");
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("プレビューを保存できません");
    File compact = new File(dir, key + ".webp");
    preview = CompactPreview.save(preview, compact);
    JSONObject record =
        base(p)
            .put("id", verified.id)
            .put("owner", verified.owner)
            .put("sha", verified.sha)
            .put("md5", verified.md5)
            .put("verifiedAt", System.currentTimeMillis())
            .put("width", preview.width)
            .put("height", preview.height)
            .put("orientation", preview.orientation)
            .put("model", preview.model)
            .put("previewFormat", "webp");
    put(record, "verified");
    new File(dir, key + ".jpg").delete();
  }

  interface ReceiptVisitor {
    boolean visit(JSONObject row) throws Exception;
  }

  /** Returns false when the visitor found what it needed and stopped the scan. */
  boolean visitReceipts(ReceiptVisitor visitor) throws Exception {
    return visitReceipts(null, null, visitor);
  }

  private boolean visitReceipts(String where, String[] args, ReceiptVisitor visitor) throws Exception {
    try (Cursor c = getReadableDatabase()
        .query("photos", new String[] {"state", "payload"}, where, args, null, null, null)) {
      while (c.moveToNext())
        if (!visitor.visit(new JSONObject(c.getString(1)).put("state", c.getString(0)))) return false;
    }
    return true;
  }

  List<JSONObject> all() throws Exception {
    List<JSONObject> values = new ArrayList<>();
    visitReceipts(values::add);
    return values;
  }

  List<JSONObject> inStates(String state, String... other) throws Exception {
    List<JSONObject> values = new ArrayList<>();
    String[] states = new String[other.length + 1];
    states[0] = state;
    System.arraycopy(other, 0, states, 1, other.length);
    String place = String.join(",", Collections.nCopies(states.length, "?"));
    visitReceipts("state IN (" + place + ")", states, values::add);
    return values;
  }

  PhotoRepository.Photo cloudPhoto(JSONObject j) throws Exception {
    return new PhotoRepository.Photo(
        Uri.parse("archive://" + j.getString("key")),
        j.getString("name"),
        j.getString("folder"),
        j.getLong("size"),
        j.getLong("modified"),
        j.getLong("taken"),
        j.optLong("added", 0),
        j.optLong("created", 0),
        new PhotoMoveStore.Identity(j.getString("key"), j.optString("uri")));
  }

  PhotoRepository.Preview preview(String key) throws Exception {
    JSONObject j = get(key);
    if (j == null || !j.has("sha")) throw new IOException("保存記録がありません");
    File dir = new File(context.getFilesDir(), "archive");
    File f = new File(dir, key + ("webp".equals(j.optString("previewFormat")) ? ".webp" : ".jpg"));
    if (!f.isFile() || f.length() == 0) throw new IOException("保存したプレビューがありません");
    PhotoRepository.Preview result =
        new PhotoRepository.Preview(
            f,
            j.getString("model"),
            j.getInt("width"),
            j.getInt("height"),
            j.getInt("orientation"));
    if (!"webp".equals(j.optString("previewFormat"))) {
      PhotoRepository.Preview converted = CompactPreview.save(result, new File(dir, key + ".webp"));
      j.put("previewFormat", "webp")
          .put("width", converted.width)
          .put("height", converted.height)
          .put("orientation", 1);
      put(j, j.getString("state"));
      f.delete();
      result = converted;
    }
    return result;
  }
}
