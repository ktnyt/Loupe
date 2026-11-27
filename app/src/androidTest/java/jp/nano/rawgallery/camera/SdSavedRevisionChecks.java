package jp.nano.rawgallery.camera;

import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jp.nano.rawgallery.PhotoStorage;

/**
 * Scoped emulator fixtures. Source receipts are synthetic; saved targets are owned MediaStore rows.
 */
public final class SdSavedRevisionChecks {
  private static final CameraPhotoPublisher.CommitCheck CHECK = () -> {};
  private static final int JPEG = 293;
  private static final int RAW = 34;

  public static String run(Instrumentation test) throws Exception {
    Context context = test.getTargetContext();
    require(
        "jp.nano.rawgallery.sd166".equals(context.getPackageName())
            && ("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE)),
        "isolated SD emulator");
    String db = "camera-sd-revision-" + UUID.randomUUID() + ".db";
    var resolver = context.getContentResolver();
    rejectDestinationRace(context, false);
    rejectDestinationRace(context, true);
    missingDestinationIsHistory(context);
    List<Uri> owned = new ArrayList<>();
    byte[] jpeg = fixtureJpeg(0xff884411);
    byte[] raw = fixtureJpeg(0xff117733);
    String sid = CameraDocumentSource.originalSourceId(CameraSourceKey.sd("ab12-cd34", 1000000));
    try (var ledger = new CameraImportLedger(context, db)) {
      Uri jpg = savedTarget(context, owned, jpeg, "JPG");
      Uri orf = savedTarget(context, owned, raw, "ORF");
      List<CameraDocumentSource.Entry> entries = new ArrayList<>();
      String jpegSha = sha(jpeg), rawSha = sha(raw);
      for (int i = 0; i < JPEG + RAW; i++) {
        boolean isJpeg = i < JPEG;
        var file =
            new CameraImportFile(
                sid,
                "DCIM/" + i + (isJpeg ? ".JPG" : ".ORF"),
                isJpeg ? jpeg.length : raw.length,
                "1700000000000",
                1700000000);
        String digest = isJpeg ? jpegSha : rawSha;
        ledger.adopt(
            file,
            digest,
            digest,
            file.bytes,
            (isJpeg ? jpg : orf).toString(),
            isJpeg ? "JPEG" : "RAW",
            "fixture");
        entries.add(new CameraDocumentSource.Entry(Uri.EMPTY, file));
      }
      var importer = new CameraPhotoImporter(context, ledger, PhotoStorage.PRIMARY);
      var historyOnly = new CameraImportMetrics(context, "synthetic-saf-sd");
      importer.setMetrics(historyOnly);
      var snapshots = new java.util.concurrent.atomic.AtomicInteger();
      var bindingChecks = new java.util.concurrent.atomic.AtomicInteger();
      require(
          CameraDifferentialService.classifySd(
                      entries,
                      true,
                      () -> false,
                      historyOnly,
                      key -> {
                        snapshots.incrementAndGet();
                        return importer.sourceSnapshot(key);
                      },
                      () -> bindingChecks.incrementAndGet(),
                      () -> {})
                  .isEmpty()
              && snapshots.get() == 1
              && bindingChecks.get() == 2
              && historyOnly.known == 327
              && historyOnly.destinationBytes == 0
              && historyOnly.sourceBytes == 0
              && ledger.sdRevision(jpegSha) == null
              && ledger.sdRevision(rawSha) == null,
          "Historical original receipts use one local query, preserve the v4 state and skip byte processing");

      var bootstrap = new CameraImportMetrics(context, "synthetic-saf-sd");
      importer.setMetrics(bootstrap);
      long start = SystemClock.elapsedRealtime();
      for (var item : entries) {
        var result =
            importer.importPhoto(
                item.file,
                out -> {
                  throw new AssertionError("source was reread");
                },
                "fixture",
                true,
                false,
                () -> false,
                CHECK,
                new CameraPhotoImporter.Attempt(),
                true);
        require(!result.imported && result.contentChecked, "bootstrap is a full saved-bytes match");
        bootstrap.matches++;
        bootstrap.processed++;
      }
      long bootstrapMs = SystemClock.elapsedRealtime() - start;
      require(
          bootstrap.matches == JPEG + RAW
              && bootstrap.sourceBytes == 0
              && bootstrap.destinationBytes == (long) JPEG * jpeg.length + (long) RAW * raw.length,
          "bootstrap reads each candidate's destination exactly once");

      var repeat = new CameraImportMetrics(context, "synthetic-saf-sd");
      importer.setMetrics(repeat);
      start = SystemClock.elapsedRealtime();
      require(
          classify(entries, importer, repeat).isEmpty()
              && repeat.known == JPEG + RAW
              && repeat.checked == JPEG + RAW
              && repeat.processed == 0
              && repeat.matches == 0
              && repeat.candidates == 0
              && repeat.sourceBytes == 0
              && repeat.destinationBytes == 0
              && ledger.receiptCount() == JPEG + RAW
              && ledger.contentCount() == 2,
          "ordinary repeat skips both source and destination bodies and keeps history");
      long repeatMs = SystemClock.elapsedRealtime() - start;
      indexPendingGuards(context, importer, entries);
      mixedIndexPending(context, importer, ledger, owned, entries, sid);

      CameraImportFile previous = entries.get(0).file;
      var changed =
          new CameraImportFile(
              sid, previous.relativePath, previous.bytes, "1700000000001", previous.captureSeconds);
      var changedEntry = new CameraDocumentSource.Entry(Uri.EMPTY, changed);
      var mixed = new ArrayList<>(entries);
      mixed.set(0, changedEntry);
      var mixedMetrics = new CameraImportMetrics(context, "synthetic-saf-sd");
      importer.setMetrics(mixedMetrics);
      require(
          classify(mixed, importer, mixedMetrics).equals(List.of(changedEntry))
              && mixedMetrics.known == JPEG + RAW - 1,
          "changed source timestamp is the only candidate");
      var checked =
          importer.importPhoto(
              changed,
              out -> out.write(jpeg),
              "fixture",
              true,
              false,
              () -> false,
              CHECK,
              new CameraPhotoImporter.Attempt(),
              true);
      require(
          checked.contentChecked
              && !checked.imported
              && mixedMetrics.sourceBytes == jpeg.length
              && mixedMetrics.destinationBytes == jpeg.length,
          "changed source tuple compares both streams");
      try {
        importer.knownSdRevision(
            previous,
            () -> false,
            CHECK,
            () -> {
              throw new MountedCameraCard.Unavailable("fixture grant revoked");
            });
        throw new AssertionError("revoked grant confirmed cached receipt");
      } catch (MountedCameraCard.Unavailable expected) {
      }

      var content = ledger.known(previous);
      var proof = ledger.sdRevision(jpegSha);
      require(proof != null, "verified JPEG has revision proof");
      observedFailures(context, content, proof);
      var corrupt = jpeg.clone();
      corrupt[corrupt.length - 1] ^= 1;
      // Edit the owned fixture directly so a MediaStore row can retain its same size and revision.
      boolean hiddenMetadataUnchanged =
          overwriteOwned(context, jpg, corrupt)
              && proof.equals(SdSavedPhotoRevision.capture(context, content, () -> false));
      try {
        importer.importPhoto(
            previous,
            out -> out.write(jpeg),
            "fixture",
            true,
            true,
            () -> false,
            CHECK,
            new CameraPhotoImporter.Attempt(),
            true);
        throw new AssertionError("explicit verification ignored changed saved bytes");
      } catch (IOException expected) {
        require(
            expected.getMessage().contains("Saved photo changed")
                && ledger.sdRevision(jpegSha) == null,
            "explicit SHA detects corruption and invalidates the cached evidence");
      }

      require(ledger.sdRevision(rawSha) != null, "other content retains its revision proof");
      ledger.forgetMissing(rawSha);
      require(ledger.sdRevision(rawSha) == null, "deleting parent content cascades to its proof");
      return "PASS sd-revision receipts=327 concreteTargets=2 oneSnapshotWithoutProof=true bootstrapMs="
          + bootstrapMs
          + " bootstrapDestinationBytes="
          + bootstrap.destinationBytes
          + " repeatMs="
          + repeatMs
          + " repeatSourceBytes=0 repeatDestinationBytes=0 known=327 candidates=0 mixedCandidates=1"
          + " hiddenMetadataUnchanged="
          + hiddenMetadataUnchanged
          + " explicitMismatch=true"
          + " unlinkDuringRead=true revisionDuringExplicitRead=true"
          + " mixedListing=1299 priorReceipts=327 changed=true backdated=true pending=1 savedAfterPending=true indexedRetry=true"
          + " missingDestinationHistorical=true explicitRepair=true";
    } finally {
      for (Uri uri : owned) resolver.delete(uri, null, null);
      require(context.deleteDatabase(db), "isolated ledger removed");
    }
  }

  private static void indexPendingGuards(
      Context context, CameraPhotoImporter importer, List<CameraDocumentSource.Entry> old)
      throws IOException {
    var pending = CameraDocumentSourceChecks.registrationFailure(false);
    var ambiguous = CameraDocumentSourceChecks.registrationFailure(true);
    require(
        pending.reason == CameraDocumentSource.OriginalAccessException.Reason.INDEX_PENDING
            && ambiguous.reason == CameraDocumentSource.OriginalAccessException.Reason.MAPPING,
        "Fixture distinguishes a confirmed missing row from an ambiguous mapping");
    var stopped = new CameraImportMetrics(context, "synthetic-saf-sd");
    var checks = new java.util.concurrent.atomic.AtomicInteger();
    try {
      CameraDifferentialService.classifySd(
          old,
          true,
          () -> false,
          stopped,
          importer::sourceSnapshot,
          () -> {
            if (checks.incrementAndGet() == 2)
              throw new MountedCameraCard.Unavailable("fixture tree grant lost");
          },
          () -> {});
      throw new AssertionError("Revoked grant before classification acceptance was ignored");
    } catch (MountedCameraCard.Unavailable expected) {
      require(
          stopped.indexPending == 0 && stopped.candidates == -1 && checks.get() == 2,
          "Grant loss prevents using the history plan");
    }
    var sourceChanged = new CameraImportMetrics(context, "synthetic-saf-sd");
    var cancel = new java.util.concurrent.atomic.AtomicBoolean();
    try {
      CameraDifferentialService.classifySd(
          old,
          true,
          cancel::get,
          sourceChanged,
          key -> {
            cancel.set(true);
            return importer.sourceSnapshot(key);
          },
          CHECK,
          () -> {});
      throw new AssertionError("Cancellation after snapshot was ignored");
    } catch (java.io.InterruptedIOException expected) {
      require(
          sourceChanged.checked == 0 && sourceChanged.candidates == -1,
          "Cancellation stops classification");
    }
    try {
      CameraDifferentialService.importSdCandidates(
          old,
          () -> false,
          stopped,
          entry -> {
            throw ambiguous;
          },
          CHECK,
          () -> {});
      throw new AssertionError("Ambiguous mapping was deferred");
    } catch (CameraDocumentSource.OriginalAccessException expected) {
      require(
          expected == ambiguous && stopped.processed == 0 && stopped.indexPending == 0,
          "Ambiguous mapping stops the byte loop");
    }
    try {
      CameraDifferentialService.importSdCandidates(
          old,
          () -> false,
          stopped,
          entry -> {
            throw pending;
          },
          () -> {
            throw new java.io.InterruptedIOException("fixture cancelled");
          },
          () -> {});
      throw new AssertionError("Cancellation at deferral was ignored");
    } catch (java.io.InterruptedIOException expected) {
      require(
          stopped.processed == 0 && stopped.indexPending == 0, "Cancellation stops the byte loop");
    }
  }

  private static void mixedIndexPending(
      Context context,
      CameraPhotoImporter importer,
      CameraImportLedger ledger,
      List<Uri> owned,
      List<CameraDocumentSource.Entry> old,
      String source)
      throws Exception {
    byte[] before = fixtureJpeg(0xff220077),
        delayed = fixtureJpeg(0xff227700),
        after = fixtureJpeg(0xffffcc00);
    CameraDocumentSource.Entry beforeEntry =
        new CameraDocumentSource.Entry(
            Uri.EMPTY,
            new CameraImportFile(
                source, "DCIM/backdated.JPG", before.length, "1600000000000", 1600000000));
    CameraDocumentSource.Entry pendingEntry = fixtureEntry(source, delayed.length);
    CameraDocumentSource.Entry afterEntry = fixtureEntry(source, after.length);
    var listing = new ArrayList<>(old);
    byte[] sameOriginal = fixtureJpeg(0xff884411);
    var previous = old.get(0).file;
    var changedEntry =
        new CameraDocumentSource.Entry(
            Uri.EMPTY,
            new CameraImportFile(
                source,
                previous.relativePath,
                previous.bytes,
                "1700000000002",
                previous.captureSeconds));
    listing.set(0, changedEntry);
    listing.addAll(List.of(beforeEntry, pendingEntry, afterEntry));
    var filler = new java.util.HashSet<CameraDocumentSource.Entry>();
    while (listing.size() < 1299) {
      var entry = fixtureEntry(source, after.length);
      filler.add(entry);
      listing.add(entry);
    }

    var pending = CameraDocumentSourceChecks.registrationFailure(false);
    for (boolean indexed : new boolean[] {false, true}) {
      // Each pass consumes a fresh list of the same source, with Android registration changing
      // between passes.
      var metrics = new CameraImportMetrics(context, "synthetic-saf-sd");
      importer.setMetrics(metrics);
      ImportProgress.INSTANCE.connecting("fixture");
      var candidates = classify(new ArrayList<>(listing), importer, metrics);
      CameraDifferentialService.importSdCandidates(
          candidates,
          () -> false,
          metrics,
          entry -> {
            require(
                !old.contains(entry),
                "Historical entry reached the original/destination processing hook");
            if (entry == pendingEntry && !indexed) throw pending;
            if (filler.contains(entry))
              return new CameraPhotoImporter.Result(Uri.EMPTY, false, true, 0);
            byte[] original =
                entry == changedEntry
                    ? sameOriginal
                    : entry == beforeEntry ? before : entry == pendingEntry ? delayed : after;
            var result =
                importer.importPhoto(
                    entry.file,
                    out -> out.write(original),
                    "sd-revision-fixtures",
                    true,
                    false,
                    () -> false,
                    CHECK,
                    new CameraPhotoImporter.Attempt(),
                    true);
            if (result.imported) owned.add(result.uri);
            return result;
          },
          CHECK,
          () -> {});

      require(
          metrics.checked == 1299
              && metrics.known == (indexed ? 329 : 326)
              && metrics.candidates == (indexed ? 970 : 973)
              && metrics.saved == (indexed ? 1 : 2)
              && metrics.matches == (indexed ? 969 : 970)
              && metrics.indexPending == (indexed ? 0 : 1)
              && metrics.processed == (indexed ? 970 : 972),
          "New saves, matches and confirmed missing registrations have distinct counts");
      require(
          ledger.known(beforeEntry.file) != null
              && ledger.known(afterEntry.file) != null
              && ledger.known(changedEntry.file) != null
              && (ledger.known(pendingEntry.file) != null) == indexed
              && ledger.receiptCount() == (indexed ? 331 : 330)
              && ledger.contentCount() == (indexed ? 5 : 4)
              && ledger.pending().isEmpty(),
          "Later originals save immediately and the deferred original creates history only on retry");
    }
  }

  private static CameraDocumentSource.Entry fixtureEntry(String source, long bytes) {
    return new CameraDocumentSource.Entry(
        Uri.EMPTY,
        new CameraImportFile(
            source, "DCIM/" + UUID.randomUUID() + ".JPG", bytes, "1700000000000", 1700000000));
  }

  private static void missingDestinationIsHistory(Context context) throws Exception {
    String db = "camera-sd-missing-history-" + UUID.randomUUID() + ".db";
    var owned = new ArrayList<Uri>();
    var resolver = context.getContentResolver();
    try (var ledger = new CameraImportLedger(context, db)) {
      byte[] original = fixtureJpeg(0xff8b1989);
      String digest = sha(original);
      String source =
          CameraDocumentSource.originalSourceId(CameraSourceKey.sd("ab12-cd34", 1000000));
      String otherSource =
          CameraDocumentSource.originalSourceId(CameraSourceKey.sd("ab12-cd35", 1000000));
      var entry = fixtureEntry(source, original.length);
      var noStamp =
          new CameraDocumentSource.Entry(
              Uri.EMPTY,
              new CameraImportFile(source, "DCIM/unknown-stamp.JPG", original.length, "0", 0));
      var otherCard =
          new CameraDocumentSource.Entry(
              Uri.EMPTY,
              new CameraImportFile(
                  otherSource,
                  entry.file.relativePath,
                  entry.file.bytes,
                  entry.file.sourceStamp,
                  entry.file.captureSeconds));
      Uri destination = savedTarget(context, owned, original, "JPG");
      var receipt =
          ledger.adopt(
              entry.file,
              digest,
              digest,
              original.length,
              destination.toString(),
              "JPEG",
              "fixture");
      ledger.bind(noStamp.file, receipt);
      require(resolver.delete(destination, null, null) == 1, "remove scoped destination fixture");
      owned.remove(destination);

      var importer = new CameraPhotoImporter(context, ledger, PhotoStorage.PRIMARY);
      var ordinary = new CameraImportMetrics(context, "synthetic-saf-sd");
      importer.setMetrics(ordinary);
      require(
          classify(List.of(entry, noStamp, otherCard), importer, ordinary)
                  .equals(List.of(noStamp, otherCard))
              && ordinary.known == 1
              && ordinary.sourceBytes == 0
              && ordinary.destinationBytes == 0
              && receipt.sameRow(ledger.known(entry.file))
              && ledger.sdRevision(digest) == null,
          "Only exact scoped receipt is historical when the prior copy is missing; unknown stamp and changed card still require content");

      var verify = new CameraImportMetrics(context, "synthetic-saf-sd");
      importer.setMetrics(verify);
      var candidates =
          CameraDifferentialService.classifySd(
              List.of(entry),
              false,
              () -> false,
              verify,
              key -> {
                throw new AssertionError("Explicit verification consulted the history snapshot");
              },
              CHECK,
              () -> {});
      CameraDifferentialService.importSdCandidates(
          candidates,
          () -> false,
          verify,
          item -> {
            var result =
                importer.importPhoto(
                    item.file,
                    out -> out.write(original),
                    "sd-revision-fixtures",
                    true,
                    true,
                    () -> false,
                    CHECK,
                    new CameraPhotoImporter.Attempt(),
                    true);
            if (result.imported) owned.add(result.uri);
            return result;
          },
          CHECK,
          () -> {});
      var repaired = ledger.known(entry.file);
      require(
          verify.known == 0
              && verify.saved == 1
              && verify.sourceBytes == original.length
              && verify.destinationBytes >= original.length
              && repaired.sourceSha.equals(digest)
              && !repaired.uri.equals(receipt.uri)
              && repaired.uri.equals(ledger.known(noStamp.file).uri)
              && ledger.receiptCount() == 2
              && ledger.pending().isEmpty(),
          "Explicit verification restores a conclusively missing copy and retains both historical receipts");
    } finally {
      for (Uri uri : owned) resolver.delete(uri, null, null);
      require(context.deleteDatabase(db), "isolated missing destination ledger removed");
    }
  }

  private static void rejectDestinationRace(Context context, boolean explicit) throws Exception {
    String db = "camera-sd-read-race-" + UUID.randomUUID() + ".db";
    var owned = new ArrayList<Uri>();
    var resolver = context.getContentResolver();
    var raced = new java.util.concurrent.atomic.AtomicBoolean();
    try (var ledger = new CameraImportLedger(context, db)) {
      byte[] bytes = fixtureJpeg(0xff554488);
      Uri target = savedTarget(context, owned, bytes, "JPG");
      String sha = sha(bytes);
      String id = CameraDocumentSource.originalSourceId(CameraSourceKey.sd("ab12-cd34", 1000000));
      var source =
          new CameraImportFile(id, "DCIM/receipt.JPG", bytes.length, "1700000000000", 1700000000);
      var current =
          ledger.adopt(source, sha, sha, bytes.length, target.toString(), "JPEG", "fixture");
      var requested =
          explicit
              ? new CameraImportFile(
                  id, "DCIM/another.JPG", bytes.length, source.sourceStamp, source.captureSeconds)
              : source;
      var importer =
          new CameraPhotoImporter(
              context,
              ledger,
              PhotoStorage.PRIMARY,
              count -> {
                if (raced.get()) return;
                if (explicit) {
                  var changed = new ContentValues();
                  changed.put(
                      MediaStore.MediaColumns.DISPLAY_NAME,
                      "changed-" + UUID.randomUUID() + ".JPG");
                  require(
                      resolver.update(target, changed, null, null) == 1,
                      "fixture revises a saved MediaStore row while it is being hashed");
                } else {
                  require(
                      resolver.delete(target, null, null) == 1,
                      "fixture deletes its own saved row while it is being hashed");
                  owned.remove(target);
                }
                raced.set(true);
              });
      try {
        importer.importPhoto(
            requested,
            out -> {
              if (!explicit)
                throw new AssertionError("unseeded receipt should verify stored bytes first");
              out.write(bytes);
            },
            "fixture",
            true,
            explicit,
            () -> false,
            CHECK,
            new CameraPhotoImporter.Attempt(),
            true);
        throw new AssertionError("changed live destination accepted as already saved");
      } catch (IOException expected) {
        require(
            raced.get()
                && ledger.receiptCount() == 1
                && current.sameRow(ledger.known(source))
                && (explicit ? ledger.known(requested) == null : ledger.known(requested) != null)
                && ledger.pending().isEmpty()
                && ledger.sdRevision(sha) == null,
            "a live destination race cannot seed evidence, bind a receipt or start replacement");
      }
    } finally {
      for (Uri uri : owned) resolver.delete(uri, null, null);
      require(context.deleteDatabase(db), "isolated read race ledger removed");
    }
  }

  private static List<CameraDocumentSource.Entry> classify(
      List<CameraDocumentSource.Entry> entries,
      CameraPhotoImporter importer,
      CameraImportMetrics metrics)
      throws IOException {
    return CameraDifferentialService.classifySd(
        entries, true, () -> false, metrics, importer::sourceSnapshot, CHECK, () -> {});
  }

  private static Uri savedTarget(Context context, List<Uri> owned, byte[] bytes, String suffix)
      throws IOException {
    var resolver = context.getContentResolver();
    var values = new ContentValues();
    values.put(
        MediaStore.MediaColumns.DISPLAY_NAME, "sd-revision-" + UUID.randomUUID() + "." + suffix);
    values.put(
        MediaStore.MediaColumns.MIME_TYPE,
        suffix.equals("JPG") ? "image/jpeg" : "image/x-olympus-orf");
    values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CameraSync/sd-revision-fixtures/");
    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
    Uri uri = resolver.insert(MediaStore.Images.Media.getContentUri(PhotoStorage.PRIMARY), values);
    require(uri != null, "owned target created");
    owned.add(uri);
    try (var stream = resolver.openOutputStream(uri)) {
      stream.write(bytes);
    }
    values.clear();
    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
    require(resolver.update(uri, values, null, null) == 1, "owned target published");
    return uri;
  }

  private static byte[] fixtureJpeg(int color) throws IOException {
    var image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
    try (var bytes = new ByteArrayOutputStream()) {
      image.eraseColor(color);
      require(image.compress(Bitmap.CompressFormat.JPEG, 92, bytes), "generated synthetic fixture");
      return bytes.toByteArray();
    } finally {
      image.recycle();
    }
  }

  private static String sha(byte[] b) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(b);
    var hex = new StringBuilder(64);
    for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
    return hex.toString();
  }

