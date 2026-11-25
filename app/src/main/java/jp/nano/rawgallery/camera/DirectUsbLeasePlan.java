package jp.nano.rawgallery.camera;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Temporarily transfers the two-slot reader from Android storage to a single read owner. */
final class DirectUsbLeasePlan {
    static final long EJECT_MS = 20_000;
    private static final long RECOVERY_MS = 20_000;
    private static final long POLL_MS = 250;

    enum Outcome { SKIPPED, IMPORTED, FAILED_RESTORED, RECOVERY_PENDING }
    record Result(Outcome outcome, Throwable failure) {}

    record Slot(String id, String uuid, String state, SdRemountUsbDisks.Disk disk) {
        boolean mounted() { return "mounted".equals(state) || "mounted_ro".equals(state); }
        boolean unmounted() { return "unmounted".equals(state); }

        boolean sameVolume(Slot old) {
            return uuid.equals(old.uuid) && id.equals(old.id) && disk.equals(old.disk);
        }
    }

    record Obligation(SdRemountUsbPort.State usb, String usbDevicePath, Slot sd, Slot microSd) {
        boolean sameConnection(Obligation old) {
            return usb.equals(old.usb) && usbDevicePath.equals(old.usbDevicePath)
                    && sd.sameVolume(old.sd) && microSd.sameVolume(old.microSd);
        }

        boolean reenumerated(Obligation old) {
            return usb.connectedAt() == old.usb.connectedAt() && usb.enumerations() - old.usb.enumerations() == 2
                    && usbDevicePath.equals(old.usbDevicePath)
                    && sd.uuid.equals(old.sd.uuid) && microSd.uuid.equals(old.microSd.uuid);
        }

        boolean reprobed(Obligation old) {
            return usb.equals(old.usb) && usbDevicePath.equals(old.usbDevicePath)
                    && sd.disk.usbInterface().equals(old.sd.disk.usbInterface())
                    && microSd.disk.usbInterface().equals(old.microSd.disk.usbInterface())
                    && sd.uuid.equals(old.sd.uuid) && microSd.uuid.equals(old.microSd.uuid);
        }
    }

    /** The journal must durably and atomically claim and clear the exact obligation. */
    interface Journal {
        void begin(Obligation obligation) throws IOException;
        void complete(Obligation obligation) throws IOException;
    }

    @FunctionalInterface
    interface ScopedRead {
        /** Runs synchronously and closes every USB handle before returning or throwing. */
        void run() throws Exception;
    }

    /** Signals that the caller could not establish whether the read owner relinquished the reader. */
    static final class OwnershipUnknownException extends IOException {
        OwnershipUnknownException(Throwable cause) {
            super("USB read ownership could not be confirmed released", cause);
        }
    }

    private DirectUsbLeasePlan() {}

    static Result execute(UsbReaderReconnectPlan.Transport storage, Journal journal, String address,
            String sdUuid, String microSdUuid, long ejectDeadlineMs, ScopedRead read) {
        String expectedSd = uuid(sdUuid);
        String expectedMicroSd = uuid(microSdUuid);
        long ejectBy = Math.min(ejectDeadlineMs, storage.now() + EJECT_MS);
        if (!SdRemountUsbPort.validAddress(address) || expectedSd == null || expectedMicroSd == null
                || expectedSd.equals(expectedMicroSd) || read == null || storage.now() >= ejectBy) {
            return new Result(Outcome.SKIPPED, null);
        }

        Obligation initial;
        try {
            if (!storage.authorized()) return new Result(Outcome.SKIPPED, null);
            initial = snapshot(storage, ejectBy, null);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.SKIPPED, failure);
        } catch (IOException | RuntimeException failure) {
            return new Result(Outcome.SKIPPED, failure);
        }
        if (initial == null || !address.equals(initial.usb.address()) || !expectedSd.equals(initial.sd.uuid)
                || !expectedMicroSd.equals(initial.microSd.uuid) || !initial.sd.mounted() || !initial.microSd.mounted()) {
            return new Result(Outcome.SKIPPED, null);
        }

        try {
            journal.begin(initial);
        } catch (IOException | RuntimeException failure) {
            // Persistence may have succeeded before its acknowledgement failed. Leave it intact.
            return new Result(Outcome.RECOVERY_PENDING, failure);
        }

