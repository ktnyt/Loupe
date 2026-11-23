package jp.nano.rawgallery.camera;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class SdCardRemountPlanTest {
    private static final String SOURCE_UUID = "0123-4567";
    private static final String DESTINATION_UUID = "ABCD-1234";
    private static final String SOURCE_ID = "public:8,1";
    private static final String DESTINATION_ID = "public:8,17";
    private static final String SOURCE_MOUNTED = SOURCE_ID + " mounted " + SOURCE_UUID;
    private static final String SOURCE_UNMOUNTED = SOURCE_ID + " unmounted null";
    private static final String SOURCE_REMOUNTED = SOURCE_ID + " mounted 7654-3210";
    private static final String DESTINATION_MOUNTED = DESTINATION_ID + " mounted " + DESTINATION_UUID;
    private static final String UNMOUNT_SOURCE = "UNMOUNT " + SOURCE_ID;
    private static final String MOUNT_SOURCE = "MOUNT " + SOURCE_ID;

    public static void main(String[] args) {
        rejectsUnmatchedOrAmbiguousVolumes();
        remountsOnlyPinnedSourceWithChangedUuid();
        refusesMutationAfterDestinationChanges();
        refusesMountWhenAnUnknownCardAppearsInTheSourceSlot();
        recoversAfterUncertainUnmount();
        recoversAfterMountFailure();
        System.out.println("SdCardRemountPlanTest passed");
    }

    private static void rejectsUnmatchedOrAmbiguousVolumes() {
        for (String listing : List.of(
                SOURCE_MOUNTED + "\n" + DESTINATION_ID + " mounted 1111-2222",
                SOURCE_MOUNTED + "\n" + DESTINATION_ID + " mounted 01234567",
                SOURCE_MOUNTED + "\n" + SOURCE_ID + " mounted " + DESTINATION_UUID,
                SOURCE_MOUNTED + "\n" + DESTINATION_ID + " unmounted " + DESTINATION_UUID,
                SOURCE_MOUNTED + "\n" + DESTINATION_MOUNTED + "\npublic:8,33 unmounted null",
                SOURCE_MOUNTED + "\n" + DESTINATION_MOUNTED + "\nunexpected output",
                SOURCE_MOUNTED + "\n" + DESTINATION_MOUNTED.replace("8,17", "8,17;"))) {
            var storage = new FakeStorage(listing);
            expect(storage.execute(), SdCardRemountPlan.Outcome.SKIPPED);
            expect(storage.commands, List.of());
        }
        var storage = new FakeStorage(list(SOURCE_MOUNTED));
        expect(SdCardRemountPlan.execute(storage, "abcd1234", DESTINATION_UUID, deadline()),
                SdCardRemountPlan.Outcome.SKIPPED);
        expect(storage.commands, List.of());
    }

    private static void remountsOnlyPinnedSourceWithChangedUuid() {
        var storage = new FakeStorage(list(SOURCE_MOUNTED), list(SOURCE_ID + " ejecting " + SOURCE_UUID),
                list(SOURCE_UNMOUNTED), list(SOURCE_ID + " checking null"), list(SOURCE_REMOUNTED));
        expect(storage.execute(), SdCardRemountPlan.Outcome.MOUNTED);
        expect(storage.commands, List.of(UNMOUNT_SOURCE, MOUNT_SOURCE));
    }

    private static void refusesMutationAfterDestinationChanges() {
        for (String changed : List.of(DESTINATION_ID + " unmounted " + DESTINATION_UUID,
                "public:8,33 mounted " + DESTINATION_UUID,
                DESTINATION_ID + " mounted 1111-2222")) {
            var storage = new FakeStorage(list(SOURCE_MOUNTED), SOURCE_UNMOUNTED + "\n" + changed);
            expect(storage.execute(), SdCardRemountPlan.Outcome.FAILED);
            expect(storage.commands, List.of(UNMOUNT_SOURCE));
        }
        var storage = new FakeStorage(list(SOURCE_MOUNTED), list(SOURCE_UNMOUNTED),
                SOURCE_REMOUNTED + "\n" + DESTINATION_ID + " unmounted " + DESTINATION_UUID);
        expect(storage.execute(), SdCardRemountPlan.Outcome.FAILED);
        expect(storage.commands, List.of(UNMOUNT_SOURCE, MOUNT_SOURCE));
    }

    private static void refusesMountWhenAnUnknownCardAppearsInTheSourceSlot() {
        var storage = new FakeStorage(list(SOURCE_MOUNTED), list(SOURCE_ID + " unmounted 1111-2222"));
        expect(storage.execute(), SdCardRemountPlan.Outcome.FAILED);
        expect(storage.commands, List.of(UNMOUNT_SOURCE));
    }

    private static void recoversAfterUncertainUnmount() {
        var storage = new FakeStorage(list(SOURCE_MOUNTED), list(SOURCE_MOUNTED),
                list(SOURCE_UNMOUNTED), list(SOURCE_REMOUNTED));
        storage.failOnCommand = 1;
        expect(storage.execute(), SdCardRemountPlan.Outcome.FAILED);
        expect(storage.commands, List.of(UNMOUNT_SOURCE, MOUNT_SOURCE));
        expect(storage.listings.size(), 0);
    }

    private static void recoversAfterMountFailure() {
        var storage = new FakeStorage(list(SOURCE_MOUNTED), list(SOURCE_UNMOUNTED),
                list(SOURCE_UNMOUNTED), list(SOURCE_REMOUNTED));
        storage.failOnCommand = 2;
        expect(storage.execute(), SdCardRemountPlan.Outcome.FAILED);
        expect(storage.commands, List.of(UNMOUNT_SOURCE, MOUNT_SOURCE, MOUNT_SOURCE));
        expect(storage.listings.size(), 0);
    }

    private static String list(String source) { return DESTINATION_MOUNTED + "\n" + source + "\n"; }
    private static long deadline() { return SdCardRemountPlan.OPERATION_MS; }

    private static void expect(Object actual, Object expected) {
        if (!actual.equals(expected)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    private static final class FakeStorage implements SdCardRemountPlan.Storage {
        final ArrayDeque<String> listings;
        final List<String> commands = new ArrayList<>();
        String lastListing;
        int failOnCommand;
        long elapsed;

        FakeStorage(String... listings) { this.listings = new ArrayDeque<>(List.of(listings)); }
        SdCardRemountPlan.Outcome execute() {
            return SdCardRemountPlan.execute(this, SOURCE_UUID, DESTINATION_UUID, deadline());
        }
        @Override public String listPublicVolumes(long deadline) {
            if (!listings.isEmpty()) lastListing = listings.remove();
            return lastListing;
        }
        @Override public void change(SdCardRemountPlan.Action action, String id, long deadline) throws IOException {
            commands.add(action + " " + id);
            if (commands.size() == failOnCommand) throw new IOException("Synthetic command timeout");
        }
        @Override public long now() { return elapsed; }
        @Override public void waitMs(long duration) { elapsed += duration; }
    }
}
