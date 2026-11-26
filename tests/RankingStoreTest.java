package jp.nano.rawgallery;

import android.content.SharedPreferences;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import jp.nano.rawgallery.RankingStore.*;

/** Host-side storage contract tests; no Android disk-persistence claim. */
public final class RankingStoreTest {
  public static void main(String[] args) throws Exception {
    Preferences file = new Preferences();
    Map<String, Object> disk = file.disk;
    Map<String, Object> memory = file.memory;
    SharedPreferences preferences = file.preferences;
    List<String> requested = new ArrayList<>(List.of("写真C", "写真A", "写真B"));
    // Independently reproduce the old MainActivity key, rather than calling the new key helper.
    byte[] hash =
        MessageDigest.getInstance("SHA-256")
            .digest("写真A|写真B|写真C".getBytes(StandardCharsets.UTF_8));
    StringBuilder legacyKey = new StringBuilder("v1-");
    for (byte value : hash) legacyKey.append(String.format(Locale.ROOT, "%02x", value));
    disk.put(legacyKey.toString(), "2");
    memory.put(legacyKey.toString(), "2");
    RankingStore store = new RankingStore(preferences);
    PhotoRanking restored = store.load(requested);
    check(restored.journal.equals("2"), "legacy journal resumed");
    check(restored.ids.equals(List.of("写真A", "写真B", "写真C")), "canonical members");
    check(requested.get(0).equals("写真C"), "caller order untouched");
    PhotoRanking completed = restored.tie();
    check(store.save(completed), "save succeeds");
    check(file.commits.get() == 1 && disk.size() == 1, "synchronous commit to existing key");
    PhotoRanking reopened = file.reopen().load(List.of("写真B", "写真C", "写真A"));
    check(reopened.done() && reopened.best().size() == 3, "ties survive reopen and reordering");
    check(store.load(List.of("写真A", "写真B")).journal.isEmpty(), "changed members isolated");
    file.steps.add(Step.FAIL_AFTER);
    check(!store.save(completed.undo()), "commit failure returned to caller");
    check(completed.journal.equals("22"), "input comparison not mutated");
    check(store.load(requested).journal.equals("22"), "failed commit leaves last disk result intact");
    check(store.save(completed.undo()), "undo saved");
    check(store.load(requested).journal.equals("2"), "undo resumes");
    PhotoRanking best = new PhotoRanking(restored.ids, "B:").choose(true).choose(false);
    check(store.save(best), "best-only save");
    PhotoRanking bestReopened = file.reopen().load(requested);
    check(bestReopened.done() && bestReopened.best().equals(List.of("写真B")), "best-only winner survives reopen");
    check(bestReopened.rankOf("写真A") == 2 && bestReopened.rankOf("写真C") == 2, "tied second survives reopen");
    check(store.save(new PhotoRanking(restored.ids, "A:")), "all-rank mode saved before first choice");
    PhotoRanking allRankReopened = file.reopen().load(requested);
    check(!allRankReopened.bestOnly() && allRankReopened.decisions() == 0, "all-rank mode survives restart");
    check(store.load(requested).undo().journal.equals("A:"), "all-rank undo keeps method");
    PhotoRanking full = new PhotoRanking(restored.ids, "").choose(true).tie();
    while (!full.done()) full = full.tie();
    PhotoRanking promoted = full.addCoWinner(full.order.get(full.best().size() + 1));
    check(promoted.journal.startsWith("A:"), "co-winner result uses full-rank journal");
    check(store.save(promoted), "co-winner result saved");
    PhotoRanking coWinnerReopened = file.reopen().load(List.of("写真B", "写真A", "写真C"));
    check(coWinnerReopened.done() && coWinnerReopened.order.equals(promoted.order),
        "co-winner result reopens with reordered requested members");
    check(coWinnerReopened.journal.startsWith("A:") && coWinnerReopened.best().equals(promoted.best()),
        "co-winner journal and winner group replay after restart");
    int bulkStart = file.commits.get();
    PhotoRanking bulk = coWinnerReopened.addCoWinners(List.of("写真C", "写真A", "写真B", "写真C"));
    check(store.saveBulk(coWinnerReopened, bulk) && file.commits.get() == bulkStart + 1, "bulk result uses one commit");
    PhotoRanking bulkReopened = file.reopen().load(requested);
    check(disk.size() == 1 && bulkReopened.journal.startsWith("A:"), "existing key and format");
    check(Set.copyOf(bulkReopened.best()).equals(Set.copyOf(requested)), "bulk winners survive reopen");
    check(!store.saveBulk(coWinnerReopened, bulk), "stale confirmation cannot replace a newer journal");
    check(store.saveBulk(bulkReopened, bulkReopened), "unchanged confirmation succeeds");
    check(file.commits.get() == bulkStart + 1 && store.load(requested).journal.equals(bulkReopened.journal),
        "stale and unchanged confirmations do not write");
    check(store.save(best), "legacy best-only journal remains writable");
    check(store.load(requested).bestOnly(), "best-only history still reads after full-rank history");
    String photoKey = "singleton-photo";
    String singleKey = "single-best-v1-" + PhotoRepository.digest(photoKey);
    check(!store.singleBest(photoKey), "singleton defaults to false");
    check(store.saveSingleBest(photoKey, false, true), "singleton mark saved");
    check(store.singleBest(photoKey), "singleton mark reads true");
    check(store.saveSingleBest(photoKey, true, false), "singleton unmark saved");
    check(!file.reopen().singleBest(photoKey), "singleton unmark survives reopen");
    check(!store.singleBest("different-photo"), "singleton identities are isolated");
    check(store.load(requested).bestOnly(), "singleton state does not change burst journal");
    check(store.saveSingleBest(photoKey, false, true), "singleton marked again");
    check(!store.saveSingleBest(photoKey, false, false), "stale singleton expectation is rejected");
    check(store.singleBest(photoKey), "stale singleton write leaves current state intact");
    file.steps.add(Step.FAIL_AFTER);
    String unmarkedKey = "failed-singleton-photo";
    String unmarkedStorageKey = "single-best-v1-" + PhotoRepository.digest(unmarkedKey);
    check(!store.saveSingleBest(unmarkedKey, false, true), "failed singleton commit reported");
    check(!memory.containsKey(unmarkedStorageKey), "failed commit memory publish is rolled back by removal");
    check(!disk.containsKey(unmarkedStorageKey), "failed commit leaves disk value absent");
    check(!store.singleBest(unmarkedKey), "failed singleton remains unmarked");
    // Reopen from a separate simulated disk seeded with malformed legacy rows.
    Map<String, Object> corrupt = new HashMap<>(disk);
    corrupt.put(singleKey, "corrupt");
    corrupt.put(legacyKey.toString(), "invalid");
    file = new Preferences(corrupt);
    store = new RankingStore(file.preferences);
    int before = file.commits.get();
    try {
      store.singleBest(photoKey);
      throw new AssertionError("corrupt singleton accepted");
    } catch (java.io.IOException expected) {
      check(file.commits.get() == before, "corrupt singleton read not overwritten");
    }
    try {
      store.saveSingleBest(photoKey, false, true);
      throw new AssertionError("corrupt singleton overwritten");
    } catch (java.io.IOException expected) {
      check(file.commits.get() == before, "corrupt singleton write not committed");
    }
    try {
      store.load(requested);
      throw new AssertionError("corrupt journal accepted");
    } catch (IllegalArgumentException expected) {
      check(file.commits.get() == before, "corrupt journal not overwritten");
    }
    sharedNamespaceAcquisition();
    initialLoadAndRetry();
    committedSnapshotsAndCompare();
    repairBeforeOtherOperations();
    System.out.println("Ranking storage: legacy keys and journals, cold retry, snapshot isolation, serialized CAS and repair passed");
  }

