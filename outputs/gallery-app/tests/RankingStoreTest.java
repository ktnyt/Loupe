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
    boolean[] commitSucceeds = {true};
    int[] commits = {0};
    Map<String, String> pending = new HashMap<>();
    SharedPreferences.Editor editor =
        (SharedPreferences.Editor)
            Proxy.newProxyInstance(
                RankingStoreTest.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.Editor.class},
                (proxy, method, values) -> {
                  switch (method.getName()) {
                    case "putString":
                      pending.put((String) values[0], (String) values[1]);
                      return proxy;
                    case "commit":
                      commits[0]++;
                      if (commitSucceeds[0]) disk.putAll(pending);
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
                      return disk.getOrDefault((String) values[0], (String) values[1]);
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
    disk.put(legacyKey.toString(), "invalid");
    int before = commits[0];
    try {
      store.load(requested);
      throw new AssertionError("corrupt journal accepted");
    } catch (IllegalArgumentException expected) {
      check(commits[0] == before, "corrupt journal not overwritten");
    }
    System.out.println("Ranking storage: legacy keys/journals, ties, membership, undo, commit failure and corruption passed");
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
