package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.widget.FrameLayout;
import java.io.*;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in private fixture supplied after APK installation; never contains photo data in the APK. */
final class ViewerRawLookChecks {
  private static void require(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
  private static Object field(Object owner,String name){try{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
  private static byte[] hash(File file)throws Exception {
    MessageDigest digest=MessageDigest.getInstance("SHA-256");
    try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
    return digest.digest();
  }
  static String run(Instrumentation t)throws Exception {
    File input=new File(t.getTargetContext().getFilesDir(),"viewer-look-raw-fixture.dng");
    MainActivity[] activity={null};PhotoLookController[] controller={null};
    LookPresetStore store=new LookPresetStore(new File(t.getTargetContext().getFilesDir(),"look-library"));
    String key=null,previewKey=null;Throwable primary=null;
    try {
      require(input.isFile()&&input.length()>1000000&&input.length()<256L*1024*1024,"private RAW fixture supplied separately");
      MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));activity[0]=a;
      PhotoRepository.Photo photo=new PhotoRepository.Photo(Uri.fromFile(input),"viewer-look-private.dng","/private-look-validation",input.length(),1,1);
      key=ArchiveStore.key(photo);previewKey=PhotoRepository.digest("raw-preview-v2:"+photo.uri+":"+photo.size+":"+photo.modified);byte[] original=hash(input);
      GalleryScreenChecks.finishInitialIndex(t,a);
      Bitmap embedded=PhotoRepository.bitmap(new PhotoRepository(a).fullPreview(photo),1600);
      TagDetailChecks.ui(t,()->{
        a.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root=new FrameLayout(a);ZoomImageView image=new ZoomImageView(a);root.addView(image);a.setContentView(root);
        try{controller[0]=new PhotoLookController(a,photo,image,()->{});}catch(IOException e){throw new AssertionError(e);}
        controller[0].original(embedded);
        controller[0].select(new LookPresetStore.Entry("cnt-like","CNT-like（仮）",RawEditLook.cntLike()));
      });
      long until=android.os.SystemClock.uptimeMillis()+150000;boolean[] ready={false};
      while(android.os.SystemClock.uptimeMillis()<until){TagDetailChecks.ui(t,()->ready[0]=!controller[0].busy()&&controller[0].readyToSave());if(ready[0])break;Thread.sleep(100);}
      require(ready[0],"actual RAW development and full-source viewer render completed");
      TagDetailChecks.ui(t,()->{
        require((Integer)field(controller[0],"width")==3072&&(Integer)field(controller[0],"height")==4080,"upright RAW dimensions match separately inspected fixture");
        Bitmap shown=(Bitmap)field(controller[0],"developed");
        require(shown!=null&&Math.max(shown.getWidth(),shown.getHeight())<=4000&&(long)shown.getWidth()*shown.getHeight()<=12000000,"bounded full-source RAW viewer image");
        require(Math.abs(shown.getWidth()/(double)shown.getHeight()-3072d/4080)<.001,"RAW portrait orientation retained");
        require(controller[0].applied().look.equals(RawEditLook.cntLike()),"RAW applied measured recipe");
      });
      require(Arrays.equals(original,hash(input)),"private RAW input unchanged");
      require(store.loadSelection(key).look.equals(RawEditLook.cntLike()),"RAW selection persisted");
      return "PASS private real DNG supplied after APK install; actual RAW development through shared full-source GPU; upright3072x4080 portrait, viewer<=4000px/12MP, measuredCNT recipe persisted, input bytes unchanged. Owned input/original-cache/embedded-preview cache cleanup verified. No screenshot, MediaStore output, send or photographic-quality claim.\n";
    } catch(Exception|Error failure){primary=failure;throw failure;}
    finally {
      List<Throwable> errors=new ArrayList<>();
      if(controller[0]!=null){
        PhotoLookController c=controller[0];
        cleanup(errors,()->{ExecutorService gl=(ExecutorService)field(c,"gl");TagDetailChecks.ui(t,c::close);require(gl.awaitTermination(30,TimeUnit.SECONDS),"RAW GL worker cleanup");});
      }
      boolean[] nativeStopped={false};
      cleanup(errors,()->{RawEditFullSession.initial(()->{}).get(45,TimeUnit.SECONDS);nativeStopped[0]=true;});
      cleanup(errors,()->{ExecutorService io=(ExecutorService)staticField(PhotoLookController.class,"STORAGE");io.submit(()->{}).get(30,TimeUnit.SECONDS);});
      String ownedKey=key;
      if(ownedKey!=null)cleanup(errors,()->store.clearSelection(ownedKey));
      if(nativeStopped[0]){
        if(ownedKey!=null&&activity[0]!=null)cleanup(errors,()->remove(new File(new File(activity[0].getCacheDir(),"originals"),ownedKey+".dng")));
        String ownedPreview=previewKey;
        if(ownedPreview!=null&&activity[0]!=null)for(String extension:new String[]{".jpg",".json",".part"})cleanup(errors,()->remove(new File(new File(activity[0].getCacheDir(),"previews"),ownedPreview+extension)));
        cleanup(errors,()->remove(input));
      }else errors.add(new IOException("RAW native cleanup not complete; private files retained for safe cleanup"));
      if(activity[0]!=null)cleanup(errors,()->TagDetailChecks.ui(t,()->{activity[0].getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);activity[0].finish();}));
      if(!errors.isEmpty()){
        Exception failure=new Exception("Private RAW cleanup failed");for(Throwable error:errors)failure.addSuppressed(error);
        if(primary!=null)primary.addSuppressed(failure);else throw failure;
      }
    }
  }
  private interface Cleanup {void run()throws Exception;}
  private static void cleanup(List<Throwable> errors,Cleanup step){try{step.run();}catch(Exception|Error failure){errors.add(failure);}}
  private static void remove(File file)throws IOException {Files.deleteIfExists(file.toPath());if(Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Owned RAW fixture remains");}

  private static Object staticField(Class<?> owner,String name){try{Field f=owner.getDeclaredField(name);f.setAccessible(true);return f.get(null);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
}
