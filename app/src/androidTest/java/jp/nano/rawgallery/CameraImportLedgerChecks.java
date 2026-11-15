package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import jp.nano.rawgallery.camera.CameraImportCopy;
import jp.nano.rawgallery.camera.CameraImportFile;
import jp.nano.rawgallery.camera.CameraImportLedger;
import jp.nano.rawgallery.camera.CameraPhotoImporter;
import jp.nano.rawgallery.camera.CameraPhotoPublisher;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Synthetic-only checks for cross-route identity and publication recovery. */
final class CameraImportLedgerChecks {
    private static final String[] MEDIA_FIELDS = {
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH
    };

    private CameraImportLedgerChecks() { }

    static String run(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        String runId = UUID.randomUUID().toString();
        Set<String> ownedFolders = new HashSet<>();
        Set<String> databaseNames = new HashSet<>();
        String mainDb = "camera-ledger-check-" + runId + ".db";
        databaseNames.add(mainDb);
        try {
            crossRouteAndAssociation(context, runId, mainDb, ownedFolders);
            for (CameraPhotoPublisher.Boundary boundary : CameraPhotoPublisher.Boundary.values()) {
                recoverBoundary(context, runId, boundary, ownedFolders, databaseNames);
            }
            return "PASS camera ledger: cross-route receipts, strict content identity, JPEG/RAW association, " +
                    "and recovery at every publication boundary";
        } finally {
            Exception cleanupFailure = null;
            try {
                cleanOwnedMedia(context, ownedFolders);
            } catch (Exception e) {
                cleanupFailure = e;
            }
            for (String databaseName : databaseNames) {
                if (!context.deleteDatabase(databaseName)) {
                    IOException e = new IOException("Could not delete isolated test database");
                    if (cleanupFailure == null) cleanupFailure = e;
                    else cleanupFailure.addSuppressed(e);
                }
            }
            if (cleanupFailure != null) throw cleanupFailure;
        }
    }

    private static void crossRouteAndAssociation(
            Context context, String runId, String databaseName, Set<String> ownedFolders) throws Exception {
        CameraImportLedger ledger = new CameraImportLedger(context, databaseName);
        try {
            CameraPhotoImporter importer = new CameraPhotoImporter(context, ledger);
            String folder = "LoupeSynthetic-" + runId + "-identity";
            ownedFolders.add(CameraPhotoPublisher.folder(folder, false));

            byte[] jpeg = jpegWithComment(0x17L, "BASE0001");
            byte[] sameLengthDifferentContent = jpegWithComment(0x17L, "ALTER001");
            require(jpeg.length == sameLengthDifferentContent.length, "synthetic JPEG fixture lengths match");
            require(!sha256(jpeg).equals(sha256(sameLengthDifferentContent)), "synthetic JPEG fixture contents differ");

            CameraImportFile wifi = source("wifi:synthetic-camera", "DCIM/100CAM/PAIR.JPG", jpeg,
                    "2026-11-15T10:11:12", 1794737472L);
            CameraPhotoImporter.Result first = importPhoto(importer, wifi, jpeg, folder, false, true);
            require(first.imported && first.contentChecked, "first synthetic Wi-Fi import publishes verified content");
            CameraImportLedger.Content original = ledger.find(sha256(jpeg), jpeg.length);
            require(original != null && original.uri.equals(first.uri.toString()), "first content is in the isolated ledger");

            CameraImportFile sd = source("sd:synthetic-volume", "DCIM/100CAM/PAIR.JPG", jpeg,
                    "2026-11-15T10:11:12", 1794737472L);
            CameraPhotoImporter.Result second = importPhoto(importer, sd, jpeg, folder, false, true);
            require(!second.imported && second.contentChecked && first.uri.equals(second.uri),
                    "same original from SD binds to the Wi-Fi publication");
            require(ledger.contentCount() == 1 && ledger.receiptCount() == 2,
                    "one content has two cross-route receipts");

            CameraImportFile renamed = source("wifi:synthetic-camera", "DCIM/100CAM/RENAMED.JPG", jpeg,
                    "2026-11-15T10:11:12", 1794737472L);
            CameraPhotoImporter.Result third = importPhoto(importer, renamed, jpeg, folder, false, true);
            require(!third.imported && third.uri.equals(first.uri), "different filename binds by verified content");
            require(ledger.contentCount() == 1 && ledger.receiptCount() == 3,
                    "renamed identical content adds a receipt without another content row");

            final int[] reads = {0};
            CameraPhotoImporter.Result fast = importer.importPhoto(wifi, out -> reads[0]++, folder,
                    true, false, () -> false);
            require(reads[0] == 0 && !fast.contentChecked && !fast.imported && fast.uri.equals(first.uri),
                    "verified known source uses the explicit stable-source fast path without reading bytes");

            CameraPhotoImporter.Result strictChanged = importPhoto(importer, wifi,
                    sameLengthDifferentContent, folder, false, true);
            require(strictChanged.imported && strictChanged.contentChecked && !strictChanged.uri.equals(first.uri),
                    "strict verification does not conflate changed bytes at the same path and metadata");
            require(ledger.contentCount() == 2,
                    "same source receipt tuple with changed contents produces a separate content row");

            // A cross-route bind carries the existing capture group to the route where the RAW arrives.
            byte[] pairJpeg = jpegWithComment(0x17L, "PAIR0001");
            CameraImportFile pairWifi = source("wifi:pair-camera", "DCIM/100CAM/GROUP.JPG", pairJpeg,
                    "pair-date", 1794737473L);
            CameraPhotoImporter.Result pair = importPhoto(importer, pairWifi, pairJpeg, folder, false, true);
            CameraImportFile pairSd = source("sd:pair-volume", "DCIM/100CAM/GROUP.JPG", pairJpeg,
                    "pair-date", 1794737473L);
            importPhoto(importer, pairSd, pairJpeg, folder, false, true);
            CameraImportLedger.Content pairContent = ledger.find(sha256(pairJpeg), pairJpeg.length);
            require(pairContent != null && pairContent.uri.equals(pair.uri.toString()), "pair JPEG content is ledger-backed");
            CameraImportFile rawOnSd = new CameraImportFile("sd:pair-volume", "DCIM/100CAM/GROUP.RW2",
                    64, "pair-date", 1794737473L);
            String rawGroup = ledger.captureGroup(rawOnSd, syntheticHash("synthetic-raw-payload"), "RAW");
            require(pairContent.captureGroup.equals(rawGroup),
                    "RAW capture group follows the verified cross-route JPEG receipt");
        } finally {
            ledger.close();
        }
    }

