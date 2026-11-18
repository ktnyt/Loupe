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
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.json.JSONObject;

final class E2Panel {
  private static final int PICK = 701, CREATE = 702;
  private final E2SyncActivity activity;
  private final android.widget.ScrollView scroll;
  private final LinearLayout card, list;
  private final EditText bucket, access, secret;
  private final TextView status, autoStatus, catalogStatus, cleanupStatus;
  private final Button connect, save, cancel, autoToggle, catalogLoad, catalogSave, cleanupToggle;
  private final ExecutorService worker = new DiagnosticExecutor("E2Panel.worker", 1);
  private final E2Photos photos;
  private String activeBucket, pickerBucket;
  private Uri pendingPick;
  private Export export;
  private File verified;
  private volatile Thread operation;
  private volatile boolean cancelled;
  private boolean busy, connecting;
  private final List<JSONObject> savedRows = new java.util.ArrayList<>();
  private int savedPage;
  private static final int PAGE_SIZE = 25;

  E2Panel(E2SyncActivity activity, android.widget.ScrollView scroll, LinearLayout root, Bundle state) {
    this.activity = activity;
    this.scroll = scroll;
    photos = new E2Photos(activity);
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
    save = button(card, "原本を1つ選んで保存", 52, this::pick);
    TextView autoTitle = text("自動バックアップ", 16);
    autoTitle.setAccessibilityHeading(true);
    add(card, autoTitle);
    add(card, text("従量制でない回線でRAW・JPEG・動画の原本を保存します。", 14));
    autoStatus = text("", 14);
    add(card, autoStatus);
    autoToggle = button(card, "開始", 48, this::toggleAuto);
    cleanupStatus = text("", 14);
    cleanupStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    add(card, cleanupStatus);
    cleanupToggle = button(card, "端末原本の整理を有効にする", 48, this::toggleCleanup);
    button(card, "処理の状況で見る", 48, () ->
        activity.startActivity(new Intent(activity, DashboardActivity.class)));
    add(card, text("保存した原本", 16));
    button(card, "保存した原本を更新", 48, () -> run("一覧を読み込んでいます", this::refresh));
    catalogLoad = button(card, "クラウドから復元一覧を取得", 48, () -> catalog(false));
    catalogSave = button(card, "復元一覧をクラウドに保存", 48, () -> catalog(true));
    catalogStatus = text("", 14);
    catalogStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    catalogStatus.setVisibility(View.GONE);
    add(card, catalogStatus);
    list = column();
    add(card, list);
    setBusy(false);
    if (state != null) {
      pickerBucket = state.getString("e2-picker-bucket");
      String directory = state.getString("e2-verified-directory", "");
      if (directory.matches("e2-export-[0-9]+")) {
        File file = new File(new File(activity.getCacheDir(), directory), "original");
        if (file.isFile()) verified = file;
      }
    }
    Object retained = activity.getLastCustomNonConfigurationInstance();
    if (retained instanceof Export) {
      export = (Export) retained;
      export.panel = this;
      setBusy(true);
      message("原本を書き出しています", GalleryUi.TEXT);
      if (export.outcome != null) exportFinished(export, export.outcome);
    } else if (verified != null) {
      setBusy(true);
      message("復元先を選んでください", GalleryUi.TEXT);
    } else if (photos.settings.configured()) run("保存先を読み込んでいます", this::refresh);
    else showSaved(null, Collections.emptyList());
    if (!(retained instanceof Export) && state != null && state.getBoolean("e2-exporting"))
      message("書き出し結果を確認できませんでした", 0xffffb4a8);
  }

  Object retainedState() { return export; }

  void saveState(Bundle state) {
    state.putString("e2-picker-bucket", pickerBucket);
    state.putBoolean("e2-exporting", export != null);
    if (verified != null) state.putString("e2-verified-directory", verified.getParentFile().getName());
  }

