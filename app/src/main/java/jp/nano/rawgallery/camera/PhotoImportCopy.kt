package jp.nano.rawgallery.camera

import java.io.File
import java.io.OutputStream

/** Prepares a verified photo copy; camera bytes and already imported media are never modified. */
object PhotoImportCopy {
    fun <T> prepare(
        cacheDirectory: File,
        fileName: String,
        expectedBytes: Long,
        copy: (OutputStream) -> Unit,
        use: (File) -> T,
    ): T {
        require(expectedBytes > 0) { "Photo size is unknown" }
        val temporary = File.createTempFile(
            "camera-photo-", "." + fileName.substringAfterLast('.', "bin"), cacheDirectory,
        )
        try {
            temporary.outputStream().use(copy)
            check(temporary.length() == expectedBytes) { "Photo transfer size could not be verified" }
            ExifOffsetFix.patchIfBroken(temporary)
            return use(temporary)
        } finally {
            temporary.delete()
        }
    }

    /** Offset repair changes only the imported header, so compare those headers in the same form. */
    fun sameHead(cacheDirectory: File, cameraHead: ByteArray, phoneHead: ByteArray): Boolean {
        if (cameraHead.contentEquals(phoneHead)) return true
        if (cameraHead.size != phoneHead.size) return false
        return normalizedHead(cacheDirectory, cameraHead).contentEquals(
            normalizedHead(cacheDirectory, phoneHead),
        )
    }

    private fun normalizedHead(cacheDirectory: File, bytes: ByteArray): ByteArray {
        val temporary = File.createTempFile("camera-head-", ".bin", cacheDirectory)
        try {
            temporary.writeBytes(bytes)
            ExifOffsetFix.patchIfBroken(temporary)
            return temporary.readBytes()
        } finally {
            temporary.delete()
        }
    }
}
