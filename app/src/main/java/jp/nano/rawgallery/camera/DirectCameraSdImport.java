package jp.nano.rawgallery.camera;

import android.content.Context;
import android.content.ContentUris;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import jp.nano.rawgallery.PhotoStorage;

/** Coordinates bounded batches without staging source content on the phone. */
final class DirectCameraSdImport {
    record Result(int saved, int skipped) {}

    private static final int MAX_BATCH_FILES = 32;
    private static final long MAX_BATCH_BYTES = 64L * 1024 * 1024;
    private static final int BLOCK_BYTES = 512;
    private static final int COMPARISON_PREFIX_BYTES = 128 * 1024;

    private record Source(ExFat fs, Map<String, ExFat.Node> entries) {}
    private record FileEvidence(String sha, byte[] prefix, String tailSha) {}
    private record Candidate(ExFat.Node node, CameraImportFile file, FileEvidence evidence) {
        String sha() { return evidence.sha(); }
    }
    private record Batch(String volumeId, Map<String, ExFat.Node> entries, List<Candidate> candidates,
            Map<String, CameraImportLedger.Content> known, boolean more) {}
    private record Prepared(Candidate candidate, CameraPhotoPublisher.DirectReservation reservation) {}

    private DirectCameraSdImport() {}

    static Result run(Context app, UsbDevice device, Uri sourceTree, String targetVolume,
            Set<MediaTypes.Kind> kinds, BooleanSupplier stopped) throws IOException {
        String sourceUuid = SdSourceRegistrations.volumeUuid(sourceTree);
        if (!validUuid(sourceUuid) || !validUuid(targetVolume) || sourceUuid.equalsIgnoreCase(targetVolume)) {
            throw new IOException("取り込み元のSDと保存先のmicroSDを確認してください。");
        }
        if (!kinds.contains(MediaTypes.Kind.JPEG) && !kinds.contains(MediaTypes.Kind.RAW)) {
            throw new IOException("直接取り込みではJPEGとRAWを選んでください。");
        }
        CameraPhotoPublisher.check(stopped);
        if (!DirectUsbHandoff.settleIfRestored(app, device)) {
            throw new IOException(DirectUsbHandoff.RECOVERY);
        }
        requireWritableTarget(app, targetVolume);
        CameraImportLedger ledger = CameraImportLedger.get(app);
        CameraPhotoImporter importer = new CameraPhotoImporter(app, ledger, targetVolume);
        importer.requireLibraryPermission();
        CameraPhotoPublisher publisher = new CameraPhotoPublisher(app, ledger, targetVolume, boundary -> {});
        publisher.recover(stopped);

        var history = HistoryDb.Companion.get(app);
        var identity = MassStorageImporter.INSTANCE.identity(device);
        Map<String, List<CameraImportHistoryPlan.Entry>> legacy = new HashMap<>();
        for (var row : history.photoImportRecords(identity.getModel(), identity.getSerial())) {
            legacy.computeIfAbsent(row.key, key -> new ArrayList<>()).add(row);
        }
        String historyFolder = history.defaultPhotoFolder();
        ImportProgress.INSTANCE.begin("SD・microSD");
        boolean verifyAll = app.getSharedPreferences("camera_import", 0).getBoolean("sd_verify_all", false);
        Set<String> accounted = new HashSet<>();
        int saved = 0;
        int skipped = 0;
        String volumeId = null;
        Map<String, ExFat.Node> pinnedEntries = null;

        for (;;) {
            CameraPhotoPublisher.check(stopped);
            requireSourceGrant(app, sourceTree);
            requireWritableTarget(app, targetVolume);
            importer.requireLibraryPermission();
            String expectedVolumeId = volumeId;
            Map<String, ExFat.Node> expectedEntries = pinnedEntries;
            var destinations = verifyAll ? null : MediaSaver.INSTANCE.photoDestinationSnapshot(app);
            AtomicReference<Batch> scanned = new AtomicReference<>();
            ImportProgress.INSTANCE.photoListing(0, -1);
            DirectUsbHandoff.run(app, device, sourceUuid, targetVolume, stopped, (reader, cancelled) -> {
                Source source = source(reader, sourceUuid, expectedVolumeId, cancelled);
                requireSameListing(expectedEntries, source.entries());
                var receipts = destinations == null ? Map.<String, CameraImportLedger.Content>of()
                        : ledger.sourceSnapshot("sd-usb:" + source.fs().getVolumeId());
                scanned.set(scan(source, kinds, accounted, receipts, destinations, cancelled));
            });
            Batch batch = scanned.get();
            if (batch == null) throw new IOException("SDの写真一覧を確認できませんでした。");
            volumeId = batch.volumeId();
            pinnedEntries = batch.entries();
            ImportProgress.INSTANCE.photoListing(pinnedEntries.size(), pinnedEntries.size());
            if (!batch.known().isEmpty()) {
                var restored = MediaSaver.INSTANCE.photoDestinationSnapshot(app);
                for (var existing : batch.known().values()) {
                    CameraPhotoPublisher.check(stopped);
                    var proof = ledger.sdRevision(existing.sourceSha);
                    if (!recordedDestinationPresent(existing, restored)) {
                        throw new IOException("以前の取り込み先の状態が変わりました。");
                    }
                    boolean proofMatches = proof != null && proof.matchesContent(existing)
                            && proof.equals(SdSavedPhotoRevision.capture(app, existing, stopped));
                    if (!proofMatches && !importer.available(existing, stopped)) {
                        throw new IOException("以前の取り込み先の状態が変わりました。");
                    }
                }
                skipped += batch.known().size();
                accounted.addAll(batch.known().keySet());
            }

            List<Prepared> prepared = new ArrayList<>();
            Set<String> preparedDigests = new HashSet<>();
            List<Candidate> duplicateCandidates = new ArrayList<>();
            try {
                for (Candidate candidate : batch.candidates()) {
                    CameraPhotoPublisher.check(stopped);
                    requireWritableTarget(app, targetVolume);
                    importer.requireLibraryPermission();
                    CameraImportFile file = candidate.file();
                    CameraImportLedger.Content receipt = ledger.known(file);
                    if (receipt != null && !candidate.sha().equals(receipt.sourceSha)) {
                        throw new CameraPhotoImporter.SavedPhotoConflict("取り込み履歴とSDの写真の内容が異なります。");
                    }
                    CameraImportLedger.Content identical = ledger.find(candidate.sha(), file.bytes);
                    if (identical != null && importer.available(identical, stopped)) {
                        ledger.bind(file, identical);
                        skipped++;
                        continue;
                    }

                    String kind = MediaTypes.INSTANCE.kindOf(file.name).name();
                    String group = ledger.captureGroup(file, candidate.sha(), kind);
                    String historyKey = CameraImportHistoryPlan.key(file.name, file.bytes, file.captureSeconds);
                    var adopted = legacyMatch(app, file, candidate.evidence(), legacy.get(historyKey), stopped);
                    if (adopted != null) {
                        importer.requireLibraryPermission();
                        if (identical != null && CameraPhotoPublisher.savedPhotoExists(app, Uri.parse(identical.uri)))
                            throw legacyConflict();
                        ledger.adopt(file, candidate.sha(), adopted.getSha256(), adopted.getBytes(),
                                adopted.getUri(), kind, group, identical);
                        skipped++;
                        continue;
                    }
                    if (!preparedDigests.add(candidate.sha())) {
                        duplicateCandidates.add(candidate);
                        continue;
                    }
                    String folder = CameraPhotoPublisher.folder(historyFolder, kind.equals("RAW"));
                    var reservation = publisher.reserveDirect(file, candidate.sha(), candidate.sha(),
                            file.bytes, kind, folder, group, stopped, identical);
                    prepared.add(new Prepared(candidate, reservation));
                }

                if (!prepared.isEmpty()) {
                    ImportProgress.INSTANCE.photoSaving(saved);
                    String pinnedVolumeId = volumeId;
                    Map<String, ExFat.Node> listing = pinnedEntries;
                    requireSourceGrant(app, sourceTree);
                    DirectUsbHandoff.run(app, device, sourceUuid, targetVolume, stopped, (reader, cancelled) -> {
                        Source source = source(reader, sourceUuid, pinnedVolumeId, cancelled);
                        requireSameListing(listing, source.entries());
                        for (Prepared item : prepared) transfer(reader, source, targetVolume, item, cancelled);
                    });
                    for (Prepared item : prepared) {
                        CameraPhotoPublisher.check(stopped);
                        CameraPhotoPublisher.CommitCheck beforeCommit = () -> {
                            requireSourceGrant(app, sourceTree);
                            requireWritableTarget(app, targetVolume);
                            importer.requireLibraryPermission();
                        };
                        publisher.finishDirect(item.reservation(), stopped, beforeCommit);
                        saved++;
                        ImportProgress.INSTANCE.photoSaving(saved);
                    }
                }

                // Same-content entries seen within a batch acquire their own source receipts
                // only after the first reservation for those bytes has been published.
                for (Candidate candidate : duplicateCandidates) {
                    CameraPhotoPublisher.check(stopped);
                    importer.requireLibraryPermission();
                    CameraImportLedger.Content published = ledger.find(candidate.sha(), candidate.file().bytes);
                    if (published == null) throw new IOException("同じ写真の保存履歴を確認できませんでした。");
                    ledger.bind(candidate.file(), published);
                    skipped++;
                }
                for (Candidate candidate : batch.candidates()) {
                    accounted.add(candidate.node().getRelativePath());
                }
            } catch (IOException | RuntimeException failure) {
                abandon(app, publisher, targetVolume, prepared, failure);
                throw failure;
            }

            if (!batch.more()) return new Result(saved, skipped);
        }
    }

