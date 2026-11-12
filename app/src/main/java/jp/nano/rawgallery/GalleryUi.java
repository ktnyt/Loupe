package jp.nano.rawgallery;

import android.app.*;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.util.function.IntSupplier;

final class GalleryUi {
  static final int BG = 0xff111318,
      PANEL = 0xff1d2026,
      TEXT = 0xffe2e2e9,
      MUTED = 0xffc4c6d0,
      ACCENT = 0xffadc6ff;

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
    Button button = MaterialUi.button(c);
    button.setText(text);
    button.setTextSize(14);
    button.setPadding(dp(c, 12), dp(c, 4), dp(c, 12), dp(c, 4));
    button.setContentDescription(text);
    decorateAction(button);
    button.setOnClickListener(
        v -> {
          feedback(v);
          try (DiagnosticLog.Scope ignored = DiagnosticLog.span("action.button")) { action.run(); }
        });
    return button;
  }

  private static void decorateAction(Button button) {
    String title = button.getText().toString();
    String symbol = title;
    if (title.startsWith("すべての写真")) symbol = "すべての写真";
    else if (title.contains("拡大")) symbol = "拡大";
    else if (title.equals("Aを選ぶ") || title.equals("Bを選ぶ") || title.equals("この写真を選ぶ")) symbol = "選択";
    switch (symbol) {
      case "戻る": case "選択": case "選択を終了": case "選択へ戻る": case "比較に戻る":
      case "写真一覧": case "連写一覧": case "その他": case "タグ": case "共有":
      case "すべての写真": case "すべて選択": case "ソート": case "ソートを再開": case "ソート結果": case "同じ順位":
      case "直前の操作を取り消す": case "拡大": case "前の写真": case "次の写真":
      case "同期設定": case "ログイン":
        ActionIcon icon = new ActionIcon(symbol, button.getResources().getDisplayMetrics().density);
        int size = dp(button.getContext(), 24); icon.setBounds(0, 0, size, size);
        button.setCompoundDrawables(null, icon, null, null);
        button.setCompoundDrawablePadding(dp(button.getContext(), 4));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(button.getContext(), 6), dp(button.getContext(), 6), dp(button.getContext(), 6), dp(button.getContext(), 6));
        button.setMinHeight(dp(button.getContext(), 64));
        button.setMinimumHeight(dp(button.getContext(), 64));
        break;
      default: break;
    }
  }

  private static void tintIcon(Button button, int color) {
    button.setCompoundDrawableTintList(new ColorStateList(
        new int[][] {new int[] {android.R.attr.state_enabled}, new int[] {}},
        new int[] {color, 0xff626b72}));
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
    MaterialUi.filled(button);
    tintIcon(button, 0xff002e69);
    return button;
  }

  static void feedback(View view) {
    if (view.isShown() && view.isEnabled())
      view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
  }

  static Button primary(Button button) {
    MaterialUi.filled(button);
    tintIcon(button, 0xff002e69);
    return button;
  }

  static View dock(Context c, Button... buttons) {
    LinearLayout row = new LinearLayout(c);
    row.setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8));
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setBackgroundColor(PANEL);
    for (Button button : buttons) {
      decorateAction(button);
      tintIcon(button, button.getTextColors().getColorForState(
          new int[] {android.R.attr.state_enabled}, TEXT));
      button.setTooltipText(button.getText());
      row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }
    return row;
  }

  static View adaptiveDock(Context c, Button... buttons) {
    for (Button button : buttons) {
      // Keep the original action icon and full accessible name when its visible label is short.
      tintIcon(button, button.getTextColors().getColorForState(
          new int[] {android.R.attr.state_enabled}, TEXT));
      button.setTooltipText(button.getContentDescription());
    }
    return new ActionRows(c, buttons);
  }

  static Button chip(Context c, String title, boolean selected, Runnable action) {
    Button button = button(c, title, action);
    MaterialUi.choice(button, selected);
    button.setStateDescription(selected ? "選択中" : "未選択");
    return button;
  }

  // Keep controls reachable while allowing content to occupy the remaining screen.
  static void bottomControls(LinearLayout root, int first) { bottomControls(root, first, PANEL); }

  static void bottomControls(LinearLayout root, int first, int background) {
    bottomControls(root, first, background, null);
  }

  static void bottomControls(LinearLayout root, int first, int background, IntSupplier reservedContentHeight) {
    Context c = root.getContext();
    LinearLayout controls = new LinearLayout(c); controls.setOrientation(LinearLayout.VERTICAL);
    controls.setBackgroundColor(background);
    while (root.getChildCount() > first) {
      View child = root.getChildAt(first); root.removeViewAt(first);
      controls.addView(child);
    }
    ScrollView scroll = new ScrollView(c) {
      @Override protected void onMeasure(int width, int height) {
        android.graphics.Rect visible = new android.graphics.Rect();
        getWindowVisibleDisplayFrame(visible);
        int available = visible.height() > 0 ? visible.height() : getResources().getDisplayMetrics().heightPixels;
        int limit = (int) (available * .48f);
        if (reservedContentHeight != null) {
          limit = Math.max(limit, available - reservedContentHeight.getAsInt());
        }
        int cap = Math.min(MeasureSpec.getSize(height), limit);
        super.onMeasure(width, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
      }
    };
    scroll.setFillViewport(false); scroll.addView(controls);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
  }

  static final class Sheet extends MaterialAlertDialogBuilder {
    Sheet(Context c) { super(c); }
    @Override public AlertDialog create() {
      AlertDialog dialog = super.create();
      DiagnosticWindow.install(dialog.getWindow());
      return dialog;
    }
    @Override public AlertDialog show() {
      AlertDialog dialog = create(); dialog.show(); return dialog;
    }
  }

}
