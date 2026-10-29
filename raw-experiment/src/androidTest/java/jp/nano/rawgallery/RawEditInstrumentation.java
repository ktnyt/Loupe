package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.*;
import java.util.concurrent.atomic.AtomicReference;

/** Core GPU/native and real UI gesture contracts; no user photo contents are emitted. */
public final class RawEditInstrumentation extends Instrumentation {
  private Bundle arguments;
  public void onCreate(Bundle args){arguments=args;start();}
  public void callActivityOnCreate(Activity activity,Bundle saved){
    if(arguments!=null&&arguments.containsKey("font")&&activity instanceof RawEditActivity){
      android.content.res.Configuration config=new android.content.res.Configuration(activity.getResources().getConfiguration());
      config.fontScale=Float.parseFloat(arguments.getString("font"));activity.getResources().updateConfiguration(config,activity.getResources().getDisplayMetrics());
    }
    super.callActivityOnCreate(activity,saved);
    if(arguments!=null&&arguments.containsKey("height")&&activity instanceof RawEditActivity){
      int h=Integer.parseInt(arguments.getString("height"));activity.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,Math.round(h*activity.getResources().getDisplayMetrics().density));
    }
  }

  private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  public void onStart(){
    Bundle result=new Bundle();
    try{
      core();pad();
      if(arguments!=null&&Boolean.parseBoolean(arguments.getString("ui","false")))RawEditUiChecks.run(this);
      if(arguments!=null&&Boolean.parseBoolean(arguments.getString("real","false")))real();
      result.putString("stream","PASS raw-editor GPU neutral/orientation/brightness/saturation/comparison; XY/edge independent; native\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("stream","FAIL "+error.getClass().getSimpleName()+": "+(error instanceof AssertionError?error.getMessage():"core execution")+"\n");finish(Activity.RESULT_CANCELED,result);}
  }
  private void core()throws Exception{
    ByteBuffer buffer=ByteBuffer.allocateDirect(4*4*4).order(ByteOrder.nativeOrder());
    buffer.asFloatBuffer().put(new float[]{1,0,0,1,0,1,0,1,0,0,1,1,.2f,.2f,.2f,1});
    try(RawEditRenderer render=new RawEditRenderer(2,2,buffer)){
      Bitmap neutral=render.render(new RawEditState(),false,false,256);
      require((neutral.getPixel(0,0)&0xffffff)==0xff0000,"top-left red / orientation");
      require((neutral.getPixel(1,0)&0xffffff)==0x00ff00,"top-right green");
      require((neutral.getPixel(0,1)&0xffffff)==0x0000ff,"bottom-left blue");
      Bitmap grey=render.render(new RawEditState().with(RawEditState.SATURATION,-1),false,false,256);
      int pixel=grey.getPixel(0,0);require(Math.abs(((pixel>>16)&255)-((pixel>>8)&255))<=1&&Math.abs((pixel&255)-((pixel>>8)&255))<=1,"desaturation");
      Bitmap brighter=render.render(new RawEditState().with(RawEditState.EXPOSURE,1),false,false,256);
      require((brighter.getPixel(1,1)&255)>(neutral.getPixel(1,1)&255),"linear exposure");
      RawEditState edit=new RawEditState().with(0,.5f).with(3,.4f).with(15,.6f);
      Bitmap original=render.render(edit,true,true,256);require(original.sameAs(neutral),"before bypasses edits and MAX");
      Bitmap saved=render.render(edit,false,false,256),shown=render.render(edit,false,true,256);
      require(!saved.sameAs(shown),"MAX is a distinct transient display");
      require(saved.sameAs(render.render(new RawEditState(edit.snapshot()),false,false,256)),"export depends only on edit values");
      Bitmap mono=render.render(new RawEditState().with(RawEditState.MONO,1),false,false,256);
      int tint=mono.getPixel(1,1),red=(tint>>16)&255,green=(tint>>8)&255,blue=tint&255;
      require(blue>red&&red>green,"Mono hue 260 tint order");
      Bitmap monoBefore=render.render(new RawEditState().with(RawEditState.MONO,1),true,false,256);
      require(monoBefore.sameAs(neutral),"before bypasses Mono");mono.recycle();monoBefore.recycle();
      neutral.recycle();grey.recycle();brighter.recycle();original.recycle();saved.recycle();shown.recycle();
    }
    try{NativeRaw.decode("/unreadable-test-input",2048);throw new AssertionError("native missing file accepted");}catch(IOException expected){require(!expected.getMessage().contains("unreadable"),"private native error");}
  }
  private void pad(){
    runOnMainSync(()->{
      AtomicReference<RawEditState> current=new AtomicReference<>(new RawEditState());
      RawEditPad pad=new RawEditPad(getTargetContext(),180,current.get(),RawEditState.TEXTURE,RawEditState.CLARITY,"テクスチャ","明瞭度",current::set);
      float density=getTargetContext().getResources().getDisplayMetrics().density;int size=Math.round(236*density);
      pad.measure(View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));pad.layout(0,0,size,pad.getMeasuredHeight());
      FrameLayout frame=pad;View plane=frame.getChildAt(0),vertical=frame.getChildAt(1),horizontal=frame.getChildAt(2);
      require(plane.getRight()<vertical.getLeft()&&plane.getBottom()<horizontal.getTop(),"non-overlapping input bounds");
      touch(plane,.8f,.2f);require(current.get().get(2)>0&&current.get().get(3)>0,"plane changes both axes");
      float y=current.get().get(3);touch(horizontal,.2f,.5f);require(current.get().get(2)<0&&current.get().get(3)==y,"horizontal leaves vertical unchanged");
      float x=current.get().get(2);touch(vertical,.5f,.8f);require(current.get().get(2)==x&&current.get().get(3)<0,"vertical leaves horizontal unchanged");
    });
  }
  private static void touch(View view,float x,float y){
    long time=android.os.SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,view.getWidth()*x,view.getHeight()*y,0);view.dispatchTouchEvent(down);down.recycle();
    MotionEvent up=MotionEvent.obtain(time,time+10,MotionEvent.ACTION_UP,view.getWidth()*x,view.getHeight()*y,0);view.dispatchTouchEvent(up);up.recycle();
  }
  private void real()throws Exception{
    for(String name:new String[]{"raw-fixture.dng","raw-fixture.orf"}){
      File f=new File(getTargetContext().getFilesDir(),name);long start=android.os.SystemClock.elapsedRealtime();
      NativeRaw.Image source=NativeRaw.decode(f.getAbsolutePath(),2048);
      require(Math.max(source.width,source.height)<=2048,"native dimension limit");
      try(RawEditRenderer r=new RawEditRenderer(source.width,source.height,source.pixels)){
        Bitmap image=r.render(new RawEditState(),false,false,2048);require(image.getWidth()==source.width&&image.getHeight()==source.height,"real export dimensions");
        File result=new File(getTargetContext().getCacheDir(),name+".jpg");try(OutputStream out=new FileOutputStream(result)){require(image.compress(Bitmap.CompressFormat.JPEG,95,out),"real JPEG encoding");}require(result.length()>1024,"encoded image");image.recycle();result.delete();
      }
      android.util.Log.i("LoupeRawEdit","real.check ms="+(android.os.SystemClock.elapsedRealtime()-start)+" width="+source.width+" height="+source.height);
    }
  }
}
