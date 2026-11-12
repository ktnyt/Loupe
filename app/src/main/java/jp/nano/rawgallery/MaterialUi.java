package jp.nano.rawgallery;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.widget.Button;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

/** Native Material 3 button states. Never replace a MaterialButton's managed background. */
final class MaterialUi {
  private MaterialUi() {}

  static MaterialButton button(Context context) {
    MaterialButton button = new MaterialButton(context);
    button.setAllCaps(false);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    int target = Math.round(48 * context.getResources().getDisplayMetrics().density);
    button.setMinHeight(target);
    button.setMinimumHeight(target);
    button.setInsetTop(0);
    button.setInsetBottom(0);
    text(button);
    return button;
  }

  private static int color(Button button, int attr) {
    return MaterialColors.getColor(button, attr);
  }

  private static ColorStateList enabled(int active, int disabled) {
    return new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}},
        new int[] {disabled, active});
  }

  private static void style(Button button, boolean filled, boolean outlined) {
    MaterialButton material = (MaterialButton) button;
    int primary = color(button, androidx.appcompat.R.attr.colorPrimary);
    int onSurface = color(button, com.google.android.material.R.attr.colorOnSurface);
    int surface = color(button, com.google.android.material.R.attr.colorSurface);
    int foreground = filled ? color(button, com.google.android.material.R.attr.colorOnPrimary) : primary;
    ColorStateList ink = enabled(foreground, MaterialColors.layer(surface, onSurface, .38f));
    material.setTextColor(ink);
    material.setIconTint(ink);
    material.setBackgroundTintList(enabled(filled ? primary : Color.TRANSPARENT,
        filled ? MaterialColors.layer(surface, onSurface, .12f) : Color.TRANSPARENT));
    material.setStrokeWidth(outlined ? Math.round(button.getResources().getDisplayMetrics().density) : 0);
    material.setStrokeColor(enabled(color(button, com.google.android.material.R.attr.colorOutline),
        MaterialColors.layer(surface, onSurface, .12f)));
    material.setRippleColor(ColorStateList.valueOf(MaterialColors.layer(Color.TRANSPARENT, foreground, .12f)));
  }

  static void text(Button button) { style(button, false, false); }
  static void filled(Button button) { style(button, true, false); }
  static void outlined(Button button) { style(button, false, true); }

  /** Selection is committed by the caller, so a failed action never toggles the visual state. */
  static void choice(Button button, boolean selected) {
    MaterialButton material = (MaterialButton) button;
    material.setCheckable(true);
    material.setToggleCheckedStateOnClick(false);
    material.setChecked(selected);
    material.setSelected(selected);
    if (selected) filled(button); else outlined(button);
  }
}
