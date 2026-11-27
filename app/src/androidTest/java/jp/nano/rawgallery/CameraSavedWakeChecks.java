package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import jp.nano.rawgallery.camera.CameraCredentialStore;

/** Uses the ordinary UI and its encrypted credential lookup; no passcode leaves the device. */
final class CameraSavedWakeChecks {
  static String status(Instrumentation instrumentation) throws Exception {
    CameraCredentialStore store = new CameraCredentialStore(instrumentation.getTargetContext());
    List<String> names = store.names();
    int wifiComplete = 0;
    for (String name : names) {
      try (CameraCredentialStore.Registration profile =
          store.registration(CameraCredentialStore.profileId(name))) {
        if (profile != null && profile.hasWifi()) wifiComplete++;
      }
      char[] code = store.passcode(name);
      try {
        if (code == null || code.length < 1 || code.length > 12)
          throw new AssertionError("Stored credential unavailable");
      } finally {
        if (code != null) java.util.Arrays.fill(code, '\0');
      }
    }
    return "PASS camera-registration registeredCount="
        + names.size()
        + " decryptable=true wifiCompleteCount="
        + wifiComplete;
  }

  static String run(Instrumentation instrumentation) throws Exception {
    return run(instrumentation, false);
  }

  static String advertisement(Instrumentation instrumentation) throws Exception {
    return run(instrumentation, true);
  }

  private static String run(Instrumentation instrumentation, boolean advertisementOnly)
      throws Exception {
    List<String> names = new CameraCredentialStore(instrumentation.getTargetContext()).names();
    if (names.size() != 1)
      throw new AssertionError("Saved-wake check requires one registered camera");
    String name = names.get(0);
    CameraBleActivity screen =
        (CameraBleActivity)
            instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), CameraBleActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      instrumentation.runOnMainSync(
          () -> {
            screen.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            button(screen.getWindow().getDecorView(), "カメラを探す").performClick();
          });
      long deadline = android.os.SystemClock.elapsedRealtime() + 20000;
      boolean[] selected = {false};
      while (!selected[0] && android.os.SystemClock.elapsedRealtime() < deadline) {
        instrumentation.runOnMainSync(
            () -> {
              List<TextView> matches = new ArrayList<>();
              buttons(screen.getWindow().getDecorView(), name, matches);
              if (matches.size() > 1)
                throw new AssertionError("Ambiguous registered camera advertisement");
              if (matches.size() == 1) {
                matches.get(0).performClick();
                selected[0] = true;
              }
            });
        if (!selected[0]) Thread.sleep(100);
      }
      if (!selected[0]) throw new AssertionError("Registered camera was not discovered");
      long[] advertisement = {-1, -1};
      instrumentation.runOnMainSync(
          () -> {
            try {
              java.lang.reflect.Field helperField =
                  CameraBleActivity.class.getDeclaredField("helper");
              helperField.setAccessible(true);
              Object helper = helperField.get(screen);
              java.lang.reflect.Field deviceField =
                  CameraBleActivity.class.getDeclaredField("selected");
              deviceField.setAccessible(true);
              Object device = deviceField.get(screen);
              java.lang.reflect.Field seenField = helper.getClass().getDeclaredField("seen");
              seenField.setAccessible(true);
              Object seen = ((java.util.Map<?, ?>) seenField.get(helper)).get(device);
              if (seen == null) throw new AssertionError("Fresh advertisement unavailable");
              java.lang.reflect.Field flags = seen.getClass().getDeclaredField("flags");
              flags.setAccessible(true);
              java.lang.reflect.Field time = seen.getClass().getDeclaredField("time");
              time.setAccessible(true);
              advertisement[0] = flags.getInt(seen);
              advertisement[1] = android.os.SystemClock.elapsedRealtime() - time.getLong(seen);
              if (advertisement[0] < 0 || advertisement[0] > 255 || advertisement[1] < 0)
                throw new AssertionError("Advertisement metadata unavailable");
            } catch (ReflectiveOperationException error) {
              throw new AssertionError("Advertisement metadata unavailable");
            }
          });
      String advertisementInfo =
          " flags="
              + advertisement[0]
              + " asicOn="
              + ((advertisement[0] & 1) != 0)
              + " advertisementAgeMs="
              + advertisement[1];
      if (advertisementOnly) return "PASS saved-camera-advertisement" + advertisementInfo;
      instrumentation.runOnMainSync(
          () -> button(screen.getWindow().getDecorView(), "選んだカメラの起動を試す").performClick());
      String[] text = {""};
      deadline = android.os.SystemClock.elapsedRealtime() + 100000;
      do {
        instrumentation.runOnMainSync(
            () ->
                text[0] =
                    ((TextView) screen.getWindow().getDecorView().findViewWithTag("ble-status"))
                        .getText()
                        .toString());
        if (!text[0].equals("カメラの登録を確認しています…") && !text[0].equals("カメラに接続しています…")) break;
        Thread.sleep(100);
      } while (android.os.SystemClock.elapsedRealtime() < deadline);
      android.content.SharedPreferences diagnostics =
          screen.getSharedPreferences("camera_ble_diagnostics", 0);
      boolean accepted = text[0].startsWith("カメラが操作を受け付けました。");
      return (accepted ? "PASS" : "FAIL")
          + " saved-camera-wake accepted="
          + accepted
          + " outcome="
          + diagnostics.getString("outcome", "NONE")
          + " stage="
          + diagnostics.getString("stage", "NONE")
          + " reason="
          + diagnostics.getString("reason", "NONE")
          + " cameraStatus="
          + diagnostics.getInt("camera_status", -1)
          + " wakeSent="
          + diagnostics.getBoolean("wake_sent", false)
          + " warmConnected="
          + diagnostics.getBoolean("warm_connected", false)
          + " warmDisconnected="
          + diagnostics.getBoolean("warm_disconnected", false)
          + " freshAsicOn="
          + diagnostics.getBoolean("fresh_asic_on", false)
          + " connectionCount="
          + diagnostics.getInt("connection_count", -1)
          + " gattStatus="
          + diagnostics.getInt("gatt_status", -1)
          + " elapsedMs="
          + diagnostics.getLong("elapsed_ms", -1)
          + advertisementInfo;
    } finally {
      instrumentation.runOnMainSync(
          () -> {
            screen.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            screen.finish();
          });
    }
  }

  private static TextView button(View root, String text) {
    List<TextView> matches = new ArrayList<>();
    buttons(root, text, matches);
    if (matches.size() != 1) throw new AssertionError("Expected one UI action");
    return matches.get(0);
  }

  private static void buttons(View root, String text, List<TextView> results) {
    if (root instanceof android.widget.Button
        && text.contentEquals(((TextView) root).getText())
        && root.isEnabled()) results.add((TextView) root);
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
        buttons(((ViewGroup) root).getChildAt(i), text, results);
  }
}
