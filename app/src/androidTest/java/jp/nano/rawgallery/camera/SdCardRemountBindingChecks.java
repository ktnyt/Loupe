package jp.nano.rawgallery.camera;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jp.nano.rawgallery.CameraImportActivity;
import rikka.shizuku.Shizuku;

/** Exercises the real shell service on a dedicated emulator without targeting any mounted volume. */
public final class SdCardRemountBindingChecks {
    public static final String MODE = "camera-pilot-sd-remount-shizuku";

    private SdCardRemountBindingChecks() {}

    public static String run(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        if (!("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE))
                || !"jp.nano.rawgallery.sd166".equals(context.getPackageName())) {
            throw new AssertionError("Use the isolated SD test package on the emulator");
        }
        Activity screen = test.startActivitySync(CameraImportActivity.showProgressIntent(context)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            verifyMountNoticeOrdering();
            awaitShizuku();
            if (SdCardRemount.status() == SdCardRemount.Status.NEEDS_PERMISSION) {
                CountDownLatch decided = new CountDownLatch(1);
                test.runOnMainSync(() -> SdCardRemount.requestPermission(granted -> decided.countDown()));
                if (!decided.await(25, TimeUnit.SECONDS) || SdCardRemount.status() != SdCardRemount.Status.READY) {
                    throw new AssertionError("Grant Shizuku access to the isolated Loupe emulator package");
                }
            }
            confirmServiceRefusesUnmatchedVolumes(test, context);
            return "PASS delayed mount suppression, real Shizuku shell Binder, app identity and refusal for nonexistent SD volumes";
        } finally {
            test.runOnMainSync(screen::finish);
        }
    }

    private static void verifyMountNoticeOrdering() {
        var notices = new SdRemountMountNotice.Tracker(1, () -> 1000L, null, record -> true);
        if (!notices.begin("ABCD-0123", 2000L)) throw new AssertionError("Expected a durable reservation");
        notices.end(true);
        if (notices.suppressMount("DEAD-1234") || !notices.suppressMount("abcd0123")
                || notices.suppressMount("ABCD-0123")) {
            throw new AssertionError("Only the delayed source broadcast is suppressed");
        }
        notices.begin("ABCD-0123", 2000L);
        notices.end(true);
        notices.physicalDeparture("ABCD-0123");
        if (notices.suppressMount("ABCD-0123")) throw new AssertionError("Reconnection after physical departure");
        notices.begin("ABCD-0123", 2000L);
        notices.end(false);
        if (notices.suppressMount("ABCD-0123")) throw new AssertionError("Skipped remount leaves no notice");
        notices.begin("ABCD-0123", 2000L);
        notices.end(true);
        notices.registrationChanged("DEAD-1234");
        if (notices.suppressMount("ABCD-0123")) throw new AssertionError("Changing cards clears the old notice");
    }

    private static void awaitShizuku() {
        long until = SystemClock.elapsedRealtime() + 4_000;
        while (SdCardRemount.status() == SdCardRemount.Status.UNAVAILABLE
                && SystemClock.elapsedRealtime() < until) {
            SystemClock.sleep(50);
        }
        if (SdCardRemount.status() == SdCardRemount.Status.UNAVAILABLE) {
            throw new AssertionError("Start Shizuku in ADB mode on the emulator");
        }
    }

    private static void confirmServiceRefusesUnmatchedVolumes(Instrumentation test, Context context)
            throws Exception {
        AtomicReference<IBinder> remote = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                remote.set(service);
                connected.countDown();
            }

            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        var args = SdCardRemount.serviceArgs(context);
        test.runOnMainSync(() -> Shizuku.bindUserService(args, connection));
        try {
            if (!connected.await(8, TimeUnit.SECONDS) || remote.get() == null) {
                throw new AssertionError("Shizuku did not bind the Loupe remount service");
            }
            AtomicInteger outcome = new AtomicInteger(-1);
            CountDownLatch finished = new CountDownLatch(1);
            var callback = new ISdCardRemountCallback.Stub() {
                @Override public void onResult(int result) {
                    outcome.set(result);
                    finished.countDown();
                }
            };
            ISdCardRemountService.Stub.asInterface(remote.get()).remount("FFFF-FFFF", "EEEE-EEEE",
                    SystemClock.elapsedRealtime() + SdCardRemountPlan.OPERATION_MS, callback);
            if (!finished.await(8, TimeUnit.SECONDS) || outcome.get() != SdCardRemount.Result.SKIPPED.ordinal()) {
                throw new AssertionError("Expected a refusal for unmatched cards, received " + outcome.get());
            }
        } finally {
            test.runOnMainSync(() -> Shizuku.unbindUserService(args, connection, true));
        }
    }
}
