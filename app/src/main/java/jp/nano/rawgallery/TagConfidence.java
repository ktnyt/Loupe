package jp.nano.rawgallery;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Independent, uncalibrated tag scores. Contract: docs/tag-confidence-contract.md. */
final class TagConfidence {
  static final String VERSION = "balanced-logistic-l2-v1";
  static final double DEFAULT_THRESHOLD = .60;

  static final class Model {
    final double[] weights;
    final double bias;
    final int positives, negatives;

    Model(double[] weights, double bias, int positives, int negatives) {
      if (weights.length < 1 || weights.length > 4096 || !Double.isFinite(bias)
          || positives < 1 || negatives < 1) throw new IllegalArgumentException("Invalid model");
      this.weights = weights.clone(); this.bias = bias;
      this.positives = positives; this.negatives = negatives;
      for (double weight : weights) if (!Double.isFinite(weight)) throw new IllegalArgumentException("Invalid weight");
    }

    double score(RelatedPhotos.Vector vector) {
      if (vector.values.length != weights.length) throw new IllegalArgumentException("Incompatible features");
      double logit = bias;
      for (int i = 0; i < weights.length; i++) logit += weights[i] * vector.values[i] / vector.norm;
      return sigmoid(logit);
    }

    byte[] encode() {
      try {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(weights.length); out.writeInt(positives); out.writeInt(negatives); out.writeDouble(bias);
        for (double weight : weights) out.writeDouble(weight);
        return bytes.toByteArray();
      } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    static Model decode(byte[] bytes) {
      if (bytes == null || bytes.length < 28 || bytes.length > 20 + 4096 * 8)
        throw new IllegalArgumentException("Invalid model bytes");
      try {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int size = in.readInt(), positive = in.readInt(), negative = in.readInt();
        if (size < 1 || size > 4096 || bytes.length != 20 + size * 8) throw new IllegalArgumentException("Invalid model size");
        double bias = in.readDouble(); double[] weights = new double[size];
        for (int i = 0; i < size; i++) weights[i] = in.readDouble();
        return new Model(weights, bias, positive, negative);
      } catch (IOException invalid) { throw new IllegalArgumentException("Invalid model bytes", invalid); }
    }
  }

  /** Fits latest human labels only. A single class is insufficient, not a zero-score model. */
  static Model fit(Map<String, RelatedPhotos.Vector> vectors, Map<String, TagJudgments.Value> labels,
      BooleanSupplier cancelled) {
    List<String> keys = new ArrayList<>(labels.keySet()); Collections.sort(keys);
    List<RelatedPhotos.Vector> inputs = new ArrayList<>(); List<Boolean> outputs = new ArrayList<>();
    int dimensions = 0, positives = 0, negatives = 0;
    for (String key : keys) {
      TagJudgments.Value label = labels.get(key);
      RelatedPhotos.Vector vector = vectors.get(key);
      if (vector == null || label == TagJudgments.Value.UNDECIDED) continue;
      if (dimensions != 0 && vector.values.length != dimensions) throw new IllegalArgumentException("Mixed features");
      dimensions = vector.values.length;
      if (dimensions > 4096) throw new IllegalArgumentException("Too many features");
      boolean positive = label == TagJudgments.Value.ASSIGNED;
      inputs.add(vector); outputs.add(positive);
      if (positive) positives++; else negatives++;
    }
    if (positives == 0 || negatives == 0) return null;
    double[] weights = new double[dimensions]; double bias = 0;
    for (int step = 0; step < 160; step++) {
      requireActive(cancelled);
      double[] gradient = new double[dimensions]; double intercept = 0;
      for (int row = 0; row < inputs.size(); row++) {
        RelatedPhotos.Vector vector = inputs.get(row);
        double logit = bias;
        for (int col = 0; col < dimensions; col++) logit += weights[col] * vector.values[col] / vector.norm;
        boolean positive = outputs.get(row);
        double error = (sigmoid(logit) - (positive ? 1 : 0)) * .5 / (positive ? positives : negatives);
        intercept += error;
        for (int col = 0; col < dimensions; col++) gradient[col] += error * vector.values[col] / vector.norm;
      }
      double change = intercept * intercept;
      for (int col = 0; col < dimensions; col++) {
        double value = gradient[col] + .01 * weights[col];
        weights[col] -= 2 * value; change += value * value;
      }
      bias -= 2 * intercept;
      if (change < 1e-12) break;
    }
    return new Model(weights, bias, positives, negatives);
  }

  static void requireActive(BooleanSupplier cancelled) {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new java.util.concurrent.CancellationException();
  }

  static double sigmoid(double value) {
    return 1 / (1 + Math.exp(-Math.max(-40, Math.min(40, value))));
  }

  static void requireScore(double value) {
    if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("Invalid score");
  }

  /** Three boundary queries, one high-score audit and one date-order exploration per cycle. */
  static int next(List<TagJudgments.Value> labels, Map<Integer, Double> scores,
      Set<Integer> seen, double threshold, int reviewed) {
    requireScore(threshold);
    int best = -1, mode = reviewed % 5;
    int target = labels.isEmpty() ? 0 : (int) ((long) reviewed * 997 % labels.size());
    double bestPriority = -Double.MAX_VALUE;
    for (int i = 0; i < labels.size(); i++) {
      if (labels.get(i) != TagJudgments.Value.UNDECIDED || seen.contains(i)) continue;
      Double score = scores.get(i);
      if (score != null) requireScore(score);
      double priority = mode == 4 || score == null ? -2 - Math.abs(i - target)
          : mode == 3 ? score : -Math.abs(score - threshold);
      if (best == -1 || priority > bestPriority) { best = i; bestPriority = priority; }
    }
    return best;
  }
}