  private static void sharedNamespaceAcquisition() throws Exception {
    Object namespace = new Object();
    Thread caller = Thread.currentThread();
    Preferences file = new Preferences();
    Gate acquisition = new Gate();
    AtomicInteger opens = new AtomicInteger();
    RankingBackend first = RankingBackend.forNamespace(namespace, () -> {
      check(Thread.currentThread() != caller, "preference acquisition happens off caller");
      opens.incrementAndGet();
      try { acquisition.enter(); } catch (InterruptedException e) { throw new AssertionError(e); }
      return file.preferences;
    });
    acquisition.awaitEntry();
    try {
      RankingBackend second = RankingBackend.forNamespace(namespace, () -> { throw new AssertionError(); });
      check(first == second && second.snapshot().phase() == Phase.PENDING,
          "second namespace handle returns while first acquisition is held");
      check(new RankingStore(new Preferences().preferences).awaitReady().phase() == Phase.READY,
          "independent namespace initialization can finish while another is held");
    } finally {
      acquisition.release();
    }
    check(first.awaitReady().phase() == Phase.READY && opens.get() == 1 && file.loads.get() == 1,
        "open and cold load each ran once for the namespace");
  }

  private static void initialLoadAndRetry() throws Exception {
    Preferences file = new Preferences();
    String markedKey = "single-best-v1-" + PhotoRepository.digest("marked");
    file.seed(markedKey, "1");
    file.loadGate = new Gate();
    file.failLoad = true;
    RankingStore store = new RankingStore(file.preferences);
    file.loadGate.awaitEntry();
    Snapshot pending = store.snapshot();
    check(pending.phase() == Phase.PENDING, "construction returns during cold load");
    expectReadFailure(() -> pending.singleBest("marked"));
    expectReadFailure(() -> pending.burst(List.of("X")));
    check(done(store.submitSingleBest("marked", true, false)).outcome() == Outcome.READ_FAILED,
        "an intent while pending cannot be applied later");

    BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
    AtomicInteger closedRefreshes = new AtomicInteger();
    Subscription closed = store.observe(callbacks::add, closedRefreshes::incrementAndGet);
    closed.close();
    Objects.requireNonNull(callbacks.poll(5, TimeUnit.SECONDS), "initial callback timed out").run();
    check(closedRefreshes.get() == 0, "queued initial callback observes close");
    BlockingQueue<Phase> phases = new LinkedBlockingQueue<>();
    try (Subscription listener = store.observe(Runnable::run, () -> phases.add(store.snapshot().phase()))) {
      check(phases.poll(5, TimeUnit.SECONDS) == Phase.PENDING, "observe delivers current state");
      file.loadGate.release();
      expectReadFailure(store::awaitReady);
      check(phases.poll(5, TimeUnit.SECONDS) == Phase.FAILED, "failed phase signalled");
      expectReadFailure(() -> store.snapshot().singleBest("marked"));
      check(done(store.submitSingleBest("marked", false, true)).outcome() == Outcome.READ_FAILED,
          "failed state rejects mutation");
      check(file.loads.get() == 1 && file.commits.get() == 0, "read failure does not retry itself");
      file.loadGate = new Gate();
      file.failLoad = false;
      store.retryLoad();
      file.loadGate.awaitEntry();
      store.retryLoad();
      check(phases.poll(5, TimeUnit.SECONDS) == Phase.PENDING && file.loads.get() == 2,
          "explicit retries share one pending load");
      file.loadGate.release();
      Snapshot ready = store.awaitReady();
      check(ready.phase() == Phase.READY && ready.revision() == 0 && ready.singleBest("marked"),
          "retry recovers the actual stored mark at initial revision");
      check(phases.poll(5, TimeUnit.SECONDS) == Phase.READY, "ready phase signalled");
      store.retryLoad();
      check(callbacks.isEmpty(), "closed subscription receives no later publication");
      check(file.loads.get() == 2 && file.commits.get() == 0 && file.disk.get(markedKey).equals("1"),
          "retry is read-only; rejected intent was never enqueued");
    }
  }

