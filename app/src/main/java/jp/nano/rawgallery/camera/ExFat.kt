package jp.nano.rawgallery.camera

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.GregorianCalendar

/**
 * exFAT の読み取り専用パーサ。[BlockDevice] の上で動く。
 * ディレクトリ走査とファイル読み出しのみ。書き込み・ビットマップ更新はしない。
 *
 * 参照: Microsoft exFAT file system specification
 */
class ExFat(private val dev: BlockDevice, private val partitionLba: Long) {

    data class Node(
        val name: String,
        val isDir: Boolean,
        val firstCluster: Long,
        val length: Long,
        val noFatChain: Boolean,
        val modifiedEpochSec: Long,
    )

    private var bytesPerSector = 512
    private var sectorsPerCluster = 8
    private var fatOffsetSectors = 0L
    private var clusterHeapOffsetSectors = 0L
    private var rootDirCluster = 0L
    private var clusterCount = 0L
    private val bytesPerCluster get() = bytesPerSector * sectorsPerCluster

    fun mount() {
        require(dev.blockSize in 512..4096) { "Unsupported USB block size" }
        val boot = ByteArray(dev.blockSize)
        dev.readBlocks(partitionLba, boot, 0, 1)
        // "EXFAT   " シグネチャ (offset 3, 8 bytes) を確認
        val sig = String(boot, 3, 8, Charsets.US_ASCII)
        require(sig == "EXFAT   ") { "not exFAT (sig='$sig')" }
        val bb = ByteBuffer.wrap(boot).order(ByteOrder.LITTLE_ENDIAN)
        fatOffsetSectors = bb.getInt(80).toLong() and 0xFFFFFFFFL
        clusterHeapOffsetSectors = bb.getInt(88).toLong() and 0xFFFFFFFFL
        rootDirCluster = bb.getInt(96).toLong() and 0xFFFFFFFFL
        clusterCount = bb.getInt(92).toLong() and 0xFFFFFFFFL
        val sectorShift = boot[108].toInt() and 0xFF
        val clusterShift = boot[109].toInt() and 0xFF
        require(sectorShift in 9..12 && clusterShift <= 25 - sectorShift) { "Invalid exFAT geometry" }
        bytesPerSector = 1 shl sectorShift
        sectorsPerCluster = 1 shl clusterShift
        require(bytesPerSector % dev.blockSize == 0 && validCluster(rootDirCluster) &&
            fatOffsetSectors > 0 && clusterHeapOffsetSectors > fatOffsetSectors) { "Invalid exFAT volume" }
    }

    /** DCIM を辿って取り込み対象 (写真・RAW・動画) のノードを機種フォルダ順に列挙する */
    fun listMediaUnderDcim(): List<Node> {
        val dcim = listDir(rootDirCluster, noFatChain = false, length = -1)
            .firstOrNull { it.isDir && it.name.equals("DCIM", ignoreCase = true) }
            ?: return emptyList()
        val out = ArrayList<Node>()
        for (sub in listDir(dcim.firstCluster, dcim.noFatChain, dcim.length).sortedBy { it.name }) {
            if (!sub.isDir) continue
            for (f in listDir(sub.firstCluster, sub.noFatChain, sub.length).sortedBy { it.name }) {
                if (!f.isDir && MediaTypes.isTarget(f.name)) out.add(f)
            }
        }
        return out
    }

    /** 先頭 [maxBytes] バイトだけを読む。実在ファイルとの同一性確認用 */
    fun readHead(node: Node, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        copyTo(node.copy(length = minOf(node.length, maxBytes.toLong())), out)
        return out.toByteArray()
    }

