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

  private static SourceSignature signature(Context context) throws Exception {
    Method method = GalleryIndex.class.getDeclaredMethod("signature", Context.class, int.class);
    method.setAccessible(true);
    String whole = (String) method.invoke(null, context, 1000);
    String moveMarker = ":move-v1:", archiveMarker = ":archive-v3:";
    int mediaEnd = whole.indexOf(moveMarker);
    int moveStart = mediaEnd + moveMarker.length();
    int moveEnd = whole.indexOf(':', moveStart);
    int archiveMarkerAt = whole.indexOf(archiveMarker, moveEnd);
    check(mediaEnd >= 0 && moveEnd > moveStart && archiveMarkerAt > moveEnd, "signature layout");
    int archiveStart = archiveMarkerAt + archiveMarker.length();
    int archiveEnd = whole.indexOf(':', archiveStart);
    int e2End = whole.indexOf(':', archiveEnd + 1);
    check(archiveEnd > archiveStart && e2End > archiveEnd + 1, "archive and e2 revisions present");
    String[] grants = whole.substring(e2End + 1).split(":", 5);
    check(grants.length == 5, "signature grants present");
    return new SourceSignature(whole, whole.substring(0, mediaEnd),
        Long.parseLong(whole.substring(moveStart, moveEnd)),
        Long.parseLong(whole.substring(archiveStart, archiveEnd)),
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
        SourceSignature before = signature(isolated);
        if (oldVersion == 3) checkBurstCache(isolated, root, store);
        checkStableSignature(before, signature(isolated), "burst metadata");
        ContentValues metadata = new ContentValues();
        metadata.put("key", "new-derived"); metadata.put("model", "synthetic"); metadata.put("taken", 123);
        check(db.insertOrThrow("metadata", null, metadata) >= 0, "metadata saved");
        check(store.indexRevision() == revision, "metadata does not invalidate source");
        checkStableSignature(before, signature(isolated), "metadata insert");
        JSONObject photo = new JSONObject().put("key", "new-photo").put("name", "synthetic");
        store.put(photo, "pending");
        check(store.indexRevision() > revision, "insert invalidates");
        SourceSignature afterInsert = signature(isolated);
        check(before.archiveRevision() != afterInsert.archiveRevision()
            && !before.whole().equals(afterInsert.whole()), "photo signature changes");
        revision = store.indexRevision();
        photo.put("name", "replacement"); store.put(photo, "verified");
        check(store.indexRevision() > revision, "replace invalidates");
        revision = store.indexRevision();
        db.execSQL("UPDATE photos SET state='updated' WHERE key='new-photo'");
        check(store.indexRevision() > revision, "update invalidates");
        revision = store.indexRevision();
        before = signature(isolated);
        db.beginTransaction();
        try { store.put(new JSONObject().put("key", "rolled-back"), "pending"); }
        finally { db.endTransaction(); }
        check(store.get("rolled-back") == null, "photo rolled back");
        check(store.indexRevision() == revision, "revision rolled back");
        checkStableSignature(before, signature(isolated), "rollback");
        db.delete("photos", "key=?", new String[] {"new-photo"});
        check(store.indexRevision() > revision, "delete invalidates");
        before = signature(isolated);
        store.setWriteAheadLoggingEnabled(true);
        check(db.isWriteAheadLoggingEnabled(), "WAL enabled for checkpoint fixture");
        SourceSignature afterWal = signature(isolated);
        String walChange = checkStableSignature(before, afterWal, "WAL switch");

        metadata.put("key", "wal-derived");
        db.insertOrThrow("metadata", null, metadata);
        SourceSignature afterMetadata = signature(isolated);
        String metadataChange = checkStableSignature(afterWal, afterMetadata, "WAL metadata");

        try (Cursor checkpoint = db.rawQuery("PRAGMA wal_checkpoint(FULL)", null)) {
          check(checkpoint.moveToFirst() && checkpoint.getInt(0) == 0 && checkpoint.getInt(1) >= 0,
              "WAL checkpoint completed");
        }
        SourceSignature afterCheckpoint = signature(isolated);
        String checkpointChange = checkStableSignature(afterMetadata, afterCheckpoint, "checkpoint");
        return "Archive v" + oldVersion + ": " + walChange + "; " + metadataChange
            + "; " + checkpointChange + "\n";
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
      store.probeBurstFrame(photo, key, stale);
      throw new AssertionError("missing local file must be retryable");
    } catch (IOException expected) {
      check(store.metadata(photo).burstFrame == null, "I/O failure retains NULL");
    }
    byte[] jpegPixels = jpegPixels();
    checkJpegProbeFailures(jpegPixels);
    Files.write(jpeg.toPath(), numberedJpeg(jpegPixels, 3));
    ArchiveStore.RawMetadata filled = store.probeBurstFrame(photo, key, stale);
    check(filled.burstFrame != null && filled.burstFrame.number() == 3, "JPEG EXIF MakerNote retry succeeds");
    check(filled.model.equals("Preserved model") && filled.taken == 987 && store.metadataSnapshot().containsKey(key),
        "burst update preserves existing key/model/taken");
    // A second caller observed the old NULL while the winner was reading its original.
    File alternate = new File(root, "alternate.jpg");
    Files.write(alternate.toPath(), numberedJpeg(jpegPixels, 8));
    var secondRead = new PhotoRepository.Photo(Uri.fromFile(alternate), alternate.getName(), "fixture/", 0, 1, 0);
    try (ArchiveStore concurrent = new ArchiveStore(context)) {
      check(concurrent.probeBurstFrame(secondRead, key, stale).burstFrame.equals(filled.burstFrame),
          "stale competing probe cannot replace the winner");
    }
    File single = new File(root, "single.jpg");
    Files.write(single.toPath(), jpegPixels);
    var plain = new PhotoRepository.Photo(Uri.fromFile(single), single.getName(), "fixture/", 0, 1, 0);
    var noFrame = store.probeBurstFrame(plain, ArchiveStore.key(plain), store.metadata(plain));
    check(noFrame.burstFrame.equals(RawPreview.BurstFrame.NONE), "no frame caches explicit zero");
    check(store.metadata(plain).burstFrame.equals(RawPreview.BurstFrame.NONE), "no frame persisted");
    File mislabeled = new File(root, "mislabeled.jpg");
    Files.write(mislabeled.toPath(), BurstMakerNoteFixture.tiff(
        BurstMakerNoteFixture.makerNote(true, 3, 1, 8, 0, 0, 4, 0x49), false));
    var wrongFormat = new PhotoRepository.Photo(Uri.fromFile(mislabeled), mislabeled.getName(), "fixture/", 0, 1, 0);
    check(store.probeBurstFrame(wrongFormat, ArchiveStore.key(wrongFormat), store.metadata(wrongFormat))
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
    StringBuilder observations = new StringBuilder();
    for (int version : new int[] {0, 1, 2, 3}) observations.append(runVersion(context, version));
    return "PASS archive schema 1/2/3 migration and fresh create preserve records; JPEG burst cache NULL/retry/zero, "
        + "original key/model/taken and competing winner preserved; JPEG signature, segment bounds, short reads, zero skip and stop-before-pixels checked; I/O and scan-limit failures propagated; "
        + "storage volumes stay separate and E2 legacy aliases fall back; archive revision stable across WAL switch, metadata and checkpoint; "
        + "photo insert/replace/update/delete invalidate; rollback atomic; isolated DBs removed.\n" + observations;
  }
}
