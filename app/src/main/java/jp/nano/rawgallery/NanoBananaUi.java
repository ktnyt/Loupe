package jp.nano.rawgallery;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Generation starts in-place; only explicit settings actions leave the viewer. */
final class NanoBananaUi {
  interface Dispatcher {
    boolean start(Activity activity,PhotoRepository.Photo photo,NanoBananaStore.Prompt prompt);
    boolean resume(Activity activity);
    boolean discard(Activity activity);
    boolean busy();
  }
  static final Dispatcher ACTUAL=new Dispatcher(){
    public boolean start(Activity a,PhotoRepository.Photo p,NanoBananaStore.Prompt prompt){return NanoBananaService.start(a,p,prompt);}
    public boolean resume(Activity a){return NanoBananaService.resume(a);}
    public boolean discard(Activity a){return NanoBananaService.discard(a);}
    public boolean busy(){return NanoBananaService.busy();}
  };
  static void show(Activity activity,PhotoRepository.Photo photo){show(activity,photo,new NanoBananaStore(activity),ACTUAL);}
  static void show(Activity activity,PhotoRepository.Photo photo,NanoBananaStore store){show(activity,photo,store,ACTUAL);}
  static Dialog show(Activity activity,PhotoRepository.Photo photo,NanoBananaStore store,Dispatcher dispatcher){
    try {
      if(dispatcher.busy())return busy(activity);
      NanoBananaService.forgetComplete(store);
      JSONObject job=store.job();
      if(job!=null)return pending(activity,job,dispatcher);
      List<NanoBananaStore.Prompt> prompts=store.prompts();
      if(!store.hasKey()||!store.hasE2()||prompts.isEmpty()) {
        return new MaterialAlertDialogBuilder(activity).setTitle("Nano Bananaを設定")
            .setMessage("APIキー、IDrive e2の送信先、プロンプトを登録してください。写真は送信用JPEGにして外部へ送信し、生成結果を別画像で保存します。")
            .setNegativeButton("閉じる",null).setPositiveButton("設定",(d,w)->settings(activity)).show();
      }
      ArrayList<String> names=new ArrayList<>();for(NanoBananaStore.Prompt p:prompts)names.add(p.name);names.add("設定");
      return MoreMenuUi.show(activity,"Nano Banana",names.toArray(new String[0]),(d,w)->{
        if(w==prompts.size())settings(activity);
        else dispatcher.start(activity,photo,prompts.get(w));
      });
    }catch(Exception error){return unreadable(activity);}
  }
  static Dialog showPending(Activity activity,NanoBananaStore store){
    try {
      if(ACTUAL.busy())return busy(activity);
      NanoBananaService.forgetComplete(store);
      JSONObject job=store.job();
      if(job!=null)return pending(activity,job,ACTUAL);
      return new MaterialAlertDialogBuilder(activity).setMessage("実行中の処理はありません").setPositiveButton("閉じる",null).show();
    }catch(Exception error){return unreadable(activity);}
  }
  private static Dialog busy(Activity activity){
    return MoreMenuUi.show(activity,"画像を生成中",new String[]{"設定"},(d,w)->settings(activity));
  }
  private static Dialog pending(Activity activity,JSONObject job,Dispatcher dispatcher){
    String stage=job.optString("stage");
    boolean cleanup=stage.equals("cleanup")||stage.equals("discarding");
    boolean retry=cleanup||stage.equals("generating");
    String title=cleanup?"後処理が残っています":stage.equals("generating")?"生成を再確認できます":"処理を完了できませんでした";
    String[] items=retry?new String[]{cleanup?"後処理を再試行":"状態を再確認","処理を終了","設定"}:new String[]{"処理を終了","設定"};
    return MoreMenuUi.show(activity,title,items,(d,w)->{
      if(retry&&w==0){dispatcher.resume(activity);return;}
      if(w==(retry?1:0)){
        new MaterialAlertDialogBuilder(activity).setTitle("処理を終了しますか？")
            .setMessage("保存済みの画像は残します。送信用画像を削除しますが、API側の処理や課金は取り消せない場合があります。")
            .setNegativeButton("戻る",null).setPositiveButton("終了",(dialog,which)->dispatcher.discard(activity)).show();
      }else settings(activity);
    });
  }
  private static Dialog unreadable(Activity activity){return new MaterialAlertDialogBuilder(activity).setMessage("Nano Bananaの設定を読み込めませんでした").setPositiveButton("閉じる",null).show();}
  private static void settings(Activity activity){activity.startActivity(new Intent(activity,NanoBananaSettingsActivity.class));}
}