    fun copyTo(node: Node, out: OutputStream) {
        require(node.length > 0 && node.length <= clusterCount * bytesPerCluster &&
            validCluster(node.firstCluster)) { "Invalid exFAT file allocation" }
        if (node.noFatChain) {
            require((node.length - 1) / bytesPerCluster <= clusterCount + 1 - node.firstCluster) {
                "Invalid exFAT extent"
            }
            // カメラの書くファイルはほぼ連続配置。大きな窓でまとめ読みして USB 往復を最小化する
            copyContiguous(node.firstCluster, node.length, out)
        } else {
            copyChained(node, out)
        }
    }

    /** 連続配置 (NoFatChain) のファイルを [READ_WINDOW_BYTES] の窓でまとめ読みする */
    private fun copyContiguous(firstCluster: Long, length: Long, out: OutputStream) {
        var remaining = length
        var sector = clusterHeapOffsetSectors + (firstCluster - 2) * sectorsPerCluster
        val windowSectors = maxOf(sectorsPerCluster, READ_WINDOW_BYTES / bytesPerSector)
        val window = ByteArray(windowSectors * bytesPerSector)
        while (remaining > 0) {
            val needSectors = (remaining + bytesPerSector - 1) / bytesPerSector
            val secs = minOf(windowSectors.toLong(), needSectors).toInt()
            readSectors(sector, secs, window, 0)
            val n = minOf(remaining, secs.toLong() * bytesPerSector).toInt()
            out.write(window, 0, n)
            remaining -= n
            sector += secs
        }
    }

    /** 断片化 (FAT チェーン) のファイルはクラスタごとに辿る */
    private fun copyChained(node: Node, out: OutputStream) {
        var remaining = node.length
        val clusterBuf = ByteArray(bytesPerCluster)
        var cluster = node.firstCluster
        val seen = HashSet<Long>()
        while (remaining > 0) {
            require(validCluster(cluster) && seen.add(cluster) && seen.size <= 1_000_000) {
                "Invalid or incomplete exFAT file chain"
            }
            readCluster(cluster, clusterBuf)
            val n = minOf(remaining, bytesPerCluster.toLong()).toInt()
            out.write(clusterBuf, 0, n)
            remaining -= n
            if (remaining > 0) cluster = fatNext(cluster)
        }
    }

    /** ディレクトリのエントリ集合を Node にまとめる */
    private fun listDir(firstCluster: Long, noFatChain: Boolean, length: Long): List<Node> {
        val bytes = readDirBytes(firstCluster, noFatChain, length)
        val nodes = ArrayList<Node>()
        var i = 0
        while (i + 32 <= bytes.size) {
            val type = bytes[i].toInt() and 0xFF
            if (type == 0x00) break // End of directory
            if (type != 0x85) { i += 32; continue } // File Directory Entry 以外は飛ばす
            val secondaryCount = bytes[i + 1].toInt() and 0xFF
            val attrs = le16(bytes, i + 4)
            val isDir = attrs and 0x10 != 0
            val modified = decodeTimestamp(le32(bytes, i + 12))
            // 続く Stream Extension (0xC0) と File Name (0xC1) を読む
            var firstClusterOfNode = 0L
            var dataLen = 0L
            var noChain = false
            var nameLen = 0
            val nameSb = StringBuilder()
            var j = i + 32
            var read = 0
            while (read < secondaryCount && j + 32 <= bytes.size) {
                when (bytes[j].toInt() and 0xFF) {
                    0xC0 -> {
                        val flags = bytes[j + 1].toInt() and 0xFF
                        noChain = flags and 0x02 != 0
                        nameLen = bytes[j + 3].toInt() and 0xFF
                        firstClusterOfNode = le32(bytes, j + 20)
                        dataLen = le64(bytes, j + 24)
                    }
                    0xC1 -> {
                        var k = j + 2
                        while (k <= j + 30 && nameSb.length < nameLen) {
                            nameSb.append(le16(bytes, k).toChar())
                            k += 2
                        }
                    }
                }
                read++
                j += 32
            }
            nodes.add(
                Node(
                    name = nameSb.toString(),
                    isDir = isDir,
                    firstCluster = firstClusterOfNode,
                    length = dataLen,
                    noFatChain = noChain,
                    modifiedEpochSec = modified,
                )
            )
            i = j
        }
        return nodes
    }

