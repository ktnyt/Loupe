package jp.nano.rawgallery.camera

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.UncheckedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.BitSet
import java.util.GregorianCalendar
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.TreeSet

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
        val relativePath: String,
    ) {
        // Only nodes read from complete on-disk entry sets can start a direct transfer.
        internal var directSourceLength = -1L

        /** Preserve the original Java/Kotlin six-argument constructor for existing callers. */
        constructor(
            name: String,
            isDir: Boolean,
            firstCluster: Long,
            length: Long,
            noFatChain: Boolean,
            modifiedEpochSec: Long,
        ) : this(name, isDir, firstCluster, length, noFatChain, modifiedEpochSec, name)
    }

    private var bytesPerSector = 512
    private var sectorsPerCluster = 8
    private var fatOffsetSectors = 0L
    private var clusterHeapOffsetSectors = 0L
    private var rootDirCluster = 0L
    private var clusterCount = 0L
    private var volumeLength = 0L
    private var fatLengthSectors = 0L
    private var numberOfFats = 0
    private var volumeFlags = 0
    private var directAllocation: DirectAllocation? = null
    private val bytesPerCluster get() = bytesPerSector * sectorsPerCluster

    /** Raw unsigned exFAT VolumeSerialNumber from the main boot sector (offset 100). */
    var volumeSerial: Long = 0L
        private set
    /** Zero is exposed as unreliable so callers can avoid trusting it for fast deduplication. */
    val volumeSerialReliable: Boolean get() = volumeSerial != 0L
    /** Stable transport-independent descriptor for this volume and its parsed geometry. */
    var volumeId: String = ""
        private set

    fun mount() {
        directAllocation = null
        require(dev.blockSize in 512..4096) { "Unsupported USB block size" }
        val boot = ByteArray(dev.blockSize)
        dev.readBlocks(partitionLba, boot, 0, 1)
        // "EXFAT   " シグネチャ (offset 3, 8 bytes) を確認
        val sig = String(boot, 3, 8, Charsets.US_ASCII)
        require(sig == "EXFAT   ") { "not exFAT (sig='$sig')" }
        val bb = ByteBuffer.wrap(boot).order(ByteOrder.LITTLE_ENDIAN)
        volumeLength = le64(boot, 72)
        fatOffsetSectors = bb.getInt(80).toLong() and 0xFFFFFFFFL
        fatLengthSectors = bb.getInt(84).toLong() and 0xFFFFFFFFL
        clusterHeapOffsetSectors = bb.getInt(88).toLong() and 0xFFFFFFFFL
        rootDirCluster = bb.getInt(96).toLong() and 0xFFFFFFFFL
        clusterCount = bb.getInt(92).toLong() and 0xFFFFFFFFL
        volumeSerial = le32(boot, 100)
        volumeFlags = le16(boot, 106)
        val sectorShift = boot[108].toInt() and 0xFF
        val clusterShift = boot[109].toInt() and 0xFF
        numberOfFats = boot[110].toInt() and 0xFF
        require(sectorShift in 9..12 && clusterShift <= 25 - sectorShift) { "Invalid exFAT geometry" }
        bytesPerSector = 1 shl sectorShift
        sectorsPerCluster = 1 shl clusterShift
        require(bytesPerSector % dev.blockSize == 0 && validCluster(rootDirCluster) &&
            fatOffsetSectors > 0 && clusterHeapOffsetSectors > fatOffsetSectors) { "Invalid exFAT volume" }
        volumeId = listOf(
            "exfat",
            java.lang.Long.toUnsignedString(volumeSerial, 16).padStart(8, '0'),
            java.lang.Long.toUnsignedString(partitionLba),
            java.lang.Long.toUnsignedString(volumeLength),
            java.lang.Long.toUnsignedString(fatOffsetSectors),
            java.lang.Long.toUnsignedString(fatLengthSectors),
            java.lang.Long.toUnsignedString(clusterHeapOffsetSectors),
            java.lang.Long.toUnsignedString(clusterCount),
            java.lang.Long.toUnsignedString(rootDirCluster),
            bytesPerSector.toString(),
            sectorsPerCluster.toString(),
            numberOfFats.toString(),
        ).joinToString(":")
    }

    /** DCIM を辿って取り込み対象 (写真・RAW・動画) のノードを機種フォルダ順に列挙する */
    fun listMediaUnderDcim(): List<Node> {
        val budget = ScanBudget()
        val dcim = listDir(rootDirCluster, noFatChain = false, length = -1, relativePath = "", budget = budget)
            .firstOrNull { it.isDir && it.name.equals("DCIM", ignoreCase = true) }
            ?: return emptyList()
        val out = ArrayList<Node>()
        for (sub in listDir(dcim.firstCluster, dcim.noFatChain, dcim.length, dcim.relativePath, budget).sortedBy { it.name }) {
            if (!sub.isDir) continue
            for (f in listDir(sub.firstCluster, sub.noFatChain, sub.length, sub.relativePath, budget).sortedBy { it.name }) {
                if (!f.isDir && MediaTypes.isTarget(f.name)) out.add(f)
            }
        }
        return out
    }

    /** Scans the entire selected DCIM tree before admitting any direct transfer. */
    @Throws(IOException::class)
    fun listDirectMediaUnderDcim(stopped: java.util.function.BooleanSupplier): List<Node> {
        directAllocation = null
        CameraPhotoPublisher.check(stopped)
        val budget = ScanBudget()
        val allocation = DirectAllocation(requireDirectGeometry())
        val roots = allocation.root(budget)
        val selected = roots.filter { it.isDir && it.name.equals("DCIM", ignoreCase = true) }
        directCheck(selected.size == 1, "A single DCIM folder is required")
        data class Directory(val node: Node, val selected: Boolean, val depth: Int)
        val pending = ArrayDeque<Directory>()
        val files = ArrayList<Node>()
        for (root in roots) {
            if (root.isDir) pending.add(Directory(root, root === selected.single(), 1))
            else if (root.length > 0) files.add(root)
        }
        val out = ArrayList<Node>()
        while (pending.isNotEmpty()) {
            CameraPhotoPublisher.check(stopped)
            val directory = pending.removeLast()
            directCheck(directory.depth <= if (directory.selected) 5 else 32,
                "exFAT directory exceeds the depth limit")
            val children = allocation.directory(directory.node, budget)
            for (node in children.asReversed()) {
                CameraPhotoPublisher.check(stopped)
                if (node.isDir) {
                    pending.add(Directory(node, directory.selected, directory.depth + 1))
                } else {
                    if (node.length > 0) files.add(node)
                    if (directory.selected && MediaTypes.isTarget(node.name)) out.add(node)
                }
            }
        }
        allocation.requireUniqueFileOwners(files, stopped)
        allocation.admitted.addAll(out)
        directAllocation = allocation
        return out.sortedBy { it.relativePath }
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

    /** Streams an initialized on-disk file without allocating a cluster-sized buffer. */
    @Throws(IOException::class)
    fun openForDirectTransfer(node: Node): InputStream {
        val allocation = directAllocation ?: throw IOException("exFAT source allocation has not been verified")
        directCheck(allocation.admitted.contains(node), "Source file was not in the verified DCIM listing")
        directCheck(!node.isDir && node.length > 0 && node.length == node.directSourceLength &&
            validCluster(node.firstCluster), "Invalid or incompletely initialized exFAT source")
        return DirectSourceStream(node, allocation.blocksPerSector, allocation.requireFile(node))
    }

    private fun requireDirectGeometry(): Int {
        val blockSize = dev.blockSize
        directCheck(blockSize in 512..4096 && bytesPerSector % blockSize == 0,
            "Unsupported exFAT source block size")
        val blocksPerSector = bytesPerSector / blockSize
        directCheck(partitionLba >= 0 && blocksPerSector > 0 && numberOfFats == 1 &&
            volumeFlags == 0 &&
            volumeLength > clusterHeapOffsetSectors &&
            volumeLength <= (Long.MAX_VALUE - partitionLba) / blocksPerSector &&
            fatOffsetSectors >= 24 && fatLengthSectors > 0 &&
            fatOffsetSectors + fatLengthSectors <= clusterHeapOffsetSectors &&
            clusterCount in 1..0xFFFFFFF5L &&
            clusterCount <= (volumeLength - clusterHeapOffsetSectors) / sectorsPerCluster &&
            (clusterCount + 2) * 4 <= fatLengthSectors * bytesPerSector,
            "Invalid exFAT source geometry")
        return blocksPerSector
    }

    private inner class DirectAllocation(val blocksPerSector: Int) {
        val admitted: MutableSet<Node> = Collections.newSetFromMap(IdentityHashMap())
        private val metadata = TreeSet<Long>()
        private val fat = ByteArray(16 * 1024)
        private var fatWindow = -1L
        private var bitmap = LongArray(0)
        private val bitmapSector = ByteArray(bytesPerSector)
        private var bitmapLba = -1L

        fun root(budget: ScanBudget): List<Node> {
            val chain = metadataChain(rootDirCluster, -1, false, MAX_DIR_BYTES / bytesPerCluster)
            val bytes = directoryBytes(chain, budget)
            var bitmapEntry = -1
            var upcaseEntry = -1
            for (offset in bytes.indices step 32) {
                when (bytes[offset].toInt() and 0xFF) {
                    0 -> break
                    0x81 -> {
                        directCheck(bitmapEntry == -1 && bytes[offset + 1] == 0.toByte(),
                            "Invalid exFAT allocation bitmap entry")
                        bitmapEntry = offset
                    }
                    0x82 -> {
                        directCheck(upcaseEntry == -1, "Duplicate exFAT Up-case Table")
                        upcaseEntry = offset
                    }
                }
            }
            directCheck(bitmapEntry >= 0 && upcaseEntry >= 0, "Missing exFAT allocation metadata")
            val bitmapLength = le64(bytes, bitmapEntry + 24)
            directCheck(bitmapLength == (clusterCount + 7) / 8, "Incorrect exFAT bitmap length")
            bitmap = metadataChain(le32(bytes, bitmapEntry + 20), bitmapLength, false, 131_072)
            val upcaseLength = le64(bytes, upcaseEntry + 24)
            directCheck(upcaseLength in 2..131072 && upcaseLength % 2 == 0L,
                "Invalid exFAT Up-case Table length")
            val upcase = metadataChain(le32(bytes, upcaseEntry + 20), upcaseLength, false,
                131072 / bytesPerCluster + 1)
            for (cluster in metadata) requireAllocated(cluster)
            verifyUpcase(upcase, upcaseLength, le32(bytes, upcaseEntry + 4))
            return parseDirectory(bytes, "", budget, direct = true, root = true)
        }

        fun directory(node: Node, budget: ScanBudget): List<Node> {
            val chain = metadataChain(node.firstCluster, node.length, node.noFatChain,
                MAX_DIR_BYTES / bytesPerCluster)
            for (cluster in chain) requireAllocated(cluster)
            return parseDirectory(directoryBytes(chain, budget), node.relativePath, budget, direct = true)
        }

        fun requireUniqueFileOwners(files: List<Node>, stopped: java.util.function.BooleanSupplier) {
            directCheck(clusterCount <= MAX_DIRECT_OWNER_CLUSTERS, "exFAT source ownership exceeds the scan budget")
            val owners = BitSet()
            var chainStepsRemaining = MAX_DIRECT_FAT_STEPS
            fun claim(first: Long, end: Long) {
                val metadataOverlap = metadata.ceiling(first)
                directCheck(metadataOverlap == null || metadataOverlap >= end,
                    "exFAT source file overlaps metadata")
                val dataOverlap = owners.nextSetBit(first.toInt())
                directCheck(dataOverlap < 0 || dataOverlap >= end,
                    "Invalid or cyclic exFAT source chain or source file overlaps another file")
                owners.set(first.toInt(), end.toInt())
            }
            for (node in files) {
                CameraPhotoPublisher.check(stopped)
                val count = clusterLength(node.length)
                val first = node.firstCluster
                directCheck(validCluster(first) && count <= clusterCount &&
                    (!node.noFatChain || count <= clusterCount + 2 - first), "Invalid exFAT source extent")
                if (node.noFatChain) {
                    claim(first, first + count)
                    continue
                }
                directCheck(count <= chainStepsRemaining, "exFAT source chains exceed the scan budget")
                chainStepsRemaining -= count
                var cluster = first
                for (index in 0 until count) {
                    CameraPhotoPublisher.check(stopped)
                    directCheck(validCluster(cluster), "Invalid exFAT source chain")
                    claim(cluster, cluster + 1)
                    cluster = next(cluster)
                }
                directCheck(cluster == 0xFFFFFFFFL, "Invalid end of exFAT source chain")
            }
        }

        fun requireFile(node: Node): LongArray? {
            val first = node.firstCluster
            val count = clusterLength(node.length)
            directCheck(node.length <= CameraImportFile.MAX_BYTES && count <= clusterCount &&
                (!node.noFatChain || count <= clusterCount + 2 - first), "Invalid exFAT source extent")
            if (node.noFatChain) {
                val end = first + count
                val overlap = metadata.ceiling(first)
                directCheck(overlap == null || overlap >= end, "exFAT source file overlaps metadata")
                requireAllocatedRange(first, end)
                return null
            }
            val verified = LongArray(count.toInt())
            var cluster = first
            var anchor = first
            var span = 1L
            var steps = 0L
            for (index in 0 until count) {
                directCheck(validCluster(cluster), "Invalid exFAT source chain")
                directCheck(cluster !in metadata, "exFAT source file overlaps metadata")
                requireAllocated(cluster)
                verified[index.toInt()] = cluster
                val next = next(cluster)
                if (index == count - 1) {
                    directCheck(next == 0xFFFFFFFFL, "Invalid end of exFAT source chain")
                } else {
                    directCheck(next != anchor, "Invalid or cyclic exFAT source chain")
                    if (++steps == span) {
                        anchor = next
                        span *= 2
                        steps = 0
                    }
                }
                cluster = next
            }
            return verified
        }

        private fun metadataChain(first: Long, length: Long, contiguous: Boolean, limit: Int): LongArray {
            val count = if (length == -1L) -1 else clusterLength(length)
            directCheck(length != 0L && (length == -1L || length > 0 && count in 1..limit.toLong()) &&
                (!contiguous || count <= clusterCount + 2 - first), "Invalid or oversized exFAT metadata extent")
            val chain = ArrayList<Long>()
            var cluster = first
            while (true) {
                directCheck(chain.size < limit && validCluster(cluster) && metadata.add(cluster),
                    "Invalid or overlapping exFAT metadata chain")
                chain.add(cluster)
                val last = chain.size.toLong() == count
                if (contiguous) {
                    if (last) break
                    cluster++
                } else {
                    cluster = next(cluster)
                    if (count < 0 && cluster == 0xFFFFFFFFL) break
                    if (last) {
                        directCheck(cluster == 0xFFFFFFFFL, "Invalid end of exFAT metadata chain")
                        break
                    }
                }
            }
            return chain.toLongArray()
        }

        private fun directoryBytes(chain: LongArray, budget: ScanBudget): ByteArray {
            for (cluster in chain) budget.directoryCluster(cluster, bytesPerCluster)
            val data = ByteArray(chain.size * bytesPerCluster)
            for ((index, cluster) in chain.withIndex()) {
                val offset = index * bytesPerCluster
                readDirectSectors(clusterSector(cluster), sectorsPerCluster, data, offset, blocksPerSector)
                // The entire chain is reserved even after the directory's end marker.
                if ((offset until offset + bytesPerCluster step 32).any { data[it] == 0.toByte() }) break
            }
            return data
        }

        private fun verifyUpcase(chain: LongArray, length: Long, expected: Long) {
            val buffer = ByteArray(bytesPerSector)
            var checksum = 0
            var remaining = length
            for (cluster in chain) {
                for (sector in 0 until sectorsPerCluster) {
                    readDirectSectors(clusterSector(cluster) + sector, 1, buffer, 0, blocksPerSector)
                    val count = minOf(bytesPerSector.toLong(), remaining).toInt()
                    for (index in 0 until count) {
                        checksum = Integer.rotateRight(checksum, 1) + (buffer[index].toInt() and 0xFF)
                    }
                    remaining -= count
                    if (remaining == 0L) {
                        directCheck(Integer.toUnsignedLong(checksum) == expected, "Invalid exFAT Up-case Table checksum")
                        return
                    }
                }
            }
            throw IOException("Incomplete exFAT Up-case Table")
        }

        private fun requireAllocated(cluster: Long) = requireAllocatedRange(cluster, cluster + 1)

        private fun requireAllocatedRange(first: Long, end: Long) {
            val endBit = end - 2
            var bit = first - 2
            while (bit < endBit) {
                val shift = (bit % 8).toInt()
                val count = minOf((8 - shift).toLong(), endBit - bit).toInt()
                val mask = ((1 shl count) - 1) shl shift
                val byteIndex = bit / 8
                val sector = clusterSector(bitmap[(byteIndex / bytesPerCluster).toInt()]) +
                    byteIndex % bytesPerCluster / bytesPerSector
                if (sector != bitmapLba) {
                    readDirectSectors(sector, 1, bitmapSector, 0, blocksPerSector)
                    bitmapLba = sector
                }
                directCheck(bitmapSector[(byteIndex % bytesPerSector).toInt()].toInt() and mask == mask,
                    "exFAT source contains a free cluster")
                bit += count
            }
        }

        private fun next(cluster: Long): Long {
            val byteIndex = cluster * 4
            val sector = byteIndex / bytesPerSector
            val windowSectors = fat.size / bytesPerSector
            val start = sector / windowSectors * windowSectors
            if (start != fatWindow) {
                val count = minOf(windowSectors.toLong(), fatLengthSectors - start).toInt()
                readDirectSectors(fatOffsetSectors + start, count, fat, 0, blocksPerSector)
                fatWindow = start
            }
            return le32(fat, (byteIndex - start * bytesPerSector).toInt())
        }

        private fun clusterLength(bytes: Long): Long = (bytes - 1) / bytesPerCluster + 1

        private fun clusterSector(cluster: Long): Long =
            clusterHeapOffsetSectors + (cluster - 2) * sectorsPerCluster
    }

    private inner class DirectSourceStream(
        private val node: Node,
        private val blocksPerSector: Int,
        private val chain: LongArray?,
    ) : InputStream() {
        private val sector = ByteArray(bytesPerSector)
        private var cachedDataSector = -1L
        private var closed = false
        private var position = 0L

        override fun read(): Int {
            checkOpen()
            if (position == node.length) return -1
            val lba = currentSector()
            loadDataSector(lba)
            val result = sector[(position % bytesPerSector).toInt()].toInt() and 0xFF
            position++
            return result
        }

        override fun read(dest: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset > dest.size - length) throw IndexOutOfBoundsException()
            checkOpen()
            if (length == 0) return 0
            if (position == node.length) return -1
            val lba = currentSector()
            val remainingInExtent = if (node.noFatChain) node.length - position
                else bytesPerCluster - position % bytesPerCluster
            val requested = minOf(length.toLong(), node.length - position, remainingInExtent).toInt()
            val inSector = (position % bytesPerSector).toInt()
            val copied = if (inSector == 0 && requested >= bytesPerSector) {
                val count = requested / bytesPerSector
                readSource(lba, count, dest, offset)
                count * bytesPerSector
            } else {
                loadDataSector(lba)
                val count = minOf(requested, bytesPerSector - inSector)
                sector.copyInto(dest, offset, inSector, inSector + count)
                count
            }
            position += copied
            return copied
        }

        override fun close() {
            closed = true
        }

        private fun checkOpen() {
            directCheck(!closed, "exFAT source stream is closed")
        }

        private fun currentSector(): Long {
            if (node.noFatChain) {
                return clusterHeapOffsetSectors + (node.firstCluster - 2) * sectorsPerCluster +
                    position / bytesPerSector
            }
            val cluster = checkNotNull(chain)[(position / bytesPerCluster).toInt()]
            return clusterHeapOffsetSectors + (cluster - 2) * sectorsPerCluster +
                position % bytesPerCluster / bytesPerSector
        }

        private fun loadDataSector(lba: Long) {
            if (cachedDataSector != lba) {
                cachedDataSector = -1
                readSource(lba, 1, sector, 0)
                cachedDataSector = lba
            }
        }

        private fun readSource(lba: Long, sectors: Int, dest: ByteArray, offset: Int) {
            readDirectSectors(lba, sectors, dest, offset, blocksPerSector)
        }
    }

    private fun readDirectSectors(lba: Long, sectors: Int, dest: ByteArray, offset: Int, blocksPerSector: Int) {
        directCheck(lba >= 0 && sectors > 0 && lba <= volumeLength - sectors,
            "exFAT source read exceeds volume")
        try {
            dev.readBlocks(partitionLba + lba * blocksPerSector, dest, offset, sectors * blocksPerSector)
        } catch (error: UncheckedIOException) {
            throw error.cause ?: error
        } catch (error: RuntimeException) {
            throw IOException("Could not read exFAT source sectors", error)
        }
    }

    private fun directCheck(condition: Boolean, reason: String) {
        if (!condition) throw IOException(reason)
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
    private fun listDir(
        firstCluster: Long,
        noFatChain: Boolean,
        length: Long,
        relativePath: String,
        budget: ScanBudget,
    ): List<Node> = parseDirectory(readDirBytes(firstCluster, noFatChain, length, budget), relativePath, budget)

    private fun parseDirectory(
        bytes: ByteArray,
        relativePath: String,
        budget: ScanBudget,
        direct: Boolean = false,
        root: Boolean = false,
    ): List<Node> {
        val nodes = ArrayList<Node>()
        val directNames = if (direct) HashSet<String>() else null
        var i = 0
        while (i + 32 <= bytes.size) {
            val type = bytes[i].toInt() and 0xFF
            if (type == 0x00) break // End of directory
            if (type != 0x85) {
                if (direct && type and 0x80 != 0) {
                    directCheck(root && (type in 0x81..0x83 || type == 0xA0),
                        "Unsupported active exFAT directory entry")
                }
                i += 32
                continue
            }
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
            budget.entry()
            val name = nameSb.toString()
            val verifiedLength = if (read == secondaryCount && (direct || !isDir))
                verifiedDirectSourceLength(bytes, i, secondaryCount, j) else -1
            if (direct) {
                directCheck(verifiedLength in 0..dataLen && name.isNotEmpty() && name.length == nameLen &&
                    name != "." && name != ".." &&
                    name.none { it == '/' || it == '\\' || it.isISOControl() } &&
                    (!isDir || (verifiedLength > 0 && verifiedLength == dataLen &&
                        verifiedLength % bytesPerCluster == 0L)),
                    "Invalid exFAT directory entry")
                directCheck(directNames!!.add(name.uppercase(Locale.ROOT)), "Duplicate exFAT directory name")
            }
            val node = Node(
                name = name,
                isDir = isDir,
                firstCluster = firstClusterOfNode,
                length = dataLen,
                noFatChain = noChain,
                modifiedEpochSec = modified,
                relativePath = if (relativePath.isEmpty()) name else "$relativePath/$name",
            )
            if (!isDir) node.directSourceLength = verifiedLength
            nodes.add(node)
            i = j
        }
        return nodes
    }

    private fun verifiedDirectSourceLength(bytes: ByteArray, start: Int, count: Int, end: Int): Long {
        val stream = start + 32
        if (count < 2 || (bytes[stream].toInt() and 0xFF) != 0xC0) return -1
        val flags = bytes[stream + 1].toInt() and 0xFF
        val nameLength = bytes[stream + 3].toInt() and 0xFF
        if ((flags != 1 && flags != 3) || nameLength == 0 || count != 1 + (nameLength + 14) / 15) return -1
        for (offset in stream + 32 until end step 32) {
            if ((bytes[offset].toInt() and 0xFF) != 0xC1 || bytes[offset + 1] != 0.toByte()) return -1
        }
        var checksum = 0
        for (offset in start until end) {
            if (offset == start + 2 || offset == start + 3) continue
            checksum = (((checksum ushr 1) or ((checksum and 1) shl 15)) +
                (bytes[offset].toInt() and 0xFF)) and 0xFFFF
        }
        if (checksum != le16(bytes, start + 2)) return -1
        return le64(bytes, stream + 8)
    }

    /** ディレクトリのクラスタチェーンをすべて連結して返す */
    private fun readDirBytes(firstCluster: Long, noFatChain: Boolean, length: Long, budget: ScanBudget): ByteArray {
        require(validCluster(firstCluster)) { "Invalid exFAT directory cluster" }
        budget.directoryCluster(firstCluster, bytesPerCluster)
        val out = java.io.ByteArrayOutputStream()
        val clusterBuf = ByteArray(bytesPerCluster)
        var cluster = firstCluster
        val seen = HashSet<Long>()
        var remaining = if (length > 0) length else Long.MAX_VALUE
        while (validCluster(cluster) && remaining > 0) {
            require(seen.add(cluster) && out.size() + bytesPerCluster <= MAX_DIR_BYTES) {
                "Invalid or oversized exFAT directory"
            }
            if (cluster != firstCluster) budget.directoryCluster(cluster, bytesPerCluster)
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

    /** One budget spans root, DCIM and every camera folder; cross-linked directories fail closed. */
    private class ScanBudget {
        private var bytes = 0L
        private var entries = 0
        private val clusters = HashSet<Long>()

        fun directoryCluster(cluster: Long, count: Int) {
            require(clusters.add(cluster) && bytes + count <= MAX_DIR_BYTES) {
                "Repeated or oversized exFAT directory scan"
            }
            bytes += count
        }

        fun entry() {
            require(++entries <= MAX_SCAN_ENTRIES) { "Too many exFAT directory entries" }
        }
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
        private const val MAX_SCAN_ENTRIES = 100_000
        private const val MAX_DIR_BYTES = 64 * 1024 * 1024
        private const val MAX_DIRECT_OWNER_CLUSTERS = 32L * 1024 * 1024
        private const val MAX_DIRECT_FAT_STEPS = 4_000_000L
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
