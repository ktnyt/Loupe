package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.hardware.usb.UsbManager;
import android.view.View;
import android.widget.CompoundButton;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import jp.nano.rawgallery.camera.ImportProgress;
import jp.nano.rawgallery.camera.UsbLaunchSettings;

/** Real PackageManager/UI checks, without any USB device, permission request or photo import. */
final class CameraUsbLaunchChecks {
  static String run(Instrumentation t) throws Exception {
    var context=t.getTargetContext(); var pm=context.getPackageManager();
    require(!ImportProgress.INSTANCE.getActive(),"no active import");
    var alias=UsbLaunchSettings.component(context);
    int original=pm.getComponentEnabledSetting(alias);
    var prefs=context.getSharedPreferences("camera_import",0);
    var before=new HashMap<>(prefs.getAll());
    CameraImportActivity screen=null;
    try {
      require(original==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,"fresh alias state required");
      var info=pm.getActivityInfo(alias,PackageManager.MATCH_DISABLED_COMPONENTS|PackageManager.GET_META_DATA);
      require(!info.enabled && info.exported,"USB alias defaults off");
      require(info.targetActivity.equals("jp.nano.rawgallery.camera.CameraAttachActivity"),"validated target");
      int resource=info.metaData.getInt(UsbManager.ACTION_USB_DEVICE_ATTACHED);
      boolean reader=false;
      try(var xml=context.getResources().getXml(resource)) {
        for(int event=xml.getEventType();event!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT;event=xml.next())
          if(event==org.xmlpull.v1.XmlPullParser.START_TAG && xml.getName().equals("usb-device")
              && xml.getAttributeIntValue(null,"class",-1)==8 && xml.getAttributeIntValue(null,"subclass",-1)==6
              && xml.getAttributeIntValue(null,"protocol",-1)==80) reader=true;
      }
      require(reader,"actual SD reader filter metadata retained");
      require(candidates(t)==0,"no USB candidate before opening app");
      screen=(CameraImportActivity)t.startActivitySync(new Intent(context,CameraImportActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      final CameraImportActivity first=screen;
      TagDetailChecks.ui(t,()->control(first,"settingsButton").performClick());
      reveal(t,screen);capture(t,screen,"off");
      TagDetailChecks.ui(t,()->{
        var toggle=(CompoundButton)first.getWindow().getDecorView().findViewWithTag("usb-auto-open");
        require(toggle.isEnabled() && !toggle.isChecked(),"setting starts off");
        require(toggle.getMinimumHeight()>=Math.round(48*first.getResources().getDisplayMetrics().density),"48dp target");
        toggle.performClick();
        require(toggle.isChecked() && UsbLaunchSettings.isEnabled(context),"UI enables actual alias");
      });
      require(candidates(t)==1,"only opt-in alias resolves");capture(t,screen,"on");
      var monitor=t.addMonitor(CameraImportActivity.class.getName(),null,false);
      try {
        t.runOnMainSync(first::recreate);
        screen=(CameraImportActivity)monitor.waitForActivityWithTimeout(5000);
        require(screen!=null,"screen recreated");t.waitForIdleSync();
      } finally { t.removeMonitor(monitor); }
      final CameraImportActivity next=screen;reveal(t,next);
      TagDetailChecks.ui(t,()->{
        var toggle=(CompoundButton)next.getWindow().getDecorView().findViewWithTag("usb-auto-open");
        require(toggle.isChecked() && UsbLaunchSettings.isEnabled(context),"choice survives activity recreation");
        toggle.performClick();require(!toggle.isChecked() && !UsbLaunchSettings.isEnabled(context),"UI disables actual alias");
      });
      require(candidates(t)==0,"off removes USB candidate again");capture(t,screen,"off-again");
      var manual=pm.getActivityInfo(new android.content.ComponentName(context,CameraImportActivity.class),0);
      require(manual.enabled,"manual import remains enabled");
      require(before.equals(prefs.getAll()),"existing import preferences unchanged");
      require(!ImportProgress.INSTANCE.getActive(),"setting never starts import");
      return "PASS USB alias default-off before app start; real setting off/on/recreation/off follows PackageManager resolution, actual reader XML retained,48dp and captures; manual import/preferences preserved; no source or physical USB test";
    } finally {
      pm.setComponentEnabledSetting(alias,original,PackageManager.DONT_KILL_APP);
      if(screen!=null){CameraImportActivity close=screen;t.runOnMainSync(close::finish);t.waitForIdleSync();}
    }
  }
  static String choice(Instrumentation t,android.os.Bundle args) {
    var context=t.getTargetContext();
    require(!ImportProgress.INSTANCE.getActive(),"no active import");
    String choice=args.getString("choice","");
    switch(choice) {
      case "on": UsbLaunchSettings.setEnabled(context,true); break;
      case "off": UsbLaunchSettings.setEnabled(context,false); break;
      case "default": context.getPackageManager().setComponentEnabledSetting(UsbLaunchSettings.component(context),PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,PackageManager.DONT_KILL_APP); break;
      case "verify-on": case "verify-off": break;
      default: throw new IllegalArgumentException("Unknown USB choice check");
    }
    boolean wanted=choice.equals("on")||choice.equals("verify-on");
    require(UsbLaunchSettings.isEnabled(context)==wanted && candidates(t)==(wanted?1:0),"actual stored choice and resolver agree");
    return "PASS USB choice "+choice+" actual stored state and resolver verified; app UID only, no source operation";
  }
  private static int candidates(Instrumentation t) {
    var context=t.getTargetContext();var list=context.getPackageManager().queryIntentActivities(
        new Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).setPackage(context.getPackageName()),0);
    for(var r:list)require(r.activityInfo.name.equals(UsbLaunchSettings.component(context).getClassName()),"no legacy/other USB candidate");
    return list.size();
  }
  private static void reveal(Instrumentation t,CameraImportActivity screen) {
    TagDetailChecks.ui(t,()->{View v=screen.getWindow().getDecorView().findViewWithTag("usb-auto-open");v.requestRectangleOnScreen(new Rect(0,0,v.getWidth(),v.getHeight()),true);});
    t.waitForIdleSync();
  }
  private static void capture(Instrumentation t,CameraImportActivity screen,String state)throws Exception {
    File dir=new File(screen.getCacheDir(),"consistency-checks");dir.mkdirs();
    String name="usb-launch-"+screen.getResources().getConfiguration().screenWidthDp+"-"+screen.getResources().getConfiguration().fontScale+"-"+state;
    ConsistencyLayoutChecks.captureHardware(t,screen,screen.getWindow().getDecorView(),dir,name);
    var full=t.getUiAutomation().takeScreenshot();require(full!=null,"full display capture");
    try(FileOutputStream out=new FileOutputStream(new File(dir,name+"-full.png"))){full.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{full.recycle();}
  }
  private static View control(CameraImportActivity a,String name){try{Field f=CameraImportActivity.class.getDeclaredField(name);f.setAccessible(true);return(View)f.get(a);}catch(Exception e){throw new AssertionError(e);}}
  private static void require(boolean yes,String reason){if(!yes)throw new AssertionError(reason);}
}
