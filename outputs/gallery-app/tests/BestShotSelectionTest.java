package jp.nano.rawgallery;

import java.util.*;

public class BestShotSelectionTest {
  public static void main(String[] args) {
    List<List<Integer>> groups =
        List.of(List.of(0, 1, 2), List.of(3, 4), List.of(5), List.of(6, 7));
    java.util.function.Function<List<Integer>, Set<Integer>> winners =
        g -> g.get(0) == 0 ? Set.of(0, 2) : g.get(0) == 3 ? Set.of(4) : null;
    var r = BestShotSelection.resolve(Set.of(0, 1, 2, 3, 4, 5, 6, 7), groups, true, winners);
    if (!r.members.equals(List.of(0, 2, 4, 5)) || !r.unresolved.equals(List.of(List.of(6, 7))))
      throw new AssertionError("mixed ranks, ties, single, unresolved");
    r = BestShotSelection.resolve(Set.of(1, 2, 3), groups, true, winners);
    if (!r.members.equals(List.of(2))) throw new AssertionError("must not add unselected winners");
    r =
        BestShotSelection.resolve(
            Set.of(1, 3, 6),
            groups,
            false,
            g -> {
              throw new AssertionError("ranking consulted when disabled");
            });
    if (!r.members.equals(List.of(1, 3, 6)) || !r.unresolved.isEmpty())
      throw new AssertionError("all selected");
    r = BestShotSelection.resolve(Set.of(), groups, true, winners);
    if (!r.members.isEmpty() || !r.unresolved.isEmpty())
      throw new AssertionError("empty selection");
    System.out.println(
        "Best shots: tied winners, singles, unfinished, partial selection, disabled and empty"
            + " passed");
  }
}
