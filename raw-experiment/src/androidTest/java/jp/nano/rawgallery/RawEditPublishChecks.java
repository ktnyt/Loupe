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
    public void prepared(int w,int h,RawEditFullSource.Preview preview){
      if(w!=65||h!=43||preview.width!=65||preview.height!=43)callbackProblem.set("Unexpected synthetic full cache dimensions");
      prepared.incrementAndGet();ready.countDown();
    }
    public void prepareFailed(){prepareFailed.incrementAndGet();ready.countDown();}
    public void detail(Bitmap bitmap,RectF source,long edit,long viewport,boolean before,boolean max){bitmap.recycle();callbackProblem.set("Unexpected detail callback");terminal.countDown();}
    public void saved(RawEditState snapshot){saved.incrementAndGet();terminal.countDown();}
    public void saveFailed(){failed.incrementAndGet();terminal.countDown();}
    public void saveCancelled(){cancelled.incrementAndGet();terminal.countDown();}
  }
  static void run(Instrumentation instrumentation)throws Exception {
    runCase(instrumentation,true);
    runCase(instrumentation,false);
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
