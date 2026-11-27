package jp.nano.rawgallery.camera;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.*;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Verified original contents, per-source receipts, and the recoverable MediaStore publication
 * journal. Legacy camera-import-history.db is intentionally preserved as unverified historical
 * evidence.
 */
public final class CameraImportLedger extends SQLiteOpenHelper {
  private static volatile CameraImportLedger instance;

  public static CameraImportLedger get(Context context) {
    if (instance == null)
      synchronized (CameraImportLedger.class) {
        if (instance == null)
          instance =
              new CameraImportLedger(context.getApplicationContext(), "camera-content-history.db");
      }
    return instance;
  }

  public CameraImportLedger(Context context, String name) {
    super(context, name, null, 4);
    setWriteAheadLoggingEnabled(true);
  }

  @Override
  public void onConfigure(SQLiteDatabase db) {
    db.setForeignKeyConstraintsEnabled(true);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE contents (source_sha TEXT PRIMARY KEY,source_bytes INTEGER NOT NULL,stored_sha TEXT NOT NULL,stored_bytes INTEGER NOT NULL,dest_uri TEXT NOT NULL,kind TEXT NOT NULL,capture_group TEXT NOT NULL,created_at INTEGER NOT NULL)");
    db.execSQL(
        "CREATE TABLE receipts (source_key TEXT PRIMARY KEY,source_id TEXT NOT NULL,relative_path TEXT NOT NULL,source_bytes INTEGER NOT NULL,source_stamp TEXT NOT NULL,capture_seconds INTEGER NOT NULL,source_sha TEXT NOT NULL REFERENCES contents(source_sha),created_at INTEGER NOT NULL)");
    db.execSQL("CREATE INDEX receipts_source ON receipts(source_id)");
    db.execSQL("CREATE INDEX contents_capture ON contents(capture_group)");
    db.execSQL(
        "CREATE TABLE pending (token TEXT PRIMARY KEY,source_id TEXT NOT NULL,relative_path TEXT NOT NULL,source_bytes INTEGER NOT NULL,source_stamp TEXT NOT NULL,capture_seconds INTEGER NOT NULL,source_sha TEXT NOT NULL UNIQUE,stored_sha TEXT NOT NULL,stored_bytes INTEGER NOT NULL,kind TEXT NOT NULL,capture_group TEXT NOT NULL,pending_name TEXT NOT NULL,final_name TEXT NOT NULL,relative_folder TEXT NOT NULL,target_uri TEXT,ready INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,volume TEXT NOT NULL,replacement_witness TEXT)");
    createSdRevisions(db);
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (oldVersion < 2) {
      // Version 1 always inserted via the primary external collection.
      db.execSQL("ALTER TABLE pending ADD COLUMN volume TEXT NOT NULL DEFAULT 'external_primary'");
    }
    if (oldVersion < 3) db.execSQL("ALTER TABLE pending ADD COLUMN replacement_witness TEXT");
    if (oldVersion < 4) createSdRevisions(db);
  }

  private static void createSdRevisions(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE sd_saved_revisions (source_sha TEXT PRIMARY KEY NOT NULL REFERENCES contents(source_sha)"
            + " ON DELETE CASCADE, dest_uri TEXT NOT NULL, stored_sha TEXT NOT NULL, stored_bytes INTEGER NOT NULL,"
            + " epoch TEXT NOT NULL, generation_added INTEGER NOT NULL, generation_modified INTEGER NOT NULL)");
    db.execSQL(
        "CREATE TRIGGER invalidate_sd_saved_revision AFTER UPDATE OF dest_uri,stored_sha,stored_bytes ON contents"
            + " WHEN OLD.dest_uri<>NEW.dest_uri OR OLD.stored_sha<>NEW.stored_sha OR OLD.stored_bytes<>NEW.stored_bytes"
            + " BEGIN DELETE FROM sd_saved_revisions WHERE source_sha=OLD.source_sha; END");
  }

