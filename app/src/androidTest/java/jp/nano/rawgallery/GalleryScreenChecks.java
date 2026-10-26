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
      TagDetailChecks.require(date!=null,"other albums section");
      LinearLayout section=(LinearLayout)date.getParent().getParent();section.setVisibility(View.VISIBLE);
      LinearLayout cards=(LinearLayout)((HorizontalScrollView)section.getChildAt(1)).getChildAt(0);cards.removeAllViews();
      for(String name:new String[]{"カメラ","夕方の散歩","テスト撮影","スクリーンショットと長いアルバム名"})GalleryCarouselUi.photo(a,cards,name,false,()->{}).setImageDrawable(new android.graphics.drawable.ColorDrawable(0xff718774));
    });t.waitForIdleSync();
  }
  static ThumbnailCell firstPhoto(View view){if(view instanceof ThumbnailCell)return (ThumbnailCell)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){ThumbnailCell found=firstPhoto(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;}
  static String run(Instrumentation t,int width)throws Exception{
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    File file=new File(a.getCacheDir(),"gallery-screen-synthetic.jpg");
    try{settle(t,a);Bitmap bitmap=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff718774);try(FileOutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);}bitmap.recycle();
      for(String state:new String[]{"all","album","long"}){
        ArrayList<PhotoRepository.Photo> photos=new ArrayList<>();ArrayList<List<Integer>> groups=new ArrayList<>();
        for(int i=0;i<40;i++){photos.add(new PhotoRepository.Photo(Uri.fromFile(file),"合成"+i+".jpg","",file.length(),i,0));groups.add(List.of(i));}
        TagDetailChecks.ui(t,()->{a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);RankingResultChecks.set(a,"photos",photos);RankingResultChecks.set(a,"bundles",groups);RankingResultChecks.set(a,"selecting",false);RankingResultChecks.set(a,"rankFilter",0);RankingResultChecks.set(a,"burstMembers",null);RankingResultChecks.set(a,"albumFolder",state.equals("all")?null:"/synthetic/"+(state.equals("long")?"旅先で見つけた夕暮れの空と長い名前のアルバム":"嵐山 10.18"));RankingResultChecks.call(a,"gallery");});
        syntheticCarousel(t,a);
        View root=(View)RankingResultChecks.get(a,"root");File dir=new File(a.getCacheDir(),"consistency-checks");dir.mkdirs();
        TagDetailChecks.ui(t,()->{TagDetailChecks.inspect(root);ThumbnailCell first=firstPhoto(root);TagDetailChecks.require(first!=null&&first.getHeight()==GalleryUi.dp(a,96),"96dp photos");for(String name:new String[]{"すべて","1位","順位が未決定","順位決定済み"}){Button filter=TagDetailChecks.button(root,name);android.graphics.Rect visible=new android.graphics.Rect();TagDetailChecks.require(filter!=null&&filter.getGlobalVisibleRect(visible)&&visible.width()==filter.getWidth(),"filter fully visible: "+name);TagDetailChecks.require(filter.getPaddingLeft()>=GalleryUi.dp(a,16)&&filter.getPaddingRight()>=GalleryUi.dp(a,16),"filter interior spacing: "+name);}});
        TagDetailChecks.ui(t,()->{TextView count=(TextView)RankingResultChecks.get(a,"subtitle");View status=(View)RankingResultChecks.get(a,"listStatus");TagDetailChecks.require(status.getWidth()==0&&status.getHeight()==0,"idle status consumes no heading space");TagDetailChecks.require(count.getRight()==((View)count.getParent()).getWidth(),"count right aligned");TagDetailChecks.require(TagDetailChecks.button(root,"選択").getHeight()==GalleryUi.dp(a,64),"64dp gallery dock");Button firstFilter=TagDetailChecks.button(root,"すべて"),lastFilter=TagDetailChecks.button(root,"順位決定済み");TagDetailChecks.require(lastFilter.getTop()>firstFilter.getTop(),"filters wrap with interior spacing");});
        ConsistencyLayoutChecks.captureHardware(t,a,root,dir,"gallery-"+width+"-"+a.getResources().getConfiguration().fontScale+"-"+state);
        TagDetailChecks.ui(t,()->TagDetailChecks.button((View)RankingResultChecks.get(a,"root"),"1位").performClick());syntheticCarousel(t,a);
        TagDetailChecks.ui(t,()->{TagDetailChecks.require((int)RankingResultChecks.get(a,"rankFilter")==1&&((List<?>)RankingResultChecks.get(a,"displayed")).size()==40,"best filter preserves singleton photos");TagDetailChecks.button((View)RankingResultChecks.get(a,"root"),"選択").performClick();});settle(t,a);
        TagDetailChecks.ui(t,()->{ThumbnailCell first=firstPhoto((View)RankingResultChecks.get(a,"root"));TagDetailChecks.require(first!=null,"selection photo present");first.performClick();TagDetailChecks.require(((Set<?>)RankingResultChecks.get(a,"selectedPhotos")).size()==1,"photo selection");a.onBackPressed();TagDetailChecks.require(!(boolean)RankingResultChecks.get(a,"selecting")&&(int)RankingResultChecks.get(a,"rankFilter")==1,"Back leaves selection and preserves filter");});settle(t,a);
      }
      return "PASS synthetic actual gallery captures: all/album/long title. Existing data read only; carousel replaced before captures. Text bounds, all four visible filters, 96dp photos, filtering, selection and Back checked.\n";
    }finally{TagDetailChecks.ui(t,()->{a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.finish();});file.delete();}
  }
}
