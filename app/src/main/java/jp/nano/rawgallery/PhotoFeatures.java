package jp.nano.rawgallery;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder;
import com.google.mediapipe.tasks.vision.core.RunningMode;

/** Rebuildable features; only local content:// images, no archive/network fallback. */
final class PhotoFeatures implements AutoCloseable {
  static final String PIPELINE = "mobilenet-v3-small-1-bbbb4c51-preview-v2-oriented-mp0.10.35-int8-v1";
  private final Cache cache;
  private final Context context;
  private final PhotoRepository repository;
  private ImageEmbedder embedder;

  PhotoFeatures(Context context) { this(context, "photo-features.db"); }
  PhotoFeatures(Context context, String database) {
    this.context = context.getApplicationContext();
    cache = new Cache(context, database);
    repository = new PhotoRepository(context);
  }

  static final class Cache extends SQLiteOpenHelper {
    Cache(Context context, String name) { super(context, name, null, 1); }
    public void onCreate(SQLiteDatabase db) {
      db.execSQL("CREATE TABLE features (source TEXT NOT NULL, pipeline TEXT NOT NULL, vector BLOB NOT NULL, PRIMARY KEY(source,pipeline))");
    }
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int next) {
      throw new IllegalStateException("Unsupported feature cache version");
    }
  }

  RelatedPhotos.Vector cached(PhotoRepository.Photo photo) {
    try (Cursor c = cache.getReadableDatabase().rawQuery(
        "SELECT vector FROM features WHERE source=? AND pipeline=?",
        new String[] {TagStore.source(photo), PIPELINE})) {
      if (!c.moveToFirst()) return null;
      byte[] bytes = c.getBlob(0);
      if (bytes.length != 1024) return null;
      try { return new RelatedPhotos.Vector(bytes); }
      catch (IllegalArgumentException invalid) { return null; }
    }
  }

  /** Reuses completed features across albums without reading images or starting inference. */
  java.util.Map<String,RelatedPhotos.Vector> allCached() {
    java.util.Map<String,RelatedPhotos.Vector> result=new java.util.HashMap<>();
    try(Cursor c=cache.getReadableDatabase().rawQuery("SELECT source,vector FROM features WHERE pipeline=?",new String[]{PIPELINE})) {
      while(c.moveToNext()) {
        byte[] bytes=c.getBlob(1);if(bytes.length!=1024)continue;
        try{result.put(c.getString(0),new RelatedPhotos.Vector(bytes));}
        catch(IllegalArgumentException invalid){ /* Regenerable invalid features are not judgments. */ }
      }
    }
    return result;
  }

  RelatedPhotos.Vector obtain(PhotoRepository.Photo photo) throws Exception {
    if (!"content".equals(photo.uri.getScheme())) throw new IllegalArgumentException("Local photos only");
    RelatedPhotos.Vector saved = cached(photo);
    if (saved != null) return saved;
    Bitmap bitmap = PhotoRepository.bitmap(repository.preview(photo), 512);
    try {
      RelatedPhotos.Vector vector = embed(bitmap);
      ContentValues values = new ContentValues();
      values.put("source", TagStore.source(photo)); values.put("pipeline", PIPELINE);
      values.put("vector", vector.values);
      if (cache.getWritableDatabase().insertWithOnConflict("features", null, values,
          SQLiteDatabase.CONFLICT_REPLACE) < 0) throw new IllegalStateException("Feature cache write failed");
      return vector;
    } finally { bitmap.recycle(); }
  }

  RelatedPhotos.Vector embed(Bitmap bitmap) {
    if (bitmap == null || bitmap.isRecycled()) throw new IllegalArgumentException("Invalid bitmap");
    if (embedder == null) {
      embedder = ImageEmbedder.createFromOptions(context, ImageEmbedder.ImageEmbedderOptions.builder()
          .setBaseOptions(BaseOptions.builder().setModelAssetPath("mobilenet_v3_small.tflite").build())
          .setRunningMode(RunningMode.IMAGE).setL2Normalize(true).build());
    }
    // MPImage.close recycles its bitmap. Keep ownership of the caller's bitmap separate.
    Bitmap owned = bitmap.copy(Bitmap.Config.ARGB_8888, false);
    if (owned == null) throw new IllegalStateException("Cannot copy embedding input");
    MPImage image = new BitmapImageBuilder(owned).build();
    try {
      RelatedPhotos.Vector vector = RelatedPhotos.Vector.from(
          embedder.embed(image).embeddingResult().embeddings().get(0).floatEmbedding());
      if (vector.values.length != 1024) throw new IllegalStateException("Unexpected embedding dimensions");
      return vector;
    } finally { image.close(); }
  }

  public void close() { if (embedder != null) embedder.close(); cache.close(); }
}
