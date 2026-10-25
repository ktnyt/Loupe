package jp.nano.rawgallery;

import java.nio.file.*;

public final class DiagnosticWriterTest {
  public static void main(String[] args) throws Exception {
    Path dir = Files.createTempDirectory("loupe-diagnostics-");
    try {
      try (DiagnosticWriter writer = new DiagnosticWriter(dir.toFile(), 32)) {
        for (int i = 0; i < 100; i++) writer.write("{\"seq\":" + i + "}");
      }
      for (String name : new String[]{"events.jsonl", "previous.jsonl"}) {
        Path file = dir.resolve(name);
        if (Files.size(file) > 32 || Files.size(file) == 0) throw new AssertionError("capacity");
      }
      try (DiagnosticWriter writer = new DiagnosticWriter(dir.toFile(), 32)) {
        writer.write("{\"seq\":100}");
      }
      if (!Files.readString(dir.resolve("events.jsonl")).contains("100")) throw new AssertionError("reopen");
      boolean failed = false;
      try (DiagnosticWriter writer = new DiagnosticWriter(dir.toFile(), 32)) {
        writer.write("x".repeat(33));
      } catch (java.io.IOException expected) { failed = true; }
      if (!failed) throw new AssertionError("oversized event not rejected");
      System.out.println("PASS rotation, capacity, reopen, oversized failure");
    } finally {
      Files.deleteIfExists(dir.resolve("events.jsonl"));
      Files.deleteIfExists(dir.resolve("previous.jsonl"));
      Files.delete(dir);
    }
  }
}
