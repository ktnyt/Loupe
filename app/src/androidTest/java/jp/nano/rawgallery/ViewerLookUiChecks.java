package jp.nano.rawgallery;


import android.app.*;

import android.content.*;

import android.database.Cursor;

import android.graphics.*;

import android.net.Uri;

import android.os.*;

import android.provider.MediaStore;

import android.view.*;

import android.widget.*;

import org.json.*;

import java.io.*;

import java.nio.file.Files;

import java.util.*;

import java.util.concurrent.*;

import java.util.function.BooleanSupplier;


/** Actual viewer/editor windows and an isolated synthetic original. */
final class ViewerLookUiChecks {
  private static void require(boolean v,String s){if(!v)throw new AssertionError(s);
}
  private static Object field(Object o,String name){try{java.lang.reflect.Field f=o.getClass().getDeclaredField(name);
f.setAccessible(true);
return f.get(o);
}catch(ReflectiveOperationException e){throw new AssertionError(e);
}}
  private static Dialog presetDialog(RawEditActivity a){return (Dialog)field(a,"presetDialog");
}
  private static Dialog viewerDialog(PhotoViewerUi v){return (Dialog)field(v.lookUi,"sheet");
}
  private static void await(Instrumentation t,long ms,BooleanSupplier condition,String message)throws Exception{android.util.Log.i("LoupeLookTest","wait "+message);
long end=SystemClock.uptimeMillis()+ms;
while(SystemClock.uptimeMillis()<end){boolean[] ok={false};
TagDetailChecks.ui(t,()->ok[0]=condition.getAsBoolean());
if(ok[0]){android.util.Log.i("LoupeLookTest","ready "+message);
return;
}Thread.sleep(50);
}android.util.Log.i("LoupeLookTest","timeout "+message);
throw new AssertionError(message);
}
  private static <T> T findType(View root,Class<T> type){if(type.isInstance(root))return type.cast(root);
if(root instanceof ViewGroup)for(int i=0;
i<((ViewGroup)root).getChildCount();
i++){T result=findType(((ViewGroup)root).getChildAt(i),type);
if(result!=null)return result;
}return null;
}
  private static void progress(View v,float value){Bundle b=new Bundle();
b.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,value);
require(v.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),b),"editor pad accessibility progress");
}
  private static View find(Dialog d,String tag){return d==null||d.getWindow()==null?null:d.getWindow().getDecorView().findViewWithTag(tag);
}
  private static void click(Instrumentation t,View view,String what)throws Exception{TagDetailChecks.ui(t,()->{require(view!=null&&view.isEnabled(),what);
boolean checked=view instanceof android.widget.CompoundButton&&((android.widget.CompoundButton)view).isChecked();
boolean handled=view.performClick();
require(handled||view instanceof android.widget.CompoundButton&&((android.widget.CompoundButton)view).isChecked()!=checked,what);
});
t.waitForIdleSync();
}
  private static View windowButton(String label){for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews()){Button b=TagDetailChecks.button(root,label);
if(b!=null&&b.isShown())return b;
}return null;
}
  private static void reveal(Instrumentation t,View view)throws Exception{TagDetailChecks.ui(t,()->{require(view!=null,"view exists");
view.requestRectangleOnScreen(new Rect(0,0,view.getWidth(),view.getHeight()),true);
});
t.waitForIdleSync();
}
  private static void checkTarget(View v,Activity a,String name){require(v!=null&&v.getWidth()>=GalleryUi.dp(a,48)&&v.getHeight()>=GalleryUi.dp(a,48),name+" target >=48dp");
Rect r=new Rect();
require(v.getGlobalVisibleRect(r)&&r.width()>=v.getWidth()-1&&r.height()>=v.getHeight()-1,name+" fully visible");
}
  private static void settled(Instrumentation t,PhotoViewerUi v,String id)throws Exception{await(t,90000,()->!v.lookUi.controller.busy()&&(id==null?v.lookUi.controller.applied()==null:v.lookUi.controller.applied()!=null&&id.equals(v.lookUi.controller.applied().id)),"Look committed "+id);
}
  private static Bitmap fixture(int w,int h){Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
Canvas c=new Canvas(b);
Paint p=new Paint(3);
p.setShader(new LinearGradient(0,0,0,h*.52f,0xff86a8c8,0xffc9d6de,Shader.TileMode.CLAMP));
c.drawRect(0,0,w,h*.53f,p);
p.setShader(new LinearGradient(0,h*.53f,0,h,0xff7d8f6a,0xff4e5d40,Shader.TileMode.CLAMP));
c.drawRect(0,h*.53f,w,h,p);
p.setShader(null);
p.setColor(0xffe8d2b0);
c.drawOval(w*.22f,h*.38f,w*.50f,h*.78f,p);
p.setColor(0xffc9573e);
c.drawOval(w*.61f,h*.22f,w*.79f,h*.46f,p);
return b;
}
  private static Set<Long> outputs(ContentResolver r,String owner){Set<Long> out=new HashSet<>();
String[] cols={MediaStore.Images.Media._ID};
String selection=MediaStore.Images.Media.OWNER_PACKAGE_NAME+"=? AND "+MediaStore.Images.Media.RELATIVE_PATH+" IN (?,?) AND "+MediaStore.Images.Media.MIME_TYPE+"=? AND "+MediaStore.Images.Media.DISPLAY_NAME+" LIKE ?";
try(Cursor c=r.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cols,selection,new String[]{owner,"Pictures/LoupeDevelop","Pictures/LoupeDevelop/","image/jpeg","Loupe-%.jpg"},null)){if(c!=null)while(c.moveToNext())out.add(c.getLong(0));
}return out;
}
  private static void storageBarrier()throws Exception{java.lang.reflect.Field f=PhotoLookController.class.getDeclaredField("STORAGE");
f.setAccessible(true);
((ExecutorService)f.get(null)).submit(()->{}).get(30,TimeUnit.SECONDS);
}
  private static Bitmap copy(Instrumentation t,Window w)throws Exception{CountDownLatch frame=new CountDownLatch(1);
TagDetailChecks.ui(t,()->{View d=w.getDecorView();
d.getViewTreeObserver().registerFrameCommitCallback(frame::countDown);
d.invalidate();
});
require(frame.await(5,TimeUnit.SECONDS),"window frame committed");
Bitmap[] bitmap={null};
int[] status={-1};
CountDownLatch done=new CountDownLatch(1);
TagDetailChecks.ui(t,()->{View d=w.getDecorView();
bitmap[0]=Bitmap.createBitmap(d.getWidth(),d.getHeight(),Bitmap.Config.ARGB_8888);
PixelCopy.request(w,bitmap[0],result->{status[0]=result;
done.countDown();
},new Handler(Looper.getMainLooper()));
});
require(done.await(5,TimeUnit.SECONDS)&&status[0]==PixelCopy.SUCCESS,"PixelCopy actual window");
return bitmap[0];
}
  private static JSONArray rect(Rect r){try{return new JSONArray(new int[]{r.left,r.top,r.right,r.bottom});
}catch(JSONException e){throw new AssertionError(e);
}}
  private static Rect bounds(View v){int[] xy=new int[2];
v.getLocationOnScreen(xy);
return new Rect(xy[0],xy[1],xy[0]+v.getWidth(),xy[1]+v.getHeight());
}
  private static void capture(Instrumentation t,Activity a,Dialog d,String name,boolean shortWindow)throws Exception{
    // Reference states exclude the preceding LENGTH_SHORT success toast.
    Thread.sleep(2500);
    Rect[] crop={null};
int[] app={0,0},dialog={0,0},underOrigin={0,0};
Window[] under={null};
JSONObject metadata=new JSONObject();

    TagDetailChecks.ui(t,()->{View decor=a.getWindow().getDecorView(),root=((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);
decor.getLocationOnScreen(app);
Rect r=bounds(root);
r.left+=root.getPaddingLeft();
r.top+=root.getPaddingTop();
r.right-=root.getPaddingRight();
r.bottom-=root.getPaddingBottom();
crop[0]=new Rect(r);
crop[0].offset(-app[0],-app[1]);
if(a instanceof RawEditActivity){Dialog lower=(Dialog)field(a,"lookDialog");
if(lower!=null&&lower.isShowing()&&lower!=d){under[0]=lower.getWindow();
View underView=under[0].getDecorView();
Rect ub=bounds(underView);
int deltaUnder=shortWindow?r.bottom-ub.bottom:0;
if(deltaUnder!=0){WindowManager.LayoutParams up=under[0].getAttributes();
up.y-=deltaUnder;
under[0].setAttributes(up);
}try{metadata.put("underlying_dialog_before_screen_px",rect(ub));
metadata.put("underlying_dialog_fixture_delta_y_px",deltaUnder);
}catch(JSONException ex){throw new AssertionError(ex);
}}}Window dw=d.getWindow();
View overlay=dw.getDecorView();
Rect before=bounds(overlay);
int oldY=dw.getAttributes().y;
int delta=shortWindow?r.bottom-before.bottom:0;
if(delta!=0){WindowManager.LayoutParams lp=dw.getAttributes();
lp.y-=delta;
dw.setAttributes(lp);
}try{metadata.put("transient_feedback_wait_ms",2500);
metadata.put("density",a.getResources().getDisplayMetrics().density);
metadata.put("font",a.getResources().getConfiguration().fontScale);
metadata.put("activity_content_screen_px",rect(r));
metadata.put("dialog_before_screen_px",rect(before));
metadata.put("dialog_before_window_y_px",oldY);
metadata.put("dialog_fixture_delta_y_px",delta);
metadata.put("fixture_short_window",shortWindow);
metadata.put("composition","actual windows, screen-coordinate alignment");
}catch(JSONException e){throw new AssertionError(e);
}});

    t.waitForIdleSync();
Thread.sleep(150);
TagDetailChecks.ui(t,()->{View overlay=d.getWindow().getDecorView();
overlay.getLocationOnScreen(dialog);
try{if(under[0]!=null){under[0].getDecorView().getLocationOnScreen(underOrigin);
metadata.put("underlying_dialog_after_screen_px",rect(bounds(under[0].getDecorView())));
}metadata.put("window_pixelcopy_dim_limitation","system compositor dim may not appear in individual window copies");
metadata.put("dialog_dim_amount",d.getWindow().getAttributes().dimAmount);
metadata.put("dialog_dim_flag",(d.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND)!=0);
metadata.put("dialog_after_screen_px",rect(bounds(overlay)));
metadata.put("dialog_after_window_y_px",d.getWindow().getAttributes().y);
View s=overlay.findViewWithTag("viewer-look-sheet");
if(s==null)s=overlay.findViewWithTag("look-preset-dialog");
metadata.put("scroll_y",s instanceof ScrollView?((ScrollView)s).getScrollY():0);
}catch(JSONException e){throw new AssertionError(e);
}});

    Bitmap image=copy(t,a.getWindow()),layer=null,underLayer=null,cropped=null;
try{if(under[0]!=null){underLayer=copy(t,under[0]);
new Canvas(image).drawBitmap(underLayer,underOrigin[0]-app[0],underOrigin[1]-app[1],null);
}layer=copy(t,d.getWindow());
new Canvas(image).drawBitmap(layer,dialog[0]-app[0],dialog[1]-app[1],null);
Rect r=crop[0];
require(r.left>=0&&r.top>=0&&r.right<=image.getWidth()&&r.bottom<=image.getHeight(),"capture crop inside window");
cropped=Bitmap.createBitmap(image,r.left,r.top,r.width(),r.height());
File dir=new File(a.getCacheDir(),"viewer-look-ui");
require(dir.isDirectory()||dir.mkdirs(),"capture directory");
try(OutputStream o=new FileOutputStream(new File(dir,name+"-windows.png"))){require(cropped.compress(Bitmap.CompressFormat.PNG,100,o),"window capture PNG");}
Bitmap display=t.getUiAutomation().takeScreenshot();require(display!=null,"actual compositor screenshot");
Bitmap actual=null;
try{int x=r.left+app[0],y=r.top+app[1];require(x>=0&&y>=0&&x+r.width()<=display.getWidth()&&y+r.height()<=display.getHeight(),"actual compositor crop bounds");try(OutputStream full=new FileOutputStream(new File(dir,name+"-display.png"))){require(display.compress(Bitmap.CompressFormat.PNG,100,full),"full physical display PNG");}actual=Bitmap.createBitmap(display,x,y,r.width(),r.height());try(OutputStream o=new FileOutputStream(new File(dir,name+".png"))){require(actual.compress(Bitmap.CompressFormat.PNG,100,o),"actual compositor PNG");}metadata.put("primary_capture","actual compositor screenshot cropped to Activity content; includes system dim");metadata.put("pixelcopy_artifact",name+"-windows.png");}finally{if(actual!=null&&actual!=display)actual.recycle();display.recycle();}
try(Writer o=new FileWriter(new File(dir,name+".json"))){o.write(metadata.toString(2));
}}finally{if(cropped!=null&&cropped!=image)cropped.recycle();
if(layer!=null)layer.recycle();
if(underLayer!=null)underLayer.recycle();
image.recycle();
}
  }
  private static void constrain(Instrumentation t,Activity a,int width,boolean shortWindow)throws Exception{TagDetailChecks.ui(t,()->{WindowManager.LayoutParams p=a.getWindow().getAttributes();
p.width=GalleryUi.dp(a,width);
if(shortWindow){View root=((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);
p.height=GalleryUi.dp(a,640);
}a.getWindow().setAttributes(p);
});
t.waitForIdleSync();
if(shortWindow)await(t,5000,()->{View root=((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);return root.getHeight()-root.getPaddingTop()-root.getPaddingBottom()==GalleryUi.dp(a,640);},"exact 640dp content height");
}
  static String run(Instrumentation t,int width,float font)throws Exception{
    require(width>=320&&GalleryUi.dp(t.getTargetContext(),width)<=t.getTargetContext().getResources().getDisplayMetrics().widthPixels,"capture width must fit the actual display");
boolean shortWindow=font>1.1f;
String prefix=shortWindow?"short":"normal";
MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));

    LookPresetStore store=new LookPresetStore(new File(a.getFilesDir(),"look-library"));
ArrayList<LookPresetStore.Entry> owned=new ArrayList<>();
ArrayList<File> files=new ArrayList<>();
ArrayList<PhotoRepository.Photo> photos=new ArrayList<>();
Instrumentation.ActivityMonitor monitor=t.addMonitor(RawEditActivity.class.getName(),null,false);
RawEditActivity[] editor={null};
Set<Long> baseline=null;
Uri output=null;
boolean saveStarted=false,ambiguous=false;
String ownedName="検証 "+UUID.randomUUID();

    try{
      require(store.list().isEmpty(),"UI capture requires empty preset library; existing entries are preserved");
      GalleryScreenChecks.finishInitialIndex(t,a);
for(int i=0;
i<2;
i++){File f=File.createTempFile("viewer-look-ui-",".jpg",a.getCacheDir());
files.add(f);
Bitmap b=fixture(i==0?600:400,i==0?400:600);
try(OutputStream out=new FileOutputStream(f)){require(b.compress(Bitmap.CompressFormat.JPEG,95,out),"fixture encoded");
}finally{b.recycle();
}photos.add(new PhotoRepository.Photo(Uri.fromFile(f),f.getName(),"/viewer-look-ui",f.length(),1792298040L,1792298040000L));
}
      byte[] original=Files.readAllBytes(files.get(0).toPath());
require(store.list().size()<=29,"library has room for isolated fixture presets");
owned.add(store.save("夕方の散歩",RawEditLook.neutral()));
owned.add(store.save("ポートレート（やわらかめ）",RawEditLook.neutral()));

      TagDetailChecks.ui(t,()->{a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
RankingResultChecks.set(a,"photos",photos);
RankingResultChecks.set(a,"bundles",List.of(List.of(0),List.of(1)));
RankingResultChecks.set(a,"selecting",false);
RankingResultChecks.set(a,"burstMembers",null);
RankingResultChecks.set(a,"albumFolder","/viewer-look-ui");
RankingResultChecks.call(a,"gallery");
});
GalleryScreenChecks.settle(t,a);
constrain(t,a,width,shortWindow);
ViewerScreenChecks.open(t,a,0);

      PhotoViewerUi initialViewer=ViewerScreenChecks.viewer(a);
await(t,30000,()->!initialViewer.lookUi.controller.busy(),"viewer restore complete");
click(t,TagDetailChecks.button(initialViewer,"手動"),"open real editor");
Activity launched=monitor.waitForActivityWithTimeout(15000);
require(launched instanceof RawEditActivity,"real editor launched from MainActivity");
editor[0]=(RawEditActivity)launched;
RawEditActivity ed=editor[0];
constrain(t,ed,width,shortWindow);

      await(t,30000,()->{View open=ed.getWindow().getDecorView().findViewWithTag("look-open");
return open!=null&&open.isEnabled();
},"editor ready");
TagDetailChecks.ui(t,()->{RawEditPad pad=findType(ed.getWindow().getDecorView(),RawEditPad.class);
require(pad!=null,"WB pad");
progress(pad.getChildAt(2),.4f);
require(((RawEditState)field(ed,"state")).get(RawEditState.TEMP)>.2f,"WB changed through editor");
require(TagDetailChecks.button(ed.getWindow().getDecorView(),"明るさ").performClick(),"brightness tab");
});
t.waitForIdleSync();
TagDetailChecks.ui(t,()->{RawEditPad pad=findType(ed.getWindow().getDecorView(),RawEditPad.class);
progress(pad.getChildAt(2),1.25f);
require(((RawEditState)field(ed,"state")).get(RawEditState.EXPOSURE)>1,"exposure changed through editor");
});
click(t,TagDetailChecks.button(ed.getWindow().getDecorView(),"WB"),"reference WB tab preserves development edits");
click(t,ed.getWindow().getDecorView().findViewWithTag("look-open"),"open editor look panel");
await(t,5000,()->field(ed,"lookDialog")!=null,"editor look sheet");
Dialog look=(Dialog)field(ed,"lookDialog");
View cnt=find(look,"look-preset-1");
click(t,cnt,"select CNT in editor");
View vignette=find(look,"look-vignette");
reveal(t,vignette);
click(t,vignette,"custom vignette");
RawEditState[] snapshot={null};
TagDetailChecks.ui(t,()->snapshot[0]=(RawEditState)field(ed,"state"));
require(!snapshot[0].look().vignetteEnabled(),"custom vignette disabled independently");

      View savePreset=find(look,"look-preset-save");
reveal(t,savePreset);
click(t,savePreset,"open name dialog");
await(t,5000,()->presetDialog(ed)!=null&&presetDialog(ed).isShowing(),"name dialog open");
Dialog dialog=presetDialog(ed);
View name=find(dialog,"preset-name"),confirm=find(dialog,"preset-confirm"),cancel=find(dialog,"preset-cancel");
await(t,10000,()->find(presetDialog(ed),"preset-confirm").isEnabled(),"library ready");
reveal(t,name);
TagDetailChecks.ui(t,()->checkTarget(find(presetDialog(ed),"preset-name"),ed,"name"));
reveal(t,confirm);
TagDetailChecks.ui(t,()->checkTarget(find(presetDialog(ed),"preset-confirm"),ed,"save"));
reveal(t,cancel);
TagDetailChecks.ui(t,()->checkTarget(find(presetDialog(ed),"preset-cancel"),ed,"cancel"));

      reveal(t,confirm);
click(t,confirm,"empty name validation");
TagDetailChecks.ui(t,()->require(presetDialog(ed).isShowing(),"empty name does not save"));
TagDetailChecks.ui(t,()->((EditText)find(presetDialog(ed),"preset-name")).setText("あ".repeat(41)));
click(t,confirm,"long name validation");
TagDetailChecks.ui(t,()->require(presetDialog(ed).isShowing(),"41 codepoint name does not save"));
reveal(t,cancel);
click(t,cancel,"cancel name dialog");
TagDetailChecks.ui(t,()->require(snapshot[0].equals(field(ed,"state")),"cancel preserves editor state"));

      click(t,savePreset,"reopen name dialog");
await(t,5000,()->presetDialog(ed)!=null&&presetDialog(ed).isShowing(),"name dialog reopened");
dialog=presetDialog(ed);
final Dialog captureDialog=dialog;
name=find(dialog,"preset-name");
confirm=find(dialog,"preset-confirm");
await(t,10000,()->find(presetDialog(ed),"preset-confirm").isEnabled(),"library ready again");
final View nameField=name;
TagDetailChecks.ui(t,()->((EditText)nameField).setText("やわらかい夕方"));
TagDetailChecks.ui(t,()->((ScrollView)find(captureDialog,"look-preset-dialog")).scrollTo(0,0));
t.waitForIdleSync();
TagDetailChecks.ui(t,()->TagDetailChecks.inspect(find(captureDialog,"look-preset-dialog")));
capture(t,ed,dialog,prefix+"-dialog",shortWindow);
TagDetailChecks.ui(t,()->((EditText)nameField).setText(ownedName));
reveal(t,confirm);
click(t,confirm,"save named preset");
await(t,15000,()->!captureDialog.isShowing(),"preset saved");
LookPresetStore.Entry saved=null;
for(LookPresetStore.Entry e:store.list())if(e.name.equals(ownedName))saved=e;
require(saved!=null&&saved.look.equals(snapshot[0].look())&&snapshot[0].get(RawEditState.TEMP)>.2f&&snapshot[0].get(RawEditState.EXPOSURE)>1,"saved Look snapshot matches Look while editor WB/exposure differ from neutral");
owned.add(saved);
String savedId=saved.id;

      // IME and Dialog dismissal may consume Back separately; wait on observable windows.
      for(int back=0;back<5;back++){
        boolean[] discard={false};TagDetailChecks.ui(t,()->discard[0]=windowButton("破棄")!=null);
        if(discard[0])break;
        t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);t.waitForIdleSync();
        TagDetailChecks.ui(t,()->{
          Dialog lookNow=(Dialog)field(ed,"lookDialog");WindowInsets insets=ed.getWindow().getDecorView().getRootWindowInsets();
          android.util.Log.i("LoupeLookTest","Back state look="+(lookNow!=null&&lookNow.isShowing())+" ime="+(insets!=null&&insets.isVisible(WindowInsets.Type.ime()))+" discard="+(windowButton("破棄")!=null));
        });
        Thread.sleep(150);
      }
      await(t,5000,()->windowButton("破棄")!=null,"dirty editor discard confirmation");
click(t,windowButton("破棄"),"discard editor changes after preset save");
await(t,15000,ed::isFinishing,"editor closed");
await(t,30000,()->ViewerScreenChecks.viewer(a)!=initialViewer&&ViewerScreenChecks.viewer(a).lookUi!=null&&!ViewerScreenChecks.viewer(a).lookUi.controller.busy(),"viewer returned");
final PhotoViewerUi viewer=ViewerScreenChecks.viewer(a);
click(t,viewer.findViewWithTag("viewer-look"),"open viewer Look");
await(t,5000,()->viewerDialog(viewer)!=null,"viewer selector open");
Dialog selector=viewerDialog(viewer);
await(t,10000,()->find(selector,"viewer-look-cnt-like")!=null,"selector loaded");
click(t,find(selector,"viewer-look-cnt-like"),"apply CNT");
settled(t,viewer,"cnt-like");
capture(t,a,selector,prefix+"-viewer",shortWindow);

      View savedRow=find(selector,"viewer-look-entry-"+savedId);
reveal(t,savedRow);
TagDetailChecks.ui(t,()->checkTarget(savedRow,a,"saved preset"));
click(t,savedRow,"apply saved preset");
settled(t,viewer,savedId);
click(t,viewer.findViewWithTag("viewer-before"),"Before");
TagDetailChecks.ui(t,()->require(viewer.lookUi.controller.before(),"Before selected"));
t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
await(t,5000,()->viewerDialog(viewer)==null,"Back closes selector");
require(!a.isFinishing(),"Back retained viewer");

      ViewerScreenChecks.clickSecondary(t,a,"次の写真");
await(t,30000,()->(int)RankingResultChecks.get(a,"index")==1,"next photo");
PhotoViewerUi next=ViewerScreenChecks.viewer(a);
ViewerScreenChecks.clickSecondary(t,a,"前の写真");
await(t,30000,()->(int)RankingResultChecks.get(a,"index")==0,"previous photo");
PhotoViewerUi restored=ViewerScreenChecks.viewer(a);
settled(t,restored,savedId);
click(t,restored.findViewWithTag("viewer-look"),"open selector for deletion");
await(t,5000,()->viewerDialog(restored)!=null,"selector opened for deletion");
Dialog deleteSelector=viewerDialog(restored);
await(t,10000,()->{View target=find(deleteSelector,"viewer-look-delete-"+savedId);return target!=null&&target.getWidth()>=GalleryUi.dp(a,48)&&target.getHeight()>=GalleryUi.dp(a,48);},"loaded and measured saved delete control");
View row=find(deleteSelector,"viewer-look-entry-"+savedId);
reveal(t,row);
View delete=find(deleteSelector,"viewer-look-delete-"+savedId);
TagDetailChecks.ui(t,()->checkTarget(delete,a,"delete"));
click(t,delete,"delete preset");
await(t,5000,()->windowButton("削除")!=null,"delete confirmation");
click(t,windowButton("削除"),"confirm deletion");
long deletionDeadline=SystemClock.uptimeMillis()+15000;
while(SystemClock.uptimeMillis()<deletionDeadline&&store.list().stream().anyMatch(e->e.id.equals(savedId)))Thread.sleep(50);
require(store.list().stream().noneMatch(e->e.id.equals(savedId)),"preset deleted through UI");
owned.remove(saved);
require(store.loadSelection(ArchiveStore.key(photos.get(0))).id.equals(savedId),"deleted preset leaves photo snapshot");
t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
await(t,5000,()->viewerDialog(restored)==null,"selector closed");

      click(t,restored.findViewWithTag("viewer-look"),"open selector for JPEG");
await(t,5000,()->viewerDialog(restored)!=null,"selector reopened");
Dialog exportDialog=viewerDialog(restored);
await(t,90000,()->restored.lookUi.controller.readyToSave(),"full export prepared");
View export=find(exportDialog,"viewer-look-save");
reveal(t,export);
TagDetailChecks.ui(t,()->checkTarget(export,a,"JPEG save"));
baseline=outputs(a.getContentResolver(),a.getPackageName());
click(t,export,"save JPEG");
saveStarted=true;
await(t,120000,()->!restored.lookUi.controller.saving(),"JPEG save completed");
Set<Long> made=outputs(a.getContentResolver(),a.getPackageName());
made.removeAll(baseline);
if(made.size()>1){ambiguous=true;
throw new AssertionError("ambiguous new JPEGs preserved");
}require(made.size()==1,"one owned JPEG published");
output=ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,made.iterator().next());
try(Cursor publishedRow=a.getContentResolver().query(output,new String[]{MediaStore.Images.Media.IS_PENDING},null,null,null)){require(publishedRow!=null&&publishedRow.moveToFirst()&&publishedRow.getInt(0)==0,"JPEG is published");
}Bitmap rendered;
try(InputStream in=a.getContentResolver().openInputStream(output)){rendered=BitmapFactory.decodeStream(in);
}require(rendered!=null&&rendered.getWidth()==600&&rendered.getHeight()==400,"JPEG has full fixture dimensions");
rendered.recycle();
require(Arrays.equals(original,Files.readAllBytes(files.get(0).toPath())),"original source unchanged");

      return "PASS actual MainActivity→RawEditActivity preset save→viewer CNT and named Look, Before, Back, paging restore, deleted-preset snapshot, full JPEG dimensions and unchanged synthetic original; captures="+prefix+" viewer/dialog. JPEG cancellation, real photos, RAW, physical IME and visual comparison remain unverified.\n";

    }finally{
      if(editor[0]!=null){RawEditActivity e=editor[0];
TagDetailChecks.ui(t,()->{if(!e.isFinishing())e.finish();
});
}if(editor[0]!=null){Uri copy=editor[0].getIntent().getData();
if(copy!=null&&copy.getLastPathSegment()!=null&&copy.getLastPathSegment().matches("[A-Za-z0-9._-]+"))new File(new File(a.getCacheDir(),"shares"),copy.getLastPathSegment()).delete();
}t.removeMonitor(monitor);
TagDetailChecks.ui(t,()->{a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
a.finish();
});
storageBarrier();
for(PhotoRepository.Photo p:photos)store.clearSelection(ArchiveStore.key(p));
for(LookPresetStore.Entry e:store.list())if(e.name.equals(ownedName))store.delete(e.id);
for(LookPresetStore.Entry e:owned)store.delete(e.id);

      if(output==null&&saveStarted&&baseline!=null&&!ambiguous){Set<Long> made=outputs(a.getContentResolver(),a.getPackageName());
made.removeAll(baseline);
if(made.size()==1)output=ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,made.iterator().next());
}if(output!=null&&!ambiguous)a.getContentResolver().delete(output,null,null);
for(File f:files)f.delete();

    }
  }
}
