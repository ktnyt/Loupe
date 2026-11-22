package jp.nano.rawgallery.camera;

import android.Manifest;
import android.content.Context;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded read-only enumeration for an Android-mounted SD card's selected DCIM tree. */
public final class CameraDocumentSource {
    public static final class Entry {
        public final Uri uri;public final CameraImportFile file;
        Entry(Uri uri,CameraImportFile file){this.uri=uri;this.file=file;}
    }
    public static final class Listing {
        public final List<Entry> photos;public final boolean stable;
        Listing(List<Entry> photos,boolean stable){this.photos=Collections.unmodifiableList(photos);this.stable=stable;}
    }
    private final Context context;private final Uri tree;private final String source;private final BooleanSupplier cancelled;
    private int visited,rows;
    private CameraDocumentSource(Context context,Uri tree,String source,BooleanSupplier cancelled){this.context=context;this.tree=tree;this.source=source;this.cancelled=cancelled;}
    public static Listing list(Context context,Uri tree,BooleanSupplier cancelled)throws IOException {
        CameraPhotoPublisher.check(cancelled);
        if(tree==null||!DocumentsContract.isTreeUri(tree))throw new IOException("Choose the SD card DCIM folder");
        String root=DocumentsContract.getTreeDocumentId(tree);
        String path=root.contains(":")?root.substring(root.indexOf(':')+1):"";
        if(!path.isEmpty()&&!path.equalsIgnoreCase("DCIM")&&!path.regionMatches(true,0,"DCIM/",0,5))throw new IOException("Choose the SD card DCIM folder");
        if(!path.isEmpty())try{new CameraImportFile("path-check",path+"/check.JPG",1,"",0);}catch(IllegalArgumentException invalid){throw new IOException("Invalid DCIM tree path",invalid);}
        String identity=CameraSourceIdentity.sd(context,tree);
        CameraDocumentSource reader=new CameraDocumentSource(context,tree,originalSourceId(identity==null?"saf:"+tree:identity),cancelled);
        List<Entry> files=new ArrayList<>();reader.scan(root,path,files,0);return new Listing(files,identity!=null);
    }
    private void scan(String id,String path,List<Entry> files,int depth)throws IOException {
        CameraPhotoPublisher.check(cancelled);if(depth>5||++visited>1000)throw new IOException("SD directory limit");
        String[] projection={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        try(Cursor c=context.getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,id),projection,null,null,null)) {
            if(c==null)throw new IOException("SD listing failed");
            while(c.moveToNext()) {
                if(++rows>20000)throw new IOException("SD entry limit");
                CameraPhotoPublisher.check(cancelled);String name=c.getString(1);
                if(name==null||name.contains("/")||name.contains("\\")||name.equals(".")||name.equals(".."))throw new IOException("Invalid SD name");
                String child=c.getString(0),mime=c.getString(2),relative=path.isEmpty()?name:path+"/"+name;
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){if(!path.isEmpty()||name.equalsIgnoreCase("DCIM"))scan(child,relative,files,depth+1);}
                else if(!path.isEmpty()&&MediaTypes.INSTANCE.kindOf(name)!=null&&!MediaTypes.INSTANCE.isVideo(name)) {
                    if(files.size()>=20000)throw new IOException("SD file limit");
                    long size=c.getLong(3),modified=c.getLong(4);if(size<=0||size>Ep7WifiClient.MAX_FILE_BYTES)throw new IOException("Unsupported photo size");
                    files.add(new Entry(DocumentsContract.buildDocumentUriUsingTree(tree,child),new CameraImportFile(source,relative,size,Long.toString(modified),modified/1000)));
                }
            }
        }
    }
    /** SAF receipts from potentially redacted reads cannot authorize an original-byte shortcut. */
    static String originalSourceId(String previousSourceId) { return "original-byte-v1:"+previousSourceId; }
    public static final class OriginalAccessException extends IOException {
        public enum Reason { PERMISSION, MAPPING, VOLUME, UNAVAILABLE, LENGTH }
        public final Reason reason;
        private OriginalAccessException(Reason reason) {
            super(switch (reason) {
                case PERMISSION -> "写真の原本を取り込むには、写真の位置情報へのアクセスを許可してください";
                case VOLUME -> "取り込み元のカードで写真の原本を確認できません。カードとDCIMフォルダを確認してください";
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
        catch(IOException|RuntimeException unavailable){throw new OriginalAccessException(OriginalAccessException.Reason.MAPPING);}
        if(media==null||!"content".equals(media.getScheme())||!"media".equals(media.getAuthority()))
            throw new OriginalAccessException(OriginalAccessException.Reason.MAPPING);
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
