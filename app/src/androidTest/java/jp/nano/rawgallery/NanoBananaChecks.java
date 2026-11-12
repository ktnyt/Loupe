package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Isolated local fixture. Never uses production credentials or makes an API call. */
final class NanoBananaChecks {
  static NanoBananaStore fixtureStore;
  static String run(Instrumentation test,int width,float font) throws Exception {
    Context context=test.getTargetContext();String namespace="nano-banana-fixture";
    SharedPreferences prefs=context.getSharedPreferences(namespace,0);prefs.edit().clear().commit();
    NanoBananaStore store=new NanoBananaStore(context,namespace);
    NanoBananaSettingsActivity activity=null;
    try {
      testOutput(context,store,prefs);
      store.setKey("synthetic-credential-only");
      TagDetailChecks.require(store.hasKey()&&store.apiKey().equals("synthetic-credential-only"),"credential roundtrip");
      TagDetailChecks.require(!prefs.getString("credential","").contains("synthetic"),"encrypted credential");
      boolean invalid=false;try{store.setKey("bad\nkey");}catch(java.io.IOException expected){invalid=true;}
      TagDetailChecks.require(invalid,"reject header injection");
      store.savePrompt(null,"色を整える","自然な色に整えてください");
      NanoBananaStore.Prompt first=store.prompts().get(0);
      store.savePrompt(first.id,"色を整える","自然な色と明るさに整えてください");
      TagDetailChecks.require(store.prompts().size()==1&&store.prompts().get(0).text.contains("明るさ"),"edit by stable ID");
      invalid=false;try{store.savePrompt(null,"色を整える","重複");}catch(java.io.IOException expected){invalid=true;}
      TagDetailChecks.require(invalid,"reject duplicate labels");
      store.savePrompt(null,"背景を変える長い名前のプロンプトを編集する","背景を夕暮れの海辺にしてください");
      activity=(NanoBananaSettingsActivity)test.startActivitySync(new Intent(context,NanoBananaSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      final NanoBananaSettingsActivity screen=activity;
      TagDetailChecks.ui(test,()->{
        try {
          Field field=NanoBananaSettingsActivity.class.getDeclaredField("store");field.setAccessible(true);field.set(screen,store);
          android.content.res.Configuration config=new android.content.res.Configuration(screen.getResources().getConfiguration());
          config.fontScale=font;screen.getResources().updateConfiguration(config,screen.getResources().getDisplayMetrics());
          Method render=NanoBananaSettingsActivity.class.getDeclaredMethod("render");render.setAccessible(true);render.invoke(screen);
          screen.getWindow().setLayout(GalleryUi.dp(screen,width),-1);
        }catch(Exception e){throw new RuntimeException(e);}
      });test.waitForIdleSync();Thread.sleep(250);
      View content=((ViewGroup)screen.findViewById(android.R.id.content)).getChildAt(0);
      TagDetailChecks.ui(test,()->{
        TagDetailChecks.require((screen.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0,"secure settings window");
        verifyTargets(content,GalleryUi.dp(screen,48));
      });
      File dir=new File(context.getCacheDir(),"consistency-checks");dir.mkdirs();
      // FLAG_SECURE blocks PixelCopy; draw this synthetic fixture directly for layout review.
      TagDetailChecks.ui(test,()->{
        android.graphics.Bitmap b=android.graphics.Bitmap.createBitmap(content.getWidth(),content.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
        content.draw(new android.graphics.Canvas(b));
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(dir,"nano-settings-"+width+"-"+font+".png"))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}catch(Exception e){throw new RuntimeException(e);}finally{b.recycle();}
      });
      testOutputUi(test,screen,store,width,font);
      testJobs(test,store,width,font);
      test4k(test,store);
      store.deletePrompt(first.id);TagDetailChecks.require(store.prompts().size()==1,"delete one prompt");
      store.deleteKey();TagDetailChecks.require(!store.hasKey(),"delete credential");
      return "PASS Nano Banana isolated encrypted credential roundtrip, input validation, prompt CRUD/duplicate labels, output allowlists/atomic save rollback/defaults/request body, choice dialogs Save/Cancel/restored selection, corrupt non-String preference recovery, scrolled 21:9 selection/save/reopen, settings layout and 48dp controls. 4K 4800x3584 save/readback, sampled preview and 20MP rejection verified. Durable job recovery, ambiguous POST suppression, result save and cleanup retry verified with fake network. No service request or real credentials; physical touch, IME and spoken output unverified.\n";
    }finally{if(activity!=null){final NanoBananaSettingsActivity screen=activity;TagDetailChecks.ui(test,screen::finish);}prefs.edit().clear().commit();}
  }
  private static void testOutput(Context context,NanoBananaStore store,SharedPreferences prefs) throws Exception {
    TagDetailChecks.require(store.output().aspectRatio.equals("auto")&&store.output().resolution.equals("1K"),"legacy output defaults");
    for(String aspect:NanoBananaOutput.ASPECTS)for(String resolution:NanoBananaOutput.RESOLUTIONS){
      store.setOutput(aspect,resolution);
      NanoBananaOutput restored=new NanoBananaStore(context,"nano-banana-fixture").output();
      org.json.JSONObject body=NanoBananaApi.request("fixture","https://synthetic.invalid/input",restored);
      TagDetailChecks.require(body.getString("aspectRatio").equals(aspect)&&body.getString("resolution").equals(resolution)&&body.getString("outputFormat").equals("jpg"),"all allowed output values persist and reach API body");
    }
    store.setOutput("4:3","2K");
    for(String[] pair:new String[][]{{"bad","4K"},{"auto","8K"},{null,"1K"},{"AUTO","1K"}}){
      boolean rejected=false;try{store.setOutput(pair[0],pair[1]);}catch(java.io.IOException expected){rejected=true;}
      TagDetailChecks.require(rejected&&store.output().aspectRatio.equals("4:3")&&store.output().resolution.equals("2K"),"invalid save preserves pair");
    }
    TagDetailChecks.require(NanoBananaOutput.fromJob(new org.json.JSONObject()).resolution.equals("1K"),"old job defaults independently of settings");
    boolean rejected=false;try{NanoBananaOutput.fromJob(new org.json.JSONObject().put("output",new org.json.JSONObject().put("aspectRatio","invalid").put("resolution","4K")));}catch(Exception expected){rejected=true;}
    TagDetailChecks.require(rejected,"invalid job output rejected before request");
    // SharedPreferences updates memory even when commit fails; simulate that exact failure.
    SharedPreferences failing=(SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(proxy,method,args)->{
      Object result=method.invoke(prefs,args);
      if(!method.getName().equals("edit"))return result;
      SharedPreferences.Editor editor=(SharedPreferences.Editor)result;
      return java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class[]{SharedPreferences.Editor.class},(p,m,a)->{
        Object edited=m.invoke(editor,a);
        if(m.getName().equals("commit"))return false;
        return edited==editor?p:edited;
      });
    });
    rejected=false;try{new NanoBananaStore(failing).setOutput("16:9","4K");}catch(java.io.IOException expected){rejected=true;}
    TagDetailChecks.require(rejected&&store.output().aspectRatio.equals("4:3")&&store.output().resolution.equals("2K"),"failed atomic save restores original pair");
    prefs.edit().putString("output","{broken").commit();
    rejected=false;try{store.output();}catch(java.io.IOException expected){rejected=true;}
    TagDetailChecks.require(rejected,"corrupt stored output rejected");store.setOutput("auto","1K");
  }
  private static final class LinearLayoutSearch {
    static void findOutput(android.widget.ScrollView scroll){
      ViewGroup column=(ViewGroup)scroll.getChildAt(0);
      for(int i=0;i<column.getChildCount();i++){
        View child=column.getChildAt(i);
        if(child instanceof android.widget.TextView&&((android.widget.TextView)child).getText().toString().equals("出力")){scroll.scrollTo(0,child.getTop());return;}
      }
      throw new AssertionError("output section missing");
    }
  }
  private static void testOutputUi(Instrumentation test,NanoBananaSettingsActivity screen,NanoBananaStore store,int width,float font) throws Exception {
    File dir=new File(test.getTargetContext().getCacheDir(),"consistency-checks");
    TagDetailChecks.ui(test,()->{
      android.widget.ScrollView scroll=(android.widget.ScrollView)((ViewGroup)screen.findViewById(android.R.id.content)).getChildAt(0);
      LinearLayoutSearch.findOutput(scroll);
      android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(scroll.getWidth(),scroll.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);scroll.draw(new android.graphics.Canvas(bitmap));
      try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(dir,"nano-output-settings-"+width+"-"+font+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}catch(Exception e){throw new RuntimeException(e);}finally{bitmap.recycle();}
      scroll.scrollTo(0,0);
    });
    // A malformed preference type must be repairable through the same Save listener as normal edits.
    test.getTargetContext().getSharedPreferences("nano-banana-fixture",0).edit().putInt("output",1).commit();
    androidx.appcompat.app.AlertDialog[] recovery={null};
    TagDetailChecks.ui(test,()->{try{
      Method render=NanoBananaSettingsActivity.class.getDeclaredMethod("render");render.setAccessible(true);render.invoke(screen);
      ViewGroup column=(ViewGroup)((android.widget.ScrollView)((ViewGroup)screen.findViewById(android.R.id.content)).getChildAt(0)).getChildAt(0);
      boolean repair=false;for(int i=0;i<column.getChildCount();i++)if(column.getChildAt(i) instanceof Button&&((Button)column.getChildAt(i)).getText().toString().equals("解像度: 1K"))repair=true;
      TagDetailChecks.require(repair,"corrupt preference still offers output repair");
      Method edit=NanoBananaSettingsActivity.class.getDeclaredMethod("editOutput",boolean.class,NanoBananaOutput.class);edit.setAccessible(true);
      recovery[0]=(androidx.appcompat.app.AlertDialog)edit.invoke(screen,false,NanoBananaOutput.DEFAULT);
    }catch(Exception e){throw new RuntimeException(e);}});test.waitForIdleSync();
    TagDetailChecks.ui(test,()->{recovery[0].getListView().performItemClick(recovery[0].getListView().getChildAt(1),1,1);recovery[0].getButton(-1).performClick();});test.waitForIdleSync();
    TagDetailChecks.require(store.output().resolution.equals("2K"),"non-String preference repaired by dialog Save");
    TagDetailChecks.ui(test,()->{
      ViewGroup column=(ViewGroup)((android.widget.ScrollView)((ViewGroup)screen.findViewById(android.R.id.content)).getChildAt(0)).getChildAt(0);
      boolean displayed=false;for(int i=0;i<column.getChildCount();i++)if(column.getChildAt(i) instanceof Button&&((Button)column.getChildAt(i)).getText().toString().equals("解像度: 2K"))displayed=true;
      TagDetailChecks.require(displayed,"repaired output re-rendered as 2K");
    });
    store.setOutput("auto","1K");
    for(boolean aspect:new boolean[]{true,false}){
      androidx.appcompat.app.AlertDialog[] opened={null};
      TagDetailChecks.ui(test,()->{try{
        Method edit=NanoBananaSettingsActivity.class.getDeclaredMethod("editOutput",boolean.class,NanoBananaOutput.class);edit.setAccessible(true);
        opened[0]=(androidx.appcompat.app.AlertDialog)edit.invoke(screen,aspect,store.output());
      }catch(Exception e){throw new RuntimeException(e);}});test.waitForIdleSync();Thread.sleep(250);
      androidx.appcompat.app.AlertDialog dialog=opened[0];
      TagDetailChecks.ui(test,()->{
        TagDetailChecks.require(dialog.getListView().getCheckedItemPosition()==0,"dialog restores initial selection");
        TagDetailChecks.require((dialog.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0,"secure output dialog");
        for(int i=0;i<dialog.getListView().getChildCount();i++)TagDetailChecks.require(dialog.getListView().getChildAt(i).getHeight()>=GalleryUi.dp(screen,48),"48dp output choices");
        View decor=dialog.getWindow().getDecorView();
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);decor.draw(new android.graphics.Canvas(bitmap));
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(dir,"nano-output-"+(aspect?"aspect":"resolution")+"-"+width+"-"+font+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}catch(Exception e){throw new RuntimeException(e);}finally{bitmap.recycle();}
        dialog.getListView().performItemClick(dialog.getListView().getChildAt(1),1,1);
        dialog.getButton(-2).performClick();
      });
      TagDetailChecks.require(store.output().aspectRatio.equals("auto")&&store.output().resolution.equals("1K"),"cancel preserves output");
      TagDetailChecks.ui(test,()->{try{
        Method edit=NanoBananaSettingsActivity.class.getDeclaredMethod("editOutput",boolean.class,NanoBananaOutput.class);edit.setAccessible(true);
        opened[0]=(androidx.appcompat.app.AlertDialog)edit.invoke(screen,aspect,store.output());
      }catch(Exception e){throw new RuntimeException(e);}});test.waitForIdleSync();
      TagDetailChecks.ui(test,()->{opened[0].getListView().performItemClick(opened[0].getListView().getChildAt(1),1,1);opened[0].getButton(-1).performClick();});
      TagDetailChecks.require((aspect?store.output().aspectRatio:store.output().resolution).equals(aspect?"1:1":"2K"),"Save commits selected output");
      TagDetailChecks.ui(test,()->{try{
        Method edit=NanoBananaSettingsActivity.class.getDeclaredMethod("editOutput",boolean.class,NanoBananaOutput.class);edit.setAccessible(true);
        opened[0]=(androidx.appcompat.app.AlertDialog)edit.invoke(screen,aspect,store.output());
        TagDetailChecks.require(opened[0].getListView().getCheckedItemPosition()==1,"reopen restores committed selection");opened[0].getButton(-2).performClick();
      }catch(Exception e){throw new RuntimeException(e);}});
      store.setOutput("auto","1K");
    }
    androidx.appcompat.app.AlertDialog[] tail={null};
    TagDetailChecks.ui(test,()->{try{
      Method edit=NanoBananaSettingsActivity.class.getDeclaredMethod("editOutput",boolean.class,NanoBananaOutput.class);edit.setAccessible(true);
      tail[0]=(androidx.appcompat.app.AlertDialog)edit.invoke(screen,true,store.output());
    }catch(Exception e){throw new RuntimeException(e);}});test.waitForIdleSync();
    final int last=NanoBananaOutput.ASPECTS.size()-1;
    TagDetailChecks.ui(test,()->tail[0].getListView().smoothScrollToPosition(last));
    boolean[] visible={false};long deadline=android.os.SystemClock.uptimeMillis()+10000;
    while(!visible[0]&&android.os.SystemClock.uptimeMillis()<deadline){
      TagDetailChecks.ui(test,()->{
        android.widget.ListView list=tail[0].getListView();View row=list.getChildAt(last-list.getFirstVisiblePosition());
        visible[0]=list.getLastVisiblePosition()==last&&row!=null&&row.getTop()>=0&&row.getBottom()<=list.getHeight();
      });if(!visible[0])Thread.sleep(50);
    }
    TagDetailChecks.require(visible[0],"aspect list scroll reaches fully visible 21:9");
    TagDetailChecks.ui(test,()->{
      android.widget.ListView list=tail[0].getListView();View row=list.getChildAt(last-list.getFirstVisiblePosition());
      TagDetailChecks.require(row instanceof android.widget.TextView&&((android.widget.TextView)row).getText().toString().equals("21:9"),"last attached aspect is 21:9");
      TagDetailChecks.require(row.getHeight()>=GalleryUi.dp(screen,48),"last aspect has 48dp target");
      list.performItemClick(row,last,list.getAdapter().getItemId(last));
      TagDetailChecks.require(list.getCheckedItemPosition()==last,"21:9 selected by attached row");
      View decor=tail[0].getWindow().getDecorView();android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);decor.draw(new android.graphics.Canvas(bitmap));
      try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(dir,"nano-output-aspect-tail-"+width+"-"+font+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}catch(Exception e){throw new RuntimeException(e);}finally{bitmap.recycle();}
      tail[0].getButton(-1).performClick();
    });
    TagDetailChecks.require(store.output().aspectRatio.equals("21:9"),"21:9 saved after list scroll");
    TagDetailChecks.ui(test,()->{try{
      Method edit=NanoBananaSettingsActivity.class.getDeclaredMethod("editOutput",boolean.class,NanoBananaOutput.class);edit.setAccessible(true);
      tail[0]=(androidx.appcompat.app.AlertDialog)edit.invoke(screen,true,store.output());
      TagDetailChecks.require(tail[0].getListView().getCheckedItemPosition()==last,"reopened dialog retains 21:9 selection");
      tail[0].getButton(-2).performClick();
    }catch(Exception e){throw new RuntimeException(e);}});
    store.setOutput("auto","1K");
  }
  private static void testJobs(Instrumentation test,NanoBananaStore store,int width,float font) throws Exception {
    Context context=test.getTargetContext();
    File source=new File(context.getCacheDir(),"nano-fixture-input.jpg");
    android.graphics.Bitmap b=android.graphics.Bitmap.createBitmap(3000,2000,android.graphics.Bitmap.Config.ARGB_8888);
    b.eraseColor(0xff2468ac);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
    b.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,out);b.recycle();byte[] jpeg=out.toByteArray();
    try(java.io.FileOutputStream file=new java.io.FileOutputStream(source)){file.write(jpeg);}
    PhotoRepository.Photo photo=new PhotoRepository.Photo(android.net.Uri.fromFile(source),"nano-fixture-input.jpg","synthetic",jpeg.length,1,1);
    NanoBananaStore.Prompt prompt=new NanoBananaStore.Prompt("fixture","Fixture","Edit synthetic image");
    class Fake implements NanoBananaJob.Services {
      int submits,downloads,deletes; boolean ambiguous,queryFailure=true,deleteFailure=true;
      public String upload(String key,byte[] bytes,String owner)throws Exception{
        android.graphics.BitmapFactory.Options size=new android.graphics.BitmapFactory.Options();size.inJustDecodeBounds=true;
        android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,size);
        TagDetailChecks.require(Math.max(size.outWidth,size.outHeight)<=1600,"1600px upload bound");store.setOutput("16:9","4K");return "fixture-v1";
      }
      public String generate(String text,String key,String version,NanoBananaOutput output)throws Exception{
        TagDetailChecks.require(output.aspectRatio.equals("4:3")&&output.resolution.equals("1K"),"POST uses captured output, not changed settings");submits++;if(ambiguous)throw new java.io.IOException("synthetic response loss");return "fixture-task";}
      public org.json.JSONObject status(String task)throws Exception{
        TagDetailChecks.require(task.equals("fixture-task"),"resume same task");
        if(queryFailure){queryFailure=false;throw new java.io.IOException("synthetic disconnect");}
        return new org.json.JSONObject().put("successFlag",1).put("response",new org.json.JSONObject().put("resultImageUrl","https://synthetic.invalid/result.jpg"));
      }
      public byte[] download(String url){downloads++;return jpeg;}
      public void delete(String key,String version,String owner)throws Exception{deletes++;if(deleteFailure){deleteFailure=false;throw new java.io.IOException("synthetic cleanup failure");}}
    }
    String object="loupe-nano-temp/00000000-0000-0000-0000-000000000000.jpg";
    String xml="<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Prefix>"+object+"</Prefix><IsTruncated>false</IsTruncated><Version><Key>"+object+"</Key><VersionId>fixture-v1</VersionId></Version></ListVersionsResult>";
    TagDetailChecks.require(NanoBananaE2.listedVersions(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8),object).equals(java.util.List.of("fixture-v1")),"Android XML version parser");
    boolean unavailable=false;try{NanoBananaJob.pendingState(null);}catch(java.io.IOException expected){unavailable=true;}
    TagDetailChecks.require(unavailable,"unavailable provider must stop before deletion");
    try(android.database.MatrixCursor cursor=new android.database.MatrixCursor(new String[]{"is_pending"})){
      TagDetailChecks.require(NanoBananaJob.pendingState(cursor)==-1,"absent row is separate from unavailable provider");
      cursor.addRow(new Object[]{0});TagDetailChecks.require(NanoBananaJob.pendingState(cursor)==0,"published row preserved");
    }
    String saved=null;
    try {
      TagDetailChecks.require(NanoBananaApi.resultUrl("https://tempfile.aiquickdraw.com/result.jpg").getHost().equals("tempfile.aiquickdraw.com"),"provider CDN allowed");
      for(String url:java.util.List.of("https://127.0.0.1/x","https://[fd00::1]/x","https://100.64.0.1/x","https://evil.example/x","http://file.aiquickdraw.com/x","https://file.aiquickdraw.com.evil.example/x","https://user@file.aiquickdraw.com/x")){
        boolean denied=false;try{NanoBananaApi.resultUrl(url);}catch(Exception expected){denied=true;}TagDetailChecks.require(denied,"reject non-provider result URL");
      }
      org.json.JSONObject request=NanoBananaApi.request("fixture prompt","https://synthetic.invalid/input",NanoBananaOutput.DEFAULT);
      TagDetailChecks.require(request.getJSONArray("imageUrls").length()==1&&request.getString("resolution").equals("1K")&&!request.getBoolean("googleSearch")&&!request.has("callBackUrl"),"API request contract");
      store.setOutput("4:3","1K");
      Fake fake=new Fake();NanoBananaJob job=new NanoBananaJob(context,store,fake);
      boolean failed=false;try{job.run(photo,prompt,m->{});}catch(java.io.IOException expected){failed=true;}
      TagDetailChecks.require(failed&&store.job().getString("stage").equals("generating")&&fake.submits==1,"query failure preserves task");
      TagDetailChecks.require(NanoBananaOutput.fromJob(store.job()).resolution.equals("1K")&&store.output().resolution.equals("4K"),"durable snapshot survives settings change");
      TagDetailChecks.require(NanoBananaOutput.fromJob(new NanoBananaStore(context,"nano-banana-fixture").job()).resolution.equals("1K"),"recreated store restores job output");
      failed=false;try{job.run(null,null,m->{});}catch(java.io.IOException expected){failed=true;}
      saved=store.job().getString("saved");
      TagDetailChecks.require(failed&&store.job().getString("stage").equals("cleanup")&&fake.submits==1,"saved output survives cleanup failure");
      job.run(null,null,m->{});
      TagDetailChecks.require(store.job().getString("stage").equals("complete")&&fake.submits==1&&fake.downloads==1&&fake.deletes==2,"cleanup retry never regenerates or resaves");
      captureRun(test,store,width,font);
      org.json.JSONObject published=store.job();published.remove("saved");published.put("stage","generating");store.job(published);
      job.discard();
      try(android.database.Cursor cursor=context.getContentResolver().query(android.net.Uri.parse(saved),new String[]{"_id"},null,null,null)){
        TagDetailChecks.require(cursor!=null&&cursor.moveToFirst(),"discard preserves already published result after crash");
      }
      store.setE2("synthetic-bucket","ABCDEFGHIJKLMNOPQRST","a".repeat(40));
      store.job(new org.json.JSONObject().put("stage","uploading").put("object","loupe-nano-temp/00000000-0000-0000-0000-000000000000.jpg"));
      store.setE2("synthetic-bucket","ABCDEFGHIJKLMNOPQRST","b".repeat(40));
      boolean blocked=false;try{store.setE2("different-bucket","ABCDEFGHIJKLMNOPQRST","b".repeat(40));}catch(java.io.IOException expected){blocked=true;}
      TagDetailChecks.require(blocked,"cleanup destination immutable while credentials remain correctable");
      NanoBananaJob.BUSY.set(true);
      blocked=false;try{store.setE2("synthetic-bucket","ABCDEFGHIJKLMNOPQRST","c".repeat(40));}catch(java.io.IOException expected){blocked=true;}finally{NanoBananaJob.BUSY.set(false);}
      TagDetailChecks.require(blocked,"busy request locks credential snapshot");
      store.job(null);
      store.setOutput("4:3","1K");
      Fake ambiguous=new Fake();ambiguous.ambiguous=true;ambiguous.deleteFailure=false;
      NanoBananaJob uncertain=new NanoBananaJob(context,store,ambiguous);
      try{uncertain.run(photo,prompt,m->{});}catch(java.io.IOException expected){}
      TagDetailChecks.require(store.job().getString("stage").equals("submitting"),"persist ambiguous POST boundary");
      try{uncertain.run(null,null,m->{});}catch(java.io.IOException expected){}
      TagDetailChecks.require(ambiguous.submits==1,"ambiguous POST never resubmitted");
      uncertain.discard();TagDetailChecks.require(store.job()==null&&ambiguous.deletes==1,"explicit cleanup clears ambiguous job");
      captureViewer(test,store,photo,width,font);
    }finally{
      if(saved!=null)context.getContentResolver().delete(android.net.Uri.parse(saved),null,null);
      store.job(null);source.delete();
    }
  }
  private static void test4k(Instrumentation test,NanoBananaStore store) throws Exception {
    Context context=test.getTargetContext();
    android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(4800,3584,android.graphics.Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(0xff123456);java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,92,bytes);bitmap.recycle();
    NanoBananaJob.Services network=new NanoBananaJob.Services(){
      public String upload(String object,byte[] data,String owner){throw new AssertionError("resume must not upload");}
      public String generate(String prompt,String object,String version,NanoBananaOutput output){throw new AssertionError("resume must not POST");}
      public org.json.JSONObject status(String task)throws Exception{return new org.json.JSONObject().put("successFlag",1).put("response",new org.json.JSONObject().put("resultImageUrl","https://synthetic.invalid/4k"));}
      public byte[] download(String url){return bytes.toByteArray();}
      public void delete(String object,String version,String owner){}
    };
    String saved=null;
    try {
      store.job(new org.json.JSONObject().put("stage","generating").put("task","fixture-4k").put("object","fixture-4k").put("owner","fixture").put("version","fixture-v1").put("output",NanoBananaOutput.of("4:3","4K").json()));
      new NanoBananaJob(context,store,network).run(null,null,m->{});saved=store.job().getString("saved");
      android.graphics.BitmapFactory.Options size=new android.graphics.BitmapFactory.Options();size.inJustDecodeBounds=true;
      try(java.io.InputStream in=context.getContentResolver().openInputStream(android.net.Uri.parse(saved))){android.graphics.BitmapFactory.decodeStream(in,null,size);}
      TagDetailChecks.require(size.outWidth==4800&&size.outHeight==3584,"4K result saved without changing dimensions");
      android.graphics.Bitmap preview=NanoBananaActivity.resultPreview(context,android.net.Uri.parse(saved));
      TagDetailChecks.require(preview!=null&&Math.max(preview.getWidth(),preview.getHeight())<=1600,"4K display preview samples without changing saved JPEG");preview.recycle();
      // Bounds-only decode rejects excessive dimensions before allocating a result bitmap.
      android.graphics.Bitmap excessive=android.graphics.Bitmap.createBitmap(5000,4100,android.graphics.Bitmap.Config.ARGB_8888);
      bytes.reset();excessive.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,bytes);excessive.recycle();
      store.job(new org.json.JSONObject().put("stage","generating").put("task","fixture-oversize").put("object","fixture").put("owner","fixture"));
      boolean rejected=false;try{new NanoBananaJob(context,store,network).run(null,null,m->{});}catch(java.io.IOException expected){rejected=true;}
      TagDetailChecks.require(rejected&&!store.job().has("saved"),"result above 20MP rejected before save");
    }finally{if(saved!=null)context.getContentResolver().delete(android.net.Uri.parse(saved),null,null);store.job(null);}
  }
  private static void captureRun(Instrumentation test,NanoBananaStore store,int width,float font) throws Exception {
    org.json.JSONObject complete=store.job();
    captureRunState(test,store,width,font,"nano-result");
    store.job(new org.json.JSONObject(complete.toString()).put("stage","generating"));
    captureRunState(test,store,width,font,"nano-resume");
    store.job(complete);
  }
  private static void captureRunState(Instrumentation test,NanoBananaStore store,int width,float font,String name) throws Exception {
    fixtureStore=store;NanoBananaActivity activity=null;
    try {
      activity=(NanoBananaActivity)test.startActivitySync(new Intent(test.getTargetContext(),NanoBananaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      final NanoBananaActivity screen=activity;
      TagDetailChecks.ui(test,()->{
        android.content.res.Configuration config=new android.content.res.Configuration(screen.getResources().getConfiguration());config.fontScale=font;
        screen.getResources().updateConfiguration(config,screen.getResources().getDisplayMetrics());screen.getWindow().setLayout(GalleryUi.dp(screen,width),-1);
      });test.waitForIdleSync();Thread.sleep(250);
      File dir=new File(test.getTargetContext().getCacheDir(),"consistency-checks");dir.mkdirs();
      View content=((ViewGroup)screen.findViewById(android.R.id.content)).getChildAt(0);
      ConsistencyLayoutChecks.captureHardware(test,screen,content,dir,name+"-"+width+"-"+font);
    }finally{if(activity!=null){final NanoBananaActivity screen=activity;TagDetailChecks.ui(test,screen::finish);}fixtureStore=null;}
  }
  private static void captureViewer(Instrumentation test,NanoBananaStore store,PhotoRepository.Photo photo,int width,float font) throws Exception {
    MainActivity activity=(MainActivity)test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      GalleryScreenChecks.finishInitialIndex(test,activity);GalleryScreenChecks.portraitFixture(test,activity,width,font);
      TagDetailChecks.ui(test,()->{
        RankingResultChecks.set(activity,"photos",new java.util.ArrayList<>(java.util.List.of(photo)));
        RankingResultChecks.set(activity,"burstMembers",null);
        try{Method m=MainActivity.class.getDeclaredMethod("showPhoto",int.class);m.setAccessible(true);m.invoke(activity,0);}catch(Exception e){throw new RuntimeException(e);}
      });
      long until=android.os.SystemClock.uptimeMillis()+10000;boolean[] ready={false};
      while(!ready[0]&&android.os.SystemClock.uptimeMillis()<until){TagDetailChecks.ui(test,()->ready[0]=RankingResultChecks.get(activity,"activePreview")!=null);Thread.sleep(40);}
      TagDetailChecks.require(ready[0],"synthetic photo viewer loaded");
      File dir=new File(test.getTargetContext().getCacheDir(),"consistency-checks");dir.mkdirs();
      View content=((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);
      ConsistencyLayoutChecks.captureHardware(test,activity,content,dir,"nano-viewer-"+width+"-"+font);
      TagDetailChecks.ui(test,()->NanoBananaUi.show(activity,photo,store));test.waitForIdleSync();Thread.sleep(350);
      java.io.File target=new File(dir,"nano-menu-"+width+"-"+font+".png");
      android.graphics.Bitmap screenshot=test.getUiAutomation().takeScreenshot();
      try(java.io.FileOutputStream out=new java.io.FileOutputStream(target)){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{screenshot.recycle();}
      TagDetailChecks.ui(test,()->activity.onBackPressed());
    }finally{TagDetailChecks.ui(test,activity::finish);}
  }
  private static void verifyTargets(View v,int min) {
    if(v instanceof Button&&v.getVisibility()==View.VISIBLE)TagDetailChecks.require(v.getHeight()>=min,"48dp target");
    if(v instanceof EditText)TagDetailChecks.require(!v.isSaveEnabled(),"credential state saving disabled");
    if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)verifyTargets(g.getChildAt(i),min);}
  }
}
