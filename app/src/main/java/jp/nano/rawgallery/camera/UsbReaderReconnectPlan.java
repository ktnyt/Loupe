package jp.nano.rawgallery.camera;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Disconnects a verified reader only after Android has unmounted all its known volumes. */
final class UsbReaderReconnectPlan {
    static final long OPERATION_MS = 45_000;
    private static final long EJECT_MS = 14_000;
    private static final long ARM_MS = 5_000;
    private static final long DETACH_MS = 9_000;
    private static final long ENABLE_MS = 7_000;
    private static final long ENABLE_CHECK_MS = 600;
    private static final long RECOVERY_MS = 7_000;
    private static final long MIN_RESTORE_MS = 3_000;
    private static final long SOURCE_SETTLE_MS = 6_000;
    private static final long POLL_MS = 250;
    private static final int MAX_MOUNT_ATTEMPTS = 2;

    enum Outcome { RECONNECTED, SOURCE_MISSING, SKIPPED, FAILED }
    enum CycleState { UNKNOWN, NOT_STARTED, TERMINATED, COMPLETED }
    record CycleResult(CycleState state, boolean offSucceeded, boolean onSucceeded) {}
    interface DataCycle extends AutoCloseable {
        void start() throws IOException;
        CycleResult await(long timeoutMs) throws IOException, InterruptedException;
        @Override void close();
    }
    interface Transport extends SdCardRemountPlan.Storage {
        DataCycle armCycle(long deadline) throws IOException, InterruptedException;
        String usbDump(long deadline) throws IOException, InterruptedException;
        void restoreUsbData(long deadline) throws IOException, InterruptedException;
        boolean authorized();
    }

    private record Slot(String id, String uuid, String state) {
        boolean mounted() { return "mounted".equals(state) || "mounted_ro".equals(state); }
        boolean writable() { return "mounted".equals(state); }
        boolean unmounted() { return "unmounted".equals(state); }
        boolean sameVolume(Slot other) { return other != null && id.equals(other.id()) && uuid.equals(other.uuid()); }
    }

    private record MountAttempt(int count, boolean submitted) {}

    private record Snapshot(SdRemountUsbPort.State usb, SdRemountUsbDisks.Reader reader, Slot source, Slot target) {
        boolean sameConnection(Snapshot initial) {
            return usb.equals(initial.usb) && reader.sd().equals(initial.reader.sd())
                    && reader.microSd().equals(initial.reader.microSd());
        }

        boolean reenumerated(Snapshot initial) {
            return usb.enumerations() - initial.usb.enumerations() == 2 && samePort(initial);
        }

        boolean samePort(Snapshot initial) {
            return usb.connectedAt() == initial.usb.connectedAt()
                    && reader.usbDevicePath().equals(initial.reader.usbDevicePath());
        }
    }

    private UsbReaderReconnectPlan() {}

