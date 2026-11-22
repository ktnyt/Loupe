package jp.nano.rawgallery.camera;

import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Synthetic original reads and isolated receipts only; no source or MediaStore writes. */
public final class CameraDocumentSourceChecks {
    private static final byte[] ORIGINAL = {12, 34, 56, 78};
    private static final Uri DOCUMENT = Uri.parse("content://synthetic.documents/document/test");
    private static final Uri MEDIA = Uri.parse("content://media/external/images/media/0");
    private static final String SOURCE = "ab12-cd34";
    private static final Uri PINNED = MediaStore.Files.getContentUri(SOURCE, 0);
    public static String run(Instrumentation instrumentation) throws Exception {
        Fake denied = new Fake(); denied.permitted = false;
        expect(denied, CameraDocumentSource.OriginalAccessException.Reason.PERMISSION);
        require(denied.maps == 0 && denied.opens == 0, "Denied permission cannot map or read");
        Fake unmapped = new Fake(); unmapped.mapped = null;
        expect(unmapped, CameraDocumentSource.OriginalAccessException.Reason.MAPPING);
        require(unmapped.maps == 1 && unmapped.opens == 0, "Null mapping cannot open document fallback");
        Fake wrongMapping = new Fake(); wrongMapping.mapped = DOCUMENT;
        expect(wrongMapping, CameraDocumentSource.OriginalAccessException.Reason.MAPPING);
        require(wrongMapping.opens == 0, "Non-MediaStore mapping cannot be read");
        Fake failed = new Fake(); failed.failOpen = true;
        expect(failed, CameraDocumentSource.OriginalAccessException.Reason.UNAVAILABLE);
        require(failed.opens == 1 && failed.required, "Original failure has no default retry");
        Fake missing = new Fake(); missing.nullOpen = true;
        expect(missing, CameraDocumentSource.OriginalAccessException.Reason.UNAVAILABLE);
        Fake successful = new Fake();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CameraDocumentSource.copy(successful, entry(), output, () -> false);
        require(successful.required && successful.closes == 1 && Arrays.equals(output.toByteArray(), ORIGINAL)
                && Arrays.equals(successful.bytes, ORIGINAL), "Original stream remains immutable and closes once");
        AtomicBoolean stop = new AtomicBoolean();
        Fake cancelled = new Fake(); cancelled.onRead = () -> stop.set(true);
        ByteArrayOutputStream cancelledOutput = new ByteArrayOutputStream();
        try { CameraDocumentSource.copy(cancelled, entry(), cancelledOutput, stop::get); throw new AssertionError("Cancellation ignored"); }
        catch (InterruptedIOException expected) { }
        require(cancelled.closes == 1 && cancelledOutput.size() == 0, "Cancellation after read prevents output and closes stream");
        Fake revoked = new Fake(); revoked.onRead = () -> revoked.permitted = false;
        expect(revoked, CameraDocumentSource.OriginalAccessException.Reason.PERMISSION);
        require(revoked.closes == 1, "Revocation after read closes stream");
        Fake shortSource = new Fake(); shortSource.bytes = new byte[]{1, 2};
        expect(shortSource, CameraDocumentSource.OriginalAccessException.Reason.LENGTH);
        Fake excess = new Fake(); excess.bytes = new byte[100];
        expect(excess, CameraDocumentSource.OriginalAccessException.Reason.LENGTH);
        require(excess.readBytes == ORIGINAL.length + 1, "Excess source bounded at expected length plus one");
        strictVolumeReads();
        kindSelection();
        legacyReceiptRequiresRead(instrumentation.getTargetContext());
        return "PASS sd-original permission=true mapping=true pinnedVolume=true requiredOriginal=true noFallback=true immutable=true cancel=true revoke=true bounds=true legacyReceiptRevalidated=true";
    }
    private static void strictVolumeReads() throws IOException {
        Fake unpinned = new Fake();
        expect(unpinned, CameraDocumentSource.OriginalAccessException.Reason.VOLUME, SOURCE);
        require(unpinned.maps == 1 && unpinned.pins == 1 && unpinned.opens == 0,
                "A missing or ambiguous source cannot open an aggregate URI");
        Fake wrongVolume = new Fake();
        wrongVolume.pinned = MediaStore.Files.getContentUri("ef56-7890", 0);
        expect(wrongVolume, CameraDocumentSource.OriginalAccessException.Reason.VOLUME, SOURCE);
        require(wrongVolume.opens == 0, "Wrong volume cannot open originals");
        Fake aggregate = new Fake();
        aggregate.pinned = MEDIA;
        expect(aggregate, CameraDocumentSource.OriginalAccessException.Reason.VOLUME, SOURCE);
        require(aggregate.opens == 0, "A resolver cannot return an unpinned aggregate URI");
        Fake source = new Fake();
        source.pinned = PINNED;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CameraDocumentSource.copy(source, entry(), output, () -> false, SOURCE);
        require(source.opens == 1 && source.pins == 1 && source.required && Arrays.equals(output.toByteArray(), ORIGINAL),
                "Resolved source volume opens exactly one original and retains bytes");
        Fake stopped = new Fake();
        stopped.pinned = PINNED;
        ByteArrayOutputStream stoppedOutput = new ByteArrayOutputStream();
        try {
            CameraDocumentSource.copy(stopped, entry(), stoppedOutput, () -> stopped.pins > 0, SOURCE);
            throw new AssertionError("Cancellation before the pinned open was ignored");
        } catch (InterruptedIOException expected) {}
        require(stopped.opens == 0 && stoppedOutput.size() == 0, "Cancelled source never opens or writes a photo");
    }

