package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import jp.nano.rawgallery.LegacyArchiveMoveProof.Rejection;
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
    checkInventoryCountGroups();
    checkReadOnlyPreflight(base, secondary);
    for (String state : List.of("verified", "pending", "released")) checkLateArchiveConflict(base, secondary, state);
    checkHistoricalCaptureGuard(base, secondary);
    checkPublishedTargetDrift(base, secondary);
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
    checkRollbackPreservesOlderBindings(base, secondary, PhotoMove.Boundary.HISTORY_UPDATED, false);
    checkRollbackPreservesOlderBindings(base, secondary, PhotoMove.Boundary.REMAPPED, true);
    checkPublishedExactBindingConflict(base, secondary);
    checkDeletedSourceWithResetHistory(base, secondary, false);
    checkDeletedSourceWithResetHistory(base, secondary, true);
    checkStaleSourceWithResetHistory(base, secondary);
    checkLockedAbandon(base, secondary);
    checkRoundTrip(base, secondary);
    checkVersionTwoResume(base, secondary);
    checkVersionThreeOrdinaryResume(base, secondary);
    for (var migration : VersionThreeHistorical.values()) checkVersionThreeHistorical(base, secondary, migration);
    return "PASS legacy archive move: strict metadata and bounded epoch/generation proof, read-only preflight, "
        + "selected history, v2/v3-to-v4 migration, pinned targets, proof interruption, rollback and reverse move. "
        + "Capture-time coverage is at the metadata predicate level using observed known and unknown MediaStore rows.";
  }

  static String checkSnapshots(Instrumentation test, String secondaryArg, String cadenceArg) throws Exception {
    Context base = test.getTargetContext();
    String secondary = isolatedVolume(base, secondaryArg);
    if (cadenceArg != null) {
      int cadence = Integer.parseInt(cadenceArg);
      require(List.of(500, 1000, 2000).contains(cadence), "Use one of the contract cadences");
      return checkSnapshotCadence(base, secondary, cadence);
    }
    checkRevisionRetry(base, secondary);
    for (FenceExit exit : FenceExit.values()) checkWriteFenceRelease(base, secondary, exit);
    checkWriteFenceWait(base, secondary, false);
    checkWriteFenceWait(base, secondary, true);
    for (ReceiptChange change : ReceiptChange.values()) {
      checkConcurrentReceiptChange(base, secondary, change, false);
      checkConcurrentReceiptChange(base, secondary, change, true);
    }
    checkArchiveReadFailures(base, secondary);
    checkAliasRejection(base, secondary);
    checkPendingTargetRejection(base, secondary, false);
    checkPendingTargetRejection(base, secondary, true);
    return "PASS archive snapshots: previous revision fence rejects, 2 unrelated commits retry, "
        + "the final scan defers local writes and still rejects direct SQL churn, "
        + "the write fence times out and honors cancellation while waiting, "
        + "cancellation and exceptions release it, changed evidence blocks moves and resume, "
        + "and the corrupt archive stays unchanged.";
  }

  private static void checkRevisionRetry(Context base, String secondary) throws Exception {
    try (Fixture f = new Fixture(base, secondary); CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         ArchiveStore archive = E2Settings.openRecords(f.isolatedContext());
         ArchiveStore writer = E2Settings.openRecords(f.isolatedContext())) {
      Seed seed = seed(f, history, archive, "SNAPSHOT-RETRY.JPG", 0xff598093, false, false);
      String receipt = archive.get(seed.archiveKey()).toString();
      Context context = f.isolatedContext();
      String uri = PhotoMove.galleryUri(seed.uri()).toString();
      var initial = new LegacyArchiveMoveProof.Records(context).read(() -> false);
      require(initial.rejection() == null && initial.scanAttempts() == 1 && initial.records().containsKey(uri),
          "The unmodified archive has stable authority before any competing write");
      // The old single-attempt check discarded the entire scan on this same real revision transition.
      try (SQLiteDatabase reader = openReadOnlyArchive(context)) {
        long before = revision(reader);
        try (Cursor rows = reader.rawQuery("SELECT key,state,payload FROM photos", null)) {
          require(rows.moveToFirst() && seed.archiveKey().equals(rows.getString(0)), "The old scan saw the selected receipt");
        }
        putUnrelated(writer, 0);
        require(before != revision(reader), "The old postscan revision fence would return empty despite the valid receipt");
      }
      SQLiteDatabase direct = writer.getWritableDatabase();
      var commits = new AtomicInteger();
      var keepWriting = new AtomicBoolean(true);
      var reader = new LegacyArchiveMoveProof.Records(context, () -> {
        if (keepWriting.get()) putUnrelatedSql(direct, commits.incrementAndGet());
      });
      var failed = reader.read(() -> false);
      require(failed.rejection() == Rejection.ARCHIVE_REVISION_CHURN && failed.scanAttempts() == 3
          && failed.records().isEmpty() && commits.get() == 3,
          "Direct SQL can still invalidate the guarded last scan, with no partial snapshot accepted");
      keepWriting.set(false);
      var recovered = reader.read(() -> false);
      var cached = reader.read(() -> false);
      require(recovered.rejection() == null && recovered.scanAttempts() == 1
          && cached.rejection() == null && cached.scanAttempts() == 0
          && cached.records().equals(recovered.records())
          && cached.records().get(uri).equals(initial.records().get(uri)),
          "A later stable scan recovers the same receipt and caches its full revision");
      writer.put(archive.get(seed.archiveKey()), "pending");
      var invalidated = reader.read(() -> false);
      require(invalidated.rejection() == null && invalidated.scanAttempts() == 1
          && !invalidated.records().get(uri).get(0).verified(), "A cached verified receipt is invalidated by a committed state change");
      writer.put(new JSONObject(receipt), "verified");

      require(PhotoStorage.select(context, secondary), "Choose the synthetic SD destination");
      var stop = new AtomicBoolean();
      var cancelReader = new LegacyArchiveMoveProof.Records(context, () -> stop.set(true));
      expectInterrupted(() -> cancelReader.read(stop::get), "Cancellation after closing the cursor does not cache a scan");
      require(cancelReader.read(() -> false).scanAttempts() == 1, "A cancelled scan cannot seed the cache");
      expectInterrupted(() -> reader.read(() -> true), "Cancellation wins even when the cache would match");

      var scans = new AtomicInteger();
      try (PhotoMove mover = observeScans(f, ledger, history, () -> {
        int attempt = scans.incrementAndGet();
        if (attempt <= 2) writeArchive(() -> putUnrelated(writer, 10 + attempt));
      })) {
        PhotoMove.Plan plan = preflight(mover, mover.plan(secondary), () -> false);
        require(plan.items().size() == 1, "The fixture source has one confirmed valid proof");
        mover.begin(plan);
        require(mover.run(() -> false, ignored -> {}).done() == 1 && scans.get() == 3,
            "The move retries two independent commits, accepts the third scan, and reuses that stable cache");
      }
      checkReplacement(f, history, archive, seed, receipt, secondary);
      requireCompletedProof(f, seed, f.rows(secondary).get(0).uri());
    }
  }

  private enum FenceExit { SUCCESS, CANCELLED, EXCEPTION }

  private static void checkWriteFenceRelease(Context base, String secondary, FenceExit exit) throws Exception {
    try (Fixture f = new Fixture(base, secondary); ArchiveStore archive = E2Settings.openRecords(f.isolatedContext());
         ArchiveStore writer = E2Settings.openRecords(f.isolatedContext())) {
      SQLiteDatabase direct = archive.getWritableDatabase();
      writer.getWritableDatabase();
      long revisionBefore = archive.indexRevision();
      var startWriter = new CountDownLatch(1);
      var writerDone = new CountDownLatch(1);
      var writerFailure = new AtomicReference<Exception>();
      Thread background = new Thread(() -> {
        try {
          if (!startWriter.await(5, TimeUnit.SECONDS)) throw new IOException("Final scan did not start the writer");
          putUnrelated(writer, 3);
        } catch (Exception failure) {
          writerFailure.set(failure);
        } finally {
          writerDone.countDown();
        }
      }, "archive-fence-fixture-writer");
      var stop = new AtomicBoolean();
      var injected = new IOException("Synthetic final scan failure");
      var observer = new LegacyArchiveMoveProof.ScanObserver() {
        private int attempt;

        @Override public void beforeScan(int number) {
          attempt = number;
          require(ArchiveStore.E2_PHOTO_UPSERT.isHeldByCurrentThread() == (attempt == 3),
              "Only the final scan holds the upsert fence");
        }

        @Override public void afterScan() throws IOException {
          if (attempt < 3) {
            putUnrelatedSql(direct, attempt);
            return;
          }
          startWriter.countDown();
          long deadline = SystemClock.elapsedRealtime() + 2_000;
          while (!ArchiveStore.E2_PHOTO_UPSERT.hasQueuedThread(background)
              && writerDone.getCount() != 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(5);
          require(ArchiveStore.E2_PHOTO_UPSERT.hasQueuedThread(background) && writerDone.getCount() == 1,
              "The normal E2 writer waits without committing inside the final scan");
          if (exit == FenceExit.CANCELLED) stop.set(true);
          if (exit == FenceExit.EXCEPTION) throw injected;
        }
      };
      var reader = new LegacyArchiveMoveProof.Records(f.isolatedContext(), observer);
      background.start();
      try {
        switch (exit) {
          case SUCCESS -> {
            var result = reader.read(stop::get);
            require(result.rejection() == null && result.scanAttempts() == 3
                && !result.records().containsKey("fixture://archive/3"),
                "The last fenced read used only the revision before the queued write");
          }
          case CANCELLED -> expectInterrupted(() -> reader.read(stop::get), "Cancellation releases the final scan fence");
          case EXCEPTION -> {
            try {
              reader.read(stop::get);
              throw new CheckFailure("The injected scan exception was suppressed");
            } catch (IOException failure) {
              require(failure == injected, "The final scan propagates the original exception");
            }
          }
        }
      } finally {
        startWriter.countDown();
        background.join(6_000);
      }
      require(!background.isAlive() && writerFailure.get() == null && archive.indexRevision() - revisionBefore == 3
          && archive.get("unrelated-3") != null, "The deferred writer commits after every final scan exit: " + exit);
    }
  }

  private static void checkWriteFenceWait(Context base, String secondary, boolean cancel) throws Exception {
    try (Fixture f = new Fixture(base, secondary); ArchiveStore archive = E2Settings.openRecords(f.isolatedContext());
         ArchiveStore writer = E2Settings.openRecords(f.isolatedContext())) {
      SQLiteDatabase direct = archive.getWritableDatabase();
      writer.getWritableDatabase();
      var startBlocker = new CountDownLatch(1);
      var lockHeld = new CountDownLatch(1);
      var releaseBlocker = new CountDownLatch(1);
      var stopped = new AtomicBoolean();
      var waits = new AtomicLong();
      var blockerFailure = new AtomicReference<Exception>();
      var lock = ArchiveStore.E2_PHOTO_UPSERT;
      Thread reading = Thread.currentThread();
      Thread blocker = new Thread(() -> {
        try {
          if (!startBlocker.await(5, TimeUnit.SECONDS)) throw new IOException("The second scan did not start the blocker");
          if (!lock.tryLock(5, TimeUnit.SECONDS)) throw new IOException("The fixture could not hold the write fence");
          try {
            lockHeld.countDown();
            long deadline = SystemClock.elapsedRealtime() + 1_500;
            boolean queued = lock.hasQueuedThread(reading);
            while (!queued && SystemClock.elapsedRealtime() < deadline) {
              LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
              queued = lock.hasQueuedThread(reading);
            }
            if (!queued) throw new IOException("The reader did not queue on the held fence");
            waits.set(SystemClock.elapsedRealtime());
            if (cancel) stopped.set(true);
            if (!releaseBlocker.await(5, TimeUnit.SECONDS)) throw new IOException("The reader did not finish within its wait bound");
          } finally {
            lock.unlock();
          }
          putUnrelated(writer, 3);
        } catch (Exception failure) {
          blockerFailure.set(failure);
        }
      }, "archive-fence-fixture-blocker");
      var scans = new AtomicInteger();
      var reader = new LegacyArchiveMoveProof.Records(f.isolatedContext(), () -> {
        int count = scans.incrementAndGet();
        if (count > 2) return;
        putUnrelatedSql(direct, count);
        if (count != 2) return;
        startBlocker.countDown();
        writeArchive(() -> require(lockHeld.await(2, TimeUnit.SECONDS), "Acquire the test fence before the final scan"));
      });
      blocker.start();
      try {
        if (cancel) {
          expectInterrupted(() -> reader.read(stopped::get), "A reader already queued on the write fence honors cancellation");
          require(stopped.get() && scans.get() == 2 && waits.get() > 0
              && SystemClock.elapsedRealtime() - waits.get() < 1_000, "Cancel promptly without beginning a third scan");
        } else {
          var outcome = reader.read(stopped::get);
          require(waits.get() > 0 && outcome.rejection() == Rejection.ARCHIVE_WRITE_BUSY
              && outcome.scanAttempts() == 2 && scans.get() == 2 && outcome.records().isEmpty(),
              "The held fence times out without counting an unstarted scan or publishing a snapshot");
        }
      } finally {
        startBlocker.countDown();
        releaseBlocker.countDown();
        blocker.join(6_000);
      }
      require(!blocker.isAlive() && blockerFailure.get() == null && archive.get("unrelated-3") != null,
          "A normal writer runs after the timed-out or cancelled reader leaves");
      var subsequent = reader.read(() -> false);
      require(subsequent.rejection() == null && subsequent.scanAttempts() == 1
          && subsequent.records().size() == 3 && reader.read(() -> false).scanAttempts() == 0,
          "Only the later stable read publishes a complete cached snapshot");
    }
  }

  private enum ReceiptChange {
    DELETED(Rejection.RECEIPT_MISSING), SHA_CHANGED(Rejection.SAVED_PROOF), STATE(Rejection.RECEIPT_UNVERIFIED),
    METADATA(Rejection.RECEIPT_METADATA), VERIFIED_DUPLICATE(Rejection.RECEIPT_DUPLICATE),
    PENDING_DUPLICATE(Rejection.RECEIPT_DUPLICATE), RELEASED_DUPLICATE(Rejection.RECEIPT_DUPLICATE);

    final Rejection reason;
    ReceiptChange(Rejection reason) { this.reason = reason; }

    void write(ArchiveStore writer, String key, JSONObject receipt) throws Exception {
      switch (this) {
        case DELETED -> require(writer.getWritableDatabase().delete("photos", "key=?", new String[]{key}) == 1,
            "The competing connection deleted the selected receipt");
        case SHA_CHANGED -> writer.put(receipt.put("sha", SHA), "verified");
        case STATE -> writer.put(receipt, "pending");
        case METADATA -> writer.put(receipt.put("size", receipt.getLong("size") + 1), "verified");
        case VERIFIED_DUPLICATE -> writer.put(receipt.put("key", "e".repeat(32)), "verified");
        case PENDING_DUPLICATE -> writer.put(receipt.put("key", "e".repeat(32)), "pending");
        case RELEASED_DUPLICATE -> writer.put(receipt.put("key", "e".repeat(32)), "released");
      }
    }
  }

  private static void checkConcurrentReceiptChange(Context base, String secondary, ReceiptChange change, boolean published)
      throws Exception {
    try (Fixture f = new Fixture(base, secondary); CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         ArchiveStore archive = E2Settings.openRecords(f.isolatedContext()); ArchiveStore writer = E2Settings.openRecords(f.isolatedContext());
         PhotoMoveStore journal = new PhotoMoveStore(f.isolatedContext())) {
      Seed seed = seed(f, history, archive, "SNAPSHOT-CHANGED.JPG", 0xff57688b, false, false);
      JSONObject receipt = archive.get(seed.archiveKey());
      require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the synthetic SD destination");
      var stop = new AtomicBoolean();
      try (PhotoMove preparing = f.move(ledger, history, boundary -> {
        if (boundary == PhotoMove.Boundary.BEFORE_HISTORY_VERIFY) stop.set(true);
      })) {
        PhotoMove.Plan plan = preflight(preparing, preparing.plan(secondary), () -> false);
        require(plan.items().size() == 1, "Confirm the original receipt before changing it");
        preparing.begin(plan);
        if (published) expectInterrupted(() -> preparing.run(stop::get, ignored -> {}), "Stop at the published resume boundary");
      }
      var before = journal.next();
      require(before.stage() == (published ? PhotoMoveStore.UPDATING_HISTORY : PhotoMoveStore.QUEUED),
          "Use the normal durable queued or published stage");
      var scans = new AtomicInteger();
      try (PhotoMove resumed = observeScans(f, ledger, history, () -> {
        if (scans.incrementAndGet() == 1) writeArchive(() -> change.write(writer, seed.archiveKey(), receipt));
      })) {
        try {
          resumed.run(() -> false, ignored -> {});
          throw new CheckFailure("The move accepted changed archive evidence");
        } catch (PhotoMove.Failure rejected) {
          require(rejected.reason.equals(change.reason.code()) && rejected.scanAttempts == 2 && scans.get() == 2,
              "A fresh stable scan identifies the actual receipt failure: case=" + change
                  + " expected=" + change.reason.code() + " actual=" + rejected.reason
                  + " attempts=" + rejected.scanAttempts + " observed_scans=" + scans.get());
        }
      }
      require(before.equals(journal.next()) && history.moveEvidence().equals(List.of(seed.history()))
          && ledger.receiptCount() == 0 && journal.completed(seed.history().getId()) == null
          && Arrays.equals(seed.bytes(), f.read(seed.uri())), "A receipt change cannot advance history or remove the original");
      if (published) {
        var targets = f.rows(secondary);
        require(targets.size() == 1 && !targets.get(0).pending() && before.target().equals(targets.get(0).uri().toString())
            && Arrays.equals(seed.bytes(), f.read(targets.get(0).uri())), "Preserve the same already-published copy");
      } else require(f.rows(secondary).isEmpty(), "Do not allocate a target after rejecting the queued proof");
    }
  }

  private static void checkArchiveReadFailures(Context base, String secondary) throws Exception {
    try (Fixture f = new Fixture(base, secondary)) {
      Context context = f.isolatedContext();
      var scans = new AtomicInteger();
      var reader = new LegacyArchiveMoveProof.Records(context, scans::incrementAndGet);
      require(reader.read(() -> false).rejection() == Rejection.ARCHIVE_OPEN && scans.get() == 0
          && !context.getDatabasePath(E2Settings.DATABASE).exists(), "A read failure never creates the archive");
      checkCorruptArchiveIsPreserved(context);
      try (ArchiveStore writer = E2Settings.openRecords(context)) {
        SQLiteDatabase db = writer.getWritableDatabase();
        var empty = reader.read(() -> false);
        require(empty.rejection() == null && empty.records().isEmpty() && empty.scanAttempts() == 1,
            "Distinguish a stable valid empty archive from a failed read");
        db.execSQL("UPDATE index_revision SET value='invalid' WHERE id=1");
        var invalid = reader.read(() -> false);
        require(invalid.rejection() == Rejection.ARCHIVE_REVISION_INVALID && invalid.scanAttempts() == 0,
            "A malformed revision is a terminal error without any scan");
        db.execSQL("UPDATE index_revision SET value=1 WHERE id=1");
        db.execSQL("INSERT INTO photos VALUES ('bad','verified','[')");
        var malformed = reader.read(() -> false);
        require(malformed.rejection() == Rejection.ARCHIVE_JSON_INVALID && malformed.scanAttempts() == 1,
            "A malformed row stops a scan without retrying it or returning an earlier result");
        db.execSQL("DELETE FROM photos");
        db.execSQL("WITH RECURSIVE rows(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM rows WHERE n<100000) "
            + "INSERT INTO photos SELECT CAST(n AS TEXT),'pending','{}' FROM rows");
        var maximum = reader.read(() -> false);
        require(maximum.rejection() == null && maximum.scanAttempts() == 1
            && maximum.records().values().stream().mapToInt(List::size).sum() == 100_000,
            "The archive can scan all rows at the accepted bound");
        db.execSQL("INSERT INTO photos VALUES ('overflow','pending','{}')");
        var limited = reader.read(() -> false);
        require(limited.rejection() == Rejection.ARCHIVE_ROW_LIMIT && limited.scanAttempts() == 1 && limited.records().isEmpty(),
            "The first extra row rejects without returning a partial result");
        db.execSQL("DELETE FROM photos WHERE key='overflow'");
        require(reader.read(() -> false).scanAttempts() == 1 && reader.read(() -> false).scanAttempts() == 0,
            "The rejected snapshot is never published to the cache");
      }
    }
  }

  private static void checkCorruptArchiveIsPreserved(Context context) throws Exception {
    File corrupt = File.createTempFile("corrupt-photo-archive-", ".db", context.getCacheDir());
    byte[] badBytes = new byte[4096];
    Arrays.fill(badBytes, (byte) 0x5a);
    try {
      Files.write(corrupt.toPath(), badBytes);
      Context damaged = new ContextWrapper(context) {
        @Override public File getDatabasePath(String name) {
          return E2Settings.DATABASE.equals(name) ? corrupt : super.getDatabasePath(name);
        }
      };
      var read = new LegacyArchiveMoveProof.Records(damaged).read(() -> false);
      require((read.rejection() == Rejection.ARCHIVE_OPEN || read.rejection() == Rejection.ARCHIVE_READ)
          && read.scanAttempts() == 0 && Files.exists(corrupt.toPath())
          && Arrays.equals(badBytes, Files.readAllBytes(corrupt.toPath())),
          "A corrupt archive is refused and its file is neither deleted nor rewritten");
    } finally {
      Files.deleteIfExists(corrupt.toPath());
    }
  }

  private static void checkAliasRejection(Context base, String secondary) throws Exception {
    try (Fixture f = new Fixture(base, secondary); CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         ArchiveStore archive = E2Settings.openRecords(f.isolatedContext());
         PhotoMoveStore journal = new PhotoMoveStore(f.isolatedContext())) {
      Seed seed = seed(f, history, archive, "DUPLICATE-MEDIA-ALIAS.JPG", 0xff57688b, false, false);
      Context context = f.isolatedContext();
      require(PhotoStorage.select(context, secondary), "Choose the synthetic SD destination");
      try (PhotoMove preparing = f.move(ledger, history, ignored -> {})) {
        preparing.begin(preflight(preparing, preparing.plan(secondary), () -> false));
      }
      long id = ContentUris.parseId(seed.uri());
      Uri alias = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
      var aliasQueries = new AtomicInteger();
      var before = journal.next();
      require(before.archiveProof().epoch().equals(LegacyArchiveMoveProof.epoch(context)),
          "The fixture uses the same live app-UID epoch as the confirmed proof");
      // Supply two volumes for this aggregate query only; the real resolver handles every other operation.
      try (PhotoMove resumed = new PhotoMove(context, ledger, history, new PhotoMoveStore(context), ignored -> {},
          f.resolver::openInputStream, f.resolver::openFileDescriptor, () -> {}, (uri, columns, args) -> {
            if (!alias.equals(uri)) return f.resolver.query(uri, columns, args, null);
            aliasQueries.incrementAndGet();
            MatrixCursor rows = new MatrixCursor(columns, 2);
            for (String volume : List.of(PRIMARY, secondary)) {
              var row = rows.newRow();
              row.add(_ID, id);
              row.add(VOLUME_NAME, volume);
            }
            return rows;
          })) {
        expectMoveReason(PhotoMove.FailureReason.SOURCE_ALIAS, () -> resumed.run(() -> false, ignored -> {}),
            "写真を一意に特定できません。");
      }
      require(aliasQueries.get() == 1 && before.equals(journal.next()) && f.rows(secondary).isEmpty()
          && history.moveEvidence().equals(List.of(seed.history())) && Arrays.equals(seed.bytes(), f.read(seed.uri())),
          "An ambiguous source alias cannot copy the original or advance its history");
    }
  }

  private static void checkPendingTargetRejection(Context base, String secondary, boolean duringVerification) throws Exception {
    try (Fixture f = new Fixture(base, secondary); CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         ArchiveStore archive = E2Settings.openRecords(f.isolatedContext());
         PhotoMoveStore journal = new PhotoMoveStore(f.isolatedContext())) {
      Seed seed = seed(f, history, archive, "TARGET-PUBLICATION-CHANGED.JPG", 0xff57688b, false, false);
      require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the synthetic SD destination");
      try (PhotoMove preparing = f.move(ledger, history, boundary -> {
        if (boundary == PhotoMove.Boundary.ATTACHED) throw new InterruptedMove();
      })) {
        preparing.begin(preflight(preparing, preparing.plan(secondary), () -> false));
        expectCrash(preparing, PhotoMove.Boundary.ATTACHED);
      }
      var before = journal.next();
      Uri target = Uri.parse(before.target());
      require(before.stage() == PhotoMoveStore.COPYING && f.row(target).pending(), "Use the pinned unpublished copy");
      byte[] copiedBytes = duringVerification ? seed.bytes() : jpeg(0xff216759);
      if (!duringVerification) {
        f.write(target, copiedBytes);
        publishFixtureTarget(f, target);
      }
      var publications = new AtomicInteger();
      try (PhotoMove resumed = new PhotoMove(f.isolatedContext(), ledger, history, new PhotoMoveStore(f.isolatedContext()),
          ignored -> {}, uri -> {
            if (duringVerification && target.getPath().equals(uri.getPath())) {
              publishFixtureTarget(f, target);
              publications.incrementAndGet();
            }
            return f.resolver.openInputStream(uri);
          })) {
        expectMoveReason(PhotoMove.FailureReason.TARGET_PIN, () -> resumed.run(() -> false, ignored -> {}),
            duringVerification ? "照合中に写真が変更されました。" : "移動先のファイルを確認できません。");
      }
      require(publications.get() == (duringVerification ? 1 : 0) && before.equals(journal.next())
          && history.moveEvidence().equals(List.of(seed.history())) && journal.completed(seed.history().getId()) == null
          && Arrays.equals(seed.bytes(), f.read(seed.uri())) && Arrays.equals(copiedBytes, f.read(target))
          && !f.row(target).pending(), "Changed target publication preserves both files and the historical journal");
    }
  }

  private static void publishFixtureTarget(Fixture f, Uri target) {
    var published = new ContentValues();
    published.put(IS_PENDING, 0);
    require(f.resolver.update(target, published, null, null) == 1, "Publish the synthetic target at the intended boundary");
  }

  private static void expectMoveReason(PhotoMove.FailureReason reason, CheckedOperation operation, String message)
      throws Exception {
    try {
      operation.run();
      throw new CheckFailure("The move accepted changed identity");
    } catch (PhotoMove.Failure rejected) {
      require(reason.code().equals(rejected.reason) && rejected.scanAttempts == 0 && message.equals(rejected.getMessage()),
          "The move identifies the failed check and keeps its existing message: " + rejected.reason);
    }
  }

  private record ScanWindow(long start, long end) {
    boolean contains(long time) { return time >= start && time <= end; }
  }

  private static String checkSnapshotCadence(Context base, String secondary, int cadence) throws Exception {
    int recordCount = 12_478;
    int photoCount = 12;
    try (Fixture f = new Fixture(base, secondary); CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         ArchiveStore archive = E2Settings.openRecords(f.isolatedContext()); ArchiveStore writer = E2Settings.openRecords(f.isolatedContext())) {
      String sampleKey = null;
      var namesByHistoryId = new HashMap<Long, String>();
      for (int i = 0; i < photoCount; i++) {
        byte[] jpeg = jpegWithCaptureTime(f, 0xff57688b + i, SHOT + 1, SHOT + 2);
        byte[] bytes = Arrays.copyOf(jpeg, 8 * 1024 * 1024);
        var candidate = seed(f, history, archive, "CADENCE-" + i + ".JPG", bytes, false, false);
        if (i == 0) sampleKey = candidate.archiveKey();
        namesByHistoryId.put(candidate.history().getId(), candidate.name());
      }
      require(namesByHistoryId.size() == photoCount, "Seed one history ID for each candidate");
      JSONObject sample = archive.get(sampleKey).put("key", "SYNTHETIC_KEY").put("uri", "SYNTHETIC_URI");
      archive.getWritableDatabase().execSQL(
          "WITH RECURSIVE rows(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM rows WHERE n<?) "
              + "INSERT INTO photos SELECT printf('%032x',n),'verified',"
              + "replace(replace(?, 'SYNTHETIC_KEY', printf('%032x',n)), 'SYNTHETIC_URI', 'fixture://archive/'||n) FROM rows",
          new Object[]{recordCount - photoCount, sample.toString()});
      require(DatabaseUtils.longForQuery(archive.getReadableDatabase(), "SELECT count(*) FROM photos", null) == recordCount,
          "Use exactly 12478 valid synthetic archive records");

      var scanStarted = new CountDownLatch(1);
      var stopWriter = new CountDownLatch(1);
      var writerReady = new CountDownLatch(1);
      var writes = new ConcurrentLinkedQueue<Long>();
      var writerFailure = new AtomicReference<Exception>();
      var scanStart = new AtomicLong();
      var scanAttempts = new AtomicInteger();
      var retries = new AtomicInteger();
      List<ScanWindow> windows = new ArrayList<>();
      Thread background = new Thread(() -> {
        try {
          writer.getWritableDatabase();
          writerReady.countDown();
          if (!scanStarted.await(20, TimeUnit.SECONDS)) throw new IOException("No move scan started");
          int sequence = 0;
          do {
            if (stopWriter.getCount() == 0) break;
            writer.put(new JSONObject(sample.toString()).put("key", "0".repeat(31) + "1")
                .put("uri", "fixture://archive/1").put("sequence", ++sequence), "verified");
            writes.add(SystemClock.elapsedRealtimeNanos());
          } while (!stopWriter.await(cadence, TimeUnit.MILLISECONDS));
        } catch (Exception failure) {
          writerFailure.set(failure);
        } finally {
          writerReady.countDown();
        }
      }, "archive-fixture-writer");

      require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the synthetic SD destination");
      var observer = new LegacyArchiveMoveProof.ScanObserver() {
        @Override public void beforeScan(int attempt) {
          scanAttempts.incrementAndGet();
          if (attempt > 1) retries.incrementAndGet();
          scanStart.set(SystemClock.elapsedRealtimeNanos());
          scanStarted.countDown();
        }
        @Override public void afterScan() {
          windows.add(new ScanWindow(scanStart.get(), SystemClock.elapsedRealtimeNanos()));
        }
      };
      long elapsed;
      long revisionBefore = archive.indexRevision();
      int done = 0;
      String status = "completed";
      try (PhotoMove mover = observeScans(f, ledger, history, observer)) {
        PhotoMove.Plan plan = preflight(mover, mover.plan(secondary), () -> false);
        require(plan.items().size() == photoCount, "All expected synthetic candidates pass the full preflight");
        mover.begin(plan);
        background.start();
        try {
          require(writerReady.await(10, TimeUnit.SECONDS), "The background writer must start before measurement");
          long started = SystemClock.elapsedRealtime();
          try {
            done = mover.run(() -> false, ignored -> {}).done();
          } catch (PhotoMove.Failure failure) {
            done = mover.batch().done();
            status = failure.reason;
          }
          elapsed = SystemClock.elapsedRealtime() - started;
        } finally {
          stopWriter.countDown();
          scanStarted.countDown();
          background.join(10_000);
        }
      }
      require(!background.isAlive() && writerFailure.get() == null, "The writer stopped without failing");
      long revisionDelta = archive.indexRevision() - revisionBefore;
      long overlap = writes.stream().filter(time -> windows.stream().anyMatch(window -> window.contains(time))).count();
      String metrics = "cadence_ms=" + cadence + " records=" + recordCount + " done=" + done + "/" + photoCount
          + " commits=" + writes.size() + " revision_delta=" + revisionDelta + " scan_attempts=" + scanAttempts.get()
          + " retries=" + retries.get() + " overlapped_commits=" + overlap + " move_ms=" + elapsed + " result=" + status;
      android.util.Log.i("LoupeArchiveSnapshotCheck", metrics);
      require(writes.size() >= 2 && revisionDelta == writes.size() && overlap > 0,
          "Measure actual committed writes overlapping a real scan: " + metrics);
      var targets = f.rows(secondary);
      require(done == photoCount && targets.size() == photoCount && f.rows(PRIMARY).isEmpty(),
          "The photo move must finish during real concurrent archive writes: " + metrics);
      Set<Uri> unmatchedTargets = targets.stream().map(PhotoStorageChecks.Row::uri).collect(Collectors.toSet());
      var movedHistory = history.moveEvidence();
      require(movedHistory.size() == photoCount && movedHistory.stream().map(HistoryDb.MoveEvidence::getId)
          .collect(Collectors.toSet()).equals(namesByHistoryId.keySet()) && ledger.receiptCount() == 0,
          "Every original history ID remains and no new receipt is created");
      for (var row : movedHistory) {
        String expectedName = namesByHistoryId.get(row.getId());
        Uri target = Uri.parse(row.getUri());
        require(unmatchedTargets.remove(target) && expectedName.equals(row.getName()),
            "Every history ID references a different exact destination with its original name");
        try (Cursor media = f.resolver.query(target, new String[]{DISPLAY_NAME}, null, null, null)) {
          require(media != null && media.getCount() == 1 && media.moveToFirst() && expectedName.equals(media.getString(0)),
              "The history URI resolves to the corresponding moved photo");
        }
      }
      require(unmatchedTargets.isEmpty(), "Every moved photo has its original history");
      return "PASS archive snapshot load: " + metrics;
    }
  }

  private static PhotoMove observeScans(Fixture f, CameraImportLedger ledger, HistoryDb history,
      LegacyArchiveMoveProof.ScanObserver observer) {
    return new PhotoMove(f.isolatedContext(), ledger, history, new PhotoMoveStore(f.isolatedContext()), ignored -> {},
        f.resolver::openInputStream, f.resolver::openFileDescriptor, observer);
  }

  private static SQLiteDatabase openReadOnlyArchive(Context context) {
    return SQLiteDatabase.openDatabase(context.getDatabasePath(E2Settings.DATABASE).getPath(), null, SQLiteDatabase.OPEN_READONLY);
  }

  private static long revision(SQLiteDatabase db) {
    return DatabaseUtils.longForQuery(db, "SELECT value FROM index_revision WHERE id=1", null);
  }

  private static void putUnrelated(ArchiveStore writer, int ordinal) throws Exception {
    writer.put(new JSONObject().put("key", "unrelated-" + ordinal).put("uri", "fixture://archive/" + ordinal), "pending");
  }

  private static void putUnrelatedSql(SQLiteDatabase db, int ordinal) {
    db.execSQL("INSERT INTO photos(key,state,payload) VALUES(?,'pending',?)",
        new Object[]{"direct-" + ordinal, new JSONObject(java.util.Collections.singletonMap(
            "uri", "fixture://archive/direct-" + ordinal)).toString()});
  }

  private static void writeArchive(CheckedOperation write) throws IOException {
    try {
      write.run();
    } catch (Exception failure) {
      throw new IOException("Synthetic competing archive write failed", failure);
    }
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
    require(LegacyArchiveMoveProof.match(photo, EPOCH, GA, GA + 256, history, indexed(List.of(receipt)), budget, () -> false) != null
        && LegacyArchiveMoveProof.match(photo, EPOCH, GA, GA, history, indexed(List.of(receipt)), budget, () -> false) == null,
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
    expectInterrupted(() -> LegacyArchiveMoveProof.match(photo, EPOCH, GA, GA + 256, history, indexed(List.of(receipt)),
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
    return LegacyArchiveMoveProof.match(photo, epoch, added, modified, history, indexed(records),
        new LegacyArchiveMoveProof.Budget(budget), () -> false);
  }

  private static List<LegacyArchiveMoveProof.IndexedReceipt> indexed(List<LegacyArchiveMoveProof.ArchiveRecord> records) {
    return records.stream().map(record -> new LegacyArchiveMoveProof.IndexedReceipt(true, record))
        .collect(Collectors.toList());
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
    return seed(f, history, archive, name, bytes, missingDate, wrongSha);
  }

  private static Seed seed(Fixture f, HistoryDb history, ArchiveStore archive, String name, byte[] bytes, boolean missingDate,
      boolean wrongSha) throws Exception {
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
      var snapshots = new ArrayList<PhotoMove.PreflightProgress>();
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
          expectInterrupted(() -> preflight(injected, plan, beganRead::get, snapshots::add),
              "Cancelling during the read aborts the entire preflight");
          require(snapshots.stream().noneMatch(PhotoMove.PreflightProgress::finished), "Cancelled preflight never finishes");
        } else {
          PhotoMove.Plan checked = preflight(injected, plan, () -> false, snapshots::add);
          require(scenario.equals("full") ? checked.items().size() == 1 && fullEof.get()
              : checked.items().isEmpty() && checked.archiveRejected() == 1, "Verify EOF and reject short, extra, revoked and metadata-changed originals");
          PhotoMove.PreflightProgress done = snapshots.get(snapshots.size() - 1);
          long expectedRead = switch (scenario) {
            case "short" -> dated.bytes().length - 1;
            case "extra" -> dated.bytes().length + 1;
            case "revoked" -> Math.max(1, dated.bytes().length / 2);
            default -> dated.bytes().length;
          };
          require(done.finished() && done.completed() == 1 && done.total() == 1
              && done.rejected() == checked.archiveRejected() && done.totalBytes() == dated.bytes().length
              && done.readBytes() == expectedRead, "Final " + scenario + " snapshot reports actual bytes and the completed outcome");
          if (scenario.equals("full")) require(snapshots.stream().anyMatch(progress -> progress.completed() == 0
              && progress.readBytes() > 0 && progress.readBytes() < progress.totalBytes()), "Report bytes before a single original is verified");
        }
        PhotoMove.PreflightProgress initial = snapshots.get(0);
        require(initial.total() == 1 && initial.completed() == 0 && initial.readBytes() == 0 && !initial.finished(),
            "Preflight starts with the known candidate count and no reads");
        require(beganRead.get() && injected.batch() == null && f.rows(f.secondary).isEmpty()
            && Arrays.equals(dated.bytes(), f.read(dated.uri())), "A preflight read outcome has no durable move or photo side effects");
      }
    }
  }

  private static PhotoMove.Plan preflight(PhotoMove move, PhotoMove.Plan plan, BooleanSupplier stopped) throws IOException {
    return preflight(move, plan, stopped, null);
  }

  private static PhotoMove.Plan preflight(PhotoMove move, PhotoMove.Plan plan, BooleanSupplier stopped,
      Consumer<PhotoMove.PreflightProgress> observer) throws IOException {
    var gate = CameraImportGate.lock;
    gate.lock();
    try {
      return move.preflight(plan, stopped, observer);
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

  private static void checkPublishedTargetDrift(Context base, String secondary) throws Exception {
    try (Fixture f = new Fixture(base, secondary); Fixture relocated = new Fixture(base, secondary);
         CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         ArchiveStore archive = E2Settings.openRecords(f.isolatedContext()); PhotoMoveStore journal = new PhotoMoveStore(f.isolatedContext())) {
      String scenario = "published folder drift";
      Seed seed = seed(f, history, archive, "PUBLISHED-DRIFT.JPG", 0xff698a59, false, false);
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
          ContentValues values = new ContentValues();
          values.put(RELATIVE_PATH, relocated.path);
          int updated = f.resolver.update(target, values, null, null);
          require(updated == 1, scenario + ": expected exactly one relative-path update, actual=" + updated);
          try (Cursor row = f.resolver.query(target, new String[]{RELATIVE_PATH}, null, null, null)) {
            require(row != null && row.moveToFirst() && relocated.path.equals(row.getString(0)),
                scenario + ": observe the new relative folder at the exact published URI");
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
            "Folder drift preserves both byte-exact copies and all histories without completed proof");
      }
    }
  }

  private static void checkHistoricalCaptureGuard(Context base, String secondary) throws Exception {
    try (Fixture f = new Fixture(base, secondary)) {
      String name = "CAPTURE-GUARD.JPG";
      byte[] unknownExif = jpeg(0xff234f62);
      byte[] datedExif = jpegWithCaptureTime(f, 0xff234f62, SHOT + 1, SHOT + 2);
      long expected = (SHOT + 1) * 1000;
      Uri unknown = f.insert(PRIMARY, name, unknownExif, 0);
      Uri dated = f.insert(secondary, name, datedExif, expected);
      var datedRow = f.row(dated);
      require(!datedRow.pending() && f.isolatedContext().getPackageName().equals(datedRow.owner()),
          "Capture guard: explicit EXIF fixture is published and owned by the test APK");
      Uri callback = rescanDatedCapture(f, dated, name);
      requireObservedCapture(f, dated, callback, expected);
      requireObservedCapture(f, unknown, null, 0);

      // Capture time on an existing published JPEG is scanner-derived. Feed the production
      // comparator actual MediaStore rows; folder and revision drift exercise the full move above.
      var columns = PhotoMove.class.getDeclaredField("COLUMNS");
      columns.setAccessible(true);
      String[] projection = (String[]) columns.get(null);
      Method read = PhotoMove.class.getDeclaredMethod("media", Cursor.class);
      read.setAccessible(true);
      Object original = readMedia(f, read, projection, unknown);
      Object published = readMedia(f, read, projection, dated);
      Method check = PhotoMove.class.getDeclaredMethod("requireHistoricalMetadata", read.getReturnType(), read.getReturnType(), String.class);
      check.setAccessible(true);
      checkHistoricalMetadata(check, original, original, PRIMARY);
      checkHistoricalMetadata(check, published, published, secondary);
      expectCaptureMismatch(check, original, published, secondary);
      expectCaptureMismatch(check, published, original, PRIMARY);
      require(Arrays.equals(unknownExif, f.read(unknown)) && Arrays.equals(datedExif, f.read(dated)),
          "Capture guard validates metadata without rewriting either owned input");
    }
  }

  private static Uri rescanDatedCapture(Fixture f, Uri target, String name) throws Exception {
    Context context = f.isolatedContext();
    File root = PhotoStorage.requireWritable(context, f.secondary);
    File path = new File(root, f.path + name).getCanonicalFile();
    try (Cursor row = f.resolver.query(target, new String[]{DATA}, null, null, null)) {
      require(row != null && row.moveToFirst() && row.getString(0) != null
          && path.equals(new File(row.getString(0)).getCanonicalFile()), "Capture guard: scan only the exact owned target path");
    }
    var finished = new CountDownLatch(1);
    var scanned = new AtomicReference<Uri>();
    MediaScannerConnection.scanFile(context, new String[]{path.getPath()}, new String[]{"image/jpeg"}, (ignored, result) -> {
      scanned.set(result);
      finished.countDown();
    });
    require(finished.await(8, TimeUnit.SECONDS), "Capture guard: scanner callback timeout for synthetic target ID " + ContentUris.parseId(target));
    return scanned.get();
  }

  private static void requireObservedCapture(Fixture f, Uri uri, Uri callback, long expected) throws IOException {
    String callbackLabel = expected == 0 ? "not requested" : String.valueOf(callback);
    try (Cursor row = f.resolver.query(uri, new String[]{DATE_TAKEN, DATE_MODIFIED, IS_PENDING, MIME_TYPE, DISPLAY_NAME, RELATIVE_PATH}, null, null, null);
         var original = f.resolver.openInputStream(MediaStore.setRequireOriginal(uri))) {
      require(row != null && row.moveToFirst() && original != null, "Capture guard: read synthetic row and EXIF, scan callback=" + callbackLabel);
      ExifInterface exif = new ExifInterface(original);
      long taken = row.getLong(0);
      int pending = row.getInt(2);
      String mime = row.getString(3);
      require((expected == 0 || callback != null && ContentUris.parseId(callback) == ContentUris.parseId(uri))
          && taken == expected && pending == 0 && "image/jpeg".equals(mime)
          && "CAPTURE-GUARD.JPG".equals(row.getString(4)) && f.path.equals(row.getString(5)),
          "Capture guard: callback=" + callbackLabel + ", target=" + uri + ", expectedTaken=" + expected + ", actualTaken=" + taken
              + ", modified=" + row.getLong(1) + ", pending=" + pending + ", mime=" + mime
              + ", exifOriginal=" + exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
              + ", exifOffset=" + exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL));
    }
  }

  private static Object readMedia(Fixture f, Method read, String[] columns, Uri uri) throws Exception {
    try (Cursor row = f.resolver.query(uri, columns, null, null, null)) {
      require(row != null && row.getCount() == 1 && row.moveToFirst(), "Capture guard requires one exact published MediaStore row");
      return read.invoke(null, row);
    }
  }

  private static void checkHistoricalMetadata(Method check, Object original, Object published, String volume) throws Exception {
    try {
      check.invoke(null, original, published, volume);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof PhotoMove.Failure rejected) throw rejected;
      throw failure;
    }
  }

  private static void expectCaptureMismatch(Method check, Object original, Object published, String volume) throws Exception {
    try {
      checkHistoricalMetadata(check, original, published, volume);
      throw new PhotoStorageChecks.CheckFailure("Capture guard accepted known and unknown times for the same folder, name and MIME");
    } catch (PhotoMove.Failure rejected) {
      require(rejected.getMessage().contains("撮影日時が一致しません"), "Capture guard returned the destination metadata mismatch");
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

  private static void checkRollbackPreservesOlderBindings(Context base, String secondary, PhotoMove.Boundary boundary,
      boolean historyErased) throws Exception {
    try (Fixture f = new Fixture(base, secondary); CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         ArchiveStore archive = E2Settings.openRecords(f.isolatedContext()); PhotoMoveStore journal = new PhotoMoveStore(f.isolatedContext());
         TagStore tags = new TagStore(f.isolatedContext()); ShareHistoryStore shares = new ShareHistoryStore(f.isolatedContext())) {
      Seed seed = seed(f, history, archive, "ROLLBACK-OLDER-BINDINGS.JPG", 0xff674f99, false, false);
      String archiveBefore = archive.get(seed.archiveKey()).toString();
      require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the archived move destination");
      try (PhotoMove interrupted = f.move(ledger, history, reached -> { if (reached == boundary) throw new InterruptedMove(); })) {
        interrupted.begin(preflight(interrupted, interrupted.plan(secondary), () -> false));
        expectCrash(interrupted, boundary);
      }
      Uri target = f.rows(secondary).get(0).uri();
      String moveUri = PhotoMove.galleryUri(target).toString();
      boolean remapped = boundary == PhotoMove.Boundary.REMAPPED;
      var current = exactGalleryMetadata(f, target);
      var active = journal.next();
      var currentBinding = new GalleryBinding(PhotoIdentityIndex.location(current), TagStore.locationSource(current), active.identity(), moveUri);
      require(active.stage() == (remapped ? PhotoMoveStore.REMAPPED : PhotoMoveStore.UPDATING_HISTORY)
          && history.importDestinations().equals(Set.of(target.toString()))
          && galleryBindings(journal, moveUri).equals(remapped ? Set.of(currentBinding) : Set.of()),
          "UPDATING_HISTORY has not created a target binding; REMAPPED has created exactly the current binding");

      var ranking = new RankingStore(f.isolatedContext());
      var priorLocation = priorGalleryPhoto(current, current.modified - 20, current.added - 20_000, f.id + "-prior-location");
      var priorVersion = priorGalleryPhoto(current, current.modified, current.added - 40_000, f.id + "-prior-revision");
      LogicalLabels locationLabels = seedLogicalLabels(priorLocation, tags, ranking, shares);
      LogicalLabels versionLabels = seedLogicalLabels(priorVersion, tags, ranking, shares);
      PhotoIdentityIndex.save(journal.getWritableDatabase(), priorLocation, priorLocation.movedIdentity, moveUri);
      PhotoIdentityIndex.save(journal.getWritableDatabase(), priorVersion, priorVersion.movedIdentity, moveUri);
      Set<GalleryBinding> earlier = Set.of(binding(priorLocation, moveUri), binding(priorVersion, moveUri));
      require(!currentBinding.location().equals(PhotoIdentityIndex.location(priorLocation))
          && currentBinding.location().equals(PhotoIdentityIndex.location(priorVersion))
          && !currentBinding.mediaVersion().equals(TagStore.locationSource(priorVersion))
          && !locationLabels.key().equals(versionLabels.key()), "Older rows share the target URI but represent two distinct logical histories");
      Set<GalleryBinding> before = new HashSet<>(earlier);
      if (remapped) before.add(currentBinding);
      require(galleryBindings(journal, moveUri).equals(before), "Record the exact identities and count before historical rollback");
      if (historyErased) history.resetHistory(MODEL, f.id);
      try (PhotoMove reopened = f.move(ledger, history, ignored -> {})) {
        var ended = reopened.abandon();
        require(ended.total() == 1 && ended.done() == 0 && reopened.batch() == null
            && galleryBindings(journal, moveUri).equals(earlier) && journal.completed(seed.history().getId()) == null
            && history.moveEvidence().equals(historyErased ? List.of() : List.of(seed.history()))
            && archiveBefore.equals(archive.get(seed.archiveKey()).toString()) && ledger.receiptCount() == 0
            && Arrays.equals(seed.bytes(), f.read(seed.uri())) && Arrays.equals(seed.bytes(), f.read(target)),
            "Abandon retains both prior bindings, drops only this move's remap when present, and preserves both files and the expected history");
      }
      requireLogicalLabels(priorLocation, locationLabels, tags, ranking, shares);
      requireLogicalLabels(priorVersion, versionLabels, tags, ranking, shares);
    }
  }

  private static void checkPublishedExactBindingConflict(Context base, String secondary) throws Exception {
    try (Fixture f = new Fixture(base, secondary); CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         ArchiveStore archive = E2Settings.openRecords(f.isolatedContext()); PhotoMoveStore journal = new PhotoMoveStore(f.isolatedContext());
         TagStore tags = new TagStore(f.isolatedContext()); ShareHistoryStore shares = new ShareHistoryStore(f.isolatedContext())) {
      Seed seed = seed(f, history, archive, "EXACT-GALLERY-PREDECESSOR.JPG", 0xffa0786b, false, false);
      String archiveBefore = archive.get(seed.archiveKey()).toString();
      require(PhotoStorage.select(f.isolatedContext(), secondary), "Choose the synthetic move destination");
      try (PhotoMove interrupted = f.move(ledger, history, reached -> {
        if (reached == PhotoMove.Boundary.PUBLISHED) throw new InterruptedMove();
      })) {
        interrupted.begin(preflight(interrupted, interrupted.plan(secondary), () -> false));
        expectCrash(interrupted, PhotoMove.Boundary.PUBLISHED);
      }
      Uri target = f.rows(secondary).get(0).uri();
      var current = exactGalleryMetadata(f, target);
      String moveUri = current.uri.toString();
      require(!f.row(target).pending() && journal.next().stage() == PhotoMoveStore.VERIFIED
          && galleryBindings(journal, moveUri).isEmpty(), "Occupy the exact published key in the gap before any journal remap");
      var predecessor = priorGalleryPhoto(current, current.modified, current.added, f.id + "-exact-predecessor");
      var ranking = new RankingStore(f.isolatedContext());
      LogicalLabels labels = seedLogicalLabels(predecessor, tags, ranking, shares);
      PhotoIdentityIndex.save(journal.getWritableDatabase(), predecessor, predecessor.movedIdentity, moveUri);
      Set<GalleryBinding> earlier = Set.of(binding(predecessor, moveUri));
      try (PhotoMove reopened = f.move(ledger, history, reached -> {
        if (reached == PhotoMove.Boundary.REMAPPED)
          throw new PhotoStorageChecks.CheckFailure("The historical move overwrote an existing exact gallery association");
      })) {
        try {
          reopened.run(() -> false, ignored -> {});
          throw new PhotoStorageChecks.CheckFailure("An occupied exact gallery key must block historical remap");
        } catch (PhotoMove.Failure rejected) {
          require(rejected.getMessage().contains("既存の写真の関連付け"), "Reject specifically because the exact gallery key is occupied");
        }
        var active = journal.next();
        require(active.stage() == PhotoMoveStore.UPDATING_HISTORY && active.targetPin().binding() == null
            && galleryBindings(journal, moveUri).equals(earlier) && journal.completed(seed.history().getId()) == null
            && Arrays.equals(seed.bytes(), f.read(seed.uri())) && Arrays.equals(seed.bytes(), f.read(target)),
            "Reject before remap without rewriting the previous binding or either verified copy");
        var ended = reopened.abandon();
        require(ended.done() == 0 && reopened.batch() == null && galleryBindings(journal, moveUri).equals(earlier)
            && history.moveEvidence().equals(List.of(seed.history())) && archiveBefore.equals(archive.get(seed.archiveKey()).toString())
            && journal.completed(seed.history().getId()) == null && ledger.receiptCount() == 0
            && Arrays.equals(seed.bytes(), f.read(seed.uri())) && Arrays.equals(seed.bytes(), f.read(target)),
            "Ending the blocked move restores source history and retains the exact previous association and both files");
      }
      requireLogicalLabels(f, target, labels, tags, ranking, shares);
    }
  }

  private static PhotoRepository.Photo exactGalleryMetadata(Fixture f, Uri uri) throws IOException {
    String[] fields = {DISPLAY_NAME, RELATIVE_PATH, SIZE, DATE_MODIFIED, DATE_TAKEN, DATE_ADDED};
    try (Cursor row = f.resolver.query(uri, fields, null, null, null)) {
      require(row != null && row.moveToFirst() && row.getCount() == 1, "Observe exact target metadata without creating any gallery binding");
      return new PhotoRepository.Photo(PhotoMove.galleryUri(uri), row.getString(0), row.getString(1), row.getLong(2), row.getLong(3),
          row.getLong(4), row.getLong(5) * 1000, 0);
    }
  }

  private static PhotoRepository.Photo priorGalleryPhoto(PhotoRepository.Photo current, long modified, long added, String id) throws IOException {
    var identity = new PhotoMoveStore.Identity(PhotoRepository.digest(id), "synthetic-older-photo:" + id, added);
    return new PhotoRepository.Photo(current.uri, current.name, current.folder, current.size, modified, current.taken, added, 0)
        .withIdentity(identity);
  }

  private record GalleryBinding(String location, String mediaVersion, PhotoMoveStore.Identity identity, String moveUri) {}

  private static GalleryBinding binding(PhotoRepository.Photo photo, String moveUri) {
    return new GalleryBinding(PhotoIdentityIndex.location(photo), TagStore.locationSource(photo), photo.movedIdentity, moveUri);
  }

  private static Set<GalleryBinding> galleryBindings(PhotoMoveStore journal, String moveUri) {
    Set<GalleryBinding> bindings = new HashSet<>();
    try (Cursor rows = journal.getReadableDatabase().rawQuery("SELECT location,media_version,archive_key,tag_source,sort_time,move_uri "
        + "FROM local_photos WHERE move_uri=?", new String[]{moveUri})) {
      while (rows.moveToNext()) bindings.add(new GalleryBinding(rows.getString(0), rows.getString(1),
          new PhotoMoveStore.Identity(rows.getString(2), rows.getString(3), rows.getLong(4)), rows.getString(5)));
      require(rows.getCount() == bindings.size(), "Count each logical location and revision independently");
    }
    return bindings;
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
    return seedLogicalLabels(f.photo(seed.uri()), tags, ranking, shares);
  }

  private static LogicalLabels seedLogicalLabels(PhotoRepository.Photo original, TagStore tags, RankingStore ranking, ShareHistoryStore shares)
      throws IOException {
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
    requireLogicalLabels(f.photo(uri), labels, tags, ranking, shares);
  }

  private static void requireLogicalLabels(PhotoRepository.Photo photo, LogicalLabels labels, TagStore tags, RankingStore ranking,
      ShareHistoryStore shares) throws IOException {
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

  private static void checkInventoryCountGroups() {
    var location = new PhotoStorageLocation(PhotoStorageLocation.Kind.CURRENT,
        PhotoStorageLocation.Availability.MOUNTED, PRIMARY, "内部ストレージ", FOLDER);
    var eligible = PhotoStorageInventory.Status.ELIGIBLE;
    var pending = List.of(PhotoStorageInventory.Reason.ARCHIVE_CHECK_REQUIRED);
    var photos = List.of(
        new PhotoStorageInventory.Photo(Uri.parse("content://media/external_primary/images/media/1"),
            "MATCHED.JPG", location, eligible, List.of(), null, true),
        new PhotoStorageInventory.Photo(Uri.parse("content://media/external_primary/images/media/2"),
            "MATCHED-PENDING.JPG", location, eligible, pending, null, true),
        new PhotoStorageInventory.Photo(Uri.parse("content://media/external_primary/images/media/3"),
            "UNMATCHED-PENDING.JPG", location, eligible, pending, null, false),
        new PhotoStorageInventory.Photo(Uri.parse("content://media/external_primary/images/media/4"),
            "OTHER-REFERENCE.JPG", location, PhotoStorageInventory.Status.EXCLUDED,
            List.of(PhotoStorageInventory.Reason.HISTORY_NAME), "RECORDED.JPG", false));
    var unresolved = new PhotoStorageInventory.History("MISSING.JPG", PhotoStorageLocation.unknown(),
        List.of(PhotoStorageInventory.Reason.MISSING_REFERENCE), null, null, 7);
    String grouped = PhotoStorageInventoryActivity.summary(new PhotoStorageInventory(photos, List.of(unresolved)));
    require(grouped.equals("履歴と一致した写真 2枚\n　内部ストレージ 2枚\n　うち内容の確認待ち 1枚"
        + "\n\n履歴との照合待ち 2枚\n　うち内容の確認待ち 1枚\n　うちその他の参照先 1枚"
        + "\n\n確認が必要な履歴 7件"), "Matched and unmatched are disjoint photos, and unresolved histories count records separately");
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
      require(overview.startsWith("履歴と一致した写真 1枚\n")
          && overview.contains("\n　うち内容の確認待ち 1枚\n\n履歴との照合待ち 0枚\n")
          && !overview.contains("その他の参照先"), "Reverse inventory nests its one pending photo in the matched group");
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
    var rangeChecks = new ArrayList<String>();
    try (Fixture f = new Fixture(base, secondary); ArchiveStore archive = E2Settings.openRecords(base)) {
      var syntheticKeys = new java.util.ArrayList<String>();
      try {
        int largeSize = 8 * 1024 * 1024;
        Seed undated = seed(f, history, archive, "UNDATED.JPG", Arrays.copyOf(jpeg(0xff416c5c), largeSize), true, false);
        syntheticKeys.add(undated.archiveKey());
        byte[] largeJpeg = Arrays.copyOf(jpegWithCaptureTime(f, 0xff845a51, SHOT + 1, SHOT + 2), largeSize);
        Seed dated = seed(f, history, archive, "DATED.JPG", largeJpeg, false, false);
        syntheticKeys.add(dated.archiveKey());
        byte[] strictBytes = jpegWithCaptureTime(f, 0xff4b6085, SHOT, SHOT + 2);
        Uri strict = f.insert(PRIMARY, "STRICT.JPG", strictBytes, SHOT * 1000);
        require(f.awaitCaptureTime(strict, SHOT * 1000) == SHOT * 1000, "Index a strict matching capture time");
        history.recordImport(MODEL, f.id, new ImportedKey("STRICT.JPG", strictBytes.length, SHOT), strict.toString());
        byte[] referenceBytes = jpegWithCaptureTime(f, 0xff6a4f78, SHOT, SHOT + 2);
        Uri reference = f.insert(PRIMARY, "OTHER-REFERENCE.JPG", referenceBytes, SHOT * 1000);
        history.recordImport(MODEL, f.id, new ImportedKey("OTHER-REFERENCE.JPG", referenceBytes.length + 1, SHOT),
            reference.toString());
        var before = history.moveEvidence();
        require(PhotoStorage.select(base, secondary), "Select the isolated UI destination");
        Seed bad;
        try (var move = new PhotoMove(base)) {
          // Make the first historical candidate fail so its exclusion is visible during the second read.
          String first = move.plan(secondary).items().stream().filter(PhotoMoveStore.Item::hasHistoricalProof)
              .findFirst().orElseThrow().source();
          bad = first.equals(undated.uri().toString()) ? undated : dated;
        }
        Seed good = bad == undated ? dated : undated;
        archive.put(archive.get(bad.archiveKey()).put("sha", SHA), "verified");
        long archiveRevision = archive.indexRevision();
        captureConfirmation(test, base, secondary, captures, profile, good, bad, rangeChecks);
        try (PhotoMoveStore journal = new PhotoMoveStore(base)) {
          require(journal.batch() == null && !PhotoMoveService.state().running() && f.rows(secondary).isEmpty()
              && archive.indexRevision() == archiveRevision
              && history.moveEvidence().equals(before) && Arrays.equals(good.bytes(), f.read(good.uri()))
              && Arrays.equals(bad.bytes(), f.read(bad.uri())) && Arrays.equals(strictBytes, f.read(strict))
              && Arrays.equals(referenceBytes, f.read(reference)),
              "Cancelling and backing out of the native confirmation does not begin a move");
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
    return "PASS archive count groups and preflight progress: one history match, two unmatched pending and one other reference, "
        + "two eligible after preflight, one archive exclusion and one other exclusion, cancel and back without moving; "
        + "indeterminate preparation, 1 MiB and 4 MiB inside one original, cancel/retry and pause without stale results; "
        + "accessibility ranges " + rangeChecks + "; "
        + test.photoStorageEffectiveWidth() + "dp, font " + font + " " + captures.getAbsolutePath();
  }

  private static void captureConfirmation(ArchiveInstrumentation test, Context base, String secondary, File captures,
      String profile, Seed good, Seed bad, List<String> rangeChecks) throws Exception {
    PhotoStorageActivity activity = (PhotoStorageActivity) test.startActivitySync(
        new Intent(base, PhotoStorageActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      UiAutomation automation = test.getUiAutomation();
      String label = "取り込み済みの写真を移動";
      Predicate<AccessibilityNodeInfo> moveButton = node -> hasText(node, label) && node.isEnabled();
      var start = awaitStorageControl(automation, moveButton);
      assertWindowWidth(test, activity);
      assertTouchTarget(test, activity, start, label);
      Predicate<AccessibilityNodeInfo> awaitingCheck = node -> mentions(node, "履歴と一致した写真 1枚")
          && mentions(node, "履歴との照合待ち 3枚\n　うち内容の確認待ち 2枚\n　うちその他の参照先 1枚")
          && !mentions(node, "内容を照合できなかった");
      awaitStorageControl(automation, awaitingCheck);
      captureSettled(test, automation, captures, profile + "-settings", awaitingCheck);
      captureCountList(test, automation, captures, profile, awaitingCheck);
      String card = PhotoStorage.label(base, secondary);
      Predicate<AccessibilityNodeInfo> confirmed = node -> mentions(node, card + "に2枚")
          && mentions(node, "内容を照合できなかった1枚は元の場所に残ります。")
          && mentions(node, "移動対象外の項目があります。保存場所の一覧で写真と理由を確認できます。")
          && !mentions(node, "内容の確認待ち");
      click(awaitStorageControl(automation, moveButton));
      awaitStorageControl(automation, confirmed);
      captureSettled(test, automation, captures, profile + "-confirmation", confirmed);
      assertDialogAction(activity, awaitStorageControl(automation, node -> hasText(node, "移動")), "移動");
      assertDialogAction(activity, awaitStorageControl(automation, node -> hasText(node, "理由を見る")), "理由を見る");
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
      captureProgress(test, activity, automation, captures, profile, moveButton, confirmed, good, bad, rangeChecks);
    } finally {
      test.runOnMainSync(activity::finish);
      test.waitForIdleSync();
    }
  }

  /** Each test pause happens in the input stream, outside the production progress implementation. */
  private static final class PacedPreflight implements AutoCloseable {
    final CountDownLatch preparation, reachedRead = new CountDownLatch(1), continueRead = new CountDownLatch(1);
    final int pauseAt;

    PacedPreflight(boolean holdPreparation, int mib) {
      preparation = new CountDownLatch(holdPreparation ? 1 : 0);
      pauseAt = mib * 1024 * 1024;
    }

    PhotoMove open(PhotoStorageActivity activity, Uri pacedOriginal) {
      try {
        awaitGate(preparation, false);
      } catch (IOException error) {
        throw new UncheckedIOException(error);
      }
      return new PhotoMove(activity, CameraImportLedger.get(activity), HistoryDb.Companion.get(activity),
          new PhotoMoveStore(activity), ignored -> {}, uri -> {
        InputStream stream = activity.getContentResolver().openInputStream(uri);
        if (stream == null || !pacedOriginal.equals(uri)) return stream;
        return new FilterInputStream(stream) {
          private int bytesRead;

          @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (bytesRead == pauseAt && continueRead.getCount() != 0) {
              reachedRead.countDown();
              awaitGate(continueRead, true);
            }
            int count = super.read(bytes, offset, bytesRead < pauseAt ? Math.min(length, pauseAt - bytesRead) : length);
            if (count > 0) bytesRead += count;
            return count;
          }
        };
      });
    }

    private static void awaitGate(CountDownLatch gate, boolean keepReading) throws IOException {
      long deadline = SystemClock.elapsedRealtimeNanos() + TimeUnit.SECONDS.toNanos(15);
      boolean interrupted = false;
      try {
        while (true) {
          try {
            if (!gate.await(deadline - SystemClock.elapsedRealtimeNanos(), TimeUnit.NANOSECONDS))
              throw new IOException("Timed out awaiting synthetic preflight capture");
            return;
          } catch (InterruptedException error) {
            if (!keepReading) throw new InterruptedIOException("Cancelled synthetic preflight capture");
            interrupted = true;
          }
        }
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }

    @Override public void close() {
      preparation.countDown();
      continueRead.countDown();
    }
  }

  private static void captureProgress(ArchiveInstrumentation test, PhotoStorageActivity activity, UiAutomation automation,
      File captures, String profile, Predicate<AccessibilityNodeInfo> moveButton, Predicate<AccessibilityNodeInfo> confirmed,
      Seed good, Seed bad, List<String> rangeChecks) throws Exception {
    String moveName = "取り込み済みの写真を移動";
    String stopName = "確認を中止";
    var mover = new AtomicReference<Supplier<PhotoMove>>(() -> new PhotoMove(activity));
    test.runOnMainSync(() -> findButton(activity.getWindow().getDecorView(), moveName)
        .setOnClickListener(view -> activity.plan(mover.get())));
    Uri required = MediaStore.setRequireOriginal(good.uri());
    Predicate<AccessibilityNodeInfo> progressBar = node -> "写真の読み取りの進捗".contentEquals(
        node.getContentDescription() == null ? "" : node.getContentDescription());
    try (var phase = new PacedPreflight(true, 1)) {
      mover.set(() -> phase.open(activity, required));
      click(awaitStorageControl(automation, moveButton));
      awaitNode(automation, node -> hasText(node, "照合する写真を調べています。"));
      awaitNode(automation, node -> hasText(node, "まだ移動していません。"));
      require(awaitNode(automation, progressBar).getRangeInfo() == null, "Preparation is indeterminate");
      assertCancelInViewport(test, activity, automation, stopName);
      captureFrame(test, automation, captures, profile + "-preparing");
      phase.preparation.countDown();
      rangeChecks.add(assertReading(test, activity, automation, good, bad, phase, progressBar));
      assertCancelInViewport(test, activity, automation, stopName);
      captureFrame(test, automation, captures, profile + "-reading-1mib");
      click(awaitNode(automation, node -> hasText(node, stopName)));
      assertCancelling(automation, moveName);
      phase.continueRead.countDown();
      awaitStorageControl(automation, node -> hasText(node, "写真の確認を中止しました。"));
    }
    try (var phase = new PacedPreflight(false, 4)) {
      mover.set(() -> phase.open(activity, required));
      click(awaitStorageControl(automation, moveButton));
      rangeChecks.add(assertReading(test, activity, automation, good, bad, phase, progressBar));
      assertCancelInViewport(test, activity, automation, stopName);
      captureFrame(test, automation, captures, profile + "-reading-4mib");
      phase.continueRead.countDown();
      awaitStorageControl(automation, confirmed);
      click(awaitStorageControl(automation, node -> hasText(node, "キャンセル")));
    }
    try (var phase = new PacedPreflight(false, 1)) {
      mover.set(() -> phase.open(activity, required));
      click(awaitStorageControl(automation, moveButton));
      rangeChecks.add(assertReading(test, activity, automation, good, bad, phase, progressBar));
      var monitor = test.addMonitor(PhotoStorageInventoryActivity.class.getName(), null, false);
      try {
        test.runOnMainSync(() -> activity.startActivity(new Intent(activity, PhotoStorageInventoryActivity.class)));
        Activity inventory = test.waitForMonitorWithTimeout(monitor, 6000);
        require(inventory != null, "Opening inventory pauses the settings Activity");
        test.runOnMainSync(inventory::finish);
      } finally {
        test.removeMonitor(monitor);
      }
      assertCancelling(automation, moveName);
      phase.continueRead.countDown();
      var retry = awaitStorageControl(automation, moveButton);
      var resumed = automation.getRootInActiveWindow();
      require(resumed != null && resumed.findAccessibilityNodeInfosByText("照合する写真を調べています").isEmpty()
          && resumed.findAccessibilityNodeInfosByText("バックアップと写真の内容を照合中").isEmpty()
          && resumed.findAccessibilityNodeInfosByText("枚を確認").isEmpty()
          && resumed.findAccessibilityNodeInfosByText("読み取り ").isEmpty()
          && resumed.findAccessibilityNodeInfosByText("取り込み済みの写真を移動しますか？").isEmpty(),
          "The resumed screen has no old phase, progress counts or confirmation after the cancelled worker exits");
      mover.set(() -> new PhotoMove(activity));
      click(retry);
      awaitStorageControl(automation, confirmed);
      click(awaitStorageControl(automation, node -> hasText(node, "キャンセル")));
    }
  }

  private static void assertCancelInViewport(Instrumentation test, Activity activity, UiAutomation automation,
      String name) {
    assertTouchTarget(test, activity, awaitNode(automation, node -> hasText(node, name)), name);
    test.runOnMainSync(() -> {
      var button = findButton(activity.getWindow().getDecorView(), name);
      var visible = new Rect();
      require(button != null && button.getLocalVisibleRect(visible)
          && visible.width() == button.getWidth() && visible.height() == button.getHeight(),
          "The entire preflight cancel target fits in the scroll viewport without scrolling");
    });
  }

  private static void assertCancelling(UiAutomation automation, String moveName) {
    awaitNode(automation, node -> hasText(node, "写真の確認を中止しています。"));
    var cancelling = automation.getRootInActiveWindow();
    require(cancelling.findAccessibilityNodeInfosByText(moveName).stream().noneMatch(AccessibilityNodeInfo::isVisibleToUser),
        "Retry stays hidden while the cancelled original read is still open");
    require(cancelling.findAccessibilityNodeInfosByText("バックアップと写真の内容を照合中").isEmpty(),
        "The cancelled original no longer updates the visible phase");
  }

  private static String assertReading(Instrumentation test, Activity activity, UiAutomation automation, Seed good, Seed bad,
      PacedPreflight phase, Predicate<AccessibilityNodeInfo> progressBar) throws Exception {
    require(phase.reachedRead.await(8, TimeUnit.SECONDS), "Pause a real original stream midway through the large file");
    awaitNode(automation, node -> hasText(node, "バックアップと写真の内容を照合中"));
    long readBytes = (long) phase.pauseAt + bad.bytes().length;
    long totalBytes = (long) good.bytes().length + bad.bytes().length;
    String bytes = Formatter.formatFileSize(activity, readBytes);
    String total = Formatter.formatFileSize(activity, totalBytes);
    awaitNode(automation, node -> mentions(node, "1 / 2枚を確認") && mentions(node, "一致 0枚・対象外 1枚")
        && mentions(node, "読み取り " + bytes + " / " + total));
    var node = awaitNode(automation, progressBar);
    String cachedRange = readingRange(node.getRangeInfo());
    var nativeState = new AtomicReference<String>();
    test.runOnMainSync(() -> {
      var views = new ArrayList<View>();
      activity.getWindow().getDecorView().findViewsWithText(views, "写真の読み取りの進捗",
          View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
      for (var view : views) {
        if (view instanceof LinearProgressIndicator bar && bar.isShown()) {
          nativeState.set(bar.isIndeterminate() ? "indeterminate" : bar.getProgress() + "/" + bar.getMax());
        }
      }
    });
    int expectedProgress = (int) (1000d * readBytes / totalBytes);
    boolean refreshed = node.refresh();
    var range = node.getRangeInfo();
    String rangeEvidence = "cached=" + cachedRange + ", fresh=" + readingRange(range) + ", native=" + nativeState.get();
    require((expectedProgress + "/1000").equals(nativeState.get()) && refreshed && range != null
        && range.getMin() == 0 && range.getMax() == 1000 && range.getCurrent() == expectedProgress,
        "Expected byte progress " + expectedProgress + "/1000; " + rangeEvidence);
    return rangeEvidence;
  }

  private static String readingRange(AccessibilityNodeInfo.RangeInfo range) {
    return range == null ? "indeterminate" : range.getMin() + "–" + range.getCurrent() + "–" + range.getMax();
  }

  private static void captureCountList(ArchiveInstrumentation test, UiAutomation automation, File captures,
      String profile, Predicate<AccessibilityNodeInfo> grouped) throws Exception {
    var monitor = test.addMonitor(PhotoStorageInventoryActivity.class.getName(), null, false);
    Activity inventory = null;
    try {
      click(awaitStorageControl(automation, node -> hasText(node, "保存場所と移動対象外の理由を見る")));
      inventory = test.waitForMonitorWithTimeout(monitor, 6000);
      require(inventory instanceof PhotoStorageInventoryActivity, "Open the real inventory activity");
      awaitStorageControl(automation, node -> hasText(node, "絞り込み") && node.isEnabled());
      awaitStorageControl(automation, grouped);
      assertWindowWidth(test, inventory);
      captureSettled(test, automation, captures, profile + "-inventory", grouped);
    } finally {
      test.removeMonitor(monitor);
      if (inventory != null) {
        Activity opened = inventory;
        test.runOnMainSync(opened::finish);
        test.waitForIdleSync();
      }
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
