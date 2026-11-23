package jp.nano.rawgallery.camera;

import android.os.SystemClock;
import java.util.Locale;
import java.util.function.LongSupplier;

/** A shell command can finish before Android delivers the source's mount broadcast. */
final class SdRemountMountNotice {
    private static final Tracker SOURCE = new Tracker(SystemClock::elapsedRealtime);

    private SdRemountMountNotice() {}

    static void begin(String source) { SOURCE.begin(source); }
    static void end() { SOURCE.end(); }
    static boolean suppressMount(String uuid) { return SOURCE.suppressMount(uuid); }
    static void physicalDeparture(String uuid) { SOURCE.physicalDeparture(uuid); }

    static final class Tracker {
        private static final long BROADCAST_GRACE_MS = 5_000;
        private final LongSupplier now;
        private String source;
        private boolean active;
        private boolean observed;
        private long expires;

        Tracker(LongSupplier now) { this.now = now; }

        synchronized void begin(String uuid) {
            source = normalized(uuid);
            active = true;
            observed = false;
        }

        synchronized void end() {
            active = false;
            if (observed) source = null;
            else expires = now.getAsLong() + BROADCAST_GRACE_MS;
        }

        synchronized boolean suppressMount(String uuid) {
            if (source == null || !source.equals(normalized(uuid))) return false;
            if (active) {
                observed = true;
                return true;
            }
            source = null; // A completed request can consume only its expected mount event.
            return now.getAsLong() < expires;
        }

        synchronized void physicalDeparture(String uuid) {
            if (source != null && source.equals(normalized(uuid))) source = null;
        }
    }

    private static String normalized(String uuid) {
        return uuid == null ? null : uuid.replace("-", "").toLowerCase(Locale.ROOT);
    }
}
