package jp.nano.rawgallery;

import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.system.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

public final class PhotoRepository {
  public static final class Photo {
    public final Uri uri;
    public final String name, folder;
    public final long size, modified, taken, added, created;

    public Photo(Uri u, String n, String f, long s, long m, long t) {
      this(u, n, f, s, m, t, 0, 0);
    }

    public Photo(Uri u, String n, String f, long s, long m, long t, long a, long birth) {
      uri = u;
      name = n;
      folder = f;
      size = s;
      modified = m;
      taken = t;
      added = a;
      created = birth;
    }

    public boolean isJpeg() {
      return name.toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg)$");
    }

    public String cameraGroup() {
      String f = folder.toLowerCase(Locale.ROOT);
      if (f.contains("gr world")) return "GR";
      if (f.contains("om-1")) return "OM-1";
      if (f.contains("e-p7")) return "E-P7";
      if (f.contains("camera/raw")) return "Leitz";
      return "その他";
    }
  }

  public static final class Preview {
    public final File file;
    public final String model;
    public final int width, height, orientation;

    Preview(File f, String m, int w, int h, int o) {
      file = f;
      model = m;
      width = w;
      height = h;
      orientation = o;
    }
  }

  public static final class DescriptorSource implements RawPreview.Source, AutoCloseable {
    private final ParcelFileDescriptor descriptor;
    private final long size;

    DescriptorSource(ContentResolver resolver, Uri uri, long expected) throws IOException {
      descriptor = resolver.openFileDescriptor(uri, "r");
      if (descriptor == null) throw new IOException("写真を開けません");
      long length = descriptor.getStatSize();
      size = length >= 0 ? length : expected;
    }

    public long length() {
      return size;
    }

    public void read(long offset, byte[] dst) throws IOException {
      try {
        int done = 0;
        while (done < dst.length) {
          int n =
              Os.pread(descriptor.getFileDescriptor(), dst, done, dst.length - done, offset + done);
          if (n <= 0) throw new EOFException();
          done += n;
        }
      } catch (ErrnoException e) {
        throw new IOException("この保存先はランダム読み取りに対応していません", e);
      }
    }

    public void close() throws IOException {
      descriptor.close();
    }
  }

  private final Context context;
  private final File previews;
  private final File prepared;
  private static final Object PREVIEW_LOCK = new Object();
  private static final Object BACKGROUND_LOCK = new Object();
  private static final Object PUBLISH_LOCK = new Object();
  private final Object processingLock;
  private final File legacyPreviews;

  public PhotoRepository(Context c) { this(c, false); }

  PhotoRepository(Context c, boolean background) {
    processingLock = background ? BACKGROUND_LOCK : PREVIEW_LOCK;
    context = c.getApplicationContext();
    DiagnosticLog.init(context);
    legacyPreviews = new File(context.getCacheDir(), "previews");
    previews = background ? new File(context.getCacheDir(), "preview-preparation-source") : legacyPreviews;
    previews.mkdirs();
    prepared = new File(context.getFilesDir(), "prepared-previews");
    prepared.mkdirs();
  }

  public List<Photo> scan(String filter) throws IOException {
    LinkedHashMap<String, Photo> result = new LinkedHashMap<>();
    String[] cols = {
      MediaStore.Files.FileColumns._ID,
      MediaStore.Files.FileColumns.DISPLAY_NAME,
      MediaStore.Files.FileColumns.RELATIVE_PATH,
      MediaStore.Files.FileColumns.SIZE,
      MediaStore.Files.FileColumns.DATE_MODIFIED,
      MediaStore.Images.ImageColumns.DATE_TAKEN,
      MediaStore.MediaColumns.DATE_ADDED
    };
    Uri root = MediaStore.Files.getContentUri("external");
    String[] patterns = RawFormats.patterns();
    String condition = "(" + String.join(" OR ",
        Collections.nCopies(patterns.length, MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ?")) + ")";
    try (Cursor c =
        context
            .getContentResolver()
            .query(
                root,
                cols,
                condition,
                patterns,
                MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC")) {
      if (c != null)
        while (c.moveToNext()) {
          String folder = c.getString(2), name = c.getString(1);
          if (folder == null || name == null) continue;
          Photo p =
              new Photo(
                  ContentUris.withAppendedId(root, c.getLong(0)),
                  name,
                  folder,
                  c.getLong(3),
                  c.getLong(4),
                  c.getLong(5),
                  SyncPolicy.secondsToMillis(c.getLong(6)),
                  0);
          result.put(folder + name, p);
        }
    } catch (SecurityException e) {
      /* Explicit document grants still work without media access. */
    }
    for (UriPermission grant : context.getContentResolver().getPersistedUriPermissions())
      if (grant.isReadPermission() && DocumentsContract.isTreeUri(grant.getUri())) {
        try {
          scanTree(grant.getUri(), DocumentsContract.getTreeDocumentId(grant.getUri()), result, 0);
        } catch (SecurityException e) {
          throw new IOException("フォルダの読み取り許可が失効しています", e);
        }
      }
    try (ArchiveStore store = new ArchiveStore(context)) {
      for (JSONObject record : store.all())
        if (record.has("sha")) {
          String path = record.getString("folder") + record.getString("name");
          if (!result.containsKey(path)) result.put(path, store.cloudPhoto(record));
        }
    } catch (Exception e) {
      throw new IOException("クラウド原本の記録を読めません", e);
    }
    List<Photo> photos = new ArrayList<>();
    for (Photo p : result.values())
      if (filter.equals("すべて") || p.cameraGroup().equals(filter)) photos.add(p);
    photos.sort(
        Comparator.comparingLong((Photo p) -> p.taken > 0 ? p.taken : p.modified * 1000)
            .reversed()
            .thenComparing(p -> p.name));
    return photos;
  }

  private void scanTree(Uri tree, String id, Map<String, Photo> result, int depth)
      throws IOException {
    if (depth > 16) throw new IOException("フォルダの階層が深すぎます");
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
    String[] cols = {
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
      DocumentsContract.Document.COLUMN_SIZE,
      DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };
    try (Cursor c = context.getContentResolver().query(children, cols, null, null, null)) {
      if (c == null) return;
      while (c.moveToNext()) {
        String child = c.getString(0), name = c.getString(1), mime = c.getString(2);
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
          scanTree(tree, child, result, depth + 1);
          continue;
        }
        if (!RawFormats.accepts(name))
          continue;
        String relative = child.startsWith("primary:") ? child.substring(8) : child;
        String folder = relative.substring(0, Math.max(0, relative.lastIndexOf('/') + 1));
        Photo previous = result.get(folder + name);
        Photo p =
            new Photo(
                DocumentsContract.buildDocumentUriUsingTree(tree, child),
                name,
                folder,
                c.getLong(3),
                c.getLong(4) / 1000,
                previous != null ? previous.taken : 0);
        if (previous == null || !android.os.Environment.isExternalStorageManager())
          result.put(folder + name, p);
      }
    }
  }

  /** Only a real birth-time attribute is accepted; ctime/mtime are not creation dates. */
  static long creationTime(Context c, Photo p) {
    try (Cursor cursor =
        c.getContentResolver()
            .query(p.uri, new String[] {MediaStore.MediaColumns.DATA}, null, null, null)) {
      if (cursor == null || !cursor.moveToFirst() || cursor.isNull(0)) return 0;
      Object value =
          java.nio.file.Files.getAttribute(
              java.nio.file.Paths.get(cursor.getString(0)), "unix:birthtime");
      return value instanceof java.nio.file.attribute.FileTime
          ? Math.max(0, ((java.nio.file.attribute.FileTime) value).toMillis())
          : 0;
    } catch (Exception unsupportedOrUnavailable) {
      return 0;
    }
  }

  public static String digest(String text) throws IOException {
    try {
      byte[] b = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      char[] hex = "0123456789abcdef".toCharArray();
      char[] result = new char[b.length * 2];
      for (int i = 0; i < b.length; i++) {
        result[i * 2] = hex[(b[i] & 255) >>> 4];
        result[i * 2 + 1] = hex[b[i] & 15];
      }
      return new String(result);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  static String previewKey(Photo p) throws IOException {
    return digest("raw-preview-v2:" + p.uri + ":" + p.size + ":" + p.modified);
  }

  boolean prepared(Photo p) throws IOException {
    String key = previewKey(p);
    return new File(prepared, key + ".webp").isFile()
        && new File(prepared, key + ".webp.json").isFile();
  }

  boolean prepare(Photo p, java.util.function.BooleanSupplier cancelled) throws IOException {
    synchronized (processingLock) {
      if (cancelled.getAsBoolean()) return false;
      DiagnosticLog.event("preparation.item_start", 0, "photo", DiagnosticLog.photo(p));
      try { previewLocked(p, cancelled); }
      catch (java.io.InterruptedIOException e) {
        DiagnosticLog.event("preparation.item_cancel", 0, "photo", DiagnosticLog.photo(p));
        return false;
      }
      DiagnosticLog.event("preparation.item_end", 0, "photo", DiagnosticLog.photo(p));
      return true;
    }
  }

  // Run only off the UI thread. Remove derived images whose indexed source/version is gone.
  void prunePrepared(java.util.Set<String> keep, java.util.function.BooleanSupplier cancelled) {
    File[] files = prepared.listFiles();
    if (files == null) return;
    for (File file : files) {
      if (cancelled.getAsBoolean()) return;
      String name = file.getName();
      if (!name.endsWith(".webp") && !name.endsWith(".webp.json")) continue;
      String key = name.substring(0, name.indexOf(".webp"));
      if (!keep.contains(key)) synchronized (PUBLISH_LOCK) {
        if (cancelled.getAsBoolean()) return;
        file.delete();
      }
    }
  }

  Bitmap thumbnail(Photo p, int edge, java.util.function.BooleanSupplier cancelled) throws IOException {
    checkPreparation(cancelled);
    if ("media".equals(p.uri.getAuthority())) {
      try (DiagnosticLog.Scope ignored = DiagnosticLog.span("thumbnail.provider")) {
        Bitmap bitmap = context.getContentResolver().loadThumbnail(p.uri, new android.util.Size(edge, edge), null);
        if (bitmap == null) throw new IOException("No provider thumbnail");
        if (cancelled.getAsBoolean()) { bitmap.recycle(); checkPreparation(cancelled); }
        return bitmap;
      } catch (IOException | RuntimeException e) { checkPreparation(cancelled); }
    }
    return bitmap(preview(p, cancelled), edge);
  }

  public Preview preview(Photo p) throws IOException { return preview(p, () -> false); }

  Preview preview(Photo p, java.util.function.BooleanSupplier cancelled) throws IOException {
    try (DiagnosticLog.Scope span = DiagnosticLog.span("repository.preview")) {
      DiagnosticLog.event("repository.lock_wait", span.id);
      synchronized (processingLock) {
        DiagnosticLog.event("repository.lock_acquired", span.id);
        try { return previewLocked(p, cancelled); }
        catch (IOException | RuntimeException e) {
          DiagnosticLog.event("repository.fail", span.id, "error", e.getClass().getSimpleName());
          throw e;
        }
      }
    }
  }

  private Preview previewLocked(Photo p) throws IOException {
    return previewLocked(p, () -> false);
  }

  private static void checkPreparation(java.util.function.BooleanSupplier cancelled) throws IOException {
    if (cancelled.getAsBoolean()) throw new java.io.InterruptedIOException("Preview preparation paused");
  }

  private Preview previewLocked(Photo p, java.util.function.BooleanSupplier cancelled) throws IOException {
    if ("archive".equals(p.uri.getScheme())) {
      try (ArchiveStore store = new ArchiveStore(context)) {
        return store.preview(p.uri.getHost());
      } catch (Exception e) {
        throw new IOException("保存済みプレビューを読めません", e);
      }
    }
    String key = digest("raw-preview-v2:" + p.uri + ":" + p.size + ":" + p.modified);
    File webp = new File(prepared, key + ".webp"), meta = new File(prepared, key + ".webp.json");
    // Only metadata/file publication is shared. No parsing, decoding or compression under this lock.
    synchronized (PUBLISH_LOCK) {
      checkPreparation(cancelled);
      if (!webp.isFile() || !meta.isFile()) {
        File old = new File(legacyPreviews, key + ".webp"), oldMeta = new File(legacyPreviews, key + ".webp.json");
        if (old.isFile() && oldMeta.isFile()) {
          java.nio.file.Files.move(old.toPath(), webp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
          java.nio.file.Files.move(oldMeta.toPath(), meta.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
      }
      DiagnosticLog.event("cache.compact", DiagnosticLog.parent(), "hit", webp.isFile() && meta.isFile());
      if (webp.isFile() && meta.isFile()) {
        try {
          JSONObject j = new JSONObject(readText(meta));
          return new Preview(webp, j.getString("model"), j.getInt("width"), j.getInt("height"), 1);
        } catch (Exception ignored) {
          webp.delete(); meta.delete();
        }
      }
    }
    checkPreparation(cancelled);
    Preview source = sourcePreview(p);
    File staged = null, stagedMeta = null;
    try {
      checkPreparation(cancelled);
      staged = File.createTempFile("compact-", ".part", previews);
      Preview compact;
      try (DiagnosticLog.Scope ignored = DiagnosticLog.span("preview.compact")) {
        compact = CompactPreview.save(source, staged);
      }
      checkPreparation(cancelled);
      stagedMeta = File.createTempFile("compact-meta-", ".part", previews);
      try (FileOutputStream out = new FileOutputStream(stagedMeta)) {
        JSONObject j = new JSONObject().put("model", compact.model)
            .put("width", compact.width).put("height", compact.height);
        out.write(j.toString().getBytes(StandardCharsets.UTF_8));
      } catch (JSONException e) { throw new IOException(e); }
      synchronized (PUBLISH_LOCK) {
        checkPreparation(cancelled);
        java.nio.file.Files.move(staged.toPath(), webp.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        java.nio.file.Files.move(stagedMeta.toPath(), meta.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      }
      return new Preview(webp, compact.model, compact.width, compact.height, 1);
    } finally {
      if (staged != null) staged.delete();
      if (stagedMeta != null) stagedMeta.delete();
      // Background and foreground use separate source files, including for the same photo.
      if (source.file.getParentFile().equals(previews)) {
        source.file.delete();
        new File(previews, key + ".json").delete();
      }
    }
  }

  /** Called with a materialized original, never with the small archive proxy. */
  public Preview fullPreview(Photo p) throws IOException {
    if (!"file".equals(p.uri.getScheme())) throw new IOException("原本の取得が必要です");
    if (!p.isJpeg()) return sourcePreview(p);
    File f = new File(p.uri.getPath());
    ExifInterface exif = new ExifInterface(f);
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(f.getPath(), bounds);
    if (bounds.outWidth <= 0
        || bounds.outHeight <= 0
        || (long) bounds.outWidth * bounds.outHeight > 120_000_000L)
      throw new IOException("原本の画素数を読み取れません");
    String model = exif.getAttribute(ExifInterface.TAG_MODEL);
    return new Preview(
        f,
        model == null ? "JPEG" : model,
        bounds.outWidth,
        bounds.outHeight,
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1));
  }

  public Preview sourcePreview(Photo p) throws IOException {
    try (DiagnosticLog.Scope span = DiagnosticLog.span("repository.sourcePreview")) {
      DiagnosticLog.event("repository.lock_wait", span.id);
      synchronized (processingLock) {
        DiagnosticLog.event("repository.lock_acquired", span.id);
        try { return sourcePreviewLocked(p); }
        catch (IOException | RuntimeException e) {
          DiagnosticLog.event("repository.fail", span.id, "error", e.getClass().getSimpleName());
          throw e;
        }
      }
    }
  }

  private Preview sourcePreviewLocked(Photo p) throws IOException {
    if ("archive".equals(p.uri.getScheme())) {
      try (ArchiveStore store = new ArchiveStore(context)) {
        return store.preview(p.uri.getHost());
      } catch (Exception e) {
        throw new IOException("保存済みプレビューを読めません", e);
      }
    }
    String key = digest("raw-preview-v2:" + p.uri + ":" + p.size + ":" + p.modified);
    File jpg = new File(previews, key + ".jpg"), meta = new File(previews, key + ".json");
    DiagnosticLog.event("cache.source", DiagnosticLog.parent(), "hit", jpg.isFile() && meta.isFile());
    if (jpg.isFile() && meta.isFile()) {
      try {
        JSONObject j = new JSONObject(readText(meta));
        jpg.setLastModified(System.currentTimeMillis());
        return new Preview(
            jpg,
            j.getString("model"),
            j.getInt("width"),
            j.getInt("height"),
            j.getInt("orientation"));
      } catch (Exception ignored) {
        jpg.delete();
        meta.delete();
      }
    }
    if (p.isJpeg()) {
      try (DiagnosticLog.Scope ignored = DiagnosticLog.span("jpeg.source")) { return jpegPreview(p, jpg, meta); }
    }
    try (DescriptorSource source =
        new DescriptorSource(context.getContentResolver(), p.uri, p.size)) {
      RawPreview.Result r;
      try (DiagnosticLog.Scope ignored = DiagnosticLog.span("raw.inspect")) { r = RawPreview.inspect(source); }
      File partial = new File(previews, key + ".part");
      try (FileOutputStream f = new FileOutputStream(partial)) {
        try (DiagnosticLog.Scope ignored = DiagnosticLog.span("raw.extract")) { f.write(r.jpeg(source)); }
      }
      if (!partial.renameTo(jpg)) throw new IOException("プレビューを保存できません");
      try {
        JSONObject j = new JSONObject();
        j.put("model", r.model);
        j.put("width", r.width);
        j.put("height", r.height);
        j.put("orientation", r.orientation);
        try (FileOutputStream f = new FileOutputStream(meta)) {
          f.write(j.toString().getBytes(StandardCharsets.UTF_8));
        }
      } catch (JSONException e) {
        throw new IOException(e);
      }
      trim(jpg);
      return new Preview(jpg, r.model, r.width, r.height, r.orientation);
    }
  }

  /** Fail closed if Android cannot provide the unredacted original bytes. */
  static InputStream original(Context c, Photo p) throws IOException {
    Uri uri = "media".equals(p.uri.getAuthority()) ? MediaStore.setRequireOriginal(p.uri) : p.uri;
    InputStream in = c.getContentResolver().openInputStream(uri);
    if (in == null) throw new IOException("原本を開けません");
    return in;
  }

  private Preview jpegPreview(Photo p, File jpg, File meta) throws IOException {
    String model;
    int orientation;
    try (InputStream in = context.getContentResolver().openInputStream(p.uri)) {
      if (in == null) throw new IOException("JPEGを開けません");
      ExifInterface exif = new ExifInterface(in);
      model = exif.getAttribute(ExifInterface.TAG_MODEL);
      if (model == null) model = "JPEG";
      orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
      if (orientation < 1 || orientation > 8) orientation = 1;
    }
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    try (InputStream in = context.getContentResolver().openInputStream(p.uri)) {
      BitmapFactory.decodeStream(in, null, options);
    }
    if (options.outWidth <= 0
        || options.outHeight <= 0
        || (long) options.outWidth * options.outHeight > 120_000_000L)
      throw new IOException("JPEGの画素数を読み取れません");
    options.inJustDecodeBounds = false;
    options.inSampleSize = 1;
    while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 2560)
      options.inSampleSize *= 2;
    Bitmap bitmap;
    try (InputStream in = context.getContentResolver().openInputStream(p.uri)) {
      bitmap = BitmapFactory.decodeStream(in, null, options);
    }
    if (bitmap == null) throw new IOException("JPEGを読み取れません");
    File part = new File(jpg.getPath() + ".part");
    int width = bitmap.getWidth(), height = bitmap.getHeight();
    try {
      try (FileOutputStream out = new FileOutputStream(part)) {
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out))
          throw new IOException("プレビューを作れません");
      }
      if (!part.renameTo(jpg)) throw new IOException("プレビューを保存できません");
      JSONObject j =
          new JSONObject()
              .put("model", model)
              .put("width", width)
              .put("height", height)
              .put("orientation", orientation);
      try (FileOutputStream out = new FileOutputStream(meta)) {
        out.write(j.toString().getBytes(StandardCharsets.UTF_8));
      }
    } catch (JSONException e) {
      throw new IOException(e);
    } finally {
      bitmap.recycle();
      part.delete();
    }
    trim(jpg);
    return new Preview(jpg, model, width, height, orientation);
  }

  private static String readText(File f) throws IOException {
    try (FileInputStream in = new FileInputStream(f)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] b = new byte[4096];
      int n;
      while ((n = in.read(b)) != -1) {
        if (out.size() + n > 8192) throw new IOException("不正なキャッシュ");
        out.write(b, 0, n);
      }
      return out.toString("UTF-8");
    }
  }

  private void trim(File keep) {
    try (DiagnosticLog.Scope ignored = DiagnosticLog.span("cache.trim")) { trimFiles(keep); }
  }

  private void trimFiles(File keep) {
    File[] files =
        previews.listFiles((dir, name) -> name.endsWith(".jpg"));
    if (files == null) return;
    long total = 0;
    for (File f : files) total += f.length();
    if (total <= 256L * 1024 * 1024) return;
    Arrays.sort(files, Comparator.comparingLong(File::lastModified));
    for (File f : files)
      if (total > 256L * 1024 * 1024 && !f.equals(keep)) {
        long n = f.length();
        if (f.delete()) {
          total -= n;
          new File(
                  previews,
                  f.getName().endsWith(".webp")
                      ? f.getName() + ".json"
                      : f.getName().replace(".jpg", ".json"))
              .delete();
        }
      }
  }

  /** Applies the RAW orientation once; BitmapFactory does not apply JPEG EXIF orientation. */
  public static Bitmap bitmap(Preview p, int maxEdge) throws IOException {
    try (DiagnosticLog.Scope ignored = DiagnosticLog.span("bitmap.decode")) {
      return bitmapDecoded(p, maxEdge);
    }
  }

  private static Bitmap bitmapDecoded(Preview p, int maxEdge) throws IOException {
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inSampleSize = 1;
    while (Math.max(p.width, p.height) / o.inSampleSize > maxEdge * 2) o.inSampleSize *= 2;
    Bitmap b = BitmapFactory.decodeFile(p.file.getPath(), o);
    if (b == null) throw new IOException("JPEGを表示できません");
    Matrix m = new Matrix();
    switch (p.orientation) {
      case 2:
        m.setScale(-1, 1);
        break;
      case 3:
        m.setRotate(180);
        break;
      case 4:
        m.setScale(1, -1);
        break;
      case 5:
        m.setRotate(90);
        m.postScale(-1, 1);
        break;
      case 6:
        m.setRotate(90);
        break;
      case 7:
        m.setRotate(-90);
        m.postScale(-1, 1);
        break;
      case 8:
        m.setRotate(-90);
        break;
      default:
        return b;
    }
    Bitmap rotated = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    if (rotated != b) b.recycle();
    return rotated;
  }
}
