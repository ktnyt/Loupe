package jp.nano.rawgallery;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageVolume;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jp.nano.rawgallery.camera.MediaSaver;
import jp.nano.rawgallery.camera.CameraImportHistoryPlan;
import jp.nano.rawgallery.camera.CameraImportLedger;
import jp.nano.rawgallery.camera.HistoryDb;
import static android.provider.MediaStore.MediaColumns.*;

/** Copies owned camera imports between volumes. The verified replacement always precedes deletion. */
final class PhotoMove implements AutoCloseable {
  static final class Failure extends IOException {
    Failure(String message) { super(message); }
  }

  enum Boundary { ALLOCATED, INSERTED, COPIED, VERIFIED, PUBLISHED, HISTORY_UPDATED, REMAPPED, BEFORE_SOURCE_DELETE, DELETED }
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
    Map<String, List<CameraImportLedger.MoveReceipt>> receipts = ledger.moveReceipts();
    Map<String, List<HistoryDb.MoveEvidence>> legacy = history.moveEvidence().stream()
        .collect(Collectors.groupingBy(HistoryDb.MoveEvidence::getUri));
    Set<String> imported = new LinkedHashSet<>(checksums.keySet());
    imported.addAll(legacy.keySet());
    Map<String, Media> found = new LinkedHashMap<>();
    Set<String> writableSources = new LinkedHashSet<>();
    for (StorageVolume candidate : PhotoStorage.volumes(context)) {
      String name = candidate.getMediaStoreVolumeName();
      if (PhotoStorage.directoryIfWritable(context, name) != null) writableSources.add(name);
    }
    boolean needsLegacyUris = imported.stream().anyMatch(uri -> uri.startsWith(ALL + "/"));
    // Aggregate URIs from older releases are usable only when exactly one row resolves to the ID.
    Set<String> ambiguous = new LinkedHashSet<>();
    try (Cursor rows = resolver.query(ALL, COLUMNS, queryArgs(null, null), null)) {
      if (rows == null) throw new Failure("取り込み済み写真を確認できません。");
      while (rows.moveToNext()) {
        long id = rows.getLong(0);
        String rowVolume = rows.getString(4);
        if (rowVolume == null) continue;
        String exact = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(rowVolume), id).toString();
        String aggregate = needsLegacyUris ? ContentUris.withAppendedId(ALL, id).toString() : "";
        boolean exactEvidence = imported.contains(exact);
        boolean aggregateEvidence = imported.contains(aggregate);
        if (!exactEvidence && !aggregateEvidence) continue;
        Media media = media(rows);
        if (exactEvidence) found.put(exact, media);
        if (aggregateEvidence && found.put(aggregate, media) != null) ambiguous.add(aggregate);
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
      if (ambiguous.contains(uri) || !eligible(row) || !writableSources.contains(row.volume())) {
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
      List<HistoryDb.MoveEvidence> candidates = new ArrayList<>(legacy.getOrDefault(exact, List.of()));
      if (oldAlias != null) candidates.addAll(legacy.getOrDefault(oldAlias, List.of()));
      var link = new ArrayList<>(receipts.getOrDefault(exact, List.of()));
      if (oldAlias != null) link.addAll(receipts.getOrDefault(oldAlias, List.of()));
      String legacyIds = candidates.stream().filter(evidence -> matchesLegacy(row, evidence)
              || link.stream().anyMatch(receipt -> matchesReceipt(receipt, evidence)))
          .map(evidence -> Long.toString(evidence.getId())).collect(Collectors.joining(","));
      if ("".equals(expected) || expected == null && legacyIds.isEmpty()) {
        skipped.add(exact);
        continue;
      }
      eligible.putIfAbsent(exact, new PhotoMoveStore.Item(exact, oldAlias, row.version(), expected, legacyIds,
          row.bytes(), null, null, null, PhotoMoveStore.QUEUED, null));
    }
    return new Plan(volume, List.copyOf(eligible.values()), skipped.size());
  }

  private static boolean matchesReceipt(CameraImportLedger.MoveReceipt receipt, HistoryDb.MoveEvidence evidence) {
    return receipt.bytes() == evidence.getBytes() && receipt.name().equalsIgnoreCase(evidence.getName())
        && evidence.getShot() > 0 && (receipt.capture() == evidence.getShot() || receipt.sourceModified() == evidence.getShot());
  }

