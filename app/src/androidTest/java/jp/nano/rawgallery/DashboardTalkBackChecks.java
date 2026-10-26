package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Manual TalkBack fixture: never obtains UiAutomation, which would suppress the real service. */
final class DashboardTalkBackChecks {
  static String run(Instrumentation t)throws Exception{
    String file="dashboard-talkback-"+java.util.UUID.randomUUID();SharedPreferences p=t.getTargetContext().getSharedPreferences(file,0);
    DashboardActivity a=(DashboardActivity)t.startActivitySync(new Intent(t.getTargetContext(),DashboardActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();AtomicInteger step=new AtomicInteger();AtomicBoolean restored=new AtomicBoolean();
    String[] cases={"unknown","zero","lost","read-fail-prior","read-fail-first","lost"};
    DashboardObservation[] samples=new DashboardObservation[cases.length];for(int i=0;i<cases.length;i++)samples[i]=DashboardLayoutChecks.sample(p,cases[i]);
    AtomicReference<DashboardObservation> value=new AtomicReference<>(samples[0]);
    try{
      t.runOnMainSync(()->{
        a.source=value::get;a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=(LinearLayout)get(a,"root");
        Runnable show=()->{try{
          int index=step.get();value.set(samples[index]);set(a,"observation",value.get());set(a,"detail",-1);invoke(a,"screen");
          View node=find(root,index==0?"読み込み中の仮画像":index>=3&&index<=4?"共通の準備ジョブ":"表示用プレビュー");
          if(node==null)throw new AssertionError("missing focus target");
          node.postDelayed(()->{
            try{node.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,null);
              if(index==5){node.performClick();root.postDelayed(()->{a.onBackPressed();root.postDelayed(()->{View returned=find(root,"表示用プレビュー");restored.set(returned!=null&&returned.isAccessibilityFocused());},700);},1800);}
            }catch(Throwable e){failure.set(e);done.countDown();}
          },500);
        }catch(Throwable e){failure.set(e);done.countDown();}};
        root.addView(GalleryUi.button(a,"次の検証",()->{step.set(Math.min(cases.length-1,step.get()+1));show.run();}));
        root.addView(GalleryUi.button(a,"検証を終了",done::countDown));root.postDelayed(show,500);
      });
      if(!done.await(10,TimeUnit.MINUTES))throw new AssertionError("manual fixture timeout");
      if(failure.get()!=null)throw new AssertionError(failure.get());
      if(step.get()!=5||!restored.get())throw new AssertionError("TalkBack Back focus not verified");
      return "PASS TalkBack fixture: reached six scenarios and accessibility focus returned to preview card after Back. Spoken output assessed separately; no claim about physical gestures or audio quality.\n";
    }finally{t.runOnMainSync(()->{a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.finish();});p.edit().clear().commit();t.getTargetContext().deleteSharedPreferences(file);}
  }
  private static View find(View v,String prefix){if(!(v instanceof android.widget.TextView)&&v.getContentDescription()!=null&&v.getContentDescription().toString().startsWith(prefix))return v;if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++){View result=find(group.getChildAt(i),prefix);if(result!=null)return result;}}return null;}
  private static Object get(Object o,String name){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
  private static void set(Object o,String name,Object value){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}catch(Exception e){throw new AssertionError(e);}}
  private static void invoke(Object o,String name){try{Method m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(o);}catch(Exception e){throw new AssertionError(e);}}
}
