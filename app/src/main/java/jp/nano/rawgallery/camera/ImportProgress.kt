package jp.nano.rawgallery.camera


/** 取り込みの進捗をサービスから UI へ共有するためのシングルトン */
object ImportProgress {
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
    }

    fun begin(model: String) {
        this.model = model
        done = 0
        total = 0
        lastMessage = null
        status = null
        // stopRequested はここでは触らない。接続中に押された停止を消さないため
        active = true
    }

    fun update(done: Int, total: Int) {
        this.done = done
        this.total = total
    }

    fun finish(message: String) {
        active = false
        status = null
        lastMessage = message
    }
}
