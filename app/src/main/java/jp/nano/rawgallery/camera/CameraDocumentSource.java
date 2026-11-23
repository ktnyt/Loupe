package jp.nano.rawgallery.camera;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContentUris;
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
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Bounded read-only enumeration for an Android-mounted SD card's selected DCIM tree. */
public final class CameraDocumentSource {
    public static final class Entry {
        public final Uri uri;public final CameraImportFile file;
        Entry(Uri uri,CameraImportFile file){this.uri=uri;this.file=file;}
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
        enum Reason { LOADING, TIMEOUT, UNAVAILABLE, NO_GRANT }
        final Reason reason;

        ListingException(Reason reason) {
            super(switch (reason) {
                case LOADING -> "SDカードの一覧を読み込み中です。少し待ってから確認してください。";
                case TIMEOUT -> "SDカードの一覧を時間内に確認できませんでした。接続を確認してからやり直してください。";
                case UNAVAILABLE -> "SDカードの一覧を確認できません。接続とフォルダを確認してください。";
                case NO_GRANT -> "SDカードの読み取り許可を確認できません。フォルダを選び直してください。";
            });
            this.reason = reason;
        }
    }

    private static final long LISTING_TIMEOUT_MS = 30_000;
    // A wedged provider cannot tie up the import gate or accumulate queued listing requests.
    private static final ThreadPoolExecutor LISTINGS = listingPool("sd-document-listing");
    private static final ThreadPoolExecutor CANCELLATIONS = listingPool("sd-document-cancel");

