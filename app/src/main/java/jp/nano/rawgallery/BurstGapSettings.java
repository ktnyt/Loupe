package jp.nano.rawgallery;

import android.content.SharedPreferences;

final class BurstGapSettings {
  static final String MILLIS = "burstGapMillis";
  static final String LEGACY_SECONDS = "burstGapSeconds";

  private BurstGapSettings() {}

  static synchronized int readMillis(SharedPreferences settings) {
    int stored = settings.getInt(MILLIS, -1);
    if (stored >= 0) return stored;
    int seconds = settings.getInt(LEGACY_SECONDS, 1);
    return seconds >= 0 && seconds <= 3 ? seconds * 1000 : 1000;
  }

  static synchronized boolean saveMillis(SharedPreferences settings, int millis) {
    int previous = settings.getInt(MILLIS, -1);
    if (settings.edit().putInt(MILLIS, millis).commit()) return true;

    // SharedPreferences updates its memory before attempting the disk write.
    // Restore the previous value before an index worker can read the failed draft.
    SharedPreferences.Editor rollback = settings.edit();
    if (previous == -1) rollback.remove(MILLIS);
    else rollback.putInt(MILLIS, previous);
    rollback.apply();
    return false;
  }

  /** Returns zero for invalid custom input; zero is reserved for the off preset. */
  static int parseCustomMillis(String raw) {
    String value = raw.trim();
    if (!value.matches("[0-9]+")) return 0;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException outOfRange) {
      return 0;
    }
  }
}
