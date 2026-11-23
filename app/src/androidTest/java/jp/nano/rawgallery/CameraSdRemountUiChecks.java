package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.TextView;

import com.google.android.material.materialswitch.MaterialSwitch;

import jp.nano.rawgallery.camera.CameraAttachActivity;
import jp.nano.rawgallery.camera.CameraCredentialStore;
import jp.nano.rawgallery.camera.CameraImportGate;
import jp.nano.rawgallery.camera.ImportProgress;
import jp.nano.rawgallery.camera.SdCardRemount;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Dedicated-emulator checks with fake Shizuku outcomes and an empty SD/camera setup. */
final class CameraSdRemountUiChecks {
    static final String MODE = "camera-pilot-sd-remount-ui";
    private static volatile RemountFixture fixture;

    static void activityCreated(Activity activity) {
        if (activity instanceof CameraImportActivity screen && fixture != null) {
            screen.sdRemountAccess = fixture;
            screen.wifiProfileLoader = (context, cancellation) -> List.of();
        }
    }

    static String run(Instrumentation test) throws Exception {
        require(Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish"), "dedicated emulator only");
        var context = test.getTargetContext();
        var prefs = context.getSharedPreferences("camera_import", 0);
        var progress = ImportProgress.INSTANCE;
        require(!progress.getActive() && !CameraImportGate.lock.isLocked(), "no active operations");
        require(!prefs.contains("sd_tree_uri") && new CameraCredentialStore(context).names().isEmpty()
                && CameraImportActivity.connected(context) == null, "empty SD and camera setup");
        boolean priorSettingExists = prefs.contains(CameraImportActivity.SD_REMOUNT_ON_ENTRY);
        boolean priorSetting = prefs.getBoolean(CameraImportActivity.SD_REMOUNT_ON_ENTRY, false);
        var priorCompletion = progress.getCompletion();
        var fake = new RemountFixture();
        fixture = fake;
        prefs.edit().remove(CameraImportActivity.SD_REMOUNT_ON_ENTRY).commit();
        CameraImportActivity screen = null;
        AtomicInteger pickers = new AtomicInteger();
        var pickerMonitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (!Intent.ACTION_OPEN_DOCUMENT_TREE.equals(intent.getAction())) return null;
                pickers.incrementAndGet();
                return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
            }
        };
        test.addMonitor(pickerMonitor);
        try {
            fake.status = SdCardRemount.Status.READY;
            screen = open(test, entry(test));
            require(fake.starts == 0 && fake.requests == 0, "ready Shizuku remains unused while off");
            openSettings(test, screen);
            test.runOnMainSync(() -> {
                var option = option();
                require(!option.isChecked(), "default is off");
                require(!option.isSingleLine(), "switch label can wrap");
                require(option.getMinHeight() >= GalleryUi.dp(option.getContext(), 48), "touch target at least 48dp");
                reveal(option);
                fake.status = SdCardRemount.Status.UNAVAILABLE;
                option.performClick();
                require(option.isChecked() && prefs.getBoolean(CameraImportActivity.SD_REMOUNT_ON_ENTRY, false),
                        "checking the switch saves the opt-in");
                require(text(settingsView("sd-remount-info")).contains("インストール"),
                        "enable with unavailable Shizuku shows setup instructions");
                require(settingsView("sd-remount-permission").getVisibility() == View.GONE,
                        "no permission action while Shizuku is unavailable");
            });
            capture(test, "unavailable");
            require(fake.requests == 0 && fake.starts == 0, "turning on with unavailable Shizuku is quiet");
            finish(test, screen);

            fake.status = SdCardRemount.Status.NEEDS_PERMISSION;
            screen = open(test, entry(test));
            require(fake.requests == 0 && fake.starts == 0, "entry does not request permission or start before grant");
            openSettings(test, screen);
            test.runOnMainSync(() -> {
                require(option().isChecked(), "saved choice remains on");
                View action = settingsView("sd-remount-permission");
                reveal(action);
                require(action.isShown() && action.isEnabled(), "permission action is available");
                action.performClick();
                require(fake.requests == 1 && !action.isEnabled(), "request is sent and action is disabled while pending");
                fake.permission(false);
                require(text(settingsView("sd-remount-info")).contains("許可されませんでした"), "denial provides recovery instructions");
            });
            capture(test, "permission-denied");
            test.runOnMainSync(() -> {
                View action = settingsView("sd-remount-permission");
                require(action.isEnabled(), "action is available after denial");
                action.performClick();
                require(fake.requests == 2, "retry requests permission again");
                fake.permission(true);
                require(action.getVisibility() == View.GONE, "permission action disappears when granted");
            });
            require(fake.requests == 2 && fake.starts == 0, "permission actions alone do not remount");
            finish(test, screen);

            screen = open(test, CameraImportActivity.showProgressIntent(context));
            require(fake.starts == 0, "progress launch does not remount");
            finish(test, screen);
            screen = open(test, entry(test).setAction(CameraAttachActivity.ACTION_OPEN_FROM_USB_ATTACH));
            require(fake.starts == 0, "USB attach launch does not remount");
            finish(test, screen);

            screen = open(test, entry(test));
            await(test, () -> fake.starts == 1, "fresh manual entry starts once");
            screen = recreate(test, screen);
            require(fake.starts == 1, "recreating a pending remount does not start another");
            CameraImportActivity manual = screen;
            test.runOnMainSync(() -> {
                require(!screenView(manual, "sd-import-start").isEnabled(), "SD import is disabled during remount");
                require(text(screenView(manual, "sd-remount-result")).contains("再マウントしています"),
                        "recreated SD card shows remount in progress");
                fake.complete(SdCardRemount.Result.FAILED);
                require(text(screenView(manual, "sd-remount-result")).contains("接続を確認"),
                        "recreated SD card receives recoverable failure");
                require(text(screenView(manual, "sd-import-status")).equals(CameraQuickImportSources.SdState.CHECKING.message),
                        "SD source status is queried again after completion");
                require(screenView(manual, "sd-import-start").isEnabled(), "manual import is usable again");
                reveal(screenView(manual, "sd-remount-result"));
            });
            capture(test, "remount-failed");
            openSettings(test, screen);
            test.runOnMainSync(() -> {
                for (View window : WindowInspector.getGlobalWindowViews()) {
                    if (window.findViewWithTag("sd-remount-switch") == null) continue;
                    View picker = findText(window, "SDカードのフォルダを選ぶ");
                    require(picker != null && picker.isEnabled(), "existing picker stays accessible");
                    picker.performClick();
                    return;
                }
                throw new AssertionError("missing SD settings dialog");
            });
            await(test, () -> pickers.get() == 1, "SD picker launched");
            require(fake.starts == 1, "return from picker does not remount");

            screen = recreate(test, screen);
            require(fake.starts == 1, "recreation does not remount");
            finish(test, screen);

            screen = open(test, entry(test).putExtra(CameraImportActivity.START_IMPORT_KEEP_ORIGINALS, true));
            await(test, () -> fake.starts == 2, "long press starts one remount");
            screen = recreate(test, screen);
            test.runOnMainSync(() -> {
                require(!hasDialog("取り込み元を登録"), "long press waits for remount before discovering sources");
                fake.complete(SdCardRemount.Result.MOUNTED);
            });
            await(test, () -> hasDialog("取り込み元を登録"), "long press continues existing source discovery");
            finish(test, screen);

            fake.restoredReservation = true;
            screen = open(test, entry(test).putExtra(CameraImportActivity.START_IMPORT_KEEP_ORIGINALS, true));
            CameraImportActivity restarted = screen;
            test.runOnMainSync(() -> {
                require(!hasDialog("取り込み元を登録") && !screenView(restarted, "sd-import-start").isEnabled(),
                        "restored reservation blocks new import actions");
                fake.restoredReservation = false;
            });
            await(test, () -> hasDialog("取り込み元を登録"), "long press continues after restored reservation expires");
            require(fake.starts == 2 && fake.requests == 2 && pickers.get() == 1
                    && !progress.getActive() && priorCompletion == progress.getCompletion(),
                    "remount leaves actual import progress and the picker untouched");
            return "PASS default off, opt-in with Shizuku unavailable, explicit permission and denial retry, "
                    + "manual entry once, picker return, progress/USB attach exclusion, failure and SD recheck after recreation, "
                    + "and deferred long-press discovery across recreation and reservation restoration with no storage command.";
        } finally {
            if (screen != null) finish(test, screen);
            test.removeMonitor(pickerMonitor);
            fixture = null;
            var edit = prefs.edit();
            if (priorSettingExists) edit.putBoolean(CameraImportActivity.SD_REMOUNT_ON_ENTRY, priorSetting);
            else edit.remove(CameraImportActivity.SD_REMOUNT_ON_ENTRY);
            edit.commit();
        }
    }

    private static final class RemountFixture implements CameraImportActivity.SdRemountAccess {
        volatile SdCardRemount.Status status = SdCardRemount.Status.UNAVAILABLE;
        volatile int requests, starts;
        boolean restoredReservation;
        Consumer<Boolean> permission;
        Consumer<SdCardRemount.Result> completion;

        @Override public SdCardRemount.Status status() { return status; }
        @Override public boolean isRunning() { return completion != null || restoredReservation; }

        @Override public void requestPermission(Consumer<Boolean> callback) {
            require(permission == null, "one permission request at a time");
            permission = callback;
            requests++;
        }

        @Override public boolean start(Consumer<SdCardRemount.Result> callback) {
            require(completion == null && status == SdCardRemount.Status.READY, "remount starts only when ready");
            completion = callback;
            starts++;
            return true;
        }

        void permission(boolean approved) {
            if (approved) status = SdCardRemount.Status.READY;
            var callback = permission;
            permission = null;
            callback.accept(approved);
        }

        void complete(SdCardRemount.Result result) {
            var callback = completion;
            completion = null;
            callback.accept(result);
        }
    }

    private static Intent entry(Instrumentation test) {
        return new Intent(test.getTargetContext(), CameraImportActivity.class);
    }

    private static CameraImportActivity open(Instrumentation test, Intent intent) {
        var screen = (CameraImportActivity) test.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.waitForIdleSync();
        return screen;
    }

    private static void finish(Instrumentation test, Activity screen) {
        test.runOnMainSync(screen::finish);
        test.waitForIdleSync();
    }

    private static CameraImportActivity recreate(Instrumentation test, CameraImportActivity before) {
        var monitor = test.addMonitor(CameraImportActivity.class.getName(), null, false);
        try {
            test.runOnMainSync(before::recreate);
            var after = (CameraImportActivity) monitor.waitForActivityWithTimeout(5000);
            require(after != null && after != before, "screen recreated");
            test.waitForIdleSync();
            return after;
        } finally {
            test.removeMonitor(monitor);
        }
    }

    private static void openSettings(Instrumentation test, CameraImportActivity screen) {
        test.runOnMainSync(() -> {
            View pencil = screenView(screen, "sd-import-settings");
            require(pencil.isEnabled(), "SD pencil is available");
            pencil.performClick();
        });
        await(test, () -> settingsView("sd-remount-switch") != null, "SD settings dialog visible");
    }

    private static MaterialSwitch option() { return (MaterialSwitch) settingsView("sd-remount-switch"); }
    private static String text(View view) { return ((TextView) view).getText().toString(); }
    private static View screenView(Activity screen, String tag) { return screen.getWindow().getDecorView().findViewWithTag(tag); }

    private static View settingsView(String tag) {
        for (View window : WindowInspector.getGlobalWindowViews()) {
            View view = window.findViewWithTag(tag);
            if (window.isShown() && view != null) return view;
        }
        return null;
    }

    private static View findText(View view, String label) {
        if (view instanceof TextView text && label.contentEquals(text.getText())) return view;
        if (view instanceof ViewGroup group) {
            for (int child = 0; child < group.getChildCount(); child++) {
                View found = findText(group.getChildAt(child), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasDialog(String title) {
        for (View window : WindowInspector.getGlobalWindowViews())
            if (window.isShown() && findText(window, title) != null) return true;
        return false;
    }

    private static void reveal(View view) {
        view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true);
    }

    private static void await(Instrumentation test, BooleanSupplier condition, String message) {
        long deadline = SystemClock.uptimeMillis() + 6000;
        boolean[] met = {false};
        do {
            test.runOnMainSync(() -> met[0] = condition.getAsBoolean());
            if (met[0]) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError(message);
    }

    private static void capture(Instrumentation test, String name) throws Exception {
        test.waitForIdleSync();
        Bitmap bitmap = test.getUiAutomation().takeScreenshot();
        require(bitmap != null, "display screenshot");
        File directory = new File(test.getTargetContext().getCacheDir(), "sd-remount-ui-checks");
        directory.mkdirs();
        try (var output = new FileOutputStream(new File(directory, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally {
            bitmap.recycle();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
