package jp.nano.rawgallery;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.json.JSONObject;

/** Verifies the normal gallery scan with isolated archive ledgers and synthetic MediaStore rows. */
final class ArchiveScanChecks {
  private static final byte[] LOCAL = {1, 3, 5};
  private static final byte[] AMAZON = {2, 4, 6};
  private static final byte[] E2 = {7, 8, 9};

  static String run(Context target) throws Exception {
    File root = Files.createTempDirectory(target.getCacheDir().toPath(), "scan-").toFile();
    Context context =
        new ContextWrapper(target) {
          @Override
          public Context getApplicationContext() {
            return this;
          }

          @Override
          public File getFilesDir() {
            return root;
          }

          @Override
          public File getCacheDir() {
            return root;
          }

          @Override
          public File getDatabasePath(String name) {
            return new File(root, name);
          }

          @Override
          public SQLiteDatabase openOrCreateDatabase(
              String name,
              int mode,
              SQLiteDatabase.CursorFactory factory,
              DatabaseErrorHandler handler) {
            return SQLiteDatabase.openOrCreateDatabase(
                getDatabasePath(name).getPath(), factory, handler);
          }
        };
    String folder = "Pictures/LoupeScan-" + UUID.randomUUID() + "/";
    Uri local = null;
    try (ArchiveStore amazon = new ArchiveStore(context);
        ArchiveStore e2 = E2Settings.openRecords(context)) {
      var repository = new PhotoRepository(context);
      local = publish(context, folder, "local.jpg");
      Uri localFile =
          ContentUris.withAppendedId(
              MediaStore.Files.getContentUri("external"), ContentUris.parseId(local));
      var original = only(entries(repository.scan("すべて"), folder, "local.jpg"));
      check(localFile.equals(original.uri), "local-only URI");
      checkOriginal(context, original, LOCAL);
      var moveKey = ArchiveStore.key(original);
      try (PhotoMoveStore moves = new PhotoMoveStore(context)) {
        PhotoIdentityIndex.save(
            moves.getWritableDatabase(),
            original,
            new PhotoMoveStore.Identity(
                PhotoRepository.digest("original:" + folder), TagStore.source(original)),
            localFile.toString());
      }

      var moved = only(entries(repository.scan("すべて"), folder, "local.jpg"));
      String stable = ArchiveStore.key(moved);
      check(!stable.equals(moveKey), "old logical ID bound to the moved row");
      var historicPath =
          receipt(folder, "formerly-local.jpg", "amazon-moved", AMAZON).put("key", stable);
      amazon.put(historicPath, "verified");
      amazon.put(receipt(folder, "local.jpg", "amazon-local", AMAZON), "verified");
      amazon.put(receipt(folder, "amazon.jpg", "amazon-only", AMAZON), "verified");
      e2.put(receipt(folder, "e2.jpg", "e2-only", E2), E2Cleanup.RELEASED);
      amazon.put(receipt(folder, "same.jpg", "amazon-same", E2), "verified");
      e2.put(receipt(folder, "same.jpg", "e2-same", E2), E2Cleanup.RELEASED);
      amazon.put(receipt(folder, "different.jpg", "amazon-different", AMAZON), "verified");
      e2.put(receipt(folder, "different.jpg", "e2-different", E2), E2Cleanup.RELEASED);
      e2.put(receipt(folder, "local.jpg", "releasing-local", E2), E2Cleanup.RELEASING);
      e2.put(receipt(folder, "released.jpg", "verified-excluded", E2), "verified");

      var scanned = repository.scan("すべて");
      check(
          entries(scanned, folder, "formerly-local.jpg").isEmpty(),
          "old path shadowed by logical ID");
      moved = only(entries(scanned, folder, "local.jpg"));
      check(
          localFile.equals(moved.uri) && stable.equals(ArchiveStore.key(moved)),
          "moved local and releasing overlap");
      checkOriginal(context, moved, LOCAL);
      checkArchived(
          repository,
          context,
          only(entries(scanned, folder, "amazon.jpg")),
          "archive",
          "amazon-only",
          AMAZON);
      checkArchived(
          repository,
          context,
          only(entries(scanned, folder, "e2.jpg")),
          "e2archive",
          "e2-only",
          E2);
      checkArchived(
          repository,
          context,
          only(entries(scanned, folder, "same.jpg")),
          "e2archive",
          "e2-same",
          E2);
      var different = entries(scanned, folder, "different.jpg");
      check(
          different.stream()
                  .map(photo -> photo.uri.getScheme())
                  .collect(Collectors.toSet())
                  .equals(Set.of("archive", "e2archive"))
              && different.size() == 2,
          "different SHA preserves both providers");
      for (var photo : different) {
        boolean legacy = photo.uri.getScheme().equals("archive");
        checkArchived(
            repository,
            context,
            photo,
            photo.uri.getScheme(),
            legacy ? "amazon-different" : "e2-different",
            legacy ? AMAZON : E2);
      }
      check(entries(scanned, folder, "released.jpg").isEmpty(), "verified-only E2 excluded");
      return "PASS archive scan, identity, preview and cached originals";
    } finally {
      if (local != null) context.getContentResolver().delete(local, null, null);
      try (var paths = Files.walk(root.toPath())) {
        for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
          Files.delete(path);
      }
    }
  }

