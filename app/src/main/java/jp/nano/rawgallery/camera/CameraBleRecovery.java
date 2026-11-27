package jp.nano.rawgallery.camera;

/** Bounded policy for the one optional warm connection; no Android resources or credentials. */
final class CameraBleRecovery {
  enum Phase {
    NEW,
    WARM_CONNECT,
    WARM_CONNECTED,
    SETTLE,
    SCAN,
    NORMAL,
    DONE
  }

  Phase phase = Phase.NEW;
  private final long started;

  CameraBleRecovery(long started) {
    this.started = started;
  }

  boolean start(boolean asicOn) {
    if (phase != Phase.NEW) return false;
    phase = asicOn ? Phase.NORMAL : Phase.WARM_CONNECT;
    return true;
  }

  boolean connected() {
    if (phase != Phase.WARM_CONNECT) return false;
    phase = Phase.WARM_CONNECTED;
    return true;
  }

  boolean disconnected() {
    if (phase != Phase.WARM_CONNECTED) return false;
    phase = Phase.SETTLE;
    return true;
  }

  boolean settled() {
    if (phase != Phase.SETTLE) return false;
    phase = Phase.SCAN;
    return true;
  }

  boolean advertisement(boolean sameTarget, boolean asicOn) {
    if (phase != Phase.SCAN) return false;
    phase = sameTarget && asicOn ? Phase.NORMAL : Phase.DONE;
    return phase == Phase.NORMAL;
  }

  long remaining(long now, long stageMillis) {
    return Math.max(0, Math.min(stageMillis, 90000 - Math.max(0, now - started)));
  }

  void finish() {
    phase = Phase.DONE;
  }
}
