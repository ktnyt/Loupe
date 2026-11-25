package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inspector.WindowInspector;
import android.widget.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/**
 * Synthetic originals only. Synthetic build of the external recipient plus real internal editor end
 * to end.
 */
final class PhotoEditChecks {
  static final class MonoTintFailure extends AssertionError {
    MonoTintFailure(String message) {
      super(message);
    }
  }

  static void await(BooleanSupplier condition) throws Exception {
    long until = android.os.SystemClock.uptimeMillis() + 5000;
    while (!condition.getAsBoolean()) {
      if (android.os.SystemClock.uptimeMillis() > until)
        throw new AssertionError("edit condition timed out");
      Thread.sleep(20);
    }
  }

  static final class Fixture {
    PhotoViewerUi viewer;
    PhotoEditor editor;
  }

  static Fixture fixture(
      Instrumentation t,
      MainActivity a,
      PhotoRepository.Photo photo,
      ExecutorService worker,
      SharePreparation preparation)
      throws Exception {
    Fixture f = new Fixture();
    TagDetailChecks.ui(
        t,
        () -> {
          f.viewer = new PhotoViewerUi(a, photo);
          f.viewer.zoom();
          f.editor = new PhotoEditor(a, f.viewer, photo, worker, preparation);
          a.setContentView(f.viewer);
        });
    t.waitForIdleSync();
    await(() -> f.viewer.hasWindowFocus());
    return f;
  }

  static void click(Instrumentation t, Fixture f) throws Exception {
    TagDetailChecks.ui(t, () -> f.editor.button.performLongClick());
  }

  static String label(Instrumentation t, Fixture f) throws Exception {
    String[] result = {""};
    TagDetailChecks.ui(t, () -> result[0] = f.editor.button.getText().toString());
    return result[0];
  }

