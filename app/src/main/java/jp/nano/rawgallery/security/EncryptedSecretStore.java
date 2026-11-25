package jp.nano.rawgallery.security;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.function.Supplier;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Creates AES-GCM ciphers without changing the caller's alias, framing, or associated data. */
public final class EncryptedSecretStore {
  private static final String PROVIDER = "AndroidKeyStore";
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";

  private EncryptedSecretStore() {}

  public static Cipher encryptionCipher(String alias) throws GeneralSecurityException, IOException {
    return encryptionCipher(alias, null);
  }

  public static Cipher encryptionCipher(String alias, Integer keyBits)
      throws GeneralSecurityException, IOException {
    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(alias, keyBits));
    return cipher;
  }

  public static Cipher decryptionCipher(
      String alias, Supplier<GCMParameterSpec> parameters, String missingKeyMessage)
      throws GeneralSecurityException, IOException {
    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    SecretKey key = (SecretKey) keyStore().getKey(alias, null);
    if (key == null) throw new IOException(missingKeyMessage);
    cipher.init(Cipher.DECRYPT_MODE, key, parameters.get());
    return cipher;
  }

  public static void deleteKey(String alias) throws GeneralSecurityException, IOException {
    keyStore().deleteEntry(alias);
  }

  private static synchronized SecretKey encryptionKey(String alias, Integer keyBits)
      throws GeneralSecurityException, IOException {
    KeyStore store = keyStore();
    SecretKey key = (SecretKey) store.getKey(alias, null);
    if (key != null) return key;
    // A present but unreadable entry must not be replaced during a normal write.
    if (store.containsAlias(alias)) throw new GeneralSecurityException("Encryption key unavailable");
    KeyGenParameterSpec.Builder spec =
        new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE);
    if (keyBits != null) spec.setKeySize(keyBits);
    KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
    generator.init(spec.build());
    return generator.generateKey();
  }

  private static KeyStore keyStore() throws GeneralSecurityException, IOException {
    KeyStore store = KeyStore.getInstance(PROVIDER);
    store.load(null);
    return store;
  }
}
