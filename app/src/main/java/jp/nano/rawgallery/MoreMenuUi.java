package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** The two library menus; existing item actions remain with MainActivity. */
final class MoreMenuUi {
  static Dialog show(Activity activity,String title,String[] items,DialogInterface.OnClickListener action){
    Dialog dialog=create(activity,title,items,action);dialog.show();return dialog;
  }
  static Dialog create(Activity activity,String title,String[] items,DialogInterface.OnClickListener action){
    Dialog dialog=new Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    dialog.setCanceledOnTouchOutside(true);
    Content content=new Content(activity,title,items,index->{dialog.dismiss();action.onClick(dialog,index);},dialog::dismiss);
    dialog.setContentView(content);dialog.create();
    int width=activity.getWindow().getDecorView().getWidth();
    GalleryUi.styleSheet(dialog,width>0?width:activity.getResources().getDisplayMetrics().widthPixels);
    DiagnosticWindow.install(dialog.getWindow());
    dialog.getWindow().setBackgroundDrawable(GalleryUi.shape(activity,0xff1c1c1f,20));
    dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    WindowManager.LayoutParams attributes=dialog.getWindow().getAttributes();attributes.dimAmount=.64f;dialog.getWindow().setAttributes(attributes);
    dialog.getWindow().getDecorView().setAccessibilityPaneTitle(title);
    return dialog;
  }
  private static int dp(Context c,float value){return GalleryUi.dp(c,value);}
  private static Button button(Context c,String label,int size,int line){
    Button b=new Button(c);b.setText(label);b.setContentDescription(label);b.setAllCaps(false);b.setTextColor(GalleryUi.TEXT);b.setTextSize(size);b.setIncludeFontPadding(false);b.setLineHeight(Math.round(b.getTextSize()*line/size));b.setMinHeight(dp(c,48));b.setMinimumHeight(dp(c,48));b.setMinWidth(0);b.setMinimumWidth(0);
    b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),new ColorDrawable(Color.TRANSPARENT),null));return b;
  }
  private static final class Chevron extends Drawable {
    final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    public void draw(Canvas canvas){Rect b=getBounds();int saved=canvas.save();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);paint.setColor(GalleryUi.MUTED);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);Path path=new Path();path.moveTo(9,6);path.lineTo(15,12);path.lineTo(9,18);canvas.drawPath(path,paint);canvas.restoreToCount(saved);}
    public void setAlpha(int value){}public void setColorFilter(ColorFilter filter){}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
  }
  static final class Content extends ScrollView {
    final LinearLayout column,top,bottom,rows;
    final ScrollView list;
    Content(Context c,String title,String[] items,java.util.function.IntConsumer select,Runnable close){
      super(c);setFillViewport(false);setClipToPadding(false);setVerticalScrollBarEnabled(false);
      column=new LinearLayout(c);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(0,0,0,0);addView(column,new LayoutParams(-1,-2));
      top=new LinearLayout(c);top.setOrientation(LinearLayout.VERTICAL);
      View handle=new View(c);handle.setBackground(GalleryUi.shape(c,0xff4a4a50,2));handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(c,32),dp(c,4));hp.gravity=Gravity.CENTER_HORIZONTAL;hp.topMargin=dp(c,12);hp.bottomMargin=dp(c,12);top.addView(handle,hp);
      TextView heading=new TextView(c);heading.setText(title);heading.setTextColor(GalleryUi.TEXT);heading.setTextSize(20);heading.setIncludeFontPadding(false);heading.setLineHeight(Math.round(heading.getTextSize()*28/20));heading.setTypeface(Typeface.create(Typeface.DEFAULT,600,false));heading.setPadding(dp(c,24),0,dp(c,24),dp(c,4));heading.setAccessibilityHeading(true);top.addView(heading,new LinearLayout.LayoutParams(-1,-2));column.addView(top);
      rows=new LinearLayout(c);rows.setOrientation(LinearLayout.VERTICAL);
      for(int i=0;i<items.length;i++){final int index=i;Button row=button(c,items[i],15,22);row.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);row.setTypeface(Typeface.create(Typeface.DEFAULT,400,false));row.setPadding(dp(c,24),0,dp(c,24),0);Chevron arrow=new Chevron();arrow.setBounds(0,0,dp(c,20),dp(c,20));row.setCompoundDrawables(null,null,arrow,null);row.setCompoundDrawablePadding(dp(c,16));row.setOnClickListener(v->{GalleryUi.feedback(v);select.accept(index);});rows.addView(row,new LinearLayout.LayoutParams(-1,-2));}
      list=new ScrollView(c);list.setFillViewport(false);list.addView(rows,new LayoutParams(-1,-2));column.addView(list,new LinearLayout.LayoutParams(-1,-2));
      bottom=new LinearLayout(c);bottom.setPadding(dp(c,16),dp(c,8),dp(c,16),dp(c,20));Button cancel=button(c,"閉じる",14,20);cancel.setTypeface(Typeface.create(Typeface.DEFAULT,500,false));GradientDrawable shape=GalleryUi.shape(c,Color.TRANSPARENT,24);shape.setStroke(dp(c,1),0xff3a3a3f);cancel.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),shape,null));cancel.setPadding(dp(c,16),0,dp(c,16),0);cancel.setOnClickListener(v->close.run());bottom.addView(cancel,new LinearLayout.LayoutParams(-1,-2));column.addView(bottom);
    }
    @Override protected void onMeasure(int width,int height){
      Rect visible=new Rect();getWindowVisibleDisplayFrame(visible);int available=visible.height()>0?visible.height():getResources().getDisplayMetrics().heightPixels;
      int cap=(int)(available*.48f);if(MeasureSpec.getMode(height)!=MeasureSpec.UNSPECIFIED)cap=Math.min(cap,MeasureSpec.getSize(height));
      int exact=MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(width),MeasureSpec.EXACTLY),free=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED);
      top.measure(exact,free);bottom.measure(exact,free);rows.measure(exact,free);
      int remaining=cap-top.getMeasuredHeight()-bottom.getMeasuredHeight();
      int listHeight=remaining<dp(getContext(),48)?rows.getMeasuredHeight():Math.min(rows.getMeasuredHeight(),remaining);
      LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)list.getLayoutParams();if(params.height!=listHeight){params.height=listHeight;list.setLayoutParams(params);}
      super.onMeasure(width,MeasureSpec.makeMeasureSpec(Math.max(0,cap),MeasureSpec.AT_MOST));
    }
  }
}
