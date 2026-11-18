package jp.nano.rawgallery;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Separate settings and restore entry point for the e2 backup destination. */
public final class E2SyncActivity extends DiagnosticActivity {
  private E2Panel panel;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable refresh = new Runnable() {
    @Override public void run() {
      panel.refreshAuto();
      main.postDelayed(this, 1000);
    }
  };

  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setBackgroundColor(GalleryUi.BG);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.addView(content);
    scroll.setOnApplyWindowInsetsListener((view, insets) -> {
      Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
      view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
      return insets;
    });
    setContentView(scroll);
    panel = new E2Panel(this, content, saved);
  }

  static boolean hasPhotoAccess(Context context) {
    return context.checkSelfPermission(imagePermission()) == PackageManager.PERMISSION_GRANTED
        && context.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
  }

  private static String imagePermission() {
    return Build.VERSION.SDK_INT >= 33 ? android.Manifest.permission.READ_MEDIA_IMAGES
        : android.Manifest.permission.READ_EXTERNAL_STORAGE;
  }

  void requestPhotoAccess() {
    java.util.ArrayList<String> permissions = new java.util.ArrayList<>();
    for (String permission : new String[] {imagePermission(),
        android.Manifest.permission.ACCESS_MEDIA_LOCATION}) {
      if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
        permissions.add(permission);
    }
    if (!permissions.isEmpty()) requestPermissions(permissions.toArray(new String[0]), 703);
  }

  @Override protected void onResume() {
    super.onResume();
    applySystemBarAppearance();
    main.post(refresh);
  }

  @Override public void onWindowFocusChanged(boolean focused) {
    super.onWindowFocusChanged(focused);
    if (focused) applySystemBarAppearance();
  }

  private void applySystemBarAppearance() {
    android.view.WindowInsetsController controller = getWindow().getInsetsController();
    if (controller != null) controller.setSystemBarsAppearance(0,
        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
  }

  @Override protected void onPause() {
    main.removeCallbacks(refresh);
    super.onPause();
  }

  @Override public void onBackPressed() {
    if (!panel.confirmBack()) super.onBackPressed();
  }

  @Override public Object onRetainCustomNonConfigurationInstance() {
    return panel.retainedState();
  }

  @Override protected void onSaveInstanceState(Bundle state) {
    panel.saveState(state);
    super.onSaveInstanceState(state);
  }

  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    panel.result(request, result, data);
  }

  @Override protected void onDestroy() {
    panel.close();
    super.onDestroy();
  }
}
