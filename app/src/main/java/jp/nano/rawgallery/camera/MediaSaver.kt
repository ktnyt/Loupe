package jp.nano.rawgallery.camera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import jp.nano.rawgallery.PhotoStorage
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.function.BooleanSupplier

/**
 * MediaStore への保存先はファイル種別で分ける。取り込み方式に依らず共通。
 * JPEG: Pictures/CameraSync/<機種名>/、RAW: Pictures/CameraSync/<機種名>-RAW/、
 * 動画: Movies/CameraSync/<機種名>/。
 * RAW をサブフォルダにしないのは、ギャラリー系アプリがアルバムをフォルダの
 * 末端名で表示するため。「RAW」という同名アルバムが機種の数だけ並ぶのを避ける
 */
object MediaSaver {
    /** 保存先ルート。CameraSync と同じ保存先を維持する */
    const val ROOT = "CameraSync"

    private fun collectionOf(
        fileName: String,
        volume: String,
    ): Uri =
        if (MediaTypes.isVideo(fileName)) {
            MediaStore.Video.Media.getContentUri(volume)
        } else {
            MediaStore.Images.Media.getContentUri(volume)
        }

    private fun relativePathOf(
        fileName: String,
        camera: Camera,
    ): String =
        when {
            MediaTypes.isVideo(fileName) -> "Movies/$ROOT/${camera.folder}"
            MediaTypes.isRaw(fileName) -> "Pictures/$ROOT/${camera.folder}-RAW"
            else -> "Pictures/$ROOT/${camera.folder}"
        }

