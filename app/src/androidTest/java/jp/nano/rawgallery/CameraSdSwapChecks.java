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
            View reselect = findText((ViewGroup) sdStatus.getParent(), "SDカードのフォルダを選ぶ");
            require(reselect != null && reselect.isClickable() && fullyVisible(reselect)
                    && reselect.getHeight() >= GalleryUi.dp(screen, 48),
                    "disconnected status and its 48dp reselect action are both in view");
            capture(test, captures, prefix + "-disconnected");

            test.runOnMainSync(() -> screen.renderSdSource(CameraQuickImportSources.SdState.CHECKING));
            awaitText(test, sdStatus, CameraQuickImportSources.SdState.CHECKING.message);
            reveal(test, sdStatus);
            capture(test, captures, prefix + "-checking");
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
            var confirmSource = CameraImportActivity.class.getDeclaredMethod("confirmSdSource",
                    CameraImportActivity.SdStart.class, boolean.class);
            confirmSource.setAccessible(true);
            test.runOnMainSync(() -> {
                try { confirmSource.invoke(screen, invalidPick, true); }
                catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            require(field(screen, "sdConfirmation") == null && OLD.toString().equals(preferences.getString("sd_tree_uri", null)),
                    "the normal picker rejects a non-DCIM tree without publishing the selection");

            var result = (TextView) field(screen, "result");
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
            capture(test, captures, prefix + "-incomplete");

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
            capture(test, captures, prefix + "-empty");
            return "PASS isolated SD swap: incomplete and complete listing, stale registration and picker, synthetic confirmation/cancel and stale request, native status at "
                    + width + "dp/" + font + ". No physical card or original reads.";
        } finally {
            progress.removeListener(observe);
            if (activity != null) test.runOnMainSync(activity::finish);
            test.removeMonitor(monitor);
            for (var entry : progressBefore.entrySet()) entry.getKey().set(null, entry.getValue());
            var restore = preferences.edit();
            if (oldTree == null) restore.remove("sd_tree_uri"); else restore.putString("sd_tree_uri", oldTree);
            if (oldAuto == null) restore.remove("sd_auto"); else restore.putBoolean("sd_auto", oldAuto);
            restore.commit();
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

    private static void capture(Instrumentation test, File directory, String name) throws Exception {
        test.waitForIdleSync();
        Bitmap screen = test.getUiAutomation().takeScreenshot();
        require(screen != null, "capture composed display");
        try (var output = new FileOutputStream(new File(directory, name + ".png"))) {
            screen.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally {
            screen.recycle();
        }
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
