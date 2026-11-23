package jp.nano.rawgallery.camera;

import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import jp.nano.rawgallery.PhotoStorage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Scoped emulator fixtures. Source receipts are synthetic; saved targets are owned MediaStore rows. */
public final class SdSavedRevisionChecks {
    private static final CameraPhotoPublisher.CommitCheck CHECK = () -> {};
    private static final int JPEG = 293;
    private static final int RAW = 34;

    public static String run(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        require("jp.nano.rawgallery.sd166".equals(context.getPackageName())
                && ("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE)), "isolated SD emulator");
        String db = "camera-sd-revision-" + UUID.randomUUID() + ".db";
        var resolver = context.getContentResolver();
        List<Uri> owned = new ArrayList<>();
        byte[] jpeg = fixtureJpeg(0xff884411);
        byte[] raw = fixtureJpeg(0xff117733);
        String sid = CameraDocumentSource.originalSourceId(CameraSourceKey.sd("ab12-cd34", 1000000));
        try (var ledger = new CameraImportLedger(context, db)) {
            Uri jpg = savedTarget(context, owned, jpeg, "JPG");
            Uri orf = savedTarget(context, owned, raw, "ORF");
            List<CameraDocumentSource.Entry> entries = new ArrayList<>();
            String jpegSha = sha(jpeg), rawSha = sha(raw);
            for (int i = 0; i < JPEG + RAW; i++) {
                boolean isJpeg = i < JPEG;
                var file = new CameraImportFile(sid, "DCIM/" + i + (isJpeg ? ".JPG" : ".ORF"),
                        isJpeg ? jpeg.length : raw.length, "1700000000000", 1700000000);
                String digest = isJpeg ? jpegSha : rawSha;
                ledger.adopt(file, digest, digest, file.bytes, (isJpeg ? jpg : orf).toString(),
                        isJpeg ? "JPEG" : "RAW", "fixture");
                entries.add(new CameraDocumentSource.Entry(Uri.EMPTY, file));
            }
            var importer = new CameraPhotoImporter(context, ledger, PhotoStorage.PRIMARY);
            var bootstrap = new CameraImportMetrics(context, "synthetic-saf-sd");
            importer.setMetrics(bootstrap);
            long start = SystemClock.elapsedRealtime();
            List<CameraDocumentSource.Entry> candidates = classify(entries, importer, bootstrap);
            require(candidates.size() == JPEG + RAW && bootstrap.known == 0 && ledger.sdRevision(jpegSha) == null,
                    "older receipts alone do not prove saved revisions");
            for (var item : candidates) {
                var result = importer.importPhoto(item.file, out -> { throw new AssertionError("source was reread"); },
                        "fixture", true, false, () -> false, CHECK, new CameraPhotoImporter.Attempt(), true);
                require(!result.imported && result.contentChecked, "bootstrap is a full saved-bytes match");
                bootstrap.matches++;
                bootstrap.processed++;
            }
            long bootstrapMs = SystemClock.elapsedRealtime() - start;
            require(bootstrap.matches == JPEG + RAW && bootstrap.sourceBytes == 0
                            && bootstrap.destinationBytes == (long) JPEG * jpeg.length + (long) RAW * raw.length,
                    "bootstrap reads each candidate's destination exactly once");

            var repeat = new CameraImportMetrics(context, "synthetic-saf-sd");
            importer.setMetrics(repeat);
            start = SystemClock.elapsedRealtime();
            require(classify(entries, importer, repeat).isEmpty() && repeat.known == JPEG + RAW
                            && repeat.checked == JPEG + RAW && repeat.processed == 0 && repeat.matches == 0
                            && repeat.candidates == 0 && repeat.sourceBytes == 0 && repeat.destinationBytes == 0
                            && ledger.receiptCount() == JPEG + RAW && ledger.contentCount() == 2,
                    "ordinary repeat skips both source and destination bodies and keeps history");
            long repeatMs = SystemClock.elapsedRealtime() - start;

            CameraImportFile previous = entries.get(0).file;
            var changed = new CameraImportFile(sid, previous.relativePath, previous.bytes,
                    "1700000000001", previous.captureSeconds);
            var changedEntry = new CameraDocumentSource.Entry(Uri.EMPTY, changed);
            var mixed = new ArrayList<>(entries);
            mixed.set(0, changedEntry);
            var mixedMetrics = new CameraImportMetrics(context, "synthetic-saf-sd");
            importer.setMetrics(mixedMetrics);
            require(classify(mixed, importer, mixedMetrics).equals(List.of(changedEntry))
                            && mixedMetrics.known == JPEG + RAW - 1, "changed source timestamp is the only candidate");
            var checked = importer.importPhoto(changed, out -> out.write(jpeg), "fixture", true, false,
                    () -> false, CHECK, new CameraPhotoImporter.Attempt(), true);
            require(checked.contentChecked && !checked.imported && mixedMetrics.sourceBytes == jpeg.length
                            && mixedMetrics.destinationBytes == jpeg.length,
                    "changed source tuple compares both streams");
            try {
                importer.knownSdRevision(previous, () -> false, CHECK,
                        () -> { throw new MountedCameraCard.Unavailable("fixture grant revoked"); });
                throw new AssertionError("revoked grant confirmed cached receipt");
            } catch (MountedCameraCard.Unavailable expected) { }

            var content = ledger.known(previous);
            var proof = ledger.sdRevision(jpegSha);
            require(proof != null, "verified JPEG has revision proof");
            observedFailures(context, content, proof);
            var corrupt = jpeg.clone();
            corrupt[corrupt.length - 1] ^= 1;
            // Edit the owned fixture directly so a MediaStore row can retain its same size and revision.
            boolean hiddenMetadataUnchanged = overwriteOwned(context, jpg, corrupt)
                    && proof.equals(SdSavedPhotoRevision.capture(context, content, () -> false));
            try {
                importer.importPhoto(previous, out -> out.write(jpeg), "fixture", true, true, () -> false,
                        CHECK, new CameraPhotoImporter.Attempt(), true);
                throw new AssertionError("explicit verification ignored changed saved bytes");
            } catch (IOException expected) {
                require(expected.getMessage().contains("Saved photo changed") && ledger.sdRevision(jpegSha) == null,
                        "explicit SHA detects corruption and invalidates the cached evidence");
            }

            require(ledger.sdRevision(rawSha) != null, "other content retains its revision proof");
            ledger.forgetMissing(rawSha);
            require(ledger.sdRevision(rawSha) == null, "deleting parent content cascades to its proof");
            return "PASS sd-revision receipts=327 concreteTargets=2 bootstrapMs=" + bootstrapMs
                    + " bootstrapDestinationBytes=" + bootstrap.destinationBytes + " repeatMs=" + repeatMs
                    + " repeatSourceBytes=0 repeatDestinationBytes=0 known=327 candidates=0 mixedCandidates=1"
                    + " hiddenMetadataUnchanged=" + hiddenMetadataUnchanged + " explicitMismatch=true";
        } finally {
            for (Uri uri : owned) resolver.delete(uri, null, null);
            require(context.deleteDatabase(db), "isolated ledger removed");
        }
    }

    private static List<CameraDocumentSource.Entry> classify(List<CameraDocumentSource.Entry> entries,
            CameraPhotoImporter importer, CameraImportMetrics metrics) throws IOException {
        return CameraDifferentialService.classifySd(entries, () -> false, metrics,
                entry -> importer.knownSdRevision(entry.file, () -> false, CHECK, CHECK), () -> {});
    }

    private static Uri savedTarget(Context context, List<Uri> owned, byte[] bytes, String suffix) throws IOException {
        var resolver = context.getContentResolver();
        var values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "sd-revision-" + UUID.randomUUID() + "." + suffix);
        values.put(MediaStore.MediaColumns.MIME_TYPE, suffix.equals("JPG") ? "image/jpeg" : "image/x-olympus-orf");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CameraSync/sd-revision-fixtures/");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.getContentUri(PhotoStorage.PRIMARY), values);
        require(uri != null, "owned target created");
        owned.add(uri);
        try (var stream = resolver.openOutputStream(uri)) { stream.write(bytes); }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        require(resolver.update(uri, values, null, null) == 1, "owned target published");
        return uri;
    }

    private static byte[] fixtureJpeg(int color) throws IOException {
        var image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        try (var bytes = new ByteArrayOutputStream()) {
            image.eraseColor(color);
            require(image.compress(Bitmap.CompressFormat.JPEG, 92, bytes), "generated synthetic fixture");
            return bytes.toByteArray();
        } finally {
            image.recycle();
        }
    }

    private static String sha(byte[] b) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(b);
        var hex = new StringBuilder(64);
        for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }

