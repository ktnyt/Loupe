package jp.nano.rawgallery;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Host-side storage contract tests; no Android disk-persistence claim. */
public final class RankingStoreTest {
  public static void main(String[] args) throws Exception {
    Map<String, String> disk = new HashMap<>();
    Map<String, String> memory = new HashMap<>();
    boolean[] commitSucceeds = {true};
    boolean[] publishBeforeFailedCommit = {false};
    int[] commits = {0};
    Map<String, Optional<String>> pending = new HashMap<>();
    SharedPreferences.Editor editor =
        (SharedPreferences.Editor)
            Proxy.newProxyInstance(
                RankingStoreTest.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.Editor.class},
                (proxy, method, values) -> {
                  switch (method.getName()) {
                    case "putString":
                      pending.put((String) values[0], Optional.of((String) values[1]));
                      return proxy;
                    case "remove":
                      pending.put((String) values[0], Optional.empty());
                      return proxy;
                    case "commit":
                      commits[0]++;
                      if (commitSucceeds[0] || publishBeforeFailedCommit[0]) {
                        for (Map.Entry<String, Optional<String>> change : pending.entrySet()) {
                          if (change.getValue().isPresent()) memory.put(change.getKey(), change.getValue().get());
                          else memory.remove(change.getKey());
                        }
                      }
                      if (commitSucceeds[0]) {
                        for (Map.Entry<String, Optional<String>> change : pending.entrySet()) {
                          if (change.getValue().isPresent()) disk.put(change.getKey(), change.getValue().get());
                          else disk.remove(change.getKey());
                        }
                      }
                      pending.clear();
                      return commitSucceeds[0];
                    default:
                      throw new AssertionError("Unexpected storage call: " + method.getName());
                  }
                });
    SharedPreferences preferences =
        (SharedPreferences)
            Proxy.newProxyInstance(
                RankingStoreTest.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class},
                (proxy, method, values) -> {
                  switch (method.getName()) {
                    case "getString":
                      return memory.getOrDefault((String) values[0], (String) values[1]);
                    case "edit":
                      return editor;
                    default:
                      throw new AssertionError("Unexpected storage call: " + method.getName());
                  }
                });
    RankingStore store = new RankingStore(preferences);
    List<String> requested = new ArrayList<>(List.of("写真C", "写真A", "写真B"));
    // Independently reproduce the old MainActivity key, rather than calling the new key helper.
    byte[] hash =
        MessageDigest.getInstance("SHA-256")
            .digest("写真A|写真B|写真C".getBytes(StandardCharsets.UTF_8));
    StringBuilder legacyKey = new StringBuilder("v1-");
    for (byte value : hash) legacyKey.append(String.format(Locale.ROOT, "%02x", value));
    disk.put(legacyKey.toString(), "2");
    memory.put(legacyKey.toString(), "2");
    PhotoRanking restored = store.load(requested);
    check(restored.journal.equals("2"), "legacy journal resumed");
    check(restored.ids.equals(List.of("写真A", "写真B", "写真C")), "canonical members");
    check(requested.get(0).equals("写真C"), "caller order untouched");
    PhotoRanking completed = restored.tie();
    check(store.save(completed), "save succeeds");
    check(commits[0] == 1 && disk.size() == 1, "synchronous commit to existing key");
    PhotoRanking reopened = store.load(List.of("写真B", "写真C", "写真A"));
    check(reopened.done() && reopened.best().size() == 3, "ties survive reopen and reordering");
    check(store.load(List.of("写真A", "写真B")).journal.isEmpty(), "changed members isolated");
    commitSucceeds[0] = false;
    check(!store.save(completed.undo()), "commit failure returned to caller");
    check(completed.journal.equals("22"), "input comparison not mutated");
    check(store.load(requested).journal.equals("22"), "failed commit leaves last disk result intact");
    commitSucceeds[0] = true;
    check(store.save(completed.undo()), "undo saved");
    check(store.load(requested).journal.equals("2"), "undo resumes");
    PhotoRanking best = new PhotoRanking(restored.ids, "B:").choose(true).choose(false);
    check(store.save(best), "best-only save");
    PhotoRanking bestReopened = store.load(requested);
    check(bestReopened.done() && bestReopened.best().equals(List.of("写真B")), "best-only winner survives reopen");
    check(bestReopened.rankOf("写真A") == 2 && bestReopened.rankOf("写真C") == 2, "tied second survives reopen");
    check(store.save(new PhotoRanking(restored.ids, "A:")), "all-rank mode saved before first choice");
    check(!store.load(requested).bestOnly() && store.load(requested).decisions() == 0, "all-rank mode survives restart");
    check(store.load(requested).undo().journal.equals("A:"), "all-rank undo keeps method");
    PhotoRanking full = new PhotoRanking(restored.ids, "").choose(true).tie();
    while (!full.done()) full = full.tie();
    PhotoRanking promoted = full.addCoWinner(full.order.get(full.best().size() + 1));
    check(promoted.journal.startsWith("A:"), "co-winner result uses full-rank journal");
    check(store.save(promoted), "co-winner result saved");
    PhotoRanking coWinnerReopened = store.load(List.of("写真B", "写真A", "写真C"));
    check(coWinnerReopened.done() && coWinnerReopened.order.equals(promoted.order),
        "co-winner result reopens with reordered requested members");
    check(coWinnerReopened.journal.startsWith("A:") && coWinnerReopened.best().equals(promoted.best()),
        "co-winner journal and winner group replay after restart");
    int bulkStart = commits[0];
    PhotoRanking bulk = coWinnerReopened.addCoWinners(List.of("写真C", "写真A", "写真B", "写真C"));
    check(store.saveBulk(coWinnerReopened, bulk) && commits[0] == bulkStart + 1, "bulk result uses one commit");
    PhotoRanking bulkReopened = new RankingStore(preferences).load(requested);
    check(disk.size() == 1 && bulkReopened.journal.startsWith("A:"), "existing key and format");
    check(Set.copyOf(bulkReopened.best()).equals(Set.copyOf(requested)), "bulk winners survive reopen");
    check(!store.saveBulk(coWinnerReopened, bulk), "stale confirmation cannot replace a newer journal");
    check(store.saveBulk(bulkReopened, bulkReopened), "unchanged confirmation succeeds");
    check(commits[0] == bulkStart + 1 && store.load(requested).journal.equals(bulkReopened.journal),
        "stale and unchanged confirmations do not write");
    check(store.save(best), "legacy best-only journal remains writable");
    check(store.load(requested).bestOnly(), "best-only history still reads after full-rank history");
    String photoKey = "singleton-photo";
    String singleKey = "single-best-v1-" + PhotoRepository.digest(photoKey);
    check(!store.singleBest(photoKey), "singleton defaults to false");
    check(store.saveSingleBest(photoKey, false, true), "singleton mark saved");
    check(store.singleBest(photoKey), "singleton mark reads true");
    check(store.saveSingleBest(photoKey, true, false), "singleton unmark saved");
    check(!new RankingStore(preferences).singleBest(photoKey), "singleton unmark survives reopen");
    check(!store.singleBest("different-photo"), "singleton identities are isolated");
    check(store.load(requested).bestOnly(), "singleton state does not change burst journal");
    check(store.saveSingleBest(photoKey, false, true), "singleton marked again");
    check(!store.saveSingleBest(photoKey, false, false), "stale singleton expectation is rejected");
    check(store.singleBest(photoKey), "stale singleton write leaves current state intact");
    commitSucceeds[0] = false;
    String unmarkedKey = "failed-singleton-photo";
    String unmarkedStorageKey = "single-best-v1-" + PhotoRepository.digest(unmarkedKey);
    publishBeforeFailedCommit[0] = true;
    check(!store.saveSingleBest(unmarkedKey, false, true), "failed singleton commit reported");
    check(!memory.containsKey(unmarkedStorageKey), "failed commit memory publish is rolled back by removal");
    check(!disk.containsKey(unmarkedStorageKey), "failed commit leaves disk value absent");
    check(!store.singleBest(unmarkedKey), "failed singleton remains unmarked");
    commitSucceeds[0] = true;
    disk.put(singleKey, "corrupt");
    memory.put(singleKey, "corrupt");
    int before = commits[0];
    try {
      store.singleBest(photoKey);
      throw new AssertionError("corrupt singleton accepted");
    } catch (java.io.IOException expected) {
      check(commits[0] == before, "corrupt singleton read not overwritten");
    }
    try {
      store.saveSingleBest(photoKey, false, true);
      throw new AssertionError("corrupt singleton overwritten");
    } catch (java.io.IOException expected) {
      check(commits[0] == before, "corrupt singleton write not committed");
    }
    disk.put(legacyKey.toString(), "invalid");
    memory.put(legacyKey.toString(), "invalid");
    before = commits[0];
    try {
      store.load(requested);
      throw new AssertionError("corrupt journal accepted");
    } catch (IllegalArgumentException expected) {
      check(commits[0] == before, "corrupt journal not overwritten");
    }
    System.out.println("Ranking storage: burst journals, singleton state, stale writes, rollback and corruption passed");
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
