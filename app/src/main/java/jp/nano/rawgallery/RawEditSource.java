package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import java.io.*;
import java.nio.*;

/** Bounded private copy of a selected document; no path or photo metadata leaves this class. */
final class RawEditSource {
  final int width,height;
  final ByteBuffer pixels;
  final boolean raw;
  RawEditSource(int w,int h,ByteBuffer p,boolean r){width=w;height=h;pixels=p;raw=r;}
  static RawEditSource load(Context c,Uri uri)throws IOException {
    if(uri==null||!"content".equals(uri.getScheme()))throw new IOException("写真を選び直してください");
    File temp=File.createTempFile("raw-edit-",".input",c.getCacheDir());
    try {
      try(InputStream in=c.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temp)){
        if(in==null)throw new IOException("写真を読み込めません");
        byte[] chunk=new byte[65536];long total=0;int n;
        while((n=in.read(chunk))!=-1){
          if(Thread.currentThread().isInterrupted())throw new IOException("読み込みを中止しました");
          total+=n;if(total>256L*1024*1024)throw new IOException("256MB以下の写真を選んでください");out.write(chunk,0,n);
        }
      }
      boolean jpeg;
      try(InputStream in=new FileInputStream(temp)){jpeg=in.read()==255&&in.read()==216&&in.read()==255;}
      if(jpeg)return jpeg(temp);
      NativeRaw.Image image=NativeRaw.decode(temp.getAbsolutePath(),2048);
      return new RawEditSource(image.width,image.height,image.pixels,true);
    } catch(SecurityException failure){throw new IOException("写真を選び直してください");}
    finally {if(!temp.delete())temp.deleteOnExit();}
  }
  private static RawEditSource jpeg(File file)throws IOException {
    BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),o);
    if(o.outWidth<=0||o.outHeight<=0||o.outWidth>16384||o.outHeight>16384||(long)o.outWidth*o.outHeight>80000000)
      throw new IOException("この画像の寸法には対応していません");
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
      buffer.position(0);return new RawEditSource(w,h,buffer,false);
    } finally {image.recycle();}
  }
  private static float linear(int c){float v=c/255f;return v<=.04045f?v/12.92f:(float)Math.pow((v+.055)/1.055,2.4);}
}
