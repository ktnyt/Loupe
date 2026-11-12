package jp.nano.rawgallery.camera


/** 取り込みの進捗をサービスから UI へ共有するためのシングルトン */
object ImportProgress {
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

    /** スキャン前 (接続・再試行) の状態表示。begin で消える */
    @Volatile var status: String? = null

    /** 取り込みの中止要求。サービス側が isCancelled でここを見る */
    @Volatile
    var stopRequested: Boolean = false
        private set

    fun requestStop() {
        stopRequested = true
    }

    /**
     * 接続開始。カメラ名が分かる前から進捗カードを出し、
     * 「動いていないように見えてもう一度押す」を防ぐ
     */
    fun connecting(message: String) {
        model = ""
        done = 0
        total = 0
        lastMessage = null
        status = message
        stopRequested = false
        active = true
        changed()
    }

    fun begin(model: String) {
        this.model = model
        done = 0
        total = 0
        lastMessage = null
        status = null
        // stopRequested はここでは触らない。接続中に押された停止を消さないため
        active = true
        changed()
    }

    fun update(done: Int, total: Int) {
        this.done = done
        this.total = total
        changed()
    }

    fun finish(message: String) {
        active = false
        status = null
        lastMessage = message
        changed()
    }
}
