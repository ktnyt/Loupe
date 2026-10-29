package jp.nano.rawgallery;

import android.graphics.Rect;
import java.io.*;
import java.nio.*;
import java.util.concurrent.ConcurrentHashMap;

/** Validated oriented linear RGB16 cache. Each read owns its descriptor; never maps the image. */
final class RawEditFullSource implements AutoCloseable {
  static final int MAX_REGION_SIDE=2048;
  final int width,height;
  private final File file;
  private final int channels;
  private final long offset;
  private volatile boolean closed;
  private final ConcurrentHashMap<Thread,ByteBuffer> regionBuffers=new ConcurrentHashMap<>();
  static final class Preview {
    final int width,height; final ByteBuffer pixels;
    Preview(int w,int h,ByteBuffer b){width=w;height=h;pixels=b;}
  }
  private RawEditFullSource(File f,int w,int h,int c,long o){file=f;width=w;height=h;channels=c;offset=o;}
  static RawEditFullSource open(File file)throws IOException {
    try(RandomAccessFile in=new RandomAccessFile(file,"r")){
      Header header=new Header(in);String magic=header.token();
      int c="P6".equals(magic)?3:"P5".equals(magic)?1:0;
      int w=Integer.parseInt(header.token()),h=Integer.parseInt(header.token());
      int max=Integer.parseInt(header.token());
      long start=in.getFilePointer();
      if(c==0||w<1||h<1||w>16384||h>16384||(long)w*h>80000000L||max!=65535
          ||in.length()!=start+(long)w*h*c*2)throw failure();
      return new RawEditFullSource(file,w,h,c,start);
    }catch(IOException|RuntimeException e){throw failure();}
  }
  // The mandatory single whitespace after maxval belongs to the header, further bytes are pixels.
  private static final class Header {
    final RandomAccessFile in;int count;
    Header(RandomAccessFile f){in=f;}
    int read()throws IOException {if(++count>65536)throw failure();return in.read();}
    String token()throws IOException {
      StringBuilder s=new StringBuilder();int c;
      do {c=read();if(c=='#'){do{c=read();}while(c!=-1&&c!='\n');}}while(c>=0&&c<=32);
      while(c>32&&c!='#') {if(s.length()>32)throw failure();s.append((char)c);c=read();}
      if(c<0||c=='#'||s.length()==0)throw failure();
      if(c=='\r'){long pos=in.getFilePointer();if(read()!='\n')in.seek(pos);}
      return s.toString();
    }
  }
  ByteBuffer region(Rect r)throws IOException {
    check();if(r==null||r.left<0||r.top<0||r.right>width||r.bottom>height||r.width()<1||r.height()<1
        ||r.width()>MAX_REGION_SIDE||r.height()>MAX_REGION_SIDE)throw failure();
    int size=r.width()*r.height()*16;
    ByteBuffer out=regionBuffers.get(Thread.currentThread());
    if(out==null||out.capacity()<size){out=ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());regionBuffers.put(Thread.currentThread(),out);}
    out.clear();out.limit(size);
    byte[] row=new byte[r.width()*channels*2];
    try(RandomAccessFile in=new RandomAccessFile(file,"r")){
      for(int y=r.top;y<r.bottom;y++) {check();in.seek(offset+((long)y*width+r.left)*channels*2);in.readFully(row);
        for(int x=0;x<r.width();x++){int i=x*channels*2;float red=value(row,i);out.putFloat(red);
          out.putFloat(channels==1?red:value(row,i+2));out.putFloat(channels==1?red:value(row,i+4));out.putFloat(1);}
      }
    }catch(IOException e){throw failure();}
    check();out.flip();return out;
  }
  Preview preview(int maxSide)throws IOException {
    check();if(maxSide<1||maxSide>2048)throw failure();
    double scale=Math.min(1,maxSide/(double)Math.max(width,height));
    int w=Math.max(1,(int)Math.round(width*scale)),h=Math.max(1,(int)Math.round(height*scale));
    ByteBuffer out=ByteBuffer.allocateDirect(w*h*16).order(ByteOrder.nativeOrder());
    byte[][] rows={new byte[width*channels*2],new byte[width*channels*2]};int[] keys={-1,-1};
    try(RandomAccessFile in=new RandomAccessFile(file,"r")){
      for(int y=0;y<h;y++){check();double sy=Math.max(0,Math.min(height-1,(y+.5)*height/h-.5));
        int y0=(int)sy,y1=Math.min(height-1,y0+1);float fy=(float)(sy-y0);
        byte[] a=row(in,rows,keys,y0),b=row(in,rows,keys,y1);
        for(int x=0;x<w;x++){double sx=Math.max(0,Math.min(width-1,(x+.5)*width/w-.5));
          int x0=(int)sx,x1=Math.min(width-1,x0+1);float fx=(float)(sx-x0);
          for(int c=0;c<3;c++){int k=channels==1?0:c;float top=value(a,(x0*channels+k)*2)*(1-fx)+value(a,(x1*channels+k)*2)*fx;
            float bottom=value(b,(x0*channels+k)*2)*(1-fx)+value(b,(x1*channels+k)*2)*fx;out.putFloat(top*(1-fy)+bottom*fy);}
          out.putFloat(1);
        }
      }
    }catch(IOException e){throw failure();}
    out.flip();return new Preview(w,h,out);
  }
  private byte[] row(RandomAccessFile in,byte[][] rows,int[] keys,int y)throws IOException {
    int slot=y&1;if(keys[slot]!=y){check();in.seek(offset+(long)y*width*channels*2);in.readFully(rows[slot]);keys[slot]=y;}return rows[slot];
  }
  private static float value(byte[] b,int i){return (((b[i]&255)<<8)|(b[i+1]&255))/65535f;}
  private void check()throws IOException {if(closed||Thread.currentThread().isInterrupted())throw failure();}
  private static IOException failure(){return new IOException("原寸画像を読み込めません");}
  public void close(){closed=true;regionBuffers.clear();}
}