        Set<String> awaitingEject = new HashSet<>();
        Throwable failure = null;
        boolean enteredScopedOwner = false;
        try {
            eject(storage, initial, false, awaitingEject, ejectBy);
            eject(storage, initial, true, awaitingEject, ejectBy);
            pause(storage, ejectBy);
            Obligation current = connectedSnapshot(storage, initial, ejectBy);
            if (!current.sd.unmounted() || !current.microSd.unmounted() || !storage.authorized()) {
                throw new IOException("The reader is not exclusively available for USB reads");
            }
            enteredScopedOwner = true;
            read.run();
        } catch (OwnershipUnknownException unknown) {
            return new Result(Outcome.RECOVERY_PENDING, unknown);
        } catch (Exception | Error failed) {
            failure = failed;
        }

        boolean interrupted = Thread.interrupted() || failure instanceof InterruptedException;
        try {
            if (restore(storage, journal, initial, awaitingEject, enteredScopedOwner, storage.now() + RECOVERY_MS)) {
                return new Result(failure == null ? Outcome.IMPORTED : Outcome.FAILED_RESTORED, failure);
            }
        } catch (IOException | RuntimeException recoveryFailure) {
            failure = addFailure(failure, recoveryFailure);
        } catch (InterruptedException recoveryInterrupted) {
            interrupted = true;
            failure = addFailure(failure, recoveryInterrupted);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        return new Result(Outcome.RECOVERY_PENDING, failure);
    }

    /** Retries a durable obligation after both the owner and any old unmount request have settled. */
    static boolean recover(UsbReaderReconnectPlan.Transport storage, Journal journal, Obligation initial, long deadline)
            throws IOException, InterruptedException {
        return restore(storage, journal, initial, new HashSet<>(), true, Math.min(deadline, storage.now() + RECOVERY_MS));
    }

    private static void eject(UsbReaderReconnectPlan.Transport storage, Obligation initial, boolean microSd,
            Set<String> awaitingEject, long deadline) throws IOException, InterruptedException {
        Obligation before = connectedSnapshot(storage, initial, deadline);
        boolean originalStates = before.microSd.state.equals(initial.microSd.state)
                && (microSd ? before.sd.unmounted() : before.sd.state.equals(initial.sd.state));
        if (!originalStates || !storage.authorized()) throw new IOException("Reader state changed before unmount");

        Slot original = microSd ? initial.microSd : initial.sd;
        awaitingEject.add(original.id);
        storage.change(SdCardRemountPlan.Action.UNMOUNT, original.id, deadline);
        while (storage.now() < deadline) {
            Obligation observed = snapshot(storage, deadline, initial);
            if (observed != null) {
                if (!observed.sameConnection(initial)) throw new IOException("Reader identity changed during unmount");
                Slot current = microSd ? observed.microSd : observed.sd;
                boolean otherSlotUnchanged = microSd ? observed.sd.unmounted()
                        : observed.microSd.state.equals(initial.microSd.state);
                if (!otherSlotUnchanged) throw new IOException("Other reader slot changed during unmount");
                if (current.unmounted()) {
                    awaitingEject.remove(original.id);
                    return;
                }
                if (!current.state.equals(original.state) && !"ejecting".equals(current.state)) {
                    throw new IOException("Reader slot unexpectedly changed during unmount");
                }
            }
            pause(storage, deadline);
        }
        throw new IOException("Reader unmount could not be confirmed");
    }

    private static Obligation connectedSnapshot(UsbReaderReconnectPlan.Transport storage, Obligation initial, long deadline)
            throws IOException, InterruptedException {
        if (storage.now() >= deadline) throw new IOException("Reader unmount deadline elapsed");
        Obligation current = snapshot(storage, deadline, initial);
        if (current == null || !current.sameConnection(initial)) throw new IOException("Reader identity changed");
        if (storage.now() >= deadline) throw new IOException("Reader unmount deadline elapsed");
        return current;
    }

