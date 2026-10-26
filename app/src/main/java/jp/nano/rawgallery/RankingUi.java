package jp.nano.rawgallery;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Layout of burst browsing and comparison; ranking decisions stay in the activity. */
final class RankingUi {
  static final int BG=0xff0b0b0c;
  static int dp(Context c,float n){return GalleryUi.dp(c,n);}
  static LinearLayout column(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(LinearLayout.VERTICAL);return v;}
  static final class Screen extends ViewGroup {
    final View header, stage;
    final ScrollView footer;
    final boolean burst;
    Screen(Context c,View header,View stage,View controls,boolean burst){
      super(c);this.header=header;this.stage=stage;this.burst=burst;
      setBackgroundColor(BG);footer=new ScrollView(c);footer.setFillViewport(false);
      footer.setVerticalScrollBarEnabled(false);footer.addView(controls);
      addView(header);addView(stage);addView(footer);
    }
    protected void onMeasure(int ws,int hs){
      int w=MeasureSpec.getSize(ws),h=MeasureSpec.getSize(hs);
      header.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.AT_MOST));
      int available=Math.max(0,h-header.getMeasuredHeight());
      footer.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(0,available-Math.min(available/2,dp(getContext(),160))),MeasureSpec.AT_MOST));
      int stageH=Math.max(0,available-footer.getMeasuredHeight()-(burst?0:dp(getContext(),6)));
      if(burst)stageH=Math.min(stageH,Math.round(w*2f/3));
      stage.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(stageH,MeasureSpec.EXACTLY));
      setMeasuredDimension(w,h);
    }
    protected void onLayout(boolean changed,int l,int t,int r,int b){
      int w=r-l,h=b-t,hh=header.getMeasuredHeight();header.layout(0,0,w,hh);
      int st=hh+(burst?0:dp(getContext(),6));stage.layout(0,st,w,st+stage.getMeasuredHeight());
      footer.layout(0,h-footer.getMeasuredHeight(),w,h);
    }
  }
  static Button action(Button b,boolean primary,int height,int size,String icon){
    Context c=b.getContext();b.setTextSize(size);b.setIncludeFontPadding(false);
    b.setCompoundDrawables(null,null,null,null);b.setCompoundDrawablePadding(dp(c,6));
    if(icon!=null){ActionIcon d=new ActionIcon(icon,c.getResources().getDisplayMetrics().density);d.setBounds(0,0,dp(c,18),dp(c,18));d.setTintList(ColorStateList.valueOf(primary?0xff111111:GalleryUi.TEXT));inline(b,d,b.getText().toString(),18);}
    b.setGravity(Gravity.CENTER);b.setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8));
    b.setMinHeight(dp(c,height));b.setMinimumHeight(dp(c,height));b.setElevation(0);b.setStateListAnimator(null);
    GradientDrawable bg=GalleryUi.shape(c,primary?GalleryUi.TEXT:Color.TRANSPARENT,height/2);
    if(!primary)bg.setStroke(dp(c,1),0xff3a3a3f);
    if(primary){android.graphics.drawable.StateListDrawable state=new android.graphics.drawable.StateListDrawable();state.addState(new int[]{android.R.attr.state_enabled},bg);state.addState(new int[]{},GalleryUi.shape(c,0xff2a2a2e,height/2));b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33aaaaaa),state,null));}else b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33aaaaaa),bg,null));
    b.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled},new int[]{}},new int[]{primary?0xff111111:GalleryUi.TEXT,0xff626b72}));
    return b;
  }
  static void inline(Button b,Drawable icon,String label,int size){
    Context c=b.getContext();int edge=dp(c,size);icon.setBounds(0,0,edge,edge);
    android.text.SpannableString text=new android.text.SpannableString("\ufffc  "+label);
    text.setSpan(new android.text.style.ReplacementSpan(){
      public int getSize(android.graphics.Paint p,CharSequence t,int start,int end,android.graphics.Paint.FontMetricsInt fm){return edge;}
      public void draw(android.graphics.Canvas canvas,CharSequence t,int start,int end,float x,int top,int y,int bottom,android.graphics.Paint paint){int saved=canvas.save();canvas.translate(x,(top+bottom-edge)/2f);icon.setState(b.getDrawableState());icon.draw(canvas);canvas.restoreToCount(saved);}
    },0,1,0);b.setText(text);
  }
  static void choice(Button b,String letter){
    Drawable icon=new Drawable(){final android.graphics.Paint paint=new android.graphics.Paint(3);
      public void draw(android.graphics.Canvas canvas){android.graphics.Rect r=getBounds();paint.setColor(b.isEnabled()?0xff111111:0xff3a3a3f);canvas.drawCircle(r.exactCenterX(),r.exactCenterY(),r.width()/2f,paint);paint.setColor(b.isEnabled()?GalleryUi.TEXT:0xff8a8a85);paint.setTextAlign(android.graphics.Paint.Align.CENTER);paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);paint.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,13,b.getResources().getDisplayMetrics()));canvas.drawText(letter,r.exactCenterX(),r.exactCenterY()-(paint.ascent()+paint.descent())/2,paint);}
      public void setAlpha(int a){}public void setColorFilter(android.graphics.ColorFilter f){}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    };inline(b,icon,"を選ぶ",24);
  }
  static View zooms(Context c,Button a,Button tie,Button b){return new ViewGroup(c){
    boolean three;int firstHeight,secondHeight;
    {addView(a);if(tie!=null)addView(tie);addView(b);}
    protected void onMeasure(int ws,int hs){
      int w=MeasureSpec.getSize(ws),gap=dp(c,8),need=0;
      for(Button button:new Button[]{a,tie,b})if(button!=null)need=Math.max(need,(int)Math.ceil(android.text.Layout.getDesiredWidth(button.getText(),button.getPaint()))+button.getPaddingLeft()+button.getPaddingRight());
      three=(w-2*gap)/3>=need;int cell=three?(w-2*gap)/3:(w-gap)/2;firstHeight=0;secondHeight=0;
      for(Button button:new Button[]{a,b}){button.measure(MeasureSpec.makeMeasureSpec(cell,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));firstHeight=Math.max(firstHeight,button.getMeasuredHeight());}
      if(tie!=null){tie.measure(MeasureSpec.makeMeasureSpec(three?cell:w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));if(three)firstHeight=Math.max(firstHeight,tie.getMeasuredHeight());else secondHeight=tie.getMeasuredHeight()+gap;}
      setMeasuredDimension(w,firstHeight+secondHeight);
    }
    protected void onLayout(boolean changed,int l,int t,int r,int bottom){int w=r-l,gap=dp(c,8),cell=a.getMeasuredWidth();a.layout(0,0,cell,firstHeight);b.layout(w-cell,0,w,firstHeight);if(tie!=null){if(three)tie.layout(cell+gap,0,cell+gap+tie.getMeasuredWidth(),firstHeight);else tie.layout(0,firstHeight+gap,w,firstHeight+secondHeight);}}
  };}
  static Drawable star(Context c){return new Drawable(){final android.graphics.Paint p=new android.graphics.Paint(3);
    public void draw(android.graphics.Canvas canvas){android.graphics.Rect r=getBounds();android.graphics.Path path=new android.graphics.Path();for(int i=0;i<10;i++){double angle=-Math.PI/2+i*Math.PI/5;float radius=r.width()*(i%2==0?.45f:.21f);float x=r.exactCenterX()+(float)Math.cos(angle)*radius,y=r.exactCenterY()+(float)Math.sin(angle)*radius;if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}path.close();p.setColor(GalleryUi.TEXT);p.setStyle(android.graphics.Paint.Style.STROKE);p.setStrokeWidth(dp(c,1.5f));canvas.drawPath(path,p);}
    public void setAlpha(int a){}public void setColorFilter(android.graphics.ColorFilter f){}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
  };}
  static void state(TextView label,StatusView.Symbol symbol,int best){
    Context c=label.getContext();boolean complete=symbol==StatusView.Symbol.COMPLETE;
    Drawable icon=new Drawable(){final android.graphics.Paint paint=new android.graphics.Paint(3);
      public void draw(android.graphics.Canvas canvas){StatusView.glyph(canvas,paint,symbol,0,0,dp(c,18),GalleryUi.TEXT,0);if(complete){Drawable star=star(c);star.setBounds(dp(c,22),0,dp(c,40),dp(c,18));star.draw(canvas);}}
      public void setAlpha(int a){}public void setColorFilter(android.graphics.ColorFilter f){}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    };icon.setBounds(0,0,dp(c,complete?40:18),dp(c,18));label.setCompoundDrawables(icon,null,null,null);label.setCompoundDrawablePadding(dp(c,4));label.setText(complete?best+"枚目":"");label.setPadding(0,dp(c,4),0,0);
  }
  static View pair(Context c,View... views){
    LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);row.setBaselineAligned(false);
    for(int i=0;i<views.length;i++){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i>0)lp.leftMargin=dp(c,8);row.addView(views[i],lp);}return row;
  }
  static void add(LinearLayout column,View child,int gap){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(column.getContext(),gap);column.addView(child,lp);}
  static View dock(Context c,Button...buttons){
    for(Button b:buttons){b.setTextSize(12);b.setIncludeFontPadding(false);b.setElevation(0);b.setStateListAnimator(null);b.setPadding(dp(c,2),dp(c,6),dp(c,2),dp(c,6));}
    View dock=GalleryUi.adaptiveDock(c,buttons);dock.setBackgroundColor(BG);dock.setPadding(0,0,0,0);return dock;
  }
}
