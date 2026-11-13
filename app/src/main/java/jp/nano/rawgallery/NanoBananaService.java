package jp.nano.rawgallery;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import java.io.IOException;
import java.util.UUID;
import org.json.JSONObject;

/** Owns a single background edit, independent of the browsing Activity. */
public class NanoBananaService extends Service {
  private static final String CHANNEL="nano-banana-progress", EXTRA_NONCE="request_nonce";
  private static final int NOTIFICATION=107;
  private static final Handler MAIN=new Handler(Looper.getMainLooper());
  // All admission and worker ownership changes use Store.LOCK, including BUSY.
  private static Request reserved;
  /** In-process dependency seam; no Intent can select fixture dependencies. */
  interface Factory {
    NanoBananaStore store(Context context);
    NanoBananaJob job(Context context,NanoBananaStore store);
    default void result(String message){}
  }
  static final Factory PRODUCTION=new Factory(){
    public NanoBananaStore store(Context context){return new NanoBananaStore(context);}
    public NanoBananaJob job(Context context,NanoBananaStore store){return new NanoBananaJob(context,store);}
  };
  static volatile Factory dependencies=PRODUCTION;
  private static final class Request {
    final String nonce=UUID.randomUUID().toString();
    final NanoBananaStore store;
    final PhotoRepository.Photo photo;
    final NanoBananaStore.Prompt prompt;
    final NanoBananaOutput output;
    final boolean discard;
    boolean consumed;
    Request(NanoBananaStore store,PhotoRepository.Photo photo,NanoBananaStore.Prompt prompt,
        NanoBananaOutput output,boolean discard) {
      this.store=store;this.photo=photo;this.prompt=prompt;this.output=output;this.discard=discard;
    }
  }
  private Thread worker;
  private Request running;
  private boolean destroyed,stopping;

