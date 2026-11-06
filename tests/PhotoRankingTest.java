package jp.nano.rawgallery;

import java.util.*;

public final class PhotoRankingTest {
  public static void main(String[] args) {
    Random random = new Random(42);
    for (int size = 2; size <= 60; size++) {
      List<String> ids = new ArrayList<>();
      for (int i = 0; i < size; i++) ids.add("best" + i);
      for (String winner : ids) {
        PhotoRanking best = new PhotoRanking(ids, "B:");
        if (best.decisions() != 0 || !best.undo().journal.equals("B:"))
          throw new AssertionError("best mode survives undo at start");
        while (!best.done()) {
          PhotoRanking previous = best;
          best = best.choose(best.upper().equals(winner));
          if (!best.undo().journal.equals(previous.journal)) throw new AssertionError("best undo");
          best = new PhotoRanking(ids, best.journal);
          if (!best.done() && !best.best().isEmpty()) throw new AssertionError("premature winner");
        }
        if (!best.best().equals(List.of(winner)) || best.decisions() != size - 1)
          throw new AssertionError("winner or comparison count");
        for (String id : ids) if (best.rankOf(id) != (id.equals(winner) ? 1 : 2))
          throw new AssertionError("remaining photos must tie at rank two");
      }
    }
    for (String bad : List.of("B:2", "B:00", "B:x")) {
      try {
        new PhotoRanking(List.of("a", "b"), bad);
        throw new AssertionError("invalid best journal");
      } catch (IllegalArgumentException expected) {}
    }
    System.out.println("Best-only: all winners for 2..60 photos, tied second, replay, undo, invalid journals passed");
    for (int size = 1; size <= 100; size++) {
      List<String> ids = new ArrayList<>();
      for (int i = 0; i < size; i++) ids.add("p" + i);
      List<String> desired = new ArrayList<>(ids);
      Collections.shuffle(desired, random);
      PhotoRanking s = new PhotoRanking(ids, "");
      while (!s.done()) {
        String before = s.journal;
        PhotoRanking next = s.choose(desired.indexOf(s.upper()) < desired.indexOf(s.lower()));
        if (!next.undo().journal.equals(before)) throw new AssertionError("undo");
        PhotoRanking restored = new PhotoRanking(ids, next.journal);
        if (!restored.order.equals(next.order)) throw new AssertionError("resume");
        s = restored;
      }
      if (!s.order.equals(desired)) throw new AssertionError("order size=" + size);
      if (s.journal.length() > size * 7) throw new AssertionError("comparison bound");
    }
    for (int size = 2; size <= 80; size++) {
      List<String> ids = new ArrayList<>();
      Map<String, Integer> scores = new HashMap<>();
      for (int i = 0; i < size; i++) {
        ids.add("t" + i);
        scores.put("t" + i, random.nextInt(5));
      }
      PhotoRanking r = new PhotoRanking(ids, "");
      while (!r.done()) {
        int a = scores.get(r.upper()), b = scores.get(r.lower());
        PhotoRanking n = a == b ? r.tie() : r.choose(a < b);
        if (!n.undo().order.equals(r.order)) throw new AssertionError("tie undo");
        r = new PhotoRanking(ids, n.journal);
      }
      int min = Collections.min(scores.values());
      Set<String> best = new HashSet<>();
      for (String id : ids) {
        if (scores.get(id) == min) best.add(id);
        int expected = 1;
        for (int score : scores.values()) if (score < scores.get(id)) expected++;
        if (r.rankOf(id) != expected) throw new AssertionError("competition rank");
      }
      if (!new HashSet<>(r.best()).equals(best)) throw new AssertionError("all tied winners");
    }
    if (!new PhotoRanking(List.of("a", "b"), "").best().isEmpty())
      throw new AssertionError("unfinished ranking has no winner");
    for (List<String> invalid : List.of(List.<String>of(), List.of("a", "a"))) {
      try {
        new PhotoRanking(invalid, "");
        throw new AssertionError("invalid members");
      } catch (IllegalArgumentException expected) {
      }
    }
    for (String bad : List.of("x", "00")) {
      try {
        new PhotoRanking(List.of("a", "b"), bad);
        throw new AssertionError("invalid journal");
      } catch (IllegalArgumentException expected) {
      }
    }
    System.out.println(
        "Photo ranking: 100 permutations, resume/undo, bounds and invalid input passed");
    coWinnerAdditions(random);
  }

