package jp.nano.rawgallery;

import jp.nano.rawgallery.camera.BlockDevice;
import jp.nano.rawgallery.camera.ExFat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class CameraExFatCheck {
    public static void main(String[] args) {
        // One sector per cluster. FAT at sector 1, heap at sector 2.
        byte[] disk = new byte[8 * 512];
        var header = ByteBuffer.wrap(disk).order(ByteOrder.LITTLE_ENDIAN);
        header.position(3);
        header.put("EXFAT   ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(80, 1);
        header.putInt(88, 2);
        header.putInt(92, 6);
        header.putInt(96, 2);
        header.put(108, (byte) 9);
        header.put(109, (byte) 0);
        Arrays.fill(disk, 3 * 512, 4 * 512, (byte) 123);
        Arrays.fill(disk, 4 * 512, 5 * 512, (byte) 45);
        var fs = new ExFat(new BlockDevice() {
            public int getBlockSize() { return 512; }
            public void readBlocks(long lba, byte[] dest, int offset, int count) {
                System.arraycopy(disk, (int) lba * 512, dest, offset, count * 512);
            }
        }, 0);
        fs.mount();
        var photo = new ExFat.Node("SAMPLE.RW2", false, 3, 1024, false, 0);
        header.putInt(512 + 3 * 4, 4);
        var copied = new ByteArrayOutputStream();
        fs.copyTo(photo, copied);
        byte[] bytes = copied.toByteArray();
        if (bytes.length != 1024 || bytes[0] != 123 || bytes[512] != 45) throw new AssertionError();
        header.putInt(512 + 3 * 4, 0xffffffff); // Premature end of chain.
        rejects(fs, photo);
        header.putInt(512 + 3 * 4, 3); // Cyclic chain.
        rejects(fs, photo);
        directoryScanChecks();
        System.out.println("PASS exFAT file chains, normal directories, repeated directory clusters and aggregate entry budget");
    }

    private static void directoryScanChecks() {
        // Two camera folders sharing one cluster must not be traversed twice.
        byte[] disk = volume(16);
        entry(disk, 2 * 512, "DCIM", true, 3, 512);
        entry(disk, 3 * 512, "100CAM", true, 4, 512);
        entry(disk, 3 * 512 + 96, "101CAM", true, 5, 512);
        entry(disk, 4 * 512, "A.JPG", false, 6, 1);
        entry(disk, 5 * 512, "B.JPG", false, 7, 1);
        ExFat fs = mounted(disk);
        if (fs.listMediaUnderDcim().size() != 2) throw new AssertionError("Normal folders lost");
        entry(disk, 3 * 512 + 96, "101CAM", true, 4, 512);
        rejectsScan(fs, "Repeated");
        entry(disk, 3 * 512 + 96, "101CAM", true, 999, 512);
        rejectsScan(fs, "Invalid exFAT directory cluster");
        // Each folder is below the entry limit; their aggregate must be rejected.
        int files = 50_001;
        int directoryBytes = ((files * 96 + 511) / 512) * 512;
        disk = volume(2 * directoryBytes / 512 + 8);
        entry(disk, 2 * 512, "DCIM", true, 3, 512);
        entry(disk, 3 * 512, "100CAM", true, 4, directoryBytes);
        int second = 4 + directoryBytes / 512;
        entry(disk, 3 * 512 + 96, "101CAM", true, second, directoryBytes);
        for (int i = 0; i < files; i++) {
            entry(disk, 4 * 512 + i * 96, "A.JPG", false, 5, 1);
            entry(disk, second * 512 + i * 96, "B.JPG", false, 5, 1);
        }
        rejectsScan(mounted(disk), "Too many");
    }

    private static byte[] volume(int clusters) {
        byte[] disk = new byte[(clusters + 2) * 512];
        var header = ByteBuffer.wrap(disk).order(ByteOrder.LITTLE_ENDIAN);
        header.position(3);
        header.put("EXFAT   ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(80, 1); header.putInt(88, 2);
        header.putInt(92, clusters); header.putInt(96, 2);
        header.put(108, (byte)9); header.put(109, (byte)0);
        return disk;
    }

    private static void entry(byte[] disk, int at, String name, boolean directory, int cluster, int length) {
        var data = ByteBuffer.wrap(disk).order(ByteOrder.LITTLE_ENDIAN);
        disk[at] = (byte)0x85; disk[at + 1] = 2;
        data.putShort(at + 4, (short)(directory ? 0x10 : 0));
        disk[at + 32] = (byte)0xc0; disk[at + 33] = 2;
        disk[at + 35] = (byte)name.length();
        data.putInt(at + 52, cluster); data.putLong(at + 56, length);
        disk[at + 64] = (byte)0xc1;
        for (int i=0; i<name.length(); i++) data.putChar(at + 66 + i*2, name.charAt(i));
    }

    private static ExFat mounted(byte[] disk) {
        var fs = new ExFat(new BlockDevice() {
            public int getBlockSize() { return 512; }
            public void readBlocks(long lba, byte[] dest, int offset, int count) {
                System.arraycopy(disk, (int)lba*512, dest, offset, count*512);
            }
        }, 0);
        fs.mount(); return fs;
    }

    private static void rejectsScan(ExFat fs, String message) {
        try {
            fs.listMediaUnderDcim();
            throw new AssertionError("Invalid directory scan accepted");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains(message)) throw expected;
        }
    }

    private static void rejects(ExFat fs, ExFat.Node photo) {
        try {
            fs.copyTo(photo, new ByteArrayOutputStream());
            throw new AssertionError("Invalid chain was accepted");
        } catch (IllegalArgumentException expected) {
            // Caller rolls back the pending MediaStore row.
        }
    }
}
