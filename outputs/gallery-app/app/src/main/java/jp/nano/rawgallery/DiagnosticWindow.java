package jp.nano.rawgallery;

import android.view.*;
import java.lang.reflect.*;

/** Dialog input uses a separate window; delegate every callback unchanged. */
final class DiagnosticWindow {
  static void install(Window window) {
    if (window == null || window.getCallback() == null) return;
    Window.Callback original = window.getCallback();
    window.setCallback((Window.Callback) Proxy.newProxyInstance(
        Window.Callback.class.getClassLoader(), new Class<?>[] {Window.Callback.class},
        (proxy, method, args) -> {
          boolean input = method.getName().equals("dispatchTouchEvent")
              || method.getName().equals("dispatchKeyEvent");
          if (input) DiagnosticLog.event("dialog.input", 0, "kind", method.getName());
          try { return method.invoke(original, args); }
          catch (InvocationTargetException e) { throw e.getCause(); }
        }));
  }
}
