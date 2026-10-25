package jp.nano.rawgallery;

import java.util.concurrent.*;

/** Instrumentation only: preserves FIFO and existing worker count. */
final class DiagnosticExecutor extends ThreadPoolExecutor {
  private final String name;
  DiagnosticExecutor(String name, int workers) {
    super(workers, workers, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    this.name = name;
  }
  @Override public void execute(Runnable command) {
    long request = DiagnosticLog.id(), parent = DiagnosticLog.parent();
    DiagnosticLog.event("task.enqueue", request, "pool", name, "queued", getQueue().size());
    super.execute(() -> {
      long old = DiagnosticLog.use(parent);
      DiagnosticLog.event("task.start", request, "pool", name);
      DiagnosticLog.use(request);
      try {
        command.run();
        if (command instanceof Future<?>) {
          Future<?> result = (Future<?>) command;
          if (result.isCancelled()) DiagnosticLog.event("task.cancel", request);
          else if (result.isDone()) {
            try { result.get(); }
            catch (ExecutionException e) {
              DiagnosticLog.event("task.fail", request, "error", e.getCause().getClass().getSimpleName());
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
          }
        }
      }
      catch (RuntimeException | Error e) {
        DiagnosticLog.event("task.fail", request, "error", e.getClass().getSimpleName());
        throw e;
      } finally {
        DiagnosticLog.event("task.end", request, "pool", name);
        DiagnosticLog.use(old);
      }
    });
  }
}
