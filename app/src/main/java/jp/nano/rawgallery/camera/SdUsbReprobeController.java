package jp.nano.rawgallery.camera;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import java.io.Closeable;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import jp.nano.rawgallery.PhotoStorage;
import jp.nano.rawgallery.camera.SdUsbReprobe.Reader;
import jp.nano.rawgallery.camera.SdUsbReprobe.Snapshot;
import jp.nano.rawgallery.camera.SdUsbReprobe.State;

/** Coordinates a single foreground refresh with USB permission, transfer exclusion and cleanup. */
public final class SdUsbReprobeController {
  private static final String LOG = "LoupeSdReprobe";
  private static final long REMOUNT_WAIT_MS = 12_000;
  private static final long PERMISSION_WAIT_MS = 60_000;
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final AtomicReference<OwnMounts> ownMounts = new AtomicReference<>();

  private record OwnMounts(AndroidUsb platform, SdUsbReprobe.MountCycle cycle) {}

  static boolean suppressAutomaticMount(String uuid, String target) {
    var mounts = ownMounts.get();
    if (mounts == null) return false;
    if (!mounts.cycle().matchesDestination(SdSourceRegistrations.normalizeVolumeUuid(target))) {
      ownMounts.compareAndSet(mounts, null);
      return false;
    }
    var current = mounts.platform().snapshot();
    if (mounts.cycle().expired(current, false, null)) {
      ownMounts.compareAndSet(mounts, null);
      return false;
    }
    return mounts.cycle().suppresses(uuid, current);
  }

  static void observeMountCycle(MountedCameraCard.MountChange change) {
    var mounts = ownMounts.get();
    if (mounts == null) return;
    if (mounts
        .cycle()
        .expired(mounts.platform().snapshot(), change.detached(), change.volumeUuid()))
      ownMounts.compareAndSet(mounts, null);
  }

  private final Activity activity;
  private final Platform platform;
  private final BooleanSupplier idle;
  private final Consumer<Display> render;
  private final Runnable discardSourceStatus;
  private final LongSupplier now;
  private final Executor worker;
  private final BroadcastReceiver usbReceiver;
  private final Runnable expirePermission = () -> cancelPending(State.DENIED);
  private volatile boolean foreground;
  private boolean destroyed;
  private Runnable removeMediaObserver;
  private Runnable removeSelectionObserver;
  private Snapshot positive;
  private Consumer<Restoration> onRestored;
  private Request pending;
  private Run running;

  interface Platform extends SdUsbReprobe.Platform {
    Closeable requestPermission(Activity activity, Reader reader, Consumer<Boolean> callback)
        throws IOException;
  }

  private static final class Request {
    final Snapshot snapshot;
    final CameraImportGate.Reservation reservation;
    final long mediaGeneration = MountedCameraCard.mediaGeneration();
    Closeable registration;
    Boolean granted;

    Request(Snapshot snapshot, CameraImportGate.Reservation reservation) {
      this.snapshot = snapshot;
      this.reservation = reservation;
    }
  }

  public record Display(String message, boolean busy) {}

  public record Restoration(String destination, boolean sourceRequired) {}

  public SdUsbReprobeController(
      Activity activity,
      BooleanSupplier idle,
      Consumer<Display> render,
      Runnable discardSourceStatus) {
    this(
        activity,
        new AndroidUsb(activity.getApplicationContext()),
        idle,
        render,
        discardSourceStatus,
        SystemClock::elapsedRealtime,
        work -> new Thread(work, "sd-usb-reprobe").start());
  }