  private static void committedSnapshotsAndCompare() throws Exception {
    Preferences file = new Preferences();
    RankingStore store = new RankingStore(file.preferences);
    RankingStore second = new RankingStore(file.preferences);
    RankingStore copy = new RankingStore(second);
    RankingStore other = new RankingStore(new Preferences().preferences);
    Snapshot initial = store.awaitReady();
    check(second.snapshot() == initial && copy.snapshot() == initial && other.awaitReady() != initial,
        "same preference identity shares state, distinct identities isolate it");
    List<String> ids = new ArrayList<>(List.of("C", "A", "B"));
    Burst absent = initial.burst(ids);
    ids.clear();
    check(absent.ids().equals(List.of("A", "B", "C")) && absent.storedJournal() == null,
        "base captures canonical members and raw absence");
    PhotoRanking detached = absent.ranking();
    detached.order.clear();
    check(!absent.ranking().order.isEmpty(), "mutating a returned ranking cannot change the snapshot");
    check(done(other.submitBurst(absent, () -> { throw new AssertionError(); })).outcome() == Outcome.READ_FAILED,
        "base from another namespace cannot be applied");

    Gate gate = new Gate();
    file.commitGate = gate;
    CompletionStage<WriteResult> first = store.submitBurst(absent, () -> new PhotoRanking(absent.ids(), "B:").choose(true));
    gate.awaitEntry();
    CompletionStage<WriteResult> duplicate;
    CompletionStage<WriteResult> unrelated;
    try {
      Snapshot observed = CompletableFuture.supplyAsync(() -> {
        try { return second.awaitReady(); } catch (IOException e) { throw new CompletionException(e); }
      }).get(5, TimeUnit.SECONDS);
      check(observed == initial && second.snapshot().burst(absent.ids()).storedJournal() == null,
          "reader returns immediately while changed framework memory awaits acknowledgement");
      duplicate = second.submitBurst(absent, () -> { throw new AssertionError("stale supplier ran"); });
      unrelated = copy.submitSingleBest("one", false, true);
      CompletionStage<WriteResult> abandoned = second.submitSingleBest("two", false, true);
      abandoned.toCompletableFuture().cancel(true);
      check(file.commits.get() == 1 && file.disk.isEmpty() && !file.memory.isEmpty(),
          "later edits serialized; dirty memory alone remains unpublished");
    } finally {
      gate.release();
    }
    WriteResult saved = done(first);
    check(saved.outcome() == Outcome.SAVED && saved.snapshot().revision() == 1
        && saved.ranking().journal.equals("B:0"), "display B: can save against an absent raw basis");
    saved.ranking().order.clear();
    check(!saved.snapshot().burst(absent.ids()).ranking().order.isEmpty(), "successful result is detached");
    check(done(duplicate).outcome() == Outcome.CONFLICT, "second submission fails the raw compare");
    check(done(unrelated).snapshot().revision() == 2, "singleton commit advances shared revision");
    check(copy.saveSingleBest("two", true, true) && store.snapshot().revision() == 3,
        "dropping/cancelling caller's observation cannot cancel admitted work");
    check(!initial.singleBest("one") && !saved.snapshot().singleBest("two")
        && !saved.snapshot().singleBest("one"), "older snapshot remains immutable across keys");
    Burst unchangedBase = saved.snapshot().burst(absent.ids());
    int before = file.commits.get();
    WriteResult noOp = done(copy.submitBurst(unchangedBase, unchangedBase::ranking));
    check(noOp.outcome() == Outcome.UNCHANGED && noOp.ranking().journal.equals("B:0")
        && noOp.snapshot().revision() == 3 && file.commits.get() == before,
        "unrelated writes do not conflict and exact no-op does not publish");

    // Absent, explicit empty and displayed/stored mode defaults each have distinct CAS values.
    Burst blank = store.snapshot().burst(List.of("X", "Y"));
    WriteResult empty = done(store.submitBurst(blank, blank::ranking));
    check(empty.outcome() == Outcome.SAVED && empty.snapshot().burst(blank.ids()).storedJournal().equals(""),
        "explicit empty journal is persisted");
    check(done(store.submitBurst(blank, blank::ranking)).outcome() == Outcome.CONFLICT,
        "absent expectation cannot overwrite stored empty");
    Burst emptyBase = empty.snapshot().burst(blank.ids());
    WriteResult mode = done(store.submitBurst(emptyBase, () -> new PhotoRanking(blank.ids(), "B:")));
    check(mode.snapshot().burst(blank.ids()).storedJournal().equals("B:"), "stored mode distinct from empty");
    check(done(store.submitBurst(emptyBase, emptyBase::ranking)).outcome() == Outcome.CONFLICT,
        "empty expectation cannot overwrite stored mode");
    Burst current = mode.snapshot().burst(blank.ids());
    before = file.commits.get();
    check(done(store.submitBurst(current, () -> new PhotoRanking(List.of("other"), ""))).outcome() == Outcome.READ_FAILED
        && file.commits.get() == before, "mismatched candidate members rejected");
    check(!other.snapshot().singleBest("one"), "distinct namespace remained isolated");

    AtomicInteger publications = new AtomicInteger();
    try (Subscription good = second.observe(Runnable::run, publications::incrementAndGet);
         Subscription rejected = store.observe(run -> { throw new RejectedExecutionException(); }, () -> {});
         Subscription throwing = store.observe(Runnable::run, () -> { throw new IllegalStateException(); })) {
      Snapshot previous = store.snapshot();
      check(done(store.submitBurst(current, current::ranking)).outcome() == Outcome.UNCHANGED, "accepted no-op");
      WriteResult changed = done(store.submitBurst(current, () -> current.ranking().choose(false)));
      check(changed.outcome() == Outcome.SAVED && changed.snapshot().revision() == previous.revision() + 1
          && publications.get() == 2, "one callback for a change; observer failure cannot undo commit");
    }

    Gate interrupted = new Gate();
    file.commitGate = interrupted;
    CompletableFuture<Boolean> completed = new CompletableFuture<>();
    Thread caller = new Thread(() -> {
      try {
        boolean ok = second.saveSingleBest("interrupted", false, true);
        completed.complete(ok && Thread.currentThread().isInterrupted());
      } catch (Throwable e) { completed.completeExceptionally(e); }
    });
    caller.start();
    interrupted.awaitEntry();
    caller.interrupt();
    interrupted.release();
    check(completed.get(5, TimeUnit.SECONDS) && store.snapshot().singleBest("interrupted"),
        "interrupting compatibility waiter preserves interrupt and completes admitted write");
  }

