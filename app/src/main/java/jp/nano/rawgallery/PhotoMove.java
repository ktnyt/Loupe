package jp.nano.rawgallery;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
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
import jp.nano.rawgallery.PhotoStorageInventory.Reason;
import jp.nano.rawgallery.camera.CameraImportGate;
import jp.nano.rawgallery.camera.CameraImportHistoryPlan;
import jp.nano.rawgallery.camera.CameraImportLedger;
import jp.nano.rawgallery.camera.HistoryDb;
import jp.nano.rawgallery.camera.MediaSaver;
import static android.provider.MediaStore.MediaColumns.*;

/** Copies owned camera imports between volumes. The verified replacement always precedes deletion. */
final class PhotoMove implements AutoCloseable {
  static final class Failure extends IOException {
    Failure(String message) { super(message); }
  }

  enum Boundary {
    ALLOCATED, INSERTED, COPIED, VERIFIED, PUBLISHED, BEFORE_HISTORY_VERIFY, HISTORY_UPDATED, REMAPPED, BEFORE_SOURCE_DELETE, DELETED
  }
  interface Faults { void after(Boundary boundary); }
  interface Progress { void update(PhotoMoveStore.Batch batch); }
  interface OriginalOpen { InputStream open(Uri requiredOriginal) throws IOException; }
  record Plan(String volume, List<PhotoMoveStore.Item> items, int skipped, int archiveRejected,
      ArchivePreflight archivePreflight) {
    Plan {
      items = List.copyOf(items);
    }

    Plan(String volume, List<PhotoMoveStore.Item> items, int skipped) {
      this(volume, items, skipped, 0, null);
    }
  }

  private record ArchivePreflight(String volume, List<PhotoMoveStore.Item> items, int skipped, int rejected) {
    boolean covers(Plan plan) {
      return volume.equals(plan.volume()) && items.equals(plan.items())
          && skipped == plan.skipped() && rejected == plan.archiveRejected();
    }
  }

