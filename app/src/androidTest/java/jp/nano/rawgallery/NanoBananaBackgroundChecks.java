package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.core.content.ContextCompat;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Synthetic OS-service integration. All network operations fail closed through an in-process seam. */
final class NanoBananaBackgroundChecks {
  private static final List<String> created=new ArrayList<>();
  static void activityCreated(Activity activity){synchronized(created){created.add(activity.getClass().getName());}}
  interface Check {boolean ok() throws Exception;}
  private static void await(String label,Check check)throws Exception{
    long end=SystemClock.uptimeMillis()+15000;
    while(!check.ok()&&SystemClock.uptimeMillis()<end)Thread.sleep(30);
    TagDetailChecks.require(check.ok(),label);
  }
  private static void idle(Instrumentation test)throws Exception{await("service worker released lease",()->!NanoBananaService.busy());test.waitForIdleSync();}
  private static final class Fake implements NanoBananaJob.Services {
    final NanoBananaStore store;final byte[] jpeg;final List<String> saved;
    final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    volatile int submits,downloads,deletes;volatile boolean deleteFailure,ignoreInterrupt;
    volatile String prompt;volatile NanoBananaOutput output;
    Fake(NanoBananaStore store,byte[] jpeg,List<String> saved){this.store=store;this.jpeg=jpeg;this.saved=saved;}
    public String upload(String object,byte[] bytes,String owner){return "synthetic-version";}
    public String generate(String text,String object,String version,NanoBananaOutput requested){submits++;prompt=text;output=requested;return "synthetic-task";}
    public JSONObject status(String task)throws Exception{
      entered.countDown();
      boolean interrupted=false;
      while(true){try{if(!release.await(15,TimeUnit.SECONDS))throw new java.io.IOException("fixture timed out");break;}catch(InterruptedException e){if(!ignoreInterrupt)throw e;interrupted=true;}}
      if(interrupted)Thread.currentThread().interrupt();
      return new JSONObject().put("successFlag",1).put("response",new JSONObject().put("resultImageUrl","https://synthetic.invalid/result.jpg"));
    }
    public byte[] download(String url){downloads++;return jpeg;}
    public void delete(String object,String version,String owner)throws Exception{
      deletes++;JSONObject record=store.job();if(record!=null&&record.has("saved")){synchronized(saved){if(!saved.contains(record.getString("saved")))saved.add(record.getString("saved"));}}
      if(deleteFailure){deleteFailure=false;throw new java.io.IOException("synthetic cleanup interruption");}
    }
  }
  private static final class FixtureFactory implements NanoBananaService.Factory {
    final NanoBananaStore store;volatile Fake fake;
    FixtureFactory(NanoBananaStore store){this.store=store;}
    public NanoBananaStore store(Context context){return store;}
    public NanoBananaJob job(Context context,NanoBananaStore received){
      TagDetailChecks.require(received==store&&fake!=null,"isolated service dependencies required");
      return new NanoBananaJob(context,store,fake);
    }
  }
  static String run(Instrumentation test,int width,float font)throws Exception{
    Context context=test.getTargetContext();String namespace="nano-background-fixture";
    TagDetailChecks.require(!NanoBananaService.busy(),"no existing service lease before fixture");
    context.stopService(new Intent(context,NanoBananaService.class));test.waitForIdleSync();
    context.getSharedPreferences(namespace,0).edit().clear().commit();
    NanoBananaStore store=new NanoBananaStore(context,namespace);
    FixtureFactory factory=new FixtureFactory(store);List<String> saved=new ArrayList<>();
    MainActivity activity=null;
    Bitmap bitmap=Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff286ca0);
    ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);bitmap.recycle();byte[] jpeg=out.toByteArray();
    NanoBananaStore.Prompt prompt=new NanoBananaStore.Prompt("fixture","色を整える","captured synthetic prompt");
    android.content.ContentValues values=new android.content.ContentValues();
    values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"nano-background-owned-fixture.jpg");
    values.put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
    values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/LoupeSyntheticChecks");
    Uri fixture=context.getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
    TagDetailChecks.require(fixture!=null,"owned fixture row created");
    try(java.io.OutputStream fixtureOut=context.getContentResolver().openOutputStream(fixture)){fixtureOut.write(jpeg);}
    PhotoRepository.Photo photo=new PhotoRepository.Photo(fixture,"nano-background-owned-fixture.jpg","synthetic",jpeg.length,1,1);
    PhotoRepository.Photo second=new PhotoRepository.Photo(fixture,"nano-background-second.jpg","synthetic",jpeg.length,2,2);
    final PhotoRepository.Photo input=photo,next=second;
    NanoBananaService.dependencies=factory;
    try{
      store.setKey("synthetic-only");store.setE2("synthetic-bucket","ABCDEFGHIJKLMNOPQRST","a".repeat(40));store.savePrompt(null,prompt.name,prompt.text);store.setOutput("4:3","1K");
      activity=(MainActivity)test.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      final MainActivity screen=activity;GalleryScreenChecks.finishInitialIndex(test,screen);GalleryScreenChecks.portraitFixture(test,screen,width,font);
      TagDetailChecks.ui(test,()->{RankingResultChecks.set(screen,"photos",new ArrayList<>(List.of(input,next)));RankingResultChecks.set(screen,"burstMembers",null);showPhoto(screen,0);});
      await("synthetic viewer loaded",()->RankingResultChecks.get(screen,"activePreview")!=null);
      // Exercise the actual viewer tag action while the slow service is running below.
      Fake first=new Fake(store,jpeg,saved);factory.fake=first;
      synchronized(created){created.clear();}
      Dialog[] selected={null};
      TagDetailChecks.ui(test,()->selected[0]=NanoBananaUi.show(screen,input,store,NanoBananaUi.ACTUAL));
      capture(test,screen,"nano-background-ready-"+width+"-"+font);
      TagDetailChecks.ui(test,()->{View row=findLabel(selected[0].getWindow().getDecorView(),prompt.name);TagDetailChecks.require(row!=null&&row.performClick(),"prompt selection starts service directly");});
      TagDetailChecks.require(NanoBananaService.busy()&&!selected[0].isShowing(),"prompt selection dismisses menu and admits foreground service");
      TagDetailChecks.require(!NanoBananaService.start(screen,photo,prompt),"duplicate admission rejected");
      store.setOutput("16:9","4K");store.savePrompt(store.prompts().get(0).id,"変更した設定","changed after admission");
      TagDetailChecks.require(first.entered.await(15,TimeUnit.SECONDS),"real OS service reached fake provider");
      TagDetailChecks.require(first.submits==1&&first.prompt.equals(prompt.text)&&first.output.aspectRatio.equals("4:3")&&first.output.resolution.equals("1K"),"admission captures prompt and output before settings edits");
      TagDetailChecks.ui(test,()->{View nextControl=findLabel(screen.getWindow().getDecorView(),"次の写真");TagDetailChecks.require(nextControl!=null&&nextControl.isEnabled()&&nextControl.performClick(),"normal next-photo control responds during generation");});
      await("next photo usable while service waits",()->((Integer)RankingResultChecks.get(screen,"index"))==1);test.waitForIdleSync();
      Instrumentation.ActivityMonitor tags=test.addMonitor(TagActivity.class.getName(),null,false);
      TagDetailChecks.ui(test,()->{View tag=findLabel(screen.getWindow().getDecorView(),"タグ");TagDetailChecks.require(tag!=null&&tag.performClick(),"normal viewer tag control responds during generation");});
      Activity tagScreen=tags.waitForActivityWithTimeout(10000);test.removeMonitor(tags);
      TagDetailChecks.require(tagScreen instanceof TagActivity,"normal tag screen opens during generation");
      TagDetailChecks.ui(test,tagScreen::finish);test.waitForIdleSync();
      synchronized(created){TagDetailChecks.require(created.equals(List.of(TagActivity.class.getName())),"only requested tag navigation occurred while generating");created.clear();}
      capture(test,screen,"nano-background-viewer-"+width+"-"+font);
      Dialog[] menu={null};TagDetailChecks.ui(test,()->menu[0]=NanoBananaUi.show(screen,next,store,NanoBananaUi.ACTUAL));
      capture(test,screen,"nano-background-busy-"+width+"-"+font);TagDetailChecks.ui(test,()->menu[0].dismiss());
      // Home is an actual OS navigation event; the service remains independent of the Activity.
      test.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);test.waitForIdleSync();
      TagDetailChecks.require(NanoBananaService.busy(),"service continues after Home");
      first.release.countDown();idle(test);
      TagDetailChecks.require(first.downloads==1&&first.deletes==1&&store.job()==null,"background completion saves, cleans and clears durable record");
      synchronized(created){TagDetailChecks.require(created.isEmpty(),"generation and completion do not launch another Activity");}
      TagDetailChecks.require(saved.size()==1,"one automatically saved JPEG");verifySaved(context,saved.get(0),1200,800);
      context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));test.waitForIdleSync();
      TagDetailChecks.ui(test,screen::onBackPressed);test.waitForIdleSync();
      // A second request is accepted after automatic completion and exercises saved cleanup recovery.
      Fake cleanup=new Fake(store,jpeg,saved);cleanup.deleteFailure=true;cleanup.release.countDown();factory.fake=cleanup;
      TagDetailChecks.require(NanoBananaService.start(screen,photo,prompt),"next request accepted after completion");idle(test);
      JSONObject pending=store.job();TagDetailChecks.require(pending!=null&&pending.optString("stage").equals("cleanup")&&pending.has("saved"),"cleanup failure retains saved URI");String retained=pending.getString("saved");
      TagDetailChecks.ui(test,()->{showPhoto(screen,0);menu[0]=NanoBananaUi.show(screen,input,store,NanoBananaUi.ACTUAL);});
      capture(test,screen,"nano-background-cleanup-"+width+"-"+font);TagDetailChecks.ui(test,()->menu[0].dismiss());
      TagDetailChecks.ui(test,()->{menu[0]=NanoBananaUi.show(screen,input,store,NanoBananaUi.ACTUAL);View row=findLabel(menu[0].getWindow().getDecorView(),"後処理を再試行");TagDetailChecks.require(row!=null&&row.performClick(),"cleanup retry control responds");});idle(test);
      TagDetailChecks.require(store.job()==null&&cleanup.submits==1&&cleanup.downloads==1&&cleanup.deletes==2,"cleanup retry never POSTs or downloads again");verifySaved(context,retained,1200,800);
      // Stop while a provider ignores interruption: the lease must remain held until it returns.
      Fake stopped=new Fake(store,jpeg,saved);stopped.ignoreInterrupt=true;factory.fake=stopped;
      TagDetailChecks.require(NanoBananaService.start(screen,photo,prompt),"interruption request admitted");TagDetailChecks.require(stopped.entered.await(15,TimeUnit.SECONDS),"worker blocks in provider");
      context.stopService(new Intent(context,NanoBananaService.class));test.waitForIdleSync();
      TagDetailChecks.require(NanoBananaService.busy()&&!NanoBananaService.start(screen,photo,prompt),"destroy cannot release a live worker lease");
      stopped.release.countDown();idle(test);TagDetailChecks.require(stopped.downloads==0&&store.job().optString("stage").equals("generating"),"interrupted worker preserves resumable task");
      // Mark discard before launch; stale/redelivered intents may only execute this cleanup.
      NanoBananaJob.markDiscarding(store);stopped.deleteFailure=true;
      ContextCompat.startForegroundService(context,new Intent(context,NanoBananaService.class).putExtra("request_nonce","stale"));
      await("stale intent started durable discard",()->stopped.deletes==1);idle(test);
      TagDetailChecks.require(store.job().optString("stage").equals("discarding")&&stopped.submits==1&&stopped.downloads==0,"failed discard remains durable without resurrection");
      TagDetailChecks.require(NanoBananaService.resume(screen),"discard retry admitted");idle(test);
      TagDetailChecks.require(store.job()==null&&stopped.deletes==2&&stopped.downloads==0,"discard retry only removes temporary object");
      int before=stopped.submits;ContextCompat.startForegroundService(context,new Intent(context,NanoBananaService.class).putExtra("request_nonce","stale"));Thread.sleep(300);test.waitForIdleSync();
      TagDetailChecks.require(!NanoBananaService.busy()&&stopped.submits==before&&store.job()==null,"stale request without durable work never submits");
      Context failing=new ContextWrapper(context){public Context getApplicationContext(){return this;}public ComponentName startForegroundService(Intent i){throw new IllegalStateException("synthetic startup rejection");}};
      TagDetailChecks.require(!NanoBananaService.start(failing,photo,prompt)&&!NanoBananaService.busy()&&store.job()==null,"startup rejection rolls back reservation");
      return "PASS nano-background: actual production Android Service with isolated fake provider; next-photo operation, busy/error menu captures, Home continuation, no generation/result Activity launch, automatic JPEG save/readback 1200x800, temporary-object cleanup, duplicate admission, immutable prompt/output snapshot, next admission, saved cleanup retry without POST/download, interrupted-worker lease, durable discard retry, stale Intent suppression and startup rejection. Captures are synthetic layouts; OS-service runtime assertions are separate. No real API, credentials, photos, physical touch or TalkBack speech tested.\n";
    }finally{
      if(factory.fake!=null)factory.fake.release.countDown();
      context.stopService(new Intent(context,NanoBananaService.class));
      // Never restore production dependencies while a fixture worker could still run.
      idle(test);context.stopService(new Intent(context,NanoBananaService.class));test.waitForIdleSync();
      if(activity!=null){MainActivity screen=activity;TagDetailChecks.ui(test,screen::finish);}
      JSONObject remaining=store.job();if(remaining!=null&&remaining.has("saved"))saved.add(remaining.getString("saved"));
      for(String uri:saved)context.getContentResolver().delete(Uri.parse(uri),null,null);
      store.job(null);context.getSharedPreferences(namespace,0).edit().clear().commit();context.getContentResolver().delete(fixture,null,null);
      NanoBananaService.dependencies=NanoBananaService.PRODUCTION;
    }
  }
  private static View findLabel(View view,String label){
    if(view.getVisibility()!=View.VISIBLE)return null;
    if(label.equals(view.getContentDescription()==null?null:view.getContentDescription().toString())||view instanceof android.widget.TextView&&label.contentEquals(((android.widget.TextView)view).getText()))return view;
    if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findLabel(group.getChildAt(i),label);if(found!=null)return found;}}return null;
  }
  private static void showPhoto(MainActivity screen,int index){try{Method m=MainActivity.class.getDeclaredMethod("showPhoto",int.class);m.setAccessible(true);m.invoke(screen,index);}catch(Exception e){throw new RuntimeException(e);}}
  private static void verifySaved(Context context,String uri,int width,int height)throws Exception{
    BitmapFactory.Options size=new BitmapFactory.Options();size.inJustDecodeBounds=true;
    try(java.io.InputStream input=context.getContentResolver().openInputStream(Uri.parse(uri))){BitmapFactory.decodeStream(input,null,size);}
    TagDetailChecks.require(size.outWidth==width&&size.outHeight==height&&"image/jpeg".equals(size.outMimeType),"saved JPEG dimensions and format read back from MediaStore");
  }
  private static void capture(Instrumentation test,Activity activity,String name)throws Exception{
    test.waitForIdleSync();Thread.sleep(250);File dir=new File(test.getTargetContext().getCacheDir(),"consistency-checks");dir.mkdirs();
    Bitmap image=test.getUiAutomation().takeScreenshot();try(FileOutputStream output=new FileOutputStream(new File(dir,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,output);}finally{image.recycle();}
  }
}
