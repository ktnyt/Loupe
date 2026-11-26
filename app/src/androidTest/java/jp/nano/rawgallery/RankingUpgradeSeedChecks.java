package jp.nano.rawgallery;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.Settings;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;

/** Runs with the new instrumentation APK and the old application APK. */
final class RankingUpgradeSeedChecks {
  static final String MARKER = "loupe249-ranking-xml-v1";
  static final String RANKS = "ranking-upgrade-fixture-249-v1";
  static final String WITNESS = "ranking-upgrade-witness-249-v1";
  static final String EXTRA_SET = "fixture-unrelated-set";
  static final Set<String> SET_VALUES = Set.of("fixture-one", "fixture-two");
  static final long OLD_VERSION = 265;
  private static final String CERT =
      "dcb218bcf8fb4784a2018889b6df63d3485713c5742708e2ceaf4460ceee6e4e";

  record Burst(String raw, List<String> members) {
    List<String> canonical() {
      List<String> sorted = new ArrayList<>(members);
      sorted.sort(String::compareTo);
      return sorted;
    }

    String key() throws Exception {
      return "v1-" + sha256(String.join("|", canonical()).getBytes(StandardCharsets.UTF_8));
    }
  }

  record Single(String id, Object raw) {
    String key() throws Exception {
      return "single-best-v1-" + sha256(id.getBytes(StandardCharsets.UTF_8));
    }
  }

  static List<Burst> bursts() {
    return List.of(
        burst(0, "2"),
        burst(1, "22"),
        burst(2, "A:2"),
        burst(3, "A:20"),
        burst(4, "B:0"),
        burst(5, "B:01"),
        burst(6, null),
        burst(7, ""),
        burst(8, "A:"),
        burst(9, "B:"),
        burst(10, "invalid-fixture-journal"));
  }

  private static Burst burst(int index, String journal) {
    String prefix = "ranking-upgrade-" + index;
    return new Burst(journal, List.of(prefix + "-c", prefix + "-a", prefix + "-b"));
  }

  static List<Single> singles() {
    return List.of(
        new Single("fixture-single-absent", null),
        new Single("fixture-single-unmarked", "0"),
        new Single("fixture-single-marked", "1"),
        new Single("fixture-single-wrong-type", 1));
  }

  static String run(Instrumentation test, Bundle arguments) throws Exception {
    Context context = guard(test, arguments);
    PackageInfo installed = packageInfo(context);
    require(installed.getLongVersionCode() == OLD_VERSION, "old fixture version required");
    require(installed.firstInstallTime > 0, "old install time required");

    // Reject existing files, including backups. A failed attempt is never provisioned over.
    requireFreshFile(context, RANKS);
    requireFreshFile(context, WITNESS);
    SharedPreferences ranks = prefs(context, RANKS), witness = prefs(context, WITNESS);
    require(ranks.getAll().isEmpty() && witness.getAll().isEmpty(), "fresh fixture namespaces");
    SharedPreferences.Editor edit = ranks.edit().putStringSet(EXTRA_SET, SET_VALUES);
    List<Burst> bursts = bursts();
    List<Single> singles = singles();
    for (Burst burst : bursts) {
      if (burst.raw() != null) edit.putString(burst.key(), burst.raw());
    }
    for (Single single : singles) {
      if (single.raw() instanceof String value) edit.putString(single.key(), value);
      else if (single.raw() instanceof Integer value) edit.putInt(single.key(), value);
    }
    require(edit.commit(), "seed ranking XML");
    require(
        ranks.getAll().size() == bursts.size() + singles.size() - 1, "exact fixture entry count");

    SharedPreferences.Editor old =
        witness
            .edit()
            .putString("xmlSha256", xmlHash(context))
            .putLong("oldVersion", installed.getLongVersionCode())
            .putLong("firstInstallTime", installed.firstInstallTime);
    int usable = 0;
    for (int index = 0; index < bursts.size(); index++) {
      Burst burst = bursts.get(index);
      try {
        PhotoRanking ranking =
            new PhotoRanking(burst.canonical(), ranks.getString(burst.key(), ""));
        require(index != bursts.size() - 1, "old app rejected malformed journal");
        old.putString("oldModel-" + index, describe(ranking));
        usable++;
      } catch (IllegalArgumentException malformed) {
        require(index == bursts.size() - 1, "old app accepted valid journals");
      }
    }
    require(old.commit(), "write separate upgrade witness");
    requireEmptyData(context);
    return "PASS ranking upgrade seed bursts="
        + usable
        + " storedEntries="
        + ranks.getAll().size()
        + " originalVersion="
        + installed.getLongVersionCode()
        + " oldModelWitness=true defaultDataEmpty=true\n";
  }

