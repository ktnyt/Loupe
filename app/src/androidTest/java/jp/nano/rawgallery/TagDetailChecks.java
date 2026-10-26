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

/** Generated cache images, in-memory UI judgments and a disposable DB; never writes the user tag DB. */
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
  static void photoSave(TagActivity a,boolean failed){try{Method m=TagActivity.class.getDeclaredMethod("refreshPhotoSave",boolean.class);m.setAccessible(true);m.invoke(a,failed);}catch(Exception e){throw new RuntimeException(e);}}
  static PhotoViewerUi viewer(TagActivity a){return (PhotoViewerUi)((ViewGroup)get(a,"root")).getChildAt(0);}
  static void viewerCapture(Instrumentation t,TagActivity a,File dir,String state)throws Exception{
    int[] position=new int[2];ui(t,()->{ScrollingGrid grid=(ScrollingGrid)get(a,"photoGrid");position[0]=grid.getFirstVisiblePosition();position[1]=grid.getChildAt(0)==null?0:grid.getChildAt(0).getTop();});
    ui(t,()->button((View)get(a,"root"),"1枚ずつ確認する").performClick());idle(t,a);
    String name="tag-viewer-"+width+"-"+a.getResources().getConfiguration().fontScale+"-"+state;
    ui(t,()->{PhotoViewerUi v=viewer(a);require((int)get(a,"browseMode")==2&&v.dock.getChildCount()==4,"four-action tag viewer");require(((android.graphics.drawable.ColorDrawable)v.getBackground()).getColor()==Color.BLACK,"black tag viewer");require(button(v,"共有")==null&&button(v,"情報")==null,"tag viewer has no share/info");require(button(v,"assigned".equals(state)?"タグを外す":"タグを付ける")!=null,"current tag action");require(!button(v,"前の写真").isEnabled()&&button(v,"次の写真").isEnabled(),"first endpoint");inspect(v);});
    ConsistencyLayoutChecks.captureHardware(t,a,(View)get(a,"root"),dir,name);
    ViewerScreenChecks.pinch(t,viewer(a).image);ui(t,()->{require(viewer(a).image.isZoomed(),"tag pinch");button(viewer(a),"全体を表示").performClick();});ViewerScreenChecks.swipe(t,viewer(a).image,-1);idle(t,a);
    ui(t,()->{require((int)get(a,"photoPosition")==1,"tag swipe next");button(viewer(a),"前の写真").performClick();});idle(t,a);ui(t,()->require((int)get(a,"photoPosition")==0,"tag previous button"));
    Object judgments=get(a,"judgments");
    ui(t,()->{set(a,"busy",true);photoSave(a,false);require(!button(viewer(a),"次の写真").isEnabled()&&!((Button)get(a,"photoTag")).isEnabled(),"save disables tag and paging");a.onBackPressed();require((int)get(a,"browseMode")==2,"save blocks Back");});idle(t,a);
    ConsistencyLayoutChecks.captureHardware(t,a,(View)get(a,"root"),dir,name+"-busy");
    ui(t,()->{set(a,"busy",false);photoSave(a,true);require(((Button)get(a,"photoTag")).isEnabled()&&((Button)get(a,"photoTag")).getContentDescription().toString().contains("保存できません"),"save failure retry affordance");require(get(a,"judgments")==judgments&&(int)get(a,"photoPosition")==0,"save failure retains judgment and photo");inspect(viewer(a));});idle(t,a);
    ConsistencyLayoutChecks.captureHardware(t,a,(View)get(a,"root"),dir,name+"-failed");
    ui(t,()->photoSave(a,false));
    if("assigned".equals(state)){
      List<PhotoRepository.Photo> photos=(List<PhotoRepository.Photo>)get(a,"photos");int index=((List<Integer>)get(a,"browsePhotos")).get(0);PhotoRepository.Photo original=photos.get(index);
      try{
        ui(t,()->{photos.set(index,new PhotoRepository.Photo(Uri.fromFile(new File(a.getCacheDir(),"tag-viewer-intentionally-missing.jpg")),"合成欠損.jpg","",0,0,0));try{Method method=TagActivity.class.getDeclaredMethod("showAssignedPhoto",int.class);method.setAccessible(true);method.invoke(a,0);}catch(Exception e){throw new RuntimeException(e);}});idle(t,a);
        ui(t,()->{require(button(viewer(a),"再試行")!=null&&viewer(a).image.photoBounds()==null,"real missing file retry");inspect(viewer(a));});ConsistencyLayoutChecks.captureHardware(t,a,(View)get(a,"root"),dir,name+"-image-failed");
        ui(t,()->{photos.set(index,original);button(viewer(a),"再試行").performClick();});idle(t,a);ui(t,()->require(viewer(a).image.photoBounds()!=null,"real image retry recovers"));
      }finally{ui(t,()->photos.set(index,original));}
    }
    ui(t,a::onBackPressed);idle(t,a);ui(t,()->{require((int)get(a,"browseMode")==1,"tag viewer Back");ScrollingGrid grid=(ScrollingGrid)get(a,"photoGrid");require(grid.getFirstVisiblePosition()==position[0]&&(grid.getChildAt(0)==null?0:grid.getChildAt(0).getTop())==position[1],"tag viewer restores grid position");});
  }
  static void isolatedSave(Instrumentation t,File image)throws Exception{
    android.content.Context context=t.getTargetContext();String database="tag-viewer-save-fixture-"+UUID.randomUUID()+".db";
    try(TagStore store=new TagStore(context,database)){
      TagStore.Tag tag=store.create("隔離した保存検査");PhotoRepository.Photo photo=new PhotoRepository.Photo(Uri.fromFile(image),"合成保存.jpg","",image.length(),0,0);
      store.getWritableDatabase().execSQL("DROP TABLE automatic_tags");
      boolean readFailed=false;try{store.automatic(tag.id);}catch(android.database.sqlite.SQLiteException expected){readFailed=true;}require(readFailed,"isolated automatic read failure injected");
      String operation=UUID.randomUUID().toString();TagActivity.PhotoTagSave saved=TagActivity.savePhotoTag(store,photo,operation,tag.id,0,TagJudgments.Value.ASSIGNED);
      require(!saved.conflict&&saved.judgments.value(tag.id,saved.photoId)==TagJudgments.Value.ASSIGNED&&saved.judgments.latest(tag.id,saved.photoId).revision==1,"save succeeds without optional automatic read");
      TagActivity.PhotoTagSave repeated=TagActivity.savePhotoTag(store,photo,operation,tag.id,0,TagJudgments.Value.ASSIGNED);require(!repeated.conflict&&repeated.judgments.history.size()==1,"same save is idempotent");
      TagActivity.PhotoTagSave conflict=TagActivity.savePhotoTag(store,photo,UUID.randomUUID().toString(),tag.id,0,TagJudgments.Value.EXCLUDED);require(conflict.conflict&&conflict.judgments.value(tag.id,saved.photoId)==TagJudgments.Value.ASSIGNED&&store.load().history.size()==1,"stale edit returns current state without overwrite");
      TagActivity.PhotoTagSave retried=TagActivity.savePhotoTag(store,photo,UUID.randomUUID().toString(),tag.id,1,TagJudgments.Value.EXCLUDED);require(!retried.conflict&&retried.judgments.value(tag.id,saved.photoId)==TagJudgments.Value.EXCLUDED&&retried.judgments.latest(tag.id,saved.photoId).revision==2,"updated revision retry succeeds");
    }finally{context.deleteDatabase(database);}
  }
  static String run(Instrumentation t,int requestedWidth)throws Exception{
    width=requestedWidth;
    TagActivity a=(TagActivity)t.startActivitySync(new Intent(t.getTargetContext(),TagActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));File file=new File(a.getCacheDir(),"tag-detail-synthetic.jpg");
    try{idle(t,a);
      ui(t,()->{TextView clipped=new TextView(a);clipped.setText("Horizontal clipping must fail");clipped.setSingleLine(true);clipped.measure(View.MeasureSpec.makeMeasureSpec(30,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY));clipped.layout(0,0,30,300);boolean caught=false;try{inspect(clipped);}catch(AssertionError expected){caught=expected.getMessage().startsWith("text width:");}require(caught,"horizontal clipping detector");});
      Bitmap photo=Bitmap.createBitmap(240,160,Bitmap.Config.ARGB_8888);require(photo.getWidth()==240&&photo.getHeight()==160,"3:2 fixture dimensions");photo.eraseColor(0xff718774);try(FileOutputStream out=new FileOutputStream(file)){photo.compress(Bitmap.CompressFormat.JPEG,90,out);}photo.recycle();
      isolatedSave(t,file);
      ui(t,()->{a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);set(a,"tag",new TagStore.Tag("detail-fixture","空"));List<PhotoRepository.Photo> photos=(List<PhotoRepository.Photo>)get(a,"photos");List<String> ids=(List<String>)get(a,"photoIds");photos.clear();ids.clear();TagJudgments judgments=new TagJudgments();for(int i=0;i<40;i++){photos.add(new PhotoRepository.Photo(Uri.fromFile(file),"合成"+i+".jpg","",file.length(),1792298040L,1792298040000L));ids.add("fixture-"+i);if(i<6)judgments=judgments.record("op-"+i,"detail-fixture","fixture-"+i,0,TagJudgments.Value.ASSIGNED);else if(i<8)judgments=judgments.record("op-"+i,"detail-fixture","fixture-"+i,0,TagJudgments.Value.EXCLUDED);}set(a,"judgments",judgments);set(a,"automatic",new HashMap<>());call(a,"renderTag");});idle(t,a);
      File dir=new File(a.getCacheDir(),"consistency-checks");dir.mkdirs();ConsistencyLayoutChecks.captureHardware(t,a,(View)get(a,"root"),dir,"tag-detail-"+width+"-"+a.getResources().getConfiguration().fontScale+"-normal");
      ui(t,()->inspect((View)get(a,"root")));
      viewerCapture(t,a,dir,"assigned");
      ui(t,()->{View root=(View)get(a,"root");require(((List<?>)get(a,"browsePhotos")).size()==6,"attached filter");require(button(root,"似た写真にもタグを付ける").isEnabled(),"automatic entry");button(root,"該当しない 2").performClick();});idle(t,a);
      viewerCapture(t,a,dir,"excluded");
      ui(t,()->{require(((List<?>)get(a,"browsePhotos")).size()==2,"excluded filter");button((View)get(a,"root"),"一括訂正").performClick();require((boolean)get(a,"selecting"),"bulk entry");button((View)get(a,"root"),"すべて選択").performClick();require(((Set<?>)get(a,"selectedPhotos")).size()==2,"select all");require(button((View)get(a,"root"),"タグ付きにする").isEnabled(),"batch action");set(a,"busy",true);a.onBackPressed();require((boolean)get(a,"selecting"),"busy guard");set(a,"busy",false);a.onBackPressed();require(!(boolean)get(a,"selecting")&&((Set<?>)get(a,"selectedPhotos")).isEmpty(),"selection Back");button((View)get(a,"root"),"写真を追加").performClick();});idle(t,a);
      ui(t,()->{require(!(boolean)get(a,"tagOverview")&&(boolean)get(a,"selecting"),"add-photo entry");require(((List<?>)get(a,"browsePhotos")).size()==40,"all filter");require(!button((View)get(a,"root"),"タグ付きにする").isEnabled(),"empty selection disabled");a.onBackPressed();require(!(boolean)get(a,"selecting"),"add selection Back");});
      ui(t,()->{ScrollingGrid grid=(ScrollingGrid)get(a,"photoGrid");grid.restoreCell(0,-12,false);});idle(t,a);
      ui(t,()->{ScrollingGrid grid=(ScrollingGrid)get(a,"photoGrid");require(grid.getFirstVisiblePosition()==grid.getHeaderViewsCount(),"first photo row setup");int top=grid.getChildAt(0).getTop();Object filter=get(a,"browseFilter");try{Method m=TagActivity.class.getDeclaredMethod("renderPhotos",filter.getClass(),int.class,int.class,boolean.class);m.setAccessible(true);m.invoke(a,filter,0,top,false);}catch(Exception e){throw new RuntimeException(e);}});idle(t,a);
      ui(t,()->{ScrollingGrid grid=(ScrollingGrid)get(a,"photoGrid");require(grid.getFirstVisiblePosition()==grid.getHeaderViewsCount()&&grid.getChildAt(0).getTop()==-12,"first photo row restore");});
      ui(t,()->{set(a,"tag",new TagStore.Tag("detail-fixture","旅先で見つけた夕暮れの空と長い名前のタグ"));call(a,"renderTag");});idle(t,a);
      ui(t,()->inspect((View)get(a,"root")));
      ConsistencyLayoutChecks.captureHardware(t,a,(View)get(a,"root"),dir,"tag-detail-"+width+"-"+a.getResources().getConfiguration().fontScale+"-long");
      return "PASS actual TagActivity with synthetic photos and in-memory judgments: attached/excluded filters, bulk/select-all, busy Back guard, selection cancellation, add-photo entry, empty selection disabled, tag viewer four-action layout/pinch/swipe/paging/Back/grid position, actual missing-image retry and busy/failure presentation fixtures; PixelCopy of synthetic root. No writes to the user tag database, inference or automatic removal. Separate disposable database exercised save, unavailable automatic read, idempotency, conflict and retry.\n";
    }finally{ui(t,()->{a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.finish();});file.delete();}
  }
}
