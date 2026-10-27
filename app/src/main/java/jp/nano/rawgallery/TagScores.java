package jp.nano.rawgallery;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Atomic learned snapshots in tags.db, separate from human judgments and display thresholds. */
final class TagScores {
  private final TagStore store;
  TagScores(TagStore store) { this.store = store; }
  static String pipeline() { return PhotoFeatures.PIPELINE + ":" + TagConfidence.VERSION; }

  static void createTables(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE confidence_preferences(tag TEXT PRIMARY KEY, threshold REAL NOT NULL DEFAULT 0.60 CHECK(threshold>=0 AND threshold<=1), enabled INTEGER NOT NULL DEFAULT 0, epoch INTEGER NOT NULL DEFAULT 0)");
    db.execSQL("CREATE TABLE confidence_models(tag TEXT PRIMARY KEY, revision INTEGER NOT NULL, pipeline TEXT NOT NULL, parameters BLOB NOT NULL)");
    db.execSQL("CREATE TABLE confidence_scores(tag TEXT NOT NULL, photo TEXT NOT NULL, score REAL NOT NULL CHECK(score>=0 AND score<=1), PRIMARY KEY(tag,photo))");
  }

  static final class Preferences {
    final double threshold;
    final boolean enabled;
    final long epoch;
    Preferences(double threshold, boolean enabled, long epoch) {
      TagConfidence.requireScore(threshold);
      this.threshold = threshold; this.enabled = enabled; this.epoch = epoch;
    }
  }

  Preferences preferences(String tag) {
    try (Cursor c = store.getReadableDatabase().rawQuery(
        "SELECT threshold,enabled,epoch FROM confidence_preferences WHERE tag=?", new String[]{tag})) {
      return c.moveToFirst() ? new Preferences(c.getDouble(0),c.getInt(1)!=0,c.getLong(2))
          : new Preferences(TagConfidence.DEFAULT_THRESHOLD,false,0);
    }
  }

  private void requirePreferences(SQLiteDatabase db, String tag) {
    try (Cursor c = db.rawQuery("SELECT id FROM tags WHERE id=?",new String[]{tag})) {
      if (!c.moveToFirst()) throw new IllegalArgumentException("Unknown tag");
    }
    db.execSQL("INSERT OR IGNORE INTO confidence_preferences(tag) VALUES(?)",new Object[]{tag});
  }

  long enable(String tag) {
    SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
    try {
      requirePreferences(db,tag);
      db.execSQL("UPDATE confidence_preferences SET enabled=1,epoch=epoch+1 WHERE tag=?",new Object[]{tag});
      long epoch=preferences(tag).epoch; db.setTransactionSuccessful();return epoch;
    } finally { db.endTransaction(); }
  }

  /** Every computation receives a new generation, including simultaneous Activity instances. */
  long begin(String tag) {
    SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
    try {
      Preferences current=preferences(tag);
      if(!current.enabled)return -1;
      db.execSQL("UPDATE confidence_preferences SET epoch=epoch+1 WHERE tag=?",new Object[]{tag});
      long epoch=preferences(tag).epoch;db.setTransactionSuccessful();return epoch;
    } finally {db.endTransaction();}
  }

  void threshold(String tag, double threshold) {
    TagConfidence.requireScore(threshold);
    SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
    try {
      requirePreferences(db,tag);
      db.execSQL("UPDATE confidence_preferences SET threshold=? WHERE tag=?",new Object[]{threshold,tag});
      db.setTransactionSuccessful();
    } finally { db.endTransaction(); }
  }

  boolean hasModel(String tag) {
    try(Cursor c=store.getReadableDatabase().rawQuery("SELECT 1 FROM confidence_models WHERE tag=? AND pipeline=?",new String[]{tag,pipeline()})) {
      return c.moveToFirst();
    }
  }

  static final class Snapshot {
    final Preferences preferences;
    final TagConfidence.Model model;
    final long revision;
    final boolean stale;
    final Map<String,Double> scores;
    Snapshot(Preferences preferences,TagConfidence.Model model,long revision,boolean stale,Map<String,Double> scores) {
      this.preferences=preferences;this.model=model;this.revision=revision;this.stale=stale;
      this.scores=Collections.unmodifiableMap(new HashMap<>(scores));
    }
  }

  Snapshot snapshot(String tag) {
    SQLiteDatabase db=store.getReadableDatabase();db.beginTransactionNonExclusive();
    try {
      Preferences preferences=preferences(tag);TagConfidence.Model model=null;long revision=-1;
      try(Cursor c=db.rawQuery("SELECT revision,parameters FROM confidence_models WHERE tag=? AND pipeline=?",new String[]{tag,pipeline()})) {
        if(c.moveToFirst()){revision=c.getLong(0);model=TagConfidence.Model.decode(c.getBlob(1));}
      }
      Map<String,Double> values=new HashMap<>();
      if(model!=null) try(Cursor c=db.rawQuery("SELECT p.source,s.score FROM confidence_scores s JOIN photos p ON p.id=s.photo WHERE s.tag=?",new String[]{tag})) {
        while(c.moveToNext()){double value=c.getDouble(1);TagConfidence.requireScore(value);values.put(c.getString(0),value);}
      }
      Snapshot result=new Snapshot(preferences,model,revision,model!=null&&revision!=store.judgmentRevision(tag),values);
      db.setTransactionSuccessful();return result;
    } finally { db.endTransaction(); }
  }

