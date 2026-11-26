package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import jp.nano.rawgallery.camera.CameraImportCopy;
import jp.nano.rawgallery.camera.CameraImportFile;
import jp.nano.rawgallery.camera.CameraImportLedger;
import jp.nano.rawgallery.camera.CameraPhotoImporter;
import jp.nano.rawgallery.camera.CameraPhotoPublisher;

/** Synthetic-only checks for cross-route identity and publication recovery. */
final class CameraImportLedgerChecks {
  private static final String[] MEDIA_FIELDS = {
    MediaStore.MediaColumns._ID,
    MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
    MediaStore.MediaColumns.RELATIVE_PATH
  };

  private CameraImportLedgerChecks() {}

  static String run(Instrumentation instrumentation) throws Exception {
    Context context = instrumentation.getTargetContext();
    LedgerFixture fixture = new LedgerFixture(context);
    runFixture(
        fixture,
        () -> {
          crossRouteAndAssociation(context, fixture.runId, fixture.crossRoute, fixture.folders);
          fixture.phase = Phase.SAVED_DESTINATION;
          savedDestinationRegressions(
              context, fixture.runId, fixture.savedDestination, fixture.folders);
          fixture.phase = Phase.RECOVERY;
          for (CameraPhotoPublisher.Boundary boundary : CameraPhotoPublisher.Boundary.values()) {
            fixture.boundary = boundary;
            recoverBoundary(context, fixture.runId, boundary, fixture.folders, fixture.databases);
          }
        });
    return "PASS camera ledger: cross-route receipts, strict content identity, JPEG/RAW association, "
        + "missing/corrupt saved-copy recovery, and recovery at every publication boundary";
  }

  private enum Phase {
    CROSS_ROUTE,
    SAVED_DESTINATION,
    RECOVERY,
    COMPLETE
  }

  private enum DatabaseRole {
    CROSS_ROUTE,
    SAVED_DESTINATION,
    RECOVERY
  }

  private enum Presence {
    PRESENT,
    ABSENT,
    UNKNOWN
  }

  private enum Deletion {
    RETURNED_FALSE,
    THREW,
    ABSENCE_UNCONFIRMED
  }

  private record OwnedDatabase(
      String name, DatabaseRole role, CameraPhotoPublisher.Boundary boundary) {}

  private record DatabaseFiles(Presence db, Presence wal, Presence shm, Presence journal) {
    private static DatabaseFiles observe(Context context, String name) {
      final Path path;
      try {
        path = context.getDatabasePath(name).toPath();
      } catch (RuntimeException unavailable) {
        return new DatabaseFiles(
            Presence.UNKNOWN, Presence.UNKNOWN, Presence.UNKNOWN, Presence.UNKNOWN);
      }
      return new DatabaseFiles(
          presence(path),
          presence(sidecar(path, "-wal")),
          presence(sidecar(path, "-shm")),
          presence(sidecar(path, "-journal")));
    }

    private boolean allAbsent() {
      return db == Presence.ABSENT
          && wal == Presence.ABSENT
          && shm == Presence.ABSENT
          && journal == Presence.ABSENT;
    }

    private static Path sidecar(Path db, String suffix) {
      return db.resolveSibling(db.getFileName() + suffix);
    }

    private static Presence presence(Path path) {
      try {
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return Presence.PRESENT;
      } catch (NoSuchFileException absent) {
        return Presence.ABSENT;
      } catch (IOException | RuntimeException unavailable) {
        return Presence.UNKNOWN;
      }
    }
  }

  /**
   * Only fixed stage/role labels and file-presence values are eligible for the CI failure report.
   */
  static final class FixtureDiagnostic extends IOException {
    private static final long serialVersionUID = 1L;
    final String detail;

    private FixtureDiagnostic(String detail, Throwable cause) {
      super(null, cause);
      this.detail = detail;
    }
  }

  private static String boundaryDetail(CameraPhotoPublisher.Boundary boundary) {
    return boundary == null ? "" : " boundary=" + boundary.name();
  }

