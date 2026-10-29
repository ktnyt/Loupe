package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import java.util.Locale;

/** Transparent absolute XY area and separate, invisible single-axis touch bands. */
final class RawEditPad extends FrameLayout {
  interface Listener {
    void changed(RawEditState value);
    default void tracking(boolean active) {}
  }
  private RawEditState state;
  private final int xKey,yKey;
  private final String xName,yName;
  private final Listener listener;
  private final Surface plane,vertical,horizontal;
  private boolean tracking;
  RawEditPad(Context c,int sizeDp,RawEditState initial,int x,int y,String xn,String yn,Listener changed){
    super(c);state=initial;xKey=x;yKey=y;xName=xn;yName=yn;listener=changed;
    setClipChildren(false);setClipToPadding(false);
    plane=new Surface(c,0);vertical=new Surface(c,2);horizontal=new Surface(c,1);
    addView(plane,new LayoutParams(dp(sizeDp),dp(sizeDp)));
    LayoutParams vp=new LayoutParams(dp(48),dp(sizeDp));vp.leftMargin=dp(sizeDp+8);addView(vertical,vp);
    LayoutParams hp=new LayoutParams(dp(sizeDp),dp(48));hp.topMargin=dp(sizeDp+8);addView(horizontal,hp);
    setMinimumWidth(dp(sizeDp+56));setMinimumHeight(dp(sizeDp+56));
  }
  private int dp(float n){return Math.round(px(n));}
  private float px(float n){return n*getResources().getDisplayMetrics().density;}
  static String value(int key,float n){
    if(key==RawEditState.EXPOSURE)return String.format(Locale.ROOT,"%+.2f EV",n);
    if(key==RawEditState.CURVE_X||key==RawEditState.CURVE_Y)return Integer.toString(Math.round(n*100));
    return n==0?"±0":String.format(Locale.ROOT,"%+d",Math.round(n*100));
  }
  void update(RawEditState next){
    boolean moved=state.get(xKey)!=next.get(xKey)||state.get(yKey)!=next.get(yKey);
    state=next;
    if(moved)plane.invalidate();
  }
  private float normalized(int key){return (state.get(key)-RawEditState.min(key))/(RawEditState.max(key)-RawEditState.min(key));}
  private void tracking(boolean next){
    if(tracking==next)return;
    tracking=next;plane.invalidate();listener.tracking(next);
  }
  private void change(int axis,float x,float y){
    RawEditState next=state;
    if(axis!=2)next=next.with(xKey,RawEditState.min(xKey)+Math.max(0,Math.min(1,x))*(RawEditState.max(xKey)-RawEditState.min(xKey)));
    if(axis!=1)next=next.with(yKey,RawEditState.min(yKey)+Math.max(0,Math.min(1,y))*(RawEditState.max(yKey)-RawEditState.min(yKey)));
    update(next);listener.changed(next);
  }
  private final class Surface extends View {
    private final int axis;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int pointer=-1;
    Surface(Context c,int axis){
      super(c);this.axis=axis;setFocusable(true);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
      setContentDescription(axis==0?xName+"と"+yName+"の面。左右で"+xName+"、上下で"+yName:axis==1?xName+"（横だけ）":yName+"（縦だけ）");
    }
    protected void onDraw(Canvas c){
      if(isFocused()||isAccessibilityFocused()){
        paint.setStyle(Paint.Style.STROKE);paint.setColor(0xffededea);paint.setStrokeWidth(px(2));
        c.drawRect(px(1),px(1),getWidth()-px(1),getHeight()-px(1),paint);
      }
      if(axis!=0||!isEnabled())return;
      float x=normalized(xKey)*getWidth(),y=(1-normalized(yKey))*getHeight();
      paint.setStyle(Paint.Style.FILL);paint.setColor(tracking?0x400f0f10:0x1f0f0f10);c.drawCircle(x,y,px(13),paint);
      paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(px(1));paint.setColor(tracking?0x80000000:0x4d000000);c.drawCircle(x,y,px(14.5f),paint);
      paint.setStrokeWidth(px(2));paint.setColor(tracking?0xf2ededea:0x8cededea);c.drawCircle(x,y,px(13),paint);
    }
    protected void onFocusChanged(boolean focused,int direction,Rect previous){super.onFocusChanged(focused,direction,previous);invalidate();}
    public boolean onTouchEvent(MotionEvent e){
      if(!isEnabled())return false;
      int action=e.getActionMasked();
      if(action==MotionEvent.ACTION_CANCEL){pointer=-1;tracking(false);return true;}
      if(e.getPointerCount()!=1)return true;
      if(action==MotionEvent.ACTION_DOWN){pointer=e.getPointerId(0);tracking(true);}
      if(pointer!=e.getPointerId(0))return true;
      if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP){
        if(getWidth()>0&&getHeight()>0)change(axis,e.getX()/getWidth(),1-e.getY()/getHeight());
      }
      if(action==MotionEvent.ACTION_UP){pointer=-1;tracking(false);performClick();}
      return true;
    }
    public boolean performClick(){super.performClick();return true;}
    public boolean onKeyDown(int code,KeyEvent event){
      if(!isEnabled())return super.onKeyDown(code,event);
      float step=event.isShiftPressed()?.05f:.005f;
      if(code==KeyEvent.KEYCODE_DPAD_LEFT&&axis!=2){change(1,normalized(xKey)-step,normalized(yKey));return true;}
      if(code==KeyEvent.KEYCODE_DPAD_RIGHT&&axis!=2){change(1,normalized(xKey)+step,normalized(yKey));return true;}
      if(code==KeyEvent.KEYCODE_DPAD_UP&&axis!=1){change(2,normalized(xKey),normalized(yKey)+step);return true;}
      if(code==KeyEvent.KEYCODE_DPAD_DOWN&&axis!=1){change(2,normalized(xKey),normalized(yKey)-step);return true;}
      return super.onKeyDown(code,event);
    }
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
      super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.SeekBar");
      info.setStateDescription(axis==0?xName+" "+value(xKey,state.get(xKey))+"、"+yName+" "+value(yKey,state.get(yKey)):axis==1?value(xKey,state.get(xKey)):value(yKey,state.get(yKey)));
      int key=axis==2?yKey:xKey;
      info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,RawEditState.min(key),RawEditState.max(key),state.get(key)));
      if(isEnabled()){
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
      }
    }
    public boolean performAccessibilityAction(int action,Bundle args){
      if(isEnabled()){
        int key=axis==2?yKey:xKey;float v=state.get(key),range=RawEditState.max(key)-RawEditState.min(key);
        if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)v+=range*.01f;
        else if(action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)v-=range*.01f;
        else if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()&&args!=null)
          v=args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
        else {boolean handled=super.performAccessibilityAction(action,args);invalidate();return handled;}
        if(!Float.isFinite(v))return false;
        float n=(v-RawEditState.min(key))/range;
        change(axis==2?2:1,axis==2?normalized(xKey):n,axis==2?n:normalized(yKey));return true;
      }
      return super.performAccessibilityAction(action,args);
    }
  }
}
