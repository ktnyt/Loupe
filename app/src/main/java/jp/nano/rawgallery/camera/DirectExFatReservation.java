package jp.nano.rawgallery.camera;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only proof of the data sectors of an existing exFAT reservation. */
final class DirectExFatReservation {
    private static final int SECTOR_BYTES = 512;
    private static final int ENTRY_BYTES = 32;
    private static final int FILENAME_UNITS = 15;
    private static final int WINDOW_SECTORS = 32;
    private static final long END_OF_CHAIN = 0xffff_ffffL;
    private static final long MAX_CLUSTERS = 0xffff_fff5L;
    private static final long MAX_DIRECTORY_BYTES = 64L * 1024 * 1024;
    private static final int MAX_DIRECTORY_ENTRIES = 100_000;
    private static final int MAX_BITMAP_CLUSTERS = 131_072;
    private static final long MAX_DATA_FAT_STEPS = 4_000_000;

    record Extent(long firstLba, long sectors) {}

    static Extent locate(BlockDevice disk, long diskSectors, String expectedUuid,
                         String relativePath, long byteLength) throws IOException {
        require(disk.getBlockSize() == SECTOR_BYTES && diskSectors >= 2048,
                "Unsupported disk geometry");
        require(expectedUuid != null && expectedUuid.matches("[0-9a-fA-F]{4}-[0-9a-fA-F]{4}")
                && !expectedUuid.equals("0000-0000"), "Invalid exFAT volume UUID");
        require(relativePath != null && relativePath.length() <= 4096 && byteLength > 0,
                "Invalid reservation path or length");
        String[] path = relativePath.split("/", -1);
        for (String name : path) require(validName(name), "Invalid reservation path component");
        return new DirectExFatReservation(disk, diskSectors, expectedUuid).find(path, byteLength);
    }

    /** Before Android allocates its pending file, ensure its allocator cannot reuse live data sectors. */
    static void checkCanAllocate(BlockDevice disk, long diskSectors, String expectedUuid) throws IOException {
        require(disk.getBlockSize() == SECTOR_BYTES && diskSectors >= 2048, "Unsupported disk geometry");
        require(expectedUuid != null && expectedUuid.matches("[0-9a-fA-F]{4}-[0-9a-fA-F]{4}")
                && !expectedUuid.equals("0000-0000"), "Invalid exFAT volume UUID");
        new DirectExFatReservation(disk, diskSectors, expectedUuid).find(null, 0);
    }

    private final BlockDevice disk;
    private final long diskSectors;
    private final long partition;
    private final long fatStart;
    private final long fatSectors;
    private final long heapStart;
    private final long clusterCount;
    private final long rootCluster;
    private final int sectorsPerCluster;
    private final int bytesPerCluster;
    private final byte[] fatWindow = new byte[WINDOW_SECTORS * SECTOR_BYTES];
    private long fatWindowStart = -1;
    private final Set<Long> metadataClusters = new HashSet<>();
    private long directoryBytes;
    private int directoryEntries;
    private char[] upcase;

