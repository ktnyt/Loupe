package jp.nano.rawgallery.camera

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
            RandomAccessFile(file, "rw").use { patch(it) }
        } catch (e: Exception) {
            0
        }

    private fun patch(f: RandomAccessFile): Int {
        val tiffBase = findTiffBase(f) ?: return 0
        // "II"/"MM" は 2 バイト同値なのでエンディアン指定はどちらでもよい
        val le = when (readU16(f, tiffBase, le = true)) {
            0x4949 -> true
            0x4D4D -> false
            else -> return 0
        }
        val ifd0 = tiffBase + readU32(f, tiffBase + 4, le)
        val exifIfdOff = findTagValue(f, ifd0, le, TAG_EXIF_IFD) ?: return 0
        val exifIfd = tiffBase + exifIfdOff
        val count = readU16(f, exifIfd, le)
        if (count <= 0 || count > MAX_IFD_ENTRIES) return 0

        // 撮影日時のタイムゾーンオフセット。DST のある地域でも撮影時点の値になる
        val offsetBytes = offsetStringFor(
            readAsciiTag(f, tiffBase, exifIfd, le, count, TAG_DATETIME_ORIGINAL)
                ?: readAsciiTag(f, tiffBase, exifIfd, le, count, TAG_DATETIME_DIGITIZED)
        )

        var patched = 0
        var restored = 0
        for (i in 0 until count) {
            val entry = exifIfd + 2 + i.toLong() * 12
            val tag = readU16(f, entry, le)
            if (readU16(f, entry + 2, le) != TYPE_ASCII) continue
            val n = readU32(f, entry + 4, le)
            if (n <= 0 || n > 33) continue
            val valuePos = if (n <= 4) entry + 8 else tiffBase + readU32(f, entry + 8, le)
            val bytes = ByteArray(n.toInt())
            f.seek(valuePos)
            f.readFully(bytes)
            when {
                tag in OFFSET_TAGS && !isValidOffsetString(bytes) -> {
                    if (n.toInt() == OFFSET_LEN) {
                        f.seek(valuePos)
                        f.write(offsetBytes)
                    } else {
                        // 値を書き込める形でないときは無効化だけして解析失敗を防ぐ
                        writeU16(f, entry, le, TAG_PADDING)
                    }
                    patched++
                }
                // 旧版のこのアプリが Padding 化した痕跡 (空欄値のまま残っている)。
                // IFD はタグ昇順なので、遭遇順に 0x9010/0x9011/0x9012 を割り戻す
                tag == TAG_PADDING && n.toInt() == OFFSET_LEN && isBlankOffset(bytes) && restored < 3 -> {
                    writeU16(f, entry, le, 0x9010 + restored)
                    f.seek(valuePos)
                    f.write(offsetBytes)
                    restored++
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
        f: RandomAccessFile,
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
    private fun findTiffBase(f: RandomAccessFile): Long? {
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

    private fun findTagValue(f: RandomAccessFile, ifd: Long, le: Boolean, wanted: Int): Long? {
        val count = readU16(f, ifd, le)
        if (count <= 0 || count > MAX_IFD_ENTRIES) return null
        for (i in 0 until count) {
            val entry = ifd + 2 + i.toLong() * 12
            if (readU16(f, entry, le) == wanted) return readU32(f, entry + 8, le)
        }
        return null
    }

    private fun readU16(f: RandomAccessFile, pos: Long, le: Boolean): Int {
        f.seek(pos)
        val a = f.read()
        val b = f.read()
        if (a < 0 || b < 0) return -1
        return if (le) a or (b shl 8) else (a shl 8) or b
    }

    private fun readU32(f: RandomAccessFile, pos: Long, le: Boolean): Long {
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

    private fun writeU16(f: RandomAccessFile, pos: Long, le: Boolean, v: Int) {
        f.seek(pos)
        if (le) {
            f.write(v and 0xFF)
            f.write((v shr 8) and 0xFF)
        } else {
            f.write((v shr 8) and 0xFF)
            f.write(v and 0xFF)
        }
    }
}