  static boolean busy(){return NanoBananaJob.BUSY.get();}
  static boolean start(Context c,PhotoRepository.Photo p,NanoBananaStore.Prompt prompt){
    return start(c,dependencies.store(c),NanoBananaService.class,p,prompt);
  }
  static boolean resume(Context c){return resume(c,dependencies.store(c),NanoBananaService.class);}
  static boolean discard(Context c){return discard(c,dependencies.store(c),NanoBananaService.class);}
  static boolean start(Context c,NanoBananaStore store,Class<? extends NanoBananaService> type,
      PhotoRepository.Photo photo,NanoBananaStore.Prompt prompt){
    return launch(c,store,type,photo,prompt,false,false);
  }
  static boolean resume(Context c,NanoBananaStore store,Class<? extends NanoBananaService> type){
    return launch(c,store,type,null,null,true,false);
  }
  static boolean discard(Context c,NanoBananaStore store,Class<? extends NanoBananaService> type){
    return launch(c,store,type,null,null,true,true);
  }
  static void forgetComplete(NanoBananaStore store) throws IOException {
    synchronized(NanoBananaStore.LOCK){
      if(busy())return;
      JSONObject job=store.job();
      if(job!=null&&job.optString("stage").equals("complete"))store.job(null);
    }
  }
  private static boolean launch(Context context,NanoBananaStore store,Class<? extends NanoBananaService> type,
      PhotoRepository.Photo photo,NanoBananaStore.Prompt prompt,boolean resume,boolean discard){
    Context app=context.getApplicationContext();
    Request request=null;
    try {
      synchronized(NanoBananaStore.LOCK){
        if(busy()){toast(app,"画像を生成しています");return false;}
        forgetComplete(store);
        JSONObject job=store.job();
        if(resume&&job==null){toast(app,"再開する処理はありません");return false;}
        if(!resume&&(job!=null||photo==null||prompt==null)){
          toast(app,job!=null?"残っている処理を確認してください":"写真とプロンプトを選び直してください");return false;
        }
        NanoBananaOutput output=resume?null:store.output();
        if(discard)NanoBananaJob.markDiscarding(store);
        NanoBananaStore.Prompt snapshot=prompt==null?null:new NanoBananaStore.Prompt(prompt.id,prompt.name,prompt.text);
        request=new Request(store,photo,snapshot,output,discard);
        reserved=request;NanoBananaJob.BUSY.set(true);
      }
      ContextCompat.startForegroundService(app,new Intent(app,type).putExtra(EXTRA_NONCE,request.nonce));
      toast(app,discard?"後処理を開始しました":"画像の生成を開始しました");
      return true;
    }catch(Exception failure){
      synchronized(NanoBananaStore.LOCK){
        if(request!=null&&reserved==request&&!request.consumed){reserved=null;NanoBananaJob.BUSY.set(false);}
      }
      toast(app,"処理を開始できませんでした。もう一度お試しください");return false;
    }
  }
  private static void toast(Context c,String message){MAIN.post(()->Toast.makeText(c,message,Toast.LENGTH_SHORT).show());}
  protected NanoBananaStore createStore(){return dependencies.store(this);}
  protected NanoBananaJob createJob(NanoBananaStore store){return dependencies.job(this,store);}
  /** Called on the main thread. */
  protected void notifyResult(String message){dependencies.result(message);Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
  @Override public IBinder onBind(Intent intent){return null;}
  private Notification notification(){
    NotificationManager manager=getSystemService(NotificationManager.class);
    NotificationChannel channel=new NotificationChannel(CHANNEL,"画像の生成",NotificationManager.IMPORTANCE_LOW);
    channel.setSound(null,null);channel.enableVibration(false);channel.setShowBadge(false);
    channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);manager.createNotificationChannel(channel);
    return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_nano_banana)
        .setContentTitle("画像を処理しています").setOngoing(true).setOnlyAlertOnce(true)
        .setCategory(Notification.CATEGORY_PROGRESS).setVisibility(Notification.VISIBILITY_PRIVATE)
        .setProgress(0,0,true).build();
  }
  @Override public int onStartCommand(Intent intent,int flags,int startId){
    try {
      startForeground(NOTIFICATION,notification());
    }catch(RuntimeException failure){
      // Quota/permission/restart restrictions must not take down the browsing process.
      stopping=true;
      if(worker!=null)worker.interrupt();
      try{stopForeground(STOP_FOREGROUND_REMOVE);}catch(RuntimeException ignored){}
      stopSelf();
      synchronized(NanoBananaStore.LOCK){
        String nonce=intent==null?null:intent.getStringExtra(EXTRA_NONCE);
        if(running==null&&reserved!=null&&!reserved.consumed&&reserved.nonce.equals(nonce)){
          reserved=null;NanoBananaJob.BUSY.set(false);
        }
        // A consumed lease belongs to its worker until that thread actually exits.
      }
      notifyResult("処理を開始できませんでした。もう一度お試しください");
      return START_NOT_STICKY;
    }
    Request request;
    synchronized(NanoBananaStore.LOCK){
      if(running!=null)return START_STICKY;
      String nonce=intent==null?null:intent.getStringExtra(EXTRA_NONCE);
      if(reserved!=null&&!reserved.consumed&&reserved.nonce.equals(nonce))request=reserved;
      else {
        if(busy()){
          // A stale start can arrive ahead of the admitted nonce; leave its lease intact.
          if(reserved!=null&&!reserved.consumed)return START_STICKY;
          stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);return START_NOT_STICKY;
        }
        NanoBananaStore store=createStore();
        try {
          JSONObject job=store.job();String stage=job==null?"":job.optString("stage");
          if(!stage.equals("generating")&&!stage.equals("cleanup")&&!stage.equals("discarding")){
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);return START_NOT_STICKY;
          }
          request=new Request(store,null,null,null,stage.equals("discarding"));
          reserved=request;NanoBananaJob.BUSY.set(true);
        }catch(IOException error){stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);return START_NOT_STICKY;}
      }
      request.consumed=true;running=request;
    }
    final Request owned=request;
    try {
      worker=new Thread(()->execute(owned),"nano-banana");worker.start();
    }catch(RuntimeException|OutOfMemoryError failure){
      finish(owned,"処理を開始できませんでした。もう一度お試しください");
      return START_NOT_STICKY;
    }
    return START_STICKY;
  }
  private void execute(Request request){
    String message;
    try {
      Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
      NanoBananaJob job=createJob(request.store);
      if(request.discard)job.discard();
      else job.run(request.photo,request.prompt,request.output,ignored->{});
      synchronized(NanoBananaStore.LOCK){
        JSONObject record=request.store.job();
        if(record!=null&&record.optString("stage").equals("complete"))request.store.job(null);
      }
      message=request.discard?"後処理が完了しました":"生成画像を保存しました";
    }catch(Exception|OutOfMemoryError error){
      message="処理を続けられませんでした。AI編集メニューから確認してください";
      try {
        synchronized(NanoBananaStore.LOCK){
          JSONObject record=request.store.job();
          if(record!=null&&record.optString("stage").equals("complete")){
            request.store.job(null);message="生成画像を保存しました";
          }else if(record!=null&&record.has("saved"))message="画像は保存済みです。後処理を再試行してください";
        }
      }catch(IOException ignored){}
    }
    final String result=message;
    MAIN.post(()->finish(request,result));
  }
  private void finish(Request request,String message){
    synchronized(NanoBananaStore.LOCK){
      if(running!=request)return;
      // Posting from the worker can race its return; never release a still-live lease.
      if(worker!=null&&worker.isAlive()){MAIN.postDelayed(()->finish(request,message),10);return;}
      running=null;worker=null;
      if(!destroyed){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
      // Stop/instance state transitions precede release, so a new launch cannot be dropped.
      if(reserved==request){reserved=null;NanoBananaJob.BUSY.set(false);}
    }
    if(!stopping)notifyResult(message);
  }
  @Override public void onTimeout(int startId,int fgsType){stopWorker();stopSelf();}
  private void stopWorker(){
    stopping=true;
    if(worker!=null)worker.interrupt();
    stopForeground(STOP_FOREGROUND_REMOVE);
  }
  @Override public void onDestroy(){destroyed=true;stopWorker();super.onDestroy();}
}
