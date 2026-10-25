package jp.nano.rawgallery;

import android.net.Uri;
import java.util.List;

/** An in-memory anchor sampled immediately before a browsing index is applied. */
final class GalleryPosition {
  private final Uri photo;
  private final int cell, top;
  private final boolean header;

  private GalleryPosition(Uri photo, int cell, int top, boolean header) {
    this.photo = photo; this.cell = cell; this.top = top; this.header = header;
  }

  static GalleryPosition capture(ScrollingGrid grid, List<PhotoRepository.Photo> photos,
      List<List<Integer>> displayed) {
    if (grid == null || grid.getChildCount() == 0 || displayed.isEmpty()) return null;
    boolean header = grid.getFirstVisiblePosition() < grid.getHeaderViewsCount();
    int cell = Math.min(grid.firstCellPosition(), displayed.size() - 1);
    Uri photo = photos.get(displayed.get(cell).get(0)).uri;
    return new GalleryPosition(photo, cell, grid.getChildAt(0).getTop(), header);
  }

  void restore(ScrollingGrid grid, List<PhotoRepository.Photo> photos, List<List<Integer>> displayed) {
    int target = Math.min(cell, Math.max(0, displayed.size() - 1));
    if (!header) {
      search: for (int i = 0; i < displayed.size(); i++) {
        for (int member : displayed.get(i)) {
          if (photo.equals(photos.get(member).uri)) { target = i; break search; }
        }
      }
    }
    grid.restoreCell(target, top, header || displayed.isEmpty());
  }
}
