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

/** In-memory judgments and generated cache images only; never invokes tag persistence. */
final class TagDetailChecks {
  static Object get(TagActivity a,String name){try{Field f=TagActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(a);}catch(Exception e){throw new RuntimeException(e);}}
  static void set(TagActivity a,String name,Object value){try{Field f=TagActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);}catch(Exception e){throw new RuntimeException(e);}}
  static void call(TagActivity a,String name){try{Method m=TagActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(a);}catch(Exception e){throw new RuntimeException(e);}}
  static Button button(View v,String name){if(v instanceof Button&&(name.equals(((Button)v).getText().toString())||name.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())))return (Button)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button b=button(((ViewGroup)v).getChildAt(i),name);if(b!=null)return b;}return null;}
  static void ui(Instrumentation t,Runnable action){Throwable[] error={null};t.runOnMainSync(()->{try{action.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(error[0]);}
  static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
  static void idle(Instrumentation t,TagActivity a)throws Exception{((ExecutorService)get(a,"worker")).submit(()->{}).get(10,TimeUnit.SECONDS);t.waitForIdleSync();Thread.sleep(100);}
  static String run(Instrumentation t)throws Exception{
    TagActivity a=(TagActivity)t.startActivitySync(new Intent(t.getTargetContext(),TagActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));File file=new File(a.getCacheDir(),"tag-detail-synthetic.jpg");
    try{idle(t,a);Bitmap photo=Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888);photo.eraseColor(0xff718774);try(FileOutputStream out=new FileOutputStream(file)){photo.compress(Bitmap.CompressFormat.JPEG,90,out);}photo.recycle();
      ui(t,()->{a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);set(a,"tag",new TagStore.Tag("detail-fixture","空"));List<PhotoRepository.Photo> photos=(List<PhotoRepository.Photo>)get(a,"photos");List<String> ids=(List<String>)get(a,"photoIds");photos.clear();ids.clear();TagJudgments judgments=new TagJudgments();for(int i=0;i<40;i++){photos.add(new PhotoRepository.Photo(Uri.fromFile(file),"合成"+i+".jpg","",file.length(),i,0));ids.add("fixture-"+i);if(i<6)judgments=judgments.record("op-"+i,"detail-fixture","fixture-"+i,0,TagJudgments.Value.ASSIGNED);else if(i<8)judgments=judgments.record("op-"+i,"detail-fixture","fixture-"+i,0,TagJudgments.Value.EXCLUDED);}set(a,"judgments",judgments);set(a,"automatic",new HashMap<>());call(a,"renderTag");});idle(t,a);
      File dir=new File(a.getCacheDir(),"consistency-checks");dir.mkdirs();ConsistencyLayoutChecks.captureHardware(t,a,(View)get(a,"root"),dir,"tag-detail-normal");
      ui(t,()->{View root=(View)get(a,"root");require(((List<?>)get(a,"browsePhotos")).size()==6,"attached filter");require(button(root,"似た写真にもタグを付ける").isEnabled(),"automatic entry");button(root,"該当しない 2").performClick();});idle(t,a);
      ui(t,()->{require(((List<?>)get(a,"browsePhotos")).size()==2,"excluded filter");button((View)get(a,"root"),"一括訂正").performClick();require((boolean)get(a,"selecting"),"bulk entry");button((View)get(a,"root"),"すべて選択").performClick();require(((Set<?>)get(a,"selectedPhotos")).size()==2,"select all");require(button((View)get(a,"root"),"タグ付きにする").isEnabled(),"batch action");set(a,"busy",true);a.onBackPressed();require((boolean)get(a,"selecting"),"busy guard");set(a,"busy",false);a.onBackPressed();require(!(boolean)get(a,"selecting")&&((Set<?>)get(a,"selectedPhotos")).isEmpty(),"selection Back");button((View)get(a,"root"),"写真を追加").performClick();});idle(t,a);
      ui(t,()->{require(!(boolean)get(a,"tagOverview")&&(boolean)get(a,"selecting"),"add-photo entry");require(((List<?>)get(a,"browsePhotos")).size()==40,"all filter");require(!button((View)get(a,"root"),"タグ付きにする").isEnabled(),"empty selection disabled");a.onBackPressed();require(!(boolean)get(a,"selecting"),"add selection Back");});
      ui(t,()->{ScrollingGrid grid=(ScrollingGrid)get(a,"photoGrid");grid.restoreCell(0,-12,false);});idle(t,a);
      ui(t,()->{ScrollingGrid grid=(ScrollingGrid)get(a,"photoGrid");require(grid.getFirstVisiblePosition()==grid.getHeaderViewsCount(),"first photo row setup");int top=grid.getChildAt(0).getTop();Object filter=get(a,"browseFilter");try{Method m=TagActivity.class.getDeclaredMethod("renderPhotos",filter.getClass(),int.class,int.class,boolean.class);m.setAccessible(true);m.invoke(a,filter,0,top,false);}catch(Exception e){throw new RuntimeException(e);}});idle(t,a);
      ui(t,()->{ScrollingGrid grid=(ScrollingGrid)get(a,"photoGrid");require(grid.getFirstVisiblePosition()==grid.getHeaderViewsCount()&&grid.getChildAt(0).getTop()==-12,"first photo row restore");});
      return "PASS actual TagActivity with synthetic photos and in-memory judgments: attached/excluded filters, bulk/select-all, busy Back guard, selection cancellation, add-photo entry, empty selection disabled; PixelCopy of synthetic root. No persistence/inference/automatic removal executed.\n";
    }finally{ui(t,()->{a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.finish();});file.delete();}
  }
}
