package jp.nano.rawgallery.camera;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Exercises the direct-import locator using only synthetic in-memory sectors. */
public final class DirectExFatReservationCheck {
    private static final int SECTOR = 512;
    private static final int VOLUME_SECTORS = 8192;
    private static final int FAT = 24;
    private static final int FAT_SECTORS = 64;
    private static final int HEAP = FAT + FAT_SECTORS;
    private static final int FIRST_TARGET = 6;
    private static final long LENGTH = 2 * SECTOR + 3;
    private static final String UUID = "1234-ABCD";
    private static final String FILENAME = "Reservation Long.JPG";
    private static final String PATH = "Import/" + FILENAME;

    public static void main(String[] args) throws IOException {
        Image image = new Image(0);
        check(image, "import/reservation long.jpg", FIRST_TARGET);
        check(new Image(2048), PATH, FIRST_TARGET);
        image.file(image.root() + 160, "Root.JPG", false, true, 4099, LENGTH, LENGTH);
        check(image, "root.jpg", 4099); // The allocated target uses the second bitmap cluster.
        Arrays.fill(image.disk, image.cluster(5), image.cluster(5) + SECTOR, (byte) 0);
        image.file(image.cluster(5), "夏の写真.jpg", false, true, FIRST_TARGET, LENGTH, LENGTH);
        check(image, "Import/夏の写真.jpg", FIRST_TARGET);
        rejects(image, "Import/夏の写真.JPG", LENGTH, "path spelling");
        rejects(new Image(0), FILENAME, LENGTH, "Missing");
        rejects(new Image(0), "Import//" + FILENAME, LENGTH, "path component");
        rejects(new Image(0), PATH, LENGTH - 1, "Missing");
        rejectsWithUuid(new Image(2048), "1234-ABCE", "UUID mismatch");

        image = new Image(0);
        image.file(image.cluster(5) + 128, "reservation long.jpg", false, true, 20, LENGTH, LENGTH);
        rejects(image, PATH, LENGTH, "Duplicate");
        image = new Image(0);
        image.file(image.cluster(5), FILENAME, false, false, FIRST_TARGET, LENGTH, LENGTH);
        rejects(image, PATH, LENGTH, "fragmented");
        image = new Image(0);
        image.file(image.cluster(5), FILENAME, false, true, FIRST_TARGET, LENGTH, LENGTH - 1);
        rejects(image, PATH, LENGTH, "incompletely initialized");
        image = new Image(0);
        image.disk[image.cluster(5) + 2] ^= 1;
        rejects(image, PATH, LENGTH, "checksum");
        image = new Image(0);
        image.disk[image.cluster(5) + 96] = 0;
        rejects(image, PATH, LENGTH, "file name entry");
        image = new Image(0);
        image.file(image.cluster(5), FILENAME, false, true, VOLUME_SECTORS - HEAP + 1, LENGTH, LENGTH);
        rejects(image, PATH, LENGTH, "file extent");
        image = new Image(0);
        image.disk[image.cluster(3)] &= ~(1 << (7 - 2));
        rejects(image, PATH, LENGTH, "free cluster");

        for (int overlap : new int[] {2, 3, 4, 5, 9, 12}) {
            image = new Image(0);
            image.file(image.cluster(5), FILENAME, false, true, overlap, LENGTH, LENGTH);
            rejects(image, PATH, LENGTH, "overlaps metadata");
        }
        image = new Image(0);
        image.file(image.root() + 160, "Unrelated", true, true, FIRST_TARGET + 1, SECTOR, SECTOR);
        rejects(image, PATH, LENGTH, "overlaps metadata");
        image = new Image(0);
        image.file(image.root() + 160, "Existing.jpg", false, true, FIRST_TARGET + 1, SECTOR, SECTOR);
        rejects(image, PATH, LENGTH, "overlaps a live file");
        image = new Image(0);
        image.file(image.root() + 160, "Existing.jpg", false, false, 21, 2 * SECTOR, 2 * SECTOR);
        image.fat(21, FIRST_TARGET + 1);
        rejects(image, PATH, LENGTH, "overlaps a live file");
        image.fat(21, 21);
        rejects(image, PATH, LENGTH, "chain length or cycle");
        image.fat(21, 22);
        image.fat(22, -1);
        check(image, PATH, FIRST_TARGET);
        image = new Image(0);
        image.fat(3, 3);
        rejects(image, PATH, LENGTH, "cyclic");
        image = new Image(0);
        image.fat(3, -1);
        rejects(image, PATH, LENGTH, "chain length");
        image = new Image(0);
        image.fat(5, 5);
        rejects(image, PATH, LENGTH, "cyclic");
        image = new Image(0);
        image.fat(2, VOLUME_SECTORS + 2);
        rejects(image, PATH, LENGTH, "metadata chain");
        image = new Image(0);
        image.integers.putShort(106, (short) 2);
        rejects(image, PATH, LENGTH, "unclean");
        image = new Image(0);
        image.disk[11 * SECTOR] ^= 1;
        rejects(image, PATH, LENGTH, "boot checksum");
        image = new Image(0);
        image.disk[23 * SECTOR] ^= 1;
        rejects(image, PATH, LENGTH, "boot checksum");
        image = new Image(2048);
        image.disk[462 + 4] = 7;
        rejects(image, PATH, LENGTH, "one basic exFAT MBR");
        image = new Image(2048);
        image.ascii(SECTOR, "EFI PART");
        rejects(image, PATH, LENGTH, "GPT");
        image = new Image(2048);
        image.disk[446 + 4] = (byte) 0xee;
        rejects(image, PATH, LENGTH, "one basic exFAT MBR");
        image = new Image(2048);
        image.availableSectors = 2048 + VOLUME_SECTORS - 1;
        rejects(image, PATH, LENGTH, "partition range");
        image = new Image(0);
        image.availableSectors = VOLUME_SECTORS - 1;
        rejects(image, PATH, LENGTH, "geometry");
        image = new Image(0);
        image.backingSectors = VOLUME_SECTORS - 1;
        rejects(image, PATH, LENGTH, "Could not read");
        System.out.println("PASS direct exFAT reservation lookup, format boundaries and corrupted metadata");
    }

