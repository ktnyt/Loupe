package jp.nano.rawgallery;

import java.util.Arrays;

/** Local video discovery; decoder/codec support is provided by Android. */
final class VideoFormats {
  private static final String EXTENSIONS = "mp4|m4v|mov|3gp|3gpp|mkv|webm";

  static boolean accepts(String name) { return RawFormats.extension(name).matches(EXTENSIONS); }

  static String[] patterns() {
    return Arrays.stream(EXTENSIONS.split("\\|")).map(e -> "%." + e).toArray(String[]::new);
  }

  static String mime(String name) {
    return switch (RawFormats.extension(name)) {
      case "mp4", "m4v" -> "video/mp4";
      case "mov" -> "video/quicktime";
      case "3gp", "3gpp" -> "video/3gpp";
      case "mkv" -> "video/x-matroska";
      case "webm" -> "video/webm";
      default -> throw new IllegalArgumentException("未対応の動画形式です");
    };
  }
}