    private static void kindSelection() throws IOException {
        CameraDocumentSource.Entry jpeg = entry();
        CameraDocumentSource.Entry raw = new CameraDocumentSource.Entry(DOCUMENT,
                new CameraImportFile("synthetic-kind", "DCIM/TEST.ORF", ORIGINAL.length, "stamp", 1));
        require(CameraDifferentialService.selectedPhotos(List.of(jpeg, raw),
                CameraDifferentialService.sdKinds("JPEG")).equals(List.of(jpeg)), "JPEG-only request excludes RAW");
        require(CameraDifferentialService.selectedPhotos(List.of(jpeg, raw),
                CameraDifferentialService.sdKinds("RAW")).equals(List.of(raw)), "RAW-only request excludes JPEG");
        require(CameraDifferentialService.selectedPhotos(List.of(jpeg, raw),
                CameraDifferentialService.sdKinds(null)).equals(List.of(jpeg, raw)), "Normal SAF defaults to both image kinds");
        try {
            CameraDifferentialService.sdKinds("JPEG,VIDEO");
            throw new AssertionError("Unsupported video request proceeded");
        } catch (MountedCameraCard.Unavailable expected) {
            require(expected.getMessage().contains("動画"), "Unsupported video is reported explicitly");
        }
    }

    private static void legacyReceiptRequiresRead(Context context) throws Exception {
        String database = "camera-original-source-check-" + UUID.randomUUID() + ".db";
        CameraImportLedger ledger = new CameraImportLedger(context, database);
        CameraImportFile old = new CameraImportFile("sd:synthetic-volume", "DCIM/100TEST/TEST.JPG", ORIGINAL.length, "mtime", 1);
        CameraImportFile original = new CameraImportFile(CameraDocumentSource.originalSourceId(old.sourceId), old.relativePath, old.bytes, old.sourceStamp, old.captureSeconds);
        try {
            String sha = hex(MessageDigest.getInstance("SHA-256").digest(ORIGINAL));
            CameraImportLedger.Content content = ledger.adopt(old, sha, sha, ORIGINAL.length, MEDIA.toString(), "JPEG", "synthetic-group");
            require(ledger.known(old) != null && ledger.known(original) == null, "Legacy receipt cannot authorize original-byte shortcut");
            CameraPhotoImporter importer = new CameraPhotoImporter(context, ledger);
            Fake wrongSource = new Fake();
            wrongSource.pinned = MediaStore.Files.getContentUri("ef56-7890", 0);
            try {
                importer.importPhoto(original, output -> CameraDocumentSource.copy(
                        wrongSource, entry(), output, () -> false, SOURCE), null, true, false, () -> false);
                throw new AssertionError("Source on the wrong volume was imported");
            } catch (CameraDocumentSource.OriginalAccessException expected) {
                require(expected.reason == CameraDocumentSource.OriginalAccessException.Reason.VOLUME, "Wrong source volume");
            }
            require(wrongSource.opens == 0 && ledger.pending().isEmpty() && ledger.known(original) == null,
                    "A wrong source volume cannot allocate a destination or bind a receipt");

            Fake access = new Fake();
            try {
                importer.importPhoto(original, output -> {
                    CameraDocumentSource.copy(access, entry(), output, () -> false);
                    throw new InterruptedIOException("Synthetic stop after complete original read");
                }, null, true, false, () -> false);
                throw new AssertionError("Synthetic source boundary did not stop import");
            } catch (InterruptedIOException expected) { }
            require(access.readBytes == ORIGINAL.length && access.opens == 1 && access.required,
                    "Legacy receipt forces the complete required-original read before any binding");
            require(ledger.contentCount() == 1 && ledger.receiptCount() == 1 && ledger.pending().isEmpty()
                    && ledger.known(original) == null, "Failed revalidation preserves legacy content and receipt without new binding");
            // The same verified digest may bind the versioned receipt without deleting the old one.
            ledger.bind(original, content);
            require(ledger.contentCount() == 1 && ledger.receiptCount() == 2 && ledger.known(old) != null
                    && ledger.known(original) != null, "Verified original receipt retains old content and receipt");
        } finally { ledger.close(); require(context.deleteDatabase(database), "Isolated receipt database removed"); }
    }
    private static CameraDocumentSource.Entry entry() {
        return new CameraDocumentSource.Entry(DOCUMENT, new CameraImportFile("original-byte-v1:saf:synthetic", "DCIM/TEST.JPG", ORIGINAL.length, "stamp", 1));
    }
    private static void expect(Fake access, CameraDocumentSource.OriginalAccessException.Reason reason) throws IOException {
        expect(access, reason, null);
    }