    /** [write] に保存先の OutputStream を渡してファイル本体を書かせる。保存先 Uri を返す */
    fun save(
        context: Context,
        fileName: String,
        shotAtSec: Long,
        camera: Camera,
        volume: String,
        expectedBytes: Long,
        write: (OutputStream) -> Unit,
    ): Uri {
        PhotoStorage.requireSpace(context, volume, expectedBytes)
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, MediaTypes.mimeOf(fileName))
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathOf(fileName, camera))
                if (shotAtSec > 0) put(MediaStore.MediaColumns.DATE_TAKEN, shotAtSec * 1000)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val target =
            resolver.insert(collectionOf(fileName, volume), values)
                ?: throw IllegalStateException("MediaStore insert failed")
        try {
            val fd =
                resolver.openFileDescriptor(target, "w")
                    ?: throw IllegalStateException("MediaStore open failed")
            ParcelFileDescriptor.AutoCloseOutputStream(fd).use { output ->
                write(output)
                output.fd.sync()
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            check(resolver.update(target, values, null, null) == 1) { "MediaStore publish failed" }
            return target
        } catch (e: Exception) {
            try {
                resolver.delete(target, MediaStore.MediaColumns.IS_PENDING + "=1", null)
            } catch (cleanup: Exception) {
                e.addSuppressed(cleanup)
            }
            throw e
        }
    }

    data class SavedFingerprint(
        val bytes: Long,
        val sha256: String,
    )

    /** Read all published bytes before allowing deletion of the only camera original. */
    fun canReadSavedFile(
        context: Context,
        uri: Uri,
        expected: SavedFingerprint,
        cancelled: BooleanSupplier,
    ): Boolean =
        try {
            PhotoStorage.requireMounted(context, uri)
            val resolver = context.contentResolver

            fun revision(): Long? =
                resolver
                    .query(
                        uri,
                        arrayOf(
                            MediaStore.MediaColumns.IS_PENDING,
                            MediaStore.MediaColumns.IS_TRASHED,
                            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                            MediaStore.MediaColumns.SIZE,
                            MediaStore.MediaColumns.GENERATION_MODIFIED,
                        ),
                        null,
                        null,
                        null,
                    )?.use { row ->
                        if (row.moveToFirst() && row.getInt(0) == 0 && row.getInt(1) == 0 &&
                            row.getString(2) == context.packageName && row.getLong(3) == expected.bytes && !row.isNull(4)
                        ) {
                            row.getLong(4)
                        } else {
                            null
                        }
                    }
            val before = revision()
            val matches =
                before != null && resolver
                    .openInputStream(MediaStore.setRequireOriginal(uri))
                    ?.use { matchesSavedBytes(it, expected.bytes, expected.sha256, cancelled) } == true
            PhotoStorage.requireMounted(context, uri)
            matches && !cancelled.getAsBoolean() && before == revision()
        } catch (e: Exception) {
            ImportLog.w("MediaSaver", "saved media unavailable before camera deletion")
            false
        }

    /** Bounded reads cover exactly the independently fingerprinted staged bytes, including EOF. */
    fun matchesSavedBytes(
        input: InputStream,
        bytes: Long,
        sha256: String,
        cancelled: BooleanSupplier,
    ): Boolean {
        if (bytes <= 0 || !sha256.matches(Regex("[0-9a-f]{64}"))) return false
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var read = 0L
        try {
            while (!cancelled.getAsBoolean() && !Thread.currentThread().isInterrupted) {
                val count = input.read(buffer)
                if (count == -1) {
                    val alphabet = "0123456789abcdef"
                    val actual =
                        buildString(64) {
                            for (byte in digest.digest()) {
                                val value = byte.toInt() and 255
                                append(alphabet[value ushr 4])
                                append(alphabet[value and 15])
                            }
                        }
                    return !cancelled.getAsBoolean() && !Thread.currentThread().isInterrupted && read == bytes && actual == sha256
                }
                if (count <= 0 || count > bytes - read) return false
                digest.update(buffer, 0, count)
                read += count
            }
        } catch (unreadable: java.io.IOException) {
            return false
        }
        return false
    }

    /** 保存先に実在するファイル。名前照合の候補と、同一性確認 (サイズ・先頭バイト) に使う */
    data class ExistingFile(
        val uri: Uri,
        val size: Long,
    )

    /**
     * 保存先フォルダに実在するファイルの名前→実体の対応を返す (画像・動画の両コレクション)。
     * 履歴 DB を消しても実データが残っていれば取り込み直さないための整合チェック。
     * ただし名前の一致だけでは足りない (E-P7 実測: 削除後に番号を使い回すため、
     * 同名の別写真が撮れる)。呼び出し側はサイズと先頭バイトまで確かめること。
     * 1 件ずつ問い合わせると走査前に数千回の照会で詰まるため、まとめて取る。
     */
    fun existingFiles(
        context: Context,
        camera: Camera,
    ): Map<String, ExistingFile> {
        val out = HashMap<String, ExistingFile>()
        collectFiles(
            context,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "Pictures/$ROOT/${camera.folder}/",
            out,
        )
        collectFiles(
            context,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "Pictures/$ROOT/${camera.folder}-RAW/",
            out,
        )
        collectFiles(
            context,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            "Movies/$ROOT/${camera.folder}/",
            out,
        )
        return out
    }

    /** One metadata query for the complete SD history. A failed/null query stays unknown. */
    fun photoDestinationSnapshot(context: Context): Map<String, CameraImportHistoryPlan.Destination>? {
        val permission =
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                android.Manifest.permission.READ_MEDIA_IMAGES
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
        if (context.checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        return try {
            val out = HashMap<String, CameraImportHistoryPlan.Destination>()
            val args =
                android.os.Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                }
            val cursor =
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.IS_PENDING,
                        MediaStore.MediaColumns.IS_TRASHED,
                        MediaStore.MediaColumns.VOLUME_NAME,
                    ),
                    args,
                    null,
                ) ?: return null
            cursor.use { c ->
                while (c.moveToNext()) {
                    if (c.isNull(1)) continue
                    val row = CameraImportHistoryPlan.Destination(c.getLong(1), c.getInt(2) == 0 && c.getInt(3) == 0)
                    val id = c.getLong(0)
                    val volume = c.getString(4) ?: continue
                    out["content://media/$volume/images/media/$id"] = row
                    if (volume == PhotoStorage.PRIMARY) out["content://media/external/images/media/$id"] = row
                }
            }
            out
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun collectFiles(
        context: Context,
        collection: Uri,
        relPath: String,
        out: MutableMap<String, ExistingFile>,
    ) {
        context.contentResolver
            .query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.VOLUME_NAME,
                ),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(relPath),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val volume = c.getString(3) ?: continue
                    val actualCollection =
                        if (collection == MediaStore.Video.Media.EXTERNAL_CONTENT_URI) {
                            MediaStore.Video.Media.getContentUri(volume)
                        } else {
                            MediaStore.Images.Media.getContentUri(volume)
                        }
                    out[c.getString(1)] =
                        ExistingFile(
                            ContentUris.withAppendedId(actualCollection, c.getLong(0)),
                            c.getLong(2),
                        )
                }
            }
    }

    /**
     * 名前衝突時 (番号使い回しの別写真) の保存名。撮影日時を挟んで確定的に別名にする。
     * MediaStore まかせの " (1)" 連番はギャラリーで紛らわしいため使わない
     */
    fun collisionName(
        original: String,
        shotAtSec: Long,
    ): String {
        val dot = original.lastIndexOf('.')
        val base = if (dot >= 0) original.substring(0, dot) else original
        val ext = if (dot >= 0) original.substring(dot) else ""
        val stamp =
            if (shotAtSec > 0) {
                java.text
                    .SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date(shotAtSec * 1000))
            } else {
                System.currentTimeMillis().toString()
            }
        return "$base-$stamp$ext"
    }

    /** 実在ファイルの先頭バイト列。読めなければ null */
    fun readHead(
        context: Context,
        uri: Uri,
        maxBytes: Int,
    ): ByteArray? =
        try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val buf = ByteArray(maxBytes)
                var off = 0
                while (off < maxBytes) {
                    val n = ins.read(buf, off, maxBytes - off)
                    if (n < 0) break
                    off += n
                }
                buf.copyOf(off)
            }
        } catch (e: Exception) {
            null
        }
}
