package jp.nano.rawgallery.camera;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
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
        if(tree==null||!DocumentsContract.isTreeUri(tree))throw new IOException("Choose the SD card DCIM folder");
        String root=DocumentsContract.getTreeDocumentId(tree);
        String path=root.contains(":")?root.substring(root.indexOf(':')+1):"";
        if(!path.isEmpty()&&!path.equalsIgnoreCase("DCIM")&&!path.regionMatches(true,0,"DCIM/",0,5))throw new IOException("Choose the SD card DCIM folder");
        if(!path.isEmpty())try{new CameraImportFile("path-check",path+"/check.JPG",1,"",0);}catch(IllegalArgumentException invalid){throw new IOException("Invalid DCIM tree path",invalid);}
        String identity=CameraSourceIdentity.sd(context,tree);
        CameraDocumentSource reader=new CameraDocumentSource(context,tree,identity==null?"saf:"+tree:identity,cancelled);
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
    public static void copy(Context context,Entry entry,OutputStream out,BooleanSupplier cancelled)throws IOException {
        try(InputStream in=context.getContentResolver().openInputStream(entry.uri)) {
            if(in==null)throw new IOException("SD read failed");byte[] buffer=new byte[65536];int n;
            while((n=in.read(buffer))!=-1){CameraPhotoPublisher.check(cancelled);out.write(buffer,0,n);}
        }
    }
}
