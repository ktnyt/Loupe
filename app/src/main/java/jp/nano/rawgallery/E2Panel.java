package jp.nano.rawgallery;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import org.json.JSONObject;

final class E2Panel {
  static final String DASHBOARD_TITLE = "クラウド同期の状況";
  private static final int PICK = 701, CREATE = 702;
  private static final int PAGE_SIZE = 25;
  private static final int SEARCH_DELAY_MS = 150;
  private final E2SyncActivity activity;
  private final android.widget.ScrollView scroll;
  private final LinearLayout card, list;
  private final EditText bucket, access, secret;
  private final TextInputLayout searchBox;
  private final TextInputEditText searchInput;
  private final TextView status, catalogStatus, cleanupStatus, searchCount, transferBucket, uploadCounts;
  private final TransferRow backup, manualUpload, catalogDownload, originalDownload;
  private final Button connect, save, cancel, autoToggle, catalogLoad, catalogSave, cleanupToggle;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = new DiagnosticExecutor("E2Panel.worker", 1);
  private final E2Photos photos;
  private String activeBucket, pickerBucket;
  private Uri pendingPick;
  private Export export;
  private final E2RestorePicker restorePicker;
  private volatile Thread operation;
  private volatile boolean cancelled;
  private boolean busy, connecting, catalogPending;
  private E2RecoveryCatalog.SavedRows savedRows;
  private List<JSONObject> visibleRows = Collections.emptyList();
  private int savedPage;
  private Runnable pendingSearch;
  private volatile int searchGeneration;

