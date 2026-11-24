package jp.nano.rawgallery.camera;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Requests indexing one confirmed missing document at a time, once per candidate per import. */
final class SdIndexRecovery {
    private static final long SCAN_BUDGET_MS = 3_000;
    private static final long CANCEL_POLL_MS = 50;

    @FunctionalInterface
    interface Target {
        File resolve(CameraDocumentSource.Entry entry, BooleanSupplier stopped) throws IOException;
    }

    @FunctionalInterface
    interface Scanner {
        void scan(String path, MediaScannerConnection.OnScanCompletedListener listener);
    }

    private final Target target;
    private final Scanner scanner;
    private final Set<Uri> requested = new HashSet<>();
    private long remainingMs;

    SdIndexRecovery(Context context, Uri tree, String sourceVolume) {
        this((entry, stopped) -> resolveTarget(context, tree, sourceVolume, entry, stopped),
                (path, listener) -> MediaScannerConnection.scanFile(context, new String[]{path}, null, listener),
                SCAN_BUDGET_MS);
    }

    SdIndexRecovery(Target target, Scanner scanner, long budgetMs) {
        this.target = target;
        this.scanner = scanner;
        remainingMs = budgetMs;
    }

    /** The caller has confirmed INDEX_PENDING. A callback only allows it to redo the original mapping. */
    boolean recover(CameraDocumentSource.Entry entry, BooleanSupplier stopped,
            CameraPhotoPublisher.CommitCheck requireFreshSource) throws IOException {
        CameraPhotoPublisher.check(stopped);
        if (remainingMs <= 0 || requested.contains(entry.uri)) return false;
        File file = tryCurrentTarget(entry, stopped, requireFreshSource);
        if (file == null) return false;
        requested.add(entry.uri);
        String path = file.getPath();
        CountDownLatch completed = new CountDownLatch(1);
        boolean[] registered = {false};
        long start = SystemClock.elapsedRealtime();
        long deadline = start + remainingMs;
        try {
            try {
                scanner.scan(path, (scannedPath, media) -> {
                    registered[0] = path.equals(scannedPath) && media != null
                            && "content".equals(media.getScheme()) && "media".equals(media.getAuthority());
                    completed.countDown();
                });
            } catch (RuntimeException unavailable) {
                return false;
            }
            while (true) {
                CameraPhotoPublisher.check(stopped);
                long waitMs = deadline - SystemClock.elapsedRealtime();
                if (waitMs <= 0) return false;
                if (completed.await(Math.min(waitMs, CANCEL_POLL_MS), TimeUnit.MILLISECONDS)) {
                    CameraPhotoPublisher.check(stopped);
                    if (!registered[0] || SystemClock.elapsedRealtime() >= deadline) return false;
                    break;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("SD card indexing cancelled");
        } finally {
            remainingMs = Math.max(0, deadline - SystemClock.elapsedRealtime());
        }
        return file.equals(tryCurrentTarget(entry, stopped, requireFreshSource));
    }

    private File tryCurrentTarget(CameraDocumentSource.Entry entry, BooleanSupplier stopped,
            CameraPhotoPublisher.CommitCheck requireFreshSource) throws IOException {
        requireFreshSource.check();
        File current;
        try {
            current = target.resolve(entry, stopped);
        } catch (CameraDocumentSource.ListingException unavailable) {
            if (unavailable.reason == CameraDocumentSource.ListingException.Reason.NO_GRANT) throw unavailable;
            // Missing or inconclusive evidence only defers this candidate's optional scan.
            current = null;
        }
        CameraPhotoPublisher.check(stopped);
        requireFreshSource.check();
        CameraPhotoPublisher.check(stopped);
        return current;
    }

    private static File resolveTarget(Context context, Uri tree, String sourceVolume,
            CameraDocumentSource.Entry entry, BooleanSupplier stopped) throws IOException {
        String relative = exactRelativePath(tree, entry);
        if (relative == null || sourceVolume == null) return null;
        // Filesystem metadata can stall when the card disappears; never block the import worker on it.
        File file = CameraDocumentSource.boundedFileMetadata(stopped,
                signal -> mountedFile(context, tree, sourceVolume, relative));
        if (file == null) return null;
        CameraDocumentSource.requireCurrentScanDocument(context.getContentResolver(), entry, sourceVolume, stopped);
        return file;
    }

    private static File mountedFile(Context context, Uri tree, String sourceVolume, String relative) {
        if (!sourceVolume.equals(SdSourceRegistrations.mountedVolume(context, tree))) return null;
        String uuid = SdSourceRegistrations.volumeUuid(tree);
        File directory = null;
        try {
            StorageManager manager = context.getSystemService(StorageManager.class);
            if (manager == null) return null;
            for (StorageVolume volume : manager.getStorageVolumes()) {
                var mounted = SdSourceRegistrations.Volume.from(volume);
                if (!mounted.removable() || !mounted.readable() || !uuid.equals(mounted.uuid())
                        || !sourceVolume.equals(mounted.name())) continue;
                if (directory != null) return null;
                directory = volume.getDirectory();
                if (directory == null) return null;
            }
        } catch (RuntimeException unavailable) {
            return null;
        }
        return exactFile(directory, relative);
    }

    static String exactRelativePath(Uri tree, CameraDocumentSource.Entry entry) {
        if (SdSourceRegistrations.volumeUuid(tree) == null || !CameraDocumentSource.isImportTree(tree, false))
            return null;
        String treeId = DocumentsContract.getTreeDocumentId(tree);
        String root = treeId.substring(treeId.indexOf(':') + 1);
        String relative = entry.file.relativePath;
        if (!relative.startsWith(root + "/")) return null;
        Uri document = DocumentsContract.buildDocumentUriUsingTree(tree,
                treeId.substring(0, treeId.indexOf(':') + 1) + relative);
        return document.equals(entry.uri) ? relative : null;
    }

    static File exactFile(File directory, String relative) {
        if (directory == null) return null;
        try {
            File root = directory.getCanonicalFile();
            File file = new File(root, relative);
            // SAF confirms the file type. App-side stat may not see an unindexed file through FUSE.
            if (!file.getCanonicalFile().equals(file) || file.isDirectory()) return null;
            return file;
        } catch (IOException | SecurityException unavailable) {
            return null;
        }
    }
}
