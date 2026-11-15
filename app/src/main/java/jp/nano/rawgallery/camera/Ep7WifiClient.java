package jp.nano.rawgallery.camera;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Read-only OPC experiment. E-P7 compatibility remains subject to physical-camera validation. */
public final class Ep7WifiClient {
    public static final String HOST = "192.168.0.10";
    public static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    private static final int MAX_ENTRIES = 20000;
    private static final int MAX_LIST_BYTES = 4 * 1024 * 1024;
    private static final long DEADLINE_NS = 120_000_000_000L;

    /** Production callers bind this to the selected Wi-Fi Network, never the process default. */
    public interface Connections { HttpURLConnection open(URL url) throws IOException; }
    public static final class Entry {
        public final String path;
        public final long bytes;
        public final int date, time;
        public final boolean directory;
        private final boolean accessible;
        private Entry(String path, long bytes, int date, int time, boolean directory, boolean accessible) {
            this.path = path; this.bytes = bytes; this.date = date; this.time = time; this.directory = directory; this.accessible = accessible;
        }
        public String name() { return path.substring(path.lastIndexOf('/') + 1); }
        public boolean photo() { return path.toUpperCase(Locale.ROOT).matches(".*\\.(JPG|JPEG|ORF)$"); }
    }
    public static final class Listing {
        public final List<Entry> photos;
        public final double seconds;
        Listing(List<Entry> photos, double seconds) { this.photos = Collections.unmodifiableList(photos); this.seconds = seconds; }
    }
    public static final class Download {
        public final File file;
        public final long bytes;
        public final String sha256;
        public final double transferSeconds, totalSeconds;
        Download(File file, long bytes, String sha, double transferSeconds, double totalSeconds) {
            this.file = file; this.bytes = bytes; this.sha256 = sha;
            this.transferSeconds = transferSeconds; this.totalSeconds = totalSeconds;
        }
        public double mibPerSecond() { return bytes / 1048576.0 / transferSeconds; }
    }
    private final Connections connections;
    private final BooleanSupplier cancelled;
    public Ep7WifiClient(Connections connections, BooleanSupplier cancelled) {
        this.connections = Objects.requireNonNull(connections); this.cancelled = Objects.requireNonNull(cancelled);
    }
    private void check(long start) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
        if (System.nanoTime() - start > DEADLINE_NS) throw new IOException("Operation timed out");
    }
    private static void path(String value) throws IOException {
        if (!value.matches("/DCIM(?:/[A-Za-z0-9_-]{1,64}(?:\\.[A-Za-z0-9]{1,8})?){0,4}"))
            throw new IOException("Unsupported camera path");
    }
    private HttpURLConnection open(String target) throws IOException {
        HttpURLConnection c = connections.open(new URL("http://" + HOST + target));
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(5000); c.setReadTimeout(5000);
        c.setRequestMethod("GET");
        c.setRequestProperty("User-Agent", "OlympusCameraKit");
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }
    private static void response(HttpURLConnection c) throws IOException {
        if (c.getResponseCode() != 200) throw new IOException("Camera HTTP response was not successful");
        String encoding = c.getContentEncoding();
        if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IOException("Unsupported content encoding");
    }
    /** Strict six-field VER_100 listing. Malformed/partial lists are errors, never 'no new photos'. */
    public static List<Entry> parseList(String body, String requested) throws IOException {
        path(requested);
        String[] lines = body.split("\\r?\\n", -1);
        if (lines.length == 0 || !lines[0].equals("VER_100")) throw new IOException("Unsupported image list format");
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty() && i == lines.length - 1) continue;
            String[] f = lines[i].split(",", -1);
            if (f.length != 6 || !f[0].equals(requested)) throw new IOException("Invalid image list entry");
            String name = f[1];
            if (!name.matches("[A-Za-z0-9_-]{1,64}(?:\\.[A-Za-z0-9]{1,8})?")) throw new IOException("Invalid file name");
            String full = requested + "/" + name; path(full);
            try {
                for (int n = 2; n < 6; n++) if (!f[n].matches("[0-9]{1,19}")) throw new NumberFormatException();
                long size = Long.parseLong(f[2]);
                int attr = Integer.parseInt(f[3]), date = Integer.parseInt(f[4]), time = Integer.parseInt(f[5]);
                if (size < 0 || attr < 0 || attr > 255 || date < 0 || date > 65535 || time < 0 || time > 65535)
                    throw new NumberFormatException();
                if (!seen.add(full) || entries.size() >= MAX_ENTRIES) throw new IOException("Duplicate or excessive image list");
                entries.add(new Entry(full, size, date, time, (attr & 16) != 0, (attr & 14) == 0));
            } catch (NumberFormatException e) { throw new IOException("Invalid image list numbers", e); }
        }
        return entries;
    }
    public Listing list() throws IOException {
        long start = System.nanoTime();
        ArrayDeque<String> pending = new ArrayDeque<>(); pending.add("/DCIM");
        Set<String> visited = new HashSet<>(); List<Entry> photos = new ArrayList<>();
        int count = 0;
        while (!pending.isEmpty()) {
            check(start); String dir = pending.remove();
            if (!visited.add(dir) || visited.size() > 1000) throw new IOException("Excessive camera directories");
            HttpURLConnection c = open("/get_imglist.cgi?DIR=" + URLEncoder.encode(dir, "UTF-8"));
            List<Entry> entries;
            try {
                response(c);
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        check(start);
                        if (body.size() + n > MAX_LIST_BYTES) throw new IOException("Image list too large");
                        body.write(buffer, 0, n);
                    }
                }
                entries = parseList(body.toString(StandardCharsets.UTF_8.name()), dir);
            } finally { c.disconnect(); }
            count += entries.size();
            if (count > MAX_ENTRIES) throw new IOException("Too many camera files");
            for (Entry e : entries) {
                if (!e.accessible) continue;
                if (e.directory) pending.add(e.path);
                else if (e.photo() && e.bytes > 0 && e.bytes <= MAX_FILE_BYTES) photos.add(e);
            }
        }
        check(start);
        return new Listing(photos, seconds(start));
    }
    /** Receive -> exact length -> sync -> SHA readback -> unique final file. No MediaStore/history writes. */
    public Download download(Entry entry, File directory) throws IOException {
        path(entry.path);
        if (entry.directory || !entry.photo() || entry.bytes <= 0 || entry.bytes > MAX_FILE_BYTES)
            throw new IOException("Unsupported test transfer");
        long start = System.nanoTime(); check(start);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create test storage");
        File temporary = File.createTempFile("ep7-", ".part", directory);
        boolean saved = false;
        HttpURLConnection c = null;
        try {
            MessageDigest digest = sha(); long received = 0;
            long httpStart = System.nanoTime();
            c = open(entry.path); response(c);
            long length = c.getContentLengthLong();
            if (length >= 0 && length != entry.bytes) throw new IOException("Camera size changed");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) != -1) {
                    check(start); received += n;
                    if (received > entry.bytes) throw new IOException("Camera file too long");
                    out.write(buffer, 0, n); digest.update(buffer, 0, n);
                }
                if (received != entry.bytes) throw new IOException("Camera file incomplete");
                // Transfer timing excludes sync and readback; both remain inside the total timing.
                double transfer = seconds(httpStart);
                out.getFD().sync();
                byte[] expected = digest.digest();
                MessageDigest readback = sha(); long readBytes = 0;
                try (InputStream savedInput = new FileInputStream(temporary)) {
                    while ((n = savedInput.read(buffer)) != -1) {
                        check(start); readback.update(buffer, 0, n); readBytes += n;
                    }
                }
                if (readBytes != received || !MessageDigest.isEqual(expected, readback.digest()))
                    throw new IOException("Saved bytes could not be verified");
                check(start);
                File result = new File(directory, UUID.randomUUID() + "-" + entry.name());
                if (result.exists() || !temporary.renameTo(result)) throw new IOException("Cannot finalize test file");
                saved = true;
                return new Download(result, received, hex(expected), transfer, seconds(start));
            }
        } finally {
            if (c != null) c.disconnect();
            if (!saved && temporary.exists() && !temporary.delete()) throw new IOException("Cannot remove incomplete test file");
        }
    }
    private static double seconds(long start) { return Math.max(1, System.nanoTime() - start) / 1_000_000_000.0; }
    private static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
}