  SdUsbReprobeController(
      Activity activity,
      Platform platform,
      BooleanSupplier idle,
      Consumer<Display> render,
      Runnable discardSourceStatus,
      LongSupplier now,
      Executor worker) {
    this.activity = activity;
    this.platform = platform;
    this.idle = idle;
    this.render = render;
    this.discardSourceStatus = discardSourceStatus;
    this.now = now;
    this.worker = worker;
    usbReceiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context ignored, Intent event) {
            connectionChanged();
          }
        };
  }

  public boolean busy() {
    return pending != null || running != null;
  }

  public void resume() {
    if (destroyed) return;
    foreground = true;
    if (removeMediaObserver == null) {
      try {
        var filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
          activity.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
          activity.registerReceiver(usbReceiver, filter);
        }
        try {
          removeSelectionObserver =
              PhotoStorage.observeSelection(activity, this::connectionChanged);
          removeMediaObserver = MountedCameraCard.observeMounts(activity, this::mediaChanged);
        } finally {
          if (removeMediaObserver == null) {
            if (removeSelectionObserver != null) removeSelectionObserver.run();
            activity.unregisterReceiver(usbReceiver);
          }
        }
      } catch (RuntimeException unavailable) {
        // Never claim without the connection and inventory observers.
      }
    }
    continuePermission();
  }

  public void pause() {
    foreground = false;
    if (running != null) running.authorization.cancel();
  }

  public void stop() {
    pause();
    cancelPending(State.CANCELLED);
    if (removeMediaObserver == null) return;
    removeMediaObserver.run();
    removeSelectionObserver.run();
    removeMediaObserver = null;
    removeSelectionObserver = null;
    activity.unregisterReceiver(usbReceiver);
    if (positive != null) show(null);
  }

  public void destroy() {
    destroyed = true;
    stop();
  }

  /** Returns false when the normal import route should handle this entry. */
  public boolean request(boolean explicitRetry, Consumer<Restoration> restored) {
    if (!foreground || destroyed) return true;
    Snapshot snapshot = platform.snapshot();
    State admission = snapshot.admission();
    if (admission == State.UNSUPPORTED) {
      if (explicitRetry && !busy()) show(State.UNSUPPORTED);
      return explicitRetry;
    }
    if (busy() || !idle.getAsBoolean()) {
      if (!busy()) show(State.BUSY);
      return true;
    }
    if (admission == State.UNAVAILABLE || removeMediaObserver == null) {
      discardSourceStatus.run();
      show(removeMediaObserver == null ? State.OBSERVER_UNAVAILABLE : State.UNAVAILABLE);
      return true;
    }
    var reservation = CameraImportGate.tryReservePreparation();
    if (reservation == null) {
      show(State.BUSY);
      return true;
    }
    Request request = new Request(snapshot, reservation);
    pending = request;
    onRestored = restored;
    discardSourceStatus.run();
    if (admission == State.PERMISSION) {
      show(State.PERMISSION);
      MAIN.postDelayed(expirePermission, PERMISSION_WAIT_MS);
      try {
        request.registration =
            platform.requestPermission(
                activity,
                snapshot.reader(),
                granted ->
                    MAIN.post(
                        () -> {
                          if (pending != request) return;
                          request.granted = granted;
                          continuePermission();
                        }));
      } catch (IOException | RuntimeException unavailable) {
        cancelPending(State.DENIED);
      }
    } else {
      request.granted = true;
      continuePermission();
    }
    return true;
  }

  private void connectionChanged() {
    observeMountCycle(new MountedCameraCard.MountChange(null, false, null));
    cancelPending(State.CHANGED);
    if (running != null) {
      running.invalidateBeforeClaim();
      running.connectionChanged = true;
    }
    if (positive != null) show(null);
  }

  void mediaChanged(MountedCameraCard.MountChange change) {
    cancelPending(State.CHANGED);
    if (running != null) {
      running.invalidateBeforeClaim();
      if (change.detached()) running.observeSourceDetach(change.volumeUuid());
    }
    if (positive != null && (change.detached() || !positive.sameAdmission(platform.snapshot()))) {
      show(null);
    }
  }

  private void continuePermission() {
    if (!foreground || pending == null || pending.granted == null) return;
    Request request = pending;
    Snapshot current = platform.snapshot();
    if (!request.snapshot.sameAdmission(current)
        || request.mediaGeneration != MountedCameraCard.mediaGeneration()) {
      cancelPending(State.CHANGED);
    } else if (!request.granted || !current.permitted()) {
      cancelPending(State.DENIED);
    } else if (!idle.getAsBoolean()) {
      cancelPending(State.BUSY);
    } else {
      endPermission();
      var reservation = request.reservation.handoffToWorker();
      if (reservation == null) {
        onRestored = null;
        show(State.BUSY);
        return;
      }
      Run attempt =
          new Run(
              platform,
              new WeakReference<>(this),
              current,
              request.mediaGeneration,
              reservation,
              now);
      running = attempt;
      show(State.REDETECTING);
      try {
        worker.execute(attempt);
      } catch (RuntimeException failure) {
        reservation.close();
        running = null;
        onRestored = null;
        show(State.FAILED);
      }
    }
  }

  private void cancelPending(State outcome) {
    if (pending == null) return;
    Request request = pending;
    endPermission();
    request.reservation.close();
    onRestored = null;
    show(outcome);
  }

  private void endPermission() {
    var registration = pending.registration;
    pending = null;
    MAIN.removeCallbacks(expirePermission);
    if (registration == null) return;
    try {
      registration.close();
    } catch (IOException | RuntimeException ignored) {
      // A permission registration does not authorize USB access by itself.
    }
  }

  private void show(State next) {
    if (next != State.READY && next != State.SOURCE_REQUIRED) positive = null;
    if (destroyed) return;
    String message =
        next == null
            ? ""
            : switch (next) {
              case UNSUPPORTED -> "Androidでカードを安全に取り出してから、リーダーを挿し直してください。";
              case UNAVAILABLE -> "カードの状態を確認できませんでした。「SDカードを再認識」でやり直してください。";
              case OBSERVER_UNAVAILABLE -> "SDカードを確認できませんでした。取り込み画面を開き直してください。";
              case PERMISSION -> "USBの使用許可を確認しています。";
              case REDETECTING -> "SDカードを再認識しています…";
              case WAITING -> "Androidがカードを認識するのを待っています…";
              case SOURCE_REQUIRED -> "取り込み元のフォルダを選んでください。";
              case READY -> "取り込み元と保存先を確認しました。";
              case BUSY -> "処理が終わってから、もう一度お試しください。";
              case CHANGED -> "カードの接続または保存先が変わりました。接続を確認してください。";
              case DENIED -> "USBの使用許可を確認できません。もう一度お試しください。";
              case TIMEOUT, FAILED -> "カードを再認識できませんでした。Androidで安全に取り出してから、リーダーを挿し直してください。";
              case CANCELLED -> "SDカードの再認識を中止しました。";
            };
    render.accept(new Display(message, busy()));
  }

  private static final class Run implements Runnable {
    private final Platform platform;
    private final WeakReference<SdUsbReprobeController> owner;
    private final Snapshot admitted;
    private final long mediaGeneration;
    private final CameraImportGate.Reservation reservation;
    private final LongSupplier now;
    private final SdUsbReprobe.ClaimAuthorization authorization =
        new SdUsbReprobe.ClaimAuthorization();
    private final String previousSource;
    private volatile boolean claimStarted, connectionChanged;
    private volatile Snapshot snapshotOnSourceDetach;
    private SdUsbReprobe.MountCycle mountCycle;
    private Snapshot restored;
    private long restoredGeneration;

    void observeSourceDetach(String uuid) {
      if (!claimStarted
          || previousSource == null
          || (uuid != null && !previousSource.equals(uuid))
          || snapshotOnSourceDetach != null) return;
      Snapshot current = platform.snapshot();
      if (admitted.sameConnection(current) && current.sourceUnavailable(previousSource)) {
        snapshotOnSourceDetach = current;
      }
    }

    void invalidateBeforeClaim() {
      authorization.invalidateBeforeClaim();
    }

    Run(
        Platform platform,
        WeakReference<SdUsbReprobeController> owner,
        Snapshot admitted,
        long mediaGeneration,
        CameraImportGate.Reservation reservation,
        LongSupplier now) {
      this.platform = platform;
      this.owner = owner;
      this.admitted = admitted;
      this.previousSource = SdUsbReprobe.readableSource(admitted);
      this.mediaGeneration = mediaGeneration;
      this.reservation = reservation;
      this.now = now;
    }

    @Override
    public void run() {
      State result;
      try {
        result =
            SdUsbReprobe.run(
                admitted,
                platform,
                CameraImportGate.lock,
                () -> authorization.cancelled() || connectionChanged || !reservation.isOpen(),
                now,
                REMOUNT_WAIT_MS,
                new SdUsbReprobe.Observer() {
                  @Override
                  public boolean startingClaim() {
                    var controller = owner.get();
                    if (!authorization.tryStart(
                        () ->
                            controller != null
                                && controller.foreground
                                && reservation.isOpen()
                                && mediaGeneration == MountedCameraCard.mediaGeneration())) {
                      return false;
                    }
                    if (platform instanceof AndroidUsb android) {
                      mountCycle = new SdUsbReprobe.MountCycle(admitted);
                      ownMounts.set(new OwnMounts(android, mountCycle));
                    }
                    claimStarted = true;
                    return true;
                  }

                  @Override
                  public void waiting() {
                    MAIN.post(
                        () -> {
                          var controller = owner.get();
                          if (controller != null
                              && controller.foreground
                              && controller.running == Run.this) controller.show(State.WAITING);
                        });
                  }

                  @Override
                  public Snapshot snapshotOnSourceDetach() {
                    return snapshotOnSourceDetach;
                  }

                  @Override
                  public long detachGeneration() {
                    return MountedCameraCard.detachGeneration();
                  }

                  @Override
                  public void restored(Snapshot current, long generation) {
                    restored = current;
                    restoredGeneration = generation;
                  }
                });
      } catch (InterruptedException stopped) {
        Thread.currentThread().interrupt();
        result = State.CANCELLED;
      } finally {
        if (mountCycle != null) mountCycle.finish(restored);
        reservation.close();
      }
      State terminal = result;
      android.util.Log.i(LOG, "outcome=" + terminal.name());
      MAIN.post(() -> finish(terminal));
    }

    private void finish(State terminal) {
      var controller = owner.get();
      if (controller == null || controller.destroyed || controller.running != this) return;
      controller.running = null;
      Consumer<Restoration> onRestored = controller.onRestored;
      controller.onRestored = null;
      if (connectionChanged) {
        controller.show(State.CHANGED);
      } else if (!controller.foreground || authorization.cancelled()) {
        controller.show(State.CANCELLED);
      } else if (restored != null) {
        Snapshot current = platform.snapshot();
        if (restored.sameAdmission(current)
            && current.permitted()
            && restoredGeneration == MountedCameraCard.detachGeneration()) {
          controller.positive = restored;
          controller.show(terminal);
          onRestored.accept(
              new Restoration(current.destination(), terminal == State.SOURCE_REQUIRED));
        } else {
          controller.show(State.CHANGED);
        }
      } else {
        controller.show(terminal);
      }
    }
  }

  private static SdUsbReprobe.VisibleVolume visible(StorageVolume volume) {
    String uuid =
        volume.isPrimary()
            ? volume.getUuid()
            : SdSourceRegistrations.normalizeVolumeUuid(volume.getUuid());
    return new SdUsbReprobe.VisibleVolume(
        uuid, volume.getState(), volume.isPrimary(), volume.isEmulated(), volume.isRemovable());
  }

  private static final class AndroidUsb implements Platform {
    final Context app;
    final UsbManager usb;

    AndroidUsb(Context app) {
      this.app = app;
      this.usb = app.getSystemService(UsbManager.class);
    }

    @Override
    public Snapshot snapshot() {
      return Snapshot.observe(this::observeDestination, Snapshot.unobserved(null));
    }

    private Snapshot observeDestination() {
      if (!SdUsbReprobe.supportedPhone(Build.DEVICE, Build.VERSION.SDK_INT, Build.FINGERPRINT))
        return new Snapshot(null, null, List.of(), false);
      String destination = SdSourceRegistrations.normalizeVolumeUuid(PhotoStorage.selected(app));
      if (destination == null) return new Snapshot(null, null, List.of(), false);
      return Snapshot.observe(() -> observeReader(destination), Snapshot.unobserved(destination));
    }

    private Snapshot observeReader(String destination) {
      UserManager users = app.getSystemService(UserManager.class);
      if (users == null || !users.isUserUnlocked()) return Snapshot.unobserved(destination);
      var devices = usb.getDeviceList();
      if (devices.size() != 1) return new Snapshot(null, destination, List.of(), false);
      UsbDevice device = devices.values().iterator().next();
      Reader reader = reader(device);
      if (reader == null) return new Snapshot(null, destination, List.of(), false);
      Snapshot connection = Snapshot.connectionOnly(reader, destination);
      return Snapshot.observe(
          () -> {
            var volumes = currentVolumes();
            if (volumes == null) return new Snapshot(reader, destination, List.of(), false);
            return new Snapshot(reader, destination, volumes, usb.hasPermission(device));
          },
          connection);
    }

    private List<SdUsbReprobe.Volume> currentVolumes() {
      StorageManager storage = app.getSystemService(StorageManager.class);
      var visible = new ArrayList<SdUsbReprobe.VisibleVolume>();
      for (StorageVolume volume : storage.getStorageVolumes()) visible.add(visible(volume));
      return SdUsbReprobe.portableLayout(visible);
    }

    private static Reader reader(UsbDevice device) {
      if (device.getVendorId() != SdUsbReprobe.VENDOR
          || device.getProductId() != SdUsbReprobe.PRODUCT
          || device.getConfigurationCount() != 1
          || device.getInterfaceCount() != 1) return null;
      UsbInterface endpoint = device.getInterface(0);
      if (endpoint.getInterfaceClass() != UsbConstants.USB_CLASS_MASS_STORAGE
          || endpoint.getInterfaceSubclass() != 6
          || endpoint.getInterfaceProtocol() != 80
          || endpoint.getAlternateSetting() != 0) return null;
      return new Reader(
          device.getDeviceName(),
          device.getDeviceId(),
          device.getVendorId(),
          device.getProductId(),
          endpoint.getId());
    }

    @Override
    public Closeable requestPermission(
        Activity activity, Reader expected, Consumer<Boolean> callback) throws IOException {
      UsbDevice device = device(expected);
      if (device == null) throw new IOException("USB device changed");
      return CameraUsb.request(activity, device, callback);
    }

    UsbDevice device(Reader expected) {
      if (expected == null) return null;
      var devices = usb.getDeviceList();
      if (devices.size() != 1) return null;
      UsbDevice current = devices.get(expected.name());
      return current != null && expected.equals(reader(current)) ? current : null;
    }

    @Override
    public SdUsbReprobe.Session open(Reader expected) {
      UsbDevice device = device(expected);
      if (device == null || !usb.hasPermission(device)) return null;
      UsbInterface endpoint = device.getInterface(0);
      UsbDeviceConnection connection = usb.openDevice(device);
      if (connection == null) return null;
      return new SdUsbReprobe.Session() {
        @Override
        public boolean claim() {
          boolean claimed = connection.claimInterface(endpoint, true);
          android.util.Log.i(LOG, "claim=" + claimed);
          return claimed;
        }

        @Override
        public boolean release() {
          boolean released = connection.releaseInterface(endpoint);
          android.util.Log.i(LOG, "release=" + released);
          return released;
        }

        @Override
        public void close() {
          connection.close();
          android.util.Log.i(LOG, "close=true");
        }
      };
    }

    @Override
    public boolean destinationWritable(String expected) {
      try {
        String selected = PhotoStorage.selected(app);
        if (expected == null
            || !Objects.equals(expected, SdSourceRegistrations.normalizeVolumeUuid(selected)))
          return false;
        PhotoStorage.requireWritable(app, selected);
        return true;
      } catch (PhotoStorage.Unavailable | RuntimeException unavailable) {
        return false;
      }
    }

    @Override
    public boolean sourceReachable(String source, String destination, BooleanSupplier stopped) {
      try {
        long generation = MountedCameraCard.detachGeneration();
        String selected = PhotoStorage.selected(app);
        if (!destination.equals(SdSourceRegistrations.normalizeVolumeUuid(selected))) return false;
        var tree = SdSourceRegistrations.forVolume(app, source);
        if (tree == null || !SdSourceRegistrations.isUnambiguousSource(app, tree, selected))
          return false;
        String mounted =
            MountedCameraCard.requireDistinctSource(
                app, tree, selected, PhotoStorage.selected(app), true, stopped);
        MountedCameraCard.requireGeneration(generation);
        return source.equals(SdSourceRegistrations.normalizeVolumeUuid(mounted))
            && destinationWritable(destination)
            && SdSourceRegistrations.isUnambiguousSource(app, tree, selected)
            && SdSourceRegistrations.hasReadGrant(app, tree);
      } catch (IOException | RuntimeException unavailable) {
        return false;
      }
    }
  }
}
