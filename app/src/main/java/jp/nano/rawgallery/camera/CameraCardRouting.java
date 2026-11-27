package jp.nano.rawgallery.camera;

/** Routing policy when a mounted volume could share a USB mass-storage interface. */
public final class CameraCardRouting {
  public enum Route {
    DIRECT,
    DIRECT_WITHOUT_DETACH,
    MOUNTED_SOURCE
  }

  public enum Attach {
    OPEN_SCREEN,
    CONFIRM_MOUNTED,
    START_DIRECT
  }

  enum Volumes {
    DISTINCT,
    TARGET_CHANGED,
    SOURCE_UNAVAILABLE,
    SAME_CARD
  }

  private CameraCardRouting() {}

  public static Route route(boolean massStorage, boolean knownCamera, boolean externalTarget) {
    if (!massStorage || !externalTarget) return Route.DIRECT;
    return knownCamera ? Route.DIRECT_WITHOUT_DETACH : Route.MOUNTED_SOURCE;
  }

  public static Attach onAttach(Route route, boolean autoImport, boolean mountedWatcherActive) {
    if (!autoImport) return Attach.OPEN_SCREEN;
    if (route != Route.MOUNTED_SOURCE) return Attach.START_DIRECT;
    return mountedWatcherActive ? Attach.OPEN_SCREEN : Attach.CONFIRM_MOUNTED;
  }

  static Volumes compareVolumes(String expectedTarget, String selectedTarget, String sourceVolume) {
    if (expectedTarget == null || !expectedTarget.equals(selectedTarget))
      return Volumes.TARGET_CHANGED;
    if (sourceVolume == null) return Volumes.SOURCE_UNAVAILABLE;
    return expectedTarget.equalsIgnoreCase(sourceVolume) ? Volumes.SAME_CARD : Volumes.DISTINCT;
  }
}
