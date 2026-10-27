package jp.nano.rawgallery;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.*;
import android.widget.*;

/** Selection presentation; the activity owns selection and sharing decisions. */
final class SelectionUi {
  static final class Summary extends LinearLayout {
    Summary(Context c){super(c);setGravity(Gravity.CENTER_VERTICAL);setMinimumHeight(GalleryUi.dp(c,40));setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);}
    void counts(int target,int hidden,int unresolved,boolean any){
      removeAllViews();if(any){badge(0,target+"枚");if(hidden>0)badge(1,hidden+"枚");if(unresolved>0)badge(2,unresolved+"組");}
      setContentDescription("共有の対象 "+target+"枚"+(hidden>0?"。今の絞り込みで見えない選択 "+hidden+"枚":"")+(unresolved>0?"。順位が未決定の連写 "+unresolved+"組":""));
    }
    private void badge(int kind,String value){
      Context c=getContext();TextView t=new TextView(c);t.setText(value);t.setTextSize(12);t.setTextColor(GalleryUi.TEXT);t.setIncludeFontPadding(false);t.setGravity(Gravity.CENTER_VERTICAL);android.graphics.drawable.GradientDrawable badgeShape=GalleryUi.shape(c,0xff1f1f22,14);badgeShape.setStroke(GalleryUi.dp(c,1),0xff3a3a3f);t.setBackground(badgeShape);t.setPadding(GalleryUi.dp(c,7),GalleryUi.dp(c,5),GalleryUi.dp(c,7),GalleryUi.dp(c,5));t.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
      Drawable icon=new Drawable(){final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);public void draw(Canvas canvas){int save=canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/16f,getBounds().height()/16f);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.2f);p.setColor(GalleryUi.TEXT);if(kind==0){ActionIcon action=new ActionIcon("共有",16f/26);action.setTintList(ColorStateList.valueOf(GalleryUi.TEXT));action.setBounds(0,0,16,16);action.draw(canvas);}else if(kind==1){canvas.drawOval(1,4,15,12,p);canvas.drawCircle(8,8,2,p);canvas.drawLine(2,2,14,14,p);}else{canvas.drawRoundRect(2,2,13,11,2,2,p);canvas.drawRoundRect(4,5,15,14,2,2,p);p.setStyle(Paint.Style.FILL);p.setTextSize(10);p.setTypeface(Typeface.DEFAULT_BOLD);canvas.drawText("?",6,12,p);}canvas.restoreToCount(save);}public void setAlpha(int a){}public void setColorFilter(ColorFilter f){}public int getOpacity(){return PixelFormat.TRANSLUCENT;}};
      icon.setBounds(0,0,GalleryUi.dp(c,14),GalleryUi.dp(c,14));t.setCompoundDrawables(icon,null,null,null);t.setCompoundDrawablePadding(GalleryUi.dp(c,4));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.rightMargin=GalleryUi.dp(c,6);addView(t,lp);
    }
  }
  static final class BestSwitch extends CompoundButton {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    BestSwitch(Context c){super(c);setText("連写は1位だけ共有（同率を含む）");setTextSize(14);setTextColor(GalleryUi.TEXT);setIncludeFontPadding(false);setButtonDrawable((Drawable)null);setGravity(Gravity.START|Gravity.CENTER_VERTICAL);setPadding(0,GalleryUi.dp(c,6),GalleryUi.dp(c,76),GalleryUi.dp(c,6));setMinHeight(GalleryUi.dp(c,48));setMinimumHeight(GalleryUi.dp(c,48));setBackgroundColor(GalleryUi.BG);}
    public CharSequence getAccessibilityClassName(){return Switch.class.getName();}
    protected void onDraw(Canvas canvas){super.onDraw(canvas);float d=getResources().getDisplayMetrics().density,w=getWidth()/d,h=getHeight()/d;int save=canvas.save();canvas.scale(d,d);paint.setColor(isChecked()?GalleryUi.TEXT:0xff8c8c86);paint.setStyle(isChecked()?Paint.Style.FILL:Paint.Style.STROKE);paint.setStrokeWidth(2);canvas.drawRoundRect(w-58,h/2-16,w-6,h/2+16,16,16,paint);paint.setStyle(Paint.Style.FILL);paint.setColor(isChecked()?0xff111111:0xff8c8c86);canvas.drawCircle(w-(isChecked()?24:44),h/2,10,paint);canvas.restoreToCount(save);}
  }
  static Button action(Context c,String title,boolean primary,Runnable click){
    Button b=GalleryUi.button(c,title,click);b.setCompoundDrawables(null,null,null,null);b.setTextSize(primary?15:14);b.setIncludeFontPadding(false);b.setMinHeight(GalleryUi.dp(c,48));b.setMinimumHeight(GalleryUi.dp(c,48));
    int enabledColor=primary?0xff111111:0xfff5b947;b.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled},new int[]{}},new int[]{enabledColor,0xff626b72}));
    android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();for(boolean enabled:new boolean[]{false,true}){android.graphics.drawable.GradientDrawable shape=GalleryUi.shape(c,primary?(enabled?GalleryUi.TEXT:0xff26262a):0,24);if(!primary)shape.setStroke(GalleryUi.dp(c,1),enabled?0xfff5b947:0xff303034);states.addState(enabled?new int[]{}:new int[]{-android.R.attr.state_enabled},shape);}b.setBackground(states);b.setPadding(GalleryUi.dp(c,12),GalleryUi.dp(c,4),GalleryUi.dp(c,12),GalleryUi.dp(c,4));
    android.text.SpannableString label=new android.text.SpannableString(" "+title);label.setSpan(new android.text.style.ReplacementSpan(){public int getSize(Paint paint,CharSequence value,int start,int end,Paint.FontMetricsInt fm){return GalleryUi.dp(c,26);}public void draw(Canvas canvas,CharSequence value,int start,int end,float x,int top,int y,int bottom,Paint paint){ActionIcon icon=new ActionIcon(title,c.getResources().getDisplayMetrics().density*18f/26);icon.setTintList(ColorStateList.valueOf(paint.getColor()));int n=GalleryUi.dp(c,18),center=Math.round(y+(paint.ascent()+paint.descent())/2);icon.setBounds((int)x,center-n/2,(int)x+n,center+n/2);if(title.equals("ソート")){int save=canvas.save();canvas.translate(x,center-n/2f);canvas.scale(n/24f,n/24f);Paint outline=new Paint(Paint.ANTI_ALIAS_FLAG);outline.setColor(paint.getColor());outline.setStyle(Paint.Style.STROKE);outline.setStrokeWidth(1.9f);canvas.drawRoundRect(4,3.5f,20,10.5f,1.5f,1.5f,outline);canvas.drawRoundRect(4,13.5f,20,20.5f,1.5f,1.5f,outline);canvas.restoreToCount(save);}else icon.draw(canvas);}},0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);b.setText(label);return b;
  }
  static void allIcon(Button button){Context c=button.getContext();Drawable icon=new Drawable(){final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);public void draw(Canvas canvas){int save=canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);p.setColor(GalleryUi.TEXT);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);canvas.drawRoundRect(4,4,20,20,2.5f,2.5f,p);Path tick=new Path();tick.moveTo(8.5f,12.3f);tick.lineTo(11,14.8f);tick.lineTo(15.8f,9.8f);canvas.drawPath(tick,p);canvas.restoreToCount(save);}public void setAlpha(int a){}public void setColorFilter(ColorFilter f){}public int getOpacity(){return PixelFormat.TRANSLUCENT;}};icon.setBounds(0,0,GalleryUi.dp(c,24),GalleryUi.dp(c,24));button.setCompoundDrawables(null,icon,null,null);}
  static void finishFooter(LinearLayout root,int first){GalleryUi.bottomControls(root,first,GalleryUi.BG);ScrollView scroll=(ScrollView)root.getChildAt(root.getChildCount()-1);LinearLayout panel=(LinearLayout)scroll.getChildAt(0);panel.setClipToPadding(false);for(int i=1;i<panel.getChildCount();i++){View child=panel.getChildAt(i);LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)child.getLayoutParams();lp.topMargin=GalleryUi.dp(root.getContext(),8);child.setLayoutParams(lp);}panel.setPadding(GalleryUi.dp(root.getContext(),16),GalleryUi.dp(root.getContext(),8),GalleryUi.dp(root.getContext(),16),GalleryUi.dp(root.getContext(),20));}
}
