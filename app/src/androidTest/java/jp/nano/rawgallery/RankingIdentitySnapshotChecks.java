package jp.nano.rawgallery;

import static jp.nano.rawgallery.TagDetailChecks.require;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Isolated proof that only new competing identities need ranking readiness. */
final class RankingIdentitySnapshotChecks {
  static String run(Instrumentation test) throws Exception {
    RankingPersistenceWaitChecks.requireEmptyAvd(test);
    Context base = test.getTargetContext();
    Scoped context = new Scoped(base);
    SharedPreferences prefs = context.getSharedPreferences("ranking-proof", 0);
    ExecutorService scans = Executors.newSingleThreadExecutor();
    ExecutorService probes = Executors.newSingleThreadExecutor();
    LoadGate gate = new LoadGate();
    PhotoMoveStore moves = new PhotoMoveStore(context);
    RankingStore owner = null;
    try {
      PhotoRepository.Photo media = media(24901, "FIRST.JPG"), display = document("FIRST.JPG");
      String mediaKey = ArchiveStore.key(media), displayKey = ArchiveStore.key(display);
      require(!mediaKey.equals(displayKey), "distinct historical representation keys");
      require(
          prefs
              .edit()
              .putString("single-best-v1-" + PhotoRepository.digest(mediaKey), "1")
              .commit(),
          "seed isolated legacy media rank before owner initialization");
      owner = new RankingStore(gate.wrap(prefs));
      RankingStore unboundOwner = owner;
      require(gate.entered.await(2, TimeUnit.SECONDS), "ranking initialization paused");
      require(owner.snapshot().phase() == RankingStore.Phase.PENDING, "truthful pending state");
      Map<String, PhotoRepository.Photo> visible = new HashMap<>(Map.of("first", display));
      CountDownLatch started = new CountDownLatch(1);
      AtomicReference<Thread> scanThread = new AtomicReference<>();
      Future<?> resolving =
          scans.submit(
              () -> {
                scanThread.set(Thread.currentThread());
                started.countDown();
                PhotoIdentityIndex.resolve(
                    context, moves, visible, Map.of("first", media), unboundOwner);
                return null;
              });
      require(started.await(1, TimeUnit.SECONDS), "identity scan started");
      long deadline = android.os.SystemClock.uptimeMillis() + 2000;
      boolean waiting = false;
      do {
        for (StackTraceElement frame : scanThread.get().getStackTrace()) {
          waiting |=
              frame.getClassName().equals(RankingStore.class.getName())
                  && frame.getMethodName().equals("awaitReady");
        }
        if (waiting) break;
        Thread.sleep(8);
      } while (android.os.SystemClock.uptimeMillis() < deadline);
      require(waiting && !resolving.isDone(), "identity scan reached held ranking readiness");
      // A separate real SQLite writer must finish while the ranking loader is still held.
      probes
          .submit(
              () -> {
                try (PhotoMoveStore probe = new PhotoMoveStore(context)) {
                  SQLiteDatabase db = probe.getWritableDatabase();
                  db.beginTransactionNonExclusive();
                  try {
                    db.execSQL("UPDATE revision SET value=value WHERE id=1");
                    db.setTransactionSuccessful();
                  } finally {
                    db.endTransaction();
                  }
                }
                return null;
              })
          .get(2, TimeUnit.SECONDS);
      require(
          !resolving.isDone() && rows(moves) == 0,
          "pending ranking leaves identity unbound and SQLite writer available");
      gate.release.countDown();
      resolving.get(5, TimeUnit.SECONDS);
      require(
          rows(moves) == 1 && mediaKey.equals(ArchiveStore.key(visible.get("first"))),
          "ready snapshot preserves marked media identity");
      require(
          owner.snapshot().revision() == 0 && gate.reads.get() == 1 && prefs.getAll().size() == 1,
          "identity read does not save or reload ranking");

      PhotoMoveStore.Identity first = visible.get("first").movedIdentity;
      verifyBoundWhileHeld(context, moves, scans, first);

      SharedPreferences failedPrefs = context.getSharedPreferences("failed-ranking-proof", 0);
      AtomicInteger failures = new AtomicInteger(1);
      SharedPreferences failing =
          proxy(
              failedPrefs,
              () -> {
                if (failures.getAndDecrement() > 0)
                  throw new IllegalStateException("Synthetic load failure");
              });
      RankingStore failed = new RankingStore(failing);
      try {
        failed.awaitReady();
        throw new AssertionError("failed initialization must not become an empty ranking");
      } catch (IOException expected) {
        require(failed.snapshot().phase() == RankingStore.Phase.FAILED, "failed state retained");
      }
      verifyBoundWithFailedLoad(context, moves, failed, first);
      int boundRows = rows(moves);
      Map<String, PhotoRepository.Photo> pending =
          new HashMap<>(Map.of("second", document("SECOND.JPG")));
      try {
        PhotoIdentityIndex.resolve(
            context, moves, pending, Map.of("second", media(24902, "SECOND.JPG")), failed);
        throw new AssertionError("failed rank load must not bind a competing identity");
      } catch (IOException expected) {
        require(
            rows(moves) == boundRows && failedPrefs.getAll().isEmpty(),
            "failed read leaves all rows intact");
      }
      failed.retryLoad();
      failed.awaitReady();
      PhotoIdentityIndex.resolve(
          context, moves, pending, Map.of("second", media(24902, "SECOND.JPG")), failed);
      require(
          rows(moves) == boundRows + 1 && failedPrefs.getAll().isEmpty(),
          "read-only retry permits fresh binding");
      return "PASS ranking identity snapshot pendingWriterAvailable=true markedMediaRetained=true"
          + " existingHeldLoadSkipped=true exactAndLegacyFailedLoadSkipped=true"
          + " failedLoadNoBinding=true retryReadOnly=true defaultDataUntouched=true";
    } finally {
      gate.release.countDown();
      scans.shutdown();
      probes.shutdown();
      try {
        require(
            scans.awaitTermination(6, TimeUnit.SECONDS)
                && probes.awaitTermination(6, TimeUnit.SECONDS),
            "identity proof tasks drained");
        if (owner != null) owner.awaitReady();
      } finally {
        moves.close();
        context.clean();
        RankingPersistenceWaitChecks.requireEmptyAvd(test);
      }
    }
  }

