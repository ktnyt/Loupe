package jp.nano.rawgallery;

/** Main-thread request coalescing: changes during a scan require another scan. */
final class IndexRefreshRequests {
  static final int NONE = 0, NORMAL = 1, FORCE = 2;
  private boolean running;
  private int pending;

  boolean start(boolean force) {
    if (running) {
      pending = Math.max(pending, force ? FORCE : NORMAL);
      return false;
    }
    running = true;
    return true;
  }

  int finish() {
    running = false;
    int next = pending;
    pending = NONE;
    return next;
  }
}
