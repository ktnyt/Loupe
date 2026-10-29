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
    try(Cursor rows=context.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,new String[]{"_id","is_pending"},
        "owner_package_name=? AND relative_path IN (?,?) AND _display_name LIKE ?",
        new String[]{context.getPackageName(),"Pictures/LoupeDevelop/","Pictures/LoupeDevelop","Loupe-%.jpg"},null)){
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
  static void run(Instrumentation t,Gate gate)throws Exception {
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
