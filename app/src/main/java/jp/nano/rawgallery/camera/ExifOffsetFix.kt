package jp.nano.rawgallery.camera

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * E-P7 が書く壊れた OffsetTime 系タグ ("   :  " のような空欄) を正しい値に直す。
 * Android の ExifInterface は日時とオフセットを連結して解析するため、壊れた
 * オフセットがあると撮影日時の解析ごと失敗し、MediaStore の datetaken が -1 になる。
 * タグを消すだけだとスキャナが「ファイル更新時刻との差 (15 分丸め)」でタイムゾーンを
 * 推定してしまい、取り込みや修復の時刻に引きずられて datetaken がずれる (実測 30 分)。
 * そこで端末のタイムゾーンから "+09:00" 形式の値を作り、同じ 7 バイトへ上書きする。
 * バイト位置は一切動かないので、画像データや Olympus MakerNotes には影響しない。
 * JPEG と TIFF 構造の RAW (ORF 等) の両方を扱う
 */
object ExifOffsetFix {

    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_DATETIME_ORIGINAL = 0x9003
    private const val TAG_DATETIME_DIGITIZED = 0x9004
    private val OFFSET_TAGS = setOf(0x9010, 0x9011, 0x9012) // OffsetTime / Original / Digitized
    private const val TAG_PADDING = 0xEA1C
    private const val TYPE_ASCII = 2
    private const val OFFSET_LEN = 7 // "+09:00" + 終端 NUL
    private const val MAX_IFD_ENTRIES = 512 // 異常なファイルの読み過ぎ防止

    /** 壊れた OffsetTime タグを直し、書き換えた個数を返す。対象外・異常時は 0 */
    fun patchIfBroken(file: File): Int =
        try {
            RandomAccessFile(file, "rw").use { patch(FileAccess(it)) }
        } catch (e: Exception) {
            0
        }

    /** Comparison uses the imported values only for fields the source repair is allowed to change. */
    internal fun patchForComparison(sourceCopy: File, importedHeader: File) {
        try {
            RandomAccessFile(sourceCopy, "rw").use { source ->
                RandomAccessFile(importedHeader, "rw").use { imported ->
                    normalizeForComparison(FileAccess(source), FileAccess(imported))
                }
            }
        } catch (e: Exception) {
            // The full-byte comparison still rejects any remaining difference.
        }
    }

    /** The caller must compare the file length and every byte beyond these equal-length prefixes. */
    @JvmStatic
    fun sameAfterOffsetRepair(sourcePrefix: ByteArray, savedPrefix: ByteArray): Boolean {
        if (sourcePrefix.isEmpty() || sourcePrefix.size != savedPrefix.size) return false
        if (sourcePrefix.contentEquals(savedPrefix)) return true
        val normalized = sourcePrefix.copyOf()
        val source = PrefixAccess(normalized)
        val saved = PrefixAccess(savedPrefix)
        return try {
            val directory = directory(source) ?: return false
            val entries = repairableEntries(source)
            patch(source, validOffsets(saved), entries)
            for (entry in entries) {
                val tag = readU16(source, entry, directory.little)
                if (tag !in OFFSET_TAGS || readU16(saved, entry, directory.little) != TAG_PADDING ||
                    readU32(source, entry + 4, directory.little) != OFFSET_LEN.toLong()) continue
                val value = ByteArray(OFFSET_LEN)
                source.seek(directory.base + readU32(source, entry + 8, directory.little))
                source.readFully(value)
                // Older CameraSync releases changed the tag to Padding and kept its blank value.
                if (isBlankOffset(value)) writeU16(source, entry, directory.little, TAG_PADDING)
            }
            normalized.contentEquals(savedPrefix)
        } catch (_: EOFException) {
            false
        }
    }

    private fun normalizeForComparison(source: Access, imported: Access) {
        val entries = repairableEntries(source)
        val offsets = OFFSET_TAGS.associateWith { "+00:00\u0000".toByteArray(Charsets.US_ASCII) }
            .toMutableMap()
        offsets.putAll(validOffsets(imported))
        patch(source, offsets, entries)
        patch(imported, offsets, entries)
    }

