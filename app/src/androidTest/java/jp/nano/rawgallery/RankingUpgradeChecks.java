package jp.nano.rawgallery;

import static jp.nano.rawgallery.RankingUpgradeSeedChecks.require;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read-only proof after an externally performed, data-preserving APK update. */
final class RankingUpgradeChecks {
  static String run(Instrumentation test, Bundle arguments) throws Exception {
    Context context = RankingUpgradeSeedChecks.guard(test, arguments);
    PackageInfo installed = RankingUpgradeSeedChecks.packageInfo(context);
    SharedPreferences ranks =
        RankingUpgradeSeedChecks.prefs(context, RankingUpgradeSeedChecks.RANKS);
    SharedPreferences witness =
        RankingUpgradeSeedChecks.prefs(context, RankingUpgradeSeedChecks.WITNESS);
    long oldVersion = witness.getLong("oldVersion", -1);
    long firstInstall = witness.getLong("firstInstallTime", -1);
    require(
        oldVersion == RankingUpgradeSeedChecks.OLD_VERSION
            && installed.getLongVersionCode() > oldVersion
            && firstInstall > 0
            && installed.firstInstallTime == firstInstall,
        "updated version and original install time");
    String oldHash = witness.getString("xmlSha256", "");
    require(
        oldHash.matches("[0-9a-f]{64}")
            && oldHash.equals(RankingUpgradeSeedChecks.xmlHash(context)),
        "unchanged seeded ranking XML before read");

    List<RankingUpgradeSeedChecks.Burst> bursts = RankingUpgradeSeedChecks.bursts();
    List<RankingUpgradeSeedChecks.Single> singles = RankingUpgradeSeedChecks.singles();
    Map<String, Object> expected = new HashMap<>();
    Map<String, ?> actual = ranks.getAll();
    expected.put(RankingUpgradeSeedChecks.EXTRA_SET, RankingUpgradeSeedChecks.SET_VALUES);
    for (var burst : bursts) {
      if (burst.raw() != null) expected.put(burst.key(), burst.raw());
    }
    for (var single : singles) {
      if (single.raw() != null) expected.put(single.key(), single.raw());
    }
    require(expected.equals(actual), "exact legacy raw entries, types and unrelated set");

    RankingStore store = new RankingStore(ranks);
    RankingStore.Snapshot snapshot = store.awaitReady();
    require(
        snapshot.phase() == RankingStore.Phase.READY && snapshot.revision() == 0,
        "fresh committed ranking snapshot");
    int usable = 0;
    for (int index = 0; index < bursts.size(); index++) {
      var fixture = bursts.get(index);
      RankingStore.Burst loaded = snapshot.burst(fixture.members());
      require(
          Objects.equals(fixture.raw(), loaded.storedJournal()),
          "absent, empty and explicit raw journals remain distinct");
      if (index == bursts.size() - 1) {
        try {
          loaded.ranking();
          throw new AssertionError("malformed journal was accepted");
        } catch (IllegalArgumentException expectedError) {
          require(!witness.contains("oldModel-" + index), "malformed journal absent from witness");
        }
      } else {
        PhotoRanking first = loaded.ranking();
        String old = witness.getString("oldModel-" + index, "");
        require(old.equals(RankingUpgradeSeedChecks.describe(first)), "same ranking and undo");
        first.order.clear();
        require(
            old.equals(RankingUpgradeSeedChecks.describe(loaded.ranking())),
            "ranking reads return detached models");
        usable++;
      }
    }
    int readableSingles = 0;
    for (var single : singles) {
      if (single.raw() instanceof Integer) {
        try {
          snapshot.singleBest(single.id());
          throw new AssertionError("wrong-typed target was accepted");
        } catch (IOException expectedError) {
          // One malformed target does not prevent correct reads of other entries.
        }
      } else {
        require(
            snapshot.singleBest(single.id()) == "1".equals(single.raw()), "same singleton rank");
        readableSingles++;
      }
    }
    require(
        snapshot.singleBest(singles.get(2).id())
            && store.snapshot() == snapshot
            && store.snapshot().revision() == 0
            && witness.getAll().size() == usable + 3
            && oldHash.equals(RankingUpgradeSeedChecks.xmlHash(context))
            && expected.equals(ranks.getAll()),
        "reads made no ranking writes and kept all independent values");
    RankingUpgradeSeedChecks.requireEmptyData(context);
    return "PASS ranking upgrade verify bursts="
        + usable
        + " singletons="
        + readableSingles
        + " originalVersion="
        + oldVersion
        + " installedVersion="
        + installed.getLongVersionCode()
        + " signerSame=true firstInstallSame=true xmlSame=true detached=true revision=0"
        + " defaultDataEmpty=true\n";
  }
}
