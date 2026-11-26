package jp.nano.rawgallery;

import static jp.nano.rawgallery.RankingResultChecks.call;
import static jp.nano.rawgallery.RankingResultChecks.get;
import static jp.nano.rawgallery.RankingResultChecks.set;
import static jp.nano.rawgallery.TagDetailChecks.require;
import static jp.nano.rawgallery.TagDetailChecks.ui;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Test-only lock and frame proof against real MainActivity controls and isolated XML. */
final class RankingPersistenceWaitChecks {
  private static final String AVD = "loupe249-ranking-baseline";
  private static final long PROOF_MS = 650;
  private static final long GATE_LIMIT_MS = 2200;
  private static final long FRAME_WAIT_MS = 5000;
  private static final List<Integer> BURST = List.of(1, 2, 3);

  static String run(Instrumentation test, Bundle args) throws Exception {
    requireEmptyAvd(test);
    boolean baseline =
        switch (args.getString("baseline", "false")) {
          case "true" -> true;
          case "false" -> false;
          default -> throw new IllegalArgumentException("baseline must be true or false");
        };
    int entries =
        switch (args.getString("ranking_entries", "200")) {
          case "200" -> 200;
          case "2000" -> 2000;
          case "10000" -> 10000;
          default -> throw new IllegalArgumentException("unsupported ranking_entries count");
        };

    Context context = test.getTargetContext();
    String namespace = "ranking-wait-fixture-" + UUID.randomUUID();
    SharedPreferences prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE);
    File xml = new File(context.getApplicationInfo().dataDir, "shared_prefs/" + namespace + ".xml");
    List<File> files = new ArrayList<>();
    MainActivity activity = null;
    Map<String, Object> originalFields = new LinkedHashMap<>();
    Commit held = new Commit(true);
    try {
      seedXml(prefs, entries);
      long xmlBytes = xml.length();
      require(xmlBytes > 0 && prefs.getAll().size() == entries, "exact fixture XML population");
      List<PhotoRepository.Photo> photos = fixturePhotos(context, files);
      AtomicReference<Commit> next = new AtomicReference<>();
      RankingStore observed = new RankingStore(observe(prefs, next));
      activity =
          (MainActivity)
              test.startActivitySync(
                  new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      GalleryScreenChecks.finishInitialIndex(test, activity);
      GalleryScreenChecks.settle(test, activity);
      installFixture(test, activity, originalFields, photos, observed);
      String read = heldRead(test, activity, prefs, next, held, baseline, photos.get(0));
      String choose = choose(test, activity, prefs, next, baseline, photos);
      int width = context.getResources().getConfiguration().screenWidthDp;
      float font = context.getResources().getConfiguration().fontScale;
      return "PASS ranking persistence waits baseline="
          + baseline
          + " entries="
          + entries
          + " singletonEntries="
          + entries / 2
          + " burstEntries="
          + entries / 2
          + " xmlBytesBefore="
          + xmlBytes
          + " xmlBytesAfter="
          + xml.length()
          + " photos=4 burstMembers=3 jpegWidth=320 jpegHeight=240 prefsWarm=true"
          + " firstPairReady=true widthDp="
          + width
          + " fontScale="
          + font
          + "\n"
          + read
          + "\n"
          + choose
          + "\n";
    } finally {
      held.release();
      try {
        if (activity != null) restoreAndFinish(test, activity, originalFields);
      } finally {
        try {
          require(
              context.deleteSharedPreferences(namespace), "isolated ranking preferences removed");
        } finally {
          try {
            for (File file : files) Files.deleteIfExists(file.toPath());
          } finally {
            requireEmptyData(context);
          }
        }
      }
    }
  }