    private interface Access {
        fun length(): Long
        fun seek(offset: Long)
        fun read(): Int
        fun readFully(destination: ByteArray)
        fun write(bytes: ByteArray)
    }

    private class FileAccess(private val file: RandomAccessFile) : Access {
        override fun length() = file.length()
        override fun seek(offset: Long) = file.seek(offset)
        override fun read() = file.read()
        override fun readFully(destination: ByteArray) = file.readFully(destination)
        override fun write(bytes: ByteArray) = file.write(bytes)
    }

    private class PrefixAccess(private val bytes: ByteArray) : Access {
        private var position = 0

        override fun length() = bytes.size.toLong()

        override fun seek(offset: Long) {
            if (offset < 0 || offset > bytes.size) throw EOFException()
            position = offset.toInt()
        }

        override fun read(): Int = bytes[advance(1)].toInt() and 0xFF

        override fun readFully(destination: ByteArray) {
            val start = advance(destination.size)
            bytes.copyInto(destination, startIndex = start, endIndex = position)
        }

        override fun write(bytes: ByteArray) {
            bytes.copyInto(this.bytes, destinationOffset = advance(bytes.size))
        }

        private fun advance(count: Int): Int {
            if (count > bytes.size - position) throw EOFException()
            val start = position
            position += count
            return start
        }
    }

    private data class Directory(val base: Long, val offset: Long, val little: Boolean, val count: Int)

    private fun directory(f: Access): Directory? {
        val tiffBase = findTiffBase(f) ?: return null
        // "II"/"MM" は 2 バイト同値なのでエンディアン指定はどちらでもよい
        val le = when (readU16(f, tiffBase, le = true)) {
            0x4949 -> true
            0x4D4D -> false
            else -> return null
        }
        val ifd0 = tiffBase + readU32(f, tiffBase + 4, le)
        val exifIfdOff = findTagValue(f, ifd0, le, TAG_EXIF_IFD) ?: return null
        val exifIfd = tiffBase + exifIfdOff
        val count = readU16(f, exifIfd, le)
        if (count <= 0 || count > MAX_IFD_ENTRIES) return null
        return Directory(tiffBase, exifIfd, le, count)
    }

    private fun validOffsets(f: Access): Map<Int, ByteArray> {
        val d = directory(f) ?: return emptyMap()
        val result = HashMap<Int, ByteArray>()
        for (i in 0 until d.count) {
            val entry = d.offset + 2 + i.toLong() * 12
            val tag = readU16(f, entry, d.little)
            if (tag !in OFFSET_TAGS || readU16(f, entry + 2, d.little) != TYPE_ASCII ||
                readU32(f, entry + 4, d.little) != OFFSET_LEN.toLong()) continue
            val value = ByteArray(OFFSET_LEN)
            f.seek(d.base + readU32(f, entry + 8, d.little))
            f.readFully(value)
            if (isValidOffsetString(value) && value[6].toInt() == 0) result[tag] = value
        }
        return result
    }

    private fun repairableEntries(f: Access): Set<Long> {
        val d = directory(f) ?: return emptySet()
        val result = HashSet<Long>()
        for (i in 0 until d.count) {
            val entry = d.offset + 2 + i.toLong() * 12
            val tag = readU16(f, entry, d.little)
            if (tag !in OFFSET_TAGS && tag != TAG_PADDING) continue
            if (readU16(f, entry + 2, d.little) != TYPE_ASCII) continue
            val n = readU32(f, entry + 4, d.little)
            if (n <= 0 || n > 33) continue
            val value = ByteArray(n.toInt())
            f.seek(if (n <= 4) entry + 8 else d.base + readU32(f, entry + 8, d.little))
            f.readFully(value)
            if ((tag in OFFSET_TAGS && !isValidOffsetString(value)) ||
                (tag == TAG_PADDING && n.toInt() == OFFSET_LEN && isBlankOffset(value)))
                result.add(entry)
        }
        return result
    }

