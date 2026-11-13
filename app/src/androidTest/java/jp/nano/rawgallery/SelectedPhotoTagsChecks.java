package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import static jp.nano.rawgallery.TagDetailChecks.ui;
import static jp.nano.rawgallery.TagDetailChecks.require;
import static jp.nano.rawgallery.RankingResultChecks.*;

/** Cache-only pictures and a disposable tag DB; never assigns tags in the user's DB. */
final class SelectedPhotoTagsChecks {
  private static Object field(Object target,String name){
    try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    catch(Exception e){throw new RuntimeException(e);}
  }
  private static Set<String> uris(List<PhotoRepository.Photo> photos){
    Set<String> result=new LinkedHashSet<>();for(var photo:photos)result.add(photo.uri.toString());return result;
  }
  private static boolean text(View view,String value){
    if(view instanceof TextView&&value.contentEquals(((TextView)view).getText()))return true;
    if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(text(((ViewGroup)view).getChildAt(i),value))return true;
    return false;
  }
  private static void idle(Instrumentation t,ExecutorService worker)throws Exception{
    worker.submit(()->{}).get(15,TimeUnit.SECONDS);t.waitForIdleSync();
  }
  private static Button confirmation(String title){
    for(View root:WindowInspector.getGlobalWindowViews())if(root.isShown()&&root.hasWindowFocus()&&text(root,title)){
      Button button=TagDetailChecks.button(root,"付ける");if(button!=null)return button;
    }
    return null;
  }
  private static void chooseAndConfirm(Instrumentation t,SelectedPhotoTags sheet,String tag)throws Exception{
    ui(t,()->{Button row=TagDetailChecks.button(sheet.getWindow().getDecorView(),tag);require(row!=null&&row.isEnabled(),"tag choice loaded");row.performClick();});
    t.waitForIdleSync();Button[] confirm={null};
    long until=android.os.SystemClock.uptimeMillis()+5000;
    do{ui(t,()->confirm[0]=confirmation("2枚に「"+tag+"」を付ける"));if(confirm[0]!=null)break;Thread.sleep(25);}while(android.os.SystemClock.uptimeMillis()<until);
    require(confirm[0]!=null,"explicit confirmation names selected count and tag");
    ui(t,()->{confirm[0].performClick();confirm[0].performClick();});
  }
  @SuppressWarnings("unchecked") private static void sparseBaseline(Instrumentation t,MainActivity a,
      ExecutorService worker,String database)throws Exception{
    List<PhotoRepository.Photo> selected=new ArrayList<>();
    for(int i=0;i<1000;i++)selected.add(new PhotoRepository.Photo(
        Uri.parse("content://jp.nano.rawgallery.synthetic/selected-tags/"+i),"metadata-"+i+".jpg",
        "/synthetic-metadata",1024,100+i,100000+i));
    PhotoRepository.Photo outside=new PhotoRepository.Photo(
        Uri.parse("content://jp.nano.rawgallery.synthetic/selected-tags/outside"),"outside.jpg",
        "/synthetic-metadata",1024,2000,200000);
    PhotoRepository.Photo differentVersion=new PhotoRepository.Photo(selected.get(0).uri,
        "different-version.jpg","/synthetic-metadata",2048,3000,300000);
    SelectedPhotoTags[] picker={null};
    Map<String,Long> expected=new LinkedHashMap<>();Map<String,String> beforeIds;
    int beforeHistory;
    try{
      try(TagStore store=new TagStore(a,database)){
        List<TagStore.Tag> tags=new ArrayList<>();
        for(int i=0;i<100;i++)tags.add(store.create(String.format(java.util.Locale.ROOT,"scale-%03d",i)));
        String first=store.photoId(selected.get(0),true),middle=store.photoId(selected.get(500),true);
        String other=store.photoId(outside,true),version=store.photoId(differentVersion,true);
        store.record("scale-first-1",tags.get(0).id,first,0,TagJudgments.Value.ASSIGNED);
        store.record("scale-first-2",tags.get(0).id,first,1,TagJudgments.Value.EXCLUDED);
        store.record("scale-middle",tags.get(1).id,middle,0,TagJudgments.Value.ASSIGNED);
        store.record("scale-outside",tags.get(0).id,other,0,TagJudgments.Value.ASSIGNED);
        store.record("scale-version",tags.get(2).id,version,0,TagJudgments.Value.ASSIGNED);
        expected.put(tags.get(0).id+"|"+TagStore.source(selected.get(0)),2L);
        expected.put(tags.get(1).id+"|"+TagStore.source(selected.get(500)),1L);
        beforeIds=store.photoIds();beforeHistory=store.load().history.size();
        require(beforeIds.size()==4&&beforeHistory==5,"moderate-scale fixture has sparse current judgments");
      }
      ui(t,()->{picker[0]=new SelectedPhotoTags(a,selected,worker,()->new TagStore(a,database));picker[0].show();});
      t.waitForIdleSync();idle(t,worker);
      ui(t,()->{
        require((boolean)field(picker[0],"loaded"),"100-tag/1000-metadata picker loads");
        require(((List<?>)field(picker[0],"tags")).size()==100,"all 100 tag choices retained");
        Map<String,Long> actual=(Map<String,Long>)field(picker[0],"expected");
        require(actual.equals(expected),"baseline contains only 2 selected existing judgments with latest revision, excludes outside sources and changed observed versions");
        require(actual.size()==2,"no tag-by-photo zero revision matrix");
        TagDetailChecks.button(picker[0].getWindow().getDecorView(),"閉じる").performClick();
        require(!picker[0].isShowing(),"moderate-scale picker cancels without save");
      });
      try(TagStore store=new TagStore(a,database)){
        require(store.photoIds().equals(beforeIds),"opening/cancel does not create identities for 998 unjudged metadata photos");
        require(store.load().history.size()==beforeHistory,"opening/cancel creates no tag associations or judgment revisions");
      }
    }finally{
      idle(t,worker);
      ui(t,()->{if(picker[0]!=null&&picker[0].isShowing())picker[0].dismiss();});
      a.deleteDatabase(database);
    }
  }
  @SuppressWarnings("unchecked") static String run(Instrumentation t,int width,float font)throws Exception{
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    String unique="selected-tags-"+UUID.randomUUID();String database=unique+".db";
    List<File> files=new ArrayList<>();List<SelectedPhotoTags> sheets=new ArrayList<>();
    ExecutorService worker=Executors.newSingleThreadExecutor();CountDownLatch release=new CountDownLatch(1);
    var prefs=a.getSharedPreferences(unique,0);
    try{
      GalleryScreenChecks.finishInitialIndex(t,a);GalleryScreenChecks.portraitFixture(t,a,width,font);
      List<PhotoRepository.Photo> photos=new ArrayList<>();
      for(int i=0;i<3;i++){
        File file=new File(a.getCacheDir(),unique+"-"+i+".jpg");files.add(file);
        Bitmap bitmap=Bitmap.createBitmap(180,120,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff607080+i*0x101010);
        try(FileOutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);}bitmap.recycle();
        photos.add(new PhotoRepository.Photo(Uri.fromFile(file),"合成写真"+i+".jpg","/selected-tags",file.length(),1792298040L+i,1792298040000L+i*1000));
      }
      RankingStore ranking=new RankingStore(prefs);
      String visibleKey=ArchiveStore.key(photos.get(0));
      require(!ranking.singleBest(visibleKey),"fresh isolated rank starts unmarked");
      require(ranking.saveSingleBest(visibleKey,false,true),"seed isolated visible rank from observed false to true");
      require(new RankingStore(prefs).singleBest(visibleKey),"seeded visible mark is readable before rendering filtered selection");
      Set<String> expected=uris(List.of(photos.get(0),photos.get(2)));
      ui(t,()->{
        set(a,"photos",photos);set(a,"bundles",List.of(List.of(0),List.of(1),List.of(2)));
        set(a,"rankingStore",ranking);((Map<?,?>)get(a,"rankCache")).clear();set(a,"burstMembers",null);
        set(a,"albumFolder","/selected-tags");set(a,"filter","すべて");set(a,"rankFilter",1);set(a,"selecting",true);
        Set<String> selected=(Set<String>)get(a,"selectedPhotos");selected.clear();selected.addAll(expected);call(a,"gallery");
        require(((List<?>)get(a,"displayed")).equals(List.of(List.of(0))),"selected photo 2 is hidden by rank filter");
        Button tag=(Button)get(a,"selectionTag");require(tag!=null&&tag.isEnabled(),"selection tag entry");tag.performClick();
        SelectedPhotoTags actual=(SelectedPhotoTags)get(a,"selectionTags");sheets.add(actual);
        require(actual.isShowing()&&uris((List<PhotoRepository.Photo>)field(actual,"photos")).equals(expected),"Main freezes exactly selected URIs including hidden selection");
        require(selected.equals(expected)&&get(a,"photos")==photos&&(int)get(a,"rankFilter")==1,"opening tag picker preserves library selection and filter");
        actual.dismiss();
      });
      idle(t,(ExecutorService)get(a,"actions"));
      TagStore.Tag tag;
      try(TagStore store=new TagStore(a,database)){
        tag=store.create("選択だけのタグ");
        for(var photo:photos)store.photoId(photo,true);
        // Abort the second judgment so a partially applied batch must roll back as well.
        store.getWritableDatabase().execSQL("CREATE TRIGGER fail_selected_tags BEFORE INSERT ON judgments WHEN (SELECT COUNT(*) FROM judgments)>0 BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
      }
      List<PhotoRepository.Photo> supplied=new ArrayList<>(List.of(photos.get(0),photos.get(2)));
      SelectedPhotoTags[] isolated={null};
      ui(t,()->{
        isolated[0]=new SelectedPhotoTags(a,supplied,worker,()->new TagStore(a,database));sheets.add(isolated[0]);
        supplied.clear();supplied.add(photos.get(1));
        set(a,"photos",List.of(photos.get(1)));Set<String> selected=(Set<String>)get(a,"selectedPhotos");selected.clear();selected.add(photos.get(1).uri.toString());
        isolated[0].show();
      });
      t.waitForIdleSync();idle(t,worker);
      File dir=new File(a.getCacheDir(),"consistency-checks");dir.mkdirs();String prefix="selected-tags-"+width+"-"+font;
      ShareScreenChecks.shot(t,isolated[0],dir,prefix+"-choices");
      chooseAndConfirm(t,isolated[0],tag.name);idle(t,worker);
      ui(t,()->{
        StatusView status=(StatusView)field(isolated[0],"status");
        require(status.isShown()&&status.isClickable()&&status.getContentDescription().toString().contains("タグを保存できませんでした"),"real SQLite failure exposes visible retry");
        require(isolated[0].isShowing(),"failure retains picker");
      });
      try(TagStore store=new TagStore(a,database)){
        require(store.load().history.isEmpty(),"failed batch rolls back first judgment and history");
        for(var photo:photos)require(store.load().latest(tag.id,store.photoId(photo,false))==null,"failure leaves every photo unassigned");
        store.getWritableDatabase().execSQL("DROP TRIGGER fail_selected_tags");
      }
      ShareScreenChecks.shot(t,isolated[0],dir,prefix+"-failed");
      CountDownLatch blocked=new CountDownLatch(1);
      worker.execute(()->{blocked.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
      require(blocked.await(5,TimeUnit.SECONDS),"retry blocker ready");
      ui(t,()->{
        StatusView status=(StatusView)field(isolated[0],"status");status.performClick();status.performClick();
        require((boolean)field(isolated[0],"busy"),"retry enters busy state");
        Button row=TagDetailChecks.button(isolated[0].getWindow().getDecorView(),tag.name);
        require(row!=null&&!row.isEnabled(),"busy disables duplicate tag choice");
      });
      release.countDown();idle(t,worker);
      String operation=(String)field(isolated[0],"operation");
      try(TagStore store=new TagStore(a,database)){
        TagJudgments judgments=store.load();require(judgments.history.size()==2,"retry commits only two selected judgments once");
        Map<String,Long> baseline=new LinkedHashMap<>();
        for(int index:new int[]{0,2}){
          String id=store.photoId(photos.get(index),false);TagJudgments.Entry latest=judgments.latest(tag.id,id);
          require(latest!=null&&latest.value==TagJudgments.Value.ASSIGNED&&latest.revision==1,"frozen selected target assigned once");baseline.put(id,0L);
        }
        require(judgments.latest(tag.id,store.photoId(photos.get(1),false))==null,"unselected third photo remains unassigned after library/input mutation");
        require(store.recordBatch(operation,tag.id,baseline,TagJudgments.Value.ASSIGNED).history.size()==2,"same successful operation replay is idempotent");
      }
      ui(t,()->{StatusView status=(StatusView)field(isolated[0],"status");require(status.getContentDescription().toString().contains("2枚にタグを付けました"),"successful retry announces exact frozen count");TagDetailChecks.inspect((View)field(isolated[0],"body"));});
      ShareScreenChecks.shot(t,isolated[0],dir,prefix+"-saved");
      ui(t,()->{Button close=TagDetailChecks.button((View)field(isolated[0],"body"),"閉じる");require(close!=null&&close.isEnabled(),"success has explicit close action");close.performClick();require(!isolated[0].isShowing(),"success close dismisses picker");});
      require(ranking.singleBest(ArchiveStore.key(photos.get(0)))&&!ranking.singleBest(ArchiveStore.key(photos.get(2))),"tagging preserves isolated ranks");
      sparseBaseline(t,a,worker,unique+"-scale.db");
      return "PASS selected-photo tag entry freezes exact selected URIs including a rank-hidden photo; explicit 2-photo confirmation, real SQLite second-write failure rolls entire isolated batch back, visible retry succeeds, busy duplicate suppression, unselected photo unchanged after source/selection mutation, operation replay idempotent, success Close; 100-tag/1000-metadata picker keeps exactly two latest selected-source revisions and creates no identities/associations on open/cancel. Captures at "+width+"dp/font"+font+". Cache photos and isolated tag/rank stores only; no default-store associations or photo originals changed.\n";
    }finally{
      release.countDown();worker.shutdown();worker.awaitTermination(20,TimeUnit.SECONDS);
      ui(t,()->{for(var sheet:sheets)if(sheet.isShowing())sheet.dismiss();a.finish();});
      a.deleteDatabase(database);prefs.edit().clear().commit();a.deleteSharedPreferences(unique);
      for(File file:files)file.delete();
    }
  }
}
