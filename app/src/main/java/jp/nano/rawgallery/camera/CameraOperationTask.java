package jp.nano.rawgallery.camera;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** An executor task whose queued cancellation and actual completion share one owner. */
public final class CameraOperationTask implements Runnable {
  private final AtomicBoolean started = new AtomicBoolean();
  private final Runnable operation;
  private final Runnable discarded;

  public CameraOperationTask(Runnable operation, Runnable discarded) {
    this.operation = operation;
    this.discarded = discarded;
  }

  /** Binds cancellation to the exact request whose task may still be waiting for the executor. */
  public static final class Cancellation {
    private volatile boolean requested;
    private CameraOperationTask queued;

    public boolean requested() {
      return requested;
    }

    public boolean bind(CameraOperationTask task) {
      synchronized (this) {
        if (!requested) {
          queued = task;
          return true;
        }
      }
      task.discardBeforeStart();
      return false;
    }

    public synchronized void clear() {
      queued = null;
    }

    public void cancel() {
      CameraOperationTask task;
      synchronized (this) {
        requested = true;
        task = queued;
        queued = null;
      }
      // Service cleanup may acquire its own monitor to advance a paired continuation.
      if (task != null) task.discardBeforeStart();
    }
  }

  /** Submits work to an executor whose queue is not canceled during the Activity lifecycle. */
  public static boolean executeAdmitted(
      Executor executor, CameraImportGate.Admission admission, Runnable operation) {
    try {
      executor.execute(
          () -> {
            try (admission) {
              operation.run();
            }
          });
      return true;
    } catch (RejectedExecutionException rejected) {
      admission.close();
      return false;
    }
  }

  @Override
  public void run() {
    if (started.compareAndSet(false, true)) operation.run();
  }

  /** The worker owns cleanup once it starts, including during interruption. */
  public void discardBeforeStart() {
    if (started.compareAndSet(false, true)) discarded.run();
  }
}
