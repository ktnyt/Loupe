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
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ListView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
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
import jp.nano.rawgallery.camera.CameraImportGate;
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
    if ("photo-storage-inventory".equals(mode)) {
      PhotoStorage.requireWritable(base, secondary);
      try (Fixture fixture = new Fixture(base, secondary)) {
        checkInventory(fixture);
      }
      checkIndependentInventoryEvidence(base, secondary);
      try (Fixture fixture = new Fixture(base, secondary)) {
        checkLedgerInventoryEvidence(fixture);
      }
      return "PASS photo storage inventory: independent histories, stored sizes, cross-store evidence, exact locations, "
          + "exclusion evidence, unresolved record counts, "
          + "unavailable destination, import and move guards, viewer location resolution, and read-only data";
    }
    if (mode.startsWith("photo-storage-abandon-detach-")) return abandonDetached(base, mode, secondary);
    if (!"photo-storage".equals(mode)) return detach(base, mode, secondary);
    PhotoStorage.requireWritable(base, secondary);

    for (JournalFixture schema : JournalFixture.values()) checkJournalUpgrade(base, secondary, schema);
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
    int changedTargetVersions = 0;
    List<PhotoMove.Boundary> corruptionPoints = List.of(PhotoMove.Boundary.COPIED, PhotoMove.Boundary.VERIFIED,
        PhotoMove.Boundary.PUBLISHED, PhotoMove.Boundary.BEFORE_HISTORY_VERIFY, PhotoMove.Boundary.HISTORY_UPDATED,
        PhotoMove.Boundary.REMAPPED);
    for (PhotoMove.Boundary boundary : corruptionPoints) {
      try (Fixture fixture = new Fixture(base, secondary)) {
        if (corruptTargetAndResume(fixture, boundary)) changedTargetVersions++;
      }
    }
    try (Fixture fixture = new Fixture(base, secondary)) {
      rejectCorruptTargetOnHistoryResume(fixture);
    }
    for (PhotoMove.Boundary boundary : List.of(PhotoMove.Boundary.PUBLISHED, PhotoMove.Boundary.BEFORE_HISTORY_VERIFY,
        PhotoMove.Boundary.REMAPPED, PhotoMove.Boundary.BEFORE_SOURCE_DELETE)) {
      try (Fixture fixture = new Fixture(base, secondary)) {
        cancelAtPublishedBoundary(fixture, boundary);
      }
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
        + "USB mass-storage mtime/EXIF fast path, abandon with one or two verified copies, sequential SD identity snapshots, "
        + "v1 journal migration and rollback; target corruption, fresh history-stage verification on resume, "
        + "and cancellation before history or deletion; observed version changes on " + changedTargetVersions
        + "/" + corruptionPoints.size() + " same-size target writes";
  }

  static String performance(Instrumentation test, String secondaryArg, Bundle args) throws Exception {
    Context base = test.getTargetContext();
    require(TARGET_PACKAGE.equals(base.getPackageName()), "Photo storage benchmark requires the isolated APK");
    require(Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish"),
        "Photo storage benchmark requires an Android emulator");
    String secondary = secondaryArg == null ? secondaryVolume(base) : secondaryArg.toLowerCase(Locale.ROOT);
    require(!PRIMARY.equals(secondary), "Expected a secondary volume");
    int count = Integer.parseInt(args.getString("profile_count", "12"));
    int bytes = Integer.parseInt(args.getString("profile_bytes", "8388608"));
    require(count > 0 && count <= 1900 && bytes >= 1024 && bytes <= 16 * 1024 * 1024
        && (long) count * bytes <= 512L * 1024 * 1024, "Unsupported synthetic benchmark size");
    PhotoStorage.requireWritable(base, secondary);
    String runner = args.getString("profile_runner", "backend");
    require(runner.equals("backend") || runner.equals("service"), "Unsupported benchmark runner");
    if (runner.equals("service")) return servicePerformance(test, base, secondary, count, bytes);
    try (Fixture fixture = new Fixture(base, secondary);
         CameraImportLedger ledger = fixture.ledger(); HistoryDb history = fixture.history()) {
      List<PerformancePhoto> photos = seedPerformancePhotos(fixture, ledger, history, count, bytes);
      require(PhotoStorage.select(fixture.context, secondary), "Select benchmark SD destination");
      BoundaryTimes timings = new BoundaryTimes();
      String report;
      try (PhotoMove move = fixture.move(ledger, history, timings::record)) {
        long start = SystemClock.elapsedRealtimeNanos();
        PhotoMove.Plan plan = move.plan(secondary);
        long planned = SystemClock.elapsedRealtimeNanos();
        checkPerformancePlan(plan, photos, bytes);
        long beforeBegin = SystemClock.elapsedRealtimeNanos();
        move.begin(plan);
        long begun = SystemClock.elapsedRealtimeNanos();
        timings.previous = begun;
        PhotoMoveStore.Batch result = move.run(() -> false, batch -> {});
        long finished = SystemClock.elapsedRealtimeNanos();
        checkPerformanceResult(result, count);
        require(move.batch() == null, "The benchmark batch was durably closed");
        report = String.format(Locale.ROOT,
            "backend plan_ms=%.3f begin_ms=%.3f run_through_finish_ms=%.3f photos_per_second=%.3f%n",
            milliseconds(planned - start), milliseconds(begun - beforeBegin), milliseconds(finished - begun),
            count * 1e9 / (finished - begun));
        report += timings.report(count, finished);
      }
      verifyPerformancePhotos(fixture, ledger, history, photos, bytes);
      return String.format(Locale.ROOT, "PASS photo storage performance photos=%d bytes_per_photo=%d total_bytes=%d%n",
          count, bytes, (long) count * bytes) + report;
    }
  }

  private static String servicePerformance(Instrumentation test, Context base, String secondary, int count, int bytes)
      throws Exception {
    CameraImportLedger ledger = CameraImportLedger.get(base);
    HistoryDb history = HistoryDb.Companion.get(base);
    require(history.importDestinations().isEmpty() && ledger.contentCount() == 0 && ledger.receiptCount() == 0
        && ledger.pending().isEmpty(), "Service benchmark requires clean isolated import histories");
    try (PhotoMoveStore store = new PhotoMoveStore(base)) {
      require(store.batch() == null && !PhotoMoveService.state().running(), "Service benchmark requires an idle journal");
    }
    if (Build.VERSION.SDK_INT >= 33) {
      require(base.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
          == android.content.pm.PackageManager.PERMISSION_GRANTED
          && base.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO)
          == android.content.pm.PackageManager.PERMISSION_GRANTED, "Grant the isolated app full photo and video access");
    }
    SharedPreferences settings = base.getSharedPreferences("settings", 0);
    String previous = settings.getString("photo_volume", null);
    Fixture fixture = new Fixture(base, secondary);
    Set<String> fixtureSources = new HashSet<>();
    AtomicBoolean accepted = new AtomicBoolean();
    boolean completionObserved = false;
    try {
      List<PerformancePhoto> photos = seedPerformancePhotos(fixture, ledger, history, count, bytes);
      for (PerformancePhoto photo : photos) fixtureSources.add(photo.original.toString());
      require(PhotoStorage.select(base, secondary), "Select service benchmark SD destination");
      Activity gallery = test.startActivitySync(new Intent(base, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        PhotoStorageActivity screen = (PhotoStorageActivity) test.startActivitySync(
            new Intent(base, PhotoStorageActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
          UiAutomation automation = test.getUiAutomation();
          awaitNode(automation, node -> hasText(node, "取り込み済みの写真を移動"));
          GalleryIndex.WORKER.submit(() -> {}).get(120, TimeUnit.SECONDS);
          PhotoMove.Plan plan;
          long beginPlan;
          long endPlan;
          try (PhotoMove mover = new PhotoMove(base)) {
            beginPlan = SystemClock.elapsedRealtimeNanos();
            plan = mover.plan(secondary);
            endPlan = SystemClock.elapsedRealtimeNanos();
          }
          checkPerformancePlan(plan, photos, bytes);
          long[] launched = new long[1];
          test.runOnMainSync(() -> {
            launched[0] = SystemClock.elapsedRealtimeNanos();
            accepted.set(PhotoMoveService.launch(screen, plan));
          });
          require(accepted.get(), "Launch the foreground move service");
          PhotoMoveService.State completion = awaitServiceCompletion(15 * 60 * 1000);
          completionObserved = completion != null;
          long serviceFinished = SystemClock.elapsedRealtimeNanos();
          require(completionObserved, "Foreground move service exceeded the benchmark deadline");
          checkPerformanceResult(completion.batch(), count);
          String completed = "写真を" + count + "枚移動しました。";
          awaitNode(automation, node -> node.getText() != null && node.getText().toString().contains(completed));
          long screenFinished = SystemClock.elapsedRealtimeNanos();
          try (PhotoMoveStore store = new PhotoMoveStore(base)) {
            require(store.batch() == null, "The foreground service finished its durable journal");
          }
          verifyPerformancePhotos(fixture, ledger, history, photos, bytes);
          return String.format(Locale.ROOT,
              "PASS photo storage performance photos=%d bytes_per_photo=%d total_bytes=%d%n"
                  + "service plan_ms=%.3f launch_to_finished_state_ms=%.3f launch_to_observed_ui_completion_ms=%.3f"
                  + " photos_per_second=%.3f%n",
              count, bytes, (long) count * bytes, milliseconds(endPlan - beginPlan),
              milliseconds(serviceFinished - launched[0]), milliseconds(screenFinished - launched[0]),
              count * 1e9 / (serviceFinished - launched[0]));
        } finally {
          test.runOnMainSync(() -> {
            gallery.finish();
            screen.finish();
          });
          test.waitForIdleSync();
        }
      } finally {
        if (!gallery.isFinishing()) test.runOnMainSync(gallery::finish);
      }
    } finally {
      if (accepted.get() && !completionObserved && stopFixtureService(base, fixtureSources)) {
        require(awaitServiceCompletion(30_000) != null,
            "Benchmark service is still active; retain the fixture for safe cleanup");
      }
      // PhotoMoveService.launch uses this monitor. A new invocation must not race fixture cleanup.
      synchronized (PhotoMoveService.class) {
        require(!PhotoMoveService.state().running(), "An active service prevents safe fixture cleanup; retain the fixture");
        clearFixtureJournal(base, fixtureSources, accepted.get());
        cleanupServicePerformance(base, fixture, ledger, history, settings, previous);
      }
    }
  }

  private static PhotoMoveService.State awaitServiceCompletion(long timeoutMillis) {
    long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
    PhotoMoveService.State state = PhotoMoveService.state();
    while (state.running() && SystemClock.elapsedRealtime() < deadline) {
      SystemClock.sleep(10);
      state = PhotoMoveService.state();
    }
    return state.running() ? null : state;
  }

  private static boolean stopFixtureService(Context base, Set<String> fixtureSources) {
    synchronized (PhotoMoveService.class) {
      if (!PhotoMoveService.state().running()) return false;
      try (PhotoMoveStore store = new PhotoMoveStore(base)) {
        require(store.batch() != null && fixtureOwnsJournal(store.getReadableDatabase(), fixtureSources),
            "Cannot establish active service ownership; retain the fixture for safe cleanup");
        if (!PhotoMoveService.state().running()) return false;
        PhotoMoveService.stopMove();
        return true;
      }
    }
  }

  private static void clearFixtureJournal(Context base, Set<String> fixtureSources, boolean accepted) {
    try (PhotoMoveStore store = new PhotoMoveStore(base)) {
      SQLiteDatabase db = store.getWritableDatabase();
      db.beginTransaction();
      try {
        if (store.batch() != null) {
          require(accepted && fixtureOwnsJournal(db, fixtureSources),
              "Cannot establish journal ownership; retain the fixture for safe cleanup");
          db.delete("items", null, null);
          db.delete("batch", null, null);
        }
        db.setTransactionSuccessful();
      } finally {
        db.endTransaction();
      }
    }
  }

  private static boolean fixtureOwnsJournal(SQLiteDatabase db, Set<String> fixtureSources) {
    try (Cursor sources = db.rawQuery("SELECT source FROM items", null)) {
      if (fixtureSources.isEmpty() || sources.getCount() != fixtureSources.size()) return false;
      while (sources.moveToNext()) if (!fixtureSources.contains(sources.getString(0))) return false;
      return true;
    }
  }

  private static void cleanupServicePerformance(Context base, Fixture f, CameraImportLedger ledger, HistoryDb history,
      SharedPreferences settings, String previous) throws IOException {
    Cleanup cleanup = new Cleanup();
    Map<String, CameraImportLedger.Content> seeded = ledger.sourceSnapshot("wifi:" + f.id);
    cleanup.attempt(f::close);
    cleanup.attempt(() -> history.resetHistory(MODEL, f.id));
    for (CameraImportLedger.Content content : seeded.values()) cleanup.attempt(() -> ledger.forgetMissing(content.sourceSha));
    cleanup.attempt(() -> {
      try (PhotoMoveStore store = new PhotoMoveStore(base)) {
        for (String volume : List.of(PRIMARY, f.secondary)) {
          String prefix = PhotoIdentityIndex.path(MediaStore.Images.Media.getContentUri(volume), f.path, "");
          store.getWritableDatabase().delete("local_photos", "substr(location,1,?)=?",
              new String[]{Integer.toString(prefix.length()), prefix});
        }
      }
    });
    cleanup.attempt(() -> {
      SharedPreferences.Editor restore = settings.edit();
      if (previous == null) restore.remove("photo_volume");
      else restore.putString("photo_volume", previous);
      require(restore.commit(), "Restore original isolated app storage preference");
    });
    cleanup.finish();
  }

  private record PerformancePhoto(CameraImportFile source, Uri original, String sha) {}

  private static List<PerformancePhoto> seedPerformancePhotos(Fixture f, CameraImportLedger ledger, HistoryDb history,
      int count, int bytes) throws Exception {
    byte[] image = Arrays.copyOf(jpeg(0xff39546a), bytes);
    byte[] fixtureId = f.id.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    System.arraycopy(fixtureId, 0, image, bytes - fixtureId.length - Integer.BYTES, fixtureId.length);
    ByteBuffer sequence = ByteBuffer.wrap(image);
    List<PerformancePhoto> photos = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      sequence.putInt(bytes - Integer.BYTES, index);
      String sha = E2Archive.sha256(image);
      String name = String.format(Locale.ROOT, "BENCH-%05d.JPG", index);
      CameraImportFile source = f.source("wifi:", name, image);
      Uri original = f.insert(PRIMARY, name, image);
      ledger.adopt(source, sha, sha, bytes, original.toString(), "JPEG", ledger.captureGroup(source, sha, "JPEG"));
      history.recordImport(MODEL, f.id, new ImportedKey(name, bytes, CAPTURE), original.toString());
      photos.add(new PerformancePhoto(source, original, sha));
    }
    return photos;
  }

  private static void checkPerformancePlan(PhotoMove.Plan plan, List<PerformancePhoto> photos, int bytes) {
    require(plan.skipped() == 0 && plan.items().size() == photos.size(), "The plan contains every synthetic photograph");
    Map<String, String> originals = new HashMap<>();
    for (PerformancePhoto photo : photos) originals.put(photo.original.toString(), photo.sha);
    for (PhotoMoveStore.Item item : plan.items()) {
      String sha = originals.remove(item.source());
      require(sha != null && sha.equals(item.expectedSha()) && item.bytes() == bytes && !item.legacyIds().isEmpty(),
          "The plan is limited to exact fixture originals and their import evidence");
    }
  }

  private static void checkPerformanceResult(PhotoMoveStore.Batch result, int count) {
    require(result != null && result.done() == count && result.total() == count && result.skipped() == 0,
        "All benchmark photographs completed");
  }

  private static void verifyPerformancePhotos(Fixture f, CameraImportLedger ledger, HistoryDb history,
      List<PerformancePhoto> photos, int bytes) throws Exception {
    List<Row> rows = f.rows(f.secondary);
    require(f.rows(PRIMARY).isEmpty() && rows.size() == photos.size()
        && rows.stream().noneMatch(row -> row.pending || row.trashed),
        "The batch left only published replacements and removed the originals");
    Set<String> destinations = new HashSet<>();
    for (Row row : rows) destinations.add(row.uri.toString());
    Map<String, String> legacy = new HashMap<>();
    for (CameraImportHistoryPlan.Entry entry : history.photoImportRecords(MODEL, f.id)) legacy.put(entry.key, entry.uri);
    require(legacy.size() == photos.size(), "Legacy import history count is preserved");
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] buffer = new byte[64 * 1024];
    for (PerformancePhoto photo : photos) {
      CameraImportLedger.Content receipt = ledger.known(photo.source);
      String key = new ImportedKey(photo.source.name, bytes, CAPTURE).asString();
      require(receipt != null && receipt.sourceSha.equals(photo.sha) && receipt.storedSha.equals(photo.sha)
          && receipt.sourceBytes == bytes && receipt.storedBytes == bytes
          && destinations.remove(receipt.uri) && receipt.uri.equals(legacy.get(key)),
          "Both import histories identify the exact replacement");
      try (Cursor original = f.resolver.query(photo.original, new String[]{_ID}, null, null, null)) {
        require(original != null && !original.moveToFirst(), "The exact original URI is absent");
      }
      try (InputStream input = f.resolver.openInputStream(MediaStore.setRequireOriginal(Uri.parse(receipt.uri)))) {
        require(input != null, "The replacement is readable");
        long length = 0;
        for (int n; (n = input.read(buffer)) != -1; ) {
          length += n;
          digest.update(buffer, 0, n);
        }
        require(length == bytes && photo.sha.equals(String.format(Locale.ROOT, "%064x", new java.math.BigInteger(1, digest.digest()))),
            "The published replacement preserves the full source bytes");
      }
    }
  }

  private static double milliseconds(long nanoseconds) { return nanoseconds / 1e6; }

  private static final class BoundaryTimes {
    private long previous;
    private final EnumMap<PhotoMove.Boundary, Long> elapsed = new EnumMap<>(PhotoMove.Boundary.class);
    private final EnumMap<PhotoMove.Boundary, Integer> samples = new EnumMap<>(PhotoMove.Boundary.class);

    void record(PhotoMove.Boundary boundary) {
      long now = SystemClock.elapsedRealtimeNanos();
      elapsed.merge(boundary, now - previous, Long::sum);
      samples.merge(boundary, 1, Integer::sum);
      previous = now;
    }

    String report(int count, long finished) {
      StringBuilder result = new StringBuilder();
      for (PhotoMove.Boundary boundary : PhotoMove.Boundary.values()) {
        int events = samples.getOrDefault(boundary, 0);
        require(events == count, "The fresh batch reaches each boundary once");
        result.append(String.format(Locale.ROOT, "until_%s total_ms=%.3f mean_ms=%.3f samples=%d%n", boundary,
            milliseconds(elapsed.get(boundary)), milliseconds(elapsed.get(boundary)) / events, events));
      }
      result.append(String.format(Locale.ROOT, "after_DELETED_through_finish_ms=%.3f%n", milliseconds(finished - previous)));
      return result.toString();
    }
  }

  private enum JournalFixture { EARLY_QUEUED, LATER_CLAIMS, MISSING_ACTIVE_IDENTITY, EARLIEST_ALL_DONE }

  private static void checkJournalUpgrade(Context base, String secondary, JournalFixture schema) throws Exception {
    boolean withClaims = schema == JournalFixture.LATER_CLAIMS;
    boolean brokenIdentity = schema == JournalFixture.MISSING_ACTIVE_IDENTITY;
    boolean allDone = schema == JournalFixture.EARLIEST_ALL_DONE;
    ScopedContext fixture = new ScopedContext(base, UUID.randomUUID().toString());
    String source = "content://media/external_primary/images/media/501";
    String sourceAlias = "content://media/external/images/media/501";
    String targetUri = "content://media/" + secondary + "/file/802";
    String targetLocation = targetUri + "\n42:12:8:0";
    String archiveKey = PhotoRepository.digest("Move before upgrade");
    String tagSource = "content://media/external/file/802\n42:12:8:0";
    String claimUri = "content://media/" + secondary + "/file/803";
    String claimLocation = claimUri + "\n43:14:11:0";
    String claimKey = "Pictures/CameraSync/Synthetic/CLAIM.JPG:43:14";
    String version = "SYNTHETIC.JPG\nPictures/CameraSync/Synthetic/\nimage/jpeg\n42\n12\n8\n0\n1\n1";
    String checksum = PhotoRepository.digest("Synthetic camera file");
    int stage = switch (schema) {
      case MISSING_ACTIVE_IDENTITY -> PhotoMoveStore.UPDATING_HISTORY;
      case EARLIEST_ALL_DONE -> PhotoMoveStore.DONE;
      default -> PhotoMoveStore.QUEUED;
    };
    String movedTarget = "content://media/" + secondary + "/images/media/802";

    try (AutoCloseable clean = () -> {
      Cleanup cleanup = new Cleanup();
      fixture.clean(cleanup);
      cleanup.finish();
    }) {
      try (SQLiteDatabase old = fixture.openOrCreateDatabase("photo-moves.db", 0, null)) {
        old.execSQL("CREATE TABLE batch (id INTEGER PRIMARY KEY CHECK(id=1), volume TEXT NOT NULL, total INTEGER NOT NULL, skipped INTEGER NOT NULL)");
        old.execSQL("CREATE TABLE items (source TEXT PRIMARY KEY, source_alias TEXT, version TEXT NOT NULL, expected_sha TEXT, "
            + (allDone ? "" : "legacy_ids TEXT NOT NULL, ")
            + "bytes INTEGER NOT NULL, token TEXT, target TEXT, sha TEXT, stage INTEGER NOT NULL DEFAULT 0"
            + (withClaims ? ", archive_key TEXT, tag_source TEXT, sort_time INTEGER)" : ")"));
        old.execSQL("CREATE INDEX items_stage ON items(stage)");
        old.execSQL("CREATE TABLE identities (location TEXT PRIMARY KEY, archive_key TEXT NOT NULL, tag_source TEXT NOT NULL, sort_time INTEGER NOT NULL)");
        old.execSQL("CREATE TABLE revision (id INTEGER PRIMARY KEY CHECK(id=1), value INTEGER NOT NULL)");
        old.execSQL("INSERT INTO batch VALUES(1,?,1,2)", new Object[]{secondary});
        ContentValues item = new ContentValues();
        item.put("source", source);
        item.put("source_alias", sourceAlias);
        item.put("version", version);
        item.put("expected_sha", checksum);
        item.put("bytes", 42);
        item.put("stage", stage);
        if (!allDone) item.put("legacy_ids", "9,10");
        if (stage != PhotoMoveStore.QUEUED) {
          item.put("token", "__loupe_move_test.JPG");
          item.put("target", movedTarget);
          item.put("sha", checksum);
        }
        old.insertOrThrow("items", null, item);
        old.execSQL("INSERT INTO identities VALUES(?,?,?,?)", new Object[]{targetLocation, archiveKey, tagSource, 12000});
        old.execSQL("INSERT INTO revision VALUES(1,12)");
        if (withClaims) {
          old.execSQL("CREATE TABLE archive_claims (legacy_identity TEXT PRIMARY KEY, location TEXT NOT NULL)");
          old.execSQL("INSERT INTO archive_claims VALUES(?,?)", new Object[]{claimKey, claimLocation});
          old.execSQL("INSERT INTO archive_claims VALUES(?,?)", new Object[]{"overlapping claim", targetLocation});
        }
        old.setVersion(1);
      }

      if (brokenIdentity) {
        try (PhotoMoveStore store = new PhotoMoveStore(fixture)) {
          try {
            store.getWritableDatabase();
            throw new CheckFailure("Upgrade accepted an active v1 move with no recoverable identity");
          } catch (IllegalStateException expected) {
            require(expected.getMessage().contains("cannot recover the recorded identity"), "Reject the missing active identity");
          }
        }
        try (SQLiteDatabase old = fixture.openOrCreateDatabase("photo-moves.db", 0, null);
             Cursor item = old.rawQuery("SELECT * FROM items", null)) {
          require(old.getVersion() == 1 && item.moveToFirst() && item.getCount() == 1
              && item.getColumnIndex("archive_key") == -1 && stage == item.getInt(item.getColumnIndexOrThrow("stage"))
              && source.equals(item.getString(item.getColumnIndexOrThrow("source")))
              && journalTable(old, "identities") && !journalTable(old, "local_photos"),
              "Failed upgrade rolls back schema changes and keeps old journal rows");
        }
        return;
      }

      try (PhotoMoveStore store = new PhotoMoveStore(fixture)) {
        SQLiteDatabase updated = store.getWritableDatabase();
        require(updated.getVersion() == 2
            && new PhotoMoveStore.Batch(secondary, 1, 2, allDone ? 1 : 0).equals(store.batch()) && store.revision() == 13,
            "v1 upgrade preserves batch counts and advances the revision");
        if (allDone) {
          try (Cursor completed = updated.rawQuery("SELECT source,target,stage,legacy_ids FROM items", null)) {
            require(store.next() == null && completed.moveToFirst() && completed.getCount() == 1
                && source.equals(completed.getString(0)) && movedTarget.equals(completed.getString(1))
                && completed.getInt(2) == PhotoMoveStore.DONE && completed.getString(3).isEmpty(),
                "Earliest v1 keeps a completed row without inventing legacy evidence or requeuing it");
          }
        } else {
          PhotoMoveStore.Item expected = new PhotoMoveStore.Item(source, sourceAlias, version, checksum, "9,10", 42,
              null, null, null, PhotoMoveStore.QUEUED, null);
          require(expected.equals(store.next()), "v1 upgrade preserves the complete queued item");
        }
        require(!journalTable(updated, "archive_claims") && !journalTable(updated, "identities"),
            "Successful upgrade removes superseded legacy tables");
        try (Cursor moved = updated.rawQuery("SELECT archive_key,tag_source,sort_time,media_version,move_uri "
            + "FROM local_photos WHERE location=?", new String[]{targetLocation})) {
          require(moved.moveToFirst() && moved.getCount() == 1 && archiveKey.equals(moved.getString(0))
              && tagSource.equals(moved.getString(1)) && moved.getLong(2) == 12000
              && moved.getString(3).isEmpty() && targetUri.equals(moved.getString(4)),
              "Moved v1 gallery URI, rank/share key, tag identity and order time survive");
        }
        if (withClaims) {
          try (Cursor claim = updated.rawQuery("SELECT archive_key,tag_source,move_uri FROM local_photos WHERE location=?",
              new String[]{claimLocation})) {
            require(claim.moveToFirst() && PhotoRepository.digest(claimKey).equals(claim.getString(0))
                && "content://media/external/file/803\n43:14:11:0".equals(claim.getString(1)) && claim.isNull(2),
                "Later v1 archive claims migrate without replacing explicit moved aliases");
          }
        }
        if (allDone) {
          store.finish();
          try (Cursor remaining = updated.rawQuery("SELECT archive_key FROM local_photos WHERE location=?",
              new String[]{targetLocation})) {
            require(store.batch() == null && store.next() == null && remaining.moveToFirst()
                && archiveKey.equals(remaining.getString(0)),
                "Finish closes the migrated all-DONE batch and retains its moved photo identity");
          }
        } else {
          PhotoMoveStore.Identity identity = new PhotoMoveStore.Identity(archiveKey, tagSource, 12000);
          store.updatingHistory(store.next(), identity);
          require(store.historyUpdatePending() && identity.equals(store.next().identity()),
              "An upgraded queue can persist an active photo identity in the new columns");
        }
      }
    }
  }

  private static boolean journalTable(SQLiteDatabase database, String name) {
    try (Cursor tables = database.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", new String[]{name})) {
      return tables.moveToFirst();
    }
  }

  private static void moveImportedPhotos(Fixture f) throws Exception {
    byte[] original = jpeg(0xff445599);
    byte[] other = jpeg(0xff996644);
    byte[] legacyBytes = jpegWithCaptureTime(f, 0xff449955, CAPTURE, CAPTURE + 1);
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

      Uri legacy = f.insertDatedLegacyPhoto("LEGACY.JPG", legacyBytes);
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
        var beforeInventory = move.inventory(f.secondary);
        require(beforeInventory.photos().size() == 2 && beforeInventory.unresolved().stream()
            .mapToInt(PhotoStorageInventory.History::count).sum() == 1,
            "Read-only inventory deduplicates ledger and legacy aggregate evidence and does not include unrelated photos");
        requireInventoryPhoto(beforeInventory, first.uri, source.name, f.path, PhotoStorageInventory.Status.ELIGIBLE);
        requireInventoryPhoto(beforeInventory, legacy, legacyKey.getFileName(), f.path, PhotoStorageInventory.Status.ELIGIBLE);
        require(Arrays.equals(original, f.read(first.uri)) && Arrays.equals(legacyBytes, f.read(legacy))
            && before.uri.equals(ledger.known(source).uri) && ledger.receiptCount() == 2
            && history.photoImportRecords(MODEL, f.id).stream().anyMatch(entry -> entry.key.equals(imported.asString())
                && entry.uri.equals(legacyAlias) && entry.deleted)
            && tags.load().value(tag.id, tagId) == TagJudgments.Value.ASSIGNED
            && ranking.singleBest(photoKey) && shares.history(photoKey).size() == 1 && move.batch() == null,
            "Listing preserves original bytes, legacy deletion evidence, ledger, tag, rank, share and move state");
        PhotoMove.Plan plan = move.plan(f.secondary);
        require(plan.items().size() == 2 && plan.skipped() == 1,
            "Select the imported and legacy items, skipping missing history (eligible=" + plan.items().size()
                + ", skipped=" + plan.skipped() + ")");
        move.begin(plan);
        PhotoMoveStore.Batch result = move.run(() -> false, batch -> {});
        require(result.done() == 2 && result.total() == 2 && result.skipped() == 1 && move.batch() == null,
            "Both photos complete and the batch closes");
      }

      CameraImportLedger.Content after = ledger.known(source);
      Uri moved = Uri.parse(after.uri);
      require(!first.uri.equals(moved) && f.secondary.equals(PhotoStorage.volumeOf(moved)), "Receipt points to the actual SD photo");
      try (PhotoMove move = f.move(ledger, history, boundary -> {})) {
        var movedInventory = move.inventory(f.secondary);
        require(movedInventory.photos().size() == 2 && movedInventory.photos().stream()
            .allMatch(photo -> photo.status() == PhotoStorageInventory.Status.ALREADY_THERE
                && f.secondary.equals(photo.location().volume()))
            && movedInventory.unresolved().stream().mapToInt(PhotoStorageInventory.History::count).sum() == 1,
            "Completed move remains listed at the actual card while the missing history is still unresolved");
        requireInventoryPhoto(movedInventory, moved, source.name, f.path, PhotoStorageInventory.Status.ALREADY_THERE);
      }
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

  private static boolean corruptTargetAndResume(Fixture f, PhotoMove.Boundary boundary) throws Exception {
    byte[] bytes = jpeg(0xff665588);
    CameraImportFile source = f.source("wifi:", "CORRUPT.JPG", bytes);
    try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history()) {
      Uri original = prepareBoundaryPhoto(f, ledger, history, source, bytes);
      Uri[] target = new Uri[1];
      AtomicBoolean versionChanged = new AtomicBoolean();
      try (PhotoMove move = f.move(ledger, history, reached -> {
        if (reached != boundary) return;
        try {
          target[0] = onlyTarget(f).uri;
          versionChanged.set(changeTargetBytes(f, target[0], bytes));
        } catch (IOException error) {
          throw new UncheckedIOException(error);
        }
      })) {
        move.begin(move.plan(f.secondary));
        expectMoveFailure(move, "Move accepted target byte corruption at " + boundary);
        require(target[0] != null && move.batch().done() == 0 && Arrays.equals(bytes, f.read(original)),
            "A same-size target mutation keeps the original at " + boundary);
        boolean historyMoved = boundary == PhotoMove.Boundary.HISTORY_UPDATED || boundary == PhotoMove.Boundary.REMAPPED;
        requireHistoryAt(ledger, history, source, historyMoved ? target[0] : original);
      }
      if (boundary == PhotoMove.Boundary.PUBLISHED || boundary == PhotoMove.Boundary.BEFORE_HISTORY_VERIFY) {
        try (PhotoMoveStore journal = new PhotoMoveStore(f.context)) {
          require(journal.next().stage() == PhotoMoveStore.UPDATING_HISTORY,
              "Published corruption stops in the durable history stage before either history is relocated");
        }
      }
      try (PhotoMove resumed = f.move(ledger, history, reached -> {})) {
        if (boundary != PhotoMove.Boundary.COPIED) {
          expectMoveFailure(resumed, "Resume accepted a corrupt verified target at " + boundary);
          require(Arrays.equals(bytes, f.read(original)), "Retry kept the only intact original at " + boundary);
          f.requireOwned(onlyTarget(f));
          f.write(target[0], bytes);
        }
        verifyBoundaryRecovery(f, ledger, history, resumed, source, bytes);
      }
      return versionChanged.get();
    }
  }

  private static void rejectCorruptTargetOnHistoryResume(Fixture f) throws Exception {
    byte[] bytes = jpeg(0xff224466);
    CameraImportFile source = f.source("wifi:", "HISTORY-RESUME.JPG", bytes);
    try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history()) {
      Uri original = prepareBoundaryPhoto(f, ledger, history, source, bytes);
      try (PhotoMove move = f.move(ledger, history, reached -> {
        if (reached == PhotoMove.Boundary.HISTORY_UPDATED) throw new InterruptedAtBoundary();
      })) {
        move.begin(move.plan(f.secondary));
        try {
          move.run(() -> false, batch -> {});
          throw new CheckFailure("Expected interruption while the history stage was still pending");
        } catch (InterruptedAtBoundary expected) {
          require(move.batch().done() == 0, "Interrupted history update is not completed");
        }
      }
      try (PhotoMoveStore store = new PhotoMoveStore(f.context)) {
        require(store.next().stage() == PhotoMoveStore.UPDATING_HISTORY, "Reopen at the durable history stage");
      }
      Uri target = onlyTarget(f).uri;
      changeTargetBytes(f, target, bytes);
      AtomicBoolean reachedVerification = new AtomicBoolean();
      try (PhotoMove resumed = f.move(ledger, history, reached -> {
        require(reached == PhotoMove.Boundary.BEFORE_HISTORY_VERIFY,
            "Corrupt history-stage target reached a later boundary on restart");
        reachedVerification.set(true);
      })) {
        expectMoveFailure(resumed, "Resumed history accepted a corrupt target");
        require(reachedVerification.get() && Arrays.equals(bytes, f.read(original)),
            "History-stage restart rejects the target and preserves the intact original");
      }
      f.requireOwned(onlyTarget(f));
      f.write(target, bytes);
      try (PhotoMove resumed = f.move(ledger, history, reached -> {})) {
        verifyBoundaryRecovery(f, ledger, history, resumed, source, bytes);
      }
    }
  }

  private static void cancelAtPublishedBoundary(Fixture f, PhotoMove.Boundary boundary) throws Exception {
    byte[] bytes = jpeg(0xff446688);
    CameraImportFile source = f.source("wifi:", "CANCEL-BOUNDARY.JPG", bytes);
    try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history()) {
      Uri original = prepareBoundaryPhoto(f, ledger, history, source, bytes);
      AtomicBoolean stopped = new AtomicBoolean();
      try (PhotoMove move = f.move(ledger, history, reached -> {
        if (reached == boundary) stopped.set(true);
      })) {
        move.begin(move.plan(f.secondary));
        try {
          move.run(stopped::get, batch -> {});
          throw new CheckFailure("Move ignored cancellation at " + boundary);
        } catch (InterruptedIOException expected) {
          Row target = onlyTarget(f);
          require(stopped.get() && move.batch().done() == 0 && !target.pending && !target.trashed
              && Arrays.equals(bytes, f.read(target.uri)) && Arrays.equals(bytes, f.read(original)),
              "Cancellation preserves both copies at " + boundary);
          boolean historyMoved = boundary == PhotoMove.Boundary.REMAPPED || boundary == PhotoMove.Boundary.BEFORE_SOURCE_DELETE;
          requireHistoryAt(ledger, history, source, historyMoved ? target.uri : original);
        }
      }
      try (PhotoMove resumed = f.move(ledger, history, reached -> {})) {
        verifyBoundaryRecovery(f, ledger, history, resumed, source, bytes);
      }
    }
  }

  private static Uri prepareBoundaryPhoto(Fixture f, CameraImportLedger ledger, HistoryDb history,
      CameraImportFile source, byte[] bytes) throws IOException {
    Uri original = f.importPhoto(new CameraPhotoImporter(f.context, ledger), source, bytes).uri;
    history.recordImport(MODEL, f.id, new ImportedKey(source.name, source.bytes, source.captureSeconds), original.toString());
    require(PhotoStorage.select(f.context, f.secondary), "Set boundary test SD destination");
    return original;
  }

  private static Row onlyTarget(Fixture f) throws IOException {
    List<Row> targets = f.rows(f.secondary);
    require(targets.size() == 1, "Boundary test has one target");
    return targets.get(0);
  }

  private static boolean changeTargetBytes(Fixture f, Uri target, byte[] original) throws IOException {
    f.requireOwned(onlyTarget(f));
    require(Arrays.equals(original, f.read(target)), "Target starts with all original bytes");
    List<String> before = targetVersion(f, target);
    byte[] changed = original.clone();
    changed[changed.length / 2] ^= 1;
    f.write(target, changed);
    require(Arrays.equals(changed, f.read(target)), "Observed exact same-size byte corruption");
    return !before.equals(targetVersion(f, target));
  }

  private static List<String> targetVersion(Fixture f, Uri target) {
    Bundle visibility = new Bundle();
    visibility.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
    String[] columns = {DISPLAY_NAME, RELATIVE_PATH, MIME_TYPE, SIZE, DATE_MODIFIED, DATE_ADDED, DATE_TAKEN,
        GENERATION_ADDED, GENERATION_MODIFIED};
    try (Cursor row = f.resolver.query(target, columns, visibility, null)) {
      require(row != null && row.moveToFirst() && row.getCount() == 1, "Read exact target version during mutation");
      List<String> values = new ArrayList<>(columns.length);
      for (int index = 0; index < columns.length; index++) values.add(row.getString(index));
      return values;
    }
  }

  private static void requireHistoryAt(CameraImportLedger ledger, HistoryDb history, CameraImportFile source, Uri expected) {
    require(expected.toString().equals(ledger.known(source).uri)
        && history.importDestinations().equals(Set.of(expected.toString())), "Both histories point to the expected copy");
  }

  private static void verifyBoundaryRecovery(Fixture f, CameraImportLedger ledger, HistoryDb history,
      PhotoMove resumed, CameraImportFile source, byte[] bytes) throws IOException {
    require(resumed.run(() -> false, batch -> {}).done() == 1 && resumed.batch() == null,
        "Retry completes and clears the journal");
    Row target = onlyTarget(f);
    require(f.rows(PRIMARY).isEmpty() && !target.pending && !target.trashed && Arrays.equals(bytes, f.read(target.uri)),
        "Retry leaves exactly one intact published replacement");
    requireHistoryAt(ledger, history, source, target.uri);
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

  private static void checkInventory(Fixture f) throws Exception {
    byte[] dated = jpegWithCaptureTime(f, 0xff335555, CAPTURE, CAPTURE + 1);
    byte[] undated = jpeg(0xff444433);
    Uri primary = f.insertDatedLegacyPhoto("PRIMARY.JPG", dated);
    Uri sd = f.insert(f.secondary, "ON-SD.JPG", dated);
    require(f.awaitCaptureTime(sd, CAPTURE * 1000) == CAPTURE * 1000, "Fixture card photo has the recorded capture time");
    Uri name = f.insertDatedLegacyPhoto("NEW-NAME.JPG", dated);
    Uri size = f.insertDatedLegacyPhoto("SIZE.JPG", dated);
    Uri absentDate = f.insert(PRIMARY, "NO-DATE.JPG", undated, 0);
    require(f.awaitCaptureTime(absentDate, 0) == 0, "Fixture has no recorded MediaStore capture time");
    Uri date = f.insertDatedLegacyPhoto("OTHER-DATE.JPG", dated);
    Uri pending = f.insertPending(PRIMARY, "PENDING.JPG", dated, CAPTURE * 1000);
    Uri trashed = f.insertDatedLegacyPhoto("TRASH.JPG", dated);
    f.trashOwned(f.row(trashed));
    Uri missingCard = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MISSING), Long.MAX_VALUE);
    Uri unknownAggregate = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Long.MAX_VALUE);
    try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         PhotoMove move = f.move(ledger, history, boundary -> {})) {
      recordFixtureHistory(history, f, "PRIMARY.JPG", dated.length, CAPTURE, primary.toString());
      recordFixtureHistory(history, f, "ON-SD.JPG", dated.length, CAPTURE, sd.toString());
      recordFixtureHistory(history, f, "OLD-NAME.JPG", dated.length, CAPTURE, name.toString());
      recordFixtureHistory(history, f, "SIZE.JPG", dated.length + 16, CAPTURE, size.toString());
      recordFixtureHistory(history, f, "NO-DATE.JPG", undated.length, CAPTURE, absentDate.toString());
      recordFixtureHistory(history, f, "OTHER-DATE.JPG", dated.length, CAPTURE + 60, date.toString());
      recordFixtureHistory(history, f, "PENDING.JPG", dated.length, CAPTURE, pending.toString());
      recordFixtureHistory(history, f, "TRASH.JPG", dated.length, CAPTURE, trashed.toString());
      recordFixtureHistory(history, f, "DETACHED.JPG", dated.length, CAPTURE, missingCard.toString());
      recordFixtureHistory(history, f, "DETACHED.JPG", dated.length + 16, CAPTURE, missingCard.toString());
      recordFixtureHistory(history, f, "UNKNOWN.JPG", dated.length, CAPTURE, unknownAggregate.toString());
      recordFixtureHistory(history, f, "NO-URI.JPG", dated.length, CAPTURE, "existing");
      List<HistoryDb.MoveEvidence> oldEvidence = history.moveEvidence();
      List<Row> primaryRows = f.rows(PRIMARY), cardRows = f.rows(f.secondary);
      require(PhotoStorage.select(f.context, f.secondary), "Set the synthetic inventory destination");
      PhotoStorageInventory inventory = move.inventory(f.secondary);
      require(inventory.photos().size() == 8, "Unresolved legacy records are not counted as located photographs");
      requireInventoryPhoto(inventory, primary, "PRIMARY.JPG", f.path, PhotoStorageInventory.Status.ELIGIBLE);
      requireInventoryPhoto(inventory, sd, "ON-SD.JPG", f.path, PhotoStorageInventory.Status.ALREADY_THERE);
      require(requireInventoryPhoto(inventory, name, "NEW-NAME.JPG", f.path, PhotoStorageInventory.Status.EXCLUDED,
              PhotoStorageInventory.Reason.HISTORY_NAME).recordedName().equals("OLD-NAME.JPG"),
          "Show the current file name separately from the historical mismatch");
      requireInventoryPhoto(inventory, size, "SIZE.JPG", f.path, PhotoStorageInventory.Status.EXCLUDED,
          PhotoStorageInventory.Reason.HISTORY_SIZE);
      requireInventoryPhoto(inventory, absentDate, "NO-DATE.JPG", f.path, PhotoStorageInventory.Status.EXCLUDED,
          PhotoStorageInventory.Reason.HISTORY_CAPTURE_MISSING);
      requireInventoryPhoto(inventory, date, "OTHER-DATE.JPG", f.path, PhotoStorageInventory.Status.EXCLUDED,
          PhotoStorageInventory.Reason.HISTORY_CAPTURE_DIFFERS);
      requireInventoryPhoto(inventory, pending, "PENDING.JPG", f.path, PhotoStorageInventory.Status.EXCLUDED,
          PhotoStorageInventory.Reason.PENDING);
      requireInventoryPhoto(inventory, trashed, "TRASH.JPG", f.path, PhotoStorageInventory.Status.EXCLUDED,
          PhotoStorageInventory.Reason.TRASHED);
      require(inventory.unresolved().stream().filter(row -> row.name().equals("DETACHED.JPG")
              && row.reason() == PhotoStorageInventory.Reason.MISSING_REFERENCE
              && row.location().kind() == PhotoStorageLocation.Kind.RECORDED && MISSING.equals(row.location().volume())
              && row.location().availability() == PhotoStorageLocation.Availability.UNAVAILABLE)
          .mapToInt(PhotoStorageInventory.History::count).sum() == 2,
          "A detached card keeps the recorded location and counts both history records");
      require(inventory.unresolved().stream().anyMatch(row -> row.name().equals("UNKNOWN.JPG")
              && row.location().kind() == PhotoStorageLocation.Kind.UNKNOWN && row.location().volume() == null)
          && inventory.unresolved().stream().anyMatch(row -> row.name().equals("NO-URI.JPG")
              && row.reason() == PhotoStorageInventory.Reason.NO_FILE_REFERENCE),
          "An unresolved aggregate URI never implies internal storage, and a missing URI has a distinct reason");
      PhotoStorageInventory withDetachedTarget = move.inventory(MISSING);
      requireInventoryPhoto(withDetachedTarget, primary, "PRIMARY.JPG", f.path, PhotoStorageInventory.Status.EXCLUDED,
          PhotoStorageInventory.Reason.DESTINATION_UNAVAILABLE);
      requireInventoryPhoto(withDetachedTarget, sd, "ON-SD.JPG", f.path, PhotoStorageInventory.Status.EXCLUDED,
          PhotoStorageInventory.Reason.DESTINATION_UNAVAILABLE);
      require(f.secondary.equals(PhotoStorage.selected(f.context)), "Read-only inventory does not update the save destination");
      PhotoMove.Plan plan = move.plan(f.secondary);
      require(plan.items().size() == 1 && plan.items().get(0).source().equals(primary.toString()) && plan.skipped() == 9,
          "The actual move plan retains its prior eligible and skipped reference semantics");
      assertInventoryGuards(move, f.secondary, plan);
      checkLocationResolution(f, primary, sd, missingCard, unknownAggregate, dated.length);
      require(history.moveEvidence().equals(oldEvidence) && ledger.receiptCount() == 0 && ledger.contentCount() == 0
          && move.batch() == null && new HashSet<>(f.rows(PRIMARY)).equals(new HashSet<>(primaryRows))
          && new HashSet<>(f.rows(f.secondary)).equals(new HashSet<>(cardRows)), "Listing does not change any file or history row");
      for (Uri uri : List.of(primary, sd, name, size, date, pending)) {
        require(Arrays.equals(dated, f.read(uri)), "Inventory preserves every selected synthetic original");
      }
      require(Arrays.equals(undated, f.read(absentDate)), "The undated original remains unchanged");
    } finally {
      f.setTrashed(f.row(trashed), false);
    }
  }

  private static void checkIndependentInventoryEvidence(Context base, String secondary) throws Exception {
    try (Fixture f = new Fixture(base, secondary);
         Fixture outside = new Fixture(base, secondary, UUID.randomUUID().toString(), "Pictures/");
         CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         PhotoMove move = f.move(ledger, history, boundary -> {})) {
      byte[] bytes = jpegWithCaptureTime(f, 0xff326364, CAPTURE, CAPTURE + 1);
      Uri renamed = outside.insertDatedLegacyPhoto("NEW-OUTSIDE.JPG", bytes);
      Uri matched = f.insertDatedLegacyPhoto("MATCHED.JPG", bytes);
      recordFixtureHistory(history, f, "OLD-OUTSIDE.JPG", bytes.length, CAPTURE, renamed.toString());
      recordFixtureHistory(history, f, "MATCHED.JPG", bytes.length, CAPTURE, matched.toString());
      recordFixtureHistory(history, f, "OLD-OTHER.JPG", bytes.length, CAPTURE, matched.toString());
      List<HistoryDb.MoveEvidence> before = history.moveEvidence();
      PhotoStorageInventory listing = move.inventory(secondary);
      require(listing.photos().size() == 2 && listing.unresolved().stream().mapToInt(PhotoStorageInventory.History::count).sum() == 2,
          "Keep two physical references and two independently unresolved historical records");
      var outsidePhoto = requireInventoryPhoto(listing, renamed, "NEW-OUTSIDE.JPG", outside.path,
          PhotoStorageInventory.Status.EXCLUDED, PhotoStorageInventory.Reason.UNSUPPORTED_FOLDER,
          PhotoStorageInventory.Reason.HISTORY_NAME);
      require(!outsidePhoto.historyMatches() && "OLD-OUTSIDE.JPG".equals(outsidePhoto.recordedName()),
          "Physical exclusion does not hide the mismatch or classify the reference as the historical photo");
      requireUnmatchedHistory(listing, "OLD-OUTSIDE.JPG", "NEW-OUTSIDE.JPG", outside.path);
      var identifiedPhoto = requireInventoryPhoto(listing, matched, "MATCHED.JPG", f.path,
          PhotoStorageInventory.Status.ELIGIBLE);
      require(identifiedPhoto.historyMatches(), "The valid history can still independently identify the physical file");
      requireUnmatchedHistory(listing, "OLD-OTHER.JPG", "MATCHED.JPG", f.path);
      String matchingId = Long.toString(before.stream().filter(row -> row.getName().equals("MATCHED.JPG")).findFirst().orElseThrow().getId());
      PhotoMove.Plan plan = move.plan(secondary);
      require(plan.skipped() == 1 && plan.items().size() == 1 && matched.toString().equals(plan.items().get(0).source())
          && matchingId.equals(plan.items().get(0).legacyIds()), "Move eligibility and exact proof IDs remain unchanged");
      require(history.moveEvidence().equals(before) && ledger.importDestinations().isEmpty() && move.batch() == null
          && Arrays.equals(bytes, f.read(matched)) && Arrays.equals(bytes, outside.read(renamed)),
          "Classifying historical references leaves both originals, legacy records and the journal unchanged");
    }
  }

  private static void checkLedgerInventoryEvidence(Fixture f) throws Exception {
    byte[] beforeExif = jpeg(0xff386477);
    byte[] normalized = jpegWithCaptureTime(f, 0xff386477, CAPTURE, CAPTURE + 1);
    byte[] ledgerOnlyBytes = jpegWithCaptureTime(f, 0xff459469, CAPTURE, CAPTURE + 1);
    byte[] linkedBytes = jpegWithCaptureTime(f, 0xff9b455a, CAPTURE, CAPTURE + 1);
    byte[] ledgerOnlyStored = Arrays.copyOf(ledgerOnlyBytes, ledgerOnlyBytes.length + 16);
    byte[] linkedStored = Arrays.copyOf(linkedBytes, linkedBytes.length + 32);
    require(beforeExif.length != normalized.length, "Adding EXIF changes the stored byte count of the normalized fixture");
    Uri normalizedUri = f.insertDatedLegacyPhoto("EXIF-NORMALIZED.JPG", normalized);
    Uri ledgerOnlyUri = f.insertDatedLegacyPhoto("LEDGER-SIZE.JPG", ledgerOnlyStored);
    Uri linkedUri = f.insertDatedLegacyPhoto("LINKED-SIZE.JPG", linkedStored);
    Uri mixedDetached = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MISSING), Long.MAX_VALUE);
    Uri matchedDetached = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MISSING), Long.MAX_VALUE - 1);
    try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         PhotoMove move = f.move(ledger, history, boundary -> {})) {
      var normalizedReceipt = adoptFixtureReceipt(f, ledger, normalizedUri, "EXIF-NORMALIZED.JPG", beforeExif, normalized);
      adoptFixtureReceipt(f, ledger, ledgerOnlyUri, "LEDGER-SIZE.JPG", ledgerOnlyBytes, ledgerOnlyStored);
      adoptFixtureReceipt(f, ledger, linkedUri, "LINKED-SIZE.JPG", linkedBytes, linkedStored);
      recordFixtureHistory(history, f, "LINKED-SIZE.JPG", linkedBytes.length, CAPTURE, linkedUri.toString());
      recordFixtureHistory(history, f, "DETACHED-OLD.JPG", beforeExif.length, CAPTURE, mixedDetached.toString());
      byte[] detachedOther = jpeg(0xff47875e);
      adoptFixtureReceipt(f, ledger, mixedDetached, "DETACHED-OTHER.JPG", detachedOther, detachedOther);
      byte[] detachedLinked = jpeg(0xff665288);
      adoptFixtureReceipt(f, ledger, matchedDetached, "DETACHED-LINKED.JPG", detachedLinked, detachedLinked);
      recordFixtureHistory(history, f, "DETACHED-LINKED.JPG", detachedLinked.length, CAPTURE, matchedDetached.toString());

      var initial = move.inventory(f.secondary);
      require(normalizedReceipt.sourceBytes == beforeExif.length && normalizedReceipt.storedBytes == normalized.length
          && requireInventoryPhoto(initial, normalizedUri, "EXIF-NORMALIZED.JPG", f.path,
              PhotoStorageInventory.Status.ELIGIBLE).historyMatches(),
          "A legitimate EXIF rewrite confirms the stored file using its published size instead of the source size");
      require(requireInventoryPhoto(initial, ledgerOnlyUri, "LEDGER-SIZE.JPG", f.path,
              PhotoStorageInventory.Status.ELIGIBLE).historyMatches()
          && requireInventoryPhoto(initial, linkedUri, "LINKED-SIZE.JPG", f.path,
              PhotoStorageInventory.Status.ELIGIBLE).historyMatches(), "Both published receipts initially match");
      Map<String, Integer> missing = new HashMap<>();
      for (var row : initial.unresolved()) {
        require(row.location().kind() == PhotoStorageLocation.Kind.RECORDED && MISSING.equals(row.location().volume())
            && row.location().availability() == PhotoStorageLocation.Availability.UNAVAILABLE
            && row.reason() == PhotoStorageInventory.Reason.MISSING_REFERENCE && row.candidateLocation() == null,
            "Detached histories preserve the recorded volume and have no current candidate");
        missing.merge(row.name(), row.count(), Integer::sum);
      }
      require(missing.equals(Map.of("DETACHED-OLD.JPG", 1, "DETACHED-OTHER.JPG", 1, "DETACHED-LINKED.JPG", 1)),
          "Same-URI unmatched names both survive while matching ledger and legacy evidence is counted once");

      replaceFixtureBytes(f, ledgerOnlyUri, ledgerOnlyBytes);
      replaceFixtureBytes(f, linkedUri, linkedBytes);
      List<HistoryDb.MoveEvidence> legacyBefore = history.moveEvidence();
      var ledgerBefore = ledger.importDestinations();
      var storedSizesBefore = ledger.importStoredSizes();
      var receiptsBefore = ledger.moveReceipts();
      PhotoMove.Plan planBefore = move.plan(f.secondary);
      PhotoStorageInventory after = move.inventory(f.secondary);
      require(requireInventoryPhoto(after, normalizedUri, "EXIF-NORMALIZED.JPG", f.path,
          PhotoStorageInventory.Status.ELIGIBLE).historyMatches(), "The unchanged EXIF-rewritten receipt remains confirmed");
      requireStoredSizeMismatch(after, f, ledgerOnlyUri, "LEDGER-SIZE.JPG");
      requireStoredSizeMismatch(after, f, linkedUri, "LINKED-SIZE.JPG");
      require(planBefore.items().size() == 3 && planBefore.skipped() == 2
          && planBefore.items().stream().anyMatch(item -> item.source().equals(linkedUri.toString())
              && !item.legacyIds().isEmpty() && item.bytes() == linkedBytes.length)
          && planBefore.equals(move.plan(f.secondary)) && move.batch() == null,
          "The read-only diagnosis does not change legacy proof, queue eligibility, or the journal");
      require(history.moveEvidence().equals(legacyBefore) && ledger.importDestinations().equals(ledgerBefore)
          && ledger.importStoredSizes().equals(storedSizesBefore) && ledger.moveReceipts().equals(receiptsBefore)
          && ledger.contentCount() == 5 && ledger.receiptCount() == 5
          && Arrays.equals(normalized, f.read(normalizedUri)) && Arrays.equals(ledgerOnlyBytes, f.read(ledgerOnlyUri))
          && Arrays.equals(linkedBytes, f.read(linkedUri)), "Inventory leaves file bytes and both histories unchanged");
    }
  }

  private static CameraImportLedger.Content adoptFixtureReceipt(Fixture f, CameraImportLedger ledger, Uri uri, String name,
      byte[] sourceBytes, byte[] storedBytes) throws Exception {
    var source = f.source("wifi:", name, sourceBytes);
    String sourceSha = E2Archive.sha256(sourceBytes);
    return ledger.adopt(source, sourceSha, E2Archive.sha256(storedBytes), storedBytes.length, uri.toString(), "JPEG",
        ledger.captureGroup(source, sourceSha, "JPEG"));
  }

  private static void replaceFixtureBytes(Fixture f, Uri uri, byte[] replacement) throws IOException {
    f.requireOwned(f.row(uri));
    f.write(uri, replacement);
    require(f.awaitMediaLong(uri, SIZE, replacement.length) == replacement.length
        && f.awaitCaptureTime(uri, CAPTURE * 1000) == CAPTURE * 1000 && Arrays.equals(replacement, f.read(uri)),
        "The same URI now has the camera source size with matching name and capture time");
  }

  private static void requireStoredSizeMismatch(PhotoStorageInventory listing, Fixture f, Uri uri, String name) {
    var photo = requireInventoryPhoto(listing, uri, name, f.path, PhotoStorageInventory.Status.EXCLUDED,
        PhotoStorageInventory.Reason.HISTORY_SIZE);
    require(!photo.historyMatches() && name.equals(photo.recordedName()),
        "A changed stored size cannot be confirmed, even if the current size matches a source receipt or legacy evidence");
    require(listing.unresolved().stream().filter(row -> name.equals(row.name()) && name.equals(row.candidateName())
            && row.reasons().contains(PhotoStorageInventory.Reason.HISTORY_SIZE)).mapToInt(PhotoStorageInventory.History::count).sum() == 1,
        "The historical receipt remains unresolved with the stored-size mismatch reason");
  }

  private static void requireUnmatchedHistory(PhotoStorageInventory listing, String oldName, String currentName, String folder) {
    var history = listing.unresolved().stream().filter(row -> oldName.equals(row.name())).findFirst().orElseThrow();
    require(history.count() == 1 && history.reasons().contains(PhotoStorageInventory.Reason.HISTORY_NAME)
        && history.location().kind() == PhotoStorageLocation.Kind.RECORDED && PRIMARY.equals(history.location().volume())
        && currentName.equals(history.candidateName()) && history.candidateLocation() != null
        && history.candidateLocation().kind() == PhotoStorageLocation.Kind.CURRENT
        && PRIMARY.equals(history.candidateLocation().volume()) && folder.equals(history.candidateLocation().folder()),
        "Unmatched historical name and recorded location stay separate from the identifiable reference file");
  }

  private static void recordFixtureHistory(HistoryDb history, Fixture fixture, String name, long size, long shot, String uri) {
    history.recordImport(MODEL, fixture.id, new ImportedKey(name, size, shot), uri);
  }

  private static PhotoStorageInventory.Photo requireInventoryPhoto(PhotoStorageInventory listing, Uri uri, String name,
      String folder, PhotoStorageInventory.Status status, PhotoStorageInventory.Reason... reasons) {
    var photo = listing.photos().stream().filter(row -> uri.equals(row.uri())).findFirst().orElseThrow();
    require(name.equals(photo.name()) && status == photo.status() && photo.location().kind() == PhotoStorageLocation.Kind.CURRENT
        && PhotoStorage.volumeOf(uri).equals(photo.location().volume()) && folder.equals(photo.location().folder()),
        "Inventory shows exact MediaStore name, status, folder and volume for the fixture");
    require(photo.reasons().containsAll(List.of(reasons)) && (reasons.length > 0 || photo.reasons().isEmpty()),
        "Inventory exposes the expected specific exclusion evidence");
    return photo;
  }

  private static void assertInventoryGuards(PhotoMove move, String secondary, PhotoMove.Plan plan) throws Exception {
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread importThread = new Thread(() -> {
      CameraImportGate.lock.lock();
      try {
        locked.countDown();
        release.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException stopped) {
        Thread.currentThread().interrupt();
      } finally {
        CameraImportGate.lock.unlock();
      }
    });
    importThread.start();
    try {
      require(locked.await(5, TimeUnit.SECONDS), "Hold import gate on another worker");
      expectInventoryBlocked(move, secondary);
    } finally {
      release.countDown();
      importThread.join(5000);
    }
    move.begin(plan);
    expectInventoryBlocked(move, secondary);
    move.abandon();
  }

  private static void expectInventoryBlocked(PhotoMove move, String secondary) throws IOException {
    try {
      move.inventory(secondary);
      throw new CheckFailure("Inventory published a snapshot while history could be incomplete");
    } catch (PhotoMove.Failure expected) {
      require(expected.getMessage() != null && !expected.getMessage().isBlank(), "The blocked inventory provides guidance");
    }
  }

  private static void checkLocationResolution(Fixture f, Uri primary, Uri sd, Uri detached, Uri missingAggregate, int size) {
    var primaryLocation = PhotoStorageLocation.resolve(f.context,
        new PhotoRepository.Photo(primary, "PRIMARY.JPG", f.path, size, 0, 0));
    require(primaryLocation.kind() == PhotoStorageLocation.Kind.CURRENT && PRIMARY.equals(primaryLocation.volume())
        && f.path.equals(primaryLocation.folder()), "Viewer location confirms a real exact primary original");
    Uri cardAggregate = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentUris.parseId(sd));
    var cardLocation = PhotoStorageLocation.resolve(f.context,
        new PhotoRepository.Photo(cardAggregate, "ON-SD.JPG", f.path, size, 0, 0));
    require(cardLocation.kind() == PhotoStorageLocation.Kind.CURRENT && f.secondary.equals(cardLocation.volume()),
        "Viewer location identifies a unique aggregate MediaStore match on SD");
    var detachedLocation = PhotoStorageLocation.resolve(f.context,
        new PhotoRepository.Photo(detached, "DETACHED.JPG", f.path, size, 0, 0));
    require(detachedLocation.kind() == PhotoStorageLocation.Kind.RECORDED && MISSING.equals(detachedLocation.volume()),
        "Viewer does not present a detached volume as currently accessible");
    Uri document = DocumentsContract.buildDocumentUri(DOCUMENTS_AUTHORITY, MISSING + ":" + f.path + "DETACHED.JPG");
    var documentLocation = PhotoStorageLocation.resolve(f.context,
        new PhotoRepository.Photo(document, "DETACHED.JPG", f.path, size, 0, 0));
    require(documentLocation.kind() == PhotoStorageLocation.Kind.RECORDED && MISSING.equals(documentLocation.volume()),
        "Recognized but unverifiable SAF retains recorded volume only");
    for (Uri unknown : List.of(missingAggregate, Uri.parse("content://test.synthetic/opaque/1"))) {
      var location = PhotoStorageLocation.resolve(f.context, new PhotoRepository.Photo(unknown, "UNKNOWN.JPG", f.path, size, 0, 0));
      require(location.kind() == PhotoStorageLocation.Kind.UNKNOWN && location.volume() == null,
          "Opaque and unmatched aggregate references never infer the original location");
    }
    var cloud = PhotoStorageLocation.resolve(f.context,
        new PhotoRepository.Photo(Uri.parse("archive://synthetic/photo"), "CLOUD.JPG", f.path, size, 0, 0));
    require(cloud.kind() == PhotoStorageLocation.Kind.CLOUD && cloud.volume() == null && cloud.folder().isEmpty(),
        "A cloud original is not assigned a local volume or stale folder");
  }

  private static void rejectStaleHistory(Fixture f) throws Exception {
    byte[] image = jpegWithCaptureTime(f, 0xff335555, CAPTURE, CAPTURE + 1);
    byte[] noCaptureImage = jpeg(0xff335555);
    Uri wrongName = f.insertDatedLegacyPhoto("OTHER.JPG", image);
    Uri wrongSize = f.insertDatedLegacyPhoto("WRONGSIZE.JPG", image);
    Uri noCapture = f.insert(PRIMARY, "NO-CAPTURE.JPG", noCaptureImage, 0);
    require(f.awaitCaptureTime(noCapture, 0) == 0, "Owned EXIF-free fixture has no MediaStore capture time");
    try (CameraImportLedger ledger = f.ledger(); HistoryDb history = f.history();
         PhotoMove move = f.move(ledger, history, boundary -> {})) {
      history.recordImport(MODEL, f.id, new ImportedKey("PREVIOUS.JPG", image.length, CAPTURE), wrongName.toString());
      history.recordImport(MODEL, f.id, new ImportedKey("WRONGSIZE.JPG", image.length + 16, CAPTURE), wrongSize.toString());
      history.recordImport(MODEL, f.id, new ImportedKey("NO-CAPTURE.JPG", noCaptureImage.length, CAPTURE), noCapture.toString());
      PhotoMove.Plan plan = move.plan(f.secondary);
      require(plan.items().isEmpty() && plan.skipped() == 3,
          "Stale filename, size, or absent MediaStore capture time cannot prove a legacy-only import");
      require(f.rows(f.secondary).isEmpty() && Arrays.equals(image, f.read(wrongName))
          && Arrays.equals(image, f.read(wrongSize)) && Arrays.equals(noCaptureImage, f.read(noCapture))
          && history.importDestinations().equals(Set.of(wrongName.toString(), wrongSize.toString(), noCapture.toString())),
          "All unrelated bytes and legacy history references remain untouched");
    }
  }

  private static void rejectResetHistory(Fixture f) throws Exception {
    byte[] image = jpegWithCaptureTime(f, 0xff776655, CAPTURE, CAPTURE + 1);
    Uri source = f.insertDatedLegacyPhoto("RESET.JPG", image);
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
    byte[] photo = jpegWithCaptureTime(f, 0xff895544, captureSeconds, cameraMtime);
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

  private static byte[] jpegWithCaptureTime(Fixture f, int color, long captureSeconds, long cameraMtime) throws IOException {
    File jpeg = File.createTempFile("loupe-dated-fixture-" + f.id, ".jpg", f.context.getCacheDir());
    try {
      Files.write(jpeg.toPath(), jpeg(color));
      var zoned = Instant.ofEpochSecond(captureSeconds).atZone(ZoneId.systemDefault());
      String timestamp = zoned.format(DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.ROOT));
      String offset = zoned.getOffset().getTotalSeconds() == 0 ? "+00:00" : zoned.getOffset().getId();
      ExifInterface exif = new ExifInterface(jpeg);
      exif.setAttribute(ExifInterface.TAG_DATETIME, timestamp);
      exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, timestamp);
      exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, timestamp);
      exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offset);
      exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset);
      exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offset);
      exif.saveAttributes();
      require(CameraImportLegacy.metadata(jpeg, cameraMtime).getCaptureSeconds() == captureSeconds,
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

  static String captureInventoryUi(ArchiveInstrumentation test, String secondaryArg, int width, float font, int records)
      throws Exception {
    Context base = test.getTargetContext();
    require(TARGET_PACKAGE.equals(base.getPackageName()), "Inventory captures require the isolated APK");
    require(Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish"), "Inventory captures require an Android emulator");
    require(records > 0 && records <= 1900, "Unsupported synthetic history count");
    String secondary = secondaryArg == null ? secondaryVolume(base) : secondaryArg.toLowerCase(Locale.ROOT);
    PhotoStorage.requireWritable(base, secondary);
    SharedPreferences settings = base.getSharedPreferences("settings", 0);
    String previous = settings.getString("photo_volume", null);
    HistoryDb history = HistoryDb.Companion.get(base);
    CameraImportLedger ledger = CameraImportLedger.get(base);
    require(history.importDestinations().isEmpty() && ledger.importDestinations().isEmpty(),
        "Inventory capture requires clean isolated import histories");
    try (PhotoMoveStore journal = new PhotoMoveStore(base)) {
      require(journal.batch() == null && !PhotoMoveService.state().running(), "Inventory capture requires an idle journal");
    }
    File captures = new File(base.getCacheDir(), "photo-storage-checks");
    require(captures.isDirectory() || captures.mkdirs(), "Create Android capture directory");
    String profile = "inventory-" + width + "-" + font;
    try (Fixture fixture = new Fixture(base, secondary)) {
      byte[] image = jpegWithCaptureTime(fixture, 0xff52748e, CAPTURE, CAPTURE + 1);
      Uri primary = fixture.insertDatedLegacyPhoto("CURRENT-FILE.JPG", image);
      Uri card = fixture.insert(secondary, "ON-CARD.JPG", image);
      require(fixture.awaitCaptureTime(card, CAPTURE * 1000) == CAPTURE * 1000, "Card fixture has exact capture evidence");
      Uri recorded = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MISSING), Long.MAX_VALUE);
      SQLiteDatabase db = history.getWritableDatabase();
      try {
        db.beginTransaction();
        try {
          recordFixtureHistory(history, fixture, "RECORDED-NAME.JPG", image.length, CAPTURE, primary.toString());
          recordFixtureHistory(history, fixture, "ON-CARD.JPG", image.length, CAPTURE, card.toString());
          for (int i = 0; i < records; i++) recordFixtureHistory(history, fixture,
              String.format(Locale.ROOT, "UNRESOLVED-%04d.JPG", i), image.length, CAPTURE, recorded.toString());
          db.setTransactionSuccessful();
        } finally {
          db.endTransaction();
        }
        List<HistoryDb.MoveEvidence> before = history.moveEvidence();
        require(PhotoStorage.select(base, PRIMARY), "Start the capture with internal storage selected");
        captureInventoryScreens(test, base, secondary, captures, profile, records);
        require(Arrays.equals(image, fixture.read(primary)) && Arrays.equals(image, fixture.read(card))
            && history.moveEvidence().equals(before) && ledger.importDestinations().isEmpty(),
            "Opening, filtering, viewing and reloading do not change photos or import histories");
        try (PhotoMoveStore journal = new PhotoMoveStore(base)) {
          require(journal.batch() == null, "Inventory interactions do not start a move");
        }
      } finally {
        history.resetHistory(MODEL, fixture.id);
      }
    } finally {
      SharedPreferences.Editor restore = settings.edit();
      if (previous == null) restore.remove("photo_volume");
      else restore.putString("photo_volume", previous);
      require(restore.commit(), "Restore the previous isolated app destination");
    }
    return "PASS photo storage inventory UI requested " + width + "dp, effective " + test.photoStorageEffectiveWidth()
        + "dp, font " + font + ": zero eligible, exact volumes, separate reference details, " + (records + 1)
        + " virtualized history records, selection guard, retry and back. " + captures.getAbsolutePath();
  }

  private static void captureInventoryScreens(ArchiveInstrumentation test, Context base, String secondary, File captures,
      String profile, int records) throws Exception {
    Activity settings = test.startActivitySync(new Intent(base, PhotoStorageActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    Instrumentation.ActivityMonitor monitor = test.addMonitor(PhotoStorageInventoryActivity.class.getName(), null, false);
    Activity inventory = null;
    try {
      UiAutomation automation = test.getUiAutomation();
      String openLabel = "保存場所と移動対象外の理由を見る";
      String moveLabel = "取り込み済みの写真を移動";
      Predicate<AccessibilityNodeInfo> open = node -> hasText(node, openLabel);
      Predicate<AccessibilityNodeInfo> noEligible = node -> mentions(node, "移動できる取り込み済みの写真はありません。")
          && mentions(node, "移動対象外の項目があります。保存場所の一覧で写真と理由を確認できます。")
          && !mentions(node, "移動対象外 2件");
      checkInventorySelectionGuard(test, base, settings, monitor, automation, secondary, open);
      String cardLabel = new PhotoStorageLocation.Volumes(base).location(
          PhotoStorageLocation.Kind.CURRENT, secondary, "").label();
      Predicate<AccessibilityNodeInfo> overview = node -> mentions(node, "確認できた写真 1枚")
          && mentions(node, "参照先の写真 1枚") && mentions(node, cardLabel + " 1枚")
          && mentions(node, "確認が必要な履歴 " + (records + 1) + "件");
      awaitStorageControl(automation, overview);
      click(awaitStorageControl(automation, node -> hasText(node, moveLabel) && node.isEnabled()));
      awaitStorageControl(automation, noEligible);
      captureSettled(test, automation, captures, profile + "-zero-eligible", noEligible);
      AccessibilityNodeInfo opening = awaitStorageControl(automation, open);
      assertWindowWidth(test, settings);
      assertTouchTarget(test, settings, opening, openLabel);
      click(opening);
      inventory = test.waitForMonitorWithTimeout(monitor, 6000);
      require(inventory instanceof PhotoStorageInventoryActivity, "Settings opens its real inventory activity");
      final Activity listing = inventory;
      Predicate<AccessibilityNodeInfo> summary = node -> mentions(node, "確認できた写真 1枚")
          && mentions(node, "参照先の写真 1枚") && mentions(node, "確認が必要な履歴 " + (records + 1) + "件");
      awaitNode(automation, summary);
      assertWindowWidth(test, listing);
      assertTouchTarget(test, listing, awaitNode(automation, node -> hasText(node, "絞り込み")), "絞り込み");
      assertTouchTarget(test, listing, awaitNode(automation, node -> hasText(node, "再読み込み")), "再読み込み");
      assertAccessibilityTarget(listing, awaitNode(automation, node -> "写真の保存場所を閉じる".contentEquals(
          node.getContentDescription() == null ? "" : node.getContentDescription())), "Close inventory");
      assertVirtualizedInventory(test, listing, records + 3);
      captureSettled(test, automation, captures, profile + "-all", summary);

      openInventoryFilter(automation);
      captureSettled(test, automation, captures, profile + "-filters", node -> hasText(node, "表示する項目"));
      click(awaitStorageControl(automation, node -> hasText(node, cardLabel)));
      Predicate<AccessibilityNodeInfo> onCard = node -> mentions(node, "ON-CARD.JPG") && mentions(node, cardLabel);
      AccessibilityNodeInfo cardRow = awaitNode(automation, onCard);
      assertAccessibilityTarget(listing, cardRow, "Located photo row");
      assertVirtualizedInventory(test, listing, 1);
      click(cardRow);
      Predicate<AccessibilityNodeInfo> cardDetail = node -> mentions(node, "ON-CARD.JPG") && mentions(node, cardLabel)
          && mentions(node, "Pictures/CameraSync/");
      awaitStorageControl(automation, cardDetail);
      captureSettled(test, automation, captures, profile + "-card-detail", cardDetail);
      click(awaitStorageControl(automation, node -> hasText(node, "閉じる")));

      openInventoryFilter(automation);
      click(awaitStorageControl(automation, node -> hasText(node, "内部ストレージ")));
      click(awaitNode(automation, node -> mentions(node, "参照先の写真") && mentions(node, "CURRENT-FILE.JPG")
          && mentions(node, "内部ストレージ")));
      awaitNode(automation, node -> hasText(node, "参照先の写真の詳細"));
      Predicate<AccessibilityNodeInfo> mismatch = node -> mentions(node, "履歴の名前") && mentions(node, "RECORDED-NAME.JPG")
          && mentions(node, PhotoStorageInventory.Reason.HISTORY_NAME.label());
      awaitStorageControl(automation, mismatch);
      captureSettled(test, automation, captures, profile + "-mismatch-detail", mismatch);
      click(awaitStorageControl(automation, node -> hasText(node, "閉じる")));

      openInventoryFilter(automation);
      click(awaitStorageControl(automation, node -> hasText(node, "確認が必要")));
      click(awaitNode(automation, node -> node.getContentDescription() != null
          && node.getContentDescription().toString().startsWith("履歴の名前　RECORDED-NAME.JPG")
          && mentions(node, "参照先の写真") && mentions(node, "CURRENT-FILE.JPG")));
      awaitNode(automation, node -> hasText(node, "取り込み履歴の詳細"));
      captureSettled(test, automation, captures, profile + "-mismatched-history-detail", mismatch);
      click(awaitStorageControl(automation, node -> hasText(node, "閉じる")));
      Predicate<AccessibilityNodeInfo> unresolved = node -> mentions(node, "UNRESOLVED-0000.JPG")
          && mentions(node, "記録上は") && mentions(node, MISSING.toUpperCase(Locale.ROOT));
      click(awaitStorageControl(automation, unresolved));
      Predicate<AccessibilityNodeInfo> recordedDetail = node -> mentions(node, "UNRESOLVED-0000.JPG")
          && mentions(node, PhotoStorageInventory.Reason.MISSING_REFERENCE.label());
      awaitStorageControl(automation, recordedDetail);
      captureSettled(test, automation, captures, profile + "-recorded-detail", recordedDetail);
      click(awaitStorageControl(automation, node -> hasText(node, "閉じる")));
      openInventoryFilter(automation);
      click(awaitStorageControl(automation, node -> hasText(node, "移動対象外")));
      awaitNode(automation, node -> mentions(node, "CURRENT-FILE.JPG") && mentions(node, PhotoStorageInventory.Reason.HISTORY_NAME.label()));

      require(CameraImportGate.lock.tryLock(), "Acquire the import gate for a blocked UI refresh");
      try {
        click(awaitNode(automation, node -> hasText(node, "再読み込み")));
        Predicate<AccessibilityNodeInfo> blocked = node -> mentions(node, "取り込みや移動が終わってから保存場所を確認してください。");
        awaitNode(automation, blocked);
        captureSettled(test, automation, captures, profile + "-blocked", blocked);
      } finally {
        CameraImportGate.lock.unlock();
      }
      click(awaitNode(automation, node -> hasText(node, "再読み込み")));
      awaitNode(automation, node -> mentions(node, "CURRENT-FILE.JPG") && mentions(node, PhotoStorageInventory.Reason.HISTORY_NAME.label()));
      click(awaitNode(automation, node -> "写真の保存場所を閉じる".contentEquals(
          node.getContentDescription() == null ? "" : node.getContentDescription())));
      awaitStorageControl(automation, open);
    } finally {
      test.removeMonitor(monitor);
      Activity listing = inventory;
      test.runOnMainSync(() -> {
        if (listing != null) listing.finish();
        settings.finish();
      });
      test.waitForIdleSync();
    }
  }

  private static void checkInventorySelectionGuard(Instrumentation test, Context base, Activity settings,
      Instrumentation.ActivityMonitor monitor, UiAutomation automation, String secondary, Predicate<AccessibilityNodeInfo> open)
      throws Exception {
    SharedPreferences preferences = base.getSharedPreferences("settings", 0);
    CountDownLatch committing = new CountDownLatch(1);
    AtomicBoolean disabledDuringCommit = new AtomicBoolean();
    SharedPreferences.OnSharedPreferenceChangeListener observeCommit = (prefs, key) -> {
      if (!"photo_volume".equals(key) || !secondary.equals(prefs.getString(key, null))) return;
      View inventoryButton = findButton(settings.getWindow().getDecorView(), "保存場所と移動対象外の理由を見る");
      if (inventoryButton != null) {
        disabledDuringCommit.set(!inventoryButton.isEnabled());
        // Bypass the disabled view's touch behavior to test the click handler's own async guard.
        inventoryButton.performClick();
      }
      committing.countDown();
    };
    preferences.registerOnSharedPreferenceChangeListener(observeCommit);
    try {
      click(awaitStorageControl(automation, node -> hasText(node, "保存先を変更") && node.isEnabled()));
      String card = PhotoStorage.label(base, secondary);
      click(awaitStorageControl(automation, node -> hasText(node, card) && node.isCheckable()));
      click(awaitStorageControl(automation, node -> hasText(node, "設定")));
      require(committing.await(6, TimeUnit.SECONDS), "Observe the confirmed selection before its worker notifies the screen");
      awaitStorageControl(automation, open.and(AccessibilityNodeInfo::isEnabled));
      test.waitForIdleSync();
      require(disabledDuringCommit.get() && monitor.getHits() == 0 && secondary.equals(PhotoStorage.selected(base)),
          "Inventory cannot open during the selection commit and is available immediately afterward");
    } finally {
      preferences.unregisterOnSharedPreferenceChangeListener(observeCommit);
    }
  }

  private static boolean mentions(AccessibilityNodeInfo node, String value) {
    return node.getText() != null && node.getText().toString().contains(value)
        || node.getContentDescription() != null && node.getContentDescription().toString().contains(value);
  }

  private static void openInventoryFilter(UiAutomation automation) {
    click(awaitNode(automation, node -> hasText(node, "絞り込み")));
    awaitNode(automation, node -> hasText(node, "表示する項目"));
  }

  private static AccessibilityNodeInfo awaitStorageControl(UiAutomation automation, Predicate<AccessibilityNodeInfo> match) {
    long deadline = SystemClock.uptimeMillis() + 8000;
    int scroll = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
    while (SystemClock.uptimeMillis() < deadline) {
      var root = automation.getRootInActiveWindow();
      var node = findNode(root, match);
      if (node != null) return node;
      var body = findNode(root, AccessibilityNodeInfo::isScrollable);
      if (body != null && !body.performAction(scroll)) {
        scroll = scroll == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
      }
      SystemClock.sleep(200);
    }
    throw new CheckFailure("Inventory accessibility control is not reachable by scrolling");
  }

  private static void assertAccessibilityTarget(Activity activity, AccessibilityNodeInfo node, String label) {
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    int minimum = GalleryUi.dp(activity, 48);
    require(node.isClickable() && node.isEnabled() && bounds.width() >= minimum && bounds.height() >= minimum
        && availableDisplay(activity).contains(bounds), label + " is accessible and has an unclipped 48dp touch target");
  }

  private static void assertVirtualizedInventory(Instrumentation test, Activity activity, int rows) throws InterruptedException {
    awaitPresentedFrames(test);
    int[] counts = new int[2];
    test.runOnMainSync(() -> {
      ListView list = findInventoryList(activity.getWindow().getDecorView());
      if (list != null) {
        counts[0] = list.getCount();
        counts[1] = list.getChildCount();
      }
    });
    require(counts[0] == rows && counts[1] > 0 && counts[1] <= Math.min(rows, 40),
        "Only a viewport-sized set of native rows is attached for the expected adapter count");
  }

  private static ListView findInventoryList(View view) {
    if (view instanceof ListView list) return list;
    if (view instanceof ViewGroup container) {
      for (int i = 0; i < container.getChildCount(); i++) {
        ListView list = findInventoryList(container.getChildAt(i));
        if (list != null) return list;
      }
    }
    return null;
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
      byte[] bytes = jpegWithCaptureTime(f, 0xff264167, CAPTURE, CAPTURE + 1);
      String name = "CONFIRM.JPG";
      Uri original = f.insertDatedLegacyPhoto(name, bytes);
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

      click(awaitStorageControl(automation, node -> hasText(node, "取り込み済みの写真を移動") && node.isEnabled()));
      Predicate<AccessibilityNodeInfo> confirmation = node -> {
        String message = node.getText() == null ? "" : node.getText().toString();
        return message.contains(label + "に1枚") && (skipped == 0 ? !message.contains("移動対象外")
            : message.contains("移動対象外の項目があります。保存場所の一覧で写真と理由を確認できます。")
                && !message.contains("移動対象外 1件"));
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
        && node.getText().toString().contains("未処理0枚。")
        && (skipped == 0 ? !node.getText().toString().contains("移動対象外")
            : node.getText().toString().contains("移動対象外の項目があります。")
                && !node.getText().toString().contains("移動対象外 " + skipped + "件"));
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

  private static void assertWindowWidth(ArchiveInstrumentation test, Activity activity) {
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

  private static void assertTouchTarget(Instrumentation test, Activity activity,
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
      var documentLocation = PhotoStorageLocation.resolve(f.context, oldDocument);
      require(documentLocation.kind() == PhotoStorageLocation.Kind.CURRENT && secondary.equals(documentLocation.volume())
          && f.path.equals(documentLocation.folder()), "Viewer location follows the explicitly granted SD document");
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
      this(base, secondary, id, "Pictures/CameraSync/");
    }

    Fixture(Context base, String secondary, String id, String picturesRoot) {
      this.id = id;
      this.path = picturesRoot + id + "/";
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
      Uri uri = insertPending(volume, name, bytes, dateTaken);
      ContentValues publish = new ContentValues();
      publish.put(IS_PENDING, 0);
      require(resolver.update(uri, publish, null, null) == 1, "Publish fixture");
      return uri;
    }

    Uri insertPending(String volume, String name, byte[] bytes, long dateTaken) throws IOException {
      ContentValues values = new ContentValues();
      values.put(DISPLAY_NAME, name);
      values.put(RELATIVE_PATH, path);
      values.put(MIME_TYPE, "image/jpeg");
      values.put(DATE_TAKEN, dateTaken);
      values.put(IS_PENDING, 1);
      Uri uri = resolver.insert(MediaStore.Images.Media.getContentUri(volume), values);
      require(uri != null, "Insert fixture under unique folder");
      write(uri, bytes);
      return uri;
    }

    Uri insertDatedLegacyPhoto(String name, byte[] bytes) throws IOException {
      Uri uri = insert(PRIMARY, name, bytes);
      long taken = awaitCaptureTime(uri, CAPTURE * 1000);
      require(taken == CAPTURE * 1000,
          "Published legacy-only fixture " + name + " needs matching MediaStore DATE_TAKEN (actual="
              + taken + ", expected=" + CAPTURE * 1000 + ")");
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
      return awaitMediaLong(uri, DATE_TAKEN, expected);
    }

    long awaitMediaLong(Uri uri, String column, long expected) throws IOException {
      long deadline = SystemClock.uptimeMillis() + 6000;
      long actual = 0;
      do {
        try (Cursor row = resolver.query(uri, new String[]{column}, null, null, null)) {
          if (row == null || !row.moveToFirst()) throw new IOException("Could not query synthetic media metadata");
          actual = row.getLong(0);
          if (actual == expected) return actual;
        }
        SystemClock.sleep(50);
      } while (SystemClock.uptimeMillis() < deadline);
      return actual;
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
