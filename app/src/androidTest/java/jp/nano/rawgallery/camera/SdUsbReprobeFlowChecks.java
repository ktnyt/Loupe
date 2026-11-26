package jp.nano.rawgallery.camera;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import java.io.Closeable;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import jp.nano.rawgallery.camera.SdUsbReprobe.Reader;
import jp.nano.rawgallery.camera.SdUsbReprobe.Snapshot;
import jp.nano.rawgallery.camera.SdUsbReprobe.Volume;
import jp.nano.rawgallery.camera.SdUsbReprobeController.Display;
import jp.nano.rawgallery.camera.SdUsbReprobeController.Restoration;

/** Uses mounted snapshots and a controlled worker without touching a USB device. */
public final class SdUsbReprobeFlowChecks {
  public static final String TARGET = "3000-0001";
  private static final String SOURCE = "3000-0002";
  private static final String CHANGED_SOURCE = "3000-0003";
  private static final Reader READER = new Reader("fake-anker", 2, 8644, 45152, 0);

  private SdUsbReprobeFlowChecks() {}

  public static void run(Instrumentation test, Activity activity) {
    test.runOnMainSync(
        () -> {
          try (var flow = new Harness(activity);
              var queued = CameraImportGate.tryAdmit()) {
            require(queued != null, "Acquire admission before a transfer runs");
            flow.request();
            require(
                !flow.controller.busy()
                    && flow.platform.permission == null
                    && flow.platform.claims == 0
                    && flow.last.message().contains("処理が終わって"),
                "An admitted, queued transfer prevents even requesting permission");
          }

          try (var flow = new Harness(activity)) {
            flow.request();
            require(flow.controller.busy(), "Permission wait is active");
            assertReserved();
            Consumer<Boolean> staleResult = flow.platform.permission;
            flow.request();
            require(flow.platform.permission == staleResult, "A duplicate cannot ask again");
            flow.controller.pause();
            staleResult.accept(true);
            flow.controller.stop();
            require(flow.platform.permissionClosed, "Stopping releases permission registration");
            require(!CameraImportGate.isPreparationReserved(), "Stopping releases permission gate");
            flow.controller.resume();
            require(flow.worker.isEmpty(), "Resume cannot restart a stopped request");
          }

          try (var flow = new Harness(activity)) {
            flow.platform.snapshot = mounted(SOURCE, true);
            flow.request();
            assertReserved();
            flow.request();
            require(flow.worker.size() == 1, "An explicit request queues exactly one handoff");
            flow.controller.pause();
            flow.runWorker();
            require(
                flow.platform.claims == 0 && !CameraImportGate.isPreparationReserved(),
                "A worker queued before leaving cannot claim");
          }

          try (var flow = new Harness(activity)) {
            flow.platform.snapshot = mounted(SOURCE, true);
            flow.request();
            volumeEvent(SOURCE, true);
            volumeEvent(SOURCE, false);
            flow.runWorker();
            require(
                flow.platform.claims == 0,
                "A mount generation change invalidates even an identical snapshot");
          }
        });

    try (var flow = new Harness(activity)) {
      test.runOnMainSync(
          () -> {
            flow.start();
            flow.request();
            flow.platform.permission.accept(false);
          });
      test.waitForIdleSync();
      test.runOnMainSync(
          () ->
              require(
                  flow.platform.permissionClosed
                      && flow.platform.claims == 0
                      && !CameraImportGate.isPreparationReserved()
                      && flow.last.message().contains("使用許可を確認できません"),
                  "Denied permission releases the gate and cannot claim"));
    }

    try (var flow = new Harness(activity)) {
      test.runOnMainSync(
          () -> {
            flow.start();
            flow.request();
            flow.controller.pause();
            flow.platform.snapshot = mounted(SOURCE, true);
            flow.platform.permission.accept(true);
          });
      test.waitForIdleSync();
      test.runOnMainSync(
          () -> {
            require(
                flow.worker.isEmpty(),
                "Permission response alone cannot claim from the background");
            assertReserved();
            flow.controller.resume();
            flow.runWorker();
          });
      test.waitForIdleSync();
      test.runOnMainSync(
          () -> {
            require(
                flow.platform.claims == 1,
                "Granted foreground request must claim once. claims="
                    + flow.platform.claims
                    + " status="
                    + flow.last);
            require(
                flow.platform.releases == 1 && flow.platform.closed == 1,
                "Granted request releases and closes once. release="
                    + flow.platform.releases
                    + " close="
                    + flow.platform.closed);
            require(
                CHANGED_SOURCE.equals(flow.platform.checkedSource),
                "Check the newly mounted source. checked=" + flow.platform.checkedSource);
            require(
                flow.restored != null && flow.restored.sourceRequired(),
                "A new source without a grant requires a folder. restored="
                    + flow.restored
                    + " status="
                    + flow.last);
          });
    }

    for (boolean replaceReader : new boolean[] {true, false}) {
      try (var flow = new Harness(activity)) {
        test.runOnMainSync(
            () -> {
              flow.start();
              flow.request();
              flow.platform.snapshot =
                  new Snapshot(
                      replaceReader ? new Reader(READER.name(), 3, 8644, 45152, 0) : READER,
                      replaceReader ? TARGET : SOURCE,
                      mounted(SOURCE, true).volumes(),
                      true);
              flow.platform.permission.accept(true);
            });
        test.waitForIdleSync();
        test.runOnMainSync(
            () ->
                require(
                    flow.worker.isEmpty()
                        && flow.platform.claims == 0
                        && flow.platform.permissionClosed
                        && !CameraImportGate.isPreparationReserved(),
                    "A replacement reader or destination cannot inherit an outstanding permission flow"));
      }
    }
  }

