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
  static void settle(Instrumentation t,MainActivity a)throws Exception{
    ((ExecutorService)RankingResultChecks.get(a,"scans")).submit(()->{}).get(15,TimeUnit.SECONDS);
    t.waitForIdleSync();
    ((ExecutorService)RankingResultChecks.get(a,"images")).submit(()->{}).get(15,TimeUnit.SECONDS);
    t.waitForIdleSync();Thread.sleep(150);
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
  static void selectionPresentation(Instrumentation t,MainActivity a,boolean any)throws Exception{
    TagDetailChecks.ui(t,()->{View root=(View)RankingResultChecks.get(a,"root");TagDetailChecks.inspect(root);Button share=(Button)RankingResultChecks.get(a,"selectionShare"),sort=(Button)RankingResultChecks.get(a,"selectionSort");TagDetailChecks.require(share.isEnabled()==any,"share enabled state");for(Button action:new Button[]{share,sort})TagDetailChecks.require(action.getCompoundDrawables()[1]==null,"single inline action icon");android.graphics.drawable.GradientDrawable bg=(android.graphics.drawable.GradientDrawable)share.getBackground().getCurrent();TagDetailChecks.require(bg.getColor().getDefaultColor()==(any?GalleryUi.TEXT:0xff26262a),"share state background");TagDetailChecks.require(!sort.isEnabled(),"singleton sort disabled");});
  }
  static void selectionStates(Instrumentation t,MainActivity a,List<File> fixtures,int width)throws Exception{
    ArrayList<PhotoRepository.Photo> photos=new ArrayList<>();for(int i=0;i<9;i++)photos.add(new PhotoRepository.Photo(Uri.fromFile(fixtures.get(i)),"合成状態"+i+".jpg","",fixtures.get(i).length(),i,0));
    List<Integer> undecided=List.of(0,1,2),decided=List.of(3,4,5);ArrayList<String> pendingIds=new ArrayList<>(),doneIds=new ArrayList<>();for(int i:undecided)pendingIds.add(ArchiveStore.key(photos.get(i)));for(int i:decided)doneIds.add(ArchiveStore.key(photos.get(i)));PhotoRanking done=new PhotoRanking(doneIds,"B:");while(!done.done())done=done.choose(true);PhotoRanking finished=done;
    TagDetailChecks.ui(t,()->{RankingResultChecks.set(a,"photos",photos);RankingResultChecks.set(a,"bundles",List.of(undecided,decided,List.of(6),List.of(7),List.of(8)));Map<List<Integer>,PhotoRanking> cache=(Map<List<Integer>,PhotoRanking>)RankingResultChecks.get(a,"rankCache");cache.clear();cache.put(undecided,new PhotoRanking(pendingIds,"B:"));cache.put(decided,finished);Set<String> selected=(Set<String>)RankingResultChecks.get(a,"selectedPhotos");selected.clear();for(int i=0;i<7;i++)selected.add(photos.get(i).uri.toString());RankingResultChecks.set(a,"selecting",true);RankingResultChecks.set(a,"bestOnly",true);RankingResultChecks.set(a,"rankFilter",0);RankingResultChecks.set(a,"albumFolder","/synthetic/嵐山 10.18");RankingResultChecks.call(a,"gallery");});settle(t,a);
    File dir=new File(a.getCacheDir(),"consistency-checks");TagDetailChecks.ui(t,()->{Button sort=(Button)RankingResultChecks.get(a,"selectionSort");TagDetailChecks.require(sort.isEnabled()&&sort.getCurrentTextColor()==0xfff5b947,"pending sort enabled and gold");TagDetailChecks.require(((View)RankingResultChecks.get(a,"selectionCount")).getContentDescription().toString().contains("未決定の連写 1組"),"pending count");TagDetailChecks.inspect((View)RankingResultChecks.get(a,"root"));});
    ConsistencyLayoutChecks.captureHardware(t,a,(View)RankingResultChecks.get(a,"root"),dir,"gallery-selection-pending-"+width+"-"+a.getResources().getConfiguration().fontScale);
    TagDetailChecks.ui(t,()->TagDetailChecks.button((View)RankingResultChecks.get(a,"root"),"順位決定済み").performClick());settle(t,a);
    TagDetailChecks.ui(t,()->{String summary=((View)RankingResultChecks.get(a,"selectionCount")).getContentDescription().toString();TagDetailChecks.require(((Set<?>)RankingResultChecks.get(a,"selectedPhotos")).size()==7&&summary.contains("見えない選択 4枚"),"hidden selection retained");});
    ConsistencyLayoutChecks.captureHardware(t,a,(View)RankingResultChecks.get(a,"root"),dir,"gallery-selection-hidden-"+width+"-"+a.getResources().getConfiguration().fontScale);
    TagDetailChecks.ui(t,()->{SelectionUi.BestSwitch toggle=findSwitch((View)RankingResultChecks.get(a,"root"));TagDetailChecks.require(toggle!=null&&toggle.isChecked(),"best switch initial");toggle.performClick();TagDetailChecks.require(!(boolean)RankingResultChecks.get(a,"bestOnly")&&!((Button)RankingResultChecks.get(a,"selectionSort")).isEnabled(),"all burst photos no pending sort");((Button)RankingResultChecks.get(a,"selectionAll")).performClick();TagDetailChecks.require(((Set<?>)RankingResultChecks.get(a,"selectedPhotos")).size()==4,"visible deselection retains hidden photos");a.onBackPressed();});settle(t,a);
  }
  static SelectionUi.BestSwitch findSwitch(View view){if(view instanceof SelectionUi.BestSwitch)return (SelectionUi.BestSwitch)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){SelectionUi.BestSwitch result=findSwitch(((ViewGroup)view).getChildAt(i));if(result!=null)return result;}return null;}
  static String run(Instrumentation t,int width)throws Exception{
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    File file=new File(a.getCacheDir(),"gallery-screen-synthetic.jpg");ArrayList<File> fixtures=new ArrayList<>();
    try{settle(t,a);Bitmap bitmap=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff718774);try(FileOutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);}bitmap.recycle();for(int i=0;i<40;i++){File fixture=new File(a.getCacheDir(),"gallery-screen-synthetic-"+i+".jpg");java.nio.file.Files.copy(file.toPath(),fixture.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);fixtures.add(fixture);}
      for(String state:new String[]{"all","album","long"}){
        ArrayList<PhotoRepository.Photo> photos=new ArrayList<>();ArrayList<List<Integer>> groups=new ArrayList<>();
        for(int i=0;i<40;i++){photos.add(new PhotoRepository.Photo(Uri.fromFile(fixtures.get(i)),"合成"+i+".jpg","",file.length(),i,0));groups.add(List.of(i));}
        TagDetailChecks.ui(t,()->{a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);RankingResultChecks.set(a,"photos",photos);RankingResultChecks.set(a,"bundles",groups);RankingResultChecks.set(a,"selecting",false);RankingResultChecks.set(a,"rankFilter",0);RankingResultChecks.set(a,"burstMembers",null);RankingResultChecks.set(a,"albumFolder",state.equals("all")?null:"/synthetic/"+(state.equals("long")?"旅先で見つけた夕暮れの空と長い名前のアルバム":"嵐山 10.18"));RankingResultChecks.call(a,"gallery");});
        syntheticCarousel(t,a);
        View root=(View)RankingResultChecks.get(a,"root");File dir=new File(a.getCacheDir(),"consistency-checks");dir.mkdirs();
        TagDetailChecks.ui(t,()->{TagDetailChecks.inspect(root);ThumbnailCell first=firstPhoto(root);TagDetailChecks.require(first!=null&&first.getHeight()==GalleryUi.dp(a,96),"96dp photos");for(String name:new String[]{"すべて","1位","順位が未決定","順位決定済み"}){Button filter=TagDetailChecks.button(root,name);android.graphics.Rect visible=new android.graphics.Rect();TagDetailChecks.require(filter!=null&&filter.getGlobalVisibleRect(visible)&&visible.width()==filter.getWidth(),"filter fully visible: "+name);TagDetailChecks.require(filter.getPaddingLeft()>=GalleryUi.dp(a,16)&&filter.getPaddingRight()>=GalleryUi.dp(a,16),"filter interior spacing: "+name);}});
        TagDetailChecks.ui(t,()->{TextView count=(TextView)RankingResultChecks.get(a,"subtitle");View status=(View)RankingResultChecks.get(a,"listStatus");TagDetailChecks.require(status.getWidth()==0&&status.getHeight()==0,"idle status consumes no heading space");TagDetailChecks.require(count.getRight()==((View)count.getParent()).getWidth(),"count right aligned");TagDetailChecks.require(TagDetailChecks.button(root,"選択").getHeight()==GalleryUi.dp(a,64),"64dp gallery dock");Button firstFilter=TagDetailChecks.button(root,"すべて"),lastFilter=TagDetailChecks.button(root,"順位決定済み");TagDetailChecks.require(lastFilter.getTop()>firstFilter.getTop(),"filters wrap with interior spacing");});
        ConsistencyLayoutChecks.captureHardware(t,a,root,dir,"gallery-"+width+"-"+a.getResources().getConfiguration().fontScale+"-"+state);
        TagDetailChecks.ui(t,()->TagDetailChecks.button((View)RankingResultChecks.get(a,"root"),"1位").performClick());syntheticCarousel(t,a);
        TagDetailChecks.ui(t,()->{TagDetailChecks.require((int)RankingResultChecks.get(a,"rankFilter")==1&&((List<?>)RankingResultChecks.get(a,"displayed")).size()==40,"best filter preserves singleton photos");TagDetailChecks.button((View)RankingResultChecks.get(a,"root"),"選択").performClick();});syntheticCarousel(t,a);
        selectionPresentation(t,a,false);
        ConsistencyLayoutChecks.captureHardware(t,a,(View)RankingResultChecks.get(a,"root"),dir,"gallery-selection-empty-"+width+"-"+state);
        TagDetailChecks.ui(t,()->{ThumbnailCell first=firstPhoto((View)RankingResultChecks.get(a,"root"));TagDetailChecks.require(first!=null,"selection photo present");first.performClick();TagDetailChecks.require(((Set<?>)RankingResultChecks.get(a,"selectedPhotos")).size()==1,"photo selection");});settle(t,a);
        selectionPresentation(t,a,true);
        ConsistencyLayoutChecks.captureHardware(t,a,(View)RankingResultChecks.get(a,"root"),dir,"gallery-selection-one-"+width+"-"+state);
        TagDetailChecks.ui(t,()->{a.onBackPressed();TagDetailChecks.require(!(boolean)RankingResultChecks.get(a,"selecting")&&(int)RankingResultChecks.get(a,"rankFilter")==1,"Back leaves selection and preserves filter");});settle(t,a);
      }
      selectionStates(t,a,fixtures,width);
      return "PASS selection states/backgrounds/single icons, pending sort, hidden retention, best switch, visible deselection; synthetic actual gallery captures: all/album/long title. Existing data read only; carousel replaced before captures. Text bounds, all four visible filters, 96dp photos, filtering, selection and Back checked.\n";
    }finally{TagDetailChecks.ui(t,()->{a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.finish();});file.delete();for(File fixture:fixtures)fixture.delete();}
  }
}
