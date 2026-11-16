package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import jp.nano.rawgallery.camera.CameraBleWake;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises real helper termination without a device, and rejects unbounded/sensitive diagnostic fields. */
final class CameraBleDiagnosticsChecks {
    static String run(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        if (android.os.Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw new AssertionError("Grant BLUETOOTH_CONNECT to the inspection package before this check");
        SharedPreferences prefs = context.getSharedPreferences("camera_ble_diagnostics", Context.MODE_PRIVATE);
        prefs.edit().putString("stale_unbounded_field", "must be removed").commit();
        check(context, prefs, new char[]{'t','e','s','t'}, CameraBleWake.Outcome.UNSUPPORTED_CAMERA);
        check(context, prefs, new char[]{'\uD800'}, CameraBleWake.Outcome.INVALID_INPUT);
        return "PASS ble-diagnostics closedSchema=true staleFieldsRemoved=true credentialsExcluded=true";
    }
    private static void check(Context context, SharedPreferences prefs, char[] input,
            CameraBleWake.Outcome expected) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<CameraBleWake.Result> result = new AtomicReference<>();
        CameraBleWake helper = new CameraBleWake(context);
        try (java.io.Closeable handle = helper.wake(null, input, value -> { result.set(value); done.countDown(); })) {
            if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("No bounded helper result");
            if (result.get().outcome != expected) throw new AssertionError("Unexpected helper result: " + result.get().outcome);
            Map<String, ?> values = prefs.getAll();
            HashSet<String> schema = new HashSet<>(Arrays.asList("outcome", "reason", "stage", "operation",
                    "subscription", "gatt_status", "camera_status", "wake_sent", "elapsed_ms",
                    "notification_role", "notification_bytes", "warm_connected", "warm_disconnected", "fresh_asic_on", "connection_count"));
            if (!values.keySet().equals(schema)) throw new AssertionError("Unexpected diagnostic schema");
            if (!expected.name().equals(values.get("outcome")) || !"NONE".equals(values.get("stage"))
                    || !"NONE".equals(values.get("operation")) || !Boolean.FALSE.equals(values.get("wake_sent")))
                throw new AssertionError("Termination diagnostics do not match the operation");
            if (!Boolean.FALSE.equals(values.get("warm_connected")) || !Boolean.FALSE.equals(values.get("warm_disconnected"))
                    || !Boolean.FALSE.equals(values.get("fresh_asic_on")) || !Integer.valueOf(0).equals(values.get("connection_count")))
                throw new AssertionError("Preflight termination started recovery");
            if (((Long) values.get("elapsed_ms")) < 0) throw new AssertionError("Negative elapsed time");
        } finally { Arrays.fill(input, '\0'); }
    }
}
