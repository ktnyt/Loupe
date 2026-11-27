package jp.nano.rawgallery.camera;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;
import java.io.*;
import java.util.Map;
import java.util.function.BooleanSupplier;
import jp.nano.rawgallery.PhotoStorage;

/**
 * The photo import contract shared by Wi-Fi, SAF/SD and USB readers. Call under CameraImportGate.
 */
public final class CameraPhotoImporter {
  /** Retrying within the same import must not select a newer missing destination. */
  static final class SavedPhotoConflict extends IOException {
    SavedPhotoConflict(String message) {
      super(message);
    }
  }

  /** One file's initial absent destination, retained across in-operation read retries. */
  static final class Attempt {
    private CameraImportLedger.Content firstMissing;
  }

  public static final class Result {
    public final Uri uri;
    public final boolean imported, contentChecked;
    public final long sourceBytesRead;

    Result(Uri uri, boolean imported, boolean checked, long bytes) {
      this.uri = uri;
      this.imported = imported;
      this.contentChecked = checked;
      this.sourceBytesRead = bytes;
    }
  }

  private final Context context;
  private final CameraImportLedger ledger;
  private final CameraPhotoPublisher publisher;
  private final String defaultFolder;
  private final java.util.function.LongConsumer savedReadObserver;
  private CameraImportMetrics metrics;

  public void setMetrics(CameraImportMetrics metrics) {
    this.metrics = metrics;
    publisher.setReadCounter(
        n -> {
          if (this.metrics != null) this.metrics.destinationBytes += n;
        });
  }

  public CameraPhotoImporter(Context context) {
    this(context, CameraImportLedger.get(context));
  }

  public CameraPhotoImporter(Context context, CameraImportLedger ledger) {
    this(context, ledger, PhotoStorage.selected(context));
  }

  public CameraPhotoImporter(Context context, CameraImportLedger ledger, String volume) {
    this(context, ledger, volume, count -> {});
  }

  CameraPhotoImporter(
      Context context,
      CameraImportLedger ledger,
      String volume,
      java.util.function.LongConsumer savedReadObserver) {
    this.context = context.getApplicationContext();
    this.ledger = ledger;
    this.publisher = new CameraPhotoPublisher(this.context, ledger, volume, b -> {});
    this.defaultFolder = HistoryDb.Companion.get(this.context).defaultPhotoFolder();
    this.savedReadObserver = savedReadObserver;
  }

  public void recover(BooleanSupplier cancelled) throws IOException {
    publisher.recover(cancelled);
  }

  Map<String, CameraImportLedger.Content> sourceSnapshot(String sourceId) {
    return ledger.sourceSnapshot(sourceId);
  }

  boolean knownSdRevision(
      CameraImportFile source,
      BooleanSupplier stopped,
      CameraPhotoPublisher.CommitCheck requireMappedSource,
      CameraPhotoPublisher.CommitCheck requireFreshSource)
      throws IOException {
    if (!SdSavedPhotoRevision.eligibleSource(source)) return false;
    requireLibraryPermission();
    CameraImportLedger.Content content = ledger.known(source);
    if (content == null) return false;
    SdSavedPhotoRevision proof = ledger.sdRevision(content.sourceSha);
    if (proof == null || !proof.matchesContent(content)) return false;
    requireMappedSource.check();
    if (!proof.equals(SdSavedPhotoRevision.capture(context, content, stopped))) return false;
    requireFreshSource.check();
    requireLibraryPermission();
    return content.sameRow(ledger.known(source))
        && proof.equals(ledger.sdRevision(content.sourceSha));
  }

  private boolean available(CameraImportLedger.Content content, BooleanSupplier cancelled)
      throws IOException {
    return available(content, cancelled, false, false, CameraPhotoPublisher.NO_COMMIT_CHECK);
  }

