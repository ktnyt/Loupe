package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Switch;
import java.util.concurrent.ExecutorService;

/** Never enables synchronization or deletion; exercises the pre-confirmation boundary. */
final class SyncControlChecks {
  static String run(Instrumentation test) throws Exception {
    SharedPreferences prefs=test.getTargetContext().getSharedPreferences("settings",0);
    boolean sync=prefs.getBoolean("syncEnabled",false),cleanup=prefs.getBoolean("cleanupEnabled",false);
    int checked=0;
    for(String field:new String[]{"syncState","cleanupState"}){
      boolean saved=field.equals("syncState")?sync:cleanup;
      if(saved)continue; // Do not change a user's active setting just to test it.
      SyncActivity activity=(SyncActivity)test.startActivitySync(new Intent(test.getTargetContext(),SyncActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try{
        ((ExecutorService)AlbumTagEntryChecks.field(activity,"worker")).submit(()->{}).get();test.waitForIdleSync();
        Switch toggle=(Switch)AlbumTagEntryChecks.field(activity,field);
        test.runOnMainSync(()->{
          // Native drag ends by calling setChecked, bypassing OnClickListener.
          toggle.setChecked(true);
          if(toggle.isChecked())throw new AssertionError("unconfirmed switch remained on");
          if(prefs.getBoolean(field.equals("syncState")?"syncEnabled":"cleanupEnabled",false))throw new AssertionError("setting enabled before confirmation");
        });
        ((ExecutorService)AlbumTagEntryChecks.field(activity,"worker")).submit(()->{}).get();test.waitForIdleSync();
        checked++;
      }finally{test.runOnMainSync(activity::finish);test.waitForIdleSync();}
    }
    if(prefs.getBoolean("syncEnabled",false)!=sync||prefs.getBoolean("cleanupEnabled",false)!=cleanup)throw new AssertionError("settings changed");
    return "PASS pre-confirmation state-change path: "+checked+" switches checked; active settings skipped. No confirmation accepted, no upload/deletion enabled. Physical drag gesture not exercised.\n";
  }
}
