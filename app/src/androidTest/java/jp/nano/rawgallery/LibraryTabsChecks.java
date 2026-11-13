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
  private static TextView visibleLabel(View v){
    if(v.getVisibility()!=View.VISIBLE)return null;
    if(v instanceof TextView && v.getHeight()>0)return (TextView)v;
    if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){
      TextView found=visibleLabel(((ViewGroup)v).getChildAt(i));if(found!=null)return found;
    }
    return null;
  }
  private static LibraryTabs tabs(MainActivity a){return find(a.getWindow().getDecorView(),LibraryTabs.class);}
  private static View tab(MainActivity a,boolean albums){return tabs(a).destinationView(albums?1:0);}
  private static void switchTo(Instrumentation t,MainActivity a,boolean albums)throws Exception{
    main(t,()->{if(tabs(a)==null)throw new AssertionError("Missing tabs");tab(a,albums).performClick();return true;});
    waitFor(t,"loaded "+albums,()->{
      ScrollingGrid grid=(ScrollingGrid)field(a,albums?"albumGridView":"grid");
      return (boolean)field(a,"albumHome")==albums && grid!=null&&grid.isAttachedToWindow()&&tabs(a).selectedDestination()==(albums?1:0);
    });
    GalleryScreenChecks.settle(t,a);
  }
  private static void capture(Instrumentation t,MainActivity a,LibraryTabs tabs,String name)throws Exception{
    t.waitForIdleSync();Thread.sleep(150);int[] xy=new int[2];tabs.getLocationOnScreen(xy);
    Bitmap all=t.getUiAutomation().takeScreenshot();if(all==null)throw new AssertionError("No screenshot");
    Bitmap bar=Bitmap.createBitmap(all,xy[0],xy[1],tabs.getWidth(),tabs.getHeight());
    try(FileOutputStream out=new FileOutputStream(new File(a.getCacheDir(),"tabs-test-"+name+".png"))){bar.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bar.recycle();all.recycle();}
  }
  private static void checkSparseLibrary(Instrumentation t,MainActivity a)throws Exception{
    // These are activity-local committed filters; no index/store or MediaStore writes.
    main(t,()->{set(a,"filter","__tabs_empty_camera__");set(a,"rankFilter",1);invoke(a,"gallery");invoke(a,"loadIndexedPhotos");return true;});
    GalleryScreenChecks.settle(t,a);
    main(t,()->{if(!((List<?>)field(a,"photos")).isEmpty())throw new AssertionError("synthetic camera condition must be empty");return true;});
    capture(t,a,tabs(a),"sparse-photos");
    switchTo(t,a,true);GalleryScreenChecks.settle(t,a);
    main(t,()->{if(tabs(a).selectedDestination()!=1||!(boolean)field(a,"libraryAlbums"))throw new AssertionError("album destination disagrees with screen");return true;});
    capture(t,a,tabs(a),"sparse-albums");
    switchTo(t,a,false);GalleryScreenChecks.settle(t,a);
    main(t,()->{
      if((boolean)field(a,"albumHome")||(boolean)field(a,"libraryAlbums")||tabs(a).selectedDestination()!=0)throw new AssertionError("photo destination disagrees with screen");
      if(!"__tabs_empty_camera__".equals(field(a,"filter"))||(int)field(a,"rankFilter")!=1)throw new AssertionError("sparse filters lost");
      // Empty libraries cannot enter selection through the photo action. Exercise the
      // selection presentation explicitly, without fabricating a photo or selection URI.
      set(a,"selecting",true);invoke(a,"gallery");
      if(tabs(a)!=null&&tabs(a).getVisibility()==View.VISIBLE)throw new AssertionError("selection navigation visible");
      return true;
    });
    t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);t.waitForIdleSync();
    main(t,()->{
      if((boolean)field(a,"selecting")||tabs(a)==null||tabs(a).getVisibility()!=View.VISIBLE||!tab(a,true).isEnabled())throw new AssertionError("sparse selection Back did not restore navigation");
      if(!"__tabs_empty_camera__".equals(field(a,"filter"))||(int)field(a,"rankFilter")!=1)throw new AssertionError("selection Back lost filters");
      return true;
    });
  }
  static String run(Instrumentation t)throws Exception{
    MainActivity initial=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    MainActivity[] current={initial};StringBuilder log=new StringBuilder();
    try{
      MainActivity a=current[0];
      GalleryScreenChecks.finishInitialIndex(t,a);
      waitFor(t,"album load",()->{ScrollingGrid g=(ScrollingGrid)field(a,"albumGridView");return g!=null&&g.isAttachedToWindow()&&tabs(a)!=null;});
      int[] counts=new int[2];
      main(t,()->{ScrollingGrid g=(ScrollingGrid)field(a,"albumGridView");counts[0]=g.getCount()-g.getHeaderViewsCount();return true;});
      switchTo(t,a,false);GalleryScreenChecks.settle(t,a);
      main(t,()->{ScrollingGrid g=(ScrollingGrid)field(a,"grid");counts[1]=g.getCount()-g.getHeaderViewsCount();return true;});
      boolean rich=counts[0]>10&&counts[1]>10;
      if(rich){
      switchTo(t,a,true);GalleryScreenChecks.settle(t,a);
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

      main(t,()->{if(tabs(a)!=null&&tabs(a).getVisibility()==View.VISIBLE)throw new AssertionError("selection navigation visible");if((boolean)field(a,"albumHome")||((Set<?>)field(a,"selectedPhotos")).size()!=1)throw new AssertionError("selection lost");return true;});
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
      log.append("PASS rich-data navigation retains album cell8/top-37 and photos cell28/top-49, selection hiding/Back, filters, recreation and root Back exits.\n");
      }else{
        checkSparseLibrary(t,a);
        log.append("PASS sparse-library native photo/album navigation, empty-result camera/rank filter retention, synthetic selection-state navigation hiding and Back restoration. Observed album rows=").append(counts[0]).append(", photo rows=").append(counts[1]).append(". UNVERIFIED scroll restoration: insufficient fixture data for album cell8/photo cell28 offsets; rich-data checks were not executed.\n");
        main(t,()->{a.finish();return true;});
      }
      MainActivity display=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));current[0]=display;
      for(float scale:new float[]{1f,1.3f})for(int width:new int[]{320,400})for(boolean selected:new boolean[]{false,true}){
        LibraryTabs[] bar={null};
        main(t,()->{
          Configuration config=new Configuration(display.getResources().getConfiguration());config.fontScale=scale;Context c=new android.view.ContextThemeWrapper(display.createConfigurationContext(config),R.style.AppTheme);
          bar[0]=new LibraryTabs(c,selected,true,x->{});LinearLayout root=new LinearLayout(display);root.setOrientation(1);root.setBackgroundColor(GalleryUi.BG);
          root.setPadding(0,GalleryUi.dp(display,64),0,0);
          root.addView(bar[0],new LinearLayout.LayoutParams(GalleryUi.dp(c,width),-2));display.setContentView(root);return true;
        });
        waitFor(t,"fixture layout",()->bar[0].getHeight()>0);
        main(t,()->{
          ViewGroup row=bar[0].getMenuViewGroup();
          AccessibilityNodeInfo.CollectionInfo collection=row.createAccessibilityNodeInfo().getCollectionInfo();
          if(collection==null||collection.getColumnCount()!=3||collection.getSelectionMode()!=AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE)throw new AssertionError("tab collection");
          for(int i=0;i<3;i++){
            View v=bar[0].destinationView(i);AccessibilityNodeInfo info=v.createAccessibilityNodeInfo();
            TextView label=visibleLabel(v);if(label==null)throw new AssertionError("tab label missing");
            if(v.getHeight()<GalleryUi.dp(v.getContext(),48)||v.isSelected()!=((selected?1:0)==i)||androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(info).getRoleDescription()==null||info.getCollectionItemInfo()==null)throw new AssertionError("tab semantics/size");
            for(int n=0;n<label.getLayout().getLineCount();n++)if(label.getLayout().getEllipsisCount(n)>0)throw new AssertionError("ellipsis");
            if(label.getLayout().getHeight()+label.getPaddingTop()+label.getPaddingBottom()>label.getHeight())throw new AssertionError("clipped");
          }
          if(row.getChildAt(0).getHeight()!=row.getChildAt(1).getHeight())throw new AssertionError("unequal height");return true;
        });
        capture(t,display,bar[0],width+"-"+scale+"-"+selected);
      }
      log.append("PASS 8 size/font/selected-state combinations, accessibility navigation role/index/selected and no clipping. Captures contain navigation bars only. Photo/rank/tag stores unchanged.\n");
      return log.toString();
    }finally{MainActivity a=current[0];if(!a.isFinishing())main(t,()->{a.finish();return true;});}
  }
}
