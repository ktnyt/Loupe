package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.function.Consumer;

/** Fixed peer navigation; contract: docs/design/README.md Issue #12. */
final class LibraryTabs extends LinearLayout {
  LibraryTabs(Context c, boolean albums, boolean enabled, Consumer<Boolean> change) {
    super(c);
    setOrientation(VERTICAL); setBackgroundColor(GalleryUi.BG);
    View rule = new View(c); rule.setBackgroundColor(GalleryUi.PANEL);
    addView(rule, new LayoutParams(-1, GalleryUi.dp(c, 1)));
    LinearLayout row = new LinearLayout(c);
    row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
      @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(host, info);
        info.setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(1, 2, false,
            AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE));
      }
    });
    row.setPadding(GalleryUi.dp(c, 8), 0, GalleryUi.dp(c, 8), 0);
    for (int i = 0; i < 2; i++) {
      boolean target = i == 1;
      Tab tab = new Tab(c, target ? "アルバム" : "すべての写真", target == albums, i);
      tab.setEnabled(enabled); tab.setAlpha(enabled ? 1f : .38f);
      tab.setOnClickListener(v -> { if (tab.isEnabled() && !tab.isSelected()) {
        GalleryUi.feedback(v); change.accept(target);
      }});
      row.addView(tab, new LayoutParams(0, -1, 1));
    }
    addView(row, new LayoutParams(-1, -2));
  }

  private static final class Tab extends TextView {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int column;
    Tab(Context c, String name, boolean selected, int column) {
      super(c); this.column = column;
      setText(name); setContentDescription(name); setSelected(selected);
      setTextSize(16); setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      setIncludeFontPadding(false);
      setLineHeight(Math.round(24 * getResources().getDisplayMetrics().scaledDensity));
      setTextColor(selected ? GalleryUi.TEXT : GalleryUi.MUTED);
      setGravity(Gravity.CENTER); setMinHeight(GalleryUi.dp(c, 48));
      setPadding(GalleryUi.dp(c, 4), GalleryUi.dp(c, 6), GalleryUi.dp(c, 4), GalleryUi.dp(c, 12));
      setBackground(new RippleDrawable(ColorStateList.valueOf(0x33c7d8e4), null,
          GalleryUi.shape(c, Color.WHITE, 0)));
      setFocusable(true); setClickable(true); line.setColor(GalleryUi.TEXT);
    }
    private int lineBoxHeight() {
      return Math.max(getLayout() == null ? 0 : getLayout().getHeight(),
          Math.round(24 * getResources().getDisplayMetrics().scaledDensity)
              * (getLayout() == null ? 1 : getLayout().getLineCount()));
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
      super.onMeasure(widthSpec, heightSpec);
      if (MeasureSpec.getMode(heightSpec) != MeasureSpec.EXACTLY)
        setMeasuredDimension(getMeasuredWidth(), resolveSize(Math.max(getMeasuredHeight(),
            lineBoxHeight() + getPaddingTop() + getPaddingBottom()), heightSpec));
    }
    @Override protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      if (!isSelected() || getLayout() == null) return;
      float width = 0;
      for (int i = 0; i < getLayout().getLineCount(); i++) width = Math.max(width, getLayout().getLineWidth(i));
      width = Math.min(width, getWidth() - getPaddingLeft() - getPaddingRight());
      // Android single-line layout may be shorter than setLineHeight; use the contract's line box.
      float top = getPaddingTop() + (getHeight() - getPaddingTop()
          - getPaddingBottom() + lineBoxHeight()) / 2f + GalleryUi.dp(getContext(), 4);
      canvas.drawRoundRect((getWidth()-width)/2, top, (getWidth()+width)/2,
          top + GalleryUi.dp(getContext(), 2), GalleryUi.dp(getContext(), 1), GalleryUi.dp(getContext(), 1), line);
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
      super.onInitializeAccessibilityNodeInfo(info);
      info.setClassName("android.app.ActionBar$Tab");
      info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(0,1,column,1,false,isSelected()));
    }
  }
}
