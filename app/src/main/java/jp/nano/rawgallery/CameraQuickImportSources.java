package jp.nano.rawgallery;

import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import jp.nano.rawgallery.camera.CameraCredentialStore;
import jp.nano.rawgallery.camera.CameraDocumentSource;
import jp.nano.rawgallery.camera.SdSourceRegistrations;

/** Local discovery only. No radio/network connection and no photo reads. Call off main. */
final class CameraQuickImportSources {
  static final class Profile {
    final String id, name;

    Profile(String id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  static final class Sources {
    final Uri sd;
    final List<Profile> profiles;
    final SdSource sdSource;

    Sources(Uri sd, List<Profile> profiles) {
      this(sd, profiles, null);
    }

    private Sources(Uri sd, List<Profile> profiles, SdSource sdSource) {
      this.sd = sd;
      this.profiles = List.copyOf(profiles);
      this.sdSource = sdSource;
    }

    static Sources fromSd(SdSource source, List<Profile> profiles) {
      return new Sources(source.reachableTree(), profiles, source);
    }
  }

  enum SdState {
    CHECKING("SDカードを確認しています…"),
    BUSY("確認処理が混み合っています。少し待ってからやり直してください。"),
    UNREGISTERED("取り込み元のDCIMフォルダを選んでください。"),
    NO_GRANT("SDカードの読み取り許可がありません。フォルダを選び直してください。"),
    DISCONNECTED("取り込み元のSDカードを接続してください。表示されないときは、カードかリーダーを挿し直してください。"),
    FOLDER_UNAVAILABLE("登録したフォルダを確認できません。接続を確認するか、フォルダを選び直してください。"),
    SAME_CARD("取り込み元と保存先が同じカードです。取り込み元を選び直してください。"),
    AMBIGUOUS("登録済みのSDカードが複数つながっています。"),
    READY("取り込み元のフォルダを確認しました。");

    final String message;

    SdState(String message) {
      this.message = message;
    }
  }

  record SdSource(SdState state, Uri tree, String destination, List<Uri> candidates) {
    SdSource(SdState state, Uri tree, String destination) {
      this(state, tree, destination, List.of());
    }

    Uri reachableTree() {
      return state == SdState.READY ? tree : null;
    }
  }

  static SdState registrationState(
      boolean registered, boolean disconnected, boolean sameCard, boolean granted) {
    if (!registered) return SdState.UNREGISTERED;
    if (disconnected) return SdState.DISCONNECTED;
    if (sameCard) return SdState.SAME_CARD;
    return granted ? SdState.CHECKING : SdState.NO_GRANT;
  }

  private static SdSource registeredSource(Context context) {
    String destination = PhotoStorage.selected(context);
    try {
      List<Uri> registered = SdSourceRegistrations.registrations(context);
      if (registered.isEmpty()) return new SdSource(SdState.UNREGISTERED, null, destination);
      var mounted = SdSourceRegistrations.mountedSources(context, registered, destination);
      List<Uri> candidates = mounted.trees();
      if (candidates.size() > 1)
        return new SdSource(SdState.AMBIGUOUS, null, destination, candidates);
      Uri unique = mounted.unambiguousTree();
      if (unique != null) return grantedSource(context, unique, destination);
      if (mounted.destinationTree() != null)
        return new SdSource(SdState.SAME_CARD, mounted.destinationTree(), destination);
      return new SdSource(SdState.DISCONNECTED, null, destination);
    } catch (RuntimeException unavailable) {
      return new SdSource(SdState.FOLDER_UNAVAILABLE, null, destination);
    }
  }

  static boolean matchesRegisteredSelection(Context context, Uri tree) {
    return SdSourceRegistrations.isUnambiguousSource(context, tree, PhotoStorage.selected(context))
        && SdSourceRegistrations.hasReadGrant(context, tree);
  }

  private static SdSource grantedSource(Context context, Uri tree, String destination) {
    SdState state =
        registrationState(true, false, false, SdSourceRegistrations.hasReadGrant(context, tree));
    return new SdSource(state, tree, destination);
  }

  static SdState readyState(
      SdSource inspected, Uri currentTree, String currentDestination, SdState current) {
    if (inspected.state() != SdState.READY) return inspected.state();
    if (currentTree == null) return SdState.UNREGISTERED;
    if (!currentTree.equals(inspected.tree())
        || !currentDestination.equals(inspected.destination())) return SdState.FOLDER_UNAVAILABLE;
    return current == SdState.CHECKING ? SdState.READY : current;
  }

  static SdSource recheckSd(Context context, SdSource inspected) {
    SdSource current = registeredSource(context);
    if (inspected.state() == SdState.AMBIGUOUS || current.state() == SdState.AMBIGUOUS)
      return current;
    if (inspected.state() != SdState.READY) return inspected;
    if (current.state() != SdState.CHECKING) return current;
    return readyState(inspected, current.tree(), current.destination(), current.state())
            == SdState.READY
        ? inspected
        : new SdSource(SdState.FOLDER_UNAVAILABLE, current.tree(), current.destination());
  }

  static SdSource inspectSd(Context context, CancellationSignal cancelled) {
    cancelled.throwIfCanceled();
    SdSource source = registeredSource(context);
    if (source.state() != SdState.CHECKING) return source;
    Uri tree = source.tree();
    boolean reachable =
        CameraDocumentSource.isImportTree(tree, true)
            && isReachableDirectory(context.getContentResolver(), tree, cancelled);
    SdSource result =
        new SdSource(
            reachable ? SdState.READY : SdState.FOLDER_UNAVAILABLE, tree, source.destination());
    return reachable ? recheckSd(context, result) : result;
  }

  static boolean isReachableDirectory(
      android.content.ContentResolver resolver, Uri tree, CancellationSignal cancelled) {
    try {
      return CameraDocumentSource.isReachableDirectory(resolver, tree, cancelled::isCanceled);
    } catch (IOException | RuntimeException unavailable) {
      cancelled.throwIfCanceled();
      return false;
    }
  }

  static Sources resolve(Context context, CancellationSignal cancelled) throws IOException {
    SdSource sd = inspectSd(context, cancelled);
    if (sd.state() == SdState.READY || sd.state() == SdState.AMBIGUOUS)
      return Sources.fromSd(sd, List.of());
    return Sources.fromSd(sd, wifiProfiles(context, cancelled));
  }

  /** Registered Wi-Fi metadata only: never enumerates USB or probes an SD provider. */
  static List<Profile> wifiProfiles(Context context, CancellationSignal cancelled)
      throws IOException {
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
