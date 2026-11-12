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
  static Button chip(Context c,String name,boolean selected,Runnable action) {
    Button button=GalleryUi.button(c,name,action);
    button.setCompoundDrawables(null,null,null,null);
    button.setPadding(dp(c,12),dp(c,4),dp(c,12),dp(c,4));
    MaterialUi.choice(button,selected);
    button.setStateDescription(selected?"選択中":"未選択");
    return button;
  }
  static final class Filters extends ViewGroup {
    private boolean paired;
    Filters(Context c){super(c);setPadding(dp(c,8),dp(c,4),dp(c,8),0);setBackgroundColor(GalleryUi.BG);}
    protected void onMeasure(int widthSpec,int heightSpec){
      int width=MeasureSpec.getSize(widthSpec),available=width-getPaddingLeft()-getPaddingRight(),total=0;
      for(int i=0;i<getChildCount();i++){
        View child=getChildAt(i);int padding=dp(getContext(),16);
        child.setPadding(padding,child.getPaddingTop(),padding,child.getPaddingBottom());
        child.measure(MeasureSpec.makeMeasureSpec(available,MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
        int excess=child.getMeasuredWidth()-available/2;
        if(excess>0){padding=Math.max(dp(getContext(),6),padding-(excess+1)/2);child.setPadding(padding,child.getPaddingTop(),padding,child.getPaddingBottom());child.measure(MeasureSpec.makeMeasureSpec(available,MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));}
        total+=child.getMeasuredWidth();
      }
      // Keep the two rank-state filters together when the four choices need wrapping.
      paired=getChildCount()==4&&total>available
          &&getChildAt(0).getMeasuredWidth()+getChildAt(1).getMeasuredWidth()<=available
          &&getChildAt(2).getMeasuredWidth()+getChildAt(3).getMeasuredWidth()<=available;
      int x=0,y=0,row=0,count=0;
      for(int i=0;i<getChildCount();i++){
        View child=getChildAt(i);
        if(x>0&&(x+child.getMeasuredWidth()>available||paired&&count==2)){x=0;y+=row;row=0;count=0;}
        x+=child.getMeasuredWidth();row=Math.max(row,child.getMeasuredHeight());count++;
      }
      setMeasuredDimension(width,getPaddingTop()+y+row+getPaddingBottom());
    }
    protected void onLayout(boolean changed,int left,int top,int right,int bottom){
      int x=getPaddingLeft(),y=getPaddingTop(),row=0,count=0;
      for(int i=0;i<getChildCount();i++){
        View child=getChildAt(i);
        if(x>getPaddingLeft()&&(x+child.getMeasuredWidth()>getWidth()-getPaddingRight()||paired&&count==2)){x=getPaddingLeft();y+=row;row=0;count=0;}
        child.layout(x,y,x+child.getMeasuredWidth(),y+child.getMeasuredHeight());
        x+=child.getMeasuredWidth();row=Math.max(row,child.getMeasuredHeight());count++;
      }
    }
  }
}
