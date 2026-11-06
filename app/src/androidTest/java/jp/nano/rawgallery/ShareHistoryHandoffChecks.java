package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.lang.reflect.Method;
import java.util.*;

/** Actual chooser/direct handoff to the local test APK, with generated JPEGs only. */
final class ShareHistoryHandoffChecks {
  private static final String LABEL="Loupe 検証用共有先";
  static String run(Instrumentation t)throws Exception {
    Context context=t.getTargetContext();
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    List<File> copies=Collections.synchronizedList(new ArrayList<>());
    List<PhotoRepository.Photo> photos=new ArrayList<>();List<String> keys=new ArrayList<>();
    File fixture=new File(a.getCacheDir(),"handoff-"+UUID.randomUUID()+".jpg");
    Bitmap bitmap=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff718774);
    try(FileOutputStream out=new FileOutputStream(fixture)){bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);}bitmap.recycle();
    String folder="/synthetic/"+UUID.randomUUID();
    for(int i=0;i<3;i++){PhotoRepository.Photo p=new PhotoRepository.Photo(Uri.fromFile(fixture),"synthetic-"+i+".jpg",folder,fixture.length(),i,0);photos.add(p);keys.add(ArchiveStore.key(p));}
    try {
      GalleryScreenChecks.finishInitialIndex(t,a);
      TagDetailChecks.ui(t,()->{
        a.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        RankingResultChecks.set(a,"sharePreparation",new SharePreparation(new File(a.getCacheDir(),"shares"),(photo,raw,destination)->{java.nio.file.Files.copy(fixture.toPath(),destination.toPath());copies.add(destination);}));
      });
      String targetPackage=t.getContext().getPackageName();
      ComponentName component=new ComponentName(targetPackage,ShareHistoryTargetActivity.class.getName());
      try(ShareHistoryStore db=new ShareHistoryStore(context)){for(String key:keys)check(db.history(key).isEmpty(),"fixture keys unique");}
      export(t,a,photos.subList(0,2),new ShareTargets.Target(component.flattenToString(),LABEL,false));
      awaitText(t,"Loupe test receipt 2 OK",15000);
      awaitHistory(context,keys.subList(0,2),targetPackage,1,0);
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);t.waitForIdleSync();
      // A cancelled system chooser must not create history for its distinct third photo.
      export(t,a,List.of(photos.get(2)),null);
      awaitChooser(t,15000);
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);t.waitForIdleSync();Thread.sleep(250);
      try(ShareHistoryStore db=new ShareHistoryStore(context)){check(db.history(keys.get(2)).isEmpty(),"cancel adds no history");}
      // Repeat the same two photos through the actual OS picker; package entries remain unique.
      long chooserStarted=System.currentTimeMillis();
      export(t,a,photos.subList(0,2),null);
      awaitChooser(t,15000);
      choose(t,LABEL);
      awaitText(t,"Loupe test receipt 2 OK",15000);
      awaitHistory(context,keys.subList(0,2),targetPackage,1,chooserStarted);
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);t.waitForIdleSync();
      try(ShareHistoryStore reopened=new ShareHistoryStore(context)){for(String key:keys.subList(0,2))check(reopened.history(key).size()==1,"reopen retains history");check(reopened.history(keys.get(2)).isEmpty(),"unshared photo isolated");}
      return "PASS share-history-handoff: actual direct launch and Android chooser to local test APK; two generated JPEG URI grants read; cancelled chooser no history; package dedup and DB reopen. No external posting or real photographs.\n";
    } finally {
      TagDetailChecks.ui(t,()->a.finish());
      try(ShareHistoryStore db=new ShareHistoryStore(context)){
        android.database.sqlite.SQLiteDatabase sql=db.getWritableDatabase();
        for(String key:keys){sql.delete("history","photo=?",new String[]{key});sql.delete("sessions","id IN (SELECT session FROM session_photos WHERE photo=?)",new String[]{key});sql.delete("session_photos","photo=?",new String[]{key});}
      }
      fixture.delete();for(File copy:copies)copy.delete();
    }
  }
  private static void export(Instrumentation t,MainActivity a,List<PhotoRepository.Photo> photos,ShareTargets.Target target)throws Exception {
    Method method=MainActivity.class.getDeclaredMethod("export",List.class,boolean.class,ShareTargets.Target.class);method.setAccessible(true);
    TagDetailChecks.ui(t,()->{try{method.invoke(a,List.copyOf(photos),false,target);}catch(Exception e){throw new RuntimeException(e);}});
  }
  private static void awaitHistory(Context c,List<String> keys,String pkg,int expected,long minimumTime)throws Exception {
    long end=SystemClock.uptimeMillis()+8000;
    do{boolean good=true;try(ShareHistoryStore db=new ShareHistoryStore(c)){for(String key:keys){List<ShareHistoryStore.Entry> entries=db.history(key);good&=entries.size()==expected&&entries.get(0).packageName.equals(pkg)&&entries.get(0).handedOffAt>=minimumTime;}}if(good)return;Thread.sleep(40);}while(SystemClock.uptimeMillis()<end);
    throw new AssertionError("handoff history not saved");
  }
  private static AccessibilityNodeInfo find(AccessibilityNodeInfo node,String text){if(node==null)return null;if(text.contentEquals(node.getText())||text.contentEquals(node.getContentDescription()))return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=find(node.getChild(i),text);if(found!=null)return found;}return null;}
  private static void awaitText(Instrumentation t,String text,long timeout)throws Exception {long end=SystemClock.uptimeMillis()+timeout;do{if(find(t.getUiAutomation().getRootInActiveWindow(),text)!=null)return;Thread.sleep(60);}while(SystemClock.uptimeMillis()<end);throw new AssertionError("test recipient receipt missing");}
  private static void awaitChooser(Instrumentation t,long timeout)throws Exception {long end=SystemClock.uptimeMillis()+timeout;do{AccessibilityNodeInfo root=t.getUiAutomation().getRootInActiveWindow();if(root!=null&&root.getPackageName()!=null&&!root.getPackageName().toString().startsWith("jp.nano.rawgallery"))return;Thread.sleep(60);}while(SystemClock.uptimeMillis()<end);throw new AssertionError("OS chooser not visible");}
  private static boolean scroll(AccessibilityNodeInfo node){if(node==null)return false;for(int i=0;i<node.getChildCount();i++)if(scroll(node.getChild(i)))return true;return node.isScrollable()&&node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);}
  private static void choose(Instrumentation t,String label)throws Exception {
    for(int pass=0;pass<12;pass++){
      AccessibilityNodeInfo root=t.getUiAutomation().getRootInActiveWindow(),target=find(root,label);
      if(target!=null){while(target!=null&&!target.isClickable())target=target.getParent();check(target!=null&&target.performAction(AccessibilityNodeInfo.ACTION_CLICK),"test target click");return;}
      if(!scroll(root))break;Thread.sleep(150);
    }
    throw new AssertionError("test-only share target not found; no other target selected");
  }
  private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
