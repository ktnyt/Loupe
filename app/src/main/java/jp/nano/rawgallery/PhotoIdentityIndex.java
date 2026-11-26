package jp.nano.rawgallery;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps storage locations to the logical keys originally used by ranks, shares and tag judgments.
 */
final class PhotoIdentityIndex {
  private record Binding(PhotoMoveStore.Identity identity, String moveUri) {}

  /** A previous location is present only when the binding needs to be migrated. */
  private record Existing(Binding binding, String previousLocation) {}

  private static final class RankingPreparationRequired extends IOException {
    RankingPreparationRequired() {
      super("Ranking snapshot was not prepared");
    }
  }

  private final SQLiteDatabase db;
  private final boolean bulk;
  private final RankingStore.Snapshot rankings;
  private final Map<String, Map<String, Binding>> byLocation = new HashMap<>();
  private final Set<String> archiveKeys = new HashSet<>(), tagSources = new HashSet<>();

  static void create(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE local_photos (location TEXT NOT NULL, media_version TEXT NOT NULL,"
            + " archive_key TEXT NOT NULL, tag_source TEXT NOT NULL, sort_time INTEGER NOT NULL,"
            + " move_uri TEXT, PRIMARY KEY(location, media_version))");
    db.execSQL("CREATE INDEX local_photo_archive ON local_photos(archive_key)");
    db.execSQL("CREATE INDEX local_photo_tag ON local_photos(tag_source)");
  }

