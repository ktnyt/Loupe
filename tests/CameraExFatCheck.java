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
        System.out.println("PASS exFAT full, truncated and cyclic chains");
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
