package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.DatabaseErrorHandler;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import jp.nano.rawgallery.camera.CameraImportFile;
import jp.nano.rawgallery.camera.CameraImportHistoryPlan;
import jp.nano.rawgallery.camera.CameraImportLegacy;
import jp.nano.rawgallery.camera.CameraImportLedger;
import jp.nano.rawgallery.camera.CameraPhotoImporter;
import jp.nano.rawgallery.camera.HistoryDb;
import jp.nano.rawgallery.camera.ImportedKey;
import jp.nano.rawgallery.camera.MediaSaver;

import static android.provider.MediaStore.MediaColumns.*;

final class PhotoStorageChecks {
  private static final String TARGET_PACKAGE = "jp.nano.rawgallery.sd162";
  private static final String PRIMARY = PhotoStorage.PRIMARY;
  private static final String MISSING = "dead-beef";
  private static final String DETACH_PREFS = "photo-storage-detach-fixture";
  private static final String ABANDON_DETACH_PREFS = "photo-storage-abandon-detach-fixture";
  private static final String SAF_PREFS = "photo-storage-saf-fixture";
  private static final String DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents";
  private static final String MODEL = "Synthetic camera";
  private static final long CAPTURE = 1784712024L;

  private PhotoStorageChecks() {}

  static String run(Instrumentation test, String mode, String secondaryArg) throws Exception {
    Context base = test.getTargetContext();
    require(TARGET_PACKAGE.equals(base.getPackageName()), "Photo storage checks require the isolated APK");
    // CameraPhotoImporter reads default folder settings through this process-wide singleton.
    HistoryDb.Companion.get(base);
    String secondary = secondaryArg == null ? secondaryVolume(base) : secondaryArg.toLowerCase(Locale.ROOT);
    require(!PRIMARY.equals(secondary), "Expected a secondary volume");
    if (mode.startsWith("photo-storage-saf-")) return saf(base, mode, secondary);
    if (mode.startsWith("photo-storage-abandon-detach-")) return abandonDetached(base, mode, secondary);
    if (!"photo-storage".equals(mode)) return detach(base, mode, secondary);
    PhotoStorage.requireWritable(base, secondary);

    try (Fixture fixture = new Fixture(base, secondary)) {
      moveImportedPhotos(fixture);
    }
    for (PhotoMove.Boundary boundary : PhotoMove.Boundary.values()) {
      try (Fixture fixture = new Fixture(base, secondary)) {
        recover(fixture, boundary);
      }
    }
    try (Fixture fixture = new Fixture(base, secondary)) {
      cancelAndResume(fixture);
    }
    try (Fixture fixture = new Fixture(base, secondary)) {
      rejectUnavailable(fixture);
    }
    try (Fixture fixture = new Fixture(base, secondary)) {
      rejectChangedSource(fixture, false);
    }
    try (Fixture fixture = new Fixture(base, secondary)) {
      rejectChangedSource(fixture, true);
    }
    try (Fixture fixture = new Fixture(base, secondary)) {
      rejectStaleHistory(fixture);
    }
    try (Fixture fixture = new Fixture(base, secondary)) {
      rejectResetHistory(fixture);
    }
    try (Fixture fixture = new Fixture(base, secondary)) {
      loseVerifiedTarget(fixture, PhotoMove.Boundary.HISTORY_UPDATED, true);
    }
    try (Fixture fixture = new Fixture(base, secondary)) {
      loseVerifiedTarget(fixture, PhotoMove.Boundary.BEFORE_SOURCE_DELETE, false);
    }
    try (Fixture fixture = new Fixture(base, secondary)) {
      preservePreUpdateSecondaryIdentity(fixture);
    }
    try (Fixture fixture = new Fixture(base, secondary)) {
      moveMassStorageReceipt(fixture);
    }
    try (Fixture fixture = new Fixture(base, secondary)) {
      abandonWithOnlyReplacement(fixture);
    }
    try (Fixture fixture = new Fixture(base, secondary)) {
      abandonWithBothCopies(fixture);
    }
    try (Fixture fixture = new Fixture(base, secondary)) {
      sequentialCardIdentity(fixture);
    }
    return "PASS photo storage " + secondary + ": selected import, bulk move and original bytes, ledger and legacy history,"
        + " tags, rank, sharing, same-name files, retries after " + Arrays.toString(PhotoMove.Boundary.values())
        + ", verified-copy space estimate, cancellation, unavailable target, stale and reset history, "
        + "changed or missing source, lost replacement, pre-update SD tag/rank/share compatibility, "
        + "USB mass-storage mtime/EXIF fast path, abandon with one or two verified copies, sequential SD identity snapshots";
  }

  private static void moveImportedPhotos(Fixture f) throws Exception {
    byte[] original = jpeg(0xff445599);
    byte[] other = jpeg(0xff996644);
    byte[] legacyBytes = jpeg(0xff449955);
    byte[] freshBytes = jpeg(0xff994455);
    CameraImportFile source = f.source("wifi:", "PAIR.JPG", original);
    CameraImportFile secondRoute = f.source("sd:", "PAIR.JPG", original);
    require(PRIMARY.equals(PhotoStorage.selected(f.context)), "A fresh profile defaults to primary");
    try (CameraImportLedger ledger = f.ledger();
         HistoryDb history = f.history();
         TagStore tags = new TagStore(f.context);
         ShareHistoryStore shares = new ShareHistoryStore(f.context)) {
      CameraPhotoImporter importer = new CameraPhotoImporter(f.context, ledger);
      CameraPhotoImporter.Result first = f.importPhoto(importer, source, original);
      require(first.imported && PRIMARY.equals(PhotoStorage.volumeOf(first.uri)), "Initial import publishes on primary");
      require(!f.importPhoto(importer, secondRoute, original).imported, "Second route binds to original receipt");
      CameraImportLedger.Content before = ledger.known(source);
      require(before != null && ledger.contentCount() == 1 && ledger.receiptCount() == 2, "Two receipts identify one original");
      String legacyAlias = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
          ContentUris.parseId(first.uri)).toString();
      ImportedKey imported = new ImportedKey(source.name, source.bytes, source.captureSeconds);
      history.recordImport(MODEL, f.id, imported, legacyAlias);
      history.markDeleted(MODEL, f.id, imported);

      Uri legacy = f.insert(PRIMARY, "LEGACY.JPG", legacyBytes);
      ImportedKey legacyKey = new ImportedKey("LEGACY.JPG", legacyBytes.length, CAPTURE);
      history.recordImport(MODEL, f.id, legacyKey, legacy.toString());
      Uri missing = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(PRIMARY), Long.MAX_VALUE);
      history.recordImport(MODEL, f.id, new ImportedKey("MISSING.JPG", original.length, CAPTURE), missing.toString());

      Uri sameName = f.insert(f.secondary, source.name, other);
      Uri unrelated = f.insert(PRIMARY, "UNRELATED.JPG", other);
      PhotoRepository.Photo oldPhoto = f.photo(first.uri);
      PhotoRepository.Photo otherPhoto = f.photo(sameName);
      require(oldPhoto.name.equals(otherPhoto.name) && oldPhoto.folder.equals(otherPhoto.folder),
          "Library exposes separate photos at identical folder and filename across volumes");
      PhotoRepository.Photo legacyPhoto = f.photo(legacy);
      String photoKey = ArchiveStore.key(oldPhoto);
      String otherKey = ArchiveStore.key(otherPhoto);
      require(!photoKey.equals(otherKey), "Same-name photos have different logical keys");
      TagStore.Tag tag = tags.create("Synthetic assignment");
      String tagId = tags.photoId(oldPhoto, true);
      String otherTagId = tags.photoId(otherPhoto, true);
      require(!tagId.equals(otherTagId), "Same-name photos have different tag identities");
      tags.record(UUID.randomUUID().toString(), tag.id, tagId, 0, TagJudgments.Value.ASSIGNED);
      RankingStore ranking = new RankingStore(f.context);
      require(ranking.saveSingleBest(photoKey, false, true), "Record original's best mark");
      long shareTime = System.currentTimeMillis();
      require(shares.complete(shares.begin(List.of(photoKey), shareTime), "test.fixture", "Synthetic share", shareTime),
          "Record original's share handoff");

      require(PhotoStorage.select(f.context, f.secondary) && f.secondary.equals(PhotoStorage.selected(f.context)),
          "Saving the selected secondary volume succeeds");
      require(Arrays.equals(original, f.read(first.uri)), "Changing the setting leaves existing files in place");
      try (PhotoMove move = f.move(ledger, history, boundary -> {})) {
        PhotoMove.Plan plan = move.plan(f.secondary);
        require(plan.items().size() == 2 && plan.skipped() == 1, "Select the imported and legacy items, skipping missing history");
        move.begin(plan);
        PhotoMoveStore.Batch result = move.run(() -> false, batch -> {});
        require(result.done() == 2 && result.total() == 2 && result.skipped() == 1 && move.batch() == null,
            "Both photos complete and the batch closes");
      }

      CameraImportLedger.Content after = ledger.known(source);
      Uri moved = Uri.parse(after.uri);
      require(!first.uri.equals(moved) && f.secondary.equals(PhotoStorage.volumeOf(moved)), "Receipt points to the actual SD photo");
      require(f.rows(PRIMARY).stream().map(Row::uri).collect(java.util.stream.Collectors.toList()).equals(List.of(unrelated)),
          "Only primary originals in import history are removed");
      require(Arrays.equals(original, f.read(moved)) && Arrays.equals(other, f.read(sameName))
          && Arrays.equals(other, f.read(unrelated)), "Full image bytes match and the existing same-name photo remains intact");
      List<CameraImportHistoryPlan.Entry> entries = history.photoImportRecords(MODEL, f.id);
      require(entries.size() == 3 && history.importedCount(MODEL, f.id) == 3, "Legacy history count is preserved");
      CameraImportHistoryPlan.Entry legacyEntry = entries.stream()
          .filter(entry -> entry.key.equals(legacyKey.asString())).findFirst().orElseThrow();
      Uri movedLegacy = Uri.parse(legacyEntry.uri);
      require(f.secondary.equals(PhotoStorage.volumeOf(movedLegacy)) && Arrays.equals(legacyBytes, f.read(movedLegacy)),
          "Legacy-only item moved, with full bytes");
      require(entries.stream().anyMatch(entry -> entry.key.equals(imported.asString())
          && entry.uri.equals(after.uri) && entry.deleted), "Legacy aggregate URI and deletion evidence are preserved");
      require(entries.stream().anyMatch(entry -> missing.toString().equals(entry.uri)), "Missing history remains recorded");
      require(after.uri.equals(ledger.known(secondRoute).uri)
          && before.sourceSha.equals(after.sourceSha) && before.storedSha.equals(after.storedSha)
          && before.sourceBytes == after.sourceBytes && before.storedBytes == after.storedBytes
          && before.kind.equals(after.kind) && before.captureGroup.equals(after.captureGroup)
          && ledger.contentCount() == 1 && ledger.receiptCount() == 2,
          "Move preserves contents, source IDs, receipts, sizes, hashes, and capture group");
      PhotoRepository.Photo movedPhoto = f.photo(moved);
      require(oldPhoto.taken == movedPhoto.taken && oldPhoto.folder.equals(movedPhoto.folder)
          && legacyPhoto.name.equals(f.photo(movedLegacy).name), "MediaStore retains capture time, folder and unconflicted name");
      require(photoKey.equals(ArchiveStore.key(movedPhoto)) && photoKey.equals(ArchiveStore.key(oldPhoto))
          && otherKey.equals(ArchiveStore.key(f.photo(sameName))), "Both logical keys remain separate and stable");
      require(tagId.equals(tags.photoId(movedPhoto, false)) && otherTagId.equals(tags.photoId(f.photo(sameName), false))
          && tags.load().value(tag.id, tagId) == TagJudgments.Value.ASSIGNED
          && tags.load().value(tag.id, otherTagId) == TagJudgments.Value.UNDECIDED, "Tag identity and judgment follow only the moved photo");
      require(ranking.singleBest(ArchiveStore.key(movedPhoto)) && !ranking.singleBest(otherKey), "Best mark follows only the moved photo");
      require(shares.history(ArchiveStore.key(movedPhoto)).size() == 1
          && shares.history(ArchiveStore.key(movedPhoto)).get(0).handedOffAt == shareTime
          && shares.history(otherKey).isEmpty(), "Share handoff follows only the moved photo");

