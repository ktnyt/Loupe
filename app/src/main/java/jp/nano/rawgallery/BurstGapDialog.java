package jp.nano.rawgallery;

import android.content.Context;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.function.IntPredicate;

final class BurstGapDialog {
  private static final int CUSTOM = 4;
  private static final String CUSTOM_LABEL = "カスタム（ミリ秒）";
  private static final String SAVE_ERROR = "保存できませんでした。もう一度お試しください。";

  private BurstGapDialog() {}

  static void show(Context context, int millis, IntPredicate save) {
    int checked = millis <= 3000 && millis % 1000 == 0 ? millis / 1000 : CUSTOM;
    String custom = checked == CUSTOM ? CUSTOM_LABEL + "\n" + millis + " ミリ秒" : CUSTOM_LABEL;
    new GalleryUi.Sheet(context)
        .setTitle("同じ機種・保存先の撮影間隔")
        .setSingleChoiceItems(
            new String[] {"まとめない", "１秒以内（標準）", "２秒以内", "３秒以内", custom},
            checked,
            (dialog, choice) -> {
              dialog.dismiss();
              if (choice == CUSTOM) showCustom(context, millis, save);
              else if (!save.test(choice * 1000)) {
                new GalleryUi.Sheet(context)
                    .setMessage(SAVE_ERROR)
                    .setPositiveButton("閉じる", null)
                    .show();
              }
            })
        .setNegativeButton("閉じる", null)
        .show();
  }

  static void showCustom(Context context, int millis, IntPredicate save) {
    GalleryUi.Sheet builder = new GalleryUi.Sheet(context);
    Context dialogContext = builder.getContext();
    TextInputLayout field = new TextInputLayout(dialogContext);
    field.setHint("撮影間隔（ミリ秒）");
    TextInputEditText input = new TextInputEditText(field.getContext());
    input.setSingleLine(true);
    // Request a number keyboard while allowing validation of invalid pasted text.
    input.setRawInputType(InputType.TYPE_CLASS_NUMBER);
    input.setText(Integer.toString(millis));
    input.setSelectAllOnFocus(true);
    field.addView(input, new LinearLayout.LayoutParams(-1, -2));
    ScrollView scroll = new ScrollView(dialogContext);
    int inset = GalleryUi.dp(dialogContext, 24);
    scroll.setPadding(inset, 0, inset, 0);
    scroll.addView(field, new ScrollView.LayoutParams(-1, -2));

    AlertDialog dialog = builder.setTitle(CUSTOM_LABEL)
        .setView(scroll)
        .setNegativeButton("キャンセル", null)
        .setPositiveButton("保存", null)
        .create();
    dialog.setCanceledOnTouchOutside(true);
    dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    dialog.show();
    int touchHeight = GalleryUi.dp(dialogContext, 48);
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setMinHeight(touchHeight);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setMinHeight(touchHeight);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      int value = BurstGapSettings.parseCustomMillis(input.getText().toString());
      if (value == 0) {
        field.setError("1〜2147483647の整数を入力してください");
      } else if (!save.test(value)) {
        field.setError(SAVE_ERROR);
      } else {
        dialog.dismiss();
      }
    });
  }
}
