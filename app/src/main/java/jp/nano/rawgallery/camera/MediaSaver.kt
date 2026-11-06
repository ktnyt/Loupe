package jp.nano.rawgallery.camera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.OutputStream

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

    private fun collectionOf(fileName: String): Uri =
        if (MediaTypes.isVideo(fileName)) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private fun relativePathOf(fileName: String, camera: Camera): String = when {
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
        write: (OutputStream) -> Unit,
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, MediaTypes.mimeOf(fileName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathOf(fileName, camera))
            if (shotAtSec > 0) put(MediaStore.MediaColumns.DATE_TAKEN, shotAtSec * 1000)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = resolver.insert(collectionOf(fileName), values)
            ?: throw IllegalStateException("MediaStore insert failed")
        try {
            resolver.openOutputStream(target)?.use { write(it) }
                ?: throw IllegalStateException("MediaStore open failed")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            check(resolver.update(target, values, null, null) == 1) { "MediaStore publish failed" }
            return target
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            throw e
        }
    }

    /** 保存先に実在するファイル。名前照合の候補と、同一性確認 (サイズ・先頭バイト) に使う */
    data class ExistingFile(val uri: Uri, val size: Long)

    /**
     * 保存先フォルダに実在するファイルの名前→実体の対応を返す (画像・動画の両コレクション)。
     * 履歴 DB を消しても実データが残っていれば取り込み直さないための整合チェック。
     * ただし名前の一致だけでは足りない (E-P7 実測: 削除後に番号を使い回すため、
     * 同名の別写真が撮れる)。呼び出し側はサイズと先頭バイトまで確かめること。
     * 1 件ずつ問い合わせると走査前に数千回の照会で詰まるため、まとめて取る。
     */
    fun existingFiles(context: Context, camera: Camera): Map<String, ExistingFile> {
        val out = HashMap<String, ExistingFile>()
        collectFiles(
            context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "Pictures/$ROOT/${camera.folder}/", out,
        )
        collectFiles(
            context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "Pictures/$ROOT/${camera.folder}-RAW/", out,
        )
        collectFiles(
            context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            "Movies/$ROOT/${camera.folder}/", out,
        )
        return out
    }

    private fun collectFiles(
        context: Context,
        collection: Uri,
        relPath: String,
        out: MutableMap<String, ExistingFile>,
    ) {
        context.contentResolver.query(
            collection,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
            ),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(relPath),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                out[c.getString(1)] = ExistingFile(
                    ContentUris.withAppendedId(collection, c.getLong(0)),
                    c.getLong(2),
                )
            }
        }
    }

    /**
     * 名前衝突時 (番号使い回しの別写真) の保存名。撮影日時を挟んで確定的に別名にする。
     * MediaStore まかせの " (1)" 連番はギャラリーで紛らわしいため使わない
     */
    fun collisionName(original: String, shotAtSec: Long): String {
        val dot = original.lastIndexOf('.')
        val base = if (dot >= 0) original.substring(0, dot) else original
        val ext = if (dot >= 0) original.substring(dot) else ""
        val stamp =
            if (shotAtSec > 0) {
                java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date(shotAtSec * 1000))
            } else {
                System.currentTimeMillis().toString()
            }
        return "$base-$stamp$ext"
    }

    /** 実在ファイルの先頭バイト列。読めなければ null */
    fun readHead(context: Context, uri: Uri, maxBytes: Int): ByteArray? =
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
