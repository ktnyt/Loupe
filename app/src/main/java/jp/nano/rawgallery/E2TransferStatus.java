package jp.nano.rawgallery;

import android.os.SystemClock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/** In-process observations of user-facing transfers. The backup job persists its own counters. */
final class E2TransferStatus implements AutoCloseable {
  enum Kind { MANUAL_UPLOAD, CATALOG_DOWNLOAD, ORIGINAL_DOWNLOAD }
  enum State { RUNNING, COMPLETE, FAILED, CANCELLED }

  private static final List<E2TransferStatus> active = new ArrayList<>();
  private static final EnumMap<Kind, E2TransferStatus> last = new EnumMap<>(Kind.class);

  final Kind kind;
  final String bucket;
  private volatile long totalBytes;
  private volatile long bytes, startedAt, lastBytesAt;
  private State state = State.RUNNING;
  private int count = -1;
  private boolean publishing, originalSaved;

  record Snapshot(State state, long bytes, long totalBytes, int count,
      boolean publishing, boolean originalSaved, double bytesPerSecond) {}

  private E2TransferStatus(Kind kind, String bucket, long totalBytes) {
    this.kind = kind;
    this.bucket = bucket;
    this.totalBytes = totalBytes;
  }

  static synchronized E2TransferStatus begin(Kind kind, String bucket, long totalBytes) {
    E2TransferStatus transfer = new E2TransferStatus(kind, bucket, totalBytes);
    active.add(transfer);
    return transfer;
  }

  static synchronized Snapshot snapshot(Kind kind, String bucket) {
    for (E2TransferStatus item : active) {
      if (item.kind == kind && item.bucket.equals(bucket)) return item.snapshot();
    }
    E2TransferStatus item = last.get(kind);
    return item != null && item.bucket.equals(bucket) ? item.snapshot() : null;
  }

  private Snapshot snapshot() {
    long received = bytes;
    long now = SystemClock.elapsedRealtime();
    long elapsed = now - startedAt;
    double rate = startedAt > 0 && elapsed >= 1000 && now - lastBytesAt <= 5000
        ? 1000.0 * received / elapsed : 0;
    return new Snapshot(state, received, totalBytes, count, publishing, originalSaved, rate);
  }

  void startBytes(long total) {
    totalBytes = total;
    startedAt = SystemClock.elapsedRealtime();
  }

  void bytesRead(long value) {
    lastBytesAt = SystemClock.elapsedRealtime();
    bytes = value;
  }

  void publishing(boolean savedOriginal) {
    synchronized (E2TransferStatus.class) {
      publishing = true;
      originalSaved = savedOriginal;
    }
  }

  void fail() {
    finish(State.FAILED, -1);
  }

  void complete(int resultCount) {
    finish(State.COMPLETE, resultCount);
  }

  void cancel() {
    finish(State.CANCELLED, -1);
  }

  private void finish(State result, int resultCount) {
    synchronized (E2TransferStatus.class) {
      if (state != State.RUNNING) return;
      state = result;
      count = resultCount;
      active.remove(this);
      last.put(kind, this);
    }
  }

  @Override public void close() {
    finish(Thread.currentThread().isInterrupted() ? State.CANCELLED : State.FAILED, -1);
  }
}
