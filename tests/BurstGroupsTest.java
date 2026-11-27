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
    check(
        List.of(e(0, 4000, "A"), e(1, 3650, "A"), e(2, 3299, "A")),
        350,
        List.of(List.of(0, 1), List.of(2)));
    check(
        List.of(e(0, 4000, "A"), e(1, 3999, "A"), e(2, 3997, "A")),
        1,
        List.of(List.of(0, 1), List.of(2)));
    check(
        List.of(
            e(0, 2L * Integer.MAX_VALUE + 2, "A"), e(1, Integer.MAX_VALUE + 2L, "A"), e(2, 1, "A")),
        Integer.MAX_VALUE,
        List.of(List.of(0, 1), List.of(2)));
    // Capture time ascending, while results retain the gallery's descending order.
    check(List.of(n(0, 9000, "z", 1, 2), n(1, 1000, "a", 1, 1)), 1, List.of(List.of(0, 1)));
    check(
        List.of(n(0, 9000, "z", 1, 2), n(1, 1000, "a", 1, 1)), 0, List.of(List.of(0), List.of(1)));
    check(
        List.of(
            n(0, 1000, "b", 1, 2),
            n(1, 1000, "c", 1, 1),
            n(2, 1000, "d", 1, 2),
            n(3, 1000, "a", 1, 1)),
        1,
        List.of(List.of(0, 3), List.of(1, 2)));
    check(
        List.of(
            n(0, 1000, "a", 1, 1),
            n(1, 1000, "b", 1, 1),
            n(2, 1000, "c", 1, 3),
            n(3, 1000, "d", 2, 4)),
        1000,
        List.of(List.of(0), List.of(1), List.of(2), List.of(3)));
    check(
        List.of(
            n(0, 1000, "a", 1, 1),
            n(1, 1000, "b", 0, 0),
            n(2, 1000, "c", 1, 2),
            n(3, 1000, "d", 0, 0),
            n(4, 1001, "e", 0, 0)),
        1,
        List.of(List.of(0), List.of(1), List.of(2), List.of(3, 4)));
    check(
        List.of(
            n(0, 0, "a", 1, 1),
            n(1, 1000, "b", 1, 2),
            new BurstGroups.Entry(2, 1000, "other", "c", 1, 3),
            new BurstGroups.Entry(3, 1000, "", "d", 1, 3)),
        1,
        List.of(List.of(0), List.of(1), List.of(2), List.of(3)));
    System.out.println("Burst grouping: 17 cases passed");
  }

  static BurstGroups.Entry e(int i, long t, String s) {
    return new BurstGroups.Entry(i, t, s, "", 0, 0);
  }

  static BurstGroups.Entry n(int i, long time, String name, int mode, int frame) {
    return new BurstGroups.Entry(i, time, "A", name, mode, frame);
  }

  static void check(List<BurstGroups.Entry> e, long gap, List<List<Integer>> expected) {
    if (!BurstGroups.group(e, gap).equals(expected)) throw new AssertionError("Grouping mismatch");
  }
}
