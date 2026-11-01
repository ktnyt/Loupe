package jp.nano.rawgallery;

import java.nio.*;

/** Shared curve sampling and CPU reference for the single global tone pipeline. */
final class RawEditTone {
  static final int STEPS=8192,LUT_WIDTH=1024,LUT_ROWS=9,LUT_HEIGHT=18;
  static RawEditCurve basic(RawEditState s){
    float b=s.get(RawEditState.BLACKS),w=s.get(RawEditState.WHITES),br=s.get(RawEditState.BRIGHTNESS),c=s.get(RawEditState.CONTRAST);
    float x0=Math.max(0,-b*.12f),y0=Math.max(0,b*.12f),x1=1-Math.max(0,w*.15f),y1=1+Math.min(0,w*.15f);
    float[] p={x0,y0,.2f,.2f+s.get(RawEditState.SHADOWS)*.1f+br*.1f-c*.12f,.5f,.5f+br*.16f,.8f,.8f+s.get(RawEditState.HIGHLIGHTS)*.1f+br*.1f+c*.12f,x1,y1};
    for(int i=3;i<8;i+=2)p[i]=Math.max(p[i-2],Math.min(y1,p[i]));return new RawEditCurve(p);
  }
  static boolean sameLut(RawEditState a,RawEditState b){if(a==null)return false;for(int i=RawEditState.BRIGHTNESS;i<=RawEditState.BLACKS;i++)if(a.get(i)!=b.get(i))return false;for(int i=0;i<5;i++)if(!a.curve(i).equals(b.curve(i)))return false;return true;}
  static ByteBuffer lut(RawEditState s){
    ByteBuffer data=ByteBuffer.allocateDirect(LUT_WIDTH*LUT_HEIGHT*16).order(ByteOrder.nativeOrder());updateLut(s,null,data);return data;
  }
  static void updateLut(RawEditState s,RawEditState previous,ByteBuffer data){
    RawEditCurve[] curves={basic(s),s.curve(0),s.curve(1),s.curve(2),s.curve(3),s.curve(4)};
    for(int k=0;k<curves.length;k++){
      RawEditCurve old=previous==null?null:k==0?basic(previous):previous.curve(k-1);
      if(curves[k].equals(old))continue;
      for(int i=0;i<=STEPS;i++)data.putFloat((((k/4)*LUT_ROWS+i/LUT_WIDTH)*LUT_WIDTH+i%LUT_WIDTH)*16+(k%4)*4,curves[k].at(i/(float)STEPS));
    }
  }
  static double shoulder(double x,double rolloff){x=Math.max(0,x);if(rolloff==0)return Math.min(1,x);double k=1-.3*rolloff;return x<=k?x:k+(1-k)*(-Math.expm1(-(x-k)/(1-k)));}
  static double encode(double x){return x<=.0031308?12.92*x:1.055*Math.pow(x,1/2.4)-.055;}
  static double decode(double x){return x<=.04045?x/12.92:Math.pow((x+.055)/1.055,2.4);}
  static double[] lab(double[] c){
    double l=Math.cbrt(.4122214708*c[0]+.5363325363*c[1]+.0514459929*c[2]);
    double m=Math.cbrt(.2119034982*c[0]+.6806995451*c[1]+.1073969566*c[2]);
    double s=Math.cbrt(.0883024619*c[0]+.2817188376*c[1]+.6299787005*c[2]);
    return new double[]{.2104542553*l+.793617785*m-.0040720468*s,1.9779984951*l-2.428592205*m+.4505937099*s,.0259040371*l+.7827717662*m-.808675766*s};
  }
  static double[] rgb(double[] c){
    double l=c[0]+.3963377774*c[1]+.2158037573*c[2],m=c[0]-.1055613458*c[1]-.0638541728*c[2],s=c[0]-.0894841775*c[1]-1.291485548*c[2];l=l*l*l;m=m*m*m;s=s*s*s;
    return new double[]{4.0767416621*l-3.3077115913*m+.2309699292*s,-1.2684380046*l+2.6097574011*m-.3413193965*s,-.0041960863*l-.7034186147*m+1.707614701*s};
  }
  static boolean inGamut(double[] c){for(double v:c)if(v<0||v>1)return false;return true;}
  static double[] gamut(double[] c){
    c=c.clone();c[0]=Math.max(0,Math.min(1,c[0]));double[] out=rgb(c);boolean near=true;for(double v:out)near&=v>=-2e-6&&v<=1+2e-6;if(near){for(int i=0;i<3;i++)out[i]=Math.max(0,Math.min(1,out[i]));return out;}
    double lo=0,hi=1;for(int i=0;i<16;i++){double t=(lo+hi)/2;out=rgb(new double[]{c[0],c[1]*t,c[2]*t});if(inGamut(out))lo=t;else hi=t;}
    return rgb(new double[]{c[0],c[1]*lo,c[2]*lo});
  }
  /** Global reference starts at WB/exposure-adjusted linear RGB, after optional spatial details. */
  static double[] apply(RawEditState state,double[] input){
    double[] c=lab(input);c[0]=basic(state).at((float)shoulder(c[0],state.get(RawEditState.ROLLOFF)));
    if(!state.curve(0).identity())c[0]=state.curve(0).at((float)c[0]);
    boolean rgbCurves=false;for(int i=1;i<5;i++)rgbCurves|=!state.curve(i).identity();
    if(rgbCurves){
      c=rgb(c);
      for(int i=0;i<3;i++)if(!state.curve(1).identity()||!state.curve(i+2).identity()){
        double v=encode(c[i]);if(!state.curve(1).identity())v=state.curve(1).at((float)v);if(!state.curve(i+2).identity())v=state.curve(i+2).at((float)v);c[i]=decode(v);
      }
      c=lab(c);
    }
    double chroma=Math.hypot(c[1],c[2]);double sat=(1+state.get(RawEditState.SATURATION))*(1+state.get(RawEditState.VIBRANCE)*(1-Math.min(1,chroma*4))*.8)*(1+state.get(RawEditState.DEHAZE)*.12);c[1]*=sat;c[2]*=sat;
    c=gamut(c);for(int i=0;i<3;i++)c[i]=Math.max(0,Math.min(1,encode(c[i])));
    if(state.get(RawEditState.MONO)==1){double l=Math.max(0,Math.min(1,.2126*c[0]+.7152*c[1]+.0722*c[2]+.02)),mc=(1-Math.abs(2*l-1))*.11;c=new double[]{mc/3+l-mc*.5,l-mc*.5,mc+l-mc*.5};}
    return c;
  }
  static final String GLSL="""
    uniform highp sampler2D toneLut;
    uniform int curveMask;
    float lookup(float v,int bank){
      float q=clamp(v,0.,1.)*8192.;int i=min(8191,int(q));int channel=bank%4,offset=(bank/4)*9;
      float a=texelFetch(toneLut,ivec2(i%1024,offset+i/1024),0)[channel];i++;
      float b=texelFetch(toneLut,ivec2(i%1024,offset+i/1024),0)[channel];return mix(a,b,q-float(i-1));
    }
    vec3 srgb(vec3 c){return mix(12.92*c,1.055*pow(max(c,vec3(0)),vec3(1./2.4))-.055,step(vec3(.0031308),c));}
    vec3 unSrgb(vec3 c){return mix(c/12.92,pow(max((c+.055)/1.055,vec3(0)),vec3(2.4)),step(vec3(.04045),c));}
    vec3 toLab(vec3 c){
      vec3 lms=vec3(dot(c,vec3(.4122214708,.5363325363,.0514459929)),dot(c,vec3(.2119034982,.6806995451,.1073969566)),dot(c,vec3(.0883024619,.2817188376,.6299787005)));
      lms=sign(lms)*pow(abs(lms),vec3(1./3.));
      return vec3(dot(lms,vec3(.2104542553,.793617785,-.0040720468)),dot(lms,vec3(1.9779984951,-2.428592205,.4505937099)),dot(lms,vec3(.0259040371,.7827717662,-.808675766)));
    }
    vec3 fromLab(vec3 c){
      vec3 lms=vec3(c.x+.3963377774*c.y+.2158037573*c.z,c.x-.1055613458*c.y-.0638541728*c.z,c.x-.0894841775*c.y-1.291485548*c.z);lms=lms*lms*lms;
      return vec3(dot(lms,vec3(4.0767416621,-3.3077115913,.2309699292)),dot(lms,vec3(-1.2684380046,2.6097574011,-.3413193965)),dot(lms,vec3(-.0041960863,-.7034186147,1.707614701)));
    }
    bool inGamut(vec3 c){return all(greaterThanEqual(c,vec3(0)))&&all(lessThanEqual(c,vec3(1)));}
    vec3 gamut(vec3 lab){
      lab.x=clamp(lab.x,0.,1.);vec3 c=fromLab(lab);if(all(greaterThanEqual(c,vec3(-.000002)))&&all(lessThanEqual(c,vec3(1.000002))))return clamp(c,0.,1.);
      float lo=0.,hi=1.;for(int i=0;i<16;i++){float t=(lo+hi)*.5;if(inGamut(fromLab(vec3(lab.x,lab.yz*t))))lo=t;else hi=t;}
      return fromLab(vec3(lab.x,lab.yz*lo));
    }
    float shoulder(float x,float r){x=max(0.,x);if(r==0.)return min(1.,x);float k=1.-.3*r;return x<=k?x:k+(1.-k)*(1.-exp(-(x-k)/(1.-k)));}
    vec3 globalTone(vec3 c){
      vec3 lab=toLab(c);lab.x=lookup(shoulder(lab.x,p[5]),0);if((curveMask&1)!=0)lab.x=lookup(lab.x,1);
      if((curveMask&30)!=0){vec3 v=srgb(fromLab(lab));for(int i=0;i<3;i++){if((curveMask&2)!=0)v[i]=lookup(v[i],2);if((curveMask&(1<<(i+2)))!=0)v[i]=lookup(v[i],i+3);}lab=toLab(unSrgb(v));}
      float sat=(1.+p[13])*(1.+p[14]*(1.-min(1.,length(lab.yz)*4.))*.8)*(1.+p[4]*.12);if(mode==2)sat=4.;lab.yz*=sat;
      c=srgb(gamut(lab));
      if(p[15]>.5){float l=clamp(dot(c,vec3(.2126,.7152,.0722))+.02,0.,1.);float mc=(1.-abs(2.*l-1.))*.11;c=vec3(mc/3.,0.,mc)+l-mc*.5;}
      return c;
    }
    """;
}
