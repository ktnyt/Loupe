package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.*;
import android.widget.Button;
import com.google.android.material.button.MaterialButton;

/** Shared action button for sync and dashboard screens. */
final class SyncSettingsUi {
  private static int dp(Context c, float n) {
    return GalleryUi.dp(c, n);
  }

  private static boolean hasIcon(Button b) {
    String label = b.getText().toString();
    return label.contains("位置情報") || label.contains("アクセス") || label.equals("状態を更新");
  }

  static final class ActionButton extends MaterialButton {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path location = new Path();

    ActionButton(Context c, String label, Runnable action) {
      super(c);
      setText(label);
      location.moveTo(10, 18);
      location.cubicTo(2, 10, 2, 3, 10, 2);
      location.cubicTo(18, 3, 18, 10, 10, 18);
      setMinWidth(0);
      setMinimumWidth(0);
      setOnClickListener(
          v -> {
            GalleryUi.feedback(v);
            try (DiagnosticLog.Scope ignored = DiagnosticLog.span("action.button")) {
              action.run();
            }
          });
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      if (!hasIcon(this)) return;
      android.text.Layout layout = getLayout();
      if (layout == null) return;
      float left = getCompoundPaddingLeft() + layout.getLineLeft(0) - dp(getContext(), 28),
          top = getHeight() / 2f - dp(getContext(), 10);
      int save = canvas.save();
      canvas.translate(left, top);
      float d = getResources().getDisplayMetrics().density;
      canvas.scale(d, d);
      paint.setColor(getCurrentTextColor());
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(1.7f);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
      String label = getText().toString();
      if (label.contains("位置情報")) {
        canvas.drawCircle(10, 8, 2.3f, paint);
        canvas.drawPath(location, paint);
      } else if (label.contains("アクセス"))
        StatusView.glyph(canvas, paint, StatusView.Symbol.AUTH, 0, 0, 20, getCurrentTextColor(), 0);
      else {
        canvas.drawArc(3, 3, 17, 17, 210, 145, false, paint);
        canvas.drawArc(3, 3, 17, 17, 30, 145, false, paint);
        canvas.drawLine(17, 3, 17, 8, paint);
        canvas.drawLine(17, 8, 12, 8, paint);
        canvas.drawLine(3, 17, 3, 12, paint);
        canvas.drawLine(3, 12, 8, 12, paint);
      }
      canvas.restoreToCount(save);
    }
  }
}
