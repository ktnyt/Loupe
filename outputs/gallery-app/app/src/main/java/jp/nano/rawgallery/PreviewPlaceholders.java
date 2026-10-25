package jp.nano.rawgallery;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.graphics.Bitmap;
import android.os.*;
import android.util.LruCache;
import com.madebyevan.thumbhash.ThumbHash;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.*;

/** Persistent tiny placeholders. Never opens an original to satisfy a UI request. */
final class PreviewPlaceholders extends SQLiteOpenHelper {
  private static PreviewPlaceholders instance;
  private final ExecutorService reads = new DiagnosticExecutor("Placeholder.read", 1);
  private final ExecutorService writes = new DiagnosticExecutor("Placeholder.write", 1);
  private final Handler main = new Handler(Looper.getMainLooper());
  private final LruCache<String, Bitmap> decoded = new LruCache<>(256);
  private final Set<String> pending = ConcurrentHashMap.newKeySet();
  static synchronized PreviewPlaceholders get(Context c) {
    if (instance == null) instance = new PreviewPlaceholders(c.getApplicationContext());
    return instance;
  }
  private PreviewPlaceholders(Context c) { super(c, "preview-placeholders.db", null, 1); setWriteAheadLoggingEnabled(true); }
  public void onCreate(SQLiteDatabase db) { db.execSQL("CREATE TABLE hashes (photo TEXT PRIMARY KEY, hash BLOB NOT NULL)"); }
  public void onUpgrade(SQLiteDatabase db, int old, int next) { throw new IllegalStateException(); }
  static String key(PhotoRepository.Photo p) { return p.uri + ":" + p.size + ":" + p.modified; }
  boolean has(PhotoRepository.Photo p) {
    try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM hashes WHERE photo=?", new String[]{key(p)})) { return c.moveToFirst(); }
  }
  void load(PhotoRepository.Photo p, BooleanSupplier current, Consumer<Bitmap> result) {
    reads.execute(() -> {
      if (!current.getAsBoolean()) return;
      try {
        Bitmap bitmap = decoded.get(key(p));
        if (bitmap == null) {
          try (Cursor c = getReadableDatabase().rawQuery("SELECT hash FROM hashes WHERE photo=?", new String[]{key(p)})) {
            if (!c.moveToFirst()) return;
            ThumbHash.Image image = ThumbHash.thumbHashToRGBA(c.getBlob(0));
            int[] pixels = new int[image.width * image.height];
            for (int i=0,j=0;i<pixels.length;i++,j+=4)
              pixels[i]=((image.rgba[j+3]&255)<<24)|((image.rgba[j]&255)<<16)|((image.rgba[j+1]&255)<<8)|(image.rgba[j+2]&255);
            bitmap=Bitmap.createBitmap(pixels,image.width,image.height,Bitmap.Config.ARGB_8888);
            decoded.put(key(p),bitmap);
          }
        }
        Bitmap ready=bitmap;
        main.post(() -> { if (current.getAsBoolean()) result.accept(ready); });
      } catch (RuntimeException ignored) { /* A malformed placeholder must never prevent the actual image. */ }
    });
  }
  void remember(PhotoRepository.Photo p, Bitmap bitmap) {
    String key=key(p);
    if (!pending.add(key)) return;
    writes.execute(() -> {
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
      try { if (!has(p)) store(p,bitmap); }
      catch (RuntimeException ignored) {}
      finally { pending.remove(key); }
    });
  }
  void store(PhotoRepository.Photo p, Bitmap bitmap) {
    float scale=Math.min(1f,64f/Math.max(bitmap.getWidth(),bitmap.getHeight()));
    int w=Math.max(1,Math.round(bitmap.getWidth()*scale)),h=Math.max(1,Math.round(bitmap.getHeight()*scale));
    Bitmap small=Bitmap.createScaledBitmap(bitmap,w,h,true);
    try {
      int[] pixels=new int[w*h];small.getPixels(pixels,0,w,0,0,w,h);
      byte[] rgba=new byte[w*h*4];
      for(int i=0,j=0;i<pixels.length;i++,j+=4){int v=pixels[i];rgba[j]=(byte)(v>>16);rgba[j+1]=(byte)(v>>8);rgba[j+2]=(byte)v;rgba[j+3]=(byte)(v>>>24);}
      ContentValues values=new ContentValues();values.put("photo",key(p));values.put("hash",ThumbHash.rgbaToThumbHash(w,h,rgba));
      getWritableDatabase().insertWithOnConflict("hashes",null,values,SQLiteDatabase.CONFLICT_REPLACE);
    } finally { if(small!=bitmap)small.recycle(); }
  }
}
