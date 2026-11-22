package jp.nano.rawgallery.camera;

import android.content.Context;
import android.database.Cursor;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import jp.nano.rawgallery.PhotoStorage;

/** The guarded SD-to-SD route accepts only the mounted volume of the selected, granted tree. */
public final class MountedCameraCard {
    static final String CHOOSE_SOURCE = "取り込み元のSDカードを接続し、DCIMフォルダを選んでください。";
    private static final String TARGET_CHANGED = "保存先が変わりました。取り込み元と保存先を確認してください。";

    public static final class Unavailable extends IOException {
        Unavailable(String message) { super(message); }
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
            case SOURCE_UNAVAILABLE -> throw new Unavailable(CHOOSE_SOURCE);
            case SAME_CARD -> throw new Unavailable("取り込み元と保存先が同じカードです。別のカードを選んでください。");
            case TARGET_CHANGED -> throw new Unavailable(TARGET_CHANGED);
            case DISTINCT -> { }
        }
        if (requireRegistered) requireGrant(context, tree);
        if (!isReachable(context, tree)) throw new Unavailable(CHOOSE_SOURCE);
        CameraPhotoPublisher.check(cancelled);
        return source;
    }

    private static String mountedSourceVolume(Context context, Uri tree) {
        if (tree == null || !"com.android.externalstorage.documents".equals(tree.getAuthority())
                || !DocumentsContract.isTreeUri(tree)) return null;
        try {
            String root = DocumentsContract.getTreeDocumentId(tree);
            int colon = root.indexOf(':');
            if (colon <= 0) return null;
            String uuid = root.substring(0, colon);
            String folder = root.substring(colon + 1);
            if (!folder.equalsIgnoreCase("DCIM") && !folder.regionMatches(true, 0, "DCIM/", 0, 5)) return null;
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

    static void requireGrant(Context context, Uri tree) throws Unavailable {
        try {
            String registered = context.getSharedPreferences("camera_import", 0).getString("sd_tree_uri", null);
            if (tree.toString().equals(registered)
                    && context.getContentResolver().getPersistedUriPermissions().stream()
                            .anyMatch(grant -> grant.isReadPermission() && tree.equals(grant.getUri()))) return;
        } catch (RuntimeException unavailable) {
            // An unconfirmed grant must not authorize a receipt shortcut or an original read.
        }
        throw new Unavailable("取り込み元のフォルダまたは読み取り権限が変わりました。DCIMフォルダを選び直してください。");
    }

    private static boolean isReachable(Context context, Uri tree) {
        try {
            var resolver = context.getContentResolver();
            Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor cursor = resolver.query(root, new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE},
                    null, null, null)) {
                return cursor != null && cursor.moveToFirst()
                        && DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(0));
            }
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
