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
    private static final String WIFI="wifi", SD="sd", WATCH="watch", CHANNEL="camera-differential";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean running, destroyed;
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver mediaReceiver;
    private final BooleanSupplier cancelled=()->destroyed||ImportProgress.INSTANCE.getStopRequested();
    public static void startWifi(Context c,boolean verifyAll){start(c,new Intent(c,CameraDifferentialService.class).setAction(WIFI).putExtra("verify",verifyAll));}
    public static void startDocumentTree(Context c,Uri uri,boolean verifyAll){start(c,new Intent(c,CameraDifferentialService.class).setAction(SD).setData(uri).putExtra("verify",verifyAll));}
    public static void updateAuto(Context c){start(c,new Intent(c,CameraDifferentialService.class).setAction(WATCH));}
    private static void start(Context c,Intent i){c.startForegroundService(i);}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"写真の取り込み",NotificationManager.IMPORTANCE_LOW));}
    private Notification notification(String text){return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Loupe").setContentText(text).setOngoing(true).setContentIntent(PendingIntent.getActivity(this,0,new Intent(this,CameraImportActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT)).build();}
    @Override public int onStartCommand(Intent i,int flags,int id){
        startForeground(781,notification("カメラの接続を待っています"));
        configureWatch();
        if(i!=null&&!WATCH.equals(i.getAction()))enqueue(i);
        else if(!watching()&&!running)stopSelf();
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
    private synchronized void enqueue(Intent request){
        if(running||destroyed)return;running=true;
        worker.execute(()->{
            boolean locked=CameraImportGate.lock.tryLock();
            try{
                if(!locked)throw new IOException("別の取り込みが実行中です");
                String permission=Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_EXTERNAL_STORAGE;
                if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)throw new IOException("重複を確認するため写真全体へのアクセスを許可してください");
                ImportProgress.INSTANCE.connecting("写真を確認しています");
                CameraPhotoImporter importer=new CameraPhotoImporter(this);importer.recover(cancelled);
                if(WIFI.equals(request.getAction()))wifi(importer,request.getBooleanExtra("verify",true));
                else if(SD.equals(request.getAction())&&request.getData()!=null)sd(importer,request.getData(),request.getBooleanExtra("verify",true));
                else throw new IOException("接続元がありません");
            }catch(Exception e){if(locked)ImportProgress.INSTANCE.finish(cancelled.getAsBoolean()?"取り込みを中断しました":"取り込みを完了できませんでした。接続と写真へのアクセスを確認してください");android.util.Log.w("CameraDifferential","Import failed: "+e.getClass().getSimpleName());}
            finally{if(locked)CameraImportGate.lock.unlock();running=false;if(!destroyed){if(watching())getSystemService(NotificationManager.class).notify(781,notification("カメラの接続を待っています"));else stopSelf();}}
        });
    }
    private void wifi(CameraPhotoImporter importer,boolean verify)throws IOException{
        ConnectivityManager cm=getSystemService(ConnectivityManager.class);Network selected=null;
        for(Network n:cm.getAllNetworks()){NetworkCapabilities cap=cm.getNetworkCapabilities(n);if(cap!=null&&cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){if(selected!=null)throw new IOException("複数のWi-Fi接続があります");selected=n;}}
        if(selected==null)throw new IOException("Wi-Fi接続がありません");
        final Network network=selected;
        WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiInfo info=wm==null?null:wm.getConnectionInfo();int networkId=info==null?-1:info.getNetworkId();
        String source="wifi-session:"+network.getNetworkHandle();
        Ep7WifiClient client=new Ep7WifiClient(url->(HttpURLConnection)network.openConnection(url,java.net.Proxy.NO_PROXY),cancelled);
        long start=System.nanoTime();Ep7WifiClient.Listing listing=client.list();
        ImportProgress.INSTANCE.begin("E-P7 Wi-Fi");int added=0,done=0;long bytes=0;
        for(Ep7WifiClient.Entry e:listing.photos){
            CameraPhotoPublisher.check(cancelled);
            CameraImportFile file=new CameraImportFile(source,e.path.substring(1),e.bytes,e.date+":"+e.time,fatTime(e.date,e.time));
            CameraPhotoImporter.Result r=retry(importer,file,out->client.copyOriginal(e,out),false,verify);
            if(r.imported)added++;bytes+=r.sourceBytesRead;ImportProgress.INSTANCE.update(++done,listing.photos.size());
        }
        finish(added,done,bytes,start,false);
    }
    private CameraPhotoImporter.Result retry(CameraPhotoImporter importer,CameraImportFile f,CameraImportCopy.Copy copy,boolean stable,boolean verify)throws IOException{
        IOException first=null;
        for(int i=0;i<2;i++)try{return importer.importPhoto(f,copy,null,stable,verify,cancelled);}catch(InterruptedIOException e){throw e;}catch(IOException e){if(first==null)first=e;CameraPhotoPublisher.check(cancelled);importer.recover(cancelled);}
        throw first;
    }
    private static long fatTime(int date,int time){try{return java.time.LocalDateTime.of(1980+(date>>9),(date>>5)&15,date&31,time>>11,(time>>5)&63,(time&31)*2).atZone(java.time.ZoneId.systemDefault()).toEpochSecond();}catch(Exception e){return 0;}}
    private static final class Document {final Uri uri;final CameraImportFile file;Document(Uri u,CameraImportFile f){uri=u;file=f;}}
    private void sd(CameraPhotoImporter importer,Uri tree,boolean verify)throws IOException{
        if(!DocumentsContract.isTreeUri(tree))throw new IOException("SDフォルダを選択してください");
        String root=DocumentsContract.getTreeDocumentId(tree);String volume=root.split(":",2)[0];
        boolean stable=volume.matches("(?i)[0-9a-f]{4}-[0-9a-f]{4}");
        String source=stable?"sd:"+volume.replace("-","").toUpperCase(Locale.ROOT):"saf:"+tree;
        List<Document> files=new ArrayList<>();int[] visited={0};
        String rootName=root.contains(":")?root.substring(root.indexOf(':')+1):"";
        if(!rootName.isEmpty()&&!rootName.equalsIgnoreCase("DCIM"))throw new IOException("SDカードのルートまたはDCIMを選択してください");
        long start=System.nanoTime();scan(tree,root,rootName.isEmpty()?"":"DCIM",source,files,visited,0);
        ImportProgress.INSTANCE.begin("SDカード");int added=0,done=0;long bytes=0;
        for(Document d:files){CameraPhotoPublisher.check(cancelled);CameraPhotoImporter.Result r=retry(importer,d.file,out->{try(InputStream in=getContentResolver().openInputStream(d.uri)){if(in==null)throw new IOException("SD read failed");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){CameraPhotoPublisher.check(cancelled);out.write(b,0,n);}}},stable,verify);if(r.imported)added++;bytes+=r.sourceBytesRead;ImportProgress.INSTANCE.update(++done,files.size());}
        finish(added,done,bytes,start,true);
    }
    private void scan(Uri tree,String id,String path,String source,List<Document> files,int[] visited,int depth)throws IOException{
        CameraPhotoPublisher.check(cancelled);if(depth>5||++visited[0]>1000)throw new IOException("SD directory limit");
        String[] projection={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        try(Cursor c=getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,id),projection,null,null,null)){
            if(c==null)throw new IOException("SD listing failed");
            while(c.moveToNext()){
                CameraPhotoPublisher.check(cancelled);String name=c.getString(1);if(name==null||name.contains("/")||name.contains("\\")||name.equals(".")||name.equals(".."))throw new IOException("Invalid SD name");
                String child=c.getString(0),mime=c.getString(2),relative=path.isEmpty()?name:path+"/"+name;
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){if(!path.isEmpty()||name.equalsIgnoreCase("DCIM"))scan(tree,child,relative,source,files,visited,depth+1);}
                else if(!path.isEmpty()&&MediaTypes.INSTANCE.kindOf(name)!=null&&!MediaTypes.INSTANCE.isVideo(name)){
                    if(files.size()>=20000)throw new IOException("SD file limit");long size=c.getLong(3),modified=c.getLong(4);if(size<=0||size>Ep7WifiClient.MAX_FILE_BYTES)throw new IOException("Unsupported photo size");
                    files.add(new Document(DocumentsContract.buildDocumentUriUsingTree(tree,child),new CameraImportFile(source,relative,size,Long.toString(modified),modified/1000)));
                }
            }
        }
    }
    private void finish(int added,int count,long bytes,long start,boolean card){
        double seconds=(System.nanoTime()-start)/1e9;
        android.util.Log.i("CameraDifferential",String.format(Locale.ROOT,"files=%d added=%d readBytes=%d seconds=%.3f",count,added,bytes,seconds));
        ImportProgress.INSTANCE.finish(added+"枚追加・"+(count-added)+"枚取り込み済み"+(card?"。カメラへカードを戻しましたか":""));
        if(card)getSystemService(NotificationManager.class).notify(782,new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("カメラへカードを戻しましたか").setContentText("SDカードの取り込みが完了しました").setAutoCancel(true).build());
    }
    @Override public void onDestroy(){destroyed=true;if(networkCallback!=null)getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);if(mediaReceiver!=null)unregisterReceiver(mediaReceiver);worker.shutdownNow();super.onDestroy();}
}
