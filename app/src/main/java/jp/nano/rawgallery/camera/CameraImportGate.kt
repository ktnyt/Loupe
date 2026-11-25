package jp.nano.rawgallery.camera

import android.content.Context
import java.util.concurrent.locks.ReentrantLock

/** Serializes admission to camera import and photo move, including recovery after a process restart. */
object CameraImportGate {
    @Volatile private var app: Context? = null

    @JvmStatic fun initialize(context: Context) {
        app = context.applicationContext
    }

    private fun recoveringUsb(): Boolean = app?.let(DirectUsbHandoff::blocksImport) == true

    @JvmField val lock: ReentrantLock = object : ReentrantLock() {
        override fun tryLock(): Boolean {
            if (!super.tryLock()) return false
            if (holdCount > 1 || (!SdRemountMountNotice.blocksImport() && !recoveringUsb())) return true
            super.unlock()
            return false
        }

        override fun isLocked(): Boolean = super.isLocked() || SdRemountMountNotice.blocksImport() || recoveringUsb()
    }
}
