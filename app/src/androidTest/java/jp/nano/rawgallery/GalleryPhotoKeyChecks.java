package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.security.MessageDigestSpi;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Measures the real gallery adapter through the point where previewInto sets the bitmap. */
final class GalleryPhotoKeyChecks {
  private static final String DIGEST_PROVIDER = "LoupeGalleryPhotoKeyCounter";
  private static final String KEY_PADDING =
      "0000000000000000000000000000000000000000000000000000000000000000";
  private static final int BATCH = 128;

  static String run(Instrumentation test, Bundle arguments) throws Exception {
    return run(test, arguments, null);
  }

  static String run(Instrumentation test, Bundle arguments, Runnable beforeCountedBatch)
      throws Exception {
    requireEmulator();
    int size = Integer.parseInt(arguments.getString("photos", "1024"));
    require(size >= 1024 && size <= 30000, "Photo count outside fixture range");
    boolean record = Boolean.parseBoolean(arguments.getString("record", "false"));
    boolean count = Boolean.parseBoolean(arguments.getString("count", "true"));
    String identity = arguments.getString("identity", "legacy");
    boolean resolved =
        switch (identity) {
          case "legacy" -> false;
          case "resolved" -> true;
          default -> throw new IllegalArgumentException("identity must be legacy or resolved");
        };

    Context context = test.getTargetContext();
    try (RecordingState saved = new RecordingState(context)) {
      MainActivity activity =
          (MainActivity)
              test.startActivitySync(
                  new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        GalleryScreenChecks.finishInitialIndex(test, activity);
        setRecording(context, false);
        Harness harness = new Harness(test, activity);
        harness.prepare(BATCH, resolved);
        harness.pass(false, null); // Warm the adapter and view classes on separate Photo instances.
        setRecording(context, record);
        harness.prepare(size, resolved);
        if (count) installCounter();
        try {
          String first = harness.pass(count, beforeCountedBatch);
          String repeat = harness.pass(count, beforeCountedBatch);
          return "PASS gallery-photo-keys photos="
              + size
              + " diagnostics="
              + record
              + " digestCounter="
              + count
              + " cache=preseeded-memory\nidentity="
              + identity
              + "\nfirst "
              + first
              + "\nrepeat "
              + repeat
              + "\n";
        } finally {
          if (count) Security.removeProvider(DIGEST_PROVIDER);
        }
      } finally {
        TagDetailChecks.ui(test, activity::finish);
      }
    }
  }

  static void requireEmulator() {
    require("ranchu".equals(Build.HARDWARE), "Gallery key fixture requires an emulator");
  }

  static final class RecordingState implements AutoCloseable {
    private final Context context;
    private final SharedPreferences settings;
    private final boolean hadSetting;
    private final boolean previousSetting;
    private final boolean wasRecording;

    RecordingState(Context context) {
      this.context = context;
      settings = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE);
      hadSetting = settings.contains("record");
      previousSetting = settings.getBoolean("record", false);
      wasRecording = DiagnosticLog.currentRecorder() != null;
    }

