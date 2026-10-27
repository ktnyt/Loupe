package jp.nano.rawgallery;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.TextView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Synthetic menus only: no user photos, tags, ranking or selection are changed. */
final class SheetChecks {
  private static void actualMenus(Instrumentation test, MainActivity activity,int width,float font) throws Exception {
    GalleryScreenChecks.finishInitialIndex(test,activity);
    java.io.File photo=new java.io.File(activity.getCacheDir(),"menu-screen-synthetic.jpg");
    android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(200,200,android.graphics.Bitmap.Config.ARGB_8888);image.eraseColor(0xff718774);
    try(java.io.FileOutputStream out=new java.io.FileOutputStream(photo)){image.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,out);}finally{image.recycle();}
    java.io.File dir=new java.io.File(activity.getCacheDir(),"consistency-checks");dir.mkdirs();
    try{
      java.util.ArrayList<PhotoRepository.Photo> photos=new java.util.ArrayList<>();java.util.ArrayList<java.util.List<Integer>> groups=new java.util.ArrayList<>();
      for(int i=0;i<16;i++){photos.add(new PhotoRepository.Photo(android.net.Uri.fromFile(photo),"合成メニュー"+i+".jpg","",photo.length(),i,0));groups.add(java.util.List.of(i));}
      TagDetailChecks.ui(test,()->{RankingResultChecks.set(activity,"photos",photos);RankingResultChecks.set(activity,"bundles",groups);RankingResultChecks.set(activity,"selecting",false);RankingResultChecks.set(activity,"burstMembers",null);RankingResultChecks.set(activity,"albumFolder",null);RankingResultChecks.call(activity,"gallery");});GalleryScreenChecks.syntheticCarousel(test,activity);
      for(String method:new String[]{"photoMenu","galleryMenu"})for(int dismissal=0;dismissal<3;dismissal++){
        TagDetailChecks.ui(test,()->RankingResultChecks.call(activity,method));test.waitForIdleSync();
        android.view.View[] focused={null};long deadline=android.os.SystemClock.uptimeMillis()+5000;
        do{TagDetailChecks.ui(test,()->{for(android.view.View root:android.view.inspector.WindowInspector.getGlobalWindowViews())if(root!=activity.getWindow().getDecorView()&&root.isShown()&&root.hasWindowFocus())focused[0]=root;});if(focused[0]!=null)break;Thread.sleep(16);}while(android.os.SystemClock.uptimeMillis()<deadline);
        android.view.View dialog=focused[0];if(dialog==null)throw new AssertionError("menu dialog focus");ConsistencyLayoutChecks.frame(test,dialog);
        Thread.sleep(250); // Let the OS dim-layer fade finish before comparing colors.
        android.graphics.Bitmap screenshot=test.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
        if(screenshot==null)throw new AssertionError("menu full screenshot");
        android.view.View menu=dialog;TagDetailChecks.ui(test,()->{
          TagDetailChecks.inspect(menu);int expected=GalleryUi.dp(activity,width-24);TagDetailChecks.require(menu.getWidth()==expected,"menu matches activity width");
          MoreMenuUi.Content content=findContent(menu);TagDetailChecks.require(content!=null,"dedicated menu content");
          for(int i=0;i<content.rows.getChildCount();i++)TagDetailChecks.require(content.rows.getChildAt(i).getHeight()>=GalleryUi.dp(activity,48),"menu row touch height");
        });
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,"menu-actual-"+width+"-"+font+"-"+method+".png"))){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{screenshot.recycle();}
        if(method.equals("galleryMenu")&&dismissal==0){
          MoreMenuUi.Content content=findContent(dialog);TagDetailChecks.ui(test,()->content.list.fullScroll(View.FOCUS_DOWN));test.waitForIdleSync();ConsistencyLayoutChecks.frame(test,dialog);
          fullyVisible(test,content.rows.getChildAt(6));
        }
        if(dismissal==0){TagDetailChecks.ui(test,()->{android.widget.Button close=TagDetailChecks.button(menu,"閉じる");TagDetailChecks.require(close!=null,"menu close action");close.performClick();});}
        else if(dismissal==1)test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        else{int[] point=new int[2];TagDetailChecks.ui(test,()->{menu.getLocationOnScreen(point);point[0]+=menu.getWidth()/2;point[1]-=GalleryUi.dp(activity,32);});long now=android.os.SystemClock.uptimeMillis();android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,0,point[0],point[1],0),up=android.view.MotionEvent.obtain(now,now+40,1,point[0],point[1],0);try{test.sendPointerSync(down);test.sendPointerSync(up);}finally{down.recycle();up.recycle();}}
        test.waitForIdleSync();
        boolean[] restored={false};deadline=android.os.SystemClock.uptimeMillis()+5000;
        do{TagDetailChecks.ui(test,()->restored[0]=((android.view.View)RankingResultChecks.get(activity,"root")).hasWindowFocus());if(restored[0])break;Thread.sleep(16);}while(android.os.SystemClock.uptimeMillis()<deadline);
        TagDetailChecks.require(restored[0],"menu dismissal restores gallery focus");
      }
      TagDetailChecks.ui(test,()->RankingResultChecks.call(activity,"photoMenu"));test.waitForIdleSync();
      View stage=focusedMenu(test,activity);TagDetailChecks.ui(test,()->TagDetailChecks.button(stage,"設定とその他").performClick());test.waitForIdleSync();
      View settings=focusedMenu(test,activity);TagDetailChecks.ui(test,()->TagDetailChecks.require(TagDetailChecks.button(settings,"クラウドと同期")!=null,"photo menu opens settings menu"));
      test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);test.waitForIdleSync();
    }finally{photo.delete();}
  }
  static String run(Instrumentation test) throws Exception {
    MainActivity activity = (MainActivity) test.startActivitySync(
        new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    StringBuilder result = new StringBuilder();
    int originalOrientation=activity.getRequestedOrientation();
    try {
      for(int width:new int[]{320,400})for(float font:new float[]{1f,1.3f}){
        GalleryScreenChecks.portraitFixture(test,activity,width,font);
        actualMenus(test,activity,width,font);
      }
      GalleryScreenChecks.portraitFixture(test,activity,400,1f);
      test.runOnMainSync(() -> {
        TextView backdrop = new TextView(activity);
        backdrop.setText("メニュー表示テスト"); backdrop.setTextColor(GalleryUi.TEXT);
        backdrop.setBackgroundColor(GalleryUi.BG); activity.setContentView(backdrop);
      });
      for (int kind = 0; kind < 6; kind++) {
        int mode = kind;
        if(mode>=4)GalleryScreenChecks.portraitFixture(test,activity,mode==4?400:320,mode==4?1f:1.3f);
        Dialog[] dialog = new Dialog[1];
        boolean[] clicked = {false}, shown = {false};
        test.runOnMainSync(() -> {
          android.content.Context context = activity;
          if (mode == 3) {
            android.view.ContextThemeWrapper enlarged = new android.view.ContextThemeWrapper(activity, activity.getTheme());
            android.content.res.Configuration config = new android.content.res.Configuration();
            config.fontScale = 1.3f; enlarged.applyOverrideConfiguration(config); context = enlarged;
          }
          if(mode>=4){dialog[0]=MoreMenuUi.create(activity,"設定とその他の操作",new String[]{"クラウドと同期","共有先の設定","連写のまとめ方","写真へのアクセス","再読み込み","写真のプレビュー","処理の状況"},(d,i)->clicked[0]=i==6);dialog[0].setOnShowListener(d->shown[0]=true);return;}
          GalleryUi.Sheet builder = new GalleryUi.Sheet(context);
          if (mode == 2) {
            EditText input = new EditText(activity); input.setHint("タグ名");
            builder.setTitle("新しいタグ").setView(input).setPositiveButton("作成", null);
          } else {
            String[] items = mode == 0 ? new String[]{"カメラで絞り込み", "タグ", "設定とその他"} : new String[40];
            if (mode == 1 || mode == 3) for (int i = 0; i < items.length; i++) items[i] = "項目 " + i;
            builder.setItems(items, (d, which) -> clicked[0] = true);
          }
          builder.setNegativeButton("閉じる", null);
          dialog[0] = builder.create();
          // This is the same override pattern used by the tag creation screen.
          dialog[0].setOnShowListener(d -> shown[0] = true);
        });
        for (int repeat = 0; repeat < 2; repeat++) {
          Rect[] first = new Rect[1]; Rect[] visible = new Rect[1];
          java.util.List<Rect> frames = new java.util.ArrayList<>();
          CountDownLatch drawn = new CountDownLatch(1);
          View decor = dialog[0].getWindow().getDecorView();
          ViewTreeObserver.OnDrawListener observer = () -> {
            frames.add(bounds(decor));
            decor.postInvalidateOnAnimation();
            if (first[0] == null) {
              first[0] = bounds(decor);
              visible[0] = new Rect(); decor.getWindowVisibleDisplayFrame(visible[0]);
              drawn.countDown();
            }
          };
          long start = android.os.SystemClock.elapsedRealtime();
          test.runOnMainSync(() -> { decor.getViewTreeObserver().addOnDrawListener(observer); dialog[0].show(); });
          if (!drawn.await(4, TimeUnit.SECONDS)) throw new AssertionError("no first draw " + mode);
          Thread.sleep(300);
          Rect[] stable = new Rect[1];
          test.runOnMainSync(() -> {
            stable[0] = bounds(decor);
            decor.getViewTreeObserver().removeOnDrawListener(observer);
          });
          int expectedWidth = Math.min((mode>=4?activity.getWindow().getDecorView().getWidth():activity.getResources().getDisplayMetrics().widthPixels)
              - GalleryUi.dp(activity, 24), GalleryUi.dp(activity, 560));
          if (frames.size() < 2) throw new AssertionError("insufficient frames");
          for (Rect frame : frames) if (!frame.equals(first[0]))
            throw new AssertionError("intermediate moving frame " + first[0] + " -> " + frame);
          if (Math.abs(first[0].centerX() - visible[0].centerX()) > 1) throw new AssertionError("not centered " + first[0]);
          if (!first[0].equals(stable[0])) throw new AssertionError("moving frame " + first[0] + " -> " + stable[0]);
          if (first[0].width() != expectedWidth) throw new AssertionError("wrong width " + first[0]);
          if (Math.abs(first[0].bottom - visible[0].bottom) > 1) throw new AssertionError("not bottom " + first[0] + " visible=" + visible[0]);
          if (first[0].height() > (int)(visible[0].height() * .48f)) throw new AssertionError("height cap " + first[0]);
          if (!shown[0]) throw new AssertionError("onShow lost");
          result.append("PASS mode=").append(mode).append(" repeat=").append(repeat)
              .append(" frames=").append(frames.size()).append(" first=").append(first[0]).append(" unchanged after 300ms; observed_ms=")
              .append(android.os.SystemClock.elapsedRealtime() - start).append('\n');
          test.runOnMainSync(() -> {
            if (mode == 0 && ((AlertDialog)dialog[0]).getListView() != null)
              ((AlertDialog)dialog[0]).getListView().performItemClick(((AlertDialog)dialog[0]).getListView().getChildAt(0), 0, 0);
            if(mode>=4){MoreMenuUi.Content content=findContent(decor);content.rows.getChildAt(6).performClick();}
            dialog[0].dismiss();
          });
        }
        if ((mode == 0||mode>=4) && !clicked[0]) throw new AssertionError("item action lost");
        if(mode>=4){
          Dialog[] low={null};TagDetailChecks.ui(test,()->{low[0]=MoreMenuUi.create(activity,"設定とその他の操作",new String[]{"一","二","三","四","五","六","処理の状況"},(d,i)->{});low[0].getWindow().setLayout(GalleryUi.dp(activity,mode==4?376:296),GalleryUi.dp(activity,180));low[0].show();});
          try{View decor=low[0].getWindow().getDecorView();ConsistencyLayoutChecks.frame(test,decor);MoreMenuUi.Content content=findContent(decor);TagDetailChecks.ui(test,()->content.fullScroll(View.FOCUS_DOWN));ConsistencyLayoutChecks.frame(test,decor);
            fullyVisible(test,content.rows.getChildAt(6));fullyVisible(test,TagDetailChecks.button(content,"閉じる"));
            result.append("PASS short menu 180dp at ").append(mode==4?400:320).append("dp; last item and close reachable\n");
          }finally{TagDetailChecks.ui(test,()->low[0].dismiss());}
        }
      }
      return "PASS actual menu four portrait conditions; close/Back/outside, last-item scroll and photo-to-settings path checked\n"+result;
    } finally { test.runOnMainSync(()->{activity.setRequestedOrientation(originalOrientation);activity.finish();}); }
  }
  private static void fullyVisible(Instrumentation test,View view)throws Exception{
    boolean[] ready={false};long until=android.os.SystemClock.uptimeMillis()+3000;Rect visible=new Rect();
    do{TagDetailChecks.ui(test,()->ready[0]=view.getGlobalVisibleRect(visible)&&visible.height()==view.getHeight());if(ready[0])return;Thread.sleep(16);}while(android.os.SystemClock.uptimeMillis()<until);
    TagDetailChecks.ui(test,()->android.util.Log.e("LoupeFixtureGeometry","menuItem measured="+view.getMeasuredWidth()+"x"+view.getMeasuredHeight()+" laid="+view.getWidth()+"x"+view.getHeight()+" visible="+visible+" parentScroll="+((View)view.getParent()).getScrollY()));
    throw new AssertionError("menu item not fully visible after scroll");
  }
  private static View focusedMenu(Instrumentation test,MainActivity activity)throws Exception{
    View[] focused={null};long until=android.os.SystemClock.uptimeMillis()+5000;
    do{TagDetailChecks.ui(test,()->{for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews())if(root!=activity.getWindow().getDecorView()&&root.isShown()&&root.hasWindowFocus())focused[0]=root;});if(focused[0]!=null)return focused[0];Thread.sleep(16);}while(android.os.SystemClock.uptimeMillis()<until);throw new AssertionError("focused menu not ready");
  }
  private static MoreMenuUi.Content findContent(View view){if(view instanceof MoreMenuUi.Content)return (MoreMenuUi.Content)view;if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){MoreMenuUi.Content found=findContent(group.getChildAt(i));if(found!=null)return found;}}return null;}
  private static Rect bounds(View view) {
    int[] p = new int[2]; view.getLocationOnScreen(p);
    return new Rect(p[0], p[1], p[0] + view.getWidth(), p[1] + view.getHeight());
  }
}
