package jp.nano.rawgallery.camera;

public final class SdRemountReservationTest {
    private static final int BOOT = 7;
    private static final String SOURCE = "ABCD-0123";
    private static final String OTHER = "DEAD-1234";

    public static void main(String[] args) {
        restoresAndExpiresAcrossProcessRestart();
        ignoresOtherBoots();
        keepsBoundWhenCurrentBootCannotBeRead();
        refusesDispatchWhenReservationCannotBeSaved();
        keepsOneExactNoticeAcrossCompletionAndRestart();
        clearsNoticeOnSkippedRemountOrStaleRegistration();
        keepsBoundAfterFailedCompletionSave();
        reconnectMutesLateNoticesWithoutHoldingTheGate();
        reconnectSourceWaitsForRealDeparture();
        missingReconnectSourceMutesAnAmbiguousLateMount();
        aDifferentRegisteredCardCanBeTheOneThatReturns();
        aSkippedRetryPreservesTheUnresolvedReservation();
        aVerifiedSourceTakesPrecedenceOverLateReports();
        System.out.println("SdRemountReservationTest passed");
    }

    private static void restoresAndExpiresAcrossProcessRestart() {
        var store = new FakeStore();
        var initial = store.open(BOOT);
        require(initial.begin(SOURCE, 8000), "persist before accepting work");
        require(initial.blocksImport(), "the live reservation holds the gate");
        require(store.saved.source().equals("abcd0123"), "persist normalized source marker");

        store.now = 2000;
        var restarted = store.open(BOOT);
        require(restarted.blocksImport(), "the same boot restores the reservation");
        require(!restarted.begin(OTHER, 16000), "a second remount must wait");
        require(!restarted.suppressMount(OTHER), "an unrelated mount does not consume the notice");
        require(restarted.suppressMount(SOURCE), "the exact mount consumes the notice while active");
        require(!restarted.suppressMount(SOURCE), "the notice is one-shot while active");
        require(store.open(BOOT).blocksImport(), "consuming the notice preserves the deadline");
        require(!store.open(BOOT).suppressMount(SOURCE), "consumed notice stays cleared after restarting");
        store.now = 8000;
        require(!restarted.blocksImport() && !store.open(BOOT).blocksImport(), "the gate expires at the deadline");
    }

    private static void ignoresOtherBoots() {
        var store = new FakeStore();
        require(store.open(BOOT).begin(SOURCE, 8000), "persist boot-scoped state");
        var rebooted = store.open(BOOT + 1);
        require(!rebooted.blocksImport() && !rebooted.suppressMount(SOURCE), "old boot is ignored");
    }

    private static void keepsBoundWhenCurrentBootCannotBeRead() {
        var store = new FakeStore();
        require(store.open(BOOT).begin(SOURCE, 8000), "persist a known-boot reservation");
        store.now = 2000;
        var unknown = store.open(-1);
        require(unknown.blocksImport() && !unknown.begin(OTHER, 9000), "missing boot value still blocks existing work");
        require(unknown.suppressMount(SOURCE) && !store.open(BOOT).suppressMount(SOURCE),
                "consumed source marker stays cleared and the known boot identity is retained");
        require(store.open(BOOT).blocksImport(), "consuming a notice cannot invalidate the known boot record");
        store.now = 8000;
        require(!unknown.blocksImport() && !unknown.begin(OTHER, 9000), "unknown boot cannot start fresh work");

        store.now = 100_000;
        require(store.open(BOOT).begin(SOURCE, 120_000), "persist before an ambiguous clock reset");
        store.now = 1000;
        var capped = store.open(-1);
        require(capped.blocksImport(), "conservatively respect a short bound after an ambiguous reset");
        store.now += SdRemountMountNotice.Tracker.MAX_BLOCK_MS;
        require(!capped.blocksImport() && !store.open(-1).blocksImport(), "the cap survives another process start");
    }

    private static void refusesDispatchWhenReservationCannotBeSaved() {
        var store = new FakeStore();
        var tracker = store.open(BOOT);
        store.available = false;
        require(!tracker.begin(SOURCE, 8000), "a failed write must prevent dispatch");
        require(!tracker.blocksImport() && !tracker.suppressMount(SOURCE), "no in-memory state after failed begin");
        require(store.saved == null, "no durable state after failed begin");
    }

