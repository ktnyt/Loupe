package jp.nano.rawgallery;
import android.content.Context;
import android.graphics.Typeface;
import android.widget.*;

/** Tag detail heading; counts already resolve human and automatic judgments. */
final class TagDetailUi {
  static LinearLayout heading(Context c,String scope,String name,int attached,int automatic,int excluded,int unknown){
    LinearLayout box=new LinearLayout(c);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(GalleryUi.dp(c,20),GalleryUi.dp(c,40),GalleryUi.dp(c,20),GalleryUi.dp(c,20));
    box.addView(text(c,scope,13,18,0xffa9a9a4));TextView title=text(c,name,28,36,GalleryUi.TEXT);title.setTypeface(Typeface.create(Typeface.DEFAULT,600,false));title.setAccessibilityHeading(true);add(c,box,title);
    add(c,box,text(c,"タグ付き "+attached+"枚"+(automatic>0?"（自動 "+automatic+"枚）":"")+" · 該当しない "+excluded+"枚 · 未確認 "+unknown+"枚",14,20,0xffa9a9a4));return box;
  }
  private static void add(Context c,LinearLayout box,TextView text){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=GalleryUi.dp(c,4);box.addView(text,p);}
  private static TextView text(Context c,String value,int size,int line,int color){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setLineHeight(Math.round(t.getTextSize()*line/size));return t;}
  static Button state(Context c,String title,boolean selected,Runnable action){
    Button b=GalleryUi.button(c,"✓  "+title,action);android.text.SpannableString value=new android.text.SpannableString("✓  "+title);value.setSpan(new android.text.style.ForegroundColorSpan(selected?0xff111111:android.graphics.Color.TRANSPARENT),0,1,0);b.setText(value);b.setContentDescription(title);b.setSelected(selected);b.setStateDescription(selected?"選択中":"未選択");b.setTextColor(selected?0xff111111:GalleryUi.TEXT);b.setBackground(GalleryUi.shape(c,selected?GalleryUi.TEXT:0xff1c1c1f,24));b.setIncludeFontPadding(false);b.setPadding(GalleryUi.dp(c,8),GalleryUi.dp(c,4),GalleryUi.dp(c,8),GalleryUi.dp(c,4));return b;
  }
  static Button action(Context c,String title,boolean primary,Runnable action){
    Button b=GalleryUi.button(c,title,action);b.setTextSize(primary?15:14);b.setIncludeFontPadding(false);b.setMinHeight(GalleryUi.dp(c,primary?52:48));b.setMinimumHeight(GalleryUi.dp(c,primary?52:48));b.setPadding(GalleryUi.dp(c,16),GalleryUi.dp(c,6),GalleryUi.dp(c,16),GalleryUi.dp(c,6));
    android.graphics.drawable.GradientDrawable bg=GalleryUi.shape(c,primary?GalleryUi.TEXT:android.graphics.Color.TRANSPARENT,primary?26:24);if(!primary)bg.setStroke(GalleryUi.dp(c,1),0xff3a3a3f);b.setBackground(bg);int color=primary?0xff111111:GalleryUi.TEXT;b.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled},new int[]{}},new int[]{color,0xff626b72}));android.text.SpannableString label=new android.text.SpannableString(" "+title);label.setSpan(new android.text.style.ReplacementSpan(){
      public int getSize(android.graphics.Paint paint,CharSequence value,int start,int end,android.graphics.Paint.FontMetricsInt fm){return GalleryUi.dp(c,26);}
      public void draw(android.graphics.Canvas canvas,CharSequence value,int start,int end,float x,int top,int y,int bottom,android.graphics.Paint paint){ActionIcon icon=new ActionIcon(title,c.getResources().getDisplayMetrics().density);int n=GalleryUi.dp(c,18);int center=Math.round(y+(paint.ascent()+paint.descent())/2);icon.setBounds((int)x,center-n/2,(int)x+n,center+n/2);icon.setTintList(android.content.res.ColorStateList.valueOf(paint.getColor()));icon.draw(canvas);}
    },0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);b.setText(label);return b;
  }
  static Button dockButton(Context c,String title,Runnable action){Button b=GalleryUi.button(c,title,action);b.setTextSize(12);b.setIncludeFontPadding(false);b.setMinHeight(GalleryUi.dp(c,64));b.setMinimumHeight(GalleryUi.dp(c,64));icon(c,b,title,true,24);return b;}
  private static void icon(Context c,Button b,String title,boolean top,int size){ActionIcon icon=new ActionIcon(title,c.getResources().getDisplayMetrics().density);int n=GalleryUi.dp(c,size);icon.setBounds(0,0,n,n);b.setCompoundDrawables(top?null:icon,top?icon:null,null,null);b.setCompoundDrawablePadding(GalleryUi.dp(c,top?4:8));}
  static LinearLayout dock(Context c,Button... buttons){LinearLayout row=new LinearLayout(c);for(Button b:buttons)row.addView(b,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.leftMargin=-GalleryUi.dp(c,8);p.rightMargin=-GalleryUi.dp(c,8);row.setLayoutParams(p);return row;}
  static void footer(LinearLayout root,int first){GalleryUi.bottomControls(root,first);ScrollView scroll=(ScrollView)root.getChildAt(root.getChildCount()-1);LinearLayout panel=(LinearLayout)scroll.getChildAt(0);Context c=root.getContext();panel.setBackgroundColor(GalleryUi.BG);panel.setPadding(GalleryUi.dp(c,16),GalleryUi.dp(c,10),GalleryUi.dp(c,16),GalleryUi.dp(c,20));}
  static void gaps(LinearLayout box){for(int i=1;i<box.getChildCount();i++){LinearLayout.LayoutParams p=box.getChildAt(i).getLayoutParams() instanceof LinearLayout.LayoutParams?(LinearLayout.LayoutParams)box.getChildAt(i).getLayoutParams():new LinearLayout.LayoutParams(-1,-2);p.topMargin=GalleryUi.dp(box.getContext(),8);box.getChildAt(i).setLayoutParams(p);}}
  static void configureTile(ThumbnailCell cell){cell.fixedHeight=GalleryUi.dp(cell.getContext(),96);cell.badges.tagAtRight=true;}
}
