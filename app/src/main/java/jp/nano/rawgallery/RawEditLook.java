package jp.nano.rawgallery;

import java.io.*;
import java.nio.*;
import java.util.Arrays;
import java.util.Objects;

/** Optional display-referred look, separate from RAW development. No additional tone mapping. */
public final class RawEditLook {
  private final boolean tone,lut,grain,vignette;
  private final float mix,amount,shadows,vignetteStrength;
  private final int seed,basePreset;
  private final RawEditCurve[] curves;
  private final RawEditCube cube;
  private RawEditLook(boolean tone,boolean lut,boolean grain,float mix,float amount,float shadows,int seed,int basePreset,RawEditCurve[] curves,RawEditCube cube,boolean vignette,float vignetteStrength){
    if(!valid(vignetteStrength)||!valid(mix)||!valid(amount)||!valid(shadows)||basePreset<0||basePreset>1||curves==null||curves.length!=5)throw new IllegalArgumentException("Invalid look");for(RawEditCurve c:curves)Objects.requireNonNull(c);
    this.vignette=vignette;this.vignetteStrength=vignetteStrength==0?0:vignetteStrength;this.tone=tone;this.lut=lut;this.grain=grain;this.mix=mix==0?0:mix;this.amount=amount==0?0:amount;this.shadows=shadows==0?0:shadows;this.seed=seed;this.basePreset=basePreset;this.curves=curves.clone();this.cube=cube;
  }
  private static boolean valid(float v){return Float.isFinite(v)&&v>=0&&v<=1;}
  private static RawEditCurve[] identityCurves(){RawEditCurve[] c=new RawEditCurve[5];Arrays.fill(c,new RawEditCurve());return c;}
  public static RawEditLook neutral(){return new RawEditLook(false,false,false,1,0,1,65,0,identityCurves(),null,false,1);}
  private static final class Preset {
    static final RawEditLook CNT=new RawEditLook(true,true,false,1,.18f,.8f,65,1,identityCurves(),RawEditCube.cntLike(),true,1);
  }
  /** Measured CNT-like approximation; reference images are not embedded. */
  public static RawEditLook cntLike(){return Preset.CNT;}
  public boolean vignetteEnabled(){return vignette;} public float vignetteStrength(){return vignetteStrength;}
  public boolean toneEnabled(){return tone;} public boolean lutEnabled(){return lut;} public boolean grainEnabled(){return grain;}
  public float lutStrength(){return mix;} public float grainStrength(){return amount;} public float grainShadows(){return shadows;} public int seed(){return seed;}
  /** Immutable reset origin, retained through edits, undo/redo and persistence. */
  public int basePreset(){return basePreset;}
  public RawEditLook withBasePreset(int value){return new RawEditLook(tone,lut,grain,mix,amount,shadows,seed,value,curves,cube,vignette,vignetteStrength);}
  public RawEditCurve curve(int bank){return curves[bank];} public RawEditCube cube(){return cube;}
  public boolean hasTone(){if(!tone)return false;for(RawEditCurve c:curves)if(!c.identity())return true;return false;}
  public boolean active(){return hasTone()||lut&&cube!=null&&mix>0||grain&&amount>0||vignette&&vignetteStrength>0;}
  public RawEditLook withVignetteEnabled(boolean v){return new RawEditLook(tone,lut,grain,mix,amount,shadows,seed,basePreset,curves,cube,v,vignetteStrength);}
  public RawEditLook withVignetteStrength(float v){return new RawEditLook(tone,lut,grain,mix,amount,shadows,seed,basePreset,curves,cube,vignette,v);}
  public RawEditLook withToneEnabled(boolean v){return new RawEditLook(v,lut,grain,mix,amount,shadows,seed,basePreset,curves,cube,vignette,vignetteStrength);}
  public RawEditLook withLutEnabled(boolean v){return new RawEditLook(tone,v,grain,mix,amount,shadows,seed,basePreset,curves,cube,vignette,vignetteStrength);}
  public RawEditLook withGrainEnabled(boolean v){return new RawEditLook(tone,lut,v,mix,amount,shadows,seed,basePreset,curves,cube,vignette,vignetteStrength);}
  public RawEditLook withLutStrength(float v){return new RawEditLook(tone,lut,grain,v,amount,shadows,seed,basePreset,curves,cube,vignette,vignetteStrength);}
  public RawEditLook withGrainStrength(float v){return new RawEditLook(tone,lut,grain,mix,v,shadows,seed,basePreset,curves,cube,vignette,vignetteStrength);}
  public RawEditLook withGrainShadows(float v){return new RawEditLook(tone,lut,grain,mix,amount,v,seed,basePreset,curves,cube,vignette,vignetteStrength);}
  public RawEditLook withSeed(int v){return new RawEditLook(tone,lut,grain,mix,amount,shadows,v,basePreset,curves,cube,vignette,vignetteStrength);}
  public RawEditLook withCube(RawEditCube v){return new RawEditLook(tone,lut,grain,mix,amount,shadows,seed,basePreset,curves,v,vignette,vignetteStrength);}
  public RawEditLook withCurve(int bank,RawEditCurve v){RawEditCurve[] c=curves.clone();c[bank]=Objects.requireNonNull(v);return new RawEditLook(tone,lut,grain,mix,amount,shadows,seed,basePreset,c,cube,vignette,vignetteStrength);}
  public void write(DataOutput out)throws IOException {out.writeInt(6503);out.writeBoolean(tone);out.writeBoolean(lut);out.writeBoolean(grain);out.writeFloat(mix);out.writeFloat(amount);out.writeFloat(shadows);out.writeInt(seed);out.writeInt(basePreset);out.writeBoolean(vignette);out.writeFloat(vignetteStrength);for(RawEditCurve c:curves){float[] p=c.points();out.writeInt(p.length);for(float v:p)out.writeFloat(v);}out.writeBoolean(cube!=null);if(cube!=null)cube.write(out);}
  public static RawEditLook read(DataInput in)throws IOException {
    int format=in.readInt();if(format!=6501&&format!=6502&&format!=6503)throw new IOException("Invalid look recipe");boolean tone=in.readBoolean(),lut=in.readBoolean(),grain=in.readBoolean();float mix=in.readFloat(),amount=in.readFloat(),shadows=in.readFloat();int seed=in.readInt(),basePreset=format>=6502?in.readInt():0;boolean vignette=format>=6503&&in.readBoolean();float vignetteStrength=format>=6503?in.readFloat():1;RawEditCurve[] curves=new RawEditCurve[5];
    try {for(int k=0;k<5;k++){int n=in.readInt();if(n<4||n>64||n%2!=0)throw new IOException("Invalid curve length");float[] p=new float[n];for(int j=0;j<n;j++)p[j]=in.readFloat();curves[k]=new RawEditCurve(p);}RawEditCube cube=in.readBoolean()?RawEditCube.read(in):null;return new RawEditLook(tone,lut,grain,mix,amount,shadows,seed,basePreset,curves,cube,vignette,vignetteStrength);}catch(IllegalArgumentException failure){throw new IOException("Invalid look recipe",failure);}
  }
  public boolean equals(Object other){if(this==other)return true;if(!(other instanceof RawEditLook))return false;RawEditLook l=(RawEditLook)other;return tone==l.tone&&lut==l.lut&&grain==l.grain&&vignette==l.vignette&&vignetteStrength==l.vignetteStrength&&mix==l.mix&&amount==l.amount&&shadows==l.shadows&&seed==l.seed&&basePreset==l.basePreset&&Arrays.equals(curves,l.curves)&&Objects.equals(cube,l.cube);}
  public int hashCode(){return Objects.hash(tone,lut,grain,vignette,vignetteStrength,mix,amount,shadows,seed,basePreset,Arrays.hashCode(curves),cube);}
  /** CPU reference for nonspatial looks. Coordinates are continuous on the grain reference grid. */
  public double[] apply(double[] input,double x,double y){return apply(input,x,y,0,0);}
  /** Four subpixel samples approximate a pixel footprint for preview/export consistency. */
  public double[] apply(double[] input,double x,double y,double footprintX,double footprintY){
    if(vignette&&vignetteStrength>0)throw new IllegalStateException("Spatial look requires applyAt with full-image normalized position");
    return applyAt(input,x,y,footprintX,footprintY,.5,.5);
  }
  /** Position u/v is normalized to the entire upright image, independent of preview size or tile origin.
   * Grain x/y and footprint remain continuous coordinates on their common reference grid. */
  public double[] applyAt(double[] input,double x,double y,double u,double v){return applyAt(input,x,y,0,0,u,v);}
  public double[] applyAt(double[] input,double x,double y,double footprintX,double footprintY,double u,double v){
    if(!Double.isFinite(u)||!Double.isFinite(v)||u<0||u>1||v<0||v>1)throw new IllegalArgumentException("Expected full-image normalized position [0,1]");
    if(!Double.isFinite(footprintX)||!Double.isFinite(footprintY)||footprintX<0||footprintY<0)throw new IllegalArgumentException("Invalid grain footprint");
    if(input==null||input.length!=3||!Double.isFinite(x)||!Double.isFinite(y))throw new IllegalArgumentException("Invalid look input");double[] c=input.clone();for(double value:c)if(!Double.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Expected encoded sRGB [0,1]");
    if(hasTone()){
      if(!curves[0].identity()){double[] linear={RawEditTone.decode(c[0]),RawEditTone.decode(c[1]),RawEditTone.decode(c[2])};double[] lab=RawEditTone.lab(linear);lab[0]=curves[0].at((float)lab[0]);linear=RawEditTone.gamut(lab);for(int k=0;k<3;k++)c[k]=RawEditTone.encode(linear[k]);}
      for(int k=0;k<3;k++){if(!curves[1].identity())c[k]=curves[1].at((float)c[k]);if(!curves[k+2].identity())c[k]=curves[k+2].at((float)c[k]);}
    }
    if(lut&&cube!=null&&mix>0){double[] graded=cube.apply(c);for(int k=0;k<3;k++)c[k]+=mix*(graded[k]-c[k]);}
    if(vignette&&vignetteStrength>0){double r2=2*((u-.5)*(u-.5)+(v-.5)*(v-.5));double t=clamp((r2-.2605580157892769)/(1-.2605580157892769));double brightness=.2126*clamp(c[0])+.7152*clamp(c[1])+.0722*clamp(c[2]);double protect=smooth(.5093659906365791,1.19444653821375,brightness);double gain=1-vignetteStrength*.2999377522080069*Math.pow(t,1.3831209709068195)*(1-protect);for(int k=0;k<3;k++)c[k]*=gain;}
    // Bounded, common-channel field avoids random RGB/chroma noise; edge fade avoids clipping bias.
    if(grain&&amount>0){double l=clamp(.2126*c[0]+.7152*c[1]+.0722*c[2]);double weight=(1-shadows)+shadows*(1-smooth(.35,.95,l));double edge=Math.min(1,Math.min(l,1-l)*12);double n=filteredField(x,y,footprintX,footprintY,seed)*amount*.035*weight*edge;for(int k=0;k<3;k++)c[k]+=n;}
    for(int k=0;k<3;k++)c[k]=clamp(c[k]);return c;
  }
  private static double clamp(double v){return Math.max(0,Math.min(1,v));}
  private static double smooth(double a,double b,double x){double t=clamp((x-a)/(b-a));return t*t*(3-2*t);}
  private static int hash(int x,int y,int seed){int v=x*0x9e3779b9^y*0x85ebca6b^seed;v^=v>>>16;v*=0x7feb352d;v^=v>>>15;v*=0x846ca68b;return v^(v>>>16);}
  private static double random(int x,int y,int seed){return ((hash(x,y,seed)>>>8)+.5)/16777216.0-.5;}
  private static double node(int x,int y,int seed){return (random(x,y,seed)+random(x,y,seed^0x68bc21eb)+random(x,y,seed^0x02e5be93))*.6666666666666666;}
  public static double field(double x,double y,int seed){int ix=(int)Math.floor(x),iy=(int)Math.floor(y);double fx=x-ix,fy=y-iy;fx=fx*fx*(3-2*fx);fy=fy*fy*(3-2*fy);return (node(ix,iy,seed)*(1-fx)+node(ix+1,iy,seed)*fx)*(1-fy)+(node(ix,iy+1,seed)*(1-fx)+node(ix+1,iy+1,seed)*fx)*fy;}
  public static double filteredField(double x,double y,double dx,double dy,int seed){double a=dx*.25,b=dy*.25;return (field(x-a,y-b,seed)+field(x+a,y-b,seed)+field(x-a,y+b,seed)+field(x+a,y+b,seed))*.25;}
  static ByteBuffer curveData(RawEditLook look){ByteBuffer out=ByteBuffer.allocateDirect(1024*9*16*2).order(ByteOrder.nativeOrder());for(int bank=0;bank<5;bank++)for(int i=0;i<=8192;i++)out.putFloat((((bank/4)*9+i/1024)*1024+i%1024)*16+(bank%4)*4,look.curve(bank).at(i/8192f));return out;}
  static final String GLSL="""
    precision highp int;
    uniform highp sampler2D lookCurves;
    uniform highp sampler3D lookCube;
    uniform int lookMask;
    uniform int lookCurveMask;
    uniform vec3 cubeMin;
    uniform vec3 cubeMax;
    uniform float cubeMix;
    uniform float vignetteStrength;
    uniform float grainAmount;
    uniform float grainShadows;
    uniform highp uint grainSeed;
    uniform vec2 grainGrid;
    float lookLookup(float v,int bank){float q=clamp(v,0.,1.)*8192.;int i=min(8191,int(q)),channel=bank%4,offset=(bank/4)*9;float a=texelFetch(lookCurves,ivec2(i%1024,offset+i/1024),0)[channel];i++;float b=texelFetch(lookCurves,ivec2(i%1024,offset+i/1024),0)[channel];return mix(a,b,q-float(i-1));}
    vec3 cubeSample(vec3 c){ivec3 size=textureSize(lookCube,0);vec3 p=clamp((c-cubeMin)/(cubeMax-cubeMin),0.,1.)*vec3(size-1);ivec3 a=ivec3(floor(p)),b=min(a+1,size-1);vec3 f=fract(p);
      return mix(mix(mix(texelFetch(lookCube,a,0).rgb,texelFetch(lookCube,ivec3(b.x,a.y,a.z),0).rgb,f.x),mix(texelFetch(lookCube,ivec3(a.x,b.y,a.z),0).rgb,texelFetch(lookCube,ivec3(b.x,b.y,a.z),0).rgb,f.x),f.y),mix(mix(texelFetch(lookCube,ivec3(a.x,a.y,b.z),0).rgb,texelFetch(lookCube,ivec3(b.x,a.y,b.z),0).rgb,f.x),mix(texelFetch(lookCube,ivec3(a.x,b.y,b.z),0).rgb,texelFetch(lookCube,b,0).rgb,f.x),f.y),f.z);
    }
    uint grainHash(ivec2 p,uint seed){uint v=uint(p.x)*0x9e3779b9u^uint(p.y)*0x85ebca6bu^seed;v^=v>>16;v*=0x7feb352du;v^=v>>15;v*=0x846ca68bu;return v^(v>>16);}
    float grainRandom(ivec2 p,uint seed){return (float(grainHash(p,seed)>>8)+.5)/16777216.-.5;}
    float grainNode(ivec2 p){return (grainRandom(p,grainSeed)+grainRandom(p,grainSeed^0x68bc21ebu)+grainRandom(p,grainSeed^0x02e5be93u))*(2./3.);}
    float grainField(vec2 p){ivec2 a=ivec2(floor(p));vec2 f=fract(p);f=f*f*(3.-2.*f);return mix(mix(grainNode(a),grainNode(a+ivec2(1,0)),f.x),mix(grainNode(a+ivec2(0,1)),grainNode(a+ivec2(1,1)),f.x),f.y);}
    float grainFiltered(vec2 p){vec2 d=fwidth(p)*.25;return (grainField(p-d)+grainField(p+vec2(d.x,-d.y))+grainField(p+vec2(-d.x,d.y))+grainField(p+d))*.25;}
    vec3 applyLook(vec3 c,vec2 q){
      if((lookMask&1)!=0){if((lookCurveMask&1)!=0){vec3 lab=toLab(unSrgb(c));lab.x=lookLookup(lab.x,0);c=srgb(gamut(lab));}for(int k=0;k<3;k++){if((lookCurveMask&2)!=0)c[k]=lookLookup(c[k],1);if((lookCurveMask&(1<<(k+2)))!=0)c[k]=lookLookup(c[k],k+2);}}
      if((lookMask&2)!=0)c=mix(c,cubeSample(c),cubeMix);
      if((lookMask&8)!=0){vec2 d=q-vec2(.5);float r2=2.*dot(d,d);float t=clamp((r2-.2605580157892769)/(1.-.2605580157892769),0.,1.);float brightness=dot(clamp(c,0.,1.),vec3(.2126,.7152,.0722));float protect=smoothstep(.5093659906365791,1.19444653821375,brightness);c*=1.-vignetteStrength*.2999377522080069*pow(t,1.3831209709068195)*(1.-protect);}
      if((lookMask&4)!=0){float l=clamp(dot(c,vec3(.2126,.7152,.0722)),0.,1.);float weight=mix(1.,1.-smoothstep(.35,.95,l),grainShadows);float edge=min(1.,min(l,1.-l)*12.);c+=vec3(grainFiltered(q*grainGrid)*grainAmount*.035*weight*edge);}
      return c;
    }
    """;
}
