package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.database.Cursor;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inspector.WindowInspector;
import android.widget.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Synthetic originals only. External chooser is intercepted; internal editor runs end to end. */
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
  static void click(Instrumentation t,Fixture f)throws Exception{TagDetailChecks.ui(t,()->f.editor.button.performLongClick());}
  static String label(Instrumentation t,Fixture f)throws Exception{
    String[] result={""};TagDetailChecks.ui(t,()->result[0]=f.editor.button.getText().toString());return result[0];
  }
  private static Button findButton(View root,String text){
    if(root instanceof Button&&text.contentEquals(((Button)root).getText()))return (Button)root;
    if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){
      Button found=findButton(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;
    }
    return null;
  }
  private static Button button(Activity a,String text){return findButton(a.getWindow().getDecorView(),text);}
  private static void awaitUi(Instrumentation t,long timeout,BooleanSupplier condition,String message)throws Exception{
    long until=android.os.SystemClock.uptimeMillis()+timeout;boolean[] done={false};
    while(android.os.SystemClock.uptimeMillis()<until){
      TagDetailChecks.ui(t,()->done[0]=condition.getAsBoolean());if(done[0])return;Thread.sleep(25);
    }
    throw new AssertionError(message);
  }
  private static AccessibilityNodeInfo node(AccessibilityNodeInfo root,String text,boolean clickable){
    if(root==null)return null;
    if(text.contentEquals(root.getText()==null?"":root.getText())&&(!clickable||root.isClickable()))return root;
    for(int i=0;i<root.getChildCount();i++){AccessibilityNodeInfo found=node(root.getChild(i),text,clickable);if(found!=null)return found;}
    return null;
  }
  private static void dialog(Instrumentation t,String title,String action)throws Exception{
    long until=android.os.SystemClock.uptimeMillis()+5000;
    while(android.os.SystemClock.uptimeMillis()<until){
      AccessibilityNodeInfo root=t.getUiAutomation().getRootInActiveWindow();
      if(node(root,title,false)!=null){
        AccessibilityNodeInfo choice=node(root,action,true);
        if(choice!=null&&choice.performAction(AccessibilityNodeInfo.ACTION_CLICK)){t.waitForIdleSync();return;}
      }
      Thread.sleep(25);
    }
    throw new AssertionError("internal editor dialog action timed out");
  }
  private static boolean hasText(View root,String text){
    if(root instanceof TextView&&text.contentEquals(((TextView)root).getText()))return true;
    if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)if(hasText(((ViewGroup)root).getChildAt(i),text))return true;
    return false;
  }
  private static Button dialogButton(String title,String action){
    for(View root:WindowInspector.getGlobalWindowViews())if(root.isShown()&&root.hasWindowFocus()&&hasText(root,title)){
      Button found=findButton(root,action);if(found!=null&&found.isEnabled())return found;
    }
    return null;
  }
  private static Set<Long> outputs(ContentResolver resolver,String owner){
    Set<Long> result=new HashSet<>();
    String where=MediaStore.Images.Media.OWNER_PACKAGE_NAME+"=? AND "+MediaStore.Images.Media.RELATIVE_PATH+" IN (?,?) AND "+MediaStore.Images.Media.MIME_TYPE+"=? AND "+MediaStore.Images.Media.DISPLAY_NAME+" LIKE ?";
    try(Cursor rows=resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,new String[]{MediaStore.Images.Media._ID},where,
        new String[]{owner,"Pictures/LoupeDevelop","Pictures/LoupeDevelop/","image/jpeg","Loupe-%.jpg"},null)){
      TagDetailChecks.require(rows!=null,"owned output query available");while(rows.moveToNext())result.add(rows.getLong(0));
    }
    return result;
  }
  private static void cancelInternalSave(Instrumentation t,MainActivity main,ExecutorService worker,File source,byte[] before,List<File> copies)throws Exception{
    ContentResolver resolver=main.getContentResolver();String owner=main.getPackageName();Set<Long> baseline=outputs(resolver,owner);
    Instrumentation.ActivityMonitor live=t.addMonitor(RawEditActivity.class.getName(),null,false);
    RawEditActivity current=null;Uri ownPending=null;boolean ambiguous=false;Throwable primary=null;
    try{
      PhotoRepository.Photo photo=new PhotoRepository.Photo(Uri.fromFile(source),"edit-integration-cancel.jpg","/synthetic",source.length(),0,0);
      Fixture viewer=fixture(t,main,photo,worker,new SharePreparation(main,new PhotoRepository(main)));
      TagDetailChecks.ui(t,()->viewer.editor.button.performClick());Activity opened=live.waitForActivityWithTimeout(10000);
      TagDetailChecks.require(opened instanceof RawEditActivity,"second real internal Activity starts");current=(RawEditActivity)opened;RawEditActivity editor=current;
      Uri input=editor.getIntent().getData();TagDetailChecks.require(input!=null&&"jp.nano.rawgallery.share".equals(input.getAuthority()),"cancel test has its own prepared copy");
      File copy=new File(main.getCacheDir(),"shares/"+input.getLastPathSegment());copies.add(copy);
      awaitUi(t,15000,()->button(editor,"保存")!=null&&button(editor,"保存").isEnabled(),"cancel test preview ready");
      TagDetailChecks.ui(t,()->button(editor,"保存").performClick());
      awaitUi(t,5000,()->dialogButton("JPEGで保存","保存")!=null,"cancel test save confirmation visible");
      TagDetailChecks.ui(t,()->{
        dialogButton("JPEGで保存","保存").performClick();editor.onBackPressed();
        TagDetailChecks.require(editor.isFinishing(),"Back immediately after confirming save leaves Activity");
      });
      awaitUi(t,10000,editor::isDestroyed,"cancelled saving Activity destroyed");
      // No hook forces the background worker beyond insert(). This is immediate
      // save-start cancellation, not a claim of cancellation after pending creation.
      long until=android.os.SystemClock.uptimeMillis()+3000;Set<Long> remaining=Collections.emptySet();
      while(android.os.SystemClock.uptimeMillis()<until){
        remaining=outputs(resolver,owner);remaining.removeAll(baseline);
        if(remaining.size()>1){ambiguous=true;throw new AssertionError("ambiguous media after immediate save cancellation");}
        if(remaining.size()==1)ownPending=ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,remaining.iterator().next());
        Thread.sleep(50);
      }
      remaining=outputs(resolver,owner);remaining.removeAll(baseline);
      if(remaining.size()>1)ambiguous=true;
      TagDetailChecks.require(remaining.isEmpty(),"immediate save cancellation leaves no new owned pending or published output in observation window");
      TagDetailChecks.require(Arrays.equals(before,Files.readAllBytes(source.toPath()))&&Arrays.equals(before,Files.readAllBytes(copy.toPath())),"save cancellation preserves original and input copy bytes");
    }catch(Exception|Error failure){primary=failure;throw failure;}
    finally{
      Throwable cleanup=null;
      try{
        if(current==null&&live.getLastActivity() instanceof RawEditActivity)current=(RawEditActivity)live.getLastActivity();
        if(current!=null){RawEditActivity closing=current;TagDetailChecks.ui(t,closing::finish);}
      }catch(Exception|Error failure){cleanup=failure;}finally{t.removeMonitor(live);}
      try{if(ownPending!=null&&!ambiguous)resolver.delete(ownPending,null,null);}
      catch(Exception|Error failure){if(cleanup==null)cleanup=failure;else cleanup.addSuppressed(failure);}
      if(cleanup!=null){if(primary!=null)primary.addSuppressed(cleanup);else if(cleanup instanceof Error)throw (Error)cleanup;else throw (Exception)cleanup;}
    }
  }
  private static void internalEditor(Instrumentation t,MainActivity main,ExecutorService worker,List<File> copies)throws Exception{
    File source=File.createTempFile("edit-integration-",".jpg",main.getCacheDir());
    ContentResolver resolver=main.getContentResolver();String owner=main.getPackageName();
    Instrumentation.ActivityMonitor live=t.addMonitor(RawEditActivity.class.getName(),null,false);
    AtomicReference<RawEditActivity> active=new AtomicReference<>();
    Set<Long> baseline=null;Uri output=null;boolean saveStarted=false,ambiguous=false;Throwable primary=null;
    try{
      Bitmap fixture=Bitmap.createBitmap(192,128,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(fixture);Paint paint=new Paint();
      int[] colors={Color.rgb(210,75,45),Color.rgb(85,180,110),Color.rgb(128,128,128)};
      for(int i=0;i<3;i++){paint.setColor(colors[i]);canvas.drawRect(i*64,0,(i+1)*64,128,paint);}
      try(OutputStream stream=new FileOutputStream(source)){TagDetailChecks.require(fixture.compress(Bitmap.CompressFormat.JPEG,95,stream),"synthetic integration JPEG encoded");}finally{fixture.recycle();}
      byte[] before=Files.readAllBytes(source.toPath());
      PhotoRepository.Photo photo=new PhotoRepository.Photo(Uri.fromFile(source),"edit-integration.jpg","/synthetic",source.length(),0,0);
      Fixture viewer=fixture(t,main,photo,worker,new SharePreparation(main,new PhotoRepository(main)));
      TagDetailChecks.ui(t,()->viewer.editor.button.performClick());
      Activity launched=live.waitForActivityWithTimeout(10000);
      TagDetailChecks.require(launched instanceof RawEditActivity,"normal tap starts real internal Activity");
      RawEditActivity editor=(RawEditActivity)launched;active.set(editor);
      Uri input=editor.getIntent().getData();
      TagDetailChecks.require(input!=null&&"jp.nano.rawgallery.share".equals(input.getAuthority()),"real editor receives prepared content copy");
      File copy=new File(main.getCacheDir(),"shares/"+input.getLastPathSegment());copies.add(copy);
      TagDetailChecks.require(Arrays.equals(before,Files.readAllBytes(copy.toPath())),"real editor's copy preserves input bytes");
      awaitUi(t,15000,()->button(editor,"保存")!=null&&button(editor,"保存").isEnabled(),"internal JPEG preview ready");
      TagDetailChecks.ui(t,()->{button(editor,"カラー").performClick();button(editor,"モノクロ").performClick();
        TagDetailChecks.require(button(editor,"モノクロ").isSelected(),"Mono selected through UI");});
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);dialog(t,"変更を破棄しますか？","編集を続ける");
      TagDetailChecks.ui(t,()->TagDetailChecks.require(!editor.isFinishing()&&button(editor,"モノクロ").isSelected(),"Back cancel retains unsaved Mono"));
      Instrumentation.ActivityMonitor recreated=t.addMonitor(RawEditActivity.class.getName(),null,false);
      try{
        TagDetailChecks.ui(t,editor::recreate);
        Activity restored=recreated.waitForActivityWithTimeout(10000);
        TagDetailChecks.require(restored instanceof RawEditActivity&&restored!=editor,"internal Activity recreated");
        active.set((RawEditActivity)restored);
      }finally{t.removeMonitor(recreated);}
      RawEditActivity current=active.get();
      awaitUi(t,15000,()->button(current,"保存")!=null&&button(current,"保存").isEnabled(),"restored internal JPEG preview ready");
      TagDetailChecks.ui(t,()->TagDetailChecks.require(button(current,"カラー").isSelected()&&button(current,"モノクロ")!=null&&button(current,"モノクロ").isSelected(),"recreation restores color tab and Mono"));
      baseline=outputs(resolver,owner);
      TagDetailChecks.ui(t,()->button(current,"保存").performClick());saveStarted=true;
      dialog(t,"JPEGで保存","保存");
      long until=android.os.SystemClock.uptimeMillis()+15000;boolean published=false;
      while(android.os.SystemClock.uptimeMillis()<until){
        Set<Long> made=outputs(resolver,owner);made.removeAll(baseline);
        if(made.size()>1){ambiguous=true;throw new AssertionError("multiple new outputs; preserve ambiguous media");}
        if(made.size()==1){
          output=ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,made.iterator().next());
          try(Cursor row=resolver.query(output,new String[]{MediaStore.Images.Media.IS_PENDING,MediaStore.Images.Media.MIME_TYPE},null,null,null)){
            if(row!=null&&row.moveToFirst()&&row.getInt(0)==0){TagDetailChecks.require("image/jpeg".equals(row.getString(1)),"published output is JPEG");published=true;break;}
          }
        }
        Thread.sleep(25);
      }
      TagDetailChecks.require(published&&output!=null,"single JPEG output published within deadline");
      awaitUi(t,5000,()->button(current,"✓")!=null||button(current,"保存")!=null&&button(current,"保存").isEnabled(),"save completes in UI");
      Bitmap written;try(InputStream stream=resolver.openInputStream(output)){written=BitmapFactory.decodeStream(stream);}
      Bitmap original=BitmapFactory.decodeByteArray(before,0,before.length);
      try{
        TagDetailChecks.require(written!=null&&written.getWidth()==192&&written.getHeight()==128,"MediaStore JPEG preserves fixture dimensions");
        for(int x:new int[]{32,96,160}){
          int start=original.getPixel(x,64),pixel=written.getPixel(x,64);
          float luminance=(.2126f*Color.red(start)+.7152f*Color.green(start)+.0722f*Color.blue(start))/255f;
          float light=Math.min(1,luminance+.02f),chroma=(1-Math.abs(2*light-1))*.11f;
          int[] expected={Math.round((light-chroma/6)*255),Math.round((light-chroma/2)*255),Math.round((light+chroma/2)*255)};
          TagDetailChecks.require(Math.abs(Color.red(pixel)-expected[0])<=6&&Math.abs(Color.green(pixel)-expected[1])<=6&&Math.abs(Color.blue(pixel)-expected[2])<=6,"saved Mono follows hue260/saturation11/lightness+2 within JPEG tolerance");
          TagDetailChecks.require(Color.blue(pixel)>Color.red(pixel)&&Color.red(pixel)>Color.green(pixel),"saved Mono tint channel order");
        }
      }finally{if(written!=null)written.recycle();if(original!=null)original.recycle();}
      TagDetailChecks.require(Arrays.equals(before,Files.readAllBytes(source.toPath()))&&Arrays.equals(before,Files.readAllBytes(copy.toPath())),"full editor/save leaves synthetic original and input copy unchanged");
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
      awaitUi(t,5000,current::isFinishing,"Back after saved state exits without discard dialog");
      cancelInternalSave(t,main,worker,source,before,copies);
    }catch(Exception|Error failure){primary=failure;throw failure;}
    finally{
      Throwable cleanup=null;
      try{
        RawEditActivity current=active.get();Activity last=live.getLastActivity();
        if(last instanceof RawEditActivity&&!last.isDestroyed())current=(RawEditActivity)last;
        if(current!=null){RawEditActivity closing=current;TagDetailChecks.ui(t,closing::finish);}
      }catch(Exception|Error failure){cleanup=failure;}
      finally{t.removeMonitor(live);}
      try{
        // Only a single unambiguous row created after this test's save can be removed.
        if(output==null&&saveStarted&&baseline!=null&&!ambiguous){Set<Long> made=outputs(resolver,owner);made.removeAll(baseline);if(made.size()==1)output=ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,made.iterator().next());}
        if(output!=null&&!ambiguous)resolver.delete(output,null,null);
      }catch(Exception|Error failure){if(cleanup==null)cleanup=failure;else cleanup.addSuppressed(failure);}
      finally{if(source.exists()&&!source.delete()){IOException failure=new IOException("synthetic integration fixture cleanup failed");if(cleanup==null)cleanup=failure;else cleanup.addSuppressed(failure);}}
      if(cleanup!=null){if(primary!=null)primary.addSuppressed(cleanup);else if(cleanup instanceof Error)throw (Error)cleanup;else throw (Exception)cleanup;}
    }
  }
  static String run(Instrumentation t)throws Exception{
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    ExecutorService worker=Executors.newSingleThreadExecutor();
    AtomicReference<Intent> chosen=new AtomicReference<>(),nativeChosen=new AtomicReference<>();AtomicInteger launches=new AtomicInteger(),nativeLaunches=new AtomicInteger();
    AtomicBoolean allowNative=new AtomicBoolean();
    Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){
      public Instrumentation.ActivityResult onStartActivity(Intent intent){
        if(intent.getComponent()!=null&&RawEditActivity.class.getName().equals(intent.getComponent().getClassName())){nativeChosen.set(intent);nativeLaunches.incrementAndGet();return allowNative.get()?null:new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}
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
      TagDetailChecks.ui(t,()->real.editor.button.performClick());await(()->nativeLaunches.get()==1);
      Uri nativeUri=nativeChosen.get().getData();TagDetailChecks.require("jp.nano.rawgallery.share".equals(nativeUri.getAuthority()),"normal tap opens internal editor with prepared copy");
      File nativeCopy=new File(a.getCacheDir(),"shares/"+nativeUri.getLastPathSegment());copies.add(nativeCopy);TagDetailChecks.require(Arrays.equals(before,Files.readAllBytes(nativeCopy.toPath()))&&Arrays.equals(before,Files.readAllBytes(original.toPath())),"internal editor gets identical separate copy");
      int initial=launches.get();
      for(int scenario:new int[]{0,1,2}){
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger writes=new AtomicInteger();AtomicReference<File> made=new AtomicReference<>();
        SharePreparation slow=new SharePreparation(new File(a.getCacheDir(),"shares"),(p,rawFormat,destination)->{
          writes.incrementAndGet();made.set(destination);started.countDown();
          boolean waiting=true;while(waiting)try{release.await();waiting=false;}catch(InterruptedException ignored){}
          Files.write(destination.toPath(),before);
        });
        Fixture f=fixture(t,a,photo,worker,slow);click(t,f);click(t,f);
        TagDetailChecks.require(started.await(3,TimeUnit.SECONDS),"copy started");
        try{
          if(scenario==1)TagDetailChecks.ui(t,()->a.setContentView(new FrameLayout(a)));
          else if(scenario==2)TagDetailChecks.ui(t,()->{f.viewer.onWindowFocusChanged(false);f.viewer.onWindowFocusChanged(true);});
          else{
            long until=android.os.SystemClock.uptimeMillis()+3000;
            while(!"中止".equals(label(t,f))){if(android.os.SystemClock.uptimeMillis()>until)throw new AssertionError("cancel label");Thread.sleep(20);}
            TagDetailChecks.ui(t,()->f.editor.button.performClick());
          }
        }finally{release.countDown();}
        worker.submit(()->{}).get(5,TimeUnit.SECONDS);t.waitForIdleSync();
        TagDetailChecks.require(writes.get()==1&&launches.get()==initial&&!made.get().exists(),"cancel/detach/focus loss-return prevents late launch and discards copy");
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
      allowNative.set(true);internalEditor(t,a,worker,copies);
      return "PASS real normal-tap internal Activity load, Mono dirty-Back cancel/recreation, confirmed MediaStore JPEG dimensions/tint/original unchanged, immediate save-start Back cancellation/no remaining owned output (pending-stage cancellation not forced), scoped cleanup; long-press external chooser; original-format copy/unchanged original, read-only data+ClipData grant, RAW MIME, intercepted chooser, duplicate prevention, inline cancel, detach/focus loss-return/late completion cleanup, prepare failure/retry. No external editor launched; external editor/save compatibility is separate.\n";
    }finally{
      t.removeMonitor(monitor);worker.shutdownNow();
      TagDetailChecks.ui(t,()->a.finish());original.delete();for(File copy:copies)copy.delete();
    }
  }
}
