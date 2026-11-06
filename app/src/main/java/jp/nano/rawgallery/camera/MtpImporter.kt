package jp.nano.rawgallery.camera

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo
import android.net.Uri
import java.io.File

/** MTP セッションを開き、写真・RAW・動画の差分取り込みを行う (仕様書 5 章の手順 3〜6) */
object MtpImporter {

    private const val TAG = "MtpImporter"

    /** スキャンで照会する format code。0 (全件) に加え、全件に含めてこない機種がある枠を明示する */
    private val SCAN_FORMATS = intArrayOf(
        0, // 全件
        MtpConstants.FORMAT_UNDEFINED, // 0x3000: ORF/RW2 など RAW はここに入る機種が多い
        0x3811, // DNG
        MtpConstants.FORMAT_TIFF, // 0x380D: RAW を TIFF 扱いで見せる機種向け
    )

    data class CameraIdentity(val model: String, val serial: String)

    data class Progress(val total: Int, val done: Int)

    data class Result(
        val imported: Int,
        val skipped: Int,
        val failed: Int,
        val elapsedMs: Long,
        val aborted: Boolean,
        /** 走査・接続そのものが失敗したとき true。「新しい写真なし」と区別する */
        val error: Boolean = false,
        /** 取り込み後にカメラ側から消せた件数 (MTP のみ) */
        val deleted: Int = 0,
        /** RAW を含む照会を省いた縮退スキャンで走ったとき true。取りこぼしの可能性の案内に使う */
        val degraded: Boolean = false,
        /** 削除成功の応答直後の再確認でカメラ側に残っていた件数 */
        val deleteRemained: Int = 0,
        /** 過去に削除したはずのファイルが今回のスキャンで見つかった件数 (E-P7 実測: 復活する) */
        val resurrected: Int = 0,
    )

    /**
     * 機種名とシリアルを読む (初回接続判定用)。
     * マスストレージ機は MTP を開けないので USB 属性から作る
     */
    fun readIdentity(
        context: Context,
        usbDevice: UsbDevice,
        /** 開いた接続を呼び出し側へ渡す。期限切れの後始末 (close) 用 */
        onConnection: (android.hardware.usb.UsbDeviceConnection) -> Unit = {},
    ): CameraIdentity? {
        if (UsbMassStorage.isMassStorage(usbDevice)) {
            return MassStorageImporter.identity(usbDevice)
        }
        val mtp = open(context, usbDevice, onConnection) ?: return null
        try {
            val info = mtp.deviceInfo ?: return null
            return CameraIdentity(info.model ?: "Unknown", info.serialNumber ?: "")
        } finally {
            mtp.close()
        }
    }