    private static void recoverBoundary(
            Context context,
            String runId,
            CameraPhotoPublisher.Boundary boundary,
            Set<String> ownedFolders,
            Set<String> databaseNames) throws Exception {
        String suffix = boundary.name().toLowerCase(java.util.Locale.ROOT);
        String databaseName = "camera-ledger-check-" + runId + "-" + suffix + ".db";
        databaseNames.add(databaseName);
        String folderName = "LoupeSynthetic-" + runId + "-" + suffix;
        String relativeFolder = CameraPhotoPublisher.folder(folderName, false);
        ownedFolders.add(relativeFolder);
        byte[] bytes = jpegWithComment(0x731L + boundary.ordinal(), "CRASH000");
        CameraImportFile source = source("wifi:crash-fixture", "DCIM/100CAM/CRASH.JPG", bytes,
                "crash-" + suffix, 1794738000L + boundary.ordinal());
        CameraImportLedger ledger = new CameraImportLedger(context, databaseName);
        try {
            File stagedDir = context.getCacheDir();
            try (CameraImportCopy.Prepared prepared = CameraImportCopy.prepare(stagedDir, source,
                    out -> out.write(bytes), () -> false)) {
                boolean crashed = false;
                CameraPhotoPublisher publisher = new CameraPhotoPublisher(context, ledger, reached -> {
                    if (reached == boundary) throw new SimulatedProcessLoss();
                });
                try {
                    publisher.publish(source, prepared.file, prepared.sourceSha256, "JPEG", relativeFolder,
                            "synthetic-group-" + suffix, () -> false);
                } catch (SimulatedProcessLoss expected) {
                    crashed = true;
                }
                require(crashed, "fault injection reached " + boundary);
            }
        } finally {
            ledger.close();
        }

        // Reopen the unique SQLite file to model process recreation, then recover journal + MediaStore.
        CameraImportLedger reopened = new CameraImportLedger(context, databaseName);
        try {
            CameraPhotoImporter importer = new CameraPhotoImporter(context, reopened);
            importer.recover(() -> false);
            require(reopened.pending().isEmpty(), "recovery closes pending journal at " + boundary);
            long expectedBeforeRetry = isAtOrAfterVerified(boundary) ? 1 : 0;
            require(reopened.contentCount() == expectedBeforeRetry,
                    "recovery commits only verified/published states at " + boundary);
            require(reopened.receiptCount() == expectedBeforeRetry,
                    "recovery saves receipt only at/after verified state " + boundary);
            require(countOwnedVisible(context, relativeFolder) == expectedBeforeRetry,
                    "recovery exposes the expected public row at " + boundary);

            CameraPhotoImporter.Result retry = importPhoto(importer, source, bytes, folderName, false, true);
            require(retry.contentChecked, "retry verifies source contents at " + boundary);
            require(reopened.pending().isEmpty(), "retry leaves no pending journal at " + boundary);
            require(reopened.contentCount() == 1 && reopened.receiptCount() == 1,
                    "retry converges to one content and receipt at " + boundary);
            require(countOwnedVisible(context, relativeFolder) == 1,
                    "retry does not duplicate publication at " + boundary);
        } finally {
            reopened.close();
        }
    }

