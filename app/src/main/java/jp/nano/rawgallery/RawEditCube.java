package jp.nano.rawgallery;

import java.io.*;
import java.nio.*;
import java.util.Arrays;

/** Immutable .cube 3D table. Input/output are display-referred, encoded sRGB, red varies fastest. */
public final class RawEditCube {
  public static final int MAX_SIZE=65,MAX_TEXT_BYTES=32*1024*1024;
  private final int size;
  private final float[] min,max,values;
  private final String title;
  private RawEditCube(int size,float[] min,float[] max,float[] values,String title){
    if(size<2||size>MAX_SIZE||values.length!=size*size*size*3)throw new IllegalArgumentException("Invalid cube size");
    for(int c=0;c<3;c++)if(!Float.isFinite(min[c])||!Float.isFinite(max[c])||max[c]<=min[c]||!Float.isFinite(max[c]-min[c]))throw new IllegalArgumentException("Invalid cube domain");
    for(float v:values)if(!Float.isFinite(v)||Math.abs(v)>65504)throw new IllegalArgumentException("Invalid cube value");
    this.size=size;this.min=min.clone();this.max=max.clone();this.values=values.clone();this.title=title;
  }
  public int size(){return size;}
  public String title(){return title;}
  public float[] domainMin(){return min.clone();}
  public float[] domainMax(){return max.clone();}
  public static RawEditCube read(InputStream input)throws IOException {
    // Caller owns input. Stream through bounded byte/character buffers without retaining source text.
    BufferedReader reader=new BufferedReader(new InputStreamReader(new BoundedInput(input),
        java.nio.charset.StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)),8192);
    int size=0,index=0;float[] lo={0,0,0},hi={1,1,1},data=null;boolean gotMin=false,gotMax=false,gotTitle=false,started=false;String title="External LUT";
    String line;int lineNo=0;
    while((line=boundedLine(reader))!=null){lineNo++;if(lineNo==1&&line.startsWith("\ufeff"))line=line.substring(1);boolean quoted=false;for(int j=0;j<line.length();j++){char ch=line.charAt(j);if(ch=='\"')quoted=!quoted;else if(ch=='#'&&!quoted){line=line.substring(0,j);break;}}line=line.trim();if(line.isEmpty())continue;
      String[] words=line.split("\\s+");
      try {
        switch(words[0]){
          case "TITLE": if(gotTitle||started)throw new IllegalArgumentException();title=line.substring(5).trim();if(title.length()<2||title.charAt(0)!='\"'||title.charAt(title.length()-1)!='\"'||title.length()>258)throw new IllegalArgumentException();title=title.substring(1,title.length()-1);gotTitle=true;break;
          case "LUT_3D_SIZE": if(size!=0||started||words.length!=2)throw new IllegalArgumentException();size=Integer.parseInt(words[1]);if(size<2||size>MAX_SIZE)throw new IllegalArgumentException();data=new float[size*size*size*3];break;
          case "DOMAIN_MIN": if(gotMin||started||words.length!=4)throw new IllegalArgumentException();for(int c=0;c<3;c++)lo[c]=finite(words[c+1]);gotMin=true;break;
          case "DOMAIN_MAX": if(gotMax||started||words.length!=4)throw new IllegalArgumentException();for(int c=0;c<3;c++)hi[c]=finite(words[c+1]);gotMax=true;break;
          default: if(size==0||words.length!=3||index+3>data.length)throw new IllegalArgumentException();for(String word:words)data[index++]=finite(word);started=true;
        }
      }catch(IllegalArgumentException failure){throw new IOException("Invalid/unsupported .cube directive or data at line "+lineNo,failure);}
    }
    if(data==null||index!=data.length)throw new IOException("Incomplete 3D LUT");
    try{return new RawEditCube(size,lo,hi,data,title);}catch(IllegalArgumentException failure){throw new IOException("Invalid 3D LUT domain or values",failure);}
  }
  private static final class BoundedInput extends InputStream {
    private final InputStream input;private int total;
    BoundedInput(InputStream input){this.input=java.util.Objects.requireNonNull(input);}
    private void counted(int count)throws IOException {if(count>0){total+=count;if(total>MAX_TEXT_BYTES)throw new IOException("LUT exceeds 32 MiB");}}
    private static void checkCancellation()throws IOException {if(Thread.currentThread().isInterrupted())throw new IOException("LUT reading cancelled");}
    public int read()throws IOException {checkCancellation();int value=input.read();if(value!=-1)counted(1);return value;}
    public int read(byte[] bytes,int offset,int length)throws IOException {checkCancellation();if(length==0)return 0;int count=input.read(bytes,offset,Math.min(length,MAX_TEXT_BYTES-total+1));counted(count);return count;}
    // Deliberately do not close the caller-owned stream.
  }
  private static String boundedLine(BufferedReader reader)throws IOException {
    if(Thread.currentThread().isInterrupted())throw new IOException("LUT reading cancelled");
    StringBuilder line=new StringBuilder(128);int value;
    while((value=reader.read())!=-1){
      if(value=='\n')return line.toString();
      if(value=='\r'){reader.mark(1);if(reader.read()!='\n')reader.reset();return line.toString();}
      if(line.length()==4096)throw new IOException("LUT line too long");line.append((char)value);
    }
    return line.length()==0?null:line.toString();
  }
  private static float finite(String word){float v=Float.parseFloat(word);if(!Float.isFinite(v))throw new IllegalArgumentException();return v;}
  public double[] apply(double[] input){
    if(input==null||input.length!=3)throw new IllegalArgumentException("Expected RGB");int[] a=new int[3],b=new int[3];double[] f=new double[3];
    for(int c=0;c<3;c++){if(!Double.isFinite(input[c]))throw new IllegalArgumentException("Non-finite LUT input");double p=Math.max(0,Math.min(1,(input[c]-min[c])/(max[c]-min[c])))*(size-1);a[c]=(int)p;b[c]=Math.min(size-1,a[c]+1);f[c]=p-a[c];}
    double[] result=new double[3];
    for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++){
      double weight=(x==0?1-f[0]:f[0])*(y==0?1-f[1]:f[1])*(z==0?1-f[2]:f[2]);int offset=(((z==0?a[2]:b[2])*size+(y==0?a[1]:b[1]))*size+(x==0?a[0]:b[0]))*3;
      for(int c=0;c<3;c++)result[c]+=values[offset+c]*weight;
    }return result;
  }
  /** GPU texel order exactly matches red-fastest .cube row order. */
  ByteBuffer rgba(){ByteBuffer out=ByteBuffer.allocateDirect(size*size*size*16).order(ByteOrder.nativeOrder());for(int i=0;i<values.length;i+=3)out.putFloat(values[i]).putFloat(values[i+1]).putFloat(values[i+2]).putFloat(1);out.flip();return out;}
  public static RawEditCube identity(int size){return generated(size,false);}
  public static RawEditCube cntLike(){return generated(33,true);}
  private static RawEditCube generated(int size,boolean grade){
    if(size<2||size>MAX_SIZE)throw new IllegalArgumentException("Invalid cube size");float[] data=new float[size*size*size*3];int i=0;
    for(int b=0;b<size;b++)for(int g=0;g<size;g++)for(int r=0;r<size;r++){
      double[] c={r/(double)(size-1),g/(double)(size-1),b/(double)(size-1)};
      if(grade)c=RawEditCntCalibration.sample(c[0],c[1],c[2]);
      for(int k=0;k<3;k++)data[i++]=(float)c[k];
    }
    return new RawEditCube(size,new float[]{0,0,0},new float[]{1,1,1},data,grade?"Gallery CNT measured v1":"Identity");
  }
  public void write(DataOutput out)throws IOException {out.writeInt(size);out.writeUTF(title);for(float v:min)out.writeFloat(v);for(float v:max)out.writeFloat(v);for(float v:values)out.writeFloat(v);}
  public static RawEditCube read(DataInput in)throws IOException {int size=in.readInt();if(size<2||size>MAX_SIZE)throw new IOException("Invalid cube size");String title=in.readUTF();if(title.length()>256)throw new IOException("Invalid cube title");float[] min=new float[3],max=new float[3],values=new float[size*size*size*3];for(int c=0;c<3;c++)min[c]=in.readFloat();for(int c=0;c<3;c++)max[c]=in.readFloat();for(int i=0;i<values.length;i++)values[i]=in.readFloat();try{return new RawEditCube(size,min,max,values,title);}catch(IllegalArgumentException failure){throw new IOException("Invalid cube",failure);}}
  public boolean equals(Object other){if(this==other)return true;if(!(other instanceof RawEditCube))return false;RawEditCube c=(RawEditCube)other;return size==c.size&&title.equals(c.title)&&Arrays.equals(min,c.min)&&Arrays.equals(max,c.max)&&Arrays.equals(values,c.values);}
  public int hashCode(){return 31*(31*(31*size+Arrays.hashCode(min))+Arrays.hashCode(max))+Arrays.hashCode(values);}
}
