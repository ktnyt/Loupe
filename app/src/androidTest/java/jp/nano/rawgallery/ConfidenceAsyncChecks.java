package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Deterministic asynchronous regressions; only the parent fixture's disposable DB/cache are used. */
final class ConfidenceAsyncChecks {
  static Object get(TagActivity a,String name){return TagDetailChecks.get(a,name);}
  static void set(TagActivity a,String name,Object value){TagDetailChecks.set(a,name,value);}
  static void call(TagActivity a,String name){TagDetailChecks.call(a,name);}
  static void ui(Instrumentation t,Runnable action){TagDetailChecks.ui(t,action);}
  static void idle(Instrumentation t,TagActivity a)throws Exception{ConfidenceUiChecks.idle(t,a);ConfidenceUiChecks.idle(t,a);}
  static void require(boolean ok,String message){TagDetailChecks.require(ok,message);}
  static void click(TagActivity a,String name){ConfidenceUiChecks.click(a,name);}
  static void useTag(Instrumentation t,TagActivity a,TagStore.Tag tag)throws Exception{
    ui(t,()->{call(a,"stopAnalysis");set(a,"tag",tag);call(a,"showTag");});idle(t,a);
  }
  static CountDownLatch blockAnalysis(TagActivity a)throws Exception{
    CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
    ((ExecutorService)get(a,"analysis")).submit(()->{started.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
    require(started.await(5,TimeUnit.SECONDS),"analysis gate entered");return release;
  }
  static void finishAnalysis(Instrumentation t,TagActivity a,CountDownLatch release)throws Exception{
    release.countDown();((ExecutorService)get(a,"analysis")).submit(()->{}).get(10,TimeUnit.SECONDS);idle(t,a);
  }
  static void run(Instrumentation t,TagActivity a,TagStore.Tag original,List<PhotoRepository.Photo> photos,List<String> ids,String db,String cache)throws Exception{
    Context context=t.getTargetContext();
    ui(t,()->call(a,"relearnCached"));idle(t,a);require(get(a,"pendingConfidence")!=null,"pending model prepared");
    ui(t,()->{SeekBar slider=ConfidenceUiChecks.slider((View)get(a,"root"));slider.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));slider.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT));});idle(t,a);
    double threshold=(double)get(a,"displayThreshold");ui(t,()->click(a,"該当しない 1"));idle(t,a);
    require((double)get(a,"displayThreshold")==threshold&&(double)get(a,"persistedThreshold")==threshold,"pending model does not roll threshold back");
    ui(t,()->click(a,"基準を教える"));idle(t,a);int index=(int)get(a,"teachingIndex");
    try(TagStore store=new TagStore(context,db)){
      TagJudgments.Entry old=store.load().latest(original.id,ids.get(index));store.record(UUID.randomUUID().toString(),original.id,ids.get(index),old==null?0:old.revision,TagJudgments.Value.EXCLUDED);
    }
    ui(t,()->click(a,"含める"));idle(t,a);require((int)get(a,"teachingIndex")==index,"conflict keeps current photo");
    TagJudgments refreshed=(TagJudgments)get(a,"judgments");require(refreshed.value(original.id,ids.get(index))==TagJudgments.Value.EXCLUDED,"conflict refreshes human revision");
    ui(t,()->click(a,"含める"));idle(t,a);
    try(TagStore store=new TagStore(context,db)){require(store.load().value(original.id,ids.get(index))==TagJudgments.Value.ASSIGNED,"conflict retry succeeds");}

    TagStore.Tag insufficient,missing,finishing;
    try(TagStore store=new TagStore(context,db)){
      insufficient=store.create("隔離・最後の負例取消");store.record("insufficient-yes",insufficient.id,ids.get(0),0,TagJudgments.Value.ASSIGNED);new TagScores(store).enable(insufficient.id);
      missing=store.create("隔離・解析中の画面切替");store.record("missing-yes",missing.id,ids.get(0),0,TagJudgments.Value.ASSIGNED);
      finishing=store.create("隔離・保存中の解析完了");store.record("finishing-yes",finishing.id,ids.get(0),0,TagJudgments.Value.ASSIGNED);store.record("finishing-no",finishing.id,ids.get(1),0,TagJudgments.Value.EXCLUDED);
    }
    useTag(t,a,insufficient);ui(t,()->click(a,"基準を教える"));idle(t,a);ui(t,()->click(a,"含めない"));idle(t,a);
    try(TagStore store=new TagStore(context,db)){require(new TagScores(store).hasModel(insufficient.id),"one positive and one negative form model");}
    ui(t,()->click(a,"取り消し"));idle(t,a);
    require((boolean)get(a,"learningUnavailable")&&(int)get(a,"exampleNegative")==0,"last negative undo is insufficient");
    ui(t,a::onBackPressed);idle(t,a);ui(t,()->require(((StatusView)get(a,"learningStatus")).getContentDescription().toString().contains("不足"),"old result explicitly marked insufficient"));
    try(TagStore store=new TagStore(context,db)){require(new TagScores(store).hasModel(insufficient.id),"insufficient fit preserves completed result");}

    useTag(t,a,missing);CountDownLatch first=blockAnalysis(a);
    try{ui(t,()->click(a,"写真を解析"));idle(t,a);TagAnalysisProgressChecks.verify(t,a);ui(t,()->click(a,"写真を追加"));idle(t,a);require(get(a,"assignmentStatus")==null,"analysis notification view detached");finishAnalysis(t,a,first);require(!(boolean)get(a,"autoPending"),"missing examples finish safely outside overview");}finally{first.countDown();}
    ui(t,()->call(a,"stopAnalysis"));

    useTag(t,a,finishing);CountDownLatch second=blockAnalysis(a);
    try{ui(t,()->click(a,"写真を解析"));idle(t,a);ui(t,()->set(a,"busy",true));finishAnalysis(t,a,second);
      try(TagStore store=new TagStore(context,db)){require(new TagScores(store).hasModel(finishing.id),"analysis completion not lost during save");}
      ui(t,()->{set(a,"busy",false);set(a,"pendingConfidence",null);call(a,"loadResults");});idle(t,a);require(((TagScores.Snapshot)get(a,"confidence")).model!=null,"results reload completed DB model even without callback snapshot");
    }finally{second.countDown();ui(t,()->set(a,"busy",false));}

    try(TagStore store=new TagStore(context,db)){store.automation(finishing.id,false,true);}
    useTag(t,a,finishing);ui(t,()->require(((StatusView)get(a,"learningStatus")).getContentDescription().toString().contains("停止"),"clear state explains disabled learning"));

    CountDownLatch third=blockAnalysis(a);
    try{ui(t,()->click(a,"写真を解析"));idle(t,a);ui(t,()->require(!((StatusView)get(a,"learningStatus")).getContentDescription().toString().contains("停止"),"restart clears stopped status during analysis"));finishAnalysis(t,a,third);ui(t,()->require(((StatusView)get(a,"learningStatus")).getContentDescription().toString().contains("新しい結果"),"prepared model reports ready after restart"));}
    finally{third.countDown();}
    ui(t,()->call(a,"loadResults"));idle(t,a);

    TagActivity restarted=(TagActivity)t.startActivitySync(TagFixtureIsolation.intent(context,db,cache));
    try{idle(t,restarted);TagFixtureIsolation.assertGuard(restarted);ui(t,()->{restarted.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);set(restarted,"tag",original);((List<PhotoRepository.Photo>)get(restarted,"photos")).addAll(photos);((List<String>)get(restarted,"photoIds")).addAll(ids);call(restarted,"showTag");});idle(t,restarted);require((double)get(restarted,"displayThreshold")==threshold,"new Activity restores persisted threshold");require(((TagScores.Snapshot)get(restarted,"confidence")).model!=null,"new Activity restores saved scores");}
    finally{ui(t,()->{restarted.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);restarted.finish();});TagFixtureIsolation.cleanup(restarted);}
  }
}
