package jp.nano.rawgallery;

import android.content.Context;

/** A paused photo move must finish updating both histories before import deduplication. */
public final class ImportHistoryGuard {
  private ImportHistoryGuard() {}

  public static void requireReady(Context context) throws PhotoStorage.Unavailable {
    try (PhotoMoveStore store = new PhotoMoveStore(context)) {
      if (store.historyUpdatePending()) {
        throw new PhotoStorage.Unavailable("写真・動画の保存先から移動を再開してください。");
      }
    }
  }
}
