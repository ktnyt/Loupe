package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.widget.FrameLayout;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual photo viewer with generated local images; no send or ranking/tag save. */
final class ViewerScreenChecks {
  static ZoomImageView image(View root){if(root instanceof ZoomImageView)return (ZoomImageView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){ZoomImageView found=image(((ViewGroup)root).getChildAt(i));if(found!=null)return found;}return null;}
  static void open(Instrumentation t,MainActivity a,int index)throws Exception{TagDetailChecks.ui(t,()->{try{java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("showPhoto",int.class);m.setAccessible(true);m.invoke(a,index);}catch(Exception e){throw new RuntimeException(e);}});((Future<?>)RankingResultChecks.get(a,"viewing")).get(30,TimeUnit.SECONDS);t.waitForIdleSync();Thread.sleep(800);t.waitForIdleSync();}
  static void openFromRanking(Instrumentation t,MainActivity a,int index)throws Exception{TagDetailChecks.ui(t,()->{try{java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("showPhoto",int.class,boolean.class);m.setAccessible(true);m.invoke(a,index,true);}catch(Exception e){throw new RuntimeException(e);}});((Future<?>)RankingResultChecks.get(a,"viewing")).get(30,TimeUnit.SECONDS);t.waitForIdleSync();Thread.sleep(800);t.waitForIdleSync();}
  static PhotoViewerUi viewer(MainActivity a){return (PhotoViewerUi)((android.widget.LinearLayout)RankingResultChecks.get(a,"root")).getChildAt(0);}
  static StatusView status(View v){if(v instanceof StatusView)return (StatusView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){StatusView found=status(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;}
  static void shot(Instrumentation t,MainActivity a,File dir,String name)throws Exception{t.waitForIdleSync();Thread.sleep(100);ConsistencyLayoutChecks.captureHardware(t,a,(View)RankingResultChecks.get(a,"root"),dir,name);}
  static void doubleTap(Instrumentation t,View image)throws Exception{int[] p=new int[2];TagDetailChecks.ui(t,()->{image.getLocationOnScreen(p);p[0]+=image.getWidth()/2;p[1]+=image.getHeight()/2;});for(int i=0;i<2;i++){long down=android.os.SystemClock.uptimeMillis();MotionEvent press=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,p[0],p[1],0),release=MotionEvent.obtain(down,down+30,MotionEvent.ACTION_UP,p[0],p[1],0);try{t.sendPointerSync(press);t.sendPointerSync(release);}finally{press.recycle();release.recycle();}Thread.sleep(70);}t.waitForIdleSync();}
  static String run(Instrumentation t,int width)throws Exception{
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));ArrayList<File> files=new ArrayList<>();int[] rootInsets=new int[4];
    try{GalleryScreenChecks.finishInitialIndex(t,a);ArrayList<PhotoRepository.Photo> photos=new ArrayList<>();
      for(int i=0;i<2;i++){File f=new File(a.getCacheDir(),"viewer-synthetic-"+i+".jpg");files.add(f);Bitmap b=Bitmap.createBitmap(i==0?600:400,i==0?400:600,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);Paint p=new Paint(3);p.setShader(new LinearGradient(0,0,0,b.getHeight(),0xff8d9399,0xff2a2d31,Shader.TileMode.CLAMP));canvas.drawRect(0,0,b.getWidth(),b.getHeight(),p);p.setShader(null);p.setColor(0xffc8b08a);canvas.drawOval(b.getWidth()*.39f,b.getHeight()*.45f,b.getWidth()*.61f,b.getHeight()*.75f,p);try(FileOutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,90,out);}b.recycle();photos.add(new PhotoRepository.Photo(Uri.fromFile(f),"合成写真_"+i+".jpg","/synthetic",f.length(),1792298040L,1792298040000L));}
      TagDetailChecks.ui(t,()->{a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);RankingResultChecks.set(a,"photos",photos);RankingResultChecks.set(a,"bundles",List.of(List.of(0),List.of(1)));RankingResultChecks.set(a,"selecting",false);RankingResultChecks.set(a,"burstMembers",null);RankingResultChecks.set(a,"albumFolder","/synthetic");RankingResultChecks.call(a,"gallery");});GalleryScreenChecks.settle(t,a);
      File dir=new File(a.getCacheDir(),"consistency-checks");dir.mkdirs();
      for(int i=0;i<2;i++){open(t,a,i);TagDetailChecks.ui(t,()->{TagDetailChecks.require((boolean)RankingResultChecks.get(a,"detail")&&RankingResultChecks.get(a,"activePreview")!=null,"generated photo loaded");TagDetailChecks.inspect((View)RankingResultChecks.get(a,"root"));View measuredRoot=(View)RankingResultChecks.get(a,"root");rootInsets[0]=measuredRoot.getPaddingLeft();rootInsets[1]=measuredRoot.getPaddingTop();rootInsets[2]=measuredRoot.getPaddingRight();rootInsets[3]=measuredRoot.getPaddingBottom();PhotoViewerUi viewer=(PhotoViewerUi)((android.widget.LinearLayout)RankingResultChecks.get(a,"root")).getChildAt(0);TagDetailChecks.require(viewer.stage.getTop()==GalleryUi.dp(a,72),"72dp photo stage");TagDetailChecks.require(viewer.getHeight()-viewer.stage.getBottom()>=GalleryUi.dp(a,100),"100dp photo bottom space");TagDetailChecks.require(viewer.dock.getChildCount()==5&&viewer.dock.getHeight()>=GalleryUi.dp(a,64),"single viewer dock");TagDetailChecks.require(((android.graphics.drawable.ColorDrawable)viewer.getBackground()).getColor()==Color.BLACK,"pure black viewer");TagDetailChecks.require(viewer.date.getText().toString().contains("10月18日"),"known capture date");});ConsistencyLayoutChecks.captureHardware(t,a,(View)RankingResultChecks.get(a,"root"),dir,"viewer-"+width+"-"+a.getResources().getConfiguration().fontScale+"-"+(i==0?"landscape":"portrait"));TagDetailChecks.ui(t,()->a.onBackPressed());GalleryScreenChecks.settle(t,a);TagDetailChecks.ui(t,()->TagDetailChecks.require(!(boolean)RankingResultChecks.get(a,"detail"),"Back restores gallery"));}
      open(t,a,0);
      TagDetailChecks.ui(t,()->{PhotoViewerUi v=viewer(a);android.widget.Button zoom=TagDetailChecks.button(v,"拡大");zoom.performClick();android.view.accessibility.AccessibilityNodeInfo info=zoom.createAccessibilityNodeInfo();TagDetailChecks.require(v.image.isZoomed()&&zoom.getText().toString().equals("全体")&&info.isCheckable()&&info.isChecked(),"zoom button state");});
      shot(t,a,dir,"viewer-zoom-"+width);
      doubleTap(t,viewer(a).image);
      TagDetailChecks.ui(t,()->TagDetailChecks.require(!viewer(a).image.isZoomed()&&TagDetailChecks.button(viewer(a),"拡大")!=null,"double tap restores fit and label"));
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
        TagDetailChecks.ui(t,()->{PhotoViewerUi v=viewer(a);TagDetailChecks.require(v.dock.getChildCount()==3&&TagDetailChecks.button(v,"共有")!=null&&TagDetailChecks.button(v,"前へ")==null,"ranking viewer retains share and no paging");android.widget.Button choose=TagDetailChecks.button(v,"この写真を選ぶ");TagDetailChecks.require((choose!=null)==!complete,"choose only unfinished comparison");if(choose!=null)TagDetailChecks.require(choose.getText() instanceof android.text.Spanned&&((android.text.Spanned)choose.getText()).getSpans(0,choose.getText().length(),android.text.style.ReplacementSpan.class).length==1,"inline choose check");});
        shot(t,a,dir,"viewer-"+(complete?"result":"comparison")+"-"+width);
        TagDetailChecks.ui(t,()->TagDetailChecks.button(viewer(a),"共有").performClick());
        t.waitForIdleSync();Thread.sleep(150);TagDetailChecks.ui(t,()->TagDetailChecks.require(((Dialog)RankingResultChecks.get(a,"shareDialog")).isShowing(),"viewer opens existing share menu"));
        t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);t.waitForIdleSync();
        TagDetailChecks.ui(t,()->{TagDetailChecks.require(!((Dialog)RankingResultChecks.get(a,"shareDialog")).isShowing()&&(boolean)RankingResultChecks.get(a,"detail")&&(int)RankingResultChecks.get(a,"index")==0,"share Back preserves viewer identity");TagDetailChecks.button(viewer(a),"情報").performClick();});
        t.waitForIdleSync();Thread.sleep(150);t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);t.waitForIdleSync();
        TagDetailChecks.ui(t,()->{TagDetailChecks.require((boolean)RankingResultChecks.get(a,"detail")&&(int)RankingResultChecks.get(a,"index")==0,"info Back preserves viewer identity");a.onBackPressed();TagDetailChecks.require((boolean)RankingResultChecks.get(a,"rankingScreen"),"Back restores comparison or result");});
      }
      // An app-owned generated content URI exercises the existing tag-button condition.
      File shareDir=new File(a.getCacheDir(),"shares");shareDir.mkdirs();File contentFile=new File(shareDir,UUID.randomUUID()+".jpg");files.add(contentFile);java.nio.file.Files.copy(files.get(0).toPath(),contentFile.toPath());
      photos.set(0,new PhotoRepository.Photo(Uri.parse("content://jp.nano.rawgallery.share/"+contentFile.getName()),"合成content.jpg","/synthetic",contentFile.length(),1792298040L,0));
      open(t,a,0);TagDetailChecks.ui(t,()->TagDetailChecks.require(TagDetailChecks.button(viewer(a),"タグ")!=null&&viewer(a).date.getContentDescription().toString().startsWith("更新日時"),"content tag and modified fallback"));
      shot(t,a,dir,"viewer-content-"+width);
      TagDetailChecks.ui(t,()->a.onBackPressed());GalleryScreenChecks.settle(t,a);
      // Cloud-badge presentation is isolated from archive/network retrieval.
      Bitmap cloudBitmap=BitmapFactory.decodeFile(files.get(0).getPath());
      TagDetailChecks.ui(t,()->{PhotoRepository.Photo cloudPhoto=new PhotoRepository.Photo(Uri.parse("archive://viewer-synthetic"),"合成cloud.jpg","",0,0,0);android.widget.LinearLayout root=(android.widget.LinearLayout)RankingResultChecks.get(a,"root");root.removeAllViews();PhotoViewerUi v=new PhotoViewerUi(a,cloudPhoto);root.addView(v,new android.widget.LinearLayout.LayoutParams(-1,0,1));v.action("前の写真","前へ","前の写真",()->{});v.action("次の写真","次へ","次の写真",()->{});v.zoom();v.action("共有","共有","共有",()->{});v.action("情報","情報","写真の情報",()->{});v.image.setBitmap(cloudBitmap);TagDetailChecks.require(v.date.getText().toString().equals("—"),"unknown date");});
      t.waitForIdleSync();shot(t,a,dir,"viewer-cloud-"+width);TagDetailChecks.ui(t,()->TagDetailChecks.button(viewer(a),"拡大").performClick());shot(t,a,dir,"viewer-cloud-zoom-"+width);
      return "PASS rootPaddingPx="+java.util.Arrays.toString(rootInsets)+"; generated actual viewer landscape/portrait loaded; text bounds/Back, zoom button/AX state/real double tap, preview marker placement (presentation fixture), missing-file failure, comparison/result share/info Back, content tag condition, modified/unknown date, cloud presentation checked. No photo/ranking/tag save or send invoked by test; existing startup index may update browsing DB.\n";
    }finally{TagDetailChecks.ui(t,()->{a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.finish();});for(File f:files)f.delete();}
  }
}
