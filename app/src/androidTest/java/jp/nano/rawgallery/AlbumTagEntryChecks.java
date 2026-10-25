package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;

/** Entry/return checks only; never create, infer, edit or delete user tags. */
final class AlbumTagEntryChecks {
  interface Check { boolean run() throws Exception; }
  static Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
  static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
  static void main(Instrumentation t,Check c)throws Exception{
    Throwable[] error={null};t.runOnMainSync(()->{try{c.run();}catch(Throwable e){error[0]=e;}});
    if(error[0]!=null)throw new AssertionError("UI check",error[0]);t.waitForIdleSync();
  }
  static void waitFor(Instrumentation t,String name,Check c)throws Exception{
    long end=SystemClock.uptimeMillis()+10000;boolean[] ready={false};
    while(SystemClock.uptimeMillis()<end){main(t,()->{ready[0]=c.run();return true;});if(ready[0])return;Thread.sleep(50);}
    throw new AssertionError(name);
  }
  static Button button(View v,String name){
    if(v instanceof Button&&(name.contentEquals(((Button)v).getText())||name.contentEquals(v.getContentDescription())))return (Button)v;
    if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button b=button(((ViewGroup)v).getChildAt(i),name);if(b!=null)return b;}
    return null;
  }
  static void capture(Instrumentation t,Activity a,View view,int height,String name)throws Exception{
    t.waitForIdleSync();Thread.sleep(150);int[] xy=new int[2];view.getLocationOnScreen(xy);
    Bitmap screen=t.getUiAutomation().takeScreenshot();Bitmap crop=Bitmap.createBitmap(screen,xy[0],xy[1],view.getWidth(),height);
    try(FileOutputStream out=new FileOutputStream(new File(a.getCacheDir(),"album-tags-"+name+".png"))){crop.compress(Bitmap.CompressFormat.PNG,100,out);}finally{crop.recycle();screen.recycle();}
  }
  static String run(Instrumentation t)throws Exception{
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    Activity[] tagScreen={null};StringBuilder result=new StringBuilder();
    try{
      waitFor(t,"album loaded",()->{ScrollingGrid g=(ScrollingGrid)field(a,"albumGridView");return g!=null&&g.getChildCount()>0&&g.getCount()>10;});
      Button entry=button(a.getWindow().getDecorView(),"タグ");if(entry==null)throw new AssertionError("No entry");
      View dock=(View)entry.getParent();ViewGroup root=(ViewGroup)dock.getParent();View tabs=root.getChildAt(root.getChildCount()-1);
      capture(t,a,dock,dock.getHeight()+tabs.getHeight(),"real");
      main(t,()->{((ScrollingGrid)field(a,"albumGridView")).restoreCell(8,-37,false);set(a,"albumFolder","/__stale_album__");set(a,"filter","GR");return true;});
      waitFor(t,"album scrolled",()->{ScrollingGrid g=(ScrollingGrid)field(a,"albumGridView");return g.firstCellPosition()==8&&g.getChildAt(0).getTop()==-37;});
      Instrumentation.ActivityMonitor monitor=t.addMonitor(TagActivity.class.getName(),null,false);
      long start=SystemClock.uptimeMillis();
      main(t,()->{entry.performClick();return true;});
      Activity tag=t.waitForMonitorWithTimeout(monitor,10000);t.removeMonitor(monitor);tagScreen[0]=tag;
      if(tag==null)throw new AssertionError("TagActivity missing");
      waitFor(t,"tag screen visible",()->tag.hasWindowFocus()&&button(tag.getWindow().getDecorView(),"新しいタグ")!=null);
      result.append("Entry click to focused tag screen observed: ").append(SystemClock.uptimeMillis()-start).append(" ms (polling upper bound, not first GPU frame).\n");
      Intent intent=tag.getIntent();
      if(intent.getStringExtra("folder")!=null||!"すべて".equals(intent.getStringExtra("camera"))||intent.hasExtra("photo"))throw new AssertionError("root scope leaked");
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
      waitFor(t,"Back restores position",()->{ScrollingGrid g=(ScrollingGrid)field(a,"albumGridView");return a.hasWindowFocus()&&(boolean)field(a,"albumHome")&&g.firstCellPosition()==8&&g.getChildAt(0).getTop()==-37;});
      // The existing non-root entry retains its scope.
      main(t,()->{set(a,"albumHome",false);set(a,"albumFolder","/__existing_scope__");set(a,"filter","GR");return true;});
      monitor=t.addMonitor(TagActivity.class.getName(),null,false);
      main(t,()->{Method m=MainActivity.class.getDeclaredMethod("openTags",PhotoRepository.Photo.class);m.setAccessible(true);m.invoke(a,new Object[]{null});return true;});
      Activity scoped=t.waitForMonitorWithTimeout(monitor,10000);t.removeMonitor(monitor);tagScreen[0]=scoped;
      if(scoped==null||!"/__existing_scope__".equals(scoped.getIntent().getStringExtra("folder"))||!"GR".equals(scoped.getIntent().getStringExtra("camera")))throw new AssertionError("existing scope changed");
      main(t,()->{scoped.finish();set(a,"albumHome",true);return true;});
      waitFor(t,"main focus",()->a.hasWindowFocus());
      // Recent-photo detail keeps albumHome=true: exercise its actual showPhoto and tag button.
      java.util.List<PhotoRepository.Photo> recent;
      try(GalleryIndex catalog=new GalleryIndex(a)){recent=catalog.recent(1);}
      if(recent.isEmpty())throw new AssertionError("recent fixture missing");
      PhotoRepository.Photo recentPhoto=recent.get(0);
      main(t,()->{set(a,"photos",new java.util.ArrayList<>(recent));set(a,"recentPhoto",true);Method m=MainActivity.class.getDeclaredMethod("showPhoto",int.class);m.setAccessible(true);m.invoke(a,0);return true;});
      waitFor(t,"recent detail",()->button(a.getWindow().getDecorView(),"タグ")!=null&&(boolean)field(a,"detail"));
      monitor=t.addMonitor(TagActivity.class.getName(),null,false);
      main(t,()->{button(a.getWindow().getDecorView(),"タグ").performClick();return true;});
      Activity fromRecent=t.waitForMonitorWithTimeout(monitor,10000);t.removeMonitor(monitor);tagScreen[0]=fromRecent;
      if(fromRecent==null||!"/__existing_scope__".equals(fromRecent.getIntent().getStringExtra("folder"))||!"GR".equals(fromRecent.getIntent().getStringExtra("camera"))||!recentPhoto.uri.toString().equals(fromRecent.getIntent().getStringExtra("photo")))throw new AssertionError("recent-photo scope changed");
      main(t,()->{fromRecent.finish();return true;});waitFor(t,"recent resumed",()->a.hasWindowFocus());
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitFor(t,"recent back",()->!(boolean)field(a,"detail"));
      for(int width:new int[]{320,400})for(float scale:new float[]{1f,1.3f}){
        View[] controls={null};
        main(t,()->{
          Configuration config=new Configuration(a.getResources().getConfiguration());config.fontScale=scale;Context c=a.createConfigurationContext(config);
          LinearLayout outer=new LinearLayout(a);outer.setOrientation(1);outer.setPadding(0,GalleryUi.dp(a,64),0,0);outer.setBackgroundColor(GalleryUi.BG);
          LinearLayout group=new LinearLayout(c);group.setOrientation(1);
          group.addView(GalleryUi.dock(c,GalleryUi.button(c,"タグ",()->{}),GalleryUi.button(c,"その他",()->{})));
          group.addView(new LibraryTabs(c,true,true,x->{}));outer.addView(group,new LinearLayout.LayoutParams(GalleryUi.dp(c,width),-2));a.setContentView(outer);controls[0]=group;return true;
        });
        waitFor(t,"controls layout",()->controls[0].getHeight()>0);
        main(t,()->{for(String name:new String[]{"タグ","その他"}){Button b=button(controls[0],name);if(!name.contentEquals(b.getContentDescription())||b.getHeight()<GalleryUi.dp(b.getContext(),64))throw new AssertionError("name/size");if(b.getLayout().getHeight()+b.getCompoundPaddingTop()+b.getCompoundPaddingBottom()>b.getHeight())throw new AssertionError("clipped label");}return true;});
        capture(t,a,controls[0],controls[0].getHeight(),width+"-"+scale);
      }
      return result+"PASS root ignores stale album/camera, actual TagActivity and Back restore cell8/top-37, existing scoped/recent-photo entries unchanged, 4 width/font dock+tab combinations. Tag data unmodified; no create/inference/edit actions. TalkBack speech not tested.\n";
    }finally{main(t,()->{if(tagScreen[0]!=null&&!tagScreen[0].isFinishing())tagScreen[0].finish();a.finish();return true;});}
  }
}
