package jp.nano.rawgallery;

import java.util.*;

public final class AutomaticTagsTest {
  static RelatedPhotos.Vector v(float... values) { return RelatedPhotos.Vector.from(values); }
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  public static void main(String[] args) {
    Map<Integer, RelatedPhotos.Vector> vectors = Map.of(0, v(1,0), 1, v(.9f,.1f), 2, v(0,1), 3, v(.8f,.3f));
    List<TagJudgments.Value> human = new ArrayList<>(Collections.nCopies(4, TagJudgments.Value.UNDECIDED));
    check(AutomaticTags.predict(vectors, human, Set.of()).isEmpty(), "No human seed");
    human.set(0, TagJudgments.Value.ASSIGNED);
    Map<Integer, AutomaticTags.Decision> predicted = AutomaticTags.predict(vectors, human, Set.of());
    check(predicted.keySet().equals(Set.of(1,3)), "Broad assignment must actually expand the seed");
    check(!predicted.containsKey(0), "Human seed counted as automatic");
    check(!AutomaticTags.predict(vectors, human, Set.of(1)).containsKey(1), "Cancellation ignored");
    human.set(3, TagJudgments.Value.EXCLUDED);
    check(!AutomaticTags.predict(vectors, human, Set.of()).containsKey(3), "Human exclusion ignored");
    human.set(1, TagJudgments.Value.EXCLUDED);
    check(AutomaticTags.predict(vectors, human, Set.of()).isEmpty(), "No eligible unseen photo");
    human.set(1, TagJudgments.Value.UNDECIDED);
    human.set(3, TagJudgments.Value.ASSIGNED);
    check(AutomaticTags.predict(vectors, human, Set.of()).containsKey(1), "Multiple exemplars lost");
    check(AutomaticTags.predict(Map.of(0, v(1,0)), human, Set.of()).isEmpty(), "Missing features assigned");
    // Automatic predictions are not fed back; unchanged human labels produce unchanged results.
    check(human.get(1) == TagJudgments.Value.UNDECIDED, "Self-training mutation");
    System.out.println("AutomaticTagsTest passed: broad assignment, no seed, negative examples, suppression, missing features, human-only learning");
  }
}
