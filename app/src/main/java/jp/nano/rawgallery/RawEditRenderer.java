package jp.nano.rawgallery;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.opengl.*;
import java.io.IOException;
import java.nio.*;

/** Single-worker EGL renderer. Uses the same source-pixel radii and shader for preview and export. */
final class RawEditRenderer implements AutoCloseable {
  private static final Object DISPLAY_LOCK=new Object();
  private static int displayUsers;
  private boolean ownsDisplay;
  private EGLDisplay display=EGL14.EGL_NO_DISPLAY;
  private EGLContext context=EGL14.EGL_NO_CONTEXT;
  private EGLSurface surface=EGL14.EGL_NO_SURFACE;
  private int program,source,output,fbo,width,height,outputWidth,outputHeight;
  private ByteBuffer readback;
  private int maxTextureSize,uploadWidth,uploadHeight;
  private int grainWidth,grainHeight;
  private final FloatBuffer vertices=ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder())
      .asFloatBuffer().put(new float[]{-1,-1,1,-1,-1,1,1,1});
  private int lutTexture,lookCurveTexture,lookCubeTexture;
  private RawEditLook lookCurvesState;
  private RawEditCube uploadedCube;
  private static final RawEditCube IDENTITY_CUBE=RawEditCube.identity(2);
  private RawEditState lutState;
  private ByteBuffer lutData;
  private final String fragment=
      "#version 300 es\nprecision highp float;\nin vec2 uv;out vec4 result;\n"+
      "uniform highp sampler2D photo;uniform vec2 pixel;uniform vec4 sourceRect;uniform vec4 outputRect;uniform float p[16];uniform int mode;\n"+
      RawEditTone.GLSL+RawEditLook.GLSL+
      "float lum(vec3 c){return dot(c,vec3(.2126,.7152,.0722));}\n"+
      "vec3 linearRGB(vec2 q){vec2 v=(clamp(q,vec2(0),vec2(1))-sourceRect.xy)/sourceRect.zw;ivec2 sz=textureSize(photo,0);vec2 z=clamp(v*vec2(sz)-.5,vec2(0),vec2(sz-1));ivec2 a=ivec2(floor(z)),b=min(a+1,sz-1);vec2 f=fract(z);return mix(mix(texelFetch(photo,a,0).rgb,texelFetch(photo,ivec2(b.x,a.y),0).rgb,f.x),mix(texelFetch(photo,ivec2(a.x,b.y),0).rgb,texelFetch(photo,b,0).rgb,f.x),f.y); }\n"+
      "vec3 sampleRGB(vec2 q){return linearRGB(q)*exp2(vec3(p[0]*.6+p[1]*.12,-p[1]*.24,-p[0]*.6+p[1]*.12)+p[6]);}\n"+
      "vec3 blur(vec2 q,float radius){vec3 c=vec3(0);float total=0.;for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){float w=(x==0?2.:1.)*(y==0?2.:1.);c+=sampleRGB(q+vec2(float(x),float(y))*pixel*radius)*w;total+=w;}return c/total;}\n"+
      "float noise(vec2 q){const int b[16]=int[16](0,8,2,10,12,4,14,6,3,11,1,9,15,7,13,5);ivec2 v=ivec2(floor(q/pixel));return ((float(b[(v.y&3)*4+(v.x&3)])+.5)/16.-.5)/255.;}\n"+
      "void main(){vec2 q=outputRect.xy+uv*outputRect.zw;if(mode==1){result=vec4(clamp(srgb(linearRGB(q)),0.,1.),1.);return;}vec3 c=sampleRGB(q);float l=clamp(lum(c),0.,1.);"+
      "if(abs(p[2])>.00001)c+=p[2]*.8*(c-blur(q,2.));if(abs(p[3])>.00001)c+=vec3(p[3]*.7*(l-lum(blur(q,20.)))*4.*l*(1.-l));c=(c-vec3(.06*p[4]))/(1.-.12*p[4]);result=vec4(clamp(applyLook(globalTone(c),q)+noise(q),0.,1.),1.); }\n";

  RawEditRenderer() throws IOException {this(0,0,null);}

  RawEditRenderer(int w,int h,ByteBuffer rgba) throws IOException {
    width=w;height=h;grainWidth=w;grainHeight=h;vertices.position(0);
    try {
      display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
      synchronized(DISPLAY_LOCK){
        int[] version=new int[2];
        if(displayUsers==0&&!EGL14.eglInitialize(display,version,0,version,1))
          throw new IOException("画像処理を初期化できません");
        displayUsers++;ownsDisplay=true;
      }
      EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
      int[] attributes={EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
          EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE};
      if(!EGL14.eglChooseConfig(display,attributes,0,configs,0,1,count,0)||count[0]==0)
        throw new IOException("この端末の画像処理に対応していません");
      context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
          new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
      surface=EGL14.eglCreatePbufferSurface(display,configs[0],
          new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
      if(!EGL14.eglMakeCurrent(display,surface,surface,context))throw new IOException("画像処理を開始できません");
      int vs=compile(GLES30.GL_VERTEX_SHADER,"#version 300 es\nin vec2 position;out vec2 uv;void main(){gl_Position=vec4(position,0,1);uv=position*.5+.5;}");
      int fs=compile(GLES30.GL_FRAGMENT_SHADER,fragment);
      program=GLES30.glCreateProgram();GLES30.glAttachShader(program,vs);GLES30.glAttachShader(program,fs);
      GLES30.glLinkProgram(program);GLES30.glDeleteShader(vs);GLES30.glDeleteShader(fs);
      int[] ok=new int[1];GLES30.glGetProgramiv(program,GLES30.GL_LINK_STATUS,ok,0);
      if(ok[0]==0)throw new IOException("画像処理の構成に失敗しました");
      int[] ids=new int[5];GLES30.glGenTextures(5,ids,0);source=ids[0];output=ids[1];lutTexture=ids[2];lookCurveTexture=ids[3];lookCubeTexture=ids[4];
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,source);textureOptions();
      int[] limit=new int[1];GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE,limit,0);maxTextureSize=limit[0];
      if(rgba!=null)upload(w,h,rgba);
      GLES30.glGenFramebuffers(1,ids,0);fbo=ids[0];
      check();
    } catch(IOException|RuntimeException failure) {close();throw failure;}
  }
  private static int compile(int type,String text)throws IOException {
    int shader=GLES30.glCreateShader(type);GLES30.glShaderSource(shader,text);GLES30.glCompileShader(shader);
    int[] ok=new int[1];GLES30.glGetShaderiv(shader,GLES30.GL_COMPILE_STATUS,ok,0);
    if(ok[0]==0){GLES30.glDeleteShader(shader);throw new IOException("画像処理の準備に失敗しました");}
    return shader;
  }
  private static void textureOptions(){
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST);
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST);
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
  }
  private void makeCurrent()throws IOException {
    if(display==EGL14.EGL_NO_DISPLAY||!EGL14.eglMakeCurrent(display,surface,surface,context))
      throw new IOException("画像処理を開始できません");
  }
  private void upload(int w,int h,ByteBuffer rgba)throws IOException {
    makeCurrent();
    if(w<1||h<1||w>maxTextureSize||h>maxTextureSize||w>2048||h>2048)throw new IOException("画像の範囲が大きすぎます");
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,source);textureOptions();rgba.position(0);
    if(w!=uploadWidth||h!=uploadHeight){
      GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA32F,w,h,0,GLES30.GL_RGBA,GLES30.GL_FLOAT,rgba);
      uploadWidth=w;uploadHeight=h;
    }else GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D,0,0,0,w,h,GLES30.GL_RGBA,GLES30.GL_FLOAT,rgba);
    check();
  }
  /** Set oriented original dimensions before the initial preview to keep its grain grid stable. */
  void grainDimensions(int w,int h){if(w<1||h<1)throw new IllegalArgumentException("Invalid grain dimensions");grainWidth=w;grainHeight=h;}
  Bitmap render(RawEditState state,boolean before,boolean maxSaturation,int maxSide)throws IOException {
    float scale=Math.min(1,maxSide/(float)Math.max(width,height));
    int w=Math.max(1,Math.round(width*scale)),h=Math.max(1,Math.round(height*scale));
    return bitmap(draw(state,before,maxSaturation,w,h,new RectF(0,0,1,1),new RectF(0,0,1,1),1f/width,1f/height,grainWidth,grainHeight),w,h);
  }
  ByteBuffer renderRegionBytes(RawEditFullSource full,RawEditState state,boolean before,boolean maxSaturation,
      RectF region,int w,int h)throws IOException {
    if(region==null||!Float.isFinite(region.left)||!Float.isFinite(region.top)||!Float.isFinite(region.right)
        ||!Float.isFinite(region.bottom)||region.width()<=0||region.height()<=0||region.left<0||region.top<0
        ||region.right>full.width||region.bottom>full.height||w<1||h<1||w>512||h>512)
      throw new IOException("画像の範囲が不正です");
    float ref=Math.min(2048,Math.max(full.width,full.height));
    float radius=20f*Math.max(full.width,full.height)/ref;
    int halo=(int)Math.ceil(radius)+1;
    Rect input=new Rect(Math.max(0,(int)Math.floor(region.left)-halo),Math.max(0,(int)Math.floor(region.top)-halo),
        Math.min(full.width,(int)Math.ceil(region.right)+halo),Math.min(full.height,(int)Math.ceil(region.bottom)+halo));
    upload(input.width(),input.height(),full.region(input));
    RectF sourceBounds=new RectF(input.left/(float)full.width,input.top/(float)full.height,
        input.right/(float)full.width,input.bottom/(float)full.height);
    RectF outputBounds=new RectF(region.left/full.width,region.top/full.height,region.right/full.width,region.bottom/full.height);
    float factor=Math.max(full.width,full.height)/ref;
    return draw(state,before,maxSaturation,w,h,sourceBounds,outputBounds,factor/full.width,factor/full.height,full.width,full.height);
  }
  Bitmap renderRegionBitmap(RawEditFullSource full,RawEditState state,boolean before,boolean maxSaturation,
      RectF region,int w,int h)throws IOException {
    return bitmap(renderRegionBytes(full,state,before,maxSaturation,region,w,h),w,h);
  }
  private static Bitmap bitmap(ByteBuffer bytes,int w,int h){
    Bitmap image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);bytes.position(0);image.copyPixelsFromBuffer(bytes);return image;
  }
  private ByteBuffer draw(RawEditState state,boolean before,boolean maxSaturation,int w,int h,
      RectF sourceBounds,RectF outputBounds,float pixelX,float pixelY,int grainW,int grainH)throws IOException {
    if(Thread.currentThread().isInterrupted())throw new IOException("画像処理を中止しました");
    makeCurrent();
    if(w<1||h<1||w>maxTextureSize||h>maxTextureSize)throw new IOException("画像の範囲が大きすぎます");
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,output);textureOptions();
    if(w!=outputWidth||h!=outputHeight){
      GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA8,w,h,0,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,null);
      outputWidth=w;outputHeight=h;
      if(readback==null||readback.capacity()<w*h*4)readback=ByteBuffer.allocateDirect(w*h*4).order(ByteOrder.nativeOrder());
    }
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo);
    GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,output,0);
    if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)
      throw new IOException("画像の描画先を作れません");
    GLES30.glViewport(0,0,w,h);GLES30.glUseProgram(program);
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,source);
    GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"photo"),0);
    GLES30.glUniform2f(GLES30.glGetUniformLocation(program,"pixel"),pixelX,pixelY);
    GLES30.glUniform4f(GLES30.glGetUniformLocation(program,"sourceRect"),sourceBounds.left,sourceBounds.top,sourceBounds.width(),sourceBounds.height());
    GLES30.glUniform4f(GLES30.glGetUniformLocation(program,"outputRect"),outputBounds.left,outputBounds.top,outputBounds.width(),outputBounds.height());
    GLES30.glUniform1fv(GLES30.glGetUniformLocation(program,"p[0]"),RawEditState.COUNT,state.parameters(),0);
    GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"mode"),before?1:maxSaturation?2:0);
    GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,lutTexture);textureOptions();
    long lutNs=0,uploadNs=0;
    if(!RawEditTone.sameLut(lutState,state)){
      long begin=android.os.SystemClock.elapsedRealtimeNanos();
      if(lutData==null)lutData=RawEditTone.lut(state);else RawEditTone.updateLut(state,lutState,lutData);
      lutNs=android.os.SystemClock.elapsedRealtimeNanos()-begin;begin=android.os.SystemClock.elapsedRealtimeNanos();
      GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA32F,RawEditTone.LUT_WIDTH,RawEditTone.LUT_HEIGHT,0,GLES30.GL_RGBA,GLES30.GL_FLOAT,lutData);
      uploadNs=android.os.SystemClock.elapsedRealtimeNanos()-begin;lutState=state;
    }
    GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"toneLut"),1);
    int mask=0;for(int i=0;i<5;i++)if(!state.curve(i).identity())mask|=1<<i;
    GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"curveMask"),mask);
    configureLook(state.look());
    GLES30.glUniform2f(GLES30.glGetUniformLocation(program,"grainGrid"),2048f*grainW/Math.max(grainW,grainH),2048f*grainH/Math.max(grainW,grainH));
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
    int pos=GLES30.glGetAttribLocation(program,"position");vertices.position(0);
    GLES30.glEnableVertexAttribArray(pos);GLES30.glVertexAttribPointer(pos,2,GLES30.GL_FLOAT,false,0,vertices);
    long drawBegin=android.os.SystemClock.elapsedRealtimeNanos();
    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP,0,4);
    ByteBuffer bytes=readback;bytes.clear();
    GLES30.glReadPixels(0,0,w,h,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,bytes);check();
    RawEditLog.stages(lutNs,uploadNs,android.os.SystemClock.elapsedRealtimeNanos()-drawBegin);
    bytes.position(0);bytes.limit(w*h*4);return bytes;
  }
  private void configureLook(RawEditLook look)throws IOException {
    GLES30.glActiveTexture(GLES30.GL_TEXTURE2);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,lookCurveTexture);textureOptions();
    boolean changed=lookCurvesState==null;for(int bank=0;bank<5&&!changed;bank++)changed=!look.curve(bank).equals(lookCurvesState.curve(bank));
    if(changed)GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA32F,1024,18,0,GLES30.GL_RGBA,GLES30.GL_FLOAT,RawEditLook.curveData(look));
    lookCurvesState=look;GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"lookCurves"),2);
    GLES30.glActiveTexture(GLES30.GL_TEXTURE3);GLES30.glBindTexture(GLES30.GL_TEXTURE_3D,lookCubeTexture);
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST);GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST);
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D,GLES30.GL_TEXTURE_WRAP_R,GLES30.GL_CLAMP_TO_EDGE);
    RawEditCube cube=look.cube()==null?IDENTITY_CUBE:look.cube();
    if(cube!=uploadedCube){GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D,0,GLES30.GL_RGBA32F,cube.size(),cube.size(),cube.size(),0,GLES30.GL_RGBA,GLES30.GL_FLOAT,cube.rgba());uploadedCube=cube;}
    GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"lookCube"),3);
    GLES30.glUniform3fv(GLES30.glGetUniformLocation(program,"cubeMin"),1,cube.domainMin(),0);GLES30.glUniform3fv(GLES30.glGetUniformLocation(program,"cubeMax"),1,cube.domainMax(),0);
    GLES30.glUniform1f(GLES30.glGetUniformLocation(program,"cubeMix"),look.lutStrength());
    GLES30.glUniform1f(GLES30.glGetUniformLocation(program,"grainAmount"),look.grainStrength());GLES30.glUniform1f(GLES30.glGetUniformLocation(program,"grainShadows"),look.grainShadows());
    GLES30.glUniform1ui(GLES30.glGetUniformLocation(program,"grainSeed"),look.seed());
    int mask=(look.hasTone()?1:0)|(look.lutEnabled()&&look.cube()!=null&&look.lutStrength()>0?2:0)|(look.grainEnabled()&&look.grainStrength()>0?4:0);
    GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"lookMask"),mask);
    int curveMask=0;for(int bank=0;bank<5;bank++)if(!look.curve(bank).identity())curveMask|=1<<bank;
    GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"lookCurveMask"),curveMask);check();
  }
  private static void check()throws IOException{
    if(GLES30.glGetError()!=GLES30.GL_NO_ERROR)throw new IOException("画像処理に失敗しました");
  }
  public void close(){
    if(display==EGL14.EGL_NO_DISPLAY)return;
    if(context!=EGL14.EGL_NO_CONTEXT&&surface!=EGL14.EGL_NO_SURFACE){
      EGL14.eglMakeCurrent(display,surface,surface,context);
      if(program!=0)GLES30.glDeleteProgram(program);
      GLES30.glDeleteTextures(5,new int[]{source,output,lutTexture,lookCurveTexture,lookCubeTexture},0);GLES30.glDeleteFramebuffers(1,new int[]{fbo},0);
    }
    EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
    if(surface!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,surface);
    if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context);
    synchronized(DISPLAY_LOCK){
      if(ownsDisplay&&--displayUsers==0)EGL14.eglTerminate(display);
      ownsDisplay=false;
    }
    readback=null;display=EGL14.EGL_NO_DISPLAY;
  }
}
