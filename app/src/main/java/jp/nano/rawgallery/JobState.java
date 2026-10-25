package jp.nano.rawgallery;

import android.content.SharedPreferences;

/** Machine-readable observations; never infer state from localized status messages. */
final class JobState {
  enum Kind { UNKNOWN, RUNNING, WAITING, PAUSED, COMPLETE, PARTIAL, FAILED, AUTH_REQUIRED }
  final Kind kind;
  final long updatedAt;
  final boolean countsKnown;
  final int ready, total, failed;
  private JobState(Kind kind,long updatedAt,boolean known,int ready,int total,int failed) {
    this.kind=kind;this.updatedAt=updatedAt;countsKnown=known;
    this.ready=known?ready:0;this.total=known?total:0;this.failed=known?failed:0;
  }
  static SharedPreferences.Editor write(SharedPreferences.Editor edit,String prefix,Kind kind,long at) {
    return edit.putString(prefix+"StateCode",kind.name()).putLong(prefix+"StateAt",at);
  }
  static JobState read(SharedPreferences prefs,String prefix,boolean running,boolean withCounts) {
    Kind saved;
    try { saved=Kind.valueOf(prefs.getString(prefix+"StateCode","UNKNOWN")); }
    catch(IllegalArgumentException e) { saved=Kind.UNKNOWN; }
    long at=prefs.getLong(prefix+"StateAt",0);
    boolean recorded=saved!=Kind.UNKNOWN&&at>0;
    Kind visible=!recorded||saved==Kind.RUNNING&&!running?Kind.UNKNOWN:saved;
    int ready=prefs.getInt("ready",0),total=prefs.getInt("total",0),failed=prefs.getInt("failed",0);
    boolean known=withCounts&&recorded&&prefs.getBoolean(prefix+"CountsKnown",false)&&prefs.contains("ready")&&prefs.contains("total")&&prefs.contains("failed")
        &&ready>=0&&total>=ready&&failed>=0&&failed<=total;
    return new JobState(visible,recorded?at:0,known,ready,total,failed);
  }
}
