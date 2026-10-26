package jp.nano.rawgallery;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual result screen with generated files; no persistence or sharing invoked. */
final class RankingResultChecks {
  static Object get(MainActivity a,String n){try{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f.get(a);}catch(Exception e){throw new RuntimeException(e);}}
  static void set(MainActivity a,String n,Object v){try{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);f.set(a,v);}catch(Exception e){throw new RuntimeException(e);}}
  static void call(MainActivity a,String n){try{Method m=MainActivity.class.getDeclaredMethod(n);m.setAccessible(true);m.invoke(a);}catch(Exception e){throw new RuntimeException(e);}}
  static void idle(Instrumentation t,MainActivity a,int width)throws Exception{((ExecutorService)get(a,"images")).submit(()->{}).get(10,TimeUnit.SECONDS);t.waitForIdleSync();t.waitForIdleSync();Thread.sleep(200);if(width>0)TagDetailChecks.ui(t,()->TagDetailChecks.require(((View)get(a,"root")).getWidth()==GalleryUi.dp(a,width),"constant window width"));}
  static String run(Instrumentation t,int width,boolean focusTest)throws Exception{
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));File file=new File(a.getCacheDir(),"result-synthetic.jpg");
    try{((ExecutorService)get(a,"scans")).submit(()->{}).get(10,TimeUnit.SECONDS);t.waitForIdleSync();Bitmap b=Bitmap.createBitmap(630,420,Bitmap.Config.ARGB_8888);b.eraseColor(0xff718774);try(FileOutputStream out=new FileOutputStream(file)){b.compress(Bitmap.CompressFormat.JPEG,90,out);}b.recycle();
      for(int count:new int[]{12,120})for(boolean best:new boolean[]{true,false})for(boolean failed:new boolean[]{false,true}){
        if(failed&&count==120)continue;
        ArrayList<PhotoRepository.Photo> photos=new ArrayList<>();ArrayList<String> ids=new ArrayList<>();Map<String,Integer> members=new LinkedHashMap<>();for(int i=0;i<count;i++){photos.add(new PhotoRepository.Photo(Uri.fromFile(failed?new File(a.getCacheDir(),"result-intentionally-missing.jpg"):file),!best&&i==0?"旅先の夕暮れを撮影した連写の長いファイル名_2026_10_26_0001.jpg":"fixture-"+i+".jpg","",file.length(),i,0));ids.add("result-fixture-"+i);members.put(ids.get(i),i);}
        PhotoRanking rank=new PhotoRanking(ids,best?"B:":"A:");while(!rank.done())rank=best?rank.choose(false):rank.tie();PhotoRanking complete=rank;
        TagDetailChecks.ui(t,()->{a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);set(a,"photos",photos);((Map<String,Integer>)get(a,"rankingMembers")).clear();((Map<String,Integer>)get(a,"rankingMembers")).putAll(members);set(a,"ranking",complete);set(a,"rankingResultState",null);set(a,"rankingResultFocus",null);call(a,"showRanking");});idle(t,a,width);
        TagDetailChecks.ui(t,()->{View root=(View)get(a,"root");TagDetailChecks.inspect(root);for(String id:ids){View cell=root.findViewWithTag("result-"+id);TagDetailChecks.require(cell!=null&&cell.isClickable(),"all photos open");TagDetailChecks.require(cell.getWidth()>=GalleryUi.dp(a,48)&&cell.getHeight()>=GalleryUi.dp(a,48),"photo target size");}if(count==120){LinearLayout last=(LinearLayout)root.findViewWithTag("result-"+ids.get(119));TagDetailChecks.require(((ImageView)((ViewGroup)last.getChildAt(0)).getChildAt(0)).getDrawable()==null,"offscreen preview deferred");}
          View hero=root.findViewWithTag("result-hero-"+ids.get(0));TagDetailChecks.require(hero!=null,"winner hero");TagDetailChecks.require(hero.getContentDescription().toString().startsWith(count+"枚の順位"),"rank summary");if(failed){TagDetailChecks.require(hero.getContentDescription().toString().contains("プレビューを読み込めませんでした"),"failed hero announced");}});
        File dir=new File(a.getCacheDir(),"consistency-checks");dir.mkdirs();ConsistencyLayoutChecks.captureHardware(t,a,(View)get(a,"root"),dir,"result-"+count+"-"+width+"-"+a.getResources().getConfiguration().fontScale+"-"+(best?"best":"tie")+(failed?"-failed":""));
        int[] position=new int[2],tap=new int[2];
        TagDetailChecks.ui(t,()->{ListView list=(ListView)((ViewGroup)get(a,"root")).getChildAt(0);list.scrollListBy(GalleryUi.dp(a,300));});idle(t,a,width);
        ConsistencyLayoutChecks.captureHardware(t,a,(View)get(a,"root"),dir,"result-"+count+"-"+width+"-"+a.getResources().getConfiguration().fontScale+"-"+(best?"best":"tie")+(failed?"-failed":"")+"-bottom");
        if(failed)TagDetailChecks.ui(t,()->{View item=((View)get(a,"root")).findViewWithTag("result-"+ids.get(0));android.graphics.Rect visible=new android.graphics.Rect();TagDetailChecks.require(item.getGlobalVisibleRect(visible)&&visible.height()==item.getHeight(),"failed thumbnail visible before assertion");TagDetailChecks.require(item.getContentDescription().toString().contains("プレビューを読み込めませんでした"),"failed thumbnail announced after scroll");});
        TagDetailChecks.ui(t,()->{ListView list=(ListView)((ViewGroup)get(a,"root")).getChildAt(0);position[0]=list.getFirstVisiblePosition();position[1]=list.getChildAt(0).getTop();View item=((View)get(a,"root")).findViewWithTag("result-"+ids.get(3));android.graphics.Rect rect=new android.graphics.Rect();TagDetailChecks.require(item.getGlobalVisibleRect(rect)&&rect.height()==item.getHeight(),"photo target visible");if(focusTest)TagDetailChecks.require(item.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,null),"focus photo before open");item.getLocationOnScreen(tap);tap[0]+=item.getWidth()/2;tap[1]+=item.getHeight()/2;android.util.Log.i("LoupeResultCheck","count="+count+" best="+best+" visible="+rect+" screenTap="+tap[0]+","+tap[1]);});
        if(focusTest){TagDetailChecks.ui(t,()->((View)get(a,"root")).findViewWithTag("result-"+ids.get(3)).performClick());}else{
        long down=android.os.SystemClock.uptimeMillis();MotionEvent press=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,tap[0],tap[1],0);MotionEvent release=MotionEvent.obtain(down,down+50,MotionEvent.ACTION_UP,tap[0],tap[1],0);try{t.sendPointerSync(press);t.sendPointerSync(release);}finally{press.recycle();release.recycle();}}idle(t,a,width);
        TagDetailChecks.ui(t,()->{TagDetailChecks.require(!(boolean)get(a,"rankingScreen"),"detail opened count="+count+" best="+best+" screenTap="+tap[0]+","+tap[1]);TagDetailChecks.require((int)get(a,"index")==3,"tapped photo identity");a.onBackPressed();});idle(t,a,width);
        TagDetailChecks.ui(t,()->TagDetailChecks.require((boolean)get(a,"rankingScreen")&&((PhotoRanking)get(a,"ranking")).journal.equals(complete.journal),"Back preserves result"));
        TagDetailChecks.ui(t,()->{ListView list=(ListView)((ViewGroup)get(a,"root")).getChildAt(0);TagDetailChecks.require(list.getFirstVisiblePosition()==position[0]&&list.getChildAt(0).getTop()==position[1],"result scroll restored expected="+position[0]+":"+position[1]+" actual="+list.getFirstVisiblePosition()+":"+list.getChildAt(0).getTop());if(focusTest)TagDetailChecks.require(list.findViewWithTag("result-"+ids.get(3)).isAccessibilityFocused(),"photo accessibility focus restored");});
      }
      return "PASS actual result: 12/120 synthetic photos; offscreen preview deferred, best-only/all-tied ranks, long winner filename, missing previews announced, tapped photo identity, all photo targets >=48dp, text bounds, detail/Back preserves ranking. No persistence/sharing invoked.\n";
    }finally{TagDetailChecks.ui(t,()->{a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.finish();});file.delete();}
  }
}