  private static void assertReserved() {
    try (var blocked = CameraImportGate.tryAdmit()) {
      require(
          blocked == null && CameraImportGate.isPreparationReserved(),
          "Refresh excludes transfers");
    }
  }

  public static void volumeEvent(String uuid, boolean detached) {
    MountedCameraCard.volumeChanged(
        uuid,
        detached ? android.os.Environment.MEDIA_UNMOUNTED : android.os.Environment.MEDIA_MOUNTED);
  }

  public static void legacyVolumeEvent(String uuid, String action) {
    MountedCameraCard.legacyMediaChanged(uuid, action);
  }

  private static Snapshot mounted(String source, boolean permitted) {
    return new Snapshot(
        READER,
        TARGET,
        List.of(new Volume(TARGET, "mounted"), new Volume(source, "mounted")),
        permitted);
  }

  public static final class Harness implements AutoCloseable {
    private final Activity activity;
    private final ArrayDeque<Runnable> worker = new ArrayDeque<>();
    private final FakePlatform platform = new FakePlatform();
    private final SdUsbReprobeController controller;
    private Display last;
    private Restoration restored;

    private Harness(Activity activity) {
      this(activity, () -> true, ignored -> {}, () -> {});
      if (android.os.Looper.getMainLooper().isCurrentThread()) start();
    }

    public Harness(
        Activity activity, BooleanSupplier idle, Consumer<Display> render, Runnable discardSource) {
      this.activity = activity;
      controller =
          new SdUsbReprobeController(
              activity,
              platform,
              idle,
              display -> {
                last = display;
                render.accept(display);
              },
              discardSource,
              SystemClock::elapsedRealtime,
              worker::add);
    }

    public SdUsbReprobeController controller() {
      return controller;
    }

    public void start() {
      controller.resume();
    }

    public void setPermitted() {
      platform.snapshot = mounted(SOURCE, true);
    }

    public int claims() {
      return platform.claims;
    }

    public int queued() {
      return worker.size();
    }

    public void runWorker() {
      worker.remove().run();
    }

    private void request() {
      require(controller.request(false, value -> restored = value), "Supported manual request");
    }

    @Override
    public void close() {
      if (android.os.Looper.getMainLooper().isCurrentThread()) {
        controller.destroy();
      } else {
        var finished = new java.util.concurrent.CountDownLatch(1);
        activity.runOnUiThread(
            () -> {
              controller.destroy();
              finished.countDown();
            });
        try {
          require(
              finished.await(5, java.util.concurrent.TimeUnit.SECONDS), "Close test controller");
        } catch (InterruptedException stopped) {
          Thread.currentThread().interrupt();
          throw new AssertionError(stopped);
        }
      }
    }
  }

  private static final class FakePlatform
      implements SdUsbReprobeController.Platform, SdUsbReprobe.Session {
    Snapshot snapshot = mounted(SOURCE, false);
    final Snapshot restored = mounted(CHANGED_SOURCE, true);
    Consumer<Boolean> permission;
    boolean permissionClosed;
    int claims, releases, closed;
    String checkedSource;

    @Override
    public Snapshot snapshot() {
      return snapshot;
    }

    @Override
    public Closeable requestPermission(
        Activity activity, Reader reader, Consumer<Boolean> callback) {
      permission = callback;
      return () -> permissionClosed = true;
    }

    @Override
    public SdUsbReprobe.Session open(Reader reader) {
      return this;
    }

    @Override
    public boolean destinationWritable(String destination) {
      return snapshot.equals(restored) && TARGET.equals(destination);
    }

    @Override
    public boolean sourceReachable(String source, String destination, BooleanSupplier cancelled) {
      checkedSource = source;
      return false;
    }

    @Override
    public boolean claim() {
      assertReserved();
      claims++;
      return true;
    }

    @Override
    public boolean release() {
      releases++;
      return true;
    }

    @Override
    public void close() {
      closed++;
      snapshot = restored;
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