  private static boolean matchesLegacy(Media media, HistoryDb.MoveEvidence evidence) {
    if (media.bytes() != evidence.getBytes()) return false;
    if (media.taken() > 0 && evidence.getShot() > 0 && media.taken() / 1000 != evidence.getShot()) return false;
    String original = evidence.getName();
    int dot = original.lastIndexOf('.');
    if (dot <= 0) return false;
    String saved = media.name();
    if (saved.equalsIgnoreCase(original)) return true;
    if (evidence.getShot() > 0 && saved.equalsIgnoreCase(MediaSaver.INSTANCE.collisionName(original, evidence.getShot())))
      return true;
    return Pattern.compile(Pattern.quote(original.substring(0, dot)) + " \\([1-9][0-9]*\\)"
        + Pattern.quote(original.substring(dot)), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(saved).matches();
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
      // Auto-cleanup can remove a verified photo. Serialize the two local deletion paths.
      if (!E2SyncJob.ORIGINALS.tryLock())
        throw new Failure("端末の容量整理が終わってから移動を再開してください。");
      try {
        advance(item, batch.volume(), stopped);
      } finally {
        E2SyncJob.ORIGINALS.unlock();
      }
      batch = store.batch();
      progress.update(batch);
    }
    store.finish();
    return batch;
  }

