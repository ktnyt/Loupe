package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Decodes the viewer's two image stages and transfers ownership only to the current view. */
final class ViewerPhotoLoad {
  private ViewerPhotoLoad() {}

  interface Display {
    void preview(PhotoRepository.Preview source, Bitmap bitmap);

    void previewUnavailable();

    void original(PhotoRepository.Preview source, Bitmap bitmap);

    void originalUnavailable(boolean legacy);
  }

  static void read(
      Context context,
      PhotoRepository repository,
      PhotoRepository.Photo photo,
      Handler main,
      BooleanSupplier current,
      Display display) {
    try {
      PhotoRepository.Preview preview = repository.preview(photo);
      Bitmap bitmap = PhotoRepository.bitmap(preview, 1600);
      postImage(main, current, bitmap, image -> display.preview(preview, image));
    } catch (Exception missingPreview) {
      postFailure(main, current, display::previewUnavailable);
    }
    if (Thread.currentThread().isInterrupted() || !current.getAsBoolean()) return;
    try {
      PhotoRepository.Photo original = OriginalCache.obtain(context, photo);
      PhotoRepository.Preview full = repository.fullPreview(original);
      Bitmap high = PhotoRepository.bitmap(full, 4000);
      postImage(main, current, high, image -> display.original(full, image));
    } catch (Exception unavailable) {
      boolean legacy = unavailable instanceof OriginalCache.LegacyOriginalUnavailable;
      postFailure(main, current, () -> display.originalUnavailable(legacy));
    }
  }

  /** Until delivery succeeds, the load owns the decoded image. */
  static void postImage(
      Handler main, BooleanSupplier current, Bitmap bitmap, Consumer<Bitmap> show) {
    if (!main.post(
        () -> {
          if (current.getAsBoolean()) show.accept(bitmap);
          else bitmap.recycle();
        })) bitmap.recycle();
  }

  private static void postFailure(Handler main, BooleanSupplier current, Runnable failed) {
    main.post(
        () -> {
          if (current.getAsBoolean()) failed.run();
        });
  }
}
