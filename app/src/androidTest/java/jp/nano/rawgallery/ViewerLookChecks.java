package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.widget.FrameLayout;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Synthetic end-to-end exercise of viewer Look rendering and durable selection only. */
final class ViewerLookChecks {
  static volatile int rawEditActivityLaunches;
  private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
  private static Bitmap shown(ZoomImageView image){try{Field f=ZoomImageView.class.getDeclaredField("bitmap");f.setAccessible(true);return (Bitmap)f.get(image);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
  private static String hash(String value)throws Exception {byte[] b=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}
  private static void waitFor(Instrumentation t,PhotoLookController c,String id,long limitMs)throws Exception {
    long until=android.os.SystemClock.uptimeMillis()+limitMs;
    while(android.os.SystemClock.uptimeMillis()<until){final boolean[] ok={false};TagDetailChecks.ui(t,()->ok[0]=!c.busy()&&c.applied()!=null&&(id==null||id.equals(c.applied().id)));
      if(ok[0])return;Thread.sleep(100);}
    throw new AssertionError("Look operation did not reach expected committed state");
  }
  private static void waitMessage(Instrumentation t,PhotoLookController c,long limitMs)throws Exception {
    long until=android.os.SystemClock.uptimeMillis()+limitMs;
    while(android.os.SystemClock.uptimeMillis()<until){final boolean[] ok={false};TagDetailChecks.ui(t,()->ok[0]=!c.busy()&&!c.message().isEmpty());if(ok[0])return;Thread.sleep(100);}
    throw new AssertionError("Look restore error state did not appear");
  }
  private static void awaitStorage()throws Exception {
    Field f=PhotoLookController.class.getDeclaredField("STORAGE");f.setAccessible(true);
    ((java.util.concurrent.ExecutorService)f.get(null)).submit(()->{}).get(30,java.util.concurrent.TimeUnit.SECONDS);
  }
  private static RawEditCube imported17()throws Exception {
    StringBuilder text=new StringBuilder("TITLE \"viewer synthetic split\"\nLUT_3D_SIZE 17\n");
    for(int b=0;b<17;b++)for(int g=0;g<17;g++)for(int r=0;r<17;r++)text.append(String.format(Locale.ROOT,"%.7f %.7f %.7f\n",1-r/16f,g/16f,b/16f));
    return RawEditCube.read(new ByteArrayInputStream(text.toString().getBytes(StandardCharsets.UTF_8)));
  }
  private static Bitmap fixture(int w,int h){Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);Paint p=new Paint(3);p.setShader(new LinearGradient(0,0,w,h,0xffe62c31,0xff2479d1,Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,p);p.setShader(null);p.setColor(0xff29cc70);c.drawCircle(w*.68f,h*.4f,Math.min(w,h)*.2f,p);return b;}
  private static Bitmap decodeShownOriginal(File f)throws Exception{return BitmapFactory.decodeFile(f.getAbsolutePath());}
  private static PhotoLookController makeController(Activity a,PhotoRepository.Photo p,ZoomImageView v){try{return new PhotoLookController(a,p,v,()->{});}catch(IOException e){throw new RuntimeException(e);}}
  private static boolean differs(Bitmap a,Bitmap b){if(a==null||b==null||a.getWidth()!=b.getWidth()||a.getHeight()!=b.getHeight())return false;int differences=0;for(int y=2;y<a.getHeight()-2;y+=3)for(int x=2;x<a.getWidth()-2;x+=3)if(a.getPixel(x,y)!=b.getPixel(x,y)&&++differences>=20)return true;return false;}

  static String run(Instrumentation t,int width,float font)throws Exception {
    rawEditActivityLaunches=0;
    Intent launch=new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    MainActivity a=(MainActivity)t.startActivitySync(launch);File source=new File(a.getCacheDir(),"viewer-look-"+UUID.randomUUID()+".jpg");
    LookPresetStore store=new LookPresetStore(new File(a.getFilesDir(),"look-library"));PhotoRepository.Photo photo=null;String key=null;LookPresetStore.Entry preset=null;PhotoLookController[] controller={null};ZoomImageView[] image={null};byte[][] committedBytes={null};
    try {
      Bitmap generated=fixture(480,320);try(FileOutputStream out=new FileOutputStream(source)){require(generated.compress(Bitmap.CompressFormat.JPEG,95,out),"synthetic JPEG written");}finally{generated.recycle();}
      photo=new PhotoRepository.Photo(Uri.fromFile(source),source.getName(),"/viewer-look-synthetic",source.length(),1,1);key=ArchiveStore.key(photo);
      RawEditLook namedLook=RawEditLook.neutral().withLutEnabled(true).withLutStrength(1).withCube(imported17());
      preset=store.save("Synthetic split",namedLook);
      final PhotoRepository.Photo testPhoto=photo;final LookPresetStore.Entry named=preset;
      Bitmap original=decodeShownOriginal(source);
      TagDetailChecks.ui(t,()->{
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root=new FrameLayout(a);ZoomImageView view=new ZoomImageView(a);root.addView(view,new FrameLayout.LayoutParams(-1,-1));a.setContentView(root);
        ViewGroup.LayoutParams window=a.getWindow().getAttributes();window.width=GalleryUi.dp(a,width);a.getWindow().setAttributes((WindowManager.LayoutParams)window);
        image[0]=view;controller[0]=makeController(a,testPhoto,view);controller[0].original(original);
        // Both requests arrive before the source preparation can finish; only the latest candidate may persist.
        controller[0].select(named);controller[0].select(new LookPresetStore.Entry("cnt-like","CNT-like（仮）",RawEditLook.cntLike()));
      });
      t.waitForIdleSync();TagDetailChecks.ui(t,()->require(image[0].getWidth()==GalleryUi.dp(a,width)&&Math.abs(a.getResources().getConfiguration().fontScale-font)<.01f,"instrumentation width and font configuration applied"));waitFor(t,controller[0],"cnt-like",90000);
      LookPresetStore.Entry selection=store.loadSelection(key);require(selection!=null&&selection.id.equals("cnt-like"),"rapid selection commits and persists the latest built-in candidate");
      Bitmap[] renderedHolder={null};TagDetailChecks.ui(t,()->renderedHolder[0]=shown(image[0]));Bitmap rendered=renderedHolder[0];Bitmap decoded=decodeShownOriginal(source);try{require(rendered!=null&&differs(rendered,decoded),"applied Look changes the actual viewer image");}finally{decoded.recycle();}
      Bitmap originalCallback=fixture(480,320);TagDetailChecks.ui(t,()->controller[0].original(originalCallback));t.waitForIdleSync();
      TagDetailChecks.ui(t,()->require(shown(image[0])==rendered&&controller[0].applied().id.equals("cnt-like"),"late original delivery cannot replace committed Look"));
      TagDetailChecks.ui(t,()->controller[0].toggleBefore());TagDetailChecks.ui(t,()->require(shown(image[0])==originalCallback&&controller[0].before(),"Before displays original temporarily"));
      require(store.loadSelection(key).id.equals("cnt-like"),"Before retains persisted selection");TagDetailChecks.ui(t,()->controller[0].toggleBefore());TagDetailChecks.ui(t,()->require(shown(image[0])==rendered,"After restores applied Look"));

      // Apply a named imported-LUT snapshot after preparation; then recreate the controller after deleting its library row.
      TagDetailChecks.ui(t,()->controller[0].select(named));waitFor(t,controller[0],named.id,90000);
      Bitmap[] namedRendered={null};TagDetailChecks.ui(t,()->namedRendered[0]=shown(image[0]));require(differs(namedRendered[0],originalCallback),"named imported 17^3 Look renders to viewer");
      TagDetailChecks.ui(t,()->controller[0].close());store.delete(named.id);
      require(store.list().stream().noneMatch(e->e.id.equals(named.id)),"named preset removed from library");
      TagDetailChecks.ui(t,()->{controller[0]=makeController(a,testPhoto,image[0]);controller[0].original(fixture(480,320));});waitFor(t,controller[0],named.id,90000);
      TagDetailChecks.ui(t,()->require(controller[0].applied().look.equals(namedLook),"deleted preset recipe survives in photo selection snapshot"));

      // Restore must fail closed on corrupt bytes, and retry must load the repaired snapshot without clearing it.
      PathHolder selectionFile=new PathHolder(new File(a.getFilesDir(),"look-library/photo-"+hash(key)+".look"));
      committedBytes[0]=java.nio.file.Files.readAllBytes(selectionFile.file.toPath());
      TagDetailChecks.ui(t,()->controller[0].close());java.nio.file.Files.write(selectionFile.file.toPath(),new byte[]{1,2,3});
      Bitmap[] restoreOriginal={fixture(480,320)};
      TagDetailChecks.ui(t,()->{controller[0]=makeController(a,testPhoto,image[0]);controller[0].original(restoreOriginal[0]);});waitMessage(t,controller[0],30000);
      require(Arrays.equals(java.nio.file.Files.readAllBytes(selectionFile.file.toPath()),new byte[]{1,2,3}),"failed restore preserves the stored bytes for recovery");
      java.nio.file.Files.write(selectionFile.file.toPath(),committedBytes[0]);TagDetailChecks.ui(t,()->controller[0].retry());waitFor(t,controller[0],named.id,90000);
      TagDetailChecks.ui(t,()->require(controller[0].applied().look.equals(namedLook),"Retry restores repaired saved recipe"));
      TagDetailChecks.ui(t,()->controller[0].select(null));long until=android.os.SystemClock.uptimeMillis()+30000;while(android.os.SystemClock.uptimeMillis()<until){final boolean[] done={false};TagDetailChecks.ui(t,()->done[0]=!controller[0].busy()&&controller[0].applied()==null);if(done[0])break;Thread.sleep(100);}
      TagDetailChecks.ui(t,()->require(controller[0].applied()==null&&shown(image[0])==restoreOriginal[0],"clear restores current original pixels"));require(store.loadSelection(key)==null,"clear removes only fixture selection");
      require(rawEditActivityLaunches==0,"viewer Look flow never opened RawEditActivity");
      return "PASS actual MainActivity window width="+width+" font="+font+"; named imported LUT and CNT rendered; rapid selection last-wins; late original callback gated; Before retained durable selection; controller close/recreate restored recipe after library deletion; corrupt restore preserved saved bytes and Retry recovered; clear removed fixture selection; RawEditActivity launches="+rawEditActivityLaunches+". Synthetic private JPEG only; no MediaStore export or other photo selection touched.\n";
    } finally {
      if(controller[0]!=null)TagDetailChecks.ui(t,()->controller[0].close());
      awaitStorage();
      if(key!=null)store.clearSelection(key);
      if(preset!=null)store.delete(preset.id);
      TagDetailChecks.ui(t,()->{a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.finish();});
      source.delete();
    }
  }
  private static final class PathHolder {final File file;PathHolder(File f){file=f;}}
}
