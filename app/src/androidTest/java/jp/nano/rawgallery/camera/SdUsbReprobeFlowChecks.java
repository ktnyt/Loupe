package jp.nano.rawgallery.camera;

import android.app.Activity;
import android.app.Instrumentation;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.Closeable;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import jp.nano.rawgallery.camera.SdUsbReprobe.Reader;
import jp.nano.rawgallery.camera.SdUsbReprobe.Snapshot;
import jp.nano.rawgallery.camera.SdUsbReprobe.Volume;

/** Fakes the platform and system dialogs while driving the actual controller lifecycle. */
public final class SdUsbReprobeFlowChecks {
  private static final String TARGET = "3000-0001";
  private static final String SOURCE = "3000-0002";
  private static final String CHANGED_SOURCE = "3000-0003";
  private static final Reader READER = new Reader("fake-anker", 2, 8644, 45152, 0);

  private SdUsbReprobeFlowChecks() {}

  public static void run(Instrumentation test, Activity activity) {
    test.runOnMainSync(
        () -> {
          try (var flow = new Flow(activity);
              var queued = CameraImportGate.tryAdmit()) {
            require(
                queued != null,
                "An existing transfer can reserve admission before its worker runs");
            flow.controller.resume();
            flow.controller.request();
            require(flow.open != null, "Show preparation guidance");
            flow.open.run();
            require(
                !flow.controller.preparing()
                    && flow.platform.claims == 0
                    && flow.last.message().contains("処理中"),
                "An admitted but queued transfer blocks opening Settings");
          }
          try (var flow = new Flow(activity)) {
            flow.arm();
            flow.assertReserved();
            flow.controller.resume();
            require(
                flow.platform.claims == 0 && !flow.controller.preparing(),
                "Returning without ejection releases preparation without a claim");
          }
          try (var flow = new Flow(activity)) {
            flow.arm();
            flow.platform.snapshot = new Snapshot(null, null, List.of(), false);
            flow.controller.observed();
            flow.platform.snapshot = ejected();
            flow.controller.observed();
            flow.assertReserved();
            flow.controller.resume();
            flow.controller.request();
            require(
                flow.confirm == null && flow.platform.claims == 0 && !flow.controller.preparing(),
                "A disappearance while Settings is foreground invalidates authorization permanently");
          }
          try (var flow = new Flow(activity)) {
            flow.arm();
            flow.platform.snapshot = ejected();
            flow.controller.storageChanged(
                new SdUsbReprobe.VisibleVolume(SOURCE, "removed", false, false, true));
            flow.assertReserved();
            flow.controller.resume();
            require(
                !flow.controller.preparing() && flow.platform.claims == 0,
                "A transient removal event invalidates proof even after the same UUID list returns");
          }
          try (var flow = new Flow(activity)) {
            flow.arm();
            flow.now += 3 * 60_000;
            flow.platform.snapshot = ejected();
            flow.controller.observed();
            flow.assertReserved();
            flow.controller.resume();
            require(
                !flow.controller.preparing() && flow.platform.claims == 0,
                "Expired Settings proof remains exclusive until the user returns");
          }
          try (var flow = new Flow(activity)) {
            flow.platform.snapshot = new Snapshot(READER, TARGET, mounted(SOURCE).volumes(), false);
            flow.controller.resume();
            flow.controller.request();
            Consumer<Boolean> result = flow.platform.permission;
            require(result != null, "Request foreground USB permission");
            flow.controller.stop();
            flow.platform.snapshot = mounted(SOURCE);
            result.accept(true);
            flow.controller.resume();
            require(
                flow.platform.permissionClosed && flow.open == null && flow.platform.claims == 0,
                "A USB permission result cannot reauthorize a stopped activity");
          }
          try (var flow = new Flow(activity)) {
            flow.arm();
            flow.platform.snapshot = ejected();
            flow.controller.observed();
            flow.controller.resume();
            require(
                flow.platform.claims == 0 && flow.confirm == null,
                "A storage notification and settings return never claim");
            flow.controller.request();
            require(flow.confirm != null, "An explicit second action enables confirmation");
            flow.controller.destroy();
            flow.confirm.run();
            require(
                flow.platform.claims == 0 && !flow.controller.preparing(),
                "Rotation tears down the settings proof");
          }
          try (var flow = new Flow(activity)) {
            flow.ejectAndConfirm();
            flow.platform.snapshot = new Snapshot(null, null, List.of(), false);
            flow.controller.storageChanged(
                new SdUsbReprobe.VisibleVolume(SOURCE, "removed", false, false, true));
            flow.platform.snapshot = ejected();
            flow.controller.observed();
            flow.tasks.remove().run();
            require(
                flow.platform.claims == 0,
                "A removal after confirmation invalidates the parked worker even if the inventory returns");
          }
          try (var flow = new Flow(activity)) {
            flow.ejectAndConfirm();
            flow.assertReserved();
            flow.controller.destroy();
            flow.assertReserved();
            require(flow.platform.claims == 0, "Waiting worker has not touched USB");
            flow.tasks.remove().run();
            require(flow.platform.claims == 0, "A canceled worker leaves USB untouched");
            require(
                !CameraImportGate.isPreparationReserved(), "Worker releases its own reservation");
          }
        });

    var ready = new java.util.concurrent.atomic.AtomicReference<Flow>();
    test.runOnMainSync(
        () -> {
          var flow = new Flow(activity);
          ready.set(flow);
          flow.platform.restored = mounted(CHANGED_SOURCE);
          flow.platform.folder = tree(CHANGED_SOURCE + ":DCIM");
          flow.platform.closedUsb = flow.controller::observed;
          flow.ejectAndConfirm();
          flow.assertReserved();
          flow.tasks.remove().run();
          require(
              flow.platform.claims == 1 && flow.platform.releases == 1 && flow.platform.closed == 1,
              "One explicit ejection authorizes exactly one claim and cleanup");
        });
    test.waitForIdleSync();
    test.runOnMainSync(
        () -> {
          try (var flow = ready.get()) {
            require(
                flow.platform.checkedSource.equals(CHANGED_SOURCE)
                    && flow.last.message().contains("元カードと保存先を確認しました"),
                "Verify the newly registered source");
            try (var ordinary = CameraImportGate.tryAdmit()) {
              require(ordinary != null, "Normal admissions can resume after the completed worker");
            }
            flow.platform.snapshot =
                new Snapshot(READER, TARGET, List.of(new Volume(TARGET, "mounted")), true);
            flow.controller.observed();
            require(flow.last.message().isEmpty(), "Source removal invalidates READY immediately");
          }
        });

    test.runOnMainSync(
        () -> {
          var flow = new Flow(activity);
          ready.set(flow);
          flow.platform.folder = tree(SOURCE + ":");
          flow.ejectAndConfirm();
          flow.tasks.remove().run();
        });
    test.waitForIdleSync();
    test.runOnMainSync(
        () -> {
          try (var flow = ready.get()) {
            require(
                flow.last.message().contains("取り込み元のフォルダを選んでください"),
                "A legacy volume-root tree does not satisfy the strict picker");
            flow.platform.snapshot = mounted(SOURCE);
            flow.controller.pause();
            flow.controller.resume();
            require(
                flow.last.message().isEmpty(),
                "Returning to the screen clears a stale positive outcome");
          }
        });
  }

