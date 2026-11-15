package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import jp.nano.rawgallery.camera.CameraCredentialStore;
import java.util.ArrayList;
import java.util.List;

/** Uses the ordinary UI and its encrypted credential lookup; no passcode leaves the device. */
final class CameraSavedWakeChecks {
    static String status(Instrumentation instrumentation) throws Exception {
        CameraCredentialStore store = new CameraCredentialStore(instrumentation.getTargetContext());
        List<String> names = store.names();
        for (String name : names) {
            char[] code = store.passcode(name);
            try { if (code == null || code.length < 1 || code.length > 12) throw new AssertionError("Stored credential unavailable"); }
            finally { if (code != null) java.util.Arrays.fill(code, '\0'); }
        }
        return "PASS camera-registration registeredCount=" + names.size() + " decryptable=true";
    }
    static String run(Instrumentation instrumentation) throws Exception {
        List<String> names = new CameraCredentialStore(instrumentation.getTargetContext()).names();
        if (names.size() != 1) throw new AssertionError("Saved-wake check requires one registered camera");
        String name = names.get(0);
        CameraBleActivity screen = (CameraBleActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), CameraBleActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(() -> {
                screen.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                button(screen.getWindow().getDecorView(), "カメラを探す").performClick();
            });
            long deadline = android.os.SystemClock.elapsedRealtime() + 20000;
            boolean[] selected = {false};
            while (!selected[0] && android.os.SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync(() -> {
                    List<TextView> matches = new ArrayList<>();
                    buttons(screen.getWindow().getDecorView(), name, matches);
                    if (matches.size() > 1) throw new AssertionError("Ambiguous registered camera advertisement");
                    if (matches.size() == 1) { matches.get(0).performClick(); selected[0] = true; }
                });
                if (!selected[0]) Thread.sleep(100);
            }
            if (!selected[0]) throw new AssertionError("Registered camera was not discovered");
            instrumentation.runOnMainSync(() -> button(screen.getWindow().getDecorView(), "選んだカメラの起動を試す").performClick());
            String[] text = {""}; deadline = android.os.SystemClock.elapsedRealtime() + 100000;
            do {
                instrumentation.runOnMainSync(() -> text[0] = ((TextView) screen.getWindow().getDecorView().findViewWithTag("ble-status")).getText().toString());
                if (!text[0].equals("カメラの登録を確認しています…") && !text[0].equals("カメラに接続しています…")) break;
                Thread.sleep(100);
            } while (android.os.SystemClock.elapsedRealtime() < deadline);
            android.content.SharedPreferences diagnostics = screen.getSharedPreferences("camera_ble_diagnostics", 0);
            boolean accepted = text[0].startsWith("カメラが操作を受け付けました。");
            return (accepted ? "PASS" : "FAIL") + " saved-camera-wake accepted=" + accepted
                    + " outcome=" + diagnostics.getString("outcome", "NONE")
                    + " stage=" + diagnostics.getString("stage", "NONE")
                    + " reason=" + diagnostics.getString("reason", "NONE")
                    + " cameraStatus=" + diagnostics.getInt("camera_status", -1)
                    + " wakeSent=" + diagnostics.getBoolean("wake_sent", false);
        } finally {
            instrumentation.runOnMainSync(() -> { screen.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); screen.finish(); });
        }
    }
    private static TextView button(View root, String text) {
        List<TextView> matches = new ArrayList<>(); buttons(root, text, matches);
        if (matches.size() != 1) throw new AssertionError("Expected one UI action");
        return matches.get(0);
    }
    private static void buttons(View root, String text, List<TextView> results) {
        if (root instanceof android.widget.Button && text.contentEquals(((TextView) root).getText()) && root.isEnabled()) results.add((TextView) root);
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) buttons(((ViewGroup) root).getChildAt(i), text, results);
    }
}
