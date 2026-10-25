package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Navigation tests use generated cache images; no tag edits, inference, login or sync. */
final class SystemBackChecks {
  private interface Work { void run() throws Exception; }
  private static Object get(Object o,String n)throws Exception { Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o); }
  private static void set(Object o,String n,Object v)throws Exception { Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v); }
  private static void call(Object o,String n,Class<?>[] types,Object... args)throws Exception { Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);m.invoke(o,args); }
  private static void ui(Instrumentation t,Work w)throws Exception { Throwable[] error={null};t.runOnMainSync(()->{try{w.run();}catch(Throwable e){error[0]=e;}});t.waitForIdleSync();if(error[0]!=null)throw new AssertionError(error[0]); }
  private static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
  private static void drain(Instrumentation t,TagActivity a)throws Exception { ((ExecutorService)get(a,"worker")).submit(()->{}).get(10,TimeUnit.SECONDS);t.waitForIdleSync(); }
  private static void back(Instrumentation t) { t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);t.waitForIdleSync(); }
  private static void exited(Instrumentation t,Activity a)throws Exception {
    long until=android.os.SystemClock.uptimeMillis()+4000;
    while(android.os.SystemClock.uptimeMillis()<until){
      boolean[] inactive={false};ui(t,()->inactive[0]=a.isFinishing()||a.isDestroyed()||!a.hasWindowFocus());
      android.view.accessibility.AccessibilityNodeInfo node=t.getUiAutomation().getRootInActiveWindow();
      if(inactive[0]&&node!=null&&!"jp.nano.rawgallery".contentEquals(node.getPackageName()))return;
      if(a.isFinishing()||a.isDestroyed())return;
      Thread.sleep(50);
    }throw new AssertionError("Back did not leave "+a.getClass().getSimpleName());
  }
  private static void noBack(View v) {
    if(v instanceof Button) { String s=((Button)v).getText().toString();check(!Arrays.asList("戻る","写真に戻る","タグ一覧に戻る","タグに戻る","一覧に戻る","比較に戻る","連写一覧","選択へ戻る").contains(s),"navigation button remains: "+s); }
    if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)noBack(((ViewGroup)v).getChildAt(i));
  }
  static String run(Instrumentation t)throws Exception {
    String result=SortNamingChecks.run(t)+ComparisonLabelChecks.run(t);
    TagActivity a=(TagActivity)t.startActivitySync(new Intent(t.getTargetContext(),TagActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    File file=new File(a.getCacheDir(),"system-back-fixture.jpg");
    try {
      drain(t,a);
      Bitmap bitmap=Bitmap.createBitmap(320,400,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff587c8c);
      try(FileOutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);}bitmap.recycle();
      Class<?> filter=Class.forName("jp.nano.rawgallery.TagActivity$Filter");Object all=Enum.valueOf((Class)filter,"ALL");
      ui(t,()->{
        set(a,"tag",new TagStore.Tag("system-back-test","検査"));
        List<PhotoRepository.Photo> photos=(List<PhotoRepository.Photo>)get(a,"photos");photos.clear();
        List<String> ids=(List<String>)get(a,"photoIds");ids.clear();
        for(int i=0;i<80;i++){photos.add(new PhotoRepository.Photo(Uri.fromFile(file),"検査"+i+".jpg","",file.length(),i,0));ids.add(null);}
        call(a,"showPhotos",new Class<?>[]{filter},all);
      });drain(t,a);
      long deadline=android.os.SystemClock.uptimeMillis()+5000;
      while(true){boolean[] ready={false};ui(t,()->{GridView grid=(GridView)get(a,"photoGrid");ready[0]=grid.getChildCount()>0&&grid.getHeight()>0;});if(ready[0])break;if(android.os.SystemClock.uptimeMillis()>deadline)throw new AssertionError("grid layout timeout");Thread.sleep(50);}
      ui(t,()->{GridView grid=(GridView)get(a,"photoGrid");grid.scrollListBy(1800);});
      Thread.sleep(200);t.waitForIdleSync();
      Bitmap shot=t.getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(a.getCacheDir(),"system-back-grid.png"))){shot.compress(Bitmap.CompressFormat.PNG,100,out);}shot.recycle();
      int[] position=new int[2];
      ui(t,()->{GridView grid=(GridView)get(a,"photoGrid");position[0]=grid.getFirstVisiblePosition();position[1]=grid.getChildAt(0).getTop();check(position[0]>0,"fixture did not scroll: count="+grid.getCount()+" children="+grid.getChildCount()+" height="+grid.getHeight()+" firstHeight="+grid.getChildAt(0).getHeight()+" firstTop="+position[1]);noBack(a.getWindow().getDecorView());call(a,"showAssignedPhoto",new Class<?>[]{int.class},25);});
      drain(t,a);back(t);Thread.sleep(200);
      ui(t,()->{GridView grid=(GridView)get(a,"photoGrid");check((int)get(a,"browseMode")==1,"photo Back destination");check(get(a,"browseFilter")==all,"filter lost");check(position[0]==grid.getFirstVisiblePosition()&&position[1]==grid.getChildAt(0).getTop(),"scroll lost");set(a,"selecting",true);((Set<Integer>)get(a,"selectedPhotos")).add(25);});
      back(t);ui(t,()->check(!(boolean)get(a,"selecting")&&(int)get(a,"browseMode")==1&&((Set<?>)get(a,"selectedPhotos")).isEmpty(),"selection Back"));
      ui(t,()->set(a,"busy",true));back(t);ui(t,()->{check((int)get(a,"browseMode")==1,"busy Back escaped");set(a,"busy",false);});
      back(t);drain(t,a);ui(t,()->{check((int)get(a,"browseMode")==0&&get(a,"tag")!=null&&!a.isFinishing(),"tag detail Back");noBack(a.getWindow().getDecorView());});
      back(t);drain(t,a);ui(t,()->{check(get(a,"tag")==null&&!a.isFinishing(),"tag list Back");noBack(a.getWindow().getDecorView());});
      back(t);exited(t,a);
    } finally { ui(t,a::finish);file.delete(); }
    for(Class<? extends Activity> type:List.of(CloudActivity.class,SyncActivity.class)) {
      Activity screen=t.startActivitySync(new Intent(t.getTargetContext(),type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try { ui(t,()->noBack(screen.getWindow().getDecorView()));back(t);exited(t,screen); }
      finally {ui(t,screen::finish);}
    }
    return result+"PASS system Back: tag photo/filter/scroll, selection cancellation, busy guard, detail/list/root chain, Cloud and Sync exit. Generated private cache images; no tag writes/inference/login/sync.\n";
  }
}
