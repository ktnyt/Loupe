package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import jp.nano.rawgallery.camera.CameraDocumentSourceChecks;
import jp.nano.rawgallery.camera.ImportProgress;
import jp.nano.rawgallery.camera.MountedCameraCard;

/** Native UI and local provider fixtures. No import or source access is started. */
final class CameraSdSwapChecks {
    static final String MODE = "camera-pilot-sd-swap";
    private static final String ISOLATED_PACKAGE = "jp.nano.rawgallery.sd166";
    private static final Uri OLD = DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents", "AA16-6001:DCIM");
    private static final Uri PICKED = DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents", "AA16-6002:DCIM");

    static String run(Instrumentation test, int width, float font) throws Exception {
        var context = test.getTargetContext();
        require(ISOLATED_PACKAGE.equals(context.getPackageName()), "SD swap fixture requires the isolated .sd166 APK");
        require("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE), "emulator only");
        require(width == 400 && font == 1f || width == 320 && font == 1.3f, "unsupported capture profile");
        require(CameraQuickImportSources.registrationState(true, true, false, true)
                == CameraQuickImportSources.SdState.DISCONNECTED, "old grant does not authorize a new card");
        require(CameraQuickImportSources.registrationState(true, false, false, false)
                == CameraQuickImportSources.SdState.NO_GRANT, "grant and card status differ");
        require(CameraQuickImportSources.registrationState(false, false, false, false)
                == CameraQuickImportSources.SdState.UNREGISTERED, "missing registration differs from missing grant");
        require(CameraQuickImportSources.registrationState(true, false, true, true)
                == CameraQuickImportSources.SdState.SAME_CARD, "a registered tree on the destination never shows READY");
        CameraDocumentSourceChecks.assertSdSwapListing(context);
        CameraDocumentSourceChecks.assertSdAdmission();
        assertStaleReady();

        var progress = ImportProgress.INSTANCE;
        require(!progress.getActive(), "fixture requires idle import");
        var progressBefore = new LinkedHashMap<Field, Object>();
        for (String name : new String[]{"photo", "active", "model", "done", "total", "lastMessage", "waiting", "status", "stopRequested"}) {
            Field field = ImportProgress.class.getDeclaredField(name);
            field.setAccessible(true);
            progressBefore.put(field, field.get(null));
        }
        var preferences = context.getSharedPreferences("camera_import", 0);
        String oldTree = preferences.getString("sd_tree_uri", null);
        Boolean oldAuto = preferences.contains("sd_auto") ? preferences.getBoolean("sd_auto", false) : null;
        File captures = new File(context.getCacheDir(), "sd-swap-checks");
        require(captures.isDirectory() || captures.mkdirs(), "capture directory");
        var pickerCount = new AtomicInteger();
        var started = new AtomicInteger();
        Runnable observe = () -> { if (progress.getActive()) started.incrementAndGet(); };
        var monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (!Intent.ACTION_OPEN_DOCUMENT_TREE.equals(intent.getAction())) return null;
                pickerCount.incrementAndGet();
                return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
            }
        };
        CameraImportActivity activity = null;
        boolean serviceFixtureStarted = false;
        test.addMonitor(monitor);
        progress.addListener(observe);
        try {
            preferences.edit().putBoolean("sd_auto", true).putString("sd_tree_uri", OLD.toString()).commit();
            CameraDualSlotUiChecks.run(test, width, font);
            require(OLD.toString().equals(preferences.getString("sd_tree_uri", null)), "confirmation/cancel never exposes a picked tree");
            require(!progress.getActive() && started.get() == 0, "confirmation fixture with auto enabled remains idle");

            activity = (CameraImportActivity)test.startActivitySync(new Intent(context, CameraImportActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            CameraImportActivity screen = activity;
            var sdStatus = (TextView) field(screen, "sdStatus");
            var start = (View) field(screen, "sdStart");
            var settings = (View) field(screen, "settingsButton");
            var render = CameraImportActivity.class.getDeclaredMethod("renderStatus");
            render.setAccessible(true);
            test.runOnMainSync(settings::performClick);
            awaitText(test, sdStatus, CameraQuickImportSources.SdState.DISCONNECTED.message);
            String prefix = "sd-swap-" + width + "-" + font;
            reveal(test, sdStatus);
            var sdSettings = (View) field(screen, "sdSettingsButton");
            require(sdSettings.isClickable() && "SDカード取り込み設定".contentEquals(sdSettings.getContentDescription())
                            && sdSettings.getHeight() >= GalleryUi.dp(screen, 48),
                    "SD settings have a labeled 48dp action");
            require(findText((ViewGroup) sdStatus.getParent(), "SDカードのフォルダを選ぶ") == null,
                    "folder settings are not duplicated in the main card");
            capture(test, screen, captures, prefix + "-disconnected",
                    CameraQuickImportSources.SdState.DISCONNECTED.message, "SDカードから取り込む");

            test.runOnMainSync(sdSettings::performClick);
            var options = (androidx.appcompat.app.AlertDialog) field(screen, "sdSettingsDialog");
            require(options.isShowing() && pickerCount.get() == 0 && started.get() == 0,
                    "opening SD settings does not register or import");
            capture(test, screen, options.getWindow().getDecorView(), captures, prefix + "-settings",
                    "SDカード取り込み設定", "SDカードのフォルダを選ぶ", "内容も確認する");
            test.runOnMainSync(() -> options.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick());
            require(!options.isShowing(), "close returns to the single SD action");

            test.runOnMainSync(() -> screen.renderSdSource(CameraQuickImportSources.SdState.CHECKING));
            awaitText(test, sdStatus, CameraQuickImportSources.SdState.CHECKING.message);
            reveal(test, sdStatus);
            capture(test, screen, captures, prefix + "-checking", CameraQuickImportSources.SdState.CHECKING.message,
                    "SDカードから取り込む");
            test.runOnMainSync(start::performClick);
            long deadline = SystemClock.uptimeMillis() + 6000;
            while (pickerCount.get() == 0 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(30);
            require(pickerCount.get() == 1, "manual stale registration opens reselectable picker");
            test.waitForIdleSync();
            require(OLD.toString().equals(preferences.getString("sd_tree_uri", null)), "cancelling reselect preserves the old registration");
            require(started.get() == 0, "reselect did not begin an operation");

            long epoch = MountedCameraCard.detachGeneration();
            var stale = new CameraImportActivity.SdStart(PICKED, null, PhotoStorage.selected(screen), "JPEG",
                    epoch - 1, true, OLD.toString());
            require(!CameraImportActivity.matchesSelection(stale, PhotoStorage.selected(screen), epoch, OLD.toString()),
                    "same UUID with an older generation cannot start");
            var current = new CameraImportActivity.SdStart(PICKED, "aa16-6003", "aa16-6003", "JPEG", epoch, true, OLD.toString());
            require(!CameraImportActivity.matchesSelection(current, "aa16-6004", epoch, OLD.toString()),
                    "a changed destination invalidates the selected source request");
            require(!CameraImportActivity.matchesSelection(current, "aa16-6003", epoch, PICKED.toString()),
                    "a changed registration invalidates the selected source request");
            test.runOnMainSync(() -> screen.confirmSdKinds(stale, "取り込み元　選んだSDカード\n保存先　内部ストレージ"));
            var dialog = (androidx.appcompat.app.AlertDialog) field(screen, "sdConfirmation");
            test.runOnMainSync(() -> dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick());
            require(OLD.toString().equals(preferences.getString("sd_tree_uri", null)), "a stale confirmation does not register the candidate");
            require(started.get() == 0, "a stale confirmation did not start an operation");

            var invalidTree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "AA16-6002:Pictures");
            var invalidPick = new CameraImportActivity.SdStart(invalidTree, null, PhotoStorage.selected(screen),
                    "JPEG", epoch, true, OLD.toString());
            test.runOnMainSync(() -> screen.confirmSdSource(invalidPick, true));
            require(field(screen, "sdConfirmation") == null && OLD.toString().equals(preferences.getString("sd_tree_uri", null)),
                    "the normal picker rejects a non-DCIM tree without publishing the selection");

            Uri primary = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:DCIM");
            var internalPick = new CameraImportActivity.SdStart(primary, null, PhotoStorage.selected(screen),
                    "JPEG", epoch, true, OLD.toString());
            test.runOnMainSync(() -> screen.confirmSdSource(internalPick, true));
            awaitText(test, sdStatus, "接続したSDカードのDCIMフォルダを選んでください。");
            require(field(screen, "sdConfirmation") == null && OLD.toString().equals(preferences.getString("sd_tree_uri", null)),
                    "a primary DCIM tree cannot be newly registered as a removable camera source");

            var externalPick = new CameraImportActivity.SdStart(PICKED, null, PhotoStorage.selected(screen),
                    "JPEG", epoch, true, OLD.toString());
            assertRegistrationReview(test, screen, externalPick, captures, prefix, sdStatus);
            serviceFixtureStarted = true;
            assertBusyAfterFinalProbe(test, screen, externalPick, sdStatus);
            require(OLD.toString().equals(preferences.getString("sd_tree_uri", null))
                            && preferences.getBoolean("sd_auto", false) && started.get() == 0,
                    "cancellation and a vanished folder both retain the old auto-import source");
            require(context.getContentResolver().getPersistedUriPermissions().stream()
                            .noneMatch(permission -> permission.getUri().equals(PICKED)),
                    "failed final reachability did not persist a read grant");

            var result = (TextView) field(screen, "result");
            var acknowledged = new CameraImportActivity.SdStart(OLD, null, PhotoStorage.selected(screen),
                    "JPEG", epoch, false, OLD.toString());
            startAdmittedFixture(test, screen, acknowledged, sdStatus);
            Field connectionFailure = screen.getClass().getDeclaredField("connectionFailure");
            connectionFailure.setAccessible(true);
            test.runOnMainSync(() -> {
                if (((com.google.android.material.button.MaterialButton) settings).isChecked()) settings.performClick();
                try { connectionFailure.set(screen, null); }
                catch (IllegalAccessException failure) { throw new AssertionError(failure); }
                progress.begin("Synthetic SD");
                progress.photoListing(0, -1);
                progress.photoTerminal("failed", "");
                progress.finish("SDカードの一覧を読み込み中です。少し待ってから確認してください。");
                render(screen, render);
            });
            reveal(test, result);
            assertResult(result, "一覧を読み込み中");
            capture(test, screen, captures, prefix + "-incomplete", "一覧を読み込み中");
            captureTerminalSdPanel(test, screen, settings, sdStatus, captures, prefix + "-incomplete-source");

            startAdmittedFixture(test, screen, acknowledged, sdStatus);
            test.runOnMainSync(() -> {
                progress.begin("Synthetic SD");
                progress.photoListing(0, 0);
                progress.photoTerminal("no_candidates", "");
                progress.finish("選んだフォルダに取り込める写真がありません。");
                render(screen, render);
            });
            reveal(test, result);
            assertResult(result, "選んだフォルダに取り込める写真がありません");
            require(ImportProgressPresentation.current().secondary.isEmpty(), "empty result is not a saved/history summary");
            capture(test, screen, captures, prefix + "-empty", "選んだフォルダに取り込める写真がありません");
            captureTerminalSdPanel(test, screen, settings, sdStatus, captures, prefix + "-empty-source");

            startAdmittedFixture(test, screen, acknowledged, sdStatus);
            test.runOnMainSync(() -> {
                progress.photoContent(1, 1, 1, 0, 1024, 1024);
                progress.photoTerminal("complete", "");
                progress.finish("1枚追加・0枚取り込み済み");
                render(screen, render);
            });
            captureTerminalSdPanel(test, screen, settings, sdStatus, captures, prefix + "-complete-source");
            require(result.getText().toString().equals("取り込み完了"), "completion belongs to the operation panel");
            return "PASS isolated SD swap: incomplete and complete listing, stale registration and picker, synthetic confirmation/cancel and stale request, native status at "
                    + width + "dp/" + font + ". No physical card or original reads.";
        } finally {
            progress.removeListener(observe);
            test.removeMonitor(monitor);
            for (var entry : progressBefore.entrySet()) entry.getKey().set(null, entry.getValue());
            var restore = preferences.edit();
            if (oldTree == null) restore.remove("sd_tree_uri"); else restore.putString("sd_tree_uri", oldTree);
            if (oldAuto == null) restore.remove("sd_auto"); else restore.putBoolean("sd_auto", oldAuto);
            restore.commit();
            if (activity != null) {
                CameraImportActivity exit = activity;
                boolean restoreWatch = serviceFixtureStarted;
                test.runOnMainSync(() -> {
                    if (restoreWatch)
                        jp.nano.rawgallery.camera.CameraDifferentialService.updateAuto(exit);
                    exit.finish();
                });
            }
        }
    }

    private static void assertResult(TextView view, String text) {
        require(fullyVisible(view) && view.getText().toString().contains(text)
                        && !view.getText().toString().contains("取り込み完了"),
                "result presents the specific non-success condition");
    }

    private static void reveal(Instrumentation test, View view) {
        test.waitForIdleSync();
        test.runOnMainSync(() -> {
            View parent = view;
            while (!(parent instanceof ScrollView) && parent.getParent() instanceof View ancestor) parent = ancestor;
            if (parent instanceof ScrollView scroll) {
                int[] item = new int[2], container = new int[2];
                view.getLocationOnScreen(item);
                scroll.getLocationOnScreen(container);
                scroll.scrollTo(0, scroll.getScrollY() + item[1] - container[1] - GalleryUi.dp(view.getContext(), 12));
            }
        });
        test.waitForIdleSync();
        require(fullyVisible(view), "capture status is fully visible before taking the screenshot");
    }

    private static boolean fullyVisible(View view) {
        Rect visible = new Rect();
        return view.isShown() && view.getHeight() > 0 && view.getGlobalVisibleRect(visible)
                && visible.width() >= view.getWidth() && visible.height() >= view.getHeight();
    }

    private static View findText(View root, String text) {
        if (root instanceof TextView view && text.contentEquals(view.getText())) return view;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View found = findText(group.getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }

    private static void awaitText(Instrumentation test, TextView view, String expected) {
        long deadline = SystemClock.uptimeMillis() + 6000;
        while (SystemClock.uptimeMillis() < deadline) {
            test.waitForIdleSync();
            if (expected.contentEquals(view.getText())) return;
            SystemClock.sleep(30);
        }
        throw new AssertionError("expected SD source status");
    }

    private static void startAdmittedFixture(Instrumentation test, CameraImportActivity screen,
            CameraImportActivity.SdStart request, TextView sdStatus) throws Exception {
        test.runOnMainSync(() -> {
            CameraImportActivity.SdSubmission real = screen.sdSubmission;
            try {
                screen.sdSubmission = (submitted, registrationOnly, confirmation) -> {
                    require(!registrationOnly, "fixture acknowledges the import route");
                    ImportProgress.INSTANCE.connecting("写真を確認しています");
                    CameraDocumentSourceChecks.acknowledgeSdUiFixture(confirmation);
                };
                screen.submitSd(request, false);
            } finally { screen.sdSubmission = real; }
        });
        awaitText(test, sdStatus, CameraQuickImportSources.SdState.DISCONNECTED.message);
        require(field(screen, "submittedSd") == null && ImportProgress.INSTANCE.getActive(),
                "accepted acknowledgement keeps source status separate from active operation progress");
    }

    private static void captureTerminalSdPanel(Instrumentation test, CameraImportActivity screen, View settings,
            TextView sdStatus, File captures, String name) throws Exception {
        var button = (com.google.android.material.button.MaterialButton) settings;
        test.runOnMainSync(() -> { if (!button.isChecked()) button.performClick(); });
        awaitText(test, sdStatus, CameraQuickImportSources.SdState.DISCONNECTED.message);
        reveal(test, sdStatus);
        capture(test, screen, captures, name, CameraQuickImportSources.SdState.DISCONNECTED.message,
                "SDカードから取り込む");
        test.runOnMainSync(() -> { if (button.isChecked()) button.performClick(); });
    }

    private static void assertRegistrationReview(Instrumentation test, CameraImportActivity screen,
            CameraImportActivity.SdStart request, File captures, String prefix, TextView sdStatus) throws Exception {
        String source = "取り込み元　カメラのSDカード（AA16-6002）";
        String destination = "保存先　内部ストレージ";
        String auto = "カード接続時の自動取り込みに使います";
        var probes = new AtomicInteger();
        var available = new java.util.concurrent.atomic.AtomicBoolean(true);
        CameraImportActivity.SdSourceProbe real = screen.sdSourceProbe;
        test.runOnMainSync(() -> screen.sdSourceProbe = (pending, cancellation) -> {
            probes.incrementAndGet();
            return available.get() ? new CameraImportActivity.SdLocation("aa16-6002", source + "\n" + destination) : null;
        });
        try {
            test.runOnMainSync(() -> screen.confirmSdSource(request, true));
            var first = awaitConfirmation(test, screen);
            capture(test, screen, first.getWindow().getDecorView(), captures, prefix + "-registration",
                    "取り込み元を登録", source, destination, auto, "キャンセル", "登録する");

            var root = test.getUiAutomation().getRootInActiveWindow();
            Rect safe = PhotoStorageChecks.availableDisplay(screen);
            int minimum = GalleryUi.dp(screen, 48);
            requireVisibleAction(root, safe, minimum, "キャンセル");
            requireVisibleAction(root, safe, minimum, "登録する");
            var labels = new java.util.ArrayList<String>();
            addVisibleLabels(root, labels);
            int explanation = indexContaining(labels, source);
            require(explanation >= 0 && explanation < indexContaining(labels, "キャンセル")
                            && explanation < indexContaining(labels, "登録する"),
                    "native accessibility tree exposes source/destination before registration actions");
            test.runOnMainSync(() -> first.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick());
            test.waitForIdleSync();
            require(probes.get() == 1, "cancelling registration does not run a commit probe");

            test.runOnMainSync(() -> screen.confirmSdSource(request, true));
            var second = awaitConfirmation(test, screen);
            require(probes.get() == 2, "second picker review checked the tree");
            available.set(false);
            test.runOnMainSync(() -> second.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick());
            awaitText(test, sdStatus, CameraImportActivity.PICKED_FOLDER_UNAVAILABLE);
            require(probes.get() == 3, "register tap repeats reachability before taking a grant");
        } finally {
            test.runOnMainSync(() -> screen.sdSourceProbe = real);
        }
    }

    private static void assertBusyAfterFinalProbe(Instrumentation test, CameraImportActivity screen,
            CameraImportActivity.SdStart request, TextView status) throws Exception {
        var workStarted = new java.util.concurrent.CountDownLatch(1);
        var finishWork = new java.util.concurrent.CountDownLatch(1);
        var probes = new AtomicInteger();
        CameraImportActivity.SdSourceProbe real = screen.sdSourceProbe;
        // Simulate the camera gate being taken by automatic import after the final probe begins.
        var automaticWork = new Thread(() -> {
            var lock = jp.nano.rawgallery.camera.CameraImportGate.lock;
            lock.lock();
            try {
                workStarted.countDown();
                finishWork.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { lock.unlock(); }
        }, "sd-busy-admission-fixture");
        test.runOnMainSync(() -> screen.sdSourceProbe = (pending, cancellation) -> {
            if (probes.incrementAndGet() == 2) {
                automaticWork.start();
                try {
                    if (!workStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
                        throw new java.io.IOException("fixture could not hold the camera gate");
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("fixture interrupted");
                }
            }
            return new CameraImportActivity.SdLocation("aa16-6002", "取り込み元　SDカード\n保存先　内部ストレージ");
        });
        try {
            test.runOnMainSync(() -> screen.confirmSdSource(request, true));
            var dialog = awaitConfirmation(test, screen);
            test.runOnMainSync(() -> dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick());
            awaitText(test, status, "別の取り込みが実行中です。処理が終わってからやり直してください。");
            require(probes.get() == 2 && field(screen, "submittedSd") == null,
                    "the real service rejects and acknowledges the busy request after the final probe");
        } finally {
            finishWork.countDown();
            automaticWork.join(1000);
            test.runOnMainSync(() -> screen.sdSourceProbe = real);
        }
        require(!automaticWork.isAlive(), "the synthetic automatic operation released the camera gate");
    }

    private static androidx.appcompat.app.AlertDialog awaitConfirmation(Instrumentation test,
            CameraImportActivity screen) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 6000;
        while (SystemClock.uptimeMillis() < deadline) {
            test.waitForIdleSync();
            var dialog = (androidx.appcompat.app.AlertDialog) field(screen, "sdConfirmation");
            if (dialog != null && dialog.isShowing()) return dialog;
            SystemClock.sleep(30);
        }
        throw new AssertionError("registration confirmation did not open");
    }

    private static void requireVisibleAction(AccessibilityNodeInfo root, Rect safe, int min, String label) {
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label)) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (node.getText() != null && label.contentEquals(node.getText()) && node.isVisibleToUser() && node.isClickable()
                    && node.isFocusable() && safe.contains(bounds) && bounds.width() >= min && bounds.height() >= min)
                return;
        }
        throw new AssertionError("registration action lacks a visible focusable 48dp target");
    }

