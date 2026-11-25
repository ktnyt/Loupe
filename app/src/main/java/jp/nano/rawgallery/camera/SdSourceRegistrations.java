package jp.nano.rawgallery.camera;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Removable SAF source registrations keyed by filesystem UUID. */
public final class SdSourceRegistrations {
    private static final String LATEST = "sd_tree_uri";
    private static final String PREFIX = "sd_tree_uri_volume_";
    private static final String LEGACY = "sd_tree_uri_legacy";
    private static final String AUTHORITY = "com.android.externalstorage.documents";
    private static final Pattern FAT = Pattern.compile("[0-9a-f]{4}-?[0-9a-f]{4}");
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");

    private SdSourceRegistrations() {}

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("camera_import", Context.MODE_PRIVATE);
    }

    public static String normalizeVolumeUuid(String uuid) {
        if (uuid == null) return null;
        String lower = uuid.toLowerCase(Locale.ROOT);
        if (UUID.matcher(lower).matches()) return lower;
        if (!FAT.matcher(lower).matches()) return null;
        return lower.length() == 9 ? lower : lower.substring(0, 4) + "-" + lower.substring(4);
    }

    public static String volumeUuid(Uri tree) {
        if (!plainTree(tree) || !AUTHORITY.equals(tree.getAuthority())) return null;
        String document = DocumentsContract.getTreeDocumentId(tree);
        int separator = document.indexOf(':');
        return separator > 0 ? normalizeVolumeUuid(document.substring(0, separator)) : null;
    }

    private static boolean plainTree(Uri tree) {
        return tree != null && "content".equals(tree.getScheme()) && tree.getAuthority() != null
                && tree.getQuery() == null && tree.getFragment() == null && tree.getPathSegments().size() == 2
                && DocumentsContract.isTreeUri(tree);
    }

    private static boolean isLegacyTree(Uri tree) {
        if (!plainTree(tree) || !CameraDocumentSource.isImportTree(tree, true)) return false;
        if (!AUTHORITY.equals(tree.getAuthority())) return true;
        return DocumentsContract.getTreeDocumentId(tree).indexOf(':') > 0 && volumeUuid(tree) == null;
    }

    public static synchronized String latest(Context context) {
        return value(preferences(context), LATEST);
    }

    public static synchronized List<Uri> registrations(Context context) {
        Map<String, ?> settings = preferences(context).getAll();
        Map<String, Uri> volumes = new TreeMap<>();
        for (String key : settings.keySet()) {
            if (!key.startsWith(PREFIX)) continue;
            Uri tree = tree(settings, key);
            String uuid = volumeUuid(tree);
            if (uuid != null && key.equals(PREFIX + uuid)) volumes.put(uuid, tree);
        }
        Uri latest = tree(settings, LATEST);
        String latestUuid = volumeUuid(latest);
        if (latestUuid != null) volumes.putIfAbsent(latestUuid, latest);
        List<Uri> registrations = new ArrayList<>(volumes.values());
        Uri legacy = isLegacyTree(latest) ? latest : tree(settings, LEGACY);
        if (isLegacyTree(legacy)) registrations.add(legacy);
        return List.copyOf(registrations);
    }

    public static synchronized Uri forVolume(Context context, String uuid) {
        String key = normalizeVolumeUuid(uuid);
        if (key == null) return null;
        SharedPreferences settings = preferences(context);
        Uri registered = parse(value(settings, PREFIX + key));
        if (key.equals(volumeUuid(registered))) return registered;
        Uri latest = parse(value(settings, LATEST));
        return key.equals(volumeUuid(latest)) ? latest : null;
    }

    public static synchronized boolean contains(Context context, Uri tree) {
        if (tree == null) return false;
        String uuid = volumeUuid(tree);
        return uuid == null ? registrations(context).contains(tree) : tree.equals(forVolume(context, uuid));
    }

    /** The service owns the registration gate and validates the mounted path and grant before saving. */
    public static synchronized boolean save(Context context, Uri tree) {
        String uuid = volumeUuid(tree);
        if (uuid == null || !CameraDocumentSource.isImportTree(tree, false)) return false;
        SharedPreferences preferences = preferences(context);
        Map<String, ?> previous = preferences.getAll();
        String newKey = PREFIX + uuid;
        SharedPreferences.Editor update = preferences.edit();
        Uri priorTree = tree(previous, LATEST);
        String priorUuid = volumeUuid(priorTree);
        String migrationKey = priorUuid == null ? LEGACY : PREFIX + priorUuid;
        boolean migrate = (priorUuid != null || isLegacyTree(priorTree))
                && !newKey.equals(migrationKey) && !previous.containsKey(migrationKey);
        if (migrate) update.putString(migrationKey, priorTree.toString());
        if (update.putString(newKey, tree.toString()).putString(LATEST, tree.toString()).commit()) return true;

        // SharedPreferences publishes changes in memory even when its disk commit fails.
        SharedPreferences.Editor restore = preferences.edit();
        if (migrate) restore.remove(migrationKey);
        restore.putString(newKey, value(previous, newKey)).putString(LATEST, value(previous, LATEST)).commit();
        return false;
    }

    public static boolean directDcimAuthorized(Context context, Uri tree) {
        String uuid = volumeUuid(tree);
        return uuid != null && (uuid + ":DCIM").equalsIgnoreCase(DocumentsContract.getTreeDocumentId(tree))
                && contains(context, tree) && hasReadGrant(context, tree);
    }

    public static boolean hasReadGrant(Context context, Uri tree) {
        if (tree == null) return false;
        try {
            return context.getContentResolver().getPersistedUriPermissions().stream()
                    .anyMatch(grant -> grant.isReadPermission() && tree.equals(grant.getUri()));
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public static boolean hasReadableRegistration(Context context) {
        return hasReadableRegistration(registrations(context), tree -> hasReadGrant(context, tree));
    }

    static boolean hasReadableRegistration(List<Uri> registrations, Predicate<Uri> readGranted) {
        return registrations.stream().anyMatch(tree -> volumeUuid(tree) != null && readGranted.test(tree));
    }

    public record MountedSources(List<Uri> trees, Uri destinationTree, Uri legacyTree) {
        public Uri unambiguousTree() {
            if (trees.size() == 1) return trees.get(0);
            return trees.isEmpty() ? legacyTree : null;
        }
    }

    /** Checks the current selection without probing the document provider. The caller must still check its grant. */
    public static boolean isUnambiguousSource(Context context, Uri tree, String destination) {
        if (tree == null) return false;
        try {
            return tree.equals(mountedSources(context, registrations(context), destination).unambiguousTree());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public static List<Uri> mounted(Context context, String destination) {
        return mountedSources(context, registrations(context), destination).trees();
    }

    public static MountedSources mountedSources(Context context, List<Uri> registrations, String destination) {
        return mountedSources(registrations, storageVolumes(context), destination);
    }

    static MountedSources mountedSources(List<Uri> registrations, List<Volume> volumes, String destination) {
        String destinationUuid = normalizeVolumeUuid(destination);
        List<Uri> mounted = new ArrayList<>();
        Uri destinationTree = null;
        Uri legacyTree = null;
        for (Uri tree : registrations) {
            String uuid = volumeUuid(tree);
            if (uuid == null) {
                legacyTree = tree;
                continue;
            }
            String name = mountedVolume(volumes, uuid);
            if (name == null) continue;
            if (name.equalsIgnoreCase(destination) || uuid.equals(destinationUuid)) destinationTree = tree;
            else mounted.add(tree);
        }
        return new MountedSources(List.copyOf(mounted), destinationTree, legacyTree);
    }

    public static String mountedVolume(Context context, Uri tree) {
        return mountedVolume(storageVolumes(context), volumeUuid(tree));
    }

    record Volume(String uuid, String name, boolean removable, boolean readable) {
        static Volume from(StorageVolume volume) {
            String state = volume.getState();
            return new Volume(normalizeVolumeUuid(volume.getUuid()), volume.getMediaStoreVolumeName(),
                    volume.isRemovable() && !volume.isEmulated() && !volume.isPrimary(),
                    Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state));
        }
    }

    private static List<Volume> storageVolumes(Context context) {
        try {
            StorageManager manager = context.getSystemService(StorageManager.class);
            if (manager == null) return List.of();
            List<Volume> result = new ArrayList<>();
            for (StorageVolume volume : manager.getStorageVolumes()) result.add(Volume.from(volume));
            return result;
        } catch (RuntimeException unavailable) {
            return List.of();
        }
    }

    private static String mountedVolume(List<Volume> volumes, String uuid) {
        if (uuid == null) return null;
        Volume matched = null;
        for (Volume volume : volumes) {
            if (!volume.removable() || !uuid.equals(volume.uuid())) continue;
            if (matched != null) return null;
            matched = volume;
        }
        if (matched == null || !matched.readable()) return null;
        String name = matched.name();
        if (name == null || name.isEmpty() || MediaStore.VOLUME_EXTERNAL.equalsIgnoreCase(name)
                || MediaStore.VOLUME_EXTERNAL_PRIMARY.equalsIgnoreCase(name))
            return null;
        for (Volume volume : volumes) {
            if (volume != matched && volume.readable() && name.equalsIgnoreCase(volume.name()))
                return null;
        }
        return name;
    }

    private static String value(Map<String, ?> settings, String key) {
        return settings.get(key) instanceof String stored ? stored : null;
    }

    private static String value(SharedPreferences settings, String key) {
        try { return settings.getString(key, null); }
        catch (ClassCastException invalid) { return null; }
    }

    private static Uri tree(Map<String, ?> settings, String key) {
        return parse(value(settings, key));
    }

    private static Uri parse(String value) {
        return value == null ? null : Uri.parse(value);
    }
}
