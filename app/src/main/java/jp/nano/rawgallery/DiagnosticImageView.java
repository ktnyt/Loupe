package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.Canvas;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

/** Registers before traversal: registering inside onDraw can miss that frame. */
final class DiagnosticImageView extends ImageView {
  private long request, drawn;
  private boolean pending;
  private ViewTreeObserver.OnPreDrawListener listener;
  DiagnosticImageView(Context c) { super(c); }
  void loading() {
    removeListener();
    pending = false;
    drawn = 0;
  }
  void bound(long request) {
    if (pending) DiagnosticLog.event("image.draw_superseded", this.request);
    removeListener();
    this.request = request;
    drawn = 0;
    pending = true;
    final long id = request;
    listener = () -> {
      removeListener();
      if (isHardwareAccelerated()) {
        getViewTreeObserver().registerFrameCommitCallback(() -> {
          // A different bind may have replaced this request before a frame was drawn.
          if (drawn == id) DiagnosticLog.event("image.frame_commit", id);
          else DiagnosticLog.event("image.frame_unavailable", id);
        });
      } else DiagnosticLog.event("image.frame_unavailable", id);
      return true;
    };
    getViewTreeObserver().addOnPreDrawListener(listener);
  }
  private void removeListener() {
    if (listener != null && getViewTreeObserver().isAlive())
      getViewTreeObserver().removeOnPreDrawListener(listener);
    listener = null;
  }
  @Override protected void onDetachedFromWindow() {
    removeListener();
    if (pending) DiagnosticLog.event("image.draw_detached", request);
    pending = false;
    super.onDetachedFromWindow();
  }
  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (!pending || getDrawable() == null) return;
    pending = false;
    drawn = request;
    DiagnosticLog.event("image.draw", request, "hardware", isHardwareAccelerated());
  }
}
