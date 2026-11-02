package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.graphics.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Synthetic-only GPU look/precision/tile contracts. No original photographs are read or emitted. */
final class RawEditLookChecks {
  private static void require(boolean value,String text){if(!value)throw new AssertionError(text);}
  private static int code(int pixel,int channel){return pixel>>(16-channel*8)&255;}
  private static int maxError(Bitmap a,Bitmap b){require(a.getWidth()==b.getWidth()&&a.getHeight()==b.getHeight(),"equal comparison dimensions");int max=0;for(int y=0;y<a.getHeight();y++)for(int x=0;x<a.getWidth();x++)for(int c=0;c<3;c++)max=Math.max(max,Math.abs(code(a.getPixel(x,y),c)-code(b.getPixel(x,y),c)));return max;}
  private static RawEditCube table(int size,boolean domain)throws IOException {
    StringBuilder text=new StringBuilder("TITLE \"Synthetic GPU fixture\"\nLUT_3D_SIZE "+size+"\n");
    if(domain)text.append("DOMAIN_MIN -0.1 0.0 0.1\nDOMAIN_MAX 0.9 1.0 1.1\n");
    for(int b=0;b<size;b++)for(int g=0;g<size;g++)for(int r=0;r<size;r++){
      double x=r/(double)(size-1),y=g/(double)(size-1),z=b/(double)(size-1);
      text.append(domain?.15+.7*x*y:x).append(' ').append(domain?.1+.8*y*z:y).append(' ').append(domain?.05+.9*z*x:z).append('\n');
    }
    return RawEditCube.read(new ByteArrayInputStream(text.toString().getBytes(StandardCharsets.UTF_8)));
  }
  static void run(Instrumentation instrumentation)throws Exception {
    long started=android.os.SystemClock.elapsedRealtime();int width=1024,height=5;
    ByteBuffer pixels=ByteBuffer.allocateDirect(width*height*16).order(ByteOrder.nativeOrder());double[][][] input=new double[height][width][];
    for(int y=0;y<height;y++)for(int x=0;x<width;x++){
      double t=x/(double)(width-1);double[] encoded=y==0?new double[]{t,t,t}:y==1?new double[]{t,.32,.1}:y==2?new double[]{.1,t,.7}:y==3?new double[]{.7,.3,t}:new double[]{.42,.42,.42};
      double[] linear=new double[3];for(int c=0;c<3;c++){float v=(float)RawEditTone.decode(encoded[c]);pixels.putFloat(v);linear[c]=v;}pixels.putFloat(1);input[y][x]=linear;
    }pixels.flip();RawEditState neutral=RawEditState.neutral();int maxCpuGpu=0;
    try(RawEditRenderer renderer=new RawEditRenderer(width,height,pixels)){
      Bitmap before=renderer.render(neutral,true,false,2048),unprocessed=renderer.render(neutral,false,false,2048);
      require(maxError(before,unprocessed)<=1,"Neutral differs from Before by more than one code");before.recycle();
      RawEditLook identity=RawEditLook.neutral();
      for(int size:new int[]{17,33,65}){
        RawEditLook lut=identity.withCube(table(size,false)).withLutEnabled(true);
        Bitmap actual=renderer.render(neutral.withLook(lut),false,false,2048);require(maxError(actual,unprocessed)<=1,"GPU Identity LUT size "+size);actual.recycle();
        maxCpuGpu=Math.max(maxCpuGpu,reference(renderer,neutral.withLook(identity.withCube(table(size,true)).withLutEnabled(true).withLutStrength(.63f)),input));
      }
      RawEditLook cnt=RawEditLook.cntLike();maxCpuGpu=Math.max(maxCpuGpu,reference(renderer,neutral.withLook(cnt),input));
      maxCpuGpu=Math.max(maxCpuGpu,reference(renderer,neutral.withLook(cnt.withCurve(1,new RawEditCurve(new float[]{0,0,.4f,.45f,1,1})).withCurve(2,new RawEditCurve(new float[]{0,0,.5f,.47f,1,1})).withCurve(3,new RawEditCurve(new float[]{0,0,.5f,.54f,1,1})).withCurve(4,new RawEditCurve(new float[]{0,0,.5f,.43f,1,1}))),input));
      RawEditLook grain=identity.withGrainEnabled(true).withGrainStrength(.75f);
      Bitmap grainA=renderer.render(neutral.withLook(grain),false,false,2048),grainB=renderer.render(neutral.withLook(grain),false,false,2048),grainOther=renderer.render(neutral.withLook(grain.withSeed(66)),false,false,2048);
      require(grainA.sameAs(grainB),"same seed GPU output must be exact");require(!grainA.sameAs(grainOther),"different seed must change GPU grain");
      for(int x=0;x<width;x++){int p=grainA.getPixel(x,4);require(Math.abs(code(p,0)-code(p,1))<=1&&Math.abs(code(p,0)-code(p,2))<=1,"grain must remain achromatic on neutral gray");}
      grainA.recycle();grainB.recycle();grainOther.recycle();
      RawEditLook disabled=cnt.withToneEnabled(false).withLutEnabled(false).withGrainEnabled(false);Bitmap off=renderer.render(neutral.withLook(disabled),false,false,2048);require(off.sameAs(unprocessed),"disabled look must preserve old path exactly");off.recycle();
      Bitmap graded=renderer.render(neutral.withLook(cnt.withGrainEnabled(false)),false,false,2048);boolean[] levels=new boolean[256];int previous=-1,unique=0;
      for(int x=0;x<width;x++){int v=code(graded.getPixel(x,0),0);require(v>=previous-1,"CNT gray ramp must not reverse beyond final dither");levels[v]=true;previous=v;}for(boolean present:levels)if(present)unique++;require(unique>225,"gradient has too few final 8-bit levels");require(code(graded.getPixel(0,0),0)<=1&&code(graded.getPixel(width-1,0),0)<255,"black endpoint / highlight compression");graded.recycle();unprocessed.recycle();
    }
    File recipe=File.createTempFile("synthetic-look-",".recipe",instrumentation.getTargetContext().getCacheDir());
    try{RawEditLook expected=RawEditLook.cntLike().withCube(table(65,true)).withSeed(-319);try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(recipe)))){expected.write(out);}try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(recipe)))){require(expected.equals(RawEditLook.read(in))&&in.read()==-1,"private look recipe round trip");}}finally{recipe.delete();}
    tiles(instrumentation);gridStability(instrumentation);resize();
    android.util.Log.i("LoupeRawEdit","look.check max_code_error="+maxCpuGpu+" ms="+(android.os.SystemClock.elapsedRealtime()-started));
  }
  private static int reference(RawEditRenderer renderer,RawEditState state,double[][][] input)throws Exception {
    Bitmap actual=renderer.render(state,false,false,2048);int max=0;
    try{for(int y=0;y<input.length;y++)for(int x=0;x<input[0].length;x++){
      double[] expected=state.look().apply(RawEditTone.apply(state,input[y][x]),(x+.5)*2,(y+.5)*2,2,2);int pixel=actual.getPixel(x,y);
      for(int c=0;c<3;c++){int difference=Math.abs(code(pixel,c)-(int)Math.round(expected[c]*255));max=Math.max(max,difference);require(difference<=2,"look CPU/GPU row="+y+" x="+x+" channel="+c+" error="+difference);}
    }}finally{actual.recycle();}return max;
  }
  private static void tiles(Instrumentation instrumentation)throws Exception {
    File cache=File.createTempFile("synthetic-look-tiles-",".ppm",instrumentation.getTargetContext().getCacheDir());
    try{
      // Uses the existing validated RGB16 fixture path, including genuine 16-bit source quantization.
      RawEditTileChecks.fixture(cache,193,127,3);
      try(RawEditFullSource full=RawEditFullSource.open(cache);RawEditRenderer reference=new RawEditRenderer(full.width,full.height,full.region(new Rect(0,0,full.width,full.height)));RawEditRenderer tiled=new RawEditRenderer()){
        RawEditState state=RawEditState.neutral().withLook(RawEditLook.cntLike().withGrainStrength(.8f));Bitmap expected=reference.render(state,false,false,2048);int max=0;
        try{for(int y=0;y<127;y+=31)for(int x=0;x<193;x+=47){int w=Math.min(47,193-x),h=Math.min(31,127-y);Bitmap tile=tiled.renderRegionBitmap(full,state,false,false,new RectF(x,y,x+w,y+h),w,h);try{for(int j=0;j<h;j++)for(int i=0;i<w;i++)for(int c=0;c<3;c++){int difference=Math.abs(code(tile.getPixel(i,j),c)-code(expected.getPixel(x+i,y+j),c));max=Math.max(max,difference);require(difference<=2,"look tile seam mismatch x="+(x+i)+" y="+(y+j)+" error="+difference);}}finally{tile.recycle();}}}finally{expected.recycle();}
        android.util.Log.i("LoupeRawEdit","look.tiles max_code_error="+max+" tiles=25");
      }
    }finally{cache.delete();}
  }
  private static void gridStability(Instrumentation instrumentation)throws Exception {
    File cache=File.createTempFile("synthetic-look-grid-",".ppm",instrumentation.getTargetContext().getCacheDir());
    try{
      try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(cache)))){out.writeBytes("P6\n1544 1016\n65535\n");for(int i=0;i<1544*1016*3;i++)out.writeShort(16384);}
      try(RawEditFullSource full=RawEditFullSource.open(cache);RawEditRenderer prepared=new RawEditRenderer()){
        RawEditFullSource.Preview source=full.preview(193);
        try(RawEditRenderer initial=new RawEditRenderer(source.width,source.height,source.pixels)){
          initial.grainDimensions(full.width,full.height);
          RawEditState state=RawEditState.neutral().withLook(RawEditLook.neutral().withGrainEnabled(true).withGrainStrength(1));
          Bitmap before=initial.render(state,false,false,193),after=prepared.renderRegionBitmap(full,state,false,false,new RectF(0,0,full.width,full.height),source.width,source.height);
          try{int error=maxError(before,after);require(error<=1,"initial/prepared grain grid differs error="+error);android.util.Log.i("LoupeRawEdit","look.grid initial=193 original=1544 max_code_error="+error);}finally{before.recycle();after.recycle();}
        }
      }
    }finally{cache.delete();}
  }
  private static void resize()throws Exception {
    int width=1024,height=256;ByteBuffer pixels=ByteBuffer.allocateDirect(width*height*16).order(ByteOrder.nativeOrder());float v=(float)RawEditTone.decode(.42);for(int i=0;i<width*height;i++)pixels.putFloat(v).putFloat(v).putFloat(v).putFloat(1);pixels.flip();
    RawEditState state=RawEditState.neutral().withLook(RawEditLook.neutral().withGrainEnabled(true).withGrainStrength(.8f));
    try(RawEditRenderer renderer=new RawEditRenderer(width,height,pixels)){
      Bitmap full=renderer.render(state,false,false,2048),preview=renderer.render(state,false,false,512),reduced=Bitmap.createScaledBitmap(full,512,128,true);double sum=0,sumSquared=0,meanA=0,meanB=0;
      try{for(int y=0;y<128;y++)for(int x=0;x<512;x++){double a=code(preview.getPixel(x,y),0),b=code(reduced.getPixel(x,y),0),d=a-b;sum+=Math.abs(d);sumSquared+=d*d;meanA+=a;meanB+=b;}double count=512.*128,mae=sum/count,rms=Math.sqrt(sumSquared/count);require(mae<=1.5&&Math.abs(meanA-meanB)/count<.5,"preview/export grain mean or footprint differs: MAE="+mae+" RMS="+rms);android.util.Log.i("LoupeRawEdit","look.resize width=1024 preview=512 mae_code="+mae+" rms_code="+rms);}finally{full.recycle();preview.recycle();reduced.recycle();}
    }
  }
}