  private static FixtureDiagnostic databaseFailure(
      OwnedDatabase database,
      DatabaseFiles before,
      DatabaseFiles after,
      Deletion deletion,
      boolean proofRemainder,
      Throwable cause) {
    return new FixtureDiagnostic(
        (proofRemainder ? "cleanup=proof-remainder role=" : "cleanup=db role=")
            + database.role().name()
            + boundaryDetail(database.boundary())
            + " delete="
            + deletion.name()
            + " before="
            + before
            + " after="
            + after,
        cause);
  }

  private static FixtureDiagnostic recordCleanup(
      FixtureDiagnostic first, FixtureDiagnostic failure) {
    if (first == null) return failure;
    first.addSuppressed(failure);
    return first;
  }

  private static final class LedgerFixture implements AutoCloseable {
    final Context context;
    final String runId = UUID.randomUUID().toString();
    final String crossRoute = "camera-ledger-check-" + runId + ".db";
    final String savedDestination = "camera-ledger-destination-check-" + runId + ".db";
    final Set<String> folders = new HashSet<>();
    final List<OwnedDatabase> databases = new ArrayList<>();
    Phase phase = Phase.CROSS_ROUTE;
    CameraPhotoPublisher.Boundary boundary;

    LedgerFixture(Context context) {
      this.context = context;
      databases.add(new OwnedDatabase(crossRoute, DatabaseRole.CROSS_ROUTE, null));
      databases.add(new OwnedDatabase(savedDestination, DatabaseRole.SAVED_DESTINATION, null));
    }

    @Override
    public void close() throws FixtureDiagnostic {
      FixtureDiagnostic first = null;
      for (String folder : folders) {
        try {
          cleanOwnedMedia(context, folder);
        } catch (FixtureDiagnostic failure) {
          first = recordCleanup(first, failure);
        } catch (Exception | Error failure) {
          first = recordCleanup(first, new FixtureDiagnostic("cleanup=media-folder", failure));
        }
      }
      cleanDatabases(context, false, first);
    }

    AutoCloseable proofCleanup(Context realContext) {
      return () -> cleanDatabases(realContext, true, null);
    }

    private void cleanDatabases(Context owner, boolean proofRemainder, FixtureDiagnostic first)
        throws FixtureDiagnostic {
      for (OwnedDatabase database : databases) {
        DatabaseFiles before = DatabaseFiles.observe(owner, database.name());
        // The proof has already exercised strict deletion. Its outer resource removes leftovers.
        if (proofRemainder && before.allAbsent()) continue;
        Deletion failure = null;
        Throwable cause = null;
        try {
          if (!owner.deleteDatabase(database.name())) failure = Deletion.RETURNED_FALSE;
        } catch (Exception | Error thrown) {
          failure = Deletion.THREW;
          cause = thrown;
        }
        if (failure == null && !proofRemainder) continue;
        DatabaseFiles after = DatabaseFiles.observe(owner, database.name());
        if (failure == null) {
          if (after.allAbsent()) continue;
          failure = Deletion.ABSENCE_UNCONFIRMED;
        }
        first =
            recordCleanup(
                first, databaseFailure(database, before, after, failure, proofRemainder, cause));
      }
      if (first != null) throw first;
    }
  }

  private interface FixtureBody {
    void run() throws Exception;
  }

  private static void runFixture(LedgerFixture fixture, FixtureBody body) throws Exception {
    try (fixture) {
      body.run();
      fixture.phase = Phase.COMPLETE;
      fixture.boundary = null;
    } catch (Exception | Error failure) {
      failure.addSuppressed(
          new FixtureDiagnostic(
              "phase=" + fixture.phase.name() + boundaryDetail(fixture.boundary), null));
      throw failure;
    }
  }

  private enum TeardownCase {
    BODY_EXCEPTION,
    BODY_ERROR,
    CLEANUP_ONLY,
    SUCCESS
  }

