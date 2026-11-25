package jp.nano.rawgallery.camera;

import jp.nano.rawgallery.camera.ISdCardRemountCallback;
import jp.nano.rawgallery.camera.ISdCardDirectLeaseCallback;

oneway interface ISdCardRemountService {
    void remount(String sourceUuid, String destinationUuid, long deadlineMs, ISdCardRemountCallback callback) = 0;
    void reconnect(String address, String destinationUuid, long deadlineMs, ISdCardRemountCallback callback) = 1;
    void leaseDirect(String address, String sdUuid, String microSdUuid, long deadlineMs,
            ISdCardDirectLeaseCallback callback) = 2;
    // Shizuku's UserService stop transaction.
    void destroy() = 16777114;
}
