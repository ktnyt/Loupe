package jp.nano.rawgallery;

import android.content.*;
import android.net.Uri;
import java.io.*;
import java.util.UUID;

/** Installed by instrumentation before Activity.onCreate, never activated by production intents. */
final class TagFixtureIsolation {
  static Intent intent(Context context,String db,String features){return new Intent(context,TagActivity.class).putExtra("fixtureTags",db).putExtra("fixtureFeatures",features).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);}
  static void install(TagActivity activity){
    String db=activity.getIntent().getStringExtra("fixtureTags"),features=activity.getIntent().getStringExtra("fixtureFeatures");
    if(!valid(db)||!valid(features))throw new IllegalArgumentException("Named fixture databases required before onCreate");
    activity.tagDatabaseName=db;activity.featureDatabaseName=features;activity.isolatedPhotos=true;
    File base=new File(activity.getCacheDir(),"tag-fixture-repository-"+UUID.randomUUID());
    activity.photoContext=new ContextWrapper(activity){
      public Context getApplicationContext(){return this;}
      public File getCacheDir(){File dir=new File(base,"cache");dir.mkdirs();return dir;}
      public File getFilesDir(){File dir=new File(base,"files");dir.mkdirs();return dir;}
    };
  }
  private static boolean valid(String name){return name!=null&&(name.startsWith("confidence-")||name.startsWith("tag-detail-"))&&name.endsWith(".db")&&!name.contains("/")&&!name.contains("\\");}
  static void assertGuard(TagActivity activity)throws Exception{
    TagDetailChecks.require(activity.isolatedPhotos&&activity.rejectedFixtureAccess==0,"initial onCreate stayed isolated");
    boolean rejected=false;try{activity.getDatabasePath("tags.db");}catch(SecurityException expected){rejected=true;}TagDetailChecks.require(rejected,"real tag DB denied");
    rejected=false;try(GalleryIndex index=new GalleryIndex(activity)){index.getReadableDatabase();}catch(SecurityException expected){rejected=true;}TagDetailChecks.require(rejected,"real GalleryIndex denied");
    java.lang.reflect.Method preview=TagActivity.class.getDeclaredMethod("photoPreview",PhotoRepository.Photo.class);preview.setAccessible(true);
    rejected=false;try{preview.invoke(activity,new PhotoRepository.Photo(Uri.parse("content://media/external/images/media/1"),"denied.jpg","",1,0,0));}catch(java.lang.reflect.InvocationTargetException expected){rejected=expected.getCause() instanceof SecurityException;}TagDetailChecks.require(rejected,"real photo denied before repository access");
  }
  static void cleanup(TagActivity activity){File cache=activity.photoContext.getCacheDir();remove(cache.getParentFile());}
  private static void remove(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);file.delete();}
}
