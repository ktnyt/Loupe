package jp.nano.rawgallery.camera;

import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Keeps an accepted remount separate from ordinary storage work and mount broadcasts. */
final class SdRemountMountNotice {
    static final long RESULT_GRACE_MS = 1_000;
    private static final long RECONNECT_NOTICE_MS = 3_000;
    private static final Pattern UUID = Pattern.compile("(?i)[0-9a-f]{4}-?[0-9a-f]{4}");
    private static volatile Tracker tracker;

    record Reservation(int boot, long blockedUntil, String source, long mutedUntil, String reconnectSource,
            boolean awaitingReconnectMount) {
        Reservation(int boot, long blockedUntil, String source) { this(boot, blockedUntil, source, 0, null, false); }
    }
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

    static boolean beginReconnect(String source, long blockedUntil) {
        Tracker current = tracker;
        return current != null && current.beginReconnect(source, blockedUntil);
    }

    static void endReconnect(boolean usbReenumerated, boolean awaitingMount) {
        Tracker current = tracker;
        if (current != null) current.endReconnect(usbReenumerated, awaitingMount);
    }

    static void end(boolean mountMayArrive) {
        Tracker current = tracker;
        if (current != null) current.end(mountMayArrive);
    }

    static boolean blocksImport() {
        Tracker current = tracker;
        return current != null && current.blocksImport();
    }

    static boolean suppressesReconnectNotices() {
        Tracker current = tracker;
        return current != null && current.suppressesReconnectNotices();
    }

    static boolean suppressMount(String uuid) {
        Tracker current = tracker;
        return current != null && current.suppressMount(uuid);
    }

    static void physicalDeparture(String uuid) {
        Tracker current = tracker;
        if (current != null) current.physicalDeparture(uuid);
    }

    static void reconnectPhysicalDeparture(String uuid) {
        Tracker current = tracker;
        if (current != null) current.reconnectPhysicalDeparture(uuid);
    }

    static void reconnectMountObserved(String uuid) {
        Tracker current = tracker;
        if (current != null) current.reconnectMountObserved(uuid);
    }

    static void registrationChanged(String uuid) {
        Tracker current = tracker;
        if (current != null) current.registrationChanged(uuid);
    }

    static final class Tracker {
        // The remote operation is independently bounded, but a submitted vold command is asynchronous.
        static final long MAX_BLOCK_MS =
                Math.max(SdCardRemountPlan.OPERATION_MS, UsbReaderReconnectPlan.OPERATION_MS) + RESULT_GRACE_MS;

        private final int boot;
        private final LongSupplier elapsed;
        private final Store store;
        private volatile Reservation state;
        private Reservation previousReconnect;

        Tracker(int boot, LongSupplier elapsed, Reservation saved, Store store) {
            this.boot = boot;
            this.elapsed = elapsed;
            this.store = store;
            if (saved != null && saved.boot() >= 0 && (boot < 0 || saved.boot() == boot)) {
                long latest = elapsed.getAsLong() + MAX_BLOCK_MS;
                long until = Math.max(0, Math.min(saved.blockedUntil(), latest));
                long mutedUntil = Math.max(0, Math.min(saved.mutedUntil(), latest + RECONNECT_NOTICE_MS));
                state = new Reservation(saved.boot(), until, normalized(saved.source()), mutedUntil,
                        normalized(saved.reconnectSource()), saved.awaitingReconnectMount());
                if (!state.equals(saved)) store.save(state);
            } else {
                state = new Reservation(boot, 0, null);
            }
        }

        boolean blocksImport() { return state.blockedUntil() > elapsed.getAsLong(); }

        synchronized boolean begin(String uuid, long blockedUntil) {
            String source = normalized(uuid);
            long now = elapsed.getAsLong();
            if (source == null) return false;
            return begin(new Reservation(boot, blockedUntil, source, state.mutedUntil(), state.reconnectSource(),
                    state.awaitingReconnectMount()), now);
        }

        synchronized boolean beginReconnect(String source, long blockedUntil) {
            Reservation previous = state;
            if (!begin(new Reservation(boot, blockedUntil, state.source(), blockedUntil + RECONNECT_NOTICE_MS,
                    normalized(source), source != null), elapsed.getAsLong())) return false;
            previousReconnect = previous;
            return true;
        }

        private boolean begin(Reservation next, long now) {
            if (boot < 0 || state.blockedUntil() > now || next.blockedUntil() <= now
                    || next.blockedUntil() > now + MAX_BLOCK_MS || !store.save(next)) return false;
            state = next;
            return true;
        }

        synchronized void endReconnect(boolean usbReenumerated, boolean awaitingMount) {
            long mutedUntil = usbReenumerated ? elapsed.getAsLong() + RECONNECT_NOTICE_MS
                    : previousReconnect == null ? 0 : previousReconnect.mutedUntil();
            // A late mount cannot be distinguished from an insertion until a subsequent departure is verified.
            String reconnectSource = usbReenumerated ? state.reconnectSource()
                    : previousReconnect == null ? null : previousReconnect.reconnectSource();
            boolean awaiting = reconnectSource != null && (usbReenumerated ? awaitingMount
                    : previousReconnect.awaitingReconnectMount());
            update(new Reservation(state.boot(), 0, state.source(), mutedUntil, reconnectSource, awaiting));
            previousReconnect = null;
        }

        synchronized void end(boolean mountMayArrive) {
            update(0, mountMayArrive ? state.source() : null);
        }

        boolean suppressesReconnectNotices() { return state.mutedUntil() > elapsed.getAsLong(); }

        synchronized boolean suppressMount(String uuid) {
            String observed = normalized(uuid);
            if (state.source() != null && state.source().equals(observed)) {
                update(state.blockedUntil(), null);
                return true;
            }
            return blocksImport() && suppressesReconnectNotices()
                    || state.reconnectSource() != null && state.reconnectSource().equals(observed);
        }

        synchronized void physicalDeparture(String uuid) {
            if (state.source() != null && state.source().equals(normalized(uuid))) {
                update(state.blockedUntil(), null);
            }
        }

        synchronized void reconnectPhysicalDeparture(String uuid) {
            if (!state.awaitingReconnectMount() && !blocksImport()
                    && state.reconnectSource() != null && state.reconnectSource().equals(normalized(uuid))) {
                update(new Reservation(state.boot(), state.blockedUntil(), state.source(), state.mutedUntil(), null, false));
            }
        }

        synchronized void reconnectMountObserved(String uuid) {
            if (state.awaitingReconnectMount() && !blocksImport() && state.reconnectSource() != null
                    && state.reconnectSource().equals(normalized(uuid))) {
                update(new Reservation(state.boot(), state.blockedUntil(), state.source(), state.mutedUntil(),
                        state.reconnectSource(), false));
            }
        }

        synchronized void registrationChanged(String uuid) {
            retainRegistered(source -> source.equals(normalized(uuid)));
        }

        synchronized void retainRegistered(Predicate<String> registered) {
            String source = state.source();
            String reconnectSource = state.reconnectSource();
            boolean retainReconnect = reconnectSource != null && registered.test(reconnectSource);
            update(new Reservation(state.boot(), state.blockedUntil(),
                    source != null && registered.test(source) ? source : null, state.mutedUntil(),
                    retainReconnect ? reconnectSource : null, retainReconnect && state.awaitingReconnectMount()));
        }

        private void update(long blockedUntil, String source) {
            update(new Reservation(state.boot(), blockedUntil, source, state.mutedUntil(), state.reconnectSource(),
                    state.awaitingReconnectMount()));
        }

        private void update(Reservation next) {
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
