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
  static int width;
  static void inspect(View v) {
    if(v.getVisibility()!=View.VISIBLE)return;
    if(v instanceof TextView){TextView text=(TextView)v;android.text.Layout layout=text.getLayout();
      if(layout!=null){require(layout.getHeight()<=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom(),"text height: "+text.getText());
        for(int i=0;i<layout.getLineCount();i++){
          require(layout.getEllipsisCount(i)==0,"ellipsis: "+text.getText());
          int available=text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight();
          require(layout.getLineLeft(i)>=-1&&layout.getLineRight(i)<=available+1,"text width: "+text.getText());
        }}}
    if(v instanceof Button)require(v.getHeight()>=Math.round(48*v.getResources().getDisplayMetrics().density),"touch height");
    if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)inspect(((ViewGroup)v).getChildAt(i));
  }
  static void idle(Instrumentation t,TagActivity a)throws Exception{((ExecutorService)get(a,"worker")).submit(()->{}).get(10,TimeUnit.SECONDS);t.waitForIdleSync();if(width>0)ui(t,()->{View root=(View)get(a,"root");ViewGroup.LayoutParams params=root.getLayoutParams();params.width=Math.round(width*a.getResources().getDisplayMetrics().density);root.setLayoutParams(params);});t.waitForIdleSync();Thread.sleep(150);}
  static String run(Instrumentation t,int requestedWidth)throws Exception{
    width=requestedWidth;
    TagActivity a=(TagActivity)t.startActivitySync(new Intent(t.getTargetContext(),TagActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));File file=new File(a.getCacheDir(),"tag-detail-synthetic.jpg");
    try{idle(t,a);
      ui(t,()->{TextView clipped=new TextView(a);clipped.setText("Horizontal clipping must fail");clipped.setSingleLine(true);clipped.measure(View.MeasureSpec.makeMeasureSpec(30,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY));clipped.layout(0,0,30,300);boolean caught=false;try{inspect(clipped);}catch(AssertionError expected){caught=expected.getMessage().startsWith("text width:");}require(caught,"horizontal clipping detector");});
      Bitmap photo=Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888);photo.eraseColor(0xff718774);try(FileOutputStream out=new FileOutputStream(file)){photo.compress(Bitmap.CompressFormat.JPEG,90,out);}photo.recycle();
      ui(t,()->{a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);set(a,"tag",new TagStore.Tag("detail-fixture","空"));List<PhotoRepository.Photo> photos=(List<PhotoRepository.Photo>)get(a,"photos");List<String> ids=(List<String>)get(a,"photoIds");photos.clear();ids.clear();TagJudgments judgments=new TagJudgments();for(int i=0;i<40;i++){photos.add(new PhotoRepository.Photo(Uri.fromFile(file),"合成"+i+".jpg","",file.length(),i,0));ids.add("fixture-"+i);if(i<6)judgments=judgments.record("op-"+i,"detail-fixture","fixture-"+i,0,TagJudgments.Value.ASSIGNED);else if(i<8)judgments=judgments.record("op-"+i,"detail-fixture","fixture-"+i,0,TagJudgments.Value.EXCLUDED);}set(a,"judgments",judgments);set(a,"automatic",new HashMap<>());call(a,"renderTag");});idle(t,a);
      File dir=new File(a.getCacheDir(),"consistency-checks");dir.mkdirs();ConsistencyLayoutChecks.captureHardware(t,a,(View)get(a,"root"),dir,"tag-detail-"+width+"-"+a.getResources().getConfiguration().fontScale+"-normal");
      ui(t,()->inspect((View)get(a,"root")));
      ui(t,()->{View root=(View)get(a,"root");require(((List<?>)get(a,"browsePhotos")).size()==6,"attached filter");require(button(root,"似た写真にもタグを付ける").isEnabled(),"automatic entry");button(root,"該当しない 2").performClick();});idle(t,a);
      ui(t,()->{require(((List<?>)get(a,"browsePhotos")).size()==2,"excluded filter");button((View)get(a,"root"),"一括訂正").performClick();require((boolean)get(a,"selecting"),"bulk entry");button((View)get(a,"root"),"すべて選択").performClick();require(((Set<?>)get(a,"selectedPhotos")).size()==2,"select all");require(button((View)get(a,"root"),"タグ付きにする").isEnabled(),"batch action");set(a,"busy",true);a.onBackPressed();require((boolean)get(a,"selecting"),"busy guard");set(a,"busy",false);a.onBackPressed();require(!(boolean)get(a,"selecting")&&((Set<?>)get(a,"selectedPhotos")).isEmpty(),"selection Back");button((View)get(a,"root"),"写真を追加").performClick();});idle(t,a);
      ui(t,()->{require(!(boolean)get(a,"tagOverview")&&(boolean)get(a,"selecting"),"add-photo entry");require(((List<?>)get(a,"browsePhotos")).size()==40,"all filter");require(!button((View)get(a,"root"),"タグ付きにする").isEnabled(),"empty selection disabled");a.onBackPressed();require(!(boolean)get(a,"selecting"),"add selection Back");});
      ui(t,()->{ScrollingGrid grid=(ScrollingGrid)get(a,"photoGrid");grid.restoreCell(0,-12,false);});idle(t,a);
      ui(t,()->{ScrollingGrid grid=(ScrollingGrid)get(a,"photoGrid");require(grid.getFirstVisiblePosition()==grid.getHeaderViewsCount(),"first photo row setup");int top=grid.getChildAt(0).getTop();Object filter=get(a,"browseFilter");try{Method m=TagActivity.class.getDeclaredMethod("renderPhotos",filter.getClass(),int.class,int.class,boolean.class);m.setAccessible(true);m.invoke(a,filter,0,top,false);}catch(Exception e){throw new RuntimeException(e);}});idle(t,a);
      ui(t,()->{ScrollingGrid grid=(ScrollingGrid)get(a,"photoGrid");require(grid.getFirstVisiblePosition()==grid.getHeaderViewsCount()&&grid.getChildAt(0).getTop()==-12,"first photo row restore");});
      ui(t,()->{set(a,"tag",new TagStore.Tag("detail-fixture","旅先で見つけた夕暮れの空と長い名前のタグ"));call(a,"renderTag");});idle(t,a);
      ui(t,()->inspect((View)get(a,"root")));
      ConsistencyLayoutChecks.captureHardware(t,a,(View)get(a,"root"),dir,"tag-detail-"+width+"-"+a.getResources().getConfiguration().fontScale+"-long");
      return "PASS actual TagActivity with synthetic photos and in-memory judgments: attached/excluded filters, bulk/select-all, busy Back guard, selection cancellation, add-photo entry, empty selection disabled; PixelCopy of synthetic root. No persistence/inference/automatic removal executed.\n";
    }finally{ui(t,()->{a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.finish();});file.delete();}
  }
}
