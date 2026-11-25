package jp.nano.rawgallery.camera;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Transfers one photo into a previously synced, exclusive Android reservation. */
final class DirectPhotoTransfer {
    static final int WINDOW = 64 * 1024;
    private static final int SECTOR = 512;

    interface Target {
        void read(long lba, byte[] buffer, int offset, int sectors) throws IOException;
        void permit(long firstLba, long sectors);
        void write(long lba, byte[] buffer, int offset, int sectors) throws IOException;
        void sync() throws IOException;
    }

    private DirectPhotoTransfer() {}

    static void copy(DirectUsbTransport transport, InputStream input, DirectExFatReservation.Extent extent,
            long bytes, DirectReservationMarker marker, String expectedSha, BooleanSupplier cancelled,
            LongConsumer progress) throws IOException {
        copy(new Target() {
            @Override public void read(long lba, byte[] buffer, int offset, int sectors) throws IOException {
                transport.readBlocks(1, lba, buffer, offset, sectors);
            }
            @Override public void permit(long lba, long sectors) {
                transport.authorizeTarget(lba, sectors);
            }
            @Override public void write(long lba, byte[] buffer, int offset, int sectors) throws IOException {
                transport.writeTargetBlocks(lba, buffer, offset, sectors);
            }
            @Override public void sync() throws IOException {
                transport.syncTarget();
            }
        }, input, extent.firstLba(), extent.sectors(), bytes, marker, expectedSha, cancelled, progress);
        transport.completeTarget();
    }

    static void copy(Target target, InputStream input, long startLba, long sectors, long bytes,
            DirectReservationMarker marker, String expectedSha, BooleanSupplier cancelled, LongConsumer progress)
            throws IOException {
        if (bytes <= 0 || bytes > CameraImportFile.MAX_BYTES || startLba <= 0
                || sectors != sectorCount(bytes) || startLba > Long.MAX_VALUE - sectors)
            throw new IOException("microSDの書き込み範囲が不正です。");

        byte[] buffer = new byte[2 * WINDOW];
        for (long offset = 0; offset < bytes; offset += WINDOW) {
            CameraPhotoPublisher.check(cancelled);
            int length = chunk(bytes, offset);
            target.read(startLba + offset / SECTOR, buffer, WINDOW, (int) sectorCount(length));
            marker.requireMatch(buffer, WINDOW, length, offset);
        }
        CameraPhotoPublisher.check(cancelled);
        target.permit(startLba, sectors);

        MessageDigest sourceSha = digest();
        for (long offset = 0; offset < bytes; offset += WINDOW) {
            CameraPhotoPublisher.check(cancelled);
            int length = chunk(bytes, offset);
            int blocks = (int) sectorCount(length);
            int paddedLength = blocks * SECTOR;
            readExactly(input, buffer, length, cancelled);
            sourceSha.update(buffer, 0, length);
            long lba = startLba + offset / SECTOR;
            // Recheck the current target just before every WRITE. The marker ends at the
            // logical length. Preserve the rest of the last sector byte for byte.
            target.read(lba, buffer, WINDOW, blocks);
            marker.requireMatch(buffer, WINDOW, length, offset);
            System.arraycopy(buffer, WINDOW + length, buffer, length, paddedLength - length);
            CameraPhotoPublisher.check(cancelled);
            target.write(lba, buffer, 0, blocks);
            progress.accept(length);
        }
        if (input.read() != -1 || !expectedSha.equals(CameraImportFile.hex(sourceSha.digest())))
            throw new IOException("SDの写真が確認時から変わりました。");

        target.sync();
        MessageDigest savedSha = digest();
        for (long offset = 0; offset < bytes; offset += WINDOW) {
            CameraPhotoPublisher.check(cancelled);
            int length = chunk(bytes, offset);
            target.read(startLba + offset / SECTOR, buffer, 0, (int) sectorCount(length));
            savedSha.update(buffer, 0, length);
        }
        if (!expectedSha.equals(CameraImportFile.hex(savedSha.digest())))
            throw new IOException("microSDに保存した写真が読み出し元と一致しません。");
    }

    private static void readExactly(InputStream input, byte[] buffer, int length, BooleanSupplier cancelled)
            throws IOException {
        int offset = 0;
        while (offset < length) {
            CameraPhotoPublisher.check(cancelled);
            int read = input.read(buffer, offset, length - offset);
            if (read <= 0) throw new IOException("SDの写真を最後まで読み出せませんでした。");
            offset += read;
        }
    }

    private static int chunk(long bytes, long offset) {
        return (int) Math.min(WINDOW, bytes - offset);
    }

    private static long sectorCount(long bytes) {
        return (bytes - 1) / SECTOR + 1;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
