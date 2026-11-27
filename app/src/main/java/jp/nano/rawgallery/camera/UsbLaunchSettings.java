package jp.nano.rawgallery.camera;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/** Optional USB launch registration; manual import and transfer preferences are independent. */
public final class UsbLaunchSettings {
  private UsbLaunchSettings() {}

  public static ComponentName component(Context context) {
    return new ComponentName(context.getPackageName(), "jp.nano.rawgallery.camera.UsbAttachEntry");
  }

  public static boolean isEnabled(Context context) {
    // DEFAULT is off in the manifest, including the first attachment after an update.
    return context.getPackageManager().getComponentEnabledSetting(component(context))
        == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
  }

  public static void setEnabled(Context context, boolean enabled) {
    context
        .getPackageManager()
        .setComponentEnabledSetting(
            component(context),
            enabled
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP);
    if (isEnabled(context) != enabled)
      throw new IllegalStateException("USB launch registration not applied");
  }
}
