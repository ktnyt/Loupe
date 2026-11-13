package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Generated cache photographs only; no recipient, AI request, rank save or preference edit. */
final class ExpressiveUiChecks {
  static View description(View root,String label){
    if(label.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;
    if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=description(((ViewGroup)root).getChildAt(i),label);if(found!=null)return found;}return null;
  }
  static <T extends View> T find(View root,Class<T> type){
    if(type.isInstance(root))return type.cast(root);
    if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){T found=find(((ViewGroup)root).getChildAt(i),type);if(found!=null)return found;}return null;
  }
  static Button apply(View root){
    if(root instanceof Button&&"photo-filter-apply".equals(root.getTag()))return (Button)root;
    if(root instanceof Button&&((Button)root).getText().toString().matches(".*(件を表示|適用).*"))return (Button)root;
    if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){Button found=apply(((ViewGroup)root).getChildAt(i));if(found!=null)return found;}return null;
  }
  static View openFilter(Instrumentation t,MainActivity a)throws Exception{
    TagDetailChecks.ui(t,()->{View entry=description((View)RankingResultChecks.get(a,"root"),"絞り込み");TagDetailChecks.require(entry!=null,"filter entry in toolbar");entry.performClick();});return ShareScreenChecks.modal(t,a);
  }
  static void ready(Instrumentation t,MainActivity a,View sheet)throws Exception{
    GalleryScreenChecks.settle(t,a);boolean[] enabled={false};
    for(int i=0;i<60&&!enabled[0];i++){TagDetailChecks.ui(t,()->enabled[0]=apply(sheet)!=null&&apply(sheet).isEnabled());if(!enabled[0])Thread.sleep(50);}
    TagDetailChecks.require(enabled[0],"filter count completes and enables explicit apply");ConsistencyLayoutChecks.frame(t,sheet);
  }
  static void applyRank(Instrumentation t,MainActivity a,int rank)throws Exception{
    View sheet=openFilter(t,a);TagDetailChecks.ui(t,()->description(sheet,PhotoFilterSheet.RANKS[rank]).performClick());ready(t,a,sheet);
    TagDetailChecks.ui(t,()->apply(sheet).performClick());GalleryScreenChecks.settle(t,a);
  }
  static void capture(Instrumentation t,MainActivity a,File dir,String name)throws Exception{
    View root=(View)RankingResultChecks.get(a,"root");ConsistencyLayoutChecks.frame(t,root);TagDetailChecks.ui(t,()->TagDetailChecks.inspect(root));ConsistencyLayoutChecks.captureHardware(t,a,root,dir,name);
  }
  static void countGeneration(Instrumentation t,MainActivity a)throws Exception{
    List<java.util.function.IntConsumer> counts=new ArrayList<>();PhotoFilterSheet[] sheet={null};
    TagDetailChecks.ui(t,()->{sheet[0]=new PhotoFilterSheet(a,"すべて",0,(camera,rank,result)->counts.add(result),(camera,rank)->{throw new AssertionError("count test must not commit");});sheet[0].show();});
    try{
      // Dialog dispatches OnShow through the main queue. Let its initial count request finish
      // before choosing a new draft, so callback generations match actual user ordering.
      t.waitForIdleSync();View root=sheet[0].getWindow().getDecorView();ConsistencyLayoutChecks.frame(t,root);
      TagDetailChecks.ui(t,()->{
        TagDetailChecks.require(counts.size()==1,"initial OnShow requests exactly one count; actual="+counts.size());
        TagDetailChecks.require(apply(root)!=null,"pending Apply exists by stable tag");
        TagDetailChecks.require(!apply(root).isEnabled(),"initial count keeps Apply disabled");
        description(root,"1位").performClick();
        TagDetailChecks.require(counts.size()==2,"draft requests its own count; actual="+counts.size());
        TagDetailChecks.require(!apply(root).isEnabled(),"new draft waits for own count");
        counts.get(0).accept(999);TagDetailChecks.require(!apply(root).isEnabled(),"stale count cannot enable newer draft");
        counts.get(1).accept(12);TagDetailChecks.require(apply(root).isEnabled()&&apply(root).getText().toString().equals("12件を表示"),"latest count enables apply");
        sheet[0].dismiss();counts.get(1).accept(888);TagDetailChecks.require(apply(root).getText().toString().equals("12件を表示"),"dismissed sheet ignores late count");
      });
    }finally{TagDetailChecks.ui(t,()->sheet[0].dismiss());}
  }
  static void captureFilter(Instrumentation t,View sheet,File dir,String name)throws Exception{
    ConsistencyLayoutChecks.frame(t,sheet);int[] location=new int[2];TagDetailChecks.ui(t,()->{TagDetailChecks.require(sheet.hasWindowFocus(),"filter owns capture focus");sheet.getLocationOnScreen(location);});
    Bitmap screen=t.getUiAutomation().takeScreenshot();TagDetailChecks.require(screen!=null,"composited filter screenshot");Bitmap crop=null;
    try{crop=Bitmap.createBitmap(screen,location[0],location[1],sheet.getWidth(),sheet.getHeight());try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){crop.compress(Bitmap.CompressFormat.PNG,100,out);}}finally{if(crop!=null&&crop!=screen)crop.recycle();screen.recycle();}
  }
  static String run(Instrumentation t,int width,float font)throws Exception{
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    File fixture=new File(a.getCacheDir(),"expressive-fixture-"+UUID.randomUUID());fixture.mkdirs();
    File dir=new File(a.getCacheDir(),"consistency-checks");dir.mkdirs();List<File> files=new ArrayList<>();
    String prefix="expressive-"+width+"-"+font;
    try{
      GalleryScreenChecks.portraitFixture(t,a,width,font);GalleryScreenChecks.finishInitialIndex(t,a);
      List<PhotoRepository.Photo> photos=new ArrayList<>();List<List<Integer>> groups=new ArrayList<>();
      for(int i=0;i<12;i++){
        File f=new File(fixture,i+".jpg");files.add(f);Bitmap image=Bitmap.createBitmap(480,360,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);Paint paint=new Paint();
        paint.setShader(new LinearGradient(0,0,480,360,Color.rgb(65+i*10,110,140),Color.rgb(140,90+i*8,75),Shader.TileMode.CLAMP));canvas.drawRect(0,0,480,360,paint);paint.setShader(null);paint.setColor(0xffd5c9a7);canvas.drawCircle(150+i*8,170,60,paint);
        try(FileOutputStream out=new FileOutputStream(f)){image.compress(Bitmap.CompressFormat.JPEG,90,out);}finally{image.recycle();}
        photos.add(new PhotoRepository.Photo(Uri.fromFile(f),"合成写真"+i+".jpg",fixture.getAbsolutePath(),f.length(),1792298040L+i*5,1792298040000L+i*5000));groups.add(List.of(i));
      }
      TagDetailChecks.ui(t,()->{
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        RankingResultChecks.set(a,"photos",photos);RankingResultChecks.set(a,"bundles",groups);RankingResultChecks.set(a,"albumFolder",null);RankingResultChecks.set(a,"albumHome",false);RankingResultChecks.set(a,"filter","すべて");RankingResultChecks.set(a,"rankFilter",0);RankingResultChecks.set(a,"selecting",false);RankingResultChecks.set(a,"burstMembers",null);
        ((Set<?>)RankingResultChecks.get(a,"selectedPhotos")).clear();((Map<?,?>)RankingResultChecks.get(a,"rankCache")).clear();RankingResultChecks.call(a,"gallery");
      });GalleryScreenChecks.settle(t,a);
      TagDetailChecks.ui(t,()->{View root=(View)RankingResultChecks.get(a,"root");LibraryTabs tabs=find(root,LibraryTabs.class);TagDetailChecks.require(tabs!=null&&tabs.getMenu().size()==3,"three native bottom destinations");TagDetailChecks.require(tabs.destinationView(2).isEnabled(),"import available without connected camera");TagDetailChecks.require(((List<?>)RankingResultChecks.get(a,"displayed")).size()==12,"all synthetic photos visible");});
      capture(t,a,dir,prefix+"-gallery");countGeneration(t,a);
      View sheet=openFilter(t,a);TagDetailChecks.ui(t,()->{description(sheet,"順位決定済み").performClick();description(sheet,"GR").performClick();});ready(t,a,sheet);
      TagDetailChecks.ui(t,()->{TagDetailChecks.require((int)RankingResultChecks.get(a,"rankFilter")==0&&"すべて".equals(RankingResultChecks.get(a,"filter")),"draft leaves committed filters unchanged");TagDetailChecks.inspect(sheet);});
      captureFilter(t,sheet,dir,prefix+"-filter");
      TagDetailChecks.ui(t,()->description(sheet,"閉じる").performClick());GalleryScreenChecks.settle(t,a);
      TagDetailChecks.ui(t,()->TagDetailChecks.require((int)RankingResultChecks.get(a,"rankFilter")==0&&"すべて".equals(RankingResultChecks.get(a,"filter"))&&((List<?>)RankingResultChecks.get(a,"displayed")).size()==12,"dismiss preserves committed filter and photos"));
      applyRank(t,a,1);TagDetailChecks.ui(t,()->TagDetailChecks.require(((List<?>)RankingResultChecks.get(a,"displayed")).isEmpty(),"unmarked singles excluded from best"));
      applyRank(t,a,2);TagDetailChecks.ui(t,()->TagDetailChecks.require((int)RankingResultChecks.get(a,"rankFilter")==2&&((List<?>)RankingResultChecks.get(a,"displayed")).size()==12,"explicit unresolved filter shows unmarked singles"));
      TagDetailChecks.ui(t,()->TagDetailChecks.button((View)RankingResultChecks.get(a,"root"),"選択").performClick());GalleryScreenChecks.settle(t,a);
      GalleryScreenChecks.selectFirst(t,a,false);TagDetailChecks.ui(t,()->{View root=(View)RankingResultChecks.get(a,"root");GalleryScreenChecks.selectionFiltersVisible(root);TagDetailChecks.require(find(root,SelectionUi.BestSwitch.class)!=null,"best-only remains available");TagDetailChecks.require(((View)RankingResultChecks.get(a,"selectionAll")).getParent()==root.findViewWithTag("library-toolbar"),"select-all belongs to header");});
      capture(t,a,dir,prefix+"-selection");TagDetailChecks.ui(t,a::onBackPressed);GalleryScreenChecks.settle(t,a);
      TagDetailChecks.ui(t,()->TagDetailChecks.require(!(boolean)RankingResultChecks.get(a,"selecting")&&(int)RankingResultChecks.get(a,"rankFilter")==2,"selection Back preserves applied condition"));
      ViewerScreenChecks.open(t,a,0);TagDetailChecks.ui(t,()->{PhotoViewerUi v=ViewerScreenChecks.viewer(a);TagDetailChecks.require(v.dock.getChildCount()==3&&TagDetailChecks.button(v.dock,"手動")!=null&&TagDetailChecks.button(v.dock,"AI")!=null&&TagDetailChecks.button(v.dock,"共有")!=null,"three direct viewer entries");TagDetailChecks.require(description(v,"写真のその他の操作")!=null,"secondary action entry");});
      capture(t,a,dir,prefix+"-viewer");TagDetailChecks.ui(t,a::onBackPressed);GalleryScreenChecks.settle(t,a);
      TagDetailChecks.ui(t,()->TagDetailChecks.require(!(boolean)RankingResultChecks.get(a,"detail")&&(int)RankingResultChecks.get(a,"rankFilter")==2,"viewer Back preserves filter"));
      return "PASS expressive UI: generated-photo gallery/filter/selection/viewer captures; native three-item bottom navigation, draft/cancel/apply, explicit singleton rank filtering, selection navigation suppression/header actions/best-only and Back retention; "+width+"dp font"+font+". No AI request, sharing handoff or rank save. Physical device and spoken accessibility remain separate evidence.\n";
    }finally{TagDetailChecks.ui(t,()->{a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.finish();});for(File f:files)f.delete();fixture.delete();}
  }
}
