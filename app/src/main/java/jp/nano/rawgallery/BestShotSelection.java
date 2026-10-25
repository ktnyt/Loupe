package jp.nano.rawgallery;

import java.util.*;
import java.util.function.Function;

/** Resolves only explicitly selected photos; null winners mean an unfinished ranking. */
final class BestShotSelection {
  final List<Integer> members = new ArrayList<>();
  final List<List<Integer>> unresolved = new ArrayList<>();

  static BestShotSelection resolve(
      Set<Integer> requested,
      List<List<Integer>> groups,
      boolean bestOnly,
      Function<List<Integer>, Set<Integer>> winnersFor) {
    BestShotSelection result = new BestShotSelection();
    Set<Integer> added = new HashSet<>();
    for (List<Integer> group : groups) {
      if (Collections.disjoint(requested, group)) continue;
      Set<Integer> winners = null;
      if (bestOnly && group.size() > 1) {
        winners = winnersFor.apply(group);
        if (winners == null) {
          result.unresolved.add(List.copyOf(group));
          continue;
        }
      }
      for (int member : group)
        if (requested.contains(member)
            && (winners == null || winners.contains(member))
            && added.add(member)) result.members.add(member);
    }
    return result;
  }
}