    private static void keepsOneExactNoticeAcrossCompletionAndRestart() {
        var store = new FakeStore();
        var tracker = store.open(BOOT);
        require(tracker.begin(SOURCE, 8000), "persist in-flight notice");
        tracker.end(true);
        store.now = 80_000;
        var restarted = store.open(BOOT);
        require(!restarted.blocksImport(), "confirmed completion releases the gate");
        require(!restarted.suppressMount(OTHER), "destination mount is unaffected");
        require(restarted.suppressMount("aBcD0123"), "the pending notice survives restart without a TTL");
        require(!store.open(BOOT).suppressMount(SOURCE), "the consumed notice stays cleared");
    }

    private static void clearsNoticeOnSkippedRemountOrStaleRegistration() {
        var store = new FakeStore();
        var tracker = store.open(BOOT);
        require(tracker.begin(SOURCE, 8000), "begin skipped scenario");
        tracker.end(false);
        require(!store.open(BOOT).blocksImport() && !store.open(BOOT).suppressMount(SOURCE),
                "known no-command outcome clears gate and notice");

        require(tracker.begin(SOURCE, 8000), "begin departure scenario");
        tracker.physicalDeparture(OTHER);
        require(tracker.suppressMount(SOURCE), "a different card's departure does not clear the notice");
        tracker.end(true);
        require(tracker.begin(SOURCE, 8000), "begin exact departure scenario");
        tracker.physicalDeparture(SOURCE);
        require(!store.open(BOOT).suppressMount(SOURCE), "the same card's departure clears the notice");
        tracker.end(true);

        require(tracker.begin(SOURCE, 8000), "begin changed registration scenario");
        var restarted = store.open(BOOT);
        restarted.registrationChanged(OTHER);
        require(!store.open(BOOT).suppressMount(SOURCE), "changed registration clears the old notice");
        require(restarted.blocksImport(), "changing registration cannot release an unresolved reservation");
    }

    private static void keepsBoundAfterFailedCompletionSave() {
        var store = new FakeStore();
        var tracker = store.open(BOOT);
        require(tracker.begin(SOURCE, 8000), "persist operation before dispatch");
        store.available = false;
        tracker.end(false);
        require(!tracker.blocksImport(), "known no-command completion releases the live gate");
        require(store.open(BOOT).blocksImport(), "the previous durable reservation is conservative after restart");
        store.now = 8000;
        require(!store.open(BOOT).blocksImport(), "the conservative reservation can never outlive its deadline");
    }

    private static void reconnectMutesLateNoticesWithoutHoldingTheGate() {
        var store = new FakeStore();
        var tracker = store.open(BOOT);
        require(tracker.beginReconnect(null, 12_000), "reserve a reconnect without a registered source");
        require(store.open(BOOT).blocksImport(), "a restart preserves the active storage reservation");
        require(store.open(BOOT).suppressMount(SOURCE) && store.open(BOOT).suppressMount(OTHER),
                "mount broadcasts from both slots stay muted");
        tracker.endReconnect(true, false);
        require(!store.open(BOOT).blocksImport(), "manual operation can resume after reconnect");
        require(store.open(BOOT).suppressesReconnectNotices(),
                "delayed USB events remain muted across restart");
        store.now += 3_001;
        require(!store.open(BOOT).suppressesReconnectNotices() && !store.open(BOOT).suppressMount(OTHER),
                "later external events are handled normally");
        require(tracker.beginReconnect(null, 12_000), "start another reconnect");
        tracker.endReconnect(false, false);
        require(!store.open(BOOT).suppressesReconnectNotices(), "a known skipped reconnect does not mute later events");
    }

    private static void reconnectSourceWaitsForRealDeparture() {
        var store = new FakeStore();
        var tracker = store.open(BOOT);
        require(tracker.beginReconnect(SOURCE, 12_000), "reserve the registered source");
        tracker.physicalDeparture(SOURCE);
        tracker.reconnectPhysicalDeparture(SOURCE);
        tracker.endReconnect(true, false);
        store.now += 10_000;
        tracker = store.open(BOOT);
        require(!tracker.blocksImport() && tracker.suppressMount(SOURCE) && tracker.suppressMount(SOURCE),
                "the same source stays muted beyond the grace period, across restart and duplicated mount broadcasts");
        require(!tracker.suppressMount(OTHER), "an unrelated mount is no longer muted");
        require(tracker.beginReconnect(SOURCE, 20_000), "start another reconnect");
        tracker.endReconnect(false, false);
        require(store.open(BOOT).suppressMount(SOURCE), "a skipped reconnect preserves prior source suppression");
        tracker.reconnectPhysicalDeparture(OTHER);
        require(store.open(BOOT).suppressMount(SOURCE), "another card cannot unlock the suppression");
        tracker.reconnectPhysicalDeparture(SOURCE);
        require(!store.open(BOOT).suppressMount(SOURCE), "verified physical departure clears the matching source");
    }

