package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.VideoView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Synthetic local movie only. No upload, original modification, tag save or backup. */
final class VideoChecks {
  private static Object get(Object owner, String name) {
    try {
      Field field = owner.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(owner);
    } catch (ReflectiveOperationException error) {
      throw new RuntimeException(error);
    }
  }

  private static void set(Object owner, String name, Object value) {
    try {
      Field field = owner.getClass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(owner, value);
    } catch (ReflectiveOperationException error) {
      throw new RuntimeException(error);
    }
  }

  private static void await(Instrumentation test, BooleanSupplier condition, String failure)
      throws Exception {
    long until = SystemClock.uptimeMillis() + 15000;
    boolean[] ready = {false};
    do {
      TagDetailChecks.ui(test, () -> ready[0] = condition.getAsBoolean());
      if (ready[0]) return;
      Thread.sleep(40);
    } while (SystemClock.uptimeMillis() < until);
    throw new AssertionError(failure);
  }

  private static View content(Activity activity) {
    return ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
  }

  private enum Progress {
    ERROR,
    UNPREPARED,
    COMPLETED,
    IDLE,
    STALLED,
    ADVANCED
  }

  private record PlaybackSample(
      boolean prepared,
      boolean failed,
      boolean completed,
      boolean paused,
      boolean playing,
      int position,
      int duration) {
    Progress progressFrom(int baseline) {
      if (failed) return Progress.ERROR;
      if (!prepared) return Progress.UNPREPARED;
      if (completed) return Progress.COMPLETED;
      if (!playing) return Progress.IDLE;
      return position > baseline ? Progress.ADVANCED : Progress.STALLED;
    }
  }

  private static void checkProgressStates() {
    int baseline = 100;
    TagDetailChecks.require(
        new PlaybackSample(true, false, false, false, false, 150, 6000).progressFrom(baseline)
            == Progress.IDLE,
        "idle position is not playback progress");
    TagDetailChecks.require(
        new PlaybackSample(true, false, false, false, true, baseline, 6000).progressFrom(baseline)
            == Progress.STALLED,
        "reported playing without movement is not progress");
    TagDetailChecks.require(
        new PlaybackSample(true, false, false, false, true, 150, 6000).progressFrom(baseline)
            == Progress.ADVANCED,
        "live forward movement is progress");
    TagDetailChecks.require(
        new PlaybackSample(false, false, false, false, true, 150, 6000).progressFrom(baseline)
            == Progress.UNPREPARED,
        "unprepared player cannot prove progress");
    TagDetailChecks.require(
        new PlaybackSample(false, true, false, false, true, 150, 6000).progressFrom(baseline)
            == Progress.ERROR,
        "error cannot prove progress");
    TagDetailChecks.require(
        new PlaybackSample(true, false, true, false, true, 150, 6000).progressFrom(baseline)
            == Progress.COMPLETED,
        "completion cannot prove progress");
  }

  private static PlaybackSample sample(VideoActivity activity, VideoView video) {
    String state = String.valueOf(video.getStateDescription());
    return new PlaybackSample(
        (boolean) get(activity, "prepared"),
        (boolean) get(activity, "failed"),
        "再生終了".equals(state),
        "一時停止中".equals(state),
        video.isPlaying(),
        video.getCurrentPosition(),
        video.getDuration());
  }

  private static void reportPlayback(
      Instrumentation test, String state, long elapsed, int baseline, PlaybackSample sample) {
    Bundle report = new Bundle();
    report.putString(
        "video_fixture",
        "state="
            + state
            + " elapsed_ms="
            + elapsed
            + " baseline_ms="
            + baseline
            + " latest_ms="
            + sample.position()
            + " duration_ms="
            + sample.duration());
    test.sendStatus(0, report);
  }

  private static AssertionError reportFailedPlayback(AssertionError primary, Runnable report) {
    try {
      report.run();
    } catch (RuntimeException | Error reportingFailure) {
      if (reportingFailure != primary) primary.addSuppressed(reportingFailure);
    }
    return primary;
  }

  private static void checkFailureReporting() {
    AssertionError primary = new AssertionError("playback fixture primary");
    RuntimeException sinkFailure = new IllegalStateException("playback status sink");
    AssertionError reported =
        reportFailedPlayback(
            primary,
            () -> {
              throw sinkFailure;
            });
    TagDetailChecks.require(
        reported == primary
            && primary.getSuppressed().length == 1
            && primary.getSuppressed()[0] == sinkFailure,
        "status sink error remains secondary to the original assertion");
    reported =
        reportFailedPlayback(
            primary,
            () -> {
              throw primary;
            });
    TagDetailChecks.require(
        reported == primary && primary.getSuppressed().length == 1,
        "an assertion is not suppressed onto itself");
  }

