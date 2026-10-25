package jp.nano.rawgallery;

public final class ComparisonLayoutTest {
  public static void main(String[] args) {
    check(1200, 2200, .75, .75, false);
    check(1200, 2200, .4, .4, true);
    check(1200, 2200, 4.0 / 3, 4.0 / 3, false);
    check(2200, 1000, 4.0 / 3, 4.0 / 3, true);
    check(1000, 2200, .75, 4.0 / 3, false);
    check(0, 2200, .75, .75, false);
    for (int w = 300; w <= 2400; w += 300)
      for (int h = 300; h <= 2400; h += 300)
        if (ComparisonLayout.horizontal(w, h, .6, 1.7)
            != ComparisonLayout.horizontal(w, h, 1.7, .6))
          throw new AssertionError("Order changed layout");
    System.out.println(
        "Comparison layout: portrait, landscape, mixed, rotation and symmetry passed");
  }

  static void check(double w, double h, double a, double b, boolean expected) {
    if (ComparisonLayout.horizontal(w, h, a, b) != expected) throw new AssertionError(w + "x" + h);
  }
}
