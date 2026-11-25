package jp.nano.rawgallery.camera;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Exercises the direct source stream using only synthetic in-memory sectors. */
public final class DirectExFatSourceCheck {
    private static final int SECTOR = 512;
    private static final int FAT = 24;
    private static final int HEAP = 32;
    private static final int SECTORS = 512;
    private static final int LENGTH = 2 * SECTOR + 3;
    private static final int FIRST = 8;
    private static final int SECOND = 11;
    private static final int THIRD = 15;
    private static final int BITMAP = 6;
    private static final int UPCASE = 7;

    public static void main(String[] args) throws IOException {
        Image contiguous = new Image(true, LENGTH, LENGTH);
        byte[] buffer = new byte[LENGTH + 10];
        try (InputStream in = contiguous.open()) {
            if (in.read(buffer, 3, LENGTH) != 2 * SECTOR || contiguous.lastDest != buffer
                    || contiguous.lastOffset != 3 || contiguous.lastCount != 2) {
                throw new AssertionError("Aligned source was not read directly into the caller's buffer");
            }
            if (in.read() != 200 || in.read(buffer, 3 + 2 * SECTOR, 2) != 2) {
                throw new AssertionError("Final source sector was not read to the logical limit");
            }
            int reads = contiguous.readCalls;
            if (in.read() != -1 || in.read(buffer, 0, 1) != -1 || in.read(buffer, 0, 0) != 0
                    || contiguous.readCalls != reads) throw new AssertionError("Read at EOF");
        }
        if ((buffer[3] & 255) != 100 || (buffer[SECTOR + 3] & 255) != 150
                || (buffer[3 + 2 * SECTOR] & 255) != 200 || buffer[3 + LENGTH] != 0) {
            throw new AssertionError("Invalid contiguous output bytes");
        }

        Image chained = new Image(false, LENGTH, LENGTH);
        try (InputStream in = chained.open()) {
            byte[] logical = in.readAllBytes();
            if (logical.length != LENGTH || (logical[0] & 255) != 100 || (logical[SECTOR] & 255) != 150
                    || (logical[2 * SECTOR] & 255) != 200) throw new AssertionError("Wrong FAT-chain data");
            in.close();
            rejects(in::read, "closed");
        }
        Image shortChain = new Image(false, LENGTH, LENGTH);
        shortChain.fat(FIRST, -1);
        rejects(() -> shortChain.open().readAllBytes(), "source chain");
        Image longChain = new Image(false, LENGTH, LENGTH);
        longChain.fat(THIRD, 20);
        rejects(() -> longChain.open().readAllBytes(), "end of exFAT source chain");
        Image cycle = new Image(false, 12 * SECTOR, 12 * SECTOR);
        cycle.fat(THIRD, SECOND);
        rejects(() -> cycle.open().readAllBytes(), "cyclic");
        Image invalidCluster = new Image(false, LENGTH, LENGTH);
        invalidCluster.fat(FIRST, SECTORS + 3);
        rejects(() -> invalidCluster.open().readAllBytes(), "source chain");
        Image wrongVdl = new Image(true, LENGTH, LENGTH - 1);
        rejects(wrongVdl::open, "incompletely initialized");
        Image badChecksum = new Image(true, LENGTH, LENGTH);
        badChecksum.disk[badChecksum.fileEntry + 2] ^= 1;
        rejects(badChecksum::open, "Invalid exFAT directory entry");
        Image badGeometry = new Image(true, LENGTH, LENGTH);
        badGeometry.data.putInt(84, 1);
        rejects(badGeometry::open, "source geometry");
        Image badExtent = new Image(true, (SECTORS - HEAP) * SECTOR, (SECTORS - HEAP) * SECTOR);
        rejects(badExtent::open, "source extent");
        ExFat fs = contiguous.mounted();
        fs.listDirectMediaUnderDcim(() -> false);
        rejects(() -> fs.openForDirectTransfer(new ExFat.Node("A.JPG", false, FIRST, LENGTH, true, 0)),
                "verified DCIM listing");
        allocationChecks();
        fullDcimListing();
        cancelledBlockRead();
        System.out.println("PASS bounded exFAT source stream, allocation bitmap, metadata ownership, and cancellation");
    }

