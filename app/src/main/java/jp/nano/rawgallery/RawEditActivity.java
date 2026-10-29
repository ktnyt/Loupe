package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Experimental, local, non-destructive RAW editor; same activity is used by the isolated test app. */
public final class RawEditActivity extends Activity {
  private static final int BG=0xff0f0f10,INK=0xffededea,MUTED=0xffa9a9a4;
  private final Handler main=new Handler(Looper.getMainLooper());
  private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"loupe-raw-editor"));
  private final AtomicLong revision=new AtomicLong();
  private final AtomicBoolean queued=new AtomicBoolean();
  private volatile RawEditState state=new RawEditState();
  private RawEditState saved=new RawEditState();
  private volatile boolean visible=true,dead,ready,before,maxSaturation,saving;
  private volatile int epoch;
  private volatile long rendered=-1,inputNs;
  private boolean loading;
  private Uri input;
  private int tab,sub,width,height,padSize=180;
  private RawEditRenderer renderer; // Worker-owned EGL resources.
  private Future<?> loadFuture;
  private LinearLayout root,controls,tabs;
  private ScrollView scroll;
  private FrameLayout picture;
  private RawEditImageView image;
  private TextView status,title;
  private Button compare,reset,save,zoom;
  private RawEditPad pad;
  private boolean laidOut;
  private final String[] tabNames={"WB","質感","トーン","ライト","カラー"};
  private final Runnable busy=()->{if(ready&&rendered!=revision.get())status.setText("反映中…");};

  public static void open(Activity from,Uri source){from.startActivity(new Intent(from,RawEditActivity.class).setData(source));}
  protected void onCreate(Bundle bundle){
    super.onCreate(bundle);RawEditLog.enabled=(getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0;setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);getWindow().setDecorFitsSystemWindows(false);
    if(bundle!=null){
      try{state=new RawEditState(bundle.getFloatArray("values"));saved=new RawEditState(bundle.getFloatArray("saved"));}catch(IllegalArgumentException ignored){}
      tab=Math.max(0,Math.min(4,bundle.getInt("tab")));sub=Math.max(0,Math.min(2,bundle.getInt("sub")));
      String source=bundle.getString("input");if(source!=null)input=Uri.parse(source);
    }else input=getIntent().getData();
    build();
    if(input!=null||synthetic())load();else showPicker();
  }
  private boolean synthetic(){return (getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0&&getIntent().getBooleanExtra("rawEditSynthetic",false);}
  private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
  private TextView text(String value,float size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
  private Button button(String value,Runnable action){
    Button b=new Button(this);b.setAllCaps(false);b.setText(value);b.setTextColor(INK);b.setTextSize(13);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(8),0,dp(8),0);b.setBackgroundColor(Color.TRANSPARENT);b.setOnClickListener(v->action.run());return b;
  }
  private GradientDrawable rounded(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
  private void build(){
    root=new LinearLayout(this){protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);post(()->layoutControls());}};
    root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
    root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(b.left,b.top,b.right,b.bottom);return insets;});
    setContentView(root);
    LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(16),0,dp(8),0);root.addView(top,new LinearLayout.LayoutParams(-1,dp(56)));
    title=text("現像 実験",15,INK);title.setContentDescription("現像、実験版");top.addView(title,new LinearLayout.LayoutParams(0,-1,1));
    compare=button("比較",()->{if(ready&&!saving){before=!before;compare.setSelected(before);requestRender();}});compare.setContentDescription("補正前と比べる");compare.setCompoundDrawablesWithIntrinsicBounds(null,new Icon("compare"),null,null);compare.setTextSize(12);top.addView(compare,new LinearLayout.LayoutParams(dp(48),-1));
    reset=button("初期化",this::resetTab);reset.setContentDescription("現在のタブだけ初期化");reset.setCompoundDrawablesWithIntrinsicBounds(null,new Icon("reset"),null,null);reset.setTextSize(12);top.addView(reset,new LinearLayout.LayoutParams(dp(60),-1));
    save=button("保存",this::confirmSave);save.setTextColor(0xff111111);save.setTextSize(14);save.setBackground(rounded(INK,24));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(64),dp(48));sp.leftMargin=dp(4);top.addView(save,sp);
    picture=new FrameLayout(this);picture.setBackgroundColor(Color.BLACK);root.addView(picture,new LinearLayout.LayoutParams(-1,0,1));
    image=new RawEditImageView(this);picture.addView(image,new FrameLayout.LayoutParams(-1,-1));
    status=text("",14,INK);status.setGravity(Gravity.CENTER);status.setPadding(dp(16),dp(8),dp(16),dp(8));status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);picture.addView(status,new FrameLayout.LayoutParams(-1,-1));status.setClickable(false);
    zoom=button("拡大",()->{image.toggle();zoom.setText(image.zoom>1?"全体":"拡大");});zoom.setBackground(rounded(0x99000000,24));FrameLayout.LayoutParams zp=new FrameLayout.LayoutParams(dp(64),dp(48),Gravity.BOTTOM|Gravity.RIGHT);zp.rightMargin=dp(8);zp.bottomMargin=dp(8);picture.addView(zoom,zp);
    scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setBackgroundColor(BG);scroll.setVerticalScrollBarEnabled(false);root.addView(scroll,new LinearLayout.LayoutParams(-1,dp(350)));
    controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);controls.setPadding(dp(16),dp(12),dp(16),dp(12));scroll.addView(controls,new ScrollView.LayoutParams(-1,-2));
    tabs=new LinearLayout(this);tabs.setPadding(0,0,0,dp(20));tabs.setBackgroundColor(BG);root.addView(tabs,new LinearLayout.LayoutParams(-1,dp(84)));
    for(int i=0;i<5;i++){final int index=i;Button t=button(tabNames[i],()->selectTab(index));t.setTextSize(12);t.setPadding(0,0,0,0);t.setCompoundDrawablesWithIntrinsicBounds(null,new Icon("tab"+i),null,null);t.setCompoundDrawablePadding(dp(4));tabs.addView(t,new LinearLayout.LayoutParams(0,dp(64),1));}
    renderControls();enabled(false);
  }
  private void selectTab(int index){tab=index;maxSaturation=false;renderControls();requestRender();}
  private void layoutControls(){
    int h=root.getHeight()-root.getPaddingTop()-root.getPaddingBottom(),w=root.getWidth()-root.getPaddingLeft()-root.getPaddingRight();if(w<=0||h<=0)return;
    int size=Math.max(120,Math.min(Math.round(w/getResources().getDisplayMetrics().density)-88,Math.round((h/getResources().getDisplayMetrics().density-140)*.36f)));
    if(size!=padSize){padSize=size;renderControls();}
    controls.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
    int wanted=controls.getMeasuredHeight(),cap=Math.round((h-dp(140))*.7f);
    LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)scroll.getLayoutParams();int next=Math.min(wanted,Math.max(dp(160),cap));
    if(lp.height!=next){lp.height=next;scroll.setLayoutParams(lp);}laidOut=true;
  }
  private void renderControls(){
    if(controls==null)return;controls.removeAllViews();pad=null;
    for(int i=0;i<5;i++){Button t=(Button)tabs.getChildAt(i);t.setTextColor(tab==i?INK:0xff8a8a85);t.setSelected(tab==i);t.setContentDescription(tabNames[i]+(tab==i?"、選択中":""));}
    int x,y;String xn,yn;
    if(tab==0){x=0;y=1;xn="色温度";yn="色かぶり";}
    else if(tab==1){x=2;y=3;xn="テクスチャ";yn="明瞭度";}
    else if(tab==2){x=5;y=6;xn="入力";yn="出力";}
    else if(tab==3){
      LinearLayout segments=new LinearLayout(this);segments.setBackground(rounded(0xff1c1c1f,24));String[] names={"明るさ","明暗","白・黒"};
      for(int i=0;i<3;i++){final int value=i;Button b=button(names[i],()->{sub=value;renderControls();});b.setTextSize(13);if(sub==i){b.setBackground(rounded(INK,24));b.setTextColor(0xff111111);}segments.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));}
      LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(48));pp.bottomMargin=dp(8);controls.addView(segments,pp);
      if(sub==0){x=7;y=8;xn="露出";yn="明度";}else if(sub==1){x=9;y=10;xn="ハイライト";yn="シャドウ";}else{x=12;y=13;xn="ホワイト";yn="ブラック";}
    }else{x=14;y=15;xn="彩度";yn="バイブランス";}
    pad=new RawEditPad(this,padSize,state,x,y,xn,yn,this::changed);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.gravity=Gravity.CENTER_HORIZONTAL;controls.addView(pad,p);
    if(tab==0){Button max=button("彩度MAX",()->{maxSaturation=!maxSaturation;renderControls();requestRender();});max.setContentDescription("彩度MAX、一時表示。保存する彩度は変わりません"+(maxSaturation?"、オン":"、オフ"));max.setBackground(rounded(maxSaturation?INK:0xff1c1c1f,24));max.setTextColor(maxSaturation?0xff111111:INK);controls.addView(max,new LinearLayout.LayoutParams(-1,dp(48)));}
    if(tab==1)addSingle(4,"かすみの除去");if(tab==3&&sub==1)addSingle(11,"コントラスト");
    setChildrenEnabled(controls,ready&&!saving);if(laidOut)root.post(this::layoutControls);
  }
  private void addSingle(int key,String name){
    TextView label=text(name+"　"+RawEditPad.value(key,state.get(key)),13,INK);controls.addView(label,new LinearLayout.LayoutParams(-1,-2));
    SeekBar bar=new SeekBar(this);bar.setMax(200);bar.setProgress(Math.round((state.get(key)+1)*100));bar.setContentDescription(name);bar.setProgressTintList(ColorStateList.valueOf(INK));bar.setThumbTintList(ColorStateList.valueOf(INK));controls.addView(bar,new LinearLayout.LayoutParams(-1,dp(48)));
    bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      public void onProgressChanged(SeekBar v,int n,boolean user){if(user){changed(state.with(key,n/100f-1));label.setText(name+"　"+RawEditPad.value(key,state.get(key)));}}
      public void onStartTrackingTouch(SeekBar v){}public void onStopTrackingTouch(SeekBar v){}
    });
  }
  private void changed(RawEditState value){state=value;before=false;compare.setSelected(false);if(pad!=null)pad.update(value);requestRender();}
  private void resetTab(){
    if(!ready||saving)return;
    int[][] keys={{0,1},{2,3,4},{5,6},{7,8,9,10,11,12,13},{14,15}};
    state=state.reset(keys[tab]);maxSaturation=false;before=false;renderControls();requestRender();
  }
  private static void setChildrenEnabled(View v,boolean enabled){v.setEnabled(enabled);if(v instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)v).getChildCount();i++)setChildrenEnabled(((android.view.ViewGroup)v).getChildAt(i),enabled);}
  private void enabled(boolean value){compare.setEnabled(value);reset.setEnabled(value);save.setEnabled(value);zoom.setEnabled(value);setChildrenEnabled(controls,value);}
  private void showPicker(){status.setText("RAW / JPEGを選択\n実験版・長辺2048pxまで");status.setOnClickListener(v->pick());status.setClickable(true);zoom.setVisibility(View.GONE);}
  private void pick(){
    Intent choose=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
    try{startActivityForResult(choose,53);}catch(ActivityNotFoundException e){status.setText("写真を選択できるアプリがありません");}
  }
  protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==53&&result==RESULT_OK&&data!=null&&data.getData()!=null){input=data.getData();state=new RawEditState();saved=state;load();}}
  private RawEditSource syntheticSource(){
    int w=1200,h=900;ByteBuffer b=ByteBuffer.allocateDirect(w*h*16).order(ByteOrder.nativeOrder());FloatBuffer f=b.asFloatBuffer();
    for(int y=0;y<h;y++)for(int x=0;x<w;x++){
      float r=.2f,g=.3f,bl=.15f;
      if(y<h*.48){r=.3f;g=.45f;bl=.65f;}
      if(Math.hypot(x-w*.35,y-h*.62)<w*.14){r=.7f;g=.55f;bl=.3f;}
      if(Math.hypot(x-w*.7,y-h*.3)<w*.095){r=.55f;g=.13f;bl=.07f;}
      f.put(r).put(g).put(bl).put(1);
    }
    return new RawEditSource(w,h,b,false);
  }
  private void load(){
    if(dead||loading)return;loading=true;ready=false;int token=++epoch;enabled(false);status.setText("読み込み中…\nタップで中止");status.setClickable(true);status.setOnClickListener(v->onBackPressed());zoom.setVisibility(View.GONE);
    long started=SystemClock.elapsedRealtime();loadFuture=worker.submit(()->{
      try{
        if(renderer!=null){renderer.close();renderer=null;}
        RawEditSource source=synthetic()?syntheticSource():RawEditSource.load(this,input);
        if(dead||token!=epoch||!visible)return;
        renderer=new RawEditRenderer(source.width,source.height,source.pixels);
        Bitmap initial=renderer.render(state,false,false,1024);
        if(dead||token!=epoch||!visible){initial.recycle();renderer.close();renderer=null;return;}
        RawEditLog.event(source.raw?"raw.load.complete":"jpeg.load.complete",SystemClock.elapsedRealtime()-started,source.width,source.height);
        main.post(()->{if(dead||token!=epoch||!visible){initial.recycle();return;}width=source.width;height=source.height;image.replace(initial);status.setText("");status.setClickable(false);status.setOnClickListener(null);loading=false;ready=true;zoom.setVisibility(View.VISIBLE);enabled(true);renderControls();});
      }catch(IOException|RuntimeException|LinkageError|OutOfMemoryError e){
        if(renderer!=null){renderer.close();renderer=null;}
        RawEditLog.event("load.failed",SystemClock.elapsedRealtime()-started,0,0);
        main.post(()->{if(dead||token!=epoch||!visible)return;loading=false;ready=false;status.setText("読み込めませんでした\nタップで再試行");status.setClickable(true);status.setOnClickListener(v->load());});
      }
    });
  }
  private void requestRender(){inputNs=SystemClock.elapsedRealtimeNanos();long requested=revision.incrementAndGet();RawEditLog.input(requested,inputNs);main.removeCallbacks(busy);main.postDelayed(busy,400);scheduleRender();}
  private void scheduleRender(){
    if(!ready||dead||!visible||saving||!queued.compareAndSet(false,true))return;
    worker.execute(()->{
      int token=epoch;long ticket=revision.get(),requestedAt=inputNs,start=SystemClock.elapsedRealtime();
      RawEditState snapshot=state;boolean original=before,boost=maxSaturation;
      try{
        Bitmap b=renderer.render(snapshot,original,boost,1024);
        RawEditLog.event("adjust.render",SystemClock.elapsedRealtime()-start,b.getWidth(),b.getHeight());
        main.post(()->{
          // Present completed work even while a newer request is pending. The single worker
          // and UI handoff preserve order; equality with the newest input would starve drags.
          if(!dead&&visible&&token==epoch){image.replace(b,ticket,requestedAt);status.setText(original?"補正前":"");}
          else b.recycle();
          rendered=ticket;queued.set(false);
          if(rendered!=revision.get())image.postOnAnimation(this::scheduleRender);
        });
      }catch(IOException|RuntimeException|OutOfMemoryError e){
        main.post(()->{rendered=ticket;queued.set(false);if(!dead&&visible&&token==epoch)status.setText("反映できませんでした。値を動かすと再試行します");if(rendered!=revision.get())image.postOnAnimation(this::scheduleRender);});
      }
    });
  }
  private void confirmSave(){
    if(!ready||saving)return;maxSaturation=false;before=false;renderControls();requestRender();
    new AlertDialog.Builder(this).setTitle("JPEGで保存").setMessage(width+" × "+height+" px\n実験版・別のファイルとして保存")
        .setNegativeButton("キャンセル",null).setPositiveButton("保存",(d,w)->saveImage()).show();
  }
  private void saveImage(){
    if(!ready||saving)return;saving=true;enabled(false);save.setText("保存中");RawEditState snapshot=state;int token=epoch;long started=SystemClock.elapsedRealtime();
    worker.execute(()->{
      Uri pending=null;Bitmap output=null;
      try{
        if(dead||!visible||token!=epoch)throw new IOException("中止");
        output=renderer.render(snapshot,false,false,2048);
        ContentValues v=new ContentValues();v.put(MediaStore.Images.Media.DISPLAY_NAME,"Loupe-"+UUID.randomUUID()+".jpg");v.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");v.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/LoupeDevelop");v.put(MediaStore.Images.Media.IS_PENDING,1);
        pending=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);if(pending==null)throw new IOException("保存先");
        ParcelFileDescriptor fd=getContentResolver().openFileDescriptor(pending,"w");
        if(fd==null)throw new IOException("保存先");
        try(FileOutputStream stream=new ParcelFileDescriptor.AutoCloseOutputStream(fd)){
          if(!output.compress(Bitmap.CompressFormat.JPEG,95,stream))throw new IOException("圧縮");
          stream.flush();fd.getFileDescriptor().sync();
        }
        if(dead||!visible||token!=epoch)throw new IOException("中止");
        ContentValues publish=new ContentValues();publish.put(MediaStore.Images.Media.IS_PENDING,0);
        if(getContentResolver().update(pending,publish,null,null)!=1)throw new IOException("公開");pending=null;
        RawEditLog.event("save.complete",SystemClock.elapsedRealtime()-started,width,height);
        main.post(()->{if(dead||!visible||token!=epoch)return;saving=false;saved=snapshot;save.setText("保存");enabled(true);status.setText("保存しました");main.postDelayed(()->{if(!dead&&!before)status.setText("");},900);});
      }catch(IOException|RuntimeException|OutOfMemoryError e){RawEditLog.event("save.failed",SystemClock.elapsedRealtime()-started,0,0);main.post(()->{if(dead||!visible||token!=epoch)return;saving=false;save.setText("保存");enabled(true);status.setText("保存できませんでした。もう一度保存できます");});}
      finally{if(output!=null)output.recycle();if(pending!=null)try{getContentResolver().delete(pending,null,null);}catch(RuntimeException ignored){RawEditLog.event("save.cleanup.failed",0,0,0);}}
    });
  }
  public void onBackPressed(){
    if(saving)return;
    if(!state.equals(saved)){new AlertDialog.Builder(this).setTitle("変更を破棄しますか？").setNegativeButton("編集を続ける",null).setPositiveButton("破棄",(d,w)->finish()).show();}
    else finish();
  }
  protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putFloatArray("values",state.snapshot());b.putFloatArray("saved",saved.snapshot());b.putInt("tab",tab);b.putInt("sub",sub);if(input!=null)b.putString("input",input.toString());}
  protected void onStart(){super.onStart();visible=true;if(root!=null&&input!=null&&!ready&&!loading)load();else if(ready)requestRender();}
  protected void onStop(){visible=false;++epoch;maxSaturation=false;before=false;main.removeCallbacks(busy);if(loadFuture!=null&&loading)loadFuture.cancel(true);loading=false;saving=false;super.onStop();}
  protected void onDestroy(){dead=true;ready=false;main.removeCallbacksAndMessages(null);if(loadFuture!=null)loadFuture.cancel(true);worker.execute(()->{if(renderer!=null){renderer.close();renderer=null;}});worker.shutdown();image.clear();super.onDestroy();}

  private final class Icon extends Drawable {
    final String type;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    Icon(String t){type=t;setBounds(0,0,dp(20),dp(20));}
    public int getIntrinsicWidth(){return dp(24);}public int getIntrinsicHeight(){return dp(24);}
    public void draw(Canvas canvas){
      canvas.save();canvas.scale(getBounds().width()/24f,getBounds().height()/24f);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.6f);p.setColor(INK);
      if(type.equals("compare")){canvas.drawRect(4,4,20,20,p);canvas.drawLine(12,4,12,20,p);}
      else if(type.equals("reset")){canvas.drawArc(5,5,21,21,-90,280,false,p);canvas.drawLine(5,3,5,10,p);canvas.drawLine(5,10,12,10,p);}
      else if(type.equals("tab0")){canvas.drawCircle(12,12,7,p);canvas.drawLine(12,2,12,22,p);}
      else if(type.equals("tab1")){for(int x=4;x<=20;x+=4)canvas.drawLine(x,5,x,19,p);}
      else if(type.equals("tab2")){Path path=new Path();path.moveTo(4,20);path.cubicTo(8,20,14,5,20,4);canvas.drawPath(path,p);}
      else if(type.equals("tab3")){canvas.drawCircle(12,12,4,p);for(int i=0;i<8;i++){double a=i*Math.PI/4;canvas.drawLine(12+(float)Math.cos(a)*7,12+(float)Math.sin(a)*7,12+(float)Math.cos(a)*10,12+(float)Math.sin(a)*10,p);}}
      else {canvas.drawCircle(9,10,5,p);canvas.drawCircle(15,10,5,p);canvas.drawCircle(12,16,5,p);}
      canvas.restore();
    }
    public void setAlpha(int a){p.setAlpha(a);}public void setColorFilter(ColorFilter f){p.setColorFilter(f);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
  }
}