  private static void repairBeforeOtherOperations() throws Exception {
    String original = "single-best-v1-" + PhotoRepository.digest("original");
    String unrelated = "single-best-v1-" + PhotoRepository.digest("unrelated");
    String badType = "v1-" + PhotoRepository.digest("X|Y");
    String badJournal = "v1-" + PhotoRepository.digest("M|N");
    Preferences file = new Preferences();
    file.seed(original, "0");
    file.seed(badType, 123);
    file.seed(badJournal, "?");
    file.seed("future-schema", Set.of("unrelated", "typed"));
    RankingStore store = new RankingStore(file.preferences);
    RankingStore second = new RankingStore(store);
    Snapshot initial = store.awaitReady();
    expectReadFailure(() -> initial.burst(List.of("Y", "X")));
    Burst malformed = initial.burst(List.of("M", "N"));
    check(done(store.submitBurst(malformed, () -> { throw new AssertionError("reset corrupt journal"); }))
        .outcome() == Outcome.READ_FAILED, "malformed journal cannot be overwritten by reset");

    file.steps.addAll(List.of(Step.THROW_AFTER, Step.THROW_BEFORE));
    check(done(store.submitSingleBest("original", false, true)).outcome() == Outcome.REPAIR_REQUIRED,
        "failure to repair a thrown commit reported");
    check(file.memory.get(original).equals("1") && file.disk.get(original).equals("0")
        && initial == second.snapshot() && !second.singleBest("original"), "failed intent isolated from readers");
    file.steps.add(Step.THROW_BEFORE);
    check(done(second.submitSingleBest("original", false, false)).outcome() == Outcome.REPAIR_REQUIRED,
        "a no-op cannot bypass failed repair");
    file.steps.add(Step.THROW_BEFORE);
    check(done(second.submitSingleBest("unrelated", false, true)).outcome() == Outcome.REPAIR_REQUIRED
        && !file.memory.containsKey(unrelated) && file.commits.get() == 4,
        "unrelated write may only attempt the outstanding repair");
    file.steps.addAll(List.of(Step.SUCCESS, Step.SUCCESS));
    WriteResult recovered = done(second.submitSingleBest("unrelated", false, true));
    check(recovered.outcome() == Outcome.SAVED && recovered.snapshot().revision() == 1 && file.commits.get() == 6,
        "one repair then the requested write; no repair publication or replay of failed intent");
    check(file.disk.get(original).equals("0") && file.disk.get(unrelated).equals("1")
        && file.disk.get(badType).equals(123) && file.disk.get(badJournal).equals("?")
        && file.disk.get("future-schema").equals(Set.of("unrelated", "typed")), "complete map flush preserves prior exact rows");

    String fresh = "single-best-v1-" + PhotoRepository.digest("new");
    file.steps.addAll(List.of(Step.FAIL_AFTER, Step.FAIL_AFTER));
    check(done(store.submitSingleBest("new", false, true)).outcome() == Outcome.REPAIR_REQUIRED,
        "false compensation after memory restoration still requires acknowledgement");
    int commits = file.commits.get();
    WriteResult repairedNoOp = done(store.submitSingleBest("new", false, false));
    check(repairedNoOp.outcome() == Outcome.UNCHANGED && file.commits.get() == commits + 1
        && !file.disk.containsKey(fresh) && store.snapshot() == recovered.snapshot(),
        "repair restores absent row before accepting no-op, retaining version");
    file.steps.add(Step.THROW_AFTER);
    check(done(store.submitSingleBest("new", false, true)).outcome() == Outcome.WRITE_FAILED
        && !file.memory.containsKey(fresh) && !file.disk.containsKey(fresh), "repaired async failure stays unpublished");
    file.steps.add(Step.THROW_AFTER);
    try {
      store.saveSingleBest("new", false, true);
      throw new AssertionError("synchronous thrown commit became false");
    } catch (IllegalStateException expected) {
      check(expected.getMessage().equals("injected commit"), "legacy caller receives the original write exception");
    }

    RankingStore restart = file.reopen();
    check(!restart.singleBest("original") && restart.singleBest("unrelated") && !restart.singleBest("new"),
        "fresh namespace loaded from simulated disk contains only acknowledged edits");
  }

