package jp.nano.rawgallery.camera;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.DocumentsContract;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import jp.nano.rawgallery.PhotoStorage;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** MediaStore + SQLite publication saga; owned pending rows are recoverable at every boundary. */
public final class CameraPhotoPublisher {
    public enum Boundary { ALLOCATED, URI_INSERTED, URI_RECORDED, FILE_VERIFIED, PUBLISHED, RECEIPT_SAVED }
    public interface Faults { void after(Boundary boundary); }
    private final Context context;
    private final ContentResolver resolver;
    private final CameraImportLedger ledger;
    private final Faults faults;
    @FunctionalInterface
    interface MetadataQuery { Cursor query(Uri uri, String[] columns, Bundle args); }
    private final MetadataQuery metadataQuery;
    private final String volume;
    private java.util.function.LongConsumer readCounter=n->{};
    public void setReadCounter(java.util.function.LongConsumer counter){this.readCounter=counter;}
    public CameraPhotoPublisher(Context context,CameraImportLedger ledger) { this(context,ledger,b -> {}); }
    public CameraPhotoPublisher(Context context,CameraImportLedger ledger,Faults faults) {
        this(context,ledger,PhotoStorage.selected(context),faults);
    }
    CameraPhotoPublisher(Context context, CameraImportLedger ledger, String volume, Faults faults) {
        this(context, ledger, volume, faults, metadata(context.getApplicationContext().getContentResolver()));
    }

    private static MetadataQuery metadata(ContentResolver resolver) {
        return (uri, columns, args) -> resolver.query(uri, columns, args, null);
    }