    private DirectExFatReservation(BlockDevice disk, long diskSectors, String expectedUuid)
            throws IOException {
        this.disk = disk;
        this.diskSectors = diskSectors;
        byte[] sectorZero = new byte[SECTOR_BYTES];
        read(0, 1, sectorZero);
        byte[] sector = new byte[SECTOR_BYTES];
        read(1, 1, sector);
        require(!asciiAt(sector, 0, "EFI PART"), "GPT is unsupported for direct import");
        read(diskSectors - 1, 1, sector);
        require(!asciiAt(sector, 0, "EFI PART"), "GPT is unsupported for direct import");

        long partitionStart = 0;
        long partitionLength = diskSectors;
        require(u16(sectorZero, 510) == 0xaa55, "Invalid disk signature");
        if (!asciiAt(sectorZero, 3, "EXFAT   ")) {
            for (int entry = 446; entry < 510; entry += 16) {
                if (allZero(sectorZero, entry, entry + 16)) continue;
                int bootIndicator = u8(sectorZero, entry);
                require(partitionStart == 0 && u8(sectorZero, entry + 4) == 7
                        && (bootIndicator == 0 || bootIndicator == 0x80),
                        "Expected one basic exFAT MBR partition");
                partitionStart = u32(sectorZero, entry + 8);
                partitionLength = u32(sectorZero, entry + 12);
                require(partitionStart > 0 && partitionStart < diskSectors
                        && partitionLength >= 2048 && partitionLength <= diskSectors - partitionStart,
                        "Invalid MBR partition range");
            }
            require(partitionStart > 0, "Missing exFAT MBR partition");
        }
        partition = partitionStart;
        byte[] main = bootRegion(partition);
        byte[] backup = bootRegion(partition + 12);
        // The backup flags and usage percentage may be stale. These are also excluded from checksums.
        for (int i = 0; i < 113; i++) {
            if (i == 106 || i == 107 || i == 112) continue;
            require(main[i] == backup[i], "exFAT boot headers disagree");
        }
        require(u16(main, 104) == 0x0100 && u16(main, 106) == 0
                && u8(main, 108) == 9 && u8(main, 109) <= 16 && u8(main, 110) == 1,
                "Unsupported or unclean exFAT volume");
        long declaredPartition = u64(main, 64);
        require(declaredPartition == 0 || declaredPartition == partition, "exFAT partition offset mismatch");
        long volumeSectors = u64(main, 72);
        long fatOffset = u32(main, 80);
        fatSectors = u32(main, 84);
        long heapOffset = u32(main, 88);
        clusterCount = u32(main, 92);
        rootCluster = u32(main, 96);
        long serial = Long.parseLong(expectedUuid.replace("-", ""), 16);
        require(u32(main, 100) == serial, "exFAT volume UUID mismatch");
        sectorsPerCluster = 1 << u8(main, 109);
        bytesPerCluster = SECTOR_BYTES * sectorsPerCluster;
        require(volumeSectors >= 2048 && volumeSectors <= partitionLength && fatOffset >= 24
                && fatOffset + fatSectors <= heapOffset && heapOffset < volumeSectors
                && clusterCount > 0 && clusterCount <= MAX_CLUSTERS && validCluster(rootCluster)
                && clusterCount == Math.min(MAX_CLUSTERS, (volumeSectors - heapOffset) / sectorsPerCluster)
                && fatSectors >= ceilDiv((clusterCount + 2) * 4, SECTOR_BYTES),
                "Invalid exFAT FAT or heap geometry");
        fatStart = partition + fatOffset;
        heapStart = partition + heapOffset;
    }

    private byte[] bootRegion(long lba) throws IOException {
        byte[] region = new byte[12 * SECTOR_BYTES];
        read(lba, 12, region);
        require(asciiAt(region, 3, "EXFAT   ") && allZero(region, 11, 64)
                && u16(region, 510) == 0xaa55, "Invalid exFAT boot sector");
        for (int i = 2; i <= 9; i++) {
            require(u32(region, i * SECTOR_BYTES - 4) == 0xaa55_0000L,
                    "Invalid exFAT extended boot sector");
        }
        int checksum = 0;
        int checksumOffset = 11 * SECTOR_BYTES;
        for (int i = 0; i < checksumOffset; i++) {
            if (i == 106 || i == 107 || i == 112) continue;
            checksum = Integer.rotateRight(checksum, 1) + u8(region, i);
        }
        for (int i = checksumOffset; i < region.length; i += 4) {
            require(u32(region, i) == Integer.toUnsignedLong(checksum), "Invalid exFAT boot checksum");
        }
        return region;
    }

