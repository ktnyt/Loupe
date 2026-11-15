package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import jp.nano.rawgallery.camera.CameraImportCopy;
import jp.nano.rawgallery.camera.CameraImportFile;
import jp.nano.rawgallery.camera.CameraImportLedger;
import jp.nano.rawgallery.camera.Ep7WifiClient;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** E-P7 differential-read benchmark. Uses only an isolated ledger and cache directory. */
final class CameraImportBenchmarkChecks {
    private static final String SOURCE_ID = "wifi:ep7-benchmark";
    private static final int EXPECTED_LIST_FILES = 525;
    private static final int JPEG_COUNT = 2;
    private static final int ORF_COUNT = 2;

    private CameraImportBenchmarkChecks() { }

    static String run(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        String runId = UUID.randomUUID().toString();
        String databaseName = "camera-import-benchmark-" + runId + ".db";
        File testCache = new File(context.getCacheDir(), "camera-import-benchmark-" + runId);
        CameraImportLedger ledger = new CameraImportLedger(context, databaseName);
        try {
            Network network = uniqueWifiNetwork(context);
            Ep7WifiClient client = new Ep7WifiClient(
                    url -> (HttpURLConnection) network.openConnection(url, Proxy.NO_PROXY), () -> false);

            Ep7WifiClient.Listing listing = client.list();
            if (listing.photos.size() != EXPECTED_LIST_FILES) {
                throw new IOException("Unexpected camera photo count for benchmark fixture");
            }
            List<Ep7WifiClient.Entry> selected = selectFixtureSet(listing.photos);
            List<CameraImportFile> sources = new ArrayList<>(selected.size());
            long selectedBytes = 0;
            for (Ep7WifiClient.Entry entry : selected) {
                CameraImportFile source = source(entry);
                sources.add(source);
                selectedBytes = Math.addExact(selectedBytes, source.bytes);
            }

            ensureDirectory(testCache);
            List<String> baselineSha = new ArrayList<>(selected.size());
            long baselineNanos = 0;
            for (int i = 0; i < selected.size(); i++) {
                Ep7WifiClient.Entry entry = selected.get(i);
                CameraImportFile source = sources.get(i);
                long itemStart = System.nanoTime();
                String sourceSha;
                try (CameraImportCopy.Prepared prepared = CameraImportCopy.prepare(
                        testCache, source, out -> client.copyOriginal(entry, out), () -> false)) {
                    if (prepared.bytes != source.bytes) throw new IOException("Camera file size changed during baseline");
                    sourceSha = prepared.sourceSha256;
                }
                baselineNanos = Math.addExact(baselineNanos, System.nanoTime() - itemStart);
                baselineSha.add(sourceSha);
                String kind = isOrf(entry) ? "RAW" : "JPEG";
                String uri = "content://media/benchmark/" + runId + "/" + i;
                ledger.adopt(source, sourceSha, sourceSha, source.bytes, uri, kind, "benchmark-capture");
            }
            double baselineSeconds = Math.max(1, baselineNanos) / 1_000_000_000.0;

            // Repeat the full camera listing, require the same selected metadata, then time
            // only the ledger receipt lookups. No original photo payload is requested here.
            long metadataStart = System.nanoTime();
            Ep7WifiClient.Listing metadataListing = client.list();
            if (metadataListing.photos.size() != EXPECTED_LIST_FILES) {
                throw new IOException("Camera photo count changed during metadata benchmark");
            }
            List<Ep7WifiClient.Entry> metadataSelection = selectFixtureSet(metadataListing.photos);
            requireSameSelection(selected, metadataSelection);
            long metadataPhotoBytesTransferred = 0;
            for (int i = 0; i < selected.size(); i++) {
                CameraImportFile metadataSource = source(metadataSelection.get(i));
                CameraImportLedger.Content known = ledger.known(metadataSource);
                if (known == null || known.sourceBytes != metadataSource.bytes ||
                        !known.sourceSha.equals(baselineSha.get(i))) {
                    throw new IOException("Verified benchmark receipt was not found");
                }
            }
            double metadataSeconds = secondsSince(metadataStart);

            long strictStart = System.nanoTime();
            long strictBytesRead = 0;
            for (int i = 0; i < selected.size(); i++) {
                Ep7WifiClient.Entry entry = selected.get(i);
                CameraImportFile source = sources.get(i);
                try (CameraImportCopy.Prepared prepared = CameraImportCopy.prepare(
                        testCache, source, out -> client.copyOriginal(entry, out), () -> false)) {
                    strictBytesRead = Math.addExact(strictBytesRead, prepared.bytes);
                    if (prepared.bytes != source.bytes || !prepared.sourceSha256.equals(baselineSha.get(i))) {
                        throw new IOException("Strict benchmark content check failed");
                    }
                    CameraImportLedger.Content content = ledger.find(prepared.sourceSha256, prepared.bytes);
                    if (content == null) throw new IOException("Strict benchmark content record was not found");
                }
            }
            double strictSeconds = secondsSince(strictStart);
            if (strictBytesRead != selectedBytes) throw new IOException("Strict benchmark byte total changed");
            if (metadataPhotoBytesTransferred != 0) throw new AssertionError("Metadata round unexpectedly read source bytes");

            return "PASS camera-import-benchmark" +
                    " listFiles=" + listing.photos.size() +
                    " selectedFiles=" + selected.size() +
                    " selectedBytes=" + selectedBytes +
                    " listSeconds=" + metric(listing.seconds) +
                    " baselineSeconds=" + metric(baselineSeconds) +
                    " baselineMiBPerSecond=" + metric(mibPerSecond(selectedBytes, baselineSeconds)) +
                    " metadataSeconds=" + metric(metadataSeconds) +
                    " metadataPhotoBytesTransferred=" + metadataPhotoBytesTransferred +
                    " metadataMiBPerSecond=" + metric(mibPerSecond(metadataPhotoBytesTransferred, metadataSeconds)) +
                    " strictSeconds=" + metric(strictSeconds) +
                    " strictMiBPerSecond=" + metric(mibPerSecond(strictBytesRead, strictSeconds));
        } finally {
            ledger.close();
            File databaseFile = context.getDatabasePath(databaseName);
            boolean dbDeleted = !databaseFile.exists() || context.deleteDatabase(databaseName);
            IOException cleanupFailure = null;
            try {
                deleteTree(testCache);
            } catch (IOException e) {
                cleanupFailure = e;
            }
            if (!dbDeleted) {
                IOException e = new IOException("Could not remove isolated benchmark ledger");
                if (cleanupFailure == null) cleanupFailure = e;
                else cleanupFailure.addSuppressed(e);
            }
            if (cleanupFailure != null) throw cleanupFailure;
        }
    }

