package jp.nano.rawgallery;

import java.util.*;

/** Discovery allowlist, not a promise that every camera embeds a displayable JPEG. */
final class RawFormats {
  static final String RAW_EXTENSIONS =
      "3fr|ari|arq|arw|bay|bmq|cap|cr2|cr3|crw|cs1|dc2|dcr|dng|drf|eip|erf|fff|gpr|ia|iiq|k25|kc2|kdc|mdc|mef|mos|mrw|nef|nrw|obm|orf|ori|pef|ptx|pxn|qtk|raf|raw|rdc|rw1|rw2|rwl|rwz|sr2|srf|srw|sti|tif|tiff|x3f";
  static final String IMAGE_EXTENSIONS = RAW_EXTENSIONS + "|jpg|jpeg";

  static String extension(String name) {
    if (name == null) return "";
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  static boolean accepts(String name) {
    return extension(name).matches(IMAGE_EXTENSIONS);
  }

  static String originalExtension(String name) {
    String extension = extension(name);
    if (!accepts(name)) throw new IllegalArgumentException("未対応の写真拡張子です");
    return extension;
  }

  static String mime(String name) {
    return switch (extension(name)) {
      case "jpg", "jpeg" -> "image/jpeg";
      case "tif", "tiff" -> "image/tiff";
      case "dng" -> "image/x-adobe-dng";
      case "orf", "ori" -> "image/x-olympus-orf";
      case "cr2" -> "image/x-canon-cr2";
      case "cr3" -> "image/x-canon-cr3";
      case "crw" -> "image/x-canon-crw";
      case "nef" -> "image/x-nikon-nef";
      case "nrw" -> "image/x-nikon-nrw";
      case "arw" -> "image/x-sony-arw";
      case "sr2" -> "image/x-sony-sr2";
      case "srf" -> "image/x-sony-srf";
      case "raf" -> "image/x-fuji-raf";
      case "rw2" -> "image/x-panasonic-rw2";
      case "rwl" -> "image/x-leica-rwl";
      case "pef" -> "image/x-pentax-pef";
      case "srw" -> "image/x-samsung-srw";
      case "x3f" -> "image/x-sigma-x3f";
      case "mrw" -> "image/x-minolta-mrw";
      case "erf" -> "image/x-epson-erf";
      default -> "application/octet-stream";
    };
  }

  static String[] patterns() {
    return Arrays.stream(IMAGE_EXTENSIONS.split("\\|")).map(e -> "%." + e).toArray(String[]::new);
  }

  static Set<String> mimeTypes() {
    Set<String> types = new LinkedHashSet<>();
    for (String extension : IMAGE_EXTENSIONS.split("\\|")) types.add(mime("file." + extension));
    return types;
  }
}
