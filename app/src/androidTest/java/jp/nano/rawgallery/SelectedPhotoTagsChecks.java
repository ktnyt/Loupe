package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewParent;
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
  private static String pointerGeometry(View target){
    StringBuilder result=new StringBuilder("tap geometry");
    Rect usable=new Rect();target.getWindowVisibleDisplayFrame(usable);
    result.append(" usable=").append(usable).append(" display=").append(target.getResources().getDisplayMetrics().widthPixels).append('x').append(target.getResources().getDisplayMetrics().heightPixels);
    for(View view=target;view!=null;){
      int[] screen=new int[2],window=new int[2];view.getLocationOnScreen(screen);view.getLocationInWindow(window);
      Rect visible=new Rect();boolean intersects=view.getLocalVisibleRect(visible);
      result.append("\n").append(view.getClass().getName()).append(" id=").append(view.getId())
          .append(" size=").append(view.getWidth()).append('x').append(view.getHeight())
          .append(" measured=").append(view.getMeasuredWidth()).append('x').append(view.getMeasuredHeight())
          .append(" screen=").append(Arrays.toString(screen)).append(" window=").append(Arrays.toString(window))
          .append(" bounds=").append(view.getLeft()).append(',').append(view.getTop()).append(',').append(view.getRight()).append(',').append(view.getBottom())
          .append(" localVisible=").append(intersects).append(':').append(visible)
          .append(" clip=").append(view.getClipBounds()).append(" padding=").append(view.getPaddingLeft()).append(',').append(view.getPaddingTop()).append(',').append(view.getPaddingRight()).append(',').append(view.getPaddingBottom())
          .append(" scroll=").append(view.getScrollX()).append(',').append(view.getScrollY())
          .append(" translation=").append(view.getTranslationX()).append(',').append(view.getTranslationY())
          .append(" scale=").append(view.getScaleX()).append(',').append(view.getScaleY())
          .append(" laidOut=").append(view.isLaidOut()).append(" layoutRequested=").append(view.isLayoutRequested());
      if(view instanceof ViewGroup)result.append(" clipChildren=").append(((ViewGroup)view).getClipChildren()).append(" clipToPadding=").append(((ViewGroup)view).getClipToPadding());
      ViewParent parent=view.getParent();view=parent instanceof View?(View)parent:null;
    }
    return result.toString();
  }
  private static void pointerCheck(boolean condition,View target,String reason){
    if(!condition){String details=pointerGeometry(target);android.util.Log.e("SelectedTagsPointer",reason+"\n"+details);throw new AssertionError(reason+"\n"+details);}
  }
  private static void tap(Instrumentation t,View target)throws Exception{
    require(target!=null,"pointer target exists");
    Rect previous=new Rect();long stable=0,deadline=SystemClock.uptimeMillis()+5000;
    while(SystemClock.uptimeMillis()<deadline){
      Rect next=new Rect();ui(t,()->{int[] at=new int[2];target.getLocationOnScreen(at);next.set(at[0],at[1],at[0]+target.getWidth(),at[1]+target.getHeight());});
      long now=SystemClock.uptimeMillis();if(!next.equals(previous)||next.isEmpty()){previous.set(next);stable=now;}
      if(now-stable>=250)break;Thread.sleep(25);
    }
    require(SystemClock.uptimeMillis()-stable>=250,"pointer target geometry settles");
    boolean[] confirmationTarget={false};
    ui(t,()->confirmationTarget[0]=target instanceof Button&&"付ける".contentEquals(((Button)target).getText())&&target.hasWindowFocus());
    if(confirmationTarget[0]){
      Bitmap screen=t.getUiAutomation().takeScreenshot();require(screen!=null,"confirmation full-screen evidence available");
      File dir=new File(t.getTargetContext().getCacheDir(),"consistency-checks");dir.mkdirs();
      var configuration=target.getResources().getConfiguration();
      File file=new File(dir,"selected-tags-"+configuration.screenWidthDp+"-"+configuration.fontScale+"-confirmation-full-screen.png");
      try(FileOutputStream out=new FileOutputStream(file)){screen.compress(Bitmap.CompressFormat.PNG,100,out);}finally{screen.recycle();}
    }
    int[] center=new int[2];
    ui(t,()->{
      pointerCheck(target.isShown()&&target.isEnabled()&&target.hasWindowFocus(),target,"pointer target is enabled in focused visible window");
      int[] at=new int[2];target.getLocationOnScreen(at);center[0]=at[0]+target.getWidth()/2;center[1]=at[1]+target.getHeight()/2;
      Rect local=new Rect(),usable=new Rect();target.getWindowVisibleDisplayFrame(usable);
      boolean locallyVisible=target.getLocalVisibleRect(local);
      // getLocalVisibleRect reports content coordinates. TextView may scroll its text
      // internally; a physical pointer center is expressed in the view's viewport.
      local.offset(-target.getScrollX(),-target.getScrollY());
      pointerCheck(locallyVisible&&local.contains(target.getWidth()/2,target.getHeight()/2),target,"pointer center survives native ancestor clipping");
      pointerCheck(usable.contains(center[0],center[1]),target,"pointer center is within usable display");
      Rect ownClip=target.getClipBounds();pointerCheck(ownClip==null||ownClip.contains(target.getWidth()/2,target.getHeight()/2),target,"pointer center survives own clip bounds");
      for(ViewParent parent=target.getParent();parent instanceof View;parent=parent.getParent()){
        View ancestor=(View)parent;int[] origin=new int[2];ancestor.getLocationOnScreen(origin);
        Rect clip=ancestor.getClipBounds();
        if(clip!=null){clip.offset(origin[0],origin[1]);pointerCheck(clip.contains(center[0],center[1]),target,"pointer center survives ancestor explicit clip");}
        if(ancestor instanceof ViewGroup&&((ViewGroup)ancestor).getClipChildren()){
          int insetLeft=0,insetTop=0,insetRight=0,insetBottom=0;
          if(((ViewGroup)ancestor).getClipToPadding()){insetLeft=ancestor.getPaddingLeft();insetTop=ancestor.getPaddingTop();insetRight=ancestor.getPaddingRight();insetBottom=ancestor.getPaddingBottom();}
          pointerCheck(new Rect(origin[0]+insetLeft,origin[1]+insetTop,origin[0]+ancestor.getWidth()-insetRight,origin[1]+ancestor.getHeight()-insetBottom).contains(center[0],center[1]),target,"pointer center survives ancestor child clipping");
        }
      }
    });
    long down=SystemClock.uptimeMillis();MotionEvent press=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,center[0],center[1],0),up=MotionEvent.obtain(down,down+60,MotionEvent.ACTION_UP,center[0],center[1],0);
    try{t.sendPointerSync(press);t.sendPointerSync(up);}finally{press.recycle();up.recycle();}t.waitForIdleSync();
  }
  private static Button action(SelectedPhotoTags picker,String label){return TagDetailChecks.button(picker.getWindow().getDecorView(),label);}
  private static void visibleMessage(SelectedPhotoTags picker,String contains){
    TextView message=(TextView)field(picker,"explanation");require(message.isShown()&&message.getText().toString().contains(contains),"visible status explains outcome: "+contains);
    require(!((StatusView)field(picker,"status")).isClickable(),"status glyph is not an unlabeled recovery target");
  }
  private static Button confirmation(String title){
    for(View root:WindowInspector.getGlobalWindowViews())if(root.isShown()&&root.hasWindowFocus()&&text(root,title)){
      Button button=TagDetailChecks.button(root,"付ける");if(button!=null)return button;
    }
    return null;
  }
  private static void chooseAndConfirm(Instrumentation t,SelectedPhotoTags sheet,String tag)throws Exception{
    tap(t,action(sheet,tag));
    t.waitForIdleSync();Button[] confirm={null};
    long until=android.os.SystemClock.uptimeMillis()+5000;
    do{ui(t,()->confirm[0]=confirmation("2枚に「"+tag+"」を付ける"));if(confirm[0]!=null)break;Thread.sleep(25);}while(android.os.SystemClock.uptimeMillis()<until);
    require(confirm[0]!=null,"explicit confirmation names selected count and tag");
    tap(t,confirm[0]);
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
      });
      tap(t,action(picker[0],"閉じる"));
      ui(t,()->require(!picker[0].isShowing(),"moderate-scale picker cancels without save"));
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
  private static void presentationStates(Instrumentation t,MainActivity a,ExecutorService worker,
      String database,List<PhotoRepository.Photo> photos,File dir,String prefix)throws Exception{
    List<SelectedPhotoTags> opened=new ArrayList<>();SelectedPhotoTags[] picker={null};
    ExecutorService rejected=Executors.newSingleThreadExecutor();rejected.shutdown();
    try{
      ui(t,()->{picker[0]=new SelectedPhotoTags(a,photos,worker,()->new TagStore(a,database));opened.add(picker[0]);picker[0].show();});
      t.waitForIdleSync();idle(t,worker);
      ui(t,()->visibleMessage(picker[0],"タグがありません"));
      ShareScreenChecks.shot(t,picker[0],dir,prefix+"-empty");tap(t,action(picker[0],"閉じる"));
      TagStore.Tag tag;try(TagStore store=new TagStore(a,database)){tag=store.create("競合を確認するタグ");}
      java.util.concurrent.atomic.AtomicBoolean failLoad=new java.util.concurrent.atomic.AtomicBoolean(true);
      ui(t,()->{picker[0]=new SelectedPhotoTags(a,photos,worker,()->{
        if(failLoad.getAndSet(false))throw new android.database.sqlite.SQLiteException("synthetic picker load failure");
        return new TagStore(a,database);
      });opened.add(picker[0]);picker[0].show();});
      t.waitForIdleSync();idle(t,worker);
      ui(t,()->visibleMessage(picker[0],"タグを読み込めませんでした"));
      ShareScreenChecks.shot(t,picker[0],dir,prefix+"-load-failed");
      tap(t,action(picker[0],"再試行"));idle(t,worker);
      ui(t,()->require((boolean)field(picker[0],"loaded")&&action(picker[0],tag.name)!=null,"visible load retry restores choices"));
      try(TagStore store=new TagStore(a,database)){
        store.record("external-before-confirm",tag.id,store.photoId(photos.get(0),true),0,TagJudgments.Value.EXCLUDED);
      }
      chooseAndConfirm(t,picker[0],tag.name);idle(t,worker);
      ui(t,()->visibleMessage(picker[0],"ほかの操作でタグが変更されました"));
      ShareScreenChecks.shot(t,picker[0],dir,prefix+"-conflict");
      tap(t,action(picker[0],"タグを読み直す"));idle(t,worker);
      ui(t,()->require((boolean)field(picker[0],"loaded")&&action(picker[0],tag.name)!=null,"conflict reload restores explicit choices"));
      tap(t,action(picker[0],"閉じる"));
      try(TagStore store=new TagStore(a,database)){
        require(store.load().history.size()==1,"conflict and choice reload preserve only external judgment");
        require(store.load().latest(tag.id,store.photoId(photos.get(0),false)).value==TagJudgments.Value.EXCLUDED,"conflict never silently overwrites another writer");
      }
      ui(t,()->{picker[0]=new SelectedPhotoTags(a,photos,rejected,()->new TagStore(a,database));opened.add(picker[0]);picker[0].show();});
      t.waitForIdleSync();
      ui(t,()->visibleMessage(picker[0],"処理を開始できませんでした"));
      ShareScreenChecks.shot(t,picker[0],dir,prefix+"-worker-unavailable");
      tap(t,TagDetailChecks.button((View)field(picker[0],"body"),"閉じる"));
      ui(t,()->require(!picker[0].isShowing(),"rejected task has visible working close action"));
    }finally{
      idle(t,worker);ui(t,()->{for(var sheet:opened)if(sheet.isShowing())sheet.dismiss();});a.deleteDatabase(database);
    }
  }
  private static void identityFailure(Instrumentation t,MainActivity a,ExecutorService worker,
      String database,List<PhotoRepository.Photo> photos,File dir,String prefix)throws Exception{
    SelectedPhotoTags[] picker={null};
    try{
      TagStore.Tag tag;
      try(TagStore store=new TagStore(a,database)){
        tag=store.create("保存失敗を確認するタグ");
        require(store.photoIds().isEmpty(),"identity failure fixture starts without photo identities");
        store.getWritableDatabase().execSQL("CREATE TRIGGER fail_selected_identity BEFORE INSERT ON photos BEGIN SELECT RAISE(IGNORE); END");
        try{store.photoId(photos.get(0),true);throw new AssertionError("identity insertion should fail");}
        catch(IllegalStateException failure){require(!(failure instanceof TagJudgments.StaleRevisionException),"identity persistence failure is not a stale revision");}
      }
      ui(t,()->{picker[0]=new SelectedPhotoTags(a,photos,worker,()->new TagStore(a,database));picker[0].show();});
      t.waitForIdleSync();idle(t,worker);
      chooseAndConfirm(t,picker[0],tag.name);idle(t,worker);
      ui(t,()->{
        visibleMessage(picker[0],"タグを保存できませんでした");
        require(action(picker[0],"再試行")!=null&&action(picker[0],"タグを読み直す")==null,"identity failure offers save retry instead of false conflict reload");
      });
      ShareScreenChecks.shot(t,picker[0],dir,prefix+"-identity-failed");
      try(TagStore store=new TagStore(a,database)){
        require(store.photoIds().isEmpty()&&store.load().history.isEmpty(),"failed identity creation never reaches batch save");
        store.getWritableDatabase().execSQL("DROP TRIGGER fail_selected_identity");
      }
      tap(t,action(picker[0],"再試行"));idle(t,worker);
      ui(t,()->visibleMessage(picker[0],"2枚にタグを付けました"));
      try(TagStore store=new TagStore(a,database)){
        TagJudgments saved=store.load();require(saved.history.size()==2,"identity failure retry commits only the two frozen photos");
        for(var photo:photos){TagJudgments.Entry entry=saved.latest(tag.id,store.photoId(photo,false));require(entry!=null&&entry.revision==1&&entry.value==TagJudgments.Value.ASSIGNED,"identity creation retry assigns each target once");}
      }
      tap(t,TagDetailChecks.button((View)field(picker[0],"body"),"閉じる"));
    }finally{
      idle(t,worker);ui(t,()->{if(picker[0]!=null&&picker[0].isShowing())picker[0].dismiss();});a.deleteDatabase(database);
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
      });
      t.waitForIdleSync();tap(t,(Button)get(a,"selectionTag"));
      ui(t,()->{
        Set<String> selected=(Set<String>)get(a,"selectedPhotos");
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
        visibleMessage(isolated[0],"タグを保存できませんでした");
        Button retry=action(isolated[0],"再試行");require(retry!=null&&retry.isShown()&&retry.isEnabled(),"real SQLite failure exposes labeled retry");
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
      Button retry=action(isolated[0],"再試行");tap(t,retry);
      ui(t,()->{
        retry.performClick();
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
      ui(t,()->{visibleMessage(isolated[0],"2枚にタグを付けました");TagDetailChecks.inspect((View)field(isolated[0],"body"));});
      ShareScreenChecks.shot(t,isolated[0],dir,prefix+"-saved");
      tap(t,TagDetailChecks.button((View)field(isolated[0],"body"),"閉じる"));
      ui(t,()->require(!isolated[0].isShowing(),"success close dismisses picker"));
      require(ranking.singleBest(ArchiveStore.key(photos.get(0)))&&!ranking.singleBest(ArchiveStore.key(photos.get(2))),"tagging preserves isolated ranks");
      sparseBaseline(t,a,worker,unique+"-scale.db");
      presentationStates(t,a,worker,unique+"-presentation.db",List.of(photos.get(0),photos.get(2)),dir,prefix);
      identityFailure(t,a,worker,unique+"-identity.db",List.of(photos.get(0),photos.get(2)),dir,prefix);
      return "PASS selected-photo tag entry freezes exact selected URIs including a rank-hidden photo; explicit 2-photo confirmation, real SQLite second-write failure rolls entire isolated batch back, visible explanatory text and labeled pointer retry succeeds, busy duplicate suppression, unselected photo unchanged after source/selection mutation, operation replay idempotent, success Close, visible empty/load-failure/conflict/rejected-task outcomes and pointer recovery actions, actual identity persistence failure classified as save retry and recovered; all pointer centers pass usable-display and ancestor clipping checks; 100-tag/1000-metadata picker keeps exactly two latest selected-source revisions and creates no identities/associations on open/cancel. Captures at "+width+"dp/font"+font+". Cache photos and isolated tag/rank stores only; no default-store associations or photo originals changed.\n";
    }finally{
      release.countDown();worker.shutdown();worker.awaitTermination(20,TimeUnit.SECONDS);
      ui(t,()->{for(var sheet:sheets)if(sheet.isShowing())sheet.dismiss();a.finish();});
      a.deleteDatabase(database);prefs.edit().clear().commit();a.deleteSharedPreferences(unique);
      for(File file:files)file.delete();
    }
  }
}