  private static void requireEmptyAvd(Instrumentation test) throws Exception {
    require("ranchu".equals(Build.HARDWARE), "disposable emulator hardware required");
    String name;
    try (ParcelFileDescriptor descriptor =
            test.getUiAutomation().executeShellCommand("getprop ro.boot.qemu.avd_name");
        FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
      byte[] output = new byte[128];
      int length = input.read(output);
      require(length > 0 && input.read() == -1, "unambiguous emulator name required");
      name = new String(output, 0, length, StandardCharsets.UTF_8).trim();
    }
    require(AVD.equals(name), "exact dedicated ranking AVD name required");
    requireEmptyData(test.getTargetContext());
  }

  private static void requireEmptyData(Context context) {
    require(
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                == PackageManager.PERMISSION_GRANTED
            && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)
                == PackageManager.PERMISSION_GRANTED,
        "complete image and video fixture queries require permissions");
    require(
        context.getSharedPreferences("rankings", Context.MODE_PRIVATE).getAll().isEmpty(),
        "default ranking preferences must remain empty");
    for (Uri collection :
        List.of(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))) {
      try (Cursor rows =
          context.getContentResolver().query(collection, new String[] {"_id"}, null, null, null)) {
        require(rows != null && rows.getCount() == 0, "MediaStore must remain empty");
      }
    }
  }

  private static void seedXml(SharedPreferences prefs, int entries) throws Exception {
    SharedPreferences.Editor editor = prefs.edit();
    for (int index = 0; index < entries / 2; index++) {
      editor.putString(
          "single-best-v1-" + PhotoRepository.digest("rank-fixture-single-" + index),
          index % 2 == 0 ? "0" : "1");
      editor.putString(
          "v1-" + PhotoRepository.digest("rank-fixture-a-" + index + "|rank-fixture-b-" + index),
          switch (index % 3) {
            case 0 -> "B:0";
            case 1 -> "A:2";
            default -> "1";
          });
    }
    require(editor.commit(), "seed isolated ranking XML");
  }

  private static List<PhotoRepository.Photo> fixturePhotos(Context context, List<File> files)
      throws Exception {
    List<PhotoRepository.Photo> photos = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      File file = File.createTempFile("ranking-wait-", ".jpg", context.getCacheDir());
      files.add(file);
      Bitmap bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888);
      bitmap.eraseColor(0xff305060 + 0x120800 * index);
      try (FileOutputStream output = new FileOutputStream(file)) {
        require(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output), "write generated JPEG");
      } finally {
        bitmap.recycle();
      }
      photos.add(
          new PhotoRepository.Photo(
              Uri.fromFile(file),
              "合成写真" + index + ".jpg",
              "/ranking-wait-fixture",
              file.length(),
              1792298000L + index,
              1792298000000L + index * 1000));
    }
    return photos;
  }

  private static void installFixture(
      Instrumentation test,
      MainActivity activity,
      Map<String, Object> original,
      List<PhotoRepository.Photo> photos,
      RankingStore store)
      throws Exception {
    ui(
        test,
        () -> {
          for (String field :
              List.of(
                  "rankingStore",
                  "photos",
                  "bundles",
                  "burstMembers",
                  "burstPosition",
                  "rankFilter",
                  "selecting",
                  "albumFolder",
                  "ranking")) {
            original.put(field, get(activity, field));
          }
          for (String field : List.of("rankCache", "rankingMembers", "photoBundles")) {
            original.put(field, new HashMap<>((Map<?, ?>) get(activity, field)));
          }
          set(activity, "rankingStore", store);
          set(activity, "photos", photos);
          set(activity, "bundles", List.of(List.of(0), BURST));
          set(activity, "burstMembers", null);
          set(activity, "rankFilter", 0);
          set(activity, "selecting", false);
          set(activity, "albumFolder", "/ranking-wait-fixture");
          ((Map<?, ?>) get(activity, "rankCache")).clear();
          call(activity, "gallery");
        });
    GalleryScreenChecks.settle(test, activity);
  }

  @SuppressWarnings("unchecked")
  private static void restoreAndFinish(
      Instrumentation test, MainActivity activity, Map<String, Object> original) throws Exception {
    try {
      ((ExecutorService) get(activity, "actions")).submit(() -> {}).get(5, TimeUnit.SECONDS);
    } finally {
      ui(
          test,
          () -> {
            try {
              original.forEach(
                  (field, value) -> {
                    if (value instanceof Map<?, ?> saved) {
                      Map<Object, Object> target = (Map<Object, Object>) get(activity, field);
                      target.clear();
                      target.putAll(saved);
                    } else set(activity, field, value);
                  });
            } finally {
              activity.finish();
            }
          });
      test.waitForIdleSync();
    }
  }

  private static String heldRead(
      Instrumentation test,
      MainActivity activity,
      SharedPreferences prefs,
      AtomicReference<Commit> next,
      Commit held,
      boolean baseline,
      PhotoRepository.Photo photo)
      throws Exception {
    ViewerScreenChecks.open(test, activity, 0);
    View decor = activity.getWindow().getDecorView();
    View startingRoot = (View) get(activity, "root");
    Frame oldFrame = new Frame(decor, () -> matchingStar(activity, startingRoot, false));
    Frame newFrame = new Frame(decor, () -> matchingStar(activity, startingRoot, true));
    String storeKey = "single-best-v1-" + PhotoRepository.digest(ArchiveStore.key(photo));
    require(!prefs.contains(storeKey), "fixture singleton starts without a saved row");
    next.set(held);
    ui(
        test,
        () -> {
          Button star = star(activity);
          require(star != null && !star.isSelected() && star.performClick(), "toggle real star");
          oldFrame.arm();
          newFrame.arm();
        });

    AtomicLong posted = new AtomicLong(), handled = new AtomicLong(), returned = new AtomicLong();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch finished = new CountDownLatch(1);
    boolean blocked = false;
    try {
      require(held.entered.await(2, TimeUnit.SECONDS), "writer entered fixture commit");
      Method open = MainActivity.class.getDeclaredMethod("showPhoto", int.class);
      open.setAccessible(true);
      posted.set(now());
      new Handler(Looper.getMainLooper())
          .post(
              () -> {
                handled.set(now());
                try {
                  invoke(open, activity, new Object[] {0});
                  returned.set(now());
                } catch (Throwable error) {
                  failure.set(error);
                } finally {
                  finished.countDown();
                }
              });
      long deadline = SystemClock.uptimeMillis() + PROOF_MS;
      do {
        blocked |= mainWaitingInSingleBest();
        if (baseline ? blocked : oldFrame.ready.getCount() == 0) break;
        Thread.sleep(8);
      } while (SystemClock.uptimeMillis() < deadline);
      if (baseline) {
        require(
            blocked && finished.getCount() != 0 && oldFrame.ready.getCount() != 0,
            "old UI read must wait on the writer monitor");
      } else {
        require(
            !blocked && oldFrame.committed > 0 && finished.getCount() == 0,
            "old committed star frame must arrive while writer is held");
      }
      require(held.delegateStart == 0, "held proof precedes Android commit");
    } finally {
      held.release();
    }
    require(finished.await(5, TimeUnit.SECONDS), "viewer reopen returned after gate release");
    if (failure.get() != null) throw new AssertionError(failure.get());
    require(held.done.await(5, TimeUnit.SECONDS) && held.success, "held write committed");
    require(
        !held.timedOut && !held.onMain && held.delegateStart >= held.released,
        "held writer and Android commit segments are separate");
    require(
        newFrame.ready.await(FRAME_WAIT_MS, TimeUnit.MILLISECONDS), "updated star frame displayed");
    require(
        "1".equals(prefs.getString(storeKey, null))
            && newFrame.committed >= held.delegateEnd
            && (baseline ? returned.get() >= held.delegateEnd : oldFrame.committed < held.released),
        "viewer state agrees with read gate and persisted singleton");
    ui(
        test,
        () -> {
          oldFrame.disarm();
          newFrame.disarm();
        });
    return "heldRead blocked="
        + blocked
        + " oldFrameBeforeRelease="
        + (oldFrame.committed > 0 && oldFrame.committed < held.released)
        + " inputToHandledNs="
        + (handled.get() - posted.get())
        + " handledToReturnNs="
        + (returned.get() - handled.get())
        + " gateNs="
        + (held.released - held.commitEntry)
        + " delegateCommitNs="
        + (held.delegateEnd - held.delegateStart)
        + " releaseToNewPreDrawNs="
        + (newFrame.preDraw - held.released)
        + " inputToNewFrameNs="
        + (newFrame.committed - posted.get());
  }

  private static boolean mainWaitingInSingleBest() {
    Thread main = Looper.getMainLooper().getThread();
    if (main.getState() != Thread.State.BLOCKED) return false;
    for (StackTraceElement frame : main.getStackTrace()) {
      if (frame.getClassName().equals(RankingStore.class.getName())
          && frame.getMethodName().equals("singleBest")) return true;
    }
    return false;
  }

  private static Button star(MainActivity activity) {
    View root = (View) get(activity, "root");
    return root.findViewWithTag("viewer-action-1位");
  }

  private static boolean matchingStar(MainActivity activity, View previous, boolean chosen) {
    if (get(activity, "root") == previous) return false;
    Button star = star(activity);
    return star != null && star.isShown() && star.isEnabled() && star.isSelected() == chosen;
  }

  private static String choose(
      Instrumentation test,
      MainActivity activity,
      SharedPreferences prefs,
      AtomicReference<Commit> next,
      boolean baseline,
      List<PhotoRepository.Photo> photos)
      throws Exception {
    List<String> keys = new ArrayList<>();
    for (int member : BURST) keys.add(ArchiveStore.key(photos.get(member)));
    keys.sort(String::compareTo);
    String key = "v1-" + PhotoRepository.digest(String.join("|", keys));
    require(!prefs.contains(key), "first comparison starts with an absent persisted row");

    ui(
        test,
        () -> {
          set(activity, "burstMembers", BURST);
          set(activity, "burstPosition", 0);
          call(activity, "openRanking");
        });
    View decor = activity.getWindow().getDecorView();
    Frame first = new Frame(decor, () -> comparisonVisible(activity, "B:"));
    ui(test, first::arm);
    require(
        first.ready.await(FRAME_WAIT_MS, TimeUnit.MILLISECONDS), "first comparison frame ready");
    require(!prefs.contains(key), "displayed B: is not a persisted row");

    Commit commit = new Commit(false);
    AtomicLong handled = new AtomicLong();
    Frame result = new Frame(decor, () -> comparisonVisible(activity, "B:0"));
    int[] location = new int[2];
    next.set(commit);
    ui(
        test,
        () -> {
          Button button = choice(activity);
          require(
              button != null && button.isShown() && button.isEnabled(), "comparison action ready");
          button.getLocationOnScreen(location);
          location[0] += button.getWidth() / 2;
          location[1] += button.getHeight() / 2;
          button.setOnTouchListener(
              (view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) handled.set(now());
                return false;
              });
          result.arm();
        });
    long downTime = SystemClock.uptimeMillis();
    sendPointer(test, downTime, MotionEvent.ACTION_DOWN, location);
    long input = now();
    sendPointer(test, downTime, MotionEvent.ACTION_UP, location);

    require(
        commit.done.await(5, TimeUnit.SECONDS) && commit.success, "comparison commit succeeded");
    require(
        result.ready.await(FRAME_WAIT_MS, TimeUnit.MILLISECONDS), "next comparison frame ready");
    require(
        commit.onMain == baseline
            && handled.get() >= input
            && commit.delegateStart >= handled.get()
            && result.preDraw >= commit.delegateEnd
            && "B:0".equals(prefs.getString(key, null)),
        "comparison input, thread, durable history and next frame agree");
    return "comparison kind=chooseA commitOnMain="
        + commit.onMain
        + " inputToHandledNs="
        + (handled.get() - input)
        + " handledToCommitNs="
        + (commit.delegateStart - handled.get())
        + " delegateCommitNs="
        + (commit.delegateEnd - commit.delegateStart)
        + " postCommitToPreDrawNs="
        + (result.preDraw - commit.delegateEnd)
        + " inputToResultFrameNs="
        + (result.committed - input);
  }

  private static Button choice(MainActivity activity) {
    return TagDetailChecks.button((View) get(activity, "root"), "Aを選ぶ");
  }

  private static boolean comparisonVisible(MainActivity activity, String journal) {
    PhotoRanking ranking = (PhotoRanking) get(activity, "ranking");
    Button button = choice(activity);
    return ranking != null
        && journal.equals(ranking.journal)
        && button != null
        && button.isShown()
        && button.isEnabled();
  }

  private static void sendPointer(Instrumentation test, long down, int action, int[] location) {
    MotionEvent event =
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, location[0], location[1], 0);
    try {
      test.sendPointerSync(event);
    } finally {
      event.recycle();
    }
  }

  private static long now() {
    return SystemClock.elapsedRealtimeNanos();
  }

  private static final class Frame implements ViewTreeObserver.OnPreDrawListener {
    final View decor;
    final BooleanSupplier matches;
    final CountDownLatch ready = new CountDownLatch(1);
    volatile long preDraw, committed;

    Frame(View decor, BooleanSupplier matches) {
      this.decor = decor;
      this.matches = matches;
    }

    void arm() {
      require(decor.isHardwareAccelerated(), "hardware frame commit required");
      decor.getViewTreeObserver().addOnPreDrawListener(this);
      decor.invalidate();
    }

    void disarm() {
      decor.getViewTreeObserver().removeOnPreDrawListener(this);
    }

    @Override
    public boolean onPreDraw() {
      if (!matches.getAsBoolean()) return true;
      disarm();
      preDraw = now();
      decor
          .getViewTreeObserver()
          .registerFrameCommitCallback(
              () -> {
                committed = now();
                ready.countDown();
              });
      return true;
    }
  }

  private static SharedPreferences observe(SharedPreferences actual, AtomicReference<Commit> next) {
    return (SharedPreferences)
        Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(),
            new Class<?>[] {SharedPreferences.class},
            (proxy, method, args) -> {
              if (method.getName().equals("equals")) return proxy == args[0];
              if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
              Object value = invoke(method, actual, args);
              if (!method.getName().equals("edit")) return value;
              return Proxy.newProxyInstance(
                  SharedPreferences.Editor.class.getClassLoader(),
                  new Class<?>[] {SharedPreferences.Editor.class},
                  (editor, operation, values) -> {
                    if (operation.getName().equals("commit")) {
                      Commit commit = next.getAndSet(null);
                      if (commit != null) return commit.run((SharedPreferences.Editor) value);
                    }
                    Object result = invoke(operation, value, values);
                    return result == value ? editor : result;
                  });
            });
  }

  private static Object invoke(Method method, Object owner, Object[] args) throws Throwable {
    try {
      return method.invoke(owner, args);
    } catch (InvocationTargetException error) {
      throw error.getCause();
    }
  }

  private static final class Commit {
    final CountDownLatch entered = new CountDownLatch(1), done = new CountDownLatch(1);
    private final CountDownLatch gate;
    volatile long commitEntry, released, delegateStart, delegateEnd;
    volatile boolean onMain, success, timedOut;

    Commit(boolean held) {
      gate = new CountDownLatch(held ? 1 : 0);
    }

    void release() {
      if (gate.getCount() != 0) {
        released = now();
        gate.countDown();
      }
    }

    boolean run(SharedPreferences.Editor actual) throws InterruptedException {
      onMain = Looper.myLooper() == Looper.getMainLooper();
      commitEntry = now();
      entered.countDown();
      try {
        if (!gate.await(GATE_LIMIT_MS, TimeUnit.MILLISECONDS)) {
          timedOut = true;
          return false;
        }
        delegateStart = now();
        try {
          success = actual.commit();
          return success;
        } finally {
          delegateEnd = now();
        }
      } finally {
        done.countDown();
      }
    }
  }
}
