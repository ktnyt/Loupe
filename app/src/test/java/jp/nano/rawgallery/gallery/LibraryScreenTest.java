package jp.nano.rawgallery.gallery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class LibraryScreenTest {
  @ParameterizedTest
  @CsvSource({
    "true, false, false, false, false, true,  ALBUMS,       false",
    "false,false, false, false, false, false, ALL_PHOTOS,   true",
    "false,false, false, false, false, true,  ALBUM_PHOTOS, true",
    "false,false, false, false, true,  true,  BURST,        false",
    "false,false, false, true,  true,  true,  SELECTION,    false",
    "false,true,  false, true,  true,  true,  PHOTO,        false",
    "false,true,  true,  true,  true,  true,  RANKING,      false"
  })
  void resolvesCurrentScreenAndIndexDestination(
      boolean albumHome,
      boolean detail,
      boolean ranking,
      boolean selecting,
      boolean insideBurst,
      boolean insideAlbum,
      LibraryScreen expected,
      boolean publishesPhotoList) {
    LibraryScreen shown =
        LibraryScreen.shown(albumHome, detail, ranking, selecting, insideBurst, insideAlbum);
    assertEquals(expected, shown);
    assertEquals(publishesPhotoList, shown.showsPhotoList());
  }
}
