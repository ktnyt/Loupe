package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import java.io.*;
import java.nio.*;

/** Bounded private copy of a selected document; no path or photo metadata leaves this class. */
final class RawEditSource implements AutoCloseable {
  final int width,height;
  volatile ByteBuffer pixels;
  final boolean raw;
  private final File input;
  private final int originalWidth,originalHeight,orientation;
  private final SharedInput shared;
  private final boolean analytic;
  private boolean closed;
  private static final class SharedInput {
    final File file; int owners=1;
    SharedInput(File file){this.file=file;}
  }
  RawEditSource(int w,int h,ByteBuffer p,boolean r){this(w,h,p,r,null,w,h,1,false,null);}
  private RawEditSource(int w,int h,ByteBuffer p,boolean r,File file,int ow,int oh,int o,boolean analytic,SharedInput shared){
    width=w;height=h;pixels=p;raw=r;input=file;originalWidth=ow;originalHeight=oh;orientation=o;this.analytic=analytic;this.shared=shared==null?new SharedInput(file):shared;
  }
  static RawEditSource synthetic(int previewW,int previewH,ByteBuffer pixels,int fullW,int fullH){
    if(previewW<1||previewH<1||previewW>2048||previewH>2048||pixels==null||pixels.capacity()!=(long)previewW*previewH*16
        ||fullW<1||fullH<1||fullW>16384||fullH>16384||(long)fullW*fullH>80000000L)throw new IllegalArgumentException("Invalid synthetic dimensions");
    return new RawEditSource(previewW,previewH,pixels,false,null,fullW,fullH,1,true,null);
  }
  RawEditSource retain()throws IOException {
    synchronized(shared){
      if(closed||shared.owners<=0)throw new IOException("画像を閉じました");
      RawEditSource lease=new RawEditSource(width,height,pixels,raw,input,originalWidth,originalHeight,orientation,analytic,shared);
      shared.owners++;return lease;
    }
  }
  public void close(){
    synchronized(shared){
      if(closed)return;
      closed=true;pixels=null;
      if(--shared.owners==0&&shared.file!=null&&shared.file.exists()&&!shared.file.delete())RawEditLog.event("source.cleanup.failed",0,0,0);
    }
  }
  int originalWidth(){return originalWidth;}
  int originalHeight(){return originalHeight;}
  void releasePreviewPixels(){pixels=null;}
  void prepareFull(File output,NativeRaw.Cancellation cancel)throws IOException {
    synchronized(shared){if(closed)throw new IOException("画像を閉じました");}
    if(output==null||cancel==null)throw new IllegalArgumentException("Invalid full-image request");
    try {
      check(cancel);
      if(raw){if(input==null)throw new IOException("原寸画像を準備できません");NativeRaw.prepareFull(input.getAbsolutePath(),output.getAbsolutePath(),cancel);return;}
      if(input==null)writeSynthetic(output,cancel);else writeJpeg(output,cancel);
    }catch(IOException|SecurityException failure){throw new IOException("原寸画像を準備できません");}
    finally{pixels=null;}
  }
  private void writeSynthetic(File output,NativeRaw.Cancellation cancel)throws IOException {
    checkSize(originalWidth,originalHeight);
    ByteBuffer preview=pixels;
    if(!analytic&&(preview==null||preview.capacity()!=(long)width*height*16))throw new IOException("原寸画像を準備できません");
    if(!output.createNewFile())throw new IOException("原寸画像を準備できません");
    boolean complete=false;
    try {
      try(FileOutputStream stream=new FileOutputStream(output)){
        stream.write(header(originalWidth,originalHeight));
        FloatBuffer values=analytic?null:preview.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer();byte[] row=new byte[originalWidth*6];
        for(int y=0;y<originalHeight;y++){
          check(cancel);
          for(int x=0;x<originalWidth;x++){
            if(analytic)analyticPixel(row,x,y,originalWidth,originalHeight);
            else {for(int c=0;c<3;c++)put16(row,x*6+c*2,values.get());values.get();}
          }
          stream.write(row);
        }
        stream.flush();stream.getFD().sync();check(cancel);
      }
      validate(output,originalWidth,originalHeight,cancel);complete=true;
    }finally{if(!complete)output.delete();}
  }
  private static final int[] TOP={134,168,200},SKY={201,214,222},GROUND={125,143,106},BOTTOM={78,93,64},RED={201,87,62},BEIGE={232,210,176};
  private static void analyticPixel(byte[] row,int x,int y,int w,int h){
    float yy=h==1?0:y/(float)(h-1),xx=w==1?0:x/(float)(w-1);
    int[] lo=yy<=.52f?TOP:yy<.53f?SKY:GROUND,hi=yy<=.52f?SKY:yy<.53f?GROUND:BOTTOM;
    float amount=yy<=.52f?yy/.52f:yy<.53f?(yy-.52f)/.01f:(yy-.53f)/.47f;
    float small=(float)Math.hypot((xx-.7f)/.09f,(yy-.34f)/.12f),large=(float)Math.hypot((xx-.36f)/.14f,(yy-.58f)/.2f);
    float a=Math.max(0,Math.min(1,(1-small)/.04f)),c=Math.max(0,Math.min(1,(1-large)/.04f));
    for(int k=0;k<3;k++){float v=(lo[k]+(hi[k]-lo[k])*amount)/255f;v=v*(1-a)+RED[k]/255f*a;v=v*(1-c)+BEIGE[k]/255f*c;put16(row,x*6+k*2,v<=.04045f?v/12.92f:(float)Math.pow((v+.055f)/1.055f,2.4));}
  }
  private static void validate(File file,int w,int h,NativeRaw.Cancellation cancel)throws IOException {
    check(cancel);
    try(RawEditFullSource cache=RawEditFullSource.open(file)){if(cache.width!=w||cache.height!=h)throw new IOException("原寸画像を準備できません");}
    check(cancel);
  }
  private void writeJpeg(File output,NativeRaw.Cancellation cancel)throws IOException {
    if(!output.createNewFile())throw new IOException("原寸画像を準備できません");
    boolean complete=false;BitmapRegionDecoder decoder=null;
    try {
     try(RandomAccessFile out=new RandomAccessFile(output,"rw")){
      decoder=BitmapRegionDecoder.newInstance(input.getAbsolutePath(),false);if(decoder==null)throw new IOException("画像を読み込めません");
      int sourceW=decoder.getWidth(),sourceH=decoder.getHeight();checkSize(sourceW,sourceH);
      if(sourceW!=(orientation>=5&&orientation<=8?originalHeight:originalWidth)||sourceH!=(orientation>=5&&orientation<=8?originalWidth:originalHeight))throw new IOException("画像の寸法が変わりました");
      Matrix matrix=orientation(orientation);RectF all=new RectF(0,0,sourceW,sourceH);matrix.mapRect(all);matrix.postTranslate(-all.left,-all.top);
      byte[] head=header(originalWidth,originalHeight);out.write(head);out.setLength(head.length+(long)originalWidth*originalHeight*6);
      BitmapFactory.Options opts=new BitmapFactory.Options();opts.inPreferredConfig=Bitmap.Config.ARGB_8888;
      for(int y=0;y<sourceH;y+=256)for(int x=0;x<sourceW;x+=256){
        check(cancel);Rect r=new Rect(x,y,Math.min(sourceW,x+256),Math.min(sourceH,y+256));Bitmap tile=decoder.decodeRegion(r,opts);if(tile==null)throw new IOException("画像を読み込めません");Bitmap rotated=null;
        try{
          rotated=Bitmap.createBitmap(tile,0,0,tile.getWidth(),tile.getHeight(),matrix,false);RectF target=new RectF(r);matrix.mapRect(target);int left=Math.round(target.left),top=Math.round(target.top),w=rotated.getWidth(),h=rotated.getHeight();
          if(left<0||top<0||left+w>originalWidth||top+h>originalHeight)throw new IOException("画像の向きを適用できません");
          int[] argb=new int[w];byte[] row=new byte[w*6];
          for(int yy=0;yy<h;yy++){check(cancel);rotated.getPixels(argb,0,w,0,yy,w,1);for(int xx=0;xx<w;xx++){int c=argb[xx];put16(row,xx*6,linear(c>>16&255));put16(row,xx*6+2,linear(c>>8&255));put16(row,xx*6+4,linear(c&255));}out.seek(head.length+((long)(top+yy)*originalWidth+left)*6);out.write(row);}
        }finally{if(rotated!=null&&rotated!=tile)rotated.recycle();tile.recycle();}
      }
      out.getFD().sync();check(cancel);
     }
     validate(output,originalWidth,originalHeight,cancel);complete=true;
    }finally{if(decoder!=null)decoder.recycle();if(!complete)output.delete();}
  }
  private static byte[] header(int w,int h){return ("P6\n"+w+" "+h+"\n65535\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);}
  private static void check(NativeRaw.Cancellation c)throws IOException {if(c.isCancelled())throw new IOException("画像処理を中止しました");}
  private static void put16(byte[] row,int index,float value){int v=Math.max(0,Math.min(65535,Math.round(value*65535)));row[index]=(byte)(v>>8);row[index+1]=(byte)v;}
  private static void checkSize(int w,int h)throws IOException {if(w<1||h<1||w>16384||h>16384||(long)w*h>80000000)throw new IOException("この画像の寸法には対応していません");}
  private static Matrix orientation(int o){Matrix m=new Matrix();switch(o){case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;case 7:m.setRotate(-90);m.postScale(-1,1);break;case 8:m.setRotate(-90);break;default:break;}return m;}

  static RawEditSource load(Context c,Uri uri)throws IOException {
    if(uri==null||!"content".equals(uri.getScheme()))throw new IOException("写真を選び直してください");
    RawEditLog.event("source.copy.start",0,0,0);
    File temp=File.createTempFile("raw-edit-",".input",c.getCacheDir());
    boolean keep=false;
    try {
      try(InputStream in=c.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temp)){
        if(in==null)throw new IOException("写真を読み込めません");
        byte[] chunk=new byte[65536];long total=0;int n;
        while((n=in.read(chunk))!=-1){
          if(Thread.currentThread().isInterrupted())throw new IOException("読み込みを中止しました");
          total+=n;if(total>256L*1024*1024)throw new IOException("256MB以下の写真を選んでください");out.write(chunk,0,n);
        }
      }
      RawEditLog.event("source.copy.complete",0,0,0);
      keep=true; // fromPrivateCopy now owns cleanup, including all failure paths.
      return fromPrivateCopy(temp);
    } catch(IOException|SecurityException failure){throw new IOException("写真を選び直してください");}
    finally {if(!keep&&temp.exists()&&!temp.delete())RawEditLog.event("source.cleanup.failed",0,0,0);}
  }
  /** Transfers ownership of a caller-created private copy, even when decoding fails. */
  static RawEditSource fromPrivateCopy(File temp)throws IOException {
    boolean keep=false;
    try {
      if(temp==null||!temp.isFile()||temp.length()<1||temp.length()>256L*1024*1024)throw new IOException("256MB以下の写真を選んでください");
      check(new NativeRaw.Cancellation());
      boolean jpeg;
      try(InputStream in=new FileInputStream(temp)){jpeg=in.read()==255&&in.read()==216&&in.read()==255;}
      RawEditSource result;
      if(jpeg)result=jpeg(temp);
      else {
        RawEditLog.event("source.raw.start",0,0,0);
        NativeRaw.Image image=NativeRaw.decode(temp.getAbsolutePath(),2048);
        RawEditLog.event("source.raw.complete",0,image.width,image.height);
        result=new RawEditSource(image.width,image.height,image.pixels,true,temp,image.originalWidth,image.originalHeight,1,false,null);
      }
      keep=true;return result;
    }catch(IOException|SecurityException failure){throw new IOException("写真を選び直してください");}
    finally{if(!keep&&temp!=null&&temp.exists()&&!temp.delete())RawEditLog.event("source.cleanup.failed",0,0,0);}
  }
  private static RawEditSource jpeg(File file)throws IOException {
    BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),o);
    if(o.outWidth<=0||o.outHeight<=0||o.outWidth>16384||o.outHeight>16384||(long)o.outWidth*o.outHeight>80000000)
      throw new IOException("この画像の寸法には対応していません");
    final int ow=o.outWidth,oh=o.outHeight;
    o.inJustDecodeBounds=false;o.inSampleSize=1;o.inPreferredConfig=Bitmap.Config.ARGB_8888;
    while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>4096)o.inSampleSize*=2;
    Bitmap image=BitmapFactory.decodeFile(file.getPath(),o);if(image==null)throw new IOException("JPEGを読み込めません");
    try {
      int orientation=new ExifInterface(file.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);
      Matrix m=new Matrix();
      switch(orientation){
        case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;
        case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;
        case 7:m.setRotate(-90);m.postScale(-1,1);break;case 8:m.setRotate(-90);break;
        default:break;
      }
      Bitmap rotated=Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),m,true);
      if(rotated!=image){image.recycle();image=rotated;}
      float scale=Math.min(1,2048f/Math.max(image.getWidth(),image.getHeight()));
      Bitmap small=Bitmap.createScaledBitmap(image,Math.max(1,(int)(image.getWidth()*scale)),Math.max(1,(int)(image.getHeight()*scale)),true);
      if(small!=image){image.recycle();image=small;}
      int w=image.getWidth(),h=image.getHeight();ByteBuffer buffer=ByteBuffer.allocateDirect(w*h*16).order(ByteOrder.nativeOrder());
      FloatBuffer floats=buffer.asFloatBuffer();int[] row=new int[w];
      for(int y=0;y<h;y++){
        if(Thread.currentThread().isInterrupted())throw new IOException("読み込みを中止しました");
        image.getPixels(row,0,w,0,y,w,1);
        for(int argb:row){floats.put(linear((argb>>16)&255));floats.put(linear((argb>>8)&255));floats.put(linear(argb&255));floats.put(1);}
      }
      buffer.position(0);return new RawEditSource(w,h,buffer,false,file,orientation>=5&&orientation<=8?oh:ow,orientation>=5&&orientation<=8?ow:oh,orientation,false,null);
    } finally {image.recycle();}
  }
  private static float linear(int c){float v=c/255f;return v<=.04045f?v/12.92f:(float)Math.pow((v+.055)/1.055,2.4);}
}
