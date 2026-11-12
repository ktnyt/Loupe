package jp.nano.rawgallery;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import com.google.android.material.tabs.TabLayout;
import java.util.function.Consumer;

/** Peer destinations with native Material tab selection and accessibility. */
final class LibraryTabs extends TabLayout {
  @Override public void selectTab(Tab tab, boolean updateIndicator) {
    if(isEnabled())super.selectTab(tab,updateIndicator);
  }

  LibraryTabs(Context context, boolean albums, boolean enabled, Consumer<Boolean> change) {
    super(context);
    setTabMode(MODE_FIXED);
    setTabGravity(GRAVITY_FILL);
    setMinimumHeight(GalleryUi.dp(context, 48));
    setBackgroundColor(GalleryUi.PANEL);
    addTab(newTab().setText("すべての写真"), !albums);
    addTab(newTab().setText("アルバム"), albums);
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
}
