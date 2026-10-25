package jp.nano.rawgallery;

import java.util.*;

public final class RelatedPhotosTest {
  static RelatedPhotos.Vector v(float... values) { return RelatedPhotos.Vector.from(values); }
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static void invalid(Runnable action) {
    try { action.run(); } catch (IllegalArgumentException expected) { return; }
    throw new AssertionError("Invalid vector accepted");
  }
  public static void main(String[] args) {
    Map<Integer, RelatedPhotos.Vector> vectors = new HashMap<>();
    vectors.put(0, v(1, 0)); vectors.put(1, v(.9f, .1f));
    vectors.put(2, v(.7f, .7f)); vectors.put(3, v(0, 1));
    List<TagJudgments.Value> labels = new ArrayList<>(Collections.nCopies(4, TagJudgments.Value.UNDECIDED));
    check(RelatedPhotos.next(vectors, labels, Set.of(), 0) == null, "Unseeded retrieval");
    labels.set(0, TagJudgments.Value.ASSIGNED);
    check(RelatedPhotos.next(vectors, labels, Set.of(), 0).photo == 1, "Nearest seed missing");
    check(RelatedPhotos.next(vectors, labels, Set.of(1), 0).photo == 2, "Pass repeated");
    check(labels.get(1) == TagJudgments.Value.UNDECIDED, "Pass became a negative");
    check(RelatedPhotos.next(vectors, labels, Set.of(), 4).photo == 3, "Exploration missing");
    labels.set(3, TagJudgments.Value.ASSIGNED);
    vectors.put(4, v(.1f, .99f)); labels.add(TagJudgments.Value.UNDECIDED);
    check(RelatedPhotos.next(vectors, labels, Set.of(), 0).photo == 4, "Additional positive ignored");
    labels.set(3, TagJudgments.Value.UNDECIDED); // undo the added exemplar
    check(RelatedPhotos.next(vectors, labels, Set.of(), 0).photo == 1, "Undo did not restore ranking");
    vectors.put(5, v(.9f, .1f)); labels.add(TagJudgments.Value.EXCLUDED);
    vectors.put(6, v(.9f, -.2f)); labels.add(TagJudgments.Value.UNDECIDED);
    int withoutNegative;
    labels.set(5, TagJudgments.Value.UNDECIDED);
    withoutNegative = RelatedPhotos.next(vectors, labels, Set.of(5), 0).photo;
    labels.set(5, TagJudgments.Value.EXCLUDED);
    int withNegative = RelatedPhotos.next(vectors, labels, Set.of(), 0).photo;
    check(withoutNegative == 1 && withNegative == 6, "Negative did not change order");
    check(RelatedPhotos.next(Map.of(0, v(1, 0)), labels, Set.of(), 0) == null, "Unanalyzed became a candidate");
    invalid(() -> v(0, 0)); invalid(() -> v(Float.NaN, 1)); invalid(() -> v(Float.POSITIVE_INFINITY, 1));
    invalid(() -> v(1, 0).similarity(v(1)));
    check(Math.abs(v(1, 2).similarity(v(1, 2)) - 1) < 1e-6, "Cosine mismatch");
    System.out.println("RelatedPhotosTest passed: exemplar updates, exclusions, pass, undo, exploration, missing/invalid features");
  }
}
