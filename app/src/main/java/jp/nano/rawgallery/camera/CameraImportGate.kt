package jp.nano.rawgallery.camera

import java.util.concurrent.locks.ReentrantLock

/** Serializes admission to camera import and photo move, including recovery after a process restart. */
object CameraImportGate {
    @JvmField val lock: ReentrantLock = object : ReentrantLock() {
        override fun tryLock(): Boolean {
            if (!super.tryLock()) return false
            if (holdCount > 1 || !SdRemountMountNotice.blocksImport()) return true
            super.unlock()
            return false
        }

        override fun isLocked(): Boolean = super.isLocked() || SdRemountMountNotice.blocksImport()
    }
}
