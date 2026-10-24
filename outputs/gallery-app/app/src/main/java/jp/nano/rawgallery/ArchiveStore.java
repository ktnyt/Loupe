package jp.nano.rawgallery;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.net.Uri;
import java.io.*;
import java.util.*;
import org.json.*;

/** Durable verification metadata and previews, separate from expendable caches. */
final class ArchiveStore extends SQLiteOpenHelper {
  private final Context context;

  ArchiveStore(Context c) {
    super(c, "archive.db", null, 2);
    context = c.getApplicationContext();
  }

  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE metadata (key TEXT PRIMARY KEY, model TEXT NOT NULL, taken INTEGER NOT"
            + " NULL)");
    db.execSQL(
        "CREATE TABLE photos (key TEXT PRIMARY KEY, state TEXT NOT NULL, payload TEXT NOT NULL)");
  }

  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (oldVersion < 2)
      db.execSQL(
          "CREATE TABLE metadata (key TEXT PRIMARY KEY, model TEXT NOT NULL, taken INTEGER NOT"
              + " NULL)");
  }

  RawMetadata metadata(PhotoRepository.Photo p) throws Exception {
    String key = key(p);
    try (Cursor c =
        getReadableDatabase()
            .query(
                "metadata",
                new String[] {"model", "taken"},
                "key=?",
                new String[] {key},
                null,
                null,
                null)) {
      if (c.moveToFirst()) return new RawMetadata(c.getString(0), c.getLong(1));
    }
    if (p.isJpeg()) {
      try (InputStream in = context.getContentResolver().openInputStream(p.uri)) {
        if (in == null) throw new IOException("JPEGを開けません");
        android.media.ExifInterface exif = new android.media.ExifInterface(in);
        String model = exif.getAttribute(android.media.ExifInterface.TAG_MODEL);
        long taken =
            RawPreview.captureTime(
                exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL),
                exif.getAttribute(android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL));
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("model", model == null ? "JPEG" : model);
        values.put("taken", taken);
        getWritableDatabase()
            .insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        return new RawMetadata(model == null ? "JPEG" : model, taken);
      }
    }
    try (PhotoRepository.DescriptorSource source =
        new PhotoRepository.DescriptorSource(context.getContentResolver(), p.uri, p.size)) {
      RawPreview.Result result = RawPreview.inspect(source);
      ContentValues values = new ContentValues();
      values.put("key", key);
      values.put("model", result.model);
      values.put("taken", result.taken);
      getWritableDatabase()
          .insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE);
      return new RawMetadata(result.model, result.taken);
    }
  }

  Map<String, RawMetadata> metadataSnapshot() {
    Map<String, RawMetadata> result = new HashMap<>();
    try (Cursor c =
        getReadableDatabase()
            .query(
                "metadata", new String[] {"key", "model", "taken"}, null, null, null, null, null)) {
      while (c.moveToNext())
        result.put(c.getString(0), new RawMetadata(c.getString(1), c.getLong(2)));
    }
    return result;
  }

  static final class RawMetadata {
    final String model;
    final long taken;

    RawMetadata(String model, long taken) {
      this.model = model;
      this.taken = taken;
    }
  }

  static String key(PhotoRepository.Photo p) throws IOException {
    return PhotoRepository.digest(p.folder + p.name + ":" + p.size + ":" + p.modified);
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
    if (getWritableDatabase()
            .insertWithOnConflict("photos", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        < 0) throw new IOException("同期記録を永続保存できません");
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

  List<JSONObject> all() throws Exception {
    List<JSONObject> values = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .query("photos", new String[] {"state", "payload"}, null, null, null, null, null)) {
      while (c.moveToNext())
        values.add(new JSONObject(c.getString(1)).put("state", c.getString(0)));
    }
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
        j.optLong("created", 0));
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
