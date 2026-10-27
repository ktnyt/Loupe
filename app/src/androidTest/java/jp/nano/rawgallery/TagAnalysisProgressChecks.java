package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import java.io.File;

/** Holds only the disposable fixture's analysis worker; validates the final, attached UI. */
final class TagAnalysisProgressChecks {
  static void verify(Instrumentation t,TagActivity activity)throws Exception{
    long deadline=SystemClock.uptimeMillis()+2500;
    boolean[] visible={false};
    do{
      TagDetailChecks.ui(t,()->{
        StatusView progress=(StatusView)TagDetailChecks.get(activity,"analysisStatus");Rect rect=new Rect();
        visible[0]=progress!=null&&progress.isShown()&&progress.getAlpha()>0&&progress.getGlobalVisibleRect(rect)&&rect.width()==progress.getWidth()&&rect.height()==progress.getHeight();
      });
      if(visible[0])break;
      SystemClock.sleep(50);
    }while(SystemClock.uptimeMillis()<deadline);
    TagDetailChecks.require(visible[0],"analysis progress is invisible after the loading delay");
    File dir=new File(activity.getCacheDir(),"consistency-checks");dir.mkdirs();
    String prefix="tag-analysis-progress-"+TagDetailChecks.width+"-"+activity.getResources().getConfiguration().fontScale;
    TagDetailChecks.ui(t,()->{
      StatusView progress=(StatusView)TagDetailChecks.get(activity,"analysisStatus");
      TagDetailChecks.require(progress.getContentDescription()!=null&&progress.getContentDescription().toString().contains("解析 0 / 12枚"),"initial analysis count is exposed");
    });
    ConsistencyLayoutChecks.captureHardware(t,activity,(View)TagDetailChecks.get(activity,"root"),dir,prefix+"-running");
    TagDetailChecks.ui(t,()->{TagDetailChecks.set(activity,"analyzed",6);TagDetailChecks.call(activity,"updateProgress");});
    ConsistencyLayoutChecks.frame(t,(View)TagDetailChecks.get(activity,"root"));
    TagDetailChecks.ui(t,()->{
      StatusView progress=(StatusView)TagDetailChecks.get(activity,"analysisStatus");
      TagDetailChecks.require(progress.isShown()&&progress.getContentDescription().toString().contains("解析 6 / 12枚"),"running progress updates visibly");
    });
    ConsistencyLayoutChecks.captureHardware(t,activity,(View)TagDetailChecks.get(activity,"root"),dir,prefix+"-updated");
    TagDetailChecks.ui(t,()->TagDetailChecks.call(activity,"renderPhotos"));
    SystemClock.sleep(650);
    TagDetailChecks.ui(t,()->{
      StatusView progress=(StatusView)TagDetailChecks.get(activity,"analysisStatus");
      TagDetailChecks.require(progress.isShown()&&progress.getAlpha()>0&&progress.getContentDescription().toString().contains("解析 6 / 12枚"),"progress survives a full panel rebuild");
    });
    // Layout-only large-number fixture; no photos or scores are processed for this number.
    TagDetailChecks.ui(t,()->{
      StatusView progress=(StatusView)TagDetailChecks.get(activity,"analysisStatus");
      progress.observe(StatusView.Symbol.RUNNING,"20354/32541","解析 20354 / 32541枚");
      ((StatusView)TagDetailChecks.get(activity,"assignmentStatus")).show(StatusView.Symbol.AUTO_TAG,"365","推定365枚",null,"");
    });
    ConsistencyLayoutChecks.frame(t,(View)TagDetailChecks.get(activity,"root"));
    TagDetailChecks.ui(t,()->{
      StatusView progress=(StatusView)TagDetailChecks.get(activity,"analysisStatus");Rect rect=new Rect();
      TagDetailChecks.require(progress.isShown()&&progress.getGlobalVisibleRect(rect)&&rect.width()==progress.getWidth()&&rect.height()==progress.getHeight(),"large progress is fully within the viewport");
      TagDetailChecks.inspect((View)TagDetailChecks.get(activity,"root"));
    });
    ConsistencyLayoutChecks.captureHardware(t,activity,(View)TagDetailChecks.get(activity,"root"),dir,prefix+"-large");
    TagDetailChecks.ui(t,()->{TagDetailChecks.set(activity,"analyzed",0);TagDetailChecks.call(activity,"updateProgress");});
  }
}
