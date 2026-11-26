package jp.nano.rawgallery.camera;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CameraOperationAdmissionTest {
  @Test
  void queuedAdmissionExcludesPreparationBeforeWorkerAcquiresIoLock() throws Exception {
    var worker = Executors.newSingleThreadExecutor();
    var entered = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    try (var admission = admit()) {
      assertNull(CameraImportGate.tryReservePreparation());
      var completion =
          worker.submit(
              new CameraOperationTask(
                  () -> {
                    try (admission) {
                      entered.countDown();
                      await(proceed);
                      assertTrue(
                          CameraImportOperationGate.runIfAvailable(
                              CameraImportGate.lock,
                              () -> assertNull(CameraImportGate.tryReservePreparation())));
                    }
                  },
                  admission::close));
      await(entered);
      assertFalse(CameraImportGate.lock.isLocked());
      assertNull(CameraImportGate.tryReservePreparation());
      proceed.countDown();
      completion.get(5, TimeUnit.SECONDS);
      preparationAvailable();
    } finally {
      proceed.countDown();
      worker.shutdownNow();
    }
  }

  @Test
  void directIoSubmissionExcludesPreparationInEitherOrder() {
    var queued = new AtomicReference<Runnable>();
    try (var reservation = reserve()) {
      assertNull(CameraImportGate.tryAdmit());
      assertNull(queued.get());
    }
    assertTrue(
        CameraOperationTask.executeAdmitted(
            queued::set,
            admit(),
            () ->
                assertTrue(
                    CameraImportOperationGate.runIfAvailable(
                        CameraImportGate.lock,
                        () -> assertNull(CameraImportGate.tryReservePreparation())))));
    assertNotNull(queued.get());
    assertFalse(CameraImportGate.lock.isLocked());
    assertNull(CameraImportGate.tryReservePreparation());
    queued.getAndSet(null).run();
    preparationAvailable();
  }

  @Test
  void queuedCancellationAndRejectedExecutorReleaseExactlyOnce() {
    var worker = Executors.newSingleThreadExecutor();
    worker.shutdown();
    try (var admission = admit();
        var other = admit()) {
      var canceled = new CameraOperationTask(() -> fail("Discarded task ran"), admission::close);
      assertThrows(RejectedExecutionException.class, () -> worker.execute(canceled));
      canceled.discardBeforeStart();
      canceled.discardBeforeStart();
      canceled.run();
      assertNull(CameraImportGate.tryReservePreparation());
      other.close();
      preparationAvailable();
    }
  }

  @Test
  void interruptingAnExecutingTaskKeepsAdmissionUntilTerminalCleanup() throws Exception {
    var worker = Executors.newSingleThreadExecutor();
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    try (var admission = admit()) {
      var task =
          new CameraOperationTask(
              () -> {
                try (admission) {
                  entered.countDown();
                  try {
                    new CountDownLatch(1).await();
                  } catch (InterruptedException expected) {
                    interrupted.countDown();
                    await(finish);
                  }
                }
              },
              admission::close);
      var completion = worker.submit(task);
      await(entered);
      task.discardBeforeStart();
      worker.shutdownNow();
      await(interrupted);
      assertNull(CameraImportGate.tryReservePreparation());
      finish.countDown();
      completion.get(5, TimeUnit.SECONDS);
      preparationAvailable();
    } finally {
      finish.countDown();
      worker.shutdownNow();
    }
  }

  @Test
  void pairedQueueRetainsCurrentAdmissionBetweenWorkers() {
    var queue = new CameraRegisteredQueue();
    try (var active = admit();
        var duplicate = admit();
        var queued = admit()) {
      queue.started("first");
      queue.offer("first", true);
      duplicate.close();
      queue.offer("next", false);
      queue.offer("next", true);
      queued.close();
      var next = queue.finished(false);
      assertNotNull(next);
      assertEquals("next", next.profileId);
      assertTrue(next.verify);
      assertFalse(CameraImportGate.lock.isLocked());
      assertNull(CameraImportGate.tryReservePreparation());
      queue.started(next.profileId);
      queue.offer("cancelled", false);
      assertNull(queue.finished(true));
      active.close();
      preparationAvailable();
    }
  }

  @Test
  void reservationCanBeCanceledByCurrentOwnerOnly() {
    var ui = reserve();
    ui.close();
    assertNull(ui.handoffToWorker());
    preparationAvailable();

    try (var renewedUi = reserve()) {
      assertTrue(CameraImportGate.isPreparationReserved());
      assertNull(CameraImportGate.tryAdmit());
      assertNull(CameraImportGate.tryReservePreparation());
      try (var worker = renewedUi.handoffToWorker()) {
        assertNotNull(worker);
        renewedUi.close();
        assertFalse(renewedUi.isOpen());
        assertTrue(worker.isOpen());
        assertNull(CameraImportGate.tryAdmit());
      }
      preparationAvailable();
    }
  }

  @Test
  void directIoLockAlsoPreventsPreparation() {
    var lock = CameraImportGate.lock;
    lock.lock();
    try {
      assertNull(CameraImportGate.tryReservePreparation());
    } finally {
      lock.unlock();
    }
    preparationAvailable();
  }

  private static CameraImportGate.Admission admit() {
    var admission = CameraImportGate.tryAdmit();
    assertNotNull(admission);
    return admission;
  }

  private static CameraImportGate.Reservation reserve() {
    var reservation = CameraImportGate.tryReservePreparation();
    assertNotNull(reservation);
    return reservation;
  }

  private static void preparationAvailable() {
    try (var reservation = reserve()) {
      assertTrue(reservation.isOpen());
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      fail(interrupted);
    }
  }
}
