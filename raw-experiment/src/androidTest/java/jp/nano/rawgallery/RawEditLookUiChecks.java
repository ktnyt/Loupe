package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Synthetic actual-UI checks. External document selection and physical finger/IME behavior are separate. */
final class RawEditLookUiChecks {
  private static final java.util.WeakHashMap<Window,Alignment> ALIGNMENTS=new java.util.WeakHashMap<>();
  private static final class Alignment {
    final Rect firstBefore;final int firstWindowY;Rect before,after,host;int totalDeltaY,lastDeltaY,windowY,updates;
    Alignment(Rect bounds,int y){firstBefore=new Rect(bounds);firstWindowY=y;}
    JSONObject json()throws JSONException {JSONObject value=new JSONObject();value.put("test_only",true);value.put("reason","constrained_activity_window_bottom_alignment");value.put("first_before_bounds_screen_px",rectJson(firstBefore));value.put("last_before_bounds_screen_px",rectJson(before));value.put("after_bounds_screen_px",rectJson(after));value.put("host_content_bounds_screen_px",rectJson(host));value.put("first_window_y_px",firstWindowY);value.put("window_y_px",windowY);value.put("last_fixture_delta_y_px",lastDeltaY);value.put("total_fixture_delta_y_px",totalDeltaY);value.put("updates",updates);return value;}
  }
  private interface Condition {boolean test();}
  private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  private static void main(Instrumentation t,Runnable action){Throwable[] failure={null};t.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure[0]=error;}});if(failure[0]!=null)throw new AssertionError(failure[0].getMessage(),failure[0]);}
  private static Object field(Object target,String name){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(ReflectiveOperationException error){throw new AssertionError(error);}}
  private static void setupField(Object target,String name,Object value){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}catch(ReflectiveOperationException error){throw new AssertionError(error);}}
  private static RawEditState state(RawEditActivity a){return (RawEditState)field(a,"state");}
  private static RawEditLookPanel sheet(RawEditActivity a){return (RawEditLookPanel)field(a,"lookPanel");}
  private static View tag(RawEditActivity a,String value){RawEditLookPanel panel=sheet(a);View view=panel==null?null:panel.findViewWithTag(value);if(view==null)view=a.getWindow().getDecorView().findViewWithTag(value);require(view!=null,"missing control "+value);return view;}
  private static View named(View root,String value){return RawEditCurveUiChecks.named(root,value);}
  private static <T> T find(View root,Class<T> type){return RawEditCurveUiChecks.find(root,type);}
  private static void await(Instrumentation t,Condition condition,String message)throws Exception {long deadline=SystemClock.uptimeMillis()+25000;boolean[] done={false};while(!done[0]&&SystemClock.uptimeMillis()<deadline){main(t,()->done[0]=condition.test());if(!done[0])Thread.sleep(30);}require(done[0],message);t.waitForIdleSync();}
  private static String renderDiagnostic(RawEditActivity a){
    RawEditFullSession full=(RawEditFullSession)field(a,"fullSession");
    return "ready="+field(a,"ready")+" full="+(full!=null)+" prepared="+(full!=null&&full.isPrepared())+" fullFailed="+(full!=null&&full.hasFailed())+" pending="+(field(a,"pendingFullPreview")!=null)+" swap="+field(a,"previewSwapQueued")+" queued="+((AtomicBoolean)field(a,"queued")).get()+" revision="+((AtomicLong)field(a,"revision")).get()+" rendered="+field(a,"rendered")+" visible="+field(a,"visible")+" dead="+field(a,"dead")+" loading="+field(a,"loading")+" interacting="+field(a,"interacting");
  }
  private static Rect hostContentBounds(RawEditActivity a){View root=((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);Rect bounds=screenBounds(root);bounds.left+=root.getPaddingLeft();bounds.top+=root.getPaddingTop();bounds.right-=root.getPaddingRight();bounds.bottom-=root.getPaddingBottom();return bounds;}
  private static void alignmentWait(Instrumentation t,Condition condition,String message)throws Exception {long deadline=SystemClock.uptimeMillis()+5000;boolean[] matched={false};while(!matched[0]&&SystemClock.uptimeMillis()<deadline){main(t,()->matched[0]=condition.test());if(!matched[0])Thread.sleep(30);}require(matched[0],message);t.waitForIdleSync();}
  private static void alignOwnedDialogs(Instrumentation t,RawEditActivity a,String stage)throws Exception {
    Dialog[][] owned={null};boolean[] constrained={false};main(t,()->{constrained[0]=a.getWindow().getAttributes().height>0;if(constrained[0])owned[0]=new Dialog[]{(Dialog)field(a,"lookDialog"),(Dialog)field(a,"neutralDialog")};});if(!constrained[0])return;
    for(Dialog dialog:owned[0]){
      if(dialog==null)continue;
      alignmentWait(t,()->dialog.isShowing()&&dialog.getWindow()!=null&&dialog.getWindow().getDecorView().getWidth()>0&&dialog.getWindow().getDecorView().getHeight()>0,"test-only dialog layout stage="+stage);
      Rect[] before={null},host={null};int[] delta={0},oldY={0};Window window=dialog.getWindow();
      main(t,()->{before[0]=screenBounds(window.getDecorView());host[0]=hostContentBounds(a);WindowManager.LayoutParams attributes=window.getAttributes();require((attributes.gravity&Gravity.VERTICAL_GRAVITY_MASK)==Gravity.BOTTOM,"test-only dialog must use BOTTOM gravity");delta[0]=before[0].bottom-host[0].bottom;oldY[0]=attributes.y;
        if(delta[0]!=0){WindowManager.LayoutParams adjusted=new WindowManager.LayoutParams();adjusted.copyFrom(attributes);adjusted.y+=delta[0];window.setAttributes(adjusted);}
      });
      String[] observed={null};try{alignmentWait(t,()->{Rect actual=screenBounds(window.getDecorView()),desired=hostContentBounds(a);observed[0]="actual="+actual.toShortString()+" desiredHost="+desired.toShortString()+" delta="+delta[0]+" windowY="+window.getAttributes().y;return Math.abs(actual.bottom-desired.bottom)<=1;},"test-only dialog alignment stage="+stage+" before="+before[0].toShortString()+" host="+host[0].toShortString()+" delta="+delta[0]);}catch(AssertionError failure){throw new AssertionError(failure.getMessage()+" "+observed[0],failure);}
      main(t,()->{Alignment record=ALIGNMENTS.get(window);if(record==null){record=new Alignment(before[0],oldY[0]);ALIGNMENTS.put(window,record);}record.before=new Rect(before[0]);record.after=screenBounds(window.getDecorView());record.host=hostContentBounds(a);record.lastDeltaY=delta[0];record.totalDeltaY+=delta[0];record.windowY=window.getAttributes().y;if(delta[0]!=0)record.updates++;});
      if(delta[0]!=0)android.util.Log.i("LoupeRawEdit","look.ui.fixture stage="+stage+" before="+before[0].toShortString()+" "+observed[0]);
    }
  }
  private static void ready(Instrumentation t,RawEditActivity a,String stage)throws Exception {
    long started=SystemClock.uptimeMillis();android.util.Log.i("LoupeRawEdit","look.ui.wait stage="+stage);
    try{await(t,()->{RawEditFullSession full=(RawEditFullSession)field(a,"fullSession");return (Boolean)field(a,"ready")&&full!=null&&full.isPrepared()&&field(a,"pendingFullPreview")==null&&!(Boolean)field(a,"previewSwapQueued")&&!((AtomicBoolean)field(a,"queued")).get()&&(Long)field(a,"rendered")==((AtomicLong)field(a,"revision")).get();},"current synthetic frame ready");}
    catch(AssertionError failure){String[] diagnostic={null};main(t,()->diagnostic[0]=renderDiagnostic(a));String message="stage="+stage+" "+failure.getMessage()+" "+diagnostic[0];android.util.Log.e("LoupeRawEdit","look.ui.wait FAIL "+message);throw new AssertionError(message,failure);}
    alignOwnedDialogs(t,a,"ready."+stage);
    android.util.Log.i("LoupeRawEdit","look.ui.wait complete stage="+stage+" ms="+(SystemClock.uptimeMillis()-started));
  }
  private static void click(Instrumentation t,RawEditActivity a,String value)throws Exception {alignOwnedDialogs(t,a,"click."+value);main(t,()->{View v=tag(a,value);require(v.isEnabled()&&v.performClick(),"enabled click "+value);});t.waitForIdleSync();}
  private static void show(Instrumentation t,RawEditActivity a)throws Exception {click(t,a,"look-open");await(t,()->sheet(a)!=null&&sheet(a).getWidth()>0,"look sheet laid out");alignOwnedDialogs(t,a,"show");}
  private static void progress(View view,float value){Bundle args=new Bundle();args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,value);require(view.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),args),"AX progress "+view.getTag());}
  private static void reveal(Instrumentation t,RawEditActivity a,String value)throws Exception {alignOwnedDialogs(t,a,"reveal."+value);main(t,()->{View v=tag(a,value);v.requestRectangleOnScreen(new Rect(0,0,v.getWidth(),v.getHeight()),true);});t.waitForIdleSync();await(t,()->{View v=tag(a,value);Rect visible=new Rect();return v.getGlobalVisibleRect(visible)&&visible.height()>=v.getHeight()-1&&visible.width()>=v.getWidth()-1;},"scroll reveals "+value);}
  private static void scrollTop(Instrumentation t,RawEditActivity a)throws Exception {main(t,()->sheet(a).scrollTo(0,0));t.waitForIdleSync();}
  private static String boundedText(CharSequence text){String value=text==null?"":text.toString().replace('\n',' ').replace('\r',' ').replace('\t',' ');return value.length()>64?value.substring(0,64):value;}
  private static CharSequence displayedText(TextView text){android.text.method.TransformationMethod transformation=text.getTransformationMethod();return transformation==null?text.getText():transformation.getTransformation(text.getText(),text);}
  private static void buttonDiagnostic(View view,StringBuilder result,int[] remaining){
    if(remaining[0]--<=0||result.length()>=768)return;
    if(view instanceof Button){Button button=(Button)view;Rect visible=new Rect();boolean onScreen=button.getGlobalVisibleRect(visible);result.append(" [text=").append(boundedText(button.getText())).append(" displayed=").append(boundedText(displayedText(button))).append(" id=").append(Integer.toHexString(button.getId())).append(" tag=").append(boundedText(String.valueOf(button.getTag()))).append(" enabled=").append(button.isEnabled()).append(" shown=").append(button.isShown()).append(" visible=").append(onScreen).append(" bounds=").append(visible.toShortString()).append(']');}
    if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount()&&remaining[0]>0&&result.length()<768;i++)buttonDiagnostic(((ViewGroup)view).getChildAt(i),result,remaining);
  }
  private static void dialogButton(Instrumentation t,RawEditActivity a,String label)throws Exception {
    alignOwnedDialogs(t,a,"neutral-confirmation");
    String target="キャンセル".equals(label)?"look-neutral-cancel":"look-neutral-confirm";long deadline=SystemClock.uptimeMillis()+5000;boolean[] clicked={false};String[] diagnostic={"dialog missing"};
    while(!clicked[0]&&SystemClock.uptimeMillis()<deadline){main(t,()->{Dialog dialog=(Dialog)field(a,"neutralDialog");if(dialog==null||!dialog.isShowing()||dialog.getWindow()==null){diagnostic[0]="dialog="+(dialog!=null)+" showing="+(dialog!=null&&dialog.isShowing());return;}View root=dialog.getWindow().getDecorView(),view=root.findViewWithTag(target);Rect visible=new Rect();boolean onScreen=view!=null&&view.getGlobalVisibleRect(visible)&&visible.width()>0&&visible.height()>0;
      if(view instanceof Button&&label.contentEquals(displayedText((Button)view))&&view.isEnabled()&&view.isClickable()&&view.isShown()&&onScreen){clicked[0]=view.performClick();return;}
      StringBuilder buttons=new StringBuilder("dialogShowing=true target="+target);buttonDiagnostic(root,buttons,new int[]{64});diagnostic[0]=buttons.toString();});if(!clicked[0])Thread.sleep(30);}
    if(!clicked[0])android.util.Log.e("LoupeRawEdit","look.ui.confirm FAIL "+diagnostic[0]);require(clicked[0],"Neutral confirmation button "+label+" "+diagnostic[0]);t.waitForIdleSync();
  }
  private static void sizes(RawEditActivity a){float density=a.getResources().getDisplayMetrics().density;String[] tags={"look-preset-0","look-preset-1","look-preset-2","look-tone","look-edit-curves","look-lut","look-pick","look-lut-strength","look-grain","look-grain-strength","look-grain-shadows","look-seed","look-reset","look-close"};for(String name:tags){View v=tag(a,name);require(v.getWidth()>=Math.round(48*density)-1&&v.getHeight()>=Math.round(48*density)-1,"48dp control "+name+" actual="+v.getWidth()/density+"x"+v.getHeight()/density);}textBounds(sheet(a));}
  private static void textBounds(View view){if(view.getVisibility()!=View.VISIBLE)return;if(view instanceof TextView){TextView text=(TextView)view;android.text.Layout layout=text.getLayout();if(layout!=null){require(layout.getHeight()<=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom()+2,"text height clipped: "+text.getText());for(int i=0;i<layout.getLineCount();i++)require(layout.getEllipsisCount(i)==0,"ellipsized text: "+text.getText());}}if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)textBounds(((ViewGroup)view).getChildAt(i));}
  private static void setSeed(Instrumentation t,RawEditActivity a,String value)throws Exception {reveal(t,a,"look-seed");main(t,()->{EditText seed=(EditText)tag(a,"look-seed");seed.requestFocus();seed.setText(value);seed.onEditorAction(EditorInfo.IME_ACTION_DONE);});t.waitForIdleSync();}
  private static void pointProgress(RawEditCurvePanel panel,int id,float value){Bundle args=new Bundle();args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,value);require(panel.graph.getAccessibilityNodeProvider().performAction(id,AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),args),"curve point AX progress");}
  private static void addPoint(RawEditCurvePanel panel){AccessibilityNodeInfo host=panel.graph.createAccessibilityNodeInfo();try{for(AccessibilityNodeInfo.AccessibilityAction action:host.getActionList())if("点を追加".contentEquals(action.getLabel()==null?"":action.getLabel())){require(panel.graph.performAccessibilityAction(action.getId(),null),"curve AX add");return;}}finally{host.recycle();}throw new AssertionError("curve AX add action missing");}
  static void run(Instrumentation t)throws Exception {
    RawEditActivity original=(RawEditActivity)t.startActivitySync(new Intent(t.getTargetContext(),RawEditActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("rawEditSynthetic",true).putExtra("rawEditSmallSynthetic",true));RawEditActivity[] live={original};
    try {
      ready(t,original,"main.initial");RawEditCube retained=RawEditCube.identity(17);
      show(t,original);main(t,()->sizes(original));capture(t,original,"neutral-default-sheet-top");reveal(t,original,"look-close");click(t,original,"look-close");
      main(t,()->{
        View brightness=named(original.getWindow().getDecorView(),"明るさ");require(brightness!=null&&brightness.performClick(),"brightness tab");
      });t.waitForIdleSync();
      main(t,()->{RawEditPad pad=find(original.getWindow().getDecorView(),RawEditPad.class);require(pad!=null,"exposure pad");progress(pad.getChildAt(2),1.25f);require(Math.abs(state(original).get(RawEditState.EXPOSURE)-1.25f)<.001,"actual exposure edit");named(original.getWindow().getDecorView(),"トーン").performClick();});t.waitForIdleSync();
      main(t,()->{RawEditCurvePanel panel=find(original.getWindow().getDecorView(),RawEditCurvePanel.class);addPoint(panel);pointProgress(panel,2,60);require(state(original).curve(0).size()==3,"existing development curve edited");});
      RawEditState[] developed={null};main(t,()->developed[0]=state(original));show(t,original);
      click(t,original,"look-preset-1");main(t,()->{require(state(original).look().equals(RawEditLook.cntLike()),"CNT-like preset recipe");require(state(original).get(RawEditState.EXPOSURE)==developed[0].get(RawEditState.EXPOSURE)&&state(original).curve(0).equals(developed[0].curve(0)),"CNT-like preserves existing development exposure/curves");sizes(original);});ready(t,original,"main.cnt-preset");capture(t,original,"cnt-sheet-top");
      // Fixture setup only, after reference-state captures: no external document/import coverage claimed.
      main(t,()->setupField(original,"retainedCube",retained));
      reveal(t,original,"look-grain");click(t,original,"look-grain");main(t,()->require((Integer)field(original,"lookPreset")==2&&!state(original).look().grainEnabled(),"grain toggle changes preset to Custom"));
      reveal(t,original,"look-grain-strength");main(t,()->progress(tag(original,"look-grain-strength"),67));reveal(t,original,"look-grain-shadows");main(t,()->progress(tag(original,"look-grain-shadows"),42));setSeed(t,original,"123456");reveal(t,original,"look-grain");click(t,original,"look-grain");
      RawEditLook[] custom={null};main(t,()->{custom[0]=state(original).look();require(custom[0].grainEnabled()&&custom[0].seed()==123456&&Math.abs(custom[0].grainStrength()-.67f)<1e-6&&Math.abs(custom[0].grainShadows()-.42f)<1e-6,"slider and seed actual model values");});reveal(t,original,"look-reset");main(t,()->sizes(original));capture(t,original,"custom-sheet-bottom");
      scrollTop(t,original);click(t,original,"look-preset-0");dialogButton(t,original,"キャンセル");main(t,()->require(state(original).look().equals(custom[0])&&state(original).get(RawEditState.EXPOSURE)==developed[0].get(RawEditState.EXPOSURE),"Neutral cancel preserves all edits"));
      click(t,original,"look-preset-0");dialogButton(t,original,"Neutral にする");main(t,()->require(state(original).equals(RawEditState.neutral())&&custom[0].equals(field(original,"customLook"))&&retained.equals(field(original,"retainedCube")),"confirmed Neutral clears development and keeps Custom/import"));ready(t,original,"main.neutral-confirmed");capture(t,original,"neutral-sheet-top");
      click(t,original,"look-preset-2");main(t,()->require(state(original).look().equals(custom[0])&&state(original).get(RawEditState.EXPOSURE)==0&&state(original).curve(0).identity(),"Custom restores manual look without resurrecting cleared development"));
      reveal(t,original,"look-edit-curves");click(t,original,"look-edit-curves");await(t,()->field(original,"curvePanel")!=null&&field(original,"lookDialog")==null,"look curve editor opened");
      RawEditState[] beforeCurve={null},curveEdit={null};main(t,()->{beforeCurve[0]=state(original);require((Boolean)field(original,"lookCurves"),"look curve target selected");RawEditCurvePanel panel=(RawEditCurvePanel)field(original,"curvePanel");pointProgress(panel,4,55);curveEdit[0]=state(original);require(Math.abs(curveEdit[0].look().curve(0).y(3)-.55f)<1e-5&&curveEdit[0].curve(0).identity(),"look curve edit independent of development");named(panel,"元に戻す").performClick();require(state(original).equals(beforeCurve[0]),"look curve actual Undo");named(panel,"やり直す").performClick();require(state(original).equals(curveEdit[0]),"look curve actual Redo");});
      click(t,original,"curve-target-development");main(t,()->{RawEditCurvePanel panel=(RawEditCurvePanel)field(original,"curvePanel");View red=named(panel,"Rのカーブを編集");require(red!=null&&red.performClick(),"development red bank");addPoint(panel);pointProgress(panel,2,57);require(state(original).curve(2).size()==3&&state(original).look().equals(curveEdit[0].look()),"development curve preserves look");named(panel,"元に戻す").performClick();named(panel,"元に戻す").performClick();require(state(original).equals(curveEdit[0]),"development curve Undo preserves look");});click(t,original,"curve-target-look");ready(t,original,"main.look-curves");capture(t,original,"look-curves");
      RawEditState[] expected={null},saved={null};RawEditLook[] remembered={null};main(t,()->{expected[0]=state(original);saved[0]=(RawEditState)field(original,"saved");remembered[0]=(RawEditLook)field(original,"customLook");});
      Instrumentation.ActivityMonitor monitor=t.addMonitor(RawEditActivity.class.getName(),null,false);main(t,original::recreate);RawEditActivity restored=(RawEditActivity)monitor.waitForActivityWithTimeout(20000);t.removeMonitor(monitor);require(restored!=null&&restored!=original,"actual Activity recreated");live[0]=restored;ready(t,restored,"main.recreated");
      main(t,()->{require(state(restored).equals(expected[0]),"recreate restores current look and development");require(saved[0].equals(field(restored,"saved"))&&remembered[0].equals(field(restored,"customLook"))&&retained.equals(field(restored,"retainedCube")),"recreate restores saved/custom/independently retained table");require(!(Boolean)field(restored,"lookRestoreFailed")&&field(restored,"lookToken")!=null,"private token restore succeeded");});
      show(t,restored);reveal(t,restored,"look-seed");main(t,()->require("123456".contentEquals(((EditText)tag(restored,"look-seed")).getText()),"restored seed field value"));reveal(t,restored,"look-reset");capture(t,restored,"restored-sheet-bottom");reveal(t,restored,"look-close");click(t,restored,"look-close");main(t,()->require(field(restored,"lookDialog")==null,"close control reachable and dismisses sheet"));
      android.util.Log.i("LoupeRawEdit","look.ui PASS preset/custom/Neutral cancel+confirm/AX sliders/seed/curve targets+Undo/recreate; external_document_picker=unverified; retained_cube_fixture=reflection_setup");
    }finally{main(t,()->{if(!live[0].isFinishing())live[0].finish();});}
    basePresetCases(t);
  }
  private static boolean sameDevelopment(RawEditState a,RawEditState b){if(!java.util.Arrays.equals(a.parameters(),b.parameters()))return false;for(int bank=0;bank<5;bank++)if(!a.curve(bank).equals(b.curve(bank)))return false;return true;}
  private static void prepareDevelopment(Instrumentation t,RawEditActivity a,int origin)throws Exception {
    main(t,()->require(named(a.getWindow().getDecorView(),"WB").performClick(),"WB tab for reset preservation"));t.waitForIdleSync();
    main(t,()->{RawEditPad pad=find(a.getWindow().getDecorView(),RawEditPad.class);progress(pad.getChildAt(2),.25f+origin*.1f);require(named(a.getWindow().getDecorView(),"トーン").performClick(),"development tone tab");});t.waitForIdleSync();
    click(t,a,"curve-target-development");main(t,()->{RawEditCurvePanel p=(RawEditCurvePanel)field(a,"curvePanel");require(named(p,"明度").performClick(),"development lightness bank");require(named(p,"選択中のカーブをリセット").performClick(),"clear development curve fixture");addPoint(p);pointProgress(p,2,60);require(state(a).curve(0).size()==3&&state(a).get(RawEditState.TEMP)>.2f,"WB and curve fixture applied through UI");});
  }
  private static RawEditActivity recreate(Instrumentation t,RawEditActivity a,String stage)throws Exception {
    Instrumentation.ActivityMonitor monitor=t.addMonitor(RawEditActivity.class.getName(),null,false);
    try{main(t,a::recreate);RawEditActivity restored=(RawEditActivity)monitor.waitForActivityWithTimeout(20000);require(restored!=null&&restored!=a,"base-origin Activity recreated");ready(t,restored,stage);return restored;}finally{t.removeMonitor(monitor);}
  }
  private static void basePresetCases(Instrumentation t)throws Exception {
    RawEditActivity[] live={(RawEditActivity)t.startActivitySync(new Intent(t.getTargetContext(),RawEditActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("rawEditSynthetic",true).putExtra("rawEditSmallSynthetic",true))};
    try {
      ready(t,live[0],"base.initial");
      for(int origin=0;origin<2;origin++){
        final int base=origin;RawEditActivity a=live[0];show(t,a);
        click(t,a,"look-preset-"+base);if(base==0)dialogButton(t,a,"Neutral にする");reveal(t,a,"look-close");click(t,a,"look-close");prepareDevelopment(t,a,base);show(t,a);
        main(t,()->require(state(a).look().basePreset()==base,"selected preset immutable origin"));
        if(base==0){reveal(t,a,"look-grain");click(t,a,"look-grain");}
        reveal(t,a,"look-grain-strength");main(t,()->progress(tag(a,"look-grain-strength"),61+base));setSeed(t,a,Integer.toString(234500+base));
        RawEditLook[] custom={null};main(t,()->{custom[0]=state(a).look();require(custom[0].basePreset()==base&&(Integer)field(a,"lookBase")==base,"Custom retains its actual source preset");});
        scrollTop(t,a);click(t,a,"look-preset-"+(1-base));if(base==1)dialogButton(t,a,"Neutral にする");click(t,a,"look-preset-2");
        main(t,()->require(state(a).look().equals(custom[0])&&(Integer)field(a,"lookBase")==base,"Custom origin survives visiting the other preset"));
        // Neutral selection deliberately clears all development; set it again before look-only reset.
        reveal(t,a,"look-close");click(t,a,"look-close");prepareDevelopment(t,a,base);show(t,a);
        RawEditState[] beforeReset={null};main(t,()->beforeReset[0]=state(a));reveal(t,a,"look-reset");click(t,a,"look-reset");
        main(t,()->require(sameDevelopment(beforeReset[0],state(a))&&state(a).look().equals(base==0?RawEditLook.neutral():RawEditLook.cntLike()),"look reset preserves WB/development curves and uses retained origin"));
        scrollTop(t,a);click(t,a,"look-preset-2");main(t,()->require(state(a).look().equals(custom[0]),"look-only reset keeps manual Custom values"));
        reveal(t,a,"look-edit-curves");click(t,a,"look-edit-curves");RawEditState[] beforeCurve={null},afterCurve={null};
        main(t,()->{RawEditCurvePanel p=(RawEditCurvePanel)field(a,"curvePanel");require(named(p,"明度").performClick(),"look lightness bank");beforeCurve[0]=state(a);pointProgress(p,1,1);afterCurve[0]=state(a);require(!afterCurve[0].equals(beforeCurve[0])&&afterCurve[0].look().basePreset()==base,"actual look curve edit preserves origin");named(p,"元に戻す").performClick();require(state(a).equals(beforeCurve[0])&&(Integer)field(a,"lookBase")==base,"Undo restores immutable origin");named(p,"やり直す").performClick();require(state(a).equals(afterCurve[0])&&(Integer)field(a,"lookBase")==base,"Redo restores immutable origin");});
        RawEditActivity restored=recreate(t,a,"base."+base+".recreated");live[0]=restored;
        main(t,()->require(state(restored).equals(afterCurve[0])&&state(restored).look().basePreset()==base&&((RawEditLook)field(restored,"customLook")).basePreset()==base&&(Integer)field(restored,"lookBase")==base,"recreated Custom retains reset origin and all edits"));
        show(t,restored);reveal(t,restored,"look-reset");main(t,()->{String label=((TextView)tag(restored,"look-reset")).getText().toString();require(label.startsWith(base==0?"Neutral":"CNT-like"),"restored reset label matches origin");});click(t,restored,"look-reset");
        main(t,()->require(sameDevelopment(afterCurve[0],state(restored))&&state(restored).look().equals(base==0?RawEditLook.neutral():RawEditLook.cntLike()),"recreated reset preserves development and correct defaults"));scrollTop(t,restored);click(t,restored,"look-preset-2");main(t,()->require(state(restored).look().equals(afterCurve[0].look()),"Custom after recreated reset retains manual edits"));reveal(t,restored,"look-close");click(t,restored,"look-close");
      }
      android.util.Log.i("LoupeRawEdit","look.ui.base PASS Neutral-origin/CNT-origin Custom across other presets, look-only reset preserves WB+curves, Undo/Redo/recreate retain origin");
    }finally{main(t,()->{if(!live[0].isFinishing())live[0].finish();});}
  }
  private static Bitmap copy(Instrumentation t,Window window)throws Exception {
    CountDownLatch frame=new CountDownLatch(1);main(t,()->{View decor=window.getDecorView();decor.getViewTreeObserver().registerFrameCommitCallback(frame::countDown);decor.invalidate();});require(frame.await(5,TimeUnit.SECONDS),"look capture frame committed");
    Bitmap[] image={null};int[] result={-1};CountDownLatch done=new CountDownLatch(1);main(t,()->{View decor=window.getDecorView();image[0]=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);PixelCopy.request(window,image[0],code->{result[0]=code;done.countDown();},new Handler(Looper.getMainLooper()));});require(done.await(5,TimeUnit.SECONDS)&&result[0]==PixelCopy.SUCCESS,"look PixelCopy window");return image[0];
  }
  private static JSONArray rectJson(Rect rect)throws JSONException{return new JSONArray(new int[]{rect.left,rect.top,rect.right,rect.bottom});}
  private static Rect screenBounds(View view){int[] origin=new int[2];view.getLocationOnScreen(origin);return new Rect(origin[0],origin[1],origin[0]+view.getWidth(),origin[1]+view.getHeight());}
  private static JSONObject windowGeometry(Window window)throws JSONException {
    View decor=window.getDecorView();WindowManager.LayoutParams attributes=window.getAttributes();Rect visible=new Rect();boolean onScreen=decor.getGlobalVisibleRect(visible);JSONObject result=new JSONObject();
    result.put("decor_bounds_screen_px",rectJson(screenBounds(decor)));result.put("decor_size_px",new JSONArray(new int[]{decor.getWidth(),decor.getHeight()}));result.put("decor_global_visible_px",rectJson(visible));result.put("decor_visible",onScreen);result.put("requested_size_px",new JSONArray(new int[]{attributes.width,attributes.height}));result.put("layout_offset_xy_px",new JSONArray(new int[]{attributes.x,attributes.y}));result.put("gravity",attributes.gravity);result.put("window_metrics_bounds_px",rectJson(window.getWindowManager().getCurrentWindowMetrics().getBounds()));Alignment alignment=ALIGNMENTS.get(window);result.put("test_fixture_alignment",alignment==null?JSONObject.NULL:alignment.json());return result;
  }
  private static void captureGeometry(JSONObject metadata,RawEditActivity activity,View root,Dialog dialog,Rect crop,int[] appOrigin)throws JSONException {
    Rect appContent=new Rect(crop);appContent.offset(appOrigin[0],appOrigin[1]);metadata.put("app_window",windowGeometry(activity.getWindow()));metadata.put("app_root_bounds_screen_px",rectJson(screenBounds(root)));metadata.put("app_root_padding_px",new JSONArray(new int[]{root.getPaddingLeft(),root.getPaddingTop(),root.getPaddingRight(),root.getPaddingBottom()}));metadata.put("app_content_crop_window_px",rectJson(crop));metadata.put("app_content_bounds_screen_px",rectJson(appContent));metadata.put("capture_composition","actual_window_screen_coordinates_no_pixel_reposition");metadata.put("owned_dialog_test_alignment_enabled",activity.getWindow().getAttributes().height>0);
    android.util.DisplayMetrics display=new android.util.DisplayMetrics();activity.getDisplay().getRealMetrics(display);metadata.put("display_size_px",new JSONArray(new int[]{display.widthPixels,display.heightPixels}));
    if(dialog!=null&&dialog.getWindow()!=null){metadata.put("dialog_window",windowGeometry(dialog.getWindow()));RawEditLookPanel panel=sheet(activity);if(panel!=null){Rect bounds=screenBounds(panel),intersection=new Rect(bounds);boolean intersects=intersection.intersect(appContent);metadata.put("dialog_panel_bounds_screen_px",rectJson(bounds));metadata.put("dialog_panel_size_px",new JSONArray(new int[]{panel.getWidth(),panel.getHeight()}));metadata.put("dialog_panel_intersects_content",intersects);metadata.put("dialog_panel_intersection_content_px",intersects?rectJson(intersection):JSONObject.NULL);metadata.put("dialog_panel_visible_height_in_capture_px",intersects?intersection.height():0);metadata.put("dialog_panel_clipped_by_capture",!appContent.contains(bounds));}}
  }
  private static void capture(Instrumentation t,RawEditActivity a,String name)throws Exception {
    ready(t,a,"capture."+name);Window[] overlay={null};int[] appOrigin=new int[2],dialogOrigin=new int[2];Rect[] content={null};JSONObject metadata=new JSONObject();String[] prefix={null};
    main(t,()->{View decor=a.getWindow().getDecorView(),root=((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);decor.getLocationOnScreen(appOrigin);int[] location=new int[2];root.getLocationOnScreen(location);content[0]=new Rect(location[0]-appOrigin[0]+root.getPaddingLeft(),location[1]-appOrigin[1]+root.getPaddingTop(),location[0]-appOrigin[0]+root.getWidth()-root.getPaddingRight(),location[1]-appOrigin[1]+root.getHeight()-root.getPaddingBottom());Dialog dialog=(Dialog)field(a,"lookDialog");if(dialog!=null){overlay[0]=dialog.getWindow();overlay[0].getDecorView().getLocationOnScreen(dialogOrigin);}float density=a.getResources().getDisplayMetrics().density,font=a.getResources().getConfiguration().fontScale;prefix[0]=font>1.1f?"short-":"normal-";try{metadata.put("density",density);metadata.put("font",font);metadata.put("width",content[0].width());metadata.put("height",content[0].height());metadata.put("content_dp_width",content[0].width()/density);metadata.put("content_dp_height",content[0].height()/density);metadata.put("dialog",overlay[0]!=null);metadata.put("scroll",sheet(a)==null?0:sheet(a).getScrollY());metadata.put("external_document_picker","unverified");captureGeometry(metadata,a,root,dialog,content[0],appOrigin);}catch(JSONException error){throw new AssertionError(error);}});
    Bitmap app=copy(t,a.getWindow()),dialog=null,cropped=null;
    try{if(overlay[0]!=null){dialog=copy(t,overlay[0]);new Canvas(app).drawBitmap(dialog,dialogOrigin[0]-appOrigin[0],dialogOrigin[1]-appOrigin[1],null);}Rect r=content[0];require(r.left>=0&&r.top>=0&&r.right<=app.getWidth()&&r.bottom<=app.getHeight(),"capture content within Window");cropped=Bitmap.createBitmap(app,r.left,r.top,r.width(),r.height());File folder=new File(a.getCacheDir(),"look-ui-check");require(folder.isDirectory()||folder.mkdirs(),"look capture directory");try(OutputStream out=new FileOutputStream(new File(folder,prefix[0]+name+".png"))){require(cropped.compress(Bitmap.CompressFormat.PNG,100,out),"look capture PNG");}try(Writer out=new FileWriter(new File(folder,prefix[0]+name+".json"))){out.write(metadata.toString(2));}}finally{if(cropped!=null&&cropped!=app)cropped.recycle();if(dialog!=null)dialog.recycle();app.recycle();}
  }
}
