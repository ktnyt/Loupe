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
      if(layout!=null){
        if(layout.getHeight()>text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom())android.util.Log.e("LoupeFixtureGeometry","layout="+layout.getHeight()+" view="+text.getHeight()+" measured="+text.getMeasuredHeight()+" paddingTop="+text.getCompoundPaddingTop()+" paddingBottom="+text.getCompoundPaddingBottom()+" lines="+layout.getLineCount()+" textSize="+text.getTextSize()+" lineHeight="+text.getLineHeight());
        require(layout.getHeight()<=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom(),"text height: "+text.getText());
        for(int i=0;i<layout.getLineCount();i++){
          require(layout.getEllipsisCount(i)==0,"ellipsis: "+text.getText());
          int available=text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight();
          require(layout.getLineLeft(i)>=-1&&layout.getLineRight(i)<=available+1,"text width: "+text.getText());
        }}}
    if(v instanceof Button)require(v.getHeight()>=Math.round(48*v.getResources().getDisplayMetrics().density),"touch height");
    if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)inspect(((ViewGroup)v).getChildAt(i));
  }
  static void idle(Instrumentation t,TagActivity a)throws Exception{((ExecutorService)get(a,"worker")).submit(()->{}).get(10,TimeUnit.SECONDS);t.waitForIdleSync();if(width>0)ui(t,()->{View root=(View)get(a,"root");ViewGroup.LayoutParams params=root.getLayoutParams();params.width=Math.round(width*a.getResources().getDisplayMetrics().density);root.setLayoutParams(params);});t.waitForIdleSync();ConsistencyLayoutChecks.frame(t,(View)get(a,"root"));}
  static void photoSave(TagActivity a,boolean failed){try{Method m=TagActivity.class.getDeclaredMethod("refreshPhotoSave",boolean.class);m.setAccessible(true);m.invoke(a,failed);}catch(Exception e){throw new RuntimeException(e);}}
  static PhotoViewerUi viewer(TagActivity a){return (PhotoViewerUi)((ViewGroup)get(a,"root")).getChildAt(0);}
  static Dialog viewerActions(Instrumentation t,TagActivity a)throws Exception{
    PhotoViewerUi v=viewer(a);ui(t,v::openActions);t.waitForIdleSync();
    Field field=PhotoViewerUi.class.getDeclaredField("actionSheet");field.setAccessible(true);Dialog dialog=(Dialog)field.get(v);
    require(dialog!=null&&dialog.isShowing(),"viewer secondary sheet is showing");
    ConsistencyLayoutChecks.frame(t,dialog.getWindow().getDecorView());
    ui(t,()->visibleTagAction(a,dialog));return dialog;
  }
  static void visibleTagAction(TagActivity a,Dialog dialog){
    View surface=dialog.getWindow().findViewById(com.google.android.material.R.id.design_bottom_sheet);
    Button tag=viewer(a).actionButton("タグ");Rect visible=new Rect();
    require(dialog.isShowing()&&dialog.getWindow().getDecorView().hasWindowFocus()&&surface!=null&&surface.isShown(),"secondary sheet owns a visible native window");
    require(tag!=null&&tag.isShown()&&tag.getRootView()==dialog.getWindow().getDecorView(),"tag action is attached to active sheet");
    require(tag.getGlobalVisibleRect(visible)&&visible.width()==tag.getWidth()&&visible.height()==tag.getHeight(),"tag save state is fully visible");
  }
  static void closeViewerActions(Instrumentation t,Dialog dialog)throws Exception{
    ui(t,()->button(dialog.getWindow().getDecorView(),"閉じる").performClick());t.waitForIdleSync();
  }
  static void realViewerTagRetry(Instrumentation t,TagActivity a,File dir,String name)throws Exception{
    require(a.isolatedPhotos&&a.tagDatabaseName.startsWith("tag-detail-")&&!"tags.db".equals(a.tagDatabaseName),"real save test uses named isolated database");
    List<String> ids=(List<String>)get(a,"photoIds");int index=((List<Integer>)get(a,"browsePhotos")).get(0);
    PhotoRepository.Photo photo=((List<PhotoRepository.Photo>)get(a,"photos")).get(index);
    TagStore.Tag tag=(TagStore.Tag)get(a,"tag");Object originalJudgments=get(a,"judgments");String originalId=ids.get(index);
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);String photoId;TagJudgments seeded;
    try(TagStore store=new TagStore(a,a.tagDatabaseName)){
      store.getWritableDatabase().execSQL("INSERT OR IGNORE INTO tags(id,name) VALUES(?,?)",new Object[]{tag.id,tag.name});
      photoId=store.photoId(photo,true);seeded=store.record(UUID.randomUUID().toString(),tag.id,photoId,0,TagJudgments.Value.ASSIGNED);
      store.getWritableDatabase().execSQL("CREATE TRIGGER fixture_viewer_save_failure BEFORE INSERT ON history BEGIN SELECT RAISE(FAIL, 'fixture write failure'); END");
    }
    try{
      ui(t,()->{ids.set(index,photoId);set(a,"judgments",seeded);});Dialog dialog=viewerActions(t,a);
      ((ExecutorService)get(a,"worker")).execute(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
      require(entered.await(5,TimeUnit.SECONDS),"fixture worker gate entered");
      ui(t,()->{viewer(a).actionButton("タグ").performClick();require((boolean)get(a,"busy"),"actual tag click starts save");require(!viewer(a).actionButton("タグ").isEnabled()&&"保存中".contentEquals(viewer(a).actionButton("タグ").getStateDescription()),"actual save presents busy");visibleTagAction(a,dialog);});
      ConsistencyLayoutChecks.captureHardware(t,dialog.getWindow(),dialog.getWindow().getDecorView(),dir,name+"-actual-save-busy");
      release.countDown();idle(t,a);
      ui(t,()->{require(!(boolean)get(a,"busy")&&get(a,"judgments")==seeded,"failed store write retains judgment");require(viewer(a).actionButton("タグ").isEnabled()&&viewer(a).actionButton("タグ").getContentDescription().toString().contains("保存できません"),"actual store failure presents retry");visibleTagAction(a,dialog);inspect(dialog.getWindow().getDecorView());});
      ConsistencyLayoutChecks.captureHardware(t,dialog.getWindow(),dialog.getWindow().getDecorView(),dir,name+"-actual-save-failed");
      try(TagStore store=new TagStore(a,a.tagDatabaseName)){require(store.load().latest(tag.id,photoId).revision==1,"failed write did not persist judgment");store.getWritableDatabase().execSQL("DROP TRIGGER fixture_viewer_save_failure");}
      ui(t,()->viewer(a).actionButton("タグ").performClick());idle(t,a);
      try(TagStore store=new TagStore(a,a.tagDatabaseName)){TagJudgments.Entry saved=store.load().latest(tag.id,photoId);require(saved.revision==2&&saved.value==TagJudgments.Value.EXCLUDED,"retry persisted requested judgment exactly once");}
      ui(t,()->{require(!(boolean)get(a,"busy")&&(int)get(a,"photoPosition")==0,"retry preserves photo identity");require(!dialog.isShowing()&&viewer(a).actionButton("タグ").isEnabled(),"successful save replaces viewer and dismisses old sheet");});
      // A separate writer advances the judgment after the viewer loaded revision 2.
      try(TagStore store=new TagStore(a,a.tagDatabaseName)){store.record(UUID.randomUUID().toString(),tag.id,photoId,2,TagJudgments.Value.ASSIGNED);}
      Dialog staleSheet=viewerActions(t,a);ui(t,()->viewer(a).actionButton("タグ").performClick());idle(t,a);
      // Observe the replacement sheet without opening it: opening here would mask the bug.
      Field actionSheet=PhotoViewerUi.class.getDeclaredField("actionSheet");actionSheet.setAccessible(true);
      Dialog conflict=(Dialog)actionSheet.get(viewer(a));require(conflict!=null&&conflict.isShowing(),"optimistic conflict reopens the replacement action sheet");
      ConsistencyLayoutChecks.frame(t,conflict.getWindow().getDecorView());
      ui(t,()->{
        require(conflict!=staleSheet&&!staleSheet.isShowing(),"conflict uses the current viewer window");visibleTagAction(a,conflict);
        Button tagAction=viewer(a).actionButton("タグ");TagJudgments latest=(TagJudgments)get(a,"judgments");
        require(latest.latest(tag.id,photoId).revision==3&&latest.value(tag.id,photoId)==TagJudgments.Value.ASSIGNED,"conflict presents latest committed judgment");
        require(tagAction.isEnabled()&&tagAction.getText().toString().equals("タグを外す")&&tagAction.getStateDescription().toString().contains("変更競合")&&tagAction.getContentDescription().toString().contains("最新の判断"),"visible conflict names latest state and retry");inspect(conflict.getWindow().getDecorView());
      });
      try(TagStore store=new TagStore(a,a.tagDatabaseName)){require(store.load().latest(tag.id,photoId).revision==3,"conflicting request did not overwrite newer judgment");}
      ConsistencyLayoutChecks.captureHardware(t,conflict.getWindow(),conflict.getWindow().getDecorView(),dir,name+"-actual-save-conflict");
      ui(t,()->viewer(a).actionButton("タグ").performClick());idle(t,a);
      try(TagStore store=new TagStore(a,a.tagDatabaseName)){TagJudgments.Entry saved=store.load().latest(tag.id,photoId);require(saved.revision==4&&saved.value==TagJudgments.Value.EXCLUDED,"visible conflict retry commits against latest revision");}
      ui(t,()->require(!conflict.isShowing()&&!(boolean)get(a,"busy")&&(int)get(a,"photoPosition")==0,"conflict retry returns to same photo"));
    }finally{
      release.countDown();((ExecutorService)get(a,"worker")).submit(()->{}).get(15,TimeUnit.SECONDS);t.waitForIdleSync();
      try(TagStore store=new TagStore(a,a.tagDatabaseName)){store.getWritableDatabase().execSQL("DROP TRIGGER IF EXISTS fixture_viewer_save_failure");}
      ui(t,()->{ids.set(index,originalId);set(a,"judgments",originalJudgments);set(a,"busy",false);try{Method show=TagActivity.class.getDeclaredMethod("showAssignedPhoto",int.class);show.setAccessible(true);show.invoke(a,0);}catch(Exception error){throw new RuntimeException(error);}});idle(t,a);
    }
  }
  static void viewerCapture(Instrumentation t,TagActivity a,File dir,String state)throws Exception{
    int[] position=new int[2];ui(t,()->{ScrollingGrid grid=(ScrollingGrid)get(a,"photoGrid");position[0]=grid.getFirstVisiblePosition();position[1]=grid.getChildAt(0)==null?0:grid.getChildAt(0).getTop();});
    ui(t,()->{try{Method m=TagActivity.class.getDeclaredMethod("showAssignedPhoto",int.class);m.setAccessible(true);m.invoke(a,0);}catch(Exception e){throw new RuntimeException(e);}});idle(t,a);
    String name="tag-viewer-"+width+"-"+a.getResources().getConfiguration().fontScale+"-"+state;
    ui(t,()->{PhotoViewerUi v=viewer(a);require((int)get(a,"browseMode")==2&&v.actionCount()==5,"five tag viewer actions across direct and secondary controls");require(((android.graphics.drawable.ColorDrawable)v.getBackground()).getColor()==Color.BLACK,"black tag viewer");require(v.actionButton("共有")==null&&v.actionButton("情報")==null,"tag viewer has no share/info");require(v.actionButton("タグ")!=null&&v.actionButton("タグ").getText().toString().equals("assigned".equals(state)?"タグを外す":"タグを付ける"),"current tag action");require(!v.actionButton("前の写真").isEnabled()&&v.actionButton("次の写真").isEnabled(),"first endpoint");inspect(v);});
    ConsistencyLayoutChecks.captureHardware(t,a,(View)get(a,"root"),dir,name);
    Dialog actions=viewerActions(t,a);ConsistencyLayoutChecks.captureHardware(t,actions.getWindow(),actions.getWindow().getDecorView(),dir,name+"-actions");closeViewerActions(t,actions);
    ViewerScreenChecks.pinch(t,viewer(a).image);ui(t,()->{Button zoom=viewer(a).actionButton("拡大");android.view.accessibility.AccessibilityNodeInfo node=zoom.createAccessibilityNodeInfo();try{require(viewer(a).image.isZoomed()&&"全体を表示".contentEquals(zoom.getContentDescription())&&node.isChecked(),"real tag pinch updates photo and AX zoom state");}finally{node.recycle();}});viewerActions(t,a);ui(t,()->viewer(a).actionButton("拡大").performClick());
    ViewerScreenChecks.swipe(t,viewer(a).image,-1);idle(t,a);
    ui(t,()->require((int)get(a,"photoPosition")==1,"tag swipe next"));viewerActions(t,a);ui(t,()->viewer(a).actionButton("前の写真").performClick());idle(t,a);ui(t,()->require((int)get(a,"photoPosition")==0,"tag previous button"));
    Object judgments=get(a,"judgments");Dialog saveSheet=viewerActions(t,a);
    ui(t,()->{set(a,"busy",true);photoSave(a,false);require(!viewer(a).actionButton("次の写真").isEnabled()&&!((Button)get(a,"photoTag")).isEnabled(),"save disables tag and paging");a.onBackPressed();require((int)get(a,"browseMode")==2,"save blocks Back");visibleTagAction(a,saveSheet);});idle(t,a);
    ConsistencyLayoutChecks.captureHardware(t,saveSheet.getWindow(),saveSheet.getWindow().getDecorView(),dir,name+"-busy");
    ui(t,()->{set(a,"busy",false);photoSave(a,true);require(((Button)get(a,"photoTag")).isEnabled()&&((Button)get(a,"photoTag")).getContentDescription().toString().contains("保存できません"),"save failure retry affordance");require(get(a,"judgments")==judgments&&(int)get(a,"photoPosition")==0,"save failure retains judgment and photo");visibleTagAction(a,saveSheet);inspect(saveSheet.getWindow().getDecorView());});idle(t,a);
    ConsistencyLayoutChecks.captureHardware(t,saveSheet.getWindow(),saveSheet.getWindow().getDecorView(),dir,name+"-failed");
    ui(t,()->photoSave(a,false));closeViewerActions(t,saveSheet);
    if("assigned".equals(state)){
      realViewerTagRetry(t,a,dir,name);
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
    String uiDb="tag-detail-ui-"+UUID.randomUUID()+".db",featureDb="tag-detail-features-"+UUID.randomUUID()+".db";TagActivity a=(TagActivity)t.startActivitySync(TagFixtureIsolation.intent(t.getTargetContext(),uiDb,featureDb));File file=new File(a.getCacheDir(),"tag-detail-synthetic.jpg");
    try{idle(t,a);TagFixtureIsolation.assertGuard(a);
      ui(t,()->{TextView clipped=new TextView(a);clipped.setText("Horizontal clipping must fail");clipped.setSingleLine(true);clipped.measure(View.MeasureSpec.makeMeasureSpec(30,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY));clipped.layout(0,0,30,300);boolean caught=false;try{inspect(clipped);}catch(AssertionError expected){caught=expected.getMessage().startsWith("text width:");}require(caught,"horizontal clipping detector");});
      Bitmap photo=Bitmap.createBitmap(240,160,Bitmap.Config.ARGB_8888);require(photo.getWidth()==240&&photo.getHeight()==160,"3:2 fixture dimensions");photo.eraseColor(0xff718774);try(FileOutputStream out=new FileOutputStream(file)){photo.compress(Bitmap.CompressFormat.JPEG,90,out);}photo.recycle();
      isolatedSave(t,file);
      ui(t,()->{a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);set(a,"tag",new TagStore.Tag("detail-fixture","空"));List<PhotoRepository.Photo> photos=(List<PhotoRepository.Photo>)get(a,"photos");List<String> ids=(List<String>)get(a,"photoIds");photos.clear();ids.clear();TagJudgments judgments=new TagJudgments();for(int i=0;i<40;i++){photos.add(new PhotoRepository.Photo(Uri.fromFile(file),"合成"+i+".jpg","",file.length(),1792298040L,1792298040000L));ids.add("fixture-"+i);if(i<6)judgments=judgments.record("op-"+i,"detail-fixture","fixture-"+i,0,TagJudgments.Value.ASSIGNED);else if(i<8)judgments=judgments.record("op-"+i,"detail-fixture","fixture-"+i,0,TagJudgments.Value.EXCLUDED);}set(a,"judgments",judgments);set(a,"automatic",new HashMap<>());call(a,"renderTag");});idle(t,a);
      File dir=new File(a.getCacheDir(),"consistency-checks");dir.mkdirs();ConsistencyLayoutChecks.captureHardware(t,a,(View)get(a,"root"),dir,"tag-detail-"+width+"-"+a.getResources().getConfiguration().fontScale+"-normal");
      ui(t,()->{
        inspect((View)get(a,"root"));
        call(a,"refreshConfidenceCounts");
        require(((Button)get(a,"attachedState")).getText().toString().equals("タグ付き 6"),"native state label survives count refresh");
        ViewGroup states=(ViewGroup)((View)get(a,"attachedState")).getParent();
        for(int i=0;i<states.getChildCount();i++){
          View state=states.getChildAt(i);
          require(state.getTop()>=0&&state.getBottom()<=states.getHeight(),"tag filter shape fits its parent");
          require(state.getHeight()==states.getChildAt(0).getHeight(),"tag filter target heights agree");
        }
      });
      viewerCapture(t,a,dir,"assigned");
      ui(t,()->{View root=(View)get(a,"root");require(((List<?>)get(a,"browsePhotos")).size()==6,"attached filter");require(button(root,"写真を解析").isEnabled(),"automatic entry");button(root,"該当しない 2").performClick();});idle(t,a);
      viewerCapture(t,a,dir,"excluded");
      ui(t,()->{require(((List<?>)get(a,"browsePhotos")).size()==2,"excluded filter");button((View)get(a,"root"),"一括訂正").performClick();require((boolean)get(a,"selecting"),"bulk entry");button((View)get(a,"root"),"すべて選択").performClick();require(((Set<?>)get(a,"selectedPhotos")).size()==2,"select all");require(button((View)get(a,"root"),"タグ付きにする").isEnabled(),"batch action");set(a,"busy",true);a.onBackPressed();require((boolean)get(a,"selecting"),"busy guard");set(a,"busy",false);a.onBackPressed();require(!(boolean)get(a,"selecting")&&((Set<?>)get(a,"selectedPhotos")).isEmpty(),"selection Back");button((View)get(a,"root"),"写真を追加").performClick();});idle(t,a);
      ui(t,()->{require(!(boolean)get(a,"tagOverview")&&(boolean)get(a,"selecting"),"add-photo entry");require(((List<?>)get(a,"browsePhotos")).size()==40,"all filter");require(!button((View)get(a,"root"),"タグ付きにする").isEnabled(),"empty selection disabled");a.onBackPressed();require(!(boolean)get(a,"selecting"),"add selection Back");});
      ui(t,()->{ScrollingGrid grid=(ScrollingGrid)get(a,"photoGrid");grid.restoreCell(0,-12,false);});idle(t,a);
      ui(t,()->{ScrollingGrid grid=(ScrollingGrid)get(a,"photoGrid");require(grid.getFirstVisiblePosition()==grid.getHeaderViewsCount(),"first photo row setup");int top=grid.getChildAt(0).getTop();Object filter=get(a,"browseFilter");try{Method m=TagActivity.class.getDeclaredMethod("renderPhotos",filter.getClass(),int.class,int.class,boolean.class);m.setAccessible(true);m.invoke(a,filter,0,top,false);}catch(Exception e){throw new RuntimeException(e);}});idle(t,a);
      ui(t,()->{ScrollingGrid grid=(ScrollingGrid)get(a,"photoGrid");require(grid.getFirstVisiblePosition()==grid.getHeaderViewsCount()&&grid.getChildAt(0).getTop()==-12,"first photo row restore");});
      ui(t,()->{set(a,"tag",new TagStore.Tag("detail-fixture","旅先で見つけた夕暮れの空と長い名前のタグ"));call(a,"renderTag");});idle(t,a);
      ui(t,()->inspect((View)get(a,"root")));
      ConsistencyLayoutChecks.captureHardware(t,a,(View)get(a,"root"),dir,"tag-detail-"+width+"-"+a.getResources().getConfiguration().fontScale+"-long");
      return "PASS actual TagActivity with synthetic photos and in-memory judgments: attached/excluded filters, bulk/select-all, busy Back guard, selection cancellation, add-photo entry, empty selection disabled, tag viewer direct/secondary action layout/pinch/swipe/paging/Back/grid position, actual missing-image retry, visible sheet busy/failure presentation and injected isolated-store write failure/retry and visible optimistic-conflict/latest-state retry; PixelCopy of synthetic root. No writes to the user tag database, inference or automatic removal. Separate disposable database exercised save, unavailable automatic read, idempotency, conflict and retry.\n";
    }finally{ui(t,()->{a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.finish();});TagFixtureIsolation.cleanup(a);file.delete();t.getTargetContext().deleteDatabase(uiDb);t.getTargetContext().deleteDatabase(featureDb);}
  }
}