    private static Network uniqueWifiNetwork(Context context) throws IOException {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) throw new IOException("Wi-Fi network service is unavailable");
        Network selected = null;
        for (Network candidate : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(candidate);
            if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (selected != null) throw new IOException("Multiple Wi-Fi networks are available");
            selected = candidate;
        }
        if (selected == null) throw new IOException("No Wi-Fi network is available");
        return selected;
    }

    private static List<Ep7WifiClient.Entry> selectFixtureSet(List<Ep7WifiClient.Entry> photos) throws IOException {
        List<Ep7WifiClient.Entry> jpegs = new ArrayList<>();
        List<Ep7WifiClient.Entry> orfs = new ArrayList<>();
        for (Ep7WifiClient.Entry entry : photos) {
            String upper = entry.name().toUpperCase(Locale.ROOT);
            if (upper.endsWith(".ORF")) orfs.add(entry);
            else if (upper.endsWith(".JPG") || upper.endsWith(".JPEG")) jpegs.add(entry);
        }
        Comparator<Ep7WifiClient.Entry> order = Comparator.comparing(entry -> entry.path);
        jpegs.sort(order);
        orfs.sort(order);
        if (jpegs.size() < JPEG_COUNT || orfs.size() < ORF_COUNT) {
            throw new IOException("Camera does not have two JPEG and two ORF benchmark files");
        }
        List<Ep7WifiClient.Entry> selected = new ArrayList<>(JPEG_COUNT + ORF_COUNT);
        selected.addAll(jpegs.subList(0, JPEG_COUNT));
        selected.addAll(orfs.subList(0, ORF_COUNT));
        return selected;
    }

    private static CameraImportFile source(Ep7WifiClient.Entry entry) {
        String path = entry.path.startsWith("/") ? entry.path.substring(1) : entry.path;
        String stamp = entry.date + ":" + entry.time;
        return new CameraImportFile(SOURCE_ID, path, entry.bytes, stamp, 0);
    }

    private static boolean isOrf(Ep7WifiClient.Entry entry) {
        return entry.name().toUpperCase(Locale.ROOT).endsWith(".ORF");
    }

    private static void requireSameSelection(
            List<Ep7WifiClient.Entry> before, List<Ep7WifiClient.Entry> after) throws IOException {
        if (before.size() != after.size()) throw new IOException("Selected camera set changed between rounds");
        for (int i = 0; i < before.size(); i++) {
            Ep7WifiClient.Entry a = before.get(i);
            Ep7WifiClient.Entry b = after.get(i);
            if (!a.path.equals(b.path) || a.bytes != b.bytes || a.date != b.date || a.time != b.time) {
                throw new IOException("Selected camera metadata changed between rounds");
            }
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create benchmark cache directory");
        }
    }

    private static void deleteTree(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        if (!file.delete()) throw new IOException("Could not remove benchmark cache file");
    }

    private static double secondsSince(long start) {
        return Math.max(1, System.nanoTime() - start) / 1_000_000_000.0;
    }

    private static double mibPerSecond(long bytes, double seconds) {
        return seconds <= 0 ? 0 : bytes / 1048576.0 / seconds;
    }

    private static String metric(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
