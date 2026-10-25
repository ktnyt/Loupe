package jp.nano.rawgallery;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Single writer; current + previous file are each bounded by maxBytes. */
final class DiagnosticWriter implements AutoCloseable {
  private final File current, previous;
  private final long maxBytes;
  private OutputStream out;
  private long size;
  DiagnosticWriter(File dir, long maxBytes) throws IOException {
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("diagnostics directory");
    this.maxBytes = maxBytes;
    current = new File(dir, "events.jsonl");
    previous = new File(dir, "previous.jsonl");
    size = current.length();
    out = new BufferedOutputStream(new FileOutputStream(current, true));
  }
  void write(String line) throws IOException {
    byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
    if (bytes.length > maxBytes) throw new IOException("oversized diagnostic event");
    if (size + bytes.length > maxBytes) {
      out.close();
      if (previous.exists() && !previous.delete()) throw new IOException("diagnostic rotation delete");
      if (!current.renameTo(previous)) throw new IOException("diagnostic rotation rename");
      out = new BufferedOutputStream(new FileOutputStream(current));
      size = 0;
    }
    out.write(bytes);
    size += bytes.length;
  }
  void flush() throws IOException { out.flush(); }
  public void close() throws IOException { out.close(); }
}
