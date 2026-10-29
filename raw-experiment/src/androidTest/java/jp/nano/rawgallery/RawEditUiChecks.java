package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Synthetic data only: real View dispatch, fixed geometry, cursor-only pixels and two pointers. */
final class RawEditUiChecks {
  private static void require(boolean ok,String text){if(!ok)throw new AssertionError(text);}
  private static <T> T find(View v,Class<T> type){if(type.isInstance(v))return type.cast(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){T result=find(((ViewGroup)v).getChildAt(i),type);if(result!=null)return result;}return null;}
  private static Button button(View v,String text){if(v instanceof Button&&text.contentEquals(((Button)v).getText()))return (Button)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button b=button(((ViewGroup)v).getChildAt(i),text);if(b!=null)return b;}return null;}
  private static Rect bounds(View v){int[] p=new int[2];v.getLocationOnScreen(p);return new Rect(p[0],p[1],p[0]+v.getWidth(),p[1]+v.getHeight());}
  private static float[] values(RawEditActivity a){Bundle b=new Bundle();a.onSaveInstanceState(b);return b.getFloatArray("values");}
  private static void touch(View picture,long time,int action,float... xy){
    int count=xy.length/2;MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[count];MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[count];
    for(int i=0;i<count;i++){properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coords[i]=new MotionEvent.PointerCoords();coords[i].x=xy[2*i];coords[i].y=xy[2*i+1];coords[i].pressure=coords[i].size=1;}
    MotionEvent e=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,count,properties,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);picture.dispatchTouchEvent(e);e.recycle();
  }
  static void run(Instrumentation t)throws Exception{
    RawEditActivity a=(RawEditActivity)t.startActivitySync(new Intent(t.getTargetContext(),RawEditActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("rawEditSynthetic",true));
    try{
      boolean[] loaded={false};long until=SystemClock.uptimeMillis()+10000;
      while(!loaded[0]&&SystemClock.uptimeMillis()<until){t.runOnMainSync(()->{Button b=button(a.getWindow().getDecorView(),"保存");loaded[0]=b!=null&&b.isEnabled();});Thread.sleep(25);}
      require(loaded[0],"synthetic preview loaded");t.waitForIdleSync();
      Rect[] original={null};
      for(String tab:new String[]{"WB","質感","トーン","明るさ","明暗","白黒","カラー"}){
        t.runOnMainSync(()->button(a.getWindow().getDecorView(),tab).performClick());t.waitForIdleSync();
        t.runOnMainSync(()->{RawEditPad pad=find(a.getWindow().getDecorView(),RawEditPad.class);Rect r=bounds(pad);if(original[0]==null)original[0]=r;require(original[0].equals(r),"all seven tabs preserve control geometry");
          RawEditImageView image=find(a.getWindow().getDecorView(),RawEditImageView.class);ViewGroup root=(ViewGroup)image.getParent().getParent();int available=root.getHeight()-root.getPaddingTop()-root.getPaddingBottom();float density=a.getResources().getDisplayMetrics().density;
          require(Math.abs(image.getHeight()-(available-Math.round(196*density)))<=2,"photo takes all space except 56+56+84 rows");
          require(pad.getChildAt(0).getWidth()==pad.getChildAt(0).getHeight(),"square control");
          Button selected=button(a.getWindow().getDecorView(),tab);require(selected.getLayout()!=null&&selected.getLayout().getLineCount()==1&&selected.getLayout().getEllipsisCount(0)==0,"tab label remains visible without wrapping or ellipsis");
          require(pad.getWidth()<=image.getWidth()&&pad.getHeight()<=image.getHeight(),"overlay fits the photo viewport");});
        java.util.concurrent.CountDownLatch frame=new java.util.concurrent.CountDownLatch(1);
        t.runOnMainSync(()->{View decor=a.getWindow().getDecorView();decor.getViewTreeObserver().registerFrameCommitCallback(frame::countDown);decor.invalidate();});
        require(frame.await(3,java.util.concurrent.TimeUnit.SECONDS),"new tab frame committed before capture");
        Bitmap capture=t.getUiAutomation().takeScreenshot();require(capture!=null,"synthetic UI capture");
        java.io.File folder=new java.io.File(a.getCacheDir(),"raw-ui-"+a.getResources().getConfiguration().fontScale+"-"+a.getWindow().getDecorView().getHeight());folder.mkdirs();
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(folder,tab+".png"))){capture.compress(Bitmap.CompressFormat.PNG,100,out);}finally{capture.recycle();}
      }
      t.runOnMainSync(()->{
        float[] before=values(a);button(a.getWindow().getDecorView(),"モノクロ").performClick();require(values(a)[RawEditState.MONO]==1,"Mono persists in edit state");
        for(int i=0;i<RawEditState.MONO;i++)require(values(a)[i]==before[i],"Mono does not replace other parameters");
        button(a.getWindow().getDecorView(),"初期化").performClick();require(values(a)[RawEditState.MONO]==0,"color reset includes Mono");
        button(a.getWindow().getDecorView(),"WB").performClick();before=values(a);button(a.getWindow().getDecorView(),"彩度MAX").performClick();require(Arrays.equals(before,values(a)),"MAX never enters saved state");
        button(a.getWindow().getDecorView(),"質感").performClick();
      });t.waitForIdleSync();
      t.runOnMainSync(()->{
        RawEditPad pad=find(a.getWindow().getDecorView(),RawEditPad.class);RawEditImageView image=find(a.getWindow().getDecorView(),RawEditImageView.class);View picture=(View)image.getParent();
        float[] before=values(a);Rect r=bounds(pad),p=bounds(picture);float x=r.left-p.left,y=r.top-p.top,w=pad.getChildAt(0).getWidth();long time=SystemClock.uptimeMillis();
        touch(picture,time,MotionEvent.ACTION_DOWN,x+w*.4f,y+w*.5f);require(!Arrays.equals(before,values(a)),"first finger changes absolute parameters");
        touch(picture,time,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),x+w*.4f,y+w*.5f,x+w*.6f,y+w*.5f);
        require(Arrays.equals(before,values(a)),"second finger restores pre-gesture parameters");
        touch(picture,time,MotionEvent.ACTION_MOVE,x+w*.2f,y+w*.5f,x+w*.8f,y+w*.5f);require(image.zoom>1&&image.zoom<=4,"two-pointer pinch zooms over the invisible pad");require(Arrays.equals(before,values(a)),"pinch does not change edit parameters");
        touch(picture,time,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),x+w*.2f,y+w*.5f,x+w*.8f,y+w*.5f);
        touch(picture,time,MotionEvent.ACTION_MOVE,x+w*.9f,y+w*.1f);touch(picture,time,MotionEvent.ACTION_UP,x+w*.9f,y+w*.1f);require(Arrays.equals(before,values(a)),"remaining finger does not resume parameter editing");require(original[0].equals(bounds(pad)),"pinch leaves overlay at fixed screen coordinates");
        RawEditPad isolated=new RawEditPad(a,180,new RawEditState(),2,3,"テクスチャ","明瞭度",v->{});int size=Math.round(236*a.getResources().getDisplayMetrics().density);isolated.measure(View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY));isolated.layout(0,0,size,size);
        Bitmap bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);isolated.draw(new Canvas(bitmap));require(Color.alpha(bitmap.getPixel(size/5,size/5))==0,"control plane has no background or grid");int[] pixels=new int[size*size];bitmap.getPixels(pixels,0,size,0,0,size,size);long colored=0;for(int pixel:pixels)if(Color.alpha(pixel)>0)colored++;float density=a.getResources().getDisplayMetrics().density;require(colored>100*density*density&&colored<900*density*density,"only the 28dp cursor draws pixels");bitmap.recycle();
      });
    }finally{t.runOnMainSync(()->a.finish());}
  }
}
