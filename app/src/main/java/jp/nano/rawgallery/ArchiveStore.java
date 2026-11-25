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
  static final int JPEG_BURST_SCAN_LIMIT = 1024 * 1024;
  private static final String[] METADATA_COLUMNS = {"key", "model", "taken", "burst_mode", "burst_frame"};
  private static final int E2_INDEX_QUERY_KEYS = 512;
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

    RawMetadata inspected = inspectMetadata(p);
    if (insertMetadata(getWritableDatabase(), key, inspected) >= 0) return inspected;
    // Another caller may have completed a burst probe since this caller read the source.
    cached = readMetadata(key);
    if (cached == null) throw new IOException("写真のメタデータを保存できません");
    return cached;
  }

  /** Reads the source without writing or opening a database transaction. */
  RawMetadata inspectMetadata(PhotoRepository.Photo p) throws Exception {
    if (p.isVideo()) return new RawMetadata("動画", p.taken);
    if (p.isJpeg()) {
      try (InputStream in = context.getContentResolver().openInputStream(p.uri)) {
        if (in == null) throw new IOException("JPEGを開けません");
        android.media.ExifInterface exif = new android.media.ExifInterface(in);
        String model = exif.getAttribute(android.media.ExifInterface.TAG_MODEL);
        long taken = RawPreview.captureTime(
            exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL),
            exif.getAttribute(android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL));
        return new RawMetadata(model == null ? "JPEG" : model, taken);
      }
    } else {
      try (PhotoRepository.DescriptorSource source =
          new PhotoRepository.DescriptorSource(context.getContentResolver(), p.uri, p.size)) {
        RawPreview.Result result = RawPreview.inspect(source);
        return new RawMetadata(result.model, result.taken);
      }
    }
  }

  private static long insertMetadata(SQLiteDatabase db, String key, RawMetadata metadata) {
    ContentValues values = new ContentValues();
    values.put("key", key);
    values.put("model", metadata.model);
    values.put("taken", metadata.taken);
    return db.insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_IGNORE);
  }

  RawMetadata readMetadata(String key) {
    return readMetadata(getReadableDatabase(), key);
  }

  private static RawMetadata readMetadata(SQLiteDatabase db, String key) {
    try (Cursor c = db.query("metadata", METADATA_COLUMNS,
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

  /** Reads only the source. Callers leave the burst cache NULL when inspection fails. */
  RawPreview.BurstFrame inspectBurstFrame(PhotoRepository.Photo p) throws IOException {
    if (p.isJpeg()) {
      try (InputStream in = context.getContentResolver().openInputStream(p.uri)) {
        if (in == null) throw new IOException("JPEGを開けません");
        return readJpegBurstFrame(in);
      }
    }
    try (PhotoRepository.DescriptorSource source =
        new PhotoRepository.DescriptorSource(context.getContentResolver(), p.uri, p.size)) {
      return RawPreview.readBurstFrame(source);
    }
  }

  record IndexMetadata(String key, RawMetadata inspected, boolean insert, RawPreview.BurstFrame frame) {}

  /**
   * Writes a bounded set of already inspected records. The returned metadata reflects the row
   * that won each insert/probe race. Cancellation rolls back the batch and returns null.
   */
  Map<String, RawMetadata> writeIndexMetadata(List<IndexMetadata> batch,
      java.util.function.BooleanSupplier cancelled) throws IOException {
    if (cancelled.getAsBoolean()) return null;
    if (batch.isEmpty()) return Map.of();
    Map<String, RawMetadata> committed = new HashMap<>();
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransactionNonExclusive();
    try {
      for (IndexMetadata change : batch) {
        if (cancelled.getAsBoolean()) return null;
        RawMetadata metadata = change.inspected();
        if (change.insert() && insertMetadata(db, change.key(), metadata) < 0) {
          metadata = readMetadata(db, change.key());
          if (metadata == null) throw new IOException("写真のメタデータを保存できません");
        }
        if (change.frame() != null && metadata.burstFrame == null
            && metadata.model.equals(change.inspected().model) && metadata.taken == change.inspected().taken) {
          ContentValues values = new ContentValues();
          values.put("burst_mode", change.frame().mode());
          values.put("burst_frame", change.frame().number());
          int rows = db.update("metadata", values,
              "key=? AND model=? AND taken=? AND burst_mode IS NULL AND burst_frame IS NULL",
              new String[] {change.key(), metadata.model, Long.toString(metadata.taken)});
          if (rows == 1) {
            metadata = new RawMetadata(metadata.model, metadata.taken, change.frame());
          } else {
            RawMetadata current = readMetadata(db, change.key());
            if (current != null) metadata = current;
          }
        }
        committed.put(change.key(), metadata);
      }
      if (cancelled.getAsBoolean()) return null;
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    return committed;
  }

  static RawPreview.BurstFrame readJpegBurstFrame(InputStream input) throws IOException {
    return new JpegBurstProbe(input).read();
  }

  /** Reads only bounded JPEG header segments, with no thumbnail or pixel decoding. */
  private static final class JpegBurstProbe {
    private static final byte[] EXIF = {'E', 'x', 'i', 'f', 0, 0};
    private final InputStream input;
    private int remaining = JPEG_BURST_SCAN_LIMIT;

    JpegBurstProbe(InputStream input) { this.input = input; }

    RawPreview.BurstFrame read() throws IOException {
      if (requiredByte() != 0xff || requiredByte() != 0xd8 || requiredByte() != 0xff)
        return RawPreview.BurstFrame.NONE;
      byte[] header = new byte[EXIF.length];
      do {
        int marker = requiredByte();
        while (marker == 0xff) marker = requiredByte();
        if (marker == 0xda || marker == 0xd9) break; // SOS or EOI; do not read image data.
        if (marker == 0x01) continue; // TEM is a standalone marker.
        if (marker < 0xc0 || (marker >= 0xd0 && marker <= 0xd8)) break;
        int length = (requiredByte() << 8) | requiredByte();
        if (length < 2) break;
        int payload = length - 2;
        if (marker == 0xe1 && payload >= header.length) {
          readFully(header);
          payload -= header.length;
          if (Arrays.equals(header, EXIF)) {
            requireBudget(payload);
            byte[] tiff = new byte[payload];
            readFully(tiff);
            RawPreview.BurstFrame frame = RawPreview.readBurstFrame(new RawPreview.Source() {
              public long length() { return tiff.length; }
              public void read(long offset, byte[] data) {
                System.arraycopy(tiff, (int) offset, data, 0, data.length);
              }
            });
            if (frame.number() > 0) return frame;
            continue;
          }
        }
        skipFully(payload);
      } while (requiredByte() == 0xff);
      return RawPreview.BurstFrame.NONE;
    }

    private void requireBudget(int count) throws IOException {
      if (count > remaining) throw new IOException("JPEGメタデータの読み取り上限に達しました");
    }

    private int requiredByte() throws IOException {
      requireBudget(1);
      int value = input.read();
      if (value < 0) throw truncated();
      remaining--;
      return value;
    }

    private EOFException truncated() {
      return new EOFException("JPEGメタデータの途中で読み取りが終わりました");
    }

    private void readFully(byte[] bytes) throws IOException {
      requireBudget(bytes.length);
      int offset = 0;
      while (offset < bytes.length) {
        int count = input.read(bytes, offset, bytes.length - offset);
        if (count < 0) throw truncated();
        if (count == 0) bytes[offset++] = (byte) requiredByte();
        else {
          offset += count;
          remaining -= count;
        }
      }
    }

    private void skipFully(int count) throws IOException {
      requireBudget(count);
      byte[] discard = null;
      while (count > 0) {
        int skipped = (int) input.skip(count);
        if (skipped == 0) {
          if (discard == null) discard = new byte[Math.min(count, 4096)];
          skipped = input.read(discard, 0, Math.min(count, discard.length));
          if (skipped < 0) throw truncated();
          if (skipped == 0) {
            requiredByte();
            count--;
            continue;
          }
        }
        count -= skipped;
        remaining -= skipped;
      }
    }
  }

  Map<String, RawMetadata> metadataSnapshot() {
    Map<String, RawMetadata> result = new HashMap<>();
    try (Cursor c = getReadableDatabase().query("metadata", METADATA_COLUMNS,
        null, null, null, null, null)) {
      while (c.moveToNext()) result.put(c.getString(0), rawMetadata(c));
    }
    return result;
  }

  /** Reads metadata for the requested E2 keys; a missing or malformed row has no fallback. */
  Map<String, RawMetadata> releasedIndexMetadata(Set<String> keys,
      java.util.function.BooleanSupplier cancelled) {
    if (cancelled.getAsBoolean()) return null;
    Map<String, RawMetadata> result = new HashMap<>();
    List<String> requested = new ArrayList<>(keys);
    SQLiteDatabase db = getReadableDatabase();
    for (int first = 0; first < requested.size(); first += E2_INDEX_QUERY_KEYS) {
      if (cancelled.getAsBoolean()) return null;
      int size = Math.min(E2_INDEX_QUERY_KEYS, requested.size() - first);
      String[] args = new String[size + 2];
      args[0] = E2Cleanup.RELEASING;
      args[1] = E2Cleanup.RELEASED;
      for (int i = 0; i < size; i++) args[i + 2] = requested.get(first + i);
      String where = "state IN (?,?) AND key IN (" + String.join(",", Collections.nCopies(size, "?")) + ")";
      try (Cursor c = db.query("photos", new String[] {"key", "payload"}, where, args, null, null, null)) {
        while (c.moveToNext()) {
          if (cancelled.getAsBoolean()) return null;
          try {
            result.put(c.getString(0), RawMetadata.fromReceipt(new JSONObject(c.getString(1))));
          } catch (JSONException malformed) {
            DiagnosticLog.exception("index.e2_metadata_row", "skipped", malformed);
          }
        }
      } catch (android.database.sqlite.SQLiteException unavailable) {
        DiagnosticLog.exception("index.e2_metadata", "failed", unavailable);
      }
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

    static RawMetadata fromReceipt(JSONObject receipt) {
      return receipt == null ? null : new RawMetadata(receipt.optString("model", ""), receipt.optLong("taken", 0));
    }

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
