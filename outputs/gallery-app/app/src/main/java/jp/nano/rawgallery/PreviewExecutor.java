package jp.nano.rawgallery;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Visible photos precede queued thumbnails; running work is allowed to finish. */
final class PreviewExecutor extends ThreadPoolExecutor {
  private final AtomicLong sequence = new AtomicLong();

  PreviewExecutor() {
    super(2, 2, 0, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>());
  }

  @Override public void execute(Runnable task) { execute(task, false); }

  void execute(Runnable task, boolean foreground) {
    super.execute(new Request(task, foreground, sequence.getAndIncrement()));
  }

  private static final class Request implements Runnable, Comparable<Request> {
    final Runnable task;
    final boolean foreground;
    final long sequence;
    Request(Runnable task, boolean foreground, long sequence) {
      this.task = task;
      this.foreground = foreground;
      this.sequence = sequence;
    }
    public void run() { task.run(); }
    public int compareTo(Request other) {
      int priority = Boolean.compare(other.foreground, foreground);
      return priority != 0 ? priority : Long.compare(sequence, other.sequence);
    }
  }
}
