package jp.nano.rawgallery.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import android.os.storage.*;
import android.provider.DocumentsContract;
import java.io.File;
import java.net.Inet4Address;

/** Read source identifiers only when they can be bound to the actual selected connection. */
public final class CameraSourceIdentity {
  private CameraSourceIdentity() {}

  public static String wifi(Context context, Network selected) {
    if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) return null;
    try {
      WifiManager manager =
          (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
      WifiInfo info = manager == null ? null : manager.getConnectionInfo();
      LinkProperties links =
          context.getSystemService(ConnectivityManager.class).getLinkProperties(selected);
      if (info == null || links == null || info.getIpAddress() == 0) return null;
      int reported = info.getIpAddress();
      boolean same = false;
      for (LinkAddress link : links.getLinkAddresses())
        if (link.getAddress() instanceof Inet4Address) {
          byte[] ip = link.getAddress().getAddress();
          int address =
              (ip[0] & 255) | ((ip[1] & 255) << 8) | ((ip[2] & 255) << 16) | ((ip[3] & 255) << 24);
          if (address == reported && (ip[0] & 255) == 192 && (ip[1] & 255) == 168 && ip[2] == 0)
            same = true;
        }
      return same ? CameraSourceKey.wifi(info.getSSID(), info.getBSSID()) : null;
    } catch (RuntimeException unavailable) {
      return null;
    }
  }

  public static String sd(Context context, Uri tree) {
    // A third-party provider's document IDs cannot prove membership in a physical volume.
    if (tree == null
        || !"com.android.externalstorage.documents".equals(tree.getAuthority())
        || !DocumentsContract.isTreeUri(tree)) return null;
    try {
      String uuid = DocumentsContract.getTreeDocumentId(tree).split(":", 2)[0];
      if (CameraSourceKey.sd(uuid, 1) == null) return null;
      StorageVolume found = null;
      for (StorageVolume volume :
          context.getSystemService(StorageManager.class).getStorageVolumes()) {
        if (volume.getUuid() != null
            && volume.getUuid().equalsIgnoreCase(uuid)
            && volume.isRemovable()
            && !volume.isEmulated()) {
          if (found != null) return null;
          found = volume;
        }
      }
      if (found == null
          || !(Environment.MEDIA_MOUNTED.equals(found.getState())
              || Environment.MEDIA_MOUNTED_READ_ONLY.equals(found.getState()))) return null;
      File directory = found.getDirectory();
      if (directory == null) return null;
      long bytes = new StatFs(directory.getAbsolutePath()).getTotalBytes();
      return CameraSourceKey.sd(uuid, bytes);
    } catch (RuntimeException unavailable) {
      return null;
    }
  }
}
