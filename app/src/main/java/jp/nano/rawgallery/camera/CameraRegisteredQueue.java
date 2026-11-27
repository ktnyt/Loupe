package jp.nano.rawgallery.camera;

import java.util.Objects;

/**
 * One explicit request behind an active import. Caller serializes access with the service monitor.
 */
final class CameraRegisteredQueue {
  static final class Request {
    final String profileId;
    final boolean verify;

    Request(String profileId, boolean verify) {
      this.profileId = profileId;
      this.verify = verify;
    }
  }

  private String activeProfileId;
  private Request pending;

  void started(String profileId) {
    activeProfileId = profileId;
  }

  /**
   * Last requested profile wins; tapping the currently active registered camera does not duplicate
   * it.
   */
  void offer(String profileId, boolean verify) {
    if (profileId == null || Objects.equals(profileId, activeProfileId)) return;
    pending = new Request(profileId, verify);
  }

  Request finished(boolean cancelledOrDestroyed) {
    Request next = cancelledOrDestroyed ? null : pending;
    clear();
    return next;
  }

  void clear() {
    activeProfileId = null;
    pending = null;
  }
}