  E2Panel(E2SyncActivity activity, android.widget.ScrollView scroll, LinearLayout root, Bundle state) {
    this.activity = activity;
    this.scroll = scroll;
    photos = new E2Photos(activity);
    restorePicker = new E2RestorePicker(activity.getCacheDir(), state);
    LinearLayout dashboard = column();
    dashboard.setPadding(dp(16), dp(16), dp(16), dp(16));
    background(dashboard, 0xff19191b, 16);
    LinearLayout.LayoutParams dashboardSize = new LinearLayout.LayoutParams(-1, -2);
    dashboardSize.setMargins(dp(16), dp(12), dp(16), 0);
    root.addView(dashboard, dashboardSize);
    TextView dashboardTitle = text(DASHBOARD_TITLE, 20);
    dashboardTitle.setTypeface(null, Typeface.BOLD);
    dashboardTitle.setAccessibilityHeading(true);
    dashboard.addView(dashboardTitle);
    transferBucket = text("", 14);
    add(dashboard, transferBucket);
    addHeading(dashboard, "アップロード");
    backup = new TransferRow(dashboard, "自動バックアップ");
    uploadCounts = text("", 14);
    add(backup.container, uploadCounts);
    manualUpload = new TransferRow(dashboard, "手動保存");
    addHeading(dashboard, "ダウンロード");
    catalogDownload = new TransferRow(dashboard, "復元一覧");
    originalDownload = new TransferRow(dashboard, "オリジナル");
    card = column();
    card.setPadding(dp(16), dp(16), dp(16), dp(16));
    background(card, 0xff19191b, 16);
    LinearLayout.LayoutParams cardSize = new LinearLayout.LayoutParams(-1, -2);
    cardSize.setMargins(dp(16), dp(12), dp(16), dp(16));
    root.addView(card, cardSize);
    TextView title = text("IDrive e2（東京）", 18);
    title.setTypeface(null, Typeface.BOLD);
    card.addView(title);
    bucket = field("バケット", false);
    access = field("アクセスキー", true);
    secret = field("シークレットキー", true);
    connect = button(card, "接続して保存", 52, this::connect);
    status = text("", 14);
    status.setPadding(dp(12), dp(10), dp(12), dp(10));
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    background(status, 0xff232327, 12);
    add(card, status);
    cancel = button(card, "中止", 48, () -> {
      cancelled = true;
      Thread thread = operation;
      if (thread != null) thread.interrupt();
    });
    save = button(card, "オリジナルを1つ選んで保存", 52, this::pick);
    TextView autoTitle = text("自動バックアップ", 16);
    autoTitle.setAccessibilityHeading(true);
    add(card, autoTitle);
    add(card, text("従量制でない回線でRAW・JPEG・動画のオリジナルを保存します。", 14));
    autoToggle = button(card, "開始", 48, this::toggleAuto);
    cleanupStatus = text("", 14);
    cleanupStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    add(card, cleanupStatus);
    cleanupToggle = button(card, "端末オリジナルの整理を有効にする", 48, this::toggleCleanup);
    button(card, "処理の状況で見る", 48, () ->
        activity.startActivity(new Intent(activity, DashboardActivity.class)));
    add(card, text("保存したオリジナル", 16));
    button(card, "保存したオリジナルを更新", 48, () -> run("一覧を読み込んでいます", this::refresh));
    catalogLoad = button(card, "クラウドから復元一覧を取得", 48, () -> catalog(false));
    catalogSave = button(card, "復元一覧をクラウドに保存", 48, () -> catalog(true));
    catalogStatus = text("", 14);
    catalogStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    catalogStatus.setVisibility(View.GONE);
    add(card, catalogStatus);
    searchBox = new TextInputLayout(activity);
    searchBox.setHint("ファイル名で検索");
    searchBox.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
    searchBox.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
    searchBox.setVisibility(View.GONE);
    searchInput = new TextInputEditText(searchBox.getContext());
    searchInput.setSingleLine(true);
    searchInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    searchInput.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
    searchInput.setSaveEnabled(false);
    searchInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    searchBox.addView(searchInput, new LinearLayout.LayoutParams(-1, -2));
    searchInput.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence value, int start, int before, int count) {}
      @Override public void afterTextChanged(Editable value) { searchSaved(value.toString()); }
    });
    add(card, searchBox);
    searchCount = text("", 14);
    searchCount.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    searchCount.setVisibility(View.GONE);
    add(card, searchCount);
    list = column();
    add(card, list);
    setBusy(false);
    if (state != null) pickerBucket = state.getString("e2-picker-bucket");
    Object retained = activity.getLastCustomNonConfigurationInstance();
    if (retained instanceof Export) {
      export = (Export) retained;
      export.panel = this;
      setBusy(true);
      message("オリジナルを書き出しています", GalleryUi.TEXT);
      if (export.outcome != null) exportFinished(export, export.outcome);
    } else if (restorePicker.isWaiting()) {
      setBusy(true);
      message("復元先を選んでください", GalleryUi.TEXT);
    } else if (photos.settings.configured()) run("保存先を読み込んでいます", this::refresh);
    else showSaved(null, null);
    if (!(retained instanceof Export) && state != null && state.getBoolean("e2-exporting"))
      message("書き出し結果を確認できませんでした", 0xffffb4a8);
  }

  Object retainedState() { return export; }

  void saveState(Bundle state) {
    state.putString("e2-picker-bucket", pickerBucket);
    state.putBoolean("e2-exporting", export != null);
    restorePicker.saveState(state);
  }

  void refreshAuto() {
    boolean enabled = E2SyncJob.enabled(activity);
    JobState state = E2SyncJob.snapshot(activity);
    String reason = enabled ? E2AutoSync.state(activity).getString("failureReason", "") : "";
    if (enabled && !E2SyncJob.fullMediaConsent(activity))
      reason = "写真・動画のバックアップ範囲の確認が必要です。一時停止してから再開してください";
    if (!enabled && catalogPending && reason.isEmpty())
      reason = "復元一覧の更新が必要な場合があります。「復元一覧をクラウドに保存」で更新できます";
    refreshTransfers(enabled, state, reason);
    autoToggle.setText(enabled ? "一時停止" : backupObserved() ? "再開" : "開始");
    autoToggle.setEnabled(!busy && (enabled || activeBucket != null));
    connect.setEnabled(!busy && !enabled && !E2SyncJob.running());
    boolean cleaning = E2Cleanup.enabled(activity, activeBucket);
    String cleanupText = cleaning ? E2Cleanup.status(activity) : "";
    if (!cleanupText.contentEquals(cleanupStatus.getText())) cleanupStatus.setText(cleanupText);
    cleanupStatus.setVisibility(cleanupText.isEmpty() ? View.GONE : View.VISIBLE);
    cleanupToggle.setText(cleaning ? "端末オリジナルの整理を停止" : "端末オリジナルの整理を有効にする");
    cleanupToggle.setEnabled(!busy && enabled && E2SyncJob.fullMediaConsent(activity)
        && activeBucket != null);
  }

  private void toggleCleanup() {
    String bucket = activeBucket;
    if (bucket == null) return;
    if (E2Cleanup.enabled(activity, bucket)) {
      setCleanup(bucket, 0);
      return;
    }
    if (!android.os.Environment.isExternalStorageManager()) {
      new GalleryUi.Sheet(activity).setTitle("写真を整理する権限")
          .setMessage("端末からオリジナルを削除するには、Androidで全ファイルへのアクセスを許可してください。")
          .setNegativeButton("キャンセル", null)
          .setPositiveButton("Android設定を開く", (dialog, which) -> {
            Intent settings = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + activity.getPackageName()));
            try {
              activity.startActivity(settings);
            } catch (android.content.ActivityNotFoundException ignored) {
              message("Androidの設定から全ファイルへのアクセスを許可してください", 0xffffb4a8);
            }
          }).show();
      return;
    }
    int retention = E2Cleanup.days(activity);
    new GalleryUi.Sheet(activity).setTitle("端末オリジナルの整理を有効にしますか？")
        .setMessage(bucket + " に復元できる状態で保管したJPEGのうち、"
            + retention + "日以上経ったオリジナルを端末から削除します。"
            + "小さいサムネイルは端末に残ります。")
        .setNegativeButton("キャンセル", null)
        .setPositiveButton("有効にする", (dialog, which) -> setCleanup(bucket, retention)).show();
  }

  private void setCleanup(String bucket, int retention) {
    try {
      E2Cleanup.setEnabled(activity, bucket, retention);
      message(retention > 0 ? "次の自動バックアップで整理対象を確認します" : "端末オリジナルの整理を停止しました",
          GalleryUi.TEXT);
    } catch (java.io.IOException failed) {
      message("容量整理の設定を保存できません。保存先や権限を確認してください", 0xffffb4a8);
    }
    refreshAuto();
  }

  private boolean backupObserved() {
    String target = E2Settings.configuredBucket(activity);
    android.content.SharedPreferences preferences = E2AutoSync.state(activity);
    return !target.isEmpty() && (target.equals(preferences.getString("observationBucket", ""))
        || target.equals(preferences.getString("lastCountBucket", "")));
  }

  private void refreshTransfers(boolean enabled, JobState state, String reason) {
    String target = E2Settings.configuredBucket(activity);
    android.content.SharedPreferences preferences = E2AutoSync.state(activity);
    boolean current = enabled && !target.isEmpty()
        && target.equals(preferences.getString("destination", ""));
    boolean publishing = current && state.kind == JobState.Kind.RUNNING
        && preferences.getBoolean("publishingCatalog", false);
    boolean scopedCounts = current && state.countsKnown;
    boolean known = scopedCounts && state.kind != JobState.Kind.UNKNOWN;
    boolean previous = !known && (scopedCounts || (!target.isEmpty()
        && target.equals(preferences.getString("lastCountBucket", ""))));
    int ready = scopedCounts ? state.ready : preferences.getInt("lastReady", 0);
    int total = scopedCounts ? state.total : preferences.getInt("lastTotal", 0);
    int failed = scopedCounts ? state.failed : preferences.getInt("lastFailed", 0);
    String backupState = publishing ? "復元一覧に反映中"
        : !current ? (backupObserved() ? "一時停止中" : "未開始")
        : switch (state.kind) {
          case RUNNING -> !known ? "対象を確認中"
              : ready + failed >= total ? "同期結果を確認中" : "アップロード中";
          case PAUSED -> "中断 · 再開待ち";
          case COMPLETE -> "完了";
          case PARTIAL -> "一部失敗";
          case FAILED -> "失敗";
          case AUTH_REQUIRED -> "設定の確認が必要";
          default -> "待機中";
        };
    updateText(transferBucket, target.isEmpty() ? "IDrive e2 未接続" : "IDrive e2 · " + target);
    backup.render(backupState, current && state.kind == JobState.Kind.RUNNING
        && (!known || publishing || ready + failed >= total), known ? ready : 0,
        known && !publishing ? total : 0);
    String counts = "";
    if (known || previous) {
      counts = (previous ? "前回の計測 · " : "") + "確認済み "
          + number(ready) + " / " + number(total)
          + "件\n残り " + number(Math.max(0, total - ready - failed))
          + "件 · 失敗 " + number(failed) + "件";
    }
    if (known && state.kind == JobState.Kind.RUNNING && !publishing && ready + failed < total) {
      E2AutoSync.Rate rate = E2AutoSync.rate(target);
      if (rate != null) {
        counts += "\n実効速度 " + transferSpeed(rate.bytesPerSecond());
        if (failed == 0 && rate.remainingSeconds() >= 0)
          counts += " · 転送残り " + estimate(rate.remainingSeconds());
      }
    }
    if (!reason.isEmpty()) counts = counts.isEmpty() ? reason : counts + "\n" + reason;
    updateText(uploadCounts, counts);
    uploadCounts.setVisibility(counts.isEmpty() ? View.GONE : View.VISIBLE);

    showTransfer(manualUpload, E2TransferStatus.Kind.MANUAL_UPLOAD, target, "保存中", "保存済み");
    showTransfer(catalogDownload, E2TransferStatus.Kind.CATALOG_DOWNLOAD, target, "取得中", "取得済み");
    showTransfer(originalDownload, E2TransferStatus.Kind.ORIGINAL_DOWNLOAD, target, "取得中", "取得済み");
  }

  private void showTransfer(TransferRow row, E2TransferStatus.Kind kind, String target,
      String running, String completed) {
    E2TransferStatus.Snapshot item = E2TransferStatus.snapshot(kind, target);
    if (item == null) {
      row.render("未実行", false, 0, 0);
      return;
    }
    long total = item.totalBytes();
    long bytes = item.bytes();
    boolean active = item.state() == E2TransferStatus.State.RUNNING;
    boolean verifying = active && total > 0 && bytes == total;
    String label = switch (item.state()) {
      case RUNNING -> item.publishing() ? "復元一覧に反映中" : verifying ? "照合中" : running;
      case COMPLETE -> item.count() == 0 ? "クラウドに一覧なし"
          : item.count() > 0 ? completed + " · " + number(item.count()) + "件" : completed;
      case FAILED -> item.publishing() ? "復元一覧の反映に失敗" : "失敗";
      case CANCELLED -> "中止";
    };
    if (item.originalSaved() && item.state() != E2TransferStatus.State.COMPLETE)
      label = "オリジナル保存済み · " + label;
    if (active && total > 0) {
      label += " · " + android.text.format.Formatter.formatShortFileSize(activity, bytes)
          + " / " + android.text.format.Formatter.formatShortFileSize(activity, total);
    }
    if (active && !verifying && !item.publishing() && total > bytes && item.bytesPerSecond() > 0)
      label += "\n" + transferSpeed(item.bytesPerSecond()) + " · 転送残り "
          + estimate((long) Math.ceil((total - bytes) / item.bytesPerSecond()));
    row.render(label, active && (total <= 0 || verifying), bytes,
        active && !verifying ? total : 0);
  }

  private String transferSpeed(double bytesPerSecond) {
    return android.text.format.Formatter.formatShortFileSize(activity, (long) bytesPerSecond) + "/s";
  }

  private String estimate(long seconds) {
    if (seconds < 60) return "1分未満";
    long minutes = (seconds + 59) / 60;
    if (minutes < 60) return "約" + minutes + "分";
    long remainder = minutes % 60;
    return "約" + minutes / 60 + "時間" + (remainder == 0 ? "" : remainder + "分");
  }

  private String number(int value) {
    return java.text.NumberFormat.getIntegerInstance(
        activity.getResources().getConfiguration().getLocales().get(0)).format(value);
  }

  private void addHeading(LinearLayout parent, String label) {
    TextView heading = text(label, 16);
    heading.setTypeface(null, Typeface.BOLD);
    heading.setAccessibilityHeading(true);
    add(parent, heading);
  }

  private final class TransferRow {
    final LinearLayout container;
    final String name;
    final TextView label;
    final com.google.android.material.progressindicator.LinearProgressIndicator indicator;

    TransferRow(LinearLayout parent, String name) {
      this.name = name;
      container = column();
      add(parent, container);
      label = text("", 14);
      container.addView(label);
      indicator = new com.google.android.material.progressindicator.LinearProgressIndicator(activity);
      indicator.setMax(1000);
      indicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      indicator.setVisibility(View.GONE);
      add(container, indicator);
    }

    void render(String state, boolean indeterminate, long progress, long total) {
      updateText(label, name + " · " + state);
      boolean visible = indeterminate || total > 0;
      if (indicator.isIndeterminate() != indeterminate) {
        indicator.setVisibility(View.GONE);
        indicator.setIndeterminate(indeterminate);
      }
      if (!indeterminate && total > 0) indicator.setProgress((int) (1000.0 * progress / total));
      indicator.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
  }

  private static void updateText(TextView view, String value) {
    if (!value.contentEquals(view.getText())) view.setText(value);
  }

  private void toggleAuto() {
    if (E2SyncJob.enabled(activity)) {
      boolean cleaning = E2Cleanup.enabled(activity, activeBucket);
      setAuto(false);
      if (cleaning && !E2SyncJob.enabled(activity))
        message("端末オリジナルの整理も停止しました。再開後に設定できます", GalleryUi.TEXT);
    } else if (activeBucket != null) {
      if (!E2Settings.hasMediaAccess(activity)) {
        new GalleryUi.Sheet(activity).setTitle("写真と動画の読み取りを許可してください")
            .setMessage("自動バックアップには、端末のすべての写真・動画とオリジナルの読み取り許可が必要です。")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("許可", (dialog, which) -> activity.requestPhotoAccess()).show();
        return;
      }
      new GalleryUi.Sheet(activity).setTitle("自動バックアップを開始しますか？")
          .setMessage("今あるRAW・JPEG・動画と今後追加するオリジナルをIDrive e2（東京）に保存し、読み戻して照合します。一時領域にオリジナルのサイズに応じた空き容量が必要です。"
              + "\n保存済みのオリジナルもクラウドの復元一覧に反映します。\n保存先バケット " + activeBucket)
          .setNegativeButton("キャンセル", null)
          .setPositiveButton("開始", (dialog, which) -> setAuto(true)).show();
    }
  }

  private void setAuto(boolean enabled) {
    try {
      E2SyncJob.setEnabled(activity, enabled, enabled ? activeBucket : null);
    } catch (RuntimeException e) {
      message("自動バックアップを開始できません。保存先や端末の自動削除の設定を確認してください。", 0xffffb4a8);
    }
    refreshAuto();
  }

  private void connect() {
    String name = bucket.getText().toString().trim();
    String id = access.getText().toString().trim();
    String key = secret.getText().toString().trim();
    if (name.isEmpty()) bucket.setError("入力してください");
    if (id.isEmpty()) access.setError("入力してください");
    if (key.isEmpty()) secret.setError("入力してください");
    if (name.isEmpty() || id.isEmpty() || key.isEmpty()) return;
    connecting = true;
    run("接続を確認しています", () -> {
      photos.settings.connect(name, id, key);
      post(() -> {
        access.setText("");
        secret.setText("");
        connect.setText("接続して保存");
        message("接続しました", 0xff9fe0b5);
      });
      refresh();
    });
  }

  private void pick() {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*").putExtra(Intent.EXTRA_LOCAL_ONLY, true);
    pickerBucket = activeBucket;
    activity.startActivityForResult(intent, PICK);
  }

  void result(int request, int result, Intent intent) {
    Uri uri = intent == null ? null : intent.getData();
    if (request == PICK) {
      if (result == Activity.RESULT_OK && uri != null && "content".equals(uri.getScheme())) {
        pendingPick = uri;
        consumePick();
      } else pickerBucket = null;
    } else if (request == CREATE) {
      if (!restorePicker.isWaiting()) {
        if (result == Activity.RESULT_OK) showRestorePickerUnavailable();
      } else if (result != Activity.RESULT_OK || uri == null) {
        restorePicker.cancel();
        message("復元先の選択を中止しました", GalleryUi.TEXT);
        reloadAfterExport();
      } else runExport(restorePicker.take(), uri);
    }
  }

  private void showRestorePickerUnavailable() {
    message("復元を再開できませんでした。オリジナルをもう一度選んでください", 0xffffb4a8);
  }

  private void consumePick() {
    if (busy || pendingPick == null) return;
    Uri uri = pendingPick;
    String target = pickerBucket;
    pendingPick = null;
    pickerBucket = null;
    if (target == null) {
      message("保存先を確認して写真を選び直してください", 0xffffb4a8);
      return;
    }
    run("写真を確認しています", () -> {
      String name = photos.name(uri);
      post(() -> new GalleryUi.Sheet(activity).setTitle("オリジナルを保存しますか？")
          .setMessage(name + "\nIDrive e2（東京） · " + target
              + "\n保存済みのオリジナルもクラウドの復元一覧に反映します。")
          .setNegativeButton("キャンセル", null)
          .setPositiveButton("保存", (dialog, which) -> {
            long started = SystemClock.elapsedRealtime();
            runTransfer("オリジナルを保存しています", E2TransferStatus.Kind.MANUAL_UPLOAD, target,
                -1, transfer -> {
              long put = photos.save(uri, name, target, transfer);
              transfer.publishing(true);
              boolean catalogSaved;
              try {
                photos.publishCatalog(target);
                catalogSaved = true;
              } catch (Exception publicationFailed) {
                catalogSaved = false;
              }
              if (!cancelled) {
                if (catalogSaved) transfer.complete(-1);
                else transfer.fail();
              }
              refresh();
              boolean recovered = catalogSaved;
              post(() -> {
                message(recovered ? "オリジナルと復元一覧を保存しました"
                    : "オリジナルは保存しました。復元一覧を保存できませんでした。「復元一覧をクラウドに保存」で再試行してください",
                    recovered ? 0xff9fe0b5 : 0xffffb4a8);
                timing("save " + (name.toLowerCase(java.util.Locale.ROOT).endsWith(".orf")
                    ? "orf" : "other"), started, " put_ms=" + put);
              });
            });
          }).show());
    });
  }

  private void reloadAfterExport() {
    setBusy(false);
    if (activeBucket == null && photos.settings.configured())
      run("保存先を読み込んでいます", this::refresh);
  }

  private static final class Export {
    E2Panel panel;
    Boolean outcome;
    final long started = SystemClock.elapsedRealtime();
    long bytes;

    Export(E2Panel panel) { this.panel = panel; }

    void copy(E2Photos photos, E2Photos.PreparedRestore file, Uri destination) {
      boolean ok = false;
      try {
        bytes = file.size();
        photos.export(file, destination);
        ok = true;
      } catch (Exception ignored) {
        // Report through the panel without disclosing the destination.
      } finally {
        E2Photos.discard(file.file());
      }
      boolean success = ok;
      new Handler(Looper.getMainLooper()).post(() -> {
        outcome = success;
        if (panel != null) panel.exportFinished(this, success);
      });
    }
  }

  private void runExport(E2Photos.PreparedRestore file, Uri uri) {
    Export current = new Export(this);
    export = current;
    setBusy(true);
    message("オリジナルを書き出しています", GalleryUi.TEXT);
    E2Photos transfer = photos;
    worker.execute(() -> current.copy(transfer, file, uri));
  }

  private void exportFinished(Export current, boolean success) {
    if (export != current) return;
    current.panel = null;
    export = null;
    setBusy(false);
    if (activeBucket == null && photos.settings.configured()) {
      run("保存先を読み込んでいます", () -> {
        refresh();
        post(() -> showExportResult(current, success));
      });
    } else showExportResult(current, success);
  }

  private void showExportResult(Export current, boolean success) {
    message(success ? "オリジナルを復元しました" : "復元先にオリジナルを書き出せませんでした",
        success ? 0xff9fe0b5 : 0xffffb4a8);
    if (success) timing("restore_export", current.started, " bytes=" + current.bytes);
  }

  private void restore(JSONObject record) {
    long started = SystemClock.elapsedRealtime();
    runTransfer("オリジナルを取得しています", E2TransferStatus.Kind.ORIGINAL_DOWNLOAD,
        record.optString("bucket"), record.optLong("size", -1), transfer -> {
      long size = record.getLong("size");
      String sha = record.getString("sha");
      File file = photos.downloadRecord(record, transfer);
      E2Photos.PreparedRestore prepared;
      try {
        prepared = new E2Photos.PreparedRestore(file, size, sha);
        E2Photos.holdForPicker(file);
        if (!cancelled) transfer.complete(-1);
      } catch (java.io.IOException | IllegalArgumentException failure) {
        E2Photos.discard(file);
        throw failure;
      }
      activity.runOnUiThread(() -> {
        if (activity.isDestroyed() || cancelled) {
          E2Photos.discard(file);
          return;
        }
        if (!restorePicker.open(prepared, record.optString("mime"), record.optString("name"),
            intent -> activity.startActivityForResult(intent, CREATE))) {
          showRestorePickerUnavailable();
          return;
        }
        timing("restore_fetch", started, " bytes=" + file.length());
      });
    });
  }

  private void catalog(boolean save) {
    String bucket = activeBucket;
    if (bucket == null) return;
    if (save) {
      new GalleryUi.Sheet(activity).setTitle("復元一覧を保存しますか？")
          .setMessage(bucket + " に保存します。機種変更後にこの一覧からオリジナルを選べます。")
          .setNegativeButton("キャンセル", null)
          .setPositiveButton("保存", (dialog, which) -> transferCatalog(bucket, true)).show();
    } else transferCatalog(bucket, false);
  }

  private void transferCatalog(String bucket, boolean save) {
    String progress = save ? "復元一覧を保存しています" : "復元一覧を取得しています";
    showCatalogStatus(progress);
    runTransfer(progress, save ? E2TransferStatus.Kind.MANUAL_UPLOAD
        : E2TransferStatus.Kind.CATALOG_DOWNLOAD, bucket, -1, transfer -> {
      try {
        if (save) transfer.publishing(false);
        E2RecoveryCatalog.Snapshot result = save
            ? photos.publishCatalog(bucket) : photos.loadCatalog(bucket);
        if (!cancelled) transfer.complete(save ? -1 : result == null ? 0 : result.records().size());
        if (result != null) refresh();
        post(() -> {
          String outcome = "クラウドに復元一覧がありません";
          if (result != null) {
            String time = java.text.DateFormat.getDateTimeInstance().format(
                new java.util.Date(result.created()));
            outcome = (save ? "復元一覧を保存しました" :
                result.previous() ? "前の復元一覧を読み込みました" : "復元一覧を読み込みました")
                + "\n" + result.records().size() + "枚 · " + time;
          }
          showCatalogStatus(outcome);
          message(outcome, GalleryUi.TEXT);
          if (!save && result != null) revealRecoveryList();
        });
      } catch (Exception failure) {
        post(() -> showCatalogStatus(save ? "復元一覧を保存できませんでした" :
            "復元一覧を取得できませんでした"));
        throw failure;
      }
    });
  }

  private void showCatalogStatus(String label) {
    catalogStatus.setText(label);
    catalogStatus.setVisibility(View.VISIBLE);
  }

  private void revealRecoveryList() {
    View first = searchBox.getVisibility() == View.VISIBLE ? searchBox : list.getChildAt(0);
    if (first == null) return;
    Runnable reveal = () -> {
      if (!first.isAttachedToWindow() || first.getVisibility() != View.VISIBLE) return;
      android.graphics.Rect bounds = new android.graphics.Rect();
      first.getDrawingRect(bounds);
      scroll.offsetDescendantRectToMyCoords(first, bounds);
      scroll.scrollTo(0, Math.max(0, bounds.top - dp(64)));
    };
    if (first.isLaidOut() && !first.isLayoutRequested()) {
      first.post(reveal);
      return;
    }
    first.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
      @Override public void onLayoutChange(View row, int left, int top, int right, int bottom,
          int oldLeft, int oldTop, int oldRight, int oldBottom) {
        row.removeOnLayoutChangeListener(this);
        row.post(reveal);
      }
    });
  }

  private void timing(String phase, long start, String detail) {
    if ((activity.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
      android.util.Log.i("LoupeE2Timing", phase + " ms="
          + (SystemClock.elapsedRealtime() - start) + detail);
  }

  private void refresh() throws Exception {
    String name = photos.settings.configuration().getString("bucket");
    E2RecoveryCatalog.SavedRows rows = photos.saved(name);
    boolean pending = photos.catalogPending(name);
    post(() -> {
      if (!name.equals(activeBucket)) bucket.setText(name);
      catalogPending = pending;
      showSaved(name, rows);
      if (status.getText().toString().equals("保存先を読み込んでいます"))
        message("接続済み", 0xff9fe0b5);
    });
  }

  private void showSaved(String name, E2RecoveryCatalog.SavedRows rows) {
    boolean changedBucket = !Objects.equals(name, activeBucket);
    if (changedBucket) catalogStatus.setVisibility(View.GONE);
    activeBucket = name;
    savedRows = rows;
    boolean available = rows != null && !rows.isEmpty();
    searchBox.setVisibility(available ? View.VISIBLE : View.GONE);
    setBusy(busy);
    if ((changedBucket || !available) && searchInput.length() > 0) searchInput.setText("");
    else searchSaved(searchInput.getText().toString());
  }

  private void searchSaved(String query) {
    int generation = ++searchGeneration;
    if (pendingSearch != null) main.removeCallbacks(pendingSearch);
    pendingSearch = null;
    savedPage = 0;
    E2RecoveryCatalog.SavedRows source = savedRows;
    if (source == null || source.isEmpty() || query.isEmpty()) {
      showSearchResults(source == null ? Collections.emptyList() : source);
      return;
    }
    // Keep previous matches out of the restore flow while their replacement is being computed.
    list.removeAllViews();
    searchCount.setVisibility(View.GONE);
    searchCount.setText("");
    pendingSearch = () -> {
      pendingSearch = null;
      worker.execute(() -> {
        if (generation != searchGeneration) return;
        E2RecoveryCatalog.SavedRows matches = source.matchingFilename(query);
        activity.runOnUiThread(() -> {
          if (!activity.isDestroyed() && generation == searchGeneration) showSearchResults(matches);
        });
      });
    };
    main.postDelayed(pendingSearch, SEARCH_DELAY_MS);
  }

  private void showSearchResults(List<JSONObject> matches) {
    visibleRows = matches;
    boolean available = savedRows != null && !savedRows.isEmpty();
    searchCount.setVisibility(available ? View.VISIBLE : View.GONE);
    if (available) {
      searchCount.setText(matches.isEmpty() ? "一致するファイル名はありません（0件）" : matches.size() + "件");
    }
    drawSavedPage();
  }

  private void drawSavedPage() {
    list.removeAllViews();
    if (savedRows == null || savedRows.isEmpty()) add(list, text("まだありません", 14));
    int start = savedPage * PAGE_SIZE;
    int end = Math.min(start + PAGE_SIZE, visibleRows.size());
    for (JSONObject row : visibleRows.subList(start, end)) {
      LinearLayout item = new LinearLayout(activity);
      item.setGravity(android.view.Gravity.CENTER_VERTICAL);
      item.setMinimumHeight(dp(64));
      LinearLayout detail = column();
      item.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
      detail.addView(text(row.optString("name"), 14));
      String size = android.text.format.Formatter.formatShortFileSize(activity, row.optLong("size"));
      long verifiedAt = row.optLong("verifiedAt");
      String verification = verifiedAt > 0 ? " · "
          + android.text.format.DateFormat.format("yyyy/MM/dd", verifiedAt) : "";
      detail.addView(text(size + verification, 12));
      Button restore = new SyncSettingsUi.ActionButton(activity, "復元", () -> restore(row));
      restore.setContentDescription(row.optString("name") + "を復元");
      restore.setMinWidth(dp(72));
      restore.setMinHeight(dp(48));
      restore.setEnabled(!busy);
      item.addView(restore, new LinearLayout.LayoutParams(-2, -2));
      add(list, item);
    }
    if (visibleRows.size() > PAGE_SIZE) {
      add(list, text((start + 1) + "〜" + end + " / " + visibleRows.size(), 14));
      if (savedPage > 0) button(list, "前の写真", 48, () -> {
        savedPage--;
        drawSavedPage();
      });
      if (end < visibleRows.size()) button(list, "次の写真", 48, () -> {
        savedPage++;
        drawSavedPage();
      });
    }
  }

  private interface Task { void run() throws Exception; }

  private interface ObservedTask { void run(E2TransferStatus transfer) throws Exception; }

  private void runTransfer(String label, E2TransferStatus.Kind kind, String target, long totalBytes,
      ObservedTask task) {
    run(label, () -> {
      try (E2TransferStatus transfer = E2TransferStatus.begin(kind, target, totalBytes)) {
        try {
          task.run(transfer);
        } finally {
          if (cancelled) transfer.cancel();
        }
      }
    });
  }

  private void run(String label, Task task) {
    if (busy) return;
    start(label, task);
  }

  private void start(String label, Task task) {
    cancelled = false;
    setBusy(true);
    message(label, GalleryUi.TEXT);
    worker.execute(() -> {
      operation = Thread.currentThread();
      try {
        if (!cancelled) task.run();
      } catch (Exception e) {
        post(() -> {
          message("処理に失敗しました。設定や通信を確認してもう一度お試しください", 0xffffb4a8);
          if (label.equals("接続を確認しています")) connect.setText("再試行");
        });
      } finally {
        operation = null;
        activity.runOnUiThread(() -> {
          if (activity.isDestroyed()) return;
          connecting = false;
          if (cancelled) message("中止しました", GalleryUi.TEXT);
          if (!restorePicker.isWaiting()) {
            setBusy(false);
            consumePick();
          }
        });
      }
    });
  }

  private void post(Runnable action) {
    activity.runOnUiThread(() -> {
      if (!activity.isDestroyed() && !cancelled) action.run();
    });
  }

  private void setBusy(boolean value) {
    busy = value;
    refreshAuto();
    bucket.setEnabled(!value);
    access.setEnabled(!value);
    secret.setEnabled(!value);
    searchBox.setEnabled(!value);
    save.setEnabled(!value && activeBucket != null);
    catalogLoad.setEnabled(!value && activeBucket != null);
    catalogSave.setEnabled(!value && activeBucket != null);
    cancel.setVisibility(value && !connecting && export == null && !restorePicker.isWaiting() ? View.VISIBLE : View.GONE);
    for (int i = 0; i < list.getChildCount(); i++) {
      if (list.getChildAt(i) instanceof LinearLayout) {
        LinearLayout row = (LinearLayout) list.getChildAt(i);
        row.getChildAt(1).setEnabled(!value);
      }
    }
  }

  boolean confirmBack() {
    if (!busy) return false;
    if (export != null || connecting) return true;
    new GalleryUi.Sheet(activity).setTitle("処理を中止して戻りますか？")
        .setNegativeButton("続ける", null)
        .setPositiveButton("中止して戻る", (dialog, which) -> activity.finish()).show();
    return true;
  }

  void close() {
    cancelled = true;
    searchGeneration++;
    if (pendingSearch != null) main.removeCallbacks(pendingSearch);
    restorePicker.close(activity.isFinishing(), activity.isChangingConfigurations());
    worker.execute(photos::close);
    Thread thread = operation;
    if (export != null) export.panel = null;
    if (thread != null) thread.interrupt();
    worker.shutdown();
  }

  private EditText field(String hint, boolean masked) {
    EditText edit = new EditText(activity);
    edit.setSingleLine(true);
    edit.setHint(hint);
    edit.setContentDescription(hint);
    edit.setTextColor(GalleryUi.TEXT);
    edit.setHintTextColor(0xffb9b9bd);
    edit.setMinHeight(dp(48));
    edit.setPadding(dp(12), 0, dp(12), 0);
    edit.setInputType(InputType.TYPE_CLASS_TEXT | (masked
        ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
    edit.setSaveEnabled(false);
    edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    background(edit, 0xff232327, 12);
    add(card, edit);
    return edit;
  }

  private void message(String label, int color) {
    status.setText(label);
    status.setTextColor(color);
  }

  private Button button(LinearLayout parent, String name, int height, Runnable action) {
    Button button = new SyncSettingsUi.ActionButton(activity, name, action);
    button.setMinHeight(dp(height));
    add(parent, button);
    return button;
  }

  private void add(LinearLayout parent, View child) {
    LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
    size.topMargin = dp(8);
    parent.addView(child, size);
  }

  private TextView text(String label, int size) {
    TextView view = new TextView(activity);
    view.setText(label);
    view.setTextSize(size);
    view.setTextColor(GalleryUi.TEXT);
    return view;
  }

  private LinearLayout column() {
    LinearLayout layout = new LinearLayout(activity);
    layout.setOrientation(LinearLayout.VERTICAL);
    return layout;
  }

  private void background(View view, int color, int radius) {
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(color);
    shape.setCornerRadius(dp(radius));
    view.setBackground(shape);
  }

  private int dp(int value) { return GalleryUi.dp(activity, value); }
}
