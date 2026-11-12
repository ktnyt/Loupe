package jp.nano.rawgallery;

import android.app.Activity;
import android.content.Intent;
import java.util.ArrayList;
import java.util.List;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

final class NanoBananaUi {
  static void show(Activity activity,PhotoRepository.Photo photo) {
    show(activity,photo,new NanoBananaStore(activity));
  }
  static void show(Activity activity,PhotoRepository.Photo photo,NanoBananaStore store) {
    try {
      List<NanoBananaStore.Prompt> prompts=store.prompts();
      if(store.job()!=null) {
        MoreMenuUi.show(activity,"Nano Banana",new String[]{"進行中の処理・前回の結果","設定"},(d,w)->{
          if(w==0)activity.startActivity(new Intent(activity,NanoBananaActivity.class));else settings(activity);
        });return;
      }
      if(!store.hasKey()||!store.hasE2()||prompts.isEmpty()) {
        new MaterialAlertDialogBuilder(activity).setTitle("Nano Bananaを設定")
            .setMessage("APIキー、IDrive e2の送信先、プロンプトを登録してください。写真は送信用JPEGにして外部へ送信し、生成結果を別画像で保存します。")
            .setNegativeButton("閉じる",null).setPositiveButton("設定",(d,w)->settings(activity)).show();return;
      }
      ArrayList<String> names=new ArrayList<>();for(NanoBananaStore.Prompt p:prompts)names.add(p.name);names.add("設定");
      MoreMenuUi.show(activity,"Nano Banana",names.toArray(new String[0]),(d,w)->{
        if(w==prompts.size()){settings(activity);return;}
        activity.startActivity(new Intent(activity,NanoBananaActivity.class).setData(photo.uri)
            .putExtra("name",photo.name).putExtra("folder",photo.folder).putExtra("size",photo.size)
            .putExtra("modified",photo.modified).putExtra("taken",photo.taken).putExtra("prompt",prompts.get(w).id));
      });
    }catch(Exception error){new MaterialAlertDialogBuilder(activity).setMessage("Nano Bananaの設定を読み込めませんでした")
        .setPositiveButton("閉じる",null).show();}
  }
  private static void settings(Activity activity){activity.startActivity(new Intent(activity,NanoBananaSettingsActivity.class));}
}
