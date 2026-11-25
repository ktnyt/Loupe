package jp.nano.rawgallery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SettingsRepositoryTest {
  @Test
  void readsValuesFromTheExistingKeys() {
    SharedPreferences settings =
        preferences(
            Map.of(
                "syncEnabled",
                true,
                "cleanupEnabled",
                true,
                "retentionDays",
                42,
                "syncStatus",
                "existing status",
                "syncFinishedAt",
                120L,
                "cloudUserAgent",
                "existing agent",
                "cloudVerificationResult",
                "SUCCESS"));

    assertTrue(SettingsRepository.syncEnabled(settings));
    assertTrue(SettingsRepository.cleanupEnabled(settings));
    assertEquals(42, SettingsRepository.retentionDays(settings));
    assertEquals("existing status", SettingsRepository.syncStatus(settings));
    assertEquals(120L, SettingsRepository.syncFinishedAt(settings));
    assertEquals("existing agent", SettingsRepository.cloudUserAgent(settings, "fallback"));
    assertEquals("SUCCESS", SettingsRepository.cloudVerificationResult(settings));
  }

  @Test
  void keepsEachExistingFallback() {
    SharedPreferences settings = preferences(Map.of());
    assertFalse(SettingsRepository.syncEnabled(settings));
    assertFalse(SettingsRepository.cleanupEnabled(settings));
    assertEquals(7, SettingsRepository.retentionDays(settings));
    assertEquals("まだ同期していません", SettingsRepository.syncStatus(settings));
    assertEquals(0L, SettingsRepository.syncFinishedAt(settings));
    assertEquals("platform agent", SettingsRepository.cloudUserAgent(settings, "platform agent"));
    assertEquals("", SettingsRepository.cloudUserAgent(settings, ""));
    assertEquals("", SettingsRepository.cloudVerificationResult(settings));
  }

  @Test
  void keepsStrictTypesAndDoesNotNormalizeStoredValues() {
    SharedPreferences invalid = preferences(Map.of("cleanupEnabled", "true", "retentionDays", 30L));
    assertThrows(ClassCastException.class, () -> SettingsRepository.cleanupEnabled(invalid));
    assertThrows(ClassCastException.class, () -> SettingsRepository.retentionDays(invalid));
    assertEquals(366, SettingsRepository.retentionDays(preferences(Map.of("retentionDays", 366))));
  }

  private static SharedPreferences preferences(Map<String, Object> data) {
    return (SharedPreferences)
        Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(),
            new Class<?>[] {SharedPreferences.class},
            (proxy, method, args) -> {
              Class<?> type =
                  switch (method.getName()) {
                    case "getBoolean" -> Boolean.class;
                    case "getInt" -> Integer.class;
                    case "getLong" -> Long.class;
                    case "getString" -> String.class;
                    default -> throw new AssertionError(method.getName());
                  };
              return type.cast(data.getOrDefault(args[0], args[1]));
            });
  }
}
