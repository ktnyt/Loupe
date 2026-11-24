package jp.nano.rawgallery.camera;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Pins both public volume IDs before issuing any command and verifies both through the remount. */
final class SdCardRemountPlan {
    static final long OPERATION_MS = 25_000;
    private static final long RECOVERY_MS = 8_000;
    private static final long POLL_MS = 250;

    enum Outcome { MOUNTED, SKIPPED, FAILED }
    enum Action { MOUNT, UNMOUNT }
    interface Storage {
        String listPublicVolumes(long deadlineMs) throws IOException, InterruptedException;
        String mountDump(long deadlineMs) throws IOException, InterruptedException;
        void change(Action action, String id, long deadlineMs) throws IOException, InterruptedException;
        long now();
        void waitMs(long duration) throws InterruptedException;
    }

    private static final Pattern ID = Pattern.compile("public:[0-9]+,[0-9]+");
    private static final Pattern UUID = Pattern.compile("(?i)[0-9a-f]{4}-?[0-9a-f]{4}");
    private static final Set<String> STATES = Set.of("unmounted", "checking", "mounted",
            "mounted_ro", "ejecting", "unmountable", "removed", "bad_removal", "unknown");
    private record Volume(String id, String state, String uuid, SdRemountUsbDisks.Disk disk) {
        boolean mounted() { return "mounted".equals(state); }
        boolean unmounted() { return "unmounted".equals(state); }
        Volume onDisk(SdRemountUsbDisks.Disk disk) { return new Volume(id, state, uuid, disk); }
    }

    private SdCardRemountPlan() {}

    static boolean isPublicId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    static boolean isFilesystemUuid(String uuid) {
        return uuid != null && UUID.matcher(uuid).matches();
    }

    static Outcome execute(Storage storage, String sourceUuid, String destinationUuid, long deadline) {
        return execute(storage, sourceUuid, destinationUuid, deadline, ignored -> {});
    }

    static Outcome execute(Storage storage, String sourceUuid, String destinationUuid, long deadline,
            Consumer<String> mountedSource) {
        String source = normalize(sourceUuid);
        String destination = normalize(destinationUuid);
        long operationDeadline = deadline - RECOVERY_MS;
        if (source == null || destination == null || source.equals(destination)
                || operationDeadline <= storage.now()) return Outcome.SKIPPED;

        Volume original;
        Volume target;
        try {
            Map<String, Volume> initial = snapshot(storage, operationDeadline);
            original = mountedByUuid(initial, source);
            target = mountedByUuid(initial, destination);
        } catch (IOException e) {
            return Outcome.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.FAILED;
        }
        if (original == null || target == null) return Outcome.SKIPPED;

        boolean unmounted = false;
        try {
            storage.change(Action.UNMOUNT, original.id(), operationDeadline);
            unmounted = await(storage, original, target, false, operationDeadline, mountedSource);
            if (unmounted) {
                storage.change(Action.MOUNT, original.id(), operationDeadline);
                if (await(storage, original, target, true, operationDeadline, mountedSource)) return Outcome.MOUNTED;
            }
        } catch (IOException e) {
            // The storage service can finish an accepted command after the CLI reports failure.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        recover(storage, original, target, unmounted, deadline);
        return Outcome.FAILED;
    }

    private static boolean await(Storage storage, Volume original, Volume target, boolean mounting, long deadline,
            Consumer<String> mountedSource)
            throws IOException, InterruptedException {
        while (storage.now() < deadline) {
            Volume source;
            try {
                source = pinnedSource(snapshot(storage, deadline), original, target);
            } catch (IOException unavailable) {
                pause(storage, deadline);
                continue;
            }
            if (source == null) return false;
            if (mounting && source.mounted() && source.uuid() != null) {
                mountedSource.accept(source.uuid());
                return true;
            }
            if (!mounting && source.unmounted()) return originalOrUnknown(source, original);
            if (!mounting && !originalOrUnknown(source, original)) return false;
            pause(storage, deadline);
        }
        return false;
    }

    private static void recover(Storage storage, Volume original, Volume target, boolean unmounted, long deadline) {
        boolean interrupted = Thread.interrupted();
        try {
            boolean mountAttempted = false;
            while (storage.now() < deadline) {
                Volume source;
                try {
                    source = pinnedSource(snapshot(storage, deadline), original, target);
                } catch (IOException unavailable) {
                    pause(storage, deadline);
                    continue;
                }
                if (source == null || (unmounted && source.mounted()) || !originalOrUnknown(source, original)) return;
                // An unmount that timed out can still be in progress while sm reports the old state.
                if (source.unmounted() && !mountAttempted) {
                    mountAttempted = true;
                    unmounted = true;
                    try {
                        storage.change(Action.MOUNT, original.id(), deadline);
                    } catch (IOException ignored) {
                        // Observe an accepted command even if sm reported an error.
                    }
                }
                pause(storage, deadline);
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void pause(Storage storage, long deadline) throws InterruptedException {
        long remaining = deadline - storage.now();
        if (remaining > 0) storage.waitMs(Math.min(POLL_MS, remaining));
    }

    private static boolean originalOrUnknown(Volume source, Volume original) {
        return source.uuid() == null || original.uuid().equals(source.uuid());
    }

    private static Volume pinnedSource(Map<String, Volume> volumes, Volume original, Volume target) {
        if (volumes.size() != 2 || !target.equals(volumes.get(target.id()))) return null;
        Volume source = volumes.get(original.id());
        return source != null && original.disk().equals(source.disk()) ? source : null;
    }

    private static Volume mountedByUuid(Map<String, Volume> volumes, String uuid) {
        if (volumes.size() != 2) return null;
        return volumes.values().stream().filter(v -> v.mounted() && uuid.equals(v.uuid())).findFirst().orElse(null);
    }

    private static Map<String, Volume> snapshot(Storage storage, long deadline) throws IOException, InterruptedException {
        Map<String, Volume> volumes = new LinkedHashMap<>();
        Set<String> uuids = new HashSet<>();
        for (String line : storage.listPublicVolumes(deadline).split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] fields = trimmed.split("\\s+");
            if (fields.length != 3 || !isPublicId(fields[0]) || !STATES.contains(fields[1])) return Map.of();
            String uuid = normalize(fields[2]);
            if (uuid == null ? !"null".equals(fields[2]) : !uuids.add(uuid)) return Map.of();
            if (volumes.put(fields[0], new Volume(fields[0], fields[1], uuid, null)) != null) return Map.of();
        }
        if (volumes.size() != 2) return Map.of();
        var ids = volumes.keySet().iterator();
        Map<String, SdRemountUsbDisks.Volume> dump = SdRemountUsbDisks.resolve(
                storage.mountDump(deadline), ids.next(), ids.next());
        if (dump.size() != 2) return Map.of();
        for (var entry : volumes.entrySet()) {
            Volume listed = entry.getValue();
            var observed = dump.get(entry.getKey());
            if (!listed.state().equals(observed.state()) || !Objects.equals(listed.uuid(), normalize(observed.uuid()))) {
                throw new IOException("Storage state changed between snapshots");
            }
            entry.setValue(listed.onDisk(observed.disk()));
        }
        return volumes;
    }

    private static String normalize(String uuid) {
        return isFilesystemUuid(uuid) ? uuid.replace("-", "").toLowerCase(Locale.ROOT) : null;
    }
}
