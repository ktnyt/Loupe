package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.Locale;

/** Square XY area plus non-overlapping external single-axis controls; all share one edit state. */
final class RawEditPad extends LinearLayout {
  interface Listener {void changed(RawEditState value);}
  private RawEditState state;
  private final int xKey,yKey,size;
  private final String xName,yName;
  private final Listener listener;
  private final TextView label;
  private final Surface plane,horizontal,vertical;
  RawEditPad(Context c,int size,RawEditState state,int x,int y,String xn,String yn,Listener changed){
    super(c);this.size=size;this.state=state;xKey=x;yKey=y;xName=xn;yName=yn;listener=changed;
    setOrientation(VERTICAL);
    label=new TextView(c);label.setTextColor(0xffededea);label.setTextSize(13);label.setPadding(0,0,0,dp(8));addView(label);
    FrameLayout frame=new FrameLayout(c);addView(frame,new LayoutParams(dp(size+56),dp(size+56)));
    plane=new Surface(c,0);vertical=new Surface(c,2);horizontal=new Surface(c,1);
    frame.addView(plane,new FrameLayout.LayoutParams(dp(size),dp(size)));
    FrameLayout.LayoutParams vp=new FrameLayout.LayoutParams(dp(48),dp(size));vp.leftMargin=dp(size+8);frame.addView(vertical,vp);
    FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(dp(size),dp(48));hp.topMargin=dp(size+8);frame.addView(horizontal,hp);
    update(state);
  }
  private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
  static String value(int key,float n){
    if(key==RawEditState.EXPOSURE)return String.format(Locale.ROOT,"%+.2f EV",n);
    if(key==RawEditState.CURVE_X||key==RawEditState.CURVE_Y)return Integer.toString(Math.round(n*100));
    return n==0?"±0":String.format(Locale.ROOT,"%+d",Math.round(n*100));
  }
  void update(RawEditState next){
    state=next;label.setText("横 "+xName+" "+value(xKey,state.get(xKey))+"　縦 "+yName+" "+value(yKey,state.get(yKey)));
    if(plane!=null){plane.invalidate();vertical.invalidate();horizontal.invalidate();
      plane.setContentDescription(xName+"と"+yName+"の面。左右で"+xName+"、上下で"+yName+"。"+label.getText());
      horizontal.setContentDescription(xName+"（横だけ） "+value(xKey,state.get(xKey)));
      vertical.setContentDescription(yName+"（縦だけ） "+value(yKey,state.get(yKey)));}
  }
  private float normalized(int key){return (state.get(key)-RawEditState.min(key))/(RawEditState.max(key)-RawEditState.min(key));}
  private void change(int axis,float x,float y){
    RawEditState next=state;
    if(axis!=2)next=next.with(xKey,RawEditState.min(xKey)+Math.max(0,Math.min(1,x))*(RawEditState.max(xKey)-RawEditState.min(xKey)));
    if(axis!=1)next=next.with(yKey,RawEditState.min(yKey)+Math.max(0,Math.min(1,y))*(RawEditState.max(yKey)-RawEditState.min(yKey)));
    update(next);listener.changed(next);
  }
  private final class Surface extends View {
    private final int axis;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    Surface(Context c,int axis){super(c);this.axis=axis;setFocusable(true);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
    private void line(Canvas c,float x,float y,float xx,float yy,int color,float width){paint.setColor(color);paint.setStrokeWidth(dp(width));c.drawLine(x,y,xx,yy,paint);}
    protected void onDraw(Canvas c){
      float w=getWidth(),h=getHeight(),in=dp(12),x=in+normalized(xKey)*(w-2*in),y=h-in-normalized(yKey)*(h-2*in);
      float zx=(0-RawEditState.min(xKey))/(RawEditState.max(xKey)-RawEditState.min(xKey));
      float zy=(0-RawEditState.min(yKey))/(RawEditState.max(yKey)-RawEditState.min(yKey));
      if(xKey==RawEditState.CURVE_X){zx=.5f;zy=.5f;}
      if(axis==0){
        paint.setColor(0xff141416);paint.setStyle(Paint.Style.FILL);c.drawRoundRect(0,0,w,h,dp(8),dp(8),paint);
        paint.setColor(0xff2e2e32);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));c.drawRoundRect(dp(.5f),dp(.5f),w-dp(.5f),h-dp(.5f),dp(8),dp(8),paint);paint.setStyle(Paint.Style.FILL);
        line(c,in+zx*(w-2*in),in,in+zx*(w-2*in),h-in,0xff3a3a3f,1);
        line(c,in,h-in-zy*(h-2*in),w-in,h-in-zy*(h-2*in),0xff3a3a3f,1);
        if(xKey==RawEditState.CURVE_X){
          Path curve=new Path();for(int i=0;i<=100;i++){float xx=in+(w-2*in)*i/100f,yy=h-in-state.curve(i/100f)*(h-2*in);if(i==0)curve.moveTo(xx,yy);else curve.lineTo(xx,yy);}
          paint.setColor(0xffededea);paint.setStrokeWidth(dp(2));paint.setStyle(Paint.Style.STROKE);c.drawPath(curve,paint);paint.setStyle(Paint.Style.FILL);
        } else {
          paint.setColor(0xffa9a9a4);paint.setTextSize(12*getResources().getDisplayMetrics().scaledDensity);
          String left=xKey==RawEditState.TEMP?"寒":"−",right=xKey==RawEditState.TEMP?"暖":"＋";
          c.drawText(left,dp(6),h/2-dp(8),paint);c.drawText(right,w-dp(6)-paint.measureText(right),h/2-dp(8),paint);
          String top=yKey==RawEditState.TINT?"マゼンタ":"＋",bottom=yKey==RawEditState.TINT?"緑":"−";
          c.drawText(top,(w-paint.measureText(top))/2,dp(16),paint);c.drawText(bottom,(w-paint.measureText(bottom))/2,h-dp(6),paint);
        }
        paint.setColor(0xff000000);c.drawCircle(x,y,dp(14),paint);paint.setColor(0xffededea);c.drawCircle(x,y,dp(12),paint);
      } else if(axis==1){
        line(c,in,h/2,w-in,h/2,0xff3a3a3f,4);line(c,in+zx*(w-2*in),h/2-dp(8),in+zx*(w-2*in),h/2+dp(8),0xff6b6b70,2);
        paint.setColor(0xffededea);c.drawCircle(x,h/2,dp(10),paint);
      } else {
        line(c,w/2,in,w/2,h-in,0xff3a3a3f,4);line(c,w/2-dp(8),h-in-zy*(h-2*in),w/2+dp(8),h-in-zy*(h-2*in),0xff6b6b70,2);
        paint.setColor(0xffededea);c.drawCircle(w/2,y,dp(10),paint);
      }
    }
    public boolean onTouchEvent(MotionEvent e){
      if(!isEnabled())return false;
      if(e.getActionMasked()==MotionEvent.ACTION_DOWN)getParent().requestDisallowInterceptTouchEvent(true);
      if(e.getActionMasked()==MotionEvent.ACTION_DOWN||e.getActionMasked()==MotionEvent.ACTION_MOVE||e.getActionMasked()==MotionEvent.ACTION_UP){
        float in=dp(12);change(axis,(e.getX()-in)/(getWidth()-2*in),1-(e.getY()-in)/(getHeight()-2*in));
      }
      if(e.getActionMasked()==MotionEvent.ACTION_UP){performClick();getParent().requestDisallowInterceptTouchEvent(false);}
      if(e.getActionMasked()==MotionEvent.ACTION_CANCEL)getParent().requestDisallowInterceptTouchEvent(false);
      return true;
    }
    public boolean performClick(){super.performClick();return true;}
    public boolean onKeyDown(int code,KeyEvent event){
      float step=event.isShiftPressed()?.05f:.005f;
      if(code==KeyEvent.KEYCODE_DPAD_LEFT&&axis!=2){change(1,normalized(xKey)-step,normalized(yKey));return true;}
      if(code==KeyEvent.KEYCODE_DPAD_RIGHT&&axis!=2){change(1,normalized(xKey)+step,normalized(yKey));return true;}
      if(code==KeyEvent.KEYCODE_DPAD_UP&&axis!=1){change(2,normalized(xKey),normalized(yKey)+step);return true;}
      if(code==KeyEvent.KEYCODE_DPAD_DOWN&&axis!=1){change(2,normalized(xKey),normalized(yKey)-step);return true;}
      return super.onKeyDown(code,event);
    }
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
      super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.SeekBar");
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
        else return super.performAccessibilityAction(action,args);
        if(!Float.isFinite(v))return false;
        float n=(v-RawEditState.min(key))/range;
        change(axis==2?2:1,axis==2?normalized(xKey):n,axis==2?n:normalized(yKey));return true;
      }
      return super.performAccessibilityAction(action,args);
    }
  }
}
