package jp.nano.rawgallery;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Opens an editor with a separate original-format copy; never grants access to the original. */
final class PhotoEditor {
  private final Activity activity;
  private final PhotoViewerUi viewer;
  private final PhotoRepository.Photo photo;
  private final ExecutorService worker;
  private final SharePreparation preparation;
  private final Handler main = new Handler(Looper.getMainLooper());
  final Button button;
  private Request active;

  private static final class Request {
    volatile boolean cancelled;
    boolean visible;
    Future<?> task;
    final long event = DiagnosticLog.id();
    Runnable show;
  }

  static Button add(Activity activity, PhotoViewerUi viewer, PhotoRepository.Photo photo,
      ExecutorService worker) {
    Context context = activity.getApplicationContext();
    return new PhotoEditor(activity, viewer, photo, worker,
        new SharePreparation(context, new PhotoRepository(context))).button;
  }

  PhotoEditor(Activity activity, PhotoViewerUi viewer, PhotoRepository.Photo photo,
      ExecutorService worker, SharePreparation preparation) {
    this.activity=activity; this.viewer=viewer; this.photo=photo;
    this.worker=worker; this.preparation=preparation;
    button=viewer.action("編集","編集","編集。端末の写真編集アプリで開きます",this::activate);
    button.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    viewer.editFocusLost=()->cancel(false);
    viewer.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
      public void onViewAttachedToWindow(View view) {}
      public void onViewDetachedFromWindow(View view) { cancel(false); }
    });
  }

  static Intent intent(Uri copy, String mime) {
    Intent edit=new Intent(Intent.ACTION_EDIT).setDataAndType(copy,mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    edit.setClipData(ClipData.newRawUri("写真",copy));
    return edit;
  }

  private boolean current(Request request) {
    return active==request && !request.cancelled && !activity.isFinishing()
        && !activity.isDestroyed() && viewer.isAttachedToWindow();
  }

  private void activate() {
    if(active!=null){if(active.visible)cancel(true);return;}
    Request request=new Request();active=request;
    PhotoViewerUi.editState(button,false,false,"");
    DiagnosticLog.event("photo.edit.prepare.start",request.event,"format",photo.isJpeg()?"JPEG":"RAW");
    request.show=()->{
      if(!current(request))return;
      request.visible=true;PhotoViewerUi.editState(button,true,false,"");
    };
    main.postDelayed(request.show,400);
    try {
      request.task=worker.submit(()->{
        List<File> files=null;
        try {
          SharePreparation.Result prepared=preparation.prepare(List.of(photo),true);
          files=prepared.files;
          if(request.cancelled){discard(files);return;}
          DiagnosticLog.event("photo.edit.prepare.complete",request.event);
          main.post(()->ready(request,prepared));
        } catch(Exception error) {
          discard(files);
          main.post(()->{
            if(!current(request))return;
            finish(request);
            DiagnosticLog.event("photo.edit.prepare.failed",request.event);
            PhotoViewerUi.editState(button,false,true,"編集の準備ができませんでした");
          });
        }
      });
    } catch(java.util.concurrent.RejectedExecutionException error) {
      finish(request);PhotoViewerUi.editState(button,false,true,"編集の準備ができませんでした");
      DiagnosticLog.event("photo.edit.prepare.failed",request.event);
    }
  }

  private void ready(Request request,SharePreparation.Result prepared) {
    if(!current(request)||!viewer.hasWindowFocus()){
      discard(prepared.files);
      if(active==request)cancel(false);
      return;
    }
    finish(request);
    File file=prepared.files.get(0);
    Intent edit=intent(Uri.parse("content://jp.nano.rawgallery.share/"+file.getName()),prepared.mimeType);
    try {
      if(edit.resolveActivity(activity.getPackageManager())==null)throw new ActivityNotFoundException();
      button.requestFocus();
      activity.startActivity(Intent.createChooser(edit,"写真を編集"));
      DiagnosticLog.event("photo.edit.chooser.open",request.event);
    } catch(ActivityNotFoundException error) {
      discard(prepared.files);
      PhotoViewerUi.editState(button,false,true,"この形式を編集できるアプリがありません");
      DiagnosticLog.event("photo.edit.chooser.unavailable",request.event);
    } catch(SecurityException error) {
      discard(prepared.files);
      PhotoViewerUi.editState(button,false,true,"編集アプリに写真を渡せませんでした");
      DiagnosticLog.event("photo.edit.chooser.denied",request.event);
    }
  }

  private void finish(Request request) {
    main.removeCallbacks(request.show);
    active=null;PhotoViewerUi.editState(button,false,false,"");
  }

  private void cancel(boolean announce) {
    Request request=active;if(request==null)return;
    request.cancelled=true;
    if(request.task!=null)request.task.cancel(true);
    finish(request);
    DiagnosticLog.event("photo.edit.prepare.cancelled",request.event);
    if(announce)button.announceForAccessibility("編集の準備を中止しました");
  }

  private static void discard(List<File> files) {
    if(files!=null)for(File file:files)file.delete();
  }
}
