package jp.nano.rawgallery.camera;

public final class CameraBleRecoveryTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        CameraBleRecovery warm = new CameraBleRecovery(100);
        check(warm.start(false));
        check(!warm.disconnected()); // A failure before connection is never warm success.
        check(!warm.settled());
        check(!warm.advertisement(true, true));
        check(warm.connected());
        check(!warm.connected()); // No re-entry or repeated warm attempt.
        check(warm.disconnected());
        check(!warm.disconnected()); // A late disconnect cannot progress settling.
        check(warm.settled());
        check(warm.advertisement(true, true));
        check(warm.phase == CameraBleRecovery.Phase.NORMAL);
        check(!warm.start(false));
        check(!warm.connected());
        check(!warm.advertisement(true, true));
        for (boolean same : new boolean[]{false, true}) {
            for (boolean on : new boolean[]{false, true}) {
                if (same && on) continue;
                CameraBleRecovery rejected = new CameraBleRecovery(0);
                rejected.start(false); rejected.connected(); rejected.disconnected(); rejected.settled();
                check(!rejected.advertisement(same, on));
                check(rejected.phase == CameraBleRecovery.Phase.DONE);
                check(!rejected.advertisement(true, true));
            }
        }
        // Cancellation/timeout in every phase is terminal, including late callbacks.
        for (int steps = 0; steps < 6; steps++) {
            CameraBleRecovery cancelled = new CameraBleRecovery(0);
            if (steps > 0) cancelled.start(false);
            if (steps > 1) cancelled.connected();
            if (steps > 2) cancelled.disconnected();
            if (steps > 3) cancelled.settled();
            if (steps > 4) cancelled.advertisement(true, true);
            cancelled.finish();
            check(!cancelled.start(false) && !cancelled.connected() && !cancelled.disconnected()
                    && !cancelled.settled() && !cancelled.advertisement(true, true));
        }
        CameraBleRecovery direct = new CameraBleRecovery(100);
        check(direct.start(true));
        check(direct.phase == CameraBleRecovery.Phase.NORMAL);
        check(!direct.connected() && !direct.disconnected() && !direct.settled());
        check(direct.remaining(100, 20000) == 20000);
        check(direct.remaining(80100, 30000) == 10000);
        check(direct.remaining(90099, 15000) == 1);
        check(direct.remaining(90100, 3000) == 0);
        check(direct.remaining(100000, 10000) == 0);
        System.out.println("CameraBleRecoveryTest passed");
    }
}
