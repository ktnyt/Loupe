package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Baseline-format recipes, owned synthetic JPEGs and isolated tag stores only. */
final class LookRemovalChecks {
  static Bundle legacyState(Intent intent){
    Bundle state=new Bundle();state.putFloatArray("values",new RawEditState().with(RawEditState.EXPOSURE,.25f).snapshot());state.putFloatArray("saved",new RawEditState().snapshot());
    state.putString("input",intent.getData().toString());state.putInt("uiVersion",3);state.putInt("tab",2);
    state.putString("lookToken","00000000-0000-0000-0000-000000000119");state.putInt("lookPreset",1);state.putBoolean("lookCurves",true);state.putBoolean("lookSaveFailed",true);return state;
  }
  static Object get(Object object,String name)throws Exception{java.lang.reflect.Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
  static void set(Object object,String name,Object value)throws Exception{java.lang.reflect.Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
  static Object call(Object object,String name,Class<?>[] types,Object...args)throws Exception{java.lang.reflect.Method m=object.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(object,args);}
  static void ui(Instrumentation t,GenerationIndicatorChecks.Check check){TagDetailChecks.ui(t,()->{try{check.ok();}catch(Exception e){throw new RuntimeException(e);}});}
  static String digest(byte[] bytes)throws Exception{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
  static byte[] bytes(File file)throws Exception{try(InputStream input=new FileInputStream(file)){return input.readAllBytes();}}
  static void tree(File file,String key,Map<String,String> result)throws Exception{
    if(!file.exists())return;
    if(file.isDirectory()){result.put(key,"directory");File[] children=file.listFiles();if(children==null)throw new IOException("fixture directory unreadable");for(File child:children)tree(child,key+"/"+child.getName(),result);}
    else result.put(key,file.length()+":"+file.lastModified()+":"+digest(bytes(file)));
  }
  static Map<String,String> snapshot(Context c)throws Exception{Map<String,String> result=new TreeMap<>();for(String name:new String[]{"look-library","look-sessions"})tree(new File(c.getFilesDir(),name),name,result);return result;}
  static void seed(Instrumentation t,File root,String folder,List<File> created)throws Exception{
    File directory=new File(root,folder);directory.mkdirs();
    for(String name:t.getContext().getAssets().list("legacy-look/"+folder)){
      File target=new File(directory,name);byte[] fixture;
      try(InputStream input=t.getContext().getAssets().open("legacy-look/"+folder+"/"+name)){fixture=input.readAllBytes();}
      if(target.exists()){TagDetailChecks.require(Arrays.equals(bytes(target),fixture),"existing fixture differs; never overwrite");continue;}
      try(FileOutputStream output=new FileOutputStream(target)){output.write(fixture);}created.add(target);
    }
  }
  static void noLook(View view){
    if(view.getVisibility()!=View.VISIBLE)return;
    CharSequence description=view.getContentDescription();TagDetailChecks.require(description==null||!description.toString().contains("ルック"),"removed look accessibility action");
    if(view instanceof android.widget.TextView)TagDetailChecks.require(!((android.widget.TextView)view).getText().toString().contains("ルック"),"removed look label");
    if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)noLook(((ViewGroup)view).getChildAt(i));
  }
  static void safe(Activity activity,PhotoViewerUi viewer){
    android.view.WindowInsets insets=activity.getWindow().getDecorView().getRootWindowInsets();TagDetailChecks.require(insets!=null,"actual OS insets");
    android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
    android.graphics.Rect available=new android.graphics.Rect(activity.getWindowManager().getCurrentWindowMetrics().getBounds());available.inset(bars.left,bars.top,bars.right,bars.bottom);
    List<View> actions=new ArrayList<>();actions.add(ExpressiveUiChecks.description(viewer,"写真一覧に戻る"));actions.add(ExpressiveUiChecks.description(viewer,"写真のその他の操作"));for(int i=0;i<viewer.dock.getChildCount();i++)actions.add(viewer.dock.getChildAt(i));
    for(View action:actions){TagDetailChecks.require(action!=null&&action.isShown(),"viewer action is visible");int[] xy=new int[2];action.getLocationOnScreen(xy);TagDetailChecks.require(available.contains(new android.graphics.Rect(xy[0],xy[1],xy[0]+action.getWidth(),xy[1]+action.getHeight())),"viewer action fits actual OS safe bounds");}
  }
  static Set<Uri> outputs(Context c){Set<Uri> result=new HashSet<>();try(android.database.Cursor cursor=c.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,new String[]{"_id"},"relative_path = ? AND owner_package_name = ?",new String[]{"Pictures/LoupeDevelop/",c.getPackageName()},null)){if(cursor!=null)while(cursor.moveToNext())result.add(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cursor.getLong(0)));}return result;}
  static String run(Instrumentation t,int width,float font)throws Exception{
    Context c=t.getTargetContext();List<File> created=new ArrayList<>();List<Uri> owned=new ArrayList<>();Set<Uri> previous=outputs(c);
    MainActivity main=null;TagActivity tags=null;RawEditActivity editor=null;
    String tagDb="tag-detail-remove-look-"+UUID.randomUUID()+".db",featureDb="tag-detail-remove-feature-"+UUID.randomUUID()+".db";
    boolean hadLibrary=new File(c.getFilesDir(),"look-library").exists(),hadSessions=new File(c.getFilesDir(),"look-sessions").exists();
    byte[] jpeg=GenerationIndicatorChecks.jpeg();
    try{
      seed(t,c.getFilesDir(),"look-library",created);seed(t,c.getFilesDir(),"look-sessions",created);
      ContentValues v=new ContentValues();v.put(MediaStore.Images.Media.DISPLAY_NAME,"look-removal-synthetic.jpg");v.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");v.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/LoupeSyntheticChecks");
      Uri uri=c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);TagDetailChecks.require(uri!=null,"owned source inserted");owned.add(uri);try(OutputStream output=c.getContentResolver().openOutputStream(uri)){output.write(jpeg);}
      PhotoRepository.Photo photo=new PhotoRepository.Photo(uri,"look-removal-synthetic.jpg","synthetic",jpeg.length,1,1);
      // Baseline selection files are indexed by the hash of the actual photo key.
      File[] old=new File(c.getFilesDir(),"look-library").listFiles((dir,name)->name.startsWith("photo-"));TagDetailChecks.require(old!=null&&old.length>0,"valid baseline photo snapshot");
      File selected=new File(c.getFilesDir(),"look-library/photo-"+digest(ArchiveStore.key(photo).getBytes(StandardCharsets.UTF_8))+".look");
      TagDetailChecks.require(!selected.exists(),"do not overwrite photo selection");try(FileOutputStream output=new FileOutputStream(selected)){output.write(bytes(old[0]));}created.add(selected);
      Map<String,String> expected=snapshot(c);
      main=(MainActivity)t.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final MainActivity gallery=main;
      GalleryScreenChecks.finishInitialIndex(t,gallery);GalleryScreenChecks.portraitFixture(t,gallery,width,font);
      ui(t,()->{set(gallery,"albumHome",false);set(gallery,"photos",new ArrayList<>(List.of(photo)));call(gallery,"showPhoto",new Class<?>[]{int.class},0);return true;});
      GenerationIndicatorChecks.await(t,"viewer original loaded",()->get(gallery,"activePreview")!=null&&ViewerScreenChecks.viewer(gallery).image.photoBounds()!=null);
      ui(t,()->{PhotoViewerUi viewer=ViewerScreenChecks.viewer(gallery);noLook(viewer);safe(gallery,viewer);TagDetailChecks.require(GenerationIndicatorChecks.find(viewer,"viewer-look")==null&&GenerationIndicatorChecks.find(viewer,"viewer-before")==null,"no viewer look controls");return true;});
      Bitmap expectedImage=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);
      ui(t,()->{Bitmap actual=(Bitmap)get(ViewerScreenChecks.viewer(gallery).image,"bitmap");TagDetailChecks.require(actual.sameAs(expectedImage),"saved look never auto-applies to original pixels");return true;});expectedImage.recycle();
      GenerationIndicatorChecks.capture(t,"look-removed-viewer-"+width);TagDetailChecks.require(snapshot(c).equals(expected),"viewer preserves all legacy data");
      tags=(TagActivity)t.startActivitySync(TagFixtureIsolation.intent(c,tagDb,featureDb));final TagActivity tagScreen=tags;TagDetailChecks.idle(t,tags);
      File tagFile=new File(tags.getCacheDir(),"tag-detail-synthetic.jpg");TagDetailChecks.require(!tagFile.exists(),"never overwrite an existing tag fixture");created.add(tagFile);try(FileOutputStream output=new FileOutputStream(tagFile)){output.write(jpeg);}
      PhotoRepository.Photo tagPhoto=new PhotoRepository.Photo(Uri.fromFile(tagFile),"tag-synthetic.jpg","",jpeg.length,1,1);
      ui(t,()->{set(tagScreen,"tag",new TagStore.Tag("remove-look-fixture","確認用"));set(tagScreen,"photos",new ArrayList<>(List.of(tagPhoto)));set(tagScreen,"photoIds",new ArrayList<>(List.of("fixture")));set(tagScreen,"browsePhotos",new ArrayList<>(List.of(0)));set(tagScreen,"judgments",new TagJudgments());set(tagScreen,"automatic",new HashMap<>());call(tagScreen,"showAssignedPhoto",new Class<?>[]{int.class},0);return true;});
      GenerationIndicatorChecks.await(t,"tag viewer loaded",()->((PhotoViewerUi)get(tagScreen,"photoViewer")).image.photoBounds()!=null);
      ui(t,()->{noLook((View)get(tagScreen,"root"));safe(tagScreen,(PhotoViewerUi)get(tagScreen,"photoViewer"));return true;});GenerationIndicatorChecks.capture(t,"look-removed-tag-"+width);ui(t,()->{tagScreen.finish();return true;});
      editor=(RawEditActivity)t.startActivitySync(new Intent(c,RawEditActivity.class).setData(uri).putExtra("removeLookLegacyFixture",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final RawEditActivity raw=editor;
      GenerationIndicatorChecks.await(t,"ordinary editor restored",()->(boolean)get(raw,"ready")&&get(raw,"fullSession")!=null);
      ui(t,()->{noLook((View)get(raw,"root"));TagDetailChecks.require(Math.abs(((RawEditState)get(raw,"state")).get(RawEditState.EXPOSURE)-.25f)<.0001,"legacy development values restored");Bundle saved=new Bundle();raw.onSaveInstanceState(saved);TagDetailChecks.require(!saved.containsKey("lookToken")&&!saved.containsKey("lookPreset"),"legacy look flags never written back");((Button)get(raw,"compare")).performClick();TagDetailChecks.require((boolean)get(raw,"before"),"editor original compare retained");((Button)get(raw,"compare")).performClick();return true;});
      GenerationIndicatorChecks.capture(t,"look-removed-editor-"+width);TagDetailChecks.require(snapshot(c).equals(expected),"editor restore preserves legacy bytes and timestamps");
      GenerationIndicatorChecks.await(t,"full editor prepared",()->((RawEditFullSession)get(raw,"fullSession")).isPrepared());
      ui(t,()->{call(raw,"saveImage",new Class<?>[0]);return true;});
      GenerationIndicatorChecks.await(t,"editor JPEG saved",()->!(boolean)get(raw,"saving")&&outputs(c).size()==previous.size()+1);
      Set<Uri> newOutputs=outputs(c);newOutputs.removeAll(previous);owned.addAll(newOutputs);TagDetailChecks.require(newOutputs.size()==1,"one new explicit JPEG output");
      BitmapFactory.Options size=new BitmapFactory.Options();size.inJustDecodeBounds=true;try(InputStream input=c.getContentResolver().openInputStream(newOutputs.iterator().next())){BitmapFactory.decodeStream(input,null,size);}TagDetailChecks.require(size.outWidth==1200&&size.outHeight==800&&"image/jpeg".equals(size.outMimeType),"ordinary full JPEG dimensions read back");
      ui(t,()->{raw.finish();return true;});GenerationIndicatorChecks.await(t,"editor destroyed",raw::isDestroyed);
      TagDetailChecks.require(snapshot(c).equals(expected),"editor finish never deletes legacy sessions");
      try(InputStream input=c.getContentResolver().openInputStream(uri)){TagDetailChecks.require(Arrays.equals(input.readAllBytes(),jpeg),"original synthetic photo unchanged");}
      return "PASS look-removal: normal/tag viewers have no Look controls, saved legacy photo selection does not change source pixels; actual baseline preset/photo/session bytes and timestamps preserved across viewing/editor legacy Bundle restore/finish; ordinary compare and full JPEG save/readback; width="+width+" font="+font+". Synthetic data only, no physical camera/API.\n";
    }finally{
      for(Activity a:new Activity[]{editor,tags,main})if(a!=null)TagDetailChecks.ui(t,a::finish);
      if(tags!=null){TagFixtureIsolation.cleanup(tags);c.deleteDatabase(tagDb);c.deleteDatabase(featureDb);}
      for(Uri uri:new LinkedHashSet<>(owned))c.getContentResolver().delete(uri,null,null);
      for(File file:created)if(!file.delete()&&file.exists())throw new IOException("fixture cleanup failed");
      if(!hadLibrary)new File(c.getFilesDir(),"look-library").delete();if(!hadSessions)new File(c.getFilesDir(),"look-sessions").delete();
    }
  }
}
