package jp.nano.rawgallery;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.GestureDetector;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.accessibility.AccessibilityManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import java.util.Locale;

/** Transparent cursor controls; physical gestures are captured at their initial handle. */
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
  static final int RESET_ACTION=0x7f0f0056;
  private boolean tracking;
  private int activeAxis=-1;
  private float guideOpacity;
  private ValueAnimator guideAnimation;
  private final Runnable fadeGuides=()->animateGuides(0,400);
  /** Physical taps only; accessibility activation uses the explicit reset action. */
  static final class DoubleTapReset {
    private final GestureDetector detector;private final AccessibilityManager accessibility;
    private final int slop;private float downX,downY;private long downTime;private boolean candidate,moved;
    DoubleTapReset(Context context){
      accessibility=(AccessibilityManager)context.getSystemService(Context.ACCESSIBILITY_SERVICE);slop=ViewConfiguration.get(context).getScaledTouchSlop();
      detector=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
        public boolean onDown(MotionEvent event){return true;}
        public boolean onDoubleTap(MotionEvent event){candidate=true;return true;}
      });detector.setIsLongpressEnabled(false);
    }
    boolean completed(MotionEvent event){
      int action=event.getActionMasked();
      if((accessibility!=null&&accessibility.isTouchExplorationEnabled())||event.getPointerCount()!=1||action==MotionEvent.ACTION_CANCEL){cancel();return false;}
      if(action==MotionEvent.ACTION_DOWN){candidate=false;moved=false;downX=event.getX();downY=event.getY();downTime=event.getEventTime();}
      if((action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP)&&Math.hypot(event.getX()-downX,event.getY()-downY)>slop)moved=true;
      if(action==MotionEvent.ACTION_UP&&(moved||event.getEventTime()-downTime>=ViewConfiguration.getLongPressTimeout())){cancel();return false;}
      detector.onTouchEvent(event);
      if(action==MotionEvent.ACTION_UP){boolean reset=candidate&&!moved;candidate=false;return reset;}
      return false;
    }
    void cancel(){long now=android.os.SystemClock.uptimeMillis();MotionEvent cancel=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,0,0,0);detector.onTouchEvent(cancel);cancel.recycle();candidate=false;moved=false;}
  }
  private void invalidateGuides(){plane.invalidate();vertical.invalidate();horizontal.invalidate();}
  private void animateGuides(float target,long duration){
    if(guideAnimation!=null){guideAnimation.cancel();guideAnimation=null;}
    if(guideOpacity==target)return;
    guideAnimation=ValueAnimator.ofFloat(guideOpacity,target);guideAnimation.setDuration(duration);guideAnimation.setInterpolator(new DecelerateInterpolator());
    guideAnimation.addUpdateListener(animation->{guideOpacity=(float)animation.getAnimatedValue();invalidateGuides();});guideAnimation.start();
  }
  private void showGuides(){removeCallbacks(fadeGuides);animateGuides(1,120);}
  private void releaseGuides(){removeCallbacks(fadeGuides);postDelayed(fadeGuides,3000);}
  void cancelGuides(){
    removeCallbacks(fadeGuides);if(guideAnimation!=null){guideAnimation.cancel();guideAnimation=null;}guideOpacity=0;
    plane.cancelGesture();vertical.cancelGesture();horizontal.cancelGesture();tracking(false);activeAxis=-1;invalidateGuides();
  }
  protected void onDetachedFromWindow(){cancelGuides();super.onDetachedFromWindow();}
  protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE&&plane!=null)cancelGuides();}
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
    if(moved)invalidateGuides();
  }
  // PhotoSurface owns physical dispatch so targets may extend beyond a child's bounds.
  public boolean dispatchTouchEvent(MotionEvent event){return false;}
  int hitCursor(float x,float y,int viewportWidth,int viewportHeight){
    Surface[] surfaces={plane,vertical,horizontal};int best=-1;float distance=Float.MAX_VALUE;
    float hit=px(48);
    for(int i=0;i<surfaces.length;i++){
      Surface v=surfaces[i];if(!v.isEnabled())continue;
      float cx=getLeft()+v.getLeft()+v.cursorX(),cy=getTop()+v.getTop()+v.cursorY();
      float left=Math.max(0,Math.min(viewportWidth-hit,cx-hit/2)),top=Math.max(0,Math.min(viewportHeight-hit,cy-hit/2));
      float d=(x-cx)*(x-cx)+(y-cy)*(y-cy);
      if(x>=left&&x<=left+hit&&y>=top&&y<=top+hit&&d<distance){best=i;distance=d;}
    }
    return best;
  }
  void cancelCursorTaps(){plane.taps.cancel();vertical.taps.cancel();horizontal.taps.cancel();}
  boolean touchCursor(int index,MotionEvent event){
    Surface[] surfaces={plane,vertical,horizontal};Surface v=surfaces[index];
    if(event.getActionMasked()==MotionEvent.ACTION_DOWN)for(Surface other:surfaces)if(other!=v)other.taps.cancel();
    MotionEvent local=MotionEvent.obtain(event);
    local.offsetLocation(-getLeft()-v.getLeft(),-getTop()-v.getTop());
    try{return v.touch(local,true);}finally{local.recycle();}
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
  private void reset(int axis){
    RawEditState next=axis==0?state.reset(xKey,yKey):state.reset(axis==1?xKey:yKey);
    update(next);listener.changed(next);
  }
  private float defaultNormalized(int key){return (new RawEditState().get(key)-RawEditState.min(key))/(RawEditState.max(key)-RawEditState.min(key));}
  private final class Surface extends View {
    private final int axis;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int pointer=-1;
    private float downX,downY,startX,startY;
    private final DoubleTapReset taps;
    Surface(Context c,int axis){
      super(c);this.axis=axis;taps=new DoubleTapReset(c);setFocusable(true);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
      setContentDescription(axis==0?xName+"と"+yName+"の面。左右で"+xName+"、上下で"+yName:axis==1?xName+"（横だけ）":yName+"（縦だけ）");
    }
    protected void onDraw(Canvas c){
      if(isFocused()||isAccessibilityFocused()){
        paint.setStyle(Paint.Style.STROKE);paint.setColor(0xffededea);paint.setStrokeWidth(px(2));
        c.drawRect(px(1),px(1),getWidth()-px(1),getHeight()-px(1),paint);
      }
      if(!isEnabled())return;
      if(guideOpacity>0){
        float w=getWidth(),h=getHeight();paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.BUTT);
        if(axis==0){
          paint.setStrokeWidth(px(1));paint.setColor(0x40000000);paint.setAlpha(Math.round(64*guideOpacity));c.drawRoundRect(-px(.5f),-px(.5f),w+px(.5f),h+px(.5f),px(8.5f),px(8.5f),paint);
          paint.setStrokeWidth(px(1));paint.setColor(0xffededea);paint.setAlpha(Math.round(89*guideOpacity));c.drawRoundRect(px(.5f),px(.5f),w-px(.5f),h-px(.5f),px(8),px(8),paint);
          paint.setAlpha(Math.round(31*guideOpacity));
          for(float f:new float[]{.25f,.5f,.75f}){c.drawLine(w*f,0,w*f,h,paint);c.drawLine(0,h*f,w,h*f,paint);}
          paint.setAlpha(Math.round(82*guideOpacity));float dx=defaultNormalized(xKey)*w,dy=(1-defaultNormalized(yKey))*h;c.drawLine(dx,0,dx,h,paint);c.drawLine(0,dy,w,dy,paint);
        }else{
          paint.setColor(0xffededea);paint.setAlpha(Math.round(89*guideOpacity));paint.setStrokeWidth(px(2));paint.setStrokeCap(Paint.Cap.ROUND);
          if(axis==2)c.drawLine(w/2,0,w/2,h,paint);else c.drawLine(0,h/2,w,h/2,paint);
          paint.setStrokeCap(Paint.Cap.BUTT);paint.setAlpha(Math.round(153*guideOpacity));float center=axis==2?(1-defaultNormalized(yKey))*h:defaultNormalized(xKey)*w;
          if(axis==2)c.drawLine(w/2-px(6),center,w/2+px(6),center,paint);else c.drawLine(center,h/2-px(6),center,h/2+px(6),paint);
        }
      }
      paint.setAlpha(255);
      boolean active=tracking&&activeAxis==axis;float radius=px(axis==0?13:11);
      float x=cursorX(),y=cursorY();
      paint.setStyle(Paint.Style.FILL);paint.setColor(active?0x400f0f10:0x1f0f0f10);c.drawCircle(x,y,radius,paint);
      paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(px(1));paint.setColor(active?0x80000000:0x4d000000);c.drawCircle(x,y,radius+px(1.5f),paint);
      paint.setStrokeWidth(px(2));paint.setColor(active?0xf2ededea:0x8cededea);c.drawCircle(x,y,radius,paint);
    }
    private float cursorX(){return axis==2?getWidth()/2f:normalized(xKey)*getWidth();}
    private float cursorY(){return axis==1?getHeight()/2f:(1-normalized(yKey))*getHeight();}
    private boolean hit(float x,float y){
      float size=px(48),left=Math.max(0,Math.min(getWidth()-size,cursorX()-size/2)),top=Math.max(0,Math.min(getHeight()-size,cursorY()-size/2));
      return x>=left&&x<=left+size&&y>=top&&y<=top+size;
    }
    protected void onFocusChanged(boolean focused,int direction,Rect previous){super.onFocusChanged(focused,direction,previous);invalidate();}
    void cancelGesture(){pointer=-1;taps.cancel();}
    protected void onDetachedFromWindow(){cancelGesture();super.onDetachedFromWindow();}
    public void setEnabled(boolean enabled){super.setEnabled(enabled);if(!enabled&&taps!=null)cancelGuides();}
    public boolean onTouchEvent(MotionEvent e){return touch(e,false);}
    private boolean touch(MotionEvent e,boolean captured){
      if(!isEnabled())return false;
      int action=e.getActionMasked();
      if(action==MotionEvent.ACTION_CANCEL||e.getPointerCount()!=1){cancelGuides();return true;}
      if(action==MotionEvent.ACTION_DOWN){
        if(!captured&&!hit(e.getX(),e.getY())){taps.cancel();return false;}
        pointer=e.getPointerId(0);downX=e.getX();downY=e.getY();startX=normalized(xKey);startY=normalized(yKey);
        activeAxis=axis;tracking(true);invalidateGuides();showGuides();
      }
      if(pointer!=e.getPointerId(0))return false;
      boolean reset=taps.completed(e);
      if(reset){RawEditPad.this.reset(axis);announceForAccessibility((axis==0?xName+"と"+yName:axis==1?xName:yName)+"を初期値に戻しました");}
      else if(action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP){
        if(getWidth()>0&&getHeight()>0)change(axis,startX+(e.getX()-downX)/getWidth(),startY-(e.getY()-downY)/getHeight());
      }
      if(action==MotionEvent.ACTION_UP){pointer=-1;tracking(false);activeAxis=-1;invalidateGuides();releaseGuides();performClick();}
      return true;
    }
    public boolean performClick(){super.performClick();return true;}
    public boolean onKeyDown(int code,KeyEvent event){
      if(!isEnabled())return super.onKeyDown(code,event);
      if(code==KeyEvent.KEYCODE_DEL||code==KeyEvent.KEYCODE_FORWARD_DEL){RawEditPad.this.reset(axis);return true;}
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
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(RESET_ACTION,"初期値に戻す"));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
      }
    }
    public boolean performAccessibilityAction(int action,Bundle args){
      if(isEnabled()){
        if(action==RESET_ACTION){RawEditPad.this.reset(axis);announceForAccessibility("初期値に戻しました");return true;}
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
