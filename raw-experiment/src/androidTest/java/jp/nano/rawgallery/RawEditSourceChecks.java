package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.ExifInterface;
import java.io.*;
import java.nio.*;
import java.util.concurrent.atomic.AtomicReference;

/** Private synthetic JPEGs only. Exercises the same entry point as a content-URI copy. */
final class RawEditSourceChecks {
  private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
  private static File unused(File dir)throws IOException {File f=File.createTempFile("synthetic-source-",".ppm",dir);require(f.delete(),"reserve unused output");return f;}
  private static File jpeg(File dir,int w,int h,int orientation)throws IOException {
    File file=File.createTempFile("synthetic-source-",".jpg",dir);Bitmap image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
    try {
      int[] row=new int[w];
      for(int y=0;y<h;y++){for(int x=0;x<w;x++)row[x]=Color.rgb(30+(x*173/Math.max(1,w-1)),40+(y*151/Math.max(1,h-1)),60+((x/31+y/29)%2)*110);image.setPixels(row,0,w,0,y,w,1);}
      try(FileOutputStream out=new FileOutputStream(file)){require(image.compress(Bitmap.CompressFormat.JPEG,98,out),"encode synthetic JPEG");}
      ExifInterface exif=new ExifInterface(file.getPath());exif.setAttribute(ExifInterface.TAG_ORIENTATION,Integer.toString(orientation));exif.saveAttributes();return file;
    }catch(Throwable failure){file.delete();throw failure;}finally{image.recycle();}
  }
  private static float linear(int c){float v=c/255f;return v<=.04045f?v/12.92f:(float)Math.pow((v+.055)/1.055,2.4);}
  private static int[] mapped(int x,int y,int w,int h,int o){
    switch(o){case 2:return new int[]{w-1-x,y};case 3:return new int[]{w-1-x,h-1-y};case 4:return new int[]{x,h-1-y};case 5:return new int[]{y,x};case 6:return new int[]{h-1-y,x};case 7:return new int[]{h-1-y,w-1-x};case 8:return new int[]{y,w-1-x};default:return new int[]{x,y};}
  }
  private static void patchDimensions(File file,int w,int h)throws IOException {
    try(RandomAccessFile jpeg=new RandomAccessFile(file,"rw")){
      jpeg.seek(2);
      while(jpeg.getFilePointer()<jpeg.length()){
        if(jpeg.readUnsignedByte()!=255)throw new IOException("Invalid fixture marker");
        int marker;do{marker=jpeg.readUnsignedByte();}while(marker==255);
        if(marker==0xd9||marker==0xda)break;
        int length=jpeg.readUnsignedShort();long payload=jpeg.getFilePointer();
        if(marker==0xc0||marker==0xc1||marker==0xc2){jpeg.seek(payload+1);jpeg.writeShort(h);jpeg.writeShort(w);return;}
        jpeg.seek(payload+length-2);
      }
      throw new IOException("Missing fixture dimensions");
    }
  }
  static void run(Instrumentation instrumentation)throws Exception {
    File dir=instrumentation.getTargetContext().getCacheDir();
    for(int orientation=1;orientation<=8;orientation++){
      int w=513,h=323;File input=jpeg(dir,w,h,orientation),output=unused(dir);Bitmap expected=BitmapFactory.decodeFile(input.getPath());
      RawEditSource owner=null,work=null;
      try {
        owner=RawEditSource.fromPrivateCopy(input);require(owner.originalWidth()==(orientation>=5?h:w)&&owner.originalHeight()==(orientation>=5?w:h),"initial metadata dimensions");work=owner.retain();owner.close();owner.close();require(input.exists(),"owner double-close preserves retained input");
        work.prepareFull(output,new NativeRaw.Cancellation());require(work.pixels==null,"prepare releases preview reference");
        try(RawEditFullSource full=RawEditFullSource.open(output)){
          require(full.width==(orientation>=5?h:w)&&full.height==(orientation>=5?w:h),"oriented original dimensions");
          for(int[] point:new int[][]{{0,0},{512,322},{255,255},{256,256},{37,41},{490,21},{21,300}}){
            int[] p=mapped(point[0],point[1],w,h,orientation);int argb=expected.getPixel(point[0],point[1]);ByteBuffer sample=full.region(new Rect(p[0],p[1],p[0]+1,p[1]+1));
            for(int c=0;c<3;c++)require(Math.abs(sample.getFloat(c*4)-linear((argb>>(16-c*8))&255))<.025,"EXIF pixel orientation and tile boundaries");
          }
        }
      }finally{if(work!=null)work.close();if(owner!=null)owner.close();expected.recycle();input.delete();output.delete();}
      require(!input.exists(),"last lease removes private input");
    }
    // Sampled preview options must not overwrite the original bounds.
    File wide=jpeg(dir,4201,17,6),wideOut=unused(dir);
    try(RawEditSource source=RawEditSource.fromPrivateCopy(wide)){
      require(source.width<=2048&&source.height<=2048,"bounded JPEG preview");source.prepareFull(wideOut,new NativeRaw.Cancellation());
      try(RawEditFullSource full=RawEditFullSource.open(wideOut)){require(full.width==17&&full.height==4201,"bounds survive sampled decode");}
    }finally{wide.delete();wideOut.delete();}
    File rejected=File.createTempFile("synthetic-source-limit-",".input",dir);
    try {try(RandomAccessFile file=new RandomAccessFile(rejected,"rw")){file.setLength(256L*1024*1024+1);}boolean failed=false;try{RawEditSource.fromPrivateCopy(rejected);}catch(IOException expected){failed=true;}require(failed&&!rejected.exists(),"oversize private copy rejected and removed");}finally{rejected.delete();}
    for(int[] size:new int[][]{{16385,2},{10000,9000}}){
      File dimensions=jpeg(dir,17,13,1);patchDimensions(dimensions,size[0],size[1]);boolean rejectedBounds=false;
      try{RawEditSource.fromPrivateCopy(dimensions);}catch(IOException expected){rejectedBounds=true;}
      require(rejectedBounds&&!dimensions.exists(),"dimension/80MP limit rejects before allocation and cleans input");
    }
    // Invalid/failed decode transfers cleanup ownership too.
    File invalid=File.createTempFile("synthetic-source-invalid-",".input",dir);try(FileOutputStream out=new FileOutputStream(invalid)){out.write(new byte[]{(byte)255,(byte)216,(byte)255,0});}
    boolean failed=false;try{RawEditSource.fromPrivateCopy(invalid);}catch(IOException expected){failed=true;}require(failed&&!invalid.exists(),"failed JPEG decode cleans private input");
    ByteBuffer preview=ByteBuffer.allocateDirect(2*2*16).order(ByteOrder.nativeOrder());File analytic=unused(dir);
    try(RawEditSource source=RawEditSource.synthetic(2,2,preview,101,73)){
      source.prepareFull(analytic,new NativeRaw.Cancellation());
      try(RawEditFullSource full=RawEditFullSource.open(analytic)){require(full.width==101&&full.height==73,"analytic original dimensions");ByteBuffer p=full.region(new Rect(0,0,1,1));require(Math.abs(p.getFloat()-linear(134))<.00003,"analytic generation does not enlarge black preview");}
      try(FileOutputStream extra=new FileOutputStream(analytic,true)){extra.write(0);}boolean surplus=false;
      try{RawEditFullSource.open(analytic);}catch(IOException expected){surplus=true;}require(surplus,"surplus cache payload rejected");
    }finally{analytic.delete();}
    File cancelled=unused(dir);NativeRaw.Cancellation cancellation=new NativeRaw.Cancellation();AtomicReference<Throwable> error=new AtomicReference<>();
    try(RawEditSource source=RawEditSource.synthetic(2,2,preview,6000,4000)){
      Thread worker=new Thread(()->{try{source.prepareFull(cancelled,cancellation);}catch(Throwable result){error.set(result);}},"synthetic-source-cancel");worker.start();
      long deadline=System.nanoTime()+10_000_000_000L;while(!cancelled.exists()&&worker.isAlive()&&System.nanoTime()<deadline)Thread.yield();
      cancellation.cancel();worker.join(10000);require(!worker.isAlive(),"scanline cancellation completes");require(error.get() instanceof IOException&&!cancelled.exists(),"partial output removed on cancellation");
    }finally{cancelled.delete();}
    File cancelInput=jpeg(dir,1501,1103,7),cancelJpeg=unused(dir);
    RawEditSource inputOwner=RawEditSource.fromPrivateCopy(cancelInput),inputWork=inputOwner.retain();inputOwner.close();
    NativeRaw.Cancellation jpegCancellation=new NativeRaw.Cancellation();AtomicReference<Throwable> jpegError=new AtomicReference<>();
    Thread jpegWorker=new Thread(()->{try{inputWork.prepareFull(cancelJpeg,jpegCancellation);}catch(Throwable result){jpegError.set(result);}finally{inputWork.close();}},"synthetic-jpeg-cancel");
    try {
      require(cancelInput.exists(),"retained JPEG input survives owner close before work starts");jpegWorker.start();
      long deadline=System.nanoTime()+10_000_000_000L;while(!cancelJpeg.exists()&&jpegWorker.isAlive()&&System.nanoTime()<deadline)Thread.yield();
      jpegCancellation.cancel();jpegWorker.join(10000);
      require(!jpegWorker.isAlive()&&jpegError.get() instanceof IOException,"JPEG region cancellation completes");
      require(!cancelJpeg.exists()&&!cancelInput.exists(),"cancelled JPEG output and final input lease are cleaned");
    }finally{jpegCancellation.cancel();inputWork.close();cancelJpeg.delete();cancelInput.delete();}
    File preserve=File.createTempFile("synthetic-source-existing-",".ppm",dir);try(FileOutputStream out=new FileOutputStream(preserve)){out.write(42);}
    try(RawEditSource source=RawEditSource.synthetic(2,2,preview,101,73)){
      failed=false;try{source.prepareFull(preserve,new NativeRaw.Cancellation());}catch(IOException expected){failed=true;}require(failed&&preserve.length()==1,"existing output protected");
      source.close();source.close();failed=false;try{source.retain();}catch(IOException expected){failed=true;}require(failed,"closed lease cannot retain");
    }finally{preserve.delete();}
  }
}