    private Extent find(String[] path, long byteLength) throws IOException {
        List<Long> root = metadataChain(rootCluster, -1, false, true);
        DirectoryCursor cursor = new DirectoryCursor(root);
        byte[] bitmapEntry = null;
        byte[] upcaseEntry = null;
        byte[] entry;
        while ((entry = cursor.next()) != null && u8(entry, 0) != 0) {
            int type = u8(entry, 0);
            if (type == 0x81) {
                require(bitmapEntry == null && u8(entry, 1) == 0, "Invalid exFAT allocation bitmap entry");
                bitmapEntry = entry;
            } else if (type == 0x82) {
                require(upcaseEntry == null, "Duplicate exFAT Up-case Table");
                upcaseEntry = entry;
            }
        }
        require(bitmapEntry != null && upcaseEntry != null, "Missing exFAT allocation metadata");
        long bitmapLength = u64(bitmapEntry, 24);
        require(bitmapLength == ceilDiv(clusterCount, 8)
                && ceilDiv(bitmapLength, bytesPerCluster) <= MAX_BITMAP_CLUSTERS,
                "Invalid or oversized exFAT allocation bitmap");
        List<Long> bitmap = metadataChain(u32(bitmapEntry, 20), bitmapLength, false, false);
        upcase = loadUpcase(upcaseEntry);
        List<FileEntry> otherFiles = new ArrayList<>();
        FileEntry target = scanDirectories(root, path, otherFiles);
        Bitmap allocation = new Bitmap(bitmap);
        if (path == null) {
            for (long cluster : metadataClusters.stream().mapToLong(Long::longValue).sorted().toArray()) {
                require(allocation.isAllocated(cluster), "exFAT metadata cluster is free");
            }
            checkFileAllocations(otherFiles, 0, 0, allocation);
            return null;
        }
        require(target != null && !target.directory() && target.contiguous()
                && target.length() == byteLength && target.validLength() == byteLength,
                "Missing, fragmented or incompletely initialized exFAT reservation");
        long first = target.cluster();
        long end = first + ceilDiv(byteLength, bytesPerCluster);
        for (long cluster : metadataClusters.stream().mapToLong(Long::longValue).sorted().toArray()) {
            require((cluster < first || cluster >= end) && allocation.isAllocated(cluster),
                    "exFAT reservation overlaps metadata or metadata is free");
        }
        checkFileAllocations(otherFiles, first, end, null);
        allocation.requireAllocated(first, end);
        return new Extent(clusterLba(first), ceilDiv(byteLength, SECTOR_BYTES));
    }

    private record Directory(List<Long> root, FileEntry file, int pathIndex) {}
    private record FileEntry(String name, boolean directory, boolean contiguous,
                             long cluster, long length, long validLength) {}

    private FileEntry scanDirectories(List<Long> root, String[] path,
                                      List<FileEntry> otherFiles) throws IOException {
        ArrayDeque<Directory> pending = new ArrayDeque<>();
        pending.add(new Directory(root, null, path == null ? -1 : 0));
        FileEntry target = null;
        // Inspect every directory allocation, including directories outside the requested path.
        while (!pending.isEmpty()) {
            Directory directory = pending.removeFirst();
            FileEntry dirFile = directory.file();
            boolean isRoot = dirFile == null;
            List<Long> chain = isRoot ? directory.root() : metadataChain(dirFile.cluster(),
                    dirFile.length(), dirFile.contiguous(), true);
            String expected = directory.pathIndex() < 0 ? null : path[directory.pathIndex()];
            String expectedFolded = expected == null ? null : fold(expected);
            Set<String> names = new HashSet<>();
            boolean volumeLabel = false;
            boolean volumeGuid = false;
            DirectoryCursor cursor = new DirectoryCursor(chain);
            byte[] entry;
            while ((entry = cursor.next()) != null && u8(entry, 0) != 0) {
                int type = u8(entry, 0);
                if ((type & 0x80) == 0) continue;
                if (isRoot && (type == 0x81 || type == 0x82 || type == 0x83 || type == 0xa0)) {
                    if (type == 0x83) {
                        require(!volumeLabel && u8(entry, 1) <= 11, "Invalid exFAT volume label entry");
                        volumeLabel = true;
                    }
                    if (type == 0xa0) {
                        require(!volumeGuid && u8(entry, 1) == 0 && u16(entry, 4) == 0
                                && entryChecksum(0, entry, true) == u16(entry, 2),
                                "Invalid exFAT volume GUID entry");
                        volumeGuid = true;
                    }
                    continue;
                }
                require(type == 0x85 && ++directoryEntries <= MAX_DIRECTORY_ENTRIES,
                        "Unsupported or oversized exFAT directory");
                FileEntry file = readFile(entry, cursor);
                String folded = fold(file.name());
                require(names.add(folded), "Duplicate exFAT directory name");
                boolean matches = folded.equals(expectedFolded);
                int childIndex = -1;
                if (matches) {
                    require(supportedMatch(expected, file.name()), "Unsupported exFAT reservation path spelling");
                    if (directory.pathIndex() == path.length - 1) {
                        target = file;
                    } else {
                        require(file.directory(), "exFAT reservation ancestor is not a directory");
                        childIndex = directory.pathIndex() + 1;
                    }
                }
                if (file.directory() && file.length() > 0) pending.add(new Directory(null, file, childIndex));
                if (!file.directory() && file.length() > 0 && file != target) otherFiles.add(file);
            }
        }
        return target;
    }

