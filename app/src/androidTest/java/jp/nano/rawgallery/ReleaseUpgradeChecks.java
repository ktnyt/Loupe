package jp.nano.rawgallery;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import jp.nano.rawgallery.camera.HistoryDb;
import jp.nano.rawgallery.camera.ImportedKey;

/** Exercises app-owned state across an in-place install on a disposable AVD. */
final class ReleaseUpgradeChecks {
  private static final String PACKAGE = "jp.nano.rawgallery";
  private static final String PREFS = "release-upgrade-verification";
  private static final String MODEL = "Synthetic upgrade camera";
  private static final String SERIAL = "synthetic-upgrade-fixture";
  private static final String ORIGINAL = "Loupe-upgrade-original.jpg";
  private static final String EDIT = "Loupe-upgrade-edit.jpg";
  private static final String DIR = "Pictures/LoupeUpgradeVerification";
  private static final String CREDENTIAL = "synthetic-upgrade-noncredential";
  private static final String TAG = "Synthetic upgrade tag";
  private static final int SHOT_AT = 12345678;

  static String run(Context context, boolean seed) throws Exception {
    require("ranchu".equals(Build.HARDWARE) && PACKAGE.equals(context.getPackageName()));
    var application = context.getApplicationInfo();
    var packageInfo = context.getPackageManager().getPackageInfo(PACKAGE, 0);
    boolean debuggable = (application.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    require(debuggable == seed);
    SharedPreferences witness = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    if (seed) {
      seed(context, witness, packageInfo.getLongVersionCode(), packageInfo.firstInstallTime);
      return "PASS seeded synthetic MediaStore photos, history, ranking, tags and encrypted API settings";
    }
    require(packageInfo.getLongVersionCode() > witness.getLong("version", Long.MAX_VALUE));
    require(packageInfo.firstInstallTime == witness.getLong("installed", 0));
    verify(context, witness);
    return "PASS non-debuggable upgrade kept photos, history, ranking, tags and encrypted API settings";
  }

  private static void seed(Context context, SharedPreferences witness, long version, long installed)
      throws Exception {
    NanoBananaStore settings = new NanoBananaStore(context);
    require(witness.getAll().isEmpty() && !settings.hasKey());

    byte[] original = jpeg(0xff253b5c), edit = jpeg(0xff8f6e34);
    Uri originalUri = publish(context, ORIGINAL, original), editUri = publish(context, EDIT, edit);
    ImportedKey key = new ImportedKey(ORIGINAL, original.length, SHOT_AT);
    try (HistoryDb history = new HistoryDb(context)) {
      history.recordImport(MODEL, SERIAL, key, originalUri.toString());
    }
    List<PhotoRepository.Photo> photos = new PhotoRepository(context).scan("すべて");
    PhotoRepository.Photo originalPhoto = photo(photos, originalUri, ORIGINAL);
    PhotoRepository.Photo editPhoto = photo(photos, editUri, EDIT);
    String originalKey = ArchiveStore.key(originalPhoto);
    String editKey = ArchiveStore.key(editPhoto);
    RankingStore rankings = new RankingStore(context);
    require(rankings.saveSingleBest(originalKey, false, true));
    PhotoRanking comparison = rankings.load(List.of(originalKey, editKey));
    comparison = comparison.choose(comparison.upper().equals(originalKey));
    require(comparison.best().equals(List.of(originalKey)));
    require(rankings.save(comparison));
    String tagId, photoId;
    try (TagStore tags = new TagStore(context)) {
      tagId = tags.create(TAG).id;
      photoId = tags.photoId(originalPhoto, true);
      tags.record("release-upgrade", tagId, photoId, 0, TagJudgments.Value.ASSIGNED);
    }
    settings.setOutput("16:9", "2K");
    settings.setKey(CREDENTIAL);
    require(
        witness
            .edit()
            .putLong("version", version)
            .putLong("installed", installed)
            .putString("original", originalUri.toString())
            .putString("edit", editUri.toString())
            .putInt("size", original.length)
            .putString("tag", tagId)
            .putString("tag-photo", photoId)
            .putString("original-key", originalKey)
            .putString("edit-key", editKey)
            .putString("comparison", comparison.journal)
            .commit());
  }

  private static void verify(Context context, SharedPreferences witness) throws Exception {
    Uri originalUri = Uri.parse(witness.getString("original", ""));
    Uri editUri = Uri.parse(witness.getString("edit", ""));
    try (InputStream original = context.getContentResolver().openInputStream(originalUri);
        InputStream edit = context.getContentResolver().openInputStream(editUri)) {
      require(Arrays.equals(jpeg(0xff253b5c), original.readAllBytes()));
      require(Arrays.equals(jpeg(0xff8f6e34), edit.readAllBytes()));
    }
    long size = witness.getInt("size", -1);
    ImportedKey key = new ImportedKey(ORIGINAL, size, SHOT_AT);
    try (HistoryDb history = new HistoryDb(context)) {
      require(Boolean.FALSE.equals(history.importedKeys(MODEL, SERIAL).get(key.asString())));
      require(history.importDestinations().contains(originalUri.toString()));
    }
    List<PhotoRepository.Photo> photos = new PhotoRepository(context).scan("すべて");
    PhotoRepository.Photo originalPhoto = photo(photos, originalUri, ORIGINAL);
    PhotoRepository.Photo editPhoto = photo(photos, editUri, EDIT);
    String originalKey = ArchiveStore.key(originalPhoto);
    String editKey = ArchiveStore.key(editPhoto);
    require(originalKey.equals(witness.getString("original-key", "")));
    require(editKey.equals(witness.getString("edit-key", "")));
    RankingStore rankings = new RankingStore(context);
    require(rankings.singleBest(originalKey));
    PhotoRanking comparison = rankings.load(List.of(originalKey, editKey));
    require(comparison.journal.equals(witness.getString("comparison", "")));
    require(comparison.done() && comparison.order.equals(List.of(originalKey, editKey)));
    require(
        comparison.best().equals(List.of(originalKey))
            && comparison.selectionCount(originalKey) == 1);
    require(comparison.selectionCount(editKey) == 0);
    try (TagStore tags = new TagStore(context)) {
      String tagId = witness.getString("tag", "");
      require(tags.tags().stream().anyMatch(tag -> TAG.equals(tag.name) && tagId.equals(tag.id)));
      String photoId = tags.photoId(originalPhoto, false);
      require(Objects.equals(photoId, witness.getString("tag-photo", null)));
      require(tags.load().value(tagId, photoId) == TagJudgments.Value.ASSIGNED);
    }
    NanoBananaStore settings = new NanoBananaStore(context);
    require(CREDENTIAL.equals(settings.apiKey()));
    NanoBananaOutput output = settings.output();
    require("16:9".equals(output.aspectRatio) && "2K".equals(output.resolution));
  }

  private static PhotoRepository.Photo photo(
      List<PhotoRepository.Photo> photos, Uri uri, String name) {
    long id = ContentUris.parseId(uri);
    return photos.stream()
        .filter(
            photo ->
                name.equals(photo.name)
                    && !photo.isArchived()
                    && ContentUris.parseId(photo.uri) == id)
        .findFirst()
        .orElseThrow(() -> new AssertionError("Synthetic photo not visible in library"));
  }

  private static byte[] jpeg(int color) {
    Bitmap image = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888);
    try {
      image.eraseColor(color);
      var bytes = new java.io.ByteArrayOutputStream();
      require(image.compress(Bitmap.CompressFormat.JPEG, 95, bytes));
      return bytes.toByteArray();
    } finally {
      image.recycle();
    }
  }

  private static Uri publish(Context context, String name, byte[] bytes) throws Exception {
    var resolver = context.getContentResolver();
    ContentValues values = new ContentValues();
    values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
    values.put(MediaStore.Images.Media.RELATIVE_PATH, DIR);
    values.put(MediaStore.Images.Media.IS_PENDING, 1);
    Uri uri =
        Objects.requireNonNull(
            resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values));
    try (OutputStream out = resolver.openOutputStream(uri)) {
      out.write(bytes);
    }
    values.clear();
    values.put(MediaStore.Images.Media.IS_PENDING, 0);
    require(resolver.update(uri, values, null, null) == 1);
    return uri;
  }

  private static void require(boolean condition) {
    if (!condition) throw new AssertionError("Release upgrade fixture check failed");
  }
}
