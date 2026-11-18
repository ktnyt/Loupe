package jp.nano.rawgallery;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Stores a single connection in app-private storage, encrypted by Android Keystore. */
final class E2Settings {
  private static final String ALIAS = "loupe-e2-connection";
  private static final String CONFIG = "connection";
  static final Object DESTINATION_LOCK = new Object();
  static final String DATABASE = "e2_photo_archive.db";
  private final SharedPreferences preferences;
  private final Context context;
  private final Context recordsContext;
  private final android.net.Network network;
  final ArchiveStore records;

  E2Settings(Context context) {
    this(context, null);
  }

  E2Settings(Context context, android.net.Network network) {
    recordsContext = context.getApplicationContext();
    this.context = network == null ? null : recordsContext;
    this.network = network;
    preferences = context.getSharedPreferences("e2_connection", Context.MODE_PRIVATE);
    records = openRecords(context);
  }

  static String configuredBucket(Context context) {
    return context.getSharedPreferences("e2_connection", Context.MODE_PRIVATE).getString("bucket", "");
  }

  static ArchiveStore openRecords(Context context) {
    return new ArchiveStore(context, DATABASE);
  }

  boolean configured() {
    return preferences.contains(CONFIG);
  }

  synchronized void connect(String bucket, String access, String secret) throws Exception {
    E2Archive.forPhotos(records, bucket, access, secret).checkConnection();
    JSONObject config = new JSONObject().put("bucket", bucket)
        .put("access", access).put("secret", secret);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key(true));
    byte[] encrypted = cipher.doFinal(config.toString().getBytes(StandardCharsets.UTF_8));
    String encoded = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
        + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    synchronized (DESTINATION_LOCK) {
      if (Thread.currentThread().isInterrupted()) throw new IOException("接続を中断しました");
      Context owner = recordsContext;
      if (E2SyncJob.enabled(owner) || E2SyncJob.running())
        throw new IOException("自動バックアップの停止を待ってください");
      if (!preferences.edit().putString(CONFIG, encoded).putString("bucket", bucket).commit())
        throw new IOException("接続設定を保存できません");
    }
  }

  synchronized JSONObject configuration() throws Exception {
    String encoded = preferences.getString(CONFIG, "");
    if (encoded.isEmpty()) throw new IOException("IDrive e2を設定してください");
    String[] parts = encoded.split("\\.", 2);
    if (parts.length != 2) throw new IOException("接続設定を読み込めません");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key(false),
        new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
    byte[] data = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP));
    return new JSONObject(new String(data, StandardCharsets.UTF_8));
  }

  E2Archive archive() throws Exception {
    return archive(null);
  }

  E2Archive archive(String expectedBucket) throws Exception {
    JSONObject config = configuration();
    if (expectedBucket != null && !expectedBucket.equals(config.getString("bucket")))
      throw new IOException("保存先が変わりました");
    return E2Archive.forPhotos(records, config.getString("bucket"),
        config.getString("access"), config.getString("secret"), context, network);
  }

  private static synchronized SecretKey key(boolean create) throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    SecretKey existing = (SecretKey) store.getKey(ALIAS, null);
    if (existing != null) return existing;
    if (!create) throw new IOException("接続設定の暗号鍵を読み込めません");
    KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
        "AndroidKeyStore");
    generator.init(new KeyGenParameterSpec.Builder(ALIAS,
        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .build());
    return generator.generateKey();
  }
}
