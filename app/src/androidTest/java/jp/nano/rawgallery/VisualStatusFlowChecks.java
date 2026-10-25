package jp.nano.rawgallery;
import static jp.nano.rawgallery.AlbumTagEntryChecks.*;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Drives real share sheets with an isolated failing writer; never starts a recipient. */
final class VisualStatusFlowChecks {
  static void click(Instrumentation t,String label)throws Exception {
    long end=android.os.SystemClock.uptimeMillis()+5000;
    while(android.os.SystemClock.uptimeMillis()<end){
      AccessibilityNodeInfo root=t.getUiAutomation().getRootInActiveWindow();
      if(root!=null)for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText(label))
        if(label.contentEquals(n.getText()==null?"":n.getText())&&n.isClickable()&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK)){t.waitForIdleSync();return;}
      Thread.sleep(80);
    }throw new AssertionError("Missing action "+label);
  }
  static String run(Instrumentation t,MainActivity a)throws Exception {
    CountDownLatch release=new CountDownLatch(1);AtomicInteger attempts=new AtomicInteger();
    File dir=new File(a.getCacheDir(),"status-share-"+UUID.randomUUID());
    PhotoRepository.Photo photo=new PhotoRepository.Photo(Uri.fromFile(new File(dir,"missing.jpg")),"synthetic.jpg","",0,0,0);
    List<PhotoRepository.Photo> selection=List.of(photo);
    try {
      ((ExecutorService)field(a,"scans")).submit(()->{}).get(10,TimeUnit.SECONDS);t.waitForIdleSync();
      main(t,()->{
        ((Set<String>)field(a,"selectedPhotos")).add(photo.uri.toString());
        set(a,"sharePreparation",new SharePreparation(dir,(p,raw,out)->{attempts.incrementAndGet();release.await();throw new IOException("synthetic failure");}));
        Method export=MainActivity.class.getDeclaredMethod("export",List.class,boolean.class,ShareTargets.Target.class);export.setAccessible(true);export.invoke(a,selection,false,null);return true;
      });
      waitFor(t,"writer started",()->attempts.get()==1);Thread.sleep(500);
      click(t,"中止");release.countDown();Thread.sleep(500);
      main(t,()->{if(!((Set<?>)field(a,"selectedPhotos")).contains(photo.uri.toString()))throw new AssertionError("cancel lost selection");
        Method export=MainActivity.class.getDeclaredMethod("export",List.class,boolean.class,ShareTargets.Target.class);export.setAccessible(true);export.invoke(a,selection,false,null);return true;});
      click(t,"再試行");waitFor(t,"retry writer",()->attempts.get()==3);
      click(t,"選択に戻る");
      main(t,()->{if(!((Set<?>)field(a,"selectedPhotos")).contains(photo.uri.toString()))throw new AssertionError("failure lost selection");return true;});
      return "PASS actual share preparation cancel -> selection retained; injected failure -> retry calls writer again -> return retains selection. No recipient or external transfer.\n";
    } finally {release.countDown();File[] files=dir.listFiles();if(files!=null)for(File f:files)f.delete();dir.delete();}
  }
}
