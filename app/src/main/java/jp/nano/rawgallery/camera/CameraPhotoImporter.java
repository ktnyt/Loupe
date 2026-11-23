package jp.nano.rawgallery.camera;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import jp.nano.rawgallery.PhotoStorage;
import java.util.function.BooleanSupplier;

/** The photo import contract shared by Wi-Fi, SAF/SD and USB readers. Call under CameraImportGate. */
public final class CameraPhotoImporter {
    public static final class Result {
        public final Uri uri;
        public final boolean imported, contentChecked;
        public final long sourceBytesRead;
        Result(Uri uri,boolean imported,boolean checked,long bytes){this.uri=uri;this.imported=imported;this.contentChecked=checked;this.sourceBytesRead=bytes;}
    }
    private final Context context;
    private final CameraImportLedger ledger;
    private final CameraPhotoPublisher publisher;
    private final String defaultFolder;
    private CameraImportMetrics metrics;
    public void setMetrics(CameraImportMetrics metrics){this.metrics=metrics;publisher.setReadCounter(n->{if(this.metrics!=null)this.metrics.destinationBytes+=n;});}
    public CameraPhotoImporter(Context context) { this(context,CameraImportLedger.get(context)); }
    public CameraPhotoImporter(Context context,CameraImportLedger ledger) {
        this(context,ledger,PhotoStorage.selected(context));
    }
    public CameraPhotoImporter(Context context,CameraImportLedger ledger,String volume) {
        this.context=context.getApplicationContext();this.ledger=ledger;this.publisher=new CameraPhotoPublisher(this.context,ledger,volume,b -> {});
        this.defaultFolder=HistoryDb.Companion.get(this.context).defaultPhotoFolder();
    }
    public void recover(BooleanSupplier cancelled)throws IOException {publisher.recover(cancelled);}
    private boolean available(CameraImportLedger.Content content,BooleanSupplier cancelled)throws IOException {
        Uri uri=Uri.parse(content.uri);
        if (!CameraPhotoPublisher.savedPhotoExists(context, uri)) return false;
        try(InputStream in=context.getContentResolver().openInputStream(uri)) {
            if(in==null)throw new IOException("Saved photo unavailable");
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf=new byte[65536];int n;long size=0;
            while((n=in.read(buf))!=-1){CameraPhotoPublisher.check(cancelled);size+=n;if(metrics!=null)metrics.destinationBytes+=n;if(size>content.storedBytes)throw new IOException("Saved photo changed");digest.update(buf,0,n);}
            StringBuilder sha=new StringBuilder();for(byte b:digest.digest())sha.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            if(size!=content.storedBytes||!sha.toString().equals(content.storedSha))throw new IOException("Saved photo changed");
            return true;
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    public Result importPhoto(CameraImportFile source,CameraImportCopy.Copy copy,String folderHint,boolean stableSource,boolean verifyAll,BooleanSupplier cancelled)throws IOException {
        return importPhoto(source, copy, folderHint, stableSource, verifyAll, cancelled, CameraPhotoPublisher.NO_COMMIT_CHECK);
    }

    Result importPhoto(CameraImportFile source, CameraImportCopy.Copy copy, String folderHint, boolean stableSource,
            boolean verifyAll, BooleanSupplier cancelled, CameraPhotoPublisher.CommitCheck beforeCommit) throws IOException {
        CameraPhotoPublisher.check(cancelled);
        String read=android.os.Build.VERSION.SDK_INT>=33?android.Manifest.permission.READ_MEDIA_IMAGES:android.Manifest.permission.READ_EXTERNAL_STORAGE;
        if(context.checkSelfPermission(read)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IOException("Full photo access required for duplicate verification");
        if(stableSource&&!verifyAll) {
            CameraImportLedger.Content known=ledger.known(source);
            if(known!=null&&available(known,cancelled)) {
                beforeCommit.check();
                return new Result(Uri.parse(known.uri),false,false,0);
            }
        }
        try(CameraImportCopy.Prepared prepared=CameraImportCopy.prepare(context.getCacheDir(),source,metrics==null?copy:metrics.countSource(copy),cancelled)) {
            CameraImportLedger.Content existing = ledger.find(prepared.sourceSha256, source.bytes);
            CameraImportLedger.Content missing = null;
            if (existing != null && !available(existing, cancelled)) {
                missing = existing;
                existing = null;
            }
            CameraImportLegacy.Evidence adopted=existing==null?CameraImportLegacy.existing(context,source,prepared.file,cancelled,n->{if(metrics!=null)metrics.legacyBytes+=n;}):null;
            // Preserve the original digest; only this owned temporary copy may be repaired.
            ExifOffsetFix.INSTANCE.patchIfBroken(prepared.file);
            CameraImportLegacy.Metadata metadata=CameraImportLegacy.metadata(prepared.file,source.captureSeconds);
            CameraImportFile eventSource=new CameraImportFile(source.sourceId,source.relativePath,source.bytes,source.sourceStamp,metadata.getCaptureSeconds());
            String kind=MediaTypes.INSTANCE.isRaw(source.name)?"RAW":"JPEG";
            if(existing!=null) {
                beforeCommit.check();
                ledger.bind(eventSource,existing);
                return new Result(Uri.parse(existing.uri),false,true,source.bytes);
            }
            String group=ledger.captureGroup(eventSource,prepared.sourceSha256,kind);
            if(adopted!=null) {
                if (missing != null && CameraPhotoPublisher.savedPhotoExists(context, Uri.parse(missing.uri)))
                    throw new IOException("Previous saved photo still exists");
                beforeCommit.check();
                CameraImportLedger.Content content = ledger.adopt(eventSource, prepared.sourceSha256, adopted.getSha256(),
                        adopted.getBytes(), adopted.getUri(), kind, group, missing);
                return new Result(Uri.parse(content.uri),false,true,source.bytes);
            }
            String folder=folderHint==null||folderHint.isEmpty()?(defaultFolder==null?metadata.getModel():defaultFolder):folderHint;
            if(metrics!=null)metrics.saving();
            Uri uri = publisher.publish(eventSource, prepared.file, prepared.sourceSha256, kind,
                    CameraPhotoPublisher.folder(folder, kind.equals("RAW")), group, cancelled, beforeCommit, missing);
            return new Result(uri,true,true,source.bytes);
        }
    }
}