  private void advance(PhotoMoveStore.Item item, String volume, BooleanSupplier stopped) throws IOException {
    if (item.stage() <= PhotoMoveStore.VERIFIED) {
      if (!ledger.hasMoveEvidence(item.source(), item.sourceAlias(), item.expectedSha())
          && !history.hasMoveEvidence(item.source(), item.sourceAlias(), item.legacyIds()))
        throw new Failure("取り込みの記録を確認できません。元の写真を保持しました。");
    }
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
      source = requireSource(item);
      store.updatingHistory(item, logicalIdentity(source.photo()));
      item = store.next();
    }
    if (item.stage() == PhotoMoveStore.UPDATING_HISTORY) {
      Media published = verify(target, item.bytes(), item.sha(), true, stopped);
      if (!relocate(item, false)) throw new Failure("取り込みの記録を確認できません。元の写真を保持しました。");
      faults.after(Boundary.HISTORY_UPDATED);
      store.remapped(item, published.photo(), item.identity());
      faults.after(Boundary.REMAPPED);
      item = store.next();
    }
    // A process can die after deletion. The mounted source query must distinguish absence from failure.
    Media verified = verify(target, item.bytes(), item.sha(), true, stopped);
    Uri sourceUri = Uri.parse(item.source());
    Media source = find(sourceUri);
    if (source != null) {
      requireSource(item);
      verify(sourceUri, item.bytes(), item.sha(), true, stopped);
      if (!relocate(item, false)) throw new Failure("取り込みの記録を確認できません。元の写真を保持しました。");
      check(stopped);
      requireSource(item);
      PhotoStorage.requireWritable(context, source.volume());
      PhotoStorage.requireWritable(context, volume);
      faults.after(Boundary.BEFORE_SOURCE_DELETE);
      Media current = requireOwned(target);
      if (current.pending() || !verified.version().equals(current.version()))
        throw new Failure("移動先の写真が変更されました。元の写真を保持しました。");
      Bundle exactVersion = queryArgs(
          GENERATION_MODIFIED + "=? AND " + OWNER_PACKAGE_NAME + "=? AND " + IS_PENDING + "=0 AND " + IS_TRASHED + "=0",
          new String[]{Long.toString(source.generationModified()), context.getPackageName()});
      if (resolver.delete(sourceUri, exactVersion) != 1 || find(sourceUri) != null)
        throw new Failure("移動元の写真を削除できません。");
      faults.after(Boundary.DELETED);
    }
    store.done(item);
  }

  private PhotoMoveStore.Identity logicalIdentity(PhotoRepository.Photo photo) throws IOException {
    return PhotoRepository.moveIdentity(context, store, photo);
  }

  private boolean relocate(PhotoMoveStore.Item item, boolean reset) {
    String from = reset ? item.target() : item.source();
    String alias = reset ? null : item.sourceAlias();
    String to = reset ? item.source() : item.target();
    boolean ledgerSaved = ledger.relocate(from, alias, to, item.expectedSha());
    boolean legacySaved = history.relocate(from, alias, to, item.legacyIds());
    return ledgerSaved || legacySaved;
  }

  /** Stops the batch without deleting an original or a verified replacement. */
  PhotoMoveStore.Batch abandon() throws IOException {
    PhotoMoveStore.Batch batch = store.batch();
    if (batch == null) throw new Failure("終了できる移動がありません。");
    PhotoMoveStore.Item item = store.next();
    String identity = null;
    if (item != null) {
      if (item.stage() == PhotoMoveStore.COPYING) {
        discardIncomplete(item, batch.volume());
      } else if (item.stage() == PhotoMoveStore.UPDATING_HISTORY) {
        if (intactSource(item)) {
          relocate(item, true);
        } else {
          // Complete the reference swap if the only verifiable copy is already at the destination.
          Media replacement = verify(Uri.parse(item.target()), item.bytes(), item.sha(), true, () -> false);
          if (!relocate(item, false)) throw new Failure("取り込みの記録を確認できません。");
          store.remapped(item, replacement.photo(), item.identity());
        }
      } else if (item.stage() == PhotoMoveStore.REMAPPED && !publishedReplacement(item)) {
        if (!intactSource(item))
          throw new Failure("元の写真と移動先を確認できません。保存先を接続してから再試行してください。");
        relocate(item, true);
        identity = galleryUri(Uri.parse(item.target())).toString();
      }
    }
    store.abandon(identity);
    return batch;
  }

  private void discardIncomplete(PhotoMoveStore.Item item, String volume) throws IOException {
    Uri collection = MediaStore.Images.Media.getContentUri(volume);
    if (!MediaStore.getExternalVolumeNames(context).contains(volume)) return;
    Media pending = queryOne(collection, queryArgs(DISPLAY_NAME + "=? AND " + IS_PENDING + "=1 AND " + OWNER_PACKAGE_NAME + "=?",
        new String[]{item.token(), context.getPackageName()}));
    if (pending == null || !owned(pending) || !pending.pending() || !volume.equals(pending.volume())) return;
    if (item.target() != null && !item.target().equals(pending.uri().toString())) return;
    if (resolver.delete(pending.uri(), queryArgs(DISPLAY_NAME + "=? AND " + IS_PENDING
        + "=1 AND " + OWNER_PACKAGE_NAME + "=?", new String[]{item.token(), context.getPackageName()})) != 1)
      throw new Failure("未完了のコピーを片付けられません。保存先を確認してください。");
  }

  private boolean intactSource(PhotoMoveStore.Item item) {
    try {
      Media source = requireSource(item);
      verify(source.uri(), item.bytes(), item.sha(), true, () -> false);
      return true;
    } catch (IOException | RuntimeException unavailable) {
      return false;
    }
  }

  private boolean publishedReplacement(PhotoMoveStore.Item item) {
    try {
      verify(Uri.parse(item.target()), item.bytes(), item.sha(), true, () -> false);
      return true;
    } catch (IOException | RuntimeException unavailable) {
      return false;
    }
  }

  static Uri galleryUri(Uri image) {
    String volume = PhotoStorage.volumeOf(image);
    return ContentUris.withAppendedId(MediaStore.Files.getContentUri(
        PhotoStorage.PRIMARY.equals(volume) ? "external" : volume), ContentUris.parseId(image));
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

  private Media verify(Uri uri, long bytes, String sha, boolean published, BooleanSupplier stopped) throws IOException {
    Media media = requireOwned(uri);
    if (published == media.pending()) throw new Failure("写真の公開状態を確認できません。");
    try (InputStream in = original(uri)) {
      if (!sha.equals(hash(in, null, bytes, stopped))) throw new Failure("写真の読み戻しが原本と一致しません。");
    }
    Media current = requireOwned(uri);
    if (published == current.pending() || !media.version().equals(current.version()))
      throw new Failure("照合中に写真が変更されました。");
    return current;
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
