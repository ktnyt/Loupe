package jp.nano.rawgallery.camera;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.DocumentsContract;
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
            faults.after(Boundary.URI_RECORDED);
            ParcelFileDescriptor fd=resolver.openFileDescriptor(target,"w");
            if(fd==null)throw new IOException("Photo output could not be opened");
            try(InputStream input=new FileInputStream(staged); ParcelFileDescriptor.AutoCloseOutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {

                    byte[] buf=new byte[65536];int n;long total=0;
                    while((n=input.read(buf))!=-1) {check(cancelled);total+=n;if(total>p.storedBytes)throw new IOException("Staged photo changed");out.write(buf,0,n);}
                    if(total!=p.storedBytes)throw new IOException("Staged photo is incomplete");
                    out.getFD().sync();
            }
            check(cancelled);verify(p,target,cancelled);
            requirePublicationContent(p);
            // READY is the durable admission to publication. A refused source check must leave
            // the row unready so recovery can only discard it, never publish it later.
            beforeCommit.check();
            ledger.ready(p.token);p=ledger.pending(p.token);
            faults.after(Boundary.FILE_VERIFIED);
            // An admitted publication must finish or recover with its receipt, even after a stop.
            requirePublicationContent(p);
            makeVisible(p,target);
            faults.after(Boundary.PUBLISHED);
            requirePublicationContent(p);
            ledger.commit(p.token);faults.after(Boundary.RECEIPT_SAVED);
            return target;
        } catch(IOException|RuntimeException failure) {
            CameraImportLedger.Pending current=ledger.pending(p.token);
            if(current!=null&&!current.ready) {
                try {discardUnpublished(current);}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            }
            throw failure;
        }
    }
    private record State(boolean pending, boolean trashed, String owner, String name, String folder,
            long generationAdded, long generationModified) {}

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

    private void verify(CameraImportLedger.Pending p,Uri uri,BooleanSupplier cancelled)throws IOException {
        owned(state(uri));
        try(InputStream in=resolver.openInputStream(uri)) {
            if(in==null)throw new IOException("Saved photo could not be read");
            MessageDigest sha=MessageDigest.getInstance("SHA-256");byte[] buf=new byte[65536];int n;long count=0;
            while((n=in.read(buf))!=-1){check(cancelled);count+=n;readCounter.accept(n);if(count>p.storedBytes)throw new IOException("Saved photo length mismatch");sha.update(buf,0,n);}
            if(count!=p.storedBytes||!hex(sha.digest()).equals(p.storedSha))throw new IOException("Saved photo fingerprint mismatch");
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private void makeVisible(CameraImportLedger.Pending p,Uri uri)throws IOException {
        State state=state(uri);owned(state);
        if(!state.pending())return;
        ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,p.finalName);values.put(MediaStore.MediaColumns.IS_PENDING,0);
        if(resolver.update(uri,values,null,null)!=1)throw new IOException("Photo publish failed");
    }
    private void discardUnpublished(CameraImportLedger.Pending p)throws IOException {
        Uri uri=locate(p);
        if(uri!=null) {
            State state=state(uri);
            if (state != null) {
                owned(state);
                if (!state.pending()) throw new IOException("Published photo needs receipt recovery");
                if (!p.pendingName.equals(state.name()) || !p.relativeFolder.equals(state.folder()))
                    throw new IOException("Pending photo identity changed before cleanup");
                Bundle exact = includeAllPhotos();
                exact.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                        MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=? AND " + MediaStore.MediaColumns.IS_PENDING
                                + "=1 AND " + MediaStore.MediaColumns.IS_TRASHED + "=0 AND "
                                + MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH
                                + "=? AND " + MediaStore.MediaColumns.GENERATION_ADDED + "=? AND "
                                + MediaStore.MediaColumns.GENERATION_MODIFIED + "=?");
                exact.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        new String[]{state.owner(), p.pendingName, p.relativeFolder,
                                Long.toString(state.generationAdded()), Long.toString(state.generationModified())});
                if (resolver.delete(uri, exact) != 1 || state(uri) != null)
                    throw new IOException("Incomplete photo cleanup failed");
            }
        }
        PhotoStorage.requireMounted(context,MediaStore.Images.Media.getContentUri(p.volume));
        ledger.discard(p.token);
    }
    private void requirePublicationContent(CameraImportLedger.Pending p) throws IOException {
        CameraImportLedger.Content current = ledger.find(p.sourceSha, p.source.bytes);
        // A migrated journal without a witness can finish a new or idempotent publication.
        // It cannot authorize replacing a different destination selected after allocation.
        if (p.replacement == null) {
            if (current != null && (!p.uri.equals(current.uri) || !p.storedSha.equals(current.storedSha)
                    || p.storedBytes != current.storedBytes))
                throw new IOException("Saved photo changed before publication");
            return;
        }
        if (!p.replacement.sameRow(current)) throw new IOException("Saved photo changed before replacement");
        if (savedPhotoExists(context, Uri.parse(p.replacement.uri), metadataQuery))
            throw new IOException("Previous saved photo still exists");
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
            verify(p, uri, cancelled);
            requirePublicationContent(p);
            makeVisible(p, uri);
            requirePublicationContent(p);
            ledger.commit(p.token);
        }
    }
    private static String hex(byte[] bytes) { StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString(); }
}
