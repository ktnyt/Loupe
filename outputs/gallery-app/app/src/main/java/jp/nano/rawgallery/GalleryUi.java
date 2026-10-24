package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

final class GalleryUi {
  static final int BG = 0xff151719,
      PANEL = 0xff22262a,
      TEXT = 0xfff1f3f4,
      MUTED = 0xffacb3b8,
      ACCENT = 0xffc7d8e4;

  static int dp(Context c, float value) {
    return Math.round(value * c.getResources().getDisplayMetrics().density);
  }

  static GradientDrawable shape(Context c, int color, int radius) {
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(color);
    shape.setCornerRadius(dp(c, radius));
    return shape;
  }

  static Button button(Context c, String text, Runnable action) {
    Button button = new Button(c);
    button.setText(text);
    button.setAllCaps(false);
    button.setTextSize(13);
    button.setTypeface(
        android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    button.setTextColor(
        new ColorStateList(
            new int[][] {new int[] {android.R.attr.state_enabled}, new int[] {}},
            new int[] {TEXT, 0xff626b72}));
    button.setMinHeight(dp(c, 48));
    button.setMinimumHeight(dp(c, 48));
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(dp(c, 10), 0, dp(c, 10), 0);
    button.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(0x33c7d8e4),
            shape(c, Color.TRANSPARENT, 14),
            shape(c, Color.WHITE, 14)));
    button.setContentDescription(text);
    button.setOnClickListener(
        v -> {
          feedback(v);
          action.run();
        });
    return button;
  }

  static void feedback(View view) {
    if (view.isShown() && view.isEnabled())
      view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
  }

  static Button primary(Button button) {
    Context c = button.getContext();
    button.setTextColor(ACCENT);
    button.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(0x44c7d8e4),
            shape(c, 0xff344753, 14),
            shape(c, Color.WHITE, 14)));
    return button;
  }

  static View dock(Context c, Button... buttons) {
    int actionHeight = dp(c, Math.max(48, 34 * c.getResources().getConfiguration().fontScale));
    int rowHeight = actionHeight + dp(c, 8), totalHeight = rowHeight + dp(c, 8);
    FrameLayout outer =
        new FrameLayout(c) {
          protected void onMeasure(int w, int h) {
            super.onMeasure(w, h);
            View row = getChildAt(0);
            int available = Math.max(0, MeasureSpec.getSize(w) - dp(c, 24));
            row.measure(
                MeasureSpec.makeMeasureSpec(Math.min(available, dp(c, 560)), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY));
            setMeasuredDimension(MeasureSpec.getSize(w), totalHeight);
          }
        };
    LinearLayout row = new LinearLayout(c);
    row.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setBackground(shape(c, PANEL, 22));
    for (Button button : buttons) {
      String label = button.getText().toString();
      button.setContentDescription(label);
      button.setTooltipText(label);
      button.setText("");
      button.setForeground(new ActionIcon(label, c.getResources().getDisplayMetrics().density));
      row.addView(button, new LinearLayout.LayoutParams(0, actionHeight, 1));
    }
    FrameLayout.LayoutParams lp =
        new FrameLayout.LayoutParams(-1, rowHeight, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    outer.addView(row, lp);
    return outer;
  }

  static final class Sheet extends AlertDialog.Builder {
    private final Context context;

    Sheet(Context c) {
      super(c);
      context = c;
    }

    @Override
    public AlertDialog show() {
      AlertDialog dialog = super.show();
      Window window = dialog.getWindow();
      if (window != null) {
        window.setBackgroundDrawable(shape(context, PANEL, 20));
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int width = context.getResources().getDisplayMetrics().widthPixels;
        if (context instanceof Activity)
          width =
              ((Activity) context).getWindowManager().getCurrentWindowMetrics().getBounds().width();
        window.setLayout(
            Math.min(width - dp(context, 24), dp(context, 560)),
            WindowManager.LayoutParams.WRAP_CONTENT);
        WindowManager.LayoutParams params = window.getAttributes();
        params.y = dp(context, 8);
        window.setAttributes(params);
        final int sheetWidth = Math.min(width - dp(context, 24), dp(context, 560));
        int screenHeight =
            context instanceof Activity
                ? ((Activity) context)
                    .getWindowManager()
                    .getCurrentWindowMetrics()
                    .getBounds()
                    .height()
                : context.getResources().getDisplayMetrics().heightPixels;
        final int maxHeight = (int) (screenHeight * .7f);
        window
            .getDecorView()
            .post(
                () -> {
                  if (dialog.isShowing() && window.getDecorView().getHeight() > maxHeight)
                    window.setLayout(sheetWidth, maxHeight);
                });
      }
      return dialog;
    }
  }
}
