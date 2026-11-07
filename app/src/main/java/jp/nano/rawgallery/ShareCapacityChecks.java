package jp.nano.rawgallery;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.*;
import java.util.concurrent.*;

/** Local export-size checks belong to the visible selection; no cloud retrieval or UI-thread I/O. */
final class ShareCapacityChecks implements AutoCloseable {
  static final class Snapshot {
    final ShareCapacity.Result jpeg, original, available;
    Snapshot(ShareCapacity.Result jpeg, ShareCapacity.Result original) {
      this.jpeg=jpeg; this.original=original;
      available=ShareCapacity.available(jpeg,original);
    }
  }
  interface JpegSizer { long size(PhotoRepository.Photo photo) throws Exception; }
  private final JpegSizer sizer;
  private final Runnable changed;
  private final Handler main=new Handler(Looper.getMainLooper());
  private final ExecutorService worker=new DiagnosticExecutor("share.capacity",1);
  private final Map<String,Long> jpegSizes=new HashMap<>();
  private List<String> active=List.of();
  private Future<?> checking;
  private long generation;
  private boolean pending,closed;

  ShareCapacityChecks(Context context,PhotoRepository repository,Runnable changed) {
    this(changed,photo->SharePreparation.measureJpeg(context.getApplicationContext(),repository,photo));
  }
  ShareCapacityChecks(Runnable changed,JpegSizer sizer) { this.changed=changed;this.sizer=sizer; }
  private static List<String> identity(List<PhotoRepository.Photo> photos) {
    List<String> keys=new ArrayList<>();
    for(PhotoRepository.Photo photo:photos)keys.add(ShareCapacity.key(photo));
    return keys;
  }
  Snapshot request(List<PhotoRepository.Photo> photos) {
    List<String> keys=identity(photos);
    if(!active.equals(keys)) {
      active=List.copyOf(keys);long token=++generation;
      if(checking!=null)checking.cancel(true);
      pending=false;
      // A new selection is also an explicit retry of previous local measurement failures.
      for(String key:keys)if(jpegSizes.getOrDefault(key,0L)<0)jpegSizes.remove(key);
      List<PhotoRepository.Photo> missing=new ArrayList<>();
      if(ShareCapacity.assess(photos,false,jpegSizes,false).state!=ShareCapacity.State.OVER)
        for(PhotoRepository.Photo photo:photos)
          if(!photo.isJpeg() && !"archive".equals(photo.uri.getScheme())
              && !jpegSizes.containsKey(ShareCapacity.key(photo))) missing.add(photo);
      if(!missing.isEmpty() && !closed) {
        pending=true;
        checking=worker.submit(()->{
          Map<String,Long> measured=new HashMap<>();
          for(PhotoRepository.Photo photo:missing) {
            if(Thread.currentThread().isInterrupted())return;
            long size;
            try { size=sizer.size(photo); }
            catch(Exception error) { if(Thread.currentThread().isInterrupted())return;size=-1; }
            measured.put(ShareCapacity.key(photo),size);
          }
          main.post(()->{
            if(closed||generation!=token)return;
            jpegSizes.putAll(measured);pending=false;changed.run();
          });
        });
      }
    }
    return snapshot(photos);
  }
  Snapshot snapshot(List<PhotoRepository.Photo> photos) {
    boolean currentPending=pending&&active.equals(identity(photos));
    return new Snapshot(ShareCapacity.assess(photos,false,jpegSizes,currentPending),
        ShareCapacity.assess(photos,true,jpegSizes,false));
  }
  void observed(Map<String,Long> lengths) { jpegSizes.putAll(lengths); }
  void clearSelection() {
    generation++;active=List.of();pending=false;
    if(checking!=null)checking.cancel(true);
  }
  public void close() { closed=true;clearSelection();worker.shutdownNow(); }
}
