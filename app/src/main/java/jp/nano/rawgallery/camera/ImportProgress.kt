package jp.nano.rawgallery.camera


/** 取り込みの進捗をサービスから UI へ共有するためのシングルトン */
object ImportProgress {
    enum class PhotoStage { LISTING, HISTORY, CONTENT, SAVING, COMPLETE, INDEX_PENDING, CANCELLED, FAILED, NO_CANDIDATES, ENDED }
    /** Unknown counts/bytes are -1; total is the current stage's work, never planned saves. */
    data class PhotoState(
        val stage: PhotoStage, val processed: Int = 0, val total: Int = -1,
        val known: Int = -1, val saved: Int = -1, val contentMatches: Int = -1,
        val sourceBytes: Long = -1, val destinationBytes: Long = -1, val indexPending: Int = 0,
        val historyReceipts: Boolean = false
    )
    @Volatile var photo: PhotoState? = null
        private set

    @Synchronized fun photoListing(scanned: Int, total: Int) {
        photo = (photo ?: PhotoState(PhotoStage.LISTING)).copy(stage = PhotoStage.LISTING, processed = scanned, total = total)
        changed()
    }
    @JvmOverloads
    @Synchronized fun photoHistory(checked: Int, total: Int, known: Int, historyReceipts: Boolean = false) {
        photo = (photo ?: PhotoState(PhotoStage.HISTORY)).copy(stage = PhotoStage.HISTORY, processed = checked,
            total = total, known = known, historyReceipts = historyReceipts)
        changed()
    }
    @JvmOverloads
    @Synchronized fun photoContent(processed: Int, candidates: Int, saved: Int, contentMatches: Int, sourceBytes: Long, destinationBytes: Long, indexPending: Int = 0) {
        photo = (photo ?: PhotoState(PhotoStage.CONTENT)).copy(stage = PhotoStage.CONTENT,
            processed = processed, total = candidates, saved = saved, contentMatches = contentMatches,
            sourceBytes = sourceBytes, destinationBytes = destinationBytes, indexPending = indexPending)
        changed()
    }
    @Synchronized fun photoSaving(saved: Int) {
        photo = (photo ?: PhotoState(PhotoStage.SAVING)).copy(stage = PhotoStage.SAVING, saved = saved)
        changed()
    }
    @Synchronized fun photoTerminal(outcome: String, message: String) {
        val stage = when (outcome) {
            "complete" -> PhotoStage.COMPLETE
            "index_pending" -> PhotoStage.INDEX_PENDING
            "cancelled" -> PhotoStage.CANCELLED
            "failed" -> PhotoStage.FAILED
            "no_candidates" -> PhotoStage.NO_CANDIDATES
            else -> PhotoStage.ENDED
        }
        photo = (photo ?: PhotoState(stage)).copy(stage = stage)
        lastMessage = message
        active = false
        status = null
        waiting = false
        changed()
    }

    data class Completion(val requestId: String, val succeeded: Boolean)
    @Volatile var completion: Completion? = null
        private set

    fun completeRequest(requestId: String?, succeeded: Boolean) {
        if (requestId != null) completion = Completion(requestId, succeeded)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Runnable>()

    fun addListener(listener: Runnable) { listeners.addIfAbsent(listener) }
    fun removeListener(listener: Runnable) { listeners.remove(listener) }
    private fun changed() { listeners.forEach(Runnable::run) }

    @Volatile var active = false
    @Volatile var model = ""
    @Volatile var done = 0
    @Volatile var total = 0
    @Volatile var lastMessage: String? = null
    /** Explicit connection/open/retry wait state until begin() confirms scanning; independent of totals. */
    @Volatile var waiting = false
        private set

    /** スキャン前 (接続・再試行) の状態表示。begin で消える */
    @Volatile var status: String? = null

    /** 取り込みの中止要求。サービス側が isCancelled でここを見る */
    @Volatile
    var stopRequested: Boolean = false
        private set

    @Synchronized fun requestStop() {
        stopRequested = true
        changed()
    }

    /**
     * 接続開始。カメラ名が分かる前から進捗カードを出し、
     * 「動いていないように見えてもう一度押す」を防ぐ
     */
    fun connecting(message: String) {
        photo = null
        model = ""
        done = 0
        total = 0
        lastMessage = null
        status = message
        waiting = true
        stopRequested = false
        active = true
        changed()
    }

    /** Start a queued explicit connection without clearing a concurrent stop request. */
    fun handoffConnection(message: String) {
        photo = null
        model = ""
        done = 0
        total = 0
        lastMessage = null
        status = message
        waiting = true
        active = true
        changed()
    }

    fun begin(model: String) {
        photo = null
        this.model = model
        done = 0
        total = 0
        lastMessage = null
        status = null
        waiting = false
        // stopRequested はここでは触らない。接続中に押された停止を消さないため
        active = true
        changed()
    }

    /** Retry owns the same operation and counters; never undo a concurrent user stop. */
    @Synchronized fun waiting(message: String) {
        if (stopRequested || photo?.stage == PhotoStage.CANCELLED ||
            (!active && photo?.stage == PhotoStage.COMPLETE)) return
        status = message
        waiting = true
        active = true
        changed()
    }

    /** An open attempt has the same cancellable waiting presentation as its retry delay. */
    fun attempting(message: String) = waiting(message)

    fun update(done: Int, total: Int) {
        this.done = done
        this.total = total
        changed()
    }

    @Synchronized fun finish(message: String) {
        photo?.let {
            if (it.stage !in setOf(PhotoStage.COMPLETE, PhotoStage.INDEX_PENDING, PhotoStage.CANCELLED, PhotoStage.FAILED, PhotoStage.NO_CANDIDATES, PhotoStage.ENDED))
                photo = it.copy(stage = if (stopRequested) PhotoStage.CANCELLED else PhotoStage.ENDED)
        }
        active = false
        status = null
        waiting = false
        lastMessage = message
        changed()
    }
}