  private boolean available(
      CameraImportLedger.Content content,
      BooleanSupplier cancelled,
      boolean seedSd,
      boolean clearSdOnMismatch,
      CameraPhotoPublisher.CommitCheck beforeCommit)
      throws IOException {
    Uri uri = Uri.parse(content.uri);
    CameraPhotoPublisher.SavedRevision before =
        CameraPhotoPublisher.savedRevision(context, uri, cancelled);
    if (before == null) return false;
    var resolver = context.getContentResolver();
    try (ParcelFileDescriptor file = resolver.openFileDescriptor(uri, "r")) {
      if (file == null) throw new IOException("Saved photo unavailable");
      StructStat opened = Os.fstat(file.getFileDescriptor());
      if (!OsConstants.S_ISREG(opened.st_mode) || opened.st_size != content.storedBytes)
        throw savedPhotoChanged(content, clearSdOnMismatch);
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[65536];
      int n;
      long size = 0;
      try (InputStream in =
          new ParcelFileDescriptor.AutoCloseInputStream(
              ParcelFileDescriptor.dup(file.getFileDescriptor()))) {
        while ((n = in.read(buf)) != -1) {
          CameraPhotoPublisher.check(cancelled);
          size += n;
          if (metrics != null) metrics.destinationBytes += n;
          savedReadObserver.accept(n);
          if (size > content.storedBytes) break;
          digest.update(buf, 0, n);
        }
      }
      StringBuilder sha = new StringBuilder();
      for (byte b : digest.digest())
        sha.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
      if (size != content.storedBytes || !sha.toString().equals(content.storedSha))
        throw savedPhotoChanged(content, clearSdOnMismatch);
      try (ParcelFileDescriptor current = resolver.openFileDescriptor(uri, "r")) {
        if (current == null) {
          Log.e("LedgerIdentityProbe", "branch=reopen-null");
          throw savedPhotoChanged(content, clearSdOnMismatch);
        }
        StructStat reopened = Os.fstat(current.getFileDescriptor());
        if (!sameFile(opened, reopened)) {
          logIdentityMismatch("reopened-stat", opened, reopened);
          throw savedPhotoChanged(content, clearSdOnMismatch);
        }
        StructStat held = Os.fstat(file.getFileDescriptor());
        if (!sameFile(opened, held)) {
          logIdentityMismatch("held-stat", opened, held);
          throw savedPhotoChanged(content, clearSdOnMismatch);
        }
      }
      if (!before.equals(CameraPhotoPublisher.savedRevision(context, uri, cancelled)))
        throw savedPhotoChanged(content, clearSdOnMismatch);
    } catch (ErrnoException statFailure) {
      throw new IOException("Saved photo identity could not be verified", statFailure);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
    SdSavedPhotoRevision proof = seedSd ? before.sdProof(content, context.getPackageName()) : null;
    if (proof != null) {
      beforeCommit.check();
      requireLibraryPermission();
      ledger.rememberSdRevision(content, proof);
    }
    return true;
  }

  private IOException savedPhotoChanged(
      CameraImportLedger.Content content, boolean clearSdOnMismatch) {
    if (clearSdOnMismatch) ledger.forgetSdRevision(content);
    return new IOException("Saved photo changed");
  }

  private static boolean sameFile(StructStat before, StructStat after) {
    return OsConstants.S_ISREG(after.st_mode)
        && before.st_dev == after.st_dev
        && before.st_ino == after.st_ino
        && before.st_size == after.st_size
        && before.st_mtime == after.st_mtime
        && before.st_ctime == after.st_ctime;
  }

  // Probe build only. Report differences without writing file identities or contents.
  private static void logIdentityMismatch(String branch, StructStat before, StructStat after) {
    Log.e(
        "LedgerIdentityProbe",
        "branch="
            + branch
            + " regularChanged="
            + (OsConstants.S_ISREG(before.st_mode) != OsConstants.S_ISREG(after.st_mode))
            + " devChanged="
            + (before.st_dev != after.st_dev)
            + " inodeChanged="
            + (before.st_ino != after.st_ino)
            + " sizeChanged="
            + (before.st_size != after.st_size)
            + " mtimeChanged="
            + (before.st_mtime != after.st_mtime)
            + " ctimeChanged="
            + (before.st_ctime != after.st_ctime));
  }

  public Result importPhoto(
      CameraImportFile source,
      CameraImportCopy.Copy copy,
      String folderHint,
      boolean stableSource,
      boolean verifyAll,
      BooleanSupplier cancelled)
      throws IOException {
    return importPhoto(
        source,
        copy,
        folderHint,
        stableSource,
        verifyAll,
        cancelled,
        CameraPhotoPublisher.NO_COMMIT_CHECK);
  }

  Result importPhoto(
      CameraImportFile source,
      CameraImportCopy.Copy copy,
      String folderHint,
      boolean stableSource,
      boolean verifyAll,
      BooleanSupplier cancelled,
      CameraPhotoPublisher.CommitCheck beforeCommit)
      throws IOException {
    return importPhoto(
        source, copy, folderHint, stableSource, verifyAll, cancelled, beforeCommit, new Attempt());
  }

  Result importPhoto(
      CameraImportFile source,
      CameraImportCopy.Copy copy,
      String folderHint,
      boolean stableSource,
      boolean verifyAll,
      BooleanSupplier cancelled,
      CameraPhotoPublisher.CommitCheck beforeCommit,
      Attempt attempt)
      throws IOException {
    return importPhoto(
        source, copy, folderHint, stableSource, verifyAll, cancelled, beforeCommit, attempt, false);
  }

  private void requireLibraryPermission() throws IOException {
    String read =
        android.os.Build.VERSION.SDK_INT >= 33
            ? android.Manifest.permission.READ_MEDIA_IMAGES
            : android.Manifest.permission.READ_EXTERNAL_STORAGE;
    if (context.checkSelfPermission(read) != android.content.pm.PackageManager.PERMISSION_GRANTED)
      throw new IOException("Full photo access required for duplicate verification");
  }

  Result importPhoto(
      CameraImportFile source,
      CameraImportCopy.Copy copy,
      String folderHint,
      boolean stableSource,
      boolean verifyAll,
      BooleanSupplier cancelled,
      CameraPhotoPublisher.CommitCheck beforeCommit,
      Attempt attempt,
      boolean sdProofs)
      throws IOException {
    CameraPhotoPublisher.check(cancelled);
    requireLibraryPermission();
    boolean seedSd = sdProofs && SdSavedPhotoRevision.eligibleSource(source);
    if (stableSource && !verifyAll && attempt.firstMissing == null) {
      CameraImportLedger.Content known = ledger.known(source);
      if (known != null && available(known, cancelled, seedSd, sdProofs, beforeCommit)) {
        beforeCommit.check();
        return new Result(Uri.parse(known.uri), false, sdProofs, 0);
      }
      attempt.firstMissing = known;
    }
    try (CameraImportCopy.Prepared prepared =
        CameraImportCopy.prepare(
            context.getCacheDir(),
            source,
            metrics == null ? copy : metrics.countSource(copy),
            cancelled)) {
      CameraImportLedger.Content existing = ledger.find(prepared.sourceSha256, source.bytes);
      CameraImportLedger.Content missing = null;
      CameraImportLedger.Content firstMissing = attempt.firstMissing;
      if (firstMissing != null) {
        if (!firstMissing.sourceSha.equals(prepared.sourceSha256))
          throw new SavedPhotoConflict("カード上の写真の内容が以前の取り込みと異なります。取り込み元を確認してください。");
        if (!firstMissing.sameRow(existing) || available(firstMissing, cancelled))
          throw new SavedPhotoConflict("以前の取り込み先が変わりました。保存先を確認してください。");
        missing = firstMissing;
        existing = null;
      } else if (existing != null
          && !available(existing, cancelled, seedSd, sdProofs, beforeCommit)) {
        missing = existing;
        attempt.firstMissing = existing;
        existing = null;
      }
      CameraImportLegacy.Evidence adopted =
          existing == null
              ? CameraImportLegacy.existing(
                  context,
                  source,
                  prepared.file,
                  cancelled,
                  n -> {
                    if (metrics != null) metrics.legacyBytes += n;
                  })
              : null;
      // Preserve the original digest; only this owned temporary copy may be repaired.
      ExifOffsetFix.INSTANCE.patchIfBroken(prepared.file);
      CameraImportLegacy.Metadata metadata =
          CameraImportLegacy.metadata(prepared.file, source.captureSeconds);
      CameraImportFile eventSource =
          new CameraImportFile(
              source.sourceId,
              source.relativePath,
              source.bytes,
              source.sourceStamp,
              metadata.getCaptureSeconds());
      String kind = MediaTypes.INSTANCE.isRaw(source.name) ? "RAW" : "JPEG";
      if (existing != null) {
        beforeCommit.check();
        ledger.bind(eventSource, existing);
        return new Result(Uri.parse(existing.uri), false, true, source.bytes);
      }
      String group = ledger.captureGroup(eventSource, prepared.sourceSha256, kind);
      if (adopted != null) {
        if (missing != null
            && CameraPhotoPublisher.savedPhotoExists(context, Uri.parse(missing.uri)))
          throw new IOException("Previous saved photo still exists");
        beforeCommit.check();
        CameraImportLedger.Content content =
            ledger.adopt(
                eventSource,
                prepared.sourceSha256,
                adopted.getSha256(),
                adopted.getBytes(),
                adopted.getUri(),
                kind,
                group,
                missing);
        return new Result(Uri.parse(content.uri), false, true, source.bytes);
      }
      String folder =
          folderHint == null || folderHint.isEmpty()
              ? (defaultFolder == null ? metadata.getModel() : defaultFolder)
              : folderHint;
      if (metrics != null) metrics.saving();
      Uri uri =
          publisher.publish(
              eventSource,
              prepared.file,
              prepared.sourceSha256,
              kind,
              CameraPhotoPublisher.folder(folder, kind.equals("RAW")),
              group,
              cancelled,
              beforeCommit,
              missing);
      return new Result(uri, true, true, source.bytes);
    }
  }
}
