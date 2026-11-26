package jp.nano.rawgallery;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Committed snapshots of the existing rankings preferences. See docs/current-design.md P2.2. */
final class RankingStore {
  private final RankingBackend backend;

  RankingStore(Context context) {
    Context app = Objects.requireNonNull(context.getApplicationContext());
    backend = RankingBackend.forNamespace(app, () -> app.getSharedPreferences("rankings", 0));
  }

  RankingStore(SharedPreferences preferences) {
    backend = RankingBackend.forNamespace(preferences, () -> preferences);
  }

  RankingStore(RankingStore shared) {
    backend = shared.backend;
  }

  enum Phase { PENDING, READY, FAILED }
  enum Outcome { SAVED, UNCHANGED, CONFLICT, READ_FAILED, WRITE_FAILED, REPAIR_REQUIRED }
  record WriteResult(Outcome outcome, Snapshot snapshot, PhotoRanking ranking) {}

  interface Subscription extends AutoCloseable {
    @Override void close();
  }

  static final class Snapshot {
    private final Object namespace;
    private final Phase phase;
    private final long revision;
    private final Map<String, String> values;
    private final Throwable readFailure;

    private Snapshot(Object namespace, Phase phase, long revision, Map<String, String> values, Throwable readFailure) {
      this.namespace = namespace;
      this.phase = phase;
      this.revision = revision;
      this.values = values;
      this.readFailure = readFailure;
    }

    static Snapshot unavailable(Object namespace, Phase phase, Throwable failure) {
      return new Snapshot(namespace, phase, 0, Map.of(), failure);
    }

    static Snapshot loaded(Object namespace, Map<String, ?> raw) {
      Map<String, String> values = new HashMap<>();
      raw.forEach((key, value) -> {
        if (key != null && (key.startsWith("single-best-v1-") || key.startsWith("v1-"))) {
          // A present null marks an unsupported type. Never retain mutable preference values.
          values.put(key, value instanceof String ? (String) value : null);
        }
      });
      return new Snapshot(namespace, Phase.READY, 0, values, null);
    }

    Snapshot changed(String key, String value) {
      Map<String, String> next = new HashMap<>(values);
      next.put(key, value);
      return new Snapshot(namespace, Phase.READY, revision + 1, next, null);
    }

    Phase phase() { return phase; }
    long revision() { return revision; }

    void requireReady() throws IOException {
      if (phase != Phase.READY) throw new IOException("Ranking data unavailable", readFailure);
    }

    String raw(String key) throws IOException {
      requireReady();
      String value = values.get(key);
      if (value == null && values.containsKey(key)) throw new IOException("Invalid ranking value type");
      return value;
    }

    boolean singleBest(String archiveKey) throws IOException {
      return decodeSingleBest(raw(singleBestKey(archiveKey)));
    }

    Burst burst(List<String> memberIds) throws IOException {
      requireReady();
      List<String> sorted = new ArrayList<>(memberIds);
      Collections.sort(sorted);
      String key = "v1-" + PhotoRepository.digest(String.join("|", sorted));
      return new Burst(namespace, key, List.copyOf(sorted), raw(key));
    }
  }

  static final class Burst {
    private final Object namespace;
    private final String key;
    private final List<String> ids;
    private final String storedJournal;

    private Burst(Object namespace, String key, List<String> ids, String storedJournal) {
      this.namespace = namespace;
      this.key = key;
      this.ids = ids;
      this.storedJournal = storedJournal;
    }

    List<String> ids() { return ids; }
    String storedJournal() { return storedJournal; }
    PhotoRanking ranking() { return new PhotoRanking(ids, storedJournal == null ? "" : storedJournal); }
  }

  private static String singleBestKey(String archiveKey) throws IOException {
    return "single-best-v1-" + PhotoRepository.digest(archiveKey);
  }

  private static boolean decodeSingleBest(String value) throws IOException {
    if (value == null || value.equals("0")) return false;
    if (value.equals("1")) return true;
    throw new IOException("Corrupt singleton best value");
  }

  Snapshot snapshot() { return backend.snapshot(); }
  Snapshot awaitReady() throws IOException { return backend.awaitReady(); }
  void retryLoad() { backend.retryLoad(); }
  Subscription observe(Executor delivery, Runnable refresh) { return backend.observe(delivery, refresh); }

  private RankingBackend.Operation singleChange(String archiveKey, boolean expected, boolean next) {
    return current -> {
      String key = singleBestKey(archiveKey);
      if (decodeSingleBest(current.raw(key)) != expected) return backend.result(Outcome.CONFLICT, null);
      if (expected == next) return backend.result(Outcome.UNCHANGED, null);
      return backend.commit(key, next ? "1" : "0", null);
    };
  }

  CompletionStage<WriteResult> submitSingleBest(String archiveKey, boolean expected, boolean next) {
    return backend.submit(singleChange(archiveKey, expected, next)).thenApply(RankingBackend.Attempt::result);
  }

  CompletionStage<WriteResult> submitBurst(Burst expected, Supplier<PhotoRanking> makeNext) {
    return backend.submit(current -> {
      if (expected.namespace != current.namespace) throw new IllegalArgumentException("Different ranking store");
      if (!Objects.equals(current.raw(expected.key), expected.storedJournal)) {
        return backend.result(Outcome.CONFLICT, null);
      }
      expected.ranking(); // Validate even if a reset or mode change supplies a fresh journal.
      PhotoRanking next = makeNext.get();
      if (!expected.ids.equals(next.ids)) throw new IllegalArgumentException("Different ranking members");
      return backend.commit(expected.key, next.journal, next);
    }).thenApply(RankingBackend.Attempt::result);
  }

  // Worker-only compatibility calls. Screen code uses a captured Snapshot and submits an intent.
  boolean singleBest(String archiveKey) throws IOException { return awaitReady().singleBest(archiveKey); }
  PhotoRanking load(List<String> ids) throws IOException { return awaitReady().burst(ids).ranking(); }

  boolean saveSingleBest(String archiveKey, boolean expected, boolean next) throws IOException {
    return backend.writeAndWait(singleChange(archiveKey, expected, next));
  }

  boolean save(PhotoRanking next) throws IOException {
    return backend.writeAndWait(current -> {
      Burst previous = current.burst(next.ids);
      previous.ranking();
      return backend.commit(previous.key, next.journal, next);
    });
  }

  boolean saveBulk(PhotoRanking original, PhotoRanking next) throws IOException {
    return backend.writeAndWait(current -> {
      if (!original.ids.equals(next.ids)) throw new IllegalArgumentException("Different ranking members");
      Burst previous = current.burst(original.ids);
      String journal = previous.storedJournal == null ? "" : previous.storedJournal;
      if (!original.journal.equals(journal)) return backend.result(Outcome.CONFLICT, null);
      if (original.journal.equals(next.journal)) return backend.result(Outcome.UNCHANGED, next);
      return backend.commit(previous.key, next.journal, next);
    });
  }
}
