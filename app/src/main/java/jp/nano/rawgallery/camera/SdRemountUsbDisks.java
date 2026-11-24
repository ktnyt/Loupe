package jp.nano.rawgallery.camera;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Resolves public volume IDs through Android's mount service to physical USB LUNs. */
final class SdRemountUsbDisks {
    private static final Pattern SECTION = Pattern.compile("(?m)^(Disks|Volumes|Records):[ \\t]*(?:\\R|$)");
    private static final Pattern DISK = Pattern.compile("(?m)^[ \\t]+DiskInfo\\{(disk:[0-9]+,[0-9]+)\\}:[ \\t]*\\R");
    private static final Pattern VOLUME = Pattern.compile("(?m)^[ \\t]+VolumeInfo\\{([^\\{\\}\\s]+)\\}:[ \\t]*\\R");
    private static final Pattern PUBLIC_TYPE = Pattern.compile("\\A[ \\t]+type=PUBLIC(?:\\s|$)");
    private static final Pattern VOLUME_DISK = Pattern.compile("\\A[ \\t]+type=PUBLIC[ \\t]+diskId=(disk:[0-9]+,[0-9]+)(?:\\s|$)");
    private static final Pattern STATE = Pattern.compile("(?m)[ \t]state=([A-Z_]+)[ \t]*$");
    private static final Pattern FS_UUID = Pattern.compile("(?m)^[ \t]+fsType=\\S+[ \t]+fsUuid=(\\S+)(?:\\s|$)");
    private static final Pattern FLAGS = Pattern.compile("\\A[ \\t]+flags=([^\\s]+)(?:\\s|$)");
    private static final Pattern SYS_PATH = Pattern.compile("(?m)^[ \\t]+sysPath=(\\S+)[ \\t]*$");
    private static final Pattern BLOCK_PATH = Pattern.compile("/sys/devices/(?:[A-Za-z0-9_.:@+-]+/)*block/[A-Za-z0-9_.+-]+");
    private static final Pattern USB_INTERFACE = Pattern.compile("(([0-9]+)-[0-9]+(?:\\.[0-9]+)*):[0-9]+\\.[0-9]+");

    record Disk(String id, String path, String usbInterface) {}
    record Volume(Disk disk, String state, String uuid) {}
    record Reader(Disk sd, Disk microSd, Map<String, Volume> volumes) {
        String usbDevicePath() {
            String usbInterface = microSd.usbInterface();
            return usbInterface.substring(0, usbInterface.lastIndexOf('/'));
        }
    }
    private record MountRecords(Map<String, String> disks, Map<String, String> volumes) {}
    private static final Pattern LUN = Pattern.compile(
            "/host([0-9]+)/target\\1:([0-9]+):([0-9]+)/\\1:\\2:\\3:([01])/block/[A-Za-z0-9_.+-]+");
    private static final Pattern INTERNAL = Pattern.compile(
            "\\A[ \\t]+type=(EMULATED|PRIVATE)[ \\t]+diskId=null(?:\\s|$)");

    private SdRemountUsbDisks() {}

    static Map<String, Volume> resolve(String dump, String firstVolume, String secondVolume) {
        MountRecords records = mountRecords(dump);
        if (records == null) return Map.of();
        Map<String, String> diskRecords = records.disks();
        Map<String, String> volumeRecords = records.volumes();
        long publicCount = volumeRecords.entrySet().stream()
                .filter(record -> record.getKey().startsWith("public:") || PUBLIC_TYPE.matcher(record.getValue()).find())
                .count();
        if (publicCount != 2) return Map.of();
        Volume first = volume(firstVolume, volumeRecords, diskRecords);
        Volume second = volume(secondVolume, volumeRecords, diskRecords);
        if (first == null || second == null) return Map.of();
        Disk a = first.disk();
        Disk b = second.disk();
        if (a.id().equals(b.id()) || a.path().equals(b.path()) || !a.usbInterface().equals(b.usbInterface())) {
            return Map.of();
        }
        return Map.of(firstVolume, first, secondVolume, second);
    }

