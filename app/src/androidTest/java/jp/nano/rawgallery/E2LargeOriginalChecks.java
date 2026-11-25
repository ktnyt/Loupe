package jp.nano.rawgallery;

import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.json.JSONObject;

/** An opt-in exact-version transfer of one synthetic MP4 larger than the old limit. */
final class E2LargeOriginalChecks {
  static String run(Instrumentation test, String token) throws Exception {
    Context context = test.getTargetContext();
    if (!"jp.nano.rawgallery.e2verify".equals(context.getPackageName()))
      throw new AssertionError("Requires the verification APK");
    File credential = new File(context.getCacheDir(), "e2-background-credentials");
    List<String> config;
    try {
      if (credential.length() > 512) throw new AssertionError("Invalid test credentials input");
      config = Files.readAllLines(credential.toPath());
    } finally {
      Files.deleteIfExists(credential.toPath());
    }
    if (token == null || !token.matches("[0-9a-f]{12}") || config.size() != 3
        || !"loupe-test".equals(config.get(0)) || !"1".equals(shell(test, "getprop ro.kernel.qemu"))
        || Integer.parseInt(shell(test, "am get-current-user")) != android.os.Process.myUid() / 100000
        || android.os.Process.myUid() / 100000 == 0 || !E2Settings.configuredBucket(context).isEmpty())
      throw new AssertionError("Requires loupe-test and a separate emulator user");
    long size = E2Archive.LIMIT + 1024 * 1024;
    File source = File.createTempFile("loupe-large-video-", ".mp4", context.getCacheDir());
    String bucket = config.get(0);
    String name = "loupe-large-test-" + token + ".mp4";
    long started = android.os.SystemClock.elapsedRealtime();
    try (E2Photos photos = new E2Photos(context)) {
      if (!photos.settings.records.all().isEmpty()) throw new AssertionError("Test app data is not empty");
      long sourceBytes;
      try (InputStream input = test.getContext().getAssets().open("video-synthetic.mp4");
          RandomAccessFile file = new RandomAccessFile(source, "rw")) {
        byte[] initial = input.readAllBytes();
        file.write(initial);
        file.writeInt(Math.toIntExact(size - initial.length));
        file.writeBytes("free");
        file.seek(size - 1);
        file.writeByte(0);
        file.getFD().sync();
        sourceBytes = file.length();
      }
      if (sourceBytes != size) throw new AssertionError("Unexpected fixture size");
      String sha;
      try (InputStream input = Files.newInputStream(source.toPath())) {
        sha = E2Archive.readHash(input, null, size);
      }
      photos.settings.connect(bucket, config.get(1), config.get(2));
      photos.save(Uri.fromFile(source), name, bucket);
      List<JSONObject> records = photos.settings.records.all();
      if (records.size() != 1) throw new AssertionError("Expected only one protected fixture");
      JSONObject row = records.get(0);
      if (!"verified".equals(row.getString("state")) || size != row.getLong("size")
          || !sha.equals(row.getString("sha")) || !"video/mp4".equals(row.getString("mime")))
        throw new AssertionError("Large upload receipt was not confirmed");
      File downloaded = photos.downloadRecord(row);
      try (InputStream input = Files.newInputStream(downloaded.toPath())) {
        if (!sha.equals(E2Archive.readHash(input, null, size))) throw new AssertionError("Restored bytes differ");
      } finally {
        E2Photos.discard(downloaded);
      }
      return "PASS large MP4: bytes=" + size + ", SHA-256 " + sha
          + ", elapsed_ms=" + (android.os.SystemClock.elapsedRealtime() - started);
    } finally {
      Files.deleteIfExists(source.toPath());
      context.getSharedPreferences("e2_connection", Context.MODE_PRIVATE).edit().clear().commit();
    }
  }

