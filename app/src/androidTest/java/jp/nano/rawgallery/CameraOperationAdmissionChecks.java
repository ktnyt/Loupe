package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import jp.nano.rawgallery.camera.CameraDifferentialService;
import jp.nano.rawgallery.camera.CameraImportGate;
import jp.nano.rawgallery.camera.CameraOperationDelivery;
import jp.nano.rawgallery.camera.ImportProgress;
import jp.nano.rawgallery.camera.ImportService;

final class CameraOperationAdmissionChecks {
  static final String MODE = "camera-pilot-operation-admission";
  private static final String REQUEST_ID = "import_request_id";

  static String run(Instrumentation test) throws Exception {
    Context context = test.getTargetContext();
    try (var qemu =
        new ParcelFileDescriptor.AutoCloseInputStream(
            test.getUiAutomation().executeShellCommand("getprop ro.kernel.qemu"))) {
      require(qemu.read() == '1', "Requires an emulator");
    }
    require(
        context.getSystemService(UsbManager.class).getDeviceList().isEmpty(),
        "Requires an emulator with no attached USB device");
    Activity activity =
        test.startActivitySync(
            CameraImportActivity.showProgressIntent(context)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      checkStartFailure(activity);
      checkDeliveryIdentity(activity);
      checkReservationBlocksTransfers(activity);
      checkUsbDelivery(test, activity);
      checkMoveCancellation(test, activity);
      return "PASS camera operation admission service delivery, invalid/replayed request, "
          + "startup failure, canceled move and WATCH";
    } finally {
      test.runOnMainSync(activity::finish);
    }
  }

  private static void checkStartFailure(Activity activity) {
    var replay = new AtomicReference<Intent>();
    Context failing =
        new ContextWrapper(activity) {
          @Override
          public ComponentName startForegroundService(Intent intent) {
            replay.set(new Intent(intent));
            throw new IllegalStateException("Simulated foreground-service failure");
          }
        };
    try {
      CameraOperationDelivery.submit(
          failing, new Intent(activity, ImportService.class), admit(), null);
      throw new AssertionError("Expected service startup failure");
    } catch (IllegalStateException expected) {
      require(
          "Simulated foreground-service failure".equals(expected.getMessage()),
          "Wrong startup failure");
    }
    require(
        CameraOperationDelivery.claim(
                replay.get(), new ComponentName(activity, ImportService.class))
            == null,
        "Failed submission retained a claim");
    requirePreparationAvailable();
  }

  private static void checkDeliveryIdentity(Activity activity) {
    var submitted = new AtomicReference<Intent>();
    try (var admission = admit()) {
      Object payload = new Object();
      require(
          CameraOperationDelivery.submit(
              captureStart(activity, submitted),
              new Intent(activity, ImportService.class),
              admission,
              payload),
          "Capture real delivery");
      var intended = new ComponentName(activity, ImportService.class);
      var wrong = new ComponentName(activity, PhotoMoveService.class);
      require(
          CameraOperationDelivery.claim(new Intent(submitted.get()).setComponent(wrong), wrong)
              == null,
          "Another service was able to claim the submission");
      require(
          CameraImportGate.tryReservePreparation() == null,
          "Wrong recipient released the original submission");
      var owner = CameraOperationDelivery.claim(submitted.get(), intended);
      require(
          owner != null && owner.admission() == admission && owner.payload() == payload,
          "Intended service did not receive the original owner and payload");
      require(
          CameraOperationDelivery.claim(submitted.get(), intended) == null,
          "The same Binder was claimed twice");
      require(
          CameraImportGate.tryReservePreparation() == null,
          "Duplicate delivery closed the active owner's admission");
    }
    requirePreparationAvailable();
  }