    /** Accepts only the two physical SCSI LUNs of the reader, even when the SD slot has no volume. */
    static Reader reader(String dump) {
        MountRecords records = mountRecords(dump);
        if (records == null || records.disks().size() != 2) return null;
        Disk[] luns = new Disk[2];
        String target = null;
        for (var entry : records.disks().entrySet()) {
            Disk disk = disk(entry.getKey(), entry.getValue());
            if (disk == null) return null;
            String relativePath = disk.path().substring(disk.usbInterface().length());
            var match = LUN.matcher(relativePath);
            if (!match.matches()) return null;
            int lun = Integer.parseInt(match.group(4));
            String current = disk.usbInterface() + relativePath.substring(0, match.start(4));
            if (luns[lun] != null || (target != null && !target.equals(current))) return null;
            target = current;
            luns[lun] = disk;
        }

        Map<String, Volume> publicVolumes = new HashMap<>();
        Set<String> occupied = new java.util.HashSet<>();
        for (var entry : records.volumes().entrySet()) {
            String id = entry.getKey();
            if (!id.startsWith("public:")) {
                var internal = INTERNAL.matcher(entry.getValue());
                if (!internal.find() || !("PRIVATE".equals(internal.group(1)) ? "private".equals(id)
                        : id.matches("emulated(?:;[0-9]+)?"))) return null;
                continue;
            }
            if (!SdCardRemountPlan.isPublicId(id)) return null;
            Volume volume = volume(id, records.volumes(), records.disks());
            if (volume == null || !occupied.add(volume.disk().id())) return null;
            publicVolumes.put(id, volume);
        }
        return new Reader(luns[0], luns[1], Map.copyOf(publicVolumes));
    }

    private static MountRecords mountRecords(String dump) {
        var sections = SECTION.matcher(dump);
        if (!sections.find() || !"Disks".equals(sections.group(1))) return null;
        int disksStart = sections.end();
        if (!sections.find() || !"Volumes".equals(sections.group(1))) return null;
        Map<String, String> diskRecords = records(DISK, dump.substring(disksStart, sections.start()));
        int volumesStart = sections.end();
        if (!sections.find() || !"Records".equals(sections.group(1))) return null;
        Map<String, String> volumeRecords = records(VOLUME, dump.substring(volumesStart, sections.start()));
        return diskRecords == null || volumeRecords == null ? null : new MountRecords(diskRecords, volumeRecords);
    }

    private static Volume volume(String volume, Map<String, String> volumes, Map<String, String> disks) {
        String volumeRecord = volumes.get(volume);
        if (volumeRecord == null) return null;
        var reference = VOLUME_DISK.matcher(volumeRecord);
        if (!reference.find()) return null;
        String diskId = reference.group(1);
        var stateMatch = STATE.matcher(volumeRecord);
        var uuidMatch = FS_UUID.matcher(volumeRecord);
        if (!stateMatch.find() || !uuidMatch.find()) return null;
        String state = stateMatch.group(1).toLowerCase(Locale.ROOT);
        if ("mounted_read_only".equals(state)) state = "mounted_ro";
        String uuid = uuidMatch.group(1);
        if (stateMatch.find() || uuidMatch.find()
                || !("null".equals(uuid) || SdCardRemountPlan.isFilesystemUuid(uuid))) return null;
        Disk disk = disk(diskId, disks.get(diskId));
        return disk == null ? null : new Volume(disk, state, uuid);
    }

    private static Disk disk(String diskId, String disk) {
        if (disk == null) return null;
        var flags = FLAGS.matcher(disk);
        if (!flags.find()) return null;
        var tokens = List.of(flags.group(1).split("\\|"));
        if (!tokens.contains("USB") || tokens.contains("SD")) return null;
        var foundPath = SYS_PATH.matcher(disk);
        if (!foundPath.find()) return null;
        String path = foundPath.group(1).replaceAll("/+", "/");
        if (foundPath.find() || !BLOCK_PATH.matcher(path).matches()
                || path.contains("/../") || path.contains("/./")) return null;
        String usbInterface = usbInterface(path);
        return usbInterface == null ? null : new Disk(diskId, path, usbInterface);
    }

    private static String usbInterface(String path) {
        String[] components = path.split("/");
        for (int i = components.length - 3; i > 2; i--) {
            var iface = USB_INTERFACE.matcher(components[i]);
            if (!iface.matches() || !iface.group(1).equals(components[i - 1])) continue;
            String ancestor = String.join("/", Arrays.copyOf(components, i + 1));
            if (ancestor.contains("/usb" + iface.group(2) + "/")) return ancestor;
        }
        return null;
    }

    private static Map<String, String> records(Pattern pattern, String section) {
        var headers = pattern.matcher(section);
        Map<String, String> found = new HashMap<>();
        String id = null;
        int start = 0;
        while (headers.find()) {
            if (id != null && found.put(id, section.substring(start, headers.start())) != null) return null;
            id = headers.group(1);
            start = headers.end();
        }
        if (id != null && found.put(id, section.substring(start)) != null) return null;
        return found;
    }
}
