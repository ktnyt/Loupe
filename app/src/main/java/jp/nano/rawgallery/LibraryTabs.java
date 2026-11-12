package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.View;
import android.view.ViewGroup;
import com.google.android.material.tabs.TabLayout;
import java.util.function.Consumer;

/** Peer destinations with native Material tab selection and accessibility. */
final class LibraryTabs extends TabLayout {
  private Tab camera;
  private CameraIcon cameraIcon;
  private boolean importing;
  private Runnable openCamera;
  private Runnable startImport;

  @Override public void selectTab(Tab tab, boolean updateIndicator) {
    if(isEnabled() && tab != camera) super.selectTab(tab,updateIndicator);
  }

  LibraryTabs(Context context, boolean albums, boolean enabled, Consumer<Boolean> change) {
    this(context, albums, enabled, change, null, null);
  }

  LibraryTabs(Context context, boolean albums, boolean enabled, Consumer<Boolean> change,
      Runnable openCamera, Runnable startImport) {
    super(context);
    setTabMode(MODE_FIXED);
    setTabGravity(GRAVITY_FILL);
    setMinimumHeight(GalleryUi.dp(context, 48));
    setBackgroundColor(GalleryUi.PANEL);
    addTab(newTab().setText("すべての写真"), !albums);
    addTab(newTab().setText("アルバム"), albums);
    this.openCamera = openCamera;
    this.startImport = startImport;
    setEnabled(enabled);
    setAlpha(enabled ? 1f : .38f);
    ViewGroup strip = (ViewGroup) getChildAt(0);
    for (int i = 0; i < strip.getChildCount(); i++) {
      View tab = strip.getChildAt(i);
      tab.setEnabled(enabled);
      tab.setMinimumHeight(GalleryUi.dp(context, 48));
    }
    addOnTabSelectedListener(new OnTabSelectedListener() {
      @Override public void onTabSelected(Tab tab) {
        if (isEnabled()) {
          GalleryUi.feedback(LibraryTabs.this);
          change.accept(tab.getPosition() == 1);
        }
      }
      @Override public void onTabUnselected(Tab tab) {}
      @Override public void onTabReselected(Tab tab) {}
    });
  }
  private void createCameraTab() {
      cameraIcon = new CameraIcon(getContext());
      camera = newTab().setText("カメラ").setIcon(cameraIcon);
      camera.view.setOnClickListener(v -> {
        if (isEnabled() && camera.getPosition() >= 0) {
          GalleryUi.feedback(v);
          openCamera.run();
        }
      });
      camera.view.setOnLongClickListener(v -> {
        if (!isEnabled() || camera.getPosition() < 0) return false;
        if (!importing && startImport != null) startImport.run();
        return true;
      });
      camera.view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
        @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
          super.onInitializeAccessibilityNodeInfo(host, info);
          info.setClassName("android.widget.Button");
          info.setSelected(false);
          info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
          if (isEnabled() && !importing) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
              AccessibilityNodeInfo.ACTION_LONG_CLICK, "原本を削除せずに取り込む"));
        }
      });
    }

  void setCameraState(boolean connected, String name, boolean active, int done, int total) {
    if (openCamera == null) return;
    importing = active;
    if (!connected) {
      if (camera != null) {
        boolean hadFocus = camera.view.hasFocus();
        removeTab(camera);
        camera = null;
        if (hadFocus && getSelectedTabPosition() >= 0) getTabAt(getSelectedTabPosition()).view.requestFocus();
      }
      return;
    }
    if (camera == null) {
      createCameraTab();
      addTab(camera, false);
    }
    camera.view.setVisibility(VISIBLE);
    camera.view.setEnabled(isEnabled());
    camera.view.setLongClickable(isEnabled());
    camera.view.setMinimumHeight(GalleryUi.dp(getContext(), 48));
    String description = name == null || name.isBlank() ? "カメラ" : "カメラ、" + name;
    if (active) description += total > 0 ? "、取り込み中 " + done + " / " + total + "件" : "、取り込み中";
    if (!isEnabled()) description += "、選択中は使えません";
    else if (!active) description += "、長押しで取り込み";
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

}
