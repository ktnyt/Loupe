package jp.nano.rawgallery.camera;

/** Deterministic service admission regression: no Android, camera, network or photo access. */
public final class CameraRegisteredQueueTest {
    public static void main(String[] args) {
        CameraRegisteredQueue queue = new CameraRegisteredQueue();
        queue.started(null); // A passive Internet Wi-Fi watcher already occupies the worker.
        queue.offer("camera-a", true);
        CameraRegisteredQueue.Request first = queue.finished(false);
        require(first != null && first.profileId.equals("camera-a") && first.verify, "Explicit request survives automatic wait");
        require(queue.finished(false) == null, "Handoff only once");
        queue.started("camera-a");
        queue.offer("camera-a", true);
        require(queue.finished(false) == null, "Active profile is not imported twice");
        queue.started(null);
        queue.offer("camera-a", true);
        queue.offer("camera-b", false);
        CameraRegisteredQueue.Request latest = queue.finished(false);
        require(latest != null && latest.profileId.equals("camera-b") && !latest.verify, "Last explicit request wins");
        queue.started("camera-a");
        queue.offer("camera-b", true);
        queue.offer("camera-a", true);
        require(queue.finished(false).profileId.equals("camera-b"), "Active duplicate preserves pending different camera");
        queue.started(null);
        queue.offer("camera-a", true);
        require(queue.finished(true) == null && queue.finished(false) == null, "Cancellation permanently discards pending request");
        queue.started(null);
        queue.offer("camera-a", true);
        queue.clear();
        require(queue.finished(false) == null, "Service destruction discards pending request");
        System.out.println("PASS CameraRegisteredQueue autoBusyHandoff=true once=true activeDuplicate=true lastWins=true cancel=true destroy=true");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
