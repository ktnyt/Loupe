package jp.nano.rawgallery;

import android.content.Intent;
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

  @Override protected void onResume() {
    super.onResume();
    main.post(refresh);
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
