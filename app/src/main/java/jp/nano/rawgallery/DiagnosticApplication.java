package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

public final class DiagnosticApplication extends Application {
  @Override
  public void onCreate() {
    super.onCreate();
    Thread.setDefaultUncaughtExceptionHandler(
        new DiagnosticCrashHandler(
            new java.io.File(getFilesDir(), "diagnostics"),
            BuildConfig.VERSION_CODE,
            Thread.getDefaultUncaughtExceptionHandler()));
    DiagnosticLog.init(this);
    GenerationIndicator.install(this);
    registerActivityLifecycleCallbacks(
        new ActivityLifecycleCallbacks() {
          public void onActivityStarted(Activity a) {
            PreviewPreparationJob.enter();
          }

          public void onActivityStopped(Activity a) {
            PreviewPreparationJob.leave(DiagnosticApplication.this);
          }

          public void onActivityCreated(Activity a, Bundle b) {}

          public void onActivityResumed(Activity a) {
            E2SyncJob.recoverOnOpen(DiagnosticApplication.this);
          }

          public void onActivityPaused(Activity a) {}

          public void onActivitySaveInstanceState(Activity a, Bundle b) {}

          public void onActivityDestroyed(Activity a) {}
        });
  }
}