    private void checkFileAllocations(List<FileEntry> files, long first, long end, Bitmap preflight)
            throws IOException {
        long stepsRemaining = MAX_DATA_FAT_STEPS;
        for (FileEntry file : files) {
            long cluster = file.cluster();
            long count = ceilDiv(file.length(), bytesPerCluster);
            if (file.contiguous()) {
                require(cluster >= end || cluster + count <= first,
                        "exFAT reservation overlaps a live file");
                if (preflight != null) preflight.requireAllocated(cluster, cluster + count);
                continue;
            }
            require(count <= stepsRemaining, "Oversized exFAT file chains");
            stepsRemaining -= count;
            for (long index = 0; index < count; index++) {
                require(validCluster(cluster), "Invalid exFAT file chain");
                require(cluster < first || cluster >= end, "exFAT reservation overlaps a live file");
                if (preflight != null) require(preflight.isAllocated(cluster), "Live exFAT file has a free cluster");
                cluster = fatNext(cluster);
            }
            require(cluster == END_OF_CHAIN, "Invalid exFAT file chain length or cycle");
        }
    }

    private FileEntry readFile(byte[] primary, DirectoryCursor cursor) throws IOException {
        int checksum = entryChecksum(0, primary, true);
        int secondaries = u8(primary, 1);
        byte[] stream = cursor.next();
        require(stream != null && u8(stream, 0) == 0xc0, "Missing exFAT stream extension");
        checksum = entryChecksum(checksum, stream, false);
        int flags = u8(stream, 1);
        int nameLength = u8(stream, 3);
        int nameEntries = (int) ceilDiv(nameLength, FILENAME_UNITS);
        require((flags == 1 || flags == 3) && nameLength > 0 && secondaries == 1 + nameEntries,
                "Unsupported exFAT file entry layout");
        StringBuilder name = new StringBuilder(nameLength);
        for (int i = 0; i < nameEntries; i++) {
            byte[] part = cursor.next();
            require(part != null && u8(part, 0) == 0xc1 && u8(part, 1) == 0,
                    "Invalid exFAT file name entry");
            checksum = entryChecksum(checksum, part, false);
            for (int j = 2; j < ENTRY_BYTES && name.length() < nameLength; j += 2) {
                name.append((char) u16(part, j));
            }
        }
        String filename = name.toString();
        require(validName(filename) && checksum == u16(primary, 2)
                && nameHash(filename) == u16(stream, 4), "Invalid exFAT file entry or checksum");
        boolean dir = (u16(primary, 4) & 0x10) != 0;
        boolean contiguous = flags == 3;
        long validLength = u64(stream, 8);
        long cluster = u32(stream, 20);
        long length = u64(stream, 24);
        require(validLength >= 0 && length >= validLength
                && length <= clusterCount * bytesPerCluster
                && (length == 0 ? cluster == 0 && !contiguous : validCluster(cluster))
                && (!contiguous || ceilDiv(length, bytesPerCluster) <= clusterCount + 2 - cluster)
                && (!dir || (validLength == length && length % bytesPerCluster == 0)),
                "Invalid exFAT file extent");
        return new FileEntry(filename, dir, contiguous, cluster, length, validLength);
    }

    private List<Long> metadataChain(long first, long length, boolean contiguous, boolean directory)
            throws IOException {
        long count = length < 0 ? -1 : ceilDiv(length, bytesPerCluster);
        ArrayList<Long> chain = new ArrayList<>();
        long cluster = first;
        while (true) {
            require(validCluster(cluster) && metadataClusters.add(cluster),
                    "Invalid, cross-linked or cyclic exFAT metadata chain");
            if (directory) {
                directoryBytes += bytesPerCluster;
                require(directoryBytes <= MAX_DIRECTORY_BYTES, "Oversized exFAT directory tree");
            }
            chain.add(cluster);
            long next = contiguous ? cluster + 1 : fatNext(cluster);
            if (count == chain.size() || next == END_OF_CHAIN) {
                require((count < 0 || count == chain.size()) && (contiguous || next == END_OF_CHAIN),
                        "Invalid exFAT metadata chain length");
                return chain;
            }
            cluster = next;
        }
    }

