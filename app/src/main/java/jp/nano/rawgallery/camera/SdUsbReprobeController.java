package jp.nano.rawgallery.camera;

import android.app.Activity;
import android.content.ActivityNotFoundException;
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
import android.provider.Settings;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.Closeable;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import jp.nano.rawgallery.PhotoStorage;
import jp.nano.rawgallery.camera.SdUsbReprobe.Ejection;
import jp.nano.rawgallery.camera.SdUsbReprobe.Reader;
import jp.nano.rawgallery.camera.SdUsbReprobe.Snapshot;
import jp.nano.rawgallery.camera.SdUsbReprobe.State;

/** Foreground USB permission and settings confirmation for an exclusive SD re-detection. */
public final class SdUsbReprobeController {
  private static final String LOG = "LoupeSdReprobe";
  private static final long REMOUNT_WAIT_MS = 12_000;
  private static final long EJECTION_FLOW_MS = 2 * 60_000;
  private static final Handler MAIN = new Handler(Looper.getMainLooper());

  private final Activity activity;
  private final Platform platform;
  private final Dialogs dialogs;
  private final BooleanSupplier idle;
  private final Consumer<Display> render;
  private final Runnable refresh;
  private final LongSupplier now;
  private final Executor worker;
  private final StorageManager storage;
  private final StorageManager.StorageVolumeCallback storageCallback;
  private final BroadcastReceiver usbReceiver;
  private final Runnable expire = this::expirePreparation;
  private boolean observing, foreground, settingsOpen, destroyed;
  private Runnable removeMediaObserver;
  private Snapshot lastObserved, positive;
  private Ejection armed;
  private CameraImportGate.Reservation preparing;
  private Permission pending;
  private Run running;

  interface Platform extends SdUsbReprobe.Platform {
    Closeable requestPermission(Activity activity, Reader reader, Consumer<Boolean> callback)
        throws IOException;
  }

  interface Dialogs {
    void prepare(Runnable open, Runnable cancel);

    void confirm(Runnable confirm, Runnable cancel);

    void openSettings();
  }

  private static final class Permission {
    final Snapshot request;
    Closeable registration;
    Boolean granted;

    Permission(Snapshot request) {
      this.request = request;
    }
  }

  public record Display(String message, boolean busy) {}

  public SdUsbReprobeController(
      Activity activity, BooleanSupplier idle, Consumer<Display> render, Runnable refresh) {
    this(
        activity,
        new AndroidUsb(activity.getApplicationContext()),
        new AndroidDialogs(activity),
        idle,
        render,
        refresh,
        SystemClock::elapsedRealtime,
        work -> new Thread(work, "sd-usb-reprobe").start());
  }