  private static void verifyBoundWhileHeld(
      Context context, PhotoMoveStore moves, ExecutorService scans, PhotoMoveStore.Identity first)
      throws Exception {
    LoadGate held = new LoadGate();
    RankingStore owner =
        new RankingStore(held.wrap(context.getSharedPreferences("held-bound-ranking-proof", 0)));
    try {
      require(held.entered.await(2, TimeUnit.SECONDS), "second ranking owner reached held load");
      Map<String, PhotoRepository.Photo> visible =
          new HashMap<>(Map.of("first", document("FIRST.JPG")));
      scans
          .submit(
              () -> {
                PhotoIdentityIndex.resolve(
                    context, moves, visible, Map.of("first", media(24901, "FIRST.JPG")), owner);
                return null;
              })
          .get(2, TimeUnit.SECONDS);
      require(
          first.equals(visible.get("first").movedIdentity)
              && rows(moves) == 1
              && owner.snapshot().phase() == RankingStore.Phase.PENDING
              && held.reads.get() == 1,
          "reconstructed bound pair resolves unchanged while a different owner is held");
    } finally {
      held.release.countDown();
      owner.awaitReady();
    }
  }

  private static void verifyBoundWithFailedLoad(
      Context context, PhotoMoveStore moves, RankingStore failed, PhotoMoveStore.Identity first)
      throws Exception {
    var v1File = media(24903, "OLD-PATH.JPG");
    var v1Display = document("OLD-PATH.JPG");
    var versionlessFile = media(24904, "OLD-VERSION.JPG");
    var v1 = new PhotoMoveStore.Identity("old-path-archive", "old-path-tag", 42);
    var versionless = new PhotoMoveStore.Identity("old-version-archive", "old-version-tag", 43);
    SQLiteDatabase db = moves.getWritableDatabase();
    seedOldBinding(db, TagStore.locationSource(v1Display), v1);
    seedOldBinding(db, PhotoIdentityIndex.location(versionlessFile), versionless);
    Map<String, PhotoRepository.Photo> visible =
        new HashMap<>(
            Map.of(
                "first", document("FIRST.JPG"),
                "old-path", v1Display,
                "old-version", document("OLD-VERSION.JPG")));
    PhotoIdentityIndex.resolve(
        context,
        moves,
        visible,
        Map.of(
            "first", media(24901, "FIRST.JPG"),
            "old-path", v1File,
            "old-version", versionlessFile),
        failed);
    require(
        first.equals(visible.get("first").movedIdentity)
            && v1.equals(visible.get("old-path").movedIdentity)
            && versionless.equals(visible.get("old-version").movedIdentity)
            && rows(moves) == 3
            && failed.snapshot().phase() == RankingStore.Phase.FAILED,
        "exact and legacy bindings preserve archive and tag identities without a ranking retry");
    for (var file : new PhotoRepository.Photo[] {v1File, versionlessFile}) {
      try (var row =
          db.rawQuery(
              "SELECT media_version FROM local_photos WHERE location=?",
              new String[] {PhotoIdentityIndex.location(file)})) {
        require(
            row.moveToFirst()
                && PhotoIdentityIndex.mediaVersion(file).equals(row.getString(0))
                && !row.moveToNext(),
            "existing binding migrated to the recognized MediaStore version");
      }
    }
  }

