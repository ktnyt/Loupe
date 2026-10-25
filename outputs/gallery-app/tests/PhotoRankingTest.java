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
  }
}
