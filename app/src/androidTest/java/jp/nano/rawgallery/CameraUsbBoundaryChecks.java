package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Parcel;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import jp.nano.rawgallery.camera.CameraCardRouting;
import jp.nano.rawgallery.camera.CameraDifferentialService;
import jp.nano.rawgallery.camera.CameraImportGate;
import jp.nano.rawgallery.camera.CameraUsb;
import jp.nano.rawgallery.camera.ImportProgress;
import jp.nano.rawgallery.camera.MountedCameraCard;
import jp.nano.rawgallery.camera.SdUsbReprobeFlowChecks;

/** Called only by the emulator-only, no-attached-USB SD reprobe fixture. */
final class CameraUsbBoundaryChecks {
  private static final String REOPEN = "取り込み画面を開き直してください";
  private static final String RETRY = "「SDカードを再認識」でやり直してください";

  static void checkColdPrimaryRegistration(
      Instrumentation test, File directory, int width, float font) throws Exception {
    Field observing = field(MountedCameraCard.class, "observing");
    require(!observing.getBoolean(null), "Primary registration fault starts in a cold process");
    Field primary = field(MountedCameraCard.class, "registerPrimaryMedia");
    Object platform = primary.get(null);
    var calls = new AtomicInteger();
    try {
      primary.set(
          null,
          (BiConsumer<Context, BroadcastReceiver>)
              (context, receiver) -> {
                calls.incrementAndGet();
                throw new SecurityException("synthetic primary media registration failure");
              });
      checkBlockedEntries(
          test, directory, width, font, REOPEN, "primary-observer-registration-failed");
      require(
          calls.get() >= 2 && !observing.getBoolean(null),
          "Both cold entries reach the real registration boundary and leave it unavailable");
      checkWatcherRouting(test, primary, platform);
    } finally {
      primary.set(null, platform);
    }
  }

