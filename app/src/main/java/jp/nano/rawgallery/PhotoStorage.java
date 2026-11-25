package jp.nano.rawgallery;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Selects one concrete MediaStore volume for a write operation. */
public final class PhotoStorage {
  public static final String PRIMARY = MediaStore.VOLUME_EXTERNAL_PRIMARY;
  private static final String PREFERENCE = "photo_volume";

  private PhotoStorage() {}

  public static String selected(Context context) {
    return SettingsRepository.open(context).getString(PREFERENCE, PRIMARY);
  }

  static boolean select(Context context, String volume) {
    return SettingsRepository.open(context).edit().putString(PREFERENCE, volume).commit();
  }

  static List<StorageVolume> volumes(Context context) {
    List<StorageVolume> out = new ArrayList<>();
    for (StorageVolume volume : context.getSystemService(StorageManager.class).getStorageVolumes()) {
      if (volume.getMediaStoreVolumeName() != null
          && (volume.isPrimary() || volume.isRemovable() && !volume.isEmulated())) out.add(volume);
    }
    return out;
  }

  static String label(Context context, String volume) {
    if (PRIMARY.equals(volume)) return "内部ストレージ";
    List<StorageVolume> available = volumes(context);
    for (StorageVolume candidate : available) {
      if (!volume.equals(candidate.getMediaStoreVolumeName())) continue;
      String description = candidate.getDescription(context);
      if (description == null || description.isEmpty()) break;
      long sameLabel = available.stream().filter(item -> description.equals(item.getDescription(context))).count();
      return sameLabel > 1 ? description + "（" + volume.toUpperCase(java.util.Locale.ROOT) + "）" : description;
    }
    return "SDカード（" + volume.toUpperCase(java.util.Locale.ROOT) + "）";
  }

  static File directoryIfWritable(Context context, String name) {
    if (!MediaStore.getExternalVolumeNames(context).contains(name)) return null;
    for (StorageVolume volume : volumes(context)) {
      if (name.equals(volume.getMediaStoreVolumeName())
          && Environment.MEDIA_MOUNTED.equals(volume.getState())) return volume.getDirectory();
    }
    return null;
  }

  public static class Unavailable extends IOException {
    Unavailable(String message) { super(message); }
  }

  public static File requireWritable(Context context, String volume) throws Unavailable {
    File directory = directoryIfWritable(context, volume);
    if (directory == null) throw new Unavailable(label(context, volume)
        + "に書き込めません。接続または保存先を確認してください。");
    return directory;
  }

  public static void requireSpace(Context context, String volume, long bytes) throws Unavailable {
    if (requireWritable(context, volume).getUsableSpace() < bytes)
      throw new Unavailable(label(context, volume) + "の空き容量が足りません。");
  }

  public static String volumeOf(Uri uri) {
    String volume = MediaStore.getVolumeName(uri);
    return MediaStore.VOLUME_EXTERNAL.equals(volume) ? PRIMARY : volume;
  }

  public static void requireMounted(Context context, Uri uri) throws Unavailable {
    if (!MediaStore.getExternalVolumeNames(context).contains(volumeOf(uri)))
      throw new Unavailable(label(context, volumeOf(uri)) + "を接続してください。");
  }
}
