package jp.nano.rawgallery;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import jp.nano.rawgallery.camera.HistoryDb;
import jp.nano.rawgallery.camera.MediaSaver;
import org.json.JSONException;
import org.json.JSONObject;

/** Matches the original auto-archive identity without changing records or granting cleanup authority. */
final class LegacyArchiveMoveProof {
  static final int MAX_GENERATION_DELTA = 256;
  private static final int MAX_RECORDS = 100_000;
  private static final Pattern KEY = Pattern.compile("[0-9a-f]{32}");
  private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

  record ArchiveRecord(String key, String bucket, String sha, String uri, String name,
      String folder, long size, long modified, long added, long verifiedAt) {}

  static final class Budget {
    private int remaining;
    Budget(int attempts) { remaining = attempts; }

    boolean spend(int attempts) {
      if (attempts > remaining) return false;
      remaining -= attempts;
      return true;
    }
  }

  private LegacyArchiveMoveProof() {}

  static String epoch(Context context) {
    return epoch(context, PhotoStorage.PRIMARY);
  }

  static String epoch(Context context, String volume) {
    try {
      if (!E2Settings.hasPhotoAccess(context)) return null;
      String value = MediaStore.getVersion(context.getApplicationContext(), volume);
      return value == null || value.isEmpty() ? null : value;
    } catch (RuntimeException unavailable) {
      return null;
    }
  }

  private record ArchiveStamp(long device, long inode, long revision) {}

  /** Reuse a bounded scan only while this same file has an unchanged photo-record revision. */
  static final class Records {
    private final String path;
    private final Budget budget = new Budget(3_000_000);
    private ArchiveStamp stamp;
    private Map<String, List<ArchiveRecord>> snapshot = Map.of();

    Records(Context context) { path = context.getDatabasePath(E2Settings.DATABASE).getPath(); }

