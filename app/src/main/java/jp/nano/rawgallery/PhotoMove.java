package jp.nano.rawgallery;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import jp.nano.rawgallery.camera.CameraImportHistoryPlan;
import jp.nano.rawgallery.camera.CameraImportLedger;
import jp.nano.rawgallery.camera.HistoryDb;
import static android.provider.MediaStore.MediaColumns.*;

/** Copies owned camera imports between volumes. The verified replacement always precedes deletion. */
final class PhotoMove implements AutoCloseable {
  static final class Failure extends IOException {
    Failure(String message) { super(message); }
  }

  enum Boundary { ALLOCATED, INSERTED, COPIED, VERIFIED, PUBLISHED, HISTORY_UPDATED, REMAPPED, DELETED }
  interface Faults { void after(Boundary boundary); }
  interface Progress { void update(PhotoMoveStore.Batch batch); }
  record Plan(String volume, List<PhotoMoveStore.Item> items, int skipped) {}

  private record Media(Uri uri, String name, String path, String mime, String volume,
      String owner, long bytes, long modified, long added, long taken, long generationAdded,
      long generationModified, boolean pending, boolean trashed) {
    String version() {
      return name + "\n" + path + "\n" + mime + "\n" + bytes + "\n"
          + modified + "\n" + added + "\n" + taken + "\n" + generationAdded + "\n" + generationModified;
    }

    PhotoRepository.Photo photo() {
      Uri root = MediaStore.Files.getContentUri(PhotoStorage.PRIMARY.equals(volume) ? "external" : volume);
      return new PhotoRepository.Photo(ContentUris.withAppendedId(root, ContentUris.parseId(uri)), name,
          path, bytes, modified, taken, SyncPolicy.secondsToMillis(added), 0);
    }
  }

  private static final Uri ALL = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
  private static final String[] COLUMNS = {_ID, DISPLAY_NAME, RELATIVE_PATH, MIME_TYPE, VOLUME_NAME,
      OWNER_PACKAGE_NAME, SIZE, DATE_MODIFIED, DATE_ADDED, DATE_TAKEN, GENERATION_ADDED,
      GENERATION_MODIFIED, IS_PENDING, IS_TRASHED};
  private final Context context;
  private final ContentResolver resolver;
  private final CameraImportLedger ledger;
  private final HistoryDb history;
  private final PhotoMoveStore store;
  private final Faults faults;

  PhotoMove(Context context) {
    this(context, CameraImportLedger.get(context), HistoryDb.Companion.get(context), new PhotoMoveStore(context), b -> {});
  }

  PhotoMove(Context context, CameraImportLedger ledger, HistoryDb history, PhotoMoveStore store, Faults faults) {
    this.context = context.getApplicationContext();
    resolver = context.getContentResolver();
    this.ledger = ledger;
    this.history = history;
    this.store = store;
    this.faults = faults;
  }

