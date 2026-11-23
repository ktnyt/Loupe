package jp.nano.rawgallery.camera;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SdCardRemountPlanTest {
    private static final String SOURCE_UUID = "0123-4567";
    private static final String DESTINATION_UUID = "ABCD-1234";
    private static final String SOURCE_ID = "public:8,1";
    private static final String DESTINATION_ID = "public:8,17";
    private static final String SOURCE_MOUNTED = SOURCE_ID + " mounted " + SOURCE_UUID;
    private static final String SOURCE_UNMOUNTED = SOURCE_ID + " unmounted null";
    private static final String SOURCE_REMOUNTED = SOURCE_ID + " mounted 7654-3210";
    private static final String DESTINATION_MOUNTED = DESTINATION_ID + " mounted " + DESTINATION_UUID;
    private static final String SOURCE_DISK = "disk:8,0";
    private static final String DESTINATION_DISK = "disk:8,16";
    private static final String USB_PATH = "/sys//devices/platform/soc/usb2/2-1/2-1:1.0";
    private static final String SOURCE_PATH = USB_PATH + "/host1/target1:0:0/1:0:0:0/block/sda";
    private static final String DESTINATION_PATH = USB_PATH + "/host1/target1:0:0/1:0:0:1/block/sdb";
    private static final String UNMOUNT_SOURCE = "UNMOUNT " + SOURCE_ID;
    private static final String MOUNT_SOURCE = "MOUNT " + SOURCE_ID;

    public static void main(String[] args) {
        rejectsUnmatchedOrAmbiguousVolumes();
        rejectsUnknownDiskTopology();
        refusesReusedPublicVolumeId();
        remountsOnlyPinnedSourceWithChangedUuid();
        waitsForMatchingSnapshotsBeforeMounting();
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

    private static void rejectsUnknownDiskTopology() {
        for (String dump : List.of(
                mountDump(list(SOURCE_MOUNTED)).replace(SOURCE_PATH, SOURCE_PATH.replace("/2-1", "/2-2")),
                mountDump(list(SOURCE_MOUNTED)).replace("flags=USB", "flags=SD"),
                mountDump(list(SOURCE_MOUNTED)).replace("diskId=" + SOURCE_DISK, "diskId=" + DESTINATION_DISK),
                mountDump(list(SOURCE_MOUNTED)).replace("sysPath=" + SOURCE_PATH, "sysPath=null"),
                mountDump(list(SOURCE_MOUNTED)).replace("Volumes:", "Missing volume section:"),
                mountDump(list(SOURCE_MOUNTED) + "public:8,33 mounted 1111-2222\n"),
                mountDump(list(SOURCE_MOUNTED)).replace("diskId=" + SOURCE_DISK, "diskId=disk:8,48"))) {
            var storage = new FakeStorage(list(SOURCE_MOUNTED));
            storage.dumps.add(dump);
            expect(storage.execute(), SdCardRemountPlan.Outcome.SKIPPED);
            expect(storage.commands, List.of());
        }
        var storage = new FakeStorage(list(SOURCE_MOUNTED));
        storage.dumps.add(mountDump(list(SOURCE_MOUNTED)).replace("fsUuid=" + DESTINATION_UUID, "fsUuid=1111-1111"));
        expect(storage.execute(), SdCardRemountPlan.Outcome.FAILED);
        expect(storage.commands, List.of());
    }

    private static void refusesReusedPublicVolumeId() {
        var storage = new FakeStorage(list(SOURCE_MOUNTED), list(SOURCE_UNMOUNTED));
        String changed = mountDump(list(SOURCE_UNMOUNTED)).replace(SOURCE_PATH,
                SOURCE_PATH.replace("1:0:0:0/block/sda", "1:0:0:2/block/sdc"));
        storage.dumps.add(mountDump(list(SOURCE_MOUNTED)));
        storage.dumps.add(changed);
        storage.dumps.add(changed);
        expect(storage.execute(), SdCardRemountPlan.Outcome.FAILED);
        expect(storage.commands, List.of(UNMOUNT_SOURCE));
    }

    private static void remountsOnlyPinnedSourceWithChangedUuid() {
        var storage = new FakeStorage(list(SOURCE_MOUNTED), list(SOURCE_ID + " ejecting " + SOURCE_UUID),
                list(SOURCE_UNMOUNTED), list(SOURCE_ID + " checking null"), list(SOURCE_REMOUNTED));
        expect(storage.execute(), SdCardRemountPlan.Outcome.MOUNTED);
        expect(storage.commands, List.of(UNMOUNT_SOURCE, MOUNT_SOURCE));
    }

    private static void waitsForMatchingSnapshotsBeforeMounting() {
        var storage = new FakeStorage(list(SOURCE_MOUNTED), list(SOURCE_UNMOUNTED),
                list(SOURCE_UNMOUNTED), list(SOURCE_REMOUNTED));
        storage.dumps.add(mountDump(list(SOURCE_MOUNTED)));
        storage.dumps.add(mountDump(list(SOURCE_ID + " ejecting " + SOURCE_UUID)));
        expect(storage.execute(), SdCardRemountPlan.Outcome.MOUNTED);
        expect(storage.commands, List.of(UNMOUNT_SOURCE, MOUNT_SOURCE));
        expect(storage.elapsed, 250L);
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

    private static String mountDump(String listing) {
        StringBuilder dump = new StringBuilder("Disks:\n");
        dump.append(disk(SOURCE_DISK, SOURCE_PATH)).append(disk(DESTINATION_DISK, DESTINATION_PATH));
        dump.append("\nVolumes:\n")
                .append("  VolumeInfo{emulated;0}:\n    type=EMULATED diskId=null state=MOUNTED \n");
        for (String row : listing.split("\\n")) {
            String[] fields = row.split(" ");
            String disk = SOURCE_ID.equals(fields[0]) ? SOURCE_DISK : DESTINATION_DISK;
            dump.append("  VolumeInfo{").append(fields[0]).append("}:\n    type=PUBLIC diskId=")
                    .append(disk).append(" partGuid= mountFlags=VISIBLE_FOR_WRITE mountUserId=0 state=")
                    .append(fields[1].toUpperCase(Locale.ROOT)).append(" \n    fsType=exfat fsUuid=")
                    .append(fields[2]).append(" fsLabel=Generic \n    path=null internalPath=null \n");
        }
        return dump.append("\nRecords:\n").toString();
    }

    private static String disk(String id, String path) {
        return "  DiskInfo{" + id + "}:\n    flags=USB size=64054362112 label=Generic \n    sysPath=" + path + " \n";
    }

    private static final class FakeStorage implements SdCardRemountPlan.Storage {
        final ArrayDeque<String> listings;
        final ArrayDeque<String> dumps = new ArrayDeque<>();
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
        @Override public String mountDump(long deadline) {
            return dumps.isEmpty() ? SdCardRemountPlanTest.mountDump(lastListing) : dumps.remove();
        }
        @Override public void change(SdCardRemountPlan.Action action, String id, long deadline) throws IOException {
            commands.add(action + " " + id);
            if (commands.size() == failOnCommand) throw new IOException("Synthetic command timeout");
        }
        @Override public long now() { return elapsed; }
        @Override public void waitMs(long duration) { elapsed += duration; }
    }
}
