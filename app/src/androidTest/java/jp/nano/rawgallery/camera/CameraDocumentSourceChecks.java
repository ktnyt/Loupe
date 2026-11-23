package jp.nano.rawgallery.camera;

import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContentUris;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Synthetic originals and isolated journals; publication fixtures write only their own emulator files. */
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
                var cursor = new MatrixCursor(columns) {
                    @Override public boolean onMove(int oldPosition, int newPosition) {
                        return mode != 17 && mode != 18;
                    }
                };
                if (mode == 1 || mode == 3 || mode == 4 || mode == 7 || mode == 18)
                    cursor.addRow(new Object[]{"AB12-CD34:DCIM/TEST.JPG", "TEST.JPG", "image/jpeg", 8L, 1024L});
                if (mode == 8)
                    cursor.addRow(new Object[]{"AB12-CD34:DCIM/TEST.MP4", "TEST.MP4", "video/mp4", 8L, 1024L});
                if (mode == 9) for (int i = 0; i <= 20000; i++)
                    cursor.addRow(new Object[]{"AB12-CD34:DCIM/unknown", "unknown", "text/plain", 8L, 1024L});
                if (mode == 13 || mode == 14)
                    cursor.addRow(new Object[]{"AB12-CD34:DCIM/TEST.JPG", mode == 13 ? "" : " ", "image/jpeg", 8L, 1024L});
                if (mode == 15 || mode == 16 || mode == 17)
                    cursor.addRow(new Object[]{DocumentsContract.Document.MIME_TYPE_DIR});
                if (mode == 16) cursor.addRow(new Object[]{DocumentsContract.Document.MIME_TYPE_DIR});
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
        for (int mode : new int[]{2, 3, 4, 5, 6, 9, 10, 11, 13, 14, 18}) {
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

        provider.stallNext();
        try {
            CameraDocumentSource.isReachableDirectory(resolver, tree, () -> false, 500);
            throw new AssertionError("stalled root metadata was accepted");
        } catch (CameraDocumentSource.ListingException expected) {
            require(expected.reason == CameraDocumentSource.ListingException.Reason.TIMEOUT
                            && provider.returned.getCount() == 1
                            && provider.signalled.await(2, java.util.concurrent.TimeUnit.SECONDS),
                    "root metadata reaches its deadline and cancels the provider before it returns");
            provider.mode = 15;
            require(CameraDocumentSource.isReachableDirectory(resolver, tree, () -> false),
                    "a fresh root read completes after the previous one times out");
        } finally {
            provider.release.countDown();
            require(provider.returned.await(2, java.util.concurrent.TimeUnit.SECONDS), "stalled root fixture was released");
        }

        for (int mode : new int[]{16, 17}) {
            provider.mode = mode;
            require(!CameraDocumentSource.isReachableDirectory(resolver, tree, () -> false),
                    "root reachability requires precisely one complete row that actually moves");
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

    /** Both sides of one-shot admission, with the actual worker-owned camera gate and a synthetic commit. */
    public static void assertSdAdmission() throws Exception {
        var acknowledgement = new java.util.concurrent.CountDownLatch(1);
        var commits = new java.util.concurrent.atomic.AtomicInteger();
        var operations = new java.util.concurrent.atomic.AtomicInteger();
        var result = new java.util.concurrent.atomic.AtomicReference<String>("not acknowledged");
        var failureAcknowledged = new java.util.concurrent.CountDownLatch(1);
        var failureResult = new java.util.concurrent.atomic.AtomicReference<String>();
        var grantDenied = new CameraDifferentialService.SdConfirmation((request, error) -> {
            failureResult.set(error);
            failureAcknowledged.countDown();
        });
        var abandoned = new CameraDifferentialService.SdConfirmation((request, error) -> {
            throw new AssertionError("a closed caller was acknowledged");
        });
        abandoned.cancel();
        var admitted = new CameraDifferentialService.SdConfirmation((request, error) -> {
            result.set(error);
            acknowledgement.countDown();
        });
        var fixture = new java.util.concurrent.FutureTask<>(() -> {
            boolean acquired = CameraImportOperationGate.runIfAvailable(CameraImportGate.lock, () -> {
                try {
                    require(!CameraDifferentialService.claimSd(abandoned, commits::incrementAndGet),
                            "closing before admission prevents any commit");
                    require(CameraDifferentialService.claimSd(admitted, commits::incrementAndGet),
                            "an open request is claimed under the worker gate");
                    admitted.cancel(); // Closing after claim cannot undo accepted work.
                    require(!CameraDifferentialService.claimSd(admitted, commits::incrementAndGet),
                            "the token cannot be claimed twice");
                    operations.incrementAndGet();
                    admitted.complete(null);

                    try {
                        if (CameraDifferentialService.claimSd(grantDenied, () -> {
                            throw new IOException("synthetic grant failure");
                        })) operations.incrementAndGet();
                        throw new AssertionError("grant failure did not stop admission");
                    } catch (IOException expected) {
                        grantDenied.complete(expected.getMessage());
                        grantDenied.complete(null);
                    }
                } catch (IOException failure) { throw new AssertionError(failure); }
            });
            require(acquired, "isolated admission worker acquired the gate");
            return null;
        });
        new Thread(fixture, "sd-admission-fixture").start();
        fixture.get(5, java.util.concurrent.TimeUnit.SECONDS);
        require(acknowledgement.await(5, java.util.concurrent.TimeUnit.SECONDS) && result.get() == null
                        && commits.get() == 1 && operations.get() == 1,
                "accepted work commits, executes and acknowledges once after the caller closes");
        require(failureAcknowledged.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        && "synthetic grant failure".equals(failureResult.get()) && operations.get() == 1,
                "a failed grant has only a failure acknowledgement and never executes import");
    }

    public static void acknowledgeSdUiFixture(CameraDifferentialService.SdConfirmation confirmation) {
        boolean entered = CameraImportOperationGate.runIfAvailable(CameraImportGate.lock, () -> {
            try {
                require(CameraDifferentialService.claimSd(confirmation, () -> {}),
                        "fixture uses a single admitted acknowledgement");
                confirmation.complete(null);
            } catch (IOException failure) { throw new AssertionError(failure); }
        });
        require(entered, "the isolated acknowledgement fixture owns the camera gate");
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
        Context context = instrumentation.getTargetContext();
        legacyReceiptRequiresRead(context);
        if ("jp.nano.rawgallery.sd166".equals(context.getPackageName())
                && ("ranchu".equals(android.os.Build.HARDWARE) || "goldfish".equals(android.os.Build.HARDWARE))) {
            revokedTreeGrant(context);
            publicationGrantBoundary(context);
            uncertainPublicationRecovery(context);
            replacementRecovery(context);
            conditionalPendingCleanup(context);
            migrateReplacementWitness(context);
        }
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
        for (Uri mapped : List.of(MEDIA, PINNED)) {
            for (int problem = 0; problem < 7; problem++) {
                Fake blocked = new Fake();
                blocked.mapped = mapped;
                blocked.checkMountedRow = true;
                blocked.mountedRow = mountedRow(problem);
                expect(blocked, CameraDocumentSource.OriginalAccessException.Reason.VOLUME, SOURCE);
                require(blocked.opens == 0 && blocked.readBytes == 0,
                        "incomplete or inconsistent MediaStore metadata never opens the source");
            }
            Fake complete = new Fake();
            complete.mapped = mapped;
            complete.checkMountedRow = true;
            complete.pinned = PINNED;
            complete.mountedRow = mountedRow(-1);
            var exactOutput = new ByteArrayOutputStream();
            CameraDocumentSource.copy(complete, entry(), exactOutput, () -> false, SOURCE);
            require(complete.opens == 1 && Arrays.equals(exactOutput.toByteArray(), ORIGINAL),
                    "complete single-row metadata allows exact and aggregate mapping to pin to the source");
        }
        Fake stopped = new Fake();
        stopped.pinned = PINNED;
        ByteArrayOutputStream stoppedOutput = new ByteArrayOutputStream();
        try {
            CameraDocumentSource.copy(stopped, entry(), stoppedOutput, () -> stopped.pins > 0, SOURCE);
            throw new AssertionError("Cancellation before the pinned open was ignored");
        } catch (InterruptedIOException expected) {}
        require(stopped.opens == 0 && stoppedOutput.size() == 0, "Cancelled source never opens or writes a photo");
    }

    private static Cursor mountedRow(int problem) {
        if (problem == 0) return null;
        String[] columns = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.VOLUME_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE};
        MatrixCursor result = new MatrixCursor(columns) {
            @Override public boolean onMove(int oldPosition, int newPosition) {
                if (problem == 4) return false;
                if (problem == 5) {
                    Bundle incomplete = new Bundle();
                    incomplete.putBoolean(DocumentsContract.EXTRA_LOADING, true);
                    setExtras(incomplete);
                }
                return true;
            }
        };
        result.addRow(new Object[]{problem == 6 ? 1L : 0L, SOURCE, "DCIM/", "TEST.JPG", (long) ORIGINAL.length});
        if (problem == 3) result.addRow(new Object[]{0L, SOURCE, "DCIM/", "TEST.JPG", (long) ORIGINAL.length});
        if (problem == 1 || problem == 2) {
            Bundle incomplete = new Bundle();
            if (problem == 1) incomplete.putBoolean(DocumentsContract.EXTRA_LOADING, true);
            else incomplete.putString(DocumentsContract.EXTRA_ERROR, "fixture");
            result.setExtras(incomplete);
        }
        return result;
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
        public Cursor documentRegistration(Uri requested, android.os.CancellationSignal signal) {
            require(document.equals(requested),"Read current metadata for the selected document");
            MatrixCursor cursor = new MatrixCursor(new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE});
            for (int i=0;i<documentCount;i++) cursor.addRow(new Object[]{SOURCE_DOCUMENT_ID,name,size});
            return cursor;
        }
        public Cursor mediaRegistration(String volume, String folder, String name, android.os.CancellationSignal signal) {
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
        String photoRead = android.os.Build.VERSION.SDK_INT >= 33 ? android.Manifest.permission.READ_MEDIA_IMAGES
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        require(context.checkSelfPermission(photoRead) == android.content.pm.PackageManager.PERMISSION_GRANTED,
                "Grant full photo access to the isolated test app before checking the real importer");
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
            Fake incomplete = new Fake();
            incomplete.checkMountedRow = true;
            incomplete.mountedRow = mountedRow(1);
            try {
                importer.importPhoto(original, output -> CameraDocumentSource.copy(
                        incomplete, entry(), output, () -> false, SOURCE), null, true, false, () -> false);
                throw new AssertionError("Incomplete source metadata reached the importer");
            } catch (CameraDocumentSource.OriginalAccessException expected) {
                require(expected.reason == CameraDocumentSource.OriginalAccessException.Reason.VOLUME, "Incomplete source is not pinned");
            }
            require(incomplete.opens == 0 && incomplete.readBytes == 0 && ledger.pending().isEmpty()
                            && ledger.known(original) == null,
                    "Incomplete source metadata cannot read original bytes, allocate a destination or record a receipt");

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
    private static void revokedTreeGrant(Context context) throws Exception {
        String database = "camera-grant-revoke-" + UUID.randomUUID() + ".db";
        String name = "grant-revoke-" + UUID.randomUUID() + ".JPG";
        var source = new CameraImportFile("sd:grant-fixture", "DCIM/" + name, 128 * 1024, "mtime", 1);
        var entry = new CameraDocumentSource.Entry(DOCUMENT, source);
        var ledger = new CameraImportLedger(context, database);
        var treeGranted = new AtomicBoolean(true);
        var elapsed = new java.util.concurrent.atomic.AtomicLong(0);
        var grant = grantFixture(treeGranted, elapsed);
        var access = new Fake();
        access.expectedPath = source.relativePath;
        access.pinned = PINNED;
        access.bytes = new byte[(int) source.bytes];
        new Random(165L).nextBytes(access.bytes);
        byte[] original = access.bytes.clone();
        access.onRead = () -> {
            // Revoke only the remembered SAF grant; MediaStore original permission stays granted.
            treeGranted.set(false);
            elapsed.addAndGet(101);
        };
        Set<String> staged = stagedCopies(context);
        try {
            try {
                new CameraPhotoImporter(context, ledger, jp.nano.rawgallery.PhotoStorage.PRIMARY)
                        .importPhoto(source, output -> {
                            grant.requireFresh();
                            CameraDocumentSource.copy(access, entry, output, grant::stopped, SOURCE);
                            grant.requireFresh();
                        }, "grant-check", true, false, grant::stopped, grant::requireFresh);
                throw new AssertionError("Revoking the tree grant did not stop a slow source read");
            } catch (InterruptedIOException expected) {
                try {
                    // This is the same stop-to-permission classification used by the SD service.
                    grant.requireFresh();
                    throw new AssertionError("The source grant loss was treated as a generic stop");
                } catch (MountedCameraCard.Unavailable unavailable) {
                    require(unavailable.getMessage().contains("読み取り権限"), "grant loss remains distinguishable");
                }
            }
            require(access.permitted && access.opens == 1 && access.closes == 1
                            && access.readBytes == 65536 && Arrays.equals(original, access.bytes),
                    "only the first chunk was read and the original fixture stayed unchanged");
            require(ledger.pending().isEmpty() && ledger.contentCount() == 0 && ledger.receiptCount() == 0
                            && staged.equals(stagedCopies(context)) && countNamedMedia(context, name) == 0,
                    "grant loss leaves no stage, pending/publication row or import history");
        } finally {
            ledger.close();
            require(context.deleteDatabase(database), "isolated grant fixture database removed");
        }
    }

    private static CameraDifferentialService.SdGrantCheck grantFixture(AtomicBoolean treeGranted,
            java.util.concurrent.atomic.AtomicLong elapsed) {
        return new CameraDifferentialService.SdGrantCheck(() -> {
            if (!treeGranted.get())
                throw new MountedCameraCard.Unavailable("取り込み元の読み取り権限が変わりました。");
        }, elapsed::get);
    }

    private static Set<String> stagedCopies(Context context) {
        String[] files = context.getCacheDir().list((directory, name) -> name.startsWith("camera-import-"));
        require(files != null, "can check isolated staged copies");
        return new HashSet<>(Arrays.asList(files));
    }

    private static int countNamedMedia(Context context, String name) {
        var args = new Bundle();
        args.putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=?");
        args.putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{name, context.getPackageName()});
        args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Images.Media.getContentUri(jp.nano.rawgallery.PhotoStorage.PRIMARY),
                new String[]{MediaStore.MediaColumns._ID}, args, null)) {
            require(cursor != null, "can check isolated fixture photo publication");
            return cursor.getCount();
        }
    }

    private static void publicationGrantBoundary(Context context) throws Exception {
        String database = "camera-grant-publication-" + UUID.randomUUID() + ".db";
        String name = "grant-publish-" + UUID.randomUUID() + ".JPG";
        File staged = File.createTempFile("sd-grant-fixture-", ".jpg", context.getCacheDir());
        var bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888);
        try (var stream = new FileOutputStream(staged)) {
            require(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream), "make fixture JPEG");
        } finally { bitmap.recycle(); }
        var source = new CameraImportFile("sd:grant-publication", "DCIM/" + name, staged.length(), "mtime", 1);
        String sha = CameraImportCopy.sha256(staged, () -> false);
        var ledger = new CameraImportLedger(context, database);
        Uri destination = null;
        try {
            String volume = jp.nano.rawgallery.PhotoStorage.PRIMARY;
            String folder = CameraPhotoPublisher.folder("sd-grant-fixture", false);
            var treeGranted = new AtomicBoolean(true);
            var elapsed = new java.util.concurrent.atomic.AtomicLong(0);
            var rejectedGrant = grantFixture(treeGranted, elapsed);
            var enteredReady = new AtomicBoolean();
            var refused = new CameraPhotoPublisher(context, ledger, volume, boundary -> {
                if (boundary == CameraPhotoPublisher.Boundary.FILE_VERIFIED) enteredReady.set(true);
            });
            try {
                refused.publish(source, staged, sha, "JPEG", folder, "grant-fixture", () -> false, () -> {
                    require(ledger.pending().size() == 1 && !ledger.pending().get(0).ready,
                            "source authorization runs before durable READY");
                    treeGranted.set(false);
                    rejectedGrant.requireFresh();
                });
                throw new AssertionError("The revoked grant admitted publication");
            } catch (MountedCameraCard.Unavailable expected) { }
            refused.recover(() -> false);
            require(!enteredReady.get() && ledger.pending().isEmpty() && ledger.receiptCount() == 0
                            && ledger.contentCount() == 0 && countNamedMedia(context, name) == 0,
                    "refused authorization has no READY row, photo or receipt even after recovery");

            treeGranted.set(true);
            var admittedGrant = grantFixture(treeGranted, elapsed);
            var admitted = new CameraPhotoPublisher(context, ledger, volume, boundary -> {
                if (boundary == CameraPhotoPublisher.Boundary.PUBLISHED) treeGranted.set(false);
            });
            destination = admitted.publish(source, staged, sha, "JPEG", folder, "grant-fixture",
                    admittedGrant::stopped, admittedGrant::requireFresh);
            require(!treeGranted.get() && countNamedMedia(context, name) == 1 && ledger.contentCount() == 1
                            && ledger.receiptCount() == 1 && ledger.pending().isEmpty(),
                    "publication admitted while granted keeps its visible photo and receipt after a later revocation");
            try {
                admittedGrant.requireFresh();
                throw new AssertionError("A later item could reuse the revoked grant");
            } catch (MountedCameraCard.Unavailable expected) { }

            var importer = new CameraPhotoImporter(context, ledger, volume);
            try {
                importer.importPhoto(source, out -> { throw new AssertionError("fixture has a known receipt"); },
                        "sd-grant-fixture", true, false, () -> false, admittedGrant::requireFresh);
                throw new AssertionError("A revoked source grant counted an existing receipt as a match");
            } catch (MountedCameraCard.Unavailable expected) { }

            var newSource = new CameraImportFile("sd:grant-new-binding", "DCIM/" + name, staged.length(), "mtime", 1);
            try {
                importer.importPhoto(newSource, out -> java.nio.file.Files.copy(staged.toPath(), out),
                        "sd-grant-fixture", true, false, () -> false, admittedGrant::requireFresh);
                throw new AssertionError("A revoked source grant bound an existing hash as a new receipt");
            } catch (MountedCameraCard.Unavailable expected) { }
            require(ledger.known(newSource) == null && ledger.receiptCount() == 1 && ledger.pending().isEmpty(),
                    "receipt reuse and binding both recheck the source grant without erasing the admitted receipt");

            refuseUnreadyVisible(context, source, staged, sha, destination, volume, folder);
            destination = replaceAfterRefusedGrant(context, ledger, source, staged, destination, name, volume);
        } finally {
            if (destination != null) context.getContentResolver().delete(destination, null, null);
            ledger.close();
            require(context.deleteDatabase(database) && staged.delete(), "isolated publication fixture cleaned up");
        }
    }

    private static Uri replaceAfterRefusedGrant(Context context, CameraImportLedger ledger, CameraImportFile source,
            File staged, Uri destination, String name, String volume) throws Exception {
        CameraImportLedger.Content old = ledger.known(source);
        var secondSource = new CameraImportFile("sd:second-transport", source.relativePath, source.bytes,
                "second", source.captureSeconds);
        ledger.bind(secondSource, old);
        List<List<String>> oldReceipts = receiptRows(ledger);
        require(context.getContentResolver().delete(destination, null, null) == 1,
                "remove only the destination created by this isolated fixture");
        require(countNamedMedia(context, name) == 0, "missing destination was confirmed");

        var importer = new CameraPhotoImporter(context, ledger, volume);
        var grant = new AtomicBoolean(true);
        var boundary = grantFixture(grant, new java.util.concurrent.atomic.AtomicLong(0));
        try {
            importer.importPhoto(source, out -> java.nio.file.Files.copy(staged.toPath(), out),
                    "sd-grant-fixture", true, false, () -> false, () -> {
                        require(ledger.pending().size() == 1 && !ledger.pending().get(0).ready,
                                "replacement grant is checked before durable READY");
                        grant.set(false);
                        boundary.requireFresh();
                    });
            throw new AssertionError("Revoked replacement grant permitted publication");
        } catch (MountedCameraCard.Unavailable expected) { }
        require(ledger.known(source).uri.equals(old.uri) && ledger.known(secondSource).uri.equals(old.uri)
                        && receiptRows(ledger).equals(oldReceipts) && ledger.contentCount() == 1
                        && ledger.pending().isEmpty() && countNamedMedia(context, name) == 0,
                "refused replacement leaves both historical receipts and the old content intact");
        var replacement = importer.importPhoto(source, out -> java.nio.file.Files.copy(staged.toPath(), out),
                "sd-grant-fixture", true, false, () -> false);
        CameraImportLedger.Content updated = ledger.known(source);
        require(replacement.imported && updated.uri.equals(replacement.uri.toString()) && !updated.uri.equals(old.uri)
                        && updated.sourceSha.equals(old.sourceSha) && updated.createdAt == old.createdAt
                        && updated.kind.equals(old.kind) && updated.captureGroup.equals(old.captureGroup)
                        && ledger.known(secondSource).uri.equals(updated.uri) && receiptRows(ledger).equals(oldReceipts)
                        && ledger.pending().isEmpty() && countNamedMedia(context, name) == 1,
                "successful verified replacement repoints both receipts without rewriting timestamps or metadata");
        return replacement.uri;
    }

    private static List<List<String>> receiptRows(CameraImportLedger ledger) {
        List<List<String>> rows = new ArrayList<>();
        try (Cursor cursor = ledger.getReadableDatabase().rawQuery("SELECT * FROM receipts ORDER BY source_key", null)) {
            while (cursor.moveToNext()) {
                List<String> row = new ArrayList<>();
                for (int i = 0; i < cursor.getColumnCount(); ++i) row.add(cursor.getString(i));
                rows.add(row);
            }
        }
        return rows;
    }

    private static void refuseUnreadyVisible(Context context, CameraImportFile source, File staged, String sha,
            Uri visible, String volume, String folder) throws IOException {
        String database = "camera-unready-visible-" + UUID.randomUUID() + ".db";
        var ledger = new CameraImportLedger(context, database);
        try {
            var pending = ledger.allocate(source, sha, sha, staged.length(), source.name, folder, "JPEG", "fixture", volume);
            ledger.attach(pending.token, visible.toString());
            try {
                new CameraPhotoPublisher(context, ledger, volume, ignored -> {}).recover(() -> false);
                throw new AssertionError("Visible UNREADY file was promoted to an authorized receipt");
            } catch (IOException expected) {
                require(expected.getMessage().contains("no READY authorization"), "visible UNREADY is an explicit inconsistency");
            }
            require(!ledger.pending(pending.token).ready && ledger.contentCount() == 0 && ledger.receiptCount() == 0
                            && countNamedMedia(context, source.name) == 1,
                    "visible UNREADY file and journal both survive without an invented receipt");
        } finally {
            ledger.close();
            require(context.deleteDatabase(database), "isolated UNREADY fixture removed");
        }
    }

    private static void uncertainPublicationRecovery(Context context) throws IOException {
        String database = "camera-uncertain-journal-" + UUID.randomUUID() + ".db";
        var ledger = new CameraImportLedger(context, database);
        try {
            String sha = hexDigest(ORIGINAL);
            String volume = jp.nano.rawgallery.PhotoStorage.PRIMARY;
            var pending = ledger.allocate(entry().file, sha, sha, ORIGINAL.length, "TEST.JPG",
                    CameraPhotoPublisher.folder("camera-uncertain-fixture", false), "JPEG", "fixture", volume);

            for (int problem : new int[]{0, 1, 2, 3, 4}) {
                expectUncertainRecovery(context, ledger, pending.token, volume, problem);
            }
            // Simulate an attached READY journal after the visibility call: unknown results
            // must not discard it before the eventual receipt recovery.
            Uri attached = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volume), 123456789L);
            ledger.attach(pending.token, attached.toString());
            ledger.ready(pending.token);
            for (int problem : new int[]{0, 1, 2, 3, 4}) {
                expectUncertainRecovery(context, ledger, pending.token, volume, problem);
                require(ledger.pending(pending.token).ready, "unknown state retains READY authority");
            }
        } finally {
            ledger.close();
            require(context.deleteDatabase(database), "isolated uncertain fixture removed");
        }
    }

    private static void expectUncertainRecovery(Context context, CameraImportLedger ledger, String token,
            String volume, int problem) throws IOException {
        var publisher = new CameraPhotoPublisher(context, ledger, volume, ignored -> {}, (uri, fields, args) -> {
            if (problem == 0) return null;
            var cursor = new MatrixCursor(fields) {
                @Override public boolean onMove(int oldPosition, int newPosition) { return problem != 3; }
            };
            if (problem >= 3) cursor.addRow(new Object[fields.length]);
            if (problem == 4) cursor.addRow(new Object[fields.length]);
            Bundle extras = new Bundle();
            if (problem == 1) extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
            if (problem == 2) extras.putString(DocumentsContract.EXTRA_ERROR, "fixture");
            cursor.setExtras(extras);
            return cursor;
        });
        try {
            publisher.recover(() -> false);
            throw new AssertionError("Unknown photo result was interpreted as confirmed absence");
        } catch (IOException expected) {
            require(ledger.pending(token) != null && ledger.contentCount() == 0 && ledger.receiptCount() == 0,
                    "unknown lookup or exact state retains its journal and does not invent history");
        }
    }

    private static final class ProcessLoss extends Error {}

    private static void replacementRecovery(Context context) throws IOException {
        String database = "camera-replacement-witness-" + UUID.randomUUID() + ".db";
        var ledger = new CameraImportLedger(context, database);
        File staged = File.createTempFile("sd-replacement-fixture-", ".jpg", context.getCacheDir());
        Uri target = null;
        try {
            try (var output = new FileOutputStream(staged)) { output.write(ORIGINAL); }
            String name = "replacement-" + UUID.randomUUID() + ".JPG";
            var source = new CameraImportFile("sd:replacement", "DCIM/" + name, staged.length(), "mtime", 1);
            String sha = hexDigest(ORIGINAL);
            String volume = jp.nano.rawgallery.PhotoStorage.PRIMARY;
            Uri oldUri = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volume), 123456789L);
            Uri movedUri = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volume), 123456790L);
            require(!CameraPhotoPublisher.savedPhotoExists(context, oldUri)
                            && !CameraPhotoPublisher.savedPhotoExists(context, movedUri),
                    "both former saved identities have complete absence");
            var old = ledger.adopt(source, sha, sha, source.bytes, oldUri.toString(), "JPEG", "fixture");
            var receipts = receiptRows(ledger);
            var publisher = new CameraPhotoPublisher(context, ledger, volume, boundary -> {
                if (boundary == CameraPhotoPublisher.Boundary.FILE_VERIFIED) throw new ProcessLoss();
            });
            try {
                publisher.publish(source, staged, sha, "JPEG", CameraPhotoPublisher.folder("camera-cas-fixture", false),
                        "fixture", () -> false, CameraPhotoPublisher.NO_COMMIT_CHECK, old);
                throw new AssertionError("Fixture did not stop at FILE_VERIFIED");
            } catch (ProcessLoss expected) { }
            var pending = ledger.pending().get(0);
            target = Uri.parse(pending.uri);
            require(pending.ready && old.sameRow(pending.replacement), "READY journals the initial absent row");
            require(ledger.relocate(oldUri.toString(), null, movedUri.toString(), sha), "fixture concurrently changes the old row");
            ledger.close();
            ledger = new CameraImportLedger(context, database);
            var recovery = new CameraPhotoPublisher(context, ledger, volume, ignored -> {});
            try {
                recovery.recover(() -> false);
                throw new AssertionError("Recovery replaced a newer absent saved row");
            } catch (IOException expected) {
                require(expected.getMessage().contains("changed before replacement"), "recovery refused the newer content");
            }
            require(ledger.pending(pending.token).ready && old.sameRow(ledger.pending(pending.token).replacement)
                            && ledger.known(source).uri.equals(movedUri.toString()) && receiptRows(ledger).equals(receipts)
                            && countNamedMedia(context, name) == 0,
                    "recovery retained the original witness, READY, pending media and newer history");
            ContentValues legacy = new ContentValues();
            legacy.putNull("replacement_witness");
            ledger.getWritableDatabase().update("pending", legacy, "token=?", new String[]{pending.token});
            try {
                recovery.recover(() -> false);
                throw new AssertionError("A legacy READY journal inferred replacement authority");
            } catch (IOException expected) {
                require(expected.getMessage().contains("changed before publication")
                                && ledger.pending(pending.token).ready && countNamedMedia(context, name) == 0,
                        "legacy READY retains pending bytes without replacing existing history");
            }
            legacy.put("replacement_witness", old.encodeWitness());
            ledger.getWritableDatabase().update("pending", legacy, "token=?", new String[]{pending.token});
            require(ledger.relocate(movedUri.toString(), null, oldUri.toString(), sha), "fixture restores original witness");
            recovery.recover(() -> false);
            require(ledger.pending().isEmpty() && target.toString().equals(ledger.known(source).uri)
                            && receiptRows(ledger).equals(receipts) && countNamedMedia(context, name) == 1,
                    "recovery completes the original witnessed replacement and preserves receipts");
        } finally {
            if (target != null) context.getContentResolver().delete(target, null, null);
            ledger.close();
            require(context.deleteDatabase(database) && staged.delete(), "isolated replacement fixture removed");
        }
    }

    private static void conditionalPendingCleanup(Context context) throws IOException {
        String database = "camera-cleanup-race-" + UUID.randomUUID() + ".db";
        var ledger = new CameraImportLedger(context, database);
        Uri target = null;
        try {
            var resolver = context.getContentResolver();
            String volume = jp.nano.rawgallery.PhotoStorage.PRIMARY;
            Uri collection = MediaStore.Images.Media.getContentUri(volume);
            String sha = hexDigest(ORIGINAL);
            String name = "cleanup-race-" + UUID.randomUUID() + ".JPG";
            var pending = ledger.allocate(entry().file, sha, sha, ORIGINAL.length, name,
                    CameraPhotoPublisher.folder("camera-race-fixture", false), "JPEG", "fixture", volume);
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, pending.pendingName);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, pending.relativeFolder);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            target = resolver.insert(collection, values);
            require(target != null, "fixture obtained a pending row");
            ledger.attach(pending.token, target.toString());
            try (var output = resolver.openOutputStream(target)) { output.write(ORIGINAL); }
            Uri racedTarget = target;
            var checks = new java.util.concurrent.atomic.AtomicInteger();
            var recovery = new CameraPhotoPublisher(context, ledger, volume, ignored -> {}, (uri, fields, args) -> {
                Cursor cursor = resolver.query(uri, fields, args, null);
                if (!racedTarget.equals(uri) || checks.incrementAndGet() != 2 || cursor == null) return cursor;
                return new CursorWrapper(cursor) {
                    @Override public void close() {
                        super.close();
                        ContentValues visible = new ContentValues();
                        visible.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        visible.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                        require(resolver.update(racedTarget, visible, null, null) == 1,
                                "fixture publishes after cleanup observed the old pending identity");
                    }
                };
            });
            try {
                recovery.recover(() -> false);
                throw new AssertionError("Cleanup accepted a stale pending observation");
            } catch (IOException expected) {
                require(expected.getMessage().contains("cleanup failed"), "conditional deletion refused the new row");
            }
            require(ledger.pending(pending.token) != null && !ledger.pending(pending.token).ready
                            && ledger.contentCount() == 0 && countNamedMedia(context, name) == 1,
                    "a pending-to-visible race preserves both the visible item and its unready journal");
            try (var input = resolver.openInputStream(target)) {
                require(Arrays.equals(input.readAllBytes(), ORIGINAL), "racing cleanup preserves the visible bytes");
            }
        } finally {
            if (target != null) context.getContentResolver().delete(target, null, null);
            ledger.close();
            require(context.deleteDatabase(database), "isolated cleanup fixture removed");
        }
    }

    private static void migrateReplacementWitness(Context context) {
        String templateName = "camera-witness-schema-" + UUID.randomUUID() + ".db";
        List<String> schema = new ArrayList<>();
        try (var template = new CameraImportLedger(context, templateName);
                Cursor rows = template.getReadableDatabase().rawQuery(
                        "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL"
                                + " AND tbl_name IN ('contents','receipts','pending') ORDER BY rowid", null)) {
            while (rows.moveToNext()) schema.add(rows.getString(0));
        } finally {
            require(context.deleteDatabase(templateName), "isolated schema template removed");
        }
        String sha = hexDigest(ORIGINAL);
        var source = entry().file;
        String volume = jp.nano.rawgallery.PhotoStorage.PRIMARY;
        String saved = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volume), 11).toString();
        String target = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volume), 12).toString();
        for (int version : new int[]{1, 2}) {
            String name = "camera-witness-v" + version + "-" + UUID.randomUUID() + ".db";
            try {
                try (SQLiteDatabase old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)) {
                    for (String sql : schema) {
                        String versioned = sql.replace(",replacement_witness TEXT", "");
                        if (version == 1) versioned = versioned.replace(",volume TEXT NOT NULL", "");
                        old.execSQL(versioned);
                    }
                    old.execSQL("INSERT INTO contents VALUES(?,?,?,?,?,?,?,?)",
                            new Object[]{sha, source.bytes, sha, source.bytes, saved, "JPEG", "fixture", 17});
                    old.execSQL("INSERT INTO receipts VALUES(?,?,?,?,?,?,?,?)",
                            new Object[]{source.sourceKey(), source.sourceId, source.relativePath, source.bytes,
                                    source.sourceStamp, source.captureSeconds, sha, 18});
                    String columns = "token,source_id,relative_path,source_bytes,source_stamp,capture_seconds,"
                            + "source_sha,stored_sha,stored_bytes,kind,capture_group,pending_name,final_name,"
                            + "relative_folder,target_uri,ready,created_at";
                    old.execSQL("INSERT INTO pending(" + columns + ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            new Object[]{"legacy", source.sourceId, source.relativePath, source.bytes, source.sourceStamp,
                                    source.captureSeconds, sha, sha, source.bytes, "JPEG", "fixture", "__loupe_fixture.JPG",
                                    source.name, "Pictures/CameraSync/fixture/", target, 1, 19});
                    if (version == 2) old.execSQL("UPDATE pending SET volume=?", new Object[]{"abcd-1234"});
                    old.setVersion(version);
                }
                try (var migrated = new CameraImportLedger(context, name)) {
                    var content = migrated.known(source);
                    var journal = migrated.pending("legacy");
                    require(migrated.getReadableDatabase().getVersion() == 3 && migrated.contentCount() == 1
                                    && migrated.receiptCount() == 1 && migrated.pending().size() == 1
                                    && content.createdAt == 17 && content.uri.equals(saved)
                                    && receiptRows(migrated).get(0).get(7).equals("18")
                                    && journal.ready && journal.uri.equals(target) && journal.replacement == null
                                    && journal.source.sourceKey().equals(source.sourceKey())
                                    && journal.volume.equals(version == 1 ? volume : "abcd-1234"),
                            "legacy migration preserves content, receipt and READY state without inventing authority");
                    try (Cursor row = migrated.getReadableDatabase().rawQuery(
                            "SELECT created_at FROM pending WHERE token='legacy'", null)) {
                        require(row.moveToFirst() && row.getLong(0) == 19, "legacy pending timestamp preserved");
                    }
                }
            } finally {
                require(context.deleteDatabase(name), "isolated legacy migration fixture removed");
            }
        }
    }

    private static String hexDigest(byte[] bytes) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
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
        String expectedPath = "DCIM/TEST.JPG";
        byte[] bytes = ORIGINAL.clone();
        Runnable onRead = () -> { };
        public boolean permitted() { return permitted; }
        public Uri mediaUri(Uri document) { require(DOCUMENT.equals(document), "Exact selected document mapping"); maps++; return mapped; }
        boolean checkMountedRow;
        Cursor mountedRow;

        public Uri onVolume(Uri media, CameraImportFile file, String expectedVolume, android.os.CancellationSignal signal) {
            require(mapped.equals(media) && expectedPath.equals(file.relativePath) && SOURCE.equals(expectedVolume),
                    "Pinned check receives the exact mapping, original metadata and selected volume");
            pins++;
            if (checkMountedRow) {
                try (Cursor row = mountedRow) {
                    return CameraDocumentSource.matchesMountedOriginal(row,
                            expectedVolume, android.content.ContentUris.parseId(media), file) ? PINNED : null;
                }
            }
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
