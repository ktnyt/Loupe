package jp.nano.rawgallery.camera;

import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Keeps an accepted remount separate from ordinary storage work and mount broadcasts. */
final class SdRemountMountNotice {
    static final long RESULT_GRACE_MS = 1_000;
    private static final Pattern UUID = Pattern.compile("(?i)[0-9a-f]{4}-?[0-9a-f]{4}");
    private static volatile Tracker tracker;

    record Reservation(int boot, long blockedUntil, String source) {}
    interface Store { boolean save(Reservation reservation); }

    private SdRemountMountNotice() {}

    static void restore(int boot, LongSupplier elapsed, Reservation saved, Store store, Predicate<String> registered) {
        Tracker restored = new Tracker(boot, elapsed, saved, store);
        restored.retainRegistered(registered);
        tracker = restored;
    }

    static boolean begin(String source, long blockedUntil) {
        Tracker current = tracker;
        return current != null && current.begin(source, blockedUntil);
    }

    static void end(boolean mountMayArrive) {
        Tracker current = tracker;
        if (current != null) current.end(mountMayArrive);
    }

    static boolean blocksImport() {
        Tracker current = tracker;
        return current != null && current.blocksImport();
    }

    static boolean suppressMount(String uuid) {
        Tracker current = tracker;
        return current != null && current.suppressMount(uuid);
    }

    static void physicalDeparture(String uuid) {
        Tracker current = tracker;
        if (current != null) current.physicalDeparture(uuid);
    }

    static void registrationChanged(String uuid) {
        Tracker current = tracker;
        if (current != null) current.registrationChanged(uuid);
    }

    static final class Tracker {
        // The remote operation is independently bounded, but a submitted vold command is asynchronous.
        static final long MAX_BLOCK_MS = SdCardRemountPlan.OPERATION_MS + RESULT_GRACE_MS;

        private final int boot;
        private final LongSupplier elapsed;
        private final Store store;
        private volatile Reservation state;

        Tracker(int boot, LongSupplier elapsed, Reservation saved, Store store) {
            this.boot = boot;
            this.elapsed = elapsed;
            this.store = store;
            if (boot >= 0 && saved != null && saved.boot() == boot) {
                long until = saved.blockedUntil();
                if (until < 0 || until > elapsed.getAsLong() + MAX_BLOCK_MS) until = 0;
                state = new Reservation(boot, until, normalized(saved.source()));
            } else {
                state = new Reservation(boot, 0, null);
            }
        }

        boolean blocksImport() { return state.blockedUntil() > elapsed.getAsLong(); }

        synchronized boolean begin(String uuid, long blockedUntil) {
            String source = normalized(uuid);
            long now = elapsed.getAsLong();
            if (boot < 0 || source == null || state.blockedUntil() > now || blockedUntil <= now
                    || blockedUntil > now + MAX_BLOCK_MS) return false;
            Reservation next = new Reservation(boot, blockedUntil, source);
            if (!store.save(next)) return false;
            state = next;
            return true;
        }

        synchronized void end(boolean mountMayArrive) {
            update(0, mountMayArrive ? state.source() : null);
        }

        synchronized boolean suppressMount(String uuid) {
            if (state.source() == null || !state.source().equals(normalized(uuid))) return false;
            update(state.blockedUntil(), null);
            return true;
        }

        synchronized void physicalDeparture(String uuid) {
            if (state.source() != null && state.source().equals(normalized(uuid))) {
                update(state.blockedUntil(), null);
            }
        }

        synchronized void registrationChanged(String uuid) {
            retainRegistered(source -> source.equals(normalized(uuid)));
        }

        synchronized void retainRegistered(Predicate<String> registered) {
            if (state.source() != null && !registered.test(state.source())) update(state.blockedUntil(), null);
        }

        private void update(long blockedUntil, String source) {
            Reservation next = new Reservation(boot, blockedUntil, source);
            if (next.equals(state)) return;
            // The live process has completion/event evidence even if the disk is temporarily unavailable.
            // A restart can retain the previous reservation only up to its original deadline.
            store.save(next);
            state = next;
        }
    }

    private static String normalized(String uuid) {
        return uuid != null && UUID.matcher(uuid).matches() ? uuid.replace("-", "").toLowerCase(Locale.ROOT) : null;
    }
}
