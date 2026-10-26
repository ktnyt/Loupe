package jp.nano.rawgallery;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.Gravity;
import android.widget.*;

/** Presentation of tag navigation; never reads photos or changes tag data. */
final class TagListUi {
  private static int dp(Context c,float n){return GalleryUi.dp(c,n);}
  static Button row(Context c,String name,Runnable open){
    Button b=GalleryUi.button(c,name,open);
    b.setTextSize(16);b.setIncludeFontPadding(false);b.setLineHeight(Math.round(b.getTextSize()*1.45f));
    b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setSingleLine(false);b.setEllipsize(null);
    b.setPadding(dp(c,20),dp(c,12),dp(c,16),dp(c,12));
    b.setMinHeight(dp(c,72));b.setMinimumHeight(dp(c,72));
    b.setCompoundDrawables(new Mark(c,0),null,new Mark(c,1),null);
    b.setCompoundDrawablePadding(dp(c,16));b.setContentDescription(name+"。タグを開く");
    b.setBackground(background(c,false,0));return b;
  }
  static LinearLayout footer(Context c,Runnable create){
    LinearLayout box=new LinearLayout(c);box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(c,16),dp(c,12),dp(c,16),dp(c,16));
    Button b=GalleryUi.button(c,"新しいタグ",create);b.setTextSize(16);
    b.setIncludeFontPadding(false);b.setLineHeight(Math.round(b.getTextSize()*1.35f));
    b.setMinHeight(dp(c,52));b.setMinimumHeight(dp(c,52));
    b.setPadding(dp(c,20),dp(c,12),dp(c,20),dp(c,12));
    b.setTextColor(0xff111111);b.setCompoundDrawables(new Mark(c,2),null,null,null);
    b.setCompoundDrawablePadding(dp(c,8));b.setBackground(background(c,true,26));
    box.addView(b,new LinearLayout.LayoutParams(-1,-2));return box;
  }
  static TextView title(Context c){
    TextView t=new TextView(c);t.setText("タグ");t.setTextSize(22);t.setTextColor(GalleryUi.TEXT);
    t.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));t.setIncludeFontPadding(false);
    t.setLineHeight(Math.round(t.getTextSize()*1.35f));t.setAccessibilityHeading(true);
    t.setPadding(dp(c,20),dp(c,24),dp(c,20),dp(c,4));return t;
  }
  static TextView scope(Context c){
    TextView t=new TextView(c);t.setText("現在のアルバム・絞り込み内の端末写真が対象です");
    t.setTextSize(14);t.setTextColor(0xffa0a0a4);t.setIncludeFontPadding(false);
    t.setLineHeight(Math.round(t.getTextSize()*1.5f));t.setPadding(dp(c,20),0,dp(c,20),dp(c,12));return t;
  }
  private static Drawable background(Context c,boolean primary,int radius){
    StateListDrawable states=new StateListDrawable();
    GradientDrawable focus=GalleryUi.shape(c,primary?GalleryUi.TEXT:GalleryUi.BG,radius);
    focus.setStroke(dp(c,2),primary?0xff111111:GalleryUi.TEXT);
    states.addState(new int[]{android.R.attr.state_focused},focus);
    states.addState(new int[]{},GalleryUi.shape(c,primary?GalleryUi.TEXT:Color.TRANSPARENT,radius));
    return new RippleDrawable(ColorStateList.valueOf(primary?0x22000000:0x33ffffff),states,GalleryUi.shape(c,Color.WHITE,radius));
  }
  private static final class Mark extends Drawable {
    final int kind;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    Mark(Context c,int kind){this.kind=kind;int size=dp(c,kind==0?40:20);setBounds(0,0,size,size);}
    public void draw(Canvas canvas){int save=canvas.save();Rect r=getBounds();canvas.translate(r.left,r.top);canvas.scale(r.width()/(kind==0?40f:20f),r.height()/(kind==0?40f:20f));
      if(kind==0){p.setStyle(Paint.Style.FILL);p.setColor(GalleryUi.PANEL);canvas.drawRoundRect(0,0,40,40,12,12,p);StatusView.glyph(canvas,p,StatusView.Symbol.TAG,10,10,20,0xffa0a0a4,0);}
      else{p.setStyle(Paint.Style.STROKE);p.setColor(kind==2?0xff111111:0xffa0a0a4);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);Path path=new Path();if(kind==1){path.moveTo(7,4);path.lineTo(13,10);path.lineTo(7,16);}else{path.moveTo(10,4);path.lineTo(10,16);path.moveTo(4,10);path.lineTo(16,10);}canvas.drawPath(path,p);}canvas.restoreToCount(save);
    }
    public void setAlpha(int alpha){p.setAlpha(alpha);invalidateSelf();}
    public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);invalidateSelf();}
    public int getOpacity(){return PixelFormat.TRANSLUCENT;}
  }
}
