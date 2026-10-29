package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic Activity and real View dispatch; no photo or production test hook. */
final class RawEditGuidesChecks {
  private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  private static <T> T find(View v,Class<T> type){if(type.isInstance(v))return type.cast(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){T next=find(((ViewGroup)v).getChildAt(i),type);if(next!=null)return next;}return null;}
  private static Button button(View v,String text){if(v instanceof Button&&text.contentEquals(((Button)v).getText()))return (Button)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button b=button(((ViewGroup)v).getChildAt(i),text);if(b!=null)return b;}return null;}
  private static float[] values(RawEditActivity activity){Bundle b=new Bundle();activity.onSaveInstanceState(b);return b.getFloatArray("values");}
  private static void event(View target,long down,long time,int action,float x,float y){MotionEvent e=MotionEvent.obtain(down,time,action,target.getWidth()*x,target.getHeight()*y,0);target.dispatchTouchEvent(e);e.recycle();}
  private static void tap(View target,long time,float x,float y){event(target,time,time,MotionEvent.ACTION_DOWN,x,y);event(target,time,time+30,MotionEvent.ACTION_UP,x,y);}
  private static void doubleTap(View target,float x,float y){long now=SystemClock.uptimeMillis();tap(target,now,x,y);tap(target,now+120,x,y);}
  private static RawEditPad pad(RawEditActivity activity){return find(activity.getWindow().getDecorView(),RawEditPad.class);}
  private static void select(Instrumentation t,RawEditActivity activity,String tab){t.runOnMainSync(()->button(activity.getWindow().getDecorView(),tab).performClick());t.waitForIdleSync();}
  private static int alpha(RawEditPad pad){Bitmap b=Bitmap.createBitmap(pad.getWidth(),pad.getHeight(),Bitmap.Config.ARGB_8888);try{pad.draw(new Canvas(b));View plane=pad.getChildAt(0);return Color.alpha(b.getPixel(Math.round(plane.getWidth()*.25f),Math.round(plane.getHeight()*.4f)));}finally{b.recycle();}}
  private static void geometry(RawEditPad pad){
    Bitmap b=Bitmap.createBitmap(pad.getWidth(),pad.getHeight(),Bitmap.Config.ARGB_8888);
    try{pad.draw(new Canvas(b));View plane=pad.getChildAt(0),right=pad.getChildAt(1),bottom=pad.getChildAt(2);int w=plane.getWidth(),h=plane.getHeight();
      require(Color.alpha(b.getPixel(Math.round(w*.1f),Math.round(h*.1f)))==0,"plane remains unfilled");
      require(Color.alpha(b.getPixel(Math.round(w*.25f),Math.round(h*.4f)))<=40,"grid is faint 12 percent white");
      require(Color.alpha(b.getPixel(right.getLeft()+right.getWidth()/2,Math.round(h*.2f)))>=170,"right current-value dot visible");
      require(Color.alpha(b.getPixel(Math.round(w*.8f),bottom.getTop()+bottom.getHeight()/2))>=170,"bottom current-value dot visible");
    }finally{b.recycle();}
  }
  private static int sample(Instrumentation t,RawEditPad pad){int[] result={-1};t.runOnMainSync(()->result[0]=alpha(pad));return result[0];}
  private static void until(long time)throws Exception{long remaining;while((remaining=time-SystemClock.uptimeMillis())>0)Thread.sleep(Math.min(remaining,50));}
  private static void assertOnly(float[] before,float[] after,int... changed){for(int i=0;i<before.length;i++){boolean allowed=false;for(int key:changed)allowed|=key==i;if(!allowed)require(before[i]==after[i],"reset preserves other values including Mono");}}
  private static void isolated(Instrumentation t,RawEditActivity activity){
    t.runOnMainSync(()->{
      AtomicReference<RawEditState> state=new AtomicReference<>(new RawEditState().with(RawEditState.MONO,1).with(RawEditState.DEHAZE,.4f));
      RawEditPad p=new RawEditPad(activity,180,state.get(),0,1,"色温度","色かぶり",state::set);int size=Math.round(236*activity.getResources().getDisplayMetrics().density);
      p.measure(View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY));p.layout(0,0,size,size);
      View plane=p.getChildAt(0),vertical=p.getChildAt(1),horizontal=p.getChildAt(2);
      doubleTap(plane,.8f,.2f);require(state.get().get(0)==0&&state.get().get(1)==0,"WB plane resets relative adjustment to zero after second UP");require(state.get().get(16)==1&&state.get().get(4)==.4f,"plane reset preserves Mono and other tab values");
      p.cancelGuides();long now=SystemClock.uptimeMillis();tap(plane,now,.8f,.2f);float x=state.get().get(0);doubleTap(vertical,.5f,.1f);require(state.get().get(0)==x&&state.get().get(1)==0,"right reset changes only y");
      p.cancelGuides();tap(plane,SystemClock.uptimeMillis(),.8f,.2f);float y=state.get().get(1);doubleTap(horizontal,.9f,.5f);require(state.get().get(0)==0&&state.get().get(1)==y,"bottom reset changes only x");
      p.cancelGuides();now=SystemClock.uptimeMillis();tap(plane,now,.8f,.2f);long second=now+120;event(plane,second,second,MotionEvent.ACTION_DOWN,.8f,.2f);event(plane,second,second+20,MotionEvent.ACTION_MOVE,.2f,.8f);event(plane,second,second+40,MotionEvent.ACTION_UP,.2f,.8f);require(state.get().get(0)<-.3f&&state.get().get(1)<-.3f,"second-tap drag adjusts without reset");
      p.cancelGuides();now=SystemClock.uptimeMillis();tap(plane,now,.8f,.2f);second=now+120;event(plane,second,second,MotionEvent.ACTION_DOWN,.8f,.2f);event(plane,second,second+ViewConfiguration.getLongPressTimeout()+10,MotionEvent.ACTION_UP,.8f,.2f);require(state.get().get(0)>.3f&&state.get().get(1)>.3f,"long second touch does not reset");
      p.cancelGuides();now=SystemClock.uptimeMillis();tap(plane,now,.8f,.2f);tap(plane,now+ViewConfiguration.getDoubleTapTimeout()+100,.7f,.3f);require(state.get().get(0)>.2f,"late second tap does not reset");
      p.cancelGuides();now=SystemClock.uptimeMillis();tap(plane,now,.1f,.1f);tap(plane,now+120,.9f,.9f);require(state.get().get(0)>.5f&&state.get().get(1)<-.5f,"distant second tap does not reset");
      float[] old=state.get().snapshot();plane.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null);require(Arrays.equals(old,state.get().snapshot()),"TalkBack click is not a physical reset");
      AccessibilityNodeInfo info=plane.createAccessibilityNodeInfo();try{require(info.getRangeInfo()!=null&&info.getStateDescription()!=null,"accessibility range and values retained");boolean reset=false;for(AccessibilityNodeInfo.AccessibilityAction action:info.getActionList())reset|=action.getId()==RawEditPad.RESET_ACTION;require(reset,"explicit reset accessibility action");}finally{info.recycle();}
      vertical.performAccessibilityAction(RawEditPad.RESET_ACTION,null);require(state.get().get(1)==0&&state.get().get(0)==old[0],"accessible y-only reset");horizontal.onKeyDown(KeyEvent.KEYCODE_DEL,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DEL));require(state.get().get(0)==0,"Delete resets selected x axis");
      plane.setEnabled(false);old=state.get().snapshot();doubleTap(plane,.8f,.2f);require(Arrays.equals(old,state.get().snapshot()),"disabled control does not edit");p.cancelGuides();
      AtomicReference<RawEditState> curve=new AtomicReference<>(new RawEditState());RawEditPad curves=new RawEditPad(activity,180,curve.get(),5,6,"入力","出力",curve::set);curves.measure(View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY));curves.layout(0,0,size,size);doubleTap(curves.getChildAt(0),.8f,.2f);require(curve.get().get(5)==.5f&&curve.get().get(6)==.5f,"curve uses 0.5 defaults");curves.cancelGuides();
    });
  }
  static void run(Instrumentation t)throws Exception{
    RawEditActivity activity=(RawEditActivity)t.startActivitySync(new Intent(t.getTargetContext(),RawEditActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("rawEditSynthetic",true));
    try {
      boolean[] ready={false};long limit=SystemClock.uptimeMillis()+15000;
      while(!ready[0]&&SystemClock.uptimeMillis()<limit){t.runOnMainSync(()->{Button save=button(activity.getWindow().getDecorView(),"保存");ready[0]=save!=null&&save.isEnabled();});Thread.sleep(25);}require(ready[0],"synthetic Activity preview ready");t.waitForIdleSync();
      RawEditPad[] actual={null};t.runOnMainSync(()->actual[0]=pad(activity));require(sample(t,actual[0])==0,"before any touch only existing cursor draws");
      long[] release={0};t.runOnMainSync(()->{View plane=actual[0].getChildAt(0);long now=SystemClock.uptimeMillis();event(plane,now,now,MotionEvent.ACTION_DOWN,.8f,.2f);});Thread.sleep(180);int full=sample(t,actual[0]);require(full>=25,"guide grid fades in over 120ms without opaque fill");t.runOnMainSync(()->geometry(actual[0]));
      Thread.sleep(3400);require(sample(t,actual[0])>=25,"guides stay visible while finger is held");
      final float[][] after={null};t.runOnMainSync(()->{View plane=actual[0].getChildAt(0);long now=SystemClock.uptimeMillis();event(plane,now-3600,now,MotionEvent.ACTION_UP,.8f,.2f);release[0]=SystemClock.uptimeMillis();after[0]=values(activity);});
      until(release[0]+2800);require(sample(t,actual[0])>=25,"guide held for three seconds after UP");until(release[0]+3200);int fading=sample(t,actual[0]);require(fading>0&&fading<full,"400ms fade is visible at its midpoint");
      t.runOnMainSync(()->{View plane=actual[0].getChildAt(0);long now=SystemClock.uptimeMillis();event(plane,now,now,MotionEvent.ACTION_DOWN,.8f,.2f);});Thread.sleep(180);require(sample(t,actual[0])>=25,"re-touch cancels fade and shows guides again");
      t.runOnMainSync(()->{View plane=actual[0].getChildAt(0);long now=SystemClock.uptimeMillis();event(plane,now,now,MotionEvent.ACTION_CANCEL,.8f,.2f);});require(sample(t,actual[0])==0,"cancel hides guides immediately");
      t.runOnMainSync(()->tap(actual[0].getChildAt(0),SystemClock.uptimeMillis(),.8f,.2f));long finalRelease=SystemClock.uptimeMillis();until(finalRelease+3550);require(sample(t,actual[0])==0,"after hold and fade guides disappear");
      t.runOnMainSync(()->require(Arrays.equals(after[0],values(activity)),"guide timers do not change edit state"));
      isolated(t,activity);
      select(t,activity,"カラー");t.runOnMainSync(()->button(activity.getWindow().getDecorView(),"モノクロ").performClick());
      String[] allTabs={"WB","質感","トーン","明るさ","明暗","白黒","カラー"};int[][] pairs={{0,1},{2,3},{5,6},{7,8},{9,10},{12,13},{14,15}};
      for(int tab=0;tab<allTabs.length;tab++){select(t,activity,allTabs[tab]);final int[] keys=pairs[tab];t.runOnMainSync(()->{float[] old=values(activity);doubleTap(pad(activity).getChildAt(0),.8f,.2f);float[] next=values(activity);RawEditState defaults=new RawEditState();require(next[keys[0]]==defaults.get(keys[0])&&next[keys[1]]==defaults.get(keys[1]),"all seven planes restore their actual defaults");assertOnly(old,next,keys);require(next[RawEditState.MONO]==1,"all paired resets preserve Mono");});}
      select(t,activity,"WB");t.runOnMainSync(()->{button(activity.getWindow().getDecorView(),"彩度MAX").performClick();doubleTap(pad(activity).getChildAt(0),.8f,.2f);require(button(activity.getWindow().getDecorView(),"彩度MAX").isSelected(),"reset preserves MAX transient mode");});
      for(String tab:new String[]{"質感","明暗"}){
        select(t,activity,tab);t.runOnMainSync(()->{SeekBar single=find(activity.getWindow().getDecorView(),SeekBar.class);require(single!=null,"standalone slider exists");float[] old=values(activity);doubleTap(single,.8f,.5f);int key="質感".equals(tab)?4:11;require(values(activity)[key]==0,"standalone double tap resets its own value");assertOnly(old,values(activity),key);});
      }
      select(t,activity,"トーン");RawEditPad[] detached={null};t.runOnMainSync(()->{detached[0]=pad(activity);tap(detached[0].getChildAt(0),SystemClock.uptimeMillis(),.8f,.2f);});Thread.sleep(160);require(sample(t,detached[0])>0,"old tab guide shown");select(t,activity,"WB");require(sample(t,detached[0])==0,"tab rebuild cancels old guides and animations");
      // A real parent-dispatched second pointer cancels its child's tap and restores the pre-DOWN state.
      t.runOnMainSync(()->{
        RawEditPad p=pad(activity);RawEditImageView image=find(activity.getWindow().getDecorView(),RawEditImageView.class);View picture=(View)image.getParent();int[] pp=new int[2],sp=new int[2];picture.getLocationOnScreen(pp);p.getLocationOnScreen(sp);float x=sp[0]-pp[0]+p.getChildAt(0).getWidth()*.7f,y=sp[1]-pp[1]+p.getChildAt(0).getHeight()*.3f;long first=SystemClock.uptimeMillis();MotionEvent firstDown=MotionEvent.obtain(first,first,MotionEvent.ACTION_DOWN,x,y,0);picture.dispatchTouchEvent(firstDown);firstDown.recycle();MotionEvent firstUp=MotionEvent.obtain(first,first+30,MotionEvent.ACTION_UP,x,y,0);picture.dispatchTouchEvent(firstUp);firstUp.recycle();long now=first+120;float[] old=values(activity);
        MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0);picture.dispatchTouchEvent(down);down.recycle();
        MotionEvent.PointerProperties[] ids={new MotionEvent.PointerProperties(),new MotionEvent.PointerProperties()};MotionEvent.PointerCoords[] points={new MotionEvent.PointerCoords(),new MotionEvent.PointerCoords()};for(int i=0;i<2;i++){ids[i].id=i;ids[i].toolType=MotionEvent.TOOL_TYPE_FINGER;points[i].x=x+i*60;points[i].y=y;points[i].pressure=points[i].size=1;}
        MotionEvent two=MotionEvent.obtain(now,now+20,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,ids,points,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);picture.dispatchTouchEvent(two);two.recycle();require(Arrays.equals(old,values(activity)),"pinch cancels second-tap reset candidate and restores pre-DOWN values");require(alpha(p)==0,"pinch hides guides");
        MotionEvent cancel=MotionEvent.obtain(now,now+40,MotionEvent.ACTION_CANCEL,x,y,0);picture.dispatchTouchEvent(cancel);cancel.recycle();
      });
      t.runOnMainSync(()->{RawEditPad p=pad(activity);tap(p.getChildAt(0),SystemClock.uptimeMillis(),.8f,.2f);t.callActivityOnStop(activity);});require(sample(t,pad(activity))==0,"Activity background stops guide callbacks");
    }finally{t.runOnMainSync(activity::finish);}
  }
}
