package jp.nano.rawgallery.camera;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class UsbReaderReconnectPlanTest {
    private static final String READER = "/dev/bus/usb/002/002";
    private static final String SD_ID = "public:8,97";
    private static final String MICRO_ID = "public:8,113";
    private static final String SD_UUID = "1234-ABCD";
    private static final String MICRO_UUID = "0123-FEDC";
    private static final String SYS_PATH = "/sys//devices/platform/xhci/usb2/2-1/2-1:1.0";

    public static void main(String[] args) {
        bothCardsReturnOnTheSameReader();
        onlyExistingVolumesAreEjected();
        aMissingSourceCanBecomeVisible();
        sourceCanBeMissingAfterReenumeration();
        refusesConflictingUsbOrStorageTopology();
        refusesDifferentOrChangedDestination();
        doesNotDisableBeforeBothEjectsAreVerified();
        restoresDataWhenDisableReportsFailure();
        restoresDataWhenCallerDisappearsDuringDisconnect();
        refusesDataSwitchWithoutArmedCycle();
        unknownCycleCompletionDoesNotStartASecondUsbRestore();
        retriesAKnownUnmountedDestination();
        retriesMountOnlyAfterRecheckingTheSameVolume();
        ambiguousReturnDoesNotRemountOtherCards();
        recoversOnlyTheDestinationWhenTheSourceWasReplaced();
        System.out.println("UsbReaderReconnectPlanTest passed");
    }

    private static void bothCardsReturnOnTheSameReader() {
        var usb = new Fake();
        assertOutcome(usb, UsbReaderReconnectPlan.Outcome.RECONNECTED);
        writes(usb, "UNMOUNT " + SD_ID, "UNMOUNT " + MICRO_ID, "data false", "data true");
    }

    private static void onlyExistingVolumesAreEjected() {
        var usb = new Fake();
        usb.sd = null;
        assertOutcome(usb, UsbReaderReconnectPlan.Outcome.SOURCE_MISSING);
        writes(usb, "UNMOUNT " + MICRO_ID, "data false", "data true");
    }

    private static void aMissingSourceCanBecomeVisible() {
        var usb = new Fake();
        usb.sd = null;
        usb.sourceAppears = true;
        assertOutcome(usb, UsbReaderReconnectPlan.Outcome.RECONNECTED);
        writes(usb, "UNMOUNT " + MICRO_ID, "data false", "data true");
    }

    private static void sourceCanBeMissingAfterReenumeration() {
        var usb = new Fake();
        usb.sourceDisappearsOnEnable = true;
        assertOutcome(usb, UsbReaderReconnectPlan.Outcome.SOURCE_MISSING);
        writes(usb, "UNMOUNT " + SD_ID, "UNMOUNT " + MICRO_ID, "data false", "data true");
    }

    private static void refusesConflictingUsbOrStorageTopology() {
        for (String conflict : new String[]{"simulation", "wrong device", "extra device", "extra port",
                "other disk", "other storage", "split target", "duplicate volume", "wrong bus", "behind hub",
                "different list", "target swapped"}) {
            var usb = new Fake();
            usb.conflict = conflict;
            assertOutcome(usb, UsbReaderReconnectPlan.Outcome.SKIPPED);
            writes(usb);
        }
    }

    private static void refusesDifferentOrChangedDestination() {
        var usb = new Fake();
        var outcome = UsbReaderReconnectPlan.execute(usb, READER, SD_UUID, UsbReaderReconnectPlan.OPERATION_MS);
        if (outcome != UsbReaderReconnectPlan.Outcome.SKIPPED) throw new AssertionError(outcome);
        writes(usb);

        usb = new Fake();
        usb.changeTargetAfterFirstEject = true;
        assertOutcome(usb, UsbReaderReconnectPlan.Outcome.FAILED);
        writes(usb, "UNMOUNT " + SD_ID);
    }

    private static void doesNotDisableBeforeBothEjectsAreVerified() {
        var usb = new Fake();
        usb.targetEjectDoesNotFinish = true;
        assertOutcome(usb, UsbReaderReconnectPlan.Outcome.FAILED);
        writes(usb, "UNMOUNT " + SD_ID, "UNMOUNT " + MICRO_ID, "MOUNT " + SD_ID);

        usb = new Fake();
        usb.delayedFirstEject = true;
        assertOutcome(usb, UsbReaderReconnectPlan.Outcome.FAILED);
        writes(usb, "UNMOUNT " + SD_ID, "MOUNT " + SD_ID);
    }

    private static void restoresDataWhenDisableReportsFailure() {
        var usb = new Fake();
        usb.disableThrows = true;
        usb.restoreThrowsOnce = true;
        assertOutcome(usb, UsbReaderReconnectPlan.Outcome.FAILED);
        writes(usb, "UNMOUNT " + SD_ID, "UNMOUNT " + MICRO_ID, "data false", "data true", "data true");
        if (usb.disabled) throw new AssertionError("USB data was left off");
    }

    private static void restoresDataWhenCallerDisappearsDuringDisconnect() {
        var usb = new Fake();
        usb.callerDiesAfterDisable = true;
        assertOutcome(usb, UsbReaderReconnectPlan.Outcome.RECONNECTED);
        writes(usb, "UNMOUNT " + SD_ID, "UNMOUNT " + MICRO_ID, "data false", "data true");
    }

    private static void refusesDataSwitchWithoutArmedCycle() {
        var usb = new Fake();
        usb.cycleArmFails = true;
        assertOutcome(usb, UsbReaderReconnectPlan.Outcome.FAILED);
        writes(usb, "UNMOUNT " + SD_ID, "UNMOUNT " + MICRO_ID, "MOUNT " + MICRO_ID, "MOUNT " + SD_ID);
    }

    private static void unknownCycleCompletionDoesNotStartASecondUsbRestore() {
        var usb = new Fake();
        usb.cycleIncomplete = true;
        assertOutcome(usb, UsbReaderReconnectPlan.Outcome.FAILED);
        writes(usb, "UNMOUNT " + SD_ID, "UNMOUNT " + MICRO_ID, "data false");
    }

    private static void retriesAKnownUnmountedDestination() {
        var usb = new Fake();
        usb.micro = "unmounted";
        assertOutcome(usb, UsbReaderReconnectPlan.Outcome.RECONNECTED);
        writes(usb, "UNMOUNT " + SD_ID, "data false", "data true");
    }

    private static void retriesMountOnlyAfterRecheckingTheSameVolume() {
        var usb = new Fake();
        usb.replaceSourceBeforeDisable = true;
        usb.recoveryMountFailsOnce = true;
        assertOutcome(usb, UsbReaderReconnectPlan.Outcome.FAILED);
        writes(usb, "UNMOUNT " + SD_ID, "UNMOUNT " + MICRO_ID, "MOUNT " + MICRO_ID, "MOUNT " + MICRO_ID);

        usb = new Fake();
        usb.replaceSourceBeforeDisable = true;
        usb.recoveryMountFailsOnce = true;
        usb.replaceTargetOnFailedMount = true;
        assertOutcome(usb, UsbReaderReconnectPlan.Outcome.FAILED);
        writes(usb, "UNMOUNT " + SD_ID, "UNMOUNT " + MICRO_ID, "MOUNT " + MICRO_ID);
    }

    private static void ambiguousReturnDoesNotRemountOtherCards() {
        var usb = new Fake();
        usb.replaceTargetOnEnable = true;
        assertOutcome(usb, UsbReaderReconnectPlan.Outcome.FAILED);
        writes(usb, "UNMOUNT " + SD_ID, "UNMOUNT " + MICRO_ID, "data false", "data true");
    }

    private static void recoversOnlyTheDestinationWhenTheSourceWasReplaced() {
        var usb = new Fake();
        usb.replaceSourceBeforeDisable = true;
        assertOutcome(usb, UsbReaderReconnectPlan.Outcome.FAILED);
        writes(usb, "UNMOUNT " + SD_ID, "UNMOUNT " + MICRO_ID, "MOUNT " + MICRO_ID);
    }

    private static void assertOutcome(Fake usb, UsbReaderReconnectPlan.Outcome expected) {
        var actual = UsbReaderReconnectPlan.execute(usb, READER, MICRO_UUID, UsbReaderReconnectPlan.OPERATION_MS);
        if (actual != expected) throw new AssertionError(usb.conflict + ": expected " + expected + ", got " + actual);
    }

    private static void writes(Fake usb, String... expected) {
        if (!usb.writes.equals(List.of(expected))) throw new AssertionError("Unexpected writes: " + usb.writes);
    }

    private static final class Fake implements UsbReaderReconnectPlan.Transport {
        final List<String> writes = new ArrayList<>();
        long time;
        String sd = "mounted";
        String micro = "mounted";
        String destination = MICRO_UUID;
        String sourceUuid = SD_UUID;
        boolean disabled;
        boolean cycleArmFails;
        boolean cycleIncomplete;
        boolean recoveryMountFailsOnce;
        boolean replaceTargetOnFailedMount;
        long events = 103;
        boolean alive = true;
        boolean sourceAppears;
        boolean sourceDisappearsOnEnable;
        boolean replaceSourceBeforeDisable;
        boolean disableThrows;
        boolean restoreThrowsOnce;
        boolean changeTargetAfterFirstEject;
        boolean targetEjectDoesNotFinish;
        boolean delayedFirstEject;
        boolean callerDiesAfterDisable;
        boolean replaceTargetOnEnable;
        String conflict = "";

        @Override public long now() { return time; }
        @Override public void waitMs(long duration) {
            time += duration;
            if (delayedFirstEject && time >= 500 && !writes.contains("MOUNT " + SD_ID)) sd = "unmounted";
        }
        @Override public boolean authorized() { return alive; }

        @Override public UsbReaderReconnectPlan.DataCycle armCycle(long deadline) throws IOException {
            if (cycleArmFails) throw new IOException("cycle failed before arming");
            return new UsbReaderReconnectPlan.DataCycle() {
                @Override public void start() {
                    writes.add("data false");
                    disabled = true;
                    events++;
                    if (callerDiesAfterDisable) alive = false;
                }

                @Override public UsbReaderReconnectPlan.CycleResult await(long timeout) {
                    if (cycleIncomplete) return null;
                    boolean on;
                    try {
                        restoreUsbData(timeout);
                        on = true;
                    } catch (IOException unavailable) {
                        on = false;
                    }
                    return new UsbReaderReconnectPlan.CycleResult(!disableThrows, on);
                }

                @Override public void close() {}
            };
        }

        @Override public String usbDump(long deadline) {
            String device = "    devices={\n      name=" + READER + "\n      vendor_id=8644\n"
                    + "      product_id=45152\n    }\n";
            if (conflict.equals("wrong device")) device = device.replace("8644", "1234");
            if (conflict.equals("extra device")) device += device.replace(READER, "/dev/bus/usb/002/003");
            return "USB MANAGER STATE\n  host_manager={\n" + (disabled ? "" : device)
                    + "    num_connects=" + events + "\n  }\n"
                    + "  port_manager={\n"
                    + "    is_simulation_active=" + conflict.equals("simulation") + "\n"
                    + "    usb_ports={\n      port={\n        id=port0\n"
                    + (conflict.equals("extra port") ? "        id=port1\n" : "")
                    + "      }\n      status={\n        connected=true\n        current_mode=dfp\n"
                    + "        power_role=source\n        data_role=host\n"
                    + "        usb_data_status=" + (disabled ? "disabled-force" : "enabled") + "\n      }\n"
                    + "      connected_at_millis=349043812\n    }\n    usb_hal_version=20\n  }\n";
        }

        @Override public String listPublicVolumes(long deadline) {
            return (sd == null ? "" : SD_ID + " " + sd + " " + sourceUuid + "\n")
                    + MICRO_ID + " " + micro + " " + destination + "\n"
                    + (conflict.equals("different list") ? "public:8,17 unmounted null\n" : "");
        }

        @Override public String mountDump(long deadline) {
            String first = SYS_PATH;
            if (conflict.equals("wrong bus")) first = first.replace("usb2/2-1/2-1", "usb3/3-1/3-1");
            if (conflict.equals("behind hub")) first = first.replace("usb2/2-1/2-1", "usb2/2-1/2-1.2/2-1.2");
            String second = conflict.equals("split target") ? first + "/host2/target2:0:0/2:0:0:1/block/sdh"
                    : first + "/host1/target1:0:0/1:0:0:" + (conflict.equals("target swapped") ? "0" : "1") + "/block/sdh";
            String source = first + "/host1/target1:0:0/1:0:0:" + (conflict.equals("target swapped") ? "1" : "0") + "/block/sdg";
            return "Disks:\n" + disk("disk:8,96", source, sd == null ? "-1" : "127865454592")
                    + disk("disk:8,112", second, "511868665856")
                    + (conflict.equals("other disk") ? disk("disk:8,16", "/sys/devices/other/block/sdb", "-1") : "")
                    + "\nVolumes:\n" + (sd == null ? "" : volume(SD_ID, "disk:8,96", sd, sourceUuid))
                    + volume(MICRO_ID, "disk:8,112", micro, destination)
                    + "  VolumeInfo{emulated;0}:\n    type=EMULATED diskId=null state=MOUNTED\n"
                    + (conflict.equals("other storage") ? "  VolumeInfo{stub:8,17}:\n    type=STUB diskId=null\n" : "")
                    + (conflict.equals("duplicate volume") ? volume("public:8,98", "disk:8,96", sd, "9999-0000") : "")
                    + "\nRecords:\n";
        }

        private static String disk(String id, String path, String size) {
            return "  DiskInfo{" + id + "}:\n    flags=USB size=" + size + " label=Generic \n    sysPath=" + path + " \n";
        }

        private static String volume(String id, String disk, String state, String uuid) {
            return "  VolumeInfo{" + id + "}:\n    type=PUBLIC diskId=" + disk
                    + " partGuid= mountFlags=VISIBLE_FOR_WRITE mountUserId=0 state=" + state.toUpperCase(java.util.Locale.ROOT)
                    + " \n    fsType=exfat fsUuid=" + uuid + " fsLabel=\n";
        }

        @Override public void change(SdCardRemountPlan.Action action, String id, long deadline) throws IOException {
            writes.add(action + " " + id);
            if (action == SdCardRemountPlan.Action.MOUNT && recoveryMountFailsOnce) {
                recoveryMountFailsOnce = false;
                if (replaceTargetOnFailedMount) destination = "0000-DEAD";
                throw new IOException("mount not delivered");
            }
            if (action == SdCardRemountPlan.Action.UNMOUNT && id.equals(SD_ID)) {
                if (delayedFirstEject) throw new IOException("late command");
                if (changeTargetAfterFirstEject) destination = "0000-DEAD";
            }
            if (action == SdCardRemountPlan.Action.UNMOUNT && id.equals(MICRO_ID) && targetEjectDoesNotFinish) return;
            String next = action == SdCardRemountPlan.Action.UNMOUNT ? "unmounted" : "mounted";
            if (id.equals(SD_ID)) sd = next;
            else {
                micro = next;
                if (replaceSourceBeforeDisable && action == SdCardRemountPlan.Action.UNMOUNT) sourceUuid = "0000-DEAD";
            }
        }

        @Override public void restoreUsbData(long deadline) throws IOException {
            writes.add("data true");
            if (restoreThrowsOnce) {
                restoreThrowsOnce = false;
                throw new IOException("enable did not reach the USB service");
            }
            disabled = false;
            events++;
            if (sd != null || sourceAppears) sd = "mounted";
            if (sourceDisappearsOnEnable) sd = null;
            micro = "mounted";
            if (replaceTargetOnEnable) destination = "0000-DEAD";
        }
    }
}
