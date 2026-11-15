package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import jp.nano.rawgallery.camera.CameraDocumentSource;
import jp.nano.rawgallery.camera.CameraImportFile;
import jp.nano.rawgallery.camera.CameraImportGate;
import jp.nano.rawgallery.camera.CameraImportLedger;
import jp.nano.rawgallery.camera.CameraPhotoImporter;
import jp.nano.rawgallery.camera.CameraPhotoPublisher;
import jp.nano.rawgallery.camera.CameraSourceIdentity;
import jp.nano.rawgallery.camera.CameraWifiNetwork;
import jp.nano.rawgallery.camera.Ep7WifiClient;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Small, reversible Wi-Fi/SD pilot. All state is namespaced by a validated run UUID. */
final class CameraImportPilotChecks {
    private static final String INDEX_PREFS = "camera_import_pilot";
    private static final String RUN_PREFS_PREFIX = "camera_import_pilot_";
    private static final String RUN_ID_KEY = "run_id";
    private static final String DATABASE_PREFIX = "camera-import-pilot-";
    private static final String FOLDER_PREFIX = "LoupePilot-";
    private static final long MAX_PILOT_BYTES = 256L * 1024L * 1024L;
    private static final int JPEG_COUNT = 2;
    private static final int RAW_COUNT = 2;
    private static final int MAX_FILES = 4;
    private static final String[] OWNED_MEDIA_FIELDS = {
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH
    };

    private CameraImportPilotChecks() { }

