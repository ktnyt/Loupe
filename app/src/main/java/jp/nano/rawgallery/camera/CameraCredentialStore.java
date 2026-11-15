package jp.nano.rawgallery.camera;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

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
                try (Profile profile = read(filename)) { names.add(profile.name); }
            }
            Collections.sort(names);
            return names;
        }
    }
    public char[] passcode(String exactBleName) throws IOException {
        synchronized (LOCK) {
            String filename = filename(exactBleName);
            if (!files().contains(filename)) return null;
            try (Profile profile = read(filename)) {
                if (!profile.name.equals(exactBleName)) throw failure();
                return profile.code.clone();
            }
        }
    }
    public void save(CameraQrCredentials credentials) throws IOException {
        synchronized (LOCK) {
            String filename = filename(credentials.bleName);
            Set<String> existing = files();
            if (!existing.contains(filename) && existing.size() >= MAX_CAMERAS)
                throw new IOException("Camera registration limit reached");
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) throw failure();
            char[] code = credentials.passcode();
            byte[] plain = null, encrypted = null;
            byte[] previous = null, record = null;
            File target = new File(directory, filename), rollback = new File(directory, filename + ".rollback");
            try {
                byte[] name = credentials.bleName.getBytes(StandardCharsets.US_ASCII);
                if (code.length < 1 || code.length > 12) throw failure();
                plain = new byte[2 + name.length + code.length];
                plain[0] = (byte) name.length;
                System.arraycopy(name, 0, plain, 1, name.length);
                plain[1 + name.length] = (byte) code.length;
                for (int i = 0; i < code.length; i++) {
                    if (code[i] < 0x20 || code[i] > 0x7e) throw failure();
                    plain[2 + name.length + i] = (byte) code[i];
                }
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key(filename, true));
                cipher.updateAAD(filename.getBytes(StandardCharsets.US_ASCII));
                encrypted = cipher.doFinal(plain);
                if (cipher.getIV().length != 12) throw failure();
                record = new byte[13 + encrypted.length]; record[0] = 1;
                System.arraycopy(cipher.getIV(), 0, record, 1, 12);
                System.arraycopy(encrypted, 0, record, 13, encrypted.length);
                // The old ciphertext remains authoritative until the new file has been verified.
                File current = exists(rollback) ? rollback : target;
                previous = exists(current) ? bytes(current) : new byte[0];
                write(rollback, previous);
                write(target, record);
                faults.afterReplacement();
                try (Profile saved = decode(filename, bytes(target))) {
                    if (!saved.name.equals(credentials.bleName) || !Arrays.equals(saved.code, code)) throw failure();
                }
                erase(rollback); // Commit point. A crash or failure before this keeps the old registration.
            } catch (Exception error) {
                throw failure();
            } finally {
                Arrays.fill(code, '\0'); wipe(plain); wipe(encrypted); wipe(previous); wipe(record);
            }
        }
    }
    public void remove(String exactBleName) throws IOException {
        synchronized (LOCK) {
            String filename = filename(exactBleName);
            // Remove the candidate first: a failed deletion must not reveal an uncommitted replacement.
            erase(new File(directory, filename));
            erase(new File(directory, filename + ".rollback"));
            try { KeyStore store = keys(); store.deleteEntry(alias(filename)); }
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
    private Profile read(String filename) throws IOException {
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
    private Profile decode(String filename, byte[] record) throws IOException {
        byte[] plain = null;
        try {
            if (record.length < 31 || record[0] != 1) throw failure();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(filename, false), new GCMParameterSpec(128, record, 1, 12));
            cipher.updateAAD(filename.getBytes(StandardCharsets.US_ASCII));
            plain = cipher.doFinal(record, 13, record.length - 13);
            int nameLength = plain[0] & 255;
            if (nameLength < 1 || nameLength > 32 || plain.length < nameLength + 3) throw failure();
            String name = new String(plain, 1, nameLength, StandardCharsets.US_ASCII);
            int codeLength = plain[1 + nameLength] & 255;
            if (!filename(name).equals(filename) || codeLength < 1 || codeLength > 12
                    || plain.length != nameLength + codeLength + 2) throw failure();
            char[] code = new char[codeLength];
            for (int i = 0; i < codeLength; i++) code[i] = (char) (plain[nameLength + 2 + i] & 255);
            return new Profile(name, code);
        } catch (Exception error) { throw failure(); }
        finally { wipe(record); wipe(plain); }
    }
    private SecretKey key(String filename, boolean create) throws Exception {
        KeyStore store = keys(); String alias = alias(filename);
        if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
        if (!create) throw failure();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    private static KeyStore keys() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); return store;
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
    private static final class Profile implements AutoCloseable {
        final String name; final char[] code;
        Profile(String name, char[] code) { this.name = name; this.code = code; }
        public void close() { Arrays.fill(code, '\0'); }
    }
    private static void wipe(byte[] value) { if (value != null) Arrays.fill(value, (byte) 0); }
    private static IOException failure() { return new IOException("Camera registration could not be read or saved"); }
}
