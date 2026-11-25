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
import jp.nano.rawgallery.camera.MountedCameraCard;
import jp.nano.rawgallery.camera.SdCardRemount;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
        boolean priorReconnectExists = prefs.contains(CameraImportActivity.SD_RECONNECT_ON_ENTRY);
        boolean priorReconnect = prefs.getBoolean(CameraImportActivity.SD_RECONNECT_ON_ENTRY, false);
        var priorCompletion = progress.getCompletion();
        var fake = new RemountFixture();
        fixture = fake;
        prefs.edit().remove(CameraImportActivity.SD_REMOUNT_ON_ENTRY)
                .remove(CameraImportActivity.SD_RECONNECT_ON_ENTRY).commit();
        CameraImportActivity screen = null;
        AtomicInteger pickers = new AtomicInteger();
        AtomicReference<Intent> pickedFolder = new AtomicReference<>();
        var pickerMonitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (!Intent.ACTION_OPEN_DOCUMENT_TREE.equals(intent.getAction())) return null;
                pickers.incrementAndGet();
                Intent choice = pickedFolder.getAndSet(null);
                if (choice != null) return new Instrumentation.ActivityResult(Activity.RESULT_OK, choice);
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
                require(settingsView("sd-reader-reconnect").getVisibility() == View.GONE,
                        "no reader reconnect action for an unsupported configuration");
                require(settingsView("sd-direct-import").getVisibility() == View.GONE,
                        "no direct write action in an unsupported configuration");
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
            finish(test, screen);
            prefs.edit().putBoolean(CameraImportActivity.SD_REMOUNT_ON_ENTRY, false).commit();
            fake.reader = true;
            fake.status = SdCardRemount.Status.NEEDS_PERMISSION;
            screen = open(test, entry(test));
            openSettings(test, screen);
            test.runOnMainSync(() -> {
                View reconnect = settingsView("sd-reader-reconnect");
                require(reconnect.isShown() && !reconnect.isEnabled(), "reader reconnect needs Shizuku permission");
                require(!option().isChecked(), "manual reconnect does not need the automatic remount preference");
                reveal(settingsView("sd-remount-permission"));
                settingsView("sd-remount-permission").performClick();
                fake.permission(true);
                require(reconnect.isEnabled(), "grant enables manual reconnect");
                reveal(reconnect);
            });
            capture(test, "reader-reconnect-settings");
            // Render the real control for inspection while keeping the write action disabled.
            test.runOnMainSync(() -> {
                TextView direct = (TextView) settingsView("sd-direct-import");
                require(!direct.isSingleLine() && direct.getMinHeight() >= GalleryUi.dp(direct.getContext(), 48),
                        "direct action wraps and has a 48dp touch target");
                require(direct.getText().toString().contains("microSD"), "direct action names the destination");
                direct.setVisibility(View.VISIBLE);
                direct.setEnabled(false);
                reveal(direct);
            });
            capture(test, "direct-import-setting");
            test.runOnMainSync(() -> {
                settingsView("sd-direct-import").setVisibility(View.GONE);
                settingsView("sd-reader-reconnect").performClick();
            });
            await(test, () -> hasDialog("リーダーを再接続しますか？"), "reconnect confirmation appears");
            capture(test, "reader-reconnect-confirmation");
            test.runOnMainSync(() -> {
                require(fake.reconnects == 0, "opening confirmation does not reconnect");
                clickDialogButton("キャンセル");
            });
            await(test, () -> !hasDialog("リーダーを再接続しますか？"), "cancel closes only confirmation");
            test.runOnMainSync(() -> settingsView("sd-reader-reconnect").performClick());
            await(test, () -> hasDialog("リーダーを再接続しますか？"), "confirmation can be reopened");
            CameraImportActivity beforeReconnect = screen;
            test.runOnMainSync(() -> {
                fake.detachGeneration++;
                clickDialogButton("再接続");
            });
            await(test, () -> text(screenView(beforeReconnect, "sd-remount-result")).contains("接続が変わりました"),
                    "confirmation from a previous connection is rejected");
            require(fake.reconnects == 0, "stale confirmation never dispatches");
            openSettings(test, screen);
            test.runOnMainSync(() -> settingsView("sd-reader-reconnect").performClick());
            await(test, () -> hasDialog("リーダーを再接続しますか？"), "changed reader can be explicitly confirmed");
            test.runOnMainSync(() -> clickDialogButton("再接続"));
            await(test, () -> fake.reconnects == 1, "confirmed operation starts exactly once");
            screen = recreate(test, screen);
            CameraImportActivity reconnecting = screen;
            test.runOnMainSync(() -> {
                require(fake.reconnects == 1 && !screenView(reconnecting, "sd-import-start").isEnabled(),
                        "recreation preserves the pending reconnect and blocks import");
                View outcome = screenView(reconnecting, "sd-remount-result");
                require(text(outcome).equals("リーダーを再接続しています…"), "reconnect has distinct progress");
                require(outcome.getAccessibilityLiveRegion() == View.ACCESSIBILITY_LIVE_REGION_POLITE,
                        "status changes are announced");
                reveal(outcome);
            });
            capture(test, "reader-reconnect-progress");
            test.runOnMainSync(() -> {
                fake.complete(SdCardRemount.Result.SOURCE_MISSING);
                require(text(screenView(reconnecting, "sd-remount-result")).contains("取り込み元のSDカードを認識できませんでした"),
                        "reconnect with missing source explains remaining problem");
                require(screenView(reconnecting, "sd-import-start").isEnabled() && !progress.getActive(),
                        "reconnect unblocks import without starting a transfer");
            });
            capture(test, "reader-reconnect-source-missing");
            openSettings(test, screen);
            test.runOnMainSync(() -> settingsView("sd-reader-reconnect").performClick());
            await(test, () -> hasDialog("リーダーを再接続しますか？"), "reconnect can be retried after a missing source");
            test.runOnMainSync(() -> clickDialogButton("再接続"));
            await(test, () -> fake.reconnects == 2, "retry begins after a new confirmation");
            test.runOnMainSync(() -> {
                fake.complete(SdCardRemount.Result.RECONNECTED);
                View outcome = screenView(reconnecting, "sd-remount-result");
                require(text(outcome).equals("リーダーを再接続しました。"), "successful reconnect is reported");
                require(screenView(reconnecting, "sd-import-start").isEnabled() && !progress.getActive(),
                        "successful reconnect does not start importing");
                reveal(outcome);
            });
            capture(test, "reader-reconnect-restored");

            openSettings(test, screen);
            test.runOnMainSync(() -> {
                var automatic = (MaterialSwitch) settingsView("sd-reconnect-on-entry");
                require(!automatic.isChecked() && !automatic.isSingleLine()
                        && automatic.getMinHeight() >= GalleryUi.dp(automatic.getContext(), 48),
                        "automatic reader reconnect is off by default and its label can wrap");
                reveal(automatic);
                automatic.performClick();
                require(automatic.isChecked()
                        && prefs.getBoolean(CameraImportActivity.SD_RECONNECT_ON_ENTRY, false)
                        && !option().isChecked(), "reader reconnect has its own persisted opt-in");
                require(fake.reconnects == 2, "enabling automatic reconnect does not reconnect the current entry");
            });
            capture(test, "auto-reader-settings");
            finish(test, screen);

            prefs.edit().putBoolean(CameraImportActivity.SD_REMOUNT_ON_ENTRY, true).commit();
            int previousPickers = pickers.get();
            screen = open(test, entry(test));
            require(fake.reconnects == 3 && fake.starts == 2,
                    "a supported reader reconnect takes precedence over legacy remount on manual entry");
            screen = recreate(test, screen);
            CameraImportActivity afterRotation = screen;
            test.runOnMainSync(() -> {
                require(fake.reconnects == 3 && !screenView(afterRotation, "sd-import-start").isEnabled(),
                        "manual entry rotation retains exactly one reconnect");
                View outcome = screenView(afterRotation, "sd-remount-result");
                require(text(outcome).equals("リーダーを再接続しています…"), "automatic reconnect shows progress");
                require(fullyVisible(outcome), "automatic reconnect progress is visible without scrolling");
                require(screenView(afterRotation, "sd-import-checking").getVisibility() == View.GONE,
                        "rotation does not display a second progress indicator while source discovery is deferred");
            });
            capture(test, "auto-reader-progress");
            test.runOnMainSync(() -> {
                fake.complete(SdCardRemount.Result.RECONNECTED);
                require(screenView(afterRotation, "sd-import-start").isEnabled(),
                        "manual entry refreshes SD controls after reconnect");
                requireNoQuickDiscovery(afterRotation, pickers, previousPickers);
            });
            finish(test, screen);

            screen = open(test, quickEntry(test));
            require(fake.reconnects == 4 && fake.starts == 2, "long press starts just one reader reconnect");
            screen = recreate(test, screen);
            CameraImportActivity quick = screen;
            test.runOnMainSync(() -> requireNoQuickDiscovery(quick, pickers, previousPickers));
            var storageMonitor = test.addMonitor(PhotoStorageActivity.class.getName(), null, false);
            Activity covering;
            try {
                test.runOnMainSync(() -> quick.startActivity(new Intent(quick, PhotoStorageActivity.class)));
                covering = storageMonitor.waitForActivityWithTimeout(5000);
                require(covering != null, "storage screen covers the import screen");
                test.waitForIdleSync();
            } finally {
                test.removeMonitor(storageMonitor);
            }
            test.runOnMainSync(() -> {
                fake.detachGeneration++;
                fake.complete(SdCardRemount.Result.RECONNECTED);
                requireNoQuickDiscovery(quick, pickers, previousPickers);
            });
            finish(test, covering);
            await(test, () -> pickers.get() == previousPickers + 1, "resumed long press seeks only the SD source after success");
            require(fake.reconnects == 4 && !progress.getActive(), "rotation, return and picker do not duplicate reconnect or import");
            finish(test, screen);

            int afterSuccess = pickers.get();
            for (var outcome : List.of(SdCardRemount.Result.SOURCE_MISSING, SdCardRemount.Result.FAILED,
                    SdCardRemount.Result.SKIPPED, SdCardRemount.Result.UNAVAILABLE)) {
                int before = fake.reconnects;
                screen = open(test, quickEntry(test));
                require(fake.reconnects == before + 1, "entry attempts one reconnect before " + outcome);
                CameraImportActivity unsuccessful = screen;
                test.runOnMainSync(() -> {
                    fake.complete(outcome);
                    requireNoQuickDiscovery(unsuccessful, pickers, afterSuccess);
                });
                await(test, () -> fullyVisible(screenView(unsuccessful, "sd-remount-result")),
                        "failed reconnect gives feedback without scrolling for " + outcome);
                if (outcome == SdCardRemount.Result.FAILED) capture(test, "auto-reader-failed");
                finish(test, screen);
            }

            screen = open(test, quickEntry(test));
            CameraImportActivity notificationReturn = screen;
            test.runOnMainSync(() -> {
                notificationReturn.onNewIntent(CameraImportActivity.showProgressIntent(context));
                fake.complete(SdCardRemount.Result.RECONNECTED);
                requireNoQuickDiscovery(notificationReturn, pickers, afterSuccess);
            });
            SystemClock.sleep(500);
            test.runOnMainSync(() -> requireNoQuickDiscovery(notificationReturn, pickers, afterSuccess));
            finish(test, screen);

            screen = open(test, quickEntry(test));
            CameraImportActivity changed = screen;
            test.runOnMainSync(() -> {
                fake.readerDestination = "3066-9999";
                fake.complete(SdCardRemount.Result.RECONNECTED);
                requireNoQuickDiscovery(changed, pickers, afterSuccess);
                require(text(screenView(changed, "sd-remount-result")).contains("保存先の接続が変わりました"),
                        "a different reader destination cannot continue long-press SD discovery");
            });
            finish(test, screen);

            fake.rejectReconnect = true;
            screen = open(test, quickEntry(test));
            screen = recreate(test, screen);
            CameraImportActivity rejected = screen;
            test.runOnMainSync(() -> {
                requireNoQuickDiscovery(rejected, pickers, afterSuccess);
                require(text(screenView(rejected, "sd-remount-result")).contains("再接続できませんでした"),
                        "dispatch refusal leaves the entry visible without continuing");
            });
            finish(test, screen);
            fake.rejectReconnect = false;

            int reconnects = fake.reconnects;
            int permissionRequests = fake.requests;
            fake.status = SdCardRemount.Status.NEEDS_PERMISSION;
            screen = open(test, quickEntry(test));
            CameraImportActivity needsPermission = screen;
            test.runOnMainSync(() -> {
                requireNoQuickDiscovery(needsPermission, pickers, afterSuccess);
                require(text(screenView(needsPermission, "sd-remount-result")).contains("Shizukuの使用を許可"),
                        "permission is explained on entry instead of requested automatically");
            });
            finish(test, screen);
            require(fake.reconnects == reconnects && fake.requests == permissionRequests && fake.starts == 2,
                    "permission failure does not retry, prompt, or start legacy remount");
            fake.status = SdCardRemount.Status.READY;

            screen = open(test, CameraImportActivity.showProgressIntent(context));
            require(fake.reconnects == reconnects, "progress only entry skips automatic reconnect even when enabled");
            finish(test, screen);
            screen = open(test, entry(test).setAction(CameraAttachActivity.ACTION_OPEN_FROM_USB_ATTACH));
            require(fake.reconnects == reconnects, "USB launch skips automatic reconnect even when enabled");
            finish(test, screen);
            screen = open(test, CameraImportActivity.mountedReaderAttachIntent(context, PhotoStorage.selected(context)));
            await(test, () -> pickers.get() == afterSuccess + 1, "mounted USB launch keeps its existing SD discovery");
            require(fake.reconnects == reconnects, "mounted USB launch does not repeat an automatic reconnect");
            finish(test, screen);

            int beforeWrongFolder = pickers.get();
            screen = open(test, quickEntry(test));
            CameraImportActivity wrongFolder = screen;
            pickedFolder.set(new Intent().setData(android.net.Uri.parse(
                    "content://com.android.externalstorage.documents/tree/9999-ABCD%3ADCIM"))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            test.runOnMainSync(() -> fake.complete(SdCardRemount.Result.RECONNECTED));
            await(test, () -> pickers.get() == beforeWrongFolder + 1 && findText(wrongFolder.getWindow().getDecorView(),
                    "再接続したSDカードのDCIMフォルダを選んでください。") != null,
                    "folder picker rejects the previous card after reconnect");
            require(!progress.getActive(), "wrong source never enters import");
            finish(test, screen);

            fake.reader = false;
            prefs.edit().putBoolean(CameraImportActivity.SD_REMOUNT_ON_ENTRY, false).commit();
            fake.restoredReservation = true;
            screen = open(test, quickEntry(test));
            CameraImportActivity busy = screen;
            test.runOnMainSync(() -> {
                requireNoQuickDiscovery(busy, pickers, beforeWrongFolder + 1);
                fake.restoredReservation = false;
            });
            SystemClock.sleep(500);
            test.runOnMainSync(() -> requireNoQuickDiscovery(busy, pickers, beforeWrongFolder + 1));
            require(text(screenView(busy, "sd-remount-result")).contains("リーダーの処理中"),
                    "temporarily missing reader does not replay the long press after another operation");
            finish(test, screen);

            reconnects = fake.reconnects;
            screen = open(test, quickEntry(test));
            await(test, () -> hasDialog("取り込み元を登録"), "unsupported reader keeps ordinary long-press discovery");
            require(fake.reconnects == reconnects && fake.starts == 2 && !progress.getActive(),
                    "automatic reconnect leaves unsupported readers and ordinary import alone");
            return "PASS default off, opt-in with Shizuku unavailable, explicit permission and denial retry, "
                    + "manual entry once, picker return, progress/USB attach exclusion, failure and SD recheck after recreation, "
                    + "deferred long-press discovery, and manual reader reconnect permission, confirmation, cancel, changed target, "
                    + "plus isolated automatic opt-in, plain and quick entries, pause and rotation, refusal and failed outcomes, "
                    + "destination changes, and unsupported readers with no storage command.";
        } finally {
            if (screen != null) finish(test, screen);
            test.removeMonitor(pickerMonitor);
            fixture = null;
            var edit = prefs.edit();
            if (priorSettingExists) edit.putBoolean(CameraImportActivity.SD_REMOUNT_ON_ENTRY, priorSetting);
            else edit.remove(CameraImportActivity.SD_REMOUNT_ON_ENTRY);
            if (priorReconnectExists) edit.putBoolean(CameraImportActivity.SD_RECONNECT_ON_ENTRY, priorReconnect);
            else edit.remove(CameraImportActivity.SD_RECONNECT_ON_ENTRY);
            edit.commit();
        }
    }

    private static final class RemountFixture implements CameraImportActivity.SdRemountAccess {
        volatile SdCardRemount.Status status = SdCardRemount.Status.UNAVAILABLE;
        volatile int requests, starts, reconnects;
        boolean restoredReservation, reader, rejectReconnect;
        long detachGeneration;
        String readerDestination = "3066-3064";
        Consumer<Boolean> permission;
        Consumer<SdCardRemount.Result> completion;

        @Override public SdCardRemount.Status status() { return status; }
        @Override public SdCardRemount.ReconnectTarget reconnectTarget() {
            return reader ? new SdCardRemount.ReconnectTarget("/dev/bus/usb/002/002", readerDestination, detachGeneration) : null;
        }
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

        @Override public boolean reconnect(SdCardRemount.ReconnectTarget target, Consumer<SdCardRemount.ReconnectResult> callback) {
            require(completion == null && target.equals(reconnectTarget()) && status == SdCardRemount.Status.READY,
                    "reconnect starts only for the confirmed reader with permission");
            if (rejectReconnect) return false;
            completion = result -> callback.accept(new SdCardRemount.ReconnectResult(result,
                    result == SdCardRemount.Result.RECONNECTED ? "abcd-1234" : null, MountedCameraCard.detachGeneration()));
            reconnects++;
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

    private static Intent quickEntry(Instrumentation test) {
        return entry(test).putExtra(CameraImportActivity.START_IMPORT_KEEP_ORIGINALS, true);
    }

    private static void requireNoQuickDiscovery(CameraImportActivity screen, AtomicInteger pickers, int before) {
        require(pickers.get() == before && !hasDialog("取り込み元を登録")
                        && !text(screenView(screen, "camera-import-stage")).contains("取り込み元を確認しています"),
                "reader reconnect must finish successfully before quick discovery");
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

    private static boolean fullyVisible(View view) {
        Rect bounds = new Rect();
        return view.isShown() && view.getGlobalVisibleRect(bounds) && bounds.height() == view.getHeight();
    }

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

    private static void clickDialogButton(String label) {
        for (View window : WindowInspector.getGlobalWindowViews()) {
            if (!window.isShown() || findText(window, "リーダーを再接続しますか？") == null) continue;
            View button = findText(window, label);
            require(button != null && button.performClick(), "dialog action " + label);
            return;
        }
        throw new AssertionError("missing reconnect confirmation");
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
        var automation = test.getUiAutomation();
        automation.waitForIdle(300, 2_500);
        Bitmap bitmap = automation.takeScreenshot();
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