    private static boolean isAtOrAfterVerified(CameraPhotoPublisher.Boundary boundary) {
        return boundary == CameraPhotoPublisher.Boundary.FILE_VERIFIED ||
                boundary == CameraPhotoPublisher.Boundary.PUBLISHED ||
                boundary == CameraPhotoPublisher.Boundary.RECEIPT_SAVED;
    }

    private static CameraPhotoImporter.Result importPhoto(
            CameraPhotoImporter importer,
            CameraImportFile source,
            byte[] bytes,
            String folder,
            boolean stableSource,
            boolean verifyAll) throws IOException {
        return importer.importPhoto(source, out -> out.write(bytes), folder, stableSource, verifyAll, () -> false);
    }

    private static CameraImportFile source(String sourceId, String path, byte[] bytes, String stamp, long captureSeconds) {
        return new CameraImportFile(sourceId, path, bytes.length, stamp, captureSeconds);
    }

    private static byte[] jpegWithComment(long seed, String comment) throws IOException {
        if (comment.getBytes(StandardCharsets.US_ASCII).length != 8) throw new IllegalArgumentException("comment must be 8 bytes");
        Random random = new Random(seed);
        int width = 192;
        int height = 128;
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xff000000 | random.nextInt(1 << 24);
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            ByteArrayOutputStream image = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 91, image)) throw new IOException("JPEG fixture encode failed");
            byte[] jpeg = image.toByteArray();
            if (jpeg.length < 4 || (jpeg[jpeg.length - 2] & 0xff) != 0xff || (jpeg[jpeg.length - 1] & 0xff) != 0xd9) {
                throw new IOException("JPEG fixture has no end marker");
            }
            ByteArrayOutputStream withComment = new ByteArrayOutputStream(jpeg.length + 12);
            withComment.write(jpeg, 0, jpeg.length - 2);
            withComment.write(new byte[] {(byte) 0xff, (byte) 0xfe, 0, 10});
            withComment.write(comment.getBytes(StandardCharsets.US_ASCII));
            withComment.write(jpeg, jpeg.length - 2, 2);
            return withComment.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte value : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    private static String syntheticHash(String value) throws Exception {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static long countOwnedVisible(Context context, String relativeFolder) throws IOException {
        Bundle args = new Bundle();
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=? AND " +
                        MediaStore.MediaColumns.IS_PENDING + "=0");
        args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[] {relativeFolder, context.getPackageName()});
        try (Cursor cursor = context.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[] {MediaStore.MediaColumns._ID}, args, null)) {
            if (cursor == null) throw new IOException("Could not query synthetic publication folder");
            return cursor.getCount();
        }
    }

    private static void cleanOwnedMedia(Context context, Set<String> folders) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        for (String folder : folders) {
            Bundle args = new Bundle();
            args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
            args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=?");
            args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    new String[] {folder, context.getPackageName()});
            List<Uri> candidates = new ArrayList<>();
            try (Cursor cursor = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[] {MediaStore.MediaColumns._ID}, args, null)) {
                if (cursor == null) throw new IOException("Could not find owned synthetic media for cleanup");
                while (cursor.moveToNext()) {
                    candidates.add(Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            Long.toString(cursor.getLong(0))));
                }
            }
            for (Uri uri : candidates) deleteAfterOwnershipCheck(context, uri, folder);
        }
    }

    private static void deleteAfterOwnershipCheck(Context context, Uri uri, String expectedFolder) throws IOException {
        if (!"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority()) ||
                !uri.getPath().matches("/(?:external|external_primary)/images/media/[0-9]+")) {
            throw new IOException("Refusing to delete unexpected synthetic media URI");
        }
        Bundle args = new Bundle();
        args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        try (Cursor cursor = context.getContentResolver().query(uri, MEDIA_FIELDS, args, null)) {
            if (cursor == null || cursor.getCount() != 1 || !cursor.moveToFirst()) {
                throw new IOException("Could not verify synthetic media URI before deletion");
            }
            String owner = cursor.getString(1);
            String folder = cursor.getString(2);
            if (!context.getPackageName().equals(owner) || !expectedFolder.equals(folder)) {
                throw new IOException("Synthetic media ownership/path mismatch; refusing deletion");
            }
        }
        if (context.getContentResolver().delete(uri, null, null) != 1) {
            throw new IOException("Could not delete verified synthetic media row");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class SimulatedProcessLoss extends Error {
        private static final long serialVersionUID = 1L;
    }
}
