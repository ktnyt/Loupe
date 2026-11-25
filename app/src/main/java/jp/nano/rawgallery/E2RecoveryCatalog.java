package jp.nano.rawgallery;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.icu.text.Normalizer2;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Versioned recovery points. Imported rows never become local verified backup records. */
final class E2RecoveryCatalog extends SQLiteOpenHelper {
  static final String LEGACY = "loupe/catalog/v1.json";
  static final String INDEX = "loupe/catalog/v2.json";
  static final int MAX_BYTES = 512 * 1024;
  private static final int MAX_LEGACY_ROWS = 2000;
  static final int PART_BYTES = 256 * 1024;
  private static final int PART_ROWS = 256;
  private static final int MAX_PARTS = 512;
  private static final int MAX_ROWS = MAX_PARTS * PART_ROWS;
  private static final Object PUBLISH_LOCK = new Object();
  private static final Object CACHE_LOCK = new Object();

  interface Storage {
    E2Archive.CatalogObject readCatalog(String key, String version) throws Exception;
    List<String> previousCatalogVersions(String root) throws Exception;
    // Conditional write followed by an exact-version GET and byte comparison.
    E2Archive.CatalogObject writeCatalog(String key, byte[] body, E2Archive.CatalogObject expected)
        throws Exception;
    void verifyVersion(JSONObject record) throws Exception;
  }

  record Snapshot(String bucket, long created, Rows records, boolean previous,
                  E2Archive.CatalogObject index, Map<String, String> partVersions) {}

  private record Row(String key, String sha, String name, String version, long size, long taken,
                     long verifiedAt, String mime) {
    JSONObject json(String bucket) {
      try {
        return new JSONObject().put("key", key).put("sha", sha).put("name", name)
            .put("version", version).put("size", size).put("taken", taken)
            .put("verifiedAt", verifiedAt).put("bucket", bucket)
            .put("provider", "idrive-e2").put("state", "verified").put("mime", mime);
      } catch (org.json.JSONException impossible) {
        throw new IllegalStateException("A validated catalog row could not be serialized", impossible);
      }
    }

    boolean sameContent(Row other) {
      return size == other.size && name.equals(other.name) && sha.equals(other.sha);
    }
  }

  static final class SavedRows extends AbstractList<JSONObject> {
    private final String bucket;
    private final List<Row> order;

    private SavedRows(String bucket, List<Row> order) {
      this.bucket = bucket;
      this.order = order;
    }

    @Override public int size() { return order.size(); }
    @Override public JSONObject get(int index) { return order.get(index).json(bucket); }

    SavedRows matchingFilename(String query) {
      Normalizer2 fold = Normalizer2.getNFKCCasefoldInstance();
      String needle = fold.normalize(query).trim();
      if (needle.isEmpty()) return this;
      List<Row> matches = new ArrayList<>();
      for (Row row : order) {
        if (fold.normalize(row.name()).contains(needle)) matches.add(row);
      }
      return new SavedRows(bucket, matches);
    }
  }

  /** Keep validated rows compact and only build a JSON object at the original API boundary. */
  static final class Rows extends AbstractMap<String, JSONObject> {
    private final String bucket;
    private final LinkedHashMap<String, Row> rows;

    Rows(String bucket) {
      this(bucket, new LinkedHashMap<>());
    }

    private Rows(String bucket, LinkedHashMap<String, Row> rows) {
      this.bucket = bucket;
      this.rows = rows;
    }

    private Rows copy() {
      return new Rows(bucket, new LinkedHashMap<>(rows));
    }

    @Override public int size() { return rows.size(); }
    @Override public boolean containsKey(Object key) { return rows.containsKey(key); }

    @Override public JSONObject get(Object key) {
      Row row = rows.get(key);
      return row == null ? null : row.json(bucket);
    }

    @Override public Set<Entry<String, JSONObject>> entrySet() {
      return new AbstractSet<>() {
        @Override public int size() { return rows.size(); }
        @Override public Iterator<Entry<String, JSONObject>> iterator() {
          Iterator<Row> source = rows.values().iterator();
          return new Iterator<>() {
            @Override public boolean hasNext() { return source.hasNext(); }
            @Override public Entry<String, JSONObject> next() {
              Row row = source.next();
              return new SimpleImmutableEntry<>(row.key(), row.json(bucket));
            }
          };
        }
      };
    }