  synchronized SdSavedPhotoRevision sdRevision(String sourceSha) {
    try (Cursor row =
        getReadableDatabase()
            .rawQuery(
                "SELECT dest_uri,stored_sha,stored_bytes,epoch,"
                    + "generation_added,generation_modified FROM sd_saved_revisions WHERE source_sha=?",
                new String[] {sourceSha})) {
      return row.moveToFirst()
          ? new SdSavedPhotoRevision(
              row.getString(0),
              row.getString(1),
              row.getLong(2),
              row.getString(3),
              row.getLong(4),
              row.getLong(5))
          : null;
    }
  }

  /** The caller has just hashed this exact published revision with the SD grant still active. */
  synchronized void rememberSdRevision(Content expected, SdSavedPhotoRevision proof) {
    if (!proof.matchesContent(expected))
      throw new IllegalArgumentException("Saved revision differs from content");
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      if (expected.sameRow(find(expected.sourceSha, expected.sourceBytes))) {
        ContentValues values = new ContentValues();
        values.put("source_sha", expected.sourceSha);
        values.put("dest_uri", proof.uri());
        values.put("stored_sha", proof.storedSha());
        values.put("stored_bytes", proof.storedBytes());
        values.put("epoch", proof.epoch());
        values.put("generation_added", proof.generationAdded());
        values.put("generation_modified", proof.generationModified());
        if (db.insertWithOnConflict(
                "sd_saved_revisions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            < 0) throw new IllegalStateException("Saved revision could not be recorded");
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  synchronized void forgetSdRevision(Content content) {
    getWritableDatabase()
        .delete(
            "sd_saved_revisions",
            "source_sha=? AND dest_uri=? AND stored_sha=? AND stored_bytes=?",
            new String[] {
              content.sourceSha, content.uri, content.storedSha, Long.toString(content.storedBytes)
            });
  }

  public static final class Content {
    public final String sourceSha, storedSha, uri, kind, captureGroup;
    public final long sourceBytes, storedBytes;
    final long createdAt;

    Content(Cursor c) {
      sourceSha = c.getString(0);
      sourceBytes = c.getLong(1);
      storedSha = c.getString(2);
      storedBytes = c.getLong(3);
      uri = c.getString(4);
      kind = c.getString(5);
      captureGroup = c.getString(6);
      createdAt = c.getLong(7);
    }

    Content(String witness) {
      try {
        JSONObject value = new JSONObject(witness);
        sourceSha = value.getString("source_sha");
        sourceBytes = value.getLong("source_bytes");
        storedSha = value.getString("stored_sha");
        storedBytes = value.getLong("stored_bytes");
        uri = value.getString("dest_uri");
        kind = value.getString("kind");
        captureGroup = value.getString("capture_group");
        createdAt = value.getLong("created_at");
      } catch (JSONException failure) {
        throw new IllegalStateException("Invalid replacement witness");
      }
    }

    String encodeWitness() {
      try {
        return new JSONObject()
            .put("source_sha", sourceSha)
            .put("source_bytes", sourceBytes)
            .put("stored_sha", storedSha)
            .put("stored_bytes", storedBytes)
            .put("dest_uri", uri)
            .put("kind", kind)
            .put("capture_group", captureGroup)
            .put("created_at", createdAt)
            .toString();
      } catch (JSONException failure) {
        throw new IllegalStateException("Could not record replacement witness");
      }
    }

    boolean sameRow(Content other) {
      return other != null
          && sourceSha.equals(other.sourceSha)
          && sourceBytes == other.sourceBytes
          && storedSha.equals(other.storedSha)
          && storedBytes == other.storedBytes
          && uri.equals(other.uri)
          && kind.equals(other.kind)
          && captureGroup.equals(other.captureGroup)
          && createdAt == other.createdAt;
    }
  }

  public static final class Pending {
    public final String token,
        sourceSha,
        storedSha,
        kind,
        captureGroup,
        pendingName,
        finalName,
        relativeFolder,
        uri,
        volume;
    public final CameraImportFile source;
    public final long storedBytes;
    public final boolean ready;
    final Content replacement;

    Pending(Cursor c) {
      token = c.getString(0);
      source =
          new CameraImportFile(
              c.getString(1), c.getString(2), c.getLong(3), c.getString(4), c.getLong(5));
      sourceSha = c.getString(6);
      storedSha = c.getString(7);
      storedBytes = c.getLong(8);
      kind = c.getString(9);
      captureGroup = c.getString(10);
      pendingName = c.getString(11);
      finalName = c.getString(12);
      relativeFolder = c.getString(13);
      uri = c.isNull(14) ? null : c.getString(14);
      ready = c.getInt(15) != 0;
      volume = c.getString(16);
      replacement = c.isNull(17) ? null : new Content(c.getString(17));
    }
  }

  private static final String CONTENT_FIELDS =
      "source_sha,source_bytes,stored_sha,stored_bytes,dest_uri,kind,capture_group,created_at";
  private static final String PENDING_FIELDS =
      "token,source_id,relative_path,source_bytes,source_stamp,capture_seconds,source_sha,stored_sha,stored_bytes,kind,capture_group,pending_name,final_name,relative_folder,target_uri,ready,volume,replacement_witness";

  private static void hash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid content fingerprint");
  }

  public synchronized Content known(CameraImportFile source) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT "
                    + CONTENT_FIELDS
                    + " FROM contents WHERE source_sha=(SELECT source_sha FROM receipts WHERE source_key=?)",
                new String[] {source.sourceKey()})) {
      if (!c.moveToFirst()) return null;
      Content out = new Content(c);
      if (out.sourceBytes != source.bytes) throw new IllegalStateException("Receipt size mismatch");
      return out;
    }
  }

