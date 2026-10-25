package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.*;
import android.util.TypedValue;
import android.widget.ImageView;

/** A/B belongs to the displayed candidate, not its current horizontal/vertical slot. */
final class ComparisonPhotoView extends ImageView {
  private final String letter, name;
  private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF image = new RectF(), badge = new RectF();
  private final Paint.FontMetrics metrics = new Paint.FontMetrics();

  ComparisonPhotoView(Context context, String letter, String name) {
    super(context); this.letter = letter; this.name = name;
    setScaleType(ScaleType.FIT_CENTER);
    ink.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    loading();
  }
  void loading() { setContentDescription(letter + "、" + name + "、読み込み中"); }
  void loaded() { setContentDescription(letter + "、" + name + "、タップで選択、長押しで拡大"); }
  void failed() { setContentDescription(letter + "、" + name + "、読み込めませんでした"); }
  private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
  private float sp(float value) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics()); }

  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    image.set(getPaddingLeft(), getPaddingTop(), getWidth()-getPaddingRight(), getHeight()-getPaddingBottom());
    if (getDrawable() != null) {
      image.set(getDrawable().getBounds());
      getImageMatrix().mapRect(image);
      image.offset(getPaddingLeft(), getPaddingTop());
      if (!image.intersect(getPaddingLeft(), getPaddingTop(), getWidth()-getPaddingRight(), getHeight()-getPaddingBottom())) image.setEmpty();
    }
    ink.setTextSize(sp(13));
    float width = Math.max(dp(24), ink.measureText(letter) + dp(14));
    float height = Math.max(dp(24), sp(16) + dp(4));
    float stroke = dp(1);
    badge.setEmpty();
    if (image.width() < width + stroke*2 || image.height() < height + stroke*2) return;
    float inset = Math.min(image.width(), image.height()) < dp(96) ? dp(4) : dp(8);
    float x = image.left + Math.min(inset, image.width()-width-stroke);
    float y = image.top + Math.min(inset, image.height()-height-stroke);
    badge.set(x, y, x+width, y+height);
    int save = canvas.save(); canvas.clipRect(image);
    ink.setStyle(Paint.Style.FILL); ink.setColor(0xb80f0f10);
    canvas.drawRoundRect(badge, dp(6), dp(6), ink);
    ink.setStyle(Paint.Style.STROKE); ink.setStrokeWidth(stroke); ink.setColor(0x73ededea);
    canvas.drawRoundRect(x-stroke/2, y-stroke/2, x+width+stroke/2, y+height+stroke/2, dp(6)+stroke/2, dp(6)+stroke/2, ink);
    ink.setStyle(Paint.Style.FILL); ink.setColor(GalleryUi.TEXT); ink.setTextAlign(Paint.Align.CENTER);
    ink.getFontMetrics(metrics);
    canvas.drawText(letter, badge.centerX(), badge.centerY()-(metrics.ascent+metrics.descent)/2, ink);
    canvas.restoreToCount(save);
  }
}
