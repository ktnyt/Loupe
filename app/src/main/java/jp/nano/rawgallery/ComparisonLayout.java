package jp.nano.rawgallery;

/** Chooses the larger total uncropped image area with equal-sized comparison slots. */
public final class ComparisonLayout {
  public static boolean horizontal(double width, double height, double first, double second) {
    if (width <= 0 || height <= 0 || first <= 0 || second <= 0) return false;
    return area(width / 2, height, first) + area(width / 2, height, second)
        > area(width, height / 2, first) + area(width, height / 2, second);
  }

  private static double area(double width, double height, double ratio) {
    double w = Math.min(width, height * ratio);
    return w * w / ratio;
  }
}
