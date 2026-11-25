package jp.nano.rawgallery;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

final class DiagnosticControls {
  // The user's operations finish in order even if DashboardActivity is recreated.
  private static final Executor WORKER = Executors.newSingleThreadExecutor();
  private final AppCompatActivity activity;
  private final ActivityResultLauncher<Intent> picker;

  DiagnosticControls(AppCompatActivity activity) {
    this.activity = activity;
    picker = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
        this::onFolder);
  }

  public static final class ExportState extends ViewModel {
    private byte[] pending;
    private boolean exporting;

    public ExportState() {}
  }

  private ExportState state() {
    return new ViewModelProvider(activity).get(ExportState.class);
  }

  void show() {
    boolean recording = DiagnosticLog.recording();
    new MaterialAlertDialogBuilder(activity).setTitle("診断記録")
        .setMessage((recording ? "処理の記録中です。" : "処理の記録は停止中です。")
            + "\n処理時間とクラッシュの概要をZIPに保存できます。")
        .setNeutralButton(recording ? "記録を止める" : "記録を始める",
            (dialog, which) -> setRecording(!recording))
        .setPositiveButton("ZIPを保存", (dialog, which) -> chooseLocation())
        .setNegativeButton("閉じる", null)
        .show();
  }

  private void setRecording(boolean enabled) {
    Context app = activity.getApplicationContext();
    WORKER.execute(() -> {
      boolean saved = DiagnosticLog.setRecording(app, enabled);
      feedback(saved ? (enabled ? "診断の記録を開始しました" : "診断の記録を停止しました")
          : (enabled ? "診断の記録を開始できませんでした" : "診断の停止処理を完了できませんでした"));
    });
  }

  private void chooseLocation() {
    if (state().exporting) return;
    new MaterialAlertDialogBuilder(activity).setTitle("診断ZIPの保存先")
        .setItems(new String[] {"ダウンロード", "別のフォルダを選ぶ"},
            (dialog, which) -> prepareArchive(which == 1))
        .setNegativeButton("キャンセル", null)
        .show();
  }

  private void prepareArchive(boolean selectFolder) {
    ExportState export = state();
    if (export.exporting) return;
    export.exporting = true;
    Context app = activity.getApplicationContext();
    WORKER.execute(() -> {
      byte[] archive;
      try { archive = DiagnosticExport.capture(app); }
      catch (IOException | SecurityException error) {
        exportFeedback(export, "診断記録を読み込めませんでした");
        return;
      }
      if (archive.length == 0) {
        exportFeedback(export, "保存できる診断記録がありません");
      } else if (selectFolder) {
        activity.runOnUiThread(() -> openFolderPicker(export, archive));
      } else {
        save(export, app, null, archive);
      }
    });
  }

  private void openFolderPicker(ExportState export, byte[] archive) {
    if (activity.isDestroyed() || activity.isFinishing()) {
      exportFeedback(export, "保存先を選び直してください");
      return;
    }
    export.pending = archive;
    try { picker.launch(DiagnosticDestination.folderPicker()); }
    catch (ActivityNotFoundException error) {
      export.pending = null;
      exportFeedback(export, "保存先の選択画面を開けませんでした");
    }
  }

  private void onFolder(ActivityResult result) {
    ExportState export = state();
    byte[] selected = export.pending;
    export.pending = null;
    Uri uri = result.getResultCode() == Activity.RESULT_OK && result.getData() != null
        ? result.getData().getData() : null;
    if (uri == null) {
      export.exporting = false;
      return;
    }
    if (selected == null) {
      exportFeedback(export, "診断記録を保存し直してください");
      return;
    }
    Context app = activity.getApplicationContext();
    WORKER.execute(() -> save(export, app, uri, selected));
  }

  private void save(ExportState export, Context app, Uri folder, byte[] archive) {
    try {
      String location = folder == null ? DiagnosticDestination.downloads(app, archive)
          : DiagnosticDestination.folder(app, folder, archive);
      exportFeedback(export, "診断ZIPを保存しました\n" + location);
    } catch (DiagnosticDestination.CleanupFailed error) {
      exportFeedback(export, "保存できませんでした。保存先に一時ファイルが残った可能性があります");
    } catch (IOException | RuntimeException error) {
      exportFeedback(export, "診断記録を保存できませんでした");
    }
  }

  private void exportFeedback(ExportState export, String message) {
    activity.runOnUiThread(() -> {
      export.exporting = false;
      feedback(message);
    });
  }

  private void feedback(String message) {
    Context app = activity.getApplicationContext();
    activity.runOnUiThread(() -> Toast.makeText(app, message, Toast.LENGTH_SHORT).show());
  }
}
