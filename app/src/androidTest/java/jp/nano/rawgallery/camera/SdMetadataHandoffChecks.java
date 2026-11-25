package jp.nano.rawgallery.camera;

import android.os.SystemClock;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class SdMetadataHandoffChecks {
  static void run() throws Exception {
    try (Workers workers = new Workers(true)) {
      workers.onHandoff = workers.release::countDown;
      int result =
          CameraDocumentSource.boundedMetadata(workers.pool, () -> false, 1000, signal -> 17);
      require(result == 17, "completed workers accept the next metadata request");
    }

    try (Workers workers = new Workers(false)) {
      AtomicBoolean entered = new AtomicBoolean();
      try {
        CameraDocumentSource.boundedMetadata(
            workers.pool, () -> false, 1000, signal -> entered.getAndSet(true));
        throw new AssertionError("occupied workers accepted another query");
      } catch (CameraDocumentSource.ListingException expected) {
        require(
            expected.reason == CameraDocumentSource.ListingException.Reason.LOADING,
            "occupied workers report loading within the request deadline");
      }
      workers.finish();
      require(!entered.get(), "rejected metadata never runs after occupied workers return");
    }

    try (Workers workers = new Workers(true)) {
      AtomicBoolean entered = new AtomicBoolean();
      AtomicBoolean cancelled = new AtomicBoolean();
      workers.onHandoff =
          () -> {
            cancelled.set(true);
            workers.release.countDown();
          };
      try {
        CameraDocumentSource.boundedMetadata(
            workers.pool, cancelled::get, 1000, signal -> entered.getAndSet(true));
        throw new AssertionError("cancelled metadata returned a result");
      } catch (InterruptedIOException expected) {
      }
      workers.finish();
      require(!entered.get(), "cancelled metadata never begins the provider query");
    }

    try (Workers workers = new Workers(true)) {
      AtomicBoolean entered = new AtomicBoolean();
      workers.onHandoff =
          () -> {
            SystemClock.sleep(80);
            workers.release.countDown();
          };
      try {
        CameraDocumentSource.boundedMetadata(
            workers.pool,
            () -> false,
            500,
            signal -> {
              entered.set(true);
              SystemClock.sleep(450);
              return 17;
            });
        throw new AssertionError("metadata execution received a new deadline after handoff");
      } catch (CameraDocumentSource.ListingException expected) {
        require(
            entered.get()
                && expected.reason == CameraDocumentSource.ListingException.Reason.TIMEOUT,
            "handoff and provider work consume the same deadline");
      }
    }

    try (Workers workers = new Workers(true)) {
      AtomicBoolean entered = new AtomicBoolean();
      workers.onHandoff =
          () -> {
            SystemClock.sleep(150);
            workers.release.countDown();
          };
      try {
        CameraDocumentSource.boundedMetadata(
            workers.pool, () -> false, 100, signal -> entered.getAndSet(true));
        throw new AssertionError("expired metadata returned a result");
      } catch (CameraDocumentSource.ListingException expected) {
        require(
            expected.reason == CameraDocumentSource.ListingException.Reason.TIMEOUT,
            "expired handoff reports timeout");
      }
      workers.finish();
      require(!entered.get(), "expired metadata never begins the provider query");
    }
  }

  private static final class Workers implements AutoCloseable {
    final CountDownLatch held = new CountDownLatch(2);
    final CountDownLatch release = new CountDownLatch(1);
    Runnable onHandoff = () -> {};
    final ThreadPoolExecutor pool;

    Workers(boolean afterCompletion) throws Exception {
      SynchronousQueue<Runnable> queue =
          new SynchronousQueue<>() {
            @Override
            public boolean offer(Runnable work, long time, TimeUnit unit)
                throws InterruptedException {
              onHandoff.run();
              return super.offer(work, time, unit);
            }
          };
      pool =
          new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, queue) {
            @Override
            protected void afterExecute(Runnable work, Throwable failure) {
              if (afterCompletion && held.getCount() > 0) hold();
            }
          };
      try {
        for (int i = 0; i < 2; i++) {
          if (afterCompletion) pool.submit(() -> 0).get(5, TimeUnit.SECONDS);
          else pool.execute(this::hold);
        }
        require(held.await(5, TimeUnit.SECONDS), "both metadata workers held by the fixture");
      } catch (Exception | AssertionError failure) {
        close();
        throw failure;
      }
    }

    private void hold() {
      held.countDown();
      try {
        release.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    void finish() throws InterruptedException {
      release.countDown();
      pool.shutdown();
      require(pool.awaitTermination(5, TimeUnit.SECONDS), "metadata workers finish");
    }

    @Override
    public void close() {
      release.countDown();
      pool.shutdownNow();
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