  Plan plan(String volume) throws IOException {
    PhotoStorage.requireWritable(context, volume);
    Map<String, String> checksums = ledger.importDestinations();
    Set<String> imported = new LinkedHashSet<>(checksums.keySet());
    imported.addAll(history.importDestinations());
    Map<String, Media> found = new LinkedHashMap<>();
    boolean needsLegacyUris = imported.stream().anyMatch(uri -> uri.startsWith(ALL + "/"));
    // Aggregate URIs from older releases are usable only when exactly one row resolves to the ID.
    Set<String> ambiguous = new LinkedHashSet<>();
    try (Cursor rows = resolver.query(ALL, COLUMNS, queryArgs(null, null), null)) {
      if (rows == null) throw new Failure("取り込み済み写真を確認できません。");
      while (rows.moveToNext()) {
        Media media = media(rows);
        String exact = media.uri().toString();
        String aggregate = aggregate(media.uri());
        if (imported.contains(exact)) found.put(exact, media);
        if (needsLegacyUris && imported.contains(aggregate) && found.put(aggregate, media) != null)
          ambiguous.add(aggregate);
      }
    }
    Map<String, PhotoMoveStore.Item> eligible = new LinkedHashMap<>();
    Set<String> skipped = new LinkedHashSet<>();
    for (String uri : imported) {
      Media row = found.get(uri);
      if (row == null) {
        if (CameraImportHistoryPlan.validUri(uri) || "existing".equals(uri)) skipped.add(uri);
        continue;
      }
      if (volume.equals(row.volume())) continue;
      if (ambiguous.contains(uri) || !eligible(row)) {
        skipped.add(row.uri().toString());
        continue;
      }
      String exact = row.uri().toString();
      String aggregate = aggregate(row.uri());
      String oldAlias = row.equals(found.get(aggregate)) && !ambiguous.contains(aggregate) ? aggregate : null;
      String expected = checksums.get(exact);
      String legacySha = oldAlias == null ? null : checksums.get(oldAlias);
      if (expected == null) expected = legacySha;
      else if (legacySha != null && !expected.equals(legacySha)) expected = "";
      if ("".equals(expected)) {
        skipped.add(exact);
        eligible.remove(exact);
        continue;
      }
      eligible.putIfAbsent(exact, new PhotoMoveStore.Item(exact, oldAlias, row.version(), expected, row.bytes(), null, null,
          null, PhotoMoveStore.QUEUED));
    }
    return new Plan(volume, List.copyOf(eligible.values()), skipped.size());
  }

  PhotoMoveStore.Batch batch() { return store.batch(); }

  void begin(Plan plan) throws IOException {
    if (!plan.volume().equals(PhotoStorage.selected(context)))
      throw new Failure("保存先が変わりました。対象を確認し直してください。");
    long bytes = 0;
    for (PhotoMoveStore.Item item : plan.items()) {
      bytes = Math.addExact(bytes, item.bytes());
    }
    PhotoStorage.requireSpace(context, plan.volume(), bytes);
    store.begin(plan.volume(), plan.items(), plan.skipped());
  }

  PhotoMoveStore.Batch run(BooleanSupplier stopped, Progress progress) throws IOException {
    PhotoMoveStore.Batch batch = store.batch();
    if (batch == null) throw new Failure("再開できる移動がありません。");
    PhotoStorage.requireSpace(context, batch.volume(), store.remainingBytes());
    progress.update(batch);
    for (PhotoMoveStore.Item item; (item = store.next()) != null;) {
      check(stopped);
      advance(item, batch.volume(), stopped);
      batch = store.batch();
      progress.update(batch);
    }
    store.finish();
    return batch;
  }

