package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.*;
import java.io.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic Activity and real View dispatch; no photo or production test hook. */
final class RawEditGuidesChecks {
  private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  private static void onMain(Instrumentation t,Runnable action){
    Throwable[] failure={null};t.runOnMainSync(()->{try{action.run();}catch(Throwable thrown){failure[0]=thrown;}});
    if(failure[0] instanceof Error)throw (Error)failure[0];
    if(failure[0] instanceof RuntimeException)throw (RuntimeException)failure[0];
    if(failure[0]!=null)throw new AssertionError("guide UI check failed",failure[0]);
  }
  private static <T> T find(View v,Class<T> type){if(type.isInstance(v))return type.cast(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){T next=find(((ViewGroup)v).getChildAt(i),type);if(next!=null)return next;}return null;}
  private static Button button(View v,String text){if(v instanceof Button&&text.contentEquals(((Button)v).getText()))return (Button)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button b=button(((ViewGroup)v).getChildAt(i),text);if(b!=null)return b;}return null;}
  private static float[] values(RawEditActivity activity){Bundle b=new Bundle();activity.onSaveInstanceState(b);return b.getFloatArray("values");}
  private static void event(View target,long down,long time,int action,float x,float y){MotionEvent e=MotionEvent.obtain(down,time,action,target.getWidth()*x,target.getHeight()*y,0);e.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{target.dispatchTouchEvent(e);}finally{e.recycle();}}
  private static void tap(View target,long time,float x,float y){event(target,time,time,MotionEvent.ACTION_DOWN,x,y);event(target,time,time+30,MotionEvent.ACTION_UP,x,y);}
  private static void doubleTap(View target,float x,float y){long now=SystemClock.uptimeMillis();tap(target,now,x,y);tap(target,now+120,x,y);}
  private static float normalized(float value,int key){return (value-RawEditState.min(key))/(RawEditState.max(key)-RawEditState.min(key));}
  private static float[] planePoint(View plane,float[] values,int xKey,int yKey){return new float[]{normalized(values[xKey],xKey),1-normalized(values[yKey],yKey)};}
  private static float[] axisPoint(View axis,float[] values,int key,boolean vertical){return vertical?new float[]{.5f,1-normalized(values[key],key)}:new float[]{normalized(values[key],key),.5f};}
  private static void setAxis(View axis,int key,float value){Bundle args=new Bundle();args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,value);require(axis.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),args),"AX setup sets a nondefault cursor value");}
  private static View picture(RawEditPad pad){ViewParent parent=pad.getParent();return parent instanceof View?(View)parent:pad;}
  private static float[] inPicture(RawEditPad pad,View plane,float[] point){View target=picture(pad);int[] targetXY=new int[2],planeXY=new int[2];target.getLocationInWindow(targetXY);plane.getLocationInWindow(planeXY);return new float[]{(planeXY[0]-targetXY[0]+plane.getWidth()*point[0])/target.getWidth(),(planeXY[1]-targetXY[1]+plane.getHeight()*point[1])/target.getHeight()};}
  private static void eventAtCursor(RawEditPad pad,View plane,float[] values,int xKey,int yKey,long down,long time,int action){float[] xy=inPicture(pad,plane,planePoint(plane,values,xKey,yKey));event(picture(pad),down,time,action,xy[0],xy[1]);}
  private static void tapCursor(RawEditPad pad,View plane,float[] values,int xKey,int yKey){long now=SystemClock.uptimeMillis();eventAtCursor(pad,plane,values,xKey,yKey,now,now,MotionEvent.ACTION_DOWN);eventAtCursor(pad,plane,values,xKey,yKey,now,now+30,MotionEvent.ACTION_UP);}
  private static void doubleTapCursor(View target,float[] point){doubleTap(target,point[0],point[1]);}
  private static float[] thumbPoint(SeekBar bar){require(bar.getWidth()>0&&bar.getHeight()>0&&bar.getThumb()!=null,"auxiliary thumb is laid out");Bitmap frame=Bitmap.createBitmap(bar.getWidth(),bar.getHeight(),Bitmap.Config.ARGB_8888);try{bar.draw(new Canvas(frame));Rect thumb=bar.getThumb().getBounds();return new float[]{(thumb.centerX()+bar.getPaddingLeft()-bar.getThumbOffset())/(float)bar.getWidth(),.5f};}finally{frame.recycle();}}
  private static void prepareAuxiliary(Instrumentation t,RawEditActivity activity,int progress)throws Exception{
    onMain(t,()->{SeekBar single=find(activity.getWindow().getDecorView(),SeekBar.class);require(single!=null,"standalone slider exists");Bundle args=new Bundle();args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,(float)progress);require(single.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),args),"AX setup gives auxiliary slider a nondefault value");});
    boolean[] settled={false};float[] observed={Float.NaN,Float.NaN};long deadline=SystemClock.uptimeMillis()+2000;
    while(!settled[0]&&SystemClock.uptimeMillis()<deadline){
      onMain(t,()->{SeekBar single=find(activity.getWindow().getDecorView(),SeekBar.class);
        if(single==null||single.getWidth()<=0||single.getHeight()<=0||!single.isLaidOut()||single.isLayoutRequested()||single.getProgress()!=progress)return;
        // AX may animate the visual thumb after logical progress has already changed.
        float visual=thumbPoint(single)[0]*single.getWidth();float logical=single.getPaddingLeft()+(single.getWidth()-single.getPaddingLeft()-single.getPaddingRight())*single.getProgress()/(float)single.getMax();
        observed[0]=visual;observed[1]=logical;settled[0]=Math.abs(visual-logical)<=1;
      });
      if(!settled[0])Thread.sleep(20);
    }
    require(settled[0],"auxiliary visual thumb settles at AX progress "+progress+"; visual="+observed[0]+" logical="+observed[1]);
  }
  private static RawEditPad pad(RawEditActivity activity){return find(activity.getWindow().getDecorView(),RawEditPad.class);}
  private static void select(Instrumentation t,RawEditActivity activity,String tab){onMain(t,()->button(activity.getWindow().getDecorView(),tab).performClick());t.waitForIdleSync();}
  private static int alpha(RawEditPad pad){Bitmap b=Bitmap.createBitmap(pad.getWidth(),pad.getHeight(),Bitmap.Config.ARGB_8888);try{pad.draw(new Canvas(b));View plane=pad.getChildAt(0);return Color.alpha(b.getPixel(Math.round(plane.getWidth()*.25f),Math.round(plane.getHeight()*.4f)));}finally{b.recycle();}}
  private static void geometry(RawEditPad pad,float[] values,boolean activePlane){
    Bitmap b=Bitmap.createBitmap(pad.getWidth(),pad.getHeight(),Bitmap.Config.ARGB_8888);
    try{pad.draw(new Canvas(b));View plane=pad.getChildAt(0),right=pad.getChildAt(1),bottom=pad.getChildAt(2);int w=plane.getWidth(),h=plane.getHeight();
      require(Color.alpha(b.getPixel(Math.round(w*.1f),Math.round(h*.1f)))==0,"plane remains unfilled");
      require(Color.alpha(b.getPixel(Math.round(w*.25f),Math.round(h*.4f)))<=40,"grid is faint 12 percent white");
      float[] planePoint=planePoint(plane,values,0,1);int planeRing=Color.alpha(b.getPixel(Math.max(0,Math.min(w-1,Math.round(planePoint[0]*w+px(9,pad)))),Math.max(0,Math.min(h-1,Math.round(planePoint[1]*h+px(9,pad))))));
      float[] yPoint=axisPoint(right,values,1,true),xPoint=axisPoint(bottom,values,0,false);
      // Interior probes avoid the rail, which remains visible through the translucent fill.
      int rightInterior=Color.alpha(b.getPixel(right.getLeft()+right.getWidth()/2+px(4,pad),Math.round(yPoint[1]*right.getHeight())+px(4,pad))),rightRing=Color.alpha(b.getPixel(right.getLeft()+right.getWidth()/2+Math.round(px(10,pad)),Math.round(yPoint[1]*right.getHeight())));
      int bottomInterior=Color.alpha(b.getPixel(Math.round(xPoint[0]*bottom.getWidth())+px(4,pad),bottom.getTop()+bottom.getHeight()/2+px(4,pad))),bottomRing=Color.alpha(b.getPixel(Math.round(xPoint[0]*bottom.getWidth()),bottom.getTop()+bottom.getHeight()/2+Math.round(px(10,pad))));
      require(activePlane?planeRing>=220:planeRing>=120&&planeRing<=160,activePlane?"active plane 28dp cursor ring uses 95 percent":"plane 28dp cursor ring persists at 55 percent");
      require(rightInterior>=20&&rightInterior<=40&&rightRing>=120&&rightRing<=160,"right 24dp cursor is an outlined 55 percent ring with translucent fill");
      require(bottomInterior>=20&&bottomInterior<=40&&bottomRing>=120&&bottomRing<=160,"bottom 24dp cursor is an outlined 55 percent ring with translucent fill");
    }finally{b.recycle();}
  }
  private static int px(float value,RawEditPad pad){return Math.round(value*pad.getResources().getDisplayMetrics().density);}
  private static int sample(Instrumentation t,RawEditPad pad){int[] result={-1};onMain(t,()->result[0]=alpha(pad));return result[0];}
  private static void until(long time)throws Exception{long remaining;while((remaining=time-SystemClock.uptimeMillis())>0)Thread.sleep(Math.min(remaining,50));}
  private static void assertOnly(float[] before,float[] after,int... changed){for(int i=0;i<before.length;i++){boolean allowed=false;for(int key:changed)allowed|=key==i;if(!allowed)require(before[i]==after[i],"reset preserves other values including Mono");}}
  private static void isolated(Instrumentation t,RawEditActivity activity){
    onMain(t,()->{
      AtomicReference<RawEditState> state=new AtomicReference<>(new RawEditState().with(RawEditState.MONO,1).with(RawEditState.DEHAZE,.4f));
      RawEditPad p=new RawEditPad(activity,180,state.get(),0,1,"色温度","色かぶり",state::set);int size=Math.round(236*activity.getResources().getDisplayMetrics().density);
      p.measure(View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY));p.layout(0,0,size,size);
      View plane=p.getChildAt(0),vertical=p.getChildAt(1),horizontal=p.getChildAt(2);
      setAxis(vertical,1,.6f);setAxis(horizontal,0,-.5f);float[] point=planePoint(plane,state.get().snapshot(),0,1);
      doubleTapCursor(plane,point);require(state.get().get(0)==0&&state.get().get(1)==0,"cursor double tap resets both plane axes");require(state.get().get(16)==1&&state.get().get(4)==.4f,"plane reset preserves Mono and other tab values");
      p.cancelGuides();setAxis(vertical,1,.4f);setAxis(horizontal,0,.3f);float[] vals=state.get().snapshot();float[] yp=axisPoint(vertical,vals,1,true);doubleTapCursor(vertical,yp);require(state.get().get(0)==vals[0]&&state.get().get(1)==0,"right cursor reset changes only y");
      p.cancelGuides();setAxis(vertical,1,-.4f);setAxis(horizontal,0,.3f);vals=state.get().snapshot();float[] xp=axisPoint(horizontal,vals,0,false);doubleTapCursor(horizontal,xp);require(state.get().get(0)==0&&state.get().get(1)==vals[1],"bottom cursor reset changes only x");
      p.cancelGuides();setAxis(vertical,1,.45f);setAxis(horizontal,0,-.35f);point=planePoint(plane,state.get().snapshot(),0,1);long now=SystemClock.uptimeMillis();tap(plane,now,point[0],point[1]);long second=now+120;event(plane,second,second,MotionEvent.ACTION_DOWN,point[0],point[1]);event(plane,second,second+20,MotionEvent.ACTION_MOVE,point[0]+.2f,point[1]+.2f);event(plane,second,second+40,MotionEvent.ACTION_UP,point[0]+.2f,point[1]+.2f);require(state.get().get(0)>-.35f&&state.get().get(1)<.45f,"cursor drag adjusts relative to DOWN without resetting");
      for(boolean smallMove:new boolean[]{false,true}){
        p.cancelGuides();setAxis(vertical,1,.4f);setAxis(horizontal,0,-.4f);point=planePoint(plane,state.get().snapshot(),0,1);now=SystemClock.uptimeMillis();tap(plane,now,point[0],point[1]);second=now+120;
        event(plane,second,second,MotionEvent.ACTION_DOWN,point[0],point[1]);
        if(smallMove)event(plane,second,second+10,MotionEvent.ACTION_MOVE,point[0]+.01f,point[1]+.01f);
        event(plane,second,second+40,MotionEvent.ACTION_UP,point[0]-.2f,point[1]-.2f);
        require(state.get().get(0)<-.4f&&state.get().get(1)>.4f,"distant final UP applies relative change even when MOVE is absent or within slop");
      }
      p.cancelGuides();setAxis(vertical,1,.35f);setAxis(horizontal,0,.45f);vals=state.get().snapshot();point=planePoint(plane,vals,0,1);now=SystemClock.uptimeMillis();tap(plane,now,point[0],point[1]);second=now+120;event(plane,second,second,MotionEvent.ACTION_DOWN,point[0],point[1]);event(plane,second,second+ViewConfiguration.getLongPressTimeout()+10,MotionEvent.ACTION_UP,point[0],point[1]);require(Arrays.equals(vals,state.get().snapshot()),"long second touch does not reset cursor value");
      p.cancelGuides();setAxis(vertical,1,.35f);setAxis(horizontal,0,.45f);vals=state.get().snapshot();point=planePoint(plane,vals,0,1);now=SystemClock.uptimeMillis();tap(plane,now,point[0],point[1]);tap(plane,now+ViewConfiguration.getDoubleTapTimeout()+100,point[0],point[1]);require(Arrays.equals(vals,state.get().snapshot()),"late second tap does not reset cursor value");
      p.cancelGuides();setAxis(vertical,1,.35f);setAxis(horizontal,0,.45f);vals=state.get().snapshot();point=planePoint(plane,vals,0,1);now=SystemClock.uptimeMillis();tap(plane,now,point[0],point[1]);tap(plane,now+120,Math.min(1,point[0]+.4f),Math.min(1,point[1]+.4f));require(Arrays.equals(vals,state.get().snapshot()),"distant second tap outside cursor does not reset");
      float[] old=state.get().snapshot();plane.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null);require(Arrays.equals(old,state.get().snapshot()),"TalkBack click is not a physical reset");
      AccessibilityNodeInfo info=plane.createAccessibilityNodeInfo();try{require(info.getRangeInfo()!=null&&info.getStateDescription()!=null,"accessibility range and values retained");boolean reset=false;for(AccessibilityNodeInfo.AccessibilityAction action:info.getActionList())reset|=action.getId()==RawEditPad.RESET_ACTION;require(reset,"explicit reset accessibility action");}finally{info.recycle();}
      vertical.performAccessibilityAction(RawEditPad.RESET_ACTION,null);require(state.get().get(1)==0&&state.get().get(0)==old[0],"accessible y-only reset");horizontal.onKeyDown(KeyEvent.KEYCODE_DEL,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DEL));require(state.get().get(0)==0,"Delete resets selected x axis");
      plane.setEnabled(false);old=state.get().snapshot();point=planePoint(plane,old,0,1);doubleTapCursor(plane,point);require(Arrays.equals(old,state.get().snapshot()),"disabled control does not edit");p.cancelGuides();
      AtomicReference<RawEditState> curve=new AtomicReference<>(new RawEditState().with(5,.2f).with(6,.8f));RawEditPad curves=new RawEditPad(activity,180,curve.get(),5,6,"入力","出力",curve::set);curves.measure(View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY));curves.layout(0,0,size,size);point=planePoint(curves.getChildAt(0),curve.get().snapshot(),5,6);doubleTapCursor(curves.getChildAt(0),point);require(curve.get().get(5)==.5f&&curve.get().get(6)==.5f,"physical curve reset uses 0.5 defaults");curves.cancelGuides();
    });
  }
  private static Rect windowBounds(View view){int[] xy=new int[2];view.getLocationInWindow(xy);return new Rect(xy[0],xy[1],xy[0]+view.getWidth(),xy[1]+view.getHeight());}
  private static JSONArray rectangle(Rect r)throws org.json.JSONException{return new JSONArray(new int[]{r.left,r.top,r.right,r.bottom});}
  private static JSONObject captureWindow(Instrumentation t,RawEditActivity activity,File folder,String phase,long down,long released,int fullAlpha)throws Exception {
    int[] size=new int[2],guideAlpha={0};float[][] current={null};Rect[] geometry=new Rect[4];long[] committed={0},requested={0},copied={0};
    CountDownLatch frame=new CountDownLatch(1);
    onMain(t,()->{
      View decor=activity.getWindow().getDecorView();size[0]=decor.getWidth();size[1]=decor.getHeight();
      decor.getViewTreeObserver().registerFrameCommitCallback(()->{committed[0]=SystemClock.uptimeMillis();frame.countDown();});decor.invalidate();
    });
    require(frame.await(3,TimeUnit.SECONDS),"requested guide frame committed before capture");
    Bitmap pixels=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);CountDownLatch copy=new CountDownLatch(1);int[] result={-1};
    try {
      onMain(t,()->{
        RawEditPad p=pad(activity);guideAlpha[0]=alpha(p);current[0]=values(activity);geometry[0]=windowBounds(p);geometry[1]=windowBounds(p.getChildAt(0));geometry[2]=windowBounds(p.getChildAt(1));geometry[3]=windowBounds(p.getChildAt(2));requested[0]=SystemClock.uptimeMillis();
        PixelCopy.request(activity.getWindow(),pixels,status->{result[0]=status;copied[0]=SystemClock.uptimeMillis();copy.countDown();},new Handler(Looper.getMainLooper()));
      });
      require(copy.await(3,TimeUnit.SECONDS)&&result[0]==PixelCopy.SUCCESS,"actual synthetic window pixels copied");
      onMain(t,()->{Button wb=button(activity.getWindow().getDecorView(),"WB");Rect r=windowBounds(wb);int y=r.top+Math.max(1,Math.round(activity.getResources().getDisplayMetrics().density));require((pixels.getPixel(r.centerX(),y)&0xffffff)==0xededea,"capture contains current WB selection line");});
      require(Math.abs(current[0][0]-.6f)<.001f&&Math.abs(current[0][1]-.6f)<.001f,"capture WB is relative +0.6/+0.6");
      if("drag".equals(phase)||"release".equals(phase))require(guideAlpha[0]>=25,"capture shows held guide");
      if("fading".equals(phase))require(guideAlpha[0]>0&&guideAlpha[0]<fullAlpha&&copied[0]-released<3400,"capture observes the fade before it ends");
      if("hidden".equals(phase))require(guideAlpha[0]==0,"capture shows hidden guide and retained cursor");
      try(OutputStream out=new FileOutputStream(new File(folder,phase+".png"))){require(pixels.compress(Bitmap.CompressFormat.PNG,100,out),"synthetic guide PNG written");}
      JSONObject record=new JSONObject();record.put("file",phase+".png");record.put("phase",phase);record.put("frameCommitUptimeMs",committed[0]);record.put("copyRequestUptimeMs",requested[0]);record.put("copyCompleteUptimeMs",copied[0]);record.put("sinceDownMs",requested[0]-down);record.put("sinceUpMs",released==0?JSONObject.NULL:requested[0]-released);record.put("guideProbeAlphaAtRequest",guideAlpha[0]);record.put("temp",current[0][0]);record.put("tint",current[0][1]);
      record.put("pad",rectangle(geometry[0]));record.put("plane",rectangle(geometry[1]));record.put("right",rectangle(geometry[2]));record.put("bottom",rectangle(geometry[3]));return record;
    }finally{pixels.recycle();}
  }
  /** Dedicated screenshot scenario, kept separate so file IO cannot shift functional timing assertions. */
  static void capture(Instrumentation t)throws Exception {
    RawEditActivity activity=(RawEditActivity)t.startActivitySync(new Intent(t.getTargetContext(),RawEditActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("rawEditSynthetic",true));
    try {
      boolean[] ready={false};long deadline=SystemClock.uptimeMillis()+15000;
      while(!ready[0]&&SystemClock.uptimeMillis()<deadline){onMain(t,()->{Button save=button(activity.getWindow().getDecorView(),"保存");ready[0]=save!=null&&save.isEnabled();});Thread.sleep(25);}require(ready[0],"synthetic capture preview ready");select(t,activity,"WB");
      int[] size=new int[2];float[] configuration=new float[2];long[] down={0},released={0};float[][] point={null};
      onMain(t,()->{size[0]=activity.getWindow().getDecorView().getWidth();size[1]=activity.getWindow().getDecorView().getHeight();configuration[0]=activity.getResources().getConfiguration().fontScale;configuration[1]=activity.getResources().getDisplayMetrics().density;RawEditPad p=pad(activity);setAxis(p.getChildAt(2),0,.6f);setAxis(p.getChildAt(1),1,.6f);View plane=p.getChildAt(0);point[0]=inPicture(p,plane,planePoint(plane,values(activity),0,1));down[0]=SystemClock.uptimeMillis();event(picture(p),down[0],down[0],MotionEvent.ACTION_DOWN,point[0][0],point[0][1]);});
      File folder=new File(activity.getCacheDir(),"raw-guides-"+configuration[0]+"-"+size[1]);require(folder.isDirectory()||folder.mkdirs(),"synthetic guide capture folder");
      JSONArray images=new JSONArray();until(down[0]+500);int fullAlpha=sample(t,pad(activity));images.put(captureWindow(t,activity,folder,"drag",down[0],0,fullAlpha));
      onMain(t,()->{released[0]=SystemClock.uptimeMillis();event(picture(pad(activity)),down[0],released[0],MotionEvent.ACTION_UP,point[0][0],point[0][1]);});
      images.put(captureWindow(t,activity,folder,"release",down[0],released[0],fullAlpha));
      until(released[0]+3150);images.put(captureWindow(t,activity,folder,"fading",down[0],released[0],fullAlpha));
      until(released[0]+3550);images.put(captureWindow(t,activity,folder,"hidden",down[0],released[0],fullAlpha));
      JSONObject manifest=new JSONObject();manifest.put("input","debug synthetic analytic preview only");manifest.put("tab","WB");manifest.put("fontScale",configuration[0]);manifest.put("density",configuration[1]);manifest.put("windowWidth",size[0]);manifest.put("windowHeight",size[1]);manifest.put("images",images);
      try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(folder,"manifest.json")),java.nio.charset.StandardCharsets.UTF_8)){out.write(manifest.toString(2));}
    }finally{onMain(t,activity::finish);}
  }
  static void run(Instrumentation t)throws Exception{
    RawEditActivity activity=(RawEditActivity)t.startActivitySync(new Intent(t.getTargetContext(),RawEditActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("rawEditSynthetic",true));
    try {
      boolean[] ready={false};long limit=SystemClock.uptimeMillis()+15000;
      while(!ready[0]&&SystemClock.uptimeMillis()<limit){onMain(t,()->{Button save=button(activity.getWindow().getDecorView(),"保存");ready[0]=save!=null&&save.isEnabled();});Thread.sleep(25);}require(ready[0],"synthetic Activity preview ready");t.waitForIdleSync();
      RawEditPad[] actual={null};onMain(t,()->actual[0]=pad(activity));require(sample(t,actual[0])==0,"before any touch only existing cursor draws");onMain(t,()->geometry(actual[0],values(activity),false));
      long[] release={0};float[][] point={null};onMain(t,()->{View plane=actual[0].getChildAt(0);point[0]=inPicture(actual[0],plane,planePoint(plane,values(activity),0,1));long now=SystemClock.uptimeMillis();event(picture(actual[0]),now,now,MotionEvent.ACTION_DOWN,point[0][0],point[0][1]);});Thread.sleep(180);int full=sample(t,actual[0]);require(full>=25,"guide grid fades in over 120ms without opaque fill");onMain(t,()->geometry(actual[0],values(activity),true));
      Thread.sleep(3400);require(sample(t,actual[0])>=25,"guides stay visible while finger is held");
      final float[][] after={null};onMain(t,()->{long now=SystemClock.uptimeMillis();event(picture(actual[0]),now-3600,now,MotionEvent.ACTION_UP,point[0][0],point[0][1]);release[0]=SystemClock.uptimeMillis();after[0]=values(activity);geometry(actual[0],after[0],false);});
      until(release[0]+2800);require(sample(t,actual[0])>=25,"guide held for three seconds after UP");until(release[0]+3200);int fading=sample(t,actual[0]);require(fading>0&&fading<full,"400ms fade is visible at its midpoint");
      onMain(t,()->{long now=SystemClock.uptimeMillis();event(picture(actual[0]),now,now,MotionEvent.ACTION_DOWN,point[0][0],point[0][1]);});Thread.sleep(180);require(sample(t,actual[0])>=25,"re-touch cancels fade and shows guides again");
      onMain(t,()->{long now=SystemClock.uptimeMillis();event(picture(actual[0]),now,now,MotionEvent.ACTION_CANCEL,point[0][0],point[0][1]);});require(sample(t,actual[0])==0,"cancel hides guides immediately");
      onMain(t,()->{long now=SystemClock.uptimeMillis();tap(picture(actual[0]),now,point[0][0],point[0][1]);});long finalRelease=SystemClock.uptimeMillis();until(finalRelease+3550);require(sample(t,actual[0])==0,"after hold and fade guides disappear");
      onMain(t,()->require(Arrays.equals(after[0],values(activity)),"guide timers do not change edit state"));
      isolated(t,activity);
      select(t,activity,"カラー");onMain(t,()->button(activity.getWindow().getDecorView(),"モノクロ").performClick());
      String[] allTabs={"WB","質感","トーン","明るさ","明暗","白黒","カラー"};int[][] pairs={{0,1},{2,3},{5,6},{7,8},{9,10},{12,13},{14,15}};
      for(int tab=0;tab<allTabs.length;tab++){select(t,activity,allTabs[tab]);final int[] keys=pairs[tab];onMain(t,()->{RawEditPad p=pad(activity);float xv=RawEditState.min(keys[0])+(RawEditState.max(keys[0])-RawEditState.min(keys[0]))*.72f,yv=RawEditState.min(keys[1])+(RawEditState.max(keys[1])-RawEditState.min(keys[1]))*.28f;setAxis(p.getChildAt(2),keys[0],xv);setAxis(p.getChildAt(1),keys[1],yv);float[] old=values(activity);View plane=p.getChildAt(0);doubleTapCursor(picture(p),inPicture(p,plane,planePoint(plane,old,keys[0],keys[1])));float[] next=values(activity);RawEditState defaults=new RawEditState();require(next[keys[0]]==defaults.get(keys[0])&&next[keys[1]]==defaults.get(keys[1]),"cursor double tap resets both axes to defaults");assertOnly(old,next,keys);require(next[RawEditState.MONO]==1,"all paired resets preserve Mono");});}
      select(t,activity,"WB");onMain(t,()->{button(activity.getWindow().getDecorView(),"彩度MAX").performClick();RawEditPad p=pad(activity);setAxis(p.getChildAt(2),0,.45f);setAxis(p.getChildAt(1),1,-.35f);float[] old=values(activity);View plane=p.getChildAt(0);doubleTapCursor(picture(p),inPicture(p,plane,planePoint(plane,old,0,1)));float[] next=values(activity);require(next[0]==0&&next[1]==0,"cursor double tap resets selected values while MAX is active");assertOnly(old,next,0,1);require(button(activity.getWindow().getDecorView(),"彩度MAX").isSelected(),"reset preserves MAX transient mode");});
      for(String tab:new String[]{"質感","明暗"}){
        select(t,activity,tab);prepareAuxiliary(t,activity,135);onMain(t,()->{SeekBar single=find(activity.getWindow().getDecorView(),SeekBar.class);int key="質感".equals(tab)?4:11;float[] old=values(activity);float[] thumb=thumbPoint(single);doubleTap(single,thumb[0],thumb[1]);require(values(activity)[key]==0,"auxiliary thumb double tap resets its own value");assertOnly(old,values(activity),key);});
      }
      select(t,activity,"質感");prepareAuxiliary(t,activity,120);onMain(t,()->{SeekBar single=find(activity.getWindow().getDecorView(),SeekBar.class);int key=4;float[] before=values(activity);float[] thumb=thumbPoint(single);long now=SystemClock.uptimeMillis();event(single,now,now,MotionEvent.ACTION_DOWN,thumb[0],thumb[1]);event(single,now,now+20,MotionEvent.ACTION_MOVE,Math.min(.98f,thumb[0]+.12f),thumb[1]);event(single,now,now+40,MotionEvent.ACTION_UP,Math.min(.98f,thumb[0]+.12f),thumb[1]);float[] dragged=values(activity);require(dragged[key]>before[key],"auxiliary thumb drag changes value by relative movement");assertOnly(before,dragged,key);float[] afterThumb=thumbPoint(single);float blank=afterThumb[0]<.5f?.98f:.02f;now=SystemClock.uptimeMillis();event(single,now,now,MotionEvent.ACTION_DOWN,blank,.5f);event(single,now,now+20,MotionEvent.ACTION_MOVE,afterThumb[0],afterThumb[1]);event(single,now,now+40,MotionEvent.ACTION_UP,afterThumb[0],afterThumb[1]);require(Arrays.equals(dragged,values(activity)),"blank slider drag crossing the thumb does not edit");});
      select(t,activity,"トーン");RawEditPad[] detached={null};onMain(t,()->{detached[0]=pad(activity);View plane=detached[0].getChildAt(0);tapCursor(detached[0],plane,values(activity),5,6);});Thread.sleep(160);require(sample(t,detached[0])>0,"old tab guide shown");select(t,activity,"WB");require(sample(t,detached[0])==0,"tab rebuild cancels old guides and animations");
      // A real parent-dispatched second pointer cancels its child's tap and restores the pre-DOWN state.
      onMain(t,()->{
        RawEditPad p=pad(activity);RawEditImageView image=find(activity.getWindow().getDecorView(),RawEditImageView.class);View picture=(View)image.getParent();View plane=p.getChildAt(0);float[] local=planePoint(plane,values(activity),0,1),xy=inPicture(p,plane,local);float x=xy[0]*picture.getWidth(),y=xy[1]*picture.getHeight();long first=SystemClock.uptimeMillis();MotionEvent firstDown=MotionEvent.obtain(first,first,MotionEvent.ACTION_DOWN,x,y,0);picture.dispatchTouchEvent(firstDown);firstDown.recycle();MotionEvent firstUp=MotionEvent.obtain(first,first+30,MotionEvent.ACTION_UP,x,y,0);picture.dispatchTouchEvent(firstUp);firstUp.recycle();long now=first+120;float[] old=values(activity);
        MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0);picture.dispatchTouchEvent(down);down.recycle();
        MotionEvent.PointerProperties[] ids={new MotionEvent.PointerProperties(),new MotionEvent.PointerProperties()};MotionEvent.PointerCoords[] points={new MotionEvent.PointerCoords(),new MotionEvent.PointerCoords()};for(int i=0;i<2;i++){ids[i].id=i;ids[i].toolType=MotionEvent.TOOL_TYPE_FINGER;points[i].x=x+i*60;points[i].y=y;points[i].pressure=points[i].size=1;}
        MotionEvent two=MotionEvent.obtain(now,now+20,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,ids,points,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);picture.dispatchTouchEvent(two);two.recycle();require(Arrays.equals(old,values(activity)),"pinch cancels second-tap reset candidate and restores pre-DOWN values");require(alpha(p)==0,"pinch hides guides");
        MotionEvent cancel=MotionEvent.obtain(now,now+40,MotionEvent.ACTION_CANCEL,x,y,0);picture.dispatchTouchEvent(cancel);cancel.recycle();
      });
      onMain(t,()->{RawEditPad p=pad(activity);tapCursor(p,p.getChildAt(0),values(activity),0,1);t.callActivityOnStop(activity);});require(sample(t,pad(activity))==0,"Activity background stops guide callbacks");
    }finally{onMain(t,activity::finish);}
  }
}
