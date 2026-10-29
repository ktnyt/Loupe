package jp.nano.rawgallery;

import android.os.SystemClock;
import android.util.Log;

/** Fixed event names and anonymous dimensions/timings only; no photo identifiers or error text. */
final class RawEditLog {
  static boolean enabled;
  static void event(String event,long ms,int width,int height){
    if(enabled)Log.i("LoupeRawEdit",event+" ms="+ms+" width="+width+" height="+height+" ns="+SystemClock.elapsedRealtimeNanos());
  }
  static void frame(long ticket,long inputNs){
    if(enabled)Log.i("LoupeRawEdit","adjust.frame ticket="+ticket+" input_ns="+inputNs+" commit_ns="+SystemClock.elapsedRealtimeNanos());
  }
  static void input(long ticket,long ns){
    if(enabled)Log.i("LoupeRawEdit","adjust.input ticket="+ticket+" ns="+ns);
  }
}
