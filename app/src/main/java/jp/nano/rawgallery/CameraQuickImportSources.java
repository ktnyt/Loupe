package jp.nano.rawgallery;

import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import jp.nano.rawgallery.camera.CameraCredentialStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Local discovery only. No radio/network connection and no photo reads. Call off main. */
final class CameraQuickImportSources {
    static final class Profile {
        final String id, name;
        Profile(String id, String name) { this.id = id; this.name = name; }
    }
    static final class Sources {
        final Uri sd;
        final List<Profile> profiles;
        Sources(Uri sd, List<Profile> profiles) { this.sd = sd; this.profiles = List.copyOf(profiles); }
    }
    static Uri reachableSdTree(Context context, CancellationSignal cancelled) {
        String saved = context.getSharedPreferences("camera_import", 0).getString("sd_tree_uri", null);
        if (saved == null) return null;
        try {
            Uri tree = Uri.parse(saved);
            boolean granted = false;
            for (var grant : context.getContentResolver().getPersistedUriPermissions())
                if (grant.isReadPermission() && grant.getUri().equals(tree)) granted = true;
            if (!granted) return null;
            return isReachableDirectory(context.getContentResolver(), tree, cancelled) ? tree : null;
        } catch (RuntimeException unavailable) { cancelled.throwIfCanceled(); }
        return null;
    }
    static boolean isReachableDirectory(android.content.ContentResolver resolver, Uri tree, CancellationSignal cancelled) {
        try {
            cancelled.throwIfCanceled();
            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (var cursor = resolver.query(document,
                    new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null, cancelled)) {
                return cursor != null && cursor.moveToFirst()
                        && DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(0));
            }
        } catch (RuntimeException unavailable) { cancelled.throwIfCanceled(); return false; }
    }
    static Sources resolve(Context context, CancellationSignal cancelled) throws IOException {
        Uri sd = reachableSdTree(context, cancelled);
        if (sd != null) return new Sources(sd, List.of());
        return new Sources(null, wifiProfiles(context, cancelled));
    }
    /** Registered Wi-Fi metadata only: never enumerates USB or probes an SD provider. */
    static List<Profile> wifiProfiles(Context context, CancellationSignal cancelled) throws IOException {
        cancelled.throwIfCanceled();
        List<Profile> profiles = new ArrayList<>();
        CameraCredentialStore store = new CameraCredentialStore(context);
        for (String name : store.names()) {
            cancelled.throwIfCanceled();
            String id = CameraCredentialStore.profileId(name);
            try (var registration = store.registration(id)) {
                if (registration != null && registration.hasWifi()) profiles.add(new Profile(id, name));
            }
        }
        return List.copyOf(profiles);
    }
}
