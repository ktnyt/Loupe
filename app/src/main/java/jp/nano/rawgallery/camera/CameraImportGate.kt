package jp.nano.rawgallery.camera

import java.util.concurrent.locks.ReentrantLock

/** Serializes admission to camera import and photo move. */
object CameraImportGate {
    @JvmField val lock = ReentrantLock()
}
