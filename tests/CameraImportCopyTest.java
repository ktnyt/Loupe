package jp.nano.rawgallery.camera;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CameraImportCopyTest {
    public static void main(String[] args) throws Exception {
        File cache = Files.createTempDirectory("camera-import-copy-test-").toFile();
        try {
            metadataValidation();
            sameMetadataDifferentContents(cache);
            shortAndOversizeAreRejected(cache);
            receivedVersusStagedMismatchIsRejected(cache);
            cancellationAndFailuresCleanUp(cache);
            if (cache.listFiles() == null || cache.listFiles().length != 0) {
                throw new AssertionError("staging cache was not empty after tests");
            }
            System.out.println("PASS camera import copy: metadata, source key, full content hash, bounded staging, cancellation and cleanup");
        } finally {
            File[] remaining = cache.listFiles();
            if (remaining != null) for (File file : remaining) file.delete();
            if (!cache.delete()) throw new AssertionError("test cache cleanup failed");
        }
    }

    private static void metadataValidation() {
        CameraImportFile file = new CameraImportFile("wifi:ep7", "DCIM/旅行/雪.JPG", 32,
                "raw-date-token", 1700000000L);
        check(file.name.equals("雪.JPG"), "basename is derived from relative path");
        check(file.relativePath.equals("DCIM/旅行/雪.JPG"), "Unicode path retained");
        check(file.captureSeconds == 1700000000L, "capture seconds retained");
        expectIllegal(() -> new CameraImportFile("source", "/DCIM/A.JPG", 1, "stamp", 0));
        expectIllegal(() -> new CameraImportFile("source", "DCIM//A.JPG", 1, "stamp", 0));
        expectIllegal(() -> new CameraImportFile("source", "DCIM/../A.JPG", 1, "stamp", 0));
        expectIllegal(() -> new CameraImportFile("source", "DCIM\\A.JPG", 1, "stamp", 0));
        expectIllegal(() -> new CameraImportFile("source", "DCIM/A\u0000.JPG", 1, "stamp", 0));
        expectIllegal(() -> new CameraImportFile("source", "DCIM/A.JPG", 0, "stamp", 0));
        expectIllegal(() -> new CameraImportFile("source", "DCIM/A.JPG", CameraImportFile.MAX_BYTES + 1, "stamp", 0));
        expectIllegal(() -> new CameraImportFile("source", "DCIM/A.JPG", 1, "stamp", -1));
        check(!source("ab", 32, "c").sourceKey().equals(source("a", 32, "bc").sourceKey()),
                "source tuple is length framed");
    }

    private static void sameMetadataDifferentContents(File cache) throws Exception {
        byte[] first = new byte[32768];
        byte[] second = new byte[32768];
        Arrays.fill(first, (byte) 0x31);
        Arrays.fill(second, (byte) 0x31);
        second[16384] = 0x32;
        byte[] originalFirst = first.clone();
        byte[] originalSecond = second.clone();
        CameraImportFile receipt = source("wifi:ep7", first.length, "2026-01-02T03:04:05");
        check(receipt.sourceKey().equals(source("wifi:ep7", second.length, "2026-01-02T03:04:05").sourceKey()),
                "same receipt metadata has same source key regardless of content");

        String firstSha;
        String secondSha;
        try (CameraImportCopy.Prepared prepared = CameraImportCopy.prepare(cache, receipt, out -> out.write(first), () -> false)) {
            check(prepared.bytes == first.length, "prepared byte count");
            firstSha = prepared.sourceSha256;
            check(prepared.sourceSha256.equals(CameraImportCopy.sha256(prepared.file, () -> false)),
                    "source digest came from full saved readback");
            byte[] transformed = first.clone(); transformed[10] ^= 0x55;
            Files.write(prepared.file.toPath(), transformed);
            check(!prepared.sourceSha256.equals(CameraImportCopy.sha256(prepared.file, () -> false)),
                    "post-transform digest can be measured separately");
        }
        try (CameraImportCopy.Prepared prepared = CameraImportCopy.prepare(cache, receipt, out -> out.write(second), () -> false)) {
            secondSha = prepared.sourceSha256;
            check(!firstSha.equals(secondSha), "same metadata with different middle bytes has different source hashes");
        }
        check(Arrays.equals(first, originalFirst) && Arrays.equals(second, originalSecond),
                "copy preparation leaves caller-owned originals unchanged");
        check(cache.listFiles().length == 0, "closing prepared copy deletes owned staging file");
    }

    private static void shortAndOversizeAreRejected(File cache) throws Exception {
        CameraImportFile expectedEight = source("sd:volume", 8, "mtime");
        expectIOException(() -> CameraImportCopy.prepare(cache, expectedEight, out -> out.write(new byte[7]), () -> false));
        expectIOException(() -> CameraImportCopy.prepare(cache, expectedEight, out -> out.write(new byte[9]), () -> false));
        check(cache.listFiles().length == 0, "short and oversize attempts leave no staging file");
    }

    private static void receivedVersusStagedMismatchIsRejected(File cache) throws Exception {
        byte[] received = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 };
        CameraImportFile expected = source("sd:volume", received.length, "mtime");
        expectIOException(() -> CameraImportCopy.prepare(cache, expected, out -> {
            out.write(received);
            File[] staged = cache.listFiles();
            if (staged == null || staged.length != 1) throw new IOException("staging file not found");
            byte[] altered = received.clone();
            altered[4] ^= 0x40;
            Files.write(staged[0].toPath(), altered);
        }, () -> false));
        check(cache.listFiles().length == 0,
                "same-size staged mutation is detected and its owned temp is removed");
    }

    private static void cancellationAndFailuresCleanUp(File cache) throws Exception {
        CameraImportFile eight = source("sd:volume", 8, "mtime");
        AtomicBoolean cancelled = new AtomicBoolean();
        expectIOException(() -> CameraImportCopy.prepare(cache, eight, out -> {
            out.write(new byte[4]);
            cancelled.set(true);
            out.write(new byte[4]);
        }, cancelled::get));
        check(cache.listFiles().length == 0, "cancelled copy is removed");

        expectIOException(() -> CameraImportCopy.prepare(cache, eight, out -> {
            out.write(new byte[3]);
            throw new IOException("synthetic source failure");
        }, () -> false));
        check(cache.listFiles().length == 0, "source exception removes partial copy");

        try (CameraImportCopy.Prepared prepared = CameraImportCopy.prepare(cache, eight,
                out -> out.write(new byte[8]), () -> false)) {
            expectIOException(() -> CameraImportCopy.sha256(prepared.file, () -> true));
        }
        check(cache.listFiles().length == 0, "hash cancellation and close leave no staging file");
    }

    private static CameraImportFile source(String id, long bytes, String stamp) {
        return new CameraImportFile(id, "DCIM/100CAM/SAMPLE.JPG", bytes, stamp, 1700000000L);
    }

    private static void expectIOException(IoAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("expected IOException");
        } catch (IOException expected) { }
    }

    private static void expectIllegal(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface private interface IoAction { void run() throws Exception; }
}
