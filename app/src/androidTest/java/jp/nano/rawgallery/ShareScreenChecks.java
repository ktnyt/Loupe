package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic share windows; every preparation writer throws, so no recipient can open. */
final class ShareScreenChecks {
  static void export(MainActivity a,List<PhotoRepository.Photo> photos,boolean raw){
    try{java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("export",List.class,boolean.class,ShareTargets.Target.class);m.setAccessible(true);m.invoke(a,photos,raw,null);}catch(Exception e){throw new RuntimeException(e);}
  }
  static void shot(Instrumentation t,Dialog dialog,File dir,String name)throws Exception{
    ConsistencyLayoutChecks.captureHardware(t,dialog.getWindow(),dialog.getWindow().getDecorView(),dir,name);
  }
  static View modal(Instrumentation t,MainActivity a)throws Exception{
    View[] found={null};for(int attempt=0;attempt<100&&found[0]==null;attempt++){TagDetailChecks.ui(t,()->{for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews())if(root!=a.getWindow().getDecorView()&&root.isShown()&&root.hasWindowFocus())found[0]=root;});if(found[0]==null)Thread.sleep(50);}
    TagDetailChecks.require(found[0]!=null,"focused synthetic dialog");return found[0];
  }
  static ShareUi.StateIcon marker(View root){if(root instanceof ShareUi.StateIcon)return (ShareUi.StateIcon)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){ShareUi.StateIcon found=marker(((ViewGroup)root).getChildAt(i));if(found!=null)return found;}return null;}
  static void visibleMarker(Instrumentation t,Dialog dialog)throws Exception{boolean[] shown={false};for(int i=0;i<100&&!shown[0];i++){TagDetailChecks.ui(t,()->{ShareUi.StateIcon icon=marker(dialog.getWindow().getDecorView());shown[0]=icon!=null&&icon.isShown();});if(!shown[0])Thread.sleep(50);}TagDetailChecks.require(shown[0],"share state marker becomes visible");}
  static ShareUi.Confirmation confirmationDialog(Object dialog){TagDetailChecks.require(dialog instanceof ShareUi.Confirmation,"expected full-width Confirmation, not legacy dialog");return (ShareUi.Confirmation)dialog;}
  static void confirmation(Instrumentation t,MainActivity a,List<PhotoRepository.Photo> photos,Set<String> selection,File dir,String prefix)throws Exception{
    boolean bestOnly=(boolean)RankingResultChecks.get(a,"bestOnly");
    TagDetailChecks.ui(t,()->{boolean rejected=false;try{confirmationDialog(new AlertDialog.Builder(a).create());}catch(AssertionError expected){rejected=true;}TagDetailChecks.require(rejected,"legacy AlertDialog rejected by current confirmation test");});
    PhotoRanking unfinished=new PhotoRanking(List.of(ArchiveStore.key(photos.get(0)),ArchiveStore.key(photos.get(1))),"");
    try{
      TagDetailChecks.ui(t,()->{RankingResultChecks.set(a,"bestOnly",true);List<List<Integer>> groups=new ArrayList<>();groups.add(List.of(0,1));for(int i=2;i<photos.size();i++)groups.add(List.of(i));RankingResultChecks.set(a,"bundles",groups);Map<List<Integer>,PhotoRanking> cache=(Map<List<Integer>,PhotoRanking>)RankingResultChecks.get(a,"rankCache");cache.clear();cache.put(List.of(0,1),unfinished);});
      for(boolean partial:new boolean[]{false,true}){
        Set<String> expected=new HashSet<>(partial?selection:Set.of(photos.get(0).uri.toString(),photos.get(1).uri.toString()));
        TagDetailChecks.ui(t,()->{Set<String> chosen=(Set<String>)RankingResultChecks.get(a,"selectedPhotos");chosen.clear();chosen.addAll(expected);RankingResultChecks.call(a,"shareSelection");});
        View root=modal(t,a);ConsistencyLayoutChecks.frame(t,root);
        ShareUi.Confirmation active=confirmationDialog(RankingResultChecks.get(a,"shareDialog"));
        TagDetailChecks.require(active.isShowing(),"confirmation shown");shot(t,active,dir,prefix+"-confirm-"+(partial?"partial":"only"));
        TagDetailChecks.ui(t,()->{TagDetailChecks.inspect(root);TagDetailChecks.require(root==active.getWindow().getDecorView() && root.getWidth()==GalleryUi.dp(a,a.getResources().getConfiguration().screenWidthDp),"confirmation owns focused full-width window");});
        TagDetailChecks.ui(t,()->{TagDetailChecks.require((TagDetailChecks.button(root,"順位が未決定の連写を除いて共有")!=null)==partial,"partial share visibility");TagDetailChecks.button(root,"キャンセル").performClick();TagDetailChecks.require(((Set<?>)RankingResultChecks.get(a,"selectedPhotos")).equals(expected),"confirmation cancel retains selection");});t.waitForIdleSync();
        TagDetailChecks.ui(t,()->RankingResultChecks.call(a,"shareSelection"));View backRoot=modal(t,a);t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);t.waitForIdleSync();
        TagDetailChecks.ui(t,()->TagDetailChecks.require(!confirmationDialog(RankingResultChecks.get(a,"shareDialog")).isShowing()&&((Set<?>)RankingResultChecks.get(a,"selectedPhotos")).equals(expected),"confirmation Back retains selection"));
        if(partial){
          TagDetailChecks.ui(t,()->RankingResultChecks.call(a,"shareSelection"));View again=modal(t,a);
          TagDetailChecks.ui(t,()->{TagDetailChecks.button(again,"順位が未決定の連写を除いて共有").performClick();Object menu=RankingResultChecks.get(a,"shareDialog");TagDetailChecks.require(menu instanceof ShareUi.Menu && ((ShareUi.Menu)menu).previews.size()==photos.size()-2,"partial share opens only resolved photos");TagDetailChecks.require(((Set<?>)RankingResultChecks.get(a,"selectedPhotos")).equals(expected),"partial share retains selection");((Dialog)menu).dismiss();});t.waitForIdleSync();
        }
      }
    }finally{TagDetailChecks.ui(t,()->{RankingResultChecks.set(a,"bestOnly",bestOnly);Set<String> chosen=(Set<String>)RankingResultChecks.get(a,"selectedPhotos");chosen.clear();chosen.addAll(selection);});}
  }
  static String geometry(View view,String path){StringBuilder value=new StringBuilder();float d=view.getResources().getDisplayMetrics().density;
    value.append(path).append(' ').append(view.getClass().getSimpleName()).append(" x=").append(view.getLeft()/d).append(" y=").append(view.getTop()/d).append(" w=").append(view.getWidth()/d).append(" h=").append(view.getHeight()/d);
    if(view instanceof TextView){TextView text=(TextView)view;value.append(" textDp=").append(text.getTextSize()/d).append(" lineDp=").append(text.getLineHeight()/d).append(" lines=").append(text.getLineCount()).append(" layoutDp=").append(text.getLayout()==null?-1:text.getLayout().getHeight()/d).append(" pad=").append(text.getPaddingTop()/d).append(',').append(text.getPaddingBottom()/d).append(" weight=").append(text.getTypeface().getWeight()).append(" scaleX=").append(text.getTextScaleX());}
    if(view instanceof LinearLayout)value.append(" baselineAligned=").append(((LinearLayout)view).isBaselineAligned());value.append('\n');
    if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)value.append(geometry(((ViewGroup)view).getChildAt(i),path+"/"+i));return value.toString();}
  static void worker(Instrumentation t,MainActivity a)throws Exception{((ExecutorService)RankingResultChecks.get(a,"actions")).submit(()->{}).get(10,TimeUnit.SECONDS);t.waitForIdleSync();}
  static String run(Instrumentation t,int width)throws Exception{
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    File fixtures=new File(a.getCacheDir(),"share-ui-fixture-"+UUID.randomUUID());fixtures.mkdirs();File dir=new File(a.getCacheDir(),"consistency-checks");dir.mkdirs();
    List<File> files=new ArrayList<>();List<PhotoRepository.Photo> photos=new ArrayList<>();List<Dialog> dialogs=new ArrayList<>();CountDownLatch release=new CountDownLatch(1);
    StringBuilder metrics=new StringBuilder();
    String prefix="share-"+width+"-"+a.getResources().getConfiguration().fontScale;
    try{
      GalleryScreenChecks.finishInitialIndex(t,a);
      Bitmap bitmap=Bitmap.createBitmap(90,60,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff718774);
      for(int i=0;i<7;i++){File file=new File(fixtures,i+".jpg");files.add(file);try(FileOutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);}photos.add(new PhotoRepository.Photo(Uri.fromFile(file),"合成"+i+".jpg","",file.length(),0,0));}
      Set<String> selection=new HashSet<>();for(PhotoRepository.Photo photo:photos)selection.add(photo.uri.toString());
      TagDetailChecks.ui(t,()->{a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);((ViewGroup)RankingResultChecks.get(a,"root")).removeAllViews();((View)RankingResultChecks.get(a,"root")).setBackgroundColor(Color.BLACK);RankingResultChecks.set(a,"photos",photos);Set<String> selected=(Set<String>)RankingResultChecks.get(a,"selectedPhotos");selected.clear();selected.addAll(selection);});
      confirmation(t,a,photos,selection,dir,prefix);
      AtomicInteger chosen=new AtomicInteger();boolean[] raw={false};ShareTargets.Target[] target={null};
      List<ShareTargets.Target> targets=List.of(new ShareTargets.Target("fixture/.Family","家族アルバム",false),new ShareTargets.Target("fixture/.Computer","PCへ転送",true));
      ShareUi.Menu[] menu={null};
      TagDetailChecks.ui(t,()->{menu[0]=new ShareUi.Menu(a,photos,targets,2,Set.of(photos.get(0).uri.toString()),(value,destination)->{chosen.incrementAndGet();raw[0]=value;target[0]=destination;});dialogs.add(menu[0]);for(ImageView image:menu[0].previews)image.setImageBitmap(bitmap);menu[0].show();});
      shot(t,menu[0],dir,prefix+"-menu-jpeg");
      TagDetailChecks.ui(t,()->{TagDetailChecks.require(menu[0].getWindow().getDecorView().getWidth()==GalleryUi.dp(a,width),"share sheet follows available width");TagDetailChecks.inspect(menu[0].content);metrics.append(geometry(menu[0].content,"menu"));});
      TagDetailChecks.ui(t,()->TagDetailChecks.require(!a.hasWindowFocus()&&menu[0].getWindow().getDecorView().hasWindowFocus(),"dialog owns focus while activity remains visible"));
      ConsistencyLayoutChecks.captureHardware(t,a,(View)RankingResultChecks.get(a,"root"),dir,prefix+"-dialog-underlay");
      TagDetailChecks.ui(t,()->{menu[0].original.performClick();TagDetailChecks.require(chosen.get()==0&&menu[0].raw,"format alone does not prepare");});shot(t,menu[0],dir,prefix+"-menu-original");TagDetailChecks.ui(t,()->TagDetailChecks.inspect(menu[0].content));
      TagDetailChecks.ui(t,()->{menu[0].jpeg.performClick();ViewGroup destinations=(ViewGroup)menu[0].content.getChildAt(menu[0].content.getChildCount()-2);destinations.getChildAt(1).performClick();TagDetailChecks.require(chosen.get()==1&&raw[0]&&target[0]==targets.get(1),"saved destination uses its saved format");});
      TagDetailChecks.ui(t,()->{menu[0]=new ShareUi.Menu(a,photos,targets,0,Set.of(),(value,destination)->{chosen.incrementAndGet();raw[0]=value;target[0]=destination;});dialogs.add(menu[0]);menu[0].show();menu[0].original.performClick();ViewGroup destinations=(ViewGroup)menu[0].content.getChildAt(menu[0].content.getChildCount()-2);destinations.getChildAt(2).performClick();TagDetailChecks.require(chosen.get()==2&&raw[0]&&target[0]==null,"chooser uses current format");});
      CountDownLatch entered=new CountDownLatch(1);AtomicInteger copies=new AtomicInteger();
      SharePreparation blocked=new SharePreparation(new File(fixtures,"cancel"),(photo,value,destination)->{copies.incrementAndGet();entered.countDown();release.await();throw new IOException("synthetic preparation never succeeds");});
      TagDetailChecks.ui(t,()->{RankingResultChecks.set(a,"sharePreparation",blocked);export(a,List.copyOf(photos),true);});TagDetailChecks.require(entered.await(10,TimeUnit.SECONDS),"preparation started");t.waitForIdleSync();
      Dialog progress=(Dialog)RankingResultChecks.get(a,"shareProgress");dialogs.add(progress);visibleMarker(t,progress);shot(t,progress,dir,prefix+"-preparing");TagDetailChecks.ui(t,()->metrics.append(geometry(((ShareUi.Sheet)progress).content,"preparing")));
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);t.waitForIdleSync();
      TagDetailChecks.ui(t,()->{TagDetailChecks.require(progress.isShowing(),"Back does not cancel preparation");TagDetailChecks.button(progress.getWindow().getDecorView(),"中止").performClick();});worker(t,a);
      TagDetailChecks.ui(t,()->TagDetailChecks.require(!progress.isShowing()&&copies.get()==1&&((Set<?>)RankingResultChecks.get(a,"selectedPhotos")).equals(selection),"cancel retains selection and closes preparation"));
      AtomicInteger attempts=new AtomicInteger();
      SharePreparation failed=new SharePreparation(new File(fixtures,"failed"),(photo,value,destination)->{attempts.incrementAndGet();throw new IOException("synthetic failure");});
      TagDetailChecks.ui(t,()->{RankingResultChecks.set(a,"sharePreparation",failed);export(a,List.copyOf(photos),false);});worker(t,a);
      View failure=modal(t,a);Dialog failureDialog=(Dialog)RankingResultChecks.get(a,"shareDialog");dialogs.add(failureDialog);shot(t,failureDialog,dir,prefix+"-failed");TagDetailChecks.ui(t,()->TagDetailChecks.inspect(failure));
      TagDetailChecks.ui(t,()->TagDetailChecks.button(failure,"再試行").performClick());worker(t,a);
      View retry=modal(t,a);TagDetailChecks.ui(t,()->{TagDetailChecks.require(attempts.get()==2&&((Set<?>)RankingResultChecks.get(a,"selectedPhotos")).equals(selection),"retry repeats preparation without changing selection");TagDetailChecks.button(retry,"選択に戻る").performClick();});
      bitmap.recycle();
      return "PASS strict Confirmation type (legacy AlertDialog rejected), conditional action visibility, cancel/Back selection retention, own Window PixelCopy and partial-share Menu transition. Synthetic share Menu formats and destination callbacks; actual export cancellation, Back guard, preparation failure and retry with writers that always throw. Selection retained. No send or user preference save invoked. Menu/preparing/failure use PixelCopy of their own windows; available width and text bounds checked. Existing startup index may update browsing DB.\n"+metrics;
    }finally{
      release.countDown();TagDetailChecks.ui(t,()->{for(Dialog dialog:dialogs)if(dialog.isShowing())dialog.dismiss();a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.finish();});
      for(File file:files)file.delete();for(String child:new String[]{"cancel","failed"}){File sub=new File(fixtures,child);File[] contents=sub.listFiles();if(contents!=null)for(File file:contents)file.delete();sub.delete();}fixtures.delete();
    }
  }
}
