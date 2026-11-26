package jp.nano.rawgallery;

import android.content.SharedPreferences;
import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;
import jp.nano.rawgallery.RankingStore.*;

/** One process-owned lane per ranking namespace. Only its worker touches preferences and repair. */
final class RankingBackend {
  private static final Map<Object, RankingBackend> namespaces = new IdentityHashMap<>();

  static synchronized RankingBackend forNamespace(
      Object identity, Supplier<SharedPreferences> open) {
    return namespaces.computeIfAbsent(identity, ignored -> new RankingBackend(open));
  }

  private final Object namespace = new Object();
  private final Supplier<SharedPreferences> open;
  private final Executor worker;
  private volatile Thread workerThread;
  private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
  private volatile Snapshot state = Snapshot.unavailable(namespace, Phase.PENDING, null);
  private CompletableFuture<Snapshot> loading = new CompletableFuture<>();
  private SharedPreferences preferences;
  private Repair repair;

  private RankingBackend(Supplier<SharedPreferences> open) {
    this.open = open;
    worker =
        new ThreadPoolExecutor(
            0,
            1,
            30,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            run -> {
              Thread thread = new Thread(run, "RankingStore");
              thread.setDaemon(true);
              workerThread = thread;
              return thread;
            });
    startLoad(loading);
  }

  Snapshot snapshot() {
    return state;
  }

  Snapshot awaitReady() throws IOException {
    Snapshot current = state;
    if (current.phase() == Phase.PENDING) {
      CompletableFuture<Snapshot> attempt;
      synchronized (this) {
        attempt = loading;
        if (attempt == null) {
          current = state;
          current.requireReady();
          return current;
        }
      }
      if (Thread.currentThread() == workerThread && !attempt.isDone()) {
        throw new IllegalStateException("Cannot wait on ranking loader");
      }
      try {
        current = attempt.get();
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while loading rankings", error);
      } catch (ExecutionException error) {
        throw new IOException("Could not load rankings", error.getCause());
      }
    }
    current.requireReady();
    return current;
  }

  void retryLoad() {
    synchronized (this) {
      if (state.phase() != Phase.FAILED) return;
      loading = new CompletableFuture<>();
      state = Snapshot.unavailable(namespace, Phase.PENDING, null);
      startLoad(loading);
    }
    notifyObservers();
  }

  private void startLoad(CompletableFuture<Snapshot> attempt) {
    worker.execute(
        () -> {
          Snapshot loaded;
          try {
            preferences = open.get();
            loaded = Snapshot.loaded(namespace, preferences.getAll());
          } catch (RuntimeException | Error failure) {
            loaded = Snapshot.unavailable(namespace, Phase.FAILED, failure);
          }
          synchronized (this) {
            state = loaded;
            loading = null;
          }
          attempt.complete(loaded);
          notifyObservers();
        });
  }

  Subscription observe(Executor delivery, Runnable refresh) {
    Observer observer = new Observer(delivery, refresh);
    observers.add(observer);
    observer.dispatch();
    return observer;
  }

  private void notifyObservers() {
    for (Observer observer : observers) observer.dispatch();
  }

  private final class Observer implements Subscription {
    private final Executor delivery;
    private final Runnable refresh;
    private volatile boolean closed;

    Observer(Executor delivery, Runnable refresh) {
      this.delivery = delivery;
      this.refresh = refresh;
    }

    void dispatch() {
      try {
        delivery.execute(
            () -> {
              if (!closed) refresh.run();
            });
      } catch (RuntimeException | Error ignored) {
        // A rejected delivery or inline observer cannot change a storage acknowledgement.
      }
    }

    @Override
    public void close() {
      closed = true;
      observers.remove(this);
    }
  }

  interface Operation {
    Attempt prepare(Snapshot current) throws IOException;
  }

  record Attempt(WriteResult result, Throwable failure) {}

  private record Repair(String key, String previous) {}

  Attempt result(Outcome outcome, PhotoRanking ranking) {
    return new Attempt(new WriteResult(outcome, state, ranking), null);
  }

  private Attempt failed(Outcome outcome, Throwable failure) {
    return new Attempt(new WriteResult(outcome, state, null), failure);
  }

  CompletableFuture<Attempt> submit(Operation operation) {
    Snapshot atSubmission = state;
    if (atSubmission.phase() != Phase.READY) {
      return CompletableFuture.completedFuture(
          new Attempt(new WriteResult(Outcome.READ_FAILED, atSubmission, null), null));
    }
    CompletableFuture<Attempt> completion = new CompletableFuture<>();
    // The queued task belongs to this namespace, independent of any caller's future or lifecycle.
    worker.execute(
        () -> {
          try {
            completion.complete(execute(operation));
          } catch (Throwable failure) {
            completion.completeExceptionally(failure);
          }
        });
    return completion;
  }

  private Attempt execute(Operation operation) {
    if (repair != null) {
      Attempt failure = restore(null);
      if (failure != null) return failure;
    }
    try {
      return operation.prepare(state);
    } catch (IOException | IllegalArgumentException failure) {
      return failed(Outcome.READ_FAILED, failure);
    }
  }

  boolean writeAndWait(Operation operation) throws IOException {
    if (Thread.currentThread() == workerThread)
      throw new IllegalStateException("Cannot wait on ranking writer");
    awaitReady();
    Attempt attempt;
    try {
      // join does not interrupt a commit/repair or let the caller return before its final outcome.
      attempt = submit(operation).join();
    } catch (CompletionException error) {
      throw asIOException(error.getCause());
    }
    if (attempt.failure() != null) throw asIOException(attempt.failure());
    Outcome outcome = attempt.result().outcome();
    return outcome == Outcome.SAVED || outcome == Outcome.UNCHANGED;
  }

  private static IOException asIOException(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    if (failure instanceof Error error) throw error;
    if (failure instanceof IOException io) return io;
    return new IOException(failure);
  }

  Attempt commit(String key, String value, PhotoRanking ranking) throws IOException {
    String previous = state.raw(key);
    if (value.equals(previous)) return result(Outcome.UNCHANGED, ranking);
    Snapshot next = state.changed(key, value);
    Repair undo = new Repair(key, previous);
    boolean committed;
    Throwable failure = null;
    try {
      committed = edit(key, value);
    } catch (RuntimeException | Error error) {
      committed = false;
      failure = error;
    }
    if (!committed) {
      // A failed commit may already have modified framework memory. A later commit flushes all
      // keys.
      repair = undo;
      Attempt repairFailure = restore(failure);
      return repairFailure != null ? repairFailure : failed(Outcome.WRITE_FAILED, failure);
    }
    state = next;
    notifyObservers();
    return result(Outcome.SAVED, ranking);
  }

  /** Null means the previous value (including absence) was durably restored. */
  private Attempt restore(Throwable originalFailure) {
    try {
      if (edit(repair.key(), repair.previous())) {
        repair = null;
        return null;
      }
    } catch (RuntimeException | Error restoreFailure) {
      if (originalFailure == null)
        originalFailure = new IOException("Could not restore ranking", restoreFailure);
      else if (restoreFailure != originalFailure) originalFailure.addSuppressed(restoreFailure);
    }
    return failed(Outcome.REPAIR_REQUIRED, originalFailure);
  }

  private boolean edit(String key, String value) {
    SharedPreferences.Editor editor = preferences.edit();
    if (value == null) editor.remove(key);
    else editor.putString(key, value);
    return editor.commit();
  }
}
