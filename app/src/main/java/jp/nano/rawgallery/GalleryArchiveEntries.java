package jp.nano.rawgallery;

import android.content.Context;
import android.net.Uri;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import jp.nano.rawgallery.PhotoRepository.Photo;
import org.json.JSONException;
import org.json.JSONObject;

/** Reconciles retained Amazon and E2 archive entries with the gallery's resolved local identities. */
final class GalleryArchiveEntries {
  private GalleryArchiveEntries() {}

  /** Merges archive rows into a scan after the caller has resolved moved local photos. */
  static void appendTo(Context context, Map<String, Photo> result) throws IOException {
    Set<String> localArchiveKeys = new HashSet<>();
    for (Photo photo : result.values()) {
      localArchiveKeys.add(ArchiveStore.key(photo));
    }
    Map<String, String> amazonHashes = new HashMap<>();
    try (ArchiveStore store = new ArchiveStore(context)) {
      for (JSONObject record : store.all())
        if (record.has("sha")) {
          String path = archivePath(record);
          if (!result.containsKey(path) && !localArchiveKeys.contains(record.getString("key"))) {
            result.put(path, store.cloudPhoto(record));
            amazonHashes.put(path, record.getString("sha"));
          }
        }
    } catch (Exception e) {
      throw new IOException("クラウドオリジナルの記録を読めません", e);
    }
    try (ArchiveStore archive = E2Settings.openRecords(context)) {
      for (JSONObject record : archive.inStates(E2Cleanup.RELEASING, E2Cleanup.RELEASED)) {
        String path = archivePath(record);
        Photo local = result.get(path);
        if (local != null && !local.isArchived() && E2Cleanup.releasing(record)) continue;
        if (record.getString("sha").equals(amazonHashes.get(path))) result.remove(path);
        Photo cloud = E2Cleanup.galleryPhoto(record);
        result.put(cloud.uri.toString(), cloud);
      }
    } catch (Exception e) {
      throw new IOException("IDrive e2の保管記録を読めません", e);
    }
  }

  private static String archivePath(JSONObject record) throws JSONException {
    return PhotoIdentityIndex.path(Uri.parse(record.optString("uri")), record.getString("folder"), record.getString("name"));
  }
}
