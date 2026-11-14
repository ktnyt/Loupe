package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.view.*;
import android.widget.Button;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static jp.nano.rawgallery.TagDetailChecks.*;
import static jp.nano.rawgallery.RankingResultChecks.*;

/** Real OS insets and full-display captures; never dispatches synthetic insets or pads test roots. */
final class WindowInsetsChecks {
  private static View root(MainActivity a){return (View)get(a,"root");}
  private static Rect screen(View v){int[] at=new int[2];v.getLocationOnScreen(at);return new Rect(at[0],at[1],at[0]+v.getWidth(),at[1]+v.getHeight());}
  private static Insets bars(MainActivity a){
    WindowInsets actual=root(a).getRootWindowInsets();
    require(actual!=null,"actual OS rootWindowInsets available");
    Insets result=actual.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
    require(result.top>0,"real status bar inset must be nonzero: "+result);
    return result;
  }
  private static Rect safe(MainActivity a){Rect bounds=new Rect(a.getWindowManager().getCurrentWindowMetrics().getBounds());Insets i=bars(a);bounds.inset(i.left,i.top,i.right,i.bottom);return bounds;}
  private static void settle(Instrumentation t,MainActivity a)throws Exception{
    GalleryScreenChecks.settle(t,a);ConsistencyLayoutChecks.frame(t,root(a));
    // Observe completed native layout rather than asserting immediately after a setter.
    long until=SystemClock.uptimeMillis()+5000;boolean[] ready={false};
    do{ui(t,()->ready[0]=root(a).isAttachedToWindow()&&!root(a).isLayoutRequested()&&root(a).getHeight()>0);if(ready[0])break;Thread.sleep(30);}while(SystemClock.uptimeMillis()<until);
    require(ready[0],"native root layout settled");ConsistencyLayoutChecks.frame(t,root(a));
  }
  private static void capture(Instrumentation t,MainActivity a,File dir,String state)throws Exception{
    t.waitForIdleSync();ConsistencyLayoutChecks.frame(t,root(a));
    android.content.res.Configuration config=a.getResources().getConfiguration();
    String suffix="-"+config.screenWidthDp+"-"+config.fontScale;
    String name=state.endsWith(suffix)?state:state+suffix;
    Bitmap bitmap=t.getUiAutomation().takeScreenshot();require(bitmap!=null,"composed full-display screenshot");
    try(FileOutputStream out=new FileOutputStream(new File(dir,"system-insets-"+name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
  }
  private static void inside(MainActivity a,View view,String label){
    require(view!=null&&view.isShown(),label+" is shown");Rect box=screen(view),safe=safe(a);
    require(safe.contains(box),label+" crosses physical safe bounds: target="+box+" safe="+safe);
    Rect visible=new Rect();require(view.getLocalVisibleRect(visible),label+" visible");
    visible.offset(-view.getScrollX(),-view.getScrollY());
    require(visible.contains(new Rect(0,0,view.getWidth(),view.getHeight())),label+" clipped by a parent: "+visible);
  }
  private static void safety(Instrumentation t,MainActivity a,File dir,String state)throws Exception{
    capture(t,a,dir,state);
    ui(t,()->{
      View r=root(a),toolbar=r.findViewWithTag("library-toolbar");Insets i=bars(a);
      android.util.Log.i("LoupeInsetsCheck",state+" os="+i+" cutout="+r.getRootWindowInsets().getInsets(WindowInsets.Type.displayCutout())+" root="+screen(r)+" padding="+r.getPaddingLeft()+","+r.getPaddingTop()+","+r.getPaddingRight()+","+r.getPaddingBottom()+" header="+(toolbar==null?"viewer":screen(toolbar))+" safe="+safe(a));
      if(toolbar!=null){inside(a,toolbar,"gallery toolbar");for(int n=0;n<((ViewGroup)toolbar).getChildCount();n++){View child=((ViewGroup)toolbar).getChildAt(n);if(child.isClickable())inside(a,child,"gallery header action");}}
      LibraryTabs tabs=ExpressiveUiChecks.find(r,LibraryTabs.class);if(tabs!=null)inside(a,tabs,"library navigation");
      if((boolean)get(a,"selecting"))inside(a,(View)get(a,"selectionShare"),"selection share");
      if((boolean)get(a,"detail")){
        PhotoViewerUi viewer=ViewerScreenChecks.viewer(a);
        inside(a,ExpressiveUiChecks.description(viewer,"写真一覧に戻る"),"viewer back");
        inside(a,ExpressiveUiChecks.description(viewer,"写真のその他の操作"),"viewer menu");
        for(int n=0;n<viewer.dock.getChildCount();n++)inside(a,viewer.dock.getChildAt(n),"viewer primary action");
        Button star=(Button)viewer.findViewWithTag("viewer-action-1位");if(star!=null)inside(a,star,"singleton best");
        View look=viewer.findViewWithTag("viewer-look");if(look!=null)inside(a,look,"viewer Look");
      }
    });
  }
  private static void tap(Instrumentation t,MainActivity a,View view,String label)throws Exception{
    int[] center=new int[2];ui(t,()->{
      require(view!=null&&view.isShown()&&view.isEnabled()&&view.hasWindowFocus(),label+" focused enabled target");
      Rect local=new Rect();require(view.getLocalVisibleRect(local),label+" visible");local.offset(-view.getScrollX(),-view.getScrollY());
      require(local.contains(view.getWidth()/2,view.getHeight()/2),label+" center clipped");
      Rect box=screen(view);center[0]=box.centerX();center[1]=box.centerY();require(safe(a).contains(center[0],center[1]),label+" center under OS bars: "+box);
    });
    long down=SystemClock.uptimeMillis();MotionEvent press=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,center[0],center[1],0),release=MotionEvent.obtain(down,down+60,MotionEvent.ACTION_UP,center[0],center[1],0);
    try{t.sendPointerSync(press);t.sendPointerSync(release);}finally{press.recycle();release.recycle();}settle(t,a);
  }
  private static Button star(MainActivity a){return (Button)ViewerScreenChecks.viewer(a).findViewWithTag("viewer-action-1位");}
  private static void openFirst(Instrumentation t,MainActivity a)throws Exception{
    tap(t,a,GalleryScreenChecks.firstPhoto(root(a)),"native singleton grid cell");
    Future<?> viewing=(Future<?>)get(a,"viewing");if(viewing!=null)viewing.get(30,TimeUnit.SECONDS);settle(t,a);
    ui(t,()->require((boolean)get(a,"detail")&&(int)get(a,"index")==0,"grid pointer opens singleton identity"));
  }
  static String run(Instrumentation t,int width,float font)throws Exception{
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    SharedPreferences prefs=a.getSharedPreferences("system-insets-checks",0);ArrayList<File> files=new ArrayList<>();
    try{
      GalleryScreenChecks.portraitFixture(t,a,width,font);GalleryScreenChecks.finishInitialIndex(t,a);
      require(prefs.edit().clear().commit(),"reset isolated ranking fixture");RankingStore rankings=new RankingStore(prefs);ArrayList<PhotoRepository.Photo> photos=new ArrayList<>();
      for(int n=0;n<4;n++){
        File file=new File(a.getCacheDir(),"system-insets-synthetic-"+n+".jpg");files.add(file);Bitmap bitmap=Bitmap.createBitmap(480,320,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff527c88+n*0x080600);
        try(FileOutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);}finally{bitmap.recycle();}
        photos.add(new PhotoRepository.Photo(Uri.fromFile(file),"合成写真"+n+".jpg","/synthetic-insets",file.length(),1794438000L+n,1794438000000L+n*1000));
      }
      ui(t,()->{
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        set(a,"photos",photos);set(a,"bundles",List.of(List.of(0),List.of(1),List.of(2,3)));set(a,"rankingStore",rankings);((Map<?,?>)get(a,"rankCache")).clear();
        set(a,"selecting",false);set(a,"rankFilter",0);set(a,"filter","すべて");set(a,"burstMembers",null);
        // Native album -> gallery render with synthetic state; no default album DB mutation/query.
        set(a,"albumHome",true);set(a,"albumFolder","/synthetic-insets");call(a,"gallery");
      });settle(t,a);
      File dir=new File(a.getCacheDir(),"consistency-checks");require(dir.isDirectory()||dir.mkdirs(),"capture directory");
      try{safety(t,a,dir,"album-gallery-"+width+"-"+font);}
      catch(AssertionError original){
        // Preserve the original failure while observing the reported Look-visible/star-hidden state.
        // Only the safe grid-cell center is tapped; never tap a header control under a system bar.
        try{
          openFirst(t,a);capture(t,a,dir,"viewer-baseline-"+width+"-"+font);
          ui(t,()->{
            View best=star(a),look=root(a).findViewWithTag("viewer-look");Rect safe=safe(a);
            android.util.Log.i("LoupeInsetsCheck","viewer-baseline os="+bars(a)+" safe="+safe
                +" starExists="+(best!=null)+" star="+(best==null?"absent":screen(best))
                +" starSafe="+(best!=null&&safe.contains(screen(best)))
                +" lookExists="+(look!=null)+" look="+(look==null?"absent":screen(look))
                +" lookSafe="+(look!=null&&safe.contains(screen(look))));
          });
        }catch(Throwable diagnostic){original.addSuppressed(diagnostic);android.util.Log.w("LoupeInsetsCheck","viewer-baseline diagnostic failed: "+diagnostic.getClass().getSimpleName());}
        throw original;
      }
      ui(t,()->{set(a,"albumFolder",null);call(a,"gallery");});settle(t,a);
      ui(t,()->require(ExpressiveUiChecks.find(root(a),LibraryTabs.class)!=null,"all-photos native library navigation present"));
      safety(t,a,dir,"all-photos-navigation");
      ui(t,()->{set(a,"albumFolder","/synthetic-insets");call(a,"gallery");});settle(t,a);
      for(int pass=0;pass<2;pass++){
        tap(t,a,TagDetailChecks.button(root(a),"選択"),"enter selection");
        ui(t,()->require((boolean)get(a,"selecting"),"pointer enters selection"));safety(t,a,dir,"selection-"+pass);
        tap(t,a,GalleryScreenChecks.firstPhoto(root(a)),"select singleton");
        ui(t,()->require(((Set<?>)get(a,"selectedPhotos")).size()==1,"pointer selected exactly one photo"));
        t.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);settle(t,a);
        ui(t,()->require(!(boolean)get(a,"selecting"),"system Back exits selection"));safety(t,a,dir,"gallery-back-"+pass);
      }
      openFirst(t,a);safety(t,a,dir,"viewer-unmarked");
      ui(t,()->require(star(a)!=null&&!star(a).isSelected()&&star(a).createAccessibilityNodeInfo().isCheckable(),"singleton 1位 exists, unmarked and AX checkable"));
      tap(t,a,star(a),"mark singleton best");((ExecutorService)get(a,"actions")).submit(()->{}).get(10,TimeUnit.SECONDS);settle(t,a);
      require(new RankingStore(prefs).singleBest(ArchiveStore.key(photos.get(0))),"pointer mark persisted in isolated store");
      ui(t,()->require(star(a).isSelected()&&star(a).createAccessibilityNodeInfo().isChecked(),"saved mark checked"));safety(t,a,dir,"viewer-marked");
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);settle(t,a);safety(t,a,dir,"marked-gallery-back");openFirst(t,a);
      ui(t,()->require(star(a).isSelected(),"reenter viewer reads committed mark"));
      tap(t,a,star(a),"unmark singleton best");((ExecutorService)get(a,"actions")).submit(()->{}).get(10,TimeUnit.SECONDS);settle(t,a);
      require(!new RankingStore(prefs).singleBest(ArchiveStore.key(photos.get(0))),"pointer unmark persisted");
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);settle(t,a);openFirst(t,a);ui(t,()->require(!star(a).isSelected(),"reenter viewer reads committed unmark"));
      // The existing native burst viewer must not acquire the singleton control.
      ui(t,()->a.onBackPressed());settle(t,a);ViewerScreenChecks.open(t,a,2);settle(t,a);
      ui(t,()->require(star(a)==null,"burst member has no singleton 1位 control"));safety(t,a,dir,"burst-viewer");
      android.util.Log.i("LoupeInsetsCheck","PASS singleton grid pointer mark/unmark/readback and burst isolation");
      return "PASS system-insets: actual OS bars and any reported cutout, full-display captures, album-gallery/all-photos navigation/selection/Back rerenders, physical singleton grid and 1位 mark/unmark/readback/reentry, burst isolation; synthetic cache files and isolated rankings only. Video behavior remains covered by video mode.\n";
    }finally{ui(t,()->{a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.finish();});prefs.edit().clear().commit();for(File file:files)file.delete();}
  }
}