    /** ディレクトリのクラスタチェーンをすべて連結して返す */
    private fun readDirBytes(firstCluster: Long, noFatChain: Boolean, length: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val clusterBuf = ByteArray(bytesPerCluster)
        var cluster = firstCluster
        val seen = HashSet<Long>()
        var remaining = if (length > 0) length else Long.MAX_VALUE
        while (validCluster(cluster) && remaining > 0) {
            require(seen.add(cluster) && out.size() + bytesPerCluster <= MAX_DIR_BYTES) {
                "Invalid or oversized exFAT directory"
            }
            readCluster(cluster, clusterBuf)
            val n = if (length > 0) minOf(remaining, bytesPerCluster.toLong()).toInt() else bytesPerCluster
            out.write(clusterBuf, 0, n)
            for (entry in 0 until n step 32) {
                if (clusterBuf[entry] == 0.toByte()) return out.toByteArray()
            }
            remaining -= n
            if (remaining > 0) cluster = if (noFatChain) cluster + 1 else fatNext(cluster)
        }
        return out.toByteArray()
    }

    private fun validCluster(cluster: Long): Boolean = cluster in 2..minOf(clusterCount + 1, 0xFFFFFFF6L)

    private fun fatNext(cluster: Long): Long {
        val fatByte = fatOffsetSectors * bytesPerSector + cluster * 4
        val sector = fatByte / bytesPerSector
        val within = (fatByte % bytesPerSector).toInt()
        val buf = ByteArray(bytesPerSector)
        readSectors(sector, 1, buf, 0)
        return le32(buf, within)
    }

    private fun readCluster(cluster: Long, dest: ByteArray) {
        val sector = clusterHeapOffsetSectors + (cluster - 2) * sectorsPerCluster
        readSectors(sector, sectorsPerCluster, dest, 0)
    }

    /** exFAT セクタ単位の read を BlockDevice のブロック単位に変換する */
    private fun readSectors(sector: Long, count: Int, dest: ByteArray, destOffset: Int) {
        if (bytesPerSector == dev.blockSize) {
            dev.readBlocks(partitionLba + sector, dest, destOffset, count)
        } else {
            // exFAT セクタが 512 より大きい場合 (稀) の換算
            val lba = partitionLba + sector * (bytesPerSector / dev.blockSize)
            val blocks = (count * bytesPerSector + dev.blockSize - 1) / dev.blockSize
            dev.readBlocks(lba, dest, destOffset, blocks)
        }
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int): Long {
        var v = 0L
        for (k in 0 until 4) v = v or ((b[o + k].toLong() and 0xFF) shl (8 * k))
        return v
    }
    private fun le64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (k in 0 until 8) v = v or ((b[o + k].toLong() and 0xFF) shl (8 * k))
        return v
    }

    companion object {
        private const val MAX_DIR_BYTES = 64 * 1024 * 1024
        private const val READ_WINDOW_BYTES = 256 * 1024

        /** exFAT の 32bit タイムスタンプ (ローカル時刻) を epoch 秒に直す */
        fun decodeTimestamp(ts: Long): Long {
            if (ts == 0L) return 0
            val sec = (ts and 0x1F).toInt() * 2
            val min = (ts ushr 5 and 0x3F).toInt()
            val hour = (ts ushr 11 and 0x1F).toInt()
            val day = (ts ushr 16 and 0x1F).toInt()
            val month = (ts ushr 21 and 0x0F).toInt()
            val year = 1980 + (ts ushr 25 and 0x7F).toInt()
            if (month < 1 || day < 1) return 0
            return GregorianCalendar(year, month - 1, day, hour, min, sec).timeInMillis / 1000
        }
    }
}