    static Outcome execute(Transport storage, String address, String destinationUuid, long deadline) {
        String destination = uuid(destinationUuid);
        long ejectBy = Math.min(storage.now() + EJECT_MS, deadline - ARM_MS - DETACH_MS - ENABLE_MS - RECOVERY_MS);
        if (!SdRemountUsbPort.validAddress(address) || destination == null || ejectBy <= storage.now()
                || !storage.authorized()) return Outcome.SKIPPED;

        Snapshot initial;
        try {
            initial = snapshot(storage, ejectBy);
        } catch (IOException e) {
            return Outcome.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.FAILED;
        }
        if (initial == null || !address.equals(initial.usb.address()) || !destination.equals(initial.target.uuid())
                || !(initial.target.writable() || initial.target.unmounted()) || (initial.source != null
                && !(initial.source.mounted() || initial.source.unmounted()))) return Outcome.SKIPPED;

        Set<String> attempted = new LinkedHashSet<>();
        Set<String> ejected = new HashSet<>();
        boolean cycleRequested = false;
        CycleState cycleState = CycleState.UNKNOWN;
        boolean removed = false;
        try {
            for (Slot slot : new Slot[]{initial.source, initial.target}) {
                if (slot == null || slot.unmounted()) continue;
                if (!matches(snapshot(storage, ejectBy), initial, ejected, null) || !storage.authorized()) {
                    if (attempted.isEmpty()) return Outcome.SKIPPED;
                    throw new IOException("USB topology or card selection changed before eject");
                }
                attempted.add(slot.id());
                storage.change(SdCardRemountPlan.Action.UNMOUNT, slot.id(), ejectBy);
                awaitEject(storage, initial, ejected, slot, ejectBy);
                ejected.add(slot.id());
            }

            long armBy = Math.min(storage.now() + ARM_MS, deadline - DETACH_MS - ENABLE_MS - RECOVERY_MS);
            long finishBy = deadline - RECOVERY_MS;
            CycleResult completion;
            try (DataCycle cycle = storage.armCycle(armBy)) {
                if (!matches(snapshot(storage, armBy), initial, ejected, null) || !storage.authorized()) {
                    throw new IOException("Not all reader volumes are safely ejected");
                }
                cycleRequested = true;
                try {
                    cycle.start();
                } catch (IOException unknownStart) {
                    // The control pipe may fail after the request was accepted. Read the child state.
                }
                removed = awaitDeparture(storage, initial.usb, Math.min(storage.now() + DETACH_MS, finishBy));
                completion = cycle.await(Math.max(0, finishBy - storage.now()));
                cycleState = completion.state();
            }
            if (cycleState == CycleState.TERMINATED && !removed) {
                // A transitional USB dump may hide the detach until after the child has stopped.
                removed = awaitDeparture(storage, initial.usb, deadline - MIN_RESTORE_MS);
            }
            if (removed && cycleState == CycleState.COMPLETED && completion.offSucceeded() && completion.onSucceeded()) {
                Outcome result = awaitReturn(storage, initial, finishBy);
                if (result != Outcome.FAILED) return result;
            }
        } catch (IOException ignored) {
            // A storage or USB command can take effect even if its CLI reports failure.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        boolean offFinished = cycleState == CycleState.COMPLETED || (cycleState == CycleState.TERMINATED && removed);
        // When the child died, the observed OFF effect proves its only disable request reached the port.
        // A live or unobserved OFF request may still be pending and must never be overtaken by recovery.
        if (!cycleRequested || cycleState == CycleState.NOT_STARTED || offFinished) {
            recover(storage, initial, attempted, ejected, offFinished, deadline);
        }
        return Outcome.FAILED;
    }

    private static boolean matches(Snapshot observed, Snapshot initial, Set<String> ejected, Slot pending) {
        return observed != null && observed.sameConnection(initial)
                && matches(observed.source, initial.source, ejected, pending)
                && matches(observed.target, initial.target, ejected, pending);
    }

    private static boolean matches(Slot observed, Slot original, Set<String> ejected, Slot pending) {
        if (original == null) return observed == null;
        if (!original.sameVolume(observed)) return false;
        if (ejected.contains(original.id())) return observed.unmounted();
        if (original.equals(pending)) return observed.mounted() || observed.unmounted() || "ejecting".equals(observed.state());
        return observed.state().equals(original.state());
    }

    private static void awaitEject(Transport storage, Snapshot initial, Set<String> ejected, Slot pending, long deadline)
            throws IOException, InterruptedException {
        boolean source = pending.equals(initial.source);
        while (storage.now() < deadline) {
            Snapshot current = snapshot(storage, deadline);
            if (current != null) {
                if (!matches(current, initial, ejected, pending)) throw new IOException("USB state changed during eject");
                if ((source ? current.source : current.target).unmounted()) return;
            }
            pause(storage, deadline);
        }
        throw new IOException("USB eject could not be confirmed");
    }

    private static boolean awaitDeparture(Transport storage, SdRemountUsbPort.State initial, long deadline)
            throws InterruptedException {
        while (storage.now() < deadline) {
            try {
                SdRemountUsbPort.State usb = SdRemountUsbPort.parse(storage.usbDump(deadline));
                if (usb != null) {
                    if (!usb.connectedHost() || usb.connectedAt() != initial.connectedAt()) return false;
                    long events = usb.enumerations() - initial.enumerations();
                    if (events == 1 && usb.removed()) return true;
                    if (events < 0 || events > 1 || usb.address() != null && !initial.address().equals(usb.address())) {
                        return false;
                    }
                }
            } catch (IOException unavailable) {
                // Do not interrupt the independent cycle if a read fails.
            }
            pause(storage, deadline);
        }
        return false;
    }

    private static void restoreData(Transport storage, Snapshot initial, long deadline) throws IOException, InterruptedException {
        boolean interrupted = Thread.interrupted();
        try {
            while (storage.now() < deadline) {
                try {
                    var before = SdRemountUsbPort.parse(storage.usbDump(deadline));
                    if (before == null) {
                        pause(storage, deadline);
                        continue;
                    }
                    if (!dataStillDisabled(before, initial)) return;
                    storage.restoreUsbData(deadline);
                    // The CLI can return before the HAL updates the port's data state.
                    long checkBy = Math.min(deadline, storage.now() + ENABLE_CHECK_MS);
                    while (storage.now() < checkBy) {
                        var usb = SdRemountUsbPort.parse(storage.usbDump(deadline));
                        if (usb != null && "enabled".equals(usb.data())) return;
                        pause(storage, checkBy);
                    }
                    if (storage.now() >= deadline) throw new IOException("USB data recovery was not confirmed");
                } catch (IOException | InterruptedException failure) {
                    interrupted |= failure instanceof InterruptedException;
                    if (storage.now() >= deadline) throw failure;
                    // Retry the compensating command if the previous request did not complete.
                    try {
                        pause(storage, deadline);
                    } catch (InterruptedException pauseInterrupted) {
                        interrupted = true;
                    }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static Outcome awaitReturn(Transport storage, Snapshot initial, long deadline)
            throws InterruptedException {
        long missingSince = -1;
        while (storage.now() < deadline) {
            Snapshot current = retrySnapshot(storage, deadline);
            if (current != null && current.reenumerated(initial) && initial.target.uuid().equals(current.target.uuid())
                    && current.target.writable()) {
                if (current.source != null && current.source.mounted() && expectedSource(current, initial)) {
                    return Outcome.RECONNECTED;
                }
                if (current.source == null) {
                    if (missingSince < 0) missingSince = storage.now();
                    if (storage.now() - missingSince >= SOURCE_SETTLE_MS) return Outcome.SOURCE_MISSING;
                    pause(storage, deadline);
                    continue;
                }
            }
            missingSince = -1;
            pause(storage, deadline);
        }
        return Outcome.FAILED;
    }

    private static void recover(Transport storage, Snapshot initial, Set<String> attempted, Set<String> ejected,
            boolean cycleAttempted, long deadline) {
        boolean interrupted = Thread.interrupted();
        try {
            Map<String, MountAttempt> mounts = new HashMap<>();
            boolean dataRestoreRetried = false;
            while ((cycleAttempted || !attempted.isEmpty()) && storage.now() < deadline) {
                Snapshot current = retrySnapshot(storage, deadline);
                if (current == null) {
                    if (cycleAttempted && !dataRestoreRetried) {
                        dataRestoreRetried = true;
                        restoreData(storage, initial, Math.min(deadline, storage.now() + ENABLE_MS));
                    }
                    pause(storage, deadline);
                    continue;
                }
                boolean same = current.sameConnection(initial);
                if (!(same || cycleAttempted && current.reenumerated(initial))
                        || !initial.target.uuid().equals(current.target.uuid())) return;
                if (restore(storage, current.target, initial.target, attempted, mounts, deadline)) {
                    ejected.add(initial.target.id());
                    pause(storage, deadline);
                    continue;
                }
                if (restore(storage, current.source, initial.source, attempted, mounts, deadline)) {
                    ejected.add(initial.source.id());
                    pause(storage, deadline);
                    continue;
                }
                // A timed-out eject can still take effect while the previous mount state is visible.
                if (current.target.mounted() && (!attempted.contains(initial.target.id())
                        || ejected.contains(initial.target.id()))
                        && (!expectedSource(current, initial) || initial.source == null
                        || !attempted.contains(initial.source.id()) || (ejected.contains(initial.source.id())
                        && current.source.mounted()))) return;
                pause(storage, deadline);
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } catch (IOException ignored) {
            // The bounded recovery cannot safely resolve the current USB state.
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static boolean dataStillDisabled(SdRemountUsbPort.State usb, Snapshot initial) {
        return usb.connectedHost() && usb.connectedAt() == initial.usb.connectedAt()
                && usb.enumerations() - initial.usb.enumerations() == 1 && usb.removed();
    }

    private static boolean restore(Transport storage, Slot current, Slot original, Set<String> attempted,
            Map<String, MountAttempt> mounts, long deadline) throws InterruptedException {
        if (current == null || original == null || !current.unmounted() || !original.uuid().equals(current.uuid())
                || !attempted.contains(original.id())) return false;
        MountAttempt prior = mounts.get(original.id());
        if (prior != null && (prior.submitted() || prior.count() >= MAX_MOUNT_ATTEMPTS)) return false;
        int count = prior == null ? 1 : prior.count() + 1;
        try {
            storage.change(SdCardRemountPlan.Action.MOUNT, current.id(), deadline);
            mounts.put(original.id(), new MountAttempt(count, true));
        } catch (IOException ignored) {
            // Recheck topology and the unmounted UUID before retrying a failed request.
            mounts.put(original.id(), new MountAttempt(count, false));
        }
        return true;
    }

    private static boolean expectedSource(Snapshot current, Snapshot initial) {
        return initial.source == null || current.source != null && initial.source.uuid().equals(current.source.uuid());
    }

    private static Snapshot retrySnapshot(Transport storage, long deadline) throws InterruptedException {
        try {
            return snapshot(storage, deadline);
        } catch (IOException unavailable) {
            return null;
        }
    }

    private static Snapshot snapshot(Transport storage, long deadline) throws IOException, InterruptedException {
        SdRemountUsbPort.State usb = SdRemountUsbPort.parse(storage.usbDump(deadline));
        if (usb == null || !usb.ready()) return null;
        SdRemountUsbDisks.Reader reader = SdRemountUsbDisks.reader(storage.mountDump(deadline));
        if (reader == null || !usb.onBus(reader.usbDevicePath())) return null;
        Set<String> expected = new HashSet<>();
        Slot source = null;
        Slot target = null;
        for (var entry : reader.volumes().entrySet()) {
            var volume = entry.getValue();
            String uuid = uuid(volume.uuid());
            if (uuid == null) return null;
            expected.add(entry.getKey() + " " + volume.state() + " " + uuid);
            Slot slot = new Slot(entry.getKey(), uuid, volume.state());
            if (reader.sd().equals(volume.disk())) source = slot;
            else target = slot;
        }
        if (target == null || source != null && source.uuid().equals(target.uuid())) return null;
        for (String line : storage.listPublicVolumes(deadline).split("\\R")) {
            if (line.isBlank()) continue;
            String[] fields = line.trim().split("\\s+");
            if (fields.length != 3 || !expected.remove(fields[0] + " " + fields[1] + " " + uuid(fields[2]))) {
                return null;
            }
        }
        if (!expected.isEmpty() || !usb.equals(SdRemountUsbPort.parse(storage.usbDump(deadline)))) return null;
        return new Snapshot(usb, reader, source, target);
    }

    private static String uuid(String value) {
        return SdCardRemountPlan.isFilesystemUuid(value) ? value.replace("-", "").toLowerCase(Locale.ROOT) : null;
    }

    private static void pause(Transport storage, long deadline) throws InterruptedException {
        long remaining = deadline - storage.now();
        if (remaining > 0) storage.waitMs(Math.min(POLL_MS, remaining));
    }
}
