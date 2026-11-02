package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.SystemClock;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in measurements; photographs supplied privately after install, never committed/captured. */
final class ViewerLookTimingChecks {
  private static void require(boolean value,String label){if(!value)throw new AssertionError(label);}
  private static Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
  private static byte[] hash(File file)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}return d.digest();}
  private static String pixels(Bitmap image)throws Exception{ByteBuffer bytes=ByteBuffer.allocate(image.getByteCount());image.copyPixelsToBuffer(bytes);return hex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));}
  private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
  private static void close(Instrumentation t,PhotoLookController[] c)throws Exception{
    File[] owned=new File[2];
    if(c[0]!=null){PhotoLookController previous=c[0];
      TagDetailChecks.ui(t,()->{try{RawEditFullSession session=(RawEditFullSession)field(previous,"full");if(session!=null){owned[0]=(File)field(session,"cacheFile");owned[1]=(File)field(field(session,"source"),"input");}}catch(Exception e){throw new AssertionError(e);}});
      ExecutorService gl=(ExecutorService)field(previous,"gl");TagDetailChecks.ui(t,previous::close);require(gl.awaitTermination(30,TimeUnit.SECONDS),"GL terminated");c[0]=null;
    }
    RawEditFullSession.initial(()->{}).get(45,TimeUnit.SECONDS);
    for(File ownedFile:owned)if(ownedFile!=null)require(!Files.exists(ownedFile.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS),"controller-owned input/full cache removed");
    Field f=PhotoLookController.class.getDeclaredField("STORAGE");f.setAccessible(true);((ExecutorService)f.get(null)).submit(()->{}).get(30,TimeUnit.SECONDS);
  }
  private static void remove(File f)throws Exception{Files.deleteIfExists(f.toPath());require(!f.exists(),"owned fixture cache removed");}
  /** Fault injection at the native identification checkpoint, not a latency estimate. */
  private static void cancelledIdentification(File source,File dir)throws Exception {
    File copy=File.createTempFile("look-identify-cancel-",".input",dir);
    Files.copy(source.toPath(),copy.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    boolean stopped=true;
    try {
      // Direct JNI has no Java postcheck. Removing the progress handler must make this fail.
      for(boolean direct:new boolean[]{true,false}){
        java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Throwable> result=new java.util.concurrent.atomic.AtomicReference<>();
        int threshold=direct?2:3;
        Thread worker=new Thread(()->{try{if(direct)NativeRaw.identify(copy.getAbsolutePath());else try(RawEditSource ignored=RawEditSource.forFullResolution(copy)){}result.set(new AssertionError("cancelled identify returned dimensions/source"));}catch(Throwable e){result.set(e);}},"look-identify-cancel-check"){
          @Override public boolean isInterrupted(){return super.isInterrupted()||checks.incrementAndGet()>=threshold;}
        };
        stopped=false;worker.start();worker.join(30000);
        if(worker.isAlive()){worker.interrupt();worker.join(30000);}
        stopped=!worker.isAlive();require(stopped,"cancelled identify worker stopped; retain owned copy if still active");
        require(checks.get()>=threshold&&result.get() instanceof IOException,direct?"native identify checkpoint cancellation required":"factory identify cancellation required");
        require(direct?copy.exists():!copy.exists(),direct?"identify does not own caller file":"interrupted identify removes owned copy");
      }
    }finally{if(stopped)remove(copy);}
  }
  static String run(Instrumentation t,boolean raw)throws Exception{
    File input=raw?new File(t.getTargetContext().getFilesDir(),"viewer-look-raw-fixture.dng"):File.createTempFile("look-timing-",".jpg",t.getTargetContext().getCacheDir());
    MainActivity[] activity={null};PhotoLookController[] c={null};ZoomImageView[] image={null};String key=null;boolean stopped=false;Throwable primary=null;
    LookPresetStore store=new LookPresetStore(new File(t.getTargetContext().getFilesDir(),"look-library"));
    try{
      if(raw)require(input.isFile()&&input.length()>1000000&&input.length()<256L*1024*1024,"private RAW supplied");
      else{Bitmap b=Bitmap.createBitmap(4000,3000,Bitmap.Config.ARGB_8888);try{Paint p=new Paint();p.setShader(new LinearGradient(0,0,4000,3000,0xffaa3355,0xff22bbaa,Shader.TileMode.CLAMP));new Canvas(b).drawPaint(p);try(FileOutputStream out=new FileOutputStream(input)){require(b.compress(Bitmap.CompressFormat.JPEG,95,out),"synthetic JPEG");}}finally{b.recycle();}}
      byte[] original=hash(input);
      if(raw)cancelledIdentification(input,t.getTargetContext().getCacheDir());
      MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));activity[0]=a;GalleryScreenChecks.finishInitialIndex(t,a);
      PhotoRepository.Photo photo=new PhotoRepository.Photo(Uri.fromFile(input),raw?"timing.dng":"timing.jpg","/private-look-timing",input.length(),1,1);key=ArchiveStore.key(photo);
      TagDetailChecks.ui(t,()->{a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);FrameLayout root=new FrameLayout(a);image[0]=new ZoomImageView(a);root.addView(image[0]);a.setContentView(root);});
      StringBuilder results=new StringBuilder("PASS Look timing type=").append(raw?"raw":"jpeg").append("; three fresh controllers/original-cache misses, each followed by same-controller repeat; OS page cache/JIT not cleared; original hash unchanged. Frame commit is GPU submission, not display scanout.\n");
      for(int trial=0;trial<3;trial++){
        close(t,c);store.clearSelection(key);remove(new File(new File(a.getCacheDir(),"originals"),key+(raw?".dng":".jpg")));
        TagDetailChecks.ui(t,()->{try{c[0]=new PhotoLookController(a,photo,image[0],()->{});c[0].original(Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888));}catch(IOException e){throw new AssertionError(e);}});
        t.waitForIdleSync();
        for(int repeat=0;repeat<2;repeat++){
          long[] start={0};TagDetailChecks.ui(t,()->{start[0]=SystemClock.elapsedRealtimeNanos();c[0].select(new LookPresetStore.Entry("cnt-like","CNT-like",RawEditLook.cntLike()));});
          long end=SystemClock.uptimeMillis()+150000;boolean ready=false;
          while(SystemClock.uptimeMillis()<end){boolean[] done={false};TagDetailChecks.ui(t,()->{try{done[0]=!c[0].busy()&&c[0].readyToSave()&&(Long)field(c[0],"frameCommittedNs")>start[0];}catch(Exception e){throw new AssertionError(e);}});if(done[0]){ready=true;break;}Thread.sleep(20);}
          require(ready,"Look reached committed GPU frame");
          Bitmap[] copy={null};long[] frame={0};TagDetailChecks.ui(t,()->{try{frame[0]=(Long)field(c[0],"frameCommittedNs");Bitmap b=(Bitmap)field(c[0],"developed");copy[0]=b.copy(Bitmap.Config.ARGB_8888,false);}catch(Exception e){throw new AssertionError(e);}});
          try{results.append("TIMING trial=").append(trial).append(" repeat=").append(repeat).append(" frame_ns=").append(frame[0]-start[0]).append(" width=").append(copy[0].getWidth()).append(" height=").append(copy[0].getHeight()).append(" pixels_sha256=").append(pixels(copy[0])).append('\n');}finally{copy[0].recycle();}
        }
      }
      require(Arrays.equals(original,hash(input)),"source bytes unchanged");return results.toString();
    }catch(Exception|Error failure){primary=failure;throw failure;}
    finally{
      List<Throwable> failures=new ArrayList<>();
      try{close(t,c);stopped=true;}catch(Exception|Error e){failures.add(e);}
      if(key!=null)try{store.clearSelection(key);}catch(Exception e){failures.add(e);}
      if(stopped){if(key!=null&&activity[0]!=null)try{remove(new File(new File(activity[0].getCacheDir(),"originals"),key+(raw?".dng":".jpg")));}catch(Exception e){failures.add(e);}try{remove(input);}catch(Exception e){failures.add(e);}}
      if(activity[0]!=null)try{TagDetailChecks.ui(t,()->{activity[0].getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);activity[0].finish();});}catch(Exception|Error e){failures.add(e);}
      if(!failures.isEmpty()){Exception failure=new Exception("Timing cleanup failed; unresolved native work retains private files");for(Throwable e:failures)failure.addSuppressed(e);if(primary!=null)primary.addSuppressed(failure);else throw failure;}
    }
  }
}