    private static void requireSourceGrant(Context app, Uri tree) throws IOException {
        if (!SdSourceRegistrations.directDcimAuthorized(app, tree)) {
            throw new IOException("取り込み元SDのDCIMフォルダを選び直してください。");
        }
    }

    /** A mounted destination may still be an unready journal from an unfinished lease. */
    private static void requireWritableTarget(Context app, String target) throws IOException {
        if (DirectUsbHandoff.recoveryPending(app)) throw new IOException(DirectUsbHandoff.RECOVERY);
        if (!target.equals(PhotoStorage.selected(app))) throw new IOException("写真の保存先が変わりました。");
        PhotoStorage.requireImportReady(app);
        PhotoStorage.requireWritable(app, target);
    }

    private static Source source(DirectUsbTransport reader, String uuid, String expectedVolumeId,
            BooleanSupplier stopped) throws IOException {
        BlockDevice disk = cancellable(reader.readView(0), stopped);
        try {
            ExFat fs = openSource(disk, reader.sectors(0), uuid);
            if (expectedVolumeId != null && !expectedVolumeId.equals(fs.getVolumeId())) {
                throw new IOException("取り込み元のSDが変わりました。");
            }
            Map<String, ExFat.Node> nodes = new LinkedHashMap<>();
            Set<String> paths = new HashSet<>();
            for (ExFat.Node node : fs.listDirectMediaUnderDcim(stopped)) {
                String path = node.getRelativePath();
                if (!paths.add(path.toUpperCase(Locale.ROOT))) {
                    throw new IOException("SDに同じ名前の写真が複数あります。");
                }
                nodes.put(path, node);
            }
            return new Source(fs, nodes);
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        } catch (IllegalArgumentException unrecognized) {
            throw new IOException("取り込み元のexFATを確認できませんでした。", unrecognized);
        }
    }

