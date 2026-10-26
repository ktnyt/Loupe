package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Manual TalkBack fixture. Never obtains UiAutomation (which suppresses accessibility services). */
final class TalkBackStatusChecks {
  static String run(Instrumentation t) throws Exception {
    MainActivity a=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    CountDownLatch done=new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger retries=new java.util.concurrent.atomic.AtomicInteger();
    try {
      Thread.sleep(1000);
      t.runOnMainSync(()->{
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=new LinearLayout(a);root.setOrientation(1);root.setPadding(24,180,24,0);root.setBackgroundColor(GalleryUi.BG);
        StatusView.Symbol[] kinds={StatusView.Symbol.UNKNOWN,StatusView.Symbol.RUNNING,StatusView.Symbol.WAITING,StatusView.Symbol.PAUSED,StatusView.Symbol.COMPLETE,StatusView.Symbol.PARTIAL,StatusView.Symbol.AUTH,StatusView.Symbol.FAILED};
        String[] labels={"準備状況は不明です","写真を読み込んでいます。12枚中3枚","同期を待機しています","同期を一時停止しています","準備が完了しました","12枚中3枚の準備に失敗しました","ログインが必要です","写真の原本を読み込めませんでした"};
        StatusView state=new StatusView(a);root.addView(state,new LinearLayout.LayoutParams(-1,GalleryUi.dp(a,64)));
        int[] index={0};
        Runnable focus=()->{if(done.getCount()!=0)state.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,null);};
        Runnable show=()->{
          state.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS,null);
          int i=index[0];state.show(kinds[i],"",labels[i],i==7?()->{retries.incrementAndGet();state.show(StatusView.Symbol.COMPLETE,"再試行して写真を読み込みました");state.postDelayed(focus,500);}:null,i==7?"再試行":"");
          state.postDelayed(focus,500);
        };
        root.addView(GalleryUi.button(a,"次の状態",()->{index[0]=(index[0]+1)%kinds.length;show.run();}));
        root.addView(GalleryUi.button(a,"検証を終了",done::countDown));a.setContentView(root);show.run();
      });
      if(!done.await(10,TimeUnit.MINUTES))throw new AssertionError("manual fixture timed out");
      if(retries.get()!=1)throw new AssertionError("expected one actual retry, got "+retries.get());
      return "PASS manual fixture retry invoked once. Spoken output must be assessed separately from TalkBack speech display; no audio-quality or gesture assertion.\n";
    } finally { t.runOnMainSync(()->{a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.finish();}); }
  }
}
