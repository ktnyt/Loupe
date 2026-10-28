package jp.nano.rawgallery;

import android.util.Log;

/** Fixed event names and aggregate dimensions/timings only; no input identifiers or error text. */
final class RawEditLog {
  static void event(String event,long ms,int width,int height){
    Log.i("LoupeRawEdit",event+" ms="+ms+" width="+width+" height="+height);
  }
}