    private static void addVisibleLabels(AccessibilityNodeInfo node, java.util.List<String> labels) {
        if (node == null) return;
        if (node.isVisibleToUser() && node.getText() != null) labels.add(node.getText().toString());
        for (int i = 0; i < node.getChildCount(); i++) addVisibleLabels(node.getChild(i), labels);
    }

    private static int indexContaining(java.util.List<String> labels, String text) {
        for (int i = 0; i < labels.size(); i++) if (labels.get(i).contains(text)) return i;
        return -1;
    }

    private static void assertStaleReady() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var returnResult = new java.util.concurrent.CountDownLatch(1);
        var task = new java.util.concurrent.FutureTask<>(() -> {
            entered.countDown();
            require(returnResult.await(2, java.util.concurrent.TimeUnit.SECONDS), "release stalled synthetic reachability");
            return new CameraQuickImportSources.SdSource(CameraQuickImportSources.SdState.READY, OLD, "external_primary");
        });
        var scenario = new Thread(task, "sd-stale-ready-fixture");
        scenario.start();
        try {
            require(entered.await(2, java.util.concurrent.TimeUnit.SECONDS), "reachability in flight");
            Uri currentRegistration = PICKED;
            String currentDestination = "aa16-6003";
            var currentGrant = CameraQuickImportSources.SdState.NO_GRANT;
            returnResult.countDown();
            var stale = task.get(2, java.util.concurrent.TimeUnit.SECONDS);
            require(CameraQuickImportSources.readyState(stale, currentRegistration, "external_primary",
                    CameraQuickImportSources.SdState.CHECKING) != CameraQuickImportSources.SdState.READY,
                    "late provider success cannot describe a new registration");
            require(CameraQuickImportSources.readyState(stale, OLD, currentDestination,
                    CameraQuickImportSources.SdState.CHECKING) != CameraQuickImportSources.SdState.READY,
                    "late provider success cannot describe a new destination");
            require(CameraQuickImportSources.readyState(stale, OLD, "external_primary", currentGrant)
                    == CameraQuickImportSources.SdState.NO_GRANT,
                    "late provider success cannot restore a revoked read grant");
            require(CameraQuickImportSources.readyState(stale, OLD, "external_primary",
                    CameraQuickImportSources.SdState.CHECKING) == CameraQuickImportSources.SdState.READY,
                    "an unchanged registration, destination and grant may show ready");
        } finally {
            returnResult.countDown();
            scenario.join(2000);
        }
    }

    private static void capture(Instrumentation test, CameraImportActivity activity, File directory, String name,
            String... labels) throws Exception {
        capture(test, activity, activity.getWindow().getDecorView(), directory, name, labels);
    }

    private static void capture(Instrumentation test, CameraImportActivity activity, View decor, File directory,
            String name, String... labels) throws Exception {
        awaitRenderedFrame(test, activity, decor, labels);
        Bitmap screen = test.getUiAutomation().takeScreenshot();
        require(screen != null, "capture composed display");
        try (var output = new FileOutputStream(new File(directory, name + ".png"))) {
            screen.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally {
            screen.recycle();
        }
    }

    private static void awaitRenderedFrame(Instrumentation test, CameraImportActivity activity, View decor,
            String[] labels) throws InterruptedException {
        long timeout = SystemClock.uptimeMillis() + 6000;
        do {
            var frame = new java.util.concurrent.CountDownLatch(1);
            test.runOnMainSync(() -> {
                decor.invalidate();
                decor.postOnAnimation(() -> decor.postOnAnimation(frame::countDown));
            });
            require(frame.await(2, java.util.concurrent.TimeUnit.SECONDS), "wait for a new displayed frame");
            test.waitForIdleSync();
            var root = test.getUiAutomation().getRootInActiveWindow();
            if (root != null && root.getPackageName() != null
                    && ISOLATED_PACKAGE.contentEquals(root.getPackageName()) && decor.hasWindowFocus()) {
                Rect safe = PhotoStorageChecks.availableDisplay(activity);
                boolean allVisible = true;
                for (String label : labels) allVisible &= visibleInWindow(root, safe, label);
                if (allVisible) {
                    SystemClock.sleep(100);
                    return;
                }
            }
            SystemClock.sleep(40);
        } while (SystemClock.uptimeMillis() < timeout);
        throw new AssertionError("fresh accessibility window did not expose every capture label");
    }

    private static boolean visibleInWindow(AccessibilityNodeInfo window, Rect safe, String label) {
        for (AccessibilityNodeInfo node : window.findAccessibilityNodeInfosByText(label)) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (node.isVisibleToUser() && node.getText() != null && node.getText().toString().contains(label)
                    && !bounds.isEmpty() && safe.contains(bounds)) return true;
        }
        return false;
    }

    private static Object field(Object target, String name) throws Exception {
        Field value = target.getClass().getDeclaredField(name);
        value.setAccessible(true);
        return value.get(target);
    }

    private static void render(CameraImportActivity activity, java.lang.reflect.Method render) {
        try { render.invoke(activity); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

}
