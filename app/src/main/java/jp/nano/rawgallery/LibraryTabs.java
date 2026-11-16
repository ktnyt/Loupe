package jp.nano.rawgallery;

import android.content.Context;
import android.animation.ValueAnimator;
import android.os.Build;
import android.view.View;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;
import java.util.function.Consumer;

/** Persistent library destinations; import opens a separate activity without changing selection. */
final class LibraryTabs extends BottomNavigationView {
  private static final int PHOTOS = 1, ALBUMS = 2, IMPORT = 3;
  private final Runnable openCamera;
  private final Runnable startImport;
  private boolean connected;
  private boolean importing;
  private String cameraName;
  private int done, total;
  private boolean waiting;
  private ValueAnimator waitingPulse;

  LibraryTabs(Context context, boolean albums, boolean enabled, Consumer<Boolean> change) {
    this(context, albums, enabled, change, null, null);
  }

  LibraryTabs(Context context, boolean albums, boolean enabled, Consumer<Boolean> change,
      Runnable openCamera, Runnable startImport) {
    super(context);
    setTag("library-navigation");
    this.openCamera = openCamera;
    this.startImport = startImport;
    setBackgroundColor(GalleryUi.PANEL);
    setLabelVisibilityMode(NavigationBarView.LABEL_VISIBILITY_LABELED);
    setItemHorizontalTranslationEnabled(false);
    setLabelFontScalingEnabled(true);
    setLabelMaxLines(2);
    setItemActiveIndicatorEnabled(true);
    setMinimumHeight(GalleryUi.dp(context, 80));
    getMenu().add(0, PHOTOS, 0, "写真").setIcon(R.drawable.ic_nav_photos);
    getMenu().add(0, ALBUMS, 1, "アルバム").setIcon(R.drawable.ic_nav_albums);
    getMenu().add(0, IMPORT, 2, "取り込み").setIcon(R.drawable.ic_camera_usb);
    setSelectedItemId(albums ? ALBUMS : PHOTOS);
    setOnItemSelectedListener(item -> {
      if (!isEnabled()) return false;
      if (item.getItemId() == IMPORT) {
        if (this.openCamera != null) {
          GalleryUi.feedback(this);
          this.openCamera.run();
        }
        return false;
      }
      GalleryUi.feedback(this);
      change.accept(item.getItemId() == ALBUMS);
      return true;
    });
    setOnItemReselectedListener(item -> {});
    for (int i = 0; i < 3; i++) {
      View destination = destinationView(i);
      destination.setOnClickListener(view -> {
        // performClick() and accessibility can be invoked directly on a disabled view.
        // Keep the native menu selection path behind the same guard as physical taps.
        if (isEnabled() && view.isEnabled()) setSelectedItemId(view.getId());
      });
    }
    setEnabled(enabled);
    updateCameraAccessibility();
  }

  /** Index follows the stable photo / album / import visual order. */
  View destinationView(int index) {
    return findViewById(index == 0 ? PHOTOS : index == 1 ? ALBUMS : IMPORT);
  }

  int selectedDestination() { return getSelectedItemId() == ALBUMS ? 1 : 0; }

  @Override public void setEnabled(boolean enabled) {
    super.setEnabled(enabled);
    if (getMenu() == null) return;
    for (int i = 0; i < getMenu().size(); i++) getMenu().getItem(i).setEnabled(enabled);
    setAlpha(enabled ? 1f : .38f);
    if (getMenu().size() == 3) updateCameraAccessibility();
  }

  void setCameraState(boolean connected, String name, boolean active, int done, int total) {
    setCameraState(connected, name, active, done, total, false);
  }

  void setCameraState(boolean connected, String name, boolean active, int done, int total, boolean waiting) {
    this.connected = connected;
    cameraName = name;
    importing = active;
    this.done = done;
    this.total = total;
    this.waiting = active && waiting;
    if (connected || active) {
      var badge = getOrCreateBadge(IMPORT);
      if (active && total > 0) badge.setNumber(Math.max(0, done));
      else badge.clearNumber();
      badge.setContentDescriptionNumberless(this.waiting ? "カメラ接続待ち" : active ? "取り込み中" : "カメラ接続中");
    } else removeBadge(IMPORT);
    updateWaitingPulse();
    updateCameraAccessibility();
  }

  private void updateCameraAccessibility() {
    View camera = destinationView(2);
    if (camera == null) return;
    String state = connected ? "カメラ接続中" : "カメラ未接続";
    if (connected && cameraName != null && !cameraName.isBlank()) state += "、" + cameraName;
    if (importing) state += waiting ? "、カメラ接続待ち" : total > 0 ? "、取り込み中 " + done + " / " + total + "件" : "、取り込み中";
    if (!isEnabled()) state += "、選択中は使えません";
    ViewCompat.setStateDescription(camera, state);
    boolean quickImport = isEnabled() && !importing && startImport != null;
    camera.setOnLongClickListener(quickImport ? view -> {
      // Recheck live state as device and import events can arrive between gestures.
      if (!isEnabled() || importing) return false;
      startImport.run();
      return true;
    } : null);
    ViewCompat.removeAccessibilityAction(camera, AccessibilityNodeInfoCompat.ACTION_LONG_CLICK);
    if (quickImport) ViewCompat.replaceAccessibilityAction(camera,
        AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
        "原本を削除せずに取り込む", (view, arguments) -> view.performLongClick());
    camera.setLongClickable(quickImport);
  }

  private void updateWaitingPulse() {
    boolean shouldPulse = waiting && isAttachedToWindow() && isShown()
        && getWindowVisibility() == View.VISIBLE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        && ValueAnimator.areAnimatorsEnabled() && cameraBadge() != null;
    if (shouldPulse) {
      if (waitingPulse != null && waitingPulse.isStarted()) return;
      BadgeDrawable badge = cameraBadge();
      if (badge == null) return;
      badge.setAlpha(255);
      waitingPulse = ValueAnimator.ofInt(255, 150);
      waitingPulse.setDuration(1200);
      waitingPulse.setRepeatMode(ValueAnimator.REVERSE);
      waitingPulse.setRepeatCount(ValueAnimator.INFINITE);
      waitingPulse.addUpdateListener(animation -> setBadgeAlpha((Integer) animation.getAnimatedValue()));
      waitingPulse.start();
      return;
    }
    stopWaitingPulse();
  }

  private void setBadgeAlpha(int alpha) {
    BadgeDrawable badge = cameraBadge();
    if (badge != null) badge.setAlpha(alpha);
  }

  private BadgeDrawable cameraBadge() {
    return connected || importing ? getBadge(IMPORT) : null;
  }

  private void stopWaitingPulse() {
    if (waitingPulse != null) {
      waitingPulse.cancel();
      waitingPulse = null;
    }
    setBadgeAlpha(255);
  }

  @Override protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    updateWaitingPulse();
  }

  @Override protected void onDetachedFromWindow() {
    stopWaitingPulse();
    super.onDetachedFromWindow();
  }

  @Override protected void onVisibilityChanged(View changedView, int visibility) {
    super.onVisibilityChanged(changedView, visibility);
    updateWaitingPulse();
  }

  @Override protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    updateWaitingPulse();
  }
}