  private static void checkWatcherRouting(Instrumentation test, Field primary, Object platform)
      throws Exception {
    Context app = test.getTargetContext();
    String settingsName = "camera-watcher-fixture-" + UUID.randomUUID();
    SharedPreferences settings = app.getSharedPreferences(settingsName, Context.MODE_PRIVATE);
    require(
        settings.edit().putBoolean("sd_auto", true).commit(), "Set synthetic watcher preference");
    Context isolated =
        new ContextWrapper(app) {
          @Override
          public SharedPreferences getSharedPreferences(String name, int mode) {
            return "camera_import".equals(name) ? settings : super.getSharedPreferences(name, mode);
          }
        };
    var service = new CameraDifferentialService();
    var attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
    var configure = CameraDifferentialService.class.getDeclaredMethod("configureWatch");
    var watching = CameraDifferentialService.class.getDeclaredMethod("isWatchingSdMounts");
    var mountReceiver = field(CameraDifferentialService.class, "mediaReceiver");
    for (var method : new java.lang.reflect.Method[] {attach, configure, watching})
      method.setAccessible(true);
    CameraImportActivity activity = null;
    try {
      test.runOnMainSync(
          () -> {
            invoke(attach, service, isolated);
            service.onCreate();
            invoke(configure, service);
            try {
              require(
                  mountReceiver.get(service) != null,
                  "The separate mount-only service subscription successfully registers");
            } catch (IllegalAccessException unavailable) {
              throw new AssertionError(unavailable);
            }
            require(
                attachRoute(watching) == CameraCardRouting.Attach.CONFIRM_MOUNTED,
                "Attach uses confirmation when only the service mount receiver is registered");
          });
      primary.set(null, platform);
      activity =
          (CameraImportActivity)
              test.startActivitySync(
                  new Intent(app, CameraImportActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      require(
          field(CameraImportActivity.class, "removeMountObserver").get(activity) != null,
          "Opening import registers the primary media observer after recovery");
      test.runOnMainSync(
          () ->
              require(
                  attachRoute(watching) == CameraCardRouting.Attach.OPEN_SCREEN,
                  "Attach defers to the watcher when both actual subscriptions are active"));
    } finally {
      if (activity != null) {
        CameraImportActivity opened = activity;
        test.runOnMainSync(opened::finish);
      }
      test.runOnMainSync(service::onDestroy);
      test.waitForIdleSync();
      app.deleteSharedPreferences(settingsName);
    }
  }

  private static CameraCardRouting.Attach attachRoute(java.lang.reflect.Method watching) {
    return CameraCardRouting.onAttach(
        CameraCardRouting.route(true, false, true), true, (Boolean) invoke(watching, null));
  }

  private static Object invoke(
      java.lang.reflect.Method method, Object receiver, Object... arguments) {
    try {
      return method.invoke(receiver, arguments);
    } catch (ReflectiveOperationException unavailable) {
      throw new AssertionError(unavailable);
    }
  }

  static void checkUsbQueries(Instrumentation test, File directory, int width, float font)
      throws Exception {
    Field queries = field(CameraUsb.class, "queries");
    Object platform = queries.get(null);
    UsbDevice device = syntheticCamera();
    try {
      for (boolean enumeration : new boolean[] {true, false}) {
        var fault = new UsbFault(device, enumeration);
        queries.set(null, fault);
        checkBlockedEntries(
            test,
            directory,
            width,
            font,
            RETRY,
            enumeration ? "usb-enumeration-failed" : "usb-permission-query-failed");
        require(
            fault.enumerations.get() > 0 && (enumeration || fault.permissions.get() > 0),
            "Opening, rendering and retry reach the failing USB query");
      }
    } finally {
      queries.set(null, platform);
    }
  }

  private static void checkBlockedEntries(
      Instrumentation test, File directory, int width, float font, String expected, String capture)
      throws Exception {
    for (boolean quick : new boolean[] {false, true}) {
      CameraImportActivity activity =
          (CameraImportActivity)
              test.startActivitySync(
                  CameraImportActivity.manualImportIntent(test.getTargetContext(), quick)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      var sourceCheck = field(CameraImportActivity.class, "sourceCheck");
      var scan = field(CameraImportActivity.class, "sdStatusCancellation");
      var mountObserver = field(CameraImportActivity.class, "removeMountObserver");
      try {
        test.waitForIdleSync();
        test.runOnMainSync(
            () -> {
              try {
                View root = activity.getWindow().getDecorView();
                TextView status = root.findViewWithTag("sd-reprobe-status");
                require(
                    !activity.isFinishing() && status.getText().toString().contains(expected),
                    "Real import opening survived and shows " + expected);
                View action = root.findViewWithTag("sd-reprobe-start");
                if (REOPEN.equals(expected)) {
                  require(!action.isEnabled(), "Reopening is required before retry is available");
                } else {
                  require(
                      action.isEnabled() && action.performClick(),
                      "Tap actual SD retry during a query error");
                  require(
                      status.getText().toString().contains(expected),
                      "Retry survives the same query error");
                }
                require(
                    !root.findViewWithTag("sd-import-start").isEnabled()
                        && !root.findViewWithTag("sd-import-settings").isEnabled(),
                    "Affected scan/selection controls remain blocked");
                require(
                    !((CameraSourceCheck) sourceCheck.get(activity)).isPending()
                        && scan.get(activity) == null
                        && !ImportProgress.INSTANCE.getActive(),
                    "Fault cannot fall through to ordinary source discovery, scan or import");
                require(
                    !REOPEN.equals(expected) || mountObserver.get(activity) == null,
                    "Failure never returns a dummy SD mount registration");
                require(
                    !CameraImportGate.isPreparationReserved(),
                    "No reprobe admitted during the fault");
                status.requestRectangleOnScreen(
                    new Rect(0, 0, status.getWidth(), status.getHeight()), true);
              } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
              }
            });
        if (!quick)
          CameraSdUsbReprobeChecks.capture(
              test, activity.getWindow(), directory, width, font, capture);
      } finally {
        test.runOnMainSync(activity::finish);
        test.waitForIdleSync();
      }
    }
  }

  static void checkPermissionResults(Instrumentation test, CameraImportActivity activity)
      throws Exception {
    require(
        field(CameraImportActivity.class, "removeMountObserver").get(activity) != null,
        "Fresh entry after restoring the registrar has a real observer");
    Field queries = field(CameraUsb.class, "queries");
    Object platform = queries.get(null);
    UsbDevice device = syntheticCamera();
    try {
      for (boolean enumeration : new boolean[] {true, false}) {
        var fault = new UsbFault(device, enumeration);
        queries.set(null, fault);
        var broadcasts = new PermissionContext(activity);
        var replies = new AtomicInteger();
        try (var flow =
            new SdUsbReprobeFlowChecks.Harness(activity, () -> true, ignored -> {}, () -> {})) {
          test.runOnMainSync(
              () -> {
                flow.start();
                flow.usePermissionReceiver(
                    broadcasts,
                    device,
                    granted -> {
                      require(!granted, "A failed permission-result revalidation must deny");
                      replies.incrementAndGet();
                    });
                flow.request();
                require(
                    CameraImportGate.isPreparationReserved(),
                    "Controller reserves transfer admission during the real permission receiver");
                broadcasts.deliver();
                broadcasts.deliver();
                require(
                    replies.get() == 1 && broadcasts.unregisters == 1,
                    "The actual receiver denies once and releases its subscription once");
              });
          test.waitForIdleSync();
          test.runOnMainSync(
              () -> {
                require(
                    !CameraImportGate.isPreparationReserved()
                        && flow.queued() == 0
                        && flow.claims() == 0,
                    "Failed permission-result delivery releases admission and never claims");
                require(
                    fault.enumerations.get() > 0 && (enumeration || fault.permissions.get() > 0),
                    "The actual permission receiver reached the failing query");
              });
        }
      }
    } finally {
      queries.set(null, platform);
    }
  }

  private static UsbDevice syntheticCamera() {
    Parcel parcel = Parcel.obtain();
    try {
      // Android 15 UsbDevice Parcelable descriptor; no corresponding device is attached.
      parcel.writeString("/dev/bus/usb/001/002");
      parcel.writeInt(0x07B4);
      parcel.writeInt(1);
      parcel.writeInt(0);
      parcel.writeInt(0);
      parcel.writeInt(0);
      parcel.writeString("Loupe instrumentation");
      parcel.writeString("Synthetic camera");
      parcel.writeString("1");
      parcel.writeStrongBinder(new Binder());
      parcel.writeParcelableArray(new UsbConfiguration[0], 0);
      for (int capability = 0; capability < 5; capability++) parcel.writeInt(0);
      parcel.setDataPosition(0);
      return UsbDevice.CREATOR.createFromParcel(parcel);
    } finally {
      parcel.recycle();
    }
  }

  private static final class UsbFault implements CameraUsb.Queries {
    final UsbDevice device;
    final boolean enumeration;
    final AtomicInteger enumerations = new AtomicInteger();
    final AtomicInteger permissions = new AtomicInteger();

    UsbFault(UsbDevice device, boolean enumeration) {
      this.device = device;
      this.enumeration = enumeration;
    }

    @Override
    public Map<String, UsbDevice> devices(Context context) {
      enumerations.incrementAndGet();
      if (enumeration) throw new IllegalStateException("synthetic device enumeration failure");
      return Map.of(device.getDeviceName(), device);
    }

    @Override
    public boolean hasPermission(Context context, UsbDevice device) {
      permissions.incrementAndGet();
      throw new SecurityException("synthetic USB permission query failure");
    }

    @Override
    public void requestPermission(Context context, UsbDevice device, PendingIntent pending) {
      require(
          context instanceof PermissionContext,
          "The Android fixture never requests real USB permission");
    }
  }

  private static final class PermissionContext extends ContextWrapper {
    BroadcastReceiver receiver;
    String action;
    int unregisters;

    PermissionContext(Context base) {
      super(base);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
      require(flags == Context.RECEIVER_NOT_EXPORTED, "USB permission receiver remains app-only");
      this.receiver = receiver;
      action = filter.getAction(0);
      return null;
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
      this.receiver = receiver;
      action = filter.getAction(0);
      return null;
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
      require(receiver == this.receiver, "Close the exact permission receiver");
      unregisters++;
    }

    void deliver() {
      receiver.onReceive(
          this, new Intent(action).putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true));
    }
  }

  private static Field field(Class<?> type, String name) throws NoSuchFieldException {
    var field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
