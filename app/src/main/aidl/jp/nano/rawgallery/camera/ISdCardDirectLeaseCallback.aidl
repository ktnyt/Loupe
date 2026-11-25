package jp.nano.rawgallery.camera;

interface ISdCardDirectLeaseCallback {
    const int TRANSFER_OK = 0;
    const int FAILED_CLOSED = 1;
    const int OWNERSHIP_UNKNOWN = 2;

    const int SKIPPED = 0;
    const int TRANSFERRED = 1;
    const int FAILED_RESTORED = 2;
    const int RECOVERY_PENDING = 3;

    // Return true only after durably recording this token and its two slot UUIDs.
    boolean persist(String recoveryToken, String sourceUuid, String destinationUuid);
    // Return TRANSFER_OK or FAILED_CLOSED only after all USB handles have been closed.
    int transfer(String recoveryToken);
    // Called after Android has restored both original volumes; clear only this token.
    boolean clear(String recoveryToken);
    void onFinished(int outcome);
}
