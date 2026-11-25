package jp.nano.rawgallery;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.R;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

final class DiagnosticControls {
  // The user's operations finish in order even if DashboardActivity is recreated.
  private static final Executor WORKER = Executors.newSingleThreadExecutor();
  // Displayed only in this app process. Never store destination names in diagnostic records.
  private static String lastDestination;
  private static String lastFailure;
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
    DiagnosticLog.Recorder displayedRecorder = DiagnosticLog.currentRecorder();
    boolean recording = displayedRecorder != null;
    String message = (recording ? "処理の記録中です。" : "処理の記録は停止中です。")
        + "\n処理時間とクラッシュの概要をZIPに保存できます。";
    if (lastFailure != null) message += "\n\n" + lastFailure;
    if (lastDestination != null) message += "\n\n直近の保存先\n" + lastDestination;
    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity).setTitle("診断記録");
    Context context = builder.getContext();
    LinearLayout panel = new LinearLayout(context);
    panel.setOrientation(LinearLayout.VERTICAL);

    TextView body = new TextView(context);
    body.setTextAppearance(R.style.TextAppearance_Material3_BodyLarge);
    body.setTextColor(MaterialColors.getColor(body, R.attr.colorOnSurfaceVariant));
    body.setText(message);
    int gutter = dp(context, 24);
    body.setPadding(gutter, dp(context, 16), gutter, dp(context, 12));

    NestedScrollView scroll = new NestedScrollView(context);
    scroll.addView(body, new NestedScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
    // The body can shrink and scroll, leaving the full height for the actions.
    panel.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1));

    LinearLayout actions = new LinearLayout(context) {
      @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int inlineWidth = getPaddingLeft() + getPaddingRight();
        int natural = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        for (int i = 0; i < getChildCount(); i++) {
          View child = getChildAt(i);
          child.measure(natural, natural);
          inlineWidth += child.getMeasuredWidth();
        }
        setOrientation(inlineWidth > MeasureSpec.getSize(widthSpec) ? VERTICAL : HORIZONTAL);
        super.onMeasure(widthSpec, heightSpec);
      }
    };
    int inset = dp(context, 16);
    actions.setPadding(inset, 0, inset, dp(context, 12));
    actions.setGravity(Gravity.END);
    panel.addView(actions, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

    AlertDialog dialog = builder.setView(panel).create();
    addAction(actions, recording ? "記録を止める" : "記録を始める", () -> {
      dialog.dismiss();
      changeRecording(displayedRecorder);
    });
    addAction(actions, "閉じる", dialog::dismiss);
    addAction(actions, "ZIPを保存", () -> {
      dialog.dismiss();
      chooseLocation();
    });
    dialog.show();
  }

  private static void addAction(LinearLayout actions, String label, Runnable action) {
    var button = MaterialUi.button(actions.getContext());
    button.setText(label);
    button.setOnClickListener(view -> action.run());
    actions.addView(button, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
  }

  private static int dp(Context context, int value) {
    return Math.round(context.getResources().getDisplayMetrics().density * value);
  }

  private void changeRecording(DiagnosticLog.Recorder displayedRecorder) {
    Context app = activity.getApplicationContext();
    WORKER.execute(() -> {
      boolean enabled = displayedRecorder == null;
      boolean saved = enabled ? DiagnosticLog.startRecording(app)
          : DiagnosticLog.stopRecording(app, displayedRecorder);
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
        exportFailed(export, "診断記録を読み込めませんでした");
        return;
      }
      if (archive.length == 0) {
        exportFailed(export, "保存できる診断記録がありません");
      } else if (selectFolder) {
        activity.runOnUiThread(() -> openFolderPicker(export, archive));
      } else {
        save(export, app, null, archive);
      }
    });
  }

  private void openFolderPicker(ExportState export, byte[] archive) {
    if (activity.isDestroyed() || activity.isFinishing()) {
      exportFailed(export, "保存先を選び直してください");
      return;
    }
    export.pending = archive;
    try { picker.launch(DiagnosticDestination.folderPicker()); }
    catch (ActivityNotFoundException error) {
      export.pending = null;
      exportFailed(export, "保存先の選択画面を開けませんでした");
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
      exportFailed(export, "診断記録を保存し直してください");
      return;
    }
    Context app = activity.getApplicationContext();
    WORKER.execute(() -> save(export, app, uri, selected));
  }

  private void save(ExportState export, Context app, Uri folder, byte[] archive) {
    try {
      String location = folder == null ? DiagnosticDestination.downloads(app, archive)
          : DiagnosticDestination.folder(app, folder, archive);
      activity.runOnUiThread(() -> {
        lastDestination = location;
        lastFailure = null;
        export.exporting = false;
        feedback("診断ZIPを保存しました");
      });
    } catch (DiagnosticDestination.CleanupFailed error) {
      exportFailed(export, "保存できませんでした。保存先に一時ファイルが残った可能性があります");
    } catch (IOException | RuntimeException error) {
      exportFailed(export, "診断記録を保存できませんでした");
    }
  }

  private void exportFailed(ExportState export, String message) {
    activity.runOnUiThread(() -> {
      lastFailure = message;
      export.exporting = false;
      feedback(message);
    });
  }

  private void feedback(String message) {
    Context app = activity.getApplicationContext();
    activity.runOnUiThread(() -> Toast.makeText(app, message, Toast.LENGTH_SHORT).show());
  }
}
