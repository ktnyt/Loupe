package jp.nano.rawgallery;

import java.util.Arrays;

/** Immutable experimental edit values. Transient comparison/MAX display never enter this model. */
public final class RawEditState {
  public static final int TEMP=0,TINT=1,TEXTURE=2,CLARITY=3,DEHAZE=4,CURVE_X=5,CURVE_Y=6,
      EXPOSURE=7,BRIGHTNESS=8,HIGHLIGHTS=9,SHADOWS=10,CONTRAST=11,WHITES=12,BLACKS=13,
      SATURATION=14,VIBRANCE=15,MONO=16,COUNT=17;
  private final float[] values;
  public RawEditState() { values=new float[COUNT];values[CURVE_X]=values[CURVE_Y]=.5f; }
  public RawEditState(float[] input) {
    if(input==null||(input.length!=COUNT&&input.length!=16))throw new IllegalArgumentException("Invalid edit state");
    values=Arrays.copyOf(input,COUNT);
    for(int i=0;i<COUNT;i++) {
      if(!Float.isFinite(values[i])||values[i]<min(i)||values[i]>max(i)||(i==MONO&&values[i]!=0&&values[i]!=1))
        throw new IllegalArgumentException("Invalid edit value");
    }
  }
  public float get(int key) { return values[key]; }
  public float[] snapshot() { return values.clone(); }
  public RawEditState with(int key,float value) {
    if(!Float.isFinite(value))throw new IllegalArgumentException("Non-finite edit value");
    float[] next=values.clone();next[key]=key==MONO?(value>=.5f?1:0):Math.max(min(key),Math.min(max(key),value));
    return new RawEditState(next);
  }
  public RawEditState pair(int x,int y,float xv,float yv) {return with(x,xv).with(y,yv);}
  public RawEditState reset(int... keys) {
    RawEditState result=this,zero=new RawEditState();
    for(int key:keys)result=result.with(key,zero.get(key));
    return result;
  }
  public static float min(int key) {
    if(key<0||key>=COUNT)throw new IllegalArgumentException("Invalid edit key");
    return key==MONO?0:key==CURVE_X?.05f:key==CURVE_Y?0f:key==EXPOSURE?-5f:-1f;
  }
  public static float max(int key) { min(key);return key==CURVE_X?.95f:key==EXPOSURE?5f:1f; }
  public boolean equals(Object o){return o instanceof RawEditState&&Arrays.equals(values,((RawEditState)o).values);}
  public int hashCode(){return Arrays.hashCode(values);}
  /** Monotone cubic curve through (0,0), a movable point, and (1,1). */
  public float curve(float value) {
    float x=get(CURVE_X),y=get(CURVE_Y),a=y/x,b=(1-y)/(1-x);
    float m=Math.min(3*Math.min(a,b),2*a*b/(a+b));
    float t,h,u,v,du,dv;
    if(value<=x){t=Math.max(0,value)/x;h=x;u=0;v=y;du=a;dv=m;}
    else {t=(Math.min(1,value)-x)/(1-x);h=1-x;u=y;v=1;du=m;dv=b;}
    return (2*t*t*t-3*t*t+1)*u+(t*t*t-2*t*t+t)*h*du
        +(-2*t*t*t+3*t*t)*v+(t*t*t-t*t)*h*dv;
  }
}
