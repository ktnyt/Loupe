package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.graphics.*;
import java.io.*;
import java.nio.*;

/** Synthetic cache/GL checks; never opens an original photo. */
final class RawEditTileChecks {
  private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  static void fixture(File file,int w,int h,int channels)throws IOException {
    try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))){
      out.writeBytes((channels==1?"P5":"P6")+"\n# synthetic only\n"+w+" "+h+"\n65535\n");
      for(int y=0;y<h;y++)for(int x=0;x<w;x++)for(int c=0;c<channels;c++)
        out.writeShort((x%2==0?12000:44000)+(y%5)*900+c*1300);
    }
  }
  static void run(Instrumentation instrumentation)throws Exception {
    File cache=File.createTempFile("synthetic-tiles-",".ppm",instrumentation.getTargetContext().getCacheDir());
    try {
      fixture(cache,3101,117,3);
      try(RawEditFullSource full=RawEditFullSource.open(cache)){
        require(full.width==3101&&full.height==117,"valid effective cache dimensions");
        ByteBuffer detail=full.region(new Rect(2200,20,2202,21));
        require(Math.abs(detail.getFloat(0)-detail.getFloat(16))>.4,"original alternating detail beyond preview resolution");
        require(full.preview(2048).width==2048,"bounded replacement preview");
        boolean rejected=false;try{full.region(new Rect(0,0,2049,1));}catch(IOException expected){rejected=true;}
        require(rejected,"oversized input tiles rejected");
      }
      fixture(cache,193,127,3);
      try(RawEditFullSource full=RawEditFullSource.open(cache);
          RawEditRenderer reference=new RawEditRenderer(full.width,full.height,full.region(new Rect(0,0,full.width,full.height)));
          RawEditRenderer tiles=new RawEditRenderer()){
        // Closing a sibling renderer must not terminate the shared EGLDisplay.
        new RawEditRenderer().close();
        for(RawEditState state:new RawEditState[]{new RawEditState(),new RawEditState().with(2,1).with(3,1),new RawEditState().with(16,1)})
          for(int mode=0;mode<3;mode++){
            Bitmap expected=reference.render(state,mode==1,mode==2,2048);
            try {
              // Alternate EGL contexts before each tile, checking both halo seams and physical edges.
              for(int y=0;y<127;y+=31)for(int x=0;x<193;x+=47){
                int w=Math.min(47,193-x),h=Math.min(31,127-y);
                Bitmap actual=tiles.renderRegionBitmap(full,state,mode==1,mode==2,new RectF(x,y,x+w,y+h),w,h);
                try {for(int j=0;j<h;j++)for(int i=0;i<w;i++){
                  int a=actual.getPixel(i,j),b=expected.getPixel(x+i,y+j);
                  for(int shift:new int[]{0,8,16})require(Math.abs(((a>>shift)&255)-((b>>shift)&255))<=2,"tiled/untiled RGBA tolerance");
                }}finally{actual.recycle();}
                Bitmap switched=reference.render(state,mode==1,mode==2,2048);switched.recycle();
              }
            }finally{expected.recycle();}
          }
      }
      fixture(cache,9,7,1);
      try(RawEditFullSource full=RawEditFullSource.open(cache)){
        ByteBuffer pixel=full.region(new Rect(0,0,1,1));require(pixel.getFloat(0)==pixel.getFloat(4)&&pixel.getFloat(4)==pixel.getFloat(8),"monochrome cache replicates RGB");
      }
      try(RandomAccessFile truncated=new RandomAccessFile(cache,"rw")){truncated.setLength(truncated.length()-1);}
      boolean rejected=false;try{RawEditFullSource.open(cache);}catch(IOException expected){rejected=true;}
      require(rejected,"truncated payload rejected");
    }finally{cache.delete();}
  }
}
