package jp.nano.rawgallery;

import static jp.nano.rawgallery.AlbumTagEntryChecks.*;
import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Generated photos + private ranking preferences; no user ranking writes. */
final class ComparisonLabelChecks {
  static void invoke(Object a,String name)throws Exception{Method m=a.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(a);}
  static ComparisonPhotoView candidate(View v,String letter){
    if(v instanceof ComparisonPhotoView&&v.getContentDescription().toString().startsWith(letter+"、"))return (ComparisonPhotoView)v;
    if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){ComparisonPhotoView found=candidate(((ViewGroup)v).getChildAt(i),letter);if(found!=null)return found;}
    return null;
  }
  static ComparisonPhotoView candidate(MainActivity a,String letter){return candidate(a.getWindow().getDecorView(),letter);}
  static void ready(Instrumentation t,MainActivity a)throws Exception{waitFor(t,"candidates",()->candidate(a,"A")!=null&&candidate(a,"A").isEnabled()&&candidate(a,"B").isEnabled());}
  static void click(Instrumentation t,MainActivity a,String name)throws Exception{main(t,()->{Button b=button(a.getWindow().getDecorView(),name);if(b==null||!b.isEnabled())throw new AssertionError("button "+name);b.performClick();return true;});}
  static void geometry(ComparisonPhotoView v)throws Exception{
    Bitmap target=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(target));target.recycle();
    RectF image=(RectF)field(v,"image"),badge=(RectF)field(v,"badge");
    if(badge.isEmpty()||!image.contains(badge))throw new AssertionError("badge outside image "+image+" "+badge);
    if(badge.centerX()>=image.centerX()||badge.centerY()>=image.centerY())throw new AssertionError("badge covers center");
  }
  static String run(Instrumentation t)throws Exception{
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    SharedPreferences prefs=a.getSharedPreferences("comparison-label-test",0);List<File> files=new ArrayList<>();List<Bitmap> bitmaps=new ArrayList<>();
    try{
      ((ExecutorService)field(a,"scans")).submit(()->{}).get(10,TimeUnit.SECONDS);t.waitForIdleSync();prefs.edit().clear().commit();
      List<PhotoRepository.Photo> photos=new ArrayList<>();
      for(int i=0;i<3;i++){
        File f=new File(a.getCacheDir(),"comparison-label-"+i+".jpg");files.add(f);
        Bitmap b=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888);new Canvas(b).drawColor(i==0?Color.WHITE:i==1?0xff0f0f10:0xff8a9a7a);
        try(FileOutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,95,out);}b.recycle();
        photos.add(new PhotoRepository.Photo(Uri.fromFile(f),"fixture-"+i+".jpg","",f.length(),i,0));
      }
      main(t,()->{set(a,"rankingStore",new RankingStore(prefs));set(a,"photos",photos);set(a,"bundles",List.of(List.of(0,1,2)));set(a,"burstMembers",List.of(0,1,2));invoke(a,"openRanking");return true;});ready(t,a);
      // Resize the actual comparison container in place: the existing layout chooser changes orientation.
      for(int height:new int[]{420,180,420}){
        main(t,()->{LinearLayout cmp=(LinearLayout)candidate(a,"A").getParent();cmp.setLayoutParams(new LinearLayout.LayoutParams(GalleryUi.dp(a,400),GalleryUi.dp(a,height)));return true;});
        waitFor(t,"orientation",()->((LinearLayout)candidate(a,"A").getParent()).getOrientation()==(height==180?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL));
        main(t,()->{PhotoRanking rank=(PhotoRanking)field(a,"ranking");Map<String,Integer> members=(Map<String,Integer>)field(a,"rankingMembers");
          for(String letter:new String[]{"A","B"}){ComparisonPhotoView v=candidate(a,letter);String key=letter.equals("A")?rank.upper():rank.lower();if(!v.getContentDescription().toString().contains(photos.get(members.get(key)).name))throw new AssertionError("wrong candidate name");geometry(v);}return true;});
      }
      for(String letter:new String[]{"A","B"}){
        PhotoRanking before=(PhotoRanking)field(a,"ranking");String key=letter.equals("A")?before.upper():before.lower();int expected=((Map<String,Integer>)field(a,"rankingMembers")).get(key);
        click(t,a,letter+"を拡大");main(t,()->{if((int)field(a,"index")!=expected)throw new AssertionError("zoom mismatch");return true;});
        t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);ready(t,a);
        click(t,a,letter+"を選ぶ");ready(t,a);
        main(t,()->{if(!((PhotoRanking)field(a,"ranking")).journal.equals(before.choose(letter.equals("A")).journal))throw new AssertionError("button choice mismatch");return true;});
        click(t,a,"直前の操作を取り消す");ready(t,a);
        main(t,()->{candidate(a,letter).performClick();return true;});ready(t,a);
        main(t,()->{if(!((PhotoRanking)field(a,"ranking")).journal.equals(before.choose(letter.equals("A")).journal))throw new AssertionError("photo choice mismatch");return true;});
        click(t,a,"直前の操作を取り消す");ready(t,a);
        main(t,()->{candidate(a,letter).performLongClick();return true;});
        main(t,()->{if((int)field(a,"index")!=expected)throw new AssertionError("long press mismatch");return true;});t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);ready(t,a);
      }
      // Unreadable candidate: no choice or tie becomes enabled and A/B remains accessible.
      PhotoRepository.Photo missing=new PhotoRepository.Photo(Uri.fromFile(new File(a.getCacheDir(),"comparison-intentionally-missing.jpg")),"missing.jpg","",0,0,0);
      main(t,()->{set(a,"photos",List.of(photos.get(0),missing));set(a,"burstMembers",List.of(0,1));invoke(a,"openRanking");return true;});
      waitFor(t,"failure",()->{ComparisonPhotoView x=candidate(a,"A"),y=candidate(a,"B");return x!=null&&(x.getContentDescription().toString().contains("読み込めません")||y.getContentDescription().toString().contains("読み込めません"));});
      main(t,()->{if(button(a.getWindow().getDecorView(),"Aを選ぶ").isEnabled()||button(a.getWindow().getDecorView(),"Bを選ぶ").isEnabled()||candidate(a,"A").isEnabled()||candidate(a,"B").isEnabled())throw new AssertionError("failed pair selectable");return true;});
      // Standalone identical-size visual fixtures, no original photos or OS bars in captures.
      for(int width:new int[]{320,400})for(float scale:new float[]{1f,1.3f})for(boolean horizontal:new boolean[]{false,true})for(boolean dark:new boolean[]{false,true}){
        LinearLayout[] pair={null};
        main(t,()->{Configuration cfg=new Configuration(a.getResources().getConfiguration());cfg.fontScale=scale;Context c=a.createConfigurationContext(cfg);
          LinearLayout outer=new LinearLayout(a);outer.setOrientation(1);outer.setPadding(0,GalleryUi.dp(a,64),0,0);outer.setBackgroundColor(GalleryUi.BG);
          pair[0]=new LinearLayout(c);pair[0].setOrientation(horizontal?0:1);
          for(String letter:new String[]{"A","B"}){ComparisonPhotoView v=new ComparisonPhotoView(c,letter,"fixture.jpg");Bitmap b=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888);b.eraseColor(dark?0xff0f0f10:Color.WHITE);bitmaps.add(b);v.setImageBitmap(b);v.loaded();pair[0].addView(v,new LinearLayout.LayoutParams(horizontal?0:-1,horizontal?-1:0,1));}
          outer.addView(pair[0],new LinearLayout.LayoutParams(GalleryUi.dp(c,width),GalleryUi.dp(c,420)));a.setContentView(outer);return true;});
        waitFor(t,"fixture",()->pair[0].getHeight()>0&&pair[0].getChildAt(0).getWidth()>0);
        main(t,()->{geometry((ComparisonPhotoView)pair[0].getChildAt(0));geometry((ComparisonPhotoView)pair[0].getChildAt(1));return true;});
        capture(t,a,pair[0],pair[0].getHeight(),"compare-"+width+"-"+scale+"-"+(horizontal?"h":"v")+"-"+(dark?"dark":"bright"));
      }
      main(t,()->{ComparisonPhotoView tiny=new ComparisonPhotoView(a,"A","tiny");Bitmap b=Bitmap.createBitmap(1000,1,Bitmap.Config.ARGB_8888);tiny.setImageBitmap(b);tiny.measure(View.MeasureSpec.makeMeasureSpec(300,1073741824),View.MeasureSpec.makeMeasureSpec(300,1073741824));tiny.layout(0,0,300,300);Bitmap target=Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888);tiny.draw(new Canvas(target));if(!((RectF)field(tiny,"badge")).isEmpty()||!tiny.getContentDescription().toString().startsWith("A、"))throw new AssertionError("tiny overflow");target.recycle();b.recycle();return true;});
      return "PASS actual comparison vertical/horizontal/vertical, A/B names and candidate correspondence, both buttons/photo taps/long presses/zoom/undo, unreadable pair disabled, 16 size/font/orientation/brightness fixtures and tiny image fallback. Private ranking store only. TalkBack speech and first-frame latency unmeasured.\n";
    }finally{main(t,()->{a.finish();return true;});prefs.edit().clear().commit();for(File f:files)f.delete();for(Bitmap b:bitmaps)b.recycle();}
  }
}
