package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import jp.nano.rawgallery.camera.ImportProgress;
import org.json.JSONObject;

/** Actual Android Service/UI, isolated fake provider. No real network, key or source photo. */
final class GenerationIndicatorChecks {
  interface Check{boolean ok()throws Exception;}
  static void await(Instrumentation t,String label,Check check)throws Exception{
    android.util.Log.i("IndicatorChecks","await "+label);
    long end=SystemClock.uptimeMillis()+10000;boolean[] ok={false};
    do{TagDetailChecks.ui(t,()->{try{ok[0]=check.ok();}catch(Exception e){throw new RuntimeException(e);}});if(ok[0])return;Thread.sleep(50);}while(SystemClock.uptimeMillis()<end);
    throw new AssertionError(label);
  }
  static View find(View view,Object tag){
    if(tag.equals(view.getTag()))return view;
    if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View result=find(((ViewGroup)view).getChildAt(i),tag);if(result!=null)return result;}return null;
  }
  static View indicator(){for(View root:WindowInspector.getGlobalWindowViews()){View found=find(root,"generation-indicator");if(found!=null&&found.isShown())return found;}return null;}
  static View label(View view,String text){
    if(view instanceof android.widget.TextView&&text.contentEquals(((android.widget.TextView)view).getText()))return view;
    if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View result=label(((ViewGroup)view).getChildAt(i),text);if(result!=null)return result;}return null;
  }
  static void capture(Instrumentation t,String name)throws Exception{
    TagDetailChecks.ui(t,()->{});Thread.sleep(300);
    android.util.Log.i("IndicatorChecks","capture "+name);
    File directory=new File(t.getTargetContext().getCacheDir(),"indicator-captures");directory.mkdirs();
    try(android.os.ParcelFileDescriptor descriptor=t.getUiAutomation().executeShellCommand("screencap -p");
        java.io.FileInputStream input=new java.io.FileInputStream(descriptor.getFileDescriptor());
        FileOutputStream output=new FileOutputStream(new File(directory,name+".png"))){input.transferTo(output);}
  }
  static byte[] jpeg(){
    Bitmap bitmap=Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(bitmap);Paint p=new Paint(3);
    p.setShader(new LinearGradient(0,0,0,800,new int[]{0xff699eb9,0xffe7d9b4,0xff4d776a},null,Shader.TileMode.CLAMP));c.drawRect(0,0,1200,800,p);p.setShader(null);
    p.setColor(0xfff6e8c5);c.drawCircle(865,210,65,p);p.setColor(0xff577781);
    android.graphics.Path mountain=new android.graphics.Path();mountain.moveTo(0,600);mountain.lineTo(310,320);mountain.lineTo(600,620);mountain.lineTo(860,410);mountain.lineTo(1200,660);mountain.lineTo(1200,800);mountain.lineTo(0,800);c.drawPath(mountain,p);
    p.setColor(0xff244c48);c.drawOval(-150,650,1400,1100,p);
    ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,92,out);bitmap.recycle();return out.toByteArray();
  }
  static String run(Instrumentation t,int width,float font)throws Exception{
    Context context=t.getTargetContext();String namespace="indicator-isolated-fixture";
    TagDetailChecks.require(!NanoBananaService.busy()&&!ImportProgress.INSTANCE.getActive(),"no active work before fixture");
    NanoBananaStore store=new NanoBananaStore(context,namespace);store.job(null);
    byte[] jpeg=jpeg();CountDownLatch release=new CountDownLatch(1);List<Uri> owned=new ArrayList<>();
    final boolean[] fail={false},holdDelete={false};final int[] submits={0};
    final CountDownLatch[] deleting={null},deleteRelease={null};
    NanoBananaJob.Services network=new NanoBananaJob.Services(){
      public String upload(String object,byte[] b,String owner){return "fixture-version";}
      public String generate(String p,String object,String v,NanoBananaOutput o){submits[0]++;return "fixture-task";}
      public JSONObject status(String task)throws Exception{if(fail[0])throw new java.io.IOException("fixture failure");if(!release.await(90,TimeUnit.SECONDS))throw new java.io.IOException("fixture timeout");return new JSONObject().put("successFlag",1).put("response",new JSONObject().put("resultImageUrl","https://synthetic.invalid/result.jpg"));}
      public byte[] download(String u){return jpeg;}
      public void delete(String o,String v,String owner)throws Exception{if(holdDelete[0]){deleting[0].countDown();if(!deleteRelease[0].await(15,TimeUnit.SECONDS))throw new java.io.IOException("fixture cleanup timeout");}JSONObject record=store.job();if(record!=null&&record.has("saved"))owned.add(Uri.parse(record.getString("saved")));}
    };
    NanoBananaService.Factory factory=new NanoBananaService.Factory(){public NanoBananaStore store(Context c){return store;}public NanoBananaJob job(Context c,NanoBananaStore s){return new NanoBananaJob(c,s,network);}};
    MainActivity screen=null;Activity settings=null,result=null;Dialog[] modal={null};
    try{
    NanoBananaService.dependencies=factory;
    ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,"indicator-fixture.jpg");values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/LoupeSyntheticChecks");
    Uri uri=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new java.io.IOException("fixture insertion failed");owned.add(uri);
    try(java.io.OutputStream output=context.getContentResolver().openOutputStream(uri)){output.write(jpeg);}
    PhotoRepository.Photo photo=new PhotoRepository.Photo(uri,"indicator-fixture.jpg","synthetic",jpeg.length,1,1);
      screen=(MainActivity)t.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final MainActivity main=screen;
      GalleryScreenChecks.finishInitialIndex(t,main);GalleryScreenChecks.portraitFixture(t,main,width,font);
      TagDetailChecks.ui(t,()->{RankingResultChecks.set(main,"albumHome",false);RankingResultChecks.set(main,"photos",new ArrayList<>(List.of(photo)));invoke(main,"gallery");});
      capture(t,"baseline-library-"+width);
      // Actual explicit waiting state -> scanning -> import count -> finish.
      TagDetailChecks.ui(t,()->ImportProgress.INSTANCE.connecting("カメラ接続待ち"));
      await(t,"waiting tab",()->{LibraryTabs tabs=(LibraryTabs)findType(main.getWindow().getDecorView(),LibraryTabs.class);return tabs!=null&&tabs.destinationView(2).getStateDescription().toString().contains("接続待ち");});
      int[] alphas={0,0};
      TagDetailChecks.ui(t,()->alphas[0]=((LibraryTabs)findType(main.getWindow().getDecorView(),LibraryTabs.class)).getBadge(3).getAlpha());
      Thread.sleep(650);
      TagDetailChecks.ui(t,()->alphas[1]=((LibraryTabs)findType(main.getWindow().getDecorView(),LibraryTabs.class)).getBadge(3).getAlpha());
      TagDetailChecks.require(Math.abs(alphas[1]-alphas[0])>10,"waiting badge actually changes alpha");
      capture(t,"camera-waiting-"+width);Thread.sleep(850);capture(t,"camera-waiting-dim-"+width);
      TagDetailChecks.ui(t,()->{ImportProgress.INSTANCE.begin("Fixture camera");ImportProgress.INSTANCE.update(3,12);});
      await(t,"steady count",()->{LibraryTabs tabs=(LibraryTabs)findType(main.getWindow().getDecorView(),LibraryTabs.class);return tabs!=null&&tabs.getBadge(3)!=null&&tabs.getBadge(3).getAlpha()==255&&tabs.getBadge(3).getNumber()==3;});
      capture(t,"camera-importing-"+width);TagDetailChecks.ui(t,()->ImportProgress.INSTANCE.finish("fixture done"));
      TagDetailChecks.ui(t,()->{TagDetailChecks.require(NanoBananaService.start(main,photo,new NanoBananaStore.Prompt("fixture","色を整える","synthetic")),"service accepted");});
      await(t,"visible running",()->indicator()!=null&&indicator().getContentDescription().toString().contains("生成"));Thread.sleep(2600);
      verifyBounds(t);capture(t,"generating-library-"+width);
      shortcut(t);
      await(t,"keyboard status menu",()->windowLabel("画像を生成中")!=null);
      t.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);

      TagDetailChecks.ui(t,()->invoke(main,"showPhoto",0));
      await(t,"viewer ready",()->RankingResultChecks.get(main,"activePreview")!=null&&indicator()!=null);
      verifyBounds(t);
      await(t,"Look not covered",()->{
        android.graphics.Rect a=new android.graphics.Rect(),b=new android.graphics.Rect();
        View look=find(main.getWindow().getDecorView(),"viewer-look");
        TagDetailChecks.require(look!=null,"Look exists");
        int[] l=new int[2],i=new int[2];look.getLocationOnScreen(l);indicator().getLocationOnScreen(i);
        a.set(l[0],l[1],l[0]+look.getWidth(),l[1]+look.getHeight());b.set(i[0],i[1],i[0]+indicator().getWidth(),i[1]+indicator().getHeight());
        return !android.graphics.Rect.intersects(a,b);
      });
      capture(t,"generating-viewer-"+width);
      t.getUiAutomation().adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
      float motion=android.provider.Settings.Global.getFloat(context.getContentResolver(),"animator_duration_scale",1f);
      try{
        android.provider.Settings.Global.putFloat(context.getContentResolver(),"animator_duration_scale",0f);
        await(t,"reduced motion static symbol",()->!android.animation.ValueAnimator.areAnimatorsEnabled()&&findType(indicator(),com.google.android.material.progressindicator.CircularProgressIndicator.class)==null);
        capture(t,"reduced-motion-"+width);
      }finally{android.provider.Settings.Global.putFloat(context.getContentResolver(),"animator_duration_scale",motion);TagDetailChecks.require(android.provider.Settings.Global.getFloat(context.getContentResolver(),"animator_duration_scale",-1f)==motion,"original motion setting read back");t.getUiAutomation().dropShellPermissionIdentity();}
      await(t,"motion restored",()->android.animation.ValueAnimator.areAnimatorsEnabled());
      settings=t.startActivitySync(new Intent(context,NanoBananaSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final Activity config=settings;
      TagDetailChecks.ui(t,()->config.getWindow().setLayout(GalleryUi.dp(config,width),ViewGroup.LayoutParams.MATCH_PARENT));
      await(t,"settings indicator",()->indicator()!=null);TagDetailChecks.require((config.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0,"credential settings remain secure");
      TagDetailChecks.ui(t,config::finish);
      await(t,"viewer restored",()->main.hasWindowFocus()&&indicator()!=null);
      TagDetailChecks.ui(t,()->modal[0]=MoreMenuUi.show(main,"生成中もほかの操作ができます",new String[]{"閉じる"},(d,w)->{}));
      await(t,"modal indicator",()->indicator()!=null&&modal[0].getWindow().getDecorView().hasWindowFocus());Thread.sleep(450);
      TagDetailChecks.ui(t,()->{View indicator=indicator();try{java.lang.reflect.Field field=GenerationIndicator.class.getDeclaredField("instance");field.setAccessible(true);Object controller=field.get(null);java.lang.reflect.Field host=GenerationIndicator.class.getDeclaredField("host");host.setAccessible(true);TagDetailChecks.require(host.get(controller)==modal[0].getWindow().getDecorView(),"indicator attached to focused dialog above scrim");}catch(Exception e){throw new RuntimeException(e);}});
      capture(t,"generating-modal-"+width);
      shortcut(t);await(t,"modal keyboard status menu",()->windowLabel("画像を生成中")!=null);
      t.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
      TagDetailChecks.ui(t,()->modal[0].dismiss());
      t.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_HOME);Thread.sleep(200);
      await(t,"hidden in background",()->indicator()==null);
      // Return through normal task navigation, then release the fake provider.
      context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
      await(t,"returned running",()->indicator()!=null);release.countDown();
      await(t,"completion",()->!NanoBananaService.busy()&&indicator()!=null&&indicator().getContentDescription().toString().contains("保存しました"));
      Thread.sleep(350);verifyNotice(t,main);capture(t,"complete-"+width);
      Instrumentation.ActivityMonitor monitor=t.addMonitor(MainActivity.class.getName(),null,false);
      TagDetailChecks.ui(t,()->{View open=null;for(View root:WindowInspector.getGlobalWindowViews()){open=label(root,"開く");if(open!=null)break;}TagDetailChecks.require(open!=null&&open.performClick(),"Snackbar opens generated result");});
      result=t.waitForMonitorWithTimeout(monitor,10000);t.removeMonitor(monitor);TagDetailChecks.require(result instanceof MainActivity,"saved result opens in Loupe");final MainActivity generated=(MainActivity)result;
      await(t,"saved result preview",()->RankingResultChecks.get(generated,"activePreview")!=null);capture(t,"opened-result-"+width);
      TagDetailChecks.require(submits[0]==1,"one generation only");
      // Failure must not look like a still-running or successful task.
      fail[0]=true;TagDetailChecks.ui(t,()->NanoBananaService.start(generated,photo,new NanoBananaStore.Prompt("fixture","失敗の確認","synthetic")));
      await(t,"attention state",()->!NanoBananaService.busy()&&indicator()!=null&&indicator().getContentDescription().toString().contains("確認が必要"));
      Thread.sleep(350);verifyNotice(t,generated);capture(t,"attention-"+width);
      TagDetailChecks.ui(t,()->indicator().performClick());Thread.sleep(200);capture(t,"attention-menu-"+width);
      t.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
      TagDetailChecks.require(submits[0]==2,"opening status never submits request");
      // Exercise the durable restart admission path, with deletion still blocked on Activity resume.
      for(String stage:new String[]{"cleanup","discarding"}){
        deleting[0]=new CountDownLatch(1);deleteRelease[0]=new CountDownLatch(1);holdDelete[0]=true;
        JSONObject record=new JSONObject().put("stage",stage).put("object","fixture-object").put("owner","fixture-owner").put("version","fixture-version");
        if(stage.equals("cleanup"))record.put("saved",owned.get(1).toString());store.job(record);
        androidx.core.content.ContextCompat.startForegroundService(context,new Intent(context,NanoBananaService.class));
        TagDetailChecks.require(deleting[0].await(10,TimeUnit.SECONDS),"durable restart reached blocked deletion");
        await(t,"durable cleanup running",()->NanoBananaService.busy()&&indicator()!=null&&findType(indicator(),com.google.android.material.progressindicator.CircularProgressIndicator.class)!=null);
        t.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_HOME);
        await(t,"cleanup hidden in background",()->indicator()==null);
        context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        await(t,"cleanup visible after resume",()->NanoBananaService.busy()&&indicator()!=null);
        deleteRelease[0].countDown();
        await(t,"durable cleanup finished",()->!NanoBananaService.busy()&&indicator()!=null&&indicator().getContentDescription().toString().contains(stage.equals("discarding")?"後処理が完了しました":"保存しました"));
        if(stage.equals("discarding"))TagDetailChecks.ui(t,()->{String description=indicator().getContentDescription().toString();TagDetailChecks.require(!description.contains("保存")&&!description.contains("開く"),"cleanup-only completion never claims a saved image");});
      }

      return "PASS generation-indicator: explicit camera wait/count/finish; real foreground Service with fake provider; library/viewer/settings/modal; background hides and return restores; saved completion and actual Snackbar action to Loupe viewer; failure status and recovery menu; real keyboard shortcuts in activity/modal; durable cleanup/discard Service restart with blocked delete and Activity resume; cleanup-only completion wording; no automatic resend. width="+width+" font="+font+". No physical camera, real API or TalkBack speech.\n";
    }finally{
      release.countDown();if(deleteRelease[0]!=null)deleteRelease[0].countDown();context.stopService(new Intent(context,NanoBananaService.class));
      await(t,"worker stopped",()->!NanoBananaService.busy());
      // Restore after the worker lease is released, before any fallible fixture cleanup.
      NanoBananaService.dependencies=NanoBananaService.PRODUCTION;
      if(modal[0]!=null)TagDetailChecks.ui(t,()->modal[0].dismiss());
      for(Activity activity:new Activity[]{result,settings,screen})if(activity!=null)TagDetailChecks.ui(t,activity::finish);
      store.job(null);context.getSharedPreferences(namespace,0).edit().clear().commit();
      for(Uri item:new java.util.LinkedHashSet<>(owned))context.getContentResolver().delete(item,null,null);
      GenerationIndicator.finished("fixture ended",null,false);
    }
  }
  private static View windowLabel(String text){for(View root:WindowInspector.getGlobalWindowViews()){View found=label(root,text);if(found!=null&&found.isShown())return found;}return null;}
  private static void shortcut(Instrumentation t){long now=SystemClock.uptimeMillis();int meta=android.view.KeyEvent.META_CTRL_ON|android.view.KeyEvent.META_SHIFT_ON;t.sendKeySync(new android.view.KeyEvent(now,now,0,android.view.KeyEvent.KEYCODE_G,0,meta));t.sendKeySync(new android.view.KeyEvent(now,SystemClock.uptimeMillis(),1,android.view.KeyEvent.KEYCODE_G,0,meta));}
  private static Object findType(View root,Class<?> type){if(root==null)return null;if(type.isInstance(root))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){Object value=findType(((ViewGroup)root).getChildAt(i),type);if(value!=null)return value;}return null;}
  private static void verifyNotice(Instrumentation t,MainActivity activity)throws Exception{
    TagDetailChecks.ui(t,()->{
      View actions=find(activity.getWindow().getDecorView(),"viewer-actions");
      View notice=activity.getWindow().getDecorView().findViewById(com.google.android.material.R.id.snackbar_text);
      TagDetailChecks.require(actions!=null&&notice!=null,"viewer controls and status notice present");
      int[] a=new int[2],b=new int[2];actions.getLocationOnScreen(a);notice.getLocationOnScreen(b);
      TagDetailChecks.require(b[1]+notice.getHeight()<=a[1],"status notice stays above viewer controls");
    });
  }
  private static void verifyBounds(Instrumentation t)throws Exception{TagDetailChecks.ui(t,()->{View v=indicator();int[] xy=new int[2];v.getLocationOnScreen(xy);TagDetailChecks.require(v.getWidth()>=GalleryUi.dp(v.getContext(),48)&&v.getHeight()>=GalleryUi.dp(v.getContext(),48)&&xy[0]>=0&&xy[1]>=0,"indicator touch target fits screen");});}
  private static void invoke(Object object,String name,Object...args){try{java.lang.reflect.Method method=args.length==0?object.getClass().getDeclaredMethod(name):object.getClass().getDeclaredMethod(name,int.class);method.setAccessible(true);method.invoke(object,args);}catch(Exception e){throw new RuntimeException(e);}}
}
