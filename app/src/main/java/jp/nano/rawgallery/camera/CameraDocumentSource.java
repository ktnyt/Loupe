package jp.nano.rawgallery.camera;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Bounded read-only enumeration for an Android-mounted SD card's selected DCIM tree. */
public final class CameraDocumentSource {
  public static final class Entry {
    public final Uri uri;
    public final CameraImportFile file;

    Entry(Uri uri, CameraImportFile file) {
      this.uri = uri;
      this.file = file;
    }
  }

  public static final class Listing {
    public final List<Entry> photos;
    public final boolean stable;
    final boolean hasVideo;

    Listing(List<Entry> photos, boolean stable, boolean hasVideo) {
      this.photos = Collections.unmodifiableList(photos);
      this.stable = stable;
      this.hasVideo = hasVideo;
    }
  }

  static final class ListingException extends IOException {
    enum Reason {
      LOADING,
      TIMEOUT,
      UNAVAILABLE,
      NO_GRANT,
      CHANGED,
      INCONSISTENT
    }

    final Reason reason;

    ListingException(Reason reason) {
      super(
          switch (reason) {
            case LOADING -> "SDカードの一覧を読み込み中です。少し待ってから確認してください。";
            case TIMEOUT -> "SDカードの一覧を時間内に確認できませんでした。接続を確認してからやり直してください。";
            case UNAVAILABLE -> "SDカードの一覧を確認できません。接続とフォルダを確認してください。";
            case NO_GRANT -> "SDカードの読み取り許可を確認できません。フォルダを選び直してください。";
            case CHANGED -> "SDカード上の写真が変わりました。もう一度取り込みを開始してください。";
            case INCONSISTENT -> "SDカードに写真がありますが、一覧を確認できません。取り込み元のカードを接続し直してください。";
          });
      this.reason = reason;
    }
  }

  private static final long LISTING_TIMEOUT_MS = 30_000;
  private static final long METADATA_TIMEOUT_MS = 3_000;
  private static final long METADATA_HANDOFF_MS = 100;
  private static final long MOUNTED_LISTING_RETRY_MS = 1000;
  private static final int MOUNTED_LISTING_HINT_LIMIT = 6;
  private static final String EXTERNAL_STORAGE_DOCUMENTS = "com.android.externalstorage.documents";
  // A wedged provider cannot tie up the import gate or accumulate queued listing requests.
  private static final ThreadPoolExecutor METADATA = metadataPool("sd-document-metadata");
  private static final ThreadPoolExecutor CANCELLATIONS = metadataPool("sd-document-cancel");

