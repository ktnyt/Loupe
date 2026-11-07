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

    /** Match only permitted source repairs; a phone timezone change must not change identity. */
    fun sameHead(cacheDirectory: File, cameraHead: ByteArray, phoneHead: ByteArray): Boolean {
        if (cameraHead.contentEquals(phoneHead)) return true
        if (cameraHead.size != phoneHead.size) return false
        val cameraCopy = File.createTempFile("camera-head-", ".bin", cacheDirectory)
        var importedCopy: File? = null
        try {
            importedCopy = File.createTempFile("imported-head-", ".bin", cacheDirectory)
            cameraCopy.writeBytes(cameraHead)
            importedCopy.writeBytes(phoneHead)
            ExifOffsetFix.patchForComparison(cameraCopy, importedCopy)
            return cameraCopy.readBytes().contentEquals(importedCopy.readBytes())
        } finally {
            cameraCopy.delete()
            importedCopy?.delete()
        }
    }
}