    private char[] loadUpcase(byte[] entry) throws IOException {
        long length = u64(entry, 24);
        require(length >= 2 && length <= 2L * 65536 && (length & 1) == 0,
                "Invalid length of exFAT Up-case Table");
        List<Long> chain = metadataChain(u32(entry, 20), length, false, false);
        byte[] bytes = new byte[(int) (ceilDiv(length, SECTOR_BYTES) * SECTOR_BYTES)];
        int offset = 0;
        for (long cluster : chain) {
            int count = Math.min(sectorsPerCluster, (bytes.length - offset) / SECTOR_BYTES);
            read(clusterLba(cluster), count, bytes, offset);
            offset += count * SECTOR_BYTES;
        }
        int checksum = 0;
        for (int i = 0; i < length; i++) checksum = Integer.rotateRight(checksum, 1) + u8(bytes, i);
        require(u32(entry, 4) == Integer.toUnsignedLong(checksum), "Invalid exFAT Up-case Table checksum");
        char[] table = new char[65536];
        int index = 0;
        for (int i = 0; i < length; i += 2) {
            require(index < table.length, "Overlong exFAT Up-case Table");
            int mapping = u16(bytes, i);
            if (mapping == 0xffff && index != 0xffff) {
                i += 2;
                require(i < length, "Truncated exFAT Up-case Table");
                int count = u16(bytes, i);
                require(count > 0 && count <= table.length - index, "Invalid exFAT Up-case Table compression");
                int end = index + count;
                for (; index < end; index++) table[index] = (char) index;
            } else {
                table[index++] = (char) mapping;
            }
        }
        require(index == table.length, "Incomplete exFAT Up-case Table");
        for (char c = 0; c < 128; c++) {
            char upper = c >= 'a' && c <= 'z' ? (char) (c - 'a' + 'A') : c;
            require(table[c] == upper, "Invalid mandatory exFAT Up-case Table mapping");
        }
        return table;
    }

    private final class DirectoryCursor {
        private final List<Long> chain;
        private final byte[] window = new byte[WINDOW_SECTORS * SECTOR_BYTES];
        private long offset;
        private int windowOffset;
        private int windowLength;

        DirectoryCursor(List<Long> chain) {
            this.chain = chain;
        }

        byte[] next() throws IOException {
            if (offset == (long) chain.size() * bytesPerCluster) return null;
            if (windowOffset == windowLength) {
                long cluster = chain.get((int) (offset / bytesPerCluster));
                int sector = (int) (offset % bytesPerCluster) / SECTOR_BYTES;
                int count = Math.min(WINDOW_SECTORS, sectorsPerCluster - sector);
                read(clusterLba(cluster) + sector, count, window);
                windowOffset = 0;
                windowLength = count * SECTOR_BYTES;
            }
            byte[] result = Arrays.copyOfRange(window, windowOffset, windowOffset + ENTRY_BYTES);
            windowOffset += ENTRY_BYTES;
            offset += ENTRY_BYTES;
            return result;
        }
    }

    private final class Bitmap {
        private final List<Long> clusters;
        private final byte[] sector = new byte[SECTOR_BYTES];
        private long loaded = -1;

        Bitmap(List<Long> clusters) {
            this.clusters = clusters;
        }

        boolean isAllocated(long cluster) throws IOException {
            long bit = cluster - 2;
            return (readByte(bit / 8) & (1 << (bit % 8))) != 0;
        }

        void requireAllocated(long first, long end) throws IOException {
            long endBit = end - 2;
            for (long bit = first - 2; bit < endBit;) {
                int offset = (int) (bit % 8);
                int count = (int) Math.min(8 - offset, endBit - bit);
                int mask = ((1 << count) - 1) << offset;
                require((readByte(bit / 8) & mask) == mask, "exFAT reservation contains a free cluster");
                bit += count;
            }
        }

