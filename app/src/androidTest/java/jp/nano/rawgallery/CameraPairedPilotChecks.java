package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import androidx.appcompat.view.WindowCallbackWrapper;
import jp.nano.rawgallery.camera.CameraImportGate;
import jp.nano.rawgallery.camera.ImportProgress;
import android.widget.TextView;
import java.util.List;
import jp.nano.rawgallery.camera.CameraCredentialStore;
import jp.nano.rawgallery.camera.CameraPairedSession;

/** Explicit four-file physical pilot. No full-library import and no credentials in output. */
final class CameraPairedPilotChecks {
    static String run(Instrumentation instrumentation) throws Exception {
        android.content.Context context = instrumentation.getTargetContext();
        android.content.SharedPreferences prefs = context.getSharedPreferences("camera_import", 0);
        if (prefs.getBoolean("wifi_auto", false) || prefs.getBoolean("sd_auto", false))
            return "FAIL paired-pilot stage=PREFLIGHT reason=AUTO_ENABLED";
        if (ImportProgress.INSTANCE.getActive()) return "FAIL paired-pilot stage=PREFLIGHT reason=IMPORT_ACTIVE";
        android.app.ActivityManager manager = context.getSystemService(android.app.ActivityManager.class);
        for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(100))
            if (service.service.getClassName().equals(jp.nano.rawgallery.camera.CameraDifferentialService.class.getName()))
                return "FAIL paired-pilot stage=PREFLIGHT reason=SERVICE_RUNNING";
        if (!CameraImportGate.lock.tryLock()) return "FAIL paired-pilot stage=PREFLIGHT reason=GATE_BUSY";
        try { return runOwned(instrumentation); }
        finally { CameraImportGate.lock.unlock(); }
    }
    private static String runOwned(Instrumentation instrumentation) throws Exception {
        CameraCredentialStore store = new CameraCredentialStore(instrumentation.getTargetContext());
        List<String> names = store.names();
        if (names.size() != 1) return "FAIL paired-pilot stage=REGISTRATION reason=EXPECTED_ONE_PROFILE";
        String id = CameraCredentialStore.profileId(names.get(0));
        try (CameraCredentialStore.Registration profile = store.registration(id)) {
            if (profile == null || !profile.hasWifi()) return "FAIL paired-pilot stage=REGISTRATION reason=WIFI_INCOMPLETE";
        }
        CameraBleActivity activity = (CameraBleActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), CameraBleActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(() -> {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            View root = activity.getWindow().getDecorView();
            root.getViewTreeObserver().addOnGlobalLayoutListener(() -> disableActions(root));
            disableActions(root);
            activity.getWindow().setCallback(new WindowCallbackWrapper(activity.getWindow().getCallback()) {
                @Override public boolean dispatchTouchEvent(MotionEvent event) { return true; }
            });
        });
        long started = android.os.SystemClock.elapsedRealtime();
        try (CameraPairedSession session = CameraPairedSession.connect(instrumentation.getTargetContext(), id,
                () -> activity.isFinishing(), message -> instrumentation.runOnMainSync(() -> {
                    TextView status = activity.getWindow().getDecorView().findViewWithTag("ble-status");
                    if (status != null) status.setText(message);
                }))) {
            long connected = android.os.SystemClock.elapsedRealtime() - started;
            String result = CameraImportPilotChecks.runWifi(instrumentation, session);
            return result + " pairedConnectionMs=" + connected + " exactNetwork=true";
        } catch (Exception failure) {
            return "FAIL paired-pilot stage=CONNECT_OR_IMPORT reason=" + failure.getClass().getSimpleName();
        } finally {
            instrumentation.runOnMainSync(() -> { activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); activity.finish(); });
        }
    }
    private static void disableActions(View view) {
        if (view.isClickable() || view instanceof android.widget.Button || view instanceof android.widget.EditText) view.setEnabled(false);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) disableActions(((ViewGroup)view).getChildAt(i));
    }
    static String guardChecks(Instrumentation instrumentation) throws Exception {
        if (!android.os.Build.HARDWARE.equals("ranchu") && !android.os.Build.HARDWARE.equals("goldfish"))
            throw new AssertionError("Guard fixtures require the dedicated emulator");
        android.content.SharedPreferences prefs = instrumentation.getTargetContext().getSharedPreferences("camera_import", 0);
        boolean existed = prefs.contains("wifi_auto"), previous = prefs.getBoolean("wifi_auto", false);
        try {
            prefs.edit().putBoolean("wifi_auto", true).commit();
            if (!run(instrumentation).contains("reason=AUTO_ENABLED")) throw new AssertionError("Auto import did not stop pilot before connection");
        } finally { android.content.SharedPreferences.Editor edit = prefs.edit(); if (existed) edit.putBoolean("wifi_auto", previous); else edit.remove("wifi_auto"); edit.commit(); }
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        Thread usb = new Thread(() -> { CameraImportGate.lock.lock(); try { held.countDown(); release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } finally { CameraImportGate.lock.unlock(); } });
        usb.start();
        try {
            if (!held.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("USB gate fixture failed");
            if (!run(instrumentation).contains("reason=GATE_BUSY")) throw new AssertionError("Busy gate did not stop pilot before connection");
        } finally { release.countDown(); usb.join(2000); }
        return "PASS paired-pilot-guards autoRejected=true busyGateRejected=true noCameraConnection=true";
    }
}
