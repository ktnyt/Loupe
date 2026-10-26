package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;

/** Synthetic display captures. Overrides only this process's resources, restores them in finally. */
final class DashboardLayoutChecks {
  static String run(Instrumentation t) throws Exception {
    String file="dashboard-layout-"+java.util.UUID.randomUUID();SharedPreferences p=t.getTargetContext().getSharedPreferences(file,0);
    DashboardActivity a=(DashboardActivity)t.startActivitySync(new Intent(t.getTargetContext(),DashboardActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    Configuration original=new Configuration(a.getResources().getConfiguration());DisplayMetrics originalMetrics=new DisplayMetrics();originalMetrics.setTo(a.getResources().getDisplayMetrics());
    File output=new File(a.getCacheDir(),"dashboard-checks");output.mkdirs();int captures=0;
    try {
      onMain(t,()->{a.onPause();a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);});
      String[] cases={"unknown","running","lost","waiting","paused","partial","complete","zero","failed","unmeasured-failed","read-fail-prior","read-fail-first"};
      for(int width:new int[]{320,400})for(float scale:new float[]{1f,1.3f}){
        onMain(t,()->{
          Configuration configuration=new Configuration(original);configuration.fontScale=scale;configuration.screenWidthDp=width;
          DisplayMetrics metrics=new DisplayMetrics();metrics.setTo(originalMetrics);metrics.scaledDensity=metrics.density*scale;
          a.getResources().updateConfiguration(configuration,metrics);
          View root=(View)get(a,"root");ViewGroup.LayoutParams params=root.getLayoutParams();params.width=GalleryUi.dp(a,width);root.setLayoutParams(params);((TextView)get(a,"heading")).setTextSize(22);
        });
        for(String kind:cases){
          DashboardObservation value=sample(p,kind);
          onMain(t,()->{set(a,"observation",value);set(a,"detail",-1);invoke(a,"screen");((ScrollView)get(a,"scroll")).scrollTo(0,0);});
          t.waitForIdleSync();Thread.sleep(120);
          onMain(t,()->checkBounds((View)get(a,"content")));
          capture(t,output,width+"-"+scale+"-"+kind+".png");captures++;
        }
        for(int detail=0;detail<4;detail++){
          final int index=detail;DashboardObservation value=sample(p,"lost");
          onMain(t,()->{set(a,"observation",value);set(a,"detail",index);invoke(a,"screen");((ScrollView)get(a,"scroll")).scrollTo(0,0);});
          t.waitForIdleSync();Thread.sleep(120);onMain(t,()->checkBounds((View)get(a,"content")));capture(t,output,width+"-"+scale+"-detail"+detail+".png");captures++;
        }
      }
      return "PASS "+captures+" synthetic real-device captures: 12 list states + 4 details at 320/400dp and font 1/1.3. Child horizontal bounds checked. Visual and TalkBack review separate. Output "+output+"\n";
    }finally{onMain(t,()->{a.getResources().updateConfiguration(original,originalMetrics);a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.finish();});p.edit().clear().commit();t.getTargetContext().deleteSharedPreferences(file);}
  }
  static DashboardObservation sample(SharedPreferences p,String name){
    p.edit().clear().commit();if(name.equals("unknown")||name.equals("read-fail-first")){DashboardObservation empty=new DashboardObservation(JobState.read(p,"p",false,true),true);return name.equals("unknown")?empty:empty.unreadable();}
    JobState.Kind kind=name.equals("running")||name.equals("lost")?JobState.Kind.RUNNING:name.equals("waiting")?JobState.Kind.WAITING:name.equals("paused")?JobState.Kind.PAUSED:name.equals("partial")?JobState.Kind.PARTIAL:name.contains("failed")?JobState.Kind.FAILED:JobState.Kind.COMPLETE;
    JobState.write(p.edit(),"p",kind,1792978920000L).putBoolean("pCountsKnown",!name.equals("unmeasured-failed")).putInt("ready",name.equals("zero")?0:name.equals("running")?902:1284).putInt("total",name.equals("zero")?0:1284).putInt("failed",name.equals("partial")||name.equals("failed")?3:0).commit();
    DashboardObservation value=new DashboardObservation(JobState.read(p,"p",!name.equals("lost"),true),true);return name.equals("read-fail-prior")?value.unreadable():value;
  }
  private static void checkBounds(View v){if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View child=g.getChildAt(i);if(child.getVisibility()!=View.VISIBLE)continue;if(child.getLeft()<0||child.getRight()>g.getWidth()+1)throw new AssertionError("horizontal overflow "+child.getClass().getSimpleName());checkBounds(child);}}}
  private static void capture(Instrumentation t,File dir,String name)throws Exception{Bitmap image=t.getUiAutomation().takeScreenshot();if(image==null)throw new AssertionError("capture unavailable");try(FileOutputStream out=new FileOutputStream(new File(dir,name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}}
  private static Object get(Object o,String name){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
  private static void set(Object o,String name,Object value){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}catch(Exception e){throw new AssertionError(e);}}
  private static void invoke(Object o,String name){try{Method m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(o);}catch(Exception e){throw new AssertionError(e);}}
  private static void onMain(Instrumentation t,Runnable action){final Throwable[] failure={null};t.runOnMainSync(()->{try{action.run();}catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new AssertionError(failure[0]);}

}
