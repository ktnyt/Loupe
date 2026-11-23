package jp.nano.rawgallery;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import jp.nano.rawgallery.camera.CameraImportGate;

/** Shows the selected volume, then separately asks before moving existing files. */
public final class PhotoStorageActivity extends AppCompatActivity {
  private record VolumeChoice(String volume, String label) {}
  private record StorageOptions(String volume, String label, List<VolumeChoice> choices) {
    boolean writable(String name) {
      return choices.stream().anyMatch(choice -> choice.volume().equals(name));
    }
  }

  private static final class PlanRequest {
    final long started = SystemClock.elapsedRealtimeNanos();
    volatile boolean cancelled;
    volatile PhotoMove.PreflightProgress progress;
    boolean showCancellation;
    private Thread worker;

    synchronized boolean begin() {
      if (cancelled) return false;
      worker = Thread.currentThread();
      return true;
    }

    synchronized void cancel(boolean showCancellation) {
      this.showCancellation = showCancellation;
      cancelled = true;
      if (worker != null) worker.interrupt();
    }

    synchronized void finish() { worker = null; }
  }

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Runnable refresh = this::render;
  private final StorageManager.StorageVolumeCallback volumeChanges = new StorageManager.StorageVolumeCallback() {
    @Override public void onStateChanged(StorageVolume volume) { if (visible) loadOverview(); }
  };
  private LinearLayout content;
  private TextView destination, storageSummary, status, preflightStats, preflightNote;
  private MaterialButton choose, inventory, move, stop, abandon;
  private LinearProgressIndicator progress, preflightBar;
  private boolean visible, selecting, journalReady;
  private StorageOptions options;
  private Future<?> overviewLoad;
  private volatile int overviewRequest;
  private PlanRequest planRequest;
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
    inventory = button("保存場所と移動対象外の理由を見る", this::openInventory);
    MaterialUi.outlined(inventory);
    move = button("取り込み済みの写真を移動", this::plan);
    MaterialUi.filled(move);
    ((LinearLayout.LayoutParams) move.getLayoutParams()).topMargin = dp(8);
    progress = new LinearProgressIndicator(this);
    progress.setContentDescription("写真の移動の進捗");
    content.addView(progress, new LinearLayout.LayoutParams(-1, -2));
    status = label();
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    preflightBar = new LinearProgressIndicator(this);
    preflightBar.setContentDescription("写真の読み取りの進捗");
    preflightBar.setMax(1000);
    preflightBar.setVisibility(View.GONE);
    content.addView(preflightBar, new LinearLayout.LayoutParams(-1, -2));
    preflightStats = label();
    preflightStats.setPadding(0, dp(12), 0, 0);
    preflightStats.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
    preflightNote = label();
    preflightNote.setPadding(0, dp(8), 0, dp(4));
    preflightNote.setText("まだ移動していません。");
    preflightNote.setTextColor(GalleryUi.MUTED);
    stop = button("中止", () -> {
      if (planRequest != null) {
        planRequest.cancel(true);
        render();
        return;
      }
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
    if (planRequest != null) {
      planRequest.cancel(false);
      screenMessage = null;
    }
    super.onPause();
  }

  @Override public void onDestroy() {
    worker.shutdownNow();
    super.onDestroy();
  }

  private void loadOverview() {
    if (!visible || selecting || isFinishing() || isDestroyed()) return;
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
    if (refreshOverview && !selecting) {
      loadOverview();
      return;
    }
    boolean writable = options != null && options.writable(options.volume());
    display(destination, options == null ? "保存先を確認しています。"
        : options.label() + (writable ? "" : "（書き込み不可）"));
    boolean planning = planRequest != null;
    boolean cancelling = planning && planRequest.cancelled;
    boolean ready = !running && !planning && !selecting && !CameraImportGate.lock.isLocked() && journalReady;
    move.setText(unfinished == null ? "取り込み済みの写真を移動" : "移動を再開");
    move.setEnabled(ready && (unfinished != null || writable));
    choose.setEnabled(ready);
    inventory.setEnabled(!planning && !selecting);
    stop.setText(planning ? "確認を中止" : "中止");
    stop.setVisibility(running || planning ? View.VISIBLE : View.GONE);
    abandon.setVisibility(!running && unfinished != null ? View.VISIBLE : View.GONE);
    abandon.setEnabled(ready);
    if (!running) stop.setEnabled(!cancelling);
    PhotoMoveStore.Batch batch = running ? state.batch() : unfinished;
    progress.setVisibility(running && batch != null ? View.VISIBLE : View.GONE);
    if (running && batch != null) {
      progress.setMax(batch.total());
      progress.setProgressCompat(batch.done(), false);
    }
    String message = screenMessage != null ? screenMessage : state.message();
    if (selecting) message = "保存先を変更しています。";
    if (batch != null) {
      String action = running || options == null || options.writable(batch.volume())
          ? "" : " 移動先を接続して再開してください。";
      message = (message == null ? "" : message + " ") + batch.done() + " / " + batch.total()
          + "枚" + PhotoMoveService.skipped(batch) + action;
    }
    if (message == null && options != null && !writable) message = "カードを接続するか、保存先を変更してください。";
    if (message == null && options != null && options.choices().size() <= 1)
      message = "書き込めるSDカードが見つかりません。";
    PhotoMove.PreflightProgress reading = planning && !cancelling ? planRequest.progress : null;
    if (cancelling) message = "写真の確認を中止しています。";
    else if (planning) message = reading == null ? "照合する写真を調べています。" : "バックアップと写真の内容を照合中";
    display(status, message == null ? "" : message);
    status.setVisibility(status.length() == 0 ? View.GONE : View.VISIBLE);
    renderPreflight(planning && !cancelling, reading);
    handler.removeCallbacks(refresh);
    handler.postDelayed(refresh, planning ? 250 : running ? 400 : 1000);
  }

  private void renderPreflight(boolean planning, PhotoMove.PreflightProgress reading) {
    if (planning && preflightBar.isIndeterminate() != (reading == null)) {
      preflightBar.setVisibility(View.GONE);
      preflightBar.setIndeterminate(reading == null);
    }
    if (reading != null) {
      int progress = reading.totalBytes() == 0 ? 0 : (int) (1000d * reading.readBytes() / reading.totalBytes());
      int limit = reading.finished() && reading.rejected() == 0 ? 1000 : 999;
      preflightBar.setProgressCompat(Math.min(limit, progress), false);
      String read = Formatter.formatFileSize(this, reading.readBytes());
      String total = Formatter.formatFileSize(this, reading.totalBytes());
      if (read.equals(total) && reading.readBytes() != reading.totalBytes()) {
        read = reading.readBytes() + " B";
        total = reading.totalBytes() + " B";
      }
      display(preflightStats, reading.completed() + " / " + reading.total() + "枚を確認"
          + "\n一致 " + reading.successful() + "枚・対象外 " + reading.rejected() + "枚"
          + "\n読み取り " + read + " / " + total);
    }
    preflightBar.setVisibility(planning ? View.VISIBLE : View.GONE);
    preflightStats.setVisibility(reading != null ? View.VISIBLE : View.GONE);
    preflightNote.setVisibility(planning ? View.VISIBLE : View.GONE);
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
    ++overviewRequest;
    if (overviewLoad != null) overviewLoad.cancel(false);
    render();
    PhotoStorageInventoryActivity.READS.execute(() -> {
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

  private void openInventory() {
    if (visible && planRequest == null && !selecting && !isFinishing() && !isDestroyed())
      startActivity(new Intent(this, PhotoStorageInventoryActivity.class));
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
    plan(() -> new PhotoMove(this));
  }

  void plan(Supplier<PhotoMove> openMover) {
    if (!visible || planRequest != null || !move.isEnabled() || isFinishing() || isDestroyed()) return;
    screenMessage = null;
    if (unfinished != null) {
      PhotoMoveService.launch(this, null);
      render();
      return;
    }
    PlanRequest request = new PlanRequest();
    planRequest = request;
    render();
    StorageOptions target = options;
    worker.execute(() -> plan(request, target, openMover));
  }

  private void plan(PlanRequest request, StorageOptions target, Supplier<PhotoMove> openMover) {
    PhotoMove.Plan verified = null;
    String message = null;
    try {
      if (!request.begin()) return;
      var lock = CameraImportGate.lock;
      if (!lock.tryLock()) {
        message = "取り込みが終わってからもう一度お試しください。";
        return;
      }
      try (PhotoMove mover = openMover.get()) {
        PhotoMove.Plan proposal = mover.plan(target.volume(), () -> request.cancelled);
        verified = mover.preflight(proposal, () -> request.cancelled, reading -> request.progress = reading);
      } catch (InterruptedIOException cancelled) {
        message = "写真の確認を中止しました。";
      } catch (PhotoStorage.Unavailable | PhotoMove.Failure unavailable) {
        message = unavailable.getMessage();
      } catch (Exception unavailable) {
        message = "取り込み済みの写真を確認できません。";
      } finally {
        lock.unlock();
      }
    } finally {
      request.finish();
      PhotoMove.Plan result = verified;
      String resultMessage = message;
      runOnUiThread(() -> showPlan(request, result, resultMessage, target.label()));
    }
  }

  private void showPlan(PlanRequest request, PhotoMove.Plan proposal, String message, String targetLabel) {
    if (request != planRequest) return;
    planRequest = null;
    if (!visible || isFinishing() || isDestroyed()) return;
    if (request.cancelled) {
      screenMessage = request.showCancellation ? "写真の確認を中止しました。" : null;
      render();
      return;
    }
    screenMessage = message;
    if (proposal != null) {
      int count = proposal.items().size();
      boolean otherExcluded = proposal.skipped() > proposal.archiveRejected();
      String skipped = otherExcluded
          ? "\n\n移動対象外の項目があります。保存場所の一覧で写真と理由を確認できます。" : "";
      if (proposal.archiveRejected() > 0)
        skipped += "\n\n内容を照合できなかった" + proposal.archiveRejected() + "枚は元の場所に残ります。";
      if (count == 0) {
        screenMessage = "移動できる取り込み済みの写真はありません。" + skipped;
      } else {
        long bytes = proposal.items().stream().mapToLong(PhotoMoveStore.Item::bytes).sum();
        var confirmation = new MaterialAlertDialogBuilder(this)
            .setTitle("取り込み済みの写真を移動しますか？")
            .setMessage(targetLabel + "に" + count + "枚（"
                + Formatter.formatFileSize(this, bytes) + "）の移動を開始します。"
                + "\n照合できない写真は元の場所に残ります。" + skipped)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("移動", (dialog, which) -> {
              PhotoMoveService.launch(this, proposal);
              render();
            });
        if (otherExcluded) confirmation.setNeutralButton("理由を見る", (dialog, which) -> openInventory());
        confirmation.show();
      }
    }
    render();
    PhotoMove.PreflightProgress reading = request.progress;
    DiagnosticLog.event("photo_move.confirmation_ready", 0,
        "result", proposal == null ? "failed" : proposal.items().isEmpty() ? "empty" : "confirmation",
        "elapsed_ns", SystemClock.elapsedRealtimeNanos() - request.started,
        "candidates", reading == null ? 0 : reading.total(),
        "candidate_bytes", reading == null ? 0 : reading.totalBytes(),
        "read_bytes", reading == null ? 0 : reading.readBytes());
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
