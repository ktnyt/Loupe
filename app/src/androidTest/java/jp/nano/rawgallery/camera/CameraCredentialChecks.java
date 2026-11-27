package jp.nano.rawgallery.camera;

import android.app.Instrumentation;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;

/**
 * Only synthetic credentials in an isolated no-backup namespace; never accesses registered user
 * cameras.
 */
public final class CameraCredentialChecks {
  private static final String VECTOR = "OIS2,3,TVJ1CRDC,-+*%$ZYX,UKR1CRDC,-+*%$Z";
  private static final String RESTART =
      "camera-credentials-test-00000000-0000-4000-8000-000000000124";

  public static String run(Instrumentation instrumentation) throws Exception {
    Context context = instrumentation.getTargetContext();
    String namespace = "camera-credentials-test-" + UUID.randomUUID();
    CameraCredentialStore store = new CameraCredentialStore(context, namespace);
    try {
      try (CameraQrCredentials qr = CameraQrCredentials.parse(VECTOR)) {
        store.save(qr);
      }
      require(store.names().equals(Collections.singletonList("BLE-TEST")), "Stored names");
      checkCode(new CameraCredentialStore(context, namespace), "123456");
      checkWifi(store, "CAM-TEST", 1, "12345678");
      require(store.passcode("ANOTHER-CAMERA") == null, "Cross-camera credential lookup");
      File directory = new File(context.getNoBackupFilesDir(), namespace);
      File[] records = directory.listFiles((parent, name) -> name.endsWith(".bin"));
      require(records != null && records.length == 1, "One encrypted record");
      byte[] cipher = read(records[0]);
      String raw = new String(cipher, StandardCharsets.ISO_8859_1);
      require(
          !raw.contains("BLE-TEST")
              && !raw.contains("123456")
              && !raw.contains("CAM-TEST")
              && !raw.contains("12345678"),
          "No plaintext in storage");
      require(!records[0].getName().contains("BLE-TEST"), "Hashed profile filename");
      CameraCredentialStore failing =
          new CameraCredentialStore(
              context,
              namespace,
              () -> {
                throw new IOException("Injected post-replacement failure");
              });
      try (CameraQrCredentials replacement = CameraQrCredentials.parse(replacement())) {
        try {
          failing.save(replacement);
          throw new AssertionError("Injected replacement failure ignored");
        } catch (IOException expected) {
        }
      }
      checkCode(new CameraCredentialStore(context, namespace), "123456");
      checkWifi(new CameraCredentialStore(context, namespace), "CAM-TEST", 1, "12345678");
      try (CameraQrCredentials first = CameraQrCredentials.parse(VECTOR.replace("UKR1CRDC", "V"))) {
        try {
          failing.save(first);
          throw new AssertionError("Injected first registration failure ignored");
        } catch (IOException expected) {
        }
      }
      require(
          store.passcode("A") == null
              && store.names().equals(Collections.singletonList("BLE-TEST")),
          "Failed first registration remains absent");
      try (CameraQrCredentials replacement = CameraQrCredentials.parse(replacement())) {
        store.save(replacement);
      }
      checkCode(store, "6789AB");
      checkWifi(store, "A", 2, "6789AB12");
      require(read(records[0]).length <= 256, "Bounded encrypted profile");
      for (String invalid :
          new String[] {
            "", "../", "A".repeat(64), CameraCredentialStore.profileId("BLE-TEST") + ".bin"
          }) {
        try {
          store.registration(invalid);
          throw new AssertionError("Invalid opaque reference accepted");
        } catch (IOException expected) {
        }
      }
      require(
          store.registration(CameraCredentialStore.profileId("ABSENT")) == null,
          "Absent profile reference");
      byte[] changed = read(records[0]);
      changed[changed.length - 1] ^= 1;
      try (FileOutputStream output = new FileOutputStream(records[0])) {
        output.write(changed);
        output.getFD().sync();
      }
      try {
        store.passcode("BLE-TEST");
        throw new AssertionError("Tampered credential accepted");
      } catch (IOException expected) {
      }
      store.remove("BLE-TEST");
      require(store.names().isEmpty() && store.passcode("BLE-TEST") == null, "Removal persisted");
      installLegacy(context, namespace);
      checkLegacy(new CameraCredentialStore(context, namespace));
      try (CameraQrCredentials upgrade =
          CameraQrCredentials.parse(VECTOR.replace("UKR1CRDC", "V"))) {
        try {
          failing.save(upgrade);
          throw new AssertionError("Failed legacy upgrade accepted");
        } catch (IOException expected) {
        }
      }
      checkLegacy(new CameraCredentialStore(context, namespace));
      store.remove("A");
      String maximum =
          "OIS3,3,2,"
              + "V".repeat(32)
              + ","
              + "-".repeat(63)
              + ","
              + "U".repeat(32)
              + ","
              + "-".repeat(12);
      try (CameraQrCredentials qr = CameraQrCredentials.parse(maximum)) {
        store.save(qr);
      }
      String maximumName = "B".repeat(32);
      File maximumRecord =
          new File(directory, CameraCredentialStore.profileId(maximumName) + ".bin");
      require(maximumRecord.length() <= 256, "Largest profile fits record bound");
      try (CameraCredentialStore.Registration maximumProfile =
          store.registration(CameraCredentialStore.profileId(maximumName))) {
        require(maximumProfile != null && maximumProfile.hasWifi(), "Largest profile roundtrip");
      }
      store.remove(maximumName);
      // Eight records remain supported; a ninth cannot evict or replace another camera.
      for (int i = 0; i < 8; i++) {
        try (CameraQrCredentials qr =
            CameraQrCredentials.parse(
                VECTOR.replace("UKR1CRDC", String.valueOf((char) ('V' - i))))) {
          store.save(qr);
        }
      }
      require(store.names().size() == 8, "Eight profiles supported");
      try (CameraQrCredentials qr = CameraQrCredentials.parse(VECTOR.replace("UKR1CRDC", "N"))) {
        try {
          store.save(qr);
          throw new AssertionError("Ninth profile accepted");
        } catch (IOException expected) {
        }
      }
      require(store.names().size() == 8, "Limit preserves profiles");
      File oversized = new File(directory, CameraCredentialStore.profileId("A") + ".bin");
      try (FileOutputStream output = new FileOutputStream(oversized)) {
        output.write(new byte[257]);
        output.getFD().sync();
      }
      try {
        store.registration(CameraCredentialStore.profileId("A"));
        throw new AssertionError("Oversized record accepted");
      } catch (IOException expected) {
      }
      return "PASS camera-credentials wifiRoundtrip=true legacyV1=true profileLimit=true encrypted=true exactName=true replacement=true failedReplacementPreserved=true failedFirstAbsent=true tamperingRejected=true removed=true";
    } finally {
      store.remove("BLE-TEST");
      for (int i = 0; i < 8; i++) store.remove(String.valueOf((char) ('A' + i)));
      store.remove("B".repeat(32));
      File directory = new File(context.getNoBackupFilesDir(), namespace);
      require(!directory.exists() || directory.delete(), "Synthetic directory cleanup");
    }
  }

