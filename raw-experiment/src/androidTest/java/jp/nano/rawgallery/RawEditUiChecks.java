package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
  private static int brightness(int color){return (Color.red(color)+Color.green(color)+Color.blue(color))/3;}
  private static Rect windowBounds(View view){int[] xy=new int[2];view.getLocationInWindow(xy);return new Rect(xy[0],xy[1],xy[0]+view.getWidth(),xy[1]+view.getHeight());}
  private static void confirmedCapture(Instrumentation t,RawEditActivity activity,String tab)throws Exception {
    int[] size=new int[2];float[] font={0};
    t.runOnMainSync(()->{View decor=activity.getWindow().getDecorView();size[0]=decor.getWidth();size[1]=decor.getHeight();font[0]=activity.getResources().getConfiguration().fontScale;});
    java.io.File folder=new java.io.File(activity.getCacheDir(),"raw-ui-confirmed-"+font[0]+"-"+size[1]);require(folder.isDirectory()||folder.mkdirs(),"confirmed synthetic capture folder");
    long started=SystemClock.uptimeMillis(),deadline=started+5000;JSONArray attempts=new JSONArray();boolean confirmed=false;int attempt=0;
    try {
      while(SystemClock.uptimeMillis()<deadline&&!confirmed){
        attempt++;CountDownLatch frame=new CountDownLatch(1);long[] committed={0};
        t.runOnMainSync(()->{View decor=activity.getWindow().getDecorView();RawEditImageView image=find(decor,RawEditImageView.class);RawEditPad pad=find(decor,RawEditPad.class);decor.getViewTreeObserver().registerFrameCommitCallback(()->{committed[0]=SystemClock.uptimeMillis();frame.countDown();});image.invalidate();pad.invalidate();decor.invalidate();});
        JSONObject record=new JSONObject();record.put("attempt",attempt);
        if(!frame.await(Math.max(1,deadline-SystemClock.uptimeMillis()),TimeUnit.MILLISECONDS)){record.put("failure","frame commit timeout");record.put("elapsedMs",SystemClock.uptimeMillis()-started);attempts.put(record);break;}
        Bitmap capture=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);CountDownLatch copied=new CountDownLatch(1);int[] status={-1},probe=new int[5],coordinates=new int[10];boolean[] copyDone={false},abandoned={false};Object lock=new Object();boolean ownsBitmap=true;long[] copyAt={0};
        try {
          t.runOnMainSync(()->{
            View decor=activity.getWindow().getDecorView();RawEditImageView image=find(decor,RawEditImageView.class);RawEditPad pad=find(decor,RawEditPad.class);Rect selected=windowBounds(button(decor,tab)),photo=windowBounds(image),plane=windowBounds(pad.getChildAt(0));float density=activity.getResources().getDisplayMetrics().density;
            coordinates[0]=selected.centerX();coordinates[1]=selected.top+Math.max(1,Math.round(density));coordinates[2]=photo.centerX();coordinates[3]=photo.centerY();
            coordinates[4]=plane.centerX()+Math.round(13*density);coordinates[5]=plane.centerY();coordinates[6]=plane.centerX()+Math.round(9*density);coordinates[7]=plane.centerY();coordinates[8]=plane.centerX()+Math.round(17*density);coordinates[9]=plane.centerY();
            PixelCopy.request(activity.getWindow(),capture,result->{synchronized(lock){status[0]=result;copyAt[0]=SystemClock.uptimeMillis();copyDone[0]=true;if(abandoned[0])capture.recycle();}copied.countDown();},new Handler(Looper.getMainLooper()));
          });
          if(!copied.await(Math.max(1,deadline-SystemClock.uptimeMillis()),TimeUnit.MILLISECONDS)){
            synchronized(lock){if(!copyDone[0]){abandoned[0]=true;ownsBitmap=false;}}
            record.put("failure","PixelCopy timeout");record.put("elapsedMs",SystemClock.uptimeMillis()-started);attempts.put(record);break;
          }
          record.put("frameCommitElapsedMs",committed[0]-started);record.put("copyElapsedMs",copyAt[0]-started);record.put("pixelCopyStatus",status[0]);record.put("probeCoordinates",new JSONArray(coordinates));
          if(status[0]==PixelCopy.SUCCESS){
            for(int p=0;p<probe.length;p++)probe[p]=capture.getPixel(coordinates[p*2],coordinates[p*2+1]);
            int photo=probe[1],border=probe[2];boolean selected=(probe[0]&0xffffff)==0xededea;
            boolean photoVisible=Color.red(photo)>32&&Color.green(photo)>32&&Color.blue(photo)>32;
            boolean cursorVisible=Color.red(border)>96&&Color.green(border)>96&&Color.blue(border)>96
                &&brightness(border)-Math.max(brightness(probe[3]),brightness(probe[4]))>=15;
            confirmed=selected&&photoVisible&&cursorVisible;
            record.put("tabSelectionVisible",selected);record.put("photoVisible",photoVisible);record.put("cursorBorderVisible",cursorVisible);record.put("probeArgb",new JSONArray(probe));
            if(confirmed||deadline-SystemClock.uptimeMillis()<100){java.io.File output=new java.io.File(folder,tab+(confirmed?"":"-failed")+".png");try(java.io.FileOutputStream stream=new java.io.FileOutputStream(output)){require(capture.compress(Bitmap.CompressFormat.PNG,100,stream),"confirmed synthetic PNG written");}record.put("file",output.getName());}
          }
          record.put("confirmed",confirmed);attempts.put(record);
        }finally{if(ownsBitmap)capture.recycle();}
        if(!confirmed)Thread.sleep(Math.min(50,Math.max(1,deadline-SystemClock.uptimeMillis())));
      }
    }finally{
      JSONObject manifest=new JSONObject();manifest.put("input","debug synthetic only");manifest.put("tab",tab);manifest.put("fontScale",font[0]);manifest.put("windowWidth",size[0]);manifest.put("windowHeight",size[1]);manifest.put("confirmed",confirmed);manifest.put("attemptCount",attempt);manifest.put("elapsedMs",SystemClock.uptimeMillis()-started);manifest.put("probeOrder",new JSONArray(new String[]{"tab line","photo center","cursor border +13dp","cursor inside +9dp","cursor outside +17dp"}));manifest.put("attempts",attempts);
      try(java.io.Writer output=new java.io.OutputStreamWriter(new java.io.FileOutputStream(new java.io.File(folder,tab+".json")),java.nio.charset.StandardCharsets.UTF_8)){output.write(manifest.toString(2));}
    }
    require(confirmed,"actual window must contain current tab, visible synthetic photo and cursor border within 5 seconds");
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
        confirmedCapture(t,a,tab);
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