  SdUsbReprobeController(
      Activity activity,
      Platform platform,
      Dialogs dialogs,
      BooleanSupplier idle,
      Consumer<Display> render,
      Runnable refresh,
      LongSupplier now,
      Executor worker) {
    this.activity = activity;
    this.platform = platform;
    this.dialogs = dialogs;
    this.idle = idle;
    this.render = render;
    this.refresh = refresh;
    this.now = now;
    this.worker = worker;
    this.storage = activity.getSystemService(StorageManager.class);
    this.storageCallback =
        new StorageManager.StorageVolumeCallback() {
          @Override
          public void onStateChanged(StorageVolume volume) {
            storageChanged(visible(volume));
          }
        };
    this.usbReceiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context ignored, Intent event) {
            if (armed != null) invalidateEjection();
            if (running != null) running.invalidateBeforeClaim();
            if (positive != null) show(null);
            observed();
          }
        };
  }

  public boolean busy() {
    return pending != null || running != null;
  }

  public boolean preparing() {
    return preparing != null;
  }

  public void resume() {
    if (destroyed) return;
    foreground = true;
    lastObserved = platform.snapshot();
    if (positive != null) show(null);
    if (!observing) {
      boolean storageRegistered = false;
      try {
        removeMediaObserver = MountedCameraCard.observeMounts(activity, this::mediaChanged);
        storage.registerStorageVolumeCallback(activity.getMainExecutor(), storageCallback);
        storageRegistered = true;
        var filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
          activity.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
          activity.registerReceiver(usbReceiver, filter);
        }
        observing = true;
      } catch (RuntimeException unavailable) {
        if (storageRegistered) storage.unregisterStorageVolumeCallback(storageCallback);
        stopObserving();
      }
    }
    if (settingsOpen) {
      settingsOpen = false;
      if (armed == null || !armed.matches(lastObserved, now.getAsLong())) {
        releasePreparation();
        show(State.CHANGED);
      } else if (!armed.confirmed(lastObserved, now.getAsLong())) {
        releasePreparation();
        show(State.EJECT_REQUIRED);
      } else if (!lastObserved.permitted()) {
        releasePreparation();
        show(State.DENIED);
      } else {
        show(State.CONFIRM_EJECTION);
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
    // Keep observing while Android Settings owns the foreground. A transient detach
    // must invalidate this proof even if the same inventory returns before onResume.
    if (!settingsOpen) {
      stopObserving();
      releasePreparation();
    }
    if (pending == null) return;
    close(pending.registration);
    pending = null;
    show(State.CANCELLED);
  }

  public void destroy() {
    destroyed = true;
    settingsOpen = false;
    stop();
  }

  private void stopObserving() {
    if (removeMediaObserver != null) {
      removeMediaObserver.run();
      removeMediaObserver = null;
    }
    if (!observing) return;
    storage.unregisterStorageVolumeCallback(storageCallback);
    activity.unregisterReceiver(usbReceiver);
    observing = false;
  }

  public void request() {
    if (!foreground || settingsOpen) return;
    if (busy() || !idle.getAsBoolean()) {
      if (!busy()) show(State.BUSY);
      return;
    }
    Snapshot snapshot = platform.snapshot();
    if (preparing != null) {
      if (armed == null || !armed.confirmed(snapshot, now.getAsLong())) {
        releasePreparation();
        show(State.CHANGED);
      } else if (!snapshot.permitted()) {
        releasePreparation();
        show(State.DENIED);
      } else {
        confirmEjection(armed);
      }
    } else if (snapshot.canPrepareEjection()) {
      if (snapshot.permitted()) offerEjection(snapshot);
      else requestPermission(snapshot);
    } else {
      show(State.UNSUPPORTED);
    }
  }

  private void mediaChanged(MountedCameraCard.MountChange change) {
    mediaStateChanged(change.volumeUuid(), change.state());
    observed();
  }

  void storageChanged(SdUsbReprobe.VisibleVolume event) {
    if (!event.emulated()) mediaStateChanged(event.uuid(), event.state());
    observed();
  }

  private void mediaStateChanged(String uuid, String state) {
    // A completed ejection cannot authorize a later mount cycle with the same UUID.
    // Both Android notification channels must agree on which transitions are expected.
    if (armed != null && !armed.beforeSettings().expectsEjectionEvent(uuid, state)) {
      invalidateEjection();
    }
    if (running != null && !running.snapshot.expectsEjectionEvent(uuid, state)) {
      running.invalidateBeforeClaim();
    }
    if (positive != null
        && (uuid == null
            || positive.volumes().stream()
                .noneMatch(v -> uuid.equals(v.uuid()) && Objects.equals(v.state(), state)))) {
      show(null);
    }
  }

  void observed() {
    if (destroyed) return;
    Snapshot current = platform.snapshot();
    if (armed != null && !armed.matches(current, now.getAsLong())) invalidateEjection();
    if (running != null && (!running.snapshot.sameAdmission(current) || !current.permitted())) {
      running.invalidateBeforeClaim();
    }
    if (positive != null && (!positive.sameAdmission(current) || !current.permitted())) show(null);
    if (current.equals(lastObserved)) return;
    lastObserved = current;
    if (foreground && !busy()) refresh.run();
  }

  private void offerEjection(Snapshot snapshot) {
    show(State.EJECT_REQUIRED);
    dialogs.prepare(() -> openSettings(snapshot), () -> show(null));
  }

  private void openSettings(Snapshot before) {
    if (!foreground || busy() || preparing != null || !idle.getAsBoolean()) return;
    if (!observing) {
      show(State.UNSUPPORTED);
      return;
    }
    Snapshot current = platform.snapshot();
    if (!before.sameAdmission(current) || !current.canPrepareEjection() || !current.permitted()) {
      show(State.CHANGED);
      return;
    }
    preparing = CameraImportGate.tryReservePreparation();
    if (preparing == null) {
      show(State.BUSY);
      return;
    }
    try {
      armed = new Ejection(current, now.getAsLong() + EJECTION_FLOW_MS);
      settingsOpen = true;
      MAIN.postDelayed(expire, EJECTION_FLOW_MS);
      dialogs.openSettings();
    } catch (RuntimeException unavailable) {
      settingsOpen = false;
      releasePreparation();
      show(State.UNSUPPORTED);
    }
  }

  private void invalidateEjection() {
    armed = null;
    if (!settingsOpen) {
      releasePreparation();
      show(State.CHANGED);
    }
  }

  private void expirePreparation() {
    if (armed != null && now.getAsLong() >= armed.expiresAt()) invalidateEjection();
  }

  private void releasePreparation() {
    armed = null;
    MAIN.removeCallbacks(expire);
    if (preparing == null) return;
    preparing.close();
    preparing = null;
  }

  private void confirmEjection(Ejection confirmation) {
    dialogs.confirm(
        () -> {
          if (armed != confirmation || !foreground || busy() || !idle.getAsBoolean()) return;
          Snapshot current = platform.snapshot();
          if (!confirmation.confirmed(current, now.getAsLong()) || !current.permitted()) {
            releasePreparation();
            show(State.CHANGED);
            return;
          }
          var reservation = preparing.handoffToWorker();
          releasePreparation();
          if (reservation == null) {
            show(State.BUSY);
            return;
          }
          start(current, confirmation, reservation);
        },
        () -> {
          if (armed == confirmation) {
            releasePreparation();
            show(null);
          }
        });
  }

  private void requestPermission(Snapshot snapshot) {
    Permission request = new Permission(snapshot);
    pending = request;
    show(State.PERMISSION);
    try {
      request.registration =
          platform.requestPermission(
              activity,
              snapshot.reader(),
              granted -> {
                if (pending != request) return;
                request.granted = granted;
                continuePermission();
              });
    } catch (IOException | RuntimeException unavailable) {
      pending = null;
      show(State.DENIED);
    }
  }

  private void continuePermission() {
    if (!foreground || pending == null || pending.granted == null) return;
    Permission request = pending;
    pending = null;
    close(request.registration);
    Snapshot current = platform.snapshot();
    if (!request.granted || !current.permitted()) {
      show(State.DENIED);
    } else if (!request.request.sameAdmission(current) || !current.canPrepareEjection()) {
      show(State.CHANGED);
    } else if (!idle.getAsBoolean()) {
      show(State.BUSY);
    } else {
      offerEjection(current);
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

  private void start(
      Snapshot snapshot, Ejection confirmation, CameraImportGate.Reservation reservation) {
    Run attempt =
        new Run(platform, new WeakReference<>(this), snapshot, confirmation, reservation, now);
    running = attempt;
    show(State.REDETECTING);
    try {
      worker.execute(attempt);
    } catch (RuntimeException failure) {
      reservation.close();
      running = null;
      show(State.FAILED);
    }
  }

  private void show(State next) {
    if (next != State.READY && next != State.SOURCE_REQUIRED) positive = null;
    if (destroyed) return;
    String message =
        next == null
            ? ""
            : switch (next) {
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
    render.accept(new Display(message, busy()));
  }

  private static final class Run implements Runnable {
    final Platform platform;
    final WeakReference<SdUsbReprobeController> owner;
    final Snapshot snapshot;
    final Ejection confirmation;
    final CameraImportGate.Reservation reservation;
    final LongSupplier now;
    final SdUsbReprobe.ClaimAuthorization authorization = new SdUsbReprobe.ClaimAuthorization();
    private final long unsafeMediaGeneration = MountedCameraCard.unsafeMediaGeneration();
    Snapshot restored;

    void invalidateBeforeClaim() {
      authorization.invalidateBeforeClaim();
    }

    Run(
        Platform platform,
        WeakReference<SdUsbReprobeController> owner,
        Snapshot snapshot,
        Ejection confirmation,
        CameraImportGate.Reservation reservation,
        LongSupplier now) {
      this.platform = platform;
      this.owner = owner;
      this.snapshot = snapshot;
      this.confirmation = confirmation;
      this.reservation = reservation;
      this.now = now;
    }

    @Override
    public void run() {
      State result;
      try (reservation) {
        result =
            SdUsbReprobe.run(
                confirmation,
                snapshot,
                platform,
                CameraImportGate.lock,
                () -> authorization.cancelled() || !reservation.isOpen(),
                now,
                REMOUNT_WAIT_MS,
                new SdUsbReprobe.Observer() {
                  @Override
                  public boolean startingClaim() {
                    return authorization.tryStart(
                        () ->
                            reservation.isOpen()
                                && unsafeMediaGeneration
                                    == MountedCameraCard.unsafeMediaGeneration());
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
                  public void restored(Snapshot current) {
                    restored = current;
                  }
                });
      } catch (InterruptedException stopped) {
        Thread.currentThread().interrupt();
        result = State.CANCELLED;
      }
      State terminal = result;
      android.util.Log.i(LOG, "outcome=" + terminal.name());
      MAIN.post(
          () -> {
            var controller = owner.get();
            if (controller == null || controller.destroyed || controller.running != this) return;
            controller.running = null;
            if (authorization.cancelled()) {
              controller.show(State.CANCELLED);
            } else if (restored != null) {
              Snapshot current = platform.snapshot();
              if (restored.sameAdmission(current) && current.permitted()) {
                controller.positive = restored;
                controller.show(terminal);
              } else {
                controller.show(State.CHANGED);
              }
            } else {
              controller.show(terminal);
            }
            if (controller.foreground) controller.refresh.run();
          });
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

  private record AndroidDialogs(Activity activity) implements Dialogs {
    private static final String ANDROID_SETTINGS = "com.android.settings";

    @Override
    public void prepare(Runnable open, Runnable cancel) {
      show(
          "Androidでカードを取り出す",
          "フォーマットは選ばないでください。\n"
              + "両方のカードがポータブルストレージで、各カードにパーティションが1つの場合に使えます。"
              + "\n\n「デバイス情報」→「ストレージ」を開き、下にある「USBドライブ」欄の"
              + "「USBデバイスのマウントを解除」で、表示されるカードをすべて取り出してください。"
              + "リーダーはつないだままLoupeに戻ります。"
              + "\n\n構成が分からない場合は、安全に取り出してリーダーを挿し直してください。",
          "設定を開く",
          open,
          cancel);
    }

    @Override
    public void confirm(Runnable confirm, Runnable cancel) {
      show(
          "カードの取り出しを確認",
          "Androidが表示中のすべてのカードを安全に取り外せると案内したことを確認してください。"
              + "リーダーはつないだままにし、フォーマットやマウントが進行中ならキャンセルしてください。",
          "取り出し済み",
          confirm,
          cancel);
    }

    private void show(
        String title, String message, String actionLabel, Runnable action, Runnable cancel) {
      var builder = new MaterialAlertDialogBuilder(activity);
      Context context = builder.getContext();
      float density = context.getResources().getDisplayMetrics().density;
      int windowHeight = context.getResources().getConfiguration().screenHeightDp;
      int maxBodyHeight = Math.round(Math.min(245f, windowHeight / 2f) * density);
      var scroll =
          new ScrollView(context) {
            @Override
            protected void onMeasure(int width, int height) {
              int limit =
                  MeasureSpec.getMode(height) == MeasureSpec.UNSPECIFIED
                      ? maxBodyHeight
                      : Math.min(MeasureSpec.getSize(height), maxBodyHeight);
              super.onMeasure(width, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
            }
          };
      scroll.setTag("sd-reprobe-dialog-body");
      scroll.setScrollbarFadingEnabled(false);
      var text = new TextView(context);
      text.setTextAppearance(
          com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
      text.setTextColor(
          MaterialColors.getColor(text, com.google.android.material.R.attr.colorOnSurfaceVariant));
      int side = Math.round(24 * density);
      text.setPadding(side, Math.round(8 * density), side, Math.round(12 * density));
      text.setText(message);
      scroll.addView(text);
      var dialog =
          builder
              .setTitle(title)
              .setView(scroll)
              .setNegativeButton("キャンセル", (ignored, which) -> cancel.run())
              .setOnCancelListener(ignored -> cancel.run())
              .setPositiveButton(actionLabel, (ignored, which) -> action.run())
              .show();
      int minimumTouchHeight = Math.round(48 * density);
      dialog
          .getButton(android.content.DialogInterface.BUTTON_NEGATIVE)
          .setMinHeight(minimumTouchHeight);
      dialog
          .getButton(android.content.DialogInterface.BUTTON_POSITIVE)
          .setMinHeight(minimumTouchHeight);
    }

    @Override
    public void openSettings() {
      try {
        activity.startActivity(
            new Intent(Settings.ACTION_MEMORY_CARD_SETTINGS).setPackage(ANDROID_SETTINGS));
      } catch (ActivityNotFoundException unavailable) {
        activity.startActivity(
            new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).setPackage(ANDROID_SETTINGS));
      }
    }
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
      try {
        if (!SdUsbReprobe.supportedPhone(Build.DEVICE, Build.VERSION.SDK_INT, Build.FINGERPRINT))
          return unavailable();
        String destination = SdSourceRegistrations.normalizeVolumeUuid(PhotoStorage.selected(app));
        UserManager users = app.getSystemService(UserManager.class);
        if (destination == null || users == null || !users.isUserUnlocked()) return unavailable();
        var devices = usb.getDeviceList();
        if (devices.size() != 1) return unavailable();
        UsbDevice device = devices.values().iterator().next();
        Reader reader = reader(device);
        if (reader == null) return unavailable();
        return new Snapshot(reader, destination, currentVolumes(), usb.hasPermission(device));
      } catch (RuntimeException unavailable) {
        return unavailable();
      }
    }

    private List<SdUsbReprobe.Volume> currentVolumes() {
      try {
        StorageManager storage = app.getSystemService(StorageManager.class);
        var visible = new ArrayList<SdUsbReprobe.VisibleVolume>();
        for (StorageVolume volume : storage.getStorageVolumes()) visible.add(visible(volume));
        var volumes = SdUsbReprobe.portableLayout(visible);
        return volumes == null ? List.of() : volumes;
      } catch (RuntimeException unavailable) {
        // USB and the selected target can still be known while vold discovers the media.
        return List.of();
      }
    }

    private Snapshot unavailable() {
      return new Snapshot(null, null, List.of(), false);
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