  private static ThreadPoolExecutor metadataPool(String name) {
    return new ThreadPoolExecutor(
        0, 2, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), work -> new Thread(work, name));
  }

  private final ContentResolver resolver;
  private final Uri tree;
  private final String source;
  private final BooleanSupplier cancelled;
  private final CancellationSignal signal;
  private int visited, rows;
  private boolean hasVideo;

  private CameraDocumentSource(
      ContentResolver resolver,
      Uri tree,
      String source,
      BooleanSupplier cancelled,
      CancellationSignal signal) {
    this.resolver = resolver;
    this.tree = tree;
    this.source = source;
    this.cancelled = cancelled;
    this.signal = signal;
  }

  public static Listing list(Context context, Uri tree, BooleanSupplier cancelled)
      throws IOException {
    return boundedMetadata(
        cancelled,
        LISTING_TIMEOUT_MS,
        signal ->
            readListing(
                context.getContentResolver(),
                tree,
                CameraSourceIdentity.sd(context, tree),
                cancelled,
                signal));
  }

  static Listing listMounted(
      Context context,
      Uri tree,
      String volume,
      BooleanSupplier cancelled,
      CameraPhotoPublisher.CommitCheck requireFreshSource)
      throws IOException {
    return listMounted(
        context.getContentResolver(),
        tree,
        () -> CameraSourceIdentity.sd(context, tree),
        volume,
        cancelled,
        requireFreshSource,
        (source, folder, signal) ->
            queryPhotoHints(
                context.getContentResolver(),
                MediaStore.getExternalVolumeNames(context),
                source,
                folder,
                signal),
        MOUNTED_LISTING_RETRY_MS);
  }

  @FunctionalInterface
  interface MountedPhotoRows {
    Cursor query(String volume, String folder, CancellationSignal signal) throws IOException;
  }

  static Listing listMounted(
      ContentResolver resolver,
      Uri tree,
      Supplier<String> identity,
      String volume,
      BooleanSupplier cancelled,
      CameraPhotoPublisher.CommitCheck requireFreshSource,
      MountedPhotoRows photoRows,
      long retryDelayMs)
      throws IOException {
    return boundedMetadata(
        cancelled,
        LISTING_TIMEOUT_MS,
        signal -> {
          String sourceId = identity.get();
          boolean mountedExternal =
              volume != null
                  && tree != null
                  && DocumentsContract.isTreeUri(tree)
                  && EXTERNAL_STORAGE_DOCUMENTS.equals(tree.getAuthority())
                  && DocumentsContract.getTreeDocumentId(tree)
                      .regionMatches(true, 0, volume + ":", 0, volume.length() + 1);
          try {
            Listing first = readListing(resolver, tree, sourceId, cancelled, signal);
            if (!first.photos.isEmpty() || first.hasVideo || !mountedExternal) return first;
          } catch (ListingException failure) {
            if (!mountedExternal || failure.reason != ListingException.Reason.LOADING)
              throw failure;
          }
          requireFreshSource.check();
          long retryAt = SystemClock.elapsedRealtime() + retryDelayMs;
          while (SystemClock.elapsedRealtime() < retryAt) {
            CameraPhotoPublisher.check(cancelled);
            try {
              Thread.sleep(Math.max(1, Math.min(100, retryAt - SystemClock.elapsedRealtime())));
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new InterruptedIOException("SD card listing cancelled");
            }
          }
          requireFreshSource.check();
          Listing retry = readListing(resolver, tree, sourceId, cancelled, signal);
          if (retry.photos.isEmpty() && !retry.hasVideo) {
            requireFreshSource.check();
            boolean contradicted =
                hasUnlistedMountedPhoto(resolver, tree, volume, photoRows, cancelled, signal);
            requireFreshSource.check();
            if (contradicted) throw new ListingException(ListingException.Reason.INCONSISTENT);
          }
          return retry;
        });
  }

  static Cursor queryPhotoHints(
      ContentResolver resolver,
      Set<String> indexedVolumes,
      String volume,
      String folder,
      CancellationSignal signal)
      throws IOException {
    if (!indexedVolumes.contains(volume))
      throw new ListingException(ListingException.Reason.LOADING);
    Bundle args = new Bundle();
    args.putString(
        ContentResolver.QUERY_ARG_SQL_SELECTION,
        MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? ESCAPE '!'");
    String prefix = folder.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[] {prefix + "%"});
    args.putInt(ContentResolver.QUERY_ARG_LIMIT, MOUNTED_LISTING_HINT_LIMIT);
    return resolver.query(
        MediaStore.Images.Media.getContentUri(volume),
        new String[] {
          MediaStore.MediaColumns.VOLUME_NAME,
          MediaStore.MediaColumns.RELATIVE_PATH,
          MediaStore.MediaColumns.DISPLAY_NAME
        },
        args,
        signal);
  }

  private static boolean hasUnlistedMountedPhoto(
      ContentResolver resolver,
      Uri tree,
      String volume,
      MountedPhotoRows photoRows,
      BooleanSupplier cancelled,
      CancellationSignal signal)
      throws IOException {
    String treeId = DocumentsContract.getTreeDocumentId(tree);
    int separator = treeId.indexOf(':');
    String selectedPath = treePath(tree);
    String prefix = selectedPath.isEmpty() ? "DCIM/" : selectedPath + "/";

    Set<String> paths = new LinkedHashSet<>();
    try (Cursor rows = photoRows.query(volume, prefix, signal)) {
      requireCompleteListing(rows);
      int source = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME);
      int folder = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH);
      int name = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
      for (int i = 0; i < MOUNTED_LISTING_HINT_LIMIT && rows.moveToNext(); i++) {
        CameraPhotoPublisher.check(cancelled);
        if (!volume.equals(rows.getString(source))) continue;
        String directory = rows.getString(folder);
        String filename = rows.getString(name);
        if (directory == null
            || !directory.endsWith("/")
            || filename == null
            || filename.isBlank()
            || filename.contains("/")
            || filename.contains("\\")) continue;
        boolean inTree =
            selectedPath.isEmpty()
                ? directory.regionMatches(true, 0, prefix, 0, prefix.length())
                : directory.startsWith(prefix);
        if (!inTree
            || MediaTypes.INSTANCE.kindOf(filename) == null
            || MediaTypes.INSTANCE.isVideo(filename)) continue;
        String path = directory + filename;
        try {
          new CameraImportFile("path-check", path, 1, "", 0);
        } catch (IllegalArgumentException invalid) {
          continue;
        }
        paths.add(path);
      }
      requireCompleteListing(rows);
    }

    for (String path : paths) {
      CameraPhotoPublisher.check(cancelled);
      String id = treeId.substring(0, separator + 1) + path;
      Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, id);
      if (isCurrentPhoto(resolver, document, id, path.substring(path.lastIndexOf('/') + 1), signal))
        return true;
    }
    return false;
  }

  private static boolean isCurrentPhoto(
      ContentResolver resolver, Uri document, String id, String name, CancellationSignal signal)
      throws IOException {
    String[] fields = {
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
      DocumentsContract.Document.COLUMN_SIZE
    };
    try (Cursor row = resolver.query(document, fields, null, null, null, signal)) {
      if (row == null) return false;
      requireCompleteListing(row);
      if (!singleCompleteRow(row)) return false;
      int size = row.getColumnIndexOrThrow(fields[3]);
      String mime = row.getString(row.getColumnIndexOrThrow(fields[2]));
      return id.equals(row.getString(row.getColumnIndexOrThrow(fields[0])))
          && name.equals(row.getString(row.getColumnIndexOrThrow(fields[1])))
          && mime != null
          && !mime.isBlank()
          && !DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)
          && row.getType(size) == Cursor.FIELD_TYPE_INTEGER
          && row.getLong(size) > 0
          && row.getLong(size) <= Ep7WifiClient.MAX_FILE_BYTES
          && complete(row);
    } catch (SecurityException denied) {
      throw new ListingException(ListingException.Reason.NO_GRANT);
    }
  }

  static Listing list(
      ContentResolver resolver, Uri tree, String identity, BooleanSupplier cancelled)
      throws IOException {
    return list(resolver, tree, identity, cancelled, LISTING_TIMEOUT_MS);
  }

  static Listing list(
      ContentResolver resolver,
      Uri tree,
      String identity,
      BooleanSupplier cancelled,
      long timeoutMs)
      throws IOException {
    return boundedMetadata(
        cancelled, timeoutMs, signal -> readListing(resolver, tree, identity, cancelled, signal));
  }

  interface MetadataQuery<T> {
    T query(CancellationSignal signal) throws IOException;
  }

  static <T> T boundedMetadata(BooleanSupplier cancelled, MetadataQuery<T> query)
      throws IOException {
    return boundedMetadata(cancelled, LISTING_TIMEOUT_MS, query);
  }

  static <T> T boundedFileMetadata(BooleanSupplier cancelled, MetadataQuery<T> query)
      throws IOException {
    return boundedMetadata(cancelled, METADATA_TIMEOUT_MS, query);
  }

  private static <T> T boundedMetadata(
      BooleanSupplier cancelled, long timeoutMs, MetadataQuery<T> query) throws IOException {
    return boundedMetadata(METADATA, cancelled, timeoutMs, query);
  }

  static <T> T boundedMetadata(
      ThreadPoolExecutor metadata,
      BooleanSupplier cancelled,
      long timeoutMs,
      MetadataQuery<T> query)
      throws IOException {
    CameraPhotoPublisher.check(cancelled);
    CancellationSignal signal = new CancellationSignal();
    long deadline = SystemClock.elapsedRealtime() + timeoutMs;
    FutureTask<T> pending =
        new FutureTask<>(
            () -> {
              CameraPhotoPublisher.check(cancelled);
              if (SystemClock.elapsedRealtime() >= deadline)
                throw new ListingException(ListingException.Reason.TIMEOUT);
              return query.query(signal);
            });
    boolean submitted = false;
    try {
      try {
        metadata.execute(pending);
      } catch (RejectedExecutionException notWaitingYet) {
        CameraPhotoPublisher.check(cancelled);
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new ListingException(ListingException.Reason.TIMEOUT);
        // A completed worker may not be waiting for work yet. This synchronous queue keeps no work.
        if (!metadata
            .getQueue()
            .offer(pending, Math.min(remaining, METADATA_HANDOFF_MS), TimeUnit.MILLISECONDS)) {
          CameraPhotoPublisher.check(cancelled);
          throw new ListingException(
              SystemClock.elapsedRealtime() >= deadline
                  ? ListingException.Reason.TIMEOUT
                  : ListingException.Reason.LOADING);
        }
      }
      submitted = true;
      while (true) {
        CameraPhotoPublisher.check(cancelled);
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new ListingException(ListingException.Reason.TIMEOUT);
        try {
          T result = pending.get(Math.min(remaining, 100), TimeUnit.MILLISECONDS);
          CameraPhotoPublisher.check(cancelled);
          if (SystemClock.elapsedRealtime() >= deadline)
            throw new ListingException(ListingException.Reason.TIMEOUT);
          return result;
        } catch (TimeoutException waitingForProvider) {
          // Poll for cancellation without waiting for the provider to return.
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("SD card listing cancelled");
    } catch (ExecutionException failed) {
      CameraPhotoPublisher.check(cancelled);
      if (failed.getCause() instanceof android.os.OperationCanceledException)
        throw new InterruptedIOException("SD card metadata cancelled");
      if (failed.getCause() instanceof IOException failure) throw failure;
      if (failed.getCause() instanceof Error error) throw error;
      throw unavailable();
    } finally {
      if (pending.cancel(true) && submitted) {
        try {
          CANCELLATIONS.execute(signal::cancel);
        } catch (RejectedExecutionException stillCancelling) {
          // Future cancellation already prevents publication, even when the provider is wedged.
        }
      }
    }
  }

  public static boolean isReachableDirectory(
      ContentResolver resolver, Uri tree, BooleanSupplier cancelled) throws IOException {
    return isReachableDirectory(resolver, tree, cancelled, METADATA_TIMEOUT_MS);
  }

  static boolean isReachableDirectory(
      ContentResolver resolver, Uri tree, BooleanSupplier cancelled, long timeout)
      throws IOException {
    return boundedMetadata(
        cancelled,
        timeout,
        signal -> {
          Uri root =
              DocumentsContract.buildDocumentUriUsingTree(
                  tree, DocumentsContract.getTreeDocumentId(tree));
          try (Cursor cursor =
              resolver.query(
                  root,
                  new String[] {DocumentsContract.Document.COLUMN_MIME_TYPE},
                  null,
                  null,
                  null,
                  signal)) {
            if (cursor == null) return false;
            Bundle extras = cursor.getExtras();
            if (extras != null
                && !extras.containsKey(DocumentsContract.EXTRA_ERROR)
                && extras.getBoolean(DocumentsContract.EXTRA_LOADING))
              throw new ListingException(ListingException.Reason.LOADING);
            if (!singleCompleteRow(cursor)) return false;
            int mime = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            return mime >= 0
                && DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(mime))
                && complete(cursor);
          }
        });
  }

  public static boolean isImportTree(Uri tree, boolean allowVolumeRoot) {
    try {
      return !treePath(tree).isEmpty() || allowVolumeRoot;
    } catch (ListingException invalid) {
      return false;
    }
  }

  private static String treePath(Uri tree) throws ListingException {
    if (tree == null || !DocumentsContract.isTreeUri(tree)) throw unavailable();
    try {
      String root = DocumentsContract.getTreeDocumentId(tree);
      String path = root.contains(":") ? root.substring(root.indexOf(':') + 1) : "";
      if (!path.isEmpty()) {
        if (!path.equalsIgnoreCase("DCIM") && !path.regionMatches(true, 0, "DCIM/", 0, 5))
          throw unavailable();
        new CameraImportFile("path-check", path + "/check.JPG", 1, "", 0);
      }
      return path;
    } catch (IllegalArgumentException invalid) {
      throw unavailable();
    }
  }

  private static Listing readListing(
      ContentResolver resolver,
      Uri tree,
      String identity,
      BooleanSupplier cancelled,
      CancellationSignal signal)
      throws IOException {
    String path = treePath(tree);
    CameraDocumentSource reader =
        new CameraDocumentSource(
            resolver,
            tree,
            originalSourceId(identity == null ? "saf:" + tree : identity),
            cancelled,
            signal);
    List<Entry> files = new ArrayList<>();
    reader.scan(DocumentsContract.getTreeDocumentId(tree), path, files, 0);
    CameraPhotoPublisher.check(cancelled);
    return new Listing(files, identity != null, reader.hasVideo);
  }

  private static ListingException unavailable() {
    return new ListingException(ListingException.Reason.UNAVAILABLE);
  }

  private static void requireCompleteListing(Cursor cursor) throws ListingException {
    if (cursor == null) throw unavailable();
    Bundle extras = cursor.getExtras();
    if (extras == null || extras.containsKey(DocumentsContract.EXTRA_ERROR)) throw unavailable();
    if (extras.getBoolean(DocumentsContract.EXTRA_LOADING))
      throw new ListingException(ListingException.Reason.LOADING);
  }

  private void scan(String id, String path, List<Entry> files, int depth) throws IOException {
    CameraPhotoPublisher.check(cancelled);
    if (depth > 5 || ++visited > 1000) throw unavailable();
    String[] projection = {
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
      DocumentsContract.Document.COLUMN_SIZE,
      DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };
    try (Cursor cursor =
        resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
            projection,
            null,
            null,
            null,
            signal)) {
      requireCompleteListing(cursor);
      int documentId = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
      int displayName =
          cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
      int mimeType = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
      int fileSize = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE);
      int lastModified =
          cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
      int count = cursor.getCount(), visitedRows = 0;
      while (cursor.moveToNext()) {
        CameraPhotoPublisher.check(cancelled);
        if (++rows > 20000 || ++visitedRows > count) throw unavailable();
        String name = cursor.getString(displayName);
        if (name == null
            || name.isBlank()
            || name.contains("/")
            || name.contains("\\")
            || name.equals(".")
            || name.equals("..")) throw unavailable();
        String child = cursor.getString(documentId);
        if (child == null || child.isEmpty()) throw unavailable();
        String relative = path.isEmpty() ? name : path + "/" + name;
        String mime = cursor.getString(mimeType);
        if (mime == null || mime.isBlank()) throw unavailable();
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
          if (!path.isEmpty() || name.equalsIgnoreCase("DCIM"))
            scan(child, relative, files, depth + 1);
        } else if (!path.isEmpty() && MediaTypes.INSTANCE.kindOf(name) != null) {
          if (MediaTypes.INSTANCE.isVideo(name)) {
            hasVideo = true;
            continue;
          }
          long size = cursor.getLong(fileSize), modified = cursor.getLong(lastModified);
          if (files.size() >= 20000 || size <= 0 || size > Ep7WifiClient.MAX_FILE_BYTES)
            throw unavailable();
          files.add(
              new Entry(
                  DocumentsContract.buildDocumentUriUsingTree(tree, child),
                  new CameraImportFile(
                      source, relative, size, Long.toString(modified), modified / 1000)));
        }
      }
      if (visitedRows != count || cursor.getCount() != count) throw unavailable();
      requireCompleteListing(cursor);
    } catch (android.os.OperationCanceledException stopped) {
      throw new InterruptedIOException("SD card listing cancelled");
    } catch (SecurityException denied) {
      throw new ListingException(ListingException.Reason.NO_GRANT);
    } catch (RuntimeException invalid) {
      throw unavailable();
    }
  }

  /** SAF receipts from potentially redacted reads cannot authorize an original-byte shortcut. */
  static String originalSourceId(String previousSourceId) {
    return "original-byte-v1:" + previousSourceId;
  }

  public static final class OriginalAccessException extends IOException {
    public enum Reason {
      PERMISSION,
      MAPPING,
      INDEX_PENDING,
      VOLUME,
      UNAVAILABLE,
      LENGTH
    }

    public final Reason reason;

    private OriginalAccessException(Reason reason) {
      super(
          switch (reason) {
            case PERMISSION -> "写真のオリジナルを取り込むには、写真の位置情報へのアクセスを許可してください";
            case VOLUME -> "取り込み元のカードで写真のオリジナルを確認できません。カードとDCIMフォルダを確認してください";
            case INDEX_PENDING -> "SDカードに写真があります。Androidへの登録を待ってから、もう一度取り込んでください。";
            default -> "写真のオリジナルを取得できません。SDカードのDCIMフォルダを選び直し、写真へのアクセスを確認してください";
          });
      this.reason = reason;
    }
  }

  public static void requireOriginalPermission(Context context) throws OriginalAccessException {
    try {
      if (context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)
          != PackageManager.PERMISSION_GRANTED)
        throw new OriginalAccessException(OriginalAccessException.Reason.PERMISSION);
    } catch (SecurityException denied) {
      throw new OriginalAccessException(OriginalAccessException.Reason.PERMISSION);
    }
  }

  /**
   * Minimal read seam: production and synthetic checks share permission, mapping and streaming
   * logic.
   */
  interface OriginalAccess {
    boolean permitted();

    Uri mediaUri(Uri document) throws IOException;

    default Uri onVolume(
        Uri media, CameraImportFile file, String expectedVolume, CancellationSignal signal)
        throws IOException {
      return null;
    }

    default boolean canCheckRegistration(Uri tree, String volume) throws IOException {
      return false;
    }

    default Cursor documentRegistration(Uri document, CancellationSignal signal)
        throws IOException {
      return null;
    }

    default Cursor mediaRegistration(
        String volume, String folder, String name, CancellationSignal signal) throws IOException {
      return null;
    }

    InputStream openOriginal(Uri requiredOriginal) throws IOException;
  }

  public static void copy(Context context, Entry entry, OutputStream out, BooleanSupplier cancelled)
      throws IOException {
    copy(context, entry, out, cancelled, null);
  }

  static void copy(
      Context context,
      Entry entry,
      OutputStream out,
      BooleanSupplier cancelled,
      String expectedVolume)
      throws IOException {
    copy(systemAccess(context), entry, out, cancelled, expectedVolume);
  }

  static void requireMappedOriginal(
      Context context, Entry entry, String expectedVolume, BooleanSupplier cancelled)
      throws IOException {
    requiredOriginal(systemAccess(context), entry, expectedVolume, cancelled);
  }

  static void requireCurrentReceiptDocument(
      ContentResolver resolver, Entry entry, String sourceVolume, BooleanSupplier cancelled)
      throws IOException {
    requireCurrentDocument(resolver, entry, sourceVolume, cancelled, false);
  }

  static void requireCurrentScanDocument(
      ContentResolver resolver, Entry entry, String sourceVolume, BooleanSupplier cancelled)
      throws IOException {
    requireCurrentDocument(resolver, entry, sourceVolume, cancelled, true);
  }

  private static void requireCurrentDocument(
      ContentResolver resolver,
      Entry entry,
      String sourceVolume,
      BooleanSupplier cancelled,
      boolean requireFileType)
      throws IOException {
    String documentId = DocumentsContract.getDocumentId(entry.uri);
    int split = documentId.indexOf(':');
    if (!"com.android.externalstorage.documents".equals(entry.uri.getAuthority())
        || split <= 0
        || !sourceVolume.equalsIgnoreCase(documentId.substring(0, split))
        || !entry.file.relativePath.equals(documentId.substring(split + 1)))
      throw new ListingException(ListingException.Reason.CHANGED);
    boundedFileMetadata(
        cancelled,
        signal -> {
          String[] fields = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
          };
          if (requireFileType) {
            fields = Arrays.copyOf(fields, fields.length + 1);
            fields[4] = DocumentsContract.Document.COLUMN_MIME_TYPE;
          }
          try (Cursor row = resolver.query(entry.uri, fields, null, null, null, signal)) {
            requireCompleteListing(row);
            if (!singleCompleteRow(row))
              throw new ListingException(ListingException.Reason.CHANGED);
            int id = row.getColumnIndexOrThrow(fields[0]);
            int name = row.getColumnIndexOrThrow(fields[1]);
            int size = row.getColumnIndexOrThrow(fields[2]);
            int time = row.getColumnIndexOrThrow(fields[3]);
            if (!documentId.equals(row.getString(id))
                || !entry.file.name.equals(row.getString(name))
                || row.getType(size) != Cursor.FIELD_TYPE_INTEGER
                || row.getLong(size) != entry.file.bytes
                || row.getType(time) != Cursor.FIELD_TYPE_INTEGER
                || !entry.file.sourceStamp.equals(Long.toString(row.getLong(time))))
              throw new ListingException(ListingException.Reason.CHANGED);
            if (requireFileType) {
              String mime =
                  row.getString(
                      row.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE));
              if (mime == null
                  || mime.isEmpty()
                  || DocumentsContract.Document.MIME_TYPE_DIR.equals(mime))
                throw new ListingException(ListingException.Reason.CHANGED);
            }
            requireCompleteListing(row);
          } catch (SecurityException denied) {
            throw new ListingException(ListingException.Reason.NO_GRANT);
          }
          return null;
        });
  }

  private static OriginalAccess systemAccess(Context context) {
    return new OriginalAccess() {
      public boolean permitted() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
      }

      public Uri mediaUri(Uri document) {
        return MediaStore.getMediaUri(context, document);
      }

      public Uri onVolume(
          Uri media, CameraImportFile file, String expected, CancellationSignal signal) {
        return onMountedVolume(context, media, file, expected, signal);
      }

      public boolean canCheckRegistration(Uri tree, String volume) throws IOException {
        String read =
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (!permitted()
            || context.checkSelfPermission(read) != PackageManager.PERMISSION_GRANTED
            || !MediaStore.getExternalVolumeNames(context).contains(volume)) return false;
        MountedCameraCard.requireGrant(context, tree);
        return true;
      }

      public Cursor documentRegistration(Uri document, CancellationSignal signal) {
        return context
            .getContentResolver()
            .query(
                document,
                new String[] {
                  DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                  DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                  DocumentsContract.Document.COLUMN_SIZE
                },
                null,
                null,
                null,
                signal);
      }

      public Cursor mediaRegistration(
          String volume, String folder, String name, CancellationSignal signal) {
        Bundle query = new Bundle();
        query.putString(
            ContentResolver.QUERY_ARG_SQL_SELECTION,
            MediaStore.MediaColumns.RELATIVE_PATH
                + "=? AND "
                + MediaStore.MediaColumns.DISPLAY_NAME
                + "=?");
        query.putStringArray(
            ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[] {folder, name});
        query.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        query.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
        return context
            .getContentResolver()
            .query(
                MediaStore.Files.getContentUri(volume),
                new String[] {MediaStore.MediaColumns._ID},
                query,
                signal);
      }

      public InputStream openOriginal(Uri original) throws IOException {
        android.os.ParcelFileDescriptor descriptor =
            context.getContentResolver().openFileDescriptor(original, "r");
        return descriptor == null
            ? null
            : new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor);
      }
    };
  }

  private static Uri onMountedVolume(
      Context context,
      Uri mapped,
      CameraImportFile file,
      String expected,
      CancellationSignal signal) {
    if (!MediaStore.getExternalVolumeNames(context).contains(expected)) return null;
    String mappedVolume = MediaStore.getVolumeName(mapped);
    if (!MediaStore.VOLUME_EXTERNAL.equals(mappedVolume) && !expected.equals(mappedVolume))
      return null;
    long id = ContentUris.parseId(mapped);
    if (id < 0) return null;
    Uri exact = MediaStore.Files.getContentUri(expected, id);
    try (Cursor row =
        context
            .getContentResolver()
            .query(
                mapped,
                new String[] {
                  MediaStore.MediaColumns._ID,
                  MediaStore.MediaColumns.VOLUME_NAME,
                  MediaStore.MediaColumns.RELATIVE_PATH,
                  MediaStore.MediaColumns.DISPLAY_NAME,
                  MediaStore.MediaColumns.SIZE
                },
                null,
                null,
                null,
                signal)) {
      return matchesMountedOriginal(row, expected, id, file) ? exact : null;
    }
  }

  static boolean matchesMountedOriginal(Cursor row, String volume, long id, CameraImportFile file) {
    if (!singleCompleteRow(row)) return false;
    int mediaId = row.getColumnIndex(MediaStore.MediaColumns._ID);
    int mediaVolume = row.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME);
    int path = row.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
    int name = row.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
    int size = row.getColumnIndex(MediaStore.MediaColumns.SIZE);
    if (mediaId < 0 || mediaVolume < 0 || path < 0 || name < 0 || size < 0) return false;
    String folder = file.relativePath.substring(0, file.relativePath.lastIndexOf('/') + 1);
    return row.getType(mediaId) == Cursor.FIELD_TYPE_INTEGER
        && row.getLong(mediaId) == id
        && volume.equals(row.getString(mediaVolume))
        && folder.equals(row.getString(path))
        && file.name.equals(row.getString(name))
        && row.getType(size) == Cursor.FIELD_TYPE_INTEGER
        && file.bytes == row.getLong(size)
        && complete(row);
  }

  /** Check whether this exact document has any visible rows on the source volume. */
  private static boolean confirmedMissingRegistration(
      OriginalAccess access, Entry entry, String volume, CancellationSignal signal) {
    Uri document = entry.uri;
    if (!"content".equals(document.getScheme())
        || !"com.android.externalstorage.documents".equals(document.getAuthority())
        || MediaStore.VOLUME_EXTERNAL.equals(volume)
        || MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(volume)) return false;
    try {
      List<String> segments = document.getPathSegments();
      if (segments.size() != 4
          || !"tree".equals(segments.get(0))
          || !"document".equals(segments.get(2))) return false;
      String treeId = DocumentsContract.getTreeDocumentId(document);
      String documentId = DocumentsContract.getDocumentId(document);
      int split = treeId.indexOf(':');
      int documentSplit = documentId.indexOf(':');
      if (split <= 0
          || documentSplit <= 0
          || !volume.equalsIgnoreCase(treeId.substring(0, split))
          || !volume.equalsIgnoreCase(documentId.substring(0, documentSplit))) return false;
      String root = treeId.substring(split + 1);
      String path = entry.file.relativePath;
      if ((!root.equalsIgnoreCase("DCIM") && !root.regionMatches(true, 0, "DCIM/", 0, 5))
          || !path.startsWith(root + "/")
          || !path.equals(documentId.substring(documentSplit + 1))) return false;

      Uri tree = DocumentsContract.buildTreeDocumentUri(document.getAuthority(), treeId);
      if (!access.canCheckRegistration(tree, volume)) return false;
      try (Cursor row = access.documentRegistration(document, signal)) {
        if (!singleCompleteRow(row)) return false;
        int id = row.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
        int name = row.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
        int size = row.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE);
        if (!documentId.equals(row.getString(id))
            || !entry.file.name.equals(row.getString(name))
            || row.getType(size) != Cursor.FIELD_TYPE_INTEGER
            || row.getLong(size) != entry.file.bytes
            || !complete(row)) return false;
      }
      String folder = path.substring(0, path.lastIndexOf('/') + 1);
      try (Cursor rows = access.mediaRegistration(volume, folder, entry.file.name, signal)) {
        return complete(rows)
            && rows.getColumnIndex(MediaStore.MediaColumns._ID) >= 0
            && rows.getCount() == 0
            && !rows.moveToFirst()
            && rows.getCount() == 0
            && complete(rows)
            && access.canCheckRegistration(tree, volume);
      }
    } catch (IOException | RuntimeException unavailable) {
      return false;
    }
  }

  private static boolean singleCompleteRow(Cursor cursor) {
    return complete(cursor)
        && cursor.getCount() == 1
        && cursor.moveToFirst()
        && cursor.getCount() == 1
        && complete(cursor);
  }

  private static boolean complete(Cursor cursor) {
    if (cursor == null) return false;
    Bundle extras = cursor.getExtras();
    return extras != null
        && !extras.getBoolean(DocumentsContract.EXTRA_LOADING)
        && !extras.containsKey(DocumentsContract.EXTRA_ERROR);
  }

  private static void requireOriginalPermission(OriginalAccess access)
      throws OriginalAccessException {
    try {
      if (!access.permitted())
        throw new OriginalAccessException(OriginalAccessException.Reason.PERMISSION);
    } catch (SecurityException denied) {
      throw new OriginalAccessException(OriginalAccessException.Reason.PERMISSION);
    }
  }

  static void copy(OriginalAccess access, Entry entry, OutputStream out, BooleanSupplier cancelled)
      throws IOException {
    copy(access, entry, out, cancelled, null);
  }

  private static Uri requiredOriginal(
      OriginalAccess access, Entry entry, String expectedVolume, BooleanSupplier cancelled)
      throws IOException {
    Uri original =
        boundedMetadata(
            cancelled,
            METADATA_TIMEOUT_MS,
            signal -> resolveOriginal(access, entry, expectedVolume, cancelled, signal));
    CameraPhotoPublisher.check(cancelled);
    requireOriginalPermission(access);
    return original;
  }

  private static Uri resolveOriginal(
      OriginalAccess access,
      Entry entry,
      String expectedVolume,
      BooleanSupplier cancelled,
      CancellationSignal signal)
      throws IOException {
    CameraPhotoPublisher.check(cancelled);
    requireOriginalPermission(access);
    Uri media;
    try {
      media = access.mediaUri(entry.uri);
    } catch (SecurityException denied) {
      throw new OriginalAccessException(OriginalAccessException.Reason.PERMISSION);
    } catch (IllegalArgumentException rejected) {
      // MediaProvider can report an unregistered document this way. Check for visible rows
      // separately.
      media = null;
    } catch (IOException | RuntimeException unavailable) {
      throw new OriginalAccessException(OriginalAccessException.Reason.MAPPING);
    }
    if (media == null
        || !"content".equals(media.getScheme())
        || !"media".equals(media.getAuthority())) {
      if (expectedVolume != null) {
        boolean missing = confirmedMissingRegistration(access, entry, expectedVolume, signal);
        CameraPhotoPublisher.check(cancelled);
        if (missing) {
          requireOriginalPermission(access);
          throw new OriginalAccessException(OriginalAccessException.Reason.INDEX_PENDING);
        }
      }
      throw new OriginalAccessException(OriginalAccessException.Reason.MAPPING);
    }
    if (expectedVolume != null) {
      try {
        media = access.onVolume(media, entry.file, expectedVolume, signal);
      } catch (SecurityException denied) {
        throw new OriginalAccessException(OriginalAccessException.Reason.PERMISSION);
      } catch (IOException | RuntimeException unavailable) {
        throw new OriginalAccessException(OriginalAccessException.Reason.VOLUME);
      }
      if (media == null
          || !"content".equals(media.getScheme())
          || !"media".equals(media.getAuthority())
          || !expectedVolume.equals(MediaStore.getVolumeName(media)))
        throw new OriginalAccessException(OriginalAccessException.Reason.VOLUME);
    }
    CameraPhotoPublisher.check(cancelled);
    requireOriginalPermission(access);
    return MediaStore.setRequireOriginal(media);
  }

  static void copy(
      OriginalAccess access,
      Entry entry,
      OutputStream out,
      BooleanSupplier cancelled,
      String expectedVolume)
      throws IOException {
    Uri original = requiredOriginal(access, entry, expectedVolume, cancelled);
    InputStream input;
    try {
      input = access.openOriginal(original);
    } catch (SecurityException denied) {
      throw new OriginalAccessException(OriginalAccessException.Reason.PERMISSION);
    } catch (IOException | RuntimeException unavailable) {
      throw new OriginalAccessException(OriginalAccessException.Reason.UNAVAILABLE);
    }
    if (input == null)
      throw new OriginalAccessException(OriginalAccessException.Reason.UNAVAILABLE);
    byte[] buffer = new byte[65536];
    boolean completed = false;
    try {
      InputStream in = input;
      long bytes = 0;
      while (true) {
        CameraPhotoPublisher.check(cancelled);
        requireOriginalPermission(access);
        int count;
        try {
          count = in.read(buffer, 0, (int) Math.min(buffer.length, entry.file.bytes - bytes + 1));
        } catch (SecurityException denied) {
          throw new OriginalAccessException(OriginalAccessException.Reason.PERMISSION);
        } catch (IOException | RuntimeException unavailable) {
          throw new OriginalAccessException(OriginalAccessException.Reason.UNAVAILABLE);
        }
        CameraPhotoPublisher.check(cancelled);
        requireOriginalPermission(access);
        if (count < 0) break;
        bytes += count;
        if (bytes > entry.file.bytes || bytes > Ep7WifiClient.MAX_FILE_BYTES)
          throw new OriginalAccessException(OriginalAccessException.Reason.LENGTH);
        out.write(buffer, 0, count);
      }
      if (bytes != entry.file.bytes)
        throw new OriginalAccessException(OriginalAccessException.Reason.LENGTH);
      completed = true;
    } catch (InterruptedIOException cancelledRead) {
      throw cancelledRead;
    } catch (OriginalAccessException closed) {
      throw closed;
    } catch (SecurityException denied) {
      throw new OriginalAccessException(OriginalAccessException.Reason.PERMISSION);
    } finally {
      Arrays.fill(buffer, (byte) 0);
      try {
        input.close();
      } catch (IOException | RuntimeException closeFailed) {
        if (completed)
          throw new OriginalAccessException(OriginalAccessException.Reason.UNAVAILABLE);
      }
    }
  }
}
