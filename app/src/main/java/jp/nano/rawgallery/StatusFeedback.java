package jp.nano.rawgallery;

import android.app.Activity;
import android.view.*;
import android.widget.FrameLayout;

/** Replaces a transient message with one state marker in the current screen. */
final class StatusFeedback {
  static void show(Activity activity,StatusView.Symbol symbol,String message,Runnable recovery,String action) {
    FrameLayout content=activity.findViewById(android.R.id.content);
    View old=content.findViewWithTag("status-feedback");
    if(old!=null)content.removeView(old);
    StatusView status=new StatusView(activity);status.setTag("status-feedback");
    FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-2,GalleryUi.dp(activity,48),Gravity.BOTTOM|Gravity.END);
    params.rightMargin=GalleryUi.dp(activity,16);params.bottomMargin=GalleryUi.dp(activity,108);
    content.addView(status,params);
    if(recovery!=null||StatusView.error(symbol)||symbol==StatusView.Symbol.AUTH)
      status.show(symbol,"",message,recovery,action);
    else status.result(symbol,message);
  }
}
