package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.widget.LinearLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Isolated synthetic control: no selection/count/layout updates can mask a stale display list. */
final class BestSwitchChecks {
  static void run(Instrumentation t,MainActivity a)throws Exception{
    SelectionUi.BestSwitch[] control={null};int[] changes={0};
    TagDetailChecks.ui(t,()->{
      LinearLayout panel=new LinearLayout(a);panel.setBackgroundColor(GalleryUi.BG);
      panel.setPadding(GalleryUi.dp(a,16),GalleryUi.dp(a,80),GalleryUi.dp(a,16),0);
      SelectionUi.BestSwitch toggle=new SelectionUi.BestSwitch(a);toggle.setChecked(true);
      toggle.setOnCheckedChangeListener((button,checked)->changes[0]++);
      toggle.setOnClickListener(GalleryUi::feedback);
      panel.addView(toggle,new LinearLayout.LayoutParams(-1,-2));a.setContentView(panel);control[0]=toggle;
    });
    SelectionUi.BestSwitch toggle=control[0];
    ConsistencyLayoutChecks.frame(t,toggle); // Initial layout only. Never force a frame after a toggle.
    pixels(t,a,toggle,true,"initial");
    tap(t,toggle,true);pixels(t,a,toggle,false,"track-off");
    tap(t,toggle,false);pixels(t,a,toggle,true,"label-on");
    TagDetailChecks.ui(t,()->{for(int i=0;i<9;i++)toggle.performClick();});
    pixels(t,a,toggle,false,"rapid-off");
    TagDetailChecks.ui(t,()->toggle.setChecked(true));pixels(t,a,toggle,true,"programmatic-on");
    TagDetailChecks.ui(t,()->{
      TagDetailChecks.require(changes[0]==12,"one state change per action");
      toggle.setChecked(true);TagDetailChecks.require(changes[0]==12,"same value has no callback");
    });
  }
  private static void tap(Instrumentation t,View toggle,boolean track)throws Exception{
    int[] point=new int[2];TagDetailChecks.ui(t,()->{
      toggle.getLocationOnScreen(point);point[0]+=track?toggle.getWidth()-GalleryUi.dp(toggle.getContext(),32):GalleryUi.dp(toggle.getContext(),24);point[1]+=toggle.getHeight()/2;
    });
    long down=SystemClock.uptimeMillis();
    MotionEvent press=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,point[0],point[1],0);
    MotionEvent release=MotionEvent.obtain(down,down+50,MotionEvent.ACTION_UP,point[0],point[1],0);
    try{t.sendPointerSync(press);t.sendPointerSync(release);}finally{press.recycle();release.recycle();}
  }
  private static void pixels(Instrumentation t,MainActivity a,SelectionUi.BestSwitch toggle,boolean checked,String name)throws Exception{
    t.waitForIdleSync();Thread.sleep(400);
    Bitmap[] image={null};CountDownLatch copied=new CountDownLatch(1);int[] result={-1};
    TagDetailChecks.ui(t,()->{
      android.view.accessibility.AccessibilityNodeInfo node=toggle.createAccessibilityNodeInfo();
      TagDetailChecks.require(toggle.isChecked()==checked&&node.isCheckable()&&node.isChecked()==checked,"checked and AX state "+name);
      node.recycle();
      int[] origin=new int[2];toggle.getLocationInWindow(origin);
      int[] screen=new int[2];toggle.getLocationOnScreen(screen);
      android.graphics.Point display=new android.graphics.Point();a.getWindowManager().getDefaultDisplay().getRealSize(display);
      if(screen[0]<0||screen[1]<0||screen[0]+toggle.getWidth()>display.x||screen[1]+toggle.getHeight()>display.y)
        throw new AssertionError("switch capture must fit physical display; use a smaller fixture width");
      image[0]=Bitmap.createBitmap(toggle.getWidth(),toggle.getHeight(),Bitmap.Config.ARGB_8888);
      PixelCopy.request(a.getWindow(),new Rect(origin[0],origin[1],origin[0]+toggle.getWidth(),origin[1]+toggle.getHeight()),image[0],value->{result[0]=value;copied.countDown();},new Handler(Looper.getMainLooper()));
    });
    try{
      TagDetailChecks.require(copied.await(5,TimeUnit.SECONDS)&&result[0]==PixelCopy.SUCCESS,"PixelCopy "+name);
      File dir=new File(a.getCacheDir(),"consistency-checks");dir.mkdirs();
      try(FileOutputStream out=new FileOutputStream(new File(dir,"best-switch-"+a.getResources().getConfiguration().screenWidthDp+"-"+a.getResources().getConfiguration().fontScale+"-"+name+".png"))){image[0].compress(Bitmap.CompressFormat.PNG,100,out);}
      TagDetailChecks.ui(t,()->{
        Bitmap expected=Bitmap.createBitmap(toggle.getWidth(),toggle.getHeight(),Bitmap.Config.ARGB_8888);
        try {
          toggle.draw(new android.graphics.Canvas(expected));
          Rect bounds=toggle.getThumbDrawable().getBounds();
          int x=Math.max(0,Math.min(expected.getWidth()-1,bounds.centerX()));
          int y=Math.max(0,Math.min(expected.getHeight()-1,bounds.centerY()));
          int actual=image[0].getPixel(x,y), wanted=expected.getPixel(x,y);
          if(Math.abs(android.graphics.Color.red(actual)-android.graphics.Color.red(wanted))>8
              ||Math.abs(android.graphics.Color.green(actual)-android.graphics.Color.green(wanted))>8
              ||Math.abs(android.graphics.Color.blue(actual)-android.graphics.Color.blue(wanted))>8)
            throw new AssertionError("native thumb did not update on screen: "+name);
        } finally { expected.recycle(); }
      });
    }finally{if(image[0]!=null)image[0].recycle();}
  }
}