    static String runWifi(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        if (!CameraImportGate.lock.tryLock()) throw new IOException("Another camera import is active");
        PilotSession session = null;
        boolean success = false;
        try {
            cleanup(instrumentation);
            session = createSession(context);
            ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
            if (connectivity == null) throw new IOException("Wi-Fi network service is unavailable");
            Network network = CameraWifiNetwork.select(connectivity);
            if (network == null) throw new IOException("No unambiguous camera Wi-Fi network is available");
            String identity = CameraSourceIdentity.wifi(context, network);
            boolean stable = identity != null;
            String sourceId = stable ? identity : "wifi-session:" + network.getNetworkHandle();
            Ep7WifiClient client = new Ep7WifiClient(
                    url -> (HttpURLConnection) network.openConnection(url, Proxy.NO_PROXY), () -> false);

            long listStart = System.nanoTime();
            Ep7WifiClient.Listing listing = client.list();
            requireNetwork(context, connectivity, network, identity);
            List<Ep7WifiClient.Entry> selected = selectWifi(listing.photos);
            long selectedBytes = totalWifiBytes(selected);
            double listSeconds = elapsed(listStart);
            CameraImportLedger ledger = new CameraImportLedger(context, session.databaseName);
            try {
                CameraPhotoImporter importer = new CameraPhotoImporter(context, ledger);
                importer.recover(() -> false);
                long contentsBefore = ledger.contentCount();
                long receiptsBefore = ledger.receiptCount();
                long firstStart = System.nanoTime();
                long firstBytes = 0;
                int firstPublished = 0;
                List<String> firstUris = new ArrayList<>(selected.size());
                List<String> firstSourceShas = new ArrayList<>(selected.size());
                List<CameraImportFile> sources = new ArrayList<>(selected.size());
                for (Ep7WifiClient.Entry entry : selected) {
                    requireNetwork(context, connectivity, network, identity);
                    CameraImportFile source = wifiSource(sourceId, entry);
                    sources.add(source);
                    CameraPhotoImporter.Result result = importer.importPhoto(source,
                            out -> copyWifi(context, connectivity, network, identity, client, entry, out),
                            session.folderName, stable, false, () -> false);
                    require(result.contentChecked && result.sourceBytesRead == source.bytes,
                            "Wi-Fi first pass did not read and verify the selected source");
                    firstBytes = Math.addExact(firstBytes, result.sourceBytesRead);
                    if (result.imported) firstPublished++;
                    CameraImportLedger.Content content = ledger.known(source);
                    require(content != null && content.uri.equals(result.uri.toString()) &&
                                    content.sourceBytes == source.bytes,
                            "Wi-Fi first pass did not retain a verified receipt");
                    firstSourceShas.add(content.sourceSha);
                    firstUris.add(result.uri.toString());
                }
                double firstSeconds = elapsed(firstStart);
                require(firstBytes == selectedBytes, "Wi-Fi first pass byte total changed");

                long historyStart = System.nanoTime();
                long historyBytes = 0;
                for (int i = 0; i < selected.size(); i++) {
                    Ep7WifiClient.Entry entry = selected.get(i);
                    CameraImportFile source = sources.get(i);
                    requireNetwork(context, connectivity, network, identity);
                    final int[] copies = {0};
                    CameraPhotoImporter.Result result = importer.importPhoto(source, out -> {
                        copies[0]++;
                        copyWifi(context, connectivity, network, identity, client, entry, out);
                    }, session.folderName, stable, false, () -> false);
                    require(!result.imported && result.uri.toString().equals(firstUris.get(i)),
                            "Wi-Fi history pass created a new publication");
                    if (stable) {
                        require(copies[0] == 0 && result.sourceBytesRead == 0 && !result.contentChecked,
                                "Stable Wi-Fi history lookup unexpectedly transferred source bytes");
                    } else {
                        require(copies[0] == 1 && result.sourceBytesRead == source.bytes && result.contentChecked,
                                "Unstable Wi-Fi history fallback did not verify the complete source");
                    }
                    historyBytes = Math.addExact(historyBytes, result.sourceBytesRead);
                }
                double historySeconds = elapsed(historyStart);

                long strictStart = System.nanoTime();
                long strictBytes = 0;
                for (int i = 0; i < selected.size(); i++) {
                    Ep7WifiClient.Entry entry = selected.get(i);
                    CameraImportFile source = sources.get(i);
                    requireNetwork(context, connectivity, network, identity);
                    CameraPhotoImporter.Result result = importer.importPhoto(source,
                            out -> copyWifi(context, connectivity, network, identity, client, entry, out),
                            session.folderName, stable, true, () -> false);
                    require(!result.imported && result.contentChecked && result.sourceBytesRead == source.bytes &&
                                    result.uri.toString().equals(firstUris.get(i)),
                            "Wi-Fi strict pass did not verify the existing publication");
                    strictBytes = Math.addExact(strictBytes, result.sourceBytesRead);
                }
                double strictSeconds = elapsed(strictStart);
                require(ledger.pending().isEmpty(), "Wi-Fi pilot left pending publications");
                long newContents = ledger.contentCount() - contentsBefore;
                long newReceipts = ledger.receiptCount() - receiptsBefore;
                saveWifiResults(session, stable, sources, firstSourceShas, firstUris);
                success = true;
                return "PASS pilot-wifi" +
                        " stable=" + stable +
                        " listFiles=" + listing.photos.size() +
                        " selectedFiles=" + selected.size() +
                        " bytes=" + selectedBytes +
                        " listSeconds=" + metric(listSeconds) +
                        " firstSeconds=" + metric(firstSeconds) +
                        " firstBytes=" + firstBytes +
                        " firstPublished=" + firstPublished +
                        " newContents=" + newContents +
                        " newReceipts=" + newReceipts +
                        " historySeconds=" + metric(historySeconds) +
                        " historyBytes=" + historyBytes +
                        " strictSeconds=" + metric(strictSeconds) +
                        " strictBytes=" + strictBytes;
            } finally {
                ledger.close();
            }
        } finally {
            try {
                if (!success && session != null) cleanupSession(context, session);
            } finally {
                CameraImportGate.lock.unlock();
            }
        }
    }