    private static BlockDevice cancellable(BlockDevice disk, BooleanSupplier stopped) {
        return new BlockDevice() {
            @Override public int getBlockSize() { return disk.getBlockSize(); }
            @Override public void readBlocks(long lba, byte[] data, int offset, int count) {
                try {
                    CameraPhotoPublisher.check(stopped);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
                disk.readBlocks(lba, data, offset, count);
            }
        };
    }

    private static void requireSameListing(Map<String, ExFat.Node> expected, Map<String, ExFat.Node> actual)
            throws IOException {
        if (expected != null && !expected.equals(actual)) {
            throw new IOException("SDの写真一覧が確認時から変わりました。");
        }
    }

    private static Batch scan(Source source, Set<MediaTypes.Kind> kinds, Set<String> accounted,
            Map<String, CameraImportLedger.Content> receipts,
            Map<String, CameraImportHistoryPlan.Destination> destinations, BooleanSupplier stopped) throws IOException {
        List<Candidate> candidates = new ArrayList<>();
        Map<String, CameraImportLedger.Content> known = new HashMap<>();
        long bytes = 0;
        boolean more = false;
        String volumeId = source.fs().getVolumeId();
        for (ExFat.Node node : source.entries().values()) {
            CameraPhotoPublisher.check(stopped);
            MediaTypes.Kind kind = MediaTypes.INSTANCE.kindOf(node.getName());
            if (kind == MediaTypes.Kind.VIDEO || !kinds.contains(kind)
                    || accounted.contains(node.getRelativePath())) continue;
            CameraImportFile file;
            try {
                long modified = node.getModifiedEpochSec();
                file = new CameraImportFile("sd-usb:" + volumeId, node.getRelativePath(), node.getLength(),
                        Long.toString(Math.multiplyExact(modified, 1000)), modified);
            } catch (IllegalArgumentException | ArithmeticException invalid) {
                throw new IOException("SDの写真の名前、日時、またはサイズに対応していません。", invalid);
            }
            CameraImportLedger.Content receipt = receipts.get(file.sourceKey());
            if (file.captureSeconds > 0 && receipt != null && receipt.sourceBytes == file.bytes
                    && recordedDestinationPresent(receipt, destinations)) {
                known.put(node.getRelativePath(), receipt);
                continue;
            }
            if (candidates.size() >= MAX_BATCH_FILES
                    || !candidates.isEmpty() && file.bytes > MAX_BATCH_BYTES - bytes) {
                more = true;
                break;
            }
            try (InputStream input = source.fs().openForDirectTransfer(node)) {
                candidates.add(new Candidate(node, file, evidence(input, file.bytes, stopped)));
            } catch (UncheckedIOException failure) {
                throw failure.getCause();
            }
            bytes += file.bytes;
        }
        return new Batch(volumeId, source.entries(), candidates, known, more);
    }

    private static boolean recordedDestinationPresent(CameraImportLedger.Content content,
            Map<String, CameraImportHistoryPlan.Destination> destinations) {
        if (destinations == null || !CameraImportHistoryPlan.validUri(content.uri)) return false;
        CameraImportHistoryPlan.Destination row = destinations.get(content.uri);
        return row != null && row.published && row.bytes == content.storedBytes;
    }

    /** Compare legacy candidates without staging source or destination bytes. */
    private static CameraImportLegacy.Evidence legacyMatch(Context app, CameraImportFile file, FileEvidence source,
            List<CameraImportHistoryPlan.Entry> history, BooleanSupplier stopped) throws IOException {
        Set<Uri> candidates = new LinkedHashSet<>();
        Set<Uri> recorded = new HashSet<>();
        if (history != null) {
            for (var row : history) {
                if (row.deleted) throw legacyConflict();
                if (CameraImportHistoryPlan.validUri(row.uri)) recorded.add(Uri.parse(row.uri));
                else if (!"existing".equals(row.uri)) throw legacyConflict();
            }
            if (recorded.size() != 1) throw legacyConflict();
            candidates.addAll(recorded);
        }
        candidates.addAll(CameraDocumentSource.boundedMetadata(stopped, signal -> {
            List<Uri> found = new ArrayList<>();
            var collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String id = MediaStore.MediaColumns._ID;
            String volumeName = MediaStore.MediaColumns.VOLUME_NAME;
            try (var rows = app.getContentResolver().query(collection, new String[]{id, volumeName},
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND "
                            + MediaStore.MediaColumns.SIZE + "=? AND " + MediaStore.MediaColumns.IS_PENDING + "=0",
                    new String[]{"Pictures/CameraSync/%", Long.toString(file.bytes)}, null, signal)) {
                if (rows == null || rows.getCount() > 2048)
                    throw new IOException("以前の保存先を照合できませんでした。");
                int expected = rows.getCount();
                while (rows.moveToNext()) {
                    String volume = rows.getString(1);
                    if (volume == null) throw new IOException("以前の保存先を照合できませんでした。");
                    found.add(ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volume), rows.getLong(0)));
                }
                if (found.size() != expected) throw new IOException("以前の保存先を照合できませんでした。");
            }
            return found;
        }));
        CameraImportLegacy.Evidence match = null;
        for (Uri uri : candidates) {
            CameraPhotoPublisher.check(stopped);
            var before = CameraPhotoPublisher.savedRevision(app, uri, stopped);
            if (before == null || before.size() != file.bytes) {
                if (recorded.contains(uri)) throw legacyConflict();
                continue;
            }
            FileEvidence saved;
            try (InputStream input = app.getContentResolver().openInputStream(MediaStore.setRequireOriginal(uri))) {
                if (input == null) throw new IOException("以前の写真を照合できませんでした。");
                saved = evidence(input, file.bytes, stopped);
            }
            if (!before.equals(CameraPhotoPublisher.savedRevision(app, uri, stopped))) throw legacyConflict();
            boolean matches = source.sha().equals(saved.sha()) || source.tailSha().equals(saved.tailSha())
                    && ExifOffsetFix.sameAfterOffsetRepair(source.prefix(), saved.prefix());
            if (!matches && recorded.contains(uri)) throw legacyConflict();
            if (matches && match == null) match = new CameraImportLegacy.Evidence(uri.toString(), saved.sha(), file.bytes);
        }
        return match;
    }

    private static CameraPhotoImporter.SavedPhotoConflict legacyConflict() {
        return new CameraPhotoImporter.SavedPhotoConflict("以前の取り込み先と照合できません。保存先を確認してください。");
    }

    private static void transfer(DirectUsbTransport reader, Source source, String targetUuid, Prepared item,
            BooleanSupplier stopped) throws IOException {
        CameraPhotoPublisher.check(stopped);
        Candidate candidate = item.candidate();
        ExFat.Node node = source.entries().get(candidate.node().getRelativePath());
        if (!candidate.node().equals(node)) throw new IOException("SDの写真が確認時から変わりました。");
        var reservation = item.reservation();
        try (InputStream input = source.fs().openForDirectTransfer(node)) {
            var extent = DirectExFatReservation.locate(cancellable(reader.readView(1), stopped), reader.sectors(1), targetUuid,
                    reservation.path(), reservation.bytes());
            DirectPhotoTransfer.copy(reader, input, extent, reservation.bytes(), reservation.marker(),
                    candidate.sha(), stopped, bytes -> {});
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private static void abandon(Context app, CameraPhotoPublisher publisher, String target, List<Prepared> prepared,
            Throwable failure) {
        if (prepared.isEmpty() || DirectUsbHandoff.recoveryPending(app)) return;
        try {
            PhotoStorage.requireWritable(app, target);
        } catch (IOException | RuntimeException unmounted) {
            failure.addSuppressed(unmounted);
            return;
        }
        for (Prepared item : prepared) {
            try {
                publisher.abandonDirect(item.reservation().token());
            } catch (IOException | RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
    }

    private static boolean validUuid(String uuid) {
        return uuid != null && uuid.matches("[0-9a-fA-F]{4}-[0-9a-fA-F]{4}") && !uuid.equals("0000-0000");
    }

    static ExFat openSource(BlockDevice disk, long diskSectors, String uuid) throws IOException {
        if (!validUuid(uuid) || disk.getBlockSize() != BLOCK_BYTES || diskSectors < 24) {
            throw new IOException("SDのセクターサイズを確認できませんでした。");
        }
        byte[] boot = new byte[BLOCK_BYTES];
        disk.readBlocks(0, boot, 0, 1);
        if (!bootSignature(boot)) throw new IOException("SDのパーティションを確認できませんでした。");
        long partition = 0;
        long partitionSectors = diskSectors;
        ByteBuffer header = ByteBuffer.wrap(boot).order(ByteOrder.LITTLE_ENDIAN);
        if (!exFatSignature(boot)) {
            for (int entry = 446; entry < 510; entry += 16) {
                int nonzero = 0;
                for (int i = entry; i < entry + 16; i++) nonzero |= boot[i];
                if (nonzero == 0) continue;
                if (partition != 0 || boot[entry + 4] != 7
                        || (boot[entry] != 0 && boot[entry] != (byte) 0x80)) {
                    throw new IOException("SDのパーティション構成に対応していません。");
                }
                partition = Integer.toUnsignedLong(header.getInt(entry + 8));
                partitionSectors = Integer.toUnsignedLong(header.getInt(entry + 12));
                if (partition == 0 || partition >= diskSectors || partitionSectors < 24
                        || partitionSectors > diskSectors - partition) {
                    throw new IOException("SDのパーティション範囲を確認できませんでした。");
                }
            }
            if (partition == 0) throw new IOException("SDにexFATのパーティションがありません。");
            disk.readBlocks(partition, boot, 0, 1);
        }
        long serial = Long.parseLong(uuid.replace("-", ""), 16);
        long volumeLength = header.getLong(72);
        int sectorShift = Byte.toUnsignedInt(boot[108]);
        if (!bootSignature(boot) || !exFatSignature(boot)
                || Integer.toUnsignedLong(header.getInt(100)) != serial
                || sectorShift < 9 || sectorShift > 12 || volumeLength < 24
                || volumeLength > (partitionSectors >> (sectorShift - 9))) {
            throw new IOException("選択したSDと読み取ったexFATが一致しません。");
        }
        ExFat fs = new ExFat(disk, partition);
        fs.mount();
        if (fs.getVolumeSerial() != serial) throw new IOException("取り込み元のSDが変わりました。");
        return fs;
    }

    private static boolean bootSignature(byte[] bytes) {
        return bytes[510] == 0x55 && bytes[511] == (byte) 0xaa;
    }

    private static boolean exFatSignature(byte[] bytes) {
        return "EXFAT   ".equals(new String(bytes, 3, 8, StandardCharsets.US_ASCII));
    }

    private static FileEvidence evidence(InputStream input, long expectedBytes, BooleanSupplier stopped) throws IOException {
        MessageDigest digest = sha256();
        MessageDigest tail = sha256();
        byte[] prefix = new byte[(int) Math.min(expectedBytes, COMPARISON_PREFIX_BYTES)];
        byte[] window = new byte[DirectPhotoTransfer.WINDOW];
        long bytes = 0;
        for (;;) {
            CameraPhotoPublisher.check(stopped);
            int read = input.read(window);
            if (read == -1) break;
            if (read <= 0 || read > expectedBytes - bytes) {
                throw new IOException("照合中に写真のサイズが変わりました。");
            }
            digest.update(window, 0, read);
            int prefixBytes = (int) Math.min(read, Math.max(0, prefix.length - bytes));
            if (prefixBytes > 0) System.arraycopy(window, 0, prefix, (int) bytes, prefixBytes);
            if (prefixBytes < read) tail.update(window, prefixBytes, read - prefixBytes);
            bytes += read;
        }
        if (bytes != expectedBytes) throw new IOException("写真を最後まで読み出せませんでした。");
        return new FileEvidence(CameraImportFile.hex(digest.digest()), prefix, CameraImportFile.hex(tail.digest()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
