package jp.nano.rawgallery;

import android.content.Context;
import com.google.android.material.materialswitch.MaterialSwitch;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.*;
import android.widget.*;
import java.util.function.IntSupplier;

/** Selection presentation; the activity owns selection and sharing decisions. */
final class SelectionUi {
  static final class Summary extends ViewGroup {
    private final java.util.List<Rect> positions=new java.util.ArrayList<>();
    private int contentHeight;
    Summary(Context c){super(c);setMinimumHeight(GalleryUi.dp(c,40));setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);}
    protected void onMeasure(int width,int height){
      int available=MeasureSpec.getSize(width),x=0,y=0,row=0,max=0,gap=GalleryUi.dp(getContext(),4);
      positions.clear();
      for(int i=0;i<getChildCount();i++){
        View child=getChildAt(i);MarginLayoutParams lp=(MarginLayoutParams)child.getLayoutParams();
        child.measure(MeasureSpec.makeMeasureSpec(Math.max(0,available-lp.leftMargin-lp.rightMargin),MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
        int w=child.getMeasuredWidth()+lp.leftMargin+lp.rightMargin;
        if(x>0&&x+w>available){x=0;y+=row+gap;row=0;}
        positions.add(new Rect(x+lp.leftMargin,y,x+lp.leftMargin+child.getMeasuredWidth(),y+child.getMeasuredHeight()));
        x+=w;max=Math.max(max,x);row=Math.max(row,child.getMeasuredHeight());
      }
      contentHeight=y+row;
      setMeasuredDimension(resolveSize(max,width),resolveSize(Math.max(getMinimumHeight(),contentHeight),height));
    }
    protected void onLayout(boolean changed,int l,int t,int r,int b){
      int offset=Math.max(0,(getHeight()-contentHeight)/2);
      for(int i=0;i<getChildCount();i++){Rect p=positions.get(i);getChildAt(i).layout(p.left,p.top+offset,p.right,p.bottom+offset);}
    }
    void counts(int target,int hidden,int unresolved,int outside,boolean any){
      removeAllViews();if(any){badge(0,target+"枚");if(hidden>0)badge(1,hidden+"枚");if(unresolved>0)badge(2,unresolved+"組");if(outside>0)badge(3,"ほか "+outside+"枚");}
      setContentDescription("共有の対象 "+target+"枚"+(hidden>0?"。今の絞り込みで見えない選択 "+hidden+"枚":"")+(unresolved>0?"。順位が未決定の連写 "+unresolved+"組":"")+(outside>0?"。ほかの連写の選択 "+outside+"枚。この連写の順位には使わない":""));
    }
    void capacity(ShareCapacity.Result capacity){
      if(capacity==null)return;
      Context c=getContext();boolean warning=capacity.state==ShareCapacity.State.OVER||capacity.state==ShareCapacity.State.FAILED||capacity.state==ShareCapacity.State.SOURCE_LIMIT||capacity.state==ShareCapacity.State.SOURCE_SIZE;
      TextView value=new TextView(c);value.setTag("share-capacity");
      value.setText(capacity.state==ShareCapacity.State.FAILED?"確認できません":capacity.label());
      value.setTextSize(13);value.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));value.setFontFeatureSettings("tnum");
      value.setTextColor(warning?0xffffb4a8:GalleryUi.TEXT);value.setIncludeFontPadding(false);value.setGravity(Gravity.CENTER_VERTICAL);
      value.setMinimumHeight(GalleryUi.dp(c,28));value.setPadding(GalleryUi.dp(c,6),GalleryUi.dp(c,4),GalleryUi.dp(c,8),GalleryUi.dp(c,4));
      android.graphics.drawable.GradientDrawable shape=GalleryUi.shape(c,0xff1f1f22,14);
      shape.setStroke(Math.max(1,Math.round(c.getResources().getDisplayMetrics().density*(warning?1.5f:1))),warning?0xffff8a7a:0xff3a3a3f);value.setBackground(shape);
      if(warning||capacity.state==ShareCapacity.State.CHECKING){
        Drawable icon=new Drawable(){final Paint paint=new Paint(3);public void draw(Canvas canvas){
          int save=canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/16f,getBounds().height()/16f);
          boolean moving=!warning&&android.animation.ValueAnimator.areAnimatorsEnabled();
          StatusView.glyph(canvas,paint,warning?StatusView.Symbol.FAILED:StatusView.Symbol.RUNNING,0,0,16,warning?0xffff8a7a:GalleryUi.TEXT,moving?(android.os.SystemClock.uptimeMillis()%1000)*.36f:0);
          canvas.restoreToCount(save);if(moving&&value.isShown())value.postInvalidateDelayed(50);
        }public void setAlpha(int a){}public void setColorFilter(ColorFilter f){}public int getOpacity(){return PixelFormat.TRANSLUCENT;}};
        icon.setBounds(0,0,GalleryUi.dp(c,16),GalleryUi.dp(c,16));value.setCompoundDrawables(icon,null,null,null);value.setCompoundDrawablePadding(GalleryUi.dp(c,4));
      }
      value.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
      MarginLayoutParams lp=new MarginLayoutParams(-2,-2);lp.rightMargin=GalleryUi.dp(c,6);addView(value,Math.min(1,getChildCount()),lp);
      setContentDescription(getContentDescription()+"。"+capacity.description());
    }
    private void badge(int kind,String value){
      Context c=getContext();TextView t=new TextView(c);t.setText(value);t.setTextSize(12);t.setTextColor(GalleryUi.TEXT);t.setIncludeFontPadding(false);t.setGravity(Gravity.CENTER_VERTICAL);android.graphics.drawable.GradientDrawable badgeShape=GalleryUi.shape(c,0xff1f1f22,14);badgeShape.setStroke(GalleryUi.dp(c,1),0xff3a3a3f);t.setBackground(badgeShape);t.setPadding(GalleryUi.dp(c,7),GalleryUi.dp(c,5),GalleryUi.dp(c,7),GalleryUi.dp(c,5));t.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
      Drawable icon=new Drawable(){final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);public void draw(Canvas canvas){int save=canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/16f,getBounds().height()/16f);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.2f);p.setColor(GalleryUi.TEXT);if(kind==0){ActionIcon action=new ActionIcon("共有",16f/26);action.setTintList(ColorStateList.valueOf(GalleryUi.TEXT));action.setBounds(0,0,16,16);action.draw(canvas);}else if(kind==1){canvas.drawOval(1,4,15,12,p);canvas.drawCircle(8,8,2,p);canvas.drawLine(2,2,14,14,p);}else if(kind==3){canvas.drawRoundRect(2,2,12,12,2,2,p);canvas.drawLine(5,15,14,15,p);canvas.drawLine(15,5,15,14,p);}else{canvas.drawRoundRect(2,2,13,11,2,2,p);canvas.drawRoundRect(4,5,15,14,2,2,p);p.setStyle(Paint.Style.FILL);p.setTextSize(10);p.setTypeface(Typeface.DEFAULT_BOLD);canvas.drawText("?",6,12,p);}canvas.restoreToCount(save);}public void setAlpha(int a){}public void setColorFilter(ColorFilter f){}public int getOpacity(){return PixelFormat.TRANSLUCENT;}};
      icon.setBounds(0,0,GalleryUi.dp(c,14),GalleryUi.dp(c,14));t.setCompoundDrawables(icon,null,null,null);t.setCompoundDrawablePadding(GalleryUi.dp(c,4));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.rightMargin=GalleryUi.dp(c,6);addView(t,lp);
    }
  }
  static final class BestSwitch extends MaterialSwitch {
    BestSwitch(Context c) {
      super(c);setText("連写は1位だけ共有（同率を含む）");setTextSize(14);
      setTextColor(GalleryUi.TEXT);setMinHeight(GalleryUi.dp(c,48));
      setMinimumHeight(GalleryUi.dp(c,48));
      setPadding(0,GalleryUi.dp(c,6),0,GalleryUi.dp(c,6));
    }
  }
  static Button action(Context c,String title,boolean primary,Runnable click) {
    Button button=GalleryUi.button(c,title,click);
    button.setCompoundDrawables(null,null,null,null);
    button.setTextSize(14);
    button.setPadding(GalleryUi.dp(c,12),GalleryUi.dp(c,8),GalleryUi.dp(c,12),GalleryUi.dp(c,8));
    if(primary)MaterialUi.filled(button);else MaterialUi.outlined(button);
    return button;
  }
  static void allIcon(Button button){Context c=button.getContext();Drawable icon=new Drawable(){final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);public void draw(Canvas canvas){int save=canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);p.setColor(GalleryUi.TEXT);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);canvas.drawRoundRect(4,4,20,20,2.5f,2.5f,p);Path tick=new Path();tick.moveTo(8.5f,12.3f);tick.lineTo(11,14.8f);tick.lineTo(15.8f,9.8f);canvas.drawPath(tick,p);canvas.restoreToCount(save);}public void setAlpha(int a){}public void setColorFilter(ColorFilter f){}public int getOpacity(){return PixelFormat.TRANSLUCENT;}};icon.setBounds(0,0,GalleryUi.dp(c,24),GalleryUi.dp(c,24));button.setCompoundDrawables(null,icon,null,null);}
  static void finishFooter(LinearLayout root, int first, IntSupplier reservedContentHeight) {
    GalleryUi.bottomControls(root, first, GalleryUi.BG, reservedContentHeight);
    ScrollView scroll = (ScrollView) root.getChildAt(root.getChildCount() - 1);
    LinearLayout panel = (LinearLayout) scroll.getChildAt(0);
    panel.setClipToPadding(false);
    Context c = root.getContext();
    for (int i = 1; i < panel.getChildCount(); i++) {
      View child = panel.getChildAt(i);
      LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
      lp.topMargin = GalleryUi.dp(c, 8);
      child.setLayoutParams(lp);
    }
    panel.setPadding(GalleryUi.dp(c, 16), GalleryUi.dp(c, 8), GalleryUi.dp(c, 16), GalleryUi.dp(c, 20));
  }
}
