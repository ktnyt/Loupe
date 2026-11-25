package jp.nano.rawgallery;

import static jp.nano.rawgallery.AlbumTagEntryChecks.*;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Drives real share sheets with an isolated failing writer; never starts a recipient. */
final class VisualStatusFlowChecks {
  static void click(Instrumentation t, String label) throws Exception {
    long end = android.os.SystemClock.uptimeMillis() + 5000;
    while (android.os.SystemClock.uptimeMillis() < end) {
      AccessibilityNodeInfo root = t.getUiAutomation().getRootInActiveWindow();
      if (root != null)
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(label))
          if (label.equals(n.getText() == null ? "" : n.getText().toString().strip())
              && n.isClickable()
              && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
      Thread.sleep(80);
    }
    throw new AssertionError("Missing action " + label);
  }

  private static void shareOnMain(Instrumentation t, Check action) {
    // The deliberately blocked writer keeps the progress view drawing until cancellation.
    TagDetailChecks.ui(
        t,
        () -> {
          try {
            action.run();
          } catch (Exception failure) {
            throw new AssertionError("share fixture UI", failure);
          }
        });
  }

  static android.view.View failed(android.view.View view) {
    if (view instanceof StatusView
        && view.isClickable()
        && view.getVisibility() == android.view.View.VISIBLE) return view;
    if (view instanceof android.widget.Button
        && "再試行".contentEquals(((android.widget.Button) view).getText())) return view;
    if (view instanceof android.view.ViewGroup)
      for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++) {
        android.view.View v = failed(((android.view.ViewGroup) view).getChildAt(i));
        if (v != null) return v;
      }
    return null;
  }

  static AccessibilityNodeInfo input(Instrumentation t) {
    AccessibilityNodeInfo root = t.getUiAutomation().getRootInActiveWindow();
    return inputNode(root);
  }

  static AccessibilityNodeInfo inputNode(AccessibilityNodeInfo n) {
    if (n == null) return null;
    if ("android.widget.EditText".contentEquals(n.getClassName())) return n;
    for (int i = 0; i < n.getChildCount(); i++) {
      AccessibilityNodeInfo found = inputNode(n.getChild(i));
      if (found != null) return found;
    }
    return null;
  }

  static android.view.View photoView(android.view.View view) {
    if (view instanceof ZoomImageView) return view;
    if (view instanceof android.view.ViewGroup)
      for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++) {
        android.view.View found = photoView(((android.view.ViewGroup) view).getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  static android.view.View mainView(Instrumentation t, MainActivity a) throws Exception {
    android.view.View[] result = {null};
    main(
        t,
        () -> {
          result[0] = failed(a.getWindow().getDecorView());
          return true;
        });
    return result[0];
  }

  static void assertPhotoColor(MainActivity a, int expected) throws Exception {
    android.graphics.Bitmap image =
        (android.graphics.Bitmap) field(photoView(a.getWindow().getDecorView()), "bitmap");
    if (image == null) throw new AssertionError("photo bitmap missing");
    int actual = image.getPixel(image.getWidth() / 2, image.getHeight() / 2);
    for (int shift : new int[] {0, 8, 16})
      if (Math.abs(((actual >> shift) & 255) - ((expected >> shift) & 255)) > 5)
        throw new AssertionError("wrong photo pixel " + Integer.toHexString(actual));
  }

  static void assertNoStatus(android.view.View view) {
    if (view instanceof StatusView && view.isShown())
      throw new AssertionError("visible stale status");
    if (view instanceof android.view.ViewGroup)
      for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++)
        assertNoStatus(((android.view.ViewGroup) view).getChildAt(i));
  }

  static void swipe(Instrumentation t, MainActivity a, boolean left, boolean fling)
      throws Exception {
    int[] bounds = new int[4];
    android.view.View[] target = {null};
    main(
        t,
        () -> {
          android.view.View view = photoView(a.getWindow().getDecorView());
          TagDetailChecks.require(view != null && view.hasWindowFocus(), "photo receives input");
          target[0] = view;
          int[] xy = new int[2];
          view.getLocationOnScreen(xy);
          bounds[0] = xy[0];
          bounds[1] = xy[1];
          bounds[2] = view.getWidth();
          bounds[3] = view.getHeight();
          return true;
        });
    float from = bounds[0] + bounds[2] * (left ? .8f : .2f),
        to = bounds[0] + bounds[2] * (left ? .2f : .8f),
        y = bounds[1] + bounds[3] * .5f;
    float endOfFastMotion = to - Math.copySign(12f, to - from);
    CountDownLatch released = new CountDownLatch(1);
    android.view.VelocityTracker velocity = android.view.VelocityTracker.obtain();
    float[] downX = {0}, distance = {0}, speed = {0};
    TagDetailChecks.ui(
        t,
        () ->
            target[0].setOnTouchListener(
                (view, event) -> {
                  velocity.addMovement(event);
                  if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    downX[0] = event.getX();
                  } else if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                    distance[0] = event.getX() - downX[0];
                    velocity.computeCurrentVelocity(1000);
                    speed[0] = Math.abs(velocity.getXVelocity());
                    released.countDown();
                  }
                  return false;
                }));
    try {
      int steps = 12;
      long sampleMillis = 12;
      UiAutomation input = t.getUiAutomation();
      // Give Android a realistic, monotonic event history ending at the current uptime.
      // The slow release covers the same distance but decelerates for the last nine samples.
      long start = android.os.SystemClock.uptimeMillis() - steps * sampleMillis;
      for (int i = 0; i <= steps; i++) {
        float x;
        if (fling) x = from + (to - from) * i / steps;
        else if (i <= 3) x = from + (endOfFastMotion - from) * i / 3;
        else x = endOfFastMotion + (to - endOfFastMotion) * (i - 3) / (steps - 3);
        int action =
            i == 0
                ? android.view.MotionEvent.ACTION_DOWN
                : i == steps
                    ? android.view.MotionEvent.ACTION_UP
                    : android.view.MotionEvent.ACTION_MOVE;
        android.view.MotionEvent event =
            android.view.MotionEvent.obtain(start, start + i * sampleMillis, action, x, y, 0);
        try {
          event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
          TagDetailChecks.require(
              input.injectInputEvent(event, i == 0 || i == steps), "Android accepted swipe event");
        } finally {
          event.recycle();
        }
      }
      TagDetailChecks.require(released.await(5, TimeUnit.SECONDS), "photo received swipe release");
      android.util.Log.i(
          "LoupeVisualSwipe",
          "fling=" + fling + " distance=" + distance[0] + " releasePxPerSecond=" + speed[0]);
      TagDetailChecks.require(
          Math.abs(distance[0] - (to - from)) <= 2, "full swipe distance received");
      TagDetailChecks.require(
          (speed[0] >= 200) == fling, "delivered release speed matches fixture");
    } finally {
      TagDetailChecks.ui(
          t,
          () -> {
            target[0].setOnTouchListener(null);
            velocity.recycle();
          });
    }
  }

  static String run(Instrumentation t, MainActivity a) throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch writerInterrupted = new CountDownLatch(1);
    AtomicInteger attempts = new AtomicInteger();
    File dir = new File(a.getCacheDir(), "status-share-" + UUID.randomUUID());
    android.graphics.Bitmap bitmap =
        android.graphics.Bitmap.createBitmap(600, 800, android.graphics.Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(0xff587c8c);
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, encoded);
    bitmap.recycle();
    byte[] jpeg = encoded.toByteArray();
    PhotoRepository.Photo photo =
        new PhotoRepository.Photo(
            Uri.fromFile(new File(dir, "missing.jpg")),
            dir.getName() + ".jpg",
            "",
            jpeg.length,
            0,
            0);
    List<PhotoRepository.Photo> selection = List.of(photo);
    AtomicInteger shareLaunches = new AtomicInteger();
    Instrumentation.ActivityMonitor chooser =
        new Instrumentation.ActivityMonitor() {
          @Override
          public Instrumentation.ActivityResult onStartActivity(Intent intent) {
            String action = intent.getAction();
            if (!Intent.ACTION_CHOOSER.equals(action)
                && !Intent.ACTION_SEND.equals(action)
                && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return null;
            shareLaunches.incrementAndGet();
            return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
          }
        };
    t.addMonitor(chooser);
    try {
      ((ExecutorService) field(a, "scans")).submit(() -> {}).get(10, TimeUnit.SECONDS);
      t.waitForIdleSync();
      int[] firstAttempt = {0};
      shareOnMain(
          t,
          () -> {
            ((Set<String>) field(a, "selectedPhotos")).add(photo.uri.toString());
            set(
                a,
                "sharePreparation",
                new SharePreparation(
                    dir,
                    (p, raw, out) -> {
                      try (FileOutputStream partial = new FileOutputStream(out)) {
                        partial.write(jpeg, 0, Math.min(32, jpeg.length));
                      }
                      attempts.incrementAndGet();
                      try {
                        if (!release.await(15, TimeUnit.SECONDS))
                          throw new IOException("fixture writer was not released");
                      } catch (InterruptedException cancelled) {
                        writerInterrupted.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        throw cancelled;
                      }
                      throw new IOException("synthetic failure");
                    }));
            Method export =
                MainActivity.class.getDeclaredMethod(
                    "export", List.class, boolean.class, ShareTargets.Target.class);
            export.setAccessible(true);
            export.invoke(a, selection, false, null);
            firstAttempt[0] = (int) field(a, "shareAttempt");
            return true;
          });
      GenerationIndicatorChecks.await(t, "share writer started", () -> attempts.get() == 1);
      String[] partial = dir.list();
      TagDetailChecks.require(
          partial != null && partial.length == 1, "blocked writer left a partial fixture copy");
      click(t, "中止");
      GenerationIndicatorChecks.await(
          t,
          "share cancel received",
          () -> {
            Dialog preparing = (Dialog) field(a, "shareProgress");
            return (int) field(a, "shareAttempt") == firstAttempt[0] + 1
                && preparing != null
                && !preparing.isShowing();
          });
      TagDetailChecks.require(
          writerInterrupted.await(5, TimeUnit.SECONDS), "share cancel interrupts writer");
      release.countDown();
      ((ExecutorService) field(a, "actions")).submit(() -> {}).get(10, TimeUnit.SECONDS);
      String[] afterCancel = dir.list();
      shareOnMain(
          t,
          () -> {
            TagDetailChecks.require(
                afterCancel != null && afterCancel.length == 0 && shareLaunches.get() == 0,
                "cancel removes the partial share copy without starting an external share");
            if (!((Set<?>) field(a, "selectedPhotos")).contains(photo.uri.toString()))
              throw new AssertionError("cancel lost selection");
            Method export =
                MainActivity.class.getDeclaredMethod(
                    "export", List.class, boolean.class, ShareTargets.Target.class);
            export.setAccessible(true);
            export.invoke(a, selection, false, null);
            return true;
          });
      click(t, "再試行");
      GenerationIndicatorChecks.await(t, "retry writer", () -> attempts.get() == 3);
      click(t, "選択に戻る");
      ((ExecutorService) field(a, "actions")).submit(() -> {}).get(10, TimeUnit.SECONDS);
      String[] afterFailure = dir.list();
      main(
          t,
          () -> {
            TagDetailChecks.require(
                afterFailure != null && afterFailure.length == 0 && shareLaunches.get() == 0,
                "failed copies are removed without starting an external share");
            if (!((Set<?>) field(a, "selectedPhotos")).contains(photo.uri.toString()))
              throw new AssertionError("failure lost selection");
            return true;
          });
      main(
          t,
          () -> {
            set(a, "photos", new ArrayList<>(selection));
            set(a, "burstMembers", null);
            Method show = MainActivity.class.getDeclaredMethod("showPhoto", int.class);
            show.setAccessible(true);
            show.invoke(a, 0);
            return true;
          });
      waitFor(t, "detail failure recovery", () -> failed(a.getWindow().getDecorView()) != null);
      main(
          t,
          () -> {
            android.widget.Button retry =
                (android.widget.Button) failed(a.getWindow().getDecorView());
            if (retry.getLayout() == null
                || retry.getLayout().getWidth() < retry.getPaint().measureText("再試行"))
              throw new AssertionError("retry label clipped");
            return true;
          });
      android.view.View content = a.findViewById(android.R.id.content);
      capture(t, a, content, content.getHeight(), "status-detail-failed");
      File secondFile = new File(dir, "second.jpg");
      dir.mkdirs();
      android.graphics.Bitmap orange =
          android.graphics.Bitmap.createBitmap(600, 800, android.graphics.Bitmap.Config.ARGB_8888);
      orange.eraseColor(0xffc46c32);
      try (FileOutputStream out = new FileOutputStream(secondFile)) {
        orange.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out);
      } finally {
        orange.recycle();
      }
      PhotoRepository.Photo second =
          new PhotoRepository.Photo(
              Uri.fromFile(secondFile),
              dir.getName() + "-second.jpg",
              "",
              secondFile.length(),
              0,
              0);
      android.view.View oldFailure = mainView(t, a);
      main(
          t,
          () -> {
            set(a, "photos", new ArrayList<>(List.of(photo, second)));
            return true;
          });
      swipe(t, a, true, true);
      waitFor(
          t,
          "left swipe second photo",
          () -> ((Integer) field(a, "index")) == 1 && field(a, "activePreview") != null);
      Thread.sleep(1000);
      capture(t, a, content, content.getHeight(), "status-swipe-left");
      main(
          t,
          () -> {
            assertPhotoColor(a, 0xffc46c32);
            if (oldFailure.isAttachedToWindow() || failed(a.getWindow().getDecorView()) != null)
              throw new AssertionError("old failure after left swipe");
            assertNoStatus(a.getWindow().getDecorView());
            return true;
          });
      swipe(t, a, false, false);
      main(
          t,
          () -> {
            TagDetailChecks.require(
                ((Integer) field(a, "index")) == 1 && failed(a.getWindow().getDecorView()) == null,
                "slow swipe does not leave the second photo");
            assertPhotoColor(a, 0xffc46c32);
            return true;
          });
      swipe(t, a, false, true);
      waitFor(
          t,
          "right swipe first photo failure",
          () -> ((Integer) field(a, "index")) == 0 && failed(a.getWindow().getDecorView()) != null);
      capture(t, a, content, content.getHeight(), "status-swipe-right");
      File restored = new File(photo.uri.getPath());
      dir.mkdirs();
      try (FileOutputStream out = new FileOutputStream(restored)) {
        out.write(jpeg);
      }
      main(
          t,
          () -> {
            android.view.View retry = failed(a.getWindow().getDecorView());
            if (!retry.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
              throw new AssertionError("detail retry AX");
            return true;
          });
      waitFor(
          t,
          "detail recovered",
          () -> field(a, "activePreview") != null && failed(a.getWindow().getDecorView()) == null);
      Thread.sleep(900);
      main(
          t,
          () -> {
            assertPhotoColor(a, 0xff587c8c);
            return true;
          });
      capture(t, a, content, content.getHeight(), "status-detail-recovered");
      TagActivity tags =
          (TagActivity)
              t.startActivitySync(
                  new Intent(t.getTargetContext(), TagActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        waitFor(t, "tag entry", () -> button(tags.getWindow().getDecorView(), "新しいタグ") != null);
        main(
            t,
            () -> {
              button(tags.getWindow().getDecorView(), "新しいタグ").performClick();
              return true;
            });
        click(t, "作成");
        t.waitForIdleSync();
        AccessibilityNodeInfo edit = input(t);
        if (edit == null || !android.text.TextUtils.equals("1〜80文字で入力してください", edit.getError()))
          throw new AssertionError("tag input error missing");
        android.os.Bundle value = new android.os.Bundle();
        value.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "synthetic-cancel-only");
        if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value))
          throw new AssertionError("tag input edit");
        Thread.sleep(250);
        edit = input(t);
        if (edit == null || edit.getError() != null)
          throw new AssertionError("corrected input error remains");
        click(t, "キャンセル");
        t.waitForIdleSync();
      } finally {
        main(
            t,
            () -> {
              tags.finish();
              return true;
            });
      }
      return "PASS injected touch left/right swipes and slow-release control: missing original -> orange JPEG pixel verified, old failure detached and no visible status -> missing original -> retry -> blue JPEG pixel verified.\nPASS tag empty input error -> valid edit clears error -> cancel without persistence.\nPASS detail missing original -> persistent error -> actual AX retry after synthetic JPEG restore -> image recovered.\nPASS actual share cancel interrupts the writer and retains selection; cancel, failure and retry remove partial files without starting a chooser or recipient. No external transfer.\n";
    } finally {
      release.countDown();
      t.removeMonitor(chooser);
      File[] files = dir.listFiles();
      if (files != null) for (File f : files) f.delete();
      dir.delete();
    }
  }
}
