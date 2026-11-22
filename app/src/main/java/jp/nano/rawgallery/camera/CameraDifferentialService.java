package jp.nano.rawgallery.camera;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import android.provider.DocumentsContract;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import jp.nano.rawgallery.CameraImportActivity;

/** Read-only Wi-Fi and mounted SD imports, serialized with the existing USB importer. */
public final class CameraDifferentialService extends Service {
    private static final String WIFI="wifi", PAIRED="paired", SD="sd", WATCH="watch", CHANNEL="camera-differential";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean running, destroyed;
    private volatile CameraPairedSession pairedSession;
    private CameraImportMetrics runMetrics;
    private final CameraRegisteredQueue registeredQueue=new CameraRegisteredQueue();
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver mediaReceiver;
    private final BooleanSupplier cancelled=()->destroyed||ImportProgress.INSTANCE.getStopRequested();
    public static void startWifi(Context c,boolean verifyAll){start(c,new Intent(c,CameraDifferentialService.class).setAction(WIFI).putExtra("verify",verifyAll));}
    public static void startRegistered(Context c,String profileId,boolean verifyAll){start(c,new Intent(c,CameraDifferentialService.class).setAction(PAIRED).putExtra("profile_id",profileId).putExtra("verify",verifyAll));}
    public static void startDocumentTree(Context c,Uri uri,boolean verifyAll){start(c,new Intent(c,CameraDifferentialService.class).setAction(SD).setData(uri).putExtra("verify",verifyAll));}
    public static void updateAuto(Context c){start(c,new Intent(c,CameraDifferentialService.class).setAction(WATCH));}
    private static void start(Context c,Intent i){c.startForegroundService(i);}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"写真の取り込み",NotificationManager.IMPORTANCE_LOW));}
    private PendingIntent progressPendingIntent() {
        Intent intent = CameraImportActivity.showProgressIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private Notification notification(String text){return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Loupe").setContentText(text).setOngoing(true).setContentIntent(progressPendingIntent()).build();}
    @Override public int onStartCommand(Intent i,int flags,int id){
        startForeground(781,notification("カメラの接続を待っています"));
        if(i!=null&&!WATCH.equals(i.getAction()))enqueue(i);
        configureWatch();
        if((i==null||WATCH.equals(i.getAction()))&&!watching()&&!running)stopSelf();
        return START_NOT_STICKY;
    }
    private boolean watching(){android.content.SharedPreferences p=getSharedPreferences("camera_import",0);return p.getBoolean("wifi_auto",false)||p.getBoolean("sd_auto",false);}
    private void configureWatch(){
        android.content.SharedPreferences p=getSharedPreferences("camera_import",0);
        ConnectivityManager cm=getSystemService(ConnectivityManager.class);
        if(p.getBoolean("wifi_auto",false)&&networkCallback==null){
            networkCallback=new ConnectivityManager.NetworkCallback(){@Override public void onAvailable(Network n){enqueue(new Intent().setAction(WIFI));}};
            cm.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),networkCallback);
        }else if(!p.getBoolean("wifi_auto",false)&&networkCallback!=null){cm.unregisterNetworkCallback(networkCallback);networkCallback=null;}
        if(p.getBoolean("sd_auto",false)&&mediaReceiver==null){
            mediaReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){String tree=getSharedPreferences("camera_import",0).getString("sd_tree_uri",null);if(tree!=null)enqueue(new Intent().setAction(SD).setData(Uri.parse(tree)));}};
            IntentFilter f=new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);f.addDataScheme("file");if(Build.VERSION.SDK_INT>=33)registerReceiver(mediaReceiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(mediaReceiver,f);
        }else if(!p.getBoolean("sd_auto",false)&&mediaReceiver!=null){unregisterReceiver(mediaReceiver);mediaReceiver=null;}
    }
    private synchronized void enqueue(Intent request){enqueue(request,false);}
    private synchronized void enqueue(Intent request,boolean continuation){
        if(destroyed)return;
        if(running){
            if(PAIRED.equals(request.getAction())&&!cancelled.getAsBoolean())
                registeredQueue.offer(request.getStringExtra("profile_id"),request.getBooleanExtra("verify",false));
            return;
        }
        if(continuation&&cancelled.getAsBoolean())return;
        running=true;
        registeredQueue.started(PAIRED.equals(request.getAction())?request.getStringExtra("profile_id"):null);
        worker.execute(()->{
            try{
                boolean accepted=CameraImportOperationGate.runIfAvailable(CameraImportGate.lock,()->{
                    if(destroyed)return;
                    // An owned direct request may reset an earlier stop; a queued handoff must preserve it.
                    if(continuation)ImportProgress.INSTANCE.handoffConnection("写真を確認しています");
                    else ImportProgress.INSTANCE.connecting("写真を確認しています");
                    runMetrics=new CameraImportMetrics(this,SD.equals(request.getAction())?"saf-sd":"wifi");
                    runMetrics.setSavingListener(this::notifyProgress);
                    try{
                        CameraPhotoPublisher.check(cancelled);
                        String permission=Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_EXTERNAL_STORAGE;
                        if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)throw new IOException("重複を確認するため写真全体へのアクセスを許可してください");
                        if(SD.equals(request.getAction()))CameraDocumentSource.requireOriginalPermission(this);
                        jp.nano.rawgallery.PhotoStorage.requireImportReady(this);
                        String volume=jp.nano.rawgallery.PhotoStorage.selected(this);
                        jp.nano.rawgallery.PhotoStorage.requireWritable(this,volume);
                        CameraPhotoImporter importer=new CameraPhotoImporter(this,CameraImportLedger.get(this),volume);importer.setMetrics(runMetrics);importer.recover(cancelled);
                        if(PAIRED.equals(request.getAction()))paired(importer,request.getStringExtra("profile_id"),request.getBooleanExtra("verify",getSharedPreferences("camera_import",0).getBoolean("wifi_verify_all",false)));
                        else if(WIFI.equals(request.getAction()))wifi(importer,request.getBooleanExtra("verify",getSharedPreferences("camera_import",0).getBoolean("wifi_verify_all",false)));
                        else if(SD.equals(request.getAction())&&request.getData()!=null)sd(importer,request.getData(),request.getBooleanExtra("verify",getSharedPreferences("camera_import",0).getBoolean("sd_verify_all",false)));
                        else throw new IOException("接続元がありません");
                    }catch(Exception e){runMetrics.terminal(cancelled.getAsBoolean()?"cancelled":"failed");ImportProgress.INSTANCE.finish(cancelled.getAsBoolean()?"取り込みを中断しました":(e instanceof CameraPairedSession.MissingWifiException||e instanceof CameraDocumentSource.OriginalAccessException||e instanceof jp.nano.rawgallery.PhotoStorage.Unavailable)?e.getMessage():"取り込みを完了できませんでした。接続と写真へのアクセスを確認してください");android.util.Log.w("CameraDifferential","Import failed: "+e.getClass().getSimpleName());}
                });
                if(!accepted&&!destroyed)getSystemService(NotificationManager.class).notify(783,
                        new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera)
                        .setContentTitle("取り込みを開始できませんでした")
                        .setContentText("別の取り込みが実行中です。完了後にもう一度お試しください")
                        .setContentIntent(progressPendingIntent())
                        .setAutoCancel(true).build());
            }finally{operationFinished();}
        });
    }
    private synchronized void operationFinished(){
        CameraRegisteredQueue.Request next=registeredQueue.finished(cancelled.getAsBoolean());
        running=false;
        if(destroyed)return;
        if(next!=null){
            enqueue(new Intent().setAction(PAIRED).putExtra("profile_id",next.profileId).putExtra("verify",next.verify),true);
            if(running)return;
        }
        if(watching())getSystemService(NotificationManager.class).notify(781,notification("カメラの接続を待っています"));
        else stopSelf();
    }
    private static final class WifiSession {
        final Network network; final Ep7WifiClient client;
        WifiSession(Network network,Ep7WifiClient client){this.network=network;this.client=client;}
    }
    private void paired(CameraPhotoImporter importer,String profileId,boolean verify)throws IOException{
        try(CameraPairedSession session=CameraPairedSession.connect(this,profileId,cancelled,
                text->ImportProgress.INSTANCE.waiting(text))){
            pairedSession=session;
            wifi(importer,new WifiSession(session.network,session.client),verify,session::isCancelled,System.nanoTime());
        }finally{pairedSession=null;}
    }
    private void wifi(CameraPhotoImporter importer,boolean verify)throws IOException{
        ConnectivityManager cm=getSystemService(ConnectivityManager.class);
        long start=System.nanoTime();
        WifiSession session=CameraConnectionWait.await(15000,waitingCancelled->{
            Network selected=CameraWifiNetwork.select(cm);
            if(selected==null)return null;
            final Network network=selected;
            // The short deadline applies to discovery; a validated session keeps its bound Network for the import.
            java.util.concurrent.atomic.AtomicBoolean connecting=new java.util.concurrent.atomic.AtomicBoolean(true);
            Ep7WifiClient client=new Ep7WifiClient(url->(HttpURLConnection)network.openConnection(url,java.net.Proxy.NO_PROXY),()->cancelled.getAsBoolean()||(connecting.get()&&waitingCancelled.getAsBoolean()));
            client.probe();connecting.set(false);
            return new WifiSession(network,client);
        },cancelled);
        wifi(importer,session,verify,cancelled,start);
    }
    private void wifi(CameraPhotoImporter importer,WifiSession session,boolean verify,BooleanSupplier sessionCancelled,long start)throws IOException{
        ConnectivityManager cm=getSystemService(ConnectivityManager.class);
        CameraPhotoPublisher.check(sessionCancelled);
        ImportProgress.INSTANCE.begin("E-P7 Wi-Fi");ImportProgress.INSTANCE.photoListing(0,-1);notifyProgress();
        long listStart=android.os.SystemClock.elapsedRealtime();
        Ep7WifiClient client=session.client;Ep7WifiClient.Listing listing=client.list();
        runMetrics.listingMs=android.os.SystemClock.elapsedRealtime()-listStart;
        runMetrics.listed=listing.photos.size();runMetrics.candidates=listing.photos.size();
        ImportProgress.INSTANCE.photoListing(listing.photos.size(),listing.photos.size());
        String identity=CameraSourceIdentity.wifi(this,session.network);
        boolean stable=identity!=null;
        String source=stable?identity:"wifi-session:"+session.network.getNetworkHandle();
        int added=0,done=0;long bytes=0;runMetrics.content();notifyProgress();
        for(Ep7WifiClient.Entry e:listing.photos){
            CameraPhotoPublisher.check(sessionCancelled);
            if(cm.getNetworkCapabilities(session.network)==null)throw new IOException("Camera disconnected");
            String currentIdentity=CameraSourceIdentity.wifi(this,session.network);
            if(stable&&currentIdentity!=null&&!identity.equals(currentIdentity))throw new IOException("Camera connection changed");
            boolean verifiedIdentity=stable&&currentIdentity!=null;
            CameraImportFile file=new CameraImportFile(verifiedIdentity?source:"wifi-session:"+session.network.getNetworkHandle(),e.path.substring(1),e.bytes,e.date+":"+e.time,fatTime(e.date,e.time));
            CameraPhotoImporter.Result r=retry(importer,file,out->client.copyOriginal(e,out),verifiedIdentity,verify,sessionCancelled);
            if(r.imported){added++;runMetrics.saved++;}else runMetrics.matches++;
            bytes+=r.sourceBytesRead;runMetrics.processed++;runMetrics.content();
            ImportProgress.INSTANCE.update(++done,listing.photos.size());notifyProgress();
        }
        CameraPhotoPublisher.check(sessionCancelled);
        finish(added,done,bytes,start,false);
    }
    private CameraPhotoImporter.Result retry(CameraPhotoImporter importer,CameraImportFile f,CameraImportCopy.Copy copy,boolean stable,boolean verify)throws IOException{
        return retry(importer,f,copy,stable,verify,cancelled);
    }
    private CameraPhotoImporter.Result retry(CameraPhotoImporter importer,CameraImportFile f,CameraImportCopy.Copy copy,boolean stable,boolean verify,BooleanSupplier operationCancelled)throws IOException{
        return retry(importer,f,copy,stable,verify,operationCancelled,false);
    }
    private CameraPhotoImporter.Result retry(CameraPhotoImporter importer,CameraImportFile f,CameraImportCopy.Copy copy,boolean stable,boolean verify,BooleanSupplier operationCancelled,boolean sdOriginal)throws IOException{
        IOException first=null;
        for(int i=0;i<2;i++)try{
            if(sdOriginal)CameraDocumentSource.requireOriginalPermission(this);
            return importer.importPhoto(f,copy,null,stable,verify,operationCancelled);
        }catch(InterruptedIOException e){throw e;}catch(CameraDocumentSource.OriginalAccessException e){throw e;}catch(IOException e){
            if(first==null)first=e;CameraPhotoPublisher.check(operationCancelled);
            if(sdOriginal)CameraDocumentSource.requireOriginalPermission(this);
            importer.recover(operationCancelled);
        }
        throw first;
    }
    private static long fatTime(int date,int time){try{return java.time.LocalDateTime.of(1980+(date>>9),(date>>5)&15,date&31,time>>11,(time>>5)&63,(time&31)*2).atZone(java.time.ZoneId.systemDefault()).toEpochSecond();}catch(Exception e){return 0;}}
    private void sd(CameraPhotoImporter importer,Uri tree,boolean verify)throws IOException{
        CameraDocumentSource.requireOriginalPermission(this);
        long start=System.nanoTime();ImportProgress.INSTANCE.begin("SDカード");ImportProgress.INSTANCE.photoListing(0,-1);notifyProgress();
        long listStart=android.os.SystemClock.elapsedRealtime();
        CameraDocumentSource.Listing listing=CameraDocumentSource.list(this,tree,cancelled);
        runMetrics.listingMs=android.os.SystemClock.elapsedRealtime()-listStart;
        runMetrics.listed=listing.photos.size();runMetrics.candidates=listing.photos.size();
        ImportProgress.INSTANCE.photoListing(listing.photos.size(),listing.photos.size());
        int added=0,done=0;long bytes=0;runMetrics.content();notifyProgress();
        for(CameraDocumentSource.Entry entry:listing.photos){
            CameraPhotoPublisher.check(cancelled);
            CameraPhotoImporter.Result result=retry(importer,entry.file,out->CameraDocumentSource.copy(this,entry,out,cancelled),listing.stable,verify,cancelled,true);
            if(result.imported){added++;runMetrics.saved++;}else runMetrics.matches++;
            bytes+=result.sourceBytesRead;runMetrics.processed++;runMetrics.content();
            ImportProgress.INSTANCE.update(++done,listing.photos.size());notifyProgress();
        }
        CameraDocumentSource.requireOriginalPermission(this);
        CameraPhotoPublisher.check(cancelled);
        finish(added,done,bytes,start,true);
    }
    private void notifyProgress(){getSystemService(NotificationManager.class).notify(781,notification(jp.nano.rawgallery.ImportProgressPresentation.current().accessibility()));}
    private void finish(int added,int count,long bytes,long start,boolean card){
        runMetrics.terminal("complete");
        double seconds=(System.nanoTime()-start)/1e9;
        android.util.Log.i("CameraDifferential",String.format(Locale.ROOT,"files=%d added=%d readBytes=%d seconds=%.3f",count,added,bytes,seconds));
        ImportProgress.INSTANCE.finish(added+"枚追加・"+(count-added)+"枚取り込み済み"+(card?"。カメラへカードを戻しましたか":""));
        if(card)getSystemService(NotificationManager.class).notify(782,new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("カメラへカードを戻しましたか").setContentText("SDカードの取り込みが完了しました").setAutoCancel(true).build());
    }
    @Override public void onDestroy(){destroyed=true;synchronized(this){registeredQueue.clear();}CameraPairedSession session=pairedSession;if(session!=null)session.close();if(networkCallback!=null)getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);if(mediaReceiver!=null)unregisterReceiver(mediaReceiver);worker.shutdownNow();super.onDestroy();}
}
