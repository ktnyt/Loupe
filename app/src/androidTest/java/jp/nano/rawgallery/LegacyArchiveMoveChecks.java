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
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    checkLostHistory(base, secondary);
    for (var boundary : List.of(PhotoMove.Boundary.PUBLISHED, PhotoMove.Boundary.HISTORY_UPDATED,
        PhotoMove.Boundary.REMAPPED, PhotoMove.Boundary.DELETED)) {
      checkProofRecovery(base, secondary, boundary);
    }
    checkProofRollback(base, secondary);
    checkRoundTrip(base, secondary);
    checkVersionTwoResume(base, secondary);
    return "PASS legacy archive move: strict metadata and bounded epoch/generation proof, read-only preflight, "
        + "selected history, v2-to-v3 migration, proof interruption, rollback and reverse move";
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
        && LegacyArchiveMoveProof.parse(receipt.key(), new JSONObject(value.toString()).put("provider", "other")) == null,
        "Proof requires a typed, exact and verified E2 payload identity");
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
        require(reopened.getReadableDatabase().getVersion() == 3 && item != null && proof.equals(item.archiveProof())
            && item.expectedSha() == null && item.legacyIds().equals(Long.toString(seed.history().getId()))
            && reopened.completed(seed.history().getId()) == null,
            "The reopened v3 journal retains the initial proof without prematurely promoting completed evidence");
        if (boundary == PhotoMove.Boundary.DELETED) {
          var pending = item.pendingCompletion();
          require(pending != null && pending.historyId() == seed.history().getId() && pending.source().equals(item.target())
              && pending.sha().equals(proof.sha()) && pending.historyFingerprint().equals(proof.historyFingerprint()),
              "Verified target proof is already durable when interrupted after source deletion");
        }
      }
      require(boundary == PhotoMove.Boundary.DELETED ? f.rows(PRIMARY).isEmpty() : Arrays.equals(seed.bytes(), f.read(seed.uri())),
          "Interrupting preserves the original until the verified deletion boundary");
      try (PhotoMove resumed = f.move(ledger, history, ignored -> {})) {
        if (boundary == PhotoMove.Boundary.DELETED) resumed.abandon();
        else require(resumed.run(() -> false, ignored -> {}).done() == 1, "The interrupted archive-only move finishes once on restart");
        require(resumed.batch() == null, "Recovery or post-delete roll-forward clears the journal");
      }
      checkReplacement(f, history, archive, seed, e2Before, secondary);
      requireCompletedProof(f, seed, f.rows(secondary).get(0).uri());
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
        require(store.getReadableDatabase().getVersion() == 3 && store.revision() == 12 && store.next().archiveProof() == null,
            "Existing v2 journal upgrades additively without inventing archive proof or changing the revision");
        require(mover.run(() -> false, ignored -> {}).done() == 1 && mover.batch() == null && f.rows(PRIMARY).isEmpty(),
            "The ordinary queued v2 move resumes on v3 without an E2 record");
        Uri replacement = f.rows(secondary).get(0).uri();
        require(Arrays.equals(bytes, f.read(replacement)) && history.importDestinations().equals(Set.of(replacement.toString())),
            "Migrated v2 preserves the original bytes and normal legacy update semantics");
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
      captureSettled(test, automation, captures, profile + "-settings", moveButton);
      String card = PhotoStorage.label(base, secondary);
      Predicate<AccessibilityNodeInfo> confirmed = node -> mentions(node, card + "に1枚")
          && mentions(node, "バックアップとの照合を確認できませんでした");
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
