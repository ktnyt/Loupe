package jp.nano.rawgallery.camera;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Resolves public volume IDs through Android's mount service to physical USB LUNs. */
final class SdRemountUsbDisks {
    private static final Pattern SECTION = Pattern.compile("(?m)^(Disks|Volumes|Records):[ \\t]*(?:\\R|$)");
    private static final Pattern DISK = Pattern.compile("(?m)^[ \\t]+DiskInfo\\{(disk:[0-9]+,[0-9]+)}:[ \\t]*\\R");
    private static final Pattern VOLUME = Pattern.compile("(?m)^[ \\t]+VolumeInfo\\{([^{}\\s]+)}:[ \\t]*\\R");
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

    private SdRemountUsbDisks() {}

    static Map<String, Volume> resolve(String dump, String firstVolume, String secondVolume) {
        var sections = SECTION.matcher(dump);
        if (!sections.find() || !"Disks".equals(sections.group(1))) return Map.of();
        int disksStart = sections.end();
        if (!sections.find() || !"Volumes".equals(sections.group(1))) return Map.of();
        Map<String, String> diskRecords = records(DISK, dump.substring(disksStart, sections.start()));
        int volumesStart = sections.end();
        if (!sections.find() || !"Records".equals(sections.group(1))) return Map.of();
        Map<String, String> volumeRecords = records(VOLUME, dump.substring(volumesStart, sections.start()));
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
        String disk = disks.get(diskId);
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
        return usbInterface == null ? null : new Volume(new Disk(diskId, path, usbInterface), state, uuid);
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
            if (id != null && found.put(id, section.substring(start, headers.start())) != null) return Map.of();
            id = headers.group(1);
            start = headers.end();
        }
        if (id != null && found.put(id, section.substring(start)) != null) return Map.of();
        return found;
    }
}
