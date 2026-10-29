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
  private final ScaleGestureDetector scale;
  private final GestureDetector gesture;
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
    scale=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
      public boolean onScale(ScaleGestureDetector d){zoom=Math.max(1,Math.min(4,zoom*d.getScaleFactor()));invalidate();return true;}
    });
    gesture=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){
      public boolean onDown(MotionEvent e){return true;}
      public boolean onDoubleTap(MotionEvent e){toggle();return true;}
    });
  }
  void replace(Bitmap next){image=next;invalidate();}
  void replace(Bitmap next,long ticket,long inputNs){pendingTicket=ticket;pendingInput=inputNs;replace(next);}
  void toggle(){zoom=zoom>1?1:2.5f;dx=dy=0;invalidate();}
  void clear(){replace(null);}
  protected void onDraw(Canvas c){
    c.drawColor(Color.BLACK);if(image==null||image.isRecycled())return;
    float fit=Math.min(getWidth()/(float)image.getWidth(),getHeight()/(float)image.getHeight());
    float w=image.getWidth()*fit*zoom,h=image.getHeight()*fit*zoom;
    dx=Math.max(-Math.max(0,(w-getWidth())/2),Math.min(Math.max(0,(w-getWidth())/2),dx));
    dy=Math.max(-Math.max(0,(h-getHeight())/2),Math.min(Math.max(0,(h-getHeight())/2),dy));
    c.drawBitmap(image,null,new RectF((getWidth()-w)/2+dx,(getHeight()-h)/2+dy,(getWidth()+w)/2+dx,(getHeight()+h)/2+dy),paint);
  }
  public boolean onTouchEvent(MotionEvent e){
    scale.onTouchEvent(e);gesture.onTouchEvent(e);
    if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&!scale.isInProgress()&&e.getPointerCount()==1&&zoom>1){dx+=e.getX()-lastX;dy+=e.getY()-lastY;invalidate();}
    lastX=e.getX();lastY=e.getY();if(e.getActionMasked()==MotionEvent.ACTION_UP)performClick();return true;
  }
  public boolean performClick(){super.performClick();return true;}
}
