package jp.nano.rawgallery;

import java.util.*;

/** Time-based candidates, not a claim that the camera recorded a burst flag. */
final class BurstGroups {
  static final class Entry {
    final int index;
    final long time;
    final String stream;

    Entry(int index, long time, String stream) {
      this.index = index;
      this.time = time;
      this.stream = stream;
    }
  }

  static List<List<Integer>> group(List<Entry> entries, long gap) {
    Map<String, List<Entry>> streams = new HashMap<>();
    List<List<Entry>> groups = new ArrayList<>();
    for (Entry e : entries) {
      if (gap <= 0 || e.time <= 0 || e.stream.isEmpty()) groups.add(new ArrayList<>(List.of(e)));
      else streams.computeIfAbsent(e.stream, ignored -> new ArrayList<>()).add(e);
    }
    for (List<Entry> stream : streams.values()) {
      stream.sort(
          Comparator.comparingLong((Entry e) -> e.time).reversed().thenComparingInt(e -> e.index));
      List<Entry> current = null;
      long previous = 0;
      for (Entry e : stream) {
        if (current == null || previous - e.time > gap) {
          current = new ArrayList<>();
          groups.add(current);
        }
        current.add(e);
        previous = e.time;
      }
    }
    // Preserve the gallery's ordering by placing each bundle at its first member.
    groups.sort(Comparator.comparingInt(g -> g.stream().mapToInt(e -> e.index).min().orElse(0)));
    List<List<Integer>> result = new ArrayList<>();
    for (List<Entry> g : groups) {
      List<Integer> members = new ArrayList<>();
      g.sort(Comparator.comparingInt(e -> e.index));
      for (Entry e : g) members.add(e.index);
      result.add(members);
    }
    return result;
  }
}
