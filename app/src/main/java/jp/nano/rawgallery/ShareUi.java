package jp.nano.rawgallery;

import android.app.Dialog;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Sharing presentation; preparation and sending remain in MainActivity. */
final class ShareUi {
  static int dp(Context c,int n){return GalleryUi.dp(c,n);}
  static TextView text(Context c,String value,int size,int line,int color){return PhotoListUi.text(c,value,size,line,color);}
  static LinearLayout column(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(LinearLayout.VERTICAL);return v;}
  static void add(LinearLayout parent,View child,int left,int top,int right,int bottom){Context c=parent.getContext();LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(c,left),dp(c,top),dp(c,right),dp(c,bottom));parent.addView(child,p);}
  static Button action(Context c,String title,boolean primary,Runnable click){Button b=GalleryUi.button(c,title,click);b.setCompoundDrawables(null,null,null,null);b.setTextSize(primary?15:14);b.setIncludeFontPadding(false);b.setLineHeight(Math.round(b.getTextSize()*18/(primary?15:14)));b.setMinHeight(dp(c,primary?52:48));b.setMinimumHeight(dp(c,primary?52:48));b.setPadding(dp(c,16),dp(c,4),dp(c,16),dp(c,4));b.setTextColor(primary?0xff111111:GalleryUi.TEXT);GradientDrawable shape=GalleryUi.shape(c,primary?GalleryUi.TEXT:0,primary?26:24);if(!primary)shape.setStroke(dp(c,1),0xff3a3a3f);b.setBackground(shape);return b;}
  static class Sheet extends Dialog {
    final LinearLayout content;
    Sheet(Context c,String title){super(c);requestWindowFeature(Window.FEATURE_NO_TITLE);content=column(c);content.setPadding(0,dp(c,8),0,dp(c,20));View handle=new View(c);handle.setBackground(GalleryUi.shape(c,0xff4a4a50,2));handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(c,32),dp(c,4));hp.gravity=Gravity.CENTER_HORIZONTAL;hp.topMargin=dp(c,4);hp.bottomMargin=dp(c,12);content.addView(handle,hp);TextView heading=text(c,title,20,28,GalleryUi.TEXT);heading.setTypeface(Typeface.create(Typeface.DEFAULT,600,false));heading.setAccessibilityHeading(true);add(content,heading,24,0,24,4);
      ScrollView scroll=new ScrollView(c){protected void onMeasure(int w,int h){Rect visible=new Rect();getWindowVisibleDisplayFrame(visible);int available=visible.height()>0?visible.height():getResources().getDisplayMetrics().heightPixels;int cap=Math.min(MeasureSpec.getSize(h),Math.max(dp(c,48),available-dp(c,24)));super.onMeasure(w,MeasureSpec.makeMeasureSpec(cap,MeasureSpec.AT_MOST));}};scroll.setVerticalScrollBarEnabled(false);scroll.addView(content);setContentView(scroll);setCanceledOnTouchOutside(true);Window win=getWindow();GradientDrawable bg=GalleryUi.shape(c,0xff1c1c1f,0);float r=dp(c,28);bg.setCornerRadii(new float[]{r,r,r,r,0,0,0,0});win.setBackgroundDrawable(bg);win.setGravity(Gravity.BOTTOM);win.setDimAmount(.64f);win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);win.setWindowAnimations(0);win.setLayout(-1,-2);DiagnosticWindow.install(win);
    }
    public void show(){super.show();getWindow().setLayout(-1,-2);}
  }
  /** Wrapping rows keep every item reachable without shrinking text or hit targets. */
  static final class Wrap extends ViewGroup {
    final int gap;
    Wrap(Context c,int gap){super(c);this.gap=dp(c,gap);}
    protected void onMeasure(int w,int h){int width=MeasureSpec.getSize(w),x=0,y=0,row=0;for(int i=0;i<getChildCount();i++){View child=getChildAt(i);ViewGroup.LayoutParams lp=child.getLayoutParams();child.measure(MeasureSpec.makeMeasureSpec(lp.width>=0?Math.min(width,lp.width):width,lp.width>=0?MeasureSpec.EXACTLY:MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(lp.height>=0?lp.height:0,lp.height>=0?MeasureSpec.EXACTLY:MeasureSpec.UNSPECIFIED));if(x>0&&x+child.getMeasuredWidth()>width){x=0;y+=row+gap;row=0;}x+=child.getMeasuredWidth()+gap;row=Math.max(row,child.getMeasuredHeight());}setMeasuredDimension(width,y+row);}
    protected void onLayout(boolean changed,int l,int t,int r,int b){int x=0,y=0,row=0;for(int i=0;i<getChildCount();i++){View child=getChildAt(i);if(x>0&&x+child.getMeasuredWidth()>getWidth()){x=0;y+=row+gap;row=0;}child.layout(x,y,x+child.getMeasuredWidth(),y+child.getMeasuredHeight());x+=child.getMeasuredWidth()+gap;row=Math.max(row,child.getMeasuredHeight());}}
  }
  static final class Menu extends Sheet {
    final List<ImageView> previews=new ArrayList<>();
    final List<PhotoRepository.Photo> previewPhotos=new ArrayList<>();
    final Button jpeg,original;
    final TextView explanation;
    boolean raw;
    Menu(Context c,List<PhotoRepository.Photo> photos,List<ShareTargets.Target> targets,int cloud,Set<String> best,BiConsumer<Boolean,ShareTargets.Target> chosen){super(c,photos.size()+"枚を共有");
      Wrap thumbs=new Wrap(c,6);int shown=Math.min(photos.size(),photos.size()>6?5:6);
      for(int i=0;i<shown;i++){PhotoRepository.Photo photo=photos.get(i);FrameLayout tile=new FrameLayout(c);tile.setBackground(GalleryUi.shape(c,0xff26262a,6));tile.setClipToOutline(true);ImageView image=new ImageView(c);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);tile.addView(image,new FrameLayout.LayoutParams(-1,-1));previews.add(image);previewPhotos.add(photo);if(best.contains(photo.uri.toString())){View star=new View(c){final Paint p=new Paint(3);protected void onDraw(Canvas canvas){float d=getResources().getDisplayMetrics().density;canvas.save();canvas.scale(d,d);p.setStyle(Paint.Style.FILL);p.setColor(0xfff5b947);canvas.drawRoundRect(0,0,16,16,4,4,p);p.setColor(0xff241800);Path path=new Path();for(int j=0;j<10;j++){double a=-Math.PI/2+j*Math.PI/5;float radius=j%2==0?5.5f:2.4f;float x=8+(float)Math.cos(a)*radius,y=8+(float)Math.sin(a)*radius;if(j==0)path.moveTo(x,y);else path.lineTo(x,y);}path.close();canvas.drawPath(path,p);canvas.restore();}};FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(dp(c,16),dp(c,16),Gravity.BOTTOM|Gravity.LEFT);sp.setMargins(dp(c,3),0,0,dp(c,3));tile.addView(star,sp);}thumbs.addView(tile,new ViewGroup.LayoutParams(dp(c,52),dp(c,52)));}
      if(photos.size()>shown){TextView more=text(c,"+"+(photos.size()-shown),13,18,GalleryUi.TEXT);more.setGravity(Gravity.CENTER);more.setBackground(GalleryUi.shape(c,0xff26262a,6));thumbs.addView(more,new ViewGroup.LayoutParams(dp(c,52),dp(c,52)));}thumbs.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);add(content,thumbs,24,12,24,4);
      if(cloud>0){TextView notice=text(c,cloud+"枚はクラウドからダウンロードします",13,20,GalleryUi.MUTED);notice.setCompoundDrawablesWithIntrinsicBounds(new CloudIcon(c),null,null,null);notice.setCompoundDrawablePadding(dp(c,8));add(content,notice,24,8,24,0);}
      LinearLayout formats=new LinearLayout(c);formats.setGravity(Gravity.CENTER_VERTICAL);formats.setBackground(GalleryUi.shape(c,0xff26262a,24));formats.setContentDescription("共有する形式");jpeg=action(c,"JPEGで共有",false,()->setRaw(false));original=action(c,"元の形式で共有",false,()->setRaw(true));formats.addView(jpeg,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(0,-2,1);fp.leftMargin=dp(c,4);formats.addView(original,fp);add(content,formats,16,12,16,0);
      explanation=text(c,"",12,18,GalleryUi.MUTED);add(content,explanation,24,6,24,0);setRaw(false);
      Wrap destinations=new Wrap(c,4);for(ShareTargets.Target target:targets)destinations.addView(destination(c,target.label,target.raw?"元の形式":"JPEG",false,()->{dismiss();chosen.accept(target.raw,target);}),new ViewGroup.LayoutParams(dp(c,84),-2));destinations.addView(destination(c,"共有先を選ぶ","",true,()->{dismiss();chosen.accept(raw,null);}),new ViewGroup.LayoutParams(dp(c,84),-2));add(content,destinations,12,16,12,0);add(content,action(c,"閉じる",false,this::dismiss),16,12,16,0);
    }
    void setRaw(boolean value){raw=value;for(Button button:new Button[]{jpeg,original}){boolean selected=button==original?raw:!raw;button.setSelected(selected);button.setStateDescription(selected?"選択中":"未選択");button.setTextColor(selected?0xff111111:GalleryUi.TEXT);button.setBackground(GalleryUi.shape(getContext(),selected?GalleryUi.TEXT:0,24));button.setCompoundDrawables(null,null,null,null);button.setText((selected?"✓ ":"　 ")+(button==jpeg?"JPEGで共有":"元の形式で共有"));}explanation.setText(raw?"撮影した形式のまま · RAW / JPEG":"RAWの場合は、ファイル内のJPEG画像を使います");}
  }
  static View destination(Context c,String label,String format,boolean chooser,Runnable clicked){LinearLayout item=column(c);item.setPadding(0,dp(c,4),0,0);item.setGravity(Gravity.CENTER_HORIZONTAL);item.setMinimumHeight(dp(c,84));item.setClickable(true);item.setFocusable(true);item.setContentDescription(label+(format.isEmpty()?"。形式は上の選択":"（保存した形式："+format+"）"));item.setOnClickListener(v->{GalleryUi.feedback(v);clicked.run();});View circle=new View(c){final Paint p=new Paint(3);protected void onDraw(Canvas canvas){float d=getResources().getDisplayMetrics().density;canvas.save();canvas.scale(d,d);p.setColor(0xff2a2a2e);p.setStyle(Paint.Style.FILL);canvas.drawCircle(28,28,28,p);if(chooser){ActionIcon icon=new ActionIcon("共有",24f/26);icon.setTintList(android.content.res.ColorStateList.valueOf(GalleryUi.TEXT));icon.setBounds(16,16,40,40);icon.draw(canvas);}else{p.setColor(GalleryUi.TEXT);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f);canvas.drawRoundRect(18,18,38,33,2,2,p);canvas.drawLine(28,33,28,38,p);canvas.drawLine(23,38,33,38,p);}canvas.restore();}};item.addView(circle,new LinearLayout.LayoutParams(dp(c,56),dp(c,56)));TextView name=text(c,label,12,18,GalleryUi.TEXT);name.setGravity(Gravity.CENTER);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.topMargin=dp(c,6);item.addView(name,np);if(!format.isEmpty()){TextView sub=text(c,format,12,18,GalleryUi.MUTED);sub.setGravity(Gravity.CENTER);item.addView(sub,new LinearLayout.LayoutParams(-1,-2));}for(int i=0;i<item.getChildCount();i++)item.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return item;}
  static final class CloudIcon extends android.graphics.drawable.Drawable {
    final Paint p=new Paint(3);final int size;
    CloudIcon(Context c){size=dp(c,16);setBounds(0,0,size,size);}
    public int getIntrinsicWidth(){return size;}public int getIntrinsicHeight(){return size;}
    public void draw(Canvas canvas){int save=canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/16f,getBounds().height()/16f);p.setColor(GalleryUi.MUTED);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.2f);Path path=new Path();path.moveTo(4,12);path.cubicTo(-1,12,0,6,4,6);path.cubicTo(5,0,12,1,12,6);path.cubicTo(17,6,17,12,12,12);path.close();canvas.drawPath(path,p);canvas.restoreToCount(save);}
    public void setAlpha(int a){}public void setColorFilter(ColorFilter f){}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
  }
}
