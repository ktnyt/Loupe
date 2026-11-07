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
import java.util.concurrent.atomic.AtomicInteger;

/** Disposable synthetic selections and callbacks. Never shares to a real recipient or edits media. */
final class ShareCapacityUiChecks {
  static String run(Instrumentation t,int width,float font)throws Exception {
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    File fixtures=new File(a.getCacheDir(),"capacity-fixtures-"+System.nanoTime());fixtures.mkdirs();
    File shots=new File(a.getCacheDir(),"consistency-checks");shots.mkdirs();
    List<PhotoRepository.Photo> photos=new ArrayList<>();List<File> files=new ArrayList<>();
    List<Dialog> dialogs=new ArrayList<>();
    try {
      GalleryScreenChecks.portraitFixture(t,a,width,font);GalleryScreenChecks.finishInitialIndex(t,a);
      for(int i=0;i<16;i++){
        File file=new File(fixtures,i+".jpg");files.add(file);
        Bitmap image=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.rgb(70+i*8,110+i*4,120+i*3));
        try(FileOutputStream out=new FileOutputStream(file)){image.compress(Bitmap.CompressFormat.JPEG,90,out);}finally{image.recycle();}
        photos.add(new PhotoRepository.Photo(Uri.fromFile(file),"合成容量"+i+".jpg","/fixture/",i<3?256L*1024*1024:file.length(),i,0));
      }
      List<String> ids=new ArrayList<>();for(int i=0;i<3;i++)ids.add(ArchiveStore.key(photos.get(i)));
      PhotoRanking ranking=new PhotoRanking(ids,"B:");while(!ranking.done())ranking=ranking.choose(true);PhotoRanking done=ranking;
      TagDetailChecks.ui(t,()->{
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        RankingResultChecks.set(a,"photos",photos);
        List<List<Integer>> bundles=new ArrayList<>();bundles.add(List.of(0,1,2));for(int i=3;i<photos.size();i++)bundles.add(List.of(i));
        RankingResultChecks.set(a,"bundles",bundles);
        Map<List<Integer>,PhotoRanking> ranks=(Map<List<Integer>,PhotoRanking>)RankingResultChecks.get(a,"rankCache");ranks.clear();ranks.put(List.of(0,1,2),done);
        Set<String> selected=(Set<String>)RankingResultChecks.get(a,"selectedPhotos");selected.clear();for(int i=0;i<3;i++)selected.add(photos.get(i).uri.toString());
        RankingResultChecks.set(a,"selecting",true);RankingResultChecks.set(a,"bestOnly",false);RankingResultChecks.set(a,"rankFilter",0);
        RankingResultChecks.set(a,"albumHome",false);RankingResultChecks.set(a,"albumFolder","/fixture/嵐山 10.18");RankingResultChecks.call(a,"gallery");
      });
      GalleryScreenChecks.settle(t,a);
      TagDetailChecks.ui(t,()->{
        Button share=(Button)RankingResultChecks.get(a,"selectionShare");TagDetailChecks.require(!share.isEnabled(),"768 MiB disables share");
        AccessibilityNodeInfo node=share.createAccessibilityNodeInfo();try{TagDetailChecks.require(!node.isEnabled(),"AX disabled matches view");}finally{node.recycle();}
        TagDetailChecks.require(share.getContentDescription().toString().contains("上限を超えています"),"AX capacity reason");
        Object dialog=RankingResultChecks.get(a,"shareDialog");share.performClick();TagDetailChecks.require(RankingResultChecks.get(a,"shareDialog")==dialog,"disabled action cannot open menu");
        View root=(View)RankingResultChecks.get(a,"root");TagDetailChecks.inspect(root);fullyVisibleActions(a);TextView badge=root.findViewWithTag("share-capacity");TagDetailChecks.require(badge.getText().toString().equals("768 / 512 MiB"),"visible size matches disabled state");
      });
      String prefix="capacity-"+width+"-"+font;
      ConsistencyLayoutChecks.captureHardware(t,a,(View)RankingResultChecks.get(a,"root"),shots,prefix+"-over");
      TagDetailChecks.ui(t,()->{
        ((Set<String>)RankingResultChecks.get(a,"selectedPhotos")).remove(photos.get(2).uri.toString());RankingResultChecks.call(a,"updateSelection");
        TagDetailChecks.require(((Button)RankingResultChecks.get(a,"selectionShare")).isEnabled(),"removing one restores exact-limit action");
        TextView badge=((View)RankingResultChecks.get(a,"root")).findViewWithTag("share-capacity");TagDetailChecks.require(badge.getText().toString().equals("512 / 512 MiB"),"exact limit displayed");
        ((Set<String>)RankingResultChecks.get(a,"selectedPhotos")).add(photos.get(2).uri.toString());RankingResultChecks.set(a,"bestOnly",true);RankingResultChecks.call(a,"updateSelection");
        TagDetailChecks.require(((Button)RankingResultChecks.get(a,"selectionShare")).isEnabled(),"best-only resolves smaller actual share targets");
        TagDetailChecks.inspect((View)RankingResultChecks.get(a,"root"));fullyVisibleActions(a);
      });
      ConsistencyLayoutChecks.captureHardware(t,a,(View)RankingResultChecks.get(a,"root"),shots,prefix+"-fit");
      AtomicInteger chosen=new AtomicInteger();
      TagDetailChecks.ui(t,()->{
        ShareUi.Menu menu=new ShareUi.Menu(a,photos.subList(0,3),List.of(new ShareTargets.Target("fixture/.Jpeg","JPEG先",false),new ShareTargets.Target("fixture/.Raw","元形式先",true)),0,Set.of(),(raw,target)->chosen.incrementAndGet());dialogs.add(menu);
        menu.capacity(new ShareCapacityChecks.Snapshot(new ShareCapacity.Result(ShareCapacity.State.FIT,300L*1024*1024),new ShareCapacity.Result(ShareCapacity.State.OVER,768L*1024*1024)));menu.show();
        menu.original.performClick();TagDetailChecks.require(!menu.raw&&!menu.original.isEnabled()&&menu.jpeg.isEnabled(),"over original disabled; fitting JPEG remains usable");
        ViewGroup destinations=(ViewGroup)menu.content.getChildAt(menu.content.getChildCount()-2);destinations.getChildAt(1).performClick();TagDetailChecks.require(chosen.get()==0&&menu.isShowing(),"oversized saved target does nothing");
        destinations.getChildAt(0).performClick();TagDetailChecks.require(chosen.get()==1&&!menu.isShowing(),"fitting saved target proceeds");
      });
      if(width==320&&font==1.3f)TagDetailChecks.ui(t,()->{
        for(int selected=0;selected<4;selected++){
          PhotoListUi.Filters filters=new PhotoListUi.Filters(a);String[] names={"すべて","1位","順位が未決定","順位決定済み"};
          for(int i=0;i<4;i++)filters.addView(PhotoListUi.chip(a,names[i],i==selected,()->{}),new ViewGroup.LayoutParams(-2,-2));
          filters.measure(View.MeasureSpec.makeMeasureSpec(GalleryUi.dp(a,width),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
          filters.layout(0,0,filters.getMeasuredWidth(),filters.getMeasuredHeight());
          TagDetailChecks.require(filters.getMeasuredHeight()<=GalleryUi.dp(a,100),"all selected filters fit two rows");TagDetailChecks.inspect(filters);
          TagDetailChecks.require(filters.getChildAt(2).getTop()==filters.getChildAt(3).getTop(),"long filters share second row");
        }
      });
      stale(t,a,photos.get(0));
      TagDetailChecks.ui(t,()->{
        PhotoRepository.Photo old=photos.get(3);photos.set(3,new PhotoRepository.Photo(old.uri,old.name,old.folder,ShareCapacity.LIMIT+1,old.modified,0));
        Map<List<Integer>,PhotoRanking> ranks=(Map<List<Integer>,PhotoRanking>)RankingResultChecks.get(a,"rankCache");ranks.put(List.of(0,1,2),new PhotoRanking(ids,"B:"));
        ((Set<String>)RankingResultChecks.get(a,"selectedPhotos")).add(old.uri.toString());RankingResultChecks.call(a,"updateSelection");
        Button sort=(Button)RankingResultChecks.get(a,"selectionSort");
        TagDetailChecks.require(sort.isEnabled()&&!((Button)RankingResultChecks.get(a,"selectionShare")).isEnabled(),"sort remains available despite oversized eligible selection");
        sort.performClick();Dialog dialog=(Dialog)RankingResultChecks.get(a,"shareDialog");dialogs.add(dialog);
        TagDetailChecks.require(dialog instanceof ShareUi.Confirmation&&dialog.isShowing(),"sort can open unresolved confirmation despite share capacity");dialog.dismiss();
      });
      return "PASS share capacity: selection over/exact/reduced/best-only, AX, disabled taps, per-format saved targets, stale result and unresolved sort; "+width+"dp font"+font;
    } finally {
      TagDetailChecks.ui(t,()->{for(Dialog dialog:dialogs)if(dialog.isShowing())dialog.dismiss();a.finish();});
      for(File file:files)file.delete();fixtures.delete();
    }
  }
  private static void fullyVisibleActions(MainActivity a){
    View root=(View)RankingResultChecks.get(a,"root");
    for(View action:new View[]{(View)RankingResultChecks.get(a,"selectionShare"),(View)RankingResultChecks.get(a,"selectionSort"),(View)RankingResultChecks.get(a,"selectionAll"),TagDetailChecks.button(root,"選択を終了")}){
      Rect visible=new Rect();TagDetailChecks.require(action!=null&&action.getLocalVisibleRect(visible)&&visible.left==0&&visible.top==0&&visible.right==action.getWidth()&&visible.bottom==action.getHeight(),"selection action fully visible without scrolling");
    }
  }
  private static void stale(Instrumentation t,MainActivity a,PhotoRepository.Photo jpeg)throws Exception{
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
    AtomicInteger callbacks=new AtomicInteger();ShareCapacityChecks[] check={null};
    PhotoRepository.Photo raw=new PhotoRepository.Photo(jpeg.uri,"deferred.orf","fixture/",256L*1024*1024,0,0);
    TagDetailChecks.ui(t,()->{check[0]=new ShareCapacityChecks(callbacks::incrementAndGet,photo->{entered.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}finished.countDown();return 800L*1024*1024;});check[0].request(List.of(raw));});
    try{
      TagDetailChecks.require(entered.await(10,TimeUnit.SECONDS),"old local check entered");
      TagDetailChecks.ui(t,()->TagDetailChecks.require(check[0].request(List.of(jpeg)).available.state==ShareCapacity.State.FIT,"new exact-limit JPEG selection fits"));
      release.countDown();TagDetailChecks.require(finished.await(10,TimeUnit.SECONDS),"old checker finished");t.waitForIdleSync();Thread.sleep(50);t.waitForIdleSync();
      TagDetailChecks.ui(t,()->{TagDetailChecks.require(callbacks.get()==0,"old completion cannot refresh new selection");TagDetailChecks.require(check[0].snapshot(List.of(raw)).jpeg.state==ShareCapacity.State.UNKNOWN,"old sizes not cached into new generation");});
    }finally{release.countDown();TagDetailChecks.ui(t,()->check[0].close());}
  }
}
