package jp.nano.rawgallery;

import java.util.List;
import java.util.Map;
import java.util.Locale;

/** Per-share output budget. Unknown export bytes are never inferred from a RAW's size. */
final class ShareCapacity {
  static final long LIMIT = 512L * 1024 * 1024;
  enum State { FIT, OVER, UNKNOWN, CHECKING, FAILED }

  static final class Result {
    final State state;
    final long bytes;
    Result(State state, long bytes) { this.state = state; this.bytes = bytes; }
    boolean actionable() { return state == State.FIT || state == State.UNKNOWN; }
    String amount() {
      return state == State.UNKNOWN || state == State.FAILED ? "?"
          : state == State.CHECKING ? "…" : format(bytes);
    }
    String label() { return amount() + " / 512 MiB"; }
    String description() {
      if (state == State.CHECKING) return "共有できる量を確認しています";
      if (state == State.FAILED) return "共有する写真のサイズを確認できませんでした";
      if (state == State.UNKNOWN) return "容量は共有の準備時に確認します。上限512 MiB";
      return "共有の量 " + format(bytes) + " MiB、上限512 MiB"
          + (state == State.OVER ? "。上限を超えています。写真を減らしてください" : "");
    }
  }

  static String key(PhotoRepository.Photo photo) {
    return String.valueOf(photo.uri) + "|" + photo.folder + photo.name + "|"
        + photo.size + "|" + photo.modified;
  }

  /** Length cache contains only final, orientation-written JPEG sizes; -1 denotes failed measurement. */
  static Result assess(List<PhotoRepository.Photo> photos, boolean original,
      Map<String, Long> jpegLengths, boolean checking) {
    long total = 0;
    boolean unknown = false, failed = false, pending = false;
    for (PhotoRepository.Photo photo : photos) {
      Long size = original || photo.isJpeg() ? Long.valueOf(photo.size) : jpegLengths.get(key(photo));
      if (size == null) {
        if (checking && !"archive".equals(photo.uri == null ? "" : photo.uri.getScheme())) pending = true;
        else unknown = true;
      } else if (size <= 0) {
        if (!original && !photo.isJpeg()) failed = true;
        else unknown = true;
      } else total = total > Long.MAX_VALUE - size ? Long.MAX_VALUE : total + size;
    }
    // Known output alone is enough to reject, even if other files cannot be measured yet.
    return new Result(total > LIMIT ? State.OVER : pending ? State.CHECKING
        : failed ? State.FAILED : unknown ? State.UNKNOWN : State.FIT, total);
  }

  /** The entry opens a format chooser: one fitting or deferred format is enough. */
  static Result available(Result jpeg, Result original) {
    if (jpeg.state == State.FIT && original.state == State.FIT)
      return jpeg.bytes <= original.bytes ? jpeg : original;
    if (jpeg.state == State.FIT) return jpeg;
    if (original.state == State.FIT) return original;
    if (jpeg.state == State.UNKNOWN) return jpeg;
    if (original.state == State.UNKNOWN) return original;
    if (jpeg.state == State.CHECKING) return jpeg;
    if (original.state == State.CHECKING) return original;
    if (jpeg.state == State.FAILED) return jpeg;
    if (original.state == State.FAILED) return original;
    return jpeg.bytes <= original.bytes ? jpeg : original;
  }

  static String format(long bytes) {
    // Round up so LIMIT+1 is visibly over, rather than displaying a misleading 512.
    long tenths = (bytes / (1024 * 1024)) * 10
        + ((bytes % (1024 * 1024)) * 10 + 1024 * 1024 - 1) / (1024 * 1024);
    return tenths % 10 == 0 ? Long.toString(tenths / 10)
        : String.format(Locale.ROOT, "%d.%d", tenths / 10, tenths % 10);
  }
}
