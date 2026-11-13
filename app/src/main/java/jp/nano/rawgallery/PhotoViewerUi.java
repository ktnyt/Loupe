package jp.nano.rawgallery;

import com.google.android.material.button.MaterialButton;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** The photo-first S08 layout; navigation and data changes belong to the activity. */
final class PhotoViewerUi extends ViewGroup {
  final PhotoRepository.Photo photo;
  ViewerLookUi lookUi;
  final FrameLayout stage;
  final ZoomImageView image;
  final LinearLayout footer;
  final ViewGroup dock;
  final TextView date;
  private final LinearLayout header;
  private final java.util.List<Button> secondary=new ArrayList<>();
  private com.google.android.material.bottomsheet.BottomSheetDialog actionSheet;
  private Button zoom;
  Runnable editFocusLost;
  private final View cloud, placeholder;
  final ShareHistoryUi.Overlay shared;
  private StatusView status;
  private boolean failed;

  PhotoViewerUi(Context context, PhotoRepository.Photo photo) {
    super(context);
    this.photo=photo;
    setBackgroundColor(Color.BLACK);
    date = PhotoListUi.text(context, date(photo, false), 15, 22, GalleryUi.TEXT);
    date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    date.setGravity(Gravity.CENTER);
    date.setContentDescription(date(photo, true));
    header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(4),0,dp(4),0);
    header.addView(LibraryUi.icon(context,R.drawable.ic_back,"写真一覧に戻る",this::goBack));
    header.addView(date,new LinearLayout.LayoutParams(0,-2,1));
    header.addView(LibraryUi.icon(context,R.drawable.ic_more,"写真のその他の操作",this::openActions));
    addView(header);
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
    shared=new ShareHistoryUi.Overlay(context,()->image.isZoomed()?null:photoRect(),12,4,false);
    stage.addView(shared,new FrameLayout.LayoutParams(-1,-1));
    addView(stage);
    footer = new LinearLayout(context);
    footer.setOrientation(LinearLayout.VERTICAL);
    footer.setPadding(dp(12), dp(6), dp(12), dp(10));
    footer.setBackgroundColor(Color.BLACK);
    dock = new ViewerDock(context);
    dock.setPadding(dp(6),dp(6),dp(6),dp(6));
    dock.setBackground(GalleryUi.shape(context,GalleryUi.PANEL,28));
    footer.addView(dock, new LinearLayout.LayoutParams(-1,-2));
    addView(footer);
    image.setZoomListener(expanded -> {
      if (zoom != null) {
        zoom.setText(expanded ? "全体" : "拡大");
        icon(zoom,expanded?"全体":"拡大");
        zoom.setContentDescription(expanded ? "全体を表示" : "拡大");
        zoom.setSelected(expanded);
        ((MaterialButton)zoom).setChecked(expanded);
        zoom.setStateDescription(expanded ? "拡大中" : "全体表示");
      }
      cloud.invalidate();
      shared.invalidate();
      refreshStatus();
    });
  }

  private void goBack(){
    Context context=getContext();
    while(context instanceof android.content.ContextWrapper&&!(context instanceof android.app.Activity))context=((android.content.ContextWrapper)context).getBaseContext();
    if(context instanceof android.app.Activity)((android.app.Activity)context).onBackPressed();
  }
  @Override protected void onDetachedFromWindow(){if(actionSheet!=null)actionSheet.dismiss();super.onDetachedFromWindow();}
  int actionCount(){return dock.getChildCount()+secondary.size()+(findViewWithTag("viewer-action-1位")==null?0:1);}
  Button actionButton(String icon){
    View attached=findViewWithTag("viewer-action-"+icon);if(attached instanceof Button)return (Button)attached;
    for(Button button:secondary)if(("viewer-action-"+icon).equals(button.getTag()))return button;
    return null;
  }

  @Override public void onWindowFocusChanged(boolean focused) {
    super.onWindowFocusChanged(focused);
    if(!focused && editFocusLost!=null)editFocusLost.run();
  }

  RectF photoRect(){RectF loaded=image.photoBounds();if(loaded!=null)return loaded;float width=stage.getWidth(),height=width*2/3f;if(height>stage.getHeight()){height=stage.getHeight();width=height*1.5f;}return new RectF((stage.getWidth()-width)/2,(stage.getHeight()-height)/2,(stage.getWidth()+width)/2,(stage.getHeight()+height)/2);}
  void status(StatusView view){status=view;stage.addView(view,new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.RIGHT));refreshStatus();}
  void refreshStatus(){shared.invalidate();if(status==null||status.getParent()!=stage)return;RectF bounds=photoRect();FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)status.getLayoutParams();int top=Math.round(Math.max(0,bounds.top))+dp(2),right=Math.round(Math.max(0,stage.getWidth()-bounds.right))+dp(status.photoRightInset());if(p.topMargin!=top||p.rightMargin!=right){p.topMargin=top;p.rightMargin=right;status.setLayoutParams(p);}}
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
      case "Nano Banana": resource=R.drawable.ic_nano_banana;break;
      case "タグ": resource=R.drawable.ic_viewer_tag;break;
      case "1位": resource=R.drawable.ic_viewer_best;break;
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
    Button b = new DockButton(c);MaterialUi.text(b);
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
    icon(b,icon);
    b.setCompoundDrawablePadding(GalleryUi.dp(c,4));
    return b;
  }

  /** Each column centers its own icon and label, including wrapped labels. */
  static final class DockButton extends MaterialButton {
    private android.graphics.drawable.Drawable glyph;
    private boolean saveFailed, running;
    String[] widthLabels;
    boolean compact,inline;
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
      if(compact){setMeasuredDimension(dp(48),dp(48));return;}
      if(inline){setMeasuredDimension(getMeasuredWidth(),resolveSizeAndState(Math.max(dp(52),getMeasuredHeight()),h,0));return;}
      int lines=getLayout()==null?1:getLayout().getLineCount();
      int desired=Math.max(dp(64),Math.round(dp(36)+lines*getTextSize()*16/12));
      setMeasuredDimension(getMeasuredWidth(),resolveSizeAndState(desired,h,0));
    }
    @Override protected void onDraw(Canvas canvas){
      android.text.Layout layout=getLayout();if(layout==null)return;
      if(compact||inline){
        float textWidth=compact?0:getPaint().measureText(getText().toString());
        int left=Math.round((getWidth()-dp(24)-(compact?0:dp(6)+textWidth))/2f),top=(getHeight()-dp(24))/2;
        if(!running&&glyph!=null){glyph.setTint(getCurrentTextColor());glyph.setBounds(left,top,left+dp(24),top+dp(24));glyph.draw(canvas);}
        if(!compact){getPaint().setColor(getCurrentTextColor());canvas.drawText(getText().toString(),left+dp(30),(getHeight()-getPaint().ascent()-getPaint().descent())/2,getPaint());}
        if(running){StatusView.glyph(canvas,badge,StatusView.Symbol.RUNNING,left,top,dp(24),getCurrentTextColor(),android.animation.ValueAnimator.areAnimatorsEnabled()?(android.os.SystemClock.uptimeMillis()%1000)*.36f:0);if(isShown())postInvalidateDelayed(80);}
        if(saveFailed)StatusView.glyph(canvas,badge,StatusView.Symbol.FAILED,getWidth()-dp(18),dp(2),dp(16),0xffff8a7a,0);
        return;
      }
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
    else if("編集".equals(icon))cell.widthLabels=new String[]{"手動","中止"};
    else if("タグ".equals(icon)&&label.startsWith("タグを"))cell.widthLabels=new String[]{"タグを付ける","タグを外す"};
    b.setTag("viewer-action-"+icon);
    if("1位".equals(icon)) {
      cell.compact=true;header.addView(b,header.getChildCount()-1,new LinearLayout.LayoutParams(dp(48),dp(48)));
    }else if("編集".equals(icon)||"Nano Banana".equals(icon)||"共有".equals(icon)) {
      cell.inline=true;b.setTextSize(14);b.setMinHeight(dp(52));b.setMinimumHeight(dp(52));
      if("編集".equals(icon)){b.setText("手動");MaterialUi.filled(b);cell.setCornerRadius(dp(22));}
      if("Nano Banana".equals(icon)){b.setText("AI");MaterialUi.filled(b);cell.setCornerRadius(dp(22));}
      if("Nano Banana".equals(icon))dock.addView(b,Math.min(1,dock.getChildCount()),new ViewGroup.LayoutParams(-2,-2));else dock.addView(b,new ViewGroup.LayoutParams(-2,-2));
    }else secondary.add(b);
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
  static void bestState(Button button,boolean chosen,boolean busy,boolean failed,boolean unreadable) {
    DockButton cell=(DockButton)button;
    cell.widthLabels=new String[]{"1位", "1位を解除"};
    cell.running=busy;cell.saveFailed=failed;
    cell.setCheckable(true);cell.setToggleCheckedStateOnClick(false);
    // MaterialButton's node initializer reads its native checked state.
    // Apply the committed value while enabled, then disable for pending I/O.
    cell.setEnabled(true);cell.setChecked(chosen);
    button.setText(chosen?"1位を解除":"1位");
    button.setEnabled(!busy);button.setSelected(chosen);
    button.setTextColor(chosen?0xfff5b947:GalleryUi.TEXT);
    button.setContentDescription(unreadable?"順位を読み込めませんでした。押すと再試行":(chosen?"1位を解除する":"1位にする")+(failed?"。保存できませんでした。押すと再試行":""));
    button.setStateDescription(busy?(unreadable?"読み込み中":"保存中"):unreadable?"読み込み失敗":failed?"保存失敗":chosen?"1位に指定済み":"未指定");
    if(chosen)icon(button,"1位");else{android.graphics.drawable.Drawable outline=button.getContext().getDrawable(R.drawable.ic_viewer_best_outline).mutate();outline.setBounds(0,0,GalleryUi.dp(button.getContext(),24),GalleryUi.dp(button.getContext(),24));button.setCompoundDrawables(null,outline,null,null);}
    button.invalidate();
  }
  static void editState(Button button,boolean running,boolean failed,String reason) {
    DockButton cell=(DockButton)button;cell.running=running;cell.saveFailed=failed;
    button.setText(running?"中止":"手動");
    button.setContentDescription(running?"編集の準備をしています。押すと中止":"編集。現像（実験）を開きます。長押しで外部のアプリで編集"+(failed?"。"+reason+"。もう一度押すと再試行":""));
    button.setStateDescription(running?"準備中":failed?"失敗、再試行できます":null);
    icon(button,"編集");button.invalidate();
  }

  /** Direct manual/AI actions share one group; sharing is spatially separated. */
  private static final class ViewerDock extends ViewGroup {
    ViewerDock(Context c){super(c);}
    protected void onMeasure(int ws,int hs){
      int width=MeasureSpec.getSize(ws),count=getChildCount(),available=width-getPaddingLeft()-getPaddingRight()-GalleryUi.dp(getContext(),count==3?10:8),height=0;
      for(int i=0;i<count;i++){
        View child=getChildAt(i);int cell=count==0?available:available/count;
        if(count==3&&i<2){MaterialButton edit=(MaterialButton)child;float outer=GalleryUi.dp(getContext(),22),inner=GalleryUi.dp(getContext(),4);edit.setShapeAppearanceModel(edit.getShapeAppearanceModel().toBuilder().setTopLeftCornerSize(i==0?outer:inner).setBottomLeftCornerSize(i==0?outer:inner).setTopRightCornerSize(i==0?inner:outer).setBottomRightCornerSize(i==0?inner:outer).build());}
        child.measure(MeasureSpec.makeMeasureSpec(cell,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));height=Math.max(height,child.getMeasuredHeight());
      }
      setMeasuredDimension(width,resolveSize(height+getPaddingTop()+getPaddingBottom(),hs));
    }
    protected void onLayout(boolean changed,int l,int t,int r,int b){
      int x=getPaddingLeft();for(int i=0;i<getChildCount();i++){View child=getChildAt(i);if(i>0)x+=GalleryUi.dp(getContext(),"viewer-action-共有".equals(child.getTag())?8:2);child.layout(x,getPaddingTop(),x+child.getMeasuredWidth(),getPaddingTop()+child.getMeasuredHeight());x+=child.getMeasuredWidth();}
    }
  }

  void openActions(){
    if(actionSheet!=null&&actionSheet.isShowing())return;
    Context c=getContext();actionSheet=new com.google.android.material.bottomsheet.BottomSheetDialog(c);
    LinearLayout list=new LinearLayout(c);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(dp(16),dp(12),dp(16),dp(20));
    list.addView(LibraryUi.toolbar(c,"写真の操作",LibraryUi.icon(c,R.drawable.ic_close,"閉じる",()->actionSheet.dismiss())));
    for(Button action:secondary){
      if(action.getParent() instanceof ViewGroup)((ViewGroup)action.getParent()).removeView(action);
      list.addView(action,new LinearLayout.LayoutParams(-1,-2));
    }
    ScrollView scroll=new ScrollView(c);scroll.addView(list);actionSheet.setContentView(scroll);MaterialUi.sheet(actionSheet);actionSheet.show();
  }

  void zoom() {
    zoom = action("拡大","拡大","拡大",image::toggleZoom);
    ((MaterialButton)zoom).setCheckable(true);((MaterialButton)zoom).setToggleCheckedStateOnClick(false);
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
    MaterialUi.filled(choose);
    choose.setMinHeight(dp(52));choose.setMinimumHeight(dp(52));choose.setPadding(dp(16),dp(6),dp(16),dp(6));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
    p.setMargins(dp(16),0,dp(16),dp(8)); footer.addView(choose,0,p);
  }

  protected void onMeasure(int widthSpec,int heightSpec) {
    int width=MeasureSpec.getSize(widthSpec),height=MeasureSpec.getSize(heightSpec);
    header.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
    footer.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.AT_MOST));
    int top=header.getMeasuredHeight()+(lookUi==null?0:dp(56));
    stage.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(0,height-top-footer.getMeasuredHeight()),MeasureSpec.EXACTLY));
    if(lookUi!=null)for(String tag:new String[]{"viewer-look","viewer-before"}){View v=findViewWithTag(tag);if(v!=null)v.measure(MeasureSpec.makeMeasureSpec(Math.max(0,width-dp(16)),MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(dp(48),MeasureSpec.EXACTLY));}
    setMeasuredDimension(width,height);
  }
  protected void onLayout(boolean changed,int l,int t,int r,int b) {
    int width=getWidth(),height=getHeight(),top=header.getMeasuredHeight();
    header.layout(0,0,width,top);
    if(lookUi!=null){View chip=findViewWithTag("viewer-look"),before=findViewWithTag("viewer-before");if(chip!=null)chip.layout(width-dp(8)-chip.getMeasuredWidth(),top,width-dp(8),top+dp(48));if(before!=null)before.layout(dp(8),top,dp(8)+before.getMeasuredWidth(),top+dp(48));top+=dp(56);}
    stage.layout(0,top,width,top+stage.getMeasuredHeight());
    footer.layout(0,height-footer.getMeasuredHeight(),width,height);
  }
}