    SavedRows sorted() {
      List<Row> order = new ArrayList<>(rows.values());
      order.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
      return new SavedRows(bucket, order);
    }

    String fingerprint() throws Exception {
      List<String> keys = new ArrayList<>(rows.keySet());
      Collections.sort(keys);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String key : keys) {
        digest.update(bytes(rows.get(key).json(bucket).toString()));
        digest.update((byte) '\n');
      }
      return Base64.getEncoder().encodeToString(digest.digest());
    }

    void add(JSONObject input) throws Exception {
      Row row = normalize(bucket, input);
      if (rows.putIfAbsent(row.key(), row) != null) throw new InvalidCatalog();
    }
  }

  E2RecoveryCatalog(Context context) {
    this(context, "e2_recovery_catalog.db");
  }

  E2RecoveryCatalog(Context context, String databaseName) {
    super(context, databaseName, null, 2);
  }

  @Override public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE snapshots (bucket TEXT PRIMARY KEY, body BLOB NOT NULL)");
    createRowCache(db);
  }

  @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (oldVersion == 1 && newVersion == 2) createRowCache(db);
    else throw new IllegalStateException("Unsupported e2 recovery cache migration");
  }

  private static void createRowCache(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE generations (bucket TEXT PRIMARY KEY, created INTEGER NOT NULL,"
        + " count INTEGER NOT NULL, digest BLOB NOT NULL)");
    db.execSQL("CREATE TABLE catalog_rows (bucket TEXT NOT NULL, ordinal INTEGER NOT NULL,"
        + " body BLOB NOT NULL, PRIMARY KEY(bucket, ordinal))");
  }

  Snapshot cached(String bucket) throws Exception {
    synchronized (CACHE_LOCK) {
      String[] args = {bucket};
      SQLiteDatabase db = getReadableDatabase();
      try (Cursor meta = db.rawQuery("SELECT created,count,digest FROM generations WHERE bucket=?", args)) {
        if (meta.moveToFirst()) {
          long created = meta.getLong(0), count = meta.getLong(1);
          if (created <= 0 || count <= 0 || count > MAX_ROWS) throw new InvalidCatalog();
          MessageDigest digest = MessageDigest.getInstance("SHA-256");
          Rows records = new Rows(bucket);
          try (Cursor rows = db.rawQuery("SELECT body FROM catalog_rows WHERE bucket=? ORDER BY ordinal", args)) {
            while (rows.moveToNext()) {
              if (records.size() >= count) throw new InvalidCatalog();
              byte[] body = rows.getBlob(0);
              digest.update(body);
              records.add(object(new String(body, StandardCharsets.UTF_8)));
            }
          }
          if (records.size() != count || !Arrays.equals(digest.digest(), meta.getBlob(2)))
            throw new InvalidCatalog();
          return new Snapshot(bucket, created, records, false, null, Collections.emptyMap());
        }
      }
      // Keep the v1 cache usable offline until the first successful online import.
      try (Cursor row = db.rawQuery("SELECT body FROM snapshots WHERE bucket=?", args)) {
        return row.moveToFirst() ? decodeLegacy(bucket, row.getBlob(0), false) : null;
      }
    }
  }

  Snapshot load(Storage archive, String bucket, List<JSONObject> local) throws Exception {
    return load(archive, bucket, rows(bucket, local));
  }

  Snapshot load(Storage archive, String bucket, Rows local) throws Exception {
    requireBucket(bucket, local);
    Snapshot candidate = remote(archive, bucket);
    if (candidate == null) return null;
    if (!local.isEmpty()) merged(bucket, candidate, local, archive);
    synchronized (CACHE_LOCK) {
      Snapshot previous = cached(bucket);
      if (previous != null) {
        for (Row saved : previous.records().rows.values()) {
          Row incoming = candidate.records().rows.get(saved.key());
          if (incoming == null || !saved.sameContent(incoming))
            throw new IOException("新しい復元一覧に不足または競合があります");
          if (!saved.version().equals(incoming.version())) archive.verifyVersion(incoming.json(bucket));
        }
      }
      cache(bucket, candidate);
    }
    return candidate;
  }

  private void cache(String bucket, Snapshot candidate) throws Exception {
    SQLiteDatabase db = getWritableDatabase();
    String[] args = {bucket};
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    db.beginTransaction();
    try {
      db.delete("catalog_rows", "bucket=?", args);
      ContentValues values = new ContentValues();
      values.put("bucket", bucket);
      int ordinal = 0;
      for (JSONObject row : candidate.records().values()) {
        if (Thread.currentThread().isInterrupted()) throw new IOException("取得を中止しました");
        byte[] body = bytes(row.toString());
        digest.update(body);
        values.put("ordinal", ordinal++);
        values.put("body", body);
        db.insertOrThrow("catalog_rows", null, values);
      }
      values.clear();
      values.put("bucket", bucket);
      values.put("created", candidate.created());
      values.put("count", ordinal);
      values.put("digest", digest.digest());
      db.replaceOrThrow("generations", null, values);
      db.delete("snapshots", "bucket=?", args);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  static Snapshot publish(Storage archive, String bucket, List<JSONObject> local) throws Exception {
    return publish(archive, bucket, rows(bucket, local));
  }

  static Snapshot publish(Storage archive, String bucket, Rows local) throws Exception {
    requireBucket(bucket, local);
    if (local.isEmpty()) throw new IOException("カタログに保存できるオリジナルがありません");
    synchronized (PUBLISH_LOCK) {
      Snapshot current = generation(archive, bucket, INDEX, false);
      Snapshot legacy = generation(archive, bucket, LEGACY, false);
      Rows all = current == null ? new Rows(bucket) : current.records().copy();
      // A still-active older installation can publish additional v1 rows after migration.
      if (legacy != null) mergeRows(all, legacy.records(), archive);
      mergeRows(all, local, archive);
      if (all.size() > MAX_ROWS) throw new IOException("復元一覧の件数が対応範囲外です");

      JSONArray parts = new JSONArray();
      Map<String, String> versions = new HashMap<>();
      Map<String, String> reusable = current == null ? Collections.emptyMap() : current.partVersions();
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      body.write('[');
      int count = 0;
      for (JSONObject row : all.values()) {
        byte[] encoded = bytes(row.toString());
        if (count == PART_ROWS || body.size() + encoded.length + 2 > PART_BYTES) {
          appendPart(archive, parts, reusable, versions, body, count);
          body.reset();
          body.write('[');
          count = 0;
        }
        if (count++ > 0) body.write(',');
        body.write(encoded);
      }
      appendPart(archive, parts, reusable, versions, body, count);
      if (current != null && current.partVersions().equals(versions)) return current;
      long created = System.currentTimeMillis();
      JSONObject doc = new JSONObject().put("schema", 2).put("bucket", bucket)
          .put("created", created).put("count", all.size()).put("parts", parts);
      byte[] encoded = envelope(doc);
      if (encoded.length > MAX_BYTES) throw new IOException("復元一覧の索引が大きすぎます");
      E2Archive.CatalogObject saved = archive.writeCatalog(INDEX, encoded,
          current == null ? null : current.index());
      return new Snapshot(bucket, created, all, false, saved, versions);
    }
  }

  private static void appendPart(Storage archive, JSONArray parts, Map<String, String> reusable,
      Map<String, String> versions, ByteArrayOutputStream buffer, int count) throws Exception {
    buffer.write(']');
    byte[] body = buffer.toByteArray();
    if (count < 1 || count > PART_ROWS || body.length > PART_BYTES || parts.length() >= MAX_PARTS)
      throw new IOException("復元一覧の分割サイズが対応範囲外です");
    String sha = E2Archive.sha256(body);
    String version = reusable.get(sha);
    if (version == null) {
      String key = partKey(sha);
      E2Archive.CatalogObject existing = archive.readCatalog(key, "");
      if (existing == null) existing = archive.writeCatalog(key, body, null);
      else existing = archive.readCatalog(key, existing.version());
      if (existing == null || !Arrays.equals(body, existing.body())) throw new InvalidCatalog();
      version = existing.version();
    }
    if (versions.put(sha, version) != null) throw new InvalidCatalog();
    parts.put(new JSONObject().put("sha256", sha).put("version", version).put("count", count));
  }

  static void confirmLatest(Storage archive, Snapshot snapshot) throws Exception {
    if (snapshot.previous() || snapshot.index() == null) throw new InvalidCatalog();
    // Re-read every pinned part. The root can remain unchanged while a part becomes unavailable.
    decode(archive, snapshot.bucket(), INDEX, snapshot.index(), false);
    E2Archive.CatalogObject current = archive.readCatalog(INDEX, "");
    if (current == null || !snapshot.index().version().equals(current.version())
        || !Arrays.equals(snapshot.index().body(), current.body()))
      throw new IOException("公開後に復元一覧の最新版が変わりました");
  }

  private static Rows rows(String bucket, List<JSONObject> local) throws Exception {
    Rows result = new Rows(bucket);
    for (JSONObject entry : local) {
      Row clean = normalize(bucket, entry);
      mergeRow(result, clean, null);
    }
    return result;
  }

  static Rows merged(String bucket, Snapshot snapshot, Rows local, Storage archive) throws Exception {
    if (snapshot != null) requireBucket(bucket, snapshot.records());
    Rows result = snapshot == null ? new Rows(bucket) : snapshot.records().copy();
    mergeRows(result, local, archive);
    return result;
  }

  private static void requireBucket(String bucket, Rows rows) throws IOException {
    if (!bucket.equals(rows.bucket)) throw new IOException("復元一覧の保存先が一致しません");
  }

  private static void mergeRows(Rows result, Rows incoming, Storage archive) throws Exception {
    requireBucket(result.bucket, incoming);
    for (Row row : incoming.rows.values()) mergeRow(result, row, archive);
  }

  private static void mergeRow(Rows result, Row clean, Storage archive) throws Exception {
    Row previous = result.rows.get(clean.key());
    if (previous != null) {
      if (!previous.sameContent(clean))
        throw new IOException("保管記録と復元一覧でオリジナルの内容が一致しません");
      if (!previous.version().equals(clean.version())) {
        if (archive != null) archive.verifyVersion(previous.json(result.bucket));
        return;
      }
      if (previous.taken() == clean.taken() && previous.verifiedAt() == clean.verifiedAt()) return;
    }
    result.rows.put(clean.key(), clean);
  }

  static Snapshot remote(Storage archive, String bucket) throws Exception {
    Snapshot modern;
    try {
      modern = generation(archive, bucket, INDEX, true);
    } catch (InvalidCatalog invalid) {
      Snapshot legacy = generation(archive, bucket, LEGACY, true);
      if (legacy == null) throw invalid;
      return new Snapshot(bucket, legacy.created(), legacy.records(), true, null, Collections.emptyMap());
    }
    if (modern == null) return generation(archive, bucket, LEGACY, true);
    Snapshot legacy;
    try {
      legacy = generation(archive, bucket, LEGACY, true);
    } catch (InvalidCatalog invalid) {
      // An intact v2 generation can still be restored when all retained v1 generations are invalid.
      return new Snapshot(bucket, modern.created(), modern.records(), true, null, Collections.emptyMap());
    }
    if (legacy == null) return modern;
    Rows all = modern.records().copy();
    mergeRows(all, legacy.records(), archive);
    if (all.size() > MAX_ROWS) throw new IOException("復元一覧の件数が対応範囲外です");
    // This read-only view may span two independent writers and has no single published root.
    return new Snapshot(bucket, Math.max(modern.created(), legacy.created()), all,
        modern.previous() || legacy.previous(), null, Collections.emptyMap());
  }

  static Snapshot latest(Storage archive, String bucket) throws Exception {
    Snapshot snapshot = generation(archive, bucket, INDEX, false);
    if (snapshot == null) snapshot = generation(archive, bucket, LEGACY, false);
    if (snapshot == null) throw new IOException("最新の復元一覧がありません");
    return snapshot;
  }

  private static Snapshot generation(Storage archive, String bucket, String root, boolean recover)
      throws Exception {
    E2Archive.CatalogObject latest = archive.readCatalog(root, "");
    if (latest != null) {
      try {
        return decode(archive, bucket, root, latest, false);
      } catch (InvalidCatalog invalid) {
        if (!recover) throw invalid;
      }
    }
    List<String> versions = archive.previousCatalogVersions(root);
    if (latest == null && versions.isEmpty()) return null;
    if (recover) {
      for (String version : versions) {
        if (latest != null && version.equals(latest.version())) continue;
        E2Archive.CatalogObject earlier = archive.readCatalog(root, version);
        if (earlier == null) continue;
        try {
          return decode(archive, bucket, root, earlier, true);
        } catch (InvalidCatalog ignored) {
          // Only invalid or missing generations permit fallback. Transport failures propagate.
        }
      }
    }
    throw new InvalidCatalog();
  }

  private record Part(String sha, String version, int count) {}

  private static Snapshot decode(Storage archive, String bucket, String root,
      E2Archive.CatalogObject obj, boolean previous) throws Exception {
    if (LEGACY.equals(root)) return decodeLegacy(bucket, obj.body(), previous);
    JSONObject doc = payload(bucket, 2, obj.body());
    List<Part> parts = new ArrayList<>();
    Map<String, String> versions = new HashMap<>();
    long created;
    try {
      long total = integer(doc, "count");
      created = integer(doc, "created");
      JSONArray list = doc.getJSONArray("parts");
      if (created <= 0 || total < 1 || total > MAX_ROWS || list.length() < 1
          || list.length() > MAX_PARTS) throw new InvalidCatalog();
      long sum = 0;
      for (int i = 0; i < list.length(); i++) {
        JSONObject ref = list.getJSONObject(i);
        String sha = string(ref, "sha256"), version = E2Archive.checkedVersion(string(ref, "version"));
        long count = integer(ref, "count");
        if (!sha.matches("[0-9a-f]{64}") || count < 1 || count > PART_ROWS
            || versions.put(sha, version) != null) throw new InvalidCatalog();
        parts.add(new Part(sha, version, (int) count));
        sum += count;
      }
      if (sum != total) throw new InvalidCatalog();
    } catch (Exception invalid) {
      throw new InvalidCatalog();
    }
    Rows records = new Rows(bucket);
    for (Part part : parts) {
      E2Archive.CatalogObject piece = archive.readCatalog(partKey(part.sha()), part.version());
      if (piece == null || !part.version().equals(piece.version()) || piece.body().length > PART_BYTES
          || !part.sha().equals(E2Archive.sha256(piece.body()))) throw new InvalidCatalog();
      try {
        String json = new String(piece.body(), StandardCharsets.UTF_8);
        validateJson(json);
        JSONArray rows = new JSONArray(json);
        if (rows.length() != part.count()) throw new InvalidCatalog();
        for (int i = 0; i < rows.length(); i++) records.add(rows.getJSONObject(i));
      } catch (Exception invalid) {
        throw new InvalidCatalog();
      }
    }
    return new Snapshot(bucket, created, records, previous, obj, versions);
  }

  private static Snapshot decodeLegacy(String bucket, byte[] encoded, boolean previous)
      throws InvalidCatalog {
    JSONObject doc = payload(bucket, 1, encoded);
    try {
      long created = integer(doc, "created");
      JSONArray photos = doc.getJSONArray("photos");
      if (created <= 0 || photos.length() < 1 || photos.length() > MAX_LEGACY_ROWS
          || photos.length() != integer(doc, "count")) throw new InvalidCatalog();
      Rows records = new Rows(bucket);
      for (int i = 0; i < photos.length(); i++) {
        JSONObject row = photos.getJSONObject(i);
        if (integer(row, "size") > E2Archive.LIMIT) throw new InvalidCatalog();
        records.add(row);
      }
      return new Snapshot(bucket, created, records, previous, null, Collections.emptyMap());
    } catch (Exception invalid) {
      throw new InvalidCatalog();
    }
  }

  private static String partKey(String sha) { return "loupe/catalog/parts/" + sha + ".json"; }

  private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

  private static byte[] envelope(JSONObject doc) throws Exception {
    String payload = doc.toString();
    return bytes(new JSONObject().put("payload", payload)
        .put("sha256", E2Archive.sha256(bytes(payload))).toString());
  }

  private static JSONObject payload(String bucket, int schema, byte[] encoded) throws InvalidCatalog {
    try {
      if (encoded.length > MAX_BYTES) throw new InvalidCatalog();
      JSONObject envelope = object(new String(encoded, StandardCharsets.UTF_8));
      String payload = string(envelope, "payload");
      if (!E2Archive.sha256(bytes(payload)).equals(string(envelope, "sha256")))
        throw new InvalidCatalog();
      JSONObject doc = object(payload);
      if (integer(doc, "schema") != schema || !bucket.equals(string(doc, "bucket")))
        throw new InvalidCatalog();
      return doc;
    } catch (Exception invalid) {
      throw new InvalidCatalog();
    }
  }

  private static JSONObject object(String json) throws Exception {
    validateJson(json);
    return new JSONObject(json);
  }

  private static void validateJson(String json) throws Exception {
    try (JsonReader reader = new JsonReader(new StringReader(json))) {
      int depth = 0;
      while (reader.peek() != JsonToken.END_DOCUMENT) {
        switch (reader.peek()) {
          case BEGIN_OBJECT -> { reader.beginObject(); depth++; }
          case BEGIN_ARRAY -> { reader.beginArray(); depth++; }
          case END_OBJECT -> { reader.endObject(); depth--; }
          case END_ARRAY -> { reader.endArray(); depth--; }
          case NAME -> reader.nextName();
          case NUMBER, STRING -> reader.nextString();
          case BOOLEAN -> reader.nextBoolean();
          case NULL -> reader.nextNull();
          default -> throw new IOException("Unexpected catalog token");
        }
        if (depth > 8) throw new IOException("Catalog nesting exceeds its schema");
      }
    }
  }

  private static Row normalize(String bucket, JSONObject row) throws Exception {
    String key = string(row, "key"), sha = string(row, "sha"), name = string(row, "name");
    String version = E2Archive.checkedVersion(string(row, "version"));
    long size = integer(row, "size");
    long taken = row.has("taken") ? integer(row, "taken") : 0;
    long verifiedAt = row.has("verifiedAt") ? integer(row, "verifiedAt") : 0;
    if (!bucket.equals(string(row, "bucket")) || !"idrive-e2".equals(string(row, "provider"))
        || !"verified".equals(string(row, "state")) || !key.matches("[0-9a-f]{32}")
        || !sha.matches("[0-9a-f]{64}") || size <= 0 || size > E2Archive.ORIGINAL_LIMIT || taken < 0
        || verifiedAt < 0 || name.length() > 255 || name.lastIndexOf('.') <= 0
        || !(RawFormats.accepts(name) || VideoFormats.accepts(name))
        || name.matches("(?s).*[\\\\/\\p{Cntrl}].*"))
      throw new IOException("復元一覧の保管記録が不正です");
    return new Row(key, sha, name, version, size, taken, verifiedAt,
        VideoFormats.accepts(name) ? VideoFormats.mime(name) : RawFormats.mime(name));
  }

  static boolean sameOriginal(JSONObject left, JSONObject right) throws Exception {
    return sameContent(left, right) && string(left, "version").equals(string(right, "version"));
  }

  static boolean sameContent(JSONObject left, JSONObject right) throws Exception {
    return string(left, "bucket").equals(string(right, "bucket"))
        && string(left, "key").equals(string(right, "key"))
        && string(left, "name").equals(string(right, "name"))
        && string(left, "sha").equals(string(right, "sha"))
        && integer(left, "size") == integer(right, "size");
  }

  private static long integer(JSONObject doc, String key) throws Exception {
    Object value = doc.get(key);
    if (value instanceof Integer || value instanceof Long) return ((Number) value).longValue();
    throw new IOException("Expected a catalog integer");
  }

  private static String string(JSONObject doc, String key) throws Exception {
    if (doc.get(key) instanceof String value) return value;
    throw new IOException("Expected a catalog string");
  }

  private static final class InvalidCatalog extends IOException {
    InvalidCatalog() { super("復元一覧の形式または照合値が不正です"); }
  }
}
