package jp.nano.rawgallery;

import java.util.*;

/** Binary insertion over rank groups, replayed from the compatible choice journal. */
public final class PhotoRanking {
  public final List<String> ids;
  public final List<String> order = new ArrayList<>();
  public final String journal;
  private final List<List<String>> groups = new ArrayList<>();
  private int next = 1, low = 0, high = 1;

  public PhotoRanking(List<String> ids, String journal) {
    if (ids.isEmpty() || new HashSet<>(ids).size() != ids.size())
      throw new IllegalArgumentException("Invalid members");
    this.ids = List.copyOf(ids);
    this.journal = journal;
    groups.add(new ArrayList<>(List.of(ids.get(0))));
    for (char c : journal.toCharArray()) {
      if (done() || (c != '0' && c != '1' && c != '2'))
        throw new IllegalArgumentException("Invalid journal");
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
    return groups.get(middle()).get(0);
  }

  public PhotoRanking choose(boolean upper) {
    if (done()) throw new IllegalStateException();
    return new PhotoRanking(ids, journal + (upper ? '0' : '1'));
  }

  public PhotoRanking tie() {
    if (done()) throw new IllegalStateException();
    return new PhotoRanking(ids, journal + '2');
  }

  public List<String> best() {
    return done() ? List.copyOf(groups.get(0)) : List.of();
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
        ids, journal.isEmpty() ? "" : journal.substring(0, journal.length() - 1));
  }
}
