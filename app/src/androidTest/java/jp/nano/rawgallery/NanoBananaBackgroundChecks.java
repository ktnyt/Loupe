package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.core.content.ContextCompat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import jp.nano.rawgallery.camera.MediaSaver;
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
    volatile boolean statusTimedOut,statusExited;
    volatile long statusEnteredAt;
    volatile String prompt;volatile NanoBananaOutput output;
    Fake(NanoBananaStore store,byte[] jpeg,List<String> saved){this.store=store;this.jpeg=jpeg;this.saved=saved;}
    public String upload(String object,byte[] bytes,String owner){return "synthetic-version";}
    public String generate(String text,String object,String version,NanoBananaOutput requested){submits++;prompt=text;output=requested;return "synthetic-task";}
    public JSONObject status(String task)throws Exception{
      statuses++;statusEnteredAt=SystemClock.elapsedRealtime();entered.countDown();
      // Finish when the fixture releases the provider. The watchdog covers all UI
      // actions and captures, which can exceed a single assertion's timeout.
      long deadline=statusEnteredAt+TimeUnit.SECONDS.toMillis(120);
      boolean wasInterrupted=false;
      try {
        while(true) {
          try {
            long remaining=deadline-SystemClock.elapsedRealtime();
            if(remaining>0&&release.await(remaining,TimeUnit.MILLISECONDS))break;
            statusTimedOut=true;
            throw new IOException("synthetic provider release watchdog expired");
          } catch(InterruptedException e) {
            interrupted.countDown();
            if(!ignoreInterrupt)throw e;
            wasInterrupted=true;
          }
        }
        if(wasInterrupted)Thread.currentThread().interrupt();
        return new JSONObject().put("successFlag",1).put("response",new JSONObject().put("resultImageUrl","https://synthetic.invalid/result.jpg"));
      } finally {
        statusExited=true;
      }
    }
    public byte[] download(String url){downloads++;return jpeg;}
    public void delete(String object,String version,String owner)throws Exception{
      deletes++;JSONObject record=store.job();if(record!=null&&record.has("saved")){synchronized(saved){if(!saved.contains(record.getString("saved")))saved.add(record.getString("saved"));}}
      if(requireVersion&&version==null)throw new java.io.IOException("synthetic object version missing");
      if(deleteFailure){deleteFailure=false;throw new java.io.IOException("synthetic cleanup interruption");}
    }
  }
  private static final class FixtureFactory implements NanoBananaService.Factory {
    final NanoBananaStore store;volatile Fake fake;volatile String lastResult;volatile int jobs;volatile long lastMessageAt;
    final List<String> messages=new java.util.concurrent.CopyOnWriteArrayList<>();
    FixtureFactory(NanoBananaStore store){this.store=store;}
    public NanoBananaStore store(Context context){return store;}
    public void result(String message){lastResult=message;lastMessageAt=SystemClock.uptimeMillis();messages.add(message);}
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
      verifyMtpDeletionProof();
      verifyDiscardWithDetachedResult(context,store,new Fake(store,jpeg,saved));
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
      TagDetailChecks.ui(test,()->{View row=findLabel(selected[0].getWindow().getDecorView(),prompt.name);verifyMenuTargets(selected[0].getWindow().getDecorView());TagDetailChecks.require(row!=null&&row.performClick(),"prompt selection starts service directly");});
      TagDetailChecks.require(NanoBananaService.busy()&&!selected[0].isShowing(),"prompt selection dismisses menu and admits foreground service");
      TagDetailChecks.require(!NanoBananaService.start(screen,photo,prompt),"duplicate admission rejected");
      store.setOutput("16:9","4K");store.savePrompt(store.prompts().get(0).id,"変更した設定","changed after admission");
      TagDetailChecks.require(first.entered.await(15,TimeUnit.SECONDS),"real OS service reached fake provider");
      Thread timingWatch = watchTiming(first, Thread.currentThread());
      timing(first, "provider entered");
      TagDetailChecks.require(first.submits==1&&first.prompt.equals(prompt.text)&&first.output.aspectRatio.equals("4:3")&&first.output.resolution.equals("1K"),"admission captures prompt and output before settings edits");
      TagDetailChecks.ui(test,()->{View more=findLabel(screen.getWindow().getDecorView(),"写真のその他の操作");TagDetailChecks.require(more!=null&&more.performClick(),"open current viewer action sheet");View nextControl=findWindowLabel("次の写真");TagDetailChecks.require(nextControl!=null&&nextControl.isEnabled()&&nextControl.performClick(),"normal next-photo control responds during generation");});
      timing(first, "begin next photo idle");
      await("next photo usable while service waits",()->((Integer)RankingResultChecks.get(screen,"index"))==1);test.waitForIdleSync();
      timing(first, "end next photo idle");
      Instrumentation.ActivityMonitor tags=test.addMonitor(TagActivity.class.getName(),null,false);
      TagDetailChecks.ui(test,()->{View more=findLabel(screen.getWindow().getDecorView(),"写真のその他の操作");TagDetailChecks.require(more!=null&&more.performClick(),"open current viewer tag sheet");View tag=findWindowLabel("タグ");TagDetailChecks.require(tag!=null&&tag.performClick(),"normal viewer tag control responds during generation");});
      timing(first, "begin tag activity monitor");
      Activity tagScreen=tags.waitForActivityWithTimeout(10000);test.removeMonitor(tags);
      timing(first, "end tag activity monitor");
      TagDetailChecks.require(tagScreen instanceof TagActivity,"normal tag screen opens during generation");
      timing(first, "begin tag close idle");
      TagDetailChecks.ui(test,tagScreen::finish);test.waitForIdleSync();
      timing(first, "end tag close idle");
      synchronized(created){TagDetailChecks.require(created.equals(List.of(TagActivity.class.getName())),"only requested tag navigation occurred while generating");created.clear();}
      timing(first, "begin viewer capture");
      capture(test,screen,"nano-background-viewer-"+width+"-"+font);
      timing(first, "end viewer capture");
      Dialog[] menu={null};TagDetailChecks.ui(test,()->{menu[0]=NanoBananaUi.show(screen,next,store,NanoBananaUi.ACTUAL);});
      timing(first, "begin busy capture and close");
      capture(test,screen,"nano-background-busy-"+width+"-"+font);TagDetailChecks.ui(test,()->{verifyMenuTargets(menu[0].getWindow().getDecorView());menu[0].dismiss();});
      timing(first, "end busy capture and close");
      // Verify both sides of OS navigation while the provider expressly remains blocked.
      timing(first, "begin before Home");
      requireGenerationBlocked(store,first,"before Home");
      timing(first, "end before Home");
      TagDetailChecks.require(test.getUiAutomation().performGlobalAction(
          android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME),"Android accepted Home");
      test.waitForIdleSync();
      timing(first, "begin after Home");
      requireGenerationBlocked(store,first,"after Home");
      timing(first, "end after Home");
      long homeWaitMs=SystemClock.elapsedRealtime()-first.statusEnteredAt;
      timingWatch.interrupt();
      first.release.countDown();idle(test);
      TagDetailChecks.require(first.downloads==1&&first.deletes==1&&store.job()==null,"background completion saves, cleans and clears durable record");
      synchronized(created){TagDetailChecks.require(created.isEmpty(),"generation and completion do not launch another Activity");}
      TagDetailChecks.require(factory.messages.equals(List.of("画像の生成を開始しました","画像を生成しています","生成画像を保存しました")),"initial prompt/busy/completion Toast order exactly once: "+factory.messages);
      TagDetailChecks.require(saved.size()==1,"one automatically saved JPEG");verifySaved(context,saved.get(0),1200,800);
      context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));test.waitForIdleSync();
      TagDetailChecks.ui(test,screen::onBackPressed);test.waitForIdleSync();
      // A second request is accepted after automatic completion and exercises saved cleanup recovery.
      int cleanupToastStart=factory.messages.size();
      Fake cleanup=new Fake(store,jpeg,saved);cleanup.deleteFailure=true;cleanup.release.countDown();factory.fake=cleanup;
      TagDetailChecks.require(NanoBananaService.start(screen,photo,prompt),"next request accepted after completion");idle(test);
      JSONObject pending=store.job();TagDetailChecks.require(pending!=null&&pending.optString("stage").equals("cleanup")&&pending.has("saved"),"cleanup failure retains saved URI");String retained=pending.getString("saved");
      TagDetailChecks.require(factory.messages.subList(cleanupToastStart,factory.messages.size()).equals(List.of("画像の生成を開始しました","画像は保存済みです。後処理を再試行してください")),"cleanup failure start then saved-image notice exactly once: "+factory.messages);
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
      int savedBeforeDiscard=saved.size(),statusesBeforeDiscard=stopped.statuses,discardToastStart=factory.messages.size();factory.lastResult=null;
      TagDetailChecks.require(NanoBananaService.resume(screen),"discard retry admitted");idle(test);
      TagDetailChecks.require(store.job()==null&&stopped.deletes==2&&stopped.downloads==0,"discard retry only removes temporary object");
      TagDetailChecks.require("後処理が完了しました".equals(factory.lastResult)&&saved.size()==savedBeforeDiscard&&stopped.statuses==statusesBeforeDiscard,"manual discarding resume reports cleanup complete without saving or polling");
      TagDetailChecks.require(factory.messages.subList(discardToastStart,factory.messages.size()).equals(List.of("後処理を開始しました","後処理が完了しました")),"cleanup-only retry Toast start/completion order exactly once: "+factory.messages);
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
      verifyDiscardWithRenamedPublishedResult(test,screen,store,factory,saved,input,prompt,jpeg);
      Context failing=new ContextWrapper(context){public Context getApplicationContext(){return this;}public ComponentName startForegroundService(Intent i){throw new IllegalStateException("synthetic startup rejection");}};
      TagDetailChecks.require(!NanoBananaService.start(failing,photo,prompt)&&!NanoBananaService.busy()&&store.job()==null,"startup rejection rolls back reservation");
      return "PASS nano-background: actual production Android Service with isolated fake provider; next-photo operation, busy/error menu captures, Home continuation, no generation/result Activity launch, automatic JPEG save/readback 1200x800, temporary-object cleanup, duplicate admission, immutable prompt/output snapshot, next admission, saved cleanup retry without POST/download, interrupted-worker lease, durable discard retry and cleanup-only completion text, missing-version selectable manual recovery guidance, explicit ambiguous-submission messaging, stale end-confirmation A/B identity guard and current identity cleanup, stale Intent suppression and startup rejection, remote-first discard with a detached result volume, renamed published result retained on discard, MTP same-size corruption and read error checks. Provider remained blocked through Home after "+homeWaitMs+" ms. Captures are synthetic layouts; OS-service runtime assertions are separate. No real API, credentials, photos, physical touch or TalkBack speech tested.\nStatus trace (delivery hook; fixed UI text only): "+String.join(" → ",factory.messages)+"\n";
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
  private static void timing(Fake fake, String checkpoint) {
    android.util.Log.i("LoupeNanoTiming", checkpoint + " elapsedMs="
        + (SystemClock.elapsedRealtime() - fake.statusEnteredAt)
        + " lease=" + NanoBananaService.busy() + " providerExited=" + fake.statusExited);
  }

  private static Thread watchTiming(Fake fake, Thread instrumentation) {
    Thread watcher = new Thread(() -> {
      try {
        Thread.sleep(15000);
        while (fake.release.getCount() == 1 && !fake.statusExited) {
          timing(fake, "watchdog sampled instrumentation and main");
          for (Thread thread : List.of(instrumentation, android.os.Looper.getMainLooper().getThread())) {
            android.util.Log.i("LoupeNanoTiming", "thread="
                + (thread == instrumentation ? "instrumentation" : "main") + " state=" + thread.getState());
            StackTraceElement[] stack = thread.getStackTrace();
            for (int i = 0; i < Math.min(9, stack.length); i++) {
              StackTraceElement frame = stack[i];
              android.util.Log.i("LoupeNanoTiming", frame.getClassName() + "." + frame.getMethodName()
                  + ":" + frame.getLineNumber());
            }
          }
          Thread.sleep(15000);
        }
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    }, "NanoFixtureTiming");
    watcher.setDaemon(true);
    watcher.start();
    return watcher;
  }

  private static void requireGenerationBlocked(NanoBananaStore store,Fake first,String checkpoint) throws Exception {
    JSONObject record=store.job();
    boolean generating=record!=null&&"generating".equals(record.optString("stage"));
    boolean leased=NanoBananaService.busy();
    boolean providerWaiting=first.entered.getCount()==0&&first.release.getCount()==1
        &&first.interrupted.getCount()==1&&!first.statusExited&&first.downloads==0;
    if(!leased||!generating||!providerWaiting) {
      // Emit only fixture booleans and durations. Instrumentation redacts arbitrary error text.
      android.util.Log.e("LoupeNanoFixture",checkpoint+" lease="+leased+" generating="+generating
          +" timedOut="+first.statusTimedOut+" statusExited="+first.statusExited
          +" interrupted="+(first.interrupted.getCount()==0)
          +" elapsedMs="+(SystemClock.elapsedRealtime()-first.statusEnteredAt));
      throw new AssertionError("Service and fake provider must still be running "+checkpoint);
    }
  }

  private static void verifyDiscardWithRenamedPublishedResult(Instrumentation test,MainActivity activity,
      NanoBananaStore store,FixtureFactory factory,List<String> saved,PhotoRepository.Photo input,
      NanoBananaStore.Prompt prompt,byte[] jpeg) throws Exception {
    ContentResolver resolver=activity.getContentResolver();
    Uri collection=MediaStore.Images.Media.getContentUri(PhotoStorage.PRIMARY);
    ContentValues row=new ContentValues();
    row.put(MediaStore.MediaColumns.DISPLAY_NAME,"NanoBanana-"+UUID.randomUUID()+".jpg");
    row.put(MediaStore.MediaColumns.RELATIVE_PATH,"Pictures/LoupeNanoBanana/");
    row.put(MediaStore.MediaColumns.MIME_TYPE,"image/jpeg");
    row.put(MediaStore.MediaColumns.IS_PENDING,1);
    Uri published=resolver.insert(collection,row);
    TagDetailChecks.require(published!=null,"Recovered result fixture inserted");
    try {
      try(java.io.OutputStream output=resolver.openOutputStream(published)){
        TagDetailChecks.require(output!=null,"Recovered result fixture opened");
        output.write(jpeg);
      }
      ContentValues publish=new ContentValues();
      publish.put(MediaStore.MediaColumns.IS_PENDING,0);
      TagDetailChecks.require(resolver.update(published,publish,null,null)==1,"Recovered result fixture published");
      String renamed="nano-discard-relocated-"+UUID.randomUUID()+".jpg";
      String relocated="Pictures/LoupeSyntheticChecks/";
      ContentValues move=new ContentValues();
      move.put(MediaStore.MediaColumns.DISPLAY_NAME,renamed);
      move.put(MediaStore.MediaColumns.RELATIVE_PATH,relocated);
      TagDetailChecks.require(resolver.update(published,move,null,null)==1,"Published result renamed and moved");
      Fake discard=new Fake(store,jpeg,saved);factory.fake=discard;
      store.job(new JSONObject().put("stage","generating").put("task","synthetic-published-result")
          .put("object","loupe-nano-temp/00000000-0000-0000-0000-000000000108.jpg")
          .put("owner","synthetic-published-owner").put("version","synthetic-version")
          .put("photo_volume",PhotoStorage.PRIMARY).put("pending",published.toString()));
      TagDetailChecks.require(NanoBananaService.discard(activity),"Discard renamed published result admitted");
      idle(test);
      TagDetailChecks.require(store.job()==null&&discard.deletes==1&&discard.submits==0
          &&discard.statuses==0&&discard.downloads==0,"Discard closes job and removes only the remote input");
      try(android.database.Cursor current=resolver.query(published,new String[]{MediaStore.MediaColumns.IS_PENDING,
          MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.RELATIVE_PATH},null,null,null)) {
        TagDetailChecks.require(current!=null&&current.moveToFirst()&&current.getInt(0)==0
            &&renamed.equals(current.getString(1))&&relocated.equals(current.getString(2)),
            "The relocated result remains published with its current name and folder");
      }
      try(InputStream bytes=resolver.openInputStream(published)) {
        TagDetailChecks.require(bytes!=null&&Arrays.equals(jpeg,bytes.readAllBytes()),"Discard leaves the published JPEG bytes intact");
      }
      Fake next=new Fake(store,jpeg,saved);next.release.countDown();factory.fake=next;
      TagDetailChecks.require(NanoBananaService.start(activity,input,prompt),"New job admitted after discard");
      idle(test);
      TagDetailChecks.require(next.submits==1&&next.downloads==1&&next.deletes==1&&store.job()==null,
          "New job completes after renamed published-result discard");
    } finally {
      resolver.delete(published,null,null);
    }
  }

  private static void verifyMtpDeletionProof() throws Exception {
    byte[] staged=new byte[128*1024+17];
    StringBuilder expected=new StringBuilder(64);
    for(byte value:MessageDigest.getInstance("SHA-256").digest(staged))
      expected.append(String.format(Locale.ROOT,"%02x",value&255));
    MediaSaver media=MediaSaver.INSTANCE;
    TagDetailChecks.require(media.matchesSavedBytes(new ByteArrayInputStream(staged),staged.length,
        expected.toString(),()->false),"MTP deletion allows the independently fingerprinted bytes");
    byte[] corrupted=staged.clone();corrupted[corrupted.length-1]=1;
    TagDetailChecks.require(!media.matchesSavedBytes(new ByteArrayInputStream(corrupted),staged.length,
        expected.toString(),()->false),"MTP deletion rejects a same-size mutation beyond the first 128 KiB");
    InputStream unreadable=new InputStream(){
      private boolean deliveredPrefix;
      @Override public int read() throws IOException {throw new IOException("synthetic SD read failure");}
      @Override public int read(byte[] bytes,int offset,int length) throws IOException {
        if(deliveredPrefix)throw new IOException("synthetic SD read failure");
        deliveredPrefix=true;
        int count=Math.min(1024,length);
        System.arraycopy(staged,0,bytes,offset,count);
        return count;
      }
    };
    TagDetailChecks.require(!media.matchesSavedBytes(unreadable,staged.length,expected.toString(),()->false),
        "MTP deletion rejects a read failure after a matching prefix");
    TagDetailChecks.require(!media.matchesSavedBytes(new ByteArrayInputStream(staged),staged.length,
        expected.toString(),()->true),"MTP deletion does not proceed after cancellation");
  }

  private static void verifyDiscardWithDetachedResult(Context context,NanoBananaStore store,Fake network) throws Exception {
    String volume="dead-beef";
    TagDetailChecks.require(!android.provider.MediaStore.getExternalVolumeNames(context).contains(volume),
        "fixture refers to an unavailable volume");
    String pending="content://media/"+volume+"/images/media/1";
    store.job(new JSONObject().put("stage","generating").put("task","synthetic-detached-result")
        .put("object","loupe-nano-temp/00000000-0000-0000-0000-000000000106.jpg")
        .put("owner","synthetic-detached-owner").put("version","synthetic-version")
        .put("photo_volume",volume).put("pending",pending));
    try {
      NanoBananaJob first=new NanoBananaJob(context,store,network);
      try {first.discard();throw new AssertionError("Discard silently forgot the detached result");}
      catch(PhotoStorage.Unavailable expected) { /* Remote cleanup has a separate durable boundary. */ }
      JSONObject record=store.job();
      TagDetailChecks.require(network.deletes==1&&record!=null&&record.getString("stage").equals("discarding")
          &&record.getString("pending").equals(pending)&&!record.getBoolean("discardDelete"),
          "Remote deletion completes once and detached local cleanup remains journaled");
      NanoBananaJob reopened=new NanoBananaJob(context,store,network);
      try {reopened.run(null,null,message->{});throw new AssertionError("Retry forgot the detached result");}
      catch(PhotoStorage.Unavailable expected) { /* A retry still awaits the original card. */ }
      TagDetailChecks.require(network.deletes==1&&network.submits==0&&network.statuses==0&&network.downloads==0
          &&store.job().getString("pending").equals(pending),
          "Resuming a canceled job never regenerates or repeats its finished remote deletion");
    } finally {
      store.job(null); // The fixture used a non-existent volume and no live provider objects.
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
  private static void verifyMenuTargets(View view){
    if(view.getVisibility()!=View.VISIBLE)return;
    if(view instanceof Button)TagDetailChecks.require(view.getHeight()>=GalleryUi.dp(view.getContext(),48),"visible menu row has 48dp touch target");
    if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)verifyMenuTargets(group.getChildAt(i));}
  }
  private static View findWindowLabel(String text){
    for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews()){View found=findLabel(root,text);if(found!=null)return found;}return null;
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
    test.waitForIdleSync();
    // Service Toasts cancel their predecessor; wait for the latest short Toast to leave the screen.
    FixtureFactory factory=(FixtureFactory)NanoBananaService.dependencies;
    while(factory.lastMessageAt>0&&SystemClock.uptimeMillis()-factory.lastMessageAt<2500)Thread.sleep(50);
    Thread.sleep(250);File dir=new File(test.getTargetContext().getCacheDir(),"consistency-checks");dir.mkdirs();
    Bitmap image=test.getUiAutomation().takeScreenshot();try(FileOutputStream output=new FileOutputStream(new File(dir,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,output);}finally{image.recycle();}
  }
}