    private static ThreadPoolExecutor listingPool(String name) {
        return new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
                work -> new Thread(work, name));
    }

    private final ContentResolver resolver;
    private final Uri tree;
    private final String source;
    private final BooleanSupplier cancelled;
    private final CancellationSignal signal;
    private int visited, rows;
    private boolean hasVideo;

    private CameraDocumentSource(ContentResolver resolver, Uri tree, String source, BooleanSupplier cancelled,
            CancellationSignal signal) {
        this.resolver = resolver;
        this.tree = tree;
        this.source = source;
        this.cancelled = cancelled;
        this.signal = signal;
    }
    public static Listing list(Context context,Uri tree,BooleanSupplier cancelled)throws IOException {
        CameraPhotoPublisher.check(cancelled);
        String identity = CameraSourceIdentity.sd(context, tree);
        return list(context.getContentResolver(), tree, identity, cancelled);
    }

    static Listing list(ContentResolver resolver, Uri tree, String identity, BooleanSupplier cancelled) throws IOException {
        return list(resolver, tree, identity, cancelled, LISTING_TIMEOUT_MS);
    }

    static Listing list(ContentResolver resolver, Uri tree, String identity, BooleanSupplier cancelled,
            long timeoutMs) throws IOException {
        CameraPhotoPublisher.check(cancelled);
        CancellationSignal signal = new CancellationSignal();
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        Future<Listing> pending;
        try {
            pending = LISTINGS.submit(() -> readListing(resolver, tree, identity, cancelled, signal));
        } catch (RejectedExecutionException stillBusy) {
            throw new ListingException(ListingException.Reason.LOADING);
        }
        try {
            while (true) {
                CameraPhotoPublisher.check(cancelled);
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) throw new ListingException(ListingException.Reason.TIMEOUT);
                try {
                    Listing listing = pending.get(Math.min(remaining, 100), TimeUnit.MILLISECONDS);
                    CameraPhotoPublisher.check(cancelled);
                    if (SystemClock.elapsedRealtime() >= deadline)
                        throw new ListingException(ListingException.Reason.TIMEOUT);
                    return listing;
                } catch (TimeoutException waitingForProvider) {
                    // Poll for cancellation without waiting for the provider to return.
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("SD card listing cancelled");
        } catch (ExecutionException failed) {
            CameraPhotoPublisher.check(cancelled);
            if (failed.getCause() instanceof IOException failure) throw failure;
            if (failed.getCause() instanceof Error error) throw error;
            throw unavailable();
        } finally {
            if (pending.cancel(true)) {
                try { CANCELLATIONS.execute(signal::cancel); }
                catch (RejectedExecutionException stillCancelling) {
                    // Future cancellation already prevents publication, even when the provider is wedged.
                }
            }
        }
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
        } catch (IllegalArgumentException invalid) { throw unavailable(); }
    }

    private static Listing readListing(ContentResolver resolver, Uri tree, String identity,
            BooleanSupplier cancelled, CancellationSignal signal) throws IOException {
        String path = treePath(tree);
        CameraDocumentSource reader = new CameraDocumentSource(resolver, tree,
                originalSourceId(identity == null ? "saf:" + tree : identity), cancelled, signal);
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
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        try (Cursor cursor = resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
                projection, null, null, null, signal)) {
            requireCompleteListing(cursor);
            for (String column : projection) if (cursor.getColumnIndex(column) < 0) throw unavailable();
            while (cursor.moveToNext()) {
                CameraPhotoPublisher.check(cancelled);
                if (++rows > 20000) throw unavailable();
                String name = cursor.getString(1);
                if (name == null || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals(".."))
                    throw unavailable();
                String child = cursor.getString(0);
                if (child == null || child.isEmpty()) throw unavailable();
                String relative = path.isEmpty() ? name : path + "/" + name;
                String mime = cursor.getString(2);
                if (mime == null || mime.isBlank()) throw unavailable();
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (!path.isEmpty() || name.equalsIgnoreCase("DCIM")) scan(child, relative, files, depth + 1);
                } else if (!path.isEmpty() && MediaTypes.INSTANCE.kindOf(name) != null) {
                    if (MediaTypes.INSTANCE.isVideo(name)) {
                        hasVideo = true;
                        continue;
                    }
                    long size = cursor.getLong(3), modified = cursor.getLong(4);
                    if (files.size() >= 20000 || size <= 0 || size > Ep7WifiClient.MAX_FILE_BYTES) throw unavailable();
                    files.add(new Entry(DocumentsContract.buildDocumentUriUsingTree(tree, child),
                            new CameraImportFile(source, relative, size, Long.toString(modified), modified / 1000)));
                }
            }
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
    static String originalSourceId(String previousSourceId) { return "original-byte-v1:"+previousSourceId; }
    public static final class OriginalAccessException extends IOException {
        public enum Reason { PERMISSION, MAPPING, INDEX_PENDING, VOLUME, UNAVAILABLE, LENGTH }
        public final Reason reason;
        private OriginalAccessException(Reason reason) {
            super(switch (reason) {
                case PERMISSION -> "写真の原本を取り込むには、写真の位置情報へのアクセスを許可してください";
                case VOLUME -> "取り込み元のカードで写真の原本を確認できません。カードとDCIMフォルダを確認してください";
                case INDEX_PENDING -> "SDカードに写真があります。Androidへの登録を待ってから、もう一度取り込んでください。";
                default -> "写真の原本を取得できません。SDカードのDCIMフォルダを選び直し、写真へのアクセスを確認してください";
            });
            this.reason=reason;
        }
    }
    public static void requireOriginalPermission(Context context)throws OriginalAccessException {
        try{
            if(context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)!=PackageManager.PERMISSION_GRANTED)
                throw new OriginalAccessException(OriginalAccessException.Reason.PERMISSION);
        }catch(SecurityException denied){throw new OriginalAccessException(OriginalAccessException.Reason.PERMISSION);}
    }
    /** Minimal read seam: production and synthetic checks share permission, mapping and streaming logic. */
    interface OriginalAccess {
        boolean permitted();
        Uri mediaUri(Uri document)throws IOException;
        default Uri onVolume(Uri media, CameraImportFile file, String expectedVolume)throws IOException { return null; }
        default boolean canCheckRegistration(Uri tree, String volume)throws IOException { return false; }
        default Cursor documentRegistration(Uri document)throws IOException { return null; }
        default Cursor mediaRegistration(String volume, String folder, String name)throws IOException { return null; }
        InputStream openOriginal(Uri requiredOriginal)throws IOException;
    }
    public static void copy(Context context,Entry entry,OutputStream out,BooleanSupplier cancelled)throws IOException {
        copy(context,entry,out,cancelled,null);
    }
    static void copy(Context context,Entry entry,OutputStream out,BooleanSupplier cancelled,String expectedVolume)
            throws IOException {
        copy(systemAccess(context),entry,out,cancelled,expectedVolume);
    }

    static void requireMappedOriginal(Context context, Entry entry, String expectedVolume, BooleanSupplier cancelled)
            throws IOException {
        requiredOriginal(systemAccess(context), entry, expectedVolume, cancelled);
    }

    private static OriginalAccess systemAccess(Context context) {
        return new OriginalAccess(){
            public boolean permitted(){return context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)==PackageManager.PERMISSION_GRANTED;}
            public Uri mediaUri(Uri document){return MediaStore.getMediaUri(context,document);}
            public Uri onVolume(Uri media,CameraImportFile file,String expected) {
                return onMountedVolume(context,media,file,expected);
            }
            public boolean canCheckRegistration(Uri tree,String volume)throws IOException {
                String read = Build.VERSION.SDK_INT >= 33
                        ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
                if (!permitted() || context.checkSelfPermission(read) != PackageManager.PERMISSION_GRANTED
                        || !MediaStore.getExternalVolumeNames(context).contains(volume)) return false;
                MountedCameraCard.requireGrant(context,tree);
                return true;
            }
            public Cursor documentRegistration(Uri document) {
                return context.getContentResolver().query(document,
                        new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE},
                        null,null,null);
            }
            public Cursor mediaRegistration(String volume,String folder,String name) {
                Bundle query = new Bundle();
                query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                        MediaStore.MediaColumns.RELATIVE_PATH+"=? AND "+MediaStore.MediaColumns.DISPLAY_NAME+"=?");
                query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,new String[]{folder,name});
                query.putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE);
                query.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED,MediaStore.MATCH_INCLUDE);
                return context.getContentResolver().query(MediaStore.Files.getContentUri(volume),
                        new String[]{MediaStore.MediaColumns._ID},query,null);
            }
            public InputStream openOriginal(Uri original)throws IOException {
                android.os.ParcelFileDescriptor descriptor=context.getContentResolver().openFileDescriptor(original,"r");
                return descriptor==null?null:new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            }
        };
    }

    private static Uri onMountedVolume(Context context, Uri mapped, CameraImportFile file, String expected) {
        if (!MediaStore.getExternalVolumeNames(context).contains(expected)) return null;
        String mappedVolume = MediaStore.getVolumeName(mapped);
        if (!MediaStore.VOLUME_EXTERNAL.equals(mappedVolume) && !expected.equals(mappedVolume)) return null;
        long id = ContentUris.parseId(mapped);
        if (id < 0) return null;
        Uri exact = MediaStore.Files.getContentUri(expected, id);
        String sourceFolder = file.relativePath.substring(0, file.relativePath.lastIndexOf('/') + 1);
        try (Cursor row = context.getContentResolver().query(mapped,
                new String[]{MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE}, null, null, null)) {
            if (row == null || row.getCount() != 1 || !row.moveToFirst() || !expected.equals(row.getString(0))
                    || !sourceFolder.equals(row.getString(1)) || !file.name.equals(row.getString(2))
                    || file.bytes != row.getLong(3)) return null;
        }
        return exact;
    }

    /** Check whether this exact document has any visible rows on the source volume. */
    private static boolean confirmedMissingRegistration(OriginalAccess access, Entry entry, String volume) {
        Uri document = entry.uri;
        if (!"content".equals(document.getScheme()) || !"com.android.externalstorage.documents".equals(document.getAuthority())
                || MediaStore.VOLUME_EXTERNAL.equals(volume) || MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(volume)) return false;
        try {
            List<String> segments = document.getPathSegments();
            if (segments.size() != 4 || !"tree".equals(segments.get(0)) || !"document".equals(segments.get(2)))
                return false;
            String treeId = DocumentsContract.getTreeDocumentId(document);
            String documentId = DocumentsContract.getDocumentId(document);
            int split = treeId.indexOf(':');
            int documentSplit = documentId.indexOf(':');
            if (split <= 0 || documentSplit <= 0 || !volume.equalsIgnoreCase(treeId.substring(0,split))
                    || !volume.equalsIgnoreCase(documentId.substring(0,documentSplit))) return false;
            String root = treeId.substring(split+1);
            String path = entry.file.relativePath;
            if ((!root.equalsIgnoreCase("DCIM") && !root.regionMatches(true,0,"DCIM/",0,5))
                    || !path.startsWith(root+"/") || !path.equals(documentId.substring(documentSplit+1))) return false;

            Uri tree = DocumentsContract.buildTreeDocumentUri(document.getAuthority(),treeId);
            if (!access.canCheckRegistration(tree,volume)) return false;
            try (Cursor row = access.documentRegistration(document)) {
                if (!complete(row) || row.getCount() != 1 || !row.moveToFirst()) return false;
                int id = row.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int name = row.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int size = row.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE);
                if (!documentId.equals(row.getString(id)) || !entry.file.name.equals(row.getString(name))
                        || row.isNull(size) || row.getLong(size) != entry.file.bytes) return false;
            }
            String folder = path.substring(0,path.lastIndexOf('/')+1);
            try (Cursor rows = access.mediaRegistration(volume,folder,entry.file.name)) {
                return complete(rows) && rows.getColumnIndex(MediaStore.MediaColumns._ID) >= 0
                        && rows.getCount() == 0 && access.canCheckRegistration(tree,volume);
            }
        } catch (IOException | RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean complete(Cursor cursor) {
        if (cursor == null) return false;
        Bundle extras = cursor.getExtras();
        return extras != null && !extras.getBoolean(DocumentsContract.EXTRA_LOADING)
                && !extras.containsKey(DocumentsContract.EXTRA_ERROR);
    }

    private static void requireOriginalPermission(OriginalAccess access)throws OriginalAccessException {
        try{if(!access.permitted())throw new OriginalAccessException(OriginalAccessException.Reason.PERMISSION);}
        catch(SecurityException denied){throw new OriginalAccessException(OriginalAccessException.Reason.PERMISSION);}
    }
    static void copy(OriginalAccess access,Entry entry,OutputStream out,BooleanSupplier cancelled)throws IOException {
        copy(access,entry,out,cancelled,null);
    }
    private static Uri requiredOriginal(OriginalAccess access,Entry entry,String expectedVolume,BooleanSupplier cancelled)
            throws IOException {
        CameraPhotoPublisher.check(cancelled);
        requireOriginalPermission(access);
        Uri media;
        try{media=access.mediaUri(entry.uri);}
        catch(SecurityException denied){throw new OriginalAccessException(OriginalAccessException.Reason.PERMISSION);}
        catch(IllegalArgumentException rejected) {
            // MediaProvider can report an unregistered document this way. Check for visible rows separately.
            media=null;
        }
        catch(IOException|RuntimeException unavailable){throw new OriginalAccessException(OriginalAccessException.Reason.MAPPING);}
        if(media==null||!"content".equals(media.getScheme())||!"media".equals(media.getAuthority())) {
            if(expectedVolume!=null) {
                boolean missing=confirmedMissingRegistration(access,entry,expectedVolume);
                CameraPhotoPublisher.check(cancelled);
                if(missing) {
                    requireOriginalPermission(access);
                    throw new OriginalAccessException(OriginalAccessException.Reason.INDEX_PENDING);
                }
            }
            throw new OriginalAccessException(OriginalAccessException.Reason.MAPPING);
        }
        if(expectedVolume!=null) {
            try { media=access.onVolume(media,entry.file,expectedVolume); }
            catch(SecurityException denied) { throw new OriginalAccessException(OriginalAccessException.Reason.PERMISSION); }
            catch(IOException|RuntimeException unavailable) { throw new OriginalAccessException(OriginalAccessException.Reason.VOLUME); }
            if(media==null||!"content".equals(media.getScheme())||!"media".equals(media.getAuthority())
                    ||!expectedVolume.equals(MediaStore.getVolumeName(media)))
                throw new OriginalAccessException(OriginalAccessException.Reason.VOLUME);
        }
        CameraPhotoPublisher.check(cancelled);
        requireOriginalPermission(access);
        return MediaStore.setRequireOriginal(media);
    }

    static void copy(OriginalAccess access,Entry entry,OutputStream out,BooleanSupplier cancelled,String expectedVolume)
            throws IOException {
        Uri original=requiredOriginal(access,entry,expectedVolume,cancelled);
        InputStream input;
        try{input=access.openOriginal(original);}
        catch(SecurityException denied){throw new OriginalAccessException(OriginalAccessException.Reason.PERMISSION);}
        catch(IOException|RuntimeException unavailable){throw new OriginalAccessException(OriginalAccessException.Reason.UNAVAILABLE);}
        if(input==null)throw new OriginalAccessException(OriginalAccessException.Reason.UNAVAILABLE);
        byte[] buffer=new byte[65536];
        boolean completed=false;
        try{
            InputStream in=input;
            long bytes=0;
            while(true){
                CameraPhotoPublisher.check(cancelled);requireOriginalPermission(access);
                int count;
                try{count=in.read(buffer,0,(int)Math.min(buffer.length,entry.file.bytes-bytes+1));}
                catch(SecurityException denied){throw new OriginalAccessException(OriginalAccessException.Reason.PERMISSION);}
                catch(IOException|RuntimeException unavailable){throw new OriginalAccessException(OriginalAccessException.Reason.UNAVAILABLE);}
                CameraPhotoPublisher.check(cancelled);requireOriginalPermission(access);
                if(count<0)break;
                bytes+=count;
                if(bytes>entry.file.bytes||bytes>Ep7WifiClient.MAX_FILE_BYTES)
                    throw new OriginalAccessException(OriginalAccessException.Reason.LENGTH);
                out.write(buffer,0,count);
            }
            if(bytes!=entry.file.bytes)throw new OriginalAccessException(OriginalAccessException.Reason.LENGTH);
            completed=true;
        }catch(InterruptedIOException cancelledRead){throw cancelledRead;}
        catch(OriginalAccessException closed){throw closed;}
        catch(SecurityException denied){throw new OriginalAccessException(OriginalAccessException.Reason.PERMISSION);}
        finally{
            Arrays.fill(buffer,(byte)0);
            try{input.close();}
            catch(IOException|RuntimeException closeFailed){if(completed)throw new OriginalAccessException(OriginalAccessException.Reason.UNAVAILABLE);}
        }
    }
}
