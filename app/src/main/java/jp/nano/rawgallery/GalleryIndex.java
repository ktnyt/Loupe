package jp.nano.rawgallery;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.net.Uri;
import android.provider.MediaStore;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Rebuildable browsing index; never participates in backup or deletion decisions. */
final class GalleryIndex extends SQLiteOpenHelper {
  static final java.util.concurrent.ExecutorService WORKER =
      new DiagnosticExecutor("GalleryIndex.worker", 1);
  private static final String COLS = "uri,name,folder,size,modified,taken,added,created";

  GalleryIndex(Context c) {
    super(c, "gallery-index.db", null, 1);
    setWriteAheadLoggingEnabled(true);
  }

  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE photos (ordinal INTEGER PRIMARY KEY, bundle INTEGER, camera TEXT, uri"
            + " TEXT,name TEXT,folder TEXT,size INTEGER,modified INTEGER,taken INTEGER,added"
            + " INTEGER,created INTEGER)");
    db.execSQL("CREATE INDEX photo_folder ON photos(folder,ordinal)");
    db.execSQL("CREATE TABLE albums (folder TEXT PRIMARY KEY,count INTEGER,cover INTEGER)");
    db.execSQL("CREATE TABLE state (id INTEGER PRIMARY KEY,signature TEXT,updated INTEGER)");
  }

  public void onUpgrade(SQLiteDatabase db, int old, int next) {
    throw new IllegalStateException("Unsupported index version");
  }

  static final class Album {
    final String folder;
    final int count;
    final PhotoRepository.Photo cover;

    Album(String folder, int count, PhotoRepository.Photo cover) {
      this.folder = folder;
      this.count = count;
      this.cover = cover;
    }

    String name() {
      String s = folder.replaceAll("/+$", "");
      int n = s.lastIndexOf('/');
      return n < 0 ? s : s.substring(n + 1);
    }
  }

  static final class Snapshot {
    final List<PhotoRepository.Photo> photos = new ArrayList<>();
    final List<List<Integer>> bundles = new ArrayList<>();
  }

  private static PhotoRepository.Photo photo(Cursor c, int o) {
    return new PhotoRepository.Photo(
        Uri.parse(c.getString(o)),
        c.getString(o + 1),
        c.getString(o + 2),
        c.getLong(o + 3),
        c.getLong(o + 4),
        c.getLong(o + 5),
        c.getLong(o + 6),
        c.getLong(o + 7));
  }

  List<Album> albums() {
    List<Album> result = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT"
                    + " a.folder,a.count,p.uri,p.name,p.folder,p.size,p.modified,p.taken,p.added,p.created"
                    + " FROM albums a JOIN photos p ON p.ordinal=a.cover ORDER BY a.cover",
                null)) {
      while (c.moveToNext()) result.add(new Album(c.getString(0), c.getInt(1), photo(c, 2)));
    }
    return result;
  }

  List<PhotoRepository.Photo> recent(int limit) {
    List<PhotoRepository.Photo> result = new ArrayList<>();
    try (Cursor c = getReadableDatabase().query("photos", COLS.split(","), null, null, null, null,
        "CASE WHEN taken>0 THEN taken WHEN added>0 THEN added WHEN created>0 THEN created ELSE modified*1000 END DESC, ordinal",
        Integer.toString(Math.max(1, Math.min(limit, 100))))) {
      while (c.moveToNext()) result.add(photo(c, 0));
    }
    return result;
  }

  Snapshot load(String folder, String camera) {
    Snapshot result = new Snapshot();
    List<String> where = new ArrayList<>(), args = new ArrayList<>();
    if (folder != null) {
      where.add("folder=?");
      args.add(folder);
    }
    if (!camera.equals("すべて")) {
      where.add("camera=?");
      args.add(camera);
    }
    Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
    try (Cursor c =
        getReadableDatabase()
            .query(
                "photos",
                (COLS + ",bundle").split(","),
                where.isEmpty() ? null : String.join(" AND ", where),
                args.toArray(new String[0]),
                null,
                null,
                "ordinal")) {
      while (c.moveToNext()) {
        int i = result.photos.size();
        result.photos.add(photo(c, 0));
        groups.computeIfAbsent(c.getInt(8), k -> new ArrayList<>()).add(i);
      }
    }
    result.bundles.addAll(groups.values());
    return result;
  }

  private static String signature(Context c) {
    String media;
    try {
      StringBuilder versions = new StringBuilder();
      for (String volume : new TreeSet<>(MediaStore.getExternalVolumeNames(c))) {
        versions
            .append(volume)
            .append(':')
            .append(MediaStore.getVersion(c, volume))
            .append(':')
            .append(MediaStore.getGeneration(c, volume))
            .append(';');
      }
      media = versions.toString();
    } catch (Exception e) {
      media = "unavailable";
    }
    long archiveRevision;
    try (ArchiveStore archive = new ArchiveStore(c)) {
      archiveRevision = archive.indexRevision();
    }
    return media
        + ":archive-v3:"
        + archiveRevision
        + ":"
        + c.getSharedPreferences("settings", 0).getInt("burstGapSeconds", 1)
        + ":"
        + c.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
        + ":"
        + c.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        + ":"
        + c.checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        + ":"
        + c.getContentResolver().getPersistedUriPermissions().toString();
  }

  static boolean rebuild(Context context, boolean force, BooleanSupplier cancelled)
      throws Exception {
    Context c = context.getApplicationContext();
    DiagnosticLog.init(c);
    long request = DiagnosticLog.id();
    DiagnosticLog.event("index.start", request, "force", force);
    try (GalleryIndex index = new GalleryIndex(c)) {
      String before = signature(c);
      try (Cursor state =
          index
              .getReadableDatabase()
              .rawQuery("SELECT signature,updated FROM state WHERE id=1", null)) {
        if (!force
            && state.moveToFirst()
            && before.equals(state.getString(0))
            && System.currentTimeMillis() - state.getLong(1) < 15 * 60 * 1000) return false;
      }
      DiagnosticLog.event("index.scan_start", request);
      List<PhotoRepository.Photo> photos = new PhotoRepository(c).scan("すべて");
      DiagnosticLog.event("index.scan_end", request, "count", photos.size());
      int gap = c.getSharedPreferences("settings", 0).getInt("burstGapSeconds", 1);
      List<BurstGroups.Entry> entries = new ArrayList<>();
      DiagnosticLog.event("index.metadata_start", request);
      try (ArchiveStore store = new ArchiveStore(c)) {
        Map<String, ArchiveStore.RawMetadata> metadata = store.metadataSnapshot();
        for (int i = 0; i < photos.size(); i++) {
          if (cancelled.getAsBoolean()) return false;
          PhotoRepository.Photo p = photos.get(i);
          ArchiveStore.RawMetadata meta = metadata.get(ArchiveStore.key(p));
          if (gap > 0 && meta == null)
            try {
              if ("archive".equals(p.uri.getScheme())) {
                org.json.JSONObject record = store.get(p.uri.getHost());
                meta =
                    new ArchiveStore.RawMetadata(
                        record.optString("model", ""), record.optLong("taken", 0));
              } else meta = store.metadata(p);
            } catch (Exception ignored) {
            }
          String model = meta == null ? "" : meta.model.trim();
          if (model.equals("JPEG")) model = "";
          entries.add(
              new BurstGroups.Entry(
                  i,
                  meta == null ? 0 : meta.taken,
                  model.isEmpty()
                      ? ""
                      : model + "|" + p.folder + "|" + (p.isJpeg() ? "jpeg" : "raw")));
        }
      }
      DiagnosticLog.event("index.metadata_end", request, "count", entries.size());
      if (cancelled.getAsBoolean()) return false;
      List<List<Integer>> groups = BurstGroups.group(entries, gap * 1000L);
      int[] membership = new int[photos.size()];
      for (int i = 0; i < groups.size(); i++) for (int item : groups.get(i)) membership[item] = i;
      DiagnosticLog.event("index.publish_start", request, "groups", groups.size());
      SQLiteDatabase db = index.getWritableDatabase();
      db.beginTransaction();
      try {
        db.delete("photos", null, null);
        db.delete("albums", null, null);
        Map<String, int[]> albums = new LinkedHashMap<>();
        try (SQLiteStatement insert =
            db.compileStatement("INSERT INTO photos VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
          for (int i = 0; i < photos.size(); i++) {
            if (cancelled.getAsBoolean()) return false;
            PhotoRepository.Photo p = photos.get(i);
            int[] album = albums.get(p.folder);
            if (album == null) {
              album = new int[] {0, i};
              albums.put(p.folder, album);
            }
            album[0]++;
            insert.bindLong(1, i);
            insert.bindLong(2, membership[i]);
            insert.bindString(3, p.cameraGroup());
            insert.bindString(4, p.uri.toString());
            insert.bindString(5, p.name);
            insert.bindString(6, p.folder);
            insert.bindLong(7, p.size);
            insert.bindLong(8, p.modified);
            insert.bindLong(9, p.taken);
            insert.bindLong(10, p.added);
            insert.bindLong(11, p.created);
            insert.executeInsert();
          }
        }
        for (Map.Entry<String, int[]> entry : albums.entrySet()) {
          ContentValues v = new ContentValues();
          v.put("folder", entry.getKey());
          v.put("count", entry.getValue()[0]);
          v.put("cover", entry.getValue()[1]);
          db.insertOrThrow("albums", null, v);
        }
        ContentValues state = new ContentValues();
        state.put("id", 1);
        state.put("signature", before);
        state.put("updated", System.currentTimeMillis());
        db.insertWithOnConflict("state", null, state, SQLiteDatabase.CONFLICT_REPLACE);
        db.setTransactionSuccessful();
      } finally {
        db.endTransaction();
      }
      DiagnosticLog.event("index.published", request, "count", photos.size());
      return true;
    } finally {
      DiagnosticLog.event("index.end", request);
    }
  }
}
