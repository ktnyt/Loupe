package jp.nano.rawgallery;

import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;

/** Read-only display of small persisted observations. Never enumerates photos or starts jobs. */
public final class DashboardActivity extends DiagnosticActivity {
  private static final String[] NAMES={"読み込み中の仮画像","表示用プレビュー","タグのための写真の解析","自動のタグ付け"};
  private static final String[] TECH={"ThumbHash","プレビューキャッシュ","画像の特徴の解析","自動のタグ付け"};
  private static final String[] ABOUT={
    "写真一覧で、サムネイルが届くまでのあいだに出す、ぼかした仮画像を準備します。表示用プレビューと同じ準備ジョブで作ります。",
    "写真を開いたときにすぐ表示できるよう、表示用の縮小画像を前もって作ります。仮画像と同じ準備ジョブで作ります。",
    "「似た写真にもタグを付ける」で候補を探すために、写真を端末内で解析します。","解析の結果から自動で付けたタグの数です。"};
  private static final String[] COUNTS={"この工程だけの件数と結果はまだ記録していません。",
    "準備済みは、ファイルがあることを確かめて準備に成功した数です（すべてを読み込んで確かめた数ではありません）。対象は直近に数えた端末内の写真です。",
    "件数はタグの画面の中だけで数えていて、ここではまだ使えません。","件数はタグの画面の中だけで数えていて、ここではまだ使えません。"};
  interface Source { DashboardObservation read(); }
  Source source=()->new DashboardObservation(PreviewPreparationJob.snapshot(this),PreviewPreparationJob.browsing());
  private final ExecutorService worker=new DiagnosticExecutor("Dashboard.read",1);
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final ArrayList<Runnable> bindings=new ArrayList<>();
  private final Runnable refresh=this::read;
  private DashboardObservation observation=DashboardObservation.empty();
  private LinearLayout root,content;
  private ScrollView scroll;
  private TextView heading, announcement;
  private int detail=-1,listY,epoch;
  private final View[] cards=new View[4];
  private boolean returnAccessibilityFocus, returnKeyboardFocus;
  private boolean foreground;
  @Override public void onCreate(Bundle saved){
    super.onCreate(saved);
    if(saved!=null){detail=saved.getInt("detail",-1);listY=saved.getInt("listY");observation=DashboardObservation.restore(saved.getBundle("observation"));returnAccessibilityFocus=saved.getBoolean("returnAX");returnKeyboardFocus=saved.getBoolean("returnKeyboard");}
    root=column();root.setBackgroundColor(GalleryUi.BG);
    root.setOnApplyWindowInsetsListener((v,insets)->{Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(i.left,i.top,i.right,i.bottom);return insets;});
    heading=text("",22,GalleryUi.TEXT);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));heading.setPadding(dp(20),dp(12),dp(20),dp(10));heading.setAccessibilityHeading(true);root.addView(heading);
    // Only this stable node is live: changing counts must not announce on every refresh.
    announcement=new TextView(this);announcement.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(announcement,new LinearLayout.LayoutParams(1,1));
    scroll=new ScrollView(this);scroll.setFillViewport(false);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);screen();
    if(saved!=null){int y=saved.getInt("scrollY");scroll.post(()->scroll.scrollTo(0,y));}
  }
  @Override protected void onResume(){super.onResume();foreground=true;epoch++;read();}
  @Override protected void onPause(){foreground=false;epoch++;handler.removeCallbacks(refresh);super.onPause();}
  @Override protected void onStop(){handler.removeCallbacks(refresh);super.onStop();}
  @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
  @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putBoolean("returnAX",returnAccessibilityFocus);b.putBoolean("returnKeyboard",returnKeyboardFocus);b.putInt("detail",detail);b.putInt("listY",detail<0?scroll.getScrollY():listY);b.putInt("scrollY",scroll.getScrollY());b.putBundle("observation",observation.save());}
  private void read(){
    if(!foreground)return;
    final int token=epoch;
    worker.execute(()->{
      DashboardObservation next;
      try(DiagnosticLog.Scope ignored=DiagnosticLog.span("dashboard.snapshot")){next=source.read();}
      catch(RuntimeException e){next=null;DiagnosticLog.event("dashboard.read_failed",0,"type",e.getClass().getSimpleName());}
      final DashboardObservation result=next;
      handler.post(()->{
        if(!foreground||epoch!=token)return;
        String previous=observation.stateKey();observation=result==null?observation.unreadable():result;
        for(Runnable bind:bindings)bind.run();
        if(!previous.equals(observation.stateKey()))announcement.setContentDescription(observation.jobDescription());
        handler.postDelayed(refresh,5000);
      });
    });
  }
  private void screen(){
    bindings.clear();content=column();content.setPadding(0,0,0,dp(20));scroll.removeAllViews();scroll.addView(content);
    heading.setText(detail<0?"処理の状況":NAMES[detail]);
    if(detail<0){
      label("表示の準備",4);job();condition();card(0);card(1);label("タグ",16);card(2);card(3);
    }else{
      LinearLayout panel=row();panel.setPadding(dp(14),dp(12),dp(14),dp(12));panel.setMinimumHeight(dp(56));panel.setBackground(GalleryUi.shape(this,GalleryUi.PANEL,14));
      panel.addView(new Mark(StatusView.Symbol.UNKNOWN),new LinearLayout.LayoutParams(dp(24),dp(24)));panel.setContentDescription(NAMES[detail]+"。この工程だけの状態は記録されていません。");margin(panel,16,0,16,12);
      String[] labels=fields(detail);for(int n=0;n<labels.length;n++)detailField(labels[n],detail,n);
      detailTime();
      if(detail<2){View gap=new View(this);content.addView(gap,new LinearLayout.LayoutParams(1,dp(12)));job();condition();}
      fixed("",ABOUT[detail]);fixed("技術名",TECH[detail]);fixed("数値について",COUNTS[detail]);
      LinearLayout legend=column();fixedInto(legend,"印について","時計に戻る矢印の印は、前回の観測値と時刻を表します。現在の進行状況を確かめた値ではありません。");content.addView(legend);bindings.add(()->legend.setVisibility(detail==1&&observation.old()&&(observation.known||observation.at>0)||detail==0&&observation.old()&&observation.at>0?View.VISIBLE:View.GONE));
    }
    for(Runnable bind:bindings)bind.run();
  }
  @Override public void onBackPressed(){if(detail>=0){int selected=detail;detail=-1;screen();scroll.post(()->{if(returnKeyboardFocus){cards[selected].setFocusableInTouchMode(true);cards[selected].requestFocus();}scroll.scrollTo(0,listY);if(returnAccessibilityFocus)cards[selected].performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,null);});}else super.onBackPressed();}
  private void card(int index){
    LinearLayout card=column();cards[index]=card;card.setPadding(dp(14),dp(12),dp(14),dp(12));card.setMinimumHeight(dp(72));
    card.setBackground(new RippleDrawable(ColorStateList.valueOf(0xff26262a),GalleryUi.shape(this,GalleryUi.PANEL,14),GalleryUi.shape(this,Color.WHITE,14)));
    LinearLayout title=row();TextView name=text(NAMES[index],15,GalleryUi.TEXT);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));title.addView(name,new LinearLayout.LayoutParams(0,-2,1));title.addView(new Mark(StatusView.Symbol.UNKNOWN),new LinearLayout.LayoutParams(dp(24),dp(24)));TextView chevron=text("›",24,GalleryUi.MUTED);chevron.setGravity(Gravity.CENTER);title.addView(chevron,new LinearLayout.LayoutParams(dp(26),-2));card.addView(title);
    Flow fields=new Flow();String[] labels=fields(index);for(int n=0;n<labels.length;n++){final int field=n;LinearLayout value=row();value.addView(text(labels[n]+"  ",12,GalleryUi.MUTED));TextView number=text("—",14,GalleryUi.TEXT);value.addView(number);fields.addView(value);bindings.add(()->setValue(number,value(index,field),index==1&&observation.known&&observation.old()));}
    TextView time=text("—",12,GalleryUi.MUTED);time.setTag("trailing");fields.addView(time);bindings.add(()->setValue(time,index==1?observation.time():"—",index==1&&observation.at>0&&observation.old()));
    LinearLayout.LayoutParams fieldsParams=new LinearLayout.LayoutParams(-1,-2);fieldsParams.topMargin=dp(6);card.addView(fields,fieldsParams);
    if(index==1){Progress bar=new Progress();LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(20));card.addView(bar,bp);bindings.add(()->{bar.setVisibility(observation.known&&observation.total>0?View.VISIBLE:View.GONE);bar.invalidate();});}
    card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);card.setFocusable(true);card.setClickable(true);title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);fields.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    bindings.add(()->card.setContentDescription(cardDescription(index)+"。詳細を開く"));card.setOnClickListener(v->{returnAccessibilityFocus=card.isAccessibilityFocused();returnKeyboardFocus=card.hasFocus();listY=scroll.getScrollY();detail=index;screen();scroll.scrollTo(0,0);if(returnAccessibilityFocus)heading.post(()->heading.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,null));});margin(card,16,0,16,8);
  }
  private String[] fields(int index){return index==3?new String[]{"付けた数"}:index==1?new String[]{"準備済み","対象"}:new String[]{index==2?"解析済み":"準備済み","対象","失敗"};}
  private String value(int index,int field){return index==1?observation.count(field==0?observation.ready:observation.total):"—";}
  private String cardDescription(int index){String s=NAMES[index]+"。この工程だけの状態は記録されていません。";String[] labels=fields(index);for(int n=0;n<labels.length;n++)s+=labels[n]+"、"+(value(index,n).equals("—")?"未計測":value(index,n)+(observation.old()?"、前回の観測値":""))+"。";return s+(index==1?(observation.at>0?(observation.old()?"前回の観測時刻、":"観測時刻、")+observation.time():"時刻の記録はありません"):"時刻の記録はありません");}
  private void job(){
    Flow box=new Flow();box.setPadding(dp(14),dp(8),dp(14),dp(8));box.setMinimumHeight(dp(48));box.setBackground(dashed());box.addView(text("共通の準備ジョブ",12,GalleryUi.MUTED));
    Mark state=new Mark(StatusView.Symbol.UNKNOWN);box.addView(state,new ViewGroup.LayoutParams(dp(24),dp(24)));Mark reason=new Mark(StatusView.Symbol.UNKNOWN);box.addView(reason,new ViewGroup.LayoutParams(dp(24),dp(24)));
    LinearLayout badge=row();badge.setPadding(dp(4),dp(2),dp(8),dp(2));badge.setMinimumHeight(dp(28));badge.addView(new Mark(StatusView.Symbol.FAILED),new LinearLayout.LayoutParams(dp(20),dp(20)));TextView failed=text("",13,GalleryUi.TEXT);badge.addView(failed);box.addView(badge);TextView time=text("—",12,GalleryUi.MUTED);time.setTag("trailing");box.addView(time);
    box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);box.setFocusable(true);
    for(int i=0;i<box.getChildCount();i++)box.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    bindings.add(()->{state.symbol=StatusView.symbol(observation.kind);state.noObservation=observation.readFailed;state.invalidate();reason.setVisibility(!observation.readFailed&&observation.kind==JobState.Kind.PAUSED?View.VISIBLE:View.GONE);badge.setVisibility(observation.known&&observation.failed>0?View.VISIBLE:View.GONE);GradientDrawable capsule=GalleryUi.shape(this,observation.old()?Color.TRANSPARENT:0xff1f1f22,14);capsule.setStroke(dp(observation.old()?1:1.5f),observation.old()?0xff3a3a3f:0xffff8a7a);badge.setBackground(capsule);setValue(failed,observation.count(observation.failed),observation.old());setValue(time,observation.time(),observation.old()&&observation.at>0);box.setContentDescription(observation.jobDescription());});margin(box,16,0,16,8);
  }
  private void condition(){LinearLayout box=row();box.setPadding(dp(14),dp(8),dp(14),dp(8));box.setMinimumHeight(dp(48));box.setBackground(dashed());TextView label=text("前面での抑制",12,GalleryUi.MUTED);label.setPadding(0,0,dp(10),0);box.addView(label);Mark eye=new Mark(StatusView.Symbol.VIEWING);box.addView(eye,new LinearLayout.LayoutParams(dp(24),dp(24)));box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);for(int i=0;i<box.getChildCount();i++)box.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);bindings.add(()->{eye.symbol=observation.readFailed?StatusView.Symbol.UNKNOWN:observation.browsing?StatusView.Symbol.VIEWING:StatusView.Symbol.UNKNOWN;eye.invalidate();box.setContentDescription("前面での抑制。"+(observation.readFailed?"現在の条件を取得できませんでした。":observation.browsing?"アプリが前面にあるため、新しい準備は抑えています。停止が完了したかは分かりません。":"前面による抑制条件はありません。"));});margin(box,16,0,16,8);}
  private void detailField(String name,int index,int field){LinearLayout row=row();row.setPadding(dp(20),dp(6),dp(20),dp(6));row.setMinimumHeight(dp(56));row.addView(text(name,14,GalleryUi.MUTED),new LinearLayout.LayoutParams(0,-2,1));TextView value=text("—",15,GalleryUi.TEXT);row.addView(value);bindings.add(()->{setValue(value,value(index,field)+(index==1&&observation.known?"枚":""),index==1&&observation.known&&observation.old());row.setContentDescription(name+"、"+(value(index,field).equals("—")?"未計測":value(index,field)+(observation.old()?"、前回の観測値":"")));});content.addView(row);View line=new View(this);line.setBackgroundColor(0xff1f1f22);content.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));}
  private void detailTime(){LinearLayout row=row();row.setPadding(dp(20),dp(6),dp(20),dp(6));row.setMinimumHeight(dp(56));row.addView(text("観測時刻",14,GalleryUi.MUTED),new LinearLayout.LayoutParams(0,-2,1));TextView time=text("—",15,GalleryUi.TEXT);row.addView(time);bindings.add(()->{setValue(time,detail==1?observation.time():"—",detail==1&&observation.at>0&&observation.old());time.setContentDescription(detail==1&&observation.at>0?(observation.old()?"前回の観測時刻、":"観測時刻、")+observation.time():"時刻の記録はありません");});content.addView(row);}
  private void fixed(String label,String value){fixedInto(content,label,value);}
  private void fixedInto(LinearLayout parent,String label,String value){LinearLayout box=column();box.setPadding(dp(20),dp(14),dp(20),0);if(!label.isEmpty())box.addView(text(label,12,GalleryUi.MUTED));TextView copy=text(value,13,0xffc9c9c4);copy.setLineSpacing(0,1.25f);box.addView(copy);parent.addView(box);}
  private void label(String value,int top){TextView t=text(value,13,GalleryUi.MUTED);t.setPadding(dp(20),dp(top),dp(20),dp(6));content.addView(t);}
  private void setValue(TextView text,String value,boolean old){text.setText(value);text.setTextColor(old?GalleryUi.MUTED:GalleryUi.TEXT);History icon=old?new History():null;if(icon!=null)icon.setBounds(0,0,dp(16*getResources().getConfiguration().fontScale),dp(16*getResources().getConfiguration().fontScale));text.setCompoundDrawables(icon,null,null,null);text.setCompoundDrawablePadding(dp(3));}
  private GradientDrawable dashed(){GradientDrawable d=GalleryUi.shape(this,Color.TRANSPARENT,12);d.setStroke(dp(1),0xff3a3a3f,dp(3),dp(3));return d;}
  private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);return t;}
  private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
  private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
  private int dp(float n){return GalleryUi.dp(this,n);}
  private void margin(View v,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(l),dp(t),dp(r),dp(b));content.addView(v,p);}
  private final class Mark extends View {
    StatusView.Symbol symbol;boolean noObservation;final Paint p=new Paint(3);
    Mark(StatusView.Symbol s){super(DashboardActivity.this);symbol=s;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas c){float size=Math.min(getWidth(),getHeight())*20f/24f,offset=(getWidth()-size)/2;StatusView.glyph(c,p,noObservation?StatusView.Symbol.VIEWING:symbol,offset,offset,size,noObservation?GalleryUi.MUTED:StatusView.error(symbol)?0xffff8a7a:GalleryUi.TEXT,symbol==StatusView.Symbol.RUNNING&&android.animation.ValueAnimator.areAnimatorsEnabled()?(SystemClock.uptimeMillis()%1000)*.36f:0);if(!noObservation&&symbol==StatusView.Symbol.RUNNING&&android.animation.ValueAnimator.areAnimatorsEnabled()&&isShown())postInvalidateDelayed(50);if(noObservation){p.setColor(GalleryUi.MUTED);p.setStrokeWidth(dp(1.8f));c.drawLine(offset+size*.15f,offset+size*.15f,offset+size*.85f,offset+size*.85f,p);}}
  }
  private final class History extends Drawable {
    final Paint p=new Paint(3);
    @Override public void draw(Canvas c){int s=c.save();c.translate(getBounds().left,getBounds().top);c.scale(getBounds().width()/24f,getBounds().height()/24f);p.setColor(GalleryUi.MUTED);p.setStrokeWidth(1.8f);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);c.drawArc(4,4,21,21,-135,315,false,p);c.drawLine(3,4,3,10,p);c.drawLine(3,10,9,10,p);c.drawLine(12.5f,8,12.5f,12.5f,p);c.drawLine(12.5f,12.5f,16,14.5f,p);c.restoreToCount(s);}
    @Override public void setAlpha(int a){p.setAlpha(a);}@Override public void setColorFilter(ColorFilter f){p.setColorFilter(f);}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
  }
  private final class Progress extends View {
    final Paint p=new Paint(3);Progress(){super(DashboardActivity.this);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas c){float end=getWidth()-(observation.old()?dp(22*getResources().getConfiguration().fontScale):0),y=getHeight()/2f;p.setColor(0xff2e2e32);c.drawRoundRect(0,y-dp(2),end,y+dp(2),dp(2),dp(2),p);p.setColor(observation.old()?0xff6b6b70:GalleryUi.TEXT);if(observation.total>0)c.drawRoundRect(0,y-dp(2),end*observation.ready/observation.total,y+dp(2),dp(2),dp(2),p);if(observation.old()){History h=new History();int size=dp(16*getResources().getConfiguration().fontScale);h.setBounds(getWidth()-size,(getHeight()-size)/2,getWidth(),(getHeight()+size)/2);h.draw(c);}}
  }
  /** Wraps complete number fields at narrow widths without reducing their font size. */
  private final class Flow extends ViewGroup {
    Flow(){super(DashboardActivity.this);}
    @Override protected void onMeasure(int w,int h){int width=MeasureSpec.getSize(w),available=width-getPaddingLeft()-getPaddingRight(),x=0,y=0,line=0;for(int i=0;i<getChildCount();i++){View child=getChildAt(i);if(child.getVisibility()==GONE)continue;LayoutParams p=child.getLayoutParams();child.measure(MeasureSpec.makeMeasureSpec(p.width>=0?Math.min(p.width,available):available,p.width>=0?MeasureSpec.EXACTLY:MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(p.height>=0?p.height:0,p.height>=0?MeasureSpec.EXACTLY:MeasureSpec.UNSPECIFIED));int cw=child.getMeasuredWidth(),ch=child.getMeasuredHeight();if(x>0&&x+cw>available){y+=line+dp(4);x=0;line=0;}x+=cw+dp(12);line=Math.max(line,ch);}setMeasuredDimension(width,Math.max(getSuggestedMinimumHeight(),y+line+getPaddingTop()+getPaddingBottom()));}
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){int available=getWidth()-getPaddingLeft()-getPaddingRight(),x=0,y=0,line=0;for(int i=0;i<getChildCount();i++){View child=getChildAt(i);if(child.getVisibility()==GONE)continue;int cw=child.getMeasuredWidth(),ch=child.getMeasuredHeight();if(x>0&&x+cw>available){y+=line+dp(4);x=0;line=0;}if("trailing".equals(child.getTag()))x=Math.max(x,available-cw);child.layout(getPaddingLeft()+x,getPaddingTop()+y,getPaddingLeft()+x+cw,getPaddingTop()+y+ch);x+=cw+dp(12);line=Math.max(line,ch);}}
    @Override protected LayoutParams generateDefaultLayoutParams(){return new LayoutParams(-2,-2);}
  }
}
