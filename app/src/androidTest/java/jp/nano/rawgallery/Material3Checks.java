package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.io.File;
import java.io.FileOutputStream;

/** Synthetic UI only: native state/AX, callbacks, font scaling and dialog lifecycle. */
final class Material3Checks {
  private static Object field(Object owner,String name) {
    try { java.lang.reflect.Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner); }
    catch(ReflectiveOperationException error){throw new AssertionError(error);}
  }
  static String run(Instrumentation test) throws Exception {
    MainActivity activity=(MainActivity)test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      GalleryScreenChecks.finishInitialIndex(test,activity);
      for(int width:new int[]{320,activity.getResources().getConfiguration().screenWidthDp})for(float font:new float[]{1f,1.3f}) {
        LinearLayout[] content={null};
        TagDetailChecks.ui(test,()->{
          Configuration config=new Configuration(activity.getResources().getConfiguration());config.fontScale=font;
          Context context=new ContextThemeWrapper(activity.createConfigurationContext(config),R.style.AppTheme);
          LinearLayout root=new LinearLayout(context);root.setOrientation(LinearLayout.VERTICAL);
          root.setBackgroundColor(GalleryUi.BG);root.setPadding(GalleryUi.dp(context,16),GalleryUi.dp(context,24),GalleryUi.dp(context,16),GalleryUi.dp(context,16));
          int[] clicks={0};
          Button primary=GalleryUi.primary(GalleryUi.button(context,"共有",()->clicks[0]++));
          primary.setCompoundDrawables(null,null,null,null);
          root.addView(primary,new LinearLayout.LayoutParams(-1,-2));
          TagDetailChecks.require(primary instanceof MaterialButton,"native MaterialButton");
          primary.performClick();primary.setEnabled(false);
          TagDetailChecks.require(clicks[0]==1&&!primary.createAccessibilityNodeInfo().isEnabled(),"button callback and disabled AX state");
          primary.setEnabled(true);
          Button choice=GalleryUi.chip(context,"1位の写真",true,()->{});root.addView(choice,new LinearLayout.LayoutParams(-1,-2));
          TagDetailChecks.require(((MaterialButton)choice).isChecked(),"native choice checked state");
          MaterialUi.choice(choice,false);TagDetailChecks.require(!((MaterialButton)choice).isChecked(),"native choice clears");
          int[] toggles={0};SelectionUi.BestSwitch toggle=new SelectionUi.BestSwitch(context);
          toggle.setOnCheckedChangeListener((button,checked)->toggles[0]++);root.addView(toggle,new LinearLayout.LayoutParams(-1,-2));
          toggle.performClick();toggle.performClick();toggle.setChecked(true);
          TagDetailChecks.require(toggle instanceof MaterialSwitch&&toggle.isChecked()&&toggles[0]==3,"switch callbacks");
          android.view.accessibility.AccessibilityNodeInfo node=toggle.createAccessibilityNodeInfo();
          TagDetailChecks.require(node.isCheckable()&&node.isChecked(),"switch AX checked");node.recycle();
          int[] destinations={0};LibraryTabs tabs=new LibraryTabs(context,false,true,value->destinations[0]++);
          tabs.getTabAt(1).select();TagDetailChecks.require(destinations[0]==1&&tabs.getSelectedTabPosition()==1,"tab callback");
          root.addView(tabs,new LinearLayout.LayoutParams(-1,-2));
          LibraryTabs disabled=new LibraryTabs(context,false,false,value->{throw new AssertionError("disabled tab callback");});
          ((android.view.ViewGroup)disabled.getChildAt(0)).getChildAt(1).performClick();
          TagDetailChecks.require(disabled.getSelectedTabPosition()==0,"disabled tab retains selection");
          root.addView(disabled,new LinearLayout.LayoutParams(-1,-2));
          ShareUi.FormatButton format=new ShareUi.FormatButton(context,"元の形式で共有",()->{});
          format.setText("元の形式で共有\n512 MiB");MaterialUi.choice(format,true);root.addView(format,new LinearLayout.LayoutParams(-1,-2));
          ScrollView scroll=new ScrollView(activity);scroll.addView(root,new ScrollView.LayoutParams(GalleryUi.dp(context,width),-2));activity.setContentView(scroll);content[0]=root;
        });
        ConsistencyLayoutChecks.frame(test,content[0]);
        TagDetailChecks.ui(test,()->{
          TagDetailChecks.inspect(content[0]);
          for(int i=0;i<content[0].getChildCount();i++)TagDetailChecks.require(content[0].getChildAt(i).getHeight()>=GalleryUi.dp(content[0].getContext(),48),"48dp hit target");
          Bitmap image=Bitmap.createBitmap(content[0].getWidth(),content[0].getHeight(),Bitmap.Config.ARGB_8888);content[0].draw(new android.graphics.Canvas(image));
          File directory=new File(activity.getCacheDir(),"material3-checks");directory.mkdirs();
          try(FileOutputStream out=new FileOutputStream(new File(directory,"controls-"+width+"-"+font+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}catch(java.io.IOException error){throw new AssertionError(error);}finally{image.recycle();}
        });
      }
      for(float scale:new float[]{1f,1.3f}) {
        androidx.appcompat.app.AlertDialog[] list={null};int[] chosen={0};
        TagDetailChecks.ui(test,()->{
          Configuration config=new Configuration(activity.getResources().getConfiguration());config.fontScale=scale;
          Context context=scale==1f?activity:new ContextThemeWrapper(activity.createConfigurationContext(config),R.style.AppTheme);
          list[0]=new GalleryUi.Sheet(context).setTitle("項目の合成検査")
              .setItems(new String[]{"すべての写真","アルバム"},(dialog,index)->chosen[0]++).show();
        });
        ConsistencyLayoutChecks.frame(test,list[0].getWindow().getDecorView());
        TagDetailChecks.ui(test,()->{
          ListView rows=list[0].getListView();TagDetailChecks.require(rows.getChildCount()>0&&rows.getChildAt(0).getHeight()>0,"native list item actually draws");
          rows.performItemClick(rows.getChildAt(0),0,0);TagDetailChecks.require(chosen[0]==1&&!list[0].isShowing(),"native list action closes once");
        });
      }
      TagDetailChecks.ui(test,()->{
        androidx.appcompat.app.AlertDialog dialog=new GalleryUi.Sheet(activity).setTitle("合成データの確認").setMessage("変更は保存しません").setPositiveButton("閉じる",null).show();
        TagDetailChecks.require(dialog.isShowing(),"Material alert opens");dialog.dismiss();
        int[] cancelled={0};ShareUi.Preparing sheet=new ShareUi.Preparing(activity,2,false,()->cancelled[0]++);sheet.show();
        TagDetailChecks.require(sheet instanceof com.google.android.material.bottomsheet.BottomSheetDialog,"Material bottom sheet");
        BurstBestSheet ranking=new BurstBestSheet(activity,true,false,"2枚",(together,done)->{});ranking.show();
        ((Button)field(ranking,"add")).performClick();
        ProgressBar progress=(ProgressBar)field(ranking,"progress");
        TagDetailChecks.require(progress.isIndeterminate()&&progress.getVisibility()==View.VISIBLE,"saving indicator runs");ranking.dismiss();
        Button stop=TagDetailChecks.button(sheet.content,"中止");TagDetailChecks.require(stop!=null,"cancel reachable");stop.performClick();
        TagDetailChecks.require(!sheet.isShowing()&&cancelled[0]==1,"cancel exactly once");
      });
      return "PASS Material3 native controls: 320dp/native width x font1.0/1.3, 48dp targets, checked/disabled/AX states, tab/switch callbacks, alert and bottom-sheet cancellation. Synthetic captures only; physical touch, audio and photo quality unverified.\n";
    } finally { TagDetailChecks.ui(test,activity::finish); }
  }
}