    private static void cancelledBlockRead() throws IOException {
        Image disk = new Image(true, LENGTH, LENGTH);
        try (InputStream in = disk.open()) {
            disk.interruptAtLba = disk.cluster(FIRST) / SECTOR;
            try {
                in.read();
                throw new AssertionError("Cancelled block read returned source bytes");
            } catch (InterruptedIOException expected) {
                // The caller sees its original cancellation, so it can restore both cards.
            }
        }
    }

    private static void allocationChecks() {
        Image free = new Image(true, LENGTH, LENGTH);
        free.allocated(FIRST + 1, false);
        rejects(free::open, "free cluster");
        Image freeChain = new Image(false, LENGTH, LENGTH);
        freeChain.allocated(THIRD, false);
        rejects(freeChain::open, "free cluster");
        Image overlapsBitmap = new Image(true, LENGTH, LENGTH);
        overlapsBitmap.entry(overlapsBitmap.fileEntry, "A.JPG", false, true, BITMAP, LENGTH, LENGTH);
        rejects(overlapsBitmap::open, "source file overlaps metadata");
        Image overlapsUnselectedDirectory = new Image(false, LENGTH, LENGTH);
        int other = 14;
        overlapsUnselectedDirectory.entry(overlapsUnselectedDirectory.cluster(2) + 160,
                "OTHER", true, true, other, SECTOR, SECTOR);
        overlapsUnselectedDirectory.fat(SECOND, other);
        rejects(overlapsUnselectedDirectory::open, "source file overlaps metadata");
        Image overlapsUnselectedFile = new Image(true, LENGTH, LENGTH);
        overlapsUnselectedFile.entry(overlapsUnselectedFile.cluster(2) + 160,
                "OTHER.JPG", false, true, FIRST + 1, SECTOR, SECTOR);
        rejects(overlapsUnselectedFile::open, "source file overlaps another file");
        Image freeDirectory = new Image(true, LENGTH, LENGTH);
        freeDirectory.allocated(4, false);
        rejects(freeDirectory::open, "free cluster");
        Image malformedDirectory = new Image(true, LENGTH, LENGTH);
        malformedDirectory.entry(malformedDirectory.cluster(3), "100CAM", true, false, 4,
                2 * SECTOR, 2 * SECTOR);
        malformedDirectory.fat(4, -1);
        rejects(malformedDirectory::open, "metadata chain");
    }

    private static void fullDcimListing() throws IOException {
        Image disk = new Image(true, LENGTH, LENGTH);
        disk.entry(disk.cluster(3) + 96, "ROOT.JPG", false, true, 11, LENGTH, LENGTH);
        disk.entry(disk.cluster(4) + 96, "SUB", true, true, 5, SECTOR, SECTOR);
        disk.entry(disk.cluster(5), "B.ORF", false, true, 20, LENGTH, LENGTH);
        var fs = disk.mounted();
        var names = fs.listDirectMediaUnderDcim(() -> false).stream().map(ExFat.Node::getRelativePath).toList();
        if (!names.equals(List.of("DCIM/100CAM/A.JPG", "DCIM/100CAM/SUB/B.ORF", "DCIM/ROOT.JPG")))
            throw new AssertionError("Direct listing missed root or nested DCIM photos: " + names);
        if (fs.listMediaUnderDcim().size() != 1)
            throw new AssertionError("Direct listing changed the legacy depth");
        rejects(() -> fs.listDirectMediaUnderDcim(() -> true), "cancelled");
        disk.disk[disk.cluster(5) + 2] ^= 1;
        rejects(() -> fs.listDirectMediaUnderDcim(() -> false), "Invalid exFAT directory entry");
    }

    @FunctionalInterface
    private interface Attempt { void run() throws IOException; }

    private static void rejects(Attempt attempt, String reason) {
        try {
            attempt.run();
        } catch (IOException failure) {
            if (failure.getMessage().contains(reason)) return;
            throw new AssertionError("Expected " + reason + ", got " + failure.getMessage(), failure);
        }
        throw new AssertionError("Accepted invalid source: " + reason);
    }

    private static final class Image implements BlockDevice {
        final byte[] disk = new byte[SECTORS * SECTOR];
        final ByteBuffer data = ByteBuffer.wrap(disk).order(ByteOrder.LITTLE_ENDIAN);
        final int fileEntry = cluster(4);
        byte[] lastDest;
        int lastOffset;
        int lastCount;
        int readCalls;
        long interruptAtLba = -1;

