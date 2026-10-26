package jp.nano.rawgallery;

import android.os.Bundle;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Saved job observations only: no per-photo lookup and no inferred per-stage result. */
final class DashboardObservation {
  final JobState.Kind kind;
  final boolean known, browsing, readFailed;
  final int ready, total, failed;
  final long at;
  DashboardObservation(JobState state, boolean browsing) {
    this(state.kind,state.countsKnown,state.ready,state.total,state.failed,state.updatedAt,browsing,false);
  }
  private DashboardObservation(JobState.Kind kind,boolean known,int ready,int total,int failed,long at,boolean browsing,boolean readFailed){
    this.kind=kind;this.known=known;this.ready=ready;this.total=total;this.failed=failed;this.at=at;this.browsing=browsing;this.readFailed=readFailed;
  }
  static DashboardObservation empty(){return new DashboardObservation(JobState.Kind.UNKNOWN,false,0,0,0,0,false,false);}
  DashboardObservation unreadable(){return new DashboardObservation(kind,known,ready,total,failed,at,browsing,true);}
  boolean old(){return readFailed||kind==JobState.Kind.UNKNOWN&&at>0;}
  String count(int n){return known?NumberFormat.getIntegerInstance(Locale.JAPAN).format(n):"—";}
  String time(){return at>0?new SimpleDateFormat("HH:mm",Locale.JAPAN).format(new Date(at)):"—";}
  String stateKey(){return kind+":"+readFailed+":"+browsing;}
  String jobDescription(){
    String state;
    if(readFailed)state="状況を読み込めませんでした。処理の失敗ではありません。"+(at>0?"前回の観測値があります。":"前回の観測はありません。");
    else switch(kind){
      case RUNNING:state="取得時点では実行中。その後も続いているかは分かりません。";break;
      case WAITING:state="記録では待機中。";break;
      case PAUSED:state="記録では一時停止中。理由は記録されていません。";break;
      case COMPLETE:state="記録では完了。";break;
      case PARTIAL:state="記録では一部を準備できませんでした。";break;
      case FAILED:state="記録では失敗。";break;
      case AUTH_REQUIRED:state="記録ではログインが必要です。";break;
      default:state="状態は不明です。"+(at>0?"取得時に実行していませんでした。前回の観測値があります。":"");
    }
    return "共通の準備ジョブ。表示用プレビューと仮画像の共通の結果です。"+state
      +(at>0?(old()?"前回の観測時刻、":"件数と状態の最終観測時刻、")+time()+"。":"時刻の記録はありません。")
      +(known?"失敗"+count(failed)+"件"+(old()?"、前回の観測値":"")+"。プレビューと仮画像の合計で、準備済みと重なることがあります。":"件数の記録はありません。");
  }
  Bundle save(){Bundle b=new Bundle();b.putString("kind",kind.name());b.putBoolean("known",known);b.putInt("ready",ready);b.putInt("total",total);b.putInt("failed",failed);b.putLong("at",at);b.putBoolean("browsing",browsing);b.putBoolean("readFailed",readFailed);return b;}
  static DashboardObservation restore(Bundle b){if(b==null)return empty();try{return new DashboardObservation(JobState.Kind.valueOf(b.getString("kind","UNKNOWN")),b.getBoolean("known"),b.getInt("ready"),b.getInt("total"),b.getInt("failed"),b.getLong("at"),b.getBoolean("browsing"),b.getBoolean("readFailed"));}catch(IllegalArgumentException e){return empty();}}
}