    @Override
    public void close() {
      try {
        setRecording(context, wasRecording);
      } finally {
        SharedPreferences.Editor restore = settings.edit();
        if (hadSetting) restore.putBoolean("record", previousSetting);
        else restore.remove("record");
        require(restore.commit(), "Cannot restore recording preference");
      }
    }
  }

  static void setRecording(Context context, boolean enabled) {
    DiagnosticLog.Recorder recorder = DiagnosticLog.currentRecorder();
    if (enabled && recorder == null) {
      require(DiagnosticLog.startRecording(context), "Cannot start diagnostics");
    } else if (!enabled && recorder != null) {
      require(DiagnosticLog.stopRecording(context, recorder), "Cannot stop diagnostics");
    }
    require((DiagnosticLog.currentRecorder() != null) == enabled, "Unexpected diagnostic state");
  }

  private static final class Harness {
    private final Instrumentation test;
    private final MainActivity activity;
    private final BaseAdapter adapter;
    private final Map<Integer, List<Integer>> photoBundles;
    private final LruCache<String, Bitmap> thumbs;
    private final FrameLayout root;
    private final ThumbnailCell cell;
    private final Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
    private final Field request = field(DiagnosticImageView.class, "request");
    private final Field drawn = field(DiagnosticImageView.class, "drawn");
    private int size;

    @SuppressWarnings("unchecked")
    Harness(Instrumentation test, MainActivity activity) throws Exception {
      this.test = test;
      this.activity = activity;
      var constructor =
          Class.forName(MainActivity.class.getName() + "$GalleryAdapter")
              .getDeclaredConstructor(MainActivity.class);
      constructor.setAccessible(true);
      adapter = (BaseAdapter) constructor.newInstance(activity);
      photoBundles = (Map<Integer, List<Integer>>) field("photoBundles").get(activity);
      thumbs = (LruCache<String, Bitmap>) field("thumbs").get(activity);
      bitmap.eraseColor(Color.RED);
      FrameLayout[] roots = new FrameLayout[1];
      ThumbnailCell[] cells = new ThumbnailCell[1];
      TagDetailChecks.ui(
          test,
          () -> {
            roots[0] = new FrameLayout(activity);
            cells[0] = new ThumbnailCell(activity);
            roots[0].addView(cells[0]);
            activity.setContentView(roots[0]);
          });
      root = roots[0];
      cell = cells[0];
    }

    void prepare(int count, boolean resolved) throws Exception {
      size = count;
      List<PhotoRepository.Photo> photos = new ArrayList<>(count);
      List<List<Integer>> groups = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        String name = i + ".jpg";
        PhotoMoveStore.Identity identity = null;
        if (resolved) {
          String suffix = Integer.toHexString(i);
          String storedKey = KEY_PADDING.substring(suffix.length()) + suffix;
          identity =
              new PhotoMoveStore.Identity(storedKey, "gallery-photo-key-fixture:" + i, 1_000_000L);
        }
        photos.add(
            new PhotoRepository.Photo(
                Uri.parse("file:///gallery-photo-key-fixture/" + name),
                name,
                "gallery-photo-key-fixture/",
                2048,
                1000,
                0,
                0,
                0,
                identity));
        groups.add(List.of(i));
      }
      Field photosField = field("photos");
      Field displayedField = field("displayed");
      Field displayedRanksField = field("displayedRanks");
      RankingStore.Snapshot ranks =
          ((RankingStore) field("rankingStore").get(activity)).awaitReady();
      TagDetailChecks.ui(
          test,
          () -> {
            try {
              photosField.set(activity, photos);
              displayedField.set(activity, groups);
              displayedRanksField.set(activity, ranks);
            } catch (IllegalAccessException e) {
              throw new AssertionError(e);
            }
            thumbs.evictAll();
            photoBundles.clear();
            for (int i = 0; i < count; i++) {
              photoBundles.put(i, groups.get(i));
              thumbs.put(PreviewPlaceholders.key(photos.get(i)) + ":400", bitmap);
            }
          });
    }

    String pass(boolean count, Runnable beforeCountedBatch) throws Exception {
      int[] counts = new int[3];
      long[] elapsed = new long[1];
      CompletableFuture<Long> frame = new CompletableFuture<>();
      int hits = thumbs.hitCount();
      for (int offset = 0; offset < size; offset += BATCH) {
        int start = offset;
        int end = Math.min(size, offset + BATCH);
        if (count && beforeCountedBatch != null) TagDetailChecks.ui(test, beforeCountedBatch);
        TagDetailChecks.ui(
            test,
            () -> {
              if (count) Arrays.fill(CountingDigest.counts, 0);
              long before = SystemClock.elapsedRealtimeNanos();
              long lastBind = 0;
              for (int position = start; position < end; position++) {
                if (position == size - 1) lastBind = SystemClock.elapsedRealtimeNanos();
                adapter.getView(position, cell, root);
                require(
                    cell.image.getDrawable() instanceof BitmapDrawable
                        && ((BitmapDrawable) cell.image.getDrawable()).getBitmap() == bitmap,
                    "Gallery did not apply the prepared bitmap");
              }
              elapsed[0] += SystemClock.elapsedRealtimeNanos() - before;
              if (count) {
                for (int i = 0; i < counts.length; i++) {
                  counts[i] += CountingDigest.counts[i];
                  CountingDigest.counts[i] = 0;
                }
              }
              if (lastBind != 0) observeLastFrame(frame, lastBind);
            });
      }
      require(thumbs.hitCount() - hits == size, "Preview did not get a memory hit for every bind");
      long frameNanos = frame.get(5, TimeUnit.SECONDS);
      String digests =
          count
              ? "archive=" + counts[0] + " diagnostic=" + counts[1] + " ranking=" + counts[2]
              : "digest_counts=disabled";
      return digests + " ui_nanos=" + elapsed[0] + " last_bind_to_frame_commit_nanos=" + frameNanos;
    }

    private void observeLastFrame(CompletableFuture<Long> frame, long lastBind) {
      require(cell.image.isHardwareAccelerated(), "Fixture requires hardware frame commits");
      try {
        long finalRequest = request.getLong(cell.image);
        root.getViewTreeObserver()
            .registerFrameCommitCallback(
                () -> {
                  try {
                    require(
                        finalRequest != 0 && drawn.getLong(cell.image) == finalRequest,
                        "Final gallery bitmap was not drawn in the committed frame");
                    frame.complete(SystemClock.elapsedRealtimeNanos() - lastBind);
                  } catch (IllegalAccessException | AssertionError failure) {
                    frame.completeExceptionally(failure);
                  }
                });
        cell.image.invalidate();
      } catch (IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }
  }

  private static Field field(String name) throws NoSuchFieldException {
    return field(MainActivity.class, name);
  }

  private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static void installCounter() throws Exception {
    CountingDigest.delegate = MessageDigest.getInstance("SHA-256").getProvider();
    Provider counter = new Provider(DIGEST_PROVIDER, 1.0, "Counts gallery SHA-256 calls") {};
    counter.put("MessageDigest.SHA-256", CountingDigest.class.getName());
    require(Security.insertProviderAt(counter, 1) == 1, "Cannot install gallery digest counter");
    if (MessageDigest.getInstance("SHA-256").getProvider() != counter) {
      Security.removeProvider(DIGEST_PROVIDER);
      throw new AssertionError("Android did not select gallery digest counter");
    }
  }

  public static final class CountingDigest extends MessageDigestSpi {
    private static final int[] counts = new int[3];
    private static Provider delegate;
    private final MessageDigest digest;

    public CountingDigest() throws NoSuchAlgorithmException {
      digest = MessageDigest.getInstance("SHA-256", delegate);
    }

    @Override
    protected void engineUpdate(byte input) {
      digest.update(input);
    }

    @Override
    protected void engineUpdate(byte[] input, int offset, int len) {
      digest.update(input, offset, len);
    }

    @Override
    protected byte[] engineDigest() {
      if (Looper.myLooper() == Looper.getMainLooper()) {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
          String owner = element.getClassName();
          String method = element.getMethodName();
          if (owner.equals(ArchiveStore.class.getName()) && method.equals("key")) {
            counts[0]++;
            break;
          }
          if (owner.equals(DiagnosticLog.class.getName()) && method.equals("photo")) {
            counts[1]++;
            break;
          }
          if (owner.equals(RankingStore.class.getName())) {
            counts[2]++;
            break;
          }
        }
      }
      return digest.digest();
    }

    @Override
    protected void engineReset() {
      digest.reset();
    }
  }

  private static void require(boolean condition, String problem) {
    if (!condition) throw new AssertionError(problem);
  }
}
