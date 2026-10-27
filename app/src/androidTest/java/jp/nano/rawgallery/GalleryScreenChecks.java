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

/** Captures actual gallery structure after replacing photos and carousel contents with fixtures. */
final class GalleryScreenChecks {
  static void cloudBadgePresentation(Instrumentation t,MainActivity a)throws Exception{
    File dir=new File(a.getCacheDir(),"consistency-checks");dir.mkdirs();
    for(boolean selected:new boolean[]{false,true})TagDetailChecks.ui(t,()->{
      ThumbnailCell cell=new ThumbnailCell(a);cell.fixedHeight=GalleryUi.dp(a,96);cell.badges.photoSelection=true;cell.badges.selectionMode=true;cell.badges.selected=selected;
      cell.image.setBackgroundColor(0xff587c8a);int w=GalleryUi.dp(a,a.getResources().getConfiguration().screenWidthDp/4f-1.5f),h=GalleryUi.dp(a,96);
      cell.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));cell.layout(0,0,w,h);
      Bitmap without=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888),with=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
      try{cell.draw(new Canvas(without));cell.badges.cloud=true;cell.draw(new Canvas(with));
        int changed=0;Rect expected=new Rect(w-GalleryUi.dp(a,(selected?14:5)+24),h-GalleryUi.dp(a,(selected?14:5)+20),w-GalleryUi.dp(a,selected?14:5),h-GalleryUi.dp(a,selected?14:5));
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(without.getPixel(x,y)!=with.getPixel(x,y)){changed++;if(!expected.contains(x,y))throw new AssertionError("cloud badge outside reference position");}
        try(FileOutputStream out=new FileOutputStream(new File(dir,"selection-cloud-"+a.getResources().getConfiguration().screenWidthDp+"-"+a.getResources().getConfiguration().fontScale+"-"+selected+".png"))){with.compress(Bitmap.CompressFormat.PNG,100,out);}catch(IOException e){throw new java.io.UncheckedIOException(e);}
        if(changed<GalleryUi.dp(a,20))throw new AssertionError("cloud badge hidden during selection");
      }finally{without.recycle();with.recycle();}
    });
  }
  private static void portraitFixture(Instrumentation t,MainActivity a,int width,float font)throws Exception{
    TagDetailChecks.ui(t,()->a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
    long until=android.os.SystemClock.uptimeMillis()+10000;boolean[] ready={false};
    do{TagDetailChecks.ui(t,()->ready[0]=a.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT&&a.getWindow().getDecorView().getHeight()>GalleryUi.dp(a,600));if(ready[0])break;Thread.sleep(30);}while(android.os.SystemClock.uptimeMillis()<until);
    TagDetailChecks.require(ready[0],"portrait fixture height/orientation ready");
    TagDetailChecks.ui(t,()->{
      android.content.res.Configuration config=new android.content.res.Configuration(a.getResources().getConfiguration());config.fontScale=font;config.screenWidthDp=width;
      a.getResources().updateConfiguration(config,a.getResources().getDisplayMetrics());
      a.getWindow().setLayout(GalleryUi.dp(a,width),WindowManager.LayoutParams.MATCH_PARENT);
    });
    t.waitForIdleSync();ConsistencyLayoutChecks.frame(t,(View)RankingResultChecks.get(a,"root"));
  }
  private static void portraitDimensions(MainActivity a,View root,int width,float font){
    android.content.res.Configuration config=a.getResources().getConfiguration();
    TagDetailChecks.require(config.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT,"portrait fixture orientation");
    TagDetailChecks.require(config.screenWidthDp==width&&Math.abs(config.fontScale-font)<.001f,"portrait fixture requested configuration");
    TagDetailChecks.require(root.getWidth()==GalleryUi.dp(a,width)&&a.getWindow().getDecorView().getWidth()==GalleryUi.dp(a,width),"portrait fixture rendered width");
    TagDetailChecks.require(root.getHeight()>GalleryUi.dp(a,600),"portrait fixture rendered height");
  }
  private static void photoGeometry(MainActivity a,View root){
    TagDetailChecks.inspect(root);
    ThumbnailCell first=firstPhoto(root);
    TagDetailChecks.require(first!=null,"first photo exists");
    TagDetailChecks.require(first.getHeight()==GalleryUi.dp(a,96),"96dp photos");
    for(String name:new String[]{"すべて","1位","順位が未決定","順位決定済み"}){
      Button filter=TagDetailChecks.button(root,name);
      TagDetailChecks.require(filter!=null,"filter exists");
      android.graphics.Rect visible=new android.graphics.Rect();
      TagDetailChecks.require(filter.getGlobalVisibleRect(visible),"filter visible");
      TagDetailChecks.require(visible.width()==filter.getWidth(),"filter fully visible");
      TagDetailChecks.require(filter.getPaddingLeft()>=GalleryUi.dp(a,16)&&filter.getPaddingRight()>=GalleryUi.dp(a,16),"filter interior spacing");
    }
  }
  private static String statusState(StatusView status){
    try{String result="";for(String name:new String[]{"loading","shown","symbol","collapseWhenIdle"}){java.lang.reflect.Field field=StatusView.class.getDeclaredField(name);field.setAccessible(true);result+=name+":"+field.get(status)+",";}return result;}catch(ReflectiveOperationException e){throw new RuntimeException(e);}
  }
  private static void headingGeometry(MainActivity a,View root){
    TextView count=(TextView)RankingResultChecks.get(a,"subtitle");
    View status=(View)RankingResultChecks.get(a,"listStatus");
    TagDetailChecks.require(status.getWidth()==0&&status.getHeight()==0,"idle status consumes no heading space");
    TagDetailChecks.require(count.getRight()==((View)count.getParent()).getWidth(),"count right aligned");
    TagDetailChecks.require(TagDetailChecks.button(root,"選択").getHeight()==GalleryUi.dp(a,64),"64dp gallery dock");
    Button firstFilter=TagDetailChecks.button(root,"すべて"),lastFilter=TagDetailChecks.button(root,"順位決定済み");
    TagDetailChecks.require(lastFilter.getTop()>firstFilter.getTop(),"filters wrap with interior spacing");
  }
  static void finishInitialIndex(Instrumentation t,MainActivity a)throws Exception{
    TagDetailChecks.ui(t,()->{a.getContentResolver().unregisterContentObserver((android.database.ContentObserver)RankingResultChecks.get(a,"mediaObserver"));((android.os.Handler)RankingResultChecks.get(a,"main")).removeCallbacks((Runnable)RankingResultChecks.get(a,"mediaChanged"));});
    java.lang.reflect.Field running=IndexRefreshRequests.class.getDeclaredField("running");running.setAccessible(true);
    for(int i=0;i<10;i++){
      GalleryIndex.WORKER.submit(()->{}).get(30,TimeUnit.SECONDS);t.waitForIdleSync();boolean[] active={true};TagDetailChecks.ui(t,()->{try{active[0]=running.getBoolean(RankingResultChecks.get(a,"refreshRequests"));}catch(IllegalAccessException e){throw new RuntimeException(e);}});
      if(!active[0]){settle(t,a);return;}
    }
    throw new AssertionError("initial index did not finish before synthetic data");
  }
  static void settle(Instrumentation t,MainActivity a)throws Exception{
    ((ExecutorService)RankingResultChecks.get(a,"scans")).submit(()->{}).get(15,TimeUnit.SECONDS);
    t.waitForIdleSync();
    ((ExecutorService)RankingResultChecks.get(a,"images")).submit(()->{}).get(15,TimeUnit.SECONDS);
    t.waitForIdleSync();
    Thread.sleep(150);
  }
  static void syntheticCarousel(Instrumentation t,MainActivity a)throws Exception{
    settle(t,a);
    TagDetailChecks.ui(t,()->{
      View root=(View)RankingResultChecks.get(a,"root");View date=root.findViewWithTag("carousel-date");
      if(date==null){TagDetailChecks.require((boolean)RankingResultChecks.get(a,"selecting"),"other albums section outside selection");return;}
      LinearLayout section=(LinearLayout)date.getParent().getParent();section.setVisibility(View.VISIBLE);
      LinearLayout cards=(LinearLayout)((HorizontalScrollView)section.getChildAt(1)).getChildAt(0);cards.removeAllViews();
      for(String name:new String[]{"カメラ","夕方の散歩","テスト撮影","スクリーンショットと長いアルバム名"})GalleryCarouselUi.photo(a,cards,name,false,()->{}).setImageDrawable(new android.graphics.drawable.ColorDrawable(0xff718774));
    });t.waitForIdleSync();
  }
  static ThumbnailCell firstPhoto(View view){if(view instanceof ThumbnailCell)return (ThumbnailCell)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){ThumbnailCell found=firstPhoto(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;}
  static void selectionFiltersVisible(View root){
    for(String name:new String[]{"すべて","1位","順位が未決定","順位決定済み"}){Button chip=TagDetailChecks.button(root,name);android.graphics.Rect visible=new android.graphics.Rect();TagDetailChecks.require(chip!=null&&chip.getGlobalVisibleRect(visible)&&visible.width()==chip.getWidth(),"selection filter fully visible: "+name);}
    Button first=TagDetailChecks.button(root,"すべて");ViewGroup panel=(ViewGroup)first.getParent().getParent();TagDetailChecks.require(!panel.getClipToPadding(),"selection filter can draw in footer padding");
  }
  static void selectionPresentation(Instrumentation t,MainActivity a,boolean any)throws Exception{
    TagDetailChecks.ui(t,()->{View root=(View)RankingResultChecks.get(a,"root");TagDetailChecks.inspect(root);selectionFiltersVisible(root);Button share=(Button)RankingResultChecks.get(a,"selectionShare"),sort=(Button)RankingResultChecks.get(a,"selectionSort");TagDetailChecks.require(share.isEnabled()==any,"share enabled state");for(Button action:new Button[]{share,sort})TagDetailChecks.require(action.getCompoundDrawables()[1]==null,"single inline action icon");android.graphics.drawable.GradientDrawable bg=(android.graphics.drawable.GradientDrawable)share.getBackground().getCurrent();TagDetailChecks.require(bg.getColor().getDefaultColor()==(any?GalleryUi.TEXT:0xff26262a),"share state background");TagDetailChecks.require(!sort.isEnabled(),"singleton sort disabled");});
  }
  static void selectionStates(Instrumentation t,MainActivity a,List<File> fixtures,int width)throws Exception{
    ArrayList<PhotoRepository.Photo> photos=new ArrayList<>();for(int i=0;i<9;i++)photos.add(new PhotoRepository.Photo(Uri.fromFile(fixtures.get(i)),"合成状態"+i+".jpg","",fixtures.get(i).length(),i,0));
    List<Integer> undecided=List.of(0,1,2),decided=List.of(3,4,5);ArrayList<String> pendingIds=new ArrayList<>(),doneIds=new ArrayList<>();for(int i:undecided)pendingIds.add(ArchiveStore.key(photos.get(i)));for(int i:decided)doneIds.add(ArchiveStore.key(photos.get(i)));PhotoRanking done=new PhotoRanking(doneIds,"B:");while(!done.done())done=done.choose(true);PhotoRanking finished=done;
    TagDetailChecks.ui(t,()->{RankingResultChecks.set(a,"photos",photos);RankingResultChecks.set(a,"bundles",List.of(undecided,decided,List.of(6),List.of(7),List.of(8)));Map<List<Integer>,PhotoRanking> cache=(Map<List<Integer>,PhotoRanking>)RankingResultChecks.get(a,"rankCache");cache.clear();cache.put(undecided,new PhotoRanking(pendingIds,"B:"));cache.put(decided,finished);Set<String> selected=(Set<String>)RankingResultChecks.get(a,"selectedPhotos");selected.clear();for(int i=0;i<7;i++)selected.add(photos.get(i).uri.toString());RankingResultChecks.set(a,"selecting",true);RankingResultChecks.set(a,"bestOnly",true);RankingResultChecks.set(a,"rankFilter",0);RankingResultChecks.set(a,"albumFolder","/synthetic/嵐山 10.18");RankingResultChecks.call(a,"gallery");});settle(t,a);
    File dir=new File(a.getCacheDir(),"consistency-checks");TagDetailChecks.ui(t,()->{Button sort=(Button)RankingResultChecks.get(a,"selectionSort");TagDetailChecks.require(sort.isEnabled()&&sort.getCurrentTextColor()==0xfff5b947,"pending sort enabled and gold");TagDetailChecks.require(((View)RankingResultChecks.get(a,"selectionCount")).getContentDescription().toString().contains("未決定の連写 1組"),"pending count");selectionFiltersVisible((View)RankingResultChecks.get(a,"root"));TagDetailChecks.inspect((View)RankingResultChecks.get(a,"root"));});
    ConsistencyLayoutChecks.captureHardware(t,a,(View)RankingResultChecks.get(a,"root"),dir,"gallery-selection-pending-"+width+"-"+a.getResources().getConfiguration().fontScale);
    TagDetailChecks.ui(t,()->TagDetailChecks.button((View)RankingResultChecks.get(a,"root"),"順位決定済み").performClick());settle(t,a);
    TagDetailChecks.ui(t,()->{String summary=((View)RankingResultChecks.get(a,"selectionCount")).getContentDescription().toString();TagDetailChecks.require(((Set<?>)RankingResultChecks.get(a,"selectedPhotos")).size()==7&&summary.contains("見えない選択 4枚"),"hidden selection retained");});
    ConsistencyLayoutChecks.captureHardware(t,a,(View)RankingResultChecks.get(a,"root"),dir,"gallery-selection-hidden-"+width+"-"+a.getResources().getConfiguration().fontScale);
    TagDetailChecks.ui(t,()->{SelectionUi.BestSwitch toggle=findSwitch((View)RankingResultChecks.get(a,"root"));TagDetailChecks.require(toggle!=null&&toggle.isChecked(),"best switch initial");toggle.performClick();TagDetailChecks.require(!(boolean)RankingResultChecks.get(a,"bestOnly")&&!((Button)RankingResultChecks.get(a,"selectionSort")).isEnabled(),"all burst photos no pending sort");((Button)RankingResultChecks.get(a,"selectionAll")).performClick();TagDetailChecks.require(((Set<?>)RankingResultChecks.get(a,"selectedPhotos")).size()==4,"visible deselection retains hidden photos");a.onBackPressed();});settle(t,a);
  }
  static SelectionUi.BestSwitch findSwitch(View view){if(view instanceof SelectionUi.BestSwitch)return (SelectionUi.BestSwitch)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){SelectionUi.BestSwitch result=findSwitch(((ViewGroup)view).getChildAt(i));if(result!=null)return result;}return null;}
  static void selectFirst(Instrumentation t,MainActivity a,boolean talkBack)throws Exception{
    int[] point=new int[2];
    TagDetailChecks.ui(t,()->{ThumbnailCell photo=firstPhoto((View)RankingResultChecks.get(a,"root"));TagDetailChecks.require(photo!=null,"selection photo present");android.graphics.Rect visible=new android.graphics.Rect();TagDetailChecks.require(photo.getGlobalVisibleRect(visible)&&visible.height()==photo.getHeight(),"tap photo fully visible");if(talkBack){TagDetailChecks.require(photo.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,null),"TalkBack photo focus");TagDetailChecks.require(photo.isAccessibilityFocused(),"TalkBack focus applied");TagDetailChecks.require(photo.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,null),"TalkBack photo click");}else{photo.getLocationOnScreen(point);point[0]+=photo.getWidth()/2;point[1]+=photo.getHeight()/2;}});
    if(!talkBack){long down=android.os.SystemClock.uptimeMillis();MotionEvent press=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,point[0],point[1],0),release=MotionEvent.obtain(down,down+50,MotionEvent.ACTION_UP,point[0],point[1],0);try{t.sendPointerSync(press);t.sendPointerSync(release);}finally{press.recycle();release.recycle();}}
    settle(t,a);TagDetailChecks.ui(t,()->TagDetailChecks.require(((Set<?>)RankingResultChecks.get(a,"selectedPhotos")).size()==1,"photo selection after input"));
  }
  static String run(Instrumentation t,int width)throws Exception{return run(t,width,false);}
  static String run(Instrumentation t,int width,boolean talkBack)throws Exception{
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    int originalOrientation=a.getRequestedOrientation();float requestedFont=a.getResources().getConfiguration().fontScale;
    File file=new File(a.getCacheDir(),"gallery-screen-synthetic.jpg");ArrayList<File> fixtures=new ArrayList<>();
    try{portraitFixture(t,a,width,requestedFont);if(talkBack){android.view.accessibility.AccessibilityManager manager=(android.view.accessibility.AccessibilityManager)a.getSystemService(Context.ACCESSIBILITY_SERVICE);long until=android.os.SystemClock.uptimeMillis()+5000;while(!manager.isTouchExplorationEnabled()&&android.os.SystemClock.uptimeMillis()<until)Thread.sleep(100);TagDetailChecks.require(manager.isTouchExplorationEnabled(),"real TalkBack touch exploration enabled");}
      finishInitialIndex(t,a);cloudBadgePresentation(t,a);Bitmap bitmap=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff718774);try(FileOutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);}bitmap.recycle();for(int i=0;i<40;i++){File fixture=new File(a.getCacheDir(),"gallery-screen-synthetic-"+i+".jpg");java.nio.file.Files.copy(file.toPath(),fixture.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);fixtures.add(fixture);}
      for(String state:new String[]{"all","album","long"}){
        ArrayList<PhotoRepository.Photo> photos=new ArrayList<>();ArrayList<List<Integer>> groups=new ArrayList<>();
        for(int i=0;i<40;i++){photos.add(new PhotoRepository.Photo(Uri.fromFile(fixtures.get(i)),"合成"+i+".jpg","",file.length(),i,0));groups.add(List.of(i));}
        TagDetailChecks.ui(t,()->{a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);RankingResultChecks.set(a,"photos",photos);RankingResultChecks.set(a,"bundles",groups);RankingResultChecks.set(a,"selecting",false);RankingResultChecks.set(a,"rankFilter",0);RankingResultChecks.set(a,"burstMembers",null);RankingResultChecks.set(a,"albumFolder",state.equals("all")?null:"/synthetic/"+(state.equals("long")?"旅先で見つけた夕暮れの空と長い名前のアルバム":"嵐山 10.18"));RankingResultChecks.call(a,"gallery");});
        syntheticCarousel(t,a);
        View root=(View)RankingResultChecks.get(a,"root");File dir=new File(a.getCacheDir(),"consistency-checks");dir.mkdirs();
        ConsistencyLayoutChecks.frame(t,root);
        try{TagDetailChecks.ui(t,()->{portraitDimensions(a,root,width,requestedFont);photoGeometry(a,root);headingGeometry(a,root);});}
        catch(AssertionError e){
          TagDetailChecks.ui(t,()->{ThumbnailCell photo=firstPhoto(root);TextView count=(TextView)RankingResultChecks.get(a,"subtitle");View status=(View)RankingResultChecks.get(a,"listStatus");Button dock=TagDetailChecks.button(root,"選択");android.util.Log.e("LoupeFixtureGeometry","root="+root.getWidth()+"x"+root.getHeight()+" photoH="+(photo==null?-1:photo.getHeight())+" countRight="+count.getRight()+" countParentW="+((View)count.getParent()).getWidth()+" status="+status.getWidth()+"x"+status.getHeight()+" measured="+status.getMeasuredWidth()+"x"+status.getMeasuredHeight()+" requested="+status.isLayoutRequested()+" parentRequested="+((View)status.getParent()).isLayoutRequested()+" attached="+status.isAttachedToWindow()+" rootCurrent="+(root==RankingResultChecks.get(a,"root"))+" state="+statusState((StatusView)status)+" dockH="+(dock==null?-1:dock.getHeight()));});
          ConsistencyLayoutChecks.captureHardware(t,a,root,dir,"gallery-geometry-failed-"+width+"-"+a.getResources().getConfiguration().fontScale+"-"+state);throw e;
        }
        ConsistencyLayoutChecks.captureHardware(t,a,root,dir,"gallery-"+width+"-"+a.getResources().getConfiguration().fontScale+"-"+state);
        TagDetailChecks.ui(t,()->TagDetailChecks.button((View)RankingResultChecks.get(a,"root"),"1位").performClick());syntheticCarousel(t,a);
        TagDetailChecks.ui(t,()->{TagDetailChecks.require((int)RankingResultChecks.get(a,"rankFilter")==1&&((List<?>)RankingResultChecks.get(a,"displayed")).size()==40,"best filter preserves singleton photos state="+state+" filter="+RankingResultChecks.get(a,"rankFilter")+" displayed="+((List<?>)RankingResultChecks.get(a,"displayed")).size()+" fixtureIdentity="+(RankingResultChecks.get(a,"photos")==photos));TagDetailChecks.button((View)RankingResultChecks.get(a,"root"),"選択").performClick();});syntheticCarousel(t,a);
        selectionPresentation(t,a,false);
        ConsistencyLayoutChecks.captureHardware(t,a,(View)RankingResultChecks.get(a,"root"),dir,"gallery-selection-empty-"+width+"-"+state);
        selectFirst(t,a,talkBack);
        selectionPresentation(t,a,true);
        ConsistencyLayoutChecks.captureHardware(t,a,(View)RankingResultChecks.get(a,"root"),dir,"gallery-selection-one-"+width+"-"+state);
        TagDetailChecks.ui(t,()->{a.onBackPressed();TagDetailChecks.require(!(boolean)RankingResultChecks.get(a,"selecting")&&(int)RankingResultChecks.get(a,"rankFilter")==1,"Back leaves selection and preserves filter");});settle(t,a);
      }
      selectionStates(t,a,fixtures,width);
      if(talkBack)TagDetailChecks.ui(t,()->{RankingResultChecks.set(a,"selecting",true);RankingResultChecks.call(a,"gallery");});
      if(talkBack){settle(t,a);TagDetailChecks.ui(t,()->{SelectionUi.BestSwitch toggle=findSwitch((View)RankingResultChecks.get(a,"root"));TagDetailChecks.require(toggle.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,null),"TalkBack switch focus");android.view.accessibility.AccessibilityNodeInfo node=toggle.createAccessibilityNodeInfo();TagDetailChecks.require(node.isCheckable()&&android.widget.Switch.class.getName().contentEquals(node.getClassName()),"TalkBack switch semantics");boolean before=toggle.isChecked();TagDetailChecks.require(toggle.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,null)&&toggle.isChecked()!=before,"TalkBack switch toggles");a.onBackPressed();});}
      return "PASS "+(talkBack?"real TalkBack focus/AX click/switch semantics; ":"physical photo taps; ")+"selection states/backgrounds/single icons, pending sort, hidden retention, best switch, visible deselection; synthetic actual gallery captures: all/album/long title. Existing data read only; carousel replaced before captures. Text bounds, all four visible filters, 96dp photos, filtering, selection and Back checked.\n";
    }finally{TagDetailChecks.ui(t,()->{a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.setRequestedOrientation(originalOrientation);a.finish();});file.delete();for(File fixture:fixtures)fixture.delete();}
  }
}