    private static void expect(Fake access, CameraDocumentSource.OriginalAccessException.Reason reason,
            String sourceVolume) throws IOException {
        try {
            CameraDocumentSource.copy(access, entry(), new ByteArrayOutputStream(), () -> false, sourceVolume);
            throw new AssertionError("Closed original error expected");
        }
        catch (CameraDocumentSource.OriginalAccessException expected) {
            require(expected.reason == reason && !expected.getMessage().contains("content:")
                    && !expected.getMessage().contains("synthetic-provider-detail"), "Closed error category without provider details");
        }
    }
    private static final class Fake implements CameraDocumentSource.OriginalAccess {
        boolean permitted = true, required, failOpen, nullOpen;
        int maps, pins, opens, closes, readBytes;
        Uri mapped = MEDIA, pinned;
        byte[] bytes = ORIGINAL.clone();
        Runnable onRead = () -> { };
        public boolean permitted() { return permitted; }
        public Uri mediaUri(Uri document) { require(DOCUMENT.equals(document), "Exact selected document mapping"); maps++; return mapped; }
        public Uri onVolume(Uri media, CameraImportFile file, String expectedVolume) {
            require(mapped.equals(media) && entry().file.relativePath.equals(file.relativePath) && SOURCE.equals(expectedVolume),
                    "Pinned check receives the exact mapping, original metadata and selected volume");
            pins++;
            return pinned;
        }
        public InputStream openOriginal(Uri original) throws IOException {
            opens++; required = MediaStore.getRequireOriginal(original);
            Uri expected = pins == 0 ? mapped : pinned;
            require(required && MediaStore.setRequireOriginal(expected).equals(original), "Exact mapped media with requireOriginal");
            if (failOpen) throw new IOException("synthetic-provider-detail");
            if (nullOpen) return null;
            return new ByteArrayInputStream(bytes) {
                @Override public synchronized int read(byte[] target, int offset, int count) {
                    int result = super.read(target, offset, count); if (result > 0) readBytes += result;
                    onRead.run(); return result;
                }
                @Override public void close() { closes++; }
            };
        }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(); for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255)); return result.toString();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