  private static void playThroughPause(
      Instrumentation test, VideoActivity activity, VideoView video, Button play) throws Exception {
    PlaybackSample[] latest = new PlaybackSample[1];
    long[] playAt = new long[1];
    TagDetailChecks.ui(
        test,
        () -> {
          latest[0] = sample(activity, video);
          TagDetailChecks.require(
              latest[0].progressFrom(latest[0].position()) == Progress.IDLE,
              "play action starts with a prepared idle player");
          playAt[0] = SystemClock.uptimeMillis();
          TagDetailChecks.require(play.performClick(), "play action accepted");
        });
    int baseline = latest[0].position();
    PlaybackSample[] advanced = new PlaybackSample[1];
    long[] advancedAt = new long[1];
    try {
      await(
          test,
          () -> {
            latest[0] = sample(activity, video);
            Progress progress = latest[0].progressFrom(baseline);
            switch (progress) {
              case ADVANCED -> {
                advanced[0] = latest[0];
                advancedAt[0] = SystemClock.uptimeMillis();
                TagDetailChecks.require(play.performClick(), "pause action accepted while playing");
                return true;
              }
              case IDLE, STALLED -> {
                return false;
              }
              default -> throw new AssertionError("player unavailable before progress");
            }
          },
          "playing position did not advance");
    } catch (AssertionError failure) {
      throw reportFailedPlayback(
          failure,
          () ->
              reportPlayback(
                  test,
                  advanced[0] == null
                      ? latest[0].progressFrom(baseline).name()
                      : "PAUSE_CLICK_REJECTED",
                  SystemClock.uptimeMillis() - playAt[0],
                  baseline,
                  latest[0]));
    }
    try {
      await(
          test,
          () -> {
            latest[0] = sample(activity, video);
            TagDetailChecks.require(
                latest[0].prepared() && !latest[0].failed() && !latest[0].completed(),
                "player unavailable after explicit pause");
            return latest[0].paused() && !latest[0].playing();
          },
          "explicit pause not observed");
    } catch (AssertionError failure) {
      throw reportFailedPlayback(
          failure,
          () -> {
            Progress progress = latest[0].progressFrom(baseline);
            reportPlayback(
                test,
                switch (progress) {
                  case ERROR, UNPREPARED, COMPLETED -> "PAUSE_" + progress.name();
                  default -> "PAUSE_NOT_OBSERVED";
                },
                SystemClock.uptimeMillis() - playAt[0],
                baseline,
                latest[0]);
          });
    }
    long pausedElapsed = SystemClock.uptimeMillis() - playAt[0];
    reportPlayback(test, "ADVANCED", advancedAt[0] - playAt[0], baseline, advanced[0]);
    reportPlayback(test, "PAUSED", pausedElapsed, baseline, latest[0]);
  }

