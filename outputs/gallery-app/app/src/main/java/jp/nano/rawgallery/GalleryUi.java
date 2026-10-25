package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

final class GalleryUi {
  static final int BG = 0xff0f0f10,
      PANEL = 0xff19191b,
      TEXT = 0xffededea,
      MUTED = 0xffa9a9a4,
      ACCENT = 0xff4c8dff;

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

  static Button icon(Context c, String title, Runnable action) {
    Button button = button(c, title, action);
    button.setText("");
    button.setContentDescription(title);
    button.setTooltipText(title);
    button.setForeground(new ActionIcon(title, c.getResources().getDisplayMetrics().density));
    button.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
    return button;
  }

  static Button neutralPrimary(Button button) {
    button.setTextColor(0xff111111);
    button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000),
        shape(button.getContext(), TEXT, 26), null));
    return button;
  }

  static void feedback(View view) {
    if (view.isShown() && view.isEnabled())
      view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
  }

  static Button primary(Button button) {
    Context c = button.getContext();
    button.setTextColor(TEXT);
    button.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(0x44c7d8e4),
            shape(c, 0xff285fbb, 24),
            shape(c, Color.WHITE, 14)));
    return button;
  }

  static View dock(Context c, Button... buttons) {
    LinearLayout row = new LinearLayout(c);
    row.setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8));
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setBackgroundColor(PANEL);
    for (Button button : buttons) {
      button.setTooltipText(button.getText());
      row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }
    return row;
  }

  // The check occupies the same advance in both states; no neighbouring control moves.
  static Button chip(Context c, String title, boolean selected, Runnable action) {
    Button button = button(c, "✓  " + title, action);
    android.text.SpannableString text = new android.text.SpannableString("✓  " + title);
    text.setSpan(new android.text.style.ForegroundColorSpan(
        selected ? TEXT : Color.TRANSPARENT), 0, 1, 0);
    button.setText(text);
    button.setContentDescription(title);
    button.setSelected(selected);
    button.setStateDescription(selected ? "選択中" : "未選択");
    GradientDrawable fill = shape(c, selected ? 0xff2c2c30 : 0xff19191b, 8);
    fill.setStroke(dp(c, 1), selected ? 0xff66666c : 0xff343438);
    android.graphics.drawable.InsetDrawable inset = new android.graphics.drawable.InsetDrawable(
        fill, 0, dp(c, 8), 0, dp(c, 8));
    button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x334c8dff), inset, null));
    button.setPadding(dp(c, 12), 0, dp(c, 12), 0);
    return button;
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