  void refreshAuto() {
    boolean enabled = E2SyncJob.enabled(activity);
    JobState state = E2SyncJob.snapshot(activity);
    String reason = E2AutoSync.state(activity).getString("failureReason", "");
    if (enabled && !E2SyncJob.fullMediaConsent(activity))
      reason = "写真・動画のバックアップ範囲の確認が必要です。一時停止してから再開してください";
    if (reason.isEmpty() && E2AutoSync.state(activity).getBoolean(E2AutoSync.CATALOG_PENDING, false))
      reason = "復元一覧の更新を待っています";
    autoStatus.setText(E2SyncJob.describe(enabled, state) + (reason.isEmpty() ? "" : "\n" + reason));
    autoToggle.setText(enabled ? "一時停止" : state.updatedAt == 0 ? "開始" : "再開");
    autoToggle.setEnabled(!busy && (enabled || activeBucket != null));
    connect.setEnabled(!busy && !enabled && !E2SyncJob.running());
    boolean cleaning = E2Cleanup.enabled(activity, activeBucket);
    String cleanupText = cleaning ? E2Cleanup.status(activity) : "";
    if (!cleanupText.contentEquals(cleanupStatus.getText())) cleanupStatus.setText(cleanupText);
    cleanupStatus.setVisibility(cleanupText.isEmpty() ? View.GONE : View.VISIBLE);
    cleanupToggle.setText(cleaning ? "端末原本の整理を停止" : "端末原本の整理を有効にする");
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
          .setMessage("端末から原本を削除するには、Androidで全ファイルへのアクセスを許可してください。")
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
    new GalleryUi.Sheet(activity).setTitle("端末原本の整理を有効にしますか？")
        .setMessage(bucket + " に復元できる状態で保管したJPEGのうち、"
            + retention + "日以上経った原本を端末から削除します。"
            + "小さいサムネイルは端末に残ります。")
        .setNegativeButton("キャンセル", null)
        .setPositiveButton("有効にする", (dialog, which) -> setCleanup(bucket, retention)).show();
  }

  private void setCleanup(String bucket, int retention) {
    try {
      E2Cleanup.setEnabled(activity, bucket, retention);
      message(retention > 0 ? "次の自動バックアップで整理対象を確認します" : "端末原本の整理を停止しました",
          GalleryUi.TEXT);
    } catch (java.io.IOException failed) {
      message("容量整理の設定を保存できません。保存先や権限を確認してください", 0xffffb4a8);
    }
    refreshAuto();
  }