  static String cache(Instrumentation test) throws Exception {
    Context context = test.getTargetContext();
    if (!"jp.nano.rawgallery.e2verify".equals(context.getPackageName())
        || !"1".equals(shell(test, "getprop ro.kernel.qemu")))
      throw new AssertionError("Requires a dedicated emulator");
    File cache = context.getCacheDir();
    File interrupted = File.createTempFile("e2-save-", ".part", cache);
    File orphan = new File(Files.createTempDirectory(cache.toPath(), "e2-export-").toFile(), "original");
    File protectedCopy = new File(Files.createTempDirectory(cache.toPath(), "e2-export-").toFile(), "original");
    Files.write(orphan.toPath(), new byte[] {1});
    Files.write(protectedCopy.toPath(), new byte[] {2});
    File waitingExpiry = new File(Files.createTempDirectory(cache.toPath(), "e2-export-").toFile(), "original");
    Files.write(waitingExpiry.toPath(), new byte[] {3});
    Files.createFile(new File(waitingExpiry.getParentFile(), "picker").toPath());
    File marker = new File(protectedCopy.getParentFile(), "picker");
    Files.createFile(marker.toPath());
    Files.setLastModifiedTime(marker.toPath(), java.nio.file.attribute.FileTime.fromMillis(1));
    E2Photos.holdForPicker(protectedCopy);
    Files.setLastModifiedTime(marker.toPath(), java.nio.file.attribute.FileTime.fromMillis(1));
    try (E2Photos photos = new E2Photos(context)) {
      try {
        photos.save(Uri.fromFile(new File(cache, "absent-e2-fixture")), "cache-check.mp4", "loupe-test");
        throw new AssertionError("Expected source to be missing");
      } catch (java.io.IOException expected) {
        if (interrupted.exists() || orphan.exists() || !protectedCopy.isFile() || !waitingExpiry.isFile())
          throw new AssertionError("Initial staging cleanup ignored a current picker or valid lease");
      }
      try {
        photos.stage(() -> new java.io.ByteArrayInputStream(new byte[] {1}), 2);
        throw new AssertionError("Truncated source was accepted");
      } catch (java.io.IOException expected) {
        if (!"オリジナルのサイズが変わりました".equals(expected.getMessage())) throw expected;
      }
      E2Photos.discardInterruptedStaging(cache,
          System.currentTimeMillis() + java.util.concurrent.TimeUnit.DAYS.toMillis(1));
      if (waitingExpiry.exists() || !protectedCopy.isFile())
        throw new AssertionError("Same-process cleanup did not reclaim the expired lease");
      verifyProtectedBytes(protectedCopy);
      verifyDiskReserve(cache);
      android.content.SharedPreferences settings = E2AutoSync.state(context);
      int before = settings.getInt("fullMediaConsent", 0);
      try {
        settings.edit().putInt("fullMediaConsent", 1).commit();
        if (E2SyncJob.fullMediaConsent(context)) throw new AssertionError("Old size consent still accepted");
      } finally { settings.edit().putInt("fullMediaConsent", before).commit(); }
      return "PASS large original staging: orphan and expired lease removed, active picker protected, "
          + "short and altered originals rejected, storage reserve protected, old consent rejected";
    } finally {
      Files.deleteIfExists(interrupted.toPath());
      E2Photos.discard(orphan);
      E2Photos.discard(waitingExpiry);
      E2Photos.discard(protectedCopy);
    }
  }

  private static void verifyProtectedBytes(File file) throws Exception {
    byte[] bytes = {7, 8};
    var restore = new E2Photos.PreparedRestore(file, bytes.length, E2Archive.sha256(bytes));
    Files.write(file.toPath(), bytes);
    restore.verifyStagedFile();
    for (byte[] wrong : new byte[][] {{7}, {7, 9}}) {
      Files.write(file.toPath(), wrong);
      try {
        restore.verifyStagedFile();
        throw new AssertionError("Altered staged restore was accepted");
      } catch (java.io.IOException expected) {
        // The verified original must be checked before opening the SAF destination.
      }
    }
  }

  private static void verifyDiskReserve(File cache) throws Exception {
    File file = File.createTempFile("e2-budget-", ".part", cache);
    try (var output = new E2Archive.TemporaryOutput(file,
        () -> E2Archive.STORAGE_RESERVE + 2 - file.length())) {
      output.write(new byte[] {1, 2});
      try {
        output.write(new byte[] {3});
        throw new AssertionError("Temporary write crossed the storage reserve");
      } catch (java.io.IOException expected) {
        if (file.length() != 2) throw new AssertionError("Refused write changed the file");
      }
    } finally {
      Files.deleteIfExists(file.toPath());
    }
  }

  private static String shell(Instrumentation test, String command) throws Exception {
    try (InputStream out = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
        test.getUiAutomation().executeShellCommand(command))) {
      return new String(out.readAllBytes(), StandardCharsets.UTF_8).trim();
    }
  }
}