        private int readByte(long byteIndex) throws IOException {
            long lba = clusterLba(clusters.get((int) (byteIndex / bytesPerCluster)))
                    + byteIndex % bytesPerCluster / SECTOR_BYTES;
            if (lba != loaded) {
                read(lba, 1, sector);
                loaded = lba;
            }
            return u8(sector, (int) (byteIndex % SECTOR_BYTES));
        }
    }

    private long fatNext(long cluster) throws IOException {
        long byteIndex = cluster * 4;
        long sector = byteIndex / SECTOR_BYTES;
        long windowStart = sector / WINDOW_SECTORS * WINDOW_SECTORS;
        if (windowStart != fatWindowStart) {
            int count = (int) Math.min(WINDOW_SECTORS, fatSectors - windowStart);
            read(fatStart + windowStart, count, fatWindow);
            fatWindowStart = windowStart;
        }
        return u32(fatWindow, (int) (byteIndex - windowStart * SECTOR_BYTES));
    }

    private long clusterLba(long cluster) {
        return heapStart + (cluster - 2) * sectorsPerCluster;
    }

    private boolean validCluster(long cluster) {
        return cluster >= 2 && cluster < clusterCount + 2;
    }

    private void read(long lba, int count, byte[] bytes) throws IOException {
        read(lba, count, bytes, 0);
    }

    private void read(long lba, int count, byte[] bytes, int offset) throws IOException {
        require(lba >= 0 && count > 0 && lba <= diskSectors - count, "exFAT read exceeds disk");
        try {
            disk.readBlocks(lba, bytes, offset, count);
        } catch (RuntimeException error) {
            throw new IOException("Could not read exFAT metadata", error);
        }
    }

    private String fold(String name) {
        char[] chars = name.toCharArray();
        for (int i = 0; i < chars.length; i++) chars[i] = upcase[chars[i]];
        return new String(chars);
    }

    private int nameHash(String name) {
        int hash = 0;
        for (int i = 0; i < name.length(); i++) {
            char upper = upcase[name.charAt(i)];
            hash = rotate16(hash, upper & 255);
            hash = rotate16(hash, upper >>> 8);
        }
        return hash;
    }

    private static boolean supportedMatch(String expected, String actual) {
        if (expected.equals(actual)) return true;
        for (int i = 0; i < expected.length(); i++) {
            if (expected.charAt(i) > 127 || actual.charAt(i) > 127) return false;
        }
        return true;
    }

    private static boolean validName(String name) {
        if (name.isEmpty() || name.length() > 255 || name.equals(".") || name.equals("..")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || "\"*/:<>?\\|".indexOf(c) >= 0 || Character.isLowSurrogate(c)) return false;
            if (Character.isHighSurrogate(c)
                    && (++i == name.length() || !Character.isLowSurrogate(name.charAt(i)))) return false;
        }
        return true;
    }

    private static int entryChecksum(int checksum, byte[] entry, boolean primary) {
        for (int i = 0; i < ENTRY_BYTES; i++) {
            if (!primary || (i != 2 && i != 3)) checksum = rotate16(checksum, u8(entry, i));
        }
        return checksum;
    }

    private static int rotate16(int value, int next) {
        return (((value >>> 1) | ((value & 1) << 15)) + next) & 0xffff;
    }

    private static long ceilDiv(long length, long unit) {
        return length == 0 ? 0 : 1 + (length - 1) / unit;
    }

    private static boolean asciiAt(byte[] bytes, int offset, String expected) {
        return new String(bytes, offset, expected.length(), StandardCharsets.US_ASCII).equals(expected);
    }

    private static boolean allZero(byte[] bytes, int start, int end) {
        for (int i = start; i < end; i++) if (bytes[i] != 0) return false;
        return true;
    }

    private static int u8(byte[] bytes, int index) {
        return bytes[index] & 255;
    }

    private static int u16(byte[] bytes, int index) {
        return u8(bytes, index) | (u8(bytes, index + 1) << 8);
    }

    private static long u32(byte[] bytes, int index) {
        return u16(bytes, index) | ((long) u16(bytes, index + 2) << 16);
    }

    private static long u64(byte[] bytes, int index) {
        return u32(bytes, index) | (u32(bytes, index + 4) << 32);
    }

    private static void require(boolean condition, String reason) throws IOException {
        if (!condition) throw new IOException(reason);
    }
}