  private static boolean overwriteOwned(Context context, Uri uri, byte[] bytes) throws IOException {
    String path;
    var resolver = context.getContentResolver();
    try (var cursor =
        resolver.query(
            uri,
            new String[] {MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.OWNER_PACKAGE_NAME},
            null,
            null,
            null)) {
      require(
          cursor != null
              && cursor.moveToFirst()
              && context.getPackageName().equals(cursor.getString(1)),
          "direct fixture mutation is restricted to its owned row");
      path = cursor.getString(0);
    }
    if (path != null) {
      var file = new java.io.File(path);
      long modified = file.lastModified();
      try (var stream = new RandomAccessFile(file, "rw")) {
        stream.write(bytes);
        return modified != 0 && file.setLastModified(modified);
      } catch (java.io.FileNotFoundException blocked) {
        // Some emulator providers only expose owned writes through their content resolver.
      }
    }
    try (var stream = resolver.openOutputStream(uri, "wt")) {
      stream.write(bytes);
    }
    return false;
  }

  private static void observedFailures(
      Context context, CameraImportLedger.Content content, SdSavedPhotoRevision proof)
      throws IOException {
    String[] columns = {
      "_id",
      "volume_name",
      "owner_package_name",
      "_size",
      "is_pending",
      "is_trashed",
      "generation_added",
      "generation_modified"
    };
    long id = android.content.ContentUris.parseId(Uri.parse(content.uri));
    Object[] values = {
      id,
      PhotoStorage.PRIMARY,
      context.getPackageName(),
      content.storedBytes,
      0,
      0,
      proof.generationAdded(),
      proof.generationModified()
    };
    try (var cursor = new MatrixCursor(columns)) {
      cursor.addRow(values);
      require(
          proof.equals(
              SdSavedPhotoRevision.observed(
                  cursor,
                  content,
                  id,
                  PhotoStorage.PRIMARY,
                  context.getPackageName(),
                  proof.epoch())),
          "complete pinned row matches recorded revision");
    }
    Object[] changes = {
      id + 1,
      "other",
      "another.owner",
      content.storedBytes + 1,
      1,
      1,
      proof.generationAdded() + 1,
      proof.generationModified() + 1
    };
    for (int i = 0; i < columns.length; i++) {
      try (var cursor = new MatrixCursor(columns)) {
        var changed = values.clone();
        changed[i] = changes[i];
        cursor.addRow(changed);
        require(
            !proof.equals(
                SdSavedPhotoRevision.observed(
                    cursor,
                    content,
                    id,
                    PhotoStorage.PRIMARY,
                    context.getPackageName(),
                    proof.epoch())),
            "altered row must not match");
      }
    }
    try (var cursor = new MatrixCursor(columns)) {
      cursor.addRow(values);
      require(
          !proof.equals(
              SdSavedPhotoRevision.observed(
                  cursor,
                  content,
                  id,
                  PhotoStorage.PRIMARY,
                  context.getPackageName(),
                  "new-epoch")),
          "epoch reset invalidates proof");
    }
    for (int i = 0; i < 3; i++) {
      try (var cursor = new MatrixCursor(columns)) {
        cursor.addRow(values);
        var extras = new Bundle();
        if (i == 0) extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
        if (i == 1) extras.putString(DocumentsContract.EXTRA_ERROR, "fixture");
        if (i == 2) cursor.addRow(values);
        cursor.setExtras(extras);
        try {
          SdSavedPhotoRevision.observed(
              cursor, content, id, PhotoStorage.PRIMARY, context.getPackageName(), proof.epoch());
          throw new AssertionError("incomplete or ambiguous metadata was accepted");
        } catch (IOException expected) {
        }
      }
    }
  }

  private static void require(boolean condition, String reason) {
    if (!condition) throw new AssertionError(reason);
  }
}
