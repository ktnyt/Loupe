package jp.nano.rawgallery;

import android.app.Application;
import android.app.Activity;
import android.os.Bundle;

public final class DiagnosticApplication extends Application {
  @Override public void onCreate() {
    super.onCreate();
    DiagnosticLog.init(this);
    registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
      public void onActivityStarted(Activity a) { PreviewPreparationJob.enter(); }
      public void onActivityStopped(Activity a) { PreviewPreparationJob.leave(DiagnosticApplication.this); }
      public void onActivityCreated(Activity a, Bundle b) {}
      public void onActivityResumed(Activity a) {}
      public void onActivityPaused(Activity a) {}
      public void onActivitySaveInstanceState(Activity a, Bundle b) {}
      public void onActivityDestroyed(Activity a) {}
    });
  }
}
