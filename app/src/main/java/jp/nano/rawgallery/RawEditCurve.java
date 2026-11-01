package jp.nano.rawgallery;

import java.util.Arrays;

/** Immutable monotone PCHIP. Knots are ordered, separated by GAP, and bounded in [0,1]. */
public final class RawEditCurve {
  public static final float GAP=1f/128; public static final int MAX_POINTS=32;
  private final float[] xy; private final double[] slopes;
  public RawEditCurve(){this(new float[]{0,0,1,1});}
  public RawEditCurve(float[] points){
    if(points==null||points.length<4||points.length>MAX_POINTS*2||points.length%2!=0)throw new IllegalArgumentException("Invalid curve points");
    xy=points.clone();int n=size();double[] h=new double[n-1],d=new double[n-1];
    for(int i=0;i<n;i++){
      float x=x(i),y=y(i);
      if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||x>1||y<0||y>1
          ||i>0&&(x-x(i-1)<GAP-1e-7f||y<y(i-1)))throw new IllegalArgumentException("Invalid curve order");
      if(i>0){h[i-1]=x-x(i-1);d[i-1]=(y-y(i-1))/h[i-1];}
    }
    slopes=new double[n];
    if(n==2){slopes[0]=slopes[1]=d[0];return;}
    for(int i=1;i<n-1;i++)if(d[i-1]>0&&d[i]>0){double w1=2*h[i]+h[i-1],w2=h[i]+2*h[i-1];slopes[i]=(w1+w2)/(w1/d[i-1]+w2/d[i]);}
    slopes[0]=end(h[0],h[1],d[0],d[1]);slopes[n-1]=end(h[n-2],h[n-3],d[n-2],d[n-3]);
  }
  private static double end(double h,double h2,double d,double d2){double m=((2*h+h2)*d-h*d2)/(h+h2);return m*d<=0?0:Math.min(m,3*d);}
  public int size(){return xy.length/2;}
  public float x(int i){return xy[i*2];} public float y(int i){return xy[i*2+1];}
  public float[] points(){return xy.clone();}
  public boolean identity(){return size()==2&&x(0)==0&&y(0)==0&&x(1)==1&&y(1)==1;}
  public float at(float value){
    if(!Float.isFinite(value))throw new IllegalArgumentException("Non-finite input");
    if(value<=x(0))return y(0);int last=size()-1;if(value>=x(last))return y(last);
    int i=0;while(value>x(i+1))i++;
    double h=x(i+1)-x(i),t=(value-x(i))/h,t2=t*t,t3=t2*t;
    return (float)((2*t3-3*t2+1)*y(i)+(t3-2*t2+t)*h*slopes[i]+(-2*t3+3*t2)*y(i+1)+(t3-t2)*h*slopes[i+1]);
  }
  public RawEditCurve move(int i,float x,float y){
    if(i<0||i>=size()||!Float.isFinite(x)||!Float.isFinite(y))throw new IllegalArgumentException("Invalid curve point");
    float[] p=points();p[i*2]=Math.max(i==0?0:x(i-1)+GAP,Math.min(i==size()-1?1:x(i+1)-GAP,x));
    p[i*2+1]=Math.max(i==0?0:y(i-1),Math.min(i==size()-1?1:y(i+1),y));return new RawEditCurve(p);
  }
  public RawEditCurve add(float x,float y){
    if(!Float.isFinite(x)||!Float.isFinite(y))throw new IllegalArgumentException("Invalid curve point");
    if(size()==MAX_POINTS||x<x(0)+GAP||x>x(size()-1)-GAP)return this;
    int i=1;while(x>x(i))i++;
    if(x-x(i-1)<GAP||x(i)-x<GAP)return this;
    float[] p=new float[xy.length+2];System.arraycopy(xy,0,p,0,i*2);p[i*2]=x;p[i*2+1]=Math.max(y(i-1),Math.min(y(i),y));System.arraycopy(xy,i*2,p,i*2+2,xy.length-i*2);return new RawEditCurve(p);
  }
  public RawEditCurve delete(int i){if(i<=0||i>=size()-1)return this;float[] p=new float[xy.length-2];System.arraycopy(xy,0,p,0,i*2);System.arraycopy(xy,i*2+2,p,i*2,xy.length-i*2-2);return new RawEditCurve(p);}
  public boolean equals(Object o){return o instanceof RawEditCurve&&Arrays.equals(xy,((RawEditCurve)o).xy);}
  public int hashCode(){return Arrays.hashCode(xy);}
}
