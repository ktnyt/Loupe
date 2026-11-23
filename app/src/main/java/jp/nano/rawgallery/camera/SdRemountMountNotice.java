package jp.nano.rawgallery.camera;

import java.util.Locale;

/** A shell command can finish before Android delivers the source's mount broadcast. */
final class SdRemountMountNotice {
    private static final Tracker SOURCE = new Tracker();

    private SdRemountMountNotice() {}

    static void begin(String source) { SOURCE.begin(source); }
    static void end(boolean mountMayArrive) { SOURCE.end(mountMayArrive); }
    static boolean suppressMount(String uuid) { return SOURCE.suppressMount(uuid); }
    static void physicalDeparture(String uuid) { SOURCE.physicalDeparture(uuid); }
    static void registrationChanged(String uuid) { SOURCE.registrationChanged(uuid); }

    static final class Tracker {
        private String source;
        private boolean active;
        private boolean observed;

        synchronized void begin(String uuid) {
            source = normalized(uuid);
            active = true;
            observed = false;
        }

        synchronized void end(boolean mountMayArrive) {
            active = false;
            if (observed || !mountMayArrive) source = null;
        }

        synchronized boolean suppressMount(String uuid) {
            if (source == null || !source.equals(normalized(uuid))) return false;
            if (active) {
                observed = true;
                return true;
            }
            source = null; // A completed request can consume only its expected mount event.
            return true;
        }

        synchronized void physicalDeparture(String uuid) {
            if (source != null && source.equals(normalized(uuid))) source = null;
        }

        synchronized void registrationChanged(String uuid) {
            if (source != null && !source.equals(normalized(uuid))) source = null;
        }
    }

    private static String normalized(String uuid) {
        return uuid == null ? null : uuid.replace("-", "").toLowerCase(Locale.ROOT);
    }
}