  public static String seed(Instrumentation instrumentation) throws Exception {
    Context context = instrumentation.getTargetContext();
    CameraCredentialStore store = new CameraCredentialStore(context, RESTART);
    try (CameraQrCredentials qr = CameraQrCredentials.parse(VECTOR)) {
      store.save(qr);
    }
    CameraCredentialStore failing =
        new CameraCredentialStore(
            context,
            RESTART,
            () -> {
              throw new IOException("Injected post-replacement failure");
            });
    try (CameraQrCredentials qr = CameraQrCredentials.parse(replacement())) {
      try {
        failing.save(qr);
        throw new AssertionError("Injected restart replacement failure ignored");
      } catch (IOException expected) {
      }
    }
    installLegacy(context, RESTART);
    return "PASS camera-credentials restart-seeded failedReplacement=true legacyV1=true";
  }

  public static String verify(Instrumentation instrumentation) throws Exception {
    Context context = instrumentation.getTargetContext();
    CameraCredentialStore store = new CameraCredentialStore(context, RESTART);
    try {
      checkCode(store, "123456");
      checkWifi(store, "CAM-TEST", 1, "12345678");
      checkLegacy(store);
      return "PASS camera-credentials processRestart=true wifiRoundtrip=true legacyV1=true";
    } finally {
      store.remove("BLE-TEST");
      store.remove("A");
      File directory = new File(context.getNoBackupFilesDir(), RESTART);
      require(!directory.exists() || directory.delete(), "Restart fixture cleanup");
    }
  }

