package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/** An edit preview retains its viewport when the rendered bitmap is replaced. */
final class RawEditImageView extends View {
  private Bitmap image,detail;
  private RectF detailSource;
  private int fullWidth,fullHeight;
  private boolean restoredPosePending;
  private long viewportRevision;
  private Runnable viewportListener;
  private boolean listenerPosted;
  private long pendingTicket=-1,pendingInput;
  float zoom=1,dx,dy,lastX,lastY;
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
  private final RectF destination=new RectF();
  private final GestureDetector gesture;
  private boolean singleDragging,multiSession;
  private int zoomStage;
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
    setContentDescription("写真。ピンチで拡大、ダブルタップで原寸100%、150%、全体表示の順に切り替え");
    gesture=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){
      public boolean onDown(MotionEvent e){return true;}
      public boolean onDoubleTap(MotionEvent e){toggle(e.getX(),e.getY());return true;}
    });
  }
  void cancelTouch(){
    singleDragging=false;
    if(gesture!=null){long now=android.os.SystemClock.uptimeMillis();MotionEvent e=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,0,0,0);gesture.onTouchEvent(e);e.recycle();}
  }
  public void setEnabled(boolean enabled){super.setEnabled(enabled);if(!enabled){cancelTouch();multiSession=false;firstPointer=secondPointer=-1;span=0;}}
  void replace(Bitmap next){image=next;invalidate();}
  void replace(Bitmap next,long ticket,long inputNs){pendingTicket=ticket;pendingInput=inputNs;replace(next);}
  void setViewportListener(Runnable listener){viewportListener=listener;}
  /** Apply saved pose only once the original dimensions and the actual viewport are both available. */
  void restorePose(float savedZoom,float savedX,float savedY){
    zoomStage=0;zoom=Float.isFinite(savedZoom)?Math.max(.0001f,savedZoom):1;
    dx=Float.isFinite(savedX)?savedX:0;dy=Float.isFinite(savedY)?savedY:0;
    restoredPosePending=true;clamp();viewportChanged();
  }
  void discardRestoredPose(){
    float z=zoom,x=dx,y=dy;restoredPosePending=false;clamp();changed(z,x,y);
  }
  void resetPose(){
    float z=zoom,x=dx,y=dy;restoredPosePending=false;zoomStage=0;zoom=1;dx=dy=0;changed(z,x,y);
  }
  void setFullDimensions(int w,int h){
    if(w<1||h<1)throw new IllegalArgumentException("Invalid full image dimensions");
    if(w==fullWidth&&h==fullHeight)return;
    fullWidth=w;fullHeight=h;zoomStage=0;clamp();viewportChanged();
  }
  private int sourceWidth(){return fullWidth>0?fullWidth:image!=null&&!image.isRecycled()?image.getWidth():0;}
  private int sourceHeight(){return fullHeight>0?fullHeight:image!=null&&!image.isRecycled()?image.getHeight():0;}
  private float fitScale(){
    int w=sourceWidth(),h=sourceHeight();
    return w>0&&h>0&&getWidth()>0&&getHeight()>0?Math.min(getWidth()/(float)w,getHeight()/(float)h):0;
  }
  private float nativeZoom(){float fit=fitScale();return fit>0?1/fit:1;}
  private float minZoom(){return Math.min(1,nativeZoom());}
  float maxZoom(){return Math.max(1,1.5f*nativeZoom());}
  long viewportRevision(){return viewportRevision;}
  RectF visibleSource(){
    float scale=fitScale()*zoom;int w=sourceWidth(),h=sourceHeight();
    if(scale<=0||w<=0||h<=0)return new RectF();
    float left=(getWidth()-w*scale)*.5f+dx,top=(getHeight()-h*scale)*.5f+dy;
    return new RectF(Math.max(0,-left/scale),Math.max(0,-top/scale),
        Math.min(w,(getWidth()-left)/scale),Math.min(h,(getHeight()-top)/scale));
  }
  int[] detailSize(RectF region){
    if(region==null||region.width()<=0||region.height()<=0||fitScale()<=0)return new int[]{0,0};
    float scale=fitScale()*zoom;
    return new int[]{Math.min(getWidth(),Math.max(1,(int)Math.ceil(region.width()*scale))),
        Math.min(getHeight(),Math.max(1,(int)Math.ceil(region.height()*scale)))};
  }
  /** The view owns displayed bitmaps; release references without recycling RenderThread inputs. */
  void setDetail(Bitmap bitmap,RectF source){
    if(bitmap==null||bitmap.isRecycled()||source==null||source.isEmpty()){
      clearDetail();return;
    }
    detail=bitmap;detailSource=new RectF(source);invalidate();
  }
  void clearDetail(){boolean visible=detail!=null;detail=null;detailSource=null;if(visible)invalidate();}
  private void viewportChanged(){
    viewportRevision++;clearDetail();invalidate();
    // Scheduling outside drawing and coalescing avoids recursive render/listener dispatch.
    if(!listenerPosted&&viewportListener!=null){
      listenerPosted=true;
      post(()->{listenerPosted=false;Runnable listener=viewportListener;if(listener!=null)listener.run();});
    }
  }
  private void changed(float previousZoom,float previousX,float previousY){
    if(zoom!=previousZoom||dx!=previousX||dy!=previousY)viewportChanged();
  }
  int nextZoomStage(){return zoomStage;}
  void toggle(){toggle(getWidth()/2f,getHeight()/2f);}
  private void toggle(float tapX,float tapY){
    restoredPosePending=false;
    float z=zoom,x=dx,y=dy;zoomStage=(zoomStage+1)%3;
    zoom=zoomStage==0?1:nativeZoom()*(zoomStage==2?1.5f:1);
    if(zoomStage==0){dx=dy=0;}
    else if(zoomStage==1){
      // Keep the tapped image point beneath the same physical screen point.
      float ratio=zoom/z;
      dx=tapX-getWidth()/2f+(x-(tapX-getWidth()/2f))*ratio;
      dy=tapY-getHeight()/2f+(y-(tapY-getHeight()/2f))*ratio;
    }else{dx=x*zoom/z;dy=y*zoom/z;}
    clamp();changed(z,x,y);invalidate();
  }
  void clear(){cancelTouch();multiSession=false;firstPointer=secondPointer=-1;span=0;clearDetail();replace(null);}
  private void clamp(){
    if(restoredPosePending){
      if(fullWidth<=0||fullHeight<=0||getWidth()<=0||getHeight()<=0)return;
      restoredPosePending=false;
    }
    zoom=Math.max(minZoom(),Math.min(maxZoom(),zoom));
    float fit=fitScale();if(fit<=0)return;
    float maxX=Math.max(0,(sourceWidth()*fit*zoom-getWidth())/2),maxY=Math.max(0,(sourceHeight()*fit*zoom-getHeight())/2);
    dx=Math.max(-maxX,Math.min(maxX,dx));dy=Math.max(-maxY,Math.min(maxY,dy));
  }
  protected void onSizeChanged(int w,int h,int oldw,int oldh){
    super.onSizeChanged(w,h,oldw,oldh);zoomStage=0;clamp();if(w!=oldw||h!=oldh)viewportChanged();
  }
  protected void onDraw(Canvas c){
    c.drawColor(Color.BLACK);if(image==null||image.isRecycled())return;
    float scale=fitScale()*zoom,w=sourceWidth()*scale,h=sourceHeight()*scale;
    destination.set((getWidth()-w)/2+dx,(getHeight()-h)/2+dy,(getWidth()+w)/2+dx,(getHeight()+h)/2+dy);
    c.drawBitmap(image,null,destination,paint);
    if(detail!=null&&!detail.isRecycled()&&detailSource!=null){
      RectF overlay=new RectF(destination.left+detailSource.left*scale,destination.top+detailSource.top*scale,
          destination.left+detailSource.right*scale,destination.top+detailSource.bottom*scale);
      c.drawBitmap(detail,null,overlay,paint);
    }
  }
  /** Parent picture dispatch passes its coordinates; this view fills that same picture frame. */
  boolean multi(MotionEvent e){
    if(!isEnabled())return false;
    int action=e.getActionMasked();
    if(action==MotionEvent.ACTION_DOWN){multiSession=false;firstPointer=secondPointer=-1;span=0;return false;}
    if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
      boolean handled=multiSession;multiSession=false;firstPointer=secondPointer=-1;span=0;singleDragging=false;return handled;
    }
    int excluded=action==MotionEvent.ACTION_POINTER_UP?e.getActionIndex():-1;
    int count=e.getPointerCount()-(excluded>=0?1:0);
    if(count<2){firstPointer=secondPointer=-1;span=0;return multiSession;}
    restoredPosePending=false;zoomStage=0;multiSession=true;singleDragging=false;
    MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);gesture.onTouchEvent(cancel);cancel.recycle();
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
      float previousZoom=zoom,previousX=dx,previousY=dy;
      float next=Math.max(minZoom(),Math.min(maxZoom(),zoom*distance/span)),ratio=next/zoom;
      dx=x-getWidth()*.5f+(dx-(midX-getWidth()*.5f))*ratio;
      dy=y-getHeight()*.5f+(dy-(midY-getHeight()*.5f))*ratio;
      zoom=next;clamp();changed(previousZoom,previousX,previousY);invalidate();
    }
    midX=x;midY=y;span=distance;
    return true;
  }
  public boolean onTouchEvent(MotionEvent e){
    if(!isEnabled())return false;
    int action=e.getActionMasked();
    if(action==MotionEvent.ACTION_CANCEL){gesture.onTouchEvent(e);singleDragging=false;return true;}
    if(multiSession||e.getPointerCount()!=1){singleDragging=false;return true;}
    gesture.onTouchEvent(e);
    if(action==MotionEvent.ACTION_DOWN){discardRestoredPose();singleDragging=true;lastX=e.getX();lastY=e.getY();}
    if(action==MotionEvent.ACTION_MOVE&&singleDragging){
      float previousZoom=zoom,previousX=dx,previousY=dy;
      dx+=e.getX()-lastX;dy+=e.getY()-lastY;clamp();changed(previousZoom,previousX,previousY);invalidate();
    }
    lastX=e.getX();lastY=e.getY();
    if(action==MotionEvent.ACTION_UP){singleDragging=false;performClick();}
    return true;
  }
  public boolean performClick(){super.performClick();return true;}
}
