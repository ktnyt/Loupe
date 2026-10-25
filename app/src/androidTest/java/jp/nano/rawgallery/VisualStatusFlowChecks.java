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
  static StatusView failed(android.view.View view){
    if(view instanceof StatusView&&view.isClickable()&&view.getVisibility()==android.view.View.VISIBLE)return (StatusView)view;
    if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++){StatusView v=failed(((android.view.ViewGroup)view).getChildAt(i));if(v!=null)return v;}return null;
  }
  static AccessibilityNodeInfo input(Instrumentation t){AccessibilityNodeInfo root=t.getUiAutomation().getRootInActiveWindow();return inputNode(root);}
  static AccessibilityNodeInfo inputNode(AccessibilityNodeInfo n){if(n==null)return null;if("android.widget.EditText".contentEquals(n.getClassName()))return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo found=inputNode(n.getChild(i));if(found!=null)return found;}return null;}
  static String run(Instrumentation t,MainActivity a)throws Exception {
    CountDownLatch release=new CountDownLatch(1);AtomicInteger attempts=new AtomicInteger();
    File dir=new File(a.getCacheDir(),"status-share-"+UUID.randomUUID());
    android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(600,800,android.graphics.Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff587c8c);
    ByteArrayOutputStream encoded=new ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,encoded);bitmap.recycle();byte[] jpeg=encoded.toByteArray();
    PhotoRepository.Photo photo=new PhotoRepository.Photo(Uri.fromFile(new File(dir,"missing.jpg")),dir.getName()+".jpg","",jpeg.length,0,0);
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
      main(t,()->{set(a,"photos",new ArrayList<>(selection));set(a,"burstMembers",null);Method show=MainActivity.class.getDeclaredMethod("showPhoto",int.class);show.setAccessible(true);show.invoke(a,0);return true;});
      waitFor(t,"detail failure recovery",()->failed(a.getWindow().getDecorView())!=null);
      android.view.View content=a.findViewById(android.R.id.content);
      capture(t,a,content,content.getHeight(),"status-detail-failed");
      File restored=new File(photo.uri.getPath());dir.mkdirs();
      try(FileOutputStream out=new FileOutputStream(restored)){out.write(jpeg);}
      main(t,()->{StatusView retry=failed(a.getWindow().getDecorView());if(!retry.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null))throw new AssertionError("detail retry AX");return true;});
      waitFor(t,"detail recovered",()->field(a,"activePreview")!=null&&failed(a.getWindow().getDecorView())==null);
      Thread.sleep(900);capture(t,a,content,content.getHeight(),"status-detail-recovered");
      TagActivity tags=(TagActivity)t.startActivitySync(new Intent(t.getTargetContext(),TagActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        waitFor(t,"tag entry",()->button(tags.getWindow().getDecorView(),"新しいタグ")!=null);
        main(t,()->{button(tags.getWindow().getDecorView(),"新しいタグ").performClick();return true;});
        click(t,"作成");AccessibilityNodeInfo edit=input(t);if(edit==null||edit.getStateDescription()==null)throw new AssertionError("tag input error missing");
        android.os.Bundle value=new android.os.Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"synthetic-cancel-only");if(!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value))throw new AssertionError("tag input edit");
        Thread.sleep(250);edit=input(t);if(edit==null||edit.getStateDescription()!=null)throw new AssertionError("corrected input error remains");click(t,"キャンセル");
      }finally{main(t,()->{tags.finish();return true;});}
      return "PASS tag empty input error -> valid edit clears error -> cancel without persistence.\nPASS detail missing original -> persistent error -> actual AX retry after synthetic JPEG restore -> image recovered.\nPASS actual share preparation cancel -> selection retained; injected failure -> retry calls writer again -> return retains selection. No recipient or external transfer.\n";
    } finally {release.countDown();File[] files=dir.listFiles();if(files!=null)for(File f:files)f.delete();dir.delete();}
  }
}
