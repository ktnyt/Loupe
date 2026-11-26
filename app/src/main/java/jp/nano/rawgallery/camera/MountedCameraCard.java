package jp.nano.rawgallery.camera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import jp.nano.rawgallery.PhotoStorage;

/** The guarded SD-to-SD route accepts only the mounted volume of the selected, granted tree. */
public final class MountedCameraCard {
  static final String CHOOSE_SOURCE = "取り込み元のSDカードを接続し、DCIMフォルダを選んでください。";
  private static final String TARGET_CHANGED = "保存先が変わりました。取り込み元と保存先を確認してください。";

  public static final class Unavailable extends IOException {
    private final boolean reselect;

    Unavailable(String message) {
      this(message, false);
    }

    private Unavailable(String message, boolean reselect) {
      super(message);
      this.reselect = reselect;
    }

    public boolean canReselect() {
      return reselect;
    }
  }

  private static final AtomicLong detachGeneration =
      new AtomicLong(android.os.SystemClock.elapsedRealtimeNanos());
  private static final CopyOnWriteArrayList<Consumer<MountChange>> mountListeners =
      new CopyOnWriteArrayList<>();

  public record MountChange(String volumeUuid, boolean detached, String state) {}

  private static final AtomicLong unsafeMediaGeneration = new AtomicLong();
  private static boolean observing;

  public static long detachGeneration() {
    return detachGeneration.get();
  }

  static long unsafeMediaGeneration() {
    return unsafeMediaGeneration.get();
  }

  public static synchronized Runnable observeMounts(
      Context context, Consumer<MountChange> listener) {
    startObserving(context);
    mountListeners.add(listener);
    return () -> mountListeners.remove(listener);
  }

