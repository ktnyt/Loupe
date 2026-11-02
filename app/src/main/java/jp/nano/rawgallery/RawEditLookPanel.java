package jp.nano.rawgallery;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

/** Scrollable research controls. The Activity owns recipes, imports, dialogs and persistence. */
final class RawEditLookPanel extends ScrollView {
  interface Listener {void changed(RawEditLook look);void begin();void end();void preset(int preset);void editCurves();void pickCube();void retryCube();void resetPreset();void close();}
  private static final int INK=0xffededea,MUTED=0xffa9a9a4;
  private static final String[] PRESETS={"Neutral","CNT-like（仮）","Custom"};
  private final Listener listener;
  private final LinearLayout body;
  private final Button[] presets=new Button[3];
  private final Toggle tone,lut,vignette,grain;
  private final Button edit,pick,retry,reset,close;
  private final TextView unsaved,lutStatus,retained;
  private final Slider lutStrength,vignetteStrength,grainStrength,grainShadows;
  private final EditText seed;
  private RawEditLook look=RawEditLook.neutral();
  private boolean updating,loading,seedDirty;
  private int basePreset;
  private RawEditCube retainedExternal;

  RawEditLookPanel(Context context,Listener listener){
    super(context);this.listener=listener;setFillViewport(false);setClipToPadding(false);setOverScrollMode(OVER_SCROLL_NEVER);setVerticalScrollBarEnabled(false);
    GradientDrawable bg=shape(0xff1c1c1f,0,0);float r=dp(28);bg.setCornerRadii(new float[]{r,r,r,r,0,0,0,0});setBackground(bg);setClipToOutline(true);
    body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(0,dp(12),0,dp(16));addView(body,new ScrollView.LayoutParams(-1,-2));
    View handle=new View(context);handle.setBackground(shape(0xff4a4a50,2,0));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(32),dp(4));hp.gravity=Gravity.CENTER_HORIZONTAL;hp.bottomMargin=dp(12);body.addView(handle,hp);
    LinearLayout heading=row(24,8);LinearLayout label=new LinearLayout(context);label.setGravity(Gravity.CENTER_VERTICAL);TextView title=text("ルック",20,INK);title.setTypeface(Typeface.create(Typeface.DEFAULT,600,false));label.addView(title);TextView research=text("研究用",12,MUTED);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-2,-2);rp.leftMargin=dp(8);label.addView(research,rp);heading.addView(label,new LinearLayout.LayoutParams(0,-2,1));
    unsaved=text("未保存",12,0xffffd48a);unsaved.setGravity(Gravity.CENTER);unsaved.setPadding(dp(8),dp(2),dp(8),dp(2));unsaved.setBackground(shape(0xff2a2a2e,10,0));LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(-2,-2);up.rightMargin=dp(8);heading.addView(unsaved,up);
    close=button("閉じる",listener::close);close.setTag("look-close");heading.addView(close,new LinearLayout.LayoutParams(-2,dp(48)));body.addView(heading);
    TextView note=text("Leica CNT の再現ではありません。仮説の CNT-like を比べるための研究用です。この画面の色は近似です。",12,MUTED);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.setMargins(dp(24),dp(4),dp(24),dp(8));body.addView(note,np);
    LinearLayout presetRow=row(16,16);for(int i=0;i<3;i++){final int p=i;Button b=button(PRESETS[i],()->{finishFields();listener.preset(p);});b.setTag("look-preset-"+i);b.setTextSize(13);b.setPadding(dp(6),dp(4),dp(6),dp(4));b.setContentDescription(PRESETS[i]+(i==0?"。現像の補正も中立に戻す、確認あり":i==1?"。追加のルックだけを置き換える":"。保持した手動調整に戻る"));b.setAccessibilityDelegate(new View.AccessibilityDelegate(){public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.RadioButton");info.setCheckable(true);info.setChecked(host.isSelected());}});presets[i]=b;LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(0,-2,1);if(i>0)pp.leftMargin=dp(4);presetRow.addView(b,pp);}body.addView(presetRow);
    separator();LinearLayout tr=row(8,8);tone=new Toggle(context,"トーン","ルックのトーン",()->change(look.withToneEnabled(!look.toneEnabled())));tone.setTag("look-tone");tr.addView(tone,new LinearLayout.LayoutParams(0,dp(48),1));edit=button("カーブを編集",()->{finishFields();listener.editCurves();});edit.setTag("look-edit-curves");tr.addView(edit,new LinearLayout.LayoutParams(-2,dp(48)));body.addView(tr);
    separator();LinearLayout lr=row(8,8);lut=new Toggle(context,"LUT","LUT",()->change(look.withCube(look.cube()==null?retainedExternal:look.cube()).withLutEnabled(!look.lutEnabled())));lut.setTag("look-lut");lr.addView(lut,new LinearLayout.LayoutParams(0,dp(48),1));pick=button(".cube を読み込む",()->{finishFields();listener.pickCube();});pick.setTag("look-pick");lr.addView(pick,new LinearLayout.LayoutParams(-2,dp(48)));body.addView(lr);
    lutStatus=text("未読込（.cube の 3D LUT）",12,MUTED);lutStatus.setMinHeight(dp(24));lutStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);addInset(lutStatus,16,0,16,0);
    retained=text("",12,MUTED);addInset(retained,16,0,16,0);
    retry=button("再読込",()->{finishFields();listener.retryCube();});retry.setTag("look-retry");addInset(retry,16,0,16,0);((LinearLayout.LayoutParams)retry.getLayoutParams()).width=-2;
    TextView space=text("sRGB で符号化した値用・2〜65・32MiB まで",12,MUTED);addInset(space,16,4,16,0);
    lutStrength=sliderRow("強度","LUTの強度","look-lut-strength",v->look.withLutStrength(v));
    separator();LinearLayout vr=row(8,8);vignette=new Toggle(context,"周辺光量","周辺光量",()->change(look.withVignetteEnabled(!look.vignetteEnabled())));vignette.setTag("look-vignette");vr.addView(vignette,new LinearLayout.LayoutParams(-2,dp(48)));body.addView(vr);
    vignetteStrength=sliderRow("強度","周辺光量の強度","look-vignette-strength",v->look.withVignetteStrength(v));
    separator();LinearLayout gr=row(8,8);grain=new Toggle(context,"粒子","粒子",()->change(look.withGrainEnabled(!look.grainEnabled())));grain.setTag("look-grain");gr.addView(grain,new LinearLayout.LayoutParams(-2,dp(48)));body.addView(gr);
    grainStrength=sliderRow("強度","粒子の強度","look-grain-strength",v->look.withGrainStrength(v));grainShadows=sliderRow("暗部寄り","粒子の暗部寄り","look-grain-shadows",v->look.withGrainShadows(v));
    LinearLayout sr=row(16,16);TextView sl=text("固定 seed",12,MUTED);sr.addView(sl,new LinearLayout.LayoutParams(-2,dp(48)));seed=new EditText(context);seed.setTag("look-seed");seed.setTextSize(14);seed.setTextColor(INK);seed.setIncludeFontPadding(false);seed.setSingleLine(true);seed.setGravity(Gravity.CENTER);seed.setPadding(dp(4),0,dp(4),0);seed.setSelectAllOnFocus(true);seed.setInputType(InputType.TYPE_CLASS_NUMBER);seed.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});seed.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);seed.setBackground(shape(0x59000000,8,0xff3a3a3f));seed.setContentDescription("粒子の固定 seed、0から999999");LinearLayout.LayoutParams se=new LinearLayout.LayoutParams(dp(96),dp(48));se.leftMargin=dp(8);sr.addView(seed,se);body.addView(sr);
    seed.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){if(!updating)seedDirty=true;}public void afterTextChanged(android.text.Editable e){}});
    seed.setOnFocusChangeListener((v,focus)->{if(!focus)commitSeed();});seed.setOnEditorActionListener((v,a,e)->{finishFields();((android.view.inputmethod.InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(seed.getWindowToken(),0);return true;});
    reset=button("Neutral へリセット",()->{finishFields();listener.resetPreset();});reset.setTag("look-reset");LinearLayout.LayoutParams re=new LinearLayout.LayoutParams(-1,dp(48));re.setMargins(dp(16),dp(12),dp(16),0);body.addView(reset,re);
    update(look,0,0,false,false,null,null);
  }
  /** Neutral confirmation content; the Activity owns the bottom-sheet window and Back behavior. */
  static View neutralConfirmation(Context context,Runnable confirm,Runnable cancel){
    float density=context.getResources().getDisplayMetrics().density,scale=context.getResources().getDisplayMetrics().scaledDensity;
    java.util.function.IntUnaryOperator dp=n->Math.round(n*density);
    ScrollView scroll=new ScrollView(context);scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);scroll.setOverScrollMode(OVER_SCROLL_NEVER);scroll.setClipToOutline(true);scroll.setAccessibilityPaneTitle("Neutral の確認");
    GradientDrawable background=new GradientDrawable();background.setColor(0xff1c1c1f);float radius=dp.applyAsInt(28);background.setCornerRadii(new float[]{radius,radius,radius,radius,0,0,0,0});scroll.setBackground(background);
    LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(0,dp.applyAsInt(12),0,dp.applyAsInt(20));scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
    View handle=new View(context);GradientDrawable grip=new GradientDrawable();grip.setColor(0xff4a4a50);grip.setCornerRadius(dp.applyAsInt(2));handle.setBackground(grip);LinearLayout.LayoutParams handleParams=new LinearLayout.LayoutParams(dp.applyAsInt(32),dp.applyAsInt(4));handleParams.gravity=Gravity.CENTER_HORIZONTAL;handleParams.bottomMargin=dp.applyAsInt(12);content.addView(handle,handleParams);
    TextView title=new TextView(context);title.setText("Neutral にしますか？");title.setTextSize(20);title.setTextColor(INK);title.setTypeface(Typeface.create(Typeface.DEFAULT,600,false));title.setIncludeFontPadding(false);title.setLineHeight(Math.round(28*scale));LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(-1,-2);titleParams.setMargins(dp.applyAsInt(24),0,dp.applyAsInt(24),dp.applyAsInt(4));content.addView(title,titleParams);
    TextView explanation=new TextView(context);explanation.setText("ルックと現像の補正を中立に戻します。この操作は元に戻せません。読み込んだLUTとCustomの調整は残ります。");explanation.setTextSize(14);explanation.setTextColor(INK);explanation.setIncludeFontPadding(false);explanation.setLineHeight(Math.round(21*scale));LinearLayout.LayoutParams bodyParams=new LinearLayout.LayoutParams(-1,-2);bodyParams.setMargins(dp.applyAsInt(24),dp.applyAsInt(4),dp.applyAsInt(24),0);content.addView(explanation,bodyParams);
    LinearLayout actions=new LinearLayout(context);actions.setOrientation(LinearLayout.VERTICAL);actions.setPadding(dp.applyAsInt(16),dp.applyAsInt(16),dp.applyAsInt(16),0);content.addView(actions,new LinearLayout.LayoutParams(-1,-2));
    Button positive=confirmationButton(context,"Neutral にする",true,confirm);positive.setTag("look-neutral-confirm");actions.addView(positive,new LinearLayout.LayoutParams(-1,-2));Button negative=confirmationButton(context,"キャンセル",false,cancel);negative.setTag("look-neutral-cancel");LinearLayout.LayoutParams negativeParams=new LinearLayout.LayoutParams(-1,-2);negativeParams.topMargin=dp.applyAsInt(8);actions.addView(negative,negativeParams);
    return scroll;
  }
  private static Button confirmationButton(Context context,String title,boolean positive,Runnable action){
    float density=context.getResources().getDisplayMetrics().density;Button button=new Button(context);button.setText(title);button.setAllCaps(false);button.setTextSize(positive?15:14);button.setLetterSpacing(0);button.setTypeface(Typeface.create(Typeface.DEFAULT,600,false));button.setIncludeFontPadding(false);button.setTextColor(positive?0xffff8a7a:0xff111111);button.setMinWidth(Math.round(48*density));button.setMinimumWidth(Math.round(48*density));button.setMinHeight(Math.round((positive?52:48)*density));button.setMinimumHeight(Math.round((positive?52:48)*density));button.setPadding(Math.round(16*density),Math.round(6*density),Math.round(16*density),Math.round(6*density));GradientDrawable shape=new GradientDrawable();shape.setColor(positive?Color.TRANSPARENT:INK);shape.setCornerRadius(Math.round((positive?26:24)*density));if(positive)shape.setStroke(Math.round(density),0xffff8a7a);button.setBackground(shape);button.setOnClickListener(v->action.run());return button;
  }
  private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
  private GradientDrawable shape(int color,float radius,int border){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(border!=0)d.setStroke(dp(1),border);return d;}
  private TextView text(String value,int size,int color){TextView t=new TextView(getContext());t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
  private LinearLayout row(int left,int right){LinearLayout r=new LinearLayout(getContext());r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(left),0,dp(right),0);r.setOrientation(LinearLayout.HORIZONTAL);return r;}
  private Button button(String value,Runnable action){Button b=new Button(getContext());b.setText(value);b.setAllCaps(false);b.setTextSize(14);b.setLetterSpacing(0);b.setTextColor(INK);b.setTypeface(Typeface.create(Typeface.DEFAULT,500,false));b.setIncludeFontPadding(false);b.setMinWidth(dp(48));b.setMinimumWidth(dp(48));b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(16),dp(4),dp(16),dp(4));b.setBackground(shape(Color.TRANSPARENT,24,0xff3a3a3f));b.setOnClickListener(v->action.run());return b;}
  private void addInset(View v,int left,int top,int right,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(left),dp(top),dp(right),dp(bottom));body.addView(v,p);}
  private void separator(){View line=new View(getContext());line.setBackgroundColor(0xff2a2a2e);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(1));p.setMargins(dp(16),dp(12),dp(16),dp(4));body.addView(line,p);}
  private interface Recipe {RawEditLook value(float progress);}
  private Slider sliderRow(String label,String description,String tag,Recipe recipe){LinearLayout row=row(16,16);TextView name=text(label,12,MUTED);row.addView(name,new LinearLayout.LayoutParams(-2,dp(48)));Slider s=new Slider(getContext(),description,recipe);s.setTag(tag);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(48),1);sp.leftMargin=dp(8);sp.rightMargin=dp(8);row.addView(s,sp);TextView number=text("0",13,INK);number.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);row.addView(number,new LinearLayout.LayoutParams(dp(32),dp(48)));s.number=number;body.addView(row);return s;}
  private void change(RawEditLook next){if(updating||loading||next.equals(look))return;listener.begin();listener.changed(next);listener.end();}
  private void commitSeed(){if(updating||!seedDirty)return;seedDirty=false;try{String value=seed.getText().toString();int n=Integer.parseInt(value);if(n<0||n>999999)throw new IllegalArgumentException();change(look.withSeed(n));}catch(IllegalArgumentException error){seed.announceForAccessibility("seed は0から999999の整数です");}updating=true;seed.setText(Integer.toString(look.seed()));updating=false;}
  void finishFields(){commitSeed();seed.clearFocus();}
  void update(RawEditLook look,int preset,int basePreset,boolean dirty,boolean loading,String status,RawEditCube retainedExternal){
    this.look=look;this.retainedExternal=retainedExternal;this.loading=loading;this.basePreset=Math.max(0,Math.min(1,basePreset));updating=true;
    unsaved.setVisibility(dirty?VISIBLE:GONE);
    for(int i=0;i<3;i++){boolean selected=i==preset;Button b=presets[i];b.setSelected(selected);b.setTypeface(Typeface.create(Typeface.DEFAULT,selected?600:500,false));b.setTextColor(selected?0xff111111:INK);b.setBackground(shape(selected?INK:Color.TRANSPARENT,12,selected?INK:0xff3a3a3f));b.setEnabled(!loading);}
    tone.on=look.toneEnabled();lut.on=look.lutEnabled();vignette.on=look.vignetteEnabled();grain.on=look.grainEnabled();tone.setEnabled(!loading);lut.setEnabled(!loading&&(look.cube()!=null||retainedExternal!=null));vignette.setEnabled(!loading);grain.setEnabled(!loading);tone.invalidate();lut.invalidate();vignette.invalidate();grain.invalidate();
    tone.setStateDescription(tone.on?"オン":"オフ");lut.setStateDescription(lut.on?"オン":"オフ");vignette.setStateDescription(vignette.on?"オン":"オフ");grain.setStateDescription(grain.on?"オン":"オフ");
    edit.setEnabled(!loading);pick.setEnabled(!loading);reset.setEnabled(!loading);seed.setEnabled(!loading);lutStrength.setEnabled(!loading);grainStrength.setEnabled(!loading);grainShadows.setEnabled(!loading);
    vignetteStrength.setEnabled(!loading);
    RawEditLook defaults=this.basePreset==1?RawEditLook.cntLike():RawEditLook.neutral();lutStrength.resetProgress=Math.round(defaults.lutStrength()*100);vignetteStrength.resetProgress=Math.round(defaults.vignetteStrength()*100);grainStrength.resetProgress=Math.round(defaults.grainStrength()*100);grainShadows.resetProgress=Math.round(defaults.grainShadows()*100);
    lutStrength.setValue(look.lutStrength());vignetteStrength.setValue(look.vignetteStrength());grainStrength.setValue(look.grainStrength());grainShadows.setValue(look.grainShadows());
    if(!seed.hasFocus()&&!seedDirty)seed.setText(Integer.toString(look.seed()));
    String current=look.cube()==null?"未読込（.cube の 3D LUT）":"使用中："+(RawEditLook.cntLike().cube().equals(look.cube())?"組込みの測定LUT（CNT）":look.cube().title())+"・"+look.cube().size()+"³";
    lutStatus.setText(loading?"読み込み中…":status!=null&&!status.isEmpty()?status:current);lutStatus.setTextColor(!loading&&status!=null&&!status.isEmpty()?0xffffb4a8:look.cube()==null?MUTED:INK);
    retained.setVisibility(retainedExternal==null?GONE:VISIBLE);if(retainedExternal!=null)retained.setText("読み込み済み："+retainedExternal.title()+"・"+retainedExternal.size()+"³（保持）");
    retry.setVisibility(!loading&&status!=null&&!status.isEmpty()?VISIBLE:GONE);retry.setEnabled(!loading);reset.setText(PRESETS[preset==2?this.basePreset:Math.max(0,Math.min(1,preset))]+" へリセット");
    updating=false;
  }
  static final class Toggle extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final String label;boolean on;
    Toggle(Context c,String label,String description,Runnable action){super(c);this.label=label;setContentDescription(description);setFocusable(true);setClickable(true);setOnClickListener(v->action.run());}
    protected void onMeasure(int width,int height){paint.setTextSize(14*getResources().getDisplayMetrics().scaledDensity);int desired=Math.round(paint.measureText(label))+dp(8+10+36+8);setMeasuredDimension(resolveSize(Math.max(dp(48),desired),width),resolveSize(dp(48),height));}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    protected void onDraw(Canvas c){super.onDraw(c);paint.setTypeface(Typeface.create(Typeface.DEFAULT,600,false));paint.setTextSize(14*getResources().getDisplayMetrics().scaledDensity);paint.setColor(isEnabled()?INK:0xff626b72);float x=dp(8),cy=getHeight()/2f;c.drawText(label,x,cy-(paint.ascent()+paint.descent())/2,paint);x+=paint.measureText(label)+dp(10);paint.setColor(on?(isEnabled()?INK:0xff4a4a50):0xff3a3a3f);c.drawRoundRect(x,cy-dp(10),x+dp(36),cy+dp(10),dp(10),dp(10),paint);paint.setColor(on?0xff111111:MUTED);c.drawCircle(x+dp(on?26:10),cy,dp(8),paint);if(isFocused()){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(INK);c.drawRoundRect(1,1,getWidth()-1,getHeight()-1,dp(8),dp(8),paint);paint.setStyle(Paint.Style.FILL);}}
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.Switch");info.setCheckable(true);info.setChecked(on);}
  }
  private final class Slider extends SeekBar {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Recipe recipe;private final RawEditPad.DoubleTapReset taps;TextView number;private boolean tracking;private int resetProgress;
    Slider(Context c,String description,Recipe recipe){super(c);this.recipe=recipe;taps=new RawEditPad.DoubleTapReset(c);setMax(100);setPadding(dp(8),0,dp(8),0);setContentDescription(description);setOnSeekBarChangeListener(new OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean user){if(number!=null)number.setText(Integer.toString(p));setStateDescription(Integer.toString(p));if(user&&!updating&&!loading){if(!tracking)listener.begin();listener.changed(recipe.value(p/100f));if(!tracking)listener.end();}}public void onStartTrackingTouch(SeekBar s){tracking=true;listener.begin();}public void onStopTrackingTouch(SeekBar s){tracking=false;listener.end();}});}
    public boolean onTouchEvent(MotionEvent event){if(!isEnabled())return false;if(taps.completed(event)){MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);super.onTouchEvent(cancel);cancel.recycle();tracking=false;change(recipe.value(resetProgress/100f));setProgress(resetProgress);return true;}return super.onTouchEvent(event);}
    protected void onDetachedFromWindow(){taps.cancel();super.onDetachedFromWindow();}
    void setValue(float value){setProgress(Math.round(value*100));number.setText(Integer.toString(getProgress()));setStateDescription(Integer.toString(getProgress()));}
    protected void onDraw(Canvas c){float cy=getHeight()/2f,left=dp(8),right=getWidth()-dp(8),x=left+(right-left)*getProgress()/getMax();paint.setAlpha(isEnabled()?255:110);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setColor(0xff3a3a3f);c.drawLine(left,cy,right,cy,paint);paint.setColor(0xff6b6b70);c.drawLine((left+right)/2,cy-dp(6),(left+right)/2,cy+dp(6),paint);paint.setStyle(Paint.Style.FILL);paint.setColor(INK);c.drawCircle(x,cy,dp(8),paint);if(isFocused()){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));c.drawRoundRect(1,1,getWidth()-1,getHeight()-1,dp(4),dp(4),paint);}}
  }
}
