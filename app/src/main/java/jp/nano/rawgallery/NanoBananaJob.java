package jp.nano.rawgallery;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InterruptedIOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** One durable generation at a time. An ambiguous POST is never resubmitted. */
final class NanoBananaJob {
  static final AtomicBoolean BUSY=new AtomicBoolean();
  interface Progress { void show(String message); }
  interface Services {
    String upload(String object,byte[] jpeg,String owner) throws Exception;
    String generate(String prompt,String object,String version,NanoBananaOutput output) throws Exception;
    JSONObject status(String task) throws Exception;
    byte[] download(String url) throws Exception;
    void delete(String object,String version,String owner) throws Exception;
  }
  private final Services services;
  private Services services() throws Exception {
    if(services!=null)return services;
    NanoBananaE2 e2=store.e2();NanoBananaApi api=new NanoBananaApi(store.apiKey());
    return new Services() {
      public String upload(String object,byte[] jpeg,String owner)throws Exception{return e2.upload(object,jpeg,owner);}
      public String generate(String prompt,String object,String version,NanoBananaOutput output)throws Exception{return api.generate(prompt,e2.signedGet(object,version),output);}
      public JSONObject status(String task)throws Exception{return api.status(task);}
      public byte[] download(String url)throws Exception{return NanoBananaApi.download(url);}
      public void delete(String object,String version,String owner)throws Exception{e2.delete(object,version,owner);}
    };
  }
  private final Context context;
  private final NanoBananaStore store;
  NanoBananaJob(Context context) {this(context,new NanoBananaStore(context));}
  NanoBananaJob(Context context,NanoBananaStore store) {this(context,store,null);}
  NanoBananaJob(Context context,NanoBananaStore store,Services services) {this.context=context.getApplicationContext();this.store=store;this.services=services;}
  void run(PhotoRepository.Photo photo,NanoBananaStore.Prompt prompt,Progress progress) throws Exception {
    run(photo,prompt,null,progress);
  }
  private static void checkInterrupted() throws InterruptedIOException {
    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("処理を中断しました。AI編集メニューから確認してください");
  }
  void run(PhotoRepository.Photo photo,NanoBananaStore.Prompt prompt,NanoBananaOutput snapshot,Progress progress) throws Exception {
    checkInterrupted();
    JSONObject job=store.job();
    long start=SystemClock.elapsedRealtime(), event=DiagnosticLog.id();
    DiagnosticLog.event("nano.start",event,"resume",job!=null);
    try {
      if(job==null) {
        if(photo==null||prompt==null)throw new IOException("写真とプロンプトを選び直してください");
        NanoBananaOutput output=snapshot==null?store.output():snapshot;
        // Validate both credentials before touching the network or creating remote objects.
        Services network=services();
        progress.show("送信用の写真を準備しています");
        byte[] jpeg=input(new PhotoRepository(context),photo);
        checkInterrupted();
        String object="loupe-nano-temp/"+UUID.randomUUID()+".jpg";
        job=new JSONObject().put("object",object).put("stage","uploading").put("owner",UUID.randomUUID().toString()).put("output",output.json());store.job(job);
        progress.show("IDrive e2へ送信しています");
        String version;
        try{checkInterrupted();version=network.upload(object,jpeg,job.getString("owner"));}
        catch(NanoBananaE2.UploadRejected rejected){job.put("stage","rejected");store.job(job);throw new IOException("e2が送信を拒否しました。一時画像を削除して終了し、設定を確認してください。");}
        if(version!=null){job.put("version",version);store.job(job);}
        checkInterrupted();
        if(version==null||version.equals("null")) {
          job.put("stage","failed");store.job(job);
          throw new IOException("e2バケットのバージョニングを有効にしてください。一時画像の削除後に設定を確認してください。");
        }
        // Persist this boundary BEFORE POST. Crash/timeout here requires manual cleanup, never a new POST.
        job.put("stage","submitting");store.job(job);
        progress.show("Nano Bananaへ依頼しています");
        checkInterrupted();
        String task=network.generate(prompt.text,object,version,NanoBananaOutput.fromJob(job));
        job.put("task",task).put("stage","generating");store.job(job);
        checkInterrupted();
      }
      recoverPending(job);
      String stage=job.getString("stage");
      if(stage.equals("discarding")){discard();return;}
      if(job.has("saved")&&!stage.equals("complete")){job.put("stage","cleanup");store.job(job);stage="cleanup";}
      if(stage.equals("rejected"))throw new IOException("e2が送信を拒否しました。終了して設定を確認してください。");
      if(stage.equals("uploading")||stage.equals("submitting"))
        throw new IOException("依頼の結果を確認できません。自動では再送しません。一時画像を削除して終了できます。API側の処理と課金は取り消せない場合があります。");
      if(stage.equals("complete")) {progress.show("生成画像を保存しました");return;}
      if(stage.equals("cleanup")) {cleanup(job);progress.show("一時画像を削除し、生成画像を保存しました");return;}
      if(stage.equals("failed"))throw new IOException("画像を生成できませんでした。一時画像を削除して終了してください。");
      Services network=services();
      progress.show("画像を生成しています");
      long deadline=SystemClock.elapsedRealtime()+300000;
      JSONObject status;
      while(true) {
        checkInterrupted();
        status=network.status(job.getString("task"));checkInterrupted();int flag=status.getInt("successFlag");
        if(flag==1)break;
        if(flag==2||flag==3){job.put("stage","failed");store.job(job);throw new IOException("画像を生成できませんでした。一時画像を削除して終了してください。");}
        if(flag!=0)throw new IOException("生成状態を確認できませんでした");
        if(SystemClock.elapsedRealtime()>deadline)throw new IOException("生成を待っています。「状態を再確認」で続きから確認できます。");
        Thread.sleep(3000);
      }
      progress.show("生成画像を保存しています");
      recoverPending(job);
      if(!job.has("saved")) {
        checkInterrupted();
        byte[] bytes=network.download(status.getJSONObject("response").getString("resultImageUrl"));
        checkInterrupted();saveResult(job,bytes);
      }
      job.put("stage","cleanup");store.job(job);
      progress.show("送信用の一時画像を削除しています");cleanup(job);
      progress.show("生成画像を保存しました");
      DiagnosticLog.event("nano.complete",event,"elapsed_ms",SystemClock.elapsedRealtime()-start);
    }catch(Exception error){DiagnosticLog.event("nano.failed",event,"elapsed_ms",SystemClock.elapsedRealtime()-start);throw error;}
  }
  /** Durable cancellation intent must precede any recovery or remote cleanup. */
  static void markDiscarding(NanoBananaStore store) throws Exception {
    JSONObject job=store.job();if(job==null||job.optString("stage").equals("discarding"))return;
    String stage=job.optString("stage");
    job.put("discardDelete",!stage.equals("complete")&&!stage.equals("rejected"));
    job.put("stage","discarding");store.job(job);
  }
  void discard() throws Exception {
    checkInterrupted();
    markDiscarding(store);
    JSONObject job=store.job();if(job==null)return;
    recoverPending(job);checkInterrupted();
    if(job.optBoolean("discardDelete",true)){
      services().delete(job.getString("object"),job.has("version")?job.getString("version"):null,job.getString("owner"));
      job.put("discardDelete",false);store.job(job);checkInterrupted();
    }
    store.job(null);
  }
  private void cleanup(JSONObject job) throws Exception {
    checkInterrupted();
    services().delete(job.getString("object"),job.has("version")?job.getString("version"):null,job.getString("owner"));
    job.put("stage","complete");store.job(job);checkInterrupted();
  }
  static byte[] input(PhotoRepository repository,PhotoRepository.Photo photo) throws Exception {
    if(photo.isVideo())throw new IOException("写真を選んでください");
    PhotoRepository.Preview preview=repository.preview(photo);Bitmap bitmap=PhotoRepository.bitmap(preview,1600);
    int edge=Math.max(bitmap.getWidth(),bitmap.getHeight());
    if(edge>1600) {
      float scale=1600f/edge;
      Bitmap small=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*scale)),Math.max(1,Math.round(bitmap.getHeight()*scale)),true);
      if(small!=bitmap)bitmap.recycle();bitmap=small;
    }
    try(ByteArrayOutputStream out=new ByteArrayOutputStream()) {
      if(!bitmap.compress(Bitmap.CompressFormat.JPEG,92,out))throw new IOException("送信用JPEGを作れませんでした");
      if(out.size()>5*1024*1024)throw new IOException("送信用画像が大きすぎます");return out.toByteArray();
    }finally{bitmap.recycle();}
  }
  private void recoverPending(JSONObject job) throws Exception {
    checkInterrupted();
    if(job.has("saved")||!job.has("pending"))return;
    Uri uri=Uri.parse(job.getString("pending"));
    int state;
    try(Cursor c=context.getContentResolver().query(uri,new String[]{MediaStore.Images.Media.IS_PENDING},null,null,null)) {
      state=pendingState(c);
    }
    if(state==0){job.put("saved",uri.toString());store.job(job);checkInterrupted();return;}
    checkInterrupted();
    if(state==1&&context.getContentResolver().delete(uri,MediaStore.Images.Media.IS_PENDING+" = ?",new String[]{"1"})!=1)
      throw new IOException("画像の保存状態を確認できませんでした。もう一度確認してください");
    job.remove("pending");store.job(job);
  }
  /** Unknown provider availability is never treated as an absent or unpublished image. */
  static int pendingState(Cursor cursor) throws IOException {
    if(cursor==null)throw new IOException("画像の保存先に接続できません。もう一度確認してください");
    if(!cursor.moveToFirst())return -1;
    int value=cursor.getInt(0);
    if(value!=0&&value!=1)throw new IOException("画像の保存状態を確認できませんでした");
    return value;
  }

  private void saveResult(JSONObject job,byte[] bytes) throws Exception {
    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
    BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
    if(bounds.outWidth<=0||bounds.outHeight<=0||(long)bounds.outWidth*bounds.outHeight>20000000)
      throw new IOException("生成結果を画像として開けませんでした");
    boolean jpeg="image/jpeg".equals(bounds.outMimeType);
    BitmapFactory.Options decode=new BitmapFactory.Options();decode.inSampleSize=1;
    if(jpeg)while((bounds.outWidth+decode.inSampleSize-1)/Math.max(1,decode.inSampleSize)>1600
        ||(bounds.outHeight+decode.inSampleSize-1)/Math.max(1,decode.inSampleSize)>1600)
      decode.inSampleSize*=2;
    checkInterrupted();
    Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,decode);
    if(bitmap==null)throw new IOException("生成結果を画像として開けませんでした");
    Uri uri=null;boolean published=false;
    try {
      checkInterrupted();
      ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,"NanoBanana-"+UUID.randomUUID()+".jpg");
      values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/LoupeNanoBanana");
      values.put(MediaStore.Images.Media.IS_PENDING,1);
      uri=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
      if(uri==null)throw new IOException("画像の保存先を作れませんでした");
      job.put("pending",uri.toString());store.job(job);
      try(OutputStream out=context.getContentResolver().openOutputStream(uri)) {
        if(out==null)throw new IOException("生成画像を保存できませんでした");
        checkInterrupted();
        if(jpeg)out.write(bytes);
        else if(!bitmap.compress(Bitmap.CompressFormat.JPEG,95,out))throw new IOException("生成画像を保存できませんでした");
      }
      checkInterrupted();
      ContentValues ready=new ContentValues();ready.put(MediaStore.Images.Media.IS_PENDING,0);
      if(context.getContentResolver().update(uri,ready,null,null)!=1)throw new IOException("生成画像を確定できませんでした");
      published=true;job.put("saved",uri.toString());store.job(job);checkInterrupted();
    }finally{
      bitmap.recycle();if(!published&&uri!=null)context.getContentResolver().delete(uri,MediaStore.Images.Media.IS_PENDING+" = ?",new String[]{"1"});
    }
  }
}