    static String runSd(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        if (!CameraImportGate.lock.tryLock()) throw new IOException("Another camera import is active");
        PilotSession session = null;
        boolean success = false;
        try {
            session = loadCurrentSession(context);
            if (session == null || !context.getDatabasePath(session.databaseName).exists()) {
                throw new IOException("Wi-Fi pilot baseline is missing; run Wi-Fi pilot first");
            }
            List<WifiProof> wifiProof = loadWifiProof(session);
            if (wifiProof.size() != MAX_FILES) throw new IOException("Wi-Fi pilot proof is incomplete");
            String treeValue = context.getSharedPreferences("camera_import", Context.MODE_PRIVATE)
                    .getString("sd_tree_uri", null);
            if (treeValue == null) throw new IOException("No saved SD card tree is available");
            Uri tree = Uri.parse(treeValue);
            if (!DocumentsContract.isTreeUri(tree)) throw new IOException("Saved SD card tree is invalid");

            CameraImportLedger ledger = new CameraImportLedger(context, session.databaseName);
            try {
                CameraPhotoImporter importer = new CameraPhotoImporter(context, ledger);
                importer.recover(() -> false);
                long scanStart = System.nanoTime();
                CameraDocumentSource.Listing listing = CameraDocumentSource.list(context, tree, () -> false);
                boolean crossRoute = true;
                List<CameraDocumentSource.Entry> selected = selectSdForWifi(listing.photos, wifiProof);
                long selectedBytes = totalSdBytes(selected);
                double scanSeconds = elapsed(scanStart);
                long contentsBefore = ledger.contentCount();
                long receiptsBefore = ledger.receiptCount();
                List<String> firstUris = new ArrayList<>(selected.size());
                long firstStart = System.nanoTime();
                long firstBytes = 0;
                int firstPublished = 0;
                int crossMatches = 0;
                for (int i = 0; i < selected.size(); i++) {
                    CameraDocumentSource.Entry entry = selected.get(i);
                    WifiProof expectedProof = wifiProof.get(i);
                    CameraPhotoImporter.Result result = importer.importPhoto(entry.file,
                            out -> copySdChecked(context, entry, out, expectedProof.sourceSha),
                            session.folderName, listing.stable, true, () -> false);
                    require(result.contentChecked && result.sourceBytesRead == entry.file.bytes,
                            "SD first pass did not read and verify the selected source");
                    firstBytes = Math.addExact(firstBytes, result.sourceBytesRead);
                    if (result.imported) firstPublished++;
                    if (crossRoute) {
                        WifiProof proof = wifiProof.get(i);
                        require(!result.imported && result.uri.toString().equals(proof.uri),
                                "SD source content did not match the Wi-Fi publication");
                        CameraImportLedger.Content known = ledger.known(entry.file);
                        require(known != null && known.sourceSha.equals(proof.sourceSha) &&
                                        known.uri.equals(proof.uri),
                                "SD receipt did not bind to the Wi-Fi original");
                        crossMatches++;
                    }
                    firstUris.add(result.uri.toString());
                }
                double firstSeconds = elapsed(firstStart);
                require(firstBytes == selectedBytes, "SD first pass byte total changed");
                long newContents = ledger.contentCount() - contentsBefore;
                long newReceipts = ledger.receiptCount() - receiptsBefore;
                if (crossRoute && (crossMatches != MAX_FILES || newContents != 0 || firstPublished != 0)) {
                    throw new IOException("Cross-route pilot did not verify all Wi-Fi originals");
                }

                long historyStart = System.nanoTime();
                long historyBytes = 0;
                for (int i = 0; i < selected.size(); i++) {
                    CameraDocumentSource.Entry entry = selected.get(i);
                    final int[] copies = {0};
                    WifiProof expectedProof = wifiProof.get(i);
                    CameraPhotoImporter.Result result = importer.importPhoto(entry.file, out -> {
                        copies[0]++;
                        copySdChecked(context, entry, out, expectedProof.sourceSha);
                    }, session.folderName, listing.stable, false, () -> false);
                    require(!result.imported && result.uri.toString().equals(firstUris.get(i)),
                            "SD history pass created a new publication");
                    if(listing.stable)require(copies[0]==0&&result.sourceBytesRead==0&&!result.contentChecked,"Stable SD history pass reread the source");
                    else require(copies[0]==1&&result.sourceBytesRead==entry.file.bytes&&result.contentChecked,"Unstable SD history fallback did not verify source content");
                    if (crossRoute) {
                        WifiProof proof = wifiProof.get(i);
                        CameraImportLedger.Content known = ledger.known(entry.file);
                        require(result.uri.toString().equals(proof.uri) && known != null &&
                                        known.sourceSha.equals(proof.sourceSha) && known.uri.equals(proof.uri),
                                "SD history pass did not retain the Wi-Fi content binding");
                    }
                    historyBytes = Math.addExact(historyBytes, result.sourceBytesRead);
                }
                double historySeconds = elapsed(historyStart);
                require(ledger.pending().isEmpty(), "SD pilot left pending publications");
                require(ledger.contentCount() == contentsBefore + newContents &&
                                ledger.receiptCount() == receiptsBefore + newReceipts,
                        "SD history pass changed content or receipt counts");
                success = true;
                return "PASS pilot-sd" +
                        " crossRouteVerified=" + crossRoute +
                        " stable=" + listing.stable +
                        " selectedFiles=" + selected.size() +
                        " bytes=" + selectedBytes +
                        " scanSeconds=" + metric(scanSeconds) +
                        " firstSeconds=" + metric(firstSeconds) +
                        " firstBytes=" + firstBytes +
                        " firstPublished=" + firstPublished +
                        " crossMatches=" + crossMatches +
                        " newContents=" + newContents +
                        " newReceipts=" + newReceipts +
                        " historySeconds=" + metric(historySeconds) +
                        " historyBytes=" + historyBytes;
            } finally {
                ledger.close();
            }
        } finally {
            try {
                if (success && session != null) cleanupSession(context, session);
            } finally {
                CameraImportGate.lock.unlock();
            }
        }
    }