    private fun patch(
        f: Access,
        comparisonOffsets: Map<Int, ByteArray>? = null,
        comparisonEntries: Set<Long>? = null,
    ): Int {
        val d = directory(f) ?: return 0
        val tiffBase = d.base
        val exifIfd = d.offset
        val le = d.little
        val count = d.count

        // 撮影日時のタイムゾーンオフセット。DST のある地域でも撮影時点の値になる
        val offsetBytes = if (comparisonOffsets == null) offsetStringFor(
            readAsciiTag(f, tiffBase, exifIfd, le, count, TAG_DATETIME_ORIGINAL)
                ?: readAsciiTag(f, tiffBase, exifIfd, le, count, TAG_DATETIME_DIGITIZED)
        ) else null

        var patched = 0
        var restored = 0
        for (i in 0 until count) {
            val entry = exifIfd + 2 + i.toLong() * 12
            val tag = readU16(f, entry, le)
            if (comparisonOffsets != null && tag !in OFFSET_TAGS && tag != TAG_PADDING) continue
            if (readU16(f, entry + 2, le) != TYPE_ASCII) continue
            val n = readU32(f, entry + 4, le)
            if (n <= 0 || n > 33) continue
            val valuePos = if (n <= 4) entry + 8 else tiffBase + readU32(f, entry + 8, le)
            val bytes = ByteArray(n.toInt())
            f.seek(valuePos)
            f.readFully(bytes)
            when {
                tag in OFFSET_TAGS && !isValidOffsetString(bytes) -> {
                    if (comparisonEntries != null && entry !in comparisonEntries) continue
                    if (n.toInt() == OFFSET_LEN) {
                        val replacement = comparisonOffsets?.get(tag) ?: offsetBytes ?: continue
                        f.seek(valuePos)
                        f.write(replacement)
                    } else {
                        // 値を書き込める形でないときは無効化だけして解析失敗を防ぐ
                        writeU16(f, entry, le, TAG_PADDING)
                    }
                    patched++
                }
                // 旧版のこのアプリが Padding 化した痕跡 (空欄値のまま残っている)。
                // IFD はタグ昇順なので、遭遇順に 0x9010/0x9011/0x9012 を割り戻す
                tag == TAG_PADDING && n.toInt() == OFFSET_LEN && isBlankOffset(bytes) && restored < 3 -> {
                    val restoredTag = 0x9010 + restored++
                    if (comparisonEntries != null && entry !in comparisonEntries) continue
                    val replacement = comparisonOffsets?.get(restoredTag) ?: offsetBytes ?: continue
                    writeU16(f, entry, le, restoredTag)
                    f.seek(valuePos)
                    f.write(replacement)
                    patched++
                }
            }
        }
        return patched
    }

    /** "+09:00" 形式なら真。E-P7 の空欄 ("   :  ") は偽 */
    private fun isValidOffsetString(b: ByteArray): Boolean {
        if (b.size < 6) return false
        val s = String(b, 0, 6, Charsets.US_ASCII)
        return Regex("""[+-]\d\d:\d\d""").matches(s)
    }

    /** 空白と ':' と NUL だけで ':' を含む。E-P7 の壊れ方の signature */
    private fun isBlankOffset(b: ByteArray): Boolean =
        b.all { it.toInt() == 0 || it.toInt() == ' '.code || it.toInt() == ':'.code } &&
            b.any { it.toInt() == ':'.code }

