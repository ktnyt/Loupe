package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.io.*;
import java.nio.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Isolated provider + synthetic pixels only: exercises real cache, GL, JPEG and publish arbitration. */
final class RawEditPublishChecks {
  private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  private static void await(CountDownLatch latch,String message)throws InterruptedException {
    require(latch.await(15,TimeUnit.SECONDS),message);
  }
  private static final class FakeProvider extends ContentProvider {
    final File jpeg;
    final boolean blockPublish;
    final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    final AtomicInteger updates=new AtomicInteger(),deletes=new AtomicInteger();
    FakeProvider(File jpeg,boolean blockPublish){this.jpeg=jpeg;this.blockPublish=blockPublish;}
    public boolean onCreate(){return true;}
    private void barrier() {
      entered.countDown();
      try{if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Synthetic provider barrier timed out");}
      catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Synthetic provider interrupted");}
    }
    public Uri insert(Uri uri,ContentValues values){
      require(Integer.valueOf(1).equals(values.getAsInteger(MediaStore.Images.Media.IS_PENDING)),"insert must be pending");
      return Uri.parse("content://media/external/images/media/55");
    }
    public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
      if(!blockPublish)barrier();
      return ParcelFileDescriptor.open(jpeg,ParcelFileDescriptor.MODE_CREATE|ParcelFileDescriptor.MODE_TRUNCATE|ParcelFileDescriptor.MODE_READ_WRITE);
    }
    public int update(Uri uri,ContentValues values,String selection,String[] args){
      require(Integer.valueOf(0).equals(values.getAsInteger(MediaStore.Images.Media.IS_PENDING)),"only pending publication may update");
      updates.incrementAndGet();if(blockPublish)barrier();return 1;
    }
    public int delete(Uri uri,String selection,String[] args){deletes.incrementAndGet();if(jpeg.exists())require(jpeg.delete(),"private synthetic output deleted");return 1;}
    public String getType(Uri uri){return "image/jpeg";}
    public Cursor query(Uri uri,String[] columns,String selection,String[] args,String order){return null;}
  }
  private static final class Events implements RawEditFullSession.Listener {
    final CountDownLatch ready=new CountDownLatch(1),terminal=new CountDownLatch(1);
    final AtomicInteger prepared=new AtomicInteger(),prepareFailed=new AtomicInteger(),saved=new AtomicInteger(),failed=new AtomicInteger(),cancelled=new AtomicInteger();
    final AtomicReference<String> callbackProblem=new AtomicReference<>();
    final AtomicReference<RawEditState> savedSnapshot=new AtomicReference<>();
    public void prepared(int w,int h,RawEditFullSource.Preview preview){
      if(w!=65||h!=43||preview.width!=65||preview.height!=43)callbackProblem.set("Unexpected synthetic full cache dimensions");
      prepared.incrementAndGet();ready.countDown();
    }
    public void prepareFailed(){prepareFailed.incrementAndGet();ready.countDown();}
    public void detail(Bitmap bitmap,RectF source,long edit,long viewport,boolean before,boolean max){bitmap.recycle();callbackProblem.set("Unexpected detail callback");terminal.countDown();}
    public void saved(RawEditState snapshot){savedSnapshot.compareAndSet(null,snapshot);saved.incrementAndGet();terminal.countDown();}
    public void saveFailed(){failed.incrementAndGet();terminal.countDown();}
    public void saveCancelled(){cancelled.incrementAndGet();terminal.countDown();}
  }
  static void run(Instrumentation instrumentation)throws Exception {
    runCase(instrumentation,true);
    runCase(instrumentation,false);
  }
  /** Runs the actual session -> native JPEG -> pending publication path without MediaStore writes. */
  static void runLook(Instrumentation instrumentation)throws Exception {
    long started=SystemClock.elapsedRealtime();Context base=instrumentation.getTargetContext();
    File folder=new File(base.getCacheDir(),"synthetic-look-publish-"+java.util.UUID.randomUUID());
    require(folder.mkdir(),"create isolated look publish cache directory");
    FakeProvider provider=new FakeProvider(new File(folder,"synthetic-look-output.jpg"),true);
    ProviderInfo info=new ProviderInfo();info.authority="media";info.exported=false;provider.attachInfo(base,info);
    ContentResolver resolver=ContentResolver.wrap(provider);
    Context isolated=new ContextWrapper(base){
      public Context getApplicationContext(){return this;}
      public ContentResolver getContentResolver(){return resolver;}
      public File getCacheDir(){return folder;}
    };
    ExecutorService gl=Executors.newSingleThreadExecutor(r->new Thread(r,"synthetic-look-publish-gl"));
    Handler main=new Handler(Looper.getMainLooper());Events events=new Events();
    ByteBuffer pixels=ByteBuffer.allocateDirect(3*2*16).order(ByteOrder.nativeOrder());
    for(int i=0;i<6;i++)pixels.putFloat(.2f).putFloat(.4f).putFloat(.6f).putFloat(1);pixels.flip();
    RawEditSource source=RawEditSource.synthetic(3,2,pixels,65,43);RawEditFullSession session=null;
    RawEditState snapshot=RawEditState.neutral().withLook(RawEditLook.cntLike().withGrainEnabled(true).withSeed(650043));
    require(snapshot.look().toneEnabled()&&snapshot.look().lutEnabled()&&snapshot.look().grainEnabled(),"all look stages enabled for actual JPEG save");
    try {
      session=new RawEditFullSession(isolated,source,gl,main,()->false,events);RawEditFullSession active=session;
      main.post(active::start);await(events.ready,"look full preparation callback arrived");
      require(events.callbackProblem.get()==null&&events.prepared.get()==1&&events.prepareFailed.get()==0,"look full preparation succeeded");
      main.post(()->active.save(snapshot));await(provider.entered,"look pending publication update entered");provider.release.countDown();
      await(events.terminal,"look terminal save callback arrived");gl.submit(()->{}).get(10,TimeUnit.SECONDS);instrumentation.waitForIdleSync();
      require(events.callbackProblem.get()==null&&events.saved.get()==1&&events.failed.get()==0&&events.cancelled.get()==0,"one successful look save callback");
      require(events.savedSnapshot.get()==snapshot&&events.savedSnapshot.get().look()==snapshot.look(),"saved callback retains the exact immutable look snapshot");
      require(provider.updates.get()==1&&provider.deletes.get()==0&&provider.jpeg.isFile(),"look output published once in isolated provider");
      BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;options.inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB);
      Bitmap actual=BitmapFactory.decodeFile(provider.jpeg.getAbsolutePath(),options);require(actual!=null,"published look JPEG decodes");
      try {
        require(actual.getWidth()==65&&actual.getHeight()==43&&"image/jpeg".equals(options.outMimeType),"look JPEG retains original dimensions and format");
        File referenceCache=new File(folder,"synthetic-look-reference.ppm");
        // Reproduce the same analytic full-resolution scene independently of the session's cache.
        try(RawEditSource referenceSource=RawEditSource.synthetic(3,2,pixels.duplicate().order(ByteOrder.nativeOrder()),65,43)){
          referenceSource.prepareFull(referenceCache,new NativeRaw.Cancellation());
          try(RawEditFullSource full=RawEditFullSource.open(referenceCache);RawEditRenderer renderer=new RawEditRenderer(full.width,full.height,full.region(new Rect(0,0,full.width,full.height)))){
            renderer.grainDimensions(full.width,full.height);
            Bitmap expected=renderer.render(snapshot,false,false,2048),neutral=renderer.render(RawEditState.neutral(),false,false,2048);
            try{compareLookJpeg(actual,expected,neutral);}finally{expected.recycle();neutral.recycle();}
          }
        }finally{require(!referenceCache.exists()||referenceCache.delete(),"look reference cache cleanup");}
      }finally{actual.recycle();}
      android.util.Log.i("LoupeRawEdit","look.publish ms="+(SystemClock.elapsedRealtime()-started)+" width=65 height=43 isolated=true");
    }finally{
      provider.release.countDown();if(session!=null)session.close();else source.close();RawEditFullSession.initial(()->{}).get(10,TimeUnit.SECONDS);
      try{gl.submit(()->{}).get(10,TimeUnit.SECONDS);}finally{gl.shutdown();require(gl.awaitTermination(10,TimeUnit.SECONDS),"look publish GL worker terminated");}
      File[] remaining=folder.listFiles();if(remaining!=null)for(File file:remaining){require(!file.getName().startsWith("raw-full-"),"look session full cache removed after GL barrier");require(file.delete(),"look publish private file cleanup");}
      require(folder.delete(),"look publish isolated directory cleanup");provider.shutdown();
    }
  }
  private static void compareLookJpeg(Bitmap actual,Bitmap expected,Bitmap neutral){
    require(actual.getWidth()==expected.getWidth()&&actual.getHeight()==expected.getHeight(),"equal look JPEG/reference dimensions");
    double absolute=0,squared=0,neutralError=0,signal=0;double[] bias=new double[3];int count=actual.getWidth()*actual.getHeight();
    for(int y=0;y<actual.getHeight();y++)for(int x=0;x<actual.getWidth();x++){
      int a=actual.getPixel(x,y),e=expected.getPixel(x,y),n=neutral.getPixel(x,y);
      for(int c=0;c<3;c++){int shift=16-c*8,av=a>>shift&255,ev=e>>shift&255,nv=n>>shift&255,d=av-ev;absolute+=Math.abs(d);squared+=d*d;bias[c]+=d;neutralError+=Math.abs(av-nv);signal+=Math.abs(ev-nv);}
    }
    double samples=count*3.,mae=absolute/samples,rms=Math.sqrt(squared/samples),neutralMae=neutralError/samples,signalMae=signal/samples,maxBias=0;
    for(int c=0;c<3;c++){bias[c]/=count;maxBias=Math.max(maxBias,Math.abs(bias[c]));}
    // JPEG 95 uses chroma subsampling: tolerate localized edge loss while bounding aggregate error/bias.
    require(mae<=4&&rms<=9&&maxBias<=1.5,"published look JPEG/reference mismatch MAE="+mae+" RMS="+rms+" maxBias="+maxBias);
    require(signalMae>1&&mae<neutralMae,"published output must contain the look signal, not Neutral: referenceSignal="+signalMae+" lookMAE="+mae+" neutralMAE="+neutralMae);
    android.util.Log.i("LoupeRawEdit","look.jpeg mae_code="+mae+" rms_code="+rms+" bias_rgb="+bias[0]+","+bias[1]+","+bias[2]+" neutral_mae_code="+neutralMae+" look_signal_mae_code="+signalMae+" jpeg_quality=95 width="+actual.getWidth()+" height="+actual.getHeight());
  }
  private static void runCase(Instrumentation instrumentation,boolean publishWins)throws Exception {
    Context base=instrumentation.getTargetContext();
    File folder=new File(base.getCacheDir(),"synthetic-publish-"+java.util.UUID.randomUUID());
    require(folder.mkdir(),"create isolated synthetic cache directory");
    FakeProvider provider=new FakeProvider(new File(folder,"synthetic-output.jpg"),publishWins);
    ProviderInfo info=new ProviderInfo();info.authority="media";info.exported=false;provider.attachInfo(base,info);
    ContentResolver resolver=ContentResolver.wrap(provider);
    Context isolated=new ContextWrapper(base){
      public Context getApplicationContext(){return this;}
      public ContentResolver getContentResolver(){return resolver;}
      public File getCacheDir(){return folder;}
    };
    ExecutorService gl=Executors.newSingleThreadExecutor(r->new Thread(r,"synthetic-publish-gl"));
    Handler main=new Handler(Looper.getMainLooper());Events events=new Events();
    ByteBuffer pixels=ByteBuffer.allocateDirect(3*2*16).order(ByteOrder.nativeOrder());
    for(int i=0;i<6;i++)pixels.putFloat(.2f).putFloat(.4f).putFloat(.6f).putFloat(1);pixels.flip();
    RawEditSource source=RawEditSource.synthetic(3,2,pixels,65,43);
    RawEditFullSession session=null;
    try {
      session=new RawEditFullSession(isolated,source,gl,main,()->false,events);
      RawEditFullSession active=session;
      main.post(active::start);await(events.ready,"full preparation callback arrived");
      require(events.callbackProblem.get()==null&&events.prepared.get()==1&&events.prepareFailed.get()==0,"synthetic preparation succeeded");
      main.post(()->active.save(new RawEditState()));
      await(provider.entered,publishWins?"publication update entered":"output open entered before publication gate");
      CountDownLatch returned=new CountDownLatch(1);
      main.post(()->{try{active.cancelSave();}finally{returned.countDown();}});
      // Never use runOnMainSync here: old locking code must fail promptly instead of hanging the test runner.
      boolean prompt=returned.await(1000,TimeUnit.MILLISECONDS);
      provider.release.countDown();
      require(prompt,"cancelSave must return on main thread while provider I/O is blocked");
      await(events.terminal,"exactly one terminal save callback arrived");
      gl.submit(()->{}).get(10,TimeUnit.SECONDS);
      CountDownLatch settled=new CountDownLatch(1);main.postDelayed(settled::countDown,100);await(settled,"terminal callback settling fence");
      instrumentation.waitForIdleSync();
      require(events.callbackProblem.get()==null&&events.failed.get()==0,"save must not fail");
      require(events.saved.get()+events.cancelled.get()+events.failed.get()==1,"one terminal callback only");
      if(publishWins){
        require(events.saved.get()==1&&events.cancelled.get()==0,"publication gate wins cancellation and reports saved");
        require(provider.updates.get()==1&&provider.deletes.get()==0&&provider.jpeg.isFile(),"published synthetic output survives");
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(provider.jpeg.getAbsolutePath(),bounds);
        require(bounds.outWidth==65&&bounds.outHeight==43&&"image/jpeg".equals(bounds.outMimeType),"published JPEG validates at original dimensions");
      }else{
        require(events.cancelled.get()==1&&events.saved.get()==0,"cancellation before publication reports cancelled");
        require(provider.updates.get()==0&&provider.deletes.get()==1&&!provider.jpeg.exists(),"cancelled pending output deleted without publication");
      }
    }finally{
      // Every failure path unblocks fake I/O before closing or waiting for the GL owner.
      provider.release.countDown();
      if(session!=null)session.close();else source.close();
      RawEditFullSession.initial(()->{}).get(10,TimeUnit.SECONDS);
      try{gl.submit(()->{}).get(10,TimeUnit.SECONDS);}finally{gl.shutdown();require(gl.awaitTermination(10,TimeUnit.SECONDS),"synthetic GL worker terminated");}
      File[] remaining=folder.listFiles();
      if(remaining!=null)for(File file:remaining){require(!file.getName().startsWith("raw-full-"),"session full cache removed after GL barrier");require(file.delete(),"synthetic output cleanup");}
      require(folder.delete(),"isolated synthetic cache directory cleanup");
      provider.shutdown();
    }
  }
}
