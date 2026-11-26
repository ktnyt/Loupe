package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
      checkSdCancellation(test, activity);
      return "PASS camera operation admission service delivery, invalid/replayed request, "
          + "startup failure, pending move/SD cancellation and WATCH";
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
    Context capture = captureStart(activity, submitted);
    var admission = admit();
    Object payload = new Object();
    var intent = new Intent(activity, ImportService.class);
    var delivery = CameraOperationDelivery.prepare(intent, admission, payload);
    require(delivery != null && delivery.start(capture, intent), "Capture service delivery");
    try {
      require(!admission.isOpen(), "Submission left the original owner active");
      var original = submitted.get();
      require(
          !CameraOperationDelivery.submit(
                  capture, new Intent(activity, ImportService.class), admission, payload)
              && submitted.get() == original,
          "Second submission reused the original admission");
      require(!delivery.start(capture, intent), "The same delivery was submitted twice");
      admission.close();

      var intended = new ComponentName(activity, ImportService.class);
      var wrong = new ComponentName(activity, PhotoMoveService.class);
      require(
          CameraOperationDelivery.claim(new Intent(original).setComponent(wrong), wrong) == null,
          "Another service was able to claim the submission");
      requirePreparationBlocked("Wrong recipient or the former owner closed a pending submission");
      var owner = CameraOperationDelivery.claim(original, intended);
      require(
          owner != null && owner.admission() != admission && owner.payload() == payload,
          "Intended service did not receive the new owner and original payload");
      try (var active = owner.admission()) {
        require(
            CameraOperationDelivery.claim(original, intended) == null, "Binder replay was claimed");
        require(!delivery.discardPending(), "Pending cancellation closed a worker-owned admission");
        requirePreparationBlocked("Replay or cancellation closed the active owner's admission");
      }
    } finally {
      delivery.discardPending();
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
    var oldRequest = new AtomicReference<Intent>();
    var newRequest = new AtomicReference<Intent>();
    test.runOnMainSync(
        () -> {
          require(
              PhotoMoveService.launch(captureStart(activity, oldRequest), null),
              "Move accepted before delivery");
          requirePreparationBlocked("Pending move failed to block preparation");
          PhotoMoveService.stopMove();
          require(!PhotoMoveService.state().running(), "Undelivered move left stale running state");
          require(
              "移動を中断しました。".equals(PhotoMoveService.state().message()),
              "Move did not report cancellation");
          requirePreparationAvailable();

          require(
              PhotoMoveService.launch(captureStart(activity, newRequest), null),
              "A fresh move was blocked by the canceled request");
          require(
              activity.startForegroundService(oldRequest.get()) != null,
              "Deliver canceled move through Android while the fresh move is still pending");
        });
    test.waitForIdleSync();
    require(PhotoMoveService.state().running(), "Stale delivery clobbered the fresh move state");
    requirePreparationBlocked("Stale delivery closed the fresh move admission");
    require(
        CameraOperationDelivery.claim(
                oldRequest.get(), new ComponentName(activity, PhotoMoveService.class))
            == null,
        "Canceled move could still be claimed");
    PhotoMoveService.stopMove();
    require(
        !PhotoMoveService.state().running() && PhotoMoveService.state().batch() == null,
        "Canceled move incorrectly reached journal handling");
    requirePreparationAvailable();
  }

  private static void checkSdCancellation(Instrumentation test, Activity activity) {
    var request = new AtomicReference<Intent>();
    var confirmed = new AtomicBoolean();
    var confirmation =
        new CameraDifferentialService.SdConfirmation((unused, failure) -> confirmed.set(true));
    CameraDifferentialService.submitConfirmedSd(
        captureStart(activity, request),
        Uri.parse("content://invalid/tree/test"),
        false,
        null,
        null,
        0,
        PhotoStorage.PRIMARY,
        false,
        false,
        null,
        false,
        confirmation);
    require(request.get() != null, "Android submission did not receive the SD request");
    requirePreparationBlocked("Pending SD confirmation did not block preparation");
    confirmation.cancel();
    requirePreparationAvailable();

    test.runOnMainSync(
        () -> {
          require(
              activity.startForegroundService(request.get()) != null,
              "Deliver canceled SD confirmation through Android");
        });
    test.waitForIdleSync();
    require(!confirmed.get(), "Canceled SD confirmation sent a completion response");
    require(
        CameraOperationDelivery.claim(
                request.get(), new ComponentName(activity, CameraDifferentialService.class))
            == null,
        "Canceled SD confirmation could still be claimed");
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

  private static void requirePreparationBlocked(String message) {
    try (var reservation = CameraImportGate.tryReservePreparation()) {
      require(reservation == null, message);
    }
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
