package jp.nano.rawgallery;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import jp.nano.rawgallery.security.EncryptedSecretStore;
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

  static boolean hasPhotoAccess(Context context) {
    return context.checkSelfPermission(imagePermission()) == PackageManager.PERMISSION_GRANTED
        && context.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
  }

  static boolean hasMediaAccess(Context context) {
    return hasPhotoAccess(context)
        && (Build.VERSION.SDK_INT < 33
            || context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO)
                == PackageManager.PERMISSION_GRANTED);
  }

  static String imagePermission() {
    return Build.VERSION.SDK_INT >= 33
        ? android.Manifest.permission.READ_MEDIA_IMAGES
        : android.Manifest.permission.READ_EXTERNAL_STORAGE;
  }

  static String configuredBucket(Context context) {
    return context
        .getSharedPreferences("e2_connection", Context.MODE_PRIVATE)
        .getString("bucket", "");
  }

  static ArchiveStore openRecords(Context context) {
    return new ArchiveStore(context, DATABASE);
  }

  boolean configured() {
    return preferences.contains(CONFIG);
  }

  synchronized void connect(String bucket, String access, String secret) throws Exception {
    E2Archive.forPhotos(records, bucket, access, secret).checkConnection();
    JSONObject config =
        new JSONObject().put("bucket", bucket).put("access", access).put("secret", secret);
    Cipher cipher = EncryptedSecretStore.encryptionCipher(ALIAS);
    byte[] encrypted = cipher.doFinal(config.toString().getBytes(StandardCharsets.UTF_8));
    String encoded =
        Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
            + "."
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
    Cipher cipher =
        EncryptedSecretStore.decryptionCipher(
            ALIAS,
            () -> new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
            "接続設定の暗号鍵を読み込めません");
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
    return E2Archive.forPhotos(
        records,
        config.getString("bucket"),
        config.getString("access"),
        config.getString("secret"),
        context,
        network);
  }
}