    private static void check(Image image, String path, int cluster) throws IOException {
        var found = DirectExFatReservation.locate(image, image.availableSectors, UUID, path, LENGTH);
        if (found.firstLba() != image.cluster(cluster) / SECTOR || found.sectors() != 3) {
            throw new AssertionError("Wrong reservation extent " + found);
        }
    }

    private static void rejects(Image image, String path, long length, String reason) {
        reject(() -> DirectExFatReservation.locate(image, image.availableSectors, UUID, path, length), reason);
    }

    private static void rejectsWithUuid(Image image, String uuid, String reason) {
        reject(() -> DirectExFatReservation.locate(image, image.availableSectors, uuid, PATH, LENGTH), reason);
    }

    @FunctionalInterface
    private interface Attempt { void run() throws IOException; }

    private static void reject(Attempt attempt, String reason) {
        try {
            attempt.run();
        } catch (IOException failure) {
            if (failure.getMessage().contains(reason)) return;
            throw new AssertionError("Expected " + reason + ", got " + failure.getMessage(), failure);
        }
        throw new AssertionError("Accepted image that should fail with " + reason);
    }

    private static final class Image implements BlockDevice {
        final byte[] disk;
        final ByteBuffer integers;
        final int partition;
        int availableSectors;
        int backingSectors;

        Image(int partition) {
            this.partition = partition;
            availableSectors = partition + VOLUME_SECTORS;
            backingSectors = availableSectors;
            disk = new byte[availableSectors * SECTOR];
            integers = ByteBuffer.wrap(disk).order(ByteOrder.LITTLE_ENDIAN);
            bootRegion();
            fat(0, 0xfffffff8);
            fat(1, -1);
            fat(2, -1);
            fat(3, 9);
            fat(9, -1);
            fat(4, -1);
            fat(5, 12);
            fat(12, -1);
            allocate(2, 3, 4, 5, 6, 7, 8, 9, 12, 4099, 4100, 4101);
            disk[root()] = (byte) 0x81;
            integers.putInt(root() + 20, 3);
            integers.putLong(root() + 24, (VOLUME_SECTORS - HEAP + 7) / 8);
            upcase();
            file(root() + 64, "Import", true, false, 5, 2 * SECTOR, 2 * SECTOR);
            file(cluster(5), FILENAME, false, true, FIRST_TARGET, LENGTH, LENGTH);
        }

        @Override public int getBlockSize() { return SECTOR; }

        @Override public void readBlocks(long lba, byte[] data, int offset, int count) {
            if (lba < 0 || lba + count > backingSectors) throw new IndexOutOfBoundsException();
            System.arraycopy(disk, (int) lba * SECTOR, data, offset, count * SECTOR);
        }

        int root() { return cluster(2); }

        int cluster(int number) { return (partition + HEAP + number - 2) * SECTOR; }

        void ascii(int offset, String value) {
            byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(bytes, 0, disk, offset, bytes.length);
        }

        void fat(int cluster, int value) { integers.putInt((partition + FAT) * SECTOR + cluster * 4, value); }

