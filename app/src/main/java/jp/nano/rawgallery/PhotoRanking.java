package jp.nano.rawgallery;

import java.util.*;

/** Binary insertion over rank groups, replayed from the compatible choice journal. */
public final class PhotoRanking {
  public final List<String> ids;
  public final List<String> order = new ArrayList<>();
  public final String journal;
  private final List<List<String>> groups = new ArrayList<>();
  private final Map<String, Integer> selectionCounts = new HashMap<>();
  private int next = 1, low = 0, high = 1;

  public PhotoRanking(List<String> ids, String journal) {
    if (ids.isEmpty() || new HashSet<>(ids).size() != ids.size())
      throw new IllegalArgumentException("Invalid members");
    this.ids = List.copyOf(ids);
    this.journal = journal;
    if (bestOnly()) {
      String winner = ids.get(0);
      for (char c : journal.substring(2).toCharArray()) {
        if (next >= ids.size() || (c != '0' && c != '1'))
          throw new IllegalArgumentException("Invalid best-only journal");
        selectionCounts.merge(c == '0' ? ids.get(next) : winner, 1, Integer::sum);
        if (c == '0') winner = ids.get(next);
        next++;
      }
      groups.add(new ArrayList<>(List.of(winner)));
      if (done() && ids.size() > 1) {
        List<String> rest = new ArrayList<>(ids);
        rest.remove(winner);
        groups.add(rest);
      }
      for (List<String> group : groups) order.addAll(group);
      return;
    }
    groups.add(new ArrayList<>(List.of(ids.get(0))));
    for (char c : (journal.startsWith("A:") ? journal.substring(2) : journal).toCharArray()) {
      if (done() || (c != '0' && c != '1' && c != '2'))
        throw new IllegalArgumentException("Invalid journal");
      if (c != '2') selectionCounts.merge(c == '0' ? upper() : lower(), 1, Integer::sum);
      if (c == '2') {
        groups.get(middle()).add(ids.get(next++));
        low = 0;
        high = groups.size();
      } else {
        if (c == '0') high = middle();
        else low = middle() + 1;
        if (low == high) {
          groups.add(low, new ArrayList<>(List.of(ids.get(next++))));
          low = 0;
          high = groups.size();
        }
      }
    }
    for (List<String> group : groups) order.addAll(group);
  }

  public boolean bestOnly() { return journal.startsWith("B:"); }

  public int decisions() { return journal.length() - (bestOnly() || journal.startsWith("A:") ? 2 : 0); }

  /** Explicit A/B selections in the current journal; ties and inferred wins add nothing.
   * Replaying after undo/resume restores the counts without changing the persisted format.
   */
  public int selectionCount(String id) {
    if (!ids.contains(id)) throw new IllegalArgumentException("Unknown photo");
    return selectionCounts.getOrDefault(id, 0);
  }

  private int middle() {
    return (low + high) / 2;
  }

  public boolean done() {
    return next == ids.size();
  }

  public String upper() {
    if (done()) throw new IllegalStateException();
    return ids.get(next);
  }

  public String lower() {
    if (done()) throw new IllegalStateException();
    return groups.get(bestOnly() ? 0 : middle()).get(0);
  }

  public PhotoRanking choose(boolean upper) {
    if (done()) throw new IllegalStateException();
    return new PhotoRanking(ids, journal + (upper ? '0' : '1'));
  }

  public PhotoRanking tie() {
    if (bestOnly()) throw new IllegalStateException("Best-only does not assign ties");
    if (done()) throw new IllegalStateException();
    return new PhotoRanking(ids, journal + '2');
  }

  public List<String> best() {
    return done() ? List.copyOf(groups.get(0)) : List.of();
  }

  /** Promote one member without demoting winners or reordering the remaining rank groups. */
  public PhotoRanking addCoWinner(String id) {
    if (!ids.contains(id)) throw new IllegalArgumentException("Unknown photo");
    if (!done()) throw new IllegalStateException("Ranking is unfinished");
    return addCoWinners(List.of(id));
  }

  /** Promote the captured members, or finish an unfinished ranking from that selection. */
  public PhotoRanking addCoWinners(Collection<String> selectedIds) {
    Set<String> selected = new HashSet<>(selectedIds);
    if (selected.isEmpty() || !new HashSet<>(ids).containsAll(selected))
      throw new IllegalArgumentException("Invalid selection");
    boolean complete = done();
    if (complete && best().containsAll(selected)) return this;

    Map<String, Integer> ranks = new HashMap<>();
    if (complete) {
      int rank = 1;
      for (List<String> group : groups) {
        for (String member : group) ranks.put(member, rank);
        rank += group.size();
      }
    } else {
      for (String member : ids) ranks.put(member, 2);
    }
    for (String member : selected) ranks.put(member, 1);
    PhotoRanking result = new PhotoRanking(ids, "A:");
    while (!result.done()) {
      int comparison = Integer.compare(ranks.get(result.upper()), ranks.get(result.lower()));
      result = comparison == 0 ? result.tie() : result.choose(comparison < 0);
    }
    return result;
  }

  public int rankOf(String id) {
    int rank = 1;
    for (List<String> group : groups) {
      if (group.contains(id)) return rank;
      rank += group.size();
    }
    throw new IllegalArgumentException("Unknown photo");
  }

  public PhotoRanking undo() {
    return new PhotoRanking(
        ids, decisions() == 0 ? journal : journal.substring(0, journal.length() - 1));
  }
}