    private static boolean overwriteOwned(Context context, Uri uri, byte[] bytes) throws IOException {
        String path;
        var resolver = context.getContentResolver();
        try (var cursor = resolver.query(uri, new String[]{MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME}, null, null, null)) {
            require(cursor != null && cursor.moveToFirst() && context.getPackageName().equals(cursor.getString(1)),
                    "direct fixture mutation is restricted to its owned row");
            path = cursor.getString(0);
        }
        if (path != null) {
            var file = new java.io.File(path);
            long modified = file.lastModified();
            try (var stream = new RandomAccessFile(file, "rw")) {
                stream.write(bytes);
                return modified != 0 && file.setLastModified(modified);
            } catch (java.io.FileNotFoundException blocked) {
                // Some emulator providers only expose owned writes through their content resolver.
            }
        }
        try (var stream = resolver.openOutputStream(uri, "wt")) { stream.write(bytes); }
        return false;
    }

    private static void observedFailures(Context context, CameraImportLedger.Content content, SdSavedPhotoRevision proof)
            throws IOException {
        String[] columns = {"_id", "volume_name", "owner_package_name", "_size", "is_pending", "is_trashed",
                "generation_added", "generation_modified"};
        long id = android.content.ContentUris.parseId(Uri.parse(content.uri));
        Object[] values = { id, PhotoStorage.PRIMARY, context.getPackageName(), content.storedBytes,
                0, 0, proof.generationAdded(), proof.generationModified() };
        try (var cursor = new MatrixCursor(columns)) {
            cursor.addRow(values);
            require(proof.equals(SdSavedPhotoRevision.observed(cursor, content, id, PhotoStorage.PRIMARY,
                    context.getPackageName(), proof.epoch())), "complete pinned row matches recorded revision");
        }
        Object[] changes = {id + 1, "other", "another.owner", content.storedBytes + 1, 1, 1,
                proof.generationAdded() + 1, proof.generationModified() + 1};
        for (int i = 0; i < columns.length; i++) {
            try (var cursor = new MatrixCursor(columns)) {
                var changed = values.clone();
                changed[i] = changes[i];
                cursor.addRow(changed);
                require(!proof.equals(SdSavedPhotoRevision.observed(cursor, content, id, PhotoStorage.PRIMARY,
                        context.getPackageName(), proof.epoch())), "altered row must not match");
            }
        }
        try (var cursor = new MatrixCursor(columns)) {
            cursor.addRow(values);
            require(!proof.equals(SdSavedPhotoRevision.observed(cursor, content, id, PhotoStorage.PRIMARY,
                    context.getPackageName(), "new-epoch")), "epoch reset invalidates proof");
        }
        for (int i = 0; i < 3; i++) {
            try (var cursor = new MatrixCursor(columns)) {
                cursor.addRow(values);
                var extras = new Bundle();
                if (i == 0) extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
                if (i == 1) extras.putString(DocumentsContract.EXTRA_ERROR, "fixture");
                if (i == 2) cursor.addRow(values);
                cursor.setExtras(extras);
                try {
                    SdSavedPhotoRevision.observed(cursor, content, id, PhotoStorage.PRIMARY,
                            context.getPackageName(), proof.epoch());
                    throw new AssertionError("incomplete or ambiguous metadata was accepted");
                } catch (IOException expected) { }
            }
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
