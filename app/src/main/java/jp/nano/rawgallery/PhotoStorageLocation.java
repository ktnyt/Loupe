package jp.nano.rawgallery;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import jp.nano.rawgallery.camera.CameraImportHistoryPlan;

record PhotoStorageLocation(Kind kind, Availability availability, String volume, String label, String folder) {
  private static final Pattern EXTERNAL_VOLUME = Pattern.compile("[0-9a-f]+(?:-[0-9a-f]+)*");

  enum Kind { CURRENT, RECORDED, CLOUD, UNKNOWN }
  enum Availability { MOUNTED, READ_ONLY, UNAVAILABLE, UNKNOWN }

  String display() {
    String place = kind == Kind.RECORDED ? "記録上は " + label : label;
    return switch (availability) {
      case READ_ONLY -> place + "（読み取り専用）";
      case UNAVAILABLE -> place + "（現在利用できません）";
      default -> place;
    };
  }

  private record Volume(String label, Availability availability) {}

  /** Capture storage state once per worker-side inventory, with no binder calls when binding rows. */
  static final class Volumes {
    private final Map<String, Volume> known = new HashMap<>();
    private final boolean readable;

    Volumes(Context context) {
      boolean gotVolumes = false;
      try {
        StorageManager manager = context.getSystemService(StorageManager.class);
        for (StorageVolume disk : manager.getStorageVolumes()) {
          String id = disk.getMediaStoreVolumeName();
          if (id == null) continue;
          String label = disk.getDescription(context);
          if (label == null || label.isBlank()) label = "外部ストレージ";
          Availability availability = switch (disk.getState()) {
            case Environment.MEDIA_MOUNTED -> Availability.MOUNTED;
            case Environment.MEDIA_MOUNTED_READ_ONLY -> Availability.READ_ONLY;
            default -> Availability.UNAVAILABLE;
          };
          String suffix = id.toUpperCase(Locale.ROOT);
          known.put(id, new Volume(PhotoStorage.PRIMARY.equals(id) ? "内部ストレージ"
              : label + "（" + suffix + "）", availability));
        }
        gotVolumes = true;
      } catch (RuntimeException unavailable) {
        // Provider/volume work is best effort for display and does not change eligibility.
      }
      readable = gotVolumes;
    }

    PhotoStorageLocation location(Kind kind, String volume, String folder) {
      if (!concrete(volume)) return unknown();
      Volume disk = known.get(volume);
      String label = disk == null ? fallbackLabel(volume) : disk.label();
      Availability availability = disk == null ? (readable ? Availability.UNAVAILABLE : Availability.UNKNOWN)
          : disk.availability();
      return new PhotoStorageLocation(kind, availability, volume, label, folder == null ? "" : folder);
    }
  }

  static PhotoStorageLocation unknown() {
    return new PhotoStorageLocation(Kind.UNKNOWN, Availability.UNKNOWN, null, "保存場所を確認できません", "");
  }

  private static PhotoStorageLocation cloud() {
    return new PhotoStorageLocation(Kind.CLOUD, Availability.UNKNOWN, null, "クラウド", "");
  }

  private static String fallbackLabel(String volume) {
    return PhotoStorage.PRIMARY.equals(volume) ? "内部ストレージ"
        : "外部ストレージ（" + volume.toUpperCase(Locale.ROOT) + "）";
  }

  private static boolean concrete(String volume) {
    return volume != null && !MediaStore.VOLUME_EXTERNAL.equals(volume)
        && (PhotoStorage.PRIMARY.equals(volume) || EXTERNAL_VOLUME.matcher(volume).matches());
  }

  static String recordedVolume(String reference) {
    if (!CameraImportHistoryPlan.validUri(reference)) return null;
    String volume = MediaStore.getVolumeName(Uri.parse(reference));
    return concrete(volume) ? volume : null;
  }

  /** Recheck the displayed original on a worker thread. Historical and opaque references may be unknown. */
  static PhotoStorageLocation resolve(Context context, PhotoRepository.Photo photo) {
    if (photo.isArchived()) return cloud();
    Uri uri = photo.uri;
    if (uri == null || !"content".equals(uri.getScheme())) return unknown();
    try {
      return switch (uri.getAuthority() == null ? "" : uri.getAuthority()) {
        case MediaStore.AUTHORITY -> resolveMedia(context, photo);
        case "com.android.externalstorage.documents" -> resolveDocument(context, photo);
        default -> unknown();
      };
    } catch (RuntimeException unavailable) {
      return unknown();
    }
  }

  private static PhotoStorageLocation resolveMedia(Context context, PhotoRepository.Photo photo) {
    if (ContentUris.parseId(photo.uri) < 0) return unknown();
    String referenceVolume = MediaStore.getVolumeName(photo.uri);
    boolean exact = concrete(referenceVolume);
    if (!exact && !MediaStore.VOLUME_EXTERNAL.equals(referenceVolume)) return unknown();
    Volumes volumes = new Volumes(context);
    PhotoStorageLocation recorded = exact ? volumes.location(Kind.RECORDED, referenceVolume, "") : unknown();
    Bundle options = new Bundle();
    options.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_EXCLUDE);
    options.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE);
    try (Cursor row = context.getContentResolver().query(photo.uri,
        new String[]{MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED}, options, null)) {
      if (row == null || row.getCount() != 1 || !row.moveToFirst()) return recorded;
      String volume = row.getString(0);
      String folder = row.getString(1);
      if ((exact && !referenceVolume.equals(volume)) || !photo.name.equals(row.getString(2))
          || photo.size != row.getLong(3) || !photo.folder.equals(folder)
          || (photo.modified > 0 && photo.modified != row.getLong(4))
          || (photo.added > 0 && photo.added != SyncPolicy.secondsToMillis(row.getLong(5)))) return recorded;
      return volumes.location(Kind.CURRENT, volume, folder);
    } catch (RuntimeException unavailable) {
      return recorded;
    }
  }

  private static PhotoStorageLocation resolveDocument(Context context, PhotoRepository.Photo photo) {
    String id = DocumentsContract.getDocumentId(photo.uri);
    int separator = id.indexOf(':');
    if (separator <= 0) return unknown();
    String prefix = id.substring(0, separator).toLowerCase(Locale.ROOT);
    String volume = "primary".equals(prefix) ? PhotoStorage.PRIMARY : prefix;
    if (!concrete(volume)) return unknown();
    String relative = id.substring(separator + 1);
    String folder = relative.substring(0, relative.lastIndexOf('/') + 1);
    Volumes volumes = new Volumes(context);
    PhotoStorageLocation recorded = volumes.location(Kind.RECORDED, volume, "");
    ContentResolver resolver = context.getContentResolver();
    try (Cursor row = resolver.query(photo.uri,
        new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE},
        null, null, null)) {
      if (row == null || row.getCount() != 1 || !row.moveToFirst() || !photo.name.equals(row.getString(0))
          || photo.size != row.getLong(1)) return recorded;
      return volumes.location(Kind.CURRENT, volume, folder);
    } catch (RuntimeException unavailable) {
      return recorded;
    }
  }
}
