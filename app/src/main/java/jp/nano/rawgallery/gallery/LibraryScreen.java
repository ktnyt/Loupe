package jp.nano.rawgallery.gallery;

/** Active library screen for system Back and for applying a completed index read. */
public enum LibraryScreen {
  ALBUMS,
  ALL_PHOTOS,
  ALBUM_PHOTOS,
  BURST,
  SELECTION,
  PHOTO,
  RANKING;

  public static LibraryScreen shown(
      boolean albumHome,
      boolean detail,
      boolean ranking,
      boolean selecting,
      boolean insideBurst,
      boolean insideAlbum) {
    // Ranking also shows as detail, and a photo can temporarily cover the selection.
    if (ranking) return RANKING;
    if (detail) return PHOTO;
    if (selecting) return SELECTION;
    if (insideBurst) return BURST;
    if (albumHome) return ALBUMS;
    return insideAlbum ? ALBUM_PHOTOS : ALL_PHOTOS;
  }

  public boolean showsPhotoList() {
    return this == ALL_PHOTOS || this == ALBUM_PHOTOS;
  }
}
