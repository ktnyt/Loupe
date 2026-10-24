package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.widget.*;

/** Square photo tile. Metadata occupies only small overlays, never a separate row. */
final class ThumbnailCell extends FrameLayout {
  final ImageView image;
  final Badges badges;

  ThumbnailCell(Context context) {
    super(context);
    setBackgroundColor(0xff22272a);
    image = new ImageView(context);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    addView(image, new LayoutParams(-1, -1));
    badges = new Badges(context);
    badges.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    addView(badges, new LayoutParams(-1, -1));
  }

  protected void onMeasure(int width, int height) {
    int side = MeasureSpec.getSize(width);
    super.onMeasure(
        MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY));
  }

  static final class Badges extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    String camera = "";
    int count;
    boolean cloud, failed, selectionMode, selected;

    Badges(Context context) {
      super(context);
      density = getResources().getDisplayMetrics().density;
    }

    protected void onDraw(Canvas canvas) {
      canvas.save();
      canvas.scale(density, density);
      float w = getWidth() / density, h = getHeight() / density;
      paint.setTextSize(10);
      paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
      if (!camera.isEmpty() && !camera.equals("その他")) {
        box(canvas, 5, 5, paint.measureText(camera) + 12, 21);
        paint.setColor(Color.WHITE);
        canvas.drawText(camera, 11, 19, paint);
      }
      if (cloud && !selectionMode) {
        box(canvas, w - 29, 5, 24, 21);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(w - 21, 17, 3, paint);
        canvas.drawCircle(w - 16, 14, 5, paint);
        canvas.drawCircle(w - 11, 17, 3, paint);
        canvas.drawRect(w - 21, 16, w - 11, 20, paint);
      }
      if (count > 1) {
        String number = Integer.toString(count);
        float width = 26 + paint.measureText(number), left = w - 5 - width;
        box(canvas, left, h - 26, width, 21);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.3f);
        canvas.drawRoundRect(left + 5, h - 21, left + 13, h - 13, 1, 1, paint);
        canvas.drawRoundRect(left + 8, h - 18, left + 16, h - 10, 1, 1, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawText(number, left + 21, h - 12, paint);
      }
      if (selectionMode) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(selected ? GalleryUi.ACCENT : 0xaa141719);
        canvas.drawCircle(w - 17, 17, 11, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setColor(selected ? GalleryUi.BG : Color.WHITE);
        if (selected) {
          Path tick = new Path();
          tick.moveTo(w - 22, 17);
          tick.lineTo(w - 18, 21);
          tick.lineTo(w - 11, 13);
          canvas.drawPath(tick, paint);
          paint.setColor(GalleryUi.ACCENT);
          canvas.drawRect(1, 1, w - 1, h - 1, paint);
        } else canvas.drawCircle(w - 17, 17, 10, paint);
        paint.setStyle(Paint.Style.FILL);
      }
      if (failed) {
        box(canvas, w / 2 - 12, h / 2 - 12, 24, 24);
        paint.setColor(Color.WHITE);
        canvas.drawText("!", w / 2 - 2, h / 2 + 4, paint);
      }
      canvas.restore();
    }

    private void box(Canvas canvas, float x, float y, float width, float height) {
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(0xaa141719);
      canvas.drawRoundRect(x, y, x + width, y + height, 5, 5, paint);
    }
  }
}
