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
  private static final String COLS = "uri,name,folder,size,modified,taken,added,created,archive_key,tag_source,sort_time";
  private static final String BURST_GAP_PREFIX = ":burst-gap-ms:";
  private static final String BURST_RULE_MARKER = ":burst-number-v1:";
  private static final int BURST_GAP_SIGNATURE_LENGTH = BURST_GAP_PREFIX.length() + 10 + BURST_RULE_MARKER.length();

  GalleryIndex(Context c) {
    super(c, "gallery-index.db", null, 2);
    setWriteAheadLoggingEnabled(true);
  }

  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE photos (ordinal INTEGER PRIMARY KEY, bundle INTEGER, camera TEXT, uri"
            + " TEXT,name TEXT,folder TEXT,size INTEGER,modified INTEGER,taken INTEGER,added"
            + " INTEGER,created INTEGER,archive_key TEXT,tag_source TEXT,sort_time INTEGER)");
    db.execSQL("CREATE INDEX photo_folder ON photos(folder,ordinal)");
    db.execSQL("CREATE TABLE albums (folder TEXT PRIMARY KEY,count INTEGER,cover INTEGER)");
    db.execSQL("CREATE TABLE state (id INTEGER PRIMARY KEY,signature TEXT,updated INTEGER)");
  }

  public void onUpgrade(SQLiteDatabase db, int old, int next) {
    if (old < 2) {
      db.execSQL("ALTER TABLE photos ADD COLUMN archive_key TEXT");
      db.execSQL("ALTER TABLE photos ADD COLUMN tag_source TEXT");
      db.execSQL("ALTER TABLE photos ADD COLUMN sort_time INTEGER");
    }
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
    int burstGapMillis = -1;
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
        c.getLong(o + 7),
        c.isNull(o + 8) ? null : new PhotoMoveStore.Identity(c.getString(o + 8), c.getString(o + 9), c.getLong(o + 10)));
  }

  List<Album> albums() {
    List<Album> result = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT"
                    + " a.folder,a.count,p.uri,p.name,p.folder,p.size,p.modified,p.taken,p.added,p.created,p.archive_key,p.tag_source,p.sort_time"
                    + " FROM albums a JOIN photos p ON p.ordinal=a.cover ORDER BY a.cover",
                null)) {
      while (c.moveToNext()) result.add(new Album(c.getString(0), c.getInt(1), photo(c, 2)));
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
    // Read the grouping marker from the same statement as the photo memberships.
    String query = "SELECT " + COLS + ",bundle,(SELECT substr(signature,1," + BURST_GAP_SIGNATURE_LENGTH
        + ") FROM state WHERE id=1) FROM photos"
        + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where)) + " ORDER BY ordinal";
    try (Cursor c = getReadableDatabase().rawQuery(query, args.toArray(new String[0]))) {
      String firstSignature = null;
      while (c.moveToNext()) {
        int i = result.photos.size();
        String marker = c.getString(12);
        if (i == 0) {
          firstSignature = marker;
          result.burstGapMillis = burstGapFromSignature(marker);
        } else if (result.burstGapMillis >= 0 && !Objects.equals(firstSignature, marker)
            && result.burstGapMillis != burstGapFromSignature(marker)) {
          // A cursor window refilled after publication may span two grouping settings.
          result.burstGapMillis = -1;
        }
        result.photos.add(photo(c, 0));
        groups.computeIfAbsent(c.getInt(11), k -> new ArrayList<>()).add(i);
      }
    }
    result.bundles.addAll(groups.values());
    return result;
  }

  private static int burstGapFromSignature(String signature) {
    if (signature == null || !signature.startsWith(BURST_GAP_PREFIX)) return -1;
    int end = signature.indexOf(':', BURST_GAP_PREFIX.length());
    if (end < 0 || !signature.startsWith(BURST_RULE_MARKER, end)) return -1;
    try {
      return Integer.parseInt(signature.substring(BURST_GAP_PREFIX.length(), end));
    } catch (NumberFormatException error) {
      return -1;
    }
  }

  private static String signature(Context c, int burstGapMillis) {
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
    long e2Revision;
    try (ArchiveStore archive = E2Settings.openRecords(c)) {
      e2Revision = archive.indexRevision();
    }
    long moveRevision;
    try (PhotoMoveStore store = new PhotoMoveStore(c)) {
      moveRevision = store.revision();
    }
    return BURST_GAP_PREFIX + burstGapMillis + BURST_RULE_MARKER + media + ":move-v1:" + moveRevision
        + ":capture-order-v1:video-v1:archive-v3:"
        + archiveRevision
        + ":" + e2Revision
        + ":"
        + c.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO)
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
      int burstGapMillis = BurstGapSettings.readMillis(c.getSharedPreferences("settings", 0));
      String before = signature(c, burstGapMillis);
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
      DiagnosticLog.event("index.metadata_start", request);
      Map<String, ArchiveStore.RawMetadata> metadata;
      int probes = 0, numbered = 0, failures = 0;
      long probeMillis = 0;
      try (ArchiveStore store = new ArchiveStore(c)) {
        metadata = store.metadataSnapshot();
        for (int i = 0; i < photos.size(); i++) {
          if (cancelled.getAsBoolean()) return false;
          PhotoRepository.Photo p = photos.get(i);
          String key = ArchiveStore.key(p);
          ArchiveStore.RawMetadata meta = metadata.get(key);
          // Capture dates also determine gallery order when burst grouping is disabled.
          if (meta == null)
            try {
              if (p.isArchived()) {
                org.json.JSONObject record = E2Cleanup.archived(p.uri)
                    ? E2Cleanup.localRecord(c, p.uri) : store.get(p.uri.getHost());
                meta =
                    new ArchiveStore.RawMetadata(
                        record.optString("model", ""), record.optLong("taken", 0));
              } else meta = store.metadata(p);
            } catch (Exception ignored) {
            }
          if (meta != null && meta.burstFrame == null && burstGapMillis > 0
              && meta.taken > 0 && !burstCamera(meta).isEmpty() && ArchiveStore.canProbeBurstFrame(p)) {
            probes++;
            long started = android.os.SystemClock.elapsedRealtime();
            try {
              meta = store.probeBurstFrame(p, key, meta);
              if (meta.burstFrame == null) failures++;
              else if (meta.burstFrame.number() > 0) numbered++;
            } catch (java.io.IOException | SecurityException ignored) {
              failures++;
            } finally {
              probeMillis += android.os.SystemClock.elapsedRealtime() - started;
            }
          }
          if (meta != null) metadata.put(key, meta);
        }
      }
      DiagnosticLog.event("index.burst_probe", request, "count", probes, "numbered", numbered,
          "failed", failures, "elapsedMillis", probeMillis);
      orderByCapture(photos, metadata);
      List<BurstGroups.Entry> entries = new ArrayList<>();
      for (int i = 0; i < photos.size(); i++) {
        if (cancelled.getAsBoolean()) return false;
        PhotoRepository.Photo p = photos.get(i);
        ArchiveStore.RawMetadata meta = metadata.get(ArchiveStore.key(p));
        String model = burstCamera(meta);
        RawPreview.BurstFrame frame = burstFrame(p, meta);
        entries.add(
            new BurstGroups.Entry(
                i,
                meta == null ? 0 : meta.taken,
                p.isVideo() || model.isEmpty()
                    ? ""
                    : burstStream(p, model),
                p.name, frame.mode(), frame.number()));
      }
      DiagnosticLog.event("index.metadata_end", request, "count", entries.size());
      if (cancelled.getAsBoolean()) return false;
      List<List<Integer>> groups = BurstGroups.group(entries, burstGapMillis);
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
            db.compileStatement("INSERT INTO photos VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
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
            if (p.movedIdentity == null) {
              insert.bindNull(12);
              insert.bindNull(13);
              insert.bindNull(14);
            } else {
              insert.bindString(12, p.movedIdentity.archiveKey());
              insert.bindString(13, p.movedIdentity.tagSource());
              insert.bindLong(14, p.movedIdentity.sortTime());
            }
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

  static RawPreview.BurstFrame burstFrame(PhotoRepository.Photo photo, ArchiveStore.RawMetadata metadata) {
    // An E2 object ID does not prove which moved local identity owns the cached frame.
    if (E2Cleanup.archived(photo.uri) || metadata == null || metadata.burstFrame == null)
      return RawPreview.BurstFrame.NONE;
    return metadata.burstFrame;
  }

  static String burstStream(PhotoRepository.Photo photo, String model) {
    Uri location = Uri.parse(PhotoIdentityIndex.path(photo));
    String folder = photo.folder;
    if ("storage".equals(location.getScheme())) {
      // Match MediaStore and the system document provider for the same physical folder.
      String path = location.getEncodedPath();
      folder = path.substring(0, path.lastIndexOf('/') + 1);
    }
    return model.length() + ":" + model + location.getScheme() + "://"
        + Objects.toString(location.getAuthority(), "") + ":" + folder + "|" + photo.isJpeg();
  }

  private static String burstCamera(ArchiveStore.RawMetadata metadata) {
    if (metadata == null) return "";
    String model = metadata.model.trim();
    return model.equals("JPEG") || model.equals("RAW") || model.equals("不明なカメラ") ? "" : model;
  }

  /** Browsing dates use EXIF when available without rewriting source media or backup records. */
  static void orderByCapture(
      List<PhotoRepository.Photo> photos, Map<String, ArchiveStore.RawMetadata> metadata)
      throws java.io.IOException {
    for (int i = 0; i < photos.size(); i++) {
      PhotoRepository.Photo photo = photos.get(i);
      ArchiveStore.RawMetadata capture = metadata.get(ArchiveStore.key(photo));
      if (capture != null && capture.taken > 0 && capture.taken != photo.taken)
        photos.set(i, new PhotoRepository.Photo(photo.uri, photo.name, photo.folder, photo.size,
            photo.modified, capture.taken, photo.added, photo.created, photo.movedIdentity));
    }
    photos.sort(Comparator.comparingLong(PhotoRepository.Photo::sortTime)
        .reversed().thenComparing(p -> p.name));
  }
}
