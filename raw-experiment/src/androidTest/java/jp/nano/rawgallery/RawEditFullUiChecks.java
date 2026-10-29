package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import android.system.Os;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Real Activity flow with private synthetic input and a test-owned preparation barrier. */
final class RawEditFullUiChecks {
  private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}

  /** Installed after Activity.onCreate, before its posted initial-upload callback can start preparation. */
  static final class Gate implements AutoCloseable {
    final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    final AtomicBoolean queued=new AtomicBoolean();
    volatile Future<?> task;
    void afterCreate(Activity activity){
      if(!(activity instanceof RawEditActivity)||!queued.compareAndSet(false,true))return;
      task=RawEditFullSession.initial(()->{
        entered.countDown();
        try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
      });
    }
    public void close(){release.countDown();}
  }
  private static <T> T find(View view,Class<T> type){
    if(type.isInstance(view))return type.cast(view);
    if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){T next=find(((ViewGroup)view).getChildAt(i),type);if(next!=null)return next;}
    return null;
  }
  private static Button button(View view,String label){
    if(view instanceof Button&&label.contentEquals(((Button)view).getText()))return (Button)view;
    if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button next=button(((ViewGroup)view).getChildAt(i),label);if(next!=null)return next;}
    return null;
  }
  private static Button save(RawEditActivity activity){Button b=button(activity.getWindow().getDecorView(),"保存");if(b==null)b=button(activity.getWindow().getDecorView(),"✓");require(b!=null,"Save button is present");return b;}
  private static Bundle state(RawEditActivity activity){Bundle bundle=new Bundle();activity.onSaveInstanceState(bundle);return bundle;}
  private static boolean waiting(RawEditActivity activity){CharSequence description=save(activity).getStateDescription();return description!=null&&description.toString().contains("原寸の準備を待っています");}
  private static void editable(RawEditActivity activity){
    View decor=activity.getWindow().getDecorView();
    for(String label:new String[]{"WB","質感","トーン","明るさ","明暗","白黒","カラー"}){Button tab=button(decor,label);require(tab!=null&&tab.isEnabled(),"all seven tabs stay editable");}
    RawEditPad pad=find(decor,RawEditPad.class);require(pad!=null&&pad.isEnabled()&&pad.getChildAt(0).isEnabled(),"2D pad stays editable");
    require(save(activity).isEnabled()&&!activity.isFinishing(),"Save wait remains in live Activity");
  }
  private static void changePad(RawEditActivity activity,float x,float y){
    RawEditPad pad=find(activity.getWindow().getDecorView(),RawEditPad.class);require(pad!=null,"pad available");View plane=pad.getChildAt(0);
    require(plane.getWidth()>0&&plane.getHeight()>0,"pad is laid out before gesture");
    float[] old=state(activity).getFloatArray("values");long now=SystemClock.uptimeMillis();
    MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,plane.getWidth()*x,plane.getHeight()*y,0);plane.dispatchTouchEvent(down);down.recycle();
    MotionEvent up=MotionEvent.obtain(now,now+10,MotionEvent.ACTION_UP,plane.getWidth()*x,plane.getHeight()*y,0);plane.dispatchTouchEvent(up);up.recycle();
    require(!Arrays.equals(old,state(activity).getFloatArray("values")),"real pad event changes current values");
  }
  private static boolean onMain(Instrumentation t,BooleanSupplier condition){boolean[] value={false};t.runOnMainSync(()->value[0]=condition.getAsBoolean());return value[0];}
  private static void await(BooleanSupplier condition,int seconds,String message)throws Exception {
    long until=SystemClock.uptimeMillis()+seconds*1000L;
    while(SystemClock.uptimeMillis()<until){if(condition.getAsBoolean())return;Thread.sleep(25);}
    require(condition.getAsBoolean(),message);
  }
  private static Map<Long,Integer> outputs(Context context){
    Map<Long,Integer> result=new HashMap<>();
    Bundle query=new Bundle();query.putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE);
    query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,"owner_package_name=? AND relative_path IN (?,?) AND _display_name LIKE ?");
    query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,new String[]{context.getPackageName(),"Pictures/LoupeDevelop/","Pictures/LoupeDevelop","Loupe-%.jpg"});
    try(Cursor rows=context.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,new String[]{"_id","is_pending"},query,null)){
      require(rows!=null,"owned synthetic output query");while(rows.moveToNext())result.put(rows.getLong(0),rows.getInt(1));
    }
    return result;
  }
  private static Map<Long,Integer> created(Context context,Set<Long> baseline){Map<Long,Integer> rows=outputs(context);for(Long id:baseline)rows.remove(id);return rows;}
  private static boolean treeContains(AccessibilityNodeInfo root,String text){
    if(root==null)return false;
    if(root.getText()!=null&&root.getText().toString().contains(text))return true;
    for(int i=0;i<root.getChildCount();i++){AccessibilityNodeInfo child=root.getChild(i);if(child!=null)try{if(treeContains(child,text))return true;}finally{child.recycle();}}
    return false;
  }
  private static boolean originalDialog(Instrumentation t){
    AccessibilityNodeInfo root=t.getUiAutomation().getRootInActiveWindow();
    if(root==null)return false;
    try{return root.getPackageName()!=null&&t.getTargetContext().getPackageName().contentEquals(root.getPackageName())&&treeContains(root,"JPEGで保存")&&treeContains(root,"6000 × 4000 px");}finally{root.recycle();}
  }
  private static void confirmDialog(Instrumentation t){
    AccessibilityNodeInfo root=t.getUiAutomation().getRootInActiveWindow();require(root!=null,"save dialog window");
    try {
      require(root.getPackageName()!=null&&t.getTargetContext().getPackageName().contentEquals(root.getPackageName()),"confirmation belongs to test Activity");
      List<AccessibilityNodeInfo> matches=root.findAccessibilityNodeInfosByText("保存");boolean clicked=false;
      try{for(AccessibilityNodeInfo node:matches)if(node.getText()!=null&&"保存".contentEquals(node.getText())&&node.isClickable()&&node.isEnabled()){clicked=node.performAction(AccessibilityNodeInfo.ACTION_CLICK);if(clicked)break;}}
      finally{for(AccessibilityNodeInfo node:matches)node.recycle();}
      require(clicked,"activate positive Save through accessibility UI");
    }finally{root.recycle();}
  }
  private static View description(View view,String prefix){
    if(view.isShown()&&view.getContentDescription()!=null&&view.getContentDescription().toString().startsWith(prefix))return view;
    if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View match=description(((ViewGroup)view).getChildAt(i),prefix);if(match!=null)return match;}return null;
  }
  private static Rect bounds(View view){int[] xy=new int[2];view.getLocationInWindow(xy);return new Rect(xy[0],xy[1],xy[0]+view.getWidth(),xy[1]+view.getHeight());}
  private static JSONArray rect(Rect r)throws org.json.JSONException{return new JSONArray(new int[]{r.left,r.top,r.right,r.bottom});}
  private static Set<String> fullCaches(Context context){Set<String> names=new HashSet<>();File[] files=context.getCacheDir().listFiles();if(files!=null)for(File file:files)if(file.getName().startsWith("raw-full-"))names.add(file.getName());return names;}
  private static int light(int argb){return (Color.red(argb)+Color.green(argb)+Color.blue(argb))/3;}
  private static JSONObject captureState(Instrumentation t,RawEditActivity activity,File folder,String label,long started)throws Exception {
    long until=SystemClock.uptimeMillis()+5000;JSONArray attempts=new JSONArray();boolean confirmed=false;int number=0;
    while(SystemClock.uptimeMillis()<until&&!confirmed){
      number++;CountDownLatch frame=new CountDownLatch(1);int[] size=new int[2];
      t.runOnMainSync(()->{View decor=activity.getWindow().getDecorView();size[0]=decor.getWidth();size[1]=decor.getHeight();RawEditImageView image=find(decor,RawEditImageView.class);RawEditPad pad=find(decor,RawEditPad.class);decor.getViewTreeObserver().registerFrameCommitCallback(frame::countDown);image.invalidate();pad.invalidate();decor.invalidate();});
      require(frame.await(Math.max(1,until-SystemClock.uptimeMillis()),TimeUnit.MILLISECONDS),"full state frame commits before PixelCopy");
      Bitmap bitmap=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);CountDownLatch copied=new CountDownLatch(1);int[] result={-1},points=new int[10];Rect[] geometry=new Rect[4];String[] badgeLabel={""},saveLabel={""};float[][] values={null};float[] pose=new float[4];boolean[] copyFinished={false},abandoned={false};Object lock=new Object();boolean owns=true;
      try {
        t.runOnMainSync(()->{
          View decor=activity.getWindow().getDecorView();RawEditImageView image=find(decor,RawEditImageView.class);RawEditPad pad=find(decor,RawEditPad.class);Rect selected=bounds(button(decor,"WB")),photo=bounds(image),plane=bounds(pad.getChildAt(0));float density=activity.getResources().getDisplayMetrics().density;
          points[0]=selected.centerX();points[1]=selected.top+Math.max(1,Math.round(density));points[2]=photo.centerX();points[3]=photo.centerY();points[4]=plane.centerX()+Math.round(13*density);points[5]=plane.centerY();points[6]=plane.centerX()+Math.round(9*density);points[7]=plane.centerY();points[8]=plane.centerX()+Math.round(17*density);points[9]=plane.centerY();
          geometry[0]=bounds(pad);geometry[1]=photo;geometry[2]=bounds(save(activity));View badge=description(decor,"原寸を準備できません");if(badge==null)badge=description(decor,"精細な表示を準備しています");geometry[3]=badge==null?new Rect():bounds(badge);if(badge!=null)badgeLabel[0]=badge.getContentDescription().toString();CharSequence state=save(activity).getStateDescription();saveLabel[0]=state==null?"":state.toString();values[0]=state(activity).getFloatArray("values");pose[0]=image.zoom;pose[1]=image.dx;pose[2]=image.dy;pose[3]=image.maxZoom();
          PixelCopy.request(activity.getWindow(),bitmap,status->{synchronized(lock){result[0]=status;copyFinished[0]=true;if(abandoned[0])bitmap.recycle();}copied.countDown();},new Handler(Looper.getMainLooper()));
        });
        if(!copied.await(Math.max(1,until-SystemClock.uptimeMillis()),TimeUnit.MILLISECONDS)){synchronized(lock){if(!copyFinished[0]){abandoned[0]=true;owns=false;}}throw new AssertionError("full state PixelCopy completes");}
        int[] colors=new int[5];boolean tab=false,photo=false,cursor=false;
        if(result[0]==PixelCopy.SUCCESS){for(int i=0;i<5;i++)colors[i]=bitmap.getPixel(points[i*2],points[i*2+1]);tab=(colors[0]&0xffffff)==0xededea;photo=Color.red(colors[1])>32&&Color.green(colors[1])>32&&Color.blue(colors[1])>32;cursor=Color.red(colors[2])>96&&Color.green(colors[2])>96&&Color.blue(colors[2])>96&&light(colors[2])-Math.max(light(colors[3]),light(colors[4]))>=15;}
        confirmed=tab&&photo&&cursor;JSONObject attempt=new JSONObject();attempt.put("attempt",number);attempt.put("elapsedMs",SystemClock.uptimeMillis()-started);attempt.put("pixelCopy",result[0]);attempt.put("tab",tab);attempt.put("photo",photo);attempt.put("cursor",cursor);attempt.put("probeArgb",new JSONArray(colors));attempts.put(attempt);
        if(confirmed){
          try(OutputStream out=new FileOutputStream(new File(folder,label+".png"))){require(bitmap.compress(Bitmap.CompressFormat.PNG,100,out),"full state synthetic PNG saved");}
          JSONObject record=new JSONObject();record.put("state",label);record.put("file",label+".png");record.put("elapsedMs",SystemClock.uptimeMillis()-started);record.put("attempts",attempts);record.put("pad",rect(geometry[0]));record.put("photo",rect(geometry[1]));record.put("save",rect(geometry[2]));record.put("badge",rect(geometry[3]));record.put("badgeDescription",badgeLabel[0]);record.put("saveState",saveLabel[0]);record.put("values",new JSONArray(values[0]));record.put("zoom",pose[0]);record.put("panX",pose[1]);record.put("panY",pose[2]);record.put("maxZoom",pose[3]);return record;
        }
      }finally{if(owns)bitmap.recycle();}
      Thread.sleep(40);
    }
    throw new AssertionError("full state capture must show current WB, synthetic photo and cursor");
  }
  private static boolean cancelOriginalDialog(Instrumentation t){
    AccessibilityNodeInfo root=t.getUiAutomation().getRootInActiveWindow();if(root==null)return false;
    try {
      if(root.getPackageName()==null||!t.getTargetContext().getPackageName().contentEquals(root.getPackageName()))return false;
      List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText("キャンセル");boolean clicked=false;
      try{for(AccessibilityNodeInfo node:nodes)if(node.getText()!=null&&"キャンセル".contentEquals(node.getText())&&node.isClickable()){clicked=node.performAction(AccessibilityNodeInfo.ACTION_CLICK);if(clicked)break;}}finally{for(AccessibilityNodeInfo node:nodes)node.recycle();}return clicked;
    }finally{root.recycle();}
  }
  private static void restoredPoseChecks(Instrumentation t){
    t.runOnMainSync(()->{
      RawEditImageView layoutFirst=new RawEditImageView(t.getTargetContext());layoutFirst.restorePose(6,120,-80);layoutFirst.layout(0,0,1000,800);
      require(layoutFirst.zoom==6&&layoutFirst.dx==120&&layoutFirst.dy==-80,"layout before metadata must not apply fallback zoom cap");layoutFirst.setFullDimensions(8000,6000);require(layoutFirst.zoom==6&&layoutFirst.dx==120&&layoutFirst.dy==-80,"load applies restored zoom over four and pan");
      RawEditImageView metadataFirst=new RawEditImageView(t.getTargetContext());metadataFirst.restorePose(6,120,-80);metadataFirst.setFullDimensions(8000,6000);require(metadataFirst.zoom==6,"metadata before layout must defer clamp too");metadataFirst.layout(0,0,1000,800);require(metadataFirst.zoom==6&&metadataFirst.dx==120&&metadataFirst.dy==-80,"metadata-first restoration preserves pose");
      RawEditImageView limited=new RawEditImageView(t.getTargetContext());limited.restorePose(99,10000,-10000);limited.layout(0,0,1000,800);limited.setFullDimensions(6000,4000);require(limited.zoom==6&&Math.abs(limited.dx-2500)<.01&&Math.abs(limited.dy+1600)<.01,"restore clamps once to actual maximum and pan bounds");limited.setFullDimensions(12000,8000);require(limited.zoom==6,"later metadata cannot resurrect unclamped saved pose");
      RawEditImageView changed=new RawEditImageView(t.getTargetContext());changed.restorePose(6,120,-80);changed.toggle();changed.layout(0,0,1000,800);changed.setFullDimensions(8000,6000);require(changed.zoom==1&&changed.dx==0&&changed.dy==0,"explicit user toggle supersedes pending restoration");
      RawEditImageView failed=new RawEditImageView(t.getTargetContext());failed.restorePose(6,120,-80);failed.discardRestoredPose();failed.layout(0,0,1000,800);failed.setFullDimensions(8000,6000);require(failed.zoom==4,"failed load does not retain a latent restored pose");failed.restorePose(6,120,-80);failed.resetPose();failed.setFullDimensions(9000,6000);require(failed.zoom==1&&failed.dx==0&&failed.dy==0,"new input starts at fit without old pending pose");
    });
  }
  private static RawEditActivity recreateWithOriginalPose(Instrumentation t,RawEditActivity old)throws Exception {
    float[] expected=new float[3];t.runOnMainSync(()->{RawEditImageView image=find(old.getWindow().getDecorView(),RawEditImageView.class);require(image.maxZoom()>4,"synthetic fixture supports zoom above four");expected[0]=Math.min(5,image.maxZoom());expected[1]=40;expected[2]=-30;image.restorePose(expected[0],expected[1],expected[2]);Bundle saved=state(old);require(saved.getFloat("zoom")==expected[0]&&saved.getFloat("panX")==40&&saved.getFloat("panY")==-30,"Activity saves full-resolution pose");});
    Instrumentation.ActivityMonitor monitor=t.addMonitor(RawEditActivity.class.getName(),null,false);RawEditActivity recreated=null;boolean accepted=false;
    try {
      t.runOnMainSync(old::recreate);Activity candidate=monitor.waitForActivityWithTimeout(20000);require(candidate instanceof RawEditActivity&&candidate!=old,"Activity recreation produces a new instance");recreated=(RawEditActivity)candidate;final RawEditActivity current=recreated;
      await(()->onMain(t,()->{Button b=button(current.getWindow().getDecorView(),"保存");return b!=null&&b.isEnabled();}),30,"recreated photo preview loads");
      t.runOnMainSync(()->{RawEditImageView image=find(current.getWindow().getDecorView(),RawEditImageView.class);require(Math.abs(image.zoom-expected[0])<.001&&Math.abs(image.dx-expected[1])<.001&&Math.abs(image.dy-expected[2])<.001,"recreation and asynchronous reload retain original zoom and pan over four");});accepted=true;return recreated;
    }finally{t.removeMonitor(monitor);if(!accepted&&recreated!=null){RawEditActivity rejected=recreated;t.runOnMainSync(rejected::finish);}}
  }
  /** State screenshots and a real write-denial/retry in the isolated experiment app only. */
  static void runStates(Instrumentation t,Gate gate)throws Exception {
    restoredPoseChecks(t);
    Context context=t.getTargetContext();require(context.getPackageName().equals("jp.nano.rawgallery.rawexperiment"),"permission fixture restricted to experiment app");
    File cache=context.getCacheDir().getCanonicalFile(),expected=new File(context.getApplicationInfo().dataDir,"cache").getCanonicalFile();require(cache.equals(expected),"only target app private cache may change mode");
    int originalMode=Os.stat(cache.getPath()).st_mode&07777;require((originalMode&0200)!=0,"private cache owner originally writable");
    Set<Long> baseline=new HashSet<>(outputs(context).keySet());Set<String> cachesBefore=fullCaches(context);RawEditActivity activity=null;boolean changedMode=false;File folder=null;JSONArray captures=new JSONArray();long started=SystemClock.uptimeMillis();
    try {
      activity=(RawEditActivity)t.startActivitySync(new Intent(context,RawEditActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("rawEditSynthetic",true));final RawEditActivity a=activity;
      require(gate!=null&&gate.entered.await(30,TimeUnit.SECONDS),"preparation blocked after initial synthetic decode");
      await(()->onMain(t,()->{Button b=button(a.getWindow().getDecorView(),"保存");return b!=null&&b.isEnabled()&&description(a.getWindow().getDecorView(),"精細な表示を準備しています")!=null;}),20,"fullprepare badge shown after 400ms");
      int[] height={0};float[] font={0};float[][] initial={null};
      t.runOnMainSync(()->{editable(a);height[0]=a.getWindow().getDecorView().getHeight();font[0]=a.getResources().getConfiguration().fontScale;initial[0]=state(a).getFloatArray("values");});
      folder=new File(cache,"full-ui-states-"+font[0]+"-"+height[0]);require(folder.isDirectory()||folder.mkdirs(),"synthetic full-state capture directory");
      captures.put(captureState(t,a,folder,"fullprepare",started));
      t.runOnMainSync(()->{save(a).performClick();require(waiting(a),"Save waits for full preparation");editable(a);View plane=find(a.getWindow().getDecorView(),RawEditPad.class).getChildAt(0);Bundle progress=new Bundle();progress.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,.2f);require(plane.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),progress),"waiting pad remains editable");require(state(a).getFloatArray("values")[0]>.19f,"waiting edit applied");require(plane.performAccessibilityAction(RawEditPad.RESET_ACTION,null),"WB returns to default without touching Mono or MAX");require(Arrays.equals(initial[0],state(a).getFloatArray("values")),"default WB capture state restored");});
      t.waitForIdleSync();captures.put(captureState(t,a,folder,"savewait",started));
      // Only permission bits on this isolated synthetic test's private cache change, for the failed write.
      try {
        Os.chmod(cache.getPath(),originalMode&~0222);changedMode=true;require((Os.stat(cache.getPath()).st_mode&0222)==0,"cache write permission removed for preparation failure");gate.close();
        await(()->onMain(t,()->description(a.getWindow().getDecorView(),"原寸を準備できません")!=null),20,"write-denied preparation exposes retry state");
      }finally{if(changedMode){Os.chmod(cache.getPath(),originalMode);changedMode=false;}require((Os.stat(cache.getPath()).st_mode&07777)==originalMode,"exact original cache permission restored");}
      t.runOnMainSync(()->{editable(a);require(!waiting(a),"failed preparation clears save wait");RawEditImageView image=find(a.getWindow().getDecorView(),RawEditImageView.class);float expectedZoom=Math.max(1,Math.max(1200f/image.getWidth(),800f/image.getHeight()));require(Math.abs(image.maxZoom()-expectedZoom)<.01,"failure uses preview 1:1 zoom limit");require(Arrays.equals(initial[0],state(a).getFloatArray("values")),"failure preserves values");});
      captures.put(captureState(t,a,folder,"preparefail",started));
      t.runOnMainSync(()->{View retry=description(a.getWindow().getDecorView(),"原寸を準備できません");require(retry!=null&&retry.isEnabled()&&retry.performClick(),"warning badge retries after mode restoration");save(a).performClick();require(waiting(a),"retry can wait for original confirmation");});
      await(()->originalDialog(t),120,"successful retry confirms original 6000 by 4000 dimensions");require(created(context,baseline).isEmpty(),"confirmation creates no MediaStore output");require(cancelOriginalDialog(t),"cancel original-size confirmation through target dialog");t.waitForIdleSync();
      await(()->onMain(t,()->!waiting(a)&&save(a).isEnabled()&&description(a.getWindow().getDecorView(),"精細な表示を準備しています")==null&&description(a.getWindow().getDecorView(),"原寸を準備できません")==null),10,"ready editor after confirmation cancellation");
      t.runOnMainSync(()->{editable(a);RawEditImageView image=find(a.getWindow().getDecorView(),RawEditImageView.class);require(Arrays.equals(initial[0],state(a).getFloatArray("values")),"retry and confirmation cancel preserve values");require(image.zoom==1&&image.dx==0&&image.dy==0,"retry preserves fit pose");require(image.maxZoom()>Math.max(1,1200f/image.getWidth()),"ready zoom uses original dimensions");});
      captures.put(captureState(t,a,folder,"ready",started));require(created(context,baseline).isEmpty(),"no JPEG created by cancelled original confirmation");
      activity=recreateWithOriginalPose(t,a);
    }finally {
      if(changedMode)Os.chmod(cache.getPath(),originalMode);
      gate.close();if(activity!=null){RawEditActivity closing=activity;t.runOnMainSync(closing::finish);}if(gate.task!=null)gate.task.get(10,TimeUnit.SECONDS);
      RawEditFullSession.initial(()->{}).get(30,TimeUnit.SECONDS);
      if(folder!=null){JSONObject manifest=new JSONObject();manifest.put("input","debug synthetic 6000x4000 only");manifest.put("originalCacheMode",originalMode);manifest.put("restoredCacheMode",Os.stat(cache.getPath()).st_mode&07777);manifest.put("elapsedMs",SystemClock.uptimeMillis()-started);manifest.put("states",captures);try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(folder,"manifest.json")),java.nio.charset.StandardCharsets.UTF_8)){out.write(manifest.toString(2));}}
    }
    await(()->fullCaches(context).equals(cachesBefore),10,"finishing synthetic Activity removes only its full cache");require(created(context,baseline).isEmpty(),"unexpected or ambiguous outputs are preserved and fail the state test");require((Os.stat(cache.getPath()).st_mode&07777)==originalMode,"private cache permissions unchanged after state test");
  }
  static void run(Instrumentation t,Gate gate)throws Exception {
    restoredPoseChecks(t);
    require(gate!=null,"instrumentation preparation gate configured");Context context=t.getTargetContext();Set<Long> baseline=new HashSet<>(outputs(context).keySet());
    RawEditActivity activity=null;
    try {
      activity=(RawEditActivity)t.startActivitySync(new Intent(context,RawEditActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("rawEditSynthetic",true));
      final RawEditActivity a=activity;
      require(gate.entered.await(30,TimeUnit.SECONDS),"initial decode precedes preparation barrier");
      await(()->onMain(t,()->{Button b=button(a.getWindow().getDecorView(),"保存");return b!=null&&b.isEnabled();}),30,"preview becomes editable while preparation is gated");
      t.waitForIdleSync();require(gate.release.getCount()==1&&gate.task!=null&&!gate.task.isDone(),"preparation is deterministically blocked after preview loads");
      t.runOnMainSync(()->{
        editable(a);RawEditImageView image=find(a.getWindow().getDecorView(),RawEditImageView.class);RectF original=image.visibleSource();
        require(Math.abs(original.width()-6000)<1&&Math.abs(original.height()-4000)<1,"initial metadata supplies full synthetic dimensions before preparation");
        changePad(a,.72f,.31f);save(a).performClick();require(waiting(a),"Save enters preparation wait");editable(a);
      });
      t.waitForIdleSync();
      t.runOnMainSync(()->{
        changePad(a,.83f,.19f);require(waiting(a),"editing preserves preparation wait");save(a).performClick();require(!waiting(a),"second Save cancels only waiting");editable(a);
        save(a).performClick();require(waiting(a),"Save can wait again");a.onBackPressed();require(!waiting(a),"Back cancels waiting without leaving editor");editable(a);
      });
      require(created(context,baseline).isEmpty(),"cancelled preparation waits create no MediaStore row");
      final float[][] latest={null};
      t.runOnMainSync(()->{
        save(a).performClick();require(waiting(a),"final Save waits for original preparation");
        button(a.getWindow().getDecorView(),"カラー").performClick();editable(a);
      });
      t.waitForIdleSync();
      t.runOnMainSync(()->{
        changePad(a,.61f,.42f);
        button(a.getWindow().getDecorView(),"モノクロ").performClick();latest[0]=state(a).getFloatArray("values");require(latest[0][RawEditState.MONO]==1,"latest waiting edit includes Mono");
        require(waiting(a),"latest edits leave wait active");
      });
      gate.close();
      await(()->originalDialog(t),120,"preparation automatically opens original-size save confirmation");
      require(created(context,baseline).isEmpty(),"original confirmation does not create a file before positive Save");
      t.runOnMainSync(()->require(Arrays.equals(latest[0],state(a).getFloatArray("values")),"waiting preserves latest edit snapshot"));
      confirmDialog(t);
      await(()->{
        Map<Long,Integer> made=created(context,baseline);require(made.size()<=1,"preserve ambiguous outputs");
        return made.size()==1&&made.values().iterator().next()==0&&onMain(t,()->Arrays.equals(latest[0],state(a).getFloatArray("saved"))&&save(a).isEnabled());
      },120,"original-size export with latest edit completes");
      Map<Long,Integer> made=created(context,baseline);require(made.size()==1,"one unambiguous synthetic UI JPEG");Uri output=ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,made.keySet().iterator().next());
      BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
      try(InputStream in=context.getContentResolver().openInputStream(output)){BitmapFactory.decodeStream(in,null,bounds);}
      require(bounds.outWidth==6000&&bounds.outHeight==4000,"published JPEG retains original dimensions");
      BitmapFactory.Options sample=new BitmapFactory.Options();sample.inSampleSize=64;Bitmap decoded;
      try(InputStream in=context.getContentResolver().openInputStream(output)){decoded=BitmapFactory.decodeStream(in,null,sample);}
      require(decoded!=null,"bounded JPEG sample decodes");
      try{int color=decoded.getPixel(decoded.getWidth()/2,decoded.getHeight()/2);require(Color.blue(color)>Color.red(color)&&Color.red(color)>Color.green(color),"JPEG includes latest Mono tint from waiting edits");}finally{decoded.recycle();}
    }finally {
      gate.close();
      if(activity!=null){RawEditActivity closing=activity;t.runOnMainSync(closing::finish);}
      if(gate.task!=null)gate.task.get(10,TimeUnit.SECONDS);
      t.waitForIdleSync();
      // Activity close requests GL export abort; main-idle alone does not prove that asynchronous cleanup finished.
      // Remove only the single currently observed owned row. Never broaden deletion when output identity is ambiguous.
      Map<Long,Integer> made=created(context,baseline);
      if(made.size()==1){Uri only=ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,made.keySet().iterator().next());require(context.getContentResolver().delete(only,null,null)==1,"remove only unambiguous new owned synthetic JPEG");}
      require(made.size()<=1,"ambiguous new outputs are preserved for inspection");
    }
  }
}
