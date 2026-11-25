package jp.nano.rawgallery;

import android.os.CancellationSignal;
import android.os.Handler;
import androidx.annotation.MainThread;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

/** Owns the pending request for the two user-initiated source checks in CameraImportActivity. */
@MainThread
final class CameraSourceCheck {
  private static final long TIMEOUT_MILLIS = 3000;

  private final Handler main;
  private final Executor worker;
  private Request pending;

  private final class Request {
    final CancellationSignal cancellation = new CancellationSignal();
    final Runnable timeout;

    Request(Runnable onTimeout) {
      timeout =
          () -> {
            if (!clear(this)) return;
            cancellation.cancel();
            onTimeout.run();
          };
    }
  }

  CameraSourceCheck(Handler main, Executor worker) {
    this.main = main;
    this.worker = worker;
  }

  boolean isPending() {
    return pending != null;
  }

  boolean cancel() {
    Request request = pending;
    if (request == null) return false;
    clear(request);
    request.cancellation.cancel();
    return true;
  }

  <T> void start(
      Function<CancellationSignal, T> check,
      Consumer<T> onResult,
      Runnable onTimeout,
      Consumer<RejectedExecutionException> onBusy) {
    if (isPending()) throw new IllegalStateException("A camera source check is already pending");
    Request request = new Request(onTimeout);
    pending = request;
    main.postDelayed(request.timeout, TIMEOUT_MILLIS);
    try {
      worker.execute(
          () -> {
            T result = check.apply(request.cancellation);
            main.post(
                () -> {
                  if (clear(request)) onResult.accept(result);
                });
          });
    } catch (RejectedExecutionException busy) {
      clear(request);
      request.cancellation.cancel();
      onBusy.accept(busy);
    }
  }

  private boolean clear(Request request) {
    if (pending != request) return false;
    pending = null;
    main.removeCallbacks(request.timeout);
    return true;
  }
}
