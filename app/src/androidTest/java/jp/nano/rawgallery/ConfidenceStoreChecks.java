package jp.nano.rawgallery;

import android.content.Context;
import java.util.*;

/** Disposable SQLite databases; does not modify the user's tag or feature stores. */
final class ConfidenceStoreChecks {
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static String run(Context context) throws Exception {
    for(int legacy:new int[]{1,2})migration(context,legacy);
    String database="confidence-check-"+UUID.randomUUID()+".db";
    try {
      try(TagStore store=new TagStore(context,database)) {
        String tag=store.create("fixture").id,otherTag=store.create("other").id;
        PhotoRepository.Photo seed=AutomaticTagChecks.photo(1),negative=AutomaticTagChecks.photo(2),unknown=AutomaticTagChecks.photo(3);
        String seedId=store.photoId(seed,true),negativeId=store.photoId(negative,true);
        store.record("positive",tag,seedId,0,TagJudgments.Value.ASSIGNED);
        store.record("negative",tag,negativeId,0,TagJudgments.Value.EXCLUDED);
        TagScores scores=new TagScores(store);
        Map<String,RelatedPhotos.Vector> vectors=Map.of(TagStore.source(seed),AutomaticTagChecks.v(1,0),TagStore.source(negative),AutomaticTagChecks.v(0,1));
        TagConfidence.Model model=TagConfidence.fit(vectors,scores.labels(tag),()->false);
        long epoch=scores.enable(tag),revision=store.judgmentRevision(tag);
        Map<String,Double> values=Map.of(TagStore.source(seed),.98,TagStore.source(negative),.02,TagStore.source(unknown),.45);
        check(scores.publish(tag,epoch,revision,model,values,()->false),"initial publish");
        check(scores.snapshot(tag).scores.size()==3,"below-threshold scores retained");
        store.automation(tag,false,false);
        check(!scores.publish(tag,epoch,revision,model,values,()->false),"stop rejects earlier learned job");
        check(scores.snapshot(tag).scores.size()==3,"stop retains completed results");
        epoch=scores.begin(tag);
        long firstJob=epoch;epoch=scores.begin(tag);
        check(!scores.publish(tag,firstJob,revision,model,values,()->false),"later computation supersedes older snapshot");
        check(scores.publish(tag,epoch,revision,model,values,()->false),"latest computation can publish");
        check(store.automatic(tag).isEmpty(),"manual priority and initial threshold");
        scores.threshold(tag,.4);
        check(store.automatic(tag).size()==1,"lower threshold reveals cached score");
        check(store.summaries(List.of(seed,negative,unknown)).get(0).tag!=null,"summaries query");
        try(TagStore reopened=new TagStore(context,database)) {
          TagScores.Snapshot restored=new TagScores(reopened).snapshot(tag);
          check(restored.scores.size()==3&&restored.preferences.threshold==.4,"durable scores and threshold");
        }
        String unknownId=store.photoId(unknown,false);
        store.record("exclude",tag,unknownId,0,TagJudgments.Value.EXCLUDED);
        check(store.automatic(tag).isEmpty()&&scores.snapshot(tag).stale,"correction priority and stale metadata");
        check(!scores.publish(tag,epoch,revision,model,values,()->false),"stale human revision rejected");
        store.undo("undo","exclude");
        check(store.automatic(tag).isEmpty(),"explicit cancellation remains suppressed");
        revision=store.judgmentRevision(tag);
        store.getWritableDatabase().execSQL("CREATE TRIGGER confidence_failure BEFORE INSERT ON confidence_scores BEGIN SELECT RAISE(ABORT,'fixture'); END");
        boolean failed=false;try{scores.publish(tag,epoch,revision,model,values,()->false);}catch(android.database.sqlite.SQLiteException expected){failed=true;}
        check(failed&&scores.snapshot(tag).scores.size()==3,"failed publication rolls back all scores");
        store.getWritableDatabase().execSQL("DROP TRIGGER confidence_failure");
        check(scores.publish(tag,epoch,revision,model,values,()->false),"retry publishes full snapshot");
        int[] calls={0};boolean cancelled=false;
        try{scores.publish(tag,epoch,revision,model,values,()->++calls[0]>1);}catch(java.util.concurrent.CancellationException expected){cancelled=true;}
        check(cancelled&&scores.snapshot(tag).scores.size()==3,"cancellation rolls back");
        long old=epoch;epoch=scores.enable(tag);
        check(!scores.publish(tag,old,revision,model,values,()->false),"old generation rejected");
        store.record("other-positive",otherTag,seedId,0,TagJudgments.Value.ASSIGNED);
        check(scores.labels(tag).get(TagStore.source(seed))==TagJudgments.Value.ASSIGNED,"independent tags");
        store.automation(tag,false,true);
        check(!scores.hasModel(tag)&&scores.snapshot(tag).scores.isEmpty()&&!scores.preferences(tag).enabled,"clear removes only prediction profile");
        check(!scores.publish(tag,epoch,revision,model,values,()->false),"cleared job cannot resurrect");
        check(store.load().value(tag,seedId)==TagJudgments.Value.ASSIGNED&&store.load().value(otherTag,seedId)==TagJudgments.Value.ASSIGNED,"clear preserves human judgments and other tag");
        check(scores.preferences(tag).threshold==.4,"clear preserves display preference");
      }
    } finally {context.deleteDatabase(database);}
    return "PASS confidence SQLite: v1/v2 migration, all scores, threshold persistence, human priority, cancellation suppression, snapshot rollback, revision/epoch/clear guards, independent tags\n";
  }

  static void migration(Context context,int version) {
    String database="confidence-v"+version+"-"+UUID.randomUUID()+".db";
    String tag,photo;
    try {
      try(TagStore store=new TagStore(context,database)) {
        tag=store.create("migration").id;photo=store.photoId(AutomaticTagChecks.photo(1),true);
        store.record("human",tag,photo,0,TagJudgments.Value.ASSIGNED);
        store.getWritableDatabase().execSQL("DROP TABLE confidence_preferences");
        store.getWritableDatabase().execSQL("DROP TABLE confidence_models");
        store.getWritableDatabase().execSQL("DROP TABLE confidence_scores");
        if(version==1){store.getWritableDatabase().execSQL("DROP TABLE automation");store.getWritableDatabase().execSQL("DROP TABLE automatic_tags");}
        store.getWritableDatabase().setVersion(version);
      }
      try(TagStore store=new TagStore(context,database)) {
        check(store.load().value(tag,photo)==TagJudgments.Value.ASSIGNED,"migration preserves history");
        check(!new TagScores(store).preferences(tag).enabled,"migration does not opt into learning");
      }
    } finally {context.deleteDatabase(database);}
  }
}
