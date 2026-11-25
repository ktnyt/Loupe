package jp.nano.rawgallery.camera;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DirectUsbLeasePlanTest {
    private static final String ADDRESS = "/dev/bus/usb/002/002";
    private static final String SD_UUID = "1234-ABCD";
    private static final String MICRO_UUID = "0123-FEDC";
    private static final String SD = "public:8,97";
    private static final String MICRO = "public:8,113";

    public static void main(String[] args) throws Exception {
        readOnlyWhileBothSlotsAreStablyEjected();
        aLongReadLeavesTimeForRecovery();
        partialEjectStillRestoresKnownVolumes();
        unresolvedUnmountRetainsObligation();
        readFailureRestoresBeforeItIsReported();
        uncertainOwnershipRetainsObligationWithoutMount();
        journalFailurePreventsEject();
        uncertainUsbIdentityPreventsEject();
        changedRecoveryIdentityPreventsMount();
        exactReenumerationMayRecoverWithNewVolumeIds();
        kernelReprobeMayRecoverWithNewVolumeIds();
        ejectedKnownVolumeMayTemporarilyLoseUuid();
        failedClearLeavesRecoveryPending();
        System.out.println("DirectUsbLeasePlanTest passed");
    }

    private static void readOnlyWhileBothSlotsAreStablyEjected() {
        var fake = new Fake();
        expect(fake.run(() -> {
            if (fake.time < 250 || fake.obligation == null || !"unmounted".equals(fake.sdState)
                    || !"unmounted".equals(fake.microState)) throw new AssertionError("Reader not ready");
            fake.events.add("open");
            fake.open = true;
            fake.close();
        }), DirectUsbLeasePlan.Outcome.IMPORTED);
        fake.checkEvents("begin", "UNMOUNT " + SD, "UNMOUNT " + MICRO, "open", "close", "MOUNT " + MICRO,
                "MOUNT " + SD, "complete");
    }

    private static void aLongReadLeavesTimeForRecovery() {
        var fake = new Fake();
        expect(fake.run(() -> fake.time += DirectUsbLeasePlan.EJECT_MS * 3), DirectUsbLeasePlan.Outcome.IMPORTED);
    }

    private static void partialEjectStillRestoresKnownVolumes() {
        var fake = new Fake();
        fake.lateMicroEject = true;
        expect(fake.run(DirectUsbLeasePlanTest::notExpected), DirectUsbLeasePlan.Outcome.FAILED_RESTORED);
        fake.checkEvents("begin", "UNMOUNT " + SD, "UNMOUNT " + MICRO, "MOUNT " + SD, "MOUNT " + MICRO, "complete");
    }

    private static void unresolvedUnmountRetainsObligation() {
        var fake = new Fake();
        fake.stuckMicroEject = true;
        expect(fake.run(DirectUsbLeasePlanTest::notExpected), DirectUsbLeasePlan.Outcome.RECOVERY_PENDING);
        fake.checkEvents("begin", "UNMOUNT " + SD, "UNMOUNT " + MICRO, "MOUNT " + SD);
        if (fake.obligation == null) throw new AssertionError("Recovery obligation discarded");
    }

    private static void readFailureRestoresBeforeItIsReported() {
        var fake = new Fake();
        var error = new IOException("read failed");
        var result = fake.run(() -> {
            fake.open = true;
            try {
                throw error;
            } finally {
                fake.close();
            }
        });
        expect(result, DirectUsbLeasePlan.Outcome.FAILED_RESTORED);
        if (result.failure() != error) throw new AssertionError("Read error lost");
        fake.checkEvents("begin", "UNMOUNT " + SD, "UNMOUNT " + MICRO, "close", "MOUNT " + MICRO, "MOUNT " + SD,
                "complete");
    }

    private static void uncertainOwnershipRetainsObligationWithoutMount() throws Exception {
        var fake = new Fake();
        expect(fake.run(() -> {
            fake.open = true;
            throw new DirectUsbLeasePlan.OwnershipUnknownException(new IOException("read reply lost"));
        }), DirectUsbLeasePlan.Outcome.RECOVERY_PENDING);
        fake.checkEvents("begin", "UNMOUNT " + SD, "UNMOUNT " + MICRO);
        fake.close();
        if (!DirectUsbLeasePlan.recover(fake, fake, fake.obligation, fake.time + 5_000)) {
            throw new AssertionError("Confirmed close did not permit recovery");
        }
        fake.checkEvents("begin", "UNMOUNT " + SD, "UNMOUNT " + MICRO, "close", "MOUNT " + MICRO, "MOUNT " + SD,
                "complete");
    }

    private static void journalFailurePreventsEject() {
        var fake = new Fake();
        fake.failBegin = true;
        expect(fake.run(DirectUsbLeasePlanTest::notExpected), DirectUsbLeasePlan.Outcome.RECOVERY_PENDING);
        fake.checkEvents("begin");
    }

    private static void uncertainUsbIdentityPreventsEject() {
        var fake = new Fake();
        fake.authorized = false;
        expect(fake.run(DirectUsbLeasePlanTest::notExpected), DirectUsbLeasePlan.Outcome.SKIPPED);
        fake.checkEvents();
        fake.authorized = true;
        fake.microUuid = "0000-DEAD";
        expect(fake.run(DirectUsbLeasePlanTest::notExpected), DirectUsbLeasePlan.Outcome.SKIPPED);
        fake.checkEvents();
    }

    private static void changedRecoveryIdentityPreventsMount() {
        var fake = new Fake();
        expect(fake.run(() -> {
            fake.reenumerate();
            fake.sdUuid = "0000-DEAD";
        }), DirectUsbLeasePlan.Outcome.RECOVERY_PENDING);
        fake.checkEvents("begin", "UNMOUNT " + SD, "UNMOUNT " + MICRO);

        var moved = new Fake();
        expect(moved.run(() -> {
            moved.reenumerate();
            moved.port = "2-3";
        }), DirectUsbLeasePlan.Outcome.RECOVERY_PENDING);
        moved.checkEvents("begin", "UNMOUNT " + SD, "UNMOUNT " + MICRO);
    }

    private static void exactReenumerationMayRecoverWithNewVolumeIds() {
        var fake = new Fake();
        expect(fake.run(fake::reenumerate), DirectUsbLeasePlan.Outcome.IMPORTED);
        fake.checkEvents("begin", "UNMOUNT " + SD, "UNMOUNT " + MICRO, "MOUNT public:8,33", "MOUNT public:8,17", "complete");
    }

    private static void kernelReprobeMayRecoverWithNewVolumeIds() {
        var fake = new Fake();
        expect(fake.run(() -> {
            fake.sdDisk = 16;
            fake.microDisk = 32;
        }), DirectUsbLeasePlan.Outcome.IMPORTED);
        fake.checkEvents("begin", "UNMOUNT " + SD, "UNMOUNT " + MICRO, "MOUNT public:8,33", "MOUNT public:8,17", "complete");
    }

    private static void ejectedKnownVolumeMayTemporarilyLoseUuid() {
        var fake = new Fake();
        fake.hideEjectedUuid = true;
        expect(fake.run(() -> {}), DirectUsbLeasePlan.Outcome.IMPORTED);
        fake.checkEvents("begin", "UNMOUNT " + SD, "UNMOUNT " + MICRO, "MOUNT " + MICRO, "MOUNT " + SD, "complete");
    }

    private static void failedClearLeavesRecoveryPending() {
        var fake = new Fake();
        fake.failClear = true;
        expect(fake.run(() -> {}), DirectUsbLeasePlan.Outcome.RECOVERY_PENDING);
        fake.checkEvents("begin", "UNMOUNT " + SD, "UNMOUNT " + MICRO, "MOUNT " + MICRO, "MOUNT " + SD, "complete");
        if (fake.obligation == null) throw new AssertionError("Recovery obligation discarded");
    }

    private static void notExpected() {
        throw new AssertionError("Unexpected USB owner call");
    }

    private static void expect(DirectUsbLeasePlan.Result result, DirectUsbLeasePlan.Outcome outcome) {
        if (result.failure() instanceof AssertionError failure) throw failure;
        if (result.outcome() != outcome) throw new AssertionError("Expected " + outcome + ", got " + result);
    }

    private static final class Fake implements UsbReaderReconnectPlan.Transport, DirectUsbLeasePlan.Journal {
        private final List<String> events = new ArrayList<>();
        private long time;
        private long enumerations = 103;
        private String address = ADDRESS;
        private String port = "2-1";
        private int sdDisk = 96;
        private int microDisk = 112;
        private String sdState = "mounted";
        private String microState = "mounted";
        private String sdUuid = SD_UUID;
        private String microUuid = MICRO_UUID;
        private long microEjectAt = Long.MAX_VALUE;
        private boolean authorized = true;
        private boolean open;
        private boolean lateMicroEject;
        private boolean stuckMicroEject;
        private boolean failBegin;
        private boolean failClear;
        private boolean hideEjectedUuid;
        private DirectUsbLeasePlan.Obligation obligation;

        DirectUsbLeasePlan.Result run(DirectUsbLeasePlan.ScopedRead read) {
            return DirectUsbLeasePlan.execute(this, this, ADDRESS, SD_UUID, MICRO_UUID, DirectUsbLeasePlan.EJECT_MS, read);
        }

        void close() {
            open = false;
            events.add("close");
        }

        void reenumerate() {
            address = "/dev/bus/usb/002/003";
            enumerations += 2;
            sdDisk = 16;
            microDisk = 32;
        }

        void checkEvents(String... expected) {
            if (!events.equals(List.of(expected))) throw new AssertionError("Unexpected events: " + events);
        }

        @Override public void begin(DirectUsbLeasePlan.Obligation initial) throws IOException {
            events.add("begin");
            if (failBegin || obligation != null) throw new IOException("Could not claim recovery journal");
            obligation = initial;
        }

        @Override public void complete(DirectUsbLeasePlan.Obligation initial) throws IOException {
            events.add("complete");
            if (failClear || !initial.equals(obligation)) throw new IOException("Could not clear recovery journal");
            obligation = null;
        }

        @Override public boolean authorized() { return authorized; }
        @Override public long now() { return time; }

        @Override public void waitMs(long duration) {
            time += duration;
            if (time >= microEjectAt) {
                microState = "unmounted";
                microEjectAt = Long.MAX_VALUE;
            }
        }

        @Override public String usbDump(long deadline) {
            return "USB MANAGER STATE\n  host_manager={\n    devices={\n      name=" + address
                    + "\n      vendor_id=8644\n      product_id=45152\n    }\n    num_connects=" + enumerations + "\n  }\n"
                    + "  port_manager={\n    is_simulation_active=false\n    usb_ports={\n      port={\n        id=port0\n"
                    + "      }\n      status={\n        connected=true\n        current_mode=dfp\n        data_role=host\n"
                    + "        usb_data_status=enabled\n      }\n      connected_at_millis=349043812\n    }\n  }\n";
        }

        @Override public String listPublicVolumes(long deadline) {
            return publicId(sdDisk) + " " + sdState + " " + observedUuid(sdState, sdUuid) + "\n"
                    + publicId(microDisk) + " " + microState + " " + observedUuid(microState, microUuid) + "\n";
        }

        @Override public String mountDump(long deadline) {
            return "Disks:\n" + disk(sdDisk, 0, "sda") + disk(microDisk, 1, "sdb") + "\nVolumes:\n"
                    + volume(sdDisk, sdState, observedUuid(sdState, sdUuid))
                    + volume(microDisk, microState, observedUuid(microState, microUuid)) + "\nRecords:\n";
        }

        private String observedUuid(String state, String actual) {
            return hideEjectedUuid && "unmounted".equals(state) ? "null" : actual;
        }

        @Override public void change(SdCardRemountPlan.Action action, String id, long deadline) throws IOException {
            if (obligation == null || open) throw new AssertionError("Unaudited or concurrent storage write");
            events.add(action + " " + id);
            boolean unmount = action == SdCardRemountPlan.Action.UNMOUNT;
            if (unmount && id.equals(publicId(microDisk)) && (lateMicroEject || stuckMicroEject)) {
                if (lateMicroEject) microEjectAt = time + 500;
                throw new IOException("Unmount acknowledgement lost");
            }
            String state = unmount ? "unmounted" : "mounted";
            if (id.equals(publicId(microDisk))) microState = state;
            else if (id.equals(publicId(sdDisk))) sdState = state;
            else throw new AssertionError("Unknown id: " + id);
        }

        @Override public UsbReaderReconnectPlan.DataCycle armCycle(long deadline) { throw new AssertionError(); }
        @Override public void restoreUsbData(long deadline) { throw new AssertionError(); }

        private String disk(int disk, int lun, String block) {
            return "  DiskInfo{disk:8," + disk + "}:\n    flags=USB size=127865454592 label=Generic\n"
                    + "    sysPath=/sys/devices/platform/xhci/usb2/" + port + "/" + port + ":1.0"
                    + "/host1/target1:0:0/1:0:0:" + lun + "/block/" + block + "\n";
        }

        private static String volume(int disk, String state, String uuid) {
            return "  VolumeInfo{" + publicId(disk) + "}:\n    type=PUBLIC diskId=disk:8," + disk
                    + " mountUserId=0 state=" + state.toUpperCase(Locale.ROOT) + "\n"
                    + "    fsType=exfat fsUuid=" + uuid + " fsLabel=\n";
        }

        private static String publicId(int disk) { return "public:8," + (disk + 1); }
    }
}
