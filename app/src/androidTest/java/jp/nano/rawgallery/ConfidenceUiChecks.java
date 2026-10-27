package jp.nano.rawgallery;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Exercises production UI actions with generated images and named disposable databases only. */
final class ConfidenceUiChecks {
  static void require(boolean ok,String message){TagDetailChecks.require(ok,message);}
  static Object get(TagActivity a,String name){return TagDetailChecks.get(a,name);}
  static void call(TagActivity a,String name){TagDetailChecks.call(a,name);}
  static void ui(Instrumentation t,Runnable r){TagDetailChecks.ui(t,r);}
  static SeekBar slider(View v){if(v instanceof SeekBar)return (SeekBar)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){SeekBar found=slider(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;}
  static void click(TagActivity a,String title){Button found=TagDetailChecks.button((View)get(a,"root"),title);if(found==null&&get(a,"teachingButtons")!=null)for(Button b:(List<Button>)get(a,"teachingButtons"))if(b.getText().toString().trim().equals(title))found=b;require(found!=null&&found.isEnabled(),"enabled action "+title);found.performClick();}
  static void idle(Instrumentation t,TagActivity a)throws Exception{TagDetailChecks.idle(t,a);TagDetailChecks.idle(t,a);}
  static String run(Instrumentation t,int width)throws Exception{
    Context context=t.getTargetContext();String db="confidence-ui-"+UUID.randomUUID()+".db",cache="confidence-features-"+UUID.randomUUID()+".db";
    List<File> files=new ArrayList<>();List<PhotoRepository.Photo> photos=new ArrayList<>();List<String> ids=new ArrayList<>();TagStore.Tag tag;TagJudgments initial;TagScores.Snapshot snapshot;
    try(TagStore store=new TagStore(context,db);PhotoFeatures.Cache features=new PhotoFeatures.Cache(context,cache)){
      tag=store.create("ミカヅキインコ");
      for(int i=0;i<12;i++){
        File file=new File(context.getCacheDir(),"confidence-synthetic-"+i+".jpg");files.add(file);Bitmap image=Bitmap.createBitmap(360,240,Bitmap.Config.ARGB_8888);image.eraseColor(i%2==0?0xff718774:0xff80748c);Canvas c=new Canvas(image);Paint paint=new Paint(3);paint.setColor(0xffe8e9d9);c.drawCircle(180,115,45,paint);paint.setColor(0xff313b35);c.drawCircle(190,105,4,paint);try(FileOutputStream out=new FileOutputStream(file)){image.compress(Bitmap.CompressFormat.JPEG,90,out);}image.recycle();
        PhotoRepository.Photo photo=new PhotoRepository.Photo(Uri.fromFile(file),"合成"+i+".jpg","",file.length(),1792298040L,1792298040000L);photos.add(photo);String id=store.photoId(photo,true);ids.add(id);
        byte[] vector=new byte[1024];vector[0]=(byte)(i%2==0?120:10);vector[1]=(byte)(i%2==0?10:120);
        ContentValues cv=new ContentValues();cv.put("source",TagStore.source(photo));cv.put("pipeline",PhotoFeatures.PIPELINE);cv.put("vector",vector);features.getWritableDatabase().insertOrThrow("features",null,cv);
      }
      store.record("yes",tag.id,ids.get(0),0,TagJudgments.Value.ASSIGNED);store.record("no",tag.id,ids.get(1),0,TagJudgments.Value.EXCLUDED);
      new TagScores(store).enable(tag.id);try(PhotoFeatures cached=new PhotoFeatures(context,cache)){require(new TagScores(store).recompute(tag.id,cached,()->false),"seed learned snapshot");}
      initial=store.load();snapshot=new TagScores(store).snapshot(tag.id);
    }
    TagActivity a=(TagActivity)t.startActivitySync(TagFixtureIsolation.intent(context,db,cache));TagDetailChecks.width=width;
    try{
      idle(t,a);TagFixtureIsolation.assertGuard(a);TagStore.Tag selected=tag;TagJudgments labels=initial;TagScores.Snapshot learned=snapshot;
      ui(t,()->{a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);TagDetailChecks.set(a,"tag",selected);((List<PhotoRepository.Photo>)get(a,"photos")).clear();((List<PhotoRepository.Photo>)get(a,"photos")).addAll(photos);((List<String>)get(a,"photoIds")).clear();((List<String>)get(a,"photoIds")).addAll(ids);TagDetailChecks.set(a,"judgments",labels);TagDetailChecks.set(a,"confidence",learned);call(a,"renderTag");});idle(t,a);
      File dir=new File(a.getCacheDir(),"consistency-checks");dir.mkdirs();String prefix="tag-confidence-"+width+"-"+a.getResources().getConfiguration().fontScale;
      ui(t,()->{require(((List<?>)get(a,"browsePhotos")).size()==6,"threshold seeded membership");TagDetailChecks.inspect((View)get(a,"root"));});
      ConsistencyLayoutChecks.captureHardware(t,a,(View)get(a,"root"),dir,prefix+"-normal");
      long epoch;try(TagStore store=new TagStore(context,db)){epoch=new TagScores(store).preferences(tag.id).epoch;}
      ui(t,()->{SeekBar b=slider((View)get(a,"root"));b.requestFocus();b.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MOVE_END));b.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MOVE_END));});idle(t,a);
      // Android SeekBar supports arrow keys on all supported platform versions.
      ui(t,()->{SeekBar b=slider((View)get(a,"root"));b.requestFocus();b.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));b.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT));});idle(t,a);
      try(TagStore store=new TagStore(context,db)){TagScores.Preferences prefs=new TagScores(store).preferences(tag.id);require(prefs.threshold!=.6,"threshold action persists");require(prefs.epoch==epoch,"slider does not train");require(new TagScores(store).snapshot(tag.id).scores.size()==12,"below threshold scores retained");}
      double persisted;try(TagStore store=new TagStore(context,db)){persisted=new TagScores(store).preferences(tag.id).threshold;store.getWritableDatabase().execSQL("CREATE TRIGGER fail_threshold BEFORE UPDATE ON confidence_preferences BEGIN SELECT RAISE(ABORT,'fixture'); END");}
      ui(t,()->{SeekBar b=slider((View)get(a,"root"));b.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT));b.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_LEFT));});idle(t,a);
      require(Math.abs((double)get(a,"displayThreshold")-persisted)<1e-9,"failed threshold restores current value");
      try(TagStore store=new TagStore(context,db)){require(new TagScores(store).preferences(tag.id).threshold==persisted,"failed threshold not persisted");store.getWritableDatabase().execSQL("DROP TRIGGER fail_threshold");}
      ui(t,()->click(a,"基準を教える"));idle(t,a);int first=(int)get(a,"teachingIndex");
      ui(t,()->{require((int)get(a,"browseMode")==3,"teaching entry");require((int)get(a,"teachingTotal")==10,"denominator counts only candidates");TagDetailChecks.inspect((View)get(a,"root"));});
      ConsistencyLayoutChecks.captureHardware(t,a,(View)get(a,"root"),dir,prefix+"-teaching");
      ui(t,()->click(a,"保留"));idle(t,a);require((int)get(a,"teachingIndex")!=first,"pass advances without repeat");
      try(TagStore store=new TagStore(context,db)){require(store.load().history.size()==2,"pass not a judgment");store.getWritableDatabase().execSQL("CREATE TRIGGER fail_fixture BEFORE INSERT ON history BEGIN SELECT RAISE(ABORT,'fixture'); END");}
      int failed=(int)get(a,"teachingIndex");ui(t,()->click(a,"含める"));idle(t,a);
      ui(t,()->{require((int)get(a,"teachingIndex")==failed,"failed save keeps image");require(!(boolean)get(a,"busy"),"retry enabled");});
      ConsistencyLayoutChecks.captureHardware(t,a,(View)get(a,"root"),dir,prefix+"-save-failed");
      try(TagStore store=new TagStore(context,db)){store.getWritableDatabase().execSQL("DROP TRIGGER fail_fixture");}
      ui(t,()->click(a,"含める"));idle(t,a);
      try(TagStore store=new TagStore(context,db)){require(store.load().value(tag.id,ids.get(failed))==TagJudgments.Value.ASSIGNED,"retry saved positive");require(new TagScores(store).snapshot(tag.id).model.positives==2,"edit retrains from cached vectors");}
      int afterSave=(int)get(a,"teachingIndex");idle(t,a);require((int)get(a,"teachingIndex")==afterSave,"learning does not replace current image");
      try(TagStore store=new TagStore(context,db)){store.getWritableDatabase().execSQL("CREATE TRIGGER fail_undo BEFORE INSERT ON history BEGIN SELECT RAISE(ABORT,'fixture'); END");}
      ui(t,()->click(a,"取り消し"));idle(t,a);require((int)get(a,"teachingIndex")==afterSave,"failed undo keeps current image");
      ui(t,()->require(((List<Button>)get(a,"teachingButtons")).get(3).getTag()==StatusView.Symbol.FAILED,"undo failure is visible"));
      ConsistencyLayoutChecks.captureHardware(t,a,(View)get(a,"root"),dir,prefix+"-undo-failed");
      try(TagStore store=new TagStore(context,db)){store.getWritableDatabase().execSQL("DROP TRIGGER fail_undo");}
      ui(t,()->click(a,"取り消し"));idle(t,a);require((int)get(a,"teachingIndex")==failed,"undo returns to judged image");
      try(TagStore store=new TagStore(context,db)){require(store.load().value(tag.id,ids.get(failed))==TagJudgments.Value.UNDECIDED,"undo persisted");require(new TagScores(store).snapshot(tag.id).model.positives==1,"undo retrains");}
      for(int remaining=0;remaining<12&&(int)get(a,"teachingIndex")>=0;remaining++){ui(t,()->click(a,"保留"));idle(t,a);}
      require((int)get(a,"teachingIndex")==-1&&(int)get(a,"teachingCount")==10,"completion reaches ten candidates");
      ui(t,()->{TagDetailChecks.set(a,"busy",true);a.onBackPressed();require((int)get(a,"browseMode")==3,"busy Back guard");TagDetailChecks.set(a,"busy",false);a.onBackPressed();});idle(t,a);
      ui(t,()->{TagDetailChecks.set(a,"tag",new TagStore.Tag(selected.id,"旅先で見つけたミカヅキインコと長い名前のタグ"));call(a,"renderTag");});idle(t,a);ui(t,()->TagDetailChecks.inspect((View)get(a,"root")));ConsistencyLayoutChecks.captureHardware(t,a,(View)get(a,"root"),dir,prefix+"-long");
      ConfidenceAsyncChecks.run(t,a,selected,photos,ids,db,cache);
      return "PASS tag confidence UI with generated photos and disposable databases: threshold persists without training; all scores retained; teaching pass/save failure/retry/undo failure/retry and correct completion count; cached learning and current-image stability; pending-threshold race, human conflict retry, insufficient undo, detached analysis notifications, save/analysis completion, DB result reload and fresh Activity restoration; busy Back guard; real layout and PixelCopy. No user judgments modified.\n";
    }finally{ui(t,()->{a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);a.finish();});TagFixtureIsolation.cleanup(a);for(File f:files)f.delete();context.deleteDatabase(db);context.deleteDatabase(cache);}
  }
}
