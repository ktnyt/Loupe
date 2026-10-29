package jp.nano.rawgallery;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** One photo's original cache and bounded background GL jobs. Displayed bitmap ownership is transferred. */
final class RawEditFullSession implements AutoCloseable {
  // Includes initial decoding: a cancelled predecessor must release LibRaw before another decode starts.
  private static final ExecutorService PREPARATION=Executors.newSingleThreadExecutor(r->new Thread(r,"loupe-full-prepare"));
  static Future<?> initial(Runnable action){return PREPARATION.submit(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);try{action.run();}finally{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);}});}
  interface Listener {
    void prepared(int width,int height,RawEditFullSource.Preview preview);
    void prepareFailed();
    void detail(Bitmap bitmap,RectF source,long edit,long viewport,boolean before,boolean max);
    void saved(RawEditState snapshot);
    void saveFailed();
    void saveCancelled();
  }
  private final Context context;
  private final Handler main;
  private final ExecutorService gl;
  private final Listener listener;
  private final BooleanSupplier previewBusy;
  private final RawEditSource source;
  private final File cacheFile;
  private NativeRaw.Cancellation preparationCancel=new NativeRaw.Cancellation();
  private final AtomicBoolean stepQueued=new AtomicBoolean();
  private volatile boolean closed,prepared,failed;
  private volatile long lastInput=SystemClock.elapsedRealtimeNanos();
  private final AtomicLong detailSequence=new AtomicLong();
  private volatile DetailRequest desired;
  private volatile Export export;
  private RawEditFullSource full; // Published under this monitor, used only on GL after publication.
  private RawEditRenderer tiles; // GL owned.
  private DetailJob detail;
  private long completedDetail=-1;
  private final Runnable wake=this::enqueue;

  RawEditFullSession(Context c,RawEditSource input,ExecutorService worker,Handler handler,BooleanSupplier busy,Listener callbacks){
    context=c.getApplicationContext();source=input;gl=worker;main=handler;previewBusy=busy;listener=callbacks;
    cacheFile=new File(c.getCacheDir(),"raw-full-"+UUID.randomUUID()+".ppm");
  }
  boolean isPrepared(){return prepared&&!closed;}
  boolean hasFailed(){return failed&&!closed;}
  void start(){
    final RawEditSource retained;
    try{retained=source.retain();}catch(IOException e){failed=true;listener.prepareFailed();return;}
    source.releasePreviewPixels();
    NativeRaw.Cancellation cancellation=preparationCancel;
    PREPARATION.execute(()->{
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
      RawEditFullSource result=null;long started=SystemClock.elapsedRealtime();
      try{
        if(closed||cancellation.isCancelled())throw new IOException("中止");
        retained.prepareFull(cacheFile,cancellation);
        result=RawEditFullSource.open(cacheFile);
        RawEditFullSource.Preview preview=result.preview(2048);
        if(closed||cancellation.isCancelled())throw new IOException("中止");
        synchronized(this){
          if(closed)throw new IOException("中止");
          full=result;result=null;prepared=true;
          int w=full.width,h=full.height;
          RawEditLog.event("full.prepare.complete",SystemClock.elapsedRealtime()-started,w,h);
          main.post(()->{if(!closed)listener.prepared(w,h,preview);});
        }
      }catch(IOException|RuntimeException|LinkageError|OutOfMemoryError error){
        if(!closed){failed=true;RawEditLog.event("full.prepare.failed",SystemClock.elapsedRealtime()-started,0,0);main.post(()->{if(!closed)listener.prepareFailed();});}
      }finally{
        retained.releasePreviewPixels();retained.close();
        if(result!=null)result.close();
        if(!prepared)removeCache();
      }
    });
  }
  synchronized void retry(){if(!closed&&failed&&!prepared){failed=false;preparationCancel=new NativeRaw.Cancellation();start();}}
  void input(){lastInput=SystemClock.elapsedRealtimeNanos();desired=null;detailSequence.incrementAndGet();main.removeCallbacks(wake);main.postDelayed(wake,130);}
  void requestDetail(RawEditState state,long edit,long viewport,boolean before,boolean max,RectF region,int w,int h){
    if(closed||!prepared||region==null||w<1||h<1||w>4096||h>4096||(long)w*h>12000000)return;
    desired=new DetailRequest(detailSequence.incrementAndGet(),state,edit,viewport,before,max,new RectF(region),w,h);
    main.removeCallbacks(wake);main.postDelayed(wake,130);
  }
  void save(RawEditState snapshot){
    if(closed||!prepared||export!=null){main.post(()->{if(!closed)listener.saveFailed();});return;}
    export=new Export(snapshot);enqueue();
  }
  void cancelSave(){Export current=export;if(current!=null)current.cancelled=true;enqueue();}
  private void enqueue(){
    if(closed||!prepared||!stepQueued.compareAndSet(false,true))return;
    synchronized(this){
      if(closed){stepQueued.set(false);return;}
      try{gl.execute(this::step);}catch(RejectedExecutionException ignored){stepQueued.set(false);}
    }
  }
  private void step(){
    boolean work=false;int delay=16;
    try{
      if(closed)return;
      Export current=export;
      if(current!=null&&current.cancelled){abort(current);export=null;main.post(()->{if(!closed)listener.saveCancelled();});return;}
      if(previewBusy.getAsBoolean()){work=true;return;}
      DetailRequest request=desired;
      boolean idle=SystemClock.elapsedRealtimeNanos()-lastInput>=120000000L;
      if(request!=null&&request.id!=completedDetail&&idle){
        if(detail==null||detail.request!=request){discardDetail();detail=new DetailJob(request,full.width,full.height);}
        renderDetail(detail);work=hasWork();
      }else if(current!=null){renderExport(current);work=hasWork();delay=0;}
      else work=request!=null&&request.id!=completedDetail;
    }catch(IOException|RuntimeException|OutOfMemoryError error){
      if(export!=null){Export current=export;abort(current);export=null;main.post(()->{if(!closed){if(current.cancelled)listener.saveCancelled();else listener.saveFailed();}});}
      discardDetail();DetailRequest request=desired;if(request!=null)completedDetail=request.id;
      RawEditLog.event("full.render.failed",0,0,0);
    }finally{
      stepQueued.set(false);
      if(!closed&&work){main.removeCallbacks(wake);main.postDelayed(wake,delay);}
    }
  }
  private boolean hasWork(){DetailRequest r=desired;return export!=null||(r!=null&&r.id!=completedDetail);}
  private RawEditRenderer renderer()throws IOException {if(tiles==null)tiles=new RawEditRenderer();return tiles;}
  private void renderDetail(DetailJob job)throws IOException {
    DetailRequest r=job.request;
    if(closed||desired!=r){discardDetail();return;}
    RawEditTilePlan.Tile tile=job.plan.next();
    Bitmap piece=renderer().renderRegionBitmap(full,r.state,r.before,r.max,tile.source,tile.width,tile.height);
    try{job.canvas.drawBitmap(piece,tile.x,tile.y,null);}finally{piece.recycle();}
    if(!job.plan.hasNext()){
      Bitmap bitmap=job.bitmap;job.bitmap=null;detail=null;completedDetail=r.id;
      RawEditLog.event("full.detail.complete",SystemClock.elapsedRealtime()-job.started,r.width,r.height);
      main.post(()->{if(!closed&&desired==r&&detailSequence.get()==r.id)listener.detail(bitmap,r.region,r.edit,r.viewport,r.before,r.max);else bitmap.recycle();});
    }
  }
  private void discardDetail(){if(detail!=null){if(detail.bitmap!=null)detail.bitmap.recycle();detail=null;}}
  private void renderExport(Export job)throws IOException {
    if(closed||job.cancelled)throw new IOException("中止");
    if(job.encoder==null){
      ContentValues v=new ContentValues();v.put(MediaStore.Images.Media.DISPLAY_NAME,"Loupe-"+UUID.randomUUID()+".jpg");v.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");v.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/LoupeDevelop");v.put(MediaStore.Images.Media.IS_PENDING,1);
      job.pending=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);if(job.pending==null)throw new IOException("保存先");
      job.fd=context.getContentResolver().openFileDescriptor(job.pending,"w");if(job.fd==null)throw new IOException("保存先");
      job.encoder=NativeJpeg.open(job.fd.getFd(),full.width,full.height,95);
      job.strip=ByteBuffer.allocateDirect(full.width*Math.min(128,full.height)*3);
    }
    if(job.plan==null){
      job.rows=Math.min(128,full.height-job.y);job.strip.clear();job.strip.limit(full.width*job.rows*3);
      job.plan=new RawEditTilePlan(new RectF(0,job.y,full.width,job.y+job.rows),full.width,job.rows,full.width,full.height).iterator();
    }
    RawEditTilePlan.Tile tile=job.plan.next();
    ByteBuffer bytes=renderer().renderRegionBytes(full,job.state,false,false,tile.source,tile.width,tile.height);
    for(int y=0;y<tile.height;y++){
      if(closed||job.cancelled)throw new IOException("中止");
      job.strip.position(((tile.y+y)*full.width+tile.x)*3);
      for(int x=0;x<tile.width;x++){job.strip.put(bytes.get());job.strip.put(bytes.get());job.strip.put(bytes.get());bytes.get();}
    }
    if(!job.plan.hasNext()){
      job.strip.position(0);job.strip.limit(full.width*job.rows*3);job.encoder.write(job.strip,job.rows);job.y+=job.rows;job.plan=null;
      if(job.y==full.height){
        job.encoder.finish();job.encoder=null;job.fd.close();job.fd=null;
        if(closed||job.cancelled)throw new IOException("中止");
        ContentValues v=new ContentValues();v.put(MediaStore.Images.Media.IS_PENDING,0);
        if(context.getContentResolver().update(job.pending,v,null,null)!=1)throw new IOException("公開");job.pending=null;export=null;
        RawEditLog.event("save.complete",SystemClock.elapsedRealtime()-job.started,full.width,full.height);
        main.post(()->{if(!closed&&!job.cancelled)listener.saved(job.state);});
      }
    }
  }
  private void abort(Export job){
    if(job.encoder!=null){job.encoder.close();job.encoder=null;}
    if(job.fd!=null)try{job.fd.close();}catch(IOException ignored){}finally{job.fd=null;}
    if(job.pending!=null)try{context.getContentResolver().delete(job.pending,null,null);}catch(RuntimeException ignored){RawEditLog.event("save.cleanup.failed",0,0,0);}finally{job.pending=null;}
  }
  private void removeCache(){if(cacheFile.exists()&&!cacheFile.delete())RawEditLog.event("full.cleanup.failed",0,0,0);}
  public synchronized void close(){
    if(closed)return;closed=true;preparationCancel.cancel();main.removeCallbacks(wake);desired=null;detailSequence.incrementAndGet();
    Export current=export;if(current!=null)current.cancelled=true;
    source.close();
    try{gl.execute(()->{discardDetail();if(export!=null){abort(export);export=null;}if(tiles!=null){tiles.close();tiles=null;}RawEditFullSource cached=full;if(cached!=null){cached.close();full=null;removeCache();}});}catch(RejectedExecutionException ignored){if(full!=null){full.close();full=null;removeCache();}}
  }
  private static final class DetailRequest {
    final long id,edit,viewport;final RawEditState state;final boolean before,max;final RectF region;final int width,height;
    DetailRequest(long i,RawEditState s,long e,long v,boolean b,boolean m,RectF r,int w,int h){id=i;state=s;edit=e;viewport=v;before=b;max=m;region=r;width=w;height=h;}
  }
  private static final class DetailJob {
    final DetailRequest request;final Iterator<RawEditTilePlan.Tile> plan;final Canvas canvas;final long started=SystemClock.elapsedRealtime();Bitmap bitmap;
    DetailJob(DetailRequest r,int w,int h){request=r;plan=new RawEditTilePlan(r.region,r.width,r.height,w,h).iterator();bitmap=Bitmap.createBitmap(r.width,r.height,Bitmap.Config.ARGB_8888);canvas=new Canvas(bitmap);}
  }
  private static final class Export {
    final RawEditState state;final long started=SystemClock.elapsedRealtime();volatile boolean cancelled;Uri pending;ParcelFileDescriptor fd;NativeJpeg encoder;ByteBuffer strip;Iterator<RawEditTilePlan.Tile> plan;int y,rows;
    Export(RawEditState snapshot){state=snapshot;}
  }
}
