package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Synthetic cache/detail/export integration. Only unambiguous outputs from this isolated test are removed. */
final class RawEditFullSessionChecks {
  private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  private static RawEditSource source(){
    ByteBuffer pixels=ByteBuffer.allocateDirect(16*12*16).order(ByteOrder.nativeOrder());
    for(int i=0;i<16*12;i++)pixels.putFloat(.2f).putFloat(.3f).putFloat(.4f).putFloat(1);
    pixels.flip();return RawEditSource.synthetic(16,12,pixels,1201,809);
  }
  private static Map<Long,Integer> outputs(Context c){
    Map<Long,Integer> result=new HashMap<>();
    Bundle query=new Bundle();query.putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE);
    query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,"owner_package_name=? AND relative_path IN (?,?) AND _display_name LIKE ?");
    query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,new String[]{c.getPackageName(),"Pictures/LoupeDevelop/","Pictures/LoupeDevelop","Loupe-%.jpg"});
    try(Cursor rows=c.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        new String[]{"_id","is_pending"},query,null)){
      require(rows!=null,"owned output query");while(rows.moveToNext())result.put(rows.getLong(0),rows.getInt(1));
    }return result;
  }
  /** Public local-provider adapter; every operation still reaches the real target MediaStore. */
  private static final class InsertGate extends ContentProvider {
    final ContentResolver real;final AtomicBoolean busy,armed=new AtomicBoolean();
    final CountDownLatch inserted=new CountDownLatch(1);final AtomicReference<Uri> pending=new AtomicReference<>();
    InsertGate(Context context,AtomicBoolean busy){
      real=context.getContentResolver();this.busy=busy;
      android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority=MediaStore.AUTHORITY;info.exported=false;
      attachInfo(context,info);
    }
    public boolean onCreate(){return true;}
    public String getType(Uri uri){return real.getType(uri);}
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){return real.query(uri,projection,selection,args,sort);}
    public Cursor query(Uri uri,String[] projection,Bundle args,CancellationSignal signal){return real.query(uri,projection,args,signal);}
    public Uri insert(Uri uri,ContentValues values){
      Uri result=real.insert(uri,values);
      if(armed.compareAndSet(true,false)){
        // This runs synchronously before the Session receives the actual inserted URI.
        // Its current tile can finish, then preview priority deterministically holds later tiles.
        busy.set(true);pending.set(result);inserted.countDown();
      }
      return result;
    }
    public int update(Uri uri,ContentValues values,String selection,String[] args){return real.update(uri,values,selection,args);}
    public int delete(Uri uri,String selection,String[] args){return real.delete(uri,selection,args);}
    public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{return real.openFileDescriptor(uri,mode);}
    public ParcelFileDescriptor openFile(Uri uri,String mode,CancellationSignal signal)throws FileNotFoundException{return real.openFileDescriptor(uri,mode,signal);}
  }
  private static Context gatedContext(Context context,InsertGate gate){
    ContentResolver resolver=ContentResolver.wrap(gate);
    return new ContextWrapper(context){
      public Context getApplicationContext(){return this;}
      public ContentResolver getContentResolver(){return resolver;}
    };
  }
  private static Set<String> caches(Context c){Set<String> result=new HashSet<>();File[] files=c.getCacheDir().listFiles();if(files!=null)for(File f:files)if(f.getName().startsWith("raw-full-"))result.add(f.getName());return result;}
  private static void waitFor(CountDownLatch latch,int seconds,String message)throws Exception{require(latch.await(seconds,TimeUnit.SECONDS),message);}
  static void run(Instrumentation t)throws Exception{
    Context c=t.getTargetContext();Handler main=new Handler(Looper.getMainLooper());ExecutorService gl=Executors.newSingleThreadExecutor();
    Set<Long> baseline=outputs(c).keySet();Set<String> cacheBefore=caches(c);RawEditFullSession[] session={null};
    AtomicBoolean busy=new AtomicBoolean(true);CountDownLatch prepared=new CountDownLatch(1),detailed=new CountDownLatch(1),saved=new CountDownLatch(1),cancelled=new CountDownLatch(1);
    AtomicReference<Throwable> failure=new AtomicReference<>();AtomicInteger detailCount=new AtomicInteger(),saveCount=new AtomicInteger();AtomicReference<Bitmap> detailBitmap=new AtomicReference<>();
    InsertGate insertGate=new InsertGate(c,busy);Context sessionContext=gatedContext(c,insertGate);
    Uri testOutput=null;
    try{
      RawEditFullSession.Listener listener=new RawEditFullSession.Listener(){
        public void prepared(int w,int h,RawEditFullSource.Preview preview){try{require(w==1201&&h==809,"session uses full effective dimensions");require(preview.width==1201,"replacement preview is bounded");}catch(Throwable e){failure.set(e);}finally{prepared.countDown();}}
        public void prepareFailed(){failure.set(new AssertionError("synthetic prepare failed"));prepared.countDown();}
        public void detail(Bitmap bitmap,RectF region,long edit,long viewport,boolean before,boolean max){
          try{require(edit==2&&viewport==7&&before&&!max,"only latest edit/viewport/mode detail commits");require(bitmap.getWidth()==603&&bitmap.getHeight()==407,"screen-sized detail only");detailCount.incrementAndGet();detailBitmap.set(bitmap);}catch(Throwable e){bitmap.recycle();failure.set(e);}finally{detailed.countDown();}}
        public void saved(RawEditState snapshot){saveCount.incrementAndGet();saved.countDown();}
        public void saveFailed(){failure.set(new AssertionError("synthetic save failed"));saved.countDown();}
        public void saveCancelled(){cancelled.countDown();}
      };
      t.runOnMainSync(()->{session[0]=new RawEditFullSession(sessionContext,source(),gl,main,busy::get,listener);session[0].start();});
      waitFor(prepared,30,"background preparation finishes");if(failure.get()!=null)throw new AssertionError(failure.get());
      t.runOnMainSync(()->{session[0].requestDetail(new RawEditState(),1,6,false,true,new RectF(10,10,1200,808),603,407);session[0].requestDetail(new RawEditState(),2,7,true,false,new RectF(10,10,1200,808),603,407);});
      Thread.sleep(220);require(detailCount.get()==0,"preview gate has priority over detail");busy.set(false);
      waitFor(detailed,15,"latest viewport detail finishes");if(failure.get()!=null)throw new AssertionError(failure.get());require(detailCount.get()==1,"superseded detail did not commit");
      t.runOnMainSync(()->{busy.set(true);session[0].input();session[0].save(new RawEditState().with(RawEditState.MONO,1));});
      Thread.sleep(150);Set<Long> whileBusy=new HashSet<>(outputs(c).keySet());whileBusy.removeAll(baseline);require(whileBusy.isEmpty(),"preview gate also pauses export before creating output");
      busy.set(false);waitFor(saved,25,"scanline export completes");if(failure.get()!=null)throw new AssertionError(failure.get());require(saveCount.get()==1,"one export completion");
      Set<Long> made=new HashSet<>(outputs(c).keySet());made.removeAll(baseline);require(made.size()==1,"one isolated test output");testOutput=ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,made.iterator().next());
      try(InputStream in=c.getContentResolver().openInputStream(testOutput)){
        Bitmap image=BitmapFactory.decodeStream(in);require(image!=null&&image.getWidth()==1201&&image.getHeight()==809,"single original-size JPEG decodes");
        try{int pixel=image.getPixel(600,400);require(Color.blue(pixel)>Color.red(pixel)&&Color.red(pixel)>Color.green(pixel),"full export includes Mono tint");}finally{image.recycle();}
      }
      require(c.getContentResolver().delete(testOutput,null,null)==1,"remove exactly the produced test JPEG");testOutput=null;
      // Observe the real insert through the public provider adapter, without racing a fast export.
      insertGate.armed.set(true);t.runOnMainSync(()->session[0].save(new RawEditState()));
      waitFor(insertGate.inserted,10,"real pending insertion reached deterministic preview gate");
      Uri pending=insertGate.pending.get();require(pending!=null,"real MediaStore returned pending output");
      Map<Long,Integer> pendingRows=outputs(c);Set<Long> newPending=new HashSet<>(pendingRows.keySet());newPending.removeAll(baseline);
      require(newPending.size()==1&&newPending.contains(ContentUris.parseId(pending))&&pendingRows.get(ContentUris.parseId(pending))==1,"explicit query includes exactly the inserted owned pending JPEG");testOutput=pending;
      t.runOnMainSync(()->session[0].cancelSave());waitFor(cancelled,10,"pending export cancellation completes");require(saveCount.get()==1,"cancelled export did not publish completion");
      Set<Long> remain=new HashSet<>(outputs(c).keySet());remain.removeAll(baseline);require(remain.isEmpty(),"cancelled pending output removed");testOutput=null;
    }finally{
      if(session[0]!=null)t.runOnMainSync(session[0]::close);
      gl.submit(()->{}).get(10,TimeUnit.SECONDS);gl.shutdown();
      Bitmap bitmap=detailBitmap.get();if(bitmap!=null)bitmap.recycle();
      if(testOutput!=null)c.getContentResolver().delete(testOutput,null,null);
      Set<Long> made=new HashSet<>(outputs(c).keySet());made.removeAll(baseline);
      if(made.size()==1)c.getContentResolver().delete(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,made.iterator().next()),null,null);
      require(made.size()<=1,"preserve ambiguous new outputs");
    }
    require(caches(c).equals(cacheBefore),"session cache is removed after GL owner releases it");
    // An already cancelled queued preparation cannot publish to a new session.
    CountDownLatch block=new CountDownLatch(1),entered=new CountDownLatch(1);AtomicInteger late=new AtomicInteger();
    Future<?> blocker=RawEditFullSession.initial(()->{entered.countDown();try{block.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});waitFor(entered,5,"preparation gate entered");
    ExecutorService secondGl=Executors.newSingleThreadExecutor();RawEditFullSession[] second={null};
    try{
      RawEditFullSession.Listener listener=new RawEditFullSession.Listener(){public void prepared(int w,int h,RawEditFullSource.Preview p){late.incrementAndGet();}public void prepareFailed(){late.incrementAndGet();}public void detail(Bitmap b,RectF r,long e,long v,boolean a,boolean m){b.recycle();late.incrementAndGet();}public void saved(RawEditState s){late.incrementAndGet();}public void saveFailed(){late.incrementAndGet();}public void saveCancelled(){late.incrementAndGet();}};
      t.runOnMainSync(()->{second[0]=new RawEditFullSession(c,source(),secondGl,main,()->false,listener);second[0].start();second[0].close();});
    }finally{block.countDown();blocker.get(5,TimeUnit.SECONDS);RawEditFullSession.initial(()->{}).get(10,TimeUnit.SECONDS);secondGl.submit(()->{}).get(5,TimeUnit.SECONDS);secondGl.shutdown();}
    t.waitForIdleSync();require(late.get()==0&&caches(c).equals(cacheBefore),"closed session cannot publish a queued preparation or leave cache");
  }
}
