package jp.nano.rawgallery;

import android.content.Context;
import android.content.SharedPreferences;

/** Access to the existing shared settings file and its synchronization keys. */
final class SettingsRepository {
  private static final String FILE = "settings";

  static final String RETENTION_DAYS = "retentionDays";
  static final int DEFAULT_RETENTION_DAYS = 7;

  private SettingsRepository() {}

  static SharedPreferences open(Context context) {
    return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
  }

  static int retentionDays(SharedPreferences settings) {
    return settings.getInt(RETENTION_DAYS, DEFAULT_RETENTION_DAYS);
  }
}
