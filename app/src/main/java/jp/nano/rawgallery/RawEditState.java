package jp.nano.rawgallery;

import java.util.Arrays;

/** Immutable edit recipe. Old experimental snapshots are intentionally rejected. */
public final class RawEditState {
  public static final int TEMP=0,TINT=1,TEXTURE=2,CLARITY=3,DEHAZE=4,ROLLOFF=5,
      EXPOSURE=6,BRIGHTNESS=7,HIGHLIGHTS=8,SHADOWS=9,CONTRAST=10,WHITES=11,BLACKS=12,
      SATURATION=13,VIBRANCE=14,MONO=15,COUNT=16;
  public static final int LIGHTNESS=0,RGB=1,RED=2,GREEN=3,BLUE=4,CURVES=5;
  private static final float FORMAT=6302;
  private final float[] values; private final RawEditCurve[] curves;
  private final RawEditLook look;
  public RawEditState(){look=RawEditLook.neutral();values=new float[COUNT];values[ROLLOFF]=.5f;curves=new RawEditCurve[CURVES];Arrays.fill(curves,new RawEditCurve());}
  private RawEditState(float[] v,RawEditCurve[] c,RawEditLook l){values=v.clone();curves=c.clone();look=java.util.Objects.requireNonNull(l);validate();}
  public static RawEditState neutral(){return new RawEditState().with(ROLLOFF,0);}
  public RawEditLook look(){return look;}
  public RawEditState withLook(RawEditLook value){return new RawEditState(values,curves,value);}
  public RawEditState(float[] data){
    look=RawEditLook.neutral();
    if(data==null||data.length<1+COUNT+CURVES*5||data[0]!=FORMAT)throw new IllegalArgumentException("Invalid recipe format");
    values=Arrays.copyOfRange(data,1,1+COUNT);curves=new RawEditCurve[CURVES];int index=1+COUNT;
    for(int k=0;k<CURVES;k++){
      if(index>=data.length||!Float.isFinite(data[index])||data[index]!=(int)data[index]||data[index]<2||data[index]>RawEditCurve.MAX_POINTS)throw new IllegalArgumentException("Invalid recipe curve");
      int length=(int)data[index++]*2;if(index+length>data.length)throw new IllegalArgumentException("Truncated recipe");
      curves[k]=new RawEditCurve(Arrays.copyOfRange(data,index,index+length));index+=length;
    }
    if(index!=data.length)throw new IllegalArgumentException("Extra recipe data");validate();
  }
  private void validate(){for(int i=0;i<COUNT;i++)if(!Float.isFinite(values[i])||values[i]<min(i)||values[i]>max(i)||(i==MONO&&values[i]!=0&&values[i]!=1))throw new IllegalArgumentException("Invalid edit value");}
  public float get(int key){return values[key];}
  public float[] parameters(){return values.clone();}
  public RawEditCurve curve(int bank){return curves[bank];}
  public RawEditState withCurve(int bank,RawEditCurve curve){if(curve==null)throw new IllegalArgumentException("Missing curve");RawEditCurve[] c=curves.clone();c[bank]=curve;return new RawEditState(values,c,look);}
  /** Legacy development-only snapshot; persist look() separately (external tables can exceed Bundle limits). */
  public float[] snapshot(){
    int n=1+COUNT;for(RawEditCurve c:curves)n+=1+c.size()*2;
    float[] out=new float[n];out[0]=FORMAT;System.arraycopy(values,0,out,1,COUNT);int i=1+COUNT;
    for(RawEditCurve c:curves){out[i++]=c.size();float[] p=c.points();System.arraycopy(p,0,out,i,p.length);i+=p.length;}return out;
  }
  public RawEditState with(int key,float value){if(!Float.isFinite(value))throw new IllegalArgumentException("Non-finite edit value");float[] v=values.clone();v[key]=key==MONO?(value>=.5f?1:0):Math.max(min(key),Math.min(max(key),value));return new RawEditState(v,curves,look);}
  public RawEditState pair(int x,int y,float xv,float yv){return with(x,xv).with(y,yv);}
  public RawEditState reset(int... keys){RawEditState r=this,z=new RawEditState();for(int key:keys)r=r.with(key,z.get(key));return r;}
  public RawEditState resetCurves(){RawEditState r=this;for(int i=0;i<CURVES;i++)r=r.withCurve(i,new RawEditCurve());return r.with(ROLLOFF,.5f);}
  public static float min(int key){if(key<0||key>=COUNT)throw new IllegalArgumentException("Invalid edit key");return key==MONO||key==ROLLOFF?0:key==EXPOSURE?-5:-1;}
  public static float max(int key){min(key);return key==EXPOSURE?5:1;}
  public boolean equals(Object o){return o instanceof RawEditState&&Arrays.equals(values,((RawEditState)o).values)&&Arrays.equals(curves,((RawEditState)o).curves)&&look.equals(((RawEditState)o).look);}
  public int hashCode(){return 31*(31*Arrays.hashCode(values)+Arrays.hashCode(curves))+look.hashCode();}
}