  /** One read-only snapshot for an SD run; no legacy evidence is adopted here. */
  public synchronized Map<String, Content> sourceSnapshot(String sourceId) {
    Map<String, Content> out = new HashMap<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT c.source_sha,c.source_bytes,c.stored_sha,c.stored_bytes,c.dest_uri,c.kind,c.capture_group,c.created_at,r.source_key FROM receipts r JOIN contents c ON r.source_sha=c.source_sha WHERE r.source_id=?",
                new String[] {sourceId})) {
      while (c.moveToNext()) out.put(c.getString(8), new Content(c));
    }
    return out;
  }

  public synchronized Content find(String sourceSha, long sourceBytes) {
    hash(sourceSha);
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT " + CONTENT_FIELDS + " FROM contents WHERE source_sha=?",
                new String[] {sourceSha})) {
      if (!c.moveToFirst()) return null;
      Content out = new Content(c);
      if (out.sourceBytes != sourceBytes)
        throw new IllegalStateException("Original fingerprint size mismatch");
      return out;
    }
  }

  private void receipt(SQLiteDatabase db, CameraImportFile source, String sourceSha) {
    ContentValues v = new ContentValues();
    v.put("source_key", source.sourceKey());
    v.put("source_id", source.sourceId);
    v.put("relative_path", source.relativePath);
    v.put("source_bytes", source.bytes);
    v.put("source_stamp", source.sourceStamp);
    v.put("capture_seconds", source.captureSeconds);
    v.put("source_sha", sourceSha);
    v.put("created_at", System.currentTimeMillis());
    if (db.insertWithOnConflict("receipts", null, v, SQLiteDatabase.CONFLICT_REPLACE) < 0)
      throw new IllegalStateException("Receipt could not be saved");
  }

  /** Bind an additional transport only after its complete original was hashed. */
  public synchronized void bind(CameraImportFile source, Content content) {
    if (source.bytes != content.sourceBytes)
      throw new IllegalArgumentException("Original size mismatch");
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      receipt(db, source, content.sourceSha);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  private void content(
      SQLiteDatabase db,
      CameraImportFile source,
      String raw,
      String stored,
      long storedBytes,
      String uri,
      String kind,
      String group) {
    hash(raw);
    hash(stored);
    if (storedBytes <= 0 || uri == null || !uri.startsWith("content://media/"))
      throw new IllegalArgumentException("Invalid saved content");
    ContentValues v = new ContentValues();
    v.put("source_sha", raw);
    v.put("source_bytes", source.bytes);
    v.put("stored_sha", stored);
    v.put("stored_bytes", storedBytes);
    v.put("dest_uri", uri);
    v.put("kind", kind);
    v.put("capture_group", group);
    v.put("created_at", System.currentTimeMillis());
    db.insertWithOnConflict("contents", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    try (Cursor c =
        db.rawQuery("SELECT source_bytes FROM contents WHERE source_sha=?", new String[] {raw})) {
      if (!c.moveToFirst() || c.getLong(0) != source.bytes)
        throw new IllegalStateException("Original record collision");
    }
    receipt(db, source, raw);
  }

  private void saveVerified(
      SQLiteDatabase db,
      CameraImportFile source,
      String raw,
      String stored,
      long storedBytes,
      String uri,
      String kind,
      String group,
      Content missing) {
    if (missing == null) {
      Content current = find(raw, source.bytes);
      if (current != null
          && (!current.uri.equals(uri)
              || !current.storedSha.equals(stored)
              || current.storedBytes != storedBytes))
        throw new IllegalStateException("Saved photo changed before commit");
      if (current == null) {
        content(db, source, raw, stored, storedBytes, uri, kind, group);
        return;
      }
    } else {
      hash(raw);
      hash(stored);
      if (!raw.equals(missing.sourceSha)
          || source.bytes != missing.sourceBytes
          || storedBytes <= 0
          || uri == null
          || !uri.startsWith("content://media/"))
        throw new IllegalArgumentException("Invalid verified replacement");
      // Update only the saved location/bytes. The original row, capture group, timestamps,
      // and all transport receipts keep their identities and foreign key.
      ContentValues values = new ContentValues();
      values.put("dest_uri", uri);
      values.put("stored_sha", stored);
      values.put("stored_bytes", storedBytes);
      if (db.update(
              "contents",
              values,
              "source_sha=? AND source_bytes=? AND dest_uri=? AND stored_sha=?"
                  + " AND stored_bytes=? AND kind=? AND capture_group=? AND created_at=?",
              new String[] {
                missing.sourceSha,
                Long.toString(missing.sourceBytes),
                missing.uri,
                missing.storedSha,
                Long.toString(missing.storedBytes),
                missing.kind,
                missing.captureGroup,
                Long.toString(missing.createdAt)
              })
          != 1) throw new IllegalStateException("Saved photo changed before replacement");
    }
    try (Cursor row =
        db.rawQuery(
            "SELECT source_sha FROM receipts WHERE source_key=?",
            new String[] {source.sourceKey()})) {
      if (row.moveToFirst() && raw.equals(row.getString(0))) return;
    }
    receipt(db, source, raw);
  }

  /**
   * Import legacy MediaStore evidence only after full-byte (or narrowly normalized EXIF)
   * verification.
   */
  public synchronized Content adopt(
      CameraImportFile source,
      String raw,
      String stored,
      long storedBytes,
      String uri,
      String kind,
      String group) {
    return adopt(source, raw, stored, storedBytes, uri, kind, group, null);
  }

  synchronized Content adopt(
      CameraImportFile source,
      String raw,
      String stored,
      long storedBytes,
      String uri,
      String kind,
      String group,
      Content missing) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      saveVerified(db, source, raw, stored, storedBytes, uri, kind, group, missing);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    return find(raw, source.bytes);
  }

  public synchronized Pending allocate(
      CameraImportFile source,
      String raw,
      String stored,
      long storedBytes,
      String finalName,
      String folder,
      String kind,
      String group,
      String volume) {
    return allocate(source, raw, stored, storedBytes, finalName, folder, kind, group, volume, null);
  }

  synchronized Pending allocate(
      CameraImportFile source,
      String raw,
      String stored,
      long storedBytes,
      String finalName,
      String folder,
      String kind,
      String group,
      String volume,
      Content replacement) {
    hash(raw);
    hash(stored);
    if (storedBytes <= 0
        || !folder.startsWith("Pictures/CameraSync/")
        || folder.contains("..")
        || finalName.contains("/")
        || finalName.contains("\\"))
      throw new IllegalArgumentException("Invalid publication target");
    if (replacement != null
        && (!raw.equals(replacement.sourceSha) || source.bytes != replacement.sourceBytes))
      throw new IllegalArgumentException("Invalid replacement witness");
    String token = UUID.randomUUID().toString();
    String ext = source.name.substring(source.name.lastIndexOf('.'));
    ContentValues v = new ContentValues();
    v.put("token", token);
    v.put("source_id", source.sourceId);
    v.put("relative_path", source.relativePath);
    v.put("source_bytes", source.bytes);
    v.put("source_stamp", source.sourceStamp);
    v.put("capture_seconds", source.captureSeconds);
    v.put("source_sha", raw);
    v.put("stored_sha", stored);
    v.put("stored_bytes", storedBytes);
    v.put("kind", kind);
    v.put("capture_group", group);
    v.put("volume", volume);
    v.put("pending_name", "__loupe_" + token + ext);
    v.put("final_name", finalName);
    v.put("relative_folder", folder);
    v.put("created_at", System.currentTimeMillis());
    if (replacement != null) v.put("replacement_witness", replacement.encodeWitness());
    getWritableDatabase().insertOrThrow("pending", null, v);
    return pending(token);
  }

  public synchronized void attach(String token, String uri) {
    if (uri == null || !uri.startsWith("content://media/"))
      throw new IllegalArgumentException("Invalid pending URI");
    ContentValues v = new ContentValues();
    v.put("target_uri", uri);
    if (getWritableDatabase().update("pending", v, "token=? AND ready=0", new String[] {token})
        != 1) throw new IllegalStateException("Missing publication journal");
  }

  public synchronized void ready(String token) {
    ContentValues v = new ContentValues();
    v.put("ready", 1);
    if (getWritableDatabase()
            .update("pending", v, "token=? AND target_uri IS NOT NULL", new String[] {token})
        != 1) throw new IllegalStateException("Missing verified publication");
  }

  public synchronized Pending pending(String token) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT " + PENDING_FIELDS + " FROM pending WHERE token=?", new String[] {token})) {
      if (!c.moveToFirst()) return null;
      return new Pending(c);
    }
  }

  public synchronized List<Pending> pending() {
    List<Pending> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT " + PENDING_FIELDS + " FROM pending ORDER BY created_at", null)) {
      if (c.getCount() > 1024)
        throw new IllegalStateException("Excessive publication recovery records");
      while (c.moveToNext()) out.add(new Pending(c));
    }
    return out;
  }

  /**
   * The caller first verifies that this owned URI is published with exactly the journaled bytes.
   */
  public synchronized void commit(String token) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      Pending p = pending(token);
      if (p == null || !p.ready || p.uri == null)
        throw new IllegalStateException("Publication is not verified");
      saveVerified(
          db,
          p.source,
          p.sourceSha,
          p.storedSha,
          p.storedBytes,
          p.uri,
          p.kind,
          p.captureGroup,
          p.replacement);
      db.delete("pending", "token=?", new String[] {token});
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  /** Only after the caller has removed an owned, unpublished pending MediaStore row. */
  public synchronized void discard(String token) {
    getWritableDatabase().delete("pending", "token=?", new String[] {token});
  }

  /**
   * Associate JPEG/RAW only through a matching source event or an already verified cross-route
   * receipt.
   */
  public synchronized String captureGroup(CameraImportFile source, String raw, String kind) {
    String path = source.relativePath;
    int dot = path.lastIndexOf('.');
    String stem =
        (dot > path.lastIndexOf('/') ? path.substring(0, dot) : path).toUpperCase(Locale.ROOT);
    if (source.captureSeconds <= 0) return "content:" + raw;
    Set<String> groups = new HashSet<>();
    boolean conflictingKind = false;
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT r.relative_path,c.capture_group,c.kind,c.source_sha FROM receipts r JOIN contents c ON r.source_sha=c.source_sha WHERE r.source_id=? AND r.capture_seconds=?",
                new String[] {source.sourceId, Long.toString(source.captureSeconds)})) {
      while (c.moveToNext()) {
        String other = c.getString(0);
        int d = other.lastIndexOf('.');
        String otherStem =
            (d > other.lastIndexOf('/') ? other.substring(0, d) : other).toUpperCase(Locale.ROOT);
        if (!stem.equals(otherStem)) continue;
        groups.add(c.getString(1));
        if (kind.equals(c.getString(2)) && !raw.equals(c.getString(3))) conflictingKind = true;
      }
    }
    if (conflictingKind || groups.size() > 1) return "content:" + raw;
    if (groups.size() == 1) return groups.iterator().next();
    try {
      byte[] bytes =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(
                  (source.sourceId + "\n" + stem + "\n" + source.captureSeconds)
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder("capture:");
      for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
      return out.toString();
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  /** Explicit fixture cleanup. Import replacement must retain the old content and its receipts. */
  public synchronized void forgetMissing(String raw) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      db.delete("receipts", "source_sha=?", new String[] {raw});
      db.delete("contents", "source_sha=?", new String[] {raw});
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public synchronized Map<String, String> importDestinations() {
    Map<String, String> out = new LinkedHashMap<>();
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT dest_uri,stored_sha FROM contents", null)) {
      while (c.moveToNext()) {
        String previous = out.putIfAbsent(c.getString(0), c.getString(1));
        if (previous != null && !previous.equals(c.getString(1))) out.put(c.getString(0), "");
      }
    }
    return out;
  }

  /** Stored file size by destination. A negative value marks conflicting sizes for the same URI. */
  public synchronized Map<String, Long> importStoredSizes() {
    Map<String, Long> sizes = new HashMap<>();
    try (Cursor rows =
        getReadableDatabase()
            .rawQuery(
                "SELECT dest_uri, MIN(stored_bytes), MAX(stored_bytes) FROM contents GROUP BY dest_uri",
                null)) {
      while (rows.moveToNext()) {
        long smallest = rows.getLong(1);
        sizes.put(rows.getString(0), smallest == rows.getLong(2) ? smallest : -1L);
      }
    }
    return sizes;
  }

  /** Move only the locations; the contents and per-transport receipts keep their keys. */
  public record MoveReceipt(String name, long bytes, long capture, long sourceModified) {}

  /** Link legacy import records to their original camera metadata, even after EXIF correction. */
  public synchronized Map<String, List<MoveReceipt>> moveReceipts() {
    Map<String, List<MoveReceipt>> out = new HashMap<>();
    try (Cursor rows =
        getReadableDatabase()
            .rawQuery(
                "SELECT c.dest_uri,r.relative_path,r.source_bytes,r.capture_seconds,r.source_id,r.source_stamp "
                    + "FROM contents c JOIN receipts r ON c.source_sha=r.source_sha",
                null)) {
      while (rows.moveToNext()) {
        String relative = rows.getString(1);
        long sourceModified = 0;
        if (rows.getString(4).startsWith("sd-usb:")) {
          try {
            sourceModified = Long.parseLong(rows.getString(5)) / 1000;
          } catch (NumberFormatException ignored) {
            /* Historic or non-numeric source stamp. */
          }
        }
        out.computeIfAbsent(rows.getString(0), ignored -> new ArrayList<>())
            .add(
                new MoveReceipt(
                    relative.substring(relative.lastIndexOf('/') + 1),
                    rows.getLong(2),
                    rows.getLong(3),
                    sourceModified));
      }
    }
    return out;
  }

  public synchronized boolean hasMoveEvidence(String source, String oldAlias, String sha) {
    if (sha == null) return false;
    try (Cursor rows =
        getReadableDatabase()
            .rawQuery(
                "SELECT 1 FROM contents WHERE stored_sha=? AND (dest_uri=? OR dest_uri=?) LIMIT 1",
                new String[] {sha, source, oldAlias == null ? source : oldAlias})) {
      return rows.moveToFirst();
    }
  }

  public synchronized boolean relocate(
      String source, String oldAlias, String destination, String sha) {
    if (sha == null) return false;
    ContentValues values = new ContentValues();
    values.put("dest_uri", destination);
    SQLiteDatabase db = getWritableDatabase();
    db.update(
        "contents",
        values,
        "stored_sha=? AND (dest_uri=? OR dest_uri=?)",
        new String[] {sha, source, oldAlias == null ? source : oldAlias});
    try (Cursor rows =
        db.rawQuery(
            "SELECT 1 FROM contents WHERE stored_sha=? AND dest_uri=? LIMIT 1",
            new String[] {sha, destination})) {
      return rows.moveToFirst();
    }
  }

  public synchronized long contentCount() {
    try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM contents", null)) {
      c.moveToFirst();
      return c.getLong(0);
    }
  }

  public synchronized long receiptCount() {
    try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM receipts", null)) {
      c.moveToFirst();
      return c.getLong(0);
    }
  }
}
