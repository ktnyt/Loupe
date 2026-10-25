package jp.nano.rawgallery;

import java.util.*;

public final class BurstGroupsTest {
  public static void main(String[] args) {
    check(
        List.of(e(0, 3000, "A"), e(1, 2500, "B"), e(2, 2000, "A"), e(3, 1000, "A")),
        1000,
        List.of(List.of(0, 2, 3), List.of(1)));
    check(List.of(e(0, 3001, "A"), e(1, 2000, "A")), 1000, List.of(List.of(0), List.of(1)));
    check(List.of(e(0, 3000, "A"), e(1, 2000, "A")), 0, List.of(List.of(0), List.of(1)));
    check(
        List.of(e(0, 0, "A"), e(1, 0, "A"), e(2, 1000, "")),
        1000,
        List.of(List.of(0), List.of(1), List.of(2)));
    check(List.of(e(0, 1000, "raw"), e(1, 1000, "jpeg")), 1000, List.of(List.of(0), List.of(1)));
    check(List.of(e(0, 1000, "A"), e(1, 1000, "A")), 1000, List.of(List.of(0, 1)));
    check(
        List.of(e(0, 1000, "A"), e(1, 3000, "A"), e(2, 2000, "A")),
        1000,
        List.of(List.of(0, 1, 2)));
    check(List.of(), 1000, List.of());
    System.out.println("Burst grouping: 8 cases passed");
  }

  static BurstGroups.Entry e(int i, long t, String s) {
    return new BurstGroups.Entry(i, t, s);
  }

  static void check(List<BurstGroups.Entry> e, long gap, List<List<Integer>> expected) {
    if (!BurstGroups.group(e, gap).equals(expected)) throw new AssertionError("Grouping mismatch");
  }
}
