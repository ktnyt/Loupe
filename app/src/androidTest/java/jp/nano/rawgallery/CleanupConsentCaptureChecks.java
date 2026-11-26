package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Brackets one E2 settings window buffer with two default-display captures. */
final class CleanupConsentCaptureChecks implements AutoCloseable {
  private static final String NAME = "amazon-retirement-consent-probe";
  private static final int FIRST_EVENTS = 12;
  private static final int LAST_EVENTS = 52;

  private final Instrumentation test;
  private final E2SyncActivity screen;
  private final View decor, status, stop;
  private final ScrollView scroll;
  private final String suffix;
  private final JSONObject report = new JSONObject(),
      stages = new JSONObject(),
      samples = new JSONObject();
  private final List<Event> firstEvents = new ArrayList<>();
  private final ArrayDeque<Event> lastEvents = new ArrayDeque<>();
  private int scrollEvents, drawEvents, focusEvents;
  private ViewTreeObserver observer;
  private final ViewTreeObserver.OnScrollChangedListener scrolled = () -> event("scroll");
  private final ViewTreeObserver.OnGlobalFocusChangeListener focused =
      (previous, next) -> event("focus");
  private final ViewTreeObserver.OnPreDrawListener drawing =
      () -> {
        event("pre_draw");
        return true;
      };

  private record Event(String kind, long nanos, int scrollY, String focusedClass) {}

  private record Sample(Bitmap image, Integer pixelCopyStatus, String failure) {}

  CleanupConsentCaptureChecks(
      Instrumentation test,
      E2SyncActivity screen,
      ScrollView scroll,
      View status,
      View stop,
      String suffix)
      throws Exception {
    this.test = test;
    this.screen = screen;
    this.decor = screen.getWindow().getDecorView();
    this.scroll = scroll;
    this.status = status;
    this.stop = stop;
    this.suffix = suffix;
    report.put("pixel_acceptance", "not_evaluated").put("stages", stages).put("samples", samples);
  }

  void observe() {
    TagDetailChecks.ui(
        test,
        () -> {
          observer = decor.getViewTreeObserver();
          observer.addOnScrollChangedListener(scrolled);
          observer.addOnGlobalFocusChangeListener(focused);
          observer.addOnPreDrawListener(drawing);
          event("before_reveal");
        });
  }

  void capture(int expectedScrollY) throws Exception {
    report.put("expected_scroll_y", expectedScrollY);
    Sample d1 = null;
    Sample w = null;
    Sample d2 = null;
    try {
      JSONObject before = snapshot("before_d1");
      d1 = display();
      JSONObject afterD1 = snapshot("after_d1");
      JSONObject beforeW = snapshot("before_w");
      w = window();
      JSONObject afterW = snapshot("after_w");
      JSONObject beforeD2 = snapshot("before_d2");
      d2 = display();
      JSONObject afterD2 = snapshot("after_d2");
      String first = before.toString();
      boolean stable =
          List.of(afterD1, beforeW, afterW, beforeD2, afterD2).stream()
              .allMatch(geometry -> first.equals(geometry.toString()));
      report.put("sample_geometry_unchanged", stable);
    } finally {
      // Encode only after the three samples. An individual write failure must not drop the others.
      save("d1", "default_display", d1);
      save("w", "window_pixel_copy", w);
      save("d2", "default_display", d2);
    }
    if (!written("d1") || !written("w") || !written("d2"))
      throw new AssertionError("incomplete unknown E2 capture probe");
  }

  private Sample display() {
    try {
      Bitmap image = test.getUiAutomation().takeScreenshot();
      return new Sample(image, null, image == null ? "no_bitmap" : null);
    } catch (RuntimeException failure) {
      return new Sample(null, null, failure.getClass().getSimpleName());
    }
  }