  private static String replacement() {
    return VECTOR
        .replace("OIS2,3,", "OIS3,3,2,")
        .replace("TVJ1CRDC", "V")
        .replace("-+*%$ZYX,", "ZYXWVU-+,")
        .replace("UKR1CRDC,-+*%$Z", "UKR1CRDC,ZYXWVU");
  }

  private static void checkWifi(
      CameraCredentialStore store, String ssid, int security, String expected) throws Exception {
    String id = CameraCredentialStore.profileId("BLE-TEST");
    try (CameraCredentialStore.Registration profile = store.registration(id)) {
      require(
          profile != null
              && profile.id.equals(id)
              && profile.bleName.equals("BLE-TEST")
              && profile.hasWifi()
              && profile.ssid.equals(ssid)
              && profile.wifiSecurity == security,
          "Complete profile identity");
      char[] wifi = profile.wifiPassphrase();
      try {
        require(Arrays.equals(wifi, expected.toCharArray()), "Wi-Fi credential readback");
      } finally {
        Arrays.fill(wifi, '\0');
      }
      char[] another = profile.wifiPassphrase();
      try {
        require(Arrays.equals(another, expected.toCharArray()), "Owned Wi-Fi copy");
      } finally {
        Arrays.fill(another, '\0');
      }
      profile.close();
      try {
        profile.wifiPassphrase();
        throw new AssertionError("Closed profile reused");
      } catch (IllegalStateException expectedClosed) {
      }
    }
  }

  /** Write an actual version-1 AES-GCM fixture using its original AAD and the real Keystore key. */
  private static void installLegacy(Context context, String namespace) throws Exception {
    CameraCredentialStore store = new CameraCredentialStore(context, namespace);
    try (CameraQrCredentials qr = CameraQrCredentials.parse(VECTOR.replace("UKR1CRDC", "V"))) {
      store.save(qr);
    }
    String filename = CameraCredentialStore.profileId("A") + ".bin";
    KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
    keys.load(null);
    SecretKey key = (SecretKey) keys.getKey("loupe." + namespace + "." + filename, null);
    byte[] plain = new byte[] {1, 'A', 6, '1', '2', '3', '4', '5', '6'};
    byte[] encrypted = null, record = null;
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key);
      cipher.updateAAD(filename.getBytes(StandardCharsets.US_ASCII));
      encrypted = cipher.doFinal(plain);
      record = new byte[13 + encrypted.length];
      record[0] = 1;
      System.arraycopy(cipher.getIV(), 0, record, 1, 12);
      System.arraycopy(encrypted, 0, record, 13, encrypted.length);
      try (FileOutputStream output =
          new FileOutputStream(
              new File(new File(context.getNoBackupFilesDir(), namespace), filename))) {
        output.write(record);
        output.getFD().sync();
      }
    } finally {
      Arrays.fill(plain, (byte) 0);
      if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
      if (record != null) Arrays.fill(record, (byte) 0);
    }
  }

  private static void checkLegacy(CameraCredentialStore store) throws Exception {
    try (CameraCredentialStore.Registration legacy =
        store.registration(CameraCredentialStore.profileId("A"))) {
      require(
          legacy != null
              && legacy.bleName.equals("A")
              && !legacy.hasWifi()
              && legacy.ssid == null
              && legacy.wifiSecurity == 0
              && legacy.wifiPassphrase() == null,
          "Legacy remains incomplete");
      char[] code = legacy.passcode();
      try {
        require(Arrays.equals(code, "123456".toCharArray()), "Legacy BLE credential preserved");
      } finally {
        Arrays.fill(code, '\0');
      }
    }
  }

  private static byte[] read(File file) throws IOException {
    try (FileInputStream input = new FileInputStream(file);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[256];
      int count;
      while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
      return output.toByteArray();
    }
  }

  private static void checkCode(CameraCredentialStore store, String expected) throws IOException {
    char[] code = store.passcode("BLE-TEST");
    try {
      require(Arrays.equals(code, expected.toCharArray()), "Credential readback");
    } finally {
      if (code != null) Arrays.fill(code, '\0');
    }
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
