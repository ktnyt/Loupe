package jp.nano.rawgallery.camera

/**
 * 取り込み対象の拡張子と MIME・保存先種別の対応表。
 * MTP・マスストレージの両経路がここを見て対象を絞る
 */
object MediaTypes {

    /** 取り込み対象の大分類。種別指定ボタンとカメラ別の既定設定が使う */
    enum class Kind { JPEG, RAW, VIDEO }

    val ALL_KINDS: Set<Kind> = setOf(Kind.JPEG, Kind.RAW, Kind.VIDEO)

    private val IMAGE_MIME = mapOf(
        "JPG" to "image/jpeg",
        "JPEG" to "image/jpeg",
        "DNG" to "image/x-adobe-dng",
        "RW2" to "image/x-panasonic-rw2",
        "ORF" to "image/x-olympus-orf",
    )

    private val VIDEO_MIME = mapOf(
        "MP4" to "video/mp4",
        "MOV" to "video/quicktime",
    )

    private val RAW_EXTS = setOf("DNG", "RW2", "ORF")

    private fun ext(name: String) = name.substringAfterLast('.', "").uppercase()

    fun isTarget(name: String): Boolean = ext(name).let { it in IMAGE_MIME || it in VIDEO_MIME }

    fun isVideo(name: String): Boolean = ext(name) in VIDEO_MIME

    fun isRaw(name: String): Boolean = ext(name) in RAW_EXTS

    fun mimeOf(name: String): String =
        IMAGE_MIME[ext(name)] ?: VIDEO_MIME[ext(name)]
            ?: throw IllegalArgumentException("not an import target: $name")

    /** 取り込み対象外 (対象拡張子でない) は null */
    fun kindOf(name: String): Kind? = ext(name).let {
        when {
            it in VIDEO_MIME -> Kind.VIDEO
            it in RAW_EXTS -> Kind.RAW
            it in IMAGE_MIME -> Kind.JPEG
            else -> null
        }
    }

    /** DB カラムと Intent extra の受け渡し用 CSV */
    fun kindsToString(kinds: Set<Kind>): String =
        Kind.entries.filter { it in kinds }.joinToString(",")

    /** 未知のトークンは無視し、空や null は全種別に倒す (取り込み漏れの方向に倒さない) */
    fun kindsFromString(s: String?): Set<Kind> {
        val parsed = s?.split(',')
            ?.mapNotNull { t -> Kind.entries.firstOrNull { it.name == t.trim() } }
            ?.toSet()
        return if (parsed.isNullOrEmpty()) ALL_KINDS else parsed
    }
}
