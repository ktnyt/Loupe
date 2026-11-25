package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class ViewerPhotoLoadChecks {
  static String run(Instrumentation test) throws Exception {
    var context = test.getTargetContext();
    File folder =
        Files.createTempDirectory(context.getCacheDir().toPath(), "viewer-load-fixture-").toFile();
    MainActivity original =
        (MainActivity)
            test.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    MainActivity[] recreated = {null};
    try {
      GalleryScreenChecks.finishInitialIndex(test, original);
      PhotoRepository.Photo first = jpg(folder, "first.jpg", 96, Color.RED);
      PhotoRepository.Photo second = jpg(folder, "second.jpg", 120, Color.GREEN);
      PhotoRepository.Photo missing =
          new PhotoRepository.Photo(
              Uri.fromFile(new File(folder, "missing.jpg")),
              "missing.jpg",
              folder.getPath(),
              128,
              0,
              0);
      checkStages(test, original, first);
      TagDetailChecks.ui(
          test,
          () -> {
            RankingResultChecks.set(original, "photos", List.of(first, second, missing));
            RankingResultChecks.set(
                original, "bundles", List.of(List.of(0), List.of(1), List.of(2)));
            RankingResultChecks.set(original, "albumFolder", folder.getPath());
            RankingResultChecks.set(original, "burstMembers", null);
            RankingResultChecks.set(original, "pendingRestore", null);
            RankingResultChecks.call(original, "gallery");
          });
      GalleryScreenChecks.settle(test, original);

      PhotoViewerUi[] superseded = new PhotoViewerUi[3];
      TagDetailChecks.ui(
          test,
          () -> {
            View tile =
                GalleryScreenChecks.firstPhoto((View) RankingResultChecks.get(original, "grid"));
            require(tile != null && tile.performClick(), "normal gallery photo cell click");
            superseded[0] = viewer(original);
            awaitWorker(original);
            click(original, "次の写真");
          });
      waitForFrame(test, original, 120);
      TagDetailChecks.ui(
          test,
          () -> {
            require(
                bitmap(superseded[0]) == null,
                "superseded success did not publish to its old view");
            click(original, "次の写真");
            superseded[1] = viewer(original);
            awaitWorker(original);
            click(original, "前の写真");
          });
      waitForFrame(test, original, 120);
      TagDetailChecks.ui(
          test,
          () -> {
            require(
                superseded[1].stage.findViewWithTag("viewer-original-recovery") == null,
                "old failure did not change its detached view");
            click(original, "次の写真");
          });
      awaitWorker(original);
      test.waitForIdleSync();
      TagDetailChecks.ui(
          test,
          () -> {
            require(
                viewer(original).stage.findViewWithTag("viewer-original-recovery") != null,
                "current failure still offers retry");
            click(original, "前の写真");
          });
      waitForFrame(test, original, 120);
      TagDetailChecks.ui(
          test,
          () -> {
            click(original, "前の写真");
            superseded[2] = viewer(original);
            awaitWorker(original);
            original.onBackPressed();
          });
      test.waitForIdleSync();
      TagDetailChecks.ui(
          test,
          () ->
              require(
                  bitmap(superseded[2]) == null
                      && !(boolean) RankingResultChecks.get(original, "detail"),
                  "leaving the viewer discards its pending image"));
      checkRecreation(test, original, recreated);
      return "PASS viewer stages and gallery click, rapid next/previous, stale/current failure, Back, "
          + "recreation, image transfer and rejected delivery";
    } finally {
      TagDetailChecks.ui(
          test,
          () -> {
            if (recreated[0] != null) recreated[0].finish();
            if (!original.isDestroyed()) original.finish();
          });
      test.waitForIdleSync();
      File[] created = folder.listFiles();
      if (created != null) for (File file : created) Files.deleteIfExists(file.toPath());
      Files.deleteIfExists(folder.toPath());
    }
  }

  private static void checkStages(
      Instrumentation test, MainActivity activity, PhotoRepository.Photo photo) {
    List<String> stages = new ArrayList<>();
    List<Bitmap> images = new ArrayList<>();
    ViewerPhotoLoad.read(
        activity,
        new PhotoRepository(activity),
        photo,
        new Handler(activity.getMainLooper()),
        () -> true,
        new ViewerPhotoLoad.Display() {
          @Override
          public void preview(PhotoRepository.Preview source, Bitmap image) {
            stages.add("preview");
            images.add(image);
          }

          @Override
          public void previewUnavailable() {
            stages.add("preview failed");
          }

          @Override
          public void original(PhotoRepository.Preview source, Bitmap image) {
            stages.add("original");
            images.add(image);
          }

          @Override
          public void originalUnavailable() {
            stages.add("original failed");
          }
        });
    test.waitForIdleSync();
    TagDetailChecks.ui(
        test,
        () -> {
          require(stages.equals(List.of("preview", "original")), "staged loading order");
          try {
            require(
                images.get(0) != images.get(1) && images.stream().noneMatch(Bitmap::isRecycled),
                "two live images transferred to the receiver");
          } finally {
            for (Bitmap image : images) if (!image.isRecycled()) image.recycle();
          }
        });
  }

  private static void checkRecreation(
      Instrumentation test, MainActivity old, MainActivity[] recreated) throws Exception {
    HandlerThread delivery = new HandlerThread("viewer-fixture-delivery");
    Bitmap delayed = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
    Bitmap rejected = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
    CountDownLatch release = new CountDownLatch(1), delivered = new CountDownLatch(1);
    AtomicBoolean attached = new AtomicBoolean();
    Instrumentation.ActivityMonitor monitor =
        test.addMonitor(MainActivity.class.getName(), null, false);
    delivery.start();
    Handler handler = new Handler(delivery.getLooper());
    try {
      handler.post(
          () -> {
            try {
              release.await();
            } catch (InterruptedException stop) {
              Thread.currentThread().interrupt();
            }
          });
      ViewerPhotoLoad.postImage(
          handler, () -> !old.isDestroyed(), delayed, image -> attached.set(true));
      handler.post(delivered::countDown);
      TagDetailChecks.ui(test, old::recreate);
      Activity next = test.waitForMonitorWithTimeout(monitor, 15000);
      require(next instanceof MainActivity && next != old, "recreation reached a new activity");
      recreated[0] = (MainActivity) next;
      test.waitForIdleSync();
      require(old.isDestroyed(), "old activity destroyed before delayed delivery");
      release.countDown();
      require(
          delivered.await(10, TimeUnit.SECONDS) && delayed.isRecycled() && !attached.get(),
          "destroyed activity discards its pending bitmap");
      delivery.quitSafely();
      delivery.join(10000);
      require(!delivery.isAlive(), "fixture delivery loop stopped");
      ViewerPhotoLoad.postImage(handler, () -> true, rejected, image -> attached.set(true));
      require(rejected.isRecycled() && !attached.get(), "failed handler post frees the bitmap");
    } finally {
      test.removeMonitor(monitor);
      release.countDown();
      delivery.quitSafely();
      delivery.join(10000);
      if (!delayed.isRecycled()) delayed.recycle();
      if (!rejected.isRecycled()) rejected.recycle();
    }
  }

  private static PhotoRepository.Photo jpg(File folder, String filename, int width, int color)
      throws Exception {
    File file = new File(folder, filename);
    Bitmap image = Bitmap.createBitmap(width, 80, Bitmap.Config.ARGB_8888);
    try (FileOutputStream out = new FileOutputStream(file)) {
      image.eraseColor(color);
      require(image.compress(Bitmap.CompressFormat.JPEG, 85, out), "synthetic JPEG");
    } finally {
      image.recycle();
    }
    return new PhotoRepository.Photo(
        Uri.fromFile(file), filename, folder.getPath(), file.length(), 0, 0);
  }

  private static void waitForFrame(Instrumentation test, MainActivity activity, int width)
      throws Exception {
    awaitWorker(activity);
    test.waitForIdleSync();
    ConsistencyLayoutChecks.frame(test, (View) RankingResultChecks.get(activity, "root"));
    TagDetailChecks.ui(
        test,
        () -> {
          Bitmap image = bitmap(viewer(activity));
          require(
              image != null
                  && !image.isRecycled()
                  && image.getWidth() == width
                  && "元のファイルを表示しています".contentEquals(viewer(activity).image.getStateDescription()),
              "current viewer rendered the intended original, width=" + width);
        });
  }

  private static void awaitWorker(MainActivity activity) {
    try {
      ((Future<?>) RankingResultChecks.get(activity, "viewing")).get(10, TimeUnit.SECONDS);
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private static PhotoViewerUi viewer(MainActivity activity) {
    return (PhotoViewerUi) ((ViewGroup) RankingResultChecks.get(activity, "root")).getChildAt(0);
  }

  private static Bitmap bitmap(PhotoViewerUi view) {
    try {
      Field field = ZoomImageView.class.getDeclaredField("bitmap");
      field.setAccessible(true);
      return (Bitmap) field.get(view.image);
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }

  private static void click(MainActivity activity, String action) {
    Button button = viewer(activity).actionButton(action);
    require(
        button != null && button.isEnabled() && button.performClick(), "viewer action " + action);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
