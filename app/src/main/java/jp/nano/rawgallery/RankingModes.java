package jp.nano.rawgallery;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

/** Result examples explain the two ranking contracts; they are not the current photo ranks. */
final class RankingModes {
  static LinearLayout create(Context c, boolean best, Runnable chooseBest, Runnable chooseAll) {
    LinearLayout outer = new LinearLayout(c);
    outer.setPadding(dp(c,16),dp(c,8),dp(c,16),dp(c,8));
    LinearLayout panel = new LinearLayout(c);
    panel.setPadding(dp(c,4),dp(c,4),dp(c,4),dp(c,4));
    panel.setBackground(shape(c,GalleryUi.PANEL,14));
    panel.setContentDescription("ソート方式");
    panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    Button first = new Choice(c,true,best,chooseBest), second = new Choice(c,false,!best,chooseAll);
    LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0,-2,1);
    left.setMarginEnd(dp(c,4));panel.addView(first,left);
    panel.addView(second,new LinearLayout.LayoutParams(0,-2,1));
    outer.addView(panel,new LinearLayout.LayoutParams(-1,-2));
    return outer;
  }
  private static int dp(Context c,float n) { return GalleryUi.dp(c,n); }
  private static GradientDrawable shape(Context c,int color,int radius) {
    GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;
  }
  static final class Choice extends Button {
    Choice(Context c,boolean best,boolean selected,Runnable action) {
      super(c);
      setAllCaps(false);setText(best?"1位を決める":"すべてに順位");
      setTextSize(13);setTextColor(GalleryUi.TEXT);setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
      setIncludeFontPadding(false);setGravity(Gravity.CENTER);setMinWidth(0);setMinimumWidth(dp(c,48));
      setMinHeight(dp(c,64));setMinimumHeight(dp(c,64));setPadding(dp(c,9),dp(c,9),dp(c,9),dp(c,9));
      setLineSpacing(Math.max(0,20*c.getResources().getDisplayMetrics().scaledDensity-getPaint().getFontMetrics(null)),1);
      setSelected(selected);setStateDescription(selected?"選択中":"未選択");
      setContentDescription(best?"1位を決める。1枚だけが1位、ほかの写真はすべて同率2位":"すべてに順位。すべての写真に順位を付ける、同じ順位も選べる");
      GradientDrawable fill=shape(c,selected?0xff26262a:Color.TRANSPARENT,10);
      fill.setStroke(dp(c,selected?2:1),selected?GalleryUi.TEXT:0xff3a3a3f);
      setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ededea),fill,null));
      Example example=new Example(c,best,selected);example.setBounds(0,0,example.getIntrinsicWidth(),example.getIntrinsicHeight());
      setCompoundDrawables(null,null,null,example);setCompoundDrawablePadding(dp(c,4));
      setOnClickListener(v->{GalleryUi.feedback(this);action.run();});
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
      super.onInitializeAccessibilityNodeInfo(info);info.setClassName(RadioButton.class.getName());info.setCheckable(true);info.setChecked(isSelected());
    }
  }
  private static final class Example extends Drawable {
    final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    final float density,scale;final boolean best,selected;
    Example(Context c,boolean best,boolean selected){density=c.getResources().getDisplayMetrics().density;scale=c.getResources().getConfiguration().fontScale;this.best=best;this.selected=selected;}
    @Override public int getIntrinsicWidth(){return Math.round((24+64*scale+(best?5:7))*density);}
    @Override public int getIntrinsicHeight(){return Math.round(Math.max(18,18*scale)*density);}
    @Override public void draw(Canvas canvas){
      canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(density,density);
      float h=getIntrinsicHeight()/density,cy=h/2;
      paint.setStyle(Paint.Style.FILL);paint.setColor(selected?GalleryUi.TEXT:0xff6b6b70);canvas.drawCircle(9,cy,9,paint);
      if(!selected){paint.setColor(0xff19191b);canvas.drawCircle(9,cy,7,paint);}
      else {paint.setColor(0xff111111);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.6f);paint.setStrokeCap(Paint.Cap.ROUND);Path p=new Path();p.moveTo(5,cy);p.lineTo(8,cy+3);p.lineTo(13,cy-3);canvas.drawPath(p,paint);paint.setStyle(Paint.Style.FILL);}
      float x=24,w=16*scale,boxH=18*scale,y=(h-boxH)/2;
      int[] ranks=best?new int[]{1,2,2,2}:new int[]{1,2,2,4};
      for(int i=0;i<4;i++){
        boolean joinedLeft=i>0&&ranks[i]==ranks[i-1],joinedRight=i<3&&ranks[i]==ranks[i+1];
        paint.setColor(i==0?GalleryUi.TEXT:0xff34343a);
        float[] corners={joinedLeft?0:5,joinedLeft?0:5,joinedRight?0:5,joinedRight?0:5,joinedRight?0:5,joinedRight?0:5,joinedLeft?0:5,joinedLeft?0:5};
        Path box=new Path();box.addRoundRect(new RectF(x,y,x+w,y+boxH),corners,Path.Direction.CW);canvas.drawPath(box,paint);
        paint.setColor(i==0?0xff111111:GalleryUi.TEXT);paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));paint.setTextSize(11*scale);paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm=paint.getFontMetrics();canvas.drawText(Integer.toString(ranks[i]),x+w/2,cy-(fm.ascent+fm.descent)/2,paint);
        x+=w+(joinedRight?1:3);
      }
      canvas.restore();
    }
    @Override public void setAlpha(int alpha){}
    @Override public void setColorFilter(ColorFilter filter){}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
  }
}