  private static void seedOldBinding(
      SQLiteDatabase db, String location, PhotoMoveStore.Identity identity) {
    db.execSQL(
        "INSERT INTO local_photos VALUES(?,?,?,?,?,?)",
        new Object[] {
          location, "", identity.archiveKey(), identity.tagSource(), identity.sortTime(), null
        });
  }

  private static int rows(PhotoMoveStore moves) {
    try (var cursor =
        moves.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM local_photos", null)) {
      require(cursor.moveToFirst(), "identity row count available");
      return cursor.getInt(0);
    }
  }

  private static PhotoRepository.Photo media(long id, String name) {
    return new PhotoRepository.Photo(
        Uri.parse("content://media/external_primary/file/" + id), name, "DCIM/", 32, 123, 123000);
  }

  private static PhotoRepository.Photo document(String name) {
    return new PhotoRepository.Photo(
        DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:DCIM/" + name),
        name,
        "primary:DCIM/",
        32,
        123,
        123000);
  }

  private static SharedPreferences proxy(SharedPreferences actual, Runnable beforeLoad) {
    return (SharedPreferences)
        Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(),
            new Class<?>[] {SharedPreferences.class},
            (proxy, method, args) -> {
              if (method.getName().equals("getAll")) beforeLoad.run();
              try {
                return method.invoke(actual, args);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  private static final class LoadGate {
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    final AtomicInteger reads = new AtomicInteger();

    SharedPreferences wrap(SharedPreferences actual) {
      return proxy(
          actual,
          () -> {
            reads.incrementAndGet();
            entered.countDown();
            try {
              if (!release.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Load gate timed out");
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(interrupted);
            }
          });
    }
  }

  private static final class Scoped extends ContextWrapper {
    private final String prefix = "ranking-identity-" + UUID.randomUUID() + "-";
    private final Set<String> databases = new HashSet<>(), preferences = new HashSet<>();

    Scoped(Context base) {
      super(base);
    }

    @Override
    public Context getApplicationContext() {
      return this;
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
      preferences.add(prefix + name);
      return super.getSharedPreferences(prefix + name, mode);
    }

    @Override
    public File getDatabasePath(String name) {
      return super.getDatabasePath(prefix + name);
    }

    @Override
    public synchronized SQLiteDatabase openOrCreateDatabase(
        String name, int mode, SQLiteDatabase.CursorFactory factory) {
      databases.add(prefix + name);
      return super.openOrCreateDatabase(prefix + name, mode, factory);
    }

    @Override
    public synchronized SQLiteDatabase openOrCreateDatabase(
        String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errors) {
      databases.add(prefix + name);
      return super.openOrCreateDatabase(prefix + name, mode, factory, errors);
    }

    void clean() {
      Context base = getBaseContext();
      for (String name : databases) {
        require(
            base.deleteDatabase(name) || !base.getDatabasePath(name).exists(),
            "isolated database cleanup");
      }
      for (String name : preferences) {
        require(
            base.deleteSharedPreferences(name)
                || base.getSharedPreferences(name, 0).getAll().isEmpty(),
            "isolated preference cleanup");
      }
    }
  }
}
