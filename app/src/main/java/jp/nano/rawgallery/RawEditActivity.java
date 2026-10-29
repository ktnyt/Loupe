package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.*;
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
  private volatile long rendered=-1;
  private volatile RenderRequest latest;
  private static final class RenderRequest {
    final long ticket,inputNs;final RawEditState state;final boolean before,max;
    RenderRequest(long id,long ns,RawEditState values,boolean original,boolean boost){ticket=id;inputNs=ns;state=values;before=original;max=boost;}
  }
  private boolean loading;
  private Uri input;
  private int tab,width,height,padSize=180;
  private RawEditRenderer renderer; // Worker-owned EGL resources.
  private Future<?> loadFuture;
  private final java.util.Set<RawEditSource> pendingSources=ConcurrentHashMap.newKeySet();
  private RawEditFullSession fullSession;
  private RawEditFullSource.Preview pendingFullPreview;
  private volatile boolean previewSwapQueued;
  private boolean saveWaiting,preparationFailed,fullBadgeShown;
  private int previewWidth,previewHeight;
  private long lastInputMs;
  private FullBadge fullBadge;
  private AlertDialog saveDialog;
  private final Runnable showFullBadge=()->{if(ready&&fullSession!=null&&!fullSession.isPrepared()){fullBadgeShown=true;updateFullUi();}};
  private final Runnable replaceFullPreview=this::replacePreparedPreview;
  private LinearLayout root,controls,tabs;
  private HorizontalScrollView tabScroll;
  private FrameLayout picture;
  private RawEditImageView image;
  private TextView status,title,badge;
  private ProgressBar busyIcon;
  private volatile boolean interacting;
  private boolean displayBefore,displayMax;
  private Button compare,reset,save,zoom,help;
  private RawEditPad pad;
  private final String[] tabNames={"WB","質感","トーン","明るさ","明暗","白黒","カラー"};
  private final int[][] pairs={{0,1},{2,3},{5,6},{7,8},{9,10},{12,13},{14,15}};
  private final String[][] axes={{"色温度","色かぶり"},{"テクスチャ","明瞭度"},{"入力","出力"},{"露出","明度"},{"ハイライト","シャドウ"},{"ホワイトポイント","ブラックポイント"},{"彩度","バイブランス"}};
  private final Runnable busy=()->{if(ready&&rendered!=revision.get())busyIcon.setVisibility(View.VISIBLE);};

  public static void open(Activity from,Uri source){from.startActivity(new Intent(from,RawEditActivity.class).setData(source));}
  protected void onCreate(Bundle bundle){
    super.onCreate(bundle);RawEditLog.enabled=(getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0;setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);getWindow().setDecorFitsSystemWindows(false);
    if(bundle!=null){
      try{state=new RawEditState(bundle.getFloatArray("values"));saved=new RawEditState(bundle.getFloatArray("saved"));}catch(IllegalArgumentException ignored){}
      tab=bundle.getInt("tab");
      if(bundle.getInt("uiVersion",1)<2){if(tab==4)tab=6;else if(tab==3)tab+=Math.max(0,Math.min(2,bundle.getInt("sub")));}
      tab=Math.max(0,Math.min(6,tab));
      String source=bundle.getString("input");if(source!=null)input=Uri.parse(source);
    }else input=getIntent().getData();
    build();
    if(bundle!=null){image.zoom=Math.max(1,Math.min(Float.MAX_VALUE,bundle.getFloat("zoom",1)));image.dx=bundle.getFloat("panX",0);image.dy=bundle.getFloat("panY",0);updateZoom();}
    if(input!=null||synthetic())load();else showPicker();
  }
  private boolean synthetic(){return (getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0&&getIntent().getBooleanExtra("rawEditSynthetic",false);}
  private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
  private TextView text(String value,float size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
  private Button button(String value,Runnable action){
    Button b=new Button(this);b.setAllCaps(false);b.setText(value);b.setTextColor(INK);b.setTextSize(13);b.setLetterSpacing(0);b.setTypeface(Typeface.create(Typeface.DEFAULT,500,false));b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(8),0,dp(8),0);b.setBackgroundColor(Color.TRANSPARENT);b.setOnClickListener(v->action.run());return b;
  }
  private GradientDrawable rounded(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
  private Button topButton(String label,String icon,Runnable action){
    Button b=button(label,action);b.setTextSize(12);b.setPadding(0,0,0,0);b.setIncludeFontPadding(false);
    b.setCompoundDrawablesWithIntrinsicBounds(null,new Icon(icon,20),null,null);return b;
  }
  private void build(){
    root=new LinearLayout(this){protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);post(()->layoutControls());}};
    root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
    root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(b.left,b.top,b.right,b.bottom);return insets;});setContentView(root);
    LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(16),0,dp(8),0);root.addView(top,new LinearLayout.LayoutParams(-1,dp(56)));
    LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);title=text("現像",15,INK);title.setTypeface(Typeface.create(Typeface.DEFAULT,600,false));heading.addView(title);
    TextView experiment=text("実験",12,MUTED);LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-2,-2);ep.leftMargin=dp(6);heading.addView(experiment,ep);top.addView(heading,new LinearLayout.LayoutParams(0,-1,1));
    help=topButton("ヘルプ","help",this::showHelp);top.addView(help,new LinearLayout.LayoutParams(dp(48),-1));
    compare=topButton("比較","compare",()->{if(ready&&!saving){before=!before;compare.setSelected(before);requestRender();}});compare.setContentDescription("補正前と比べる");top.addView(compare,new LinearLayout.LayoutParams(dp(48),-1));
    reset=topButton("初期化","reset",this::resetTab);reset.setContentDescription("現在のタブだけ初期化");top.addView(reset,new LinearLayout.LayoutParams(dp(48),-1));
    save=button("保存",this::confirmSave);save.setTextColor(0xff111111);save.setTextSize(14);save.setTypeface(Typeface.create(Typeface.DEFAULT,600,false));save.setPadding(0,0,0,0);save.setBackground(rounded(INK,24));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(64),dp(48));sp.leftMargin=dp(4);top.addView(save,sp);
    picture=new PhotoSurface();picture.setClipChildren(false);picture.setBackgroundColor(Color.BLACK);root.addView(picture,new LinearLayout.LayoutParams(-1,0,1));
    image=new RawEditImageView(this);picture.addView(image,new FrameLayout.LayoutParams(-1,-1));image.setViewportListener(this::viewportChanged);
    status=text("",14,INK);status.setGravity(Gravity.CENTER);status.setPadding(dp(16),dp(8),dp(16),dp(8));status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);picture.addView(status,new FrameLayout.LayoutParams(-1,-1));status.setClickable(false);
    badge=text("",12,INK);badge.setGravity(Gravity.CENTER);badge.setPadding(dp(10),0,dp(10),0);badge.setBackground(rounded(0x9e000000,12));badge.setVisibility(View.GONE);badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-2,dp(24),Gravity.TOP|Gravity.LEFT);bp.leftMargin=bp.topMargin=dp(12);picture.addView(badge,bp);
    busyIcon=new ProgressBar(this);busyIcon.setIndeterminateTintList(ColorStateList.valueOf(INK));busyIcon.setBackground(rounded(0x9e000000,14));busyIcon.setPadding(dp(5),dp(5),dp(5),dp(5));busyIcon.setVisibility(View.GONE);busyIcon.setContentDescription("反映しています");FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(dp(28),dp(28),Gravity.TOP|Gravity.RIGHT);ip.rightMargin=ip.topMargin=dp(12);picture.addView(busyIcon,ip);
    fullBadge=new FullBadge();fullBadge.setVisibility(View.GONE);fullBadge.setOnClickListener(v->retryPreparation());FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP|Gravity.RIGHT);fp.rightMargin=dp(4);fp.topMargin=dp(2);picture.addView(fullBadge,fp);
    controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER_VERTICAL);controls.setPadding(dp(16),0,dp(8),0);controls.setBackground(new RowBackground());root.addView(controls,new LinearLayout.LayoutParams(-1,dp(56)));
    zoom=button("",()->{image.toggle();updateZoom();});zoom.setContentDescription("拡大");zoom.setPadding(0,0,0,0);
    tabScroll=new HorizontalScrollView(this);tabScroll.setHorizontalScrollBarEnabled(false);tabScroll.setFillViewport(true);root.addView(tabScroll,new LinearLayout.LayoutParams(-1,dp(84)));
    tabs=new LinearLayout(this);tabs.setPadding(0,0,0,dp(20));tabs.setBackgroundColor(BG);tabScroll.addView(tabs,new HorizontalScrollView.LayoutParams(-2,-1));
    for(int i=0;i<7;i++){final int index=i;Button t=button(tabNames[i],()->selectTab(index));t.setTextSize(12);t.setIncludeFontPadding(false);t.setPadding(0,0,0,0);t.setCompoundDrawablesWithIntrinsicBounds(null,new Icon("tab"+i,24),null,null);t.setCompoundDrawablePadding(dp(4));tabs.addView(t,new LinearLayout.LayoutParams(dp(56),dp(64)));}
    renderControls();enabled(false);
  }
  private void selectTab(int index){tab=index;maxSaturation=false;renderControls();requestRender();tabScroll.post(()->{View t=tabs.getChildAt(tab);tabScroll.smoothScrollTo(Math.max(0,t.getLeft()-(tabScroll.getWidth()-t.getWidth())/2),0);});}
  private void layoutControls(){
    int w=picture.getWidth(),h=picture.getHeight();if(w<=0||h<=0)return;
    float density=getResources().getDisplayMetrics().density;
    int size=Math.max(48,(int)Math.floor(Math.min(w/density-72,h/density-72)));
    int cell=Math.max(dp(56),w/7);
    for(int i=0;i<7;i++){View t=tabs.getChildAt(i);LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)t.getLayoutParams();int next=w>=dp(392)&&i==6?w-cell*6:cell;if(lp.width!=next){lp.width=next;t.setLayoutParams(lp);}}
    if(size!=padSize){padSize=size;renderControls();}
  }
  private void renderControls(){
    if(controls==null)return;controls.removeAllViews();if(pad!=null){pad.cancelGuides();picture.removeView(pad);}
    for(int i=0;i<7;i++){Button t=(Button)tabs.getChildAt(i);t.setTextColor(tab==i?INK:0xff8a8a85);Drawable glyph=t.getCompoundDrawables()[1];if(glyph instanceof Icon)((Icon)glyph).color=tab==i?INK:0xff8a8a85;t.setTypeface(Typeface.create(Typeface.DEFAULT,tab==i?600:500,false));t.setSelected(tab==i);t.setBackground(new TabBackground(tab==i));t.setContentDescription(tabNames[i]+(tab==i?"、選択中":""));}
    pad=new RawEditPad(this,padSize,state,pairs[tab][0],pairs[tab][1],axes[tab][0],axes[tab][1],new RawEditPad.Listener(){public void changed(RawEditState value){RawEditActivity.this.changed(value);}public void tracking(boolean active){interacting=active||((PhotoSurface)picture).pinch;updateBadge();}});
    picture.addView(pad,new FrameLayout.LayoutParams(dp(padSize+56),dp(padSize+56),Gravity.CENTER));
    if(tab==0||tab==6){boolean mono=tab==6,on=mono?state.get(RawEditState.MONO)==1:maxSaturation;Button toggle=button(mono?"モノクロ":"彩度MAX",()->{if(mono)changed(state.with(RawEditState.MONO,state.get(RawEditState.MONO)==1?0:1));else{maxSaturation=!maxSaturation;requestRender();}renderControls();});
      toggle.setPadding(dp(10),0,dp(14),0);toggle.setContentDescription(mono?"モノクロ。保存に含まれます":"彩度MAX。色を見るための一時表示。保存する彩度は変わりません");toggle.setStateDescription(on?"オン":"オフ");toggle.setSelected(on);GradientDrawable bg=rounded(on?INK:Color.TRANSPARENT,24);bg.setStroke(dp(1),on?INK:0xff3a3a3f);toggle.setBackground(bg);toggle.setTextColor(on?0xff111111:INK);toggle.setCompoundDrawablesWithIntrinsicBounds(new Icon(mono?"mono":"max",20,on?0xff111111:INK),null,null,null);toggle.setCompoundDrawablePadding(dp(6));controls.addView(toggle,new LinearLayout.LayoutParams(-2,dp(48)));}
    if(tab==1)addSingle(4,"かすみ");else if(tab==4)addSingle(11,"コントラスト");else controls.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
    LinearLayout.LayoutParams zp=new LinearLayout.LayoutParams(dp(48),dp(48));zp.leftMargin=dp(8);controls.addView(zoom,zp);updateZoom();enabled(ready&&!saving);root.post(this::layoutControls);
  }
  private void updateZoom(){if(zoom!=null){zoom.setContentDescription(image.zoom>1?"全体を表示":"拡大");zoom.setCompoundDrawablesWithIntrinsicBounds(null,new Icon(image.zoom>1?"zoomOut":"zoomIn",24),null,null);}}
  private void addSingle(int key,String name){
    TextView label=text(name,12,MUTED);controls.addView(label,new LinearLayout.LayoutParams(-2,dp(48)));
    SeekBar bar=new ResetSeekBar(key,name);bar.setMax(200);bar.setProgress(Math.round((state.get(key)+1)*100));bar.setContentDescription(key==4?"かすみの除去":name);bar.setStateDescription(RawEditPad.value(key,state.get(key)));bar.setSplitTrack(false);bar.setPadding(dp(8),0,dp(8),0);bar.setProgressDrawable(new SliderTrack());GradientDrawable thumb=rounded(INK,8);thumb.setSize(dp(16),dp(16));bar.setThumb(thumb);bar.setThumbOffset(dp(8));LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(0,dp(48),1);pp.leftMargin=dp(10);controls.addView(bar,pp);
    bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      public void onProgressChanged(SeekBar v,int n,boolean user){if(user){changed(state.with(key,n/100f-1));bar.setStateDescription(RawEditPad.value(key,state.get(key)));}}
      public void onStartTrackingTouch(SeekBar v){interacting=true;updateBadge();}public void onStopTrackingTouch(SeekBar v){interacting=false;updateBadge();}
    });
  }
  private final class ResetSeekBar extends SeekBar {
    private final int key;private final String name;private final RawEditPad.DoubleTapReset taps;
    ResetSeekBar(int key,String name){super(RawEditActivity.this);this.key=key;this.name=name;taps=new RawEditPad.DoubleTapReset(RawEditActivity.this);}
    private boolean resetValue(){
      if(!isEnabled()||!ready||saving)return false;
      changed(state.reset(key));setProgress(Math.round((state.get(key)+1)*100));setStateDescription(RawEditPad.value(key,state.get(key)));announceForAccessibility(name+"を初期値に戻しました");return true;
    }
    public boolean onTouchEvent(MotionEvent event){if(!isEnabled())return false;boolean reset=taps.completed(event);boolean handled=super.onTouchEvent(event);if(reset)resetValue();return handled;}
    public void setEnabled(boolean enabled){super.setEnabled(enabled);if(!enabled&&taps!=null)taps.cancel();}
    protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE&&taps!=null)taps.cancel();}
    protected void onDetachedFromWindow(){taps.cancel();super.onDetachedFromWindow();}
    public boolean onKeyDown(int code,KeyEvent event){if((code==KeyEvent.KEYCODE_DEL||code==KeyEvent.KEYCODE_FORWARD_DEL)&&resetValue())return true;return super.onKeyDown(code,event);}
    public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);if(isEnabled())info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(RawEditPad.RESET_ACTION,"初期値に戻す"));}
    public boolean performAccessibilityAction(int action,Bundle args){if(action==RawEditPad.RESET_ACTION)return resetValue();return super.performAccessibilityAction(action,args);}
  }
  private void updateBadge(){boolean show=ready&&!saving&&!interacting&&(displayBefore||displayMax);badge.setText(displayBefore?"補正前":"彩度MAX");badge.setVisibility(show?View.VISIBLE:View.GONE);}
  private void changed(RawEditState value){state=value;before=false;compare.setSelected(false);if(pad!=null)pad.update(value);requestRender();}
  private void resetTab(){
    if(!ready||saving)return;int[][] keys={{0,1},{2,3,4},{5,6},{7,8},{9,10,11},{12,13},{14,15,16}};
    state=state.reset(keys[tab]);maxSaturation=false;before=false;renderControls();requestRender();
  }
  private void showHelp(){
    String value=axes[tab][0]+"　"+RawEditPad.value(pairs[tab][0],state.get(pairs[tab][0]))+"\n"+axes[tab][1]+"　"+RawEditPad.value(pairs[tab][1],state.get(pairs[tab][1]));
    new AlertDialog.Builder(this).setTitle(tabNames[tab]).setMessage("写真の中央の面を1本の指で動かすと、横と縦の値が変わります。右の端は縦だけ、下の端は横だけ。\n2本の指で拡大・移動できます。\nガイドは指を離して3秒後に消えます。面は両軸、右・下の端とかすみ・コントラストはその値だけ、ダブルタップで初期値に戻せます。\n\n"+value+(tab==6?"\nモノクロ：色相260・彩度11・輝度+2":"")+"\n\n精細な表示と保存は原寸の準備のあと。準備中も編集できます。\n右上の格子に三角の印は原寸の準備の失敗。押すと再試行。\n原寸 "+width+" × "+height+"："+(fullSession!=null&&fullSession.isPrepared()?"完了":preparationFailed?"失敗":"準備中")+"\n拡大の上限 "+String.format(java.util.Locale.ROOT,"%.2f",image.maxZoom())+"倍").setPositiveButton("閉じる",null).show();
  }
  private final class PhotoSurface extends FrameLayout {
    boolean pinch;RawEditState start;boolean startBefore;
    PhotoSurface(){super(RawEditActivity.this);}
    public boolean dispatchTouchEvent(MotionEvent e){
      int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN){pinch=false;start=state;startBefore=before;}
      if(e.getPointerCount()>=2&&!pinch){pinch=true;if(pad!=null)pad.cancelGuides();if(start!=null&&ready&&!saving){state=start;before=startBefore;compare.setSelected(before);if(pad!=null)pad.update(state);requestRender();}interacting=true;updateBadge();}
      if(image!=null)image.multi(e);
      boolean handled=super.dispatchTouchEvent(e);
      if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){pinch=false;interacting=false;updateBadge();updateZoom();}return handled;
    }
    public boolean onInterceptTouchEvent(MotionEvent e){return pinch||e.getPointerCount()>=2;}
    public boolean onTouchEvent(MotionEvent e){return pinch||super.onTouchEvent(e);}
  }
  private class RowBackground extends Drawable {
    final Paint p=new Paint();public void draw(Canvas c){p.setColor(BG);c.drawRect(getBounds(),p);p.setColor(0xff1a1a1d);c.drawRect(0,0,getBounds().width(),dp(1),p);}public void setAlpha(int a){}public void setColorFilter(ColorFilter f){}public int getOpacity(){return PixelFormat.OPAQUE;}
  }
  private final class TabBackground extends RowBackground {
    final boolean selected;TabBackground(boolean s){selected=s;}public void draw(Canvas c){super.draw(c);p.setColor(selected?INK:0xff222226);float w=getBounds().width();c.drawRect(selected?w*.25f:0,0,selected?w*.75f:w,dp(selected?2:1),p);}
  }
  private final class SliderTrack extends Drawable {
    final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);public void draw(Canvas c){float y=getBounds().exactCenterY();p.setColor(0xff3a3a3f);p.setStrokeWidth(dp(2));c.drawLine(0,y,getBounds().width(),y,p);p.setColor(0xff6b6b70);c.drawLine(getBounds().exactCenterX(),y-dp(6),getBounds().exactCenterX(),y+dp(6),p);}public int getIntrinsicHeight(){return dp(48);}public void setAlpha(int a){}public void setColorFilter(ColorFilter f){}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
  }
  private static void setChildrenEnabled(View v,boolean enabled){v.setEnabled(enabled);if(v instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)v).getChildCount();i++)setChildrenEnabled(((android.view.ViewGroup)v).getChildAt(i),enabled);}
  private void enabled(boolean value){compare.setEnabled(value);reset.setEnabled(value);save.setEnabled(value);zoom.setEnabled(value);setChildrenEnabled(controls,value);if(pad!=null)setChildrenEnabled(pad,value);help.setEnabled(!saving);setChildrenEnabled(tabs,value);updateBadge();}
  private void showPicker(){status.setText("RAW / JPEGを選択\n実験版・原寸で別保存");status.setOnClickListener(v->pick());status.setClickable(true);zoom.setVisibility(View.GONE);}
  private void pick(){
    Intent choose=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
    try{startActivityForResult(choose,53);}catch(ActivityNotFoundException e){status.setText("写真を選択できるアプリがありません");}
  }
  protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==53&&result==RESULT_OK&&data!=null&&data.getData()!=null){input=data.getData();state=new RawEditState();saved=state;load();}}
  private RawEditSource syntheticSource(){
    int w=1200,h=800;ByteBuffer b=ByteBuffer.allocateDirect(w*h*16).order(ByteOrder.nativeOrder());FloatBuffer f=b.asFloatBuffer();
    int[] top={134,168,200},sky={201,214,222},ground={125,143,106},bottom={78,93,64},red={201,87,62},beige={232,210,176};
    for(int y=0;y<h;y++)for(int x=0;x<w;x++){
      float yy=y/(float)(h-1),xx=x/(float)(w-1);int[] lo=yy<=.52f?top:yy<.53f?sky:ground,hi=yy<=.52f?sky:yy<.53f?ground:bottom;
      float amount=yy<=.52f?yy/.52f:yy<.53f?(yy-.52f)/.01f:(yy-.53f)/.47f;
      float small=(float)Math.hypot((xx-.7f)/.09f,(yy-.34f)/.12f),large=(float)Math.hypot((xx-.36f)/.14f,(yy-.58f)/.2f);
      float a=Math.max(0,Math.min(1,(1-small)/.04f)),c=Math.max(0,Math.min(1,(1-large)/.04f));
      for(int k=0;k<3;k++){float srgb=(lo[k]+(hi[k]-lo[k])*amount)/255f;srgb=srgb*(1-a)+red[k]/255f*a;srgb=srgb*(1-c)+beige[k]/255f*c;f.put(srgb<=.04045f?srgb/12.92f:(float)Math.pow((srgb+.055f)/1.055f,2.4));}f.put(1);
    }
    return RawEditSource.synthetic(w,h,b,6000,4000);
  }
  private boolean current(int token){return !dead&&visible&&token==epoch;}
  private boolean current(RawEditFullSession session,int token){return current(token)&&fullSession==session;}
  private void load(){
    if(dead||loading)return;
    if(fullSession!=null){fullSession.close();fullSession=null;}
    pendingFullPreview=null;previewSwapQueued=false;saveWaiting=false;preparationFailed=false;fullBadgeShown=false;
    main.removeCallbacks(showFullBadge);main.removeCallbacks(replaceFullPreview);updateFullUi();
    loading=true;ready=false;int token=++epoch;enabled(false);status.setText("読み込み中…\nタップで中止");status.setClickable(true);status.setOnClickListener(v->onBackPressed());zoom.setVisibility(View.GONE);
    long started=SystemClock.elapsedRealtime();
    loadFuture=RawEditFullSession.initial(()->{
      RawEditSource loaded=null;
      try {
        loaded=synthetic()?syntheticSource():RawEditSource.load(this,input);
        if(!current(token)||Thread.currentThread().isInterrupted()){loaded.close();return;}
        RawEditSource source=loaded;loaded=null;pendingSources.add(source);
        try{worker.execute(()->uploadInitial(source,token,started));}catch(RejectedExecutionException rejected){pendingSources.remove(source);source.close();}
      }catch(IOException|RuntimeException|LinkageError|OutOfMemoryError failure){
        if(loaded!=null)loaded.close();loadFailed(token,started,failure);
      }
    });
  }
  private void uploadInitial(RawEditSource source,int token,long started){
    RawEditRenderer created=null;Bitmap initial=null;boolean handedOff=false;
    try {
      if(!current(token)||Thread.currentThread().isInterrupted())return;
      if(renderer!=null){renderer.close();renderer=null;}
      created=new RawEditRenderer(source.width,source.height,source.pixels);initial=created.render(state,false,false,1024);
      if(!current(token))return;
      RawEditRenderer uploaded=created;Bitmap frame=initial;renderer=created;
      handedOff=main.post(()->{
        pendingSources.remove(source);
        if(!current(token)){frame.recycle();source.close();try{worker.execute(()->{if(renderer==uploaded){uploaded.close();renderer=null;}});}catch(RejectedExecutionException ignored){}return;}
        try {
          previewWidth=source.width;previewHeight=source.height;width=source.originalWidth();height=source.originalHeight();
          image.setFullDimensions(width,height);image.replace(frame);displayBefore=displayMax=false;
          status.setText("");status.setClickable(false);status.setOnClickListener(null);loading=false;ready=true;rendered=-1;
          attachFullSession(source,token);zoom.setVisibility(View.VISIBLE);enabled(true);renderControls();requestRender();
          RawEditLog.event(source.raw?"raw.load.complete":"jpeg.load.complete",SystemClock.elapsedRealtime()-started,source.width,source.height);
        }catch(RuntimeException|OutOfMemoryError failure){if(fullSession!=null){fullSession.close();fullSession=null;}source.close();ready=false;image.clear();try{worker.execute(()->{if(renderer==uploaded){uploaded.close();renderer=null;}});}catch(RejectedExecutionException ignored){}loadFailed(token,started,failure);}
      });
    }catch(IOException|RuntimeException|LinkageError|OutOfMemoryError failure){loadFailed(token,started,failure);}
    finally{if(!handedOff){pendingSources.remove(source);if(initial!=null)initial.recycle();if(created!=null){created.close();if(renderer==created)renderer=null;}source.close();}}
  }
  private void loadFailed(int token,long started,Throwable failure){
    RawEditLog.event(failure instanceof OutOfMemoryError?"load.failed.memory":failure instanceof LinkageError?"load.failed.native":failure instanceof IOException?"load.failed.io":"load.failed.runtime",SystemClock.elapsedRealtime()-started,0,0);
    main.post(()->{if(!current(token))return;loading=false;ready=false;status.setText("読み込めませんでした\nタップで再試行");status.setClickable(true);status.setOnClickListener(v->load());});
  }
  private void attachFullSession(RawEditSource source,int token){
    final RawEditFullSession[] holder=new RawEditFullSession[1];
    RawEditFullSession session=new RawEditFullSession(this,source,worker,main,
        ()->interacting||queued.get()||rendered!=revision.get()||previewSwapQueued,
        new RawEditFullSession.Listener(){
          public void prepared(int w,int h,RawEditFullSource.Preview preview){
            if(!current(holder[0],token))return;
            width=w;height=h;preparationFailed=false;fullBadgeShown=false;main.removeCallbacks(showFullBadge);
            image.setFullDimensions(w,h);pendingFullPreview=preview;main.removeCallbacks(replaceFullPreview);main.postDelayed(replaceFullPreview,120);updateFullUi();
            if(saveWaiting){saveWaiting=false;updateFullUi();actualConfirmSave();}
          }
          public void prepareFailed(){
            if(!current(holder[0],token))return;preparationFailed=true;fullBadgeShown=true;saveWaiting=false;image.setFullDimensions(previewWidth,previewHeight);updateFullUi();
          }
          public void detail(Bitmap bitmap,RectF region,long edit,long viewport,boolean original,boolean max){
            if(current(holder[0],token)&&ready&&!saving&&edit==revision.get()&&viewport==image.viewportRevision()&&original==before&&max==maxSaturation)image.setDetail(bitmap,region);else bitmap.recycle();
          }
          public void saved(RawEditState snapshot){
            if(!current(holder[0],token))return;saving=false;saved=snapshot;busyIcon.setVisibility(View.GONE);status.setText("");enabled(true);updateFullUi();save.setText("✓");save.announceForAccessibility("保存しました");main.postDelayed(()->{if(current(holder[0],token)&&!saving&&!saveWaiting)updateFullUi();},900);requestDetailForCurrent();
          }
          public void saveFailed(){if(!current(holder[0],token))return;finishSaveUi();status.setText("保存できませんでした。もう一度保存できます");}
          public void saveCancelled(){if(!current(holder[0],token))return;finishSaveUi();status.setText("");}
        });
    holder[0]=session;fullSession=session;session.start();main.postDelayed(showFullBadge,400);
  }
  private void retryPreparation(){
    if(!ready||fullSession==null||!fullSession.hasFailed())return;
    preparationFailed=false;fullBadgeShown=false;image.setFullDimensions(width,height);fullSession.retry();main.removeCallbacks(showFullBadge);main.postDelayed(showFullBadge,400);updateFullUi();
  }
  private void updateFullUi(){
    if(save==null||fullBadge==null)return;
    boolean show=ready&&fullBadgeShown&&fullSession!=null&&!fullSession.isPrepared();fullBadge.setVisibility(show?View.VISIBLE:View.GONE);fullBadge.setEnabled(preparationFailed&&!saving);
    fullBadge.setContentDescription(preparationFailed?"原寸を準備できませんでした。低い解像度で表示と編集を続けられます。拡大はプレビューの等倍まで、保存は原寸の準備のあとです。押すと再試行":"精細な表示を準備しています。編集はそのまま続けられます");fullBadge.invalidate();
    FrameLayout.LayoutParams fp=(FrameLayout.LayoutParams)fullBadge.getLayoutParams();int fullMargin=dp(preparationFailed?2:11);if(fp.rightMargin!=fullMargin){fp.rightMargin=fullMargin;fullBadge.setLayoutParams(fp);}
    FrameLayout.LayoutParams ip=(FrameLayout.LayoutParams)busyIcon.getLayoutParams();ip.rightMargin=dp(show?64:12);busyIcon.setLayoutParams(ip);
    save.setText("保存");save.setPadding(0,0,0,0);save.setCompoundDrawablePadding(dp(4));save.setBackground(rounded(saveWaiting||saving?0xff2a2a2e:INK,24));save.setTextColor(saveWaiting||saving?0xffc9c9c4:0xff111111);
    save.setCompoundDrawablesWithIntrinsicBounds(saveWaiting||saving?new Icon("spinner",16):null,null,preparationFailed?new Icon("warning",12,0xffff8a7a):null,null);
    save.setStateDescription(saveWaiting?"原寸の準備を待っています。押すと中止":saving?"保存中":preparationFailed?"原寸の準備を再試行":null);
  }
  private void viewportChanged(){
    if(!ready)return;image.clearDetail();if(fullSession!=null)fullSession.input();updateZoom();requestDetailForCurrent();
  }
  private void requestDetailForCurrent(){
    RawEditFullSession session=fullSession;
    if(!ready||saving||session==null||!session.isPrepared()||image.zoom<=1||queued.get()||rendered!=revision.get()||pendingFullPreview!=null||previewSwapQueued)return;
    RectF region=image.visibleSource();int[] size=image.detailSize(region);if(region.isEmpty()||size[0]<1||size[1]<1)return;
    session.requestDetail(state,revision.get(),image.viewportRevision(),before,maxSaturation,region,size[0],size[1]);
  }
  private void replacePreparedPreview(){
    RawEditFullSource.Preview preview=pendingFullPreview;
    if(preview==null||!ready||dead||!visible||previewSwapQueued)return;
    if(interacting||queued.get()||rendered!=revision.get()||SystemClock.elapsedRealtime()-lastInputMs<120){main.postDelayed(replaceFullPreview,120);return;}
    int token=epoch;previewSwapQueued=true;
    worker.execute(()->{
      RawEditRenderer next=null;boolean adopted=false;
      try {
        if(!current(token))return;
        next=new RawEditRenderer(preview.width,preview.height,preview.pixels);
        if(!current(token))return;
        RawEditRenderer previous=renderer;renderer=next;adopted=true;if(previous!=null)previous.close();
        main.post(()->{if(!current(token))return;previewSwapQueued=false;if(pendingFullPreview==preview)pendingFullPreview=null;requestRender();});
      }catch(IOException|RuntimeException|OutOfMemoryError failure){main.post(()->{if(!current(token))return;previewSwapQueued=false;pendingFullPreview=null;requestDetailForCurrent();});}
      finally{if(next!=null&&!adopted)next.close();}
    });
  }
  private void requestRender(){lastInputMs=SystemClock.elapsedRealtime();image.clearDetail();if(fullSession!=null)fullSession.input();long inputNs=SystemClock.elapsedRealtimeNanos(),requested=revision.incrementAndGet();latest=new RenderRequest(requested,inputNs,state,before,maxSaturation);RawEditLog.input(requested,inputNs);main.removeCallbacks(busy);main.postDelayed(busy,400);scheduleRender();}
  private void scheduleRender(){
    if(!ready||dead||!visible||latest==null||!queued.compareAndSet(false,true))return;
    int token=epoch;
    worker.execute(()->{
      if(!current(token))return;
      RenderRequest request=latest;long ticket=request.ticket,requestedAt=request.inputNs,start=SystemClock.elapsedRealtime();
      RawEditState snapshot=request.state;boolean original=request.before,boost=request.max;
      try{
        Bitmap b=renderer.render(snapshot,original,boost,1024);
        RawEditLog.event("adjust.render",SystemClock.elapsedRealtime()-start,b.getWidth(),b.getHeight());
        main.post(()->{
          // Present completed work even while a newer request is pending. The single worker
          // and UI handoff preserve order; equality with the newest input would starve drags.
          if(!current(token)){b.recycle();return;}
          image.replace(b,ticket,requestedAt);displayBefore=original;displayMax=boost;status.setText("");updateBadge();
          rendered=ticket;if(ticket==revision.get()&&!saving)busyIcon.setVisibility(View.GONE);queued.set(false);
          if(rendered!=revision.get())image.postOnAnimation(this::scheduleRender);else {requestDetailForCurrent();if(pendingFullPreview!=null){main.removeCallbacks(replaceFullPreview);main.postDelayed(replaceFullPreview,120);}}
        });
      }catch(IOException|RuntimeException|OutOfMemoryError e){
        main.post(()->{if(!current(token))return;rendered=ticket;if(ticket==revision.get()&&!saving)busyIcon.setVisibility(View.GONE);queued.set(false);if(!dead&&visible&&token==epoch)status.setText("反映できませんでした。値を動かすと再試行します");if(rendered!=revision.get())image.postOnAnimation(this::scheduleRender);});
      }
    });
  }
  private void confirmSave(){
    if(!ready||saving||fullSession==null)return;
    if(saveWaiting){saveWaiting=false;updateFullUi();return;}
    maxSaturation=false;before=false;compare.setSelected(false);renderControls();requestRender();
    if(!fullSession.isPrepared()){
      saveWaiting=true;if(fullSession.hasFailed())retryPreparation();updateFullUi();return;
    }
    actualConfirmSave();
  }
  private void actualConfirmSave(){
    RawEditFullSession session=fullSession;int token=epoch;
    if(!ready||saving||session==null||!session.isPrepared()||!current(token)||isFinishing()||saveDialog!=null)return;
    if(before||maxSaturation){before=false;maxSaturation=false;compare.setSelected(false);renderControls();requestRender();}
    saveDialog=new AlertDialog.Builder(this).setTitle("JPEGで保存").setMessage(width+" × "+height+" px　JPEG\n実験版・別のファイルとして保存"+(state.get(RawEditState.MONO)==1?"\nモノクロ":""))
      .setNegativeButton("キャンセル",null).setPositiveButton("保存",(d,w)->{if(current(session,token))saveImage();}).create();
    saveDialog.setOnDismissListener(d->saveDialog=null);saveDialog.show();
  }
  private void saveImage(){
    if(!ready||saving||dead||!visible||isFinishing()||fullSession==null||!fullSession.isPrepared())return;
    if(before||maxSaturation){before=false;maxSaturation=false;compare.setSelected(false);renderControls();requestRender();}
    saveWaiting=false;saving=true;image.clearDetail();fullSession.input();enabled(false);updateFullUi();busyIcon.setVisibility(View.VISIBLE);fullSession.save(state);
  }
  private void finishSaveUi(){saving=false;busyIcon.setVisibility(View.GONE);enabled(true);updateFullUi();scheduleRender();requestDetailForCurrent();}
  public void onBackPressed(){
    if(saveWaiting){saveWaiting=false;updateFullUi();return;}
    if(saving){if(fullSession!=null)fullSession.cancelSave();return;}
    if(!state.equals(saved)){new AlertDialog.Builder(this).setTitle("変更を破棄しますか？").setNegativeButton("編集を続ける",null).setPositiveButton("破棄",(d,w)->finish()).show();}
    else finish();
  }
  protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putFloatArray("values",state.snapshot());b.putFloatArray("saved",saved.snapshot());b.putInt("tab",tab);b.putInt("uiVersion",2);b.putFloat("zoom",image.zoom);b.putFloat("panX",image.dx);b.putFloat("panY",image.dy);if(input!=null)b.putString("input",input.toString());}
  protected void onStart(){super.onStart();visible=true;if(root!=null&&(input!=null||synthetic())&&!ready&&!loading)load();else if(ready){save.setText("保存");save.setStateDescription(null);enabled(true);requestRender();}}
  protected void onStop(){
    if(pad!=null)pad.cancelGuides();
    visible=false;++epoch;maxSaturation=false;before=false;saveWaiting=false;
    main.removeCallbacks(busy);main.removeCallbacks(showFullBadge);main.removeCallbacks(replaceFullPreview);
    if(saveDialog!=null){saveDialog.dismiss();saveDialog=null;}
    if(loadFuture!=null&&loading)loadFuture.cancel(true);
    if(fullSession!=null){fullSession.close();fullSession=null;}
    for(RawEditSource pending:pendingSources)pending.close();pendingSources.clear();
    pendingFullPreview=null;previewSwapQueued=false;loading=false;saving=false;ready=false;fullBadgeShown=false;queued.set(false);image.clear();updateFullUi();
    worker.execute(()->{if(renderer!=null){renderer.close();renderer=null;}});super.onStop();
  }
  protected void onDestroy(){
    if(pad!=null)pad.cancelGuides();
    dead=true;ready=false;main.removeCallbacksAndMessages(null);if(loadFuture!=null)loadFuture.cancel(true);
    if(fullSession!=null){fullSession.close();fullSession=null;}for(RawEditSource pending:pendingSources)pending.close();pendingSources.clear();pendingFullPreview=null;
    worker.execute(()->{if(renderer!=null){renderer.close();renderer=null;}});worker.shutdown();image.clear();super.onDestroy();
  }
  private final class FullBadge extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    FullBadge(){super(RawEditActivity.this);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);}
    protected void onDraw(Canvas canvas){
      super.onDraw(canvas);float cx=getWidth()/2f,cy=getHeight()/2f;RectF bg=new RectF(cx-dp(23),cy-dp(14),cx+dp(23),cy+dp(14));
      paint.setStyle(Paint.Style.FILL);paint.setColor(0x9e000000);canvas.drawRoundRect(bg,dp(14),dp(14),paint);
      if(preparationFailed){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.5f));paint.setColor(0xffff8a7a);canvas.drawRoundRect(bg,dp(14),dp(14),paint);}
      canvas.save();canvas.translate(cx-dp(17),cy-dp(8));canvas.scale(dp(16)/24f,dp(16)/24f);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setColor(INK);for(float gx:new float[]{4,13})for(float gy:new float[]{4,13})canvas.drawRoundRect(gx,gy,gx+7,gy+7,1,1,paint);canvas.restore();
      if(preparationFailed){canvas.save();canvas.translate(cx+dp(1),cy-dp(8));new Icon("warning",16,0xffff8a7a).draw(canvas);canvas.restore();}
      else{float radius=dp(16f/3);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.6f));paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(0x2effffff);canvas.drawCircle(cx+dp(9),cy,radius,paint);paint.setColor(INK);canvas.save();canvas.rotate((SystemClock.uptimeMillis()%1000)*.36f,cx+dp(9),cy);canvas.drawArc(cx+dp(9)-radius,cy-radius,cx+dp(9)+radius,cy+radius,-90,90,false,paint);canvas.restore();postInvalidateOnAnimation();}
    }
    public boolean performClick(){super.performClick();return true;}
  }

  private final class Icon extends Drawable {
    final String type;final int size;int color,alpha=255;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    Icon(String t,int s){this(t,s,INK);}Icon(String t,int s,int ink){type=t;size=s;color=ink;setBounds(0,0,dp(s),dp(s));}
    public int getIntrinsicWidth(){return dp(size);}public int getIntrinsicHeight(){return dp(size);}
    public void draw(Canvas canvas){
      canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setColor(color);p.setAlpha(alpha);
      if(type.equals("spinner")){canvas.rotate((SystemClock.uptimeMillis()%1000)*.36f,12,12);canvas.drawArc(4,4,20,20,-90,270,false,p);scheduleSelf(this::invalidateSelf,SystemClock.uptimeMillis()+16);}
      else if(type.equals("warning")){Path q=new Path();q.moveTo(12,4);q.lineTo(21,19.5f);q.lineTo(3,19.5f);q.close();canvas.drawPath(q,p);canvas.drawLine(12,10,12,14.5f,p);canvas.drawLine(12,17.2f,12,17.3f,p);}
      else if(type.equals("compare")){canvas.drawRoundRect(4,5,20,19,2,2,p);canvas.drawLine(12,5,12,19,p);p.setStyle(Paint.Style.FILL);p.setAlpha(Math.round(alpha*.35f));canvas.drawRect(12,5,19,19,p);}
      else if(type.equals("reset")){canvas.drawArc(4.5f,4.5f,19.5f,19.5f,180,315,false,p);canvas.drawLine(4.5f,4.5f,4.5f,8.5f,p);canvas.drawLine(4.5f,8.5f,8.5f,8.5f,p);}
      else if(type.equals("help")){canvas.drawCircle(12,12,9,p);Path q=new Path();q.moveTo(9.6f,9.3f);q.cubicTo(9.5f,5.7f,16.1f,6.3f,14.8f,10.4f);q.cubicTo(14.3f,12,12,11.3f,12,13.5f);canvas.drawPath(q,p);canvas.drawLine(12,16.6f,12,16.7f,p);}
      else if(type.equals("zoomIn")||type.equals("zoomOut")){canvas.drawCircle(10.5f,10.5f,6.5f,p);canvas.drawLine(15.5f,15.5f,21,21,p);canvas.drawLine(7.5f,10.5f,13.5f,10.5f,p);if(type.equals("zoomIn"))canvas.drawLine(10.5f,7.5f,10.5f,13.5f,p);}
      else if(type.equals("mono")||type.equals("tab4")||type.equals("tab0")){canvas.drawCircle(12,12,7.5f,p);if(!type.equals("mono"))canvas.drawLine(12,4.5f,12,19.5f,p);p.setStyle(Paint.Style.FILL);p.setAlpha(Math.round(alpha*(type.equals("tab0")?.45f:type.equals("tab4")?.8f:1f)));canvas.drawArc(4.5f,4.5f,19.5f,19.5f,type.equals("tab0")?-90:90,180,true,p);}
      else if(type.equals("max")){Path path=new Path();path.moveTo(4,18);path.lineTo(9,9);path.lineTo(13,15);path.lineTo(16,11);path.lineTo(20,18);path.close();canvas.drawPath(path,p);}
      else if(type.equals("tab1")){canvas.drawRoundRect(4,4,20,20,2.5f,2.5f,p);p.setStrokeWidth(1.2f);canvas.drawLine(4,9,20,9,p);canvas.drawLine(4,15,20,15,p);canvas.drawLine(9,4,9,20,p);canvas.drawLine(15,4,15,20,p);}
      else if(type.equals("tab2")){canvas.drawRoundRect(4,4,20,20,2.5f,2.5f,p);Path path=new Path();path.moveTo(5.5f,18.5f);path.cubicTo(9,18,9,8,12,9.5f);path.cubicTo(15,11,15.5f,5.5f,18.5f,5.5f);canvas.drawPath(path,p);}
      else if(type.equals("tab3")){canvas.drawCircle(12,12,3.8f,p);for(int i=0;i<8;i++){double a=i*Math.PI/4;canvas.drawLine(12+(float)Math.cos(a)*6.5f,12+(float)Math.sin(a)*6.5f,12+(float)Math.cos(a)*8.5f,12+(float)Math.sin(a)*8.5f,p);}}
      else if(type.equals("tab5")){p.setStyle(Paint.Style.FILL);canvas.drawRoundRect(4,6,11,18,1.5f,1.5f,p);p.setStyle(Paint.Style.STROKE);canvas.drawRoundRect(13,6,20,18,1.5f,1.5f,p);}
      else {canvas.drawCircle(9,10,5,p);canvas.drawCircle(15,10,5,p);canvas.drawCircle(12,15,5,p);}canvas.restore();
    }
    public void setAlpha(int a){alpha=a;invalidateSelf();}public void setColorFilter(ColorFilter f){p.setColorFilter(f);invalidateSelf();}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
  }
}