  private void advance(PhotoMoveStore.Item item, String volume, BooleanSupplier stopped) throws IOException {
    if (item.stage() == PhotoMoveStore.QUEUED) {
      Media original = requireSource(item);
      store.writing(item, "__loupe_move_" + UUID.randomUUID() + original.name().substring(original.name().lastIndexOf('.')));
      faults.after(Boundary.ALLOCATED);
      item = store.next();
    }
    if (item.stage() == PhotoMoveStore.COPYING) {
      Media original = requireSource(item);
      Uri target = pendingTarget(item, original, volume);
      String sha = copy(original.uri(), target, original.bytes(), stopped);
      faults.after(Boundary.COPIED);
      if (item.expectedSha() != null && !item.expectedSha().equals(sha))
        throw new Failure("取り込み時の記録と原本が一致しません。");
      verify(target, original.bytes(), sha, false, stopped);
      requireSource(item);
      store.verified(item, sha);
      faults.after(Boundary.VERIFIED);
      item = store.next();
    }
    Uri target = Uri.parse(item.target());
    if (item.stage() == PhotoMoveStore.VERIFIED) {
      Media source = requireSource(item);
      Media replacement = requireOwned(target);
      verify(target, item.bytes(), item.sha(), !replacement.pending(), stopped);
      if (replacement.pending()) {
        ContentValues values = new ContentValues();
        values.put(DISPLAY_NAME, source.name());
        values.put(DATE_TAKEN, source.taken());
        values.put(IS_PENDING, 0);
        if (resolver.update(target, values, null, null) != 1) throw new Failure("移動先の写真を公開できません。");
      }
      faults.after(Boundary.PUBLISHED);
      verify(target, item.bytes(), item.sha(), true, stopped);
      requireSource(item);
      store.updatingHistory(item);
      item = store.next();
    }
    if (item.stage() == PhotoMoveStore.UPDATING_HISTORY) {
      Media source = requireSource(item);
      verify(target, item.bytes(), item.sha(), true, stopped);
      Media published = requireOwned(target);
      PhotoRepository.Photo before = source.photo();
      PhotoRepository.Photo after = published.photo();
      PhotoMoveStore.Identity identity = store.identity(TagStore.locationSource(before));
      if (identity == null) identity = new PhotoMoveStore.Identity(ArchiveStore.key(before), TagStore.source(before));
      ledger.relocate(item.source(), item.sourceAlias(), item.target());
      history.relocate(item.source(), item.sourceAlias(), item.target());
      faults.after(Boundary.HISTORY_UPDATED);
      store.remapped(item, TagStore.locationSource(after), identity);
      faults.after(Boundary.REMAPPED);
      item = store.next();
    }
    // A process can die after deletion. The mounted source query must distinguish absence from failure.
    verify(target, item.bytes(), item.sha(), true, stopped);
    Uri sourceUri = Uri.parse(item.source());
    Media source = find(sourceUri);
    if (source != null) {
      requireSource(item);
      verify(sourceUri, item.bytes(), item.sha(), true, stopped);
      check(stopped);
      requireSource(item);
      PhotoStorage.requireWritable(context, source.volume());
      PhotoStorage.requireWritable(context, volume);
      Bundle exactVersion = queryArgs(
          GENERATION_MODIFIED + "=? AND " + OWNER_PACKAGE_NAME + "=? AND " + IS_PENDING + "=0 AND " + IS_TRASHED + "=0",
          new String[]{Long.toString(source.generationModified()), context.getPackageName()});
      if (resolver.delete(sourceUri, exactVersion) != 1 || find(sourceUri) != null)
        throw new Failure("移動元の写真を削除できません。");
      faults.after(Boundary.DELETED);
    }
    store.done(item);
  }

  private Uri pendingTarget(PhotoMoveStore.Item item, Media source, String volume) throws IOException {
    if (item.target() != null) {
      Uri target = Uri.parse(item.target());
      Media previous = find(target);
      if (previous != null) {
        requirePending(previous, item, source, volume);
        return target;
      }
    }
    PhotoStorage.requireSpace(context, volume, source.bytes());
    Uri collection = MediaStore.Images.Media.getContentUri(volume);
    Media previous = queryOne(collection, queryArgs(
        DISPLAY_NAME + "=? AND " + RELATIVE_PATH + "=? AND " + OWNER_PACKAGE_NAME + "=?",
        new String[]{item.token(), source.path(), context.getPackageName()}));
    Uri target;
    if (previous == null) {
      ContentValues values = new ContentValues();
      values.put(DISPLAY_NAME, item.token());
      values.put(RELATIVE_PATH, source.path());
      values.put(MIME_TYPE, source.mime());
      values.put(DATE_TAKEN, source.taken());
      values.put(IS_PENDING, 1);
      target = resolver.insert(collection, values);
      if (target == null) throw new Failure("移動先に写真を作れません。");
      faults.after(Boundary.INSERTED);
      previous = requireOwned(target);
    } else {
      target = previous.uri();
    }
    requirePending(previous, item, source, volume);
    store.attach(item, target.toString());
    return target;
  }

  private void requirePending(Media target, PhotoMoveStore.Item item, Media source, String volume) throws IOException {
    if (!owned(target) || !target.pending() || target.trashed() || !volume.equals(target.volume())
        || !item.token().equals(target.name()) || !source.path().equals(target.path()))
      throw new Failure("移動先のファイルを確認できません。");
  }

  private boolean owned(Media media) {
    return media != null && context.getPackageName().equals(media.owner());
  }

  private boolean eligible(Media media) {
    return owned(media) && !media.pending() && !media.trashed() && media.bytes() > 0
        && media.path() != null && media.path().startsWith("Pictures/CameraSync/")
        && media.name() != null && RawFormats.accepts(media.name()) && media.mime() != null;
  }

