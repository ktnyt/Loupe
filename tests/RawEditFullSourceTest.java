package jp.nano.rawgallery;
import android.graphics.*;
import java.io.*;
import java.nio.*;
public final class RawEditFullSourceTest {
  static void require(boolean x,String m){if(!x)throw new AssertionError(m);}
  static void fixture(File f,int w,int h,int channels)throws Exception{
    try(DataOutputStream out=new DataOutputStream(new FileOutputStream(f))){out.writeBytes((channels==1?"P5":"P6")+"\n# fixture\n"+w+" "+h+"\n65535\n");for(int y=0;y<h;y++)for(int x=0;x<w;x++)for(int c=0;c<channels;c++)out.writeShort((x&1)==0?0:65535);}
  }
  public static void main(String[] args)throws Exception{
    File f=File.createTempFile("raw-full-test",".ppm");
    try{
      fixture(f,3101,11,3);
      try(RawEditFullSource s=RawEditFullSource.open(f)){
        require(s.width==3101&&s.height==11,"dimensions");ByteBuffer b=s.region(new Rect(2200,2,2202,3));require(b.getFloat(0)==0&&b.getFloat(16)==1,"original fine detail");
        RawEditFullSource.Preview p=s.preview(2048);require(p.width==2048&&p.height==7&&p.pixels.remaining()==2048*7*16,"bounded preview");
        boolean bad=false;try{s.region(new Rect(0,0,2049,1));}catch(IOException e){bad=true;}require(bad,"bounded region");
        Thread.currentThread().interrupt();bad=false;try{s.preview(16);}catch(IOException e){bad=true;}finally{Thread.interrupted();}require(bad,"cancellation");
      }
      fixture(f,9,7,1);try(RawEditFullSource s=RawEditFullSource.open(f)){ByteBuffer b=s.region(new Rect(1,0,2,1));require(b.getFloat(0)==1&&b.getFloat(4)==1&&b.getFloat(8)==1&&b.getFloat(12)==1,"PGM replication");}
      try(RandomAccessFile out=new RandomAccessFile(f,"rw")){out.setLength(out.length()-1);}boolean bad=false;try{RawEditFullSource.open(f);}catch(IOException e){bad=true;}require(bad,"payload exactness");
      try(FileOutputStream out=new FileOutputStream(f)){out.write("P6\n16385 2\n65535\n".getBytes("US-ASCII"));}bad=false;try{RawEditFullSource.open(f);}catch(IOException e){bad=true;}require(bad,"dimension limit");
      int area=0;for(RawEditTilePlan.Tile t:new RawEditTilePlan(new RectF(0,0,16384,4000),16384,128,16384,4000)){
        require(t.width<=512&&t.height<=512,"output bound");require(t.source.width()+2*161+2<=2048.01&&t.source.height()+2*161+2<=2048.01,"input halo bound");area+=t.width*t.height;
      }require(area==16384*128,"complete output partition");
      System.out.println("RawEditFullSourceTest passed");
    }finally{f.delete();}
  }
}
