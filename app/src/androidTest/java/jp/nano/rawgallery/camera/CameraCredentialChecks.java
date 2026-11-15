package jp.nano.rawgallery.camera;

import android.app.Instrumentation;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Only synthetic credentials in an isolated no-backup namespace; never accesses registered user cameras. */
public final class CameraCredentialChecks {
    private static final String VECTOR = "OIS2,3,TVJ1CRDC,-+*%$ZYX,UKR1CRDC,-+*%$Z";
    private static final String RESTART = "camera-credentials-test-00000000-0000-4000-8000-000000000124";
    public static String run(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        String namespace = "camera-credentials-test-" + UUID.randomUUID();
        CameraCredentialStore store = new CameraCredentialStore(context, namespace);
        try {
            try (CameraQrCredentials qr = CameraQrCredentials.parse(VECTOR)) { store.save(qr); }
            require(store.names().equals(Collections.singletonList("BLE-TEST")), "Stored names");
            checkCode(new CameraCredentialStore(context, namespace), "123456");
            require(store.passcode("ANOTHER-CAMERA") == null, "Cross-camera credential lookup");
            File directory = new File(context.getNoBackupFilesDir(), namespace);
            File[] records = directory.listFiles((parent, name) -> name.endsWith(".bin"));
            require(records != null && records.length == 1, "One encrypted record");
            byte[] cipher = read(records[0]);
            String raw = new String(cipher, StandardCharsets.ISO_8859_1);
            require(!raw.contains("BLE-TEST") && !raw.contains("123456"), "No plaintext in storage");
            require(!records[0].getName().contains("BLE-TEST"), "Hashed profile filename");
            CameraCredentialStore failing = new CameraCredentialStore(context, namespace, () -> { throw new IOException("Injected post-replacement failure"); });
            try (CameraQrCredentials replacement = CameraQrCredentials.parse(VECTOR.substring(0, VECTOR.lastIndexOf(',') + 1) + "ZYXWVU")) {
                try { failing.save(replacement); throw new AssertionError("Injected replacement failure ignored"); }
                catch (IOException expected) { }
            }
            checkCode(new CameraCredentialStore(context, namespace), "123456");
            try (CameraQrCredentials first = CameraQrCredentials.parse(VECTOR.replace("UKR1CRDC", "V"))) {
                try { failing.save(first); throw new AssertionError("Injected first registration failure ignored"); }
                catch (IOException expected) { }
            }
            require(store.passcode("A") == null && store.names().equals(Collections.singletonList("BLE-TEST")), "Failed first registration remains absent");
            try (CameraQrCredentials replacement = CameraQrCredentials.parse(VECTOR.substring(0, VECTOR.lastIndexOf(',') + 1) + "ZYXWVU")) {
                store.save(replacement);
            }
            checkCode(store, "6789AB");
            byte[] changed = read(records[0]); changed[changed.length - 1] ^= 1;
            try (FileOutputStream output = new FileOutputStream(records[0])) { output.write(changed); output.getFD().sync(); }
            try { store.passcode("BLE-TEST"); throw new AssertionError("Tampered credential accepted"); }
            catch (IOException expected) { }
            store.remove("BLE-TEST");
            require(store.names().isEmpty() && store.passcode("BLE-TEST") == null, "Removal persisted");
            return "PASS camera-credentials encrypted=true exactName=true replacement=true failedReplacementPreserved=true failedFirstAbsent=true tamperingRejected=true removed=true";
        } finally {
            store.remove("BLE-TEST");
            store.remove("A");
            File directory = new File(context.getNoBackupFilesDir(), namespace);
            require(!directory.exists() || directory.delete(), "Synthetic directory cleanup");
        }
    }
    public static String seed(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        CameraCredentialStore store = new CameraCredentialStore(context, RESTART);
        try (CameraQrCredentials qr = CameraQrCredentials.parse(VECTOR)) { store.save(qr); }
        CameraCredentialStore failing = new CameraCredentialStore(context, RESTART, () -> { throw new IOException("Injected post-replacement failure"); });
        try (CameraQrCredentials qr = CameraQrCredentials.parse(VECTOR.substring(0, VECTOR.lastIndexOf(',') + 1) + "ZYXWVU")) {
            try { failing.save(qr); throw new AssertionError("Injected restart replacement failure ignored"); }
            catch (IOException expected) { }
        }
        return "PASS camera-credentials restart-seeded failedReplacement=true";
    }
    public static String verify(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        CameraCredentialStore store = new CameraCredentialStore(context, RESTART);
        try { checkCode(store, "123456"); return "PASS camera-credentials processRestart=true"; }
        finally {
            store.remove("BLE-TEST");
            File directory = new File(context.getNoBackupFilesDir(), RESTART);
            require(!directory.exists() || directory.delete(), "Restart fixture cleanup");
        }
    }
    private static byte[] read(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
    private static void checkCode(CameraCredentialStore store, String expected) throws IOException {
        char[] code = store.passcode("BLE-TEST");
        try { require(Arrays.equals(code, expected.toCharArray()), "Credential readback"); }
        finally { if (code != null) Arrays.fill(code, '\0'); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
