package jp.nano.rawgallery.camera;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import jp.nano.rawgallery.security.EncryptedSecretStore;

/** At most eight locally registered cameras. Only authenticated ciphertext lives in no-backup storage.
 * All methods perform IO; call off main. Names must match exactly before a passcode is returned. */
public final class CameraCredentialStore {
    private static final Object LOCK = new Object();
    private static final int MAX_CAMERAS = 8;
    private final File directory;
    private final String namespace;
    interface Faults { void afterReplacement() throws IOException; }
    private final Faults faults;

    public CameraCredentialStore(Context context) { this(context, "camera-credentials-v1"); }
    CameraCredentialStore(Context context, String namespace) {
        this(context, namespace, () -> { });
    }
    CameraCredentialStore(Context context, String namespace, Faults faults) {
        if (!namespace.equals("camera-credentials-v1") && !namespace.matches("camera-credentials-test-[0-9a-f-]{36}"))
            throw new IllegalArgumentException("Invalid credential namespace");
        this.namespace = namespace;
        this.faults = faults;
        directory = new File(context.getNoBackupFilesDir(), namespace);
    }
    public List<String> names() throws IOException {
        synchronized (LOCK) {
            List<String> names = new ArrayList<>();
            for (String filename : files()) {
                try (Registration profile = read(filename)) { names.add(profile.bleName); }
            }
            Collections.sort(names);
            return names;
        }
    }
    public char[] passcode(String exactBleName) throws IOException {
        synchronized (LOCK) {
            String filename = filename(exactBleName);
            if (!files().contains(filename)) return null;
            try (Registration profile = read(filename)) {
                if (!profile.bleName.equals(exactBleName)) throw failure();
                return profile.code.clone();
            }
        }
    }
    /** Opaque profile reference; never contains a camera name or credential. */
    public static String profileId(String exactBleName) {
        try { return filename(exactBleName).substring(0, 64); }
        catch (IOException invalid) { throw new IllegalArgumentException("Invalid camera name"); }
    }
    public Registration registration(String opaqueId) throws IOException {
        synchronized (LOCK) {
            if (opaqueId == null || !opaqueId.matches("[0-9a-f]{64}")) throw failure();
            String filename = opaqueId + ".bin";
            return files().contains(filename) ? read(filename) : null;
        }
    }
    public void save(CameraQrCredentials credentials) throws IOException {
        synchronized (LOCK) {
            String filename = filename(credentials.bleName);
            Set<String> existing = files();
            if (!existing.contains(filename) && existing.size() >= MAX_CAMERAS)
                throw new IOException("Camera registration limit reached");
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) throw failure();
            char[] code = credentials.passcode(), wifi = null;
            byte[] plain = null, encrypted = null;
            byte[] previous = null, record = null;
            File target = new File(directory, filename), rollback = new File(directory, filename + ".rollback");
            try {
                wifi = credentials.wifiPassphrase();
                byte[] name = ascii(credentials.bleName, 1, 32), ssid = ascii(credentials.ssid, 1, 32);
                if (!valid(code, 1, 12) || !valid(wifi, 8, 63)
                        || (credentials.wifiSecurity != 1 && credentials.wifiSecurity != 2)) throw failure();
                // Version 2: length/name, length/code, security, length/SSID, length/passphrase.
                plain = new byte[5 + name.length + code.length + ssid.length + wifi.length];
                int at = 0;
                plain[at++] = (byte) name.length;
                System.arraycopy(name, 0, plain, at, name.length); at += name.length;
                plain[at++] = (byte) code.length;
                for (char c : code) plain[at++] = (byte) c;
                plain[at++] = (byte) credentials.wifiSecurity;
                plain[at++] = (byte) ssid.length;
                System.arraycopy(ssid, 0, plain, at, ssid.length); at += ssid.length;
                plain[at++] = (byte) wifi.length;
                for (char c : wifi) plain[at++] = (byte) c;
                Cipher cipher = EncryptedSecretStore.encryptionCipher(alias(filename), 256);
                cipher.updateAAD(new byte[]{2});
                cipher.updateAAD(filename.getBytes(StandardCharsets.US_ASCII));
                encrypted = cipher.doFinal(plain);
                if (cipher.getIV().length != 12) throw failure();
                record = new byte[13 + encrypted.length]; record[0] = 2;
                System.arraycopy(cipher.getIV(), 0, record, 1, 12);
                System.arraycopy(encrypted, 0, record, 13, encrypted.length);
                // The old ciphertext remains authoritative until the new file has been verified.
                File current = exists(rollback) ? rollback : target;
                previous = exists(current) ? bytes(current) : new byte[0];
                write(rollback, previous);
                write(target, record);
                faults.afterReplacement();
                try (Registration saved = decode(filename, bytes(target))) {
                    if (!saved.bleName.equals(credentials.bleName) || !Arrays.equals(saved.code, code)
                            || !Objects.equals(saved.ssid, credentials.ssid) || saved.wifiSecurity != credentials.wifiSecurity
                            || !Arrays.equals(saved.wifi, wifi)) throw failure();
                }
                erase(rollback); // Commit point. A crash or failure before this keeps the old registration.
            } catch (Exception error) {
                throw failure();
            } finally {
                Arrays.fill(code, '\0'); if (wifi != null) Arrays.fill(wifi, '\0'); wipe(plain); wipe(encrypted); wipe(previous); wipe(record);
            }
        }
    }
    public void remove(String exactBleName) throws IOException {
        synchronized (LOCK) {
            String filename = filename(exactBleName);
            // Remove the candidate first: a failed deletion must not reveal an uncommitted replacement.
            erase(new File(directory, filename));
            erase(new File(directory, filename + ".rollback"));
            try { EncryptedSecretStore.deleteKey(alias(filename)); }
            catch (Exception error) { throw failure(); }
        }
    }
    private Set<String> files() throws IOException {
        Set<String> result = new TreeSet<>();
        if (!directory.exists()) return result;
        String[] files = directory.list();
        if (files == null || files.length > 32) throw failure();
        for (String file : files) {
            if (file.matches("[0-9a-f]{64}\\.bin(?:\\.rollback)?(?:\\.bak)?"))
                result.add(file.replaceFirst("\\.bak$", "").replaceFirst("\\.rollback$", ""));
        }
        for (String filename : new ArrayList<>(result)) {
            File rollback = new File(directory, filename + ".rollback");
            if (exists(rollback) && bytes(rollback).length == 0) result.remove(filename);
        }
        if (result.size() > MAX_CAMERAS) throw failure();
        return result;
    }
    private Registration read(String filename) throws IOException {
        File rollback = new File(directory, filename + ".rollback");
        return decode(filename, bytes(exists(rollback) ? rollback : new File(directory, filename)));
    }
    private static boolean exists(File file) { return file.exists() || new File(file + ".bak").exists(); }
    private static byte[] bytes(File file) throws IOException {
        try (FileInputStream input = new AtomicFile(file).openRead();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256]; int n;
            while ((n = input.read(buffer)) != -1) {
                if (bytes.size() + n > 256) throw failure();
                bytes.write(buffer, 0, n);
            }
            return bytes.toByteArray();
        }
    }
    private static void write(File file, byte[] value) throws IOException {
        AtomicFile atomic = new AtomicFile(file); FileOutputStream output = null;
        try {
            output = atomic.startWrite(); output.write(value); output.flush(); output.getFD().sync();
            atomic.finishWrite(output); output = null;
            byte[] saved = bytes(file);
            try { if (!Arrays.equals(value, saved)) throw failure(); } finally { wipe(saved); }
        } finally { if (output != null) atomic.failWrite(output); }
    }
    private static void erase(File file) throws IOException {
        new AtomicFile(file).delete();
        if (exists(file) || new File(file + ".new").exists()) throw failure();
    }
    private Registration decode(String filename, byte[] record) throws IOException {
        byte[] plain = null;
        char[] code = null, wifi = null;
        try {
            if (record.length < 31 || record.length > 256 || (record[0] != 1 && record[0] != 2)) throw failure();
            int version = record[0];
            Cipher cipher = EncryptedSecretStore.decryptionCipher(alias(filename),
                    () -> new GCMParameterSpec(128, record, 1, 12), null);
            if (version == 2) cipher.updateAAD(new byte[]{2});
            cipher.updateAAD(filename.getBytes(StandardCharsets.US_ASCII));
            plain = cipher.doFinal(record, 13, record.length - 13);
            int at = 0, nameLength = plain[at++] & 255;
            if (nameLength < 1 || nameLength > 32 || plain.length < nameLength + 3) throw failure();
            String name = new String(plain, at, nameLength, StandardCharsets.US_ASCII); at += nameLength;
            if (!filename(name).equals(filename)) throw failure();
            int codeLength = plain[at++] & 255;
            code = chars(plain, at, codeLength, 1, 12); at += codeLength;
            String ssid = null; int security = 0;
            if (version == 2) {
                security = plain[at++] & 255;
                if (security != 1 && security != 2) throw failure();
                int ssidLength = plain[at++] & 255;
                char[] ssidChars = chars(plain, at, ssidLength, 1, 32); at += ssidLength;
                try { ssid = new String(ssidChars); } finally { Arrays.fill(ssidChars, '\0'); }
                int wifiLength = plain[at++] & 255;
                wifi = chars(plain, at, wifiLength, 8, 63); at += wifiLength;
            }
            if (at != plain.length) throw failure();
            Registration profile = new Registration(filename.substring(0, 64), name, ssid, security, code, wifi);
            code = null; wifi = null;
            return profile;
        } catch (Exception error) { throw failure(); }
        finally {
            wipe(record); wipe(plain);
            if (code != null) Arrays.fill(code, '\0');
            if (wifi != null) Arrays.fill(wifi, '\0');
        }
    }
    private static byte[] ascii(String text, int min, int max) throws IOException {
        if (text == null || text.length() < min || text.length() > max || text.trim().isEmpty()) throw failure();
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) < 0x20 || text.charAt(i) > 0x7e) throw failure();
        return text.getBytes(StandardCharsets.US_ASCII);
    }
    private static boolean valid(char[] value, int min, int max) {
        if (value == null || value.length < min || value.length > max) return false;
        boolean nonblank = false;
        for (char c : value) { if (c < 0x20 || c > 0x7e) return false; if (c != ' ') nonblank = true; }
        return nonblank;
    }
    private static char[] chars(byte[] plain, int at, int length, int min, int max) throws IOException {
        if (length < min || length > max || at < 0 || at > plain.length - length) throw failure();
        char[] result = new char[length];
        for (int i = 0; i < length; i++) result[i] = (char) (plain[at + i] & 255);
        if (!valid(result, min, max)) { Arrays.fill(result, '\0'); throw failure(); }
        return result;
    }
    private String alias(String filename) { return "loupe." + namespace + "." + filename; }
    private static String filename(String name) throws IOException {
        if (name == null || !name.matches("[\\x20-\\x7e]{1,32}") || name.trim().isEmpty()) throw failure();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.US_ASCII));
            StringBuilder result = new StringBuilder();
            for (byte value : hash) result.append(String.format(Locale.ROOT, "%02x", value & 255));
            return result + ".bin";
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public static final class Registration implements AutoCloseable {
        public final String id, bleName, ssid;
        public final int wifiSecurity;
        private char[] code, wifi;
        private boolean closed;
        private Registration(String id, String bleName, String ssid, int wifiSecurity, char[] code, char[] wifi) {
            this.id = id; this.bleName = bleName; this.ssid = ssid; this.wifiSecurity = wifiSecurity;
            this.code = code; this.wifi = wifi;
        }
        public boolean hasWifi() { return ssid != null && (wifiSecurity == 1 || wifiSecurity == 2); }
        public synchronized char[] passcode() { checkOpen(); return code.clone(); }
        /** Null for a legacy version-1 BLE-only profile. */
        public synchronized char[] wifiPassphrase() { checkOpen(); return wifi == null ? null : wifi.clone(); }
        private void checkOpen() { if (closed) throw new IllegalStateException("Camera registration is closed"); }
        public synchronized void close() {
            closed = true;
            if (code != null) Arrays.fill(code, '\0');
            if (wifi != null) Arrays.fill(wifi, '\0');
            code = null; wifi = null;
        }
    }
    private static void wipe(byte[] value) { if (value != null) Arrays.fill(value, (byte) 0); }
    private static IOException failure() { return new IOException("Camera registration could not be read or saved"); }
}
