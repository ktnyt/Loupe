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
      RawEditLook disabled=cnt.withToneEnabled(false).withLutEnabled(false).withGrainEnabled(false).withVignetteEnabled(false);Bitmap off=renderer.render(neutral.withLook(disabled),false,false,2048);require(off.sameAs(unprocessed),"disabled look must preserve old path exactly");off.recycle();
      // This explicit luminance fixture checks precision and endpoint behavior independently of measured CNT colors.
      RawEditLook precision=RawEditLook.neutral().withToneEnabled(true).withCurve(0,new RawEditCurve(new float[]{0,0,.025f,.014f,.13f,.14f,.45f,.46f,.75f,.74f,1,.96f}));
      Bitmap graded=renderer.render(neutral.withLook(precision),false,false,2048);boolean[] levels=new boolean[256];int previous=-1,unique=0;
      for(int x=0;x<width;x++){int v=code(graded.getPixel(x,0),0);require(v>=previous-1,"luminance fixture gray ramp must not reverse beyond final dither");levels[v]=true;previous=v;}for(boolean present:levels)if(present)unique++;require(unique>225,"gradient has too few final 8-bit levels");require(code(graded.getPixel(0,0),0)<=1&&code(graded.getPixel(width-1,0),0)<255,"black endpoint / highlight compression");graded.recycle();unprocessed.recycle();
    }
    File recipe=File.createTempFile("synthetic-look-",".recipe",instrumentation.getTargetContext().getCacheDir());
    try{RawEditLook expected=RawEditLook.cntLike().withCube(table(65,true)).withSeed(-319).withVignetteEnabled(true).withVignetteStrength(.63f);try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(recipe)))){expected.write(out);}try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(recipe)))){require(expected.equals(RawEditLook.read(in))&&in.read()==-1,"private look recipe round trip");}}finally{recipe.delete();}
    calibratedAnchors();spatial();tiles(instrumentation);gridStability(instrumentation);resize();store(instrumentation);RawEditPublishChecks.runLook(instrumentation);
    android.util.Log.i("LoupeRawEdit","look.check max_code_error="+maxCpuGpu+" ms="+(android.os.SystemClock.elapsedRealtime()-started));
  }
  private static void store(Instrumentation instrumentation)throws Exception {
    File directory=new File(instrumentation.getTargetContext().getCacheDir(),"synthetic-look-store-"+java.util.UUID.randomUUID());
    require(directory.mkdir(),"create isolated look store");String token=null;
    try {
      RawEditLook current=RawEditLook.cntLike().withCube(table(65,true)).withVignetteEnabled(true).withVignetteStrength(.37f),saved=current.withSeed(71),custom=current.withGrainStrength(.3f);
      long start=android.os.SystemClock.elapsedRealtime();token=RawEditLookStore.save(directory,null,current,saved,custom);
      long saveMs=android.os.SystemClock.elapsedRealtime()-start;start=android.os.SystemClock.elapsedRealtime();
      RawEditLook[] loaded=RawEditLookStore.load(directory,token);long loadMs=android.os.SystemClock.elapsedRealtime()-start;
      require(current.equals(loaded[0])&&saved.equals(loaded[1])&&custom.equals(loaded[2]),"Android private store round trip all slots");
      String replaced=RawEditLookStore.save(directory,token,custom,saved,current);require(token.equals(replaced)&&RawEditLookStore.load(directory,token)[0].equals(custom),"Android atomic store replacement");
      android.util.Log.i("LoupeRawEdit","look.store save_ms="+saveMs+" load_ms="+loadMs+" bytes="+new File(directory,token+".look").length()+" slots=3 cube_size=65");
    } finally {if(token!=null)RawEditLookStore.delete(directory,token);require(directory.delete(),"isolated look store cleanup");}
  }
  private static int reference(RawEditRenderer renderer,RawEditState state,double[][][] input)throws Exception {
    Bitmap actual=renderer.render(state,false,false,2048);int max=0;
    try{for(int y=0;y<input.length;y++)for(int x=0;x<input[0].length;x++){
      double grid=2048./Math.max(input[0].length,input.length);double[] expected=state.look().applyAt(RawEditTone.apply(state,input[y][x]),(x+.5)*grid,(y+.5)*grid,grid,grid,(x+.5)/input[0].length,(y+.5)/input.length);int pixel=actual.getPixel(x,y);
      for(int c=0;c<3;c++){int difference=Math.abs(code(pixel,c)-(int)Math.round(expected[c]*255));max=Math.max(max,difference);require(difference<=2,"look CPU/GPU row="+y+" x="+x+" channel="+c+" error="+difference);}
    }}finally{actual.recycle();}return max;
  }
  private static void calibratedAnchors()throws Exception {
    // These constants freeze model RGB, not just agreement between implementations.
    // Corner is the center of pixel (0,0) on the full 65^2 upright image: q=(.5/65,.5/65).
    double[][] input={{0,0,0},{.18,.18,.18},{.5,.5,.5},{.9,.9,.9},{1,1,1},{.8,.2,.1},{.1,.7,.3}},center={{0,0,0},{.17347894085216523,.18607647852230072,.1818952338256836},{.5936646461486816,.6071571707725525,.5989733934402466},{.9200371122360229,.9301710448265075,.9260049095153808},{.9882687330245972,.9974678754806519,.9932222962379456},{.854177093029022,.2561441124677658,.22324134373664858},{.11555686318874357,.787363278388977,.41770423674583423}},corner={{0,0,0},{.12439399439578258,.13342712557955494,.1304289418991414},{.4343675950038363,.44423969284463144,.43825185492815505},{.8323814216409241,.8415498531142807,.8377806425173342},{.9310723750951195,.9397391143424331,.9357392493086957},{.6124922137598573,.18366949402798521,.1600763892451274},{.08506053555029708,.5795721715205571,.30746893865746383}};
    for(int i=0;i<input.length;i++){
      ByteBuffer pixels=ByteBuffer.allocateDirect(65*65*16).order(ByteOrder.nativeOrder());for(int n=0;n<65*65;n++){for(int k=0;k<3;k++)pixels.putFloat((float)RawEditTone.decode(input[i][k]));pixels.putFloat(1);}pixels.flip();
      try(RawEditRenderer renderer=new RawEditRenderer(65,65,pixels)){
        Bitmap actual=renderer.render(RawEditState.neutral().withLook(RawEditLook.cntLike()),false,false,2048);
        try{for(int k=0;k<3;k++){require(Math.abs(code(actual.getPixel(32,32),k)-(int)Math.round(center[i][k]*255))<=2,"frozen measured GPU center RGB anchor="+i+" channel="+k);require(Math.abs(code(actual.getPixel(0,0),k)-(int)Math.round(corner[i][k]*255))<=2,"frozen measured GPU corner RGB anchor="+i+" channel="+k);}}finally{actual.recycle();}
      }
    }
  }
  private static void spatial()throws Exception {
    for(int[] size:new int[][]{{193,127},{127,193}}){
      int width=size[0],height=size[1];double[][][] input=new double[height][width][];
      ByteBuffer pixels=ByteBuffer.allocateDirect(width*height*16).order(ByteOrder.nativeOrder());
      for(int y=0;y<height;y++)for(int x=0;x<width;x++){double[] c={.72,.42,.19};input[y][x]=new double[3];for(int k=0;k<3;k++){float value=(float)RawEditTone.decode(c[k]);pixels.putFloat(value);input[y][x][k]=value;}pixels.putFloat(1);}pixels.flip();
      try(RawEditRenderer renderer=new RawEditRenderer(width,height,pixels)){
        RawEditLook spatial=RawEditLook.neutral().withVignetteEnabled(true);RawEditState state=RawEditState.neutral();
        reference(renderer,state.withLook(spatial),input);
        StringBuilder cubeText=new StringBuilder("LUT_3D_SIZE 2\n");for(int i=0;i<8;i++)cubeText.append("1.1 1.1 1.1\n");RawEditCube unbounded=RawEditCube.read(new ByteArrayInputStream(cubeText.toString().getBytes(StandardCharsets.UTF_8)));reference(renderer,state.withLook(spatial.withCube(unbounded).withLutEnabled(true)),input);
        reference(renderer,state.withLook(spatial.withVignetteStrength(.41f).withGrainEnabled(true).withGrainStrength(.7f).withSeed(-319)),input);
        Bitmap baseline=renderer.render(state,false,false,2048),zero=renderer.render(state.withLook(spatial.withVignetteStrength(0)),false,false,2048),off=renderer.render(state.withLook(spatial.withVignetteEnabled(false)),false,false,2048),on=renderer.render(state.withLook(spatial),false,false,2048);
        try{require(baseline.sameAs(zero)&&baseline.sameAs(off),"vignette zero/disabled exact GPU identity");require(code(on.getPixel(0,0),0)<code(on.getPixel(width/2,height/2),0)-20,"vignette corner visibly below center");require(Math.abs(code(on.getPixel(0,0),0)-code(on.getPixel(width-1,height-1),0))<=1,"vignette opposite corners symmetric");}finally{baseline.recycle();zero.recycle();off.recycle();on.recycle();}
      }
    }
  }
  private static void tiles(Instrumentation instrumentation)throws Exception {
    File cache=File.createTempFile("synthetic-look-tiles-",".ppm",instrumentation.getTargetContext().getCacheDir());
    try{
      // Uses the existing validated RGB16 fixture path, including genuine 16-bit source quantization.
      for(int[] size:new int[][]{{193,127},{127,193}}){
      int width=size[0],height=size[1];RawEditTileChecks.fixture(cache,width,height,3);
      try(RawEditFullSource full=RawEditFullSource.open(cache);RawEditRenderer reference=new RawEditRenderer(full.width,full.height,full.region(new Rect(0,0,full.width,full.height)));RawEditRenderer tiled=new RawEditRenderer()){
        RawEditState state=RawEditState.neutral().withLook(RawEditLook.cntLike().withGrainEnabled(true).withGrainStrength(.8f).withVignetteEnabled(true));Bitmap expected=reference.render(state,false,false,2048);int max=0;
        try{for(int y=0;y<height;y+=31)for(int x=0;x<width;x+=47){int w=Math.min(47,width-x),h=Math.min(31,height-y);Bitmap tile=tiled.renderRegionBitmap(full,state,false,false,new RectF(x,y,x+w,y+h),w,h);try{for(int j=0;j<h;j++)for(int i=0;i<w;i++)for(int c=0;c<3;c++){int difference=Math.abs(code(tile.getPixel(i,j),c)-code(expected.getPixel(x+i,y+j),c));max=Math.max(max,difference);require(difference<=2,"look tile seam mismatch x="+(x+i)+" y="+(y+j)+" error="+difference);}}finally{tile.recycle();}}}finally{expected.recycle();}
        android.util.Log.i("LoupeRawEdit","look.tiles max_code_error="+max+" width="+width+" height="+height);
      }
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
