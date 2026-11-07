package jp.nano.rawgallery.camera

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * logcat と同じ内容を端末内のファイルにも残す。
 *
 * logcat の main バッファは実測で 20〜30 分ぶんしか保たないため、出先で取り込みに
 * 失敗しても、帰ってから調べようとしたときには流れて消えている。原因を追うのに要る
 * 情報 (どの format が null を返したか、スキャンに何秒かかったか、何回目の試行で
 * 何が起きたか) は、その場に居合わせないと取れないままだった。
 *
 * 置き場所は内部ストレージ (filesDir)。Android 11 以降は
 * /sdcard/Android/data/<pkg>/ が run-as からも触れず、adb で吸い出せないため。
 *
 *   adb exec-out run-as com.kotone.camimport cat files/import-log.txt
 *
 * 出先でその場で見たいときは、画面から読ませる手を別に用意すること。
 * [init] を呼ぶ前や書き込みに失敗したときは logcat へ出すだけで、握って捨てる
 */
object ImportLog {

    private const val TAG = "ImportLog"
    private const val FILE_NAME = "import-log.txt"
    /** 1 ファイルの上限。1 回の取り込みが数 KB なので、この大きさで数百回ぶん残る */
    private const val MAX_BYTES = 256 * 1024L
    /** 世代数。現行 + 過去 [GENERATIONS] 個まで持つ */
    private const val GENERATIONS = 3

    @Volatile private var dir: File? = null
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 保存先を決める。サービスと画面のどちらから起動されても最初に通る場所で呼ぶ */
    fun init(context: Context) {
        if (dir != null) return
        dir = context.filesDir
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        append("I", tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        append("W", tag, msg)
    }

    fun w(tag: String, msg: String, e: Throwable) {
        Log.w(tag, msg, e)
        // 例外は 1 行目 (型とメッセージ) だけ残す。スタックまで入れるとファイルが
        // 埋まり、追いたい時系列が流れてしまう
        append("W", tag, "$msg | $e")
    }

    /** 記録の実体。書けなくても取り込みは続ける */
    @Synchronized
    private fun append(level: String, tag: String, msg: String) {
        val base = dir ?: return
        try {
            val f = File(base, FILE_NAME)
            if (f.length() >= MAX_BYTES) rotate(base)
            f.appendText("${stamp.format(Date())} $level/$tag: $msg\n")
        } catch (e: Exception) {
            Log.w(TAG, "failed to write log file", e)
        }
    }

    /** import-log.2.txt → .3.txt のように押し出して、現行を .1 に退避する */
    private fun rotate(base: File) {
        runCatching {
            File(base, "import-log.$GENERATIONS.txt").delete()
            for (i in GENERATIONS - 1 downTo 1) {
                val from = File(base, "import-log.$i.txt")
                if (from.exists()) from.renameTo(File(base, "import-log.${i + 1}.txt"))
            }
            File(base, FILE_NAME).renameTo(File(base, "import-log.1.txt"))
        }
    }

    /** 保存先の案内用。画面から見せるときに使う */
    fun currentFile(): File? = dir?.let { File(it, FILE_NAME) }
}
