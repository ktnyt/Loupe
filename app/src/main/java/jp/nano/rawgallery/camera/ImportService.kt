package jp.nano.rawgallery.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import jp.nano.rawgallery.CameraImportActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import android.os.IBinder
import android.widget.Toast
import jp.nano.rawgallery.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** カメラ接続中の取り込みを担う前景サービス (仕様書 5 章) */
class ImportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancelled = AtomicBoolean(false)
    /** 実行中の取り込みがあるか。onStartCommand は main スレッド直列なので単純なフラグで足りる */
    private val running = AtomicBoolean(false)
    private var detachReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // logcat は 20〜30 分で流れるので、出先の失敗を後から追えるようファイルにも残す
        ImportLog.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.camera_import_notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val usbDevice = intent?.let(CameraUsb::deviceExtra)?.takeIf {
            CameraUsb.supported(it) && getSystemService(UsbManager::class.java).hasPermission(it)
        }
        if (usbDevice == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // 種別指定ボタンからの取り込みだけが extra を持つ。無指定はカメラ別の既定に従う
        val requestedKinds = intent.getStringExtra(EXTRA_KINDS)?.let { MediaTypes.kindsFromString(it) }
        // 自動起動と手動ボタンが重なると同じカメラを同時に開き、両方のセッションが壊れる
        // (E-P7 実測: deviceInfo が null になる)。実行中は新しい要求を受け付けない
        if (!running.compareAndSet(false, true)) {
            ImportLog.w(TAG, "import already running; ignoring new request")
            scope.launch(Dispatchers.Main) {
                Toast.makeText(this@ImportService, getString(R.string.camera_import_notif_busy), Toast.LENGTH_SHORT).show()
            }
            return START_NOT_STICKY
        }
        startForeground(NOTIF_PROGRESS, notification(getString(R.string.camera_import_notif_connecting), ongoing = true))
        watchDetach(usbDevice)
        val allowDeletion = intent.getBooleanExtra(EXTRA_ALLOW_DELETION, false)
        scope.launch {
            val lock = CameraImportGate.lock
            try {
                if (lock.tryLock()) {
                    try {
                        runImport(usbDevice, requestedKinds, allowDeletion)
                    } finally {
                        lock.unlock()
                    }
                } else {
                    ImportProgress.finish(getString(R.string.camera_import_notif_busy))
                }
            } catch (error: Exception) {
                ImportLog.w(TAG, "import failed", error)
                ImportProgress.finish(getString(R.string.camera_import_notif_open_failed))
            } finally {
                withContext(Dispatchers.Main) {
                    running.set(false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun runImport(usbDevice: UsbDevice, requestedKinds: Set<MediaTypes.Kind>?, allowDeletion: Boolean) {
        // 新しい取り込みは前回の中断状態を持ち越さない。
        // サービスは同一インスタンスが再利用されることがあり、残った true が即中断を招く
        cancelled.set(false)
        // 機種名が判明する前から画面に実行中カードを出す (通知だけだと止まって見える)
        ImportProgress.connecting(getString(R.string.camera_import_notif_connecting))
        val db = HistoryDb.get(this)
        // 接続直後に装置が応答しないのは両経路に共通する実測で、理由だけが違う。
        // MTP はシステム (設定アプリ) がセッションを先に張るため、マスストレージは
        // 装置側の準備が済んでいないため。以前は MTP だけ待っていたが、待たずに
        // 始めたマスストレージは init が空振りして 1 回目の試行を必ず落としていた
        // (2026-08-22 実測: G99M2 で 18.5 秒の空振り。31 秒のうち 21 秒がここで消えた)
        val isMtp = !UsbMassStorage.isMassStorage(usbDevice)
        try { Thread.sleep(INITIAL_SETTLE_MS) } catch (e: InterruptedException) { return }

        // 開く前に Device Reset を送る案は 2026-08-22 に実測して却下した。
        // open は速くなる (15.4 秒 → 1.1 秒) が、その後のスキャンが壊れる:
        // 155.9 秒かけて見切られた回と、579 件中 200 件しか読めずに ORF が
        // 1 件も返らなくなった回が出た。リセットなしの同日の回はスキャン 4.9 秒で
        // 574 件を完走している。open で稼ぐ十数秒はスキャンの損失に見合わない

        // カメラの無応答は「準備・復帰待ち」が実態で、応答が始まるまで数十秒かかることがある
        // (E-P7 実測)。短時間で諦めず、ケーブルが繋がっている限り淡々と試し続ける
        var outcome: Pair<Camera, MtpImporter.Result>? = null
        val failures = ArrayList<FailureKind>()
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        var attempt = 0
        var detached = false
        var device = usbDevice
        while (true) {
            if (ImportProgress.stopRequested) break
            if (cancelled.get()) {
                // 抜かれたら素直に終わる。挿し直しは新しい USB_DEVICE_ATTACHED として
                // 届くので、そちらに任せればよい。
                //
                // 以前はここで再接続を待って同じ取り込みを続けていたが、待っている間も
                // running が立ったままなので、肝心の挿し直しが「すでに実行中です」で
                // 弾かれていた (2026-08-27 実測)。さらに古いデバイスを掴んだまま最大
                // 180 秒粘るため、カメラ側には自分が開いたセッションが残り続け、
                // 次に開こうとした自分自身へ DeviceBusy を返させていた。
                // 挿し直すほど詰まる形になっていた
                detached = true
                break
            }
            attempt++
            // 応答待ちは最大 30 秒あるので、試行の開始時点から画面に経過を出す
            // (出さないと長い待ちがハングに見える)
            ImportProgress.status = getString(R.string.camera_import_notif_attempting, attempt)
            // 他のセッションを抱えたカメラを開くと GetDeviceInfo が 15〜21 秒かけて
            // 失敗する。空くまで待ってから開けば、その空振りを丸ごと省ける
            if (isMtp) waitUntilNotBusy(device, deadline)
            outcome = runOneAttempt(
                device, db,
                watchdogged = isMtp,
                requestedKinds = requestedKinds,
                allowDeletion = allowDeletion,
                // 序盤の試行が通らないときは、固まりの実績がある format 指定照会を
                // 省いた縮退スキャンに切り替えて、全滅よりは取りこぼしを選ぶ
                fullScan = attempt <= FULL_SCAN_ATTEMPTS,
                failures = failures,
            )
            val r = outcome?.second
            if (r != null && !r.error) break
            if (r?.aborted == true) break // ケーブル抜去。やり直しても無駄
            if (System.currentTimeMillis() > deadline) {
                ImportLog.w(TAG, "giving up after $attempt attempts")
                break
            }
            ImportLog.w(TAG, "attempt $attempt failed (result=$r), waiting and retrying")
            // しばらく直らない無応答は「外れ接続」で、実測上は挿し直しでしか直らない。
            // 挿し直すとこの取り込みは中止され、新しい接続として最初からやり直す
            val waiting =
                if (attempt >= REPLUG_HINT_AFTER_FAILURES) getString(R.string.camera_import_notif_waiting_replug, attempt)
                else getString(R.string.camera_import_notif_waiting_camera, attempt)
            ImportProgress.status = waiting
            notify(NOTIF_PROGRESS, notification(waiting, ongoing = true))
            // リセットで殴る対処は 2026-08-22 にすべて外した。PTP Device Reset は
            // 「失敗後に送ると次の open が 1.1 秒で通る」実測があったが、正常な端末では
            // そもそも open が 65ms で通っており、壊れた状態を糊塗していただけだった。
            // USB ポートリセット (非公開 API) は効果が未実測のまま、カーネルの USB
            // スタックに直接触っていた。実際 deviceInfo が null になり続けた不調は、
            // カメラの電源入れ直しでは戻らず端末の再起動で戻っており、壊れていたのは
            // プロセスより下の層だった。効くと分かっていないものを、その層へ
            // 送り続ける理由がない
            try { Thread.sleep(RETRY_DELAY_MS) } catch (e: InterruptedException) { break }
        }

        // 取り外し (5 秒以内に挿し直しなし) は失敗ではなく中止。休ませ案内などは出さない
        if (detached) {
            val n = outcome?.second?.imported ?: 0
            val msg =
                if (n > 0) getString(R.string.camera_import_notif_aborted, n)
                else getString(R.string.camera_import_notif_detached_cancel)
            ImportProgress.finish(msg)
            notify(NOTIF_DONE, notification(msg))
            return
        }

        val (camera, result) = outcome ?: run {
            // 全試行が失敗。失敗の内訳で復旧手順の案内を出し分ける。
            // 途中で応答が止まった形跡があれば、実測上それを直すのはカメラの電源入れ直し。
            // 一度も開けていなければ、まず USB モード未確定を疑う (仕様書 5.4)
            val msg =
                if (FailureKind.STALL in failures) getString(R.string.camera_import_notif_stalled_all)
                else getString(R.string.camera_import_notif_open_failed)
            ImportProgress.finish(msg)
            notify(NOTIF_DONE, notification(msg))
            return
        }

        val seconds = result.elapsedMs / 1000.0
        var msg = when {
            result.error -> getString(R.string.camera_import_notif_read_error, result.imported)
            result.aborted -> getString(R.string.camera_import_notif_aborted, result.imported)
            result.failed > 0 -> getString(R.string.camera_import_notif_done_partial, result.imported, result.failed)
            result.imported == 0 -> getString(R.string.camera_import_notif_done_nothing, result.skipped)
            else -> getString(R.string.camera_import_notif_done, result.imported, result.skipped, seconds)
        }
        if (result.deleted > 0) {
            msg += getString(R.string.camera_import_notif_deleted_suffix, result.deleted)
            // 成功応答の直後の再確認でカメラ側に残っていた分は正直に伝える
            if (result.deleteRemained > 0) {
                msg += getString(R.string.camera_import_notif_delete_remained_suffix, result.deleteRemained)
            }
        } else if (allowDeletion && camera.deleteAfterImport && isMtp && result.imported > 0) {
            // 設定は有効なのに 1 件も消せなかった。カメラ側が DeleteObject を拒む機種がある
            msg += getString(R.string.camera_import_notif_delete_failed_suffix)
        }
        if (result.resurrected > 0) {
            // 削除したはずのファイルがカメラ側で復活している (E-P7 実測)。
            // 取り込み済みなので再取得はしないが、「差分なし」の混乱を防ぐため明示する
            msg += getString(R.string.camera_import_notif_resurrected_suffix, result.resurrected)
        }
        if (result.degraded) {
            msg += getString(R.string.camera_import_notif_degraded_suffix)
        }
        ImportLog.i(TAG, "result=$result")
        ImportProgress.finish(msg)
        notify(NOTIF_DONE, notification(msg))
        scope.launch(Dispatchers.Main) {
            Toast.makeText(this@ImportService, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** 全滅時の案内文の出し分け用。試行が何で失敗したかの分類 */
    private enum class FailureKind { OPEN_FAIL, STALL, CRASH }

    /** 1 試行分の共有状態。外側の監視がここを覗いて固まりを検知する */
    private class Attempt {
        val lastBeat = AtomicLong(System.currentTimeMillis())
        /** 最初の進捗通知 (スキャン完了) で true。以降は転送で、長くかかるのが正常 */
        @Volatile var setupDone = false
        /** 見切られた試行。復帰しても進捗や結果に触らせない */
        @Volatile var invalidated = false
        @Volatile var mtp: MtpDevice? = null
        /** MtpDevice.open (native) が固まったときに閉じるための、開いた直後の接続 */
        @Volatile var conn: android.hardware.usb.UsbDeviceConnection? = null
        fun beat() = lastBeat.set(System.currentTimeMillis())
    }

    /**
     * attemptImport を使い捨てスレッドで動かし、接続〜スキャンの間に心拍が
     * [STALL_TIMEOUT_MS] 止まったら見切って null を返す。MTP の native 呼び出しは
     * タイムアウトも中断も持たないため、固まったスレッドは無効化して放置する
     * (E-P7 実測: スキャン中の getObjectInfo や再接続の open が無応答のまま戻らない)
     */
    private fun runOneAttempt(
        usbDevice: UsbDevice,
        db: HistoryDb,
        watchdogged: Boolean,
        requestedKinds: Set<MediaTypes.Kind>?,
        allowDeletion: Boolean,
        fullScan: Boolean,
        failures: MutableList<FailureKind>,
    ): Pair<Camera, MtpImporter.Result>? {
        val att = Attempt()
        val task = FutureTask { attemptImport(usbDevice, db, att, requestedKinds, allowDeletion, fullScan) }
        thread(name = "import-attempt", isDaemon = true) { task.run() }
        while (true) {
            try {
                val r = task.get(500, TimeUnit.MILLISECONDS)
                if (r == null) {
                    failures += FailureKind.OPEN_FAIL
                    // カメラ側の言い分を残す。「忙しい」と答えているのか、そもそも
                    // 応答がないのかで打つ手が変わる。MTP セッションは attemptImport の
                    // finally で閉じ済みなので、ここから control 転送で聞いてよい
                    if (watchdogged) {
                        ImportLog.w(TAG, "PTP status after failed attempt: ${MtpImporter.deviceStatus(this, usbDevice)}")
                    }
                }
                return r
            } catch (e: TimeoutException) {
                // 停止要求は見切り時間を待たず即応する (見切りが 30 秒あるため)
                if (ImportProgress.stopRequested && !att.setupDone) {
                    att.invalidated = true
                    runCatching { att.mtp?.close() ?: att.conn?.close() }
                    return null
                }
                val stale = System.currentTimeMillis() - att.lastBeat.get()
                if (watchdogged && !att.setupDone && stale > STALL_TIMEOUT_MS) {
                    att.invalidated = true
                    ImportLog.w(TAG, "setup stalled for ${stale}ms; abandoning this attempt")
                    // 固まった native 呼び出しは、接続を閉じれば失敗して戻る。
                    // open 中の固まりも openDevice 済みの接続を閉じ、claim を残さない
                    runCatching { att.mtp?.close() ?: att.conn?.close() }
                    failures += FailureKind.STALL
                    return null
                }
                // 転送開始後の固まり (importFile 等の native ハング) も見切る。
                // 放置すると取り込みが無言のまま永久に終わらない (朝の実測)
                if (watchdogged && att.setupDone && stale > TRANSFER_STALL_TIMEOUT_MS) {
                    att.invalidated = true
                    ImportLog.w(TAG, "transfer stalled for ${stale}ms; abandoning this attempt")
                    runCatching { att.mtp?.close() }
                    failures += FailureKind.STALL
                    return null
                }
            } catch (e: Exception) {
                ImportLog.w(TAG, "attempt crashed", e)
                failures += FailureKind.CRASH
                return null
            }
        }
    }

    /** 1 回分のスキャンと取り込み。カメラを開けなかったときは null */
    private fun attemptImport(
        usbDevice: UsbDevice,
        db: HistoryDb,
        att: Attempt,
        requestedKinds: Set<MediaTypes.Kind>?,
        allowDeletion: Boolean,
        fullScan: Boolean,
    ): Pair<Camera, MtpImporter.Result>? {
        val isCancelled = { cancelled.get() || ImportProgress.stopRequested || att.invalidated }
        if (UsbMassStorage.isMassStorage(usbDevice)) {
            val camera = findOrRegister(db, MassStorageImporter.identity(usbDevice), massStorage = true)
            ImportProgress.begin(camera.model)
            return camera to MassStorageImporter.importAll(
                this, usbDevice, camera, db,
                kinds = requestedKinds ?: camera.defaultKinds,
                onProgress = progressReporter(camera, att),
                isCancelled = isCancelled,
            )
        }
        val mtp = MtpImporter.open(this, usbDevice) { conn ->
            att.conn = conn
            att.beat() // openDevice が通ったら、native open にも見張りの時間を丸ごと与える
        } ?: return null
        att.mtp = mtp
        att.beat()
        try {
            // 見切られた後に遅れて戻ってきた試行は、これ以上カメラに触らず退場する
            if (att.invalidated) return null
            // GetDeviceInfo を送らずに済ませる。この照会だけが 15〜21 秒かけて失敗し、
            // 抱えたまま返らないせいで、以降 GetDeviceStatus が DeviceBusy を返す状態に
            // なる (2026-08-27 実測)。DeviceBusy は結果であって原因ではなかった。
            // カメラ自体は正常で、独自実装の別アプリからは写真の一覧まで見えている。
            //
            // 機種名とシリアルは USB 記述子にも同じ値が入っている
            // (E-P7 の実測で履歴の登録と一致)。
            // 登録済みのカメラならそれで引けるので、照会そのものを飛ばす。
            // 引けないときだけ従来どおり deviceInfo を読む: USB 側の値で新規登録すると
            // 取り違えたときに履歴が丸ごと無効になり、全件が別名で再取り込みされる
            val camera = knownByUsb(db, usbDevice) ?: run {
                ImportLog.i(TAG, "unknown by USB descriptors; asking the camera")
                val info = mtp.deviceInfo
                if (info == null) {
                    ImportLog.w(TAG, "opened but deviceInfo is null")
                    return null
                }
                ImportLog.i(TAG, "opened MTP camera")
                findOrRegister(
                    db, MtpImporter.CameraIdentity(info.model ?: "Unknown", info.serialNumber ?: ""),
                    massStorage = false,
                )
            }
            att.beat()
            ImportProgress.begin(camera.model)
            return camera to MtpImporter.importAll(
                this, mtp, camera, db,
                kinds = requestedKinds ?: camera.defaultKinds,
                deleteAfterImport = camera.deleteAfterImport && allowDeletion,
                onProgress = progressReporter(camera, att),
                isCancelled = isCancelled,
                onScanAlive = { att.beat() },
                fullScan = fullScan,
            )
        } finally {
            runCatching { mtp.close() }
        }
    }

    /**
     * USB 記述子の識別子で登録済みのカメラを引く。MTP の GetDeviceInfo を送らずに
     * 済ませるための道。実測では product / serialNumber とも MTP 側と同じ値が入る。
     * 値が欠けているときや履歴に無いときは null を返し、呼び出し側に照会させる
     */
    private fun knownByUsb(db: HistoryDb, device: UsbDevice): Camera? {
        val model = device.productName ?: return null
        val serial = device.serialNumber ?: return null
        if (model.isEmpty() || serial.isEmpty()) return null
        val found = db.findCamera(model, serial) ?: return null
        ImportLog.i(TAG, "matched by USB descriptors")
        return if (found.massStorage) found.copy(massStorage = false).also { db.saveCamera(it) } else found
    }

    private fun findOrRegister(
        db: HistoryDb,
        identity: MtpImporter.CameraIdentity,
        massStorage: Boolean,
    ): Camera {
        val found = db.findCamera(identity.model, identity.serial)
        if (found != null) {
            // 接続方式は USB モード切替で変わり得る。削除設定の可否表示のため最新を覚える
            if (found.massStorage == massStorage) return found
            return found.copy(massStorage = massStorage).also { db.saveCamera(it) }
        }
        // 初回接続ダイアログを経ずに取り込みが始まったカメラ (手動ボタン経由など) も
        // 一覧に載るよう、ここで登録する
        return Camera(
            identity.model, identity.serial, sanitizeFolder(identity.model),
            defaultKinds = MediaTypes.kindsFromString(getSharedPreferences("camera_import", MODE_PRIVATE)
                .getString("initial_kinds", null)),
            massStorage = massStorage
        ).also { db.saveCamera(it) }
    }

    private fun progressReporter(camera: Camera, att: Attempt): (MtpImporter.Progress) -> Unit = { p ->
        att.setupDone = true
        att.beat() // 1 ファイル処理ごとの心拍。転送中の固まり検知に使う
        if (!att.invalidated) {
            ImportProgress.update(p.done, p.total)
            notify(
                NOTIF_PROGRESS,
                notification(
                    getString(R.string.camera_import_notif_progress, camera.model, p.done, p.total),
                    ongoing = true,
                    progress = p,
                ),
            )
        }
    }

    /**
     * カメラが他のセッションを抱えている間だけ待つ。
     *
     * 2026-08-27 の実測: 設定アプリ (com.android.settings) が先に MTP を開いたまま
     * 手放さないとき、こちらの `open` は通るのに GetDeviceInfo だけが 15〜21 秒かけて
     * 失敗する。その最中に Get Device Status を読むと 0x2019 (DeviceBusy) が返っており、
     * カメラは「応答しない」のではなく「他が開いている」と明示していた。
     *
     * 状態照会は control 転送なので MTP セッションを張らずに読め、カメラの状態も
     * 変えない (リセットを送る案が却下されたのとはそこが違う)。
     * 読めなかったときや待ちきれなかったときは、そのまま試行に進んで従来どおり倒れる
     */
    private fun waitUntilNotBusy(device: UsbDevice, deadline: Long) {
        val limit = minOf(deadline, System.currentTimeMillis() + BUSY_WAIT_MS)
        var announced = false
        while (System.currentTimeMillis() < limit) {
            if (ImportProgress.stopRequested || cancelled.get()) return
            val status = MtpImporter.deviceStatus(this, device)
            if (!status.contains("DeviceBusy")) {
                if (announced) ImportLog.i(TAG, "camera is free now: $status")
                return
            }
            if (!announced) {
                ImportLog.w(TAG, "camera reports DeviceBusy (another session is open); waiting")
                ImportProgress.status = getString(R.string.camera_import_notif_waiting_busy)
                announced = true
            }
            try { Thread.sleep(BUSY_POLL_MS) } catch (e: InterruptedException) { return }
        }
        if (announced) ImportLog.w(TAG, "still busy after ${BUSY_WAIT_MS}ms; opening anyway")
    }

    /** ケーブル抜去でスキャン・転送を中断する (仕様書 5.4) */
    private fun watchDetach(target: UsbDevice) {
        // 再利用インスタンスで受信機が積み重ならないよう、前回分を解除してから登録する
        detachReceiver?.let { runCatching { unregisterReceiver(it) } }
        detachReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = CameraUsb.deviceExtra(intent)
                if (device?.deviceId == target.deviceId) {
                    cancelled.set(true)
                }
            }
        }
        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
    }

    override fun onDestroy() {
        detachReceiver?.let { unregisterReceiver(it) }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(
        text: String,
        ongoing: Boolean = false,
        progress: MtpImporter.Progress? = null,
    ): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_camera_import_notification)
            .setContentTitle(getString(R.string.camera_import_title))
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, CameraImportActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(ongoing)
            .apply { progress?.let { setProgress(it.total, it.done, it.total == 0) } }
            .build()

    private fun notify(id: Int, n: Notification) {
        try {
            getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (e: SecurityException) {
        }
    }

    companion object {
        private const val TAG = "ImportService"
        private const val CHANNEL = "import"
        private const val EXTRA_KINDS = "com.kotone.camimport.KINDS"
        private const val NOTIF_PROGRESS = 1
        private const val NOTIF_DONE = 2
        /**
         * 開き始める前の待ち。MTP は設定アプリが MTP セッションを先に張る間 (実測 1〜6 秒)、
         * マスストレージは装置の準備が済むまでの間、どちらも応答が返らない
         */
        private const val INITIAL_SETTLE_MS = 3_000L
        /** ケーブルが繋がっている限り粘る上限。カメラの復帰は数十秒かかることがある */
        private const val MAX_WAIT_MS = 180_000L
        /**
         * 他のセッションが空くのを待つ上限。開いたまま失敗する場合でも 15〜21 秒で
         * 戻ってくるので、それより長く待つ意味はない
         */
        private const val BUSY_WAIT_MS = 20_000L
        /** 空き待ちの照会間隔。control 転送 1 往復なのでカメラへの負荷は軽い */
        private const val BUSY_POLL_MS = 1_000L
        /** この回数失敗したら、案内をケーブルの挿し直し促しに切り替える */
        private const val REPLUG_HINT_AFTER_FAILURES = 4
        /** この回数までは format 重ね照会つきの完全スキャン。以降は縮退スキャンで粘る */
        private const val FULL_SCAN_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 3_000L
        // 接続〜スキャンの心拍がこれだけ止まったら試行を見切って仕切り直す。
        // 正常時は open が 1 秒未満だが、不調時のカメラは応答に数秒〜十数秒かかることがある
        // (実測: 不調時のシステム照会は 6.3 秒かかって完了)。5 秒で見切ると「遅い」を
        // 「死んだ」と誤変換して回復の芽を摘むため、長めに待つ。停止ボタンは見張り側の
        // stopRequested 検査で即応する
        private const val STALL_TIMEOUT_MS = 30_000L
        // 転送開始後の心拍の上限。1 ファイルごとに心拍が打たれるので、巨大な動画 1 本分
        // より長く取る。これを超えたら native 呼び出しが固まったとみなして仕切り直す
        private const val TRANSFER_STALL_TIMEOUT_MS = 300_000L

        fun sanitizeFolder(name: String): String =
            name.trim().replace(Regex("""[/\\:*?"<>|]"""), "_").takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "Camera"

        /** [kinds] が null なら、そのカメラの既定の種別設定に従う */
        private const val EXTRA_ALLOW_DELETION = "allow_camera_deletion"

        @JvmStatic @JvmOverloads
        fun start(context: Context, usbDevice: UsbDevice, kinds: Set<MediaTypes.Kind>? = null, allowDeletion: Boolean = false) {
            val intent = Intent(context, ImportService::class.java)
                .putExtra(UsbManager.EXTRA_DEVICE, usbDevice)
                .putExtra(EXTRA_ALLOW_DELETION, allowDeletion)
            kinds?.let { intent.putExtra(EXTRA_KINDS, MediaTypes.kindsToString(it)) }
            context.startForegroundService(intent)
        }
    }
}
