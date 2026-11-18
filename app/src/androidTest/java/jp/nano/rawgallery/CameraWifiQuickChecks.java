package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import jp.nano.rawgallery.camera.ImportProgress;
import jp.nano.rawgallery.camera.CameraImportGate;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Instance-scoped metadata fixtures; never writes registration, preferences, or radio state. */
final class CameraWifiQuickChecks {
    private static volatile CameraImportActivity.WifiProfileLoader fixture;
    static void activityCreated(Activity activity) {
        if (activity instanceof CameraImportActivity && fixture != null)
            ((CameraImportActivity) activity).wifiProfileLoader = fixture;
    }
    static String run(Instrumentation t) throws Exception {
        require(Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish"), "emulator only");
        var progress = ImportProgress.INSTANCE;
        require(!progress.getActive() && !CameraImportGate.lock.isLocked(), "refuse active operation");
        boolean priorActive = progress.getActive(); // The only ImportProgress field changed by this test.
        AtomicInteger ble = new AtomicInteger(), settings = new AtomicInteger();
        AtomicReference<Intent> captured = new AtomicReference<>();
        var monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (intent.getComponent() == null) return null;
                String name = intent.getComponent().getClassName();
                if (name.equals(CameraBleActivity.class.getName())) { captured.set(new Intent(intent)); ble.incrementAndGet(); }
                else if (name.equals(Ep7WifiActivity.class.getName())) settings.incrementAndGet();
                else return null;
                return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
            }
        };
        CameraImportActivity screen = null;
        try {
            t.addMonitor(monitor);
            fixture = (context, cancel) -> {
                require(Looper.myLooper() != Looper.getMainLooper(), "metadata off main");
                return List.of();
            };
            screen = open(t); await(t, screen, "wifi-import-setup"); capture(t, screen, "unregistered");
            click(t, screen, "wifi-import-settings"); require(settings.get() == 1 && ble.get() == 0, "pencil opens settings only");
            finish(t, screen); screen = null;
            fixture = (context, cancel) -> List.of(new CameraQuickImportSources.Profile("opaque-one", "OM SYSTEM E-P7"));
            screen = open(t); await(t, screen, "wifi-import-profile:opaque-one"); capture(t, screen, "one-camera");
            CameraImportActivity one = screen;
            t.runOnMainSync(() -> { View button = find(one.getWindow().getDecorView(), "wifi-import-profile:opaque-one"); button.performClick(); button.performClick(); });
            require(ble.get() == 1, "double tap dispatched once");
            Intent outgoing = captured.get();
            require(outgoing.getExtras() != null && outgoing.getExtras().keySet().size() == 1
                    && "opaque-one".equals(outgoing.getStringExtra(CameraBleActivity.EXTRA_QUICK_PROFILE_ID)), "opaque ID only");
            var recreated = t.addMonitor(CameraImportActivity.class.getName(), null, false);
            t.runOnMainSync(one::recreate);
            screen = (CameraImportActivity) recreated.waitForActivityWithTimeout(5000); t.removeMonitor(recreated);
            require(screen != null, "recreated"); await(t, screen, "wifi-import-profile:opaque-one");
            require(ble.get() == 1, "recreation does not connect");
            t.runOnMainSync(() -> progress.setActive(true)); SystemClock.sleep(500); t.waitForIdleSync();
            CameraImportActivity busy = screen;
            t.runOnMainSync(() -> {
                require(!find(busy.getWindow().getDecorView(), "wifi-import-profile:opaque-one").isEnabled(), "busy button disabled");
                find(busy.getWindow().getDecorView(), "wifi-import-profile:opaque-one").performClick();
            });
            require(ble.get() == 1, "busy does not dispatch"); t.runOnMainSync(() -> progress.setActive(priorActive));
            finish(t, screen); screen = null;
            fixture = (context, cancel) -> List.of(new CameraQuickImportSources.Profile("opaque-one", "OM SYSTEM E-P7"),
                    new CameraQuickImportSources.Profile("opaque-two", "OLYMPUS 長いカメラ名のテスト"));
            screen = open(t); await(t, screen, "wifi-import-profile:opaque-two"); capture(t, screen, "multiple-cameras");
            CameraImportActivity multiple = screen;
            t.runOnMainSync(() -> {
                View first = find(multiple.getWindow().getDecorView(), "wifi-import-profile:opaque-one");
                View second = find(multiple.getWindow().getDecorView(), "wifi-import-profile:opaque-two");
                float density = multiple.getResources().getDisplayMetrics().density;
                require(second.getTop() - first.getBottom() >= Math.round(8 * density), "distinct camera targets");
                require(first.getHeight() >= Math.round(48 * density) && second.getHeight() >= Math.round(48 * density), "camera touch heights");
            });
            require(ble.get() == 1, "no automatic selection"); click(t, screen, "wifi-import-profile:opaque-two");
            require(ble.get() == 2 && "opaque-two".equals(captured.get().getStringExtra(CameraBleActivity.EXTRA_QUICK_PROFILE_ID)), "direct second camera");
            finish(t, screen); screen = null;
            fixture = (context, cancel) -> { throw new IOException("fixture"); };
            screen = open(t); await(t, screen, "wifi-import-retry"); capture(t, screen, "read-error");
            fixture = (context, cancel) -> List.of();
            CameraImportActivity retry = screen; t.runOnMainSync(() -> retry.wifiProfileLoader = fixture);
            click(t, screen, "wifi-import-retry"); await(t, screen, "wifi-import-setup");
            finish(t, screen); screen = null;
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            fixture = (context, cancel) -> {
                entered.countDown();
                try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return List.of(new CameraQuickImportSources.Profile("stale", "stale"));
            };
            screen = open(t); require(entered.await(3, java.util.concurrent.TimeUnit.SECONDS), "loading started");
            capture(t, screen, "loading"); finish(t, screen); screen = null; release.countDown();
            fixture = (context, cancel) -> List.of(); screen = open(t); await(t, screen, "wifi-import-setup");
            SystemClock.sleep(200); require(find(screen.getWindow().getDecorView(), "wifi-import-profile:stale") == null, "stopped load ignored");
            require(ble.get() == 2, "no lifecycle replay");
            t.removeMonitor(monitor);
            checkBackStack(t, screen);

            return "PASS Wi-Fi card 0/1/multiple/loading/error/retry, settings-only pencil, opaque-only profile Intent, double tap, busy, recreation stale-load guards, and real Activities for Wi-Fi settings Back and return to import progress. No radio operations.";
        } finally {
            if (screen != null) finish(t, screen);
            fixture = null; t.removeMonitor(monitor);
            t.runOnMainSync(() -> progress.setActive(priorActive));
        }
    }
    private static void checkBackStack(Instrumentation t, CameraImportActivity original) throws Exception {
        var settingsMonitor = t.addMonitor(Ep7WifiActivity.class.getName(), null, false);
        var bleMonitor = t.addMonitor(CameraBleActivity.class.getName(), null, false);
        Ep7WifiActivity settings = null;
        CameraBleActivity registration = null;
        try {
            click(t, original, "wifi-import-settings");
            settings = (Ep7WifiActivity) settingsMonitor.waitForActivityWithTimeout(5000);
            require(settings != null, "real Wi-Fi settings opened");
            awaitState(t, settings::hasWindowFocus, "Wi-Fi settings focused");
            Ep7WifiChecks.capture(t, settings, "wifi-navigation-settings");
            t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            awaitState(t, original::hasWindowFocus, "Back from Wi-Fi settings returns to the original import screen");

            click(t, original, "wifi-import-settings");
            settings = (Ep7WifiActivity) settingsMonitor.waitForActivityWithTimeout(5000);
            require(settings != null, "reopened Wi-Fi settings");
            Ep7WifiActivity reopened = settings;
            awaitState(t, reopened::hasWindowFocus, "reopened Wi-Fi settings focused");
            t.runOnMainSync(() -> {
                View edit = findText(reopened.getWindow().getDecorView(), "カメラの登録を編集");
                require(edit != null && edit.isEnabled(), "registered camera editor available");
                edit.performClick();
            });
            registration = (CameraBleActivity) bleMonitor.waitForActivityWithTimeout(5000);
            require(registration != null, "registered camera editor opened");
            CameraBleActivity child = registration;
            awaitState(t, child::hasWindowFocus, "registered camera editor focused");
            // Exercise the same progress navigation used after the service starts, without starting a radio operation.
            t.runOnMainSync(() -> child.startActivity(CameraImportActivity.showProgressIntent(child)));
            awaitState(t, original::hasWindowFocus, "progress returns to the original import screen");
            awaitState(t, () -> reopened.isDestroyed() && child.isDestroyed(),
                    "settings and registration removed from the back stack");
            capture(t, original, "navigation-returned");
            click(t, original, "wifi-import-settings");
            settings = (Ep7WifiActivity) settingsMonitor.waitForActivityWithTimeout(5000);
            require(settings != null, "Wi-Fi settings available after progress return");
            Ep7WifiActivity beforeNotification = settings;
            awaitState(t, beforeNotification::hasWindowFocus, "settings focused before opening notification");
            var context = t.getTargetContext();
            int testRequestCode = 140;
            PendingIntent usb = PendingIntent.getActivity(context, testRequestCode, new Intent(context, CameraImportActivity.class),
                    PendingIntent.FLAG_IMMUTABLE);
            PendingIntent differential = PendingIntent.getActivity(context, testRequestCode,
                    CameraImportActivity.showProgressIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            try {
                require(!differential.equals(usb), "differential notification does not reuse the USB launch");
                differential.send();
                awaitState(t, original::hasWindowFocus, "notification returns to the original import screen");
                awaitState(t, beforeNotification::isDestroyed, "notification removes settings above progress");
            } finally {
                differential.cancel();
                usb.cancel();
            }

            t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            awaitState(t, original::isDestroyed, "Back from import exits the import screen");
        } finally {
            if (registration != null) finish(t, registration);
            if (settings != null) finish(t, settings);
            t.removeMonitor(bleMonitor);
            t.removeMonitor(settingsMonitor);
        }
    }

    private static void awaitState(Instrumentation t, java.util.function.BooleanSupplier condition, String label) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        var ready = new java.util.concurrent.atomic.AtomicBoolean();
        do {
            t.runOnMainSync(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError(label);
    }

    private static View findText(View root, String label) {
        if (root instanceof TextView && label.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findText(group.getChildAt(i), label);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static CameraImportActivity open(Instrumentation t) { return (CameraImportActivity) t.startActivitySync(new Intent(t.getTargetContext(), CameraImportActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
    private static void finish(Instrumentation t, Activity a) { t.runOnMainSync(a::finish); t.waitForIdleSync(); }
    private static View find(View v, String tag) { if (tag.equals(v.getTag())) return v; if (v instanceof ViewGroup) for (int i=0;i<((ViewGroup)v).getChildCount();i++) { View found=find(((ViewGroup)v).getChildAt(i),tag); if(found!=null)return found; } return null; }
    private static void await(Instrumentation t, Activity a, String tag) { long end=SystemClock.uptimeMillis()+6000; while(SystemClock.uptimeMillis()<end) { AtomicReference<View> found=new AtomicReference<>(); t.runOnMainSync(()->found.set(find(a.getWindow().getDecorView(),tag))); if(found.get()!=null){t.waitForIdleSync();return;} SystemClock.sleep(30); } throw new AssertionError("missing "+tag); }
    private static void click(Instrumentation t, Activity a, String tag) { t.runOnMainSync(()->{View v=find(a.getWindow().getDecorView(),tag); require(v!=null && v.isEnabled(),"action available"); v.performClick();}); t.waitForIdleSync(); }
    private static void capture(Instrumentation t, Activity a, String name) throws Exception {
        t.runOnMainSync(()->{View v=find(a.getWindow().getDecorView(),"wifi-import-card");v.requestRectangleOnScreen(new android.graphics.Rect(0,0,v.getWidth(),v.getHeight()),true);});
        t.waitForIdleSync(); SystemClock.sleep(200);
        var bitmap=t.getUiAutomation().takeScreenshot(); require(bitmap!=null,"full display capture");
        File dir=new File(t.getTargetContext().getCacheDir(),"wifi-quick-checks");dir.mkdirs();
        try(var out=new FileOutputStream(new File(dir,name+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }
    private static void require(boolean condition,String label){if(!condition)throw new AssertionError(label);}
}