  private static void capture(Instrumentation test, Activity activity, String name)
      throws Exception {
    test.waitForIdleSync();
    File directory = new File(activity.getCacheDir(), "consistency-checks");
    directory.mkdirs();
    if (activity instanceof VideoActivity) {
      Thread.sleep(1000);
      Bitmap bitmap = test.getUiAutomation().takeScreenshot();
      try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
      } finally {
        bitmap.recycle();
      }
    } else
      ConsistencyLayoutChecks.captureHardware(test, activity, content(activity), directory, name);
  }

  static String run(Instrumentation test, int width, float font) throws Exception {
    checkProgressStates();
    checkFailureReporting();
    File movie = new File(test.getTargetContext().getCacheDir(), "video-synthetic.mp4");
    try (InputStream in = test.getContext().getAssets().open("video-synthetic.mp4");
        FileOutputStream out = new FileOutputStream(movie)) {
      in.transferTo(out);
    }
    PhotoRepository.Photo photo =
        new PhotoRepository.Photo(
            Uri.fromFile(movie),
            "合成動画.mp4",
            "/synthetic/",
            movie.length(),
            1794438000L,
            1794438000000L);
    Uri published = null;
    MainActivity gallery = null;
    VideoActivity player = null;
    try {
      PhotoRepository repository = new PhotoRepository(test.getTargetContext());
      android.content.ContentValues values = new android.content.ContentValues();
      String sharedName = "Loupe_video_test_" + SystemClock.uptimeMillis() + ".mp4";
      values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, sharedName);
      values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
      values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Loupe-test/");
      values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
      published =
          test.getTargetContext()
              .getContentResolver()
              .insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
      TagDetailChecks.require(published != null, "synthetic MediaStore video insert");
      try (java.io.OutputStream out =
              test.getTargetContext().getContentResolver().openOutputStream(published);
          InputStream in = new java.io.FileInputStream(movie)) {
        if (out == null) throw new java.io.IOException("Synthetic video output unavailable");
        in.transferTo(out);
      }
      values.clear();
      values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
      test.getTargetContext().getContentResolver().update(published, values, null, null);
      PhotoRepository.Photo discovered = null;
      for (PhotoRepository.Photo item : repository.scan("すべて"))
        if (item.name.equals(sharedName)) discovered = item;
      TagDetailChecks.require(
          discovered != null
              && discovered.isVideo()
              && discovered.folder.equals("Movies/Loupe-test/"),
          "MediaStore video discovery and album");
      Bitmap providerThumbnail = repository.thumbnail(discovered, 160, () -> false);
      TagDetailChecks.require(providerThumbnail != null, "MediaStore video thumbnail");
      providerThumbnail.recycle();
      TagDetailChecks.require(photo.isVideo() && !photo.isJpeg(), "video type");
      TagDetailChecks.require(
          !PreviewPreparationJob.local(photo), "video excluded from photo preparation");
      Bitmap thumb = repository.thumbnail(photo, 160, () -> false);
      TagDetailChecks.require(
          thumb != null && Math.max(thumb.getWidth(), thumb.getHeight()) <= 160,
          "scaled video thumbnail");
      thumb.recycle();
      boolean rejected = false;
      try {
        repository.sourcePreview(photo);
      } catch (java.io.IOException expected) {
        rejected = true;
      }
      TagDetailChecks.require(rejected, "video excluded from RAW parser");
      try (ArchiveStore archive = new ArchiveStore(test.getTargetContext())) {
        TagDetailChecks.require(
            archive.metadata(photo).taken == photo.taken, "video keeps capture date");
      }

      gallery =
          (MainActivity)
              test.startActivitySync(
                  new Intent(test.getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      final MainActivity screen = gallery;
      GalleryScreenChecks.finishInitialIndex(test, screen);
      GalleryScreenChecks.portraitFixture(test, screen, width, font);
      TagDetailChecks.ui(
          test,
          () -> {
            RankingResultChecks.set(screen, "photos", new ArrayList<>(List.of(photo)));
            RankingResultChecks.set(screen, "bundles", new ArrayList<>(List.of(List.of(0))));
            RankingResultChecks.set(screen, "burstMembers", null);
            RankingResultChecks.set(screen, "albumFolder", "/synthetic/");
            RankingResultChecks.set(screen, "filter", "すべて");
            RankingResultChecks.set(screen, "rankFilter", 0);
            RankingResultChecks.set(screen, "pendingIndexUpdate", false);
            RankingResultChecks.call(screen, "gallery");
          });
      TagDetailChecks.ui(
          test,
          () -> {
            PhotoRepository.Photo a =
                new PhotoRepository.Photo(photo.uri, "before.jpg", "synthetic", 1, 1, 1);
            PhotoRepository.Photo b =
                new PhotoRepository.Photo(photo.uri, "after.jpg", "synthetic", 1, 1, 1);
            RankingResultChecks.set(screen, "photos", new ArrayList<>(List.of(a, photo, b)));
            RankingResultChecks.set(screen, "index", 0);
            try {
              java.lang.reflect.Method adjacent =
                  MainActivity.class.getDeclaredMethod("adjacentPhotoPosition", int.class);
              adjacent.setAccessible(true);
              TagDetailChecks.require(
                  (int) adjacent.invoke(screen, 1) == 2, "photo paging skips video");
              RankingResultChecks.set(screen, "index", 2);
              TagDetailChecks.require(
                  (int) adjacent.invoke(screen, -1) == 0, "reverse paging skips video");
            } catch (ReflectiveOperationException e) {
              throw new RuntimeException(e);
            }
            RankingResultChecks.set(screen, "photos", new ArrayList<>(List.of(photo)));
            RankingResultChecks.set(screen, "index", 0);
          });
      GalleryScreenChecks.settle(test, screen);
      await(
          test,
          () ->
              GalleryScreenChecks.firstPhoto((View) RankingResultChecks.get(screen, "root"))
                  != null,
          "video gallery tile");
      TagDetailChecks.ui(
          test,
          () -> {
            ThumbnailCell tile =
                GalleryScreenChecks.firstPhoto((View) RankingResultChecks.get(screen, "root"));
            TagDetailChecks.require(
                tile.badges.video && tile.getContentDescription().toString().contains("動画"),
                "video tile icon and accessible name");
          });
      capture(test, screen, "video-gallery-" + width + "-" + font);

      Instrumentation.ActivityMonitor monitor =
          test.addMonitor(VideoActivity.class.getName(), null, false);
      try {
        TagDetailChecks.ui(
            test,
            () -> {
              try {
                java.lang.reflect.Method open =
                    MainActivity.class.getDeclaredMethod("showPhoto", int.class);
                open.setAccessible(true);
                open.invoke(screen, 0);
              } catch (ReflectiveOperationException error) {
                throw new RuntimeException(error);
              }
            });
        player = (VideoActivity) test.waitForMonitorWithTimeout(monitor, 15000);
        TagDetailChecks.require(player != null, "gallery opens video activity");
      } finally {
        test.removeMonitor(monitor);
      }
      final VideoActivity activity = player;
      await(test, () -> (boolean) get(activity, "prepared"), "movie prepared");
      VideoView video = (VideoView) get(activity, "video");
      Button play = (Button) get(activity, "play");
      TagDetailChecks.ui(
          test,
          () -> {
            TagDetailChecks.require(!video.isPlaying(), "no autoplay");
            TagDetailChecks.require(
                play.getHeight() >= GalleryUi.dp(activity, 48), "play touch target");
            TagDetailChecks.require(
                activity.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_PORTRAIT,
                "video portrait");
          });
      capture(test, activity, "video-ready-" + width + "-" + font);
      playThroughPause(test, activity, video, play);
      capture(test, activity, "video-user-paused-" + width + "-" + font);
      TagDetailChecks.ui(
          test,
          () -> {
            long now = SystemClock.uptimeMillis();
            android.view.MotionEvent down =
                android.view.MotionEvent.obtain(
                    now,
                    now,
                    android.view.MotionEvent.ACTION_DOWN,
                    video.getWidth() / 2f,
                    video.getHeight() / 2f,
                    0);
            android.view.MotionEvent up =
                android.view.MotionEvent.obtain(
                    now,
                    now + 30,
                    android.view.MotionEvent.ACTION_UP,
                    video.getWidth() / 2f,
                    video.getHeight() / 2f,
                    0);
            video.dispatchTouchEvent(down);
            video.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
          });
      capture(test, activity, "video-user-paused-controls-" + width + "-" + font);
      TagDetailChecks.ui(test, () -> video.seekTo(2000));
      await(test, () -> video.getCurrentPosition() >= 1500, "seek action");
      TagDetailChecks.ui(test, play::performClick);
      await(test, video::isPlaying, "resume action");
      DashboardActivity dashboard =
          (DashboardActivity)
              test.startActivitySync(
                  new Intent(test.getTargetContext(), DashboardActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      TagDetailChecks.ui(
          test, () -> TagDetailChecks.require(!video.isPlaying(), "background pauses video"));
      TagDetailChecks.ui(test, dashboard::finish);
      test.waitForIdleSync();
      TagDetailChecks.ui(
          test, () -> TagDetailChecks.require(!video.isPlaying(), "foreground never autoplays"));
      capture(test, activity, "video-paused-" + width + "-" + font);

      TagDetailChecks.ui(
          test,
          () -> {
            set(
                activity,
                "source",
                Uri.fromFile(new File(movie.getParentFile(), "missing-video.mp4")));
            try {
              java.lang.reflect.Method load = VideoActivity.class.getDeclaredMethod("load");
              load.setAccessible(true);
              load.invoke(activity);
            } catch (ReflectiveOperationException error) {
              throw new RuntimeException(error);
            }
          });
      await(test, () -> (boolean) get(activity, "failed"), "missing source failure");
      TagDetailChecks.ui(
          test,
          () ->
              TagDetailChecks.require(
                  play.isEnabled() && play.getText().toString().equals("再試行"),
                  "retry after failure"));
      capture(test, activity, "video-failed-" + width + "-" + font);
      TagDetailChecks.ui(
          test,
          () -> {
            set(activity, "source", photo.uri);
            play.performClick();
          });
      await(test, () -> (boolean) get(activity, "prepared"), "retry recovers");
      TagDetailChecks.ui(
          test, () -> TagDetailChecks.require(!video.isPlaying(), "retry never autoplays"));
      TagDetailChecks.ui(test, activity::finish);
      player = null;
      test.waitForIdleSync();
      TagDetailChecks.ui(
          test,
          () ->
              TagDetailChecks.require(
                  !(boolean) RankingResultChecks.get(screen, "detail"), "return keeps gallery"));
      return "PASS video synthetic MP4 MediaStore-discovery/thumbnail/gallery/play/pause/seek/background/return/error/retry "
          + width
          + "dp font="
          + font
          + "; physical gesture/audio and other codecs not tested\n";
    } finally {
      if (player != null) {
        VideoActivity remaining = player;
        TagDetailChecks.ui(test, remaining::finish);
      }
      if (gallery != null) {
        MainActivity remaining = gallery;
        TagDetailChecks.ui(test, remaining::finish);
      }
      if (published != null)
        test.getTargetContext().getContentResolver().delete(published, null, null);
      movie.delete();
    }
  }
}
