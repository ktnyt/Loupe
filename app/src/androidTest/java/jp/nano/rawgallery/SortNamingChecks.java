package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Real screen transitions with generated JPEGs and an isolated ranking store. */
final class SortNamingChecks {
  private static Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
  private static Object uncheckedField(Object o,String n){try{return field(o,n);}catch(Exception e){throw new RuntimeException(e);}}
  private static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
  private static void invoke(Object o,String n)throws Exception{Method m=o.getClass().getDeclaredMethod(n);m.setAccessible(true);m.invoke(o);}
  private static void main(Instrumentation t,Runnable r){
    Throwable[] failure={null};t.runOnMainSync(()->{try{r.run();}catch(Throwable e){failure[0]=e;}});
    if(failure[0]!=null)throw new AssertionError("UI check failed",failure[0]);t.waitForIdleSync();
  }
  private static void waitFor(Instrumentation t,DiagnosticChecks.Check check)throws Exception{
    long until=android.os.SystemClock.uptimeMillis()+6000;boolean[] done={false};
    while(android.os.SystemClock.uptimeMillis()<until){main(t,()->done[0]=check.done());if(done[0])return;Thread.sleep(50);}
    throw new AssertionError("UI timeout");
  }
  private static TextView find(View v,String text){
    if(v instanceof TextView && (android.text.TextUtils.equals(text,((TextView)v).getText()) || android.text.TextUtils.equals(text,v.getContentDescription())))return (TextView)v;
    if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView found=find(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}
    return null;
  }
  private static ListView results(View v){
    if(v instanceof ListView)return (ListView)v;
    if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){ListView l=results(((ViewGroup)v).getChildAt(i));if(l!=null)return l;}return null;
  }
  private static TextView require(MainActivity a,String text){TextView v=find(a.getWindow().getDecorView(),text);if(v==null)throw new AssertionError("Missing "+text);return v;}
  private static void click(Instrumentation t,MainActivity a,String text)throws Exception{
    try { waitFor(t,()->{TextView v=find(a.getWindow().getDecorView(),text);return v!=null&&v.isEnabled();}); }
    catch(AssertionError e){capture(t,a,"failed");throw new AssertionError("Waiting for "+text+" ranking="+field(a,"rankingScreen")+" finishing="+a.isFinishing()+" journal="+((PhotoRanking)field(a,"ranking")).journal,e);}
    main(t,()->require(a,text).performClick());
  }
  private static void dialog(Instrumentation t,String text,boolean click)throws Exception{
    long until=android.os.SystemClock.uptimeMillis()+4000;
    while(android.os.SystemClock.uptimeMillis()<until){
      AccessibilityNodeInfo root=t.getUiAutomation().getRootInActiveWindow();
      if(root!=null)for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText(text)){
        if(android.text.TextUtils.equals(text,n.getText())||android.text.TextUtils.equals(text,n.getContentDescription())){
          if(click&&!n.isClickable())continue;
          if(click && !n.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Cannot click "+text);
          t.waitForIdleSync();return;
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Missing dialog text "+text);
  }
  private static void capture(Instrumentation t,MainActivity a,String name)throws Exception{
    Thread.sleep(350);t.waitForIdleSync();
    File dir=new File(a.getCacheDir(),"consistency-checks");dir.mkdirs();
    ConsistencyLayoutChecks.captureHardware(t,a,(View)field(a,"root"),dir,"sort-test-"+name);
  }
  private static void back(Instrumentation t) { t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); t.waitForIdleSync(); }
  static String run(Instrumentation t)throws Exception{
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    SharedPreferences prefs=a.getSharedPreferences("sort-naming-test",0);
    List<File> files=new ArrayList<>();
    try{
      ((ExecutorService)field(a,"scans")).submit(()->{}).get(10,TimeUnit.SECONDS);t.waitForIdleSync();
      prefs.edit().clear().commit();RankingStore store=new RankingStore(prefs);
      List<PhotoRepository.Photo> photos=new ArrayList<>();
      for(int i=0;i<3;i++){
        File f=new File(a.getCacheDir(),"sort-fixture-"+i+".jpg");files.add(f);
        Bitmap b=Bitmap.createBitmap(720,960,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(new int[]{0xff587c8c,0xffa17862,0xff688664}[i]);Paint p=new Paint();p.setColor(0xffededea);p.setTextSize(180);c.drawText(""+(i+1),300,530,p);
        try(FileOutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,90,out);}b.recycle();
        photos.add(new PhotoRepository.Photo(Uri.fromFile(f),"fixture-"+(i+1)+".jpg","",f.length(),i,0));
      }
      main(t,()->{try{set(a,"rankingStore",store);set(a,"photos",photos);set(a,"bundles",List.of(List.of(0,1,2)));set(a,"burstMembers",List.of(0,1,2));set(a,"burstPosition",0);((Map<?,?>)field(a,"rankCache")).clear();invoke(a,"gallery");}catch(Exception e){throw new RuntimeException(e);}});
      waitFor(t,()->require(a,"ソート").getHeight()>0);
      main(t,()->{TextView b=require(a,"ソート");if(!"ソート".contentEquals(b.getContentDescription())||b.getHeight()<GalleryUi.dp(a,64))throw new AssertionError("CTA accessibility/height");});
      click(t,a,"次の写真");if((int)field(a,"burstPosition")!=1)throw new AssertionError("next photo");
      click(t,a,"前の写真");if((int)field(a,"burstPosition")!=0)throw new AssertionError("previous photo");
      click(t,a,"この写真を1位に");dialog(t,"この写真を1位にしますか？",false);dialog(t,"キャンセル",true);
      capture(t,a,"idle");click(t,a,"ソート");
      main(t,()->{if(!require(a,"ソート").isAccessibilityHeading())throw new AssertionError("heading");});
      String initial=((PhotoRanking)field(a,"ranking")).journal;
      click(t,a,"1位を決める");if(!initial.equals(((PhotoRanking)field(a,"ranking")).journal))throw new AssertionError("same mode changed journal");
      click(t,a,"すべてに順位");dialog(t,"ソート方式を変更しますか？",false);
      if(!((PhotoRanking)field(a,"ranking")).bestOnly())throw new AssertionError("changed before confirmation");
      waitFor(t,()->!a.hasWindowFocus());back(t);if(!initial.equals(((PhotoRanking)field(a,"ranking")).journal))throw new AssertionError("Back changed mode");
      click(t,a,"すべてに順位");dialog(t,"変更",true);
      if(((PhotoRanking)field(a,"ranking")).bestOnly())throw new AssertionError("all mode not saved");
      capture(t,a,"all-mode");
      SharedPreferences.Editor failedEditor=(SharedPreferences.Editor)Proxy.newProxyInstance(SortNamingChecks.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(proxy,m,args)->{if(m.getName().equals("commit"))return false;if(m.getName().equals("putString"))return proxy;throw new AssertionError(m.getName());});
      SharedPreferences failedPrefs=(SharedPreferences)Proxy.newProxyInstance(SortNamingChecks.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,m,args)->{if(m.getName().equals("edit"))return failedEditor;throw new AssertionError(m.getName());});
      main(t,()->{try{set(a,"rankingStore",new RankingStore(failedPrefs));}catch(Exception e){throw new RuntimeException(e);}});
      click(t,a,"1位を決める");dialog(t,"変更",true);
      if(((PhotoRanking)field(a,"ranking")).bestOnly()||!require(a,"すべてに順位").isSelected())throw new AssertionError("save failure changed mode");
      main(t,()->{try{set(a,"rankingStore",store);}catch(Exception e){throw new RuntimeException(e);}});
      click(t,a,"1位を決める");dialog(t,"変更",true);
      if(!((PhotoRanking)field(a,"ranking")).bestOnly())throw new AssertionError("best mode not restored");
      capture(t,a,"comparison");click(t,a,"Aを選ぶ");back(t);
      main(t,()->require(a,"ソートを再開"));capture(t,a,"resume");click(t,a,"ソートを再開");click(t,a,"Aを選ぶ");
      main(t,()->require(a,"ソート結果"));capture(t,a,"result");
      String resultJournal=((PhotoRanking)field(a,"ranking")).journal;
      waitFor(t,()->results(a.getWindow().getDecorView()).getChildCount()>0);
      main(t,()->{View row=a.getWindow().getDecorView().findViewWithTag("result-hero-"+((PhotoRanking)uncheckedField(a,"ranking")).best().get(0));
        if(row==null||!row.getContentDescription().toString().contains("fixture-"))throw new AssertionError("result semantics");
        if(!row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null))throw new AssertionError("accessible open rejected");});
      waitFor(t,()->results(a.getWindow().getDecorView())==null);back(t);
      waitFor(t,()->results(a.getWindow().getDecorView())!=null&&results(a.getWindow().getDecorView()).getChildCount()>0);
      if(!resultJournal.equals(((PhotoRanking)field(a,"ranking")).journal))throw new AssertionError("open changed ranking");
      PhotoRanking originalRanking=(PhotoRanking)field(a,"ranking");
      Map<String,Integer> originalMembers=new LinkedHashMap<>((Map<String,Integer>)field(a,"rankingMembers"));
      List<PhotoRepository.Photo> many=new ArrayList<>();Map<String,Integer> manyMembers=new LinkedHashMap<>();
      for(int i=0;i<40;i++){PhotoRepository.Photo photo=new PhotoRepository.Photo(photos.get(0).uri,"scroll-fixture-"+i+".jpg","",photos.get(0).size,i,0);many.add(photo);manyMembers.put(ArchiveStore.key(photo),i);}
      PhotoRanking completed=new PhotoRanking(new ArrayList<>(manyMembers.keySet()),"B:");while(!completed.done())completed=completed.choose(true);
      PhotoRanking scrollRanking=completed;
      main(t,()->{try{set(a,"photos",many);Map<String,Integer> members=(Map<String,Integer>)field(a,"rankingMembers");members.clear();members.putAll(manyMembers);set(a,"ranking",scrollRanking);set(a,"rankingResultState",null);invoke(a,"showRanking");}catch(Exception e){throw new RuntimeException(e);}});
      waitFor(t,()->results(a.getWindow().getDecorView()).getChildCount()>0);
      main(t,()->results(a.getWindow().getDecorView()).scrollListBy(1800));
      waitFor(t,()->results(a.getWindow().getDecorView()).getFirstVisiblePosition()>0);
      int[] pos={0,0};main(t,()->{ListView list=results(a.getWindow().getDecorView());pos[0]=list.getFirstVisiblePosition();pos[1]=list.getChildAt(0).getTop();View target=list.findViewWithTag("result-"+scrollRanking.ids.get(30));if(target==null)throw new AssertionError("photo target missing");target.performClick();});
      waitFor(t,()->results(a.getWindow().getDecorView())==null);back(t);
      waitFor(t,()->{ListView list=results(a.getWindow().getDecorView());return list!=null&&list.getChildCount()>0&&list.getFirstVisiblePosition()==pos[0]&&list.getChildAt(0).getTop()==pos[1];});
      main(t,()->{try{set(a,"photos",photos);Map<String,Integer> members=(Map<String,Integer>)field(a,"rankingMembers");members.clear();members.putAll(originalMembers);set(a,"ranking",originalRanking);set(a,"rankingResultState",null);invoke(a,"showRanking");}catch(Exception e){throw new RuntimeException(e);}});

      back(t);
      main(t,()->require(a,"ソート結果"));capture(t,a,"done");click(t,a,"ソート結果");
      String journal=((PhotoRanking)field(a,"ranking")).journal;
      click(t,a,"再ソート");dialog(t,"この連写を再ソートしますか？",false);capture(t,a,"redo");dialog(t,"キャンセル",true);
      if(!journal.equals(((PhotoRanking)field(a,"ranking")).journal))throw new AssertionError("cancel changed journal");
      click(t,a,"すべての順位を決める");dialog(t,"ソート方式を変更しますか？",false);capture(t,a,"mode");dialog(t,"キャンセル",true);
      click(t,a,"再ソート");dialog(t,"再ソート",true);
      main(t,()->{try{if(((PhotoRanking)field(a,"ranking")).decisions()!=0)throw new AssertionError("redo did not reset");}catch(Exception e){throw new RuntimeException(e);}});
      back(t);
      main(t,()->{try{set(a,"selecting",true);Set<String> selected=(Set<String>)field(a,"selectedPhotos");selected.clear();for(PhotoRepository.Photo p:photos)selected.add(p.uri.toString());invoke(a,"shareSelection");}catch(Exception e){throw new RuntimeException(e);}});
      dialog(t,"1組の順位がまだ決まっていません。ソートした後に選択画面へ戻れます。",false);capture(t,a,"share");dialog(t,"ソート",true);
      main(t,()->require(a,"ソート"));back(t);
      main(t,()->{try{if(!(boolean)field(a,"selecting") || (boolean)field(a,"rankingScreen") || ((Set<?>)field(a,"selectedPhotos")).size()!=3)throw new AssertionError("share selection not preserved");invoke(a,"addTarget");}catch(Exception e){throw new RuntimeException(e);}});
      dialog(t,"登録するアプリ",false);back(t);dialog(t,"共有先と並び順",false);back(t);
      main(t,()->{
        for(float scale:new float[]{1f,1.3f})for(int width:new int[]{280,320,360,412})for(String label:new String[]{"ソート","ソートを再開","ソート結果"}){
          Configuration config=new Configuration(a.getResources().getConfiguration());config.fontScale=scale;
          Context context=a.createConfigurationContext(config);Button b=GalleryUi.neutralPrimary(GalleryUi.button(context,label,()->{}));
          b.measure(View.MeasureSpec.makeMeasureSpec(GalleryUi.dp(context,width-32),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
          b.layout(0,0,b.getMeasuredWidth(),b.getMeasuredHeight());
          if(b.getMeasuredHeight()<GalleryUi.dp(context,64)||b.getCompoundDrawables()[1]==null||!label.contentEquals(b.getContentDescription()))throw new AssertionError("button contract");
          for(int line=0;line<b.getLayout().getLineCount();line++)if(b.getLayout().getEllipsisCount(line)!=0)throw new AssertionError("clipped label");
          int required=b.getLayout().getHeight()+b.getCompoundPaddingTop()+b.getCompoundPaddingBottom();if(required>b.getMeasuredHeight())throw new AssertionError("vertical clipping "+label);
        }
      });
      return "PASS synthetic actual screens: start, compare, resume, finish, result, redo cancel/execute, mode cancel, share guidance/return; mode same/cancel/confirm/save failure; AX ACTION_CLICK opens result; 40-result scroll/open/Back restores first row and pixel offset; isolated rankings; 24 button size/font checks; accessibility names/headings. Screenshots cache/sort-test-*.png. TalkBack spoken order not tested.\n";
    }finally{main(t,a::finish);prefs.edit().clear().commit();for(File f:files)f.delete();}
  }
}
