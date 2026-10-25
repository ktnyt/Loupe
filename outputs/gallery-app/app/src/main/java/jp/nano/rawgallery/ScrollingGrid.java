package jp.nano.rawgallery;

import android.content.Context;
import android.database.DataSetObserver;
import android.view.*;
import android.widget.*;

/** A recycled grid of rows with a normal, non-sticky full-width list header. */
final class ScrollingGrid extends ListView {
  private int columns = 1, gap;
  private AdapterView.OnItemClickListener click;
  private AdapterView.OnItemLongClickListener longClick;
  private BaseAdapter cells;
  private DataSetObserver observer;

  ScrollingGrid(Context context) {
    super(context);
    setDivider(null);
    setDividerHeight(0);
    setItemsCanFocus(true);
  }

  void setNumColumns(int count) { columns = count; }
  void setHorizontalSpacing(int spacing) { gap = spacing; }
  void setVerticalSpacing(int spacing) { setDividerHeight(spacing); }
  void onCellClick(AdapterView.OnItemClickListener listener) { click = listener; }
  void onCellLongClick(AdapterView.OnItemLongClickListener listener) { longClick = listener; }
  int firstCellPosition() {
    return Math.max(0, getFirstVisiblePosition() - getHeaderViewsCount()) * columns;
  }
  void selectCell(int position) {
    // Position zero includes the header, so returning to the beginning exposes the carousel.
    setSelection(position == 0 ? 0 : position / columns + getHeaderViewsCount());
  }

  void setCells(BaseAdapter source) {
    if (cells != null && observer != null) cells.unregisterDataSetObserver(observer);
    cells = source;
    BaseAdapter rows = new BaseAdapter() {
      public int getCount() { return (source.getCount() + columns - 1) / columns; }
      public Object getItem(int position) { return source.getItem(position * columns); }
      public long getItemId(int position) { return position; }
      public boolean areAllItemsEnabled() { return false; }
      public boolean isEnabled(int position) { return false; }
      public View getView(int position, View recycled, ViewGroup parent) {
        LinearLayout row = recycled instanceof LinearLayout ? (LinearLayout) recycled : new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int column = 0; column < columns; column++) {
          final int index = position * columns + column;
          View previous = column < row.getChildCount() ? row.getChildAt(column) : null;
          View cell = index < source.getCount() ? source.getView(index, previous, row) : new View(getContext());
          if (previous != cell) {
            if (previous != null) row.removeViewAt(column);
            row.addView(cell, column);
          }
          LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
          params.setMarginEnd(column < columns - 1 ? gap : 0);
          cell.setLayoutParams(params);
          cell.setVisibility(index < source.getCount() ? VISIBLE : INVISIBLE);
          cell.setOnClickListener(v -> { if (click != null) click.onItemClick(ScrollingGrid.this, v, index, source.getItemId(index)); });
          cell.setOnLongClickListener(v -> longClick != null && longClick.onItemLongClick(ScrollingGrid.this, v, index, source.getItemId(index)));
          cell.setHapticFeedbackEnabled(false);
        }
        return row;
      }
    };
    observer = new DataSetObserver() {
      public void onChanged() { rows.notifyDataSetChanged(); }
      public void onInvalidated() { rows.notifyDataSetInvalidated(); }
    };
    source.registerDataSetObserver(observer);
    setAdapter(rows);
  }
}
