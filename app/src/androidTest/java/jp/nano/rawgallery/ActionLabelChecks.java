package jp.nano.rawgallery;

import static jp.nano.rawgallery.AlbumTagEntryChecks.*;
import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Isolated rendering + actual generated-photo choice/undo regression. No system font setting changes. */
final class ActionLabelChecks {
  static void check(Button b) {
    if (b.getLayout().getLineCount()!=1) throw new AssertionError("wrap: "+b.getText());
    if (b.getLayout().getEllipsisCount(0)!=0 || b.getLayout().getLineWidth(0)>b.getWidth()-b.getCompoundPaddingLeft()-b.getCompoundPaddingRight()+1)
      throw new AssertionError("clip: "+b.getText());
    if (b.getLayout().getHeight()+b.getCompoundPaddingTop()+b.getCompoundPaddingBottom()>b.getHeight()) throw new AssertionError("height clip");
    if (b.getWidth()<GalleryUi.dp(b.getContext(),48)||b.getHeight()<GalleryUi.dp(b.getContext(),64)) throw new AssertionError("touch size");
  }
  static String run(Instrumentation t)throws Exception {
    String regression=ComparisonLabelChecks.run(t);
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    int orientation=a.getRequestedOrientation();int count=0;
    try {
      for(boolean landscape:new boolean[]{false,true}) {
        main(t,()->{a.setRequestedOrientation(landscape?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);return true;});
        waitFor(t,"rotation",()->a.getResources().getConfiguration().orientation==(landscape?Configuration.ORIENTATION_LANDSCAPE:Configuration.ORIENTATION_PORTRAIT));
        for(int width:landscape?new int[]{640,800}:new int[]{320,400})for(float scale:new float[]{1f,1.3f})for(boolean all:new boolean[]{false,true})for(boolean share:new boolean[]{false,true})for(boolean enabled:new boolean[]{false,true}) {
          View[] dock={null};List<Button> buttons=new ArrayList<>();int[] clicks={0};
          main(t,()->{
            Configuration cfg=new Configuration(a.getResources().getConfiguration());cfg.fontScale=scale;Context c=a.createConfigurationContext(cfg);
            Button back=GalleryUi.button(c,share?"選択へ戻る":"連写一覧",()->{});
            Button undo=GalleryUi.button(c,"直前の操作を取り消す",()->clicks[0]++);undo.setText("取り消し");undo.setEnabled(enabled);
            buttons.add(back);buttons.add(undo);if(all)buttons.add(GalleryUi.button(c,"同じ順位",()->{}));buttons.add(GalleryUi.button(c,"その他",()->{}));
            dock[0]=GalleryUi.rankingDock(c,buttons.toArray(new Button[0]));
            LinearLayout outer=new LinearLayout(a);outer.setOrientation(1);outer.setPadding(0,GalleryUi.dp(a,64),0,0);outer.setBackgroundColor(GalleryUi.BG);
            outer.addView(dock[0],new LinearLayout.LayoutParams(GalleryUi.dp(c,width),-2));a.setContentView(outer);return true;
          });
          waitFor(t,"dock",()->dock[0].getHeight()>0&&buttons.get(0).getWidth()>0);
          main(t,()->{
            for(Button b:buttons)check(b);
            Button undo=buttons.get(1);
            if(!"直前の操作を取り消す".contentEquals(undo.getContentDescription())||!"直前の操作を取り消す".contentEquals(undo.getTooltipText())||undo.getCompoundDrawables()[1]==null||undo.isEnabled()!=enabled)throw new AssertionError("undo semantics");
            if(enabled){undo.performClick();if(clicks[0]!=1)throw new AssertionError("undo click");}
            for(int i=0;i<buttons.size();i++)for(int j=i+1;j<buttons.size();j++){android.graphics.Rect x=new android.graphics.Rect(),y=new android.graphics.Rect();buttons.get(i).getHitRect(x);buttons.get(j).getHitRect(y);if(android.graphics.Rect.intersects(x,y))throw new AssertionError("overlap");}
            return true;
          });count++;
          if(share&&enabled)capture(t,a,dock[0],dock[0].getHeight(),"actions-"+width+"-"+scale+"-"+(all?4:3));
        }
      }
      main(t,()->{
        for(int width:new int[]{320,400,640,800})for(float scale:new float[]{1f,1.3f})for(String label:new String[]{"ソート","ソートを再開","ソート結果"}){
          Configuration cfg=new Configuration(a.getResources().getConfiguration());cfg.fontScale=scale;Context c=a.createConfigurationContext(cfg);
          Button b=GalleryUi.neutralPrimary(GalleryUi.button(c,label,()->{}));b.measure(View.MeasureSpec.makeMeasureSpec(GalleryUi.dp(c,width-32),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));b.layout(0,0,b.getMeasuredWidth(),b.getMeasuredHeight());check(b);
        }return true;
      });
      return regression+"PASS "+count+" ranking dock combinations (actual portrait/landscape, 320/400/640/800dp, 1/1.3 fonts, 3/4 actions, list/selection return, undo enabled/disabled), 24 full-width CTA checks. Single-line, no ellipsis/clipping/overlap, touch bounds, full undo AX name/tooltip and action checked. TalkBack speech untested.\n";
    } finally { main(t,()->{a.setRequestedOrientation(orientation);a.finish();return true;}); }
  }
}
