package jp.nano.rawgallery.camera

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Camera(
    val model: String,
    val serial: String,
    val folder: String,
    /** 取り込み完了後にカメラ側の元ファイルを消すか。PTP/MTP 接続のときだけ効く */
    val deleteAfterImport: Boolean = false,
    /** 自動取り込みと「接続中のカメラから取り込み」が対象にする種別 */
    val defaultKinds: Set<MediaTypes.Kind> = MediaTypes.ALL_KINDS,
    /** 直近の接続がマスストレージだったか。削除設定を出せるかの表示に使う */
    val massStorage: Boolean = false,
)

data class ImportedKey(
    val fileName: String,
    val fileSize: Long,
    val shotAt: Long,
) {
    fun asString() = "$fileName|$fileSize|$shotAt"
}

/**
 * 取り込み履歴とカメラ別設定。スキーマは仕様書 5.2 に従う
 * (Room ではなく素の SQLite。テーブル 2 つの規模で依存を増やさないため)
 */
class HistoryDb(context: Context) : SQLiteOpenHelper(context, "camera-import-history.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE camera (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                model TEXT NOT NULL,
                serial TEXT NOT NULL,
                folder TEXT NOT NULL,
                delete_after_import INTEGER NOT NULL DEFAULT 0,
                default_kinds TEXT NOT NULL DEFAULT 'JPEG,RAW,VIDEO',
                mass_storage INTEGER NOT NULL DEFAULT 0,
                UNIQUE(model, serial)
            )"""
        )
        db.execSQL(
            """CREATE TABLE imported (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                camera_model TEXT NOT NULL,
                camera_serial TEXT NOT NULL,
                file_name TEXT NOT NULL,
                file_size INTEGER NOT NULL,
                shot_at INTEGER NOT NULL,
                imported_at INTEGER NOT NULL,
                dest_uri TEXT NOT NULL,
                deleted_at INTEGER
            )"""
        )
        db.execSQL(
            "CREATE INDEX idx_imported_key ON imported(camera_model, camera_serial, file_name, file_size, shot_at)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // カメラ別設定 (v2): 取り込み後削除・既定の取り込み種別・接続方式の記憶
            db.execSQL("ALTER TABLE camera ADD COLUMN delete_after_import INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE camera ADD COLUMN default_kinds TEXT NOT NULL DEFAULT 'JPEG,RAW,VIDEO'")
            db.execSQL("ALTER TABLE camera ADD COLUMN mass_storage INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            // v3: カメラ側から削除した記録。E-P7 実測で削除が復活することがあり、
            // 次回スキャンで「削除したはずの分」を検出して正直に報告するために使う
            db.execSQL("ALTER TABLE imported ADD COLUMN deleted_at INTEGER")
        }
        if (oldVersion < 4) {
            // v4: 名前だけの実在照合が番号使い回しの別写真を誤スキップして残した
            // "existing" 記録を一掃する。実在照合は次回スキャンで内容比較つきで
            // 再判定されるため、消しても二重取り込みにはならない
            db.execSQL("DELETE FROM imported WHERE dest_uri='existing'")
        }
    }

    fun findCamera(model: String, serial: String): Camera? =
        readableDatabase.rawQuery(
            "SELECT folder, delete_after_import, default_kinds, mass_storage " +
                "FROM camera WHERE model=? AND serial=?",
            arrayOf(model, serial),
        ).use { c ->
            if (c.moveToFirst()) {
                Camera(
                    model, serial, c.getString(0),
                    deleteAfterImport = c.getInt(1) != 0,
                    defaultKinds = MediaTypes.kindsFromString(c.getString(2)),
                    massStorage = c.getInt(3) != 0,
                )
            } else null
        }

    fun saveCamera(camera: Camera) {
        writableDatabase.execSQL(
            "INSERT INTO camera(model, serial, folder, delete_after_import, default_kinds, mass_storage) " +
                "VALUES(?,?,?,?,?,?) " +
                "ON CONFLICT(model, serial) DO UPDATE SET folder=excluded.folder, " +
                "delete_after_import=excluded.delete_after_import, " +
                "default_kinds=excluded.default_kinds, mass_storage=excluded.mass_storage",
            arrayOf<Any>(
                camera.model, camera.serial, camera.folder,
                if (camera.deleteAfterImport) 1 else 0,
                MediaTypes.kindsToString(camera.defaultKinds),
                if (camera.massStorage) 1 else 0,
            ),
        )
    }

    fun listCameras(): List<Camera> =
        readableDatabase.rawQuery(
            "SELECT model, serial, folder, delete_after_import, default_kinds, mass_storage " +
                "FROM camera ORDER BY model",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Camera(
                            c.getString(0), c.getString(1), c.getString(2),
                            deleteAfterImport = c.getInt(3) != 0,
                            defaultKinds = MediaTypes.kindsFromString(c.getString(4)),
                            massStorage = c.getInt(5) != 0,
                        )
                    )
                }
            }
        }

    /**
     * そのカメラの取り込み済みキー全件。走査時にメモリ上で照合する。
     * 値は「カメラ側から削除済みと記録したか」。削除の復活検出に使う
     */
    fun importedKeys(model: String, serial: String): HashMap<String, Boolean> =
        readableDatabase.rawQuery(
            "SELECT file_name, file_size, shot_at, deleted_at FROM imported " +
                "WHERE camera_model=? AND camera_serial=?",
            arrayOf(model, serial),
        ).use { c ->
            val map = HashMap<String, Boolean>(c.count * 2)
            while (c.moveToNext()) {
                map[ImportedKey(c.getString(0), c.getLong(1), c.getLong(2)).asString()] = !c.isNull(3)
            }
            map
        }

    /** カメラ側からの削除に成功した記録。復活検出の基準になる */
    fun markDeleted(model: String, serial: String, key: ImportedKey) {
        writableDatabase.execSQL(
            "UPDATE imported SET deleted_at=? WHERE camera_model=? AND camera_serial=? " +
                "AND file_name=? AND file_size=? AND shot_at=?",
            arrayOf<Any>(
                System.currentTimeMillis() / 1000,
                model, serial, key.fileName, key.fileSize, key.shotAt,
            ),
        )
    }

    fun recordImport(model: String, serial: String, key: ImportedKey, destUri: String) {
        val v = ContentValues().apply {
            put("camera_model", model)
            put("camera_serial", serial)
            put("file_name", key.fileName)
            put("file_size", key.fileSize)
            put("shot_at", key.shotAt)
            put("imported_at", System.currentTimeMillis() / 1000)
            put("dest_uri", destUri)
        }
        writableDatabase.insertOrThrow("imported", null, v)
    }

    fun importedCount(model: String, serial: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM imported WHERE camera_model=? AND camera_serial=?",
            arrayOf(model, serial),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** 履歴の手動リセット (仕様書 Phase 3)。次回接続で全件が取り込み対象に戻る */
    fun resetHistory(model: String, serial: String) {
        writableDatabase.delete(
            "imported", "camera_model=? AND camera_serial=?", arrayOf(model, serial)
        )
    }

    companion object {
        @Volatile private var instance: HistoryDb? = null
        fun get(context: Context): HistoryDb =
            instance ?: synchronized(this) {
                instance ?: HistoryDb(context.applicationContext).also { instance = it }
            }
    }
}
