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
import java.util.Locale;
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
  private static final int MAX_SCANS = 3;
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

  enum Rejection {
    ARCHIVE_OPEN, ARCHIVE_READ, ARCHIVE_FILE_CHANGED, ARCHIVE_REVISION_INVALID,
    ARCHIVE_REVISION_CHURN, ARCHIVE_JSON_INVALID, ARCHIVE_ROW_LIMIT,
    RECEIPT_MISSING, RECEIPT_DUPLICATE, RECEIPT_UNVERIFIED, RECEIPT_INVALID, RECEIPT_METADATA,
    HISTORY_METADATA, EPOCH, GENERATION_RANGE, PROOF_BUDGET, ARCHIVE_KEY, SAVED_PROOF;

    String code() { return name().toLowerCase(Locale.ROOT); }
  }

  record IndexedReceipt(boolean verified, ArchiveRecord record) {}
  record ReadResult(Map<String, List<IndexedReceipt>> records, Rejection rejection, int scanAttempts) {}
  record Revalidation(Rejection rejection, int scanAttempts) {}

  private record ArchiveStamp(long device, long inode, long revision) {}
  private record Match(PhotoMoveStore.ArchiveProof proof, Rejection rejection) {
    static Match reject(Rejection reason) { return new Match(null, reason); }
  }

  private static final class RejectedRead extends Exception {
    final Rejection reason;
    RejectedRead(Rejection reason) { this.reason = reason; }
  }

  /** Called after a scan has closed its cursor and before the ending revision is checked. */
  interface ScanObserver {
    default void beforeScan() {}
    void afterScan() throws IOException;
  }

  /** Reuse a bounded scan only while this same file has an unchanged photo-record revision. */
  static final class Records {
    private final String path;
    private final ScanObserver observer;
    private final Budget budget = new Budget(3_000_000);
    private ArchiveStamp stamp;
    private Map<String, List<IndexedReceipt>> snapshot = Map.of();

    Records(Context context) { this(context, () -> {}); }

    Records(Context context, ScanObserver observer) {
      path = context.getDatabasePath(E2Settings.DATABASE).getPath();
      this.observer = observer;
    }

    ReadResult read(BooleanSupplier stopped) throws IOException {
      check(stopped);
      android.system.StructStat file;
      SQLiteDatabase db;
      try {
        file = Os.stat(path);
        // Android's default corruption handler can delete the file even for a read-only open.
        db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY, ignored -> {});
      } catch (ErrnoException | RuntimeException unavailable) {
        return rejected(Rejection.ARCHIVE_OPEN, 0);
      }
      int scanned = 0;
      try (db) {
        while (scanned < MAX_SCANS) {
          check(stopped);
          ArchiveStamp current = stamp(db, file.st_dev, file.st_ino);
          if (scanned == 0 && current.equals(stamp)) {
            check(stopped);
            return new ReadResult(snapshot, null, 0);
          }
          scanned++;
          observer.beforeScan();
          Map<String, List<IndexedReceipt>> refreshed = scan(db, stopped);
          observer.afterScan();
          check(stopped);
          if (!current.equals(stamp(db, file.st_dev, file.st_ino))) continue;
          snapshot = refreshed;
          stamp = current;
          return new ReadResult(refreshed, null, scanned);
        }
        return rejected(Rejection.ARCHIVE_REVISION_CHURN, scanned);
      } catch (RejectedRead failure) {
        return rejected(failure.reason, scanned);
      } catch (JSONException invalid) {
        return rejected(Rejection.ARCHIVE_JSON_INVALID, scanned);
      } catch (RuntimeException unavailable) {
        return rejected(Rejection.ARCHIVE_READ, scanned);
      }
    }

    private static ReadResult rejected(Rejection reason, int scanAttempts) {
      return new ReadResult(Map.of(), reason, scanAttempts);
    }

    private ArchiveStamp stamp(SQLiteDatabase db, long device, long inode) throws RejectedRead {
      try (Cursor row = db.rawQuery("SELECT value FROM index_revision WHERE id=1", null)) {
        if (!row.moveToFirst() || row.getType(0) != Cursor.FIELD_TYPE_INTEGER || row.getLong(0) < 0)
          throw new RejectedRead(Rejection.ARCHIVE_REVISION_INVALID);
        long revision = row.getLong(0);
        try {
          var current = Os.stat(path);
          if (device != current.st_dev || inode != current.st_ino)
            throw new RejectedRead(Rejection.ARCHIVE_FILE_CHANGED);
        } catch (ErrnoException unavailable) {
          throw new RejectedRead(Rejection.ARCHIVE_FILE_CHANGED);
        }
        return new ArchiveStamp(device, inode, revision);
      }
    }

    private static Map<String, List<IndexedReceipt>> scan(SQLiteDatabase db, BooleanSupplier stopped)
        throws IOException, JSONException, RejectedRead {
      Map<String, List<IndexedReceipt>> byUri = new HashMap<>();
      try (Cursor rows = db.rawQuery("SELECT key,state,payload FROM photos LIMIT " + (MAX_RECORDS + 1), null)) {
        int count = 0;
        while (rows.moveToNext()) {
          check(stopped);
          if (++count > MAX_RECORDS) throw new RejectedRead(Rejection.ARCHIVE_ROW_LIMIT);
          JSONObject value = new JSONObject(rows.getString(2));
          String uri = value.optString("uri");
          // Every row for the source URI participates in uniqueness; only verified evidence can pass.
          boolean verified = "verified".equals(rows.getString(1));
          var receipt = new IndexedReceipt(verified, verified ? parse(rows.getString(0), value) : null);
          byUri.computeIfAbsent(uri, ignored -> new ArrayList<>()).add(receipt);
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
      long modifiedGeneration, HistoryDb.MoveEvidence history, List<IndexedReceipt> records, Budget budget,
      BooleanSupplier stopped) throws IOException {
    return matchResult(photo, epoch, addedGeneration, modifiedGeneration, history, records, budget, stopped).proof();
  }

  private static Match matchResult(PhotoRepository.Photo photo, String epoch, long addedGeneration,
      long modifiedGeneration, HistoryDb.MoveEvidence history, List<IndexedReceipt> records, Budget budget,
      BooleanSupplier stopped) throws IOException {
    check(stopped);
    if (epoch == null) return Match.reject(Rejection.EPOCH);
    if (records == null || records.isEmpty()) return Match.reject(Rejection.RECEIPT_MISSING);
    if (records.size() != 1) return Match.reject(Rejection.RECEIPT_DUPLICATE);
    var indexed = records.get(0);
    if (!indexed.verified()) return Match.reject(Rejection.RECEIPT_UNVERIFIED);
    ArchiveRecord receipt = indexed.record();
    if (receipt == null) return Match.reject(Rejection.RECEIPT_INVALID);
    if (!receiptMatches(receipt, photo, history.getImportedAt())) return Match.reject(Rejection.RECEIPT_METADATA);
    if (!historyMatches(photo, history)) return Match.reject(Rejection.HISTORY_METADATA);
    if (!validRange(addedGeneration, modifiedGeneration)) return Match.reject(Rejection.GENERATION_RANGE);
    if (!budget.spend((int) (modifiedGeneration - addedGeneration + 1))) return Match.reject(Rejection.PROOF_BUDGET);
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
    if (matches != 1) return Match.reject(Rejection.ARCHIVE_KEY);
    return new Match(new PhotoMoveStore.ArchiveProof(receipt.key(), receipt.sha(), match,
        history.getId(), epoch, historyFingerprint(history)), null);
  }

  static Revalidation revalidate(Context context, Records archive, PhotoMoveStore.ArchiveProof proof,
      PhotoRepository.Photo photo, long addedGeneration, long modifiedGeneration,
      HistoryDb.MoveEvidence history, BooleanSupplier stopped) throws IOException {
    String currentEpoch = epoch(context);
    if (!proof.epoch().equals(currentEpoch)) return new Revalidation(Rejection.EPOCH, 0);
    ReadResult read = archive.read(stopped);
    if (read.rejection() != null) return new Revalidation(read.rejection(), read.scanAttempts());
    var match = matchResult(photo, currentEpoch, addedGeneration, modifiedGeneration, history,
        read.records().get(photo.uri.toString()), archive.budget, stopped);
    Rejection reason = match.rejection();
    if (reason == null && !proof.equals(match.proof())) reason = Rejection.SAVED_PROOF;
    return new Revalidation(reason, read.scanAttempts());
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