  static String checkTeardown(Instrumentation instrumentation) throws Exception {
    Context context = instrumentation.getTargetContext();
    for (TeardownCase scenario : TeardownCase.values()) {
      List<String> attempts = new ArrayList<>();
      Context rejectingFirst =
          new ContextWrapper(context) {
            @Override
            public boolean deleteDatabase(String name) {
              attempts.add(name);
              if (attempts.size() == 1 && scenario != TeardownCase.SUCCESS) return false;
              return super.deleteDatabase(name);
            }
          };
      LedgerFixture fixture = new LedgerFixture(rejectingFirst);
      Exception primaryException = new IOException();
      Error primaryError = new AssertionError();
      try (var proofCleanup = fixture.proofCleanup(context)) {
        // Leave the first planned DB unopened for body failures. A later real owned DB
        // must still be removed after the first delete attempt rejects.
        createTeardownDatabase(context, fixture.savedDestination);
        if (scenario == TeardownCase.CLEANUP_ONLY || scenario == TeardownCase.SUCCESS) {
          createTeardownDatabase(context, fixture.crossRoute);
        }
        Throwable observed = null;
        try {
          runFixture(
              fixture,
              () -> {
                if (scenario == TeardownCase.BODY_EXCEPTION) throw primaryException;
                if (scenario == TeardownCase.BODY_ERROR) throw primaryError;
              });
        } catch (Exception | Error failure) {
          observed = failure;
        }
        require(
            attempts.equals(List.of(fixture.crossRoute, fixture.savedDestination)),
            "cleanup attempts every owned database in order");
        require(
            DatabaseFiles.observe(context, fixture.savedDestination).allAbsent(),
            "later owned database was removed after earlier cleanup");
        if (scenario == TeardownCase.SUCCESS) {
          require(
              observed == null && DatabaseFiles.observe(context, fixture.crossRoute).allAbsent(),
              "successful body and cleanup still succeed");
          continue;
        }
        boolean bodyFailed = scenario != TeardownCase.CLEANUP_ONLY;
        require(observed != null, "cleanup rejection still fails the fixture");
        Throwable[] suppressed = observed.getSuppressed();
        if (bodyFailed) {
          require(
              observed
                  == (scenario == TeardownCase.BODY_EXCEPTION ? primaryException : primaryError),
              "original body failure remains the primary throwable");
          require(
              suppressed.length == 2 && diagnostic(suppressed[1]).equals("phase=CROSS_ROUTE"),
              "body failure retains cleanup and fixed phase diagnostics");
        } else {
          require(
              suppressed.length == 1 && diagnostic(suppressed[0]).equals("phase=COMPLETE"),
              "cleanup-only failure has completed body phase");
        }
        require(
            (bodyFailed ? suppressed[0] : observed) instanceof FixtureDiagnostic cleanup
                && cleanup.detail.contains("role=CROSS_ROUTE delete=RETURNED_FALSE")
                && cleanup.detail.contains(
                    "before=DatabaseFiles[db=" + (bodyFailed ? "ABSENT" : "PRESENT"))
                && cleanup.detail.contains(
                    "after=DatabaseFiles[db=" + (bodyFailed ? "ABSENT" : "PRESENT")),
            "cleanup identifies the unopened or remaining database without its name");
      }
    }
    checkProofCleanup(context, true);
    checkProofCleanup(context, false);
    return "PASS camera ledger teardown: exception and error primary, absent delete rejection retained, "
        + "cleanup-only failure retained, remaining database attempted, proof cleanup preserves earlier failures, "
        + "and successful path unchanged";
  }

  private static void checkProofCleanup(Context context, boolean failAssertion) throws Exception {
    LedgerFixture fixture = new LedgerFixture(context);
    List<String> attempts = new ArrayList<>();
    var assertion = new AssertionError();
    var deletion = new IllegalStateException();
    Context fault =
        new ContextWrapper(context) {
          @Override
          public boolean deleteDatabase(String name) {
            attempts.add(name);
            if (name.equals(fixture.crossRoute)) {
              if (failAssertion) throw deletion;
              return false;
            }
            return super.deleteDatabase(name);
          }
        };
    // Use the same outer resource for restoring only the files left by this injected failure.
    try (var finalCleanup = fixture.proofCleanup(context)) {
      Throwable observed = null;
      try (var cleanup = fixture.proofCleanup(fault)) {
        createTeardownDatabase(context, fixture.crossRoute);
        createTeardownDatabase(context, fixture.savedDestination);
        if (failAssertion) throw assertion;
      } catch (Exception | Error failure) {
        observed = failure;
      }
      require(
          attempts.equals(List.of(fixture.crossRoute, fixture.savedDestination)),
          "proof cleanup attempts the second database after the first fails");
      require(
          DatabaseFiles.observe(context, fixture.savedDestination).allAbsent(),
          "proof cleanup removed the second owned database and its sidecars");
      if (failAssertion) {
        require(
            observed == assertion && assertion.getSuppressed().length == 1,
            "proof cleanup preserves the earlier assertion");
        require(
            assertion.getSuppressed()[0] instanceof FixtureDiagnostic cleanup
                && cleanup.getCause() == deletion
                && cleanup.detail.contains("cleanup=proof-remainder role=CROSS_ROUTE delete=THREW"),
            "proof cleanup attaches the thrown deletion to the assertion");
      } else {
        require(
            observed instanceof FixtureDiagnostic cleanup
                && cleanup.detail.contains(
                    "cleanup=proof-remainder role=CROSS_ROUTE delete=RETURNED_FALSE"),
            "proof cleanup rejection alone fails the resource");
      }
    }
  }

