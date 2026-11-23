package jp.nano.rawgallery;

import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import jp.nano.rawgallery.camera.CameraCredentialStore;
import jp.nano.rawgallery.camera.CameraDocumentSource;
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
        SAME_CARD("取り込み元と保存先が同じカードです。取り込み元を選び直してください。"),
        READY("取り込み元のフォルダを確認しました。");

        final String message;
        SdState(String message) { this.message = message; }
    }

    record SdSource(SdState state, Uri tree, String destination) {
        Uri reachableTree() { return state == SdState.READY ? tree : null; }
    }

    static SdState registrationState(boolean registered, boolean disconnected, boolean sameCard, boolean granted) {
        if (!registered) return SdState.UNREGISTERED;
        if (disconnected) return SdState.DISCONNECTED;
        if (sameCard) return SdState.SAME_CARD;
        return granted ? SdState.CHECKING : SdState.NO_GRANT;
    }

    private static Uri registeredTree(Context context) {
        String saved = context.getSharedPreferences("camera_import", 0).getString("sd_tree_uri", null);
        return saved == null ? null : Uri.parse(saved);
    }

    private static SdState registeredState(Context context, Uri tree, String destination) {
        try {
            return registrationState(tree != null, MountedCameraCard.isUnmountedRemovableTree(context, tree),
                    MountedCameraCard.isSameMountedCard(context, tree, destination),
                    tree != null && context.getContentResolver().getPersistedUriPermissions().stream()
                            .anyMatch(grant -> grant.isReadPermission() && tree.equals(grant.getUri())));
        } catch (RuntimeException unavailable) {
            return SdState.FOLDER_UNAVAILABLE;
        }
    }

    static SdState readyState(SdSource inspected, Uri currentTree, String currentDestination, SdState current) {
        if (inspected.state() != SdState.READY) return inspected.state();
        if (currentTree == null) return SdState.UNREGISTERED;
        if (!currentTree.equals(inspected.tree()) || !currentDestination.equals(inspected.destination()))
            return SdState.FOLDER_UNAVAILABLE;
        return current == SdState.CHECKING ? SdState.READY : current;
    }

    static SdSource recheckSd(Context context, SdSource inspected) {
        if (inspected.state() != SdState.READY) return inspected;
        Uri currentTree = registeredTree(context);
        String destination = PhotoStorage.selected(context);
        SdState current = registeredState(context, currentTree, destination);
        SdState result = readyState(inspected, currentTree, destination, current);
        return result == inspected.state() ? inspected : new SdSource(result, currentTree, destination);
    }

    static SdSource inspectSd(Context context, CancellationSignal cancelled) {
        cancelled.throwIfCanceled();
        Uri tree = registeredTree(context);
        String destination = PhotoStorage.selected(context);
        SdState state = registeredState(context, tree, destination);
        if (state != SdState.CHECKING) return new SdSource(state, tree, destination);
        boolean reachable = CameraDocumentSource.isImportTree(tree, true)
                && isReachableDirectory(context.getContentResolver(), tree, cancelled);
        return recheckSd(context, new SdSource(reachable ? SdState.READY : SdState.FOLDER_UNAVAILABLE, tree, destination));
    }

    static Uri reachableSdTree(Context context, CancellationSignal cancelled) {
        return inspectSd(context, cancelled).reachableTree();
    }

    static boolean isReachableDirectory(android.content.ContentResolver resolver, Uri tree, CancellationSignal cancelled) {
        try {
            return CameraDocumentSource.isReachableDirectory(resolver, tree, cancelled::isCanceled);
        } catch (IOException | RuntimeException unavailable) {
            cancelled.throwIfCanceled();
            return false;
        }
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