  private record Media(Uri uri, String name, String path, String mime, String volume,
      String owner, long bytes, long modified, long added, long taken, long generationAdded,
      long generationModified, boolean generationsKnown, boolean pending, boolean trashed) {
    boolean hasGenerationIdentity() {
      return generationsKnown && generationAdded >= 0 && generationModified >= generationAdded;
    }

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

  private static final class RunMetrics {
    final long started = SystemClock.elapsedRealtimeNanos();
    int completed, targetVerifiedPasses;
    long targetVerifiedBytes, targetVerifyNs, sourceVerifyNs, copyNs, syncNs;
    long allocateNs, publishNs, historyNs, deleteNs;

    void log(String result) {
      // The copy span includes the file sync. Bytes count only full, successful target verifications.
      DiagnosticLog.event("photo_move.run", 0, "result", result,
          "elapsed_ns", SystemClock.elapsedRealtimeNanos() - started, "completed", completed,
          "target_verified_passes", targetVerifiedPasses, "target_verified_bytes", targetVerifiedBytes,
          "target_verify_ns", targetVerifyNs, "source_verify_ns", sourceVerifyNs,
          "copy_ns", copyNs, "sync_ns", syncNs, "allocate_ns", allocateNs,
          "publish_ns", publishNs, "history_ns", historyNs, "delete_ns", deleteNs);
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
  private final OriginalOpen originalOpen;

  PhotoMove(Context context) {
    this(context, CameraImportLedger.get(context), HistoryDb.Companion.get(context), new PhotoMoveStore(context), b -> {});
  }

  PhotoMove(Context context, CameraImportLedger ledger, HistoryDb history, PhotoMoveStore store, Faults faults) {
    this(context, ledger, history, store, faults, context.getContentResolver()::openInputStream);
  }

  PhotoMove(Context context, CameraImportLedger ledger, HistoryDb history, PhotoMoveStore store, Faults faults,
      OriginalOpen originalOpen) {
    this.context = context.getApplicationContext();
    resolver = context.getContentResolver();
    this.ledger = ledger;
    this.history = history;
    this.store = store;
    this.faults = faults;
    this.originalOpen = originalOpen;
  }

  private record ImportEvidence(Map<String, String> checksums,
      Map<String, List<CameraImportLedger.MoveReceipt>> receipts,
      Map<String, List<HistoryDb.MoveEvidence>> legacy, Set<String> imported, Map<String, Media> found,
      Set<String> ambiguous, Set<String> writableSources) {}

  private ImportEvidence readImportEvidence(BooleanSupplier stopped) throws IOException {
    check(stopped);
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
        check(stopped);
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
    return new ImportEvidence(checksums, receipts, legacy, imported, found, ambiguous, writableSources);
  }

  Plan plan(String volume) throws IOException {
    return plan(volume, () -> false);
  }

  Plan plan(String volume, BooleanSupplier stopped) throws IOException {
    PhotoStorage.requireWritable(context, volume);
    ImportEvidence evidence = readImportEvidence(stopped);
    HistoricalCandidates historical = new HistoricalCandidates(evidence, stopped);
    Map<String, PhotoMoveStore.Item> eligible = new LinkedHashMap<>();
    Set<String> skipped = new LinkedHashSet<>();
    for (String uri : evidence.imported()) {
      check(stopped);
      Media row = evidence.found().get(uri);
      if (row == null) {
        if (CameraImportHistoryPlan.validUri(uri) || "existing".equals(uri)) skipped.add(uri);
        continue;
      }
      if (volume.equals(row.volume())) continue;
      if (evidence.ambiguous().contains(uri) || !eligible(row) || !evidence.writableSources().contains(row.volume())) {
        skipped.add(row.uri().toString());
        continue;
      }
      String exact = row.uri().toString();
      if (eligible.containsKey(exact)) continue;
      MoveProof proof = moveProof(evidence, row);
      HistoricalProof historicalProof = historical.match(row, proof);
      if ((!proof.matches() || historical.hasCompletedIdentity(row, proof)) && historicalProof == null) {
        skipped.add(exact);
        continue;
      }
      eligible.put(exact, new PhotoMoveStore.Item(exact, proof.alias(), row.version(), proof.expected(),
          historicalProof == null ? proof.legacyIds() : Long.toString(historicalProof.historyId()),
          row.bytes(), null, null, null, PhotoMoveStore.QUEUED, null,
          historicalProof == null ? null : historicalProof.archive(),
          historicalProof == null ? null : historicalProof.completed(), null));
    }
    return new Plan(volume, List.copyOf(eligible.values()), skipped.size());
  }

  private record HistoricalProof(PhotoMoveStore.ArchiveProof archive, PhotoMoveStore.CompletedProof completed) {
    long historyId() { return archive == null ? completed.historyId() : archive.historyId(); }
  }

  private final class HistoricalCandidates {
    private final ImportEvidence evidence;
    private final BooleanSupplier stopped;
    private final LegacyArchiveMoveProof.Budget budget = new LegacyArchiveMoveProof.Budget(1_000_000);
    private final Map<Long, PhotoMoveStore.CompletedProof> completedById = new HashMap<>();
    private final Map<String, PhotoMoveStore.CompletedProof> completedByUri = new HashMap<>();
    private final boolean active;
    private Map<String, List<LegacyArchiveMoveProof.ArchiveRecord>> records;
    private String epoch;

    HistoricalCandidates(ImportEvidence evidence, BooleanSupplier stopped) throws IOException {
      this.evidence = evidence;
      this.stopped = stopped;
      active = store.batch() != null;
      for (var proof : store.completed()) {
        check(stopped);
        completedById.put(proof.historyId(), proof);
        completedByUri.put(proof.source(), proof);
      }
    }

    boolean hasCompletedIdentity(Media row, MoveProof proof) {
      return completedByUri.containsKey(row.uri().toString())
          || proof.candidates().stream().anyMatch(candidate -> completedById.containsKey(candidate.getId()));
    }

    HistoricalProof match(Media row, MoveProof proof) throws IOException {
      if (active || proof.expected() != null || proof.candidates().size() != 1
          || !row.hasGenerationIdentity() || row.mime().startsWith("video/")) return null;
      String alias = aggregate(row.uri());
      if (evidence.ambiguous().contains(alias) || evidence.checksums().containsKey(alias)) return null;
      var history = proof.candidates().get(0);
      if (hasCompletedIdentity(row, proof)) {
        var completed = completedById.get(history.getId());
        if (completed == null || !completed.source().equals(history.getUri())
            || !matchesCompleted(completed, row) || !visibleAlias(row)
            || !completed.historyFingerprint().equals(LegacyArchiveMoveProof.historyFingerprint(history))) return null;
        return new HistoricalProof(null, completed);
      }
      if (proof.matches() || !PhotoStorage.PRIMARY.equals(row.volume()) || (row.taken() > 0
          && row.taken() / 1000 != history.getShot() + 1)
          || (row.taken() < 0 && row.taken() != -1)) return null;
      if (records == null) {
        epoch = LegacyArchiveMoveProof.epoch(context);
        records = epoch == null ? Map.of() : new LegacyArchiveMoveProof.Records(context).read(stopped);
      }
      var photo = row.photo();
      var record = records.get(photo.uri.toString());
      if (record == null || (proof.alias() == null && !visibleAlias(row))) return null;
      var backup = LegacyArchiveMoveProof.match(photo, epoch, row.generationAdded(), row.generationModified(),
          history, record, budget, stopped);
      return backup == null ? null : new HistoricalProof(backup, null);
    }

    private boolean visibleAlias(Media row) {
      try {
        return historicalAliasMatches(row);
      } catch (IOException | RuntimeException unavailable) {
        return false;
      }
    }
  }

  private boolean historicalAliasMatches(Media source) throws IOException {
    return source.equals(queryOne(Uri.parse(aggregate(source.uri())), queryArgs(null, null)));
  }

  /** Checks only proposed historical candidates. A rejected original is excluded, never replaced. */
  Plan preflight(Plan proposal, BooleanSupplier stopped) throws IOException {
    check(stopped);
    if (proposal.items().stream().noneMatch(PhotoMoveStore.Item::hasHistoricalProof)) return proposal;
    if (!CameraImportGate.lock.isHeldByCurrentThread()) throw new IllegalStateException("Move preflight needs the import gate");
    if (!E2SyncJob.ORIGINALS.tryLock()) throw new Failure("端末の容量整理が終わってから確認してください。");
    long started = SystemClock.elapsedRealtimeNanos();
    int checked = 0, rejected = 0;
    long verifiedBytes = 0;
    String result = "failed";
    try {
      Map<String, String> cameraEvidence = ledger.importDestinations();
      var archive = new LegacyArchiveMoveProof.Records(context);
      List<PhotoMoveStore.Item> verified = new ArrayList<>();
      for (var item : proposal.items()) {
        check(stopped);
        if (!item.hasHistoricalProof()) {
          verified.add(item);
          continue;
        }
        try {
          Media source = requireHistoricalSource(item, cameraEvidence, archive, stopped);
          verify(source.uri(), item.bytes(), item.proofSha(), true, stopped);
          requireHistoricalSource(item, cameraEvidence, archive, stopped);
          check(stopped);
          verified.add(item);
          checked++;
          verifiedBytes += item.bytes();
        } catch (InterruptedIOException cancelled) {
          throw cancelled;
        } catch (IOException | RuntimeException unavailable) {
          rejected++;
        }
      }
      check(stopped);
      List<PhotoMoveStore.Item> items = List.copyOf(verified);
      int skipped = proposal.skipped() + rejected;
      result = "completed";
      return new Plan(proposal.volume(), items, skipped, rejected,
          new ArchivePreflight(proposal.volume(), items, skipped, rejected));
    } catch (InterruptedIOException cancelled) {
      result = "cancelled";
      throw cancelled;
    } finally {
      E2SyncJob.ORIGINALS.unlock();
      DiagnosticLog.event("photo_move.preflight", 0, "result", result,
          "elapsed_ns", SystemClock.elapsedRealtimeNanos() - started,
          "verified", checked, "excluded", rejected, "verified_bytes", verifiedBytes);
    }
  }

  /** Read import locations on a worker thread. The gate keeps both histories stable during the read. */
  PhotoStorageInventory inventory() throws IOException {
    return inventory(null);
  }

  PhotoStorageInventory inventory(String volume) throws IOException {
    var gate = CameraImportGate.lock;
    if (!gate.tryLock()) throw new Failure("取り込みや移動が終わってから保存場所を確認してください。");
    try {
      if (PhotoMoveService.state().running())
        throw new Failure("移動が終わってから保存場所を確認してください。");
      if (store.batch() != null)
        throw new Failure("移動を再開または終了してから保存場所を確認してください。");
      String selected = volume == null ? PhotoStorage.selected(context) : volume;
      ImportEvidence evidence = readImportEvidence(() -> false);
      // An aggregate URI does not bind a historical receipt to a volume. Even the recent-volume
      // catalog cannot prove that every previously used card is present. Keep its current row as
      // a candidate; only independent exact evidence can confirm the historical association.
      return inventory(evidence, ledger.importStoredSizes(), selected, false);
    } finally {
      gate.unlock();
    }
  }

  private PhotoStorageInventory inventory(ImportEvidence evidence, Map<String, Long> storedSizes,
      String targetVolume, boolean aggregateComplete) throws IOException {
    HistoricalCandidates historical = new HistoricalCandidates(evidence, () -> false);
    var volumes = new PhotoStorageLocation.Volumes(context);
    Map<Uri, PhotoStorageInventory.Photo> photos = new LinkedHashMap<>();
    Map<PhotoStorageInventory.History, Integer> unresolved = new LinkedHashMap<>();
    for (String reference : evidence.imported()) {
      boolean photoReference = CameraImportHistoryPlan.validUri(reference);
      var legacy = evidence.legacy().getOrDefault(reference, List.of());
      if (!photoReference && !"existing".equals(reference)
          && legacy.stream().noneMatch(row -> RawFormats.accepts(row.getName()))) continue;
      if (!evidence.checksums().containsKey(reference) && !legacy.isEmpty()
          && legacy.stream().allMatch(row -> VideoFormats.accepts(row.getName()))) continue;
      Media row = photoReference ? evidence.found().get(reference) : null;
      boolean ambiguous = evidence.ambiguous().contains(reference);
      if (row == null || ambiguous) {
        addUnresolved(evidence, reference, ambiguous, null, null, volumes, unresolved);
        continue;
      }
      if (VideoFormats.accepts(row.name()) || row.mime() != null && row.mime().startsWith("video/")) continue;
      if (!aggregateComplete && reference.startsWith(ALL + "/")) {
        var location = volumes.location(PhotoStorageLocation.Kind.CURRENT, row.volume(), row.path());
        addUnresolved(evidence, reference, true, inventoryName(row), location, volumes, unresolved);
      }
      if (!photos.containsKey(row.uri()))
        photos.put(row.uri(), inventoryPhoto(evidence, storedSizes, row, targetVolume, aggregateComplete, volumes,
            unresolved, historical));
    }
    List<PhotoStorageInventory.History> historyRows = new ArrayList<>();
    unresolved.forEach((row, count) -> historyRows.add(row.withCount(count)));
    return new PhotoStorageInventory(new ArrayList<>(photos.values()), historyRows);
  }

  private static void addUnresolved(ImportEvidence evidence, String reference, boolean ambiguous,
      String candidateName, PhotoStorageLocation candidateLocation, PhotoStorageLocation.Volumes volumes,
      Map<PhotoStorageInventory.History, Integer> unresolved) {
    Reason reason = ambiguous ? Reason.AMBIGUOUS_REFERENCE
        : CameraImportHistoryPlan.validUri(reference) ? Reason.MISSING_REFERENCE : Reason.NO_FILE_REFERENCE;
    boolean conflict = "".equals(evidence.checksums().get(reference));
    var location = recordedLocation(volumes, reference);
    List<Reason> reasons = conflict ? List.of(reason, Reason.CONFLICTING_HISTORY) : List.of(reason);
    List<HistoryDb.MoveEvidence> legacy = evidence.legacy().getOrDefault(reference, List.of());
    for (var record : legacy) {
      if (!VideoFormats.accepts(record.getName()))
        addUnresolvedName(record.getName(), location, reasons, candidateName, candidateLocation, unresolved);
    }
    addUnresolvedReceipts(evidence, reference, location, reasons, candidateName, candidateLocation, conflict, unresolved);
  }

  private static String addUnresolvedReceipts(ImportEvidence evidence, String reference,
      PhotoStorageLocation location, List<Reason> reasons, String candidateName,
      PhotoStorageLocation candidateLocation, boolean conflict, Map<PhotoStorageInventory.History, Integer> unresolved) {
    List<HistoryDb.MoveEvidence> legacy = evidence.legacy().getOrDefault(reference, List.of());
    List<CameraImportLedger.MoveReceipt> receipts = evidence.receipts().getOrDefault(reference, List.of());
    if (legacy.isEmpty() && receipts.isEmpty())
      addUnresolvedName(null, location, reasons, candidateName, candidateLocation, unresolved);
    String firstName = null;
    for (var receipt : receipts) {
      if (VideoFormats.accepts(receipt.name())) continue;
      if (firstName == null) firstName = receipt.name();
      // One history can be represented in both stores. A shared URI or filename alone is not enough.
      if (!conflict && legacy.stream().anyMatch(record -> matchesReceipt(receipt, record))) continue;
      addUnresolvedName(receipt.name(), location, reasons, candidateName, candidateLocation, unresolved);
    }
    return firstName;
  }

  private static PhotoStorageLocation recordedLocation(PhotoStorageLocation.Volumes volumes, String reference) {
    return volumes.location(PhotoStorageLocation.Kind.RECORDED, PhotoStorageLocation.recordedVolume(reference), null);
  }

  private static void addUnresolvedName(String name, PhotoStorageLocation location, List<Reason> reasons,
      String candidateName, PhotoStorageLocation candidateLocation,
      Map<PhotoStorageInventory.History, Integer> unresolved) {
    String displayName = name == null || name.isBlank() ? "名前の記録がない写真" : name;
    unresolved.merge(new PhotoStorageInventory.History(displayName, location, reasons, candidateName,
        candidateLocation, 1), 1, Integer::sum);
  }

  private static String inventoryName(Media row) {
    return row.name() == null || row.name().isBlank() ? "写真名を確認できません" : row.name();
  }

  private PhotoStorageInventory.Photo inventoryPhoto(ImportEvidence evidence, Map<String, Long> storedSizes,
      Media row, String targetVolume, boolean aggregateComplete, PhotoStorageLocation.Volumes volumes,
      Map<PhotoStorageInventory.History, Integer> unresolved, HistoricalCandidates historical) throws IOException {
    var location = volumes.location(PhotoStorageLocation.Kind.CURRENT, row.volume(), row.path());
    String displayName = inventoryName(row);
    MoveProof planProof = moveProof(evidence, row);
    boolean unresolvedAggregate = !aggregateComplete && planProof.alias() != null;
    MoveProof proof = unresolvedAggregate ? moveProof(evidence, row, false) : planProof;
    boolean conflict = "".equals(proof.expected());
    String exact = row.uri().toString();
    List<String> references = proof.alias() == null ? List.of(exact) : List.of(exact, proof.alias());
    boolean sizeContradiction = references.stream().anyMatch(ref -> sizeContradicts(storedSizes.get(ref), row.bytes()));
    boolean completedIdentity = historical.hasCompletedIdentity(row, planProof);
    Reason sourceIssue = sourceIssue(row);
    boolean alreadyThere = targetVolume.equals(row.volume());
    boolean sourceWritable = evidence.writableSources().contains(row.volume());
    boolean targetWritable = evidence.writableSources().contains(targetVolume);
    HistoricalProof historicalProof = sourceIssue == null && (completedIdentity || (!alreadyThere && sourceWritable
        && targetWritable && !planProof.matches())) ? historical.match(row, planProof) : null;
    boolean completedMatch = historicalProof != null && historicalProof.completed() != null;
    var mismatch = completedMatch ? new HistoryMismatch(null, List.of())
        : unresolvedHistory(proof, row, sizeContradiction, unresolvedAggregate, volumes, displayName,
            location, unresolved);

    String recordedName = mismatch.name();
    for (String reference : references) {
      List<Reason> receiptReasons = new ArrayList<>(2);
      if (conflict && evidence.checksums().containsKey(reference)) receiptReasons.add(Reason.CONFLICTING_HISTORY);
      if (sizeContradicts(storedSizes.get(reference), row.bytes())) receiptReasons.add(Reason.HISTORY_SIZE);
      if (receiptReasons.isEmpty()) continue;
      String name = addUnresolvedReceipts(evidence, reference, recordedLocation(volumes, reference),
          receiptReasons, displayName, location, conflict, unresolved);
      if (recordedName == null) recordedName = name;
    }

    boolean historyMatches = completedMatch || (!completedIdentity && proof.matches() && !sizeContradiction);
    List<Reason> reasons = new ArrayList<>();
    if (sourceIssue != null) reasons.add(sourceIssue);
    if (!alreadyThere && !sourceWritable) reasons.add(Reason.VOLUME_UNAVAILABLE);
    if (!historyMatches) reasons.addAll(mismatch.reasons());
    if ("".equals(planProof.expected()) && !reasons.contains(Reason.CONFLICTING_HISTORY))
      reasons.add(Reason.CONFLICTING_HISTORY);
    if (!alreadyThere && !targetWritable) reasons.add(Reason.DESTINATION_UNAVAILABLE);
    // Plan checks the SHA while moving. Display disagreements must not silently change its membership.
    boolean planned = planProof.matches() && !completedIdentity && sourceIssue == null && sourceWritable && targetWritable;
    boolean pendingCheck = historicalProof != null && sourceWritable && targetWritable;
    if (!alreadyThere && pendingCheck) reasons.add(0, Reason.ARCHIVE_CHECK_REQUIRED);
    else if (completedIdentity && historicalProof == null) reasons.add(0, Reason.HISTORICAL_PROOF_CHANGED);
    var status = alreadyThere ? PhotoStorageInventory.Status.ALREADY_THERE
        : planned || pendingCheck ? PhotoStorageInventory.Status.ELIGIBLE : PhotoStorageInventory.Status.EXCLUDED;
    return new PhotoStorageInventory.Photo(row.uri(), displayName, location, status, reasons,
        historyMatches ? null : recordedName, historyMatches);
  }

  private static boolean sizeContradicts(Long storedSize, long liveSize) {
    return storedSize != null && (storedSize <= 0 || storedSize != liveSize);
  }

  private record HistoryMismatch(String name, List<Reason> reasons) {}

  private static HistoryMismatch unresolvedHistory(MoveProof proof, Media row, boolean sizeContradiction,
      boolean unresolvedAggregate, PhotoStorageLocation.Volumes volumes, String candidateName,
      PhotoStorageLocation candidateLocation, Map<PhotoStorageInventory.History, Integer> unresolved) {
    boolean conflict = "".equals(proof.expected());
    List<Reason> contradictions = new ArrayList<>(2);
    if (conflict) contradictions.add(Reason.CONFLICTING_HISTORY);
    if (sizeContradiction) contradictions.add(Reason.HISTORY_SIZE);
    List<Reason> fallback = contradictions.isEmpty() ? List.of(unresolvedAggregate
        ? Reason.AMBIGUOUS_REFERENCE : Reason.CONFLICTING_HISTORY) : contradictions;
    HistoryMismatch closest = new HistoryMismatch(null, fallback);
    for (HistoryDb.MoveEvidence record : proof.candidates()) {
      if (VideoFormats.accepts(record.getName())) continue;
      boolean matched = proof.matched().contains(record);
      if (contradictions.isEmpty() && matched) continue;
      List<Reason> reasons = matched ? contradictions : historyMismatchReasons(row, record, sizeContradiction);
      if (!matched && conflict) reasons.add(Reason.CONFLICTING_HISTORY);
      if (reasons.isEmpty()) reasons = fallback;
      addUnresolvedName(record.getName(), recordedLocation(volumes, record.getUri()), reasons, candidateName,
          candidateLocation, unresolved);
      if (closest.name() == null || reasons.size() < closest.reasons().size())
        closest = new HistoryMismatch(record.getName(), reasons);
    }
    return closest;
  }

  private static List<Reason> historyMismatchReasons(Media row, HistoryDb.MoveEvidence record,
      boolean sizeContradiction) {
    List<Reason> reasons = new ArrayList<>();
    if (!matchesLegacyName(row, record)) reasons.add(Reason.HISTORY_NAME);
    if (sizeContradiction || row.bytes() != record.getBytes()) reasons.add(Reason.HISTORY_SIZE);
    if (record.getShot() > 0 && row.taken() <= 0) reasons.add(Reason.HISTORY_CAPTURE_MISSING);
    else if (record.getShot() > 0 && row.taken() / 1000 != record.getShot())
      reasons.add(Reason.HISTORY_CAPTURE_DIFFERS);
    return reasons;
  }

  private record MoveProof(String alias, String expected, List<HistoryDb.MoveEvidence> matched,
      List<HistoryDb.MoveEvidence> candidates) {
    boolean matches() { return !"".equals(expected) && (expected != null || !matched.isEmpty()); }

    String legacyIds() {
      return matched.stream().map(candidate -> Long.toString(candidate.getId())).collect(Collectors.joining(","));
    }
  }

  private static MoveProof moveProof(ImportEvidence evidence, Media row) {
    return moveProof(evidence, row, true);
  }

  private static MoveProof moveProof(ImportEvidence evidence, Media row, boolean includeAggregate) {
    String exact = row.uri().toString();
    String aggregate = aggregate(row.uri());
    String oldAlias = includeAggregate && row.equals(evidence.found().get(aggregate))
        && !evidence.ambiguous().contains(aggregate)
        ? aggregate : null;
    String expected = evidence.checksums().get(exact);
    String legacySha = oldAlias == null ? null : evidence.checksums().get(oldAlias);
    if (expected == null) expected = legacySha;
    else if (legacySha != null && !expected.equals(legacySha)) expected = "";
    List<HistoryDb.MoveEvidence> candidates = new ArrayList<>(evidence.legacy().getOrDefault(exact, List.of()));
    if (oldAlias != null) candidates.addAll(evidence.legacy().getOrDefault(oldAlias, List.of()));
    var link = new ArrayList<>(evidence.receipts().getOrDefault(exact, List.of()));
    if (oldAlias != null) link.addAll(evidence.receipts().getOrDefault(oldAlias, List.of()));
    List<HistoryDb.MoveEvidence> matched = candidates.stream().filter(candidate -> matchesLegacy(row, candidate)
        || link.stream().anyMatch(receipt -> matchesReceipt(receipt, candidate))).collect(Collectors.toList());
    return new MoveProof(oldAlias, expected, matched, candidates);
  }

  private static boolean matchesReceipt(CameraImportLedger.MoveReceipt receipt, HistoryDb.MoveEvidence evidence) {
    return receipt.bytes() == evidence.getBytes() && receipt.name().equalsIgnoreCase(evidence.getName())
        && evidence.getShot() > 0 && (receipt.capture() == evidence.getShot() || receipt.sourceModified() == evidence.getShot());
  }

  private static boolean matchesLegacy(Media media, HistoryDb.MoveEvidence evidence) {
    if (media.bytes() != evidence.getBytes()) return false;
    if (evidence.getShot() > 0 && (media.taken() <= 0 || media.taken() / 1000 != evidence.getShot())) return false;
    return matchesLegacyName(media, evidence);
  }

  private static boolean matchesLegacyName(Media media, HistoryDb.MoveEvidence evidence) {
    String original = evidence.getName();
    String saved = media.name();
    if (original == null || saved == null) return false;
    int dot = original.lastIndexOf('.');
    if (dot <= 0) return false;
    if (saved.equalsIgnoreCase(original)) return true;
    if (evidence.getShot() > 0 && saved.equalsIgnoreCase(MediaSaver.INSTANCE.collisionName(original, evidence.getShot())))
      return true;
    return Pattern.compile(Pattern.quote(original.substring(0, dot)) + " \\([1-9][0-9]*\\)"
        + Pattern.quote(original.substring(dot)), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(saved).matches();
  }

  PhotoMoveStore.Batch batch() { return store.batch(); }

  void begin(Plan plan) throws IOException {
    if (plan.items().stream().anyMatch(PhotoMoveStore.Item::hasHistoricalProof)
        && (plan.archivePreflight() == null || !plan.archivePreflight().covers(plan)))
      throw new Failure("移動の前に写真の照合を完了してください。");
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
    RunMetrics metrics = new RunMetrics();
    String result = "failed";
    try {
      PhotoMoveStore.Batch batch = store.batch();
      if (batch == null) throw new Failure("再開できる移動がありません。");
      PhotoStorage.requireSpace(context, batch.volume(), store.remainingBytes());
      progress.update(batch);
      Map<String, String> cameraEvidence = null;
      var archive = new LegacyArchiveMoveProof.Records(context);
      for (PhotoMoveStore.Item item; (item = store.next()) != null;) {
        check(stopped);
        // Auto-cleanup can remove a verified photo. Serialize the two local deletion paths.
        if (!E2SyncJob.ORIGINALS.tryLock())
          throw new Failure("端末の容量整理が終わってから移動を再開してください。");
        try {
          if (item.hasHistoricalProof() && cameraEvidence == null) cameraEvidence = ledger.importDestinations();
          advance(item, batch.volume(), stopped, metrics, cameraEvidence, archive);
          metrics.completed++;
        } finally {
          E2SyncJob.ORIGINALS.unlock();
        }
        batch = store.batch();
        progress.update(batch);
      }
      store.finish();
      result = "completed";
      return batch;
    } catch (InterruptedIOException cancelled) {
      result = "cancelled";
      throw cancelled;
    } finally {
      metrics.log(result);
    }
  }

  private void advance(PhotoMoveStore.Item item, String volume, BooleanSupplier stopped, RunMetrics metrics,
      Map<String, String> cameraEvidence, LegacyArchiveMoveProof.Records archive) throws IOException {
    if (item.hasHistoricalProof()) {
      requireHistoricalHistory(item, cameraEvidence);
      requireHistoricalEpoch(item);
      if (item.stage() <= PhotoMoveStore.VERIFIED) requireHistoricalSource(item, cameraEvidence, archive, stopped);
      if (item.stage() >= PhotoMoveStore.VERIFIED && !item.proofSha().equals(item.sha()))
        throw archiveMismatch();
    } else if (item.stage() <= PhotoMoveStore.VERIFIED) {
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
      long started = SystemClock.elapsedRealtimeNanos();
      Uri target;
      try {
        target = pendingTarget(item, original, volume);
      } finally {
        metrics.allocateNs += SystemClock.elapsedRealtimeNanos() - started;
      }
      String sha = copy(original.uri(), target, original.bytes(), stopped, metrics);
      faults.after(Boundary.COPIED);
      if (item.expectedSha() != null && !item.expectedSha().equals(sha))
        throw new Failure("取り込み時の記録と原本が一致しません。");
      if (item.hasHistoricalProof() && !item.proofSha().equals(sha)) throw archiveMismatch();
      verifyTarget(target, original.bytes(), sha, false, stopped, metrics);
      requireSource(item);
      store.verified(item, sha);
      faults.after(Boundary.VERIFIED);
      item = store.next();
    }
    Uri target = Uri.parse(item.target());
    if (item.stage() == PhotoMoveStore.VERIFIED) {
      Media source = requireSource(item);
      Media replacement = requireOwned(target);
      verifyTarget(target, item.bytes(), item.sha(), !replacement.pending(), stopped, metrics);
      if (replacement.pending()) {
        ContentValues values = new ContentValues();
        values.put(DISPLAY_NAME, source.name());
        values.put(DATE_TAKEN, source.taken());
        values.put(IS_PENDING, 0);
        long started = SystemClock.elapsedRealtimeNanos();
        try {
          if (resolver.update(target, values, null, null) != 1) throw new Failure("移動先の写真を公開できません。");
        } finally {
          metrics.publishNs += SystemClock.elapsedRealtimeNanos() - started;
        }
      }
      faults.after(Boundary.PUBLISHED);
      source = requireSource(item);
      store.updatingHistory(item, logicalIdentity(source.photo()));
      item = store.next();
    }
    if (item.stage() == PhotoMoveStore.UPDATING_HISTORY) {
      if (item.hasHistoricalProof()) requireHistoricalSource(item, cameraEvidence, archive, stopped);
      // Prepare the journal first, then read back the published bytes before either history is changed.
      faults.after(Boundary.BEFORE_HISTORY_VERIFY);
      Media published = verifyTarget(target, item.bytes(), item.sha(), true, stopped, metrics);
      check(stopped);
      relocateForMove(item, metrics);
      faults.after(Boundary.HISTORY_UPDATED);
      store.remapped(item, published.photo(), item.identity());
      faults.after(Boundary.REMAPPED);
      item = store.next();
    }
    // A process can die after deletion. The mounted source query must distinguish absence from failure.
    String targetEpoch = item.hasHistoricalProof() ? LegacyArchiveMoveProof.epoch(context, volume) : null;
    Media verified = verifyTarget(target, item.bytes(), item.sha(), true, stopped, metrics);
    if (item.hasHistoricalProof() && (targetEpoch == null || !volume.equals(verified.volume())
        || !targetEpoch.equals(LegacyArchiveMoveProof.epoch(context, volume)))) throw archiveMismatch();
    Uri sourceUri = Uri.parse(item.source());
    Media source = find(sourceUri);
    if (source != null) {
      requireSource(item);
      long started = SystemClock.elapsedRealtimeNanos();
      try {
        verify(sourceUri, item.bytes(), item.sha(), true, stopped);
      } finally {
        metrics.sourceVerifyNs += SystemClock.elapsedRealtimeNanos() - started;
      }
      relocateForMove(item, metrics);
      check(stopped);
      requireSource(item);
      PhotoStorage.requireWritable(context, source.volume());
      PhotoStorage.requireWritable(context, volume);
      if (item.hasHistoricalProof()) {
        requireRelocatedHistory(item, cameraEvidence);
        store.prepareCompletion(item, historicalDestination(item, verified, targetEpoch));
        item = store.next();
      }
      faults.after(Boundary.BEFORE_SOURCE_DELETE);
      if (item.hasHistoricalProof()) requireHistoricalSource(item, cameraEvidence, archive, stopped);
      Media current = requireOwned(target);
      if (current.pending() || !verified.version().equals(current.version())
          || item.hasHistoricalProof() && !matchesCompleted(item.pendingCompletion(), current))
        throw new Failure("移動先の写真が変更されました。元の写真を保持しました。");
      Bundle exactVersion = queryArgs(
          GENERATION_MODIFIED + "=? AND " + OWNER_PACKAGE_NAME + "=? AND " + IS_PENDING + "=0 AND " + IS_TRASHED + "=0",
          new String[]{Long.toString(source.generationModified()), context.getPackageName()});
      check(stopped);
      started = SystemClock.elapsedRealtimeNanos();
      try {
        if (resolver.delete(sourceUri, exactVersion) != 1 || find(sourceUri) != null)
          throw new Failure("移動元の写真を削除できません。");
      } finally {
        metrics.deleteNs += SystemClock.elapsedRealtimeNanos() - started;
      }
      faults.after(Boundary.DELETED);
    }
    if (item.hasHistoricalProof()) finishHistoricalMove(item, verified, cameraEvidence);
    else store.done(item);
  }

  private PhotoMoveStore.Identity logicalIdentity(PhotoRepository.Photo photo) throws IOException {
    return PhotoRepository.moveIdentity(context, store, photo);
  }

  private void relocateForMove(PhotoMoveStore.Item item, RunMetrics metrics) throws IOException {
    long started = SystemClock.elapsedRealtimeNanos();
    try {
      if (!relocate(item, false)) throw new Failure("取り込みの記録を確認できません。元の写真を保持しました。");
    } finally {
      metrics.historyNs += SystemClock.elapsedRealtimeNanos() - started;
    }
  }

  private boolean relocate(PhotoMoveStore.Item item, boolean reset) {
    String from = reset ? item.target() : item.source();
    String alias = reset ? null : item.sourceAlias();
    String to = reset ? item.source() : item.target();
    if (item.hasHistoricalProof()) {
      if (item.expectedSha() != null || !Long.toString(item.historyId()).equals(item.legacyIds())) return false;
      if (reset && item.sourceAlias() != null) to = item.sourceAlias();
      return history.relocate(from, alias, to, item.legacyIds());
    }
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
      } else if (item.hasHistoricalProof() && item.stage() >= PhotoMoveStore.UPDATING_HISTORY) {
        if (!E2SyncJob.ORIGINALS.tryLock())
          throw new Failure("端末の容量整理が終わってから移動を終了してください。");
        try {
          String rollbackTarget = abandonHistorical(item);
          PhotoMoveStore.Batch completed = store.batch();
          store.abandon(rollbackTarget);
          return completed;
        } finally {
          E2SyncJob.ORIGINALS.unlock();
        }
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

  private String abandonHistorical(PhotoMoveStore.Item item) throws IOException {
    boolean sourcePresent = find(Uri.parse(item.source())) != null;
    boolean historyPresent = history.containsMoveEvidence(item.historyId());
    if (!sourcePresent) {
      Media verified = verify(Uri.parse(item.target()), item.bytes(), item.proofSha(), true, () -> false);
      if (historyPresent) {
        finishHistoricalMove(item, verified, ledger.importDestinations());
      } else {
        // The surviving target keeps its logical identity. An erased history grants no completed proof.
        requireHistoricalDestination(item, verified);
        store.finishWithoutHistory(item);
      }
      return null;
    }
    if (historyPresent) {
      requireHistoricalHistory(item, ledger.importDestinations());
      requireHistoricalEpoch(item);
      if (!Objects.equals(store.completed(item.historyId()), item.sourceProof())
          || !intactSource(item) || !relocate(item, true)) throw archiveMismatch();
    }
    return galleryUri(Uri.parse(item.target())).toString();
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
    return media != null && sourceIssue(media) == null;
  }

  private Reason sourceIssue(Media media) {
    if (media.owner() == null) return Reason.OWNER_UNKNOWN;
    if (!owned(media)) return Reason.OTHER_OWNER;
    if (media.pending()) return Reason.PENDING;
    if (media.trashed()) return Reason.TRASHED;
    if (media.bytes() <= 0) return Reason.SIZE_UNKNOWN;
    if (media.path() == null || !media.path().startsWith("Pictures/CameraSync/")) return Reason.UNSUPPORTED_FOLDER;
    if (media.name() == null || !RawFormats.accepts(media.name()) || media.mime() == null)
      return Reason.UNSUPPORTED_FORMAT;
    return null;
  }

  private static Failure archiveMismatch() {
    return new Failure("取り込みの記録とバックアップを照合できません。元の写真を保持しました。");
  }

  private void requireHistoricalEpoch(PhotoMoveStore.Item item) throws IOException {
    if (item.sourceProof() == null) {
      if (!item.archiveProof().epoch().equals(LegacyArchiveMoveProof.epoch(context))) throw archiveMismatch();
    } else if (!item.sourceProof().epoch().equals(LegacyArchiveMoveProof.epoch(context, item.sourceProof().volume()))) {
      throw archiveMismatch();
    }
  }

  private HistoryDb.MoveEvidence requireHistoricalHistory(PhotoMoveStore.Item item, Map<String, String> cameraEvidence)
      throws IOException {
    String aggregate = aggregate(Uri.parse(item.source()));
    String target = item.stage() >= PhotoMoveStore.UPDATING_HISTORY ? item.target() : null;
    String targetAlias = target == null ? null : aggregate(Uri.parse(target));
    if (item.expectedSha() != null || !Long.toString(item.historyId()).equals(item.legacyIds())
        || item.sourceAlias() != null && !aggregate.equals(item.sourceAlias())
        || cameraEvidence.containsKey(item.source()) || cameraEvidence.containsKey(aggregate)
        || target != null && (cameraEvidence.containsKey(target) || cameraEvidence.containsKey(targetAlias)))
      throw archiveMismatch();
    var records = history.moveEvidenceFor(item.source(), aggregate, target, targetAlias);
    if (records.size() != 1) throw archiveMismatch();
    var record = records.get(0);
    if (record.getId() != item.historyId()
        || !record.getUri().equals(item.source()) && !record.getUri().equals(item.sourceAlias())
            && !record.getUri().equals(target)
        || !item.historyFingerprint().equals(LegacyArchiveMoveProof.historyFingerprint(record)))
      throw archiveMismatch();
    return record;
  }

  private boolean matchesCompleted(PhotoMoveStore.CompletedProof proof, Media source) {
    return proof != null && source.hasGenerationIdentity() && proof.source().equals(source.uri().toString()) && proof.volume().equals(source.volume())
        && proof.version().equals(source.version()) && proof.generationAdded() == source.generationAdded()
        && proof.generationModified() == source.generationModified() && proof.bytes() == source.bytes()
        && proof.epoch().equals(LegacyArchiveMoveProof.epoch(context, source.volume()));
  }

  private Media requireHistoricalSource(PhotoMoveStore.Item item, Map<String, String> cameraEvidence,
      LegacyArchiveMoveProof.Records archive, BooleanSupplier stopped) throws IOException {
    Media source = requireSource(item);
    var record = requireHistoricalHistory(item, cameraEvidence);
    if (!source.hasGenerationIdentity() || !historicalAliasMatches(source)
        || !Objects.equals(store.completed(item.historyId()), item.sourceProof()))
      throw archiveMismatch();
    if (item.sourceProof() != null) {
      if (!matchesCompleted(item.sourceProof(), source)) throw archiveMismatch();
    } else if (!PhotoStorage.PRIMARY.equals(source.volume())
        || !LegacyArchiveMoveProof.revalidate(context, archive, item.archiveProof(), source.photo(), source.generationAdded(),
            source.generationModified(), record, stopped)) throw archiveMismatch();
    return source;
  }

  private PhotoMoveStore.CompletedProof historicalDestination(PhotoMoveStore.Item item, Media verified,
      String epoch) throws IOException {
    if (!eligible(verified) || !verified.hasGenerationIdentity() || verified.bytes() != item.bytes()
        || !historicalAliasMatches(verified) || !epoch.equals(LegacyArchiveMoveProof.epoch(context, verified.volume()))) throw archiveMismatch();
    return new PhotoMoveStore.CompletedProof(item.historyId(), verified.uri().toString(), verified.volume(), epoch,
        verified.generationAdded(), verified.generationModified(), verified.version(), verified.bytes(),
        item.proofSha(), item.historyFingerprint());
  }

  private void requireRelocatedHistory(PhotoMoveStore.Item item, Map<String, String> cameraEvidence) throws IOException {
    if (!item.target().equals(requireHistoricalHistory(item, cameraEvidence).getUri())) throw archiveMismatch();
  }

  private void finishHistoricalMove(PhotoMoveStore.Item item, Media verified, Map<String, String> cameraEvidence)
      throws IOException {
    requireRelocatedHistory(item, cameraEvidence);
    requireHistoricalDestination(item, verified);
    store.completeHistorical(item);
  }

  private void requireHistoricalDestination(PhotoMoveStore.Item item, Media verified) throws IOException {
    var destination = item.pendingCompletion();
    if (destination == null || !matchesCompleted(destination, verified)
        || !matchesCompleted(destination, requireOwned(verified.uri())) || !historicalAliasMatches(verified))
      throw archiveMismatch();
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
        c.getLong(8), c.getLong(9), c.getLong(10), c.getLong(11), !c.isNull(10) && !c.isNull(11),
        c.getInt(12) != 0, c.getInt(13) != 0);
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

  private String copy(Uri source, Uri target, long bytes, BooleanSupplier stopped, RunMetrics metrics)
      throws IOException {
    long started = SystemClock.elapsedRealtimeNanos();
    try (InputStream in = original(source)) {
      ParcelFileDescriptor fd = resolver.openFileDescriptor(target, "rwt");
      if (fd == null) throw new Failure("移動先に書き込めません。");
      try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
        String sha = hash(in, out, bytes, stopped);
        long syncing = SystemClock.elapsedRealtimeNanos();
        try {
          out.getFD().sync();
        } finally {
          metrics.syncNs += SystemClock.elapsedRealtimeNanos() - syncing;
        }
        return sha;
      }
    } finally {
      metrics.copyNs += SystemClock.elapsedRealtimeNanos() - started;
    }
  }

  private Media verifyTarget(Uri uri, long bytes, String sha, boolean published, BooleanSupplier stopped,
      RunMetrics metrics) throws IOException {
    long started = SystemClock.elapsedRealtimeNanos();
    try {
      Media verified = verify(uri, bytes, sha, published, stopped);
      metrics.targetVerifiedPasses++;
      metrics.targetVerifiedBytes += bytes;
      return verified;
    } finally {
      metrics.targetVerifyNs += SystemClock.elapsedRealtimeNanos() - started;
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
    InputStream in = originalOpen.open(MediaStore.setRequireOriginal(uri));
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