  private static void createTeardownDatabase(Context context, String name) {
    try (CameraImportLedger ledger = new CameraImportLedger(context, name)) {
      ledger.getWritableDatabase();
    }
  }

  private static String diagnostic(Throwable failure) {
    return failure instanceof FixtureDiagnostic diagnostic ? diagnostic.detail : "";
  }

  private static void savedDestinationRegressions(
      Context context, String runId, String databaseName, Set<String> ownedFolders)
      throws Exception {
    try (CameraImportLedger ledger = new CameraImportLedger(context, databaseName)) {
      CameraPhotoImporter importer = new CameraPhotoImporter(context, ledger);

      String missingFolderName = "LoupeSynthetic-" + runId + "-missing";
      String missingFolder = CameraPhotoPublisher.folder(missingFolderName, false);
      ownedFolders.add(missingFolder);
      byte[] missingFixture = jpegWithComment(0x551L, "MISSING1");
      CameraImportFile missingSource =
          source(
              "wifi:missing-fixture",
              "DCIM/100CAM/MISSING.JPG",
              missingFixture,
              "missing-stamp",
              1794738100L);
      CameraPhotoImporter.Result initial =
          importPhoto(importer, missingSource, missingFixture, missingFolderName, false, true);
      require(
          initial.imported && ledger.contentCount() == 1 && ledger.receiptCount() == 1,
          "initial missing-destination fixture is published and recorded");
      CameraImportLedger.Content beforeDelete = ledger.known(missingSource);
      require(
          beforeDelete != null && beforeDelete.uri.equals(initial.uri.toString()),
          "missing-destination fixture has a verified source receipt");

      // Delete only the exact, test-owned URI after rechecking its owner and folder.
      deleteAfterOwnershipCheck(context, initial.uri, missingFolder);
      require(
          countOwnedVisible(context, missingFolder) == 0,
          "test-owned destination is absent before retry");
      final int[] sourceReads = {0};
      CameraPhotoImporter.Result restored =
          importer.importPhoto(
              missingSource,
              out -> {
                sourceReads[0]++;
                out.write(missingFixture);
              },
              missingFolderName,
              true,
              false,
              () -> false);
      require(
          sourceReads[0] == 1 && restored.imported && !restored.uri.equals(initial.uri),
          "missing fast-path destination is replaced from source bytes without dropping its receipt");
      require(
          ledger.pending().isEmpty() && ledger.contentCount() == 1 && ledger.receiptCount() == 1,
          "missing-destination retry leaves one content, one receipt, and no pending row");
      require(
          countOwnedVisible(context, missingFolder) == 1,
          "missing-destination retry creates exactly one visible test row");
      CameraImportLedger.Content restoredContent = ledger.known(missingSource);
      require(
          restoredContent != null
              && restoredContent.uri.equals(restored.uri.toString())
              && restoredContent.sourceSha.equals(beforeDelete.sourceSha),
          "restored receipt points to the new URI with the same original fingerprint");

      String corruptFolderName = "LoupeSynthetic-" + runId + "-corrupt";
      String corruptFolder = CameraPhotoPublisher.folder(corruptFolderName, false);
      ownedFolders.add(corruptFolder);
      byte[] sourceFixture = jpegWithComment(0x552L, "SOURCE01");
      byte[] alteredFixture = jpegWithComment(0x552L, "ALTERED1");
      require(
          sourceFixture.length == alteredFixture.length
              && !sha256(sourceFixture).equals(sha256(alteredFixture)),
          "corruption fixtures differ while retaining the same byte length");
      CameraImportFile corruptSource =
          source(
              "wifi:corrupt-fixture",
              "DCIM/100CAM/CORRUPT.JPG",
              sourceFixture,
              "corrupt-stamp",
              1794738101L);
      CameraPhotoImporter.Result corruptInitial =
          importPhoto(importer, corruptSource, sourceFixture, corruptFolderName, false, true);
      require(
          corruptInitial.imported && ledger.contentCount() == 2 && ledger.receiptCount() == 2,
          "initial corruption fixture is separately recorded");
      CameraImportLedger.Content contentBeforeMutation = ledger.known(corruptSource);
      require(
          contentBeforeMutation != null
              && contentBeforeMutation.uri.equals(corruptInitial.uri.toString()),
          "corruption fixture is linked to its owned saved copy");

      try (java.io.OutputStream output =
          context.getContentResolver().openOutputStream(corruptInitial.uri, "wt")) {
        if (output == null) throw new IOException("Could not rewrite owned synthetic media");
        output.write(alteredFixture);
      }
      require(
          Arrays.equals(alteredFixture, readMedia(context, corruptInitial.uri)),
          "owned saved copy contains the same-length synthetic mutation");
      require(
          countOwnedVisible(context, corruptFolder) == 1,
          "corruption fixture still has exactly one owned public row");

      boolean corruptionRejected = false;
      try {
        importPhoto(importer, corruptSource, sourceFixture, corruptFolderName, false, true);
      } catch (IOException expected) {
        corruptionRejected =
            expected.getMessage() != null && expected.getMessage().contains("Saved photo changed");
      }
      require(
          corruptionRejected,
          "verifyAll rejects a same-length changed saved copy with IOException");
      CameraImportLedger.Content afterRejectedRetry = ledger.known(corruptSource);
      require(
          afterRejectedRetry != null
              && afterRejectedRetry.sourceSha.equals(contentBeforeMutation.sourceSha)
              && afterRejectedRetry.storedSha.equals(contentBeforeMutation.storedSha)
              && afterRejectedRetry.uri.equals(contentBeforeMutation.uri)
              && afterRejectedRetry.storedBytes == contentBeforeMutation.storedBytes
              && afterRejectedRetry.captureGroup.equals(contentBeforeMutation.captureGroup),
          "corrupt destination rejection leaves its original receipt/content unchanged");
      require(
          ledger.pending().isEmpty()
              && ledger.contentCount() == 2
              && ledger.receiptCount() == 2
              && countOwnedVisible(context, corruptFolder) == 1,
          "corrupt destination rejection neither republishes nor changes row counts");
      require(
          Arrays.equals(alteredFixture, readMedia(context, corruptInitial.uri)),
          "strict check does not overwrite the externally changed saved copy");
    }
  }