    // A mismatch throws before importPhoto can publish or commit a source receipt.
    private static void copySdChecked(Context context, CameraDocumentSource.Entry entry,
            java.io.OutputStream output, String expectedSha) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            java.security.DigestOutputStream checked = new java.security.DigestOutputStream(output, digest);
            CameraDocumentSource.copy(context, entry, checked, () -> false);
            StringBuilder actual = new StringBuilder();
            for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 255));
            require(expectedSha.equals(actual.toString()), "SD source SHA does not match Wi-Fi proof");
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static String runSdGuardChecks(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        require(loadCurrentSession(context) == null, "Guard check requires no active pilot");
        try { runSd(instrumentation); throw new AssertionError("Missing baseline was accepted"); }
        catch (IOException expected) { require(loadCurrentSession(context) == null, "Missing baseline created a run"); }
        PilotSession session = createSession(context);
        try {
            try (CameraImportLedger ledger = new CameraImportLedger(context, session.databaseName)) {
                ledger.getWritableDatabase();
            }
            require(context.getDatabasePath(session.databaseName).exists(), "Guard baseline database was not created");
            try { runSd(instrumentation); throw new AssertionError("Incomplete proof was accepted"); }
            catch (IOException expected) {
                PilotSession retained = loadCurrentSession(context);
                require(retained != null && retained.runId.equals(session.runId) &&
                        context.getDatabasePath(session.databaseName).exists(), "Failed SD erased baseline");
            }
            return "PASS pilot-sd-guards missingBaselineRejected=true failedProofPreserved=true";
        } finally { cleanupSession(context, session); }
    }

    static String cleanup(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        if (!CameraImportGate.lock.tryLock()) throw new IOException("Another camera import is active");
        try {
            PilotSession session = loadCurrentSession(context);
            if (session == null) return "PASS pilot-cleanup no-owned-run";
            int deleted = cleanupSession(context, session);
            return "PASS pilot-cleanup deletedOwnedRows=" + deleted;
        } finally {
            CameraImportGate.lock.unlock();
        }
    }

    private static PilotSession createSession(Context context) throws IOException {
        String runId = UUID.randomUUID().toString();
        String databaseName = DATABASE_PREFIX + runId + ".db";
        String folderName = FOLDER_PREFIX + runId;
        String prefsName = RUN_PREFS_PREFIX + runId;
        SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        if (!prefs.edit().clear().putString(RUN_ID_KEY, runId)
                .putString("database_name", databaseName).putString("folder_name", folderName).commit()) {
            context.deleteSharedPreferences(prefsName);
            throw new IOException("Could not create pilot preferences");
        }
        SharedPreferences index = context.getSharedPreferences(INDEX_PREFS, Context.MODE_PRIVATE);
        if (!index.edit().putString(RUN_ID_KEY, runId).commit()) {
            context.deleteSharedPreferences(prefsName);
            throw new IOException("Could not register pilot run");
        }
        return new PilotSession(runId, databaseName, folderName, prefsName, prefs);
    }

    private static PilotSession loadCurrentSession(Context context) throws IOException {
        String runId = context.getSharedPreferences(INDEX_PREFS, Context.MODE_PRIVATE).getString(RUN_ID_KEY, null);
        if (runId == null) return null;
        if (!validRunId(runId)) throw new IOException("Invalid pilot run identifier");
        String databaseName = DATABASE_PREFIX + runId + ".db";
        String folderName = FOLDER_PREFIX + runId;
        String prefsName = RUN_PREFS_PREFIX + runId;
        SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        if (!runId.equals(prefs.getString(RUN_ID_KEY, null)) ||
                !databaseName.equals(prefs.getString("database_name", null)) ||
                !folderName.equals(prefs.getString("folder_name", null))) {
            throw new IOException("Pilot run ownership metadata does not match its UUID");
        }
        return new PilotSession(runId, databaseName, folderName, prefsName, prefs);
    }

    private static void saveWifiResults(PilotSession session, boolean stable,
            List<CameraImportFile> sources, List<String> sourceShas, List<String> uris) throws IOException {
        if (uris.size() != MAX_FILES || sources.size() != MAX_FILES || sourceShas.size() != MAX_FILES) {
            throw new IOException("Invalid Wi-Fi pilot result set");
        }
        SharedPreferences.Editor editor = session.prefs.edit().putBoolean("wifi_complete", true)
                .putBoolean("wifi_stable", stable).putInt("wifi_count", uris.size());
        for (int i = 0; i < uris.size(); i++) {
            if (!validSha(sourceShas.get(i))) throw new IOException("Invalid Wi-Fi pilot fingerprint");
            editor.putString("wifi_path_" + i, sources.get(i).relativePath);
            editor.putLong("wifi_bytes_" + i, sources.get(i).bytes);
            editor.putString("wifi_sha_" + i, sourceShas.get(i));
            editor.putString("wifi_uri_" + i, uris.get(i));
        }
        if (!editor.commit()) throw new IOException("Could not save Wi-Fi pilot results");
    }

    private static List<WifiProof> loadWifiProof(PilotSession session) throws IOException {
        if (!session.prefs.getBoolean("wifi_complete", false)) return new ArrayList<>();
        int count = session.prefs.getInt("wifi_count", -1);
        if (count != MAX_FILES) throw new IOException("Complete Wi-Fi pilot proof is missing selected files");
        List<WifiProof> proof = new ArrayList<>(count);
        Set<String> paths = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String path = session.prefs.getString("wifi_path_" + i, null);
            long bytes = session.prefs.getLong("wifi_bytes_" + i, 0);
            String sha = session.prefs.getString("wifi_sha_" + i, null);
            String value = session.prefs.getString("wifi_uri_" + i, null);
            if (path == null || bytes <= 0 || bytes > MAX_PILOT_BYTES || !validSha(sha) || value == null) {
                throw new IOException("Wi-Fi pilot content proof is incomplete");
            }
            try {
                new CameraImportFile("pilot-proof", path, bytes, "", 0);
            } catch (IllegalArgumentException invalidPath) {
                throw new IOException("Wi-Fi pilot path proof is invalid", invalidPath);
            }
            if (!paths.add(path.toUpperCase(Locale.ROOT))) throw new IOException("Duplicate Wi-Fi pilot path proof");
            Uri uri = Uri.parse(value);
            if (!"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority()) ||
                    uri.getPath() == null || !uri.getPath().matches("/(?:external|external_primary)/images/media/[0-9]+")) {
                throw new IOException("Wi-Fi pilot URI is invalid");
            }
            proof.add(new WifiProof(path, bytes, sha, value));
        }
        return proof;
    }

    private static boolean validSha(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static int cleanupSession(Context context, PilotSession session) throws IOException {
        validateSession(session);
        String currentRun = context.getSharedPreferences(INDEX_PREFS, Context.MODE_PRIVATE).getString(RUN_ID_KEY, null);
        if (!session.runId.equals(currentRun)) throw new IOException("Refusing to clean a non-current pilot run");
        int deleted = cleanOwnedFolder(context, CameraPhotoPublisher.folder(session.folderName, false));
        deleted += cleanOwnedFolder(context, CameraPhotoPublisher.folder(session.folderName, true));

        java.io.File database = context.getDatabasePath(session.databaseName);
        if (database.exists() && !context.deleteDatabase(session.databaseName)) {
            throw new IOException("Could not delete isolated pilot database");
        }
        if (!context.deleteSharedPreferences(session.prefsName)) {
            throw new IOException("Could not delete isolated pilot preferences");
        }
        SharedPreferences index = context.getSharedPreferences(INDEX_PREFS, Context.MODE_PRIVATE);
        if (!session.runId.equals(index.getString(RUN_ID_KEY, null)) ||
                !index.edit().clear().commit() || !context.deleteSharedPreferences(INDEX_PREFS)) {
            throw new IOException("Could not clear isolated pilot index");
        }
        return deleted;
    }

    private static void validateSession(PilotSession session) throws IOException {
        if (!validRunId(session.runId) ||
                !session.databaseName.equals(DATABASE_PREFIX + session.runId + ".db") ||
                !session.folderName.equals(FOLDER_PREFIX + session.runId) ||
                !session.prefsName.equals(RUN_PREFS_PREFIX + session.runId)) {
            throw new IOException("Refusing cleanup without a validated pilot UUID prefix");
        }
        if (!session.runId.equals(session.prefs.getString(RUN_ID_KEY, null)) ||
                !session.databaseName.equals(session.prefs.getString("database_name", null)) ||
                !session.folderName.equals(session.prefs.getString("folder_name", null))) {
            throw new IOException("Refusing cleanup with mismatched pilot preferences");
        }
    }

    private static int cleanOwnedFolder(Context context, String relativeFolder) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Bundle args = new Bundle();
        args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=?");
        args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[] {relativeFolder, context.getPackageName()});
        List<Uri> uris = new ArrayList<>();
        try (Cursor cursor = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[] {MediaStore.MediaColumns._ID}, args, null)) {
            if (cursor == null) throw new IOException("Could not query owned pilot media");
            while (cursor.moveToNext()) {
                uris.add(Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        Long.toString(cursor.getLong(0))));
            }
        }
        int deleted = 0;
        for (Uri uri : uris) {
            deleteOwnedUri(context, uri, relativeFolder);
            deleted++;
        }
        return deleted;
    }

    private static void deleteOwnedUri(Context context, Uri uri, String relativeFolder) throws IOException {
        if (!"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority()) ||
                !uri.getPath().matches("/(?:external|external_primary)/images/media/[0-9]+")) {
            throw new IOException("Refusing unexpected pilot media URI");
        }
        Bundle args = new Bundle();
        args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        try (Cursor cursor = context.getContentResolver().query(uri, OWNED_MEDIA_FIELDS, args, null)) {
            if (cursor == null || cursor.getCount() != 1 || !cursor.moveToFirst()) {
                throw new IOException("Could not recheck pilot media before deletion");
            }
            if (!context.getPackageName().equals(cursor.getString(1)) ||
                    !relativeFolder.equals(cursor.getString(2))) {
                throw new IOException("Pilot media owner or folder changed; refusing deletion");
            }
        }
        if (context.getContentResolver().delete(uri, null, null) != 1) {
            throw new IOException("Could not delete owned pilot media");
        }
    }

    private static List<Ep7WifiClient.Entry> selectWifi(List<Ep7WifiClient.Entry> photos) throws IOException {
        List<Ep7WifiClient.Entry> jpegs = new ArrayList<>();
        List<Ep7WifiClient.Entry> raws = new ArrayList<>();
        for (Ep7WifiClient.Entry entry : photos) {
            if (isJpeg(entry.name())) jpegs.add(entry);
            else if (isRaw(entry.name())) raws.add(entry);
        }
        Comparator<Ep7WifiClient.Entry> order = Comparator.comparing(entry -> entry.path);
        jpegs.sort(order);
        raws.sort(order);
        if (jpegs.size() < JPEG_COUNT || raws.size() < RAW_COUNT) {
            throw new IOException("Camera needs two JPEG and two RAW pilot photos");
        }
        List<Ep7WifiClient.Entry> selected = new ArrayList<>(MAX_FILES);
        selected.addAll(jpegs.subList(0, JPEG_COUNT));
        selected.addAll(raws.subList(0, RAW_COUNT));
        if (totalWifiBytes(selected) > MAX_PILOT_BYTES) throw new IOException("Wi-Fi pilot exceeds 256 MiB");
        return selected;
    }

    private static List<CameraDocumentSource.Entry> selectSd(List<CameraDocumentSource.Entry> photos) throws IOException {
        List<CameraDocumentSource.Entry> ordered = new ArrayList<>(photos);
        ordered.sort(Comparator.comparing(entry -> entry.file.relativePath));
        List<CameraDocumentSource.Entry> selected = new ArrayList<>(MAX_FILES);
        Set<String> uris = new HashSet<>();
        long[] bytes = {0};
        addSdType(ordered, selected, uris, bytes, true, JPEG_COUNT);
        addSdType(ordered, selected, uris, bytes, false, RAW_COUNT);
        for (CameraDocumentSource.Entry entry : ordered) {
            if (selected.size() == MAX_FILES) break;
            addSd(entry, selected, uris, bytes);
        }
        if (selected.isEmpty()) throw new IOException("No SD photos are available for the pilot");
        return selected;
    }

    private static List<CameraDocumentSource.Entry> selectSdForWifi(
            List<CameraDocumentSource.Entry> photos, List<WifiProof> proof) throws IOException {
        if (proof.size() != MAX_FILES) throw new IOException("Wi-Fi cross-route proof must contain four files");
        List<CameraDocumentSource.Entry> selected = new ArrayList<>(proof.size());
        Set<String> selectedUris = new HashSet<>();
        for (WifiProof expected : proof) {
            List<CameraDocumentSource.Entry> matches = new ArrayList<>();
            for (CameraDocumentSource.Entry entry : photos) {
                if (entry.file.bytes == expected.bytes &&
                        entry.file.relativePath.equalsIgnoreCase(expected.relativePath)) {
                    matches.add(entry);
                }
            }
            if (matches.isEmpty()) throw new IOException("A Wi-Fi pilot file is missing from the selected SD tree");
            if (matches.size() != 1 || !selectedUris.add(matches.get(0).uri.toString())) {
                throw new IOException("A Wi-Fi pilot file is ambiguous in the selected SD tree");
            }
            selected.add(matches.get(0));
        }
        if (totalSdBytes(selected) > MAX_PILOT_BYTES) throw new IOException("SD cross-route pilot exceeds 256 MiB");
        return selected;
    }

    private static void addSdType(List<CameraDocumentSource.Entry> entries,
            List<CameraDocumentSource.Entry> selected, Set<String> uris, long[] bytes,
            boolean jpeg, int targetCount) {
        int count = 0;
        for (CameraDocumentSource.Entry entry : entries) {
            if (count >= targetCount || selected.size() >= MAX_FILES) break;
            if (jpeg ? !isJpeg(entry.file.name) : !isRaw(entry.file.name)) continue;
            if (addSd(entry, selected, uris, bytes)) count++;
        }
    }

    private static boolean addSd(CameraDocumentSource.Entry entry,
            List<CameraDocumentSource.Entry> selected, Set<String> uris, long[] bytes) {
        String key = entry.uri.toString();
        if (uris.contains(key) || selected.size() >= MAX_FILES || entry.file.bytes > MAX_PILOT_BYTES - bytes[0]) return false;
        uris.add(key);
        selected.add(entry);
        bytes[0] += entry.file.bytes;
        return true;
    }

    private static boolean isJpeg(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.endsWith(".JPG") || upper.endsWith(".JPEG");
    }

    private static boolean isRaw(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.endsWith(".DNG") || upper.endsWith(".RW2") || upper.endsWith(".ORF");
    }

    private static CameraImportFile wifiSource(String sourceId, Ep7WifiClient.Entry entry) {
        return new CameraImportFile(sourceId, entry.path.substring(1), entry.bytes,
                entry.date + ":" + entry.time, 0);
    }

    private static long totalWifiBytes(List<Ep7WifiClient.Entry> entries) throws IOException {
        long bytes = 0;
        for (Ep7WifiClient.Entry entry : entries) {
            if (entry.bytes <= 0 || entry.bytes > MAX_PILOT_BYTES) throw new IOException("Unsupported Wi-Fi pilot file size");
            bytes = Math.addExact(bytes, entry.bytes);
        }
        return bytes;
    }

    private static long totalSdBytes(List<CameraDocumentSource.Entry> entries) throws IOException {
        long bytes = 0;
        for (CameraDocumentSource.Entry entry : entries) bytes = Math.addExact(bytes, entry.file.bytes);
        if (bytes > MAX_PILOT_BYTES) throw new IOException("SD pilot exceeds 256 MiB");
        return bytes;
    }

    private static void requireNetwork(Context context, ConnectivityManager manager, Network network,
            String expectedIdentity) throws IOException {
        if (manager.getNetworkCapabilities(network) == null) throw new IOException("Camera Wi-Fi disconnected");
        if (expectedIdentity != null && !expectedIdentity.equals(CameraSourceIdentity.wifi(context, network))) {
            throw new IOException("Camera Wi-Fi identity changed during pilot");
        }
    }

    private static void copyWifi(Context context, ConnectivityManager manager, Network network,
            String expectedIdentity, Ep7WifiClient client, Ep7WifiClient.Entry entry,
            java.io.OutputStream output) throws IOException {
        requireNetwork(context, manager, network, expectedIdentity);
        client.copyOriginal(entry, output);
        requireNetwork(context, manager, network, expectedIdentity);
    }

    private static double elapsed(long start) {
        return Math.max(1, System.nanoTime() - start) / 1_000_000_000.0;
    }

    private static String metric(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static boolean validRunId(String value) {
        if (value == null || !value.matches("[0-9a-fA-F-]{36}")) return false;
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static final class PilotSession {
        final String runId;
        final String databaseName;
        final String folderName;
        final String prefsName;
        final SharedPreferences prefs;

        PilotSession(String runId, String databaseName, String folderName,
                String prefsName, SharedPreferences prefs) {
            this.runId = runId;
            this.databaseName = databaseName;
            this.folderName = folderName;
            this.prefsName = prefsName;
            this.prefs = prefs;
        }
    }

    private static final class WifiProof {
        final String relativePath;
        final long bytes;
        final String sourceSha;
        final String uri;

        WifiProof(String relativePath, long bytes, String sourceSha, String uri) {
            this.relativePath = relativePath;
            this.bytes = bytes;
            this.sourceSha = sourceSha;
            this.uri = uri;
        }
    }
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }

}
