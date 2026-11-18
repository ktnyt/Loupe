package jp.nano.rawgallery;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** A versioned recovery point. Imported rows never become local verified backup records. */
final class E2RecoveryCatalog extends SQLiteOpenHelper {
  static final int MAX_BYTES = 512 * 1024;
  private static final int MAX_ROWS = 2000;
  private static final Object PUBLISH_LOCK = new Object();
  private static final Object CACHE_LOCK = new Object();

  record Snapshot(long created, Map<String, JSONObject> records,
                  byte[] encoded, boolean previous) {}

  E2RecoveryCatalog(Context context) {
    super(context, "e2_recovery_catalog.db", null, 1);
  }

  @Override public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE snapshots (bucket TEXT PRIMARY KEY, body BLOB NOT NULL)");
  }

  @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    throw new IllegalStateException("Unsupported e2 recovery cache migration");
  }

  Snapshot cached(String bucket) throws Exception {
    try (Cursor row = getReadableDatabase().query("snapshots", new String[]{"body"},
        "bucket = ?", new String[]{bucket}, null, null, null)) {
      return row.moveToFirst() ? decode(bucket, row.getBlob(0), false) : null;
    }
  }

  Snapshot load(E2Archive archive, String bucket, List<JSONObject> local) throws Exception {
    Snapshot candidate = remote(archive, bucket);
    if (candidate == null) return null;
    merged(bucket, candidate, local, archive);
    synchronized (CACHE_LOCK) {
      Snapshot previous = cached(bucket);
      if (previous != null) {
        for (Map.Entry<String, JSONObject> row : previous.records().entrySet()) {
          JSONObject incoming = candidate.records().get(row.getKey());
          JSONObject saved = row.getValue();
          if (incoming == null || !sameContent(saved, incoming))
            throw new IOException("新しい復元一覧に不足または競合があります");
          if (!sameOriginal(saved, incoming)) archive.verifyVersion(incoming);
        }
      }
      if (Thread.currentThread().isInterrupted()) throw new IOException("取得を中止しました");
      ContentValues values = new ContentValues();
      values.put("bucket", bucket);
      values.put("body", candidate.encoded());
      getWritableDatabase().replaceOrThrow("snapshots", null, values);
    }
    return candidate;
  }

  static Snapshot publish(E2Archive archive, String bucket, List<JSONObject> local) throws Exception {
    if (local.isEmpty()) throw new IOException("カタログに保存できる原本がありません");
    synchronized (PUBLISH_LOCK) {
      Snapshot current = remote(archive, bucket);
      Map<String, JSONObject> all = merged(bucket, current, local, archive);
      Snapshot next = encode(bucket, System.currentTimeMillis(), all);
      if (current != null && !current.previous()
          && entries(current.records()).toString().equals(entries(all).toString())) return current;
      archive.writeCatalog(next.encoded());
      return next;
    }
  }

  // The archive is present for online catalog operations. A cached restore list is read-only:
  // actual restoration always verifies the selected version before exporting any bytes.
  static Map<String, JSONObject> merged(String bucket, Snapshot snapshot, List<JSONObject> local,
      E2Archive archive) throws Exception {
    Map<String, JSONObject> result = new TreeMap<>();
    if (snapshot != null) result.putAll(snapshot.records());
    for (JSONObject entry : local) {
      JSONObject clean = normalize(bucket, entry);
      String key = clean.getString("key");
      JSONObject previous = result.get(key);
      if (previous != null && !sameOriginal(previous, clean)) {
        if (!sameContent(previous, clean))
          throw new IOException("保管記録と復元一覧で写真の内容が一致しません");
        if (archive != null) archive.verifyVersion(previous);
        // Keep the published version so older recovery lists remain valid.
        continue;
      }
      result.put(key, clean);
    }
    return result;
  }

  private static Snapshot remote(E2Archive archive, String bucket) throws Exception {
    E2Archive.CatalogObject latest = archive.readCatalog("");
    if (latest != null) {
      try {
        return decode(bucket, latest.body(), false);
      } catch (InvalidCatalog ignored) {
        // Read the retained generations if the return value cannot be validated.
      }
    }
    List<String> versions = archive.previousCatalogVersions();
    if (latest == null && versions.isEmpty()) return null;
    for (String version : versions) {
      if (latest != null && version.equals(latest.version())) continue;
      E2Archive.CatalogObject earlier = archive.readCatalog(version);
      if (earlier == null) continue;
      try {
        return decode(bucket, earlier.body(), true);
      } catch (InvalidCatalog ignored) {
        // Network and permission failures still propagate.
      }
    }
    throw new InvalidCatalog();
  }

  static Snapshot latest(E2Archive archive, String bucket) throws Exception {
    E2Archive.CatalogObject object = archive.readCatalog("");
    if (object == null) throw new IOException("最新の復元一覧がありません");
    return decode(bucket, object.body(), false);
  }

  private static Snapshot encode(String bucket, long created, Map<String, JSONObject> records)
      throws Exception {
    if (records.isEmpty() || records.size() > MAX_ROWS)
      throw new IOException("復元一覧の件数が対応範囲外です");
    String payload = new JSONObject().put("schema", 1).put("bucket", bucket)
        .put("created", created).put("count", records.size()).put("photos", entries(records)).toString();
    byte[] encoded = new JSONObject().put("payload", payload)
        .put("sha256", E2Archive.sha256(payload.getBytes(StandardCharsets.UTF_8)))
        .toString().getBytes(StandardCharsets.UTF_8);
    return decode(bucket, encoded, false);
  }

  private static JSONArray entries(Map<String, JSONObject> records) {
    return new JSONArray(new ArrayList<>(records.values()));
  }

  private static Snapshot decode(String bucket, byte[] encoded, boolean previous)
      throws InvalidCatalog {
    try {
      if (encoded.length > MAX_BYTES) throw new IOException();
      JSONObject envelope = object(new String(encoded, StandardCharsets.UTF_8));
      String payload = string(envelope, "payload");
      if (!E2Archive.sha256(payload.getBytes(StandardCharsets.UTF_8))
          .equals(string(envelope, "sha256"))) throw new IOException();
      JSONObject doc = object(payload);
      if (integer(doc, "schema") != 1 || !bucket.equals(string(doc, "bucket")))
        throw new IOException();
      long created = integer(doc, "created");
      JSONArray photos = doc.getJSONArray("photos");
      if (created <= 0 || photos.length() == 0 || photos.length() > MAX_ROWS
          || photos.length() != integer(doc, "count")) throw new IOException();
      Map<String, JSONObject> records = new TreeMap<>();
      for (int i = 0; i < photos.length(); i++) {
        JSONObject clean = normalize(bucket, photos.getJSONObject(i));
        if (records.put(clean.getString("key"), clean) != null) throw new IOException();
      }
      return new Snapshot(created, records, encoded, previous);
    } catch (Exception invalid) {
      throw new InvalidCatalog();
    }
  }

  private static JSONObject object(String json) throws Exception {
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
    return new JSONObject(json);
  }

  private static JSONObject normalize(String bucket, JSONObject row) throws Exception {
    String key = string(row, "key"), sha = string(row, "sha"), name = string(row, "name");
    String version = E2Archive.checkedVersion(string(row, "version"));
    long size = integer(row, "size");
    long taken = row.has("taken") ? integer(row, "taken") : 0;
    long verifiedAt = row.has("verifiedAt") ? integer(row, "verifiedAt") : 0;
    if (!bucket.equals(string(row, "bucket")) || !"idrive-e2".equals(string(row, "provider"))
        || !"verified".equals(string(row, "state")) || !key.matches("[0-9a-f]{32}")
        || !sha.matches("[0-9a-f]{64}") || size <= 0 || size > E2Archive.LIMIT || taken < 0
        || verifiedAt < 0 || name.length() > 255 || name.lastIndexOf('.') <= 0
        || !RawFormats.accepts(name)
        || name.matches("(?s).*[\\\\/\\p{Cntrl}].*"))
      throw new IOException("復元一覧の保管記録が不正です");
    return new JSONObject().put("key", key).put("sha", sha).put("name", name)
        .put("version", version).put("size", size).put("taken", taken)
        .put("verifiedAt", verifiedAt).put("bucket", bucket)
        .put("provider", "idrive-e2").put("state", "verified").put("mime", RawFormats.mime(name));
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