  private static void crossRouteAndAssociation(
      Context context, String runId, String databaseName, Set<String> ownedFolders)
      throws Exception {
    try (CameraImportLedger ledger = new CameraImportLedger(context, databaseName)) {
      CameraPhotoImporter importer = new CameraPhotoImporter(context, ledger);
      String folder = "LoupeSynthetic-" + runId + "-identity";
      ownedFolders.add(CameraPhotoPublisher.folder(folder, false));

      byte[] jpeg = jpegWithComment(0x17L, "BASE0001");
      byte[] sameLengthDifferentContent = jpegWithComment(0x17L, "ALTER001");
      require(
          jpeg.length == sameLengthDifferentContent.length, "synthetic JPEG fixture lengths match");
      require(
          !sha256(jpeg).equals(sha256(sameLengthDifferentContent)),
          "synthetic JPEG fixture contents differ");

      CameraImportFile wifi =
          source(
              "wifi:synthetic-camera",
              "DCIM/100CAM/PAIR.JPG",
              jpeg,
              "2026-11-15T10:11:12",
              1794737472L);
      CameraPhotoImporter.Result first = importPhoto(importer, wifi, jpeg, folder, false, true);
      require(
          first.imported && first.contentChecked,
          "first synthetic Wi-Fi import publishes verified content");
      CameraImportLedger.Content original = ledger.find(sha256(jpeg), jpeg.length);
      require(
          original != null && original.uri.equals(first.uri.toString()),
          "first content is in the isolated ledger");

      CameraImportFile sd =
          source(
              "sd:synthetic-volume",
              "DCIM/100CAM/PAIR.JPG",
              jpeg,
              "2026-11-15T10:11:12",
              1794737472L);
      CameraPhotoImporter.Result second = importPhoto(importer, sd, jpeg, folder, false, true);
      require(
          !second.imported && second.contentChecked && first.uri.equals(second.uri),
          "same original from SD binds to the Wi-Fi publication");
      require(
          ledger.contentCount() == 1 && ledger.receiptCount() == 2,
          "one content has two cross-route receipts");

      CameraImportFile renamed =
          source(
              "wifi:synthetic-camera",
              "DCIM/100CAM/RENAMED.JPG",
              jpeg,
              "2026-11-15T10:11:12",
              1794737472L);
      CameraPhotoImporter.Result third = importPhoto(importer, renamed, jpeg, folder, false, true);
      require(
          !third.imported && third.uri.equals(first.uri),
          "different filename binds by verified content");
      require(
          ledger.contentCount() == 1 && ledger.receiptCount() == 3,
          "renamed identical content adds a receipt without another content row");

      final int[] reads = {0};
      CameraPhotoImporter.Result fast =
          importer.importPhoto(wifi, out -> reads[0]++, folder, true, false, () -> false);
      require(
          reads[0] == 0 && !fast.contentChecked && !fast.imported && fast.uri.equals(first.uri),
          "verified known source uses the explicit stable-source fast path without reading bytes");

      CameraPhotoImporter.Result strictChanged =
          importPhoto(importer, wifi, sameLengthDifferentContent, folder, false, true);
      require(
          strictChanged.imported
              && strictChanged.contentChecked
              && !strictChanged.uri.equals(first.uri),
          "strict verification does not conflate changed bytes at the same path and metadata");
      require(
          ledger.contentCount() == 2,
          "same source receipt tuple with changed contents produces a separate content row");

      // A cross-route bind carries the existing capture group to the route where the RAW arrives.
      byte[] pairJpeg = jpegWithComment(0x17L, "PAIR0001");
      CameraImportFile pairWifi =
          source("wifi:pair-camera", "DCIM/100CAM/GROUP.JPG", pairJpeg, "pair-date", 1794737473L);
      CameraPhotoImporter.Result pair =
          importPhoto(importer, pairWifi, pairJpeg, folder, false, true);
      CameraImportFile pairSd =
          source("sd:pair-volume", "DCIM/100CAM/GROUP.JPG", pairJpeg, "pair-date", 1794737473L);
      importPhoto(importer, pairSd, pairJpeg, folder, false, true);
      CameraImportLedger.Content pairContent = ledger.find(sha256(pairJpeg), pairJpeg.length);
      require(
          pairContent != null && pairContent.uri.equals(pair.uri.toString()),
          "pair JPEG content is ledger-backed");
      CameraImportFile rawOnSd =
          new CameraImportFile(
              "sd:pair-volume", "DCIM/100CAM/GROUP.RW2", 64, "pair-date", 1794737473L);
      String rawGroup = ledger.captureGroup(rawOnSd, syntheticHash("synthetic-raw-payload"), "RAW");
      require(
          pairContent.captureGroup.equals(rawGroup),
          "RAW capture group follows the verified cross-route JPEG receipt");
    }
  }

