package jp.nano.rawgallery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Candidate retrieval, never a tag decision or calibrated probability. */
final class RelatedPhotos {
  static final class Vector {
    final byte[] values;
    final double norm;
    Vector(byte[] values) {
      if (values == null || values.length == 0) throw new IllegalArgumentException("Empty vector");
      this.values = values.clone();
      double sum = 0;
      for (byte value : values) sum += value * value;
      if (sum == 0) throw new IllegalArgumentException("Zero vector");
      norm = Math.sqrt(sum);
    }
    static Vector from(float[] values) {
      double sum = 0;
      for (float value : values) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite vector");
        sum += value * value;
      }
      if (sum == 0 || !Double.isFinite(sum)) throw new IllegalArgumentException("Invalid norm");
      // Scale by maximum absolute value to preserve precision in sparse embeddings.
      float max = 0;
      for (float value : values) max = Math.max(max, Math.abs(value));
      byte[] compact = new byte[values.length];
      for (int i = 0; i < values.length; i++) compact[i] = (byte) Math.round(values[i] / max * 127);
      return new Vector(compact);
    }
    double similarity(Vector other) {
      if (values.length != other.values.length) throw new IllegalArgumentException("Incompatible vectors");
      long dot = 0;
      for (int i = 0; i < values.length; i++) dot += values[i] * other.values[i];
      return dot / (norm * other.norm);
    }
  }

  static final class Candidate {
    final int photo, exemplar;
    final boolean exploration;
    final double score;
    Candidate(int photo, int exemplar, boolean exploration, double score) {
      this.photo = photo; this.exemplar = exemplar; this.exploration = exploration; this.score = score;
    }
  }

  /** Snapshot inputs must describe one pipeline version. Every fifth result explores farther away. */
  static Candidate next(Map<Integer, Vector> vectors, List<TagJudgments.Value> labels,
      Set<Integer> seen, int reviewed) {
    List<Integer> positives = new ArrayList<>(), negatives = new ArrayList<>();
    for (int i = 0; i < labels.size(); i++) {
      if (!vectors.containsKey(i)) continue;
      if (labels.get(i) == TagJudgments.Value.ASSIGNED) positives.add(i);
      if (labels.get(i) == TagJudgments.Value.EXCLUDED) negatives.add(i);
    }
    if (positives.isEmpty()) return null;
    boolean explore = reviewed % 5 == 4;
    Candidate best = null;
    for (int i = 0; i < labels.size(); i++) {
      if (Thread.currentThread().isInterrupted()) return null;
      if (seen.contains(i) || labels.get(i) != TagJudgments.Value.UNDECIDED) continue;
      Vector candidate = vectors.get(i);
      if (candidate == null) continue;
      double positive = -2, negative = 0;
      int exemplar = -1;
      for (int seed : positives) {
        double score = candidate.similarity(vectors.get(seed));
        if (score > positive) { positive = score; exemplar = seed; }
      }
      for (int seed : negatives) negative = Math.max(negative, candidate.similarity(vectors.get(seed)));
      double score = explore ? -positive : positive - 0.5 * negative;
      if (best == null || score > best.score) best = new Candidate(i, exemplar, explore, score);
    }
    return best;
  }
}
