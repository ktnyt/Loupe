package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Checks existing key compatibility with generated metadata and disposable Android state. */
final class GalleryPhotoKeyCompatibilityChecks {
  // Independent SHA-256 vectors over the exact historical UTF-8 inputs.
  private static final String LEGACY =
      "dcda72ef6729f1a66133af4f1834edb8affb0c9629bac9b837de7cb4fc2ddaa6";
  private static final String NAME = "É.JPG";
  private static final String FOLDER = "Pictures/OM-1/";
  private static final long MODIFIED = 1_700_000_000L;
  private static final Uri URI = Uri.parse("file:///gallery-key-compatibility/fixture.jpg");

  static String run(Instrumentation test) throws Exception {
    PhotoRepository.Photo ordinary =
        new PhotoRepository.Photo(URI, NAME, FOLDER, 2048, MODIFIED, 0);
    require(LEGACY.equals(ArchiveStore.key(ordinary)), "legacy UTF-8 key");
    require(LEGACY.equals(ArchiveStore.key(ordinary)), "same Photo retains the legacy key");
    require(
        LEGACY.equals(
            ArchiveStore.key(
                new PhotoRepository.Photo(null, NAME, FOLDER, 2048, MODIFIED, 1, 2, 3))),
        "URI and other dates do not enter the legacy key");
    require(
        "a59ce90090e57360e56d59642bd61c3940bae600d095fcd4ba2ae70a00f45d09"
            .equals(
                ArchiveStore.key(new PhotoRepository.Photo(URI, NAME, FOLDER, 2049, MODIFIED, 0))),
        "size change uses the new snapshot");
    require(
        "b9216dcf48038f984bf2f4f39d8546dcfccf0ac9cda14abc8cb9bfaea02e0c42"
            .equals(
                ArchiveStore.key(
                    new PhotoRepository.Photo(URI, NAME, FOLDER, 2048, MODIFIED + 1, 0))),
        "modified change uses the new snapshot");
    require(
        "1d65d9f607ab8cb3f26c3b2d1cb3e9bb0bb42932160b782a71d56f61f4f20b88"
            .equals(
                ArchiveStore.key(
                    new PhotoRepository.Photo(URI, NAME, "BEEF/OM-1/", 2048, MODIFIED, 0))),
        "secondary-volume prefix is verbatim");
    require(
        "a0dd6a8e3714d50e5088fc49c4e87f8725821daf24aa40f9a14bb9feb6d6f050"
            .equals(ArchiveStore.key(new PhotoRepository.Photo(null, null, null, 0, 0, 0))),
        "null metadata keeps historical string concatenation");

    PhotoMoveStore.Identity persisted = new PhotoMoveStore.Identity("stored-key", "fixture-tag");
    PhotoRepository.Photo rebound = ordinary.withIdentity(persisted);
    require(
        "stored-key".equals(ArchiveStore.key(rebound)), "stored identity overrides a warmed key");
    require(
        "stored-key"
            .equals(
                ArchiveStore.key(
                    new PhotoRepository.Photo(
                        Uri.parse("file:///gallery-key-compatibility/moved.jpg"),
                        "moved.jpg",
                        "Other/",
                        5,
                        6,
                        7,
                        8,
                        9,
                        persisted))),
        "moved metadata cannot overwrite the stored key");
    require(
        ArchiveStore.key(ordinary.withIdentity(new PhotoMoveStore.Identity(null, "fixture-tag")))
            == null,
        "null stored key has no legacy fallback");
    require(
        ""
            .equals(
                ArchiveStore.key(
                    ordinary.withIdentity(new PhotoMoveStore.Identity("", "fixture-tag")))),
        "empty stored key has no legacy fallback");
    require(
        LEGACY.equals(ArchiveStore.key(rebound.withIdentity(null))),
        "removing the attached identity makes a fresh ordinary snapshot");
    require(LEGACY.equals(ArchiveStore.key(ordinary)), "rebinding preserves the original snapshot");
    try {
      ArchiveStore.key(null);
      throw new AssertionError("null Photo must fail");
    } catch (NullPointerException expected) {
      // Keep the existing failure for a null Photo, distinct from null metadata or a stored key.
    }
    checkConcurrentReads(ordinary.withIdentity(null));
    checkRecordingCorrelation(test.getTargetContext(), ordinary, rebound);

    Bundle off = new Bundle();
    off.putString("record", "false");
    off.putString("count", "true");
    String measured = GalleryPhotoKeyChecks.run(test, off);
    require(
        measured.contains("\nfirst archive=1024 diagnostic=0 ranking=1024 ")
            && measured.contains("\nrepeat archive=0 diagnostic=0 ranking=1024 "),
        "recording OFF gallery digest counts");
    return "PASS gallery-photo-key-compat historical keys, concurrent reads, stored identity, "
        + "recording ON preparation correlation and zero diagnostic hashes in recording OFF gallery binds\n";
  }

