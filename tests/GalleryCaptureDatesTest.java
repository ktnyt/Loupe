package jp.nano.rawgallery;

import java.util.*;

/** Runs the real index ordering operation with synthetic photo records and no media writes. */
public final class GalleryCaptureDatesTest {
  public static void main(String[] args) throws Exception {
    PhotoRepository.Photo wrongProvider = photo("A.JPG", 90, 1, 7, 8);
    PhotoRepository.Photo missingProvider = photo("B.ORF", -1, 2, 9, 10);
    PhotoRepository.Photo validProvider = photo("C.JPG", 200, 3, 11, 12);
    PhotoRepository.Photo noExif = photo("D.JPG", 150, 4, 13, 14);
    List<PhotoRepository.Photo> photos = new ArrayList<>(
        List.of(wrongProvider, noExif, validProvider, missingProvider));
    Map<String, ArchiveStore.RawMetadata> metadata = new HashMap<>();
    metadata.put(ArchiveStore.key(wrongProvider), new ArchiveStore.RawMetadata("Synthetic", 100));
    metadata.put(ArchiveStore.key(missingProvider), new ArchiveStore.RawMetadata("Synthetic", 300));
    metadata.put(ArchiveStore.key(noExif), new ArchiveStore.RawMetadata("Synthetic", 0));
    GalleryIndex.orderByCapture(photos, metadata);
    check(photos.stream().map(p -> p.name).toList().equals(
        List.of("B.ORF", "C.JPG", "D.JPG", "A.JPG")), "EXIF ordering and provider fallback");
    check(photos.get(0).taken == 300, "invalid provider date replaced for browsing");
    check(photos.get(3).taken == 100, "EXIF preferred to a valid but inconsistent provider date");
    check(wrongProvider.taken == 90 && missingProvider.taken == -1, "source records unchanged");
    PhotoRepository.Photo corrected = photos.get(0);
    check(corrected.size == missingProvider.size && corrected.modified == missingProvider.modified
        && corrected.added == missingProvider.added && corrected.created == missingProvider.created
        && corrected.folder.equals(missingProvider.folder)
        && ArchiveStore.key(corrected).equals(ArchiveStore.key(missingProvider)),
        "identity, timestamps and source key preserved");

    List<BurstGroups.Entry> entries = new ArrayList<>();
    for (int i = 0; i < photos.size(); i++)
      entries.add(new BurstGroups.Entry(i, photos.get(i).taken, "synthetic-stream"));
    check(BurstGroups.group(entries, 1000).get(0).equals(List.of(0, 1, 2, 3)),
        "burst member indices preserve corrected gallery chronology");

    PhotoRepository.Photo unknown = photo("UNKNOWN.JPG", -1, 5, 0, 0);
    List<PhotoRepository.Photo> fallback = new ArrayList<>(List.of(photos.get(0), unknown));
    GalleryIndex.orderByCapture(fallback, metadata);
    check(fallback.get(0) == unknown && unknown.taken == -1,
        "fallback ordering does not invent a capture date");
    List<PhotoRepository.Photo> ties = new ArrayList<>(List.of(
        photo("Z.JPG", 400, 1, 0, 0), photo("E.JPG", 400, 2, 0, 0)));
    GalleryIndex.orderByCapture(ties, Map.of());
    check(ties.get(0).name.equals("E.JPG"), "existing deterministic name tie order retained");
    System.out.println("PASS capture dates: EXIF order, fallback, immutable source and burst indices");
    GalleryIndex.WORKER.shutdown();
  }

  private static PhotoRepository.Photo photo(String name, long taken, long modified, long added, long created) {
    return new PhotoRepository.Photo(null, name, "Synthetic/", 10, modified, taken, added, created);
  }

  private static void check(boolean valid, String label) {
    if (!valid) throw new AssertionError(label);
  }
}
