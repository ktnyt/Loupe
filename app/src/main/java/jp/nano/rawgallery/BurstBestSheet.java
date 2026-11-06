package jp.nano.rawgallery;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.function.*;

/** Confirmation and retry for a captured burst member; never saves on dismissal. */
final class BurstBestSheet extends Dialog {
  interface Save { void run(boolean together, Consumer<Boolean> done); }
  private final Save save;
  private final Button add, sole, cancel;
  private final TextView error;
  private final ProgressBar progress;
  private final String soleName, soleSubtitle;
  private final android.app.Activity owner;
  private boolean busy;
  private Button failed;

  BurstBestSheet(Context c, boolean canAdd, boolean tiedWinner, String members, Save save) {
    super(c); this.save=save;owner=(android.app.Activity)c;setOwnerActivity(owner); requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout content=RankingUi.column(c);content.setPadding(dp(16),dp(12),dp(16),dp(20));
    View handle=new View(c);handle.setBackground(GalleryUi.shape(c,0xff4a4a50,2));
    LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(32),dp(4));hp.gravity=Gravity.CENTER;hp.bottomMargin=dp(12);content.addView(handle,hp);
    TextView title=text(tiedWinner?"この写真だけを1位にしますか？":"この写真を1位にしますか？",20);
    title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));title.setAccessibilityHeading(true);title.setPadding(dp(8),0,dp(8),dp(4));content.addView(title);
    TextView summary=text(canAdd||tiedWinner?members:"この連写の結果を1位と残り同率2位で保存します。",14);
    summary.setPadding(dp(8),dp(4),dp(8),0);content.addView(summary);
    error=text("保存できませんでした。順位は前のままです",14);error.setTextColor(0xffff8a7a);error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);error.setVisibility(View.GONE);RankingUi.add(content,error,8);
    progress=new ProgressBar(c);progress.setVisibility(View.GONE);LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(dp(20),dp(20));pp.gravity=Gravity.CENTER;content.addView(progress,pp);
    add=action("同率1位に追加","いまの1位はそのまま",false,()->start(true));
    if(canAdd)RankingUi.add(content,add,16);
    soleName=canAdd||tiedWinner?"この写真だけを1位にする":"1位にする";
    soleSubtitle=canAdd||tiedWinner?"ほかは同率2位":"";
    sole=action(soleName,soleSubtitle,true,()->start(false));RankingUi.add(content,sole,canAdd?8:16);
    cancel=action("キャンセル","",false,this::dismiss);cancel.setMinHeight(dp(48));cancel.setMinimumHeight(dp(48));cancel.setBackground(GalleryUi.shape(c,GalleryUi.TEXT,24));cancel.setTextColor(0xff111111);RankingUi.add(content,cancel,8);
    ScrollView scroll=new ScrollView(c);scroll.addView(content);setContentView(scroll);
    setCanceledOnTouchOutside(true);
    Window w=getWindow();GradientDrawable bg=GalleryUi.shape(c,0xff1c1c1f,28);bg.setCornerRadii(new float[]{dp(28),dp(28),dp(28),dp(28),0,0,0,0});w.setBackgroundDrawable(bg);w.setGravity(Gravity.BOTTOM);w.setWindowAnimations(0);
    DiagnosticWindow.install(w);
  }
  private int dp(int n){return GalleryUi.dp(getContext(),n);}
  private TextView text(String s,int size){TextView t=new TextView(getContext());t.setText(s);t.setTextSize(size);t.setTextColor(GalleryUi.TEXT);t.setIncludeFontPadding(false);return t;}
  private Button action(String name,String sub,boolean primary,Runnable action){
    Button b=GalleryUi.button(getContext(),name,action);RankingUi.action(b,false,56,15,null);
    b.setAllCaps(false);b.setSingleLine(false);b.setMaxLines(6);b.setEllipsize(null);
    setLabel(b,name,sub);
    GradientDrawable bg=GalleryUi.shape(getContext(),Color.TRANSPARENT,26);bg.setStroke(dp(1),primary?0xffff8a7a:0xff6b6b70);b.setBackground(bg);b.setTextColor(primary?0xffff8a7a:GalleryUi.TEXT);return b;
  }
  private void setLabel(Button b,String name,String sub){
    String value=name+(sub.isEmpty()?"":"\n"+sub);android.text.SpannableString styled=new android.text.SpannableString(value);
    styled.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),0,name.length(),0);
    if(!sub.isEmpty())styled.setSpan(new android.text.style.AbsoluteSizeSpan(12,true),name.length()+1,value.length(),0);
    if(name.equals("同率1位に追加")){
      android.graphics.drawable.Drawable star=RankingUi.star(getContext());
      android.graphics.drawable.Drawable icon=new android.graphics.drawable.Drawable(){final android.graphics.Paint paint=new android.graphics.Paint(3);
        public void draw(android.graphics.Canvas canvas){android.graphics.Rect r=getBounds();star.setBounds(r.left,r.top,r.left+r.width()*3/4,r.bottom);star.draw(canvas);paint.setColor(GalleryUi.TEXT);paint.setStrokeWidth(dp(1));float x=r.right-dp(3),y=r.top+dp(4);canvas.drawLine(x-dp(3),y,x+dp(3),y,paint);canvas.drawLine(x,y-dp(3),x,y+dp(3),paint);}
        public void setAlpha(int a){}public void setColorFilter(android.graphics.ColorFilter f){}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
      };
      RankingUi.inline(b,icon,value,20);
      android.text.SpannableString withIcon=new android.text.SpannableString(b.getText());
      withIcon.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),3,3+name.length(),0);
      if(!sub.isEmpty())withIcon.setSpan(new android.text.style.AbsoluteSizeSpan(12,true),4+name.length(),withIcon.length(),0);
      styled=withIcon;
    }
    b.setText(styled);b.setContentDescription(name+(sub.isEmpty()?"":"。"+sub));
  }
  @Override public void show(){super.show();getWindow().setLayout(owner.getWindow().getDecorView().getWidth(),ViewGroup.LayoutParams.WRAP_CONTENT);}
  private void start(boolean together){
    if(busy)return;busy=true;Button chosen=together?add:sole;
    setCancelable(false);setCanceledOnTouchOutside(false);add.setEnabled(false);sole.setEnabled(false);cancel.setEnabled(false);error.setVisibility(View.GONE);progress.setVisibility(View.VISIBLE);chosen.setText("保存しています…");chosen.setContentDescription("保存しています");
    save.run(together,success->{
      if(!isShowing())return;
      busy=false;progress.setVisibility(View.GONE);
      if(success){dismiss();return;}
      setCancelable(true);setCanceledOnTouchOutside(true);add.setEnabled(true);sole.setEnabled(true);cancel.setEnabled(true);
      if(failed!=null&&failed!=chosen){setLabel(failed,failed==add?"同率1位に追加":soleName,failed==add?"いまの1位はそのまま":soleSubtitle);}
      failed=chosen;error.setVisibility(View.VISIBLE);chosen.setText("再試行");chosen.setContentDescription("再試行、"+(together?"同率1位に追加":soleName));
    });
  }
}
