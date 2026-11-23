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
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import jp.nano.rawgallery.PhotoStorage;

/** The guarded SD-to-SD route accepts only the mounted volume of the selected, granted tree. */
public final class MountedCameraCard {
    static final String CHOOSE_SOURCE = "取り込み元のSDカードを接続し、DCIMフォルダを選んでください。";
    private static final String TARGET_CHANGED = "保存先が変わりました。取り込み元と保存先を確認してください。";

    public static final class Unavailable extends IOException {
        private final boolean reselect;
        Unavailable(String message) { this(message, false); }
        private Unavailable(String message, boolean reselect) {
            super(message);
            this.reselect = reselect;
        }
        public boolean canReselect() { return reselect; }
    }

    private static final AtomicLong detachGeneration = new AtomicLong(android.os.SystemClock.elapsedRealtimeNanos());
    private static final CopyOnWriteArrayList<Consumer<Boolean>> mountListeners = new CopyOnWriteArrayList<>();
    private static boolean observing;

    public static long detachGeneration() { return detachGeneration.get(); }

    public static synchronized Runnable observeMounts(Context context, Consumer<Boolean> listener) {
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
        filter.addDataScheme("file");
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context received, Intent event) {
                String action = event.getAction();
                boolean detached = !Intent.ACTION_MEDIA_MOUNTED.equals(action);
                if (detached) detachGeneration.incrementAndGet();
                if (Intent.ACTION_MEDIA_REMOVED.equals(action) || Intent.ACTION_MEDIA_BAD_REMOVAL.equals(action)) {
                    SdRemountMountNotice.physicalDeparture(notifiedVolumeUuid(event));
                }
                for (Consumer<Boolean> listener : mountListeners) listener.accept(detached);
            }
        };
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(receiver, filter);
        observing = true;
    }

    static void requireGeneration(long expected) throws Unavailable {
        if (detachGeneration() != expected)
            throw new Unavailable("カードの接続が変わりました。取り込み元と保存先を確認してください。");
    }

    private MountedCameraCard() {}

    public static String targetWithGenericReader(Context context, String target) {
        if (PhotoStorage.PRIMARY.equals(target)) return null;
        try {
            UsbManager manager = context.getSystemService(UsbManager.class);
            if (manager == null) return null;
            return manager.getDeviceList().values().stream().anyMatch(device -> !CameraUsb.knownCamera(device)
                    && UsbMassStorage.Companion.isMassStorage(device)) ? target : null;
        } catch (RuntimeException unavailable) {
            // An unknown USB topology cannot authorize an external same-card import.
            return target;
        }
    }

    public static String requireDistinctSource(Context context, Uri tree, String expectedTarget, String selectedTarget,
            boolean requireRegistered, BooleanSupplier cancelled) throws IOException {
        CameraPhotoPublisher.check(cancelled);
        if (PhotoStorage.PRIMARY.equals(expectedTarget) || !expectedTarget.equals(selectedTarget))
            throw new Unavailable(TARGET_CHANGED);
        String source = mountedSourceVolume(context, tree);
        switch (CameraCardRouting.compareVolumes(expectedTarget, selectedTarget, source)) {
            case SOURCE_UNAVAILABLE -> throw new Unavailable(CHOOSE_SOURCE, true);
            case SAME_CARD -> throw new Unavailable("取り込み元と保存先が同じカードです。別のカードを選んでください。", true);
            case TARGET_CHANGED -> throw new Unavailable(TARGET_CHANGED);
            case DISTINCT -> { }
        }
        if (requireRegistered) requireGrant(context, tree);
        if (!CameraDocumentSource.isReachableDirectory(context.getContentResolver(), tree, cancelled))
            throw new Unavailable(CHOOSE_SOURCE, true);
        CameraPhotoPublisher.check(cancelled);
        return source;
    }

    public static String requireRemovableSource(Context context, Uri tree, String target) throws Unavailable {
        String source = mountedSourceVolume(context, tree);
        if (source == null)
            throw new Unavailable("接続したSDカードのDCIMフォルダを選んでください。", true);
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


    private static boolean isRemovableTree(Uri tree) {
        if (tree == null || !"com.android.externalstorage.documents".equals(tree.getAuthority())
                || !DocumentsContract.isTreeUri(tree)) return false;
        try {
            String document = DocumentsContract.getTreeDocumentId(tree);
            int colon = document.indexOf(':');
            return colon > 0 && document.substring(0, colon).matches("(?i)[0-9a-f]{4}-?[0-9a-f]{4}");
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    static boolean matchesMountNotice(Uri tree, String uuid) {
        if (!isRemovableTree(tree) || uuid == null) return false;
        String id = DocumentsContract.getTreeDocumentId(tree);
        return id.substring(0, id.indexOf(':')).replace("-", "").equalsIgnoreCase(uuid.replace("-", ""));
    }

    static String notifiedVolumeUuid(Intent event) {
        StorageVolume volume = Build.VERSION.SDK_INT >= 33
                ? event.getParcelableExtra(StorageVolume.EXTRA_STORAGE_VOLUME, StorageVolume.class)
                : event.getParcelableExtra(StorageVolume.EXTRA_STORAGE_VOLUME);
        if (volume != null && SdCardRemountPlan.isFilesystemUuid(volume.getUuid())) return volume.getUuid();
        Uri path = event.getData();
        String candidate = path == null ? null : path.getLastPathSegment();
        return SdCardRemountPlan.isFilesystemUuid(candidate) ? candidate : null;
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
                ? lookupMountedVolume.get() : null;
    }

    private static String mountedSourceVolume(Context context, Uri tree, boolean requireDcim) {
        if (tree == null || !"com.android.externalstorage.documents".equals(tree.getAuthority())
                || !DocumentsContract.isTreeUri(tree)) return null;
        try {
            String root = DocumentsContract.getTreeDocumentId(tree);
            int colon = root.indexOf(':');
            if (colon <= 0) return null;
            String uuid = root.substring(0, colon);
            String folder = root.substring(colon + 1);
            if (requireDcim && !folder.equalsIgnoreCase("DCIM") && !folder.regionMatches(true, 0, "DCIM/", 0, 5)) return null;
            String source = null;
            for (StorageVolume volume : context.getSystemService(StorageManager.class).getStorageVolumes()) {
                if (!uuid.equalsIgnoreCase(volume.getUuid()) || !volume.isRemovable() || volume.isEmulated())
                    continue;
                if (source != null || !readable(volume.getState())) return null;
                source = volume.getMediaStoreVolumeName();
                if (source == null || MediaStore.VOLUME_EXTERNAL.equals(source) || PhotoStorage.PRIMARY.equals(source))
                    return null;
            }
            return source;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static boolean readable(String state) {
        return Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
    }

    static void requireMountedSource(Context context, Uri tree, String expected) throws Unavailable {
        if (!expected.equals(mountedSourceVolume(context, tree))) throw new Unavailable(CHOOSE_SOURCE, true);
    }

    static boolean matchesRegistration(Uri tree, String registered, boolean readGrant) {
        return tree != null && tree.toString().equals(registered) && readGrant;
    }

    static void requireGrant(Context context, Uri tree) throws Unavailable {
        try {
            String registered = context.getSharedPreferences("camera_import", 0).getString("sd_tree_uri", null);
            if (matchesRegistration(tree, registered, context.getContentResolver().getPersistedUriPermissions().stream()
                    .anyMatch(grant -> grant.isReadPermission() && grant.getUri().equals(tree)))) return;
        } catch (RuntimeException unavailable) {
            // An unconfirmed grant must not authorize a receipt shortcut or an original read.
        }
        throw new Unavailable("取り込み元のフォルダまたは読み取り権限が変わりました。DCIMフォルダを選び直してください。", true);
    }

}
