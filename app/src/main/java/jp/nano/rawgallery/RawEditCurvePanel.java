package jp.nano.rawgallery;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import java.util.Locale;

/** Photo-overlay point editor. Touch gestures, keys, numeric fields and AX share the same model. */
final class RawEditCurvePanel extends FrameLayout {
  interface Listener {void begin();void changed(RawEditState s);default void changed(RawEditState s,long editNs){changed(s);}void end(boolean cancel);void undo();void redo();}
  private static final int INK=0xffededea,MUTED=0xffa9a9a4;
  private static final String[] NAMES={"明度","RGB","R","G","B"};
  private static final int[] COLORS={INK,0xffc9c9c4,0xffff7a6b,0xff6fd08a,0xff7fb0ff};
  private static final int ADD=0x7f0f6301,DELETE=0x7f0f6302,X_MINUS=0x7f0f6303,X_PLUS=0x7f0f6304,Y_MINUS=0x7f0f6305,Y_PLUS=0x7f0f6306;
  private final Listener listener;private RawEditState state;private int bank,selected=-1;
  private int styledBank=-1,editedFlags=-1;private float displayedRoll=Float.NaN;
  private RawEditCurve draftCurve;private int draftBank=-1,draftPoint=-1;
  private boolean undoAvailable,redoAvailable,updating,multi,gesture,inputDirty,outputDirty;
  private final Button[] banks=new Button[5];private final Glyph reset,undo,redo,delete;
  private final TextView inLabel,outLabel,rollLabel;private final EditText input,output;private final SeekBar roll;
  private Button developmentTarget,lookTarget;
  private RawEditLookPanel.Toggle lookTone;
  private boolean targetVisible,lookMode,toneEnabled=true;
  private java.util.function.Consumer<Boolean> selectTarget,toneToggle;
  final Graph graph;
  RawEditCurvePanel(Context context,RawEditState state,int bank,Listener listener){
    super(context);this.state=state;this.bank=Math.max(0,Math.min(4,bank));this.listener=listener;
    setMotionEventSplittingEnabled(false);setClipChildren(false);setClipToPadding(false);setClickable(true);setBackground(background(0xb8101012,16,0x14ededea));
    for(int i=0;i<5;i++){final int k=i;banks[i]=new Button(context){private final Paint dot=new Paint(3);protected void onDraw(Canvas c){super.onDraw(c);if(!RawEditCurvePanel.this.state.curve(k).identity()){dot.setColor(k==RawEditCurvePanel.this.bank?0xff111111:COLORS[k]);c.drawCircle(getWidth()-dp(8.5f),dp(10.5f),dp(2.5f),dot);}}};Button b=banks[i];b.setText(NAMES[i]);b.setAllCaps(false);b.setTextSize(13);b.setPadding(0,0,0,0);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);b.setIncludeFontPadding(false);b.setOnClickListener(v->{finishFields();this.bank=k;selected=-1;refresh();});addView(b);}
    reset=new Glyph(3,"選択中のカーブをリセット",()->edit(RawEditCurvePanel.this.state.withCurve(this.bank,new RawEditCurve())));addView(reset);
    graph=new Graph(context);addView(graph);
    inLabel=label("入力",12);outLabel=label("出力",12);rollLabel=label("ロールオフ",12);addView(inLabel);addView(outLabel);addView(rollLabel);
    input=field("入力");output=field("出力");addView(input);addView(output);
    undo=new Glyph(0,"元に戻す",listener::undo);redo=new Glyph(1,"やり直す",listener::redo);delete=new Glyph(2,"選択中の点を削除",this::delete);addView(undo);addView(redo);addView(delete);
    roll=new RollSlider(context);roll.setMax(1000);roll.setPadding(0,0,0,0);roll.setProgressTintList(ColorStateList.valueOf(INK));roll.setProgressBackgroundTintList(ColorStateList.valueOf(0xff414146));roll.setThumbTintList(ColorStateList.valueOf(INK));
    GradientDrawable thumb=background(INK,8,0);thumb.setSize(dp(16),dp(16));roll.setThumb(thumb);roll.setContentDescription("ハイライトのロールオフ。失ったRAWの情報は復元しません");
    roll.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){if(user&&!updating)listener.changed(RawEditCurvePanel.this.state.with(RawEditState.ROLLOFF,p/1000f));}public void onStartTrackingTouch(SeekBar b){beginGesture();}public void onStopTrackingTouch(SeekBar b){endGesture(false);}});addView(roll);refresh();
  }
  /** Optional research target selector; absence preserves the original panel layout. */
  void setTarget(boolean lookMode,boolean toneEnabled,java.util.function.Consumer<Boolean> selectTarget,java.util.function.Consumer<Boolean> toneToggle){
    this.lookMode=lookMode;this.toneEnabled=toneEnabled;this.selectTarget=selectTarget;this.toneToggle=toneToggle;
    if(!targetVisible){
      targetVisible=true;developmentTarget=targetButton("現像",false);lookTarget=targetButton("ルック",true);addView(developmentTarget);addView(lookTarget);
      lookTone=new RawEditLookPanel.Toggle(getContext(),"トーン","ルックのトーン、ルックにはロールオフなし",()->{finishFields();if(this.toneToggle!=null)this.toneToggle.accept(!this.toneEnabled);});lookTone.setTag("curve-look-tone");addView(lookTone);
    }
    developmentTarget.setSelected(!lookMode);lookTarget.setSelected(lookMode);
    developmentTarget.setTextColor(!lookMode?0xff111111:INK);lookTarget.setTextColor(lookMode?0xff111111:INK);
    developmentTarget.setBackground(targetBackground(!lookMode,true));lookTarget.setBackground(targetBackground(lookMode,false));
    developmentTarget.setContentDescription("現像のカーブを編集"+(!lookMode?"、選択中":""));lookTarget.setContentDescription("ルックのカーブを編集"+(lookMode?"、選択中":""));
    rollLabel.setVisibility(lookMode?GONE:VISIBLE);roll.setVisibility(lookMode?GONE:VISIBLE);lookTone.setVisibility(lookMode?VISIBLE:GONE);lookTone.on=toneEnabled;lookTone.setStateDescription(toneEnabled?"オン":"オフ");
    developmentTarget.setEnabled(isEnabled());lookTarget.setEnabled(isEnabled());lookTone.setEnabled(isEnabled());lookTone.invalidate();graph.invalidate();requestLayout();
  }
  private Button targetButton(String title,boolean look){Button b=new Button(getContext());b.setText(title);b.setTag(look?"curve-target-look":"curve-target-development");b.setAllCaps(false);b.setTextSize(13);b.setLetterSpacing(0);b.setTextColor(INK);b.setIncludeFontPadding(false);b.setPadding(dp(8),0,dp(8),0);b.setMinWidth(dp(48));b.setMinimumWidth(dp(48));b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setAccessibilityDelegate(new View.AccessibilityDelegate(){public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.RadioButton");info.setCheckable(true);info.setChecked(host.isSelected());}});b.setOnClickListener(v->{finishFields();cancelGesture();if(selectTarget!=null)selectTarget.accept(look);});return b;}
  private GradientDrawable targetBackground(boolean selected,boolean left){GradientDrawable d=background(selected?INK:Color.TRANSPARENT,0,selected?INK:0xff3a3a3f);float r=dp(24);d.setCornerRadii(left?new float[]{r,r,0,0,0,0,r,r}:new float[]{0,0,r,r,r,r,0,0});return d;}
  private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
  private GradientDrawable background(int color,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
  private TextView label(String name,int size){TextView t=new TextView(getContext());t.setText(name);t.setTextSize(size);t.setTextColor(MUTED);t.setGravity(Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(false);return t;}
  private EditText field(String name){
    EditText f=new EditText(getContext());f.setSingleLine(true);f.setTextSize(14);f.setTextColor(INK);f.setHintTextColor(0xff77777b);f.setHint("—");f.setGravity(Gravity.CENTER);f.setPadding(0,0,0,0);f.setIncludeFontPadding(false);f.setBackground(background(0x38000000,8,0xff414146));f.setSelectAllOnFocus(true);f.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);f.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);f.setContentDescription("選択中の点の"+name+"、0から100");
    f.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){if(!updating){if(!inputDirty&&!outputDirty){draftCurve=RawEditCurvePanel.this.state.curve(RawEditCurvePanel.this.bank);draftBank=RawEditCurvePanel.this.bank;draftPoint=selected;}if(name.equals("入力"))inputDirty=true;else outputDirty=true;}}public void afterTextChanged(android.text.Editable e){}});
    f.setOnFocusChangeListener((v,focus)->{if(!focus&&!updating){commitFields();refresh();}});f.setOnEditorActionListener((v,action,event)->{commitFields();f.clearFocus();((android.view.inputmethod.InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(f.getWindowToken(),0);return true;});return f;
  }
  private static String number(float n){return String.format(Locale.ROOT,"%.1f",n*100).replaceAll("\\.0$","");}
  int bank(){return bank;}int selected(){return selected;}
  private void discardFields(){inputDirty=outputDirty=false;draftCurve=null;draftBank=draftPoint=-1;boolean old=updating;updating=true;input.clearFocus();output.clearFocus();updating=old;}
  void finishFields(){commitFields();updating=true;input.clearFocus();output.clearFocus();updating=false;}
  void select(int point){finishFields();selected=Math.max(0,Math.min(state.curve(bank).size()-1,point));refresh();}
  void update(RawEditState state,boolean undo,boolean redo){if((inputDirty||outputDirty)&&(draftBank!=bank||draftPoint!=selected||state.curve(draftBank)!=draftCurve))discardFields();this.state=state;undoAvailable=undo;redoAvailable=redo;if(selected>=state.curve(bank).size())selected=state.curve(bank).size()-1;refresh();}
  private void edit(RawEditState next){if(!isEnabled()||state.equals(next))return;listener.begin();listener.changed(next);listener.end(false);}
  private void delete(){if(selected>0&&selected<state.curve(bank).size()-1){edit(state.withCurve(bank,state.curve(bank).delete(selected)));selected=Math.min(selected,state.curve(bank).size()-1);refresh();}}
  private void commitFields(){
    if(updating||!isEnabled()||selected<0||!inputDirty&&!outputDirty)return;
    if(draftBank!=bank||draftPoint!=selected||state.curve(bank)!=draftCurve){discardFields();refresh();return;}
    try{RawEditCurve c=state.curve(bank);float x=inputDirty?Float.parseFloat(input.getText().toString())/100:c.x(selected),y=outputDirty?Float.parseFloat(output.getText().toString())/100:c.y(selected);inputDirty=outputDirty=false;edit(state.withCurve(bank,c.move(selected,x,y)));}
    catch(IllegalArgumentException ignored){inputDirty=outputDirty=false;announceForAccessibility("0から100の数値を入力してください");}refresh();
  }
  private void refresh(){
    if(graph==null)return;updating=true;RawEditCurve c=state.curve(bank);boolean has=selected>=0&&selected<c.size(),enabled=isEnabled();
    if(styledBank!=bank){for(int i=0;i<5;i++){Button b=banks[i];b.setTextColor(i==bank?0xff111111:COLORS[i]);b.setBackground(background(i==bank?INK:Color.TRANSPARENT,8,0));b.setSelected(i==bank);b.setTypeface(Typeface.create(Typeface.DEFAULT,i==bank?600:500,false));}styledBank=bank;}
    int flags=0;for(int i=0;i<5;i++){Button b=banks[i];b.setEnabled(enabled);if(!state.curve(i).identity())flags|=1<<i;}
    if(flags!=editedFlags){for(int i=0;i<5;i++){banks[i].setContentDescription(NAMES[i]+"のカーブを編集"+((flags&(1<<i))!=0?"、編集済み":""));banks[i].invalidate();}editedFlags=flags;}
    input.setEnabled(enabled&&has);output.setEnabled(enabled&&has);setDisplay(input,has?number(c.x(selected)):"");setDisplay(output,has?number(c.y(selected)):"");
    reset.setEnabled(enabled&&!c.identity());undo.setEnabled(enabled&&undoAvailable);redo.setEnabled(enabled&&redoAvailable);delete.setEnabled(enabled&&has&&selected>0&&selected<c.size()-1);
    graph.setEnabled(enabled);roll.setEnabled(enabled);if(targetVisible){developmentTarget.setEnabled(enabled);lookTarget.setEnabled(enabled);lookTone.setEnabled(enabled);}if(displayedRoll!=state.get(RawEditState.ROLLOFF)){displayedRoll=state.get(RawEditState.ROLLOFF);roll.setProgress(Math.round(displayedRoll*1000));roll.setStateDescription(number(displayedRoll));}updating=false;graph.invalidate();
  }
  private void setDisplay(EditText field,String text){if(!field.hasFocus()&&!field.getText().toString().equals(text))field.setText(text);}
  public void setEnabled(boolean enabled){super.setEnabled(enabled);if(!enabled)cancelGesture();refresh();}
  private void beginGesture(){if(!gesture){gesture=true;listener.begin();}}
  private void endGesture(boolean cancel){if(gesture){gesture=false;listener.end(cancel);}}
  void cancelGesture(){endGesture(true);if(graph!=null)graph.pointer=-1;}
  public boolean dispatchTouchEvent(MotionEvent e){
    if(!isEnabled())return true;int a=e.getActionMasked();if(a==MotionEvent.ACTION_DOWN)multi=false;
    if(e.getPointerCount()>1){if(!multi){multi=true;cancelGesture();MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);super.dispatchTouchEvent(cancel);cancel.recycle();}((RawEditActivity.PhotoNavigation)getParent()).curveNavigation(e);return true;}
    if(multi){((RawEditActivity.PhotoNavigation)getParent()).curveNavigation(e);if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL)multi=false;return true;}
    if(a==MotionEvent.ACTION_CANCEL)cancelGesture();return super.dispatchTouchEvent(e);
  }
  protected void onDetachedFromWindow(){cancelGesture();super.onDetachedFromWindow();}
  protected void onMeasure(int w,int h){int width=MeasureSpec.getSize(w);setMeasuredDimension(width,dp(264));int inner=width-dp(16),gw=inner-dp(160),cell=(inner-dp(48))/5;
    for(Button b:banks)b.measure(MeasureSpec.makeMeasureSpec(cell,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(dp(48),MeasureSpec.EXACTLY));
    measure(reset,48,48);graph.measure(MeasureSpec.makeMeasureSpec(Math.max(dp(48),gw),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(dp(144),MeasureSpec.EXACTLY));
    measureLabel(inLabel);measureLabel(outLabel);measure(input,64,48);measure(output,64,48);measure(undo,48,48);measure(redo,48,48);measure(delete,48,48);measureLabel(rollLabel);int targetWidth=0;if(targetVisible){measureTarget(developmentTarget);measureTarget(lookTarget);targetWidth=developmentTarget.getMeasuredWidth()+lookTarget.getMeasuredWidth();lookTone.measure(MeasureSpec.makeMeasureSpec(Math.max(dp(48),inner-targetWidth),MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(dp(48),MeasureSpec.EXACTLY));}roll.measure(MeasureSpec.makeMeasureSpec(Math.max(dp(48),inner-dp(targetVisible?16:18)-targetWidth-rollLabel.getMeasuredWidth()),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(dp(48),MeasureSpec.EXACTLY));}
  private void measureTarget(Button b){int width=Math.max(dp(48),(int)Math.ceil(b.getPaint().measureText(b.getText().toString()))+dp(16));b.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(dp(48),MeasureSpec.EXACTLY));}
  private void measureLabel(TextView v){v.measure(MeasureSpec.makeMeasureSpec((int)Math.ceil(v.getPaint().measureText(v.getText().toString())),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(dp(48),MeasureSpec.EXACTLY));}
  private void measure(View v,int w,int h){v.measure(MeasureSpec.makeMeasureSpec(dp(w),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(dp(h),MeasureSpec.EXACTLY));}
  private void place(View v,int x,int y){v.layout(x,y,x+v.getMeasuredWidth(),y+v.getMeasuredHeight());}
  protected void onLayout(boolean changed,int l,int t,int r,int b){int x=dp(8);for(Button v:banks){place(v,x,dp(8));x+=v.getMeasuredWidth();}place(reset,getWidth()-dp(56),dp(8));place(graph,dp(8),dp(60));int right=graph.getRight()+dp(8);place(inLabel,right,dp(60));place(input,right+inLabel.getMeasuredWidth()+dp(8),dp(60));place(outLabel,right,dp(108));place(output,right+outLabel.getMeasuredWidth()+dp(8),dp(108));place(undo,right,dp(156));place(redo,right+dp(52),dp(156));place(delete,right+dp(104),dp(156));if(targetVisible){place(developmentTarget,dp(8),dp(208));place(lookTarget,dp(8)+developmentTarget.getMeasuredWidth(),dp(208));if(lookMode)place(lookTone,getWidth()-dp(8)-lookTone.getMeasuredWidth(),dp(208));else{int left=dp(16)+developmentTarget.getMeasuredWidth()+lookTarget.getMeasuredWidth();place(rollLabel,left,dp(208));place(roll,left+rollLabel.getMeasuredWidth()+dp(8),dp(208));}}else{place(rollLabel,dp(12),dp(208));place(roll,dp(12)+rollLabel.getMeasuredWidth()+dp(10),dp(208));}}
  private final class RollSlider extends SeekBar {
    private final Paint paint=new Paint(3);private final RawEditPad.DoubleTapReset taps;
    RollSlider(Context c){super(c);taps=new RawEditPad.DoubleTapReset(c);}
    protected void onDraw(Canvas c){float y=getHeight()/2f,x=getWidth()*getProgress()/(float)getMax();paint.setAlpha(isEnabled()?255:115);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setColor(isEnabled()?0xff3a3a3f:0x733a3a3f);c.drawLine(0,y,getWidth(),y,paint);paint.setColor(isEnabled()?0xff6b6b70:0x736b6b70);c.drawLine(getWidth()/2f,y-dp(6),getWidth()/2f,y+dp(6),paint);paint.setStyle(Paint.Style.FILL);paint.setColor(isEnabled()?INK:0x73ededea);c.drawCircle(x,y,dp(8),paint);if(isFocused()){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));c.drawRoundRect(1,1,getWidth()-1,getHeight()-1,dp(4),dp(4),paint);}}
    public boolean onTouchEvent(MotionEvent e){if(!isEnabled())return false;if(taps.completed(e)){listener.changed(RawEditCurvePanel.this.state.with(RawEditState.ROLLOFF,.5f));MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);super.onTouchEvent(cancel);cancel.recycle();return true;}return super.onTouchEvent(e);}
    public boolean onKeyDown(int key,KeyEvent e){if(isEnabled()&&(key==KeyEvent.KEYCODE_DEL||key==KeyEvent.KEYCODE_FORWARD_DEL)){edit(RawEditCurvePanel.this.state.with(RawEditState.ROLLOFF,.5f));return true;}return super.onKeyDown(key,e);}
    protected void onDetachedFromWindow(){taps.cancel();super.onDetachedFromWindow();}
  }
  private final class Glyph extends View {
    final int type;final Paint p=new Paint(3);Glyph(int type,String label,Runnable action){super(RawEditCurvePanel.this.getContext());this.type=type;setContentDescription(label);setFocusable(true);setOnClickListener(v->{discardFields();action.run();});}
    protected void onDraw(Canvas c){super.onDraw(c);c.save();c.translate((getWidth()-dp(20))/2f,(getHeight()-dp(20))/2f);c.scale(dp(20)/24f,dp(20)/24f);p.setColor(isEnabled()?INK:0xff626b72);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);Path path=new Path();
      if(type==3){c.drawRoundRect(4,4,20,20,2,2,p);c.drawLine(7,17,17,7,p);}else if(type==2){c.drawLine(5,7,19,7,p);path.moveTo(10,7);path.lineTo(10,5);path.lineTo(14,5);path.lineTo(14,7);path.moveTo(7,7);path.lineTo(8,19);path.lineTo(16,19);path.lineTo(17,7);c.drawPath(path,p);}else{if(type==1){c.translate(24,0);c.scale(-1,1);}path.moveTo(9,7);path.lineTo(5,11);path.lineTo(9,15);path.moveTo(5,11);path.lineTo(14,11);path.cubicTo(21,11,21,21,14,21);path.lineTo(12,21);c.drawPath(path,p);}c.restore();}
  }
  final class Graph extends View {
    final Paint paint=new Paint(3);int pointer=-1;float downX,downY,startX,startY;int focus=-1;RawEditCurve startCurve;boolean added;
    Graph(Context c){super(c);setFocusable(true);setFocusableInTouchMode(true);setClickable(true);setBackground(background(0x59000000,8,0));setContentDescription("トーンカーブ。点を選び、左右で入力、上下で出力。空白のタップで追加");}
    float sx(float x){return dp(8)+x*(getWidth()-dp(16));}float sy(float y){return dp(8)+(1-y)*(getHeight()-dp(16));}
    protected void onDraw(Canvas canvas){super.onDraw(canvas);RawEditCurve c=state.curve(bank);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(0x40ededea);canvas.drawRect(sx(0),sy(1),sx(1),sy(0),paint);paint.setColor(0x1fededea);for(float f:new float[]{.25f,.5f,.75f}){canvas.drawLine(sx(f),sy(0),sx(f),sy(1),paint);canvas.drawLine(sx(0),sy(f),sx(1),sy(f),paint);}paint.setColor(0x2eededea);paint.setPathEffect(new DashPathEffect(new float[]{dp(3),dp(3)},0));canvas.drawLine(sx(0),sy(0),sx(1),sy(1),paint);paint.setPathEffect(null);paint.setColor(COLORS[bank]);if(targetVisible&&lookMode&&!toneEnabled){paint.setAlpha(102);paint.setPathEffect(new DashPathEffect(new float[]{dp(4),dp(3)},0));}paint.setStrokeWidth(dp(2));Path line=new Path();for(int i=0;i<=192;i++){float x=i/192f;if(i==0)line.moveTo(sx(x),sy(c.at(x)));else line.lineTo(sx(x),sy(c.at(x)));}canvas.drawPath(line,paint);paint.setAlpha(255);paint.setPathEffect(null);
      for(int i=0;i<c.size();i++){float x=sx(c.x(i)),y=sy(c.y(i)),radius=dp(i==selected&&i>0&&i<c.size()-1?7:5);boolean end=i==0||i==c.size()-1;paint.setStyle(Paint.Style.FILL);paint.setColor(end?(i==0?0xff111111:INK):i==selected?COLORS[bank]:0xb30f0f10);if(end)canvas.drawRoundRect(x-radius,y-radius,x+radius,y+radius,dp(2),dp(2),paint);else canvas.drawCircle(x,y,radius,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(i==selected?2:1.5f));paint.setColor(i==selected?COLORS[bank]:end?(i==0?INK:0xff111111):COLORS[bank]);if(end)canvas.drawRoundRect(x-radius,y-radius,x+radius,y+radius,dp(2),dp(2),paint);else canvas.drawCircle(x,y,radius,paint);if(end&&i==selected){paint.setStrokeWidth(dp(1));canvas.drawCircle(x,y,dp(8),paint);}}
    }
    private int hit(float x,float y){RawEditCurve c=state.curve(bank);int best=-1;float distance=Float.MAX_VALUE;for(int i=0;i<c.size();i++){float dx=x-sx(c.x(i)),dy=y-sy(c.y(i)),d=dx*dx+dy*dy;if(Math.abs(dx)<=dp(24)&&Math.abs(dy)<=dp(24)&&d<distance){distance=d;best=i;}}return best;}
    public boolean onTouchEvent(MotionEvent e){if(!isEnabled())return false;int a=e.getActionMasked();if(a==MotionEvent.ACTION_DOWN){finishFields();requestFocus();beginGesture();pointer=e.getPointerId(0);downX=e.getX();downY=e.getY();startCurve=state.curve(bank);selected=hit(downX,downY);added=selected<0;if(selected>=0){startX=startCurve.x(selected);startY=startCurve.y(selected);}refresh();return true;}
      if(pointer<0)return true;int ix=e.findPointerIndex(pointer);if(ix<0){endGesture(true);pointer=-1;return true;}
      if(a==MotionEvent.ACTION_MOVE&&selected>=0){long editNs=android.os.SystemClock.elapsedRealtimeNanos();listener.changed(state.withCurve(bank,state.curve(bank).move(selected,startX+(e.getX(ix)-downX)/(getWidth()-dp(16)),startY-(e.getY(ix)-downY)/(getHeight()-dp(16)))),editNs);}
      if(a==MotionEvent.ACTION_UP){if(added&&Math.hypot(e.getX()-downX,e.getY()-downY)<ViewConfiguration.get(getContext()).getScaledTouchSlop()){
          float x=Math.max(0,Math.min(1,(downX-dp(8))/(getWidth()-dp(16)))),y=Math.max(0,Math.min(1,1-(downY-dp(8))/(getHeight()-dp(16))));RawEditCurve next=state.curve(bank).add(x,y);if(next.size()!=state.curve(bank).size()){listener.changed(state.withCurve(bank,next));selected=0;while(selected<next.size()-1&&next.x(selected)<x)selected++;}}
        pointer=-1;endGesture(false);performClick();refresh();}
      if(a==MotionEvent.ACTION_CANCEL){pointer=-1;endGesture(true);refresh();}return true;
    }
    public boolean performClick(){super.performClick();return true;}
    private void moveSelected(float dx,float dy){if(selected<0)selected=0;RawEditCurve c=state.curve(bank);edit(state.withCurve(bank,c.move(selected,c.x(selected)+dx,c.y(selected)+dy)));}
    private void addMiddle(){RawEditCurve c=state.curve(bank);int i=selected<0?0:Math.min(selected,c.size()-2);float x=(c.x(i)+c.x(i+1))/2;RawEditCurve next=c.add(x,c.at(x));if(next.size()!=c.size()){edit(state.withCurve(bank,next));selected=i+1;refresh();}}
    public boolean onKeyDown(int key,KeyEvent event){if(!isEnabled())return false;float step=event.isShiftPressed()?.1f:.01f;if(event.isCtrlPressed()&&key==KeyEvent.KEYCODE_Z){listener.undo();return true;}if(event.isCtrlPressed()&&key==KeyEvent.KEYCODE_Y){listener.redo();return true;}switch(key){case KeyEvent.KEYCODE_DPAD_LEFT:moveSelected(-step,0);break;case KeyEvent.KEYCODE_DPAD_RIGHT:moveSelected(step,0);break;case KeyEvent.KEYCODE_DPAD_UP:moveSelected(0,step);break;case KeyEvent.KEYCODE_DPAD_DOWN:moveSelected(0,-step);break;case KeyEvent.KEYCODE_PAGE_UP:select(selected-1);break;case KeyEvent.KEYCODE_PAGE_DOWN:select(selected+1);break;case KeyEvent.KEYCODE_MOVE_HOME:select(0);break;case KeyEvent.KEYCODE_MOVE_END:select(state.curve(bank).size()-1);break;case KeyEvent.KEYCODE_INSERT:addMiddle();break;case KeyEvent.KEYCODE_DEL:case KeyEvent.KEYCODE_FORWARD_DEL:delete();break;default:return super.onKeyDown(key,event);}return true;}
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.view.ViewGroup");for(int i=0;i<state.curve(bank).size();i++)info.addChild(this,i+1);info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ADD,"点を追加"));}
    public boolean performAccessibilityAction(int action,Bundle args){if(action==ADD&&isEnabled()){addMiddle();return true;}return super.performAccessibilityAction(action,args);}
    private void event(int id,int type){AccessibilityManager manager=(AccessibilityManager)getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);if(manager==null||!manager.isEnabled())return;AccessibilityEvent e=AccessibilityEvent.obtain(type);e.setSource(this,id);e.setPackageName(getContext().getPackageName());getParent().requestSendAccessibilityEvent(this,e);}
    public AccessibilityNodeProvider getAccessibilityNodeProvider(){return new AccessibilityNodeProvider(){
      public AccessibilityNodeInfo createAccessibilityNodeInfo(int id){if(id==View.NO_ID){AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain(Graph.this);onInitializeAccessibilityNodeInfo(n);return n;}RawEditCurve c=state.curve(bank);int i=id-1;if(i<0||i>=c.size())return null;
        AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain();n.setSource(Graph.this,id);n.setParent(Graph.this);n.setPackageName(getContext().getPackageName());n.setClassName("android.widget.SeekBar");n.setContentDescription(NAMES[bank]+"、点"+(i+1)+"、入力"+number(c.x(i))+"、出力"+number(c.y(i))+(i==0||i==c.size()-1?"、端点":""));n.setEnabled(isEnabled());n.setVisibleToUser(isShown());n.setFocusable(true);n.setAccessibilityFocused(focus==id);n.setClickable(true);n.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,0,100,c.y(i)*100));
        int x=Math.round(sx(c.x(i))),y=Math.round(sy(c.y(i)));Rect bounds=new Rect(Math.max(0,x-dp(24)),Math.max(0,y-dp(24)),Math.min(getWidth(),x+dp(24)),Math.min(getHeight(),y+dp(24)));n.setBoundsInParent(bounds);int[] origin=new int[2];getLocationOnScreen(origin);bounds.offset(origin[0],origin[1]);n.setBoundsInScreen(bounds);
        n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);n.addAction(new AccessibilityNodeInfo.AccessibilityAction(focus==id?AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,"点を選択"));
        int[] actions={X_MINUS,X_PLUS,Y_MINUS,Y_PLUS,ADD,DELETE};String[] labels={"入力を減らす","入力を増やす","出力を減らす","出力を増やす","次の区間に点を追加","この点を削除"};for(int j=0;j<actions.length;j++)if(actions[j]!=DELETE||i>0&&i<c.size()-1)n.addAction(new AccessibilityNodeInfo.AccessibilityAction(actions[j],labels[j]));return n;
      }
      public boolean performAction(int id,int action,Bundle args){int i=id-1;if(i<0||i>=state.curve(bank).size()||!isEnabled())return false;select(i);if(action==AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS){focus=id;event(id,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);return true;}if(action==AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS){focus=-1;event(id,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);return true;}if(action==AccessibilityNodeInfo.ACTION_CLICK)return true;
        if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()){if(args==null)return false;float value=args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,Float.NaN);if(!Float.isFinite(value))return false;RawEditCurve c=state.curve(bank);edit(state.withCurve(bank,c.move(i,c.x(i),value/100)));}
        else if(action==X_MINUS)moveSelected(-.01f,0);else if(action==X_PLUS)moveSelected(.01f,0);else if(action==Y_MINUS)moveSelected(0,-.01f);else if(action==Y_PLUS)moveSelected(0,.01f);else if(action==ADD)addMiddle();else if(action==DELETE)delete();else return false;event(Math.min(id,state.curve(bank).size()),AccessibilityEvent.TYPE_VIEW_SELECTED);return true;
      }
    };}
  }
}
