package jp.nano.rawgallery;

import static jp.nano.rawgallery.AlbumTagEntryChecks.*;
import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

/** Actual radio rendering with OS font settings unchanged. */
final class RankingModeChecks {
  static String run(Instrumentation t)throws Exception {
    String regression=SortNamingChecks.run(t);
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    int old=a.getRequestedOrientation(),count=0;
    try {
      for(boolean landscape:new boolean[]{false,true}) {
        main(t,()->{a.setRequestedOrientation(landscape?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);return true;});
        waitFor(t,"rotation",()->a.getResources().getConfiguration().orientation==(landscape?Configuration.ORIENTATION_LANDSCAPE:Configuration.ORIENTATION_PORTRAIT));
        for(int width:landscape?new int[]{640,800}:new int[]{320,400})for(float scale:new float[]{1f,1.3f})for(boolean best:new boolean[]{true,false}) {
          LinearLayout[] modes={null};int[] clicks={0};
          main(t,()->{Configuration cfg=new Configuration(a.getResources().getConfiguration());cfg.fontScale=scale;Context c=a.createConfigurationContext(cfg);
            modes[0]=RankingModes.create(c,best,()->clicks[0]++,()->clicks[0]++);
            LinearLayout outer=new LinearLayout(a);outer.setOrientation(1);outer.setBackgroundColor(GalleryUi.BG);outer.setPadding(0,GalleryUi.dp(a,64),0,0);
            outer.addView(modes[0],new LinearLayout.LayoutParams(GalleryUi.dp(c,width),-2));a.setContentView(outer);return true;});
          waitFor(t,"modes",()->modes[0].getHeight()>0);
          main(t,()->{
            LinearLayout panel=(LinearLayout)modes[0].getChildAt(0);
            if(panel.getImportantForAccessibility()!=View.IMPORTANT_FOR_ACCESSIBILITY_YES||!"ソート方式".contentEquals(panel.getContentDescription()))throw new AssertionError("group semantics");
            for(int i=0;i<2;i++) {
              Button b=(Button)panel.getChildAt(i);ActionLabelChecks.check(b);
              if(b.isSelected()!=(i==0?best:!best))throw new AssertionError("selection");
              AccessibilityNodeInfo info=b.createAccessibilityNodeInfo();
              if(!info.isCheckable()||info.isChecked()!=b.isSelected()||!RadioButton.class.getName().contentEquals(info.getClassName()))throw new AssertionError("radio semantics");
              if(!b.getContentDescription().toString().contains(i==0?"同率2位":"同じ順位も選べる"))throw new AssertionError("meaning missing");
              int diagram=b.getCompoundDrawables()[3].getBounds().width();
              if(diagram>b.getWidth()-b.getPaddingLeft()-b.getPaddingRight())throw new AssertionError("diagram clipped");
              b.performClick();if(b.isSelected()!=(i==0?best:!best))throw new AssertionError("optimistic selection");
            }
            if(clicks[0]!=2)throw new AssertionError("callbacks");return true;
          });count++;
          capture(t,a,modes[0],modes[0].getHeight(),"modes-"+width+"-"+scale+"-"+best);
        }
      }
      return regression+"PASS "+count+" ranking-mode layouts: portrait/landscape, 320/400/640/800dp, fonts1/1.3, both selections; text/diagram/touch bounds, radio semantics and no optimistic selection. TalkBack speech untested.\n";
    }finally{main(t,()->{a.setRequestedOrientation(old);a.finish();return true;});}
  }
}
