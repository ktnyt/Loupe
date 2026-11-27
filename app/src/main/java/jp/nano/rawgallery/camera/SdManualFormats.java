package jp.nano.rawgallery.camera;

import android.content.Context;
import android.net.Uri;

/** Manual format choices belong to the exact removable source tree and destination pairing. */
public final class SdManualFormats {
  private SdManualFormats() {}

  public static String saved(Context context, Uri tree, String destination) {
    String key = key(tree, destination);
    if (key == null) return null;
    String value =
        context.getSharedPreferences("camera_import", Context.MODE_PRIVATE).getString(key, null);
    return valid(value) ? value : null;
  }

  /** Called after an explicitly confirmed import passes service admission. */
  public static void remember(Context context, Uri tree, String destination, String kinds) {
    String key = key(tree, destination);
    if (key != null && valid(kinds)) {
      context
          .getSharedPreferences("camera_import", Context.MODE_PRIVATE)
          .edit()
          .putString(key, kinds)
          .apply();
    }
  }

  private static boolean valid(String kinds) {
    return "JPEG".equals(kinds) || "RAW".equals(kinds) || "JPEG,RAW".equals(kinds);
  }

  private static String key(Uri tree, String destination) {
    if (destination == null
        || destination.isBlank()
        || SdSourceRegistrations.volumeUuid(tree) == null
        || !CameraDocumentSource.isImportTree(tree, true)) return null;
    return "sd_manual_formats:" + Uri.encode(tree.toString()) + ":" + Uri.encode(destination);
  }
}
