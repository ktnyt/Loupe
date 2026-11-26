package jp.nano.rawgallery;

import android.app.Dialog;
import android.app.Instrumentation;
import android.widget.Button;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Uses actual selection controls and an export writer that never sends an intent. */
final class ShareSelectionResultChecks {
  static void run(
      Instrumentation t,
      MainActivity a,
      List<PhotoRepository.Photo> library,
      File fixtures,
      RankingFixture rankings)
      throws Exception {
    PhotoRepository.Photo first = library.get(4), second = library.get(5), single = library.get(6);
    List<Integer> burst = List.of(0, 1);
    List<PhotoRepository.Photo> photos = List.of(first, second, single);
    String secondId = ArchiveStore.key(second);
    PhotoRanking undecided = rankings.startBurst(List.of(ArchiveStore.key(first), secondId), "B:");
    PhotoRanking ranked = undecided.choose(undecided.upper().equals(secondId));
    PhotoRepository.Photo best = second;
    AtomicReference<PhotoRepository.Photo> attempted = new AtomicReference<>();
    File exportDir = new File(fixtures, "selection-export");
    try {
      rankings.save(ranked);
      TagDetailChecks.ui(
          t,
          () -> {
            RankingResultChecks.set(a, "photos", photos);
            RankingResultChecks.set(a, "bundles", List.of(burst, List.of(2)));
            RankingResultChecks.set(a, "bestOnly", true);
            choose(a, first, second);
            RankingResultChecks.call(a, "gallery");
            Object presented = RankingResultChecks.get(a, "presentedSelection");
            ShareUi.Menu menu = menu(a, List.of(best));
            TagDetailChecks.require(
                presented != null && presented == RankingResultChecks.get(a, "presentedSelection"),
                "unchanged selection uses the displayed result");
            RankingResultChecks.set(
                a,
                "sharePreparation",
                new SharePreparation(
                    exportDir,
                    (photo, raw, destination) -> {
                      attempted.set(photo);
                      throw new IOException("Synthetic export stopped");
                    }));
            choose(a, single);
            RankingResultChecks.call(a, "updateSelection");
            menu.continueButton.performClick();
          });
      ShareScreenChecks.worker(t, a);
      TagDetailChecks.ui(
          t,
          () -> {
            TagDetailChecks.require(
                attempted.get() == best, "opened menu exports its frozen photos");
            ((Dialog) RankingResultChecks.get(a, "shareDialog")).dismiss();
            menu(a, List.of(single)).dismiss();
          });
      rankings.save(undecided);
      TagDetailChecks.ui(
          t,
          () -> {
            choose(a, first, second, single);
            RankingResultChecks.call(a, "gallery");
            share(a).performClick();
            Dialog confirmation = dialog(a);
            TagDetailChecks.require(
                confirmation instanceof ShareUi.Confirmation,
                "unresolved burst opens confirmation");
            Button partial =
                TagDetailChecks.button(confirmation.getWindow().getDecorView(), "順位が未決定の連写を除いて共有");
            choose(a, first, second);
            RankingResultChecks.call(a, "updateSelection");
            partial.performClick();
            TagDetailChecks.require(
                dialog(a) == confirmation && !confirmation.isShowing(),
                "confirmation cannot share a deselected photo");
          });

      // Replace the loaded photo snapshot on the UI thread, retaining the URI.
      PhotoRepository.Photo reloaded =
          new PhotoRepository.Photo(
              single.uri, single.name, single.folder, single.size, single.modified + 1, 0);
      TagDetailChecks.ui(
          t,
          () -> {
            choose(a, single);
            RankingResultChecks.call(a, "updateSelection");
            RankingResultChecks.set(a, "photos", List.of(first, second, reloaded));
            menu(a, List.of(reloaded)).dismiss();

            RankingResultChecks.call(a, "endSelection");
            RankingResultChecks.set(a, "albumFolder", "/fixture/second");
            RankingResultChecks.set(a, "burstMembers", new ArrayList<>(burst));
            RankingResultChecks.call(a, "beginSelection");
            choose(a, first, second);
            RankingResultChecks.set(a, "bestOnly", false);
            RankingResultChecks.call(a, "updateSelection");
            menu(a, List.of(first, second)).dismiss();
          });
      checkLateCapacity(t, a, first, second);
    } finally {
      File[] children = exportDir.listFiles();
      if (children != null) for (File file : children) file.delete();
      exportDir.delete();
    }
  }

  private static void checkLateCapacity(
      Instrumentation t, MainActivity a, PhotoRepository.Photo first, PhotoRepository.Photo second)
      throws Exception {
    long size = 300L * 1024 * 1024;
    PhotoRepository.Photo raw1 =
        new PhotoRepository.Photo(first.uri, "a.orf", first.folder, size, 1, 0);
    PhotoRepository.Photo raw2 =
        new PhotoRepository.Photo(second.uri, "b.orf", second.folder, size, 1, 0);
    TagDetailChecks.ui(
        t,
        () -> {
          RankingResultChecks.set(a, "photos", List.of(raw1, raw2));
          RankingResultChecks.set(a, "bundles", List.of(List.of(0), List.of(1)));
          RankingResultChecks.set(a, "burstMembers", null);
          ShareCapacityChecks capacity =
              (ShareCapacityChecks) RankingResultChecks.get(a, "shareCapacity");
          capacity.observed(Map.of(ShareCapacity.key(raw1), 20L, ShareCapacity.key(raw2), 20L));
          choose(a, raw1, raw2);
          RankingResultChecks.call(a, "gallery");
          TagDetailChecks.require(share(a).isEnabled(), "measured JPEGs fit initially");
          // A new size arrives before the share control re-renders.
          capacity.observed(Map.of(ShareCapacity.key(raw1), size, ShareCapacity.key(raw2), size));
          Dialog before = dialog(a);
          share(a).performClick();
          TagDetailChecks.require(
              dialog(a) == before, "latest capacity blocks the previously enabled action");
        });
  }

  @SuppressWarnings("unchecked")
  private static void choose(MainActivity a, PhotoRepository.Photo... photos) {
    Set<String> chosen = (Set<String>) RankingResultChecks.get(a, "selectedPhotos");
    chosen.clear();
    for (PhotoRepository.Photo photo : photos) chosen.add(photo.uri.toString());
  }

  private static Button share(MainActivity a) {
    return (Button) RankingResultChecks.get(a, "selectionShare");
  }

  private static Dialog dialog(MainActivity a) {
    return (Dialog) RankingResultChecks.get(a, "shareDialog");
  }

  private static ShareUi.Menu menu(MainActivity a, List<PhotoRepository.Photo> expected) {
    TagDetailChecks.require(
        share(a).isEnabled() && share(a).performClick(), "enabled share action");
    Dialog shown = dialog(a);
    TagDetailChecks.require(shown instanceof ShareUi.Menu && shown.isShowing(), "share menu shown");
    ShareUi.Menu menu = (ShareUi.Menu) shown;
    TagDetailChecks.require(
        menu.photos.equals(expected), "share menu uses exactly the expected photos");
    return menu;
  }
}
