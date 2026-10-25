package jp.nano.rawgallery;

import android.content.Context;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

/** Comparison actions keep their names and order; insufficient width adds rows, not smaller text. */
final class RankingActions extends ViewGroup {
  private int columns = 1;
  private final int[] rowHeights;

  RankingActions(Context context, Button... buttons) {
    super(context);
    int padding = GalleryUi.dp(context, 8);
    setPadding(padding, padding, padding, padding);
    setBackgroundColor(GalleryUi.PANEL);
    rowHeights = new int[buttons.length];
    for (Button button : buttons) addView(button);
  }

  @Override protected void onMeasure(int widthSpec, int heightSpec) {
    int need = GalleryUi.dp(getContext(), 48);
    for (int i = 0; i < getChildCount(); i++) {
      Button b = (Button) getChildAt(i);
      need = Math.max(need, (int)Math.ceil(Layout.getDesiredWidth(b.getText(), b.getPaint()))
          + b.getCompoundPaddingLeft() + b.getCompoundPaddingRight());
    }
    int width = resolveSize(need * getChildCount() + getPaddingLeft() + getPaddingRight(), widthSpec);
    int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
    columns = Math.max(1, Math.min(getChildCount(), available / need));
    java.util.Arrays.fill(rowHeights, 0);
    for (int i = 0; i < getChildCount(); i++) {
      int col = i % columns;
      int cell = available * (col+1) / columns - available * col / columns;
      View child = getChildAt(i);
      child.measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY),
          MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
      rowHeights[i/columns] = Math.max(rowHeights[i/columns], child.getMeasuredHeight());
    }
    int height = getPaddingTop() + getPaddingBottom();
    for (int rowHeight : rowHeights) height += rowHeight;
    setMeasuredDimension(width, resolveSize(height, heightSpec));
  }

  @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
    int available = getWidth() - getPaddingLeft() - getPaddingRight();
    int y = getPaddingTop();
    for (int i = 0; i < getChildCount(); i++) {
      int col = i % columns;
      int x = getPaddingLeft() + available * col / columns;
      View child = getChildAt(i);
      child.layout(x, y, x + child.getMeasuredWidth(), y + rowHeights[i/columns]);
      if (col == columns-1) y += rowHeights[i/columns];
    }
  }
}
