package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.widget.ImageView;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class GalleryLoadingChecks {
  static String run(Instrumentation test, MainActivity activity) throws Exception {
    File fixture=File.createTempFile("list-queue-check-", ".jpg", activity.getCacheDir());
    Bitmap image=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888); image.eraseColor(Color.RED);
    try(FileOutputStream out=new FileOutputStream(fixture)){image.compress(Bitmap.CompressFormat.JPEG,90,out);}
    PhotoRepository.Photo p=new PhotoRepository.Photo(Uri.fromFile(fixture),"synthetic.jpg","",fixture.length(),1,0);
    PhotoRepository.Photo missing=new PhotoRepository.Photo(Uri.parse("file:///missing-list-fixture.jpg"),"synthetic.jpg","",1,1,0);
    PreviewPlaceholders placeholders=PreviewPlaceholders.get(activity);
    placeholders.store(missing,image); image.recycle();
    CountDownLatch placeholder=new CountDownLatch(1);
    placeholders.load(missing,()->true,b -> {if(b.getWidth()>0 && b.getHeight()>0)placeholder.countDown();});
    if(!placeholder.await(3,TimeUnit.SECONDS))throw new AssertionError("stored hash needed original file");
    PhotoRepository.Photo changed=new PhotoRepository.Photo(missing.uri,"synthetic.jpg","",1,2,0);
    if(placeholders.has(changed))throw new AssertionError("old version hash reused");
    Method preview=MainActivity.class.getDeclaredMethod("previewInto",ImageView.class,PhotoRepository.Photo.class,int.class);preview.setAccessible(true);
    Field generation=MainActivity.class.getDeclaredField("generation");generation.setAccessible(true);
    Field images=MainActivity.class.getDeclaredField("images");images.setAccessible(true);
    ExecutorService pool=(ExecutorService)images.get(activity);
    CountDownLatch blocked=new CountDownLatch(2),release=new CountDownLatch(1);
    for(int i=0;i<2;i++)pool.execute(()->{blocked.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
    if(!blocked.await(5,TimeUnit.SECONDS))throw new AssertionError("pool not blocked");
    DiagnosticImageView[] hero=new DiagnosticImageView[1];
    DiagnosticLog.event("test.list_queue",0);
    try {
      test.runOnMainSync(()->{try{
        for(int i=0;i<36;i++)preview.invoke(activity,new DiagnosticImageView(activity),missing,400);
        generation.setInt(activity,generation.getInt(activity)+1);
        hero[0]=new DiagnosticImageView(activity);activity.setContentView(hero[0]);
        preview.invoke(activity,hero[0],p,1600);
      }catch(Exception e){throw new RuntimeException(e);}});
    }finally{release.countDown();}
    DiagnosticChecks.waitFor(test,()->hero[0].getDrawable()!=null);
    Thread.sleep(300);
    String logs=new String(Files.readAllBytes(new File(activity.getFilesDir(),"diagnostics/events.jsonl").toPath()), java.nio.charset.StandardCharsets.UTF_8);
    logs=logs.substring(logs.lastIndexOf("\"event\":\"test.list_queue\""));
    if(logs.contains("\"event\":\"image.fail\""))throw new AssertionError("abandoned list touched source");
    if(logs.split("\\\"event\\\":\\\"image.discard\\\"",-1).length-1<36)throw new AssertionError("old list was not discarded");
    // A recycled cell's late placeholder must not replace its actual image.
    CountDownLatch readDone=new CountDownLatch(1);
    AtomicBoolean active=new AtomicBoolean(true),applied=new AtomicBoolean(false);
    java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
    Field reads=PreviewPlaceholders.class.getDeclaredField("reads");reads.setAccessible(true);
    ExecutorService reader=(ExecutorService)reads.get(placeholders);
    test.runOnMainSync(()->{
      placeholders.load(missing,()->{checks.incrementAndGet();return active.get();},b->applied.set(true));
      reader.execute(()->{active.set(false);readDone.countDown();});
      try { if(!readDone.await(3,TimeUnit.SECONDS))throw new AssertionError("placeholder worker timeout"); }
      catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    });
    test.waitForIdleSync();
    if(checks.get()!=2 || applied.get())throw new AssertionError("late placeholder invalidation not enforced");
    fixture.delete();
    return "PASS 36 abandoned list requests discarded before source reads, persisted ThumbHash without original, version invalidation, stale placeholder suppressed\n";
  }
}
