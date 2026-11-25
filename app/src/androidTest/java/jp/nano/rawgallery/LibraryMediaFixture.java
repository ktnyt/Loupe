package jp.nano.rawgallery;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Small owned MediaStore inputs for the real library paths on an empty CI AVD. */
final class LibraryMediaFixture implements AutoCloseable {
  private static final int ALBUMS = 24;
  private static final int PHOTOS_PER_ALBUM = 3;
  private final Context context;
  private final List<Uri> inserted = new ArrayList<>();

  LibraryMediaFixture(Context context) throws Exception {
    if (!"ranchu".equals(Build.HARDWARE)
        || context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED
        || !new PhotoRepository(context).scan("すべて").isEmpty()) {
      throw new AssertionError("Rich library fixtures require an empty Android emulator");
    }
    this.context = context;
  }

  void seed() throws Exception {
    byte[] jpeg = jpeg();
    ContentResolver resolver = context.getContentResolver();
    Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    String root = "DCIM/GR World/Loupe-CI-" + UUID.randomUUID() + "/";
    for (int album = 0; album < ALBUMS; album++) {
      for (int photo = 0; photo < PHOTOS_PER_ALBUM; photo++) {
        ContentValues row = new ContentValues();
        row.put(MediaStore.Images.Media.DISPLAY_NAME, "Fixture-" + photo + ".jpg");
        row.put(MediaStore.Images.Media.RELATIVE_PATH, root + "Album-" + album + "/");
        row.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        row.put(MediaStore.Images.Media.DATE_TAKEN, 1700000000000L + (album * PHOTOS_PER_ALBUM + photo) * 30000L);
        row.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(collection, row);
        if (uri == null) throw new IOException("Synthetic library photo could not be created");
        inserted.add(uri);
        try (OutputStream output = resolver.openOutputStream(uri)) {
          if (output == null) throw new IOException("Synthetic library photo could not be written");
          output.write(jpeg);
        }
        ContentValues published = new ContentValues();
        published.put(MediaStore.Images.Media.IS_PENDING, 0);
        if (resolver.update(uri, published, null, null) != 1) {
          throw new IOException("Synthetic library photo could not be published");
        }
      }
    }
    rebuild();
    try (GalleryIndex index = new GalleryIndex(context)) {
      if (index.albums().size() != ALBUMS
          || index.load(null, "GR").bundles.size() != inserted.size()) {
        throw new AssertionError("Synthetic library did not produce the expected albums and groups");
      }
    }
  }

  private void rebuild() throws Exception {
    if (!GalleryIndex.rebuild(context, true, () -> false)) {
      throw new AssertionError("Synthetic library index rebuild did not publish");
    }
  }

  private static byte[] jpeg() throws IOException {
    Bitmap bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
    try {
      bitmap.eraseColor(0xff817485);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
        throw new IOException("Synthetic library JPEG could not be encoded");
      }
      return output.toByteArray();
    } finally {
      bitmap.recycle();
    }
  }

  @Override public void close() throws Exception {
    if (inserted.isEmpty()) return;
    Exception cleanup = new IOException("Synthetic library fixture cleanup failed");
    for (Uri uri : inserted) {
      try {
        if (context.getContentResolver().delete(uri, null, null) != 1) {
          cleanup.addSuppressed(new IOException("Synthetic library photo was not removed"));
        }
      } catch (RuntimeException failure) {
        cleanup.addSuppressed(failure);
      }
    }
    try {
      rebuild();
      if (!new PhotoRepository(context).scan("すべて").isEmpty()) {
        throw new IOException("Synthetic library was not empty after cleanup");
      }
    } catch (Exception failure) {
      cleanup.addSuppressed(failure);
    }
    if (cleanup.getSuppressed().length > 0) throw cleanup;
  }
}