  public static void volumeEvent(String uuid, boolean detached) {
    MountedCameraCard.volumeChanged(uuid, detached);
  }

  private static Uri tree(String document) {
    return DocumentsContract.buildTreeDocumentUri(
        "com.android.externalstorage.documents", document);
  }

  private static Snapshot mounted(String source) {
    return new Snapshot(
        READER,
        TARGET,
        List.of(new Volume(TARGET, "mounted"), new Volume(source, "mounted")),
        true);
  }

  private static Snapshot ejected() {
    return new Snapshot(
        READER,
        TARGET,
        List.of(new Volume(TARGET, "unmounted"), new Volume(SOURCE, "unmounted")),
        true);
  }

  private static final class Flow implements SdUsbReprobeController.Dialogs, AutoCloseable {
    final FakePlatform platform = new FakePlatform();
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    final SdUsbReprobeController controller;
    long now;
    Runnable open, confirm;
    SdUsbReprobeController.Display last;

    Flow(Activity activity) {
      controller =
          new SdUsbReprobeController(
              activity,
              platform,
              this,
              () -> true,
              display -> last = display,
              () -> {},
              () -> now,
              tasks::add);
    }

    @Override
    public void prepare(Runnable open, Runnable cancel) {
      this.open = open;
    }

    @Override
    public void confirm(Runnable confirm, Runnable cancel) {
      this.confirm = confirm;
    }

    @Override
    public void openSettings() {
      assertReserved();
      controller.stop();
    }

    void assertReserved() {
      require(CameraImportGate.isPreparationReserved(), "Re-detection owns camera admission");
      var automatic = CameraImportGate.tryAdmit();
      if (automatic != null) {
        automatic.close();
        throw new AssertionError(
            "Automatic transfer was accepted during Settings or the USB worker");
      }
      var competing = CameraImportGate.tryReservePreparation();
      if (competing != null) {
        competing.close();
        throw new AssertionError("Second preparation was accepted");
      }
    }

    void arm() {
      controller.resume();
      controller.request();
      require(open != null, "Show preparation guidance");
      open.run();
    }

    void ejectAndConfirm() {
      arm();
      platform.snapshot = ejected();
      controller.observed();
      controller.resume();
      controller.request();
      require(confirm != null && platform.claims == 0, "Wait for explicit confirmation");
      confirm.run();
      require(tasks.size() == 1, "Confirmation queues one worker");
    }

    @Override
    public void close() {
      controller.destroy();
      while (!tasks.isEmpty()) tasks.remove().run();
    }
  }

  private static final class FakePlatform
      implements SdUsbReprobeController.Platform, SdUsbReprobe.Session {
    Snapshot snapshot = mounted(SOURCE), restored = mounted(SOURCE);
    Uri folder = tree(SOURCE + ":DCIM");
    Consumer<Boolean> permission;
    boolean permissionClosed;
    int claims, releases, closed;
    String checkedSource;
    Runnable closedUsb = () -> {};

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
      return snapshot.equals(restored);
    }

    @Override
    public boolean sourceReachable(String source, String destination, BooleanSupplier cancelled) {
      checkedSource = source;
      return CameraDocumentSource.isImportTree(folder, false);
    }

    @Override
    public boolean claim() {
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
      closedUsb.run();
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
