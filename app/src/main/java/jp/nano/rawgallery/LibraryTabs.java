package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.Drawable;
import android.content.res.ColorStateList;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.function.Consumer;

/** Fixed peer navigation; contract: docs/design/README.md Issue #12. */
final class LibraryTabs extends LinearLayout {
  private final Tab camera;
  private final Tab selectedTab;
  private final boolean enabled;
  private final CameraIcon cameraIcon;
  private boolean importing;

  LibraryTabs(Context c, boolean albums, boolean enabled, Consumer<Boolean> change) {
    this(c, albums, enabled, change, null, null);
  }

  LibraryTabs(Context c, boolean albums, boolean enabled, Consumer<Boolean> change,
      Runnable openCamera, Runnable startImport) {
    super(c);
    this.enabled = enabled;
    setOrientation(VERTICAL); setBackgroundColor(GalleryUi.BG);
    View rule = new View(c); rule.setBackgroundColor(GalleryUi.PANEL);
    addView(rule, new LayoutParams(-1, GalleryUi.dp(c, 1)));
    LinearLayout row = new LinearLayout(c);
    cameraIcon = openCamera == null ? null : new CameraIcon(c);
    camera = openCamera == null ? null : new Tab(c, "カメラ", false, 2, cameraIcon);
    row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
      @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(host, info);
        info.setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(1, camera != null && camera.getVisibility() == VISIBLE ? 3 : 2, false,
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
    selectedTab = (Tab) row.getChildAt(albums ? 1 : 0);
    if (camera != null) {
      camera.setVisibility(GONE);
      camera.setEnabled(enabled);
      camera.setAlpha(enabled ? 1f : .38f);
      camera.setOnClickListener(v -> {
        if (enabled && camera.getVisibility() == VISIBLE) {
          GalleryUi.feedback(v);
          openCamera.run();
        }
      });
      camera.setOnLongClickListener(v -> {
        if (!enabled || camera.getVisibility() != VISIBLE) return false;
        if (!importing) startImport.run();
        return true;
      });
      row.addView(camera, new LayoutParams(0, -1, 1));
    }
    addView(row, new LayoutParams(-1, -2));
  }

  void setCameraState(boolean connected, String name, boolean active, int done, int total) {
    if (camera == null) return;
    importing = active;
    boolean hadFocus = camera.hasFocus();
    camera.setVisibility(connected ? VISIBLE : GONE);
    if (!connected) {
      if (hadFocus) selectedTab.requestFocus();
      return;
    }
    camera.setLongClickable(enabled);
    camera.announceImportAction = enabled && !active;
    String description = name == null || name.isBlank() ? "カメラ" : "カメラ、" + name;
    if (active) description += total > 0 ? "、取り込み中 " + done + " / " + total + "件" : "、取り込み中";
    if (!enabled) description += "、選択中は使えません";
    else if (!active) description += "、長押しで取り込み";
    if (!android.text.TextUtils.equals(camera.getContentDescription(), description))
      camera.setContentDescription(description);
    cameraIcon.setProgress(active, done, total);
  }

  private static final class CameraIcon extends Drawable {
    private final Drawable glyph;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float stroke;
    private boolean active;
    private float fraction;
    private int offsetY;
    CameraIcon(Context c) {
      glyph = c.getDrawable(R.drawable.ic_camera_usb).mutate();
      int size = GalleryUi.dp(c, 32);
      setBounds(0, 0, size, size);
      int inset = GalleryUi.dp(c, 4);
      glyph.setBounds(inset, inset, size - inset, size - inset);
      stroke = GalleryUi.dp(c, 2);
      ring.setStyle(Paint.Style.STROKE);
      ring.setStrokeWidth(stroke);
      ring.setStrokeCap(Paint.Cap.ROUND);
    }
    void setProgress(boolean value, int done, int total) {
      float next = total > 0 ? Math.min(1f, Math.max(0f, (float) done / total)) : .25f;
      if (active == value && fraction == next) return;
      active = value;
      fraction = next;
      invalidateSelf();
    }
    @Override public void draw(Canvas canvas) {
      int saved = canvas.save();
      canvas.translate(0, offsetY);
      if (active) {
        float inset = stroke / 2;
        float end = getBounds().width() - inset;
        ring.setColor(0xff3a3a3f);
        canvas.drawOval(inset, inset, end, end, ring);
        if (fraction > 0) {
          ring.setColor(GalleryUi.TEXT);
          canvas.drawArc(inset, inset, end, end, -90, 360 * fraction, false, ring);
        }
      }
      glyph.draw(canvas);
      canvas.restoreToCount(saved);
    }
    @Override public void setAlpha(int alpha) { glyph.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { glyph.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
  }

  private static final class Tab extends TextView {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int column;
    private final CameraIcon icon;
    private boolean announceImportAction;
    private final Rect labelBounds = new Rect();
    Tab(Context c, String name, boolean selected, int column) {
      this(c, name, selected, column, null);
    }
    Tab(Context c, String name, boolean selected, int column, CameraIcon icon) {
      super(c); this.column = column; this.icon = icon;
      setText(name); setContentDescription(name); setSelected(selected);
      setTextSize(16); setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      setIncludeFontPadding(false);
      setLineHeight(Math.round(24 * getResources().getDisplayMetrics().scaledDensity));
      setTextColor(selected || icon != null ? GalleryUi.TEXT : GalleryUi.MUTED);
      setGravity(Gravity.CENTER); setMinHeight(GalleryUi.dp(c, icon == null ? 48 : 56));
      if (icon != null) setCompoundDrawablePadding(GalleryUi.dp(c, 4));
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
      if (icon != null) {
        boolean stacked = getPaint().measureText("カメラ") + icon.getBounds().width()
            + getCompoundDrawablePadding() + getPaddingLeft() + getPaddingRight()
            > MeasureSpec.getSize(widthSpec);
        Drawable[] current = getCompoundDrawablesRelative();
        if (current[stacked ? 1 : 0] != icon)
          setCompoundDrawablesRelative(stacked ? null : icon, stacked ? icon : null, null, null);
      }
      super.onMeasure(widthSpec, heightSpec);
      if (MeasureSpec.getMode(heightSpec) != MeasureSpec.EXACTLY)
        setMeasuredDimension(getMeasuredWidth(), resolveSize(Math.max(getMeasuredHeight(),
            lineBoxHeight() + getPaddingTop() + getPaddingBottom()), heightSpec));
    }
    @Override protected void onDraw(Canvas canvas) {
      if (icon != null) {
        icon.offsetY = 0;
        if (getCompoundDrawablesRelative()[1] == icon && getLayout() != null) {
          getPaint().getTextBounds("カメラ", 0, 3, labelBounds);
          icon.offsetY = Math.max(0, getBaseline() + labelBounds.top - getPaddingTop()
              - icon.getBounds().height() - GalleryUi.dp(getContext(), 4));
        }
      }
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
      if (icon != null) {
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
        if (announceImportAction) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
            AccessibilityNodeInfo.ACTION_LONG_CLICK, "原本を削除せずに取り込む"));
      }
    }
  }
}
