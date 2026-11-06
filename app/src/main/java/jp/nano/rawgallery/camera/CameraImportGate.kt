package jp.nano.rawgallery.camera

import java.util.concurrent.locks.ReentrantLock

/** History merges and USB scans may not run at the same time. */
object CameraImportGate {
    @JvmField val lock = ReentrantLock()
}