  static String describe(PhotoRanking ranking) {
    JSONArray counts = new JSONArray(), ranks = new JSONArray();
    for (String member : ranking.ids) counts.put(ranking.selectionCount(member));
    for (String member : ranking.order) ranks.put(ranking.rankOf(member));
    return new JSONArray()
        .put(new JSONArray(ranking.ids))
        .put(ranking.journal)
        .put(ranking.done())
        .put(ranking.decisions())
        .put(new JSONArray(ranking.order))
        .put(new JSONArray(ranking.best()))
        .put(counts)
        .put(ranks)
        .put(ranking.undo().journal)
        .put(new JSONArray(ranking.undo().order))
        .toString();
  }

  static Context guard(Instrumentation test, Bundle arguments) throws Exception {
    require("ranchu".equals(Build.HARDWARE), "disposable emulator hardware required");
    String avd;
    try (ParcelFileDescriptor pipe =
            test.getUiAutomation().executeShellCommand("getprop ro.boot.qemu.avd_name");
        FileInputStream input = new FileInputStream(pipe.getFileDescriptor())) {
      byte[] output = new byte[128];
      int length = input.read(output);
      require(length > 0 && input.read() == -1, "unambiguous emulator name required");
      avd = new String(output, 0, length, StandardCharsets.UTF_8).trim();
    }
    require("loupe249-ranking-baseline".equals(avd), "exact ranking fixture AVD required");
    require(
        MARKER.equals(arguments.getString("ranking_upgrade_marker")), "explicit upgrade marker");
    Context context = test.getTargetContext();
    ConnectivityManager network = context.getSystemService(ConnectivityManager.class);
    require(
        Settings.Global.getInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0)
                == 1
            && network != null
            && network.getActiveNetwork() == null,
        "offline fixture requires airplane mode and no active network");
    requireEmptyData(context);
    return context;
  }

  static PackageInfo packageInfo(Context context) throws Exception {
    require("jp.nano.rawgallery".equals(context.getPackageName()), "exact application package");
    require(
        (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        "debug fixture application required");
    PackageInfo info =
        context
            .getPackageManager()
            .getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
    Signature[] signers =
        info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
    require(
        signers != null && signers.length == 1 && CERT.equals(sha256(signers[0].toByteArray())),
        "matching verification certificate");
    return info;
  }

  private static void requireFreshFile(Context context, String name) {
    File path = prefsFile(context, name);
    require(!path.exists() && !new File(path.getPath() + ".bak").exists(), "existing fixture data");
  }

  static SharedPreferences prefs(Context context, String name) {
    return context.getSharedPreferences(name, Context.MODE_PRIVATE);
  }

  private static File prefsFile(Context context, String name) {
    return new File(context.getApplicationInfo().dataDir, "shared_prefs/" + name + ".xml");
  }

  static String xmlHash(Context context) throws Exception {
    File path = prefsFile(context, RANKS);
    require(path.isFile() && path.length() > 0 && path.length() <= 16384, "fixture XML size");
    return sha256(Files.readAllBytes(path.toPath()));
  }

  private static String sha256(byte[] bytes) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
    StringBuilder result = new StringBuilder();
    for (byte value : digest) {
      result.append(Character.forDigit((value & 255) >>> 4, 16));
      result.append(Character.forDigit(value & 15, 16));
    }
    return result.toString();
  }

  static void requireEmptyData(Context context) {
    require(
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                == PackageManager.PERMISSION_GRANTED
            && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)
                == PackageManager.PERMISSION_GRANTED,
        "fixture needs complete image and video queries");
    require(prefs(context, "rankings").getAll().isEmpty(), "default rankings remain empty");
    for (Uri collection :
        List.of(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))) {
      try (Cursor photos =
          context.getContentResolver().query(collection, new String[] {"_id"}, null, null, null)) {
        require(photos != null && photos.getCount() == 0, "MediaStore remains empty");
      }
    }
  }

  static void require(boolean condition, String description) {
    if (!condition) throw new AssertionError(description);
  }
}
