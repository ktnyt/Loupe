package jp.nano.rawgallery.camera;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.concurrent.locks.ReentrantLock;

/** Regression: a registered request must not mutate a USB owner's progress or cancellation. */
public final class CameraImportOperationGateTest {
    public static void main(String[] args) throws Exception {
        ReentrantLock gate = new ReentrantLock();
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread usb = new Thread(() -> {
            gate.lock();
            try { held.countDown(); release.await(); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
            finally { gate.unlock(); }
        });
        usb.start();
        require(held.await(2, TimeUnit.SECONDS), "USB owns gate");
        Progress progress = new Progress();
        FutureTask<Boolean> attempt = new FutureTask<>(() -> CameraImportOperationGate.runIfAvailable(gate, () -> {
            progress.stop = false; progress.active = false; progress.message = "overwritten";
        }));
        Thread registered = new Thread(attempt);
        registered.start();
        try {
            boolean accepted = attempt.get(1, TimeUnit.SECONDS);
            require(!accepted, "Registered request rejected while USB owns gate");
            require(progress.stop && progress.active && progress.message.equals("USB copying"), "USB stop, active and message preserved");
        } finally { release.countDown(); usb.join(2000); registered.join(2000); }
        require(!usb.isAlive(), "USB finished");
        require(CameraImportOperationGate.runIfAvailable(gate, () -> {
            require(gate.isHeldByCurrentThread(), "Initialization owns gate");
            progress.stop = false; progress.message = "registered connecting";
        }), "Next explicit request can start");
        require(!progress.stop && progress.message.equals("registered connecting"), "Owned explicit start initializes progress");
        require(!gate.isLocked(), "Successful operation releases gate");
        try { CameraImportOperationGate.runIfAvailable(gate, () -> { throw new IllegalStateException("Injected"); }); }
        catch (IllegalStateException expected) { }
        require(!gate.isLocked(), "Failed operation releases gate");
        System.out.println("PASS CameraImportOperationGate usbStopPreserved=true progressPreserved=true nonblocking=true ownedStart=true releases=true");
    }
    private static final class Progress { boolean stop = true, active = true; String message = "USB copying"; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