  Map<String,AutomaticTags.Decision> effective(String tag) {
    Map<String,AutomaticTags.Decision> result=new HashMap<>();
    String sql="SELECT p.source,s.score,c.threshold FROM confidence_scores s JOIN photos p ON p.id=s.photo "
        +"JOIN confidence_models m ON m.tag=s.tag JOIN confidence_preferences c ON c.tag=s.tag "
        +"LEFT JOIN judgments j ON j.tag=s.tag AND j.photo=s.photo WHERE s.tag=? AND m.pipeline=? AND j.photo IS NULL AND s.score>=c.threshold";
    try(Cursor c=store.getReadableDatabase().rawQuery(sql,new String[]{tag,pipeline()})) {
      while(c.moveToNext()) result.put(c.getString(0),new AutomaticTags.Decision(c.getDouble(1),-1,c.getDouble(2)));
    }
    return result;
  }

  Map<String,TagJudgments.Value> labels(String tag) {
    Map<String,TagJudgments.Value> labels=new HashMap<>();
    try(Cursor c=store.getReadableDatabase().rawQuery("SELECT p.source,j.value FROM judgments j JOIN photos p ON p.id=j.photo WHERE j.tag=?",new String[]{tag})) {
      while(c.moveToNext()) labels.put(c.getString(0),TagJudgments.Value.valueOf(c.getString(1)));
    }
    return labels;
  }

  /** Counts compatible human examples without extracting images or changing cached features. */
  int[] exampleCounts(String tag,PhotoFeatures features){
    int[] counts=new int[2];
    for(Map.Entry<String,TagJudgments.Value> entry:labels(tag).entrySet()){
      if(entry.getValue()==TagJudgments.Value.UNDECIDED||features.cachedSource(entry.getKey())==null)continue;
      counts[entry.getValue()==TagJudgments.Value.ASSIGNED?0:1]++;
    }
    return counts;
  }

  /** The exact human revision and learning generation are checked before publishing all scores. */
  boolean publish(String tag,long epoch,long revision,TagConfidence.Model model,Map<String,Double> scores,BooleanSupplier cancelled) {
    Objects.requireNonNull(model);
    for(double score:scores.values())TagConfidence.requireScore(score);
    SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
    try {
      Preferences preferences=preferences(tag);
      if(cancelled.getAsBoolean()||!preferences.enabled||preferences.epoch!=epoch||store.judgmentRevision(tag)!=revision)return false;
      Map<String,String> ids=store.photoIds();
      db.delete("confidence_scores","tag=?",new String[]{tag});
      for(Map.Entry<String,Double> entry:scores.entrySet()) {
        TagConfidence.requireActive(cancelled);
        String source=entry.getKey(),id=ids.get(source);
        if(source==null||source.isEmpty())throw new IllegalArgumentException("Invalid photo reference");
        if(id==null){id=UUID.randomUUID().toString();ContentValues photo=new ContentValues();photo.put("id",id);photo.put("source",source);db.insertOrThrow("photos",null,photo);ids.put(source,id);}
        ContentValues value=new ContentValues();value.put("tag",tag);value.put("photo",id);value.put("score",entry.getValue());
        db.insertOrThrow("confidence_scores",null,value);
      }
      ContentValues value=new ContentValues();value.put("tag",tag);value.put("revision",revision);value.put("pipeline",pipeline());value.put("parameters",model.encode());
      if(db.insertWithOnConflict("confidence_models",null,value,SQLiteDatabase.CONFLICT_REPLACE)<0)
        throw new IllegalStateException("Model write failed");
      if(cancelled.getAsBoolean())return false;
      db.setTransactionSuccessful();return true;
    } finally { db.endTransaction(); }
  }

  /** No extraction here: edits replay latest labels using completed, compatible feature caches only. */
  boolean recompute(String tag,PhotoFeatures features,BooleanSupplier cancelled) {
    TagConfidence.requireActive(cancelled);
    long epoch=begin(tag);
    if(epoch<0)return false;
    long revision=store.judgmentRevision(tag);
    try(DiagnosticLog.Scope span=DiagnosticLog.span("tag.confidence")) {
      Map<String,RelatedPhotos.Vector> cached=features.allCached();
      TagConfidence.Model model=TagConfidence.fit(cached,labels(tag),cancelled);
      if(model==null){DiagnosticLog.event("tag.confidence.insufficient",0);return false;}
      Map<String,Double> scores=new HashMap<>();
      for(Map.Entry<String,RelatedPhotos.Vector> entry:cached.entrySet()) {
        TagConfidence.requireActive(cancelled);scores.put(entry.getKey(),model.score(entry.getValue()));
      }
      boolean applied=publish(tag,epoch,revision,model,scores,cancelled);
      DiagnosticLog.event("tag.confidence.result",0,"positive",model.positives,"negative",model.negatives,"scored",scores.size(),"applied",applied);
      return applied;
    }
  }
}
