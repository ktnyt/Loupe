package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static jp.nano.rawgallery.TagDetailChecks.ui;
import static jp.nano.rawgallery.TagDetailChecks.require;
import static jp.nano.rawgallery.RankingResultChecks.*;

/** Synthetic photos and isolated preferences; never modifies the user's ranking journal. */
final class SingleBestChecks {
  private static Button toggle(MainActivity a) {
    PhotoViewerUi v=ViewerScreenChecks.viewer(a);
    return (Button)v.findViewWithTag("viewer-action-1位");
  }
  private static void idle(Instrumentation t,MainActivity a)throws Exception {
    ((ExecutorService)get(a,"actions")).submit(()->{}).get(10,TimeUnit.SECONDS);
    t.waitForIdleSync();ConsistencyLayoutChecks.frame(t,(View)get(a,"root"));
  }
  private static void gallery(Instrumentation t,MainActivity a,int filter)throws Exception {
    ui(t,()->{set(a,"rankFilter",filter);call(a,"gallery");});
    GalleryScreenChecks.settle(t,a);
  }
  @SuppressWarnings("unchecked") private static List<List<Integer>> shown(MainActivity a){return (List<List<Integer>>)get(a,"displayed");}
  private static void capture(Instrumentation t,MainActivity a,File dir,String name)throws Exception {
    ConsistencyLayoutChecks.captureHardware(t,a,(View)get(a,"root"),dir,name);
  }
  static String run(Instrumentation t,int width,float font)throws Exception {
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    List<File> files=new ArrayList<>();
    SharedPreferences prefs=a.getSharedPreferences("single-best-checks",0);
    try {
      ((ExecutorService)get(a,"scans")).submit(()->{}).get(20,TimeUnit.SECONDS);
      GalleryScreenChecks.portraitFixture(t,a,width,font);
      require(prefs.edit().clear().commit(),"reset isolated rankings");
      RankingStore store=new RankingStore(prefs);
      List<PhotoRepository.Photo> photos=new ArrayList<>();
      for(int i=0;i<4;i++) {
        File f=new File(a.getCacheDir(),"single-best-fixture-"+i+".jpg");files.add(f);
        Bitmap bitmap=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(bitmap);Paint p=new Paint(3);
        p.setShader(new LinearGradient(0,0,600,400,0xff6486a5,0xff28353e,Shader.TileMode.CLAMP));c.drawRect(0,0,600,400,p);
        p.setShader(null);p.setColor(0xffe5c591);c.drawCircle(380,150,65,p);
        try(FileOutputStream out=new FileOutputStream(f)){bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);}bitmap.recycle();
        photos.add(new PhotoRepository.Photo(Uri.fromFile(f),"検査写真"+i+".jpg","/single-best",f.length(),1792298040L+i,1792298040000L+i*1000));
      }
      List<String> burst=List.of(ArchiveStore.key(photos.get(2)),ArchiveStore.key(photos.get(3)));
      PhotoRanking ranked=store.load(burst).choose(true);require(store.save(ranked),"seed existing burst");
      ui(t,()->{a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);set(a,"photos",photos);set(a,"bundles",List.of(List.of(0),List.of(1),List.of(2,3)));set(a,"rankingStore",store);((Map<?,?>)get(a,"rankCache")).clear();set(a,"burstMembers",null);set(a,"selecting",false);set(a,"albumFolder","/single-best");});
      gallery(t,a,1);ui(t,()->require(shown(a).size()==1&&!shown(a).contains(List.of(0)),"unmarked singleton excluded from best"));
      gallery(t,a,0);ViewerScreenChecks.open(t,a,0);
      File dir=new File(a.getCacheDir(),"consistency-checks");dir.mkdirs();String prefix="single-best-"+width+"-"+font;
      ui(t,()->require(toggle(a)!=null,"initial toggle exists"));
      ui(t,()->require(!toggle(a).isSelected(),"initial toggle unmarked"));
      ui(t,()->require(toggle(a).createAccessibilityNodeInfo().isCheckable(),"AX checkable"));
      ui(t,()->require(!toggle(a).createAccessibilityNodeInfo().isChecked(),"AX unchecked"));
      ui(t,()->ViewerScreenChecks.toolbar(ViewerScreenChecks.viewer(a),8,width<=320));
      capture(t,a,dir,prefix+"-off");
      int[] before=new int[4];ui(t,()->{Button b=toggle(a);before[0]=b.getLeft();before[1]=b.getTop();before[2]=b.getWidth();before[3]=b.getHeight();b.performClick();require(!b.isEnabled()&&"保存中".contentEquals(b.getStateDescription()),"disable and announce saving during commit");});idle(t,a);
      ui(t,()->{Button b=toggle(a);require(b.isSelected()&&b.isEnabled()&&b.createAccessibilityNodeInfo().isChecked(),"marked state and AX");require(b.getLeft()==before[0]&&b.getTop()==before[1]&&b.getWidth()==before[2]&&b.getHeight()==before[3],"stable toggle geometry");});
      require(new RankingStore(prefs).singleBest(ArchiveStore.key(photos.get(0))),"new store reads saved mark");
      require(!store.singleBest(ArchiveStore.key(photos.get(1)))&&store.load(burst).journal.equals(ranked.journal),"other photo and burst unchanged");
      capture(t,a,dir,prefix+"-on");
      ui(t,()->a.onBackPressed());gallery(t,a,1);
      ui(t,()->require(shown(a).contains(List.of(0))&&!shown(a).contains(List.of(1))&&shown(a).size()==2,"best filter includes only marked single and burst winner"));
      capture(t,a,dir,prefix+"-filter");
      gallery(t,a,2);ui(t,()->require(shown(a).equals(List.of(List.of(1))),"unranked filter"));
      gallery(t,a,3);ui(t,()->require(shown(a).contains(List.of(0))&&shown(a).contains(List.of(2,3)),"ranked filter"));
      gallery(t,a,0);ViewerScreenChecks.open(t,a,0);ui(t,()->{require(toggle(a).isSelected(),"reopen keeps mark");toggle(a).performClick();});idle(t,a);
      require(!new RankingStore(prefs).singleBest(ArchiveStore.key(photos.get(0))),"unmark persisted");
      ui(t,()->a.onBackPressed());gallery(t,a,1);ui(t,()->require(!shown(a).contains(List.of(0)),"unmarked disappears from best"));
      // A queued write must update a viewer reopened before the write begins.
      gallery(t,a,0);ViewerScreenChecks.open(t,a,0);
      CountDownLatch releaseViewer=new CountDownLatch(1),startedViewer=new CountDownLatch(1);
      ((ExecutorService)get(a,"actions")).execute(()->{startedViewer.countDown();try{releaseViewer.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
      require(startedViewer.await(5,TimeUnit.SECONDS),"viewer blocker active");
      try {
        ui(t,()->toggle(a).performClick());
        ui(t,()->a.onBackPressed());gallery(t,a,0);
        ui(t,()->{try{java.lang.reflect.Method open=MainActivity.class.getDeclaredMethod("showPhoto",int.class);open.setAccessible(true);open.invoke(a,0);}catch(Exception e){throw new RuntimeException(e);}});
        ui(t,()->require(!toggle(a).isSelected(),"reopened viewer reads queued old value"));
      } finally { releaseViewer.countDown(); }
      idle(t,a);ui(t,()->{require(toggle(a).isSelected(),"queued write refreshes reopened viewer");toggle(a).performClick();});idle(t,a);
      require(!store.singleBest(ArchiveStore.key(photos.get(0))),"reopened viewer can unmark without stale retries");
      // The same completion must rebuild the current filter while the write was pending.
      CountDownLatch releaseFilter=new CountDownLatch(1),startedFilter=new CountDownLatch(1);
      ((ExecutorService)get(a,"actions")).execute(()->{startedFilter.countDown();try{releaseFilter.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
      require(startedFilter.await(5,TimeUnit.SECONDS),"filter blocker active");
      try {
        ui(t,()->toggle(a).performClick());ui(t,()->a.onBackPressed());gallery(t,a,1);
        ui(t,()->require(!shown(a).contains(List.of(0)),"filter sees old value while queued"));
      } finally { releaseFilter.countDown(); }
      idle(t,a);GalleryScreenChecks.settle(t,a);ui(t,()->require(shown(a).contains(List.of(0)),"queued write refreshes current best filter"));
      // A stale expectation from another writer also recovers on this same visible button.
      gallery(t,a,0);ViewerScreenChecks.open(t,a,0);
      require(store.saveSingleBest(ArchiveStore.key(photos.get(0)),true,false),"external writer changes value");
      ui(t,()->toggle(a).performClick());idle(t,a);
      ui(t,()->{require(!toggle(a).isSelected()&&toggle(a).isEnabled(),"stale failure refreshes saved state");toggle(a).performClick();});idle(t,a);
      require(store.singleBest(ArchiveStore.key(photos.get(0))),"retry after stale rejection succeeds");
      // Inject one failed commit after SharedPreferences publishes the new value.
      boolean[] failCommit={true};int[] readsToFail={0};
      SharedPreferences failing=(SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
        if(method.getName().equals("getString")&&readsToFail[0]>0){readsToFail[0]--;throw new IllegalStateException("Synthetic read failure");}
        Object result=method.invoke(prefs,args);
        if(!method.getName().equals("edit"))return result;
        SharedPreferences.Editor actual=(SharedPreferences.Editor)result;
        return java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(editor,operation,values)->{
          Object output=operation.invoke(actual,values);
          if(operation.getName().equals("commit")&&failCommit[0]){failCommit[0]=false;return false;}
          return output==actual?editor:output;
        });
      });
      ui(t,()->set(a,"rankingStore",new RankingStore(failing)));
      gallery(t,a,0);ViewerScreenChecks.open(t,a,0);ui(t,()->toggle(a).performClick());idle(t,a);
      require(store.singleBest(ArchiveStore.key(photos.get(0))),"failed commit restores actual preference");
      ui(t,()->{require(toggle(a).isSelected()&&toggle(a).getContentDescription().toString().contains("保存できません"),"failed save keeps old state and exposes retry");toggle(a).performClick();});idle(t,a);
      require(!store.singleBest(ArchiveStore.key(photos.get(0))),"failed save retry unmarks");
      readsToFail[0]=2;ViewerScreenChecks.open(t,a,0);
      ui(t,()->require(toggle(a).getContentDescription().toString().contains("読み込めません"),"failed read exposes retry"));
      ui(t,()->{toggle(a).performClick();require("読み込み中".contentEquals(toggle(a).getStateDescription()),"read retry busy state describes loading");});idle(t,a);
      ui(t,()->require(toggle(a).getContentDescription().toString().contains("読み込めません"),"failed read retry remains read-only until a successful read"));
      require(readsToFail[0]==0,"initial and first retry reads were injected failures");
      ui(t,()->toggle(a).performClick());idle(t,a);
      require(!store.singleBest(ArchiveStore.key(photos.get(0))),"read retry never writes a mark");
      ui(t,()->{require(!toggle(a).isSelected()&&toggle(a).isEnabled(),"read retry loads existing state");toggle(a).performClick();});idle(t,a);
      require(store.singleBest(ArchiveStore.key(photos.get(0))),"explicit click after read retry writes");
      ui(t,()->set(a,"rankingStore",store));
      gallery(t,a,0);ViewerScreenChecks.open(t,a,2);ui(t,()->require(toggle(a)==null,"burst member has no singleton toggle"));
      return "PASS single-best "+width+"dp/font"+font+": mark/unmark, reload, AX, stable geometry, filters, queued navigation/filter and stale retry, failed commit/read recovery, burst isolation; screenshots in consistency-checks\n";
    } finally {
      ui(t,()->a.finish());prefs.edit().clear().commit();for(File f:files)f.delete();
    }
  }
}
