package jp.nano.rawgallery;
import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic UI only: no tag/database writes, permissions, synchronization or external recipients. */
final class ConsistencyLayoutChecks {
  static String run(Instrumentation t)throws Exception{
    TagActivity a=(TagActivity)t.startActivitySync(new Intent(t.getTargetContext(),TagActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    Configuration original=new Configuration(a.getResources().getConfiguration());DisplayMetrics metrics=new DisplayMetrics();metrics.setTo(a.getResources().getDisplayMetrics());
    File dir=new File(a.getCacheDir(),"consistency-checks");dir.mkdirs();AtomicInteger actions=new AtomicInteger();int captures=0;
    try{
      // Finish the activity's read before replacing its UI with synthetic cases.
      ((java.util.concurrent.ExecutorService)AlbumTagEntryChecks.field(a,"worker")).submit(()->{}).get();t.waitForIdleSync();
      t.runOnMainSync(()->a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      for(int width:new int[]{320,400})for(float scale:new float[]{1f,1.3f}){
        final Context[] context={null};t.runOnMainSync(()->{Configuration config=new Configuration(original);config.fontScale=scale;config.screenWidthDp=width;context[0]=a.createConfigurationContext(config);});Context c=context[0];
        for(String state:new String[]{"normal","long","empty","fail","off"}){
          final LinearLayout[] root={null};t.runOnMainSync(()->{
            LinearLayout screen=column(c);screen.setBackgroundColor(GalleryUi.BG);LinearLayout content=column(c);content.addView(TagListUi.title(c));content.addView(TagListUi.scope(c));
            if(state.equals("empty"))content.addView(TagListUi.empty(c));else if(state.equals("fail"))content.addView(TagListUi.failure(c,actions::incrementAndGet));
            else{String[] names=state.equals("long")?new String[]{"街角の光と影を集めたとても長い名前のタグです","旅先で見つけた静かな風景と窓辺の記録","家族","散歩","お気に入り"}:new String[]{"旅","家族","街角","光と影","お気に入り"};for(int i=0;i<names.length;i++){Button b=TagListUi.row(c,names[i],actions::incrementAndGet);if(state.equals("off")){b.setEnabled(false);b.setAlpha(.38f);}content.addView(b);if(i<names.length-1){View line=new View(c);line.setBackgroundColor(0xff26262a);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,GalleryUi.dp(c,1));lp.leftMargin=GalleryUi.dp(c,76);content.addView(line,lp);}}}
            ScrollView scroll=new ScrollView(c);scroll.addView(content);screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));View line=new View(c);line.setBackgroundColor(0xff26262a);screen.addView(line,new LinearLayout.LayoutParams(-1,GalleryUi.dp(c,1)));LinearLayout footer=TagListUi.footer(c,actions::incrementAndGet);if(state.equals("off")){footer.getChildAt(0).setEnabled(false);footer.getChildAt(0).setAlpha(.38f);}screen.addView(footer);mount(a,screen,c,width);root[0]=screen;
          });
          t.waitForIdleSync();Thread.sleep(100);check(t,root[0]);capture(t,root[0],dir,"tags-"+width+"-"+scale+"-"+state);captures++;
          if(state.equals("fail")){int before=actions.get();t.runOnMainSync(()->clickAll(root[0]));if(actions.get()!=before+2)throw new AssertionError("retry/create callbacks");}
        }
        for(String state:new String[]{"normal","denied","failed","long-info"}){
          final LinearLayout[] root={null};final ScrollView[] scroll={null};final SyncSettingsUi.ReadOnlySwitch[] toggle={null};
          t.runOnMainSync(()->{LinearLayout screen=column(c);screen.setBackgroundColor(GalleryUi.BG);LinearLayout content=column(c);scroll[0]=new ScrollView(c);scroll[0].addView(content);screen.addView(scroll[0],new LinearLayout.LayoutParams(-1,0,1));
            StatusView collection=new StatusView(c),permission=new StatusView(c),progress=new StatusView(c);collection.show(state.equals("failed")?StatusView.Symbol.FAILED:StatusView.Symbol.COMPLETE,"写真情報の読み取り");permission.show(state.equals("denied")?StatusView.Symbol.PERMISSION:StatusView.Symbol.COMPLETE,"自動管理の権限");progress.show(StatusView.Symbol.COMPLETE,"10:42","バックグラウンド同期、完了",null,"");TextView info=new TextView(c);info.setText(state.equals("long-info")?"JPEG 1180枚\nRAW 245枚\n合計1425枚。元のファイルの集計です。":"1,180枚");
            SyncSettingsUi.ReadOnlySwitch sync=new SyncSettingsUi.ReadOnlySwitch(c),cleanup=new SyncSettingsUi.ReadOnlySwitch(c);sync.setChecked(true);cleanup.setChecked(true);toggle[0]=sync;
            TextView retention=SyncSettingsUi.populate(c,content,screen,info,collection,permission,progress,sync,cleanup,state.equals("denied")?button(c,actions):null,button(c,actions),button(c,actions),button(c,actions),button(c,actions));retention.setText("30日");mount(a,screen,c,width);root[0]=screen;
          });t.waitForIdleSync();Thread.sleep(100);check(t,root[0]);
          t.runOnMainSync(()->{SyncSettingsUi.ReadOnlySwitch sw=toggle[0];if(sw.performClick()||!sw.isChecked())throw new AssertionError("readonly switch changed");AccessibilityNodeInfo node=sw.createAccessibilityNodeInfo();if(node.isClickable()||!node.isChecked())throw new AssertionError("switch AX contract");});
          capture(t,root[0],dir,"sync-"+width+"-"+scale+"-"+state);captures++;
          t.runOnMainSync(()->scroll[0].fullScroll(View.FOCUS_DOWN));t.waitForIdleSync();Thread.sleep(80);capture(t,root[0],dir,"sync-"+width+"-"+scale+"-"+state+"-bottom");captures++;
        }
      }
      return "PASS "+captures+" synthetic real-device captures at 320/400dp font 1/1.3. Text ellipsis/height, child horizontal bounds, minimum48dp controls, retry/create callbacks and readonly switch AX checked. Permission/state combinations and long summary rendered independently; no stored settings changed. Output "+dir+"\n";
    }finally{t.runOnMainSync(()->{a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.finish();});}
  }
  private static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(1);return l;}
  private static Button button(Context c,AtomicInteger actions){return new SyncSettingsUi.ActionButton(c,"fixture",actions::incrementAndGet);}
  private static void mount(Activity a,LinearLayout root,Context c,int width){FrameLayout frame=new FrameLayout(a);frame.setBackgroundColor(GalleryUi.BG);frame.addView(root,new FrameLayout.LayoutParams(GalleryUi.dp(c,width),GalleryUi.dp(c,760)));a.setContentView(frame);}
  private static void clickAll(View view){if(view instanceof Button&&view.isEnabled())view.performClick();else if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)clickAll(((ViewGroup)view).getChildAt(i));}
  private static void check(Instrumentation t,View root){final Throwable[] error={null};t.runOnMainSync(()->{try{bounds(root);}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(error[0]);}
  private static void bounds(View v){if(v.getVisibility()!=View.VISIBLE)return;if(v instanceof Button&&v.getHeight()<GalleryUi.dp(v.getContext(),48))throw new AssertionError("small control");if(v instanceof TextView){TextView text=(TextView)v;android.text.Layout layout=text.getLayout();if(layout!=null){for(int i=0;i<layout.getLineCount();i++)if(layout.getEllipsisCount(i)>0)throw new AssertionError("ellipsis");if(layout.getHeight()>v.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom()+1)throw new AssertionError("text height "+text.getText());}}if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View child=g.getChildAt(i);if(child.getLeft()<0||child.getRight()>g.getWidth()+1)throw new AssertionError("horizontal overflow "+child.getClass());bounds(child);}}}
  private static void capture(Instrumentation t,View view,File dir,String name)throws Exception{final Bitmap[] bitmap={null};t.runOnMainSync(()->{bitmap[0]=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap[0]));});try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){bitmap[0].compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap[0].recycle();}}
}
