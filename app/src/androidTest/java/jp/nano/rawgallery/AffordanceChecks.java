package jp.nano.rawgallery;

import static jp.nano.rawgallery.AlbumTagEntryChecks.*;
import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebView;
import android.widget.*;
import java.lang.reflect.Method;

/** Synthetic pixels and real entry UI only: no login, upload, sync or logout. */
final class AffordanceChecks {
  static String run(Instrumentation t)throws Exception {
    String regression=SortNamingChecks.run(t);
    CloudActivity cloud=(CloudActivity)t.startActivitySync(new Intent(t.getTargetContext(),CloudActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      waitFor(t,"Cloud focused",()->cloud.hasWindowFocus());
      main(t,()->{
        if((cloud.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)==0)throw new AssertionError("secure flag missing");
        TextView status=(TextView)field(cloud,"status");WebView web=(WebView)field(cloud,"web");
        if(status.getVisibility()!=View.GONE||web.getVisibility()!=View.GONE||web.getUrl()!=null)throw new AssertionError("initial state");
        for(String name:new String[]{"ログイン","同期の設定","接続と確認"})if(button(cloud.getWindow().getDecorView(),name)==null)throw new AssertionError(name);
        Method method=CloudActivity.class.getDeclaredMethod("setStatus",String.class);method.setAccessible(true);method.invoke(cloud,"合成検査：通信の安全性を確認できないため停止しました。");
        if(status.getVisibility()!=View.VISIBLE||status.getAccessibilityLiveRegion()!=View.ACCESSIBILITY_LIVE_REGION_POLITE||!status.getText().toString().contains("安全性"))throw new AssertionError("status lost");
        button(cloud.getWindow().getDecorView(),"接続と確認").performClick();return true;
      });
      waitFor(t,"menu focus",()->!cloud.hasWindowFocus());
      AccessibilityNodeInfo root=t.getUiAutomation().getRootInActiveWindow();
      for(String name:new String[]{"接続を確認","保存した写真をダウンロードして確認","写真1枚を保存して内容を確認","ログアウト"})if(root.findAccessibilityNodeInfosByText(name).isEmpty())throw new AssertionError("menu "+name);
      t.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitFor(t,"menu cancelled",()->cloud.hasWindowFocus());
    }finally{main(t,()->{cloud.finish();return true;});}
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    int count=0;
    try {
      for(int width:new int[]{320,400})for(float scale:new float[]{1f,1.3f})for(boolean longer:new boolean[]{false,true}) {
        LinearLayout[] content={null};View[] dock={null};int[] clicks={0};
        main(t,()->{
          Configuration cfg=new Configuration(a.getResources().getConfiguration());cfg.fontScale=scale;Context c=a.createConfigurationContext(cfg);
          LinearLayout root=new LinearLayout(a);root.setOrientation(1);root.setBackgroundColor(GalleryUi.BG);root.setPadding(0,GalleryUi.dp(a,64),0,0);
          content[0]=new LinearLayout(c);content[0].setOrientation(1);content[0].setPadding(GalleryUi.dp(c,8),0,GalleryUi.dp(c,8),0);
          for(int i=0;i<3;i++) {
            String name=longer?new String[]{"P1010403_嵐山_渡月橋_夕景_連写_0001_編集用.ORF","P1010404_嵐山_渡月橋_夕景_連写_0002_編集用.ORF","fixture-3-with-a-very-long-file-name-for-wrapping-check.jpg"}[i]:"fixture-"+(i+1)+".jpg";
            RankingResultRow row=new RankingResultRow(c,(i==0?"1位":"2位")+" · "+name);
            if(i==1)row.previewFailed();else{Bitmap b=Bitmap.createBitmap(88,72,Bitmap.Config.ARGB_8888);new Canvas(b).drawColor(i==0?0xff587c8c:0xff688664);row.thumbnail.setImageBitmap(b);}
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=GalleryUi.dp(c,4);content[0].addView(row,lp);
          }
          root.addView(content[0],new LinearLayout.LayoutParams(GalleryUi.dp(c,width),-2));
          dock[0]=CloudActivity.actions(c,()->clicks[0]++,()->clicks[0]++,()->clicks[0]++);
          root.addView(dock[0],new LinearLayout.LayoutParams(GalleryUi.dp(c,width),-2));a.setContentView(root);return true;
        });
        waitFor(t,"fixture layout",()->dock[0].getHeight()>0);
        main(t,()->{
          for(int i=0;i<3;i++) {
            RankingResultRow row=(RankingResultRow)content[0].getChildAt(i);TextView name=(TextView)row.getChildAt(1);
            if(row.getHeight()<GalleryUi.dp(a,84)||name.getLayout().getEllipsisCount(0)>0||name.getLayout().getHeight()>name.getHeight())throw new AssertionError("row bounds");
            for(int j=0;j<row.getChildCount();j++)if(row.getChildAt(j).isClickable()||row.getChildAt(j).isFocusable())throw new AssertionError("nested target");
            AccessibilityNodeInfo info=row.createAccessibilityNodeInfo();boolean action=false;
            for(AccessibilityNodeInfo.AccessibilityAction act:info.getActionList())if(android.text.TextUtils.equals("拡大",act.getLabel()))action=true;
            if(!action||!Button.class.getName().contentEquals(info.getClassName()))throw new AssertionError("row AX");
            Button button=(Button)((LinearLayout)dock[0]).getChildAt(i);ActionLabelChecks.check(button);button.performClick();
          }
          if(clicks[0]!=3)throw new AssertionError("entry callbacks");return true;
        });
        capture(t,a,content[0],content[0].getHeight(),"affordance-rows-"+width+"-"+scale+"-"+longer);
        capture(t,a,dock[0],dock[0].getHeight(),"affordance-cloud-"+width+"-"+scale);count++;
      }
      return regression+"PASS Cloud initial hidden status/web/no URL, FLAG_SECURE, status visible/polite, 4 menu entries and Back; "+count+" synthetic result/Cloud layouts, 320/400dp fonts1/1.3 short/long filenames, failed preview, AX action and entry callbacks. No live login/network/transfer; TalkBack speech untested.\n";
    }finally{main(t,()->{a.finish();return true;});}
  }
}
