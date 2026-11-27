package jp.nano.rawgallery;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;

/** Hands prepared photo copies to Android after the caller has checked the current selection. */
final class ShareHandoff {
  enum Outcome {
    LAUNCHED,
    SELECTION_CHANGED,
    UNSUPPORTED,
    NO_APP
  }

  private ShareHandoff() {}

  static Outcome launch(
      Activity activity,
      ArrayList<Uri> uris,
      String action,
      String type,
      int selectionCount,
      ShareTargets.Target target,
      String historySession,
      BooleanSupplier validSelection,
      Runnable selectionChanged) {
    Intent send =
        new Intent(action)
            .setType(type)
            .putExtra(Intent.EXTRA_TITLE, selectionCount + "枚の写真")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    if (uris.size() == 1) send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
    else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
    ClipData clip = ClipData.newRawUri("写真", uris.get(0));
    for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
    send.setClipData(clip);
    if (target != null) {
      ComponentName component = ComponentName.unflattenFromString(target.component);
      boolean supported = false;
      for (ResolveInfo candidate :
          activity.getPackageManager().queryIntentActivities(new Intent(action).setType(type), 0)) {
        if (new ComponentName(candidate.activityInfo.packageName, candidate.activityInfo.name)
            .equals(component)) {
          supported = true;
          break;
        }
      }
      if (!supported) {
        ShareHistoryReceiver.cancelLater(activity.getApplicationContext(), historySession);
        return Outcome.UNSUPPORTED;
      }
      send.setComponent(component);
    }
    PendingIntent callback = null;
    try {
      Intent outgoing = send;
      if (target == null) {
        if (historySession != null)
          callback = ShareHistoryReceiver.callback(activity, historySession);
        outgoing =
            callback == null
                ? Intent.createChooser(send, "写真を共有")
                : Intent.createChooser(send, "写真を共有", callback.getIntentSender());
      }
      if (!validSelection.getAsBoolean()) {
        if (callback != null) callback.cancel();
        ShareHistoryReceiver.cancelLater(activity.getApplicationContext(), historySession);
        // The caller's selection feedback stays within this exception boundary.
        selectionChanged.run();
        return Outcome.SELECTION_CHANGED;
      }
      activity.startActivity(outgoing);
      if (target != null)
        ShareHistoryReceiver.recordLater(
            activity.getApplicationContext(), historySession, send.getComponent());
      else if (historySession == null)
        ShareHistoryReceiver.failed(activity.getApplicationContext());
    } catch (RuntimeException failure) {
      if (callback != null) callback.cancel();
      ShareHistoryReceiver.cancelLater(activity.getApplicationContext(), historySession);
      if (!(failure instanceof ActivityNotFoundException)
          && !(failure instanceof SecurityException)) throw failure;
      return Outcome.NO_APP;
    }
    return Outcome.LAUNCHED;
  }
}
