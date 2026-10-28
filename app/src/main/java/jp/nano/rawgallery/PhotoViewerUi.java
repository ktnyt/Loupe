package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** The photo-first S08 layout; navigation and data changes belong to the activity. */
final class PhotoViewerUi extends ViewGroup {
  final FrameLayout stage;
  final ZoomImageView image;
  final LinearLayout footer;
  final ViewGroup dock;
  final TextView date;
  private Button zoom;
  private final View cloud, placeholder;
  private StatusView status;
  private boolean failed;

  PhotoViewerUi(Context context, PhotoRepository.Photo photo) {
    super(context);
    setBackgroundColor(Color.BLACK);
    date = PhotoListUi.text(context, date(photo, false), 15, 22, GalleryUi.TEXT);
    date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    date.setGravity(Gravity.CENTER);
    date.setContentDescription(date(photo, true));
    addView(date);
    stage = new FrameLayout(context);
    stage.setTag("viewer-stage");
    image = new ZoomImageView(context);
    image.setContentDescription("写真、" + date(photo, true)
        + ("archive".equals(photo.uri.getScheme()) ? "、クラウドにあります" : ""));
    placeholder=new View(context){private final Paint p=new Paint();protected void onDraw(Canvas canvas){if(failed){p.setColor(0xff19191b);canvas.drawRect(photoRect(),p);}}};
    placeholder.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    stage.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
    stage.addView(image, new FrameLayout.LayoutParams(-1, -1));
    stage.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->refreshStatus());
    cloud = new View(context) {
      private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
      protected void onDraw(Canvas canvas) {
        RectF bounds = image.photoBounds();
        if (bounds == null || image.isZoomed() || !"archive".equals(photo.uri.getScheme())) return;
        float density = getResources().getDisplayMetrics().density;
        int saved = canvas.save();
        canvas.translate(Math.max(0, bounds.left) + 12*density,
            Math.max(0, bounds.top) + 12*density);
        canvas.scale(density, density);
        paint.setStyle(Paint.Style.FILL); paint.setColor(0x9e000000);
        canvas.drawRoundRect(0, 0, 32, 24, 12, 12, paint);
        android.graphics.drawable.Drawable glyph = getResources().getDrawable(R.drawable.ic_viewer_cloud, context.getTheme());
        glyph.setBounds(9,5,23,19); glyph.draw(canvas);
        canvas.restoreToCount(saved);
      }
    };
    cloud.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    stage.addView(cloud, new FrameLayout.LayoutParams(-1,-1));
    addView(stage);
    footer = new LinearLayout(context);
    footer.setOrientation(LinearLayout.VERTICAL);
    footer.setPadding(0, 0, 0, dp(20));
    footer.setBackgroundColor(Color.BLACK);
    dock = new ViewerDock(context);
    footer.addView(dock, new LinearLayout.LayoutParams(-1,-2));
    addView(footer);
    image.setZoomListener(expanded -> {
      if (zoom != null) {
        zoom.setText(expanded ? "全体" : "拡大");
        icon(zoom,expanded?"全体":"拡大");
        zoom.setContentDescription(expanded ? "全体を表示" : "拡大");
        zoom.setSelected(expanded);
        zoom.setStateDescription(expanded ? "拡大中" : "全体表示");
      }
      cloud.invalidate();
      refreshStatus();
    });
  }

  RectF photoRect(){RectF loaded=image.photoBounds();if(loaded!=null)return loaded;float width=stage.getWidth(),height=width*2/3f;if(height>stage.getHeight()){height=stage.getHeight();width=height*1.5f;}return new RectF((stage.getWidth()-width)/2,(stage.getHeight()-height)/2,(stage.getWidth()+width)/2,(stage.getHeight()+height)/2);}
  void status(StatusView view){status=view;stage.addView(view,new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.RIGHT));refreshStatus();}
  void refreshStatus(){if(status==null||status.getParent()!=stage)return;RectF bounds=photoRect();FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)status.getLayoutParams();int top=Math.round(Math.max(0,bounds.top))+dp(2),right=Math.round(Math.max(0,stage.getWidth()-bounds.right))+dp(status.photoRightInset());if(p.topMargin!=top||p.rightMargin!=right){p.topMargin=top;p.rightMargin=right;status.setLayoutParams(p);}}
  void failed(){failed=true;placeholder.invalidate();}
  static void icon(Button button,String action){
    int resource;
    switch(action){
      case "前の写真": resource=R.drawable.ic_viewer_previous;break;
      case "次の写真": resource=R.drawable.ic_viewer_next;break;
      case "拡大": resource=R.drawable.ic_viewer_zoom_in;break;
      case "全体": resource=R.drawable.ic_viewer_zoom_out;break;
      case "共有": resource=R.drawable.ic_viewer_share;break;
      case "編集": resource=R.drawable.ic_viewer_edit;break;
      case "情報": resource=R.drawable.ic_viewer_info;break;
      case "タグ": resource=R.drawable.ic_viewer_tag;break;
      default: throw new IllegalArgumentException(action);
    }
    android.graphics.drawable.Drawable drawable=button.getContext().getDrawable(resource).mutate();
    drawable.setBounds(0,0,GalleryUi.dp(button.getContext(),24),GalleryUi.dp(button.getContext(),24));
    button.setCompoundDrawables(null,drawable,null,null);
  }

  int dp(int n) { return GalleryUi.dp(getContext(),n); }
  static String date(PhotoRepository.Photo photo, boolean spoken) {
    boolean capture = photo.taken > 0;
    long millis = capture ? photo.taken : SyncPolicy.secondsToMillis(photo.modified);
    if (millis <= 0) return spoken ? "日時は分かりません" : "—";
    Calendar value = Calendar.getInstance(); value.setTimeInMillis(millis);
    boolean currentYear = value.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR);
    String pattern = (currentYear ? "" : "yyyy年") + (spoken ? "M月d日 H時mm分" : "M月d日 HH:mm");
    String formatted = new SimpleDateFormat(pattern, Locale.JAPAN).format(new Date(millis));
    return spoken ? (capture ? "撮影日時 " : "更新日時 ") + formatted : formatted;
  }

  static Button button(Context c, String icon, String label, String spoken, Runnable action) {
    Button b = new DockButton(c);
    b.setAllCaps(false);
    b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
    b.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled},new int[]{}},new int[]{GalleryUi.TEXT,0xff626b72}));
    b.setMinWidth(0);b.setMinimumWidth(0);
    b.setOnClickListener(v->{GalleryUi.feedback(v);try(DiagnosticLog.Scope ignored=DiagnosticLog.span("action.button")){action.run();}});
    b.setText(label); b.setContentDescription(spoken);
    b.setTextSize(12); b.setIncludeFontPadding(false);
    b.setLineHeight(Math.round(b.getTextSize()*16/12));
    b.setMinHeight(GalleryUi.dp(c,64)); b.setMinimumHeight(GalleryUi.dp(c,64));
    b.setPadding(GalleryUi.dp(c,2),GalleryUi.dp(c,4),GalleryUi.dp(c,2),GalleryUi.dp(c,4));
    b.setBackgroundColor(Color.TRANSPARENT);
    icon(b,icon);
    b.setCompoundDrawablePadding(GalleryUi.dp(c,4));
    return b;
  }

  /** Each column centers its own icon and label, including wrapped labels. */
  static final class DockButton extends Button {
    private android.graphics.drawable.Drawable glyph;
    private boolean saveFailed, running;
    String[] widthLabels;
    private final Paint badge=new Paint(Paint.ANTI_ALIAS_FLAG);
    DockButton(Context c){super(c);}
    private int dp(float value){return GalleryUi.dp(getContext(),value);}
    @Override public void setCompoundDrawables(android.graphics.drawable.Drawable left,android.graphics.drawable.Drawable top,android.graphics.drawable.Drawable right,android.graphics.drawable.Drawable bottom){
      if(glyph!=null)glyph.setCallback(null);
      glyph=top;
      if(glyph!=null)glyph.setCallback(this);
      super.setCompoundDrawables(left,null,right,bottom);
      requestLayout();invalidate();
    }
    @Override protected boolean verifyDrawable(android.graphics.drawable.Drawable value){return value==glyph||super.verifyDrawable(value);}
    @Override protected void onMeasure(int w,int h){
      super.onMeasure(w,h);
      int lines=getLayout()==null?1:getLayout().getLineCount();
      int desired=Math.max(dp(64),Math.round(dp(36)+lines*getTextSize()*16/12));
      setMeasuredDimension(getMeasuredWidth(),resolveSizeAndState(desired,h,0));
    }
    @Override protected void onDraw(Canvas canvas){
      android.text.Layout layout=getLayout();if(layout==null)return;
      float lineHeight=getTextSize()*16/12;
      float top=(getHeight()-dp(28)-layout.getLineCount()*lineHeight)/2f;
      if(running){
        int saved=canvas.save();canvas.translate((getWidth()-dp(24))/2f,top);
        StatusView.glyph(canvas,badge,StatusView.Symbol.RUNNING,0,0,dp(24),getCurrentTextColor(),android.animation.ValueAnimator.areAnimatorsEnabled()?(android.os.SystemClock.uptimeMillis()%1000)*.36f:0);
        canvas.restoreToCount(saved);
        if(isShown()&&android.animation.ValueAnimator.areAnimatorsEnabled())postInvalidateDelayed(50);
      }else if(glyph!=null){
        glyph.setTint(getCurrentTextColor());
        int left=(getWidth()-dp(24))/2;
        glyph.setBounds(left,Math.round(top),left+dp(24),Math.round(top)+dp(24));
        glyph.draw(canvas);
      }
      android.text.TextPaint paint=getPaint();paint.setColor(getCurrentTextColor());
      float baseline=top+dp(28)+(lineHeight-paint.ascent()-paint.descent())/2;
      for(int i=0;i<layout.getLineCount();i++){
        String line=getText().subSequence(layout.getLineStart(i),layout.getLineEnd(i)).toString().replace("\n","");
        canvas.drawText(line,(getWidth()-paint.measureText(line))/2,baseline+i*lineHeight,paint);
      }
      if(saveFailed){
        badge.setStyle(Paint.Style.FILL);badge.setColor(Color.BLACK);
        canvas.drawCircle(getWidth()/2f+dp(13),dp(11),dp(9),badge);
        StatusView.glyph(canvas,badge,StatusView.Symbol.FAILED,getWidth()/2f+dp(5),dp(3),dp(16),0xffff8a7a,0);
      }
    }
  }

  Button action(String icon,String label,String spoken,Runnable action) {
    Button b = button(getContext(),icon,label,spoken,action);
    DockButton cell=(DockButton)b;
    if("拡大".equals(icon))cell.widthLabels=new String[]{"拡大","全体"};
    else if("編集".equals(icon))cell.widthLabels=new String[]{"編集","中止"};
    else if("タグ".equals(icon)&&label.startsWith("タグを"))cell.widthLabels=new String[]{"タグを付ける","タグを外す"};
    dock.addView(b,new ViewGroup.LayoutParams(-2,-2));
    return b;
  }
  static void tagState(Button button,boolean attached,boolean busy,boolean failed){
    Context c=button.getContext();
    android.graphics.drawable.Drawable tag=c.getDrawable(attached?R.drawable.ic_viewer_untag:R.drawable.ic_viewer_tag).mutate();
    android.graphics.drawable.Drawable state=new android.graphics.drawable.Drawable(){
      private final Paint p=new Paint(3);
      public void draw(Canvas canvas){int save=canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);
        if(busy){StatusView.glyph(canvas,p,StatusView.Symbol.RUNNING,0,0,24,button.getCurrentTextColor(),android.animation.ValueAnimator.areAnimatorsEnabled()?(android.os.SystemClock.uptimeMillis()%1000)*.36f:0);if(button.isShown()&&android.animation.ValueAnimator.areAnimatorsEnabled())button.postInvalidateDelayed(50);}
        else{tag.setTint(button.getCurrentTextColor());tag.setBounds(0,0,24,24);tag.draw(canvas);}
        canvas.restoreToCount(save);
      }
      public void setAlpha(int value){}public void setColorFilter(ColorFilter value){}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    };
    ((DockButton)button).saveFailed=failed;
    state.setBounds(0,0,GalleryUi.dp(c,24),GalleryUi.dp(c,24));button.setCompoundDrawables(null,state,null,null);button.invalidate();
  }
  static void editState(Button button,boolean running,boolean failed,String reason) {
    DockButton cell=(DockButton)button;cell.running=running;cell.saveFailed=failed;
    button.setText(running?"中止":"編集");
    button.setContentDescription(running?"編集の準備をしています。押すと中止":"編集。端末の写真編集アプリで開きます"+(failed?"。"+reason+"。もう一度押すと再試行":""));
    button.setStateDescription(running?"準備中":failed?"失敗、再試行できます":null);
    icon(button,"編集");button.invalidate();
  }

  /** At most two balanced rows; the last row keeps the same cell width and is centered. */
  private static final class ViewerDock extends ViewGroup {
    private int columns=1,rowHeight;
    ViewerDock(Context c){super(c);}
    protected void onMeasure(int ws,int hs){
      int width=MeasureSpec.getSize(ws),count=getChildCount();
      int need=GalleryUi.dp(getContext(),48);
      for(int i=0;i<count;i++){
        DockButton button=(DockButton)getChildAt(i);
        String[] labels=button.widthLabels==null?new String[]{button.getText().toString()}:button.widthLabels;
        for(String label:labels)need=Math.max(need,(int)Math.ceil(android.text.Layout.getDesiredWidth(label,button.getPaint()))+button.getPaddingLeft()+button.getPaddingRight());
      }
      columns=count==0?1:width/count>=need?count:(count+1)/2;
      int cell=width/columns;rowHeight=0;
      for(int i=0;i<count;i++){
        getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cell,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
        rowHeight=Math.max(rowHeight,getChildAt(i).getMeasuredHeight());
      }
      setMeasuredDimension(width,resolveSize(rowHeight*((count+columns-1)/columns),hs));
    }
    protected void onLayout(boolean changed,int l,int t,int r,int b){
      int count=getChildCount(),cell=getWidth()/columns;
      for(int i=0;i<count;i++){
        int row=i/columns,col=i%columns,inRow=Math.min(columns,count-row*columns);
        int x=(getWidth()-cell*inRow)/2+col*cell,y=row*rowHeight;
        getChildAt(i).layout(x,y,x+cell,y+rowHeight);
      }
    }
  }

  void zoom() {
    zoom = action("拡大","拡大","拡大",image::toggleZoom);
    zoom.setAccessibilityDelegate(new View.AccessibilityDelegate(){
      public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){
        super.onInitializeAccessibilityNodeInfo(host,info);
        info.setClassName("android.widget.ToggleButton");info.setCheckable(true);info.setChecked(host.isSelected());
      }
    });
  }
  void choose(Runnable selected) {
    Context c=getContext();
    Button choose = GalleryUi.button(c,"この写真を選ぶ",selected);
    choose.setCompoundDrawables(null,null,null,null);choose.setTextSize(15);
    android.text.SpannableString label=new android.text.SpannableString(" この写真を選ぶ");
    label.setSpan(new android.text.style.ReplacementSpan(){
      public int getSize(Paint p,CharSequence s,int start,int end,Paint.FontMetricsInt fm){return dp(28);}
      public void draw(Canvas canvas,CharSequence s,int start,int end,float x,int top,int y,int bottom,Paint p){int saved=canvas.save();canvas.translate(x,y+(p.ascent()+p.descent())/2-dp(10));canvas.scale(dp(20)/24f,dp(20)/24f);Paint stroke=new Paint(3);stroke.setColor(0xff111111);stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(1.8f);stroke.setStrokeCap(Paint.Cap.ROUND);stroke.setStrokeJoin(Paint.Join.ROUND);Path tick=new Path();tick.moveTo(4,12);tick.lineTo(9,17);tick.lineTo(20,6);canvas.drawPath(tick,stroke);canvas.restoreToCount(saved);}
    },0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);choose.setText(label);
    choose.setTextColor(0xff111111);choose.setBackground(GalleryUi.shape(c,GalleryUi.TEXT,26));
    choose.setMinHeight(dp(52));choose.setMinimumHeight(dp(52));choose.setPadding(dp(16),dp(6),dp(16),dp(6));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
    p.setMargins(dp(16),0,dp(16),dp(8)); footer.addView(choose,0,p);
  }

  protected void onMeasure(int widthSpec,int heightSpec) {
    int width=MeasureSpec.getSize(widthSpec),height=MeasureSpec.getSize(heightSpec);
    date.measure(MeasureSpec.makeMeasureSpec(Math.max(0,width-dp(32)),MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
    footer.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.AT_MOST));
    int top=Math.max(dp(72),date.getMeasuredHeight()+dp(8));
    int bottom=Math.max(dp(100),footer.getMeasuredHeight()+dp(16));
    stage.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(0,height-top-bottom),MeasureSpec.EXACTLY));
    setMeasuredDimension(width,height);
  }
  protected void onLayout(boolean changed,int l,int t,int r,int b) {
    int width=getWidth(),height=getHeight();
    int dateBottom=Math.max(dp(64),date.getMeasuredHeight());
    int top=Math.max(dp(72),dateBottom+dp(8));
    date.layout((width-date.getMeasuredWidth())/2,dateBottom-date.getMeasuredHeight(),(width+date.getMeasuredWidth())/2,dateBottom);
    stage.layout(0,top,width,top+stage.getMeasuredHeight());
    footer.layout(0,height-footer.getMeasuredHeight(),width,height);
  }
}
