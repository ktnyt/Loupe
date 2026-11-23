package jp.nano.rawgallery;

import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import jp.nano.rawgallery.camera.CameraCredentialStore;
import jp.nano.rawgallery.camera.MountedCameraCard;
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
    enum SdState {
        CHECKING("SDカードを確認しています…"),
        UNREGISTERED("取り込み元のDCIMフォルダを選んでください。"),
        NO_GRANT("SDカードの読み取り許可がありません。フォルダを選び直してください。"),
        DISCONNECTED("登録したSDカードが接続されていません。取り込み元のフォルダを選び直してください。"),
        FOLDER_UNAVAILABLE("登録したフォルダを確認できません。接続を確認するか、フォルダを選び直してください。"),
        READY("取り込み元のフォルダを確認しました。");

        final String message;
        SdState(String message) { this.message = message; }
    }

    record SdSource(SdState state, Uri tree) {
        Uri reachableTree() { return state == SdState.READY ? tree : null; }
    }

    static SdState registrationState(boolean registered, boolean disconnected, boolean granted) {
        if (!registered) return SdState.UNREGISTERED;
        if (disconnected) return SdState.DISCONNECTED;
        return granted ? SdState.CHECKING : SdState.NO_GRANT;
    }

    static SdSource inspectSd(Context context, CancellationSignal cancelled) {
        cancelled.throwIfCanceled();
        String saved = context.getSharedPreferences("camera_import", 0).getString("sd_tree_uri", null);
        Uri tree = saved == null ? null : Uri.parse(saved);
        try {
            var resolver = context.getContentResolver();
            SdState state = registrationState(tree != null, MountedCameraCard.isUnmountedRemovableTree(context, tree),
                    tree != null && resolver.getPersistedUriPermissions().stream()
                            .anyMatch(grant -> grant.isReadPermission() && tree.equals(grant.getUri())));
            if (state != SdState.CHECKING) return new SdSource(state, tree);
            return new SdSource(isReachableDirectory(resolver, tree, cancelled)
                    ? SdState.READY : SdState.FOLDER_UNAVAILABLE, tree);
        } catch (RuntimeException unavailable) {
            cancelled.throwIfCanceled();
            return new SdSource(SdState.FOLDER_UNAVAILABLE, tree);
        }
    }

    static Uri reachableSdTree(Context context, CancellationSignal cancelled) {
        return inspectSd(context, cancelled).reachableTree();
    }

    static boolean isReachableDirectory(android.content.ContentResolver resolver, Uri tree, CancellationSignal cancelled) {
        try {
            cancelled.throwIfCanceled();
            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (var cursor = resolver.query(document,
                    new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null, cancelled)) {
                if (cursor == null || !cursor.moveToFirst()) return false;
                var extras = cursor.getExtras();
                return extras != null && !extras.getBoolean(DocumentsContract.EXTRA_LOADING)
                        && !extras.containsKey(DocumentsContract.EXTRA_ERROR)
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
