package jp.nano.rawgallery;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.*;
import java.util.concurrent.*;

/** One viewer photograph. Public operations and display ownership belong to the main thread. */
final class PhotoLookController implements AutoCloseable {
  private static final java.util.Map<String,String> OUTCOMES=java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String,String>(){protected boolean removeEldestEntry(java.util.Map.Entry<String,String> e){return size()>32;}});
  private static final long INPUT_LIMIT=256L*1024*1024;
  private final Activity activity;
  private final PhotoRepository.Photo photo;
  private final ZoomImageView image;
  private final Runnable changed;
  private final Handler main=new Handler(Looper.getMainLooper());
  private final ExecutorService gl=Executors.newSingleThreadExecutor(r->new Thread(r,"loupe-viewer-look-gl"));
  private static final ExecutorService STORAGE=Executors.newSingleThreadExecutor(r->new Thread(r,"loupe-viewer-look-store"));
  private final LookPresetStore store;
  private final String key;
  private volatile boolean closed;
  private volatile int engine;
  private long revision,inputNs,request;
  private volatile long frameCommittedNs;
  private int width,height;
  private boolean loading,prepared,rendering,committing,saving,before,restoring=true;
  private LookPresetStore.Entry applied,candidate,failedEntry,afterSave;
  private boolean failedSelection,failedSave,queuedAfterSave,failedRestore,persistCandidate=true;
  private String message="";
  private Bitmap original,developed;
  private Future<?> initial;
  private RawEditFullSession full;
  private Runnable detailTimeout;

  PhotoLookController(Activity a,PhotoRepository.Photo p,ZoomImageView v,Runnable callback)throws IOException {
    activity=a;photo=p;image=v;changed=callback;
    store=new LookPresetStore(new File(a.getFilesDir(),"look-library"));key=ArchiveStore.key(p);
    restore();
  }
  private void restore(){
    restoring=true;failedRestore=false;main.post(this::notifyChanged);
    long restoreRevision=revision;
    STORAGE.execute(()->{
      LookPresetStore.Entry saved=null;boolean failed=false;
      try{saved=store.loadSelection(key);}catch(IOException|RuntimeException failure){failed=true;}
      LookPresetStore.Entry selection=saved;boolean error=failed;
      main.post(()->{
        if(closed||revision!=restoreRevision)return;
        restoring=false;
        if(error){message="Lookを読み込めませんでした";failedRestore=true;notifyChanged();}
        else if(selection!=null){select(selection);persistCandidate=false;}else {message="";notifyChanged();}
      });
    });
  }
  LookPresetStore.Entry applied(){return applied;}
  String message(){if(!busy()&&!saving){String outcome=OUTCOMES.remove(key);if(outcome!=null)message=outcome;}return message;}
  boolean busy(){return restoring||loading||rendering||committing;}
  boolean saving(){return saving;}
  boolean canRetry(){return failedRestore||failedSelection||failedSave;}
  boolean before(){return before;}
  boolean readyToSave(){return !closed&&!busy()&&!saving&&applied!=null&&prepared&&full!=null;}

  /** Takes ownership; late original-loader delivery must never replace an active Look. */
  void original(Bitmap bitmap){
    if(closed){recycle(bitmap);return;}
    Bitmap previous=original;original=bitmap;
    if(applied==null||before)show(original);
    if(previous!=bitmap)recycle(previous);
    notifyChanged();
  }
  void select(LookPresetStore.Entry entry){
    if(closed||committing)return;
    if(saving){afterSave=entry;queuedAfterSave=true;cancel();return;}
    restoring=false;revision++;candidate=entry;failedSelection=false;failedSave=false;failedRestore=false;persistCandidate=true;
    inputNs=SystemClock.elapsedRealtimeNanos();request=DiagnosticLog.id();
    DiagnosticLog.event("viewer.look.input",request,"revision",revision,"clear",entry==null?1:0);
    cancelDetail();message="";
    if(entry==null){
      if(applied!=null&&original==null){selectionFailed("元の写真の読み込みを待って、もう一度お試しください");return;}
      commit(null,null,revision);return;
    }
    rendering=true;message="Lookを反映中…";notifyChanged();
    if(prepared&&full!=null)requestDetail();else if(!loading)load();
  }
  void toggleBefore(){
    if(closed||applied==null||original==null||committing)return;
    before=!before;show(before?original:developed);notifyChanged();
  }
  void retry(){
    if(closed||committing||saving)return;
    if(failedRestore){restore();return;}
    if(failedSave){saveJpeg();return;}
    if(failedSelection){select(failedEntry);return;}
    if(!busy()&&applied!=null)select(applied);
  }
  void saveJpeg(){
    if(!readyToSave())return;
    failedSave=false;saving=true;message="JPEGを保存中…";notifyChanged();
    full.save(state(applied));
  }
  void cancel(){
    if(closed||committing)return;
    if(saving){message="保存の中止を確認中…";full.cancelSave();notifyChanged();return;}
    restoring=false;revision++;cancelDetail();rendering=false;candidate=applied;message="";
    if(loading)stopEngine();notifyChanged();
  }
  boolean leave(){if(committing)return false;if(saving){cancel();return false;}return true;}
  private static RawEditState state(LookPresetStore.Entry e){return RawEditState.neutral().withLook(e.look);}
  private void show(Bitmap bitmap){image.setBitmap(bitmap,true);}
  private void notifyChanged(){if(!closed)changed.run();}
  private boolean current(int token){return !closed&&engine==token;}

  private void load(){
    stopEngine();loading=true;prepared=false;int token=engine;notifyChanged();
    initial=RawEditFullSession.initial(()->{
      RawEditSource source=null;File copy=null;
      try{
        if(!current(token))return;
        long stage=SystemClock.elapsedRealtimeNanos();
        PhotoRepository.Photo cached=OriginalCache.obtain(activity,photo);
        DiagnosticLog.event("viewer.look.original",0,"elapsed_ns",SystemClock.elapsedRealtimeNanos()-stage);
        stage=SystemClock.elapsedRealtimeNanos();
        if(!current(token)||Thread.currentThread().isInterrupted())return;
        copy=File.createTempFile("viewer-look-",".input",activity.getCacheDir());
        try(InputStream in=PhotoRepository.original(activity,cached);FileOutputStream out=new FileOutputStream(copy)){
          byte[] bytes=new byte[65536];long count=0;int n;
          while((n=in.read(bytes))!=-1){
            if(!current(token)||Thread.currentThread().isInterrupted())throw new IOException("Cancelled");
            count+=n;if(count>INPUT_LIMIT)throw new IOException("Input exceeds limit");out.write(bytes,0,n);
          }
          if(count==0)throw new IOException("Empty input");out.getFD().sync();
        }
        DiagnosticLog.event("viewer.look.copy",0,"elapsed_ns",SystemClock.elapsedRealtimeNanos()-stage);
        stage=SystemClock.elapsedRealtimeNanos();
        File owned=copy;copy=null;source=RawEditSource.forFullResolution(owned);
        DiagnosticLog.event("viewer.look.decode",0,"elapsed_ns",SystemClock.elapsedRealtimeNanos()-stage);
        if(!current(token))return;
        RawEditSource loaded=source;source=null;
        try{gl.execute(()->upload(loaded,token));}catch(RejectedExecutionException failure){loaded.close();}
      }catch(Exception|LinkageError|OutOfMemoryError failure){main.post(()->{if(current(token)){loading=false;selectionFailed("Lookを反映できませんでした。もう一度お試しください");}});}
      finally{if(source!=null)source.close();if(copy!=null)copy.delete();}
    });
  }
  private void upload(RawEditSource source,int token){
    // The viewer retains its previous image until full-source rendering commits.
    // FullSession owns the only EGL renderer; an unused initial preview texture is unnecessary.
    if(!current(token)){source.close();return;}
    if(!main.post(()->{if(current(token))attach(source,token);else source.close();}))source.close();
  }
  private void attach(RawEditSource source,int token){
    RawEditFullSession session=new RawEditFullSession(activity,source,gl,main,()->false,new RawEditFullSession.Listener(){
      public void timing(String event,long ns){DiagnosticLog.event(event,0,"elapsed_ns",ns);}
      public void prepared(int w,int h,RawEditFullSource.Preview preview){
        if(!current(token))return;
        width=w;height=h;
        loading=false;prepared=true;
        if(rendering&&candidate!=null)requestDetail();notifyChanged();
      }
      public void prepareFailed(){if(current(token)){loading=false;selectionFailed("Lookを反映できませんでした。もう一度お試しください");}}
      public void detail(Bitmap bitmap,RectF region,long edit,long viewport,boolean ignoredBefore,boolean ignoredMax){
        if(!current(token)||!rendering||edit!=revision||candidate==null){recycle(bitmap);return;}
        cancelDetail();commit(candidate,bitmap,edit);
      }
      public void saved(RawEditState snapshot){if(engine==token)finishSave("JPEGを保存しました",false);}
      public void saveFailed(){if(engine==token)finishSave("JPEGを保存できませんでした。もう一度お試しください",true);}
      public void saveCancelled(){if(engine==token)finishSave("保存を中止しました",false);}
    },false);
    full=session;session.start();
  }
  private void requestDetail(){
    if(closed||!prepared||full==null||candidate==null||!rendering)return;
    long ticket=revision;
    double scale=Math.min(1,Math.min(4000d/Math.max(width,height),Math.sqrt(12000000d/((long)width*height))));
    int w=Math.max(1,(int)(width*scale)),h=Math.max(1,(int)(height*scale));
    full.requestDetail(state(candidate),ticket,0,false,false,new RectF(0,0,width,height),w,h);
    detailTimeout=()->{if(!closed&&rendering&&revision==ticket){full.input();selectionFailed("反映に時間がかかっています。もう一度お試しください");}};
    main.postDelayed(detailTimeout,90000);
  }
  /** Persistence is the uninterruptible commit phase; later requests cannot overtake this snapshot. */
  private void commit(LookPresetStore.Entry entry,Bitmap bitmap,long ticket){
    rendering=false;committing=true;message=entry==null?"Lookを解除中…":"Lookを反映中…";notifyChanged();
    boolean persist=persistCandidate;
    STORAGE.execute(()->{
      boolean success=false;
      try{if(persist){if(entry==null)store.clearSelection(key);else store.saveSelection(key,entry);}success=true;}
      catch(IOException|RuntimeException failure){/* Atomic store retains the previous selection. */}
      boolean persisted=success;
      main.post(()->{
        if(closed){recycle(bitmap);return;}
        committing=false;
        if(!persisted){recycle(bitmap);candidate=entry;selectionFailed("Lookを保存できませんでした。もう一度お試しください");return;}
        Bitmap previous=developed;developed=bitmap;applied=entry;candidate=entry;before=false;message="";
        show(entry==null?original:developed);if(previous!=bitmap)recycle(previous);
        long frameRequest=request,frameStart=inputNs;
        image.getViewTreeObserver().registerFrameCommitCallback(()->{
          long ns=SystemClock.elapsedRealtimeNanos();
          main.post(()->{if(!closed&&revision==ticket){frameCommittedNs=ns;DiagnosticLog.event("viewer.look.frame_committed",frameRequest,"revision",ticket,"elapsed_ns",ns-frameStart);}});
        });
        DiagnosticLog.event("viewer.look.committed",request,"revision",ticket,"elapsed_ns",SystemClock.elapsedRealtimeNanos()-inputNs);
        long committedRequest=request,started=inputNs;
        image.postOnAnimation(()->{if(!closed&&revision==ticket)DiagnosticLog.event("viewer.look.frame_callback",committedRequest,"elapsed_ns",SystemClock.elapsedRealtimeNanos()-started);});
        notifyChanged();
      });
    });
  }
  private void selectionFailed(String text){
    cancelDetail();rendering=false;failedSelection=true;failedEntry=candidate;message=text;notifyChanged();
  }
  private void finishSave(String text,boolean failed){
    if(closed){saving=false;OUTCOMES.put(key,text);stopEngine();gl.shutdown();return;}
    saving=false;failedSave=failed;message=text;notifyChanged();
    if(queuedAfterSave){LookPresetStore.Entry next=afterSave;queuedAfterSave=false;afterSave=null;select(next);}
  }
  private void cancelDetail(){if(detailTimeout!=null){main.removeCallbacks(detailTimeout);detailTimeout=null;}if(full!=null)full.input();}
  private void stopEngine(){
    engine++;loading=false;prepared=false;
    if(initial!=null){initial.cancel(true);initial=null;}
    if(full!=null){full.close();full=null;}
  }
  public void close(){
    if(closed)return;closed=true;revision++;cancelDetail();
    image.setBitmap(null,true);recycle(original);recycle(developed);original=null;developed=null;
    if(saving&&full!=null){full.cancelSave();return;}
    stopEngine();gl.shutdown();
  }
  private static void recycle(Bitmap bitmap){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
}
