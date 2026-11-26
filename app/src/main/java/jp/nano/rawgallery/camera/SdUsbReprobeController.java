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
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.Closeable;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import jp.nano.rawgallery.PhotoStorage;
import jp.nano.rawgallery.camera.SdUsbReprobe.Ejection;
import jp.nano.rawgallery.camera.SdUsbReprobe.Reader;
import jp.nano.rawgallery.camera.SdUsbReprobe.Snapshot;
import jp.nano.rawgallery.camera.SdUsbReprobe.State;

/** Foreground USB permission and callbacks for the SD re-detection action. */
public final class SdUsbReprobeController {
  private static final long REMOUNT_WAIT_MS = 12_000;
  private static final long EJECTION_FLOW_MS = 2 * 60_000;
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final Object MOUNT_LOCK = new Object();
  private static Reader suppressedReader;
  private static String restoredSource;
  private static Run handoffOwner;

  private final Activity activity;
  private final AndroidUsb platform;
  private final BooleanSupplier idle;
  private final Consumer<Display> render;
  private final Runnable refresh;
  private final StorageManager storage;
  private final StorageManager.StorageVolumeCallback storageCallback;
  private final BroadcastReceiver usbReceiver;
  private boolean observing, foreground, settingsOpen;
  private Snapshot lastObserved;
  private Ejection armed;
  private Permission pending;
  private Run running;
  private State state;

  private static final class Permission {
    final Snapshot request;
    final Ejection ejection;
    Closeable registration;
    Boolean granted;

    Permission(Snapshot request, Ejection ejection) {
      this.request = request;
      this.ejection = ejection;
    }
  }

  public record Display(String message, boolean busy, boolean requiresSource) {}

  public SdUsbReprobeController(Activity activity, BooleanSupplier idle, Consumer<Display> render,
      Runnable refresh) {
    this.activity = activity;
    this.platform = new AndroidUsb(activity.getApplicationContext());
    this.idle = idle;
    this.render = render;
    this.refresh = refresh;
    this.storage = activity.getSystemService(StorageManager.class);
    this.storageCallback = new StorageManager.StorageVolumeCallback() {
      @Override public void onStateChanged(StorageVolume volume) {
        observeVolumeChange(platform, SdSourceRegistrations.normalizeVolumeUuid(volume.getUuid()));
        observed();
      }
    };
    this.usbReceiver = new BroadcastReceiver() {
      @Override public void onReceive(Context context, Intent intent) {
        observed();
      }
    };
  }

  public boolean busy() {
    return pending != null || running != null;
  }

  public boolean requiresSourceSelection() {
    return state == State.SOURCE_REQUIRED;
  }

  public void sourceSelected() {
    if (requiresSourceSelection()) show(State.READY);
  }

