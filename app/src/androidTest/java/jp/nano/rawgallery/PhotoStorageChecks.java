package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import jp.nano.rawgallery.camera.CameraImportFile;
import jp.nano.rawgallery.camera.CameraImportHistoryPlan;
import jp.nano.rawgallery.camera.CameraImportLedger;
import jp.nano.rawgallery.camera.CameraPhotoImporter;
import jp.nano.rawgallery.camera.HistoryDb;
import jp.nano.rawgallery.camera.ImportedKey;

import static android.provider.MediaStore.MediaColumns.*;

final class PhotoStorageChecks {
  private static final String TARGET_PACKAGE = "jp.nano.rawgallery.sd162";
  private static final String PRIMARY = PhotoStorage.PRIMARY;
  private static final String MISSING = "dead-beef";
  private static final String DETACH_PREFS = "photo-storage-detach-fixture";
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
      rejectChangedSource(fixture);
    }
    return "PASS photo storage " + secondary + ": selected import, bulk move and original bytes, ledger and legacy history,"
        + " tags, rank, sharing, same-name files, retries after " + Arrays.toString(PhotoMove.Boundary.values())
        + ", verified-copy space estimate, cancellation, unavailable target, changed source";
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

  private static void rejectChangedSource(Fixture f) throws Exception {
    byte[] original = jpeg(0xff553377);
    byte[] changed = jpeg(0xff772255);
    CameraImportFile source = f.source("wifi:", "CHANGED.JPG", original);
    try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         PhotoMove move = f.move(ledger, history, boundary -> {})) {
      Uri uri = f.importPhoto(new CameraPhotoImporter(f.context, ledger), source, original).uri;
      require(PhotoStorage.select(f.context, f.secondary), "Set the SD destination");
      move.begin(move.plan(f.secondary));
      f.write(uri, changed);
      try {
        move.run(() -> false, batch -> {});
        throw new AssertionError("Move accepted a source changed after confirmation");
      } catch (IOException expected) {
        require(Arrays.equals(changed, f.read(uri)) && uri.toString().equals(ledger.known(source).uri)
            && f.rows(f.secondary).stream().allMatch(Row::pending),
            "Changed source remains intact and no replacement is published");
      }
    }
  }

  static String captureUi(Instrumentation test, String secondaryArg, int width, float font) throws Exception {
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

    File captures = new File(base.getCacheDir(), "photo-storage-checks");
    require(captures.isDirectory() || captures.mkdirs(), "Create capture directory");
    String profile = width + "-" + font;
    try (Fixture f = new Fixture(base, secondary)) {
      byte[] bytes = jpeg(0xff264167);
      Uri original = f.insert(PRIMARY, "CONFIRM.JPG", bytes);
      history.recordImport(MODEL, f.id, new ImportedKey("CONFIRM.JPG", bytes.length, CAPTURE), original.toString());
      try {
        require(PhotoStorage.select(base, PRIMARY), "Set initial UI storage");
        captureActivity(test, base, secondary, f, original, bytes, captures, profile);
      } finally {
        history.resetHistory(MODEL, f.id);
      }
    } finally {
      SharedPreferences.Editor restore = settings.edit();
      if (hadSelection) restore.putString("photo_volume", previous);
      else restore.remove("photo_volume");
      require(restore.commit(), "Restore original isolated app preference");
    }
    return "PASS photo storage UI " + profile + ": settings, single selection, move confirmation and cancel. "
        + captures.getAbsolutePath();
  }

  private static void captureActivity(Instrumentation test, Context base, String secondary, Fixture f,
      Uri original, byte[] bytes, File captures, String profile) throws Exception {
    PhotoStorageActivity activity = (PhotoStorageActivity) test.startActivitySync(
        new Intent(base, PhotoStorageActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      UiAutomation automation = test.getUiAutomation();
      AccessibilityNodeInfo choose = awaitNode(automation, node -> hasText(node, "保存先を変更"));
      assertTouchTarget(activity, choose, "Storage selector");
      assertTouchTarget(activity, awaitNode(automation, node -> hasText(node, "取り込み済みの写真を移動")), "Move action");
      capture(automation, captures, profile + "-settings");
      click(choose);

      String label = PhotoStorage.label(base, secondary);
      awaitNode(automation, node -> hasText(node, "内部ストレージ") && node.isCheckable() && node.isChecked());
      AccessibilityNodeInfo option = awaitNode(automation, node -> hasText(node, label) && node.isCheckable());
      capture(automation, captures, profile + "-choices");
      click(option);
      click(awaitNode(automation, node -> hasText(node, "設定")));
      awaitNode(automation, node -> hasText(node, label) && !node.isCheckable());
      require(secondary.equals(PhotoStorage.selected(base)), "Radio selection persists the secondary volume");

      click(awaitNode(automation, node -> hasText(node, "取り込み済みの写真を移動")));
      awaitNode(automation, node -> node.getText() != null && node.getText().toString().contains(label + "に1枚"));
      capture(automation, captures, profile + "-confirm");
      click(awaitNode(automation, node -> hasText(node, "キャンセル")));
      require(Arrays.equals(bytes, f.read(original)) && f.rows(secondary).isEmpty(),
          "Cancelling confirmation leaves the original in place");
    } finally {
      test.runOnMainSync(activity::finish);
      test.waitForIdleSync();
    }
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

  private static void assertTouchTarget(Context context, AccessibilityNodeInfo node, String name) {
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    int minimum = GalleryUi.dp(context, 48);
    require(bounds.height() >= minimum && bounds.width() >= minimum, name + " has a 48dp touch target");
  }

  private static void capture(UiAutomation automation, File directory, String name) throws IOException {
    Bitmap bitmap = automation.takeScreenshot();
    require(bitmap != null, "Capture visible Android UI");
    try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
      require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Write Android UI capture");
    } finally {
      bitmap.recycle();
    }
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

  private record Row(Uri uri, String owner, String path, boolean pending) {}

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
      ContentValues values = new ContentValues();
      values.put(DISPLAY_NAME, name);
      values.put(RELATIVE_PATH, path);
      values.put(MIME_TYPE, "image/jpeg");
      values.put(DATE_TAKEN, CAPTURE * 1000);
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
      try (Cursor cursor = resolver.query(root, new String[]{_ID, OWNER_PACKAGE_NAME, RELATIVE_PATH, IS_PENDING}, args, null)) {
        if (cursor == null) throw new IOException("Cannot enumerate the isolated MediaStore folder");
        while (cursor.moveToNext()) rows.add(new Row(ContentUris.withAppendedId(root, cursor.getLong(0)),
            cursor.getString(1), cursor.getString(2), cursor.getInt(3) != 0));
      }
      return rows;
    }

    @Override public void close() throws IOException {
      try {
        for (String volume : List.of(PRIMARY, secondary)) {
          for (Row row : rows(volume)) {
            require(context.getPackageName().equals(row.owner) && path.equals(row.path),
                "Cleanup is limited to owned fixture rows");
            require(resolver.delete(row.uri, null, null) == 1, "Remove exact owned fixture " + row.uri);
          }
        }
      } finally {
        context.clean();
      }
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

    void clean() {
      for (String name : databaseList())
        if (name.startsWith(prefix)) require(deleteDatabase(name), "Remove isolated fixture database");
      for (String name : preferences) deleteSharedPreferences(name);
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

  private static void require(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }
}
