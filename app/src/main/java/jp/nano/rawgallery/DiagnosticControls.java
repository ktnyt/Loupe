package jp.nano.rawgallery;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.IOException;
import java.util.concurrent.ExecutorService;

final class DiagnosticControls {
  private final AppCompatActivity activity;
  private final ExecutorService worker;
  private final ActivityResultLauncher<Intent> picker;
  private byte[] pending;
  private boolean exporting;

  DiagnosticControls(AppCompatActivity activity, ExecutorService worker) {
    this.activity = activity;
    this.worker = worker;
    picker = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
        this::onDocument);
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
    worker.execute(() -> {
      boolean saved = DiagnosticLog.setRecording(activity, enabled);
      feedback(saved ? (enabled ? "診断の記録を開始しました" : "診断の記録を停止しました")
          : "診断の設定を保存できませんでした");
    });
  }

  private void chooseLocation() {
    if (exporting) return;
    exporting = true;
    worker.execute(() -> {
      try {
        byte[] archive = DiagnosticExport.capture(activity);
        if (archive.length == 0) {
          exportFeedback("保存できる診断記録がありません");
          return;
        }
        activity.runOnUiThread(() -> {
          if (activity.isDestroyed() || activity.isFinishing()) return;
          pending = archive;
          try { picker.launch(DiagnosticExport.saveIntent()); }
          catch (ActivityNotFoundException error) {
            pending = null;
            exportFeedback("保存先の選択画面を開けませんでした");
          }
        });
      } catch (IOException | SecurityException error) {
        exportFeedback("診断記録を読み込めませんでした");
      }
    });
  }

  private void onDocument(ActivityResult result) {
    byte[] selected = pending;
    pending = null;
    Uri uri = result.getResultCode() == Activity.RESULT_OK && result.getData() != null
        ? result.getData().getData() : null;
    if (uri == null) {
      exporting = false;
      return;
    }
    exporting = true;
    worker.execute(() -> {
      try {
        byte[] archive = selected == null ? DiagnosticExport.capture(activity) : selected;
        if (archive.length == 0) {
          exportFeedback("保存できる診断記録がありません");
          return;
        }
        DiagnosticExport.save(activity, uri, archive);
        exportFeedback("診断記録を保存しました");
      } catch (IOException | SecurityException error) {
        exportFeedback("診断記録を保存できませんでした");
      }
    });
  }

  private void exportFeedback(String message) {
    activity.runOnUiThread(() -> {
      exporting = false;
      feedback(message);
    });
  }

  private void feedback(String message) {
    activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_SHORT).show());
  }
}
