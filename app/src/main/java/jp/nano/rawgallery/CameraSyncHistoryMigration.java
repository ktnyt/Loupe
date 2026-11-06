package jp.nano.rawgallery;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Set;

/** Merges a consistent CameraSync SQLite snapshot into the importer's v4 history schema. */
final class CameraSyncHistoryMigration {
    record Result(int camerasAdded, int importsAdded) {}

    private static final Set<String> KINDS = Set.of("JPEG", "RAW", "VIDEO");

    private CameraSyncHistoryMigration() {}

    /** Copies a user-selected document into private storage and removes it after the merge. */
    static Result mergeDocument(Context context, Uri document, SQLiteDatabase destination)
            throws IOException {
        if (!"content".equals(document.getScheme())) {
            throw new IllegalArgumentException("Select a CameraSync database document");
        }
        File snapshot = File.createTempFile("camera-history-", ".db", context.getCacheDir());
        try {
            try (var input = context.getContentResolver().openInputStream(document);
                 var output = new FileOutputStream(snapshot)) {
                if (input == null) throw new IOException("Cannot open CameraSync database");
                byte[] buffer = new byte[64 * 1024];
                long remaining = 64L * 1024 * 1024;
                for (int count; (count = input.read(buffer)) != -1; ) {
                    remaining -= count;
                    if (remaining < 0) throw new IOException("CameraSync database is too large");
                    output.write(buffer, 0, count);
                }
            }
            return merge(snapshot, destination);
        } finally {
            SQLiteDatabase.deleteDatabase(snapshot);
        }
    }

    /**
     * The destination must be the importer's private database. Call on a worker thread,
     * outside any destination transaction. The source is opened read-only; imported values are bound as data.
     */
    static Result merge(File snapshot, SQLiteDatabase destination) {
        if (destination.inTransaction()) {
            throw new IllegalStateException("Migration needs its own transaction");
        }
        try (SQLiteDatabase source = SQLiteDatabase.openDatabase(snapshot.getPath(), null,
                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
            source.execSQL("PRAGMA trusted_schema=OFF");
            for (String table : Set.of("camera", "imported")) {
                if (!contains(source, "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?"
                        + " AND upper(sql) LIKE 'CREATE TABLE %'", table)) {
                    throw new IllegalArgumentException("Invalid CameraSync history schema");
                }
            }
            int version = source.getVersion();
            if (version < 1 || version > 4) {
                throw new IllegalArgumentException("Unsupported CameraSync history version");
            }
            destination.beginTransaction();
            try {
                int cameras = mergeCameras(source, destination, version);
                int imports = mergeImports(source, destination, version);
                destination.setTransactionSuccessful();
                return new Result(cameras, imports);
            } finally {
                destination.endTransaction();
            }
        }
    }

    private static int mergeCameras(SQLiteDatabase source, SQLiteDatabase dest, int version) {
        int added = 0;
        String settings = version >= 2 ? "default_kinds, mass_storage" : "'JPEG,RAW,VIDEO', 0";
        try (Cursor rows = source.rawQuery(
                "SELECT model, serial, folder, " + settings + " FROM camera ORDER BY id", null)) {
            while (rows.moveToNext()) {
                String model = requiredText(rows, 0);
                String serial = requiredText(rows, 1);
                String folder = requiredText(rows, 2);
                if (folder.isBlank() || folder.equals(".") || folder.equals("..")
                        || folder.indexOf('/') >= 0 || folder.indexOf('\\') >= 0) {
                    throw new IllegalArgumentException("Invalid CameraSync folder");
                }
                String kinds = requiredText(rows, 3);
                if (kinds.isEmpty()) kinds = "JPEG,RAW,VIDEO";
                for (String kind : kinds.split(",", -1)) {
                    if (!KINDS.contains(kind)) {
                        throw new IllegalArgumentException("Invalid CameraSync media type");
                    }
                }
                long massStorage = requiredLong(rows, 4);
                if (massStorage != 0 && massStorage != 1) {
                    throw new IllegalArgumentException("Invalid CameraSync connection type");
                }
                if (contains(dest, "SELECT 1 FROM camera WHERE model=? AND serial=?",
                        model, serial)) continue;
                ContentValues values = new ContentValues();
                values.put("model", model);
                values.put("serial", serial);
                values.put("folder", folder);
                values.put("default_kinds", kinds);
                values.put("mass_storage", massStorage);
                values.put("delete_after_import", 0);
                dest.insertOrThrow("camera", null, values);
                added++;
            }
        }
        return added;
    }

    private static int mergeImports(SQLiteDatabase source, SQLiteDatabase dest, int version) {
        int added = 0;
        String deletedAt = version >= 3 ? "deleted_at" : "NULL";
        try (Cursor rows = source.rawQuery(
                "SELECT camera_model, camera_serial, file_name, file_size, shot_at, imported_at, "
                        + "dest_uri, " + deletedAt + " FROM imported ORDER BY id DESC", null)) {
            while (rows.moveToNext()) {
                String uri = requiredText(rows, 6);
                // Legacy fallback matches may have been recorded without a successful read.
                if ("existing".equals(uri)) continue;
                String model = requiredText(rows, 0);
                String serial = requiredText(rows, 1);
                String filename = requiredText(rows, 2);
                long size = requiredLong(rows, 3);
                long shot = requiredLong(rows, 4);
                long imported = requiredLong(rows, 5);
                if (filename.isEmpty() || size < 0) {
                    throw new IllegalArgumentException("Invalid CameraSync import record");
                }
                if (contains(dest, "SELECT 1 FROM imported WHERE camera_model=? AND camera_serial=?"
                                + " AND file_name=? AND file_size=? AND shot_at=?",
                        model, serial, filename, Long.toString(size), Long.toString(shot))) continue;
                ContentValues values = new ContentValues();
                values.put("camera_model", model);
                values.put("camera_serial", serial);
                values.put("file_name", filename);
                values.put("file_size", size);
                values.put("shot_at", shot);
                values.put("imported_at", imported);
                values.put("dest_uri", uri);
                if (!rows.isNull(7)) values.put("deleted_at", requiredLong(rows, 7));
                dest.insertOrThrow("imported", null, values);
                added++;
            }
        }
        return added;
    }

    private static boolean contains(SQLiteDatabase database, String query, String... args) {
        try (Cursor cursor = database.rawQuery(query + " LIMIT 1", args)) {
            return cursor.moveToFirst();
        }
    }

    private static String requiredText(Cursor rows, int column) {
        if (rows.getType(column) != Cursor.FIELD_TYPE_STRING) {
            throw new IllegalArgumentException("Invalid CameraSync text value");
        }
        String value = rows.getString(column);
        if (value.indexOf(0) >= 0) throw new IllegalArgumentException("Invalid CameraSync text value");
        return value;
    }

    private static long requiredLong(Cursor rows, int column) {
        if (rows.getType(column) != Cursor.FIELD_TYPE_INTEGER) {
            throw new IllegalArgumentException("Invalid CameraSync numeric value");
        }
        return rows.getLong(column);
    }
}