  private void toggleAuto() {
    if (E2SyncJob.enabled(activity)) {
      boolean cleaning = E2Cleanup.enabled(activity, activeBucket);
      setAuto(false);
      if (cleaning && !E2SyncJob.enabled(activity))
        message("端末原本の整理も停止しました。再開後に設定できます", GalleryUi.TEXT);
    } else if (activeBucket != null) {
      if (!E2Settings.hasMediaAccess(activity)) {
        new GalleryUi.Sheet(activity).setTitle("写真と動画の読み取りを許可してください")
            .setMessage("自動バックアップには、端末のすべての写真・動画と原本の読み取り許可が必要です。")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("許可", (dialog, which) -> activity.requestPhotoAccess()).show();
        return;
      }
      new GalleryUi.Sheet(activity).setTitle("自動バックアップを開始しますか？")
          .setMessage("現在と今後追加される端末のRAW・JPEG・動画の原本（1ファイル512MiBまで）をIDrive e2（東京）に保存し、再取得して一致を確認します。\n" + activeBucket)
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
      File file = verified;
      verified = null;
      if (file == null) {
        if (result == Activity.RESULT_OK)
          message("復元を再開できませんでした。原本をもう一度選んでください", 0xffffb4a8);
      } else if (result != Activity.RESULT_OK || uri == null) {
        E2Photos.discard(file);
        message("復元先の選択を中止しました", GalleryUi.TEXT);
        reloadAfterExport();
      } else runExport(file, uri);
    }
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
      post(() -> new GalleryUi.Sheet(activity).setTitle("原本を保存しますか？")
          .setMessage(name + "\nIDrive e2（東京） · " + target
              + "\n保存済みの原本も復元一覧に反映します。")
          .setNegativeButton("キャンセル", null)
          .setPositiveButton("保存", (dialog, which) -> {
            long started = SystemClock.elapsedRealtime();
            run("原本を保存しています", () -> {
              long put = photos.save(uri, name, target);
              boolean catalogSaved;
              try {
                photos.publishCatalog(target);
                catalogSaved = true;
              } catch (Exception publicationFailed) {
                catalogSaved = false;
              }
              refresh();
              boolean recovered = catalogSaved;
              post(() -> {
                message(recovered ? "原本と復元一覧を保存しました"
                    : "原本は保存しました。復元一覧を保存できませんでした。「復元一覧をクラウドに保存」で再試行してください",
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

    void copy(E2Photos photos, File file, Uri destination) {
      boolean ok = false;
      try {
        bytes = file.length();
        photos.export(file, destination);
        ok = true;
      } catch (Exception ignored) {
        // Report through the panel without disclosing the destination.
      } finally {
        E2Photos.discard(file);
      }
      boolean success = ok;
      new Handler(Looper.getMainLooper()).post(() -> {
        outcome = success;
        if (panel != null) panel.exportFinished(this, success);
      });
    }
  }

  private void runExport(File file, Uri uri) {
    Export current = new Export(this);
    export = current;
    setBusy(true);
    message("原本を書き出しています", GalleryUi.TEXT);
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
    message(success ? "原本を復元しました" : "復元先に原本を書き出せませんでした",
        success ? 0xff9fe0b5 : 0xffffb4a8);
    if (success) timing("restore_export", current.started, " bytes=" + current.bytes);
  }

  private void restore(JSONObject record) {
    long started = SystemClock.elapsedRealtime();
    run("原本を確認しています", () -> {
      File file = photos.downloadRecord(record);
      activity.runOnUiThread(() -> {
        if (activity.isDestroyed() || cancelled) {
          E2Photos.discard(file);
          return;
        }
        verified = file;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType(record.optString("mime"))
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            .putExtra(Intent.EXTRA_TITLE, record.optString("name"));
        activity.startActivityForResult(intent, CREATE);
        timing("restore_fetch", started, " bytes=" + file.length());
      });
    });
  }

  private void catalog(boolean save) {
    String bucket = activeBucket;
    if (bucket == null) return;
    if (save) {
      new GalleryUi.Sheet(activity).setTitle("復元一覧を保存しますか？")
          .setMessage(bucket + " に保存します。機種変更後にこの一覧から原本を選べます。")
          .setNegativeButton("キャンセル", null)
          .setPositiveButton("保存", (dialog, which) -> transferCatalog(bucket, true)).show();
    } else transferCatalog(bucket, false);
  }

  private void transferCatalog(String bucket, boolean save) {
    String progress = save ? "復元一覧を保存しています" : "復元一覧を取得しています";
    showCatalogStatus(progress);
    run(progress, () -> {
      try {
        E2RecoveryCatalog.Snapshot result = save
            ? photos.publishCatalog(bucket) : photos.loadCatalog(bucket);
        if (!save && result != null) refresh();
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
          if (!save && result != null) revealFirstPhoto();
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

  private void revealFirstPhoto() {
    View first = list.getChildAt(0);
    if (first == null) return;
    Runnable reveal = () -> {
      if (first.getParent() != list) return;
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
    List<JSONObject> rows = photos.saved(name);
    post(() -> {
      if (!name.equals(activeBucket)) bucket.setText(name);
      showSaved(name, rows);
      if (status.getText().toString().equals("保存先を読み込んでいます"))
        message("接続済み", 0xff9fe0b5);
    });
  }

  private void showSaved(String name, List<JSONObject> rows) {
    if (!java.util.Objects.equals(name, activeBucket)) catalogStatus.setVisibility(View.GONE);
    activeBucket = name;
    savedRows.clear();
    savedRows.addAll(rows);
    savedPage = 0;
    drawSavedPage();
  }

  private void drawSavedPage() {
    list.removeAllViews();
    if (savedRows.isEmpty()) add(list, text("まだありません", 14));
    int start = savedPage * PAGE_SIZE;
    int end = Math.min(start + PAGE_SIZE, savedRows.size());
    for (JSONObject row : savedRows.subList(start, end)) {
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
      item.addView(restore, new LinearLayout.LayoutParams(-2, -2));
      add(list, item);
    }
    if (savedRows.size() > PAGE_SIZE) {
      add(list, text((start + 1) + "〜" + end + " / " + savedRows.size(), 14));
      if (savedPage > 0) button(list, "前の写真", 48, () -> {
        savedPage--;
        drawSavedPage();
      });
      if (end < savedRows.size()) button(list, "次の写真", 48, () -> {
        savedPage++;
        drawSavedPage();
      });
    }
    setBusy(busy);
  }

  private interface Task { void run() throws Exception; }

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
          if (verified == null) {
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
    save.setEnabled(!value && activeBucket != null);
    catalogLoad.setEnabled(!value && activeBucket != null);
    catalogSave.setEnabled(!value && activeBucket != null);
    cancel.setVisibility(value && !connecting && export == null && verified == null ? View.VISIBLE : View.GONE);
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
    if (verified != null && !activity.isChangingConfigurations()) E2Photos.discard(verified);
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