  private static void checkReservationBlocksTransfers(Activity activity) {
    var starts = new AtomicReference<Intent>();
    Context capture = captureStart(activity, starts);
    try (var reservation = CameraImportGate.tryReservePreparation()) {
      require(reservation != null, "Reserve preparation");
      require(
          !PhotoMoveService.launch(capture, null), "Photo move was accepted during reservation");
      try {
        CameraDifferentialService.startWifi(capture, false);
        throw new AssertionError("Wi-Fi transfer was accepted during reservation");
      } catch (IllegalStateException expected) {
        require(starts.get() == null, "Transfer was submitted to Android during reservation");
      }
      CameraDifferentialService.updateAuto(capture);
      var watch = starts.get();
      require(watch != null && "watch".equals(watch.getAction()), "WATCH startup did not pass");
      require(
          CameraOperationDelivery.claim(
                  watch, new ComponentName(activity, CameraDifferentialService.class))
              == null,
          "WATCH should have no transfer admission");
    }
  }

  private static void checkUsbDelivery(Instrumentation test, Activity activity) {
    String originalId = UUID.randomUUID().toString();
    test.runOnMainSync(
        () -> {
          Intent invalid =
              new Intent(activity, ImportService.class).putExtra(REQUEST_ID, originalId);
          Context context =
              new ContextWrapper(activity) {
                @Override
                public ComponentName startForegroundService(Intent intent) {
                  ComponentName component = super.startForegroundService(intent);
                  require(
                      CameraImportGate.tryReservePreparation() == null,
                      "Admitted request did not block preparation before Android service delivery");
                  Intent replay = new Intent(intent).putExtra(REQUEST_ID, "replay-" + originalId);
                  require(
                      super.startForegroundService(replay) != null,
                      "Android accepted the duplicate intent");
                  return component;
                }
              };
          require(
              CameraOperationDelivery.submit(context, invalid, admit(), null),
              "Android accepted the invalid USB fixture intent for service-side rejection");
        });
    await(
        () -> {
          var completion = ImportProgress.INSTANCE.getCompletion();
          return completion != null && originalId.equals(completion.getRequestId());
        },
        "Import service did not claim the actual Binder or report the invalid USB request");
    test.waitForIdleSync();
    var completion = ImportProgress.INSTANCE.getCompletion();
    require(
        originalId.equals(completion.getRequestId()) && !completion.getSucceeded(),
        "Replay changed the original invalid request completion");
    requirePreparationAvailable();
  }

  private static void checkMoveCancellation(Instrumentation test, Activity activity) {
    var request = new AtomicReference<Intent>();
    test.runOnMainSync(
        () -> {
          require(
              PhotoMoveService.launch(captureStart(activity, request), null),
              "Move accepted before delivery");
          require(
              CameraImportGate.tryReservePreparation() == null,
              "Pending move failed to block preparation");
          PhotoMoveService.stopMove();
          require(
              activity.startForegroundService(request.get()) != null,
              "Deliver canceled move through Android");
        });
    await(() -> !PhotoMoveService.state().running(), "Canceled move did not finish");
    require(
        "移動を中断しました。".equals(PhotoMoveService.state().message())
            && PhotoMoveService.state().batch() == null,
        "Canceled move incorrectly reached journal handling");
    requirePreparationAvailable();
  }

  private static Context captureStart(Activity activity, AtomicReference<Intent> captured) {
    return new ContextWrapper(activity) {
      @Override
      public ComponentName startForegroundService(Intent intent) {
        captured.set(new Intent(intent));
        return intent.getComponent();
      }
    };
  }

  private static CameraImportGate.Admission admit() {
    var admission = CameraImportGate.tryAdmit();
    require(admission != null, "Accept transfer");
    return admission;
  }

  private static void requirePreparationAvailable() {
    try (var reservation = CameraImportGate.tryReservePreparation()) {
      require(reservation != null, "Admission was not released");
    }
  }

  private static void await(BooleanSupplier condition, String failure) {
    long deadline = SystemClock.elapsedRealtime() + 5_000;
    while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline)
      SystemClock.sleep(20);
    require(condition.getAsBoolean(), failure);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
