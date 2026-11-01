package jp.nano.rawgallery;
import android.graphics.*;
import java.nio.*;
import java.io.*;
/** Six synthetic photographic conditions; compares the production shader against the CPU model. */
final class RawEditToneChecks {
  static void require(boolean ok,String text){if(!ok)throw new AssertionError(text);}
  static void run()throws Exception{
    int w=1024,h=6;ByteBuffer pixels=ByteBuffer.allocateDirect(w*h*16).order(ByteOrder.nativeOrder());
    double[][][] samples=new double[h][w][];
    for(int y=0;y<h;y++)for(int x=0;x<w;x++){
      double t=x/(double)(w-1),v=y==0?t*.015:y==1?.7+t*.4:y==4?t*32:t;
      double[] c=y==2?new double[]{v,v*.04,v*.6}:new double[]{v,v,v};samples[y][x]=c;
      for(double a:c)pixels.putFloat((float)a);pixels.putFloat(1);
    }pixels.position(0);
    RawEditCurve shaped=new RawEditCurve(new float[]{0,.02f,.2f,.12f,.5f,.5f,.8f,.9f,1,.98f});
    RawEditState normal=new RawEditState(),edit=normal.with(RawEditState.SHADOWS,.5f).with(RawEditState.HIGHLIGHTS,-.7f).with(RawEditState.BLACKS,-.4f).withCurve(0,shaped);
    RawEditState color=edit.withCurve(1,new RawEditCurve(new float[]{0,0,.4f,.5f,1,1})).withCurve(2,new RawEditCurve(new float[]{0,0,.5f,.45f,1,1})).withCurve(3,new RawEditCurve(new float[]{0,0,.5f,.55f,1,1})).withCurve(4,new RawEditCurve(new float[]{0,0,.5f,.4f,1,1}));
    int maxError=0;long start=android.os.SystemClock.elapsedRealtime();
    try(RawEditRenderer renderer=new RawEditRenderer(w,h,pixels)){
      for(RawEditState s:new RawEditState[]{normal,edit,color,new RawEditState(color.snapshot()),normal.with(RawEditState.SATURATION,1),normal.with(RawEditState.MONO,1)}){
        Bitmap b=renderer.render(s,false,false,2048);
        try{for(int y=0;y<h;y++)for(int x=0;x<w;x++){
          double[] expected=RawEditTone.apply(s,samples[y][x]);int p=b.getPixel(x,y);
          for(int channel=0;channel<3;channel++){int difference=Math.abs(((p>>(16-channel*8))&255)-(int)Math.round(expected[channel]*255));maxError=Math.max(maxError,difference);require(difference<=2,"CPU/GPU mismatch row="+y+" x="+x+" channel="+channel+" difference="+difference);}
          if(y!=2&&x>0){int prev=b.getPixel(x-1,y);require((p&255)>=(prev&255)-1,"ramp reversal beyond output dither");}
        }}finally{b.recycle();}
      }
      Bitmap before=renderer.render(color,true,false,2048),original=renderer.render(normal,true,false,2048);require(before.sameAs(original),"compare bypasses all curves");before.recycle();original.recycle();
    }
    android.util.Log.i("LoupeRawEdit","tone.check max_code_error="+maxError+" ms="+(android.os.SystemClock.elapsedRealtime()-start));
  }
}
