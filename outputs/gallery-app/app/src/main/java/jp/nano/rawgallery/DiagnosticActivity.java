package jp.nano.rawgallery;

import android.app.Activity;
import android.os.Bundle;
import android.view.*;

/** Raw input has no text/coordinates; semantic actions are logged separately. */
public class DiagnosticActivity extends Activity {
  @Override public void onCreate(Bundle state) {
    DiagnosticLog.init(this);
    DiagnosticLog.event("activity.create", 0, "screen", getClass().getSimpleName());
    super.onCreate(state);
  }
  @Override protected void onResume() {
    super.onResume();
    DiagnosticLog.event("activity.resume", 0, "screen", getClass().getSimpleName());
  }
  @Override protected void onPause() {
    DiagnosticLog.event("activity.pause", 0, "screen", getClass().getSimpleName());
    super.onPause();
  }
  @Override protected void onDestroy() {
    DiagnosticLog.event("activity.destroy", 0, "screen", getClass().getSimpleName());
    super.onDestroy();
  }
  @Override public boolean dispatchTouchEvent(MotionEvent e) {
    long id = DiagnosticLog.id(), old = DiagnosticLog.use(id);
    DiagnosticLog.event("input.touch", id, "action", e.getActionMasked(),
        "input_uptime_ms", e.getEventTime(), "screen", getClass().getSimpleName());
    try { return super.dispatchTouchEvent(e); }
    finally { DiagnosticLog.event("input.dispatch_end", id); DiagnosticLog.use(old); }
  }
  @Override public boolean dispatchKeyEvent(KeyEvent e) {
    DiagnosticLog.event("input.key", 0, "action", e.getAction(), "code", e.getKeyCode() == KeyEvent.KEYCODE_BACK ? KeyEvent.KEYCODE_BACK : 0);
    return super.dispatchKeyEvent(e);
  }
  @Override public void onBackPressed() {
    DiagnosticLog.event("input.back", 0);
    super.onBackPressed();
  }
}
