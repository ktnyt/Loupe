package jp.nano.rawgallery.camera;

oneway interface ISdCardRemountCallback {
    const int MOUNTED = 0;
    const int SKIPPED = 1;
    const int FAILED = 3;
    void onResult(int result);
}
