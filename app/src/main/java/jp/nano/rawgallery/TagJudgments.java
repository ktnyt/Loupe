package jp.nano.rawgallery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Human-only immutable history. Contract: docs/tag-judgment-contract.md.
 * Persistence must commit the returned state before publishing it to the UI. */
final class TagJudgments {
  enum Value { ASSIGNED, EXCLUDED, UNDECIDED }

  static final class Entry {
    final String operationId, tagId, photoId, undoneOperationId;
    final long revision;
    final Value value;

    private Entry(String operationId, String tagId, String photoId, long revision,
        Value value, String undoneOperationId) {
      this.operationId = operationId;
      this.tagId = tagId;
      this.photoId = photoId;
      this.revision = revision;
      this.value = value;
      this.undoneOperationId = undoneOperationId;
    }
  }

  final List<Entry> history;

  TagJudgments() { this(Collections.emptyList()); }

  private TagJudgments(List<Entry> history) {
    this.history = Collections.unmodifiableList(new ArrayList<>(history));
  }

  private static String id(String value) {
    if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Empty ID");
    return value;
  }

  Entry latest(String tagId, String photoId) {
    id(tagId);
    id(photoId);
    for (int i = history.size() - 1; i >= 0; i--) {
      Entry entry = history.get(i);
      if (entry.tagId.equals(tagId) && entry.photoId.equals(photoId)) return entry;
    }
    return null;
  }

  Value value(String tagId, String photoId) {
    Entry entry = latest(tagId, photoId);
    return entry == null ? Value.UNDECIDED : entry.value;
  }

  TagJudgments record(String operationId, String tagId, String photoId,
      long expectedRevision, Value value) {
    return append(operationId, tagId, photoId, expectedRevision, value, null);
  }

  /** Undo only the specified current operation; never erase later edits. */
  TagJudgments undo(String operationId, String targetOperationId) {
    id(operationId);
    id(targetOperationId);
    Entry target = null;
    for (Entry entry : history) {
      if (entry.operationId.equals(targetOperationId)) target = entry;
    }
    if (target == null) throw new IllegalArgumentException("Unknown undo target");
    Value previous = Value.UNDECIDED;
    for (Entry entry : history) {
      if (entry == target) break;
      if (entry.tagId.equals(target.tagId) && entry.photoId.equals(target.photoId)) {
        previous = entry.value;
      }
    }
    return append(operationId, target.tagId, target.photoId, target.revision,
        previous, targetOperationId);
  }

  private TagJudgments append(String operationId, String tagId, String photoId,
      long expectedRevision, Value value, String undoneOperationId) {
    id(operationId);
    id(tagId);
    id(photoId);
    Objects.requireNonNull(value, "value");
    if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE) {
      throw new IllegalArgumentException("Invalid revision");
    }
    for (Entry entry : history) {
      if (!entry.operationId.equals(operationId)) continue;
      if (entry.tagId.equals(tagId) && entry.photoId.equals(photoId)
          && entry.revision == expectedRevision + 1 && entry.value == value
          && Objects.equals(entry.undoneOperationId, undoneOperationId)) return this;
      throw new IllegalArgumentException("Operation ID reused with different contents");
    }
    Entry current = latest(tagId, photoId);
    long revision = current == null ? 0 : current.revision;
    if (revision != expectedRevision) throw new IllegalStateException("Stale judgment");
    List<Entry> next = new ArrayList<>(history);
    next.add(new Entry(operationId, tagId, photoId, revision + 1, value, undoneOperationId));
    return new TagJudgments(next);
  }
}
