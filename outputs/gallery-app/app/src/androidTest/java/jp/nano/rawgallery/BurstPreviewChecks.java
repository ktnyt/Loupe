package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** Deterministic ordering/error tests; optional read-only real RAW6 reproduction. */
final class BurstPreviewChecks {
  static String run(Instrumentation test, boolean real) throws Exception {
    MainActivity a=(MainActivity)test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    Thread.sleep(700);
    if(real) return real(test,a);
    List<PhotoRepository.Photo> photos=new ArrayList<>();
    for(int i=0;i<6;i++) photos.add(new PhotoRepository.Photo(Uri.parse("file:///synthetic-"+i+".jpg"),"synthetic.jpg","",1,i,0));
    List<Integer> edges=new ArrayList<>(); List<Consumer<Boolean>> replies=new ArrayList<>();
    BurstPreviews[] controller=new BurstPreviews[1]; Button[] retry=new Button[1];
    boolean[] active={true};
    Bitmap bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.BLUE);
    test.runOnMainSync(() -> {
      LinearLayout root=new LinearLayout(a); root.setOrientation(1); FrameLayout stage=new FrameLayout(a);
      ImageView hero=new DiagnosticImageView(a); stage.addView(hero,new FrameLayout.LayoutParams(-1,-1));
      root.addView(stage,new LinearLayout.LayoutParams(-1,400));
      LinearLayout strip=new LinearLayout(a); root.addView(strip);
      TextView progress=new TextView(a);root.addView(progress);retry[0]=new Button(a);root.addView(retry[0]);
      a.setContentView(root);
      controller[0]=new BurstPreviews(a,hero,stage,strip,progress,retry[0],photos,(v,p,e,done)-> {
        edges.add(e); replies.add(success -> { if(success)v.setImageBitmap(bitmap);done.accept(success); });
      },()->active[0],i->controller[0].select(i));
      controller[0].select(0);
      if(!edges.equals(Arrays.asList(1600)))throw new AssertionError("strip before hero");
      replies.get(0).accept(true);
      if(edges.size()!=1)throw new AssertionError("strip before draw");
    });
    DiagnosticChecks.waitFor(test,()->edges.size()==2);
    test.runOnMainSync(()->{
      if(edges.get(1)!=200)throw new AssertionError("strip edge");
      controller[0].select(3);
      if(edges.get(2)!=1600)throw new AssertionError("new selection not prioritized");
      replies.get(1).accept(false);
    });
    Thread.sleep(100);
    test.runOnMainSync(()->{
      if(edges.size()!=3)throw new AssertionError("strip continued while hero waiting");
      replies.get(2).accept(false);
      if(retry[0].getVisibility()!=android.view.View.VISIBLE)throw new AssertionError("missing retry");
      int beforeRetry=edges.size();
      retry[0].performClick();
      if(edges.size()!=beforeRetry+1 || edges.get(edges.size()-1)!=1600)
        throw new AssertionError("failed hero not retried");
      active[0]=false;
      int before=edges.size(); replies.get(replies.size()-1).accept(true);
      if(edges.size()!=before)throw new AssertionError("left screen still loads");
    });
    PhotoRepository repository=new PhotoRepository(a);
    if(repository.prepare(photos.get(0),()->true))throw new AssertionError("cancelled background generated");
    if(PreviewPreparationJob.local(new PhotoRepository.Photo(Uri.parse("archive://id"),"x","",1,1,0)))throw new AssertionError("archive included");
    String backgroundResult = BackgroundPreviewChecks.run(a);
    String listResult = GalleryLoadingChecks.run(test, a);
    test.runOnMainSync(a::finish);
    return listResult + backgroundResult + "PASS hero-before-strip/draw, selection priority, failure/retry visibility, stale screen, cancelled preparation, local-only\n";
  }
  private static void set(Object o,String name,Object value)throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
  private static String real(Instrumentation test,MainActivity a)throws Exception {
    GalleryIndex.Snapshot snapshot;
    try(GalleryIndex index=new GalleryIndex(a)){snapshot=index.load(null,"すべて");}
    List<PhotoRepository.Photo> chosen=null;
    File old=new File(a.getCacheDir(),"previews");
    PhotoRepository repository=new PhotoRepository(a);
    for(List<Integer> group:snapshot.bundles){
      if(group.size()!=6)continue;
      List<PhotoRepository.Photo> candidate=new ArrayList<>(); boolean suitable=true;
      for(int j=0;j<6;j++){
        PhotoRepository.Photo p=snapshot.photos.get(group.get(j)); candidate.add(p);
        if(p.isJpeg()||!PreviewPreparationJob.local(p)){suitable=false;break;}
        if(j>0&&(repository.prepared(p)||new File(old,PhotoRepository.previewKey(p)+".webp").isFile())){suitable=false;break;}
      }
      if(suitable){chosen=candidate;break;}
    }
    if(chosen==null)throw new AssertionError("No RAW6 with five uncached remaining photos; no cache deleted");
    repository.preview(chosen.get(0)); // Same investigated condition: hero hit, five remaining misses.
    List<PhotoRepository.Photo> members=chosen;
    Method gallery=MainActivity.class.getDeclaredMethod("gallery");gallery.setAccessible(true);
    test.runOnMainSync(()->{try{
      set(a,"photos",members);set(a,"burstMembers",Arrays.asList(0,1,2,3,4,5));set(a,"burstPosition",0);
      long id=DiagnosticLog.id();set(a,"burstRequest",id);DiagnosticLog.event("test.raw6.open",id);DiagnosticLog.event("burst.open",id,"count",6);
      gallery.invoke(a);
    }catch(Exception e){throw new RuntimeException(e);}});
    Thread.sleep(6000);
    return "PASS opened RAW6: hero cached, remaining five compact misses; originals untouched; inspect diagnostics for timings. UI left open.\n";
  }
}