    private static void missingReconnectSourceMutesAnAmbiguousLateMount() {
        var store = new FakeStore();
        var tracker = store.open(BOOT);
        require(tracker.beginReconnect(SOURCE, 12_000), "reserve the registered source");
        tracker.endReconnect(true, true);
        require(store.open(BOOT).suppressesReconnectNotices(), "a delayed USB attach remains suppressed");
        store.now += 500;
        tracker.reconnectPhysicalDeparture(SOURCE);
        store.now += 10_000;
        require(!store.open(BOOT).blocksImport() && store.open(BOOT).suppressMount(SOURCE),
                "late removal and mount when the source had not returned do not start an import");
        tracker = store.open(BOOT);
        tracker.reconnectMountObserved(SOURCE);
        require(store.open(BOOT).suppressMount(SOURCE), "the first verified mount is also muted");
        tracker.reconnectPhysicalDeparture(SOURCE);
        require(!store.open(BOOT).suppressMount(SOURCE), "a subsequently verified physical departure returns to auto import");
    }

    private static void aDifferentRegisteredCardCanBeTheOneThatReturns() {
        var store = new FakeStore();
        var tracker = store.open(BOOT);
        require(tracker.beginReconnect(SdRemountMountNotice.ANY_SOURCE, 12_000),
                "preserve the gate without guessing which registered card will return");
        tracker.reconnectReaderDeparted();
        require(tracker.suppressMount(OTHER), "a synthetic detach during reconnect cannot clear the notice");
        tracker.endReconnect(true, true);
        store.now += 10_000;
        tracker = store.open(BOOT);
        require(!tracker.blocksImport() && tracker.suppressMount(SOURCE) && tracker.suppressMount(OTHER),
                "a late notice for either known card is muted without blocking a manual selection");
        tracker.reconnectPhysicalDeparture(OTHER);
        require(tracker.suppressMount(OTHER), "a delayed removal of an assumed card does not clear the notice");
        tracker.reconnectMountObserved(OTHER);
        tracker = store.open(BOOT);
        require(tracker.suppressMount(SOURCE) && tracker.suppressMount(OTHER),
                "an unrelated mount cannot identify the missing source slot");
        tracker.retainRegistered(uuid -> SdRemountMountNotice.ANY_SOURCE.equals(uuid)
                || SOURCE.equals(uuid) || OTHER.equals(uuid));
        require(store.open(BOOT).suppressMount(OTHER), "retaining existing registrations keeps the unresolved notice");
        tracker.reconnectReaderDeparted();
        require(!store.open(BOOT).suppressMount(SOURCE), "a verified reader removal clears the ambiguous notice");
    }

    private static void aVerifiedSourceTakesPrecedenceOverLateReports() {
        var store = new FakeStore();
        var tracker = store.open(BOOT);
        require(tracker.beginReconnect(SdRemountMountNotice.ANY_SOURCE, 12_000), "accept a multi-card remount");
        tracker.endReconnect(true, false, OTHER);
        tracker.reconnectMountObserved(SOURCE);
        require(!tracker.suppressMount(SOURCE) && tracker.suppressMount(OTHER),
                "a stale app-side observation cannot override the verified UUID");
        tracker.reconnectPhysicalDeparture(OTHER);
        require(!store.open(BOOT).suppressMount(SOURCE), "a later genuine insertion is not lost");
    }

    private static void aSkippedRetryPreservesTheUnresolvedReservation() {
        var store = new FakeStore();
        require(store.open(BOOT).beginReconnect(SdRemountMountNotice.ANY_SOURCE, 12_000), "start unresolved remount");
        store.now = 12_001;
        var tracker = store.open(BOOT);
        require(tracker.beginReconnect(SdRemountMountNotice.ANY_SOURCE, 24_000), "retry after the previous deadline");
        tracker.endReconnect(false, false);
        tracker.reconnectReaderDeparted();
        require(!tracker.blocksImport() && store.open(BOOT).suppressMount(SOURCE),
                "skipping the retry does not turn the unresolved reservation into a completed reconnect");
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static final class FakeStore implements SdRemountMountNotice.Store {
        long now = 1000;
        boolean available = true;
        SdRemountMountNotice.Reservation saved;

        SdRemountMountNotice.Tracker open(int boot) {
            return new SdRemountMountNotice.Tracker(boot, () -> now, saved, this);
        }

        @Override public boolean save(SdRemountMountNotice.Reservation next) {
            if (!available) return false;
            saved = next;
            return true;
        }
    }
}