      CameraPhotoImporter.Result retry = new CameraPhotoImporter(f.context, ledger).importPhoto(source,
          out -> { throw new AssertionError("Known reimport read the camera source"); },
          f.id, true, false, () -> false);
      require(!retry.imported && moved.equals(retry.uri) && f.rows(f.secondary).size() == 3,
          "Known source reimport reuses the relocated receipt without duplicating files");
      CameraImportFile fresh = f.source("wifi:", "FRESH.JPG", freshBytes);
      CameraPhotoImporter.Result saved = f.importPhoto(new CameraPhotoImporter(f.context, ledger), fresh, freshBytes);
      require(saved.imported && f.secondary.equals(PhotoStorage.volumeOf(saved.uri))
          && Arrays.equals(freshBytes, f.read(saved.uri)), "New import writes full bytes to the selected SD volume");
    }
  }

  private static void recover(Fixture f, PhotoMove.Boundary boundary) throws Exception {
    byte[] bytes = jpeg(0xff666699);
    CameraImportFile source = f.source("wifi:", "RECOVER.JPG", bytes);
    Uri original;
    try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history()) {
      original = f.importPhoto(new CameraPhotoImporter(f.context, ledger), source, bytes).uri;
      history.recordImport(MODEL, f.id, new ImportedKey(source.name, source.bytes, source.captureSeconds),
          original.toString());
      require(PhotoStorage.select(f.context, f.secondary), "Set the SD destination");
      try (PhotoMove move = f.move(ledger, history, reached -> {
        if (reached == boundary) throw new InterruptedAtBoundary();
      })) {
        move.begin(move.plan(f.secondary));
        try {
          move.run(() -> false, batch -> {});
          throw new AssertionError("Did not reach " + boundary);
        } catch (InterruptedAtBoundary expected) {
          require(move.batch() != null, "Progress remains durable after " + boundary);
          if (boundary != PhotoMove.Boundary.DELETED)
            require(Arrays.equals(bytes, f.read(original)), "Original survives interruption at " + boundary);
        }
      }
    }
    try (PhotoMoveStore store = new PhotoMoveStore(f.context)) {
      if (boundary == PhotoMove.Boundary.VERIFIED)
        require(store.remainingBytes() == 0, "Retry capacity excludes the already verified copy");
    }
    try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         PhotoMove reopened = f.move(ledger, history, reached -> {})) {
      require(reopened.batch() != null && reopened.batch().total() == 1, "Reopen journal after " + boundary);
      require(reopened.run(() -> false, batch -> {}).done() == 1 && reopened.batch() == null, "Resume after " + boundary);
      List<Row> targets = f.rows(f.secondary);
      require(f.rows(PRIMARY).isEmpty() && targets.size() == 1 && !targets.get(0).pending,
          "Recovery leaves one published file and no primary or pending copies after " + boundary);
      Uri restored = targets.get(0).uri;
      require(Arrays.equals(bytes, f.read(restored)) && ledger.contentCount() == 1 && ledger.receiptCount() == 1
          && restored.toString().equals(ledger.known(source).uri)
          && history.importDestinations().equals(Set.of(restored.toString())),
          "Recovery preserves full bytes and both histories after " + boundary);
    }
  }

  private static void cancelAndResume(Fixture f) throws Exception {
    byte[] firstBytes = jpeg(0xff229933);
    byte[] secondBytes = jpeg(0xffaa4455);
    CameraImportFile firstSource = f.source("wifi:", "ONE.JPG", firstBytes);
    CameraImportFile secondSource = f.source("wifi:", "TWO.JPG", secondBytes);
    try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history()) {
      CameraPhotoImporter importer = new CameraPhotoImporter(f.context, ledger);
      f.importPhoto(importer, firstSource, firstBytes);
      f.importPhoto(importer, secondSource, secondBytes);
      require(PhotoStorage.select(f.context, f.secondary), "Set the SD destination");
      try (PhotoMove move = f.move(ledger, history, boundary -> {})) {
        move.begin(move.plan(f.secondary));
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        try {
          move.run(stop::get, batch -> stop.set(batch.done() == 1));
          throw new AssertionError("Move ignored cancellation after the first completed photo");
        } catch (InterruptedIOException expected) {
          require(move.batch() != null && move.batch().done() == 1 && f.rows(PRIMARY).size() == 1
              && f.rows(f.secondary).size() == 1 && !f.rows(f.secondary).get(0).pending,
              "Cancellation keeps the completed photo and one unmoved original");
        }
      }
      try (PhotoMove reopened = f.move(ledger, history, boundary -> {})) {
        require(reopened.run(() -> false, batch -> {}).done() == 2 && reopened.batch() == null
            && f.rows(PRIMARY).isEmpty() && f.rows(f.secondary).size() == 2
            && Arrays.equals(firstBytes, f.read(Uri.parse(ledger.known(firstSource).uri)))
            && Arrays.equals(secondBytes, f.read(Uri.parse(ledger.known(secondSource).uri))),
            "Retry finishes the cancelled batch exactly once for each photo");
      }
    }
  }

  private static void rejectUnavailable(Fixture f) throws Exception {
    byte[] bytes = jpeg(0xff666633);
    CameraImportFile source = f.source("wifi:", "UNAVAILABLE.JPG", bytes);
    require(!MediaStore.getExternalVolumeNames(f.context).contains(MISSING), "Missing volume sentinel is not mounted");
    require(PhotoStorage.select(f.context, MISSING), "Store unavailable selection for failure check");
    try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         PhotoMove move = f.move(ledger, history, boundary -> {})) {
      expectUnavailable(() -> f.importPhoto(new CameraPhotoImporter(f.context, ledger), source, bytes));
      expectUnavailable(() -> move.plan(MISSING));
      require(MISSING.equals(PhotoStorage.selected(f.context)) && ledger.contentCount() == 0
          && ledger.receiptCount() == 0 && ledger.pending().isEmpty()
          && f.rows(PRIMARY).isEmpty() && f.rows(f.secondary).isEmpty(),
          "An unavailable destination does not switch to primary or create files and receipts");
    }
  }

  private static void rejectChangedSource(Fixture f, boolean remove) throws Exception {
    byte[] original = jpeg(0xff553377);
    byte[] changed = jpeg(0xff772255);
    CameraImportFile source = f.source("wifi:", "CHANGED.JPG", original);
    try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         PhotoMove move = f.move(ledger, history, boundary -> {})) {
      Uri uri = f.importPhoto(new CameraPhotoImporter(f.context, ledger), source, original).uri;
      require(PhotoStorage.select(f.context, f.secondary), "Set the SD destination");
      move.begin(move.plan(f.secondary));
      Row originalRow = f.row(uri);
      if (remove) f.removeOwned(originalRow);
      else {
        f.requireOwned(originalRow);
        f.write(uri, changed);
      }
      expectMoveFailure(move, "Move accepted a source changed after confirmation");
      require(uri.toString().equals(ledger.known(source).uri) && f.rows(f.secondary).stream().allMatch(Row::pending)
          && (remove ? f.rows(PRIMARY).isEmpty() : Arrays.equals(changed, f.read(uri))),
          "A missing or changed original does not produce a bogus replacement");
      move.abandon();
      require(move.batch() == null && uri.toString().equals(ledger.known(source).uri) && f.rows(f.secondary).isEmpty()
          && (remove ? f.rows(PRIMARY).isEmpty() : Arrays.equals(changed, f.read(uri))),
          "Abandon clears the blocked batch while retaining the recorded source and any remaining bytes");

      byte[] freshBytes = jpeg(0xffaa7766);
      CameraImportFile fresh = f.source("wifi:", "LATER.JPG", freshBytes);
      f.importPhoto(new CameraPhotoImporter(f.context, ledger), fresh, freshBytes);
      require(PhotoStorage.select(f.context, PRIMARY), "Choose primary for an independent batch");
      PhotoMove.Plan next = move.plan(PRIMARY);
      require(next.items().size() == 1, "A separate eligible photo can be planned after abandoning");
      move.begin(next);
      require(move.run(() -> false, batch -> {}).done() == 1 && move.batch() == null
          && Arrays.equals(freshBytes, f.read(Uri.parse(ledger.known(fresh).uri)))
          && uri.toString().equals(ledger.known(source).uri), "The new batch succeeds without touching the old receipt");
    }
  }

  private static void rejectStaleHistory(Fixture f) throws Exception {
    byte[] image = jpeg(0xff335555);
    Uri wrongName = f.insert(PRIMARY, "OTHER.JPG", image);
    Uri wrongSize = f.insert(PRIMARY, "WRONGSIZE.JPG", image);
    Uri noCapture = f.insert(PRIMARY, "NO-CAPTURE.JPG", image, 0);
    require(f.awaitCaptureTime(noCapture, 0) == 0, "Owned EXIF-free fixture has no MediaStore capture time");
    try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         PhotoMove move = f.move(ledger, history, boundary -> {})) {
      history.recordImport(MODEL, f.id, new ImportedKey("PREVIOUS.JPG", image.length, CAPTURE), wrongName.toString());
      history.recordImport(MODEL, f.id, new ImportedKey("WRONGSIZE.JPG", image.length + 16, CAPTURE), wrongSize.toString());
      history.recordImport(MODEL, f.id, new ImportedKey("NO-CAPTURE.JPG", image.length, CAPTURE), noCapture.toString());
      PhotoMove.Plan plan = move.plan(f.secondary);
      require(plan.items().isEmpty() && plan.skipped() == 3,
          "Stale filename, size, or absent MediaStore capture time cannot prove a legacy-only import");
      require(f.rows(f.secondary).isEmpty() && Arrays.equals(image, f.read(wrongName))
          && Arrays.equals(image, f.read(wrongSize)) && Arrays.equals(image, f.read(noCapture))
          && history.importDestinations().equals(Set.of(wrongName.toString(), wrongSize.toString(), noCapture.toString())),
          "All unrelated bytes and legacy history references remain untouched");
    }
  }

  private static void rejectResetHistory(Fixture f) throws Exception {
    byte[] image = jpeg(0xff776655);
    Uri source = f.insert(PRIMARY, "RESET.JPG", image);
    try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         PhotoMove move = f.move(ledger, history, boundary -> {})) {
      history.recordImport(MODEL, f.id, new ImportedKey("RESET.JPG", image.length, CAPTURE), source.toString());
      require(PhotoStorage.select(f.context, f.secondary), "Set the SD destination");
      PhotoMove.Plan plan = move.plan(f.secondary);
      require(plan.items().size() == 1, "Matching legacy metadata is eligible");
      move.begin(plan);
      history.resetHistory(MODEL, f.id);
      expectMoveFailure(move, "Move used a legacy receipt removed after confirmation");
      require(Arrays.equals(image, f.read(source)) && f.rows(f.secondary).isEmpty()
          && history.importedCount(MODEL, f.id) == 0 && ledger.receiptCount() == 0,
          "A reset history cannot authorize publishing a replacement, deleting the source, or recreating a receipt");
      move.abandon();
      require(move.batch() == null && Arrays.equals(image, f.read(source)), "Clear reset-history batch without touching its source");
    }
  }

  private static void loseVerifiedTarget(Fixture f, PhotoMove.Boundary boundary, boolean trash) throws Exception {
    byte[] bytes = jpeg(0xff225577);
    CameraImportFile source = f.source("wifi:", "REPLACEMENT.JPG", bytes);
    AtomicBoolean removed = new AtomicBoolean();
    try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         PhotoMove move = f.move(ledger, history, reached -> {
           if (reached != boundary) return;
           try {
             List<Row> rows = f.rows(f.secondary);
             require(rows.size() == 1 && !rows.get(0).pending && !rows.get(0).trashed,
                 "Fault sees the sole verified public replacement");
             Row row = rows.get(0);
             require(Arrays.equals(bytes, f.read(row.uri)), "Replacement matches bytes before external change");
             if (trash) f.trashOwned(row);
             else f.removeOwned(row);
             removed.set(true);
           } catch (IOException failure) {
             throw new UncheckedIOException(failure);
           }
         })) {
      Uri original = f.importPhoto(new CameraPhotoImporter(f.context, ledger), source, bytes).uri;
      require(PhotoStorage.select(f.context, f.secondary), "Set the SD destination");
      ImportedKey key = new ImportedKey(source.name, source.bytes, source.captureSeconds);
      history.recordImport(MODEL, f.id, key, original.toString());
      history.markDeleted(MODEL, f.id, key);
      move.begin(move.plan(f.secondary));
      expectMoveFailure(move, "Source was allowed to disappear after replacement loss at " + boundary);
      require(removed.get() && move.batch() != null && Arrays.equals(bytes, f.read(original)),
          "Source survives a lost replacement at " + boundary);
      move.abandon();
      require(move.batch() == null && Arrays.equals(bytes, f.read(original))
          && ledger.contentCount() == 1 && ledger.receiptCount() == 1
          && original.toString().equals(ledger.known(source).uri)
          && history.photoImportRecords(MODEL, f.id).stream().anyMatch(entry -> entry.key.equals(key.asString())
              && entry.deleted && entry.uri.equals(original.toString())),
          "Abandon restores receipt, legacy destination and deletion history to the verified source");
      require(f.rows(f.secondary).stream().noneMatch(row -> !row.pending && !row.trashed),
          "Loss does not leave a visible replacement");
    }
  }

  private static void moveMassStorageReceipt(Fixture f) throws Exception {
    LocalDateTime capture = LocalDateTime.of(2024, 8, 9, 1, 2, 3);
    long captureSeconds = capture.atZone(ZoneId.systemDefault()).toEpochSecond();
    long cameraMtime = CAPTURE + 86400;
    byte[] photo = jpegWithCaptureTime(f, capture, cameraMtime);
    CameraImportFile source = new CameraImportFile("sd-usb:" + f.id, "DCIM/100CAM/USB-EXIF.JPG",
        photo.length, Long.toString(cameraMtime * 1000), cameraMtime);
    ImportedKey oldKey = new ImportedKey(source.name, source.bytes, cameraMtime);
    try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         PhotoMove move = f.move(ledger, history, reached -> {})) {
      CameraPhotoImporter importer = new CameraPhotoImporter(f.context, ledger);
      CameraPhotoImporter.Result first = f.importPhoto(importer, source, photo);
      require(first.imported && PRIMARY.equals(PhotoStorage.volumeOf(first.uri)), "Publish the USB mass-storage fixture");
      history.recordImport(MODEL, f.id, oldKey, first.uri.toString());
      long savedCapture = f.awaitCaptureTime(first.uri, captureSeconds * 1000);
      require(savedCapture == captureSeconds * 1000 && savedCapture / 1000 != cameraMtime,
          "Published EXIF capture time differs from original USB file mtime (DATE_TAKEN="
              + savedCapture + ", expected=" + captureSeconds * 1000 + ", USB mtime=" + cameraMtime + ")");
      CameraImportLedger.Content before = ledger.sourceSnapshot(source.sourceId).get(source.sourceKey());
      require(before != null && knownMassStorage(f, history, source, before), "Legacy USB fast path recognizes the original receipt");

      require(PhotoStorage.select(f.context, f.secondary), "Choose SD for the USB fixture");
      move.begin(move.plan(f.secondary));
      require(move.run(() -> false, batch -> {}).done() == 1, "Move the verified USB photo with distinct dates");
      CameraImportLedger.Content after = ledger.sourceSnapshot(source.sourceId).get(source.sourceKey());
      require(after != null && ledger.contentCount() == 1 && ledger.receiptCount() == 1
          && before.sourceSha.equals(after.sourceSha) && before.storedSha.equals(after.storedSha),
          "USB source receipt and original/stored checksums survive the move");
      Uri replacement = Uri.parse(after.uri);
      require(f.secondary.equals(PhotoStorage.volumeOf(replacement)) && f.rows(PRIMARY).isEmpty()
          && Arrays.equals(photo, f.read(replacement)) && f.awaitCaptureTime(replacement, savedCapture) == savedCapture,
          "Relocated USB file retains full bytes and EXIF capture time");
      require(history.importDestinations().equals(Set.of(after.uri)) && history.importedCount(MODEL, f.id) == 1
          && knownMassStorage(f, history, source, after),
          "Legacy USB history remaps and the MassStorageImporter classification fast path still skips this file");
      CameraPhotoImporter.Result retry = new CameraPhotoImporter(f.context, ledger).importPhoto(source,
          out -> { throw new CheckFailure("Known USB source opened the camera input"); }, f.id, true, false, () -> false);
      require(!retry.imported && replacement.equals(retry.uri), "Known USB receipt resolves to the replacement without reading input");
    }
  }

  private static boolean knownMassStorage(Fixture f, HistoryDb history, CameraImportFile source,
      CameraImportLedger.Content receipt) {
    Map<String, CameraImportHistoryPlan.Destination> destinations = MediaSaver.INSTANCE.photoDestinationSnapshot(f.context);
    return new CameraImportHistoryPlan(history.photoImportRecords(MODEL, f.id), destinations)
        .known(source.name, source.bytes, source.captureSeconds, false, receipt.uri, receipt.storedBytes);
  }

  private static byte[] jpegWithCaptureTime(Fixture f, LocalDateTime captured, long cameraMtime) throws IOException {
    File jpeg = File.createTempFile("loupe-usb-fixture-" + f.id, ".jpg", f.context.getCacheDir());
    try {
      Files.write(jpeg.toPath(), jpeg(0xff895544));
      var zoned = captured.atZone(ZoneId.systemDefault());
      String timestamp = captured.format(DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.ROOT));
      String offset = zoned.getOffset().getTotalSeconds() == 0 ? "+00:00" : zoned.getOffset().getId();
      ExifInterface exif = new ExifInterface(jpeg);
      exif.setAttribute(ExifInterface.TAG_DATETIME, timestamp);
      exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, timestamp);
      exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, timestamp);
      exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offset);
      exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset);
      exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offset);
      exif.saveAttributes();
      require(CameraImportLegacy.metadata(jpeg, cameraMtime).getCaptureSeconds() == zoned.toEpochSecond(),
          "Production parser reads the camera JPEG EXIF capture time before import");
      return Files.readAllBytes(jpeg.toPath());
    } finally {
      Files.deleteIfExists(jpeg.toPath());
    }
  }

  private static void abandonWithOnlyReplacement(Fixture f) throws Exception {
    byte[] bytes = jpeg(0xff618122);
    CameraImportFile source = f.source("wifi:", "LAST-COPY.JPG", bytes);
    ImportedKey key = new ImportedKey(source.name, source.bytes, source.captureSeconds);
    String firstKey;
    Uri replacement;
    try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history()) {
      Uri original = f.importPhoto(new CameraPhotoImporter(f.context, ledger), source, bytes).uri;
      firstKey = ArchiveStore.key(f.photo(original));
      history.recordImport(MODEL, f.id, key, original.toString());
      require(PhotoStorage.select(f.context, f.secondary), "Choose SD before the interrupted move");
      try (PhotoMove move = f.move(ledger, history, reached -> {
        if (reached != PhotoMove.Boundary.HISTORY_UPDATED) return;
        try {
          f.removeOwned(f.row(original));
        } catch (IOException failure) {
          throw new UncheckedIOException(failure);
        }
        throw new InterruptedAtBoundary();
      })) {
        move.begin(move.plan(f.secondary));
        try {
          move.run(() -> false, batch -> {});
          throw new CheckFailure("The post-history interruption did not run");
        } catch (InterruptedAtBoundary expected) {
          replacement = Uri.parse(ledger.known(source).uri);
          require(move.batch() != null && f.rows(PRIMARY).isEmpty() && Arrays.equals(bytes, f.read(replacement)),
              "After original loss, the verified replacement and unfinished history exist on disk");
        }
      }
    }
    try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         PhotoMove reopen = f.move(ledger, history, reached -> {})) {
      reopen.abandon();
      require(reopen.batch() == null && f.rows(f.secondary).size() == 1 && f.rows(PRIMARY).isEmpty()
          && Arrays.equals(bytes, f.read(replacement)) && firstKey.equals(ArchiveStore.key(f.photo(replacement)))
          && ledger.contentCount() == 1 && ledger.receiptCount() == 1 && replacement.toString().equals(ledger.known(source).uri)
          && history.importDestinations().equals(Set.of(replacement.toString())) && history.importedCount(MODEL, f.id) == 1,
          "Abandon retains the only verified bytes, logical identity, and both history references, and clears the journal");
    }
  }

  private static void abandonWithBothCopies(Fixture f) throws Exception {
    byte[] bytes = jpeg(0xff528722);
    CameraImportFile source = f.source("wifi:", "RETAIN-BOTH.JPG", bytes);
    try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         TagStore tags = new TagStore(f.context); ShareHistoryStore shares = new ShareHistoryStore(f.context)) {
      Uri original = f.importPhoto(new CameraPhotoImporter(f.context, ledger), source, bytes).uri;
      history.recordImport(MODEL, f.id, new ImportedKey(source.name, source.bytes, source.captureSeconds),
          original.toString());
      PhotoRepository.Photo originalPhoto = f.photo(original);
      RankingStore ranking = new RankingStore(f.context);
      TagStore.Tag tag = tags.create("Intact copies");
      String key = ArchiveStore.key(originalPhoto);
      String tagged = tags.photoId(originalPhoto, true);
      tags.record(UUID.randomUUID().toString(), tag.id, tagged, 0, TagJudgments.Value.ASSIGNED);
      long time = System.currentTimeMillis();
      require(ranking.saveSingleBest(key, false, true)
          && shares.complete(shares.begin(List.of(key), time), "test.target", "Target", time),
          "Record the original tag, rank and share before abandoning");
      require(PhotoStorage.select(f.context, f.secondary), "Choose SD for the retained-copy check");
      try (PhotoMove move = f.move(ledger, history, reached -> {
        if (reached == PhotoMove.Boundary.REMAPPED) throw new InterruptedAtBoundary();
      })) {
        move.begin(move.plan(f.secondary));
        try {
          move.run(() -> false, batch -> {});
          throw new CheckFailure("The move did not interrupt at REMAPPED");
        } catch (InterruptedAtBoundary expected) {
          require(move.batch() != null, "REMAPPED interruption kept its journal");
        }
      }

      Uri target = Uri.parse(ledger.known(source).uri);
      try (PhotoMove reopen = f.move(ledger, history, reached -> {})) {
        reopen.abandon();
        require(reopen.batch() == null, "Abandon cleared the remapped batch");
      }
      require(f.rows(PRIMARY).size() == 1 && f.rows(f.secondary).size() == 1
          && Arrays.equals(bytes, f.read(original)) && Arrays.equals(bytes, f.read(target))
          && history.importDestinations().equals(Set.of(target.toString()))
          && ledger.contentCount() == 1 && ledger.receiptCount() == 1 && target.toString().equals(ledger.known(source).uri),
          "Both verified files survive, while import histories refer only to the replacement");

      PhotoRepository.Photo replacement = f.photo(target);
      PhotoRepository.Photo retained = f.photo(original);
      String retainedKey = ArchiveStore.key(retained);
      require(key.equals(ArchiveStore.key(replacement)) && tagged.equals(tags.photoId(replacement, false))
          && ranking.singleBest(key) && shares.history(key).size() == 1
          && !key.equals(retainedKey) && !TagStore.source(replacement).equals(TagStore.source(retained))
          && tags.photoId(retained, false) == null && !ranking.singleBest(retainedKey) && shares.history(retainedKey).isEmpty(),
          "The replacement keeps the original identity and the retained source begins with an independent identity");
      String retainedTag = tags.photoId(retained, true);
      tags.record(UUID.randomUUID().toString(), tag.id, retainedTag, 0, TagJudgments.Value.EXCLUDED);
      require(retainedTag.equals(tags.photoId(f.photo(original), false)) && retainedKey.equals(ArchiveStore.key(f.photo(original)))
          && tagged.equals(tags.photoId(f.photo(target), false))
          && tags.load().value(tag.id, retainedTag) == TagJudgments.Value.EXCLUDED
          && tags.load().value(tag.id, tagged) == TagJudgments.Value.ASSIGNED,
          "Repeated scans and edits keep retained source and target separate");
    }
  }

  private static void sequentialCardIdentity(Fixture f) throws Exception {
    byte[] firstBytes = {1, 2, 3, 4};
    byte[] secondBytes = {4, 3, 2, 1};
    require(firstBytes.length == secondBytes.length && !Arrays.equals(firstBytes, secondBytes),
        "Sequential card snapshots describe different bytes with otherwise equal metadata");
    PhotoRepository.Photo one = new PhotoRepository.Photo(
        ContentUris.withAppendedId(MediaStore.Files.getContentUri(f.secondary), 401),
        "SAME.JPG", f.path, firstBytes.length, CAPTURE, CAPTURE * 1000);
    PhotoRepository.Photo two = new PhotoRepository.Photo(
        ContentUris.withAppendedId(MediaStore.Files.getContentUri(MISSING), 401),
        "SAME.JPG", f.path, secondBytes.length, CAPTURE, CAPTURE * 1000);
    String legacy = PhotoRepository.digest(f.path + one.name + ":" + one.size + ":" + one.modified);
    RankingStore ranking = new RankingStore(f.context);
    long time = System.currentTimeMillis();
    try (TagStore tags = new TagStore(f.context); ShareHistoryStore shares = new ShareHistoryStore(f.context)) {
      require(ranking.saveSingleBest(legacy, false, true)
          && shares.complete(shares.begin(List.of(legacy), time), "test.card.first", "First card", time),
          "Seed pre-update rank and share history for the first card");
      PhotoRepository.Photo first = snapshotPhoto(f, one);
      require(legacy.equals(ArchiveStore.key(first)), "The first observed card retains the legacy key");
      TagStore.Tag tag = tags.create("Separate cards");
      String firstTagSource = TagStore.source(first);
      String firstTagPhoto = tags.photoId(first, true);
      tags.record(UUID.randomUUID().toString(), tag.id, firstTagPhoto, 0, TagJudgments.Value.ASSIGNED);

      PhotoRepository.Photo second = snapshotPhoto(f, two);
      String later = ArchiveStore.key(second);
      String secondTagSource = TagStore.source(second);
      require(!legacy.equals(later) && !firstTagSource.equals(secondTagSource)
          && !ranking.singleBest(later) && shares.history(later).isEmpty() && tags.photoId(second, false) == null, "A later card alone cannot acquire the first card's tag, rank or share history");
      String secondTagPhoto = tags.photoId(second, true);
      require(!firstTagPhoto.equals(secondTagPhoto), "The same aggregate row ID cannot identify two physical cards");
      tags.record(UUID.randomUUID().toString(), tag.id, secondTagPhoto, 0, TagJudgments.Value.EXCLUDED);
      require(shares.complete(shares.begin(List.of(later), time + 1), "test.card.second", "Second card", time + 1),
          "Record a separate share on the later card");

      PhotoRepository.Photo firstAgain = snapshotPhoto(f, one);
      PhotoRepository.Photo secondAgain = snapshotPhoto(f, two);
      require(legacy.equals(ArchiveStore.key(firstAgain)) && later.equals(ArchiveStore.key(secondAgain))
          && ranking.singleBest(legacy) && !ranking.singleBest(later)
          && firstTagSource.equals(TagStore.source(firstAgain)) && secondTagSource.equals(TagStore.source(secondAgain))
          && firstTagPhoto.equals(tags.photoId(firstAgain, false)) && secondTagPhoto.equals(tags.photoId(secondAgain, false))
          && tags.load().value(tag.id, firstTagPhoto) == TagJudgments.Value.ASSIGNED
          && tags.load().value(tag.id, secondTagPhoto) == TagJudgments.Value.EXCLUDED
          && shares.history(legacy).stream().map(entry -> entry.packageName)
              .collect(java.util.stream.Collectors.toList()).equals(List.of("test.card.first"))
          && shares.history(later).stream().map(entry -> entry.packageName)
              .collect(java.util.stream.Collectors.toList()).equals(List.of("test.card.second")),
          "Alternating separate snapshots preserve each card's keys and independent histories");
    }
  }

  private static PhotoRepository.Photo snapshotPhoto(Fixture f, PhotoRepository.Photo photo) throws IOException {
    Map<String, PhotoRepository.Photo> snapshot = new HashMap<>();
    snapshot.put(photo.uri.toString(), photo);
    new PhotoRepository(f.context).stabilizeLocalIdentities(snapshot);
    return snapshot.get(photo.uri.toString());
  }

  private static void preservePreUpdateSecondaryIdentity(Fixture f) throws Exception {
    byte[] bytes = jpeg(0xff776688);
    CameraImportFile source = f.source("wifi:", "OLDSECONDARY.JPG", bytes);
    try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         TagStore tags = new TagStore(f.context); ShareHistoryStore shares = new ShareHistoryStore(f.context);
         PhotoMove move = f.move(ledger, history, boundary -> {})) {
      Uri oldUri = f.importPhoto(new CameraPhotoImporter(f.context, ledger, f.secondary), source, bytes).uri;
      PhotoRepository.Photo old = readLegacyGalleryPhoto(f, oldUri);
      RankingStore ranking = new RankingStore(f.context);
      LegacyLabels labels = seedOldLabels(old, tags, ranking, shares);

      assertOldLabels(f.photo(oldUri), tags, ranking, shares, labels);
      require(PhotoStorage.select(f.context, PRIMARY), "Move old secondary photo to primary");
      move.begin(move.plan(PRIMARY));
      require(move.run(() -> false, batch -> {}).done() == 1, "Old secondary photo moves successfully");
      Uri relocated = Uri.parse(ledger.known(source).uri);
      require(PRIMARY.equals(PhotoStorage.volumeOf(relocated)) && f.rows(f.secondary).isEmpty()
          && Arrays.equals(bytes, f.read(relocated)), "Old secondary photo retains full bytes after moving");
      assertOldLabels(f.photo(relocated), tags, ranking, shares, labels);
    }
  }

  private static PhotoRepository.Photo readLegacyGalleryPhoto(Fixture f, Uri concrete) throws IOException {
    Uri aggregate = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), ContentUris.parseId(concrete));
    String[] fields = {DISPLAY_NAME, RELATIVE_PATH, SIZE, DATE_MODIFIED, DATE_TAKEN, DATE_ADDED};
    try (Cursor row = f.resolver.query(aggregate, fields, null, null, null)) {
      if (row == null || !row.moveToFirst()) throw new IOException("The pre-update aggregate URI did not resolve");
      require(f.path.equals(row.getString(1)), "The aggregate URI resolves to the exact owned fixture folder");
      return new PhotoRepository.Photo(aggregate, row.getString(0), row.getString(1), row.getLong(2),
          row.getLong(3), row.getLong(4), row.getLong(5) * 1000, 0);
    }
  }

  private record LegacyLabels(String key, String tagId, String photoId, long sharedAt) {}

  private static LegacyLabels seedOldLabels(PhotoRepository.Photo photo, TagStore tags,
      RankingStore ranking, ShareHistoryStore shares) throws IOException {
    String key = PhotoRepository.digest(photo.folder + photo.name + ":" + photo.size + ":" + photo.modified);
    String source = photo.uri + "\n" + photo.size + ":" + photo.modified + ":" + photo.added + ":" + photo.created;
    String photoId = UUID.randomUUID().toString();
    ContentValues oldTagRow = new ContentValues();
    oldTagRow.put("id", photoId);
    oldTagRow.put("source", source);
    tags.getWritableDatabase().insertOrThrow("photos", null, oldTagRow);
    TagStore.Tag tag = tags.create("Old SD assignment");
    tags.record(UUID.randomUUID().toString(), tag.id, photoId, 0, TagJudgments.Value.ASSIGNED);
    require(ranking.saveSingleBest(key, false, true), "Seed a pre-update SD best mark");
    long time = System.currentTimeMillis();
    require(shares.complete(shares.begin(List.of(key), time), "test.fixture", "Synthetic share", time),
        "Seed a pre-update SD share handoff");
    return new LegacyLabels(key, tag.id, photoId, time);
  }

  private static void assertOldLabels(PhotoRepository.Photo photo, TagStore tags, RankingStore ranking,
      ShareHistoryStore shares, LegacyLabels labels) throws IOException {
    String readKey = ArchiveStore.key(photo);
    List<ShareHistoryStore.Entry> handoffs = shares.history(readKey);
    require(labels.key.equals(readKey) && labels.photoId.equals(tags.photoId(photo, false))
        && tags.load().value(labels.tagId, labels.photoId) == TagJudgments.Value.ASSIGNED
        && ranking.singleBest(readKey) && handoffs.size() == 1 && handoffs.get(0).handedOffAt == labels.sharedAt,
        "Legacy SD tag, rank and share records resolve for " + photo.uri);
  }

  private static void expectMoveFailure(PhotoMove move, String label) {
    try {
      move.run(() -> false, batch -> {});
      throw new AssertionError(label);
    } catch (IOException expected) {
      // The failure's observable effects are asserted by each caller.
    }
  }

  static String captureUi(ArchiveInstrumentation test, String secondaryArg, int width, float font, boolean runMove,
      int skipped) throws Exception {
    Context base = test.getTargetContext();
    require(TARGET_PACKAGE.equals(base.getPackageName()), "Photo storage checks require the isolated APK");
    String secondary = secondaryArg == null ? secondaryVolume(base) : secondaryArg.toLowerCase(Locale.ROOT);
    PhotoStorage.requireWritable(base, secondary);
    SharedPreferences settings = base.getSharedPreferences("settings", 0);
    boolean hadSelection = settings.contains("photo_volume");
    String previous = PhotoStorage.selected(base);
    HistoryDb history = HistoryDb.Companion.get(base);
    require(history.importDestinations().isEmpty() && CameraImportLedger.get(base).importDestinations().isEmpty(),
        "UI confirmation test requires a clean isolated import history");
    try (PhotoMoveStore store = new PhotoMoveStore(base)) {
      require(store.batch() == null && !PhotoMoveService.state().running(), "An unrelated move must not be pending");
    }

    File captures = new File(base.getCacheDir(), "photo-storage-checks");
    require(captures.isDirectory() || captures.mkdirs(), "Create capture directory");
    String profile = width + "-" + font;
    if (runMove) profile += skipped == 0 ? "-run" : "-run-skipped";
    boolean observedProgress;
    try (Fixture f = new Fixture(base, secondary)) {
      byte[] bytes = jpeg(0xff264167);
      String name = "CONFIRM.JPG";
      Uri original = f.insert(PRIMARY, name, bytes);
      ImportedKey imported = new ImportedKey(name, bytes.length, CAPTURE);
      PhotoRepository.Photo sourcePhoto = runMove ? f.photo(original) : null;
      history.recordImport(MODEL, f.id, imported, original.toString());
      Uri missing = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(PRIMARY), Long.MAX_VALUE);
      ImportedKey missingKey = new ImportedKey("MISSING.JPG", bytes.length, CAPTURE);
      if (skipped != 0) history.recordImport(MODEL, f.id, missingKey, missing.toString());
      try {
        require(PhotoStorage.select(base, PRIMARY), "Set initial UI storage");
        observedProgress = captureActivity(test, base, secondary, captures, profile, runMove, skipped);
        if (runMove) verifyUiMove(base, f, history, imported, bytes, sourcePhoto, skipped, missingKey, missing);
        else require(Arrays.equals(bytes, f.read(original)) && f.rows(secondary).isEmpty(),
            "Cancelling confirmation leaves the original in place");
      } finally {
        history.resetHistory(MODEL, f.id);
      }
    } finally {
      SharedPreferences.Editor restore = settings.edit();
      if (hadSelection) restore.putString("photo_volume", previous);
      else restore.remove("photo_volume");
      require(restore.commit(), "Restore original isolated app preference");
    }
    String result = runMove
        ? "foreground service completed: 1 moved, 0 unprocessed, " + skipped
            + " skipped; source absence, exact SD bytes and history checked; "
            + (observedProgress ? "progress captured" : "move finished before visible progress")
        : "settings, single selection, one eligible and one skipped with explanation, confirmation and cancel";
    return "PASS photo storage UI " + profile + ": requested " + width + "dp, effective "
        + test.photoStorageEffectiveWidth() + "dp. " + result + ". " + captures.getAbsolutePath();
  }

  private static void verifyUiMove(Context base, Fixture f, HistoryDb history, ImportedKey key, byte[] bytes,
      PhotoRepository.Photo original, int skipped, ImportedKey missingKey, Uri missing) throws IOException {
    List<Row> targets = f.rows(f.secondary);
    require(f.rows(PRIMARY).isEmpty() && targets.size() == 1 && !targets.get(0).pending && !targets.get(0).trashed,
        "Android UI move leaves exactly one published SD file and no source");
    Uri target = targets.get(0).uri;
    require(Arrays.equals(bytes, f.read(target)), "Android UI move retains all original bytes");
    List<CameraImportHistoryPlan.Entry> entries = history.photoImportRecords(MODEL, f.id);
    require(entries.size() == 1 + skipped && entries.stream().anyMatch(entry ->
            entry.key.equals(key.asString()) && entry.uri.equals(target.toString())),
        "Foreground move updates the exact legacy receipt to the SD file");
    if (skipped != 0) require(entries.stream().anyMatch(entry ->
            entry.key.equals(missingKey.asString()) && entry.uri.equals(missing.toString())),
        "The skipped missing receipt remains unchanged");
    try (PhotoMoveStore store = new PhotoMoveStore(base)) {
      require(store.batch() == null, "Foreground move closes the durable batch");
      for (PhotoRepository.Photo synthetic : List.of(original, f.photo(target))) {
        store.getWritableDatabase().delete("local_photos", "location=? AND media_version=?",
            new String[]{PhotoIdentityIndex.location(synthetic), TagStore.locationSource(synthetic)});
      }
    }
  }

  private static boolean captureActivity(ArchiveInstrumentation test, Context base, String secondary,
      File captures, String profile, boolean runMove, int skipped) throws Exception {
    PhotoStorageActivity activity = (PhotoStorageActivity) test.startActivitySync(
        new Intent(base, PhotoStorageActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      UiAutomation automation = test.getUiAutomation();
      Predicate<AccessibilityNodeInfo> choosing = node -> hasText(node, "保存先を変更");
      awaitNode(automation, choosing);
      captureSettled(test, automation, captures, profile + "-settings", choosing);
      assertWindowWidth(test, activity);
      assertTouchTarget(test, activity, awaitNode(automation, choosing), "保存先を変更");
      assertTouchTarget(test, activity, awaitNode(automation, node -> hasText(node, "取り込み済みの写真を移動")),
          "取り込み済みの写真を移動");
      click(awaitNode(automation, choosing));

      String label = PhotoStorage.label(base, secondary);
      awaitNode(automation, node -> hasText(node, "内部ストレージ") && node.isCheckable() && node.isChecked());
      Predicate<AccessibilityNodeInfo> sdOption = node -> hasText(node, label) && node.isCheckable();
      awaitNode(automation, sdOption);
      captureSettled(test, automation, captures, profile + "-choices", sdOption);
      click(awaitNode(automation, sdOption));
      click(awaitNode(automation, node -> hasText(node, "設定")));
      awaitNode(automation, node -> hasText(node, label) && !node.isCheckable());
      require(secondary.equals(PhotoStorage.selected(base)), "Radio selection persists the secondary volume");

      click(awaitNode(automation, node -> hasText(node, "取り込み済みの写真を移動")));
      Predicate<AccessibilityNodeInfo> confirmation = node -> {
        String message = node.getText() == null ? "" : node.getText().toString();
        return message.contains(label + "に1枚") && (skipped == 0 ? message.contains("移動できない写真は0枚です。")
            : message.contains("移動できない写真が1枚あります。")
                && message.contains("現在読み取れない写真は対象外です。"));
      };
      awaitNode(automation, confirmation);
      captureSettled(test, automation, captures, profile + "-confirm", confirmation);
      if (!runMove) {
        click(awaitNode(automation, node -> hasText(node, "キャンセル")));
        return false;
      }

      click(awaitNode(automation, node -> hasText(node, "移動")));
      return captureMoveResult(test, automation, captures, profile, skipped);
    } finally {
      test.runOnMainSync(activity::finish);
      test.waitForIdleSync();
    }
  }

  private static boolean captureMoveResult(Instrumentation test, UiAutomation automation, File captures, String profile,
      int skipped) throws Exception {
    long deadline = SystemClock.uptimeMillis() + 12000;
    boolean progress = false;
    Predicate<AccessibilityNodeInfo> completed = node -> node.getText() != null
        && node.getText().toString().contains("写真を1枚移動しました。")
        && node.getText().toString().contains("未処理0枚、移動できない写真は" + skipped + "枚です。");
    Predicate<AccessibilityNodeInfo> moving = node -> node.getContentDescription() != null
        && "写真の移動の進捗".contentEquals(node.getContentDescription());
    while (SystemClock.uptimeMillis() < deadline) {
      AccessibilityNodeInfo screen = automation.getRootInActiveWindow();
      if (findNode(screen, completed) != null) {
        captureSettled(test, automation, captures, profile + "-completed", completed);
        return progress;
      }
      if (!progress && findNode(screen, moving) != null) {
        awaitPresentedFrames(test);
        Bitmap bitmap = automation.takeScreenshot();
        if (bitmap != null) {
          try {
            if (findNode(automation.getRootInActiveWindow(), moving) != null) {
              writeCapture(bitmap, captures, profile + "-progress");
              progress = true;
            }
          } finally {
            bitmap.recycle();
          }
        }
      }
      SystemClock.sleep(25);
    }
    throw new CheckFailure("Move completion did not become visible: " + PhotoMoveService.state().message());
  }

  private static boolean hasText(AccessibilityNodeInfo node, String text) {
    return text.contentEquals(node.getText() == null ? "" : node.getText());
  }

  private static AccessibilityNodeInfo awaitNode(UiAutomation automation, Predicate<AccessibilityNodeInfo> match) {
    long deadline = SystemClock.uptimeMillis() + 6000;
    while (SystemClock.uptimeMillis() < deadline) {
      AccessibilityNodeInfo node = findNode(automation.getRootInActiveWindow(), match);
      if (node != null) return node;
      SystemClock.sleep(50);
    }
    throw new AssertionError("Photo storage accessibility control was not visible");
  }

  private static AccessibilityNodeInfo findNode(AccessibilityNodeInfo root, Predicate<AccessibilityNodeInfo> match) {
    if (root == null) return null;
    if (root.isVisibleToUser() && match.test(root)) return root;
    for (int i = 0; i < root.getChildCount(); i++) {
      AccessibilityNodeInfo node = findNode(root.getChild(i), match);
      if (node != null) return node;
    }
    return null;
  }

  private static void click(AccessibilityNodeInfo node) {
    for (AccessibilityNodeInfo target = node; target != null; target = target.getParent()) {
      if (target.isClickable()) {
        require(target.isEnabled() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK),
            "Storage accessibility action is available for " + node.getText());
        return;
      }
    }
    throw new AssertionError("No clickable parent for " + node.getText());
  }

  static Rect availableDisplay(Activity activity) {
    var metrics = activity.getWindowManager().getMaximumWindowMetrics();
    var insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
    Rect bounds = new Rect(metrics.getBounds());
    bounds.inset(insets.left, insets.top, insets.right, insets.bottom);
    return bounds;
  }

  private static Rect screenBounds(View view) {
    int[] position = new int[2];
    view.getLocationOnScreen(position);
    return new Rect(position[0], position[1], position[0] + view.getWidth(), position[1] + view.getHeight());
  }

  private static void assertWindowWidth(ArchiveInstrumentation test, PhotoStorageActivity activity) {
    test.runOnMainSync(() -> {
      int effectiveWidth = test.photoStorageEffectiveWidth();
      Rect display = availableDisplay(activity);
      Rect window = screenBounds(activity.getWindow().getDecorView());
      require(activity.getResources().getConfiguration().screenWidthDp == effectiveWidth
          && window.width() == GalleryUi.dp(activity, effectiveWidth)
          && window.left >= display.left && window.right <= display.right,
          "Configured " + effectiveWidth + "dp window must fit the available " + display.width() + "px width");
    });
  }

  private static void assertTouchTarget(Instrumentation test, PhotoStorageActivity activity,
      AccessibilityNodeInfo node, String name) {
    Rect accessBounds = new Rect();
    node.getBoundsInScreen(accessBounds);
    test.runOnMainSync(() -> {
      Rect display = availableDisplay(activity);
      View button = findButton(activity.getWindow().getDecorView(), name);
      require(button != null && button.isShown(), "Native button is visible: " + name);
      Rect fullBounds = screenBounds(button);
      int minimum = GalleryUi.dp(activity, 48);
      require(fullBounds.width() >= minimum && fullBounds.height() >= minimum
          && accessBounds.width() >= minimum && accessBounds.height() >= minimum
          && display.contains(fullBounds) && display.contains(accessBounds),
          name + " has an unclipped 48dp touch target within the visible display");
    });
  }

  private static View findButton(View view, String label) {
    if (view instanceof TextView text && view.isClickable() && label.contentEquals(text.getText())) return view;
    if (view instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) {
        View match = findButton(group.getChildAt(i), label);
        if (match != null) return match;
      }
    }
    return null;
  }

  private static void awaitPresentedFrames(Instrumentation test) throws InterruptedException {
    CountDownLatch frames = new CountDownLatch(1);
    test.runOnMainSync(() -> {
      Choreographer choreographer = Choreographer.getInstance();
      choreographer.postFrameCallback(first -> choreographer.postFrameCallback(second -> frames.countDown()));
    });
    require(frames.await(1, TimeUnit.SECONDS), "Android did not present two frame boundaries");
  }

  private static void captureSettled(Instrumentation test, UiAutomation automation, File directory, String name,
      Predicate<AccessibilityNodeInfo> expected) throws Exception {
    test.waitForIdleSync();
    try {
      automation.waitForIdle(300, 1500);
    } catch (TimeoutException animationsStillRunning) {
      // Continue with the bounded frame comparison below.
    }
    long deadline = SystemClock.uptimeMillis() + 1400;
    Bitmap previous = null;
    try {
      while (SystemClock.uptimeMillis() < deadline) {
        awaitPresentedFrames(test);
        Bitmap current = automation.takeScreenshot();
        require(current != null, "Capture visible Android UI");
        if (previous != null && previous.sameAs(current)
            && findNode(automation.getRootInActiveWindow(), expected) != null) {
          try {
            writeCapture(current, directory, name);
          } finally {
            current.recycle();
          }
          return;
        }
        if (previous != null) previous.recycle();
        previous = current;
      }
    } finally {
      if (previous != null) previous.recycle();
    }
    throw new CheckFailure("Screen did not settle for " + name);
  }

  private static void writeCapture(Bitmap bitmap, File directory, String name) throws IOException {
    try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
      require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Write Android UI capture");
    }
  }

  private static String saf(Context base, String mode, String secondary) throws Exception {
    PhotoStorage.requireWritable(base, secondary);
    SharedPreferences marker = base.getSharedPreferences(SAF_PREFS, 0);
    byte[] legacyDocumentBytes = jpeg(0xff336644);
    byte[] existingMediaBytes = jpeg(0xff6677aa);
    if ("photo-storage-saf-seed".equals(mode)) {
      require(!marker.contains("id"), "Verify or clean the existing SAF fixture before seeding");
      Fixture f = new Fixture(base, secondary);
      boolean seeded = false;
      try (CameraImportLedger ledger = f.ledger(); TagStore tags = new TagStore(f.context);
           ShareHistoryStore shares = new ShareHistoryStore(f.context)) {
        CameraPhotoImporter importer = new CameraPhotoImporter(f.context, ledger, secondary);
        CameraImportFile mediaSource = f.source("sd:", "SAF-GRANTED.JPG", existingMediaBytes);
        Uri media = f.importPhoto(importer, mediaSource, existingMediaBytes).uri;
        PhotoRepository.Photo beforeGrant = f.photo(media);
        require("media".equals(beforeGrant.uri.getAuthority()), "Seed the photo's state through MediaStore before a SAF grant");
        RankingStore ranking = new RankingStore(f.context);
        TagStore.Tag tag = tags.create("Assigned before folder permission");
        String photoId = tags.photoId(beforeGrant, true);
        tags.record(UUID.randomUUID().toString(), tag.id, photoId, 0, TagJudgments.Value.ASSIGNED);
        String archiveKey = ArchiveStore.key(beforeGrant);
        long time = System.currentTimeMillis();
        require(ranking.saveSingleBest(archiveKey, false, true)
            && shares.complete(shares.begin(List.of(archiveKey), time), "test.fixture", "Synthetic share", time),
            "Rank and share history exist before the SAF grant");

        // Leave the second photo unscanned until the SAF grant and its pre-update records exist.
        CameraImportFile documentSource = f.source("sd:", "SAF-ORIGINAL.JPG", legacyDocumentBytes);
        Uri documentMedia = f.importPhoto(importer, documentSource, legacyDocumentBytes).uri;
        require(secondary.equals(PhotoStorage.volumeOf(media)) && secondary.equals(PhotoStorage.volumeOf(documentMedia)),
            "SAF fixtures are on the intended SD");
        require(marker.edit().putString("id", f.id).putString("volume", secondary)
            .putString("uri", documentMedia.toString()).putString("mediaUri", media.toString())
            .putString("mediaKey", archiveKey).putString("mediaTag", tag.id).putString("mediaPhoto", photoId)
            .putLong("mediaSharedAt", time).commit(), "Record isolated SAF fixtures and pre-grant identities");
        seeded = true;
        return "PASS SAF seed " + PhotoStorage.label(base, secondary) + " (" + secondary + "): " + f.path;
      } finally {
        if (!seeded) f.close();
      }
    }
    require("photo-storage-saf-verify".equals(mode), "Unknown SAF photo storage mode");
    require(secondary.equals(marker.getString("volume", null)), "Use the secondary volume from SAF seed");
    Fixture f = new Fixture(base, secondary, UUID.fromString(marker.getString("id", "")).toString());
    Uri tree = persistedFixtureTree(f);

    try (f; CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         TagStore tags = new TagStore(f.context); ShareHistoryStore shares = new ShareHistoryStore(f.context);
         PhotoMove move = f.move(ledger, history, boundary -> {})) {
      CameraImportFile documentSource = f.source("sd:", "SAF-ORIGINAL.JPG", legacyDocumentBytes);
      CameraImportFile mediaSource = f.source("sd:", "SAF-GRANTED.JPG", existingMediaBytes);
      PhotoRepository.Photo oldDocument = readLegacyDocumentPhoto(f, tree, documentSource.name);
      PhotoRepository.Photo grantedDocument = readLegacyDocumentPhoto(f, tree, mediaSource.name);
      assertDocumentReadable(f, oldDocument, legacyDocumentBytes);
      assertDocumentReadable(f, grantedDocument, existingMediaBytes);
      RankingStore ranking = new RankingStore(f.context);
      LegacyLabels documentLabels = seedOldLabels(oldDocument, tags, ranking, shares);
      LegacyLabels mediaLabels = new LegacyLabels(marker.getString("mediaKey", null), marker.getString("mediaTag", null),
          marker.getString("mediaPhoto", null), marker.getLong("mediaSharedAt", -1));
      assertOldLabels(galleryFixture(f, mediaSource.name), tags, ranking, shares, mediaLabels);
      assertOldLabels(galleryFixture(f, documentSource.name), tags, ranking, shares, documentLabels);
      require(!mediaLabels.key.equals(documentLabels.key) && !mediaLabels.photoId.equals(documentLabels.photoId),
          "The two granted fixtures have independent rank, share and tag identities");

      require(PhotoStorage.select(f.context, PRIMARY), "Select primary for the SAF move");
      PhotoMove.Plan plan = move.plan(PRIMARY);
      Set<String> sources = plan.items().stream().map(PhotoMoveStore.Item::source).collect(java.util.stream.Collectors.toSet());
      require(sources.equals(Set.of(marker.getString("uri", ""), marker.getString("mediaUri", ""))),
          "The exact app-owned MediaStore rows are selected while the SAF grant remains active");
      move.begin(plan);
      require(move.run(() -> false, batch -> {}).done() == 2 && f.rows(secondary).isEmpty(),
          "Complete both moves with the SAF grant still active");
      assertMovedSafPhoto(f, ledger, mediaSource, existingMediaBytes);
      assertMovedSafPhoto(f, ledger, documentSource, legacyDocumentBytes);
      assertOldLabels(galleryFixture(f, mediaSource.name), tags, ranking, shares, mediaLabels);
      assertOldLabels(galleryFixture(f, documentSource.name), tags, ranking, shares, documentLabels);
    } finally {
      f.resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      require(base.deleteSharedPreferences(SAF_PREFS), "Clean exact SAF marker");
    }
    return "PASS SAF persisted SD grant: independent pre-grant MediaStore and older document tag/rank/share survive grant and move";
  }

  private static void assertDocumentReadable(Fixture f, PhotoRepository.Photo document, byte[] bytes) throws IOException {
    try (InputStream input = f.resolver.openInputStream(document.uri)) {
      require(input != null && Arrays.equals(bytes, input.readAllBytes()), "The persisted DocumentsProvider grant reads " + document.name);
    }
  }

  private static void assertMovedSafPhoto(Fixture f, CameraImportLedger ledger, CameraImportFile source, byte[] bytes)
      throws IOException {
    Uri replacement = Uri.parse(ledger.known(source).uri);
    require(PRIMARY.equals(PhotoStorage.volumeOf(replacement)) && Arrays.equals(bytes, f.read(replacement)),
        "SAF-visible photo moves with full bytes " + source.name);
  }

  private static Uri persistedFixtureTree(Fixture f) {
    String expected = f.secondary + ":" + f.path.substring(0, f.path.length() - 1);
    return f.resolver.getPersistedUriPermissions().stream()
        .filter(UriPermission::isReadPermission).map(UriPermission::getUri)
        .filter(uri -> DOCUMENTS_AUTHORITY.equals(uri.getAuthority()) && DocumentsContract.isTreeUri(uri)
            && DocumentsContract.getTreeDocumentId(uri).equalsIgnoreCase(expected))
        .findFirst().orElseThrow(() -> new AssertionError("Persistent read grant for exact SD fixture folder is required"));
  }

  private static PhotoRepository.Photo readLegacyDocumentPhoto(Fixture f, Uri tree, String name) throws IOException {
    String directoryId = DocumentsContract.getTreeDocumentId(tree);
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directoryId);
    String[] fields = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED};
    try (Cursor row = f.resolver.query(children, fields, null, null, null)) {
      if (row != null) while (row.moveToNext()) {
        String documentId = row.getString(0);
        if (!name.equals(row.getString(1))) continue;
        require(documentId.equals(directoryId + "/" + name), "SAF grant resolves the exact fixture file");
        Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
        String oldFolder = documentId.substring(0, documentId.lastIndexOf('/') + 1);
        return new PhotoRepository.Photo(document, name, oldFolder, row.getLong(2), row.getLong(3) / 1000, 0, 0, 0);
      }
    }
    throw new IOException("Could not read SAF fixture " + name);
  }

  private static PhotoRepository.Photo galleryFixture(Fixture f, String name) throws IOException {
    List<PhotoRepository.Photo> entries = new PhotoRepository(f.context).scan("すべて").stream()
        .filter(photo -> name.equals(photo.name) && (f.path.equals(photo.folder) || photo.folder.endsWith(":" + f.path)))
        .collect(java.util.stream.Collectors.toList());
    require(entries.size() == 1, "Gallery shows one row for " + name + " while MediaStore and SAF both grant access");
    return entries.get(0);
  }

  private static String abandonDetached(Context base, String mode, String secondary) throws Exception {
    SharedPreferences marker = base.getSharedPreferences(ABANDON_DETACH_PREFS, 0);
    byte[] bytes = jpeg(0xff567b91);
    String name = "ABANDON-DETACH.JPG";
    if ("photo-storage-abandon-detach-seed".equals(mode)) {
      require(!marker.contains("id"), "The previous detached abandon fixture needs restoration");
      PhotoStorage.requireWritable(base, secondary);
      Fixture f = new Fixture(base, secondary);
      boolean seeded = false;
      try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
           TagStore tags = new TagStore(f.context); ShareHistoryStore shares = new ShareHistoryStore(f.context);
           PhotoMove move = f.move(ledger, history, reached -> {
             if (reached == PhotoMove.Boundary.REMAPPED) throw new InterruptedAtBoundary();
           })) {
        CameraImportFile source = f.source("sd:", name, bytes);
        Uri original = f.importPhoto(new CameraPhotoImporter(f.context, ledger, secondary), source, bytes).uri;
        require(secondary.equals(PhotoStorage.volumeOf(original)), "The original fixture is on the exact SD");
        history.recordImport(MODEL, f.id, new ImportedKey(name, source.bytes, source.captureSeconds), original.toString());
        PhotoRepository.Photo photo = f.photo(original);
        String archiveKey = ArchiveStore.key(photo);
        TagStore.Tag tag = tags.create("Assigned before removing SD");
        String photoId = tags.photoId(photo, true);
        tags.record(UUID.randomUUID().toString(), tag.id, photoId, 0, TagJudgments.Value.ASSIGNED);
        long time = System.currentTimeMillis();
        require(new RankingStore(f.context).saveSingleBest(archiveKey, false, true)
            && shares.complete(shares.begin(List.of(archiveKey), time), "test.fixture", "Synthetic share", time),
            "Label the SD photo before moving");
        require(PhotoStorage.select(f.context, PRIMARY), "Move the SD fixture to internal storage");
        move.begin(move.plan(PRIMARY));
        try {
          move.run(() -> false, batch -> {});
          throw new CheckFailure("Detached abandon seed did not reach REMAPPED");
        } catch (InterruptedAtBoundary expected) {
          require(move.batch() != null && f.rows(secondary).size() == 1 && f.rows(PRIMARY).size() == 1,
              "Both verified copies exist before ejecting the SD");
        }
        Uri target = Uri.parse(ledger.known(source).uri);
        require(PRIMARY.equals(PhotoStorage.volumeOf(target)) && Arrays.equals(bytes, f.read(target))
            && history.importDestinations().equals(Set.of(target.toString())),
            "Journal points to a verified internal copy before removal");
        require(marker.edit().putString("id", f.id).putString("volume", secondary).putString("target", target.toString())
            .putString("key", archiveKey).putString("tag", tag.id).putString("photo", photoId)
            .putLong("sharedAt", time).commit(), "Persist detached abandon seed");
        seeded = true;
        return "PASS abandon detach seed " + secondary + ": REMAPPED and both verified copies present";
      } finally {
        if (!seeded) f.close();
      }
    }

    require(secondary.equals(marker.getString("volume", null)), "Use the same secondary volume as the abandon seed");
    Fixture f = new Fixture(base, secondary, UUID.fromString(marker.getString("id", "")).toString());
    Uri target = Uri.parse(marker.getString("target", ""));
    CameraImportFile source = f.source("sd:", name, bytes);
    if ("photo-storage-abandon-detach-absent".equals(mode)) {
      require(!MediaStore.getExternalVolumeNames(base).contains(secondary), "Unmount the SD physically before abandoning");
      try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
           PhotoMove move = f.move(ledger, history, reached -> {})) {
        PhotoMoveStore.Batch before = move.batch();
        require(before != null && Arrays.equals(bytes, f.read(target)), "Only the verified internal copy is accessible");
        try (AutoCloseable restoreTarget = () -> {
          Row replacement = f.row(target);
          if (replacement.trashed) f.setTrashed(replacement, false);
        }) {
          f.trashOwned(f.row(target));
          try {
            move.abandon();
            throw new CheckFailure("Abandon cleared the journal with neither copy verifiable");
          } catch (PhotoMove.Failure expected) {
            require(before.equals(move.batch()) && target.toString().equals(ledger.known(source).uri)
                && history.importDestinations().equals(Set.of(target.toString())),
                "When neither copy is verifiable, the same batch and both history references survive");
          }
        }
        require(!f.row(target).trashed && Arrays.equals(bytes, f.read(target)), "Restore the exact verified target from trash");
        move.abandon();
        require(move.batch() == null && target.toString().equals(ledger.known(source).uri)
            && history.importDestinations().equals(Set.of(target.toString())) && Arrays.equals(bytes, f.read(target)),
            "Abandon while SD is unavailable keeps the internal copy and references");
        require(marker.edit().putBoolean("abandoned", true).commit(), "Persist completed detached abandon");
      }
      return "PASS abandon detach absent " + secondary + ": inaccessible copies preserve batch; restored internal copy permits abandon";
    }

    require("photo-storage-abandon-detach-restored".equals(mode) && marker.getBoolean("abandoned", false),
        "Abandon while the SD is unmounted before verifying restoration");
    PhotoStorage.requireWritable(base, secondary);
    long deadline = SystemClock.uptimeMillis() + 6000;
    List<Row> sdRows;
    do {
      sdRows = f.rows(secondary);
      if (!sdRows.isEmpty()) break;
      SystemClock.sleep(50);
    } while (SystemClock.uptimeMillis() < deadline);
    require(sdRows.size() == 1, "The unchanged SD file is visible again after mounting");
    Uri original = sdRows.get(0).uri;
    try (f; CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         TagStore tags = new TagStore(f.context); ShareHistoryStore shares = new ShareHistoryStore(f.context)) {
      require(Arrays.equals(bytes, f.read(original)) && Arrays.equals(bytes, f.read(target))
          && f.rows(PRIMARY).size() == 1 && ledger.receiptCount() == 1
          && target.toString().equals(ledger.known(source).uri) && history.importDestinations().equals(Set.of(target.toString())),
          "Both byte-exact copies survive, with import histories retained on internal storage");
      RankingStore ranking = new RankingStore(f.context);
      LegacyLabels labels = new LegacyLabels(marker.getString("key", null), marker.getString("tag", null),
          marker.getString("photo", null), marker.getLong("sharedAt", -1));
      PhotoRepository.Photo replacement = f.photo(target);
      PhotoRepository.Photo retained = f.photo(original);
      assertOldLabels(replacement, tags, ranking, shares, labels);
      String retainedKey = ArchiveStore.key(retained);
      require(!retainedKey.equals(labels.key) && !TagStore.source(retained).equals(TagStore.source(replacement))
          && tags.photoId(retained, false) == null && !ranking.singleBest(retainedKey) && shares.history(retainedKey).isEmpty(),
          "The reattached original gains a separate, neutral identity");
      String retainedPhoto = tags.photoId(retained, true);
      tags.record(UUID.randomUUID().toString(), labels.tagId, retainedPhoto, 0, TagJudgments.Value.EXCLUDED);
      require(retainedPhoto.equals(tags.photoId(f.photo(original), false))
          && tags.load().value(labels.tagId, retainedPhoto) == TagJudgments.Value.EXCLUDED,
          "An edit to the reattached original stays bound to it across scans");
      assertOldLabels(f.photo(target), tags, ranking, shares, labels);
    } finally {
      require(base.deleteSharedPreferences(ABANDON_DETACH_PREFS), "Remove exact abandon-detach fixture marker");
    }
    return "PASS abandon detach restored " + secondary + ": both copies retained, independent identities and tag editing";
  }

  private static String detach(Context base, String mode, String secondary) throws Exception {
    SharedPreferences marker = base.getSharedPreferences(DETACH_PREFS, 0);
    if ("photo-storage-detach-seed".equals(mode)) {
      require(!marker.contains("id"), "Restore any prior detached fixture before seeding");
      Fixture f = new Fixture(base, secondary);
      boolean seeded = false;
      try (CameraImportLedger ledger = f.ledger()) {
        require(PhotoStorage.select(f.context, secondary), "Select SD for detach fixture");
        byte[] bytes = jpeg(0xff654321);
        CameraImportFile source = f.source("wifi:", "DETACH.JPG", bytes);
        Uri uri = f.importPhoto(new CameraPhotoImporter(f.context, ledger), source, bytes).uri;
        require(secondary.equals(PhotoStorage.volumeOf(uri)), "Detach fixture was saved to SD");
        require(marker.edit().putString("id", f.id).putString("volume", secondary)
            .putString("uri", uri.toString()).commit(), "Persist detach fixture marker");
        seeded = true;
        return "PASS detach seed " + secondary + " " + uri;
      } finally {
        if (!seeded) f.close();
      }
    }
    require(secondary.equals(marker.getString("volume", null)), "Use the SD volume recorded by the seed");
    Fixture f = new Fixture(base, secondary, UUID.fromString(marker.getString("id", "")).toString());
    Uri uri = Uri.parse(marker.getString("uri", ""));
    byte[] bytes = jpeg(0xff654321);
    CameraImportFile source = f.source("wifi:", "DETACH.JPG", bytes);
    if ("photo-storage-detach-absent".equals(mode)) {
      require(!MediaStore.getExternalVolumeNames(base).contains(secondary), "Actually unmount the SD volume before this check");
      try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
           PhotoMove move = f.move(ledger, history, boundary -> {})) {
        CameraPhotoImporter importer = new CameraPhotoImporter(f.context, ledger);
        expectUnavailable(() -> importer.importPhoto(source, out -> {
          throw new AssertionError("An unavailable receipt must not reread the camera source");
        }, f.id, true, false, () -> false));
        byte[] newBytes = jpeg(0xff543210);
        expectUnavailable(() -> f.importPhoto(importer, f.source("wifi:", "NEW.JPG", newBytes), newBytes));
        expectUnavailable(() -> move.plan(secondary));
        require(secondary.equals(PhotoStorage.selected(f.context)) && ledger.contentCount() == 1
            && ledger.receiptCount() == 1 && ledger.pending().isEmpty()
            && uri.toString().equals(ledger.known(source).uri) && f.rows(PRIMARY).isEmpty(),
            "Detached SD preserves selection and receipt without fallback");
      }
      return "PASS detach absent " + secondary + ": selection and receipt retained; no fallback publication";
    }
    require("photo-storage-detach-restored".equals(mode), "Unknown photo storage mode");
    PhotoStorage.requireWritable(base, secondary);
    try (f; CameraImportLedger ledger = f.ledger()) {
      CameraPhotoImporter.Result retry = new CameraPhotoImporter(f.context, ledger).importPhoto(source,
          out -> { throw new AssertionError("Reattached receipt must not reread the camera source"); },
          f.id, true, false, () -> false);
      require(!retry.imported && uri.equals(retry.uri) && Arrays.equals(bytes, f.read(uri))
          && f.rows(f.secondary).size() == 1 && f.rows(PRIMARY).isEmpty(),
          "Reattached SD reuses the exact receipt and full bytes");
    } finally {
      require(base.deleteSharedPreferences(DETACH_PREFS), "Clean detach fixture marker");
    }
    return "PASS detach restored " + secondary + ": same URI and full bytes, one copy";
  }

  private static final class InterruptedAtBoundary extends RuntimeException {}
  private interface IoAction { void run() throws IOException; }

  private static void expectUnavailable(IoAction action) throws IOException {
    try {
      action.run();
      throw new AssertionError("Missing storage was accepted");
    } catch (PhotoStorage.Unavailable expected) {
      // The volume itself is unavailable.
    }
  }

  private static String secondaryVolume(Context context) {
    return MediaStore.getExternalVolumeNames(context).stream().filter(volume -> !PRIMARY.equals(volume))
        .filter(volume -> PhotoStorage.directoryIfWritable(context, volume) != null)
        .findFirst().orElseThrow(() -> new AssertionError("A mounted, writable secondary MediaStore volume is required"));
  }

  private record Row(Uri uri, String owner, String path, boolean pending, boolean trashed) {}

  private static final class Fixture implements AutoCloseable {
    final ScopedContext context;
    final String id, path, secondary;
    final ContentResolver resolver;

    Fixture(Context base, String secondary) {
      this(base, secondary, UUID.randomUUID().toString());
    }

    Fixture(Context base, String secondary, String id) {
      this.id = id;
      this.path = "Pictures/CameraSync/" + id + "/";
      this.secondary = secondary;
      context = new ScopedContext(base, id);
      resolver = context.getContentResolver();
    }

    CameraImportLedger ledger() { return new CameraImportLedger(context, "ledger.db"); }
    HistoryDb history() { return new HistoryDb(context, "history.db"); }

    PhotoMove move(CameraImportLedger ledger, HistoryDb history, PhotoMove.Faults faults) {
      return new PhotoMove(context, ledger, history, new PhotoMoveStore(context), faults);
    }

    CameraImportFile source(String route, String name, byte[] bytes) {
      return new CameraImportFile(route + id, "DCIM/100CAM/" + name, bytes.length, id, CAPTURE);
    }

    CameraPhotoImporter.Result importPhoto(CameraPhotoImporter importer, CameraImportFile source, byte[] bytes)
        throws IOException {
      return importer.importPhoto(source, out -> out.write(bytes), id, false, true, () -> false);
    }

    Uri insert(String volume, String name, byte[] bytes) throws IOException {
      return insert(volume, name, bytes, CAPTURE * 1000);
    }

    Uri insert(String volume, String name, byte[] bytes, long dateTaken) throws IOException {
      ContentValues values = new ContentValues();
      values.put(DISPLAY_NAME, name);
      values.put(RELATIVE_PATH, path);
      values.put(MIME_TYPE, "image/jpeg");
      values.put(DATE_TAKEN, dateTaken);
      values.put(IS_PENDING, 1);
      Uri uri = resolver.insert(MediaStore.Images.Media.getContentUri(volume), values);
      require(uri != null, "Insert fixture under unique folder");
      write(uri, bytes);
      ContentValues publish = new ContentValues();
      publish.put(IS_PENDING, 0);
      require(resolver.update(uri, publish, null, null) == 1, "Publish fixture");
      return uri;
    }

    void write(Uri uri, byte[] bytes) throws IOException {
      try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
        if (output == null) throw new IOException("Could not write " + uri);
        output.write(bytes);
      }
    }

    byte[] read(Uri uri) throws IOException {
      try (InputStream input = resolver.openInputStream(MediaStore.setRequireOriginal(uri))) {
        if (input == null) throw new IOException("Could not read " + uri);
        return input.readAllBytes();
      }
    }

    long awaitCaptureTime(Uri uri, long expected) throws IOException {
      long deadline = SystemClock.uptimeMillis() + 6000;
      long captured = 0;
      do {
        try (Cursor row = resolver.query(uri, new String[]{DATE_TAKEN}, null, null, null)) {
          if (row == null || !row.moveToFirst()) throw new IOException("Could not query synthetic DATE_TAKEN");
          captured = row.getLong(0);
          if (captured == expected) return captured;
        }
        SystemClock.sleep(50);
      } while (SystemClock.uptimeMillis() < deadline);
      return captured;
    }

    PhotoRepository.Photo photo(Uri uri) throws IOException {
      long id = ContentUris.parseId(uri);
      String volume = PhotoStorage.volumeOf(uri);
      return new PhotoRepository(context).scan("すべて").stream()
          .filter(photo -> path.equals(photo.folder) && ContentUris.parseId(photo.uri) == id
              && volume.equals(PhotoStorage.volumeOf(photo.uri)))
          .findFirst().orElseThrow(() -> new AssertionError("Library cannot find " + uri));
    }

    List<Row> rows(String volume) throws IOException {
      Uri root = MediaStore.Images.Media.getContentUri(volume);
      Bundle args = new Bundle();
      args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
      args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
      args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, RELATIVE_PATH + "=?");
      args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[]{path});
      List<Row> rows = new ArrayList<>();
      try (Cursor cursor = resolver.query(root, new String[]{_ID, OWNER_PACKAGE_NAME, RELATIVE_PATH, IS_PENDING, IS_TRASHED}, args, null)) {
        if (cursor == null) throw new IOException("Cannot enumerate the isolated MediaStore folder");
        while (cursor.moveToNext()) rows.add(new Row(ContentUris.withAppendedId(root, cursor.getLong(0)),
            cursor.getString(1), cursor.getString(2), cursor.getInt(3) != 0, cursor.getInt(4) != 0));
      }
      return rows;
    }

    Row row(Uri uri) throws IOException {
      return rows(PhotoStorage.volumeOf(uri)).stream().filter(row -> row.uri.equals(uri))
          .findFirst().orElseThrow(() -> new AssertionError("Cannot find exact fixture row " + uri));
    }

    void trashOwned(Row row) throws IOException { setTrashed(row, true); }

    void setTrashed(Row row, boolean trashed) throws IOException {
      requireOwned(row);
      ContentValues values = new ContentValues();
      values.put(IS_TRASHED, trashed ? 1 : 0);
      Bundle args = new Bundle();
      args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
      require(resolver.update(row.uri, values, args) == 1 && row(row.uri).trashed == trashed,
          "Change trash state for the exact owned replacement fixture");
    }

    void removeOwned(Row row) throws IOException {
      requireOwned(row);
      Bundle args = new Bundle();
      args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
      args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
      require(resolver.delete(row.uri, args) == 1, "Remove exact owned fixture " + row.uri);
    }

    private void requireOwned(Row row) throws IOException {
      Row current = row(row.uri);
      require(context.getPackageName().equals(current.owner) && path.equals(current.path),
          "Mutations are limited to exact owned fixture rows");
    }

    @Override public void close() throws IOException {
      Cleanup cleanup = new Cleanup();
      for (String volume : List.of(PRIMARY, secondary)) cleanup.attempt(() -> {
        for (Row row : rows(volume)) cleanup.attempt(() -> removeOwned(row));
      });
      context.clean(cleanup);
      cleanup.finish();
    }
  }

  /** Keep a fixture's fixed-name product databases and selection apart from live app state. */
  private static final class ScopedContext extends ContextWrapper {
    private final String prefix;
    private final Set<String> preferences = new HashSet<>();

    ScopedContext(Context base, String id) {
      super(base);
      prefix = "photo-storage-check-" + id + "-";
    }

    @Override public Context getApplicationContext() { return this; }

    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
      String scoped = prefix + name;
      preferences.add(scoped);
      return super.getSharedPreferences(scoped, mode);
    }

    @Override public File getDatabasePath(String name) { return super.getDatabasePath(prefix + name); }

    @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
      return super.openOrCreateDatabase(prefix + name, mode, factory);
    }

    @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory,
        DatabaseErrorHandler handler) {
      return super.openOrCreateDatabase(prefix + name, mode, factory, handler);
    }

    void clean(Cleanup cleanup) {
      Context base = getBaseContext();
      // These are physical filenames, already scoped. Deleting a database also deletes its sidecars.
      for (String name : base.databaseList()) {
        if (name.startsWith(prefix) && databaseBasename(name).equals(name)) cleanup.attempt(() -> {
          base.deleteDatabase(name);
          require(!base.getDatabasePath(name).exists(), "Remove isolated fixture database " + name);
        });
      }
      // A second listing avoids deleting sidecars that the first pass already removed.
      for (String name : base.databaseList()) {
        if (name.startsWith(prefix)) cleanup.attempt(() -> {
          String database = databaseBasename(name);
          require(!name.equals(database) && !base.getDatabasePath(database).exists(),
              "Only orphaned sidecars can remain after fixture database deletion: " + name);
          Files.deleteIfExists(base.getDatabasePath(name).toPath());
        });
      }
      for (String name : preferences) cleanup.attempt(() -> {
        boolean deleted = base.deleteSharedPreferences(name);
        require(deleted || base.getSharedPreferences(name, 0).getAll().isEmpty(),
            "Remove isolated fixture preferences " + name);
      });
    }

    private static String databaseBasename(String name) {
      for (String suffix : List.of("-journal", "-wal", "-shm"))
        if (name.endsWith(suffix)) return name.substring(0, name.length() - suffix.length());
      int superJournal = name.indexOf("-mj");
      return superJournal < 0 ? name : name.substring(0, superJournal);
    }
  }

  private static final class Cleanup {
    private IOException failure;

    void attempt(IoAction action) {
      try {
        action.run();
      } catch (IOException | RuntimeException | AssertionError error) {
        if (failure == null) failure = new IOException("Synthetic fixture cleanup failed");
        failure.addSuppressed(error);
      }
    }

    void finish() throws IOException {
      if (failure != null) throw failure;
    }
  }

  private static byte[] jpeg(int color) {
    Bitmap bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888);
    try {
      bitmap.eraseColor(color);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      require(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output), "Encode synthetic JPEG");
      return output.toByteArray();
    } finally {
      bitmap.recycle();
    }
  }

  static final class CheckFailure extends AssertionError {
    CheckFailure(String label) { super(label); }
  }

  private static void require(boolean condition, String label) {
    if (!condition) throw new CheckFailure(label);
  }
}
