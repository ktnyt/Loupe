package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.View;
import android.widget.FrameLayout;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Synthetic originals only. Chooser intents are intercepted; no editor receives these tests. */
final class PhotoEditChecks {
  static void await(BooleanSupplier condition)throws Exception{
    long until=android.os.SystemClock.uptimeMillis()+5000;
    while(!condition.getAsBoolean()){
      if(android.os.SystemClock.uptimeMillis()>until)throw new AssertionError("edit condition timed out");
      Thread.sleep(20);
    }
  }
  static final class Fixture {
    PhotoViewerUi viewer;PhotoEditor editor;
  }
  static Fixture fixture(Instrumentation t,MainActivity a,PhotoRepository.Photo photo,
      ExecutorService worker,SharePreparation preparation)throws Exception{
    Fixture f=new Fixture();
    TagDetailChecks.ui(t,()->{f.viewer=new PhotoViewerUi(a,photo);f.viewer.zoom();
      f.editor=new PhotoEditor(a,f.viewer,photo,worker,preparation);a.setContentView(f.viewer);});
    t.waitForIdleSync();await(()->f.viewer.hasWindowFocus());return f;
  }
  static void click(Instrumentation t,Fixture f)throws Exception{TagDetailChecks.ui(t,()->f.editor.button.performClick());}
  static String label(Instrumentation t,Fixture f)throws Exception{
    String[] result={""};TagDetailChecks.ui(t,()->result[0]=f.editor.button.getText().toString());return result[0];
  }
  static String run(Instrumentation t)throws Exception{
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    ExecutorService worker=Executors.newSingleThreadExecutor();
    AtomicReference<Intent> chosen=new AtomicReference<>();AtomicInteger launches=new AtomicInteger();
    Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){
      public Instrumentation.ActivityResult onStartActivity(Intent intent){
        if(!Intent.ACTION_CHOOSER.equals(intent.getAction()))return null;
        chosen.set(intent);launches.incrementAndGet();return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);
      }
    };
    File original=new File(a.getCacheDir(),"edit-synthetic-original.jpg");
    List<File> copies=new ArrayList<>();
    try{
      GalleryScreenChecks.finishInitialIndex(t,a);t.addMonitor(monitor);
      Bitmap bitmap=Bitmap.createBitmap(40,30,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.GRAY);
      try(OutputStream out=new FileOutputStream(original)){bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);}bitmap.recycle();
      byte[] before=Files.readAllBytes(original.toPath());
      PhotoRepository.Photo photo=new PhotoRepository.Photo(Uri.fromFile(original),"edit-synthetic.jpg","/synthetic",original.length(),0,0);
      Fixture real=fixture(t,a,photo,worker,new SharePreparation(a,new PhotoRepository(a)));
      click(t,real);await(()->launches.get()==1);
      Intent edit=chosen.get().getParcelableExtra(Intent.EXTRA_INTENT);
      TagDetailChecks.require(Intent.ACTION_EDIT.equals(edit.getAction())&&"image/jpeg".equals(edit.getType()),"ACTION_EDIT with original MIME");
      TagDetailChecks.require(edit.getFlags()==Intent.FLAG_GRANT_READ_URI_PERMISSION,"read grant only");
      TagDetailChecks.require(edit.getData().equals(edit.getClipData().getItemAt(0).getUri())&&"jp.nano.rawgallery.share".equals(edit.getData().getAuthority()),"same copy URI in data and ClipData");
      File copy=new File(a.getCacheDir(),"shares/"+edit.getData().getLastPathSegment());copies.add(copy);
      TagDetailChecks.require(!copy.equals(original)&&Arrays.equals(before,Files.readAllBytes(copy.toPath()))&&Arrays.equals(before,Files.readAllBytes(original.toPath())),"byte-identical copy and unchanged original");
      try{a.getContentResolver().openFileDescriptor(edit.getData(),"w");throw new AssertionError("copy must reject write");}catch(FileNotFoundException expected){}
      Intent raw=PhotoEditor.intent(Uri.parse("content://jp.nano.rawgallery.share/synthetic.dng"),RawFormats.mime("synthetic.dng"));
      TagDetailChecks.require("image/x-adobe-dng".equals(raw.getType()),"RAW MIME stays RAW");
      int initial=launches.get();
      for(boolean navigate:new boolean[]{false,true}){
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger writes=new AtomicInteger();AtomicReference<File> made=new AtomicReference<>();
        SharePreparation slow=new SharePreparation(new File(a.getCacheDir(),"shares"),(p,rawFormat,destination)->{
          writes.incrementAndGet();made.set(destination);started.countDown();
          boolean waiting=true;while(waiting)try{release.await();waiting=false;}catch(InterruptedException ignored){}
          Files.write(destination.toPath(),before);
        });
        Fixture f=fixture(t,a,photo,worker,slow);click(t,f);click(t,f);
        TagDetailChecks.require(started.await(3,TimeUnit.SECONDS),"copy started");
        try{
          if(navigate)TagDetailChecks.ui(t,()->a.setContentView(new FrameLayout(a)));
          else{
            long until=android.os.SystemClock.uptimeMillis()+3000;
            while(!"中止".equals(label(t,f))){if(android.os.SystemClock.uptimeMillis()>until)throw new AssertionError("cancel label");Thread.sleep(20);}
            click(t,f);
          }
        }finally{release.countDown();}
        worker.submit(()->{}).get(5,TimeUnit.SECONDS);t.waitForIdleSync();
        TagDetailChecks.require(writes.get()==1&&launches.get()==initial&&!made.get().exists(),"cancel or detach prevents late launch and discards copy");
      }
      AtomicInteger attempts=new AtomicInteger();
      SharePreparation failing=new SharePreparation(new File(a.getCacheDir(),"shares"),(p,rawFormat,destination)->{
        if(attempts.incrementAndGet()==1)throw new IOException("synthetic");Files.write(destination.toPath(),before);
      });
      Fixture retry=fixture(t,a,photo,worker,failing);click(t,retry);
      worker.submit(()->{}).get(5,TimeUnit.SECONDS);t.waitForIdleSync();
      TagDetailChecks.ui(t,()->TagDetailChecks.require(retry.editor.button.getContentDescription().toString().contains("再試行"),"failure offers retry on the same button"));
      click(t,retry);await(()->launches.get()==initial+1);
      Intent retried=chosen.get().getParcelableExtra(Intent.EXTRA_INTENT);copies.add(new File(a.getCacheDir(),"shares/"+retried.getData().getLastPathSegment()));
      TagDetailChecks.require(attempts.get()==2&&Arrays.equals(before,Files.readAllBytes(original.toPath())),"retry succeeds without original mutation");
      return "PASS original-format copy/unchanged original, read-only data+ClipData grant, RAW MIME, intercepted chooser, duplicate prevention, inline cancel, detach/late completion cleanup, prepare failure/retry. No external editor launched; actual editor/save compatibility is separate.\n";
    }finally{
      t.removeMonitor(monitor);worker.shutdownNow();
      TagDetailChecks.ui(t,()->a.finish());original.delete();for(File copy:copies)copy.delete();
    }
  }
}
