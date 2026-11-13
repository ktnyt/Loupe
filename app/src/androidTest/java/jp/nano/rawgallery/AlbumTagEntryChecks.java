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
  static Activity openTagsViaMenu(Instrumentation t,MainActivity a,boolean viewer)throws Exception{
    main(t,()->{Button entry=button(a.getWindow().getDecorView(),viewer?"写真のその他の操作":"設定とその他");if(entry==null)throw new AssertionError("menu entry missing");entry.performClick();return true;});
    View menu=ShareScreenChecks.modal(t,a);waitFor(t,"tag menu item",()->button(menu,"タグ")!=null);
    Instrumentation.ActivityMonitor monitor=t.addMonitor(TagActivity.class.getName(),null,false);
    try{main(t,()->{button(menu,"タグ").performClick();return true;});Activity result=t.waitForMonitorWithTimeout(monitor,10000);if(result==null)throw new AssertionError("TagActivity missing");return result;}finally{t.removeMonitor(monitor);}
  }
  static String run(Instrumentation t)throws Exception{
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    Activity[] tagScreen={null};StringBuilder result=new StringBuilder();
    try{
      GalleryScreenChecks.finishInitialIndex(t,a);
      main(t,()->{Method m=MainActivity.class.getDeclaredMethod("albumHome");m.setAccessible(true);m.invoke(a);return true;});
      GalleryScreenChecks.settle(t,a);waitFor(t,"album list loaded",()->field(a,"albumGridView")!=null);
      java.util.List<GalleryIndex.Album> albums;try(GalleryIndex catalog=new GalleryIndex(a)){albums=catalog.albums();}
      main(t,()->{ScrollingGrid g=(ScrollingGrid)field(a,"albumGridView");g.restoreCell(8,-37,false);return true;});GalleryScreenChecks.settle(t,a);
      int[] position={0},top={0},rows={0};boolean[] exactScroll={false};
      main(t,()->{ScrollingGrid g=(ScrollingGrid)field(a,"albumGridView");rows[0]=g.getCount();position[0]=g.firstCellPosition();top[0]=g.getChildCount()==0?0:g.getChildAt(0).getTop();exactScroll[0]=position[0]==8&&top[0]==-37;return true;});
      if(rows[0]>10&&!exactScroll[0])throw new AssertionError("deep album scroll cell8/top-37 failed despite sufficient indexed rows: "+rows[0]);
      if(!exactScroll[0])result.append("SKIP deep album scroll cell8/top-37: indexed albums=").append(albums.size()).append("; viewport could not reach that position. Actual position ").append(position[0]).append('/').append(top[0]).append(" will be checked on return.\n");
      View root=(View)field(a,"root");capture(t,a,root,root.getHeight(),"real-library");
      main(t,()->{set(a,"albumFolder","/__stale_album__");set(a,"filter","GR");return true;});
      long start=SystemClock.uptimeMillis();Activity tag=openTagsViaMenu(t,a,false);tagScreen[0]=tag;
      waitFor(t,"tag screen visible",()->tag.hasWindowFocus()&&button(tag.getWindow().getDecorView(),"新しいタグ")!=null);
      result.append("Library menu to focused tag screen: ").append(SystemClock.uptimeMillis()-start).append(" ms (polling upper bound, not first GPU frame).\n");
      Intent intent=tag.getIntent();if(intent.getStringExtra("folder")!=null||!"すべて".equals(intent.getStringExtra("camera"))||intent.hasExtra("photo"))throw new AssertionError("root scope leaked");
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
      waitFor(t,"Back restores actual album position",()->{ScrollingGrid g=(ScrollingGrid)field(a,"albumGridView");int y=g.getChildCount()==0?0:g.getChildAt(0).getTop();return a.hasWindowFocus()&&(boolean)field(a,"albumHome")&&g.firstCellPosition()==position[0]&&y==top[0];});
      // Drive the same native library menu from a scoped gallery.
      main(t,()->{set(a,"albumHome",false);set(a,"albumFolder","/__existing_scope__");set(a,"filter","GR");Method m=MainActivity.class.getDeclaredMethod("gallery");m.setAccessible(true);m.invoke(a);return true;});GalleryScreenChecks.settle(t,a);
      Activity scoped=openTagsViaMenu(t,a,false);tagScreen[0]=scoped;
      if(!"/__existing_scope__".equals(scoped.getIntent().getStringExtra("folder"))||!"GR".equals(scoped.getIntent().getStringExtra("camera")))throw new AssertionError("existing scope changed");
      main(t,()->{scoped.finish();return true;});waitFor(t,"main focus",()->a.hasWindowFocus());
      // Use a real indexed cover if present; no retired recent-photo state is manufactured.
      PhotoRepository.Photo cover=null;for(GalleryIndex.Album album:albums)if(!album.cover.isVideo()){cover=album.cover;break;}
      if(cover==null)result.append("SKIP real viewer tag entry: no indexed non-video album cover is available.\n");
      else{
        PhotoRepository.Photo photo=cover;
        main(t,()->{set(a,"photos",new java.util.ArrayList<>(java.util.List.of(photo)));set(a,"albumHome",true);return true;});ViewerScreenChecks.open(t,a,0);
        Activity fromPhoto=openTagsViaMenu(t,a,true);tagScreen[0]=fromPhoto;
        if(!"/__existing_scope__".equals(fromPhoto.getIntent().getStringExtra("folder"))||!"GR".equals(fromPhoto.getIntent().getStringExtra("camera"))||!photo.uri.toString().equals(fromPhoto.getIntent().getStringExtra("photo")))throw new AssertionError("viewer-photo scope changed");
        main(t,()->{fromPhoto.finish();return true;});waitFor(t,"viewer resumed",()->a.hasWindowFocus());
        t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitFor(t,"viewer back",()->!(boolean)field(a,"detail"));
        result.append("PASS real viewer secondary menu retains photo/folder/camera scope.\n");
      }
      for(int width:new int[]{320,400})for(float scale:new float[]{1f,1.3f}){
        View[] controls={null};
        main(t,()->{
          Configuration config=new Configuration(a.getResources().getConfiguration());config.fontScale=scale;Context c=new android.view.ContextThemeWrapper(a.createConfigurationContext(config),R.style.AppTheme);
          LinearLayout outer=new LinearLayout(a);outer.setOrientation(1);outer.setPadding(0,GalleryUi.dp(a,64),0,0);outer.setBackgroundColor(GalleryUi.BG);
          LinearLayout group=new LinearLayout(c);group.setOrientation(1);
          group.addView(LibraryUi.toolbar(c,"アルバム",LibraryUi.icon(c,R.drawable.ic_settings,"設定とその他",()->{})));
          group.addView(new LibraryTabs(c,true,true,x->{}));outer.addView(group,new LinearLayout.LayoutParams(GalleryUi.dp(c,width),-2));a.setContentView(outer);controls[0]=group;return true;
        });
        waitFor(t,"controls layout",()->controls[0].getHeight()>0);
        main(t,()->{Button b=button(controls[0],"設定とその他");if(b==null||b.getHeight()<GalleryUi.dp(b.getContext(),48))throw new AssertionError("menu name/size");TagDetailChecks.inspect(controls[0]);return true;});
        capture(t,a,controls[0],controls[0].getHeight(),width+"-"+scale);
      }
      return "PASS native library menu root scope ignores stale album/camera; actual TagActivity and Back preserve observed album position; scoped gallery menu retained; 4 width/font toolbar+navigation combinations. Any sparse-data skips are listed above. Tag data unmodified; no create/inference/edit actions. TalkBack speech not tested.\n"+result;
    }finally{main(t,()->{if(tagScreen[0]!=null&&!tagScreen[0].isFinishing())tagScreen[0].finish();a.finish();return true;});}
  }
}
