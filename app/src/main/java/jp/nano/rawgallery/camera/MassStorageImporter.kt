package jp.nano.rawgallery.camera

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/** マスストレージ機 (G99M2 等) 向けの差分取り込み。exFAT を直接読む */
object MassStorageImporter {

    private const val TAG = "MassStorageImporter"
    private const val EARLY_BAIL_FAILURES = 3

    fun importAll(
        context: Context,
        usbDevice: UsbDevice,
        camera: Camera,
        db: HistoryDb,
        /** 取り込む種別。指定外は走査結果から外す。exFAT は読み取り専用のため削除は非対応 */
        kinds: Set<MediaTypes.Kind>,
        onProgress: (MtpImporter.Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): MtpImporter.Result {
        val start = System.currentTimeMillis()
        val metrics = CameraImportMetrics(context, "usb-sd")
        metrics.setSavingListener {
            onProgress(MtpImporter.Progress(metrics.candidates.coerceAtLeast(0), metrics.processed))
        }
        fun complete(result: MtpImporter.Result): MtpImporter.Result {
            metrics.terminal(if (isCancelled() || result.aborted && !result.error) "cancelled" else if (result.error || result.failed > 0) "failed" else "complete")
            return result
        }
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = manager.openDevice(usbDevice)
            ?: return complete(MtpImporter.Result(0, 0, 0, elapsed(start), false, error = true))
        val ums = UsbMassStorage.fromDevice(usbDevice, conn)
        if (ums == null) {
            conn.close()
            return complete(MtpImporter.Result(0, 0, 0, elapsed(start), false, error = true))
        }
        try {
            ums.init()
            val partitionLba = firstPartitionLba(ums)
            val exfat = ExFat(ums, partitionLba)
            exfat.mount()

            val photos = CameraPhotoImporter(context)
            photos.setMetrics(metrics)
            photos.recover { isCancelled() }
            val verifyAll = context.getSharedPreferences("camera_import", 0).getBoolean("sd_verify_all", false)
            val sourceId = "sd-usb:" + exfat.volumeId
            val receipts = CameraImportLedger.get(context).sourceSnapshot(sourceId)
            val historyStart = System.currentTimeMillis()
            val plan = CameraImportHistoryPlan(db.photoImportRecords(camera.model, camera.serial),
                if (verifyAll) null else MediaSaver.photoDestinationSnapshot(context))
            metrics.historyMs = System.currentTimeMillis() - historyStart
            val known = db.importedKeys(camera.model, camera.serial)
            val existing by lazy { MediaSaver.existingFiles(context, camera) } // 動画の候補が必要な場合だけ取得
            ImportProgress.photoListing(0, -1)
            onProgress(MtpImporter.Progress(0, 0))
            val listingStart = System.currentTimeMillis()
            val all = exfat.listMediaUnderDcim()
            metrics.listingMs = System.currentTimeMillis() - listingStart
            metrics.listed = all.count { MediaTypes.kindOf(it.name) in kinds }
            ImportProgress.photoListing(metrics.listed, metrics.listed)
            ImportLog.i(TAG, "found ${all.size} media files")

            var skipped = 0
            // saveName は保存時の表示名。番号使い回しの衝突時だけカメラ側の名前と変える
            data class Target(val node: ExFat.Node, val saveName: String)
            val targets = ArrayList<Target>()
            val classificationStart = System.currentTimeMillis()
            for (node in all) {
                // 指定外の種別は履歴照合より前に外す。スキップ件数にも数えない
                if (isCancelled()) return complete(MtpImporter.Result(0, skipped, 0, elapsed(start), true))
                val kind = MediaTypes.kindOf(node.name) ?: continue
                if (kind !in kinds) continue
                val key = ImportedKey(node.name, node.length, node.modifiedEpochSec)
                if (!MediaTypes.isVideo(node.name)) {
                    // Optional common evidence must not reject a legacy-known large RAW.
                    val receipt = if (receipts.isEmpty()) null else runCatching {
                        val source = CameraImportFile(sourceId, node.relativePath, node.length,
                            (node.modifiedEpochSec * 1000).toString(), node.modifiedEpochSec)
                        receipts[source.sourceKey()]
                    }.getOrNull()
                    if (plan.known(node.name, node.length, node.modifiedEpochSec, verifyAll,
                            receipt?.uri, receipt?.storedBytes ?: -1)) {
                        skipped++
                        metrics.known++
                    } else targets.add(Target(node, node.name))
                } else if (key.asString() in known) {
                    skipped++
                } else {
                    // 履歴に無くても保存先に同一ファイルが実在すれば取り込まない (整合チェック)。
                    // カメラ内削除後の番号使い回しで同名の別写真が撮れるため、
                    // 名前だけでなくサイズと先頭バイトまで確かめる
                    val ex = existing[node.name]
                    if (ex == null) {
                        targets.add(Target(node, node.name))
                    } else if (isSameAsExisting(context, exfat, node, ex)) {
                        skipped++
                        // 履歴が消えていて実データだけ残っている場合はここで履歴を補う
                        db.recordImport(camera.model, camera.serial, key, "existing")
                    } else {
                        // 同名の別写真。撮影日時付きの別名で取り込む
                        targets.add(Target(node, MediaSaver.collisionName(node.name, node.modifiedEpochSec)))
                    }
                }
                metrics.checked++
                ImportProgress.photoHistory(metrics.checked, metrics.listed, metrics.known)
                if (metrics.checked % 50 == 0 || metrics.checked == metrics.listed)
                    onProgress(MtpImporter.Progress(metrics.listed, metrics.checked))
            }

            metrics.historyMs += System.currentTimeMillis() - classificationStart
            metrics.candidates = targets.size
            metrics.content()
            onProgress(MtpImporter.Progress(targets.size, 0))
            var imported = 0
            var failed = 0
            var aborted = false
            var totalMs = 0L
            var bytesTotal = 0L
            var consecutiveFailures = 0
            for ((i, t) in targets.withIndex()) {
                if (isCancelled()) { aborted = true; break }
                val node = t.node
                try {
                    val t0 = System.currentTimeMillis()
                    val key = ImportedKey(node.name, node.length, node.modifiedEpochSec)
                    val uri = if (MediaTypes.isVideo(node.name)) {
                        // Videos may be several GiB; keep the existing streaming path.
                        MediaSaver.save(context, t.saveName, node.modifiedEpochSec, camera) { out ->
                            metrics.countSource { counted -> exfat.copyTo(node, counted) }.write(out)
                        }
                    } else {
                        val source = CameraImportFile(
                            sourceId,
                            node.relativePath, node.length, (node.modifiedEpochSec * 1000).toString(), node.modifiedEpochSec)
                        val result = photos.importPhoto(source, { out -> exfat.copyTo(node, out) },
                            camera.folder, exfat.volumeSerialReliable, verifyAll, { isCancelled() })
                        if (!result.imported) {
                            db.recordImport(camera.model, camera.serial, key, result.uri.toString())
                            skipped++
                            metrics.matches++
                            metrics.processed++
                            metrics.content()
                            consecutiveFailures = 0
                            onProgress(MtpImporter.Progress(targets.size, i + 1))
                            continue
                        }
                        result.uri
                    }
                    totalMs += System.currentTimeMillis() - t0
                    bytesTotal += node.length
                    db.recordImport(camera.model, camera.serial, key, uri.toString())
                    imported++
                    metrics.saved++
                    if (imported % 20 == 0) {
                        val mb = bytesTotal / 1048576.0
                        ImportLog.i(TAG, "perf n=$imported totalMs=$totalMs MBps=%.1f".format(mb / (totalMs / 1000.0)))
                    }
                } catch (e: Exception) {
                    ImportLog.w(TAG, "import failed (${e.javaClass.simpleName})")
                    failed++
                    metrics.processed++
                    metrics.content()
                    if (!MediaTypes.isVideo(node.name)) {
                        // Do not publish later photos after a destination/journal verification failure.
                        return complete(MtpImporter.Result(imported, skipped, failed, elapsed(start), true, error = true))
                    }
                    consecutiveFailures++
                    // 1 件も成功しないまま失敗が続くのは接続自体の不調。1 件ごとの再試行は
                    // 秒単位かかるので全件なめずに見切り、呼び出し側の再接続やり直しに任せる
                    if (imported == 0 && consecutiveFailures >= EARLY_BAIL_FAILURES) {
                        ImportLog.w(TAG, "bailing out: first $consecutiveFailures reads all failed")
                        return complete(MtpImporter.Result(0, skipped, failed, elapsed(start), false, error = true))
                    }
                    onProgress(MtpImporter.Progress(targets.size, i + 1))
                    continue
                }
                consecutiveFailures = 0
                metrics.processed++
                metrics.content()
                onProgress(MtpImporter.Progress(targets.size, i + 1))
            }
            // 走査後に読み取りが全滅したとき (全件失敗) もエラー扱いにする
            val scanError = targets.isNotEmpty() && imported == 0 && failed == targets.size && !aborted
            return complete(MtpImporter.Result(imported, skipped, failed, elapsed(start), aborted, error = scanError))
        } catch (e: Exception) {
            ImportLog.w(TAG, "mass storage import error (${e.javaClass.simpleName})")
            return complete(MtpImporter.Result(0, 0, 0, elapsed(start), false, error = true))
        } finally {
            ums.close()
        }
    }

    /** 名前が一致した実在ファイルとの同一性確認に読む先頭バイト数 */
    private const val HEAD_COMPARE_BYTES = 65536

    /** カメラ上のファイルと保存済みファイルが同一か。サイズ→先頭バイトの順で確かめる */
    private fun isSameAsExisting(
        context: Context,
        exfat: ExFat,
        node: ExFat.Node,
        ex: MediaSaver.ExistingFile,
    ): Boolean {
        if (node.length != ex.size) return false
        val want = minOf(HEAD_COMPARE_BYTES.toLong(), ex.size).toInt()
        if (want <= 0) return false
        val cameraHead = try {
            exfat.readHead(node, want)
        } catch (e: Exception) {
            ImportLog.w(TAG, "readHead failed: ${node.name}", e)
            return false
        }
        if (cameraHead.size != want) return false
        val phoneHead = MediaSaver.readHead(context, ex.uri, want) ?: return false
        return PhotoImportCopy.sameHead(context.cacheDir, cameraHead, phoneHead)
    }

    /** MBR の先頭の有効パーティションの開始 LBA を返す */
    private fun firstPartitionLba(dev: BlockDevice): Long {
        val mbr = ByteArray(dev.blockSize)
        dev.readBlocks(0, mbr, 0, 1)
        for (p in 0 until 4) {
            val base = 446 + p * 16
            val type = mbr[base + 4].toInt() and 0xFF
            if (type == 0x00) continue
            var lba = 0L
            for (k in 0 until 4) lba = lba or ((mbr[base + 8 + k].toLong() and 0xFF) shl (8 * k))
            var sectors = 0L
            for (k in 0 until 4) sectors = sectors or ((mbr[base + 12 + k].toLong() and 0xFF) shl (8 * k))
            if (sectors > 0) return lba
        }
        // パーティションテーブルが無ければ先頭を exFAT とみなす
        return 0
    }

    /** マスストレージ機の識別。MTP のような DeviceInfo が無いので USB の属性を使う */
    fun identity(usbDevice: UsbDevice): MtpImporter.CameraIdentity =
        MtpImporter.CameraIdentity(
            model = usbDevice.productName ?: "USB Camera",
            serial = usbDevice.serialNumber ?: "",
        )

    private fun elapsed(start: Long) = System.currentTimeMillis() - start
}
