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

/** Isolated proof that ranking initialization cannot hold the identity writer transaction. */
final class RankingIdentitySnapshotChecks {
  static String run(Instrumentation test) throws Exception {
    RankingPersistenceWaitChecks.requireEmptyAvd(test);
    Context base = test.getTargetContext();
    Scoped context = new Scoped(base);
    SharedPreferences prefs = context.getSharedPreferences("ranking-proof", 0);
    ExecutorService scans = Executors.newSingleThreadExecutor();
    ExecutorService probes = Executors.newSingleThreadExecutor();
    LoadGate gate = new LoadGate();
    try (PhotoMoveStore moves = new PhotoMoveStore(context)) {
      PhotoRepository.Photo media = media(24901, "FIRST.JPG"), display = document("FIRST.JPG");
      String mediaKey = ArchiveStore.key(media), displayKey = ArchiveStore.key(display);
      require(!mediaKey.equals(displayKey), "distinct historical representation keys");
      require(prefs.edit().putString("single-best-v1-" + PhotoRepository.digest(mediaKey), "1").commit(),
          "seed isolated legacy media rank before owner initialization");
      RankingStore owner = new RankingStore(gate.wrap(prefs));
      require(gate.entered.await(2, TimeUnit.SECONDS), "ranking initialization paused");
      require(owner.snapshot().phase() == RankingStore.Phase.PENDING, "truthful pending state");
      Map<String, PhotoRepository.Photo> visible = new HashMap<>(Map.of("first", display));
      CountDownLatch started = new CountDownLatch(1);
      AtomicReference<Thread> scanThread = new AtomicReference<>();
      Future<?> resolving = scans.submit(() -> {
        scanThread.set(Thread.currentThread());
        started.countDown();
        PhotoIdentityIndex.resolve(context, moves, visible, Map.of("first", media), owner);
        return null;
      });
      require(started.await(1, TimeUnit.SECONDS), "identity scan started");
      long deadline = android.os.SystemClock.uptimeMillis() + 2000;
      boolean waiting = false;
      do {
        for (StackTraceElement frame : scanThread.get().getStackTrace()) {
          waiting |= frame.getClassName().equals(RankingStore.class.getName())
              && frame.getMethodName().equals("awaitReady");
        }
        if (waiting) break;
        Thread.sleep(8);
      } while (android.os.SystemClock.uptimeMillis() < deadline);
      require(waiting && !resolving.isDone(), "identity scan reached held ranking readiness");
      // A separate real SQLite writer must finish while the ranking loader is still held.
      probes.submit(() -> {
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
      }).get(2, TimeUnit.SECONDS);
      require(!resolving.isDone() && rows(moves) == 0,
          "pending ranking leaves identity unbound and SQLite writer available");
      gate.release.countDown();
      resolving.get(5, TimeUnit.SECONDS);
      require(rows(moves) == 1 && mediaKey.equals(ArchiveStore.key(visible.get("first"))),
          "ready snapshot preserves marked media identity");
      require(owner.snapshot().revision() == 0 && gate.reads.get() == 1 && prefs.getAll().size() == 1,
          "identity read does not save or reload ranking");

      SharedPreferences failedPrefs = context.getSharedPreferences("failed-ranking-proof", 0);
      AtomicInteger failures = new AtomicInteger(1);
      SharedPreferences failing = proxy(failedPrefs, () -> {
        if (failures.getAndDecrement() > 0) throw new IllegalStateException("Synthetic load failure");
      });
      RankingStore failed = new RankingStore(failing);
      try {
        failed.awaitReady();
        throw new AssertionError("failed initialization must not become an empty ranking");
      } catch (IOException expected) {
        require(failed.snapshot().phase() == RankingStore.Phase.FAILED, "failed state retained");
      }
      Map<String, PhotoRepository.Photo> pending =
          new HashMap<>(Map.of("second", document("SECOND.JPG")));
      try {
        PhotoIdentityIndex.resolve(context, moves, pending, Map.of("second", media(24902, "SECOND.JPG")), failed);
        throw new AssertionError("failed rank load must not bind a competing identity");
      } catch (IOException expected) {
        require(rows(moves) == 1 && failedPrefs.getAll().isEmpty(), "failed read leaves all rows intact");
      }
      failed.retryLoad();
      failed.awaitReady();
      PhotoIdentityIndex.resolve(context, moves, pending, Map.of("second", media(24902, "SECOND.JPG")), failed);
      require(rows(moves) == 2 && failedPrefs.getAll().isEmpty(), "read-only retry permits fresh binding");
      return "PASS ranking identity snapshot pendingWriterAvailable=true markedMediaRetained=true"
          + " failedLoadNoBinding=true retryReadOnly=true defaultDataUntouched=true";
    } finally {
      gate.release.countDown();
      scans.shutdown();
      probes.shutdown();
      try {
        require(scans.awaitTermination(6, TimeUnit.SECONDS)
            && probes.awaitTermination(6, TimeUnit.SECONDS), "identity proof tasks drained");
      } finally {
        context.clean();
        RankingPersistenceWaitChecks.requireEmptyAvd(test);
      }
    }
  }

  private static int rows(PhotoMoveStore moves) {
    try (var cursor = moves.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM local_photos", null)) {
      require(cursor.moveToFirst(), "identity row count available");
      return cursor.getInt(0);
    }
  }

  private static PhotoRepository.Photo media(long id, String name) {
    return new PhotoRepository.Photo(Uri.parse("content://media/external_primary/file/" + id),
        name, "DCIM/", 32, 123, 123000);
  }

  private static PhotoRepository.Photo document(String name) {
    return new PhotoRepository.Photo(DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents", "primary:DCIM/" + name),
        name, "primary:DCIM/", 32, 123, 123000);
  }

  private static SharedPreferences proxy(SharedPreferences actual, Runnable beforeLoad) {
    return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
        new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
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
      return proxy(actual, () -> {
        reads.incrementAndGet();
        entered.countDown();
        try {
          if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Load gate timed out");
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

    Scoped(Context base) { super(base); }
    @Override public Context getApplicationContext() { return this; }
    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
      preferences.add(prefix + name);
      return super.getSharedPreferences(prefix + name, mode);
    }
    @Override public File getDatabasePath(String name) { return super.getDatabasePath(prefix + name); }
    @Override public synchronized SQLiteDatabase openOrCreateDatabase(String name, int mode,
        SQLiteDatabase.CursorFactory factory) {
      databases.add(prefix + name);
      return super.openOrCreateDatabase(prefix + name, mode, factory);
    }
    @Override public synchronized SQLiteDatabase openOrCreateDatabase(String name, int mode,
        SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errors) {
      databases.add(prefix + name);
      return super.openOrCreateDatabase(prefix + name, mode, factory, errors);
    }
    void clean() {
      Context base = getBaseContext();
      for (String name : databases) {
        require(base.deleteDatabase(name) || !base.getDatabasePath(name).exists(), "isolated database cleanup");
      }
      for (String name : preferences) {
        require(base.deleteSharedPreferences(name)
            || base.getSharedPreferences(name, 0).getAll().isEmpty(), "isolated preference cleanup");
      }
    }
  }
}