  private static void recoverBoundary(
      Context context,
      String runId,
      CameraPhotoPublisher.Boundary boundary,
      Set<String> ownedFolders,
      List<OwnedDatabase> databases)
      throws Exception {
    String suffix = boundary.name().toLowerCase(java.util.Locale.ROOT);
    String databaseName = "camera-ledger-check-" + runId + "-" + suffix + ".db";
    databases.add(new OwnedDatabase(databaseName, DatabaseRole.RECOVERY, boundary));
    String folderName = "LoupeSynthetic-" + runId + "-" + suffix;
    String relativeFolder = CameraPhotoPublisher.folder(folderName, false);
    ownedFolders.add(relativeFolder);
    byte[] bytes = jpegWithComment(0x731L + boundary.ordinal(), "CRASH000");
    CameraImportFile source =
        source(
            "wifi:crash-fixture",
            "DCIM/100CAM/CRASH.JPG",
            bytes,
            "crash-" + suffix,
            1794738000L + boundary.ordinal());
    try (CameraImportLedger ledger = new CameraImportLedger(context, databaseName)) {
      File stagedDir = context.getCacheDir();
      try (CameraImportCopy.Prepared prepared =
          CameraImportCopy.prepare(stagedDir, source, out -> out.write(bytes), () -> false)) {
        boolean crashed = false;
        CameraPhotoPublisher publisher =
            new CameraPhotoPublisher(
                context,
                ledger,
                reached -> {
                  if (reached == boundary) throw new SimulatedProcessLoss();
                });
        try {
          publisher.publish(
              source,
              prepared.file,
              prepared.sourceSha256,
              "JPEG",
              relativeFolder,
              "synthetic-group-" + suffix,
              () -> false);
        } catch (SimulatedProcessLoss expected) {
          crashed = true;
        }
        require(crashed, "fault injection reached " + boundary);
      }
    }

    // Reopen the unique SQLite file to model process recreation, then recover journal + MediaStore.
    try (CameraImportLedger reopened = new CameraImportLedger(context, databaseName)) {
      CameraPhotoImporter importer = new CameraPhotoImporter(context, reopened);
      importer.recover(() -> false);
      require(reopened.pending().isEmpty(), "recovery closes pending journal at " + boundary);
      long expectedBeforeRetry = isAtOrAfterVerified(boundary) ? 1 : 0;
      require(
          reopened.contentCount() == expectedBeforeRetry,
          "recovery commits only verified/published states at " + boundary);
      require(
          reopened.receiptCount() == expectedBeforeRetry,
          "recovery saves receipt only at/after verified state " + boundary);
      require(
          countOwnedVisible(context, relativeFolder) == expectedBeforeRetry,
          "recovery exposes the expected public row at " + boundary);

      CameraPhotoImporter.Result retry =
          importPhoto(importer, source, bytes, folderName, false, true);
      require(retry.contentChecked, "retry verifies source contents at " + boundary);
      require(reopened.pending().isEmpty(), "retry leaves no pending journal at " + boundary);
      require(
          reopened.contentCount() == 1 && reopened.receiptCount() == 1,
          "retry converges to one content and receipt at " + boundary);
      require(
          countOwnedVisible(context, relativeFolder) == 1,
          "retry does not duplicate publication at " + boundary);
    }
  }

