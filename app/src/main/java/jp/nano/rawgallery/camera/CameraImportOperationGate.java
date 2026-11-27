package jp.nano.rawgallery.camera;

import java.util.concurrent.locks.Lock;

/** Runs progress and import work only while this operation owns the shared USB/camera gate. */
final class CameraImportOperationGate {
  private CameraImportOperationGate() {}

  static boolean runIfAvailable(Lock lock, Runnable operation) {
    if (!lock.tryLock()) return false;
    try {
      operation.run();
      return true;
    } finally {
      lock.unlock();
    }
  }
}
