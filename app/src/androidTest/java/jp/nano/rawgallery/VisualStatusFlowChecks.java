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
  static android.view.View failed(android.view.View view){
    if(view instanceof StatusView&&view.isClickable()&&view.getVisibility()==android.view.View.VISIBLE)return view;
    if(view instanceof android.widget.Button&&"再試行".contentEquals(((android.widget.Button)view).getText()))return view;
    if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++){android.view.View v=failed(((android.view.ViewGroup)view).getChildAt(i));if(v!=null)return v;}return null;
  }
  static AccessibilityNodeInfo input(Instrumentation t){AccessibilityNodeInfo root=t.getUiAutomation().getRootInActiveWindow();return inputNode(root);}
  static AccessibilityNodeInfo inputNode(AccessibilityNodeInfo n){if(n==null)return null;if("android.widget.EditText".contentEquals(n.getClassName()))return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo found=inputNode(n.getChild(i));if(found!=null)return found;}return null;}
  static android.view.View photoView(android.view.View view){
    if(view instanceof ZoomImageView)return view;
    if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++){android.view.View found=photoView(((android.view.ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;
  }
  static android.view.View mainView(Instrumentation t,MainActivity a)throws Exception {android.view.View[] result={null};main(t,()->{result[0]=failed(a.getWindow().getDecorView());return true;});return result[0];}
  static void assertPhotoColor(MainActivity a,int expected)throws Exception {
    android.graphics.Bitmap image=(android.graphics.Bitmap)field(photoView(a.getWindow().getDecorView()),"bitmap");if(image==null)throw new AssertionError("photo bitmap missing");int actual=image.getPixel(image.getWidth()/2,image.getHeight()/2);
    for(int shift:new int[]{0,8,16})if(Math.abs(((actual>>shift)&255)-((expected>>shift)&255))>5)throw new AssertionError("wrong photo pixel "+Integer.toHexString(actual));
  }
  static void assertNoStatus(android.view.View view){
    if(view instanceof StatusView&&view.isShown())throw new AssertionError("visible stale status");
    if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++)assertNoStatus(((android.view.ViewGroup)view).getChildAt(i));
  }
  static void swipe(Instrumentation t,MainActivity a,boolean left)throws Exception {
    int[] bounds=new int[4];main(t,()->{android.view.View view=photoView(a.getWindow().getDecorView());if(view==null)throw new AssertionError("photo missing");int[] xy=new int[2];view.getLocationOnScreen(xy);bounds[0]=xy[0];bounds[1]=xy[1];bounds[2]=view.getWidth();bounds[3]=view.getHeight();return true;});
    float from=bounds[0]+bounds[2]*(left?.8f:.2f),to=bounds[0]+bounds[2]*(left?.2f:.8f),y=bounds[1]+bounds[3]*.5f;
    long start=android.os.SystemClock.uptimeMillis();
    for(int i=0;i<=12;i++){
      long now=android.os.SystemClock.uptimeMillis();android.view.MotionEvent event=android.view.MotionEvent.obtain(start,now,i==0?0:i==12?1:2,from+(to-from)*i/12f,y,0);
      try{t.sendPointerSync(event);}finally{event.recycle();}Thread.sleep(12);
    }
    t.waitForIdleSync();
  }
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
      main(t,()->{android.widget.Button retry=(android.widget.Button)failed(a.getWindow().getDecorView());if(retry.getLayout()==null||retry.getLayout().getWidth()<retry.getPaint().measureText("再試行"))throw new AssertionError("retry label clipped");return true;});
      android.view.View content=a.findViewById(android.R.id.content);
      capture(t,a,content,content.getHeight(),"status-detail-failed");
      File secondFile=new File(dir,"second.jpg");dir.mkdirs();
      android.graphics.Bitmap orange=android.graphics.Bitmap.createBitmap(600,800,android.graphics.Bitmap.Config.ARGB_8888);orange.eraseColor(0xffc46c32);
      try(FileOutputStream out=new FileOutputStream(secondFile)){orange.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,out);}finally{orange.recycle();}
      PhotoRepository.Photo second=new PhotoRepository.Photo(Uri.fromFile(secondFile),dir.getName()+"-second.jpg","",secondFile.length(),0,0);
      android.view.View oldFailure=mainView(t,a);
      main(t,()->{set(a,"photos",new ArrayList<>(List.of(photo,second)));return true;});
      swipe(t,a,true);waitFor(t,"left swipe second photo",()->((Integer)field(a,"index"))==1&&field(a,"activePreview")!=null);
      Thread.sleep(1000);capture(t,a,content,content.getHeight(),"status-swipe-left");
      main(t,()->{assertPhotoColor(a,0xffc46c32);if(oldFailure.isAttachedToWindow()||failed(a.getWindow().getDecorView())!=null)throw new AssertionError("old failure after left swipe");assertNoStatus(a.getWindow().getDecorView());return true;});
      swipe(t,a,false);waitFor(t,"right swipe first photo failure",()->((Integer)field(a,"index"))==0&&failed(a.getWindow().getDecorView())!=null);
      capture(t,a,content,content.getHeight(),"status-swipe-right");
      File restored=new File(photo.uri.getPath());dir.mkdirs();
      try(FileOutputStream out=new FileOutputStream(restored)){out.write(jpeg);}
      main(t,()->{android.view.View retry=failed(a.getWindow().getDecorView());if(!retry.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null))throw new AssertionError("detail retry AX");return true;});
      waitFor(t,"detail recovered",()->field(a,"activePreview")!=null&&failed(a.getWindow().getDecorView())==null);
      Thread.sleep(900);main(t,()->{assertPhotoColor(a,0xff587c8c);return true;});capture(t,a,content,content.getHeight(),"status-detail-recovered");
      TagActivity tags=(TagActivity)t.startActivitySync(new Intent(t.getTargetContext(),TagActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        waitFor(t,"tag entry",()->button(tags.getWindow().getDecorView(),"新しいタグ")!=null);
        main(t,()->{button(tags.getWindow().getDecorView(),"新しいタグ").performClick();return true;});
        click(t,"作成");AccessibilityNodeInfo edit=input(t);if(edit==null||edit.getStateDescription()==null)throw new AssertionError("tag input error missing");
        android.os.Bundle value=new android.os.Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"synthetic-cancel-only");if(!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value))throw new AssertionError("tag input edit");
        Thread.sleep(250);edit=input(t);if(edit==null||edit.getStateDescription()!=null)throw new AssertionError("corrected input error remains");click(t,"キャンセル");
      }finally{main(t,()->{tags.finish();return true;});}
      return "PASS injected touch left/right swipes: missing original -> orange JPEG pixel verified, old failure detached and no visible status -> missing original -> retry -> blue JPEG pixel verified.\nPASS tag empty input error -> valid edit clears error -> cancel without persistence.\nPASS detail missing original -> persistent error -> actual AX retry after synthetic JPEG restore -> image recovered.\nPASS actual share preparation cancel -> selection retained; injected failure -> retry calls writer again -> return retains selection. No recipient or external transfer.\n";
    } finally {release.countDown();File[] files=dir.listFiles();if(files!=null)for(File f:files)f.delete();dir.delete();}
  }
}