  private static JSONObject receipt(String folder, String name, String caseName, byte[] bytes)
      throws Exception {
    return new JSONObject()
        .put("uri", "content://media/external/file/1")
        .put("folder", folder)
        .put("name", name)
        .put("key", receiptKey(folder, caseName))
        .put(
            "sha",
            java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        .put("size", bytes.length)
        .put("modified", 2)
        .put("taken", 0)
        .put("model", caseName)
        .put("width", 1)
        .put("height", 1)
        .put("orientation", 1)
        .put("thumbWidth", 1)
        .put("thumbHeight", 1)
        .put("previewFormat", "webp");
  }

  private static String receiptKey(String folder, String caseName) throws Exception {
    String digest = PhotoRepository.digest(folder + caseName);
    // E2 record keys are 32 hex digits; retained Amazon keys use the full digest.
    return caseName.startsWith("amazon-") ? digest : digest.substring(0, 32);
  }

  private static Uri publish(Context context, String folder, String name) throws Exception {
    var resolver = context.getContentResolver();
    var values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
    values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
    values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
    Uri row = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
    if (row == null) throw new AssertionError("synthetic insertion");
    boolean published = false;
    try {
      try (var sink = resolver.openOutputStream(row)) {
        if (sink == null) throw new AssertionError("synthetic original stream");
        sink.write(LOCAL);
      }
      values.clear();
      values.put(MediaStore.MediaColumns.IS_PENDING, 0);
      check(resolver.update(row, values, null, null) == 1, "publish the local original");
      published = true;
      return row;
    } finally {
      if (!published) resolver.delete(row, null, null);
    }
  }

  private static List<PhotoRepository.Photo> entries(
      List<PhotoRepository.Photo> scanned, String folder, String name) {
    return scanned.stream()
        .filter(photo -> folder.equals(photo.folder) && name.equals(photo.name))
        .toList();
  }

  private static PhotoRepository.Photo only(List<PhotoRepository.Photo> entries) {
    check(entries.size() == 1, "expected one visible item, got " + entries.size());
    return entries.get(0);
  }

  private static void checkArchived(
      PhotoRepository repository,
      Context context,
      PhotoRepository.Photo photo,
      String scheme,
      String caseName,
      byte[] bytes)
      throws Exception {
    String key = receiptKey(photo.folder, caseName);
    check(photo.uri.toString().equals(scheme + "://" + key), "archive URI for " + caseName);
    boolean legacy = scheme.equals("archive");
    File previewDir = new File(context.getFilesDir(), legacy ? "archive" : "e2-previews");
    check(previewDir.isDirectory() || previewDir.mkdir(), "create fixture preview directory");
    File preview = new File(previewDir, key + (legacy ? ".webp" : "-thumb.webp"));
    Files.write(preview.toPath(), bytes);
    check(preview.equals(repository.sourcePreview(photo).file), "preview provider for " + caseName);
    File originals = new File(context.getCacheDir(), "originals");
    check(originals.isDirectory() || originals.mkdir(), "create fixture original cache");
    File cached = new File(originals, ArchiveStore.key(photo) + ".jpg");
    Files.write(cached.toPath(), bytes);
    checkOriginal(context, photo, bytes);
  }

  private static void checkOriginal(Context context, PhotoRepository.Photo photo, byte[] bytes)
      throws Exception {
    var recovered = OriginalCache.obtain(context, photo);
    check(
        Arrays.equals(bytes, Files.readAllBytes(new File(recovered.uri.getPath()).toPath())),
        "original data from " + photo.uri.getScheme());
  }

  private static void check(boolean valid, String label) {
    if (!valid) throw new AssertionError(label);
  }
}