  private static void coWinnerAdditions(Random random) {
    List<String> ids = List.of("a", "b", "c", "d", "e", "f");
    PhotoRanking unfinished = new PhotoRanking(ids, "");
    expect(IllegalStateException.class, () -> unfinished.addCoWinner("b"));
    PhotoRanking ranked = new PhotoRanking(ids, "").tie().choose(true).tie().choose(false);
    while (!ranked.done()) ranked = ranked.tie();
    check(ranked.done(), "fixture completed");
    PhotoRanking completed = ranked;
    expect(IllegalArgumentException.class, () -> completed.addCoWinner("missing"));

    List<String> oldOrder = List.copyOf(ranked.order);
    String oldJournal = ranked.journal;
    List<String> oldBest = ranked.best();
    PhotoRanking alreadyWinner = ranked.addCoWinner(oldBest.get(0));
    check(alreadyWinner.order.equals(oldOrder) && alreadyWinner.journal.equals(oldJournal),
        "adding existing winner leaves result and journal unchanged");
    check(ranked.order.equals(oldOrder) && ranked.journal.equals(oldJournal),
        "co-winner addition does not mutate original");

    String selected = ranked.order.get(ranked.best().size() + 1);
    int selectedOldRank = ranked.rankOf(selected);
    PhotoRanking expanded = ranked.addCoWinner(selected);
    Set<String> expectedWinners = new HashSet<>(oldBest);
    expectedWinners.add(selected);
    check(new HashSet<>(expanded.best()).equals(expectedWinners),
        "existing winners retained with selected photo");
    check(expanded.rankOf(selected) == 1 && expanded.journal.startsWith("A:"),
        "new completed result is full-rank A mode");
    for (String id : oldOrder) {
      if (expectedWinners.contains(id)) continue;
      check(expanded.rankOf(id) > 1, "lower groups remain below the expanded winner group");
    }
    check(selectedOldRank > 1, "fixture selects a lower-ranked photo");
    PhotoRanking replayed = new PhotoRanking(ids, expanded.journal);
    check(replayed.done() && replayed.order.equals(expanded.order), "A journal replays exact completed ordering");

    PhotoRanking twice = expanded.addCoWinner(expanded.order.get(expanded.best().size() + 1));
    check(twice.best().containsAll(expanded.best()), "repeated additions retain every prior winner");
    PhotoRanking everyone = twice;
    while (everyone.best().size() < ids.size()) {
      String next = everyone.order.get(everyone.best().size());
      everyone = everyone.addCoWinner(next);
    }
    check(everyone.best().size() == ids.size(), "every photo can become a co-winner");
    for (String id : ids) check(everyone.rankOf(id) == 1, "all-winner competition rank");

    for (int sample = 0; sample < 80; sample++) {
      List<String> members = new ArrayList<>();
      Map<String, Integer> scores = new HashMap<>();
      for (int i = 0; i < 2 + random.nextInt(20); i++) {
        String id = "s" + sample + "_" + i;
        members.add(id);
        scores.put(id, random.nextInt(6));
      }
      PhotoRanking source = new PhotoRanking(members, "");
      while (!source.done()) {
        int upper = scores.get(source.upper()), lower = scores.get(source.lower());
        source = upper == lower ? source.tie() : source.choose(upper < lower);
      }
      String selectedId = source.order.get(random.nextInt(source.order.size()));
      List<String> original = List.copyOf(source.order);
      PhotoRanking changed = source.addCoWinner(selectedId);
      Set<String> promoted = new HashSet<>(source.best());
      promoted.add(selectedId);
      check(new HashSet<>(changed.best()).equals(promoted), "score oracle retains and expands winner group");
      for (String id : members) {
        if (promoted.contains(id)) {
          check(changed.rankOf(id) == 1, "promoted co-winners share rank one");
          continue;
        }
      }
      Integer prior = null;
      int expectedRank = promoted.size() + 1;
      int groupSize = 0;
      for (String id : original) {
        if (promoted.contains(id)) continue;
        int score = scores.get(id);
        if (prior != null && score != prior) {
          expectedRank += groupSize;
          groupSize = 0;
        }
        check(changed.rankOf(id) == expectedRank, "co-winner competition ranks match score oracle");
        groupSize++;
        prior = score;
      }
      check(new PhotoRanking(members, changed.journal).order.equals(changed.order),
          "random completed A journal replays");
    }
    System.out.println("Co-winner additions: completion guards, immutable replay, stable groups and score oracle passed");
  }

  private static void expect(Class<? extends Throwable> type, Runnable action) {
    try {
      action.run();
      throw new AssertionError("Expected " + type.getSimpleName());
    } catch (Throwable actual) {
      if (!type.isInstance(actual)) throw actual;
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
