package jp.nano.rawgallery;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Exercises Android SQLite history migration with disposable databases. */
public final class CameraSyncHistoryMigrationCheck {
  static String run(Context context) throws IOException {
    File dir =
        Files.createTempDirectory(context.getCacheDir().toPath(), "history-migration-").toFile();
    File input = new File(dir, "source.db");
    File output = new File(dir, "target.db");
    try (SQLiteDatabase source = create(input);
        SQLiteDatabase target = create(output)) {
      source.execSQL("INSERT INTO camera VALUES (1,'PEN','serial','PEN',1,'RAW',0)");
      source.execSQL(
          "INSERT INTO imported VALUES (1,'PEN','serial','shot.ORF',9,1,2,'content://media/external/images/media/1',NULL)");
      source.execSQL(
          "INSERT INTO imported VALUES (2,'PEN','serial','shot.ORF',9,1,3,'content://media/external/images/media/1',4)");
      source.execSQL(
          "INSERT INTO imported VALUES (3,'PEN','serial','unknown.ORF',9,1,3,'existing',NULL)");
      var first = CameraSyncHistoryMigration.merge(input, target);
      check(first.camerasAdded() == 1 && first.importsAdded() == 1);
      check(value(target, "SELECT delete_after_import FROM camera") == 0);
      check(value(target, "SELECT deleted_at FROM imported") == 4);
      check(value(source, "SELECT delete_after_import FROM camera") == 1);
      target.execSQL("UPDATE camera SET folder='New folder'");
      var repeat = CameraSyncHistoryMigration.merge(input, target);
      check(repeat.camerasAdded() == 0 && repeat.importsAdded() == 0);
      check(value(target, "SELECT COUNT(*) FROM camera WHERE folder='New folder'") == 1);
      source.execSQL("INSERT INTO camera VALUES (2,'Other','serial','Other',0,'RAW',0)");
      source.execSQL(
          "INSERT INTO imported VALUES (4,'Other','serial','bad.ORF',-1,1,2,'content://media/external/images/media/1',NULL)");
      try {
        CameraSyncHistoryMigration.merge(input, target);
        throw new AssertionError("Invalid migration succeeded");
      } catch (IllegalArgumentException expected) {
        check(value(target, "SELECT COUNT(*) FROM camera") == 1);
        check(value(target, "SELECT COUNT(*) FROM imported") == 1);
      }
    } finally {
      SQLiteDatabase.deleteDatabase(input);
      SQLiteDatabase.deleteDatabase(output);
      Files.delete(dir.toPath());
    }
    return "PASS CameraSync history migration";
  }

  private static SQLiteDatabase create(File file) {
    SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
    db.execSQL(
        "CREATE TABLE camera(id INTEGER PRIMARY KEY, model TEXT, serial TEXT, folder TEXT,"
            + "delete_after_import INTEGER, default_kinds TEXT, mass_storage INTEGER)");
    db.execSQL(
        "CREATE TABLE imported(id INTEGER PRIMARY KEY, camera_model TEXT, camera_serial TEXT,"
            + "file_name TEXT, file_size INTEGER, shot_at INTEGER, imported_at INTEGER,"
            + "dest_uri TEXT, deleted_at INTEGER)");
    db.setVersion(4);
    return db;
  }

  private static long value(SQLiteDatabase db, String query) {
    return DatabaseUtils.longForQuery(db, query, null);
  }

  private static void check(boolean condition) {
    if (!condition) throw new AssertionError("Migration contract failed");
  }
}
