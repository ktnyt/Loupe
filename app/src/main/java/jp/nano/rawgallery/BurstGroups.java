package jp.nano.rawgallery;

import java.util.*;

/** Groups adjacent burst frames or, when both are unnumbered, photos within the configured gap. */
final class BurstGroups {
  static final class Entry {
    final int index;
    final long time;
    final String stream, name;
    final int mode, frame;

    Entry(int index, long time, String stream, String name, int mode, int frame) {
      this.index = index;
      this.time = time;
      this.stream = stream;
      this.name = name;
      this.mode = mode;
      this.frame = frame;
    }

    private boolean numbered() { return mode > 0 && frame > 0; }

    private boolean follows(Entry previous, long gap) {
      if (numbered() || previous.numbered())
        return numbered() && previous.numbered() && mode == previous.mode
            && (long) frame - previous.frame == 1;
      return time - previous.time <= gap;
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
          Comparator.comparingLong((Entry e) -> e.time).thenComparing(e -> e.name));
      List<Entry> current = null;
      Entry previous = null;
      for (Entry e : stream) {
        if (previous == null || !e.follows(previous, gap)) {
          current = new ArrayList<>();
          groups.add(current);
        }
        current.add(e);
        previous = e;
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
