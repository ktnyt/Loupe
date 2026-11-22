package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import jp.nano.rawgallery.PhotoStorageChecks.Fixture;
import jp.nano.rawgallery.camera.CameraImportGate;
import jp.nano.rawgallery.camera.CameraImportLedger;
import jp.nano.rawgallery.camera.HistoryDb;
import jp.nano.rawgallery.camera.ImportedKey;
import jp.nano.rawgallery.camera.MediaSaver;
import org.json.JSONObject;

import static android.provider.MediaStore.MediaColumns.*;
import static jp.nano.rawgallery.PhotoStorageChecks.*;

/** Checks local archive evidence without connecting to E2 or using personal photos. */
final class LegacyArchiveMoveChecks {
  private static final String PRIMARY = PhotoStorage.PRIMARY;
  private static final String BUCKET = "loupe-synthetic-archive";
  private static final String MODEL = "Synthetic archive camera";
  private static final long SHOT = 1784712024L;
  private static final long IMPORTED = SHOT + 86400;
  private static final long GA = 1200;
  private static final String EPOCH = "synthetic MediaStore epoch";
  private static final String NAME = "LEGACY.JPG";
  private static final String FOLDER = "Pictures/CameraSync/Synthetic/";
  private static final String SHA = "1".repeat(64);
  private static final Uri FILES = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);

  private LegacyArchiveMoveChecks() {}

  static String run(Instrumentation test, String secondaryArg) throws Exception {
    Context base = test.getTargetContext();
    String secondary = isolatedVolume(base, secondaryArg);
    checkMetadataMatcher();
    checkReadOnlyPreflight(base, secondary);
    for (String state : List.of("verified", "pending", "released")) checkLateArchiveConflict(base, secondary, state);
    checkPublishedTargetDrift(base, secondary, false);
    checkPublishedTargetDrift(base, secondary, true);
    checkTargetPinDrift(base, secondary, PhotoMove.Boundary.ATTACHED);
    checkTargetPinDrift(base, secondary, PhotoMove.Boundary.PUBLISHED);
    checkTargetChangesWhileOpening(base, secondary, true);
    checkTargetChangesWhileOpening(base, secondary, false);
    checkPublishedRevisionDriftBeforeDelete(base, secondary);
    checkLostHistory(base, secondary);
    for (var boundary : List.of(PhotoMove.Boundary.HISTORY_UPDATED, PhotoMove.Boundary.REMAPPED)) {
      checkLostHistoryAfterRelocation(base, secondary, boundary);
    }
    for (var boundary : List.of(PhotoMove.Boundary.PUBLISHED, PhotoMove.Boundary.HISTORY_UPDATED,
        PhotoMove.Boundary.REMAPPED, PhotoMove.Boundary.DELETED)) {
      checkProofRecovery(base, secondary, boundary);
    }
    checkProofRollback(base, secondary);
    checkDeletedSourceWithResetHistory(base, secondary, false);
    checkDeletedSourceWithResetHistory(base, secondary, true);
    checkStaleSourceWithResetHistory(base, secondary);
    checkLockedAbandon(base, secondary);
    checkRoundTrip(base, secondary);
    checkVersionTwoResume(base, secondary);
    checkVersionThreeOrdinaryResume(base, secondary);
    for (var migration : VersionThreeHistorical.values()) checkVersionThreeHistorical(base, secondary, migration);
    return "PASS legacy archive move: strict metadata and bounded epoch/generation proof, read-only preflight, "
        + "selected history, v2/v3-to-v4 migration, pinned targets, proof interruption, rollback and reverse move";
  }

  private static String isolatedVolume(Context base, String secondaryArg) throws IOException {
    require("jp.nano.rawgallery.sd162".equals(base.getPackageName()), "Archive checks require the isolated APK");
    require("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE), "Archive checks require an emulator");
    require(E2Settings.hasPhotoAccess(base), "Grant the isolated APK full original-photo access");
    String secondary = secondaryArg == null ? secondaryVolume(base) : secondaryArg.toLowerCase(Locale.ROOT);
    require(!PRIMARY.equals(secondary), "Archive checks require a distinct destination volume");
    PhotoStorage.requireWritable(base, secondary);
    return secondary;
  }

  private static void checkMetadataMatcher() throws Exception {
    var photo = photo(NAME, FOLDER, 42, IMPORTED, (SHOT + 1) * 1000, IMPORTED * 1000, 811);
    var history = history(photo, NAME, 42, SHOT, IMPORTED);
    var receipt = record(photo, PRIMARY, EPOCH, GA);
    var proof = match(photo, EPOCH, GA, GA + 256, history, List.of(receipt), 257);
    require(proof != null && proof.historicalGeneration() == GA && proof.historyId() == history.getId()
        && proof.epoch().equals(EPOCH) && proof.sha().equals(SHA), "Accept one old key after scanning the complete inclusive interval");
    require(match(photo, EPOCH, GA, GA + 256, history, List.of(receipt), 256) == null
        && match(photo, EPOCH, GA, GA + 257, history, List.of(receipt), 300) == null,
        "A matching first generation cannot bypass the full range or its budget");
    var budget = new LegacyArchiveMoveProof.Budget(257);
    require(LegacyArchiveMoveProof.match(photo, EPOCH, GA, GA + 256, history, List.of(receipt), budget, () -> false) != null
        && LegacyArchiveMoveProof.match(photo, EPOCH, GA, GA, history, List.of(receipt), budget, () -> false) == null,
        "The finite match budget is shared by candidates");
    require(match(photo, "wrong app-UID epoch", GA, GA + 10, history, List.of(receipt), 100) == null
        && match(photo, null, GA, GA + 10, history, List.of(receipt), 100) == null
        && match(photo, EPOCH, GA + 1, GA + 10, history, List.of(receipt), 100) == null
        && match(photo, EPOCH, GA, GA + 10, history, List.of(record(photo, "1023-4671", EPOCH, GA)), 100) == null,
        "Wrong UID epoch, missing epoch, non-primary key and pre-creation generation never qualify");
    var recreated = photo(NAME, FOLDER, 42, IMPORTED, (SHOT + 1) * 1000, IMPORTED * 1000, 812);
    require(match(recreated, EPOCH, GA, GA + 10, history, List.of(receipt), 100) == null
        && match(photo, EPOCH, GA, GA + 10, history, List.of(receipt, record(photo, PRIMARY, EPOCH, GA + 1)), 100) == null,
        "An altered ID or multiple local archive records cannot establish a unique proof");
    for (long missing : List.of(0L, -1L)) {
      var noDate = photo(NAME, FOLDER, 42, IMPORTED, missing, (IMPORTED - 1) * 1000, 811);
      require(match(noDate, EPOCH, GA, GA, history, List.of(record(noDate, PRIMARY, EPOCH, GA)), 1) != null,
          "Known missing date sentinels and one-second-earlier added time are accepted");
    }
    for (long capture : List.of(-2L, -1000L, (SHOT - 1) * 1000, (SHOT + 2) * 1000)) {
      var wrongDate = photo(NAME, FOLDER, 42, IMPORTED, capture, IMPORTED * 1000, 811);
      require(match(wrongDate, EPOCH, GA, GA, history, List.of(record(wrongDate, PRIMARY, EPOCH, GA)), 1) == null,
          "Unsupported capture timestamps are excluded");
    }
    for (var badHistory : List.of(history(photo, "OTHER.JPG", 42, SHOT, IMPORTED),
        history(photo, NAME, 43, SHOT, IMPORTED), history(photo, NAME, 42, SHOT + 1, IMPORTED),
        history(photo, NAME, 42, 0, IMPORTED), history(photo, NAME, 42, SHOT, IMPORTED + 1))) {
      require(match(photo, EPOCH, GA, GA, badHistory, List.of(receipt), 1) == null,
          "Wrong name, size, nonpositive/odd history time or import event is excluded");
    }
    var staleAdded = photo(NAME, FOLDER, 42, IMPORTED, (SHOT + 1) * 1000, (IMPORTED - 2) * 1000, 811);
    require(match(staleAdded, EPOCH, GA, GA, history, List.of(record(staleAdded, PRIMARY, EPOCH, GA)), 1) == null,
        "The file's added time must belong to the recorded import event");
    var collision = photo(MediaSaver.INSTANCE.collisionName(NAME, SHOT), FOLDER, 42, IMPORTED,
        (SHOT + 1) * 1000, IMPORTED * 1000, 811);
    require(match(collision, EPOCH, GA, GA, history, List.of(record(collision, PRIMARY, EPOCH, GA)), 1) != null,
        "An exact historic camera collision filename is accepted");
    var genericDuplicate = photo("LEGACY (1).JPG", FOLDER, 42, IMPORTED, (SHOT + 1) * 1000, IMPORTED * 1000, 811);
    require(match(genericDuplicate, EPOCH, GA, GA, history, List.of(record(genericDuplicate, PRIMARY, EPOCH, GA)), 1) == null,
        "An unrelated numeric collision is insufficient for archive-only proof");
    var badRecord = new LegacyArchiveMoveProof.ArchiveRecord(receipt.key(), BUCKET, SHA, receipt.uri(), receipt.name(),
        receipt.folder(), receipt.size(), receipt.modified(), receipt.added(), IMPORTED * 1000 - 1);
    require(match(photo, EPOCH, GA, GA, history, List.of(badRecord), 1) == null, "Archive verification predating import is rejected");
    JSONObject value = archivePayload(photo, receipt.key(), receipt.sha());
    require(LegacyArchiveMoveProof.parse(receipt.key(), value) != null
        && LegacyArchiveMoveProof.parse("b".repeat(32), value) == null
        && LegacyArchiveMoveProof.parse(receipt.key(), new JSONObject(value.toString()).put("size", "42")) == null
        && LegacyArchiveMoveProof.parse(receipt.key(), new JSONObject(value.toString()).put("provider", "other")) == null
        && LegacyArchiveMoveProof.parse(receipt.key(), new JSONObject(value.toString()).put("version", JSONObject.NULL)) == null
        && LegacyArchiveMoveProof.parse(receipt.key(), new JSONObject(value.toString()).put("version", 17)) == null
        && LegacyArchiveMoveProof.parse(receipt.key(), new JSONObject(value.toString()).put("version", " ")) == null
        && LegacyArchiveMoveProof.parse(receipt.key(), new JSONObject(value.toString()).put("version", "null")) == null
        && LegacyArchiveMoveProof.parse(receipt.key(), new JSONObject(value.toString()).put("version", "bad/version")) == null,
        "Proof requires a valid, nonempty E2 object version and the exact typed payload identity");
    var checks = new AtomicInteger();
    expectInterrupted(() -> LegacyArchiveMoveProof.match(photo, EPOCH, GA, GA + 256, history, List.of(receipt),
        new LegacyArchiveMoveProof.Budget(257), () -> checks.incrementAndGet() > 10), "Stop midway through the full search");
  }

  private static PhotoRepository.Photo photo(String name, String folder, long bytes, long modified, long taken, long added, long id) {
    return new PhotoRepository.Photo(ContentUris.withAppendedId(FILES, id), name, folder, bytes, modified, taken, added, 0);
  }

  private static HistoryDb.MoveEvidence history(PhotoRepository.Photo photo, String name, long bytes, long time, long importedAt) {
    return new HistoryDb.MoveEvidence(31, photo.uri.toString(), name, bytes, time, importedAt);
  }

  private static LegacyArchiveMoveProof.ArchiveRecord record(PhotoRepository.Photo photo, String volume, String epoch, long generation)
      throws Exception {
    String key = E2AutoSync.key(new E2AutoSync.Candidate(photo, volume, epoch, generation), BUCKET, Long.toString(generation));
    return new LegacyArchiveMoveProof.ArchiveRecord(key, BUCKET, SHA, photo.uri.toString(), photo.name,
        photo.folder, photo.size, photo.modified, photo.added, IMPORTED * 1000 + 1000);
  }

  private static PhotoMoveStore.ArchiveProof match(PhotoRepository.Photo photo, String epoch, long added, long modified,
      HistoryDb.MoveEvidence history, List<LegacyArchiveMoveProof.ArchiveRecord> records, int budget) throws Exception {
    return LegacyArchiveMoveProof.match(photo, epoch, added, modified, history, records, new LegacyArchiveMoveProof.Budget(budget), () -> false);
  }

  private static JSONObject archivePayload(PhotoRepository.Photo photo, String key, String sha) throws Exception {
    return ArchiveStore.base(photo).put("key", key).put("provider", "idrive-e2").put("bucket", BUCKET)
        .put("sha", sha).put("version", "synthetic-object-version").put("mime", "image/jpeg")
        .put("verifiedAt", Math.multiplyExact(photo.modified + 1, 1000));
  }

  private record Seed(Uri uri, String name, byte[] bytes, HistoryDb.MoveEvidence history, String archiveKey) {}

  private static Seed seed(Fixture f, HistoryDb history, ArchiveStore archive, String name, int rgb, boolean missingDate,
      boolean wrongSha) throws Exception {
    byte[] bytes = missingDate ? jpeg(rgb) : jpegWithCaptureTime(f, rgb, SHOT + 1, SHOT + 2);
    long taken = missingDate ? 0 : (SHOT + 1) * 1000;
    return seed(f, history, archive, name, bytes, taken, wrongSha);
  }

  private static Seed seed(Fixture f, HistoryDb history, ArchiveStore archive, String name, byte[] bytes, long taken, boolean wrongSha)
      throws Exception {
    Uri uri = f.insert(PRIMARY, name, bytes, taken);
    require(f.awaitCaptureTime(uri, taken) == taken, "The published fixture has the expected capture time");
    String[] columns = {DISPLAY_NAME, RELATIVE_PATH, SIZE, DATE_MODIFIED, DATE_TAKEN, DATE_ADDED, GENERATION_ADDED, GENERATION_MODIFIED};
    try (Cursor row = f.resolver.query(uri, columns, null, null, null)) {
      require(row != null && row.moveToFirst() && row.getCount() == 1, "The exact fixture source is indexed");
      long id = ContentUris.parseId(uri);
      var photo = photo(row.getString(0), row.getString(1), row.getLong(2), row.getLong(3), row.getLong(4),
          row.getLong(5) * 1000, id);
      long added = row.getLong(6), modified = row.getLong(7);
      require(photo.modified == photo.added / 1000 || photo.modified == photo.added / 1000 + 1,
          "The live fixture metadata identifies one import event");
      require(added > 0 && modified >= added && modified - added <= LegacyArchiveMoveProof.MAX_GENERATION_DELTA,
          "The fixture generations fit the historic lookup interval");
      String epoch = MediaStore.getVersion(f.isolatedContext(), PRIMARY);
      require(epoch != null && !epoch.isEmpty() && epoch.equals(LegacyArchiveMoveProof.epoch(f.isolatedContext())),
          "A direct MediaStore epoch is available to the isolated application UID");
      String key = E2AutoSync.key(new E2AutoSync.Candidate(photo, PRIMARY, epoch, added), BUCKET, Long.toString(added));
      history.recordImport(MODEL, f.id, new ImportedKey(name, bytes.length, SHOT), uri.toString());
      HistoryDb.MoveEvidence evidence = history.moveEvidence().stream().filter(item -> item.getUri().equals(uri.toString())).findFirst().orElseThrow();
      ContentValues event = new ContentValues();
      event.put("imported_at", photo.modified);
      require(history.getWritableDatabase().update("imported", event, "id=? AND camera_serial=?",
          new String[]{Long.toString(evidence.getId()), f.id}) == 1, "Set this synthetic history's import-event time from the live original");
      var saved = history.moveEvidence().stream().filter(item -> item.getId() == evidence.getId()).findFirst().orElseThrow();
      archive.put(archivePayload(photo, key, wrongSha ? SHA : E2Archive.sha256(bytes)), "verified");
      return new Seed(uri, name, bytes, saved, key);
    }
  }

  private static void checkReadOnlyPreflight(Context base, String secondary) throws Exception {
    try (Fixture f = new Fixture(base, secondary);
         CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         ArchiveStore archive = E2Settings.openRecords(f.isolatedContext());
         PhotoMoveStore journal = new PhotoMoveStore(f.isolatedContext());
         PhotoMove move = f.move(ledger, history, ignored -> {})) {
      Seed dated = seed(f, history, archive, "AGREES.JPG", 0xff598093, false, false);
      Seed missing = seed(f, history, archive, "NO-DATE.JPG", 0xff7a4449, true, false);
      Seed mismatched = seed(f, history, archive, "BAD-HASH.JPG", 0xff29556a, false, true);
      Seed conflict = seed(f, history, archive, "OLD-CONFLICT.JPG", 0xff725588, false, false);
      history.recordImport(MODEL, f.id, new ImportedKey(conflict.name(), conflict.bytes().length, SHOT + 2), conflict.uri().toString());
      Seed pendingArchive = seed(f, history, archive, "UNVERIFIED.JPG", 0xff665528, false, false);
      archive.put(archive.get(pendingArchive.archiveKey()), "pending");
      Uri pending = f.insertPending(PRIMARY, "PENDING.JPG", dated.bytes(), (SHOT + 1) * 1000);
      history.recordImport(MODEL, f.id, new ImportedKey("PENDING.JPG", dated.bytes().length, SHOT), pending.toString());
      require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the synthetic SD destination");
      PhotoMove.Plan proposed = move.plan(secondary);
      require(proposed.items().size() == 3 && proposed.skipped() == 3
          && proposed.items().stream().allMatch(item -> item.archiveProof() != null && item.expectedSha() == null),
          "Metadata admits only three proven archive candidates, excluding pending files, unverified E2 records and conflicting histories");
      for (Seed candidate : List.of(dated, missing, mismatched)) {
        var item = proposed.items().stream().filter(value -> value.source().equals(candidate.uri().toString())).findFirst().orElseThrow();
        require(item.archiveProof().key().equals(candidate.archiveKey()) && item.archiveProof().historyId() == candidate.history().getId()
            && item.legacyIds().equals(Long.toString(candidate.history().getId())), "Keep one selected historical ID and a distinct typed archive proof");
      }
      expectMoveFailure(() -> move.begin(proposed), "A metadata-only proposal must not start a move");
      Seed late = seed(f, history, archive, "LATE.JPG", 0xff986b57, false, false);
      var evidence = history.moveEvidence();
      long indexRevision = archive.indexRevision();
      long locationCount = DatabaseUtils.queryNumEntries(journal.getReadableDatabase(), "local_photos");
      long revision = journal.revision();
      PhotoMove.Plan confirmed = preflight(move, proposed, () -> false);
      require(itemSources(confirmed).equals(Set.of(dated.uri().toString(), missing.uri().toString()))
          && confirmed.skipped() == 4 && confirmed.archiveRejected() == 1 && itemSources(move.plan(secondary)).contains(late.uri().toString()),
          "The verified plan contains only the proposed, fully matching originals and keeps the later file out");
      try {
        confirmed.items().clear();
        throw new PhotoStorageChecks.CheckFailure("Preflight survivors must be immutable");
      } catch (UnsupportedOperationException expected) {
        // The authenticated confirmation covers this exact immutable list.
      }
      PhotoMove.Plan changedConfirmation = new PhotoMove.Plan(confirmed.volume(), List.of(confirmed.items().get(0)),
          confirmed.skipped(), confirmed.archiveRejected(), confirmed.archivePreflight());
      expectMoveFailure(() -> move.begin(changedConfirmation), "A confirmation cannot silently narrow its immutable photo list");
      require(move.batch() == null && journal.revision() == revision
          && DatabaseUtils.queryNumEntries(journal.getReadableDatabase(), "local_photos") == locationCount
          && ledger.receiptCount() == 0 && history.moveEvidence().equals(evidence) && archive.indexRevision() == indexRevision
          && f.rows(secondary).isEmpty(), "Preflight does not create destination rows, a journal, identity, receipt or archive writes");
      for (Seed candidate : List.of(dated, missing, mismatched, conflict, pendingArchive, late)) {
        require(Arrays.equals(candidate.bytes(), f.read(candidate.uri())), "Every synthetic original remains byte exact after preflight");
      }
      checkReadOnlyStreams(f, ledger, history, dated, proposed);
    }
  }

  private static void checkReadOnlyStreams(Fixture f, CameraImportLedger ledger, HistoryDb history, Seed dated,
      PhotoMove.Plan proposed) throws Exception {
    var selected = proposed.items().stream().filter(item -> item.source().equals(dated.uri().toString())).findFirst().orElseThrow();
    var plan = new PhotoMove.Plan(proposed.volume(), List.of(selected), 0);
    Uri required = MediaStore.setRequireOriginal(dated.uri());
    Context context = f.isolatedContext();
    var fullEof = new AtomicBoolean();
    for (var scenario : List.of("full", "short", "extra", "revoked", "cancelled", "metadata")) {
      var beganRead = new AtomicBoolean();
      try (PhotoMove injected = new PhotoMove(context, ledger, history, new PhotoMoveStore(context), ignored -> {}, uri -> {
        require(required.equals(uri), "Preflight uses an original-pinned read of the exact concrete URI");
        byte[] data = switch (scenario) {
          case "short" -> Arrays.copyOf(dated.bytes(), dated.bytes().length - 1);
          case "extra" -> Arrays.copyOf(dated.bytes(), dated.bytes().length + 1);
          default -> dated.bytes();
        };
        return new FilterInputStream(new ByteArrayInputStream(data)) {
          @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (beganRead.get() && scenario.equals("revoked")) throw new IOException("Synthetic original access revoked");
            int count = super.read(bytes, offset, Math.min(length, Math.max(1, data.length / 2)));
            beganRead.set(true);
            if (count == -1 && scenario.equals("full")) fullEof.set(true);
            if (count == -1 && scenario.equals("metadata")) {
              ContentValues values = new ContentValues();
              values.put(DISPLAY_NAME, "RENAMED-DURING-READ.JPG");
              require(f.resolver.update(dated.uri(), values, null, null) == 1, "Alter only the owned synthetic source after reading all bytes");
            }
            return count;
          }
        };
      })) {
        if (scenario.equals("cancelled")) {
          expectInterrupted(() -> preflight(injected, plan, beganRead::get), "Cancelling during the read aborts the entire preflight");
        } else {
          PhotoMove.Plan checked = preflight(injected, plan, () -> false);
          require(scenario.equals("full") ? checked.items().size() == 1 && fullEof.get()
              : checked.items().isEmpty() && checked.archiveRejected() == 1, "Verify EOF and reject short, extra, revoked and metadata-changed originals");
        }
        require(beganRead.get() && injected.batch() == null && f.rows(f.secondary).isEmpty()
            && Arrays.equals(dated.bytes(), f.read(dated.uri())), "A preflight read outcome has no durable move or photo side effects");
      }
    }
  }

  private static PhotoMove.Plan preflight(PhotoMove move, PhotoMove.Plan plan, BooleanSupplier stopped) throws IOException {
    var gate = CameraImportGate.lock;
    gate.lock();
    try {
      return move.preflight(plan, stopped);
    } finally {
      gate.unlock();
    }
  }

  private static Set<String> itemSources(PhotoMove.Plan plan) {
    return plan.items().stream().map(PhotoMoveStore.Item::source).collect(java.util.stream.Collectors.toSet());
  }

  private static void checkLateArchiveConflict(Context base, String secondary, String competingState) throws Exception {
    try (Fixture f = new Fixture(base, secondary);
         CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         ArchiveStore archive = E2Settings.openRecords(f.isolatedContext());
         PhotoMove mover = f.move(ledger, history, ignored -> {})) {
      Seed seed = seed(f, history, archive, "LATE-ARCHIVE-CONFLICT.JPG", 0xff57688b, false, false);
      require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the synthetic SD destination");
      PhotoMove.Plan proposed = mover.plan(secondary);
      PhotoMove.Plan previouslyConfirmed = preflight(mover, proposed, () -> false);
      require(previouslyConfirmed.items().size() == 1, "The first local archive is the only verified proof at confirmation");
      var proof = previouslyConfirmed.items().get(0).archiveProof();
      JSONObject originalRecord = archive.get(seed.archiveKey());
      var photo = new PhotoRepository.Photo(Uri.parse(originalRecord.getString("uri")), originalRecord.getString("name"),
          originalRecord.getString("folder"), originalRecord.getLong("size"), originalRecord.getLong("modified"),
          originalRecord.getLong("taken"), originalRecord.getLong("added"), 0);
      String anotherBucket = BUCKET + "-second";
      String anotherKey = E2AutoSync.key(new E2AutoSync.Candidate(photo, PRIMARY, proof.epoch(), proof.historicalGeneration()),
          anotherBucket, Long.toString(proof.historicalGeneration()));
      archive.put(new JSONObject(originalRecord.toString()).put("key", anotherKey).put("bucket", anotherBucket), competingState);
      long archiveRevision = archive.indexRevision();
      PhotoMove.Plan noLongerConfirmed = preflight(mover, proposed, () -> false);
      require(noLongerConfirmed.items().isEmpty() && noLongerConfirmed.archiveRejected() == 1 && mover.plan(secondary).items().isEmpty()
          && mover.batch() == null && f.rows(secondary).isEmpty(),
          "A second " + competingState + " archive key for the same indexed URI invalidates the proposed unique proof");
      expectMoveFailure(() -> {
        mover.begin(previouslyConfirmed);
        mover.run(() -> false, ignored -> {});
      }, "A stale confirmation must also reject a late " + competingState + " record before authorizing any copy");
      require(f.rows(secondary).isEmpty() && Arrays.equals(seed.bytes(), f.read(seed.uri()))
          && history.moveEvidence().equals(List.of(seed.history())) && archive.indexRevision() == archiveRevision && ledger.receiptCount() == 0,
          "Late competing archive evidence does not write a replacement or modify the original or its records");
      if (mover.batch() != null) mover.abandon();
    }
  }

  private static void checkPublishedTargetDrift(Context base, String secondary, boolean changeFolder) throws Exception {
    try (Fixture f = new Fixture(base, secondary); Fixture relocated = new Fixture(base, secondary);
         CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         ArchiveStore archive = E2Settings.openRecords(f.isolatedContext()); PhotoMoveStore journal = new PhotoMoveStore(f.isolatedContext())) {
      String scenario = changeFolder ? "published folder drift" : "published capture-time drift";
      Seed seed = changeFolder ? seed(f, history, archive, "PUBLISHED-DRIFT.JPG", 0xff698a59, false, false)
          : seed(f, history, archive, "PUBLISHED-DRIFT.JPG", captureWithoutOffset(f), 0, false);
      String archiveBefore = archive.get(seed.archiveKey()).toString();
      require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the synthetic move destination");
      Uri[] changedTarget = new Uri[1];
      try (PhotoMove mover = f.move(ledger, history, reached -> {
        if (reached == PhotoMove.Boundary.HISTORY_UPDATED)
          throw new PhotoStorageChecks.CheckFailure(scenario + " reached relocation of the selected history");
        if (reached != PhotoMove.Boundary.PUBLISHED) return;
        try {
          var targets = f.rows(secondary);
          require(targets.size() == 1 && !targets.get(0).pending() && f.isolatedContext().getPackageName().equals(targets.get(0).owner()),
              scenario + ": only the published owned replacement fixture can be changed");
          Uri target = targets.get(0).uri();
          if (changeFolder) {
            ContentValues values = new ContentValues();
            values.put(RELATIVE_PATH, relocated.path);
            int updated = f.resolver.update(target, values, null, null);
            require(updated == 1, scenario + ": expected exactly one relative-path update, actual=" + updated);
            try (Cursor row = f.resolver.query(target, new String[]{RELATIVE_PATH}, null, null, null)) {
              require(row != null && row.moveToFirst() && relocated.path.equals(row.getString(0)),
                  scenario + ": observe the new relative folder at the exact published URI");
            }
          } else {
            rescanWithCaptureHint(f, target, seed.name());
          }
          require(Arrays.equals(seed.bytes(), f.read(target)), "The target metadata changed without changing its bytes");
          changedTarget[0] = target;
        } catch (IOException unavailable) {
          throw new UncheckedIOException(unavailable);
        }
      })) {
        mover.begin(preflight(mover, mover.plan(secondary), () -> false));
        expectMoveFailure(() -> mover.run(() -> false, ignored -> {}), scenario + ": changed metadata cannot relocate history");
        require(changedTarget[0] != null && mover.batch() != null && mover.batch().done() == 0 && journal.completed(seed.history().getId()) == null
            && history.moveEvidence().equals(List.of(seed.history())) && archiveBefore.equals(archive.get(seed.archiveKey()).toString())
            && ledger.receiptCount() == 0 && Arrays.equals(seed.bytes(), f.read(seed.uri()))
            && Arrays.equals(seed.bytes(), f.read(changedTarget[0])),
            "Capture-time or folder drift preserves both byte-exact copies and all histories without completed proof");
      }
    }
  }

  private static byte[] captureWithoutOffset(Fixture f) throws IOException {
    File jpeg = File.createTempFile("loupe-archive-no-offset-", ".jpg", f.isolatedContext().getCacheDir());
    try {
      Files.write(jpeg.toPath(), jpeg(0xff698a59));
      ExifInterface exif = new ExifInterface(jpeg);
      String original = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC)
          .format(Instant.ofEpochSecond(SHOT + 1));
      exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, original);
      exif.saveAttributes();
      require(!exif.hasAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL), "Capture-time drift fixture must omit its EXIF timezone");
      return Files.readAllBytes(jpeg.toPath());
    } finally {
      Files.deleteIfExists(jpeg.toPath());
    }
  }

  private static void rescanWithCaptureHint(Fixture f, Uri target, String name) throws IOException {
    Context context = f.isolatedContext();
    File root = PhotoStorage.requireWritable(context, f.secondary);
    File path = new File(root, f.path + name).getCanonicalFile();
    try (Cursor row = f.resolver.query(target, new String[]{DATA, DATE_TAKEN, IS_PENDING}, null, null, null)) {
      require(row != null && row.moveToFirst() && row.getString(0) != null && row.getLong(1) == 0 && row.getInt(2) == 0
          && path.equals(new File(row.getString(0)).getCanonicalFile()),
          "Published capture-time drift: verify the exact owned path initially has unknown capture time");
    }
    // DATE_TAKEN on a public row belongs to MediaScanner. A nearby file mtime gives the same JPEG's
    // missing EXIF timezone an inference hint, without editing photo bytes or faking a provider row.
    long hint = (SHOT + 61) * 1000;
    require(path.setLastModified(hint) && path.lastModified() == hint,
        "Published capture-time drift: set the mtime on the exact owned fixture path");
    var finished = new CountDownLatch(1);
    var scanned = new AtomicReference<Uri>();
    MediaScannerConnection.scanFile(context, new String[]{path.getPath()}, new String[]{"image/jpeg"}, (ignored, result) -> {
      scanned.set(result);
      finished.countDown();
    });
    try {
      require(finished.await(8, TimeUnit.SECONDS) && scanned.get() != null
          && ContentUris.parseId(scanned.get()) == ContentUris.parseId(target),
          "Published capture-time drift: scanner must return the original target ID");
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("Capture-time drift fixture scan interrupted");
    }
    try (Cursor row = f.resolver.query(target, new String[]{DATE_TAKEN, DATE_MODIFIED, IS_PENDING}, null, null, null)) {
      require(row != null && row.moveToFirst() && row.getLong(0) > 0 && row.getLong(1) == hint / 1000 && row.getInt(2) == 0,
          "Published capture-time drift: observe known capture time on the same still-published URI after rescanning");
    }
  }

  private static void checkTargetPinDrift(Context base, String secondary, PhotoMove.Boundary boundary) throws Exception {
    try (Fixture f = new Fixture(base, secondary); CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         ArchiveStore archive = E2Settings.openRecords(f.isolatedContext())) {
      Seed seed = seed(f, history, archive, "PIN-DRIFT.JPG", 0xff725c8b, false, false);
      require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the synthetic move destination");
      try (PhotoMove stopped = f.move(ledger, history, reached -> { if (reached == boundary) throw new InterruptedMove(); })) {
        stopped.begin(preflight(stopped, stopped.plan(secondary), () -> false));
        expectCrash(stopped, boundary);
      }
      boolean attached = boundary == PhotoMove.Boundary.ATTACHED;
      var row = f.rows(secondary).get(0);
      Uri target = row.uri();
      require(attached == row.pending() && f.isolatedContext().getPackageName().equals(row.owner()), "Locate the exact owned pinned target");
      byte[] preserved = attached ? jpeg(0xff216759) : seed.bytes();
      if (attached) f.write(target, preserved);
      require(Arrays.equals(preserved, f.read(target)), "Read the pending sentinel or fully published replacement before retry");
      try (PhotoMoveStore journal = new PhotoMoveStore(f.isolatedContext())) {
        var item = journal.next();
        require(item.stage() == (attached ? PhotoMoveStore.COPYING : PhotoMoveStore.VERIFIED)
            && target.toString().equals(item.target()), "Attach and publish leave the exact URI in the expected durable stage");
        var pin = item.targetPin();
        requireTargetBirth(f, target, pin);
        require(attached ? pin.generationModified() == null && pin.version() == null
            : pin.generationModified() != null && pin.version() != null, "Publication crash retains the verified pending revision pin");
        SQLiteDatabase db = journal.getWritableDatabase();
        JSONObject altered;
        try (Cursor stored = db.rawQuery("SELECT target_pin FROM items WHERE source=?", new String[]{item.source()})) {
          require(stored.moveToFirst() && stored.getCount() == 1, "Read just the owned fixture's durable target pin");
          altered = new JSONObject(stored.getString(0));
        }
        if (attached) altered.put("epoch", pin.epoch() + "-simulated-detached-epoch");
        else {
          long differentBirth = Math.addExact(pin.generationAdded(), 1);
          altered.put("added", differentBirth).put("modified", Math.max(pin.generationModified(), differentBirth));
        }
        ContentValues change = new ContentValues();
        change.put("target_pin", altered.toString());
        require(db.update("items", change, "source=? AND target=?", new String[]{item.source(), item.target()}) == 1,
            "Simulate a journal binding from another epoch or another birth without changing actual MediaStore state");
      }
      try (PhotoMove reopened = f.move(ledger, history, ignored -> {}); PhotoMoveStore journal = new PhotoMoveStore(f.isolatedContext())) {
        var before = journal.next();
        expectMoveFailure(() -> reopened.run(() -> false, ignored -> {}), "An epoch or birth mismatch must stop before writing the target or moving history");
        require(before.equals(journal.next()) && reopened.batch().done() == 0 && journal.completed(seed.history().getId()) == null
            && history.moveEvidence().equals(List.of(seed.history())) && ledger.receiptCount() == 0
            && Arrays.equals(seed.bytes(), f.read(seed.uri())) && Arrays.equals(preserved, f.read(target)),
            "A wrong target pin preserves the original, journal, history and even the pending file's sentinel bytes");
      }
    }
  }

  private static void checkTargetChangesWhileOpening(Context base, String secondary, boolean differentDescriptor) throws Exception {
    try (Fixture f = new Fixture(base, secondary); CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         ArchiveStore archive = E2Settings.openRecords(f.isolatedContext()); PhotoMoveStore journal = new PhotoMoveStore(f.isolatedContext())) {
      Seed seed = seed(f, history, archive, "OPEN-RACE.JPG", 0xff284e76, false, false);
      require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the synthetic move destination");
      try (PhotoMove stopped = f.move(ledger, history, reached -> {
        if (reached == PhotoMove.Boundary.ATTACHED) throw new InterruptedMove();
      })) {
        stopped.begin(preflight(stopped, stopped.plan(secondary), () -> false));
        expectCrash(stopped, PhotoMove.Boundary.ATTACHED);
      }
      var before = journal.next();
      Uri attached = Uri.parse(before.target());
      byte[] attachedSentinel = jpeg(0xff916977);
      var attachedRow = f.row(attached);
      require(attachedRow.pending() && f.isolatedContext().getPackageName().equals(attachedRow.owner()),
          "Preload only the exact attached owned fixture");
      f.write(attached, attachedSentinel);
      byte[] otherSentinel = jpeg(0xff579254);
      Uri opened = differentDescriptor
          ? f.insertPending(secondary, "UNRELATED-SENTINEL.JPG", otherSentinel, (SHOT + 1) * 1000)
          : attached;
      require(f.row(opened).pending() && f.isolatedContext().getPackageName().equals(f.row(opened).owner()),
          "The substituted descriptor can refer only to another disposable owned fixture");
      var openMode = new AtomicReference<String>();
      Context scope = f.isolatedContext();
      try (PhotoMove reopened = new PhotoMove(scope, ledger, history, new PhotoMoveStore(scope), ignored -> {},
          f.resolver::openInputStream, (uri, mode) -> {
            require(attached.equals(uri) && openMode.compareAndSet(null, mode), "Exercise one target-open boundary at the pinned URI");
            if (!differentDescriptor) {
              ContentValues rename = new ContentValues();
              rename.put(DISPLAY_NAME, "CHANGED-BEFORE-OPEN.JPG");
              Bundle pending = new Bundle();
              pending.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
              int changed = f.resolver.update(uri, rename, pending);
              try (Cursor row = f.resolver.query(uri, new String[]{DISPLAY_NAME}, pending, null)) {
                require(changed == 1 && row != null && row.moveToFirst() && "CHANGED-BEFORE-OPEN.JPG".equals(row.getString(0)),
                    "Observe the renamed pending target before handing its open descriptor back");
              }
            }
            var file = f.resolver.openFileDescriptor(opened, mode);
            require(file != null, "Open the intended owned sentinel for the simulated race");
            return file;
          })) {
        expectMoveFailure(() -> reopened.run(() -> false, ignored -> {}),
            differentDescriptor ? "Reject a different file descriptor before truncate" : "Reject changed pending metadata after open and before truncate");
        require("rw".equals(openMode.get()) && before.equals(journal.next()) && reopened.batch().done() == 0
            && history.moveEvidence().equals(List.of(seed.history())) && journal.completed(seed.history().getId()) == null
            && ledger.receiptCount() == 0 && Arrays.equals(seed.bytes(), f.read(seed.uri()))
            && Arrays.equals(attachedSentinel, f.read(attached)) && (!differentDescriptor || Arrays.equals(otherSentinel, f.read(opened))),
            "A post-check change leaves both pending sentinels, the original, journal and selected history untouched");
      }
    }
  }

  private static void checkPublishedRevisionDriftBeforeDelete(Context base, String secondary) throws Exception {
    try (Fixture f = new Fixture(base, secondary); CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         ArchiveStore archive = E2Settings.openRecords(f.isolatedContext()); PhotoMoveStore journal = new PhotoMoveStore(f.isolatedContext())) {
      Seed seed = seed(f, history, archive, "LATE-PUBLISHED-REVISION.JPG", 0xff76596a, false, false);
      require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the synthetic move destination");
      var driftObserved = new AtomicBoolean();
      try (PhotoMove moving = f.move(ledger, history, reached -> {
        if (reached != PhotoMove.Boundary.BEFORE_SOURCE_DELETE) return;
        try {
          var active = journal.next();
          Uri target = Uri.parse(active.target());
          var row = f.row(target);
          require(active.pendingCompletion() != null && active.targetPin().version() != null && !row.pending()
              && f.isolatedContext().getPackageName().equals(row.owner()), "Change only the fully pinned owned published fixture");
          int favorite;
          try (Cursor before = f.resolver.query(target, new String[]{IS_FAVORITE}, null, null, null)) {
            require(before != null && before.moveToFirst(), "Published revision drift: read the original favorite value");
            favorite = before.getInt(0);
          }
          ContentValues change = new ContentValues();
          change.put(IS_FAVORITE, favorite == 0 ? 1 : 0);
          int updated = f.resolver.update(target, change, null, null);
          require(updated == 1, "Published revision drift: expected one favorite update, actual=" + updated);
          change.put(IS_FAVORITE, favorite);
          int restored = f.resolver.update(target, change, null, null);
          require(restored == 1, "Published revision drift: expected one favorite restoration, actual=" + restored);
          try (Cursor current = f.resolver.query(target, new String[]{GENERATION_ADDED, GENERATION_MODIFIED, IS_FAVORITE, DATE_TAKEN}, null, null, null)) {
            require(current != null && current.moveToFirst() && active.targetPin().generationAdded() == current.getLong(0)
                && active.targetPin().generationModified() < current.getLong(1) && favorite == current.getInt(2)
                && current.getLong(3) == (SHOT + 1) * 1000,
                "Published revision drift: observe same birth, favorite and capture values with a newer revision");
          }
          driftObserved.set(true);
        } catch (IOException unavailable) {
          throw new UncheckedIOException(unavailable);
        }
      })) {
        moving.begin(preflight(moving, moving.plan(secondary), () -> false));
        expectMoveFailure(() -> moving.run(() -> false, ignored -> {}), "The final revision drift must revoke original deletion authority");
        var current = journal.next();
        Uri target = Uri.parse(current.target());
        require(driftObserved.get() && moving.batch() != null && moving.batch().done() == 0 && current.stage() == PhotoMoveStore.REMAPPED
            && journal.completed(seed.history().getId()) == null && history.importDestinations().equals(Set.of(target.toString()))
            && Arrays.equals(seed.bytes(), f.read(seed.uri())) && Arrays.equals(seed.bytes(), f.read(target)),
            "A late revision change leaves both identical copies and the recoverable history without finalizing deletion");
      }
    }
  }

  private static void requireTargetBirth(Fixture f, Uri target, PhotoMoveStore.TargetPin pin) throws IOException {
    String volume = PhotoStorage.volumeOf(target);
    Bundle visibility = new Bundle();
    visibility.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
    try (Cursor row = f.resolver.query(target, new String[]{GENERATION_ADDED}, visibility, null)) {
      require(row != null && row.moveToFirst() && !row.isNull(0) && pin != null && pin.generationAdded() != null
          && pin.generationAdded() == row.getLong(0) && volume.equals(pin.volume())
          && MediaStore.getVersion(f.isolatedContext(), volume).equals(pin.epoch()), "Pin records the exact target birth and app-UID epoch");
    }
  }

  private static void checkLostHistoryAfterRelocation(Context base, String secondary, PhotoMove.Boundary boundary) throws Exception {
    try (Fixture f = new Fixture(base, secondary);
         CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         ArchiveStore archive = E2Settings.openRecords(f.isolatedContext())) {
      Seed seed = seed(f, history, archive, "RESET-AFTER-UPDATE.JPG", 0xff537468, false, false);
      String archiveBefore = archive.get(seed.archiveKey()).toString();
      require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the synthetic SD destination");
      try (PhotoMove interrupted = f.move(ledger, history, reached -> { if (reached == boundary) throw new InterruptedMove(); })) {
        interrupted.begin(preflight(interrupted, interrupted.plan(secondary), () -> false));
        expectCrash(interrupted, boundary);
      }
      Uri published = f.rows(secondary).get(0).uri();
      require(history.importDestinations().equals(Set.of(published.toString())) && Arrays.equals(seed.bytes(), f.read(published))
          && Arrays.equals(seed.bytes(), f.read(seed.uri())), "Interrupt after relocating the selected history while both originals exist");
      history.resetHistory(MODEL, f.id);
      try (PhotoMove resumed = f.move(ledger, history, ignored -> {}); PhotoMoveStore journal = new PhotoMoveStore(f.isolatedContext())) {
        expectMoveFailure(() -> resumed.run(() -> false, ignored -> {}), "Resume must refuse after the selected relocated history was reset");
        require(resumed.batch() != null && resumed.batch().done() == 0 && journal.completed(seed.history().getId()) == null
            && Arrays.equals(seed.bytes(), f.read(seed.uri())) && Arrays.equals(seed.bytes(), f.read(published)),
            "Failed resume leaves intact source and verified destination copies and does not create completed proof");
        resumed.abandon();
        require(resumed.batch() == null && journal.completed(seed.history().getId()) == null && history.moveEvidence().isEmpty()
            && archiveBefore.equals(archive.get(seed.archiveKey()).toString()) && ledger.receiptCount() == 0
            && Arrays.equals(seed.bytes(), f.read(seed.uri())) && Arrays.equals(seed.bytes(), f.read(published)),
            "Abandon clears the blocked journal without recreating the removed history or deleting either verified copy");
      }
    }
  }

  private static void checkLostHistory(Context base, String secondary) throws Exception {
    try (Fixture f = new Fixture(base, secondary);
         CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history(); ArchiveStore archive = E2Settings.openRecords(f.isolatedContext());
         PhotoMove mover = f.move(ledger, history, ignored -> {})) {
      Seed seed = seed(f, history, archive, "SELECTED-ID.JPG", 0xff795b53, false, false);
      require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the synthetic SD destination");
      mover.begin(preflight(mover, mover.plan(secondary), () -> false));
      history.resetHistory(MODEL, f.id);
      history.recordImport(MODEL, f.id, new ImportedKey(seed.name(), seed.bytes().length, SHOT), seed.uri().toString());
      var cameraSource = f.source("wifi:", seed.name(), seed.bytes());
      String sameSha = E2Archive.sha256(seed.bytes());
      ledger.adopt(cameraSource, sameSha, sameSha, seed.bytes().length, seed.uri().toString(), "JPEG", "synthetic");
      expectMoveFailure(() -> mover.run(() -> false, ignored -> {}), "A later matching camera receipt cannot substitute for the selected history ID");
      require(mover.batch() != null && mover.batch().done() == 0 && f.rows(secondary).isEmpty()
          && seed.uri().toString().equals(ledger.known(cameraSource).uri) && Arrays.equals(seed.bytes(), f.read(seed.uri())),
          "Lost selected history retains the source, incidental receipt and queued journal");
      mover.abandon();
      require(mover.batch() == null && Arrays.equals(seed.bytes(), f.read(seed.uri())), "Abandon preserves the original after lost evidence");
    }
  }

  private static void checkProofRecovery(Context base, String secondary, PhotoMove.Boundary boundary) throws Exception {
    try (Fixture f = new Fixture(base, secondary);
         CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history(); ArchiveStore archive = E2Settings.openRecords(f.isolatedContext())) {
      Seed seed = seed(f, history, archive, "RESUME.JPG", 0xff735f86, false, false);
      String e2Before = archive.get(seed.archiveKey()).toString();
      require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the synthetic SD destination");
      PhotoMoveStore.ArchiveProof proof;
      try (PhotoMove interrupted = f.move(ledger, history, reached -> { if (reached == boundary) throw new InterruptedMove(); })) {
        PhotoMove.Plan plan = preflight(interrupted, interrupted.plan(secondary), () -> false);
        require(plan.items().size() == 1, "The archive-only move has one verified candidate");
        proof = plan.items().get(0).archiveProof();
        interrupted.begin(plan);
        expectCrash(interrupted, boundary);
      }
      try (PhotoMoveStore reopened = new PhotoMoveStore(f.isolatedContext())) {
        var item = reopened.next();
        require(reopened.getReadableDatabase().getVersion() == 4 && item != null && proof.equals(item.archiveProof())
            && item.expectedSha() == null && item.legacyIds().equals(Long.toString(seed.history().getId()))
            && reopened.completed(seed.history().getId()) == null,
            "The reopened v4 journal retains the initial proof without prematurely promoting completed evidence");
        if (boundary == PhotoMove.Boundary.PUBLISHED) {
          var target = f.rows(secondary).get(0);
          require(item.stage() == PhotoMoveStore.VERIFIED && !target.pending() && item.target().equals(target.uri().toString())
              && Arrays.equals(seed.bytes(), f.read(target.uri())), "Provider publishes the original attached row before the journal advances");
          requireTargetBirth(f, target.uri(), item.targetPin());
        }
        if (boundary == PhotoMove.Boundary.DELETED) {
          var pending = item.pendingCompletion();
          require(pending != null && pending.historyId() == seed.history().getId() && pending.source().equals(item.target())
              && pending.sha().equals(proof.sha()) && pending.historyFingerprint().equals(proof.historyFingerprint()),
              "Verified target proof is already durable when interrupted after source deletion");
        }
      }
      Uri publishedUri = f.rows(secondary).get(0).uri();
      require(boundary == PhotoMove.Boundary.DELETED ? f.rows(PRIMARY).isEmpty() : Arrays.equals(seed.bytes(), f.read(seed.uri())),
          "Interrupting preserves the original until the verified deletion boundary");
      try (PhotoMove resumed = f.move(ledger, history, ignored -> {})) {
        if (boundary == PhotoMove.Boundary.DELETED) {
          var ended = resumed.abandon();
          require(ended.total() == 1 && ended.done() == 1, "End reports the verified post-delete move as completed rather than unfinished");
        } else require(resumed.run(() -> false, ignored -> {}).done() == 1, "The interrupted archive-only move finishes once on restart");
        require(resumed.batch() == null, "Recovery or post-delete roll-forward clears the journal");
      }
      checkReplacement(f, history, archive, seed, e2Before, secondary);
      require(publishedUri.equals(f.rows(secondary).get(0).uri()), "Resume preserves the same already-published target URI");
      requireCompletedProof(f, seed, publishedUri);
      require(ledger.receiptCount() == 0 && ledger.contentCount() == 0, "Resuming archive-only proof never invents a camera receipt");
    }
  }

  private static void checkReplacement(Fixture f, HistoryDb history, ArchiveStore archive, Seed seed, String e2Before, String destination)
      throws Exception {
    var targets = f.rows(destination);
    require(targets.size() == 1 && !targets.get(0).pending() && !targets.get(0).trashed(), "The destination contains one published replacement");
    Uri target = targets.get(0).uri();
    require(Arrays.equals(seed.bytes(), f.read(target)) && f.awaitCaptureTime(target, (SHOT + 1) * 1000) == (SHOT + 1) * 1000
        && f.rows(PhotoStorage.PRIMARY.equals(destination) ? f.secondary : PRIMARY).isEmpty(),
        "The verified replacement preserves bytes and EXIF time without another original");
    var after = history.moveEvidence();
    require(after.size() == 1 && after.get(0).getId() == seed.history().getId() && after.get(0).getShot() == SHOT
        && after.get(0).getImportedAt() == seed.history().getImportedAt() && after.get(0).getUri().equals(target.toString())
        && e2Before.equals(archive.get(seed.archiveKey()).toString()), "Move updates only the selected history URI, preserving clock and E2 record");
  }

  private static void checkProofRollback(Context base, String secondary) throws Exception {
    try (Fixture f = new Fixture(base, secondary);
         CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history(); ArchiveStore archive = E2Settings.openRecords(f.isolatedContext())) {
      Seed seed = seed(f, history, archive, "ROLLBACK.JPG", 0xff497bb6, false, false);
      require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the synthetic SD destination");
      try (PhotoMove moved = f.move(ledger, history, reached -> {
        if (reached == PhotoMove.Boundary.REMAPPED) throw new InterruptedMove();
      })) {
        moved.begin(preflight(moved, moved.plan(secondary), () -> false));
        expectCrash(moved, PhotoMove.Boundary.REMAPPED);
      }
      f.removeOwned(f.rows(secondary).get(0));
      try (PhotoMove reopened = f.move(ledger, history, ignored -> {})) {
        expectMoveFailure(() -> reopened.run(() -> false, ignored -> {}), "A lost verified target cannot authorize source deletion");
        reopened.abandon();
        require(reopened.batch() == null && f.rows(secondary).isEmpty() && history.moveEvidence().equals(List.of(seed.history()))
            && Arrays.equals(seed.bytes(), f.read(seed.uri())) && ledger.receiptCount() == 0,
            "Rollback restores the original reference and preserves its bytes without a camera receipt");
        expectMoveFailure(reopened::abandon, "Completed rollback cannot apply again");
        require(history.moveEvidence().equals(List.of(seed.history())), "Repeating rollback leaves the restored reference unchanged");
        try (PhotoMoveStore journal = new PhotoMoveStore(f.isolatedContext())) {
          require(journal.completed(seed.history().getId()) == null, "Pre-delete rollback does not leave a new completed proof");
        }
      }
    }
  }

  private static void checkDeletedSourceWithResetHistory(Context base, String secondary, boolean reverse) throws Exception {
    try (Fixture f = new Fixture(base, secondary); CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         ArchiveStore archive = E2Settings.openRecords(f.isolatedContext()); TagStore tags = new TagStore(f.isolatedContext());
         ShareHistoryStore shares = new ShareHistoryStore(f.isolatedContext()); PhotoMoveStore journal = new PhotoMoveStore(f.isolatedContext())) {
      Seed seed = seed(f, history, archive, "RESET-AFTER-DELETE.JPG", 0xff663785, false, false);
      String archiveBefore = archive.get(seed.archiveKey()).toString();
      var ranking = new RankingStore(f.isolatedContext());
      LogicalLabels labels = seedLogicalLabels(f, seed, tags, ranking, shares);
      PhotoMoveStore.CompletedProof predecessor = null;
      String sourceVolume = PRIMARY;
      String destination = secondary;
      if (reverse) {
        try (PhotoMove first = f.move(ledger, history, ignored -> {})) {
          require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the initial SD destination");
          first.begin(preflight(first, first.plan(secondary), () -> false));
          require(first.run(() -> false, ignored -> {}).done() == 1, "Establish a completed proof before the reverse move");
        }
        predecessor = requireCompletedProof(f, seed, f.rows(secondary).get(0).uri());
        sourceVolume = secondary;
        destination = PRIMARY;
      }
      require(PhotoStorage.select(f.isolatedContext(), destination), "Choose the synthetic move destination");
      try (PhotoMove stopped = f.move(ledger, history, reached -> {
        if (reached == PhotoMove.Boundary.DELETED) throw new InterruptedMove();
      })) {
        stopped.begin(preflight(stopped, stopped.plan(destination), () -> false));
        expectCrash(stopped, PhotoMove.Boundary.DELETED);
      }
      Uri target = f.rows(destination).get(0).uri();
      var interrupted = journal.next();
      require(f.rows(sourceVolume).isEmpty() && interrupted.stage() == PhotoMoveStore.REMAPPED && interrupted.pendingCompletion() != null
          && labels.key().equals(interrupted.identity().archiveKey()) && labels.tagSource().equals(interrupted.identity().tagSource())
          && history.importDestinations().equals(Set.of(target.toString()))
          && java.util.Objects.equals(predecessor, journal.completed(seed.history().getId())),
          "Interrupt with the locally mapped replacement as the sole copy before changing the prior completed proof");
      history.resetHistory(MODEL, f.id);
      try (PhotoMove reopened = f.move(ledger, history, ignored -> {})) {
        var ended = reopened.abandon();
        require(ended.total() == 1 && ended.done() == 1 && reopened.batch() == null && history.moveEvidence().isEmpty()
            && journal.completed(seed.history().getId()) == null && ledger.receiptCount() == 0
            && archiveBefore.equals(archive.get(seed.archiveKey()).toString()) && Arrays.equals(seed.bytes(), f.read(target)),
            "End keeps the sole exact copy, removes any predecessor proof and leaves the erased import history empty");
      }
      requireLogicalLabels(f, target, labels, tags, ranking, shares);
    }
  }

  private static void checkStaleSourceWithResetHistory(Context base, String secondary) throws Exception {
    try (Fixture f = new Fixture(base, secondary); CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         ArchiveStore archive = E2Settings.openRecords(f.isolatedContext()); TagStore tags = new TagStore(f.isolatedContext());
         ShareHistoryStore shares = new ShareHistoryStore(f.isolatedContext()); PhotoMoveStore journal = new PhotoMoveStore(f.isolatedContext())) {
      Seed seed = seed(f, history, archive, "STALE-SOURCE.JPG", 0xff814866, false, false);
      var ranking = new RankingStore(f.isolatedContext());
      LogicalLabels labels = seedLogicalLabels(f, seed, tags, ranking, shares);
      require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the synthetic move destination");
      try (PhotoMove stopped = f.move(ledger, history, reached -> {
        if (reached == PhotoMove.Boundary.REMAPPED) throw new InterruptedMove();
      })) {
        stopped.begin(preflight(stopped, stopped.plan(secondary), () -> false));
        expectCrash(stopped, PhotoMove.Boundary.REMAPPED);
      }
      Uri target = f.rows(secondary).get(0).uri();
      var item = journal.next();
      var batch = journal.batch();
      history.resetHistory(MODEL, f.id);
      String renamed = "OCCUPIED-SOURCE-URI.JPG";
      ContentValues changed = new ContentValues();
      changed.put(DISPLAY_NAME, renamed);
      require(f.resolver.update(seed.uri(), changed, null, null) == 1, "Change only the exact owned synthetic source's MediaStore revision");
      try (Cursor row = f.resolver.query(seed.uri(), new String[]{DISPLAY_NAME}, null, null, null)) {
        require(row != null && row.moveToFirst() && renamed.equals(row.getString(0)), "The same concrete URI is occupied by a different row revision");
      }
      try (PhotoMove reopened = f.move(ledger, history, ignored -> {})) {
        expectMoveFailure(reopened::abandon, "An occupied original URI with a different revision cannot justify discarding the preserved target mapping");
        require(batch.equals(reopened.batch()) && item.equals(journal.next()) && journal.completed(seed.history().getId()) == null
            && history.moveEvidence().isEmpty() && ledger.receiptCount() == 0 && Arrays.equals(seed.bytes(), f.read(seed.uri()))
            && Arrays.equals(seed.bytes(), f.read(target)), "Fail closed with both copies, the exact journal and the erased history unchanged");
      }
      requireLogicalLabels(f, target, labels, tags, ranking, shares);
    }
  }

  private record LogicalLabels(String key, String tagSource, String tagId, String tagPhotoId, long sharedAt) {}

  private static LogicalLabels seedLogicalLabels(Fixture f, Seed seed, TagStore tags, RankingStore ranking, ShareHistoryStore shares)
      throws IOException {
    var original = f.photo(seed.uri());
    String key = ArchiveStore.key(original);
    String tagSource = TagStore.source(original);
    var tag = tags.create("Synthetic move tag");
    String tagPhotoId = tags.photoId(original, true);
    tags.record(UUID.randomUUID().toString(), tag.id, tagPhotoId, 0, TagJudgments.Value.ASSIGNED);
    require(ranking.saveSingleBest(key, false, true), "Seed the original's best mark");
    long sharedAt = System.currentTimeMillis();
    require(shares.complete(shares.begin(List.of(key), sharedAt), "test.fixture", "Synthetic share", sharedAt),
        "Seed the original's share handoff");
    return new LogicalLabels(key, tagSource, tag.id, tagPhotoId, sharedAt);
  }

  private static void requireLogicalLabels(Fixture f, Uri uri, LogicalLabels labels, TagStore tags, RankingStore ranking,
      ShareHistoryStore shares) throws IOException {
    var photo = f.photo(uri);
    String key = ArchiveStore.key(photo);
    var handoff = shares.history(key);
    require(labels.key().equals(key) && labels.tagSource().equals(TagStore.source(photo)) && labels.tagPhotoId().equals(tags.photoId(photo, false))
        && tags.load().value(labels.tagId(), labels.tagPhotoId()) == TagJudgments.Value.ASSIGNED && ranking.singleBest(key)
        && handoff.size() == 1 && handoff.get(0).handedOffAt == labels.sharedAt(),
        "The target retains the original logical identity, tag, ranking and share handoff");
  }

  private static void checkLockedAbandon(Context base, String secondary) throws Exception {
    try (Fixture f = new Fixture(base, secondary); CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         ArchiveStore archive = E2Settings.openRecords(f.isolatedContext());
         PhotoMoveStore journal = new PhotoMoveStore(f.isolatedContext())) {
      Seed seed = seed(f, history, archive, "ABANDON-LOCK.JPG", 0xff823e39, false, false);
      require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the synthetic SD destination");
      try (PhotoMove stopped = f.move(ledger, history, reached -> {
        if (reached == PhotoMove.Boundary.REMAPPED) throw new InterruptedMove();
      })) {
        stopped.begin(preflight(stopped, stopped.plan(secondary), () -> false));
        expectCrash(stopped, PhotoMove.Boundary.REMAPPED);
      }
      Uri target = f.rows(secondary).get(0).uri();
      var relocated = history.moveEvidence();
      var batch = journal.batch();
      var item = journal.next();
      require(history.importDestinations().equals(Set.of(target.toString())) && item.stage() == PhotoMoveStore.REMAPPED,
          "Both copies and the relocated history are available before testing abandon exclusion");
      var busyAttempt = new FutureTask<Void>(() -> {
        try (PhotoMove concurrent = f.move(ledger, history, ignored -> {})) {
          try {
            concurrent.abandon();
            throw new PhotoStorageChecks.CheckFailure("Abandon ran without acquiring the originals lock");
          } catch (PhotoMove.Failure busy) {
            require(busy.getMessage().contains("端末の容量整理"), "Busy abandon explains that original cleanup must finish first");
          }
        }
        return null;
      });
      Thread worker = new Thread(busyAttempt, "archive-abandon-test");
      var originals = E2SyncJob.ORIGINALS;
      require(originals.tryLock(5, TimeUnit.SECONDS), "Simulate a competing original-cleanup owner");
      try {
        worker.start();
        busyAttempt.get(5, TimeUnit.SECONDS);
        require(batch.equals(journal.batch()) && item.equals(journal.next()) && relocated.equals(history.moveEvidence())
            && journal.completed(seed.history().getId()) == null && Arrays.equals(seed.bytes(), f.read(seed.uri()))
            && Arrays.equals(seed.bytes(), f.read(target)), "With originals locked, abandon leaves both copies and the exact journal and history unchanged");
      } finally {
        originals.unlock();
        worker.join(5000);
        require(!worker.isAlive(), "The competing abandon attempt has stopped before fixture cleanup");
      }
      try (PhotoMove retried = f.move(ledger, history, ignored -> {})) {
        retried.abandon();
        require(retried.batch() == null && history.moveEvidence().equals(List.of(seed.history()))
            && journal.completed(seed.history().getId()) == null && Arrays.equals(seed.bytes(), f.read(seed.uri()))
            && Arrays.equals(seed.bytes(), f.read(target)), "Retry after releasing the lock safely restores the source reference and preserves both copies");
      }
    }
  }

  private static void checkRoundTrip(Context base, String secondary) throws Exception {
    try (Fixture f = new Fixture(base, secondary);
         CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history(); ArchiveStore archive = E2Settings.openRecords(f.isolatedContext());
         PhotoMove mover = f.move(ledger, history, ignored -> {})) {
      Seed seed = seed(f, history, archive, "ROUND-TRIP.JPG", 0xff733c39, false, false);
      String e2Before = archive.get(seed.archiveKey()).toString();
      require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the first synthetic destination");
      mover.begin(preflight(mover, mover.plan(secondary), () -> false));
      require(mover.run(() -> false, ignored -> {}).done() == 1, "Finish the initial archive-proven move");
      checkReplacement(f, history, archive, seed, e2Before, secondary);

      Uri card = f.rows(secondary).get(0).uri();
      var prior = requireCompletedProof(f, seed, card);
      require(PhotoStorage.select(f.isolatedContext(), PRIMARY), "Choose the reverse synthetic destination");
      PhotoStorageInventory inventory = mover.inventory(PRIMARY);
      require(inventory.photos().stream().anyMatch(photo -> photo.uri().equals(card) && photo.historyMatches()
              && photo.status() == PhotoStorageInventory.Status.ELIGIBLE),
          "A completed move remains recognized with the original historical timestamp");
      String overview = PhotoStorageInventoryActivity.summary(inventory);
      require(overview.contains("履歴と一致した写真 1枚") && overview.contains("内容の確認待ち 1枚（履歴と一致した1枚を含む）")
          && !overview.contains("参照先の写真"), "Reverse inventory explains that the one pending photo is also the one matched photo");
      PhotoMove.Plan back = preflight(mover, mover.plan(PRIMARY), () -> false);
      require(itemSources(back).equals(Set.of(card.toString())) && prior.equals(back.items().get(0).sourceProof()),
          "The confirmed replacement's durable proof authorizes the independent reverse move");
      try (PhotoMove stopped = f.move(ledger, history, reached -> {
        if (reached == PhotoMove.Boundary.BEFORE_SOURCE_DELETE) throw new InterruptedMove();
      })) {
        stopped.begin(back);
        expectCrash(stopped, PhotoMove.Boundary.BEFORE_SOURCE_DELETE);
      }
      try (PhotoMoveStore active = new PhotoMoveStore(f.isolatedContext())) {
        require(prior.equals(active.completed(seed.history().getId())) && active.next().pendingCompletion() != null,
            "Copying and remapping retain prior proof until source deletion and journal commit");
      }
      mover.abandon();
      require(mover.batch() == null && history.importDestinations().equals(Set.of(card.toString()))
          && requireCompletedProof(f, seed, card).equals(prior) && Arrays.equals(seed.bytes(), f.read(card)),
          "Pre-delete rollback keeps the prior completed proof and exact source reference");
      // Abandon keeps an already-verified target; remove only this test-owned redundant copy before retry.
      for (var copy : f.rows(PRIMARY)) f.removeOwned(copy);
      mover.begin(preflight(mover, mover.plan(PRIMARY), () -> false));
      require(mover.run(() -> false, ignored -> {}).done() == 1 && mover.batch() == null, "Complete an independent reverse move");
      checkReplacement(f, history, archive, seed, e2Before, PRIMARY);
      Uri internal = f.rows(PRIMARY).get(0).uri();
      require(!prior.equals(requireCompletedProof(f, seed, internal)) && ledger.receiptCount() == 0 && ledger.contentCount() == 0,
          "Completed proof advances to the new URI without adding a camera receipt");
      ContentValues changed = new ContentValues();
      changed.put(DISPLAY_NAME, "COMPLETED-DRIFT.JPG");
      require(f.resolver.update(internal, changed, null, null) == 1 && mover.plan(secondary).items().isEmpty()
          && Arrays.equals(seed.bytes(), f.read(internal)) && mover.batch() == null,
          "A changed same-URI MediaStore revision cannot reuse completed move proof");
    }
  }

  private static PhotoMoveStore.CompletedProof requireCompletedProof(Fixture f, Seed seed, Uri target) throws Exception {
    try (PhotoMoveStore store = new PhotoMoveStore(f.isolatedContext())) {
      var proof = store.completed(seed.history().getId());
      String volume = PhotoStorage.volumeOf(target);
      require(proof != null && proof.source().equals(target.toString()) && proof.volume().equals(volume)
          && proof.epoch().equals(MediaStore.getVersion(f.isolatedContext(), volume)) && proof.bytes() == seed.bytes().length
          && proof.generationAdded() > 0 && proof.generationModified() >= proof.generationAdded()
          && proof.sha().equals(E2Archive.sha256(seed.bytes()))
          && proof.historyFingerprint().equals(LegacyArchiveMoveProof.historyFingerprint(seed.history())),
          "Completed proof is bound to exact current application-UID MediaStore identity and unchanged historical metadata");
      return proof;
    }
  }

  private static void checkVersionTwoResume(Context base, String secondary) throws Exception {
    try (Fixture f = new Fixture(base, secondary); CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history()) {
      byte[] bytes = jpegWithCaptureTime(f, 0xff346591, SHOT, SHOT + 1);
      Uri original = f.insertDatedLegacyPhoto("V2-QUEUED.JPG", bytes);
      history.recordImport(MODEL, f.id, new ImportedKey("V2-QUEUED.JPG", bytes.length, SHOT), original.toString());
      var evidence = history.moveEvidence().get(0);
      String version;
      String[] columns = {DISPLAY_NAME, RELATIVE_PATH, MIME_TYPE, SIZE, DATE_MODIFIED, DATE_ADDED, DATE_TAKEN,
          GENERATION_ADDED, GENERATION_MODIFIED};
      try (Cursor row = f.resolver.query(original, columns, null, null, null)) {
        require(row != null && row.moveToFirst(), "A v2 journal fixture has real original metadata");
        var fields = new java.util.ArrayList<String>();
        for (int n = 0; n < columns.length; n++) fields.add(row.getString(n));
        version = String.join("\n", fields);
      }
      Context context = f.isolatedContext();
      try (SQLiteDatabase old = context.openOrCreateDatabase("photo-moves.db", 0, null)) {
        old.execSQL("CREATE TABLE batch(id INTEGER PRIMARY KEY CHECK(id=1), volume TEXT NOT NULL, total INTEGER NOT NULL, skipped INTEGER NOT NULL)");
        old.execSQL("CREATE TABLE items(source TEXT PRIMARY KEY, source_alias TEXT, version TEXT NOT NULL, expected_sha TEXT, "
            + "legacy_ids TEXT NOT NULL, bytes INTEGER NOT NULL, token TEXT, target TEXT, sha TEXT, stage INTEGER NOT NULL DEFAULT 0, "
            + "archive_key TEXT, tag_source TEXT, sort_time INTEGER)");
        old.execSQL("CREATE INDEX items_stage ON items(stage)");
        PhotoIdentityIndex.create(old);
        old.execSQL("CREATE TABLE revision (id INTEGER PRIMARY KEY CHECK(id=1), value INTEGER NOT NULL)");
        old.execSQL("INSERT INTO revision VALUES(1,12)");
        old.execSQL("INSERT INTO batch VALUES(1,?,1,0)", new Object[]{secondary});
        old.execSQL("INSERT INTO items(source,version,legacy_ids,bytes) VALUES(?,?,?,?)",
            new Object[]{original.toString(), version, Long.toString(evidence.getId()), bytes.length});
        old.setVersion(2);
      }
      try (PhotoMoveStore store = new PhotoMoveStore(context); PhotoMove mover = f.move(ledger, history, ignored -> {})) {
        require(store.getReadableDatabase().getVersion() == 4 && store.revision() == 12 && store.next().archiveProof() == null,
            "Existing v2 journal upgrades to v4 without inventing proof or changing the revision");
        require(mover.run(() -> false, ignored -> {}).done() == 1 && mover.batch() == null && f.rows(PRIMARY).isEmpty(),
            "The ordinary queued v2 move resumes on v4 without an E2 record");
        Uri replacement = f.rows(secondary).get(0).uri();
        require(Arrays.equals(bytes, f.read(replacement)) && history.importDestinations().equals(Set.of(replacement.toString())),
            "Migrated v2 preserves the original bytes and normal legacy update semantics");
      }
    }
  }

  private static void checkVersionThreeOrdinaryResume(Context base, String secondary) throws Exception {
    try (Fixture f = new Fixture(base, secondary); CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history()) {
      byte[] bytes = jpegWithCaptureTime(f, 0xff635a76, SHOT, SHOT + 1);
      Uri original = f.insertDatedLegacyPhoto("V3-ORDINARY.JPG", bytes);
      history.recordImport(MODEL, f.id, new ImportedKey("V3-ORDINARY.JPG", bytes.length, SHOT), original.toString());
      require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the ordinary move destination");
      try (PhotoMove interrupted = f.move(ledger, history, boundary -> {
        if (boundary == PhotoMove.Boundary.ATTACHED) throw new InterruptedMove();
      })) {
        interrupted.begin(interrupted.plan(secondary));
        expectCrash(interrupted, PhotoMove.Boundary.ATTACHED);
      }
      Uri target = f.rows(secondary).get(0).uri();
      copyFixtureSchemaToVersionThree(f);
      try (PhotoMoveStore upgraded = new PhotoMoveStore(f.isolatedContext()); PhotoMove reopened = f.move(ledger, history, ignored -> {})) {
        var item = upgraded.next();
        require(upgraded.getReadableDatabase().getVersion() == 4 && item.stage() == PhotoMoveStore.COPYING
            && item.targetPin() == null && item.target().equals(target.toString()) && !item.hasHistoricalProof(),
            "v3 ordinary journal retains its exact pending target without inventing a historical pin");
        require(reopened.run(() -> false, ignored -> {}).done() == 1 && reopened.batch() == null
            && f.rows(PRIMARY).isEmpty() && history.importDestinations().equals(Set.of(target.toString()))
            && Arrays.equals(bytes, f.read(target)), "v3 ordinary move resumes against its same compatible pending row");
      }
    }
  }

  private enum VersionThreeHistorical { QUEUED, ATTACHED, POST_DELETE }

  private static void checkVersionThreeHistorical(Context base, String secondary, VersionThreeHistorical migration) throws Exception {
    try (Fixture f = new Fixture(base, secondary); CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         ArchiveStore archive = E2Settings.openRecords(f.isolatedContext())) {
      Seed seed = seed(f, history, archive, "V3-HISTORICAL.JPG", 0xff656b8a, false, false);
      String e2Before = archive.get(seed.archiveKey()).toString();
      PhotoMoveStore.CompletedProof previous = null;
      String destination = secondary;
      if (migration == VersionThreeHistorical.POST_DELETE) {
        require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the first historical destination");
        try (PhotoMove first = f.move(ledger, history, ignored -> {})) {
          first.begin(preflight(first, first.plan(secondary), () -> false));
          require(first.run(() -> false, ignored -> {}).done() == 1, "Establish a completed proof for migration while reversing");
        }
        previous = requireCompletedProof(f, seed, f.rows(secondary).get(0).uri());
        destination = PRIMARY;
      }
      require(PhotoStorage.select(f.isolatedContext(), destination), "Choose the historical migration destination");
      var crash = migration == VersionThreeHistorical.ATTACHED ? PhotoMove.Boundary.ATTACHED : PhotoMove.Boundary.DELETED;
      try (PhotoMove stopped = f.move(ledger, history, reached -> { if (reached == crash) throw new InterruptedMove(); })) {
        stopped.begin(preflight(stopped, stopped.plan(destination), () -> false));
        if (migration != VersionThreeHistorical.QUEUED) expectCrash(stopped, crash);
      }
      copyFixtureSchemaToVersionThree(f);
      try (PhotoMoveStore upgraded = new PhotoMoveStore(f.isolatedContext()); PhotoMove reopened = f.move(ledger, history, ignored -> {})) {
        var item = upgraded.next();
        require(upgraded.getReadableDatabase().getVersion() == 4 && item.hasHistoricalProof() && item.targetPin() == null
            && java.util.Objects.equals(previous, upgraded.completed(seed.history().getId())),
            "v3 historical migration preserves existing proof without fabricating target binding");
        if (migration == VersionThreeHistorical.QUEUED) {
          require(item.target() == null && item.stage() == PhotoMoveStore.QUEUED && reopened.run(() -> false, ignored -> {}).done() == 1,
              "Migrated v3 historical queue can allocate and pin a fresh target");
          checkReplacement(f, history, archive, seed, e2Before, destination);
          requireCompletedProof(f, seed, f.rows(destination).get(0).uri());
        } else if (migration == VersionThreeHistorical.ATTACHED) {
          Uri target = Uri.parse(item.target());
          byte[] sentinel = jpeg(0xff982d59);
          f.write(target, sentinel);
          expectMoveFailure(() -> reopened.run(() -> false, ignored -> {}), "An unpinned allocated v3 target must never be overwritten");
          require(item.equals(upgraded.next()) && Arrays.equals(sentinel, f.read(target))
              && Arrays.equals(seed.bytes(), f.read(seed.uri())) && history.moveEvidence().equals(List.of(seed.history())),
              "Reject unpinned pending target without changing either copy or selected history");
          reopened.abandon();
          require(f.row(target).pending() && Arrays.equals(sentinel, f.read(target)) && Arrays.equals(seed.bytes(), f.read(seed.uri()))
              && history.moveEvidence().equals(List.of(seed.history())) && upgraded.completed(seed.history().getId()) == null,
              "Ending an old unpinned batch never deletes its pending target or changes the historical source");
        } else {
          Uri target = Uri.parse(item.target());
          require(previous != null && item.pendingCompletion() != null && previous.equals(item.sourceProof())
              && f.rows(secondary).isEmpty(), "The v3 reverse move has a durable completion and only its replacement remains");
          var ended = reopened.abandon();
          require(ended.done() == 1 && ended.total() == 1 && item.pendingCompletion().equals(requireCompletedProof(f, seed, target)),
              "Independent v3 pending proof verifies the surviving target and promotes it on End");
          checkReplacement(f, history, archive, seed, e2Before, PRIMARY);
        }
        require(reopened.batch() == null && ledger.receiptCount() == 0, "v3 recovery closes only the synthetic journal without writing camera history");
      }
    }
  }

  private static void copyFixtureSchemaToVersionThree(Fixture f) {
    // v4 only adds the nullable target_pin column. Remove it from this disposable fixture to model real on-disk v3 rows.
    try (SQLiteDatabase legacy = f.isolatedContext().openOrCreateDatabase("photo-moves.db", 0, null)) {
      require(legacy.getVersion() == 4, "Start the v3 compatibility fixture with a known v4 layout");
      legacy.beginTransaction();
      try {
        legacy.execSQL("ALTER TABLE items DROP COLUMN target_pin");
        legacy.setVersion(3);
        legacy.setTransactionSuccessful();
      } finally {
        legacy.endTransaction();
      }
    }
  }

  static String captureUi(ArchiveInstrumentation test, String secondaryArg, int width, float font) throws Exception {
    Context base = test.getTargetContext();
    String secondary = isolatedVolume(base, secondaryArg);
    SharedPreferences settings = base.getSharedPreferences("settings", 0);
    String previous = settings.getString("photo_volume", null);
    HistoryDb history = HistoryDb.Companion.get(base);
    var ledger = CameraImportLedger.get(base);
    require(history.importDestinations().isEmpty() && ledger.importDestinations().isEmpty(),
        "The isolated UI test requires clean import histories");
    try (PhotoMoveStore journal = new PhotoMoveStore(base)) {
      require(journal.batch() == null && !PhotoMoveService.state().running(), "The isolated UI test requires no active move");
    }
    File captures = new File(base.getCacheDir(), "photo-storage-checks");
    require(captures.isDirectory() || captures.mkdirs(), "Create the archive preflight capture directory");
    String profile = "archive-" + width + "-" + font;
    try (Fixture f = new Fixture(base, secondary); ArchiveStore archive = E2Settings.openRecords(base)) {
      var syntheticKeys = new java.util.ArrayList<String>();
      try {
        Seed good = seed(f, history, archive, "CONFIRMED.JPG", 0xff845a51, false, false);
        syntheticKeys.add(good.archiveKey());
        Seed bad = seed(f, history, archive, "EXCLUDED.JPG", 0xff416c5c, true, true);
        syntheticKeys.add(bad.archiveKey());
        var before = history.moveEvidence();
        require(PhotoStorage.select(base, secondary), "Select the isolated UI destination");
        captureConfirmation(test, base, secondary, captures, profile);
        try (PhotoMoveStore journal = new PhotoMoveStore(base)) {
          require(journal.batch() == null && !PhotoMoveService.state().running() && f.rows(secondary).isEmpty()
              && history.moveEvidence().equals(before) && Arrays.equals(good.bytes(), f.read(good.uri()))
              && Arrays.equals(bad.bytes(), f.read(bad.uri())), "Cancelling and backing out of the native confirmation does not begin a move");
        }
      } finally {
        history.resetHistory(MODEL, f.id);
        for (String key : syntheticKeys) archive.getWritableDatabase().delete("photos", "key=?", new String[]{key});
      }
    } finally {
      var restore = settings.edit();
      if (previous == null) restore.remove("photo_volume");
      else restore.putString("photo_volume", previous);
      require(restore.commit(), "Restore the isolated UI storage selection");
    }
    return "PASS archive preflight confirmation: one verified photo, one archive exclusion, cancel and back without moving; "
        + test.photoStorageEffectiveWidth() + "dp, font " + font + " " + captures.getAbsolutePath();
  }

  private static void captureConfirmation(ArchiveInstrumentation test, Context base, String secondary, File captures, String profile)
      throws Exception {
    Activity activity = test.startActivitySync(new Intent(base, PhotoStorageActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      UiAutomation automation = test.getUiAutomation();
      String label = "取り込み済みの写真を移動";
      Predicate<AccessibilityNodeInfo> moveButton = node -> hasText(node, label) && node.isEnabled();
      var start = awaitStorageControl(automation, moveButton);
      assertWindowWidth(test, activity);
      assertTouchTarget(test, activity, start, label);
      Predicate<AccessibilityNodeInfo> awaitingCheck = node -> mentions(node, "履歴と一致した写真 0枚")
          && mentions(node, "内容の確認待ち 2枚") && !mentions(node, "内容を照合できなかった");
      awaitStorageControl(automation, awaitingCheck);
      captureSettled(test, automation, captures, profile + "-settings", awaitingCheck);
      String card = PhotoStorage.label(base, secondary);
      Predicate<AccessibilityNodeInfo> confirmed = node -> mentions(node, card + "に1枚")
          && mentions(node, "内容を照合できなかった1枚は元の場所に残ります。")
          && !mentions(node, "内容の確認待ち");
      click(awaitStorageControl(automation, moveButton));
      awaitStorageControl(automation, confirmed);
      captureSettled(test, automation, captures, profile + "-confirmation", confirmed);
      assertDialogAction(activity, awaitStorageControl(automation, node -> hasText(node, "移動")), "移動");
      var cancel = awaitStorageControl(automation, node -> hasText(node, "キャンセル"));
      assertDialogAction(activity, cancel, "キャンセル");
      click(cancel);
      click(awaitStorageControl(automation, moveButton));
      awaitStorageControl(automation, confirmed);
      long when = SystemClock.uptimeMillis();
      require(automation.injectInputEvent(new KeyEvent(when, when, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true)
          && automation.injectInputEvent(new KeyEvent(when, when, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true),
          "Native back dismisses the archive confirmation");
      awaitStorageControl(automation, moveButton);
    } finally {
      test.runOnMainSync(activity::finish);
      test.waitForIdleSync();
    }
  }

  private static void assertDialogAction(Activity activity, AccessibilityNodeInfo node, String action) {
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    int touch = GalleryUi.dp(activity, 48);
    require(node.isClickable() && node.isEnabled() && bounds.width() >= touch && bounds.height() >= touch
        && availableDisplay(activity).contains(bounds), "Reachable native confirmation action: " + action);
  }

  private static void expectCrash(PhotoMove mover, PhotoMove.Boundary boundary) throws Exception {
    try {
      mover.run(() -> false, ignored -> {});
      throw new PhotoStorageChecks.CheckFailure("Expected synthetic interruption at " + boundary);
    } catch (InterruptedMove expected) {
      require(mover.batch() != null && mover.batch().done() == 0, "The interrupted batch keeps exactly one uncompleted operation");
    }
  }

  private static void expectMoveFailure(CheckedOperation operation, String label) throws Exception {
    try {
      operation.run();
      throw new PhotoStorageChecks.CheckFailure(label);
    } catch (PhotoMove.Failure expected) {
      // Verify the exact state left by the rejection separately.
    }
  }

  private static final class InterruptedMove extends RuntimeException {}

  @FunctionalInterface private interface CheckedOperation { void run() throws Exception; }

  private static void expectInterrupted(CheckedOperation operation, String label) throws Exception {
    try {
      operation.run();
      throw new PhotoStorageChecks.CheckFailure(label);
    } catch (InterruptedIOException expected) {
      // Verify durable state separately.
    }
  }
}
