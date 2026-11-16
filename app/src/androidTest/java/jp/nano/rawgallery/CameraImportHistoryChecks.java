package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.*;
import android.net.Uri;
import android.os.*;
import java.util.*;
import jp.nano.rawgallery.camera.*;

/** Generated metadata and an isolated in-memory provider; never seeds user history or media. */
final class CameraImportHistoryChecks {
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    static String run(Instrumentation test)throws Exception {
        policy();snapshot(test.getTargetContext());folder(test.getTargetContext());
        return "camera-import-history: 4800 known, two unknown, URI identity, missing, ambiguous, deleted, strict, null/denied, pending/trash and one-query snapshot passed";
    }
    public static void policy(){
        List<CameraImportHistoryPlan.Entry> entries=new ArrayList<>();
        Map<String,CameraImportHistoryPlan.Destination> present=new HashMap<>();
        for(int i=0;i<4800;i++){
            String uri="content://media/external/images/media/"+(i+1);
            entries.add(new CameraImportHistoryPlan.Entry("photo"+i+".JPG",100,20,uri,false));
            present.put(uri,new CameraImportHistoryPlan.Destination(100,true));
        }
        CameraImportHistoryPlan plan=new CameraImportHistoryPlan(entries,present);
        int bodies=0,known=0;
        for(int i=0;i<4800;i++)if(plan.known("photo"+i+".JPG",100,20,false,null,-1))known++;else bodies++;
        require(known==4800&&bodies==0,"unchanged SD must not read or publish");
        for(int i=4800;i<4802;i++)if(!plan.known("photo"+i+".JPG",100,20,false,null,-1))bodies++;
        require(bodies==2,"only unknown metadata requires content");
        require(!plan.known("photo0.JPG",100,20,true,null,-1),"strict bypass");
        require(!plan.known("photo0.JPG",101,20,false,null,-1),"changed metadata");
        present.remove("content://media/external/images/media/1");
        require(!plan.known("photo0.JPG",100,20,false,null,-1),"exact destination missing despite same-name others");
        entries.add(new CameraImportHistoryPlan.Entry("photo1.JPG",100,20,"existing",false));
        require(new CameraImportHistoryPlan(entries,present).known("photo1.JPG",100,20,false,null,-1),"placeholder plus actual record");
        entries.add(new CameraImportHistoryPlan.Entry("photo1.JPG",100,20,"content://media/external/images/media/3",false));
        require(!new CameraImportHistoryPlan(entries,present).known("photo1.JPG",100,20,false,null,-1),"conflicting destinations");
        entries.add(new CameraImportHistoryPlan.Entry("photo2.JPG",100,20,"content://media/external/images/media/3",true));
        require(!new CameraImportHistoryPlan(entries,present).known("photo2.JPG",100,20,false,null,-1),"camera deletion evidence");
        entries.add(new CameraImportHistoryPlan.Entry("placeholder.JPG",100,20,"existing",false));
        require(!new CameraImportHistoryPlan(entries,present).known("placeholder.JPG",100,20,false,null,-1),"placeholder alone");
        require(!new CameraImportHistoryPlan(entries,null).known("photo4.JPG",100,20,false,null,-1),"unknown query result");
        present.put("content://media/external/images/media/5",new CameraImportHistoryPlan.Destination(105,true));
        require(!plan.known("photo4.JPG",100,20,false,null,-1),"changed stored size");
        require(plan.known("photo4.JPG",100,20,false,"content://media/external/images/media/5",105),"matching receipt stored size");
        require(!plan.known("photo4.JPG",100,20,false,"content://media/external/images/media/6",105),"wrong receipt URI");
        entries.add(new CameraImportHistoryPlan.Entry("photo5.JPG",100,20,"content://media/external/images/media/6",false));
        require(new CameraImportHistoryPlan(entries,present).known("photo5.JPG",100,20,false,null,-1),"same URI duplicates collapse");
        int processed=0;
        for(int i=0;i<4800;i++){if(i==19)break;plan.known("photo"+i+".JPG",100,20,false,null,-1);processed++;}
        require(processed==19&&entries.size()==4805,"classification cancellation preserves evidence");
    }
    private static void folder(Context context){
        String name="camera-history-folder-test-"+UUID.randomUUID()+".db";
        HistoryDb db=new HistoryDb(context,name);
        try {
            android.database.sqlite.SQLiteDatabase sql=db.getWritableDatabase();
            sql.execSQL("INSERT INTO camera(model,serial,folder,mass_storage) VALUES('fixture','1','USB Storage',0)");
            sql.execSQL("INSERT INTO imported(camera_model,camera_serial,file_name,file_size,shot_at,imported_at,dest_uri) VALUES('fixture','1','fixture.JPG',100,1,1,'existing')");
            require("USB Storage".equals(db.defaultPhotoFolder()),"folder survives last transport flag");
            sql.execSQL("INSERT INTO camera(model,serial,folder,mass_storage) VALUES('unused','2','Unused',1)");
            require("USB Storage".equals(db.defaultPhotoFolder()),"unused camera does not create ambiguity");
            sql.execSQL("INSERT INTO imported(camera_model,camera_serial,file_name,file_size,shot_at,imported_at,dest_uri) VALUES('unused','2','fixture.JPG',100,1,1,'existing')");
            require(db.defaultPhotoFolder()==null,"conflicting used folders require fallback");
        } finally {db.close();context.deleteDatabase(name);}
    }
    private static final class Provider extends ContentProvider {
        int queries, bodies;int mode;
        @Override public boolean onCreate(){return true;}
        @Override public Cursor query(Uri u,String[] projection,String selection,String[] args,String sort){
            queries++;if(mode==1)return null;if(mode==2)throw new SecurityException("denied");
            MatrixCursor c=new MatrixCursor(new String[]{"_id","_size","is_pending","is_trashed","volume_name"});
            c.addRow(new Object[]{11,100,0,0,"external_primary"});
            c.addRow(new Object[]{12,100,1,0,"external_primary"});
            c.addRow(new Object[]{13,100,0,1,"external_primary"});return c;
        }
        @Override public String getType(Uri u){return "image/jpeg";}
        @Override public Uri insert(Uri u,ContentValues v){throw new AssertionError("unexpected insert");}
        @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new AssertionError("unexpected update");}
        @Override public int delete(Uri u,String s,String[] a){throw new AssertionError("unexpected delete");}
        @Override public ParcelFileDescriptor openFile(Uri u,String m){bodies++;throw new AssertionError("unexpected body read");}
    }
    private static void snapshot(Context target){
        Provider provider=new Provider();ProviderInfo info=new ProviderInfo();info.authority="media";provider.attachInfo(target,info);
        ContentResolver resolver=ContentResolver.wrap(provider);
        Context context=new ContextWrapper(target){
            @Override public ContentResolver getContentResolver(){return resolver;}
            @Override public int checkSelfPermission(String permission){return PackageManager.PERMISSION_GRANTED;}
        };
        Map<String,CameraImportHistoryPlan.Destination> snapshot=MediaSaver.INSTANCE.photoDestinationSnapshot(context);
        require(provider.queries==1&&provider.bodies==0,"one metadata query with zero body reads");
        require(snapshot!=null&&snapshot.get("content://media/external/images/media/11").published,"published URI");
        require(snapshot.get("content://media/external_primary/images/media/11").published,"volume URI alias");
        require(!snapshot.get("content://media/external/images/media/12").published,"pending excluded");
        require(!snapshot.get("content://media/external/images/media/13").published,"trashed excluded");
        require(!snapshot.containsKey("content://media/external/images/media/99"),"wrong URI not substituted");
        provider.mode=1;require(MediaSaver.INSTANCE.photoDestinationSnapshot(context)==null,"null is unknown");
        provider.mode=2;require(MediaSaver.INSTANCE.photoDestinationSnapshot(context)==null,"denied is unknown");
        Context denied=new ContextWrapper(target){@Override public int checkSelfPermission(String permission){return PackageManager.PERMISSION_DENIED;}};
        require(MediaSaver.INSTANCE.photoDestinationSnapshot(denied)==null,"missing full permission is unknown");
        require(provider.queries==3&&provider.bodies==0,"failures never read body");
    }
}
