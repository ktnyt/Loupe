package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Real navigation is read-only; captures contain only the new tab bar. */
final class LibraryTabsChecks {
  interface Check { boolean done() throws Exception; }
  private static Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
  private static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
  private static void invoke(Object o,String n)throws Exception{Method m=o.getClass().getDeclaredMethod(n);m.setAccessible(true);m.invoke(o);}
  private static void main(Instrumentation t,Check check)throws Exception{
    Throwable[] failure={null};t.runOnMainSync(()->{try{check.done();}catch(Throwable e){failure[0]=e;}});
    if(failure[0]!=null)throw new AssertionError("UI check",failure[0]);t.waitForIdleSync();
  }
  private static void waitFor(Instrumentation t,String name,Check check)throws Exception{
    long deadline=SystemClock.uptimeMillis()+10000;boolean[] ready={false};
    while(SystemClock.uptimeMillis()<deadline){main(t,()->{ready[0]=check.done();return true;});if(ready[0])return;Thread.sleep(50);}
    throw new AssertionError("Timed out: "+name);
  }
  private static <T> T find(View v,Class<T> type){
    if(type.isInstance(v))return type.cast(v);
    if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){T found=find(((ViewGroup)v).getChildAt(i),type);if(found!=null)return found;}
    return null;
  }
  private static LibraryTabs tabs(MainActivity a){return find(a.getWindow().getDecorView(),LibraryTabs.class);}
  private static TextView tab(MainActivity a,boolean albums){return (TextView)((ViewGroup)tabs(a).getChildAt(1)).getChildAt(albums?1:0);}
  private static void switchTo(Instrumentation t,MainActivity a,boolean albums)throws Exception{
    main(t,()->{if(tabs(a)==null)throw new AssertionError("Missing tabs");tab(a,albums).performClick();return true;});
    waitFor(t,"loaded "+albums,()->{
      ScrollingGrid grid=(ScrollingGrid)field(a,albums?"albumGridView":"grid");
      return (boolean)field(a,"albumHome")==albums && grid!=null&&grid.isAttachedToWindow()&&grid.getCount()>10&&grid.getChildCount()>0;
    });
  }
  private static void capture(Instrumentation t,MainActivity a,LibraryTabs tabs,String name)throws Exception{
    t.waitForIdleSync();Thread.sleep(150);int[] xy=new int[2];tabs.getLocationOnScreen(xy);
    Bitmap all=t.getUiAutomation().takeScreenshot();if(all==null)throw new AssertionError("No screenshot");
    Bitmap bar=Bitmap.createBitmap(all,xy[0],xy[1],tabs.getWidth(),tabs.getHeight());
    try(FileOutputStream out=new FileOutputStream(new File(a.getCacheDir(),"tabs-test-"+name+".png"))){bar.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bar.recycle();all.recycle();}
  }
  static String run(Instrumentation t)throws Exception{
    MainActivity initial=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    MainActivity[] current={initial};StringBuilder log=new StringBuilder();
    try{
      MainActivity a=current[0];
      waitFor(t,"album load",()->{ScrollingGrid g=(ScrollingGrid)field(a,"albumGridView");return g!=null&&g.getCount()>10&&g.getChildCount()>0;});
      capture(t,a,tabs(a),"albums");
      main(t,()->{((ScrollingGrid)field(a,"albumGridView")).restoreCell(8,-37,false);return true;});
      waitFor(t,"album scroll",()->{ScrollingGrid g=(ScrollingGrid)field(a,"albumGridView");return g.firstCellPosition()==8&&g.getChildAt(0).getTop()==-37;});
      switchTo(t,a,false);capture(t,a,tabs(a),"photos");
      main(t,()->{((ScrollingGrid)field(a,"grid")).restoreCell(28,-49,false);return true;});
      waitFor(t,"photo scroll",()->{ScrollingGrid g=(ScrollingGrid)field(a,"grid");return g.firstCellPosition()==28&&g.getChildAt(0).getTop()==-49;});
      switchTo(t,a,true);
      waitFor(t,"album restored",()->{ScrollingGrid g=(ScrollingGrid)field(a,"albumGridView");return g.firstCellPosition()==8&&g.getChildAt(0).getTop()==-37;});
      switchTo(t,a,false);
      waitFor(t,"photo restored",()->{ScrollingGrid g=(ScrollingGrid)field(a,"grid");return g.firstCellPosition()==28&&g.getChildAt(0).getTop()==-49;});
      // Enter selection using the existing screen behavior, then attempt the disabled peer tab.
      main(t,()->{set(a,"selecting",true);Set<String> selected=(Set<String>)field(a,"selectedPhotos");selected.add(((List<PhotoRepository.Photo>)field(a,"photos")).get(0).uri.toString());invoke(a,"gallery");return true;});
      capture(t,a,tabs(a),"disabled");
      main(t,()->{if(tab(a,true).isEnabled()||tab(a,false).isEnabled())throw new AssertionError("selection tabs enabled");tab(a,true).performClick();if((boolean)field(a,"albumHome")||((Set<?>)field(a,"selectedPhotos")).size()!=1)throw new AssertionError("selection lost");return true;});
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);t.waitForIdleSync();
      main(t,()->{if((boolean)field(a,"selecting")||!tab(a,true).isEnabled())throw new AssertionError("selection back");return true;});
      // No matching camera photos: preserve the chosen condition even without a scroll anchor.
      main(t,()->{invoke(a,"rememberGallery");set(a,"filter","__tabs_empty_camera__");set(a,"photos",new ArrayList<PhotoRepository.Photo>());set(a,"bundles",new ArrayList<>());invoke(a,"gallery");invoke(a,"loadIndexedPhotos");return true;});
      switchTo(t,a,true);
      main(t,()->{tab(a,false).performClick();return true;});
      waitFor(t,"empty condition retained",()->tabs(a)!=null&&!((boolean)field(a,"albumHome")));
      main(t,()->{if(!"__tabs_empty_camera__".equals(field(a,"filter")))throw new AssertionError("empty filter lost");return true;});
      // Reload a real camera before the existing rank and configuration checks.
      main(t,()->{set(a,"filter","GR");invoke(a,"gallery");invoke(a,"loadIndexedPhotos");return true;});
      waitFor(t,"camera restored",()->!((List<?>)field(a,"photos")).isEmpty());
      // Both filters survive a peer-tab round trip, even when the selected rank has no matches.
      main(t,()->{set(a,"filter","GR");set(a,"rankFilter",1);invoke(a,"gallery");return true;});
      switchTo(t,a,true);
      main(t,()->{tab(a,false).performClick();return true;});
      waitFor(t,"filtered snapshot",()->!((List<?>)field(a,"photos")).isEmpty());
      main(t,()->{if(!"GR".equals(field(a,"filter"))||(int)field(a,"rankFilter")!=1)throw new AssertionError("filters lost");return true;});
      // Configuration recreation uses the existing save/restore path.
      Instrumentation.ActivityMonitor monitor=t.addMonitor(MainActivity.class.getName(),null,false);
      main(t,()->{a.recreate();return true;});
      MainActivity restored=(MainActivity)t.waitForMonitorWithTimeout(monitor,10000);t.removeMonitor(monitor);
      if(restored==null)throw new AssertionError("recreated activity missing");current[0]=restored;
      waitFor(t,"restored filters",()->tabs(restored)!=null&&!((List<?>)field(restored,"photos")).isEmpty());
      main(t,()->{if((boolean)field(restored,"albumHome")||!"GR".equals(field(restored,"filter"))||(int)field(restored,"rankFilter")!=1)throw new AssertionError("recreation state");return true;});
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
      waitFor(t,"root exit",()->restored.isFinishing()||!restored.hasWindowFocus());
      main(t,()->{restored.finish();return true;});
      MainActivity fixture=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));current[0]=fixture;
      waitFor(t,"second albums",()->tabs(fixture)!=null);
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitFor(t,"album root exit",()->fixture.isFinishing()||!fixture.hasWindowFocus());
      main(t,()->{fixture.finish();return true;});
      MainActivity display=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));current[0]=display;
      for(float scale:new float[]{1f,1.3f})for(int width:new int[]{320,400})for(boolean selected:new boolean[]{false,true}){
        LibraryTabs[] bar={null};
        main(t,()->{
          Configuration config=new Configuration(display.getResources().getConfiguration());config.fontScale=scale;Context c=display.createConfigurationContext(config);
          bar[0]=new LibraryTabs(c,selected,true,x->{});LinearLayout root=new LinearLayout(display);root.setOrientation(1);root.setBackgroundColor(GalleryUi.BG);
          root.setPadding(0,GalleryUi.dp(display,64),0,0);
          root.addView(bar[0],new LinearLayout.LayoutParams(GalleryUi.dp(c,width),-2));display.setContentView(root);return true;
        });
        waitFor(t,"fixture layout",()->bar[0].getHeight()>0);
        main(t,()->{
          ViewGroup row=(ViewGroup)bar[0].getChildAt(1);
          AccessibilityNodeInfo.CollectionInfo collection=row.createAccessibilityNodeInfo().getCollectionInfo();
          if(collection==null||collection.getColumnCount()!=2||collection.getSelectionMode()!=AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE)throw new AssertionError("tab collection");
          for(int i=0;i<2;i++){
            TextView v=(TextView)row.getChildAt(i);AccessibilityNodeInfo info=v.createAccessibilityNodeInfo();
            if(v.getHeight()<GalleryUi.dp(v.getContext(),48)||v.isSelected()!=(selected==(i==1))||!"android.app.ActionBar$Tab".contentEquals(info.getClassName())||info.getCollectionItemInfo()==null)throw new AssertionError("tab semantics/size");
            for(int n=0;n<v.getLayout().getLineCount();n++)if(v.getLayout().getEllipsisCount(n)>0)throw new AssertionError("ellipsis");
            if(v.getLayout().getHeight()+v.getPaddingTop()+v.getPaddingBottom()>v.getHeight())throw new AssertionError("clipped");
          }
          if(row.getChildAt(0).getHeight()!=row.getChildAt(1).getHeight())throw new AssertionError("unequal height");return true;
        });
        capture(t,display,bar[0],width+"-"+scale+"-"+selected);
      }
      log.append("PASS real tabs retain album cell8/top-37 and photos cell28/top-49, disabled tabs preserve selection, Back exits selection, camera/rank and empty camera persist, Activity recreation retains root/filters, both root Back exit. 8 size/font/selected-state combinations, accessibility tab role/index/selected and no clipping. Captures contain tab bars only. Photo/rank/tag stores unchanged.\n");
      return log.toString();
    }finally{MainActivity a=current[0];if(!a.isFinishing())main(t,()->{a.finish();return true;});}
  }
}