  private static Button findButton(View root, String text) {
    if (root instanceof Button && text.contentEquals(((Button) root).getText()))
      return (Button) root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        Button found = findButton(((ViewGroup) root).getChildAt(i), text);
        if (found != null) return found;
      }
    return null;
  }

  private static Button button(Activity a, String text) {
    return findButton(a.getWindow().getDecorView(), text);
  }

  private static void awaitUi(
      Instrumentation t, long timeout, BooleanSupplier condition, String message) throws Exception {
    long until = android.os.SystemClock.uptimeMillis() + timeout;
    boolean[] done = {false};
    while (android.os.SystemClock.uptimeMillis() < until) {
      TagDetailChecks.ui(t, () -> done[0] = condition.getAsBoolean());
      if (done[0]) return;
      Thread.sleep(25);
    }
    throw new AssertionError(message);
  }

  private static AccessibilityNodeInfo node(
      AccessibilityNodeInfo root, String text, boolean clickable) {
    if (root == null) return null;
    if (text.contentEquals(root.getText() == null ? "" : root.getText())
        && (!clickable || root.isClickable())) return root;
    for (int i = 0; i < root.getChildCount(); i++) {
      AccessibilityNodeInfo found = node(root.getChild(i), text, clickable);
      if (found != null) return found;
    }
    return null;
  }

  static void dialog(Instrumentation t, String title, String action) throws Exception {
    long until = android.os.SystemClock.uptimeMillis() + 5000;
    while (android.os.SystemClock.uptimeMillis() < until) {
      AccessibilityNodeInfo root = t.getUiAutomation().getRootInActiveWindow();
      if (node(root, title, false) != null) {
        AccessibilityNodeInfo choice = node(root, action, true);
        if (choice != null && choice.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
          t.waitForIdleSync();
          return;
        }
      }
      Thread.sleep(25);
    }
    throw new AssertionError("dialog action timed out: " + title + " / " + action);
  }

  private static boolean hasText(View root, String text) {
    if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return true;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
        if (hasText(((ViewGroup) root).getChildAt(i), text)) return true;
    return false;
  }

  private static Button dialogButton(String title, String action) {
    for (View root : WindowInspector.getGlobalWindowViews())
      if (root.isShown() && root.hasWindowFocus() && hasText(root, title)) {
        Button found = findButton(root, action);
        if (found != null && found.isEnabled()) return found;
      }
    return null;
  }

  private static Set<Long> outputs(ContentResolver resolver, String owner) {
    Set<Long> result = new HashSet<>();
    String where =
        MediaStore.Images.Media.OWNER_PACKAGE_NAME
            + "=? AND "
            + MediaStore.Images.Media.RELATIVE_PATH
            + " IN (?,?) AND "
            + MediaStore.Images.Media.MIME_TYPE
            + "=? AND "
            + MediaStore.Images.Media.DISPLAY_NAME
            + " LIKE ?";
    try (Cursor rows =
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            new String[] {MediaStore.Images.Media._ID},
            where,
            new String[] {
              owner, "Pictures/LoupeDevelop", "Pictures/LoupeDevelop/", "image/jpeg", "Loupe-%.jpg"
            },
            null)) {
      TagDetailChecks.require(rows != null, "owned output query available");
      while (rows.moveToNext()) result.add(rows.getLong(0));
    }
    return result;
  }

  private static void cancelInternalSave(
      Instrumentation t, MainActivity main, ExecutorService worker, List<File> copies)
      throws Exception {
    ContentResolver resolver = main.getContentResolver();
    String owner = main.getPackageName();
    Set<Long> baseline = outputs(resolver, owner);
    Instrumentation.ActivityMonitor live =
        t.addMonitor(RawEditActivity.class.getName(), null, false);
    RawEditActivity current = null;
    Uri ownPending = null;
    boolean ambiguous = false;
    Throwable primary = null;
    File source = null;
    try {
      // Separate fixture: enough real rendering/encoding work to observe saving,
      // without delaying production code or changing the small export/tint test.
      source = File.createTempFile("edit-cancel-large-", ".jpg", main.getCacheDir());
      Bitmap large = Bitmap.createBitmap(2048, 1536, Bitmap.Config.ARGB_8888);
      int[] row = new int[2048];
      try {
        for (int y = 0; y < 1536; y++) {
          for (int x = 0; x < 2048; x++)
            row[x] =
                Color.rgb((x * 13 + y * 7) & 255, (x * 3 + y * 11) & 255, (x * 5 + y * 17) & 255);
          large.setPixels(row, 0, 2048, 0, y, 2048, 1);
        }
        try (OutputStream stream = new FileOutputStream(source)) {
          TagDetailChecks.require(
              large.compress(Bitmap.CompressFormat.JPEG, 95, stream),
              "large synthetic cancel JPEG encoded");
        }
      } finally {
        large.recycle();
      }
      byte[] before = Files.readAllBytes(source.toPath());
      PhotoRepository.Photo photo =
          new PhotoRepository.Photo(
              Uri.fromFile(source),
              "edit-integration-cancel.jpg",
              "/synthetic",
              source.length(),
              0,
              0);
      Fixture viewer =
          fixture(t, main, photo, worker, new SharePreparation(main, new PhotoRepository(main)));
      TagDetailChecks.ui(t, () -> viewer.editor.button.performClick());
      Activity opened = live.waitForActivityWithTimeout(10000);
      TagDetailChecks.require(
          opened instanceof RawEditActivity, "second real internal Activity starts");
      current = (RawEditActivity) opened;
      RawEditActivity editor = current;
      Uri input = editor.getIntent().getData();
      TagDetailChecks.require(
          input != null && "jp.nano.rawgallery.share".equals(input.getAuthority()),
          "cancel test has its own prepared copy");
      File copy = new File(main.getCacheDir(), "shares/" + input.getLastPathSegment());
      copies.add(copy);
      long openedAt = android.os.SystemClock.elapsedRealtime();
      awaitUi(
          t,
          15000,
          () -> button(editor, "保存") != null && button(editor, "保存").isEnabled(),
          "cancel test preview ready");
      RawEditLog.event(
          "fixture.cancel.preview.ready",
          android.os.SystemClock.elapsedRealtime() - openedAt,
          2048,
          1536);
      long clickedAt = android.os.SystemClock.elapsedRealtime();
      TagDetailChecks.ui(
          t,
          () -> {
            boolean clicked = button(editor, "保存").performClick();
            RawEditLog.event(
                clicked ? "fixture.cancel.save.clicked" : "fixture.cancel.save.no_listener",
                0,
                2048,
                1536);
          });
      try {
        awaitUi(
            t,
            30000,
            () -> dialogButton("JPEGで保存", "保存") != null,
            "cancel test full-resolution confirmation visible");
        RawEditLog.event(
            "fixture.cancel.dialog.visible",
            android.os.SystemClock.elapsedRealtime() - clickedAt,
            2048,
            1536);
      } catch (AssertionError missingDialog) {
        RawEditLog.event(
            "fixture.cancel.dialog.missing",
            android.os.SystemClock.elapsedRealtime() - clickedAt,
            2048,
            1536);
        throw missingDialog;
      }
      TagDetailChecks.ui(t, () -> dialogButton("JPEGで保存", "保存").performClick());
      // AlertDialog posts its listener through a Handler. A later UI callback
      // must observe saving before Back; a completed/unstarted save is a failure.
      TagDetailChecks.ui(
          t,
          () -> {
            Button savingButton = button(editor, "保存");
            TagDetailChecks.require(
                savingButton != null
                    && !savingButton.isEnabled()
                    && "保存中"
                        .contentEquals(
                            savingButton.getStateDescription() == null
                                ? ""
                                : savingButton.getStateDescription()),
                "must observe disabled save button with saving state before cancellation");
            editor.onBackPressed();
            TagDetailChecks.require(
                !editor.isFinishing(), "Back during observed saving cancels into the same editor");
          });
      awaitUi(
          t,
          10000,
          () ->
              button(editor, "保存") != null
                  && button(editor, "保存").isEnabled()
                  && button(editor, "保存").getStateDescription() == null,
          "cancelled saving returns to editing after cleanup");
      // Saving was observed through UI, but no hook forces the worker beyond
      // insert(). This does not claim cancellation after pending-row creation.
      long until = android.os.SystemClock.uptimeMillis() + 3000;
      Set<Long> remaining = Collections.emptySet();
      while (android.os.SystemClock.uptimeMillis() < until) {
        remaining = outputs(resolver, owner);
        remaining.removeAll(baseline);
        if (remaining.size() > 1) {
          ambiguous = true;
          throw new AssertionError("ambiguous media after immediate save cancellation");
        }
        if (remaining.size() == 1)
          ownPending =
              ContentUris.withAppendedId(
                  MediaStore.Images.Media.EXTERNAL_CONTENT_URI, remaining.iterator().next());
        Thread.sleep(50);
      }
      remaining = outputs(resolver, owner);
      remaining.removeAll(baseline);
      if (remaining.size() > 1) ambiguous = true;
      TagDetailChecks.require(
          remaining.isEmpty(),
          "immediate save cancellation leaves no new owned pending or published output in observation window");
      TagDetailChecks.require(
          Arrays.equals(before, Files.readAllBytes(source.toPath()))
              && Arrays.equals(before, Files.readAllBytes(copy.toPath())),
          "save cancellation preserves original and input copy bytes");
    } catch (Exception | Error failure) {
      primary = failure;
      throw failure;
    } finally {
      Throwable cleanup = null;
      try {
        if (current == null && live.getLastActivity() instanceof RawEditActivity)
          current = (RawEditActivity) live.getLastActivity();
        if (current != null) {
          RawEditActivity closing = current;
          TagDetailChecks.ui(t, closing::finish);
        }
      } catch (Exception | Error failure) {
        cleanup = failure;
      } finally {
        t.removeMonitor(live);
      }
      try {
        // Also clean a single output if saving finished too fast for the required
        // observation. That case fails above rather than passing cancellation.
        if (ownPending == null && !ambiguous) {
          Set<Long> made = outputs(resolver, owner);
          made.removeAll(baseline);
          if (made.size() == 1)
            ownPending =
                ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, made.iterator().next());
          else if (made.size() > 1) ambiguous = true;
        }
        if (ownPending != null && !ambiguous) resolver.delete(ownPending, null, null);
      } catch (Exception | Error failure) {
        if (cleanup == null) cleanup = failure;
        else cleanup.addSuppressed(failure);
      } finally {
        if (source != null && source.exists() && !source.delete()) {
          IOException failure = new IOException("large synthetic cancel fixture cleanup failed");
          if (cleanup == null) cleanup = failure;
          else cleanup.addSuppressed(failure);
        }
      }
      if (cleanup != null) {
        if (primary != null) primary.addSuppressed(cleanup);
        else if (cleanup instanceof Error) throw (Error) cleanup;
        else throw (Exception) cleanup;
      }
    }
  }

  private static void internalEditor(
      Instrumentation t, MainActivity main, ExecutorService worker, List<File> copies)
      throws Exception {
    File source = File.createTempFile("edit-integration-", ".jpg", main.getCacheDir());
    ContentResolver resolver = main.getContentResolver();
    String owner = main.getPackageName();
    Instrumentation.ActivityMonitor live =
        t.addMonitor(RawEditActivity.class.getName(), null, false);
    AtomicReference<RawEditActivity> active = new AtomicReference<>();
    Set<Long> baseline = null;
    Uri output = null;
    boolean saveStarted = false, ambiguous = false;
    Throwable primary = null;
    try {
      Bitmap fixture = Bitmap.createBitmap(192, 128, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(fixture);
      Paint paint = new Paint();
      int[] colors = {Color.rgb(210, 75, 45), Color.rgb(85, 180, 110), Color.rgb(128, 128, 128)};
      for (int i = 0; i < 3; i++) {
        paint.setColor(colors[i]);
        canvas.drawRect(i * 64, 0, (i + 1) * 64, 128, paint);
      }
      try (OutputStream stream = new FileOutputStream(source)) {
        TagDetailChecks.require(
            fixture.compress(Bitmap.CompressFormat.JPEG, 95, stream),
            "synthetic integration JPEG encoded");
      } finally {
        fixture.recycle();
      }
      byte[] before = Files.readAllBytes(source.toPath());
      PhotoRepository.Photo photo =
          new PhotoRepository.Photo(
              Uri.fromFile(source), "edit-integration.jpg", "/synthetic", source.length(), 0, 0);
      Fixture viewer =
          fixture(t, main, photo, worker, new SharePreparation(main, new PhotoRepository(main)));
      TagDetailChecks.ui(t, () -> viewer.editor.button.performClick());
      Activity launched = live.waitForActivityWithTimeout(10000);
      TagDetailChecks.require(
          launched instanceof RawEditActivity, "normal tap starts real internal Activity");
      RawEditActivity editor = (RawEditActivity) launched;
      active.set(editor);
      Uri input = editor.getIntent().getData();
      TagDetailChecks.require(
          input != null && "jp.nano.rawgallery.share".equals(input.getAuthority()),
          "real editor receives prepared content copy");
      File copy = new File(main.getCacheDir(), "shares/" + input.getLastPathSegment());
      copies.add(copy);
      TagDetailChecks.require(
          Arrays.equals(before, Files.readAllBytes(copy.toPath())),
          "real editor's copy preserves input bytes");
      awaitUi(
          t,
          15000,
          () -> button(editor, "保存") != null && button(editor, "保存").isEnabled(),
          "internal JPEG preview ready");
      TagDetailChecks.ui(
          t,
          () -> {
            button(editor, "カラー").performClick();
            button(editor, "モノクロ").performClick();
            TagDetailChecks.require(
                button(editor, "モノクロ").isSelected(), "Mono selected through UI");
          });
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
      dialog(t, "変更を破棄しますか？", "編集を続ける");
      TagDetailChecks.ui(
          t,
          () ->
              TagDetailChecks.require(
                  !editor.isFinishing() && button(editor, "モノクロ").isSelected(),
                  "Back cancel retains unsaved Mono"));
      Instrumentation.ActivityMonitor recreated =
          t.addMonitor(RawEditActivity.class.getName(), null, false);
      try {
        TagDetailChecks.ui(t, editor::recreate);
        Activity restored = recreated.waitForActivityWithTimeout(10000);
        TagDetailChecks.require(
            restored instanceof RawEditActivity && restored != editor,
            "internal Activity recreated");
        active.set((RawEditActivity) restored);
      } finally {
        t.removeMonitor(recreated);
      }
      RawEditActivity current = active.get();
      awaitUi(
          t,
          15000,
          () -> button(current, "保存") != null && button(current, "保存").isEnabled(),
          "restored internal JPEG preview ready");
      TagDetailChecks.ui(
          t,
          () ->
              TagDetailChecks.require(
                  button(current, "カラー").isSelected()
                      && button(current, "モノクロ") != null
                      && button(current, "モノクロ").isSelected(),
                  "recreation restores color tab and Mono"));
      baseline = outputs(resolver, owner);
      TagDetailChecks.ui(t, () -> button(current, "保存").performClick());
      saveStarted = true;
      dialog(t, "JPEGで保存", "保存");
      long until = android.os.SystemClock.uptimeMillis() + 15000;
      boolean published = false;
      while (android.os.SystemClock.uptimeMillis() < until) {
        Set<Long> made = outputs(resolver, owner);
        made.removeAll(baseline);
        if (made.size() > 1) {
          ambiguous = true;
          throw new AssertionError("multiple new outputs; preserve ambiguous media");
        }
        if (made.size() == 1) {
          output =
              ContentUris.withAppendedId(
                  MediaStore.Images.Media.EXTERNAL_CONTENT_URI, made.iterator().next());
          try (Cursor row =
              resolver.query(
                  output,
                  new String[] {
                    MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.MIME_TYPE
                  },
                  null,
                  null,
                  null)) {
            if (row != null && row.moveToFirst() && row.getInt(0) == 0) {
              TagDetailChecks.require(
                  "image/jpeg".equals(row.getString(1)), "published output is JPEG");
              published = true;
              break;
            }
          }
        }
        Thread.sleep(25);
      }
      TagDetailChecks.require(
          published && output != null, "single JPEG output published within deadline");
      awaitUi(
          t,
          5000,
          () ->
              button(current, "✓") != null
                  || button(current, "保存") != null && button(current, "保存").isEnabled(),
          "save completes in UI");
      Bitmap written;
      try (InputStream stream = resolver.openInputStream(output)) {
        written = BitmapFactory.decodeStream(stream);
      }
      Bitmap original = BitmapFactory.decodeByteArray(before, 0, before.length);
      try {
        TagDetailChecks.require(
            written != null && written.getWidth() == 192 && written.getHeight() == 128,
            "MediaStore JPEG preserves fixture dimensions");
        checkSavedMonoTint(main, input, original, written);
      } finally {
        if (written != null) written.recycle();
        if (original != null) original.recycle();
      }
      TagDetailChecks.require(
          Arrays.equals(before, Files.readAllBytes(source.toPath()))
              && Arrays.equals(before, Files.readAllBytes(copy.toPath())),
          "full editor/save leaves synthetic original and input copy unchanged");
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
      awaitUi(t, 5000, current::isFinishing, "Back after saved state exits without discard dialog");
      cancelInternalSave(t, main, worker, copies);
    } catch (Exception | Error failure) {
      primary = failure;
      throw failure;
    } finally {
      Throwable cleanup = null;
      try {
        RawEditActivity current = active.get();
        Activity last = live.getLastActivity();
        if (last instanceof RawEditActivity && !last.isDestroyed())
          current = (RawEditActivity) last;
        if (current != null) {
          RawEditActivity closing = current;
          TagDetailChecks.ui(t, closing::finish);
        }
      } catch (Exception | Error failure) {
        cleanup = failure;
      } finally {
        t.removeMonitor(live);
      }
      try {
        // Only a single unambiguous row created after this test's save can be removed.
        if (output == null && saveStarted && baseline != null && !ambiguous) {
          Set<Long> made = outputs(resolver, owner);
          made.removeAll(baseline);
          if (made.size() == 1)
            output =
                ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, made.iterator().next());
        }
        if (output != null && !ambiguous) resolver.delete(output, null, null);
      } catch (Exception | Error failure) {
        if (cleanup == null) cleanup = failure;
        else cleanup.addSuppressed(failure);
      } finally {
        if (source.exists() && !source.delete()) {
          IOException failure = new IOException("synthetic integration fixture cleanup failed");
          if (cleanup == null) cleanup = failure;
          else cleanup.addSuppressed(failure);
        }
      }
      if (cleanup != null) {
        if (primary != null) primary.addSuppressed(cleanup);
        else if (cleanup instanceof Error) throw (Error) cleanup;
        else throw (Exception) cleanup;
      }
    }
  }

  private static void checkSavedMonoTint(
      Context context, Uri inputUri, Bitmap original, Bitmap written) {
    StringBuilder samples = new StringBuilder();
    boolean withinTolerance = true;
    boolean tintOrder = true;
    for (int x : new int[] {32, 96, 160}) {
      int start = original.getPixel(x, 64);
      int pixel = written.getPixel(x, 64);
      int[] input = {Color.red(start), Color.green(start), Color.blue(start)};
      int[] actual = {Color.red(pixel), Color.green(pixel), Color.blue(pixel)};
      float luminance = (.2126f * input[0] + .7152f * input[1] + .0722f * input[2]) / 255f;
      float light = Math.min(1, luminance + .02f);
      float chroma = (1 - Math.abs(2 * light - 1)) * .11f;
      int[] expected = {
        Math.round((light - chroma / 6) * 255),
        Math.round((light - chroma / 2) * 255),
        Math.round((light + chroma / 2) * 255)
      };
      for (int channel = 0; channel < 3; channel++) {
        withinTolerance &= Math.abs(actual[channel] - expected[channel]) <= 6;
      }
      tintOrder &= actual[2] > actual[0] && actual[0] > actual[1];
      samples
          .append(" x=")
          .append(x)
          .append(" input=")
          .append(Arrays.toString(input))
          .append(" expected=")
          .append(Arrays.toString(expected))
          .append(" saved=")
          .append(Arrays.toString(actual));
    }
    if (!withinTolerance || !tintOrder) {
      // Input and output are generated from the test's three synthetic color bands.
      throw new MonoTintFailure(
          "Mono JPEG tolerance="
              + withinTolerance
              + " tintOrder="
              + tintOrder
              + samples
              + describeMonoExport(context, inputUri));
    }
  }

  /** Repeats each export boundary on the verified synthetic copy after a color assertion fails. */
  private static String describeMonoExport(Context context, Uri inputUri) {
    StringBuilder report = new StringBuilder();
    File directory = null;
    File fullCache = null;
    File jpeg = null;
    String stage = "source";
    try (RawEditSource source = RawEditSource.load(context, inputUri)) {
      directory = Files.createTempDirectory(context.getCacheDir().toPath(), "mono-probe-").toFile();
      fullCache = new File(directory, "rgb16.ppm");
      jpeg = new File(directory, "mono.jpg");
      source.prepareFull(fullCache, new NativeRaw.Cancellation());
      stage = "linear cache";
      int[] columns = {32, 96, 160};
      try (RawEditFullSource full = RawEditFullSource.open(fullCache)) {
        for (int x : columns) {
          ByteBuffer decoded = full.region(new Rect(x, 64, x + 1, 65));
          int[] rgb = new int[3];
          for (int c = 0; c < rgb.length; c++)
            rgb[c] = (int) Math.round(RawEditTone.encode(decoded.getFloat()) * 255);
          report.append(" cache[").append(x).append("]=").append(Arrays.toString(rgb));
        }
        stage = "EGL readback";
        try (RawEditRenderer renderer = new RawEditRenderer()) {
          RectF bounds = new RectF(0, 0, full.width, full.height);
          RawEditState mono = new RawEditState().with(RawEditState.MONO, 1);
          ByteBuffer before =
              renderer.renderRegionBytes(full, mono, true, false, bounds, full.width, full.height);
          appendRenderedSamples(report, "eglBefore", before, full.width, columns);
          ByteBuffer rendered =
              renderer.renderRegionBytes(full, mono, false, false, bounds, full.width, full.height);
          appendRenderedSamples(report, "eglMono", rendered, full.width, columns);
          stage = "JPEG encoder";
          ByteBuffer strip = ByteBuffer.allocateDirect(full.width * full.height * 3);
          while (rendered.hasRemaining()) {
            strip.put(rendered.get()).put(rendered.get()).put(rendered.get());
            rendered.get();
          }
          strip.flip();
          try (ParcelFileDescriptor fd =
                  ParcelFileDescriptor.open(
                      jpeg,
                      ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_WRITE_ONLY);
              NativeJpeg encoder = NativeJpeg.open(fd.getFd(), full.width, full.height, 95)) {
            encoder.write(strip, full.height);
            encoder.finish();
          }
          stage = "JPEG decoder";
          Bitmap decoded = BitmapFactory.decodeFile(jpeg.getPath());
          if (decoded == null) return report.append(" jpeg=unreadable").toString();
          try {
            for (int x : columns) {
              int pixel = decoded.getPixel(x, 64);
              int[] rgb = {Color.red(pixel), Color.green(pixel), Color.blue(pixel)};
              report.append(" jpeg[").append(x).append("]=").append(Arrays.toString(rgb));
            }
          } finally {
            decoded.recycle();
          }
        }
      }
    } catch (Exception | LinkageError failure) {
      report
          .append(" probe stopped at ")
          .append(stage)
          .append(" (")
          .append(failure.getClass().getSimpleName())
          .append(')');
    } finally {
      if (jpeg != null) jpeg.delete();
      if (fullCache != null) fullCache.delete();
      if (directory != null) directory.delete();
    }
    return report.toString();
  }

  private static void appendRenderedSamples(
      StringBuilder report, String label, ByteBuffer pixels, int width, int[] columns) {
    for (int x : columns) {
      int offset = (64 * width + x) * 4;
      int[] rgb = {
        pixels.get(offset) & 255, pixels.get(offset + 1) & 255, pixels.get(offset + 2) & 255
      };
      report
          .append(' ')
          .append(label)
          .append('[')
          .append(x)
          .append("]=")
          .append(Arrays.toString(rgb));
    }
  }

  private static AccessibilityNodeInfo activeNode(Instrumentation t, String text) {
    return node(t.getUiAutomation().getRootInActiveWindow(), text, false);
  }

  static void capture(Instrumentation t, File directory, String name) throws Exception {
    // Material checkbox and window entrance drawables can finish after focus arrives.
    Thread.sleep(350);
    Bitmap screen = t.getUiAutomation().takeScreenshot();
    TagDetailChecks.require(screen != null, "composed picker screenshot");
    try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
      TagDetailChecks.require(
          screen.compress(Bitmap.CompressFormat.PNG, 100, out), "write picker screenshot");
    } finally {
      screen.recycle();
    }
  }

  private static void select(Instrumentation t, String app) throws Exception {
    dialog(t, PhotoEditor.PICKER_TITLE, app);
  }

  static String run(Instrumentation t, int width, float font) throws Exception {
    MainActivity a =
        (MainActivity)
            t.startActivitySync(
                new Intent(t.getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    ExecutorService worker = Executors.newSingleThreadExecutor();
    AtomicReference<Intent> chosen = new AtomicReference<>(),
        nativeChosen = new AtomicReference<>();
    AtomicInteger launches = new AtomicInteger(), nativeLaunches = new AtomicInteger();
    AtomicBoolean allowNative = new AtomicBoolean(), allowExternal = new AtomicBoolean();
    String testPackage = t.getContext().getPackageName();
    String testLabel = PhotoEditor.INTERNAL_LABEL + "\n" + testPackage;
    SharedPreferences preferences =
        a.getSharedPreferences(PhotoEditor.PREFERENCES, Context.MODE_PRIVATE);
    String previous = preferences.getString(PhotoEditor.DEFAULT_APP, null);
    Set<String> previousCertificates =
        preferences.getStringSet(PhotoEditor.DEFAULT_CERTIFICATES, null);
    Instrumentation.ActivityMonitor monitor =
        new Instrumentation.ActivityMonitor() {
          public Instrumentation.ActivityResult onStartActivity(Intent intent) {
            if (intent.getComponent() != null
                && RawEditActivity.class.getName().equals(intent.getComponent().getClassName())) {
              nativeChosen.set(intent);
              nativeLaunches.incrementAndGet();
              return allowNative.get()
                  ? null
                  : new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
            }
            if (intent.getComponent() == null
                || !testPackage.equals(intent.getComponent().getPackageName())) return null;
            chosen.set(intent);
            launches.incrementAndGet();
            return allowExternal.get()
                ? null
                : new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
          }
        };
    File original = new File(a.getCacheDir(), "edit-synthetic-original.jpg");
    List<File> copies = new ArrayList<>();
    try {
      preferences
          .edit()
          .remove(PhotoEditor.DEFAULT_APP)
          .remove(PhotoEditor.DEFAULT_CERTIFICATES)
          .commit();
      GalleryScreenChecks.portraitFixture(t, a, width, font);
      GalleryScreenChecks.finishInitialIndex(t, a);
      t.addMonitor(monitor);
      Bitmap bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
      bitmap.eraseColor(Color.GRAY);
      try (OutputStream out = new FileOutputStream(original)) {
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
      }
      bitmap.recycle();
      byte[] before = Files.readAllBytes(original.toPath());
      PhotoRepository.Photo photo =
          new PhotoRepository.Photo(
              Uri.fromFile(original), "edit-synthetic.jpg", "/synthetic", original.length(), 0, 0);
      Fixture real = fixture(t, a, photo, worker, new SharePreparation(a, new PhotoRepository(a)));
      TagDetailChecks.ui(t, () -> real.editor.button.performClick());
      await(() -> activeNode(t, PhotoEditor.FIRST_USE_HINT) != null);
      TagDetailChecks.require(
          activeNode(t, PhotoEditor.REMEMBER_LABEL) == null, "short tap explains the gesture");
      TagDetailChecks.require(
          activeNode(t, PhotoEditor.INTERNAL_LABEL) != null && activeNode(t, testLabel) != null,
          "same app labels are distinguished by package in accessibility");
      select(t, "閉じる");
      await(a::hasWindowFocus);
      TagDetailChecks.require(
          !preferences.contains(PhotoEditor.DEFAULT_APP), "first choice cancel leaves no default");
      TagDetailChecks.ui(t, () -> real.editor.button.performClick());
      select(t, PhotoEditor.INTERNAL_LABEL);
      await(
          () ->
              nativeLaunches.get() == 1
                  && PhotoEditor.INTERNAL.equals(
                      preferences.getString(PhotoEditor.DEFAULT_APP, null)));
      copies.add(
          new File(a.getCacheDir(), "shares/" + nativeChosen.get().getData().getLastPathSegment()));
      TagDetailChecks.ui(t, () -> real.editor.button.performClick());
      await(() -> nativeLaunches.get() == 2);
      Uri nativeUri = nativeChosen.get().getData();
      TagDetailChecks.require(
          "jp.nano.rawgallery.share".equals(nativeUri.getAuthority()),
          "normal tap opens remembered internal editor with prepared copy");
      File nativeCopy = new File(a.getCacheDir(), "shares/" + nativeUri.getLastPathSegment());
      copies.add(nativeCopy);
      TagDetailChecks.require(
          Arrays.equals(before, Files.readAllBytes(nativeCopy.toPath())),
          "internal editor receives a byte-identical copy");

      TagDetailChecks.ui(
          t,
          () -> {
            AccessibilityNodeInfo buttonNode = real.editor.button.createAccessibilityNodeInfo();
            TagDetailChecks.require(
                buttonNode.isLongClickable()
                    && buttonNode.getActionList().stream()
                        .anyMatch(
                            action ->
                                PhotoEditor.PICKER_ACTION.contentEquals(
                                    action.getLabel() == null ? "" : action.getLabel())),
                "enabled edit button exposes a named long-click action");
          });
      click(t, real);
      await(() -> activeNode(t, PhotoEditor.REMEMBER_LABEL) != null);
      AccessibilityNodeInfo remember = activeNode(t, PhotoEditor.REMEMBER_LABEL);
      TagDetailChecks.require(
          remember.isCheckable() && !remember.isChecked(), "long press defaults to opening once");
      allowExternal.set(true);
      select(t, testLabel);
      await(() -> activeNode(t, "Loupe editor receipt OK") != null);
      TagDetailChecks.require(
          PhotoEditor.INTERNAL.equals(preferences.getString(PhotoEditor.DEFAULT_APP, null)),
          "open once retains prior default");
      t.getUiAutomation()
          .performGlobalAction(
              android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
      await(a::hasWindowFocus);
      allowExternal.set(false);
      Intent edit = chosen.get();
      TagDetailChecks.require(
          Intent.ACTION_EDIT.equals(edit.getAction()) && "image/jpeg".equals(edit.getType()),
          "ACTION_EDIT with original MIME");
      TagDetailChecks.require(
          edit.getFlags() == Intent.FLAG_GRANT_READ_URI_PERMISSION, "read grant only");
      TagDetailChecks.require(
          edit.getData().equals(edit.getClipData().getItemAt(0).getUri())
              && "jp.nano.rawgallery.share".equals(edit.getData().getAuthority()),
          "same copy URI in data and ClipData");
      File copy = new File(a.getCacheDir(), "shares/" + edit.getData().getLastPathSegment());
      copies.add(copy);
      TagDetailChecks.require(
          !copy.equals(original)
              && Arrays.equals(before, Files.readAllBytes(copy.toPath()))
              && Arrays.equals(before, Files.readAllBytes(original.toPath())),
          "byte-identical copy and unchanged original");
      try {
        a.getContentResolver().openFileDescriptor(edit.getData(), "w");
        throw new AssertionError("copy must reject write");
      } catch (FileNotFoundException expected) {
      }
      click(t, real);
      dialog(t, PhotoEditor.PICKER_TITLE, PhotoEditor.REMEMBER_LABEL);
      await(
          () -> {
            AccessibilityNodeInfo checked = activeNode(t, PhotoEditor.REMEMBER_LABEL);
            return checked != null && checked.isChecked();
          });
      select(t, testLabel);
      await(
          () ->
              launches.get() == 2
                  && testPackage.equals(preferences.getString(PhotoEditor.DEFAULT_APP, null)));
      copies.add(
          new File(a.getCacheDir(), "shares/" + chosen.get().getData().getLastPathSegment()));
      Set<String> realCertificates =
          preferences.getStringSet(PhotoEditor.DEFAULT_CERTIFICATES, Set.of());
      TagDetailChecks.require(
          realCertificates.size() == 1
              && Base64.getDecoder().decode(realCertificates.iterator().next()).length == 32,
          "successful external launch saves its SHA-256 certificate fingerprint");
      TagDetailChecks.ui(t, () -> real.editor.button.performClick());
      await(() -> launches.get() == 3);
      copies.add(
          new File(a.getCacheDir(), "shares/" + chosen.get().getData().getLastPathSegment()));

      PhotoRepository.Photo raw =
          new PhotoRepository.Photo(
              Uri.fromFile(original), "edit-synthetic.dng", "/synthetic", original.length(), 0, 0);
      Fixture viewOnly =
          fixture(t, a, raw, worker, new SharePreparation(a, new PhotoRepository(a)));
      TagDetailChecks.ui(t, () -> viewOnly.editor.button.performClick());
      await(() -> launches.get() == 4);
      Intent viewIntent = chosen.get();
      TagDetailChecks.require(
          Intent.ACTION_VIEW.equals(viewIntent.getAction())
              && "image/x-adobe-dng".equals(viewIntent.getType()),
          "VIEW-only handler retains RAW MIME");
      copies.add(new File(a.getCacheDir(), "shares/" + viewIntent.getData().getLastPathSegment()));
      PhotoRepository.Photo orf =
          new PhotoRepository.Photo(
              Uri.fromFile(original), "edit-synthetic.orf", "/synthetic", original.length(), 0, 0);
      Fixture incompatible =
          fixture(t, a, orf, worker, new SharePreparation(a, new PhotoRepository(a)));
      TagDetailChecks.ui(t, () -> incompatible.editor.button.performClick());
      await(() -> activeNode(t, PhotoEditor.FIRST_USE_HINT) != null);
      TagDetailChecks.require(
          activeNode(t, testLabel) == null, "unmatched MIME filters out the remembered app");
      select(t, "閉じる");
      await(a::hasWindowFocus);
      TagDetailChecks.require(
          testPackage.equals(preferences.getString(PhotoEditor.DEFAULT_APP, null)),
          "format fallback cancel preserves default");
      Fixture changedSignature =
          fixture(t, a, photo, worker, new SharePreparation(a, new PhotoRepository(a)));
      for (Set<String> injected :
          List.of(Set.<String>of(), Set.of(Base64.getEncoder().encodeToString(new byte[32])))) {
        preferences.edit().putStringSet(PhotoEditor.DEFAULT_CERTIFICATES, injected).commit();
        TagDetailChecks.ui(t, () -> changedSignature.editor.button.performClick());
        await(() -> activeNode(t, PhotoEditor.FIRST_USE_HINT) != null);
        select(t, "閉じる");
        await(a::hasWindowFocus);
        TagDetailChecks.require(
            launches.get() == 4, "missing or mismatched certificate requires a new selection");
      }
      preferences
          .edit()
          .putString(PhotoEditor.DEFAULT_APP, "jp.nano.rawgallery.synthetic.missing")
          .commit();
      Fixture missing =
          fixture(t, a, photo, worker, new SharePreparation(a, new PhotoRepository(a)));
      TagDetailChecks.ui(t, () -> missing.editor.button.performClick());
      await(() -> activeNode(t, PhotoEditor.FIRST_USE_HINT) != null);
      select(t, testLabel);
      await(
          () ->
              launches.get() == 5
                  && testPackage.equals(preferences.getString(PhotoEditor.DEFAULT_APP, null)));
      copies.add(
          new File(a.getCacheDir(), "shares/" + chosen.get().getData().getLastPathSegment()));
      int initial = launches.get();
      for (int scenario : new int[] {0, 1, 2}) {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        AtomicReference<File> made = new AtomicReference<>();
        SharePreparation slow =
            new SharePreparation(
                new File(a.getCacheDir(), "shares"),
                (p, rawFormat, destination) -> {
                  writes.incrementAndGet();
                  made.set(destination);
                  started.countDown();
                  boolean waiting = true;
                  while (waiting)
                    try {
                      release.await();
                      waiting = false;
                    } catch (InterruptedException ignored) {
                    }
                  Files.write(destination.toPath(), before);
                });
        Fixture f = fixture(t, a, photo, worker, slow);
        TagDetailChecks.ui(
            t,
            () -> {
              f.editor.button.performClick();
              f.editor.button.performClick();
            });
        TagDetailChecks.require(started.await(3, TimeUnit.SECONDS), "copy started");
        TagDetailChecks.ui(
            t,
            () -> {
              AccessibilityNodeInfo buttonNode = f.editor.button.createAccessibilityNodeInfo();
              TagDetailChecks.require(
                  !buttonNode.isLongClickable()
                      && buttonNode.getActionList().stream()
                          .noneMatch(
                              action -> action.getId() == AccessibilityNodeInfo.ACTION_LONG_CLICK),
                  "active copy preparation offers no unusable long press");
            });
        try {
          if (scenario == 1) TagDetailChecks.ui(t, () -> a.setContentView(new FrameLayout(a)));
          else if (scenario == 2)
            TagDetailChecks.ui(
                t,
                () -> {
                  f.viewer.onWindowFocusChanged(false);
                  f.viewer.onWindowFocusChanged(true);
                });
          else {
            long until = android.os.SystemClock.uptimeMillis() + 3000;
            while (!"中止".equals(label(t, f))) {
              if (android.os.SystemClock.uptimeMillis() > until)
                throw new AssertionError("cancel label");
              Thread.sleep(20);
            }
            TagDetailChecks.ui(t, () -> f.editor.button.performClick());
          }
        } finally {
          release.countDown();
        }
        worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
        t.waitForIdleSync();
        TagDetailChecks.require(
            writes.get() == 1 && launches.get() == initial && !made.get().exists(),
            "cancel/detach/focus loss-return prevents late launch and discards copy");
      }
      AtomicInteger attempts = new AtomicInteger();
      SharePreparation failing =
          new SharePreparation(
              new File(a.getCacheDir(), "shares"),
              (p, rawFormat, destination) -> {
                if (attempts.incrementAndGet() == 1) throw new IOException("synthetic");
                Files.write(destination.toPath(), before);
              });
      Fixture retry = fixture(t, a, photo, worker, failing);
      TagDetailChecks.ui(t, () -> retry.editor.button.performClick());
      worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
      t.waitForIdleSync();
      TagDetailChecks.ui(
          t,
          () ->
              TagDetailChecks.require(
                  retry.editor.button.getContentDescription().toString().contains("再試行"),
                  "failure offers retry on the same button"));
      TagDetailChecks.ui(t, () -> retry.editor.button.performClick());
      await(() -> launches.get() == initial + 1);
      Intent retried = chosen.get();
      copies.add(new File(a.getCacheDir(), "shares/" + retried.getData().getLastPathSegment()));
      TagDetailChecks.require(
          attempts.get() == 2 && Arrays.equals(before, Files.readAllBytes(original.toPath())),
          "retry succeeds without original mutation");
      preferences.edit().putString(PhotoEditor.DEFAULT_APP, PhotoEditor.INTERNAL).commit();
      allowNative.set(true);
      internalEditor(t, a, worker, copies);
      return "PASS real normal-tap internal Activity load, Mono dirty-Back cancel/recreation, confirmed MediaStore JPEG dimensions/tint/original unchanged, UI-observed saving Back cancellation/no remaining owned output (pending-stage cancellation not forced), scoped cleanup; first choice/remembered tap, long-press once/change, missing app/MIME/certificate mismatch, EDIT priority/VIEW fallback, duplicate names, real test APK read-only receipt, unchanged original, original-format MIME, duplicate prevention, inline cancel, detach/focus loss-return/late completion cleanup, prepare failure/retry. Third-party edit/save compatibility is separate.\n";
    } finally {
      preferences
          .edit()
          .putString(PhotoEditor.DEFAULT_APP, previous)
          .putStringSet(PhotoEditor.DEFAULT_CERTIFICATES, previousCertificates)
          .commit();
      t.removeMonitor(monitor);
      worker.shutdownNow();
      TagDetailChecks.ui(t, () -> a.finish());
      original.delete();
      for (File copy : copies) copy.delete();
    }
  }
}
