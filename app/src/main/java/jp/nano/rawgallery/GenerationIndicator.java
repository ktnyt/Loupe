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
  private int popupX,popupY;
  private final android.view.ViewTreeObserver.OnGlobalLayoutListener layout=()->scheduleRefresh();
  private final View.OnUnhandledKeyEventListener shortcut=this::keyboardShortcut;
  private boolean keyboardShortcut(View view,android.view.KeyEvent event){
    if(state==State.IDLE||event.getKeyCode()!=android.view.KeyEvent.KEYCODE_G||!event.isCtrlPressed()||!event.isShiftPressed())return false;
    if(event.getAction()==android.view.KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){if(state==State.COMPLETE&&saved!=null)openSaved();else details();}
    return true;
  }
  private State state=State.IDLE, rendered;
  private String message="";
  private Uri saved;
  private long completeUntil;
  private boolean announceResult, renderedMotion;
  private final Runnable refresh=this::refresh;

  private GenerationIndicator(Application app){application=app;}
  static void install(Application app){instance=new GenerationIndicator(app);app.registerActivityLifecycleCallbacks(instance);}
  static boolean foreground(){return instance!=null&&instance.resumed!=null;}
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
          )return view;
    return resumed.getWindow().getDecorView();
  }
  private void scheduleRefresh(){MAIN.removeCallbacks(refresh);MAIN.post(refresh);}
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
    if(popup!=null&&popup.isShowing()){int[] point=placement(target);if(point[0]!=popupX||point[1]!=popupY){popupX=point[0];popupY=point[1];popup.update(popupX,popupY,-1,-1);}}
    if(announceResult){
      announceResult=false;
      snackbar=Snackbar.make(target,message,Snackbar.LENGTH_LONG);
      View anchor=target.findViewWithTag("viewer-actions");
      if(anchor==null)anchor=target.findViewWithTag("library-navigation");
      if(anchor==null)anchor=target.findViewById(com.google.android.material.R.id.design_bottom_sheet);
      if(anchor!=null)snackbar.setAnchorView(anchor);
      if(saved!=null)snackbar.setAction("開く",v->openSaved());
      else if(state==State.ATTENTION)snackbar.setAction("確認",v->details());
      snackbar.show();
    }
    // No disk/network polling. Only track focus/geometry while a status is visible in the foreground.
    MAIN.postDelayed(refresh,200);
  }
  private void show(View target){
    host=target;host.addOnUnhandledKeyEventListener(shortcut);
    control=new FrameLayout(resumed);control.setTag("generation-indicator");
    control.setClickable(true);control.setFocusable(true);
    control.setOnClickListener(v->{if(state==State.COMPLETE&&saved!=null)openSaved();else details();});
    popup=new PopupWindow(control,dp(48),dp(48),false);
    popup.setBackgroundDrawable(null);popup.setOutsideTouchable(false);popup.setTouchModal(false);
    popup.setElevation(dp(3));popup.setIsLaidOutInScreen(true);popup.setIsClippedToScreen(true);
    render();
    int[] point=placement(target);popupX=point[0];popupY=point[1];
    try{popup.showAtLocation(target,Gravity.TOP|Gravity.RIGHT,popupX,popupY);
      control.getRootView().setTag("generation-popup");}
    catch(android.view.WindowManager.BadTokenException ignored){dismiss();}
  }
  private int[] placement(View target){
    WindowInsets insets=resumed.getWindow().getDecorView().getRootWindowInsets();
    android.graphics.Insets safe=insets==null?android.graphics.Insets.NONE:insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
    // Keep the full hit target clear of controls, including viewer actions and settings buttons.
    View decor=resumed.getWindow().getDecorView();int[] origin=new int[2];decor.getLocationOnScreen(origin);
    int right=origin[0]+decor.getWidth()-safe.right;
    int x=right-dp(56);
    int y=origin[1]+safe.top+dp(60),limit=origin[1]+decor.getHeight()-safe.bottom-dp(56);
    while(y<limit&&(overlapsAction(resumed.getWindow().getDecorView(),x,y)||overlapsAction(target,x,y)))y+=dp(8);
    return new int[]{resumed.getResources().getDisplayMetrics().widthPixels-right+dp(8),Math.min(y,limit)};
  }
  private boolean overlapsAction(View view,int x,int y){
    if(!view.isShown())return false;
    if(view instanceof android.widget.Button||view instanceof android.widget.EditText||view instanceof android.widget.SeekBar){
      android.graphics.Rect bounds=new android.graphics.Rect();
      if(view.getGlobalVisibleRect(bounds)){
        int[] origin=new int[2];view.getRootView().getLocationOnScreen(origin);bounds.offset(origin[0],origin[1]);
        if(android.graphics.Rect.intersects(bounds,new android.graphics.Rect(x,y,x+dp(48),y+dp(48))))return true;
      }
    }
    if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(overlapsAction(((ViewGroup)view).getChildAt(i),x,y))return true;
    return false;
  }
  private void render(){
    if(control==null)return;
    renderedMotion=ValueAnimator.areAnimatorsEnabled();
    control.removeAllViews();
    FrameLayout surface=new FrameLayout(resumed);
    surface.setBackground(GalleryUi.shape(resumed,0xff303744,14));
    FrameLayout.LayoutParams shape=new FrameLayout.LayoutParams(dp(28),dp(28),Gravity.CENTER);
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
    control.setContentDescription(state==State.COMPLETE?(saved!=null?"生成画像を保存しました。開く":message+"。状況を確認"):state==State.ATTENTION?"AI編集の確認が必要です。状況を確認":"AI編集："+message+"。状況を確認");
    control.setTooltipText(message+"（Ctrl+Shift+Gで確認）");control.setStateDescription(message);rendered=state;
  }
  private void details(){
    if(resumed!=null){
      NanoBananaStore store=NanoBananaService.dependencies.store(resumed);
      NanoBananaUi.showPending(resumed,store);
      try{if(state==State.ATTENTION&&!NanoBananaService.busy()&&store.job()==null){state=State.IDLE;refresh();}}catch(java.io.IOException ignored){}
    }
  }
  private void openSaved(){
    if(resumed==null||saved==null)return;
    Uri uri=saved;
    state=State.IDLE;if(snackbar!=null){snackbar.dismiss();snackbar=null;}dismiss();
    resumed.startActivity(new Intent(resumed,MainActivity.class).putExtra(MainActivity.GENERATED_IMAGE,uri.toString()));
  }
  private int dp(int value){return GalleryUi.dp(resumed,value);}
  private void dismiss(){
    if(host!=null)host.removeOnUnhandledKeyEventListener(shortcut);
    if(popup!=null){popup.dismiss();popup=null;}
    if(control!=null)control.removeAllViews();control=null;host=null;rendered=null;
  }
  public void onActivityResumed(Activity a){resumed=a;a.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(layout);recover();refresh();}
  public void onActivityPaused(Activity a){if(resumed==a){a.getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(layout);resumed=null;MAIN.removeCallbacks(refresh);dismiss();if(snackbar!=null){snackbar.dismiss();snackbar=null;}}}
  public void onActivityDestroyed(Activity a){if(resumed==a)onActivityPaused(a);}
  public void onActivityCreated(Activity a,Bundle b){}
  public void onActivityStarted(Activity a){}
  public void onActivityStopped(Activity a){}
  public void onActivitySaveInstanceState(Activity a,Bundle b){}
}
