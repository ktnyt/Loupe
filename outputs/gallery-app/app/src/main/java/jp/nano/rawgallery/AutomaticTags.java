package jp.nano.rawgallery;

import java.util.*;

/** Broad, explicitly uncalibrated assignment policy. Only human examples may train it. */
final class AutomaticTags {
  static final String VERSION = "broad-v1";
  static final class Decision {
    final double positive, negative, threshold;
    Decision(double positive, double negative, double threshold) {
      this.positive = positive; this.negative = negative; this.threshold = threshold;
    }
  }

  static Map<Integer, Decision> predict(Map<Integer, RelatedPhotos.Vector> vectors,
      List<TagJudgments.Value> human, Set<Integer> blocked) {
    List<Integer> positives = new ArrayList<>(), negatives = new ArrayList<>();
    for (int i = 0; i < human.size(); i++) {
      if (!vectors.containsKey(i)) continue;
      if (human.get(i) == TagJudgments.Value.ASSIGNED) positives.add(i);
      if (human.get(i) == TagJudgments.Value.EXCLUDED) negatives.add(i);
    }
    Map<Integer, Decision> result = new LinkedHashMap<>();
    if (positives.isEmpty()) return result;
    double threshold = .55;
    if (positives.size() > 1) {
      List<Double> neighbors = new ArrayList<>();
      for (int seed : positives) {
        double best = -1;
        for (int other : positives) if (seed != other)
          best = Math.max(best, vectors.get(seed).similarity(vectors.get(other)));
        neighbors.add(best);
      }
      Collections.sort(neighbors);
      threshold = Math.max(.4, Math.min(.65, neighbors.get((neighbors.size() - 1) / 10) - .15));
    }
    for (int i = 0; i < human.size(); i++) {
      if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
      if (human.get(i) != TagJudgments.Value.UNDECIDED || blocked.contains(i) || !vectors.containsKey(i)) continue;
      double positive = -1, negative = -1;
      for (int seed : positives) positive = Math.max(positive, vectors.get(i).similarity(vectors.get(seed)));
      for (int seed : negatives) negative = Math.max(negative, vectors.get(i).similarity(vectors.get(seed)));
      if (positive >= threshold && (negatives.isEmpty() || positive - negative >= .02))
        result.put(i, new Decision(positive, negative, threshold));
    }
    return result;
  }
}
