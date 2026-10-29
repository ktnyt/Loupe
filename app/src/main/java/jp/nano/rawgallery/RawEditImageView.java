package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/** An edit preview retains its viewport when the rendered bitmap is replaced. */
final class RawEditImageView extends View {
  private Bitmap image;
  private long pendingTicket=-1,pendingInput;
  float zoom=1,dx,dy,lastX,lastY;
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
  private final RectF destination=new RectF();
  private final GestureDetector gesture;
  private boolean singleDragging,multiSession;
  private int firstPointer=-1,secondPointer=-1;
  private float span,midX,midY;
  RawEditImageView(Context c){
    super(c);
    getViewTreeObserver().addOnPreDrawListener(()->{
      if(pendingTicket>=0&&isHardwareAccelerated()){
        long ticket=pendingTicket,started=pendingInput;pendingTicket=-1;
        getViewTreeObserver().registerFrameCommitCallback(()->RawEditLog.frame(ticket,started));
      }
      return true;
    });
    setContentDescription("写真。ピンチで拡大、ダブルタップで全体へ切り替え");
    gesture=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){
      public boolean onDown(MotionEvent e){return true;}
      public boolean onDoubleTap(MotionEvent e){toggle();return true;}
    });
  }
  void replace(Bitmap next){image=next;invalidate();}
  void replace(Bitmap next,long ticket,long inputNs){pendingTicket=ticket;pendingInput=inputNs;replace(next);}
  void toggle(){zoom=zoom>1?1:2.5f;dx=dy=0;clamp();invalidate();}
  void clear(){replace(null);}
  private void clamp(){
    if(image==null||image.isRecycled()||getWidth()==0||getHeight()==0)return;
    float fit=Math.min(getWidth()/(float)image.getWidth(),getHeight()/(float)image.getHeight());
    float maxX=Math.max(0,(image.getWidth()*fit*zoom-getWidth())/2),maxY=Math.max(0,(image.getHeight()*fit*zoom-getHeight())/2);
    dx=Math.max(-maxX,Math.min(maxX,dx));dy=Math.max(-maxY,Math.min(maxY,dy));
  }
  protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);clamp();}
  protected void onDraw(Canvas c){
    c.drawColor(Color.BLACK);if(image==null||image.isRecycled())return;
    clamp();
    float fit=Math.min(getWidth()/(float)image.getWidth(),getHeight()/(float)image.getHeight());
    float w=image.getWidth()*fit*zoom,h=image.getHeight()*fit*zoom;
    destination.set((getWidth()-w)/2+dx,(getHeight()-h)/2+dy,(getWidth()+w)/2+dx,(getHeight()+h)/2+dy);
    c.drawBitmap(image,null,destination,paint);
  }
  /** Parent picture dispatch passes its coordinates; this view fills that same picture frame. */
  boolean multi(MotionEvent e){
    int action=e.getActionMasked();
    if(action==MotionEvent.ACTION_DOWN){multiSession=false;firstPointer=secondPointer=-1;span=0;return false;}
    if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
      boolean handled=multiSession;multiSession=false;firstPointer=secondPointer=-1;span=0;singleDragging=false;return handled;
    }
    int excluded=action==MotionEvent.ACTION_POINTER_UP?e.getActionIndex():-1;
    int count=e.getPointerCount()-(excluded>=0?1:0);
    if(count<2){firstPointer=secondPointer=-1;span=0;return multiSession;}
    multiSession=true;singleDragging=false;
    int a=e.findPointerIndex(firstPointer),b=e.findPointerIndex(secondPointer);
    boolean reset=action==MotionEvent.ACTION_POINTER_DOWN||action==MotionEvent.ACTION_POINTER_UP||a<0||b<0||a==excluded||b==excluded;
    if(reset){
      a=-1;b=-1;
      for(int i=0;i<e.getPointerCount();i++)if(i!=excluded){if(a<0)a=i;else {b=i;break;}}
      firstPointer=e.getPointerId(a);secondPointer=e.getPointerId(b);
    }
    float x=(e.getX(a)+e.getX(b))*.5f,y=(e.getY(a)+e.getY(b))*.5f;
    float distance=(float)Math.hypot(e.getX(a)-e.getX(b),e.getY(a)-e.getY(b));
    if(!reset&&span>0&&distance>0&&image!=null&&!image.isRecycled()){
      float next=Math.max(1,Math.min(4,zoom*distance/span)),ratio=next/zoom;
      dx=x-getWidth()*.5f+(dx-(midX-getWidth()*.5f))*ratio;
      dy=y-getHeight()*.5f+(dy-(midY-getHeight()*.5f))*ratio;
      zoom=next;clamp();invalidate();
    }
    midX=x;midY=y;span=distance;
    return true;
  }
  public boolean onTouchEvent(MotionEvent e){
    int action=e.getActionMasked();
    if(action==MotionEvent.ACTION_CANCEL){gesture.onTouchEvent(e);singleDragging=false;return true;}
    if(multiSession||e.getPointerCount()!=1){singleDragging=false;return true;}
    gesture.onTouchEvent(e);
    if(action==MotionEvent.ACTION_DOWN){singleDragging=true;lastX=e.getX();lastY=e.getY();}
    if(action==MotionEvent.ACTION_MOVE&&singleDragging&&zoom>1){dx+=e.getX()-lastX;dy+=e.getY()-lastY;clamp();invalidate();}
    lastX=e.getX();lastY=e.getY();
    if(action==MotionEvent.ACTION_UP){singleDragging=false;performClick();}
    return true;
  }
  public boolean performClick(){super.performClick();return true;}
}
