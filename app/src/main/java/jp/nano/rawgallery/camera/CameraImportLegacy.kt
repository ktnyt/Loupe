package jp.nano.rawgallery.camera

import android.content.ContentUris
import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.util.function.BooleanSupplier

/** Read-only adoption of existing CameraSync files. Never treats a name or a prefix as content proof. */
object CameraImportLegacy {
    data class Evidence(val uri: String, val sha256: String, val bytes: Long)
    data class Metadata(val model: String, val captureSeconds: Long)

    @JvmStatic fun metadata(file: File, fallbackSeconds: Long): Metadata = try {
        val exif=ExifInterface(file)
        val captured=exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let {
            java.time.LocalDateTime.parse(it, java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", java.util.Locale.ROOT))
                .atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
        } ?: fallbackSeconds
        Metadata(exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty(),if(captured>0)captured else fallbackSeconds)
    } catch (_: Exception) { Metadata("",fallbackSeconds) }

    @JvmStatic fun existing(context: Context, source: CameraImportFile, raw: File, cancelled: BooleanSupplier): Evidence? =
        existing(context, source, raw, cancelled, java.util.function.LongConsumer { })

    @JvmStatic fun existing(context: Context, source: CameraImportFile, raw: File, cancelled: BooleanSupplier,
        readCounter: java.util.function.LongConsumer): Evidence? {
        val resolver=context.contentResolver
        var unreadable=false
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID,MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.VOLUME_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.SIZE}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
            arrayOf("Pictures/CameraSync/%",source.bytes.toString()),null)?.use { cursor ->
            if(cursor.count>2048)throw IOException("Too many unverified existing candidates")
            while(cursor.moveToNext()) {
                CameraPhotoPublisher.check(cancelled)
                val volume=cursor.getString(2) ?: continue
                val uri=ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volume),cursor.getLong(0))
                val candidate=File.createTempFile("camera-existing-",".part",context.cacheDir)
                val comparison=File.createTempFile("camera-compare-",".part",context.cacheDir)
                try {
                    val wanted=cursor.getLong(1)
                    try {
                        resolver.openInputStream(uri)?.use { input ->
                            candidate.outputStream().use { out ->
                                val buffer=ByteArray(65536);var count=0L
                                while(true){CameraPhotoPublisher.check(cancelled);val n=input.read(buffer);if(n<0)break;count+=n;readCounter.accept(n.toLong());if(count>wanted)throw IOException("Existing size changed");out.write(buffer,0,n)}
                                if(count!=wanted)throw IOException("Existing size changed")
                            }
                        }?:throw IOException("Existing photo unavailable")
                    } catch(e: IOException){CameraPhotoPublisher.check(cancelled);unreadable=true;continue}
                    val stored=CameraImportCopy.sha256(candidate,cancelled)
                    if(CameraImportCopy.sha256(raw,cancelled)==stored)return Evidence(uri.toString(),stored,wanted)
                    raw.inputStream().use { input -> comparison.outputStream().use { out ->
                        val buf=ByteArray(65536);while(true){CameraPhotoPublisher.check(cancelled);val n=input.read(buf);if(n<0)break;out.write(buf,0,n)}
                    } }
                    ExifOffsetFix.patchForComparison(comparison,candidate)
                    if(CameraImportCopy.sha256(comparison,cancelled)==CameraImportCopy.sha256(candidate,cancelled))return Evidence(uri.toString(),stored,wanted)
                } finally { candidate.delete();comparison.delete() }
            }
        }?:throw IOException("Existing photo index unavailable")
        if(unreadable)throw IOException("Existing photos could not be verified")
        return null
    }
}
