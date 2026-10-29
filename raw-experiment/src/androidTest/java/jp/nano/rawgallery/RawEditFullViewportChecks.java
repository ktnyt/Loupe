package jp.nano.rawgallery;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Real authorized input, image-only in-memory comparisons; no private pixels or URI leave the process. */
final class RawEditFullViewportChecks {
  private static volatile String stage="not-started";
  static String diagnosticStage(){return stage;}
  private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  private static <T> T find(View view,Class<T> type){
    if(type.isInstance(view))return type.cast(view);
    if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){T found=find(((ViewGroup)view).getChildAt(i),type);if(found!=null)return found;}
    return null;
  }
  private static Button button(View view,String label){
    if(view instanceof Button&&label.contentEquals(((Button)view).getText()))return (Button)view;
    if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button found=button(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}
    return null;
  }
  private static boolean onMain(Instrumentation t,BooleanSupplier action){boolean[] result={false};t.runOnMainSync(()->result[0]=action.getAsBoolean());return result[0];}
  private static void await(BooleanSupplier condition,int seconds,String message)throws Exception{
    long until=SystemClock.uptimeMillis()+seconds*1000L;
    while(SystemClock.uptimeMillis()<until){if(condition.getAsBoolean())return;Thread.sleep(50);}
    require(condition.getAsBoolean(),message);
  }
  private static Set<File> caches(Context context){
    File[] files=context.getCacheDir().listFiles(file->file.getName().startsWith("raw-full-")&&file.getName().endsWith(".ppm"));
    require(files!=null,"private cache directory available");return new HashSet<>(Arrays.asList(files));
  }
  private static Set<File> created(Context context,Set<File> baseline){Set<File> made=caches(context);made.removeAll(baseline);return made;}
  private static boolean contains(AccessibilityNodeInfo root,String text){
    if(root.getText()!=null&&root.getText().toString().contains(text))return true;
    for(int i=0;i<root.getChildCount();i++){AccessibilityNodeInfo child=root.getChild(i);if(child!=null)try{if(contains(child,text))return true;}finally{child.recycle();}}
    return false;
  }
  private static boolean originalConfirmation(Instrumentation t){
    AccessibilityNodeInfo root=t.getUiAutomation().getRootInActiveWindow();if(root==null)return false;
    try{return root.getPackageName()!=null&&t.getTargetContext().getPackageName().contentEquals(root.getPackageName())&&contains(root,"JPEGで保存");}
    finally{root.recycle();}
  }
  private static void cancelConfirmation(Instrumentation t,int width,int height){
    AccessibilityNodeInfo root=t.getUiAutomation().getRootInActiveWindow();require(root!=null,"original confirmation available");
    try{
      require(root.getPackageName()!=null&&t.getTargetContext().getPackageName().contentEquals(root.getPackageName()),"confirmation belongs to experimental package");
      require(contains(root,width+" × "+height+" px"),"confirmation uses validated full cache dimensions");
      List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByViewId("android:id/button2");boolean clicked=false;
      try{for(AccessibilityNodeInfo node:nodes)if(node.isEnabled()&&node.isClickable()&&"キャンセル".contentEquals(node.getText())){clicked=node.performAction(AccessibilityNodeInfo.ACTION_CLICK);if(clicked)break;}}
      finally{for(AccessibilityNodeInfo node:nodes)node.recycle();}
      require(clicked,"cancel original save through negative dialog action");
    }finally{root.recycle();}
  }
  private static void touch(View picture,long down,int action,float... xy){
    int count=xy.length/2;MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[count];MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[count];
    for(int i=0;i<count;i++){properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coords[i]=new MotionEvent.PointerCoords();coords[i].x=xy[2*i];coords[i].y=xy[2*i+1];coords[i].pressure=coords[i].size=1;}
    MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,properties,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
    try{picture.dispatchTouchEvent(event);}finally{event.recycle();}
  }
  private static Bitmap capture(RawEditImageView image){
    require(image.getWidth()>0&&image.getHeight()>0&&image.getWidth()<=4096&&image.getHeight()<=4096&&(long)image.getWidth()*image.getHeight()<=12000000,"bounded laid-out viewport");
    Bitmap result=Bitmap.createBitmap(image.getWidth(),image.getHeight(),Bitmap.Config.ARGB_8888);
    image.draw(new Canvas(result));return result;
  }
  private static Bitmap capture(Instrumentation t,RawEditImageView image){Bitmap[] result={null};t.runOnMainSync(()->result[0]=capture(image));return result[0];}
  private static final class Difference {
    long samples,above2,above8;
    boolean matches(){return samples>0&&above2==0;}
    // Require multiple spatially distinct witnesses, not a textured fraction of the photo.
    // Flat areas may agree exactly without weakening the native-detail evidence elsewhere.
    boolean distinguishes(){return above8>=100;}
  }
  private static Difference difference(Bitmap a,Bitmap b){
    require(a.getWidth()==b.getWidth()&&a.getHeight()==b.getHeight(),"comparison dimensions match");
    Difference result=new Difference();int w=a.getWidth();int[] left=new int[w],right=new int[w];
    // A dense deterministic grid, excluding a 4px boundary affected by Canvas edge coverage.
    for(int y=4;y<a.getHeight()-4;y+=3){a.getPixels(left,0,w,0,y,w,1);b.getPixels(right,0,w,0,y,w,1);
      for(int x=4;x<w-4;x+=3){int max=0;for(int shift=0;shift<=16;shift+=8){int error=Math.abs(((left[x]>>shift)&255)-((right[x]>>shift)&255));max=Math.max(max,error);}result.samples++;if(max>2)result.above2++;if(max>8)result.above8++;}
    }
    return result;
  }
  private static void requireFallbackSeparation(Bitmap actual,Bitmap reference,Bitmap fallback,long expectedWitnesses){
    require(actual.getWidth()==fallback.getWidth()&&actual.getHeight()==fallback.getHeight(),"fallback comparison dimensions match");
    int w=actual.getWidth();int[] shown=new int[w],nativeRow=new int[w],preview=new int[w];long witnesses=0,separated=0;
    // Revisit exactly the same 3px-spaced grid. Integer native/fallback error >8 means
    // at least 9 levels in one channel. With actual/native <=2, that channel must
    // remain at least 7 levels from the fallback (the reverse triangle inequality).
    for(int y=4;y<actual.getHeight()-4;y+=3){
      actual.getPixels(shown,0,w,0,y,w,1);reference.getPixels(nativeRow,0,w,0,y,w,1);fallback.getPixels(preview,0,w,0,y,w,1);
      for(int x=4;x<w-4;x+=3){boolean witness=false,distinct=false;
        for(int shift=0;shift<=16;shift+=8){int original=(nativeRow[x]>>shift)&255,low=(preview[x]>>shift)&255,current=(shown[x]>>shift)&255;
          if(Math.abs(original-low)>8){witness=true;if(Math.abs(current-low)>=7)distinct=true;}
        }
        if(witness){witnesses++;if(distinct)separated++;}
      }
    }
    require(witnesses==expectedWitnesses&&witnesses>=100&&separated==witnesses,
        "committed native detail differs from fallback at every witness; aggregate separated="+separated+"/"+witnesses);
  }
  private static Bitmap expected(RawEditFullSource full,RawEditState state,RectF region,int w,int h)throws Exception{
    Bitmap result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);boolean complete=false;
    try(RawEditRenderer renderer=new RawEditRenderer()){
      Canvas canvas=new Canvas(result);
      for(RawEditTilePlan.Tile tile:new RawEditTilePlan(region,w,h,full.width,full.height)){
        Bitmap piece=renderer.renderRegionBitmap(full,state,false,false,tile.source,tile.width,tile.height);
        try{canvas.drawBitmap(piece,tile.x,tile.y,null);}finally{piece.recycle();}
      }
      complete=true;return result;
    }finally{if(!complete)result.recycle();}
  }
  private static String readLaunchOutput(ParcelFileDescriptor descriptor)throws IOException{
    try(InputStream stream=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)){
      ByteArrayOutputStream kept=new ByteArrayOutputStream();byte[] buffer=new byte[1024];int count;
      while((count=stream.read(buffer))!=-1){int remaining=65536-kept.size();if(remaining>0)kept.write(buffer,0,Math.min(count,remaining));}
      return kept.toString("UTF-8"); // Bounded, process-local, never included in diagnostics.
    }
  }
  private static String launchResult(String stdout,String stderr){
    String text=(stdout+"\n"+stderr).toLowerCase(Locale.ROOT);
    if(text.contains("permission denial")||text.contains("securityexception")||text.contains("permission denied"))return "permission";
    if(text.contains("error type 3")||text.contains("does not exist")||text.contains("unable to resolve intent"))return "class-missing";
    if(text.contains("error")||text.contains("exception")||text.contains("status: timeout")||text.contains("background activity start denied"))return "error";
    return text.contains("status: ok")?"success":"error";
  }
  private static RawEditActivity startByShell(Instrumentation t,Uri input,Instrumentation.ActivityMonitor monitor)throws Exception{
    stage="activity.shell-start";
    String component=t.getTargetContext().getPackageName()+"/jp.nano.rawgallery.RawEditActivity";
    // UiAutomationConnection uses Runtime.exec(String), not a shell: quotes are literal.
    // run() admits only a whole URI made of safe, whitespace-free token characters.
    // NEW_TASK | MULTIPLE_TASK guarantees a fresh instance instead of an existing task.
    String command="am start -W -f 0x18000000 -n "+component+" -d "+input.toString()+" --grant-read-uri-permission";
    ParcelFileDescriptor[] pipes=t.getUiAutomation().executeShellCommandRwe(command);
    ExecutorService readers=Executors.newFixedThreadPool(2);
    try{
      require(pipes!=null&&pipes.length==3&&pipes[0]!=null&&pipes[1]!=null&&pipes[2]!=null,"shell result pipes available");
      pipes[1].close();
      Future<String> stdout=readers.submit(()->readLaunchOutput(pipes[0]));
      Future<String> stderr=readers.submit(()->readLaunchOutput(pipes[2]));
      String result=launchResult(stdout.get(30,TimeUnit.SECONDS),stderr.get(30,TimeUnit.SECONDS));
      stage="activity.shell-"+result;
      require("success".equals(result),"experimental Activity launch result="+result);
    }finally{
      if(pipes!=null)for(ParcelFileDescriptor pipe:pipes)if(pipe!=null)try{pipe.close();}catch(IOException ignored){}
      readers.shutdownNow();
    }
    // AOSP Instrumentation matches monitors after onCreate and onResume, including
    // externally initiated launches; no in-app startActivity interception is required.
    stage="activity.monitor-wait";
    Activity activity=monitor.waitForActivityWithTimeout(30000);
    require(activity instanceof RawEditActivity,"shell launch reaches monitored experimental Activity");
    require(input.equals(activity.getIntent().getData()),"monitored Activity receives authorized input");
    return (RawEditActivity)activity;
  }
  static void run(Instrumentation t,Uri input)throws Exception{
    stage="validate-input";
    require(input!=null&&input.toString().matches("content://media/[A-Za-z0-9_-]+/(?:images/media|file)/[0-9]+")&&"content".equals(input.getScheme())&&"media".equals(input.getAuthority())
        &&input.getPath()!=null&&input.getPath().matches("/[A-Za-z0-9_-]+/(?:images/media|file)/[0-9]+")
        &&input.getQuery()==null&&input.getFragment()==null,"authorized MediaStore content input required");
    Context context=t.getTargetContext();require("jp.nano.rawgallery.rawexperiment".equals(context.getPackageName()),"viewport check targets experimental package only");
    stage="cache-baseline";Set<File> baseline=caches(context);File owned=null;
    RawEditActivity activity=null;Bitmap fallback=null,reference=null;
    AccessibilityServiceInfo accessibility=null;int originalFlags=0;boolean changedFlags=false;
    Instrumentation.ActivityMonitor monitor=null;String failedStage=null;
    long started=SystemClock.elapsedRealtime();
    try{
      stage="automation.service-info";accessibility=t.getUiAutomation().getServiceInfo();require(accessibility!=null,"accessibility service info available");originalFlags=accessibility.flags;
      stage="automation.set-report-view-ids";accessibility.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;t.getUiAutomation().setServiceInfo(accessibility);changedFlags=true;
      stage="activity.add-monitor";monitor=t.addMonitor(RawEditActivity.class.getName(),null,false);
      activity=startByShell(t,input,monitor);
      stage="preview-ready";
      final RawEditActivity a=activity;
      await(()->onMain(t,()->{Button save=button(a.getWindow().getDecorView(),"保存");return save!=null&&save.isEnabled();}),90,"real preview becomes ready");
      stage="save-request";t.runOnMainSync(()->require(button(a.getWindow().getDecorView(),"保存").performClick(),"request original save confirmation"));
      stage="save-confirmation-wait";
      await(()->originalConfirmation(t),120,"original preparation reaches save confirmation");
      stage="cache-validation";Set<File> made=created(context,baseline);require(made.size()==1,"one unambiguous newly prepared cache; ambiguous caches preserved");owned=made.iterator().next();
      try(RawEditFullSource full=RawEditFullSource.open(owned)){
        require(Math.max(full.width,full.height)>2048,"selected real input exceeds the preview resolution");
        stage="save-confirmation-cancel";cancelConfirmation(t,full.width,full.height);
        await(()->!originalConfirmation(t),5,"save confirmation dismissed without publishing");
        // Let the unchanged neutral request issued by Save finish before the pinch starts.
        Thread.sleep(250);t.waitForIdleSync();
        RawEditImageView[] image={null};RectF[] region={null};RawEditState[] state={null};Bitmap[] before={null};long[] viewport={0};
        stage="pinch-and-fallback";t.runOnMainSync(()->{
          image[0]=find(a.getWindow().getDecorView(),RawEditImageView.class);require(image[0]!=null,"real preview image view exists");
          Bundle initial=new Bundle();a.onSaveInstanceState(initial);float[] values=initial.getFloatArray("values");state[0]=new RawEditState(values);
          View picture=(View)image[0].getParent();float cx=image[0].getWidth()*.5f,cy=image[0].getHeight()*.5f;
          require(image[0].getWidth()>520&&image[0].getHeight()>520,"pinch endpoints fit in viewport");
          long down=SystemClock.uptimeMillis();
          touch(picture,down,MotionEvent.ACTION_DOWN,cx-30,cy);
          touch(picture,down,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),cx-30,cy,cx+30,cy);
          touch(picture,down,MotionEvent.ACTION_MOVE,cx-250,cy,cx+250,cy);
          touch(picture,down,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),cx-250,cy,cx+250,cy);
          touch(picture,down,MotionEvent.ACTION_UP,cx-250,cy);
          require(image[0].maxZoom()>1&&Math.abs(image[0].zoom-image[0].maxZoom())<.002,"pinch reaches native 1:1 maximum zoom");
          region[0]=image[0].visibleSource();
          require(Math.abs(region[0].width()-image[0].getWidth())<.1&&Math.abs(region[0].height()-image[0].getHeight())<.1,"native viewport pixels correspond one-to-one to view pixels");
          Bundle after=new Bundle();a.onSaveInstanceState(after);require(Arrays.equals(values,after.getFloatArray("values")),"two-finger routing preserves edit values");
          viewport[0]=image[0].viewportRevision();
          // Same main-thread turn: the delayed detail job cannot have started for this viewport yet.
          before[0]=capture(image[0]);
        });
        stage="native-reference-render";fallback=before[0];reference=expected(full,state[0],region[0],fallback.getWidth(),fallback.getHeight());
        Difference initial=difference(fallback,reference);
        require(initial.distinguishes(),"selected viewport must distinguish native detail from preview; aggregate differing samples="+initial.above8+"/"+initial.samples);
        final Bitmap expected=reference;final RawEditImageView view=image[0];
        stage="detail-pixel-comparison";await(()->{
          require(onMain(t,()->view.viewportRevision()==viewport[0]),"viewport remains unchanged while awaiting original detail");
          Bitmap actual=capture(t,view);try{return difference(actual,expected).matches();}finally{actual.recycle();}
        },45,"real Activity displays native ROI matching original cache within 2 RGB levels");
        stage="detail-frame-commit";CountDownLatch committed=new CountDownLatch(1);
        t.runOnMainSync(()->{a.getWindow().getDecorView().getViewTreeObserver().registerFrameCommitCallback(committed::countDown);view.invalidate();});
        require(committed.await(5,TimeUnit.SECONDS),"native viewport commits a real window frame");
        Bitmap finalImage=capture(t,view);try{require(difference(finalImage,reference).matches(),"committed viewport retains original detail");requireFallbackSeparation(finalImage,reference,fallback,initial.above8);}finally{finalImage.recycle();}
        RawEditLog.event("full.viewport.check",SystemClock.elapsedRealtime()-started,view.getWidth(),view.getHeight());
      }
    }catch(Exception|AssertionError error){failedStage=stage;throw error;}
    finally{
      if(failedStage==null)stage="cleanup";
      try{
        if(fallback!=null)fallback.recycle();if(reference!=null)reference.recycle();
        if(activity==null&&monitor!=null&&monitor.getLastActivity() instanceof RawEditActivity)activity=(RawEditActivity)monitor.getLastActivity();
        if(activity!=null){RawEditActivity closing=activity;t.runOnMainSync(closing::finish);t.waitForIdleSync();}
        RawEditFullSession.initial(()->{}).get(20,TimeUnit.SECONDS);
        if(owned!=null){File cache=owned;await(()->!cache.exists(),20,"Activity releases its own full cache after finish");}
        Set<File> remaining=created(context,baseline);require(remaining.isEmpty(),"new full caches are cleaned by their Activity; ambiguous caches preserved");
      }finally{
        try{if(monitor!=null)t.removeMonitor(monitor);}
        finally{if(changedFlags){accessibility.flags=originalFlags;t.getUiAutomation().setServiceInfo(accessibility);}if(failedStage!=null)stage=failedStage;}
      }
    }
  }
}
