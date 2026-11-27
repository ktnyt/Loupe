package jp.nano.rawgallery.camera;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Verified, bounded-memory staging for one photo received from a camera source. */
public final class CameraImportCopy {
  private static final int BUFFER_BYTES = 64 * 1024;

  private CameraImportCopy() {}

  @FunctionalInterface
  public interface Copy {
    void write(OutputStream out) throws IOException;
  }

  /** Owns one verified temporary source copy. Closing it removes that file. */
  public static final class Prepared implements AutoCloseable {
    public final File file;
    public final String sourceSha256;
    public final long bytes;
    private boolean closed;

    private Prepared(File file, String sourceSha256, long bytes) {
      this.file = file;
      this.sourceSha256 = sourceSha256;
      this.bytes = bytes;
    }

    @Override
    public synchronized void close() throws IOException {
      if (closed) return;
      closed = true;
      if (file.exists() && !file.delete()) {
        throw new IOException("Could not remove staged camera copy");
      }
    }
  }

  public static Prepared prepare(
      File cacheDir, CameraImportFile source, Copy copy, BooleanSupplier cancelled)
      throws IOException {
    Objects.requireNonNull(cacheDir, "cacheDir");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(copy, "copy");
    Objects.requireNonNull(cancelled, "cancelled");
    if (!cacheDir.isDirectory() && !cacheDir.mkdirs() && !cacheDir.isDirectory()) {
      throw new IOException("Could not create camera import cache directory");
    }

    File temporary = File.createTempFile("camera-import-", ".part", cacheDir);
    boolean prepared = false;
    Throwable failure = null;
    try {
      checkCancelled(cancelled);
      CountingOutputStream counted;
      try (FileOutputStream fileOut = new FileOutputStream(temporary)) {
        counted = new CountingOutputStream(fileOut, source.bytes, cancelled);
        copy.write(counted);
        counted.flush();
        if (counted.count() != source.bytes) {
          throw new IOException("Camera copy size did not match source metadata");
        }
        checkCancelled(cancelled);
        fileOut.getFD().sync();
      }
      if (temporary.length() != source.bytes) {
        throw new IOException("Staged camera copy size changed");
      }
      String receivedDigest = counted.sha256();
      String stagedDigest = sha256(temporary, cancelled);
      if (!receivedDigest.equals(stagedDigest)) {
        throw new IOException("Staged camera copy did not match received bytes");
      }
      checkCancelled(cancelled);
      Prepared result = new Prepared(temporary, receivedDigest, counted.count());
      prepared = true;
      return result;
    } catch (IOException | RuntimeException | Error error) {
      failure = error;
      throw error;
    } finally {
      if (!prepared && temporary.exists() && !temporary.delete()) {
        IOException cleanup = new IOException("Could not remove incomplete camera copy");
        if (failure != null) failure.addSuppressed(cleanup);
        else throw cleanup;
      }
    }
  }

  /** Hashes the entire file, checking cancellation between bounded reads. */
  public static String sha256(File file, BooleanSupplier cancelled) throws IOException {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(cancelled, "cancelled");
    MessageDigest digest = newDigest();
    byte[] buffer = new byte[BUFFER_BYTES];
    try (FileInputStream input = new FileInputStream(file)) {
      while (true) {
        checkCancelled(cancelled);
        int count = input.read(buffer);
        if (count < 0) break;
        if (count > 0) digest.update(buffer, 0, count);
      }
    }
    checkCancelled(cancelled);
    return hex(digest.digest());
  }

  private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
      throw new InterruptedIOException("Camera copy cancelled");
    }
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError("SHA-256 is required by the Java runtime", impossible);
    }
  }

  private static String hex(byte[] digest) {
    char[] result = new char[digest.length * 2];
    final char[] alphabet = "0123456789abcdef".toCharArray();
    for (int i = 0; i < digest.length; i++) {
      int value = digest[i] & 0xff;
      result[i * 2] = alphabet[value >>> 4];
      result[i * 2 + 1] = alphabet[value & 0x0f];
    }
    return new String(result);
  }

  private static final class CountingOutputStream extends OutputStream {
    private final OutputStream output;
    private final long maximum;
    private final BooleanSupplier cancelled;
    private final MessageDigest digest = newDigest();
    private long count;

    CountingOutputStream(OutputStream output, long maximum, BooleanSupplier cancelled) {
      this.output = output;
      this.maximum = maximum;
      this.cancelled = cancelled;
    }

    @Override
    public void write(int value) throws IOException {
      checkCancelled(cancelled);
      requireWithinLimit(1);
      output.write(value);
      digest.update((byte) value);
      count++;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      if (bytes == null) throw new NullPointerException("bytes");
      if (offset < 0 || length < 0 || length > bytes.length - offset) {
        throw new IndexOutOfBoundsException();
      }
      checkCancelled(cancelled);
      requireWithinLimit(length);
      output.write(bytes, offset, length);
      digest.update(bytes, offset, length);
      count += length;
    }

    @Override
    public void flush() throws IOException {
      checkCancelled(cancelled);
      output.flush();
    }

    /** The callback does not own or close the staging file descriptor. */
    @Override
    public void close() throws IOException {
      flush();
    }

    long count() {
      return count;
    }

    String sha256() {
      return hex(digest.digest());
    }

    private void requireWithinLimit(int additional) throws IOException {
      if (additional > maximum - count) {
        throw new IOException("Camera copy exceeded source size");
      }
    }
  }
}