  private static void checkConcurrentReads(PhotoRepository.Photo photo) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch read = new CountDownLatch(1);
    Callable<String> key =
        () -> {
          ready.countDown();
          require(read.await(5, TimeUnit.SECONDS), "concurrent archive key release");
          return ArchiveStore.key(photo);
        };
    try {
      var first = executor.submit(key);
      var second = executor.submit(key);
      require(ready.await(5, TimeUnit.SECONDS), "concurrent archive key readers");
      read.countDown();
      require(
          LEGACY.equals(first.get(5, TimeUnit.SECONDS))
              && LEGACY.equals(second.get(5, TimeUnit.SECONDS)),
          "concurrent cold and cached reads return the legacy key");
    } finally {
      read.countDown();
      executor.shutdownNow();
    }
  }

  private static void checkRecordingCorrelation(
      Context context, PhotoRepository.Photo ordinary, PhotoRepository.Photo rebound)
      throws Exception {
    SharedPreferences settings = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE);
    boolean hadSetting = settings.contains("record");
    boolean previousSetting = settings.getBoolean("record", false);
    boolean start = DiagnosticLog.currentRecorder() == null;
    DiagnosticLog.Recorder ownedRecorder = null;
    long parent = DiagnosticLog.id();
    long previousParent = DiagnosticLog.use(parent);
    try {
      if (start) {
        require(DiagnosticLog.startRecording(context), "start diagnostic fixture recorder");
        ownedRecorder = DiagnosticLog.currentRecorder();
        require(ownedRecorder != null, "diagnostic fixture recorder is active");
      }
      long photoId = DiagnosticLog.photo(ordinary);
      require(photoId == DiagnosticLog.photo(rebound), "same URI in the same diagnostic session");
      PhotoRepository repository = new PhotoRepository(context);
      for (PhotoRepository.Photo photo : List.of(ordinary, rebound)) {
        AtomicBoolean entered = new AtomicBoolean();
        require(
            !repository.prepare(photo, () -> entered.getAndSet(true)), "prepared photo canceled");
      }
      awaitPreparationEvents(context, parent, photoId);
    } finally {
      DiagnosticLog.use(previousParent);
      if (start) {
        try {
          if (ownedRecorder != null) {
            require(
                DiagnosticLog.stopRecording(context, ownedRecorder),
                "stop diagnostic fixture recorder");
          }
        } finally {
          SharedPreferences.Editor restore = settings.edit();
          if (hadSetting) restore.putBoolean("record", previousSetting);
          else restore.remove("record");
          require(restore.commit(), "restore recording preference");
        }
      }
    }
  }

  private static void awaitPreparationEvents(Context context, long parent, long photoId)
      throws Exception {
    Path directory = context.getFilesDir().toPath().resolve("diagnostics");
    long deadline = SystemClock.uptimeMillis() + 3000;
    do {
      int started = 0;
      int canceled = 0;
      synchronized (DiagnosticLog.FILES) {
        for (String name : List.of("previous.jsonl", "events.jsonl")) {
          Path file = directory.resolve(name);
          if (!Files.isRegularFile(file)) continue;
          try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
              if (!line.contains("\"parent\":" + parent + ",")) continue;
              JSONObject event = new JSONObject(line);
              boolean start = "preparation.item_start".equals(event.optString("event"));
              boolean cancel = "preparation.item_cancel".equals(event.optString("event"));
              if (!start && !cancel) continue;
              require(event.optLong("photo", -1) == photoId, "preparation event pseudonym");
              if (start) started++;
              else canceled++;
            }
          }
        }
      }
      if (started == 2 && canceled == 2) return;
      SystemClock.sleep(25);
    } while (SystemClock.uptimeMillis() < deadline);
    throw new AssertionError("diagnostic preparation events not recorded");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