  public void resume() {
    foreground = true;
    lastObserved = platform.snapshot();
    if (!observing) {
      try {
        storage.registerStorageVolumeCallback(activity.getMainExecutor(), storageCallback);
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
          activity.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
          activity.registerReceiver(usbReceiver, filter);
        }
        observing = true;
      } catch (RuntimeException unavailable) {
        if (storage != null) storage.unregisterStorageVolumeCallback(storageCallback);
      }
    }
    if (settingsOpen) {
      settingsOpen = false;
      if (armed == null || !armed.matches(lastObserved, SystemClock.elapsedRealtime())) {
        armed = null;
        show(State.CHANGED);
      } else {
        show(armed.confirmed(lastObserved, SystemClock.elapsedRealtime()) ? State.CONFIRM_EJECTION : State.EJECT_REQUIRED);
      }
    }
    continuePermission();
  }

  public void pause() {
    foreground = false;
    if (running != null) running.cancelled.set(true);
  }

  public void stop() {
    pause();
    if (observing) {
      storage.unregisterStorageVolumeCallback(storageCallback);
      activity.unregisterReceiver(usbReceiver);
      observing = false;
    }
    if (!settingsOpen) armed = null;
    if (pending == null) return;
    close(pending.registration);
    pending = null;
    show(State.CANCELLED);
  }

  public void request() {
    if (!foreground) return;
    if (busy() || !idle.getAsBoolean()) {
      if (!busy()) show(State.BUSY);
      return;
    }
    Snapshot snapshot = platform.snapshot();
    if (armed != null && !armed.matches(snapshot, SystemClock.elapsedRealtime())) armed = null;
    if (armed != null && armed.confirmed(snapshot, SystemClock.elapsedRealtime())) {
      if (snapshot.permitted()) confirmEjection(armed);
      else requestPermission(snapshot, armed);
    } else if (armed != null || snapshot.canPrepareEjection()) {
      offerEjection(snapshot);
    } else {
      show(State.UNSUPPORTED);
    }
  }

  private void observed() {
    if (!foreground || settingsOpen) return;
    Snapshot current = platform.snapshot();
    if (current.equals(lastObserved)) return;
    lastObserved = current;
    if (armed != null && !armed.matches(current, SystemClock.elapsedRealtime())) {
      armed = null;
      show(State.CHANGED);
    }
    if (!busy()) refresh.run();
  }

  private void offerEjection(Snapshot snapshot) {
    show(State.EJECT_REQUIRED);
    new MaterialAlertDialogBuilder(activity)
        .setTitle("Androidでカードを取り出す")
        .setMessage("リーダーはつないだまま、Androidの設定で表示されているSDカードとmicroSDを安全に取り出してください。戻ったら再認識を押してください。")
        .setNegativeButton("キャンセル", (dialog, which) -> armed = null)
        .setOnCancelListener(dialog -> armed = null)
        .setPositiveButton("ストレージ設定を開く", (dialog, which) -> {
          if (!foreground || busy() || !idle.getAsBoolean()) return;
          Snapshot current = platform.snapshot();
          if (!snapshot.sameAdmission(current)) {
            armed = null;
            show(State.CHANGED);
            return;
          }
          if (armed == null && !current.canPrepareEjection()) {
            show(State.UNSUPPORTED);
            return;
          }
          try {
            if (armed == null) armed = new Ejection(current,
                SystemClock.elapsedRealtime() + EJECTION_FLOW_MS);
            settingsOpen = true;
            activity.startActivity(new Intent(Settings.ACTION_MEMORY_CARD_SETTINGS));
          } catch (RuntimeException unavailable) {
            settingsOpen = false;
            armed = null;
            show(State.UNSUPPORTED);
          }
        })
        .show();
  }

  private void confirmEjection(Ejection confirmation) {
    new MaterialAlertDialogBuilder(activity)
        .setTitle("カードの取り出しを確認")
        .setMessage("Androidで表示されていたすべてのカードの取り出しが完了し、リーダーをつないだままであることを確認してください。フォーマットやマウント中は実行できません。")
        .setNegativeButton("キャンセル", (dialog, which) -> armed = null)
        .setOnCancelListener(dialog -> armed = null)
        .setPositiveButton("取り出し済み", (dialog, which) -> {
          if (armed != confirmation || !foreground || busy() || !idle.getAsBoolean()) return;
          armed = null;
          Snapshot current = platform.snapshot();
          if (!confirmation.confirmed(current, SystemClock.elapsedRealtime())) {
            show(State.CHANGED);
          } else if (!current.permitted()) {
            show(State.DENIED);
          } else {
            start(current, confirmation);
          }
        })
        .show();
  }

  private void requestPermission(Snapshot snapshot, Ejection ejection) {
    UsbDevice device = platform.device(snapshot.reader());
    if (device == null) {
      show(State.CHANGED);
      return;
    }
    Permission request = new Permission(snapshot, ejection);
    pending = request;
    show(State.PERMISSION);
    try {
      request.registration = CameraUsb.request(activity, device, granted -> {
        if (pending != request) return;
        request.granted = granted;
        continuePermission();
      });
    } catch (RuntimeException unavailable) {
      pending = null;
      show(State.DENIED);
    }
  }

  private void continuePermission() {
    if (!foreground || pending == null || pending.granted == null) return;
    Permission request = pending;
    pending = null;
    close(request.registration);
    if (!request.granted) {
      show(State.DENIED);
      return;
    }
    Snapshot current = platform.snapshot();
    if (armed != request.ejection || !armed.confirmed(current, SystemClock.elapsedRealtime())
        || !request.request.sameAdmission(current)) {
      armed = null;
      show(State.CHANGED);
    } else if (!current.permitted()) {
      show(State.DENIED);
    } else if (!idle.getAsBoolean()) {
      show(State.BUSY);
    } else {
      show(State.CONFIRM_EJECTION);
      confirmEjection(armed);
    }
  }

  private static void close(Closeable registration) {
    if (registration == null) return;
    try {
      registration.close();
    } catch (IOException | RuntimeException ignored) {
      // A permission registration does not authorize USB access by itself.
    }
  }

  private void start(Snapshot snapshot, Ejection confirmation) {
    Run attempt = new Run(platform, new WeakReference<>(this), snapshot, confirmation);
    running = attempt;
    show(State.REDETECTING);
    new Thread(attempt, "sd-usb-reprobe").start();
  }

  private void show(State next) {
    state = next;
    String message = next == null ? "" : switch (next) {
      case UNSUPPORTED -> "再認識できません。Androidで安全に取り出してから、リーダーを挿し直してください。";
      case EJECT_REQUIRED -> "Androidでカードの取り出しが完了していることを確認してください。";
      case CONFIRM_EJECTION -> "取り出しが済んだら、もう一度「SDカードを再認識」を押してください。";
      case PERMISSION -> "USBの使用許可を確認しています。";
      case REDETECTING -> "SDカードを再認識しています…";
      case WAITING -> "Androidがカードを認識するのを待っています…";
      case SOURCE_REQUIRED -> "カードを認識しました。取り込み元のフォルダを選んでください。";
      case READY -> "元カードと保存先を確認しました。SDカードから取り込めます。";
      case BUSY -> "処理中は再認識できません。完了してからもう一度お試しください。";
      case CHANGED -> "カードの接続または保存先が変わりました。接続を確認してください。";
      case DENIED -> "USBの使用許可を確認できません。もう一度お試しください。";
      case TIMEOUT, FAILED -> "カードを再認識できませんでした。Androidで安全に取り出してから、リーダーを挿し直してください。";
      case CANCELLED -> "SDカードの再認識を中止しました。";
    };
    render.accept(new Display(message, busy(), next == State.SOURCE_REQUIRED));
  }

  private static final class Run implements Runnable {
    final AndroidUsb platform;
    final WeakReference<SdUsbReprobeController> owner;
    final Snapshot snapshot;
    final Ejection confirmation;
    final AtomicBoolean cancelled = new AtomicBoolean();

    Run(AndroidUsb platform, WeakReference<SdUsbReprobeController> owner, Snapshot snapshot,
        Ejection confirmation) {
      this.platform = platform;
      this.owner = owner;
      this.snapshot = snapshot;
      this.confirmation = confirmation;
    }

    @Override public void run() {
      State result = State.FAILED;
      var observer = new SdUsbReprobe.Observer() {
        @Override public void beforeClaim() {
          synchronized (MOUNT_LOCK) {
            handoffOwner = Run.this;
            suppressedReader = snapshot.reader();
            restoredSource = null;
          }
        }

        @Override public void waiting() {
          MAIN.post(() -> {
            var controller = owner.get();
            if (controller != null && controller.foreground && controller.running == Run.this) {
              controller.show(State.WAITING);
            }
          });
        }
      };
      try {
        result = SdUsbReprobe.run(confirmation, snapshot, platform, CameraImportGate.lock,
            cancelled::get, SystemClock::elapsedRealtime, REMOUNT_WAIT_MS, observer);
      } catch (InterruptedException stopped) {
        Thread.currentThread().interrupt();
        result = State.CANCELLED;
      } finally {
        synchronized (MOUNT_LOCK) {
          if (handoffOwner == this) {
            handoffOwner = null;
            if (result == State.READY || result == State.SOURCE_REQUIRED) {
              var current = platform.snapshot();
              if (snapshot.sameConnection(current)) restoredSource = SdUsbReprobe.readableSource(current);
            }
          }
        }
      }
      State terminal = result;
      MAIN.post(() -> {
        var controller = owner.get();
        if (controller == null || controller.running != this) return;
        controller.running = null;
        controller.show(cancelled.get() ? State.CANCELLED : terminal);
        if (controller.foreground) controller.refresh.run();
      });
    }
  }

  /** A delayed event from this attachment cannot start a copy after the handoff has finished. */
  static boolean suppressesMount(Context context) {
    synchronized (MOUNT_LOCK) {
      if (handoffOwner != null) return true;
      if (suppressedReader == null) return false;
      try {
        UsbManager manager = context.getSystemService(UsbManager.class);
        UsbDevice device = manager.getDeviceList().get(suppressedReader.name());
        if (device != null && device.getDeviceId() == suppressedReader.deviceId()
            && device.getVendorId() == suppressedReader.vendor()
            && device.getProductId() == suppressedReader.product()) return true;
        suppressedReader = null;
        restoredSource = null;
        return false;
      } catch (RuntimeException unavailable) {
        return true;
      }
    }
  }

  static void observeUnmount(Context context, String uuid) {
    observeVolumeChange(new AndroidUsb(context.getApplicationContext()), uuid);
  }

  private static void observeVolumeChange(AndroidUsb platform, String uuid) {
    synchronized (MOUNT_LOCK) {
      if (handoffOwner != null || restoredSource == null || !restoredSource.equals(uuid)) return;
      Snapshot current = platform.snapshot();
      if (current.reader() == null || !current.reader().equals(suppressedReader)) return;
      if (platform.destinationWritable(current.destination())
          && current.volumes().stream().anyMatch(volume ->
              uuid.equals(volume.uuid()) && Environment.MEDIA_UNMOUNTED.equals(volume.state()))) {
        suppressedReader = null;
        restoredSource = null;
      }
    }
  }

  private static final class AndroidUsb implements SdUsbReprobe.Platform {
    final Context app;
    final UsbManager usb;

    AndroidUsb(Context app) {
      this.app = app;
      this.usb = app.getSystemService(UsbManager.class);
    }

    @Override public Snapshot snapshot() {
      try {
        String destination = SdSourceRegistrations.normalizeVolumeUuid(PhotoStorage.selected(app));
        UserManager users = app.getSystemService(UserManager.class);
        if (destination == null || users == null || !users.isUserUnlocked()) return unavailable();
        var devices = usb.getDeviceList();
        if (devices.size() != 1) return unavailable();
        UsbDevice device = devices.values().iterator().next();
        Reader reader = reader(device);
        if (reader == null) return unavailable();
        StorageManager storage = app.getSystemService(StorageManager.class);
        List<SdUsbReprobe.Volume> volumes = new ArrayList<>();
        for (StorageVolume volume : storage.getStorageVolumes()) {
          if (volume.isEmulated()) continue;
          String uuid = volume.isPrimary() || !volume.isRemovable() ? null
              : SdSourceRegistrations.normalizeVolumeUuid(volume.getUuid());
          volumes.add(new SdUsbReprobe.Volume(uuid, volume.getState()));
        }
        return new Snapshot(reader, destination, volumes, usb.hasPermission(device));
      } catch (RuntimeException unavailable) {
        return unavailable();
      }
    }

    private Snapshot unavailable() {
      return new Snapshot(null, null, List.of(), false);
    }

    private static Reader reader(UsbDevice device) {
      if (device.getVendorId() != SdUsbReprobe.VENDOR || device.getProductId() != SdUsbReprobe.PRODUCT
          || device.getConfigurationCount() != 1 || device.getInterfaceCount() != 1) return null;
      UsbInterface endpoint = device.getInterface(0);
      if (endpoint.getInterfaceClass() != UsbConstants.USB_CLASS_MASS_STORAGE
          || endpoint.getInterfaceSubclass() != 6 || endpoint.getInterfaceProtocol() != 80
          || endpoint.getAlternateSetting() != 0) return null;
      return new Reader(device.getDeviceName(), device.getDeviceId(), device.getVendorId(),
          device.getProductId(), endpoint.getId());
    }

    UsbDevice device(Reader expected) {
      if (expected == null) return null;
      var devices = usb.getDeviceList();
      if (devices.size() != 1) return null;
      UsbDevice current = devices.get(expected.name());
      return current != null && expected.equals(reader(current)) ? current : null;
    }

    @Override public SdUsbReprobe.Session open(Reader expected) {
      UsbDevice device = device(expected);
      if (device == null || !usb.hasPermission(device)) return null;
      UsbInterface endpoint = device.getInterface(0);
      UsbDeviceConnection connection = usb.openDevice(device);
      if (connection == null) return null;
      return new SdUsbReprobe.Session() {
        @Override public boolean claim() { return connection.claimInterface(endpoint, true); }
        @Override public boolean release() { return connection.releaseInterface(endpoint); }
        @Override public void close() { connection.close(); }
      };
    }

    @Override public boolean destinationWritable(String expected) {
      String selected = PhotoStorage.selected(app);
      if (expected == null
          || !Objects.equals(expected, SdSourceRegistrations.normalizeVolumeUuid(selected))) return false;
      try {
        PhotoStorage.requireWritable(app, selected);
        return true;
      } catch (PhotoStorage.Unavailable | RuntimeException unavailable) {
        return false;
      }
    }

    @Override public boolean sourceReachable(String source, String destination, BooleanSupplier stopped) {
      var tree = SdSourceRegistrations.forVolume(app, source);
      if (tree == null || !source.equals(SdSourceRegistrations.normalizeVolumeUuid(
          SdSourceRegistrations.mountedVolume(app, tree))) || !SdSourceRegistrations.hasReadGrant(app, tree)) {
        return false;
      }
      try {
        return CameraDocumentSource.isReachableDirectory(app.getContentResolver(), tree, stopped)
            && destinationWritable(destination);
      } catch (IOException | RuntimeException unavailable) {
        return false;
      }
    }
  }
}