        Image(boolean contiguous, long length, long validLength) {
            data.position(3);
            data.put("EXFAT   ".getBytes(StandardCharsets.US_ASCII));
            data.putLong(72, SECTORS);
            data.putInt(80, FAT);
            data.putInt(84, HEAP - FAT);
            data.putInt(88, HEAP);
            data.putInt(92, SECTORS - HEAP);
            data.putInt(96, 2);
            disk[108] = 9;
            disk[110] = 1;
            fat(2, -1);
            fat(BITMAP, -1);
            fat(UPCASE, -1);
            fat(FIRST, SECOND);
            fat(SECOND, THIRD);
            fat(THIRD, -1);
            entry(cluster(2), "DCIM", true, true, 3, SECTOR, SECTOR);
            int bitmapEntry = cluster(2) + 96;
            disk[bitmapEntry] = (byte) 0x81;
            data.putInt(bitmapEntry + 20, BITMAP);
            data.putLong(bitmapEntry + 24, (SECTORS - HEAP + 7) / 8);
            int upcaseEntry = bitmapEntry + 32;
            disk[upcaseEntry] = (byte) 0x82;
            data.putInt(upcaseEntry + 20, UPCASE);
            data.putLong(upcaseEntry + 24, 2);
            entry(cluster(3), "100CAM", true, true, 4, SECTOR, SECTOR);
            entry(fileEntry, "A.JPG", false, contiguous, FIRST, length, validLength);
            for (int number = 2; number <= 24; number++) allocated(number, true);
            fill(FIRST, 100);
            fill(contiguous ? FIRST + 1 : SECOND, 150);
            fill(contiguous ? FIRST + 2 : THIRD, 200);
        }

        InputStream open() throws IOException {
            ExFat fs = mounted();
            return fs.openForDirectTransfer(fs.listDirectMediaUnderDcim(() -> false).get(0));
        }

        ExFat mounted() {
            ExFat volume = new ExFat(this, 0);
            volume.mount();
            return volume;
        }

        @Override public int getBlockSize() { return SECTOR; }

        @Override public void readBlocks(long lba, byte[] dest, int offset, int count) {
            if (interruptAtLba >= lba && interruptAtLba < lba + count)
                throw new UncheckedIOException(new InterruptedIOException("cancelled block read"));
            lastDest = dest;
            lastOffset = offset;
            lastCount = count;
            readCalls++;
            System.arraycopy(disk, (int) lba * SECTOR, dest, offset, count * SECTOR);
        }

        int cluster(int number) { return (HEAP + number - 2) * SECTOR; }

        void fill(int cluster, int value) {
            Arrays.fill(disk, cluster(cluster), cluster(cluster) + SECTOR, (byte) value);
        }

        void fat(int cluster, int next) { data.putInt(FAT * SECTOR + cluster * 4, next); }

        void allocated(int cluster, boolean allocated) {
            int index = cluster(BITMAP) + (cluster - 2) / 8;
            int bit = 1 << ((cluster - 2) % 8);
            if (allocated) disk[index] |= (byte) bit;
            else disk[index] &= (byte) ~bit;
        }

        void entry(int offset, String name, boolean directory, boolean contiguous, int cluster,
                   long length, long validLength) {
            disk[offset] = (byte) 0x85;
            disk[offset + 1] = 2;
            disk[offset + 4] = (byte) (directory ? 0x10 : 0x20);
            disk[offset + 32] = (byte) 0xc0;
            disk[offset + 33] = (byte) (contiguous ? 3 : 1);
            disk[offset + 35] = (byte) name.length();
            data.putLong(offset + 40, validLength);
            data.putInt(offset + 52, cluster);
            data.putLong(offset + 56, length);
            disk[offset + 64] = (byte) 0xc1;
            for (int i = 0; i < name.length(); i++) data.putChar(offset + 66 + 2 * i, name.charAt(i));
            int checksum = 0;
            for (int i = 0; i < 96; i++) {
                if (i == 2 || i == 3) continue;
                checksum = (((checksum >>> 1) | ((checksum & 1) << 15)) + (disk[offset + i] & 255)) & 65535;
            }
            data.putShort(offset + 2, (short) checksum);
        }
    }
}