    CameraPhotoPublisher(Context context, CameraImportLedger ledger, String volume, Faults faults,
            MetadataQuery metadataQuery) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.ledger = ledger;
        this.volume = volume;
        this.faults = faults;
        this.metadataQuery = metadataQuery;
    }
    static void check(BooleanSupplier cancelled)throws IOException { if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Import cancelled"); }
    public static String folder(String value,boolean raw) {
        String clean=value==null?"Camera":value.trim().replaceAll("[/\\\\\\p{Cntrl}]","_");
        if(clean.isEmpty()||clean.equals(".")||clean.equals(".."))clean="Camera";
        if(clean.length()>80)clean=clean.substring(0,80);
        return "Pictures/CameraSync/"+clean+(raw?"-RAW":"")+"/";
    }
    @FunctionalInterface
    interface CommitCheck { void check() throws IOException; }
    static final CommitCheck NO_COMMIT_CHECK = () -> {};

    public Uri publish(CameraImportFile source,File staged,String originalSha,String kind,String folder,String group,BooleanSupplier cancelled)throws IOException {
        return publish(source, staged, originalSha, kind, folder, group, cancelled, NO_COMMIT_CHECK);
    }

    Uri publish(CameraImportFile source, File staged, String originalSha, String kind, String folder, String group,
            BooleanSupplier cancelled, CommitCheck beforeCommit) throws IOException {
        return publish(source, staged, originalSha, kind, folder, group, cancelled, beforeCommit, null);
    }

    Uri publish(CameraImportFile source, File staged, String originalSha, String kind, String folder, String group,
            BooleanSupplier cancelled, CommitCheck beforeCommit, CameraImportLedger.Content replacement) throws IOException {
        check(cancelled);
        PhotoStorage.requireSpace(context,volume,staged.length());
        String savedSha=CameraImportCopy.sha256(staged,cancelled);
        CameraImportLedger.Pending p = ledger.allocate(source, originalSha, savedSha, staged.length(),
                source.name, folder, kind, group, volume, replacement);
        faults.after(Boundary.ALLOCATED);
        try {
            ContentValues values=new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME,p.pendingName);
            values.put(MediaStore.MediaColumns.MIME_TYPE,MediaTypes.INSTANCE.mimeOf(source.name));
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,p.relativeFolder);
            if(source.captureSeconds>0)values.put(MediaStore.MediaColumns.DATE_TAKEN,source.captureSeconds*1000);
            values.put(MediaStore.MediaColumns.IS_PENDING,1);
            Uri target=resolver.insert(MediaStore.Images.Media.getContentUri(p.volume),values);
            if(target==null)throw new IOException("Photo insert failed");
            faults.after(Boundary.URI_INSERTED);
            ledger.attach(p.token,target.toString());p=ledger.pending(p.token);
            Revision allocated = new Revision(requirePublicationRow(p, target, true), epoch(p.volume));
            faults.after(Boundary.URI_RECORDED);
            ParcelFileDescriptor fd = openPendingForWrite(p, target, allocated, cancelled);
            try(InputStream input=new FileInputStream(staged); ParcelFileDescriptor.AutoCloseOutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {

                    byte[] buf=new byte[65536];int n;long total=0;
                    while((n=input.read(buf))!=-1) {check(cancelled);total+=n;if(total>p.storedBytes)throw new IOException("Staged photo changed");out.write(buf,0,n);}
                    if(total!=p.storedBytes)throw new IOException("Staged photo is incomplete");
                    out.getFD().sync();
            }
            check(cancelled);
            verify(p, target, true, cancelled);
            requirePublicationContent(p);
            // READY is the durable admission to publication. A refused source check must leave
            // the row unready so recovery can only discard it, never publish it later.
            beforeCommit.check();
            ledger.ready(p.token);p=ledger.pending(p.token);
            faults.after(Boundary.FILE_VERIFIED);
            // An admitted publication must finish or recover with its receipt, even after a stop.
            completePublication(p, target, () -> false);
            return target;
        } catch(IOException|RuntimeException failure) {
            CameraImportLedger.Pending current=ledger.pending(p.token);
            if(current!=null&&!current.ready) {
                try {
                    discardUnpublished(current);
                } catch (CameraPhotoImporter.SavedPhotoConflict conflict) {
                    conflict.addSuppressed(failure);
                    throw conflict;
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }
    private record State(boolean pending, boolean trashed, String owner, String name, String folder,
            long generationAdded, long generationModified) {}
    private record Revision(State row, String epoch) {}

    private static void requireComplete(Cursor cursor) throws IOException {
        if (cursor == null) throw new IOException("Photo status could not be read");
        Bundle extras = cursor.getExtras();
        if (extras == null || extras.getBoolean(DocumentsContract.EXTRA_LOADING)
                || extras.containsKey(DocumentsContract.EXTRA_ERROR))
            throw new IOException("Photo status is incomplete");
    }

    private static void requireColumns(Cursor cursor, String[] columns) throws IOException {
        requireComplete(cursor);
        for (String column : columns)
            if (cursor.getColumnIndex(column) < 0) throw new IOException("Photo status lacks required metadata");
    }

    /** The only authoritative absence is a complete, consistent zero-row result. */
    static boolean moveToOnlyMediaRow(Cursor cursor) throws IOException {
        requireComplete(cursor);
        int count = cursor.getCount();
        if (count != 0 && count != 1) throw new IOException("Photo status is ambiguous");
        boolean hasRow = cursor.moveToFirst();
        if (count != cursor.getCount() || hasRow != (count == 1))
            throw new IOException("Photo status changed during lookup");
        requireComplete(cursor);
        return hasRow;
    }

    private static long id(Cursor row) throws IOException {
        return nonnegative(row, MediaStore.MediaColumns._ID);
    }

    private static long nonnegative(Cursor row, String field) throws IOException {
        int column = row.getColumnIndexOrThrow(field);
        if (row.getType(column) != Cursor.FIELD_TYPE_INTEGER || row.getLong(column) < 0)
            throw new IOException("Unexpected photo identity");
        return row.getLong(column);
    }

    private static boolean flag(Cursor row, String name) throws IOException {
        int column = row.getColumnIndexOrThrow(name);
        if (row.getType(column) != Cursor.FIELD_TYPE_INTEGER) throw new IOException("Photo status flag is invalid");
        int value = row.getInt(column);
        if (value != 0 && value != 1)
            throw new IOException("Photo status flag is invalid");
        return value != 0;
    }

    static boolean savedPhotoExists(Context context, Uri uri) throws IOException {
        return savedPhotoExists(context, uri, metadata(context.getContentResolver()));
    }

    static boolean savedPhotoExists(Context context, Uri uri, MetadataQuery query) throws IOException {
        if (!"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority()))
            throw new IOException("Unexpected saved photo URI");
        PhotoStorage.requireMounted(context, uri);
        long expectedId = ContentUris.parseId(uri);
        String[] fields = { MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.IS_TRASHED };
        try (Cursor row = query.query(uri, fields, includeAllPhotos())) {
            requireColumns(row, fields);
            if (!moveToOnlyMediaRow(row)) {
                PhotoStorage.requireMounted(context, uri);
                if (MediaStore.VOLUME_EXTERNAL.equals(MediaStore.getVolumeName(uri))
                        && !MediaStore.getExternalVolumeNames(context)
                                .containsAll(MediaStore.getRecentExternalVolumeNames(context)))
                    throw new IOException("Saved photo volume unavailable");
                requireComplete(row);
                return false;
            }
            if (id(row) != expectedId || flag(row, MediaStore.MediaColumns.IS_PENDING)
                    || flag(row, MediaStore.MediaColumns.IS_TRASHED))
                throw new IOException("Saved photo identity or status changed");
            requireComplete(row);
            return true;
        }
    }

    record SavedRevision(String volume, String epoch, String owner, String name, String folder, long size,
            long generationAdded, long generationModified) {
        SdSavedPhotoRevision sdProof(CameraImportLedger.Content content, String expectedOwner) {
            Uri uri = Uri.parse(content.uri);
            if (!volume.equals(MediaStore.getVolumeName(uri)) || !expectedOwner.equals(owner)
                    || size != content.storedBytes || !CameraImportHistoryPlan.validUri(content.uri)) return null;
            return new SdSavedPhotoRevision(content.uri, content.storedSha, size, epoch, generationAdded, generationModified);
        }
    }

    static SavedRevision savedRevision(Context context, Uri uri, BooleanSupplier cancelled) throws IOException {
        if (!"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority()))
            throw new IOException("Unexpected saved photo URI");
        return CameraDocumentSource.boundedMetadata(cancelled, signal -> {
            PhotoStorage.requireMounted(context, uri);
            String collection = MediaStore.getVolumeName(uri);
            String[] fields = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING,
                    MediaStore.MediaColumns.IS_TRASHED, MediaStore.MediaColumns.VOLUME_NAME,
                    MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.GENERATION_MODIFIED};
            try (Cursor row = context.getContentResolver().query(uri, fields, includeAllPhotos(), signal)) {
                requireColumns(row, fields);
                if (!moveToOnlyMediaRow(row)) {
                    PhotoStorage.requireMounted(context, uri);
                    if (MediaStore.VOLUME_EXTERNAL.equals(collection) && !MediaStore.getExternalVolumeNames(context)
                            .containsAll(MediaStore.getRecentExternalVolumeNames(context)))
                        throw new IOException("Saved photo volume unavailable");
                    requireComplete(row);
                    return null;
                }
                String volume = row.getString(3);
                if (id(row) != ContentUris.parseId(uri) || flag(row, fields[1]) || flag(row, fields[2])
                        || volume == null || !MediaStore.getExternalVolumeNames(context).contains(volume)
                        || !MediaStore.VOLUME_EXTERNAL.equals(collection) && !collection.equals(volume))
                    throw new IOException("Saved photo identity or status changed");
                String epoch = MediaStore.getVersion(context, volume);
                long added = nonnegative(row, fields[8]);
                long modified = nonnegative(row, fields[9]);
                if (epoch == null || epoch.isEmpty() || modified < added)
                    throw new IOException("Saved photo revision is unavailable");
                SavedRevision revision = new SavedRevision(volume, epoch, row.getString(4), row.getString(5),
                        row.getString(6), nonnegative(row, fields[7]), added, modified);
                requireComplete(row);
                return revision;
            }
        });
    }

    private static Bundle includeAllPhotos() {
        Bundle args = new Bundle();
        args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
        return args;
    }

    private State state(Uri uri) throws IOException {
        if (!CameraImportHistoryPlan.validUri(uri.toString())) throw new IOException("Unexpected photo URI");
        PhotoStorage.requireMounted(context, uri);
        String[] fields = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.IS_TRASHED, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.GENERATION_MODIFIED};
        try (Cursor row = metadataQuery.query(uri, fields, includeAllPhotos())) {
            requireColumns(row, fields);
            if (!moveToOnlyMediaRow(row)) {
                PhotoStorage.requireMounted(context, uri);
                requireComplete(row);
                return null;
            }
            if (id(row) != ContentUris.parseId(uri)) throw new IOException("Unexpected photo identity");
            State result = new State(flag(row, MediaStore.MediaColumns.IS_PENDING),
                    flag(row, MediaStore.MediaColumns.IS_TRASHED),
                    row.getString(row.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)),
                    row.getString(row.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)),
                    row.getString(row.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)),
                    nonnegative(row, MediaStore.MediaColumns.GENERATION_ADDED),
                    nonnegative(row, MediaStore.MediaColumns.GENERATION_MODIFIED));
            requireComplete(row);
            return result;
        }
    }

    private void owned(State state) throws IOException {
        if (state == null || state.trashed() || !context.getPackageName().equals(state.owner()))
            throw new IOException("Publication ownership or status could not be verified");
    }

    private Uri locate(CameraImportLedger.Pending p) throws IOException {
        Uri collection = MediaStore.Images.Media.getContentUri(p.volume);
        PhotoStorage.requireMounted(context, collection);
        if (p.uri != null) {
            Uri attached = Uri.parse(p.uri);
            if (!CameraImportHistoryPlan.validUri(p.uri) || !p.volume.equals(PhotoStorage.volumeOf(attached)))
                throw new IOException("Pending photo volume changed");
            return attached;
        }
        Bundle args = includeAllPhotos();
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                + MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=?");
        args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{p.pendingName, p.relativeFolder, context.getPackageName()});
        String[] fields = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED};
        try (Cursor row = metadataQuery.query(collection, fields, args)) {
            requireColumns(row, fields);
            if (!moveToOnlyMediaRow(row)) {
                PhotoStorage.requireMounted(context, collection);
                requireComplete(row);
                return null;
            }
            if (!p.pendingName.equals(row.getString(row.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)))
                    || !p.relativeFolder.equals(row.getString(row.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)))
                    || !context.getPackageName().equals(row.getString(row.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)))
                    || !flag(row, MediaStore.MediaColumns.IS_PENDING) || flag(row, MediaStore.MediaColumns.IS_TRASHED))
                throw new IOException("Pending photo identity or status changed");
            Uri uri = ContentUris.withAppendedId(collection, id(row));
            requireComplete(row);
            ledger.attach(p.token, uri.toString());
            return uri;
        }
    }

    private String epoch(String volume) throws IOException {
        String version = MediaStore.getVersion(context, volume);
        if (version == null || version.isEmpty()) throw new IOException("Photo volume revision is unavailable");
        return version;
    }

    private State requirePublicationRow(CameraImportLedger.Pending p, Uri uri, boolean pending) throws IOException {
        State row = state(uri);
        owned(row);
        if (row.pending() != pending || !p.relativeFolder.equals(row.folder())
                || row.generationModified() < row.generationAdded()
                || pending && !p.pendingName.equals(row.name()))
            throw new IOException("Publication target identity or status changed");
        return row;
    }

    private ParcelFileDescriptor openPendingForWrite(CameraImportLedger.Pending p, Uri target, Revision expected,
            BooleanSupplier cancelled) throws IOException {
        requirePendingRevision(p, target, expected);
        // Open without truncation. Match the write descriptor to the current readable inode before
        // mutating it, since a replacement or external publication can race the URI open.
        ParcelFileDescriptor writable = resolver.openFileDescriptor(target, "rw");
        if (writable == null) throw new IOException("Photo output could not be opened");
        boolean accepted = false;
        try {
            try (ParcelFileDescriptor readable = resolver.openFileDescriptor(target, "r")) {
                if (readable == null) throw new IOException("Pending photo could not be checked");
                var opened = Os.fstat(writable.getFileDescriptor());
                var current = Os.fstat(readable.getFileDescriptor());
                if (!OsConstants.S_ISREG(opened.st_mode) || !OsConstants.S_ISREG(current.st_mode)
                        || opened.st_dev != current.st_dev || opened.st_ino != current.st_ino)
                    throw new IOException("Pending photo file changed");
                requirePendingRevision(p, target, expected);
                check(cancelled);
                Os.ftruncate(writable.getFileDescriptor(), 0);
            }
            accepted = true;
            return writable;
        } catch (ErrnoException invalid) {
            throw new IOException("Pending photo output could not be checked", invalid);
        } finally {
            if (!accepted) writable.close();
        }
    }

    private void requirePendingRevision(CameraImportLedger.Pending p, Uri uri, Revision expected) throws IOException {
        if (!expected.row().equals(requirePublicationRow(p, uri, true)) || !expected.epoch().equals(epoch(p.volume)))
            throw new IOException("Pending photo changed before writing");
    }

    private Revision verify(CameraImportLedger.Pending p, Uri uri, boolean pending, BooleanSupplier cancelled)
            throws IOException {
        String version = epoch(p.volume);
        State before = requirePublicationRow(p, uri, pending);
        try(InputStream in=resolver.openInputStream(uri)) {
            if(in==null)throw new IOException("Saved photo could not be read");
            MessageDigest sha=MessageDigest.getInstance("SHA-256");byte[] buf=new byte[65536];int n;long count=0;
            while((n=in.read(buf))!=-1){check(cancelled);count+=n;readCounter.accept(n);if(count>p.storedBytes)throw new IOException("Saved photo length mismatch");sha.update(buf,0,n);}
            if(count!=p.storedBytes||!hex(sha.digest()).equals(p.storedSha))throw new IOException("Saved photo fingerprint mismatch");
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        State after = requirePublicationRow(p, uri, pending);
        if (!before.equals(after) || !version.equals(epoch(p.volume)))
            throw new IOException("Publication target changed during verification");
        return new Revision(after, version);
    }

    private Bundle exactPending(State row) {
        Bundle exact = includeAllPhotos();
        exact.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=? AND " + MediaStore.MediaColumns.IS_PENDING
                        + "=1 AND " + MediaStore.MediaColumns.IS_TRASHED + "=0 AND "
                        + MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH
                        + "=? AND " + MediaStore.MediaColumns.GENERATION_ADDED + "=? AND "
                        + MediaStore.MediaColumns.GENERATION_MODIFIED + "=?");
        exact.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{row.owner(), row.name(), row.folder(), Long.toString(row.generationAdded()),
                        Long.toString(row.generationModified())});
        return exact;
    }

    private void completePublication(CameraImportLedger.Pending p, Uri uri, BooleanSupplier cancelled) throws IOException {
        State current = state(uri);
        owned(current);
        Revision verifiedPending = null;
        if (current.pending()) {
            requirePublicationContent(p);
            verifiedPending = verify(p, uri, true, cancelled);
            if (!verifiedPending.epoch().equals(epoch(p.volume)))
                throw new IOException("Publication target volume changed");
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, p.finalName);
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            int updated;
            try {
                updated = resolver.update(uri, values, exactPending(verifiedPending.row()));
            } catch (SecurityException refused) {
                throw new IOException("Photo publish failed", refused);
            }
            if (updated != 1)
                throw new IOException("Photo publish failed");
            faults.after(Boundary.PUBLISHED);
        }
        requirePublicationContent(p);
        Revision published = verify(p, uri, false, cancelled);
        if (verifiedPending != null && (!published.epoch().equals(verifiedPending.epoch())
                || published.row().generationAdded() != verifiedPending.row().generationAdded()))
            throw new IOException("Published photo identity changed");
        requirePublicationContent(p);
        if (!published.row().equals(requirePublicationRow(p, uri, false))
                || !published.epoch().equals(epoch(p.volume)))
            throw new IOException("Published photo changed before receipt");
        ledger.commit(p.token);
        faults.after(Boundary.RECEIPT_SAVED);
    }
    private void discardUnpublished(CameraImportLedger.Pending p)throws IOException {
        Uri uri=locate(p);
        State state = uri == null ? null : state(uri);
        // Keep the journal as the original comparison witness if the saved row changes.
        // Discarding it would let a later attempt select a different missing destination.
        requirePublicationContent(p, uri == null ? p.uri : uri.toString());
        if (state != null) {
            owned(state);
            if (!state.pending()) throw new IOException("Published photo needs receipt recovery");
            if (!p.pendingName.equals(state.name()) || !p.relativeFolder.equals(state.folder()))
                throw new IOException("Pending photo identity changed before cleanup");
            int deleted;
            try {
                deleted = resolver.delete(uri, exactPending(state));
            } catch (SecurityException refused) {
                throw new IOException("Incomplete photo cleanup failed", refused);
            }
            if (deleted != 1 || state(uri) != null)
                throw new IOException("Incomplete photo cleanup failed");
        }
        PhotoStorage.requireMounted(context,MediaStore.Images.Media.getContentUri(p.volume));
        ledger.discard(p.token);
    }
    private void requirePublicationContent(CameraImportLedger.Pending p) throws IOException {
        requirePublicationContent(p, p.uri);
    }

    private void requirePublicationContent(CameraImportLedger.Pending p, String targetUri) throws IOException {
        if (p.replacement != null) {
            requireReplacementWitness(p);
            return;
        }
        // A migrated journal without a witness can finish a new or idempotent publication.
        // It cannot authorize replacing a different destination selected after allocation.
        CameraImportLedger.Content current = ledger.find(p.sourceSha, p.source.bytes);
        if (current != null && (!current.uri.equals(targetUri) || !p.storedSha.equals(current.storedSha)
                || p.storedBytes != current.storedBytes))
            throw new CameraPhotoImporter.SavedPhotoConflict("以前の取り込み先が変わりました。保存先を確認してください。");
    }

    private void requireReplacementWitness(CameraImportLedger.Pending p) throws IOException {
        if (p.replacement == null) return;
        CameraImportLedger.Content current = ledger.find(p.sourceSha, p.source.bytes);
        if (!p.replacement.sameRow(current))
            throw new CameraPhotoImporter.SavedPhotoConflict("以前の取り込み先が変わりました。保存先を確認してください。");
        if (savedPhotoExists(context, Uri.parse(p.replacement.uri), metadataQuery))
            throw new CameraPhotoImporter.SavedPhotoConflict("以前の取り込み先に写真があります。保存先を確認してください。");
    }

    /** Run under CameraImportGate before accepting another file. Never deletes a published photo. */
    public void recover(BooleanSupplier cancelled) throws IOException {
        for (CameraImportLedger.Pending initial : ledger.pending()) {
            check(cancelled);
            // A detached card is not missing media. Leave its journal for the same card.
            if (!MediaStore.getExternalVolumeNames(context).contains(initial.volume)) continue;
            Uri uri = locate(initial);
            State state = uri == null ? null : state(uri);
            if (state == null) {
                PhotoStorage.requireMounted(context, MediaStore.Images.Media.getContentUri(initial.volume));
                requirePublicationContent(initial);
                ledger.discard(initial.token);
                continue;
            }
            CameraImportLedger.Pending p = ledger.pending(initial.token);
            owned(state);
            if (!p.ready) {
                if (!state.pending()) throw new IOException("Visible photo has no READY authorization");
                discardUnpublished(p);
                continue;
            }
            completePublication(p, uri, cancelled);
        }
    }
    private static String hex(byte[] bytes) { StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString(); }
}
