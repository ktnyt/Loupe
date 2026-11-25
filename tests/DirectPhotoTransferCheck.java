package jp.nano.rawgallery.camera;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;

public final class DirectPhotoTransferCheck {
    private static final long START = 11;
    private static final int WINDOW = DirectPhotoTransfer.WINDOW;
    private static final int LENGTH = WINDOW + 13;
    private static final int BLOCKS = (LENGTH + 511) / 512;

    private static final class Storage implements DirectPhotoTransfer.Target {
        final byte[] bytes = new byte[(int) (START + BLOCKS + 2) * 512];
        boolean permitted;
        boolean synced;
        boolean badOnSecondWindow;
        boolean cancelledAfterProof;
        boolean corruptAtSync;
        int readCount;
        int writes;

        Storage(DirectReservationMarker marker) throws IOException {
            Arrays.fill(bytes, (byte) 0x6c);
            marker.fill(bytes, (int) START * 512, WINDOW, 0);
            marker.fill(bytes, (int) START * 512 + WINDOW, LENGTH - WINDOW, WINDOW);
        }

        @Override public void read(long lba, byte[] output, int offset, int blocks) {
            assertRange(lba, blocks);
            readCount++;
            System.arraycopy(bytes, (int) lba * 512, output, offset, blocks * 512);
            if (badOnSecondWindow && permitted && writes == 1) output[offset] ^= 1;
        }
        @Override public void permit(long lba, long blocks) {
            require(!permitted && lba == START && blocks == BLOCKS && readCount == 2,
                    "authorization must follow complete marker proof");
            permitted = true;
        }
        @Override public void write(long lba, byte[] input, int offset, int blocks) {
            assertRange(lba, blocks);
            require(permitted && !synced && input.length == 2 * WINDOW, "invalid write");
            System.arraycopy(input, offset, bytes, (int) lba * 512, blocks * 512);
            writes++;
        }
        @Override public void sync() {
            require(permitted && writes == 2 && !synced, "sync must follow the complete transfer");
            synced = true;
            if (corruptAtSync) bytes[(int) START * 512] ^= 1;
        }
        void assertRange(long lba, int blocks) {
            require(lba >= START && blocks > 0 && lba + blocks <= START + BLOCKS, "escaped reservation");
        }
    }

    public static void main(String[] args) throws Exception {
        byte[] original = new byte[LENGTH];
        for (int i = 0; i < LENGTH; i++) original[i] = (byte) (i * 37);
        String hash = CameraImportFile.hex(MessageDigest.getInstance("SHA-256").digest(original));
        DirectReservationMarker marker = new DirectReservationMarker();

        byte[] withOffset = new byte[2 * WINDOW];
        marker.fill(withOffset, WINDOW, 731, 1024);
        marker.requireMatch(withOffset, WINDOW, 731, 1024);
        IOException wrongOffset = null;
        try {
            marker.fill(withOffset, 0, 731, 1024);
            marker.requireMatch(withOffset, 0, 731, 1536);
        } catch (IOException failure) {
            wrongOffset = failure;
        }
        require(wrongOffset != null, "a marker from another offset must fail");

        Storage complete = new Storage(marker);
        byte[] before = complete.bytes.clone();
        copy(complete, original, marker, hash);
        int start = (int) START * 512;
        require(complete.writes == 2 && complete.synced, "two-window verified transfer");
        require(Arrays.equals(original, 0, LENGTH, complete.bytes, start, start + LENGTH), "copied bytes");
        require(Arrays.equals(before, 0, start, complete.bytes, 0, start)
                && Arrays.equals(before, start + LENGTH, before.length,
                        complete.bytes, start + LENGTH, complete.bytes.length), "existing sectors and EOF padding");

        Storage wrongMarker = new Storage(marker);
        wrongMarker.bytes[start + WINDOW] ^= 1;
        rejects(() -> copy(wrongMarker, original, marker, hash));
        require(!wrongMarker.permitted && wrongMarker.writes == 0, "complete proof before permission");

        Storage changedDuringTransfer = new Storage(marker);
        changedDuringTransfer.badOnSecondWindow = true;
        rejects(() -> copy(changedDuringTransfer, original, marker, hash));
        require(changedDuringTransfer.writes == 1 && !changedDuringTransfer.synced, "re-prove target before each write");

        Storage cancelled = new Storage(marker);
        cancelled.cancelledAfterProof = true;
        rejects(() -> copy(cancelled, original, marker, hash));
        require(cancelled.writes == 0 && !cancelled.synced, "stop before WRITE");

        Storage changedSource = new Storage(marker);
        rejects(() -> copy(changedSource, original, marker, "wrong-digest"));
        require(!changedSource.synced, "don't accept a changed source");

        Storage changedReadback = new Storage(marker);
        changedReadback.corruptAtSync = true;
        rejects(() -> copy(changedReadback, original, marker, hash));
        require(changedReadback.synced, "verify after sync");
        System.out.println("Passed: direct file transfer, marker checks, EOF protection, cancellation and readback");
    }

    private static void copy(Storage storage, byte[] original, DirectReservationMarker marker, String hash)
            throws IOException {
        DirectPhotoTransfer.copy(storage, new ByteArrayInputStream(original), START, BLOCKS, LENGTH,
                marker, hash, () -> storage.cancelledAfterProof && storage.readCount >= 3, count -> {});
    }

    private interface Task { void run() throws IOException; }

    private static void rejects(Task task) throws IOException {
        try {
            task.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Operation should have failed");
    }

    private static void require(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
}
