package jp.nano.rawgallery;
import java.util.*;
public final class ShareHistoryValidationTest {
  public static void main(String[] args) {
    ArrayList<String> selection = new ArrayList<>(List.of("a", "b", "a"));
    List<String> frozen = ShareHistoryStore.validatedKeys(selection);
    selection.clear();
    if (!frozen.equals(List.of("a", "b"))) throw new AssertionError("freeze/dedup");
    reject(() -> ShareHistoryStore.validatedKeys(List.of()));
    reject(() -> ShareHistoryStore.validatedKeys(Arrays.asList("a", null)));
    reject(() -> ShareHistoryStore.validatedKeys(List.of("")));
    ShareHistoryStore.validateApp("jp.example.app", "共有先", 1);
    reject(() -> ShareHistoryStore.validateApp("bad/path", "x", 1));
    reject(() -> ShareHistoryStore.validateApp("jp.example", " ", 1));
    reject(() -> ShareHistoryStore.validateApp("jp.example", "x", -1));
    System.out.println("PASS ShareHistoryValidationTest (input validation only; SQLite not exercised)");
  }
  private static void reject(Runnable action) {
    try { action.run(); throw new AssertionError("Expected invalid input"); }
    catch (IllegalArgumentException expected) {}
  }
}
