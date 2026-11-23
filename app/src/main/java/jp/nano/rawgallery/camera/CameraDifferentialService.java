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
import jp.nano.rawgallery.PhotoStorage;

/** Read-only Wi-Fi and mounted SD imports, serialized with the existing USB importer. */
public final class CameraDifferentialService extends Service {
    private static final String WIFI="wifi", PAIRED="paired", SD="sd", WATCH="watch", CHANNEL="camera-differential";
    private static final String CONFIRMED_SD = "confirmed-sd";
    private static final String BUSY = "別の取り込みが実行中です。処理が終わってからやり直してください。";
    private static final String SD_INDEX_RETRY = "SDカードを接続したまま、Androidへの登録を待ってから、もう一度取り込んでください。";
    private static final String SOURCE_CHANGED = "カードの接続または保存先が変わりました。取り込み元を確認してください。";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean running, destroyed;
    private volatile CameraPairedSession pairedSession;
    private CameraImportMetrics runMetrics;
    private final CameraRegisteredQueue registeredQueue=new CameraRegisteredQueue();
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver mediaReceiver;
    private static volatile boolean watchingSdMounts;

    static boolean isWatchingSdMounts() { return watchingSdMounts; }
    private final BooleanSupplier cancelled=()->destroyed||ImportProgress.INSTANCE.getStopRequested();
    public static void startWifi(Context c,boolean verifyAll){start(c,new Intent(c,CameraDifferentialService.class).setAction(WIFI).putExtra("verify",verifyAll));}
    public static void startRegistered(Context c,String profileId,boolean verifyAll){start(c,new Intent(c,CameraDifferentialService.class).setAction(PAIRED).putExtra("profile_id",profileId).putExtra("verify",verifyAll));}
    private static final String EXPECTED_CARD = "sd_expected_destination", SD_KINDS = "sd_kinds";
    private static final String SD_GENERATION = "sd_detach_generation", SD_DESTINATION = "sd_confirmed_destination";

    private static final String SD_CONFIRMATION = "sd_local_confirmation";
    private static final String SD_PICKED = "sd_picked_tree", SD_REGISTER_ONLY = "sd_register_only";
    private static final String SD_PRIOR_TREE = "sd_prior_tree";

    /** A one-shot authorization carried only to the non-exported service in this process. */
    public static final class SdConfirmation extends Binder {
        private enum State { OPEN, CLAIMED, CANCELLED, FINISHED }
        private final java.util.concurrent.atomic.AtomicReference<State> state =
                new java.util.concurrent.atomic.AtomicReference<>(State.OPEN);
        private final Handler main = new Handler(Looper.getMainLooper());
        private final java.util.function.BiConsumer<SdConfirmation, String> response;

        public SdConfirmation(java.util.function.BiConsumer<SdConfirmation, String> response) {
            this.response = response;
        }

        public void cancel() { state.compareAndSet(State.OPEN, State.CANCELLED); }
        boolean isOpen() { return state.get() == State.OPEN; }
        boolean claim() { return state.compareAndSet(State.OPEN, State.CLAIMED); }

        void complete(String failure) {
            State previous = state.getAndSet(State.FINISHED);
            if (previous != State.CANCELLED && previous != State.FINISHED)
                main.post(() -> response.accept(this, failure));
        }
    }

    public static void submitConfirmedSd(Context context, Uri tree, boolean verifyAll, String expectedCard,
            String kinds, long generation, String destination, boolean picked, boolean registerOnly,
            String priorTree, SdConfirmation confirmation) {
        Intent request = sdRequest(context, tree, destination, generation).setAction(CONFIRMED_SD)
                .putExtra("verify", verifyAll).putExtra(SD_PICKED, picked)
                .putExtra(SD_REGISTER_ONLY, registerOnly).putExtra(SD_PRIOR_TREE, priorTree);
        if (expectedCard != null) request.putExtra(EXPECTED_CARD, expectedCard);
        if (kinds != null) request.putExtra(SD_KINDS, kinds);
        Bundle extras = new Bundle();
        extras.putBinder(SD_CONFIRMATION, confirmation);
        request.putExtras(extras);
        start(context, request);
    }