  private Media requireSource(PhotoMoveStore.Item item) throws IOException {
    Media media = find(Uri.parse(item.source()));
    if (!eligible(media) || !item.version().equals(media.version()))
      throw new Failure("移動元の写真が変更されたか、読み取れません。");
    return media;
  }

  private Media requireOwned(Uri uri) throws IOException {
    Media media = find(uri);
    if (!owned(media) || media.trashed()) throw new Failure("写真の所有者を確認できません。");
    return media;
  }

  private Media find(Uri uri) throws IOException {
    if (!CameraImportHistoryPlan.validUri(uri.toString())) throw new Failure("写真の保存先を確認できません。");
    PhotoStorage.requireMounted(context, uri);
    Media result = queryOne(uri, queryArgs(null, null));
    PhotoStorage.requireMounted(context, uri);
    if (result != null && !uri.equals(result.uri())) throw new Failure("別のボリュームの写真です。");
    return result;
  }

  private Media queryOne(Uri uri, Bundle args) throws IOException {
    try (Cursor c = resolver.query(uri, COLUMNS, args, null)) {
      if (c == null) throw new Failure("写真の情報を取得できません。");
      if (c.getCount() > 1) throw new Failure("写真を一意に特定できません。");
      return c.moveToFirst() ? media(c) : null;
    }
  }

  private static Media media(Cursor c) {
    String volume = Objects.requireNonNull(c.getString(4));
    return new Media(ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volume), c.getLong(0)),
        c.getString(1), c.getString(2), c.getString(3), volume, c.getString(5), c.getLong(6), c.getLong(7),
        c.getLong(8), c.getLong(9), c.getLong(10), c.getLong(11), c.getInt(12) != 0, c.getInt(13) != 0);
  }

  private static Bundle queryArgs(String selection, String[] arguments) {
    Bundle args = new Bundle();
    args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
    args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
    if (selection != null) {
      args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection);
      args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arguments);
    }
    return args;
  }

  private static String aggregate(Uri uri) {
    return ContentUris.withAppendedId(ALL, ContentUris.parseId(uri)).toString();
  }

  private String copy(Uri source, Uri target, long bytes, BooleanSupplier stopped) throws IOException {
    try (InputStream in = original(source)) {
      ParcelFileDescriptor fd = resolver.openFileDescriptor(target, "rwt");
      if (fd == null) throw new Failure("移動先に書き込めません。");
      try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
        String sha = hash(in, out, bytes, stopped);
        out.getFD().sync();
        return sha;
      }
    }
  }

  private void verify(Uri uri, long bytes, String sha, boolean published, BooleanSupplier stopped) throws IOException {
    Media media = requireOwned(uri);
    if (published == media.pending()) throw new Failure("写真の公開状態を確認できません。");
    try (InputStream in = original(uri)) {
      if (!sha.equals(hash(in, null, bytes, stopped))) throw new Failure("写真の読み戻しが原本と一致しません。");
    }
  }

  private InputStream original(Uri uri) throws IOException {
    InputStream in = resolver.openInputStream(MediaStore.setRequireOriginal(uri));
    if (in == null) throw new Failure("写真の原本を開けません。");
    return in;
  }

  private static String hash(InputStream in, OutputStream out, long bytes, BooleanSupplier stopped) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[64 * 1024];
      long count = 0;
      for (int size; (size = in.read(buffer)) != -1;) {
        check(stopped);
        count += size;
        if (count > bytes) throw new Failure("写真のサイズが変わりました。");
        digest.update(buffer, 0, size);
        if (out != null) out.write(buffer, 0, size);
      }
      if (count != bytes) throw new Failure("写真を最後まで読み取れません。");
      StringBuilder sha = new StringBuilder(64);
      for (byte value : digest.digest()) sha.append(String.format(Locale.ROOT, "%02x", value & 255));
      return sha.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void check(BooleanSupplier stopped) throws InterruptedIOException {
    if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("写真の移動を中断しました。");
  }

  @Override public void close() { store.close(); }
}
