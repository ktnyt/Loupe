package jp.nano.rawgallery;

public final class TagJudgmentsTest {
  private static void check(boolean condition) {
    if (!condition) throw new AssertionError();
  }

  private static void rejects(Class<? extends Throwable> type, Runnable action) {
    try { action.run(); }
    catch (Throwable error) {
      if (type.isInstance(error)) return;
      throw new AssertionError(error);
    }
    throw new AssertionError("Expected " + type.getSimpleName());
  }

  public static void main(String[] args) {
    TagJudgments empty = new TagJudgments();
    check(empty.value("cat", "photo") == TagJudgments.Value.UNDECIDED);
    TagJudgments assigned = empty.record("1", "cat", "photo", 0, TagJudgments.Value.ASSIGNED);
    check(empty.history.isEmpty());
    check(assigned.value("cat", "photo") == TagJudgments.Value.ASSIGNED);
    check(assigned.record("1", "cat", "photo", 0, TagJudgments.Value.ASSIGNED) == assigned);
    rejects(IllegalArgumentException.class,
        () -> assigned.record("1", "cat", "photo", 0, TagJudgments.Value.EXCLUDED));
    rejects(IllegalStateException.class,
        () -> assigned.record("2", "cat", "photo", 0, TagJudgments.Value.EXCLUDED));
    TagJudgments excluded = assigned.record("2", "cat", "photo", 1, TagJudgments.Value.EXCLUDED);
    check(excluded.history.size() == 2);
    check(excluded.value("cat", "photo") == TagJudgments.Value.EXCLUDED);
    check(excluded.record("1", "cat", "photo", 0, TagJudgments.Value.ASSIGNED) == excluded);
    rejects(IllegalStateException.class, () -> excluded.undo("bad", "1"));
    TagJudgments restored = excluded.undo("3", "2");
    check(restored.value("cat", "photo") == TagJudgments.Value.ASSIGNED);
    check(restored.latest("cat", "photo").revision == 3);
    check(restored.undo("3", "2") == restored);
    TagJudgments undone = assigned.undo("undo-first", "1");
    check(undone.value("cat", "photo") == TagJudgments.Value.UNDECIDED);
    check(undone.history.size() == 2);
    TagJudgments multiple = restored.record("4", "walk", "photo", 0, TagJudgments.Value.ASSIGNED)
        .record("5", "cat", "other", 0, TagJudgments.Value.EXCLUDED);
    check(multiple.value("cat", "photo") == TagJudgments.Value.ASSIGNED);
    check(multiple.latest("walk", "photo").revision == 1);
    rejects(IllegalArgumentException.class, () -> multiple.undo("4", "2"));
    rejects(IllegalArgumentException.class, () -> multiple.undo("6", "missing"));
    rejects(IllegalArgumentException.class,
        () -> empty.record(" ", "cat", "photo", 0, TagJudgments.Value.ASSIGNED));
    rejects(IllegalArgumentException.class,
        () -> empty.record("x", "cat", "photo", Long.MAX_VALUE, TagJudgments.Value.ASSIGNED));
    rejects(NullPointerException.class, () -> empty.record("x", "cat", "photo", 0, null));
    rejects(UnsupportedOperationException.class, () -> assigned.history.clear());
    System.out.println("TagJudgmentsTest passed");
  }
}