        void allocate(int... clusters) {
            for (int cluster : clusters) {
                int bit = cluster - 2;
                int sector = cluster(bit < SECTOR * 8 ? 3 : 9);
                disk[sector + bit / 8 % SECTOR] |= (byte) (1 << (bit % 8));
            }
        }

        void bootRegion() {
            int main = partition * SECTOR;
            disk[main] = (byte) 0xeb;
            disk[main + 1] = 0x76;
            disk[main + 2] = (byte) 0x90;
            ascii(main + 3, "EXFAT   ");
            integers.putLong(main + 64, partition);
            integers.putLong(main + 72, VOLUME_SECTORS);
            integers.putInt(main + 80, FAT);
            integers.putInt(main + 84, FAT_SECTORS);
            integers.putInt(main + 88, HEAP);
            integers.putInt(main + 92, VOLUME_SECTORS - HEAP);
            integers.putInt(main + 96, 2);
            integers.putInt(main + 100, 0x1234abcd);
            integers.putShort(main + 104, (short) 0x100);
            disk[main + 108] = 9;
            disk[main + 110] = 1;
            integers.putShort(main + 510, (short) 0xaa55);
            for (int i = 2; i <= 9; ++i) integers.putInt(main + i * SECTOR - 4, 0xaa550000);
            int checksum = 0;
            for (int i = 0; i < 11 * SECTOR; i++) {
                if (i != 106 && i != 107 && i != 112) checksum = rotate32(checksum, disk[main + i]);
            }
            for (int i = 11 * SECTOR; i < 12 * SECTOR; i += 4) integers.putInt(main + i, checksum);
            System.arraycopy(disk, main, disk, main + 12 * SECTOR, 12 * SECTOR);
            if (partition > 0) {
                disk[446 + 4] = 7;
                integers.putInt(446 + 8, partition);
                integers.putInt(446 + 12, VOLUME_SECTORS);
                integers.putShort(510, (short) 0xaa55);
            }
        }

        void upcase() {
            int table = cluster(4);
            for (int i = 0; i < 128; i++) integers.putChar(table + i * 2, uppercase((char) i));
            integers.putShort(table + 256, (short) 0xffff);
            integers.putShort(table + 258, (short) (65536 - 128));
            int checksum = 0;
            for (int i = 0; i < 260; i++) checksum = rotate32(checksum, disk[table + i]);
            int entry = root() + 32;
            disk[entry] = (byte) 0x82;
            integers.putInt(entry + 4, checksum);
            integers.putInt(entry + 20, 4);
            integers.putLong(entry + 24, 260);
        }

        void file(int offset, String name, boolean directory, boolean contiguous, int first,
                  long length, long validLength) {
            int nameEntryCount = (name.length() + 14) / 15;
            int setLength = (2 + nameEntryCount) * 32;
            Arrays.fill(disk, offset, offset + setLength, (byte) 0);
            disk[offset] = (byte) 0x85;
            disk[offset + 1] = (byte) (1 + nameEntryCount);
            disk[offset + 4] = (byte) (directory ? 0x10 : 0x20);
            disk[offset + 32] = (byte) 0xc0;
            disk[offset + 33] = (byte) (contiguous ? 3 : 1);
            disk[offset + 35] = (byte) name.length();
            int nameHash = 0;
            for (int i = 0; i < name.length(); i++) {
                char c = uppercase(name.charAt(i));
                nameHash = rotate16(nameHash, c & 255);
                nameHash = rotate16(nameHash, c >>> 8);
            }
            integers.putShort(offset + 36, (short) nameHash);
            integers.putLong(offset + 40, validLength);
            integers.putInt(offset + 52, first);
            integers.putLong(offset + 56, length);
            for (int i = 0; i < nameEntryCount; i++) {
                int entry = offset + 64 + i * 32;
                disk[entry] = (byte) 0xc1;
                for (int j = 0; j < 15 && i * 15 + j < name.length(); j++) {
                    integers.putChar(entry + 2 + j * 2, name.charAt(i * 15 + j));
                }
            }
            int setChecksum = 0;
            for (int i = 0; i < setLength; i++) {
                if (i != 2 && i != 3) setChecksum = rotate16(setChecksum, disk[offset + i] & 255);
            }
            integers.putShort(offset + 2, (short) setChecksum);
        }

        private static char uppercase(char c) { return c < 128 ? Character.toUpperCase(c) : c; }
    }

    private static int rotate32(int checksum, byte next) {
        return Integer.rotateRight(checksum, 1) + (next & 255);
    }

    private static int rotate16(int value, int next) {
        return (((value >>> 1) | ((value & 1) << 15)) + next) & 0xffff;
    }
}
