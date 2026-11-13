package jp.nano.rawgallery;

import android.app.Activity;
import android.view.View;
import android.widget.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/** Explicit selected-photo tagging. Choices and retries never re-read the library selection. */
final class SelectedPhotoTags extends BottomSheetDialog {
  private final Activity activity;
  private final List<PhotoRepository.Photo> photos;
  private final ExecutorService worker;
  private final Supplier<TagStore> stores;
  private final LinearLayout body;
  private final StatusView status;
  private final Button close;
  private final String operation=UUID.randomUUID().toString();
  private final long event=DiagnosticLog.id();
  private List<TagStore.Tag> tags=List.of();
  private Map<String,Long> expected=Map.of();
  private TagStore.Tag chosen;
  private boolean busy,loaded;
  private int token;

  SelectedPhotoTags(Activity activity,List<PhotoRepository.Photo> photos,ExecutorService worker){
    this(activity,photos,worker,()->new TagStore(activity));
  }
  SelectedPhotoTags(Activity activity,List<PhotoRepository.Photo> photos,ExecutorService worker,Supplier<TagStore> stores){
    super(activity);this.activity=activity;this.photos=List.copyOf(photos);this.worker=worker;this.stores=stores;
    LinearLayout column=new LinearLayout(activity);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(dp(16),dp(8),dp(16),dp(20));
    close=LibraryUi.icon(activity,R.drawable.ic_close,"閉じる",()->{if(!busy)dismiss();});
    column.addView(LibraryUi.toolbar(activity,photos.size()+"枚にタグ",close));
    status=new StatusView(activity);column.addView(status);
    body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);column.addView(body);
    ScrollView scroll=new ScrollView(activity);scroll.addView(column);setContentView(scroll);MaterialUi.sheet(this);
    setOnShowListener(dialog->load());setOnDismissListener(dialog->token++);
  }
  private int dp(int value){return GalleryUi.dp(activity,value);}
  private void enqueue(Runnable task){
    if(activity.isFinishing()||activity.isDestroyed()){dismiss();return;}
    try{worker.execute(task);}catch(java.util.concurrent.RejectedExecutionException unavailable){
      busy=false;close.setEnabled(true);setCancelable(true);setCanceledOnTouchOutside(true);
      status.show(StatusView.Symbol.FAILED,"","処理を開始できませんでした。写真の選択は保持されています",this::dismiss,"閉じる");
    }
  }
  private void load(){
    int request=++token;loaded=false;status.begin("タグを読み込んでいます");body.removeAllViews();
    enqueue(()->{
      try(TagStore store=stores.get()){
        List<TagStore.Tag> names=store.tags();
        // Read only existing current judgments once. Opening the picker never creates identities,
        // nor allocates a tag-by-photo matrix for unjudged selections.
        Set<String> sources=new HashSet<>();for(PhotoRepository.Photo photo:photos)sources.add(TagStore.source(photo));
        Map<String,Long> revisions=new HashMap<>();
        try(android.database.Cursor rows=store.getReadableDatabase().rawQuery(
            "SELECT j.tag,p.source,j.revision FROM judgments j JOIN photos p ON p.id=j.photo",null)){
          while(rows.moveToNext())if(sources.contains(rows.getString(1)))
            revisions.put(rows.getString(0)+"|"+rows.getString(1),rows.getLong(2));
        }
        activity.runOnUiThread(()->{if(!isShowing()||request!=token)return;tags=names;expected=revisions;loaded=true;render();});
      }catch(Exception failure){activity.runOnUiThread(()->{if(isShowing()&&request==token)status.show(StatusView.Symbol.FAILED,"","タグを読み込めませんでした",this::load,"再試行");});}
    });
  }
  private void render(){
    body.removeAllViews();status.finish();close.setEnabled(!busy);
    if(tags.isEmpty()){status.show(StatusView.Symbol.EMPTY,"タグがありません。ライブラリのタグ画面で作成できます");return;}
    for(TagStore.Tag tag:tags){
      Button row=LibraryUi.text(activity,tag.name,()->{if(busy)return;new GalleryUi.Sheet(activity)
          .setTitle(photos.size()+"枚に「"+tag.name+"」を付ける")
          .setMessage("選択した写真だけが対象です。写真の原本は変更しません。")
          .setNegativeButton("戻る",null).setPositiveButton("付ける",(d,w)->{chosen=tag;save();}).show();});
      row.setEnabled(!busy);body.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
  }
  private void save(){
    if(busy||!loaded||chosen==null)return;
    busy=true;setCancelable(false);setCanceledOnTouchOutside(false);render();status.begin("タグを保存しています");
    int request=token;TagStore.Tag tag=chosen;
    DiagnosticLog.event("tag.selection.start",event,"count",photos.size());
    enqueue(()->{
      try(TagStore store=stores.get()){
        Map<String,Long> revisions=new LinkedHashMap<>();
        for(PhotoRepository.Photo photo:photos)revisions.put(store.photoId(photo,true),expected.getOrDefault(tag.id+"|"+TagStore.source(photo),0L));
        store.recordBatch(operation,tag.id,revisions,TagJudgments.Value.ASSIGNED);
        activity.runOnUiThread(()->{
          if(!isShowing()||request!=token)return;busy=false;close.setEnabled(true);setCancelable(true);setCanceledOnTouchOutside(true);
          body.removeAllViews();status.show(StatusView.Symbol.COMPLETE,photos.size()+"枚にタグを付けました");
          body.addView(LibraryUi.text(activity,"閉じる",this::dismiss));
          DiagnosticLog.event("tag.selection.complete",event,"count",photos.size());
        });
      }catch(Exception failure){activity.runOnUiThread(()->{
        if(!isShowing()||request!=token)return;busy=false;close.setEnabled(true);setCancelable(true);setCanceledOnTouchOutside(true);
        body.removeAllViews();
        if(failure instanceof IllegalStateException)status.show(StatusView.Symbol.FAILED,"","ほかの操作でタグが変更されました。選択を確認してください",this::load,"タグを読み直す");
        else status.show(StatusView.Symbol.FAILED,"","タグを保存できませんでした。写真の選択は保持されています",this::save,"再試行");
        DiagnosticLog.event("tag.selection.failed",event,"count",photos.size());
      });}
    });
  }
}
