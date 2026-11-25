package jp.nano.rawgallery;

import android.content.*;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.net.Uri;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import org.json.JSONObject;

/** Uses isolated databases and synthetic records only; never opens the user's archive database. */
final class IndexSignatureChecks {
  private static Context isolated(Context context, File root) {
    return new ContextWrapper(context) {
      @Override public Context getApplicationContext() { return this; }
      @Override public File getDatabasePath(String name) { return new File(root, name); }
      @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
        return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
      }
      @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
        return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(), factory, handler);
      }
    };
  }
  private record SourceSignature(String whole, String media, long moveRevision,
      long archiveRevision, long e2Revision, String permissions, String uriGrants) {}

  private static SourceSignature signature(Context context, ArchiveStore store) throws Exception {
    Method method = GalleryIndex.class.getDeclaredMethod("signature", Context.class, int.class);
    method.setAccessible(true);
    int burstGapMillis = 1000;
    String whole = (String) method.invoke(null, context, burstGapMillis);
    String burstPrefix = ":burst-gap-ms:" + burstGapMillis + ":burst-number-v1:";
    String moveMarker = ":move-v1:";
    String archiveMarker = ":capture-order-v1:video-v1:archive-v3:";
    check(whole.startsWith(burstPrefix), "signature burst gap and rule preserved");
    int mediaStart = burstPrefix.length();
    int mediaEnd = whole.indexOf(moveMarker, mediaStart);
    check(mediaEnd >= mediaStart, "signature move marker present");
    int moveStart = mediaEnd + moveMarker.length();
    int moveEnd = whole.indexOf(':', moveStart);
    check(moveEnd > moveStart && whole.startsWith(archiveMarker, moveEnd),
        "signature capture order, video and archive rules preserved");
    int archiveStart = moveEnd + archiveMarker.length();
    int archiveEnd = whole.indexOf(':', archiveStart);
    int e2End = whole.indexOf(':', archiveEnd + 1);
    check(archiveEnd > archiveStart && e2End > archiveEnd + 1, "archive and e2 revisions present");
    String[] grants = whole.substring(e2End + 1).split(":", 5);
    check(grants.length == 5, "signature grants present");
    long archiveRevision = Long.parseLong(whole.substring(archiveStart, archiveEnd));
    check(archiveRevision == store.indexRevision(), "signature reflects archive store revision");
    return new SourceSignature(whole, whole.substring(mediaStart, mediaEnd),
        Long.parseLong(whole.substring(moveStart, moveEnd)), archiveRevision,
        Long.parseLong(whole.substring(archiveEnd + 1, e2End)),
        String.join(":", Arrays.copyOf(grants, 4)), grants[4]);
  }

  private static String checkStableSignature(SourceSignature before, SourceSignature after, String stage) {
    boolean sameMedia = before.media().equals(after.media());
    boolean sameMove = before.moveRevision() == after.moveRevision();
    boolean sameE2 = before.e2Revision() == after.e2Revision();
    boolean samePermissions = before.permissions().equals(after.permissions());
    boolean sameUriGrants = before.uriGrants().equals(after.uriGrants());
    boolean sameWhole = before.whole().equals(after.whole());

    check(before.archiveRevision() == after.archiveRevision(), stage + ": archive revision stable");
    check(sameMove && sameE2, stage + ": other isolated database revisions stable");
    // Real Android MediaStore and grants can change while a private archive fixture is open.
    if (sameMedia && samePermissions && sameUriGrants)
      check(sameWhole, stage + ": complete signature stable when external inputs match");
    return stage + " changed(media=" + !sameMedia + ", move=" + !sameMove + ", e2=" + !sameE2
        + ", permissions=" + !samePermissions + ", grants=" + !sameUriGrants + ", whole=" + !sameWhole + ")";
  }

  private static void check(boolean valid, String label) { if (!valid) throw new AssertionError(label); }
  private static String runVersion(Context context, int oldVersion) throws Exception {
    File root = new File(context.getCacheDir(), "index-signature-" + UUID.randomUUID());
    check(root.mkdir(), "isolated directory");
    Context isolated = isolated(context, root);
    File file = isolated.getDatabasePath("archive.db");
    try {
      if (oldVersion > 0) try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
        db.execSQL("CREATE TABLE photos (key TEXT PRIMARY KEY, state TEXT NOT NULL, payload TEXT NOT NULL)");
        db.execSQL("INSERT INTO photos VALUES ('seed','verified','{\"key\":\"seed\",\"name\":\"synthetic\"}')");
        if (oldVersion >= 2) {
          db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, model TEXT NOT NULL, taken INTEGER NOT NULL)");
          db.execSQL("INSERT INTO metadata VALUES ('existing','synthetic',42)");
        }
        if (oldVersion == 3) {
          db.execSQL("CREATE TABLE index_revision (id INTEGER PRIMARY KEY CHECK(id=1), value INTEGER NOT NULL)");
          db.execSQL("INSERT INTO index_revision VALUES (1,91)");
          for (String operation : new String[] {"INSERT", "UPDATE", "DELETE"})
            db.execSQL("CREATE TRIGGER index_revision_" + operation + " AFTER " + operation
                + " ON photos BEGIN UPDATE index_revision SET value=value+1 WHERE id=1; END");
        }
        db.setVersion(oldVersion);
      }
      try (ArchiveStore store = new ArchiveStore(isolated)) {
        SQLiteDatabase db = store.getWritableDatabase();
        check(db.getVersion() == 4, "schema upgraded");
        check(oldVersion == 0 || "synthetic".equals(store.get("seed").getString("name")), "photo payload preserved");
        check(oldVersion < 2 || store.metadataSnapshot().get("existing").taken == 42, "metadata preserved");
        long revision = store.indexRevision();
        check(oldVersion < 3 || revision == 91, "version 3 revision preserved");
        check(oldVersion < 2 || store.metadataSnapshot().get("existing").burstFrame == null,
            "existing entries start unprobed");
        SourceSignature before = signature(isolated, store);
        if (oldVersion == 3) {
          checkBurstCache(isolated, root, store);
          checkMetadataBatch(isolated, root, store);
        }
        checkStableSignature(before, signature(isolated, store), "burst and batch metadata");
        ContentValues metadata = new ContentValues();
        metadata.put("key", "new-derived"); metadata.put("model", "synthetic"); metadata.put("taken", 123);
        check(db.insertOrThrow("metadata", null, metadata) >= 0, "metadata saved");
        check(store.indexRevision() == revision, "metadata does not invalidate source");
        checkStableSignature(before, signature(isolated, store), "metadata insert");
        JSONObject photo = new JSONObject().put("key", "new-photo").put("name", "synthetic");
        store.put(photo, "pending");
        check(store.indexRevision() > revision, "insert invalidates");
        SourceSignature afterInsert = signature(isolated, store);
        check(before.archiveRevision() != afterInsert.archiveRevision()
            && !before.whole().equals(afterInsert.whole()), "photo signature changes");
        revision = store.indexRevision();
        photo.put("name", "replacement"); store.put(photo, "verified");
        check(store.indexRevision() > revision, "replace invalidates");
        revision = store.indexRevision();
        db.execSQL("UPDATE photos SET state='updated' WHERE key='new-photo'");
        check(store.indexRevision() > revision, "update invalidates");
        revision = store.indexRevision();
        before = signature(isolated, store);
        db.beginTransaction();
        try { store.put(new JSONObject().put("key", "rolled-back"), "pending"); }
        finally { db.endTransaction(); }
        check(store.get("rolled-back") == null, "photo rolled back");
        check(store.indexRevision() == revision, "revision rolled back");
        checkStableSignature(before, signature(isolated, store), "rollback");
        db.delete("photos", "key=?", new String[] {"new-photo"});
        check(store.indexRevision() > revision, "delete invalidates");
        before = signature(isolated, store);
        store.setWriteAheadLoggingEnabled(true);
        check(db.isWriteAheadLoggingEnabled(), "WAL enabled for checkpoint fixture");
        SourceSignature afterWal = signature(isolated, store);
        String walChange = checkStableSignature(before, afterWal, "WAL switch");

        metadata.put("key", "wal-derived");
        db.insertOrThrow("metadata", null, metadata);
        SourceSignature afterMetadata = signature(isolated, store);
        String metadataChange = checkStableSignature(afterWal, afterMetadata, "WAL metadata");

        int walFrames;
        int checkpointedFrames;
        try (Cursor checkpoint = db.rawQuery("PRAGMA wal_checkpoint(FULL)", null)) {
          check(checkpoint.moveToFirst(), "WAL checkpoint returned frame counts");
          walFrames = checkpoint.getInt(1);
          checkpointedFrames = checkpoint.getInt(2);
          check(checkpoint.getInt(0) == 0 && walFrames > 0 && checkpointedFrames == walFrames,
              "WAL checkpoint copied all frames (" + checkpointedFrames + "/" + walFrames + ")");
        }
        SourceSignature afterCheckpoint = signature(isolated, store);
        String checkpointChange = checkStableSignature(afterMetadata, afterCheckpoint, "checkpoint");
        return "Archive v" + oldVersion + ": " + walChange + "; " + metadataChange
            + "; " + checkpointChange + " (" + checkpointedFrames + "/" + walFrames + " frames)\n";
      }
    } finally {
      File[] files = root.listFiles();
      if (files != null) for (File child : files) check(child.delete(), "isolated DB cleanup");
      check(root.delete(), "isolated directory cleanup");
    }
  }
  private static void checkBurstCache(Context context, File root, ArchiveStore store) throws Exception {
    File jpeg = new File(root, "numbered.jpg");
    var photo = new PhotoRepository.Photo(Uri.fromFile(jpeg), jpeg.getName(), "fixture/", 0, 1, 0);
    String key = ArchiveStore.key(photo);
    ContentValues saved = new ContentValues();
    saved.put("key", key);
    saved.put("model", "Preserved model");
    saved.put("taken", 987L);
    store.getWritableDatabase().insertOrThrow("metadata", null, saved);
    var stale = store.metadata(photo);
    check(stale.burstFrame == null, "a normal cache read opens no original");
    try {
      probe(store, photo, key, stale);
      throw new AssertionError("missing local file must be retryable");
    } catch (IOException expected) {
      check(store.metadata(photo).burstFrame == null, "I/O failure retains NULL");
    }
    byte[] jpegPixels = jpegPixels();
    checkJpegProbeFailures(jpegPixels);
    Files.write(jpeg.toPath(), numberedJpeg(jpegPixels, 3));
    ArchiveStore.RawMetadata filled = probe(store, photo, key, stale);
    check(filled.burstFrame != null && filled.burstFrame.number() == 3, "JPEG EXIF MakerNote retry succeeds");
    check(filled.model.equals("Preserved model") && filled.taken == 987 && store.metadataSnapshot().containsKey(key),
        "burst update preserves existing key/model/taken");
    // A second caller observed the old NULL while the winner was reading its original.
    File alternate = new File(root, "alternate.jpg");
    Files.write(alternate.toPath(), numberedJpeg(jpegPixels, 8));
    var secondRead = new PhotoRepository.Photo(Uri.fromFile(alternate), alternate.getName(), "fixture/", 0, 1, 0);
    try (ArchiveStore concurrent = new ArchiveStore(context)) {
      check(probe(concurrent, secondRead, key, stale).burstFrame.equals(filled.burstFrame),
          "stale competing probe cannot replace the winner");
    }
    File single = new File(root, "single.jpg");
    Files.write(single.toPath(), jpegPixels);
    var plain = new PhotoRepository.Photo(Uri.fromFile(single), single.getName(), "fixture/", 0, 1, 0);
    var noFrame = probe(store, plain, ArchiveStore.key(plain), store.metadata(plain));
    check(noFrame.burstFrame.equals(RawPreview.BurstFrame.NONE), "no frame caches explicit zero");
    check(store.metadata(plain).burstFrame.equals(RawPreview.BurstFrame.NONE), "no frame persisted");
    File mislabeled = new File(root, "mislabeled.jpg");
    Files.write(mislabeled.toPath(), BurstMakerNoteFixture.tiff(
        BurstMakerNoteFixture.makerNote(true, 3, 1, 8, 0, 0, 4, 0x49), false));
    var wrongFormat = new PhotoRepository.Photo(Uri.fromFile(mislabeled), mislabeled.getName(), "fixture/", 0, 1, 0);
    check(probe(store, wrongFormat, ArchiveStore.key(wrongFormat), store.metadata(wrongFormat))
        .burstFrame.equals(RawPreview.BurstFrame.NONE), "non-JPEG in a .jpg cannot supply a frame");
    var archived = new PhotoRepository.Photo(Uri.parse("archive://seed"), "archived.orf", "fixture/", 1, 1, 1);
    check(!ArchiveStore.canProbeBurstFrame(archived), "archived-only cannot open original for probing");
    var e2 = new PhotoRepository.Photo(Uri.parse("e2archive://object-id"), photo.name, photo.folder,
        photo.size, photo.modified, photo.taken);
    check(ArchiveStore.key(e2).equals(key), "E2 reconstructed legacy key can coincide with a local cache key");
    check(GalleryIndex.burstFrame(e2, store.metadataSnapshot().get(key)).equals(RawPreview.BurstFrame.NONE),
        "E2 legacy-key alias cannot consume an unproven ordinal");
    check(GalleryIndex.burstFrame(archived, filled).equals(filled.burstFrame), "exact archive cache is eligible");
    var cloudSaf = new PhotoRepository.Photo(Uri.parse("content://external.cloud.provider/document/raw"),
        "cloud.orf", "fixture/", 1, 1, 1);
    check(!ArchiveStore.canProbeBurstFrame(cloudSaf), "unknown SAF provider cannot open original for probing");
  }

  private static ArchiveStore.RawMetadata probe(ArchiveStore store, PhotoRepository.Photo photo, String key,
      ArchiveStore.RawMetadata cached) throws IOException {
    var frame = store.inspectBurstFrame(photo);
    return store.writeIndexMetadata(java.util.List.of(new ArchiveStore.IndexMetadata(key, cached, false, frame)),
        () -> false).get(key);
  }

  private static void checkMetadataBatch(Context context, File root, ArchiveStore store) throws Exception {
    long revision = store.indexRevision();
    File jpeg = new File(root, "batch-original.jpg");
    byte[] pixels = jpegPixels();
    Files.write(jpeg.toPath(), pixels);
    var local = new PhotoRepository.Photo(Uri.fromFile(jpeg), jpeg.getName(), "Batch/", pixels.length, 12, 0);
    String originalKey = ArchiveStore.key(local);
    var inspected = store.inspectMetadata(local);
    var unnumbered = store.inspectBurstFrame(local);
    check(store.readMetadata(originalKey) == null, "source inspection does not write");
    var result = store.writeIndexMetadata(java.util.List.of(
        new ArchiveStore.IndexMetadata(originalKey, inspected, true, unnumbered)), () -> false);
    check(result.get(originalKey).burstFrame.equals(RawPreview.BurstFrame.NONE), "batch retains explicit zero");

    var fresh = new ArchiveStore.RawMetadata("Fixture camera", 320L);
    var batch = java.util.List.of(new ArchiveStore.IndexMetadata("first-batch", fresh, true, null),
        new ArchiveStore.IndexMetadata("second-batch", fresh, true, null));
    var callbacks = new java.util.concurrent.atomic.AtomicInteger();
    check(store.writeIndexMetadata(batch, () -> callbacks.incrementAndGet() == 3) == null,
        "cancellation after the first row rolls back the batch");
    check(store.readMetadata("first-batch") == null && store.readMetadata("second-batch") == null,
        "cancelled entries were not published");
    check(store.readMetadata(originalKey) != null, "a prior committed batch remains usable");

    SQLiteDatabase db = store.getWritableDatabase();
    db.execSQL("CREATE TRIGGER test_metadata_failure BEFORE INSERT ON metadata "
        + "WHEN NEW.key='second-batch' BEGIN SELECT RAISE(ABORT,'fixture'); END");
    try {
      try {
        store.writeIndexMetadata(batch, () -> false);
        throw new AssertionError("write error was ignored");
      } catch (IOException | android.database.sqlite.SQLiteException expected) {
        check(store.readMetadata("first-batch") == null && store.readMetadata("second-batch") == null,
            "write error rolls back the batch");
      }
    } finally {
      db.execSQL("DROP TRIGGER test_metadata_failure");
    }
    check(store.writeIndexMetadata(batch, () -> false).size() == 2, "retry after rollback");

    var observed = new ArchiveStore.RawMetadata("Snapshot model", 345L);
    var staleFrame = new RawPreview.BurstFrame(1, 7);
    var winnerFrame = new RawPreview.BurstFrame(1, 8);
    try (ArchiveStore concurrent = new ArchiveStore(context)) {
      var winner = new ArchiveStore.RawMetadata("Concurrent model", 400L);
      concurrent.writeIndexMetadata(java.util.List.of(
          new ArchiveStore.IndexMetadata("competing-insert", winner, true, null),
          new ArchiveStore.IndexMetadata("competing-probe", observed, true, winnerFrame)), () -> false);
      var resolved = store.writeIndexMetadata(java.util.List.of(
          new ArchiveStore.IndexMetadata("competing-insert", observed, true, staleFrame),
          new ArchiveStore.IndexMetadata("competing-probe", observed, false, staleFrame)), () -> false);
      var current = resolved.get("competing-insert");
      check(current.model.equals(winner.model) && current.taken == winner.taken && current.burstFrame == null,
          "stale metadata does not alter another insert or its probe state");
      check(resolved.get("competing-probe").burstFrame.equals(winnerFrame), "stale probe keeps committed winner");
      concurrent.getWritableDatabase().delete("metadata", "key=?", new String[]{"competing-probe"});
      var missing = store.writeIndexMetadata(java.util.List.of(
          new ArchiveStore.IndexMetadata("competing-probe", observed, false, staleFrame)), () -> false);
      check(missing.get("competing-probe").burstFrame == null && store.readMetadata("competing-probe") == null,
          "an update drafted from a removed row does not recreate it");
    }

    check(java.util.Arrays.equals(pixels, Files.readAllBytes(jpeg.toPath())), "source file unchanged");
    Files.delete(jpeg.toPath());
    try {
      store.inspectMetadata(local);
      throw new AssertionError("removed original supplied metadata");
    } catch (IOException expected) {
      check(store.readMetadata(originalKey) != null, "missing source does not rewrite prior cache");
    }
    check(revision == store.indexRevision(), "derived writes do not change source revision");
  }

  private static void checkE2IndexMetadata(Context context) throws Exception {
    File root = new File(context.getCacheDir(), "e2-index-metadata-" + UUID.randomUUID());
    check(root.mkdir(), "isolated E2 index directory");
    Context fixture = isolated(context, root);
    String folder = "DCIM/e2-index-fixture/";
    String recent = e2Key(11), alias = e2Key(12), otherState = e2Key(13);
    String missing = e2Key(14), malformed = e2Key(15), unrequested = e2Key(16), replacement = e2Key(17);
    JSONObject a = e2Receipt(recent, folder, "Recent", 7000);
    JSONObject b = e2Receipt(alias, folder, "Alias", 5000);
    var photos = java.util.List.of(E2Cleanup.galleryPhoto(a), E2Cleanup.galleryPhoto(b),
        E2Cleanup.galleryPhoto(a), E2Cleanup.galleryPhoto(e2Receipt(otherState, folder, "Other state", 1)),
        E2Cleanup.galleryPhoto(e2Receipt(missing, folder, "Missing", 1)),
        E2Cleanup.galleryPhoto(e2Receipt(malformed, folder, "Malformed", 1)),
        E2Cleanup.galleryPhoto(e2Receipt("invalid-e2-key", folder, "Invalid", 1)));
    check(ArchiveStore.key(photos.get(0)).equals(ArchiveStore.key(photos.get(1))),
        "two E2 URIs can share the derived logical metadata key");
    try (ArchiveStore e2 = E2Settings.openRecords(fixture)) {
      e2.put(a, E2Cleanup.RELEASED);
      e2.put(b, E2Cleanup.RELEASING);
      e2.put(e2Receipt(otherState, folder, "Other state", 1), "verified");
      e2.put(e2Receipt(unrequested, "DCIM/another-folder/", "Unrequested", 2), E2Cleanup.RELEASED);
      var values = new ContentValues();
      values.put("key", malformed); values.put("state", E2Cleanup.RELEASED); values.put("payload", "{");
      e2.getWritableDatabase().insertOrThrow("photos", null, values);
      long before = e2.indexRevision();

      var bulk = E2Cleanup.localIndexMetadata(fixture, photos, java.util.Map.of(), () -> false);
      check(bulk.keySet().equals(java.util.Set.of(recent, alias)),
          "bulk only returns requested and eligible E2 IDs with usable records");
      check(bulk.get(recent).taken == 7000 && bulk.get(recent).model.equals("Recent")
          && bulk.get(alias).taken == 5000 && bulk.get(alias).model.equals("Alias"),
          "logical alias and duplicate URI never mix receipt values");
      check(ArchiveStore.RawMetadata.fromReceipt(E2Cleanup.localRecord(fixture, photos.get(1).uri)).taken
          == bulk.get(alias).taken, "bulk and single receipt agree");
      check(e2.indexRevision() == before, "read does not change archive state");
      var derivedCache = java.util.Map.of(ArchiveStore.key(photos.get(0)),
          new ArchiveStore.RawMetadata("Local derived metadata", 2000));
      check(E2Cleanup.localIndexMetadata(fixture, photos, derivedCache, () -> false).isEmpty(),
          "the pre-existing derived cache takes precedence for both logical aliases");
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      check(E2Cleanup.localIndexMetadata(fixture, photos, java.util.Map.of(),
          () -> calls.incrementAndGet() >= photos.size() + 4)
          == null, "cancellation after reading begins discards the partial result");
      var multiQuery = new java.util.TreeSet<String>();
      multiQuery.add(recent);
      for (int id = 1000; id < 1512; id++) multiQuery.add(e2Key(id));
      String last = e2Key(1511);
      e2.put(e2Receipt(last, "DCIM/another-folder/", "Last batch", 144), E2Cleanup.RELEASED);
      check(e2.releasedIndexMetadata(multiQuery, () -> false).keySet().equals(java.util.Set.of(recent, last)),
          "a request exceeding one SQL batch resolves the first and last IDs");

      e2.getWritableDatabase().delete("photos", "key=?", new String[]{malformed});
      check(GalleryIndex.rebuild(fixture, true, () -> false), "publish the initial E2 index");
      try (GalleryIndex index = new GalleryIndex(fixture)) {
        var published = index.load(folder, "すべて").photos;
        check(published.size() == 2 && published.stream().allMatch(photo -> photo.taken == 7000),
            "existing logical metadata precedence for E2 aliases is preserved");
      }

      try (ArchiveStore concurrent = E2Settings.openRecords(fixture)) {
        concurrent.getWritableDatabase().delete("photos", "key=?", new String[]{recent});
        concurrent.put(e2Receipt(alias, folder, "Alias now verified", 9000), "verified");
        concurrent.put(e2Receipt(replacement, folder, "Replacement", 12000), E2Cleanup.RELEASED);
      }
      check(bulk.get(recent).taken == 7000, "the prior read is not mutated by a competing writer");
      check(E2Cleanup.localIndexMetadata(fixture, photos, java.util.Map.of(), () -> false).isEmpty(),
          "next read hides records deleted or no longer released");
      check(!GalleryIndex.rebuild(fixture, false, () -> true), "cancel the changed E2 snapshot");
      try (GalleryIndex index = new GalleryIndex(fixture)) {
        check(index.load(folder, "すべて").photos.size() == 2, "cancel keeps the old published E2 index");
      }
      check(GalleryIndex.rebuild(fixture, false, () -> false), "archive revision retry without forcing");
      try (GalleryIndex index = new GalleryIndex(fixture)) {
        var published = index.load(folder, "すべて").photos;
        check(published.size() == 1 && published.get(0).uri.getHost().equals(replacement)
            && published.get(0).taken == 12000, "replacement restores expected order without stale rows");
      }
    } finally {
      File[] files = root.listFiles();
      if (files != null) for (File child : files) check(child.delete(), "isolated E2 DB cleanup");
      check(root.delete(), "isolated E2 directory cleanup");
    }
  }

  private static String e2Key(int value) {
    return String.format(java.util.Locale.ROOT, "%032x", value);
  }

  private static JSONObject e2Receipt(String key, String folder, String model, long taken) throws Exception {
    return new JSONObject().put("key", key).put("name", "ARCHIVED.JPG").put("folder", folder)
        .put("uri", "content://media/external/file/1120320").put("size", 20).put("modified", 100)
        .put("sha", key).put("model", model).put("taken", taken);
  }

  private static void checkJpegProbeFailures(byte[] pixels) throws Exception {
    byte[] valid = numberedJpeg(pixels, 3);
    for (int length = 0; length <= 3; length++) {
      try {
        ArchiveStore.readJpegBurstFrame(new ByteArrayInputStream(java.util.Arrays.copyOf(valid, length)));
        throw new AssertionError("short matching JPEG prefix must be retryable");
      } catch (java.io.EOFException expected) { /* A matching prefix ended before the first marker. */ }
    }
    byte[] invalidMarker = valid.clone();
    invalidMarker[2] = 0;
    check(ArchiveStore.readJpegBurstFrame(new ByteArrayInputStream(invalidMarker))
        .equals(RawPreview.BurstFrame.NONE), "two SOI bytes alone do not identify a JPEG");
    byte[] earlySos = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xda};
    InputStream noPixelReads = new InputStream() {
      private int offset;
      public int read() {
        if (offset == earlySos.length) throw new AssertionError("must stop before pixel data");
        return Byte.toUnsignedInt(earlySos[offset++]);
      }
      public int available() { throw new AssertionError("probe must not inspect remaining file length"); }
    };
    check(ArchiveStore.readJpegBurstFrame(noPixelReads).equals(RawPreview.BurstFrame.NONE),
        "stop at SOS without reading or sizing pixels");
    byte[] largeWithoutMakerNote = java.util.Arrays.copyOf(pixels, 2 * ArchiveStore.JPEG_BURST_SCAN_LIMIT);
    check(ArchiveStore.readJpegBurstFrame(new ByteArrayInputStream(largeWithoutMakerNote))
        .equals(RawPreview.BurstFrame.NONE), "large JPEG without a MakerNote caches absence when scan ends normally");
    byte[] late = insertJpegSegments(valid, 2);
    var shortReads = new ByteArrayInputStream(late) {
      @Override public synchronized int read(byte[] bytes, int offset, int length) {
        return super.read(bytes, offset, Math.min(1, length));
      }
      @Override public synchronized long skip(long size) { return 0; }
      @Override public synchronized int available() { return 0; }
    };
    check(ArchiveStore.readJpegBurstFrame(shortReads).number() == 3,
        "find late EXIF after skipped non-EXIF data with short reads, zero skip and zero available");
    var skipLess = new ByteArrayInputStream(late) {
      private int singles;
      @Override public synchronized int read() {
        if (++singles > 100) throw new AssertionError("zero skip must fall back to bulk reads");
        return super.read();
      }
      @Override public synchronized long skip(long size) { return 0; }
    };
    check(ArchiveStore.readJpegBurstFrame(skipLess).number() == 3, "zero skip falls back to small bulk reads");
    byte[] invalidLength = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe1, 0, 1};
    check(ArchiveStore.readJpegBurstFrame(new ByteArrayInputStream(invalidLength))
        .equals(RawPreview.BurstFrame.NONE), "invalid segment length is ignored");
    byte[] incomplete = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe1, 0, 12, 'E', 'x'};
    try {
      ArchiveStore.readJpegBurstFrame(new ByteArrayInputStream(incomplete));
      throw new AssertionError("EOF inside an advertised JPEG segment must be retryable");
    } catch (java.io.EOFException expected) { /* The original may become readable on retry. */ }
    byte[] badOffset = BurstMakerNoteFixture.tiff(BurstMakerNoteFixture.makerNote(true, 3, 1, 3, 0, 0, 4, 0x49), false);
    java.nio.ByteBuffer.wrap(badOffset).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(4, Integer.MAX_VALUE);
    check(ArchiveStore.readJpegBurstFrame(new ByteArrayInputStream(BurstMakerNoteFixture.jpeg(pixels, badOffset)))
        .equals(RawPreview.BurstFrame.NONE), "EXIF TIFF offsets cannot escape the APP1 payload");
    InputStream flaky = new InputStream() {
      private int offset;
      public int read() throws IOException {
        // Android reads the JPEG magic successfully and then encounters a failing source.
        if (offset == 16) throw new IOException("fixture read interruption");
        return Byte.toUnsignedInt(valid[offset++]);
      }
      public int available() { return valid.length - offset; }
    };
    try {
      ArchiveStore.readJpegBurstFrame(flaky);
      throw new AssertionError("an underlying JPEG IOException must be retryable");
    } catch (IOException expected) {
      check(expected.getMessage().equals("fixture read interruption"), "underlying JPEG failure propagated");
    }
    try {
      ArchiveStore.readJpegBurstFrame(new ByteArrayInputStream(insertJpegSegments(valid, 17)));
      throw new AssertionError("JPEG scan exhaustion must be retryable");
    } catch (IOException expected) {
      check(expected.getMessage().contains("読み取り上限"), "before-SOS marker scans are bounded");
    }
  }

  private static byte[] insertJpegSegments(byte[] jpeg, int segments) throws IOException {
    var output = new ByteArrayOutputStream();
    output.write(jpeg, 0, 2);
    byte[] app2 = new byte[65537];
    app2[0] = (byte) 0xff;
    app2[1] = (byte) 0xe2;
    app2[2] = app2[3] = (byte) 0xff;
    for (int i = 0; i < segments; i++) output.write(app2);
    output.write(jpeg, 2, jpeg.length - 2);
    return output.toByteArray();
  }

  private static void checkBurstVolumes() {
    var primary = new PhotoRepository.Photo(Uri.parse("content://media/external/file/1"), "1.orf",
        "Pictures/OM/", 1, 1, 1);
    var sd = new PhotoRepository.Photo(Uri.parse("content://media/abcd-1234/file/2"), "2.orf",
        "Pictures/OM/", 1, 1, 2000);
    var primarySaf = new PhotoRepository.Photo(Uri.parse("content://com.android.externalstorage.documents/"
        + "document/primary%3APictures%2FOM%2F2.orf"), "2.orf", "Pictures/OM/", 1, 1, 2000);
    String left = GalleryIndex.burstStream(primary, "Synthetic");
    String right = GalleryIndex.burstStream(sd, "Synthetic");
    check(left.equals(GalleryIndex.burstStream(primarySaf, "Synthetic")), "same volume and folder across local providers");
    var entries = java.util.List.of(new BurstGroups.Entry(0, 1, left, "1.orf", 1, 1),
        new BurstGroups.Entry(1, 2000, right, "2.orf", 1, 2));
    check(BurstGroups.group(entries, 1).equals(java.util.List.of(java.util.List.of(0), java.util.List.of(1))),
        "numbered RAW files with the same relative folder on different volumes stay separate");
  }

  static byte[] jpegPixels() throws IOException {
    Bitmap bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(0xff486381);
    try (var output = new ByteArrayOutputStream()) {
      check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output), "encode fixture JPEG");
      return output.toByteArray();
    } finally {
      bitmap.recycle();
    }
  }

  private static byte[] numberedJpeg(byte[] pixels, int shot) {
    byte[] note = BurstMakerNoteFixture.makerNote(true, 3, 1, shot, 0, 0, 4, 0x49);
    return BurstMakerNoteFixture.jpeg(pixels, BurstMakerNoteFixture.tiff(note, false));
  }

  static String run(Context context) throws Exception {
    checkBurstVolumes();
    checkE2IndexMetadata(context);
    StringBuilder observations = new StringBuilder();
    for (int version : new int[] {0, 1, 2, 3}) observations.append(runVersion(context, version));
    return "PASS archive schema 1/2/3 migration and fresh create preserve records; JPEG burst cache NULL/retry/zero, "
        + "original key/model/taken and competing winner preserved; JPEG signature, segment bounds, short reads, zero skip and stop-before-pixels checked; I/O and scan-limit failures propagated; "
        + "bounded metadata transaction rollback, retry, competing winner and deleted row preserved; "
        + "E2 batch ID, states, missing rows, logical aliases, cancellation and revision retry; "
        + "storage volumes stay separate and E2 legacy aliases fall back; archive revision stable across WAL switch, metadata and checkpoint; "
        + "photo insert/replace/update/delete invalidate; rollback atomic; isolated DBs removed.\n" + observations;
  }
}
