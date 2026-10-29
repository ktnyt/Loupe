package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.json.JSONArray;

/** Synthetic input through the actual picture dispatcher; no user image is loaded. */
final class RawEditCursorChecks {
  private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  private static void onMain(Instrumentation t,Runnable action){
    Throwable[] failure={null};
    t.runOnMainSync(()->{try{action.run();}catch(Throwable thrown){failure[0]=thrown;}});
    if(failure[0] instanceof Error)throw (Error)failure[0];
    if(failure[0] instanceof RuntimeException)throw (RuntimeException)failure[0];
    if(failure[0]!=null)throw new AssertionError("cursor UI check failed",failure[0]);
  }
  private static <T> T find(View v,Class<T> type){if(type.isInstance(v))return type.cast(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){T next=find(((ViewGroup)v).getChildAt(i),type);if(next!=null)return next;}return null;}
  private static Button button(View v,String label){if(v instanceof Button&&label.contentEquals(((Button)v).getText()))return (Button)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button next=button(((ViewGroup)v).getChildAt(i),label);if(next!=null)return next;}return null;}
  private static float[] values(RawEditActivity a){Bundle b=new Bundle();a.onSaveInstanceState(b);return b.getFloatArray("values");}
  private static boolean event(View view,long down,long time,int action,float... xy){
    int count=xy.length/2;MotionEvent.PointerProperties[] ids=new MotionEvent.PointerProperties[count];MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[count];
    for(int i=0;i<count;i++){ids[i]=new MotionEvent.PointerProperties();ids[i].id=i;ids[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coords[i]=new MotionEvent.PointerCoords();coords[i].x=xy[2*i];coords[i].y=xy[2*i+1];coords[i].size=coords[i].pressure=1;}
    MotionEvent e=MotionEvent.obtain(down,time,action,count,ids,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);try{return view.dispatchTouchEvent(e);}finally{e.recycle();}
  }
  private static float[] cursor(RawEditPad p,float[] v,int xKey,int yKey,int which){
    View s=p.getChildAt(which);float x=(v[xKey]-RawEditState.min(xKey))/(RawEditState.max(xKey)-RawEditState.min(xKey)),y=1-(v[yKey]-RawEditState.min(yKey))/(RawEditState.max(yKey)-RawEditState.min(yKey));
    return new float[]{p.getLeft()+s.getLeft()+(which==1?s.getWidth()/2f:x*s.getWidth()),p.getTop()+s.getTop()+(which==2?s.getHeight()/2f:y*s.getHeight())};
  }
  private static void drag(View picture,float x,float y,float dx,float dy){long t=SystemClock.uptimeMillis();event(picture,t,t,MotionEvent.ACTION_DOWN,x,y);event(picture,t,t+40,MotionEvent.ACTION_MOVE,x+dx,y+dy);event(picture,t,t+80,MotionEvent.ACTION_UP,x+dx,y+dy);}
  private static void doubleTap(View picture,float x,float y){long t=SystemClock.uptimeMillis();for(int i=0;i<2;i++){long d=t+i*120;event(picture,d,d,MotionEvent.ACTION_DOWN,x,y);event(picture,d,d+30,MotionEvent.ACTION_UP,x,y);}}
  private static void ready(Instrumentation t,RawEditActivity a)throws Exception{boolean[] ready={false};long limit=SystemClock.uptimeMillis()+15000;while(!ready[0]&&SystemClock.uptimeMillis()<limit){onMain(t,()->{Button b=button(a.getWindow().getDecorView(),"保存");ready[0]=b!=null&&b.isEnabled();});Thread.sleep(25);}require(ready[0],"synthetic cursor activity ready");t.waitForIdleSync();}
  private static JSONArray windowBounds(View view)throws org.json.JSONException{int[] xy=new int[2];view.getLocationInWindow(xy);return new JSONArray(new int[]{xy[0],xy[1],xy[0]+view.getWidth(),xy[1]+view.getHeight()});}
  private static JSONObject captureMetadata(RawEditActivity a,String phase){
    try{
      JSONObject m=new JSONObject();View decor=a.getWindow().getDecorView();RawEditImageView image=find(decor,RawEditImageView.class);View root=(View)image.getParent().getParent();int[] xy=new int[2];root.getLocationInWindow(xy);
      android.content.pm.PackageInfo info=a.getPackageManager().getPackageInfo(a.getPackageName(),0);
      m.put("input","debug synthetic 6000x4000 only");m.put("phase",phase);m.put("versionCode",info.getLongVersionCode());m.put("versionName",info.versionName);m.put("fontScale",a.getResources().getConfiguration().fontScale);m.put("density",a.getResources().getDisplayMetrics().density);m.put("uptimeMs",SystemClock.uptimeMillis());
      m.put("window",windowBounds(decor));m.put("content",new JSONArray(new int[]{xy[0]+root.getPaddingLeft(),xy[1]+root.getPaddingTop(),xy[0]+root.getWidth()-root.getPaddingRight(),xy[1]+root.getHeight()-root.getPaddingBottom()}));m.put("photo",windowBounds(image));m.put("pad",windowBounds(find(decor,RawEditPad.class)));m.put("values",new JSONArray(values(a)));m.put("zoom",image.zoom);return m;
    }catch(Exception failure){throw new AssertionError("synthetic capture metadata",failure);}
  }
  private static void capture(Instrumentation t,RawEditActivity a,File folder,String name)throws Exception{
    Bitmap[] bitmap={null};JSONObject[] metadata={null};CountDownLatch done=new CountDownLatch(1);int[] result={-1};
    onMain(t,()->{View decor=a.getWindow().getDecorView();metadata[0]=captureMetadata(a,name);bitmap[0]=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);PixelCopy.request(a.getWindow(),bitmap[0],n->{result[0]=n;done.countDown();},new Handler(Looper.getMainLooper()));});
    try{require(done.await(5,TimeUnit.SECONDS)&&result[0]==PixelCopy.SUCCESS,"synthetic cursor window copied");try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){require(bitmap[0].compress(Bitmap.CompressFormat.PNG,100,out),"synthetic cursor PNG");}metadata[0].put("pixelCopy",result[0]);metadata[0].put("completedUptimeMs",SystemClock.uptimeMillis());try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(folder,name+".json")),java.nio.charset.StandardCharsets.UTF_8)){out.write(metadata[0].toString(2));}}finally{if(bitmap[0]!=null)bitmap[0].recycle();}
  }
  private static void handles(RawEditPad p,float[] v){
    Bitmap b=Bitmap.createBitmap(p.getWidth()+64,p.getHeight()+64,Bitmap.Config.ARGB_8888);
    try{Canvas c=new Canvas(b);c.translate(32,32);p.draw(c);float density=p.getResources().getDisplayMetrics().density;
      for(int i=0;i<3;i++){float[] center=cursor(p,v,0,1,i);int x=Math.round(center[0]-p.getLeft()+32),y=Math.round(center[1]-p.getTop()+32);require(Color.alpha(b.getPixel(x,y))>0,"all three handles retain translucent inner fill without guides");int radius=Math.round((i==0?13:11)*density);require(Color.alpha(b.getPixel(x,y+radius))>60,"all three outlined cursor rings remain visible");}
      View plane=p.getChildAt(0);require(Color.alpha(b.getPixel(32+Math.round(plane.getWidth()*.25f),32+Math.round(plane.getHeight()*.4f)))==0,"guide grid fully hidden");
    }finally{b.recycle();}
  }
  private static void isolatedZoom(Instrumentation t){onMain(t,()->{
    RawEditImageView image=new RawEditImageView(t.getTargetContext());image.layout(0,0,600,400);image.setFullDimensions(1200,800);image.replace(Bitmap.createBitmap(12,8,Bitmap.Config.ARGB_8888));
    image.toggle();require(Math.abs(image.zoom*.5f-1)<.0001f,"native100 uses physical pixels");image.dx=80;image.dy=40;RectF before=image.visibleSource();image.toggle();RectF after=image.visibleSource();require(Math.abs(image.zoom*.5f-1.5f)<.0001f,"native150 uses physical pixels");require(Math.abs(before.centerX()-after.centerX())<.01f&&Math.abs(before.centerY()-after.centerY())<.01f,"100 to150 preserves viewed source center");image.toggle();require(image.zoom==1&&image.dx==0&&image.dy==0,"fit returns centered");
    image.setFullDimensions(300,200);image.toggle();require(Math.abs(image.zoom*2-1)<.0001f,"small source100 may be smaller than fit");image.toggle();require(Math.abs(image.zoom*2-1.5f)<.0001f,"small source150 may be smaller than fit");image.toggle();require(image.zoom==1,"small source returns fit");
    RawEditImageView restoredSmall=new RawEditImageView(t.getTargetContext());restoredSmall.restorePose(.5f,0,0);require(restoredSmall.zoom==.5f,"small saved native pose retained before dimensions");restoredSmall.setFullDimensions(300,200);require(restoredSmall.zoom==.5f,"small saved pose waits for actual viewport");restoredSmall.layout(0,0,600,400);require(restoredSmall.zoom==.5f,"small native pose survives metadata then layout");
    image.setFullDimensions(600,400);image.toggle();require(image.zoom==1,"fit equal100 remains first step");image.toggle();require(image.zoom==1.5f,"equal fit100 still cycles to150");image.restorePose(1.2f,0,0);image.toggle();require(image.zoom==1,"restored pose starts next cycle at100");
  });}
  private static Button described(View v,String label){if(v instanceof Button&&v.getContentDescription()!=null&&label.contentEquals(v.getContentDescription()))return (Button)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button b=described(((ViewGroup)v).getChildAt(i),label);if(b!=null)return b;}return null;}
  /** Start on each sibling in turn; Android must keep both pointers in the first target's stream. */
  private static void siblingPinch(Instrumentation t,RawEditActivity a,boolean sliderFirst)throws Exception{
    onMain(t,()->button(a.getWindow().getDecorView(),"質感").performClick());t.waitForIdleSync();
    boolean[] laidOut={false};long layoutDeadline=SystemClock.uptimeMillis()+5000;
    while(!laidOut[0]&&SystemClock.uptimeMillis()<layoutDeadline){
      onMain(t,()->{SeekBar bar=find(a.getWindow().getDecorView(),SeekBar.class);RawEditPad pad=find(a.getWindow().getDecorView(),RawEditPad.class);laidOut[0]=bar!=null&&bar.getWidth()>0&&bar.getHeight()>0&&bar.isLaidOut()&&!bar.isLayoutRequested()&&pad!=null&&pad.getWidth()>0&&!pad.isLayoutRequested();});
      if(!laidOut[0])Thread.sleep(20);
    }
    require(laidOut[0],"cross-sibling controls have completed actual layout");
    onMain(t,()->{
      View decor=a.getWindow().getDecorView();SeekBar bar=find(decor,SeekBar.class);RawEditPad p=find(decor,RawEditPad.class);RawEditImageView image=find(decor,RawEditImageView.class);image.resetPose();
      int[] origin=new int[2],sliderLocation=new int[2],padLocation=new int[2];decor.getLocationOnScreen(origin);bar.getLocationOnScreen(sliderLocation);p.getLocationOnScreen(padLocation);
      float sx=sliderLocation[0]-origin[0]+bar.getPaddingLeft()+(bar.getWidth()-bar.getPaddingLeft()-bar.getPaddingRight())*bar.getProgress()/bar.getMax(),sy=sliderLocation[1]-origin[1]+bar.getHeight()/2f;
      float[] old=values(a),pc=cursor(p,old,2,3,0);float px=padLocation[0]-origin[0]+pc[0]-p.getLeft(),py=padLocation[1]-origin[1]+pc[1]-p.getTop();
      float x=sliderFirst?sx:px,y=sliderFirst?sy:py,secondX=sliderFirst?px:sx,secondY=sliderFirst?py:sy,d=15*a.getResources().getDisplayMetrics().density;
      require(bar.getWidth()>0&&bar.getHeight()>0&&x>=0&&y>=0&&x<decor.getWidth()&&y<decor.getHeight(),"root gesture starts inside actual laid-out window");View picture=(View)image.getParent();int[] pictureLocation=new int[2];picture.getLocationOnScreen(pictureLocation);float localX=x-pictureLocation[0]+origin[0],localY=y-pictureLocation[1]+origin[1];int hit=p.hitCursor(localX,localY,picture.getWidth(),picture.getHeight());View plane=p.getChildAt(0);
      require(sliderFirst||(plane.getWidth()>0&&plane.getHeight()>0&&plane.isEnabled()&&hit==0),"reverse cursor preflight hit="+hit+" plane="+plane.getWidth()+"x"+plane.getHeight()+" enabled="+plane.isEnabled()+" pad="+p.getWidth()+"x"+p.getHeight()+" local="+localX+","+localY);
      long now=SystemClock.uptimeMillis();boolean downHandled=event(decor,now,now,MotionEvent.ACTION_DOWN,x,y),moveHandled=event(decor,now,now+20,MotionEvent.ACTION_MOVE,x+d,y-(sliderFirst?0:d));
      require(!Arrays.equals(old,values(a)),"root-dispatched first sibling gesture actually edits; sliderFirst="+sliderFirst+" bar="+bar.getWidth()+"x"+bar.getHeight()+" start="+x+","+y+" hit="+hit+" handled="+downHandled+","+moveHandled+" texture="+old[2]+"->"+values(a)[2]+" clarity="+old[3]+"->"+values(a)[3]);float firstX=x+d,firstY=y-(sliderFirst?0:d);
      event(decor,now,now+40,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),firstX,firstY,secondX,secondY);
      require(Arrays.equals(old,values(a)),"cross-sibling second pointer rolls back original adjustment");float zoom=image.zoom;
      event(decor,now,now+60,MotionEvent.ACTION_MOVE,firstX,firstY,firstX+(secondX-firstX)*1.2f,firstY+(secondY-firstY)*1.2f);
      require(image.zoom>zoom,"cross-sibling pointer stream reaches picture pinch");
      event(decor,now,now+80,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),firstX,firstY,secondX,secondY);
      event(decor,now,now+100,MotionEvent.ACTION_MOVE,firstX+d,firstY+d);event(decor,now,now+120,MotionEvent.ACTION_UP,firstX+d,firstY+d);
      require(Arrays.equals(old,values(a)),"cross-sibling remaining finger cannot resume editing");
    });
  }
  private static void loadedOverlay(Instrumentation t,RawEditActivity a){onMain(t,()->{
    RawEditImageView image=find(a.getWindow().getDecorView(),RawEditImageView.class);ViewGroup picture=(ViewGroup)image.getParent();boolean found=false;
    for(int i=0;i<picture.getChildCount();i++){View child=picture.getChildAt(i);
      if(child instanceof TextView&&child.getWidth()==picture.getWidth()&&child.getHeight()==picture.getHeight()){
        found=true;require(((TextView)child).getText().length()==0&&!child.isClickable(),"loaded status overlay is empty and cannot consume photo cursor gestures");
      }
    }
    require(found,"loaded photo status overlay is present in actual window");
  });}
  private static void zoomButton(Instrumentation t,RawEditActivity a)throws Exception{
    onMain(t,()->find(a.getWindow().getDecorView(),RawEditImageView.class).resetPose());t.waitForIdleSync();
    onMain(t,()->{View decor=a.getWindow().getDecorView();RawEditImageView image=find(decor,RawEditImageView.class);float fit=Math.min(image.getWidth()/6000f,image.getHeight()/4000f);
      Button hundred=described(decor,"原寸100%で表示");require(hundred!=null,"zoom button announces next100 transition");hundred.performClick();require(Math.abs(image.zoom*fit-1)<.001f,"zoom button actually selects physical100");
      Button hundredFifty=described(decor,"原寸150%で表示");require(hundredFifty!=null,"100 button announces next150 transition");hundredFifty.performClick();require(Math.abs(image.zoom*fit-1.5f)<.001f,"zoom button actually selects physical150");
      Button fitButton=described(decor,"全体を表示");require(fitButton!=null,"150 button announces nextfit transition");fitButton.performClick();require(image.zoom==1&&described(decor,"原寸100%で表示")!=null,"fit button actually selects fit and announces100");
    });
  }
  static void run(Instrumentation t)throws Exception{
    isolatedZoom(t);
    RawEditActivity a=(RawEditActivity)t.startActivitySync(new Intent(t.getTargetContext(),RawEditActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("rawEditSynthetic",true));
    try{ready(t,a);loadedOverlay(t,a);zoomButton(t,a);siblingPinch(t,a,true);siblingPinch(t,a,false);onMain(t,()->button(a.getWindow().getDecorView(),"WB").performClick());t.waitForIdleSync();RawEditPad[] pad={null};RawEditImageView[] image={null};View[] picture={null};
      onMain(t,()->{pad[0]=find(a.getWindow().getDecorView(),RawEditPad.class);image[0]=find(a.getWindow().getDecorView(),RawEditImageView.class);picture[0]=(View)image[0].getParent();RawEditPad p=pad[0];float[] start=values(a),c=cursor(p,start,0,1,0);float offset=8*a.getResources().getDisplayMetrics().density;long now=SystemClock.uptimeMillis();
        event(picture[0],now,now,MotionEvent.ACTION_DOWN,c[0]+offset,c[1]+offset);require(Arrays.equals(start,values(a)),"off-center grab never jumps onDOWN");event(picture[0],now,now+20,MotionEvent.ACTION_MOVE,c[0]+offset+p.getChildAt(0).getWidth()*.3f,c[1]+offset-p.getChildAt(0).getHeight()*.3f);require(Math.abs(values(a)[0]-.6f)<.001f&&Math.abs(values(a)[1]-.6f)<.001f,"relative grab movement follows both axes");event(picture[0],now,now+40,MotionEvent.ACTION_UP,c[0]+offset+p.getChildAt(0).getWidth()*.3f,c[1]+offset-p.getChildAt(0).getHeight()*.3f);
        p.cancelGuides();handles(p,values(a));
        float[] old=values(a);image[0].toggle();float oldX=image[0].dx;drag(picture[0],4,4,45,35);require(Arrays.equals(old,values(a)),"blank drag changes no edit values");require(image[0].dx!=oldX,"blank drag pans native photo");
        float[] point=cursor(p,old,0,1,0);float sx=point[0]-90*a.getResources().getDisplayMetrics().density;drag(picture[0],sx,point[1],point[0]-sx+50,0);require(Arrays.equals(old,values(a)),"crossing a cursor after blankDOWN never edits");
        float[] right=cursor(p,values(a),0,1,1);float x=values(a)[0];drag(picture[0],right[0],right[1],0,p.getChildAt(1).getHeight()*.1f);require(values(a)[0]==x&&values(a)[1]<old[1],"right cursor edits only y");
        float[] bottom=cursor(p,values(a),0,1,2);float y=values(a)[1];drag(picture[0],bottom[0],bottom[1],-p.getChildAt(2).getWidth()*.1f,0);require(values(a)[1]==y,"bottom cursor preserves y");
        View plane=p.getChildAt(0);Bundle arg=new Bundle();arg.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,1);plane.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),arg);
        float[] edge=cursor(p,values(a),0,1,0);float hit=48*a.getResources().getDisplayMetrics().density,lo=Math.max(0,Math.min(picture[0].getWidth()-hit,edge[0]-hit/2));require(p.hitCursor(lo+1,edge[1],picture[0].getWidth(),picture[0].getHeight())==0,"endpoint target retains full inward-shifted48dp width");
        arg.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,-1);plane.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),arg);float[] minimum=cursor(p,values(a),0,1,0);float left=Math.max(0,Math.min(picture[0].getWidth()-hit,minimum[0]-hit/2));require(p.hitCursor(left+hit-1,minimum[1],picture[0].getWidth(),picture[0].getHeight())==0,"minimum cursor retains full inward-shifted48dp target");
        arg.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,1);plane.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),arg);
        float[] r=cursor(p,values(a),0,1,1);require(p.hitCursor(r[0],r[1],picture[0].getWidth(),picture[0].getHeight())==1,"nearest right handle wins overlapping target");
        plane.performAccessibilityAction(RawEditPad.RESET_ACTION,null);require(values(a)[0]==0&&values(a)[1]==0,"AX plane reset retains contract");
        float[] c2=cursor(p,values(a),0,1,0);drag(picture[0],c2[0],c2[1],p.getChildAt(0).getWidth()*.1f,-p.getChildAt(0).getHeight()*.1f);c2=cursor(p,values(a),0,1,0);doubleTap(picture[0],c2[0],c2[1]);require(values(a)[0]==0&&values(a)[1]==0,"cursor physical doubletap resets its pair");
      });
      // Real two-pointer transition rolls an in-progress edit back before pinch owns it.
      onMain(t,()->{RawEditPad p=pad[0];float[] old=values(a),c=cursor(p,old,0,1,0);long now=SystemClock.uptimeMillis();event(picture[0],now,now,MotionEvent.ACTION_DOWN,c);event(picture[0],now,now+20,MotionEvent.ACTION_MOVE,c[0]+40,c[1]-40);require(!Arrays.equals(old,values(a)),"pre-pinch drag actually edits");event(picture[0],now,now+40,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),c[0]+40,c[1]-40,c[0]+100,c[1]-40);require(Arrays.equals(old,values(a)),"second pointer rolls edit back");event(picture[0],now,now+60,MotionEvent.ACTION_MOVE,c[0],c[1]-40,c[0]+140,c[1]-40);event(picture[0],now,now+80,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),c[0],c[1]-40,c[0]+140,c[1]-40);event(picture[0],now,now+100,MotionEvent.ACTION_MOVE,c[0]+30,c[1]);event(picture[0],now,now+120,MotionEvent.ACTION_UP,c[0]+30,c[1]);require(Arrays.equals(old,values(a)),"one remaining finger cannot resume editing");require(image[0].zoom<=image[0].maxZoom(),"pinch respects native150 maximum");doubleTap(picture[0],4,4);float fit=Math.min(image[0].getWidth()/6000f,image[0].getHeight()/4000f);require(Math.abs(image[0].zoom*fit-1)<.001f,"arbitrary pinch next doubletap100");
        float[] at=cursor(p,values(a),0,1,0);long cancel=SystemClock.uptimeMillis();event(picture[0],cancel,cancel,MotionEvent.ACTION_DOWN,at);event(picture[0],cancel,cancel+20,MotionEvent.ACTION_CANCEL,at);float[] stopped=values(a);event(picture[0],cancel,cancel+40,MotionEvent.ACTION_MOVE,at[0]+50,at[1]);require(Arrays.equals(stopped,values(a)),"cancel cannot continue editing");
        p.getChildAt(0).setEnabled(false);drag(picture[0],at[0],at[1],30,30);require(Arrays.equals(stopped,values(a)),"disabled cursor cannot edit");p.getChildAt(0).setEnabled(true);
      });
      for(String tab:new String[]{"質感","明暗"}){onMain(t,()->button(a.getWindow().getDecorView(),tab).performClick());t.waitForIdleSync();onMain(t,()->{SeekBar bar=find(a.getWindow().getDecorView(),SeekBar.class);int key="質感".equals(tab)?4:11;float[] old=values(a);float cy=bar.getHeight()/2f,cx=bar.getPaddingLeft()+(bar.getWidth()-bar.getPaddingLeft()-bar.getPaddingRight())*bar.getProgress()/bar.getMax();drag(bar,cx,cy,bar.getWidth()*.12f,0);require(values(a)[key]>old[key],"auxiliary thumb relative drag edits own value");old=values(a);drag(bar,1,cy,bar.getWidth()*.8f,0);require(Arrays.equals(old,values(a)),"auxiliary empty drag crossing thumb preserves values");cx=bar.getPaddingLeft()+(bar.getWidth()-bar.getPaddingLeft()-bar.getPaddingRight())*bar.getProgress()/bar.getMax();doubleTap(bar,cx,cy);require(values(a)[key]==0,"auxiliary cursor doubletap reset");});}
      // Keep the screenshot scenario separate from the assertions so capture IO cannot skew fade timing.
      onMain(t,()->{button(a.getWindow().getDecorView(),"WB").performClick();image[0].resetPose();pad[0]=find(a.getWindow().getDecorView(),RawEditPad.class);pad[0].getChildAt(0).performAccessibilityAction(RawEditPad.RESET_ACTION,null);});t.waitForIdleSync();
      File folder=new File(a.getCacheDir(),"raw-cursor");require(folder.isDirectory()||folder.mkdirs(),"synthetic cursor capture folder");long[] held={0};float[][] end={null};
      onMain(t,()->{RawEditPad p=pad[0];float[] c=cursor(p,values(a),0,1,0);held[0]=SystemClock.uptimeMillis();end[0]=new float[]{c[0]+p.getChildAt(0).getWidth()*.3f,c[1]-p.getChildAt(0).getHeight()*.3f};event(picture[0],held[0],held[0],MotionEvent.ACTION_DOWN,c);event(picture[0],held[0],held[0]+40,MotionEvent.ACTION_MOVE,end[0]);});Thread.sleep(180);capture(t,a,folder,"drag");
      onMain(t,()->event(picture[0],held[0],SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,end[0]));Thread.sleep(3550);onMain(t,()->{require(Math.abs(values(a)[0]-.6f)<.001f&&Math.abs(values(a)[1]-.6f)<.001f,"captureWB+.6+.6");handles(pad[0],values(a));});capture(t,a,folder,"hidden");
      Bundle[] saved={null};onMain(t,()->{saved[0]=new Bundle();a.onSaveInstanceState(saved[0]);});
      Instrumentation.ActivityMonitor monitor=t.addMonitor(RawEditActivity.class.getName(),null,false);
      onMain(t,a::recreate);
      RawEditActivity restored=(RawEditActivity)t.waitForMonitorWithTimeout(monitor,10000);t.removeMonitor(monitor);require(restored!=null,"cursor Activity recreated");
      try{ready(t,restored);onMain(t,()->{Bundle actual=new Bundle();restored.onSaveInstanceState(actual);require(Arrays.equals(saved[0].getFloatArray("values"),actual.getFloatArray("values")),"recreate retains cursor values");require(Math.abs(saved[0].getFloat("zoom")-actual.getFloat("zoom"))<.001f,"recreate retains viewport zoom");});}finally{onMain(t,restored::finish);}
    }finally{onMain(t,a::finish);}
  }
}