  static synchronized void startObserving(Context context) {
    if (observing) return;
    Context app = context.getApplicationContext();
    IntentFilter filter = new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
    filter.addAction(Intent.ACTION_MEDIA_EJECT);
    filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
    filter.addAction(Intent.ACTION_MEDIA_REMOVED);
    filter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
    filter.addAction(Intent.ACTION_MEDIA_CHECKING);
    filter.addAction(Intent.ACTION_MEDIA_NOFS);
    filter.addAction(Intent.ACTION_MEDIA_UNMOUNTABLE);
    filter.addAction(Intent.ACTION_MEDIA_SHARED);
    filter.addDataScheme("file");
    BroadcastReceiver receiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context received, Intent event) {
            legacyMediaChanged(notifiedVolumeUuid(event), event.getAction());
          }
        };
    if (Build.VERSION.SDK_INT >= 33) {
      app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
    } else {
      app.registerReceiver(receiver, filter);
    }
    observing = true;
    try {
      BroadcastReceiver usb =
          new BroadcastReceiver() {
            @Override
            public void onReceive(Context received, Intent event) {
              var device =
                  Build.VERSION.SDK_INT >= 33
                      ? event.getParcelableExtra(
                          UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice.class)
                      : (android.hardware.usb.UsbDevice)
                          event.getParcelableExtra(UsbManager.EXTRA_DEVICE);
              if (device != null && UsbMassStorage.Companion.isMassStorage(device)) {
                boolean detached = UsbManager.ACTION_USB_DEVICE_DETACHED.equals(event.getAction());
                publishMountChange(new MountChange(null, detached, null));
              }
            }
          };
      var devices = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
      devices.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
      if (Build.VERSION.SDK_INT >= 33) {
        app.registerReceiver(usb, devices, Context.RECEIVER_NOT_EXPORTED);
      } else {
        app.registerReceiver(usb, devices);
      }
      StorageManager storage = app.getSystemService(StorageManager.class);
      if (storage != null) {
        storage.registerStorageVolumeCallback(
            app.getMainExecutor(),
            new StorageManager.StorageVolumeCallback() {
              @Override
              public void onStateChanged(StorageVolume volume) {
                if (!volume.isRemovable() || volume.isEmulated()) return;
                String uuid = SdSourceRegistrations.normalizeVolumeUuid(volume.getUuid());
                volumeChanged(uuid, volume.getState());
              }
            });
      }
    } catch (RuntimeException unavailable) {
      // Keep the existing media-broadcast path if supplemental notifications are unavailable.
    }
  }

  static void legacyMediaChanged(String uuid, String action) {
    if (action == null) return;
    String state =
        switch (action) {
          case Intent.ACTION_MEDIA_EJECT -> Environment.MEDIA_EJECTING;
          case Intent.ACTION_MEDIA_UNMOUNTED -> Environment.MEDIA_UNMOUNTED;
          case Intent.ACTION_MEDIA_MOUNTED -> Environment.MEDIA_MOUNTED;
          case Intent.ACTION_MEDIA_REMOVED -> Environment.MEDIA_REMOVED;
          case Intent.ACTION_MEDIA_BAD_REMOVAL -> Environment.MEDIA_BAD_REMOVAL;
          case Intent.ACTION_MEDIA_CHECKING -> Environment.MEDIA_CHECKING;
          case Intent.ACTION_MEDIA_NOFS -> Environment.MEDIA_NOFS;
          case Intent.ACTION_MEDIA_UNMOUNTABLE -> Environment.MEDIA_UNMOUNTABLE;
          case Intent.ACTION_MEDIA_SHARED -> Environment.MEDIA_SHARED;
          default -> null;
        };
    if (state != null) volumeChanged(uuid, state);
  }

  static void volumeChanged(String uuid, String state) {
    boolean readable =
        Environment.MEDIA_MOUNTED.equals(state)
            || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
    publishMountChange(new MountChange(uuid, !readable, state));
  }

  private static void publishMountChange(MountChange change) {
    // Neither Android notification provides a shared transition ID. A delayed notification
    // must not make a later real removal look like a duplicate.
    if (change.volumeUuid() == null || !SdUsbReprobe.isEjectionState(change.state())) {
      unsafeMediaGeneration.incrementAndGet();
    }
    if (change.detached()) detachGeneration.incrementAndGet();
    for (Consumer<MountChange> listener : mountListeners) listener.accept(change);
  }

  static void requireGeneration(long expected) throws Unavailable {
    if (detachGeneration() != expected) throw new Unavailable("カードの接続が変わりました。取り込み元と保存先を確認してください。");
  }

  private MountedCameraCard() {}

  public static String targetWithGenericReader(Context context, String target) {
    if (PhotoStorage.PRIMARY.equals(target)) return null;
    try {
      UsbManager manager = context.getSystemService(UsbManager.class);
      if (manager == null) return null;
      return manager.getDeviceList().values().stream()
              .anyMatch(
                  device ->
                      !CameraUsb.knownCamera(device)
                          && UsbMassStorage.Companion.isMassStorage(device))
          ? target
          : null;
    } catch (RuntimeException unavailable) {
      // An unknown USB topology cannot authorize an external same-card import.
      return target;
    }
  }

  public static String requireDistinctSource(
      Context context,
      Uri tree,
      String expectedTarget,
      String selectedTarget,
      boolean requireRegistered,
      BooleanSupplier cancelled)
      throws IOException {
    CameraPhotoPublisher.check(cancelled);
    if (PhotoStorage.PRIMARY.equals(expectedTarget) || !expectedTarget.equals(selectedTarget))
      throw new Unavailable(TARGET_CHANGED);
    String source = mountedSourceVolume(context, tree);
    switch (CameraCardRouting.compareVolumes(expectedTarget, selectedTarget, source)) {
      case SOURCE_UNAVAILABLE -> throw new Unavailable(CHOOSE_SOURCE, true);
      case SAME_CARD -> throw new Unavailable("取り込み元と保存先が同じカードです。別のカードを選んでください。", true);
      case TARGET_CHANGED -> throw new Unavailable(TARGET_CHANGED);
      case DISTINCT -> {}
    }
    if (requireRegistered) requireGrant(context, tree);
    if (!CameraDocumentSource.isReachableDirectory(context.getContentResolver(), tree, cancelled))
      throw new Unavailable(CHOOSE_SOURCE, true);
    CameraPhotoPublisher.check(cancelled);
    return source;
  }

  public static String requireRemovableSource(Context context, Uri tree, String target)
      throws Unavailable {
    String source = mountedSourceVolume(context, tree);
    if (source == null) throw new Unavailable("接続したSDカードのDCIMフォルダを選んでください。", true);
    if (source.equalsIgnoreCase(target))
      throw new Unavailable("取り込み元と保存先が同じカードです。別のカードを選んでください。", true);
    return source;
  }

  public static boolean isUnmountedRemovableTree(Context context, Uri tree) {
    return isRemovableTree(tree) && mountedSourceVolume(context, tree, false) == null;
  }

  public static boolean isSameMountedCard(Context context, Uri tree, String target) {
    String source = mountedSourceVolume(context, tree, false);
    return source != null && source.equalsIgnoreCase(target);
  }

  static boolean isRemovableTree(Uri tree) {
    return SdSourceRegistrations.volumeUuid(tree) != null;
  }

  static String notifiedVolumeUuid(Intent event) {
    StorageVolume volume =
        Build.VERSION.SDK_INT >= 33
            ? event.getParcelableExtra(StorageVolume.EXTRA_STORAGE_VOLUME, StorageVolume.class)
            : event.getParcelableExtra(StorageVolume.EXTRA_STORAGE_VOLUME);
    if (volume != null) return SdSourceRegistrations.normalizeVolumeUuid(volume.getUuid());
    Uri path = event.getData();
    return SdSourceRegistrations.normalizeVolumeUuid(
        path == null ? null : path.getLastPathSegment());
  }

  static Uri registeredMountSource(Context context, String uuid, String destination) {
    if (uuid == null
        || destination == null
        || uuid.equals(SdSourceRegistrations.normalizeVolumeUuid(destination))) return null;
    Uri tree = SdSourceRegistrations.forVolume(context, uuid);
    String source = mountedListingVolume(context, tree);
    return source != null && !source.equalsIgnoreCase(destination) ? tree : null;
  }

  static String mountedSourceVolume(Context context, Uri tree) {
    return mountedSourceVolume(context, tree, true);
  }

  // Honor an existing volume-root grant for listing without making it a strict source binding.
  static String mountedListingVolume(Context context, Uri tree) {
    return mountedListingVolume(tree, () -> mountedSourceVolume(context, tree, false));
  }

  static String mountedListingVolume(Uri tree, Supplier<String> lookupMountedVolume) {
    return isRemovableTree(tree) && CameraDocumentSource.isImportTree(tree, true)
        ? lookupMountedVolume.get()
        : null;
  }

  private static String mountedSourceVolume(Context context, Uri tree, boolean requireDcim) {
    return CameraDocumentSource.isImportTree(tree, !requireDcim)
        ? SdSourceRegistrations.mountedVolume(context, tree)
        : null;
  }

  static void requireMountedSource(Context context, Uri tree, String expected) throws Unavailable {
    if (!expected.equals(mountedSourceVolume(context, tree)))
      throw new Unavailable(CHOOSE_SOURCE, true);
  }

  static boolean matchesRegistration(Uri tree, String registered, boolean readGrant) {
    return tree != null && tree.toString().equals(registered) && readGrant;
  }

  static void requireGrant(Context context, Uri tree) throws Unavailable {
    try {
      if (SdSourceRegistrations.contains(context, tree)
          && SdSourceRegistrations.hasReadGrant(context, tree)) return;
    } catch (RuntimeException unavailable) {
      // An unconfirmed grant must not authorize a receipt shortcut or an original read.
    }
    throw new Unavailable("取り込み元のフォルダまたは読み取り権限が変わりました。DCIMフォルダを選び直してください。", true);
  }
}
