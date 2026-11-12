package jp.nano.rawgallery;

import android.app.Dialog;
import android.view.View;
import android.view.ViewTreeObserver;

/** Measures the visible Material surface, excluding the modal window's scrim. */
final class SheetDiagnostics {
  private SheetDiagnostics() {}
  static void install(Dialog dialog) {
    View decor=dialog.getWindow().getDecorView();
    decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
      private ViewTreeObserver.OnPreDrawListener observer;
      @Override public void onViewAttachedToWindow(View view) {
        long request=DiagnosticLog.id();DiagnosticLog.event("dialog.show",request);
        observer=new ViewTreeObserver.OnPreDrawListener() {
          @Override public boolean onPreDraw() {
            View surface=dialog.getWindow().findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if(surface==null)surface=view;
            if(surface.getWidth()==0||surface.getHeight()==0)return true;
            if(view.getViewTreeObserver().isAlive())view.getViewTreeObserver().removeOnPreDrawListener(this);
            int[] position=new int[2];surface.getLocationOnScreen(position);
            DiagnosticLog.event("dialog.first_draw",request,"x",position[0],"y",position[1],
                "width",surface.getWidth(),"height",surface.getHeight());
            if(view.isHardwareAccelerated())view.getViewTreeObserver().registerFrameCommitCallback(
                ()->DiagnosticLog.event("dialog.frame_commit",request));
            else DiagnosticLog.event("dialog.frame_unavailable",request);
            return true;
          }
        };
        view.getViewTreeObserver().addOnPreDrawListener(observer);
      }
      @Override public void onViewDetachedFromWindow(View view) {
        if(observer!=null&&view.getViewTreeObserver().isAlive())view.getViewTreeObserver().removeOnPreDrawListener(observer);
      }
    });
  }
}