    fun open(
        context: Context,
        usbDevice: UsbDevice,
        /**
         * openDevice 直後に接続を渡す。MtpDevice.open (native) が無応答のまま戻らないとき、
         * 見張り側がこの接続を閉じれば呼び出しが失敗して戻り、claim も解放される。
         * 渡さないと固まったスレッドが interface を掴んだまま残る
         */
        onConnection: (android.hardware.usb.UsbDeviceConnection) -> Unit = {},
    ): MtpDevice? {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!manager.hasPermission(usbDevice)) {
            ImportLog.w(TAG, "no USB permission for ${usbDevice.deviceName}")
            return null
        }
        logInterfaces(usbDevice)
        // Storage モードだとシステムの MTP ホストがデバイスを先に掴むことがある。
        // openDevice が null を返す間は少し待って数回試す
        var conn: android.hardware.usb.UsbDeviceConnection? = null
        for (attempt in 1..5) {
            conn = manager.openDevice(usbDevice)
            if (conn != null) break
            ImportLog.w(TAG, "openDevice returned null (attempt $attempt)")
            try { Thread.sleep(400) } catch (e: InterruptedException) { break }
        }
        if (conn == null) {
            ImportLog.w(TAG, "openDevice failed after retries")
            return null
        }
        onConnection(conn)
        val mtp = MtpDevice(usbDevice)
        if (!mtp.open(conn)) {
            ImportLog.w(TAG, "MtpDevice.open failed (permission ok, connection ok)")
            conn.close()
            return null
        }
        // 注意: ここで deviceInfo を読まない。見切られた試行のスレッドがここへ遅れて
        // 戻ってきたとき、無応答のカメラへ余計な照会を重ねることになる
        return mtp
    }

    /**
     * PTP/MTP class の Get Device Status (bRequest 0x67) を読んで、カメラ側の言い分を返す。
     *
     * 失敗が `deviceInfo is null` としか記録されないと、カメラが「取り込み中で忙しい」と
     * 答えているのか、そもそも応答が返っていないのかを区別できない。打つ手が変わるので
     * 分けて残す。応答は先頭 2 バイトが長さ、続く 2 バイトが応答コード。
     *
     * MTP セッションを開いている最中に呼ぶと interface を奪って壊すので、
     * セッションを閉じた後にだけ呼ぶこと
     */
    fun deviceStatus(context: Context, usbDevice: UsbDevice): String {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!manager.hasPermission(usbDevice)) return "no-permission"
        val conn = manager.openDevice(usbDevice) ?: return "openDevice-failed"
        try {
            val itf = (0 until usbDevice.interfaceCount)
                .map { usbDevice.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
                ?: return "no-still-image-interface"
            if (!conn.claimInterface(itf, true)) return "claim-failed"
            try {
                val buf = ByteArray(64)
                // bmRequestType 0xA1 = class request / interface 宛て / device→host
                val n = conn.controlTransfer(0xA1, 0x67, 0, itf.id, buf, buf.size, 2000)
                if (n < 4) return "short-response($n)"
                val code = (buf[2].toInt() and 0xFF) or ((buf[3].toInt() and 0xFF) shl 8)
                return "0x%04x(%s)".format(code, statusName(code))
            } finally {
                conn.releaseInterface(itf)
            }
        } finally {
            conn.close()
        }
    }

    /** PTP の応答コードのうち、この文脈で出そうなものだけ名前を付ける */
    private fun statusName(code: Int): String = when (code) {
        0x2001 -> "OK"
        0x2019 -> "DeviceBusy"
        0x201F -> "TransactionCancelled"
        0x2000 -> "Undefined"
        0x2002 -> "GeneralError"
        0x2003 -> "SessionNotOpen"
        0x201E -> "InvalidTransactionID"
        else -> "unknown"
    }

    private fun logInterfaces(d: UsbDevice) {
        // USB 記述子側の識別子も残す。MTP の GetDeviceInfo は設定アプリとのセッション
        // 競合で失敗することがあり (2026-08-22 実測)、そのときここから同じカメラだと
        // 判断できるかを確かめるための観測
        val sb = StringBuilder(
            "device 0x%04x:0x%04x class=%d manufacturer=%s product=%s ifaces=".format(
                d.vendorId, d.productId, d.deviceClass,
                d.manufacturerName ?: "(none)", d.productName ?: "(none)",
            )
        )
        for (i in 0 until d.interfaceCount) {
            val itf = d.getInterface(i)
            sb.append("[cls=${itf.interfaceClass},sub=${itf.interfaceSubclass},proto=${itf.interfaceProtocol}]")
        }
        ImportLog.i(TAG, sb.toString())
    }

    /**
     * 全ストレージを走査して未取り込みの写真・RAW・動画を保存する。
     * [kinds] に含まれない種別は走査時点で無視する。
     * [deleteAfterImport] が真なら、保存と履歴記録が済んだファイルだけをカメラ側から消す。
     * [onProgress] は対象件数確定後、1 件処理するごとに呼ぶ。
     * [isCancelled] が真を返したら中断する (ケーブル抜去など)
     */
    fun importAll(
        context: Context,
        mtp: MtpDevice,
        camera: Camera,
        db: HistoryDb,
        kinds: Set<MediaTypes.Kind>,
        deleteAfterImport: Boolean,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
        /** スキャンのオブジェクト 1 件処理ごとに呼ぶ。停止検知の心拍 */
        onScanAlive: () -> Unit = {},
        /**
         * false なら format 指定の重ね照会を省き、全件照会だけでスキャンする縮退モード。
         * 削除を挟んだ後の E-P7 は format 指定照会 (0x3811 等) で固まることがある実測への逃げ道。
         * format 0 に RAW を含めない機種では RAW を取りこぼすが、全滅よりはよい
         */
        fullScan: Boolean = true,
    ): Result {
        val start = System.currentTimeMillis()
        val known = db.importedKeys(camera.model, camera.serial)
        val existing = MediaSaver.existingFiles(context, camera) // 整合チェックは一度だけ取る
        var imported = 0
        var skipped = 0
        var failed = 0
        var deleted = 0
        var deleteRemained = 0
        var resurrected = 0
        var aborted = false

        // 先に全ストレージを走査して対象を確定し、進捗の分母を出す。
        // saveName は保存時の表示名。番号使い回しの衝突時だけカメラ側の名前と変える
        data class Target(
            val handle: Int,
            val info: MtpObjectInfo,
            val key: ImportedKey,
            val saveName: String = key.fileName,
        )
        val targets = ArrayList<Target>()
        val storageIds = mtp.storageIds ?: IntArray(0)
        ImportLog.i(TAG, "storages=${storageIds.size}")
        // 照会の失敗を「0 件」と同じ顔で返さない。区別しないと再試行に乗らないまま
        // 「新しい写真・動画はありません」と表示されて終わる (issue #1 の 4)。
        // 2026-08-22 実測: GetDeviceInfo が失敗するセッションでは GetStorageIDs も
        // 応答せず storages=0 になり、1 件も取り込めないのに完了として報告された
        if (storageIds.isEmpty()) {
            ImportLog.w(TAG, "no storages returned; treating as a failed scan, not an empty camera")
            return Result(0, 0, 0, elapsed(start), false, error = true)
        }
        for (storageId in storageIds) {
            // format 0 (全件) の列挙に RAW を含めない機種がある (E-P7 実測: ORF が返らない) ので、
            // RAW が入りがちな format code を明示して重ねて照会し、handle を統合する。
            // RAW の format code は機種ごとにばらつく (DNG は 0x3811、未定義 0x3000 扱いも多い)
            val handles = LinkedHashSet<Int>()
            val formats = if (fullScan) SCAN_FORMATS else intArrayOf(0)
            if (!fullScan) ImportLog.w(TAG, "degraded scan: format 0 only")
            for (format in formats) {
                onScanAlive()
                val t0 = System.currentTimeMillis()
                val found = mtp.getObjectHandles(storageId, format, 0)
                val dt = System.currentTimeMillis() - t0
                if (found == null) {
                    // 照会そのものの失敗。0 件と区別せずに捨てると取りこぼしに気づけない
                    ImportLog.w(
                        TAG,
                        "storage=$storageId format=0x%04x getObjectHandles returned null (%dms)"
                            .format(format, dt),
                    )
                    continue
                }
                ImportLog.i(
                    TAG,
                    "storage=$storageId format=0x%04x handles=%d (%dms)".format(format, found.size, dt),
                )
                found.forEach { handles.add(it) }
            }
            // カメラが何を見せているかの記録。取りこぼし調査はここを見る
            val extCount = HashMap<String, Int>()
            // スキャンの内訳。応答しなくなった件数と最も遅かった 1 件を残す
            var infoNull = 0
            var slowestInfoMs = 0L
            val scanStart = System.currentTimeMillis()
            for (handle in handles) {
                if (isCancelled()) return Result(imported, skipped, failed, elapsed(start), true)
                onScanAlive()
                val infoT0 = System.currentTimeMillis()
                val info = mtp.getObjectInfo(handle)
                val infoDt = System.currentTimeMillis() - infoT0
                if (infoDt > slowestInfoMs) slowestInfoMs = infoDt
                if (info == null) {
                    infoNull++
                    continue
                }
                if (info.format == MtpConstants.FORMAT_ASSOCIATION) continue // フォルダ
                val name = info.name ?: continue
                extCount.merge(name.substringAfterLast('.', "(なし)").uppercase(), 1, Int::plus)
                // 指定外の種別は履歴照合より前に外す。スキップ件数にも数えない
                val kind = MediaTypes.kindOf(name) ?: continue
                if (kind !in kinds) continue
                val key = ImportedKey(
                    fileName = name,
                    // MTP の ObjectInfo は 32bit のため 4GB 超の動画は折り返した値になるが、
                    // 照合キーとしては毎回同じ値になるので差分判定には使える
                    fileSize = info.compressedSizeLong,
                    // dateCreated が 0 の機種では dateModified で代用 (仕様書 9 の未決事項)
                    shotAt = (if (info.dateCreated != 0L) info.dateCreated else info.dateModified) / 1000,
                )
                val wasDeleted = known[key.asString()]
                if (wasDeleted != null) {
                    skipped++
                    // 削除に成功したはずのキーと完全一致のファイルがまた見えている
                    if (wasDeleted) resurrected++
                } else {
                    // 履歴に無くても保存先に同一ファイルが実在すれば取り込まない (整合チェック)。
                    // ただし名前の一致だけでは足りない: E-P7 は削除後に番号を使い回すため、
                    // 同名の別写真が撮れる (実測)。サイズと先頭バイトまで確かめる
                    val ex = existing[key.fileName]
                    if (ex == null) {
                        targets.add(Target(handle, info, key))
                    } else if (isSameAsExisting(context, mtp, handle, info, ex)) {
                        skipped++
                        db.recordImport(camera.model, camera.serial, key, "existing")
                    } else {
                        // 同名の別写真。撮影日時付きの別名で取り込む
                        targets.add(
                            Target(handle, info, key, MediaSaver.collisionName(key.fileName, key.shotAt))
                        )
                    }
                }
            }
            ImportLog.i(TAG, "storage=$storageId extensions=$extCount")
            ImportLog.i(
                TAG,
                "storage=$storageId scan: handles=${handles.size} infoNull=$infoNull " +
                    "slowest=${slowestInfoMs}ms elapsed=${System.currentTimeMillis() - scanStart}ms",
            )
        }

        onProgress(Progress(targets.size, 0))
        val importedHandles = ArrayList<Target>()
        for ((i, t) in targets.withIndex()) {
            if (isCancelled()) {
                aborted = true
                break
            }
            try {
                val uri = saveOne(context, mtp, t.handle, t.key, t.saveName, camera)
                // 保存が完了してから履歴に記録する順序 (仕様書 5.4)
                db.recordImport(camera.model, camera.serial, t.key, uri.toString())
                imported++
                importedHandles.add(t)
            } catch (e: Exception) {
                ImportLog.w(TAG, "import failed: ${t.key.fileName}", e)
                failed++
            }
            onProgress(Progress(targets.size, i + 1))
        }
        // 削除は全転送が終わってからまとめて行う。転送と DeleteObject を交互に挟むと
        // カメラ側の MTP スタックが崩れることがある (E-P7 実測: 次回接続の format 指定
        // 照会が固まる)。後段に寄せれば、削除中に固まっても取り込み自体は完了している。
        // 消すのは今回転送した分だけ。スキップ済み (過去に取り込んだ分) は触らない。
        // 中断した回 (ケーブル抜去・停止) は削除もしない
        if (deleteAfterImport && !aborted) {
            val deletedHandles = HashSet<Int>()
            for (t in importedHandles) {
                if (isCancelled()) break
                if (mtp.deleteObject(t.handle)) {
                    deleted++
                    deletedHandles.add(t.handle)
                    // 復活検出の基準として記録する (E-P7 実測: 成功応答後に復活する)
                    db.markDeleted(camera.model, camera.serial, t.key)
                } else {
                    ImportLog.w(TAG, "deleteObject refused: ${t.key.fileName}")
                }
            }
            // 成功応答を信用せず、列挙し直して本当に消えたか確かめる
            if (deletedHandles.isNotEmpty()) {
                runCatching {
                    for (storageId in storageIds) {
                        mtp.getObjectHandles(storageId, 0, 0)?.forEach { h ->
                            if (h in deletedHandles) deleteRemained++
                        }
                    }
                }
                if (deleteRemained > 0) ImportLog.w(TAG, "$deleteRemained deleted object(s) still enumerated")
            }
        }
        return Result(
            imported, skipped, failed, elapsed(start), aborted,
            deleted = deleted,
            // RAW を頼まれていないなら、縮退スキャンでも取りこぼしは起きない
            degraded = !fullScan && MediaTypes.Kind.RAW in kinds,
            deleteRemained = deleteRemained,
            resurrected = resurrected,
        )
    }

    /** 名前が一致した実在ファイルとの同一性確認に読む先頭バイト数 */
    private const val HEAD_COMPARE_BYTES = 65536

    /**
     * カメラ上のオブジェクトと保存済みファイルが同一か。サイズ→先頭バイトの順で確かめる。
     * JPEG の先頭には撮影日時とサムネイルが入るため、別写真で先頭まで一致することは
     * 事実上ない。読み出しに失敗したときは従来どおりサイズ一致を信用する
     */
    private fun isSameAsExisting(
        context: Context,
        mtp: MtpDevice,
        handle: Int,
        info: MtpObjectInfo,
        ex: MediaSaver.ExistingFile,
    ): Boolean {
        // 注意: 4GB 超の動画は MTP 側のサイズが 32bit で折り返るため一致しない。
        // その場合は別ファイル扱いで取り込み直しになる (重複は MediaStore の別名保存で無害)
        if (info.compressedSizeLong != ex.size) return false
        val want = minOf(HEAD_COMPARE_BYTES.toLong(), ex.size).toInt()
        if (want <= 0) return true
        val buf = ByteArray(want)
        val got = try {
            mtp.getPartialObject(handle, 0, want.toLong(), buf)
        } catch (e: Exception) {
            ImportLog.w(TAG, "getPartialObject failed: ${info.name}", e)
            -1L
        }
        if (got <= 0) return true
        val cameraHead = if (got.toInt() == want) buf else buf.copyOf(got.toInt())
        val phoneHead = MediaSaver.readHead(context, ex.uri, got.toInt()) ?: return true
        val same = cameraHead.contentEquals(phoneHead)
        if (!same) ImportLog.i(TAG, "name collision, different content: ${info.name}")
        return same
    }

    private fun elapsed(start: Long) = System.currentTimeMillis() - start

    private fun saveOne(
        context: Context,
        mtp: MtpDevice,
        handle: Int,
        key: ImportedKey,
        saveName: String,
        camera: Camera,
    ): Uri {
        // MTP からいったんキャッシュへ、その後 MediaStore へ。
        // 動画では本体の空き容量をファイルサイズ分使う点に注意
        val suffix = "." + key.fileName.substringAfterLast('.', "bin")
        val tmp = File.createTempFile("camimport", suffix, context.cacheDir)
        try {
            if (!mtp.importFile(handle, tmp.absolutePath)) {
                throw IllegalStateException("MTP importFile failed")
            }
            // E-P7 の壊れた OffsetTime を保存前に無害化する。放置すると MediaStore の
            // スキャナが撮影日時の解析に失敗し、datetaken が -1 (1970 扱い) になる
            if (!MediaTypes.isVideo(key.fileName)) {
                val patched = ExifOffsetFix.patchIfBroken(tmp)
                if (patched > 0) ImportLog.i(TAG, "patched $patched broken offset tag(s): ${key.fileName}")
            }
            return MediaSaver.save(context, saveName, key.shotAt, camera) { out ->
                tmp.inputStream().use { it.copyTo(out) }
            }
        } finally {
            tmp.delete()
        }
    }
}