    /** 撮影日時 (無ければ現在時刻) に対する端末タイムゾーンの "+09:00\u0000" (7 バイト) */
    private fun offsetStringFor(dateTime: String?): ByteArray {
        val millis = dateTime?.let {
            try {
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it.take(19))?.time
            } catch (e: Exception) {
                null
            }
        } ?: System.currentTimeMillis()
        val off = TimeZone.getDefault().getOffset(millis)
        val sign = if (off < 0) '-' else '+'
        val absMin = kotlin.math.abs(off) / 60000
        return "%c%02d:%02d\u0000".format(sign, absMin / 60, absMin % 60)
            .toByteArray(Charsets.US_ASCII)
    }

    /** ExifIFD 内の ASCII タグの文字列値。無ければ null */
    private fun readAsciiTag(
        f: Access,
        tiffBase: Long,
        ifd: Long,
        le: Boolean,
        count: Int,
        wanted: Int,
    ): String? {
        for (i in 0 until count) {
            val entry = ifd + 2 + i.toLong() * 12
            if (readU16(f, entry, le) != wanted) continue
            if (readU16(f, entry + 2, le) != TYPE_ASCII) return null
            val n = readU32(f, entry + 4, le)
            if (n <= 0 || n > 64) return null
            val valuePos = if (n <= 4) entry + 8 else tiffBase + readU32(f, entry + 8, le)
            val bytes = ByteArray(n.toInt())
            f.seek(valuePos)
            f.readFully(bytes)
            return String(bytes, Charsets.US_ASCII).trimEnd('\u0000')
        }
        return null
    }

    /** JPEG なら APP1 の Exif ヘッダ直後、TIFF 構造 (ORF 含む) なら 0。それ以外は null */
    private fun findTiffBase(f: Access): Long? {
        if (f.length() < 16) return null
        f.seek(0)
        val b0 = f.read()
        val b1 = f.read()
        if ((b0 == 0x49 && b1 == 0x49) || (b0 == 0x4D && b1 == 0x4D)) return 0
        if (b0 != 0xFF || b1 != 0xD8) return null
        var pos = 2L
        val buf = ByteArray(4)
        // セグメントを順に見て Exif の APP1 を探す。SOS 以降に EXIF は無い
        while (pos + 4 <= f.length()) {
            f.seek(pos)
            f.readFully(buf)
            if ((buf[0].toInt() and 0xFF) != 0xFF) return null
            val marker = buf[1].toInt() and 0xFF
            if (marker == 0xDA) return null // SOS
            val len = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            if (len < 2) return null
            if (marker == 0xE1 && len >= 8) {
                val sig = ByteArray(6)
                f.readFully(sig)
                if (String(sig, Charsets.US_ASCII) == "Exif\u0000\u0000") return pos + 4 + 6
            }
            pos += 2 + len
        }
        return null
    }

    private fun findTagValue(f: Access, ifd: Long, le: Boolean, wanted: Int): Long? {
        val count = readU16(f, ifd, le)
        if (count <= 0 || count > MAX_IFD_ENTRIES) return null
        for (i in 0 until count) {
            val entry = ifd + 2 + i.toLong() * 12
            if (readU16(f, entry, le) == wanted) return readU32(f, entry + 8, le)
        }
        return null
    }

    private fun readU16(f: Access, pos: Long, le: Boolean): Int {
        f.seek(pos)
        val a = f.read()
        val b = f.read()
        if (a < 0 || b < 0) return -1
        return if (le) a or (b shl 8) else (a shl 8) or b
    }

    private fun readU32(f: Access, pos: Long, le: Boolean): Long {
        f.seek(pos)
        val b = ByteArray(4)
        f.readFully(b)
        return if (le) {
            (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
                ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
        } else {
            (b[3].toLong() and 0xFF) or ((b[2].toLong() and 0xFF) shl 8) or
                ((b[1].toLong() and 0xFF) shl 16) or ((b[0].toLong() and 0xFF) shl 24)
        }
    }

    private fun writeU16(f: Access, pos: Long, le: Boolean, v: Int) {
        f.seek(pos)
        val low = v.toByte()
        val high = (v shr 8).toByte()
        if (le) {
            f.write(byteArrayOf(low, high))
        } else {
            f.write(byteArrayOf(high, low))
        }
    }
}
