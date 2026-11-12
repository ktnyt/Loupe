package jp.nano.rawgallery;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Durable photo-edit operation UI. Leaving this screen never submits a second generation. */
public final class NanoBananaActivity extends DiagnosticActivity {
  private static final java.util.concurrent.ExecutorService WORK=Executors.newSingleThreadExecutor();
  private final Handler main=new Handler(Looper.getMainLooper());
  private NanoBananaStore store;
  private TextView status;
  private ProgressBar spinner;
  private Button resume,cleanup,location,done;
  private ImageView image;
  private String message="";
  private Bitmap result;
  private final Runnable refresh=new Runnable(){public void run(){if(isDestroyed()||isFinishing())return;render();main.postDelayed(this,1000);}};
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);if(store==null)store=new NanoBananaStore(this);
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),0,dp(20),0);
    root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(i.left+dp(20),i.top,i.right+dp(20),i.bottom);return insets;});
    TextView title=new TextView(this);title.setText("Nano Banana");title.setTextSize(24);title.setTextColor(GalleryUi.TEXT);title.setPadding(0,dp(16),0,dp(16));title.setAccessibilityHeading(true);root.addView(title);
    status=new TextView(this);status.setTextColor(GalleryUi.TEXT);status.setTextSize(16);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(status);
    spinner=new ProgressBar(this);root.addView(spinner);
    image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription("Nano Bananaの生成結果");root.addView(image,new LinearLayout.LayoutParams(-1,0,1));
    resume=button(root,"状態を再確認",()->start(false));
    cleanup=button(root,"一時画像を削除して終了",()->new MaterialAlertDialogBuilder(this).setTitle("一時画像を削除して終了しますか？")
        .setMessage("NanoBananaAPI.ai側の生成や課金は取り消せない場合があります。保存済みの生成画像は残します。")
        .setNegativeButton("戻る",null).setPositiveButton("削除して終了",(d,w)->start(true)).show());
    location=button(root,"一時画像の場所を確認",()->{
      try {
        JSONObject job=store.job();if(job==null)return;
        TextView key=new TextView(this);key.setText("e2の一時画像\n"+job.getString("object"));key.setTextIsSelectable(true);key.setTextColor(GalleryUi.TEXT);key.setTextSize(16);key.setPadding(dp(24),dp(16),dp(24),dp(16));
        new MaterialAlertDialogBuilder(this).setTitle("一時画像の場所").setView(key).setPositiveButton("閉じる",null).show();
      }catch(Exception e){message="一時画像の場所を確認できませんでした";render();}
    });
    done=button(root,"ライブラリへ戻る",()->{try{JSONObject j=store.job();if(j!=null&&j.optString("stage").equals("complete"))store.job(null);finish();}catch(Exception e){message="処理記録を更新できませんでした";render();}});
    setContentView(root);render();
    try{if(state==null&&!NanoBananaJob.BUSY.get()&&(store.job()==null||!store.job().optString("stage").equals("complete")))start(false);}catch(Exception e){message="処理記録を読み込めませんでした";render();}
  }
  private int dp(int v){return GalleryUi.dp(this,v);}
  private Button button(LinearLayout root,String text,Runnable action){Button b=MaterialUi.button(this);b.setText(text);b.setAllCaps(false);b.setMinHeight(dp(48));b.setOnClickListener(v->action.run());root.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;}
  private void start(boolean discard) {
    synchronized(NanoBananaStore.LOCK){if(!NanoBananaJob.BUSY.compareAndSet(false,true))return;}
    message=discard?"一時画像を削除しています":"処理を開始しています";render();
    WeakReference<NanoBananaActivity> screen=new WeakReference<>(this);
    Intent input=new Intent(getIntent());android.content.Context context=getApplicationContext();
    WORK.execute(()->{
      String failure=null;
      try {
        NanoBananaJob job=new NanoBananaJob(context);
        if(discard)job.discard();
        else {
          PhotoRepository.Photo photo=null;NanoBananaStore.Prompt prompt=null;
          if(input.getData()!=null)photo=new PhotoRepository.Photo(input.getData(),input.getStringExtra("name"),input.getStringExtra("folder"),input.getLongExtra("size",0),input.getLongExtra("modified",0),input.getLongExtra("taken",0));
          for(NanoBananaStore.Prompt p:new NanoBananaStore(context).prompts())if(p.id.equals(input.getStringExtra("prompt")))prompt=p;
          job.run(photo,prompt,text->{NanoBananaActivity activity=screen.get();if(activity!=null)activity.main.post(()->{if(!activity.isDestroyed()){activity.message=text;activity.render();}});});
        }
      }catch(Exception error){failure=error instanceof java.io.IOException?error.getMessage():"処理を完了できませんでした。通信と設定を確認してください。";}
      finally{NanoBananaJob.BUSY.set(false);}
      NanoBananaActivity activity=screen.get();if(activity!=null){final String error=failure;activity.main.post(()->{if(!activity.isDestroyed()){activity.message=error==null?"":error;if(discard&&error==null)activity.finish();else activity.render();}});}
    });
  }
  private void render() {
    boolean busy=NanoBananaJob.BUSY.get();spinner.setVisibility(busy?View.VISIBLE:View.GONE);
    try {
      JSONObject job=store.job();String stage=job==null?"":job.optString("stage");
      boolean complete=stage.equals("complete");
      String text=complete?"生成画像を保存しました\nPictures/LoupeNanoBanana":
          !message.isEmpty()?message:stage.equals("cleanup")?"生成画像は保存済みです。一時画像の削除を再試行してください":
          stage.equals("generating")?"生成処理があります。状態を再確認できます":
          stage.equals("failed")?"生成できませんでした。一時画像を削除して終了してください":
          job!=null?"依頼の結果が不明です。自動では再送しません":"処理はありません";
      if(!status.getText().toString().equals(text))status.setText(text);
      resume.setVisibility(job!=null&&!complete?View.VISIBLE:View.GONE);
      resume.setEnabled(!busy&&(stage.equals("generating")||stage.equals("cleanup")));
      cleanup.setVisibility(job!=null&&!complete?View.VISIBLE:View.GONE);cleanup.setEnabled(!busy);
      location.setVisibility(job!=null&&!complete?View.VISIBLE:View.GONE);location.setEnabled(!busy);
      done.setEnabled(!busy||job!=null);
      if(complete&&result==null&&job.has("saved")) {
        result=resultPreview(this,Uri.parse(job.getString("saved")));image.setImageBitmap(result);
      }
    }catch(Exception e){status.setText("処理記録を読み込めませんでした。設定を確認してください");}
  }
  /** Decode only a display-sized preview; the saved JPEG retains its original dimensions. */
  static Bitmap resultPreview(android.content.Context context,Uri uri) throws java.io.IOException {
    BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
    try(java.io.InputStream in=context.getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,options);}
    options.inJustDecodeBounds=false;options.inSampleSize=1;
    while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>1600)options.inSampleSize*=2;
    try(java.io.InputStream in=context.getContentResolver().openInputStream(uri)){return BitmapFactory.decodeStream(in,null,options);}
  }
  @Override protected void onResume(){super.onResume();main.post(refresh);}
  @Override protected void onPause(){main.removeCallbacks(refresh);super.onPause();}
  @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);image.setImageDrawable(null);if(result!=null)result.recycle();super.onDestroy();}
}
