package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** Non-text status with explicit state, complete spoken description and optional recovery. */
final class StatusView extends View {
  enum Symbol { RUNNING, WAITING, PAUSED, COMPLETE, PARTIAL, FAILED, AUTH, UNKNOWN,
    NETWORK, STORAGE, PERMISSION, TLS, HOST, NO_APP, FORMAT, CANCELLED, ALREADY,
    EMPTY, PREVIEW, TAG, AUTO_TAG, NO_TAG, VIEWING }
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  private Symbol symbol=Symbol.UNKNOWN, companion;
  private String numbers="", spoken="";
  private boolean shown, loading, marker, transientResult, collapseWhenIdle;
  private long started, visibleAt;
  private final Typeface numberTypeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);
  private int revision;
  private boolean suppressElapsed, photoOverlay, photoFailure;
  void asPhotoOverlay(){photoOverlay=true;suppressElapsed=true;requestLayout();}
  void asPhotoFailure(){photoOverlay=false;photoFailure=true;requestLayout();}
  int photoRightInset(){return companion==null||isClickable()?2:12;}
  private Runnable recovery;
  private String recoveryName="";
  StatusView(Context c) {
    super(c);setMinimumHeight(dp(48));setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
    setVisibility(INVISIBLE);
  }
  private int dp(float n){return GalleryUi.dp(getContext(),n);}
  void collapseWhenIdle(){collapseWhenIdle=true;requestLayout();}
  void show(Symbol value,String numeric,String description,Runnable action,String actionName) {
    revision++;animate().cancel();setAlpha(1);loading=false;transientResult=false;
    symbol=value;numbers=numeric==null?"":numeric;spoken=description;recovery=action;
    recoveryName=action==null||actionName==null?"":actionName;marker=action!=null||!numbers.isEmpty()||companion!=null;
    setOnClickListener(action==null?null:v->action.run());setClickable(action!=null);setFocusable(action!=null);
    shown=true;setVisibility(VISIBLE);publish();requestLayout();invalidate();
  }
  void show(Symbol value,String description){show(value,"",description,null,"");}
  void companion(Symbol value){companion=value;marker=value!=null||recovery!=null||!numbers.isEmpty();requestLayout();invalidate();}
  void begin(String description) {
    clear();loading=true;started=SystemClock.uptimeMillis();spoken=description;symbol=Symbol.RUNNING;requestLayout();
    int token=revision;
    postDelayed(()->{if(token!=revision||!loading)return;shown=true;visibleAt=SystemClock.uptimeMillis();setVisibility(VISIBLE);publish();invalidate();},400);
    postDelayed(()->{if(token!=revision||!loading||suppressElapsed)return;marker=true;requestLayout();invalidate();},8000);
  }
  void observe(Symbol value,String numeric,String description) {
    if(value==Symbol.RUNNING){if(!loading)begin(description);else describe(description);counts(numeric);}
    else show(value,numeric,description,null,"");
  }
  void describe(String value){spoken=value;if(shown)publish();}
  void counts(String value){numbers=value==null?"":value;if(shown){requestLayout();invalidate();}}
  void finish() {
    loading=false;int token=revision;
    if(!shown){clear();return;}
    postDelayed(()->{if(token!=revision)return;animate().alpha(0).setDuration(150).withEndAction(()->{if(token==revision)clear();}).start();},Math.max(0,600-(SystemClock.uptimeMillis()-visibleAt)));
  }
  void result(Symbol value,String description) {
    show(value,description);transientResult=true;marker=false;int token=revision;
    postDelayed(()->{if(token!=revision)return;animate().alpha(0).setDuration(150).withEndAction(()->{if(token==revision)clear();}).start();},900);
  }
  void clear() {
    revision++;animate().cancel();setAlpha(1);loading=false;shown=false;marker=false;transientResult=false;recoveryName="";companion=null;numbers="";
    recovery=null;setOnClickListener(null);setClickable(false);setFocusable(false);
    setContentDescription(null);setVisibility(INVISIBLE);requestLayout();invalidate();
  }
  private void drawPhotoOverlay(Canvas canvas){
    float d=getResources().getDisplayMetrics().density;int saved=canvas.save();canvas.scale(d,d);
    float width=companion==null?28:50,left=(getWidth()/d-width)/2,top=(getHeight()/d-28)/2;
    paint.setStyle(Paint.Style.FILL);paint.setColor(0x9e000000);canvas.drawRoundRect(left,top,left+width,top+28,14,14,paint);
    if(error(symbol)){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f);paint.setColor(0xffff8a7a);canvas.drawRoundRect(left+.75f,top+.75f,left+width-.75f,top+27.25f,13.25f,13.25f,paint);}
    float angle=symbol==Symbol.RUNNING&&android.animation.ValueAnimator.areAnimatorsEnabled()?(SystemClock.uptimeMillis()%1000)*.36f:0;
    if(companion!=null){glyph(canvas,paint,companion,left+6,top+5,18,GalleryUi.TEXT,0);glyph(canvas,paint,symbol,left+26,top+5,18,error(symbol)?0xffff8a7a:GalleryUi.TEXT,angle);}
    else glyph(canvas,paint,symbol,left+4,top+4,20,error(symbol)?0xffff8a7a:GalleryUi.TEXT,angle);
    canvas.restoreToCount(saved);if(symbol==Symbol.RUNNING&&isShown()&&android.animation.ValueAnimator.areAnimatorsEnabled())postInvalidateDelayed(50);
  }
  private void publish(){String value=spoken+(recoveryName.isEmpty()?"":"。"+recoveryName);if(!android.text.TextUtils.equals(getContentDescription(),value))setContentDescription(value);}
  @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);if(recovery!=null){info.setClassName("android.widget.Button");info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,recoveryName));}}
  @Override protected void onDetachedFromWindow(){revision++;animate().cancel();super.onDetachedFromWindow();}
  @Override protected void onMeasure(int w,int h){if(photoFailure){setMeasuredDimension(resolveSize(dp(32),w),resolveSize(dp(32),h));return;}if(photoOverlay){setMeasuredDimension(resolveSize(dp(companion==null?48:50),w),resolveSize(dp(48),h));return;}if(collapseWhenIdle&&!loading&&!shown){setMeasuredDimension(0,0);return;}paint.setTypeface(numberTypeface);paint.setTextSize(13*getResources().getDisplayMetrics().scaledDensity);String measured=numbers.isEmpty()&&loading&&!suppressElapsed?"999分":numbers;int width=dp(companion==null?48:68)+(measured.isEmpty()?0:(int)paint.measureText(measured)+dp(8));setMeasuredDimension(resolveSize(width,w),resolveSize(dp(48),h));}
  @Override protected void onDraw(Canvas original){
    if(!shown)return;
    if(photoFailure){glyph(original,paint,Symbol.FAILED,(getWidth()-dp(32))/2f,(getHeight()-dp(32))/2f,dp(32),0xffff8a7a,0);return;}
    if(photoOverlay){drawPhotoOverlay(original);return;}
    float density=getResources().getDisplayMetrics().density;
    Canvas c=original;int save=c.save();c.scale(density,density);
    float w=getWidth()/density,h=getHeight()/density;
    String value=numbers;
    if(loading&&!suppressElapsed&&marker&&value.isEmpty()){long mins=(SystemClock.uptimeMillis()-started)/60000;value=mins==0?"<1分":mins+"分";}
    paint.setTextSize(13*getResources().getConfiguration().fontScale);paint.setTypeface(numberTypeface);
    float textWidth=value.isEmpty()?0:paint.measureText(value)+4;
    float content=20+(companion==null?0:24)+textWidth;
    float left=(w-content)/2-(marker?2:0);
    if(marker){paint.setStyle(Paint.Style.FILL);paint.setColor(0xff1f1f22);c.drawRoundRect(left-4,h/2-14,left+content+8,h/2+14,14,14,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(error(symbol)?1.5f:1);paint.setColor(error(symbol)?0xffff8a7a:0xff3a3a3f);float inset=paint.getStrokeWidth()/2;c.drawRoundRect(left-4+inset,h/2-14+inset,left+content+8-inset,h/2+14-inset,14-inset,14-inset,paint);}
    if(transientResult){paint.setStyle(Paint.Style.FILL);paint.setColor(0xff26262a);c.drawCircle(w/2,h/2,20,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);paint.setColor(0xff3a3a3f);c.drawCircle(w/2,h/2,19.5f,paint);}
    if(companion!=null){glyph(c,paint,companion,left+1,h/2-9,18,0xffa0a0a4,0);left+=24;}
    float angle=symbol==Symbol.RUNNING?(SystemClock.uptimeMillis()%1000)*.36f:0;
    if(symbol==Symbol.EMPTY)glyph(c,paint,symbol,w/2-24,h/2-24,48,GalleryUi.MUTED,0);else glyph(c,paint,symbol,left+(marker?1:0),h/2-(marker?9:10),marker?18:20,error(symbol)?0xffff8a7a:GalleryUi.TEXT,angle);
    if(!value.isEmpty()){paint.setStyle(Paint.Style.FILL);paint.setColor(GalleryUi.TEXT);c.drawText(value,left+24,h/2-(paint.ascent()+paint.descent())/2,paint);}
    c.restoreToCount(save);if(symbol==Symbol.RUNNING&&isShown())postInvalidateDelayed(50);
  }
  static boolean error(Symbol s){return s==Symbol.FAILED||s==Symbol.NETWORK||s==Symbol.STORAGE||s==Symbol.PERMISSION||s==Symbol.TLS||s==Symbol.HOST||s==Symbol.NO_APP||s==Symbol.FORMAT;}
  static Symbol symbol(JobState.Kind kind){switch(kind){case RUNNING:return Symbol.RUNNING;case WAITING:return Symbol.WAITING;case PAUSED:return Symbol.PAUSED;case COMPLETE:return Symbol.COMPLETE;case PARTIAL:return Symbol.PARTIAL;case FAILED:return Symbol.FAILED;case AUTH_REQUIRED:return Symbol.AUTH;default:return Symbol.UNKNOWN;}}
  static void glyph(Canvas c,Paint p,Symbol s,float x,float y,float size,int color,float angle){
    int saved=c.save();c.translate(x,y);c.scale(size/24,size/24);p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setPathEffect(null);
    switch(s){
      case RUNNING:p.setStrokeWidth(2.2f);p.setAlpha(55);c.drawCircle(12,12,8,p);p.setAlpha(255);c.drawArc(4,4,20,20,-90+angle,90,false,p);break;
      case WAITING:c.drawCircle(12,12,8.5f,p);line(c,p,12,7.5f,12,12,15,14);break;
      case PAUSED:c.drawCircle(12,12,8.5f,p);line(c,p,10,9,10,15);line(c,p,14,9,14,15);break;
      case COMPLETE:p.setStyle(Paint.Style.FILL);c.drawCircle(12,12,9.5f,p);p.setStyle(Paint.Style.STROKE);p.setColor(0xff111111);p.setStrokeWidth(2.2f);line(c,p,7.8f,12.3f,10.6f,15.1f,16.2f,9.3f);break;
      case ALREADY:c.drawCircle(12,12,8.5f,p);line(c,p,8.3f,12.3f,10.9f,14.9f,15.9f,9.7f);break;
      case PARTIAL:c.drawCircle(11,11,8.5f,p);line(c,p,7.3f,11.2f,9.8f,13.7f,14.6f,8.7f);p.setStyle(Paint.Style.FILL);p.setColor(GalleryUi.BG);c.drawCircle(18.5f,18.5f,5,p);p.setStyle(Paint.Style.STROKE);p.setColor(0xffff8a7a);line(c,p,18.5f,15.8f,18.5f,18.8f);c.drawPoint(18.5f,21,p);break;
      case FAILED:line(c,p,12,4,21,19.5f,3,19.5f,12,4);line(c,p,12,10,12,14.5f);c.drawPoint(12,17.2f,p);break;
      case AUTH:c.drawRoundRect(5.5f,10.5f,18.5f,20,2,2,p);c.drawArc(8.5f,4.5f,15.5f,11.5f,180,180,false,p);line(c,p,8.5f,8,8.5f,10.5f);line(c,p,15.5f,8,15.5f,10.5f);break;
      case UNKNOWN:p.setPathEffect(new DashPathEffect(new float[]{2.2f,2.2f},0));c.drawCircle(12,12,8.5f,p);p.setPathEffect(null);Path question=new Path();question.moveTo(9.8f,9.6f);question.arcTo(9.7999403f,7.2834243f,14.3999403f,11.8834243f,179.5870769f,247.3756221f,false);question.cubicTo(12.3f,12,12,12.5f,12,13.2f);question.lineTo(12,13.6f);question.moveTo(12,16.6f);question.lineTo(12,16.7f);c.drawPath(question,p);break;
      case EMPTY:p.setStrokeWidth(1);p.setStrokeCap(Paint.Cap.BUTT);p.setPathEffect(new DashPathEffect(new float[]{2,2},0));c.drawRoundRect(3.5f,4.5f,20.5f,19.5f,3,3,p);p.setPathEffect(null);break;
      case CANCELLED:c.drawCircle(12,12,8.5f,p);p.setStyle(Paint.Style.FILL);c.drawRoundRect(9,9,15,15,1,1,p);break;
      case PREVIEW:for(int a=0;a<2;a++)for(int b=0;b<2;b++)c.drawRoundRect(4+a*9,4+b*9,11+a*9,11+b*9,1,1,p);break;
      case STORAGE:c.drawRoundRect(6,3.5f,18,20.5f,2,2,p);for(int i=9;i<=15;i+=3)line(c,p,i,3.5f,i,7.5f);line(c,p,12,11,12,15);c.drawPoint(12,17.6f,p);break;
      case PERMISSION:case TLS:Path shield=new Path();shield.moveTo(12,3.5f);shield.lineTo(19,6.5f);shield.lineTo(19,11.5f);shield.cubicTo(19,15.7f,16,18.9f,12,20.5f);shield.cubicTo(8,18.9f,5,15.7f,5,11.5f);shield.lineTo(5,6.5f);shield.close();c.drawPath(shield,p);if(s==Symbol.PERMISSION)line(c,p,7,7,17,17);else{line(c,p,9.5f,10,14.5f,15);line(c,p,14.5f,10,9.5f,15);}break;
      case HOST:c.drawCircle(12,12,8.5f,p);Path globe=new Path();globe.moveTo(12,3.5f);globe.cubicTo(14.5f,6.2f,14.5f,17.8f,12,20.5f);globe.moveTo(12,3.5f);globe.cubicTo(9.5f,6.2f,9.5f,17.8f,12,20.5f);c.drawPath(globe,p);line(c,p,3.5f,12,20.5f,12);line(c,p,5,5,19,19);break;
      case NO_APP:c.drawRoundRect(4,4,10.5f,10.5f,1.5f,1.5f,p);c.drawRoundRect(13.5f,4,20,10.5f,1.5f,1.5f,p);c.drawRoundRect(4,13.5f,10.5f,20,1.5f,1.5f,p);line(c,p,14,14,20,20);break;
      case FORMAT:line(c,p,6.5f,3.5f,14,3.5f,18,7.5f,18,20.5f,6.5f,20.5f,6.5f,3.5f);line(c,p,14,3.5f,14,7.5f,18,7.5f);line(c,p,9.5f,12.5f,14.5f,17.5f);line(c,p,14.5f,12.5f,9.5f,17.5f);break;
      case NETWORK:Path cloud=new Path();cloud.moveTo(7,18);cloud.lineTo(17.5f,18);cloud.arcTo(14.1467872f,10.9967872f,21.1532128f,18.0032128f,92.4540317f,-180.0000000f,false);cloud.arcTo(7.1770926f,3.4985456f,18.1770926f,14.4985456f,21.3399521f,-206.5710753f,false);cloud.arcTo(2.1540364f,9.4348009f,10.7540364f,18.0348009f,-80.0097896f,-197.2846462f,false);cloud.close();c.drawPath(cloud,p);line(c,p,4,4,20,20);break;
      case TAG:case AUTO_TAG:case NO_TAG:if(s==Symbol.NO_TAG)p.setPathEffect(new DashPathEffect(new float[]{2.5f,2.5f},0));if(s==Symbol.TAG)p.setStyle(Paint.Style.FILL);line(c,p,4,12,4,5,5,4,12,4,21,13,13,21,4,12);p.setPathEffect(null);p.setStyle(Paint.Style.STROKE);if(s==Symbol.AUTO_TAG){line(c,p,8,6.5f,8,9.5f);line(c,p,6.5f,8,9.5f,8);}break;
      case VIEWING:Path eye=new Path();eye.moveTo(2.5f,12);eye.cubicTo(8,3,16,3,21.5f,12);eye.cubicTo(16,21,8,21,2.5f,12);c.drawPath(eye,p);c.drawCircle(12,12,2.8f,p);break;
    }p.setPathEffect(null);p.setAlpha(255);c.restoreToCount(saved);
  }
  private static void line(Canvas c,Paint p,float...v){Path path=new Path();path.moveTo(v[0],v[1]);for(int i=2;i<v.length;i+=2)path.lineTo(v[i],v[i+1]);c.drawPath(path,p);}
}
