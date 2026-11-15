package jp.nano.rawgallery.camera;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.*;
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
    public CameraPhotoPublisher(Context context,CameraImportLedger ledger) { this(context,ledger,b -> {}); }
    public CameraPhotoPublisher(Context context,CameraImportLedger ledger,Faults faults) {
        this.context=context.getApplicationContext();this.resolver=this.context.getContentResolver();this.ledger=ledger;this.faults=faults;
    }
    static void check(BooleanSupplier cancelled)throws IOException { if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Import cancelled"); }
    public static String folder(String value,boolean raw) {
        String clean=value==null?"Camera":value.trim().replaceAll("[/\\\\\\p{Cntrl}]","_");
        if(clean.isEmpty()||clean.equals(".")||clean.equals(".."))clean="Camera";
        if(clean.length()>80)clean=clean.substring(0,80);
        return "Pictures/CameraSync/"+clean+(raw?"-RAW":"")+"/";
    }
    public Uri publish(CameraImportFile source,File staged,String originalSha,String kind,String folder,String group,BooleanSupplier cancelled)throws IOException {
        check(cancelled);
        String savedSha=CameraImportCopy.sha256(staged,cancelled);
        CameraImportLedger.Pending p=ledger.allocate(source,originalSha,savedSha,staged.length(),source.name,folder,kind,group);
        faults.after(Boundary.ALLOCATED);
        try {
            ContentValues values=new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME,p.pendingName);
            values.put(MediaStore.MediaColumns.MIME_TYPE,MediaTypes.INSTANCE.mimeOf(source.name));
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,p.relativeFolder);
            if(source.captureSeconds>0)values.put(MediaStore.MediaColumns.DATE_TAKEN,source.captureSeconds*1000);
            values.put(MediaStore.MediaColumns.IS_PENDING,1);
            Uri target=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
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
            ledger.ready(p.token);p=ledger.pending(p.token);
            faults.after(Boundary.FILE_VERIFIED);
            // Once publication begins, finish/recover its receipt even if cancellation arrives.
            makeVisible(p,target);
            faults.after(Boundary.PUBLISHED);
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
    private static final class State {
        final boolean pending;final String owner;
        State(boolean pending,String owner){this.pending=pending;this.owner=owner;}
    }
    private State state(Uri uri)throws IOException {
        if(!"content".equals(uri.getScheme())||!"media".equals(uri.getAuthority())||!uri.getPath().matches("/(?:external|external_primary)/images/media/[0-9]+"))throw new IOException("Unexpected photo URI");
        Bundle args=new Bundle();args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE);
        try(Cursor c=resolver.query(uri,new String[]{MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.OWNER_PACKAGE_NAME},args,null)) {
            if(c==null)throw new IOException("Photo status could not be read");
            if(!c.moveToFirst())return null;
            return new State(c.getInt(0)!=0,c.isNull(1)?null:c.getString(1));
        }
    }
    private void owned(State state)throws IOException {
        if(state==null||!context.getPackageName().equals(state.owner))throw new IOException("Publication ownership could not be verified");
    }
    private Uri locate(CameraImportLedger.Pending p)throws IOException {
        if(p.uri!=null)return Uri.parse(p.uri);
        Bundle args=new Bundle();args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE);
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,MediaStore.MediaColumns.DISPLAY_NAME+"=? AND "+MediaStore.MediaColumns.RELATIVE_PATH+"=? AND "+MediaStore.MediaColumns.OWNER_PACKAGE_NAME+"=?");
        args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,new String[]{p.pendingName,p.relativeFolder,context.getPackageName()});
        try(Cursor c=resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,new String[]{MediaStore.MediaColumns._ID},args,null)) {
            if(c==null)throw new IOException("Pending photo lookup failed");
            if(c.getCount()>1)throw new IOException("Ambiguous pending photo");
            if(!c.moveToFirst())return null;
            Uri uri=ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,c.getLong(0));
            ledger.attach(p.token,uri.toString());return uri;
        }
    }
    private void verify(CameraImportLedger.Pending p,Uri uri,BooleanSupplier cancelled)throws IOException {
        owned(state(uri));
        try(InputStream in=resolver.openInputStream(uri)) {
            if(in==null)throw new IOException("Saved photo could not be read");
            MessageDigest sha=MessageDigest.getInstance("SHA-256");byte[] buf=new byte[65536];int n;long count=0;
            while((n=in.read(buf))!=-1){check(cancelled);count+=n;if(count>p.storedBytes)throw new IOException("Saved photo length mismatch");sha.update(buf,0,n);}
            if(count!=p.storedBytes||!hex(sha.digest()).equals(p.storedSha))throw new IOException("Saved photo fingerprint mismatch");
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private void makeVisible(CameraImportLedger.Pending p,Uri uri)throws IOException {
        State state=state(uri);owned(state);
        if(!state.pending)return;
        ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,p.finalName);values.put(MediaStore.MediaColumns.IS_PENDING,0);
        if(resolver.update(uri,values,null,null)!=1)throw new IOException("Photo publish failed");
    }
    private void discardUnpublished(CameraImportLedger.Pending p)throws IOException {
        Uri uri=locate(p);
        if(uri!=null) {
            State state=state(uri);
            if(state!=null){owned(state);if(!state.pending)throw new IOException("Published photo needs receipt recovery");if(resolver.delete(uri,null,null)!=1)throw new IOException("Incomplete photo cleanup failed");}
        }
        ledger.discard(p.token);
    }
    /** Run under CameraImportGate before accepting another file. Never deletes a published photo. */
    public void recover(BooleanSupplier cancelled)throws IOException {
        for(CameraImportLedger.Pending initial:ledger.pending()) {
            check(cancelled);Uri uri=locate(initial);
            if(uri==null||state(uri)==null){ledger.discard(initial.token);continue;}
            CameraImportLedger.Pending p=ledger.pending(initial.token);State state=state(uri);owned(state);
            if(!p.ready&&state.pending){discardUnpublished(p);continue;}
            verify(p,uri,cancelled);
            if(!p.ready)ledger.ready(p.token);
            p=ledger.pending(p.token);makeVisible(p,uri);ledger.commit(p.token);
        }
    }
    private static String hex(byte[] bytes) { StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString(); }
}
