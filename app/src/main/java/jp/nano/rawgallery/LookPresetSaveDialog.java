package jp.nano.rawgallery;

import android.app.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.concurrent.*;

/** Saves an immutable Look snapshot without changing the editor state. */
final class LookPresetSaveDialog {
  static Dialog show(Activity a, RawEditLook look) {
    Dialog d=new Dialog(a); d.requestWindowFeature(Window.FEATURE_NO_TITLE);
    LookPresetStore store=new LookPresetStore(new File(a.getFilesDir(),"look-library"));
    ExecutorService io=Executors.newSingleThreadExecutor();
    android.graphics.Rect visible=new android.graphics.Rect();a.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);int maxHeight=Math.round(visible.height()*.85f);
    ScrollView scroll=new ScrollView(a){@Override protected void onMeasure(int w,int h){super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(maxHeight,View.MeasureSpec.AT_MOST));}};scroll.setFillViewport(false);scroll.setTag("look-preset-dialog");
    GradientDrawable bg=shape(a,0xff1c1c1f,0,0);float radius=dp(a,28);bg.setCornerRadii(new float[]{radius,radius,radius,radius,0,0,0,0});scroll.setBackground(bg);
    LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(0,dp(a,12),0,dp(a,20));scroll.addView(body);
    View grip=new View(a);grip.setBackground(shape(a,0xff4a4a50,2,0));LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(dp(a,32),dp(a,4));gp.gravity=Gravity.CENTER_HORIZONTAL;gp.bottomMargin=dp(a,12);body.addView(grip,gp);
    TextView title=text(a,"プリセットとして保存",20,0xffededea);title.setTypeface(Typeface.create(Typeface.DEFAULT,600,false));title.setLineHeight(sp(a,28));title.setPadding(0,0,0,dp(a,4));addInset(body,title,24,0,-2);
    String explanation="今のルックの設定だけを新しいプリセットとして保存します（トーンのカーブ・LUT とその強度・周辺光量・粒子）。露出・WB・現像の補正は含みません。";
    SpannableString styled=new SpannableString(explanation);int boldStart=explanation.indexOf("ルックの設定だけ"),wordStart=explanation.indexOf("粒子");styled.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),boldStart,boldStart+"ルックの設定だけ".length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);styled.setSpan(new android.text.style.WrapTogetherSpan(){},wordStart,wordStart+2,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    TextView desc=text(a,"",14,0xffededea);desc.setText(styled);desc.setLineHeight(sp(a,21));addInset(body,desc,24,4,-2);
    TextView label=text(a,"名前（1〜40文字）",13,0xffa9a9a4);label.setLineHeight(sp(a,18));addInset(body,label,24,12,-2);
    EditText name=new EditText(a);name.setTag("preset-name");name.setSingleLine(true);name.setTextSize(15);name.setTextColor(0xffededea);name.setPadding(dp(a,12),0,dp(a,12),0);name.setBackground(shape(a,0xff111111,12,0xff3a3a3f));addInset(body,name,24,6,48);
    TextView count=text(a,"0／40",12,0xffa9a9a4);count.setGravity(Gravity.RIGHT);count.setLineHeight(sp(a,16));addInset(body,count,24,6,-2);
    TextView status=text(a,"読み込み中…",12,0xffa9a9a4);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);status.setLineHeight(sp(a,16));addInset(body,status,24,6,-2);
    Button save=button(a,"保存",true);save.setTag("preset-confirm");addInset(body,save,16,16,48);
    Button cancel=button(a,"キャンセル（編集はそのまま）",false);cancel.setTag("preset-cancel");addInset(body,cancel,16,8,48);
    final int[] size={-1};final boolean[] writing={false};
    Runnable load=()->{size[0]=-1;save.setEnabled(false);status.setText("読み込み中…");io.execute(()->{try{int n=store.list().size();a.runOnUiThread(()->{if(!d.isShowing())return;size[0]=n;status.setText("保存したプリセット "+n+"／32。同じ名前でも新しい項目として保存します。");save.setEnabled(true);});}catch(Exception e){a.runOnUiThread(()->{if(!d.isShowing())return;status.setText("読み込めませんでした。保存を押すと再試行します。");save.setEnabled(true);});}});};
    name.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int af){}public void onTextChanged(CharSequence s,int st,int before,int c){String v=s.toString().trim();count.setText(v.codePointCount(0,v.length())+"／40");}public void afterTextChanged(Editable e){}});
    save.setOnClickListener(v->{if(writing[0])return;if(size[0]<0){load.run();return;}String n=name.getText().toString();int length=n.trim().codePointCount(0,n.trim().length());if(length==0){status.setText("名前を入れてください");return;}if(length>40){status.setText("40文字までです");return;}if(size[0]>=32){status.setText("保存できるのは32個までです。写真の画面で不要なプリセットを削除してください");return;}writing[0]=true;save.setEnabled(false);cancel.setEnabled(false);name.setEnabled(false);d.setCancelable(false);status.setText("保存中…");io.execute(()->{try{store.save(n,look);a.runOnUiThread(()->{if(d.isShowing()){d.dismiss();Toast.makeText(a,"プリセットを保存しました",Toast.LENGTH_SHORT).show();}});}catch(Exception e){a.runOnUiThread(()->{if(!d.isShowing())return;writing[0]=false;save.setEnabled(true);cancel.setEnabled(true);name.setEnabled(true);d.setCancelable(true);status.setText("保存できませんでした。名前と編集はそのままです。もう一度お試しください。");});}});});
    cancel.setOnClickListener(v->d.dismiss());d.setOnDismissListener(x->io.shutdown());d.setContentView(scroll);d.show();Window w=d.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setGravity(Gravity.BOTTOM);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);w.setLayout(-1,WindowManager.LayoutParams.WRAP_CONTENT);}load.run();return d;
  }
  private static int dp(Activity a,float n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
  private static GradientDrawable shape(Activity a,int color,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(a,radius));if(stroke!=0)d.setStroke(dp(a,1),stroke);return d;}
  private static TextView text(Activity a,String s,int size,int color){TextView t=new TextView(a);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);return t;}
  private static Button button(Activity a,String s,boolean primary){Button b=new Button(a);b.setText(s);b.setAllCaps(false);b.setTextSize(14);b.setIncludeFontPadding(false);b.setTypeface(Typeface.create(Typeface.DEFAULT,primary?600:500,false));b.setTextColor(primary?0xff111111:0xffededea);b.setBackground(shape(a,primary?0xffededea:Color.TRANSPARENT,24,primary?0:0xff3a3a3f));return b;}
  private static int sp(Activity a,float n){return Math.round(n*a.getResources().getDisplayMetrics().scaledDensity);}
  private static void addInset(LinearLayout p,View v,int inset,int top,int h){Activity a=(Activity)p.getContext();LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,h<0?h:dp(a,h));lp.setMargins(dp(a,inset),dp(a,top),dp(a,inset),0);p.addView(v,lp);}
}
