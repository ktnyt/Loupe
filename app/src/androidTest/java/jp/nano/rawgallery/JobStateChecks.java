package jp.nano.rawgallery;

import android.content.Context;
import android.content.SharedPreferences;

/** Dedicated preferences only, never run a sync/prepare job or touch original files. */
final class JobStateChecks {
  static String run(Context context) {
    String file="job-state-test-"+java.util.UUID.randomUUID();
    SharedPreferences p=context.getSharedPreferences(file,0);
    try {
      require(JobState.read(p,"p",false,true).kind==JobState.Kind.UNKNOWN,"empty state");
      p.edit().putString("state","準備できました").putString("syncStatus","完了").putInt("ready",0).putInt("total",0).putInt("failed",0).commit();
      require(!JobState.read(p,"p",false,true).countsKnown,"legacy zero isn't measured");
      long at=123456;
      for(JobState.Kind kind:JobState.Kind.values()) {
        JobState.write(p.edit(),"p",kind,at).putBoolean("pCountsKnown",true)
            .putInt("ready",2).putInt("total",5).putInt("failed",1).putString("state","legacy preserved").commit();
        JobState value=JobState.read(p,"p",true,true);
        require(value.kind==kind,"round trip "+kind);
        if(kind!=JobState.Kind.UNKNOWN)require(value.countsKnown&&value.ready==2&&value.total==5&&value.failed==1&&value.updatedAt==at,"snapshot "+kind);
        require("legacy preserved".equals(p.getString("state","")),"legacy message");
      }
      JobState.write(p.edit(),"p",JobState.Kind.RUNNING,at).commit();
      JobState ended=JobState.read(p,"p",false,true);
      require(ended.kind==JobState.Kind.UNKNOWN&&ended.countsKnown,"stale running");
      p.edit().putString("pStateCode","future-code").commit();
      require(JobState.read(p,"p",true,true).kind==JobState.Kind.UNKNOWN&&!JobState.read(p,"p",true,true).countsKnown,"future code");
      JobState.write(p.edit(),"p",JobState.Kind.FAILED,at).putBoolean("pCountsKnown",false).commit();
      require(!JobState.read(p,"p",false,true).countsKnown,"failed scan is unmeasured");
      JobState.write(p.edit(),"p",JobState.Kind.COMPLETE,at).putBoolean("pCountsKnown",true).putInt("ready",0).putInt("total",0).putInt("failed",0).commit();
      require(JobState.read(p,"p",false,true).countsKnown,"measured empty");
      require(!JobState.read(p,"p",false,false).countsKnown,"sync totals unknown");
      p.edit().putInt("ready",2).putInt("total",1).commit();
      require(!JobState.read(p,"p",false,true).countsKnown,"invalid counts");
      p.edit().putLong("pStateAt",0).commit();require(JobState.read(p,"p",true,true).kind==JobState.Kind.UNKNOWN,"no timestamp");
      return "PASS isolated SharedPreferences: all 8 state codes, legacy/future/missing timestamp unknown, stale running unknown with prior measured counts, scan failure unmeasured vs measured empty, invalid counts, sync total unknown, legacy message retained. No jobs/network/original operations.\n";
    }finally{p.edit().clear().commit();context.deleteSharedPreferences(file);}
  }
  private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