    Map<String, List<ArchiveRecord>> read(BooleanSupplier stopped) throws IOException {
      check(stopped);
      try {
        var file = Os.stat(path);
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)) {
          ArchiveStamp current = stamp(db, file.st_dev, file.st_ino);
          if (current.equals(stamp)) return snapshot;
          Map<String, List<ArchiveRecord>> refreshed = scan(db, stopped);
          if (!current.equals(stamp(db, file.st_dev, file.st_ino))) return Map.of();
          snapshot = refreshed;
          stamp = current;
          return refreshed;
        }
      } catch (JSONException | ErrnoException | RuntimeException unavailable) {
        return Map.of();
      }
    }

    private ArchiveStamp stamp(SQLiteDatabase db, long device, long inode) throws ErrnoException {
      try (Cursor row = db.rawQuery("SELECT value FROM index_revision WHERE id=1", null)) {
        if (!row.moveToFirst() || row.getType(0) != Cursor.FIELD_TYPE_INTEGER || row.getLong(0) < 0)
          throw new IllegalStateException("The archive revision is unavailable");
        long revision = row.getLong(0);
        var current = Os.stat(path);
        if (device != current.st_dev || inode != current.st_ino)
          throw new IllegalStateException("The archive file changed");
        return new ArchiveStamp(device, inode, revision);
      }
    }

    private static Map<String, List<ArchiveRecord>> scan(SQLiteDatabase db, BooleanSupplier stopped)
        throws IOException, JSONException {
      Map<String, List<ArchiveRecord>> byUri = new HashMap<>();
      try (Cursor rows = db.rawQuery("SELECT key,state,payload FROM photos", null)) {
        int count = 0;
        while (rows.moveToNext()) {
          check(stopped);
          if (++count > MAX_RECORDS) return Map.of();
          JSONObject value = new JSONObject(rows.getString(2));
          String uri = value.optString("uri");
          // Every row for the source URI participates in uniqueness; only verified evidence can pass.
          ArchiveRecord record = "verified".equals(rows.getString(1)) ? parse(rows.getString(0), value) : null;
          byUri.computeIfAbsent(uri, ignored -> new ArrayList<>()).add(record);
        }
      }
      return byUri;
    }
  }

  static ArchiveRecord parse(String dbKey, JSONObject value) {
    try {
      String bucket = string(value, "bucket");
      String sha = string(value, "sha");
      if (dbKey == null || !KEY.matcher(dbKey).matches() || !dbKey.equals(string(value, "key"))
          || !SHA.matcher(sha).matches() || !"idrive-e2".equals(string(value, "provider"))
          || bucket.isBlank() || bucket.indexOf('\n') >= 0) return null;
      E2Archive.checkedVersion(string(value, "version"));
      return new ArchiveRecord(dbKey, bucket, sha, string(value, "uri"), string(value, "name"),
          string(value, "folder"), number(value, "size"), number(value, "modified"),
          number(value, "added"), number(value, "verifiedAt"));
    } catch (IOException | JSONException unavailable) {
      return null;
    }
  }

  private static String string(JSONObject value, String name) throws JSONException {
    if (value.get(name) instanceof String result) return result;
    throw new JSONException("Archive string is missing");
  }

  private static long number(JSONObject value, String name) throws JSONException {
    Object field = value.get(name);
    if (field instanceof Long || field instanceof Integer) return ((Number) field).longValue();
    throw new JSONException("Archive integer is missing");
  }

  static PhotoMoveStore.ArchiveProof match(PhotoRepository.Photo photo, String epoch, long addedGeneration,
      long modifiedGeneration, HistoryDb.MoveEvidence history, List<ArchiveRecord> records, Budget budget,
      BooleanSupplier stopped) throws IOException {
    check(stopped);
    if (epoch == null || records == null || records.size() != 1
        || !historyMatches(photo, history)
        || !receiptMatches(records.get(0), photo, history.getImportedAt())
        || !validRange(addedGeneration, modifiedGeneration)
        || !budget.spend((int) (modifiedGeneration - addedGeneration + 1))) return null;
    ArchiveRecord receipt = records.get(0);
    String prefix = keyPrefix(photo, receipt.bucket(), epoch);
    long match = -1;
    int matches = 0;
    for (long offset = 0; offset <= modifiedGeneration - addedGeneration; offset++) {
      check(stopped);
      long generation = addedGeneration + offset;
      if (receipt.key().equals(key(prefix, generation))) {
        match = generation;
        matches++;
      }
    }
    return matches == 1 ? new PhotoMoveStore.ArchiveProof(receipt.key(), receipt.sha(), match,
        history.getId(), epoch, historyFingerprint(history)) : null;
  }

  static boolean revalidate(Context context, Records archive, PhotoMoveStore.ArchiveProof proof,
      PhotoRepository.Photo photo, long addedGeneration, long modifiedGeneration,
      HistoryDb.MoveEvidence history, BooleanSupplier stopped) throws IOException {
    String currentEpoch = epoch(context);
    if (!proof.epoch().equals(currentEpoch)) return false;
    var records = archive.read(stopped).get(photo.uri.toString());
    var current = match(photo, currentEpoch, addedGeneration, modifiedGeneration, history, records,
        archive.budget, stopped);
    return proof.equals(current);
  }

  private static boolean validRange(long added, long modified) {
    return added > 0 && modified >= added && modified - added <= MAX_GENERATION_DELTA;
  }

  private static boolean historyMatches(PhotoRepository.Photo photo, HistoryDb.MoveEvidence history) {
    long shot = history.getShot();
    long imported = history.getImportedAt();
    String name = history.getName();
    long addedMillis = photo.added;
    if (shot <= 0 || shot % 2 != 0 || imported <= 1 || imported > Long.MAX_VALUE / 1000
        || (photo.taken != 0 && photo.taken != -1 && (photo.taken < 0 || photo.taken / 1000 != shot + 1))
        || name == null || name.lastIndexOf('.') <= 0 || photo.size != history.getBytes()
        || photo.modified != imported || (addedMillis != imported * 1000 && addedMillis != (imported - 1) * 1000))
      return false;
    return photo.name.equalsIgnoreCase(name) || photo.name.equals(MediaSaver.INSTANCE.collisionName(name, shot));
  }

  private static boolean receiptMatches(ArchiveRecord receipt, PhotoRepository.Photo photo, long importedAt) {
    return receipt != null && receipt.key() != null && KEY.matcher(receipt.key()).matches()
        && receipt.sha() != null && SHA.matcher(receipt.sha()).matches()
        && photo.uri.toString().equals(receipt.uri()) && photo.name.equals(receipt.name())
        && photo.folder.equals(receipt.folder()) && receipt.size() == photo.size
        && receipt.modified() == photo.modified && receipt.added() == photo.added
        && receipt.verifiedAt() >= importedAt * 1000;
  }

  static String historyFingerprint(HistoryDb.MoveEvidence history) throws IOException {
    return PhotoRepository.digest(field(history.getCameraModel()) + field(history.getCameraSerial())
        + field(history.getName()) + history.getBytes() + "\n" + history.getShot() + "\n" + history.getImportedAt());
  }

  private static String field(String value) { return value.length() + ":" + value; }

  private static String keyPrefix(PhotoRepository.Photo photo, String bucket, String epoch) throws IOException {
    return "e2-auto\n" + bucket + "\n" + photo.uri + "\n" + ArchiveStore.key(photo) + "\n"
        + PhotoStorage.PRIMARY + "\n" + epoch + "\n";
  }

  private static String key(String prefix, long generation) throws IOException {
    return PhotoRepository.digest(prefix + generation).substring(0, 32);
  }

  static void check(BooleanSupplier stopped) throws InterruptedIOException {
    if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("写真の移動を中断しました。");
  }
}