  /**
   * A tree grant changes how a file is opened. ExternalStorageProvider's document ID names its
   * physical path.
   */
  static String path(Uri uri, String folder, String name) {
    if ("media".equals(uri.getAuthority()))
      return storagePath(PhotoStorage.volumeOf(uri), folder + name);
    if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
      String id = DocumentsContract.getDocumentId(uri);
      int colon = id.indexOf(':');
      if (colon >= 0) {
        String volume = id.substring(0, colon);
        return storagePath(
            "primary".equalsIgnoreCase(volume) ? PhotoStorage.PRIMARY : volume,
            id.substring(colon + 1));
      }
    }
    return uri.toString();
  }

  private static String storagePath(String volume, String relative) {
    return new Uri.Builder()
        .scheme("storage")
        .authority(volume.toLowerCase(Locale.ROOT))
        .path("/" + relative)
        .build()
        .toString();
  }

  static String path(PhotoRepository.Photo photo) {
    return path(photo.uri, photo.folder, photo.name);
  }

  static String location(PhotoRepository.Photo photo) {
    return path(photo) + "\n" + photo.size + ":" + photo.modified;
  }

  static String mediaVersion(PhotoRepository.Photo photo) {
    return "media".equals(photo.uri.getAuthority()) ? TagStore.locationSource(photo) : "";
  }

  static void save(
      SQLiteDatabase db,
      PhotoRepository.Photo media,
      PhotoMoveStore.Identity identity,
      String moveUri) {
    write(db, location(media), mediaVersion(media), identity, moveUri);
  }

  private static void write(
      SQLiteDatabase db,
      String location,
      String version,
      PhotoMoveStore.Identity identity,
      String moveUri) {
    db.execSQL(
        "INSERT OR REPLACE INTO local_photos VALUES(?,?,?,?,?,?)",
        new Object[] {
          location,
          version,
          identity.archiveKey(),
          identity.tagSource(),
          identity.sortTime(),
          moveUri
        });
  }

  static void resolve(
      Context context,
      PhotoMoveStore store,
      Map<String, PhotoRepository.Photo> visible,
      Map<String, PhotoRepository.Photo> media)
      throws IOException {
    resolve(context, store, visible, media, null);
  }

  /** The explicit store is for isolated callers; production uses the shared default owner. */
  static void resolve(
      Context context,
      PhotoMoveStore store,
      Map<String, PhotoRepository.Photo> visible,
      Map<String, PhotoRepository.Photo> media,
      RankingStore rankingStore)
      throws IOException {
    if (visible.isEmpty()) return;
    SQLiteDatabase db = store.getWritableDatabase();
    RankingStore.Snapshot rankings =
        needsRanking(db, visible, media) ? awaitRankings(context, db, rankingStore) : null;
    try {
      resolveTransaction(context, db, visible, media, rankings);
    } catch (RankingPreparationRequired changedBinding) {
      // An existing binding can disappear after preflight. The first transaction has rolled back.
      resolveTransaction(context, db, visible, media, awaitRankings(context, db, rankingStore));
    }
  }

  private static RankingStore.Snapshot awaitRankings(
      Context context, SQLiteDatabase db, RankingStore rankingStore) throws IOException {
    if (db.inTransaction()) {
      throw new IOException("Ranking readiness must be acquired before the identity transaction");
    }
    RankingStore owner = rankingStore == null ? new RankingStore(context) : rankingStore;
    return owner.awaitReady();
  }

  private static void resolveTransaction(
      Context context,
      SQLiteDatabase db,
      Map<String, PhotoRepository.Photo> visible,
      Map<String, PhotoRepository.Photo> media,
      RankingStore.Snapshot rankings)
      throws IOException {
    // With simultaneous duplicates, the old path-only gallery kept the last reader.
    List<String> keys = new ArrayList<>(visible.keySet());
    Collections.reverse(keys);
    List<PhotoRepository.Photo> resolved = new ArrayList<>(keys.size());
    db.beginTransactionNonExclusive();
    try {
      PhotoIdentityIndex index = new PhotoIdentityIndex(db, keys.size() > 1, rankings);
      for (String key : keys) {
        PhotoRepository.Photo display = visible.get(key);
        PhotoRepository.Photo file = media.get(key);
        if (file == null || file.size != display.size) file = display;
        resolved.add(display.withIdentity(index.resolve(context, display, file)));
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    // Do not expose partially resolved descriptors if the transaction needs to be retried.
    for (int i = 0; i < keys.size(); i++) visible.put(keys.get(i), resolved.get(i));
  }

  private static boolean needsRanking(
      SQLiteDatabase db,
      Map<String, PhotoRepository.Photo> visible,
      Map<String, PhotoRepository.Photo> media)
      throws IOException {
    PhotoIdentityIndex probe = null;
    for (var entry : visible.entrySet()) {
      PhotoRepository.Photo display = entry.getValue(), file = media.get(entry.getKey());
      if (file == null || file == display || file.size != display.size) continue;
      if (probe == null) probe = new PhotoIdentityIndex(db, visible.size() > 1, null);
      if (probe.existing(display, file, location(file), mediaVersion(file)) == null
          && !ArchiveStore.key(display).equals(ArchiveStore.key(file))) return true;
    }
    return false;
  }

  private PhotoIdentityIndex(SQLiteDatabase db, boolean bulk, RankingStore.Snapshot rankings) {
    this.db = db;
    this.bulk = bulk;
    this.rankings = rankings;
    if (bulk) load(null, null);
  }

  private void load(String where, String[] args) {
    try (Cursor rows =
        db.query(
            "local_photos",
            new String[] {
              "location", "media_version", "archive_key", "tag_source", "sort_time", "move_uri"
            },
            where,
            args,
            null,
            null,
            null)) {
      while (rows.moveToNext()) {
        PhotoMoveStore.Identity identity =
            new PhotoMoveStore.Identity(rows.getString(2), rows.getString(3), rows.getLong(4));
        remember(rows.getString(0), rows.getString(1), new Binding(identity, rows.getString(5)));
      }
    }
  }

  private void remember(String location, String version, Binding binding) {
    byLocation.computeIfAbsent(location, unused -> new HashMap<>()).put(version, binding);
    archiveKeys.add(binding.identity().archiveKey());
    tagSources.add(binding.identity().tagSource());
  }

  private Existing existing(
      PhotoRepository.Photo display, PhotoRepository.Photo file, String location, String version) {
    String oldFile = TagStore.locationSource(file), oldDisplay = TagStore.locationSource(display);
    if (!bulk) load("location IN (?,?,?)", new String[] {location, oldFile, oldDisplay});
    Map<String, Binding> versions = byLocation.getOrDefault(location, Map.of());
    Binding exact = versions.get(version);
    if (exact != null) return new Existing(exact, null);
    // SAF alone has no DATE_ADDED or MediaStore ID. Only bridge a unique observed version.
    if (version.isEmpty() && versions.size() == 1)
      return new Existing(versions.values().iterator().next(), null);
    if (!version.isEmpty() && versions.size() == 1 && versions.containsKey(""))
      return new Existing(versions.get(""), location);

    // Version 1 of the move journal identified locations by the visible URI and its observed dates.
    String oldLocation = byLocation.containsKey(oldFile) ? oldFile : oldDisplay;
    Binding legacy = byLocation.getOrDefault(oldLocation, Map.of()).get("");
    return legacy == null ? null : new Existing(legacy, oldLocation);
  }

  private PhotoMoveStore.Identity resolve(
      Context context, PhotoRepository.Photo display, PhotoRepository.Photo file)
      throws IOException {
    String location = location(file), version = mediaVersion(file);
    Existing match = existing(display, file, location, version);
    Binding binding;
    if (match == null) {
      binding =
          new Binding(initialIdentity(context, display, file, location + "\n" + version), null);
    } else {
      binding = match.binding();
      String oldLocation = match.previousLocation();
      if (oldLocation == null) return binding.identity();
      db.delete("local_photos", "location=? AND media_version=''", new String[] {oldLocation});
      byLocation.get(oldLocation).remove("");
    }
    write(db, location, version, binding.identity(), binding.moveUri());
    remember(location, version, binding);
    return binding.identity();
  }

  private PhotoMoveStore.Identity initialIdentity(
      Context context,
      PhotoRepository.Photo display,
      PhotoRepository.Photo file,
      String versionedLocation)
      throws IOException {
    String archive = ArchiveStore.key(display), tag = TagStore.source(display);
    if (display != file) {
      // A persisted grant may already exist on the first scan after upgrading from the aggregate
      // gallery.
      String mediaTag = TagStore.source(file), mediaArchive = ArchiveStore.key(file);
      boolean differentKeys = !archive.equals(mediaArchive);
      if (differentKeys && rankings == null) throw new RankingPreparationRequired();
      try (TagStore tags = new TagStore(context)) {
        if (tags.photoId(display, false) == null && tags.photoId(file, false) != null)
          tag = mediaTag;
      }
      if (differentKeys) {
        try (ShareHistoryStore shares = new ShareHistoryStore(context)) {
          if (!rankings.singleBest(archive)
              && shares.history(archive).isEmpty()
              && (rankings.singleBest(mediaArchive) || !shares.history(mediaArchive).isEmpty()))
            archive = mediaArchive;
        }
      }
    }
    if (claimed("archive_key", archiveKeys, archive))
      archive = PhotoRepository.digest("location\n" + versionedLocation);
    if (claimed("tag_source", tagSources, tag)) tag = "location\n" + versionedLocation;
    return new PhotoMoveStore.Identity(archive, tag);
  }

  private boolean claimed(String column, Set<String> known, String value) {
    if (bulk) return known.contains(value);
    try (Cursor row =
        db.query(
            "local_photos",
            new String[] {column},
            column + "=?",
            new String[] {value},
            null,
            null,
            null,
            "1")) {
      return row.moveToFirst();
    }
  }
}
