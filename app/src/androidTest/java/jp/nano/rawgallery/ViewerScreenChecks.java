package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.widget.Button;
import android.widget.FrameLayout;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual photo viewer with generated local images; no send or ranking/tag save. */
final class ViewerScreenChecks {
  static ZoomImageView image(View root){if(root instanceof ZoomImageView)return (ZoomImageView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){ZoomImageView found=image(((ViewGroup)root).getChildAt(i));if(found!=null)return found;}return null;}
  static void open(Instrumentation t,MainActivity a,int index)throws Exception{TagDetailChecks.ui(t,()->{try{java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("showPhoto",int.class);m.setAccessible(true);m.invoke(a,index);}catch(Exception e){throw new RuntimeException(e);}});((Future<?>)RankingResultChecks.get(a,"viewing")).get(30,TimeUnit.SECONDS);t.waitForIdleSync();ConsistencyLayoutChecks.frame(t,(View)RankingResultChecks.get(a,"root"));}
  static void openFromRanking(Instrumentation t,MainActivity a,int index)throws Exception{TagDetailChecks.ui(t,()->{try{java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("showPhoto",int.class,boolean.class);m.setAccessible(true);m.invoke(a,index,true);}catch(Exception e){throw new RuntimeException(e);}});((Future<?>)RankingResultChecks.get(a,"viewing")).get(30,TimeUnit.SECONDS);t.waitForIdleSync();ConsistencyLayoutChecks.frame(t,(View)RankingResultChecks.get(a,"root"));}
  static PhotoViewerUi viewer(MainActivity a){return (PhotoViewerUi)((android.widget.LinearLayout)RankingResultChecks.get(a,"root")).getChildAt(0);}
  static StatusView status(View v){if(v instanceof StatusView)return (StatusView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){StatusView found=status(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;}
  static void toolbar(PhotoViewerUi v,int expected,boolean legacyNarrow){
    TagDetailChecks.require(v.actionCount()==expected,"all viewer operations remain reachable: "+v.actionCount());
    TagDetailChecks.require(v.dock.getChildCount()<=3,"only direct edit/AI/share in primary dock");
    int right=0;
    for(int i=0;i<v.dock.getChildCount();i++){
      Button b=(Button)v.dock.getChildAt(i);
      TagDetailChecks.require(b.getWidth()>=GalleryUi.dp(v.getContext(),48)&&b.getHeight()>=GalleryUi.dp(v.getContext(),48),"viewer direct action touch target");
      TagDetailChecks.require(b.getLeft()>=right,"viewer direct actions do not overlap");right=b.getRight();
    }
    TagDetailChecks.require(v.stage.getBottom()<=v.footer.getTop(),"photo and action group do not overlap");
    TagDetailChecks.require(v.actionButton("拡大")!=null&&v.actionButton("情報")!=null,"secondary operations retained");
  }
  static void editBeforeShare(PhotoViewerUi v){
    int edit=-1,share=-1;for(int i=0;i<v.dock.getChildCount();i++){String label=((Button)v.dock.getChildAt(i)).getText().toString();if(label.equals("手動"))edit=i;if(label.equals("共有"))share=i;}
    TagDetailChecks.require(edit>=0&&share>edit,"edit action precedes share in actual viewer dock");
  }
  static void shot(Instrumentation t,MainActivity a,File dir,String name)throws Exception{t.waitForIdleSync();Thread.sleep(100);ConsistencyLayoutChecks.captureHardware(t,a,(View)RankingResultChecks.get(a,"root"),dir,name);}
  static void clickSecondary(Instrumentation t,MainActivity a,String icon)throws Exception{
    PhotoViewerUi current=viewer(a);TagDetailChecks.ui(t,current::openActions);t.waitForIdleSync();
    View menu=ShareScreenChecks.modal(t,a);ConsistencyLayoutChecks.frame(t,menu);
    TagDetailChecks.ui(t,()->{Button action=current.actionButton(icon);Rect visible=new Rect();TagDetailChecks.require(action!=null&&action.isShown()&&action.getRootView()==menu&&action.getGlobalVisibleRect(visible)&&visible.width()==action.getWidth()&&visible.height()==action.getHeight(),"secondary action is visible in focused sheet: "+icon);action.performClick();});t.waitForIdleSync();
  }
  static void doubleTap(Instrumentation t,View image)throws Exception{int[] p=new int[2];TagDetailChecks.ui(t,()->{image.getLocationOnScreen(p);p[0]+=image.getWidth()/2;p[1]+=image.getHeight()/2;});for(int i=0;i<2;i++){long down=android.os.SystemClock.uptimeMillis();MotionEvent press=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,p[0],p[1],0),release=MotionEvent.obtain(down,down+30,MotionEvent.ACTION_UP,p[0],p[1],0);try{t.sendPointerSync(press);t.sendPointerSync(release);}finally{press.recycle();release.recycle();}Thread.sleep(70);}t.waitForIdleSync();}
  static void pinch(Instrumentation t,View view)throws Exception{
    // A distinct gesture must not become the quick-scale continuation of a double tap.
    Thread.sleep(ViewConfiguration.getDoubleTapTimeout()+40);t.waitForIdleSync();
    int[] at=new int[2];float[] center=new float[2],span=new float[2];
    TagDetailChecks.ui(t,()->{
      Rect visible=new Rect();TagDetailChecks.require(view.hasWindowFocus()&&view.isShown()&&view.getLocalVisibleRect(visible)&&visible.equals(new Rect(0,0,view.getWidth(),view.getHeight())),"pinch targets focused, fully visible photo surface");
      if(view instanceof ZoomImageView)TagDetailChecks.require(((ZoomImageView)view).photoBounds()!=null,"pinch has a loaded photograph");
      ViewConfiguration config=ViewConfiguration.get(view.getContext());int minimum=config.getScaledMinimumScalingSpan(),slop=config.getScaledTouchSlop();
      float inset=GalleryUi.dp(view.getContext(),16),available=view.getWidth()-2*inset;
      span[1]=available*.9f;span[0]=Math.max(minimum+2f*slop,span[1]*.35f);
      TagDetailChecks.require(span[1]>span[0]+4f*slop,"photo surface has room for scaling beyond native minSpan: min="+minimum+" width="+view.getWidth());
      view.getLocationOnScreen(at);center[0]=at[0]+view.getWidth()/2f;center[1]=at[1]+view.getHeight()/2f;
      // Visible bounds are view-local; add the screen origin only when injecting events.
      float localX=view.getWidth()/2f,localY=view.getHeight()/2f;
      TagDetailChecks.require(localX-span[1]/2>=visible.left&&localX+span[1]/2<visible.right&&localY>=visible.top&&localY<visible.bottom,"all pinch endpoints stay inside visible photo surface");
      android.util.Log.i("LoupePinchFixture","widthPx="+view.getWidth()+" heightPx="+view.getHeight()+" minSpanPx="+minimum+" touchSlopPx="+slop+" startSpanPx="+span[0]+" endSpanPx="+span[1]+" screenOrigin="+at[0]+","+at[1]+" focus="+view.hasWindowFocus());
    });
    MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[2];MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[2];
    for(int i=0;i<2;i++){props[i]=new MotionEvent.PointerProperties();props[i].id=i;props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coords[i]=new MotionEvent.PointerCoords();coords[i].pressure=1;coords[i].size=1;coords[i].y=center[1];coords[i].x=center[0]+(i==0?-1:1)*span[0]/2;}
    long down=android.os.SystemClock.uptimeMillis();touch(t,down,MotionEvent.ACTION_DOWN,1,props,coords);touch(t,down,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,props,coords);
    // Continue well past onScaleBegin: crossing minSpan on only the last move does not zoom.
    for(int i=1;i<=16;i++){Thread.sleep(16);float distance=span[0]+(span[1]-span[0])*i/16;coords[0].x=center[0]-distance/2;coords[1].x=center[0]+distance/2;touch(t,down,MotionEvent.ACTION_MOVE,2,props,coords);}
    touch(t,down,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,props,coords);touch(t,down,MotionEvent.ACTION_UP,1,props,coords);t.waitForIdleSync();
    TagDetailChecks.ui(t,()->android.util.Log.i("LoupePinchFixture","completed zoomed="+(view instanceof ZoomImageView&&((ZoomImageView)view).isZoomed())+" attached="+view.isAttachedToWindow()+" focus="+view.hasWindowFocus()));
  }
  static void touch(Instrumentation t,long down,int action,int count,MotionEvent.PointerProperties[] props,MotionEvent.PointerCoords[] coords){MotionEvent event=MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,count,props,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);try{t.sendPointerSync(event);}finally{event.recycle();}}
  static void swipe(Instrumentation t,View view,int direction)throws Exception{
    TagDetailChecks.require(direction==-1||direction==1,"swipe direction");
    // A dismissed native sheet returns input focus asynchronously; do not fling into its exit.
    t.waitForIdleSync();Thread.sleep(ViewConfiguration.getDoubleTapTimeout()+40);
    long deadline=android.os.SystemClock.uptimeMillis()+5000;long[] stableSince={0};boolean[] ready={false};Rect previous=new Rect();
    do{
      TagDetailChecks.ui(t,()->{
        Rect visible=new Rect();boolean active=view.isAttachedToWindow()&&view.hasWindowFocus()&&view.isShown()&&!view.isLayoutRequested()&&view.getLocalVisibleRect(visible)&&visible.equals(new Rect(0,0,view.getWidth(),view.getHeight()));
        int[] location=new int[2];view.getLocationOnScreen(location);Rect screenBounds=new Rect(location[0],location[1],location[0]+view.getWidth(),location[1]+view.getHeight());
        if(active&&screenBounds.equals(previous)){if(stableSince[0]==0)stableSince[0]=android.os.SystemClock.uptimeMillis();ready[0]=android.os.SystemClock.uptimeMillis()-stableSince[0]>=250;}
        else{stableSince[0]=0;ready[0]=false;previous.set(screenBounds);}
      });
      if(ready[0])break;Thread.sleep(16);
    }while(android.os.SystemClock.uptimeMillis()<deadline);
    TagDetailChecks.require(ready[0],"swipe waits for focused, stable, fully visible photo input surface");
    int[] at=new int[2];float[] center=new float[2],distance={0};
    TagDetailChecks.ui(t,()->{
      if(view instanceof ZoomImageView)TagDetailChecks.require(((ZoomImageView)view).photoBounds()!=null&&!((ZoomImageView)view).isZoomed(),"paging swipe requires loaded photo in fit mode");
      view.getLocationOnScreen(at);center[0]=at[0]+view.getWidth()/2f;center[1]=at[1]+view.getHeight()/2f;distance[0]=Math.min(GalleryUi.dp(view.getContext(),160),view.getWidth()-GalleryUi.dp(view.getContext(),32));
      TagDetailChecks.require(distance[0]>=GalleryUi.dp(view.getContext(),128),"swipe has sufficient in-bounds paging distance");
      Rect visible=new Rect();float localX=view.getWidth()/2f,localY=view.getHeight()/2f;
      TagDetailChecks.require(view.getLocalVisibleRect(visible)&&visible.equals(new Rect(0,0,view.getWidth(),view.getHeight()))&&localX-distance[0]/2>=visible.left&&localX+distance[0]/2<visible.right&&localY>=visible.top&&localY<visible.bottom,"swipe endpoints stay inside photo surface");
      android.util.Log.i("LoupeSwipeFixture","widthPx="+view.getWidth()+" heightPx="+view.getHeight()+" distancePx="+distance[0]+" direction="+direction+" focus="+view.hasWindowFocus());
    });
    MotionEvent.PointerProperties pointer=new MotionEvent.PointerProperties();pointer.id=0;pointer.toolType=MotionEvent.TOOL_TYPE_FINGER;
    MotionEvent.PointerCoords point=new MotionEvent.PointerCoords();point.pressure=1;point.size=1;point.y=center[1];
    MotionEvent.PointerProperties[] pointers={pointer};MotionEvent.PointerCoords[] points={point};long down=android.os.SystemClock.uptimeMillis();
    for(int i=0;i<=8;i++){point.x=center[0]+direction*(-distance[0]/2+distance[0]*i/8);touch(t,down,i==0?MotionEvent.ACTION_DOWN:i==8?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE,1,pointers,points);Thread.sleep(16);}t.waitForIdleSync();
    android.util.Log.i("LoupeSwipeFixture","completed elapsedMs="+(android.os.SystemClock.uptimeMillis()-down));
  }
  static String run(Instrumentation t,int width)throws Exception{
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));ArrayList<File> files=new ArrayList<>();int[] rootInsets=new int[4];
    try{GalleryScreenChecks.finishInitialIndex(t,a);ArrayList<PhotoRepository.Photo> photos=new ArrayList<>();
      for(int i=0;i<2;i++){File f=new File(a.getCacheDir(),"viewer-synthetic-"+i+".jpg");files.add(f);Bitmap b=Bitmap.createBitmap(i==0?600:400,i==0?400:600,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);Paint p=new Paint(3);p.setShader(new LinearGradient(0,0,0,b.getHeight(),0xff8d9399,0xff2a2d31,Shader.TileMode.CLAMP));canvas.drawRect(0,0,b.getWidth(),b.getHeight(),p);p.setShader(null);p.setColor(0xffc8b08a);canvas.drawOval(b.getWidth()*.39f,b.getHeight()*.45f,b.getWidth()*.61f,b.getHeight()*.75f,p);try(FileOutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,90,out);}b.recycle();photos.add(new PhotoRepository.Photo(Uri.fromFile(f),"合成写真_"+i+".jpg","/synthetic",f.length(),1792298040L,1792298040000L));}
      TagDetailChecks.ui(t,()->{a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);RankingResultChecks.set(a,"photos",photos);RankingResultChecks.set(a,"bundles",List.of(List.of(0),List.of(1)));RankingResultChecks.set(a,"selecting",false);RankingResultChecks.set(a,"burstMembers",null);RankingResultChecks.set(a,"albumFolder","/synthetic");RankingResultChecks.call(a,"gallery");});GalleryScreenChecks.settle(t,a);
      File dir=new File(a.getCacheDir(),"consistency-checks");dir.mkdirs();
      for(int i=0;i<2;i++){open(t,a,i);TagDetailChecks.ui(t,()->{TagDetailChecks.require((boolean)RankingResultChecks.get(a,"detail")&&RankingResultChecks.get(a,"activePreview")!=null,"generated photo loaded");TagDetailChecks.inspect((View)RankingResultChecks.get(a,"root"));View measuredRoot=(View)RankingResultChecks.get(a,"root");rootInsets[0]=measuredRoot.getPaddingLeft();rootInsets[1]=measuredRoot.getPaddingTop();rootInsets[2]=measuredRoot.getPaddingRight();rootInsets[3]=measuredRoot.getPaddingBottom();PhotoViewerUi viewer=(PhotoViewerUi)((android.widget.LinearLayout)RankingResultChecks.get(a,"root")).getChildAt(0);TagDetailChecks.require(viewer.stage.getTop()>=GalleryUi.dp(a,48),"photo stage follows the header");TagDetailChecks.require(viewer.getHeight()-viewer.stage.getBottom()>=GalleryUi.dp(a,48),"photo leaves room for direct actions");toolbar(viewer,8,false);editBeforeShare(viewer);TagDetailChecks.require(((android.graphics.drawable.ColorDrawable)viewer.getBackground()).getColor()==Color.BLACK,"pure black viewer");TagDetailChecks.require(viewer.date.getText().toString().contains("10月18日"),"known capture date");});ConsistencyLayoutChecks.captureHardware(t,a,(View)RankingResultChecks.get(a,"root"),dir,"viewer-"+width+"-"+a.getResources().getConfiguration().fontScale+"-"+(i==0?"landscape":"portrait"));TagDetailChecks.ui(t,()->a.onBackPressed());GalleryScreenChecks.settle(t,a);TagDetailChecks.ui(t,()->TagDetailChecks.require(!(boolean)RankingResultChecks.get(a,"detail"),"Back restores gallery"));}
      open(t,a,0);
      clickSecondary(t,a,"拡大");TagDetailChecks.ui(t,()->{PhotoViewerUi v=viewer(a);android.widget.Button zoom=v.actionButton("拡大");android.view.accessibility.AccessibilityNodeInfo info=zoom.createAccessibilityNodeInfo();try{TagDetailChecks.require(v.image.isZoomed()&&zoom.getText().toString().equals("全体")&&info.isCheckable()&&info.isChecked(),"zoom button state");}finally{info.recycle();}});
      shot(t,a,dir,"viewer-zoom-"+width);
      doubleTap(t,viewer(a).image);
      TagDetailChecks.ui(t,()->TagDetailChecks.require(!viewer(a).image.isZoomed()&&viewer(a).actionButton("拡大")!=null,"double tap restores fit and label"));
      pinch(t,viewer(a).image);TagDetailChecks.ui(t,()->{Button zoom=viewer(a).actionButton("拡大");android.view.accessibility.AccessibilityNodeInfo node=zoom.createAccessibilityNodeInfo();try{TagDetailChecks.require(viewer(a).image.isZoomed()&&"全体を表示".contentEquals(zoom.getContentDescription())&&node.isCheckable()&&node.isChecked(),"independent real pinch updates zoom and AX state");}finally{node.recycle();}});clickSecondary(t,a,"拡大");
      swipe(t,viewer(a).image,-1);((Future<?>)RankingResultChecks.get(a,"viewing")).get(30,TimeUnit.SECONDS);t.waitForIdleSync();Thread.sleep(300);TagDetailChecks.ui(t,()->{TagDetailChecks.require((int)RankingResultChecks.get(a,"index")==1,"real swipe next");TagDetailChecks.require(!viewer(a).actionButton("次の写真").isEnabled(),"last photo next disabled");});clickSecondary(t,a,"前の写真");((Future<?>)RankingResultChecks.get(a,"viewing")).get(30,TimeUnit.SECONDS);t.waitForIdleSync();Thread.sleep(300);TagDetailChecks.ui(t,()->TagDetailChecks.require((int)RankingResultChecks.get(a,"index")==0&&!viewer(a).actionButton("前の写真").isEnabled(),"previous button and first disabled"));
      TagDetailChecks.ui(t,()->{StatusView loading=status(viewer(a).stage);loading.begin("合成の読込状態");loading.companion(StatusView.Symbol.PREVIEW);viewer(a).refreshStatus();});Thread.sleep(500);t.waitForIdleSync();
      TagDetailChecks.ui(t,()->{PhotoViewerUi v=viewer(a);StatusView loading=status(v.stage);FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)loading.getLayoutParams();TagDetailChecks.require(lp.topMargin==Math.round(v.image.photoBounds().top)+GalleryUi.dp(a,2),"preview marker follows fit photo top");});
      shot(t,a,dir,"viewer-preview-"+width);
      TagDetailChecks.ui(t,()->{StatusView loading=status(viewer(a).stage);loading.show(StatusView.Symbol.FAILED,"","プレビューを表示しています。元のファイルを読み込めませんでした",()->{},"再試行");viewer(a).refreshStatus();});
      shot(t,a,dir,"viewer-preview-failed-"+width);
      TagDetailChecks.ui(t,()->a.onBackPressed());GalleryScreenChecks.settle(t,a);
      photos.add(new PhotoRepository.Photo(Uri.fromFile(new File(a.getCacheDir(),"viewer-intentionally-missing.jpg")),"合成欠損.jpg","/synthetic",0,0,0));
      open(t,a,2);TagDetailChecks.ui(t,()->TagDetailChecks.require(TagDetailChecks.button(viewer(a),"再試行")!=null&&RankingResultChecks.get(a,"activePreview")==null,"missing photo offers retry"));
      shot(t,a,dir,"viewer-total-failed-"+width);
      TagDetailChecks.ui(t,()->a.onBackPressed());GalleryScreenChecks.settle(t,a);
      // Exercise both ranking entry points without choosing or saving a rank.
      ArrayList<String> ids=new ArrayList<>();for(int i=0;i<2;i++)ids.add(ArchiveStore.key(photos.get(i)));
      PhotoRanking initial=new PhotoRanking(ids,"B:");
      for(boolean complete:new boolean[]{false,true}){
        PhotoRanking rank=complete?initial.choose(true):initial;
        TagDetailChecks.ui(t,()->{RankingResultChecks.set(a,"ranking",rank);Map<String,Integer> members=(Map<String,Integer>)RankingResultChecks.get(a,"rankingMembers");members.clear();for(int i=0;i<2;i++)members.put(ids.get(i),i);RankingResultChecks.set(a,"rankingResultState",null);RankingResultChecks.set(a,"rankingResultFocus",null);});
        openFromRanking(t,a,0);
        TagDetailChecks.ui(t,()->{PhotoViewerUi v=viewer(a);toolbar(v,4,false);editBeforeShare(v);TagDetailChecks.require(TagDetailChecks.button(v,"共有")!=null&&TagDetailChecks.button(v,"前へ")==null,"ranking viewer retains share and no paging");android.widget.Button choose=TagDetailChecks.button(v,"この写真を選ぶ");TagDetailChecks.require((choose!=null)==!complete,"choose only unfinished comparison");if(choose!=null)TagDetailChecks.require(choose.getText() instanceof android.text.Spanned&&((android.text.Spanned)choose.getText()).getSpans(0,choose.getText().length(),android.text.style.ReplacementSpan.class).length==1,"inline choose check");});
        shot(t,a,dir,"viewer-"+(complete?"result":"comparison")+"-"+width);
        TagDetailChecks.ui(t,()->TagDetailChecks.button(viewer(a),"共有").performClick());
        t.waitForIdleSync();Thread.sleep(150);TagDetailChecks.ui(t,()->TagDetailChecks.require(((Dialog)RankingResultChecks.get(a,"shareDialog")).isShowing(),"viewer opens existing share menu"));
        t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);t.waitForIdleSync();
        TagDetailChecks.ui(t,()->{TagDetailChecks.require(!((Dialog)RankingResultChecks.get(a,"shareDialog")).isShowing()&&(boolean)RankingResultChecks.get(a,"detail")&&(int)RankingResultChecks.get(a,"index")==0,"share Back preserves viewer identity");});clickSecondary(t,a,"情報");
        t.waitForIdleSync();Thread.sleep(150);t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);t.waitForIdleSync();
        TagDetailChecks.ui(t,()->{TagDetailChecks.require((boolean)RankingResultChecks.get(a,"detail")&&(int)RankingResultChecks.get(a,"index")==0,"info Back preserves viewer identity");a.onBackPressed();TagDetailChecks.require((boolean)RankingResultChecks.get(a,"rankingScreen"),"Back restores comparison or result");});
      }
      // An app-owned generated content URI exercises the existing tag-button condition.
      File shareDir=new File(a.getCacheDir(),"shares");shareDir.mkdirs();File contentFile=new File(shareDir,UUID.randomUUID()+".jpg");files.add(contentFile);java.nio.file.Files.copy(files.get(0).toPath(),contentFile.toPath());
      photos.set(0,new PhotoRepository.Photo(Uri.parse("content://jp.nano.rawgallery.share/"+contentFile.getName()),"合成content.jpg","/synthetic",contentFile.length(),1792298040L,0));
      open(t,a,0);TagDetailChecks.ui(t,()->{PhotoViewerUi v=viewer(a);toolbar(v,9,width<=320);editBeforeShare(v);TagDetailChecks.require(v.actionButton("タグ")!=null&&v.date.getContentDescription().toString().startsWith("更新日時"),"content tag and modified fallback");});
      shot(t,a,dir,"viewer-content-"+width);
      TagDetailChecks.ui(t,()->a.onBackPressed());GalleryScreenChecks.settle(t,a);
      // Cloud-badge presentation is isolated from archive/network retrieval.
      Bitmap cloudBitmap=BitmapFactory.decodeFile(files.get(0).getPath());
      TagDetailChecks.ui(t,()->{PhotoRepository.Photo cloudPhoto=new PhotoRepository.Photo(Uri.parse("archive://viewer-synthetic"),"合成cloud.jpg","",0,0,0);android.widget.LinearLayout root=(android.widget.LinearLayout)RankingResultChecks.get(a,"root");root.removeAllViews();PhotoViewerUi v=new PhotoViewerUi(a,cloudPhoto);root.addView(v,new android.widget.LinearLayout.LayoutParams(-1,0,1));v.action("前の写真","前へ","前の写真",()->{});v.action("次の写真","次へ","次の写真",()->{});v.zoom();v.action("共有","共有","共有",()->{});v.action("情報","情報","写真の情報",()->{});v.image.setBitmap(cloudBitmap);TagDetailChecks.require(v.date.getText().toString().equals("—"),"unknown date");});
      t.waitForIdleSync();shot(t,a,dir,"viewer-cloud-"+width);clickSecondary(t,a,"拡大");shot(t,a,dir,"viewer-cloud-zoom-"+width);
      return "PASS rootPaddingPx="+java.util.Arrays.toString(rootInsets)+"; generated actual viewer landscape/portrait loaded; edit/share ordering and 8/9/4-operation direct/secondary hierarchy, text bounds/Back, zoom button/AX state/real double tap/pinch/swipe and paging endpoints, preview marker placement (presentation fixture), missing-file failure, comparison/result share/info Back, content tag condition, modified/unknown date, cloud presentation checked. No edit chooser, photo/ranking/tag save or send invoked by test; existing startup index may update browsing DB.\n";
    }finally{TagDetailChecks.ui(t,()->{a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.finish();});for(File f:files)f.delete();}
  }
}
