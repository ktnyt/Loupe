package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import jp.nano.rawgallery.camera.CameraCredentialStore;
import jp.nano.rawgallery.camera.CameraQrCredentials;

/**
 * Emulator-only registered-credential screen fixture. Never operates Bluetooth or a real camera.
 */
public final class CameraQrUiChecks {
  private static final String FIXTURE_NAME = "BLE-TEST";
  private static final String FIXTURE_QR = "OIS2,3,TVJ1CRDC,-+*%$ZYX,UKR1CRDC,-+*%$Z";

  public static String run(Instrumentation t) throws Exception {
    if (!Build.HARDWARE.equals("ranchu") && !Build.HARDWARE.equals("goldfish"))
      throw new AssertionError(
          "synthetic credential fixture is restricted to the dedicated emulator");

    CameraCredentialStore store = new CameraCredentialStore(t.getTargetContext());
    TagDetailChecks.require(
        store.names().isEmpty(), "fixture requires an empty default credential store");
    CameraBleActivity activity = null;
    boolean saved = false;
    try {
      try (CameraQrCredentials credentials = CameraQrCredentials.parse(FIXTURE_QR)) {
        TagDetailChecks.require(
            FIXTURE_NAME.equals(credentials.bleName), "synthetic QR has expected fixture name");
        saved = true; // Initial empty-store assertion makes this exact-name cleanup fixture-owned.
        store.save(credentials);
      }
      TagDetailChecks.require(
          store.names().size() == 1 && store.names().contains(FIXTURE_NAME),
          "only synthetic registration is present");

      activity =
          (CameraBleActivity)
              t.startActivitySync(
                  new Intent(t.getTargetContext(), CameraBleActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      final CameraBleActivity screen = activity;
      awaitRegisteredUi(t, screen);
      TagDetailChecks.ui(
          t,
          () -> {
            View root = screen.getWindow().getDecorView();
            TagDetailChecks.require(
                (screen.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE)
                    != 0,
                "BLE credential screen is secure before fixture capture");
            TextView paired = findText(root, FIXTURE_NAME + "に接続して取り込む");
            TagDetailChecks.require(
                paired != null
                    && paired.isEnabled()
                    && paired.getHeight() >= GalleryUi.dp(screen, 48),
                "registered connection action is available and touch-sized");
            TagDetailChecks.require(
                findText(root, "12345678") == null, "Wi-Fi passphrase is not displayed");
            TextView forget = findText(root, FIXTURE_NAME + "の登録を忘れる");
            TagDetailChecks.require(forget != null, "fixture forget action is displayed");
            TagDetailChecks.require(
                forget.getHeight() >= GalleryUi.dp(screen, 48), "forget action touch height");
            EditText passcode = findEditText(root);
            TagDetailChecks.require(
                passcode != null && passcode.length() == 0, "manual passcode field starts empty");
            TagDetailChecks.require(
                !passcode.isSaveEnabled(), "manual passcode is excluded from saved view state");
            TextView wake = findText(root, "選んだカメラの起動を試す");
            TagDetailChecks.require(
                wake != null && !wake.isEnabled(),
                "wake/save action stays disabled without a selected camera");
            TagDetailChecks.require(
                wake.getHeight() >= GalleryUi.dp(screen, 48), "wake action touch height");
            checkButtonTouchTargets(root, screen);

            ScrollView scroll = findScroll(root);
            TagDetailChecks.require(scroll != null, "BLE screen has a scroll container");
            Rect safe = new Rect();
            root.getWindowVisibleDisplayFrame(safe);
            int[] pos = new int[2];
            scroll.getLocationOnScreen(pos);
            TagDetailChecks.require(
                pos[1] >= safe.top && pos[1] + scroll.getHeight() <= safe.bottom,
                "BLE scroll container stays within safe display bounds");
            // The fixture contains only a synthetic label and an empty passcode field.
            screen.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
          });

      Ep7WifiChecks.capture(t, activity, "ble-registered");
      TagDetailChecks.ui(
          t, () -> findScroll(screen.getWindow().getDecorView()).fullScroll(View.FOCUS_DOWN));
      t.waitForIdleSync();
      Ep7WifiChecks.capture(t, activity, "ble-registered-bottom");
      return "PASS synthetic registered BLE UI, secure-window check, empty passcode, disabled wake, and safe scroll bounds.";
    } finally {
      if (activity != null) {
        CameraBleActivity done = activity;
        TagDetailChecks.ui(t, done::finish);
      }
      if (saved) {
        // The initial empty-store assertion guarantees this exact name belongs to this fixture.
        store.remove(FIXTURE_NAME);
        TagDetailChecks.require(
            store.names().isEmpty(), "synthetic credential cleanup restored the empty store");
      }
    }
  }

  private static void awaitRegisteredUi(Instrumentation t, CameraBleActivity activity)
      throws Exception {
    long deadline = SystemClock.uptimeMillis() + 10_000;
    boolean[] ready = {false};
    do {
      TagDetailChecks.ui(
          t,
          () -> {
            View root = activity.getWindow().getDecorView();
            ready[0] =
                activity.hasWindowFocus()
                    && findText(root, FIXTURE_NAME) != null
                    && findText(root, FIXTURE_NAME + "の登録を忘れる") != null
                    && findText(root, FIXTURE_NAME + "の登録を忘れる").isLaidOut();
          });
      if (ready[0]) return;
      Thread.sleep(50);
    } while (SystemClock.uptimeMillis() < deadline);
    throw new AssertionError("bounded wait for synthetic BLE registration UI expired");
  }

  private static TextView findText(View root, String expected) {
    if (root instanceof TextView && expected.contentEquals(((TextView) root).getText()))
      return (TextView) root;
    if (root instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) root;
      for (int i = 0; i < group.getChildCount(); i++) {
        TextView found = findText(group.getChildAt(i), expected);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static EditText findEditText(View root) {
    if (root instanceof EditText) return (EditText) root;
    if (root instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) root;
      for (int i = 0; i < group.getChildCount(); i++) {
        EditText found = findEditText(group.getChildAt(i));
        if (found != null) return found;
      }
    }
    return null;
  }

  private static ScrollView findScroll(View root) {
    if (root instanceof ScrollView) return (ScrollView) root;
    if (root instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) root;
      for (int i = 0; i < group.getChildCount(); i++) {
        ScrollView found = findScroll(group.getChildAt(i));
        if (found != null) return found;
      }
    }
    return null;
  }

  private static void checkButtonTouchTargets(View root, CameraBleActivity activity) {
    if (root instanceof Button)
      TagDetailChecks.require(
          root.getHeight() >= GalleryUi.dp(activity, 48),
          "button touch height: " + ((Button) root).getText());
    if (root instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) root;
      for (int i = 0; i < group.getChildCount(); i++)
        checkButtonTouchTargets(group.getChildAt(i), activity);
    }
  }
}
