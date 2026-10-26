package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/** Uses isolated preferences and injected observations; never modifies real progress or tags. */
final class DashboardChecks {
  static String run(Instrumentation t) throws Exception {
    String file="dashboard-test-"+java.util.UUID.randomUUID();
    SharedPreferences p=t.getTargetContext().getSharedPreferences(file,0);
    DashboardActivity a=null;
    try {
      DashboardObservation empty=new DashboardObservation(JobState.read(p,"p",false,true),true);
      require(empty.count(0).equals("—")&&empty.time().equals("—"),"unknown isn't zero");
      require(empty.unreadable().readFailed&&!empty.unreadable().known&&empty.unreadable().at==0,"first read failure");
      JobState.write(p.edit(),"p",JobState.Kind.COMPLETE,123456).putBoolean("pCountsKnown",true).putInt("ready",0).putInt("total",0).putInt("failed",0).commit();
      DashboardObservation zero=new DashboardObservation(JobState.read(p,"p",false,true),true);
      require(zero.count(0).equals("0")&&!zero.old(),"measured zero");
      JobState.write(p.edit(),"p",JobState.Kind.RUNNING,123456).putInt("ready",12).putInt("total",12).putInt("failed",3).commit();
      DashboardObservation lost=new DashboardObservation(JobState.read(p,"p",false,true),true);
      require(lost.kind==JobState.Kind.UNKNOWN&&lost.old()&&lost.ready==12&&lost.failed==3,"lost runner retains overlapping counts");
      DashboardObservation live=new DashboardObservation(JobState.read(p,"p",true,true),true);
      DashboardObservation failure=live.unreadable();
      require(failure.kind==JobState.Kind.RUNNING&&failure.readFailed&&failure.old()&&failure.at==123456&&failure.ready==12,"read failure preserves observation, not FAILED");
      DashboardObservation restored=DashboardObservation.restore(failure.save());
      require(restored.readFailed&&restored.ready==12&&restored.at==123456,"restore previous observation");
      p.edit().putString("pStateCode","future").commit();
      require(!new DashboardObservation(JobState.read(p,"p",true,true),true).known,"unknown code");
      a=(DashboardActivity)t.startActivitySync(new Intent(t.getTargetContext(),DashboardActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      DashboardActivity screen=a;
      AtomicInteger reads=new AtomicInteger();
      t.runOnMainSync(()->{screen.onPause();screen.source=()->{reads.incrementAndGet();return live;};screen.onResume();});
      Thread.sleep(700);t.waitForIdleSync();
      require(reads.get()==1,"initial read");
      t.runOnMainSync(()->{require(current(screen).ready==12,"injected rendered observation");});
      Thread.sleep(5100);require(reads.get()==2,"five second refresh");
      t.runOnMainSync(screen::onPause);int stopped=reads.get();Thread.sleep(5200);require(reads.get()==stopped,"no reads while paused");
      t.runOnMainSync(()->{screen.source=()->{throw new IllegalStateException("synthetic read failure");};screen.onResume();});
      Thread.sleep(700);t.waitForIdleSync();
      t.runOnMainSync(()->{
        require(current(screen).readFailed&&current(screen).ready==12,"screen retains values on read failure");
        ScrollView scroll=(ScrollView)get(screen,"scroll");
        // Restrict viewport to exercise actual scrolling without changing device settings.
        ViewGroup.LayoutParams params=scroll.getLayoutParams();params.height=GalleryUi.dp(screen,240);if(params instanceof android.widget.LinearLayout.LayoutParams)((android.widget.LinearLayout.LayoutParams)params).weight=0;scroll.setLayoutParams(params);
      });
      t.waitForIdleSync();
      t.runOnMainSync(()->((ScrollView)get(screen,"scroll")).scrollTo(0,120));t.waitForIdleSync();
      for(int i=0;i<4;i++){
        final int index=i;
        t.runOnMainSync(()->{View card=find(screen.getWindow().getDecorView(),new String[]{"読み込み中の仮画像","表示用プレビュー","タグのための写真の解析","自動のタグ付け"}[index]);require(card!=null,"card "+index);card.performClick();require((int)get(screen,"detail")==index,"detail navigation");screen.onBackPressed();});
        t.waitForIdleSync();
        t.runOnMainSync(()->require(((ScrollView)get(screen,"scroll")).getScrollY()==120,"back restores position"));
      }
      t.runOnMainSync(()->{Bundle saved=new Bundle();screen.onSaveInstanceState(saved);require(saved.getInt("listY")==120,"saved list position");require(DashboardObservation.restore(saved.getBundle("observation")).readFailed,"saved last observation");});
      return "PASS dashboard: unknown vs measured zero; stale runner; overlapping ready/fail; read failure retains prior data; snapshot Bundle; initial and 5s refresh; paused refresh removed; 4 detail transitions and actual Back scroll restoration. No original or production preference writes.\n";
    } finally {
      if(a!=null){DashboardActivity screen=a;t.runOnMainSync(screen::finish);}
      p.edit().clear().commit();t.getTargetContext().deleteSharedPreferences(file);
    }
  }
  private static DashboardObservation current(DashboardActivity a){return (DashboardObservation)get(a,"observation");}
  private static Object get(Object o,String name){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
  private static View find(View v,String prefix){if(v.isClickable()&&v.getContentDescription()!=null&&v.getContentDescription().toString().startsWith(prefix))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View found=find(g.getChildAt(i),prefix);if(found!=null)return found;}}return null;}
  private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
