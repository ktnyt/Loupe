package jp.nano.rawgallery;

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
  final long totalBytes;
  private volatile long bytes;
  private State state = State.RUNNING;
  private int count = -1;

  record Snapshot(State state, long bytes, long totalBytes, int count) {}

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
    return new Snapshot(state, bytes, totalBytes, count);
  }

  void bytesRead(long value) {
    bytes = value;
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