  private Sample window() throws Exception {
    var ready = new CompletableFuture<Sample>();
    TagDetailChecks.ui(
        test,
        () -> {
          Rect rect = bounds(decor, false);
          Bitmap image;
          try {
            image = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888);
          } catch (RuntimeException failure) {
            ready.complete(new Sample(null, null, failure.getClass().getSimpleName()));
            return;
          }
          try {
            PixelCopy.request(
                screen.getWindow(),
                rect,
                image,
                result -> {
                  boolean success = result == PixelCopy.SUCCESS;
                  if (!ready.complete(
                          new Sample(
                              success ? image : null, result, success ? null : "pixel_copy_error"))
                      || !success) image.recycle();
                },
                new Handler(Looper.getMainLooper()));
          } catch (RuntimeException failure) {
            image.recycle();
            ready.complete(new Sample(null, null, failure.getClass().getSimpleName()));
          }
        });
    try {
      return ready.get(5, TimeUnit.SECONDS);
    } catch (TimeoutException timeout) {
      if (!ready.cancel(false)) return ready.join();
      return new Sample(null, null, "pixel_copy_timeout");
    } catch (InterruptedException interrupted) {
      if (!ready.cancel(false)) {
        Sample arrived = ready.getNow(null);
        if (arrived != null && arrived.image != null) arrived.image.recycle();
      }
      throw interrupted;
    }
  }

  private void save(String name, String source, Sample sample) throws Exception {
    var entry = new JSONObject().put("source", source).put("written", false);
    samples.put(name, entry);
    if (sample == null) {
      entry.put("failure", "not_sampled");
      return;
    }
    entry.put(
        "pixel_copy_status",
        sample.pixelCopyStatus == null ? JSONObject.NULL : sample.pixelCopyStatus);
    if (sample.image == null) {
      entry.put("failure", sample.failure);
      return;
    }
    Bitmap image = sample.image;
    String filename = NAME + "-" + name + suffix + ".png";
    try {
      entry
          .put("width", image.getWidth())
          .put("height", image.getHeight())
          .put("filename", filename);
      try (var stream = new FileOutputStream(cache(filename))) {
        if (!image.compress(Bitmap.CompressFormat.PNG, 100, stream))
          throw new IOException("PNG compression failed");
      }
      entry.put("written", true);
    } catch (IOException | RuntimeException failure) {
      entry.put("failure", failure.getClass().getSimpleName());
    } finally {
      image.recycle();
    }
  }

  private boolean written(String name) {
    return samples.optJSONObject(name).optBoolean("written");
  }

  private JSONObject snapshot(String name) throws Exception {
    var result = new AtomicReference<JSONObject>();
    var nanos = new java.util.concurrent.atomic.AtomicLong();
    TagDetailChecks.ui(
        test,
        () -> {
          nanos.set(SystemClock.elapsedRealtimeNanos());
          try {
            result.set(geometry());
          } catch (JSONException invalid) {
            throw new AssertionError("capture geometry could not be recorded", invalid);
          }
        });
    JSONObject geometry = result.get();
    stages.put(name, new JSONObject().put("nanos", nanos.get()).put("geometry", geometry));
    return geometry;
  }

  private JSONObject geometry() throws JSONException {
    var display = decor.getDisplay();
    var real = new Point();
    display.getRealSize(real);
    WindowInsets insets = decor.getRootWindowInsets();
    int system = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
    int ime = WindowInsets.Type.ime();
    Insets safe = insets == null ? Insets.NONE : insets.getInsets(system | ime);
    Rect safeWindow = bounds(decor, true);
    safeWindow.set(
        safeWindow.left + safe.left,
        safeWindow.top + safe.top,
        safeWindow.right - safe.right,
        safeWindow.bottom - safe.bottom);
    Rect viewport = bounds(scroll, true);
    viewport.set(
        viewport.left + scroll.getPaddingLeft(),
        viewport.top + scroll.getPaddingTop(),
        viewport.right - scroll.getPaddingRight(),
        viewport.bottom - scroll.getPaddingBottom());
    Rect intersection = new Rect(viewport);
    if (!intersection.intersect(safeWindow)) intersection.setEmpty();
    var roots = new JSONArray();
    for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
      if (roots.length() >= 8) break;
      roots.put(view(root));
    }
    return new JSONObject()
        .put("display_id", display.getDisplayId())
        .put("display_rotation", display.getRotation())
        .put("display_real_size", numbers(real.x, real.y))
        .put("density", decor.getResources().getDisplayMetrics().density)
        .put("font_scale", decor.getResources().getConfiguration().fontScale)
        .put(
            "window_metrics", rect(screen.getWindowManager().getCurrentWindowMetrics().getBounds()))
        .put("screen_identity", System.identityHashCode(screen))
        .put("window_identity", System.identityHashCode(screen.getWindow()))
        .put("decor", view(decor))
        .put("scroll", view(scroll))
        .put("status", view(status))
        .put("stop", view(stop))
        .put("stop_ax_screen_bounds", rect(accessibilityBounds(stop)))
        .put("process_window_roots", roots)
        .put("focused_class", focusClass())
        .put("scroll_y", scroll.getScrollY())
        .put("consent_unknown", E2Cleanup.consentUnknown(screen))
        .put(
            "all_share_decor_and_token",
            decor == scroll.getRootView()
                && decor == status.getRootView()
                && decor == stop.getRootView()
                && decor.getWindowToken() != null
                && decor.getWindowToken() == scroll.getWindowToken()
                && decor.getWindowToken() == status.getWindowToken()
                && decor.getWindowToken() == stop.getWindowToken())
        .put("root_insets_present", insets != null)
        .put("system_insets", insetArray(insets == null ? Insets.NONE : insets.getInsets(system)))
        .put("ime_insets", insetArray(insets == null ? Insets.NONE : insets.getInsets(ime)))
        .put("ime_visible", insets != null && insets.isVisible(ime))
        .put("safe_window_screen", rect(safeWindow))
        .put("padded_scroll_viewport_screen", rect(viewport))
        .put("safe_scroll_viewport_screen", rect(intersection))
        .put(
            "whole_targets_inside_safe_viewport",
            insets != null
                && intersection.contains(bounds(status, true))
                && intersection.contains(bounds(stop, true)));
  }

  private static JSONObject view(View v) throws JSONException {
    var visible = new Rect();
    boolean shown = v.getGlobalVisibleRect(visible);
    return new JSONObject()
        .put("class", v.getClass().getName())
        .put("identity", System.identityHashCode(v))
        .put("view_id", v.getId())
        .put("root_identity", System.identityHashCode(v.getRootView()))
        .put("window_token_identity", System.identityHashCode(v.getWindowToken()))
        .put(
            "application_window_token_identity",
            System.identityHashCode(v.getApplicationWindowToken()))
        .put("window_focus", v.hasWindowFocus())
        .put("shown", v.isShown())
        .put("hardware_accelerated", v.isHardwareAccelerated())
        .put("laid_out", v.isLaidOut())
        .put("layout_requested", v.isLayoutRequested())
        .put("measured_size", numbers(v.getMeasuredWidth(), v.getMeasuredHeight()))
        .put("scale", new JSONArray().put(v.getScaleX()).put(v.getScaleY()))
        .put("translation", new JSONArray().put(v.getTranslationX()).put(v.getTranslationY()))
        .put(
            "padding",
            numbers(
                v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), v.getPaddingBottom()))
        .put("screen_bounds", rect(bounds(v, true)))
        .put("window_bounds", rect(bounds(v, false)))
        .put("has_global_visible_rect", shown)
        .put("root_global_visible_rect", rect(visible));
  }

  private static Rect bounds(View view, boolean screen) {
    int[] location = new int[2];
    if (screen) view.getLocationOnScreen(location);
    else view.getLocationInWindow(location);
    return new Rect(
        location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
  }

  private static Rect accessibilityBounds(View view) {
    var rect = new Rect();
    view.createAccessibilityNodeInfo().getBoundsInScreen(rect);
    return rect;
  }

  private static JSONArray rect(Rect r) {
    return numbers(r.left, r.top, r.right, r.bottom);
  }

  private static JSONArray insetArray(Insets insets) {
    return numbers(insets.left, insets.top, insets.right, insets.bottom);
  }

  private static JSONArray numbers(int... values) {
    var array = new JSONArray();
    for (int v : values) array.put(v);
    return array;
  }

  private String focusClass() {
    View focus = decor.findFocus();
    return focus == null ? "" : focus.getClass().getName();
  }

  private void event(String kind) {
    switch (kind) {
      case "scroll" -> scrollEvents++;
      case "pre_draw" -> drawEvents++;
      case "focus" -> focusEvents++;
    }
    var event =
        new Event(kind, SystemClock.elapsedRealtimeNanos(), scroll.getScrollY(), focusClass());
    if (firstEvents.size() < FIRST_EVENTS) firstEvents.add(event);
    else {
      if (lastEvents.size() == LAST_EVENTS) lastEvents.removeFirst();
      lastEvents.addLast(event);
    }
  }

  private static JSONArray events(Iterable<Event> events) throws Exception {
    var array = new JSONArray();
    for (Event event : events)
      array.put(
          new JSONObject()
              .put("kind", event.kind)
              .put("nanos", event.nanos)
              .put("scroll_y", event.scrollY)
              .put("focused_class", event.focusedClass));
    return array;
  }

  private File cache(String filename) {
    return new File(test.getTargetContext().getCacheDir(), filename);
  }

  @Override
  public void close() throws Exception {
    TagDetailChecks.ui(
        test,
        () -> {
          if (observer == null) return;
          ViewTreeObserver current = observer.isAlive() ? observer : decor.getViewTreeObserver();
          current.removeOnPreDrawListener(drawing);
          current.removeOnGlobalFocusChangeListener(focused);
          current.removeOnScrollChangedListener(scrolled);
        });
    report
        .put("scroll_events", scrollEvents)
        .put("pre_draw_events", drawEvents)
        .put("focus_events", focusEvents)
        .put("first_events", events(firstEvents))
        .put("last_events", events(lastEvents));
    Files.write(
        cache(NAME + suffix + ".json").toPath(),
        report.toString(2).getBytes(StandardCharsets.UTF_8));
  }
}
