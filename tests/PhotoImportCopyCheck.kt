package jp.nano.rawgallery.camera

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.TimeZone

/** Synthetic headers only; exercises the exact photo-copy boundary used by mass-storage import. */
fun main() {
    val directory = Files.createTempDirectory("loupe-photo-copy-check-").toFile()
    val timezone = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
    try {
        val broken = header("   :  ")
        val before = broken.copyOf()
        val corrected = PhotoImportCopy.prepare(directory, "SYNTHETIC.ORF", broken.size.toLong(),
            { it.write(broken) }, { it.readBytes() })
        check(broken.contentEquals(before)) { "Camera input was modified" }
        check(corrected.size == broken.size) { "Photo length changed" }
        for (position in listOf(100, 107, 114))
            check(String(corrected, position, 7, Charsets.US_ASCII) == "+09:00\u0000")
        val allowed = (100 until 121).toSet()
        for (i in before.indices) if (i !in allowed)
            check(before[i] == corrected[i]) { "Non-offset data changed at $i" }
        check(directory.listFiles()!!.isEmpty()) { "Success left a temporary file" }

        val jpeg = byteArrayOf(-1, -40, -1, -31, 0, (broken.size + 8).toByte()) +
            "Exif\u0000\u0000".toByteArray() + broken + byteArrayOf(-1, -39)
        val correctedJpeg = PhotoImportCopy.prepare(directory, "SYNTHETIC.JPG", jpeg.size.toLong(),
            { it.write(jpeg) }, { it.readBytes() })
        check(correctedJpeg.size == jpeg.size)
        check(String(correctedJpeg, 12 + 107, 7, Charsets.US_ASCII) == "+09:00\u0000") {
            "JPEG APP1 offset was not repaired"
        }

        val valid = header("-03:30")
        val unchanged = PhotoImportCopy.prepare(directory, "VALID.JPG", valid.size.toLong(),
            { it.write(valid) }, { it.readBytes() })
        check(valid.contentEquals(unchanged)) { "Valid offset changed" }
        var consumed = false
        try {
            PhotoImportCopy.prepare(directory, "UNKNOWN.ORF", 0,
                { error("Unknown-size source was read") }, { consumed = true })
            error("Unknown size accepted")
        } catch (expected: IllegalArgumentException) {
            check(!consumed)
        }
        try {
            PhotoImportCopy.prepare(directory, "SHORT.ORF", broken.size.toLong() + 1,
                { it.write(broken) }, { consumed = true })
            error("Short transfer accepted")
        } catch (expected: IllegalStateException) {
            check(!consumed) { "Unverified transfer was published" }
        }
        try {
            PhotoImportCopy.prepare(directory, "FAIL.ORF", broken.size.toLong(),
                { it.write(broken, 0, 12); throw IOException("synthetic source failure") },
                { error("Failed source was published") })
            error("Source failure ignored")
        } catch (expected: IOException) { }
        try {
            PhotoImportCopy.prepare(directory, "FAIL.ORF", broken.size.toLong(),
                { it.write(broken) }, { throw IOException("synthetic destination failure") })
            error("Destination failure ignored")
        } catch (expected: IOException) { }
        check(directory.listFiles()!!.isEmpty()) { "Failure left a temporary file" }

        check(PhotoImportCopy.sameHead(directory, broken, corrected)) { "Repaired copy dedup failed" }
        check(PhotoImportCopy.sameHead(directory, broken, before)) { "Unpatched copy dedup failed" }
        val different = corrected.copyOf().also { it[150] = 99 }
        check(!PhotoImportCopy.sameHead(directory, broken, different)) { "Different photo matched" }
        check(!PhotoImportCopy.sameHead(directory, valid, corrected)) { "Different valid offset matched" }
        check(directory.listFiles()!!.isEmpty()) { "Dedup left a temporary file" }
        println("PASS photo import copy: offset repair, original preservation, sizes, rollback and dedup")
    } finally {
        TimeZone.setDefault(timezone)
        directory.listFiles()?.forEach { check(it.delete()) }
        check(directory.delete())
    }
}

private fun header(offset: String): ByteArray {
    val bytes = ByteArray(160) { 37 }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put(0, 'I'.code.toByte()); buffer.put(1, 'I'.code.toByte())
    buffer.putShort(2, 42); buffer.putInt(4, 8)
    buffer.putShort(8, 1)
    buffer.putShort(10, 0x8769.toShort()); buffer.putShort(12, 4)
    buffer.putInt(14, 1); buffer.putInt(18, 26); buffer.putInt(22, 0)
    buffer.putShort(26, 4)
    fun entry(position: Int, tag: Int, size: Int, data: Int) {
        buffer.putShort(position, tag.toShort()); buffer.putShort(position + 2, 2)
        buffer.putInt(position + 4, size); buffer.putInt(position + 8, data)
    }
    entry(28, 0x9003, 20, 80)
    entry(40, 0x9010, 7, 100)
    entry(52, 0x9011, 7, 107)
    entry(64, 0x9012, 7, 114)
    buffer.putInt(76, 0)
    "2026:01:02 03:04:05\u0000".toByteArray().copyInto(bytes, 80)
    for (position in listOf(100, 107, 114)) (offset + "\u0000").toByteArray().copyInto(bytes, position)
    return bytes
}
