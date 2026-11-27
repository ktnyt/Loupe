package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Injects synthetic view state; normal Activity startup may refresh the browsing index. */
final class IndexApplyRaceChecks {
  private static Object get(Object o, String name) throws Exception {
    Field f = MainActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(o);
  }

  private static void set(Object o, String name, Object value) throws Exception {
    Field f = MainActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(o, value);
  }

  private static void call(Object o, String name) throws Exception {
    Method m = MainActivity.class.getDeclaredMethod(name);
    m.setAccessible(true);
    m.invoke(o);
  }

  private static void check(boolean valid, String label) {
    if (!valid) throw new AssertionError(label);
  }

  private static void drain(Instrumentation test, MainActivity a) throws Exception {
    ((ExecutorService) get(a, "scans")).submit(() -> {}).get(5, TimeUnit.SECONDS);
    test.waitForIdleSync();
  }

  private static boolean hasIntendedPhoto(MainActivity activity, PhotoRepository.Photo expected)
      throws Exception {
    List<?> photos = (List<?>) get(activity, "photos");
    return photos.size() == 1 && photos.get(0) == expected;
  }

  private record RaceState(
      boolean intendedPhoto, boolean detail, boolean pending, int generation, long version) {}

  private static RaceState traceState(MainActivity activity, PhotoRepository.Photo expected) {
    try {
      return new RaceState(
          hasIntendedPhoto(activity, expected),
          (boolean) get(activity, "detail"),
          (boolean) get(activity, "pendingIndexUpdate"),
          (int) get(activity, "generation"),
          (long) get(activity, "indexUpdateVersion"));
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static void reportState(Instrumentation test, String phase, RaceState state) {
    try {
      if (state == null) return;
      Bundle status = new Bundle();
      status.putString(
          "stream",
          "LoupeIndexRace phase="
              + phase
              + " intendedPhoto="
              + state.intendedPhoto
              + " detail="
              + state.detail
              + " pending="
              + state.pending
              + " generation="
              + state.generation
              + " version="
              + state.version);
      test.sendStatus(0, status);
    } catch (Throwable ignored) {
      // Diagnostic output must not replace the fixture result.
    }
  }

  static String run(Instrumentation test) throws Exception {
    MainActivity a =
        (MainActivity)
            test.startActivitySync(
                new Intent(test.getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    File seed = File.createTempFile("index-race-", ".jpg", a.getCacheDir());
    CountDownLatch release = new CountDownLatch(1);
    try {
      Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
      try (OutputStream out = new FileOutputStream(seed)) {
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
      } finally {
        bitmap.recycle();
      }
      GalleryIndex.WORKER.submit(() -> {}).get(30, TimeUnit.SECONDS);
      test.waitForIdleSync();
      drain(test, a);
      PhotoRepository.Photo photo =
          new PhotoRepository.Photo(Uri.fromFile(seed), "synthetic.jpg", "", seed.length(), 1, 0);
      CountDownLatch entered = new CountDownLatch(1);
      ((ExecutorService) get(a, "scans"))
          .execute(
              () -> {
                entered.countDown();
                try {
                  if (!release.await(10, TimeUnit.SECONDS))
                    throw new AssertionError("test release timeout");
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new RuntimeException(e);
                }
              });
      check(entered.await(5, TimeUnit.SECONDS), "scan blocker entered");
      RaceState[] beforeRelease = new RaceState[2];
      RaceState afterDrain = null;
      try {
        TagDetailChecks.ui(
            test,
            () -> {
              try {
                // An outstanding startup read cannot replace the synthetic photo before viewer
                // entry.
                set(a, "pendingRestore", null);
                set(a, "albumFolder", "Pictures/index-race-" + UUID.randomUUID() + "/");
                set(a, "burstMembers", null);
                set(a, "selecting", false);
                set(a, "photos", new ArrayList<>(Collections.singletonList(photo)));
                set(
                    a,
                    "bundles",
                    new ArrayList<>(
                        Collections.singletonList(new ArrayList<>(Collections.singletonList(0)))));
                call(a, "gallery");
                int listGeneration = (int) get(a, "generation");
                beforeRelease[0] = traceState(a, photo);
                check(
                    hasIntendedPhoto(a, photo) && !(boolean) get(a, "detail"),
                    "intended photo in the synthetic gallery");

                set(a, "indexUpdateVersion", (long) get(a, "indexUpdateVersion") + 1);
                set(a, "pendingIndexUpdate", true);
                call(a, "applyPendingIndexUpdate");
                Method show = MainActivity.class.getDeclaredMethod("showPhoto", int.class);
                show.setAccessible(true);
                show.invoke(a, 0);
                beforeRelease[1] = traceState(a, photo);
                check(
                    hasIntendedPhoto(a, photo)
                        && (boolean) get(a, "detail")
                        && (int) get(a, "index") == 0
                        && (int) get(a, "generation") == listGeneration + 1,
                    "intended viewer generation before releasing scans");
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
        release.countDown();
        drain(test, a);
        try {
          check((boolean) get(a, "pendingIndexUpdate"), "discarded result retains pending update");
        } finally {
          afterDrain = traceState(a, photo);
        }
      } finally {
        // Publish only after the blocker is released, including if either precondition fails.
        release.countDown();
        reportState(test, "gallery_seeded", beforeRelease[0]);
        reportState(test, "viewer_before_release", beforeRelease[1]);
        reportState(test, "scan_drained", afterDrain);
      }
      test.runOnMainSync(a::onBackPressed);
      drain(test, a);
      check(((List<?>) get(a, "photos")).isEmpty(), "return applies authoritative empty folder");
      check(!(boolean) get(a, "pendingIndexUpdate"), "successful apply clears pending update");
      // Queue an old read while Main is occupied, then publish a newer update before it applies.
      test.runOnMainSync(
          () -> {
            try {
              set(a, "pendingIndexUpdate", true);
              call(a, "loadIndexedPhotos");
              ((ExecutorService) get(a, "scans")).submit(() -> {}).get(5, TimeUnit.SECONDS);
              set(a, "indexUpdateVersion", (long) get(a, "indexUpdateVersion") + 1);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      test.waitForIdleSync();
      check((boolean) get(a, "pendingIndexUpdate"), "old read cannot clear newer update");
      test.runOnMainSync(
          () -> {
            try {
              call(a, "applyPendingIndexUpdate");
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      drain(test, a);
      check(!(boolean) get(a, "pendingIndexUpdate"), "latest read clears pending update");
      return "PASS discarded list read survives viewer round-trip; old read cannot clear newer update; successful latest apply clears pending. No source photos changed; normal Activity startup may refresh the browsing index.\n";
    } finally {
      release.countDown();
      test.runOnMainSync(a::finish);
      seed.delete();
    }
  }
}
