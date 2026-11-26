package jp.nano.rawgallery.camera

import java.util.WeakHashMap
import java.util.concurrent.locks.ReentrantLock

/** Tracks accepted operations separately from the thread-owned lock that serializes their I/O. */
object CameraImportGate {
    @JvmField
    val lock = ReentrantLock()

    private val monitor = Any()

    // The submitting Intent or executor owns each admission. If Android drops a pending start,
    // the process does not retain that start or its admission indefinitely.
    private val transfers = WeakHashMap<Admission, Unit>()
    private var preparation: Reservation? = null

    @JvmStatic
    fun tryAdmit(): Admission? =
        synchronized(monitor) {
            if (preparation == null) Admission().also { transfers[it] = Unit } else null
        }

    @JvmStatic
    fun tryReservePreparation(): Reservation? =
        synchronized(monitor) {
            if (preparation != null || transfers.isNotEmpty() || lock.isLocked) return@synchronized null
            Reservation().also { preparation = it }
        }

    @JvmStatic
    fun isPreparationReserved(): Boolean = synchronized(monitor) { preparation != null }

    class Admission internal constructor() : AutoCloseable {
        fun isOpen(): Boolean = synchronized(monitor) { transfers.containsKey(this) }

        fun handoff(): Admission? =
            synchronized(monitor) {
                if (transfers.remove(this) == null) return@synchronized null
                Admission().also { transfers[it] = Unit }
            }

        /** Keep admission while a nested I/O worker can outlive the task that started it. */
        internal fun retain(): Admission =
            synchronized(monitor) {
                check(transfers.containsKey(this))
                Admission().also { transfers[it] = Unit }
            }

        override fun close() {
            synchronized(monitor) { transfers.remove(this) }
        }
    }

    class Reservation internal constructor() : AutoCloseable {
        fun isOpen(): Boolean = synchronized(monitor) { preparation === this }

        /** The former UI owner can no longer close the reservation after a successful handoff. */
        fun handoffToWorker(): Reservation? =
            synchronized(monitor) {
                if (preparation !== this) return@synchronized null
                Reservation().also { preparation = it }
            }

        override fun close() {
            synchronized(monitor) {
                if (preparation === this) preparation = null
            }
        }
    }
}
