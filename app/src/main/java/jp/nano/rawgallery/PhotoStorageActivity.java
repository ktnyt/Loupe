package jp.nano.rawgallery;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.format.Formatter;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jp.nano.rawgallery.camera.CameraImportGate;

/** Shows the selected volume, then separately asks before moving existing files. */
public final class PhotoStorageActivity extends AppCompatActivity {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Runnable refresh = this::render;
  private final StorageManager.StorageVolumeCallback volumeChanges = new StorageManager.StorageVolumeCallback() {
    @Override public void onStateChanged(StorageVolume volume) { render(); }
  };
  private LinearLayout content;
  private TextView destination, status;
  private MaterialButton choose, move, stop;
  private LinearProgressIndicator progress;
  private boolean visible, planning;
  private PhotoMoveStore.Batch unfinished;
  private PhotoMoveService.State lastState;
  private String screenMessage;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setOnApplyWindowInsetsListener((view, insets) -> {
      var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
      return insets;
    });
    root.addView(LibraryUi.toolbar(this, "写真の保存先",
        LibraryUi.icon(this, R.drawable.ic_close, "写真の保存先を閉じる", this::finish)));
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(20), dp(8), dp(20), dp(24));
    scroll.addView(content);
    destination = label();
    choose = button("保存先を変更", this::choose);
    TextView section = label();
    section.setText("取り込み済みの写真");
    section.setTextSize(20);
    section.setAccessibilityHeading(true);
    move = button("取り込み済みの写真を移動", this::plan);
    MaterialUi.filled(move);
    progress = new LinearProgressIndicator(this);
    progress.setContentDescription("写真の移動の進捗");
    content.addView(progress, new LinearLayout.LayoutParams(-1, -2));
    status = label();
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    stop = button("中止", () -> {
      PhotoMoveService.stopMove();
      stop.setEnabled(false);
      display(status, "移動を中断しています。");
    });
    setContentView(root);
  }

  @Override public void onResume() {
    super.onResume();
    visible = true;
    getSystemService(StorageManager.class).registerStorageVolumeCallback(getMainExecutor(), volumeChanges);
    loadJournal();
    render();
  }

  @Override public void onPause() {
    visible = false;
    getSystemService(StorageManager.class).unregisterStorageVolumeCallback(volumeChanges);
    handler.removeCallbacks(refresh);
    super.onPause();
  }

  @Override public void onDestroy() {
    worker.shutdownNow();
    super.onDestroy();
  }

  private void loadJournal() {
    worker.execute(() -> {
      try (PhotoMoveStore store = new PhotoMoveStore(this)) {
        var batch = store.batch();
        runOnUiThread(() -> { unfinished = batch; render(); });
      }
    });
  }

  private void render() {
    if (!visible || isFinishing() || isDestroyed()) return;
    String volume = PhotoStorage.selected(this);
    String label = PhotoStorage.label(this, volume);
    boolean writable = PhotoStorage.directoryIfWritable(this, volume) != null;
    display(destination, label + (writable ? "" : "（書き込み不可）"));
    PhotoMoveService.State state = PhotoMoveService.state();
    boolean running = state.running();
    if (!running && lastState != state && lastState != null && lastState.running()) {
      unfinished = null;
      loadJournal();
    }
    lastState = state;
    move.setText(unfinished == null ? "取り込み済みの写真を移動" : "移動を再開");
    move.setEnabled(!running && !planning && !CameraImportGate.lock.isLocked() && (unfinished != null || writable));
    choose.setEnabled(!running && !planning && !CameraImportGate.lock.isLocked());
    stop.setVisibility(running ? View.VISIBLE : View.GONE);
    if (!running) stop.setEnabled(true);
    PhotoMoveStore.Batch batch = running ? state.batch() : unfinished;
    progress.setVisibility(running && batch != null ? View.VISIBLE : View.GONE);
    if (running && batch != null) {
      progress.setMax(batch.total());
      progress.setProgressCompat(batch.done(), false);
    }
    String message = screenMessage != null ? screenMessage : state.message();
    if (batch != null) {
      String action = running ? "" : " " + PhotoStorage.label(this, batch.volume()) + "に接続して再開できます。";
      message = (message == null ? "" : message + " ") + batch.done() + " / " + batch.total()
          + "枚" + PhotoMoveService.skipped(batch) + action;
    }
    if (message == null && !writable) message = "カードを接続するか、保存先を変更してください。";
    if (message == null && available().size() <= 1) message = "書き込めるSDカードが見つかりません。";
    display(status, message == null ? "" : message);
    status.setVisibility(status.length() == 0 ? View.GONE : View.VISIBLE);
    handler.removeCallbacks(refresh);
    handler.postDelayed(refresh, running ? 400 : 1000);
  }

  private List<String> available() {
    List<String> result = new ArrayList<>();
    for (StorageVolume volume : PhotoStorage.volumes(this)) {
      String name = volume.getMediaStoreVolumeName();
      if (PhotoStorage.directoryIfWritable(this, name) != null) result.add(name);
    }
    return result;
  }

  private void choose() {
    List<String> choices = available();
    if (choices.isEmpty()) {
      screenMessage = "書き込める保存先が見つかりません。";
      render();
      return;
    }
    var selected = new int[]{choices.indexOf(PhotoStorage.selected(this))};
    String[] labels = choices.stream().map(volume -> PhotoStorage.label(this, volume)).toArray(String[]::new);
    new MaterialAlertDialogBuilder(this).setTitle("写真の保存先")
        .setSingleChoiceItems(labels, selected[0], (dialog, which) -> selected[0] = which)
        .setNegativeButton("キャンセル", null)
        .setPositiveButton("設定", (dialog, which) -> {
          if (selected[0] < 0) return;
          var lock = CameraImportGate.lock;
          if (!lock.tryLock()) screenMessage = "取り込みが終わってから保存先を変更してください。";
          else {
            try {
              String volume = choices.get(selected[0]);
              PhotoStorage.requireWritable(this, volume);
              if (!PhotoStorage.select(this, volume)) throw new IllegalStateException();
              screenMessage = null;
            } catch (PhotoStorage.Unavailable unavailable) {
              screenMessage = unavailable.getMessage();
            } catch (RuntimeException unavailable) {
              screenMessage = "保存先を変更できませんでした。";
            } finally {
              lock.unlock();
            }
          }
          render();
        }).show();
  }

  private void plan() {
    screenMessage = null;
    if (unfinished != null) {
      PhotoMoveService.launch(this, null);
      render();
      return;
    }
    planning = true;
    screenMessage = "取り込み済みの写真を確認しています。";
    render();
    String volume = PhotoStorage.selected(this);
    worker.execute(() -> {
      var lock = CameraImportGate.lock;
      if (!lock.tryLock()) {
        runOnUiThread(() -> showPlan(null, "取り込みが終わってからもう一度お試しください。"));
        return;
      }
      try (PhotoMove mover = new PhotoMove(this)) {
        PhotoMove.Plan proposal = mover.plan(volume);
        runOnUiThread(() -> showPlan(proposal, null));
      } catch (PhotoStorage.Unavailable unavailable) {
        runOnUiThread(() -> showPlan(null, unavailable.getMessage()));
      } catch (Exception unavailable) {
        runOnUiThread(() -> showPlan(null, "取り込み済みの写真を確認できません。"));
      } finally {
        lock.unlock();
      }
    });
  }

  private void showPlan(PhotoMove.Plan proposal, String message) {
    if (isFinishing() || isDestroyed()) return;
    planning = false;
    screenMessage = message;
    if (proposal != null) {
      int count = proposal.items().size();
      String skipped = proposal.skipped() == 0 ? "" : "\n\n移動できない写真が" + proposal.skipped()
          + "件あります。元のアプリが所有する写真や、現在読み取れない写真は対象外です。";
      if (count == 0) {
        screenMessage = "移動できる取り込み済みの写真はありません。" + skipped;
      } else {
        long bytes = proposal.items().stream().mapToLong(PhotoMoveStore.Item::bytes).sum();
        new MaterialAlertDialogBuilder(this)
            .setTitle("取り込み済みの写真を移動しますか？")
            .setMessage(PhotoStorage.label(this, proposal.volume()) + "に" + count + "枚（"
                + Formatter.formatFileSize(this, bytes) + "）を移動します。" + skipped)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("移動", (dialog, which) -> {
              PhotoMoveService.launch(this, proposal);
              render();
            }).show();
      }
    }
    render();
  }

  private static void display(TextView view, String text) {
    if (!text.contentEquals(view.getText())) view.setText(text);
  }

  private TextView label() {
    TextView view = new TextView(this);
    view.setTextColor(GalleryUi.TEXT);
    view.setTextSize(16);
    view.setPadding(0, dp(12), 0, dp(12));
    content.addView(view);
    return view;
  }

  private MaterialButton button(String text, Runnable action) {
    MaterialButton button = MaterialUi.button(this);
    button.setText(text);
    button.setMinHeight(dp(48));
    button.setOnClickListener(view -> action.run());
    content.addView(button, new LinearLayout.LayoutParams(-1, -2));
    return button;
  }

  private int dp(int value) { return GalleryUi.dp(this, value); }
}
