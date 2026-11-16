package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import java.io.File;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import jp.nano.rawgallery.camera.ImportProgress;

/** Synthetic progress only: no import service, source reads, photo or profile writes. */
final class CameraImportProgressChecks {
  static String run(Instrumentation t) throws Exception {
    ImportProgress p = ImportProgress.INSTANCE;
    require(!p.getActive(), "requires no live import");
    var before = new LinkedHashMap<Field,Object>();
    for (String name : new String[]{"photo","active","model","done","total","lastMessage","waiting","status","stopRequested"}) {
      Field field = ImportProgress.class.getDeclaredField(name); field.setAccessible(true); before.put(field, field.get(null));
    }
    CameraImportActivity screen = null;
    try {
      screen = (CameraImportActivity)t.startActivitySync(new Intent(t.getTargetContext(), CameraImportActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      final CameraImportActivity activity = screen;
      var render = CameraImportActivity.class.getDeclaredMethod("renderStatus"); render.setAccessible(true);
      File dir = new File(screen.getCacheDir(), "consistency-checks"); dir.mkdirs();
      String size = screen.getResources().getConfiguration().screenWidthDp + "-" + screen.getResources().getConfiguration().fontScale;
      Runnable refresh = () -> { try { render.invoke(activity); } catch (Exception e) { throw new AssertionError(e); } };
      TagDetailChecks.ui(t, () -> {
        p.connecting("接続中"); p.begin("Synthetic SD"); p.photoListing(317, -1); refresh.run();
        require(ImportProgressPresentation.current().text().contains("総数を確認中"), "unknown total is explicit");
        require(!ImportProgressPresentation.current().text().contains("新規保存"), "listing is not save planning");
      });
      capture(t, screen, dir, size + "-listing");
      TagDetailChecks.ui(t, () -> {
        p.photoHistory(4800, 4800, 4800); p.update(4800,4800); refresh.run();
        require(p.getPhoto().getStage() == ImportProgress.PhotoStage.HISTORY, "legacy callback preserves rich phase");
        String text = ImportProgressPresentation.current().text();
        require(text.contains("履歴を確認中") && text.contains("保存済み 4800件") && !text.contains("新規保存 4800"), "known count is history");
        LibraryTabs tabs = new LibraryTabs(activity, false, true, ignored -> {}, () -> {}, () -> {});
        tabs.setCameraState(true,"Synthetic SD",true,4800,4800,false,ImportProgressPresentation.current());
        require(ViewCompat.getStateDescription(tabs.destinationView(2)).toString().contains("履歴を確認中"), "tab AX shares phase");
        require(!tabs.getBadge(3).hasNumber(), "history is not numbered save badge");
        tabs.setEnabled(false); require(!tabs.destinationView(2).performLongClick(), "selection blocks quick import");
      });
      capture(t, screen, dir, size + "-history-4800");
      TagDetailChecks.ui(t, () -> {
        p.photoContent(0, 0, 0, 0, 0, 0); p.photoTerminal("complete", "完了"); refresh.run();
        String text = ImportProgressPresentation.current().text();
        require(text.contains("新規保存 0件") && text.contains("保存済み 4800件") && text.contains("読取 0 B"), "4800 known, no reads and no new saves are distinct");
      });
      capture(t, screen, dir, size + "-known-4800-zero-saved");
      TagDetailChecks.ui(t, () -> {
        p.begin("Synthetic SD"); p.photoHistory(4802, 4802, 4800);
        p.photoContent(0, 2, 0, 0, -1, -1); refresh.run();
        String text = ImportProgressPresentation.current().text();
        require(text.contains("内容を確認中") && text.contains("要確認 0 / 2件") && text.contains("新規保存 0件"), "two candidates not planned new saves");
        require(!text.contains("読取"), "unknown bytes not zero");
      });
      capture(t, screen, dir, size + "-content-two");
      TagDetailChecks.ui(t, () -> {
        p.photoContent(2, 2, 0, 2, 4096, 0); p.photoTerminal("complete", "完了"); p.finish("完了"); refresh.run();
        require(ImportProgressPresentation.current().text().contains("新規保存 0件"), "unchanged import saves zero");
        require(ImportProgressPresentation.current().text().contains("保存済み 4802件"), "known and content matches retained");
      });
      capture(t, screen, dir, size + "-zero-saved");
      TagDetailChecks.ui(t, () -> {
        p.begin("Synthetic SD"); require(p.getPhoto() == null, "begin resets rich snapshot");
        p.photoContent(1, 2, 1, 0, 4096, 4096); p.requestStop(); p.finish("中止"); refresh.run();
        require(p.getPhoto().getStage() == ImportProgress.PhotoStage.CANCELLED, "finish cannot turn cancel into completion");
        require(ImportProgressPresentation.current().text().contains("新規保存 1件"), "cancel retains actual saves");
      });
      capture(t, screen, dir, size + "-cancelled");
      TagDetailChecks.ui(t, () -> {
        p.connecting("接続中"); p.begin("Synthetic SD"); p.photoContent(1,2,0,0,4096,-1);
        p.photoTerminal("failed","合成エラー"); p.finish("合成エラー"); refresh.run();
        require(p.getPhoto().getStage() == ImportProgress.PhotoStage.FAILED, "finish retains failure");
        require(((TextView)activity.getWindow().getDecorView().findViewWithTag("camera-import-stage")).getText().toString().contains("失敗"), "failure visible");
      });
      capture(t, screen, dir, size + "-failed");
      TagDetailChecks.ui(t, () -> {
        p.connecting("接続中"); p.begin("Synthetic SD");
        p.photoHistory(4802,4802,4800); p.photoContent(1,2,1,0,4096,4096);
        p.photoTerminal("failed","試行の合成エラー");
        p.waiting("カメラの再接続を待っています"); refresh.run();
        require(p.getActive() && p.getWaiting() && !p.getStopRequested(), "retry retains active cancellable operation");
        var presentation = ImportProgressPresentation.current();
        require(presentation.primary.equals("カメラの再接続を待っています"), "retry message supersedes prior failed rich phase");
        require(presentation.secondary.contains("新規保存 1件") && !presentation.secondary.contains("新規保存 0件"), "retry retains published actual saves");
        require(presentation.total < 0, "retry uses indeterminate progress instead of prior failed attempt total");
        require(control(activity,"stop").getVisibility() == View.VISIBLE && control(activity,"stop").isEnabled(), "retry exposes enabled stop");
        require(control(activity,"run").getVisibility() == View.GONE, "retry cannot start a second operation");
        p.attempting("カメラに再接続しています"); refresh.run();
        require(ImportProgressPresentation.current().primary.equals("カメラに再接続しています"), "attempt message supersedes prior snapshot");
      });
      capture(t, screen, dir, size + "-retry-wait");
      TagDetailChecks.ui(t, () -> {
        // The service republishes operation-wide counters after a retry starts scanning.
        p.begin("Synthetic SD"); p.photoHistory(4802,4802,4800);
        p.photoContent(2,2,2,0,8192,8192); p.photoTerminal("complete","完了"); refresh.run();
        require(ImportProgressPresentation.current().text().contains("新規保存 2件"), "retry success displays cumulative actual saves");
        require(!p.getActive() && !p.getWaiting(), "overall success ends retry ownership");
      });
      capture(t, screen, dir, size + "-retry-success");
      TagDetailChecks.ui(t, () -> {
        p.connecting("接続中"); p.begin("Synthetic SD"); p.photoContent(1,2,1,0,4096,4096);
        p.requestStop(); p.waiting("再接続待ち"); p.attempting("再接続中"); refresh.run();
        require(p.getStopRequested(), "retry never clears concurrent stop");
        require(ImportProgressPresentation.current().primary.contains("中止しています"), "concurrent stop remains visible");
        require(!control(activity,"stop").isEnabled() && control(activity,"run").getVisibility() == View.GONE, "stopping operation blocks duplicate start");
        p.photoTerminal("cancelled","中止"); p.waiting("再接続待ち"); p.attempting("再接続中");
        require(!p.getActive() && !p.getWaiting(), "stopped terminal operation is never revived");
        p.connecting("接続中"); p.begin("Synthetic SD"); p.photoTerminal("cancelled","中止");
        p.waiting("再接続待ち"); require(!p.getActive(), "explicit cancelled result cannot be revived without stop flag");
      });
      return "PASS synthetic listing unknown total, 4800 known history, two content candidates, zero actual saves, bytes unknown, cancel/failure, failed attempt/retry/cumulative success and concurrent stop, shared tab AX and selection lock; native captures " + size + ". No physical USB/TalkBack verification.";
    } finally {
      if (screen != null) { CameraImportActivity activity = screen; t.runOnMainSync(activity::finish); t.waitForIdleSync(); }
      t.runOnMainSync(() -> { for (var entry : before.entrySet()) try { entry.getKey().set(null,entry.getValue()); } catch (Exception e) { throw new AssertionError(e); } });
    }
  }
  private static void capture(Instrumentation t, CameraImportActivity screen, File dir, String name) throws Exception {
    ConsistencyLayoutChecks.captureHardware(t,screen,screen.getWindow().getDecorView(),dir,"import-progress-"+name);
  }
  private static View control(CameraImportActivity activity, String name) {
    try { Field field = CameraImportActivity.class.getDeclaredField(name); field.setAccessible(true); return (View)field.get(activity); }
    catch (Exception e) { throw new AssertionError(e); }
  }
  private static void require(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