    private static SdConfirmation confirmationOf(Intent request) {
        try {
            Bundle extras = request.getExtras();
            return extras != null && extras.getBinder(SD_CONFIRMATION) instanceof SdConfirmation confirmation
                    ? confirmation : null;
        } catch (BadParcelableException malformed) {
            return null;
        }
    }

    private static Intent sdRequest(Context context, Uri tree, String destination, long generation) {
        return new Intent(context, CameraDifferentialService.class).setAction(SD).setData(tree)
                .putExtra(SD_DESTINATION, destination).putExtra(SD_GENERATION, generation);
    }
    public static void updateAuto(Context c){start(c,new Intent(c,CameraDifferentialService.class).setAction(WATCH));}
    private static void start(Context c,Intent i){c.startForegroundService(i);}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onCreate(){super.onCreate();MountedCameraCard.startObserving(this);getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"写真の取り込み",NotificationManager.IMPORTANCE_LOW));}
    private PendingIntent progressPendingIntent() {
        Intent intent = CameraImportActivity.showProgressIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private Notification notification(String text){return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Loupe").setContentText(text).setOngoing(true).setContentIntent(progressPendingIntent()).build();}
    @Override public synchronized int onStartCommand(Intent i,int flags,int id){
        if (!running) startForeground(781,notification("カメラの接続を待っています"));
        if(i!=null&&!WATCH.equals(i.getAction()))enqueue(i);
        configureWatch();
        if(!watching()&&!running)stopSelf();
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
            mediaReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent event) {
                    String tree = getSharedPreferences("camera_import", 0).getString("sd_tree_uri", null);
                    if (tree != null) enqueue(sdRequest(context, Uri.parse(tree), PhotoStorage.selected(context),
                            MountedCameraCard.detachGeneration()));
                }
            };
            IntentFilter f=new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);f.addDataScheme("file");if(Build.VERSION.SDK_INT>=33)registerReceiver(mediaReceiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(mediaReceiver,f);
            watchingSdMounts=true;
        }else if(!p.getBoolean("sd_auto",false)&&mediaReceiver!=null){unregisterReceiver(mediaReceiver);mediaReceiver=null;watchingSdMounts=false;}
    }
    private synchronized void enqueue(Intent request){enqueue(request,false);}
    private synchronized void enqueue(Intent request, boolean continuation) {
        boolean confirmedRequest = CONFIRMED_SD.equals(request.getAction());
        SdConfirmation confirmation = confirmedRequest ? confirmationOf(request) : null;
        if (confirmedRequest && (confirmation == null || !confirmation.isOpen())) return;
        if (destroyed || running) {
            if (!destroyed && PAIRED.equals(request.getAction()) && !cancelled.getAsBoolean())
                registeredQueue.offer(request.getStringExtra("profile_id"), request.getBooleanExtra("verify", false));
            if (confirmation != null) confirmation.complete(BUSY);
            return;
        }
        if (continuation && cancelled.getAsBoolean()) return;
        boolean sdRequest = confirmedRequest || SD.equals(request.getAction());
        String queuedVolume = request.getStringExtra(SD_DESTINATION);
        long sdGeneration = request.getLongExtra(SD_GENERATION, -1);
        String requestedCard = request.getStringExtra(EXPECTED_CARD);
        String pinnedCard = requestedCard != null || !sdRequest || queuedVolume == null ? requestedCard
                : MountedCameraCard.targetWithGenericReader(this, queuedVolume);
        running = true;
        registeredQueue.started(PAIRED.equals(request.getAction()) ? request.getStringExtra("profile_id") : null);
        try {
            worker.execute(() -> {
                try {
                    boolean acquired = CameraImportOperationGate.runIfAvailable(CameraImportGate.lock,
                            () -> runOwnedRequest(request, confirmation, continuation, sdRequest,
                                    queuedVolume, pinnedCard, sdGeneration));
                    if (!acquired) {
                        if (confirmation != null) confirmation.complete(BUSY);
                        else if (!destroyed) getSystemService(NotificationManager.class).notify(783,
                                new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
                                        .setContentTitle("取り込みを開始できませんでした").setContentText(BUSY)
                                        .setContentIntent(progressPendingIntent()).setAutoCancel(true).build());
                    }
                } finally {
                    operationFinished();
                }
            });
        } catch (RejectedExecutionException stopped) {
            if (confirmation != null) confirmation.complete(BUSY);
            operationFinished();
        }
    }

    private void beginImport(boolean continuation, boolean sdRequest) {
        if (continuation) ImportProgress.INSTANCE.handoffConnection("写真を確認しています");
        else ImportProgress.INSTANCE.connecting("写真を確認しています");
        runMetrics = new CameraImportMetrics(this, sdRequest ? "saf-sd" : "wifi");
        runMetrics.setSavingListener(this::notifyProgress);
    }

    private void runOwnedRequest(Intent request, SdConfirmation confirmation, boolean continuation, boolean sdRequest,
            String queuedVolume, String pinnedCard, long generation) {
        if (destroyed) {
            if (confirmation != null) confirmation.complete(BUSY);
            return;
        }
        if (confirmation != null && !confirmation.isOpen()) return;
        boolean progressStarted = confirmation == null;
        if (progressStarted) beginImport(continuation, sdRequest);
        // A prior operation's stop flag must not cancel a new, explicitly confirmed request.
        BooleanSupplier preparing = confirmation == null ? cancelled : () -> destroyed || !confirmation.isOpen();
        try {
            CameraPhotoPublisher.check(preparing);
            Uri tree = request.getData();
            boolean picked = confirmation != null && request.getBooleanExtra(SD_PICKED, false);
            boolean registerOnly = confirmation != null && request.getBooleanExtra(SD_REGISTER_ONLY, false);
            if (registerOnly && !picked) throw new MountedCameraCard.Unavailable(SOURCE_CHANGED);
            if (!registerOnly) {
                requirePhotoLibraryPermission();
                if (sdRequest) CameraDocumentSource.requireOriginalPermission(this);
                PhotoStorage.requireImportReady(this);
            }
            String volume = PhotoStorage.selected(this);
            String source = null;
            Set<MediaTypes.Kind> kinds = sdKinds(request.getStringExtra(SD_KINDS));
            if (sdRequest) {
                requireSdDestination(queuedVolume, volume);
                if (picked) {
                    source = preparePickedSource(request, volume, generation, preparing);
                } else {
                    requireSdSource(tree, null, volume, generation);
                    String expectedCard = pinnedCard != null ? pinnedCard
                            : MountedCameraCard.targetWithGenericReader(this, volume);
                    BooleanSupplier preflight = sdStopped(tree, volume, generation, preparing);
                    try {
                        source = expectedCard != null
                                ? MountedCameraCard.requireDistinctSource(this, tree, expectedCard, volume, true, preflight)
                                : MountedCameraCard.mountedSourceVolume(this, tree);
                        requireSdSource(tree, source, volume, generation);
                    } catch (InterruptedIOException interrupted) {
                        if (!preparing.getAsBoolean()) requireSdSource(tree, source, volume, generation);
                        throw interrupted;
                    }
                }
            }
            if (!registerOnly) PhotoStorage.requireWritable(this, volume);
            if (confirmation != null) {
                String mountedSource = source;
                if (!claimSd(confirmation, () -> {
                    if (picked) commitPickedSource(request, volume, generation, mountedSource);
                    else requireSdSource(tree, mountedSource, volume, generation);
                })) return;
                if (registerOnly) {
                    confirmation.complete(null);
                    return;
                }
                beginImport(false, true);
                progressStarted = true;
                confirmation.complete(null);
            }
            BooleanSupplier stopped = sdRequest ? sdStopped(tree, volume, generation, cancelled) : cancelled;
            CameraPhotoImporter importer = new CameraPhotoImporter(this, CameraImportLedger.get(this), volume);
            importer.setMetrics(runMetrics);
            importer.recover(stopped);
            if (sdRequest) sd(importer, tree,
                    request.getBooleanExtra("verify", getSharedPreferences("camera_import", 0).getBoolean("sd_verify_all", false)),
                    source, kinds, volume, generation);
            else if (PAIRED.equals(request.getAction())) paired(importer, request.getStringExtra("profile_id"),
                    request.getBooleanExtra("verify", getSharedPreferences("camera_import", 0).getBoolean("wifi_verify_all", false)));
            else if (WIFI.equals(request.getAction())) wifi(importer,
                    request.getBooleanExtra("verify", getSharedPreferences("camera_import", 0).getBoolean("wifi_verify_all", false)));
            else throw new IOException("接続元がありません");
        } catch (Exception failure) {
            String message = failure instanceof CameraPairedSession.MissingWifiException
                    || failure instanceof CameraDocumentSource.OriginalAccessException
                    || failure instanceof CameraDocumentSource.ListingException
                    || failure instanceof CameraPhotoImporter.SavedPhotoConflict
                    || failure instanceof MountedCameraCard.Unavailable || failure instanceof PhotoStorage.Unavailable
                    ? failure.getMessage() : "取り込みを完了できませんでした。接続と写真へのアクセスを確認してください";
            if (progressStarted) {
                runMetrics.terminal(cancelled.getAsBoolean() ? "cancelled" : "failed");
                ImportProgress.INSTANCE.finish(cancelled.getAsBoolean() ? "取り込みを中断しました" : message);
            } else if (confirmation != null) {
                confirmation.complete(message);
            }
            android.util.Log.w("CameraDifferential", "Import failed: " + failure.getClass().getSimpleName());
        }
    }

    @FunctionalInterface
    interface SdCommit { void run() throws IOException; }

    static boolean claimSd(SdConfirmation confirmation, SdCommit commit) throws IOException {
        if (!CameraImportGate.lock.isHeldByCurrentThread())
            throw new IllegalStateException("The SD admission worker must own the camera gate");
        if (!confirmation.claim()) return false;
        commit.run();
        return true;
    }

    private String preparePickedSource(Intent request, String target, long generation, BooleanSupplier stopped)
            throws IOException {
        requirePickedSelection(request, target, generation);
        Uri tree = request.getData();
        String source = MountedCameraCard.requireRemovableSource(this, tree, target);
        if (!CameraDocumentSource.isReachableDirectory(getContentResolver(), tree, stopped))
            throw new MountedCameraCard.Unavailable("選んだフォルダを確認できません。接続を確認するか、フォルダを選び直してください。");
        return source;
    }

    private void requirePickedSelection(Intent request, String target, long generation) throws IOException {
        MountedCameraCard.requireGeneration(generation);
        requireSdDestination(target, PhotoStorage.selected(this));
        String previous = request.getStringExtra(SD_PRIOR_TREE);
        String current = getSharedPreferences("camera_import", 0).getString("sd_tree_uri", null);
        if (destroyed || !Objects.equals(previous, current) || !CameraDocumentSource.isImportTree(request.getData(), false))
            throw new MountedCameraCard.Unavailable(SOURCE_CHANGED);
    }

    private void commitPickedSource(Intent request, String target, long generation, String source) throws IOException {
        requirePickedSelection(request, target, generation);
        Uri tree = request.getData();
        if (!source.equals(MountedCameraCard.requireRemovableSource(this, tree, target)))
            throw new MountedCameraCard.Unavailable(SOURCE_CHANGED);
        var resolver = getContentResolver();
        boolean hadGrant = resolver.getPersistedUriPermissions().stream()
                .anyMatch(grant -> grant.isReadPermission() && grant.getUri().equals(tree));
        try {
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException denied) {
            throw new MountedCameraCard.Unavailable("フォルダの読み取り許可を保存できませんでした。選び直してください。");
        }
        boolean saved = false;
        try {
            requirePickedSelection(request, target, generation);
            if (!source.equals(MountedCameraCard.requireRemovableSource(this, tree, target)))
                throw new MountedCameraCard.Unavailable(SOURCE_CHANGED);
            var prefs = getSharedPreferences("camera_import", 0);
            saved = prefs.edit().putString("sd_tree_uri", tree.toString()).commit();
            if (!saved) {
                prefs.edit().putString("sd_tree_uri", request.getStringExtra(SD_PRIOR_TREE)).apply();
                throw new MountedCameraCard.Unavailable("取り込み元のフォルダを保存できませんでした。");
            }
        } finally {
            if (!saved && !hadGrant) {
                try { resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (SecurityException | IllegalArgumentException unavailable) {
                    android.util.Log.w("CameraDifferential", "Could not release uncommitted SD permission");
                }
            }
        }
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
        runMetrics.terminal("complete");
        logResult(added, done, bytes, start);
        ImportProgress.INSTANCE.finish(added+"枚追加・"+(done-added)+"枚取り込み済み");
    }
    private CameraPhotoImporter.Result retry(CameraPhotoImporter importer,CameraImportFile f,CameraImportCopy.Copy copy,boolean stable,boolean verify)throws IOException{
        return retry(importer,f,copy,stable,verify,cancelled);
    }
    private CameraPhotoImporter.Result retry(CameraPhotoImporter importer,CameraImportFile f,CameraImportCopy.Copy copy,boolean stable,boolean verify,BooleanSupplier operationCancelled)throws IOException{
        return retry(importer, f, copy, stable, verify, operationCancelled, false, null,
                CameraPhotoPublisher.NO_COMMIT_CHECK);
    }
    private CameraPhotoImporter.Result retry(CameraPhotoImporter importer, CameraImportFile f, CameraImportCopy.Copy copy,
            boolean stable, boolean verify, BooleanSupplier operationCancelled, boolean sdOriginal,
            CameraPhotoPublisher.CommitCheck check, CameraPhotoPublisher.CommitCheck beforeCommit) throws IOException {
        IOException first=null;
        var attempt = new CameraPhotoImporter.Attempt();
        for(int i=0;i<2;i++)try{
            if(sdOriginal)CameraDocumentSource.requireOriginalPermission(this);
            if(check!=null)check.check();
            return importer.importPhoto(f, copy, null, stable, verify, operationCancelled, beforeCommit, attempt,
                    sdOriginal && stable);
        }catch(InterruptedIOException e){throw e;}catch(CameraDocumentSource.OriginalAccessException | CameraDocumentSource.ListingException
                | MountedCameraCard.Unavailable | CameraPhotoImporter.SavedPhotoConflict e){throw e;}catch(IOException e){
            if(first==null)first=e;CameraPhotoPublisher.check(operationCancelled);
            if(sdOriginal)CameraDocumentSource.requireOriginalPermission(this);
            importer.recover(operationCancelled);
        }
        throw first;
    }
    private static long fatTime(int date,int time){try{return java.time.LocalDateTime.of(1980+(date>>9),(date>>5)&15,date&31,time>>11,(time>>5)&63,(time&31)*2).atZone(java.time.ZoneId.systemDefault()).toEpochSecond();}catch(Exception e){return 0;}}
    static Set<MediaTypes.Kind> sdKinds(String value) throws MountedCameraCard.Unavailable {
        if(value==null)return Set.of(MediaTypes.Kind.JPEG,MediaTypes.Kind.RAW);
        Set<MediaTypes.Kind> kinds=MediaTypes.INSTANCE.kindsFromString(value);
        if(kinds.contains(MediaTypes.Kind.VIDEO))
            throw new MountedCameraCard.Unavailable("この取り込み方法では動画に対応していません。JPEGまたはRAWを選んでください。");
        return kinds;
    }

    static List<CameraDocumentSource.Entry> selectedPhotos(List<CameraDocumentSource.Entry> photos,
            Set<MediaTypes.Kind> kinds) {
        return photos.stream().filter(entry->kinds.contains(MediaTypes.INSTANCE.kindOf(entry.file.name)))
                .collect(java.util.stream.Collectors.toList());
    }

    static String noSdCandidates(CameraDocumentSource.Listing listing, int selectedCount) {
        if (selectedCount > 0) return null;
        if (!listing.photos.isEmpty())
            return "選んだ種類の写真がありません。JPEGまたはRAWの選択を確認してください。";
        return listing.hasVideo
                ? "このフォルダには動画があります。この方法で取り込めるのはJPEGとRAWです。"
                : "選んだフォルダに取り込める写真がありません。";
    }

    static void requireSdDestination(String requested, String current) throws MountedCameraCard.Unavailable {
        if (requested == null || !requested.equals(current))
            throw new MountedCameraCard.Unavailable("保存先が変わりました。取り込み元と保存先を確認してください。");
    }

    private void requirePhotoLibraryPermission() throws MountedCameraCard.Unavailable {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
            throw new MountedCameraCard.Unavailable("重複を確認するため写真全体へのアクセスを許可してください。");
    }

    private void requireSdSource(Uri tree, String source, String target, long generation) throws IOException {
        requireSdBinding(tree, source, target, generation);
        MountedCameraCard.requireGrant(this, tree);
    }

    private void requireSdBinding(Uri tree, String source, String target, long generation) throws IOException {
        MountedCameraCard.requireGeneration(generation);
        requireSdDestination(target, PhotoStorage.selected(this));
        if (!tree.toString().equals(getSharedPreferences("camera_import", 0).getString("sd_tree_uri", null)))
            throw new MountedCameraCard.Unavailable("取り込み元のフォルダが変わりました。DCIMフォルダを選び直してください。");
        if (!CameraDocumentSource.isImportTree(tree, true))
            throw new MountedCameraCard.Unavailable(MountedCameraCard.CHOOSE_SOURCE);
        if (MountedCameraCard.isSameMountedCard(this, tree, target))
            throw new MountedCameraCard.Unavailable("取り込み元と保存先が同じカードです。別のカードを選んでください。");
        if (source != null) MountedCameraCard.requireMountedSource(this, tree, source);
        else if (MountedCameraCard.isUnmountedRemovableTree(this, tree))
            throw new MountedCameraCard.Unavailable("登録したSDカードが接続されていません。取り込み元を確認してください。");
    }

    private BooleanSupplier sdStopped(Uri tree, String target, long generation, BooleanSupplier stopping) {
        var settings = getSharedPreferences("camera_import", 0);
        String requestedTree = tree.toString();
        return () -> stopping.getAsBoolean() || MountedCameraCard.detachGeneration() != generation
                || !target.equals(PhotoStorage.selected(this))
                || !requestedTree.equals(settings.getString("sd_tree_uri", null));
    }

    /** Poll grants during streaming, but never substitute a cached check at a commit boundary. */
    static final class SdGrantCheck {
        private static final long POLL_INTERVAL_MS = 100;
        @FunctionalInterface interface Reader { void require() throws MountedCameraCard.Unavailable; }

        private final Reader reader;
        private final java.util.function.LongSupplier clock;
        private long nextPoll;
        private MountedCameraCard.Unavailable lost;

        SdGrantCheck(Reader reader, java.util.function.LongSupplier clock) {
            this.reader = reader;
            this.clock = clock;
        }

        synchronized void requireFresh() throws MountedCameraCard.Unavailable {
            if (lost != null) throw lost;
            try {
                reader.require();
                nextPoll = clock.getAsLong() + POLL_INTERVAL_MS;
            } catch (MountedCameraCard.Unavailable unavailable) {
                lost = unavailable;
                throw unavailable;
            }
        }

        synchronized boolean stopped() {
            if (lost != null) return true;
            if (clock.getAsLong() < nextPoll) return false;
            try { requireFresh(); return false; }
            catch (MountedCameraCard.Unavailable unavailable) { return true; }
        }
    }

    @FunctionalInterface
    interface SdReceiptMatch { boolean matches(CameraDocumentSource.Entry entry) throws IOException; }

    static List<CameraDocumentSource.Entry> classifySd(List<CameraDocumentSource.Entry> selected,
            BooleanSupplier stopped, CameraImportMetrics metrics, SdReceiptMatch known,
            CameraPhotoPublisher.CommitCheck requireFreshSource, Runnable notifyProgress)
            throws IOException {
        long historyStart = SystemClock.elapsedRealtime();
        var candidates = new ArrayList<CameraDocumentSource.Entry>();
        try {
            ImportProgress.INSTANCE.photoHistory(0, selected.size(), 0);
            notifyProgress.run();
            for (CameraDocumentSource.Entry entry : selected) {
                CameraPhotoPublisher.check(stopped);
                try {
                    if (known.matches(entry)) metrics.known++;
                    else candidates.add(entry);
                } catch (CameraDocumentSource.OriginalAccessException failure) {
                    deferUnindexed(failure, requireFreshSource, metrics);
                }
                metrics.checked++;
                if (metrics.checked % 16 == 0 || metrics.checked == selected.size()) {
                    ImportProgress.INSTANCE.photoHistory(metrics.checked, selected.size(), metrics.known);
                    notifyProgress.run();
                }
            }
            CameraPhotoPublisher.check(stopped);
            metrics.candidates = candidates.size();
            return candidates;
        } finally {
            metrics.historyMs = SystemClock.elapsedRealtime() - historyStart;
        }
    }

    private static void deferUnindexed(CameraDocumentSource.OriginalAccessException failure,
            CameraPhotoPublisher.CommitCheck requireFreshSource, CameraImportMetrics metrics) throws IOException {
        // A failed cleanup may be attached to the source exception by the staged copy.
        if (failure.reason != CameraDocumentSource.OriginalAccessException.Reason.INDEX_PENDING
                || failure.getSuppressed().length != 0) throw failure;
        requireFreshSource.check();
        metrics.indexPending++;
    }

    @FunctionalInterface
    interface SdImport { CameraPhotoImporter.Result importEntry(CameraDocumentSource.Entry entry) throws IOException; }

    static void importSdCandidates(List<CameraDocumentSource.Entry> candidates, BooleanSupplier stopped,
            CameraImportMetrics metrics, SdImport importer, CameraPhotoPublisher.CommitCheck requireFreshSource,
            Runnable notifyProgress) throws IOException {
        metrics.content();
        notifyProgress.run();
        for (CameraDocumentSource.Entry entry : candidates) {
            CameraPhotoPublisher.check(stopped);
            try {
                CameraPhotoImporter.Result result = importer.importEntry(entry);
                if (result.imported) metrics.saved++;
                else if (result.contentChecked) metrics.matches++;
                metrics.processed++;
            } catch (CameraDocumentSource.OriginalAccessException failure) {
                deferUnindexed(failure, requireFreshSource, metrics);
            }
            metrics.content();
            ImportProgress.INSTANCE.update(metrics.processed, candidates.size());
            notifyProgress.run();
        }
    }

    private void sd(CameraPhotoImporter importer, Uri tree, boolean verify, String sourceVolume,
            Set<MediaTypes.Kind> kinds, String target, long generation) throws IOException {
        CameraDocumentSource.requireOriginalPermission(this);
        requireSdSource(tree, sourceVolume, target, generation);
        BooleanSupplier bindingStopped = sdStopped(tree, target, generation, cancelled);
        SdGrantCheck grant = new SdGrantCheck(() -> MountedCameraCard.requireGrant(this, tree), SystemClock::elapsedRealtime);
        BooleanSupplier stopped = () -> bindingStopped.getAsBoolean() || grant.stopped();
        CameraPhotoPublisher.CommitCheck requireFreshSource = () -> {
            CameraPhotoPublisher.check(bindingStopped);
            requireSdBinding(tree, sourceVolume, target, generation);
            grant.requireFresh();
            CameraDocumentSource.requireOriginalPermission(this);
        };
        CameraPhotoPublisher.CommitCheck requireDeferralAllowed = () -> {
            requireFreshSource.check();
            requirePhotoLibraryPermission();
        };
        long start = System.nanoTime();
        ImportProgress.INSTANCE.begin("SDカード");
        ImportProgress.INSTANCE.photoListing(0, -1);
        notifyProgress();
        try {
            long listingStart = SystemClock.elapsedRealtime();
            CameraDocumentSource.Listing listing = CameraDocumentSource.list(this, tree, stopped);
            requireFreshSource.check();
            CameraDocumentSource.requireOriginalPermission(this);
            runMetrics.listingMs = SystemClock.elapsedRealtime() - listingStart;
            List<CameraDocumentSource.Entry> selected = selectedPhotos(listing.photos, kinds);
            runMetrics.listed = listing.photos.size();
            String noCandidates = noSdCandidates(listing, selected.size());
            if (noCandidates != null) {
                CameraPhotoPublisher.check(stopped);
                runMetrics.candidates = 0;
                runMetrics.terminal("no_candidates");
                ImportProgress.INSTANCE.finish(noCandidates);
                return;
            }
            ImportProgress.INSTANCE.photoListing(selected.size(), selected.size());
            boolean stable = listing.stable && sourceVolume != null;
            var candidates = classifySd(selected, stopped, runMetrics, entry -> !verify && stable
                    && importer.knownSdRevision(entry.file, stopped, () -> {
                        requireFreshSource.check();
                        CameraDocumentSource.requireMappedOriginal(this, entry, sourceVolume, stopped);
                    }, () -> {
                        CameraDocumentSource.requireCurrentReceiptDocument(getContentResolver(), entry, sourceVolume, stopped);
                        requireFreshSource.check();
                    }), requireDeferralAllowed, this::notifyProgress);
            importSdCandidates(candidates, stopped, runMetrics, entry -> {
                boolean stableReceipt = stable && SdSavedPhotoRevision.eligibleSource(entry.file);
                CameraPhotoPublisher.CommitCheck check = () -> {
                    requireFreshSource.check();
                    if (sourceVolume != null)
                        CameraDocumentSource.requireMappedOriginal(this, entry, sourceVolume, stopped);
                };
                return retry(importer, entry.file, out -> {
                    requireFreshSource.check();
                    CameraDocumentSource.copy(this, entry, out, stopped, sourceVolume);
                    requireFreshSource.check();
                }, stableReceipt, verify, stopped, true, check, () -> {
                    if (stableReceipt)
                        CameraDocumentSource.requireCurrentReceiptDocument(getContentResolver(), entry, sourceVolume, stopped);
                    requireFreshSource.check();
                });
            }, requireDeferralAllowed, this::notifyProgress);
            CameraDocumentSource.requireOriginalPermission(this);
            // Every item has already passed its last source check. A later grant change
            // belongs to the next import, not the outcome of this completed batch.
            CameraPhotoPublisher.check(bindingStopped);
            requireSdBinding(tree, sourceVolume, target, generation);
            finishSd(selected.size(), start);
        } catch (InterruptedIOException interrupted) {
            if (!cancelled.getAsBoolean()) {
                requireSdBinding(tree, sourceVolume, target, generation);
                grant.requireFresh();
            }
            throw interrupted;
        }
    }
    private void notifyProgress(){getSystemService(NotificationManager.class).notify(781,notification(jp.nano.rawgallery.ImportProgressPresentation.current().accessibility()));}
    private void finishSd(int count, long start) {
        boolean pending = runMetrics.indexPending > 0;
        if (pending) {
            runMetrics.terminal("index_pending", SD_INDEX_RETRY);
        } else {
            runMetrics.terminal("complete", runMetrics.saved + "枚追加・"
                    + (runMetrics.known + runMetrics.matches) + "枚取り込み済み。カメラへカードを戻しましたか");
        }
        logResult(runMetrics.saved, count, runMetrics.sourceBytes, start);
        var notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(progressPendingIntent()).setAutoCancel(true);
        if (pending) {
            var presentation = jp.nano.rawgallery.ImportProgressPresentation.current();
            notification.setContentTitle(presentation.primary)
                    .setContentText(SD_INDEX_RETRY)
                    .setStyle(new Notification.BigTextStyle().bigText(presentation.secondary));
        } else {
            notification.setContentTitle("カメラへカードを戻しましたか").setContentText("SDカードの取り込みが完了しました");
        }
        getSystemService(NotificationManager.class).notify(782, notification.build());
    }

    private static void logResult(int added, int count, long bytes, long start) {
        double seconds = (System.nanoTime() - start) / 1e9;
        android.util.Log.i("CameraDifferential", String.format(Locale.ROOT,
                "files=%d added=%d readBytes=%d seconds=%.3f", count, added, bytes, seconds));
    }
    @Override public void onDestroy(){destroyed=true;synchronized(this){registeredQueue.clear();}CameraPairedSession session=pairedSession;if(session!=null)session.close();if(networkCallback!=null)getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);if(mediaReceiver!=null)unregisterReceiver(mediaReceiver);watchingSdMounts=false;worker.shutdownNow();super.onDestroy();}
}
