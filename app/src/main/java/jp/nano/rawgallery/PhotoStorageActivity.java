package jp.nano.rawgallery;

import android.content.Intent;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import jp.nano.rawgallery.camera.CameraImportGate;

/** Shows the selected volume, then separately asks before moving existing files. */
public final class PhotoStorageActivity extends AppCompatActivity {
  private record VolumeChoice(String volume, String label) {}
  private record StorageOptions(String volume, String label, List<VolumeChoice> choices) {
    boolean writable(String name) {
      return choices.stream().anyMatch(choice -> choice.volume().equals(name));
    }
  }

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Runnable refresh = this::render;
  private final StorageManager.StorageVolumeCallback volumeChanges = new StorageManager.StorageVolumeCallback() {
    @Override public void onStateChanged(StorageVolume volume) { if (visible) loadOverview(); }
  };
  private LinearLayout content;
  private TextView destination, storageSummary, status;
  private MaterialButton choose, inventory, move, stop, abandon;
  private LinearProgressIndicator progress;
  private boolean visible, planning, selecting, journalReady;
  private StorageOptions options;
  private Future<?> overviewLoad;
  private volatile int overviewRequest;
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
    root.addView(LibraryUi.toolbar(this, "写真・動画の保存先",
        LibraryUi.icon(this, R.drawable.ic_close, "写真・動画の保存先を閉じる", this::finish)));
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
    storageSummary = label();
    storageSummary.setTextColor(GalleryUi.MUTED);
    inventory = button("保存場所と移動対象外の理由を見る", () ->
        startActivity(new Intent(this, PhotoStorageInventoryActivity.class)));
    MaterialUi.outlined(inventory);
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
    abandon = button("移動を終了", this::confirmAbandon);
    setContentView(root);
  }

  @Override public void onResume() {
    super.onResume();
    visible = true;
    getSystemService(StorageManager.class).registerStorageVolumeCallback(getMainExecutor(), volumeChanges);
    loadOverview();
  }

  @Override public void onPause() {
    visible = false;
    ++overviewRequest;
    getSystemService(StorageManager.class).unregisterStorageVolumeCallback(volumeChanges);
    handler.removeCallbacks(refresh);
    if (overviewLoad != null) overviewLoad.cancel(false);
    super.onPause();
  }

  @Override public void onDestroy() {
    worker.shutdownNow();
    super.onDestroy();
  }

  private void loadOverview() {
    if (!visible || isFinishing() || isDestroyed()) return;
    if (overviewLoad != null) overviewLoad.cancel(false);
    int request = ++overviewRequest;
    PhotoMoveService.State moveState = PhotoMoveService.state();
    lastState = moveState;
    journalReady = false;
    display(storageSummary, moveState.running() ? "保存場所は移動の終了後に確認できます。" : "保存場所を確認しています。");
    overviewLoad = PhotoStorageInventoryActivity.READS.submit(() -> {
      try {
        StorageOptions current = storageOptions();
        PhotoMoveStore.Batch batch = null;
        if (!moveState.running()) {
          try (PhotoMoveStore store = new PhotoMoveStore(this)) {
            batch = store.batch();
          }
        }
        PhotoMoveStore.Batch pending = batch;
        updateOverview(request, moveState, () -> {
          options = current;
          unfinished = pending;
          journalReady = true;
        });
        if (moveState.running() || request != overviewRequest) return;
        try (PhotoMove mover = new PhotoMove(this)) {
          String summary = PhotoStorageInventoryActivity.summary(mover.inventory(current.volume()));
          updateOverview(request, moveState, () -> display(storageSummary, summary));
        } catch (PhotoMove.Failure | PhotoStorage.Unavailable unavailable) {
          updateOverview(request, moveState, () -> display(storageSummary, unavailable.getMessage()));
        } catch (IOException | RuntimeException unavailable) {
          updateOverview(request, moveState, () -> display(storageSummary, "保存場所を確認できませんでした。"));
        }
      } catch (RuntimeException unavailable) {
        updateOverview(request, moveState, () -> display(storageSummary, "保存先の状態を読み取れませんでした。"));
      }
    });
    render();
  }

  private void updateOverview(int request, PhotoMoveService.State moveState, Runnable update) {
    runOnUiThread(() -> {
      PhotoMoveService.State current = PhotoMoveService.state();
      if (request != overviewRequest || !visible || isFinishing() || isDestroyed()
          || (current != moveState && !(current.running() && moveState.running()))) return;
      update.run();
      render();
    });
  }

  private void render() {
    if (!visible || isFinishing() || isDestroyed()) return;
    PhotoMoveService.State state = PhotoMoveService.state();
    boolean running = state.running();
    boolean refreshOverview = lastState != state && (!running || !lastState.running());
    lastState = state;
    if (refreshOverview) {
      loadOverview();
      return;
    }
    boolean writable = options != null && options.writable(options.volume());
    display(destination, options == null ? "保存先を確認しています。"
        : options.label() + (writable ? "" : "（書き込み不可）"));
    boolean ready = !running && !planning && !selecting && !CameraImportGate.lock.isLocked() && journalReady;
    move.setText(unfinished == null ? "取り込み済みの写真を移動" : "移動を再開");
    move.setEnabled(ready && (unfinished != null || writable));
    choose.setEnabled(ready);
    inventory.setEnabled(!planning);
    stop.setVisibility(running ? View.VISIBLE : View.GONE);
    abandon.setVisibility(!running && unfinished != null ? View.VISIBLE : View.GONE);
    abandon.setEnabled(ready);
    if (!running) stop.setEnabled(true);
    PhotoMoveStore.Batch batch = running ? state.batch() : unfinished;
    progress.setVisibility(running && batch != null ? View.VISIBLE : View.GONE);
    if (running && batch != null) {
      progress.setMax(batch.total());
      progress.setProgressCompat(batch.done(), false);
    }
    String message = screenMessage != null ? screenMessage : state.message();
    if (batch != null) {
      String action = running || options == null || options.writable(batch.volume())
          ? "" : " 移動先を接続して再開してください。";
      message = (message == null ? "" : message + " ") + batch.done() + " / " + batch.total()
          + "枚" + PhotoMoveService.skipped(batch) + action;
    }
    if (message == null && options != null && !writable) message = "カードを接続するか、保存先を変更してください。";
    if (message == null && options != null && options.choices().size() <= 1)
      message = "書き込めるSDカードが見つかりません。";
    display(status, message == null ? "" : message);
    status.setVisibility(status.length() == 0 ? View.GONE : View.VISIBLE);
    handler.removeCallbacks(refresh);
    handler.postDelayed(refresh, running ? 400 : 1000);
  }

  private StorageOptions storageOptions() {
    String selected = PhotoStorage.selected(this);
    List<VolumeChoice> choices = new ArrayList<>();
    for (StorageVolume volume : PhotoStorage.volumes(this)) {
      String name = volume.getMediaStoreVolumeName();
      if (PhotoStorage.directoryIfWritable(this, name) != null)
        choices.add(new VolumeChoice(name, PhotoStorage.label(this, name)));
    }
    return new StorageOptions(selected, PhotoStorage.label(this, selected), choices);
  }

  private void choose() {
    List<VolumeChoice> choices = options.choices();
    if (choices.isEmpty()) {
      screenMessage = "書き込める保存先が見つかりません。";
      render();
      return;
    }
    var selected = new int[]{-1};
    for (int i = 0; i < choices.size(); i++) {
      if (choices.get(i).volume().equals(options.volume())) selected[0] = i;
    }
    String[] labels = choices.stream().map(VolumeChoice::label).toArray(String[]::new);
    new MaterialAlertDialogBuilder(this).setTitle("写真・動画の保存先")
        .setSingleChoiceItems(labels, selected[0], (dialog, which) -> selected[0] = which)
        .setNegativeButton("キャンセル", null)
        .setPositiveButton("設定", (dialog, which) -> {
          if (selected[0] < 0) return;
          select(choices.get(selected[0]).volume());
        }).show();
  }

  private void select(String volume) {
    selecting = true;
    render();
    worker.execute(() -> {
      String message = null;
      var lock = CameraImportGate.lock;
      if (!lock.tryLock()) message = "取り込みが終わってから保存先を変更してください。";
      else {
        try {
          PhotoStorage.requireWritable(this, volume);
          if (!PhotoStorage.select(this, volume)) throw new IllegalStateException();
        } catch (PhotoStorage.Unavailable unavailable) {
          message = unavailable.getMessage();
        } catch (RuntimeException unavailable) {
          message = "保存先を変更できませんでした。";
        } finally {
          lock.unlock();
        }
      }
      String result = message;
      runOnUiThread(() -> {
        selecting = false;
        screenMessage = result;
        if (visible && !isFinishing() && !isDestroyed()) loadOverview();
      });
    });
  }

  private void confirmAbandon() {
    new MaterialAlertDialogBuilder(this).setTitle("移動を終了しますか？")
        .setMessage("完了した写真は移動先に残ります。未完了の写真は、それぞれ現在の場所に残ります。")
        .setNegativeButton("キャンセル", null)
        .setPositiveButton("移動を終了", (dialog, which) -> {
          screenMessage = null;
          PhotoMoveService.abandon(this);
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
    StorageOptions target = options;
    worker.execute(() -> {
      var lock = CameraImportGate.lock;
      if (!lock.tryLock()) {
        runOnUiThread(() -> showPlan(null, "取り込みが終わってからもう一度お試しください。", target.label()));
        return;
      }
      try (PhotoMove mover = new PhotoMove(this)) {
        PhotoMove.Plan proposal = mover.plan(target.volume());
        runOnUiThread(() -> showPlan(proposal, null, target.label()));
      } catch (PhotoStorage.Unavailable unavailable) {
        runOnUiThread(() -> showPlan(null, unavailable.getMessage(), target.label()));
      } catch (Exception unavailable) {
        runOnUiThread(() -> showPlan(null, "取り込み済みの写真を確認できません。", target.label()));
      } finally {
        lock.unlock();
      }
    });
  }

  private void showPlan(PhotoMove.Plan proposal, String message, String targetLabel) {
    if (isFinishing() || isDestroyed()) return;
    planning = false;
    screenMessage = message;
    if (proposal != null) {
      int count = proposal.items().size();
      String skipped = proposal.skipped() == 0 ? "" : "\n\n移動対象外 " + proposal.skipped()
          + "件。保存場所の一覧で理由を確認できます。";
      if (count == 0) {
        screenMessage = "移動できる取り込み済みの写真はありません。" + skipped;
      } else {
        long bytes = proposal.items().stream().mapToLong(PhotoMoveStore.Item::bytes).sum();
        new MaterialAlertDialogBuilder(this)
            .setTitle("取り込み済みの写真を移動しますか？")
            .setMessage(targetLabel + "に" + count + "枚（"
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
    button.setSingleLine(false);
    button.setOnClickListener(view -> action.run());
    content.addView(button, new LinearLayout.LayoutParams(-1, -2));
    return button;
  }

  private int dp(int value) { return GalleryUi.dp(this, value); }
}
