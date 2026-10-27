package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Switch;
import java.util.concurrent.ExecutorService;

/** Never enables synchronization or deletion; exercises the pre-confirmation boundary. */
final class SyncControlChecks {
  static String run(Instrumentation test) throws Exception {
    for(Integer days:new Integer[]{null,7,30,90}){
      SharedPreferences isolated=(SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(SyncControlChecks.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
        if(method.getName().equals("getInt")&&"retentionDays".equals(args[0]))return days==null?args[1]:days;
        throw new AssertionError("unexpected isolated preference access");
      });
      if(!("撮影日から"+(days==null?7:days)+"日").equals(SyncActivity.retentionLabel(isolated)))throw new AssertionError("isolated retention default/explicit period differs from processing policy");
    }
    SharedPreferences prefs=test.getTargetContext().getSharedPreferences("settings",0);
    boolean sync=prefs.getBoolean("syncEnabled",false),cleanup=prefs.getBoolean("cleanupEnabled",false);
    int checked=0;
    for(String field:new String[]{"syncState","cleanupState"}){
      boolean saved=field.equals("syncState")?sync:cleanup;
      if(saved)continue; // Do not change a user's active setting just to test it.
      SyncActivity activity=(SyncActivity)test.startActivitySync(new Intent(test.getTargetContext(),SyncActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try{
        ((ExecutorService)AlbumTagEntryChecks.field(activity,"worker")).submit(()->{}).get();test.waitForIdleSync();
        android.widget.TextView retention=(android.widget.TextView)AlbumTagEntryChecks.field(activity,"retention");
        test.runOnMainSync(()->{if(!("撮影日から"+prefs.getInt("retentionDays",7)+"日").contentEquals(retention.getText()))throw new AssertionError("retention display differs from processing policy");});
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
    return "PASS four isolated retention periods (unset=7, explicit7/30/90); actual display matches stored/processing period; pre-confirmation state-change path: "+checked+" switches checked; active settings skipped. No confirmation accepted, no upload/deletion enabled. Physical drag gesture not exercised.\n";
  }
}
