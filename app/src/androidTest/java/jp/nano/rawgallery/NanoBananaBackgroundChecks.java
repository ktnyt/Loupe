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
    final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),interrupted=new CountDownLatch(1);
    volatile int submits,statuses,downloads,deletes;volatile boolean deleteFailure,ignoreInterrupt,requireVersion;
    volatile String prompt;volatile NanoBananaOutput output;
    Fake(NanoBananaStore store,byte[] jpeg,List<String> saved){this.store=store;this.jpeg=jpeg;this.saved=saved;}
    public String upload(String object,byte[] bytes,String owner){return "synthetic-version";}
    public String generate(String text,String object,String version,NanoBananaOutput requested){submits++;prompt=text;output=requested;return "synthetic-task";}
    public JSONObject status(String task)throws Exception{
      statuses++;entered.countDown();
      boolean wasInterrupted=false;
      while(true){try{if(!release.await(15,TimeUnit.SECONDS))throw new java.io.IOException("fixture timed out");break;}catch(InterruptedException e){interrupted.countDown();if(!ignoreInterrupt)throw e;wasInterrupted=true;}}
      if(wasInterrupted)Thread.currentThread().interrupt();
      return new JSONObject().put("successFlag",1).put("response",new JSONObject().put("resultImageUrl","https://synthetic.invalid/result.jpg"));
    }
    public byte[] download(String url){downloads++;return jpeg;}
    public void delete(String object,String version,String owner)throws Exception{
      deletes++;JSONObject record=store.job();if(record!=null&&record.has("saved")){synchronized(saved){if(!saved.contains(record.getString("saved")))saved.add(record.getString("saved"));}}
      if(requireVersion&&version==null)throw new java.io.IOException("synthetic object version missing");
      if(deleteFailure){deleteFailure=false;throw new java.io.IOException("synthetic cleanup interruption");}
    }
  }
  private static final class FixtureFactory implements NanoBananaService.Factory {
    final NanoBananaStore store;volatile Fake fake;volatile String lastResult;volatile int jobs;
    FixtureFactory(NanoBananaStore store){this.store=store;}
    public NanoBananaStore store(Context context){return store;}
    public void result(String message){lastResult=message;}
    public NanoBananaJob job(Context context,NanoBananaStore received){
      jobs++;TagDetailChecks.require(received==store&&fake!=null,"isolated service dependencies required");
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
      context.stopService(new Intent(context,NanoBananaService.class));
      TagDetailChecks.require(stopped.interrupted.await(15,TimeUnit.SECONDS),"OS Service destruction interrupted blocked worker before fixture release");test.waitForIdleSync();
      TagDetailChecks.require(NanoBananaService.busy()&&!NanoBananaService.start(screen,photo,prompt),"destroy cannot release a live worker lease");
      stopped.release.countDown();idle(test);JSONObject interruptedRecord=store.job();TagDetailChecks.require(stopped.downloads==0&&interruptedRecord!=null&&interruptedRecord.optString("stage").equals("generating"),"interrupted worker preserves resumable task: downloads="+stopped.downloads+", statuses="+stopped.statuses+", stage="+(interruptedRecord==null?"none":interruptedRecord.optString("stage")));
      // Mark discard before launch; stale/redelivered intents may only execute this cleanup.
      NanoBananaJob.markDiscarding(store);stopped.deleteFailure=true;
      ContextCompat.startForegroundService(context,new Intent(context,NanoBananaService.class).putExtra("request_nonce","stale"));
      await("stale intent started durable discard",()->stopped.deletes==1);idle(test);
      TagDetailChecks.require(store.job().optString("stage").equals("discarding")&&stopped.submits==1&&stopped.downloads==0,"failed discard remains durable without resurrection");
      int savedBeforeDiscard=saved.size(),statusesBeforeDiscard=stopped.statuses;factory.lastResult=null;
      TagDetailChecks.require(NanoBananaService.resume(screen),"discard retry admitted");idle(test);
      TagDetailChecks.require(store.job()==null&&stopped.deletes==2&&stopped.downloads==0,"discard retry only removes temporary object");
      TagDetailChecks.require("後処理が完了しました".equals(factory.lastResult)&&saved.size()==savedBeforeDiscard&&stopped.statuses==statusesBeforeDiscard,"manual discarding resume reports cleanup complete without saving or polling");
      int before=stopped.submits;ContextCompat.startForegroundService(context,new Intent(context,NanoBananaService.class).putExtra("request_nonce","stale"));Thread.sleep(300);test.waitForIdleSync();
      TagDetailChecks.require(!NanoBananaService.busy()&&stopped.submits==before&&store.job()==null,"stale request without durable work never submits");
      // A missing upload version is recoverable only by explicit manual e2 cleanup.
      Fake missing=new Fake(store,jpeg,saved);missing.requireVersion=true;factory.fake=missing;
      String object="loupe-nano-temp/00000000-0000-0000-0000-000000000107.jpg";
      store.job(new JSONObject().put("stage","uploading").put("object",object).put("owner","synthetic-owner"));
      factory.lastResult=null;int savedBeforeMissing=saved.size();
      TagDetailChecks.require(NanoBananaService.discard(screen),"missing-version cleanup admitted");idle(test);
      TagDetailChecks.require(store.job()!=null&&store.job().optString("stage").equals("discarding")&&!store.job().has("version")&&missing.deletes==1&&missing.submits==0&&missing.statuses==0&&missing.downloads==0&&saved.size()==savedBeforeMissing,"missing-version cleanup failure retains recovery location without generation or save");
      TagDetailChecks.ui(test,()->{menu[0]=NanoBananaUi.show(screen,input,store,NanoBananaUi.ACTUAL);View location=findLabel(menu[0].getWindow().getDecorView(),"一時画像の場所");TagDetailChecks.require(location!=null&&location.performClick(),"missing-version recovery exposes temporary-object location");});test.waitForIdleSync();
      android.view.accessibility.AccessibilityNodeInfo location=findAccessibleText(test.getUiAutomation().getRootInActiveWindow(),object);
      TagDetailChecks.require(location!=null&&location.getText()!=null&&location.getText().toString().contains("自動削除できません")&&location.getText().toString().contains("手動削除")&&location.getText().toString().contains("バージョニング")&&location.getText().toString().contains("処理を終了"),"recovery dialog names the object and explains manual cleanup, versioning and retry");
      TagDetailChecks.require(location.getActionList().contains(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION),"temporary-object location text supports selection");
      capture(test,screen,"nano-background-missing-version-"+width+"-"+font);test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);test.waitForIdleSync();
      store.job(new JSONObject().put("stage","submitting").put("object",object).put("owner","synthetic-owner").put("version","synthetic-version"));
      TagDetailChecks.ui(test,()->{menu[0]=NanoBananaUi.show(screen,input,store,NanoBananaUi.ACTUAL);View decor=menu[0].getWindow().getDecorView();TagDetailChecks.require(findLabel(decor,"依頼の結果が不明です。自動では再送しません")!=null&&findLabel(decor,"状態を再確認")==null&&findLabel(decor,"後処理を再試行")==null,"ambiguous submission explicitly says unknown and never offers automatic resend");});
      capture(test,screen,"nano-background-submitting-"+width+"-"+font);TagDetailChecks.ui(test,()->menu[0].dismiss());
      TagDetailChecks.require(missing.submits==0&&missing.statuses==0&&missing.downloads==0,"opening recovery menus performs no provider request");
      // A dialog describes one durable request. Its confirmation must not target a replacement.
      Fake guarded=new Fake(store,jpeg,saved);factory.fake=guarded;
      String objectA="loupe-nano-temp/00000000-0000-0000-0000-00000000010a.jpg",ownerA="synthetic-owner-a";
      String objectB="loupe-nano-temp/00000000-0000-0000-0000-00000000010b.jpg",ownerB="synthetic-owner-b";
      store.job(new JSONObject().put("stage","submitting").put("object",objectA).put("owner",ownerA).put("version","synthetic-version-a"));
      TagDetailChecks.ui(test,()->{menu[0]=NanoBananaUi.show(screen,input,store,NanoBananaUi.ACTUAL);View end=findLabel(menu[0].getWindow().getDecorView(),"処理を終了");TagDetailChecks.require(end!=null&&end.performClick(),"request A opens explicit end confirmation");});test.waitForIdleSync();
      android.view.accessibility.AccessibilityNodeInfo confirmation=findAccessibleExact(test.getUiAutomation().getRootInActiveWindow(),"終了");
      TagDetailChecks.require(confirmation!=null&&confirmation.isClickable(),"request A confirmation is visible before request replacement");
      JSONObject replacement=new JSONObject().put("stage","generating").put("task","synthetic-task-b").put("object",objectB).put("owner",ownerB).put("version","synthetic-version-b");
      store.job(replacement);String replacementText=store.job().toString();int jobsBeforeStale=factory.jobs;
      TagDetailChecks.require(confirmation.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),"stale request A confirmation clicked");test.waitForIdleSync();TagDetailChecks.ui(test,()->{});test.waitForIdleSync();
      TagDetailChecks.require(!NanoBananaService.busy()&&store.job()!=null&&store.job().toString().equals(replacementText)&&factory.jobs==jobsBeforeStale&&guarded.deletes==0&&guarded.submits==0&&guarded.statuses==0&&guarded.downloads==0,"stale confirmation for A leaves request B unchanged without starting service or provider operations");
      TagDetailChecks.require(NanoBananaService.discard(screen,objectB,ownerB),"current request B identity admits cleanup");idle(test);
      TagDetailChecks.require(store.job()==null&&guarded.deletes==1&&guarded.submits==0&&guarded.statuses==0&&guarded.downloads==0&&factory.jobs==jobsBeforeStale+1,"current request cleanup removes B once without generation or polling");
      store.job(null); // Fixture-only record: there is no remote object to delete.
      Context failing=new ContextWrapper(context){public Context getApplicationContext(){return this;}public ComponentName startForegroundService(Intent i){throw new IllegalStateException("synthetic startup rejection");}};
      TagDetailChecks.require(!NanoBananaService.start(failing,photo,prompt)&&!NanoBananaService.busy()&&store.job()==null,"startup rejection rolls back reservation");
      return "PASS nano-background: actual production Android Service with isolated fake provider; next-photo operation, busy/error menu captures, Home continuation, no generation/result Activity launch, automatic JPEG save/readback 1200x800, temporary-object cleanup, duplicate admission, immutable prompt/output snapshot, next admission, saved cleanup retry without POST/download, interrupted-worker lease, durable discard retry and cleanup-only completion text, missing-version selectable manual recovery guidance, explicit ambiguous-submission messaging, stale end-confirmation A/B identity guard and current identity cleanup, stale Intent suppression and startup rejection. Captures are synthetic layouts; OS-service runtime assertions are separate. No real API, credentials, photos, physical touch or TalkBack speech tested.\n";
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
  private static android.view.accessibility.AccessibilityNodeInfo findAccessibleExact(android.view.accessibility.AccessibilityNodeInfo node,String text){
    if(node==null)return null;if(node.getText()!=null&&text.contentEquals(node.getText()))return node;
    for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo found=findAccessibleExact(node.getChild(i),text);if(found!=null)return found;}return null;
  }
  private static android.view.accessibility.AccessibilityNodeInfo findAccessibleText(android.view.accessibility.AccessibilityNodeInfo node,String part){
    if(node==null)return null;if(node.getText()!=null&&node.getText().toString().contains(part))return node;
    for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo found=findAccessibleText(node.getChild(i),part);if(found!=null)return found;}return null;
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
