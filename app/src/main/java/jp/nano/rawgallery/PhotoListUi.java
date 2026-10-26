package jp.nano.rawgallery;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Photo-list heading and wrapping rank filters; data and navigation remain in MainActivity. */
final class PhotoListUi {
  static int dp(Context c,int n){return GalleryUi.dp(c,n);}
  static TextView text(Context c,String value,int size,int line,int color){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setLineHeight(Math.round(t.getTextSize()*line/size));return t;}
  static final class Heading extends ViewGroup {
    final TextView title; final LinearLayout count; boolean wrapped;
    Heading(Context c,String name,TextView subtitle,StatusView status){super(c);setPadding(dp(c,20),dp(c,24),dp(c,20),dp(c,12));title=text(c,name,24,32,GalleryUi.TEXT);title.setTypeface(Typeface.create(Typeface.DEFAULT,600,false));title.setAccessibilityHeading(true);addView(title,new LayoutParams(-2,-2));subtitle.setTextSize(14);subtitle.setIncludeFontPadding(false);subtitle.setLineHeight(Math.round(subtitle.getTextSize()*20/14));subtitle.setSingleLine(true);count=new LinearLayout(c);count.setGravity(Gravity.CENTER_VERTICAL);count.addView(subtitle);status.collapseWhenIdle();count.addView(status);addView(count,new LayoutParams(-2,-2));}
    protected void onMeasure(int w,int h){int width=MeasureSpec.getSize(w),available=Math.max(0,width-getPaddingLeft()-getPaddingRight()),free=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED);title.measure(free,free);count.measure(MeasureSpec.makeMeasureSpec(available,MeasureSpec.AT_MOST),free);wrapped=title.getMeasuredWidth()+dp(getContext(),12)+count.getMeasuredWidth()>available;title.measure(MeasureSpec.makeMeasureSpec(wrapped?available:Math.max(0,available-count.getMeasuredWidth()-dp(getContext(),12)),MeasureSpec.AT_MOST),free);int height=wrapped?title.getMeasuredHeight()+dp(getContext(),12)+count.getMeasuredHeight():Math.max(title.getMeasuredHeight(),count.getMeasuredHeight());setMeasuredDimension(width,getPaddingTop()+height+getPaddingBottom());}
    protected void onLayout(boolean changed,int l,int t,int r,int b){int top=getPaddingTop();title.layout(getPaddingLeft(),top,getPaddingLeft()+title.getMeasuredWidth(),top+title.getMeasuredHeight());int y=wrapped?top+title.getMeasuredHeight()+dp(getContext(),12):top+title.getBaseline()-count.getChildAt(0).getBaseline();int right=getWidth()-getPaddingRight();count.layout(right-count.getMeasuredWidth(),y,right,y+count.getMeasuredHeight());}
  }
  static Button chip(Context c,String name,boolean selected,Runnable action){
    Button button=GalleryUi.button(c,name,action);button.setTextSize(13);button.setIncludeFontPadding(false);button.setLineHeight(Math.round(button.getTextSize()*18/13));button.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(dp(c,48));button.setMinimumHeight(dp(c,48));button.setPadding(dp(c,12),0,dp(c,16),0);button.setTextColor(selected?0xff111111:GalleryUi.TEXT);button.setSelected(selected);button.setContentDescription(name);button.setStateDescription(selected?"選択中":"未選択");
    Drawable check=new Drawable(){final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);public void draw(Canvas canvas){if(!selected)return;int save=canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);p.setColor(0xff111111);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.2f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);Path path=new Path();path.moveTo(5,12.5f);path.lineTo(9.5f,17);path.lineTo(19,7.5f);canvas.drawPath(path,p);canvas.restoreToCount(save);}public void setAlpha(int alpha){}public void setColorFilter(ColorFilter f){}public int getOpacity(){return PixelFormat.TRANSLUCENT;}};check.setBounds(0,0,dp(c,16),dp(c,16));button.setCompoundDrawables(check,null,null,null);button.setCompoundDrawablePadding(dp(c,4));
    GradientDrawable shape=GalleryUi.shape(c,selected?GalleryUi.TEXT:0,8);shape.setStroke(dp(c,1),selected?GalleryUi.TEXT:0xff3a3a3f);button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x334c8dff),new InsetDrawable(shape,dp(c,4),dp(c,6),dp(c,4),dp(c,6)),null));button.setPadding(dp(c,16),0,dp(c,16),0);return button;
  }
  static final class Filters extends ViewGroup {
    Filters(Context c){super(c);setPadding(dp(c,12),dp(c,4),dp(c,12),0);setBackgroundColor(GalleryUi.BG);}
    protected void onMeasure(int w,int h){int width=MeasureSpec.getSize(w),available=width-getPaddingLeft()-getPaddingRight(),x=0,y=0,row=0;for(int i=0;i<getChildCount();i++){View child=getChildAt(i);child.measure(MeasureSpec.makeMeasureSpec(available,MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));if(x>0&&x+child.getMeasuredWidth()>available){x=0;y+=row;row=0;}x+=child.getMeasuredWidth();row=Math.max(row,child.getMeasuredHeight());}setMeasuredDimension(width,getPaddingTop()+y+row+getPaddingBottom());}
    protected void onLayout(boolean changed,int l,int t,int r,int b){int x=getPaddingLeft(),y=getPaddingTop(),row=0;for(int i=0;i<getChildCount();i++){View child=getChildAt(i);if(x>getPaddingLeft()&&x+child.getMeasuredWidth()>getWidth()-getPaddingRight()){x=getPaddingLeft();y+=row;row=0;}child.layout(x,y,x+child.getMeasuredWidth(),y+child.getMeasuredHeight());x+=child.getMeasuredWidth();row=Math.max(row,child.getMeasuredHeight());}}
  }
}
