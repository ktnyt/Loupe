package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.*;
import android.view.*;

public final class ZoomImageView extends View {
  private Bitmap bitmap;
  private java.util.function.IntConsumer pageListener;

  public void setPageListener(java.util.function.IntConsumer listener) {
    pageListener = listener;
  }

  private boolean multiTouch;
  private float zoom = 1, dx = 0, dy = 0, lastX, lastY;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final ScaleGestureDetector scale;
  private final GestureDetector gesture;

  public ZoomImageView(Context c) {
    super(c);
    setContentDescription("写真。ピンチで拡大、ダブルタップで切り替え");
    scale =
        new ScaleGestureDetector(
            c,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              public boolean onScale(ScaleGestureDetector d) {
                zoom = Math.max(1, Math.min(8, zoom * d.getScaleFactor()));
                if (zoom == 1) {
                  dx = 0;
                  dy = 0;
                }
                invalidate();
                return true;
              }
            });
    gesture =
        new GestureDetector(
            c,
            new GestureDetector.SimpleOnGestureListener() {
              public boolean onDown(android.view.MotionEvent e) {
                return true;
              }

              public boolean onFling(MotionEvent first, MotionEvent last, float vx, float vy) {
                if (pageListener == null || multiTouch || zoom > 1 || first == null) return false;
                float dx = last.getX() - first.getX(), dy = last.getY() - first.getY();
                if (Math.abs(dx) < 64 * getResources().getDisplayMetrics().density
                    || Math.abs(dx) < Math.abs(dy) * 1.5
                    || Math.abs(vx) < 200) return false;
                pageListener.accept(dx < 0 ? 1 : -1);
                return true;
              }

              public boolean onDoubleTap(MotionEvent e) {
                zoom = zoom > 1 ? 1 : 2.5f;
                dx = 0;
                dy = 0;
                invalidate();
                return true;
              }
            });
  }

  public void setBitmap(Bitmap b) {
    bitmap = b;
    zoom = 1;
    dx = 0;
    dy = 0;
    invalidate();
  }

  protected void onDraw(Canvas c) {
    super.onDraw(c);
    if (bitmap == null) return;
    float fit =
        Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
    float w = bitmap.getWidth() * fit * zoom, h = bitmap.getHeight() * fit * zoom;
    dx =
        Math.max(
            -Math.max(0, (w - getWidth()) / 2), Math.min(Math.max(0, (w - getWidth()) / 2), dx));
    dy =
        Math.max(
            -Math.max(0, (h - getHeight()) / 2), Math.min(Math.max(0, (h - getHeight()) / 2), dy));
    float x = (getWidth() - w) / 2 + dx, y = (getHeight() - h) / 2 + dy;
    c.drawBitmap(bitmap, null, new RectF(x, y, x + w, y + h), paint);
  }

  public boolean onTouchEvent(MotionEvent e) {
    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) multiTouch = false;
    if (e.getPointerCount() > 1) multiTouch = true;
    scale.onTouchEvent(e);
    gesture.onTouchEvent(e);
    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
      lastX = e.getX();
      lastY = e.getY();
    } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
      if (!scale.isInProgress() && e.getPointerCount() == 1 && zoom > 1) {
        dx += e.getX() - lastX;
        dy += e.getY() - lastY;
        invalidate();
      }
      lastX = e.getX();
      lastY = e.getY();
    } else if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
    return true;
  }

  public boolean performClick() {
    super.performClick();
    return true;
  }
}
