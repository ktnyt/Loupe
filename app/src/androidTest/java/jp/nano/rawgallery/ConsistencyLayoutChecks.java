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
        {final LinearLayout[] albumScreen={null};
          t.runOnMainSync(()->{LinearLayout screen=column(c);screen.setBackgroundColor(GalleryUi.BG);LinearLayout header=column(c);LinearLayout cards=GalleryCarouselUi.section(c,header,"最近の写真",true);((TextView)header.findViewWithTag("carousel-date")).setText("10月18日");
            for(int n=0;n<3;n++)GalleryCarouselUi.photo(c,cards,"10月18日 13:13",true,actions::incrementAndGet).setImageDrawable(new android.graphics.drawable.ColorDrawable(n==0?0xff718774:0xffa4b7c3));
            TextView count=new TextView(c);count.setText("6件");StatusView status=new StatusView(c);status.finish();header.addView(AlbumListUi.heading(c,count,status));
            ScrollingGrid grid=new ScrollingGrid(c);grid.addHeaderView(header,null,false);AlbumListUi.configureGrid(c,grid);if(grid.getDividerHeight()!=0)throw new AssertionError("album header gap");grid.setCells(new BaseAdapter(){public int getCount(){return 6;}public Object getItem(int p){return p;}public long getItemId(int p){return p;}public View getView(int p,View old,ViewGroup parent){LinearLayout cell=AlbumListUi.cell(c);((ImageView)cell.getChildAt(0)).setImageDrawable(new android.graphics.drawable.ColorDrawable(p%2==0?0xff718774:0xffa4b7c3));((TextView)cell.getChildAt(1)).setText(p==0?"嵐山 10.18":"カメラ");((TextView)cell.getChildAt(2)).setText("51枚");return cell;}});screen.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
            screen.addView(GalleryUi.dock(c,GalleryUi.button(c,"タグ",actions::incrementAndGet),GalleryUi.button(c,"その他",actions::incrementAndGet)));screen.addView(new LibraryTabs(c,true,true,value->actions.incrementAndGet()));mount(a,screen,c,width);albumScreen[0]=screen;});
          t.waitForIdleSync();check(t,albumScreen[0]);capture(t,albumScreen[0],dir,"albums-full-"+width+"-"+scale);captures++;if(width==400&&scale==1f)captureHardware(t,a,albumScreen[0],dir,"albums-hardware");
        }
        for(boolean recent:new boolean[]{true,false}){
          final LinearLayout[] carouselRoot={null};final LinearLayout[] cards={null};
          t.runOnMainSync(()->{LinearLayout screen=column(c);screen.setBackgroundColor(GalleryUi.BG);cards[0]=GalleryCarouselUi.section(c,screen,recent?"最近の写真":"ほかのアルバム",recent);
            for(int n=0;n<3;n++){ImageView photo=GalleryCarouselUi.photo(c,cards[0],recent?"10月18日 13:13":n==0?"スクリーンショットと長いアルバム名":"カメラ",recent,actions::incrementAndGet);photo.setImageDrawable(new android.graphics.drawable.ColorDrawable(n==0?0xff718774:0xffa4b7c3));}
            mount(a,screen,c,width);carouselRoot[0]=screen;});
          t.waitForIdleSync();check(t,carouselRoot[0]);
          t.runOnMainSync(()->{View card=cards[0].getChildAt(0);View frame=((android.view.ViewGroup)card).getChildAt(0);if(card.getWidth()!=GalleryUi.dp(c,recent?300:104)||frame.getHeight()!=GalleryUi.dp(c,recent?225:78))throw new AssertionError("carousel photo dimensions");int before=actions.get();card.performClick();if(actions.get()!=before+1)throw new AssertionError("carousel callback");});
          capture(t,carouselRoot[0],dir,"carousel-"+width+"-"+scale+"-"+(recent?"recent":"albums"));captures++;
        }
        for(String state:new String[]{"normal","long","empty","fail","off"}){
          final LinearLayout[] root={null};t.runOnMainSync(()->{
            LinearLayout screen=column(c);screen.setBackgroundColor(GalleryUi.BG);LinearLayout content=column(c);content.addView(TagListUi.title(c));TextView scope=TagListUi.scope(c);scope.setText("嵐山 10.18 · 2個");content.addView(scope);
            if(state.equals("empty"))content.addView(TagListUi.empty(c));else if(state.equals("fail"))content.addView(TagListUi.failure(c,actions::incrementAndGet));
            else{String[] names=state.equals("long")?new String[]{"空","二〇二六年の秋に京都と奈良をめぐった家族旅行で撮った、あとで写真集にまとめたい写真","夕景","スクラムフェスの会場と登壇のようす（ダミーの長い名前）","紅葉"}:new String[]{"空","夕景"};for(int i=0;i<names.length;i++){Button b=TagListUi.row(c,names[i],actions::incrementAndGet);TagListUi.summary(b,names[i],i==0?3:2,i==0?0:1);Bitmap photo=Bitmap.createBitmap(112,112,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(photo);Paint paint=new Paint();paint.setShader(new LinearGradient(0,0,112,112,i==0?0xff427f9e:0xffc5947a,i==0?0xffd4c7a1:0xff615d75,Shader.TileMode.CLAMP));canvas.drawRect(0,0,112,112,paint);TagListUi.photo(b,photo);if(state.equals("off")){b.setEnabled(false);b.setAlpha(.38f);}content.addView(b);}}
            ScrollView scroll=new ScrollView(c);scroll.addView(content);screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));View line=new View(c);line.setBackgroundColor(0xff26262a);screen.addView(line,new LinearLayout.LayoutParams(-1,GalleryUi.dp(c,1)));LinearLayout footer=TagListUi.footer(c,actions::incrementAndGet);if(state.equals("off")){footer.getChildAt(0).setEnabled(false);footer.getChildAt(0).setAlpha(.38f);}screen.addView(footer);mount(a,screen,c,width);root[0]=screen;
          });
          t.waitForIdleSync();Thread.sleep(100);check(t,root[0]);capture(t,root[0],dir,"tags-"+width+"-"+scale+"-"+state);captures++;
          if(state.equals("fail")){int before=actions.get();t.runOnMainSync(()->clickAll(root[0]));if(actions.get()!=before+2)throw new AssertionError("retry/create callbacks");}
        }
        for(String state:new String[]{"normal","denied","failed","long-info"}){
          final LinearLayout[] root={null};final ScrollView[] scroll={null};final Switch[] toggle={null};
          t.runOnMainSync(()->{LinearLayout screen=column(c);screen.setBackgroundColor(GalleryUi.BG);LinearLayout content=column(c);scroll[0]=new ScrollView(c);scroll[0].addView(content);screen.addView(scroll[0],new LinearLayout.LayoutParams(-1,0,1));
            StatusView collection=new StatusView(c),permission=new StatusView(c),progress=new StatusView(c);collection.show(state.equals("failed")?StatusView.Symbol.FAILED:StatusView.Symbol.COMPLETE,"写真情報の読み取り");permission.show(state.equals("denied")?StatusView.Symbol.PERMISSION:StatusView.Symbol.COMPLETE,"自動管理の権限");progress.show(StatusView.Symbol.COMPLETE,"","バックグラウンド同期、完了",null,"");TextView clock=new TextView(c);clock.setText("10:42");TextView info=new TextView(c);info.setText(state.equals("long-info")?"JPEG 1180枚\nRAW 245枚\n合計1425枚。元のファイルの集計です。":"1,180枚");
            Switch sync=new Switch(c),cleanup=new Switch(c);sync.setChecked(true);cleanup.setChecked(true);toggle[0]=sync;
            TextView retention=SyncSettingsUi.populate(c,content,screen,info,collection,permission,progress,clock,sync,cleanup,state.equals("denied")?button(c,actions):null,button(c,actions),button(c,actions),button(c,actions),button(c,actions));retention.setText("撮影日から30日");StatusView verified=new StatusView(c);verified.show(StatusView.Symbol.COMPLETE,"前回選んだ写真の確認が完了");SyncSettingsUi.verification(c,content,verified,button(c,actions));mount(a,screen,c,width);root[0]=screen;
          });t.waitForIdleSync();Thread.sleep(100);check(t,root[0]);
          t.runOnMainSync(()->{Switch sw=toggle[0];sw.performClick();if(sw.isChecked())throw new AssertionError("switch click did not toggle");AccessibilityNodeInfo node=sw.createAccessibilityNodeInfo();if(!node.isClickable()||node.isChecked())throw new AssertionError("switch AX contract");sw.setChecked(true);});
          capture(t,root[0],dir,"sync-"+width+"-"+scale+"-"+state);captures++;
          t.runOnMainSync(()->scroll[0].fullScroll(View.FOCUS_DOWN));t.waitForIdleSync();Thread.sleep(80);capture(t,root[0],dir,"sync-"+width+"-"+scale+"-"+state+"-bottom");captures++;
        }
      }
      return "PASS "+captures+" synthetic real-device captures at 320/400dp font 1/1.3. Text ellipsis/height, child horizontal bounds, minimum48dp controls, retry/create callbacks and direct switch AX checked. Permission/state combinations and long summary rendered independently; no stored settings changed. Output "+dir+"\n";
    }finally{t.runOnMainSync(()->{a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.finish();});}
  }
  private static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(1);return l;}
  private static Button button(Context c,AtomicInteger actions){return new SyncSettingsUi.ActionButton(c,"fixture",actions::incrementAndGet);}
  private static void mount(Activity a,LinearLayout root,Context c,int width){FrameLayout frame=new FrameLayout(a);frame.setBackgroundColor(GalleryUi.BG);frame.addView(root,new FrameLayout.LayoutParams(GalleryUi.dp(c,width),GalleryUi.dp(c,760)));a.setContentView(frame);}
  private static void clickAll(View view){if(view instanceof Button&&view.isEnabled())view.performClick();else if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)clickAll(((ViewGroup)view).getChildAt(i));}
  private static void check(Instrumentation t,View root){final Throwable[] error={null};t.runOnMainSync(()->{try{bounds(root);}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(error[0]);}
  private static void bounds(View v){if(v.getVisibility()!=View.VISIBLE)return;if(v instanceof Button&&v.getHeight()<GalleryUi.dp(v.getContext(),48))throw new AssertionError("small control");if(v instanceof TextView){TextView text=(TextView)v;android.text.Layout layout=text.getLayout();if(layout!=null){for(int i=0;i<layout.getLineCount();i++)if(layout.getEllipsisCount(i)>0)throw new AssertionError("ellipsis");if(layout.getHeight()>v.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom()+1)throw new AssertionError("text height "+text.getText());}}if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View child=g.getChildAt(i);if(!(g instanceof HorizontalScrollView)&&(child.getLeft()<0||child.getRight()>g.getWidth()+1))throw new AssertionError("horizontal overflow "+child.getClass());bounds(child);}}}
  private static boolean processWindowFocused(){for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews())if(root.isShown()&&root.hasWindowFocus())return true;return false;}
  static void frame(Instrumentation t,View view)throws Exception{
    java.util.concurrent.CountDownLatch committed=new java.util.concurrent.CountDownLatch(1);
    TagDetailChecks.ui(t,()->{
      if(!view.isAttachedToWindow()||!view.isHardwareAccelerated())throw new AssertionError("synthetic frame requires attached hardware view");
      View decor=view.getRootView();
      decor.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
      decor.requestLayout();decor.postInvalidateOnAnimation();
    });
    if(!committed.await(5,java.util.concurrent.TimeUnit.SECONDS)){
      TagDetailChecks.ui(t,()->android.util.Log.e("LoupeFixtureGeometry","frameTimeout attached="+view.isAttachedToWindow()+" shown="+view.isShown()+" focus="+view.hasWindowFocus()+" visibility="+view.getWindowVisibility()+" width="+view.getWidth()+" height="+view.getHeight()+" measured="+view.getMeasuredHeight()+" layoutRequested="+view.isLayoutRequested()+" decorWidth="+view.getRootView().getWidth()+" decorHeight="+view.getRootView().getHeight()));
      throw new AssertionError("synthetic frame commit timeout");
    }
    long deadline=android.os.SystemClock.uptimeMillis()+5000;
    boolean[] ready={false};
    do{
      TagDetailChecks.ui(t,()->ready[0]=view.isLaidOut()&&view.getWidth()>0&&view.getHeight()>0&&view.isShown()&&processWindowFocused());
      if(ready[0])return;
      Thread.sleep(16);
    }while(android.os.SystemClock.uptimeMillis()<deadline);
    throw new AssertionError("synthetic frame layout or app focus timeout");
  }
  static void captureHardware(Instrumentation t,Activity activity,View view,File dir,String name)throws Exception{captureHardware(t,activity.getWindow(),view,dir,name);}
  static void captureHardware(Instrumentation t,Window window,View view,File dir,String name)throws Exception{
    frame(t,view);
    java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);int[] result={-1};Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
    t.runOnMainSync(()->{int[] origin=new int[2];view.getLocationInWindow(origin);Rect region=new Rect(origin[0],origin[1],origin[0]+view.getWidth(),origin[1]+view.getHeight());PixelCopy.request(window,region,bitmap,value->{result[0]=value;done.countDown();},new android.os.Handler(android.os.Looper.getMainLooper()));});
    try{if(!done.await(5,java.util.concurrent.TimeUnit.SECONDS)||result[0]!=PixelCopy.SUCCESS)throw new AssertionError("synthetic PixelCopy "+result[0]);try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}}finally{bitmap.recycle();}
  }
  private static void capture(Instrumentation t,View view,File dir,String name)throws Exception{final Bitmap[] bitmap={null};t.runOnMainSync(()->{bitmap[0]=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap[0]));});try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){bitmap[0].compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap[0].recycle();}}
}
