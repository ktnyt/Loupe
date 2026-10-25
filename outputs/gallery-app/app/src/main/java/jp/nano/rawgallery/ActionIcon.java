package jp.nano.rawgallery;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Resolution-independent dock symbols; action names stay on the accessible button. */
final class ActionIcon extends Drawable {
  private final String action;
  private final float size;
  private android.content.res.ColorStateList tint;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  ActionIcon(String action, float density) {
    this.action = action;
    size = 26 * density;
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1.8f);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
  }

  @Override
  public void draw(Canvas canvas) {
    boolean enabled = false;
    for (int state : getState()) if (state == android.R.attr.state_enabled) enabled = true;
    int foreground = tint == null ? (enabled ? GalleryUi.TEXT : 0xff626b72)
        : tint.getColorForState(getState(), tint.getDefaultColor());
    paint.setColor(foreground);
    int save = canvas.save();
    Rect bounds = getBounds();
    canvas.translate(bounds.exactCenterX() - size / 2, bounds.exactCenterY() - size / 2);
    canvas.scale(size / 24, size / 24);
    switch (action) {
      case "同じ順位":
      case "同順位":
        line(canvas, 5, 9, 19, 9);
        line(canvas, 5, 15, 19, 15);
        break;
      case "選択を終了":
        line(canvas, 6, 6, 18, 18);
        line(canvas, 18, 6, 6, 18);
        break;
      case "すべて選択":
        line(canvas, 3, 7, 3, 22, 18, 22);
        canvas.drawRoundRect(6, 2, 22, 19, 2, 2, paint);
        line(canvas, 9, 10, 13, 14, 19, 6);
        break;
      case "選択へ戻る":
        line(canvas, 19, 12, 5, 12);
        line(canvas, 11, 6, 5, 12, 11, 18);
        break;
      case "その他":
        for (int y : new int[] {5, 12, 19}) canvas.drawCircle(12, y, 1, paint);
        break;
      case "戻る":
      case "一覧":
      case "写真一覧":
      case "連写一覧":
        line(canvas, 19, 12, 5, 12);
        line(canvas, 11, 6, 5, 12, 11, 18);
        break;
      case "比較に戻る":
        canvas.drawRoundRect(3, 5, 10, 19, 1, 1, paint);
        canvas.drawRoundRect(14, 5, 21, 19, 1, 1, paint);
        break;
      case "直前の操作を取り消す":
      case "一つ戻す":
        line(canvas, 8, 4, 3, 9, 8, 14);
        line(canvas, 3, 9, 13, 9);
        canvas.drawArc(7, 9, 21, 21, -90, 180, false, paint);
        break;
      case "アルバムへ":
        line(canvas, 3, 8, 3, 21, 19, 21);
        canvas.drawRoundRect(6, 3, 22, 18, 2, 2, paint);
        line(canvas, 8, 15, 12, 10, 16, 14, 19, 11, 22, 15);
        break;
      case "すべての写真":
        for (int x : new int[] {3, 14})
          for (int y : new int[] {3, 14}) canvas.drawRoundRect(x, y, x + 7, y + 7, 1, 1, paint);
        break;
      case "絞り込み":
        line(canvas, 3, 5, 21, 5, 14, 13, 14, 20, 10, 18, 10, 13, 3, 5);
        break;
      case "順位を決める":
      case "順位を付ける":
        canvas.drawRect(3, 12, 9, 21, paint);
        canvas.drawRect(9, 7, 15, 21, paint);
        canvas.drawRect(15, 15, 21, 21, paint);
        break;
      case "選択":
        canvas.drawCircle(12, 12, 8.5f, paint);
        line(canvas, 8, 12, 11, 15, 16, 9);
        break;
      case "タグ":
        line(canvas, 3, 3, 12, 3, 21, 12, 12, 21, 3, 12, 3, 3);
        canvas.drawCircle(7.5f, 7.5f, 1, paint);
        break;
      case "拡大":
        canvas.drawCircle(10, 10, 6, paint);
        line(canvas, 15, 15, 21, 21);
        line(canvas, 7, 10, 13, 10);
        line(canvas, 10, 7, 10, 13);
        break;
      case "前の写真":
        line(canvas, 15, 5, 8, 12, 15, 19);
        break;
      case "次の写真":
        line(canvas, 9, 5, 16, 12, 9, 19);
        break;
      case "共有":
        line(canvas, 7, 11, 17, 5);
        line(canvas, 7, 13, 17, 19);
        canvas.drawCircle(5, 12, 2, paint);
        canvas.drawCircle(19, 4, 2, paint);
        canvas.drawCircle(19, 20, 2, paint);
        break;
      case "ログイン":
        line(canvas, 14, 3, 21, 3, 21, 21, 14, 21);
        line(canvas, 3, 12, 16, 12);
        line(canvas, 11, 7, 16, 12, 11, 17);
        break;
      case "同期設定":
      case "同期・整理":
        line(canvas, 3, 6, 21, 6);
        line(canvas, 3, 12, 21, 12);
        line(canvas, 3, 18, 21, 18);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(GalleryUi.PANEL);
        canvas.drawCircle(8, 6, 2.5f, paint);
        canvas.drawCircle(16, 12, 2.5f, paint);
        canvas.drawCircle(8, 18, 2.5f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(foreground);
        canvas.drawCircle(8, 6, 2.5f, paint);
        canvas.drawCircle(16, 12, 2.5f, paint);
        canvas.drawCircle(8, 18, 2.5f, paint);
        break;
      default:
        canvas.drawCircle(12, 12, 8, paint);
    }
    canvas.restoreToCount(save);
  }

  private void line(Canvas canvas, float... points) {
    Path path = new Path();
    path.moveTo(points[0], points[1]);
    for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
    canvas.drawPath(path, paint);
  }

  @Override
  public boolean isStateful() {
    return true;
  }

  @Override
  protected boolean onStateChange(int[] state) {
    invalidateSelf();
    return true;
  }

  @Override
  public void setTintList(android.content.res.ColorStateList colors) {
    tint = colors;
    invalidateSelf();
  }

  @Override
  public void setAlpha(int alpha) {
    paint.setAlpha(alpha);
    invalidateSelf();
  }

  @Override
  public void setColorFilter(ColorFilter filter) {
    paint.setColorFilter(filter);
    invalidateSelf();
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
