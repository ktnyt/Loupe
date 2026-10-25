package jp.nano.rawgallery;

import android.app.Application;

public final class DiagnosticApplication extends Application {
  @Override public void onCreate() { super.onCreate(); DiagnosticLog.init(this); }
}
