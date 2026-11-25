package jp.nano.rawgallery;

import android.content.Context;
import android.content.SharedPreferences;

/** Access to the existing shared settings file and its synchronization keys. */
final class SettingsRepository {
  private static final String FILE = "settings";

  static final String SYNC_ENABLED = "syncEnabled";
  static final String CLEANUP_ENABLED = "cleanupEnabled";
  static final String RETENTION_DAYS = "retentionDays";
  static final String SYNC_STATUS = "syncStatus";
  static final String SYNC_STATUS_AT = "syncStatusAt";
  static final String SYNC_FINISHED_AT = "syncFinishedAt";
  static final String CLOUD_USER_AGENT = "cloudUserAgent";
  static final String CLOUD_VERIFICATION_RESULT = "cloudVerificationResult";
  static final String CLOUD_CHECKED_AT = "cloudCheckedAt";
  static final String CLOUD_COUNT = "cloudCount";
  static final int DEFAULT_RETENTION_DAYS = 7;

  private SettingsRepository() {}

  static SharedPreferences open(Context context) {
    return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
  }

  static boolean syncEnabled(SharedPreferences settings) {
    return settings.getBoolean(SYNC_ENABLED, false);
  }

  static boolean cleanupEnabled(SharedPreferences settings) {
    return settings.getBoolean(CLEANUP_ENABLED, false);
  }

  static int retentionDays(SharedPreferences settings) {
    return settings.getInt(RETENTION_DAYS, DEFAULT_RETENTION_DAYS);
  }

  static String syncStatus(SharedPreferences settings) {
    return settings.getString(SYNC_STATUS, "まだ同期していません");
  }

  static long syncFinishedAt(SharedPreferences settings) {
    return settings.getLong(SYNC_FINISHED_AT, 0);
  }

  static String cloudUserAgent(SharedPreferences settings, String fallback) {
    return settings.getString(CLOUD_USER_AGENT, fallback);
  }

  static String cloudVerificationResult(SharedPreferences settings) {
    return settings.getString(CLOUD_VERIFICATION_RESULT, "");
  }
}