  private static boolean isAtOrAfterVerified(CameraPhotoPublisher.Boundary boundary) {
    return boundary == CameraPhotoPublisher.Boundary.FILE_VERIFIED
        || boundary == CameraPhotoPublisher.Boundary.PUBLISHED
        || boundary == CameraPhotoPublisher.Boundary.RECEIPT_SAVED;
  }

  private static CameraPhotoImporter.Result importPhoto(
      CameraPhotoImporter importer,
      CameraImportFile source,
      byte[] bytes,
      String folder,
      boolean stableSource,
      boolean verifyAll)
      throws IOException {
    return importer.importPhoto(
        source, out -> out.write(bytes), folder, stableSource, verifyAll, () -> false);
  }

  private static CameraImportFile source(
      String sourceId, String path, byte[] bytes, String stamp, long captureSeconds) {
    return new CameraImportFile(sourceId, path, bytes.length, stamp, captureSeconds);
  }

  private static byte[] jpegWithComment(long seed, String comment) throws IOException {
    if (comment.getBytes(StandardCharsets.US_ASCII).length != 8)
      throw new IllegalArgumentException("comment must be 8 bytes");
    Random random = new Random(seed);
    int width = 192;
    int height = 128;
    int[] pixels = new int[width * height];
    for (int i = 0; i < pixels.length; i++) {
      pixels[i] = 0xff000000 | random.nextInt(1 << 24);
    }
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    try {
      bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
      ByteArrayOutputStream image = new ByteArrayOutputStream();
      if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 91, image))
        throw new IOException("JPEG fixture encode failed");
      byte[] jpeg = image.toByteArray();
      if (jpeg.length < 4
          || (jpeg[jpeg.length - 2] & 0xff) != 0xff
          || (jpeg[jpeg.length - 1] & 0xff) != 0xd9) {
        throw new IOException("JPEG fixture has no end marker");
      }
      ByteArrayOutputStream withComment = new ByteArrayOutputStream(jpeg.length + 12);
      withComment.write(jpeg, 0, jpeg.length - 2);
      withComment.write(new byte[] {(byte) 0xff, (byte) 0xfe, 0, 10});
      withComment.write(comment.getBytes(StandardCharsets.US_ASCII));
      withComment.write(jpeg, jpeg.length - 2, 2);
      return withComment.toByteArray();
    } finally {
      bitmap.recycle();
    }
  }

  private static String sha256(byte[] bytes) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(bytes);
    StringBuilder out = new StringBuilder(hash.length * 2);
    for (byte value : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
    return out.toString();
  }

  private static String syntheticHash(String value) throws Exception {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] readMedia(Context context, Uri uri) throws IOException {
    try (java.io.InputStream input = context.getContentResolver().openInputStream(uri)) {
      if (input == null) throw new IOException("Could not read owned synthetic media");
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
      return bytes.toByteArray();
    }
  }

  private static long countOwnedVisible(Context context, String relativeFolder) throws IOException {
    Bundle args = new Bundle();
    args.putString(
        ContentResolver.QUERY_ARG_SQL_SELECTION,
        MediaStore.MediaColumns.RELATIVE_PATH
            + "=? AND "
            + MediaStore.MediaColumns.OWNER_PACKAGE_NAME
            + "=? AND "
            + MediaStore.MediaColumns.IS_PENDING
            + "=0");
    args.putStringArray(
        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
        new String[] {relativeFolder, context.getPackageName()});
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[] {MediaStore.MediaColumns._ID},
                args,
                null)) {
      if (cursor == null) throw new IOException("Could not query synthetic publication folder");
      return cursor.getCount();
    }
  }

  private static void cleanOwnedMedia(Context context, String folder) throws IOException {
    ContentResolver resolver = context.getContentResolver();
    Bundle args = new Bundle();
    args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
    args.putString(
        ContentResolver.QUERY_ARG_SQL_SELECTION,
        MediaStore.MediaColumns.RELATIVE_PATH
            + "=? AND "
            + MediaStore.MediaColumns.OWNER_PACKAGE_NAME
            + "=?");
    args.putStringArray(
        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
        new String[] {folder, context.getPackageName()});
    List<Uri> candidates = new ArrayList<>();
    try (Cursor cursor =
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            new String[] {MediaStore.MediaColumns._ID},
            args,
            null)) {
      if (cursor == null) throw new IOException("Could not find owned synthetic media for cleanup");
      while (cursor.moveToNext()) {
        candidates.add(
            Uri.withAppendedPath(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Long.toString(cursor.getLong(0))));
      }
    }
    FixtureDiagnostic first = null;
    for (Uri uri : candidates) {
      try {
        deleteAfterOwnershipCheck(context, uri, folder);
      } catch (Exception | Error failure) {
        first = recordCleanup(first, new FixtureDiagnostic("cleanup=media-row", failure));
      }
    }
    if (first != null) throw first;
  }

  private static void deleteAfterOwnershipCheck(Context context, Uri uri, String expectedFolder)
      throws IOException {
    if (!"content".equals(uri.getScheme())
        || !"media".equals(uri.getAuthority())
        || !uri.getPath().matches("/(?:external|external_primary)/images/media/[0-9]+")) {
      throw new IOException("Refusing to delete unexpected synthetic media URI");
    }
    Bundle args = new Bundle();
    args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
    try (Cursor cursor = context.getContentResolver().query(uri, MEDIA_FIELDS, args, null)) {
      if (cursor == null || cursor.getCount() != 1 || !cursor.moveToFirst()) {
        throw new IOException("Could not verify synthetic media URI before deletion");
      }
      String owner = cursor.getString(1);
      String folder = cursor.getString(2);
      if (!context.getPackageName().equals(owner) || !expectedFolder.equals(folder)) {
        throw new IOException("Synthetic media ownership/path mismatch; refusing deletion");
      }
    }
    if (context.getContentResolver().delete(uri, null, null) != 1) {
      throw new IOException("Could not delete verified synthetic media row");
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static final class SimulatedProcessLoss extends Error {
    private static final long serialVersionUID = 1L;
  }
}
