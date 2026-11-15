package jp.nano.rawgallery;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inspector.WindowInspector;
import android.widget.FrameLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import org.json.JSONObject;

/** App windows only: a nonmodal status control follows the focused activity or dialog window. */
final class GenerationIndicator implements Application.ActivityLifecycleCallbacks {
  enum State { IDLE, RUNNING, COMPLETE, ATTENTION }
  private static final Handler MAIN=new Handler(Looper.getMainLooper());
  private static GenerationIndicator instance;
  private final Application application;
  private Activity resumed;
  private View host;
  private PopupWindow popup;
  private FrameLayout control;
  private Snackbar snackbar;
  private State state=State.IDLE, rendered;
  private String message="";
  private Uri saved;
  private long completeUntil;
  private boolean announceResult, renderedMotion;
  private final Runnable refresh=this::refresh;

  private GenerationIndicator(Application app){application=app;}
  static void install(Application app){instance=new GenerationIndicator(app);app.registerActivityLifecycleCallbacks(instance);}
  static void running(String message){MAIN.post(()->{if(instance!=null)instance.update(State.RUNNING,message,null);});}
  static void finished(String message,Uri saved,boolean attention){MAIN.post(()->{
    if(instance!=null)instance.update(attention?State.ATTENTION:State.COMPLETE,message,saved);
  });}
  private void update(State next,String text,Uri uri){
    if(snackbar!=null){snackbar.dismiss();snackbar=null;}
    state=next;message=text;saved=uri;rendered=null;
    completeUntil=0;
    announceResult=next!=State.RUNNING;
    refresh();
  }
  private void recover(){
    if(state!=State.IDLE||NanoBananaService.busy())return;
    try{
      JSONObject job=NanoBananaService.dependencies.store(application).job();
      if(job!=null&&!job.optString("stage").equals("complete")){
        state=State.ATTENTION;message="AI編集の処理状況を確認できます";
      }
    }catch(java.io.IOException ignored){state=State.ATTENTION;message="AI編集の状態を確認してください";}
  }
  private View focusedRoot(){
    // A dialog is a separate window. Use its token so the control remains above the modal scrim.
    for(View view:WindowInspector.getGlobalWindowViews())
      if(view.hasWindowFocus()&&view.isAttachedToWindow()&&view.getVisibility()==View.VISIBLE
          &&!"generation-popup".equals(view.getTag())&&view!=control
          &&view.getApplicationWindowToken()==resumed.getWindow().getDecorView().getApplicationWindowToken())return view;
    return resumed.getWindow().getDecorView();
  }
  private void refresh(){
    MAIN.removeCallbacks(refresh);
    if(resumed==null||resumed.isFinishing()||resumed.isDestroyed()){dismiss();return;}
    if(state==State.COMPLETE&&completeUntil==0)completeUntil=SystemClock.uptimeMillis()+5000;
    if(state==State.COMPLETE&&SystemClock.uptimeMillis()>=completeUntil){state=State.IDLE;announceResult=false;}
    if(state==State.IDLE){dismiss();return;}
    View target=focusedRoot();
    if(!target.isAttachedToWindow()){MAIN.postDelayed(refresh,200);return;}
    if(host!=target||popup==null||!popup.isShowing()){dismiss();show(target);}
    else if(rendered!=state||renderedMotion!=ValueAnimator.areAnimatorsEnabled())render();
    if(announceResult){
      announceResult=false;
      snackbar=Snackbar.make(target,message,Snackbar.LENGTH_LONG);
      if(saved!=null)snackbar.setAction("開く",v->openSaved());
      else if(state==State.ATTENTION)snackbar.setAction("確認",v->details());
      snackbar.show();
    }
    // No disk/network polling. Only track focus/geometry while a status is visible in the foreground.
    MAIN.postDelayed(refresh,200);
  }
  private void show(View target){
    host=target;
    control=new FrameLayout(resumed);control.setTag("generation-indicator");
    control.setClickable(true);control.setFocusable(true);
    control.setOnClickListener(v->{if(state==State.COMPLETE&&saved!=null)openSaved();else details();});
    popup=new PopupWindow(control,dp(48),dp(48),false);
    popup.setBackgroundDrawable(null);popup.setOutsideTouchable(false);popup.setTouchModal(false);
    popup.setElevation(dp(3));popup.setIsLaidOutInScreen(true);popup.setIsClippedToScreen(true);
    render();
    WindowInsets insets=resumed.getWindow().getDecorView().getRootWindowInsets();
    android.graphics.Insets safe=insets==null?android.graphics.Insets.NONE:insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
    // Below the 56dp toolbar; nothing is inserted into or moved in the photo layout.
    try{popup.showAtLocation(target,Gravity.TOP|Gravity.RIGHT,safe.right+dp(8),safe.top+dp(60));}
    catch(android.view.WindowManager.BadTokenException ignored){dismiss();}
  }
  private void render(){
    if(control==null)return;
    renderedMotion=ValueAnimator.areAnimatorsEnabled();
    control.removeAllViews();
    FrameLayout surface=new FrameLayout(resumed);
    surface.setBackground(GalleryUi.shape(resumed,0xff303744,16));
    FrameLayout.LayoutParams shape=new FrameLayout.LayoutParams(dp(32),dp(32),Gravity.CENTER);
    control.addView(surface,shape);
    if(state==State.RUNNING&&ValueAnimator.areAnimatorsEnabled()){
      CircularProgressIndicator ring=new CircularProgressIndicator(resumed);
      ring.setIndicatorSize(dp(20));ring.setTrackThickness(dp(2));ring.setIndicatorInset(0);
      ring.setIndicatorColor(0xffADC6FF);ring.setTrackColor(0xff596273);ring.setIndeterminate(true);
      ring.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      surface.addView(ring,new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER));
    }else{
      TextView symbol=new TextView(resumed);symbol.setGravity(Gravity.CENTER);symbol.setTextSize(18);
      symbol.setTextColor(0xffADC6FF);symbol.setText(state==State.COMPLETE?"✓":state==State.ATTENTION?"!":"✦");
      symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      surface.addView(symbol,new FrameLayout.LayoutParams(-1,-1));
    }
    control.setContentDescription(state==State.COMPLETE?"生成画像を保存しました。開く":state==State.ATTENTION?"AI編集の確認が必要です。状況を確認":"AI画像を生成中。状況を確認");
    control.setTooltipText(message);control.setStateDescription(message);rendered=state;
  }
  private void details(){
    if(resumed!=null)NanoBananaUi.showPending(resumed,NanoBananaService.dependencies.store(resumed));
  }
  private void openSaved(){
    if(resumed==null||saved==null)return;
    Uri uri=saved;
    state=State.IDLE;if(snackbar!=null){snackbar.dismiss();snackbar=null;}dismiss();
    resumed.startActivity(new Intent(resumed,MainActivity.class).putExtra(MainActivity.GENERATED_IMAGE,uri.toString()));
  }
  private int dp(int value){return GalleryUi.dp(resumed,value);}
  private void dismiss(){
    if(popup!=null){popup.dismiss();popup=null;}
    if(control!=null)control.removeAllViews();control=null;host=null;rendered=null;
  }
  public void onActivityResumed(Activity a){resumed=a;recover();refresh();}
  public void onActivityPaused(Activity a){if(resumed==a){resumed=null;MAIN.removeCallbacks(refresh);dismiss();if(snackbar!=null){snackbar.dismiss();snackbar=null;}}}
  public void onActivityDestroyed(Activity a){if(resumed==a)onActivityPaused(a);}
  public void onActivityCreated(Activity a,Bundle b){}
  public void onActivityStarted(Activity a){}
  public void onActivityStopped(Activity a){}
  public void onActivitySaveInstanceState(Activity a,Bundle b){}
}
