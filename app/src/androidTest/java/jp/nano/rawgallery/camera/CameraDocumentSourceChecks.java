package jp.nano.rawgallery.camera;

import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
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
    private static final Uri SOURCE_TREE = DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents", "AB12-CD34:DCIM");
    private static final String SOURCE_DOCUMENT_ID = "AB12-CD34:DCIM/100TEST/TEST.JPG";
    private static final Uri SOURCE_DOCUMENT = DocumentsContract.buildDocumentUriUsingTree(SOURCE_TREE, SOURCE_DOCUMENT_ID);
    /** Runs the source list against a local provider, never the device card. */
    public static void assertSdSwapListing(Context context) throws Exception {
        var provider = new android.content.ContentProvider() {
            volatile int mode;
            java.util.concurrent.CountDownLatch started;
            java.util.concurrent.CountDownLatch release;
            java.util.concurrent.CountDownLatch returned;
            java.util.concurrent.CountDownLatch signalled;
            volatile boolean cancellable;

            void stallNext() {
                started = new java.util.concurrent.CountDownLatch(1);
                release = new java.util.concurrent.CountDownLatch(1);
                returned = new java.util.concurrent.CountDownLatch(1);
                signalled = new java.util.concurrent.CountDownLatch(1);
                cancellable = false;
                mode = 12;
            }

            @Override public Cursor query(Uri uri, String[] columns, String selection, String[] args, String sort,
                    android.os.CancellationSignal signal) {
                if (mode != 12) return query(uri, columns, selection, args, sort);
                if (signal != null) {
                    cancellable = true;
                    signal.setOnCancelListener(signalled::countDown);
                }
                var held = release;
                var finished = returned;
                started.countDown();
                long until = android.os.SystemClock.elapsedRealtime() + 4000;
                try {
                    while (held.getCount() != 0 && android.os.SystemClock.elapsedRealtime() < until) {
                        try { held.await(100, java.util.concurrent.TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { /* Fixture deliberately ignores interruption. */ }
                    }
                    return query(uri, columns, selection, args, sort);
                } finally {
                    finished.countDown();
                }
            }

            @Override public boolean onCreate() { return true; }
            @Override public Cursor query(Uri uri, String[] columns, String selection, String[] args, String sort) {
                if (mode == 5) return null;
                if (mode == 6) throw new SecurityException("fixture");
                var cursor = new MatrixCursor(columns);
                if (mode == 1 || mode == 3 || mode == 4 || mode == 7)
                    cursor.addRow(new Object[]{"AB12-CD34:DCIM/TEST.JPG", "TEST.JPG", "image/jpeg", 8L, 1024L});
                if (mode == 8)
                    cursor.addRow(new Object[]{"AB12-CD34:DCIM/TEST.MP4", "TEST.MP4", "video/mp4", 8L, 1024L});
                if (mode == 9) for (int i = 0; i <= 20000; i++)
                    cursor.addRow(new Object[]{"AB12-CD34:DCIM/unknown", "unknown", "text/plain", 8L, 1024L});
                if (mode == 10 || mode == 11) {
                    if (DocumentsContract.getTreeDocumentId(uri).equals(uri.getPathSegments().get(3))) {
                        cursor.addRow(new Object[]{"AB12-CD34:DCIM/100OLYMP", "100OLYMP",
                                mode == 10 ? null : "", 0L, 1024L});
                    } else {
                        cursor.addRow(new Object[]{"AB12-CD34:DCIM/100OLYMP/TEST.JPG",
                                "TEST.JPG", "image/jpeg", 8L, 1024L});
                    }
                }
                Bundle extras = new Bundle();
                if (mode == 2 || mode == 3) extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
                if (mode == 4) extras.putString(DocumentsContract.EXTRA_ERROR, "fixture");
                cursor.setExtras(extras);
                return cursor;
            }
            @Override public String getType(Uri uri) { return null; }
            @Override public Uri insert(Uri uri, android.content.ContentValues values) { throw new AssertionError("no writes"); }
            @Override public int delete(Uri uri, String where, String[] args) { throw new AssertionError("no writes"); }
            @Override public int update(Uri uri, android.content.ContentValues values, String where, String[] args) {
                throw new AssertionError("no writes");
            }
        };
        var info = new android.content.pm.ProviderInfo();
        info.authority = "sd-swap.fixture";
        provider.attachInfo(context, info);
        var resolver = android.content.ContentResolver.wrap(provider);
        Uri tree = DocumentsContract.buildTreeDocumentUri(info.authority, "AB12-CD34:DCIM");
        var empty = CameraDocumentSource.list(resolver, tree, "fixture", () -> false);
        require(CameraDifferentialService.noSdCandidates(empty, 0).contains("取り込める写真がありません"),
                "complete empty enumeration has an explicit empty result");
        provider.mode = 1;
        var full = CameraDocumentSource.list(resolver, tree, "fixture", () -> false);
        require(full.photos.size() == 1 && CameraDifferentialService.noSdCandidates(full, 1) == null,
                "complete JPEG enumeration produces a candidate");
        require(CameraDifferentialService.noSdCandidates(full, 0).contains("選んだ種類"),
                "kind filter is not a genuinely empty directory");
        for (int mode : new int[]{2, 3, 4, 5, 6, 9, 10, 11}) {
            provider.mode = mode;
            try {
                CameraDocumentSource.list(resolver, tree, "fixture", () -> false);
                throw new AssertionError("incomplete camera listing was accepted");
            } catch (CameraDocumentSource.ListingException expected) {
                var reason = switch (mode) {
                    case 2, 3 -> CameraDocumentSource.ListingException.Reason.LOADING;
                    case 6 -> CameraDocumentSource.ListingException.Reason.NO_GRANT;
                    default -> CameraDocumentSource.ListingException.Reason.UNAVAILABLE;
                };
                require(expected.reason == reason, "distinct incomplete listing outcome");
            }
        }
        provider.mode = 7;
        try {
            java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
            CameraDocumentSource.list(resolver, tree, "fixture", () -> checks.incrementAndGet() > 2);
            throw new AssertionError("cancelled camera listing was accepted");
        } catch (java.io.InterruptedIOException expected) { }
        provider.mode = 8;
        var video = CameraDocumentSource.list(resolver, tree, "fixture", () -> false);
        require(CameraDifferentialService.noSdCandidates(video, 0).contains("動画があります"),
                "unsupported videos are distinct from an empty directory");
        provider.stallNext();
        try {
            CameraDocumentSource.list(resolver, tree, "fixture", () -> false, 500);
            throw new AssertionError("a stalled provider returned a completed listing");
        } catch (CameraDocumentSource.ListingException expected) {
            require(expected.reason == CameraDocumentSource.ListingException.Reason.TIMEOUT, "stalled listing reaches its deadline");
            require(provider.started.getCount() == 0 && provider.returned.getCount() == 1,
                    "deadline returns while the provider remains blocked");
            require(provider.cancellable && provider.signalled.await(2, java.util.concurrent.TimeUnit.SECONDS),
                    "a blocked query receives provider cancellation");
            provider.mode = 1;
            require(CameraDocumentSource.list(resolver, tree, "fixture", () -> false).photos.size() == 1,
                    "a new request can list while an abandoned provider is blocked");
        } finally {
            provider.release.countDown();
            require(provider.returned.await(2, java.util.concurrent.TimeUnit.SECONDS), "stalled test provider was released");
        }

        provider.stallNext();
        try {
            CameraDocumentSource.list(resolver, tree, "fixture", () -> provider.started.getCount() == 0, 2000);
            throw new AssertionError("a cancelled provider produced a completed listing");
        } catch (InterruptedIOException expected) {
            require(provider.returned.getCount() == 1 && provider.signalled.await(2, java.util.concurrent.TimeUnit.SECONDS),
                    "cancellation returns before a non-responsive provider and forwards the signal");
        } finally {
            provider.release.countDown();
            require(provider.returned.await(2, java.util.concurrent.TimeUnit.SECONDS), "cancelled test provider was released");
        }

        require(CameraDocumentSource.isImportTree(tree, false), "DCIM picker path can be registered");
        require(!CameraDocumentSource.isImportTree(
                DocumentsContract.buildTreeDocumentUri(info.authority, "AB12-CD34:Pictures"), false),
                "a readable non-DCIM picker path cannot be registered");
        require(MountedCameraCard.matchesRegistration(tree, tree.toString(), true), "matching registered SD grant");
        require(!MountedCameraCard.matchesRegistration(tree, tree.toString(), false), "lost grant invalidates an SD request");
        require(!MountedCameraCard.matchesRegistration(tree, SOURCE_TREE.toString(), true),
                "changed registration invalidates even a granted internal-destination SD request");
        CameraDifferentialService.requireSdDestination("external_primary", "external_primary");
        for (String stale : new String[]{null, "external_primary"}) {
            try {
                CameraDifferentialService.requireSdDestination(stale, "ab12-cd34");
                throw new AssertionError("late SD service request could use a new destination");
            } catch (MountedCameraCard.Unavailable expected) { }
        }

        MountedCameraCard.requireGeneration(MountedCameraCard.detachGeneration());
        try {
            MountedCameraCard.requireGeneration(MountedCameraCard.detachGeneration() - 1);
            throw new AssertionError("request from before detach was accepted");
        } catch (MountedCameraCard.Unavailable expected) { }
    }

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
        registrationChecks();
        kindSelection();
        legacyReceiptRequiresRead(instrumentation.getTargetContext());
        return "PASS sd-original permission=true mapping=true pinnedVolume=true verifiedIndexAbsence=true requiredOriginal=true noFallback=true immutable=true cancel=true revoke=true bounds=true legacyReceiptRevalidated=true";
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

    private static void registrationChecks() throws IOException {
        var mapping = CameraDocumentSource.OriginalAccessException.Reason.MAPPING;
        var pending = CameraDocumentSource.OriginalAccessException.Reason.INDEX_PENDING;
        RegistrationProbe absent = new RegistrationProbe();
        expectRegistration(absent, SOURCE, pending);
        require(absent.accessChecks == 2 && absent.fileChecks == 1, "Only complete zero results confirm absence");

        RegistrationProbe nonMedia = new RegistrationProbe();
        nonMedia.mapped = DOCUMENT;
        expectRegistration(nonMedia, SOURCE, pending);

        RegistrationProbe legacyRoute = new RegistrationProbe();
        expectRegistration(legacyRoute, null, mapping);
        require(legacyRoute.accessChecks == 0 && legacyRoute.fileChecks == 0, "Ordinary SAF does not query registration");

        RegistrationProbe denied = new RegistrationProbe();
        denied.mappingFailure = new SecurityException("synthetic denial");
        expectRegistration(denied, SOURCE, CameraDocumentSource.OriginalAccessException.Reason.PERMISSION);
        RegistrationProbe unavailable = new RegistrationProbe();
        unavailable.mappingFailure = new IllegalStateException("synthetic provider failure");
        expectRegistration(unavailable, SOURCE, mapping);
        require(denied.accessChecks == 0 && unavailable.accessChecks == 0,
                "Denied or unavailable mapping bypasses the probe");

        RegistrationProbe rejected = new RegistrationProbe();
        rejected.mappingFailure = new IllegalArgumentException(new FileNotFoundException("synthetic missing index row"));
        expectRegistration(rejected, SOURCE, pending);
        require(rejected.accessChecks == 2 && rejected.fileChecks == 1,
                "A rejected mapping requires independent confirmation of absence");
        RegistrationProbe rejectedWithRow = new RegistrationProbe();
        rejectedWithRow.mappingFailure = new IllegalArgumentException("synthetic mapping rejection");
        rejectedWithRow.fileCount = 1;
        expectRegistration(rejectedWithRow, SOURCE, mapping);
        RegistrationProbe rejectedLegacyRoute = new RegistrationProbe();
        rejectedLegacyRoute.mappingFailure = rejected.mappingFailure;
        expectRegistration(rejectedLegacyRoute, null, mapping);
        require(rejectedLegacyRoute.accessChecks == 0, "Ordinary SAF does not probe a rejected mapping");

        RegistrationProbe wrongProvider = new RegistrationProbe();
        wrongProvider.document = DOCUMENT;
        expectRegistration(wrongProvider, SOURCE, mapping);
        RegistrationProbe wrongCard = new RegistrationProbe();
        expectRegistration(wrongCard, "ef56-7890", mapping);
        RegistrationProbe wrongPath = new RegistrationProbe();
        wrongPath.document = DocumentsContract.buildDocumentUriUsingTree(SOURCE_TREE, "AB12-CD34:DCIM/100TEST/OTHER.JPG");
        expectRegistration(wrongPath, SOURCE, mapping);
        require(wrongProvider.accessChecks + wrongCard.accessChecks + wrongPath.accessChecks == 0,
                "Untrusted source identity never opens the diagnostic query");

        for (int problem = 0; problem < 5; problem++) {
            RegistrationProbe unverifiable = new RegistrationProbe();
            switch (problem) {
                case 0 -> unverifiable.readable = false;
                case 1 -> unverifiable.documentCount = 2;
                case 2 -> unverifiable.name = "OTHER.JPG";
                case 3 -> unverifiable.size = null;
                case 4 -> unverifiable.size = (long) ORIGINAL.length + 1;
            }
            expectRegistration(unverifiable, SOURCE, mapping);
            require(unverifiable.fileChecks == 0, "Unreadable or mismatched document cannot establish missing registration");
        }
        for (int problem = 0; problem < 5; problem++) {
            RegistrationProbe notProven = new RegistrationProbe();
            switch (problem) {
                case 0 -> notProven.fileCount = 1;
                case 1 -> notProven.fileCount = 2;
                case 2 -> notProven.nullFiles = true;
                case 3 -> notProven.loading = true;
                case 4 -> notProven.revokeAfterQuery = true;
            }
            expectRegistration(notProven, SOURCE, mapping);
        }
        RegistrationProbe failedFiles = new RegistrationProbe();
        failedFiles.queryError = true;
        expectRegistration(failedFiles, SOURCE, mapping);
        RegistrationProbe deniedFiles = new RegistrationProbe();
        deniedFiles.queryDenied = true;
        expectRegistration(deniedFiles, SOURCE, mapping);

        RegistrationProbe cancelled = new RegistrationProbe();
        try {
            CameraDocumentSource.copy(cancelled, cancelled.entry(), new ByteArrayOutputStream(),
                    () -> cancelled.fileChecks > 0, SOURCE);
            throw new AssertionError("Cancellation during index check was ignored");
        } catch (InterruptedIOException expected) {}
    }

    private static void expectRegistration(RegistrationProbe access,String volume,
            CameraDocumentSource.OriginalAccessException.Reason reason) throws IOException {
        var output = new ByteArrayOutputStream();
        try {
            CameraDocumentSource.copy(access,access.entry(),output,()->false,volume);
            throw new AssertionError("An unverified original was opened");
        } catch (CameraDocumentSource.OriginalAccessException expected) {
            require(expected.reason == reason && output.size() == 0, "Exact registration failure with zero original bytes");
            if (reason == CameraDocumentSource.OriginalAccessException.Reason.INDEX_PENDING) {
                String guidance = expected.getMessage();
                require(guidance.contains("SDカードに写真があります")
                                && guidance.contains("Androidへの登録")
                                && guidance.contains("もう一度取り込んでください"),
                        "Index guidance reports the detected photo and explains when to retry");
            }
        }
    }

    private static final class RegistrationProbe implements CameraDocumentSource.OriginalAccess {
        Uri document = SOURCE_DOCUMENT;
        Uri mapped;
        RuntimeException mappingFailure;
        boolean readable = true, nullFiles, loading, queryError, revokeAfterQuery, queryDenied;
        int documentCount = 1, fileCount, accessChecks, fileChecks;
        String name = "TEST.JPG";
        Long size = (long) ORIGINAL.length;

        CameraDocumentSource.Entry entry() {
            return new CameraDocumentSource.Entry(document, new CameraImportFile("synthetic-registration",
                    "DCIM/100TEST/TEST.JPG",ORIGINAL.length,"stamp",1));
        }
        public boolean permitted() { return true; }
        public Uri mediaUri(Uri uri) {
            if (mappingFailure != null) throw mappingFailure;
            return mapped;
        }
        public boolean canCheckRegistration(Uri tree,String volume) {
            require(SOURCE_TREE.equals(tree) && SOURCE.equals(volume),"Check the exact original tree and mounted volume");
            accessChecks++;
            return readable && (!revokeAfterQuery || fileChecks == 0);
        }
        public Cursor documentRegistration(Uri requested) {
            require(document.equals(requested),"Read current metadata for the selected document");
            MatrixCursor cursor = new MatrixCursor(new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE});
            for (int i=0;i<documentCount;i++) cursor.addRow(new Object[]{SOURCE_DOCUMENT_ID,name,size});
            return cursor;
        }
        public Cursor mediaRegistration(String volume,String folder,String name) {
            require(SOURCE.equals(volume) && "DCIM/100TEST/".equals(folder) && "TEST.JPG".equals(name),
                    "Query only the exact source volume, relative path and name");
            fileChecks++;
            if (queryDenied) throw new SecurityException("synthetic query denial");
            if (nullFiles) return null;
            MatrixCursor cursor = new MatrixCursor(new String[]{MediaStore.MediaColumns._ID});
            for (int i=0;i<fileCount;i++) cursor.addRow(new Object[]{i});
            if (loading || queryError) {
                Bundle extras = new Bundle();
                if (loading) extras.putBoolean(DocumentsContract.EXTRA_LOADING,true);
                if (queryError) extras.putString(DocumentsContract.EXTRA_ERROR,"synthetic query error");
                cursor.setExtras(extras);
            }
            return cursor;
        }
        public InputStream openOriginal(Uri original) { throw new AssertionError("Index diagnostic must not read a file"); }
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
