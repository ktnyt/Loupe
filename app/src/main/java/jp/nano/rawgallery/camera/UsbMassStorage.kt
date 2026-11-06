package jp.nano.rawgallery.camera

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** LBA 単位でセクタを読めるブロックデバイス */
interface BlockDevice {
    val blockSize: Int
    /** [lba] から [blockCount] ブロックを [dest] の [destOffset] 以降へ読む */
    fun readBlocks(lba: Long, dest: ByteArray, destOffset: Int, blockCount: Int)
}

/**
 * USB Mass Storage Class の Bulk-Only Transport (BOT) + SCSI で
 * リムーバブルディスクを読むための最小実装。読み取り専用。
 * 対象は interface class 8 / subclass 6 (SCSI) / protocol 0x50 (BOT)
 */
class UsbMassStorage private constructor(
    private val conn: UsbDeviceConnection,
    private val itf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : BlockDevice, Closeable {

    override var blockSize: Int = 512
        private set
    private var tag = 0
    // この個体で安定して通る転送サイズ。大きく始め、ストールを見たら下げて保持する。
    // 不良セクタで一時的に縮めても、正常な読みが通ればここまで戻すので底に居座らない
    private var goodBlocks = INITIAL_MAX_BLOCKS
    private var successRun = 0 // 縮小後の連続成功数。一定数で転送単位を戻す (回復するシーリング)

    // データ受信を 16KB 刻みの同期転送でなく一括で運ぶための再利用資源
    private var reusableBuf: ByteBuffer? = null
    private var inReq: UsbRequest? = null

    /** データ受信用のダイレクトバッファ (足りなければ確保し直す)。ネイティブ側で一括転送される */
    private fun directBuf(size: Int): ByteBuffer {
        var b = reusableBuf
        if (b == null || b.capacity() < size) {
            b = ByteBuffer.allocateDirect(size)
            reusableBuf = b
        }
        return b
    }

    private fun inRequest(): UsbRequest {
        var r = inReq
        if (r == null) {
            r = UsbRequest().apply { initialize(conn, epIn) }
            inReq = r
        }
        return r
    }

    /** claimInterface して READ CAPACITY でブロックサイズを確定する。
     *  接続直後はデバイスがしばらく一切のコマンドに応答しないことがあるので、
     *  準備完了 (TEST UNIT READY) を待ってから READ CAPACITY を試し、駄目なら reset して繰り返す */
    fun init() {
        if (!conn.claimInterface(itf, true)) throw IllegalStateException("claimInterface failed")
        for (attempt in 1..INIT_MAX_ATTEMPTS) {
            val cb = ByteArray(6) // TEST UNIT READY
            if (transfer(cb, null, 0, 0, dataIn = false)) {
                try {
                    readCapacity()
                    ImportLog.i(TAG, "mass storage ready: blockSize=$blockSize (attempt $attempt)")
                    return
                } catch (e: Exception) {
                    ImportLog.w(TAG, "READ CAPACITY failed (attempt $attempt)")
                }
            }
            recover() // reset + HALT 解除で無反応状態を起こす
            try { Thread.sleep(300) } catch (e: InterruptedException) { break }
        }
        throw IllegalStateException("device not ready after $INIT_MAX_ATTEMPTS attempts")
    }

    override fun readBlocks(lba: Long, dest: ByteArray, destOffset: Int, blockCount: Int) {
        var doneBlocks = 0
        var attempts = 0
        var size = goodBlocks // この読みで使う転送単位。失敗のたび一時的に半減する
        var reduced = false
        while (doneBlocks < blockCount) {
            val chunk = minOf(size, blockCount - doneBlocks)
            val off = destOffset + doneBlocks * blockSize
            if (read10(lba + doneBlocks, chunk, dest, off)) {
                doneBlocks += chunk
                attempts = 0
                if (reduced) {
                    // 縮小して初めて通った＝この個体の転送上限。運用値に反映し次回以降を速くする
                    goodBlocks = minOf(goodBlocks, size)
                    reduced = false
                    successRun = 0
                } else if (goodBlocks < INITIAL_MAX_BLOCKS && ++successRun >= GROW_AFTER) {
                    // stall で一時的に縮んだあと、成功が続けば転送単位を戻して速度を回復する
                    goodBlocks = minOf(INITIAL_MAX_BLOCKS, goodBlocks * 2)
                    successRun = 0
                    ImportLog.i(TAG, "grow goodBlocks to $goodBlocks")
                }
                continue
            }
            attempts++
            if (attempts >= MAX_READ_RETRY) {
                // 最小単位でも読めない＝そのセクタ/ファイルが不良。運用値は下げずこの読みだけ諦める
                throw IllegalStateException(
                    "READ(10) failed at lba=${lba + doneBlocks} (chunk=$chunk) after $attempts attempts"
                )
            }
            recover()
            if (size > MIN_BLOCKS_PER_XFER) {
                size = maxOf(MIN_BLOCKS_PER_XFER, size / 2)
                reduced = true
                ImportLog.w(TAG, "read failed; retry at $size blocks")
            }
        }
    }

    /** 1 チャンク分の READ(10)。成否を返す (再試行・縮小は呼び出し側) */
    private fun read10(lba: Long, blocks: Int, dest: ByteArray, destOffset: Int): Boolean {
        val cb = ByteArray(10)
        cb[0] = 0x28 // READ(10)
        cb[2] = (lba ushr 24 and 0xFF).toByte()
        cb[3] = (lba ushr 16 and 0xFF).toByte()
        cb[4] = (lba ushr 8 and 0xFF).toByte()
        cb[5] = (lba and 0xFF).toByte()
        cb[7] = (blocks ushr 8 and 0xFF).toByte()
        cb[8] = (blocks and 0xFF).toByte()
        return transfer(cb, dest, destOffset, blocks * blockSize, dataIn = true)
    }

    /** BOT のスタックからの復帰: リセット + 両エンドポイントの HALT 解除。
     *  リセット直後はデバイスが未準備で READ がハングするので、準備完了を待ってから戻す */
    private fun recover() {
        runCatching { conn.controlTransfer(0x21, 0xFF, 0, itf.id, null, 0, CTRL_TIMEOUT) }
        runCatching { conn.controlTransfer(0x02, 0x01, 0, epIn.address, null, 0, CTRL_TIMEOUT) }
        runCatching { conn.controlTransfer(0x02, 0x01, 0, epOut.address, null, 0, CTRL_TIMEOUT) }
        for (attempt in 1..5) {
            val cb = ByteArray(6) // TEST UNIT READY
            if (transfer(cb, null, 0, 0, dataIn = false)) return
            try { Thread.sleep(100) } catch (e: InterruptedException) { return }
        }
    }

    private fun readCapacity() {
        val cb = ByteArray(10)
        cb[0] = 0x25 // READ CAPACITY(10)
        val data = ByteArray(8)
        if (!transfer(cb, data, 0, 8, dataIn = true)) throw IllegalStateException("READ CAPACITY failed")
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        bb.int // last LBA (未使用)
        val bs = bb.int
        if (bs in 512..8192) blockSize = bs
    }

    /** CBW 送信 → (必要なら) データ転送 → CSW 受信 */
    private fun transfer(cb: ByteArray, data: ByteArray?, dataOffset: Int, dataLen: Int, dataIn: Boolean): Boolean {
        val t = ++tag
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(0x43425355) // "USBC"
        cbw.putInt(t)
        cbw.putInt(dataLen)
        cbw.put((if (dataIn) 0x80 else 0x00).toByte()) // bmCBWFlags: 0x80 = device→host
        cbw.put(0) // LUN
        cbw.put(cb.size.toByte())
        cbw.put(cb)
        val cbwArr = cbw.array() // allocate(31) なので残りは 0 埋め
        if (conn.bulkTransfer(epOut, cbwArr, 31, TIMEOUT) < 0) return false

        if (data != null && dataLen > 0) {
            if (dataIn) {
                // 16KB刻みの同期転送を避け、UsbRequest で一括受信する (16KB の壁を越える)
                val buf = directBuf(dataLen)
                buf.clear()
                buf.limit(dataLen)
                val req = inRequest()
                if (!req.queue(buf)) return false
                if (conn.requestWait(TIMEOUT.toLong()) !== req) return false
                if (buf.position() < dataLen) return false // 短い転送＝失敗扱い (縮小して再試行)
                buf.position(0)
                buf.get(data, dataOffset, dataLen)
            } else {
                var moved = 0
                while (moved < dataLen) {
                    val n = conn.bulkTransfer(epOut, data, dataOffset + moved, dataLen - moved, TIMEOUT)
                    if (n < 0) return false
                    if (n == 0) break
                    moved += n
                }
            }
        }

        val csw = ByteArray(13)
        if (conn.bulkTransfer(epIn, csw, 13, TIMEOUT) < 13) return false
        val sig = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN).int
        if (sig != 0x53425355) return false // "USBS"
        val status = csw[12].toInt()
        return status == 0
    }

    override fun close() {
        runCatching { inReq?.close() }
        runCatching { conn.releaseInterface(itf) }
        runCatching { conn.close() }
    }

    companion object {
        private const val TAG = "UsbMassStorage"
        // 読みは最大 60KB なので 2.5 秒あれば十分。応答しない個体で無駄に待たない
        private const val TIMEOUT = 2500
        private const val CTRL_TIMEOUT = 1000
        // 512→256→128→64→32→16→8 と下げて再試行しつつ、無反応時は早めに諦める
        private const val MAX_READ_RETRY = 9
        // 実測でこの個体の転送上限は約11MB/秒。256KB で十分その天井に届く安定サイズ
        private const val INITIAL_MAX_BLOCKS = 512 // 256KB/回
        private const val GROW_AFTER = 32 // stall で縮んだあと、この回数の成功で転送単位を戻す
        private const val MIN_BLOCKS_PER_XFER = 8 // 4KB。これでも失敗するなら諦める
        // 接続直後の未準備をリセット挟みで待つ回数。実測ではこの粘りで治らない個体も
        // 接続の開き直し (ImportService 側の再試行) なら一発で通るので、早めに諦めて任せる
        private const val INIT_MAX_ATTEMPTS = 3

        fun isMassStorage(device: UsbDevice): Boolean =
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }

        fun fromDevice(device: UsbDevice, conn: UsbDeviceConnection): UsbMassStorage? {
            for (i in 0 until device.interfaceCount) {
                val itf = device.getInterface(i)
                if (itf.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE) continue
                var epIn: UsbEndpoint? = null
                var epOut: UsbEndpoint? = null
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
                }
                if (epIn != null && epOut != null) return UsbMassStorage(conn, itf, epIn, epOut)
            }
            return null
        }
    }
}