  private static WriteResult done(CompletionStage<WriteResult> result) throws Exception {
    return result.toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private interface Read { void run() throws IOException; }
  private static void expectReadFailure(Read read) throws Exception {
    try { read.run(); } catch (IOException expected) { return; }
    throw new AssertionError("expected read failure");
  }

  private enum Step { SUCCESS, FAIL_AFTER, THROW_AFTER, THROW_BEFORE }

  private static final class Gate {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch released = new CountDownLatch(1);
    void enter() throws InterruptedException {
      entered.countDown();
      check(released.await(10, TimeUnit.SECONDS), "fixture gate timed out");
    }
    void awaitEntry() throws InterruptedException {
      check(entered.await(5, TimeUnit.SECONDS), "worker did not enter fixture gate");
    }
    void release() { released.countDown(); }
  }

  /** Editors stage independent key changes. Each successful commit flushes the full current memory map. */
  private static final class Preferences {
    final Map<String, Object> memory = new ConcurrentHashMap<>();
    final Map<String, Object> disk = new ConcurrentHashMap<>();
    final Queue<Step> steps = new ConcurrentLinkedQueue<>();
    final AtomicInteger commits = new AtomicInteger();
    final AtomicInteger loads = new AtomicInteger();
    volatile Gate loadGate, commitGate;
    volatile boolean failLoad;

    Preferences() {}
    Preferences(Map<String, Object> persisted) { disk.putAll(persisted); memory.putAll(persisted); }
    RankingStore reopen() { return new RankingStore(new Preferences(disk).preferences); }

    final SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
        getClass().getClassLoader(), new Class<?>[] {SharedPreferences.class}, (proxy, method, args) -> {
          switch (method.getName()) {
            case "getAll":
              loads.incrementAndGet();
              Gate gate = loadGate;
              if (gate != null) gate.enter();
              if (failLoad) throw new IllegalStateException("injected load");
              return new HashMap<>(memory);
            case "edit":
              return editor();
            default:
              throw new AssertionError("Unexpected preference call " + method.getName());
          }
        });

    void seed(String key, Object value) { memory.put(key, value); disk.put(key, value); }

    private SharedPreferences.Editor editor() {
      Map<String, String> changes = new HashMap<>();
      return (SharedPreferences.Editor) Proxy.newProxyInstance(getClass().getClassLoader(),
          new Class<?>[] {SharedPreferences.Editor.class}, (proxy, method, args) -> {
            switch (method.getName()) {
              case "putString":
                changes.put((String) args[0], (String) args[1]);
                return proxy;
              case "remove":
                changes.put((String) args[0], null);
                return proxy;
              case "commit":
                return commit(changes);
              default:
                throw new AssertionError("Unexpected editor call " + method.getName());
            }
          });
    }

    private boolean commit(Map<String, String> changes) throws InterruptedException {
      commits.incrementAndGet();
      Step step = steps.poll();
      if (step == Step.THROW_BEFORE) throw new IllegalStateException("injected commit");
      changes.forEach((key, value) -> {
        if (value == null) memory.remove(key);
        else memory.put(key, value);
      });
      Gate gate = commitGate;
      commitGate = null;
      if (gate != null) gate.enter();
      if (step == Step.THROW_AFTER) throw new IllegalStateException("injected commit");
      if (step == Step.FAIL_AFTER) return false;
      disk.clear();
      disk.putAll(memory);
      return true;
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