    private static boolean restore(UsbReaderReconnectPlan.Transport storage, Journal journal, Obligation initial,
            Set<String> awaitingEject, boolean ownerClosed, long deadline) throws IOException, InterruptedException {
        // A failed unmount can take effect later. Require state readback after each unmount or a fresh enumeration.
        var submittedMounts = new HashSet<Slot>();
        var failedMounts = new HashSet<Slot>();
        Obligation previouslyRestored = null;
        while (storage.now() < deadline) {
            Obligation current = null;
            try {
                current = snapshot(storage, deadline, initial);
            } catch (IOException ignored) {
                // An incomplete storage dump is not proof of identity; try another read before writing.
            }
            boolean reenumerated = current != null && current.reenumerated(initial);
            boolean reprobed = current != null && ownerClosed && current.reprobed(initial);
            if (current != null && (reenumerated || reprobed || current.sameConnection(initial))
                    && storage.now() < deadline) {
                if (reenumerated) awaitingEject.clear();
                if (current.sd.unmounted()) awaitingEject.remove(initial.sd.id);
                if (current.microSd.unmounted()) awaitingEject.remove(initial.microSd.id);
                if (awaitingEject.isEmpty() && current.sd.state.equals(initial.sd.state)
                        && current.microSd.state.equals(initial.microSd.state)) {
                    if (current.equals(previouslyRestored)) {
                        journal.complete(initial);
                        return true;
                    }
                    previouslyRestored = current;
                    pause(storage, deadline);
                    continue;
                }
                Slot unmounted = mountable(current.microSd, submittedMounts) ? current.microSd : current.sd;
                if (mountable(unmounted, submittedMounts)) {
                    try {
                        storage.change(SdCardRemountPlan.Action.MOUNT, unmounted.id, deadline);
                        submittedMounts.add(unmounted);
                    } catch (IOException failed) {
                        // Revalidate the entire pair before a single retry; the first request may have taken effect.
                        if (!failedMounts.add(unmounted)) submittedMounts.add(unmounted);
                    }
                }
            }
            previouslyRestored = null;
            pause(storage, deadline);
        }
        return false;
    }

    private static boolean mountable(Slot slot, Set<Slot> submittedMounts) {
        return slot.unmounted() && !submittedMounts.contains(slot);
    }

    private static Obligation snapshot(UsbReaderReconnectPlan.Transport storage, long deadline, Obligation initial)
            throws IOException, InterruptedException {
        SdRemountUsbPort.State usb = SdRemountUsbPort.parse(storage.usbDump(deadline));
        if (usb == null || !usb.ready()) return null;
        SdRemountUsbDisks.Reader reader = SdRemountUsbDisks.reader(storage.mountDump(deadline));
        if (reader == null || reader.volumes().size() != 2 || !usb.onBus(reader.usbDevicePath())) return null;

        Slot sd = null;
        Slot microSd = null;
        Set<String> expected = new HashSet<>();
        for (var entry : reader.volumes().entrySet()) {
            var volume = entry.getValue();
            boolean sdSlot = reader.sd().equals(volume.disk());
            Slot old = initial == null ? null : sdSlot ? initial.sd : initial.microSd;
            boolean cachedUnmount = old != null && usb.equals(initial.usb)
                    && reader.usbDevicePath().equals(initial.usbDevicePath) && entry.getKey().equals(old.id)
                    && volume.disk().equals(old.disk)
                    && ("unmounted".equals(volume.state()) || "ejecting".equals(volume.state()));
            String reported = uuid(volume.uuid());
            if (reported == null && !cachedUnmount) return null;
            Slot slot = new Slot(entry.getKey(), reported != null ? reported : old.uuid, volume.state(), volume.disk());
            if (sdSlot) sd = slot;
            else microSd = slot;
            expected.add(slot.id + " " + slot.state + " " + slot.uuid);
            if (cachedUnmount) expected.add(slot.id + " " + slot.state + " null");
        }
        if (sd == null || microSd == null || sd.uuid.equals(microSd.uuid)) return null;
        for (String line : storage.listPublicVolumes(deadline).split("\\R")) {
            if (line.isBlank()) continue;
            String[] fields = line.trim().split("\\s+");
            if (fields.length != 3) return null;
            String key = fields[0] + " " + fields[1] + " ";
            if (!expected.remove(key + uuid(fields[2]))) return null;
            expected.removeIf(entry -> entry.startsWith(key));
        }
        if (!expected.isEmpty() || !usb.equals(SdRemountUsbPort.parse(storage.usbDump(deadline)))) return null;
        return new Obligation(usb, reader.usbDevicePath(), sd, microSd);
    }

    private static String uuid(String value) {
        return SdCardRemountPlan.isFilesystemUuid(value) ? value.replace("-", "").toLowerCase(Locale.ROOT) : null;
    }

    private static void pause(UsbReaderReconnectPlan.Transport storage, long deadline) throws InterruptedException {
        long remaining = deadline - storage.now();
        if (remaining > 0) storage.waitMs(Math.min(POLL_MS, remaining));
    }

    private static Throwable addFailure(Throwable failure, Throwable recoveryFailure) {
        if (failure == null) return recoveryFailure;
        if (failure != recoveryFailure) failure.addSuppressed(recoveryFailure);
        return failure;
    }
}
