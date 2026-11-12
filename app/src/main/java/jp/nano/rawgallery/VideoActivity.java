package jp.nano.rawgallery;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

/** Local video playback. Opening or resuming never starts audio without a play action. */
public final class VideoActivity extends DiagnosticActivity {
  private VideoView video;
  private Button play;
  private TextView status;
  private Uri source;
  private boolean prepared, failed;
  private int position;
  private long opened, request;

  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    source = getIntent().getData();
    position = saved == null ? 0 : saved.getInt("position", 0);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.BLACK);
    root.setOnApplyWindowInsetsListener((view, insets) -> {
      Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
      return insets;
    });
    String name = getIntent().getStringExtra("name");
    TextView title = PhotoListUi.text(this, name == null ? "動画" : name, 16, 22, GalleryUi.TEXT);
    title.setPadding(dp(20), dp(12), dp(20), dp(12));
    title.setAccessibilityHeading(true);
    root.addView(title, new LinearLayout.LayoutParams(-1, -2));
    FrameLayout stage = new FrameLayout(this);
    stage.setTag("video-stage");
    video = new VideoView(this) {
      @Override public void start() {
        super.start();
        if (prepared) {
          play.setText("一時停止");
          play.setContentDescription("動画を一時停止");
          status.setText("");
          setStateDescription("再生中");
          DiagnosticLog.event("video.play", request);
        }
      }
      @Override public void pause() {
        super.pause();
        if (prepared) {
          play.setText("再生");
          play.setContentDescription("動画を再生");
          setStateDescription("一時停止中");
          DiagnosticLog.event("video.pause", request);
        }
      }
    };
    video.setContentDescription("動画");
    stage.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
    root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
    MediaController controller = new MediaController(this);
    controller.setAnchorView(stage);
    video.setMediaController(controller);
    status = PhotoListUi.text(this, "", 14, 20, GalleryUi.MUTED);
    status.setPadding(dp(20), dp(8), dp(20), dp(8));
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    root.addView(status, new LinearLayout.LayoutParams(-1, -2));
    play = MaterialUi.button(this);
    play.setText("再生");
    play.setContentDescription("動画を再生");
    play.setMinHeight(dp(48));
    play.setOnClickListener(v -> {
      if (failed) load();
      else if (prepared) {
        if (video.isPlaying()) video.pause();
        else video.start();
      }
    });
    LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(-1, -2);
    action.setMargins(dp(20), dp(8), dp(20), dp(20));
    root.addView(play, action);
    setContentView(root);
    video.setOnPreparedListener(player -> {
      prepared = true;
      play.setEnabled(true);
      video.seekTo(position);
      // VideoView's target state remains paused until the explicit user play action.
      video.pause();
      status.setText("");
      video.setStateDescription("再生できます");
      DiagnosticLog.event("video.prepared", request, "elapsed_ms", SystemClock.elapsedRealtime() - opened);
    });
    video.setOnCompletionListener(player -> {
      position = 0;
      video.seekTo(0);
      video.pause();
      video.setStateDescription("再生終了");
      DiagnosticLog.event("video.complete", request);
    });
    video.setOnErrorListener((player, what, extra) -> {
      fail();
      DiagnosticLog.event("video.error", request, "what", what, "extra", extra);
      return true;
    });
    load();
  }

  private void load() {
    prepared = false;
    failed = false;
    play.setEnabled(false);
    play.setText("再生");
    status.setText("読み込み中");
    video.setStateDescription("読み込み中");
    opened = SystemClock.elapsedRealtime();
    request = DiagnosticLog.id();
    DiagnosticLog.event("video.open", request);
    if (source == null || !("content".equals(source.getScheme()) || "file".equals(source.getScheme()))) {
      fail();
      return;
    }
    try { video.setVideoURI(source); }
    catch (RuntimeException unavailable) { fail(); }
  }

  private void fail() {
    prepared = false;
    failed = true;
    play.setEnabled(true);
    play.setText("再試行");
    play.setContentDescription("動画の読み込みを再試行");
    status.setText("動画を再生できません。形式やアクセス許可を確認してください。");
    video.setStateDescription("再生できません");
  }

  @Override protected void onPause() {
    if (prepared) {
      position = video.getCurrentPosition();
      video.pause();
    }
    super.onPause();
  }

  @Override protected void onSaveInstanceState(Bundle state) {
    if (prepared) position = video.getCurrentPosition();
    state.putInt("position", position);
    super.onSaveInstanceState(state);
  }

  @Override protected void onDestroy() {
    video.stopPlayback();
    super.onDestroy();
  }

  private int dp(int value) { return GalleryUi.dp(this, value); }
}
